#!/bin/bash

# Redis-Streaming 完整测试脚本
# 自动启动所需服务并运行所有测试

set -e

echo "=== Redis-Streaming 测试套件 ==="
echo ""

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 1. 启动测试服务
echo -e "${YELLOW}步骤 1: 启动测试服务...${NC}"
docker-compose up -d redis mysql postgres
echo ""

# 2. 等待服务健康
echo -e "${YELLOW}步骤 2: 等待服务就绪...${NC}"
sleep 5

docker-compose ps
echo ""

# 3. 清理 Redis 旧数据
echo -e "${YELLOW}步骤 3: 清理 Redis 数据...${NC}"
docker exec streaming-redis-test redis-cli FLUSHALL
echo "✓ Redis 已清空"
echo ""

# 4. 运行单元测试
echo -e "${YELLOW}步骤 4: 运行单元测试...${NC}"
./gradlew test --console=plain || {
    echo -e "${RED}✗ 单元测试失败${NC}"
    exit 1
}
echo -e "${GREEN}✓ 单元测试通过${NC}"
echo ""

# 5. 运行集成测试
echo -e "${YELLOW}步骤 5: 运行集成测试...${NC}"
./gradlew integrationTest --console=plain || {
    echo -e "${RED}✗ 集成测试失败${NC}"
    exit 1
}
echo -e "${GREEN}✓ 集成测试通过${NC}"
echo ""

# 6. 完成
echo -e "${GREEN}=== 所有测试通过！===${NC}"
echo ""
echo "提示："
echo "  - 查看测试报告: ./gradlew htmlReports"
echo "  - 停止服务: docker-compose down"
echo "  - 清理数据: docker-compose down -v"
