package dev.sast.consumer;

import dev.sast.contracts.ScanResult;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.Map;

/**
 * Two kinds of failure, two answers:
 *
 * - Transient (Postgres down, network blip): retry the same record with backoff, forever. The partition pauses,
 *   lag grows, and it drains when the database is back. Nothing is lost (experiment E3).
 * - Permanent (bytes that aren't a ScanResult, a row the database rejects): retrying can never succeed, so the record
 *   goes to scan-results.DLT right away and the partition moves on. One bad message costs one message.
 *
 * The value deserializer is wrapped in ErrorHandlingDeserializer (application.yml). Without that wrapper a bad
 * message fails inside poll(), before this handler can see it, and the container loops on that offset forever.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> deadLetterTemplate) {
        // Default destination: <topic>.DLT, same partition number. So the DLT needs at least as many partitions.
        var recoverer = new DeadLetterPublishingRecoverer(deadLetterTemplate);

        var backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxInterval(30_000);   // 1 s, 2 s, 4 s ... then every 30 s; no limit on attempts

        var handler = new DefaultErrorHandler(recoverer, backOff);
        // DeserializationException and friends are already non-retryable by default.
        handler.addNotRetryableExceptions(DataIntegrityViolationException.class);
        return handler;
    }

    /**
     * Dead letters carry either the original bytes (deserialization failed) or a ScanResult (the write failed),
     * so the value serializer is chosen by type.
     *
     * The config is copied from Boot's producer factory, which already has the broker address from
     * KafkaConnectionDetails. Building it from KafkaProperties would ignore a Testcontainers @ServiceConnection and
     * point at localhost:9092: every dead letter would then fail to publish, and the partition would retry forever.
     */
    @Bean
    KafkaTemplate<Object, Object> deadLetterTemplate(ProducerFactory<Object, Object> producerFactory) {
        var factory = new DefaultKafkaProducerFactory<Object, Object>(producerFactory.getConfigurationProperties());
        factory.setValueSerializer(new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(),
                ScanResult.class, new JacksonJsonSerializer<ScanResult>().noTypeInfo())));
        return new KafkaTemplate<>(factory);
    }
}
