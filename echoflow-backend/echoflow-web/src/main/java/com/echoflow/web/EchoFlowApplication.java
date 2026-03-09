package com.echoflow.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.echoflow")
@EntityScan(basePackages = "com.echoflow.infrastructure")
@EnableJpaRepositories(basePackages = "com.echoflow.infrastructure")
public class EchoFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(EchoFlowApplication.class, args);
    }
}
