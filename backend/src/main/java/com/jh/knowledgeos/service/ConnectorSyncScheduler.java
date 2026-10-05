package com.jh.knowledgeos.service;

import com.jh.knowledgeos.common.ScheduleExpression;
import com.jh.knowledgeos.mapper.ConnectorMapper;
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
 * Fires connectors whose sync schedule came due.
 *
 * <p>A connector pointed at Jira or Confluence is a promise that the catalogue reflects the source.
 * Kept by hand, that promise expires the moment somebody edits a ticket, so the schedule is part of
 * the connector: whoever configures the source also decides how often it is re-read.</p>
 *
 * <p>The loop is deliberately modest: it claims one connector at a time, and always moves the next
 * fire time forward — including after a failure. A source that stops being read because one run hit
 * a network error is how an external catalogue silently turns into a lie, so the failure is recorded
 * and the next attempt stays scheduled.</p>
 */
@Service
public class ConnectorSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConnectorSyncScheduler.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 一轮最多拉起多少个连接器：外部系统慢，别让一轮把轮询线程占满。 */
    private static final int BATCH = 20;

    private final ConnectorMapper mapper;

    private final ConnectorCatalogService catalog;

    @Value("${knowledge.connector.sync-enabled:true}")
    private boolean enabled;

    public ConnectorSyncScheduler(ConnectorMapper mapper, ConnectorCatalogService catalog) {
        this.mapper = mapper;
        this.catalog = catalog;
    }

    @Scheduled(fixedDelayString = "${knowledge.connector.sync-poll-interval-ms:60000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        List<Map<String, Object>> due = mapper.listDueConnectors(LocalDateTime.now().format(TS), BATCH);
        for (Map<String, Object> row : due) {
            Long id = row.get("id") == null ? null : Long.valueOf(String.valueOf(row.get("id")));
            String cron = row.get("syncCron") == null ? "" : String.valueOf(row.get("syncCron")).trim();
            if (id == null || cron.isEmpty()) {
                continue;
            }
            // 抢占失败说明另一个实例已经在跑这一个：跳过即可，不用排队重来。
            if (mapper.claimConnector(id) <= 0) {
                continue;
            }
            try {
                Map<String, Object> result = catalog.syncInternal(id);
                mapper.updateSyncOutcome(id, "IDLE", nextFire(cron), null);
                log.info("connector scheduled sync id={} synced={} archived={}", id, result.get("synced"), result.get("archived"));
            } catch (RuntimeException ex) {
                String message = ex.getMessage() == null ? "同步失败" : ex.getMessage();
                log.warn("connector scheduled sync failed id={} message={}", id, message);
                mapper.updateSyncOutcome(id, "FAILED", nextFire(cron), message.length() > 500 ? message.substring(0, 500) : message);
            }
        }
    }

    private String nextFire(String cron) {
        try {
            return ScheduleExpression.of(cron).nextAfter(LocalDateTime.now()).format(TS);
        } catch (RuntimeException ex) {
            // 周期写坏了也要往下排：宁可晚点再试，也不要让这一行永远停在 FAILED 上不再触发。
            return LocalDateTime.now().plusHours(1).format(TS);
        }
    }
}
