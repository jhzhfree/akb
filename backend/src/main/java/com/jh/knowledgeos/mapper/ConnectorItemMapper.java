package com.jh.knowledgeos.mapper;

import com.jh.knowledgeos.domain.ConnectorItem;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/**
 * Persistence contract for the items a connector has listed and we have kept.
 *
 * <p>Two reads matter for browsing: a page filtered by type / keyword / attribute values, and the
 * raw attribute payloads the facet counts are computed from. The facets are deliberately computed
 * in Java from a bounded sample — MySQL can count {@code item_type}, but counting every value of
 * every schemaless attribute would mean one JSON expression per attribute per request.</p>
 */
public interface ConnectorItemMapper {

    /**
     * 一个属性上勾选的全部取值，例如状态 ∈ {已完成, 待办}。
     *
     * <p>同一属性的多个取值之间是「或」——勾选「已完成」和「待办」，想看的是这两种状态的合集，
     * 而不是「既已完成又待办」（那必然是空集）。不同属性之间才取「且」。</p>
     */
    class AttributeFilter {

        private final String key;

        private final List<String> values;

        public AttributeFilter(String key, List<String> values) {
            this.key = key;
            this.values = values;
        }

        public String getKey() {
            return key;
        }

        public List<String> getValues() {
            return values;
        }
    }

    int upsert(ConnectorItem item);

    int deleteByConnector(@Param("connectorId") Long connectorId);

    ConnectorItem get(@Param("id") Long id);

    List<ConnectorItem> page(@Param("connectorId") Long connectorId, @Param("type") String type,
        @Param("keyword") String keyword, @Param("filters") List<AttributeFilter> filters,
        @Param("offset") int offset, @Param("size") int size);

    long count(@Param("connectorId") Long connectorId, @Param("type") String type,
        @Param("keyword") String keyword, @Param("filters") List<AttributeFilter> filters);

    List<Map<String, Object>> typeCounts(@Param("connectorId") Long connectorId);

    /** Attribute payloads the facet counts are built from; bounded so one connector cannot stall a read. */
    List<String> attributePayloads(@Param("connectorId") Long connectorId, @Param("type") String type, @Param("limit") int limit);

    long countByConnector(@Param("connectorId") Long connectorId);
}
