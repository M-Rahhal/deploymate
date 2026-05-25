package com.deploymate;

import com.deploymate.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class DeployMateApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeployMateApplication.class, args);
    }
}
