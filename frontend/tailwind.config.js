/** tailwind.config —— ai-knowledge-os 前端主题
 *  ink / brand 是语义色阶：50 = 背景端，950 = 文本端。
 *  值全部指向 theme.css 的通道变量（[data-theme] 切换时整体换肤），
 *  用 rgb(var(--x) / <alpha-value>) 形式保留 bg-xxx/30 这类透明度修饰符。
 */
const token = (name) => `rgb(var(${name}) / <alpha-value>)`

export default {
  content: ['./index.html', './src/**/*.{vue,ts,tsx}'],
  theme: {
    extend: {
      colors: {
        ink: {
          50: token('--ink-50-rgb'),
          100: token('--ink-100-rgb'),
          200: token('--ink-200-rgb'),
          300: token('--ink-300-rgb'),
          400: token('--ink-400-rgb'),
          500: token('--ink-500-rgb'),
          600: token('--ink-600-rgb'),
          700: token('--ink-700-rgb'),
          800: token('--ink-800-rgb'),
          900: token('--ink-900-rgb'),
          950: token('--ink-950-rgb'),
        },
        // accent = 强调文字/链接色（深浅两档由主题给出），组件里写 text-accent 即可
        accent: token('--accent-text-rgb'),
        brand: {
          50: token('--brand-50-rgb'),
          100: token('--brand-100-rgb'),
          200: token('--brand-200-rgb'),
          300: token('--brand-300-rgb'),
          400: token('--brand-400-rgb'),
          500: token('--brand-500-rgb'),
          600: token('--brand-600-rgb'),
          700: token('--brand-700-rgb'),
          800: token('--brand-800-rgb'),
          900: token('--brand-900-rgb'),
        },
      },
    },
  },
  corePlugins: {
    preflight: false, // app.css 已做 reset，避免与 Element Plus 冲突
  },
}
