package dev.sast.consumer;

import dev.sast.contracts.CacheKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Invalidates dashboard-api's cached "latest build" view after a new build is committed.
 *
 * Delete, don't update: a delete is idempotent and order-free, and dashboard-api owns the shape of its cache.
 * Redis is an optimization, so a failure here is logged and swallowed. Throwing would make Kafka redeliver an
 * already-stored message, and a Redis outage would stall consumption. The 60 s TTL bounds the staleness instead.
 */
@Component
public class LatestBuildCache {

    private static final Logger log = LoggerFactory.getLogger(LatestBuildCache.class);

    private final StringRedisTemplate redis;

    public LatestBuildCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void evictLatest(String repoId) {
        String key = CacheKeys.latest(repoId);
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            log.warn("could not evict {} (dashboard may be stale until the TTL expires): {}", key, e.toString());
        }
    }
}
