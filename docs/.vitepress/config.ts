import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'

const base =
  process.env.DOCS_BASE ??
  (process.env.GITHUB_REPOSITORY ? `/${process.env.GITHUB_REPOSITORY.split('/')[1]}/` : '/')

// 共享的主题配置（logo / 仓库入口 / 社交链接对所有语言一致）
const sharedThemeConfig = {
  logo: '/logo.svg',
  repo: 'cuihairu/redis-streaming',
  socialLinks: [
    { icon: 'github', link: 'https://github.com/cuihairu/redis-streaming' },
  ],
}

export default withMermaid(
  defineConfig({
    // 顶层 lang 作为 fallback；各 locale 在下方 locales.*.lang 中显式声明
    lang: 'zh-CN',
    title: 'Redis Streaming',
    description: '基于 Redis 的流处理框架',
    base,

    // 忽略死链接检查（链接大小写敏感问题）
    ignoreDeadLinks: true,

    // mermaid 11 用到的解构语法无法降级到 VitePress 默认的旧浏览器目标，
    // 显式抬到 es2022（mermaid 11 本身要求现代浏览器）
    vite: {
      build: {
        target: 'es2022',
      },
    },

    // 每个 locale 各自声明 label/lang/title/description 与独立的 nav/sidebar。
    // label 是默认主题右上角语言切换器（VPNavBarTranslations）的数据源，
    // 两个 label 都声明后切换器才会出现，并自动把当前页映射到另一语言的同路径。
    locales: {
      root: {
        label: '中文',
        lang: 'zh-CN',
        title: 'Redis Streaming',
        description: '基于 Redis 的流处理框架',
        themeConfig: {
          ...sharedThemeConfig,
          langMenuLabel: '切换语言',

          nav: [
            { text: '首页', link: '/' },
            { text: '快速开始', link: '/Quick-Start' },
            {
              text: '模块文档',
              items: [
                { text: '核心 API', link: '/Core' },
                { text: 'Runtime', link: '/runtime' },
                { text: 'Config', link: '/config' },
                { text: 'State', link: '/state' },
                { text: 'Checkpoint', link: '/checkpoint' },
                { text: 'Watermark', link: '/watermark' },
                { text: 'Window', link: '/window' },
                { text: 'Source & Sink', link: '/source-sink' },
                { text: 'Reliability', link: '/reliability' },
                { text: 'Registry', link: '/Registry' },
                { text: 'MQ', link: '/MQ' },
              ]
            },
            {
              text: '设计文档',
              items: [
                { text: '架构设计', link: '/Architecture' },
                { text: 'Exactly-Once', link: '/exactly-once' },
                { text: 'MQ 设计', link: '/MQ-Design' },
                { text: 'Registry 设计', link: '/Registry-Design' },
                { text: 'Join/CEP 算子化', link: '/Join-CEP-Operators-Design' },
                { text: '控制面设计', link: '/Control-Plane-Design' },
                { text: '表变更流设计', link: '/Table-Changelog-Design' },
                { text: '聚合体系统一', link: '/Aggregation-Unification-Design' },
                { text: '指标体系统一', link: '/Metrics-Unification-Design' },
                { text: '状态后端统一', link: '/State-Unification-Design' },
                { text: '多租户隔离', link: '/Multi-Tenancy-Design' },
                { text: '安全加固', link: '/Security-Hardening-Design' },
                { text: '重试/死信/去重划界', link: '/Dedup-Retry-DLQ-Boundary-Design' },
              ]
            },
            {
              text: '运维指南',
              items: [
                { text: '部署指南', link: '/Deployment' },
                { text: '性能调优', link: '/Performance' },
                { text: '故障排查', link: '/troubleshooting' },
              ]
            },
          ],

          sidebar: {
            '/': [
              {
                text: '快速开始',
                collapsed: false,
                items: [
                  { text: '5分钟上手', link: '/Quick-Start' },
                  { text: 'Spring Boot 集成', link: '/spring-boot-starter-guide' },
                ]
              },
              {
                text: '核心概念',
                collapsed: false,
                items: [
                  { text: '架构概述', link: '/Architecture' },
                  { text: '核心 API', link: '/Core' },
                  { text: '运行时环境', link: '/runtime' },
                ]
              },
              {
                text: '基础设施模块',
                collapsed: false,
                items: [
                  { text: 'Config 配置中心', link: '/config' },
                  { text: 'Registry 服务注册', link: '/Registry' },
                  { text: 'MQ 消息队列', link: '/MQ' },
                ]
              },
              {
                text: '流处理核心',
                collapsed: false,
                items: [
                  { text: 'State 状态管理', link: '/state' },
                  { text: 'Checkpoint 检查点', link: '/checkpoint' },
                  { text: 'Watermark 水位线', link: '/watermark' },
                  { text: 'Window 窗口', link: '/window' },
                ]
              },
              {
                text: '数据集成',
                collapsed: false,
                items: [
                  { text: 'Source & Sink', link: '/source-sink' },
                  { text: 'CDC 变更捕获', link: '/CDC' },
                  { text: 'Aggregation 聚合', link: '/Aggregation' },
                  { text: 'Table 表操作', link: '/Table' },
                  { text: 'Join 流连接', link: '/Join' },
                ]
              },
              {
                text: '可靠性',
                collapsed: false,
                items: [
                  { text: 'Reliability 组件', link: '/reliability' },
                  { text: 'Metrics 监控', link: '/Metrics' },
                ]
              },
              {
                text: '设计文档',
                collapsed: false,
                items: [
                  { text: 'Exactly-Once 语义', link: '/exactly-once' },
                  { text: 'MQ 设计', link: '/MQ-Design' },
                  { text: 'Registry 设计', link: '/Registry-Design' },
                  { text: 'Join/CEP 算子化', link: '/Join-CEP-Operators-Design' },
                  { text: '控制面设计', link: '/Control-Plane-Design' },
                  { text: '表变更流设计', link: '/Table-Changelog-Design' },
                  { text: '聚合体系统一', link: '/Aggregation-Unification-Design' },
                  { text: '指标体系统一', link: '/Metrics-Unification-Design' },
                  { text: '状态后端统一', link: '/State-Unification-Design' },
                  { text: '多租户隔离', link: '/Multi-Tenancy-Design' },
                  { text: '安全加固', link: '/Security-Hardening-Design' },
                  { text: '重试/死信/去重划界', link: '/Dedup-Retry-DLQ-Boundary-Design' },
                ]
              },
              {
                text: '运维',
                collapsed: false,
                items: [
                  { text: '部署指南', link: '/Deployment' },
                  { text: '性能调优', link: '/Performance' },
                  { text: '故障排查', link: '/troubleshooting' },
                  { text: 'CI/CD', link: '/GitHub-Actions' },
                ]
              },
              {
                text: '开发',
                collapsed: false,
                items: [
                  { text: '开发指南', link: '/Developer-Guide' },
                  { text: '测试指南', link: '/Testing' },
                  { text: '发布流程', link: '/maven-publish' },
                ]
              },
            ],
          }
        }
      },

      en: {
        label: 'English',
        lang: 'en-US',
        title: 'Redis Streaming',
        description: 'Redis-based streaming processing framework',
        themeConfig: {
          ...sharedThemeConfig,

          nav: [
            { text: 'Home', link: '/en/' },
            { text: 'Quick Start', link: '/en/Quick-Start' },
            {
              text: 'Modules',
              items: [
                { text: 'API Overview', link: '/en/API' },
                { text: 'Spring Boot Starter', link: '/en/Spring-Boot-Starter' },
                { text: 'Examples', link: '/en/Examples' },
              ]
            },
            {
              text: 'Design',
              items: [
                { text: 'Architecture', link: '/en/Architecture' },
                { text: 'MQ Design', link: '/en/MQ-Design' },
                { text: 'MQ Broker Interaction', link: '/en/MQ-Broker-Interaction' },
                { text: 'MQ Guide', link: '/en/MQ-Guide' },
                { text: 'Registry Design', link: '/en/Registry-Design' },
                { text: 'Registry Guide', link: '/en/Registry-Guide' },
              ]
            },
            {
              text: 'Operations',
              items: [
                { text: 'Deployment', link: '/en/Deployment' },
                { text: 'Performance', link: '/en/Performance' },
                { text: 'Troubleshooting', link: '/en/Troubleshooting' },
              ]
            },
          ],

          sidebar: {
            '/en/': [
              {
                text: 'Getting Started',
                collapsed: false,
                items: [
                  { text: 'Quick Start', link: '/en/Quick-Start' },
                  { text: 'Spring Boot Starter', link: '/en/Spring-Boot-Starter' },
                  { text: 'Examples', link: '/en/Examples' },
                ]
              },
              {
                text: 'Core Concepts',
                collapsed: false,
                items: [
                  { text: 'Architecture', link: '/en/Architecture' },
                  { text: 'API Overview', link: '/en/API' },
                ]
              },
              {
                text: 'Infrastructure Modules',
                collapsed: false,
                items: [
                  { text: 'MQ Guide', link: '/en/MQ-Guide' },
                  { text: 'MQ Design', link: '/en/MQ-Design' },
                  { text: 'MQ Broker Interaction', link: '/en/MQ-Broker-Interaction' },
                  { text: 'Registry Guide', link: '/en/Registry-Guide' },
                  { text: 'Registry Design', link: '/en/Registry-Design' },
                ]
              },
              {
                text: 'Operations',
                collapsed: false,
                items: [
                  { text: 'Deployment', link: '/en/Deployment' },
                  { text: 'Performance', link: '/en/Performance' },
                  { text: 'Troubleshooting', link: '/en/Troubleshooting' },
                  { text: 'CI/CD', link: '/en/GitHub-Actions' },
                ]
              },
              {
                text: 'Development',
                collapsed: false,
                items: [
                  { text: 'Developer Guide', link: '/en/Developer-Guide' },
                  { text: 'Testing', link: '/en/Testing' },
                  { text: 'Publishing', link: '/en/Publishing' },
                ]
              },
            ],
          }
        }
      },
    },
  }
  )
)
