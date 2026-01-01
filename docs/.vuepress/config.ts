import { viteBundler } from '@vuepress/bundler-vite'
import { defaultTheme } from '@vuepress/theme-default'
import { defineUserConfig } from 'vuepress'

const base =
  process.env.DOCS_BASE ??
  (process.env.GITHUB_REPOSITORY ? `/${process.env.GITHUB_REPOSITORY.split('/')[1]}/` : '/')

export default defineUserConfig({
  lang: 'zh-CN',
  title: 'redis-streaming',
  description: 'Redis Streaming 文档站',
  base,
  bundler: viteBundler(),
  theme: defaultTheme({
    repo: 'cuihairu/redis-streaming',
    docsDir: 'docs',
    navbar: [
      { text: '首页', link: '/' },
      { text: 'Wiki 文档', link: '/wiki/Home.html' },
      { text: 'Exactly-once', link: '/exactly-once.html' }
    ],
    sidebar: 'auto'
  })
})
