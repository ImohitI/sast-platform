package dev.sast.contracts;

/**
 * Redis keys shared by the consumer (evicts) and dashboard-api (reads and fills).
 * dashboard-api lives in another repo and keeps its own copy of this name: change both together.
 */
public final class CacheKeys {

    private CacheKeys() {
    }

    public static String latest(String repoId) {
        return "latest:" + repoId;
    }
}
