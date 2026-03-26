package demo;

import java.util.*;
import java.util.concurrent.*;

/**
 * Demo Memory Leak - Static Cache không giới hạn
 * 
 * Chạy với: -Xms64m -Xmx64m -XX:+HeapDumpOnOutOfMemoryError
 * 
 * Mục đích: Minh họa cách memory leak xảy ra với static unbounded cache
 * Kết quả: JVM sẽ throw OutOfMemoryError sau ~60 iterations với heap 64MB
 */
public class MemoryLeakDemo {
    
    // ❌ LEAK SOURCE: Static map không bao giờ được xóa
    // Mỗi entry chiếm ~1MB (byte array) + overhead của HashMap
    private static final Map<String, byte[]> CACHE = new HashMap<>();
    
    private static final ExecutorService executor = 
        Executors.newFixedThreadPool(10);
    
    public static void main(String[] args) throws InterruptedException {
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║       MEMORY LEAK DEMO - Static Unbounded Cache           ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println("JVM: " + System.getProperty("java.version"));
        System.out.println("Max Heap: " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + " MB");
        System.out.println();
        System.out.println("⚠️  Warning: This will cause OutOfMemoryError!");
        System.out.println("📊 Each iteration adds 1MB to the static cache");
        System.out.println("🔍 Watch the heap grow in VisualVM or JConsole");
        System.out.println();
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n📈 Final cache size: " + CACHE.size() + " entries");
        }));
        
        for (int i = 0; i < 100; i++) {
            final int iteration = i;
            
            executor.submit(() -> {
                try {
                    // Mỗi iteration thêm 1MB vào cache
                    byte[] data = new byte[1024 * 1024]; // 1MB
                    Arrays.fill(data, (byte) iteration);
                    
                    // Tạo key unique để không bị đè
                    String key = "key-" + iteration + "-" + System.nanoTime();
                    CACHE.put(key, data); // ← LEAK: Không bao giờ remove!
                    
                    long totalMemory = Runtime.getRuntime().totalMemory();
                    long freeMemory = Runtime.getRuntime().freeMemory();
                    long usedMemory = totalMemory - freeMemory;
                    long maxMemory = Runtime.getRuntime().maxMemory();
                    
                    System.out.printf("[Iter %3d] Cache: %3d entries | Used: %5.1f MB / %.0f MB (%.1f%%)%n",
                        iteration, 
                        CACHE.size(), 
                        usedMemory / (1024.0 * 1024),
                        maxMemory / (1024.0 * 1024),
                        (usedMemory * 100.0 / maxMemory));
                    
                } catch (OutOfMemoryError e) {
                    System.err.println("\n❌ OutOfMemoryError occurred at iteration " + iteration);
                    System.err.println("💡 Check the generated heap dump (.hprof file) with MAT or VisualVM");
                    System.exit(1);
                }
            });
            
            Thread.sleep(100);
        }
        
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);
    }
}
