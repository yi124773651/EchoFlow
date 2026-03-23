package com.echoflow.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for SSE direct connections.
 *
 * <p>The frontend EventSource connects directly to the backend for SSE streaming,
 * bypassing the Next.js proxy which buffers responses and breaks real-time delivery.
 * Regular REST calls still go through Next.js rewrites (same-origin, no CORS needed).</p>
 */
@Configuration
class CorsConfig {

    @Bean
    WebMvcConfigurer corsConfigurer(
            @Value("${echoflow.cors.allowed-origins:http://localhost:3000}") String allowedOrigins) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/tasks/*/execution/stream")
                        .allowedOrigins(allowedOrigins.split(","))
                        .allowedMethods("GET");
            }
        };
    }
}
