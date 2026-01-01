import { viteBundler } from '@vuepress/bundler-vite'
import { defaultTheme } from '@vuepress/theme-default'
import { defineUserConfig } from 'vuepress'
import { mdEnhancePlugin } from 'vuepress-plugin-md-enhance'

const base =
  process.env.DOCS_BASE ??
  (process.env.GITHUB_REPOSITORY ? `/${process.env.GITHUB_REPOSITORY.split('/')[1]}/` : '/')

export default defineUserConfig({
  lang: 'zh-CN',
  title: 'Redis Streaming',
  description: '基于 Redis 的流处理框架',
  base,
  bundler: viteBundler(),
  plugins: [
    mdEnhancePlugin({
      // 启用 Mermaid 图表支持
      mermaid: true,
    }),
  ],
  theme: defaultTheme({
    repo: 'cuihairu/redis-streaming',
    navbar: [
      { text: '首页', link: '/' },
      { text: '快速开始', link: '/Quick-Start' },
      { text: '模块文档',
        children: [
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
      { text: '设计文档',
        children: [
          { text: '架构设计', link: '/Architecture' },
          { text: 'Exactly-Once', link: '/exactly-once' },
          { text: 'MQ 设计', link: '/MQ-Design' },
          { text: 'Registry 设计', link: '/Registry-Design' },
        ]
      },
      { text: '运维指南',
        children: [
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
          children: [
            { text: '5分钟上手', link: '/Quick-Start' },
            { text: 'Spring Boot 集成', link: '/spring-boot-starter-guide' },
          ]
        },
        {
          text: '核心概念',
          children: [
            { text: '架构概述', link: '/Architecture' },
            { text: '核心 API', link: '/Core' },
            { text: '运行时环境', link: '/runtime' },
          ]
        },
        {
          text: '基础设施模块',
          children: [
            { text: 'Config 配置中心', link: '/config' },
            { text: 'Registry 服务注册', link: '/Registry' },
            { text: 'MQ 消息队列', link: '/MQ' },
          ]
        },
        {
          text: '流处理核心',
          children: [
            { text: 'State 状态管理', link: '/state' },
            { text: 'Checkpoint 检查点', link: '/checkpoint' },
            { text: 'Watermark 水位线', link: '/watermark' },
            { text: 'Window 窗口', link: '/window' },
          ]
        },
        {
          text: '数据集成',
          children: [
            { text: 'Source & Sink', link: '/source-sink' },
            { text: 'CDC 变更捕获', link: '/CDC' },
            { text: 'Aggregation 聚合', link: '/Aggregation' },
            { text: 'Table 表操作', link: '/Table' },
            { text: 'Join 流连接', link: '/Join' },
          ]
        },
        {
          text: '可靠性',
          children: [
            { text: 'Reliability 组件', link: '/reliability' },
            { text: 'Metrics 监控', link: '/Metrics' },
          ]
        },
        {
          text: '设计文档',
          children: [
            { text: 'Exactly-Once 语义', link: '/exactly-once' },
            { text: 'MQ 设计', link: '/MQ-Design' },
            { text: 'Registry 设计', link: '/Registry-Design' },
          ]
        },
        {
          text: '运维',
          children: [
            { text: '部署指南', link: '/Deployment' },
            { text: '性能调优', link: '/Performance' },
            { text: '故障排查', link: '/troubleshooting' },
            { text: 'CI/CD', link: '/GitHub-Actions' },
          ]
        },
        {
          text: '开发',
          children: [
            { text: '开发指南', link: '/Developer-Guide' },
            { text: '测试指南', link: '/Testing' },
            { text: '发布流程', link: '/maven-publish' },
          ]
        },
      ],
    }
  })
})
