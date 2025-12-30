package io.github.cuihairu.redis.streaming.mq.broker.jdbc;

import io.github.cuihairu.redis.streaming.mq.broker.Broker;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JdbcBrokerFactory
 */
class JdbcBrokerFactoryTest {

    @Mock
    private javax.sql.DataSource mockDataSource;

    @Mock
    private org.redisson.api.RedissonClient mockRedissonClient;

    private JdbcBrokerFactory factory;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        factory = new JdbcBrokerFactory(mockDataSource);
    }

    // ===== Constructor Tests =====

    @Test
    void testConstructorWithValidDataSource() {
        assertNotNull(factory);
    }

    @Test
    void testConstructorWithNullDataSource() {
        // Constructor accepts null
        JdbcBrokerFactory f = new JdbcBrokerFactory(null);
        assertNotNull(f);
    }

    // ===== Create Broker Tests =====

    @Test
    void testCreateWithAllParameters() {
        MqOptions options = MqOptions.builder()
                .defaultPartitionCount(5)
                .ackDeletePolicy("immediate")
                .build();

        Broker broker = factory.create(mockRedissonClient, options);

        assertNotNull(broker);
    }

    @Test
    void testCreateWithNullOptions() {
        Broker broker = factory.create(mockRedissonClient, null);

        assertNotNull(broker);
    }

    @Test
    void testCreateWithDefaultOptions() {
        MqOptions options = new MqOptions();
        Broker broker = factory.create(mockRedissonClient, options);

        assertNotNull(broker);
    }

    @Test
    void testCreateWithEmptyOptions() {
        MqOptions options = MqOptions.builder().build();
        Broker broker = factory.create(mockRedissonClient, options);

        assertNotNull(broker);
    }

    @Test
    void testCreateWithNullRedissonClient() {
        MqOptions options = MqOptions.builder().build();
        // Should not throw on create, but will fail when using the broker
        Broker broker = factory.create(null, options);

        assertNotNull(broker);
    }

    @Test
    void testCreateWithCustomPartitionCount() {
        MqOptions options = MqOptions.builder()
                .defaultPartitionCount(10)
                .build();

        Broker broker = factory.create(mockRedissonClient, options);

        assertNotNull(broker);
    }

    @Test
    void testCreateWithAckPolicyImmediate() {
        MqOptions options = MqOptions.builder()
                .ackDeletePolicy("immediate")
                .build();

        Broker broker = factory.create(mockRedissonClient, options);

        assertNotNull(broker);
    }

    @Test
    void testCreateWithAckPolicyAllGroupsAck() {
        MqOptions options = MqOptions.builder()
                .ackDeletePolicy("all-groups-ack")
                .build();

        Broker broker = factory.create(mockRedissonClient, options);

        assertNotNull(broker);
    }

    @Test
    void testCreateWithAckPolicyNone() {
        MqOptions options = MqOptions.builder()
                .ackDeletePolicy("none")
                .build();

        Broker broker = factory.create(mockRedissonClient, options);

        assertNotNull(broker);
    }

    @Test
    void testCreateMultipleBrokers() {
        MqOptions options = MqOptions.builder().build();

        Broker broker1 = factory.create(mockRedissonClient, options);
        Broker broker2 = factory.create(mockRedissonClient, options);

        assertNotNull(broker1);
        assertNotNull(broker2);
        // Each call should create a new broker instance
        assertNotSame(broker1, broker2);
    }

    @Test
    void testCreateReturnsDefaultBroker() {
        MqOptions options = MqOptions.builder().build();
        Broker broker = factory.create(mockRedissonClient, options);

        // The factory should create a DefaultBroker
        assertTrue(broker instanceof io.github.cuihairu.redis.streaming.mq.broker.impl.DefaultBroker);
    }

    @Test
    void testCreateWithNullBothParameters() {
        Broker broker = factory.create(null, null);

        assertNotNull(broker);
    }
}
