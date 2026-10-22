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
    head: [['link', { rel: 'icon', type: 'image/svg+xml', href: `${base}logo.svg` }]],
    // 顶层 lang 作为 fallback；各 locale 在下方 locales.*.lang 中显式声明
    lang: 'en-US',
    title: 'Redis Streaming',
    description: 'Redis-based streaming processing framework',
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

    // 官方 locales 结构：root = English（docs/*.md），zh = 简体中文（docs/zh/*.md）。
    // label 是默认主题右上角语言切换器（VPNavBarTranslations）的数据源，
    // 两个 label 都声明后切换器才会出现，并自动把当前页映射到另一语言的同路径。
    locales: {
      root: {
        label: 'English',
        lang: 'en-US',
        title: 'Redis Streaming',
        description: 'Redis-based streaming processing framework',
        themeConfig: {
          ...sharedThemeConfig,
          langMenuLabel: 'Switch language',

          nav: [
            { text: 'Home', link: '/' },
            { text: 'Quick Start', link: '/Quick-Start' },
            {
              text: 'Modules',
              items: [
                { text: 'API Overview', link: '/API' },
                { text: 'Spring Boot Starter', link: '/Spring-Boot-Starter' },
                { text: 'Examples', link: '/Examples' },
              ]
            },
            {
              text: 'Design',
              items: [
                { text: 'Architecture', link: '/Architecture' },
                { text: 'MQ Design', link: '/MQ-Design' },
                { text: 'MQ Broker Interaction', link: '/MQ-Broker-Interaction' },
                { text: 'MQ Guide', link: '/MQ-Guide' },
                { text: 'Registry Design', link: '/Registry-Design' },
                { text: 'Registry Guide', link: '/Registry-Guide' },
              ]
            },
            {
              text: 'Operations',
              items: [
                { text: 'Deployment', link: '/Deployment' },
                { text: 'Performance', link: '/Performance' },
                { text: 'Troubleshooting', link: '/Troubleshooting' },
              ]
            },
          ],

          sidebar: {
            '/': [
              {
                text: 'Getting Started',
                collapsed: false,
                items: [
                  { text: 'Quick Start', link: '/Quick-Start' },
                  { text: 'Spring Boot Starter', link: '/Spring-Boot-Starter' },
                  { text: 'Examples', link: '/Examples' },
                ]
              },
              {
                text: 'Core Concepts',
                collapsed: false,
                items: [
                  { text: 'Architecture', link: '/Architecture' },
                  { text: 'API Overview', link: '/API' },
                ]
              },
              {
                text: 'Infrastructure Modules',
                collapsed: false,
                items: [
                  { text: 'MQ Guide', link: '/MQ-Guide' },
                  { text: 'MQ Design', link: '/MQ-Design' },
                  { text: 'MQ Broker Interaction', link: '/MQ-Broker-Interaction' },
                  { text: 'Registry Guide', link: '/Registry-Guide' },
                  { text: 'Registry Design', link: '/Registry-Design' },
                ]
              },
              {
                text: 'Operations',
                collapsed: false,
                items: [
                  { text: 'Deployment', link: '/Deployment' },
                  { text: 'Performance', link: '/Performance' },
                  { text: 'Troubleshooting', link: '/Troubleshooting' },
                  { text: 'CI/CD', link: '/GitHub-Actions' },
                ]
              },
              {
                text: 'Development',
                collapsed: false,
                items: [
                  { text: 'Developer Guide', link: '/Developer-Guide' },
                  { text: 'Testing', link: '/Testing' },
                  { text: 'Publishing', link: '/Publishing' },
                ]
              },
            ],
          }
        }
      },

      zh: {
        label: '简体中文',
        lang: 'zh-CN',
        link: '/zh/',
        title: 'Redis Streaming',
        description: '基于 Redis 的流处理框架',
        themeConfig: {
          ...sharedThemeConfig,
          langMenuLabel: '切换语言',

          nav: [
            { text: '首页', link: '/zh/' },
            { text: '快速开始', link: '/zh/Quick-Start' },
            {
              text: '模块文档',
              items: [
                { text: '核心 API', link: '/zh/Core' },
                { text: 'Runtime', link: '/zh/runtime' },
                { text: 'Config', link: '/zh/config' },
                { text: 'State', link: '/zh/state' },
                { text: 'Checkpoint', link: '/zh/checkpoint' },
                { text: 'Watermark', link: '/zh/watermark' },
                { text: 'Window', link: '/zh/window' },
                { text: 'Source & Sink', link: '/zh/source-sink' },
                { text: 'Reliability', link: '/zh/reliability' },
                { text: 'Registry', link: '/zh/Registry' },
                { text: 'MQ', link: '/zh/MQ' },
              ]
            },
            {
              text: '设计文档',
              items: [
                { text: '架构设计', link: '/zh/Architecture' },
                { text: 'Exactly-Once', link: '/zh/exactly-once' },
                { text: 'MQ 设计', link: '/zh/MQ-Design' },
                { text: 'Registry 设计', link: '/zh/Registry-Design' },
                { text: 'Join/CEP 算子化', link: '/zh/Join-CEP-Operators-Design' },
                { text: '控制面设计', link: '/zh/Control-Plane-Design' },
                { text: '表变更流设计', link: '/zh/Table-Changelog-Design' },
                { text: '聚合体系统一', link: '/zh/Aggregation-Unification-Design' },
                { text: '指标体系统一', link: '/zh/Metrics-Unification-Design' },
                { text: '状态后端统一', link: '/zh/State-Unification-Design' },
                { text: '多租户隔离', link: '/zh/Multi-Tenancy-Design' },
                { text: '安全加固', link: '/zh/Security-Hardening-Design' },
                { text: '重试/死信/去重划界', link: '/zh/Dedup-Retry-DLQ-Boundary-Design' },
              ]
            },
            {
              text: '运维指南',
              items: [
                { text: '部署指南', link: '/zh/Deployment' },
                { text: '性能调优', link: '/zh/Performance' },
                { text: '故障排查', link: '/zh/Troubleshooting' },
              ]
            },
          ],

          sidebar: {
            '/zh/': [
              {
                text: '快速开始',
                collapsed: false,
                items: [
                  { text: '5分钟上手', link: '/zh/Quick-Start' },
                  { text: 'Spring Boot 集成', link: '/zh/spring-boot-starter-guide' },
                ]
              },
              {
                text: '核心概念',
                collapsed: false,
                items: [
                  { text: '架构概述', link: '/zh/Architecture' },
                  { text: '核心 API', link: '/zh/Core' },
                  { text: '运行时环境', link: '/zh/runtime' },
                ]
              },
              {
                text: '基础设施模块',
                collapsed: false,
                items: [
                  { text: 'Config 配置中心', link: '/zh/config' },
                  { text: 'Registry 服务注册', link: '/zh/Registry' },
                  { text: 'MQ 消息队列', link: '/zh/MQ' },
                ]
              },
              {
                text: '流处理核心',
                collapsed: false,
                items: [
                  { text: 'State 状态管理', link: '/zh/state' },
                  { text: 'Checkpoint 检查点', link: '/zh/checkpoint' },
                  { text: 'Watermark 水位线', link: '/zh/watermark' },
                  { text: 'Window 窗口', link: '/zh/window' },
                ]
              },
              {
                text: '数据集成',
                collapsed: false,
                items: [
                  { text: 'Source & Sink', link: '/zh/source-sink' },
                  { text: 'CDC 变更捕获', link: '/zh/CDC' },
                  { text: 'Aggregation 聚合', link: '/zh/Aggregation' },
                  { text: 'Table 表操作', link: '/zh/Table' },
                  { text: 'Join 流连接', link: '/zh/Join' },
                ]
              },
              {
                text: '可靠性',
                collapsed: false,
                items: [
                  { text: 'Reliability 组件', link: '/zh/reliability' },
                  { text: 'Metrics 监控', link: '/zh/Metrics' },
                ]
              },
              {
                text: '设计文档',
                collapsed: false,
                items: [
                  { text: 'Exactly-Once 语义', link: '/zh/exactly-once' },
                  { text: 'MQ 设计', link: '/zh/MQ-Design' },
                  { text: 'Registry 设计', link: '/zh/Registry-Design' },
                  { text: 'Join/CEP 算子化', link: '/zh/Join-CEP-Operators-Design' },
                  { text: '控制面设计', link: '/zh/Control-Plane-Design' },
                  { text: '表变更流设计', link: '/zh/Table-Changelog-Design' },
                  { text: '聚合体系统一', link: '/zh/Aggregation-Unification-Design' },
                  { text: '指标体系统一', link: '/zh/Metrics-Unification-Design' },
                  { text: '状态后端统一', link: '/zh/State-Unification-Design' },
                  { text: '多租户隔离', link: '/zh/Multi-Tenancy-Design' },
                  { text: '安全加固', link: '/zh/Security-Hardening-Design' },
                  { text: '重试/死信/去重划界', link: '/zh/Dedup-Retry-DLQ-Boundary-Design' },
                ]
              },
              {
                text: '运维',
                collapsed: false,
                items: [
                  { text: '部署指南', link: '/zh/Deployment' },
                  { text: '性能调优', link: '/zh/Performance' },
                  { text: '故障排查', link: '/zh/Troubleshooting' },
                  { text: 'CI/CD', link: '/zh/GitHub-Actions' },
                ]
              },
              {
                text: '开发',
                collapsed: false,
                items: [
                  { text: '开发指南', link: '/zh/Developer-Guide' },
                  { text: '测试指南', link: '/zh/Testing' },
                  { text: '发布流程', link: '/zh/maven-publish' },
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
