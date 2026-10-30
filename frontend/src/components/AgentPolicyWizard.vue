<template>
  <el-dialog
    :model-value="modelValue"
    title="连接一个 Agent 平台"
    width="min(760px,96vw)"
    :close-on-click-modal="!saving"
    :close-on-press-escape="!saving"
    :show-close="!saving"
    @update:model-value="!saving && $emit('update:modelValue', $event)"
  >
    <p class="kb-muted mb-4">
      把 Octop、OpenCode 等平台接到 AKB。一个连接可包含多个
      Agent；私钥保留在调用平台。
    </p>
    <el-steps :active="step" simple class="mb-5"
      ><el-step title="获取平台信息" /><el-step title="选择接入应用" /><el-step
        title="核对并创建"
    /></el-steps>
    <el-alert
      v-if="failure"
      type="error"
      :closable="false"
      :title="failure"
      class="mb-4"
    />
    <section v-if="step === 0">
      <el-form label-position="top">
        <el-form-item label="你要连接哪个平台？"
          ><el-radio-group v-model="platform" @change="selectPlatform"
            ><el-radio-button value="octop">Octop</el-radio-button
            ><el-radio-button value="opencode">OpenCode</el-radio-button
            ><el-radio-button value="custom"
              >其他平台</el-radio-button
            ></el-radio-group
          ></el-form-item
        >
      </el-form>
      <div class="rounded-lg border border-ink-100 p-4 mb-4">
        <b>{{
          platform === "octop"
            ? "先在 Octop 下载接入信息"
            : platform === "opencode"
              ? "先从 OpenCode 插件导出接入信息"
              : "先让平台提供接入信息"
        }}</b>
        <p v-if="platform === 'octop'" class="mt-2">
          在 Octop 打开「外部知识库 → AKB 知识源右侧盾牌 → 下载 AKB
          接入信息」。若尚未创建知识源，先填写 AKB 地址并保存。
        </p>
        <template v-else-if="platform === 'opencode'"
          ><p class="mt-2">
            使用 V2 插件配套 SDK
            0.3.1+，在宿主终端运行以下命令。将配置路径替换为你的实际路径，再上传生成的文件。
          </p>
          <code class="mt-2 block break-all"
            >akb-agent-identity setup export /absolute/path/identity.json &gt;
            akb-setup.json</code
          ></template
        >
        <p v-else class="mt-2">
          向平台管理员索取「AKB 接入信息包」，或请管理员在下方手动填写。不要使用
          Agent 名称代替安装实例或工作区标识。
        </p>
        <label class="mt-3 block"
          ><span class="font-medium">导入接入信息包（推荐）</span
          ><input
            aria-label="导入平台接入信息包"
            type="file"
            accept=".json,application/json"
            class="mt-2 block"
            @change="importSetup"
        /></label>
        <p class="kb-muted mt-2">
          只接收公开的接入信息，不接收完整配置、私钥和服务 Key。
        </p>
        <el-alert
          v-if="imported"
          class="mt-3"
          type="success"
          :closable="false"
          title="接入信息已校验并自动填写，请核对平台和 AKB 地址后继续。"
        />
      </div>
      <el-descriptions v-if="imported" :column="1" border class="mb-4"
        ><el-descriptions-item label="平台提供的 AKB 地址">{{
          form.resource
        }}</el-descriptions-item
        ><el-descriptions-item label="调用平台">{{
          form.platformId
        }}</el-descriptions-item></el-descriptions
      >
      <el-collapse v-model="details"
        ><el-collapse-item
          title="查看接入参数 / 手动填写（由平台管理员提供）"
          name="parameters"
        >
          <el-form label-position="top">
            <el-form-item
              v-if="platform === 'custom'"
              label="平台标识"
              :error="errors.platformId"
              ><el-input
                v-model="form.platformId"
                placeholder="平台 SDK 提供的标识，例如 my-platform"
            /></el-form-item>
            <el-form-item label="安装实例标识" :error="errors.installationId"
              ><el-input
                v-model="form.installationId"
                placeholder="复制平台展示的 installationId"
              />
              <p class="kb-muted">
                用于区分不同的平台安装，不能填写 Agent 名称。
              </p></el-form-item
            >
            <el-form-item label="工作区标识" :error="errors.workspaceId"
              ><el-input
                v-model="form.workspaceId"
                placeholder="复制平台展示的 workspaceId"
              />
              <p class="kb-muted">
                用于确认调用来自哪个工作区，不能自行编造。
              </p></el-form-item
            >
            <el-form-item label="AKB 对外访问地址" :error="errors.resource"
              ><el-input
                v-model="form.resource"
                placeholder="https://akb.example.com"
              />
              <p class="kb-muted">
                填写调用平台实际使用的根地址，不加 /api/mcp 或 /api/v1。内网部署可用明文 HTTP
                （如 http://192.168.1.50:8080、http://akb.internal）；公网地址必须用 HTTPS。
              </p></el-form-item
            >
            <el-form-item label="签发者标识（issuer）" :error="errors.issuer"
              ><el-input
                v-model="form.issuer"
                placeholder="复制平台提供的完整 HTTPS 标识"
              />
              <p class="kb-muted">
                这是平台约定的身份标识，不需要在此地址部署网站。
              </p></el-form-item
            >
            <el-form-item label="受众标识（audience）" :error="errors.audience"
              ><el-input v-model="form.audience" />
              <p class="kb-muted">
                通常为 akb-external-api，必须与平台设置一致。
              </p></el-form-item
            >
          </el-form>
        </el-collapse-item></el-collapse
      >
    </section>
    <section v-else-if="step === 1">
      <p class="mb-4">
        应用代表这次接入的权限上限。之后可以为其中的每个 Agent
        分别缩小可读知识库范围。
      </p>
      <el-radio-group v-model="applicationMode" class="mb-4"
        ><el-radio-button value="new">新建应用（推荐）</el-radio-button
        ><el-radio-button value="existing"
          >使用已有应用</el-radio-button
        ></el-radio-group
      >
      <el-form label-position="top"
        ><el-form-item
          v-if="applicationMode === 'new'"
          label="给这次接入起个名字"
          :error="nameError"
          ><el-input
            v-model="applicationName"
            maxlength="100"
            placeholder="例如：Octop 客服团队知识接入"
          />
          <p class="kb-muted">名字只用于管理，不影响身份验证。</p></el-form-item
        >
        <el-form-item v-else label="已有接入应用" :error="nameError"
          ><el-select
            v-model="applicationId"
            class="w-full"
            placeholder="选择已启用的应用"
            ><el-option
              v-for="p in applications"
              :key="p.id"
              :value="p.id"
              :label="`${p.name} (#${p.id})`"
          /></el-select>
          <p class="kb-muted">
            选择已有应用会共享该应用的知识库授权，请确认这是你的预期。
          </p></el-form-item
        ></el-form
      >
    </section>
    <section v-else>
      <el-alert
        type="info"
        :closable="false"
        title="创建连接后还要授权知识库、登记 Agent 公钥并验证；创建策略本身不会开放任何知识。"
        class="mb-4"
      />
      <el-descriptions :column="1" border
        ><el-descriptions-item label="调用平台">{{
          form.platformId
        }}</el-descriptions-item
        ><el-descriptions-item label="接入应用">{{
          applicationMode === "new"
            ? applicationName
            : applications.find((p) => p.id === applicationId)?.name
        }}</el-descriptions-item
        ><el-descriptions-item label="AKB 地址">{{
          form.resource
        }}</el-descriptions-item
        ><el-descriptions-item label="安装实例">{{
          form.installationId
        }}</el-descriptions-item
        ><el-descriptions-item label="工作区">{{
          form.workspaceId
        }}</el-descriptions-item></el-descriptions
      >
      <p class="kb-muted mt-3">
        请确认信息来自你要连接的平台。如果地址不正确，请返回修改。
      </p>
    </section>
    <template #footer
      ><el-button
        :disabled="saving"
        @click="step ? step-- : $emit('update:modelValue', false)"
        >{{ step ? "上一步" : "取消" }}</el-button
      ><el-button v-if="step < 2" type="primary" @click="next">{{
        step === 0 ? "信息正确，下一步" : "核对连接信息"
      }}</el-button
      ><el-button v-else type="primary" :loading="saving" @click="create"
        >创建连接并继续授权</el-button
      ></template
    >
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import {
  createExternalPrincipal,
  type ExternalPrincipalSummary,
} from "../api/external";
import { workloadApi, type WorkloadPolicy } from "../api/workload";
import {
  parsePlatformSetup,
  validatePlatformSetup,
} from "../utils/workload-identity.mjs";
import { errorMessage } from "../utils/feedback";
const props = defineProps<{
  modelValue: boolean;
  principals: ExternalPrincipalSummary[];
}>();
const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  created: [policy: WorkloadPolicy];
}>();
const step = ref(0),
  platform = ref("octop"),
  details = ref<string[]>([]),
  imported = ref(false),
  saving = ref(false),
  failure = ref(""),
  errors = ref<Record<string, string>>({}),
  nameError = ref("");
const newApplication = ref<{ id: number; name: string } | null>(null);
const applicationMode = ref("new"),
  applicationName = ref(""),
  applicationId = ref<number>();
const form = reactive({
  platformId: "octop",
  installationId: "",
  workspaceId: "",
  issuer: "https://octop.local/agents",
  audience: "akb-external-api",
  resource: window.location.origin,
});
const applications = computed(() =>
  props.principals.filter(
    (p) => p.principalType === "APPLICATION" && p.status === "ACTIVE",
  ),
);
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      step.value = 0;
      failure.value = "";
      errors.value = {};
      nameError.value = "";
      applicationMode.value = "new";
      applicationId.value = undefined;
      newApplication.value = null;
      applicationName.value = "Octop 知识接入";
      imported.value = false;
      details.value = [];
      platform.value = "octop";
      Object.assign(form, {
        platformId: "octop",
        installationId: "",
        workspaceId: "",
        issuer: "https://octop.local/agents",
        audience: "akb-external-api",
        resource: window.location.origin,
      });
    }
  },
);
function selectPlatform() {
  form.installationId = "";
  form.workspaceId = "";
  imported.value = false;
  errors.value = {};
  form.platformId = platform.value === "custom" ? "" : platform.value;
  form.issuer = platform.value === "octop" ? "https://octop.local/agents" : "";
  applicationName.value =
    platform.value === "octop"
      ? "Octop 知识接入"
      : platform.value === "opencode"
        ? "OpenCode 知识接入"
        : "";
  details.value = [];
}
async function importSetup(event: Event) {
  const target = event.target as HTMLInputElement;
  const f = target.files?.[0];
  if (!f) return;
  try {
    if (f.size > 16384) throw new Error("接入信息包不能超过 16 KiB");
    const value = parsePlatformSetup(await f.text());
    Object.assign(form, value);
    platform.value = ["octop", "opencode"].includes(value.platformId)
      ? value.platformId
      : "custom";
    imported.value = true;
    errors.value = {};
    failure.value = "";
    applicationName.value = `${value.platformId === "octop" ? "Octop" : value.platformId === "opencode" ? "OpenCode" : value.platformId} 知识接入`;
    details.value = [];
  } catch (e) {
    failure.value = errorMessage(e);
  } finally {
    target.value = "";
  }
}
function next() {
  failure.value = "";
  if (step.value === 0) {
    errors.value = validatePlatformSetup(form);
    if (Object.keys(errors.value).length) {
      details.value = ["parameters"];
      failure.value =
        "还有接入信息未填写正确。请查看下方红色提示；不确定时，请从平台导入接入信息包。";
      return;
    }
  } else if (step.value === 1) {
    nameError.value =
      applicationMode.value === "new"
        ? !applicationName.value.trim()
          ? "请给这次接入填写一个名字"
          : ""
        : !applicationId.value
          ? "请选择一个已有应用"
          : "";
    if (nameError.value) return;
  }
  step.value++;
}
async function create() {
  if (saving.value) return;
  saving.value = true;
  failure.value = "";
  try {
    const check = validatePlatformSetup(form);
    if (Object.keys(check).length) {
      errors.value = check;
      step.value = 0;
      details.value = ["parameters"];
      throw new Error("接入信息发生变化，请重新核对");
    }
    let id =
      applicationMode.value === "existing"
        ? applicationId.value
        : newApplication.value?.name === applicationName.value.trim()
          ? newApplication.value.id
          : undefined;
    if (!id) {
      const r = await createExternalPrincipal({
        principalCode: `APP_${crypto.randomUUID().slice(0, 12)}`,
        name: applicationName.value.trim(),
        principalType: "APPLICATION",
      });
      if (!r.data.success || !r.data.data)
        throw new Error(r.data.message || "创建应用失败");
      id = r.data.data.id;
      newApplication.value = { id, name: applicationName.value.trim() };
    }
    const { resource, ...values } = form;
    const policy = await workloadApi.createPolicy({
      ...values,
      resourceOrigin: resource,
      applicationPrincipalId: id,
      proofProfile: "AGENT_KEY_RS256_V2",
    });
    emit("created", policy);
    emit("update:modelValue", false);
  } catch (e) {
    failure.value = errorMessage(e);
  } finally {
    saving.value = false;
  }
}
</script>
