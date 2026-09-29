package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The presets have to be usable the moment they are seeded: every default definition must pass
 * the same bounds a hand written one is checked against, and a definition outside those bounds
 * has to be refused with a reason a user can act on.
 */
class DerivationRuleCatalogTest {

    /**
     * The platform relation vocabulary (kb 0): the defaults every knowledge base starts from,
     * including the V1.1 types a vulnability impact walk needs.
     */
    private static final List<String> RELATIONS = Arrays.asList("RELATED_TO", "PART_OF", "DEPENDS_ON", "USES", "CAUSES", "BELONGS_TO", "LOCATED_IN",
        "DESCRIBES", "CONTAINS", "MANAGES", "AFFECTS", "DEPLOYED_IN");

    @Test
    void everyPresetShipsADefinitionInsideTheBounds() {
        List<Map<String, Object>> presets = DerivationRuleCatalog.presets();
        assertEquals(9, presets.size());
        for (Map<String, Object> preset : presets) {
            String type = String.valueOf(preset.get("derivationType"));
            Map<String, Object> definition = definition(preset);
            assertTrue(definition.containsKey("limit"), preset.get("presetCode") + " 缺少 limit");
            Map<String, Object> validated = DerivationRuleCatalog.validate(type, definition, RELATIONS);
            for (Map.Entry<String, Object> entry : definition.entrySet()) {
                assertEquals(entry.getValue(), validated.get(entry.getKey()),
                    preset.get("presetCode") + " 的默认字段 " + entry.getKey() + " 被改动");
            }
        }
    }

    @Test
    void presetsAreManualByDefaultSoNothingRunsUnattended() {
        for (Map<String, Object> preset : DerivationRuleCatalog.presets()) {
            assertEquals(KnowledgeDerivationService.SCHEDULE_MANUAL, preset.get("scheduleType"));
            assertTrue(String.valueOf(preset.get("description")).length() > 10);
        }
    }

    @Test
    void missingFieldsFallBackToTheTypeDefaults() {
        Map<String, Object> definition = DerivationRuleCatalog.validate(KnowledgeDerivationService.TYPE_GRAPH_PATH, new HashMap<String, Object>(), RELATIONS);
        assertEquals("PART_OF", definition.get("firstType"));
        assertEquals("LOCATED_IN", definition.get("secondType"));
        assertEquals(0.6, ((Number) definition.get("minConfidence")).doubleValue(), 0.0001);
        assertEquals(100, ((Number) definition.get("limit")).intValue());
    }

    @Test
    void confidenceAndLimitAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> validate("minConfidence", 0.1));
        assertThrows(IllegalArgumentException.class, () -> validate("minConfidence", 1.0));
        assertThrows(IllegalArgumentException.class, () -> validate("limit", 500));
        assertThrows(IllegalArgumentException.class, () -> validate("limit", 0));
    }

    @Test
    void relationTypesMustComeFromTheDictionary() {
        assertThrows(IllegalArgumentException.class, () -> validate("firstType", "INVENTED_BY"));
    }

    @Test
    void unknownFieldsAndUnfillablePlaceholdersAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> validate("maxHops", 3));
        assertThrows(IllegalArgumentException.class, () -> validate("template", "{head} 与 {who} 相关"));
    }

    @Test
    void aTemplateWithoutASubjectHasNothingToSay() {
        Map<String, Object> raw = new HashMap<String, Object>();
        raw.put("template", "{predicate} 为 {object}");
        assertThrows(IllegalArgumentException.class, () -> DerivationRuleCatalog.validate(KnowledgeDerivationService.TYPE_TEMPLATE, raw, RELATIONS));
    }

    @Test
    void boundsCarryTheRangeTheServerEnforces() {
        Map<String, Object> bounds = DerivationRuleCatalog.bounds(KnowledgeDerivationService.TYPE_GRAPH_PATH, RELATIONS);
        assertEquals(new ArrayList<String>(Arrays.asList("firstType", "secondType", "pathTypes", "minConfidence", "limit", "template")), new
            ArrayList<Object>(bounds.keySet()));
        Map<String, Object> confidence = field(bounds, "minConfidence");
        assertEquals(0.3, ((Number) confidence.get("min")).doubleValue(), 0.0001);
        assertEquals(0.95, ((Number) confidence.get("max")).doubleValue(), 0.0001);
        assertNotNull(field(bounds, "firstType").get("options"));
    }

    @Test
    void editPolicyStatesWhatCannotBeChanged() {
        Map<String, Object> policy = DerivationRuleCatalog.editPolicy();
        assertTrue(String.valueOf(policy.get("derivationType")).contains("不可改"));
        assertTrue(String.valueOf(policy.get("definition")).contains("可改"));
    }

    /**
     * Two hops answer "who depends on whom"; the questions that actually get asked — which
     * customer does this vulnerability land on — need three or more. The path is what makes
     * those answerable, so it is checked as strictly as the relations it walks.
     */
    @Test
    void aMultiHopPathIsKeptWhenEveryHopIsInTheDictionary() {
        assertEquals("AFFECTS>DEPENDS_ON:REV>DEPLOYED_IN:REV>BELONGS_TO",
            DerivationRuleCatalog.validate(KnowledgeDerivationService.TYPE_GRAPH_PATH,
                pathDefinition("AFFECTS>DEPENDS_ON:REV>DEPLOYED_IN:REV>BELONGS_TO"), RELATIONS).get("pathTypes"));
    }

    @Test
    void aPathHopOutsideTheDictionaryIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> DerivationRuleCatalog.validate(
            KnowledgeDerivationService.TYPE_GRAPH_PATH, pathDefinition("AFFECTS>INVENTED_BY"), RELATIONS));
    }

    @Test
    void aTwoHopPathIsWhatTheTwoRelationsAlreadyDo() {
        assertEquals("AFFECTS>DEPENDS_ON", DerivationRuleCatalog.validate(KnowledgeDerivationService.TYPE_GRAPH_PATH,
            pathDefinition("AFFECTS>DEPENDS_ON"), RELATIONS).get("pathTypes"));
    }

    @Test
    void aPathOutsideTheHopRangeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> DerivationRuleCatalog.validate(
            KnowledgeDerivationService.TYPE_GRAPH_PATH,
            pathDefinition("AFFECTS>DEPENDS_ON>DEPENDS_ON>DEPENDS_ON>DEPENDS_ON>DEPENDS_ON"), RELATIONS));
    }

    @Test
    void aReverseHopIsWalkedAgainstTheArrow() {
        List<DerivationRuleCatalog.PathHop> hops = DerivationRuleCatalog.parsePathTypes("AFFECTS>DEPENDS_ON:REV");
        assertEquals(2, hops.size());
        assertEquals("AFFECTS", hops.get(0).type());
        assertFalse(hops.get(0).reverse());
        assertEquals("DEPENDS_ON", hops.get(1).type());
        assertTrue(hops.get(1).reverse());
    }

    @Test
    void theImpactScopePresetWalksTheVulnerabilityToTheCustomer() {
        Map<String, Object> preset = null;
        for (Map<String, Object> item : DerivationRuleCatalog.presets()) {
            if ("IMPACT_SCOPE".equals(String.valueOf(item.get("presetCode")))) {
                preset = item;
            }
        }
        assertNotNull(preset, "漏洞影响面推导预设应随平台一起提供");
        assertEquals("AFFECTS>DEPENDS_ON:REV>DEPLOYED_IN:REV>BELONGS_TO", definition(preset).get("pathTypes"));
    }

    private Map<String, Object> pathDefinition(String pathTypes) {
        Map<String, Object> raw = new HashMap<String, Object>();
        raw.put("firstType", "AFFECTS");
        raw.put("secondType", "DEPENDS_ON");
        raw.put("template", "{head} 可能影响 {tail}");
        raw.put("pathTypes", pathTypes);
        return raw;
    }

    private void validate(String field, Object value) {
        Map<String, Object> raw = new HashMap<String, Object>();
        raw.put(field, value);
        DerivationRuleCatalog.validate(KnowledgeDerivationService.TYPE_GRAPH_PATH, raw, RELATIONS);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> definition(Map<String, Object> preset) {
        return (Map<String, Object>) preset.get("definition");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> field(Map<String, Object> bounds, String name) {
        return (Map<String, Object>) bounds.get(name);
    }
}
