package com.jh.knowledgeos;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.jh.knowledgeos.config.KnowledgeProperties;

/**
 * Spring Boot entry point that wires scheduling, web APIs, persistence, and operational services.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(KnowledgeProperties.class)
@MapperScan("com.jh.knowledgeos.mapper")
public class KnowledgeOsApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeOsApplication.class, args);
    }
}
