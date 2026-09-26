# GitHub Actions Workflows

本目录包含项目的 CI/CD 工作流配置，使用 **GitHub-hosted runner** (ubuntu-latest) 运行。

## 工作流概览

### 1. CI 流水线（`ci.yml`）

**触发条件：**
- Push 到 `main` 分支
- 创建标签（`v*`）
- Pull Request 到 `main` 分支
- 发布 Release
- 手动触发（workflow_dispatch）

**执行内容：**
- [检出代码]
- [设置 Java 17（使用 actions/setup-java@v4，Temurin 发行版）]
- [启用 Gradle 缓存]
- [验证 Java 环境]
- [启动 Redis 服务（使用 Docker）]
- [运行单元测试和集成测试]
- [清理 Docker 资源]
- [发布到 Maven Central（在触发条件满足时）]

**并发控制：**
- `concurrency: { group: ci-${{ github.ref }}, cancel-in-progress: true }`

**运行时间：** ~15-30 分钟

---

## 使用指南

### 查看工作流运行状态

访问 GitHub 仓库的 Actions 页面：
```
https://github.com/[用户名]/redis-streaming/actions
```

### 手动触发工作流

1. 进入 Actions 页面
2. 选择 "CI" 工作流
3. 点击 "Run workflow" 按钮
4. 选择分支并输入版本（可选）

### 查看测试结果

1. 进入具体的工作流运行页面
2. 查看每个步骤的日志输出
3. 下载 Artifacts（如果有的话）

---

## GitHub-Hosted Runner 优势

### 官方提供的环境
- [**无需维护** - GitHub 负责维护和更新]
- [**开箱即用** - 预装了常用工具和软件]
- [**高性能** - 使用云端高性能计算资源]
- [**可靠性** - 高可用性保证]
- [**安全** - GitHub 管理的安全环境]

### 环境规格
- **操作系统**: Ubuntu Latest（当前为 Ubuntu 22.04）
- **CPU**: 2-core
- **内存**: 7 GB RAM
- **存储**: 14 GB SSD

### 预装软件
- Docker
- Git
- Java 多版本支持（通过 actions/setup-java）
- Node.js
- Python
- 其他常用开发工具

---

## 工作流矩阵

| 工作流 | 频率 | 时长 | 需要服务 | 失败影响 |
|--------|------|------|----------|----------|
| CI | 每次提交/PR | 15-30分钟 | Redis | 🔴 阻止合并 |

---

## 工作流优化说明

### Java 版本管理

使用 `actions/setup-java` action 来管理 Java 版本：
```yaml
- name: Set up JDK 17
  uses: actions/setup-java@v4
  with:
    distribution: temurin
    java-version: '17'
    cache: gradle
```

**优势：**
- [版本一致性 - 保证每次运行使用相同版本]
- [缓存支持 - 自动缓存 Java 和 Gradle]
- [多版本支持 - 可以轻松切换 Java 版本]

### Docker 服务使用

工作流中使用 Docker 来运行 Redis：
```yaml
- name: Start Redis with Docker
  run: |
    docker compose -f docker-compose.yml up -d redis || docker compose up -d redis
```

**优势：**
- [环境隔离 - 测试环境完全独立]
- [版本控制 - 可以指定 Redis 版本]
- [易于清理 - 测试后自动清理]

---

## 故障排查

### 工作流失败常见原因

#### 1. 服务启动失败
查看日志确认 Docker 服务是否正常启动：
```yaml
# 检查 Redis 容器状态
docker ps | grep redis
```

#### 2. 测试超时
GitHub runner 有超时限制，确保测试在合理时间内完成。

#### 3. 依赖下载失败
使用缓存策略减少依赖下载时间：
```yaml
- name: Set up JDK 17
  uses: actions/setup-java@v4
  with:
    cache: gradle
```

#### 4. 内存不足
对于大型测试套件，考虑：
- 分割测试作业
- 使用 `--parallel` 参数并行运行
- 调整 JVM 内存设置

---

## 最佳实践

### 1. 分支保护规则

建议在 GitHub 仓库设置中配置：

**main 分支：**
- [要求 "CI" 工作流通过]
- [要求至少 1 个审核通过]
- [要求分支为最新]

### 2. 监控建议

设置 GitHub 通知：
- 工作流失败时发送邮件
- PR 状态检查失败时通知

### 3. 缓存优化

工作流已启用以下缓存：
- **Gradle 依赖缓存** - 减少依赖下载时间
- **Java 缓存** - 避免重复安装 Java

### 4. 安全考虑

- [使用 GitHub secrets 管理敏感信息]
- [最小权限原则]
- [定期更新 Actions 版本]

---

## 扩展配置

### 添加新的工作流

1. 在 `.github/workflows/` 目录创建新的 YAML 文件
2. 使用 `runs-on: ubuntu-latest` 或其他官方 runner
3. 添加必要的步骤和检查
4. 提交并推送到仓库

### 可用的 Runner 类型

- `ubuntu-latest` - Ubuntu 最新版本（推荐）
- `ubuntu-22.04` - Ubuntu 22.04
- `ubuntu-20.04` - Ubuntu 20.04
- `windows-latest` - Windows 最新版本
- `windows-2022` - Windows 2022
- `macos-latest` - macOS 最新版本
- `macos-13` - macOS 13 (Ventura)
- `macos-14` - macOS 14 (Sonoma)

### 示例：矩阵构建

```yaml
jobs:
  test:
    runs-on: ${{ matrix.os }}
    strategy:
      matrix:
        os: [ubuntu-latest, windows-latest, macos-latest]
        java: [17, 21]
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK ${{ matrix.java }}
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: ${{ matrix.java }}
          cache: gradle
```

---

## 参考资料

- [GitHub Actions 官方文档](https://docs.github.com/en/actions)
- [虚拟环境文档](https://docs.github.com/en/actions/using-github-hosted-runners/about-github-hosted-runners)
- [项目构建说明](../CLAUDE.md)