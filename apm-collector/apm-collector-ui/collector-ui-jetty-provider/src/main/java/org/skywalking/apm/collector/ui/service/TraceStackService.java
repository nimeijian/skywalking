/*
 * Copyright 2017, OpenSkywalking Organization All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Project repository: https://github.com/OpenSkywalking/skywalking
 */

package org.skywalking.apm.collector.ui.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.skywalking.apm.collector.cache.CacheModule;
import org.skywalking.apm.collector.cache.service.ApplicationCacheService;
import org.skywalking.apm.collector.cache.service.ServiceNameCacheService;
import org.skywalking.apm.collector.core.module.ModuleManager;
import org.skywalking.apm.collector.core.util.CollectionUtils;
import org.skywalking.apm.collector.core.util.Const;
import org.skywalking.apm.collector.core.util.ObjectUtils;
import org.skywalking.apm.collector.core.util.StringUtils;
import org.skywalking.apm.collector.storage.StorageModule;
import org.skywalking.apm.collector.storage.dao.IGlobalTraceUIDAO;
import org.skywalking.apm.collector.storage.dao.ISegmentUIDAO;
import org.skywalking.apm.network.proto.SpanObject;
import org.skywalking.apm.network.proto.TraceSegmentObject;
import org.skywalking.apm.network.proto.TraceSegmentReference;
import org.skywalking.apm.network.proto.UniqueId;

import java.util.ArrayList;
import java.util.List;

/**
 * @author peng-yongsheng
 */
public class TraceStackService {

    private final IGlobalTraceUIDAO globalTraceDAO;
    private final ISegmentUIDAO segmentDAO;
    private final ApplicationCacheService applicationCacheService;
    private final ServiceNameCacheService serviceNameCacheService;

    public TraceStackService(ModuleManager moduleManager) {
        this.globalTraceDAO = moduleManager.find(StorageModule.NAME).getService(IGlobalTraceUIDAO.class);
        this.segmentDAO = moduleManager.find(StorageModule.NAME).getService(ISegmentUIDAO.class);
        this.applicationCacheService = moduleManager.find(CacheModule.NAME).getService(ApplicationCacheService.class);
        this.serviceNameCacheService = moduleManager.find(CacheModule.NAME).getService(ServiceNameCacheService.class);
    }

    public JsonArray load(String globalTraceId) {
        // 查询 globalTraceId 的 TraceSegment 编号数组
        List<String> segmentIds = globalTraceDAO.getSegmentIds(globalTraceId);

        // 获得 TraceSegment 数组，从而组装出 Span 数组
        List<Span> spans = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(segmentIds)) {
            for (String segmentId : segmentIds) {
                TraceSegmentObject segment = segmentDAO.load(segmentId); // 获得 TraceSegment
                if (ObjectUtils.isNotEmpty(segment)) {
                    // 构建 TraceSegment 对应的 Span 数组
                    spans.addAll(buildSpanList(segmentId, segment));
                }
            }
        }

        List<Span> sortedSpans = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(spans)) {
            // 获得所有根 Span 数组
            List<Span> rootSpans = findRoot(spans);

            if (CollectionUtils.isNotEmpty(rootSpans)) {
                // 循环根 Span 数组，设置其子 Span 数组
                rootSpans.forEach(span -> {
                    // 以 Span 为根节点
                    List<Span> childrenSpan = new ArrayList<>(); // [parentSpanA, childSpanA1, childSpanA2 ... parentSpanB, childSpanB1]
                    childrenSpan.add(span);

                    // 寻找子 Span 数组
                    findChildren(spans, span, childrenSpan);

                    // 添加到结果集
                    sortedSpans.addAll(childrenSpan);
                });
            }
        }

        // 压缩 startTime 字段
        minStartTime(sortedSpans);

        // 转换成 JSON 数组
        return toJsonArray(sortedSpans);
    }

    private JsonArray toJsonArray(List<Span> sortedSpans) {
        JsonArray traceStackArray = new JsonArray();
        sortedSpans.forEach(span -> {
            JsonObject spanJson = new JsonObject();
            spanJson.addProperty("spanId", span.getSpanId());
            spanJson.addProperty("parentSpanId", span.getParentSpanId());
            spanJson.addProperty("segmentSpanId", span.getSegmentSpanId());
            spanJson.addProperty("segmentParentSpanId", span.getSegmentParentSpanId());
            spanJson.addProperty("startTime", span.getStartTime());
            spanJson.addProperty("operationName", span.getOperationName());
            spanJson.addProperty("applicationCode", span.getApplicationCode());
            spanJson.addProperty("cost", span.getCost());
            spanJson.addProperty("isRoot", span.isRoot());
            traceStackArray.add(spanJson);
        });
        return traceStackArray;
    }

    /**
     * 压缩 startTime 字段，减少 IO ，也方面前端时间轴展示
     *
     * 压缩方式为，startTime - minStarTime
     *
     * @param spans spans
     */
    private void minStartTime(List<Span> spans) {
        // 获得 minStartTime
        long minStartTime = Long.MAX_VALUE;
        for (Span span : spans) {
            if (span.getStartTime() < minStartTime) {
                minStartTime = span.getStartTime();
            }
        }

        // 压缩
        for (Span span : spans) {
            span.setStartTime(span.getStartTime() - minStartTime);
        }
    }

    private List<Span> buildSpanList(String segmentId, TraceSegmentObject segment) {
        List<Span> spans = new ArrayList<>();
        if (segment.getSpansCount() > 0) {
            for (SpanObject spanObject : segment.getSpansList()) {
                // 自身 segmentSpanId
                int spanId = spanObject.getSpanId();
                String segmentSpanId = segmentId + Const.SEGMENT_SPAN_SPLIT + String.valueOf(spanId);

                // 父级 parentSpanId
                int parentSpanId = spanObject.getParentSpanId();
                String segmentParentSpanId = segmentId + Const.SEGMENT_SPAN_SPLIT + String.valueOf(parentSpanId);

                // 操作名
                String operationName = spanObject.getOperationName();
                if (spanObject.getOperationNameId() != 0) {
                    String serviceName = serviceNameCacheService.get(spanObject.getOperationNameId());
                    if (StringUtils.isNotEmpty(serviceName)) {
                        operationName = serviceName.split(Const.ID_SPLIT)[1];
                    } else {
                        operationName = Const.EMPTY_STRING;
                    }
                }

                // 应用编码
                String applicationCode = applicationCacheService.get(segment.getApplicationId());

                // 消耗时间
                long cost = spanObject.getEndTime() - spanObject.getStartTime();
                if (cost == 0) {
                    cost = 1;
                }

                // 开始时间
                long startTime = spanObject.getStartTime();

                // 第一个 Span ，并且有 TraceSegmentRef
                if (parentSpanId == -1 && segment.getRefsCount() > 0) {
                    // 循环 TraceSegmentRef 数组
                    for (TraceSegmentReference reference : segment.getRefsList()) {
                        // 父级 parentSpanId
                        parentSpanId = reference.getParentSpanId();
                        UniqueId uniqueId = reference.getParentTraceSegmentId();
                        StringBuilder segmentIdBuilder = new StringBuilder();
                        for (int i = 0; i < uniqueId.getIdPartsList().size(); i++) {
                            if (i == 0) {
                                segmentIdBuilder.append(String.valueOf(uniqueId.getIdPartsList().get(i)));
                            } else {
                                segmentIdBuilder.append(".").append(String.valueOf(uniqueId.getIdPartsList().get(i)));
                            }
                        }
                        String parentSegmentId = segmentIdBuilder.toString();
                        segmentParentSpanId = parentSegmentId + Const.SEGMENT_SPAN_SPLIT + String.valueOf(parentSpanId);

                        // 添加到 spans
                        spans.add(new Span(spanId, parentSpanId, segmentSpanId, segmentParentSpanId, startTime, operationName, applicationCode, cost));
                    }
                // 添加到 spans
                } else {
                    spans.add(new Span(spanId, parentSpanId, segmentSpanId, segmentParentSpanId, startTime, operationName, applicationCode, cost));
                }
            }
        }
        return spans;
    }

    /**
     * 获得 Root Span 集合
     *
     * @param spans spans
     * @return 集合
     */
    private List<Span> findRoot(List<Span> spans) {
        List<Span> rootSpans = new ArrayList<>();
        spans.forEach(span -> {
            String segmentParentSpanId = span.getSegmentParentSpanId();

            // 寻找父节点
            boolean hasParent = false;
            for (Span span1 : spans) {
                if (segmentParentSpanId.equals(span1.getSegmentSpanId())) {
                    hasParent = true;
                }
            }

            // 无父节点，设置为根节点，并且添加到结果集
            if (!hasParent) {
                span.setRoot(true);
                rootSpans.add(span);
            }
        });
        return rootSpans;
    }

    /**
     * 获得 Child Span 集合
     *
     * @param spans 循环的 spans
     * @param parentSpan 父 span
     * @param childrenSpan child spans ( 结果 )
     */
    private void findChildren(List<Span> spans, Span parentSpan, List<Span> childrenSpan) {
        spans.forEach(span -> {
            if (span.getSegmentParentSpanId().equals(parentSpan.getSegmentSpanId())) {
                childrenSpan.add(span);

                // 【递归】获得 Child Span 集合
                findChildren(spans, span, childrenSpan);
            }
        });
    }

    /**
     * Span 类
     */
    class Span {
        /**
         * 自己的 Span 编号
         */
        private int spanId;
        /**
         * 父级的 Span 编号
         */
        private int parentSpanId;
        /**
         * 自己的 SegmentId + SpanId
         */
        private String segmentSpanId;
        /**
         * 父级的 SegmentId + SpanId
         */
        private String segmentParentSpanId;
        /**
         * 开始时间
         *
         * 传输到 UI 层时，会被 {@link #minStartTime(List)} 压缩
         */
        private long startTime;
        /**
         * 操作名
         */
        private String operationName;
        /**
         * 应用编码
         */
        private String applicationCode;
        /**
         * 消耗时间
         */
        private long cost;
        /**
         * 是否根节点
         */
        private boolean isRoot = false;

        Span(int spanId, int parentSpanId, String segmentSpanId, String segmentParentSpanId, long startTime,
            String operationName, String applicationCode, long cost) {
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
            this.segmentSpanId = segmentSpanId;
            this.segmentParentSpanId = segmentParentSpanId;
            this.startTime = startTime;
            this.operationName = operationName;
            this.applicationCode = applicationCode;
            this.cost = cost;
        }

        int getSpanId() {
            return spanId;
        }

        int getParentSpanId() {
            return parentSpanId;
        }

        String getSegmentSpanId() {
            return segmentSpanId;
        }

        String getSegmentParentSpanId() {
            return segmentParentSpanId;
        }

        long getStartTime() {
            return startTime;
        }

        String getOperationName() {
            return operationName;
        }

        String getApplicationCode() {
            return applicationCode;
        }

        long getCost() {
            return cost;
        }

        public boolean isRoot() {
            return isRoot;
        }

        public void setRoot(boolean root) {
            isRoot = root;
        }

        public void setStartTime(long startTime) {
            this.startTime = startTime;
        }
    }
}
