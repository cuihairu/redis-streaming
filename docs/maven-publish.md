# Maven Central 发布指南

本文档描述如何将项目发布到 Maven Central。

## 前置准备

### 1. 注册 Sonatype OSSRH 账号

1. 访问 [Sonatype JIRA](https://issues.sonatype.org/)
2. 创建账号
3. 创建一个新的 Issue，申请 `io.github.cuihairu` Group ID
   - Issue Type: New Project
   - Group Id: `io.github.cuihairu`
   - Project URL: `https://github.com/cuihairu/streaming`
   - SCM URL: `https://github.com/cuihairu/streaming.git`
4. 等待审核通过（通常 1-2 个工作日）

### 2. 生成 GPG 密钥

用于签名发布的 artifacts：

```bash
# 生成 GPG 密钥对
gpg --gen-key

# 按照提示输入：
# - 姓名
# - 邮箱
# - 密码（记住这个密码）

# 查看生成的密钥
gpg --list-keys

# 输出示例：
# pub   rsa3072 2024-01-01 [SC] [expires: 2026-01-01]
#       ABCD1234EFGH5678IJKL9012MNOP3456QRST7890  # 这是 Key ID
# uid           [ultimate] Your Name <your.email@example.com>
# sub   rsa3072 2024-01-01 [E] [expires: 2026-01-01]

# 导出公钥到密钥服务器
gpg --keyserver keys.openpgp.org --send-keys YOUR_KEY_ID

# 导出私钥（用于 GitHub Secrets）
gpg --export-secret-keys --armor YOUR_KEY_ID > private-key.asc
```

### 3. 配置 GitHub Secrets

在 GitHub 仓库设置中添加以下 Secrets：

| Secret Name | 说明 | 如何获取 |
|------------|------|----------|
| `OSSRH_USERNAME` | Sonatype JIRA 用户名 | 你的 JIRA 账号 |
| `OSSRH_PASSWORD` | Sonatype JIRA 密码 | 你的 JIRA 密码 |
| `GPG_KEY_ID` | GPG 密钥 ID | `gpg --list-keys` 输出的完整 40 字符 ID |
| `GPG_PASSWORD` | GPG 密钥密码 | 生成密钥时设置的密码 |
| `GPG_SECRET_KEY` | GPG 私钥内容 | `cat private-key.asc` 的完整输出 |

**添加 Secrets 步骤：**
1. 进入 GitHub 仓库
2. Settings → Secrets and variables → Actions
3. 点击 "New repository secret"
4. 添加上述 5 个 secrets

### 4. 本地测试（可选）

创建 `~/.gradle/gradle.properties` 文件：

```properties
# OSSRH credentials
ossrhUsername=YOUR_JIRA_USERNAME
ossrhPassword=YOUR_JIRA_PASSWORD

# GPG signing
signing.keyId=YOUR_GPG_KEY_ID
signing.password=YOUR_GPG_PASSWORD
signing.secretKeyRingFile=/home/your-user/.gnupg/secring.gpg
```

**注意：** 不要将 `gradle.properties` 提交到 Git！

---

## 发布流程

### 方式一：通过 GitHub Release（推荐）

1. **创建 Release**

   在 GitHub 仓库页面：
   ```
   Releases → Draft a new release
   ```

2. **填写信息**
   - Tag version: `v1.0.0`（必须以 `v` 开头）
   - Release title: `Release 1.0.0`
   - Description: 描述此版本的变更

3. **发布**
   - 点击 "Publish release"
   - GitHub Actions 会自动触发 `publish-maven.yml` 工作流

4. **监控进度**
   ```
   Actions → Publish to Maven Central → 查看运行日志
   ```

### 方式二：手动触发

1. 进入 Actions 页面
2. 选择 "Publish to Maven Central" 工作流
3. 点击 "Run workflow"
4. 输入版本号（例如：`1.0.0`）
5. 点击 "Run workflow" 确认

### 方式三：本地发布

```bash
# 1. 更新版本号
sed -i "s/version = '.*'/version = '1.0.0'/" build.gradle

# 2. 构建和测试
./gradlew clean build -x integrationTest

# 3. 发布到 Maven Central
./gradlew publish

# 4. 登录 Sonatype OSSRH 管理后台
# https://s01.oss.sonatype.org/
# 查看 Staging Repositories，关闭并发布
```

---

## 发布后操作

### 1. 验证发布

发布到 Maven Central 后，需要等待 15-30 分钟同步：

```bash
# 搜索你的 artifacts
https://search.maven.org/search?q=g:io.github.cuihairu.redis-streaming
```

### 2. 测试依赖

创建测试项目验证：

**Maven：**
```xml
<dependency>
    <groupId>io.github.cuihairu.redis-streaming</groupId>
    <artifactId>core</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Gradle：**
```gradle
implementation 'io.github.cuihairu.redis-streaming:core:1.0.0'
```

### 3. 更新文档

- 更新 README.md 中的版本号
- 更新使用示例
- 更新 CHANGELOG.md

---

## 版本管理策略

### 版本号格式

使用语义化版本 (Semantic Versioning)：`MAJOR.MINOR.PATCH`

- **MAJOR**: 不兼容的 API 变更
- **MINOR**: 向后兼容的功能新增
- **PATCH**: 向后兼容的问题修正

### 版本类型

#### 1. SNAPSHOT 版本（开发版）
```gradle
version = '1.0.0-SNAPSHOT'
```
- 用于开发和测试
- 每次构建都会覆盖之前的版本
- 不需要 GPG 签名
- 发布到 Snapshots 仓库

**发布 SNAPSHOT：**
```bash
./gradlew publish
```

#### 2. Release 版本（正式版）
```gradle
version = '1.0.0'
```
- 用于生产环境
- 不可变，一旦发布不能修改
- 必须 GPG 签名
- 发布到 Releases 仓库

**发布 Release：**
- 创建 GitHub Release（推荐）
- 或手动触发工作流

---

## 自动化工作流

### publish-maven.yml 工作流

**触发条件：**
1. 创建 GitHub Release
2. 手动触发（输入版本号）

**执行步骤：**
1. ✅ 检出代码
2. ✅ 设置 Java 17
3. ✅ 确定版本号（从 Release tag 或手动输入）
4. ✅ 更新 build.gradle 中的版本号
5. ✅ 验证 GPG 配置
6. ✅ 构建和测试（跳过集成测试）
7. ✅ 发布到 Maven Central
8. ✅ 生成 Release Notes
9. ✅ 在 Release 页面添加评论

**环境变量：**
- `OSSRH_USERNAME` - Sonatype 用户名
- `OSSRH_PASSWORD` - Sonatype 密码
- `GPG_KEY_ID` - GPG 密钥 ID
- `GPG_PASSWORD` - GPG 密钥密码
- `GPG_SECRET_KEY` - GPG 私钥内容

---

## 发布的模块

以下模块会被发布到 Maven Central：

1. **core** - 核心功能
   ```gradle
   implementation 'io.github.cuihairu.redis-streaming:core:VERSION'
   ```

2. **state** - 状态管理
   ```gradle
   implementation 'io.github.cuihairu.redis-streaming:state:VERSION'
   ```

3. **checkpoint** - 检查点机制
   ```gradle
   implementation 'io.github.cuihairu.redis-streaming:checkpoint:VERSION'
   ```

4. **window** - 窗口操作
   ```gradle
   implementation 'io.github.cuihairu.redis-streaming:window:VERSION'
   ```

5. **aggregation** - 聚合计算
   ```gradle
   implementation 'io.github.cuihairu.redis-streaming:aggregation:VERSION'
   ```

6. **spring-boot-starter** - Spring Boot 集成
   ```gradle
   implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:VERSION'
   ```

**注意：** `examples` 模块不会被发布。

---

## 故障排查

### 1. GPG 签名失败

**错误信息：**
```
gpg: signing failed: No such file or directory
```

**解决方案：**
- 确认 `GPG_SECRET_KEY` secret 包含完整的私钥内容
- 确认 `GPG_KEY_ID` 是完整的 40 字符 ID
- 确认 `GPG_PASSWORD` 正确

### 2. 认证失败

**错误信息：**
```
401 Unauthorized
```

**解决方案：**
- 确认 OSSRH 账号已激活
- 确认 `OSSRH_USERNAME` 和 `OSSRH_PASSWORD` 正确
- 确认 Group ID 已经被授权

### 3. POM 验证失败

**错误信息：**
```
Invalid POM: Missing required element
```

**解决方案：**
- 检查 `build.gradle` 中的 `pom` 配置
- 确保包含：name, description, url, licenses, developers, scm

### 4. 本地测试发布

不发布到 Maven Central，只发布到本地 Maven 仓库：

```bash
./gradlew publishToMavenLocal
```

查看发布的文件：
```bash
ls -la ~/.m2/repository/io/github/cuihairu/streaming/
```

### 5. 查看详细日志

```bash
./gradlew publish --info --stacktrace
```

---

## 版本发布清单

发布新版本前的检查清单：

- [ ] 所有测试通过
- [ ] 更新 CHANGELOG.md
- [ ] 更新 README.md 中的版本号
- [ ] 更新代码中的示例版本
- [ ] 确认没有 SNAPSHOT 依赖
- [ ] 创建 Git tag
- [ ] 创建 GitHub Release
- [ ] 等待 CI 完成发布
- [ ] 验证 Maven Central 上的 artifacts
- [ ] 测试新版本的依赖引入
- [ ] 发布公告（如有必要）

---

## 常用命令

```bash
# 查看当前版本
grep "version = " build.gradle

# 构建所有模块
./gradlew clean build -x integrationTest

# 发布到本地（测试）
./gradlew publishToMavenLocal

# 发布到 Maven Central
./gradlew publish

# 查看发布任务
./gradlew tasks --group publishing

# 查看 POM 文件
./gradlew :core:generatePomFileForMavenPublication
cat core/build/publications/maven/pom-default.xml

# 清理
./gradlew clean
```

---

## 参考资料

- [Maven Central Publishing Guide](https://central.sonatype.org/publish/publish-guide/)
- [Gradle Maven Publish Plugin](https://docs.gradle.org/current/userguide/publishing_maven.html)
- [Semantic Versioning](https://semver.org/)
- [GPG Quick Start](https://www.gnupg.org/gph/en/manual.html)
