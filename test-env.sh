#!/bin/bash

# 测试环境管理脚本
# 用法: ./test-env.sh [start|stop|restart|status|logs]

set -e

COMPOSE_FILE="docker-compose.test.yml"

function start_env() {
    echo "🚀 Starting test environment..."

    # 清理可能存在的容器
    docker-compose -f $COMPOSE_FILE down -v --remove-orphans 2>/dev/null || true

    # 启动服务
    docker-compose -f $COMPOSE_FILE up -d

    echo "⏳ Waiting for services to be healthy..."

    # 等待 Redis
    echo "  Waiting for Redis..."
    timeout 30 bash -c 'until redis-cli ping > /dev/null 2>&1; do sleep 1; done'
    echo "  ✅ Redis is ready"

    # 等待 MySQL
    echo "  Waiting for MySQL..."
    timeout 60 bash -c 'until mysqladmin ping -h localhost -u root -ptest_password > /dev/null 2>&1; do sleep 2; done'
    echo "  ✅ MySQL is ready"

    # 等待 PostgreSQL
    echo "  Waiting for PostgreSQL..."
    timeout 30 bash -c 'until pg_isready -h localhost -U test_user > /dev/null 2>&1; do sleep 1; done'
    echo "  ✅ PostgreSQL is ready"

    # 等待 Elasticsearch
    echo "  Waiting for Elasticsearch..."
    timeout 90 bash -c 'until curl -f http://localhost:9200/_cluster/health > /dev/null 2>&1; do sleep 2; done'
    echo "  ✅ Elasticsearch is ready"

    echo ""
    echo "🎉 Test environment is ready!"
    echo ""
    echo "Connection details:"
    echo "  Redis:         redis://localhost:6379"
    echo "  MySQL:         jdbc:mysql://localhost:3306/test_db (user: test_user, password: test_password)"
    echo "  PostgreSQL:    jdbc:postgresql://localhost:5432/test_db (user: test_user, password: test_password)"
    echo "  Elasticsearch: http://localhost:9200"
    echo ""
    echo "Run tests with: ./gradlew test integrationTest"
}

function stop_env() {
    echo "🛑 Stopping test environment..."
    docker-compose -f $COMPOSE_FILE down -v --remove-orphans
    echo "✅ Test environment stopped and cleaned up"
}

function restart_env() {
    echo "🔄 Restarting test environment..."
    stop_env
    start_env
}

function show_status() {
    echo "📊 Test environment status:"
    docker-compose -f $COMPOSE_FILE ps
}

function show_logs() {
    local service=${2:-""}
    if [ -n "$service" ]; then
        echo "📋 Showing logs for $service..."
        docker-compose -f $COMPOSE_FILE logs -f $service
    else
        echo "📋 Showing logs for all services..."
        docker-compose -f $COMPOSE_FILE logs -f
    fi
}

function run_tests() {
    echo "🧪 Running tests..."

    # 检查环境是否运行
    if ! docker-compose -f $COMPOSE_FILE ps | grep -q "Up"; then
        echo "❌ Test environment is not running. Starting it first..."
        start_env
    fi

    # 运行测试
    echo "Running unit tests..."
    ./gradlew test --parallel

    echo "Running integration tests..."
    ./gradlew integrationTest

    echo "✅ All tests completed!"
}

function show_help() {
    echo "Usage: $0 [start|stop|restart|status|logs|test|help]"
    echo ""
    echo "Commands:"
    echo "  start    - Start the test environment"
    echo "  stop     - Stop and cleanup the test environment"
    echo "  restart  - Restart the test environment"
    echo "  status   - Show status of all services"
    echo "  logs     - Show logs (optionally specify service name)"
    echo "  test     - Run all tests (starts environment if needed)"
    echo "  help     - Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 start"
    echo "  $0 logs redis"
    echo "  $0 test"
}

# 主程序
case "${1:-help}" in
    start)
        start_env
        ;;
    stop)
        stop_env
        ;;
    restart)
        restart_env
        ;;
    status)
        show_status
        ;;
    logs)
        show_logs "$@"
        ;;
    test)
        run_tests
        ;;
    help|--help|-h)
        show_help
        ;;
    *)
        echo "❌ Unknown command: $1"
        echo ""
        show_help
        exit 1
        ;;
esac