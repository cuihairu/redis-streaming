# Spring Boot Integration Guide

This is the quick-start page; for the full configuration reference, the auto-configured bean list, and metric names see [Spring-Boot-Starter](./Spring-Boot-Starter.md).

## Module Responsibility

`spring-boot-starter` assembles the core/runtime/mq/registry/config/reliability components into Spring beans:

- A fallback `RedissonClient` (steps aside automatically when the project already defines a `RedissonClient` bean)
- Service registration/discovery: `NamingService`, `ServiceConsumer`, `LoadBalancer`
- Configuration center: `ConfigService`
- Message queue and dead letters: `MessageQueueFactory`, `MessageQueueAdmin`, `DeadLetterService`, etc.
- Rate limiting: `RateLimiter` / `RateLimiterRegistry` (when `redis-streaming.ratelimit.enabled=true`; off by default)
- Health indicators and the Micrometer metric bridge (`MqHealthIndicator`, the various `*MicrometerCollector`s)

## Public Surface

- The auto-configuration entry point is `RedisStreamingAutoConfiguration` (active by classpath presence, registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`); `@EnableRedisStreaming` is an `@Import` alias annotation for it.
- There are six feature-domain configuration classes: `RedisStreamingRegistryAutoConfiguration` / `RedisStreamingDiscoveryAutoConfiguration` / `RedisStreamingConfigServiceAutoConfiguration` / `RedisStreamingMqAutoConfiguration` / `RedisStreamingRateLimitAutoConfiguration` / `RedisStreamingRuntimeAutoConfiguration`, toggled respectively by `redis-streaming.{registry,discovery,config,mq}.enabled` (default `true`), `redis-streaming.ratelimit.enabled` (default `false`), and `redis-streaming.runtime.control-plane.enabled` / `redis-streaming.runtime.agent.enabled` (both default `false`).
- There are two annotations. `@ServiceChangeListener` handles service-change callbacks and is registered by `ServiceChangeListenerProcessor`; `@ConfigChangeListener` currently has no processor in the starter — to listen for configuration changes call `ConfigService.addListener(dataId, group, listener)` directly.
- Auto registration: `AutoServiceRegistration` is a `@Component` but is not in the auto-configuration imports; it only takes effect when component scanning covers the `io.github.cuihairu.redis.streaming.starter.service` package.

## Configuration (prefix `redis-streaming`)

Minimal working configuration (everything is optional; defaults in parentheses):

```yaml
redis-streaming:
  redis:
    address: redis://127.0.0.1:6379   # String, default redis://127.0.0.1:6379
    password: ""                       # String, default null (blank means unset)
    database: 0                        # int, default 0
  registry:
    enabled: true                      # boolean, default true
    heartbeat-interval: 30             # int (seconds), default 30
  discovery:
    enabled: true                      # boolean, default true
    healthy-only: true                 # boolean, default true
  config:
    enabled: true                      # boolean, default true
    default-group: DEFAULT_GROUP       # String, default DEFAULT_GROUP
  mq:
    enabled: true                      # boolean, default true
    worker-threads: 8                  # int, default 8
  ratelimit:
    enabled: false                     # boolean, default false
```

For the types and defaults of every `redis-streaming.*` key (including `load-balancer.*`, `invoker.*`, the 34 `mq.*` keys, and `ratelimit.policies.<name>.*`), see the "Configuration Reference" in [Spring-Boot-Starter](./Spring-Boot-Starter.md).

## Usage Example

A runnable end-to-end example lives at `examples/src/main/java/io/github/cuihairu/redis/streaming/examples/springboot/StarterExampleApplication.java`, with sample configuration in `examples/src/main/resources/application.yml`; run it with:

```bash
./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.springboot.StarterExampleApplication
```

Inject the auto-configured beans and use them (excerpted from the example above):

```java
@SpringBootApplication
public class StarterExampleApplication implements CommandLineRunner {

    private final NamingService namingService;
    private final ConfigService configService;
    private final MessageQueueFactory messageQueueFactory;

    public StarterExampleApplication(NamingService namingService,
                                     ConfigService configService,
                                     MessageQueueFactory messageQueueFactory) {
        this.namingService = namingService;
        this.configService = configService;
        this.messageQueueFactory = messageQueueFactory;
    }

    @Override
    public void run(String... args) throws Exception {
        // service registration and discovery
        namingService.register(DefaultServiceInstance.builder()
                .instanceId("demo-instance-1")
                .serviceName("demo-service")
                .host("127.0.0.1")
                .port(8080)
                .protocol(StandardProtocol.HTTP)
                .build());
        System.out.println(namingService.getHealthyInstances("demo-service"));

        // configuration center: publish / read
        configService.publishConfig("app.settings", "DEFAULT_GROUP", "{\"greeting\":\"hello\"}");
        System.out.println(configService.getConfig("app.settings", "DEFAULT_GROUP"));

        // message queue: send (MessageProducer.send returns CompletableFuture<String>)
        MessageProducer producer = messageQueueFactory.createProducer();
        try {
            String id = producer.send("starter-example-topic", "order-1", "{\"amount\":100}").get();
            System.out.println("published " + id);
        } finally {
            producer.close();
        }
    }
}
```

Running a streaming job (Redis engine; the interfaces belong to the `runtime` module, pulled in transitively by the starter):

```java
RedisRuntimeConfig config = RedisRuntimeConfig.builder()
        .jobName("rt-job")
        .watermarkOutOfOrderness(Duration.ofSeconds(5))
        .build();
RedisStreamExecutionEnvironment env =
        RedisStreamExecutionEnvironment.create(redissonClient, config);
env.fromMqTopic("orders", "cg-orders")
        .map(m -> (String) m.getPayload())
        .addSink(v -> { /* ... */ });
try (RedisJobClient job = env.executeAsync()) {
    job.awaitTermination(Duration.ofMinutes(10));
}
```

For the full description of runtime jobs see [runtime](./runtime.md).

## FAQ

- Cluster/Sentinel/SSL: declare your own `RedissonClient` bean; the starter's fallback client (`@ConditionalOnMissingBean`) steps aside automatically.
- Rate limiting not taking effect: `redis-streaming.ratelimit.enabled` defaults to `false` and must be enabled explicitly; the `leaky-bucket` algorithm has only an in-memory backend.
- Auto service registration not taking effect: check that component scanning covers `AutoServiceRegistration`'s package (see above).
- Starts without actuator: `MqHealthIndicator` and the other health/metric beans are all guarded by `@ConditionalOnClass`.

## Related Documentation

[runtime.md](runtime.md) · [MQ.md](MQ.md) · [Registry.md](Registry.md) · [config.md](config.md)
