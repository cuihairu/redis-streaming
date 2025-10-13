# Testing Guide

This guide explains how to run tests in the streaming framework project.

## Overview

The project separates tests into two categories:

1. **Unit Tests** - Fast tests that don't require external dependencies (Redis)
2. **Integration Tests** - Tests that require Redis and test real integrations

## Quick Start

### Run Unit Tests Only (No Redis Required)

```bash
# Run all unit tests
./gradlew test

# Run tests for a specific module
./gradlew :core:test
./gradlew :aggregation:test
./gradlew :cdc:test
```

### Run Integration Tests (Redis Required)

```bash
# 1. Start Redis
docker-compose up -d

# 2. Run integration tests
./gradlew integrationTest

# 3. Stop Redis
docker-compose down
```

## Test Configuration

### Gradle Test Tasks

The project provides two test tasks:

- **`test`** - Runs unit tests only (excludes `@Tag("integration")`)
- **`integrationTest`** - Runs integration tests only (includes `@Tag("integration")`)
- **`check`** - Runs both unit and integration tests

### Test Tags

Tests are organized using JUnit 5 tags:

- **Unit tests**: No tag (default)
- **Integration tests**: `@Tag("integration")`

Example integration test:
```java
@Tag("integration")
public class RedisRegistryIntegrationExample {
    @Test
    public void testServiceRegistryAndDiscovery() throws Exception {
        // Test code that requires Redis
    }
}
```

## Environment Setup

### Docker Compose

The project includes a `docker-compose.yml` file for test infrastructure:

```bash
# Start services
docker-compose up -d

# Check status
docker-compose ps

# View logs
docker-compose logs -f redis

# Stop services
docker-compose down

# Stop and remove volumes
docker-compose down -v
```

### Redis Configuration

Integration tests use the `REDIS_URL` environment variable:

```bash
# Default
redis://127.0.0.1:6379

# Custom Redis
export REDIS_URL=redis://custom-host:6379
./gradlew integrationTest
```

## Common Test Scenarios

### Daily Development

```bash
# Quick feedback - run unit tests only
./gradlew test --parallel
```

### Before Commit

```bash
# Run all tests to ensure nothing is broken
docker-compose up -d
./gradlew clean check
docker-compose down
```

### Specific Module Testing

```bash
# Test specific module (unit tests)
./gradlew :core:test

# Test specific module (integration tests)
docker-compose up -d
./gradlew :core:integrationTest
docker-compose down
```

### Specific Test Class

```bash
# Run specific unit test class
./gradlew :core:test --tests "MessageTest"

# Run specific integration test
docker-compose up -d
./gradlew :core:integrationTest --tests "RedisRegistryIntegrationExample"
docker-compose down
```

### Test with Debug Output

```bash
# Run with info level logging
./gradlew test --info

# Run with debug logging
./gradlew test --debug

# Show standard output
./gradlew test --console=plain
```

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Tests
on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '11'
      - name: Run unit tests
        run: ./gradlew test --parallel

  integration-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '11'
      - name: Start Redis
        run: docker-compose up -d
      - name: Run integration tests
        run: ./gradlew integrationTest
      - name: Stop Redis
        run: docker-compose down
```

## Test Module Structure

```
streaming/
├── core/
│   └── src/test/java/
│       ├── *Test.java                    # Unit tests
│       └── *IntegrationExample.java      # Integration tests (@Tag("integration"))
├── aggregation/
│   └── src/test/java/
│       ├── *Test.java                    # Unit tests
│       └── *IntegrationExample.java      # Integration tests (@Tag("integration"))
└── docker-compose.yml                    # Test infrastructure
```

## Test Coverage

Run tests with coverage reports (requires JaCoCo plugin):

```bash
./gradlew test jacocoTestReport

# View report
open build/reports/jacoco/test/html/index.html
```

## Troubleshooting

### Tests Fail with "Connection refused"

**Problem**: Integration tests can't connect to Redis.

**Solution**:
```bash
# Make sure Redis is running
docker-compose ps

# Check Redis health
docker exec streaming-redis-test redis-cli ping
# Should return: PONG

# Restart Redis if needed
docker-compose restart redis
```

### Unit Tests Run Integration Tests

**Problem**: Integration tests run during `./gradlew test`.

**Solution**: Make sure integration test classes have `@Tag("integration")` annotation:
```java
@Tag("integration")
public class MyIntegrationTest {
    // ...
}
```

### Gradle Wrapper Issues

**Problem**: `./gradlew` command fails.

**Solution**:
```bash
# Regenerate wrapper
gradle wrapper --gradle-version 8.5

# Make executable
chmod +x gradlew
```

## Best Practices

1. **Keep unit tests fast** - Mock external dependencies
2. **Make integration tests reliable** - Use docker-compose for consistent environment
3. **Clean up after integration tests** - Stop containers when done
4. **Run unit tests frequently** - They're fast and don't need setup
5. **Run integration tests before commits** - Catch integration issues early
6. **Use tags consistently** - All integration tests should have `@Tag("integration")`

## Module-Specific Notes

### Core Module
- Contains registry and MQ integration tests
- Requires Redis for integration tests
- Most critical for integration testing

### Aggregation Module
- Tests PV counter and Top-K analyzer
- Integration tests verify Redis sorted set operations

### CDC Module
- Integration tests require external databases (disabled by default)
- Use `@Disabled` annotation for tests requiring complex setup

### Sink/Source Modules
- File-based tests don't require Redis
- Some integration tests may need additional services

## Further Reading

- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)
- [Gradle Testing](https://docs.gradle.org/current/userguide/java_testing.html)
- [Docker Compose](https://docs.docker.com/compose/)
