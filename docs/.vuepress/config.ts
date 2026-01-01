import { viteBundler } from '@vuepress/bundler-vite'
import { defaultTheme } from '@vuepress/theme-default'
import { defineUserConfig } from 'vuepress'

const base =
  process.env.DOCS_BASE ??
  (process.env.GITHUB_REPOSITORY ? `/${process.env.GITHUB_REPOSITORY.split('/')[1]}/` : '/')

export default defineUserConfig({
  lang: 'zh-CN',
  title: 'Redis Streaming',
  description: '基于 Redis 的流处理框架',
  base,
  bundler: viteBundler(),
  theme: defaultTheme({
    repo: 'cuihairu/redis-streaming',
    docsDir: 'docs',
    navbar: [
      { text: '首页', link: '/' },
      { text: '快速开始', link: '/wiki/Quick-Start' },
      { text: '模块文档',
        children: [
          { text: '核心 API', link: '/wiki/Core' },
          { text: 'Runtime', link: '/runtime' },
          { text: 'Config', link: '/config' },
          { text: 'State', link: '/state' },
          { text: 'Checkpoint', link: '/checkpoint' },
          { text: 'Watermark', link: '/watermark' },
          { text: 'Window', link: '/window' },
          { text: 'Source & Sink', link: '/source-sink' },
          { text: 'Reliability', link: '/reliability' },
          { text: 'Registry', link: '/wiki/Registry' },
          { text: 'MQ', link: '/wiki/MQ' },
        ]
      },
      { text: '设计文档',
        children: [
          { text: '架构设计', link: '/wiki/Architecture' },
          { text: 'Exactly-Once', link: '/exactly-once' },
          { text: 'MQ 设计', link: '/wiki/MQ-Design' },
          { text: 'Registry 设计', link: '/wiki/Registry-Design' },
        ]
      },
      { text: '运维指南',
        children: [
          { text: '部署指南', link: '/wiki/Deployment' },
          { text: '性能调优', link: '/wiki/Performance' },
          { text: '故障排查', link: '/wiki/Troubleshooting' },
        ]
      },
    ],
    sidebar: {
      '/': [
        {
          text: '快速开始',
          children: [
            { text: '5分钟上手', link: '/wiki/Quick-Start' },
            { text: 'Spring Boot 集成', link: '/spring-boot-starter-guide' },
          ]
        },
        {
          text: '核心概念',
          children: [
            { text: '架构概述', link: '/wiki/Architecture' },
            { text: '核心 API', link: '/wiki/Core' },
            { text: '运行时环境', link: '/runtime' },
          ]
        },
        {
          text: '基础设施模块',
          children: [
            { text: 'Config 配置中心', link: '/config' },
            { text: 'Registry 服务注册', link: '/wiki/Registry' },
            { text: 'MQ 消息队列', link: '/wiki/MQ' },
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
            { text: 'CDC 变更捕获', link: '/wiki/CDC' },
            { text: 'Aggregation 聚合', link: '/wiki/Aggregation' },
            { text: 'Table 表操作', link: '/wiki/Table' },
            { text: 'Join 流连接', link: '/wiki/Join' },
          ]
        },
        {
          text: '可靠性',
          children: [
            { text: 'Reliability 组件', link: '/reliability' },
            { text: 'Metrics 监控', link: '/wiki/Metrics' },
          ]
        },
        {
          text: '设计文档',
          children: [
            { text: 'Exactly-Once 语义', link: '/exactly-once' },
            { text: 'MQ 设计', link: '/wiki/MQ-Design' },
            { text: 'Registry 设计', link: '/wiki/Registry-Design' },
          ]
        },
        {
          text: '运维',
          children: [
            { text: '部署指南', link: '/wiki/Deployment' },
            { text: '性能调优', link: '/wiki/Performance' },
            { text: '故障排查', link: '/wiki/Troubleshooting' },
            { text: 'CI/CD', link: '/wiki/GitHub-Actions' },
          ]
        },
        {
          text: '开发',
          children: [
            { text: '开发指南', link: '/wiki/Developer-Guide' },
            { text: '测试指南', link: '/wiki/Testing' },
            { text: '发布流程', link: '/maven-publish' },
          ]
        },
      ],
    }
  })
})
