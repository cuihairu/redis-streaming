# 安全加固设计（Redis ACL / TLS / 密钥与配置内容）

本文供评审，不含代码改动。目标：框架当前对 Redis 的接入是"单机 + 密码可选"的最小形态，生产化所需的 ACL 用户名、TLS、密钥管理、配置内容保护都缺位。本设计给出分阶段方案。

## 现状（事实盘点）

1. **唯一的 main 侧 `Redisson.create`**：`spring-boot-starter` `RedisStreamingAutoConfiguration`，`useSingleServer()` 只设 address/database/超时/连接池 + 可选 `setPassword`（带 `@SuppressWarnings("deprecation")`，注释说明 Redisson 4.x 推荐 `CredentialsResolver`）。
2. **ACL 用户名传不进去**：`RedisStreamingProperties.RedisProperties` 只有 `address/database/password/...`，没有 `username`——Redis 6+ ACL（`default` 之外的用户）无法通过 starter 配置使用。
3. **TLS 无一等支持**：没有 `rediss://` 文档与示例，没有 truststore/peer 验证相关配置暴露；用户唯一路径是自带 `redisson-spring-boot-starter`（javadoc 里提了，但没有 TLS 具体指引）。
4. **集群/哨兵同样只靠"用官方 starter"一句话**：框架自身配置模型只有单机。
5. **配置中心内容明文**：`config` 模块把配置内容原样存 Redis（String/Hash），无加密、无敏感项标记。
6. **无日志脱敏**：连接初始化打 address（可接受），但框架层没有"密码/凭证永不出现在日志与异常"的统一约束；`JobSpec.config` 是自由 `Map<String,String>`，用户容易把密码塞进去并存进控制面 spec 存储（明文 + 进历史）。
7. 各模块（mq/registry/config/cdc/sink...）不自行创建 Redisson，统一复用注入的 client——**这一点是好的**，加固只需集中在 client 构造与配置边界。

## 可选方案

### 方案 A：ACL 用户名 + ConfigCustomizer SPI（推荐 v1）

改动最小、覆盖面最大，且不重复造 Redisson 已有的轮子：

- **`RedisProperties` 补 `username`**：非空则 `setUsername(...)`（或走 Redisson 4.x 的 `CredentialsResolver`，同时把 password 读取改为惰性解析，支持 `${env:VAR}` 形式从环境取，避免明文落配置文件）。
- **暴露 `ConfigCustomizer` SPI**：starter 增加 `@ConditionalOnMissingBean(ConfigCustomizer.class)` 的钩子接口 `void customize(Config config)`，在 `Redisson.create` 之前调用。TLS（truststore/hostname verification）、哨兵、集群、读写分离等一切高级形态都由用户在这个钩子里一行接入——框架不逐项暴露配置项（暴露项永远追不上 Redisson 的配置面）。
- **文档**：`rediss://` + ACL + ConfigCustomizer 三条生产接入路径写成 Deployment 指南章节（配最小 Redis ACL 规则示例：仅放行框架用到的 key 前缀与命令组）。

### 方案 B：框架内置全套 TLS/集群/哨兵配置项

把 Redisson 的配置面镜像到 `RedisStreamingProperties`。可控性好，但配置面巨大且与官方 starter 重复——**不推荐**（与方案 A 的 Customizer 二选一，选 A）。

### 方案 C：密钥与配置内容保护（v2，独立增量）

1. **配置中心内容加密**：`config` 模块加可插拔 `ContentCipher`（接口 `encrypt/decrypt`，默认 noop 保持兼容），按 key 前缀（如 `secure.*`）启用；密钥来源走环境/JVM 参数，不入 Redis。
2. **JobSpec 敏感项**：约定 `secret.*` 前缀的 config 值存**引用**而非明文（如 `env:DB_PASS`），执行侧解析；spec 存储/审计/`specHash` 计算前对敏感项脱敏显示。
3. **日志脱敏护栏**：统一约定——凭证只进 `CredentialsResolver`，任何模块不得把 `password/token/secret` 字段打进日志（code review + 简单 ArchUnit 规则可后续加）。

## 迁移路径

1. **v1（一个小版本可完成）**：`username` + `${env:}` 惰性凭证 + `ConfigCustomizer` SPI + TLS/ACL 文档；补 starter 单测（ApplicationContextRunner 验证 customizer 被调用、username 生效）。
2. **v2**：配置中心 `ContentCipher` + JobSpec `secret.*` 引用约定 + 脱敏审计。

## 非目标

- 不做框架自有认证体系（控制面授权器维持可插拔，见控制面设计）。
- 不内置 Vault/KMS 客户端（`CredentialsResolver`/`ContentCipher` 接口留给接入方实现）。
- 不做 Redis 服务端的 ACL 规则管理（文档给推荐规则，不代管）。
