
/**
 * 模块页面的动作契约。
 *
 * V2.1 设计（§2）要求左侧导航不再挂任何二级菜单，五个一级页面内部用 Tab 承载全部能力，
 * 于是「跳转对象详情 / 打开 Trace / 执行裁决」这类跨模块动作不能再由父组件逐个透传 props：
 * App 作为外壳统一提供，模块页面通过 inject 获取，避免每个页面重复连线。
 */
import { inject, type InjectionKey } from 'vue'
import type { UploadFile } from 'element-plus'
import type { AssetType } from './components/AssetBrowserPanel.vue'
import type { ConflictRow, QueryCitation, ReviewRow } from './types'
import type { NavLeaf } from './navigation'

export interface KosShell {
  /** 切换视图：接受 IA 视图 key，旧 key 由 navigation 归一化。 */
  navigate: (key: string) => void
  /** 权限判定：按登录人角色下发的权限码判断，未开启认证时恒为 true。 */
  can: (code: string) => boolean
  /** 当前是否为管理视图：面板内需要区分「调用方视角」与「运维视角」时用它裁剪区块。 */
  adminView: () => boolean
  /** 视图 key 是否可达：页面内的快捷入口先用它置灰，避免点了才提示无权访问。 */
  canNavigate: (key: string) => boolean
  /** 模块内当前角色可见的 Tab，页面直接用它渲染 ModuleTabs。 */
  tabs: (sectionKey: string) => NavLeaf[]
  /** 工作台 KPI 下钻：按对象类型进入资产浏览（设计 §3.1）。 */
  openAssets: (type: AssetType) => void
  /** 打开知识图谱并定位到指定实体。 */
  openGraph: (code: string) => void
  /** 任务中心 / 生成页点击任务：选中该次运行并进入生成任务页。 */
  openRun: (runId: number) => void
  /** 打开 检索链路追踪 抽屉。 */
  openTrace: (qid: string) => void
  /** 打开集成调用 Trace 抽屉：入口是对外接口的 traceId，不是问答 queryId。 */
  openCallTrace: (traceId: string) => void
  /** 打开统一对象详情抽屉。 */
  /** Chunk 也能打开：核对引用落点时需要看到原文与切分边界。 */
  openDetail: (mode: 'CARD' | 'CLAIM' | 'CHUNK', code: string) => void
  /** 打开 Evidence 原文抽屉。 */
  openEvidence: (code: string) => void
  /** 搜索结果引用跳转：Evidence → Chunk → 对象详情（设计 §9）。 */
  openCitation: (citation: QueryCitation) => void
  /** 执行一次上传：文件、权威分与 ACL 标签由外壳持有。 */
  upload: () => void
  pick: (file: UploadFile) => void
  remove: () => void
  /** 批量上传：多个文件一次提交，外壳持有批次状态并轮询进度。 */
  uploadFiles: (files: File[]) => void
  /** 取消进行中的批次：排队的文件不再启动。 */
  cancelBatch: (batchCode: string) => void
  /** 重跑批次内的失败文件。 */
  retryBatch: (batchCode: string) => void
  /** 选中一次摄入运行并加载其事件。 */
  selectRun: (id: number) => void
  /** 刷新知识库概览与统计。 */
  refresh: () => void
  /** 文档重跑结束后刷新文档、运行与统计。 */
  reloadAfterRetry: () => void
  /** 运行一次智能裁决。 */
  adjudicate: () => void
  /** 人工裁决：备注与决策一并写入复核记录（设计 §4.4）。 */
  review: (row: ReviewRow, decision: string, comment?: string) => void
  resolveConflict: (row: ConflictRow, status: string) => void
  /** 选中一条冲突：加载双方对照内容与各自证据，供中栏并排比对。 */
  selectConflict: (id: number) => void
  /** 选中一条待审核知识：加载对象详情与证据链。 */
  selectReview: (row: ReviewRow) => void
  /** 冲突裁决：KEEP_A / KEEP_B / MERGE / REJECT_BOTH / REQUEST_EVIDENCE。 */
  adjudicateConflict: (id: number, decision: string, comment?: string) => void
  /** 源文件预览：选中资料后单独加载。 */
  previewSource: (id: number) => void
}

export const KOS_SHELL: InjectionKey<KosShell> = Symbol('kos-shell')

export function useShell(): KosShell {
  const shell = inject(KOS_SHELL)
  if (!shell) throw new Error('模块页面必须在 App 外壳内使用（缺少 kos-shell 注入）')
  return shell
}
