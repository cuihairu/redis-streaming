# Examples

Module: `examples/`

Runnable examples and minimal samples, covering the mq / registry / aggregation / streaming / state / checkpoint / window / ratelimit / springboot submodules.

## How to Run
```bash
# Dependency (connects to redis://127.0.0.1:6379 by default)
docker compose up -d redis

# Default entry point: registry.ServiceRegistryExample
./gradlew :examples:run

# Specify an entry point
./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.mq.MessageQueueExample
```
(`application.mainClass` in `examples/build.gradle` is overridden by `-PmainClass`.)

## Available Entry Points (`examples/src/main/java/io/github/cuihairu/redis/streaming/examples`)
- `registry.ServiceRegistryExample` (default)
- `registry.CustomPrefixExample`
- `mq.MessageQueueExample`
- `aggregation.StreamAggregationExample`
- `streaming.ComprehensiveStreamingExample`
- `state.StateExample`
- `checkpoint.CheckpointExample`
- `window.WindowExample`
- `ratelimit.RateLimitExample`
- `springboot.StarterExampleApplication` (Spring Boot Starter example; configuration in `examples/src/main/resources/application.yml`)

## References
- Root `RUNNING_EXAMPLES.md` (full steps and environment variables)
- This module is not published (the root `build.gradle` skips the maven-publish plugin for `examples`)

---

**Version**: 0.2.4
**Last Updated**: 2026-10-16
