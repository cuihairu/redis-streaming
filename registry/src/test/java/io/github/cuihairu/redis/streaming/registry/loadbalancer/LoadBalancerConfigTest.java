package io.github.cuihairu.redis.streaming.registry.loadbalancer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LoadBalancerConfig
 */
class LoadBalancerConfigTest {

    private LoadBalancerConfig config;

    @BeforeEach
    void setUp() {
        config = new LoadBalancerConfig();
    }

    @Test
    void testDefaultValues() {
        assertEquals(1.0, config.getBaseWeightFactor(), 0.001);
        assertEquals(1.0, config.getCpuWeight(), 0.001);
        assertEquals(1.0, config.getLatencyWeight(), 0.001);
        assertEquals(50.0, config.getTargetLatencyMs(), 0.001);
        assertNull(config.getPreferredRegion());
        assertNull(config.getPreferredZone());
        assertEquals(1.1, config.getRegionBoost(), 0.001);
        assertEquals(1.05, config.getZoneBoost(), 0.001);
        assertEquals("cpu", config.getCpuKey());
        assertEquals("latency", config.getLatencyKey());
        assertEquals(0.0, config.getMemoryWeight(), 0.001);
        assertEquals("memory", config.getMemoryKey());
        assertEquals(0.0, config.getInflightWeight(), 0.001);
        assertEquals("inflight", config.getInflightKey());
        assertEquals(0.0, config.getQueueWeight(), 0.001);
        assertEquals("queue", config.getQueueKey());
        assertEquals(0.0, config.getErrorRateWeight(), 0.001);
        assertEquals("errorRate", config.getErrorRateKey());
        assertEquals(-1, config.getMaxCpuPercent(), 0.001);
        assertEquals(-1, config.getMaxLatencyMs(), 0.001);
        assertEquals(-1, config.getMaxMemoryPercent(), 0.001);
        assertEquals(-1, config.getMaxInflight(), 0.001);
        assertEquals(-1, config.getMaxQueue(), 0.001);
        assertEquals(-1, config.getMaxErrorRatePercent(), 0.001);
    }

    @Test
    void testSetBaseWeightFactor() {
        config.setBaseWeightFactor(2.5);

        assertEquals(2.5, config.getBaseWeightFactor(), 0.001);
    }

    @Test
    void testSetCpuWeight() {
        config.setCpuWeight(3.0);

        assertEquals(3.0, config.getCpuWeight(), 0.001);
    }

    @Test
    void testSetLatencyWeight() {
        config.setLatencyWeight(2.5);

        assertEquals(2.5, config.getLatencyWeight(), 0.001);
    }

    @Test
    void testSetTargetLatencyMs() {
        config.setTargetLatencyMs(100.0);

        assertEquals(100.0, config.getTargetLatencyMs(), 0.001);
    }

    @Test
    void testSetPreferredRegion() {
        config.setPreferredRegion("us-west-2");

        assertEquals("us-west-2", config.getPreferredRegion());
    }

    @Test
    void testSetPreferredZone() {
        config.setPreferredZone("zone-a");

        assertEquals("zone-a", config.getPreferredZone());
    }

    @Test
    void testSetRegionBoost() {
        config.setRegionBoost(1.5);

        assertEquals(1.5, config.getRegionBoost(), 0.001);
    }

    @Test
    void testSetZoneBoost() {
        config.setZoneBoost(1.2);

        assertEquals(1.2, config.getZoneBoost(), 0.001);
    }

    @Test
    void testSetCpuKey() {
        config.setCpuKey("cpuUsage");

        assertEquals("cpuUsage", config.getCpuKey());
    }

    @Test
    void testSetLatencyKey() {
        config.setLatencyKey("responseTime");

        assertEquals("responseTime", config.getLatencyKey());
    }

    @Test
    void testSetMemoryWeight() {
        config.setMemoryWeight(1.5);

        assertEquals(1.5, config.getMemoryWeight(), 0.001);
    }

    @Test
    void testSetMemoryKey() {
        config.setMemoryKey("memUsage");

        assertEquals("memUsage", config.getMemoryKey());
    }

    @Test
    void testSetInflightWeight() {
        config.setInflightWeight(2.0);

        assertEquals(2.0, config.getInflightWeight(), 0.001);
    }

    @Test
    void testSetInflightKey() {
        config.setInflightKey("pendingRequests");

        assertEquals("pendingRequests", config.getInflightKey());
    }

    @Test
    void testSetQueueWeight() {
        config.setQueueWeight(1.0);

        assertEquals(1.0, config.getQueueWeight(), 0.001);
    }

    @Test
    void testSetQueueKey() {
        config.setQueueKey("queueDepth");

        assertEquals("queueDepth", config.getQueueKey());
    }

    @Test
    void testSetErrorRateWeight() {
        config.setErrorRateWeight(2.5);

        assertEquals(2.5, config.getErrorRateWeight(), 0.001);
    }

    @Test
    void testSetErrorRateKey() {
        config.setErrorRateKey("failureRate");

        assertEquals("failureRate", config.getErrorRateKey());
    }

    @Test
    void testSetMaxCpuPercent() {
        config.setMaxCpuPercent(80.0);

        assertEquals(80.0, config.getMaxCpuPercent(), 0.001);
    }

    @Test
    void testSetMaxLatencyMs() {
        config.setMaxLatencyMs(200.0);

        assertEquals(200.0, config.getMaxLatencyMs(), 0.001);
    }

    @Test
    void testSetMaxMemoryPercent() {
        config.setMaxMemoryPercent(90.0);

        assertEquals(90.0, config.getMaxMemoryPercent(), 0.001);
    }

    @Test
    void testSetMaxInflight() {
        config.setMaxInflight(100.0);

        assertEquals(100.0, config.getMaxInflight(), 0.001);
    }

    @Test
    void testSetMaxQueue() {
        config.setMaxQueue(50.0);

        assertEquals(50.0, config.getMaxQueue(), 0.001);
    }

    @Test
    void testSetMaxErrorRatePercent() {
        config.setMaxErrorRatePercent(10.0);

        assertEquals(10.0, config.getMaxErrorRatePercent(), 0.001);
    }

    @Test
    void testCustomConfiguration() {
        config.setBaseWeightFactor(2.0);
        config.setCpuWeight(1.5);
        config.setLatencyWeight(2.0);
        config.setTargetLatencyMs(100.0);
        config.setPreferredRegion("us-east-1");
        config.setPreferredZone("zone-b");
        config.setRegionBoost(1.3);
        config.setZoneBoost(1.1);
        config.setMemoryWeight(1.0);
        config.setInflightWeight(0.5);
        config.setQueueWeight(0.3);
        config.setErrorRateWeight(2.0);
        config.setMaxCpuPercent(85.0);
        config.setMaxLatencyMs(150.0);
        config.setMaxMemoryPercent(95.0);
        config.setMaxInflight(200.0);
        config.setMaxQueue(100.0);
        config.setMaxErrorRatePercent(5.0);

        assertEquals(2.0, config.getBaseWeightFactor(), 0.001);
        assertEquals(1.5, config.getCpuWeight(), 0.001);
        assertEquals(2.0, config.getLatencyWeight(), 0.001);
        assertEquals(100.0, config.getTargetLatencyMs(), 0.001);
        assertEquals("us-east-1", config.getPreferredRegion());
        assertEquals("zone-b", config.getPreferredZone());
        assertEquals(1.3, config.getRegionBoost(), 0.001);
        assertEquals(1.1, config.getZoneBoost(), 0.001);
        assertEquals(1.0, config.getMemoryWeight(), 0.001);
        assertEquals(0.5, config.getInflightWeight(), 0.001);
        assertEquals(0.3, config.getQueueWeight(), 0.001);
        assertEquals(2.0, config.getErrorRateWeight(), 0.001);
        assertEquals(85.0, config.getMaxCpuPercent(), 0.001);
        assertEquals(150.0, config.getMaxLatencyMs(), 0.001);
        assertEquals(95.0, config.getMaxMemoryPercent(), 0.001);
        assertEquals(200.0, config.getMaxInflight(), 0.001);
        assertEquals(100.0, config.getMaxQueue(), 0.001);
        assertEquals(5.0, config.getMaxErrorRatePercent(), 0.001);
    }
}
