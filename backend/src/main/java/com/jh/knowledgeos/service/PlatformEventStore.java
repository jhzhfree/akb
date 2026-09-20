package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.PlatformEvent;
import com.jh.knowledgeos.mapper.PlatformEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable side of the platform event bus.
 *
 * <p>Saving runs in its own transaction: events are emitted after the business transaction commits,
 * so a rolled-back operation can never leave rows behind, while an event that reached the table is
 * proof the operation really happened.</p>
 */
@Service
public class PlatformEventStore {
    private static final Logger log = LoggerFactory.getLogger(PlatformEventStore.class);

    private final PlatformEventMapper mapper;
    private final ObjectMapper objectMapper;

    public PlatformEventStore(PlatformEventMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PlatformEvent save(PlatformEvent event) {
        event.setDetailJson(toJson(event.getDetail()));
        if (event.getEventTime() == null) {
            event.setEventTime(LocalDateTime.now());
        }
        mapper.insertPlatformEvent(event);
        return event;
    }

    /** Replay for reconnecting clients: strictly what happened after the last delivered row. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<PlatformEvent> since(Long kb, List<String> types, Long sinceId, int limit) {
        return mapper.listPlatformEvents(kb, types, sinceId, true, limit);
    }

    /** Latest-first console view of the activity trail. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<PlatformEvent> recent(Long kb, List<String> types, int limit) {
        return mapper.listPlatformEvents(kb, types, null, false, limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeBefore(LocalDateTime cutoff) {
        if (cutoff == null) return 0;
        return mapper.deletePlatformEventsBefore(cutoff);
    }

    /** Detail stays readable even if the payload failed to serialise, so events never vanish. */
    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("platform event detail could not be serialised: {}", e.getMessage());
            return null;
        }
    }

    /** Parsed form of {@code detail_json}; kept here so no caller has to touch Jackson. */
    public Map<String, Object> detailOf(PlatformEvent event) {
        if (event == null || event.getDetailJson() == null || event.getDetailJson().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    event.getDetailJson(),
                    objectMapper.getTypeFactory().constructMapType(java.util.LinkedHashMap.class,
                            String.class, Object.class));
            return parsed == null ? new LinkedHashMap<String, Object>() : parsed;
        } catch (Exception e) {
            log.warn("platform event detail of {} is unreadable: {}", event.getEventId(), e.getMessage());
            return new LinkedHashMap<String, Object>();
        }
    }

    public Long currentId() {
        Long max = mapper.maxPlatformEventId();
        return max == null ? 0L : max;
    }

}
