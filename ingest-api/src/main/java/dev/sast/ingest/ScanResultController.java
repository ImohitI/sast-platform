package dev.sast.ingest;

import dev.sast.contracts.ScanResult;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Accepts a scan result, publishes it to Kafka and replies 202. Never touches Postgres.
 *
 * 202 is only sent after the broker has acknowledged the write (acks=all). Replying before the ack would let a
 * broker failure lose a scan while Jenkins shows green. If Kafka can't take it, the reply is 503 and Jenkins retries;
 * a retry may publish the same build twice, which the consumer absorbs (idempotent on repoId + buildId).
 */
@RestController
@RequestMapping("/api/scan-results")
public class ScanResultController {

    private static final Logger log = LoggerFactory.getLogger(ScanResultController.class);

    private final KafkaTemplate<String, ScanResult> kafka;
    private final String topic;
    private final Duration ackTimeout;

    public ScanResultController(KafkaTemplate<String, ScanResult> kafka,
                                @Value("${sast.topic}") String topic,
                                @Value("${sast.ack-timeout}") Duration ackTimeout) {
        this.kafka = kafka;
        this.topic = topic;
        this.ackTimeout = ackTimeout;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> accept(@Valid @RequestBody ScanResult scanResult) {
        try {
            // key = repoId: all builds of one repo go to one partition, so they are consumed in order.
            SendResult<String, ScanResult> sent = kafka.send(topic, scanResult.repoId(), scanResult)
                    .get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
            log.info("accepted repo={} build={} findings={} -> partition={} offset={}",
                    scanResult.repoId(), scanResult.buildId(), scanResult.findings().size(),
                    sent.getRecordMetadata().partition(), sent.getRecordMetadata().offset());
            return ResponseEntity.accepted().body(Map.of("status", "accepted"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable(scanResult, e);
        } catch (ExecutionException | TimeoutException e) {
            // the broker answered with an error, or no ack within ackTimeout
            return unavailable(scanResult, e);
        } catch (KafkaException e) {
            // send() itself failed: no broker metadata within max.block.ms (broker down, topic missing)
            return unavailable(scanResult, e);
        }
    }

    private ResponseEntity<Map<String, String>> unavailable(ScanResult scanResult, Exception e) {
        log.warn("kafka did not ack repo={} build={}: {}", scanResult.repoId(), scanResult.buildId(), e.toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(Map.of("status", "unavailable", "reason", "message broker did not acknowledge the write"));
    }
}
