<template>
  <section class="kb-page" v-loading="busy">
    <header class="kb-section-head">
      <div>
        <h1 class="kb-title">Agent 接入</h1>
        <p class="kb-muted mt-2">
          创建服务 Key → 登记 Agent 公钥 → 回到调用平台绑定系统 Agent
          并导入私钥。无需配置身份策略。
        </p>
      </div>
      <el-button v-if="admin" type="primary" @click="createOpen = true"
        >新建服务接入</el-button
      >
    </header>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      class="mb-4"
    />
    <el-alert
      v-if="!admin"
      title="仅系统管理员可以管理 Agent 接入"
      type="info"
      :closable="false"
    />
    <template v-else>
      <el-alert
        title="服务 Key 确定可读知识库；公钥识别具体 Agent。私钥只保存在调用平台，切勿上传到 AKB。同一服务 Key 下的 Agent 继承相同权限。"
        type="info"
        :closable="false"
        class="mb-4"
      />
      <el-empty
        v-if="!connections.length"
        description="还没有服务接入，先创建一份共享服务 Key"
      />
      <el-select
        v-if="connections.length"
        :model-value="selected?.id"
        placeholder="选择服务接入"
        class="mb-4"
        @change="selectConnection"
        ><el-option
          v-for="c in connections"
          :key="c.id"
          :label="`${c.name} · ${c.status}`"
          :value="c.id"
      /></el-select>
      <template v-if="selected">
        <article class="kb-card p-4 mb-4">
          <h2 class="font-semibold">
            {{ selected.name }} <el-tag>{{ selected.status }}</el-tag>
          </h2>
          <p class="kb-muted mt-2">AKB 地址：{{ selected.resource_origin }}</p>
          <p class="mt-3">可读知识库</p>
          <el-select v-model="scopeIds" multiple class="mt-2 w-full"
            ><el-option
              v-for="base in bases"
              :key="base.id"
              :label="base.name"
              :value="base.id"
          /></el-select>
          <p class="kb-muted mt-2">
            简化版默认读取所选知识库中未设置权限标签的内容。变更后立即对该接入下所有
            Agent 生效。
          </p>
          <el-button
            class="mt-3"
            :disabled="!scopeIds.length"
            @click="saveScope"
            >保存可读范围</el-button
          >
          <el-button
            class="mt-3"
            :disabled="selected.status === 'REVOKED'"
            @click="connectionStatus"
            >{{
              selected.status === "ACTIVE" ? "停用接入" : "启用接入"
            }}</el-button
          >
          <h3 class="font-semibold mt-4">共享服务 Key</h3>
          <p class="kb-muted">
            复制到调用平台的知识库连接中。明文仅在生成或轮换时显示一次。
          </p>
          <el-table :data="serviceKeys"
            ><el-table-column
              prop="keyPrefix"
              label="Key 前缀"
            /><el-table-column prop="status" label="状态" /><el-table-column
              prop="expiresAt"
              label="到期时间"
            /><el-table-column label="操作"
              ><template #default="{ row }"
                ><el-button
                  v-if="row.status === 'ACTIVE'"
                  link
                  @click="rotate(row.id)"
                  >轮换</el-button
                ><el-button
                  v-if="row.status === 'ACTIVE'"
                  link
                  type="danger"
                  @click="revoke(row.id)"
                  >撤销</el-button
                ></template
              ></el-table-column
            ></el-table
          >
          <el-button
            class="mt-2"
            :disabled="selected.status !== 'ACTIVE'"
            @click="issue"
            >新增共享 Key</el-button
          >
        </article>
        <article class="kb-card p-4">
          <div class="flex justify-between">
            <h2 class="font-semibold">Agent 公钥登记</h2>
            <el-button
              type="primary"
              :disabled="selected.status !== 'ACTIVE'"
              @click="agentOpen = true"
              >添加 Agent</el-button
            >
          </div>
          <p class="kb-muted mt-2">
            用独立工具生成密钥对，将 public.jwk 导入这里。把同一个 Agent ID 和
            private.pem 填入调用平台。
          </p>
          <el-table :data="agents"
            ><el-table-column prop="name" label="名称" /><el-table-column
              prop="external_agent_id"
              label="Agent ID"
              min-width="250"
            /><el-table-column prop="status" label="状态" /><el-table-column
              label="公钥与操作"
              min-width="240"
              ><template #default="{ row }"
                ><div v-for="k in row.keys" :key="k.kid">
                  <small>{{ k.kid }} · {{ k.status }}</small
                  ><el-button
                    v-if="k.status !== 'REVOKED'"
                    link
                    type="danger"
                    @click="revokePublic(row.id, k.kid)"
                    >撤销公钥</el-button
                  >
                </div>
                <el-button
                  v-if="row.status !== 'REVOKED'"
                  link
                  @click="agentStatus(row)"
                  >{{
                    row.status === "ACTIVE" ? "停用 Agent" : "启用 Agent"
                  }}</el-button
                ></template
              ></el-table-column
            ></el-table
          >
        </article>
      </template>
      <el-collapse class="mt-4"
        ><el-collapse-item
          title="已有高级接入记录（兼容旧配置）"
          name="advanced"
          ><WorkloadIdentityPanel :bases="bases" /></el-collapse-item
      ></el-collapse>
    </template>
    <el-dialog v-model="createOpen" title="新建服务接入" width="540px"
      ><el-form label-position="top"
        ><el-form-item label="接入名称"
          ><el-input
            v-model="newName"
            placeholder="例如：Octop 公司知识库"
            maxlength="128" /></el-form-item
        ><el-form-item label="AKB 对外地址"
          ><el-input
            v-model="origin"
            placeholder="https://akb.example.com"
          /><small
            >填写调用平台实际访问的根地址，不要附加 /api
            路径。需与调用平台一致。</small
          ></el-form-item
        ><el-form-item label="可读知识库"
          ><el-select v-model="newScope" multiple class="w-full"
            ><el-option
              v-for="base in bases"
              :key="base.id"
              :label="base.name"
              :value="base.id" /></el-select></el-form-item></el-form
      ><template #footer
        ><el-button @click="createOpen = false">取消</el-button
        ><el-button
          type="primary"
          :disabled="!newName.trim() || !newScope.length"
          @click="create"
          >创建并生成 Key</el-button
        ></template
      ></el-dialog
    >
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
      title="请现在保存共享服务 Key"
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
import { onMounted, ref } from "vue";
import axios from "axios";
import { ElMessage } from "element-plus";
import { getMe } from "../api/auth";
import {
  listPrincipalKeys,
  rotateExternalKey,
  revokeExternalKey,
} from "../api/external";
import {
  workloadApi,
  type WorkloadPolicy,
  type WorkloadAgent,
  type WorkloadAgentKey,
} from "../api/workload";
import type { KnowledgeBase, ExternalKeyRow } from "../types";
import { confirmAction, errorMessage } from "../utils/feedback";
import WorkloadIdentityPanel from "./WorkloadIdentityPanel.vue";
defineProps<{ bases: KnowledgeBase[] }>();
type Connection = WorkloadPolicy & { name: string; knowledgeBaseIds: number[] };
type Agent = WorkloadAgent & { name: string; keys: WorkloadAgentKey[] };
const root = "/api/external/admin/agent-connections";
const admin = ref(false),
  busy = ref(false),
  error = ref(""),
  connections = ref<Connection[]>([]),
  selected = ref<Connection | null>(null),
  agents = ref<Agent[]>([]),
  serviceKeys = ref<ExternalKeyRow[]>([]);
const scopeIds = ref<number[]>([]),
  newScope = ref<number[]>([]),
  createOpen = ref(false),
  agentOpen = ref(false),
  secretOpen = ref(false),
  secret = ref("");
const newName = ref(""),
  origin = ref(window.location.origin),
  agentName = ref(""),
  agentId = ref(""),
  publicText = ref("");
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
async function load(id?: number) {
  const r = await axios.get(root);
  connections.value = r.data.data;
  const next =
    connections.value.find((c) => c.id === (id ?? selected.value?.id)) ??
    connections.value[0];
  selected.value = next ?? null;
  agents.value = [];
  serviceKeys.value = [];
  if (next) {
    scopeIds.value = [...next.knowledgeBaseIds];
    const [a, k] = await Promise.all([
      axios.get(`${root}/${next.id}/agents`),
      listPrincipalKeys(next.application_principal_id),
    ]);
    agents.value = a.data.data;
    serviceKeys.value = k.data.data ?? [];
  }
}
async function selectConnection(id: number) {
  await act(() => load(id));
}
function showSecret(value: string) {
  secret.value = value;
  secretOpen.value = true;
}
async function create() {
  await act(async () => {
    const r = await axios.post(root, {
      name: newName.value,
      resourceOrigin: origin.value.trim().replace(/\/$/, ""),
      knowledgeBaseIds: newScope.value,
    });
    createOpen.value = false;
    showSecret(r.data.data.key.secret);
    await load(r.data.data.id);
  });
}
async function saveScope() {
  await act(async () => {
    if (!selected.value) return;
    await axios.put(`${root}/${selected.value.id}/scope`, {
      knowledgeBaseIds: scopeIds.value,
    });
    await load();
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
    if (!selected.value) return;
    if (publicText.value.length > 16384) throw Error("公钥过长");
    const jwk = JSON.parse(publicText.value);
    if (jwk.d || jwk.p || jwk.q || jwk.privateKey)
      throw Error("禁止上传私钥，请选择 public.jwk");
    await axios.post(`${root}/${selected.value.id}/agents`, {
      name: agentName.value,
      agentId: agentId.value.trim(),
      publicJwk: jwk,
    });
    agentOpen.value = false;
    publicText.value = "";
    await load();
    ElMessage.success("公钥已登记，请回调用平台绑定并测试");
  });
}
async function issue() {
  await act(async () => {
    if (!selected.value) return;
    const expires = new Date(Date.now() + 364 * 86400000)
      .toISOString()
      .slice(0, 19);
    showSecret(
      (
        await workloadApi.issueKey(selected.value.id, {
          name: selected.value.name,
          expiresAt: expires,
        })
      ).secret,
    );
    await load();
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
    await load();
  });
}
async function revoke(id: number) {
  if (!(await confirm("撤销后，使用此 Key 的 Agent 将无法连接。"))) return;
  await act(async () => {
    await revokeExternalKey(id);
    await load();
  });
}
async function revokePublic(binding: number, kid: string) {
  if (!(await confirm("撤销后此私钥将不能再认证，且不可恢复。"))) return;
  await act(async () => {
    if (!selected.value) return;
    await workloadApi.keyStatus(selected.value.id, binding, kid, "REVOKED");
    await load();
  });
}
async function connectionStatus() {
  if (!selected.value) return;
  if (!(await confirm("确认变更服务接入状态？"))) return;
  await act(async () => {
    if (!selected.value) return;
    await workloadApi.policyStatus(
      selected.value.id,
      selected.value.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE",
    );
    await load();
  });
}
async function agentStatus(row: Agent) {
  if (!(await confirm("确认变更 Agent 状态？"))) return;
  await act(async () => {
    if (!selected.value) return;
    await workloadApi.agentStatus(
      selected.value.id,
      row.id,
      row.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE",
    );
    await load();
  });
}
async function copySecret() {
  try {
    await navigator.clipboard.writeText(secret.value);
    ElMessage.success("已复制");
  } catch {
    ElMessage.info("当前浏览器不支持剪贴板，请手动选中复制");
  }
}
onMounted(() =>
  act(async () => {
    admin.value = (await getMe()).data.data?.user?.role === "ADMIN";
    if (admin.value) await load();
  }),
);
</script>
