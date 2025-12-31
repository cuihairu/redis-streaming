# Running Examples

The `:examples` module contains runnable demos that exercise multiple submodules together.

## Prerequisites
- Java 17
- Docker (recommended) for a local Redis instance

## Start Dependencies

### Start Redis with docker-compose
`docker-compose up -d redis`

By default, examples connect to `redis://127.0.0.1:6379`. Override via:
`export REDIS_URL=redis://127.0.0.1:6379`

## Build & Run

### Run the default example
`./gradlew :examples:run`

### Run a specific example main class
The build supports `-PmainClass=...`:

`./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.mq.MessageQueueExample`

## Available Example Entrypoints
- `io.github.cuihairu.redis.streaming.examples.registry.ServiceRegistryExample` (default)
- `io.github.cuihairu.redis.streaming.examples.registry.CustomPrefixExample`
- `io.github.cuihairu.redis.streaming.examples.mq.MessageQueueExample`
- `io.github.cuihairu.redis.streaming.examples.aggregation.StreamAggregationExample`
- `io.github.cuihairu.redis.streaming.examples.streaming.ComprehensiveStreamingExample`
- `io.github.cuihairu.redis.streaming.examples.state.StateExample`
- `io.github.cuihairu.redis.streaming.examples.checkpoint.CheckpointExample`
- `io.github.cuihairu.redis.streaming.examples.window.WindowExample`
- `io.github.cuihairu.redis.streaming.examples.ratelimit.RateLimitExample`

## Cleanup
- Stop containers: `docker-compose down`
- Remove volumes (optional): `docker-compose down -v`

