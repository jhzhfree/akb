-- 补回平台内置的类型字典（knowledge_base_id=0）。
--
-- 现象：知识结构定义页面只显示「本库自定义」，「平台内置」一项都没有。
-- 原因：V36 与 V39 已成功执行过，但库里的 kb=0 内建行整批不存在（库被恢复/重建过），
-- 而 Flyway 已把 V36/V39 标记为已执行，不会重跑——所以缺的数据必须靠新迁移补齐。
--
-- 这里用 ON DUPLICATE KEY UPDATE（与 V39 相同的幂等写法）：
--   已存在的环境只刷新内置定义的文案，不新增重复行；缺失的环境整批补回。
-- 平台内置类型在服务端受保护（不可修改、不可删除），因此以平台定义为准是安全的。

INSERT INTO t_schema_type(knowledge_base_id,kind,type_code,label,description,status,built_in,updated_by)
VALUES(0,'KNOWLEDGE_TYPE','FACT','事实','可直接引用的客观事实与数据','ACTIVE',1,'system'),
      (0,'KNOWLEDGE_TYPE','PROCEDURE','流程','按步骤完成一项工作的操作序列','ACTIVE',1,'system'),
      (0,'KNOWLEDGE_TYPE','RULE','规则','约束、规范与判定标准','ACTIVE',1,'system'),
      (0,'KNOWLEDGE_TYPE','PROBLEM_SOLUTION','问题方案','问题现象与对应解决办法','ACTIVE',1,'system'),
      (0,'KNOWLEDGE_TYPE','CONCEPT','概念','术语、定义与原理说明','ACTIVE',1,'system')
ON DUPLICATE KEY UPDATE label=VALUES(label),description=VALUES(description),status='ACTIVE',built_in=1;

INSERT INTO t_schema_type(knowledge_base_id,kind,type_code,label,description,status,built_in,updated_by)
VALUES(0,'ENTITY_TYPE','TERM','术语','领域词汇与专有名词','ACTIVE',1,'system'),
      (0,'ENTITY_TYPE','PRODUCT','产品','产品、模块或服务','ACTIVE',1,'system'),
      (0,'ENTITY_TYPE','SYSTEM','系统','平台、子系统或组件','ACTIVE',1,'system'),
      (0,'ENTITY_TYPE','METRIC','指标','可度量的业务或技术指标','ACTIVE',1,'system'),
      (0,'ENTITY_TYPE','ORG','组织','部门、团队或外部机构','ACTIVE',1,'system'),
      (0,'ENTITY_TYPE','ROLE','角色','岗位、职责或使用者','ACTIVE',1,'system'),
      (0,'ENTITY_TYPE','VULNERABILITY','漏洞','安全漏洞、缺陷编号与风险项','ACTIVE',1,'system'),
      (0,'ENTITY_TYPE','PROJECT','项目','交付项目、实施项目或工程','ACTIVE',1,'system'),
      (0,'ENTITY_TYPE','CUSTOMER','客户','购买或使用交付成果的外部组织','ACTIVE',1,'system')
ON DUPLICATE KEY UPDATE label=VALUES(label),description=VALUES(description),status='ACTIVE',built_in=1;

INSERT INTO t_schema_type(knowledge_base_id,kind,type_code,label,description,status,built_in,updated_by)
VALUES(0,'RELATION_TYPE','RELATED_TO','相关','通用关联','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','PART_OF','组成部分','整体与部分','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','DEPENDS_ON','依赖','功能或运行依赖','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','USES','使用','使用某项能力或资源','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','CAUSES','导致','因果关系','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','BELONGS_TO','归属','归属关系','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','LOCATED_IN','位于','位置或部署关系','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','DESCRIBES','描述','实体与描述性知识的关系','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','CONTAINS','包含','容器与内容','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','MANAGES','管理','管理或负责关系','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','AFFECTS','受影响于','漏洞、风险或变更对实体的影响关系','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','DEPLOYED_IN','部署于','产品、组件或服务在某个项目或环境中的部署关系','ACTIVE',1,'system')
ON DUPLICATE KEY UPDATE label=VALUES(label),description=VALUES(description),status='ACTIVE',built_in=1;
