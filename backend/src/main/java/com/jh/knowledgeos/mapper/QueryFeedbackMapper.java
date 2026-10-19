package com.jh.knowledgeos.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** Persistence boundary for answer feedback and its operator workflow. */
public interface QueryFeedbackMapper {

    int upsert(@Param("code") String code, @Param("queryId") String queryId, @Param("kb") Long kb,
        @Param("actor") Long actor, @Param("rating") String rating, @Param("reason") String reason,
        @Param("comment") String comment, @Param("suggestion") String suggestion);

    Map<String, Object> findByQueryAndActor(@Param("queryId") String queryId, @Param("actor") Long actor);

    Map<String, Object> getTraceOwner(@Param("queryId") String queryId);

    List<Map<String, Object>> listMine(@Param("kb") Long kb, @Param("actor") Long actor, @Param("limit") Integer limit);

    List<Map<String, Object>> listOperations(@Param("kb") Long kb, @Param("status") String status, @Param("limit") Integer limit);

    Map<String, Object> summary(@Param("kb") Long kb);

    Map<String, Object> getById(@Param("id") Long id);

    int review(@Param("id") Long id, @Param("status") String status, @Param("reviewer") Long reviewer,
        @Param("note") String note);
}
