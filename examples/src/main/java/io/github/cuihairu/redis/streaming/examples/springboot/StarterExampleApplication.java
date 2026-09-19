package io.github.cuihairu.redis.streaming.examples.springboot;

import io.github.cuihairu.redis.streaming.config.ConfigService;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot starter 端到端示例。
 *
 * <p>演示通过 redis-streaming-spring-boot-starter 自动装配的组件:
 * 服务注册(NamingService)、配置中心(ConfigService)、消息队列(MessageQueueFactory)。
 * 配置见同模块 {@code src/main/resources/application.yml}(键前缀 {@code redis-streaming:}),
 * 需要本地 Redis(默认 redis://127.0.0.1:6379)。</p>
 *
 * <p>运行:{@code ./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.springboot.StarterExampleApplication}</p>
 */
@SpringBootApplication
public class StarterExampleApplication implements CommandLineRunner {

    private final NamingService namingService;
    private final ConfigService configService;
    private final MessageQueueFactory messageQueueFactory;

    @Value("${redis-streaming.example.service-name:demo-service}")
    private String serviceName;

    @Value("${redis-streaming.example.topic:starter-example-topic}")
    private String topic;

    public StarterExampleApplication(NamingService namingService,
                                     ConfigService configService,
                                     MessageQueueFactory messageQueueFactory) {
        this.namingService = namingService;
        this.configService = configService;
        this.messageQueueFactory = messageQueueFactory;
    }

    public static void main(String[] args) {
        org.springframework.context.ConfigurableApplicationContext ctx =
                SpringApplication.run(StarterExampleApplication.class, args);
        // Close so the Redisson client's non-daemon threads allow the JVM to exit.
        ctx.close();
    }

    @Override
    public void run(String... args) throws Exception {
        namingService.register(DefaultServiceInstance.builder()
                .instanceId("demo-instance-1")
                .serviceName(serviceName)
                .host("127.0.0.1")
                .port(8080)
                .protocol(StandardProtocol.HTTP)
                .build());
        System.out.println("registered instances: " + namingService.getHealthyInstances(serviceName));

        configService.publishConfig("app.settings", "DEFAULT_GROUP", "{\"greeting\":\"hello from config center\"}");
        System.out.println("config app.settings = "
                + configService.getConfig("app.settings", "DEFAULT_GROUP"));

        MessageProducer producer = messageQueueFactory.createProducer();
        try {
            String id = producer.send(topic, "order-1", "{\"amount\":100}").get();
            System.out.println("published mq message id=" + id + " to topic " + topic);
        } finally {
            producer.close();
        }
    }
}
