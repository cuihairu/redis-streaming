# 发布到 Maven Central 指南

本文档说明如何将项目发布到 Maven Central。

## 前置要求

已完成的配置：
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

### 在 Sonatype 完成发布

1. 登录 https://s01.oss.sonatype.org/
2. 点击左侧 "Staging Repositories"
3. 找到你的仓库（搜索 `iogithubcuihairu`）
4. 选中仓库，点击 "Close" 按钮
   - 系统会验证 POM、签名、Javadoc 等
   - 等待验证完成（约 1-2 分钟）
5. 验证通过后，点击 "Release" 按钮
6. 等待同步到 Maven Central（10-30 分钟）
7. 检查发布状态：https://search.maven.org/search?q=g:io.github.cuihairu.redis-streaming

## GitHub Actions 自动发布

### 配置 GitHub Secrets

在 GitHub 仓库设置中添加以下 Secrets：

**Settings → Secrets and variables → Actions → New repository secret**

#### 1. OSSRH_USERNAME
```
PmSSD2
```

#### 2. OSSRH_PASSWORD
```
qKhvRYsSVWvBI1JxQwmcOC2xdpAYHIyBQ
```

#### 3. GPG_PRIVATE_KEY

需要先导出 GPG 私钥：

```bash
# 在终端执行（会提示输入密码）
gpg --export-secret-keys --armor 409493B079FD4B9025CBFFC6BA6C50DB52EAF08A
```

复制完整输出（包括 `-----BEGIN PGP PRIVATE KEY BLOCK-----` 和 `-----END PGP PRIVATE KEY BLOCK-----`）到 GitHub Secret。

#### 4. GPG_PASSWORD
```
kl055620363
```

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

### 手动操作步骤

即使使用 GitHub Actions，仍需在 Sonatype 完成最后步骤：

1. 等待 GitHub Actions 完成（约 5-10 分钟）
2. 登录 https://s01.oss.sonatype.org/
3. 在 "Staging Repositories" 中找到刚才发布的仓库
4. 执行 "Close" → "Release"

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
- 检查 `ossrhUsername` 和 `ossrhPassword` 是否正确
- 在 https://central.sonatype.com 重新生成 User Token

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

- Maven Central: https://search.maven.org/search?q=g:io.github.cuihairu.redis-streaming
- Sonatype OSSRH: https://s01.oss.sonatype.org/
- Central Portal: https://central.sonatype.com/
- 项目主页: https://github.com/cuihairu/streaming
