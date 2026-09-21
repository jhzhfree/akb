package com.jh.knowledgeos.common;

/**
 * Knowledge-base sharing scope.
 * PUBLIC: every signed-in user can read; PRIVATE: owner only; GROUP: owner plus group members.
 */
public enum KnowledgeBaseVisibility {

    PUBLIC, PRIVATE, GROUP;

    public static KnowledgeBaseVisibility of(String value) {
        if (value == null) {
            return PUBLIC;
        }
        for (KnowledgeBaseVisibility item : values()) {
            if (item.name().equalsIgnoreCase(value.trim())) {
                return item;
            }
        }
        return PUBLIC;
    }
}
