# 网站嵌入问答组件（Embed Chat Widget）

> 目标：把知识库检索与问答能力做成一个**可以被任意第三方 Web 站点引用**的组件。
> 形态参考 Dify 的网站嵌入聊天组件：一行 `<script>` 引入 → 右下角悬浮气泡 → 打开聊天面板 → 流式出答案 → 可展开服务端检索过程。
> 本文是设计与实现说明，面向研发与实施人员。

---

## 1. 它解决什么问题

| 使用方 | 过去 | 现在 |
|---|---|---|
| 业务系统（OA、工单、门户） | 自己对接 `/api/v1/external/*`，自己写 UI | 引一行 script，配置凭证即可 |
| 终端用户 | 看不到答案是怎么来的 | 展开"检索过程"看各阶段耗时；点答案里的 `[证据N]` 定位到引用卡片，再展开证据原文 |
| 平台方 | API Key 一旦下发就难以约束使用范围 | 短期令牌 + 来源站点绑定 + 只读 scope + 独立限流 |

设计上坚持三条原则：

1. **凭证不落地到浏览器**：页面持有的是短期嵌入令牌（`kos_et_…`），API Key 只在服务端换令牌时出现一次。
2. **组件不绕过既有安全模型**：仍走 `ExternalAccessService`（限流、scope、知识库边界、可见性），仍受 ACL 三态标签约束 —— 组件拿不到 UI 之外的能力。
3. **检索过程必须是真实数据**：阶段与耗时来自 AI 服务实际执行的 pipeline，不是前端伪造的进度动画。

---

## 2. 整体结构

```
第三方站点 index.html
  └─ <script src="https://<host>/embed/kos-chat.js" data-token="kos_et_…">
        └─ Light DOM 组件（悬浮按钮 + 面板 + 检索过程时间线）
              │  GET /api/v1/external/stream/query   (SSE，X-Embed-Token)
              ▼
        backend  EmbedCorsFilter → ExternalStreamController → ExternalStreamService
              │  POST /ai/query
              ▼
        ai-service  QueryEngine（rewrite→route→recall→acl_filter→fusion→rerank→context→llm→citation）
```

| 层 | 文件 | 职责 |
|---|---|---|
| 组件 | `frontend/public/embed/kos-chat.js` | 零依赖原生 JS，Light DOM 渲染（样式限定在 `.kos-host` 内、变量跟随宿主主题），SSE 消费与检索过程渲染 |
| 答案排版基准 | `frontend/src/answer.css` | 全站 `.md-*` 规则的唯一来源，组件内在 `buildStyles()` 有一份同值副本，由 `scripts/check-answer-style.mjs` 守住一致 |
| 演示 | `frontend/public/embed/demo.html` | 接入示例与本地联调页 |
| 跨域 | `backend/…/config/EmbedCorsFilter.java` | 按凭证白名单动态回显 `Access-Control-Allow-Origin`，处理预检 |
| 握手 | `backend/…/controller/EmbedController.java` | `/embed/token` 换令牌、`/embed/config` 下发阶段定义与知识库默认检索语句（`knowledgeBaseDefaultQuery`） |
| 令牌 | `backend/…/service/EmbedTokenService.java` | HMAC-SHA256 自包含令牌，无会话表，任意实例可校验 |
| 鉴权 | `backend/…/service/ExternalAccessService.java` | 新增 `authenticateEmbed / authenticateEmbedToken / assertOrigin / allowsOrigin` |
| 流式 | `backend/…/service/ExternalStreamService.java` | 推送真实 pipeline 阶段（原来只有 5 个硬编码假阶段） |
| 凭证 | `t_api_key.allowed_origins`（`V45__embed_widget_origins.sql`） | 站点白名单，签发与校验两端都用它 |

---

## 3. 安全模型（最重要的一节）

### 3.1 三种凭证的关系

| 凭证 | 形态 | 谁能持有 | 生命周期 |
|---|---|---|---|
| API Key | `kos_ak_…`，库里只存 SHA-256 | 服务端 | 长期，可吊销 |
| 嵌入令牌 | `kos_et_…`，HMAC 签名自包含 | 浏览器 | 默认 12h（`external.embed-token-hours`），签发时可指定，也可签发长期有效令牌 |
| 会话 Token | 控制台登录态 | 控制台 | 与登录态一致 |

**令牌 payload**：`keyId | knowledgeBaseId | origin | expiresAtMillis | nonce` + HMAC 签名，
其中 `expiresAtMillis = 0` 表示长期有效。
它不入库，因此吊销 API Key 会让其签发的所有令牌立即失效 —— 不需要额外的令牌黑名单。

### 3.2 四道闸

1. **签名与有效期**：`EmbedTokenService.parse()` 校验 HMAC（恒定时间比较）与过期时间；长期令牌（`expiresAt = 0`）跳过过期判断，仍受签名、来源与吊销校验约束。
2. **来源绑定**：令牌里记录的 origin 必须与请求 origin 一致；key 的 `allowed_origins` 非空时，请求 origin 必须在其中（全局 `external.allowed-origins` 作为兜底）。
3. **范围与边界**：`resolveKnowledgeBase()` —— 绑定了知识库的凭证传别的 id 一律拒绝；未绑定的只能访问公开库。`assertScope(READ)`。
4. **限流**：`ExternalRateLimiter` 按 key 的 60 秒滑动窗口（默认 60/分钟），令牌与 API Key 共用同一计数。

### 3.3 跨域为什么不能用 `*`

组件加载在别人的页面里，浏览器必须被告知"哪些来源可以读响应"。
若回显 `*`，任何站点都能拿着泄露的凭证读取答案流。`EmbedCorsFilter` 的做法：

- 预检（`OPTIONS`）：浏览器不带凭证，先乐观放行（真正的校验发生在随后的真实请求）；
- 真实请求：解析凭证 → 校验 origin → 命中才写 `Access-Control-Allow-Origin: <origin>`，否则直接 403；
- 服务端调用（无 `Origin` 头）：不受影响，行为与过去一致。

> 因此移除了 `ExternalApiController` / `ExternalStreamController` 上原来的裸 `@CrossOrigin`（它等价于 `*`）。

---

## 3b. 控制台生成嵌入代码（无需手写）

「接口服务 → 嵌入组件」页签（`frontend/src/components/EmbedWidgetPanel.vue`）：

1. 选知识库、填标题 / 主题色 / 示例问题 / 允许嵌入的站点来源；
2. 填 API Key → 点「生成令牌」：前端直接调 `POST /api/v1/external/embed/token`（带 `X-API-Key` 与本页 `Origin`），
   因此**签发过程与真实调用方完全一致**，来源白名单没配对当场就会报出来；
3. 复制生成的 `<script>` 片段（令牌已内嵌），或点「打开联调演示页」。

必须记住的一条：**API Key 只在服务端出现一次**。控制台生成是为了联调方便，
生产应把这段换令牌逻辑放在业务系统后端，页面只拿短期令牌。

## 3c. 外部联调演示页

前端静态资源提供 `https://<host>/embed/demo.html`，模拟第三方站点：

- 第 1 步：填 apiBase、API Key → 换令牌（显示有效期与绑定来源）→ 挂载右下角组件；
- 第 2 步：不经过组件直接调 SSE，**把原始事件流和到达时刻打出来**（`+ms` 相对请求发出），
  用来确认「阶段是不是真的陆续到达」而不是一次性回放；
- 第 3 步：生产接入代码片段。

## 4. 接入步骤

### 步骤 1：签发 API Key 并绑定站点

```
POST /api/external/admin/keys
{
  "name": "OA 门户嵌入",
  "knowledgeBaseId": 1,
  "scopes": ["READ"],
  "rateLimitPerMinute": 30,
  "allowedOrigins": ["https://oa.corp.com"]
}
```

`allowedOrigins` 为空表示不限制来源（仅建议内网可信环境）。控制台的"对外服务 → API Key"已支持该字段。

### 步骤 2：页面加载时用后端换取短期令牌

```
POST /api/v1/external/embed/token?knowledgeBaseId=1
X-API-Key: kos_ak_xxxxxxxx
Origin: https://oa.corp.com
```

`expiresInSeconds` 指定有效期（下限 60 秒，上限 `external.embed-token-max-hours`，默认 8760 小时）；
传 `-1`（或 `permanent=true`）签发**长期有效**令牌，此时部署配置 `external.embed-token-permanent=false` 会被拒绝。
不传则沿用 `external.embed-token-hours`。

响应（限期）：

```json
{ "token": "kos_et_v1.…", "permanent": false, "expiresAt": 1770000000000, "expiresInSeconds": 43200,
  "knowledgeBaseId": 1, "origin": "https://oa.corp.com", "scope": "READ" }
```

响应（长期有效，`expiresAt` / `expiresInSeconds` 为 `null`）：

```json
{ "token": "kos_et_v1.…", "permanent": true, "expiresAt": null, "expiresInSeconds": null,
  "knowledgeBaseId": 1, "origin": "https://oa.corp.com", "scope": "READ" }
```

**这一步应该在业务系统的服务端完成**（它持有 API Key），把 token 下发给页面；
页面自己换令牌等于把 API Key 暴露出去，仅适合内网演示。

### 步骤 3：引入组件

```html
<script src="https://<host>/embed/kos-chat.js"
        data-api-base="https://<host>/api/v1/external"
        data-token="kos_et_…"
        data-kb="1"
        data-title="知识库助手"
        data-primary="#2f6bff"
        data-samples="这份制度适用于哪些部门？|最近变更了什么？"></script>
```

或显式初始化：

```js
KnowledgeOSChat.init({
  apiBase: 'https://<host>/api/v1/external',
  token: 'kos_et_…',
  knowledgeBaseId: 1,
  showTrace: true,
  sampleQuestions: ['这份制度适用于哪些部门？']
});
```

可选参数：`title / subtitle / welcome / placeholder / primary / position(left|right) / topK / minScore(0~1，最低相关度阈值) / answerFormat / content(stream|final) / showTrace / showCitations / apiKey（不推荐）`。

> `placeholder` 留空时，组件挂载后会用 `/embed/config` 下发的 `knowledgeBaseDefaultQuery`（知识库预置的示例问句）作输入框提示，并在宿主未指定 `data-samples` 时把它作为唯一示例问题；知识库没配或配置取不到时退回内置文案「输入你的问题，回车发送」。

### 步骤 4：样式接入（Light DOM + 作用域限定）

组件**不使用 Shadow DOM**，而是渲染成宿主页面里的普通 DOM：

```
body
 └─ div.kos-widget-host        ← 根节点
      ├─ style                  ← 组件样式（全局可见，但每条选择器都带 .kos-host 作用域）
      └─ div.kos-host           ← 面板容器，样式作用域的锚点
           ├─ .kos-panel / .kos-footer / .kos-card …
           └─ .md-answer / .md-cite / .md-badge …   ← 答案排版
```

**作用域**：组件样式虽然是一条全局 `<style>`，但每条选择器都被自动加上 `.kos-host` 前缀
（`buildStyles()` 里的 `scopeCss()`）。因此 `.md-answer { … }` 实际是 `.kos-host .md-answer { … }`，
**不会**命中宿主页面上的同名元素 —— 这点很关键：控制台自己也有 `.md-answer`，
不加限定两边会互相改坏。

**主题跟随**：组件变量是三级 fallback —— 外部覆盖 → 宿主应用主题 → 组件默认：

```css
.kos-host {
  --kos-ink-1: var(--kos-external-ink-1, var(--ink-1, #0f172a));
  --kos-bg-sub: var(--kos-external-bg-sub, var(--bg-sub, #f1f5f9));
  /* … */
}
```

于是：嵌进控制台时 `--ink-*` / `--bg-*` 已存在，组件**自动跟随浅色/深色主题**；
嵌到第三方站点时这些变量不存在，退回组件自带配色。

**主题化变量**（写在 `:root` 或 `.kos-widget-host` 上，优先级高于应用主题）：

```css
:root {
  --kos-external-primary: #047857;        /* 主色（按钮、高亮）*/
  --kos-external-primary-dark: #064e3b;   /* 主色深调（渐变收尾）*/
  --kos-external-primary-soft: #ecfdf5;   /* 主色浅底（hover 底、焦点环）*/
  --kos-external-accent: #047857;         /* 强调文字（链接、引用上标）*/
  --kos-external-ink-1: #0f172a;          /* 正文 */
  --kos-external-ink-2: #334155;          /* 次要文字 */
  --kos-external-ink-3: #64748b;          /* 辅助文字 / 元信息胶囊 */
  --kos-external-ink-4: #94a3b8;          /* 最弱文字 / 脚注 */
  --kos-external-line: #e5e7eb;           /* 边框 */
  --kos-external-line-soft: #f1f5f9;      /* 浅分隔 */
  --kos-external-bg: #f8fafc;             /* 页面底色 */
  --kos-external-bg-sub: #f1f5f9;         /* 次级表面（代码底、表头）*/
  --kos-external-bg-sunken: #eef1f8;      /* 下陷表面（代码块）*/
  --kos-external-surface: #fff;           /* 卡片底色 */
  --kos-external-info-bg: #eff6ff;        /* 信息浅底（hover 底）*/
  --kos-external-radius: 14px;            /* 圆角基数 */
  --kos-external-font: inherit;           /* 字体族，缺省走系统字体栈 */
}
```

组件的 `data-primary` 会写入 `--kos-external-primary`，因此外部再覆盖这个变量即可整体换色。

**覆盖单条规则**：组件规则的选择器是 `.kos-host <原选择器>`，要比它更高 specificity 才能覆盖：

```css
.kos-widget-host .kos-host .md-cite-chip { background: #fef3c7; }
```

**如果宿主页面的 reset 太激进**（例如 `* { all: unset }`）把组件结构也冲掉了，可以只对组件根节点恢复：

```css
.kos-widget-host { all: revert; }
```

### 步骤 5：答案排版与控制台保持一致（自动校验）

「控制台里检索结果长什么样」和「嵌入组件里长什么样」必须是同一套，否则同一个答案在两个地方显示不同。

- **基准**：`frontend/src/answer.css`（全局，`main.ts` 引入）—— 唯一的 `.md-*` 来源。
- **组件侧**：`kos-chat.js` 的 `buildStyles()` 里有一份**同值**的 `.md-*` 规则
  （组件不参与构建，无法 import，只能抄一份）。
- **渲染结构**：控制台 `utils/markdown.ts` 与组件 `renderMarkdown()` 输出**同一套标签**：
  `h3/h4/h5 + .md-heading`、`<b>`（不是 `<strong>`）、`<code class="md-code">`、
  `.md-codeblock > .md-codebar + .md-pre`、`.md-table-wrap > table`、`<li class="md-task"> + .md-check`、
  `<sup class="md-cite">`。

抄写会漂移，所以有一道校验：

```bash
cd frontend && npm run check:answer-style
```

它解析两边规则，把组件侧 `--kos-*` 映射回应用主题变量后逐条比对声明，不一致就非零退出：

```
answer.css 规则: 52  组件侧规则: 89
比对声明: 168
✓ 嵌入组件与控制台的答案排版规则一致
```

**改任一边都要同步另一边**，然后跑这个脚本确认。

#### 前提一：两边必须请求同一种 answerFormat

排版一致只解决「同一份文本渲染成同一个样子」。如果两边请求的格式不同，拿到的是**两份不同的文本**，
排版再对齐也还是「不一样」：

| 格式 | 内容 |
|---|---|
| `default` | 模型原文，可能不规范（`##年假天数` 无空格、`[证据]` 无编号） |
| `markdown` | 服务端规范化后的正文 + 引用原文章节 |
| `text` / `html` | 纯文本 / 富文本（引用原文已内联） |

**两侧默认值统一为 `markdown`**（控制台 `App.vue` 的 `answerFormat`、组件 `data-answer-format`、
`demo.html` 与嵌入预览面板）。任一侧手动改成别的格式，内容自然不同 —— 这是预期行为，
但排查「显示不一致」时应先核对这一点。

#### 前提二：输入容错规则也要一致

模型原文常写出**标记后不带空格**的 Markdown，两边解析器必须用同一套容错规则，否则同一条
`-旧口径（…）` 在一边是列表项、在另一边是段落：

| 输入形态 | 处理 |
|---|---|
| `##年假天数`（`#` 后无空格） | 仍识别为标题（否则整行退化成段落） |
| `-旧口径`（`-` 后无空格） | 仍识别为列表项 |
| `-09-01 是生效日` | **不是**列表项（减号紧跟数字视为日期/负数） |
| `- 带空格` | 列表项（常规写法） |

两处实现：`frontend/src/utils/markdown.ts` 与 `kos-chat.js` 的 `renderMarkdown()`，
改动需成对进行。

#### 前提三：组件必须以 done 帧的 answer 为准

流式 `delta` 里只有**模型原文**；请求格式的加工（markdown 追加「引用原文」小节、
text 剥掉标记、html 转成标签）只出现在 `done` 帧的 `answer` 字段里，
由 `ai-service/app/services/answer_formatting.py` 的 `render()` 生成，
在 `query_engine.py` 收尾时调用一次。

组件拿流式拼出来的原文去渲染，就会丢掉这层加工，表现为「一整段无结构文本、没有引用原文区」；
而控制台读的是 done 的 answer，所以是加工后的样子 —— 两边就此分叉。
`kos-chat.js` 的 `done` 事件里**无条件**用 `lastPayload.answer` 覆盖拼出来的文本，
这是有意为之，不要改回「只在没收到 delta 时才兜底」。

#### 前提四：delta 分片必须按 SSE 规范分帧

SSE 的 `data` 字段**不能包含裸换行**。`SseEmitter` 对 String 是原样写出的：一个换行就结束了这个
字段，切片剩下的部分落在没有 `data:` 前缀的行上，按规范的客户端会**整行丢弃** —— 换行连同它的
内容一起消失，答案挤成一坨（标题、列表、`[证据N]` 全糊在一行里）。

发送端把切片按行拆成多条 `data:` 字段（`ExternalStreamService.deltaEvent`），客户端用 `\n` 拼回，
与原文逐字符相同；`ExternalStreamServiceTest` 锁住这个帧格式。排查「组件里挤成一坨」先看这条，
再看 `done` 有没有把 `answer` 交出去。

#### 前提五：默认「过程流式、答案整体交付」

控制台走 `POST /api/knowledge/query`（阻塞），拿一次完整的 `answer` + `citations`，再
`renderMarkdown` 渲染——所以控制台**从来不显示未加工的模型原文**。组件默认对齐这个行为：

- `content=final`（默认）：SSE 只承担**检索过程**（`stage` / `citation`），不推 `delta` 切片；
  答案在 `done.answer` 里整体拿到——和阻塞式返回的 `answer` 是同一份加工产物，排版与控制台一致。
- `content=stream`：保留旧的逐字 `delta` 流式——给需要「打字机」效果或要边出边停的场景用。
  切片是模型原文（没有引用上标、没有「引用原文」小节），边收边渲染会和控制台不一样，
  属于已知差异（见前提一/二/三/四）。

需要逐字流式时显式声明：`data-content="stream"` 或 `init({ content: 'stream' })`。
组件会在 `open` 帧里回显服务端实际生效的 `content`；取值未识别会退回 `stream`，并 `console.warn`
（与 `answerFormat` 的诊断方式一致）。

### 3.1 答案里的引用与证据

「可追溯」在组件里是三层，与控制台问答页同一套信息层次：

| 层 | 交互 | 数据来自 |
|---|---|---|
| 答案上标 `[证据N]` | 点击滚动并高亮到对应引用卡片 | 答案正文 + `citation` 事件 |
| 引用来源卡片 | 序号 / 标题 / 出处 / 页码 / 通道 / 相关度 / 可展开的原文摘录 | `citation.citations[]` |
| 证据原文抽屉 | 来源、文档、页码、内容单元、章节、原文（引用片段高亮）、Authority / Freshness / HASH | `GET /api/v1/external/evidence/{code}` |

证据抽屉走的是同一枚嵌入令牌、同一套审计，没有为组件额外开口子；取不到原文时退回引用里自带的摘录，
而不是把按钮藏起来——「看不了」和「没有」必须是两种不同的提示。

服务端在 `answerFormat=markdown` 时会往答案尾部追加「### 引用原文」小节。组件只在确认尾部确实只有引用条目时
才摘掉它，改由引用卡片承载：同一个清单出现两次比没有更糟。

---

## 5. SSE 事件协议

`GET /api/v1/external/stream/query?question=…&topK=10&answerFormat=markdown&content=final&knowledgeBaseId=1`

| 事件 | 载荷 | 说明 |
|---|---|---|
| `open` | `{knowledgeBaseId, question, topK, answerFormat, stages:[{key,label}]}` | 握手，先告知将要执行哪些阶段，UI 可先画出骨架 |
| `stage` | `{key, label, status:running\|done, latencyMs, inputCount, outputCount, detail}` | **真实** pipeline 阶段；先发 running，完成后发 done 并带实测值 |
| `delta` | 文本片段（多 `data:` 字段，用 `\n` 拼回） | 答案分片：流式路径按 token，回放路径按字符切片。切片里的换行按 SSE 规范拆成多条 `data:` 字段，解析时用 `\n` 拼回 |
| `citation` | `{citations:[…]}` | 引用来源列表 |
| `done` | `{queryId, answer, answerFormat, model, trace, confidence, durationMs, channels, strategies, reranked, questionTypeLabel, rewrittenQuery, conclusionSource, answerQualification, contextChars, stages, elapsedMs, streamed}` | 结束；元信息字段供 UI 解释「这个答案为什么可信」（置信度、召回通道、结论来源、门控），只取 `queryId` / `answer` 的旧客户端照常工作 |
| `error` | 文本 | 失败（含限流、权限、依赖不可用） |

阶段定义（`EmbedController` 与 `ExternalStreamService` 保持一致，与 ai-service 的 `query_engine` 对齐）：

| key | 含义 |
|---|---|
| `rewrite` | 查询改写 |
| `route` | 意图路由 |
| `recall` | 多通道召回（关键词/向量/卡片/断言/图谱） |
| `acl_filter` | 权限过滤（先于排序下推） |
| `fusion` | 加权 RRF 融合 |
| `rerank` | 重排序 |
| `context` | 上下文装配 |
| `llm` | 答案生成 |
| `citation` | 引用校验 |

> 兼容性说明：旧的五个大写阶段名（`REWRITE/RECALL/FUSION/RERANK/ANSWER`）已替换为真实阶段名，
> 控制台"外部接口实验室"里做阶段顺序校验的断言需要同步更新。

---

## 6. 配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `EXTERNAL_ALLOWED_ORIGINS` | 空 | 全局允许的嵌入来源（逗号分隔），作为 key 级白名单的兜底 |
| `EXTERNAL_EMBED_TOKEN_HOURS` | 12 | 嵌入令牌有效期（1–720 小时） |
| `EXTERNAL_EMBED_TOKEN_SECRET` | 空（首次启动生成并持久化到 `~/.knowledge-os/embed-token.secret`） | 令牌签名密钥；**多副本部署必须在每个节点配置成同一个值**，否则一个节点签发的令牌在另一个节点校验失败 |
| `EXTERNAL_ANSWER_CHUNK_CHARS` | 40 | 答案分片大小 |
| `EXTERNAL_ANSWER_CHUNK_DELAY_MILLIS` | 40 | 分片间隔（毫秒） |
| `EXTERNAL_SSE_ENABLED` | true | 关闭后组件不可用（`/embed/config` 会返回 `sseEnabled=false`） |

---

## 7. 联调与排错

```bash
# 1) 换令牌
curl -s -X POST 'http://127.0.0.1:8080/api/v1/external/embed/token?knowledgeBaseId=1' \
  -H 'X-API-Key: kos_ak_xxx' -H 'Origin: http://localhost:5173'

# 2) 明文看一遍 SSE（加 -N 禁用缓冲）
TOKEN=kos_et_v1...
curl -N -H "X-Embed-Token: $TOKEN" -H 'Origin: http://localhost:5173' \
  'http://127.0.0.1:8080/api/v1/external/stream/query?question=报销流程&topK=10'

# 3) 浏览器里打开演示页
#    http://<host>/embed/demo.html
```

| 现象 | 排查 |
|---|---|
| 控制台报 CORS 错误 | 检查请求是否带 `Origin`；key 的 `allowed_origins` 是否包含该站点；大小写与尾部斜杠会被归一化 |
| 401 凭证无效 | 令牌过期（默认 12h）或 API Key 被吊销；重新换令牌 |
| 重启后所有令牌失效 | 未配置 `EXTERNAL_EMBED_TOKEN_SECRET` 且家目录不可写，导致密钥每次启动重新生成；显式配置该变量即可 |
| 403 来源未授权 | `EmbedCorsFilter` 拦截，说明凭证与站点不匹配 |
| 429 | 触发了每分钟限流，调整 `rateLimitPerMinute` 或在业务侧做节流 |
| 无 `stage` 事件 | SSE 未启用，或被 nginx 缓冲；确认反代未开启 `proxy_buffering`（`nginx.conf` 已按 600s 读超时配置） |
| 阶段全部 0ms | AI 服务未返回 `trace.stages`（旧版本/降级路径），此时由 `fallbackStages` 给出总耗时 |

---

## 7b. 真流式实现（已落地，不是回放）

早期版本是"先算完再推送"：阶段数据是真的，但到达时刻是结束时刻。现在 pipeline 是边跑边报。

### 链路

```
组件 / 客户端
  └─ GET /api/v1/external/stream/query            （Java，SSE）
        └─ ExternalStreamService.streamLive()
              └─ KnowledgeQueryService.streamQuery()   ← 权限上下文、场景策略、落库、审计都在这里
                    └─ AiClient.postStream()           ← HttpURLConnection 逐帧读 SSE
                          └─ POST /ai/query/stream     （ai-service，StreamingResponse）
                                └─ QueryEngine.query(progress=…)  ← 每个阶段结束后 await progress
                                      └─ LlmProvider.stream_generate()  ← 逐 token
```

### 三个关键点

1. **引擎不改结构，只多一个回调**：`QueryEngine.query()` 新增可选 `progress` 参数，在
   `rewrite / route / recall / acl_filter / fusion / rerank / context / llm / citation` 九个阶段
   结束后 `await progress(stageEvent)`，生成阶段逐块 `await progress({"type":"delta"})`。
   不传 `progress` 时行为与原来完全一致 —— 同步路径零风险。
2. **Provider 可选实现流式**：`LlmProvider.stream_generate()` 基类默认回退为一次性 chunk
   （Mock），Ollama 走 NDJSON、OpenAI 兼容走 `data:` SSE。**约定：拼接后的文本必须等于 `generate()` 的结果**，
   否则答案门控评判的就不再是读者看到的那段文字。流式中断且已吐出内容时不重发（避免重复），
   第一个 chunk 就失败时才回退到阻塞生成。
3. **Java 侧不能用 RestTemplate**：它会在把 body 缓冲完才返回，所以 `AiClient.postStream()`
   直接用 `HttpURLConnection` 逐行读帧。注意两点：请求体必须用固定长度（同时设置
   `setChunkedStreamingMode` 会抛 `Chunked encoding streaming mode set`），且写完要 `close()`。

### 失败回退

`ExternalStreamService` 先试 `streamLive()`；任何失败都记 WARN 日志并落到 `replay()`
（调 `/ai/query` 一次性取回，再按真实阶段数据回放，done 事件带 `streamed:false`）。
客户端协议不变，只是"新鲜度"降级。

### 实测时序（本地，Ollama）

```
+36ms   open + 9 个 running 占位        ← 立刻画出流水线骨架
+41ms   rewrite done
+42ms   route done（PROCEDURE / card+bm25+vector）
+647ms  recall done（vector 642ms，40 条）
+…      fusion → rerank → context 陆续到达
+13.6s  首个 delta（模型开始写）
+14.6s  citation / done，共 290 个 delta（token 级）
```

改造前所有 done 阶段在 21.8s 一次性到达；现在检索过程是真的"看着它发生"。

---

## 8. 已知限制与后续演进

1. ~~首字节延迟~~（已解决，见 7b）。剩余：LLM 之前的阶段是"完成后即推"，仍不是"开始即推"；
   若要做到"rewrite 开始就推"，需要把每个阶段再拆成 start/finish 两个事件。
2. **限流是单机的**：`ExternalRateLimiter` 为内存滑动窗口，多副本需换成 Redis。
3. **无多轮上下文**：组件目前是单轮问答；如需多轮，可在组件内维护历史并在服务端增加会话参数。
4. **控制台可视化配置**：可在"对外服务"页面增加"嵌入代码生成器"（选知识库 → 生成 script 片段），复用 `/embed/token` 与 `/embed/config`。

---

## 9. 令牌生命周期：吊销与调用日志

每个令牌签发时带唯一 ID（`jti`），可被单独吊销；每次调用都按令牌与来源站点记录。

### 数据模型

- `t_api_call_audit.token_jti / origin` —— 补两个字段后，审计行能定位到具体令牌与站点。
- `t_embed_token_revocation` —— 吊销名单，`jti` 唯一索引。吊销不删旧调用，只影响后续请求。

### 校验顺序（`ExternalAccessService.authenticateEmbedToken`）

1. HMAC 签名（`EmbedTokenService.parse`），同时识别 v1 / v2。
2. 过期时间：`expiresAt != 0 && expiresAt <= now` 拒绝；v2 的 `expiresAt=0` 是长期令牌。
3. 令牌里的 origin 与请求 origin 匹配。
4. 吊销检查（`EmbedTokenLifecycleService.isRevoked`）：直接查表，不缓存，吊销必须立即生效。
5. API Key 的状态与来源白名单。
6. 把解析出的 token 写进 `request` 属性 → 后续审计用 `ExternalAccessService.embedTokenOf(request)` 取，**被拒的调用也带上 `jti`**，是这条特性里最关键的一处。

### 管理员端点（`/api/external/admin/embed/tokens*`）

- `GET /embed/tokens?hours=168&limit=50` —— 窗口内出现过的令牌、调用量、来源、是否已吊销。
- `GET /embed/tokens/calls?jti=&keyId=&limit=50` —— 按令牌或 Key 过滤的调用日志。
- `POST /embed/tokens/revoke` body `{jti, keyId, keyCode, knowledgeBaseId, origin, reason}`。
- `POST /embed/tokens/{jti}/restore` —— 撤销吊销。
- `GET /embed/tokens/revocations` —— 吊销名单。

### 兼容性

- **v1 旧令牌仍可验证通过**，但因没有 jti，吊销接口会拒绝（提示"v1 旧令牌没有 ID，请吊销其 API Key"）。
- 调用日志对 v1 令牌的 `token_jti` 为 `NULL`，可通过 `keyCode` / `keyId` 维度定位。

---

## 10. 结果结构、渲染与空结果

助手卡片的阅读顺序固定为四段：

| 段 | 内容 |
|---|---|
| 检索过程 | 9 个阶段与耗时；可折叠，生成中展开，结束自动收起并带上总耗时 |
| **答案** | 按"知识检索 · 搜索"面板（`QueryPanel.vue`）的答案排版渲染；尾部「引用原文」小节默认收起成一行（`引用原文 · N 条`），点开才铺开 |
| 元信息 | 结论来源、意图、置信度、召回通道、是否重排、门控状态、耗时、模型 |
| **引用来源** | 一排 `[N]` 标号，点击某条才在下方展开其原文与属性 |
| 操作 | 复制纯文本 / 复制 Markdown / 复制 HTML |

**与"知识检索 · 搜索"保持一致**，不再有这一层自造的交互：

- 答案排版沿用同一套 Markdown 视觉（标题 `md-heading`、行内代码 `md-code`、代码块 `md-pre`、引用上标 `md-cite`）；正文行高 1.8、字号 14px，标题 14.5px/600。
- 引用来源默认只给标号：一排 `[N]` 胶囊（比控制台的 `[N] 标题` 更短，把版面留给结论），`title` 上带出处 · 页 · 通道 · 相关度；点标号在下方展开该条详情（属性表 + 原文摘录），等价于控制台的 `open-citation` 抽屉，再点一次收起。
- 答案正文里的「引用原文」小节（服务端追加的那一节）默认折叠为 `引用原文 · N 条`，与「检索过程」同一个折叠形态；需要逐条核对原文时再展开。
- 元信息是方角小胶囊，语义色与 `kb-badge` 一致：结论来源 `info`、门控通过 `ok`、门控拦截 `danger`。
- 操作按钮是白底灰边小按钮，与 `kb-btn-outline` 同调。

### 按服务端返回的格式渲染

组件不再提供"Markdown / HTML 切换"，服务端返回什么就按什么渲染，与控制台一致：

| `answerFormat` | 渲染方式 |
|---|---|
| `markdown` | 内置渲染器渲染富文本 |
| `html` | 白名单清洗后渲染富文本 |
| `text` | 保留换行的纯文本 |

流式阶段：Markdown 源边收边渲染；HTML 源分块到达时标签是残缺的，先呈现去标签纯文本，`done` 时再整段落成富文本。

### 空结果

三种情况判定为「没有可展示的知识内容」：

1. 答案为空；
2. 答案以 `现有知识不足以` 开头（服务端的弃答话术，见 `answer_evidence_gate.safe_answer`）；
3. `answerQualification.gateStatus` 为 `BLOCKED` 或 `FAIL`。

此时答案区渲染为说明卡（浅灰底 + 左侧红边，与 `kb-state-danger` 同语义），而不是一段空文本：

> **现有知识不足以形成全部可验证的结论；未通过证据完整性与忠实度校验的内容已阻止输出。**
>
> - 已检索 3 个通道，未取得可验证的证据
> - 进入生成的上下文为空（`contextChars = 0`）
> - 质量门控：拦截
> - 阻断原因：结论缺少可精确匹配的证据，已整段阻止输出；缺少与原文精确匹配的证据
> - 置信度 21% · 耗时 2.6s
>
> 可尝试缩小问题范围，或换用更贴近文档表述的措辞。

复制按钮与元信息胶囊在这种情况下都不再出现；引用来源区由 `citation` 事件独立渲染，仍会保留一排标号供人工核对（点开看的是未通过校验的证据，不代表答案依据）。

标题只取服务端弃答话术的第一句：门控拦截时服务端会把弃答话术与「引用原文」小节拼在同一段 `answer` 里，整段当标题会把十几条原文铺在结论下面。

门控返回的 `decisionReasons` / `blockReasons` 是策略枚举（`ANSWER_EVIDENCE_GATE_BLOCKED`、`STRUCTURED_ANSWER_GATE_BLOCKED` 等），组件内映射为中文说明后再展示。

### Markdown 渲染支持

标题、有序/无序列表（按缩进嵌套）、任务列表、表格（含左/中/右对齐）、围栏代码块（语言标签 + 复制按钮）、引用块、分隔线、图片、删除线、行内代码、链接。

**两端同构**：控制台 `utils/markdown.ts` 与组件 `renderMarkdown()` 支持同一子集、产出同一套标签
（控制台侧同样支持表格 / 任务列表 / 围栏语言标签），差别只有组件多一个代码块「复制」按钮。
样式同源见[步骤 5](#步骤-5答案排版与控制台保持一致自动校验)，`npm run check:answer-style` 负责守住这条线。

### 引用锚点

`[证据N]` 与 `[N]` 都渲染成可点击上标（`md-cite`），**前提是该编号能在 citations 里命中**——否则正文里的普通序号不会误变成引用。

### HTML 安全

组件跑在第三方页面里，服务端返回的 HTML 一律按白名单清洗（控制台面板内是可信环境，直接 `v-html`；嵌入组件不行）：

- 标签：仅保留排版类（标题 / 列表 / 表格 / 代码 / 引用 / 图片 / 链接等）；`script`、`style`、`iframe`、`object`、`form`、`input`、`svg` 等整体删除；未在白名单的未知标签 unwrap（保留子内容文本）。
- 属性：仅保留 `class / id / title / alt / href / src / target / rel / colspan / rowspan / align / width / height / data-cite / data-index`；`on*` 与 `style` 一律剥离。
- URL：`href` / `src` 仅允许 `http(s)` / `mailto` / `#` / 相对路径，`javascript:`、`data:` 会被移除。
- 链接强制 `target="_blank" rel="noopener noreferrer"`。
- 服务端自带的 `.answer-citations` 段落按 `.md-answer .answer-citations` 渲染（与 chips 互为补充）。

### 相关选项

| 选项 | 属性 | 默认 | 作用 |
|---|---|---|---|
| `answerFormat` | `data-answer-format` | `markdown` | 请求并呈现的答案格式（`markdown` / `html` / `text`） |
| `showCitations` | `data-show-citations` | `true` | 引用来源区 |
| `showTrace` | `data-show-trace` | `true` | 检索过程折叠条 |
