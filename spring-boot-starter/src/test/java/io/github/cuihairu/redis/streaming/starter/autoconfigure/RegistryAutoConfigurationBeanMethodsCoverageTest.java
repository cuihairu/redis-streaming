package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.registry.NamingService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/** Direct coverage for the registry auto-configuration bean factory methods. */
class RegistryAutoConfigurationBeanMethodsCoverageTest {

    private final RedisStreamingRegistryAutoConfiguration cfg = new RedisStreamingRegistryAutoConfiguration();

    @Test
    void serviceChangeListenerProcessorIsWired() {
        NamingService namingService = mock(NamingService.class);
        assertNotNull(cfg.serviceChangeListenerProcessor(namingService));
    }

}
