package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * "Works out of the box" is a claim about what happens on the first visit: a knowledge base gets
 * the common rules with definitions that already run, asking again adds nothing, and a preset that
 * was edited can be put back without touching its schedule.
 */
class DerivationPresetServiceTest {

    private KnowledgeMapper mapper;

    private KnowledgeDerivationService service;

    private final Map<String, Map<String, Object>> rules = new LinkedHashMap<String, Map<String, Object>>();

    @BeforeEach
    void setUp() {
        rules.clear();
        mapper = mock(KnowledgeMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        IdGenerator ids = mock(IdGenerator.class);
        AtomicInteger seq = new AtomicInteger();
        when(ids.next(any())).thenAnswer(invocation -> "DR" + seq.incrementAndGet());
        when(mapper.listSchemaTypes(any(), any())).thenReturn(relationDictionary());
        when(mapper.insertDerivationRule(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Map<String, Object> rule = new LinkedHashMap<String, Object>();
            rule.put("ruleCode", String.valueOf((Object) invocation.getArgument(0)));
            rule.put("knowledgeBaseId", invocation.getArgument(1));
            rule.put("presetCode", invocation.getArgument(2));
            rule.put("name", invocation.getArgument(3));
            rule.put("derivationType", invocation.getArgument(4));
            rule.put("scheduleType", invocation.getArgument(5));
            rule.put("definitionJson", invocation.getArgument(9));
            rule.put("algorithmVersion", invocation.getArgument(10));
            rules.put(String.valueOf((Object) invocation.getArgument(0)), rule);
            return 1;
        });
        when(mapper.updateDerivationRule(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            Map<String, Object> rule = rules.get(String.valueOf((Object) invocation.getArgument(0)));
            if (rule != null) {
                rule.put("name", invocation.getArgument(1));
                rule.put("derivationType", invocation.getArgument(2));
                rule.put("scheduleType", invocation.getArgument(3));
                rule.put("definitionJson", invocation.getArgument(7));
                rule.put("enabled", invocation.getArgument(9));
            }
            return 1;
        });
        when(mapper.resetDerivationRuleDefinition(any(), any(), any(), any())).thenAnswer(invocation -> {
            Map<String, Object> rule = rules.get(String.valueOf((Object) invocation.getArgument(0)));
            if (rule != null) {
                rule.put("name", invocation.getArgument(1));
                rule.put("derivationType", invocation.getArgument(2));
                rule.put("definitionJson", invocation.getArgument(3));
            }
            return 1;
        });
        when(mapper.getDerivationRule(any())).thenAnswer(invocation -> rules.get(String.valueOf((Object) invocation.getArgument(0))));
        when(mapper.listDerivationRules(any())).thenAnswer(invocation -> new ArrayList<Map<String, Object>>(rules.values()));
        when(mapper.listDerivationRulePresetCodes(any())).thenAnswer(invocation -> {
            List<String> codes = new ArrayList<String>();
            for (Map<String, Object> rule : rules.values()) {
                if (rule.get("presetCode") != null) {
                    codes.add(String.valueOf(rule.get("presetCode")));
                }
            }
            return codes;
        });
        service = new KnowledgeDerivationService(mapper, ids, objectMapper, mock(TaskCenterService.class), mock(PlatformEventPublisher.class),
            mock(AiClient.class));
    }

    @Test
    void seedingInstallsEveryPresetWithItsDefaultDefinition() {
        Map<String, Object> outcome = service.seed(7L, null);
        assertEquals(9, ((Number) outcome.get("created")).intValue());
        assertTrue(((List<?>) outcome.get("skipped")).isEmpty());
        for (Map<String, Object> rule : rules.values()) {
            assertEquals(KnowledgeDerivationService.SCHEDULE_MANUAL, rule.get("scheduleType"));
            Map<String, Object> definition = readDefinition(rule);
            assertTrue(definition.containsKey("limit"), String.valueOf(rule.get("presetCode")) + " 缺少默认定义");
        }
        Map<String, Object> deploy = byPreset("DEPLOY_CHAIN");
        assertNotNull(deploy);
        Map<String, Object> definition = readDefinition(deploy);
        assertEquals("PART_OF", definition.get("firstType"));
        assertEquals("LOCATED_IN", definition.get("secondType"));
        assertEquals("{head} 经由 {mid} 部署于 {tail}", definition.get("template"));
    }

    @Test
    void seedingTwiceAddsNothing() {
        service.seed(7L, null);
        Map<String, Object> outcome = service.seed(7L, null);
        assertEquals(0, ((Number) outcome.get("created")).intValue());
        assertEquals(9, ((List<?>) outcome.get("skipped")).size());
        assertEquals(9, rules.size());
    }

    @Test
    void aSinglePresetCanBeInstalledLater() {
        service.seed(7L, Arrays.asList("impact_chain"));
        assertEquals(1, rules.size());
        Map<String, Object> impact = byPreset("IMPACT_CHAIN");
        assertNotNull(impact);
        assertEquals("DEPENDS_ON", readDefinition(impact).get("firstType"));
        assertEquals("依赖影响面推导", impact.get("name"));
    }

    @Test
    void presetsReportWhatIsAlreadyInstalled() {
        service.seed(7L, null);
        List<Map<String, Object>> presets = service.presets(7L);
        assertEquals(9, presets.size());
        for (Map<String, Object> preset : presets) {
            assertTrue(Boolean.TRUE.equals(preset.get("installed")));
            assertNotNull(preset.get("ruleCode"));
        }
    }

    @Test
    void aPresetRuleCanBePutBackTheWayItShipped() {
        service.seed(7L, null);
        Map<String, Object> rule = byPreset("DEPLOY_CHAIN");
        String ruleCode = String.valueOf(rule.get("ruleCode"));
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        Map<String, Object> definition = new LinkedHashMap<String, Object>();
        definition.put("template", "{head} 最终落在 {tail}");
        definition.put("minConfidence", 0.4);
        body.put("definition", definition);
        service.updateRule(ruleCode, body);
        assertEquals("{head} 最终落在 {tail}", readDefinition(rules.get(ruleCode)).get("template"));

        service.resetRule(ruleCode);
        assertEquals("{head} 经由 {mid} 部署于 {tail}", readDefinition(rules.get(ruleCode)).get("template"));
        assertEquals(0.6, ((Number) readDefinition(rules.get(ruleCode)).get("minConfidence")).doubleValue(), 0.0001);
    }

    @Test
    void aCustomRuleHasNoDefaultToGoBackTo() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "自定义规则");
        Map<String, Object> created = service.createRule(7L, body);
        assertThrows(IllegalArgumentException.class, () -> service.resetRule(String.valueOf(created.get("ruleCode"))));
    }

    @Test
    void aPresetKeepsItsDerivationType() {
        service.seed(7L, null);
        Map<String, Object> rule = byPreset("DEPLOY_CHAIN");
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("derivationType", "TEMPLATE");
        assertThrows(IllegalArgumentException.class, () -> service.updateRule(String.valueOf(rule.get("ruleCode")), body));
    }

    @Test
    void definitionsOutsideTheBoundsAreRefused() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "越界规则");
        Map<String, Object> definition = new LinkedHashMap<String, Object>();
        definition.put("minConfidence", 0.05);
        body.put("definition", definition);
        assertThrows(IllegalArgumentException.class, () -> service.createRule(7L, body));
    }

    @Test
    void editingOneFieldLeavesTheOthersAlone() {
        service.seed(7L, null);
        Map<String, Object> rule = byPreset("IMPACT_CHAIN");
        String ruleCode = String.valueOf(rule.get("ruleCode"));
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        Map<String, Object> definition = new LinkedHashMap<String, Object>();
        definition.put("minConfidence", 0.8);
        body.put("definition", definition);
        service.updateRule(ruleCode, body);
        Map<String, Object> updated = readDefinition(rules.get(ruleCode));
        assertEquals(0.8, ((Number) updated.get("minConfidence")).doubleValue(), 0.0001);
        assertEquals("DEPENDS_ON", updated.get("firstType"));
        assertEquals("{head} 经 {mid} 间接依赖 {tail}", updated.get("template"));
    }

    private Map<String, Object> byPreset(String presetCode) {
        for (Map<String, Object> rule : rules.values()) {
            if (presetCode.equals(String.valueOf(rule.get("presetCode")))) {
                return rule;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDefinition(Map<String, Object> rule) {
        try {
            return new ObjectMapper().readValue(String.valueOf(rule.get("definitionJson")), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map<String, Object>> relationDictionary() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (String code : Arrays.asList("RELATED_TO", "PART_OF", "DEPENDS_ON", "USES", "CAUSES", "BELONGS_TO", "LOCATED_IN", "DESCRIBES", "CONTAINS",
            "MANAGES", "AFFECTS", "DEPLOYED_IN")) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("typeCode", code);
            rows.add(row);
        }
        return rows;
    }
}
