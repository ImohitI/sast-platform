package dev.sast.ingest;

import dev.sast.contracts.ScanResult;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ScanResultController.class, properties = {"sast.topic=scan-results", "sast.ack-timeout=1s"})
class ScanResultControllerTest {

    private static final String VALID = """
            {
              "repoId": "samples",
              "buildId": 1052,
              "commitSha": "a1b2c3d",
              "scannedAt": "2026-10-26T18:00:00Z",
              "tools": [{"name": "semgrep", "version": "1.182.1"}],
              "findings": [{
                "fingerprint": "1f0e3dad99908345f7439f8ffabdffc4c4f1b4a6f1b6c5d0d1e8b4e1f1a2b3c4",
                "cwe": "CWE-89", "ruleId": "python.sqli", "foundBy": ["semgrep"], "severity": "HIGH",
                "file": "python/app.py", "line": 20, "message": "sqli",
                "ai": {"verdict": "likely_tp", "confidence": 0.9, "reason": "tainted", "suggestedFix": "bind params"}
              }]
            }
            """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    KafkaTemplate<String, ScanResult> kafka;

    @Test
    void acceptsWithKeyRepoIdOnlyAfterKafkaAcks() throws Exception {
        when(kafka.send(eq("scan-results"), eq("samples"), any(ScanResult.class))).thenReturn(acked());

        mvc.perform(post("/api/scan-results").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));
    }

    @Test
    void missingBuildIdIsRejectedAndNeverReachesKafka() throws Exception {
        String noBuildId = VALID.replace("\"buildId\": 1052,", "");

        mvc.perform(post("/api/scan-results").contentType(MediaType.APPLICATION_JSON).content(noBuildId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("buildId must not be null"));
        verify(kafka, never()).send(anyString(), anyString(), any());
    }

    @Test
    void invalidNestedFindingIsRejected() throws Exception {
        String badSeverity = VALID.replace("\"line\": 20", "\"line\": -1");

        mvc.perform(post("/api/scan-results").contentType(MediaType.APPLICATION_JSON).content(badSeverity))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("findings[0].line must be greater than or equal to 0"));
    }

    @Test
    void malformedJsonIsRejected() throws Exception {
        mvc.perform(post("/api/scan-results").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void kafkaFailureIs503SoTheCallerRetries() throws Exception {
        when(kafka.send(anyString(), anyString(), any(ScanResult.class)))
                .thenReturn(CompletableFuture.failedFuture(new org.apache.kafka.common.errors.TimeoutException("no broker")));

        mvc.perform(post("/api/scan-results").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));
    }

    @Test
    void brokerUnreachableBeforeSendingIs503Too() throws Exception {
        // What a real KafkaTemplate does when it can't fetch metadata within max.block.ms: it throws, no future.
        when(kafka.send(anyString(), anyString(), any(ScanResult.class)))
                .thenThrow(new org.springframework.kafka.KafkaException("Send failed"));

        mvc.perform(post("/api/scan-results").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isServiceUnavailable());
    }

    private static CompletableFuture<SendResult<String, ScanResult>> acked() {
        var metadata = new RecordMetadata(new TopicPartition("scan-results", 1), 41, 0, 0L, 7, 900);
        return CompletableFuture.completedFuture(new SendResult<>(new ProducerRecord<>("scan-results", "samples", null), metadata));
    }
}
