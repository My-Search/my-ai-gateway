import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import AutoImport from 'unplugin-auto-import/vite'
import { createSvgIconsPlugin } from 'vite-plugin-svg-icons'
import { resolve } from 'path'

export default defineConfig({
  plugins: [
    vue(),
    Components({
      dts: 'src/components.d.ts',
      resolvers: []
    }),
    AutoImport({
      imports: ['vue', 'vue-router', 'pinia'],
      dts: 'src/auto-imports.d.ts'
    }),
    // SVG 图标插件：将 src/assets/icons 下的 SVG 文件注册为符号表
    createSvgIconsPlugin({
      iconDirs: [resolve(__dirname, 'src/assets/icons')],
      symbolId: 'icon-[dir]-[name]',
      inject: 'body-first',
      customDomId: '__svg__icons__dom__'
    })
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    port: 3990,
    proxy: {
      '/admin/api': {
        target: 'http://localhost:1399',
        changeOrigin: true
      },
      '/api/share': {
        target: 'http://localhost:1399',
        changeOrigin: true
      },
      // 对外 AI 接口（OpenAI / Anthropic 兼容），本地开发时直接通过前端代理调用
      '/v1': {
        target: 'http://localhost:1399',
        changeOrigin: true
      }
    }
  }
})
