# Docs

This repository builds a VuePress documentation site from `docs/` and publishes it via GitHub Pages (GitHub Actions).

Site: https://cuihairu.github.io/redis-streaming/

The historical GitHub Wiki content has been copied into `docs/wiki/` (and `docs/wiki/en/`) to keep internal links stable while
consolidating docs into this repository.

## Local Preview
```bash
cd docs
npm ci
npm run docs:dev
```

## Index
- Site entry: `docs/README.md` (this page)
- Wiki (migrated): `docs/wiki/Home.md`
- Architecture: `docs/wiki/Architecture.md`
- Deployment: `docs/wiki/Deployment.md`
- Performance: `docs/wiki/Performance.md`
- API overview: `docs/API.md`
- Exactly-once roadmap (Redis runtime): `docs/exactly-once.md`
- CI/CD: `docs/wiki/GitHub-Actions.md`
- Troubleshooting: `docs/wiki/Troubleshooting.md`
- MQ design: `docs/wiki/MQ-Design.md`
- MQ broker interaction: `docs/wiki/MQ-Broker-Interaction.md`
- Registry usage: `docs/wiki/Registry-Guide.md`
- Spring Boot starter: `docs/wiki/Spring-Boot-Starter.md`
- Publishing: `docs/maven-publish.md` (see also `PUBLISHING.md`)
- Retention & ACK policy: `docs/retention-and-ack-policy.md`
