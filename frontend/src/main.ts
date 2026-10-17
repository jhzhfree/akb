import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './theme.css'   // 主题 token：必须早于 tailwind / app，色阶变量由它提供
import './tailwind.css'
import './app.css'
import './answer.css'   // 答案排版：与嵌入组件 kos-chat.js 共用同一套 .md-* 基准（见文件头说明）
import App from './App.vue'

createApp(App).use(ElementPlus).mount('#app')
