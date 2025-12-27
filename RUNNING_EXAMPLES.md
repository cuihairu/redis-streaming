# Running Examples

This document describes the available examples and how to run them.

## Status Overview

### ✅ Working Examples

These examples are fully functional and can be run directly:

1. **ServiceRegistryExample** - Service discovery and registration
   - Location: `examples/src/main/java/.../registry/ServiceRegistryExample.java`
   - Features: Service registration, discovery, health checks, load balancing
   - Run: `./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.registry.ServiceRegistryExample`

2. **MessageQueueExample** - Message queue and event streaming  
   - Location: `examples/src/main/java/.../mq/MessageQueueExample.java`
   - Features: Producer-consumer, consumer groups, DLQ, batch processing, performance test
   - Run: Requires Redis running on localhost:6379

3. **ComprehensiveStreamingExample** - End-to-end streaming pipeline
   - Location: `examples/src/main/java/.../streaming/ComprehensiveStreamingExample.java`
   - Features: Multi-stage pipeline, service registry integration, event transformation
   - Run: Requires Redis running on localhost:6379

4. **CDCIntegrationExample** - Change Data Capture from databases
   - Location: `examples/src/main/java/.../cdc/CDCIntegrationExample.java`
   - Features: MySQL binlog CDC, PostgreSQL logical replication, database polling, CDC manager
   - Run: Requires MySQL/PostgreSQL with proper CDC setup (see Prerequisites)

5. **StreamAggregationExample** - Windowed aggregation and basic analytics
   - Location: `examples/src/main/java/.../aggregation/StreamAggregationExample.java`
   - Features: Sliding/tumbling window aggregations, PV counter, top-K
   - Run: Requires Redis running on localhost:6379

## Prerequisites

### Required

- Java 11 or higher
- Redis 6.0+ (for message queue and registry examples)
- Gradle 8.5 (included via wrapper)

### Optional (for CDC examples)

- **MySQL 5.7+** with binlog enabled for MySQL CDC
  - `binlog_format = ROW`
  - `binlog_row_image = FULL`
  - User with `REPLICATION SLAVE`, `REPLICATION CLIENT` privileges

- **PostgreSQL 10+** with logical replication for PostgreSQL CDC
  - `wal_level = logical`
  - Replication slot and publication configured
  - User with replication privileges

- **MySQL/PostgreSQL** for database polling CDC
  - Tables with timestamp or incremental columns

### Starting Redis

Using Docker Compose (recommended):
```bash
docker-compose up -d
```

Or manually:
```bash
docker run -d -p 6379:6379 redis:7-alpine
```

## Running Examples

### Option 1: Using Gradle

```bash
# Build all modules first
./gradlew build -x test

# Run ServiceRegistryExample
./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.registry.ServiceRegistryExample

# Run MessageQueueExample  
./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.mq.MessageQueueExample

# Run ComprehensiveStreamingExample
./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.streaming.ComprehensiveStreamingExample

# Run StreamAggregationExample
./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.aggregation.StreamAggregationExample

# Run CDCIntegrationExample (requires database setup)
./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.cdc.CDCIntegrationExample
```

**Note for CDC example**: Set database credentials via environment variables:
```bash
export MYSQL_USER=cdc_user
export MYSQL_PASSWORD=cdc_password
export MYSQL_HOST=localhost
export MYSQL_PORT=3306

export POSTGRES_USER=postgres_user
export POSTGRES_PASSWORD=postgres_password
export POSTGRES_HOST=localhost
export POSTGRES_PORT=5432
export POSTGRES_DB=test_db

export DB_USER=db_user
export DB_PASSWORD=db_password
export DB_URL=jdbc:mysql://localhost:3306/test_db

./gradlew :examples:run -PmainClass=io.github.cuihairu.redis.streaming.examples.cdc.CDCIntegrationExample
```

### Option 2: Using IDE (IntelliJ IDEA / VS Code)

1. Open the project in your IDE
2. Navigate to the example class
3. Right-click on the `main()` method
4. Select "Run"

**Note**: Make sure Redis is running before executing examples.

## Integration Tests

Each module has integration tests that demonstrate real functionality:

```bash
# Start Redis
docker-compose up -d

# Run all integration tests
./gradlew integrationTest

# Run specific module integration tests
./gradlew :core:integrationTest
./gradlew :aggregation:integrationTest
./gradlew :sink:integrationTest
./gradlew :source:integrationTest

# Stop Redis
docker-compose down
```

## Example Output

### ServiceRegistryExample

```
INFO  - Starting Service Registry Example
INFO  - Setting up Redis-based service registry and discovery
INFO  - Creating microservices
INFO  - All microservices started successfully
INFO  - === Demonstrating Service Discovery ===
INFO  - Discovered 1 user service instances
INFO  - === Demonstrating Load Balancing ===
INFO  - Request 1: Selected instance at localhost:8081 (instance: 1)
INFO  - Request 2: Selected instance at localhost:8085 (instance: 2)
INFO  - Service Registry Example completed
```

### MessageQueueExample

```
INFO  - Starting Message Queue Example
INFO  - === Demonstrating Basic Producer-Consumer ===
INFO  - Produced order event: ORDER_CREATED for order: order-1 (ID: 1234-5678)
INFO  - Consumed order event: ORDER_CREATED for order: order-1 (amount: $60.0)
INFO  - === Demonstrating Consumer Groups ===
INFO  - [EMAIL] Processing notification: notification-1 for user: user-1
INFO  - [SMS] Processing notification: notification-1 for user: user-1
INFO  - === Performance Test ===
INFO  - Throughput: 2500.0 messages/second
INFO  - Message Queue Example completed
```

### CDCIntegrationExample

```
INFO  - Starting CDC Integration Example
INFO  - NOTE: This example requires database setup. See documentation for prerequisites.
INFO  - === Demonstrating MySQL Binlog CDC ===
INFO  - ✅ MySQL binlog CDC connector started successfully
INFO  - 📥 Captured 3 events from MySQL connector: mysql_example
INFO  - Polled 3 events from MySQL
INFO  -   Event: INSERT mysql_binlog orders.orders key=12345
INFO  -   Event: UPDATE mysql_binlog orders.orders key=12345
INFO  -   💾 Committed position: mysql-bin.000123:4567
INFO  - 🛑 MySQL binlog CDC connector stopped
INFO  - === Demonstrating PostgreSQL Logical Replication CDC ===
INFO  - ✅ PostgreSQL logical replication CDC connector started
INFO  - PostgreSQL polled 2 events
INFO  -   Event: INSERT userdb.users
INFO  -   Event: UPDATE userdb.users
INFO  - 🛑 PostgreSQL CDC connector stopped
INFO  - === Demonstrating Database Polling CDC ===
INFO  - ✅ Database polling CDC connector started
INFO  - 📥 Database polling captured 5 events from connector: polling_example
INFO  - 🛑 Database polling CDC connector stopped
INFO  - === Demonstrating CDC Manager ===
INFO  - Added 2 connectors to manager
INFO  - ✅ CDC manager started with 2 running connectors
INFO  - Connector mysql_mgr health: HEALTHY - Running
INFO  - Connector polling_mgr metrics: 15 total events, 0 errors
INFO  - 🛑 CDC manager stopped
INFO  - CDC Integration Example completed
```

## Troubleshooting

### Redis Connection Error

```
Error: org.redisson.client.RedisConnectionException: Unable to connect to Redis server
```

**Solution**: Ensure Redis is running on `localhost:6379` or set the `REDIS_URL` environment variable:
```bash
export REDIS_URL=redis://your-redis-host:6379
```

### Build Failed - Configuration Cache

```
Configuration cache problems found in this build.
```

**Solution**: Configuration cache is disabled by default. If you see this, run:
```bash
./gradlew clean build --no-configuration-cache
```

### Jackson Instant Serialization Error

```
Java 8 date/time type `java.time.Instant` not supported by default
```

**Solution**: This has been fixed in the examples by registering `JavaTimeModule`. Ensure you're using the latest version of the code.

### CDC Database Connection Error

```
Error: Unable to connect to MySQL/PostgreSQL server
```

**Solution**:
1. Verify database is running and accessible
2. Check database configuration (binlog for MySQL, logical replication for PostgreSQL)
3. Verify user has proper privileges
4. Set correct environment variables for database credentials
5. For testing without databases, use the integration test instead:
   ```bash
   # Run CDC integration tests (these are disabled by default)
   ./gradlew :cdc:integrationTest --tests "CDCIntegrationExamplesTest"
   ```

## Next Steps

1. Review the working examples to understand the API usage
2. Check integration tests in each module for more detailed usage patterns  
3. Refer to `CLAUDE.md` for module-specific documentation
4. Once more APIs are implemented, the `.broken` examples will be updated

## Contributing

When adding new examples:
1. Use only implemented APIs
2. Add proper resource cleanup (see `MessageQueueExample.cleanup()`)
3. Register Jackson modules for Java 8 time types
4. Include comprehensive logging
5. Add to this documentation
