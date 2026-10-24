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

    PlatformEvent getPlatformEventByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /**
     * Newest-first unless the caller asks for {@code ascending}, which is what SSE replay uses so
     * reconnected clients receive missed events in the original order.
     *
     * <p>{@code untilId} pages backwards for the console history, {@code sinceId} walks forward for
     * replay, and {@code keyword} matches the human-readable columns rather than the detail payload,
     * so a search stays cheap on a table that only grows.</p>
     */
    List<PlatformEvent> listPlatformEvents(@Param("kb") Long kb, @Param("types") List<String> types, @Param("sinceId") Long sinceId,
        @Param("untilId") Long untilId, @Param("keyword") String keyword, @Param("ascending") boolean ascending, @Param("limit") int limit);

    int deletePlatformEventsBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    Long minPlatformEventId();

    Long maxPlatformEventId();
}
