# Publishing Process

[中文](../Publishing.md) | [English](Publishing-en.md)

---

Publish to Maven Central (Central Portal):

- Project guide: [../PUBLISHING.md](../PUBLISHING.md)
- Release notes: [maven-publish.md](./maven-publish.md)
- Migration record: [archive/MIGRATION_TO_CENTRAL_PORTAL.md](./archive/MIGRATION_TO_CENTRAL_PORTAL.md)

Key points (from root `build.gradle` and `.github/workflows/ci.yml`):

- Plugin: `com.vanniktech.maven.publish` (0.29.0), `publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)` + `signAllPublications`
- Coordinates: group `io.github.cuihairu.redis-streaming`, artifactId per module; `examples` module is not published
- Version: derived by axion-release from `v*` git tags (`scmVersion`, tag prefix `v`); `workflow_dispatch` can override with `-Pversion=`
- Triggers: Release published / `v*` tag push / manual dispatch (see `ci.yml` Publish step and credentials secrets convention)
- Credentials self-check task: `./gradlew checkCentralPortalCreds` (only prints whether credentials are visible, does not output secrets)

---

**Version**: 0.2.0
**Last Updated**: 2026-10-05