package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.config.ConnectorAsyncExecutorConfig;
import com.jh.knowledgeos.domain.ConnectorItem;
import com.jh.knowledgeos.domain.ConnectorSource;
import com.jh.knowledgeos.mapper.ConnectorItemMapper;
import com.jh.knowledgeos.mapper.ConnectorMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The kept knowledge list of a connector: sync it down, then browse it by type and attributes.
 *
 * <p>Reading and browsing are two different problems. Reading (see {@link ConnectorFetchService})
 * talks to Jira on every call and throws the result away; browsing needs the items to stay, because
 * the console shows them grouped by their own type and filtered by their own attributes — a "缺陷"
 * is filtered by 状态 and 经办人, a "需求" by 模块. Neither exists in the external system as a
 * column, so the type is a column here and the attributes stay JSON.</p>
 *
 * <p>Everything lives in {@code t_connector_item} and nothing here writes to the knowledge tables:
 * an item is what Jira says today, not knowledge the platform has ingested and made authoritative.
 * Documents, cards, assertions and their retrieval are untouched.</p>
 */
@Service
public class ConnectorCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorCatalogService.class);

    /** One sync reads at most this many items: the catalog is for browsing, not for bulk export. */
    private static final int MAX_SYNC_ITEMS = 500;

    private static final int SYNC_PAGE = 100;

    /** Attribute facets are counted from a bounded sample so one huge catalog cannot stall the page. */
    private static final int FACET_SAMPLE = 2000;

    private static final int FACET_ATTRIBUTES = 8;

    private static final int FACET_VALUES = 12;

    private static final int RAW_LIMIT = 20000;

    /** Attribute keys come from external systems and are English; the console shows these labels. */
    private static final Map<String, String> ATTRIBUTE_LABELS = attributeLabels();

    private final ConnectorMapper connectorMapper;

    private final ConnectorItemMapper itemMapper;

    private final ConnectorFetchService fetchService;

    private final KnowledgeBaseAccessService access;

    private final ConnectorIngestionService ingestion;

    private final ObjectMapper objectMapper;

    /** 同步后置任务（失效传播、自动入库）的线程池：不在请求线程上跑模型调用。 */
    private final ThreadPoolTaskExecutor executor;

    public ConnectorCatalogService(ConnectorMapper connectorMapper, ConnectorItemMapper itemMapper, ConnectorFetchService fetchService,
        KnowledgeBaseAccessService access, ConnectorIngestionService ingestion, ObjectMapper objectMapper,
        @Qualifier(ConnectorAsyncExecutorConfig.BEAN_NAME) ThreadPoolTaskExecutor executor) {
        this.connectorMapper = connectorMapper;
        this.itemMapper = itemMapper;
        this.fetchService = fetchService;
        this.access = access;
        this.ingestion = ingestion;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /**
     * Pulls what the connector currently lists and keeps it. Existing items are updated in place
     * ({@code external_id} is unique per connector), so a second sync refreshes rather than doubles.
     */
    /**
     * 人手点的同步：先确认这个人能改这个知识库，再走同步本体。
     *
     * <p>这里只把「目录」同步做完就返回：失效传播与自动入库交给后台线程。它们每条都要调一次
     * AI 抽取，二十条变更就是二十次模型调用，放在请求线程里必然撞上网关超时（504）。</p>
     */
    public Map<String, Object> sync(Long connectorId) {
        ConnectorSource source = require(connectorId);
        access.assertWrite(source.getKnowledgeBaseId());
        return runSync(connectorId, true);
    }

    /**
     * 同步本体：拉目录 → 源端删掉的移出检索 → 自动入库模式下补齐与重抽。
     * 定时任务没有用户上下文（也不在乎多等几分钟），因此权限由调用方各自保证。
     */
    public Map<String, Object> syncInternal(Long connectorId) {
        return runSync(connectorId, false);
    }

    private Map<String, Object> runSync(Long connectorId, boolean deferAfterSync) {
        ConnectorSource source = require(connectorId);
        int synced = 0;
        Long remoteTotal = null;
        Set<String> present = new LinkedHashSet<String>();
        for (int offset = 0; offset < MAX_SYNC_ITEMS; ) {
            ConnectorFetchService.Page page = fetchService.fetch(source, SYNC_PAGE, offset);
            List<Map<String, Object>> items = page.getItems();
            if (items.isEmpty()) {
                break;
            }
            if (remoteTotal == null) {
                remoteTotal = page.getTotal();
            }
            for (Map<String, Object> item : items) {
                String externalId = String.valueOf(item.get("externalId"));
                if (externalId != null) {
                    present.add(externalId);
                }
                itemMapper.upsert(row(source, item));
                synced++;
            }
            offset += items.size();
            if (items.size() < SYNC_PAGE) {
                break;
            }
        }
        connectorMapper.updateSyncResult(connectorId, synced);
        // A sync that stopped at the cap never saw the tail of the remote list, so the items it did
        // not return are unknown rather than deleted — archiving them would be a false positive.
        boolean complete = remoteTotal == null || remoteTotal <= synced;
        Map<String, Object> after = deferAfterSync ? deferAfterSync(connectorId, present, complete)
            : ingestion.afterSync(connectorId, present, complete);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("connectorId", connectorId);
        result.put("synced", synced);
        result.put("remoteTotal", remoteTotal);
        result.put("storedTotal", itemMapper.countByConnector(connectorId));
        result.put("archived", after.get("archived"));
        result.put("autoIngested", after.get("ingested"));
        result.put("autoRefreshed", after.get("refreshed"));
        result.put("ingestScheduled", Boolean.TRUE.equals(after.get("ingestScheduled")));
        result.put("message", "已同步 " + synced + " 条" + (remoteTotal == null ? "" : "（远端共 " + remoteTotal + " 条）")
            + afterSyncNote(after));
        log.info("connector catalog synced id={} synced={} archived={} ingested={}", connectorId, synced, after.get("archived"),
            after.get("ingested"));
        return result;
    }

    /**
     * 把失效传播与自动入库交给后台线程，请求先回。
     * 返回值里带上 ingestScheduled，前端据此提示「入库在后台进行」而不是假装已经完成。
     */
    private Map<String, Object> deferAfterSync(Long connectorId, Set<String> present, boolean complete) {
        Map<String, Object> placeholder = new LinkedHashMap<String, Object>();
        placeholder.put("archived", 0);
        placeholder.put("ingested", 0);
        placeholder.put("refreshed", 0);
        placeholder.put("ingestScheduled", Boolean.TRUE);
        final Set<String> ids = new LinkedHashSet<String>(present);
        try {
            executor.execute(new Runnable() {

                @Override
                public void run() {
                    try {
                        Map<String, Object> after = ingestion.afterSync(connectorId, ids, complete);
                        log.info("connector deferred ingest done id={} archived={} ingested={} refreshed={}", connectorId,
                            after.get("archived"), after.get("ingested"), after.get("refreshed"));
                    } catch (RuntimeException ex) {
                        // 后台失败不能吞掉：控制台看不到，只有日志会说话。
                        log.warn("connector deferred ingest failed id={} message={}", connectorId, ex.getMessage());
                    }
                }
            });
            return placeholder;
        } catch (RuntimeException ex) {
            // 线程池拒收（CallerRuns 已兜底，这里只可能是提交路径本身出错）：退回同步执行，
            // 至少让这次同步的结果是真的。
            log.warn("connector deferred ingest rejected id={} message={}", connectorId, ex.getMessage());
            return ingestion.afterSync(connectorId, ids, complete);
        }
    }

    private String afterSyncNote(Map<String, Object> after) {
        StringBuilder note = new StringBuilder();
        Object archived = after.get("archived");
        if (archived instanceof Number && ((Number) archived).intValue() > 0) {
            note.append("，源端已删除 ").append(archived).append(" 条并移出检索");
        }
        Object ingested = after.get("ingested");
        if (ingested instanceof Number && ((Number) ingested).intValue() > 0) {
            note.append("，自动入库 ").append(ingested).append(" 条");
        }
        Object refreshed = after.get("refreshed");
        if (refreshed instanceof Number && ((Number) refreshed).intValue() > 0) {
            note.append("，重抽 ").append(refreshed).append(" 条");
        }
        return note.toString();
    }

    /** One page of kept items, filtered by type, keyword and any number of {@code key=value} attributes. */
    public Map<String, Object> page(Long connectorId, String type, String keyword, List<String> attributes, int page, int size) {
        ConnectorSource source = require(connectorId);
        access.assertRead(source.getKnowledgeBaseId());
        int pageSize = Math.min(Math.max(size <= 0 ? 20 : size, 1), 100);
        int pageNo = Math.max(page <= 0 ? 1 : page, 1);
        List<ConnectorItemMapper.AttributeFilter> filters = parseFilters(attributes);
        long total = itemMapper.count(connectorId, blank(type), blank(keyword), filters);
        List<ConnectorItem> rows = itemMapper.page(connectorId, blank(type), blank(keyword), filters, (pageNo - 1) * pageSize, pageSize);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (ConnectorItem row : rows) {
            items.add(view(row, false));
        }
        Map<String, Object> connector = new LinkedHashMap<String, Object>();
        connector.put("id", source.getId());
        connector.put("name", source.getName());
        connector.put("type", source.getType());
        connector.put("baseUrl", source.getBaseUrl());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("connector", connector);
        result.put("items", items);
        result.put("total", total);
        result.put("page", pageNo);
        result.put("size", pageSize);
        result.put("lastSyncTime", source.getLastSyncTime());
        result.put("lastSyncCount", source.getLastSyncCount());
        return result;
    }

    /** Type counts plus, for the selected type, the values each attribute actually takes. */
    public Map<String, Object> facets(Long connectorId, String type) {
        ConnectorSource source = require(connectorId);
        access.assertRead(source.getKnowledgeBaseId());
        List<Map<String, Object>> types = new ArrayList<Map<String, Object>>();
        long all = 0;
        for (Map<String, Object> row : itemMapper.typeCounts(connectorId)) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            Object value = row.get("itemType");
            entry.put("value", value == null ? "ITEM" : String.valueOf(value));
            entry.put("count", ((Number) row.get("total")).longValue());
            types.add(entry);
            all += ((Number) row.get("total")).longValue();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("types", types);
        result.put("total", all);
        result.put("attributes", attributeFacets(connectorId, blank(type)));
        result.put("lastSyncTime", source.getLastSyncTime());
        result.put("lastSyncCount", source.getLastSyncCount());
        return result;
    }

    public Map<String, Object> detail(Long itemId) {
        ConnectorItem row = itemMapper.get(itemId);
        if (row == null) {
            throw new IllegalArgumentException("条目不存在：" + itemId);
        }
        ConnectorSource source = require(row.getConnectorId());
        access.assertRead(source.getKnowledgeBaseId());
        Map<String, Object> detail = view(row, true);
        detail.put("connectorName", source.getName());
        detail.put("connectorType", source.getType());
        return detail;
    }

    @SuppressWarnings("unchecked")
    private ConnectorItem row(ConnectorSource source, Map<String, Object> item) {
        ConnectorItem row = new ConnectorItem();
        row.setConnectorId(source.getId());
        row.setItemType(clip(text(item.get("type")), 64, "ITEM"));
        row.setExternalId(clip(text(item.get("externalId")), 200, ""));
        row.setTitle(clip(text(item.get("title")), 500, "(无标题)"));
        row.setItemUrl(clip(text(item.get("url")), 1000, null));
        Object excerpt = item.get("excerpt");
        row.setExcerpt(excerpt == null ? null : clip(String.valueOf(excerpt), 4000, null));
        Map<String, Object> attributes = item.get("attributes") instanceof Map
            ? (Map<String, Object>) item.get("attributes") : Collections.<String, Object>emptyMap();
        row.setAttributesJson(toJson(attributes));
        row.setRawJson(clip(toJson(item), RAW_LIMIT, null));
        row.setRemoteUpdatedAt(time(item.get("updatedAt")));
        return row;
    }

    private Map<String, Object> view(ConnectorItem row, boolean withRaw) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("id", row.getId());
        item.put("externalId", row.getExternalId());
        item.put("title", row.getTitle());
        item.put("type", row.getItemType());
        item.put("url", row.getItemUrl());
        item.put("excerpt", row.getExcerpt());
        item.put("updatedAt", row.getRemoteUpdatedAt());
        item.put("syncedTime", row.getSyncedTime());
        item.put("attributes", attributes(row));
        if (withRaw) {
            item.put("raw", row.getRawJson());
        }
        return item;
    }

    private Map<String, Object> attributes(ConnectorItem row) {
        Map<String, Object> parsed = parse(row.getAttributesJson());
        // 属性按「标签 + 值」返回：键是外部系统的字段名，前端不该去猜 status 是状态还是别的。
        Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
            Map<String, Object> attribute = new LinkedHashMap<String, Object>();
            attribute.put("key", entry.getKey());
            attribute.put("label", label(entry.getKey()));
            attribute.put("value", entry.getValue());
            attributes.put(entry.getKey(), attribute);
        }
        return attributes;
    }

    private List<Map<String, Object>> attributeFacets(Long connectorId, String type) {
        Map<String, Map<String, Long>> counts = new LinkedHashMap<String, Map<String, Long>>();
        for (String payload : itemMapper.attributePayloads(connectorId, type, FACET_SAMPLE)) {
            for (Map.Entry<String, Object> entry : parse(payload).entrySet()) {
                String value = text(entry.getValue());
                if (value.isEmpty()) {
                    continue;
                }
                Map<String, Long> values = counts.get(entry.getKey());
                if (values == null) {
                    values = new TreeMap<String, Long>();
                    counts.put(entry.getKey(), values);
                }
                Long count = values.get(value);
                values.put(value, count == null ? 1L : count + 1L);
            }
        }
        List<Map<String, Object>> facets = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Map<String, Long>> entry : counts.entrySet()) {
            if (facets.size() >= FACET_ATTRIBUTES) {
                break;
            }
            // 时间属性每条一个值，做维度没有意义：键名和取值形状各判一次，
            // 这样历史数据里的 created、以及名字不叫 created 的时间字段都不会出现在左侧。
            Map<String, Long> values = entry.getValue();
            String sample = values.isEmpty() ? null : values.keySet().iterator().next();
            if (ConnectorFetchService.isTimeAttribute(entry.getKey(), sample)) {
                continue;
            }
            Map<String, Object> facet = new LinkedHashMap<String, Object>();
            facet.put("key", entry.getKey());
            facet.put("label", label(entry.getKey()));
            facet.put("values", topValues(values));
            facets.add(facet);
        }
        return facets;
    }

    private List<Map<String, Object>> topValues(Map<String, Long> values) {
        List<Map.Entry<String, Long>> entries = new ArrayList<Map.Entry<String, Long>>(values.entrySet());
        Collections.sort(entries, (left, right) -> {
            int byCount = right.getValue().compareTo(left.getValue());
            return byCount != 0 ? byCount : left.getKey().compareTo(right.getKey());
        });
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Long> entry : entries) {
            if (out.size() >= FACET_VALUES) {
                break;
            }
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put("value", entry.getKey());
            value.put("count", entry.getValue());
            out.add(value);
        }
        return out;
    }

    /** Shared with ingestion: a selection made in the console is re-run as the same query. */
    public static List<ConnectorItemMapper.AttributeFilter> parseFilters(List<String> attributes) {
        List<ConnectorItemMapper.AttributeFilter> filters = new ArrayList<ConnectorItemMapper.AttributeFilter>();
        if (attributes == null) {
            return filters;
        }
        // 同一个 key 的多个取值先归并成一组：组内是「或」，组间才是「且」。
        Map<String, List<String>> grouped = new LinkedHashMap<String, List<String>>();
        for (String raw : attributes) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            int split = raw.indexOf('=');
            if (split <= 0 || split == raw.length() - 1) {
                continue;
            }
            String key = raw.substring(0, split).trim();
            String value = raw.substring(split + 1).trim();
            List<String> values = grouped.get(key);
            if (values == null) {
                values = new ArrayList<String>();
                grouped.put(key, values);
            }
            if (!values.contains(value)) {
                values.add(value);
            }
        }
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            filters.add(new ConnectorItemMapper.AttributeFilter(entry.getKey(), entry.getValue()));
        }
        return filters;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            return parsed == null ? Collections.<String, Object>emptyMap() : parsed;
        } catch (IOException ex) {
            log.warn("connector item attributes are not json message={}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException ex) {
            return null;
        }
    }

    private ConnectorSource require(Long connectorId) {
        ConnectorSource source = connectorMapper.get(connectorId);
        if (source == null) {
            throw new IllegalArgumentException("连接器不存在：" + connectorId);
        }
        return source;
    }

    /** External timestamps arrive in several shapes; anything unparseable simply has no time. */
    private Date time(Object value) {
        String text = text(value);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Date.from(Instant.parse(text));
        } catch (DateTimeParseException ignored) {
            // Jira 用 +0800 这种不带冒号的偏移，往下走
        }
        try {
            return Date.from(OffsetDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")).toInstant());
        } catch (DateTimeParseException ignored) {
            // 继续尝试本地时间
        }
        try {
            return Date.from(LocalDateTime.parse(text.replace('T', ' ').substring(0, Math.min(19, text.length())),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.systemDefault()).toInstant());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String label(String key) {
        return labelOf(key);
    }

    /** 入库时把属性写成中文行（「状态：已完成」），抽取出的断言才和人工文档一个语气。 */
    public static String labelOf(String key) {
        String label = ATTRIBUTE_LABELS.get(key);
        return label == null ? key : label;
    }

    private String clip(String value, int limit, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        String text = value.trim();
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private String blank(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Map<String, String> attributeLabels() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("status", "状态");
        labels.put("priority", "优先级");
        labels.put("assignee", "经办人");
        labels.put("reporter", "报告人");
        labels.put("project", "项目");
        labels.put("resolution", "解决结果");
        labels.put("labels", "标签");
        labels.put("components", "模块");
        labels.put("created", "创建时间");
        labels.put("space", "空间");
        labels.put("author", "作者");
        labels.put("version", "版本");
        return labels;
    }
}
