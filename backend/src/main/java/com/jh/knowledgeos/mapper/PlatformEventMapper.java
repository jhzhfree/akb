package com.jh.knowledgeos.mapper;

import com.jh.knowledgeos.domain.PlatformEvent;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Append-only platform event log: one insert path plus the replay / retention queries.
 */
public interface PlatformEventMapper {

    int insertPlatformEvent(PlatformEvent event);

    PlatformEvent getPlatformEventByEventId(@Param("eventId") String eventId);

    /**
     * Newest-first unless the caller asks for {@code ascending}, which is what SSE replay uses so
     * reconnected clients receive missed events in the original order.
     */
    List<PlatformEvent> listPlatformEvents(@Param("kb") Long kb, @Param("types") List<String> types, @Param("sinceId") Long sinceId, @Param("ascending") boolean ascending, @Param("limit") int limit);

    int deletePlatformEventsBefore(@Param("cutoff") LocalDateTime cutoff);

    Long maxPlatformEventId();
}
