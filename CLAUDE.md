# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Java streaming processing framework built around Redis Stream and other Redis data structures. The project consists of multiple Gradle modules designed to provide lightweight messaging, service registry, time series analysis, and other streaming capabilities.

## Build and Development Commands

### Build Commands
```bash
# Build all modules
./gradlew build

# Build specific module
./gradlew :core:build
./gradlew :spring-boot-starter:build
./gradlew :examples:build
./gradlew :aggregation:build
./gradlew :cdc:build
./gradlew :sink:build
./gradlew :source:build
./gradlew :metrics:build

# Clean and rebuild
./gradlew clean build
```

### Test Commands
```bash
# Run all tests
./gradlew test

# Run tests for specific module
./gradlew :core:test

# Run tests with verbose output
./gradlew test --info
```

### Development Commands
```bash
# Check for dependency updates
./gradlew dependencyUpdates

# Generate source and javadoc JARs
./gradlew publishToMavenLocal
```

## Project Structure

### Multi-Module Architecture
- **core**: Core functionality with Redis-based components
  - `registry`: Service registration and discovery with full protocol support
    - Service identity and instance abstractions
    - Health checking system (HTTP, TCP, WebSocket, Custom)
    - Client interfaces: ServiceRegistry, ServiceProvider, ServiceConsumer, ServiceDiscovery, NamingService
    - Redis-based implementations with pub/sub notifications
  - `mq`: Message queue implementation using Redis Streams (planned)
  - `timeseries`: Time window aggregation using Redis Sorted Set/TimeSeries (planned)
  - `logs`: Log aggregation through Redis List/Stream with ELK forwarding (planned)
  - `utils`: Utility classes for instance ID generation and system operations
- **spring-boot-starter**: Spring Boot auto-configuration and annotation support
- **examples**: Example implementations demonstrating usage patterns
- **aggregation**: Stream aggregation and windowing operations (PV counting, Top-K analysis)
- **cdc**: Change Data Capture from databases (MySQL Binlog, Oracle LogMiner)
- **sink**: Data output connectors (Elasticsearch, HBase, Snowflake)
- **source**: Data input connectors (IoT devices, HTTP APIs, file systems)
- **metrics**: Monitoring and observability (Prometheus integration, Micrometer)

### Key Dependencies
- **Redisson 3.29.0**: Redis client for distributed operations and connection pooling
- **Jackson 2.17.0**: JSON serialization/deserialization
- **Lombok 1.18.34**: Code generation for POJOs
- **JUnit Jupiter 5.9.2**: Testing framework
- **Mockito 4.6.1**: Mocking framework for unit tests
- **SLF4J 1.7.36**: Logging abstraction

## Architecture Patterns

### Redis-Centric Design
All modules leverage Redis data structures:
- **Streams**: For message queuing with consumer groups and dead letter queues
- **Hash + Pub/Sub**: For service registry with heartbeat monitoring and instance metadata
- **Sorted Set/TimeSeries**: For time-based aggregation and monitoring
- **List/Stream**: For log collection and forwarding

### Service Registry Architecture
The registry module implements a comprehensive service discovery system:
- **ServiceIdentity**: Core interface defining service name and instance ID
- **ServiceInstance**: Extended interface with host, port, metadata, health status
- **Protocol Support**: HTTP, HTTPS, TCP with extensible protocol definitions
- **Health Checking**: Pluggable health checkers for different protocols
- **Client Roles**: Clear separation between Provider, Consumer, Discovery, and Registry operations

### Module Communication
- Producer/Consumer pattern for message handling
- Event-driven architecture with async processing
- Service discovery through Redis-based registry
- Configuration management with dynamic refresh capabilities

### Java Version and Encoding
- Source/Target compatibility: Java 11
- Default encoding: UTF-8
- Uses modern Java features while maintaining compatibility

## Development Guidelines

### Code Organization
- Follow package structure: `io.github.cuihairu.redis-streaming.<module>`
- Use Lombok annotations for reducing boilerplate code
- Implement proper error handling with retry strategies
- Include comprehensive unit tests for all components

### Redis Integration
- Use Redisson for all Redis operations (thread-safe, connection pooling)
- Implement proper cleanup for streams (autoTrim methods)
- Handle Redis connection failures gracefully
- Support multiple Redis deployment options (standalone, cluster, sentinel)
- Follow package structure: `io.github.cuihairu.redis-streaming.<module>`

### Service Registry Implementation Guidelines
- All service instances must implement ServiceInstance interface
- Use Protocol enum for standardized protocol definitions
- Health checkers should be protocol-specific and implement HealthChecker interface
- Redis-based clients use hash structures for instance data and pub/sub for notifications
- Instance IDs are generated using InstanceIdGenerator utility

### Performance Considerations
- Implement memory control for Redis Stream consumers
- Use async operations where possible
- Add monitoring capabilities through metrics exposure
- Consider connection pooling and resource cleanup

## Testing Strategy

The project separates unit tests from integration tests to allow flexible testing without external dependencies.

### Test Types and Organization

#### Unit Tests
- **Location**: `src/test/java`
- **Dependencies**: None (use Mockito for mocking)
- **Tagged**: No tag (default tests)
- **Examples**: `MessageTest`, `ProtocolTest`, `ServiceInstanceTest`
- **Run without Redis**

#### Integration Tests
- **Location**: `src/test/java` (same as unit tests)
- **Dependencies**: Requires Redis running
- **Tagged**: `@Tag("integration")`
- **Examples**: `*IntegrationExample.java`, `*IntegrationExamplesTest.java`
- **Test real Redis interactions**

### Running Tests

#### Quick Unit Tests (No Redis Required)
```bash
# Run all unit tests (excludes integration tests)
./gradlew test

# Run unit tests for specific module
./gradlew :core:test
./gradlew :aggregation:test

# Parallel execution for faster results
./gradlew test --parallel
```

#### Integration Tests (Redis Required)
```bash
# 1. Start Redis using Docker Compose
docker-compose up -d

# 2. Verify Redis is running
docker-compose ps

#### Integration Tests (Full Environment Required)
```bash
# Option 1: Use the test environment script (Recommended)
./test-env.sh start    # Start all test services
./test-env.sh test     # Run all tests
./test-env.sh stop     # Stop and cleanup

# Option 2: Manual Docker Compose
# 1. Start test environment (Redis, MySQL, PostgreSQL, Elasticsearch)
docker-compose -f docker-compose.test.yml up -d

# 2. Wait for services to be ready
./test-env.sh status

# 3. Run integration tests
./gradlew integrationTest

# 4. Run integration tests for specific module
./gradlew :registry:integrationTest
./gradlew :cdc:integrationTest
./gradlew :sink:integrationTest

# 5. Stop test environment when done
docker-compose -f docker-compose.test.yml down -v
```

#### Complete Test Suite
```bash
# Quick start - All in one command
./test-env.sh test

# Or step by step
./test-env.sh start
./gradlew clean test integrationTest
./test-env.sh stop

# Or using Docker Compose directly
docker-compose -f docker-compose.test.yml up -d
./gradlew clean test integrationTest
docker-compose -f docker-compose.test.yml down -v
```

### Test Environment Configuration

Integration tests support environment variables for all services:
```bash
# Service URLs (defaults)
export REDIS_URL=redis://localhost:6379
export MYSQL_URL=jdbc:mysql://localhost:3306/test_db
export MYSQL_USER=test_user
export MYSQL_PASSWORD=test_password
export POSTGRES_URL=jdbc:postgresql://localhost:5432/test_db
export POSTGRES_USER=test_user
export POSTGRES_PASSWORD=test_password
export ELASTICSEARCH_URL=http://localhost:9200

# Run tests with custom configuration
./gradlew integrationTest
```

### Test Environment Management

The project includes comprehensive test infrastructure:

#### Docker Compose Test Environment
- `docker-compose.test.yml` - Clean test environment (data in memory, no persistence)
- `docker-compose.yml` - Development environment (with data persistence)

#### Test Environment Script (`./test-env.sh`)
```bash
# Start services
./test-env.sh start

# Show status
./test-env.sh status

# View logs
./test-env.sh logs          # All services
./test-env.sh logs redis    # Specific service

# Run tests (auto-starts environment if needed)
./test-env.sh test

# Restart environment
./test-env.sh restart

# Stop and cleanup
./test-env.sh stop
```

#### Service Details
- **Redis** (6379): Core service registry and message queuing
- **MySQL** (3306): CDC (Change Data Capture) testing with binlog enabled
- **PostgreSQL** (5432): Alternative database testing with logical replication
- **Elasticsearch** (9200/9300): Sink connector testing

#### Key Features
- ✅ **No data persistence** - All data stored in memory (`tmpfs`)
- ✅ **Health checks** - Automatic service readiness detection
- ✅ **Clean isolation** - Each test run starts with fresh environment
- ✅ **Parallel testing** - Safe concurrent test execution
- ✅ **Auto-cleanup** - Containers removed after tests

# Clean volumes
docker-compose down -v
```

### Running Specific Tests
```bash
# Run specific test class
./gradlew :core:test --tests "ServiceInstanceTest"

# Run tests for specific package
./gradlew :core:test --tests "io.github.cuihairu.redis-streaming.core.registry.*"

# Run specific integration test
./gradlew :core:integrationTest --tests "RedisRegistryIntegrationExample"

# Run with debug output
./gradlew :core:test --tests "RedisServiceRegistryTest" --debug
```

### CI/CD Testing Strategy

#### Self-hosted Runner Setup (Linux)

For Linux self-hosted runners, Docker permissions need to be configured:

```bash
# 1. Run the setup script on your Linux runner
./setup-runner-docker.sh

# 2. Restart the GitHub Actions runner service
sudo systemctl restart actions.runner.*

# 3. Verify Docker access
docker ps
```

**Manual setup (alternative):**
```bash
# Add user to docker group
sudo usermod -aG docker $USER

# Restart runner service
sudo systemctl restart actions.runner.*

# Verify permissions
docker ps
```

#### CI/CD Pipeline

```bash
# 1. Fast feedback: Unit tests only
./gradlew test --parallel

# 2. Full validation: Unit + Integration
docker-compose up -d
./gradlew clean check
docker-compose down
```

### Test Coverage Goals
- **Core module (registry + mq)**: 80%+ (critical infrastructure)
- **Business modules**: 70%+
- **Integration tests**: Cover main workflows and edge cases