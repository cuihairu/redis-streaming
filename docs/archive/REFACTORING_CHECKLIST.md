# The Redis-Streaming Refactoring Checklist

## Refactoring Goal
Rename from `io.github.cuihairu.redis-streaming` to `io.github.cuihairu.redis-streaming`

## Refactoring Progress

### 1. Configuration File Updates
- [x] build.gradle - updated the groupId to `io.github.cuihairu.redis-streaming`
- [x] settings.gradle - no update needed
- [x] gradle.properties - no update needed
- [x] .github/workflows/*.yml - updated the package-name references in the CI/CD configuration

### 2. Package Refactoring (io.github.cuihairu.redis-streaming -> io.github.cuihairu.redis.streaming)

#### All Modules
- [x] All modules refactored in bulk via script
- [x] Package declarations updated
- [x] Import statements updated
- [x] Fully qualified class names updated

### 3. Resource File Updates
- [x] spring-boot-starter/src/main/resources/META-INF/spring.factories (updated via script)
- [x] spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports (updated via script)
- [x] Other META-INF configuration files checked

### 4. Documentation Updates
- [x] README.md - updated the project name and example code
- [x] CLAUDE.md - updated the project description and package names
- [x] PUBLISHING.md - updated the Maven coordinate examples
- [x] QUICK_START.md - updated the example code
- [x] RUNNING_EXAMPLES.md - updated the example code
- [x] docs/maven-publish.md - updated the Maven coordinates
- [x] docs/spring-boot-starter-guide.md - updated the dependency examples
- [x] cep/CEP_ISSUES.md - package-name references needed no update (relative paths)

### 5. Build Verification
- [x] ./gradlew clean
- [x] ./gradlew build (succeeded)
- [x] ./gradlew test (succeeded)
- [x] ./gradlew integrationTest (succeeded - requires Redis/MySQL/PostgreSQL)
- [x] All modules compile

### 6. Git-Related
- [ ] Update .gitignore (if needed)
- [ ] Suggestion: rename the GitHub repository to redis-streaming

## The Renaming Map

```
Old package name: io.github.cuihairu.redis-streaming
New package name: io.github.cuihairu.redis.streaming

Old GroupId: io.github.cuihairu.redis-streaming
New GroupId: io.github.cuihairu.redis-streaming
```

## Maven Coordinate Examples

### Old
```xml
<dependency>
    <groupId>io.github.cuihairu.redis-streaming</groupId>
    <artifactId>core</artifactId>
    <version>0.1.0</version>
</dependency>
```

### New
```xml
<dependency>
    <groupId>io.github.cuihairu.redis-streaming</groupId>
    <artifactId>core</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Notes

1. Java package names cannot contain hyphens, so:
   - GroupId: `io.github.cuihairu.redis-streaming` ✅ (hyphens are allowed)
   - Package name: `io.github.cuihairu.redis.streaming` ✅ (dots instead of hyphens)

2. Package refactoring tools:
   - IntelliJ IDEA: Refactor → Rename Package (recommended)
   - Command line: find + sed for bulk replacement

3. Testing strategy:
   - Compile immediately after finishing each module
   - Run the full test suite at the end

## Current Progress
- Done: the build.gradle groupId update
- In progress: the package refactoring
- Not started: the documentation updates

## Estimated Time
- Package refactoring: ~30-60 minutes (depending on tooling)
- Verification tests: ~10-15 minutes
- Documentation updates: ~10 minutes
- Total: ~1-1.5 hours
