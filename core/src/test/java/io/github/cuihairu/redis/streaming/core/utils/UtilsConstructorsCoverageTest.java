package io.github.cuihairu.redis.streaming.core.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Covers utility constructors and hostname cache/clear flow. */
class UtilsConstructorsCoverageTest {

    @Test
    void systemUtilsIsInstantiable() {
        assertNotNull(new SystemUtils());
    }

    @Test
    void instanceIdGeneratorIsInstantiable() {
        assertNotNull(new InstanceIdGenerator());
        assertNotNull(InstanceIdGenerator.generateInstanceId("svc", 1234));
    }

    @Test
    void localHostnameCachesAndClears() {
        assertNotNull(SystemUtils.getLocalHostname());
        assertNotNull(SystemUtils.getLocalHostname());
        SystemUtils.clearHostnameCache();
        assertNotNull(SystemUtils.getLocalHostname());
    }
}
