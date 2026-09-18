import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './tailwind.css'
import './app.css'
import App from './App.vue'

createApp(App).use(ElementPlus).mount('#app')
