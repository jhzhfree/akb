package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.VerifiedCaller;
import com.jh.knowledgeos.domain.WorkloadIdentity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.MDC;

/** Request-owned context. Never accepts tool arguments, never holds keys or proof tokens. */
public final class CallerContext {
    private CallerContext() { }
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private static final class Trail {
        final AtomicInteger reads = new AtomicInteger();
        final List<Map<String, Object>> decisions = Collections.synchronizedList(new ArrayList<>());
    }
    private static final class Frame {
        VerifiedCaller caller;
        final Trail trail;
        Frame(VerifiedCaller caller, Trail trail) { this.caller = caller; this.trail = trail; }
    }
    public static final class Scope implements AutoCloseable {
        private final Frame previous;
        private boolean closed;
        private Scope(Frame frame) { previous = CURRENT.get(); CURRENT.set(frame); }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
    public static Scope beginRequest() { return new Scope(new Frame(null, new Trail())); }
    public static Scope open(ApiKey key) {
        return new Scope(new Frame(VerifiedCaller.fromAuthenticated(key, MDC.get("traceId")), new Trail()));
    }
    /** No global state is created outside an explicit request scope. */
    public static void authenticated(ApiKey key) {
        Frame frame = CURRENT.get();
        if (frame != null) frame.caller = VerifiedCaller.fromAuthenticated(key, MDC.get("traceId"));
    }
    public static boolean hasCaller() {
        Frame frame = CURRENT.get();
        return frame != null && frame.caller != null;
    }
    public static VerifiedCaller current() {
        Frame frame = CURRENT.get();
        if (frame == null || frame.caller == null) throw new SecurityException("No authenticated caller in request context");
        frame.trail.reads.incrementAndGet();
        return frame.caller;
    }
    public static WorkloadIdentity requireAgent() {
        WorkloadIdentity identity = current().getAgent();
        if (identity == null) throw new SecurityException("Verified Agent identity required");
        return identity;
    }
    /** Explicit propagation for awaited async work; ordinary pool tasks inherit nothing. */
    public static <T> Callable<T> wrap(Callable<T> task) {
        VerifiedCaller caller = current();
        Trail trail = CURRENT.get().trail;
        return () -> { try (Scope ignored = new Scope(new Frame(caller, trail))) { return task.call(); } };
    }
    public static Runnable wrap(Runnable task) {
        Frame frame = CURRENT.get();
        if (frame == null || frame.caller == null) return task;
        VerifiedCaller caller = frame.caller;
        return () -> { try (Scope ignored = new Scope(new Frame(caller, frame.trail))) { task.run(); } };
    }
    public static void decision(String action, Long resource, boolean allowed, String reason) {
        Frame frame = CURRENT.get();
        if (frame == null || frame.caller == null) return;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("action", action); entry.put("knowledgeBaseInternalId", resource);
        entry.put("decision", allowed ? "allow" : "deny"); entry.put("reason", reason);
        synchronized (frame.trail.decisions) {
            if (frame.trail.decisions.size() < 32) frame.trail.decisions.add(Collections.unmodifiableMap(entry));
        }
    }
    public static Map<String, Object> audit() {
        Frame frame = CURRENT.get();
        if (frame == null || frame.caller == null) return Collections.emptyMap();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caller", frame.caller.describe()); out.put("identityReads", frame.trail.reads.get());
        synchronized (frame.trail.decisions) { out.put("authorizationDecisions", new ArrayList<>(frame.trail.decisions)); }
        return out;
    }
}
