package com.jh.knowledgeos.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * 判定用的是 contracts/ 里那份规则，用例也是同一份：前端 workload-identity.test.mjs 读同一个
 * intranet-http.cases.json。两边任何一边改了口径而另一边没跟上，这里就会红。
 */
class IntranetAddressRulesTest {
    @Test
    void matchesTheSharedContractCases() throws Exception {
        JsonNode root = new ObjectMapper()
            .readTree(Files.readAllBytes(contract("intranet-http.cases.json")));
        int checked = 0;
        for (JsonNode item : root.withArray("cases")) {
            String host = item.path("host").asText();
            assertEquals(item.path("loopback").asBoolean(), IntranetAddressRules.isLoopback(host),
                "loopback 判定不一致: " + host);
            assertEquals(item.path("intranet").asBoolean(), IntranetAddressRules.isIntranetHost(host),
                "intranet 判定不一致: " + host);
            checked++;
        }
        assertNotNull(checked > 0 ? "" : null);
    }

    /** 规则文件必须真在 classpath 上（pom 把 contracts 挂成了资源目录），否则线上起不来。 */
    @Test
    void rulesAreLoadedFromTheSharedContract() {
        assertNotNull(
            IntranetAddressRulesTest.class.getClassLoader().getResource("intranet-http.json"),
            "classpath 缺少 intranet-http.json，检查 pom 的 resources 配置");
    }

    private static Path contract(String name) {
        Path up = Paths.get("../contracts", name);
        return Files.exists(up) ? up : Paths.get("contracts", name);
    }
}
