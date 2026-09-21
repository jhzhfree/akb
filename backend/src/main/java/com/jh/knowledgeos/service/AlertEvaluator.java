package com.jh.knowledgeos.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Turns metrics into alerts on a clock.
 *
 * <p>Alert rules are configuration, not code: nothing happens until something measures them. The
 * evaluator walks every enabled rule, raises an alert when a threshold is crossed and resolves it
 * again when the metric recovers, so the console shows what is wrong now instead of everything
 * that was ever wrong.</p>
 */
@Service
public class AlertEvaluator {
    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

    private final AlertService alerts;

    @Value("${knowledge.alerts.enabled:true}")
    private boolean enabled;

    public AlertEvaluator(AlertService alerts) {
        this.alerts = alerts;
    }

    @Scheduled(fixedDelayString = "${knowledge.alerts.poll-interval-ms:60000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            // Platform wide rules are measured across every knowledge base; rules owned by one
            // knowledge base are measured against that knowledge base alone.
            Map<String, Object> outcome = alerts.evaluate(null);
            Object raised = outcome.get("raised");
            if (raised != null && Integer.parseInt(String.valueOf(raised)) > 0) {
                log.info("alert evaluation raised {} alerts", raised);
            }
        } catch (Exception e) {
            log.warn("alert evaluation failed: {}", e.getMessage());
        }
    }
}
