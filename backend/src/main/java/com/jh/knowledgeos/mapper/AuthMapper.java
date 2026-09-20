package com.jh.knowledgeos.mapper;

import com.jh.knowledgeos.domain.AuthSession;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.domain.UserGroup;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** Persistence contract for accounts, user groups, and login sessions. */
public interface AuthMapper {
    User getUserByUsername(@Param("username") String username);
    User getUserById(@Param("id") Long id);
    List<User> listUsers();
    int countUsers();
    int insertUser(User user);
    int updateUserProfile(User user);
    int updateUserStatus(@Param("id") Long id, @Param("status") String status);
    int updateUserPassword(@Param("id") Long id, @Param("passwordHash") String passwordHash);
    int updateUserLoginTime(@Param("id") Long id);
    /** 账号与既有 ACL 主体对齐，使登录用户能直接参与权限标签解析。 */
    int insertAclSubject(@Param("subjectCode") String subjectCode, @Param("displayName") String displayName);

    List<UserGroup> listGroups();
    UserGroup getGroup(@Param("id") Long id);
    int insertGroup(UserGroup group);
    int updateGroup(UserGroup group);
    int updateGroupStatus(@Param("id") Long id, @Param("status") String status);
    int insertGroupMember(@Param("groupId") Long groupId, @Param("userId") Long userId);
    int deleteGroupMember(@Param("groupId") Long groupId, @Param("userId") Long userId);
    int deleteGroupMembers(@Param("groupId") Long groupId);
    int countGroupMember(@Param("groupId") Long groupId, @Param("userId") Long userId);
    List<Map<String, Object>> listAllGroupMembers();

    AuthSession getSession(@Param("token") String token);
    int insertSession(AuthSession session);
    int touchSession(@Param("token") String token);
    int revokeSession(@Param("token") String token);
    int revokeUserSessions(@Param("userId") Long userId);
    int deleteExpiredSessions();
}
