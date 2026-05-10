package com.isakatirci.MVP.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic transferRequestsTopic() {
        return TopicBuilder.name("transfer-requests")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic transferSuccessTopic() {
        return TopicBuilder.name("transfer-success")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic transferFailedTopic() {
        return TopicBuilder.name("transfer-failed")
                .partitions(3)
                .replicas(1)
                .build();
    }
}
