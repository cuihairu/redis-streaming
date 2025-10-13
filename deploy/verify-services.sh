#!/bin/bash

echo "=== Verifying Direct Installation Services ==="
echo

# Redis
echo "1. Testing Redis:"
if redis-cli ping > /dev/null 2>&1; then
    echo "   ✓ Redis is running"
    redis-cli --version
else
    echo "   ✗ Redis is not running"
fi
echo

# MySQL
echo "2. Testing MySQL:"
if mysql -h 127.0.0.1 -u test_user -ptest_password test_db -e "SELECT 1" > /dev/null 2>&1; then
    echo "   ✓ MySQL is running and accessible"
    mysql -h 127.0.0.1 -u test_user -ptest_password -e "SELECT VERSION();" 2>/dev/null | grep -v VERSION
    echo "   Binlog status:"
    mysql -u root -e "SHOW VARIABLES LIKE 'log_bin';" 2>/dev/null | grep log_bin
    mysql -u root -e "SHOW VARIABLES LIKE 'binlog_format';" 2>/dev/null | grep binlog_format
else
    echo "   ✗ MySQL is not running or not accessible"
fi
echo

# PostgreSQL
echo "3. Testing PostgreSQL:"
if PGPASSWORD=test_password psql -h 127.0.0.1 -U test_user -d test_db -c "SELECT 1" > /dev/null 2>&1; then
    echo "   ✓ PostgreSQL is running and accessible"
    PGPASSWORD=test_password psql -h 127.0.0.1 -U test_user -d test_db -c "SELECT version();" 2>/dev/null | head -3 | tail -1
else
    echo "   ✗ PostgreSQL is not running or not accessible"
fi
echo

# Elasticsearch
echo "4. Testing Elasticsearch:"
if curl -s http://localhost:9200/_cluster/health > /dev/null 2>&1; then
    echo "   ✓ Elasticsearch is running"
    curl -s http://localhost:9200 | grep version
else
    echo "   ✗ Elasticsearch is not running"
fi
echo

echo "=== Verification Complete ==="
