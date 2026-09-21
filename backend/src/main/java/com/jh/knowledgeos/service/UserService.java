package com.jh.knowledgeos.service;

import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.mapper.AuthMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Account directory: creates and maintains console accounts and their group membership.
 *
 * <p>Every account is mirrored into {@code t_acl_subject} under the same code so the
 * existing ACL tag resolution keeps working for logged-in users.</p>
 */
@Service
public class UserService {
    /** 旧角色名：升级后仍允许出现在历史数据里，但不再作为可分配角色。 */
    private static final String LEGACY_EDITOR = "EDITOR";
    private static final String LEGACY_VIEWER = "VIEWER";

    private final AuthMapper mapper;
    private final PasswordHasher hasher;
    private final IdGenerator ids;

    public UserService(AuthMapper mapper, PasswordHasher hasher, IdGenerator ids) {
        this.mapper = mapper;
        this.hasher = hasher;
        this.ids = ids;
    }

    public List<Map<String, Object>> list() {
        Map<Long, List<Long>> groupIds = groupIndex();
        Map<Long, List<String>> groupNames = groupNameIndex();
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (User user : mapper.listUsers()) {
            out.add(profile(user, groupIds.get(user.getId()), groupNames.get(user.getId())));
        }
        return out;
    }

    public Map<String, Object> profile(User user) {
        return profile(user, groupIdsOf(user.getId()), groupNamesOf(user.getId()));
    }

    /** 角色字典：角色码、中文名、职责说明与自带权限码，供用户管理与权限说明使用。 */
    public List<Map<String, Object>> roles() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (UserRole role : UserRole.values()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("code", role.name());
            row.put("label", role.label());
            row.put("homeView", role.homeView());
            row.put("description", ROLE_DESCRIPTIONS.get(role.name()));
            row.put("permissions", new ArrayList<String>(role.permissions()));
            out.add(row);
        }
        return out;
    }

    private static final Map<String, String> ROLE_DESCRIPTIONS = new LinkedHashMap<String, String>();

    static {
        ROLE_DESCRIPTIONS.put(UserRole.ADMIN.name(), "平台级配置：用户与组织、角色权限、模型、索引、任务告警、审计、Schema 与知识空间");
        ROLE_DESCRIPTIONS.put(UserRole.KNOWLEDGE_ADMIN.name(), "知识域全功能：知识工作台、知识生成（含裁决与衍生）、知识检索（含策略与评测）");
        ROLE_DESCRIPTIONS.put(UserRole.KNOWLEDGE_OPERATOR.name(), "简化知识生成：上传文件、生成知识数据、查看知识对象；可检索，但不能裁决、不能衍生、不能配置策略与测评");
        ROLE_DESCRIPTIONS.put(UserRole.KNOWLEDGE_AUDITOR.name(), "知识运营员 + 知识裁决 + 知识测评");
        ROLE_DESCRIPTIONS.put(UserRole.KNOWLEDGE_VIEWER.name(), "可查看知识工作台（总览 / 资产浏览 / 版本 / 质量）与知识检索，不能改动知识");
    }

    public Map<String, Object> create(
            String username,
            String password,
            String displayName,
            String email,
            String role,
            List<Long> groupIds) {
        String name = normalizeUsername(username);
        if (mapper.getUserByUsername(name) != null) {
            throw new IllegalArgumentException("用户名已存在：" + name);
        }
        User user = new User();
        user.setUserCode(ids.next("USR_"));
        user.setUsername(name);
        user.setDisplayName(displayName == null || displayName.trim().isEmpty() ? name : displayName.trim());
        user.setEmail(email == null ? "" : email.trim());
        user.setPasswordHash(hasher.hash(requirePassword(password)));
        user.setRole(resolveRole(role).name());
        user.setStatus("ACTIVE");
        mapper.insertUser(user);
        replaceMemberships(user.getId(), groupIds);
        mapper.insertAclSubject(user.getUserCode(), user.getDisplayName());
        return profile(user, groupIds, null);
    }

    public Map<String, Object> update(
            Long id,
            String displayName,
            String email,
            String role,
            List<Long> groupIds) {
        User user = requireUser(id);
        if (displayName != null && !displayName.trim().isEmpty()) user.setDisplayName(displayName.trim());
        if (email != null) user.setEmail(email.trim());
        if (role != null && !role.trim().isEmpty()) user.setRole(resolveRole(role).name());
        mapper.updateUserProfile(user);
        if (groupIds != null) replaceMemberships(id, groupIds);
        return profile(user);
    }

    public void updateStatus(Long id, String status) {
        User user = requireUser(id);
        boolean disabled = !"ACTIVE".equalsIgnoreCase(String.valueOf(status));
        if (disabled && UserRole.ADMIN.name().equals(user.getRole()) && countActiveAdmins() <= 1) {
            throw new IllegalArgumentException("至少保留一个启用状态的系统管理员");
        }
        mapper.updateUserStatus(id, disabled ? "DISABLED" : "ACTIVE");
        if (disabled) mapper.revokeUserSessions(id);
    }

    public void resetPassword(Long id, String password) {
        requireUser(id);
        mapper.updateUserPassword(id, hasher.hash(requirePassword(password)));
        mapper.revokeUserSessions(id);
    }

    public List<Long> groupIdsOf(Long userId) {
        List<Long> groupIds = new ArrayList<Long>();
        for (Map<String, Object> row : mapper.listAllGroupMembers()) {
            if (userId.equals(toLong(row.get("userId")))) {
                groupIds.add(toLong(row.get("groupId")));
            }
        }
        return groupIds;
    }

    public List<String> groupNamesOf(Long userId) {
        List<String> names = new ArrayList<String>();
        for (Map<String, Object> row : mapper.listAllGroupMembers()) {
            if (userId.equals(toLong(row.get("userId")))) {
                names.add(String.valueOf(row.get("groupName")));
            }
        }
        return names;
    }

    public User requireUser(Long id) {
        User user = mapper.getUserById(id);
        if (user == null) throw new IllegalArgumentException("用户不存在：" + id);
        return user;
    }

    public boolean isGroupMember(Long userId, Long groupId) {
        return groupId != null && userId != null && mapper.countGroupMember(groupId, userId) > 0;
    }

    private void replaceMemberships(Long userId, List<Long> groupIds) {
        mapper.deleteGroupMembers(userId == null ? 0L : userId);
        if (groupIds == null) return;
        for (Long groupId : groupIds) {
            if (groupId != null) mapper.insertGroupMember(groupId, userId);
        }
    }

    private int countActiveAdmins() {
        int count = 0;
        for (User user : mapper.listUsers()) {
            if (UserRole.ADMIN.name().equals(user.getRole()) && "ACTIVE".equalsIgnoreCase(String.valueOf(user.getStatus()))) {
                count++;
            }
        }
        return count;
    }

    private String normalizeUsername(String username) {
        String name = username == null ? "" : username.trim();
        if (name.length() < 3 || name.length() > 64) {
            throw new IllegalArgumentException("用户名长度需为 3-64 个字符");
        }
        if (!name.matches("[A-Za-z0-9_.@-]+")) {
            throw new IllegalArgumentException("用户名只能包含字母、数字、下划线、点、@ 与连字符");
        }
        return name;
    }

    private String requirePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("密码至少 8 位");
        }
        return password;
    }

    private UserRole resolveRole(String role) {
        if (role == null || role.trim().isEmpty()) return UserRole.KNOWLEDGE_VIEWER;
        String raw = role.trim();
        if (!UserRole.assignable().contains(raw.toUpperCase(Locale.ROOT))
                && !LEGACY_EDITOR.equalsIgnoreCase(raw) && !LEGACY_VIEWER.equalsIgnoreCase(raw)) {
            throw new IllegalArgumentException("未知角色：" + role);
        }
        return UserRole.of(raw);
    }

    private Map<String, Object> profile(User user, List<Long> groupIds, List<String> groupNames) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", user.getId());
        out.put("userCode", user.getUserCode());
        out.put("username", user.getUsername());
        out.put("displayName", user.getDisplayName());
        out.put("email", user.getEmail());
        out.put("role", user.getRole());
        out.put("roleLabel", UserRole.of(user.getRole()).label());
        out.put("status", user.getStatus());
        out.put("lastLoginTime", user.getLastLoginTime() == null ? null : String.valueOf(user.getLastLoginTime()));
        out.put("groupIds", groupIds == null ? Collections.emptyList() : groupIds);
        out.put("groupNames", groupNames == null ? new ArrayList<String>() : groupNames);
        return out;
    }

    private Map<Long, List<Long>> groupIndex() {
        Map<Long, List<Long>> index = new HashMap<Long, List<Long>>();
        for (Map<String, Object> row : mapper.listAllGroupMembers()) {
            Long userId = toLong(row.get("userId"));
            Long groupId = toLong(row.get("groupId"));
            if (userId == null || groupId == null) continue;
            List<Long> list = index.get(userId);
            if (list == null) {
                list = new ArrayList<Long>();
                index.put(userId, list);
            }
            list.add(groupId);
        }
        return index;
    }

    private Map<Long, List<String>> groupNameIndex() {
        Map<Long, List<String>> index = new HashMap<Long, List<String>>();
        for (Map<String, Object> row : mapper.listAllGroupMembers()) {
            Long userId = toLong(row.get("userId"));
            if (userId == null) continue;
            List<String> list = index.get(userId);
            if (list == null) {
                list = new ArrayList<String>();
                index.put(userId, list);
            }
            list.add(String.valueOf(row.get("groupName")));
        }
        return index;
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
