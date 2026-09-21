package com.jh.knowledgeos.service;

import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Fires planned derivations.
 *
 * <p>A rule with a schedule is only a promise until something checks the clock. The scheduler
 * claims due rules one at a time, marks them running so a slow run cannot be picked up twice, and
 * always moves the next fire time forward — including after a failure, because a rule that stops
 * firing because it failed once is how derived knowledge silently rots.</p>
 */
@Service
public class DerivationScheduler {

    private static final Logger log = LoggerFactory.getLogger(DerivationScheduler.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KnowledgeMapper mapper;

    private final KnowledgeDerivationService derivation;

    @Value("${knowledge.derivation.enabled:true}")
    private boolean enabled;

    public DerivationScheduler(KnowledgeMapper mapper, KnowledgeDerivationService derivation) {
        this.mapper = mapper;
        this.derivation = derivation;
    }

    @Scheduled(fixedDelayString = "${knowledge.derivation.poll-interval-ms:30000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        List<Map<String, Object>> due = mapper.listDueDerivationRules(LocalDateTime.now().format(TS));
        for (Map<String, Object> rule : due) {
            String ruleCode = String.valueOf(rule.get("ruleCode"));
            try {
                mapper.updateDerivationRuleState(ruleCode, "RUNNING", null, null, null);
                Long kb = rule.get("knowledgeBaseId") == null ? null : Long.valueOf(String.valueOf(rule.get("knowledgeBaseId")));
                derivation.run(kb, ruleCode, KnowledgeDerivationService.SCHEDULE_SCHEDULED);
            } catch (Exception e) {
                log.warn("planned derivation failed: rule={} message={}", ruleCode, e.getMessage());
                // Keep the schedule alive: the failure is visible on the rule, the next fire does
                // not disappear with it.
                mapper.updateDerivationRuleState(ruleCode, "FAILED", LocalDateTime.now().format(TS), nextFire(rule), e.getMessage());
            }
        }
    }

    private String nextFire(Map<String, Object> rule) {
        String cron = rule.get("cronExpr") == null ? "" : String.valueOf(rule.get("cronExpr"));
        if (cron.trim().isEmpty()) {
            return null;
        }
        try {
            return com.jh.knowledgeos.common.ScheduleExpression.of(cron).nextAfter(LocalDateTime.now()).format(TS);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
