package io.github.cuihairu.redis.streaming.runtime.redis.metrics;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test collector that throws on selected metric methods so the surrounding
 * {@code catch (Exception ignore)} defensive branches in the runtime are exercised.
 */
public final class SelectiveFailingMetricsCollector implements RedisRuntimeMetricsCollector {

    private final Set<String> failing = ConcurrentHashMap.newKeySet();

    public SelectiveFailingMetricsCollector failOn(String... methods) {
        failing.addAll(Set.of(methods));
        return this;
    }

    private void maybeFail(String method) {
        if (failing.contains(method)) {
            throw new IllegalStateException("metrics failure: " + method);
        }
    }

    @Override
    public void incJobStarted(String jobName) {
        maybeFail("incJobStarted");
    }

    @Override
    public void incJobCanceled(String jobName) {
        maybeFail("incJobCanceled");
    }

    @Override
    public void incPipelineStarted(String jobName, String topic, String consumerGroup) {
        maybeFail("incPipelineStarted");
    }

    @Override
    public void incPipelineStartFailed(String jobName, String topic, String consumerGroup) {
        maybeFail("incPipelineStartFailed");
    }

    @Override
    public void incHandleSuccess(String jobName, String topic, String consumerGroup) {
        maybeFail("incHandleSuccess");
    }

    @Override
    public void incHandleError(String jobName, String topic, String consumerGroup) {
        maybeFail("incHandleError");
    }

    @Override
    public void recordHandleLatency(String jobName, String topic, String consumerGroup, long millis) {
        maybeFail("recordHandleLatency");
    }

    @Override
    public void recordKeyedStateSize(String jobName, String topic, String consumerGroup,
                                     String operatorId, String stateName, int partitionId, long fields) {
        maybeFail("recordKeyedStateSize");
    }

    @Override
    public void incCheckpointTriggered(String jobName) {
        maybeFail("incCheckpointTriggered");
    }

    @Override
    public void incCheckpointCompleted(String jobName) {
        maybeFail("incCheckpointCompleted");
    }

    @Override
    public void incCheckpointFailed(String jobName) {
        maybeFail("incCheckpointFailed");
    }

    @Override
    public void recordCheckpointDuration(String jobName, long millis) {
        maybeFail("recordCheckpointDuration");
    }

    @Override
    public void recordCheckpointDrainDuration(String jobName, long millis) {
        maybeFail("recordCheckpointDrainDuration");
    }

    @Override
    public void recordCheckpointStoreDuration(String jobName, long millis) {
        maybeFail("recordCheckpointStoreDuration");
    }

    @Override
    public void recordCheckpointSinkCommitDuration(String jobName, long millis) {
        maybeFail("recordCheckpointSinkCommitDuration");
    }

    @Override
    public void incKeyedStateRead(String jobName, String topic, String consumerGroup,
                                  String operatorId, String stateName, int partitionId) {
        maybeFail("incKeyedStateRead");
    }

    @Override
    public void incKeyedStateWrite(String jobName, String topic, String consumerGroup,
                                   String operatorId, String stateName, int partitionId) {
        maybeFail("incKeyedStateWrite");
    }

    @Override
    public void incKeyedStateDelete(String jobName, String topic, String consumerGroup,
                                    String operatorId, String stateName, int partitionId) {
        maybeFail("incKeyedStateDelete");
    }

    @Override
    public void recordKeyedStateReadLatency(String jobName, String topic, String consumerGroup,
                                            String operatorId, String stateName, int partitionId, long millis) {
        maybeFail("recordKeyedStateReadLatency");
    }

    @Override
    public void recordKeyedStateWriteLatency(String jobName, String topic, String consumerGroup,
                                             String operatorId, String stateName, int partitionId, long millis) {
        maybeFail("recordKeyedStateWriteLatency");
    }

    @Override
    public void setEventTimeTimerQueueSize(String jobName, String topic, String consumerGroup, int size) {
        maybeFail("setEventTimeTimerQueueSize");
    }

    @Override
    public void setWatermarkMs(String jobName, String topic, String consumerGroup, long watermarkMs) {
        maybeFail("setWatermarkMs");
    }

    @Override
    public void incWindowLateDropped(String jobName, String topic, String consumerGroup,
                                     String operatorId, String windowName, int partitionId) {
        maybeFail("incWindowLateDropped");
    }

    @Override
    public void incWindowFired(String jobName, String topic, String consumerGroup,
                               String operatorId, String windowName, int partitionId) {
        maybeFail("incWindowFired");
    }

    @Override
    public void incKeyedStateHotKey(String jobName, String topic, String consumerGroup,
                                    String operatorId, String stateName, int partitionId, long fields) {
        maybeFail("incKeyedStateHotKey");
    }
}
