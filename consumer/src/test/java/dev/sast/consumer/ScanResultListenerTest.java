package dev.sast.consumer;

import dev.sast.contracts.ScanResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScanResultListenerTest {

    private final ScanResultWriter writer = mock(ScanResultWriter.class);
    private final LatestBuildCache cache = mock(LatestBuildCache.class);
    private final ScanResultListener listener = new ScanResultListener(writer, cache, Duration.ZERO);

    private final ScanResult scan = new ScanResult("samples", 7L, "abc", Instant.parse("2026-10-26T18:00:00Z"), List.of(), List.of());
    private final ConsumerRecord<String, ScanResult> record = new ConsumerRecord<>("scan-results", 0, 41, "samples", scan);

    @Test
    void evictsOnlyAfterTheWriteCommitted() throws Exception {
        when(writer.store(scan)).thenReturn(true);

        listener.onScanResult(record);

        InOrder order = inOrder(writer, cache);
        order.verify(writer).store(scan);
        order.verify(cache).evictLatest("samples");
    }

    @Test
    void evictsEvenForADuplicate() throws Exception {
        // The first delivery may have crashed after the DB commit but before the evict.
        when(writer.store(scan)).thenReturn(false);

        listener.onScanResult(record);

        verify(cache).evictLatest("samples");
    }

    @Test
    void redisOutageDoesNotFailTheMessage() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.delete("latest:samples")).thenThrow(new RedisConnectionFailureException("redis is down"));

        assertThatNoException().isThrownBy(() -> new LatestBuildCache(redis).evictLatest("samples"));
    }
}
