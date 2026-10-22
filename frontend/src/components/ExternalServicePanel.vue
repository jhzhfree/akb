<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">接口服务</div>
        <h1 class="kb-title mt-1">{{ section === 'credentials' ? '凭证' : '服务' }}</h1>
        <p class="kb-muted mt-1">
          <template v-if="section==='credentials'">
            API 密钥决定调用方可见的知识库范围与可用动作：明文只在签发时展示一次，吊销即让外部系统立即失去访问能力。
          </template>
          <template v-else>
            对外以 RESTful、MCP 服务、SSE 三种形态开放同一套知识能力：一个 API 密钥 决定可见的知识库范围与可用动作，
            三个通道共用一套限流与调用审计。
          </template>
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <!-- V2.2：服务与凭证是「接口服务」下的两个 Tab，区块由 Tab 的 focus 决定，面板内不再重复开关。 -->
        <button class="kb-btn-outline" :disabled="loading" @click="loadAll">刷新</button>
      </div>
    </header>

    <div v-if="section==='services'" class="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
      <MetricCard label="RESTful" :value="overview?.rest?.endpoints?.length ?? 0" hint="对外接口数" />
      <MetricCard label="MCP 工具" :value="overview?.mcp?.tools?.length ?? 0" hint="智能体可用工具" />
      <MetricCard label="SSE" :value="overview?.sse?.endpoints?.length ?? 0" hint="推送通道" />
      <MetricCard label="在用凭证" :value="overview?.activeKeys ?? 0" hint="有效状态的 API 密钥" />
    </div>

    <div v-if="error" class="mt-3 rounded-lg border border-[color:var(--warn-bd)] bg-[color:var(--warn-bg)] px-4 py-2.5 text-xs text-[color:var(--warn)]">
      {{ error }}
    </div>

    <div v-if="section==='services'" class="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3">
      <!-- 协议三卡属于「服务」视角：凭证 Tab 只留凭证表，免得焦点被协议信息带偏。 -->
      <template v-if="section==='services'">
      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">REST 接口</b>
          <el-tag :type="overview?.rest?.enabled ? 'success' : 'info'" size="small" effect="plain">
            {{ overview?.rest?.enabled ? '已开放' : '已关闭' }}
          </el-tag>
        </div>
        <div class="p-3">
          <div class="kb-muted">基础路径 <b class="text-ink-700">{{ overview?.rest?.basePath || '—' }}</b></div>
          <div class="kb-muted mt-1">鉴权 {{ overview?.rest?.auth || '—' }}</div>
          <div class="mt-2 flex items-center gap-2 text-xs">
            <span class="kb-muted">平台记忆服务</span>
            <el-tag :type="overview?.rest?.agentMemoryEnabled ? 'success' : 'info'" size="small" effect="plain">
              {{ overview?.rest?.agentMemoryEnabled ? '已启用' : '已关闭' }}
            </el-tag>
            <span class="kb-muted">由部署配置控制</span>
          </div>
          <div class="mt-3 max-h-64 overflow-auto">
            <div v-for="item in overview?.rest?.endpoints || []" :key="item.method + item.path"
              class="flex items-start gap-2 border-b border-ink-100 py-1.5 last:border-0">
              <span class="w-12 shrink-0 text-[11px] font-semibold text-accent">{{ item.method }}</span>
              <span class="min-w-0 flex-1">
                <span class="block truncate text-[11px] text-ink-700">{{ item.path }}</span>
                <span class="block text-[11px] text-ink-500">{{ item.summary }}</span>
              </span>
            </div>
          </div>
        </div>
      </article>

      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">MCP 服务</b>
          <el-tag :type="overview?.mcp?.enabled ? 'success' : 'info'" size="small" effect="plain">
            {{ overview?.mcp?.enabled ? '已开放' : '已关闭' }}
          </el-tag>
        </div>
        <div class="p-3">
          <div class="kb-muted">端点 <b class="text-ink-700">{{ overview?.mcp?.endpoint || '—' }}</b></div>
          <div class="kb-muted mt-1">协议版本 {{ overview?.mcp?.protocolVersion || '—' }}</div>
          <div class="mt-2 flex flex-wrap gap-1">
            <span v-for="method in overview?.mcp?.methods || []" :key="method"
              class="rounded border border-ink-200 bg-ink-50 px-2 py-0.5 text-[11px] text-ink-600">{{ method }}</span>
          </div>
          <div class="mt-3 kb-muted">可用工具</div>
          <div class="mt-1 flex flex-wrap gap-1">
            <span v-for="tool in overview?.mcp?.tools || []" :key="tool"
              class="rounded border border-[color:var(--info-bd)] bg-[color:var(--info-bg)] px-2 py-0.5 text-[11px] text-accent">{{ tool }}</span>
          </div>
        </div>
      </article>

      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">SSE 推送</b>
          <el-tag :type="overview?.sse?.enabled ? 'success' : 'info'" size="small" effect="plain">
            {{ overview?.sse?.enabled ? '已开放' : '已关闭' }}
          </el-tag>
        </div>
        <div class="p-3">
          <div class="kb-muted">心跳 {{ overview?.sse?.heartbeatSeconds ?? '—' }} 秒 · 单次最长 {{ overview?.sse?.maxMinutes ?? '—' }} 分钟</div>
          <div class="mt-3">
            <div v-for="item in overview?.sse?.endpoints || []" :key="item.path"
              class="border-b border-ink-100 py-1.5 last:border-0">
              <span class="block truncate text-[11px] text-ink-700">{{ item.path }}</span>
              <span class="block text-[11px] text-ink-500">{{ item.summary }}</span>
            </div>
          </div>
        </div>
      </article>
      </template>
    </div>

    <article v-if="section==='credentials'" class="kb-card mt-4">
      <div class="kb-card-head">
        <b class="text-sm">API 密钥凭证</b>
        <div class="flex items-center gap-2">
          <span class="kb-muted">明文只在签发时展示一次</span>
          <button class="kb-btn-primary" @click="openCreate">签发凭证</button>
        </div>
      </div>
      <div class="p-3">
        <PanelState :loading="loading" :empty="!keys.length" empty-text="尚未签发任何 API Key"
          empty-hint="签发后外部系统即可按范围调用知识服务" retryable @retry="loadAll">
          <el-table :data="keys" border size="small">
            <el-table-column prop="name" label="调用方" min-width="150" show-overflow-tooltip />
            <el-table-column label="密钥前缀" width="180">
              <template #default="{ row }"><span class="text-[11px]">{{ row.keyPrefix || '—' }}</span></template>
            </el-table-column>
            <el-table-column label="知识库范围" min-width="180">
              <template #default="{ row }">
                <span v-if="row.grantedKnowledgeBaseCount" class="text-xs">
                  {{ row.grantedKnowledgeBaseCount }} 个授权库
                </span>
                <span v-else>{{ row.knowledgeBaseName || (row.knowledgeBaseId ? `知识库 #${row.knowledgeBaseId}` : '公开库') }}</span>
              </template>
            </el-table-column>
            <el-table-column label="记忆身份" min-width="190">
              <template #default="{ row }">
                <div class="text-xs">{{ row.memoryAgentCode || row.keyCode }}</div>
                <div class="text-[10px] text-ink-500">{{ row.memoryNamespace || '密钥隔离' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="共享主体" min-width="170">
              <template #default="{ row }">
                <div class="text-xs">{{ row.principalName || '未配置主体' }}</div>
                <div class="text-[10px] text-ink-500">{{ row.principalId ? `主体 #${row.principalId}` : '—' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="权限" width="180">
              <template #default="{ row }">
                <el-tag v-for="scope in scopeList(row)" :key="scope" size="small" effect="plain" class="mr-1">
                  {{ scopeLabel(scope) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="限流" width="120">
              <template #default="{ row }">{{ row.rateLimitPerMinute ?? '—' }} 次/分</template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small" effect="plain">
                  {{ row.status === 'ACTIVE' ? '有效' : '已吊销' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="有效期" width="170">
              <template #default="{ row }">{{ row.expiresAt || '长期有效' }}</template>
            </el-table-column>
            <el-table-column label="最近调用" width="170">
              <template #default="{ row }">{{ row.lastUsedAt || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="470" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openKnowledgeBaseGrants(row)">知识库授权</el-button>
                <el-button link type="primary" size="small" @click="openPrincipalDialog(row)">共享主体</el-button>
                <el-button v-if="hasMemoryCapability(row)" link type="primary" size="small" @click="openMemoryPolicy(row)">记忆开关</el-button>
                <el-button v-if="hasMemoryScope(row)" link type="primary" size="small" @click="openMemoryAccess(row)">记忆授权</el-button>
                <el-button v-if="hasMemoryScope(row)" link type="primary" size="small" @click="openMemoryManager(row)">记忆管理</el-button>
                <el-button v-if="row.status === 'ACTIVE'" link type="danger" size="small" @click="revoke(row)">吊销</el-button>
                <span v-else class="text-[11px] text-ink-500">已失效</span>
              </template>
            </el-table-column>
          </el-table>
        </PanelState>
      </div>
    </article>

    <div v-if="section==='services'" class="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-2">
      <article class="kb-card">
        <div class="kb-card-head"><b class="text-sm">接入示例</b><span class="kb-muted">复制即可联调</span></div>
        <div class="p-3">
          <el-tabs v-model="exampleTab">
            <el-tab-pane label="RESTful" name="rest">
              <pre class="code-block">{{ restExample }}</pre>
            </el-tab-pane>
            <el-tab-pane label="MCP" name="mcp">
              <pre class="code-block">{{ mcpExample }}</pre>
            </el-tab-pane>
            <el-tab-pane label="SSE" name="sse">
              <pre class="code-block">{{ sseExample }}</pre>
            </el-tab-pane>
          </el-tabs>
          <p class="kb-muted mt-2 text-[11px]">{{ answerFormatHint }}</p>
          <p class="kb-muted mt-1 text-[11px]">{{ minScoreHint }}</p>
          <p class="kb-muted mt-1 text-[11px]">{{ responseModeHint }}</p>
          <p v-if="exampleTab === 'sse'" class="kb-muted mt-1 text-[11px]">{{ contentHint }}</p>
          <div class="mt-3 border-t border-ink-100 pt-3">
            <div class="flex flex-wrap items-center gap-2">
              <input v-model="probeSecret" class="kb-input flex-1" placeholder="粘贴明文 API 密钥做连通性自检" />
              <button class="kb-btn-outline" :disabled="probing || !probeSecret.trim()" @click="probe">REST 自检</button>
              <button class="kb-btn-outline" :disabled="probing || !probeSecret.trim()" @click="probeSse">SSE 自检</button>
            </div>
            <p v-if="probeResult" class="mt-2 text-[11px]" :class="probeOk ? 'text-[color:var(--ok)]' : 'text-[color:var(--danger)]'">
              {{ probeResult }}
            </p>
            <p class="kb-muted mt-2">
              自检请求不带控制台会话，与外部调用方完全一致：可验证凭证是否有效、知识库范围是否生效、配额是否被限流。
            </p>
          </div>
        </div>
      </article>

      <!-- 对外调用只在这里露最近几条做健康佐证：全量明细统一去「调用日志」看，
           同一批审计数据不在两个 Tab 各摆一张口径不同的表。 -->
      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">最近调用</b>
          <button class="text-xs font-medium text-accent hover:text-accent" @click="shell.navigate('integration.logs')">
            调用日志 →
          </button>
        </div>
        <div class="divide-y divide-ink-100 px-4">
          <div v-for="(row,i) in audits" :key="i" class="flex items-center gap-2 py-2 text-[12px]">
            <el-tag size="small" effect="plain" :type="channelType(row.channel)">{{ row.channel }}</el-tag>
            <span class="min-w-0 flex-1 truncate text-ink-700">{{ row.method || '—' }}</span>
            <span class="shrink-0 text-[11px] text-ink-500">{{ row.durationMs }} ms</span>
            <span
              class="shrink-0 text-[11px]"
              :class="row.statusCode >= 400 ? 'text-[color:var(--danger)]' : 'text-[color:var(--ok)]'">
              {{ row.statusCode ?? '—' }}
            </span>
          </div>
          <div v-if="auditLoading" class="py-6 text-center text-[12px] text-ink-500">加载中…</div>
          <div v-else-if="!audits.length" class="py-6 text-center text-[12px] text-ink-500">
            暂无对外调用记录，外部系统发起调用后会在此留痕
          </div>
        </div>
      </article>
    </div>

    <el-dialog v-model="createVisible" title="签发 API 密钥" width="560px">
      <el-form label-width="96px" size="small">
        <el-form-item label="调用方名称">
          <el-input v-model="form.name" placeholder="如：ERP 问答助手" />
        </el-form-item>
        <el-form-item label="知识库范围">
          <el-select v-model="form.knowledgeBaseId" clearable class="w-full" placeholder="不绑定=仅可见公开库">
            <el-option v-for="base in bases" :key="base.id" :value="base.id" :label="base.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="权限">
          <el-select v-model="form.scopes" multiple class="w-full">
            <el-option value="READ" label="读取全部（兼容权限组）" />
            <el-option value="LIST" label="列出知识库" />
            <el-option value="QUERY" label="知识问答（生成答案）" />
            <el-option value="SEARCH" label="知识检索（只返回候选）" />
            <el-option value="READ_OBJECT" label="读取知识对象与证据" />
            <el-option value="WRITE" label="写入（知识治理）" />
            <el-option value="INGEST" label="接入（文本摄入）" />
            <el-option value="MEMORY_READ" label="读取记忆（Agent Recall）" />
            <el-option value="MEMORY_WRITE" label="写入记忆（Capture / Forget）" />
          </el-select>
        </el-form-item>
        <template v-if="form.scopes.includes('MEMORY_READ') || form.scopes.includes('MEMORY_WRITE')">
          <el-form-item label="记忆命名空间">
            <el-input v-model="form.memoryNamespace" placeholder="空值时仅本密钥可见；同值用于显式共享项目 / 任务记忆" />
          </el-form-item>
          <el-form-item label="Agent 编码">
            <el-input v-model="form.memoryAgentCode" placeholder="空值时使用本密钥编码；用于 Agent 私有记忆隔离" />
          </el-form-item>
        </template>
        <el-form-item label="限流">
          <el-input-number v-model="form.rateLimitPerMinute" :min="1" :max="6000" />
          <span class="ml-2 text-[11px] text-ink-500">次 / 分钟</span>
        </el-form-item>
        <el-form-item label="有效期">
          <el-input v-model="form.expiresAt" placeholder="留空长期有效，如 2026-12-31T23:59:59" />
        </el-form-item>
      </el-form>
      <p class="kb-muted">
        绑定知识库后，凭证只能访问该库；不绑定时仅能看到公开知识库。记忆命名空间默认按密钥隔离，同一命名空间需由管理员明确配置后才共享项目 / 任务记忆。
      </p>
      <template #footer>
        <el-button size="small" @click="createVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="saveKey">签发</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="knowledgeBaseGrantVisible" title="API Key 知识库授权" width="600px">
      <div v-if="knowledgeBaseGrantKey" class="space-y-3">
        <div class="kb-muted">{{ knowledgeBaseGrantKey.name }} · {{ knowledgeBaseGrantKey.keyPrefix }}</div>
        <el-alert type="info" :closable="false" show-icon>
          <template #title>选择该凭证可访问的知识库</template>
          保存会整体替换授权。已绑定的私有库也必须保留在列表中才可继续访问。
        </el-alert>
        <el-select v-model="selectedKnowledgeBaseIds" class="w-full" multiple filterable clearable
          collapse-tags collapse-tags-tooltip placeholder="选择一个或多个知识库">
          <el-option v-for="base in bases" :key="base.id" :value="base.id" :label="base.name" />
        </el-select>
        <div v-if="selectedGrantBases.length" class="space-y-2">
          <div v-for="base in selectedGrantBases" :key="base.id" class="grid grid-cols-12 items-center gap-2 rounded border border-ink-100 p-2">
            <span class="col-span-5 truncate text-xs" :title="base.name">{{ base.name }}</span>
            <span class="col-span-2 text-[11px] text-ink-500">授权到期</span>
            <el-date-picker v-model="grantExpiryByKnowledgeBase[base.id]" class="col-span-5 w-full" type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss" format="YYYY-MM-DD HH:mm:ss" clearable placeholder="不设置=长期有效" />
          </div>
        </div>
        <el-input v-model="knowledgeBaseGrantReason" maxlength="500" show-word-limit placeholder="授权变更原因（可选，会写入审计记录）" />
        <p class="kb-muted">
          每个知识库可单独设定到期时间，过期后访问会自动拒绝。留空会撤销显式授权；未绑定知识库的凭证仍可访问系统公开库，绑定过的私有库则会失去访问权限。
          知识库自身的访问策略仍会继续生效。
        </p>
      </div>
      <template #footer>
        <el-button size="small" @click="knowledgeBaseGrantVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="knowledgeBaseGrantSaving" @click="saveKnowledgeBaseGrants">保存授权</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="principalDialogVisible" title="API Key 共享主体" width="620px">
      <div v-if="principalKey" class="space-y-3">
        <div class="kb-muted">{{ principalKey.name }} · {{ principalKey.keyPrefix }}</div>
        <el-alert type="warning" :closable="false" show-icon>
          <template #title>共享主体会共享知识库授权</template>
          加入现有主体后，此 Key 会立即继承该主体的有效知识库授权。新建主体会复制当前 Key 的有效授权，其他 Key 需再加入该主体。
        </el-alert>
        <el-form label-width="100px" size="small">
          <el-form-item label="现有主体">
            <el-select v-model="selectedPrincipalId" class="w-full" clearable filterable placeholder="选择现有主体" @change="newPrincipalName = ''">
              <el-option v-for="principal in principalOptions" :key="principal.id" :value="principal.id"
                :label="`${principal.name} · ${principal.keyCount} Key / ${principal.activeGrantCount} 授权`" />
            </el-select>
          </el-form-item>
          <el-form-item label="新建主体">
            <el-input v-model="newPrincipalName" maxlength="128" placeholder="填写名称则创建新主体，并复制当前有效授权" @input="selectedPrincipalId = null" />
          </el-form-item>
          <el-form-item label="变更原因">
            <el-input v-model="principalChangeReason" type="textarea" maxlength="500" show-word-limit :rows="2" placeholder="必填，记录主体变更的业务依据" />
          </el-form-item>
        </el-form>
        <p class="kb-muted">当前主体：{{ principalKey.principalName || '—' }}。主体关联变更会写入审计；主体本身不提供删除操作，以保留授权和变更历史。</p>
      </div>
      <template #footer>
        <el-button size="small" @click="principalDialogVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="principalSaving" @click="savePrincipalAssignment">保存主体绑定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="memoryPolicyVisible" title="API Key 记忆开关" width="460px">
      <div v-if="memoryPolicyKey" class="space-y-3">
        <div class="kb-muted">{{ memoryPolicyKey.name }} · {{ memoryPolicyKey.keyPrefix }}</div>
        <el-alert v-if="!overview?.rest?.agentMemoryEnabled" type="warning" :closable="false"
          title="平台级记忆服务当前已关闭；打开此凭证权限也不会启用记忆接口。" />
        <el-form label-width="110px" size="small">
          <el-form-item label="允许召回">
            <el-switch v-model="memoryPolicy.readEnabled" :disabled="!hasDeclaredScope(memoryPolicyKey, 'MEMORY_READ')" />
            <span v-if="!hasDeclaredScope(memoryPolicyKey, 'MEMORY_READ')" class="ml-2 kb-muted">签发时未授予 MEMORY_READ</span>
          </el-form-item>
          <el-form-item label="允许写入 / 删除">
            <el-switch v-model="memoryPolicy.writeEnabled" :disabled="!hasDeclaredScope(memoryPolicyKey, 'MEMORY_WRITE')" />
            <span v-if="!hasDeclaredScope(memoryPolicyKey, 'MEMORY_WRITE')" class="ml-2 kb-muted">签发时未授予 MEMORY_WRITE</span>
          </el-form-item>
        </el-form>
        <p class="kb-muted">此策略只能关闭或恢复签发时已授予的权限，不能增加新的 scope。关闭后 REST 调用会被拒绝，MCP 不再公布对应工具。</p>
      </div>
      <template #footer>
        <el-button size="small" @click="memoryPolicyVisible=false">取消</el-button>
        <el-button size="small" type="primary" :loading="memoryPolicySaving" @click="saveMemoryPolicy">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="memoryAccessVisible" title="共享记忆授权" width="740px">
      <p class="kb-muted mb-3">私有 AGENT 记忆仍按密钥身份隔离。共享记忆必须逐项授权；scope code 相同不代表有访问权限。</p>
      <div v-for="(grant, index) in memoryGrants" :key="index" class="mb-2 grid grid-cols-12 items-center gap-2">
        <el-select v-model="grant.scopeType" class="col-span-3" size="small">
          <el-option value="TEAM" label="团队"/><el-option value="PROJECT" label="项目"/>
          <el-option value="TASK" label="任务"/><el-option value="WORKFLOW" label="工作流"/>
          <el-option value="SESSION" label="会话"/>
        </el-select>
        <el-input v-model="grant.scopeCode" class="col-span-4" size="small" placeholder="作用域编码"/>
        <el-checkbox v-model="grant.canRead" class="col-span-2">读取</el-checkbox>
        <el-checkbox v-model="grant.canWrite" class="col-span-2">写入</el-checkbox>
        <el-button class="col-span-1" link type="danger" @click="memoryGrants.splice(index, 1)">移除</el-button>
      </div>
      <el-button size="small" @click="addMemoryGrant">添加作用域</el-button>
      <template #footer>
        <el-button size="small" @click="memoryAccessVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="memoryAccessSaving" @click="saveMemoryAccess">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="memoryManagerVisible" :title="`记忆资产 · ${memoryManagerKey?.name || ''}`" width="1000px">
      <div class="mb-3 flex flex-wrap items-center gap-2">
        <el-button size="small" @click="openMemoryEvents">治理记录</el-button>
        <el-select v-model="memoryStatusFilter" clearable size="small" class="w-36" placeholder="全部状态" @change="loadManagedMemories()">
          <el-option value="ACTIVE" label="可召回"/><el-option value="SUSPENDED" label="已暂停"/>
        </el-select>
        <el-select v-model="memoryTypeFilter" clearable size="small" class="w-36" placeholder="全部作用域" @change="loadManagedMemories()">
          <el-option value="AGENT" label="Agent"/><el-option value="TEAM" label="团队"/><el-option value="PROJECT" label="项目"/>
          <el-option value="TASK" label="任务"/><el-option value="WORKFLOW" label="工作流"/><el-option value="SESSION" label="会话"/>
        </el-select>
        <el-input v-model="memoryCodeFilter" size="small" class="w-48" placeholder="作用域编码" @keyup.enter="loadManagedMemories()"/>
        <el-button size="small" @click="loadManagedMemories()">查询</el-button>
      </div>
      <el-table :data="managedMemories" v-loading="memoryManagerLoading" max-height="480" size="small" border>
        <el-table-column prop="scopeType" label="类型" width="100"/>
        <el-table-column prop="scopeCode" label="作用域" width="150" show-overflow-tooltip/>
        <el-table-column prop="memoryLevel" label="层级" width="70"/>
        <el-table-column label="记忆内容" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">{{ row.content }}</template>
        </el-table-column>
        <el-table-column label="来源" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.sourceType || '—' }} · {{ row.sourceRef || '无引用' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'warning'" size="small">
              {{ row.status === 'ACTIVE' ? '可召回' : '已暂停' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button
              link size="small" :type="row.status === 'ACTIVE' ? 'warning' : 'success'"
              @click="changeManagedStatus(row)">
              {{ row.status === 'ACTIVE' ? '暂停' : '恢复' }}
            </el-button>
            <el-button link type="danger" size="small" @click="forgetManaged(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="mt-2 flex items-center justify-between text-xs text-ink-500">
        <span>暂停记录不会进入 Agent Recall。</span>
        <el-button v-if="memoryHasMore" link type="primary" size="small" :loading="memoryManagerLoading" @click="loadMoreManagedMemories">加载更多</el-button>
      </div>
    </el-dialog>

    <el-dialog v-model="memoryEventsVisible" title="记忆治理记录（不含正文）" width="900px">
      <el-table :data="memoryEvents" v-loading="memoryEventsLoading" max-height="480" size="small" border>
        <el-table-column prop="createdTime" label="时间" width="170"/>
        <el-table-column prop="operation" label="操作" width="100"/>
        <el-table-column label="作用域" min-width="180">
          <template #default="{ row }">{{ row.scopeType }} / {{ row.scopeCode }}</template>
        </el-table-column>
        <el-table-column label="状态变化" width="150">
          <template #default="{ row }">{{ row.oldStatus || '—' }} → {{ row.newStatus || '已删除' }}</template>
        </el-table-column>
        <el-table-column prop="operatorId" label="操作人 ID" width="100"/>
        <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip/>
      </el-table>
    </el-dialog>

    <el-dialog v-model="secretVisible" title="请立即保存明文密钥" width="520px">
      <p class="kb-muted">明文密钥只会展示这一次，关闭后无法再次获取，只能重新签发。</p>
      <pre class="code-block mt-2">{{ issuedSecret }}</pre>
      <template #footer>
        <el-button size="small" @click="copySecret">复制</el-button>
        <el-button size="small" type="primary" @click="secretVisible = false">我已保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ExternalAuditRow, ExternalKeyRow, ExternalServiceOverview, KnowledgeBase } from '../types'
import {
  createExternalKey, getExternalServices, getMemoryScopeGrants, listExternalAudits, listExternalKeys,
  listExternalPrincipals, assignExternalPrincipal,
  getKnowledgeBaseGrantConfig, replaceKnowledgeBaseGrantConfig,
  forgetManagedMemory, listManagedMemories, replaceMemoryScopeGrants, setManagedMemoryStatus, updateExternalMemoryPolicy,
  listMemoryGovernanceEvents, type ManagedMemoryItem, type MemoryGovernanceEvent, type MemoryScopeGrant,
  probeExternal, revokeExternalKey, type ExternalPrincipalSummary,
} from '../api/external'
import { unwrap } from '../api/knowledge'
import { errorMessage as message } from '../utils/feedback'
import { useShell } from '../shell'
import MetricCard from './MetricCard.vue'
import PanelState from './PanelState.vue'
import { copyText } from '../utils/clipboard'

/** focus 由知识集成的 Tab 决定：服务 Tab 看协议与接入示例，凭证 Tab 看 API Key 与调用审计。 */
const props = defineProps<{ bases:KnowledgeBase[]; focus?:'services'|'credentials' }>()
const shell = useShell()
/** 凭证（API Key 的签发、吊销与调用审计）是否可用：由该角色的可见菜单决定，不再靠「是否管理视图」。 */
const canCredentials = computed(() => shell.menuHas('integration.credentials'))
const section = ref<'services'|'credentials'>(
  props.focus === 'credentials' && canCredentials.value ? 'credentials' : 'services')
watch(() => props.focus, value => {
  section.value = value === 'credentials' && canCredentials.value ? 'credentials' : 'services'
})

const overview = ref<ExternalServiceOverview | null>(null)
const keys = ref<ExternalKeyRow[]>([])
const audits = ref<ExternalAuditRow[]>([])
const loading = ref(false)
const auditLoading = ref(false)
const saving = ref(false)
const error = ref('')
/** 最近调用只取几条做健康佐证：全量明细统一在「调用日志」Tab 看。 */
const auditLimit = ref(5)

const createVisible = ref(false)
const principalDialogVisible = ref(false)
const principalSaving = ref(false)
const principalKey = ref<ExternalKeyRow | null>(null)
const principalOptions = ref<ExternalPrincipalSummary[]>([])
const selectedPrincipalId = ref<number | null>(null)
const newPrincipalName = ref('')
const principalChangeReason = ref('')
const knowledgeBaseGrantVisible = ref(false)
const knowledgeBaseGrantSaving = ref(false)
const knowledgeBaseGrantKey = ref<ExternalKeyRow | null>(null)
const selectedKnowledgeBaseIds = ref<number[]>([])
const grantExpiryByKnowledgeBase = ref<Record<number, string>>({})
const knowledgeBaseGrantReason = ref('')
const selectedGrantBases = computed(() => props.bases.filter(base => selectedKnowledgeBaseIds.value.includes(base.id)))
const memoryAccessVisible = ref(false)
const memoryAccessSaving = ref(false)
const memoryAccessKey = ref<ExternalKeyRow | null>(null)
const memoryGrants = ref<MemoryScopeGrant[]>([])
const memoryPolicyVisible = ref(false)
const memoryPolicySaving = ref(false)
const memoryPolicyKey = ref<ExternalKeyRow | null>(null)
const memoryPolicy = ref({ readEnabled:false, writeEnabled:false })
const memoryManagerVisible = ref(false)
const memoryManagerLoading = ref(false)
const memoryManagerKey = ref<ExternalKeyRow | null>(null)
const managedMemories = ref<ManagedMemoryItem[]>([])
const memoryStatusFilter = ref('')
const memoryTypeFilter = ref('')
const memoryCodeFilter = ref('')
const memoryOffset = ref(0)
const memoryHasMore = ref(false)
const memoryEventsVisible = ref(false)
const memoryEventsLoading = ref(false)
const memoryEvents = ref<MemoryGovernanceEvent[]>([])
const secretVisible = ref(false)
const issuedSecret = ref('')
const form = ref<{
  name:string; knowledgeBaseId:number | null; scopes:string[]
  rateLimitPerMinute:number; expiresAt:string; memoryNamespace:string; memoryAgentCode:string
}>({ name:'', knowledgeBaseId:null, scopes:['READ'], rateLimitPerMinute:60, expiresAt:'', memoryNamespace:'', memoryAgentCode:'' })

const exampleTab = ref<'rest' | 'mcp' | 'sse'>('rest')
const probeSecret = ref('')
const probing = ref(false)
const probeResult = ref('')
const probeOk = ref(false)

const restExample = computed(() => `curl -X POST '${origin()}/api/v1/external/query' \\
  -H 'Content-Type: application/json' \\
  -H 'X-API-Key: <YOUR_API_KEY>' \\
  -d '{"knowledgeBaseId":1,"question":"报销流程是什么？","topK":10,"answerFormat":"markdown","minScore":0.6,"responseMode":"full"}'`)
/** answerFormat 可整个省掉（不传即默认格式）：default / markdown / text / html。 */
const answerFormatHint = '答案返回格式：default（不传即此项，模型原文）/ markdown（正文 + 引用原文章节）/ text（纯文本）/ html（含引用原文）；响应里的 answerFormat 是实际生效的那个'
/** minScore 同样可整个省掉：不传即不过滤，与以前完全一致。 */
const minScoreHint = 'minScore：最低相关度（0~1，含端点），低于该分数的候选不参与答案生成；/search 用它过滤候选，返回的 returned 是过滤后条数'
/** responseMode 可整个省掉：不传即 full，与以前完全一致。 */
const responseModeHint = 'responseMode：返回详略，minimal（/query 只给 answer 与数量口径，/search 只给 evidence）/ basic（补回 candidates，/query 另给 selectedContext）/ full（全部字段，默认）'
/** 只在 SSE 页签下出现：content 是 /stream/query 独有的交付方式开关。 */
const contentHint = 'content：stream（默认）逐片推 delta；final 只推检索过程（stage / citation），答案在 done.answer 里整体交付——与控制台阻塞式拿到的是同一份加工产物'
const mcpExample = computed(() => `{
  "mcpServers": {
    "knowledge-os": {
      "url": "${origin()}/api/mcp",
      "headers": { "X-API-Key": "<YOUR_API_KEY>" }
    }
  }
}
# tools: knowledge_query / knowledge_search / list_knowledge_bases ...
# tools/call 的 arguments 支持 minScore（0~1，最低相关度）`)
const sseExample = computed(() => `const es = new EventSource(
  '${origin()}/api/v1/external/stream/query?knowledgeBaseId=1'
  + '&question=' + encodeURIComponent('报销流程是什么？')
  + '&answerFormat=markdown'
  + '&content=final'   // stream=逐片推 delta；final=只推检索过程，答案在 done 里整体交付
  + '&minScore=0.6'    // 可选，最低相关度；不传即不过滤
  + '&apiKey=' + encodeURIComponent('<YOUR_API_KEY>'))
es.addEventListener('stage', e => console.log('阶段', JSON.parse(e.data)))
es.addEventListener('delta', e => process.stdout.write(e.data))   // content=final 时不会有此事件
es.addEventListener('citation', e => console.log('引用', JSON.parse(e.data)))
es.addEventListener('done', e => { console.log('完成', JSON.parse(e.data)); es.close() })`)

function origin() {
  return typeof window === 'undefined' ? '' : window.location.origin
}

function scopeList(row:ExternalKeyRow) {
  return String(row.scopes || 'READ').split(',').filter(Boolean)
}
function hasMemoryScope(row:ExternalKeyRow) {
  // Keep governance actions available when both runtime capabilities are disabled.
  return hasMemoryCapability(row)
}
function hasDeclaredScope(row:ExternalKeyRow, scope:string) {
  return scopeList(row).includes(scope)
}
function hasMemoryCapability(row:ExternalKeyRow) {
  return hasDeclaredScope(row, 'MEMORY_READ') || hasDeclaredScope(row, 'MEMORY_WRITE')
}
function scopeLabel(scope:string) {
  const labels:Record<string,string> = {
    READ:'读取全部（兼容权限组）', LIST:'列出知识库', QUERY:'知识问答', SEARCH:'知识检索',
    READ_OBJECT:'读取知识对象与证据', WRITE:'知识写入（WRITE）', INGEST:'文本接入（INGEST）',
    MEMORY_READ:'记忆读取', MEMORY_WRITE:'记忆写入',
  }
  return labels[scope] || scope
}
function channelType(channel:string) {
  if (channel === 'MCP') return 'warning' as const
  if (channel === 'SSE') return 'success' as const
  return 'primary' as const
}

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    overview.value = unwrap(await getExternalServices(), null)
    keys.value = unwrap(await listExternalKeys(), [])
  } catch (e) {
    error.value = message(e)
  } finally {
    loading.value = false
  }
  await loadAudits()
}

async function openKnowledgeBaseGrants(row:ExternalKeyRow) {
  knowledgeBaseGrantKey.value = row
  try {
    const grants = unwrap(await getKnowledgeBaseGrantConfig(row.id), [])
    selectedKnowledgeBaseIds.value = grants.filter(grant => grant.status === 'ACTIVE' && (!grant.expiresAt || Date.parse(grant.expiresAt) > Date.now()))
      .map(grant => grant.knowledgeBaseId)
    grantExpiryByKnowledgeBase.value = Object.fromEntries(grants.map(grant => [grant.knowledgeBaseId, grant.expiresAt || '']))
    knowledgeBaseGrantReason.value = ''
    knowledgeBaseGrantVisible.value = true
  } catch (e) { ElMessage.error(message(e)) }
}
async function saveKnowledgeBaseGrants() {
  const key = knowledgeBaseGrantKey.value
  if (!key) return
  knowledgeBaseGrantSaving.value = true
  try {
    unwrap(await replaceKnowledgeBaseGrantConfig(key.id, selectedKnowledgeBaseIds.value.map(knowledgeBaseId => ({
      knowledgeBaseId,
      expiresAt:grantExpiryByKnowledgeBase.value[knowledgeBaseId] || null,
      reason:knowledgeBaseGrantReason.value.trim() || null,
    }))), [])
    knowledgeBaseGrantVisible.value = false
    ElMessage.success('知识库授权已更新')
    await loadAll()
  } catch (e) { ElMessage.error(message(e)) }
  finally { knowledgeBaseGrantSaving.value = false }
}

async function openPrincipalDialog(row:ExternalKeyRow) {
  principalKey.value = row
  selectedPrincipalId.value = null
  newPrincipalName.value = ''
  principalChangeReason.value = ''
  try {
    principalOptions.value = unwrap(await listExternalPrincipals(), [])
    principalDialogVisible.value = true
  } catch (e) { ElMessage.error(message(e)) }
}
async function savePrincipalAssignment() {
  const key = principalKey.value
  const reason = principalChangeReason.value.trim()
  const name = newPrincipalName.value.trim()
  if (!key) return
  if (!reason) { ElMessage.warning('请填写主体变更原因'); return }
  if (!selectedPrincipalId.value && !name) { ElMessage.warning('请选择现有主体或填写新主体名称'); return }
  try {
    const target = name || principalOptions.value.find(item => item.id === selectedPrincipalId.value)?.name || '目标主体'
    await ElMessageBox.confirm(`更新后，该 Key 将使用「${target}」主体的知识库授权策略，是否继续？`, '确认切换共享主体', {
      type:'warning', confirmButtonText:'确认切换', cancelButtonText:'返回修改',
    })
  } catch { return }
  principalSaving.value = true
  try {
    await assignExternalPrincipal(key.id, {
      principalId:selectedPrincipalId.value || undefined,
      name:name || undefined,
      reason,
    })
    principalDialogVisible.value = false
    ElMessage.success('共享主体已更新')
    await loadAll()
  } catch (e) { ElMessage.error(message(e)) }
  finally { principalSaving.value = false }
}

async function loadAudits() {
  auditLoading.value = true
  try {
    audits.value = unwrap(await listExternalAudits(null, auditLimit.value), [])
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    auditLoading.value = false
  }
}

function openCreate() {
  form.value = {
    name:'', knowledgeBaseId:null, scopes:['READ'], rateLimitPerMinute:60, expiresAt:'', memoryNamespace:'', memoryAgentCode:'',
  }
  createVisible.value = true
}

async function openMemoryAccess(row:ExternalKeyRow) {
  memoryAccessKey.value = row
  try {
    memoryGrants.value = unwrap(await getMemoryScopeGrants(row.id), [])
      .map(grant => ({ ...grant, canRead:Boolean(grant.canRead), canWrite:Boolean(grant.canWrite) }))
    memoryAccessVisible.value = true
  } catch (e) { ElMessage.error(message(e)) }
}
function openMemoryPolicy(row:ExternalKeyRow) {
  memoryPolicyKey.value = row
  memoryPolicy.value = {
    readEnabled:Boolean(row.memoryReadEnabled ?? hasDeclaredScope(row, 'MEMORY_READ')),
    writeEnabled:Boolean(row.memoryWriteEnabled ?? hasDeclaredScope(row, 'MEMORY_WRITE')),
  }
  memoryPolicyVisible.value = true
}
async function saveMemoryPolicy() {
  const key = memoryPolicyKey.value
  if (!key) return
  memoryPolicySaving.value = true
  try {
    await updateExternalMemoryPolicy(key.id, memoryPolicy.value)
    memoryPolicyVisible.value = false
    ElMessage.success('API Key 记忆权限已更新')
    await loadAll()
  } catch (e) { ElMessage.error(message(e)) }
  finally { memoryPolicySaving.value = false }
}
function addMemoryGrant() {
  memoryGrants.value.push({ scopeType:'PROJECT', scopeCode:'', canRead:true, canWrite:false })
}
async function saveMemoryAccess() {
  if (!memoryAccessKey.value) return
  memoryAccessSaving.value = true
  try {
    unwrap(await replaceMemoryScopeGrants(memoryAccessKey.value.id, memoryGrants.value), [])
    memoryAccessVisible.value = false
    ElMessage.success('共享记忆授权已更新')
  } catch (e) { ElMessage.error(message(e)) }
  finally { memoryAccessSaving.value = false }
}

async function openMemoryManager(row:ExternalKeyRow) {
  memoryManagerKey.value = row
  memoryStatusFilter.value = ''
  memoryTypeFilter.value = ''
  memoryCodeFilter.value = ''
  memoryManagerVisible.value = true
  await loadManagedMemories()
}
async function loadManagedMemories(append = false) {
  if (!memoryManagerKey.value) return
  if (!append) memoryOffset.value = 0
  memoryManagerLoading.value = true
  try {
    const result = unwrap(await listManagedMemories(memoryManagerKey.value.id, {
      status:memoryStatusFilter.value || undefined,
      scopeType:memoryTypeFilter.value || undefined,
      scopeCode:memoryCodeFilter.value.trim() || undefined,
      limit:200, offset:memoryOffset.value,
    }), null)
    const items = result?.items || []
    managedMemories.value = append ? managedMemories.value.concat(items) : items
    memoryOffset.value += items.length
    memoryHasMore.value = items.length === 200
  } catch (e) { ElMessage.error(message(e)) }
  finally { memoryManagerLoading.value = false }
}
async function loadMoreManagedMemories() { await loadManagedMemories(true) }
async function openMemoryEvents() {
  if (!memoryManagerKey.value) return
  memoryEventsVisible.value = true
  memoryEventsLoading.value = true
  try {
    const result = unwrap(await listMemoryGovernanceEvents(memoryManagerKey.value.id), null)
    memoryEvents.value = result?.items || []
  } catch (e) { ElMessage.error(message(e)) }
  finally { memoryEventsLoading.value = false }
}
async function changeManagedStatus(row:ManagedMemoryItem) {
  if (!memoryManagerKey.value) return
  const target = row.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE'
  try {
    const { value } = await ElMessageBox.prompt('该原因会进入记忆治理审计记录。', target === 'ACTIVE' ? '恢复记忆' : '暂停记忆', {
      inputPlaceholder:'填写处理原因', inputValidator:value => Boolean(value?.trim()), confirmButtonText:'确认', cancelButtonText:'取消',
    })
    await setManagedMemoryStatus(memoryManagerKey.value.id, row.memoryCode, target, value)
    ElMessage.success(target === 'ACTIVE' ? '记忆已恢复' : '记忆已暂停')
    await loadManagedMemories()
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(message(e)) }
}
async function forgetManaged(row:ManagedMemoryItem) {
  if (!memoryManagerKey.value) return
  try {
    await ElMessageBox.confirm('删除后记忆正文无法恢复；系统只保留不含正文的操作记录。', '物理删除记忆', { type:'warning' })
    const { value } = await ElMessageBox.prompt('填写删除原因。', '删除原因', {
      inputPlaceholder:'填写处理原因', inputValidator:value => Boolean(value?.trim()), confirmButtonText:'删除', cancelButtonText:'取消',
    })
    await forgetManagedMemory(memoryManagerKey.value.id, row.memoryCode, value)
    ElMessage.success('记忆已删除')
    await loadManagedMemories()
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(message(e)) }
}

async function saveKey() {
  saving.value = true
  try {
    const created = unwrap(await createExternalKey({
      name:form.value.name,
      knowledgeBaseId:form.value.knowledgeBaseId,
      scopes:form.value.scopes,
      rateLimitPerMinute:form.value.rateLimitPerMinute,
      expiresAt:form.value.expiresAt.trim() || null,
      memoryNamespace:form.value.memoryNamespace.trim() || null,
      memoryAgentCode:form.value.memoryAgentCode.trim() || null,
    }), null)
    createVisible.value = false
    issuedSecret.value = String(created?.secret || '')
    secretVisible.value = true
    await loadAll()
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    saving.value = false
  }
}

async function revoke(row:ExternalKeyRow) {
  try {
    await ElMessageBox.confirm(
      `吊销「${row.name}」后，使用该凭证的外部系统会立即失去访问能力，确认吊销？`,
      '吊销 API Key',
      { type:'warning', confirmButtonText:'确认吊销', cancelButtonText:'取消' },
    )
  } catch {
    return
  }
  try {
    unwrap(await revokeExternalKey(row.id), null)
    ElMessage.success('凭证已吊销')
    await loadAll()
  } catch (e) {
    ElMessage.error(message(e))
  }
}

async function copySecret() {
  const ok = await copyText(issuedSecret.value)
  if (ok) ElMessage.success('已复制')
  else ElMessage.warning('浏览器未授权剪贴板，请手动复制')
}

/** 自检以外部调用方身份发起：凭证、范围、限流都能在真机上一并验证。 */
async function probe() {
  probing.value = true
  probeResult.value = '正在自检…'
  try {
    const response = await probeExternal(probeSecret.value.trim())
    const payload = response.data as { success?:boolean; message?:string; data?:unknown }
    probeOk.value = payload?.success !== false
    const count = Array.isArray(payload?.data) ? payload.data.length : 0
    probeResult.value = probeOk.value
      ? `凭证有效，可见知识库 ${count} 个`
      : `调用失败：${payload?.message || '未知错误'}`
  } catch (e) {
    probeOk.value = false
    probeResult.value = `调用失败：${message(e)}`
  } finally {
    probing.value = false
  }
}

/** SSE 自检只连 5 秒：确认通道、凭证与心跳后可立即关闭，避免长时间占用连接。 */
function probeSse() {
  const kb = props.bases[0]?.id
  if (!kb) {
    probeResult.value = '缺少知识库，无法自检 SSE'
    probeOk.value = false
    return
  }
  probing.value = true
  probeResult.value = '正在建立 SSE 连接…'
  const url = `/api/v1/external/stream/events?knowledgeBaseId=${kb}`
    + `&apiKey=${encodeURIComponent(probeSecret.value.trim())}`
  const source = new EventSource(url)
  let received = 0
  source.onopen = () => { probeOk.value = true; probeResult.value = 'SSE 连接已建立，正在接收事件…' }
  source.onmessage = () => { received += 1 }
  source.onerror = () => {
    probeOk.value = false
    probeResult.value = 'SSE 连接失败：请检查凭证与 SSE 通道开关'
    source.close()
    probing.value = false
  }
  window.setTimeout(() => {
    source.close()
    probing.value = false
    if (probeOk.value) probeResult.value = `SSE 通道正常，已收到 ${received} 条消息（含心跳注释）`
  }, 5000)
}



onMounted(loadAll)
</script>

<style scoped>
.code-block{white-space:pre-wrap;
word-break:break-all;
border:1px solid var(--line-d);
border-radius:10px;
background:var(--bg-sub);
padding:10px 12px;
font-size:11px;
line-height:1.6;
color:var(--ink-1);
max-height:280px;
overflow:auto}
.w-full{width:100%}
</style>
