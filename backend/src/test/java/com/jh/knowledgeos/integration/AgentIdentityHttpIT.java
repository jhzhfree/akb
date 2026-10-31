package com.jh.knowledgeos.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.service.SimpleAgentConnectionService;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Opt-in cross-project test against the actual Node bridge and a real AKB HTTP server. */
@EnabledIfSystemProperty(named="agent.sdk.bridge", matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"WORKLOAD_ALLOW_LOOPBACK_HTTP=true"})
@Testcontainers
class AgentIdentityHttpIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
        .withDatabaseName("identity_http").withUsername("knowledge").withPassword("knowledge123");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
    }
    @LocalServerPort int port;
    @Autowired SimpleAgentConnectionService connections;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json=new ObjectMapper();
    private Path root;
    private Map<String,Object> map(Object... entries) {
        Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<entries.length;i+=2)result.put((String)entries[i],entries[i+1]);return result;
    }
    private Map<String,Object> call(Map<String,Object> input) throws Exception {
        input.put("root",root.toString());
        Process child=new ProcessBuilder("node",System.getProperty("agent.sdk.bridge")).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            child.getOutputStream().write(json.writeValueAsBytes(input));child.getOutputStream().close();
            assertTrue(child.waitFor(30,TimeUnit.SECONDS),"SDK bridge timed out");
            ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int read;
            try(InputStream stream=child.getInputStream()){while((read=stream.read(buffer))!=-1)output.write(buffer,0,read);}
            return json.readValue(output.toByteArray(),new TypeReference<Map<String,Object>>(){});
        } finally {if(child.isAlive())child.destroyForcibly();}
    }
    @SuppressWarnings("unchecked") private Map<String,Object> data(Map<String,Object> response) {
        assertEquals(Boolean.TRUE,response.get("ok"),"SDK operation rejected");return (Map<String,Object>)response.get("data");
    }
    @Test @SuppressWarnings("unchecked") void twoGeneratedAgentsAuthenticateOverRestAndMcpAndRevocationIsImmediate() throws Exception {
        root=Files.createTempDirectory("agent-http-it-");
        try {
            String origin="http://127.0.0.1:"+port;
            Map<String,Object> created=connections.create(map("name",String.join("",Collections.nCopies(128,"a")),"resourceOrigin",origin,"platformType","octop","knowledgeBaseIds",Collections.singletonList(1)),1);
            long policy=((Number)created.get("id")).longValue();
            String secret=(String)((Map<String,Object>)created.get("key")).get("secret");
            List<Map<String,Object>> bindings=new ArrayList<>();List<String> kids=new ArrayList<>();
            for(int i=0;i<2;i++) {
                String agent=UUID.randomUUID().toString();
                Map<String,Object> generated=data(call(map("op","generate","keyScope","http-it","externalAgentId",agent)));
                assertFalse(generated.containsKey("privateKey"));kids.add((String)generated.get("kid"));
                connections.register(policy,map("name","Agent "+i,"agentId",agent,"publicJwk",generated.get("publicJwk")),1);
                Map<String,Object> connected=data(call(map("op","connect","origin",origin,"transport","rest","allowInsecureLoopback",true,"apiKey",secret,
                    "keyScope","http-it","externalAgentId",agent,"keyReference",generated.get("keyReference"),"invocation",map("sessionId","integration","invocationId",UUID.randomUUID().toString()))));
                Map<String,Object> binding=(Map<String,Object>)connected.get("binding");bindings.add(binding);
                for(String transport:Arrays.asList("rest","mcp")) {
                    Map<String,Object> result=call(map("op","execute","owner",binding,"binding",binding,"origin",origin,"transport",transport,"allowInsecureLoopback",true,
                        "apiKey",secret,"request",map("operation","bases"),"invocation",map("sessionId","integration-"+transport,"invocationId",UUID.randomUUID().toString())));
                    assertEquals(Boolean.TRUE,result.get("ok"));assertTrue(json.writeValueAsString(result.get("data")).contains("KB_DEFAULT"));
                }
            }
            assertNotEquals(kids.get(0),kids.get(1));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(DISTINCT agent_principal_id) FROM t_api_call_audit WHERE agent_principal_id IS NOT NULL",Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(DISTINCT caller_name) FROM t_api_call_audit WHERE agent_principal_id IS NOT NULL",Integer.class));
            Map<String,Object> binding=bindings.get(0);
            connections.keyStatus(policy,Long.parseLong((String)binding.get("bindingId")),kids.get(0),map("status","REVOKED"),1);
            Map<String,Object> revoked=call(map("op","execute","owner",binding,"binding",binding,"origin",origin,"transport","rest","allowInsecureLoopback",true,"apiKey",secret,
                "request",map("operation","bases"),"invocation",map("sessionId","revoked","invocationId",UUID.randomUUID().toString())));
            assertEquals(Boolean.FALSE,revoked.get("ok"));assertEquals("AUTHENTICATION_FAILED",revoked.get("code"));
        } finally {
            if(root!=null)try(java.util.stream.Stream<Path> paths=Files.walk(root)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ex){throw new RuntimeException(ex);}});}
        }
    }
}
