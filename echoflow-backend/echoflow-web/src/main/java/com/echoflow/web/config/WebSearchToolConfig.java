package com.echoflow.web.config;

import com.echoflow.infrastructure.ai.config.TavilyProperties;
import com.echoflow.infrastructure.ai.tool.WebSearchTool;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TavilyProperties.class)
class WebSearchToolConfig {

    @Bean
    WebSearchTool webSearchTool(TavilyProperties tavilyProperties) {
        return WebSearchTool.create(tavilyProperties);
    }
}
