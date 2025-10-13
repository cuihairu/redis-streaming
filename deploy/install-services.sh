#!/bin/bash

echo "========================================="
echo "  Streaming Framework Test Services Setup"
echo "  For Ubuntu 24.04 GitHub Actions Runner"
echo "========================================="
echo

set -e  # Exit on error

# Update system
echo ">>> Updating system packages..."
sudo apt update

# ==========================================
# 1. Install Redis
# ==========================================
echo
echo ">>> Installing Redis..."
sudo apt install -y redis-server redis-tools

# Configure Redis for testing
sudo tee /etc/redis/redis.conf > /dev/null << 'EOF'
bind 127.0.0.1 ::1
protected-mode yes
port 6379
tcp-backlog 511
timeout 0
tcp-keepalive 300
daemonize no
supervised systemd
pidfile /var/run/redis/redis-server.pid
loglevel notice
logfile /var/log/redis/redis-server.log
databases 16
always-show-logo no
set-proc-title yes
proc-title-template "{title} {listen-addr} {server-mode}"
stop-writes-on-bgsave-error yes
rdbcompression yes
rdbchecksum yes
dbfilename dump.rdb
rdb-del-sync-files no
dir /var/lib/redis
replica-serve-stale-data yes
replica-read-only yes
repl-diskless-sync yes
repl-diskless-sync-delay 5
repl-diskless-sync-max-replicas 0
repl-diskless-load disabled
repl-disable-tcp-nodelay no
replica-priority 100
acllog-max-len 128
lazyfree-lazy-eviction no
lazyfree-lazy-expire no
lazyfree-lazy-server-del no
replica-lazy-flush no
lazyfree-lazy-user-del no
lazyfree-lazy-user-flush no
oom-score-adj no
oom-score-adj-values 0 200 800
disable-thp yes
appendonly yes
appendfilename "appendonly.aof"
appenddirname "appendonlydir"
appendfsync everysec
no-appendfsync-on-rewrite no
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 64mb
aof-load-truncated yes
aof-use-rdb-preamble yes
aof-timestamp-enabled no
slowlog-log-slower-than 10000
slowlog-max-len 128
latency-monitor-threshold 0
notify-keyspace-events ""
hash-max-listpack-entries 512
hash-max-listpack-value 64
list-max-listpack-size -2
list-compress-depth 0
set-max-intset-entries 512
zset-max-listpack-entries 128
zset-max-listpack-value 64
hll-sparse-max-bytes 3000
stream-node-max-bytes 4096
stream-node-max-entries 100
activerehashing yes
client-output-buffer-limit normal 0 0 0
client-output-buffer-limit replica 256mb 64mb 60
client-output-buffer-limit pubsub 32mb 8mb 60
hz 10
dynamic-hz yes
aof-rewrite-incremental-fsync yes
rdb-save-incremental-fsync yes
jemalloc-bg-thread yes
save 60 1
EOF

sudo systemctl restart redis-server
sudo systemctl enable redis-server

echo "✓ Redis installed and configured"

# ==========================================
# 2. Install MySQL
# ==========================================
echo
echo ">>> Installing MySQL..."
sudo apt install -y mysql-server mysql-client

# Wait for MySQL to be ready
sleep 5

# Create test database and user
echo ">>> Configuring MySQL test database..."
sudo mysql << 'EOF'
CREATE DATABASE IF NOT EXISTS test_db;
CREATE USER IF NOT EXISTS 'test_user'@'localhost' IDENTIFIED BY 'test_password';
CREATE USER IF NOT EXISTS 'test_user'@'127.0.0.1' IDENTIFIED BY 'test_password';
GRANT ALL PRIVILEGES ON test_db.* TO 'test_user'@'localhost';
GRANT ALL PRIVILEGES ON test_db.* TO 'test_user'@'127.0.0.1';
FLUSH PRIVILEGES;
EOF

# Configure MySQL for CDC (binlog)
echo ">>> Configuring MySQL binlog for CDC..."
sudo tee /etc/mysql/mysql.conf.d/99-streaming-test.cnf > /dev/null << 'EOF'
[mysqld]
# CDC testing configuration
server-id = 1
log_bin = /var/log/mysql/mysql-bin.log
binlog_format = ROW
binlog_row_image = FULL
expire_logs_days = 7

# Performance optimization
max_connections = 200
innodb_buffer_pool_size = 1G
innodb_log_file_size = 256M

# Character set
character-set-server = utf8mb4
collation-server = utf8mb4_unicode_ci
EOF

sudo systemctl restart mysql
sudo systemctl enable mysql

echo "✓ MySQL installed and configured"

# ==========================================
# 3. Install PostgreSQL (Optional)
# ==========================================
echo
echo ">>> Installing PostgreSQL..."
sudo apt install -y postgresql postgresql-contrib postgresql-client

# Wait for PostgreSQL to be ready
sleep 5

# Create test database and user
echo ">>> Configuring PostgreSQL test database..."
sudo -u postgres psql << 'EOF'
CREATE USER test_user WITH PASSWORD 'test_password';
CREATE DATABASE test_db OWNER test_user;
GRANT ALL PRIVILEGES ON DATABASE test_db TO test_user;
EOF

# Configure PostgreSQL for CDC (logical replication)
echo ">>> Configuring PostgreSQL for logical replication..."
PG_VERSION=$(pg_config --version | grep -oP '\d+' | head -1)
PG_CONF="/etc/postgresql/${PG_VERSION}/main/postgresql.conf"
PG_HBA="/etc/postgresql/${PG_VERSION}/main/pg_hba.conf"

# Update postgresql.conf
sudo tee -a "$PG_CONF" > /dev/null << 'EOF'

# Streaming test configuration
wal_level = logical
max_wal_senders = 4
max_replication_slots = 4
EOF

# Update pg_hba.conf
sudo tee -a "$PG_HBA" > /dev/null << 'EOF'

# Streaming test access
local   all             test_user                               md5
host    all             test_user       127.0.0.1/32            md5
host    all             test_user       ::1/128                 md5
EOF

sudo systemctl restart postgresql
sudo systemctl enable postgresql

echo "✓ PostgreSQL installed and configured"

# ==========================================
# 4. Install Elasticsearch (Optional)
# ==========================================
echo
echo ">>> Installing Elasticsearch (this may take a while)..."

# Install prerequisites
sudo apt install -y apt-transport-https ca-certificates gnupg

# Add Elasticsearch GPG key
wget -qO - https://artifacts.elastic.co/GPG-KEY-elasticsearch | sudo gpg --dearmor -o /usr/share/keyrings/elasticsearch-keyring.gpg

# Add repository
echo "deb [signed-by=/usr/share/keyrings/elasticsearch-keyring.gpg] https://artifacts.elastic.co/packages/8.x/apt stable main" | sudo tee /etc/apt/sources.list.d/elastic-8.x.list

# Install Elasticsearch
sudo apt update
sudo apt install -y elasticsearch

# Configure Elasticsearch for testing
echo ">>> Configuring Elasticsearch..."
sudo tee /etc/elasticsearch/elasticsearch.yml > /dev/null << 'EOF'
cluster.name: streaming-test
node.name: node-1
path.data: /var/lib/elasticsearch
path.logs: /var/log/elasticsearch
network.host: 127.0.0.1
http.port: 9200
discovery.type: single-node
xpack.security.enabled: false
xpack.security.enrollment.enabled: false
xpack.security.http.ssl.enabled: false
xpack.security.transport.ssl.enabled: false
EOF

# Set proper heap size
sudo tee /etc/elasticsearch/jvm.options.d/heap.options > /dev/null << 'EOF'
-Xms512m
-Xmx512m
EOF

sudo systemctl start elasticsearch
sudo systemctl enable elasticsearch

echo "✓ Elasticsearch installed and configured"

# ==========================================
# 5. Verify All Services
# ==========================================
echo
echo "========================================="
echo "  Waiting for services to start..."
echo "========================================="
sleep 30

echo
echo ">>> Verifying service status..."

# Redis
if redis-cli ping > /dev/null 2>&1; then
    echo "✓ Redis is running"
    redis-cli --version
else
    echo "✗ Redis is NOT running"
fi

# MySQL
if mysql -h 127.0.0.1 -u test_user -ptest_password test_db -e "SELECT 1" > /dev/null 2>&1; then
    echo "✓ MySQL is running and accessible"
    mysql -h 127.0.0.1 -u test_user -ptest_password -e "SELECT VERSION();" 2>/dev/null | grep -v VERSION
    echo "  Binlog status:"
    mysql -u root -e "SHOW VARIABLES LIKE 'log_bin';" 2>/dev/null | grep log_bin
    mysql -u root -e "SHOW VARIABLES LIKE 'binlog_format';" 2>/dev/null | grep binlog_format
else
    echo "✗ MySQL is NOT running or not accessible"
fi

# PostgreSQL
if PGPASSWORD=test_password psql -h 127.0.0.1 -U test_user -d test_db -c "SELECT 1" > /dev/null 2>&1; then
    echo "✓ PostgreSQL is running and accessible"
    PGPASSWORD=test_password psql -h 127.0.0.1 -U test_user -d test_db -c "SELECT version();" 2>/dev/null | head -3 | tail -1
else
    echo "✗ PostgreSQL is NOT running or not accessible"
fi

# Elasticsearch
if curl -s http://localhost:9200/_cluster/health > /dev/null 2>&1; then
    echo "✓ Elasticsearch is running"
    curl -s http://localhost:9200 2>/dev/null | grep number | grep -oP '\d+\.\d+\.\d+'
else
    echo "✗ Elasticsearch is NOT running (may need more time to start)"
fi

echo
echo "========================================="
echo "  Installation Complete!"
echo "========================================="
echo
echo "Services installed and configured:"
echo "  - Redis (127.0.0.1:6379)"
echo "  - MySQL (127.0.0.1:3306)"
echo "    Database: test_db"
echo "    User: test_user"
echo "    Password: test_password"
echo "  - PostgreSQL (127.0.0.1:5432)"
echo "    Database: test_db"
echo "    User: test_user"
echo "    Password: test_password"
echo "  - Elasticsearch (127.0.0.1:9200)"
echo
echo "All services are configured to start on boot."
echo
echo "You can verify services anytime by running:"
echo "  ./deploy/verify-services.sh"
echo
