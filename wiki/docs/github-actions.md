# GitHub Actions Self-Hosted Runner 配置指南

本文档描述如何在 Ubuntu 24.04 服务器上配置 GitHub Actions Self-Hosted Runner，用于本项目的 CI/CD。

## 系统要求

- **操作系统**: Ubuntu 24.04 LTS
- **架构**: x86_64 (amd64)
- **内存**: 推荐 8GB 以上（运行完整测试套件需要多个数据库）
- **磁盘**: 推荐 40GB 以上可用空间
- **网络**: 需要访问 GitHub.com、Maven Central 和 Docker Hub

## 必需软件清单

### 1. Java 开发环境

本项目需要 Java 17：

```bash
# 安装 OpenJDK 17
sudo apt update
sudo apt install -y openjdk-17-jdk

# 验证安装
java -version
# 应该输出: openjdk version "17.x.x"
```

### 2. Gradle 构建工具

Gradle 会通过项目中的 gradlew wrapper 自动下载，但需要确保系统满足要求：

```bash
# Gradle wrapper 需要 unzip
sudo apt install -y unzip

# 验证 gradlew 可执行
./gradlew --version
```

### 3. Git 版本控制

```bash
# 安装 Git
sudo apt install -y git

# 配置 Git（可选，用于本地测试）
git config --global user.name "GitHub Actions"
git config --global user.email "actions@github.com"

# 验证安装
git --version
```

### 4. Docker（用于集成测试）

本项目的集成测试需要多个数据库和服务，通过 Docker Compose 启动：

```bash
# 安装 Docker
sudo apt install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# 将 runner 用户加入 docker 组（假设用户名为 runner）
sudo usermod -aG docker runner

# 验证安装
docker --version
docker compose version
```

### 5. 数据库客户端工具（可选，用于调试）

```bash
# Redis 客户端
sudo apt install -y redis-tools

# MySQL 客户端
sudo apt install -y mysql-client

# PostgreSQL 客户端
sudo apt install -y postgresql-client

# 验证安装
redis-cli --version
mysql --version
psql --version
```

### 6. 其他依赖

```bash
# 安装基础构建工具
sudo apt install -y build-essential

# 安装网络工具（用于调试和健康检查）
sudo apt install -y curl wget netcat-openbsd

# 安装进程管理工具
sudo apt install -y tmux screen

# 安装系统监控工具
sudo apt install -y htop iotop
```

## 测试环境服务

本项目的测试需要以下服务：
- **Redis** (必需) - 状态管理、检查点存储
- **MySQL** (必需) - CDC 测试
- **PostgreSQL** (可选) - CDC 测试
- **Elasticsearch** (可选) - Sink 测试

### 快速安装（推荐）

使用项目提供的一键安装脚本：

```bash
# 下载项目（如果还没有）
git clone https://github.com/cuihairu/streaming.git
cd streaming

# 运行安装脚本（需要 sudo 权限）
sudo ./deploy/install-services.sh
```

该脚本会自动完成：
- ✅ 安装 Redis、MySQL、PostgreSQL、Elasticsearch
- ✅ 创建测试数据库和用户
- ✅ 配置 MySQL binlog (CDC 测试需要)
- ✅ 配置 PostgreSQL 逻辑复制
- ✅ 配置所有服务开机自启
- ✅ 验证所有服务状态

**安装后验证：**
```bash
./deploy/verify-services.sh
```

预期输出：
```
=== Verifying Direct Installation Services ===

1. Testing Redis:
   ✓ Redis is running
   redis-cli 7.x.x

2. Testing MySQL:
   ✓ MySQL is running and accessible
   8.0.xx
   Binlog status:
   log_bin         ON
   binlog_format   ROW

3. Testing PostgreSQL:
   ✓ PostgreSQL is running and accessible
   PostgreSQL 15.x

4. Testing Elasticsearch:
   ✓ Elasticsearch is running
   "number" : "8.11.0"

=== Verification Complete ===
```

---

## 手动安装（可选）

如果你希望手动安装或自定义配置，可以参考以下步骤。

### 方式选择建议

| 场景 | 推荐方式 | 原因 |
|------|----------|------|
| GitHub Actions Runner | 直接安装 | 性能好，稳定性高 |
| 共享服务器 | Docker Compose | 隔离性好 |
| 开发环境 | Docker Compose | 易于清理 |

---

## 方式一：Docker Compose 部署（开发环境）

本项目使用 Docker Compose 管理测试所需的服务。

### 服务列表

项目的 `docker-compose.yml` 包含以下服务：

1. **Redis** (必需)
   - 用于状态管理、检查点存储
   - 端口: 6379
   - 容器名: streaming-redis-test

2. **MySQL** (CDC 测试需要)
   - 用于 CDC (Change Data Capture) 测试
   - 端口: 3306
   - 容器名: streaming-mysql-test
   - 配置: 启用 binlog，row 格式

3. **PostgreSQL** (可选)
   - 用于 CDC 测试（可选数据库）
   - 端口: 5432
   - 容器名: streaming-postgres-test
   - 配置: 启用 logical replication

4. **Elasticsearch** (可选)
   - 用于 Sink 测试
   - 端口: 9200, 9300
   - 容器名: streaming-elasticsearch-test

### 启动测试服务

```bash
# 启动所有服务
docker compose up -d

# 查看服务状态
docker compose ps

# 查看服务日志
docker compose logs -f

# 停止所有服务
docker compose down

# 停止并删除数据卷
docker compose down -v
```

### 启动特定服务

```bash
# 只启动 Redis（最小测试环境）
docker compose up -d redis

# 启动 Redis 和 MySQL（CDC 测试）
docker compose up -d redis mysql

# 启动所有服务
docker compose up -d
```

### 服务健康检查

```bash
# 检查 Redis
redis-cli -h 127.0.0.1 -p 6379 ping
# 应该返回: PONG

# 检查 MySQL
mysql -h 127.0.0.1 -P 3306 -u test_user -ptest_password -e "SELECT 1"
# 应该返回: 1

# 检查 PostgreSQL
PGPASSWORD=test_password psql -h 127.0.0.1 -p 5432 -U test_user -d test_db -c "SELECT 1"
# 应该返回: 1

# 检查 Elasticsearch
curl -X GET "localhost:9200/_cluster/health?pretty"
# 应该返回 JSON 格式的集群状态
```

### 测试数据库连接配置

项目的集成测试使用以下默认连接配置：

```properties
# Redis
redis.host=127.0.0.1
redis.port=6379

# MySQL
mysql.host=127.0.0.1
mysql.port=3306
mysql.database=test_db
mysql.username=test_user
mysql.password=test_password

# PostgreSQL
postgres.host=127.0.0.1
postgres.port=5432
postgres.database=test_db
postgres.username=test_user
postgres.password=test_password

# Elasticsearch
elasticsearch.host=127.0.0.1
elasticsearch.port=9200
```

可以通过环境变量覆盖这些配置：

```bash
export REDIS_URL=redis://127.0.0.1:6379
export MYSQL_URL=jdbc:mysql://127.0.0.1:3306/test_db
export POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/test_db
export ELASTICSEARCH_URL=http://127.0.0.1:9200
```

---

## 方式二：直接安装部署（GitHub Actions Runner 推荐）

### 使用一键安装脚本

```bash
# 克隆项目
git clone https://github.com/cuihairu/streaming.git
cd streaming

# 运行安装脚本
sudo ./deploy/install-services.sh

# 验证安装
./deploy/verify-services.sh
```

### 安装脚本说明

`deploy/install-services.sh` 脚本包含：

1. **Redis 安装**
   - 安装 redis-server 和 redis-tools
   - 配置持久化（AOF + RDB）
   - 监听 127.0.0.1:6379
   - 开机自启

2. **MySQL 安装**
   - 安装 mysql-server 8.0
   - 创建测试数据库 `test_db`
   - 创建测试用户 `test_user` / `test_password`
   - 启用 binlog (ROW 格式) - CDC 测试必需
   - 性能优化配置
   - 开机自启

3. **PostgreSQL 安装**
   - 安装 postgresql 15
   - 创建测试数据库 `test_db`
   - 创建测试用户 `test_user` / `test_password`
   - 启用逻辑复制 (wal_level=logical) - CDC 测试需要
   - 开机自启

4. **Elasticsearch 安装**
   - 安装 elasticsearch 8.x
   - 禁用 X-Pack 安全（测试环境）
   - 监听 127.0.0.1:9200
   - 堆内存限制 512MB
   - 开机自启

### 手动安装步骤

如果你希望手动控制每个步骤，可以参考以下详细命令。

#### 1. 安装 Redis

```bash
# 安装 Redis
sudo apt update
sudo apt install -y redis-server

# 配置 Redis（编辑配置文件）
sudo nano /etc/redis/redis.conf

# 推荐配置修改：
# 1. 启用持久化（默认已启用）
#    appendonly yes
#    save 60 1
# 2. 绑定地址（仅本地访问）
#    bind 127.0.0.1
# 3. 设置密码（可选，测试环境可不设）
#    # requirepass your_password

# 启动 Redis 服务
sudo systemctl start redis-server
sudo systemctl enable redis-server

# 验证安装
redis-cli ping
# 应该返回: PONG

# 查看服务状态
sudo systemctl status redis-server
```

### 2. 安装 MySQL

```bash
# 安装 MySQL Server
sudo apt update
sudo apt install -y mysql-server

# 启动 MySQL 服务
sudo systemctl start mysql
sudo systemctl enable mysql

# 运行安全配置脚本（可选，测试环境可跳过）
# sudo mysql_secure_installation

# 配置 MySQL 用户和数据库
sudo mysql << EOF
-- 创建测试数据库
CREATE DATABASE IF NOT EXISTS test_db;

-- 创建测试用户（允许本地访问）
CREATE USER IF NOT EXISTS 'test_user'@'localhost' IDENTIFIED BY 'test_password';
GRANT ALL PRIVILEGES ON test_db.* TO 'test_user'@'localhost';

-- 如果需要允许 127.0.0.1 访问
CREATE USER IF NOT EXISTS 'test_user'@'127.0.0.1' IDENTIFIED BY 'test_password';
GRANT ALL PRIVILEGES ON test_db.* TO 'test_user'@'127.0.0.1';

FLUSH PRIVILEGES;
EOF

# 启用 binlog（用于 CDC 测试）
sudo nano /etc/mysql/mysql.conf.d/mysqld.cnf

# 在 [mysqld] 部分添加以下配置：
# server-id = 1
# log_bin = /var/log/mysql/mysql-bin.log
# binlog_format = ROW
# binlog_row_image = FULL
# expire_logs_days = 7

# 重启 MySQL 使配置生效
sudo systemctl restart mysql

# 验证安装
mysql -h 127.0.0.1 -u test_user -ptest_password test_db -e "SELECT VERSION();"

# 验证 binlog 配置
mysql -u root -e "SHOW VARIABLES LIKE 'log_bin';"
mysql -u root -e "SHOW VARIABLES LIKE 'binlog_format';"
```

#### MySQL 完整配置文件示例

```bash
# 创建或编辑 /etc/mysql/mysql.conf.d/mysqld.cnf
sudo tee -a /etc/mysql/mysql.conf.d/mysqld.cnf > /dev/null << 'EOF'

# CDC 测试所需的 binlog 配置
server-id = 1
log_bin = /var/log/mysql/mysql-bin.log
binlog_format = ROW
binlog_row_image = FULL
expire_logs_days = 7

# 性能优化（可选）
max_connections = 200
innodb_buffer_pool_size = 1G
innodb_log_file_size = 256M
EOF

sudo systemctl restart mysql
```

### 3. 安装 PostgreSQL（可选）

```bash
# 安装 PostgreSQL
sudo apt update
sudo apt install -y postgresql postgresql-contrib

# 启动服务
sudo systemctl start postgresql
sudo systemctl enable postgresql

# 创建测试用户和数据库
sudo -u postgres psql << EOF
-- 创建用户
CREATE USER test_user WITH PASSWORD 'test_password';

-- 创建数据库
CREATE DATABASE test_db OWNER test_user;

-- 授权
GRANT ALL PRIVILEGES ON DATABASE test_db TO test_user;
EOF

# 配置允许本地连接（编辑 pg_hba.conf）
sudo nano /etc/postgresql/*/main/pg_hba.conf

# 添加或确保有以下行：
# local   all             test_user                               md5
# host    all             test_user       127.0.0.1/32            md5

# 启用逻辑复制（用于 CDC 测试）
sudo nano /etc/postgresql/*/main/postgresql.conf

# 修改以下配置：
# wal_level = logical
# max_wal_senders = 4
# max_replication_slots = 4

# 重启 PostgreSQL
sudo systemctl restart postgresql

# 验证安装
PGPASSWORD=test_password psql -h 127.0.0.1 -U test_user -d test_db -c "SELECT version();"
```

### 4. 安装 Elasticsearch（可选）

```bash
# 导入 Elasticsearch GPG 密钥
wget -qO - https://artifacts.elastic.co/GPG-KEY-elasticsearch | sudo gpg --dearmor -o /usr/share/keyrings/elasticsearch-keyring.gpg

# 添加 APT 仓库
echo "deb [signed-by=/usr/share/keyrings/elasticsearch-keyring.gpg] https://artifacts.elastic.co/packages/8.x/apt stable main" | sudo tee /etc/apt/sources.list.d/elastic-8.x.list

# 安装 Elasticsearch
sudo apt update
sudo apt install -y elasticsearch

# 配置 Elasticsearch（测试环境简化配置）
sudo tee /etc/elasticsearch/elasticsearch.yml > /dev/null << 'EOF'
cluster.name: streaming-test
node.name: node-1
path.data: /var/lib/elasticsearch
path.logs: /var/log/elasticsearch
network.host: 127.0.0.1
http.port: 9200
discovery.type: single-node
xpack.security.enabled: false
EOF

# 启动 Elasticsearch
sudo systemctl start elasticsearch
sudo systemctl enable elasticsearch

# 等待启动（可能需要 30-60 秒）
sleep 30

# 验证安装
curl -X GET "localhost:9200/_cluster/health?pretty"
```

### 5. 服务管理命令

```bash
# === 启动所有服务 ===
sudo systemctl start redis-server mysql postgresql elasticsearch

# === 停止所有服务 ===
sudo systemctl stop redis-server mysql postgresql elasticsearch

# === 查看服务状态 ===
sudo systemctl status redis-server
sudo systemctl status mysql
sudo systemctl status postgresql
sudo systemctl status elasticsearch

# === 查看服务日志 ===
sudo journalctl -u redis-server -f
sudo journalctl -u mysql -f
sudo journalctl -u postgresql -f
sudo journalctl -u elasticsearch -f

# === 重启服务 ===
sudo systemctl restart redis-server
sudo systemctl restart mysql
sudo systemctl restart postgresql
sudo systemctl restart elasticsearch
```

### 6. 验证所有服务

项目提供了验证脚本 `deploy/verify-services.sh`，可以直接使用：

```bash
# 直接运行验证脚本
./deploy/verify-services.sh

# 或者从 GitHub 克隆项目后运行
cd /path/to/streaming
./deploy/verify-services.sh
```

脚本会检查以下内容：
- Redis 服务状态和版本
- MySQL 服务状态、版本和 binlog 配置
- PostgreSQL 服务状态和版本
- Elasticsearch 服务状态和版本

预期输出：
```
=== Verifying Direct Installation Services ===

1. Testing Redis:
   ✓ Redis is running
   redis-cli 7.x.x

2. Testing MySQL:
   ✓ MySQL is running and accessible
   8.0.xx
   Binlog status:
   log_bin         ON
   binlog_format   ROW

3. Testing PostgreSQL:
   ✓ PostgreSQL is running and accessible
   PostgreSQL 15.x

4. Testing Elasticsearch:
   ✓ Elasticsearch is running
   "number" : "8.11.0"

=== Verification Complete ===
```

### 7. 直接安装的优缺点

#### 优点
- ✅ **性能更好** - 无容器开销，直接使用宿主机资源
- ✅ **启动更快** - 服务常驻内存，无需容器启动时间
- ✅ **资源占用少** - 无 Docker 守护进程和容器层开销
- ✅ **稳定性高** - 使用 systemd 管理，开机自启，自动重启
- ✅ **易于监控** - 使用标准系统工具（journalctl, systemctl）

#### 缺点
- ❌ **隔离性差** - 可能与其他服务冲突
- ❌ **清理麻烦** - 卸载需要手动清理配置和数据
- ❌ **端口冲突** - 可能与宿主机其他服务冲突
- ❌ **版本管理** - 难以同时运行多个版本

### 8. 故障排查

#### Redis 连接失败

```bash
# 检查 Redis 是否运行
sudo systemctl status redis-server

# 检查端口监听
sudo netstat -tlnp | grep 6379

# 查看日志
sudo journalctl -u redis-server -n 50

# 测试连接
redis-cli ping
```

#### MySQL 连接失败

```bash
# 检查 MySQL 是否运行
sudo systemctl status mysql

# 检查端口监听
sudo netstat -tlnp | grep 3306

# 查看错误日志
sudo tail -f /var/log/mysql/error.log

# 重置用户密码
sudo mysql -e "ALTER USER 'test_user'@'localhost' IDENTIFIED BY 'test_password';"
```

#### PostgreSQL 连接失败

```bash
# 检查 PostgreSQL 是否运行
sudo systemctl status postgresql

# 查看日志
sudo journalctl -u postgresql -n 50

# 检查配置
sudo -u postgres psql -c "\du"  # 查看用户
sudo -u postgres psql -c "\l"   # 查看数据库
```

### 9. 卸载服务

如果需要切换回 Docker 方式：

```bash
# 停止所有服务
sudo systemctl stop redis-server mysql postgresql elasticsearch
sudo systemctl disable redis-server mysql postgresql elasticsearch

# 卸载软件包
sudo apt remove --purge redis-server mysql-server postgresql postgresql-contrib elasticsearch

# 删除数据和配置（谨慎操作！）
sudo rm -rf /var/lib/redis
sudo rm -rf /var/lib/mysql
sudo rm -rf /var/lib/postgresql
sudo rm -rf /var/lib/elasticsearch
sudo rm -rf /etc/redis
sudo rm -rf /etc/mysql
sudo rm -rf /etc/postgresql
sudo rm -rf /etc/elasticsearch

# 清理 APT
sudo apt autoremove
sudo apt autoclean
```

---

## GitHub Actions Runner 安装

### 1. 创建 Runner 用户

```bash
# 创建专用用户
sudo useradd -m -s /bin/bash runner
sudo usermod -aG docker runner

# 切换到 runner 用户
sudo su - runner
```

### 2. 下载并安装 Runner

```bash
# 创建工作目录
mkdir -p ~/actions-runner && cd ~/actions-runner

# 下载最新版本的 runner（检查 GitHub 获取最新版本）
curl -o actions-runner-linux-x64-2.311.0.tar.gz -L \
  https://github.com/actions/runner/releases/download/v2.311.0/actions-runner-linux-x64-2.311.0.tar.gz

# 解压
tar xzf ./actions-runner-linux-x64-2.311.0.tar.gz

# 删除压缩包
rm actions-runner-linux-x64-2.311.0.tar.gz
```

### 3. 配置 Runner

前往 GitHub 仓库获取 token：
1. 访问仓库：`https://github.com/[用户名]/streaming/settings/actions/runners/new`
2. 选择 Linux 和 x64
3. 复制显示的 token

```bash
# 配置 runner（使用从 GitHub 获取的 token）
./config.sh --url https://github.com/[用户名]/streaming --token [YOUR_TOKEN]

# 配置时的选项：
# - Enter name of runner: [默认或自定义名称，如: ubuntu-streaming-runner]
# - Enter additional labels: [可选，例如: ubuntu-24.04,java-17,docker]
# - Enter name of work folder: [默认 _work]
```

### 4. 以服务方式运行（推荐）

```bash
# 退出 runner 用户
exit

# 以 root 用户安装服务
cd /home/runner/actions-runner
sudo ./svc.sh install runner

# 启动服务
sudo ./svc.sh start

# 检查状态
sudo ./svc.sh status

# 设置开机自启
sudo systemctl enable actions.runner.[用户名]-streaming.[runner名称].service
```

### 5. 手动运行（用于测试）

```bash
# 以 runner 用户运行
sudo su - runner
cd ~/actions-runner

# 运行 runner
./run.sh
```

## 验证安装

### 1. 检查所有依赖

```bash
# 创建验证脚本
cat > /tmp/verify-runner.sh << 'EOF'
#!/bin/bash

echo "=== Verifying GitHub Actions Runner Environment ==="
echo

# Java
echo "1. Java:"
java -version 2>&1 | head -1
echo

# Git
echo "2. Git:"
git --version
echo

# Docker
echo "3. Docker:"
docker --version
docker compose version
echo

# Database clients
echo "4. Database Clients:"
redis-cli --version
mysql --version | head -1
psql --version
echo

# Gradle (via wrapper)
echo "5. Gradle:"
if [ -f "./gradlew" ]; then
    ./gradlew --version | grep "Gradle"
else
    echo "gradlew not found (will be available after repo checkout)"
fi
echo

# Disk space
echo "6. Disk Space:"
df -h / | tail -1
echo

# Memory
echo "7. Memory:"
free -h | grep "Mem:"
echo

# Docker services
echo "8. Docker Services:"
docker compose ps 2>/dev/null || echo "No services running"
echo

echo "=== Verification Complete ==="
EOF

chmod +x /tmp/verify-runner.sh
/tmp/verify-runner.sh
```

### 2. 测试 Docker Compose 服务

```bash
# 启动所有测试服务
docker compose up -d

# 等待服务启动
sleep 30

# 检查服务健康状态
docker compose ps

# 测试连接
redis-cli ping
mysql -h 127.0.0.1 -u test_user -ptest_password -e "SELECT VERSION()"

# 停止服务
docker compose down
```

### 3. 测试项目构建

```bash
# 克隆仓库（如果还没有）
git clone https://github.com/[用户名]/streaming.git
cd streaming

# 运行构建（跳过测试）
./gradlew build -x test -x integrationTest

# 运行单元测试（不需要数据库）
./gradlew test

# 启动测试服务并运行集成测试
docker compose up -d
sleep 30  # 等待服务启动
./gradlew integrationTest
docker compose down
```

## GitHub Actions Workflow 示例

创建 `.github/workflows/build.yml`：

```yaml
name: Build and Test

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main, develop ]

jobs:
  build:
    runs-on: self-hosted

    steps:
    - name: Checkout code
      uses: actions/checkout@v4

    - name: Set up JDK 17
      uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'
        cache: gradle

    - name: Grant execute permission for gradlew
      run: chmod +x gradlew

    - name: Build with Gradle
      run: ./gradlew build -x test -x integrationTest

    - name: Run unit tests
      run: ./gradlew test

    - name: Start test services
      run: docker compose up -d

    - name: Wait for services to be ready
      run: |
        echo "Waiting for services to start..."
        timeout 60 bash -c 'until docker compose ps | grep -q "healthy"; do sleep 2; done' || true
        sleep 10

    - name: Verify test services
      run: |
        docker compose ps
        redis-cli ping || echo "Redis not ready"
        mysql -h 127.0.0.1 -u test_user -ptest_password -e "SELECT 1" || echo "MySQL not ready"

    - name: Run integration tests
      run: ./gradlew integrationTest

    - name: Stop test services
      if: always()
      run: docker compose down

    - name: Upload test results
      if: always()
      uses: actions/upload-artifact@v4
      with:
        name: test-results
        path: |
          **/build/test-results/
          **/build/reports/

    - name: Upload build artifacts
      if: success()
      uses: actions/upload-artifact@v4
      with:
        name: build-artifacts
        path: |
          **/build/libs/*.jar
```

### 完整测试 Workflow（包含所有服务）

创建 `.github/workflows/full-test.yml`：

```yaml
name: Full Integration Tests

on:
  schedule:
    - cron: '0 2 * * *'  # 每天凌晨 2 点运行
  workflow_dispatch:  # 允许手动触发

jobs:
  full-test:
    runs-on: self-hosted
    timeout-minutes: 60

    steps:
    - name: Checkout code
      uses: actions/checkout@v4

    - name: Set up JDK 17
      uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'
        cache: gradle

    - name: Clean previous test data
      run: docker compose down -v

    - name: Start all test services
      run: docker compose up -d

    - name: Wait for all services
      run: |
        echo "Waiting for services to be healthy..."
        for i in {1..30}; do
          if docker compose ps | grep -q "unhealthy"; then
            echo "Attempt $i: Some services not healthy yet..."
            sleep 10
          else
            echo "All services are healthy!"
            break
          fi
        done
        docker compose ps

    - name: Run all tests
      run: ./gradlew clean build integrationTest

    - name: Generate test report
      if: always()
      run: ./gradlew testReport

    - name: Collect Docker logs
      if: failure()
      run: |
        mkdir -p build/docker-logs
        docker compose logs > build/docker-logs/all-services.log

    - name: Upload test results and logs
      if: always()
      uses: actions/upload-artifact@v4
      with:
        name: full-test-results
        path: |
          **/build/test-results/
          **/build/reports/
          build/docker-logs/

    - name: Cleanup
      if: always()
      run: docker compose down -v
```

## 维护和管理

### 查看 Runner 日志

```bash
# 查看服务日志
sudo journalctl -u actions.runner.[用户名]-streaming.[runner名称].service -f

# 查看 runner 日志文件
sudo su - runner
cd ~/actions-runner
tail -f _diag/Runner_*.log
tail -f _diag/Worker_*.log
```

### 管理 Docker 服务

```bash
# 查看所有容器
docker ps -a

# 查看服务状态
docker compose ps

# 查看服务日志
docker compose logs redis
docker compose logs mysql
docker compose logs -f  # 所有服务日志

# 重启特定服务
docker compose restart redis

# 清理测试数据
docker compose down -v
```

### 监控资源使用

```bash
# 查看系统资源
htop

# 查看 Docker 资源使用
docker stats

# 查看磁盘使用
df -h
docker system df

# 查看 Docker 卷
docker volume ls
```

### 定期清理

```bash
# 创建清理脚本
cat > /home/runner/cleanup.sh << 'EOF'
#!/bin/bash

echo "=== Cleaning up Docker resources ==="

# 停止所有容器
docker compose down

# 清理未使用的镜像
docker image prune -a -f

# 清理未使用的卷
docker volume prune -f

# 清理未使用的网络
docker network prune -f

# 清理构建缓存
docker builder prune -a -f

# 清理 Gradle 缓存（保留最近 30 天）
find ~/.gradle/caches -type f -atime +30 -delete 2>/dev/null

echo "=== Cleanup complete ==="
docker system df
EOF

chmod +x /home/runner/cleanup.sh

# 设置定期清理（每周日凌晨 3 点）
sudo crontab -e -u runner
# 添加：0 3 * * 0 /home/runner/cleanup.sh >> /home/runner/cleanup.log 2>&1
```

### 更新 Runner

```bash
# 停止服务
sudo ./svc.sh stop

# 以 runner 用户下载新版本
sudo su - runner
cd ~/actions-runner
curl -o actions-runner-linux-x64-[新版本].tar.gz -L \
  https://github.com/actions/runner/releases/download/v[新版本]/actions-runner-linux-x64-[新版本].tar.gz
tar xzf ./actions-runner-linux-x64-[新版本].tar.gz

# 重启服务
exit
sudo ./svc.sh start
```

## 故障排查

### Runner 无法连接到 GitHub

```bash
# 检查网络连接
curl -I https://github.com

# 检查 DNS 解析
nslookup github.com

# 检查防火墙
sudo ufw status

# 测试 Runner 连接
sudo su - runner
cd ~/actions-runner
./run.sh --once
```

### Docker 权限问题

```bash
# 确认用户在 docker 组
groups runner

# 如果不在，添加并重新登录
sudo usermod -aG docker runner
# 注销并重新登录 runner 用户

# 测试 Docker 访问
docker ps
```

### 数据库连接失败

```bash
# 检查容器状态
docker compose ps

# 查看容器日志
docker compose logs redis
docker compose logs mysql

# 检查端口占用
sudo netstat -tlnp | grep -E '6379|3306|5432|9200'

# 重启服务
docker compose restart redis
docker compose restart mysql

# 完全重建
docker compose down -v
docker compose up -d
```

### 磁盘空间不足

```bash
# 查看磁盘使用
df -h
docker system df

# 清理 Docker 资源
docker system prune -a --volumes

# 清理 Gradle 缓存
rm -rf ~/.gradle/caches/

# 清理 runner 工作目录
cd ~/actions-runner/_work
rm -rf */

# 清理旧的 Docker 镜像
docker image prune -a --filter "until=168h"  # 删除 7 天前的镜像
```

### Java 版本问题

```bash
# 检查当前 Java 版本
java -version

# 如果有多个版本，切换到 Java 17
sudo update-alternatives --config java

# 设置 JAVA_HOME
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> ~/.bashrc
source ~/.bashrc
```

### 测试超时或失败

```bash
# 增加服务启动等待时间
docker compose up -d
sleep 60  # 等待更长时间

# 检查服务健康状态
docker compose ps

# 手动测试连接
redis-cli -h 127.0.0.1 -p 6379 ping
mysql -h 127.0.0.1 -u test_user -ptest_password test_db -e "SELECT 1"

# 查看详细测试日志
./gradlew integrationTest --info --stacktrace
```

## 安全建议

1. **最小权限原则**
   - Runner 用户只有必要的权限
   - 不要使用 root 用户运行 runner
   - 限制对敏感文件的访问

2. **网络安全**
   - 使用防火墙限制入站连接
   - 仅允许必要的出站连接
   - 考虑使用 VPN 或专用网络

3. **数据安全**
   - 测试数据库使用弱密码（仅用于测试）
   - 定期清理测试数据
   - 不要在测试环境存储生产数据

4. **定期更新**
   - 定期更新 Ubuntu 系统
   - 定期更新 Docker 和镜像
   - 定期更新 GitHub Actions Runner
   - 定期更新 Java 和其他工具

5. **监控和日志**
   - 监控 runner 状态和资源使用
   - 保留必要的日志用于审计
   - 设置日志轮转避免磁盘满

6. **备份**
   - 定期备份 runner 配置
   - 备份重要的测试数据
   - 文档化恢复流程

## 性能优化

### 1. Docker 性能

```bash
# 配置 Docker 守护进程
sudo nano /etc/docker/daemon.json
```

添加以下配置：

```json
{
  "storage-driver": "overlay2",
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  },
  "default-ulimits": {
    "nofile": {
      "Name": "nofile",
      "Hard": 64000,
      "Soft": 64000
    }
  }
}
```

```bash
# 重启 Docker
sudo systemctl restart docker
```

### 2. Gradle 缓存

```bash
# 配置 Gradle 属性
mkdir -p ~/.gradle
cat > ~/.gradle/gradle.properties << 'EOF'
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configureondemand=true
org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8
EOF
```

### 3. 系统优化

```bash
# 增加文件描述符限制
sudo nano /etc/security/limits.conf
```

添加：

```
runner soft nofile 65536
runner hard nofile 65536
```

## 常用命令参考

```bash
# === Runner 管理 ===
sudo systemctl status actions.runner.*
sudo systemctl start actions.runner.*
sudo systemctl stop actions.runner.*
sudo systemctl restart actions.runner.*

# === Docker 管理 ===
docker compose up -d              # 启动所有服务
docker compose up -d redis mysql  # 启动特定服务
docker compose ps                 # 查看状态
docker compose logs -f            # 查看日志
docker compose down               # 停止服务
docker compose down -v            # 停止并删除数据

# === 数据库连接 ===
redis-cli -h 127.0.0.1 -p 6379
mysql -h 127.0.0.1 -u test_user -ptest_password test_db
PGPASSWORD=test_password psql -h 127.0.0.1 -U test_user -d test_db

# === 系统资源监控 ===
htop                 # 系统资源
docker stats         # Docker 资源
df -h                # 磁盘空间
free -h              # 内存使用
docker system df     # Docker 磁盘使用

# === 日志查看 ===
tail -f ~/actions-runner/_diag/Worker_*.log
docker compose logs -f redis
docker compose logs -f mysql
sudo journalctl -u actions.runner.* -f

# === 清理命令 ===
docker system prune -a              # 清理所有未使用资源
docker volume prune                 # 清理未使用卷
./gradlew clean                     # 清理构建
rm -rf ~/.gradle/caches/modules-2/  # 清理 Gradle 缓存
```

## 参考链接

- [GitHub Actions Runner 官方文档](https://docs.github.com/en/actions/hosting-your-own-runners)
- [Docker 官方文档](https://docs.docker.com/)
- [Docker Compose 官方文档](https://docs.docker.com/compose/)
- [Gradle 官方文档](https://docs.gradle.org/)
- [Redis 官方文档](https://redis.io/documentation)
- [MySQL 官方文档](https://dev.mysql.com/doc/)
- [项目 README](../README.md)
- [项目开发指南](../CLAUDE.md)
