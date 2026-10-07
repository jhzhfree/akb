export type { KnowledgeBase, KnowledgeCard, Claim, KnowledgeDocument, KnowledgeSource, Overview, SourcePreview } from '../types-legacy'

/** 资产浏览行：不同对象类型字段不同，统一按索引访问，具体列由对象类型决定。 */
export type AssetRow = Record<string, unknown>

/** 单个召回通道的配置：是否参与与融合权重。 */
export interface KbChannelSetting{enabled:boolean;weight:number}

/** 知识库场景档案：生成（切片与抽取）、衍生、查询三段配置。 */
export interface KbScenarioProfile{
  generation:{
    chunk:{
      strategy:string;chunkSize:number;chunkOverlap:number;minChunkSize:number;measure:string
      keepStructure:boolean;respectSentence:boolean;parentChild:boolean
    }
    extract:{claims:boolean;entityResolution:boolean;conflictDetection:boolean}
  }
  derivation:{graphEnabled:boolean;presets:string[]}
  query:{
    defaultStrategies:string[]
    channels:Record<string,KbChannelSetting>
    recallMultiplier:number;minRecallCandidates:number
    rerankEnabled:boolean;rerankTopN:number
    rrfK:number;qualitySignalsEnabled:boolean;chunkBoostEnabled:boolean
    answerFormat:string;minChunkEvidence:number;contextMaxChars:number
  }
}

/** 内置场景模板：label 与 summary 说明它适合什么，default 是它的推荐配置。 */
export interface KbScenarioTemplate{
  code:string;label:string;summary:string
  suitedFor:string;docTypes:string[];default:KbScenarioProfile
}

/** 场景编辑器的可选值与取值范围，由后端下发，前端不再自行维护第二份。 */
export interface KbScenarioSchema{
  scenarios:KbScenarioTemplate[]
  channels:{name:string;label:string;description:string}[]
  answerFormats:{name:string;label:string;description:string}[]
  chunk:{strategies:{name:string;label:string;description:string}[];measures:{name:string;label:string}[]}
  derivation:{presets:{code:string;label:string;description:string;scene:string}[];relationTypes:string[]}
  ranges:Record<string,{min:number;max:number}>
}

/** 知识库当前场景配置：生效档案、所属模板与可选值字典。 */
export interface KbScenarioSettings{
  scenario:string;profile:KbScenarioProfile;template:KbScenarioProfile
  customized:boolean;updatedBy:string|null;updatedTime:string|null
  schema:KbScenarioSchema
}

/** 资产浏览分页结果：total 是 ACL + 关键字过滤后的命中总数，与工作台 KPI 同源。 */
export interface AssetPage {
  /** 后端归一化后的对象类型：SOURCE / DOCUMENT / CARD / CLAIM / ENTITY / RELATION */
  type:string
  keyword:string
  items:AssetRow[]
  total:number
  page:number
  size:number
  pageCount:number
}
