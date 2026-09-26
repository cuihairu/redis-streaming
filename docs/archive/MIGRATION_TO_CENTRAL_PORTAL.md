# 迁移到 Central Portal 指南

## 重要通知

**OSSRH 已于 2025年6月30日关闭!** 所有发布到 Maven Central 的操作必须迁移到新的 Central Portal。

## 变更对比

### 旧方式 (OSSRH - 已废弃)

```
开发者 → s01.oss.sonatype.org (OSSRH) → 手动 Close/Release → Maven Central
```

**特点**:
- [需要在 JIRA 注册账号]
- [使用 Nexus 界面手动操作]
- [需要手动 Close 和 Release]
- [配置复杂]

### 新方式 (Central Portal - 现在使用)

```
开发者 → central.sonatype.com (Portal) → 自动验证 → 一键 Publish → Maven Central
```

**特点**:
- [使用 GitHub 账号登录]
- [现代化 Web 界面]
- [自动验证,一键发布]
- [配置简单]

## 迁移步骤

### 步骤 1: 注册 Central Portal 账号

1. 访问 https://central.sonatype.com
2. 点击 **Sign in with GitHub**
3. 授权 GitHub OAuth

### 步骤 2: 验证命名空间所有权

如果你之前在 OSSRH 有命名空间:

1. 登录 Central Portal
2. 进入 **Namespaces**
3. 点击 **Migrate Namespace**
4. 按提示完成迁移

如果是新命名空间:

1. 在 Central Portal 中申请新命名空间
2. 按要求验证所有权 (GitHub 仓库或域名 DNS)

### 步骤 3: 生成 User Token

1. 登录 Central Portal
2. 点击右上角头像 → **View Account**
3. 点击 **Generate User Token**
4. **立即保存** Username 和 Token (只显示一次!)

### 步骤 4: 更新本地配置

编辑 `~/.gradle/gradle.properties`:

**删除旧配置**:
```properties
# ❌ 删除这些
ossrhUsername=...
ossrhPassword=...
```

**添加新配置**:
```properties
# ✅ 添加这些（注意：Central Portal 使用 password 字段传递 User Token）
centralPortalUsername=YOUR_USERNAME_FROM_TOKEN_PAGE
centralPortalPassword=YOUR_USER_TOKEN_STRING
```

### 步骤 5: 更新 build.gradle

**旧配置** (删除):
```gradle
repositories {
    maven {
        name = "OSSRH"
        url = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
        credentials {
            username = project.findProperty("ossrhUsername")
            password = project.findProperty("ossrhPassword")
        }
    }
}
```

**新配置** (使用):
```gradle
repositories {
    maven {
        name = "CentralPortal"
        url = "https://central.sonatype.com/api/v1/publisher/upload"
        credentials {
            username = project.findProperty("centralPortalUsername")
            password = project.findProperty("centralPortalPassword")
        }
    }
}
```

### 步骤 6: 更新 GitHub Secrets

在 GitHub 仓库设置中:

**删除旧 Secrets**:
- [`OSSRH_USERNAME`]
- [`OSSRH_PASSWORD`]

**添加新 Secrets**:
- [`CENTRAL_PORTAL_USERNAME`（用户名）]
- [`CENTRAL_PORTAL_TOKEN`（User Token；在工作流中映射为 PASSWORD）]

### 步骤 7: 更新 GitHub Actions 工作流

**旧配置** (删除):
```yaml
env:
  OSSRH_USERNAME: ${{ secrets.OSSRH_USERNAME }}
  OSSRH_PASSWORD: ${{ secrets.OSSRH_PASSWORD }}
```

**新配置** (使用):
```yaml
env:
  CENTRAL_PORTAL_USERNAME: ${{ secrets.CENTRAL_PORTAL_USERNAME }}
  CENTRAL_PORTAL_PASSWORD: ${{ secrets.CENTRAL_PORTAL_TOKEN }}
```

> 说明：Vanniktech 官方插件在 CENTRAL_PORTAL 模式读取的是 `CENTRAL_PORTAL_USERNAME` 与 `CENTRAL_PORTAL_PASSWORD`（或 Gradle 属性 `centralPortalUsername` / `centralPortalPassword`）。User Token 作为“密码”传递，变量名不要写成 `CENTRAL_PORTAL_TOKEN`，否则会导致 404。

### 步骤 8: 测试发布

```bash
# 1. 本地测试
./gradlew publishToMavenLocal

# 2. 发布到 Central Portal（推荐使用插件任务）
./gradlew -Pversion=0.1.0 publishAllPublicationsToMavenCentralRepository

# 3. 在 Central Portal 查看
# 访问 https://central.sonatype.com
# Publishing → Deployments
```

## 发布流程变化

### 旧流程 (OSSRH)

1. 使用 `./gradlew -Pversion=0.1.0 publishAllPublicationsToMavenCentralRepository` 上传
2. 登录 https://s01.oss.sonatype.org
3. 在 Staging Repositories 中找到仓库
4. 手动点击 **Close** 按钮
5. 等待验证 (1-2 分钟)
6. 手动点击 **Release** 按钮
7. 等待同步到 Maven Central (10-30 分钟)

### 新流程 (Central Portal)

1. 使用 `./gradlew -Pversion=0.1.0 publishAllPublicationsToMavenCentralRepository` 上传
2. 登录 https://central.sonatype.com
3. 在 **Publishing** → **Deployments** 查看状态
4. 状态为 **VALIDATED** 后,点击 **Publish** 按钮
5. 等待同步到 Maven Central (10-30 分钟)

**简化点**:
- [不再需要 Close 操作]
- [自动验证,更快]
- [界面更友好]

## 常见问题

### Q1: 旧的 OSSRH 账号怎么办?

A: 旧账号仍然有效,用于迁移命名空间。迁移后可以不再使用。

### Q2: 已发布的旧版本会受影响吗?

A: 不会。已发布到 Maven Central 的版本永久有效。

### Q3: SNAPSHOT 版本怎么发布?

A: SNAPSHOT 版本仍然发布到快照仓库:
```
https://s01.oss.sonatype.org/content/repositories/snapshots/
```

### Q4: 需要重新验证 GPG 密钥吗?

A: 不需要。GPG 签名配置保持不变。

### Q5: 迁移后需要多久才能发布?

A: 命名空间迁移后立即可以发布,无需等待。

## 验证迁移成功

### 1. 检查 Central Portal

登录 https://central.sonatype.com,确认:
- [命名空间已显示]
- [可以生成 User Token]
- [可以查看 Deployments]

### 2. 测试本地发布

```bash
./gradlew publishToMavenLocal
```

应该成功且无错误。

### 3. 测试远程发布

```bash
./gradlew -Pversion=0.1.0 publishAllPublicationsToMavenCentralRepository
```

检查输出中是否包含:
```
> Task :module:publishMavenPublicationToCentralPortalRepository
```

### 4. 在 Portal 查看

访问 https://central.sonatype.com/publishing/deployments,应该能看到刚才上传的内容。

## 回滚方案

如果迁移后遇到问题:

1. **保留旧配置备份**:
   ```bash
   cp ~/.gradle/gradle.properties ~/.gradle/gradle.properties.backup
   ```

2. **临时恢复** (如果 OSSRH 还能访问):
   - 恢复 `ossrhUsername` 和 `ossrhPassword`
   - 恢复 `build.gradle` 中的旧 URL

3. **联系支持**:
   - Central Portal: https://central.sonatype.com/support
   - GitHub Issues: https://github.com/sonatype/central-portal/issues

## 迁移检查清单

- [ ] 注册 Central Portal 账号
- [ ] 迁移命名空间 (如果有)
- [ ] 生成 User Token
- [ ] 更新 `~/.gradle/gradle.properties`
- [ ] 更新 `build.gradle`
- [ ] 更新 GitHub Secrets
- [ ] 更新 GitHub Actions 工作流
- [ ] 测试本地发布
- [ ] 测试远程发布
- [ ] 在 Portal 验证
- [ ] 更新文档

## 参考资料

- **官方迁移指南**: https://central.sonatype.org/pages/ossrh-eol/
- **Central Portal 文档**: https://central.sonatype.org/
- **命名空间迁移**: https://central.sonatype.org/register/central-portal/
- **API 文档**: https://central.sonatype.org/publish/publish-portal-api/

---

**迁移完成后**,你的项目将使用更现代、更简单的发布流程! 🎉
