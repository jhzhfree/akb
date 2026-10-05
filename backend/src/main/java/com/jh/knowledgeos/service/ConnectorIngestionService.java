package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.PlatformEventTypes;
import com.jh.knowledgeos.domain.ConnectorItem;
import com.jh.knowledgeos.domain.ConnectorItemBinding;
import com.jh.knowledgeos.domain.ConnectorSource;
import com.jh.knowledgeos.mapper.ConnectorItemMapper;
import com.jh.knowledgeos.mapper.ConnectorMapper;
import com.jh.knowledgeos.mapper.DocumentMapper;
import com.jh.knowledgeos.config.ConnectorAsyncExecutorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns catalog items into knowledge.
 *
 * <p>This service owns no extraction logic. It renders an item into the document model the
 * ingestion pipeline already consumes and hands it to {@link DocumentIngestionService}, so a Jira
 * ticket ends up as the same source / document / chunk / evidence / card / claim rows as an
 * uploaded file, indexed through the same outbox events and retrievable through the same channels.
 * What is connector-specific here is only the bookkeeping: which item became which document, and
 * whether the remote system has changed since.</p>
 *
 * <p>Ingestion is explicit — syncing a connector only refreshes the catalog. Remote systems hold
 * far more noise than a curated document set, and extraction is not free, so nothing becomes
 * knowledge until someone asks for it. A connector can opt into {@code ingestMode=AUTO}, in which
 * case a sync catches up on new and changed items within a per-pass quota: the operator still
 * chooses what is in scope, they just no longer have to press the button.</p>
 *
 * <p>Whatever the remote system does, the knowledge base follows: an item the source no longer
 * returns is retired out of retrieval, and an item that changed is re-extracted over its previous
 * version. Otherwise retrieval would keep answering with content that no longer exists upstream.</p>
 */
@Service
public class ConnectorIngestionService {

    /** One bulk call stays answerable; larger selections are meant to run in batches. */
    public static final int MAX_BATCH = 20;

    private static final Logger log = LoggerFactory.getLogger(ConnectorIngestionService.class);

    private static final String INGESTED = "INGESTED";

    private static final String STALE = "STALE";

    private static final String FAILED = "FAILED";

    /** The source no longer lists this item: the binding is kept for audit, the knowledge is gone. */
    private static final String ARCHIVED = "ARCHIVED";

    private final ConnectorMapper connectorMapper;

    private final PlatformEventPublisher events;

    private final ConnectorItemMapper itemMapper;

    private final ConnectorItemNormalizer normalizer;

    private final DocumentIngestionService ingestion;

    private final ChunkPolicyService chunkPolicy;

    private final KnowledgeBaseAccessService access;

    private final DocumentMapper documentMapper;

    private final AiClient aiClient;

    private final TaskCenterService taskCenterService;

    private final ObjectMapper objectMapper;

    /** 批量入库的线程池：一条条目一次模型抽取，一批就是一串模型调用，不能在请求线程上跑。 */
    private final ThreadPoolTaskExecutor executor;

    public ConnectorIngestionService(ConnectorMapper connectorMapper, ConnectorItemMapper itemMapper, ConnectorItemNormalizer normalizer,
        DocumentIngestionService ingestion, ChunkPolicyService chunkPolicy, KnowledgeBaseAccessService access, DocumentMapper documentMapper,
        AiClient aiClient, TaskCenterService taskCenterService, PlatformEventPublisher events, ObjectMapper objectMapper,
        @Qualifier(ConnectorAsyncExecutorConfig.BEAN_NAME) ThreadPoolTaskExecutor executor) {
        this.executor = executor;
        this.connectorMapper = connectorMapper;
        this.itemMapper = itemMapper;
        this.normalizer = normalizer;
        this.ingestion = ingestion;
        this.chunkPolicy = chunkPolicy;
        this.access = access;
        this.documentMapper = documentMapper;
        this.aiClient = aiClient;
        this.taskCenterService = taskCenterService;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /**
     * Ingests one item. Re-ingesting an unchanged item is a no-op unless {@code force} is set:
     * the remote system is polled often and extraction is the expensive half of the pipeline.
     */
    public Map<String, Object> ingest(Long connectorId, Long itemId, boolean force) {
        ConnectorSource source = require(connectorId);
        access.assertWrite(source.getKnowledgeBaseId());
        ConnectorItem item = itemMapper.get(itemId);
        if (item == null || item.getConnectorId() == null || !item.getConnectorId().equals(connectorId)) {
            throw new IllegalArgumentException("条目不存在：" + itemId);
        }
        Map<String, Object> parsed = normalizer.normalize(source, item, chunkPolicy.policyFor(source.getKnowledgeBaseId()));
        String hash = String.valueOf(parsed.get("sha256"));
        ConnectorIngestConfig policy = ConnectorIngestConfig.of(source, objectMapper);
        ConnectorItemBinding existing = itemMapper.findBinding(itemId);
        if (existing != null && INGESTED.equals(existing.getIngestStatus())) {
            if (hash.equals(existing.getContentHash()) && !force) {
                Map<String, Object> skipped = new LinkedHashMap<String, Object>();
                skipped.put("status", "SKIPPED");
                skipped.put("itemId", itemId);
                skipped.put("documentId", existing.getDocumentId());
                skipped.put("message", "源端内容未变化，已跳过（如需重抽请强制入库）");
                return skipped;
            }
            if (!force) {
                ConnectorItemBinding binding = binding(source, item, hash);
                binding.setIngestStatus(STALE);
                binding.setLastIngestMessage("源端已更新，等待重新入库");
                itemMapper.upsertBinding(binding);
                Map<String, Object> pending = new LinkedHashMap<String, Object>();
                pending.put("status", "STALE");
                pending.put("itemId", itemId);
                pending.put("documentId", existing.getDocumentId());
                pending.put("message", "源端内容已变化，重新入库会替换现有卡片与断言");
                return pending;
            }
            // Re-ingesting: the previous version has to leave the index first, otherwise both
            // revisions answer the same question and one of them is wrong.
            retire(source.getKnowledgeBaseId(), existing);
            publish(source, PlatformEventTypes.CONNECTOR_ITEM_SUPERSEDED, item, "源端已更新，重新抽取并替换旧版本");
        }
        Map<String, Object> task = taskCenterService.enqueue(source.getKnowledgeBaseId(), TaskCenterService.TYPE_INGESTION,
            "外部条目入库：" + item.getTitle(), "CONNECTOR_ITEM", String.valueOf(itemId), TaskCenterService.TRIGGER_MANUAL, taskPayload(connectorId,
                itemId));
        String taskCode = String.valueOf(task.get("taskCode"));
        taskCenterService.start(taskCode, "EXTRACT");
        try {
            Map<String, Object> result = ingestion.ingestNormalized(source.getKnowledgeBaseId(), name(item), uri(source, item), ".connector",
                "text/plain", policy.authorityScore(), policy.permissionTags(), parsed);
            ConnectorItemBinding binding = binding(source, item, hash);
            binding.setSourceId(toLong(result.get("sourceId")));
            binding.setDocumentId(toLong(result.get("documentId")));
            binding.setIngestStatus(INGESTED);
            binding.setLastIngestMessage("已生成 " + result.get("chunkCount") + " 个分片");
            itemMapper.upsertBinding(binding);
            result.put("status", "INGESTED");
            result.put("itemId", itemId);
            result.put("externalId", item.getExternalId());
            taskCenterService.succeed(taskCode, result);
            publish(source, PlatformEventTypes.CONNECTOR_ITEM_INGESTED, item, "已生成 " + result.get("chunkCount") + " 个分片");
            log.info("connector item ingested connector={} item={} document={}", connectorId, itemId, result.get("documentId"));
            return result;
        } catch (RuntimeException ex) {
            ConnectorItemBinding binding = binding(source, item, hash);
            binding.setIngestStatus(FAILED);
            binding.setLastIngestMessage(clip(ex.getMessage(), 400));
            itemMapper.upsertBinding(binding);
            taskCenterService.fail(taskCode, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Ingests what the console is currently looking at. Deliberately bounded: the call has to
     * return while the operator is still watching, and a thousand tickets is a scheduled job, not
     * a button press.
     */
    public Map<String, Object> ingestSelection(Long connectorId, String type, String keyword, List<String> attributes, int limit) {
        ConnectorSource source = require(connectorId);
        access.assertWrite(source.getKnowledgeBaseId());
        int size = Math.min(Math.max(limit <= 0 ? 10 : limit, 1), MAX_BATCH);
        List<ConnectorItem> rows = itemMapper.page(connectorId, blank(type), blank(keyword), ConnectorCatalogService.parseFilters(attributes), 0,
            size);
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>(rows.size());
        int ingested = 0;
        int skipped = 0;
        int failed = 0;
        for (ConnectorItem row : rows) {
            try {
                Map<String, Object> result = ingest(connectorId, row.getId(), false);
                String status = String.valueOf(result.get("status"));
                if ("INGESTED".equals(status)) {
                    ingested++;
                } else {
                    skipped++;
                }
                results.add(compact(row, result));
            } catch (RuntimeException ex) {
                failed++;
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("itemId", row.getId());
                entry.put("externalId", row.getExternalId());
                entry.put("status", "FAILED");
                entry.put("message", clip(ex.getMessage(), 200));
                results.add(entry);
            }
        }
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("connectorId", connectorId);
        summary.put("requested", rows.size());
        summary.put("ingested", ingested);
        summary.put("skipped", skipped);
        summary.put("failed", failed);
        summary.put("items", results);
        summary.put("message", "入库 " + ingested + " 条，跳过 " + skipped + " 条，失败 " + failed + " 条");
        return summary;
    }

    /** Per-item ingestion state the catalog list renders next to each row. */
    public List<Map<String, Object>> statuses(Long connectorId) {
        ConnectorSource source = require(connectorId);
        access.assertRead(source.getKnowledgeBaseId());
        return itemMapper.bindingStatus(connectorId);
    }

    /**
     * Drops a previous version out of retrieval: the index copy is deleted first, then the
     * authoritative row is marked superseded so no view claims it is current.
     */
    private void retire(Long kb, ConnectorItemBinding binding) {
        Long documentId = binding.getDocumentId();
        if (documentId == null) {
            return;
        }
        try {
            aiClient.delete(AiApiPaths.INDEX_DOCUMENT_PREFIX + kb + "/" + documentId);
        } catch (RuntimeException ex) {
            // The replacement still has to be ingested: a stale copy in the index is worse than
            // a clean delete that has to be retried, but neither should lose the new version.
            log.warn("connector item retire index failed document={} message={}", documentId, ex.getMessage());
        }
        try {
            documentMapper.updateDocumentState(documentId, "SUPERSEDED", "READY", "被新版外部条目替换", 0);
        } catch (RuntimeException ex) {
            log.warn("connector item retire document failed id={} message={}", documentId, ex.getMessage());
        }
    }

    private ConnectorItemBinding binding(ConnectorSource source, ConnectorItem item, String hash) {
        ConnectorItemBinding binding = new ConnectorItemBinding();
        binding.setConnectorId(source.getId());
        binding.setConnectorItemId(item.getId());
        binding.setKnowledgeBaseId(source.getKnowledgeBaseId());
        binding.setContentHash(hash);
        binding.setRemoteUpdatedAt(item.getRemoteUpdatedAt());
        return binding;
    }

    /** Console name: the external id is what people search Jira by, so it leads. */
    private String name(ConnectorItem item) {
        String externalId = item.getExternalId() == null ? "" : item.getExternalId().trim();
        String title = item.getTitle() == null ? "" : item.getTitle().trim();
        if (externalId.isEmpty()) {
            return title.isEmpty() ? "未命名条目" : title;
        }
        return title.isEmpty() ? externalId : externalId + " · " + title;
    }

    /** Where the knowledge came from, so a citation can send the reader back to the source. */
    private String uri(ConnectorSource source, ConnectorItem item) {
        String url = item.getItemUrl();
        if (url != null && !url.trim().isEmpty()) {
            return url.trim();
        }
        return source.getBaseUrl() == null ? "" : source.getBaseUrl();
    }

    /** How this connector is configured to ingest: shown in the console so the knobs are visible. */
    public Map<String, Object> policy(Long connectorId) {
        ConnectorSource source = require(connectorId);
        access.assertRead(source.getKnowledgeBaseId());
        return ConnectorIngestConfig.of(source, objectMapper).describe();
    }

    /**
     * Runs one automatic pass without waiting for a sync: new items in scope are ingested, and
     * items whose remote content changed are re-extracted, both within the connector's quota.
     */
    /**
     * 批量入库的异步入口：一次点击可能要把二十条条目逐条送进模型，放在请求线程上只会换来一个
     * 网关超时，所以这里只负责提交，进度由 {@code /ingest/status} 反映。
     */
    public Map<String, Object> ingestSelectionDeferred(final Long connectorId, final String type, final String keyword,
        final List<String> attributes, final int limit) {
        submit(connectorId, "批量入库", new Runnable() {

            @Override
            public void run() {
                ingestSelection(connectorId, type, keyword, attributes, limit);
            }
        });
        return deferred(connectorId, "已提交批量入库，正在后台处理");
    }

    /** 同上：按入库设置跑一轮也交给后台。 */
    public Map<String, Object> runAutoDeferred(final Long connectorId) {
        submit(connectorId, "按设置入库", new Runnable() {

            @Override
            public void run() {
                runAuto(connectorId);
            }
        });
        return deferred(connectorId, "已按入库设置提交一轮，正在后台处理");
    }

    private void submit(Long connectorId, String what, Runnable task) {
        try {
            executor.execute(task);
        } catch (RuntimeException ex) {
            // 线程池拒收时退回调用线程：这一次慢一点，也好过用户以为点成功了其实什么都没做。
            log.warn("connector {} rejected id={} message={}", what, connectorId, ex.getMessage());
            task.run();
        }
    }

    private Map<String, Object> deferred(Long connectorId, String message) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("connectorId", connectorId);
        result.put("scheduled", Boolean.TRUE);
        result.put("message", message + "；完成后刷新即可看到入库状态");
        return result;
    }

    public Map<String, Object> runAuto(Long connectorId) {
        ConnectorSource source = require(connectorId);
        access.assertWrite(source.getKnowledgeBaseId());
        ConnectorIngestConfig policy = ConnectorIngestConfig.of(source, objectMapper);
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        Set<Long> bound = new LinkedHashSet<Long>(itemMapper.boundItemIds(connectorId, null));
        int quota = policy.quota();
        int ingested = 0;
        int failed = 0;
        // New items first: reading a ticket for the first time teaches more than re-reading one.
        for (ConnectorItem item : itemMapper.recent(connectorId, ConnectorIngestConfig.SCAN_LIMIT)) {
            if (ingested >= quota) {
                break;
            }
            if (bound.contains(item.getId()) || !policy.accepts(item)) {
                continue;
            }
            try {
                Map<String, Object> result = ingest(connectorId, item.getId(), false);
                if (INGESTED.equals(String.valueOf(result.get("status")))) {
                    ingested++;
                }
            } catch (RuntimeException ex) {
                failed++;
                log.warn("connector auto ingest failed connector={} item={} message={}", connectorId, item.getId(), ex.getMessage());
            }
        }
        // Whatever quota is left goes to items the source has changed since their last extraction.
        int refreshed = 0;
        for (Long itemId : itemMapper.boundItemIds(connectorId, INGESTED)) {
            if (ingested + refreshed >= quota) {
                break;
            }
            ConnectorItem item = itemMapper.get(itemId);
            if (item == null || !policy.accepts(item) || !remoteChanged(source, item)) {
                continue;
            }
            try {
                Map<String, Object> result = ingest(connectorId, itemId, true);
                if (INGESTED.equals(String.valueOf(result.get("status")))) {
                    refreshed++;
                }
            } catch (RuntimeException ex) {
                failed++;
                log.warn("connector auto refresh failed connector={} item={} message={}", connectorId, itemId, ex.getMessage());
            }
        }
        summary.put("connectorId", connectorId);
        summary.put("ingested", ingested);
        summary.put("refreshed", refreshed);
        summary.put("failed", failed);
        summary.put("quota", quota);
        summary.put("message", "自动入库 " + ingested + " 条，重抽 " + refreshed + " 条，失败 " + failed + " 条");
        return summary;
    }

    /**
     * What a sync leaves behind. Items the source stopped returning are retired out of retrieval —
     * a knowledge base that keeps answering with deleted upstream content is worse than one that
     * admits the gap. Then an automatic connector catches up, within its quota.
     *
     * @param complete whether the sync saw the whole remote list; a truncated sync must not be read
     *                 as "everything else was deleted"
     */
    public Map<String, Object> afterSync(Long connectorId, Set<String> presentExternalIds, boolean complete) {
        ConnectorSource source = require(connectorId);
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        if (complete) {
            summary.put("archived", archiveMissing(source, presentExternalIds));
        } else {
            // The remote list was cut off at the sync cap; treating the remainder as deleted would
            // retire knowledge that is still perfectly good.
            summary.put("archived", 0);
            summary.put("archiveSkipped", true);
            log.info("connector sync truncated, skip archive connector={}", connectorId);
        }
        if (ConnectorIngestConfig.of(source, objectMapper).auto()) {
            summary.put("auto", true);
            summary.putAll(runAuto(connectorId));
        } else {
            summary.put("auto", false);
        }
        return summary;
    }

    /**
     * Retires everything this connector contributed. Called when the connector itself is deleted:
     * without the source there is no way to know whether the content is still true, and knowledge
     * that can never be refreshed is worse than an honest gap.
     */
    public int retireAll(Long connectorId) {
        ConnectorSource source = require(connectorId);
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        int retired = 0;
        for (Long itemId : itemMapper.boundItemIds(connectorId, INGESTED)) {
            ConnectorItemBinding binding = itemMapper.findBinding(itemId);
            if (binding == null || binding.getDocumentId() == null) {
                continue;
            }
            retire(source.getKnowledgeBaseId(), binding);
            retired++;
        }
        summary.put("connectorId", connectorId);
        summary.put("retired", retired);
        log.info("connector retired all connector={} retired={}", connectorId, retired);
        return retired;
    }

    /** Retires every kept item the source no longer lists, and drops it from the catalog. */
    private int archiveMissing(ConnectorSource source, Set<String> presentExternalIds) {
        int archived = 0;
        for (Map<String, Object> row : itemMapper.externalIndex(source.getId())) {
            String externalId = String.valueOf(row.get("externalId"));
            if (presentExternalIds.contains(externalId)) {
                continue;
            }
            Long itemId = toLong(row.get("id"));
            if (itemId == null) {
                continue;
            }
            ConnectorItemBinding binding = itemMapper.findBinding(itemId);
            if (binding != null && INGESTED.equals(binding.getIngestStatus()) && binding.getDocumentId() != null) {
                retire(source.getKnowledgeBaseId(), binding);
                itemMapper.updateBindingStatus(itemId, ARCHIVED, "源端已删除，已从检索中移除");
                publish(source, PlatformEventTypes.CONNECTOR_ITEM_ARCHIVED, itemId, externalId, "源端已删除，对应知识与索引已移除");
                archived++;
            } else if (binding != null) {
                itemMapper.updateBindingStatus(itemId, ARCHIVED, "源端已删除");
            }
            itemMapper.deleteById(itemId);
        }
        return archived;
    }

    /** Whether the remote content differs from what was extracted last time. */
    private boolean remoteChanged(ConnectorSource source, ConnectorItem item) {
        ConnectorItemBinding binding = itemMapper.findBinding(item.getId());
        if (binding == null || binding.getContentHash() == null) {
            return false;
        }
        Map<String, Object> parsed = normalizer.normalize(source, item, chunkPolicy.policyFor(source.getKnowledgeBaseId()));
        return !binding.getContentHash().equals(String.valueOf(parsed.get("sha256")));
    }

    private void publish(ConnectorSource source, String type, ConnectorItem item, String summary) {
        publish(source, type, item.getId(), item.getExternalId(), summary);
    }

    private void publish(ConnectorSource source, String type, Long itemId, String externalId, String summary) {
        try {
            Map<String, Object> detail = new LinkedHashMap<String, Object>();
            detail.put("connectorId", source.getId());
            detail.put("connectorName", source.getName());
            detail.put("itemId", itemId);
            detail.put("externalId", externalId);
            events.emitSystem(type, source.getKnowledgeBaseId(), "CONNECTOR_ITEM", String.valueOf(itemId), summary, detail);
        } catch (RuntimeException ex) {
            // Observability must never break ingestion.
            log.warn("connector event publish failed type={} item={} message={}", type, itemId, ex.getMessage());
        }
    }

    private ConnectorIngestConfig config(ConnectorSource source) {
        return ConnectorIngestConfig.of(source, objectMapper);
    }

    private Map<String, Object> taskPayload(Long connectorId, Long itemId) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("connectorId", connectorId);
        payload.put("itemId", itemId);
        return payload;
    }

    private Map<String, Object> compact(ConnectorItem row, Map<String, Object> result) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("itemId", row.getId());
        entry.put("externalId", row.getExternalId());
        entry.put("status", result.get("status"));
        entry.put("documentId", result.get("documentId"));
        entry.put("message", result.get("message"));
        return entry;
    }

    private ConnectorSource require(Long connectorId) {
        ConnectorSource source = connectorMapper.get(connectorId);
        if (source == null) {
            throw new IllegalArgumentException("连接器不存在：" + connectorId);
        }
        return source;
    }

    private String blank(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String clip(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
