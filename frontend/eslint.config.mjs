import tsParser from "@typescript-eslint/parser";
import tsPlugin from "@typescript-eslint/eslint-plugin";
import vueParser from "vue-eslint-parser";
import vuePlugin from "eslint-plugin-vue";

/**
 * 前端编码规范（ESLint flat config）。
 * 取舍原则与后端一致：只启用能拦住真实缺陷与运维风险的规则，
 * 纯审美规则（缩进、分号、引号样式）交给 Prettier，两者不互相打架。
 */
export default [
  {
    ignores: ["dist/**", "node_modules/**", "**/*.d.ts", "public/**"],
  },
  {
    files: ["**/*.{ts,vue,js}"],
    languageOptions: {
      parser: vueParser,
      parserOptions: {
        parser: tsParser,
        ecmaVersion: 2022,
        sourceType: "module",
        extraFileExtensions: [".vue"],
      },
    },
    plugins: {
      "@typescript-eslint": tsPlugin,
      vue: vuePlugin,
    },
    rules: {
      // ===== 安全 / 生产环境风险 =====
      "no-debugger": "error", // 调试语句进生产
      "vue/no-v-html": "error", // XSS 入口：渲染富文本必须走净化
      "vue/no-v-text-v-html-on-component": "warn",

      // ===== 可运维性：留了 "临时用一下" 的代码 =====
      "no-console": ["warn", { allow: ["warn", "error"] }], // console.log 应换成日志或移除
      "no-alert": "warn",
      "no-eval": "error",

      // ===== 稳定性 =====
      "no-var": "error",
      "prefer-const": "warn",
      eqeqeq: ["warn", "always", { null: "ignore" }],
      "@typescript-eslint/no-explicit-any": "warn", // any 会让类型检查失效，逐步收敛
      "@typescript-eslint/no-unused-vars": "warn",
      "vue/require-prop-type-constructor": "warn",
      "vue/no-async-in-computed-properties": "error",
      "vue/no-side-effects-in-computed-properties": "error",
      "vue/no-unused-components": "warn",
      "vue/no-unused-vars": "warn",
    },
  },
  {
    // 已通过安全评审的两个例外：HTML 均来自 escapeHtml 之后的受控拼接，
    // 其余任何地方新增 v-html 仍会被上面的 error 规则拦住。
    files: ["src/components/EvidenceViewer.vue", "src/components/QueryPanel.vue"],
    rules: {
      "vue/no-v-html": "off",
    },
  },
];
