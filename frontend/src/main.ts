import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './theme.css'   // 主题 token：必须早于 tailwind / app，色阶变量由它提供
import './tailwind.css'
import './app.css'
import App from './App.vue'

createApp(App).use(ElementPlus).mount('#app')
