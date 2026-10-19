<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">接口服务</div>
        <h1 class="kb-title mt-1">接口联调台</h1>
        <p class="kb-muted mt-1">
          用真实的 API Key 直接调用 RESTful / MCP / SSE 三个通道，逐条比对返回结构并给出判定：
          凭证是否有效、知识库范围是否生效、协议字段是否规范、流式事件顺序是否正确。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <button class="kb-btn-outline" @click="issueLabKey">签发临时测试凭证</button>
      </div>
    </header>

    <article class="kb-card">
      <div class="kb-card-head">
        <b class="text-sm">调用参数</b>
        <span class="kb-muted">三个通道共用同一套凭证、限流与审计</span>
      </div>
      <div class="kb-card-body grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
        <label class="block">
          <span class="kb-field-label">服务前缀</span>
          <input v-model="cfg.baseUrl" class="kb-input mt-1" placeholder="/api" />
        </label>
        <label class="block md:col-span-2">
          <span class="kb-field-label">API 密钥明文</span>
          <div class="mt-1 flex items-center gap-2">
            <input v-model="cfg.apiKey" :type="showKey ? 'text' : 'password'" class="kb-input" placeholder="kos_ak_..." />
            <button class="kb-btn-outline shrink-0" @click="showKey = !showKey">{{ showKey ? '隐藏' : '显示' }}</button>
          </div>
        </label>
        <label class="block">
          <span class="kb-field-label">鉴权方式</span>
          <select v-model="cfg.authMode" class="kb-input mt-1">
            <option value="header">X-API-Key 请求头</option>
            <option value="bearer">Authorization: Bearer</option>
            <option value="query">URL 参数 apiKey</option>
          </select>
        </label>
        <label class="block">
          <span class="kb-field-label">知识库 ID</span>
          <input v-model.number="cfg.knowledgeBaseId" type="number" class="kb-input mt-1" placeholder="留空则依赖凭证绑定" />
        </label>
        <label class="block xl:col-span-2">
          <span class="kb-field-label">问题</span>
          <input v-model="cfg.question" class="kb-input mt-1" placeholder="要提问的问题" />
        </label>
        <label class="block">
          <span class="kb-field-label">topK</span>
          <input v-model.number="cfg.topK" type="number" class="kb-input mt-1" />
        </label>
        <label class="block">
          <span class="kb-field-label">最低分数 minScore</span>
          <input v-model.number="cfg.minScore" type="number" step="0.05" min="0" max="1" class="kb-input mt-1"
            placeholder="留空不过滤（0~1）" />
        </label>
        <label class="block">
          <span class="kb-field-label">返回格式 answerFormat</span>
          <select v-model="cfg.answerFormat" class="kb-input mt-1">
            <option v-for="option in answerFormatOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
        <label class="block">
          <span class="kb-field-label">对象编码 code</span>
          <input v-model="cfg.code" class="kb-input mt-1" placeholder="卡片 / 断言 / 证据编码" />
        </label>
        <label class="block">
          <span class="kb-field-label">queryId（链路回放）</span>
          <input v-model="cfg.queryId" class="kb-input mt-1" placeholder="问答后自动回填" />
        </label>
      </div>
      <p v-if="!cfg.apiKey.trim()" class="border-t border-[color:var(--line-d)] px-4 py-2 text-[11px] text-[color:var(--warn)]">
        尚未填写 API 密钥：可点击右上角「签发临时测试凭证」自动生成一枚 READ 凭证，或粘贴已有明文密钥。
      </p>
    </article>

    <el-tabs v-model="tab" class="mt-4">
      <el-tab-pane label="RESTful" name="rest">
        <div class="mt-1 grid grid-cols-1 gap-4 xl:grid-cols-2">
          <article class="kb-card">
            <div class="kb-card-head">
              <b class="text-sm">单端点验证</b>
              <div class="flex items-center gap-2">
                <button class="kb-btn-outline" :disabled="sending" @click="resetBody">重置请求体</button>
                <button class="kb-btn-primary" :disabled="sending" @click="sendRest(restEndpoint)">发送</button>
              </div>
            </div>
            <div class="kb-card-body">
              <label class="block">
                <span class="kb-field-label">端点</span>
                <select v-model="restKey" class="kb-input mt-1">
                  <option v-for="item in REST_ENDPOINTS" :key="item.key" :value="item.key">
                    {{ item.method }} {{ item.path }} — {{ item.summary }}
                  </option>
                </select>
              </label>
              <p class="kb-muted mt-2">{{ restUrl(restEndpoint) }}</p>
              <p class="kb-muted mt-1 break-all text-[11px]">
                <b class="text-ink-700">参数：</b>{{ REST_PARAM_DOCS[restEndpoint.key] }}
              </p>
              <div v-if="restEndpoint.method === 'POST'" class="mt-2">
                <span class="kb-field-label">请求体</span>
                <textarea v-model="restBody" rows="8" class="kb-input mt-1 font-mono text-[11px] leading-5" />
              </div>
              <div class="mt-3 flex flex-wrap items-center gap-2">
                <button class="kb-btn-outline" :disabled="smoking" @click="smokeRest">只读端点冒烟</button>
                <button class="kb-btn-outline" :disabled="smoking" @click="authNegative">鉴权反向用例</button>
              </div>
              <p class="kb-muted mt-2">
                冒烟按顺序跑 bases → overview → search → query → cards → claims → 详情 → graph → trace，
                并把 queryId 与对象编码自动带入后续步骤。
              </p>
            </div>
          </article>

          <article v-if="smokeRows.length" class="kb-card">
            <div class="kb-card-head">
              <b class="text-sm">冒烟结果</b>
              <span class="kb-muted">
                通过 {{ smokeRows.filter(r => r.verdict === 'PASS').length }} / {{ smokeRows.length }}
              </span>
            </div>
            <div class="kb-card-body">
              <el-table :data="smokeRows" border size="small">
                <el-table-column prop="label" label="端点" min-width="220" show-overflow-tooltip />
                <el-table-column label="HTTP" width="90">
                  <template #default="{ row }">
                    <span :class="row.status >= 400 ? 'text-[color:var(--danger)]' : 'text-[color:var(--ok)]'">{{ row.status }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="耗时" width="90">
                  <template #default="{ row }">{{ row.durationMs }} ms</template>
                </el-table-column>
                <el-table-column label="判定" width="90">
                  <template #default="{ row }">
                    <el-tag size="small" effect="plain" :type="row.verdict === 'PASS' ? 'success' : 'danger'">
                      {{ row.verdict }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="note" label="结构校验" min-width="260" show-overflow-tooltip />
              </el-table>
            </div>
          </article>
        </div>
      </el-tab-pane>

      <el-tab-pane label="MCP" name="mcp">
        <div class="mt-1 grid grid-cols-1 gap-4 xl:grid-cols-2">
          <article class="kb-card">
            <div class="kb-card-head">
              <b class="text-sm">JSON-RPC 2.0 请求</b>
              <div class="flex items-center gap-2">
                <button class="kb-btn-outline" :disabled="mcpSending" @click="applyMcpTemplate">套用模板</button>
                <button class="kb-btn-primary" :disabled="mcpSending" @click="sendMcp">发送</button>
              </div>
            </div>
            <div class="kb-card-body">
              <label class="block">
                <span class="kb-field-label">模板</span>
                <select v-model="mcpKey" class="kb-input mt-1">
                  <option v-for="item in MCP_TEMPLATES" :key="item.key" :value="item.key">{{ item.label }}</option>
                </select>
              </label>
              <p class="kb-muted mt-2">POST {{ prefix() }}/mcp</p>
              <textarea v-model="mcpBody" rows="16" class="kb-input mt-2 font-mono text-[11px] leading-5" />
              <button class="kb-btn-outline mt-3" :disabled="handshaking" @click="mcpHandshake">握手自检</button>
              <p class="kb-muted mt-2">
                握手自检依次执行 initialize → tools/list → tools/call，校验 protocolVersion、工具清单与调用结果结构。
              </p>
            </div>
          </article>

          <article class="kb-card">
            <div class="kb-card-head">
              <b class="text-sm">响应与协议校验</b>
              <span v-if="mcpResult" class="kb-muted">{{ mcpResult.durationMs }} ms</span>
            </div>
            <div class="kb-card-body max-h-[560px] overflow-auto">
              <div v-if="!mcpResult" class="kb-muted">尚未发起调用。</div>
              <template v-else>
                <div v-for="item in mcpResult.checks" :key="item.label"
                  class="flex items-start gap-2 border-b border-[color:var(--line-d)] py-1.5 last:border-0">
                  <span class="text-[12px]" :class="item.pass ? 'text-[color:var(--ok)]' : 'text-[color:var(--danger)]'">
                    {{ item.pass ? '✓' : '✗' }}
                  </span>
                  <span class="min-w-0 flex-1 text-[11px] text-ink-700">
                  {{ item.label }}</span>
                  <span class="text-[11px] text-ink-500">{{ item.detail }}</span>
                </div>
                <pre
                  class="mt-2 max-h-72 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-ink-50 p-2 text-[11px] leading-5 text-ink-700">
                  {{ displayBody(mcpResult.body) }}</pre>
              </template>

              <div v-if="mcpTools.length" class="mt-3">
                <div class="kb-field-label">工具清单（{{ mcpTools.length }}）</div>
                <div v-for="tool in mcpTools" :key="tool.name" class="mt-2 rounded-lg border border-[color:var(--line-d)] px-3 py-2">
                  <b class="text-[12px] text-accent">{{ tool.name }}</b>
                  <div class="text-[11px] text-ink-500">{{ tool.description }}</div>
                  <div class="mt-1 text-[11px] text-ink-500">
                    必填：{{ (tool.required || []).join(', ') || '无' }}
                  </div>
                  <div class="text-[11px] text-ink-500">
                    可选：{{ (tool.optional || []).join(', ') || '无' }}
                  </div>
                </div>
              </div>

              <div v-if="mcpSteps.length" class="mt-3">
                <div class="kb-field-label">握手自检</div>
                <div v-for="step in mcpSteps" :key="step.label" class="mt-1 flex items-start gap-2">
                  <span class="text-[12px]" :class="step.pass ? 'text-[color:var(--ok)]' : 'text-[color:var(--danger)]'">
                    {{ step.pass ? '✓' : '✗' }}
                  </span>
                  <span class="min-w-0 flex-1 text-[11px] text-ink-700">
                  {{ step.label }}</span>
                  <span class="text-[11px] text-ink-500">{{ step.detail }}</span>
                </div>
              </div>
            </div>
          </article>
        </div>
      </el-tab-pane>

      <el-tab-pane label="SSE" name="sse">
        <div class="mt-1 grid grid-cols-1 gap-4 xl:grid-cols-2">
          <article class="kb-card">
            <div class="kb-card-head">
              <b class="text-sm">流式通道</b>
              <div class="flex items-center gap-2">
                <button v-if="!sseRunning" class="kb-btn-primary" @click="startSse">开始订阅</button>
                <button v-else class="kb-btn-danger" @click="stopSse">停止</button>
                <button class="kb-btn-outline" :disabled="sseRunning" @click="sseEvents = []">清空</button>
              </div>
            </div>
            <div class="kb-card-body">
              <label class="block">
                <span class="kb-field-label">流类型</span>
                <select v-model="sseKind" class="kb-input mt-1">
                  <option value="query">stream/query 流式问答</option>
                  <option value="events">stream/events 事件订阅</option>
                </select>
              </label>
              <label v-if="sseKind === 'query'" class="mt-2 block">
                <span class="kb-field-label">答案交付方式 content</span>
                <select v-model="cfg.content" class="kb-input mt-1">
                  <option value="stream">stream — 逐片推 delta（打字机）</option>
                  <option value="final">final — 只推检索过程，答案在 done 里整体交付</option>
                </select>
              </label>
              <p class="kb-muted mt-2 break-all">{{ sseUrl() }}</p>
              <div class="mt-3 grid grid-cols-2 gap-3 text-[11px] sm:grid-cols-4">
                <div><span class="text-ink-500">HTTP</span> <b class="text-ink-800">{{ sseStats.status || '—' }}</b></div>
                <div><span class="text-ink-500">首包</span> <b class="text-ink-800">{{ sseStats.firstByteMs || '—' }} ms</b></div>
                <div><span class="text-ink-500">事件数</span> <b class="text-ink-800">{{ sseEvents.length }}</b></div>
                <div><span class="text-ink-500">结束</span> <b class="text-ink-800">{{ sseStats.ended || '—' }}</b></div>
              </div>
              <div v-if="sseVerdict" class="mt-3">
                <div class="kb-field-label">事件序列校验</div>
                <div v-for="item in sseVerdict.items" :key="item.label" class="mt-1 flex items-start gap-2">
                  <span class="text-[12px]" :class="item.pass ? 'text-[color:var(--ok)]' : 'text-[color:var(--danger)]'">
                    {{ item.pass ? '✓' : '✗' }}
                  </span>
                  <span class="min-w-0 flex-1 text-[11px] text-ink-700">
                  {{ item.label }}</span>
                  <span class="text-[11px] text-ink-500">{{ item.detail }}</span>
                </div>
                <div class="mt-2 text-[12px]" :class="sseVerdict.pass ? 'text-[color:var(--ok)]' : 'text-[color:var(--danger)]'">
                  {{ sseVerdict.pass ? 'PASS：事件序列与契约一致' : 'FAIL：事件序列与契约不一致' }}
                </div>
              </div>
              <p class="kb-muted mt-3">
                stream/query 期望 stage(REWRITE→RECALL→FUSION→RERANK→ANSWER) → delta → citation → done；
                content=final 时不推 delta 帧——只推检索过程，答案在 done 里整体交付（与控制台同源）。
                done 帧带 answerFormat（实际生效的交付格式，与上方选择的取值不一致说明已退回默认）；
                stream/events 期望 open → ingestion / knowledge → 心跳注释。
              </p>
            </div>
          </article>

          <article class="kb-card">
            <div class="kb-card-head">
              <b class="text-sm">事件时间线</b>
              <span class="kb-muted">相对开始时间</span>
            </div>
            <div class="kb-card-body max-h-[560px] overflow-auto">
              <div v-if="!sseEvents.length" class="kb-muted">尚无事件。</div>
              <div v-for="event in sseEvents" :key="event.seq"
                class="border-b border-[color:var(--line-d)] py-1.5 last:border-0">
                <div class="flex cursor-pointer items-center gap-2" @click="toggleSse(event)">
                  <span class="w-8 text-[11px] text-ink-500">#{{ event.seq }}</span>
                  <span class="kb-badge" :class="event.name === 'error' ? 'kb-state-danger'
                    : event.name === 'comment' ? 'bg-ink-100 text-ink-600' : 'kb-state-info'">
                    {{ event.name }}
                  </span>
                  <span class="text-[11px] text-ink-500">t+{{ event.at }} ms</span>
                  <span class="min-w-0 flex-1 truncate text-right text-[11px] text-ink-500">
                    {{ isJsonBody(event.data) ? 'JSON' : '文本' }} · {{ bodySize(event.data) }}
                  </span>
                  <button class="kb-btn-ghost shrink-0 text-[11px]"
                    @click.stop="copy(prettyBody(event.data))">复制</button>
                </div>
                <pre
                  :class="event.open ? 'max-h-64 overflow-auto' : 'max-h-16 overflow-hidden'"
                  class="mt-1 whitespace-pre-wrap break-words rounded-lg bg-ink-50 p-2 text-[11px] leading-5 text-ink-700">
                  {{ displayBody(event.data) }}</pre>
              </div>
            </div>
          </article>
        </div>
      </el-tab-pane>

      <el-tab-pane label="事件总线" name="bus">
        <div class="mt-1 grid grid-cols-1 gap-4 xl:grid-cols-3">
          <article class="kb-card">
            <div class="kb-card-head">
              <b class="text-sm">订阅条件</b>
              <span class="kb-muted">登录会话</span>
            </div>
            <div class="kb-card-body">
              <span class="kb-field-label">知识库范围</span>
              <select v-model="busKb" class="kb-input mt-1 w-full">
                <option :value="null">全部知识库（管理员视角）</option>
                <option v-for="b in props.bases" :key="b.id" :value="b.id">{{ b.name }}</option>
              </select>

              <span class="kb-field-label mt-3 block">事件类型（不勾选＝全部）</span>
              <div class="mt-1 max-h-64 overflow-auto">
                <label v-for="t in busEventTypes" :key="t" class="flex items-center gap-2 py-0.5">
                  <input v-model="busSelectedTypes" type="checkbox" :value="t" />
                  <span class="text-[11px] text-ink-700">
                  {{ t }}</span>
                </label>
              </div>

              <span class="kb-field-label mt-3 block">断线续传起点</span>
              <input v-model="busSince" class="kb-input mt-1 w-full"
                placeholder="sinceId，留空＝从最新开始" />
              <div class="mt-2 text-[11px] text-ink-500">
                订阅后 NextId 会自动推进，可复制到此输入框验证续传。
              </div>
            </div>
          </article>

          <article class="kb-card xl:col-span-2">
            <div class="kb-card-head">
              <b class="text-sm">事件流</b>
              <div class="flex items-center gap-2">
                <button v-if="!busRunning" class="kb-btn-primary" @click="startBus">开始订阅</button>
                <button v-else class="kb-btn-danger" @click="stopBus">停止</button>
                <button class="kb-btn-outline" @click="loadBusHistory">加载历史</button>
                <span class="kb-muted">{{ busStatus }}</span>
              </div>
            </div>
            <div class="kb-card-body max-h-[560px] overflow-auto">
              <div v-if="!busEvents.length" class="kb-muted">
                尚无事件：执行「创建知识库 / 上传文档 / 发起问答 / 调整权限」后，事件会实时出现在此处。
              </div>
              <div v-for="e in busEvents" :key="e.key"
                class="border-b border-[color:var(--line-d)] py-1.5 last:border-0">
                <div class="flex cursor-pointer flex-wrap items-center gap-2" @click="e.open = !e.open">
                  <span class="kb-badge kb-state-info">{{ e.type }}</span>
                  <span class="text-[11px] text-ink-500">#{{ e.id }} · {{ e.time }}</span>
                  <span class="text-[11px] text-ink-500">{{ e.source }} · {{ e.actorName || 'system' }}</span>
                  <span class="min-w-0 flex-1 text-[11px] text-ink-700">
                  {{ e.summary }}</span>
                </div>
                <pre v-if="e.open"
                  class="mt-1 max-h-64 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-ink-50 p-2 text-[11px] leading-5 text-ink-700">
                  {{ previewBus(e) }}</pre>
              </div>
            </div>
          </article>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 四个通道共用一份记录：切到 MCP 复现同一个问题时，REST 那次的结果仍然在眼前。 -->
    <article class="kb-card mt-4">
      <div class="kb-card-head">
        <b class="text-sm">调用记录</b>
        <span class="kb-muted">本次会话 {{ logs.length }} 条 · 服务端全量记录在「调用日志」</span>
        <button class="kb-btn-outline" :disabled="!logs.length" @click="clearLogs()">清空</button>
      </div>
      <div class="kb-card-body max-h-[520px] overflow-auto">
        <div v-if="!logs.length" class="kb-muted">尚未发起调用。</div>
        <div v-for="log in logs" :key="log.id" class="border-b border-[color:var(--line-d)] py-2 last:border-0">
          <div class="flex flex-wrap items-center gap-2">
            <span class="kb-badge" :class="verdictClass(log.verdict)">
              {{ log.verdict === 'PENDING' ? '进行中' : log.verdict }}
            </span>
            <span class="kb-badge">{{ log.channel }}</span>
            <span class="text-[11px] font-semibold text-accent">{{ log.method }}</span>
            <span class="min-w-0 flex-1 truncate text-[11px] text-ink-700">
            {{ log.label }}</span>
            <span v-if="log.status" class="text-[11px]"
              :class="log.status >= 400 ? 'text-[color:var(--danger)]' : 'text-[color:var(--ok)]'">
              HTTP {{ log.status }}
            </span>
            <span v-else class="text-[11px] text-ink-500">等待响应</span>
            <span class="text-[11px] text-ink-500">{{ log.durationMs }} ms</span>
          </div>
          <div class="mt-1 text-[11px] text-ink-500">{{ log.note }}</div>
          <div class="mt-1 flex flex-wrap items-center gap-2">
            <button class="kb-btn-ghost text-[11px]" @click="toggleLogBody(log.id)">
              {{ logBodyOpen[log.id] ? '收起响应' : '展开响应' }}
            </button>
            <span class="text-[11px] text-ink-500">
              {{ isJsonBody(log.body) ? 'JSON' : '文本' }} · {{ bodySize(log.body) }}
            </span>
            <button class="kb-btn-ghost text-[11px]" @click="copy(prettyBody(log.body))">复制响应</button>
          </div>
          <pre
            :class="logBodyOpen[log.id] ? 'max-h-[420px]' : 'max-h-16'"
            class="mt-1 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-ink-50 p-2 text-[11px] leading-5 text-ink-700">
            {{ displayBody(log.body) }}</pre>
          <div class="mt-1 flex items-center gap-2">
            <span class="truncate text-[11px] text-ink-500">{{ log.url }}</span>
            <button v-if="log.curl" class="kb-btn-ghost shrink-0 text-[11px]" @click="copy(log.curl)">复制请求命令</button>
          </div>
        </div>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { useExternalServiceLab, type ExternalServiceLabProps } from './external-service/useExternalServiceLab'
// 记录区要格式化响应体，这三个函数模板里直接用；漏掉就会在渲染记录时抛“is not a function”，
// 表现是点完发送右侧毫无反应。
import { bodySize, displayBody, isJsonBody, prettyBody } from './external-service/lab-utils'

const props = defineProps<ExternalServiceLabProps>()
const {
  tab,
  showKey,
  cfg,
  logs,
  logBodyOpen,
  REST_ENDPOINTS,
  REST_PARAM_DOCS,
  restKey,
  restBody,
  sending,
  smoking,
  restEndpoint,
  smokeRows,
  MCP_TEMPLATES,
  mcpKey,
  mcpBody,
  mcpSending,
  handshaking,
  mcpResult,
  mcpTools,
  mcpSteps,
  sseKind,
  sseEvents,
  sseRunning,
  sseStats,
  busEventTypes,
  busSelectedTypes,
  busKb,
  busSince,
  busRunning,
  busStatus,
  busEvents,
  sseVerdict,
  prefix,
  authHeaders,
  withAuth,
  absolute,
  copy,
  curlOf,
  pushLog,
  clearLogs,
  toggleLogBody,
  restUrl,
  bodyTemplate,
  resetBody,
  callRest,
  judgeRest,
  sendRest,
  smokeRest,
  authNegative,
  applyMcpTemplate,
  rawMcp,
  judgeMcp,
  sendMcp,
  mcpHandshake,
  answerFormatOptions,
  sseUrl,
  pushSse,
  toggleSse,
  parseFrame,
  startSse,
  stopSse,
  sessionHeaders,
  busRequest,
  busParams,
  loadBusTypes,
  startBus,
  stopBus,
  handleBusFrame,
  pushBusEvent,
  loadBusHistory,
  previewBus,
  issueLabKey
} = useExternalServiceLab(props)

/** “进行中”不带成功/失败色：结果还没回来，先别定性。 */
const verdictClass = (verdict:string) => verdict === 'PASS' ? 'kb-state-ok'
  : verdict === 'FAIL' ? 'kb-state-warn'
    : verdict === 'ERROR' ? 'kb-state-danger'
      : ''
</script>
