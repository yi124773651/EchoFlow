package com.echoflow.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Configures the HTTP client used by Spring AI's OpenAI integration
 * with explicit connect and read timeouts.
 *
 * <p>Spring AI's {@code OpenAiChatAutoConfiguration} accepts an
 * {@code ObjectProvider<RestClient.Builder>}, so providing this bean
 * automatically applies timeouts to all AI model calls.</p>
 *
 * <p>Uses {@link JdkClientHttpRequestFactory} for virtual-thread compatibility.</p>
 */
@Configuration
public class AiClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder(
            @Value("${echoflow.ai.connect-timeout:10s}") Duration connectTimeout,
            @Value("${echoflow.ai.read-timeout:60s}") Duration readTimeout) {

        var httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();

        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder().requestFactory(requestFactory);
    }
}
