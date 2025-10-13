# GitHub Actions Workflows

本目录包含项目的 CI/CD 工作流配置，使用 **self-hosted runner** 在 Ubuntu 24.04 服务器上运行。

## 工作流概览

### 1. 基础构建和测试 (`build.yml`)

**触发条件：**
- Push 到 `main` 或 `develop` 分支
- Pull Request 到 `main` 或 `develop` 分支

**执行内容：**
- ✅ 检出代码
- ✅ 设置 Java 17 环境
- ✅ 构建所有模块（跳过测试）
- ✅ 运行单元测试（不需要外部服务）
- ✅ 上传测试结果和构建产物

**运行时间：** ~5-10 分钟

---

### 2. 集成测试 (`integration-test.yml`)

**触发条件：**
- Push 到 `main` 或 `develop` 分支
- Pull Request 到 `main` 或 `develop` 分支
- 每天凌晨 2 点自动运行
- 手动触发

**执行内容：**
- ✅ 检查服务状态（Redis、MySQL、PostgreSQL）
- ✅ 自动启动 Docker Compose（如果直接安装的服务不可用）
- ✅ 运行集成测试
- ✅ 收集测试结果和 Docker 日志
- ✅ 清理 Docker 容器

**运行时间：** ~15-30 分钟

**特性：**
- 智能检测：优先使用直接安装的服务，如果不可用则使用 Docker Compose
- 失败时自动收集 Docker 日志

---

### 3. 完整测试套件 (`full-test.yml`)

**触发条件：**
- 每周日凌晨 3 点自动运行
- 手动触发

**执行内容：**
- ✅ 完全清理并重建
- ✅ 强制使用 Docker Compose 服务
- ✅ 运行所有测试（单元测试 + 集成测试）
- ✅ 生成完整测试报告
- ✅ 收集所有日志（Docker + 系统服务）

**运行时间：** ~30-60 分钟

**特性：**
- 最严格的测试流程
- 完整的日志收集和归档（保留 14 天）

---

### 4. 代码质量检查 (`code-quality.yml`)

**触发条件：**
- Push 到 `main` 或 `develop` 分支
- Pull Request 到 `main` 或 `develop` 分支

**执行内容：**
- ✅ 代码风格检查（Checkstyle）
- ✅ 静态分析
- ✅ 构建验证
- ✅ 项目结构检查

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
| Build | 每次提交 | 5-10分钟 | 无 | 🔴 阻止合并 |
| Integration Test | 每次提交 + 每日 | 15-30分钟 | Redis, MySQL | 🔴 阻止合并 |
| Full Test | 每周 | 30-60分钟 | 全部 | 🟡 警告 |
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

工作流会在运行时验证 Java 版本：
```yaml
- name: Verify Java version
  run: |
    java -version
    echo "JAVA_HOME=$JAVA_HOME"
```

如果需要恢复使用 `actions/setup-java`（例如在 GitHub 托管的 runner 上运行），可以取消注释相关配置。

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
