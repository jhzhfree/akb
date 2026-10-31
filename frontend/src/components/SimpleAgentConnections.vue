<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">接口服务</div>
        <h1 class="kb-title mt-1">Agent 凭证</h1>
        <p class="kb-muted mt-1">
          每条 Agent 凭证包含服务 Key、可查询知识库和已登记 Agent。服务 Key
          可用于 MCP 连接与工具发现， 调用工具时必须提供有效的 Agent
          身份签名。点「详情」管理服务 Key、知识库范围和
          Agent。撤销后列表默认隐藏，可勾选查看；
          撤销只是停用并留痕，彻底删除才会把数据清掉。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <label class="kb-muted flex items-center gap-1 text-[12px]"
          ><input v-model="showRevoked" type="checkbox" /> 显示已撤销</label
        >
        <button class="kb-btn-outline" :disabled="busy" @click="load">
          刷新
        </button>
      </div>
    </header>

    <article class="kb-card mt-4">
      <div class="kb-card-head">
        <b class="text-sm">凭证列表</b>
        <span class="kb-muted">{{ visibleRows.length }} 条</span>
        <button class="kb-btn-primary" :disabled="busy" @click="openCreate">
          新建凭证
        </button>
      </div>
      <div class="p-3">
        <PanelState
          :loading="busy && !rows.length"
          :error="error"
          :empty="!visibleRows.length"
          empty-text="还没有 Agent 凭证"
          empty-hint="新建凭证后，把生成的服务 Key 填到调用平台，再登记 Agent 公钥"
          retryable
          @retry="load"
        >
          <el-table :data="visibleRows" border size="small">
            <el-table-column
              prop="name"
              label="凭证名称"
              min-width="160"
              show-overflow-tooltip
            />
            <el-table-column
              label="AKB 对外地址"
              min-width="200"
              show-overflow-tooltip
            >
              <template #default="{ row }">{{
                row.resource_origin || "—"
              }}</template>
            </el-table-column>
            <el-table-column label="状态" width="96">
              <template #default="{ row }">
                <el-tag
                  size="small"
                  :type="row.status === 'ACTIVE' ? 'success' : 'info'"
                  >{{ statusLabel(row.status) }}</el-tag
                >
              </template>
            </el-table-column>
            <el-table-column
              label="可读知识库"
              min-width="160"
              show-overflow-tooltip
            >
              <template #default="{ row }">{{
                baseNames(row.knowledgeBaseIds) || "—"
              }}</template>
            </el-table-column>
            <el-table-column label="服务 Key" width="110">
              <template #default="{ row }">{{ activeKeyCount(row) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="260" fixed="right">
              <template #default="{ row }">
                <el-button link size="small" @click="openDetail(row)"
                  >详情</el-button
                >
                <el-button
                  v-if="row.status !== 'REVOKED'"
                  link
                  size="small"
                  @click="openEdit(row)"
                  >编辑</el-button
                >
                <el-button
                  v-if="row.status !== 'REVOKED'"
                  link
                  type="danger"
                  size="small"
                  @click="revokeConnection(row)"
                  >撤销</el-button
                >
                <el-button
                  v-else
                  link
                  type="danger"
                  size="small"
                  @click="purgeConnection(row)"
                  >彻底删除</el-button
                >
              </template>
            </el-table-column>
          </el-table>
        </PanelState>
      </div>
    </article>

    <el-drawer
      v-model="detailOpen"
      :title="detail ? detail.name : '凭证详情'"
      size="62%"
      @closed="detail = null"
    >
      <div v-if="detail" v-loading="detailLoading">
        <el-tabs v-model="tab" @tab-change="onTabChange">
          <el-tab-pane label="概览与自检" name="overview">
            <div class="space-y-4">
              <div class="grid grid-cols-[88px_1fr] gap-y-2 text-[12px]">
                <b class="font-normal text-ink-500">AKB 地址</b>
                <span class="kb-muted break-all">{{
                  detail.resource_origin || "—"
                }}</span>
                <b class="font-normal text-ink-500">状态</b>
                <span class="kb-muted">{{ statusLabel(detail.status) }}</span>
                <b class="font-normal text-ink-500">授权方式</b>
                <span class="kb-muted">{{
                  authorizationLabel(detail.authorization_mode ?? "")
                }}</span>
              </div>

              <div
                v-if="detail.authorization_mode === 'REVIEW_REQUIRED'"
                class="rounded border border-black/10 p-3"
              >
                <b class="text-[12px]">历史接入需确认授权方式</b>
                <el-radio-group v-model="reviewMode" class="mt-2">
                  <el-radio value="SERVICE_SCOPE">继承平台权限</el-radio>
                  <el-radio value="AGENT_INTERSECTION"
                    >平台与 Agent 权限交集</el-radio
                  >
                </el-radio-group>
                <el-input
                  v-model="reviewReason"
                  class="mt-2"
                  placeholder="确认原因（必填，写入审计）"
                />
                <el-button
                  class="mt-2"
                  size="small"
                  @click="reviewAuthorization"
                  >提交确认</el-button
                >
              </div>

              <div>
                <div class="flex items-center gap-2">
                  <b class="text-[12px]">接入自检</b>
                  <el-button link size="small" @click="runCheck"
                    >检查</el-button
                  >
                </div>
                <ul v-if="checklist.length" class="mt-2 kb-muted">
                  <li v-for="item in checklist" :key="item.item">
                    {{ item.ok ? "✓" : "✗" }} {{ item.item }}
                    <span v-if="!item.ok && item.hint" class="text-amber-600"
                      >— {{ item.hint }}</span
                    >
                  </li>
                </ul>
                <p v-else class="mt-2 kb-muted">
                  点「检查」看看这条接入还缺什么。
                </p>
              </div>

              <div v-if="detail.status !== 'REVOKED'" class="flex gap-2">
                <el-button size="small" @click="connectionStatus">{{
                  detail.status === "ACTIVE" ? "停用接入" : "启用接入"
                }}</el-button>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="服务 Key" name="keys">
            <div class="space-y-3">
              <div>
                <p class="kb-muted mb-2">
                  本凭证下的服务 Key 共用此范围；修改后立即生效，Agent
                  访问仍受此范围限制。
                </p>
                <b class="text-[12px]">服务 Key 可查询知识库</b>
                <el-select
                  v-model="scopeIds"
                  multiple
                  filterable
                  class="mt-2 w-full"
                  placeholder="选择可查询的知识库（可多选、可修改）"
                >
                  <el-option
                    v-for="b in bases"
                    :key="b.id"
                    :label="b.name"
                    :value="b.baseCode"
                  />
                </el-select>
                <el-button
                  class="mt-2"
                  size="small"
                  type="primary"
                  @click="saveScope"
                  >保存知识库范围</el-button
                >
              </div>

              <div class="flex items-center justify-between">
                <span class="kb-muted"
                  >服务 Key
                  明文只在签发时显示一次；可用于连接与工具列表发现，执行工具仍需
                  Agent 验签。</span
                >
                <el-button size="small" type="primary" @click="issue"
                  >签发新 Key</el-button
                >
              </div>
              <el-table :data="serviceKeys" border size="small">
                <el-table-column
                  prop="name"
                  label="名称"
                  min-width="140"
                  show-overflow-tooltip
                />
                <el-table-column prop="keyPrefix" label="前缀" width="120" />
                <el-table-column label="状态" width="90">
                  <template #default="{ row }">{{
                    row.status === "ACTIVE" ? "生效中" : "已吊销"
                  }}</template>
                </el-table-column>
                <el-table-column label="到期时间" width="120">
                  <template #default="{ row }">{{
                    row.expiresAt ? String(row.expiresAt).slice(0, 10) : "—"
                  }}</template>
                </el-table-column>
                <el-table-column label="最近使用" width="170">
                  <template #default="{ row }">{{
                    row.lastUsedAt ? String(row.lastUsedAt).slice(0, 19) : "—"
                  }}</template>
                </el-table-column>
                <el-table-column label="操作" width="160" fixed="right">
                  <template #default="{ row }">
                    <el-button
                      v-if="row.status === 'ACTIVE'"
                      link
                      size="small"
                      @click="rotate(row.id)"
                      >轮换</el-button
                    >
                    <el-button
                      v-if="row.status === 'ACTIVE'"
                      link
                      type="danger"
                      size="small"
                      @click="revoke(row.id)"
                      >吊销</el-button
                    >
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-tab-pane>

          <el-tab-pane :label="`Agent（${agents.length}）`" name="agents">
            <div class="space-y-3">
              <div class="flex items-center justify-between">
                <span class="kb-muted"
                  >登记 Agent 的公钥，可同时保留两把便于轮换。</span
                >
                <el-button size="small" type="primary" @click="agentOpen = true"
                  >登记 Agent</el-button
                >
              </div>
              <el-table :data="agents" border size="small">
                <el-table-column
                  prop="name"
                  label="Agent"
                  min-width="140"
                  show-overflow-tooltip
                />
                <el-table-column
                  prop="external_agent_id"
                  label="Agent ID"
                  min-width="200"
                  show-overflow-tooltip
                />
                <el-table-column label="公钥（kid）" min-width="200">
                  <template #default="{ row }">
                    <div
                      v-for="k in row.keys"
                      :key="k.kid"
                      class="flex items-center gap-2"
                    >
                      <span class="font-mono">{{ k.kid.slice(0, 12) }}…</span>
                      <span class="kb-muted">{{
                        k.status === "ACTIVE" ? "生效中" : k.status
                      }}</span>
                      <el-button
                        v-if="k.status !== 'REVOKED'"
                        link
                        type="danger"
                        size="small"
                        @click="revokePublic(row.id, k.kid)"
                        >撤销</el-button
                      >
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="90">
                  <template #default="{ row }">{{
                    row.status === "ACTIVE" ? "启用" : "停用"
                  }}</template>
                </el-table-column>
                <el-table-column label="操作" width="180" fixed="right">
                  <template #default="{ row }">
                    <el-button
                      link
                      size="small"
                      @click="copyAgentId(row.external_agent_id)"
                      >复制 ID</el-button
                    >
                    <el-button link size="small" @click="agentStatus(row)">{{
                      row.status === "ACTIVE" ? "停用" : "启用"
                    }}</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-tab-pane>

          <el-tab-pane label="调用日志" name="calls">
            <div class="space-y-3">
              <div class="flex items-center justify-between">
                <span class="kb-muted"
                  >这条接入最近的调用记录（按应用主体筛）。</span
                >
                <div class="flex items-center gap-2">
                  <select v-model.number="callLimit" class="kb-input w-24">
                    <option v-for="n in [20, 50, 100]" :key="n" :value="n">
                      {{ n }} 条
                    </option>
                  </select>
                  <el-button size="small" @click="loadCalls">刷新</el-button>
                </div>
              </div>
              <el-table
                :data="calls"
                border
                size="small"
                v-loading="callsLoading"
              >
                <el-table-column label="时间" width="160">
                  <template #default="{ row }">{{
                    row.createdTime ? String(row.createdTime).slice(0, 19) : "—"
                  }}</template>
                </el-table-column>
                <el-table-column prop="channel" label="通道" width="90" />
                <el-table-column
                  prop="callerName"
                  label="调用方"
                  min-width="140"
                  show-overflow-tooltip
                />
                <el-table-column
                  prop="method"
                  label="方法"
                  min-width="150"
                  show-overflow-tooltip
                />
                <el-table-column label="HTTP" width="90">
                  <template #default="{ row }">
                    <span
                      :class="
                        row.statusCode != null && row.statusCode >= 400
                          ? 'text-[color:var(--danger)]'
                          : 'text-[color:var(--ok)]'
                      "
                      >{{ row.statusCode ?? "—" }}</span
                    >
                  </template>
                </el-table-column>
                <el-table-column label="耗时" width="90">
                  <template #default="{ row }"
                    >{{ row.durationMs ?? 0 }} ms</template
                  >
                </el-table-column>
                <el-table-column
                  prop="message"
                  label="错误信息"
                  min-width="180"
                  show-overflow-tooltip
                />
                <template #empty>
                  <span class="kb-muted">这条接入还没有调用记录</span>
                </template>
              </el-table>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-drawer>

    <el-dialog
      v-model="formOpen"
      :title="editing ? '编辑凭证' : '新建凭证'"
      width="560px"
    >
      <el-form label-position="top">
        <el-form-item label="凭证名称"
          ><el-input v-model="formName" maxlength="128"
        /></el-form-item>
        <el-form-item label="AKB 对外地址"
          ><el-input
            v-model="formOrigin"
            placeholder="https://akb.example.com"
          /><small
            >填写调用平台实际访问的根地址，不要附加 /api
            路径，需与调用平台一致。</small
          >
          <p v-if="loopbackHint" class="mt-1 text-[11px] text-amber-600">
            {{ loopbackHint }}
          </p></el-form-item
        >
        <el-form-item v-if="!editing" label="可读知识库"
          ><el-select
            v-model="formScope"
            multiple
            filterable
            class="w-full"
            placeholder="选择该接入可读的知识库"
            ><el-option
              v-for="b in bases"
              :key="b.id"
              :label="b.name"
              :value="b.baseCode" /></el-select
        ></el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="formOpen = false">取消</el-button
        ><el-button
          type="primary"
          :loading="busy"
          :disabled="!formName.trim() || !formOrigin.trim()"
          @click="submitForm"
          >{{ editing ? "保存" : "创建并生成 Key" }}</el-button
        ></template
      >
    </el-dialog>

    <el-dialog
      v-model="agentOpen"
      title="登记 Agent 公钥"
      width="560px"
      @closed="publicText = ''"
    >
      <el-form label-position="top"
        ><el-form-item label="Agent 名称"
          ><el-input v-model="agentName" maxlength="128" /></el-form-item
        ><el-form-item label="Agent ID"
          ><el-input
            v-model="agentId"
            placeholder="密钥工具生成的 UUID；与调用平台填写一致" /></el-form-item
        ><el-form-item label="公钥 public.jwk"
          ><input
            type="file"
            accept=".jwk,.json"
            @change="readPublic" /><el-input
            v-model="publicText"
            type="textarea"
            :rows="5"
            placeholder="只接受 RSA 公钥 JWK，不接受私钥"
            class="mt-2" /></el-form-item
        ><el-alert
          title="换公钥时填写原 Agent ID；可同时保留两把公钥，测试新密钥成功后撤销旧公钥。"
          type="info"
          :closable="false" /></el-form
      ><template #footer
        ><el-button @click="agentOpen = false">取消</el-button
        ><el-button
          type="primary"
          :disabled="!agentName.trim() || !agentId.trim() || !publicText.trim()"
          @click="register"
          >保存公钥</el-button
        ></template
      >
    </el-dialog>

    <el-dialog
      v-model="secretOpen"
      title="请现在保存服务 Key"
      width="560px"
      @closed="secret = ''"
      :close-on-click-modal="false"
      ><el-alert
        title="明文只显示这一次。复制到调用平台；AKB 只保存摘要。有效期一年，可随时轮换或撤销。"
        type="warning"
        :closable="false"
      /><el-input :model-value="secret" readonly class="mt-4" /><template
        #footer
        ><el-button @click="copySecret">复制</el-button
        ><el-button type="primary" @click="secretOpen = false"
          >已安全保存</el-button
        ></template
      ></el-dialog
    >
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import axios from "axios";
import { ElMessage } from "element-plus";
import { getMe } from "../api/auth";
import {
  listPrincipalKeys,
  rotateExternalKey,
  revokeExternalKey,
} from "../api/external";
import {
  type WorkloadPolicy,
  type WorkloadAgent,
  type WorkloadAgentKey,
} from "../api/workload";
import type { KnowledgeBase, ExternalKeyRow } from "../types";
import { confirmAction, errorMessage } from "../utils/feedback";
import { copyText } from "../utils/clipboard";
import PanelState from "./PanelState.vue";
import intranetRules from "../utils/intranet-rules.mjs";
const props = defineProps<{ bases: KnowledgeBase[] }>();
const reviewMode = ref<"SERVICE_SCOPE" | "AGENT_INTERSECTION">(
  "AGENT_INTERSECTION",
);
const reviewReason = ref("");
const platformType = ref("custom");
type Connection = WorkloadPolicy & {
  name: string;
  knowledgeBaseIds: string[];
  platform_type?: string;
  authorization_mode?: string;
  keys?: { status?: string }[];
};
type Agent = WorkloadAgent & { name: string; keys: WorkloadAgentKey[] };
/** 审计行直接来自后端 ApiCallAudit：字段缺失时显示占位，不猜。 */
interface CallRow {
  createdTime?: string;
  channel?: string;
  callerName?: string;
  method?: string;
  statusCode?: number | null;
  durationMs?: number | null;
  message?: string | null;
}
const root = "/api/external/admin/agent-connections";
const admin = ref(false),
  busy = ref(false),
  error = ref(""),
  rows = ref<Connection[]>([]),
  showRevoked = ref(false);
const detail = ref<Connection | null>(null),
  detailOpen = ref(false),
  detailLoading = ref(false),
  tab = ref("overview"),
  agents = ref<Agent[]>([]),
  serviceKeys = ref<ExternalKeyRow[]>([]),
  checklist = ref<{ item: string; ok: boolean; hint: string }[]>([]),
  calls = ref<CallRow[]>([]),
  callsLoading = ref(false),
  callLimit = ref(50);
const scopeIds = ref<string[]>([]),
  formOpen = ref(false),
  editing = ref<Connection | null>(null),
  formName = ref(""),
  formOrigin = ref(window.location.origin),
  formScope = ref<string[]>([]);
const agentOpen = ref(false),
  secretOpen = ref(false),
  secret = ref(""),
  agentName = ref(""),
  agentId = ref(""),
  publicText = ref("");
const visibleRows = computed(() =>
  showRevoked.value
    ? rows.value
    : rows.value.filter((r) => r.status !== "REVOKED"),
);
function statusLabel(status?: string) {
  return (
    (
      { ACTIVE: "启用", SUSPENDED: "停用", REVOKED: "已撤销" } as Record<
        string,
        string
      >
    )[status ?? ""] ??
    (status || "未知")
  );
}
function authorizationLabel(mode: string) {
  return (
    (
      {
        SERVICE_SCOPE: "继承平台权限",
        AGENT_INTERSECTION: "平台与 Agent 权限交集",
        REVIEW_REQUIRED: "待确认",
      } as Record<string, string>
    )[mode] ?? "未设置"
  );
}
function baseNames(codes: string[]) {
  return (codes ?? [])
    .map((code) => props.bases.find((b) => b.baseCode === code)?.name)
    .filter(Boolean)
    .join("、");
}
function activeKeyCount(row: Connection) {
  const all = row.keys ?? [];
  const active = all.filter((k) => k.status === "ACTIVE").length;
  return all.length ? `${active}/${all.length} 生效` : "—";
}
/**
 * 本机明文要提醒两件事：后端默认不收（WORKLOAD_ALLOW_LOOPBACK_HTTP 默认关），以及「本机」是
 * 相对平台而言的——平台与 AKB 不同机时 localhost 指向平台自己。这个地址默认从浏览器地址带出来，
 * 所以在 dev 模式下打开控制台时这条提示经常用得上。
 */
const loopbackHint = computed(() => {
  const value = formOrigin.value.trim();
  if (!value) return "";
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return "";
  }
  if (url.protocol !== "http:") return "";
  const host = url.hostname.toLowerCase();
  const loopback = intranetRules.loopback ?? {};
  const isLoopback =
    (loopback.hosts ?? []).some((h) => host === h.toLowerCase()) ||
    (loopback.ipv4Prefixes ?? []).some((prefix) => host.startsWith(prefix));
  if (!isLoopback) return "";
  return (
    `这是本机地址：后端要开启 WORKLOAD_ALLOW_LOOPBACK_HTTP=true 才会接受；而且「本机」` +
    `是相对平台而言的——平台与 AKB 不在同一台主机时，localhost 指向的是平台自己，` +
    `请改填 AKB 实际可达的地址。`
  );
});
async function act(fn: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await fn();
  } catch (e) {
    error.value = errorMessage(e);
    ElMessage.error(error.value);
  } finally {
    busy.value = false;
  }
}
async function load() {
  const r = await axios.get(root);
  rows.value = r.data.data ?? [];
  const current = detail.value;
  if (current) {
    const fresh = rows.value.find((c) => c.id === current.id);
    if (fresh) detail.value = fresh;
    else {
      detailOpen.value = false;
      detail.value = null;
    }
  }
}
/** 抽屉数据按需加载：Agent、服务 Key 各一次请求；切到「调用日志」时才拉日志。 */
async function loadDetail() {
  const current = detail.value;
  if (!current) return;
  detailLoading.value = true;
  try {
    scopeIds.value = [...(current.knowledgeBaseIds ?? [])];
    reviewMode.value = "AGENT_INTERSECTION";
    reviewReason.value = "";
    const [a, k] = await Promise.all([
      axios.get(`${root}/${current.id}/agents`),
      listPrincipalKeys(current.application_principal_id),
    ]);
    agents.value = a.data.data ?? [];
    serviceKeys.value = k.data.data ?? [];
  } finally {
    detailLoading.value = false;
  }
}
async function openDetail(row: Connection) {
  detail.value = row;
  detailOpen.value = true;
  tab.value = "overview";
  checklist.value = [];
  calls.value = [];
  await act(async () => {
    await loadDetail();
    await runCheck();
  });
}
/** 切到「调用日志」才拉：大多数时候用户只是看概览，没必要每次开抽屉都查一遍审计。 */
function onTabChange(name: string | number) {
  if (name === "calls")
    void act(async () => {
      await loadCalls();
    });
}
async function loadCalls() {
  if (!detail.value) return;
  callsLoading.value = true;
  try {
    const r = await axios.get(`${root}/${detail.value.id}/calls`, {
      params: { limit: callLimit.value },
    });
    calls.value = r.data.data ?? [];
  } finally {
    callsLoading.value = false;
  }
}
function openCreate() {
  editing.value = null;
  formName.value = "";
  formOrigin.value = window.location.origin;
  formScope.value = [];
  formOpen.value = true;
}
function openEdit(row: Connection) {
  editing.value = row;
  formName.value = row.name;
  formOrigin.value = row.resource_origin ?? window.location.origin;
  formOpen.value = true;
}
async function submitForm() {
  await act(async () => {
    const origin = formOrigin.value.trim().replace(/\/$/, "");
    if (editing.value) {
      await axios.put(`${root}/${editing.value.id}`, {
        name: formName.value.trim(),
        resourceOrigin: origin,
      });
      formOpen.value = false;
      await load();
      await loadDetail();
      ElMessage.success("已保存");
      return;
    }
    const r = await axios.post(root, {
      name: formName.value.trim(),
      platformType: platformType.value,
      resourceOrigin: origin,
      knowledgeBaseIds: formScope.value,
    });
    formOpen.value = false;
    showSecret(r.data.data.key.secret);
    await load();
  });
}
/**
 * 删除分两步：撤销只是停用并吊销服务 Key（留痕，还能查到它做过什么），彻底删除才把策略与
 * Agent 登记清掉。所以撤销之后列表里仍能看到它，只是默认隐藏。
 */
async function revokeConnection(row: Connection) {
  if (
    !(await confirm(
      `撤销「${row.name}」后它将立即停用，其服务 Key 一并吊销，调用平台会连接失败。此操作不可恢复，但记录会保留。`,
    ))
  )
    return;
  await act(async () => {
    await axios.delete(`${root}/${row.id}`);
    await load();
    ElMessage.success("接入已撤销");
  });
}
async function purgeConnection(row: Connection) {
  if (
    !(await confirm(
      `彻底删除「${row.name}」会清掉它的策略与 Agent 登记（服务 Key 不删，只解绑）。此操作不可逆，确定继续？`,
    ))
  )
    return;
  await act(async () => {
    await axios.delete(`${root}/${row.id}/purge`);
    await load();
    ElMessage.success("已彻底删除");
  });
}
function showSecret(value: string) {
  secret.value = value;
  secretOpen.value = true;
}
async function reviewAuthorization() {
  await act(async () => {
    if (!detail.value) return;
    try {
      await confirmAction({
        title: "确认授权方式",
        message:
          "继承平台权限会取消 Agent 独立权限限制。此操作将记录审计，且不会自动启用连接。",
      });
    } catch {
      return;
    }
    await axios.put(`${root}/${detail.value.id}/authorization`, {
      authorizationMode: reviewMode.value,
      reason: reviewReason.value.trim(),
      platformType: detail.value.platform_type,
    });
    await load();
    await loadDetail();
  });
}
async function saveScope() {
  await act(async () => {
    if (!detail.value) return;
    await axios.put(`${root}/${detail.value.id}/scope`, {
      knowledgeBaseIds: scopeIds.value,
    });
    await load();
    await loadDetail();
    ElMessage.success("可读范围已保存");
  });
}
async function readPublic(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file) return;
  if (file.size > 16384) {
    ElMessage.error("公钥文件不能超过 16 KiB");
    return;
  }
  publicText.value = await file.text();
}
async function register() {
  await act(async () => {
    if (!detail.value) return;
    if (publicText.value.length > 16384) throw Error("公钥过长");
    const jwk = JSON.parse(publicText.value);
    if (jwk.d || jwk.p || jwk.q || jwk.privateKey)
      throw Error("禁止上传私钥，请选择 public.jwk");
    await axios.post(`${root}/${detail.value.id}/agents`, {
      name: agentName.value,
      agentId: agentId.value.trim(),
      publicJwk: jwk,
    });
    agentOpen.value = false;
    publicText.value = "";
    await loadDetail();
    ElMessage.success("公钥已登记，请回调用平台绑定并测试");
  });
}
async function runCheck() {
  if (!detail.value) return;
  await act(async () => {
    checklist.value = (
      await axios.get(`${root}/${detail.value!.id}/checklist`)
    ).data.data;
  });
}
async function copyAgentId(id: string) {
  // 走封装：内网明文下 navigator.clipboard 不存在
  if (await copyText(id)) ElMessage.success("已复制 Agent ID");
  else ElMessage.info("当前浏览器不支持剪贴板，请手动选中复制");
}
async function issue() {
  await act(async () => {
    if (!detail.value) return;
    const expires = new Date(Date.now() + 364 * 86400000)
      .toISOString()
      .slice(0, 19);
    showSecret(
      (
        await axios.post(`${root}/${detail.value.id}/keys`, {
          name: detail.value.name,
          expiresAt: expires,
        })
      ).data.data.secret,
    );
    await loadDetail();
  });
}
async function confirm(message: string) {
  try {
    await confirmAction({ title: "确认操作", message });
    return true;
  } catch {
    return false;
  }
}
async function rotate(id: number) {
  if (!(await confirm("轮换后旧 Key 立即失效，请同步更新调用平台。"))) return;
  await act(async () => {
    const r = await rotateExternalKey(id);
    showSecret(r.data.data?.secret ?? "");
    await loadDetail();
  });
}
async function revoke(id: number) {
  if (!(await confirm("撤销后，使用此 Key 的 Agent 将无法连接。"))) return;
  await act(async () => {
    await revokeExternalKey(id);
    await loadDetail();
  });
}
async function revokePublic(binding: number, kid: string) {
  if (!(await confirm("撤销后此私钥将不能再认证，且不可恢复。"))) return;
  await act(async () => {
    if (!detail.value) return;
    await axios.put(
      `${root}/${detail.value.id}/agents/${binding}/keys/${encodeURIComponent(kid)}/status`,
      { status: "REVOKED" },
    );
    await loadDetail();
  });
}
async function connectionStatus() {
  if (!detail.value) return;
  if (!(await confirm("确认变更服务接入状态？"))) return;
  await act(async () => {
    if (!detail.value) return;
    await axios.put(`${root}/${detail.value.id}/status`, {
      status: detail.value.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE",
    });
    await load();
    await loadDetail();
  });
}
async function agentStatus(row: Agent) {
  if (!(await confirm("确认变更 Agent 状态？"))) return;
  await act(async () => {
    if (!detail.value) return;
    await axios.put(`${root}/${detail.value.id}/agents/${row.id}/status`, {
      status: row.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE",
    });
    await loadDetail();
  });
}
async function copySecret() {
  // 走封装：内网明文下 navigator.clipboard 不存在，直接取用会抛且没有任何提示
  if (await copyText(secret.value)) ElMessage.success("已复制");
  else ElMessage.info("当前浏览器不支持剪贴板，请手动选中复制");
}
onMounted(() =>
  act(async () => {
    admin.value = (await getMe()).data.data?.user?.role === "ADMIN";
    if (admin.value) await load();
  }),
);
</script>
