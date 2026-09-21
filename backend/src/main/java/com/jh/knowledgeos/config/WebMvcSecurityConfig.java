package com.jh.knowledgeos.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers authentication and knowledge-base authorization interceptors. */
@Configuration
public class WebMvcSecurityConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;
    private final KnowledgeBaseAccessInterceptor accessInterceptor;
    private final PermissionInterceptor permissionInterceptor;

    public WebMvcSecurityConfig(AuthInterceptor authInterceptor,
            KnowledgeBaseAccessInterceptor accessInterceptor,
            PermissionInterceptor permissionInterceptor) {
        this.authInterceptor = authInterceptor;
        this.accessInterceptor = accessInterceptor;
        this.permissionInterceptor = permissionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**").order(1);
        registry.addInterceptor(accessInterceptor).addPathPatterns("/api/knowledge/**").order(2);
        registry.addInterceptor(permissionInterceptor).addPathPatterns("/api/**").order(3);
    }
}
