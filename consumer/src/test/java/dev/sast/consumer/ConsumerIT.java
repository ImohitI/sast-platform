package dev.sast.consumer;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The real thing: Kafka, Postgres and Redis in Docker. Run with `mvn verify` (needs Docker).
 */
@SpringBootTest
@Testcontainers
class ConsumerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2").withExposedPorts(6379);

    @TestConfiguration
    static class Topics {
        @Bean
        NewTopic scanResults() {
            return TopicBuilder.name("scan-results").partitions(2).replicas(1).build();
        }

        @Bean
        NewTopic deadLetters() {
            return TopicBuilder.name("scan-results.DLT").partitions(2).replicas(1).build();
        }
    }

    static KafkaProducer<String, String> producer;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeAll
    static void startProducer() {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }

    @AfterAll
    static void stopProducer() {
        producer.close();
    }

    @Test
    void sameBuildDeliveredTwiceIsStoredOnce() throws Exception {
        send("dup-repo", scanJson("dup-repo", 1));
        send("dup-repo", scanJson("dup-repo", 1));   // a redelivery, or Jenkins retrying after a 503
        send("dup-repo", scanJson("dup-repo", 2));   // same key -> same partition -> consumed after both copies

        await().atMost(Duration.ofSeconds(30)).until(() -> runs("dup-repo", 2) == 1);

        assertThat(runs("dup-repo", 1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM findings WHERE repo_id = 'dup-repo' AND build_id = 1", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT ai_suggested_fix FROM findings WHERE repo_id = 'dup-repo' AND build_id = 1 AND ai_verdict IS NOT NULL",
                String.class)).isEqualTo("use bind parameters");
    }

    @Test
    void newBuildEvictsTheDashboardCache() throws Exception {
        redisTemplate.opsForValue().set("latest:cache-repo", "{\"buildId\":0}");

        send("cache-repo", scanJson("cache-repo", 5));

        await().atMost(Duration.ofSeconds(30)).until(() -> !redisTemplate.hasKey("latest:cache-repo"));
        assertThat(runs("cache-repo", 5)).isEqualTo(1);
    }

    @Test
    void poisonPillGoesToTheDeadLetterTopicAndThePartitionMovesOn() throws Exception {
        send("poison-repo", "this is not a ScanResult");
        send("poison-repo", scanJson("poison-repo", 9));   // same partition, behind the poison pill

        await().atMost(Duration.ofSeconds(30)).until(() -> runs("poison-repo", 9) == 1);
        assertThat(deadLetterValues()).contains("this is not a ScanResult");
    }

    private void send(String key, String json) throws Exception {
        producer.send(new ProducerRecord<>("scan-results", key, json)).get();
    }

    private int runs(String repoId, long buildId) {
        return jdbc.queryForObject("SELECT count(*) FROM scan_runs WHERE repo_id = ? AND build_id = ?",
                Integer.class, repoId, buildId);
    }

    private List<String> deadLetterValues() {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of("scan-results.DLT"));
            List<String> values = new ArrayList<>();
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach((ConsumerRecord<String, String> r) -> values.add(r.value()));
                return !values.isEmpty();
            });
            return values;
        }
    }

    private static String scanJson(String repoId, long buildId) {
        return """
                {
                  "repoId": "%s", "buildId": %d, "commitSha": "abc%d", "scannedAt": "2026-10-26T18:00:00Z",
                  "tools": [{"name": "opengrep", "version": "1.32.0"}],
                  "findings": [
                    {"fingerprint": "%s", "cwe": "CWE-89", "ruleId": "python.sqli", "foundBy": ["opengrep", "bandit"],
                     "severity": "HIGH", "file": "python/app.py", "line": 20, "message": "sqli",
                     "ai": {"verdict": "likely_tp", "confidence": 0.9, "reason": "tainted", "suggestedFix": "use bind parameters"}},
                    {"fingerprint": "%s", "cwe": "CWE-78", "ruleId": "python.cmdi", "foundBy": ["bandit"],
                     "severity": "MEDIUM", "file": "python/app.py", "line": 34, "message": "cmdi", "ai": null}
                  ]
                }
                """.formatted(repoId, buildId, buildId, "a".repeat(64), "b".repeat(64));
    }
}
