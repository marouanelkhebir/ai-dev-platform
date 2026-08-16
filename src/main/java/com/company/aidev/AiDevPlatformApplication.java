package com.company.aidev;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the AI Dev Platform.
 *
 * <p>The platform turns a Jira ticket into a tested and reviewed GitLab merge request by running a
 * persisted state machine over a team of specialised LLM agents. Automatic merge is intentionally
 * not implemented: the last step always waits for a human.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AiDevPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiDevPlatformApplication.class, args);
    }
}
