package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.ConfigServiceConfig;
import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Error-path storms for the config-center implementations (armed/constructing phases). */
class ConfigErrorPathStormTest {

    @Test
    void configServiceStormHappyAndFailing() {
        RedisConfigService happy = Storms.constructing(
                () -> new RedisConfigService(Storms.deep(org.redisson.api.RedissonClient.class), new ConfigServiceConfig("storm-cfg", true)));
        assertTrue(Storms.storm(happy, null) > 5);

        RedisConfigService failing = Storms.constructing(
                () -> new RedisConfigService(Storms.exploding(org.redisson.api.RedissonClient.class), new ConfigServiceConfig("storm-cfg", true)));
        assertTrue(Storms.storm(failing, null) > 5);
    }
}
