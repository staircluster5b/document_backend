# 🔥 Memory Leak trong Java: Phân tích Chuyên sâu & Công cụ Profiler

> **Tóm tắt Senior:** Memory Leak trong Java khác với C/C++ - không phải "quên free()" mà là **giữ reference không cần thiết** đến đối tượng, ngăn GC thu hồi. Hiểu được _retained heap_ vs _shallow heap_ là chìa khóa debug production.

---

## 📚 Mục lục

1. [Bản chất Memory Leak trong JVM](#1-bản-chất-memory-leak-trong-jvm)
2. [Các nguyên nhân phổ biến](#2-các-nguyên-nhân-phổ-biến)
3. [Phát hiện & Phân tích](#3-phát-hiện--phân-tích)
4. [Công cụ Profiler](#4-công-cụ-profiler)
5. [Best Practices & Prevention](#5-best-practices--prevention)
6. [Demo thực tế](#6-demo-thực-tế)

---

## 1. Bản chất Memory Leak trong JVM

### 1.1 Memory Leak là gì?

Trong Java, **Memory Leak** xảy ra khi:
- Đối tượng không còn được sử dụng (unreachable từ business logic)
- **Nhưng vẫn reachable từ GC Roots** → GC không thể thu hồi

```
┌─────────────────────────────────────────────────────────┐
│                     GC Roots                            │
│  (Static fields, Thread locals, JNI references...)       │
└─────────────────┬───────────────────────────────────────┘
                  │
                  ▼
         ┌────────────────┐
         │  Leaked Object │ ◄── Still referenced
         │   ("Zombie")   │     but logically unused
         └────────┬───────┘
                  │
                  ▼
         ┌────────────────┐
         │  Large Object  │ ◄── GC cannot reclaim
         │    Graph       │     (10MB, 100MB, 1GB...)
         └────────────────┘
```

### 1.2 Shallow Heap vs Retained Heap

| Metric | Định nghĩa | Ứng dụng |
|--------|-----------|----------|
| **Shallow Heap** | Kích thước object riêng lẻ (headers + fields) | Xác định object "nặng" về mặt cấu trúc |
| **Retained Heap** | Shallow heap + tất cả object chỉ reachable qua object này | **Đây là thực sự bị leak** - memory không thể GC |

> 💡 **Senior Tip:** Một object 16 bytes (shallow) có thể giữ 100MB (retained) nếu nó là root của một collection lớn.

### 1.3 Dominator Tree

Trong Eclipse MAT (Memory Analyzer Tool), **Dominator Tree** giúp xác định:
- Object A _dominates_ object B nếu mọi path từ GC roots đến B đều đi qua A
- Cắt reference đến A → toàn bộ subtree của B được GC

```
GC Root → Object A (Dominator)
              │
              ├──► Object B (Dominated by A)
              │       └──► Object C
              │
              └──► Object D (Dominated by A)
                      └──► Object E

Nếu A leaked → B, C, D, E đều leaked
```

---

## 2. Các nguyên nhân phổ biến

### 2.1 Static Collections (🔴 HIGH RISK)

```java
public class CacheManager {
    // ❌ LEAK: Static map grows forever, never GC'd
    private static final Map<String, Object> CACHE = new HashMap<>();
    
    public void addToCache(String key, Object value) {
        CACHE.put(key, value);  // No eviction policy!
    }
}
```

**Tại sao leak:**
- Static fields là GC Roots
- Map và tất cả entries reachable mãi mãi
- Nếu value chứa reference đến ClassLoader → **ClassLoader leak** (catastrophic)

**Giải pháp:**
```java
// ✅ Dùng WeakReference hoặc Cache có eviction
private static final Cache<String, Object> CACHE = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(Duration.ofMinutes(30))
    .weakValues()  // Cho phép GC khi value không còn strong ref
    .build();
```

### 2.2 Unclosed Resources (🔴 HIGH RISK)

```java
// ❌ LEAK: File handles, DB connections, native memory
public void processFile(String path) throws IOException {
    InputStream is = new FileInputStream(path);  // Never closed!
    // ... process
} // Stream leaked, native resources held
```

**Tại sao leak:**
- `FileInputStream` giữ file descriptor (hệ điều hành limit ~1024-65535)
- `Connection` giữ socket và DB resources
- Finalizer chỉ chạy khi GC → không đáng tin cậy

**Giải pháp (Java 7+):**
```java
// ✅ try-with-resources: Auto-close, exception-safe
public void processFile(String path) throws IOException {
    try (InputStream is = new FileInputStream(path);
         BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
        // ... process
    } // Auto-closed in reverse order: br → is
}
```

### 2.3 Listener/Callback không unregister

```java
public class EventBus {
    // ❌ LEAK: Listeners accumulate over time
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    
    public void register(Listener l) {
        listeners.add(l);  // Never removed!
    }
}

// Activity/Controller bị giữ mãi mãi
public class UserController {
    public UserController() {
        EventBus.getInstance().register(this);  // Forgot to unregister!
    }
}
```

**Giải pháp:**
```java
// ✅ WeakReference - tự động remove khi listener GC'd
private final Set<WeakReference<Listener>> weakListeners = 
    Collections.newSetFromMap(new WeakHashMap<>());

// Hoặc dùng lifecycle hook
public void destroy() {
    EventBus.getInstance().unregister(this);  // Explicit cleanup
}
```

### 2.4 ThreadLocal Abuse

```java
public class RequestContext {
    // ❌ LEAK in Thread Pool: Value persists across requests
    private static final ThreadLocal<Map<String, Object>> CONTEXT = 
        ThreadLocal.withInitial(HashMap::new);
    
    public void set(String key, Object value) {
        CONTEXT.get().put(key, value);
    }
    // Forgot to clear() at end of request!
}
```

**Tại sao leak:**
- Thread pool giữ threads sống lâu dài
- ThreadLocal map của mỗi thread không bao giờ được clear
- Request data tích lũy qua nhiều requests

**Giải pháp:**
```java
// ✅ Luôn clear trong finally block
try {
    CONTEXT.get().put("user", user);
    // ... process request
} finally {
    CONTEXT.remove();  // Critical!
}

// Hoặc dùng ScopedValue (Java 21+) - tự động cleanup
private static final ScopedLocal<Map<String, Object>> CONTEXT = 
    ScopedLocal.newInstance();
```

### 2.5 String.intern() (Java 6-7)

```java
// ❌ LEAK in Java 6-7: PermGen overflow
public void process(String input) {
    String canonical = input.intern();  // Stored in PermGen
}
```

> **Fixed in Java 8+:** String pool chuyển sang Heap, có thể GC.

### 2.6 ClassLoader Leaks (Enterprise Killer)

Xảy ra khi:
1. Custom ClassLoader (Tomcat, OSGi) bị giữ bởi singleton/static
2. ThreadLocal không clear trước khi undeploy
3. DriverManager giữ reference đến loaded drivers

```
Tomcat undeploy → ClassLoader should be GC'd
         │
         ├──► Singleton static giữ reference → ❌ LEAK
         ├──► ThreadLocal chưa clear → ❌ LEAK  
         └──► DriverManager drivers → ❌ LEAK

Result: PermGen/Metaspace OOM sau n redeploys
```

---

## 3. Phát hiện & Phân tích

### 3.1 Heap Dump là gì?

Heap dump = snapshot toàn bộ heap memory tại một thời điểm, bao gồm:
- Tất cả objects và fields
- Reference chains (dominator tree)
- Thread stacks và local variables

**Tạo heap dump:**

```bash
# Khi OOM xảy ra (Production must-have)
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/log/java/heapdump.hprof

# On-demand từ command line
jmap -dump:live,format=b,file=heap.hprof <pid>

# From code
ManagementFactory.getMemoryMXBean();
// Or use HotSpotDiagnosticMXBean
```

### 3.2 Phân tích Heap Dump

#### Bước 1: Histogram Analysis
```
Class                          | Objects | Shallow Heap
-------------------------------|---------|-------------
byte[]                         | 1,234   | 523,456,789
java.util.HashMap$Node         | 567,890 | 13,629,360
com.example.LeakedSession      | 10,000  | 240,000     ← Suspect!
```

#### Bước 2: Dominator Tree
```
Class                          | Retained Heap
-------------------------------|--------------
com.example.CacheManager       | 456,789,012   ← Dominator!
  ├── HashMap$Node[]           | 400,000,000
  ├── HashMap                  | 56,789,012
  └── ...
```

#### Bước 3: Path to GC Roots
```
Path from LeakedSession#1234 to GC Root:
├── LeakedSession@0x12345678
├── CacheManager.CACHE (static)
├── Class com.example.CacheManager
└── System ClassLoader

→ ROOT CAUSE: Static map holding references
```

### 3.3 Leak Detection Patterns

| Pattern | Indication | Tool Query |
|---------|-----------|------------|
| **Suspect #1** | Object count tăng liên tục | Histogram comparison |
| **Suspect #2** | Retained heap tăng theo time | Dominator tree growth |
| **Suspect #3** | Duplicate strings/objects | Duplicated classes report |
| **Suspect #4** | ClassLoader retention | Path to GC roots analysis |

---

## 4. Công cụ Profiler

### 4.1 VisualVM (Built-in, Free)

**Features:**
- Heap dump từ local/remote JVM
- Histogram classes
- OQL (Object Query Language)
- CPU & Memory profiling

**OQL Example:**
```sql
-- Find all HashMaps with size > 1000
SELECT * FROM java.util.HashMap WHERE size > 1000

-- Find leaked sessions
SELECT * FROM com.example.Session 
WHERE creationTime < TIMESTAMP '2024-01-01'
```

**Giới hạn:**
- Overhead cao với heap lớn (>4GB)
- Không phù hợp production continuous monitoring

### 4.2 Eclipse MAT (Memory Analyzer Tool)

**Features (Best for Heap Dump Analysis):**
- **Dominator Tree:** Tìm objects chiếm nhiều memory nhất
- **Leak Suspects Report:** Auto-detect patterns
- **Path to GC Roots:** Xác định tại sao object chưa được GC
- **Compare Heap Dumps:** Phát hiện growth over time

**Leak Suspects Report Example:**
```
One instance of "com.example.CacheManager" loaded by 
"AppClassLoader @ 0x1234" occupies 456,789,012 bytes 
(87.23%) of heap. 

The instance is referenced by:
  - static com.example.CacheManager.INSTANCE

Keywords: com.example.CacheManager, java.util.HashMap
```

### 4.3 JProfiler (Commercial)

**Features:**
- Live profiling với overhead thấp
- Telemetries (heap, GC, threads, CPU)
- Allocation hotspots
- Heap walker với filtering

**Allocation Recording:**
```
Top Allocated Classes (last 60s):
┌─────────────────────────┬────────────┬──────────────┐
│ Class                   │ Objects    │ Memory       │
├─────────────────────────┼────────────┼──────────────┤
│ byte[]                  │ 1,234,567  │ 523.4 MB     │
│ String                  │ 2,456,789  │ 156.7 MB     │
│ LeakedObject            │ 10,000     │ 45.6 MB      │ ⚠️
└─────────────────────────┴────────────┴──────────────┘
```

### 4.4 Async-Profiler (Production Safe)

```bash
# CPU profiling
./profiler.sh -d 30 -f cpu.html <pid>

# Memory allocation (JDK 11+)
./profiler.sh -e alloc -d 30 -f alloc.html <pid>

# Heap dump alternative
./profiler.sh -e live --heapdump -f heap.hprof <pid>
```

**Ưu điểm:**
- Overhead < 1% (sử dụng PerfEvents/JVMTI)
- Không cần restart JVM
- Flame graphs visualization

### 4.5 So sánh Công cụ

| Tool | Best For | Overhead | Cost |
|------|----------|----------|------|
| **VisualVM** | Quick check, development | Medium | Free |
| **Eclipse MAT** | Deep heap dump analysis | N/A (offline) | Free |
| **JProfiler** | Live profiling, production | Low-Medium | $$$ |
| **Async-Profiler** | Production continuous | Very Low | Free |
| **YourKit** | Allocation tracking | Low-Medium | $$ |

---

## 5. Best Practices & Prevention

### 5.1 Code Review Checklist

- [ ] Static collections có eviction policy?
- [ ] `close()` được gọi trong `finally` hoặc try-with-resources?
- [ ] Listeners/callbacks được unregister khi destroy?
- [ ] ThreadLocal được `.remove()` trong finally?
- [ ] Anonymous inner classes không capture outer class khi không cần?
- [ ] Finalizers avoided (dùng Cleaner API)?

### 5.2 Java 9+ Cleaners (Thay thế Finalizers)

```java
public class NativeResource implements AutoCloseable {
    private static final Cleaner cleaner = Cleaner.create();
    private final State state;
    private final Cleaner.Cleanable cleanable;
    
    private static class State implements Runnable {
        private long nativeHandle;  // Không phải reference, GC không quan tâm
        
        State(long nativeHandle) {
            this.nativeHandle = nativeHandle;
        }
        
        @Override
        public void run() {
            // Chạy khi NativeResource GC'd hoặc explicit close()
            if (nativeHandle != 0) {
                nativeFree(nativeHandle);
                nativeHandle = 0;
            }
        }
    }
    
    public NativeResource(long nativeHandle) {
        this.state = new State(nativeHandle);
        this.cleanable = cleaner.register(this, state);
    }
    
    @Override
    public void close() {
        cleanable.clean();  // Explicit or on GC
    }
}
```

**Ưu điểm Cleaner vs Finalizer:**
- Chạy nhanh hơn, không block GC
- Có thể gọi explicit (unlike finalize())
- Không có resurrection attacks

### 5.3 Monitoring & Alerting

```java
// Export heap usage metrics
MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();

long usedMB = heapUsage.getUsed() / 1024 / 1024;
long maxMB = heapUsage.getMax() / 1024 / 1024;
double usagePercent = (double) usedMB / maxMB * 100;

// Alert if > 85%
if (usagePercent > 85) {
    alert("Heap usage critical: " + usagePercent + "%");
    // Trigger heap dump for analysis
}
```

### 5.4 JVM Flags cho Memory Debugging

```bash
# Heap dump on OOM
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/log/java/

# Class histogram before full GC  
-XX:+PrintClassHistogramBeforeFullGC

# Native memory tracking
-XX:NativeMemoryTracking=summary
jcmd <pid> VM.native_memory summary

# GC logging (JDK 9+)
-Xlog:gc*:file=/var/log/java/gc.log:time,uptime,level,tags:filecount=10,filesize=100M
```

---

## 6. Demo thực tế

### 6.1 Memory Leak Demo: Static Cache

```java
import java.util.*;
import java.util.concurrent.*;

public class MemoryLeakDemo {
    
    // ❌ LEAK: Static cache without bounds
    private static final Map<String, byte[]> STATIC_CACHE = new HashMap<>();
    
    // ✅ SAFE: Bounded cache with expiration
    private static final Cache<String, byte[]> SAFE_CACHE = 
        Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .weakValues()
            .build();
    
    public static void main(String[] args) throws Exception {
        System.out.println("Starting memory leak demo...");
        printMemory("Initial");
        
        // Simulate leak
        for (int i = 0; i < 1000; i++) {
            String key = "key-" + i;
            byte[] data = new byte[1024 * 1024]; // 1MB each
            
            STATIC_CACHE.put(key, data);  // ❌ Leak!
            
            if (i % 100 == 0) {
                printMemory("After " + i + " iterations");
                Thread.sleep(100);
            }
        }
        
        printMemory("Final - Leaked!");
        
        // Force GC - won't help, objects are still referenced
        System.gc();
        Thread.sleep(1000);
        printMemory("After forced GC (still leaked)");
    }
    
    private static void printMemory(String label) {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        long max = rt.maxMemory() / 1024 / 1024;
        System.out.printf("[%s] Used: %dMB / Max: %dMB (%.1f%%)%n", 
            label, used, max, (double)used/max*100);
    }
}
```

**Kết quả:**
```
[Initial] Used: 15MB / Max: 2048MB (0.7%)
[After 0 iterations] Used: 16MB / Max: 2048MB (0.8%)
[After 100 iterations] Used: 116MB / Max: 2048MB (5.7%)
[After 200 iterations] Used: 216MB / Max: 2048MB (10.5%)
...
[Final - Leaked!] Used: 1015MB / Max: 2048MB (49.6%)
[After forced GC] Used: 1015MB / Max: 2048MB (49.6%)  ← GC cannot reclaim!
```

### 6.2 Resource Leak Demo

```java
import java.io.*;
import java.nio.file.*;

public class ResourceLeakDemo {
    
    // ❌ LEAK: Resource not closed
    public static String readFileBad(Path path) throws IOException {
        BufferedReader reader = Files.newBufferedReader(path);
        return reader.readLine();  // Reader leaked!
    }
    
    // ✅ SAFE: try-with-resources
    public static String readFileGood(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            return reader.readLine();
        } // Auto-closed
    }
    
    // ✅ SAFE: Multiple resources
    public static void copyFile(Path src, Path dst) throws IOException {
        try (InputStream in = Files.newInputStream(src);
             OutputStream out = Files.newOutputStream(dst)) {
            in.transferTo(out);
        }
    }
}
```

### 6.3 ThreadLocal Leak Demo

```java
import java.util.concurrent.*;

public class ThreadLocalLeakDemo {
    private static final ThreadLocal<Map<String, Object>> CONTEXT = 
        ThreadLocal.withInitial(HashMap::new);
    
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    
    // ❌ LEAK: ThreadLocal not cleared
    public void processRequestBad(String requestId, Object data) {
        executor.submit(() -> {
            CONTEXT.get().put("requestId", requestId);
            CONTEXT.get().put("data", data);
            // Process...
            // Forgot CONTEXT.remove()!
        });
    }
    
    // ✅ SAFE: Always clear ThreadLocal
    public void processRequestGood(String requestId, Object data) {
        executor.submit(() -> {
            try {
                CONTEXT.get().put("requestId", requestId);
                CONTEXT.get().put("data", data);
                // Process...
            } finally {
                CONTEXT.remove();  // Critical!
            }
        });
    }
}
```

### 6.4 Heap Dump Analysis Script

```bash
#!/bin/bash
# analyze-heap.sh - Quick heap dump analysis

PID=$1
DUMP_FILE="heap-$(date +%Y%m%d-%H%M%S).hprof"

echo "Creating heap dump for PID $PID..."
jmap -dump:live,format=b,file=$DUMP_FILE $PID

echo "Analyzing top memory consumers..."
jhat -J-mx2G $DUMP_FILE &
JHAT_PID=$!
sleep 5

echo "Heap dump analysis available at http://localhost:7000"
echo "Look for:"
echo "  1. Histogram (class instances)"
echo "  2. Dominator tree (retained heap)"
echo "  3. Path to GC roots (why objects aren't GC'd)"

read -p "Press Enter to stop jhat..."
kill $JHAT_PID
```

---

## 🎯 Tóm tắt Senior

### Anti-patterns tránh:
1. **Unbounded static collections** - Luôn có size limit và TTL
2. **Unclosed resources** - Dùng try-with-resources hoặc Cleaner API
3. **Forgotten listeners** - WeakReference hoặc explicit unregister
4. **ThreadLocal without remove()** - Luôn clear trong finally
5. **Anonymous inner classes** - Cẩn thận với implicit reference to outer

### Debugging Workflow:
```
1. Nghi ngờ leak → Enable heap dump on OOM
2. OOM xảy ra → Open dump in Eclipse MAT
3. Run "Leak Suspects Report"
4. Check "Dominator Tree" for largest retained heap
5. "Path to GC Roots" để tìm reference chain
6. Fix root cause (static map, listener, ThreadLocal...)
7. Verify fix with before/after heap dump comparison
```

### Java 21+ Enhancements:
- **ScopedLocal** (Preview): Thay thế ThreadLocal, tự động cleanup
- **Virtual Threads**: Giảm thread count, giảm ThreadLocal pressure
- **Generational ZGC**: Lower latency, better memory efficiency

---

## 📚 Tài liệu tham khảo

1. [Eclipse MAT Documentation](https://www.eclipse.org/mat/documentation/)
2. [Java Performance: The Definitive Guide - Scott Oaks](https://www.oreilly.com/library/view/java-performance-the/9781449363512/)
3. [Effective Java 3rd Ed - Item 8: Avoid Finalizers and Cleaners](https://www.oracle.com/technetwork/java/effectivejava-136174.html)
4. [JVM Memory Management Whitepaper](https://docs.oracle.com/javase/8/docs/technotes/guides/vm/memory-management-white-paper.pdf)
5. [Async-Profiler GitHub](https://github.com/jvm-profiling-tools/async-profiler)

---

> 💡 **Remember:** Prevention > Detection. Code review kiểm tra static fields, ThreadLocal cleanup, và resource closing là hiệu quả nhất để tránh memory leaks.
