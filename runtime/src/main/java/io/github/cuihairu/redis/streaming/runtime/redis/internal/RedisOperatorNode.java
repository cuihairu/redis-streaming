package io.github.cuihairu.redis.streaming.runtime.redis.internal;

/**
 * One operator node in a Redis runtime pipeline.
 */
@FunctionalInterface
public interface RedisOperatorNode {

    void process(Object value, RedisPipelineRunner.Context ctx, RedisPipelineRunner.Emitter emit) throws Exception;
}

