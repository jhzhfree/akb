-- 角色可见菜单配置。
--
-- 背景：去掉「用户视图 / 管理视图」这组二值开关后，菜单不再靠视图切换裁剪，
-- 而是「每个角色一份可见菜单配置」。缺省（该角色没有任何配置行）时按内置默认规则渲染，
-- 管理员在此配置后按配置渲染，可随时恢复默认。
--
-- 菜单项 key 与前端 navigation.ts 的 leaf.key 一一对应（38 项）；
-- 勾选的菜单项同时决定该角色获得的接口权限码（见 RoleMenuService.permissionsFor），
-- 因此改这份配置等于改角色的可访问范围，不只是改界面显示。
CREATE TABLE IF NOT EXISTS t_role_menu (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  role_code VARCHAR(32) NOT NULL COMMENT '角色码：ADMIN / KNOWLEDGE_ADMIN / KNOWLEDGE_OPERATOR / KNOWLEDGE_AUDITOR / KNOWLEDGE_VIEWER',
  leaf_key VARCHAR(64) NOT NULL COMMENT '菜单项 key，与前端 navigation.ts 的 leaf.key 一致',
  visible TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否对该角色可见',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_menu_role_leaf (role_code, leaf_key),
  KEY idx_role_menu_role (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色可见菜单配置：按角色裁剪模块功能菜单，缺省按内置默认规则';
