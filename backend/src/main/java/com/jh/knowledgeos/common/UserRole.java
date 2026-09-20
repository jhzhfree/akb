package com.jh.knowledgeos.common;

/**
 * Platform roles. Knowledge-base write access is granted to admins, knowledge admins,
 * editors, and the owner of the knowledge base; viewers stay read-only.
 */
public enum UserRole {
    ADMIN,
    KNOWLEDGE_ADMIN,
    EDITOR,
    VIEWER;

    public static UserRole of(String value) {
        if (value == null) return VIEWER;
        for (UserRole role : values()) {
            if (role.name().equalsIgnoreCase(value.trim())) return role;
        }
        return VIEWER;
    }

    public boolean canManageUsers() { return this == ADMIN; }

    public boolean canManageKnowledgeBases() { return this == ADMIN || this == KNOWLEDGE_ADMIN; }

    public boolean canWriteKnowledge() { return this == ADMIN || this == KNOWLEDGE_ADMIN || this == EDITOR; }
}
