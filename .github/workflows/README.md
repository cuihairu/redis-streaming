# GitHub Actions Workflows

本目录包含项目的 CI/CD 工作流配置，使用 **self-hosted runner** 在 Ubuntu 24.04 服务器上运行。

## 工作流概览

### 1. 基础构建（`build.yml`）

**触发条件：**
- Push 到 `main` 或 `develop` 分支
- Pull Request 到 `main` 或 `develop` 分支

**执行内容：**
- ✅ 检出代码
- ✅ 使用自托管 runner 已安装的 Java 17（校验并断言主版本=17）
- ✅ 启用 Gradle 缓存（wrapper + caches）
- ✅ 仅构建产物（`./gradlew assemble`，不运行测试）
- ✅ 上传构建产物（`**/build/libs/*.jar`）

**并发控制：**
- `concurrency: { group: build-${{ github.ref }}, cancel-in-progress: true }`

**运行时间：** ~5-10 分钟

---

### 2. 测试（单元 + 全量集成，`test.yml`）

**触发条件：**
- Push/PR 到任意分支（可按需收敛）

**执行内容：**
- ✅ 启用 Gradle 缓存（wrapper + caches）
- ✅ 启动 Redis + MySQL + PostgreSQL（`docker-compose.minimal.yml`）
- ✅ 健康检查：等待服务 `healthy/Up`
- ✅ 单元测试：`./gradlew test --parallel --continue`
- ✅ 集成测试（全量）：`./gradlew integrationTest --info --stacktrace`
- ✅ 上传单元/集成测试报告与 Docker 日志（失败排查更友好）
- ✅ 仅清理当前 compose 资源（避免 `docker system prune` 的全局副作用）

**并发控制：**
- `concurrency: { group: tests-${{ github.ref }}, cancel-in-progress: true }`

**运行时间：** ~15-30 分钟

**特性：**
- 智能检测：优先使用直接安装的服务，如果不可用则使用 Docker Compose
- 失败时自动收集 Docker 日志

---

> 说明：当前未启用单独的 `full-test.yml`。如需每周强制全量测试，可新增工作流并复用 `test.yml` 中的步骤模板。

---

### 3. 代码质量检查（`code-quality.yml`）

**触发条件：**
- Push 到 `main` 或 `develop` 分支
- Pull Request 到 `main` 或 `develop` 分支

**执行内容：**
- ✅ 启用 Gradle 缓存
- ✅ 代码风格检查（Checkstyle）
- ✅ 静态分析与构建验证（不运行测试）
- ✅ 项目结构检查

**并发控制：**
- `concurrency: { group: code-quality-${{ github.ref }}, cancel-in-progress: true }`

**运行时间：** ~5-10 分钟

---

## 使用指南

### 查看工作流运行状态

访问 GitHub 仓库的 Actions 页面：
```
https://github.com/[用户名]/streaming/actions
```

### 手动触发工作流

1. 进入 Actions 页面
2. 选择 "Integration Tests" 或 "Full Test Suite"
3. 点击 "Run workflow" 按钮
4. 选择分支并确认

### 查看测试结果

1. 进入具体的工作流运行页面
2. 查看每个步骤的日志输出
3. 下载 Artifacts（测试结果、构建产物、日志文件）

### Artifacts 保留时间

- **单元测试结果**: 7 天
- **集成测试结果**: 7 天
- **完整测试结果**: 14 天
- **构建产物**: 7 天
- **Docker 日志**: 3 天
- **代码质量报告**: 7 天

---

## Self-Hosted Runner 要求

### 必需软件
- **Java 17** (OpenJDK) - 必须预先安装并配置在 PATH 中
- **Gradle** (通过 wrapper 自动下载)
- **Git**
- **Docker** (可选，如果使用 Docker Compose 方式)

### 可选服务（二选一）

#### 选项 1：Docker Compose（推荐）
- Redis
- MySQL
- PostgreSQL
- Elasticsearch

#### 选项 2：直接安装
```bash
# 安装服务
sudo apt install -y redis-server mysql-server postgresql

# 验证安装
./deploy/verify-services.sh
```

详细配置步骤请参考：[docs/github-actions.md](../docs/github-actions.md)

---

## 工作流矩阵

| 工作流 | 频率 | 时长 | 需要服务 | 失败影响 |
|--------|------|------|----------|----------|
| Build | 每次提交 | 2-5分钟 | 无 | 🔴 阻止合并 |
| Tests | 每次提交 | 15-30分钟 | Redis, MySQL, PostgreSQL | 🔴 阻止合并 |
| Code Quality | 每次提交 | 5-10分钟 | 无 | 🟡 警告 |

---

## 工作流优化说明

### 使用系统 Java 而非 actions/setup-java

所有工作流已优化为**直接使用 Runner 服务器上预装的 Java 17**，而不是每次都通过 `actions/setup-java` 下载。

**优势：**
- ⚡ **更快的启动时间** - 跳过 Java 下载和设置步骤
- 💾 **节省磁盘空间** - 不需要缓存多个 Java 版本
- 🔧 **简化配置** - 只需在服务器上安装一次 Java

**前提条件：**
Runner 服务器必须预先安装 Java 17：
```bash
# 安装 Java 17
sudo apt install -y openjdk-17-jdk

# 验证安装
java -version
# 应该显示: openjdk version "17.x.x"

# 设置 JAVA_HOME（如果需要）
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> ~/.bashrc
```

工作流会在运行时验证并断言 Java 版本：
```yaml
- name: Verify Java version
  run: |
    java -version
    echo "JAVA_HOME=$JAVA_HOME"

- name: Assert Java 17
  run: |
    MAJOR=$(java -version 2>&1 | awk -F[".] '/version/ {print $2}')
    if [ "$MAJOR" != "17" ]; then
      echo "Expected Java 17 but found: $(java -version 2>&1 | head -n 1)"
      exit 1
    fi
```

如果需要恢复使用 `actions/setup-java`（例如在 GitHub 托管的 runner 上运行），可以取消注释相关配置。

### Java 版本策略（JDK 21 本地兼容 + CI 用 JDK 17）

- 源码与目标兼容性均为 Java 17；构建时使用 `javac --release 17`，即便本地 JDK 是 21 也会针对 17 API 编译。
- CI（自托管 runner）要求并断言 Java 17（在工作流中有断言步骤）。
- 本地开发可使用 JDK 21 或更高版本，Gradle 会通过 `options.release = 17` 保证编译目标为 17。

---

## 故障排查

### 工作流失败常见原因

#### 1. 服务连接失败
```bash
# 检查服务状态
./deploy/verify-services.sh

# 重启服务
docker compose restart
# 或
sudo systemctl restart redis-server mysql
```

#### 2. 端口冲突
```bash
# 检查端口占用
sudo netstat -tlnp | grep -E '6379|3306|5432'
```

#### 3. 磁盘空间不足
```bash
# 清理 Docker
docker system prune -a --volumes

# 清理 Gradle 缓存
./gradlew clean
rm -rf ~/.gradle/caches/
```

#### 4. 权限问题
```bash
# 确认 runner 用户在 docker 组
groups runner

# 添加到 docker 组
sudo usermod -aG docker runner
```

---

## 最佳实践

### 1. 分支保护规则

建议在 GitHub 仓库设置中配置：

**main 分支：**
- ✅ 要求 "Build" 工作流通过
- ✅ 要求 "Integration Tests" 工作流通过
- ✅ 要求至少 1 个审核通过
- ✅ 要求分支为最新

**develop 分支：**
- ✅ 要求 "Build" 工作流通过
- ✅ 可选：要求 "Integration Tests" 工作流通过

### 2. 监控建议

设置 GitHub 通知：
- 工作流失败时发送邮件
- PR 状态检查失败时通知

### 3. 资源优化

```bash
# Runner 服务器定期清理（添加到 crontab）
0 4 * * * docker system prune -f >> /home/runner/cleanup.log 2>&1
0 4 * * 0 rm -rf ~/.gradle/caches/modules-2/ >> /home/runner/cleanup.log 2>&1
```

---

## 扩展配置

### 添加新的工作流

1. 在 `.github/workflows/` 目录创建新的 YAML 文件
2. 使用 `runs-on: self-hosted` 指定 runner
3. 添加必要的步骤和检查
4. 提交并推送到仓库

### 示例：发布工作流

```yaml
name: Release

on:
  push:
    tags:
      - 'v*'

jobs:
  release:
    runs-on: self-hosted
    steps:
      - uses: actions/checkout@v4
      - name: Build release
        run: ./gradlew build -x test
      - name: Create GitHub release
        uses: actions/create-release@v1
        # ... 更多步骤
```

---

## 参考资料

- [GitHub Actions 官方文档](https://docs.github.com/en/actions)
- [Self-Hosted Runner 配置](../docs/github-actions.md)
- [项目构建说明](../CLAUDE.md)
