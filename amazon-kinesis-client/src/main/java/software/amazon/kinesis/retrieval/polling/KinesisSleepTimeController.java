/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package software.amazon.kinesis.retrieval.polling;

import java.time.Duration;
import java.time.Instant;

import software.amazon.kinesis.annotations.KinesisClientInternalApi;

@KinesisClientInternalApi
public class KinesisSleepTimeController implements SleepTimeController {
    @Override
    public long getSleepTimeMillis(SleepTimeControllerConfig sleepTimeControllerConfig) {
        Instant lastSuccessfulCall = sleepTimeControllerConfig.lastSuccessfulCall();
        long idleMillisBetweenCalls = sleepTimeControllerConfig.idleMillisBetweenCalls();
        if (lastSuccessfulCall == null) {
            return idleMillisBetweenCalls;
        }
        long timeSinceLastCall =
                Duration.between(lastSuccessfulCall, Instant.now()).abs().toMillis();
        long idleSleepTime = 0;
        if (timeSinceLastCall < idleMillisBetweenCalls) {
            idleSleepTime = idleMillisBetweenCalls - timeSinceLastCall;
        }
        long reducedTpsSleepTime = 0;
        Long lastMillisBehindLatest = sleepTimeControllerConfig.lastMillisBehindLatest();
        Long millisBehindThreshold = sleepTimeControllerConfig.millisBehindLatestThresholdForReducedTps();
        if (lastMillisBehindLatest != null
                && millisBehindThreshold != null
                && lastMillisBehindLatest < millisBehindThreshold) {
            reducedTpsSleepTime = millisBehindThreshold - timeSinceLastCall;
        }

        return Math.max(idleSleepTime, reducedTpsSleepTime);
    }
}
