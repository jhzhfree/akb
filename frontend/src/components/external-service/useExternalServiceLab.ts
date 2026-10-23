import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { ApiEnvelope, KnowledgeBase } from '../../types'
import { ANSWER_FORMATS } from '../../api/contracts'
import type { AnswerFormat, ResponseMode } from '../../api/contracts'
import { createExternalKey } from '../../api/external'
import { unwrap } from '../../api/knowledge'
// 格式化响应体的三个函数在组件里用（模板渲染），这里只留结构判定用的。
import { asArray, asRecord, safeJson } from './lab-utils'
import { getToken } from '../../api/auth'
import { errorMessage as message } from '../../utils/feedback'
import { copyText } from '../../utils/clipboard'

export interface ExternalServiceLabProps { bases: KnowledgeBase[]; protocol?: 'rest' | 'mcp' | 'sse' | 'bus' }

export function useExternalServiceLab(props: ExternalServiceLabProps) {
  /** protocol：知识集成按 Tab 直接进入对应协议的联调台（API → RESTful，SSE → SSE）。 */

  const tab = ref<'rest' | 'mcp' | 'sse' | 'bus'>(props.protocol || 'rest')
  watch(() => props.protocol, value => { if (value) tab.value = value })
  const showKey = ref(false)

  const cfg = reactive({
    baseUrl:'/api',
    apiKey:'',
    authMode:'header' as 'header' | 'bearer' | 'query',
    knowledgeBaseId:null as number | null,
    /** 留空：由当前知识库的「默认问题」回填（见下方 watch）。在这里写死一句，库里配好的
        问句在联调台上就永远用不上，控制台与接口发出的也就不是同一句话。 */
    question:'',
    topK:10,
    /** 最低相关度分数（0~1）：留空即不过滤，与不传这个参数完全一致。 */
    minScore:null as number | null,
    /** 答案交付格式：default / markdown / text / html，服务端回传的 answerFormat 是实际生效的那个。 */
    answerFormat:'default' as AnswerFormat,
    /** 返回详略：minimal / basic / full，默认 full（当前全部字段，不裁剪）。 */
    responseMode:'full' as ResponseMode,
    /** 流式答案交付方式：stream 逐片推 delta；final 只推检索过程，答案在 done 里整体交付。 */
    content:'stream' as 'stream' | 'final',
    code:'',
    queryId:'',
  })

  /** 未指定知识库时沿用控制台当前选择的库，避免默认请求直接报“缺少 knowledgeBaseId”。 */
  watch(() => props.bases, list => {
    if (cfg.knowledgeBaseId == null && list.length) cfg.knowledgeBaseId = list[0].id
  }, { immediate:true })

  /** 换库时把该库配的默认问题填进提问框：联调台与控制台发出的应当是同一句话，否则库里
      配好的问句在这里永远用不上，试出来的结果也就不能代表一次真实调用。 */
  watch(() => cfg.knowledgeBaseId, id => {
    const base = (props.bases || []).find(v => v.id === id)
    cfg.question = base?.defaultQuery || ''
  }, { immediate:true })

  /* ---------------- 通用：请求构造与结果判定 ---------------- */

  function prefix() {
    return String(cfg.baseUrl || '/api').replace(/\/+$/, '')
  }
  function authHeaders():Record<string, string> {
    const headers:Record<string, string> = { 'Content-Type':'application/json' }
    const key = cfg.apiKey.trim()
    if (!key) return headers
    if (cfg.authMode === 'header') headers['X-API-Key'] = key
    else if (cfg.authMode === 'bearer') headers['Authorization'] = `Bearer ${key}`
    return headers
  }
  function withAuth(url:string) {
    const key = cfg.apiKey.trim()
    if (cfg.authMode !== 'query' || !key) return url
    return url + (url.includes('?') ? '&' : '?') + 'apiKey=' + encodeURIComponent(key)
  }
  function absolute(url:string) {
    return /^https?:\/\//.test(url) ? url : window.location.origin + url
  }
  async function copy(text:string) {
    // 非安全上下文（http + 内网 IP）下 navigator.clipboard 不存在，直接调会在取值处
    // 同步抛错、catch 接不到，表现就是点了没反应；copyText 内部带兜底路径。
    const ok = await copyText(text)
    if (ok) ElMessage.success('已复制到剪贴板')
    else ElMessage.warning('浏览器拒绝了剪贴板访问，请手动复制')
  }
  function curlOf(method:string, url:string, body?:string) {
    const lines = [`curl -X ${method} '${absolute(url)}'`]
    for (const [name, value] of Object.entries(authHeaders())) lines.push(`  -H '${name}: ${value}'`)
    if (body) lines.push(`  -d '${body.replace(/\s+/g, ' ')}'`)
    return lines.join(' \\\n')
  }


  /** 调用记录对四个通道共用：一次联调往往是 REST 发过了再换 MCP 复现同一件事，
   *  记录散在各个 Tab 里就拼不出完整的排查过程。 */
  type Channel = 'REST' | 'MCP' | 'SSE' | 'BUS'
  interface CallLog {
    id:number; channel:Channel; label:string; method:string; url:string; status:number; durationMs:number
    verdict:'PENDING' | 'PASS' | 'FAIL' | 'ERROR'; note:string; body:string; curl:string
  }
  const logs = ref<CallLog[]>([])
  const logBodyOpen = ref<Record<number, boolean>>({})
  let logSeq = 0
  function pushLog(log:Omit<CallLog, 'id'>) {
    const id = ++logSeq
    logs.value.unshift({ ...log, id })
    if (logs.value.length > 40) logs.value.length = 40
    return id
  }
  /** 先落一条“进行中”再回填结果：AI 检索慢的时候，空白等待最容易被当成没反应。 */
  function settleLog(id:number, patch:Partial<Omit<CallLog, 'id'>>) {
    const row = logs.value.find(item => item.id === id)
    if (!row) return
    Object.assign(row, patch)
  }
  function clearLogs() {
    logs.value = []
    logBodyOpen.value = {}
  }

  /** Responses arrive as one dense line; indenting them is what makes a failure readable. */
  function toggleLogBody(id:number) {
    logBodyOpen.value[id] = !logBodyOpen.value[id]
  }

  /* ---------------- RESTful ---------------- */

  interface RestEndpoint { key:string; method:'GET' | 'POST' | 'DELETE'; path:string; summary:string }
  const REST_ENDPOINTS:RestEndpoint[] = [
    { key:'bases', method:'GET', path:'/v1/external/bases', summary:'可见知识库列表' },
    { key:'overview', method:'GET', path:'/v1/external/overview', summary:'知识库概览计数' },
    { key:'query', method:'POST', path:'/v1/external/query', summary:'可追溯问答' },
    { key:'search', method:'POST', path:'/v1/external/search', summary:'检索（不生成答案）' },
    { key:'retrievals', method:'POST', path:'/v1/external/kb/retrievals', summary:'第三方检索协议（chunks[] + 可选 answer）' },
    { key:'cards', method:'GET', path:'/v1/external/cards', summary:'知识卡片清单' },
    { key:'card', method:'GET', path:'/v1/external/cards/{code}', summary:'知识卡片详情' },
    { key:'claims', method:'GET', path:'/v1/external/claims', summary:'断言清单' },
    { key:'claim', method:'GET', path:'/v1/external/claims/{code}', summary:'断言详情' },
    { key:'evidence', method:'GET', path:'/v1/external/evidence/{code}', summary:'证据原文' },
    { key:'graph', method:'GET', path:'/v1/external/graph/{code}', summary:'图谱邻域' },
    { key:'trace', method:'GET', path:'/v1/external/queries/{queryId}', summary:'问答链路回放' },
    { key:'ingest', method:'POST', path:'/v1/external/documents/text', summary:'文本接入（INGEST）' },
    { key:'memoryCapture', method:'POST', path:'/v1/external/memories/capture', summary:'写入 Agent 记忆（MEMORY_WRITE）' },
    { key:'memoryRecall', method:'POST', path:'/v1/external/memories/recall', summary:'召回 Agent 记忆（MEMORY_READ）' },
    { key:'memoryForget', method:'DELETE', path:'/v1/external/memories/{code}', summary:'删除一条记忆（MEMORY_WRITE）' },
  ]

  /**
   * 每个端点实际接受的参数。联调台按下发的字段为准，说明与请求体模板保持一致——
   * 否则"看到的参数"和"发出去的参数"会各说一套，调不通时最难排查的就是这个。
   */
  const REST_PARAM_DOCS:Record<string, string> = {
    bases:'无参数；knowledgeBaseId 由凭证绑定解析',
    overview:'knowledgeBaseId（可选）',
    query: 'knowledgeBaseId（可选）· question（必填）· topK（1–50，默认 10）· '
      + 'permissionTags[]（可选）· answerFormat（default / markdown / text / html，默认 default）· '
      + 'minScore（0~1，可选，最低相关度）· '
      + 'responseMode（minimal / basic / full，默认 full：minimal 只给答案与数量口径，basic 补回 candidates 与 selectedContext）',
    search:'同 /query（answerFormat 与 responseMode 都对返回生效：minimal 只给证据，basic 补回 candidates）· '
      + 'minScore（0~1，可选）过滤候选，返回的 returned 是过滤后条数',
    // 协议的库标识是请求头 X-Knowledge-Base-Id（数字 id 或库编码）；联调台不带自定义头，
    // 所以模板里用 body 的 knowledgeBaseId——服务端两个入口都认，效果一样。
    retrievals:'X-Knowledge-Base-Id 头（必填，数字 id 或库编码；联调台可用 body 的 knowledgeBaseId）· '
      + 'question（必填）· topK（1–50，默认 10）· '
      + 'includeAnswer（默认 false：只召回，约 0.4s；true 时额外返回 answer，约 6s）',
    cards:'knowledgeBaseId（可选）',
    card:'code（路径，必填）· knowledgeBaseId（可选）',
    claims:'knowledgeBaseId（可选）',
    claim:'code（路径，必填）· knowledgeBaseId（可选）',
    evidence:'code（路径，必填）· knowledgeBaseId（可选）',
    graph:'code（路径，必填）· knowledgeBaseId（可选）· depth（默认 2）· limit（默认 50）',
    trace:'queryId（路径，必填）',
    ingest:'knowledgeBaseId（可选）· title（可选）· text（必填）· permissionTags[]（可选）',
    // 记忆接口只认服务端 API Key：嵌入令牌会被明确拒绝（它不该能读写记忆库）。
    memoryCapture:'memoryLevel（L0 / L1 / L2 / L3，必填）· scopeType（AGENT / TEAM / PROJECT / TASK / WORKFLOW / SESSION，必填）· '
      + 'content（必填，≤4000 字符）· tags[] · sourceType · sourceRef · idempotencyKey（重试用，避免重复写入）· '
      + 'confidence（0~1，默认 0.7）· importance（1~5，默认 3）· expiresInDays（1~3650，默认按级别）· '
      + 'scopeCode（非 AGENT 作用域必填，且该 Key 需先获准访问）',
    memoryRecall:'query（必填，≤300 字符）· scopeType（默认 AGENT）· scopeCode · limit（默认 10，最多 50）。'
      + '召回是子串匹配：查不到时试试更短的词',
    memoryForget:'code（路径，必填）：填入上方「对象编码」，留空会打到列表端点上',
  }

  const restKey = ref('query')
  const restBody = ref('')
  const sending = ref(false)
  const smoking = ref(false)

  const restEndpoint = computed<RestEndpoint>(() =>
    REST_ENDPOINTS.find(item => item.key === restKey.value) ?? REST_ENDPOINTS[0])

  function restUrl(endpoint:RestEndpoint) {
    let path = endpoint.path
      .replace('{code}', encodeURIComponent(cfg.code.trim()))
      .replace('{queryId}', encodeURIComponent(cfg.queryId.trim()))
    const query:string[] = []
    if (endpoint.method === 'GET' && cfg.knowledgeBaseId != null
      && endpoint.key !== 'bases' && endpoint.key !== 'trace') {
      query.push(`knowledgeBaseId=${cfg.knowledgeBaseId}`)
    }
    if (endpoint.key === 'graph') query.push('depth=2', 'limit=50')
    if (query.length) path += `?${query.join('&')}`
    return prefix() + path
  }

  function bodyTemplate(endpoint:RestEndpoint) {
    if (endpoint.key === 'query' || endpoint.key === 'search') {
      const body:Record<string, unknown> = {
        knowledgeBaseId:cfg.knowledgeBaseId,
        question:cfg.question,
        topK:cfg.topK,
      }
      // 只在填了阈值时带上：留空与服务端“未传”是同一回事，不该顺手下发一个 0。
      // Number.isFinite 同时挡住空输入框留下的空串。
      if (Number.isFinite(cfg.minScore)) body.minScore = cfg.minScore
      // 返回详略显式写进模板：默认 full 与不传等价，但写出来调用方才知道有这个开关。
      body.responseMode = cfg.responseMode
      return JSON.stringify(body, null, 2)
    }
    if (endpoint.key === 'retrievals') {
      return JSON.stringify({
        knowledgeBaseId:cfg.knowledgeBaseId,
        question:cfg.question,
        topK:cfg.topK,
        // 显式写 false 而不是省略：默认值写出来调用方才知道有这个开关，
        // 而"默认只召回"正是这个接口能在 5s 内返回的原因。
        includeAnswer:false,
      }, null, 2)
    }
    if (endpoint.key === 'memoryCapture') {
      return JSON.stringify({
        memoryLevel:'L1',
        scopeType:'AGENT',
        content:'用户偏好：报销说明必须给出金额上限与审批层级',
        tags:['偏好', '报销'],
        sourceType:'MANUAL',
        // 幂等键每次生成新的：联调台是手工点的，同一份模板连点两次应当写入两条，
        // 而不是被幂等挡掉一条让人以为没生效。
        idempotencyKey:'lab-' + Date.now(),
        importance:3,
      }, null, 2)
    }
    if (endpoint.key === 'memoryRecall') {
      return JSON.stringify({ query:cfg.question, scopeType:'AGENT', limit:5 }, null, 2)
    }
    if (endpoint.key === 'ingest') {
      return JSON.stringify({
        knowledgeBaseId:cfg.knowledgeBaseId,
        title:'联调台接入测试',
        text:'联调台写入的一段测试文本：报销需在费用发生后 30 天内提交，超过期限需补充说明。',
        permissionTags:[],
      }, null, 2)
    }
    return ''
  }

  function resetBody() {
    restBody.value = bodyTemplate(restEndpoint.value)
  }
  watch(restEndpoint, endpoint => { restBody.value = bodyTemplate(endpoint) }, { immediate:true })
  // 阈值改了要重填请求体，否则手改的 body 里还留着上一次的取值。
  watch(() => cfg.minScore, () => {
    if (restEndpoint.value.key === 'query' || restEndpoint.value.key === 'search') resetBody()
  })

  interface RawCall { status:number; durationMs:number; text:string; json:unknown; url:string }

  /**
   * 联调台是手工点击的，等一个没有超时的请求会把页面钉死在“发送中”。
   * 宁可清楚地报“30 秒未返回”，也不要让人去猜是卡住了还是失败了。
   */
  const REQUEST_TIMEOUT_MS = 30_000
  async function fetchWithTimeout(url:string, init:RequestInit, timeoutMs = REQUEST_TIMEOUT_MS) {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), timeoutMs)
    try {
      return await fetch(url, { ...init, signal:controller.signal })
    } finally {
      clearTimeout(timer)
    }
  }
  /** abort 在浏览器里只报一个空泛的 AbortError，换成调用方看得懂的话。 */
  function callError(e:unknown) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      return `请求超时：${REQUEST_TIMEOUT_MS / 1000} 秒内没有响应`
    }
    return message(e)
  }

  async function callRest(endpoint:RestEndpoint, body?:string, headers?:Record<string, string>) {
    const url = withAuth(restUrl(endpoint))
    const init:RequestInit = { method:endpoint.method, headers:headers ?? authHeaders() }
    if (endpoint.method === 'POST') init.body = body ?? restBody.value ?? '{}'
    const started = performance.now()
    const response = await fetchWithTimeout(url, init)
    const text = await response.text()
    return {
      status:response.status,
      durationMs:Math.round(performance.now() - started),
      text, json:safeJson(text), url,
    } as RawCall
  }

  /** 结构断言：HTTP 只是及格线，data 的形状才决定调用方能否正确解析。 */
  function judgeRest(endpoint:RestEndpoint, call:RawCall, expect?:number) {
    if (expect) {
      const pass = call.status === expect
      return {
        verdict:(pass ? 'PASS' : 'FAIL') as 'PASS' | 'FAIL',
        note:pass ? `按预期返回 ${call.status}` : `期望 ${expect}，实际 ${call.status}`,
      }
    }
    if (call.status !== 200) {
      // 403 在联调台里几乎总是权限范围不够（接入文本要 INGEST），而不是服务端故障；
      // 说清楚是哪一种，省得拿一枚只读凭证反复重试写入接口。
      const hint = call.status === 403 ? ' — 凭证缺少该接口所需的权限范围（接入文本需要 INGEST）' : ''
      return { verdict:'FAIL' as const, note:`HTTP ${call.status}：${asRecord(call.json).message || call.text.slice(0, 120)}${hint}` }
    }
    // 协议接口的响应不包 ApiResponse：成功就是一个裸的 {chunks:[...]}，
    // 判据是"有没有 chunks 数组"，不是 success 标志。
    if (endpoint.key === 'retrievals') {
      const chunks = asArray(asRecord(call.json).chunks)
      return { verdict:'PASS' as const, note:`chunks ${chunks.length} 条` }
    }
    const envelope = asRecord(call.json)
    if (envelope.success !== true) {
      return { verdict:'FAIL' as const, note:`success=false，code=${envelope.code || '—'}` }
    }
    const data = envelope.data
    const rows = asArray(data)
    /**
     * `effectiveTopK` 是本服务对请求 topK 夹取后的结果（1~50，不传或 <1 取 10）。请求值被夹时
     * 必须说出来：否则"我要 200 条、回来 50 条"看起来跟"库里就 50 条"一样。
     *
     * 注意别去读 `topK`：那是 AI 服务自己的字段，语义是"选中送入答案生成的上下文条数"，
     * 一次 topK=3 的请求它会返回 1，拿它跟请求值比对只会得出错误结论。
     */
    const topKNote = (row:Record<string, unknown>) => {
      const effective = row.effectiveTopK == null ? null : Number(row.effectiveTopK)
      if (effective == null || !Number.isFinite(effective) || effective === cfg.topK) return ''
      return `，topK 请求 ${cfg.topK} 未生效：服务端夹为 ${effective}（范围 1~50，不传或 <1 取 10）`
    }
    switch (endpoint.key) {
      case 'bases':
        return { verdict:'PASS' as const, note:`返回 ${rows.length} 个可见知识库` }
      case 'overview':
        return { verdict:'PASS' as const, note:`cardCount=${asRecord(data).cardCount} claimCount=${asRecord(data).claimCount}` }
      case 'query': {
        const row = asRecord(data)
        const ok = !!row.queryId && !!String(row.answer || '').trim()
        if (row.queryId) cfg.queryId = String(row.queryId)
        // 实际生效的格式与请求不一致，说明取值没被接受（服务端静默退回 default）。
        const effective = String(row.answerFormat || 'default')
        const downgraded = effective !== cfg.answerFormat
        return {
          verdict:(ok ? 'PASS' : 'FAIL') as 'PASS' | 'FAIL',
          note:ok ? `queryId=${row.queryId}，答案 ${String(row.answer).length} 字，引用 ${asArray(row.citations).length} 条`
            + `，交付格式 ${effective}${downgraded ? `（请求 ${cfg.answerFormat} 未生效，已退回默认）` : ''}`
            + topKNote(row)
            : '缺少 queryId 或 answer',
        }
      }
      case 'search': {
        const row = asRecord(data)
        const candidates = asArray(row.candidates ?? row.results ?? row.items)
        // `effectiveTopK` 是请求 topK 夹取后真正生效的召回上限（1~50，不传或 <1 取 10）。
        // 不用 `topK`——那是 AI 服务的"选中上下文条数"，语义不同（topK=3 的请求它返回 1）。
        const effective = row.effectiveTopK == null ? null : Number(row.effectiveTopK)
        const asked = effective == null ? cfg.topK : effective
        const returned = row.returned == null ? candidates.length : Number(row.returned)
        const floor0 = row.minScore
        const floor = floor0 == null ? '' : `，分数 ≥ ${floor0}`
        // 给了阈值且返回条数少于 topK，说明是阈值筛掉的，不是库里没有 —— 这两件事
        // 对调阈值的人意义完全不同，判定里必须说出来。
        const filtered = floor0 != null && returned < asked
        return {
          verdict:'PASS' as const,
          note:`候选 ${candidates.length} 条（topK=${asked}${floor}），引用 ${asArray(row.citations).length} 条`
            + (filtered ? `：${asked - returned} 条低于阈值被筛掉` : '')
            + topKNote(row),
        }
      }
      case 'cards':
      case 'claims':
        return { verdict:'PASS' as const, note:`返回 ${rows.length} 条` }
      case 'card':
      case 'claim':
      case 'evidence': {
        // 详情端点返回对象，但编码为空时请求会落到列表端点上、拿回一个数组。
        // 两种形状都要能读，否则“填错了编码”会被显示成“接口没返回东西”。
        const rows = Array.isArray(data) ? asArray(data) : []
        const row = Array.isArray(data) ? asRecord(rows[0]) : asRecord(data)
        const fields = Object.keys(row)
        return {
          verdict:(fields.length ? 'PASS' : 'FAIL') as 'PASS' | 'FAIL',
          note:`code=${cfg.code || '—'}，字段 ${fields.join(', ') || '空'}`,
        }
      }
      case 'graph': {
        const row = asRecord(data)
        const nodes = asArray(row.nodes)
        return { verdict:'PASS' as const, note:`节点 ${nodes.length}，关系 ${asArray(row.relationships ?? row.edges).length}` }
      }
      case 'trace':
        return {
          verdict:(Object.keys(asRecord(data)).length ? 'PASS' : 'FAIL') as 'PASS' | 'FAIL',
          note:`queryId=${cfg.queryId || '—'}，字段 ${Object.keys(asRecord(data)).join(', ') || '空'}`,
        }
      default:
        return { verdict:'PASS' as const, note:`data=${String(asRecord(data).sourceId || asRecord(data).runCode || '已受理')}` }
    }
  }

  /**
   * 详情类端点必须有编码。空编码发出的请求会落到列表端点上（/cards/ 命中的是 /cards），
   * 拿到一个数组，看起来就像“接口没返回成功”——所以发送前先补一个真实编码。
   */
  async function ensureCode(endpoint:RestEndpoint) {
    if (cfg.code.trim() || !['card', 'claim', 'evidence', 'graph'].includes(endpoint.key)) return
    const find = (key:string) => REST_ENDPOINTS.find(item => item.key === key) ?? null
    try {
      if (endpoint.key === 'claim') {
        const call = await callRest(find('claims')!)
        const first = asRecord(asArray(asRecord(call.json).data)[0])
        cfg.code = String(first.code || first.claimCode || '')
        return
      }
      const cards = await callRest(find('cards')!)
      const firstCard = asRecord(asArray(asRecord(cards.json).data)[0])
      const cardCode = String(firstCard.code || firstCard.knowledgeCode || '')
      if (endpoint.key !== 'evidence') {
        cfg.code = cardCode
        return
      }
      // 证据编码要从某张卡片的证据链里取：先拿到卡片，再读它的第一条证据。
      cfg.code = cardCode
      const detail = await callRest(find('card')!)
      const body = asRecord(asRecord(detail.json).data)
      const evidences = asArray(body.evidence ?? body.evidences)
      cfg.code = String(asRecord(evidences[0]).code || asRecord(evidences[0]).evidenceCode || '')
    } catch {
      // 取不到就照原样发：让响应自己说话，比在这里猜一个错误原因更诚实。
    }
  }

  async function sendRest(endpoint:RestEndpoint | null, expect?:number) {
    if (!endpoint) return
    sending.value = true
    const label = `${endpoint.method} ${endpoint.path} — ${endpoint.summary}`
    // 立刻落一条记录：拿到响应前的等待也要看得见，否则慢接口和“点了没反应”无法区分。
    const id = pushLog({
      channel:'REST', label, method:endpoint.method, url:restUrl(endpoint), status:0, durationMs:0,
      verdict:'PENDING', note:'请求已发出，等待响应…', body:'',
      curl:curlOf(endpoint.method, restUrl(endpoint), endpoint.method === 'POST' ? restBody.value : ''),
    })
    try {
      await ensureCode(endpoint)
      // 详情端点的编码可能刚被自动补上，URL 与 curl 要按补完后的样子更新。
      settleLog(id, {
        url:restUrl(endpoint),
        curl:curlOf(endpoint.method, restUrl(endpoint), endpoint.method === 'POST' ? restBody.value : ''),
      })
      const call = await callRest(endpoint)
      const judged = judgeRest(endpoint, call, expect)
      settleLog(id, {
        url:call.url, status:call.status, durationMs:call.durationMs,
        verdict:judged.verdict, note:judged.note, body:call.text,
      })
    } catch (e) {
      const text = callError(e)
      settleLog(id, { verdict:'ERROR', note:text, body:text })
    } finally {
      sending.value = false
    }
  }

  interface SmokeRow { label:string; status:number; durationMs:number; verdict:'PASS' | 'FAIL' | 'ERROR'; note:string }
  const smokeRows = ref<SmokeRow[]>([])

  async function smokeRest() {
    smoking.value = true
    smokeRows.value = []
    const endpointOf = (key:string) => REST_ENDPOINTS.find(item => item.key === key) ?? null
    const step = async (key:string, body?:string) => {
      const endpoint = endpointOf(key)
      if (!endpoint) return null
      try {
        const call = await callRest(endpoint, body)
        const judged = judgeRest(endpoint, call)
        smokeRows.value.push({
          label:`${endpoint.method} ${endpoint.path}`, status:call.status,
          durationMs:call.durationMs, verdict:judged.verdict, note:judged.note,
        })
        return call.json
      } catch (e) {
        smokeRows.value.push({
          label:`${endpoint.method} ${endpoint.path}`, status:0, durationMs:0,
          verdict:'ERROR', note:message(e),
        })
        return null
      }
    }

    // 顺序执行：后面的步骤依赖前面拿到的 queryId 与对象编码。
    await step('bases')
    await step('overview')
    const body = (extra:Record<string, unknown>) => JSON.stringify({
      knowledgeBaseId:cfg.knowledgeBaseId, question:cfg.question, topK:cfg.topK, ...extra })
    await step('search', body(Number.isFinite(cfg.minScore) ? { minScore:cfg.minScore } : {}))
    await step('query', body(Number.isFinite(cfg.minScore) ? { minScore:cfg.minScore } : {}))
    // 协议接口一并纳入冒烟：它是给第三方 Agent 平台用的，联调台里不跑就只有对接那天才被发现。
    await step('retrievals', body({}))
    const cards = await step('cards')
    const firstCard = asRecord(asArray(asRecord(cards).data)[0])
    // 列表里的编码字段过去随对象类型而变（knowledgeCode / claimCode / evidenceCode），
    // 现在统一给了 code，但仍保留旧字段的回退：联调台不该因为服务端换了个版本就取不到编码。
    const cardCode = String(firstCard.code || firstCard.knowledgeCode || '')
    if (cardCode) {
      cfg.code = cardCode
      const detail = await step('card')
      const detailBody = asRecord(asRecord(detail).data)
      const evidences = asArray(detailBody.evidence ?? detailBody.evidences)
      const evidenceCode = String(asRecord(evidences[0]).code || asRecord(evidences[0]).evidenceCode || '')
      if (evidenceCode) {
        cfg.code = evidenceCode
        await step('evidence')
      }
      cfg.code = cardCode
      await step('graph')
    }
    const claims = await step('claims')
    const firstClaim = asRecord(asArray(asRecord(claims).data)[0])
    const claimCode = String(firstClaim.code || firstClaim.claimCode || '')
    if (claimCode) {
      cfg.code = claimCode
      await step('claim')
    }
    if (cfg.queryId) await step('trace')
    smoking.value = false
  }

  /** 反向用例：确认“没有凭证 / 凭证错误”被挡在门外，而不是被当成服务故障。 */
  async function authNegative() {
    smoking.value = true
    try {
      const endpoint = REST_ENDPOINTS[0]
      const url = withAuth(restUrl(endpoint))
      const noKey = await fetch(url, { method:'GET', headers:{ 'Content-Type':'application/json' } })
      smokeRows.value.push({
        label:'GET /v1/external/bases — 不带凭证', status:noKey.status, durationMs:0,
        verdict:noKey.status === 401 ? 'PASS' : 'FAIL', note:`期望 401，实际 ${noKey.status}`,
      })
      const badKey = await fetch(url, {
        method:'GET', headers:{ 'Content-Type':'application/json', 'X-API-Key':'kos_ak_invalid_key_for_lab' },
      })
      smokeRows.value.push({
        label:'GET /v1/external/bases — 无效凭证', status:badKey.status, durationMs:0,
        verdict:badKey.status === 401 ? 'PASS' : 'FAIL', note:`期望 401，实际 ${badKey.status}`,
      })
    } catch (e) {
      ElMessage.error(message(e))
    } finally {
      smoking.value = false
    }
  }

  /* ---------------- MCP ---------------- */

  interface McpTemplate { key:string; label:string; body:() => unknown }
  const MCP_TEMPLATES:McpTemplate[] = [
    { key:'initialize', label:'initialize 握手', body:() => ({ jsonrpc:'2.0', id:1, method:'initialize', params:{} }) },
    { key:'toolsList', label:'tools/list 工具清单', body:() => ({ jsonrpc:'2.0', id:2, method:'tools/list', params:{} }) },
    {
      key:'callQuery', label:'tools/call knowledge_query',
      body:() => ({ jsonrpc:'2.0', id:3, method:'tools/call', params:{
        name:'knowledge_query', arguments:{
          knowledgeBaseId:cfg.knowledgeBaseId ?? 1, question:cfg.question, topK:cfg.topK, answerFormat:cfg.answerFormat,
          ...(Number.isFinite(cfg.minScore) ? { minScore:cfg.minScore } : {}),
        },
      } }),
    },
    {
      key:'callSearch', label:'tools/call knowledge_search',
      body:() => ({ jsonrpc:'2.0', id:4, method:'tools/call', params:{
        name:'knowledge_search', arguments:{
          knowledgeBaseId:cfg.knowledgeBaseId ?? 1, query:cfg.question, topK:cfg.topK,
          ...(Number.isFinite(cfg.minScore) ? { minScore:cfg.minScore } : {}),
        },
      } }),
    },
    {
      key:'callBases', label:'tools/call list_knowledge_bases',
      body:() => ({ jsonrpc:'2.0', id:5, method:'tools/call', params:{ name:'list_knowledge_bases', arguments:{} } }),
    },
    {
      key:'callCards', label:'tools/call list_knowledge_cards',
      body:() => ({ jsonrpc:'2.0', id:6, method:'tools/call', params:{
        name:'list_knowledge_cards', arguments:{ knowledgeBaseId:cfg.knowledgeBaseId ?? 1 },
      } }),
    },
    {
      key:'callCard', label:'tools/call get_knowledge_card',
      body:() => ({ jsonrpc:'2.0', id:7, method:'tools/call', params:{
        name:'get_knowledge_card', arguments:{ knowledgeBaseId:cfg.knowledgeBaseId ?? 1, code:cfg.code || 'CARD-DEMO' },
      } }),
    },
    {
      key:'callClaims', label:'tools/call list_claims',
      body:() => ({ jsonrpc:'2.0', id:8, method:'tools/call', params:{
        name:'list_claims', arguments:{ knowledgeBaseId:cfg.knowledgeBaseId ?? 1 },
      } }),
    },
    {
      key:'callGraph', label:'tools/call graph_neighbors',
      body:() => ({ jsonrpc:'2.0', id:9, method:'tools/call', params:{
        name:'graph_neighbors', arguments:{ knowledgeBaseId:cfg.knowledgeBaseId ?? 1, code:cfg.code || 'CARD-DEMO', depth:2, limit:50 },
      } }),
    },
    {
      key:'callIngest', label:'tools/call ingest_text（需 INGEST 权限）',
      body:() => ({ jsonrpc:'2.0', id:10, method:'tools/call', params:{
        name:'ingest_text', arguments:{
          knowledgeBaseId:cfg.knowledgeBaseId ?? 1, title:'联调台 MCP 接入', text:'这是一段来自 MCP 的测试文本。',
        },
      } }),
    },
    { key:'resourcesList', label:'resources/list', body:() => ({ jsonrpc:'2.0', id:11, method:'resources/list', params:{} }) },
    {
      key:'resourcesRead', label:'resources/read',
      body:() => ({ jsonrpc:'2.0', id:12, method:'resources/read', params:{
        uri:`knowledge://kb/${cfg.knowledgeBaseId ?? 1}`,
      } }),
    },
    { key:'ping', label:'ping', body:() => ({ jsonrpc:'2.0', id:13, method:'ping', params:{} }) },
    {
      key:'unknown', label:'未知方法（期望 -32601）',
      body:() => ({ jsonrpc:'2.0', id:14, method:'tools/not_exists', params:{} }),
    },
  ]

  const mcpKey = ref('initialize')
  const mcpBody = ref('')
  const mcpSending = ref(false)
  const handshaking = ref(false)

  interface Check { label:string; pass:boolean; detail:string }
  interface McpResult { durationMs:number; body:string; checks:Check[] }
  const mcpResult = ref<McpResult | null>(null)
  const mcpTools = ref<{ name:string; description:string; required:string[]; optional:string[] }[]>([])

  /**
   * 工具的可选入参（inputSchema.properties 去掉 required）。
   * 清单卡片只列必填的话，minScore / topK / answerFormat 这类可选参数在界面上根本看不见，
   * 而"这个工具到底收不收 minScore"正是加了新参数后最该一眼看到的事。
   */
  function optionalArgsOf(schema:Record<string, unknown>) {
    const props = asRecord(schema.properties)
    const required = asArray(schema.required).map(String)
    return Object.keys(props).filter(name => required.indexOf(name) < 0)
  }
  const mcpSteps = ref<Check[]>([])

  function applyMcpTemplate() {
    const template = MCP_TEMPLATES.find(item => item.key === mcpKey.value)
    mcpBody.value = JSON.stringify(template ? template.body() : { jsonrpc:'2.0', id:1, method:'initialize', params:{} }, null, 2)
  }
  watch(mcpKey, applyMcpTemplate, { immediate:true })

  async function rawMcp(payload:unknown):Promise<{ status:number; durationMs:number; text:string; json:unknown }> {
    const url = withAuth(`${prefix()}/mcp`)
    const started = performance.now()
    const response = await fetchWithTimeout(url, {
      method:'POST', headers:authHeaders(), body:JSON.stringify(payload),
    })
    const text = await response.text()
    return { status:response.status, durationMs:Math.round(performance.now() - started), text, json:safeJson(text) }
  }

  /** 协议校验：JSON-RPC 的字段形状是 MCP 客户端能否解析的前提。 */
  function judgeMcp(request:unknown, call:{ status:number; json:unknown }) {
    const req = asRecord(request)
    const res = asRecord(call.json)
    const checks:Check[] = []
    checks.push({ label:'HTTP 200', pass:call.status === 200, detail:`HTTP ${call.status}` })
    checks.push({ label:'jsonrpc = 2.0', pass:res.jsonrpc === '2.0', detail:String(res.jsonrpc || '—') })
    const isNotification = req.method === 'notifications/initialized'
    checks.push({
      label:isNotification ? '通知类无响应体' : 'id 与请求一致',
      pass:isNotification ? Object.keys(res).length === 0 : String(res.id) === String(req.id),
      detail:isNotification ? `${Object.keys(res).length} 个字段` : `${req.id} → ${res.id}`,
    })
    const hasResult = Object.prototype.hasOwnProperty.call(res, 'result')
    const hasError = Object.prototype.hasOwnProperty.call(res, 'error')
    checks.push({ label:'result / error 二选一', pass:hasResult !== hasError, detail:hasResult ? 'result' : hasError ? 'error' : '均无' })
    if (hasError) {
      const error = asRecord(res.error)
      checks.push({ label:'error 含 code 与 message', pass:error.code !== undefined && !!error.message,
        detail:`code=${error.code} ${String(error.message || '').slice(0, 40)}` })
    }
    if (hasResult && String(req.method) === 'tools/list') {
      const tools = asArray(asRecord(res.result).tools)
      checks.push({ label:'tools 非空且含 inputSchema', pass:tools.length > 0
        && tools.every(tool => !!asRecord(tool).name && !!asRecord(tool).inputSchema), detail:`${tools.length} 个工具` })
    }
    if (hasResult && String(req.method) === 'tools/call') {
      const result = asRecord(res.result)
      const content = asArray(result.content)
      checks.push({ label:'content[0].type = text', pass:asRecord(content[0]).type === 'text', detail:String(asRecord(content[0]).type || '—') })
      checks.push({ label:'structuredContent 存在', pass:result.structuredContent !== undefined, detail:result.structuredContent === undefined ? '缺失' : '有' })
    }
    if (hasResult && String(req.method) === 'initialize') {
      const result = asRecord(res.result)
      checks.push({ label:'protocolVersion 已声明', pass:!!result.protocolVersion, detail:String(result.protocolVersion || '—') })
    }
    return checks
  }

  async function sendMcp() {
    mcpSending.value = true
    const payload = safeJson(mcpBody.value) ?? {}
    const method = String(asRecord(payload).method || '—')
    const id = pushLog({
      channel:'MCP', label:`MCP ${method}`, method:'POST', url:`${prefix()}/mcp`, status:0, durationMs:0,
      verdict:'PENDING', note:'请求已发出，等待响应…', body:'',
      curl:curlOf('POST', `${prefix()}/mcp`, mcpBody.value),
    })
    try {
      const call = await rawMcp(payload)
      mcpResult.value = {
        durationMs:call.durationMs, body:call.text, checks:judgeMcp(payload, call),
      }
      const passed = mcpResult.value.checks.filter(item => item.pass).length
      settleLog(id, {
        status:call.status, durationMs:call.durationMs, url:withAuth(`${prefix()}/mcp`),
        verdict:passed === mcpResult.value.checks.length ? 'PASS' : 'FAIL',
        note:`HTTP ${call.status} · 协议校验 ${passed}/${mcpResult.value.checks.length} 通过`,
        body:call.text,
      })
      const result = asRecord(asRecord(call.json).result)
      if (Array.isArray(result.tools)) {
        mcpTools.value = result.tools.map(tool => {
          const row = asRecord(tool)
          const schema = asRecord(row.inputSchema)
          return {
            name:String(row.name || ''),
            description:String(row.description || ''),
            required:asArray(schema.required).map(String),
            optional:optionalArgsOf(schema),
          }
        })
      } else {
        mcpTools.value = []
      }
    } catch (e) {
      const text = callError(e)
      mcpResult.value = { durationMs:0, body:text, checks:[{ label:'请求可达', pass:false, detail:text }] }
      settleLog(id, { verdict:'ERROR', note:text, body:text })
    } finally {
      mcpSending.value = false
    }
  }

  async function mcpHandshake() {
    handshaking.value = true
    mcpSteps.value = []
    const id = pushLog({
      channel:'MCP', label:'MCP 握手自检', method:'POST', url:withAuth(`${prefix()}/mcp`), status:0, durationMs:0,
      verdict:'PENDING', note:'initialize → tools/list → tools/call', body:'', curl:'',
    })
    try {
      const init = await rawMcp({ jsonrpc:'2.0', id:1, method:'initialize', params:{} })
      const initResult = asRecord(asRecord(init.json).result)
      const serverInfo = asRecord(initResult.serverInfo)
      mcpSteps.value.push({
        label:'initialize',
        pass:init.status === 200 && !!initResult.protocolVersion,
        detail:`${serverInfo.name || '—'} ${serverInfo.version || ''} · ${initResult.protocolVersion || '—'}`,
      })

      const list = await rawMcp({ jsonrpc:'2.0', id:2, method:'tools/list', params:{} })
      const tools = asArray(asRecord(asRecord(list.json).result).tools)
      mcpTools.value = tools.map(tool => {
        const row = asRecord(tool)
        return {
          name:String(row.name || ''),
          description:String(row.description || ''),
          required:asArray(asRecord(row.inputSchema).required).map(String),
          optional:optionalArgsOf(asRecord(row.inputSchema)),
        }
      })
      mcpSteps.value.push({
        label:'tools/list',
        pass:tools.length >= 10,
        detail:`${tools.length} 个工具`,
      })

      const call = await rawMcp({
        jsonrpc:'2.0', id:3, method:'tools/call', params:{
          name:'knowledge_query',
          arguments:{ knowledgeBaseId:cfg.knowledgeBaseId ?? 1, question:cfg.question, topK:cfg.topK,
            ...(Number.isFinite(cfg.minScore) ? { minScore:cfg.minScore } : {}) },
        },
      })
      const callResult = asRecord(asRecord(call.json).result)
      const structured = asRecord(callResult.structuredContent)
      const failed = asRecord(call.json).error !== undefined
      mcpSteps.value.push({
        label:'tools/call knowledge_query',
        pass:!failed && asRecord(asArray(callResult.content)[0]).type === 'text' && !!structured.queryId,
        detail:failed ? String(asRecord(asRecord(call.json).error).message || '调用失败') : `queryId=${structured.queryId || '—'}`,
      })
      mcpResult.value = {
        durationMs:call.durationMs, body:call.text, checks:judgeMcp({ id:3, method:'tools/call' }, call),
      }
      const passed = mcpSteps.value.filter(item => item.pass).length
      settleLog(id, {
        status:call.status, durationMs:call.durationMs,
        verdict:passed === mcpSteps.value.length ? 'PASS' : 'FAIL',
        note:`自检 ${passed}/${mcpSteps.value.length} 步通过：${mcpSteps.value.map(item => item.label).join(' → ')}`,
        body:call.text,
      })
    } catch (e) {
      const text = callError(e)
      mcpSteps.value.push({ label:'握手', pass:false, detail:text })
      settleLog(id, { verdict:'ERROR', note:text, body:text })
    } finally {
      handshaking.value = false
    }
  }

  /* ---------------- SSE ---------------- */

  interface SseEvent { seq:number; name:string; data:string; at:number; open:boolean }
  const sseKind = ref<'query' | 'events'>('query')
  const sseEvents = ref<SseEvent[]>([])
  const sseRunning = ref(false)
  const sseStats = reactive({ status:0, firstByteMs:0, elapsedMs:0, ended:'' })
  let sseController:AbortController | null = null

  function sseUrl() {
    const params = new URLSearchParams()
    if (cfg.knowledgeBaseId != null) params.set('knowledgeBaseId', String(cfg.knowledgeBaseId))
    if (sseKind.value === 'query') {
      params.set('question', cfg.question)
      params.set('topK', String(cfg.topK))
      params.set('answerFormat', cfg.answerFormat)
      // content：stream 逐片推 delta；final 只推检索过程，答案在 done 里整体交付。
      params.set('content', cfg.content)
      // 阈值只在填了时带：不传即不过滤。
      if (Number.isFinite(cfg.minScore)) params.set('minScore', String(cfg.minScore))
    }
    const path = sseKind.value === 'query' ? '/v1/external/stream/query' : '/v1/external/stream/events'
    return withAuth(`${prefix()}${path}?${params.toString()}`)
  }

  function pushSse(name:string, data:string, at:number) {
    sseEvents.value.push({ seq:sseEvents.value.length + 1, name, data, at, open:false })
    if (sseEvents.value.length > 300) sseEvents.value.shift()
  }
  function toggleSse(event:SseEvent) {
    event.open = !event.open
  }

  /** 手工解析 SSE 帧：既要事件名也要原始字节，才能判断服务端是否真的在流式推送。 */
  function parseFrame(chunk:string, started:number) {
    let name = 'message'
    const dataLines:string[] = []
    for (const line of chunk.split('\n')) {
      if (!line) continue
      if (line.startsWith(':')) {
        if (name === 'message') name = 'comment'
        dataLines.push(line.slice(1).trim())
        continue
      }
      const index = line.indexOf(':')
      if (index < 0) continue
      const field = line.slice(0, index).trim()
      const value = line.slice(index + 1).trim()
      if (field === 'event') name = value
      else if (field === 'data') dataLines.push(value)
    }
    return { name, data:dataLines.join('\n'), at:Math.round(performance.now() - started) }
  }

  async function startSse() {
    if (sseRunning.value) return
    const url = sseUrl()
    sseEvents.value = []
    sseStats.status = 0
    sseStats.firstByteMs = 0
    sseStats.ended = ''
    sseController = new AbortController()
    sseRunning.value = true
    const started = performance.now()
    let buffer = ''
    let first = true
    // 流式订阅没有“响应完成”，记录要靠订阅开始/结束两个时刻撑起来。
    const logId = pushLog({
      channel:'SSE', label:`SSE ${sseKind.value === 'query' ? '/stream/query' : '/stream/events'}`,
      method:'GET', url, status:0, durationMs:0,
      verdict:'PENDING', note:'连接中，等待首个事件帧…', body:'', curl:curlOf('GET', url),
    })
    try {
      const response = await fetch(url, { headers:authHeaders(), signal:sseController.signal })
      sseStats.status = response.status
      settleLog(logId, { status:response.status, note:`HTTP ${response.status}，等待事件帧…` })
      if (!response.ok || !response.body) {
        const text = await response.text()
        sseStats.ended = `HTTP ${response.status}`
        pushSse('error', text || response.statusText, Math.round(performance.now() - started))
        return
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        if (first) {
          sseStats.firstByteMs = Math.round(performance.now() - started)
          first = false
        }
        buffer += decoder.decode(value, { stream:true })
        let index = buffer.indexOf('\n\n')
        while (index >= 0) {
          const frame = parseFrame(buffer.slice(0, index), started)
          buffer = buffer.slice(index + 2)
          pushSse(frame.name, frame.data, frame.at)
          index = buffer.indexOf('\n\n')
        }
        // 一次问答推送完 done 即结束；事件订阅由调用方手动停止或超时结束。
        if (sseKind.value === 'query' && sseEvents.value.some(event => event.name === 'done')) break
      }
      sseStats.ended = 'completed'
      sseController?.abort()
    } catch (e) {
      const error = e as { name?:string; message?:string }
      sseStats.ended = error?.name === 'AbortError' ? 'aborted' : `error: ${error?.message || ''}`
    } finally {
      sseStats.elapsedMs = Math.round(performance.now() - started)
      sseRunning.value = false
      const names = sseEvents.value.map(event => event.name)
      const stopped = sseStats.ended === 'aborted'
      const complete = sseKind.value === 'events' || names.includes('done')
      settleLog(logId, {
        status:sseStats.status, durationMs:sseStats.elapsedMs,
        verdict:sseStats.status === 200 && names.length && (complete || stopped) ? 'PASS' : 'FAIL',
        note:`HTTP ${sseStats.status || '—'} · ${names.length} 帧（首帧 ${sseStats.firstByteMs} ms，共 ${sseStats.elapsedMs} ms）`
          + (stopped ? ' · 已手动停止' : ` · ${sseStats.ended || '未结束'}`),
        body:JSON.stringify(sseEvents.value.map(event => ({ seq:event.seq, name:event.name, at:event.at })), null, 2),
      })
    }
  }

  function stopSse() {
    sseController?.abort()
    sseController = null
  }

  /* ---------------- 平台事件总线：登录会话订阅 ---------------- */
  /**
   * This is the bus itself rather than a probe of the external channel: one event per mutation,
   * enriched with actor, target and detail, streamed with standards-compliant SSE ids so a dropped
   * connection can resume from where it left off. It uses the browser session, so unlike the keyed
   * stream above it also sees platform-scope events such as knowledge-base creation.
   */
  interface BusEvent {
    key:string; id:number; type:string; time:string; source:string
    actorName:string; targetType:string; targetCode:string; summary:string
    detail:Record<string, unknown>; open:boolean
  }

  const busEventTypes = ref<string[]>([])
  const busSelectedTypes = ref<string[]>([])
  const busKb = ref<number | null>(null)
  const busSince = ref('')
  const busRunning = ref(false)
  const busStatus = ref('未连接')
  const busEvents = ref<BusEvent[]>([])
  let busController:AbortController | null = null

  function sessionHeaders():Record<string, string> {
    const headers:Record<string, string> = { Accept:'application/json' }
    const token = getToken()
    if (token) headers.Authorization = `Bearer ${token}`
    return headers
  }

  async function busRequest(path:string, params:Record<string, string> = {}):Promise<Record<string, unknown>> {
    const query = new URLSearchParams(params).toString()
    const response = await fetch(
      `${prefix()}${path}${query ? `?${query}` : ''}`, { headers:sessionHeaders() })
    if (!response.ok) throw new Error(`HTTP ${response.status}：${await response.text()}`)
    const body = await response.json() as ApiEnvelope<Record<string, unknown>>
    if (!body?.success) throw new Error(body?.message || '请求失败')
    return body.data ?? {}
  }

  function busParams():Record<string, string> {
    const params:Record<string, string> = {}
    if (busKb.value != null) params.knowledgeBaseId = String(busKb.value)
    if (busSelectedTypes.value.length) params.types = busSelectedTypes.value.join(',')
    if (busSince.value.trim()) params.sinceId = busSince.value.trim()
    return params
  }

  async function loadBusTypes() {
    if (busEventTypes.value.length) return
    try {
      busEventTypes.value = asArray(await busRequest('/internal/platform/events/types'))
        .map(value => String(value))
    } catch (e) {
      ElMessage.error(`事件类型字典加载失败：${message(e)}`)
    }
  }

  async function startBus() {
    if (busRunning.value) return
    await loadBusTypes()
    const controller = new AbortController()
    busController = controller
    busEvents.value = []
    busRunning.value = true
    busStatus.value = '连接中…'
    const query = new URLSearchParams(busParams()).toString()
    const url = `${prefix()}/internal/platform/events/stream${query ? `?${query}` : ''}`
    const started = performance.now()
    let streamStatus = 0
    const logId = pushLog({
      channel:'BUS', label:'事件总线订阅 /internal/platform/events/stream', method:'GET', url, status:0, durationMs:0,
      verdict:'PENDING', note:'连接中，等待事件推送…', body:'', curl:'',
    })
    try {
      const response = await fetch(url, {
        headers:{ ...sessionHeaders(), Accept:'text/event-stream' },
        signal:controller.signal,
      })
      streamStatus = response.status
      settleLog(logId, { status:response.status, note:`HTTP ${response.status}，等待事件推送…` })
      if (!response.ok || !response.body) {
        busStatus.value = `HTTP ${response.status}：${await response.text()}`
        busRunning.value = false
        return
      }
      busStatus.value = `已连接 HTTP ${response.status}`
      const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
      let buffer = ''
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += value
        const frames = buffer.split('\n\n')
        buffer = frames.pop() ?? ''
        frames.forEach(handleBusFrame)
      }
      busStatus.value = 'SSE 正常结束'
    } catch (e) {
      const error = e as { name?:string }
      busStatus.value = error?.name === 'AbortError' ? '已停止' : `订阅中断：${message(e)}`
    } finally {
      if (busController === controller) {
        busController = null
        busRunning.value = false
      }
      settleLog(logId, {
        status:streamStatus, durationMs:Math.round(performance.now() - started),
        verdict:streamStatus === 200 ? 'PASS' : 'FAIL',
        note:`HTTP ${streamStatus || '—'} · ${busEvents.value.length} 个事件 · ${busStatus.value}`,
        body:JSON.stringify(busEvents.value.slice(-40).map(event => ({
          type:event.type, time:event.time, target:event.targetCode, summary:event.summary,
        })), null, 2),
      })
    }
  }

  function stopBus() {
    busController?.abort()
    busController = null
    busRunning.value = false
    if (!busStatus.value.startsWith('已')) busStatus.value = '已停止'
  }

  /** Comment frames are heartbeats and carry no data; the handshake frame only updates status. */
  function handleBusFrame(frame:string) {
    let name = ''
    const chunks:string[] = []
    frame.split('\n').forEach(line => {
      const trimmed = line.trim()
      if (!trimmed || trimmed.startsWith(':')) return
      if (trimmed.startsWith('event:')) name = trimmed.slice(6).trim()
      else if (trimmed.startsWith('data:')) chunks.push(trimmed.slice(5).trim())
    })
    if (!chunks.length) return
    const text = chunks.join('\n')
    if (name === 'open') {
      busStatus.value = `已连接 ${text}`
      return
    }
    const payload = asRecord(safeJson(text))
    if (!Object.keys(payload).length) {
      busStatus.value = '收到无法解析的事件帧'
      return
    }
    pushBusEvent(payload, name)
  }

  function pushBusEvent(payload:Record<string, unknown>, fallbackType = '') {
    const id = Number(payload.id ?? 0)
    const actor = asRecord(payload.actor)
    busEvents.value.unshift({
      key:`${String(payload.eventId ?? Math.random())}-${Math.random()}`,
      id,
      type:String(payload.type || fallbackType),
      time:String(payload.time ?? ''),
      source:String(payload.source ?? ''),
      actorName:String(actor.name ?? actor.id ?? ''),
      targetType:String(payload.targetType ?? ''),
      targetCode:String(payload.targetCode ?? ''),
      summary:String(payload.summary ?? ''),
      detail:asRecord(payload.detail),
      open:false,
    })
    if (busEvents.value.length > 200) busEvents.value.pop()
    // Advancing the cursor here is what makes reconnect-with-Last-Event-ID verifiable by hand.
    if (id) busSince.value = String(id)
  }

  async function loadBusHistory() {
    const started = performance.now()
    const id = pushLog({
      channel:'BUS', label:'事件历史 GET /internal/platform/events', method:'GET',
      url:`${prefix()}/internal/platform/events`, status:0, durationMs:0,
      verdict:'PENDING', note:'加载中…', body:'', curl:'',
    })
    try {
      const payload = await busRequest('/internal/platform/events', { ...busParams(), limit:'50' })
      const items = asArray(payload.items).map(value => asRecord(value)).reverse()
      items.forEach(item => pushBusEvent(item))
      busStatus.value = `历史加载完成（${items.length} 条）`
      settleLog(id, {
        status:200, durationMs:Math.round(performance.now() - started), verdict:'PASS',
        note:`历史 ${items.length} 条`, body:JSON.stringify(payload),
      })
    } catch (e) {
      const text = callError(e)
      settleLog(id, { verdict:'ERROR', note:text, body:text })
      ElMessage.error(`事件历史加载失败：${text}`)
    }
  }

  function previewBus(event:BusEvent) {
    const body:Record<string, unknown> = { ...event.detail }
    if (event.targetType) body.target = `${event.targetType}:${event.targetCode || '—'}`
    return JSON.stringify(body, null, 2)
  }

  const sseVerdict = computed(() => {
    if (!sseEvents.value.length) return null
    const names = sseEvents.value.map(event => event.name)
    if (sseKind.value === 'query') {
      // 阶段事件带上的是引擎自己的 key（rewrite / route / recall …），且每个阶段会先报
      // running 再报 done——只有 done 才是“这一步真跑完了”。
      const stages = sseEvents.value
        .filter(event => event.name === 'stage')
        .map(event => asRecord(safeJson(event.data)))
        .filter(row => row.status === 'done')
        .map(row => String(row.key || ''))
      const expected = ['rewrite', 'route', 'recall', 'acl_filter', 'fusion', 'rerank', 'context', 'llm', 'citation']
      const missing = expected.filter(key => !stages.includes(key))
      const items:Check[] = [
        {
          label:'九个阶段全部完成',
          pass:!missing.length,
          detail:missing.length ? `缺 ${missing.join(',')}` : stages.join('→') || '未收到',
        },
        { label:'delta 增量推送', pass:names.includes('delta'), detail:`${names.filter(n => n === 'delta').length} 片` },
        { label:'citation 引用', pass:names.includes('citation'), detail:names.includes('citation') ? '已推送' : '未收到' },
        { label:'done 结束事件', pass:names.includes('done'), detail:names.includes('done') ? '已推送' : '未收到' },
        { label:'无异常事件', pass:!names.includes('error'), detail:names.includes('error') ? '有 error' : '无' },
      ]
      return { pass:items.every(item => item.pass), items }
    }
    const items:Check[] = [
      { label:'open 订阅确认', pass:names.includes('open'), detail:names.includes('open') ? '已推送' : '未收到' },
      {
        label:'心跳注释（:heartbeat）',
        pass:names.includes('comment'),
        detail:names.includes('comment') ? `${names.filter(n => n === 'comment').length} 次` : '未收到（心跳间隔内属正常）',
      },
      {
        label:'业务事件 ingestion / knowledge',
        pass:true,
        detail:`ingestion ${names.filter(n => n === 'ingestion').length} · knowledge ${names.filter(n => n === 'knowledge').length}`,
      },
    ]
    return { pass:items[0].pass, items }
  })

  /* ---------------- 临时凭证 ---------------- */

  async function issueLabKey() {
    try {
      const created = unwrap(await createExternalKey({
        name:`联调台临时凭证 ${new Date().toLocaleString('zh-CN', { hour12:false })}`,
        knowledgeBaseId:cfg.knowledgeBaseId,
        scopes:['READ'],
        rateLimitPerMinute:60,
        expiresAt:null,
      }), null)
      const secret = String(asRecord(created as unknown).secret || '')
      if (!secret) {
        ElMessage.warning('未取到明文密钥，请确认当前账号具备管理员或知识库管理员角色')
        return
      }
      cfg.apiKey = secret
      showKey.value = true
      ElMessage.success('临时凭证已签发并填入（只读 / 每分钟 60 次），验证完请到外部服务页吊销')
    } catch (e) {
      ElMessage.error(message(e))
    }
  }

  onUnmounted(() => {
    stopSse()
    stopBus()
  })

  return {
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
    answerFormatOptions:ANSWER_FORMATS,
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
  }
}
