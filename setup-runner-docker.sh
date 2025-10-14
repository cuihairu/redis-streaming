#!/bin/bash

# GitHub Actions Runner Docker 配置脚本
# 在 Linux runner 上运行此脚本来配置 Docker 权限

set -e

echo "🔧 Configuring Docker permissions for GitHub Actions Runner..."

# 获取当前用户
USER=$(whoami)
echo "Current user: $USER"

# 检查 Docker 是否安装
if ! command -v docker &> /dev/null; then
    echo "❌ Docker is not installed. Please install Docker first."
    exit 1
fi

# 检查 Docker 服务是否运行
if ! systemctl is-active --quiet docker; then
    echo "🚀 Starting Docker service..."
    sudo systemctl start docker
    sudo systemctl enable docker
fi

# 检查用户是否已在 docker 组中
if groups $USER | grep -q docker; then
    echo "✅ User $USER is already in docker group"
else
    echo "📝 Adding user $USER to docker group..."
    sudo usermod -aG docker $USER
    echo "✅ User added to docker group"
fi

# 检查是否需要重新登录
if ! docker ps >/dev/null 2>&1; then
    echo "⚠️  Docker permissions not yet effective. This can happen because:"
    echo "   1. You need to log out and log back in"
    echo "   2. Or restart the GitHub Actions runner service"
    echo ""
    echo "🔄 To restart GitHub Actions runner service:"
    echo "   sudo systemctl restart actions.runner.*"
    echo ""
    echo "🔄 Or to restart all runner services:"
    echo "   sudo systemctl list-units --type=service | grep actions.runner | awk '{print \$1}' | xargs sudo systemctl restart"
    echo ""
    echo "⚡ After restart, verify with: docker ps"
else
    echo "✅ Docker permissions are working correctly!"
fi

echo ""
echo "🎉 Configuration complete!"
echo ""
echo "Next steps:"
echo "1. If runner service needs restart, run: sudo systemctl restart actions.runner.*"
echo "2. Verify Docker access: docker ps"
echo "3. Push code to trigger GitHub Actions"