package demo;

// Maven dependency cần thiết:
// <dependency>
//     <groupId>com.github.ben-manes.caffeine</groupId>
//     <artifactId>caffeine</artifactId>
//     <version>3.1.8</version>
// </dependency>

import com.github.benmanes.caffeine.cache.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * ✅ FIXED VERSION: Sử dụng Caffeine Cache với giới hạn
 * 
 * Chạy với: -Xms64m -Xmx64m
 * 
 * Mục đích: Minh họa cách sử dụng bounded cache để tránh memory leak
 * Kết quả: JVM chạy hoàn thành 100 iterations mà không OOM
 */
public class FixedMemoryDemo {
    
    // ✅ SOLUTION: Caffeine cache với maximumSize và expiration
    private static final Cache<String, byte[]> CACHE = Caffeine.newBuilder()
        .maximumSize(10)                           // Giới hạn 10 entries
        .expireAfterWrite(5, TimeUnit.SECONDS)     // Hết hạn sau 5s
        .removalListener((key, value, cause) -> 
            System.out.printf("🗑️  Evicted: %s (reason: %s)%n", key, cause))
        .recordStats()                              // Theo dõi statistics
        .build();
    
    public static void main(String[] args) throws InterruptedException {
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║     FIXED MEMORY DEMO - Bounded Caffeine Cache            ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println("JVM: " + System.getProperty("java.version"));
        System.out.println("Max Heap: " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + " MB");
        System.out.println();
        System.out.println("✅ Using Caffeine Cache with:");
        System.out.println("   - maximumSize: 10 entries");
        System.out.println("   - expireAfterWrite: 5 seconds");
        System.out.println("   - Automatic eviction when full");
        System.out.println();
        
        for (int i = 0; i < 100; i++) {
            byte[] data = new byte[1024 * 1024]; // 1MB
            Arrays.fill(data, (byte) i);
            String key = "key-" + i;
            
            CACHE.put(key, data);
            
            // Stats
            CacheStats stats = CACHE.stats();
            long totalMemory = Runtime.getRuntime().totalMemory();
            long freeMemory = Runtime.getRuntime().freeMemory();
            long usedMemory = totalMemory - freeMemory;
            long maxMemory = Runtime.getRuntime().maxMemory();
            
            System.out.printf("[Iter %3d] Estimated: %2d | Evicted: %3d | HitRate: %.1f%% | Used: %5.1f MB%n",
                i, 
                CACHE.estimatedSize(), 
                stats.evictionCount(),
                stats.hitRate() * 100,
                usedMemory / (1024.0 * 1024));
            
            Thread.sleep(100);
        }
        
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║  ✅ Completed without OOM!                                ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
        
        // Print final stats
        CacheStats finalStats = CACHE.stats();
        System.out.println("📊 Final Statistics:");
        System.out.println("   - Total requests: " + finalStats.requestCount());
        System.out.println("   - Hit rate: " + String.format("%.2f%%", finalStats.hitRate() * 100));
        System.out.println("   - Miss rate: " + String.format("%.2f%%", finalStats.missRate() * 100));
        System.out.println("   - Eviction count: " + finalStats.evictionCount());
        System.out.println("   - Current size: " + CACHE.estimatedSize());
    }
}
