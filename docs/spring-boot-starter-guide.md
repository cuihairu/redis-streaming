# Spring Boot 集成指南

本文是快速上手篇;完整配置项参考、自动装配 Bean 清单与指标名见 [Spring-Boot-Starter](./Spring-Boot-Starter.md)(英文版:[Spring-Boot-Starter-en](./en/Spring-Boot-Starter-en.md))。

## 模块职责

`spring-boot-starter` 把 core/runtime/mq/registry/config/reliability 组件装配成 Spring Bean:

- 兜底 `RedissonClient`(项目里已有 `RedissonClient` Bean 时自动让位)
- 服务注册/发现:`NamingService`、`ServiceDiscovery`、`LoadBalancer`、`ClientSelector`/`ClientInvoker`
- 配置中心:`ConfigService`
- 消息队列与死信:`MessageQueueFactory`、`MessageQueueAdmin`、`DeadLetterService` 等
- 限流:`RateLimiter` / `RateLimiterRegistry`(`redis-streaming.ratelimit.enabled=true` 时,默认关闭)
- 健康检查与 Micrometer 指标桥(`MqHealthIndicator`、各 `*MicrometerCollector`)

## 对外接口

- 自动装配入口是 `RedisStreamingAutoConfiguration`(classpath 即生效,见 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`);`@EnableRedisStreaming` 是它的 `@Import` 别名注解。
- 功能域配置类有五个:`RedisStreamingRegistryAutoConfiguration` / `RedisStreamingDiscoveryAutoConfiguration` / `RedisStreamingConfigServiceAutoConfiguration` / `RedisStreamingMqAutoConfiguration` / `RedisStreamingRateLimitAutoConfiguration`,分别由 `redis-streaming.{registry,discovery,config,mq}.enabled`(默认 `true`)与 `redis-streaming.ratelimit.enabled`(默认 `false`)开关。
- 注解有两个。`@ServiceChangeListener` 负责服务变更回调,由 `ServiceChangeListenerProcessor` 注册;`@ConfigChangeListener` 目前在 starter 内没有对应处理器,监听配置变更请直接调用 `ConfigService.addListener(dataId, group, listener)`。
- 自动注册:`AutoServiceRegistration` 是 `@Component` 但不在自动装配 imports 中,需要组件扫描覆盖 `io.github.cuihairu.redis.streaming.starter.service` 包才生效。

## 配置项(前缀 `redis-streaming`)

最小可用配置(全部可省略,括号内为默认值):

```yaml
redis-streaming:
  redis:
    address: redis://127.0.0.1:6379   # String,默认 redis://127.0.0.1:6379
    password: ""                       # String,默认 null(空白不设置)
    database: 0                        # int,默认 0
  registry:
    enabled: true                      # boolean,默认 true
    heartbeat-interval: 30             # int(秒),默认 30
  discovery:
    enabled: true                      # boolean,默认 true
    healthy-only: true                 # boolean,默认 true
  config:
    enabled: true                      # boolean,默认 true
    default-group: DEFAULT_GROUP       # String,默认 DEFAULT_GROUP
  mq:
    enabled: true                      # boolean,默认 true
    worker-threads: 8                  # int,默认 8
  ratelimit:
    enabled: false                     # boolean,默认 false
```

`redis-streaming.*` 全部键(含 `load-balancer.*`、`invoker.*`、`mq.*` 34 项、`ratelimit.policies.<名称>.*`)的类型与默认值见 [Spring-Boot-Starter](./Spring-Boot-Starter.md) 的「配置项参考」。

## 用法示例

可运行端到端示例:`examples/src/main/java/io/github/cuihairu/redis/streaming/examples/springboot/StarterExampleApplication.java`,配置样例 `examples/src/main/resources/application.yml`,运行:

```bash
./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.springboot.StarterExampleApplication
```

注入自动装配的 Bean 并使用(节选自上述示例):

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
        // 服务注册与发现
        namingService.register(DefaultServiceInstance.builder()
                .instanceId("demo-instance-1")
                .serviceName("demo-service")
                .host("127.0.0.1")
                .port(8080)
                .protocol(StandardProtocol.HTTP)
                .build());
        System.out.println(namingService.getHealthyInstances("demo-service"));

        // 配置中心:发布 / 读取
        configService.publishConfig("app.settings", "DEFAULT_GROUP", "{\"greeting\":\"hello\"}");
        System.out.println(configService.getConfig("app.settings", "DEFAULT_GROUP"));

        // 消息队列:发送(MessageProducer.send 返回 CompletableFuture<String>)
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

运行一个流处理作业(Redis 引擎,接口属于 `runtime` 模块,由 starter 传递依赖引入):

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

运行时作业完整说明见 [runtime](./runtime.md)。

## 常见问题

- 集群/哨兵/SSL:自行声明 `RedissonClient` Bean,starter 的兜底客户端(`@ConditionalOnMissingBean`)自动让位。
- 限流不生效:`redis-streaming.ratelimit.enabled` 默认 `false`,需显式开启;`leaky-bucket` 算法只有内存后端。
- 自动服务注册不生效:检查组件扫描是否覆盖了 `AutoServiceRegistration` 所在包(见上)。
- 不引 actuator 也能启动:`MqHealthIndicator` 等健康/指标 Bean 都有 `@ConditionalOnClass` 守卫。

## 相关文档

[runtime.md](runtime.md) · [MQ.md](MQ.md) · [Registry.md](Registry.md) · [config.md](config.md)
