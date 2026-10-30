<template>
  <section class="kb-page" aria-label="Agent 身份接入">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">共享凭证 · 独立身份</div>
        <h1 class="kb-title">让 Agent 安全使用知识库</h1>
        <p class="kb-muted mt-2">
          先连接调用平台，再选择可读知识库、登记 Agent，最后回到平台验证。多个
          Agent 可以共用一个服务 Key，AKB 会分别识别它们。
        </p>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </header>
    <el-alert
      v-if="!checking && !admin"
      title="仅系统管理员可以配置 Agent 身份接入"
      type="info"
      :closable="false"
    />
    <template v-if="admin">
      <el-alert
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        class="mb-4"
      />
      <article class="kb-card p-4 mb-4" aria-label="接入操作指引">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <h2 class="font-semibold">第一次接入？按这四步操作</h2>
          <el-button type="primary" @click="policyDialog = true"
            >连接新平台</el-button
          >
        </div>
        <ol
          class="mt-3 grid gap-3 md:grid-cols-2 xl:grid-cols-4"
          style="list-style: none; padding-left: 0"
        >
          <li class="rounded-lg border border-ink-100 p-3">
            <b>1. 连接调用平台</b>
            <p class="kb-muted mt-2">
              从 Octop / OpenCode
              导出接入信息，按向导导入。安装实例、工作区等标识由平台提供，不需要自己编造。
            </p>
            <el-tag class="mt-2" :type="selected ? 'success' : 'info'">{{
              selected ? "已选择一个连接" : "从「连接新平台」开始"
            }}</el-tag>
          </li>
          <li class="rounded-lg border border-ink-100 p-3">
            <b>2. 选择可读知识库</b>
            <p class="kb-muted mt-2">
              先设置应用的最大可读范围，之后每个 Agent
              还要单独授权，两者重叠的知识才可访问。
            </p>
            <el-button
              v-if="selected"
              class="mt-2"
              size="small"
              :disabled="loading || detailLoading"
              @click="openGrants(selected.application_principal_id)"
              >{{
                applicationGrantCount === 0
                  ? "选择知识库"
                  : applicationGrantCount === null
                    ? "查看应用授权"
                    : `已授权 ${applicationGrantCount} 个知识库`
              }}</el-button
            >
          </li>
          <li class="rounded-lg border border-ink-100 p-3">
            <b>3. 添加并登记 Agent</b>
            <p class="kb-muted mt-2">
              在调用平台生成公钥包，导入下方 Agent
              列表；按公钥抽屉中的说明完成挑战签名，再为 Agent 选知识库。
            </p>
            <el-tag class="mt-2" type="info">{{
              selected
                ? agents.length
                  ? `已添加 ${agents.length} 个 Agent，请逐个登记和授权`
                  : "还未添加 Agent，请先导入平台公钥包"
                : "连接平台后可操作"
            }}</el-tag>
            <el-button
              v-if="isV2"
              class="mt-2"
              size="small"
              :disabled="!active || loading || detailLoading"
              @click="openBinding"
              >导入 Agent 公钥包</el-button
            >
          </li>
          <li class="rounded-lg border border-ink-100 p-3">
            <b>4. 回到平台验证</b>
            <p class="kb-muted mt-2">
              下载连接配置并导入平台，填入共享服务 Key；对每个 Agent
              执行「验证并选中」，再测试检索。
            </p>
            <el-button
              v-if="isV2"
              class="mt-2"
              size="small"
              @click="exportConnection"
              >下载平台连接配置</el-button
            >
            <el-button
              v-if="isV2 && !serviceKeys.some((k) => k.status === 'ACTIVE')"
              class="mt-2"
              size="small"
              :disabled="!active || loading || detailLoading"
              @click="keyDialog = true"
              >生成共享服务 Key</el-button
            >
            <p v-else-if="isV2" class="kb-muted mt-2">
              已有共享 Key，不需要为每个 Agent 另建。
            </p>
          </li>
        </ol>
        <el-alert
          v-if="selected && isV2 && applicationGrantCount === 0"
          class="mt-3"
          type="warning"
          :closable="false"
          title="这个连接还没有知识库授权，Agent 目前无法读取知识。请先完成第 2 步。"
        />
      </article>
      <el-collapse class="mb-4"
        ><el-collapse-item
          title="不熟悉这些概念？查看填写说明和常见问题"
          name="help"
        >
          <div class="grid gap-4 p-3 md:grid-cols-2">
            <div>
              <b>“接入应用”和“接入策略”是什么？</b>
              <p class="kb-muted mt-2">
                它们代表一次平台连接。你只需给连接起名，安装实例、工作区等标识从调用平台导入。一个连接内可以有多个
                Agent。
              </p>
            </div>
            <div>
              <b>服务 Key 和 Agent 公钥分别做什么？</b>
              <p class="kb-muted mt-2">
                服务 Key 证明平台获准接入；每个 Agent 的公钥用来区分调用者。只填
                Key 或只导入公钥都不能完成接入。私钥始终保留在调用平台。
              </p>
            </div>
            <div>
              <b>为什么验证身份成功，却读不到知识？</b>
              <p class="kb-muted mt-2">
                请确认应用和该 Agent
                都获准读取目标知识库，并检查资料标签权限、资料是否已发布和完成索引。身份通过不等于获得所有知识。
              </p>
            </div>
            <div>
              <b>提示“身份未就绪”时怎么办？</b>
              <p class="kb-muted mt-2">
                先在平台确认 SDK
                可用，再完成此处公钥登记，最后回平台执行“验证并选中”。启用 V2
                后，每个要使用知识库的 Agent 都需要单独完成这些步骤。
              </p>
            </div>
          </div>
        </el-collapse-item></el-collapse
      >
      <div class="grid gap-4 xl:grid-cols-[300px_minmax(0,1fr)]">
        <article class="kb-card p-4">
          <div class="mb-3 flex items-center justify-between">
            <b>接入策略</b
            ><el-button type="primary" size="small" @click="policyDialog = true"
              >连接新平台</el-button
            >
          </div>
          <el-empty
            v-if="!policies.length && !loading"
            description="还没有连接 Agent 平台"
          />
          <button
            v-for="item in policies"
            :key="item.id"
            :disabled="loading || saving"
            class="mb-2 block w-full rounded-lg border p-3 text-left"
            :class="
              selected?.id === item.id
                ? 'border-accent bg-ink-50'
                : 'border-ink-100'
            "
            @click="choose(item)"
          >
            <b class="block"
              >{{ item.platform_id || "V1 安装" }} ·
              {{ item.installation_id }}</b
            >
            <span class="kb-muted block"
              >策略 #{{ item.id }} · {{ item.workspace_id }}</span
            >
            <el-tag
              size="small"
              :type="item.status === 'ACTIVE' ? 'success' : 'info'"
              >{{ statusLabel(item.status) }}</el-tag
            >
          </button>
        </article>
        <div
          v-if="selected"
          class="min-w-0 space-y-4"
          v-loading="detailLoading"
        >
          <article class="kb-card p-4">
            <div class="flex flex-wrap items-center gap-2">
              <h2 class="text-base font-semibold">
                {{ selected.platform_id || "V1" }} · 策略 #{{ selected.id }}
              </h2>
              <el-tag>{{ selected.proof_profile }}</el-tag>
              <el-button
                v-if="selected.status !== 'REVOKED'"
                size="small"
                @click="
                  policyStatus(
                    selected.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE',
                  )
                "
                >{{
                  selected.status === "ACTIVE" ? "暂停接入" : "恢复接入"
                }}</el-button
              >
              <el-button
                v-if="selected.status !== 'REVOKED'"
                size="small"
                type="danger"
                plain
                @click="policyStatus('REVOKED')"
                >永久撤销</el-button
              >
            </div>
            <el-descriptions :column="2" size="small" class="mt-3" border>
              <el-descriptions-item label="应用"
                >{{ principalName(selected.application_principal_id) }} (#{{
                  selected.application_principal_id
                }})</el-descriptions-item
              >
              <el-descriptions-item label="工作区">{{
                selected.workspace_id
              }}</el-descriptions-item>
              <el-descriptions-item label="签发标识">{{
                selected.issuer
              }}</el-descriptions-item>
              <el-descriptions-item label="AKB 地址">{{
                selected.resource_origin || "V1 JWKS 模式"
              }}</el-descriptions-item>
              <el-descriptions-item label="安装 ID">{{
                selected.installation_id
              }}</el-descriptions-item>
              <el-descriptions-item label="受众 audience">{{
                selected.audience
              }}</el-descriptions-item>
            </el-descriptions>
            <el-button
              class="mt-3"
              size="small"
              :disabled="loading || detailLoading"
              @click="openGrants(selected.application_principal_id)"
              >为应用选择可读知识库</el-button
            >
          </article>
          <el-alert
            v-if="!isV2"
            type="info"
            title="这是 V1 安装级策略。请新建 V2 策略使用独立 Agent 公钥管理。"
            :closable="false"
          />
          <template v-if="isV2">
            <article class="kb-card p-4">
              <div
                class="mb-3 flex flex-wrap items-center justify-between gap-2"
              >
                <b>Agent 与公钥</b
                ><el-button
                  type="primary"
                  :disabled="!active"
                  @click="openBinding"
                  >绑定 Agent / 导入公钥包</el-button
                >
              </div>
              <el-table :data="agents" row-key="id" size="small" border>
                <el-table-column
                  prop="runtime_agent_key"
                  label="运行 Agent"
                  min-width="130"
                />
                <el-table-column
                  prop="external_agent_id"
                  label="稳定 Agent ID"
                  min-width="230"
                  show-overflow-tooltip
                />
                <el-table-column label="状态" width="85"
                  ><template #default="{ row }">{{
                    statusLabel(row.status)
                  }}</template></el-table-column
                >
                <el-table-column label="操作" min-width="280"
                  ><template #default="{ row }">
                    <el-button link type="primary" @click="openAgent(row)"
                      >公钥与登记</el-button
                    >
                    <el-button link @click="openGrants(row.agent_principal_id)"
                      >知识库授权</el-button
                    >
                    <el-button
                      v-if="row.status !== 'REVOKED'"
                      link
                      @click="
                        agentStatus(
                          row,
                          row.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE',
                        )
                      "
                      >{{
                        row.status === "ACTIVE" ? "暂停" : "恢复"
                      }}</el-button
                    >
                    <el-button
                      v-if="row.status !== 'REVOKED'"
                      link
                      type="danger"
                      @click="agentStatus(row, 'REVOKED')"
                      >撤销</el-button
                    >
                  </template></el-table-column
                >
              </el-table>
              <p class="kb-muted mt-2">
                Agent 不需要单独的服务 Key。没有知识库授权时，访问会被拒绝。
              </p>
            </article>
            <article class="kb-card p-4">
              <div class="mb-3 flex items-center justify-between">
                <b>共享服务 Key</b
                ><el-button
                  :disabled="!active"
                  type="primary"
                  @click="keyDialog = true"
                  >签发共享 Key</el-button
                >
              </div>
              <el-table :data="serviceKeys" size="small" border>
                <el-table-column prop="name" label="名称" /><el-table-column
                  prop="keyPrefix"
                  label="凭证前缀"
                />
                <el-table-column
                  prop="status"
                  label="状态"
                  width="110"
                /><el-table-column
                  prop="expiresAt"
                  label="到期时间"
                  min-width="170"
                />
                <el-table-column label="操作" width="150"
                  ><template #default="{ row }">
                    <el-button
                      v-if="row.status === 'ACTIVE'"
                      link
                      @click="rotateKey(row.id)"
                      >轮换</el-button
                    >
                    <el-button
                      v-if="row.status === 'ACTIVE'"
                      link
                      type="danger"
                      @click="revokeKey(row.id)"
                      >吊销</el-button
                    >
                  </template></el-table-column
                >
              </el-table>
            </article>
            <article class="kb-card p-4">
              <div class="mb-3 flex items-center justify-between">
                <b>最近身份调用</b
                ><el-button size="small" @click="loadDetails"
                  >刷新审计</el-button
                >
              </div>
              <el-table :data="audits" size="small" border>
                <el-table-column
                  prop="createdTime"
                  label="时间"
                  min-width="160"
                />
                <el-table-column prop="keyId" label="共享 Key" width="90" />
                <el-table-column
                  prop="agentPrincipalId"
                  label="Agent 主体"
                  width="100"
                />
                <el-table-column
                  prop="signingKid"
                  label="签名公钥 kid"
                  min-width="170"
                  show-overflow-tooltip
                />
                <el-table-column
                  prop="workloadSessionId"
                  label="会话"
                  min-width="140"
                  show-overflow-tooltip
                />
                <el-table-column
                  prop="workloadInvocationId"
                  label="调用 ID"
                  min-width="140"
                  show-overflow-tooltip
                />
                <el-table-column prop="statusCode" label="HTTP" width="75" />
              </el-table>
              <p class="kb-muted mt-2">
                展示最近 100 条调用中，属于此策略凭据的记录。
              </p>
            </article>
          </template>
        </div>
        <el-empty
          v-else
          description="创建或选择接入策略，开始配置独立 Agent 身份"
        />
      </div>
    </template>

    <AgentPolicyWizard
      v-model="policyDialog"
      :principals="principals"
      @created="policyCreated"
    />
    <el-dialog
      v-model="bindingDialog"
      title="绑定 Agent"
      width="min(680px,96vw)"
    >
      <el-alert
        type="info"
        :closable="false"
        class="mb-4"
        title="先回到调用平台，选择一个 Agent，点击生成公钥、导出公钥包。导入该文件即可自动填写身份；不要填写个人用户名。"
      />
      <el-form label-position="top">
        <el-form-item label="导入公钥接入包（推荐）"
          ><input
            aria-label="导入 Agent 公钥包"
            type="file"
            accept=".json,application/json"
            @change="importBindingFile"
          />
          <p class="kb-muted">
            只接收公钥和身份信息，不接收私钥或服务 Key。
          </p></el-form-item
        >
        <el-form-item label="运行 Agent 键"
          ><el-input
            v-model="bindingForm.runtimeAgentKey"
            placeholder="builder，或 Octop 展示的运行 Agent ID"
        /></el-form-item>
        <el-form-item label="稳定 Agent ID"
          ><el-input
            v-model="bindingForm.externalAgentId"
            placeholder="公钥包内的 UUID"
        /></el-form-item>
        <el-form-item label="AKB Agent 主体"
          ><el-select v-model="bindingForm.agentPrincipalId" class="w-full"
            ><el-option
              :value="0"
              label="创建与此身份匹配的新 AGENT 主体" /><el-option
              v-for="p in principals.filter(
                (p) => p.principalType === 'AGENT' && p.status === 'ACTIVE',
              )"
              :key="p.id"
              :value="p.id"
              :label="`${p.name} (#${p.id})`" /></el-select
        ></el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="bindingDialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="bindAgent">{{
          bindingForm.agentPrincipalId ? "绑定 Agent" : "创建并绑定 Agent"
        }}</el-button></template
      >
    </el-dialog>
    <el-drawer
      v-model="agentDrawer"
      :title="`公钥登记 · ${currentAgent?.runtime_agent_key || ''}`"
      size="min(780px,98vw)"
      @closed="clearEnrollment"
    >
      <el-alert
        title="私钥由调用平台保管。将登记挑战交给调用平台签名，再在此提交登记证明。"
        type="info"
        :closable="false"
        class="mb-4"
      />
      <el-table :data="agentKeys" size="small" border
        ><el-table-column
          prop="kid"
          label="公钥 kid"
          min-width="220"
          show-overflow-tooltip
        /><el-table-column
          prop="status"
          label="状态"
          width="110"
        /><el-table-column
          prop="valid_until"
          label="退休截止"
          min-width="150"
        /><el-table-column label="操作" width="120"
          ><template #default="{ row }"
            ><el-button v-if="row.status === 'ACTIVE'" link @click="retire(row)"
              >退休</el-button
            ><el-button
              v-if="row.status !== 'REVOKED'"
              link
              type="danger"
              @click="revokeAgentKey(row)"
              >撤销</el-button
            ></template
          ></el-table-column
        ></el-table
      >
      <el-divider>1. 导入调用平台生成的公钥包</el-divider>
      <input
        aria-label="导入待登记公钥"
        type="file"
        accept=".json,application/json"
        @change="importEnrollmentFile"
      />
      <el-input
        v-model="publicText"
        type="textarea"
        :rows="5"
        class="mt-2"
        placeholder="粘贴 publicJwk 或公钥接入包 JSON"
      />
      <el-button
        class="mt-3"
        type="primary"
        :disabled="!active || currentAgent?.status !== 'ACTIVE'"
        :loading="saving"
        @click="makeChallenge"
        >2. 生成挑战，交回平台签名</el-button
      >
      <template v-if="challenge">
        <el-alert
          class="mt-4"
          type="warning"
          :closable="false"
          :title="`挑战有效期至 ${new Date(challenge.expiresAt * 1000).toLocaleTimeString()}，须由当前 AKB 管理员完成提交。`"
        />
        <el-input
          :model-value="JSON.stringify(challenge, null, 2)"
          type="textarea"
          :rows="6"
          readonly
          class="mt-2"
        />
        <el-button
          class="mt-2"
          @click="download('registration-challenge.json', challenge)"
          >下载挑战，上传到调用平台</el-button
        >
        <el-form-item label="3. 粘贴调用平台返回的登记证明" class="mt-4"
          ><el-input
            v-model="proofText"
            type="textarea"
            :rows="3"
            placeholder="粘贴 registrationProof JWT"
        /></el-form-item>
        <el-button type="primary" :loading="saving" @click="finishRegistration"
          >完成登记，然后回平台验证</el-button
        >
      </template>
    </el-drawer>
    <el-dialog
      v-model="keyDialog"
      title="签发共享服务 Key"
      width="min(540px,96vw)"
    >
      <el-form label-position="top"
        ><el-form-item label="凭据名称"
          ><el-input v-model="keyForm.name" /></el-form-item
        ><el-form-item label="到期时间"
          ><el-date-picker
            v-model="keyForm.expiresAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="未来且不超过一年" /></el-form-item
        ><el-form-item label="每分钟 HTTP 请求上限"
          ><el-input-number
            v-model="keyForm.rateLimitPerMinute"
            :min="1"
            :max="100000" /></el-form-item
      ></el-form>
      <el-alert
        title="为这个连接生成一个服务 Key，所有 Agent 共用。复制到调用平台的服务 Key 配置后保存；它不会代替每个 Agent 的公钥登记。仅开放知识库只读操作。"
        type="info"
        :closable="false"
      />
      <template #footer
        ><el-button @click="keyDialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="issueKey"
          >签发</el-button
        ></template
      >
    </el-dialog>
    <el-dialog
      :model-value="!!secret"
      title="请立即保存服务 Key"
      width="min(580px,96vw)"
      @close="secret = ''"
    >
      <el-alert
        title="明文只在本次签发时展示，关闭后无法再次查询。请保存到调用平台的凭据配置。"
        type="warning"
        :closable="false"
      />
      <el-input
        :model-value="secret"
        type="password"
        show-password
        readonly
        class="mt-3"
        aria-label="一次性服务 Key"
      />
      <template #footer
        ><el-button @click="copySecret">复制 Key</el-button
        ><el-button type="primary" @click="secret = ''"
          >已安全保存</el-button
        ></template
      >
    </el-dialog>
    <el-dialog
      v-model="grantDialog"
      :close-on-click-modal="false"
      :title="`知识库授权 · ${principalName(grantPrincipal)}`"
      width="min(760px,96vw)"
    >
      <el-alert
        title="勾选允许访问的知识库并保存。应用和 Agent 都要获得同一知识库的授权才能读取；取消勾选会撤销访问。资料标签权限可在每个知识库下单独设置。"
        type="info"
        :closable="false"
      />
      <div
        v-for="base in bases"
        :key="base.id"
        class="mt-3 rounded border border-ink-100 p-3"
      >
        <el-checkbox
          :model-value="!!grantRows[base.id]"
          @change="toggleGrant(base.id, !!$event)"
          >{{ base.name }} (#{{ base.id }})</el-checkbox
        >
        <template v-if="grantRows[base.id]">
          <el-switch
            :model-value="grantRows[base.id].permissionTags === null"
            active-text="继承主体资料标签权限"
            @change="grantRows[base.id].permissionTags = $event ? null : []"
          />
          <el-select
            v-if="grantRows[base.id].permissionTags !== null"
            v-model="grantRows[base.id].permissionTags"
            multiple
            filterable
            allow-create
            default-first-option
            class="mt-2 w-full"
            placeholder="资料标签（留空仅可读无标签资料）"
          />
        </template>
      </div>
      <template #footer
        ><el-button @click="grantDialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="saveGrants"
          >保存授权</el-button
        ></template
      >
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import AgentPolicyWizard from "./AgentPolicyWizard.vue";
import axios from "axios";
import { ElMessage } from "element-plus";
import { getMe } from "../api/auth";
import {
  createExternalPrincipal,
  listExternalPrincipals,
  listExternalAudits,
  listPrincipalKeys,
  rotateExternalKey,
  revokeExternalKey,
  type ExternalPrincipalSummary,
} from "../api/external";
import {
  workloadApi,
  type WorkloadPolicy,
  type WorkloadAgent,
  type WorkloadAgentKey,
  type RegistrationChallenge,
} from "../api/workload";
import {
  parsePublicBundle,
  parseRegistrationProof,
} from "../utils/workload-identity.mjs";
import { confirmAction, errorMessage } from "../utils/feedback";
import type { ExternalKeyRow, ExternalAuditRow, KnowledgeBase } from "../types";
defineProps<{ bases: KnowledgeBase[] }>();
const admin = ref(false),
  checking = ref(true),
  loading = ref(false),
  detailLoading = ref(false),
  saving = ref(false),
  error = ref("");
const policies = ref<WorkloadPolicy[]>([]),
  principals = ref<ExternalPrincipalSummary[]>([]),
  selected = ref<WorkloadPolicy | null>(null);
const applicationGrantCount = ref<number | null>(null);
const agents = ref<WorkloadAgent[]>([]),
  serviceKeys = ref<ExternalKeyRow[]>([]),
  audits = ref<ExternalAuditRow[]>([]);
const isV2 = computed(
    () => selected.value?.proof_profile === "AGENT_KEY_RS256_V2",
  ),
  active = computed(() => selected.value?.status === "ACTIVE");
const policyDialog = ref(false),
  bindingDialog = ref(false),
  agentDrawer = ref(false),
  keyDialog = ref(false),
  grantDialog = ref(false),
  secret = ref("");
const currentAgent = ref<WorkloadAgent | null>(null),
  agentKeys = ref<WorkloadAgentKey[]>([]),
  publicText = ref(""),
  proofText = ref(""),
  challenge = ref<RegistrationChallenge | null>(null);
const importedBundle = ref<ReturnType<typeof parsePublicBundle> | null>(null);
const bindingForm = reactive({
  runtimeAgentKey: "",
  externalAgentId: "",
  agentPrincipalId: 0,
});
const keyForm = reactive({
  name: "共享 Agent 服务 Key",
  expiresAt: new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 19),
  rateLimitPerMinute: 60,
});
const grantPrincipal = ref(0),
  grantRows = ref<
    Record<
      number,
      {
        knowledgeBaseId: number;
        permissionTags: string[] | null;
        reason: string;
        expiresAt: string | null;
      }
    >
  >({});
const statusLabel = (status: string) =>
  (
    ({ ACTIVE: "启用", SUSPENDED: "暂停", REVOKED: "已撤销" }) as Record<
      string,
      string
    >
  )[status] || status;
const principalName = (id: number) =>
  principals.value.find((p) => p.id === id)?.name || `主体 #${id}`;
async function action(fn: () => Promise<void>) {
  if (saving.value) return;
  saving.value = true;
  error.value = "";
  try {
    await fn();
  } catch (e) {
    if ((e as Error).message !== "cancel") {
      error.value = errorMessage(e);
      ElMessage.error(error.value);
    }
  } finally {
    saving.value = false;
  }
}
let loadSequence = 0;
async function load() {
  const sequence = ++loadSequence;
  loading.value = true;
  try {
    const [p, s] = await Promise.all([
      workloadApi.policies(),
      listExternalPrincipals(),
    ]);
    if (sequence !== loadSequence) return;
    policies.value = p;
    principals.value = s.data.data || [];
    const next = p.find((p) => p.id === selected.value?.id) || p[0];
    if (next) await choose(next);
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    if (sequence === loadSequence) loading.value = false;
  }
}
async function choose(policy: WorkloadPolicy) {
  selected.value = policy;
  applicationGrantCount.value = null;
  agentDrawer.value = false;
  clearEnrollment();
  currentAgent.value = null;
  await loadDetails();
}
async function loadDetails() {
  const p = selected.value;
  if (!p) return;
  detailLoading.value = true;
  try {
    const [a, k, log, grants] = await Promise.all([
      workloadApi.agents(p.id),
      listPrincipalKeys(p.application_principal_id),
      listExternalAudits(null, 100),
      axios.get(
        `/api/external/admin/principals/${p.application_principal_id}/knowledge-base-grants/config`,
      ),
    ]);
    if (selected.value?.id !== p.id) return;
    applicationGrantCount.value = Array.isArray(grants.data.data)
      ? grants.data.data.filter(
          (g: { expiresAt?: string | null }) =>
            !g.expiresAt || new Date(g.expiresAt).getTime() > Date.now(),
        ).length
      : null;
    agents.value = a;
    serviceKeys.value = (k.data.data || []).filter(
      (k) =>
        k.identityMode === "APPLICATION_WORKLOAD" &&
        k.workloadPolicyId === p.id,
    );
    const ids = new Set(serviceKeys.value.map((k) => k.id));
    audits.value = (log.data.data || []).filter(
      (r) => r.keyId !== null && ids.has(r.keyId),
    );
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    detailLoading.value = false;
  }
}
async function policyCreated(p: WorkloadPolicy) {
  const sequence = ++loadSequence;
  loading.value = true;
  selected.value = p;
  applicationGrantCount.value = null;
  agents.value = [];
  serviceKeys.value = [];
  audits.value = [];
  try {
    const [next, people] = await Promise.all([
      workloadApi.policies(),
      listExternalPrincipals(),
    ]);
    if (sequence !== loadSequence) return;
    policies.value = next;
    principals.value = people.data.data || [];
    await choose(p);
    ElMessage.success("连接已创建。下一步：为接入应用选择可读知识库。");
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    if (sequence === loadSequence) loading.value = false;
  }
}
function exportConnection() {
  const p = selected.value;
  if (!p) return;
  download("akb-connection.json", {
    format: "akb-platform-connection/v1",
    proofProfile: p.proof_profile,
    platformId: p.platform_id,
    installationId: p.installation_id,
    workspaceId: p.workspace_id,
    resource: p.resource_origin,
    policyId: String(p.id),
    applicationPrincipalId: String(p.application_principal_id),
    issuer: p.issuer,
    audience: p.audience,
  });
}
async function destructive(title: string, message: string) {
  await confirmAction({ title, message, danger: true });
}
async function policyStatus(status: string) {
  await action(async () => {
    const p = selected.value!;
    if (status === "REVOKED")
      await destructive(
        "永久撤销策略",
        "该策略下全部 Agent 将无法调用，撤销后不能恢复。",
      );
    await workloadApi.policyStatus(p.id, status);
    await load();
  });
}
async function agentStatus(a: WorkloadAgent, status: string) {
  await action(async () => {
    if (status === "REVOKED")
      await destructive(
        "永久撤销 Agent",
        "此 Agent 绑定将永久失效，其他 Agent 不受影响。",
      );
    await workloadApi.agentStatus(selected.value!.id, a.id, status);
    await loadDetails();
  });
}
function openBinding() {
  Object.assign(bindingForm, {
    runtimeAgentKey: "",
    externalAgentId: "",
    agentPrincipalId: 0,
  });
  importedBundle.value = null;
  bindingDialog.value = true;
}
async function readUpload(event: Event) {
  const input = event.target as HTMLInputElement;
  const f = input.files?.[0];
  input.value = "";
  if (!f) return "";
  if (f.size > 16384) throw new Error("文件不能超过 16 KiB");
  return f.text();
}
async function importBindingFile(e: Event) {
  try {
    const text = await readUpload(e);
    if (!text) return;
    const v = parsePublicBundle(text),
      p = selected.value!;
    if (
      (v.installationId && v.installationId !== p.installation_id) ||
      (v.workspaceId && v.workspaceId !== p.workspace_id)
    )
      throw new Error("接入包的安装/工作区与当前策略不一致");
    importedBundle.value = v;
    bindingForm.runtimeAgentKey = v.runtimeAgentKey;
    bindingForm.externalAgentId = v.externalAgentId;
  } catch (e) {
    ElMessage.error(errorMessage(e));
  }
}
async function bindAgent() {
  await action(async () => {
    const p = selected.value!;
    if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(bindingForm.runtimeAgentKey))
      throw new Error(
        "运行 Agent 键需为 1–128 位字母、数字、点、冒号、下划线或短横线",
      );
    if (
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
        bindingForm.externalAgentId,
      )
    )
      throw new Error("稳定 Agent ID 必须为小写 UUID");
    let id = bindingForm.agentPrincipalId;
    if (!id) {
      const r = await createExternalPrincipal({
        principalCode: `AG_${crypto.randomUUID().slice(0, 12)}`,
        name: bindingForm.runtimeAgentKey,
        principalType: "AGENT",
        providerInstanceId: p.installation_id,
        externalSubjectId: bindingForm.externalAgentId,
      });
      id = r.data.data!.id;
      bindingForm.agentPrincipalId = id;
    }
    const rows = await workloadApi.bind(p.id, {
      ...bindingForm,
      agentPrincipalId: id,
    });
    agents.value = rows;
    bindingDialog.value = false;
    principals.value = (await listExternalPrincipals()).data.data || [];
    const a = rows.find(
      (a) => a.runtime_agent_key === bindingForm.runtimeAgentKey,
    );
    if (a) {
      await openAgent(a);
      if (importedBundle.value)
        publicText.value = JSON.stringify(
          importedBundle.value.publicJwk,
          null,
          2,
        );
    }
  });
}
async function openAgent(a: WorkloadAgent) {
  currentAgent.value = a;
  clearEnrollment();
  agentDrawer.value = true;
  try {
    agentKeys.value = await workloadApi.keys(selected.value!.id, a.id);
  } catch (e) {
    ElMessage.error(errorMessage(e));
  }
}
function clearEnrollment() {
  publicText.value = "";
  proofText.value = "";
  challenge.value = null;
}
async function importEnrollmentFile(e: Event) {
  try {
    const text = await readUpload(e);
    if (!text) return;
    const v = parsePublicBundle(text);
    if (
      v.externalAgentId &&
      v.externalAgentId !== currentAgent.value?.external_agent_id
    )
      throw new Error("公钥包属于其他 Agent");
    publicText.value = JSON.stringify(v.publicJwk, null, 2);
  } catch (e) {
    ElMessage.error(errorMessage(e));
  }
}
async function makeChallenge() {
  await action(async () => {
    const bundle = parsePublicBundle(publicText.value);
    challenge.value = await workloadApi.challenge(
      selected.value!.id,
      currentAgent.value!.id,
      bundle.publicJwk,
    );
    proofText.value = "";
  });
}
async function finishRegistration() {
  await action(async () => {
    if (!challenge.value) throw new Error("请先创建挑战");
    await workloadApi.register(
      selected.value!.id,
      currentAgent.value!.id,
      challenge.value.challengeId,
      parseRegistrationProof(proofText.value),
    );
    agentKeys.value = await workloadApi.keys(
      selected.value!.id,
      currentAgent.value!.id,
    );
    clearEnrollment();
    ElMessage.success("公钥已登记，请在调用平台执行身份探针并选中密钥");
  });
}
async function retire(key: WorkloadAgentKey) {
  await action(async () => {
    await destructive(
      "退休公钥",
      "请确认新公钥已登记且调用平台已切换。旧公钥将在两分钟后失效。",
    );
    await workloadApi.keyStatus(
      selected.value!.id,
      currentAgent.value!.id,
      key.kid,
      "RETIRING",
      new Date(Date.now() + 120000).toISOString(),
    );
    agentKeys.value = await workloadApi.keys(
      selected.value!.id,
      currentAgent.value!.id,
    );
  });
}
async function revokeAgentKey(key: WorkloadAgentKey) {
  await action(async () => {
    await destructive(
      "撤销公钥",
      "使用此公钥的后续请求将立即被拒绝，撤销后不能恢复。",
    );
    await workloadApi.keyStatus(
      selected.value!.id,
      currentAgent.value!.id,
      key.kid,
      "REVOKED",
    );
    agentKeys.value = await workloadApi.keys(
      selected.value!.id,
      currentAgent.value!.id,
    );
  });
}
async function issueKey() {
  await action(async () => {
    if (!keyForm.expiresAt) throw new Error("请选择到期时间");
    const r = await workloadApi.issueKey(selected.value!.id, {
      ...keyForm,
      scopes: ["SEARCH", "QUERY", "LIST", "READ_OBJECT"],
    });
    secret.value = r.secret;
    keyDialog.value = false;
    await loadDetails();
  });
}
async function rotateKey(id: number) {
  await action(async () => {
    await destructive(
      "轮换共享 Key",
      "旧 Key 将立即失效，需及时更新所有调用平台的服务凭据。",
    );
    const r = await rotateExternalKey(id);
    secret.value = r.data.data?.secret || "";
    await loadDetails();
  });
}
async function revokeKey(id: number) {
  await action(async () => {
    await destructive("吊销共享 Key", "使用此凭据的所有 Agent 将停止访问。");
    await revokeExternalKey(id);
    await loadDetails();
  });
}
async function copySecret() {
  try {
    await navigator.clipboard.writeText(secret.value);
    ElMessage.success("已复制，请安全保存");
  } catch {
    ElMessage.warning("复制失败，请手动保存");
  }
}
function download(name: string, value: unknown) {
  const url = URL.createObjectURL(
    new Blob([JSON.stringify(value, null, 2)], { type: "application/json" }),
  );
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
async function openGrants(id: number) {
  try {
    grantPrincipal.value = id;
    grantRows.value = {};
    const r = await axios.get(
      `/api/external/admin/principals/${id}/knowledge-base-grants/config`,
    );
    for (const g of r.data.data)
      grantRows.value[g.knowledgeBaseId] = {
        knowledgeBaseId: g.knowledgeBaseId,
        permissionTags: g.permissionTags ?? null,
        reason: g.reason || "Agent 身份授权",
        expiresAt: g.expiresAt || null,
      };
    grantDialog.value = true;
  } catch (e) {
    ElMessage.error(errorMessage(e));
  }
}
function toggleGrant(id: number, checked: boolean) {
  if (checked)
    grantRows.value[id] = {
      knowledgeBaseId: id,
      permissionTags: [],
      reason: "Agent 身份授权",
      expiresAt: null,
    };
  else delete grantRows.value[id];
}
async function saveGrants() {
  await action(async () => {
    await axios.put(
      `/api/external/admin/principals/${grantPrincipal.value}/knowledge-base-grants/config`,
      Object.values(grantRows.value),
    );
    grantDialog.value = false;
    await loadDetails();
    ElMessage.success("知识库授权已保存");
  });
}
onMounted(async () => {
  try {
    admin.value = (await getMe()).data.data?.user?.role === "ADMIN";
    if (admin.value) await load();
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    checking.value = false;
  }
});
</script>
