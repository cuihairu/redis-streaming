# Security Policy

## Supported Versions

Security fixes land on the latest `main` and the most recent release line.

| Version | Supported          |
| ------- | ------------------ |
| 0.2.x   | :white_check_mark: |
| < 0.2   | :x:                |

## Reporting a Vulnerability

Please report vulnerabilities through **GitHub's private vulnerability reporting**
(Security tab of this repository → "Report a vulnerability"). Please do not open
public issues for anything you believe is exploitable.

Expect an initial response within one week. If a report is accepted, the fix
lands on `main` and a patched release follows; the reporter is credited unless
they prefer otherwise.

## Security Surfaces in the Framework

- **Redis connections**: the starter wires Redisson from
  `redis-streaming.redis.*` properties (address/password/username); for cluster,
  sentinel or TLS deployments use the official `redisson-spring-boot-starter`
  (see docs/Security-Hardening-Design.md).
- **Config center at-rest encryption** (`config` module): install a
  `ContentCipher` bean and dataIds under `secure.` are stored encrypted in Redis
  (docs/config.md).
- **Job spec secret references** (`runtime` control plane): `secret.*` config
  keys hold `env:`/`sys:` references resolved at deploy time; spec hashes and
  audit entries never carry secret material (docs/Control-Plane-Design.md).

## Design Notes

The staged hardening plan (ACL/username, lazy credentials via
`CredentialsResolver`, content protection) is documented in
docs/Security-Hardening-Design.md, including its explicit non-goals (no
built-in Vault/KMS client, no framework-owned authentication system).
