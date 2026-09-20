package com.jh.knowledgeos.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers authentication and knowledge-base authorization interceptors. */
@Configuration
public class WebMvcSecurityConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;
    private final KnowledgeBaseAccessInterceptor accessInterceptor;

    public WebMvcSecurityConfig(AuthInterceptor authInterceptor, KnowledgeBaseAccessInterceptor accessInterceptor) {
        this.authInterceptor = authInterceptor;
        this.accessInterceptor = accessInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**").order(1);
        registry.addInterceptor(accessInterceptor).addPathPatterns("/api/knowledge/**").order(2);
    }
}
