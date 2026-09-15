package com.jh.knowledgeos.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer adapter for outbox throughput, backlog, lag, failures, and recovery metrics.
 */
@Component
public class IndexOutboxMetrics {
    private final Counter succeeded; private final Counter retried; private final Counter dead; private final Counter staleRecovered;
    private final Counter archived; private final Timer delivery; private final AtomicLong pendingGauge=new AtomicLong();
    private final AtomicLong retryGauge=new AtomicLong(); private final AtomicLong deadGauge=new AtomicLong();
    private final AtomicLong lagGauge=new AtomicLong();
    public IndexOutboxMetrics(MeterRegistry registry){
        succeeded=registry.counter("llmkb.index.outbox.delivery", "result", "succeeded");
        retried=registry.counter("llmkb.index.outbox.delivery", "result", "retry");
        dead=registry.counter("llmkb.index.outbox.delivery", "result", "dead");
        staleRecovered=registry.counter("llmkb.index.outbox.stale.recovered"); archived=registry.counter("llmkb.index.outbox.archived");
        delivery=Timer.builder("llmkb.index.outbox.delivery.duration").publishPercentileHistogram().register(registry);
        Gauge.builder("llmkb.index.outbox.pending", pendingGauge, AtomicLong::get).register(registry);
        Gauge.builder("llmkb.index.outbox.retry", retryGauge, AtomicLong::get).register(registry);
        Gauge.builder("llmkb.index.outbox.dead", deadGauge, AtomicLong::get).register(registry);
        Gauge.builder("llmkb.index.outbox.oldest.lag.seconds", lagGauge, AtomicLong::get).register(registry);
    }
    public void deliverySucceeded(long ms){succeeded.increment(); delivery.record(java.time.Duration.ofMillis(ms));}
    public void deliveryFailed(long ms, boolean isDead){(isDead?dead:retried).increment(); delivery.record(java.time.Duration.ofMillis(ms));}
    public void staleRecovered(int n){staleRecovered.increment(n);}
    public void archived(int n){archived.increment(n);}
    public void updateBacklog(Map<String,Object> stats){
        pendingGauge.set(number(stats,"pending")); retryGauge.set(number(stats,"retryCount")); deadGauge.set(number(stats,"dead"));
        lagGauge.set(number(stats,"oldestLagSeconds"));
    }
    private long number(Map<String,Object> m,String k){Object v=m==null?null:m.get(k); if(v==null)return 0L; return Long.parseLong(String.valueOf(v));}
}
