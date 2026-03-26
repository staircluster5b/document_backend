import java.util.*;
import java.util.stream.*;

/**
 * Demo: Primitive vs Reference Types in Java
 * 
 * Chạy với: javac PrimitiveVsReferenceDemo.java && java PrimitiveVsReferenceDemo
 */
public class PrimitiveVsReferenceDemo {
    
    public static void main(String[] args) {
        System.out.println("=== BIẾN VÀ KIỂU DỮ LIỆU: PRIMITIVE VS REFERENCE ===\n");
        
        memoryComparison();
        System.out.println();
        
        performanceComparison();
        System.out.println();
        
        pitfallDemo();
        System.out.println();
        
        streamComparison();
        System.out.println();
        
        cacheDemo();
    }
    
    /**
     * So sánh memory giữa primitive array và boxed array
     */
    static void memoryComparison() {
        System.out.println("--- MEMORY COMPARISON ---");
        Runtime runtime = Runtime.getRuntime();
        
        // Primitive array
        System.gc();
        long memBefore = runtime.totalMemory() - runtime.freeMemory();
        int[] primitiveArray = new int[1_000_000];
        for (int i = 0; i < 1_000_000; i++) {
            primitiveArray[i] = i;
        }
        long memAfter = runtime.totalMemory() - runtime.freeMemory();
        long primitiveMem = (memAfter - memBefore);
        System.out.printf("Primitive int[1M]: ~%,d KB (4 MB expected)%n", primitiveMem / 1024);
        
        // Integer array (boxed) - forcing new objects beyond cache
        System.gc();
        memBefore = runtime.totalMemory() - runtime.freeMemory();
        Integer[] boxedArray = new Integer[1_000_000];
        for (int i = 0; i < 1_000_000; i++) {
            boxedArray[i] = i + 1000;  // +1000 để tránh cache
        }
        memAfter = runtime.totalMemory() - runtime.freeMemory();
        long boxedMem = (memAfter - memBefore);
        System.out.printf("Boxed Integer[1M]: ~%,d KB (~16+ MB expected)%n", boxedMem / 1024);
        System.out.printf("Memory overhead: %.1fx%n", (double) boxedMem / primitiveMem);
    }
    
    /**
     * So sánh performance giữa primitive và boxed
     */
    static void performanceComparison() {
        System.out.println("--- PERFORMANCE COMPARISON ---");
        int iterations = 10_000_000;
        
        // Primitive sum
        long start = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < iterations; i++) {
            sum += i;
        }
        long primitiveTime = System.nanoTime() - start;
        
        // Boxed sum - autoboxing in loop
        start = System.nanoTime();
        Long sumBoxed = 0L;
        for (Integer i = 0; i < iterations; i++) {
            sumBoxed += i;  // Autoboxing + unboxing mỗi iteration
        }
        long boxedTime = System.nanoTime() - start;
        
        System.out.printf("Primitive loop: %,d ms%n", primitiveTime / 1_000_000);
        System.out.printf("Boxed loop: %,d ms%n", boxedTime / 1_000_000);
        System.out.printf("Performance overhead: %dx slower%n", boxedTime / primitiveTime);
    }
    
    /**
     * Các lỗi thường gặp (pitfalls)
     */
    static void pitfallDemo() {
        System.out.println("--- COMMON PITFALLS ---");
        
        // 1. So sánh == với boxed types
        Integer a = 127;
        Integer b = 127;
        System.out.println("Integer 127 == 127: " + (a == b) + " (cached)");
        
        Integer c = 128;
        Integer d = 128;
        System.out.println("Integer 128 == 128: " + (c == d) + " (NOT cached!)");
        System.out.println("Integer 128.equals(128): " + c.equals(d));
        
        // 2. NullPointerException khi unboxing
        Integer nullInt = null;
        try {
            int x = nullInt;  // Unboxing null -> NPE
        } catch (NullPointerException e) {
            System.out.println("⚠️ NPE when unboxing null Integer!");
        }
        
        // 3. Boolean comparison
        Boolean bool1 = true;
        Boolean bool2 = true;
        System.out.println("Boolean true == true: " + (bool1 == bool2) + " (cached singleton)");
    }
    
    /**
     * So sánh Stream primitive vs boxed
     */
    static void streamComparison() {
        System.out.println("--- STREAM COMPARISON ---");
        int size = 10_000_000;
        int[] data = new int[size];
        for (int i = 0; i < size; i++) data[i] = i;
        
        // Primitive stream
        long start = System.nanoTime();
        long sum1 = Arrays.stream(data).sum();
        long primitiveStreamTime = System.nanoTime() - start;
        
        // Boxed stream
        start = System.nanoTime();
        long sum2 = Arrays.stream(data)
                         .boxed()  // Boxing thành Integer
                         .mapToLong(Integer::longValue)
                         .sum();
        long boxedStreamTime = System.nanoTime() - start;
        
        System.out.printf("IntStream (primitive): %,d ms%n", primitiveStreamTime / 1_000_000);
        System.out.printf("Stream<Integer> (boxed): %,d ms%n", boxedStreamTime / 1_000_000);
        System.out.printf("Overhead: %.1fx%n", (double) boxedStreamTime / primitiveStreamTime);
    }
    
    /**
     * Demo Integer Cache
     */
    static void cacheDemo() {
        System.out.println("--- INTEGER CACHE RANGES ---");
        
        // Cache range mặc định: -128 đến 127
        System.out.println("Default cache range: -128 to 127");
        
        // Byte: tất cả values đều cached
        Byte byte1 = 100;
        Byte byte2 = 100;
        System.out.println("Byte 100 == 100: " + (byte1 == byte2) + " (all bytes cached)");
        
        // Character: 0-127 cached
        Character char1 = 'A';  // 65
        Character char2 = 'A';
        System.out.println("Character 'A' == 'A': " + (char1 == char2));
        
        Character char3 = 200;  // > 127
        Character char4 = 200;
        System.out.println("Character 200 == 200: " + (char3 == char4) + " (>127, not cached)");
        
        // Long: -128 to 127
        Long long1 = 127L;
        Long long2 = 127L;
        System.out.println("Long 127 == 127: " + (long1 == long2));
        
        Long long3 = 128L;
        Long long4 = 128L;
        System.out.println("Long 128 == 128: " + (long3 == long4) + " (not cached)");
    }
}
