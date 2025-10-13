# 故障排查指南

## Gradle Wrapper 问题

### 问题：找不到 GradleWrapperMain

**错误信息：**
```
Error: Could not find or load main class org.gradle.wrapper.GradleWrapperMain
Caused by: java.lang.ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain
```

**原因：**
Gradle wrapper JAR 文件 (`gradle/wrapper/gradle-wrapper.jar`) 没有提交到仓库，因为 `.gitignore` 中的 `*.jar` 规则排除了所有 JAR 文件。

**解决方案：**

1. 更新 `.gitignore`，添加 wrapper JAR 例外：
```gitignore
# Package Files #
*.jar
*.war
*.nar
*.ear
*.zip
*.tar.gz
*.rar

# Exception: Allow Gradle wrapper JAR
!gradle-wrapper.jar
!gradle/wrapper/gradle-wrapper.jar
```

2. 强制添加 wrapper JAR 到 Git：
```bash
git add -f gradle/wrapper/gradle-wrapper.jar
git commit -m "Add Gradle wrapper JAR"
git push
```

3. 验证修复：
```bash
./gradlew --version
```

**预防措施：**
确保以下 Gradle wrapper 文件都提交到仓库：
- `gradlew` - Linux/Mac 启动脚本
- `gradlew.bat` - Windows 启动脚本
- `gradle/wrapper/gradle-wrapper.jar` - Wrapper JAR (必需!)
- `gradle/wrapper/gradle-wrapper.properties` - Wrapper 配置

---

## GitHub Actions 常见问题

### 1. 服务连接失败

**问题：** 集成测试失败，无法连接 Redis/MySQL

**检查步骤：**
```bash
# 检查服务状态
sudo systemctl status redis-server
sudo systemctl status mysql
sudo systemctl status postgresql

# 验证服务
./deploy/verify-services.sh

# 查看日志
sudo journalctl -u redis-server -n 50
sudo journalctl -u mysql -n 50
```

**解决方案：**
```bash
# 重启服务
sudo systemctl restart redis-server mysql postgresql

# 如果服务未安装，运行安装脚本
sudo ./deploy/install-services.sh
```

### 2. 权限问题

**问题：** GitHub Actions runner 无法访问服务

**解决方案：**
```bash
# 确保 runner 用户有必要的权限
sudo usermod -aG adm runner  # 允许读取日志

# 测试连接
redis-cli ping
mysql -h 127.0.0.1 -u test_user -ptest_password test_db -e "SELECT 1"
```

### 3. 磁盘空间不足

**问题：** 构建失败，磁盘空间不足

**检查：**
```bash
df -h
du -sh ~/.gradle/caches
```

**清理：**
```bash
# 清理 Gradle 缓存
./gradlew clean
rm -rf ~/.gradle/caches/modules-2/

# 清理 Docker（如果使用）
docker system prune -a --volumes

# 清理系统日志
sudo journalctl --vacuum-time=7d
```

### 4. 测试超时

**问题：** 集成测试超时

**检查服务启动时间：**
```bash
# MySQL 可能需要较长启动时间
sudo journalctl -u mysql -n 100

# Elasticsearch 启动较慢
sudo journalctl -u elasticsearch -n 100
```

**解决方案：**
增加工作流中的等待时间，或在 runner 服务器上保持服务运行。

---

## 构建问题

### 1. 依赖下载失败

**问题：** 无法下载依赖

**解决方案：**
```bash
# 检查网络连接
curl -I https://repo1.maven.org/maven2/

# 清理并重试
./gradlew clean build --refresh-dependencies

# 使用国内镜像（可选）
# 在 build.gradle 中添加阿里云镜像
```

### 2. 编译错误

**问题：** Java 版本不匹配

**检查：**
```bash
java -version
# 应该显示 Java 17
```

**解决方案：**
```bash
# 安装 Java 17
sudo apt install -y openjdk-17-jdk

# 设置默认版本
sudo update-alternatives --config java
```

### 3. 测试失败

**问题：** 特定模块测试失败

**调试步骤：**
```bash
# 运行特定模块的测试
./gradlew :core:test --info --stacktrace

# 只运行失败的测试
./gradlew :core:test --tests "ServiceInstanceTest" --info

# 查看测试报告
open build/reports/tests/test/index.html
```

---

## Maven 发布问题

### 1. GPG 签名失败

**问题：** 无法签名 artifacts

**检查：**
```bash
# 验证 GPG 密钥
gpg --list-keys

# 验证环境变量
echo $GPG_KEY_ID
echo $GPG_PASSWORD
```

**解决方案：**
- 确保 GitHub Secrets 配置正确
- 验证 GPG 密钥未过期
- 检查私钥格式是否完整

### 2. OSSRH 认证失败

**问题：** 401 Unauthorized

**检查：**
- OSSRH 账号是否激活
- Group ID 是否已授权
- 用户名密码是否正确

**解决方案：**
登录 https://s01.oss.sonatype.org/ 验证凭据

### 3. POM 验证失败

**问题：** POM 缺少必需元素

**解决方案：**
检查 `build.gradle` 中的 `pom` 配置，确保包含：
- name
- description
- url
- licenses
- developers
- scm

---

## 性能优化

### 1. 加速构建

```bash
# 启用 Gradle 守护进程和并行构建
mkdir -p ~/.gradle
cat >> ~/.gradle/gradle.properties << 'EOF'
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.jvmargs=-Xmx2g
EOF
```

### 2. 减少测试时间

```bash
# 只运行单元测试
./gradlew test

# 跳过所有测试
./gradlew build -x test -x integrationTest
```

### 3. 缓存优化

GitHub Actions 已配置 Gradle 缓存：
```yaml
- uses: actions/setup-java@v4
  with:
    cache: gradle  # 自动缓存依赖
```

---

## 获取帮助

### 查看日志

**Gradle 构建日志：**
```bash
./gradlew build --info --stacktrace
```

**服务日志：**
```bash
sudo journalctl -u redis-server -f
sudo journalctl -u mysql -f
```

**GitHub Actions 日志：**
访问 Actions 页面查看详细运行日志

### 常用命令

```bash
# 验证环境
./deploy/verify-services.sh

# 完整清理
./gradlew clean
sudo systemctl restart redis-server mysql postgresql

# 重新构建
./gradlew build --refresh-dependencies
```

### 报告问题

如果问题仍未解决，请：
1. 收集错误日志
2. 检查 GitHub Actions 运行日志
3. 在项目 Issues 页面报告
