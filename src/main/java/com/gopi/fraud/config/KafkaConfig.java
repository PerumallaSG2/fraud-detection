package com.gopi.fraud.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gopi.fraud.messaging.Topics;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Topics, JSON (de)serialization and the retry-then-dead-letter error handling for every listener. Connection and
 * client settings are in application.yml ({@code spring.kafka.*}).
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    /** Each dead-letter topic has its topic's partition count: a failed record keeps its partition number. */
    @Bean
    KafkaAdmin.NewTopics topics(MessagingProperties properties) {
        int partitions = properties.partitions();
        return new KafkaAdmin.NewTopics(
                topic(Topics.TRANSACTIONS, partitions),
                topic(Topics.TRANSACTIONS + Topics.DLT_SUFFIX, partitions),
                topic(Topics.DECISIONS, partitions),
                topic(Topics.DECISIONS + Topics.DLT_SUFFIX, partitions));
    }

    /**
     * Values as JSON, written with the application's ObjectMapper. Raw bytes pass through unchanged: that is how a
     * message that could not be read at all is forwarded to its dead-letter topic.
     */
    @Bean
    @SuppressWarnings("unchecked")
    DefaultKafkaProducerFactoryCustomizer jsonValueSerializer(ObjectMapper objectMapper) {
        Map<Class<?>, Serializer<?>> byType = new LinkedHashMap<>();
        byType.put(byte[].class, new ByteArraySerializer());
        byType.put(Object.class, new JsonSerializer<>(objectMapper));
        Serializer<Object> serializer = new DelegatingByTypeSerializer(byType, true);
        return factory -> ((DefaultKafkaProducerFactory<Object, Object>) factory).setValueSerializer(serializer);
    }

    /**
     * Values read with the application's ObjectMapper, which keeps a timestamp's own offset (spring-kafka's default
     * mapper would shift it to UTC, and odd hours is judged in that offset). A value that can't be read becomes a
     * failed record instead of an endless poll loop, and goes straight to the dead-letter topic.
     */
    @Bean
    @SuppressWarnings("unchecked")
    DefaultKafkaConsumerFactoryCustomizer jsonValueDeserializer(ObjectMapper objectMapper) {
        JsonDeserializer<Object> json = new JsonDeserializer<>(objectMapper)
                .trustedPackages(Topics.class.getPackageName());
        ErrorHandlingDeserializer<Object> deserializer = new ErrorHandlingDeserializer<>(json);
        return factory -> ((DefaultKafkaConsumerFactory<Object, Object>) factory).setValueDeserializer(deserializer);
    }

    /**
     * Blocking retries: the failed record is retried in place, so later records on its partition wait and each
     * card's order holds. After the last attempt the record goes to {@code <topic>.DLT} (same partition, failure
     * details in its headers) and the partition moves on. Unreadable records are not retried.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafka, MessagingProperties properties) {
        DeadLetterPublishingRecoverer toDeadLetterTopic = new DeadLetterPublishingRecoverer(kafka,
                (record, ex) -> new TopicPartition(record.topic() + Topics.DLT_SUFFIX, record.partition()));
        MessagingProperties.Retry retry = properties.retry();
        return new DefaultErrorHandler(toDeadLetterTopic,
                new FixedBackOff(retry.backoff().toMillis(), retry.maxAttempts() - 1L));
    }

    private static NewTopic topic(String name, int partitions) {
        return TopicBuilder.name(name).partitions(partitions).replicas(1).build();
    }
}
