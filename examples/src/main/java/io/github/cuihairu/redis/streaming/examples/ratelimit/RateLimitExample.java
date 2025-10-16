package io.github.cuihairu.redis.streaming.examples.ratelimit;

import io.github.cuihairu.redis.streaming.reliability.ratelimit.*;
import io.github.cuihairu.redis.streaming.sink.print.PrintSink;

/** Simple demo showing usage of different rate limiters with a sink decorator. */
public class RateLimitExample {
    public static void main(String[] args) throws Exception {
        RateLimiter sliding = new InMemorySlidingWindowRateLimiter(1000, 5); // 5 req / sec
        RateLimiter token = new InMemoryTokenBucketRateLimiter(10, 5);       // burst 10, avg 5/s
        var sink = new PrintSink<String>("RL");

        var slidingSink = RateLimitingSink.drop(new NamedRateLimiter("sliding-demo", sliding), s -> "user:1", sink);
        var tokenSink = RateLimitingSink.drop(new NamedRateLimiter("token-demo", token), s -> "user:1", sink);

        System.out.println("-- Sliding window: allowing ~5 per second, extra dropped");
        long start = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            slidingSink.invoke("msg-" + i + "@" + (System.currentTimeMillis() - start));
        }

        Thread.sleep(1100);
        System.out.println("-- Token bucket: initial burst allowed up to capacity, then ~5/s");
        start = System.currentTimeMillis();
        for (int i = 0; i < 20; i++) {
            tokenSink.invoke("msg-" + i + "@" + (System.currentTimeMillis() - start));
        }
    }
}

