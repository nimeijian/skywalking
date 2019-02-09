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

package org.skywalking.apm.agent.core.context.ids;

import org.skywalking.apm.agent.core.conf.RemoteDownstreamConfig;
import org.skywalking.apm.agent.core.dictionary.DictionaryUtil;

import java.util.Random;

/**
 * 全局编号生成器
 */
public final class GlobalIdGenerator {

    /**
     * 线程变量，编号上下文
     */
    private static final ThreadLocal<IDContext> THREAD_ID_SEQUENCE = new ThreadLocal<IDContext>() {
        @Override
        protected IDContext initialValue() {
            return new IDContext(System.currentTimeMillis(), (short) 0);
        }
    };

    private GlobalIdGenerator() {
    }

    /**
     * Generate a new id, combined by three long numbers.
     *
     * The first one represents application instance id. (most likely just an integer value, would be helpful in
     * protobuf)
     *
     * The second one represents thread id. (most likely just an integer value, would be helpful in protobuf)
     *
     * The third one also has two parts,<br/>
     * 1) a timestamp, measured in milliseconds<br/>
     * 2) a seq, in current thread, between 0(included) and 9999(included)
     *
     * Notice, a long costs 8 bytes, three longs cost 24 bytes. And at the same time, a char costs 2 bytes. So
     * sky-walking's old global and segment id like this: "S.1490097253214.-866187727.57515.1.1" which costs at least 72
     * bytes.
     *
     * @return an array contains three long numbers, which represents a unique id.
     */
    public static ID generate() {
        if (RemoteDownstreamConfig.Agent.APPLICATION_INSTANCE_ID == DictionaryUtil.nullValue()) {
            throw new IllegalStateException();
        }
        // 获得 编号上下文
        IDContext context = THREAD_ID_SEQUENCE.get();
        // 生成编号
        return new ID(
            RemoteDownstreamConfig.Agent.APPLICATION_INSTANCE_ID, // 应用实例编号
            Thread.currentThread().getId(), // 线程编号
            context.nextSeq() // 带时间戳的序列号
        );
    }

    /**
     * 编号上下文
     */
    private static class IDContext {
        /**
         * 最后生成编号时间
         */
        private long lastTimestamp;
        /**
         * 线程自增序列号
         */
        private short threadSeq;

        // Just for considering time-shift-back only.
        private long runRandomTimestamp;
        private int lastRandomValue;
        private Random random;

        private IDContext(long lastTimestamp, short threadSeq) {
            this.lastTimestamp = lastTimestamp;
            this.threadSeq = threadSeq;
        }

        /**
         * @return 生成序列号
         */
        private long nextSeq() {
            return timestamp() * 10000 + nextThreadSeq();
        }

        /**
         * @return 获得时间
         */
        private long timestamp() {
            long currentTimeMillis = System.currentTimeMillis();
            // 处理时间回退，使用随机数作为时间戳
            if (currentTimeMillis < lastTimestamp) {
                // Just for considering time-shift-back by Ops or OS. @hanahmily 's suggestion.
                if (random == null) {
                    random = new Random();
                }
                // 使用随机数作为时间戳。该判断用于，每毫秒，只随机生成一次随机数。
                if (runRandomTimestamp != currentTimeMillis) {
                    lastRandomValue = random.nextInt();
                    runRandomTimestamp = currentTimeMillis;
                }
                return lastRandomValue;
            // 当前时间
            } else {
                lastTimestamp = currentTimeMillis;
                return lastTimestamp;
            }
        }

        /**
         * @return 生成自增序列号
         */
        private short nextThreadSeq() {
            if (threadSeq == 10000) {
                threadSeq = 0;
            }
            return threadSeq++;
        }

    }

}