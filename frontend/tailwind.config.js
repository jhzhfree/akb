/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,ts}'],
  theme: {
    extend: {
      colors: {
        ink: {
          50:'#f6f7f9',100:'#eceef2',200:'#d5d9e2',300:'#b0b8c9',400:'#8591a9',
          500:'#66738e',600:'#515c74',700:'#424b5e',800:'#3a4050',900:'#343845',950:'#23262e',
        },
      },
      fontFamily: {
        sans:['Inter','PingFang SC','Microsoft YaHei','system-ui','sans-serif'],
        mono:['JetBrains Mono','Cascadia Code','Consolas','monospace'],
      },
    },
  },
  plugins: [],
}
