# CI/CD

本项目的 CI/CD 配置使用 GitHub Actions。

## 工作流

项目包含以下 GitHub Actions 工作流：

- **CI**: 持续集成，运行测试和构建
- **CD**: 持续部署，自动发布到 Maven Central
- **Documentation**: 文档站点自动构建和部署

## 配置文件

工作流配置位于项目根目录的 `.github/workflows/` 目录下。
