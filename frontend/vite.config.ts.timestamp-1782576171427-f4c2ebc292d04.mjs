// vite.config.ts
import { defineConfig } from "file:///D:/code/my-ai-api/frontend/node_modules/vite/dist/node/index.js";
import vue from "file:///D:/code/my-ai-api/frontend/node_modules/@vitejs/plugin-vue/dist/index.mjs";
import Components from "file:///D:/code/my-ai-api/frontend/node_modules/unplugin-vue-components/dist/vite.js";
import AutoImport from "file:///D:/code/my-ai-api/frontend/node_modules/unplugin-auto-import/dist/vite.js";
import { createSvgIconsPlugin } from "file:///D:/code/my-ai-api/frontend/node_modules/vite-plugin-svg-icons/dist/index.mjs";
import { resolve } from "path";
var __vite_injected_original_dirname = "D:\\code\\my-ai-api\\frontend";
var vite_config_default = defineConfig({
  plugins: [
    vue(),
    Components({
      dts: "src/components.d.ts",
      resolvers: []
    }),
    AutoImport({
      imports: ["vue", "vue-router", "pinia"],
      dts: "src/auto-imports.d.ts"
    }),
    // SVG 图标插件：将 src/assets/icons 下的 SVG 文件注册为符号表
    createSvgIconsPlugin({
      iconDirs: [resolve(__vite_injected_original_dirname, "src/assets/icons")],
      symbolId: "icon-[dir]-[name]",
      inject: "body-first",
      customDomId: "__svg__icons__dom__"
    })
  ],
  resolve: {
    alias: {
      "@": resolve(__vite_injected_original_dirname, "src")
    }
  },
  server: {
    port: 3990,
    proxy: {
      "/uploads/": {
        target: "http://localhost:1399",
        changeOrigin: true
      },
      "/admin/api/": {
        target: "http://localhost:1399",
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on("proxyRes", (proxyRes) => {
            if (proxyRes.headers["content-type"]?.toString().includes("text/event-stream")) {
              proxyRes.headers["x-accel-buffering"] = "no";
              proxyRes.headers["cache-control"] = "no-cache";
            }
          });
        }
      },
      "/api/share/": {
        target: "http://localhost:1399",
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on("proxyRes", (proxyRes) => {
            if (proxyRes.headers["content-type"]?.toString().includes("text/event-stream")) {
              proxyRes.headers["x-accel-buffering"] = "no";
              proxyRes.headers["cache-control"] = "no-cache";
            }
          });
        }
      },
      // 对外 AI 接口（OpenAI / Anthropic 兼容），本地开发时直接通过前端代理调用
      "/v1/": {
        target: "http://localhost:1399",
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on("proxyRes", (proxyRes) => {
            if (proxyRes.headers["content-type"]?.toString().includes("text/event-stream")) {
              proxyRes.headers["x-accel-buffering"] = "no";
              proxyRes.headers["cache-control"] = "no-cache";
            }
          });
        }
      }
    }
  }
});
export {
  vite_config_default as default
};
//# sourceMappingURL=data:application/json;base64,ewogICJ2ZXJzaW9uIjogMywKICAic291cmNlcyI6IFsidml0ZS5jb25maWcudHMiXSwKICAic291cmNlc0NvbnRlbnQiOiBbImNvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9kaXJuYW1lID0gXCJEOlxcXFxjb2RlXFxcXG15LWFpLWFwaVxcXFxmcm9udGVuZFwiO2NvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9maWxlbmFtZSA9IFwiRDpcXFxcY29kZVxcXFxteS1haS1hcGlcXFxcZnJvbnRlbmRcXFxcdml0ZS5jb25maWcudHNcIjtjb25zdCBfX3ZpdGVfaW5qZWN0ZWRfb3JpZ2luYWxfaW1wb3J0X21ldGFfdXJsID0gXCJmaWxlOi8vL0Q6L2NvZGUvbXktYWktYXBpL2Zyb250ZW5kL3ZpdGUuY29uZmlnLnRzXCI7aW1wb3J0IHsgZGVmaW5lQ29uZmlnIH0gZnJvbSAndml0ZSdcbmltcG9ydCB2dWUgZnJvbSAnQHZpdGVqcy9wbHVnaW4tdnVlJ1xuaW1wb3J0IENvbXBvbmVudHMgZnJvbSAndW5wbHVnaW4tdnVlLWNvbXBvbmVudHMvdml0ZSdcbmltcG9ydCBBdXRvSW1wb3J0IGZyb20gJ3VucGx1Z2luLWF1dG8taW1wb3J0L3ZpdGUnXG5pbXBvcnQgeyBjcmVhdGVTdmdJY29uc1BsdWdpbiB9IGZyb20gJ3ZpdGUtcGx1Z2luLXN2Zy1pY29ucydcbmltcG9ydCB7IHJlc29sdmUgfSBmcm9tICdwYXRoJ1xuXG5leHBvcnQgZGVmYXVsdCBkZWZpbmVDb25maWcoe1xuICBwbHVnaW5zOiBbXG4gICAgdnVlKCksXG4gICAgQ29tcG9uZW50cyh7XG4gICAgICBkdHM6ICdzcmMvY29tcG9uZW50cy5kLnRzJyxcbiAgICAgIHJlc29sdmVyczogW11cbiAgICB9KSxcbiAgICBBdXRvSW1wb3J0KHtcbiAgICAgIGltcG9ydHM6IFsndnVlJywgJ3Z1ZS1yb3V0ZXInLCAncGluaWEnXSxcbiAgICAgIGR0czogJ3NyYy9hdXRvLWltcG9ydHMuZC50cydcbiAgICB9KSxcbiAgICAvLyBTVkcgXHU1NkZFXHU2ODA3XHU2M0QyXHU0RUY2XHVGRjFBXHU1QzA2IHNyYy9hc3NldHMvaWNvbnMgXHU0RTBCXHU3Njg0IFNWRyBcdTY1ODdcdTRFRjZcdTZDRThcdTUxOENcdTRFM0FcdTdCMjZcdTUzRjdcdTg4NjhcbiAgICBjcmVhdGVTdmdJY29uc1BsdWdpbih7XG4gICAgICBpY29uRGlyczogW3Jlc29sdmUoX19kaXJuYW1lLCAnc3JjL2Fzc2V0cy9pY29ucycpXSxcbiAgICAgIHN5bWJvbElkOiAnaWNvbi1bZGlyXS1bbmFtZV0nLFxuICAgICAgaW5qZWN0OiAnYm9keS1maXJzdCcsXG4gICAgICBjdXN0b21Eb21JZDogJ19fc3ZnX19pY29uc19fZG9tX18nXG4gICAgfSlcbiAgXSxcbiAgcmVzb2x2ZToge1xuICAgIGFsaWFzOiB7XG4gICAgICAnQCc6IHJlc29sdmUoX19kaXJuYW1lLCAnc3JjJylcbiAgICB9XG4gIH0sXG4gIHNlcnZlcjoge1xuICAgIHBvcnQ6IDM5OTAsXG4gICAgcHJveHk6IHtcbiAgICAgICcvdXBsb2Fkcy8nOiB7XG4gICAgICAgIHRhcmdldDogJ2h0dHA6Ly9sb2NhbGhvc3Q6MTM5OScsXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcbiAgICAgIH0sXG4gICAgICAnL2FkbWluL2FwaS8nOiB7XG4gICAgICAgIHRhcmdldDogJ2h0dHA6Ly9sb2NhbGhvc3Q6MTM5OScsXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcbiAgICAgICAgY29uZmlndXJlOiAocHJveHkpID0+IHtcbiAgICAgICAgICBwcm94eS5vbigncHJveHlSZXMnLCAocHJveHlSZXMpID0+IHtcbiAgICAgICAgICAgIC8vIFNTRSBcdTU0Q0RcdTVFOTRcdTc5ODFcdTc1MjhcdTRFRkJcdTRGNTVcdTdGMTNcdTUxQjJcbiAgICAgICAgICAgIGlmIChwcm94eVJlcy5oZWFkZXJzWydjb250ZW50LXR5cGUnXT8udG9TdHJpbmcoKS5pbmNsdWRlcygndGV4dC9ldmVudC1zdHJlYW0nKSkge1xuICAgICAgICAgICAgICBwcm94eVJlcy5oZWFkZXJzWyd4LWFjY2VsLWJ1ZmZlcmluZyddID0gJ25vJ1xuICAgICAgICAgICAgICBwcm94eVJlcy5oZWFkZXJzWydjYWNoZS1jb250cm9sJ10gPSAnbm8tY2FjaGUnXG4gICAgICAgICAgICB9XG4gICAgICAgICAgfSlcbiAgICAgICAgfVxuICAgICAgfSxcbiAgICAgICcvYXBpL3NoYXJlLyc6IHtcbiAgICAgICAgdGFyZ2V0OiAnaHR0cDovL2xvY2FsaG9zdDoxMzk5JyxcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxuICAgICAgICBjb25maWd1cmU6IChwcm94eSkgPT4ge1xuICAgICAgICAgIHByb3h5Lm9uKCdwcm94eVJlcycsIChwcm94eVJlcykgPT4ge1xuICAgICAgICAgICAgaWYgKHByb3h5UmVzLmhlYWRlcnNbJ2NvbnRlbnQtdHlwZSddPy50b1N0cmluZygpLmluY2x1ZGVzKCd0ZXh0L2V2ZW50LXN0cmVhbScpKSB7XG4gICAgICAgICAgICAgIHByb3h5UmVzLmhlYWRlcnNbJ3gtYWNjZWwtYnVmZmVyaW5nJ10gPSAnbm8nXG4gICAgICAgICAgICAgIHByb3h5UmVzLmhlYWRlcnNbJ2NhY2hlLWNvbnRyb2wnXSA9ICduby1jYWNoZSdcbiAgICAgICAgICAgIH1cbiAgICAgICAgICB9KVxuICAgICAgICB9XG4gICAgICB9LFxuICAgICAgLy8gXHU1QkY5XHU1OTE2IEFJIFx1NjNBNVx1NTNFM1x1RkYwOE9wZW5BSSAvIEFudGhyb3BpYyBcdTUxN0NcdTVCQjlcdUZGMDlcdUZGMENcdTY3MkNcdTU3MzBcdTVGMDBcdTUzRDFcdTY1RjZcdTc2RjRcdTYzQTVcdTkwMUFcdThGQzdcdTUyNERcdTdBRUZcdTRFRTNcdTc0MDZcdThDMDNcdTc1MjhcbiAgICAgICcvdjEvJzoge1xuICAgICAgICB0YXJnZXQ6ICdodHRwOi8vbG9jYWxob3N0OjEzOTknLFxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXG4gICAgICAgIGNvbmZpZ3VyZTogKHByb3h5KSA9PiB7XG4gICAgICAgICAgcHJveHkub24oJ3Byb3h5UmVzJywgKHByb3h5UmVzKSA9PiB7XG4gICAgICAgICAgICBpZiAocHJveHlSZXMuaGVhZGVyc1snY29udGVudC10eXBlJ10/LnRvU3RyaW5nKCkuaW5jbHVkZXMoJ3RleHQvZXZlbnQtc3RyZWFtJykpIHtcbiAgICAgICAgICAgICAgcHJveHlSZXMuaGVhZGVyc1sneC1hY2NlbC1idWZmZXJpbmcnXSA9ICdubydcbiAgICAgICAgICAgICAgcHJveHlSZXMuaGVhZGVyc1snY2FjaGUtY29udHJvbCddID0gJ25vLWNhY2hlJ1xuICAgICAgICAgICAgfVxuICAgICAgICAgIH0pXG4gICAgICAgIH1cbiAgICAgIH1cbiAgICB9XG4gIH1cbn0pXG4iXSwKICAibWFwcGluZ3MiOiAiO0FBQXdRLFNBQVMsb0JBQW9CO0FBQ3JTLE9BQU8sU0FBUztBQUNoQixPQUFPLGdCQUFnQjtBQUN2QixPQUFPLGdCQUFnQjtBQUN2QixTQUFTLDRCQUE0QjtBQUNyQyxTQUFTLGVBQWU7QUFMeEIsSUFBTSxtQ0FBbUM7QUFPekMsSUFBTyxzQkFBUSxhQUFhO0FBQUEsRUFDMUIsU0FBUztBQUFBLElBQ1AsSUFBSTtBQUFBLElBQ0osV0FBVztBQUFBLE1BQ1QsS0FBSztBQUFBLE1BQ0wsV0FBVyxDQUFDO0FBQUEsSUFDZCxDQUFDO0FBQUEsSUFDRCxXQUFXO0FBQUEsTUFDVCxTQUFTLENBQUMsT0FBTyxjQUFjLE9BQU87QUFBQSxNQUN0QyxLQUFLO0FBQUEsSUFDUCxDQUFDO0FBQUE7QUFBQSxJQUVELHFCQUFxQjtBQUFBLE1BQ25CLFVBQVUsQ0FBQyxRQUFRLGtDQUFXLGtCQUFrQixDQUFDO0FBQUEsTUFDakQsVUFBVTtBQUFBLE1BQ1YsUUFBUTtBQUFBLE1BQ1IsYUFBYTtBQUFBLElBQ2YsQ0FBQztBQUFBLEVBQ0g7QUFBQSxFQUNBLFNBQVM7QUFBQSxJQUNQLE9BQU87QUFBQSxNQUNMLEtBQUssUUFBUSxrQ0FBVyxLQUFLO0FBQUEsSUFDL0I7QUFBQSxFQUNGO0FBQUEsRUFDQSxRQUFRO0FBQUEsSUFDTixNQUFNO0FBQUEsSUFDTixPQUFPO0FBQUEsTUFDTCxhQUFhO0FBQUEsUUFDWCxRQUFRO0FBQUEsUUFDUixjQUFjO0FBQUEsTUFDaEI7QUFBQSxNQUNBLGVBQWU7QUFBQSxRQUNiLFFBQVE7QUFBQSxRQUNSLGNBQWM7QUFBQSxRQUNkLFdBQVcsQ0FBQyxVQUFVO0FBQ3BCLGdCQUFNLEdBQUcsWUFBWSxDQUFDLGFBQWE7QUFFakMsZ0JBQUksU0FBUyxRQUFRLGNBQWMsR0FBRyxTQUFTLEVBQUUsU0FBUyxtQkFBbUIsR0FBRztBQUM5RSx1QkFBUyxRQUFRLG1CQUFtQixJQUFJO0FBQ3hDLHVCQUFTLFFBQVEsZUFBZSxJQUFJO0FBQUEsWUFDdEM7QUFBQSxVQUNGLENBQUM7QUFBQSxRQUNIO0FBQUEsTUFDRjtBQUFBLE1BQ0EsZUFBZTtBQUFBLFFBQ2IsUUFBUTtBQUFBLFFBQ1IsY0FBYztBQUFBLFFBQ2QsV0FBVyxDQUFDLFVBQVU7QUFDcEIsZ0JBQU0sR0FBRyxZQUFZLENBQUMsYUFBYTtBQUNqQyxnQkFBSSxTQUFTLFFBQVEsY0FBYyxHQUFHLFNBQVMsRUFBRSxTQUFTLG1CQUFtQixHQUFHO0FBQzlFLHVCQUFTLFFBQVEsbUJBQW1CLElBQUk7QUFDeEMsdUJBQVMsUUFBUSxlQUFlLElBQUk7QUFBQSxZQUN0QztBQUFBLFVBQ0YsQ0FBQztBQUFBLFFBQ0g7QUFBQSxNQUNGO0FBQUE7QUFBQSxNQUVBLFFBQVE7QUFBQSxRQUNOLFFBQVE7QUFBQSxRQUNSLGNBQWM7QUFBQSxRQUNkLFdBQVcsQ0FBQyxVQUFVO0FBQ3BCLGdCQUFNLEdBQUcsWUFBWSxDQUFDLGFBQWE7QUFDakMsZ0JBQUksU0FBUyxRQUFRLGNBQWMsR0FBRyxTQUFTLEVBQUUsU0FBUyxtQkFBbUIsR0FBRztBQUM5RSx1QkFBUyxRQUFRLG1CQUFtQixJQUFJO0FBQ3hDLHVCQUFTLFFBQVEsZUFBZSxJQUFJO0FBQUEsWUFDdEM7QUFBQSxVQUNGLENBQUM7QUFBQSxRQUNIO0FBQUEsTUFDRjtBQUFBLElBQ0Y7QUFBQSxFQUNGO0FBQ0YsQ0FBQzsiLAogICJuYW1lcyI6IFtdCn0K
