# Security Hardening Design (Redis ACL / TLS / Secrets and Configuration Content)

This page is for review and contains no code changes. Goal: the framework's Redis access today is the minimal "single server + optional password" shape; production needs — an ACL username, TLS, secret management, configuration-content protection — are all missing. This design lays out a phased plan.

## Current State (fact inventory)

1. **The only main-side `Redisson.create`**: the spring-boot-starter's `RedisStreamingAutoConfiguration`, whose `useSingleServer()` sets only address/database/timeouts/connection pool + optional `setPassword` (with `@SuppressWarnings("deprecation")`, commented that Redisson 3.x recommends `CredentialsResolver`).
2. **ACL username (v1 landed 2026-10-11)**: `RedisStreamingProperties.RedisProperties.username` is supported (empty/blank = unset), and `${env:VAR}` lazy credential resolution landed in the same batch.
3. **TLS has no first-class support**: no `rediss://` documentation or examples, no truststore/peer-verification configuration exposed; the user's only path is bringing their own `redisson-spring-boot-starter` (mentioned in the javadoc, without TLS specifics).
4. **Cluster/sentinel likewise rely on the one-liner "use the official starter"**: the framework's own configuration model is single-server only.
5. **Config-center content is plaintext**: the `config` module stores configuration content in Redis as-is (String/Hash), unencrypted, with no sensitive-item marking.
6. **Log redaction guardrails (landed 2026-10-13)**: connection initialization logs the address (acceptable); `LogRedaction` provides name-based classification (`secret.*` prefixes + `password/token/credential/...` fragments) and redaction, `JobSpec.toString()` renders through it, and neither config maps nor free-text logs leak credential literals any more.
7. The modules (mq/registry/config/cdc/sink...) never create their own Redisson and uniformly reuse the injected client — **this part is healthy**; hardening only needs to concentrate on client construction and the configuration boundary.

## Candidate Approaches

### Approach A: ACL username + a ConfigCustomizer SPI (recommended v1)

Smallest change, widest coverage, and no reinventing what Redisson already ships:

- **`RedisProperties` gains `username`**: when non-empty, call `setUsername(...)` (or move to Redisson 3.x's `CredentialsResolver`, switching the password read to lazy resolution supporting `${env:VAR}` forms fetched from the environment, avoiding plaintext in config files).
- **Expose a `ConfigCustomizer` SPI**: the starter adds a `@ConditionalOnMissingBean(ConfigCustomizer.class)` hook interface `void customize(Config config)`, invoked before `Redisson.create`. TLS (truststore/hostname verification), sentinel, cluster, read/write splitting — every advanced shape is one user line in this hook — the framework does not enumerate configuration items one by one (the exposed list can never catch up with Redisson's configuration surface).
- **Documentation**: write `rediss://` + ACL + the ConfigCustomizer as three production access paths into a Deployment guide section (with a minimal Redis ACL rule example: allow only the key prefixes and command groups the framework uses).

### Approach B: the framework mirrors Redisson's full TLS/cluster/sentinel configuration surface

Mirror Redisson's configuration surface into `RedisStreamingProperties`. Controllable, but a huge surface duplicating the official starter — **not recommended** (choose between B and A's Customizer; choose A).

### Approach C: secret and configuration-content protection (v2, an independent increment)

1. **Config-center content encryption**: the `config` module gains a pluggable `ContentCipher` (an `encrypt/decrypt` interface, noop by default for compatibility), enabled per key prefix (e.g. `secure.*`); keys come from the environment/JVM flags, never Redis. **Landed 2026-10-12** (see docs/config.md, "Content Encryption (at rest)").
2. **JobSpec sensitive items**: the convention that `secret.*`-prefixed config values store **references**, not plaintext (`env:NAME` / `sys:NAME`), resolved on the execution side (`RedisJobLauncher`), the factory receiving the resolved copy; the `specHash` input masks secret literals (references are pointers and participate as-is to keep drift detection working); the audit stream's fixed fields carry no config. **Landed 2026-10-12** (convention details and rotation semantics in docs/Control-Plane-Design.md, "the secret reference convention").
3. **Log redaction guardrails (landed 2026-10-13)**: `runtime.redis.control.LogRedaction` — name-based classification (`secret.*` prefixes + `password/passwd/secret/token/credential/apikey/accesskey/privatekey/auth` fragments, case-insensitive) with three exits: `redact(Map)` / `redact(String)` / `renderSpec(JobSpec)`; references (`env:`/`sys:`) are pointers and not redacted, literals are always replaced with `***`. `JobSpec.toString()` reroutes through `renderSpec` (Lombok `@Data`'s generated version would embed config verbatim), so no `log("{}", spec)` leaks any more. The convention stands: credentials only ever enter the lazy-resolution path, and no module may log `password/token/secret` fields. An ArchUnit rule (mechanical enforcement, replacing code-review convention) is not yet introduced — it needs a new external dependency, pending review.

## Migration Path

1. **v1 (one minor release)**: `username` + `${env:}` lazy credentials + the `ConfigCustomizer` SPI + TLS/ACL docs; add starter unit tests (an ApplicationContextRunner verifying the customizer is invoked and username takes effect).
2. **v2**: the config center's `ContentCipher` + the JobSpec `secret.*` reference convention + redacted audit.

## Non-Goals

- No framework-owned authentication system (the control plane's authorizer stays pluggable — see the control-plane design).
- No built-in Vault/KMS clients (the `CredentialsResolver`/`ContentCipher` interfaces are left for integrators to implement).
- No Redis-server ACL rule management (the docs give recommended rules, not managed ones).
