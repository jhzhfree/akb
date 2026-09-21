# 编码规范（多语言）

本工程同时包含 Java（backend）、Python（ai-service + scripts）、Vue/TypeScript（frontend）、
Shell 与 SQL。规范的目标不是"统一审美"，而是**让缺陷在 CI 阶段暴露，而不是在生产排障时被发现**。

因此规则取舍有一个统一原则：

> 只启用**能指向真实缺陷或明确运维风险**的规则；纯排版类规则交给格式化工具自动处理，
> 不产生需要人工裁决的告警。告警必须少而准，否则很快会被全员忽略。

## 一、快速开始

```bash
make tools-install   # 一次性安装：ruff 与 checkstyle jar（安装到 ~/.local，不入版本库）
make lint            # 全量静态检查：Python + Java + 前端
make format-python   # Python 安全自动修复 + 格式化
make format-frontend # 前端 ESLint 自动修复
```

各语言单独执行：`make lint-python` / `make lint-java` / `make lint-frontend`。

## 二、Java（backend）

检查工具：Checkstyle 8.45（规则集 `config/checkstyle/checkstyle.xml`）。
**没有**引入 Maven checkstyle 插件——本机可 `mvn -o` 离线构建，挂插件会为离线环境引入新依赖，
因此改由 Makefile 与 CI 调用，构建链路保持零新增依赖。

强制（CI 阻断）的规则：

| 规则 | 拦截的问题 |
|---|---|
| `NeedBraces` | 单行 `if/for/while` 不加花括号：后续加语句极易漏写，属线上事故高频根因 |
| `EmptyCatchBlock` | 吞异常。确需忽略时必须写成 `catch (XxxException ignored)`，让意图可见 |
| `UnusedImports` / `RedundantImport` / `AvoidStarImport` | 依赖不清晰，误导阅读者以为用到了某个类 |
| `StringLiteralEquality` | 用 `==` 比较字符串，恒为 false 的逻辑炸弹 |
| `EqualsHashCode` | 只重写 `equals` 不重写 `hashCode`，进集合即出错 |
| `MissingSwitchDefault` / `FallThrough` | 分支遗漏、case 穿透 |
| `OneStatementPerLine` | 同行多语句：插入日志或断点时容易错位 |
| `NeedBraces` + `LeftCurly`(eol) | 统一 K&R 花括号风格（Google Java Style 一致） |
| 命名类 | `ConstantName` 等；其中 `log` 被显式放行，遵循 SLF4J 惯例 |

仅提示、不阻断的规则：

- `LineLength`（160）：现存超长行集中在 SQL 字符串、日志模板，以及规范化后合并的方法签名。
  工具强行折 SQL 字符串会带来拼接风险且可读性更差，因此降级为 info，由新代码逐步收敛。

## 三、Python（ai-service、scripts）

检查与格式化工具：ruff（`ruff.toml`），格式化风格与 Black 兼容。

启用：`F`（未定义/未使用）、`E/W`（pycodestyle）、`I`（导入顺序）、`UP`（语法现代化）、
`B`（bugbear 易错写法）、`SIM`（可简化结构）、`RET`（返回值一致性）、`C4`（推导式）。

被有意关闭的规则及理由（写进了 `ruff.toml` 注释）：

- `UP031`：`logging` 依赖 `%` 做惰性求值，改 f-string 会让日志无条件拼字符串（性能与 GC 压力）。
- `B008`：函数调用作默认参数是 FastAPI 依赖注入（`File()` / `Depends()`）的官方写法。
- `RUF001`：歧义 Unicode 检测，中文项目必然命中，无监控价值。
- `E402`：测试与运维脚本常先调整 `sys.path` 再导入，属有意为之（按路径豁免）。
- `E501`：行长交给 formatter，不在 lint 里重复报。

## 四、前端（Vue / TypeScript）

检查工具：ESLint flat config（`frontend/eslint.config.mjs`）；格式由 Prettier 负责（`npm run format`）。
命令：`npm run lint` / `npm run lint:fix` / `npm run typecheck`。

重点规则：

| 规则 | 拦截的问题 |
|---|---|
| `no-debugger` | 调试语句进生产 |
| `vue/no-v-html` | XSS 入口。当前仅 `EvidenceViewer.vue`、`QueryPanel.vue` 两处豁免： |
| | 它们的数据都先经 `escapeHtml` 再拼接受控标签（见 `utils/markdown.ts`），已在配置中显式列出 |
| `vue/no-async-in-computed-properties` | computed 里做异步：视图不会更新，典型隐蔽缺陷 |
| `vue/no-side-effects-in-computed-properties` | computed 里改状态：触发渲染循环 |
| `no-console` | `console.log` 应换成日志或删除（`console.warn/error` 允许保留） |
| `@typescript-eslint/no-explicit-any` | 目前为 warn，属渐进收敛项，见下节 |

### 渐进收敛项

`@typescript-eslint/no-explicit-any` 目前有少量遗留（`QueryTracePanel.vue`），保持 warn 不阻断 CI。
这些位置都依赖后端返回的动态结构，改造需要先固化对应的响应类型契约，避免为了消告警而做假类型。

## 五、Shell

所有 `.sh` 脚本统一使用 `set -euo pipefail`，即：**任一命令失败立即退出**，
避免"中间步骤挂了但脚本仍判定成功"——这在发布/校验类脚本里会直接误导决策。

## 六、约定俗成的写法

- **日志而非打印**：Java 用 SLF4J，Python 用标准 `logging`，前端 `console.warn/error` 仅在必要处保留。
- **异常不裸抛**：Python 重新抛出异常时一律 `raise X from exc`，保留原始异常链，
  否则服务端日志看不到根因（`B904` 规则强制）。
- **外置配置**：地址、口令、阈值一律走环境变量或配置中心，禁止写死在源码里；
  已做过全仓扫描，当前无硬编码凭据泄露。
- **迭代式修复原则**：能由工具安全自动修复的，直接跑 `make format-*`；
  涉及语义判断的（如去掉看起来"没用到"的变量）必须人工确认后再改。
