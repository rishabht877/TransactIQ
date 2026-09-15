package com.transactiq.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the Phase 5 React dashboard (Vite dev server). Origins are configurable so this
 * doesn't hardcode a single port.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebConfig(@Value("${transactiq.cors.allowed-origins:http://localhost:5173}") String origins) {
        this.allowedOrigins = origins.split(",");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST")
                // Pagination total from GET /api/payments — a browser cannot read a custom
                // response header unless it is explicitly exposed.
                .exposedHeaders("X-Total-Count");
    }
}
