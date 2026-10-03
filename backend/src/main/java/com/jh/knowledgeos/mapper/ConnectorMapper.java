package com.jh.knowledgeos.mapper;

import com.jh.knowledgeos.domain.ConnectorSource;
import org.apache.ibatis.annotations.Param;
import java.util.List;

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
}
