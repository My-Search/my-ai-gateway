import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import 'virtual:svg-icons-register'
import './assets/styles/index.css'
import './stores/loading' // 确保 loading store 被注册

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
