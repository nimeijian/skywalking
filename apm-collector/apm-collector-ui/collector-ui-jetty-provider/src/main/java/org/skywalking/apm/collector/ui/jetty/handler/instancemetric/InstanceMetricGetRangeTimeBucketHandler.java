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

package org.skywalking.apm.collector.ui.jetty.handler.instancemetric;

import com.google.gson.JsonElement;
import org.skywalking.apm.collector.core.module.ModuleManager;
import org.skywalking.apm.collector.server.jetty.ArgumentsParseException;
import org.skywalking.apm.collector.server.jetty.JettyHandler;
import org.skywalking.apm.collector.ui.service.InstanceJVMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 获得应用实例指定时间范围内的 Metric 信息
 *
 * @author peng-yongsheng
 */
public class InstanceMetricGetRangeTimeBucketHandler extends JettyHandler {

    private final Logger logger = LoggerFactory.getLogger(InstanceMetricGetRangeTimeBucketHandler.class);

    @Override public String pathSpec() {
        return "/instance/jvm/instanceId/rangeBucket";
    }

    private final InstanceJVMService service;

    public InstanceMetricGetRangeTimeBucketHandler(ModuleManager moduleManager) {
        this.service = new InstanceJVMService(moduleManager);
    }

    @Override protected JsonElement doGet(HttpServletRequest req) throws ArgumentsParseException {
        String startTimeBucketStr = req.getParameter("startTimeBucket");
        String endTimeBucketStr = req.getParameter("endTimeBucket");
        String instanceIdStr = req.getParameter("instanceId");
        String[] metricTypes = req.getParameterValues("metricTypes");

        logger.debug("instance jvm metric get start timeBucket: {}, end timeBucket:{} , instance id: {}, metric types: {}", startTimeBucketStr, endTimeBucketStr, instanceIdStr, metricTypes);

        // 解析 startTimeBucket
        long startTimeBucket;
        try {
            startTimeBucket = Long.parseLong(startTimeBucketStr);
        } catch (NumberFormatException e) {
            throw new ArgumentsParseException("start timeBucket must be long");
        }

        // 解析 endTimeBucket
        long endTimeBucket;
        try {
            endTimeBucket = Long.parseLong(endTimeBucketStr);
        } catch (NumberFormatException e) {
            throw new ArgumentsParseException("end timeBucket must be long");
        }

        // 解析 instanceId
        int instanceId;
        try {
            instanceId = Integer.parseInt(instanceIdStr);
        } catch (NumberFormatException e) {
            throw new ArgumentsParseException("instance id must be integer");
        }

        if (metricTypes.length == 0) {
            throw new ArgumentsParseException("at least one metric type");
        }

        // 解析 metricTypes 数组
        Set<String> metricTypeSet = new LinkedHashSet<>();
        for (String metricType : metricTypes) {
            metricTypeSet.add(metricType);
        }

        return service.getInstanceJvmMetrics(instanceId, metricTypeSet, startTimeBucket, endTimeBucket);
    }

    @Override protected JsonElement doPost(HttpServletRequest req) throws ArgumentsParseException {
        throw new UnsupportedOperationException();
    }
}
