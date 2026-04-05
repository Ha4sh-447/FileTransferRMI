package server;

import java.io.*;
import java.util.*;

/**
 * Thread-safe LRU Reply Cache for idempotent remote procedures.
 * 
 * DC Concept: Reply Caching of Idempotent Remote Procedures (Section I)
 * 
 * How it works:
 * - Caches results of idempotent operations (listFiles, getFileInfo)
 * - Uses LinkedHashMap with access-order to implement LRU eviction
 * - Automatically invalidated when file mutations occur (upload/delete)
 * - Thread-safe via synchronized access
 * 
 * Why LRU?
 * - Bounded memory usage (configurable max entries)
 * - Frequently accessed results stay cached
 * - Stale entries evicted automatically
 */
public class ReplyCache {

    private final int maxSize;
    private final LinkedHashMap<String, CacheEntry> cache;
    private long hits = 0;
    private long misses = 0;

    /**
     * Represents a cached response with a timestamp.
     */
    private static class CacheEntry implements Serializable {
        final Object result;
        final long timestamp;

        CacheEntry(Object result) {
            this.result = result;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired(long ttlMillis) {
            return (System.currentTimeMillis() - timestamp) > ttlMillis;
        }
    }

    /**
     * Create a reply cache with specified maximum size.
     * 
     * @param maxSize Maximum number of cached entries before LRU eviction
     */
    public ReplyCache(int maxSize) {
        this.maxSize = maxSize;
        // Access-order LinkedHashMap: least-recently-accessed entry is evicted first
        this.cache = new LinkedHashMap<String, CacheEntry>(maxSize + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > ReplyCache.this.maxSize;
            }
        };
    }

    /**
     * Get a cached result for the given key.
     * 
     * @param key       Cache key (e.g., "listFiles" or "getFileInfo:filename")
     * @param ttlMillis Time-to-live in milliseconds; entries older than this are
     *                  stale
     * @return Cached result, or null if not found / expired
     */
    public synchronized Object get(String key, long ttlMillis) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            misses++;
            return null;
        }
        if (entry.isExpired(ttlMillis)) {
            cache.remove(key);
            misses++;
            return null;
        }
        hits++;
        return entry.result;
    }

    /**
     * Store a result in the cache.
     * 
     * @param key    Cache key
     * @param result Result object to cache
     */
    public synchronized void put(String key, Object result) {
        cache.put(key, new CacheEntry(result));
    }

    /**
     * Invalidate all cached entries.
     * Called when a file mutation (upload/delete) occurs to prevent stale data.
     */
    public synchronized void invalidateAll() {
        cache.clear();
    }

    /**
     * Invalidate a specific key from the cache.
     * 
     * @param key Cache key to remove
     */
    public synchronized void invalidate(String key) {
        cache.remove(key);
    }

    /**
     * Get cache statistics for monitoring.
     * 
     * @return Formatted string with hit/miss ratio
     */
    public synchronized String getStats() {
        long total = hits + misses;
        double hitRate = total > 0 ? (double) hits / total * 100 : 0;
        return String.format("Cache Stats — Size: %d/%d | Hits: %d | Misses: %d | Hit Rate: %.1f%%",
                cache.size(), maxSize, hits, misses, hitRate);
    }
}
