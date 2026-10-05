package com.jh.knowledgeos.mapper;

import com.jh.knowledgeos.domain.ConnectorSource;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** Persistence contract for saved external data-source connectors. */
public interface ConnectorMapper {

    int insert(ConnectorSource source);

    int update(ConnectorSource source);

    int delete(@Param("id") Long id);

    ConnectorSource get(@Param("id") Long id);

    List<ConnectorSource> list(@Param("kb") Long kb);

    int updateTestResult(@Param("id") Long id, @Param("status") String status, @Param("message") String message);

    /** 同步结果：列表页要告诉用户这批数据有多新、有多少条。 */
    int updateSyncResult(@Param("id") Long id, @Param("count") int count);

    /** 到点的连接器：调度器按 next_fire_at 挑，没在跑的才算到点。 */
    List<Map<String, Object>> listDueConnectors(@Param("now") String now, @Param("limit") int limit);

    /** 抢占成功返回 1：多实例部署时只有一个实例会真正跑这一次。 */
    int claimConnector(@Param("id") Long id);

    int updateSyncOutcome(@Param("id") Long id, @Param("status") String status, @Param("nextFireAt") String nextFireAt,
        @Param("message") String message);
}
