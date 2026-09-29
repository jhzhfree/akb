-- Impact vocabulary (design §9: CVE → component → product → project → customer).
--
-- The default dictionary named components, locations and ownership, but it had no word for
-- "this vulnerability affects that component" or "this product is deployed in that project".
-- An impact chain therefore had no typed edge to travel on: extraction collapsed every such
-- sentence into RELATED_TO, no traversal rule matched it, and "which customers are affected"
-- could only be answered by a language model guessing across documents. These labels make the
-- chain expressible, and the things it connects needed names of their own: a CVE is not a
-- product, and a customer is not an entry in an org chart.
INSERT INTO t_schema_type(knowledge_base_id,kind,type_code,label,description,status,built_in,updated_by)
VALUES(0,'RELATION_TYPE','AFFECTS','受影响于','漏洞、风险或变更对实体的影响关系','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','DEPLOYED_IN','部署于','产品、组件或服务在某个项目或环境中的部署关系','ACTIVE',1,'system')
ON DUPLICATE KEY UPDATE label=VALUES(label),description=VALUES(description),status='ACTIVE',built_in=1;

INSERT INTO t_schema_type(knowledge_base_id,kind,type_code,label,description,status,built_in,updated_by)
VALUES(0,'ENTITY_TYPE','VULNERABILITY','漏洞','安全漏洞、缺陷编号与风险项','ACTIVE',1,'system'),
      (0,'ENTITY_TYPE','PROJECT','项目','交付项目、实施项目或工程','ACTIVE',1,'system'),
      (0,'ENTITY_TYPE','CUSTOMER','客户','购买或使用交付成果的外部组织','ACTIVE',1,'system')
ON DUPLICATE KEY UPDATE label=VALUES(label),description=VALUES(description),status='ACTIVE',built_in=1;
