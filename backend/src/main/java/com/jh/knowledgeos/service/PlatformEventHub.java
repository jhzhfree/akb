package com.jh.knowledgeos.service;

import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.PlatformEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fan-out hub between platform events and long-lived SSE subscriptions.
 *
 * <p>Replaces the old polling subscription: a publisher hands one event in and the hub delivers it
 * to every eligible stream, instead of each stream re-reading counters every three seconds. Two
 * threads service all subscribers, so a stream costs memory rather than blocking the request pool,
 * which is what capped the previous implementation at eight concurrent subscriptions.</p>
 */
@Service
public class PlatformEventHub {

    private static final Logger log = LoggerFactory.getLogger(PlatformEventHub.class);

    private static final int MAX_REPLAY = 200;

    private static final int MAX_SUBSCRIPTIONS_PER_PRINCIPAL = 5;

    private final PlatformEventStore store;

    private final KnowledgeProperties.External properties;

    private final Executor fanout;

    private final ConcurrentMap<String, Subscription> subscriptions = new ConcurrentHashMap<String, Subscription>();

    private final ConcurrentMap<String, AtomicInteger> perPrincipal = new ConcurrentHashMap<String, AtomicInteger>();

    private final ScheduledExecutorService heartbeat;

    public PlatformEventHub(PlatformEventStore store, KnowledgeProperties properties, @Qualifier("platformEventFanoutExecutor") Executor fanout) {
        this.store = store;
        this.properties = properties.external();
        this.fanout = fanout;
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("kos-event-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long interval = Math.max(properties.external().getSseHeartbeatSeconds(), 3);
        heartbeat.scheduleAtFixedRate(this::heartbeat, interval, interval, TimeUnit.SECONDS);
    }

    /**
     * Decides per subscription whether an event may be delivered, and how much of it leaks out.
     *
     * <p>Publishers always attach the full payload; filtering is a subscriber property, because the
     * same event is simultaneously visible to an administrator and redacted for an external key.</p>
     */
    public interface Visibility {

        boolean visible(PlatformEvent event);

        Map<String, Object> project(PlatformEvent event, Map<String, Object> detail);
    }

    /**
     * Opens one stream, applying type filters, lifetime budget and per-credential connection caps.
     */
    public SseEmitter subscribe(String principal, Long knowledgeBaseId, Set<String> types, Long sinceEventId, Visibility visibility) {
        AtomicInteger held = perPrincipal.computeIfAbsent(principal, key -> new AtomicInteger());
        if (held.incrementAndGet() > MAX_SUBSCRIPTIONS_PER_PRINCIPAL) {
            held.decrementAndGet();
            throw new IllegalStateException("订阅数超出上限：每个凭据最多 " + MAX_SUBSCRIPTIONS_PER_PRINCIPAL + " 路 SSE 连接");
        }
        String subscriptionId = "SUB-" + System.currentTimeMillis() + "-" + subscriptions.size();
        SseEmitter emitter = new SseEmitter(0L);
        long budget = Math.max(properties.getSseMaxMinutes(), 1) * 60_000L;
        Subscription subscription = new Subscription(subscriptionId, principal, knowledgeBaseId, types, visibility, emitter,
            System.currentTimeMillis() + budget);
        subscriptions.put(subscriptionId, subscription);
        Runnable release = () -> {
            subscriptions.remove(subscriptionId);
            held.decrementAndGet();
        };
        emitter.onCompletion(release);
        emitter.onTimeout(() -> {
            release.run();
            emitter.complete();
        });
        emitter.onError(e -> {
            release.run();
            emitter.completeWithError(e);
        });
        Map<String, Object> open = new LinkedHashMap<String, Object>();
        open.put("subscriptionId", subscriptionId);
        open.put("knowledgeBaseId", knowledgeBaseId);
        open.put("types", types == null || types.isEmpty() ? "ALL" : types);
        open.put("replayFrom", sinceEventId == null ? store.currentId() : sinceEventId);
        open.put("maxMinutes", properties.getSseMaxMinutes());
        send(subscription, SseEmitter.event().name("open").data(open).reconnectTime(Math.max(properties.getSseHeartbeatSeconds(), 3) * 1000L));
        if (sinceEventId != null) {
            fanout.execute(() -> replay(subscription, sinceEventId));
        }
        return emitter;
    }

    public int activeSubscriptions() {
        return subscriptions.size();
    }

    /**
     * Persisted events are broadcast after commit; see {@link PlatformEventPublisher}.
     */
    public void publish(PlatformEvent event) {
        if (event == null || event.getEventType() == null) {
            return;
        }
        List<Subscription> targets = new ArrayList<Subscription>();
        for (Subscription subscription : subscriptions.values()) {
            if (subscription.matches(event)) {
                targets.add(subscription);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        fanout.execute(() -> {
            for (Subscription subscription : targets) {
                deliver(subscription, event);
            }
        });
    }

    /**
     * Replays missed rows after a reconnect, oldest first, using the durable log as truth.
     */
    private void replay(Subscription subscription, Long sinceEventId) {
        try {
            List<String> types = subscription.types == null || subscription.types.isEmpty() ? null : new ArrayList<String>(subscription.types);
            List<PlatformEvent> missed = store.since(subscription.knowledgeBaseId, types, sinceEventId, MAX_REPLAY);
            for (PlatformEvent event : missed) {
                if (!subscription.open.get()) {
                    return;
                }
                if (!subscription.matches(event)) {
                    continue;
                }
                deliver(subscription, event);
            }
        } catch (Exception e) {
            log.warn("event replay failed for {}: {}", subscription.id, e.getMessage());
        }
    }

    private void deliver(Subscription subscription, PlatformEvent event) {
        Map<String, Object> detail = store.detailOf(event);
        Map<String, Object> projected;
        try {
            projected = subscription.visibility.project(event, detail);
        } catch (Exception e) {
            log.warn("visibility projection failed for {}: {}", subscription.id, e.getMessage());
            projected = null;
        }
        if (projected == null) {
            return;
        }
        // One envelope shape for live delivery, replay and the history endpoint alike; the
        // per-subscriber difference is entirely inside "detail".
        Map<String, Object> body = PlatformEventPublisher.envelope(event, projected);
        send(subscription, SseEmitter.event().id(String.valueOf(event.getId())).name(event.getEventType()).data(body));
    }

    private void send(Subscription subscription, SseEmitter.SseEventBuilder builder) {
        if (!subscription.open.get()) {
            return;
        }
        try {
            subscription.emitter.send(builder);
        } catch (IOException e) {
            log.debug("subscription {} disconnected: {}", subscription.id, e.getMessage());
            subscription.open.set(false);
        } catch (Exception e) {
            log.warn("delivery to subscription {} failed: {}", subscription.id, e.getMessage());
            subscription.open.set(false);
        }
    }

    /**
     * Comment heartbeats keep intermediaries from closing idle streams; expiry is enforced here.
     */
    private void heartbeat() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Subscription>> iterator = subscriptions.entrySet().iterator();
        while (iterator.hasNext()) {
            Subscription subscription = iterator.next().getValue();
            if (!subscription.open.get() || now > subscription.deadline) {
                iterator.remove();
                subscription.open.set(false);
                try {
                    subscription.emitter.complete();
                } catch (Exception ignored) {
                    log.debug("completing subscription {} failed", subscription.id);
                }
                AtomicInteger held = perPrincipal.get(subscription.principal);
                if (held != null) {
                    held.decrementAndGet();
                }
                continue;
            }
            send(subscription, SseEmitter.event().comment("heartbeat " + now));
        }
    }

    private static final class Subscription {

        private final String id;

        private final String principal;

        private final Long knowledgeBaseId;

        private final Set<String> types;

        private final Visibility visibility;

        private final SseEmitter emitter;

        private final long deadline;

        private final AtomicBoolean open = new AtomicBoolean(true);

        private Subscription(String id, String principal, Long knowledgeBaseId, Set<String> types, Visibility visibility, SseEmitter emitter, long deadline) {
            this.id = id;
            this.principal = principal;
            this.knowledgeBaseId = knowledgeBaseId;
            this.types = types;
            this.visibility = visibility;
            this.emitter = emitter;
            this.deadline = deadline;
        }

        private boolean matches(PlatformEvent event) {
            if (knowledgeBaseId != null && event.getKnowledgeBaseId() != null && !knowledgeBaseId.equals(event.getKnowledgeBaseId())) {
                return false;
            }
            if (types != null && !types.isEmpty() && !types.contains(event.getEventType())) {
                return false;
            }
            try {
                return visibility.visible(event);
            } catch (Exception e) {
                log.warn("visibility check failed for {}: {}", id, e.getMessage());
                return false;
            }
        }
    }
}
