#!/bin/bash

# Redis-Streaming Package Refactoring Script
# From: io.github.cuihairu.streaming
# To: io.github.cuihairu.redis.streaming

set -e

echo "=== Redis-Streaming Package Refactoring ==="
echo "Starting package rename..."
echo ""

# Step 1: Move directory structures
echo "Step 1: Creating new package structure..."
for module in core mq registry state checkpoint watermark window aggregation table join cdc sink source reliability cep metrics spring-boot-starter examples runtime; do
    if [ -d "$module/src" ]; then
        echo "Processing module: $module"

        # Process main sources
        if [ -d "$module/src/main/java/io/github/cuihairu/streaming" ]; then
            mkdir -p "$module/src/main/java/io/github/cuihairu/redis"
            mv "$module/src/main/java/io/github/cuihairu/streaming" "$module/src/main/java/io/github/cuihairu/redis/" 2>/dev/null || true
        fi

        # Process test sources
        if [ -d "$module/src/test/java/io/github/cuihairu/streaming" ]; then
            mkdir -p "$module/src/test/java/io/github/cuihairu/redis"
            mv "$module/src/test/java/io/github/cuihairu/streaming" "$module/src/test/java/io/github/cuihairu/redis/" 2>/dev/null || true
        fi
    fi
done

echo ""
echo "Step 2: Updating package declarations..."
# Update package declarations in all Java files
find . -type f -name "*.java" -not -path "*/build/*" -not -path "*/.gradle/*" -not -path "*/src/main/java.backup/*" -not -path "*/src/test.backup/*" -not -path "*/core.backup/*" -exec sed -i '' 's/package io\.github\.cuihairu\.streaming/package io.github.cuihairu.redis.streaming/g' {} \;

echo ""
echo "Step 3: Updating import statements..."
# Update import statements in all Java files
find . -type f -name "*.java" -not -path "*/build/*" -not -path "*/.gradle/*" -not -path "*/src/main/java.backup/*" -not -path "*/src/test.backup/*" -not -path "*/core.backup/*" -exec sed -i '' 's/import io\.github\.cuihairu\.streaming/import io.github.cuihairu.redis.streaming/g' {} \;

echo ""
echo "Step 4: Updating spring.factories if exists..."
# Update Spring Boot auto-configuration files
if [ -f "spring-boot-starter/src/main/resources/META-INF/spring.factories" ]; then
    sed -i '' 's/io\.github\.cuihairu\.streaming/io.github.cuihairu.redis.streaming/g' spring-boot-starter/src/main/resources/META-INF/spring.factories
fi

if [ -f "spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports" ]; then
    sed -i '' 's/io\.github\.cuihairu\.streaming/io.github.cuihairu.redis.streaming/g' spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
fi

echo ""
echo "Step 5: Cleaning up empty directories..."
# Remove old empty directories
find . -type d -path "*/io/github/cuihairu/streaming" -empty -delete 2>/dev/null || true
find . -type d -path "*/io/github/cuihairu" -empty -delete 2>/dev/null || true
find . -type d -path "*/io/github" -empty -delete 2>/dev/null || true
find . -type d -path "*/io" -empty -delete 2>/dev/null || true

echo ""
echo "=== Refactoring Complete! ==="
echo ""
echo "Next steps:"
echo "1. Review changes: git diff"
echo "2. Update REFACTORING_CHECKLIST.md"
echo "3. Run: ./gradlew clean build"
echo "4. Run: ./gradlew test"
echo ""
