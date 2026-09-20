package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.UserGroup;
import com.jh.knowledgeos.mapper.AuthMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** User groups are the sharing unit for GROUP-visible knowledge bases. */
@Service
public class UserGroupService {
    private final AuthMapper mapper;
    private final IdGenerator ids;

    public UserGroupService(AuthMapper mapper, IdGenerator ids) {
        this.mapper = mapper;
        this.ids = ids;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (UserGroup group : mapper.listGroups()) {
            out.add(view(group));
        }
        return out;
    }

    public Map<String, Object> create(String name, String description) {
        UserGroup group = new UserGroup();
        group.setGroupCode(ids.next("GRP_"));
        group.setName(requireName(name));
        group.setDescription(description == null ? "" : description);
        group.setStatus("ACTIVE");
        mapper.insertGroup(group);
        return view(group);
    }

    public Map<String, Object> update(Long id, String name, String description) {
        UserGroup group = requireGroup(id);
        if (name != null && !name.trim().isEmpty()) group.setName(requireName(name));
        if (description != null) group.setDescription(description);
        mapper.updateGroup(group);
        return view(group);
    }

    public void updateStatus(Long id, String status) {
        requireGroup(id);
        mapper.updateGroupStatus(id, "ACTIVE".equalsIgnoreCase(String.valueOf(status)) ? "ACTIVE" : "ARCHIVED");
    }

    public Map<String, Object> setMembers(Long id, List<Long> memberIds) {
        requireGroup(id);
        mapper.deleteGroupMembers(id);
        if (memberIds != null) {
            for (Long userId : memberIds) {
                if (userId != null) mapper.insertGroupMember(id, userId);
            }
        }
        return view(requireGroup(id));
    }

    public UserGroup requireGroup(Long id) {
        UserGroup group = mapper.getGroup(id);
        if (group == null) throw new IllegalArgumentException("用户组不存在：" + id);
        return group;
    }

    public List<Long> memberIdsOf(Long groupId) {
        List<Long> ids = new ArrayList<Long>();
        for (Map<String, Object> row : mapper.listAllGroupMembers()) {
            if (groupId.equals(toLong(row.get("groupId")))) {
                ids.add(toLong(row.get("userId")));
            }
        }
        return ids;
    }

    private String requireName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.length() > 255) {
            throw new IllegalArgumentException("用户组名称不能为空且不超过 255 个字符");
        }
        return value;
    }

    private Map<String, Object> view(UserGroup group) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", group.getId());
        out.put("groupCode", group.getGroupCode());
        out.put("name", group.getName());
        out.put("description", group.getDescription());
        out.put("status", group.getStatus());
        out.put("memberCount", group.getMemberCount());
        out.put("memberIds", memberIdsOf(group.getId()));
        return out;
    }

    private Long toLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
