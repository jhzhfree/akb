package com.jh.knowledgeos.service;

import com.jh.knowledgeos.common.EventTaskContext;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.PlatformEvent;
import com.jh.knowledgeos.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Single entry point for publishing platform events.
 *
 * <p>Callers stay tiny: they describe what happened, the publisher attributes it to the current
 * principal, defers it until the business transaction is durable, and hands it to the hub off the
 * request thread. Publishing is best-effort by design — a bus failure must never roll back a
 * knowledge operation, it only logs.</p>
 */
@Service
public class PlatformEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(PlatformEventPublisher.class);

    public static final String SOURCE_UI = "UI";
    public static final String SOURCE_REST = "REST";
    public static final String SOURCE_MCP = "MCP";
    public static final String SOURCE_SSE = "SSE";
    public static final String SOURCE_SYSTEM = "SYSTEM";

    public static final String ACTOR_USER = "USER";
    public static final String ACTOR_API_KEY = "API_KEY";
    public static final String ACTOR_SYSTEM = "SYSTEM";

    private final PlatformEventStore store;
    private final PlatformEventHub hub;
    private final IdGenerator ids;
    private final Executor fanout;

    public PlatformEventPublisher(
            PlatformEventStore store,
            PlatformEventHub hub,
            IdGenerator ids,
            @Qualifier("platformEventFanoutExecutor") Executor fanout) {
        this.store = store;
        this.hub = hub;
        this.ids = ids;
        this.fanout = fanout;
    }

    /** Event attributed to the signed-in console user. */
    public void emitUser(String type, Long kb, String source, String targetType, String targetCode,
            String summary, Map<String, Object> detail) {
        User user = AuthContext.get();
        emit(type, kb, source, targetType, targetCode, summary, detail, ACTOR_USER,
                user == null ? null : String.valueOf(user.getId()),
                user == null ? null : user.getUsername());
    }

    /** Event attributed to an external credential; also the path MCP and SSE channels use. */
    public void emitExternal(ApiKey key, String type, Long kb, String source, String targetType,
            String targetCode, String summary, Map<String, Object> detail) {
        emit(type, kb, source, targetType, targetCode, summary, detail, ACTOR_API_KEY,
                key == null ? null : key.getKeyCode(), key == null ? null : key.getName());
    }

    /** Event raised by a scheduler or internal engine rather than by a person. */
    public void emitSystem(String type, Long kb, String targetType, String targetCode,
            String summary, Map<String, Object> detail) {
        emit(type, kb, SOURCE_SYSTEM, targetType, targetCode, summary, detail,
                ACTOR_SYSTEM, null, "system");
    }

    /** Same as {@link #emitUser} but stamped with the asynchronous task that produced it. */
    public void emitUser(String type, Long kb, String source, String targetType, String targetCode,
            String summary, Map<String, Object> detail, EventTaskContext task) {
        User user = AuthContext.get();
        emit(type, kb, source, targetType, targetCode, summary, detail, ACTOR_USER,
                user == null ? null : String.valueOf(user.getId()),
                user == null ? null : user.getUsername(), task);
    }

    /** Same as {@link #emitSystem} but stamped with the asynchronous task that produced it. */
    public void emitSystem(String type, Long kb, String targetType, String targetCode,
            String summary, Map<String, Object> detail, EventTaskContext task) {
        emit(type, kb, SOURCE_SYSTEM, targetType, targetCode, summary, detail,
                ACTOR_SYSTEM, null, "system", task);
    }

    /**
     * Builds the event synchronously — the principal and trace id are thread-bound and would be
     * gone on any later thread — then persists it only once the surrounding transaction committed.
     */
    public void emit(String type, Long kb, String source, String targetType, String targetCode,
            String summary, Map<String, Object> detail, String actorType, String actorId, String actorName) {
        emit(type, kb, source, targetType, targetCode, summary, detail, actorType, actorId, actorName, null);
    }

    /**
     * Builds the event synchronously — the principal and trace id are thread-bound and would be
     * gone on any later thread — then persists it only once the surrounding transaction committed.
     */
    public void emit(String type, Long kb, String source, String targetType, String targetCode,
            String summary, Map<String, Object> detail, String actorType, String actorId, String actorName,
            EventTaskContext task) {
        if (type == null || type.isEmpty()) return;
        PlatformEvent event = new PlatformEvent();
        event.setEventId(ids.next("EV_"));
        event.setEventTime(LocalDateTime.now());
        event.setEventType(type);
        event.setKnowledgeBaseId(kb);
        event.setSource(source == null || source.isEmpty() ? SOURCE_SYSTEM : source);
        event.setActorType(actorType == null || actorType.isEmpty() ? ACTOR_SYSTEM : actorType);
        event.setActorId(actorId);
        event.setActorName(actorName);
        event.setTargetType(targetType);
        event.setTargetCode(targetCode);
        event.setSummary(summary);
        event.setDetail(detail == null ? Collections.<String, Object>emptyMap() : detail);
        event.setTraceId(MDC.get("traceId"));
        if (task != null) {
            event.setTaskId(task.getTaskId());
            event.setObjectId(task.getObjectId());
            event.setObjectVersion(task.getObjectVersion());
            event.setIdempotencyKey(task.getIdempotencyKey());
            event.setRetryCount(task.getRetryCount());
        } else {
            // retry_count is NOT NULL: an event with no task behind it simply has no retries,
            // and leaving it null would lose the event instead of storing it.
            event.setRetryCount(0);
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new AfterCommit(event));
        } else {
            dispatch(event);
        }
    }

    private void dispatch(PlatformEvent event) {
        fanout.execute(() -> {
            try {
                store.save(event);
                hub.publish(event);
            } catch (Exception e) {
                log.warn("platform event {} could not be published: {}", event.getEventType(), e.getMessage());
            }
        });
    }

    /** Envelope every subscriber receives, after the caller-specific visibility filter ran. */
    public static Map<String, Object> envelope(PlatformEvent event, Map<String, Object> detail) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("id", event.getId());
        body.put("eventId", event.getEventId());
        body.put("type", event.getEventType());
        body.put("time", event.getEventTime() == null ? null : event.getEventTime().toString());
        body.put("knowledgeBaseId", event.getKnowledgeBaseId());
        body.put("source", event.getSource());
        body.put("actor", actor(event));
        body.put("targetType", event.getTargetType());
        body.put("targetCode", event.getTargetCode());
        body.put("summary", event.getSummary());
        body.put("traceId", event.getTraceId());
        body.put("taskId", event.getTaskId());
        body.put("objectId", event.getObjectId());
        body.put("objectVersion", event.getObjectVersion());
        body.put("idempotencyKey", event.getIdempotencyKey());
        body.put("retryCount", event.getRetryCount());
        body.put("detail", detail);
        return body;
    }

    public static Map<String, Object> actor(PlatformEvent event) {
        Map<String, Object> actor = new LinkedHashMap<String, Object>();
        actor.put("type", event.getActorType());
        actor.put("id", event.getActorId());
        actor.put("name", event.getActorName());
        return actor;
    }

    private final class AfterCommit implements TransactionSynchronization {
        private final PlatformEvent event;

        private AfterCommit(PlatformEvent event) {
            this.event = event;
        }

        @Override
        public void suspend() {
        }

        @Override
        public void resume() {
        }

        @Override
        public void flush() {
        }

        @Override
        public void beforeCommit(boolean readOnly) {
        }

        @Override
        public void beforeCompletion() {
        }

        @Override
        public void afterCommit() {
            dispatch(event);
        }

        @Override
        public void afterCompletion(int status) {
        }
    }
}
