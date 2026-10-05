# Developer Guide

[中文](../Developer-Guide.md) | [English](Developer-Guide-en.md)

---

- **Project Structure**: Multi-module Gradle (module list in `settings.gradle`, 20 modules; see [Architecture](../Architecture.md) module table)
- **Full Build**: `./gradlew build` (includes unit + integration tests; `check` depends on `integrationTest`, requires Redis)
- **Unit Tests Only**: `./gradlew test`; Integration Tests: `./gradlew integrationTest`
- **Coverage Gate**: Root project `jacocoRootCoverageVerification` (unit-test deterministic gate: INSTRUCTION ≥ 0.95 AND CLASS ≥ 0.99), report `./gradlew jacocoRootReport` (unit+integration union, uploaded to Codecov)
- **Code Style**: Java 17 (`options.release = 17`) / UTF-8 / 4-space indent; logging via SLF4J; main code uses `-Xlint:deprecation -Xlint:unchecked -Werror` (tests exempt from `-Werror` and unchecked)
- **Version Management**: Version derived by axion-release from `v*` git tags (root `build.gradle` `scmVersion`)
- **Recommended Reading**: [Architecture](../Architecture.md) / [Design](../Design.md) / [Spring-Boot-Starter](../Spring-Boot-Starter.md) / [CEP](../CEP.md)

---

**Version**: 0.2.0
**Last Updated**: 2026-10-05