# Running Examples

The `:examples` module contains runnable demos that exercise multiple submodules together.
All example classes live under `examples/src/main/java/io/github/cuihairu/redis/streaming/examples/`.

## Prerequisites
- Java 17
- A reachable Redis for the Redis-backed examples (Docker recommended): `registry`, `mq`, `aggregation`, `streaming`, `state`, `checkpoint`, `springboot`
- `window/WindowExample` and `ratelimit/RateLimitExample` use only in-memory code and run without Redis

## Start Dependencies

### Start Redis with docker-compose
`docker-compose up -d redis`

By default, the Redis-backed examples connect to `redis://127.0.0.1:6379` (their mains read the `REDIS_URL` environment variable with that default). Override via:
`export REDIS_URL=redis://127.0.0.1:6379`

The Spring Boot example instead reads its address from `examples/src/main/resources/application.yml` (key `redis-streaming.redis.address`, same default).

## Build & Run

### Run the default example
`./gradlew :examples:run`

The default main class is `io.github.cuihairu.redis.streaming.examples.registry.ServiceRegistryExample` (configured in `examples/build.gradle`).

### Run a specific example main class
The build reads the Gradle project property `mainClass` (see `examples/build.gradle`):

`./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.mq.MessageQueueExample`

## Available Example Entrypoints

- `io.github.cuihairu.redis.streaming.examples.registry.ServiceRegistryExample` (default) — registration, discovery, and load balancing across simulated services
- `io.github.cuihairu.redis.streaming.examples.registry.CustomPrefixExample` — custom Redis key prefixes to avoid key conflicts
- `io.github.cuihairu.redis.streaming.examples.mq.MessageQueueExample` — producer/consumer patterns, consumer groups, dead-letter queues
- `io.github.cuihairu.redis.streaming.examples.aggregation.StreamAggregationExample` — tumbling/sliding/time windows and analytics (PV/TopK)
- `io.github.cuihairu.redis.streaming.examples.streaming.ComprehensiveStreamingExample` — registry + MQ + real-time event processing wired together
- `io.github.cuihairu.redis.streaming.examples.state.StateExample` — State module usage
- `io.github.cuihairu.redis.streaming.examples.checkpoint.CheckpointExample` — Checkpoint module usage
- `io.github.cuihairu.redis.streaming.examples.window.WindowExample` — Window module usage
- `io.github.cuihairu.redis.streaming.examples.ratelimit.RateLimitExample` — sliding-window / token-bucket rate limiters used as a sink decorator
- `io.github.cuihairu.redis.streaming.examples.springboot.StarterExampleApplication` — Spring Boot starter end-to-end (registry, config center, MQ); config in `examples/src/main/resources/application.yml`

## Cleanup
- Stop containers: `docker-compose down`
- Remove volumes (optional): `docker-compose down -v`
