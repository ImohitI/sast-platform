package dev.sast.consumer;

import dev.sast.contracts.ScanResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * At-least-once delivery + idempotent write = effectively-once storage.
 *
 * Order per message:
 *   1. store()       -> DB transaction commits (or finds the build already stored)
 *   2. evictLatest() -> after the commit, so a reader can't re-cache the old row
 *   3. return        -> Spring Kafka commits the offset (default AckMode.BATCH: after the whole poll() batch)
 *
 * A crash between 1 and 3 leaves the row stored but the offset uncommitted, so the message comes back on restart.
 * store() then returns false and nothing is written twice (experiment E1 forces exactly this).
 */
@Component
public class ScanResultListener {

    private static final Logger log = LoggerFactory.getLogger(ScanResultListener.class);

    private final ScanResultWriter writer;
    private final LatestBuildCache cache;
    private final Duration debugSleep;

    public ScanResultListener(ScanResultWriter writer, LatestBuildCache cache,
                              @Value("${sast.consumer.debug-sleep}") Duration debugSleep) {
        this.writer = writer;
        this.cache = cache;
        this.debugSleep = debugSleep;
    }

    // One thread per partition. More threads than partitions just sit idle (experiment E2).
    @KafkaListener(topics = "${sast.topic}", concurrency = "${sast.consumer.concurrency}")
    public void onScanResult(ConsumerRecord<String, ScanResult> record) throws InterruptedException {
        ScanResult scan = record.value();
        boolean stored = writer.store(scan);
        log.info("{} repo={} build={} findings={} partition={} offset={}",
                stored ? "stored" : "duplicate, skipped", scan.repoId(), scan.buildId(), scan.findings().size(),
                record.partition(), record.offset());

        // Evict even for a duplicate: the first delivery may have crashed after the commit but before the evict.
        cache.evictLatest(scan.repoId());

        if (!debugSleep.isZero()) {
            log.info("E1: sleeping {} after the commit, before the offset commit. Kill me now.", debugSleep);
            Thread.sleep(debugSleep);
        }
    }
}
