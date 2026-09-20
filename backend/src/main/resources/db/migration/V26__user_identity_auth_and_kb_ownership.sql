-- V26: 用户、用户组、登录会话，以及知识库的创建人与可见性（公开 / 私有 / 用户组）。
-- 认证体系与既有 ACL 体系是两层：这里解决"谁能登录、谁拥有知识库"，
-- ACL(t_acl_subject / permission_tags)继续解决"能看见哪些知识对象"。

CREATE TABLE IF NOT EXISTS t_user (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 user_code VARCHAR(64) NOT NULL,
 username VARCHAR(128) NOT NULL,
 display_name VARCHAR(255),
 email VARCHAR(255),
 password_hash VARCHAR(512) NOT NULL,
 role VARCHAR(32) NOT NULL DEFAULT 'VIEWER',
 status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
 last_login_time DATETIME NULL,
 created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_user_code(user_code),
 UNIQUE KEY uk_user_username(username),
 KEY idx_user_status(status)
);

CREATE TABLE IF NOT EXISTS t_user_group (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 group_code VARCHAR(64) NOT NULL,
 name VARCHAR(255) NOT NULL,
 description VARCHAR(1000),
 status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
 created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_group_code(group_code)
);

CREATE TABLE IF NOT EXISTS t_user_group_member (
 group_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY pk_group_member(group_id, user_id),
 KEY idx_group_member_user(user_id)
);

CREATE TABLE IF NOT EXISTS t_auth_session (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 token VARCHAR(128) NOT NULL,
 user_id BIGINT NOT NULL,
 issued_at DATETIME NOT NULL,
 expires_at DATETIME NOT NULL,
 last_accessed_at DATETIME NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 user_agent VARCHAR(500),
 created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_auth_session_token(token),
 KEY idx_auth_session_user(user_id),
 KEY idx_auth_session_expires(expires_at)
);

ALTER TABLE t_knowledge_base ADD COLUMN created_by BIGINT NULL AFTER description;
ALTER TABLE t_knowledge_base ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC' AFTER created_by;
ALTER TABLE t_knowledge_base ADD COLUMN owner_group_id BIGINT NULL AFTER visibility;
