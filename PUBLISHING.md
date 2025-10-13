# 发布到 Maven Central 指南 (2025 新方式)

本文档说明如何使用新的 **Central Portal** 将项目发布到 Maven Central。

> **⚠️ 重要**: OSSRH 已于 2025年6月30日关闭,必须使用新的 Central Portal!

## 前置要求

### 1. 注册 Central Portal 账号

1. 访问 https://central.sonatype.com
2. 使用 GitHub 账号登录
3. 验证命名空间所有权 (io.github.cuihairu)

### 2. 生成 User Token

1. 登录 Central Portal
2. 点击右上角头像 → **View Account**
3. 点击 **Generate User Token**
4. 保存 Username 和 Token (只显示一次!)

### 3. 配置本地凭据

编辑 `~/.gradle/gradle.properties`:

```properties
# Central Portal 凭据
centralPortalUsername=YOUR_USERNAME_FROM_TOKEN
centralPortalToken=YOUR_TOKEN_FROM_ABOVE

# GPG 签名
signing.keyId=YOUR_GPG_KEY_ID
signing.password=YOUR_GPG_PASSWORD
signing.secretKeyRingFile=/path/to/.gnupg/secring.gpg
```

### 已完成的配置：
- ✅ Maven Central 账号（使用 GitHub 登录）
- ✅ GPG 密钥生成和上传
- ✅ 本地 `~/.gradle/gradle.properties` 配置

## 本地发布

### 测试本地发布

```bash
# 发布到本地 Maven 仓库（不需要签名）
./gradlew publishToMavenLocal
```

### 发布到 Maven Central

```bash
# 发布所有模块
./gradlew publish

# 发布单个模块
./gradlew :core:publish
./gradlew :registry:publish
```

### 在 Central Portal 完成发布

**新方式 (2025)**:

1. 登录 https://central.sonatype.com
2. 点击左侧 **Publishing** → **Deployments**
3. 找到你刚才上传的部署
4. 检查状态:
   - ✅ **VALIDATED**: 验证通过,可以发布
   - ❌ **FAILED**: 查看错误信息
5. 点击 **Publish** 按钮
6. 等待同步到 Maven Central (约 10-30 分钟)
7. 检查发布状态: https://search.maven.org/search?q=g:io.github.cuihairu.redis-streaming

**旧方式 (已废弃)**:
~~1. 登录 https://s01.oss.sonatype.org/~~
~~2. 点击 "Staging Repositories"~~
~~3. Close → Release~~

## GitHub Actions 自动发布

### 配置 GitHub Secrets

在 GitHub 仓库设置中添加以下 Secrets：

**Settings → Secrets and variables → Actions → New repository secret**

#### 1. CENTRAL_PORTAL_USERNAME
从 Central Portal 生成的 User Token 中获取 Username

#### 2. CENTRAL_PORTAL_TOKEN
从 Central Portal 生成的 User Token 中获取 Token

#### 3. GPG_PRIVATE_KEY

需要先导出 GPG 私钥：

```bash
# 在终端执行（会提示输入密码）
gpg --export-secret-keys --armor 409493B079FD4B9025CBFFC6BA6C50DB52EAF08A
```

复制完整输出（包括 `-----BEGIN PGP PRIVATE KEY BLOCK-----` 和 `-----END PGP PRIVATE KEY BLOCK-----`）到 GitHub Secret。

#### 4. GPG_PASSWORD
GPG 密钥的密码

> **⚠️ 安全提示**: 旧的 OSSRH 凭据应该删除或更新为 Central Portal 凭据!

### 触发自动发布

#### 方式 1: 创建 GitHub Release（推荐）

```bash
# 1. 创建并推送 tag
git tag -a v0.1.0 -m "Release version 0.1.0"
git push origin v0.1.0

# 2. 在 GitHub 网页上创建 Release
# 进入仓库 → Releases → Draft a new release
# 选择刚才创建的 tag，填写 Release notes，点击 "Publish release"

# 3. GitHub Actions 会自动触发发布流程
```

#### 方式 2: 手动触发

1. 进入 GitHub 仓库
2. 点击 "Actions" 标签
3. 选择 "Publish to Maven Central" 工作流
4. 点击 "Run workflow" 按钮
5. 输入版本号（如 `0.1.0`）
6. 点击 "Run workflow"

## 发布流程说明

### 自动发布流程

1. ✅ Checkout 代码
2. ✅ 更新 `build.gradle` 中的版本号
3. ✅ 导入 GPG 私钥
4. ✅ 创建临时 `gradle.properties`
5. ✅ 编译和测试（排除集成测试）
6. ✅ 发布到 Maven Central Staging
7. ✅ 生成 Release Notes
8. ⚠️ **需要手动在 Sonatype 完成 Close 和 Release**

### 手动操作步骤 (新方式)

使用 Central Portal 后,**不再需要**手动 Close 和 Release!

1. 等待 GitHub Actions 完成 (约 5-10 分钟)
2. 登录 https://central.sonatype.com
3. 在 **Publishing** → **Deployments** 中查看状态
4. 如果状态为 **VALIDATED**,点击 **Publish**
5. 等待同步到 Maven Central (约 10-30 分钟)

**对比旧方式**:
~~1. 登录 https://s01.oss.sonatype.org/~~
~~2. 在 "Staging Repositories" 中找到刚才发布的仓库~~
~~3. 执行 "Close" → "Release"~~

## 版本管理

### 版本号规范

遵循语义化版本 (Semantic Versioning)：

- **主版本号 (Major)**: 不兼容的 API 修改
- **次版本号 (Minor)**: 向下兼容的功能新增
- **修订号 (Patch)**: 向下兼容的问题修正

示例：
- `0.1.0` - 初始版本
- `0.1.1` - Bug 修复
- `0.2.0` - 新增功能
- `1.0.0` - 第一个稳定版本

### SNAPSHOT 版本

在 `build.gradle` 中设置 SNAPSHOT 版本：

```gradle
version = '0.2.0-SNAPSHOT'
```

SNAPSHOT 版本会发布到快照仓库，可以随时覆盖更新。

## 验证发布

### 检查 Maven Central

```bash
# 等待 10-30 分钟后检查
curl -I "https://repo1.maven.org/maven2/io/github/cuihairu/streaming/core/0.1.0/core-0.1.0.pom"
```

### 在项目中使用

**Maven:**
```xml
<dependency>
    <groupId>io.github.cuihairu.redis-streaming</groupId>
    <artifactId>core</artifactId>
    <version>0.1.0</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'io.github.cuihairu.redis-streaming:core:0.1.0'
```

## 故障排查

### 问题 1: GPG 签名失败

```
Could not sign publication
```

**解决方案：**
- 检查 `~/.gradle/gradle.properties` 中的 GPG 配置
- 验证 GPG 密钥是否正确：`gpg --list-secret-keys`
- 确认密码正确

### 问题 2: 认证失败

```
401 Unauthorized
```

**解决方案：**
- 检查是否使用了新的 `centralPortalUsername` 和 `centralPortalToken`
- 在 https://central.sonatype.com 重新生成 User Token
- ~~旧的 `ossrhUsername` 和 `ossrhPassword` 已不再有效~~

### 问题 3: POM 验证失败

```
Invalid POM
```

**解决方案：**
- 检查 `build.gradle` 中的 POM 配置
- 确保包含必需字段：name, description, url, licenses, developers, scm

### 问题 4: Javadoc 生成失败

**解决方案：**
- 已在 `build.gradle` 中配置忽略 Lombok 生成的代码错误
- 如仍有问题，检查 Java 源代码注释格式

## 安全注意事项

- ❌ **绝对不要**将 `~/.gradle/gradle.properties` 提交到 Git
- ❌ **绝对不要**将 GPG 私钥明文存储在仓库中
- ✅ 使用 GitHub Secrets 存储敏感信息
- ✅ 定期更新 Sonatype User Token
- ✅ GPG 密钥使用强密码保护

## 相关链接

- **Maven Central Portal**: https://central.sonatype.com (新平台)
- **Maven Central Search**: https://search.maven.org/search?q=g:io.github.cuihairu.redis-streaming
- **项目主页**: https://github.com/cuihairu/redis-streaming
- **Wiki 文档**: https://github.com/cuihairu/redis-streaming/wiki

**已废弃**:
- ~~Sonatype OSSRH: https://s01.oss.sonatype.org/ (2025年6月30日关闭)~~
- ~~JIRA 注册: https://issues.sonatype.org/ (不再需要)~~
