/** @type {import('tailwindcss').Config} */
// brand / teal 色阶与 src/app.css 的 :root token 一一对应，改色请两边同步，
// 否则同一语义会出现「 tailwind 类一种蓝、自定义 CSS 另一种蓝 」。
export default {
  content: ['./index.html', './src/**/*.{vue,ts}'],
  theme: {
    extend: {
      colors: {
        ink: {
          50:'#f6f7f9',100:'#eceef2',200:'#d5d9e2',300:'#b0b8c9',400:'#8591a9',
          500:'#66738e',600:'#515c74',700:'#424b5e',800:'#3a4050',900:'#343845',950:'#23262e',
        },
        brand: {
          50:'#eef4ff',100:'#dbe7fe',200:'#c3d7fd',300:'#93b4fd',400:'#5b8bf7',
          500:'#2563eb',600:'#1d4ed8',700:'#1b3fa8',800:'#172554',900:'#111a3c',
        },
        teal: { 400:'#14b8a6', 500:'#0f766e' },
      },
      fontFamily: {
        sans:['Inter','PingFang SC','Microsoft YaHei','system-ui','sans-serif'],
        mono:['JetBrains Mono','Cascadia Code','Consolas','monospace'],
      },
      borderRadius: {
        sm:'8px', md:'12px', lg:'16px', xl:'20px',
      },
      boxShadow: {
        card:'0 1px 2px rgba(16,24,40,.04)',
        lift:'0 4px 14px rgba(23,37,84,.07)',
        float:'0 16px 40px rgba(23,37,84,.14)',
      },
    },
  },
  plugins: [],
}
