# Concurrent Collections - Nghiên cứu chuyên sâu

> **Senior Backend Focus:** Hiểu sâu cơ chế đồng bộ hóa, trade-offs giữa consistency và performance, và các anti-patterns gây thảm họa trong production.

---

## 📋 Tổng quan

Concurrent Collections trong `java.util.concurrent` là tập hợp các cấu trúc dữ liệu thread-safe được thiết kế để hoạt động hiệu quả trong môi trường đa luồng mà không cần external synchronization. Khác với `Collections.synchronizedXXX()` - chỉ đơn thuần wrap một lock xung quanh collection truyền thống, concurrent collections sử dụng các kỹ thuật tối ưu như:

- **Lock Striping** (phân đoạn lock)
- **Lock-free algorithms** (sử dụng CAS - Compare And Swap)
- **Copy-on-Write** (tạo bản sao khi ghi)
- **Optimistic concurrency control**

---

## 1. BlockingQueue Interface

### 1.1 Bản chất hoạt động

`BlockingQueue` là queue thread-safe hỗ trợ 4 nhóm operations chính:

| Operation | Throws Exception | Special Value | Blocks | Times Out |
|-----------|------------------|---------------|--------|-----------|
| Insert | `add(e)` | `offer(e)` | `put(e)` | `offer(e, time, unit)` |
| Remove | `remove()` | `poll()` | `take()` | `poll(time, unit)` |
| Examine | `element()` | `peek()` | N/A | N/A |

**Cơ chế blocking:** Sử dụng `ReentrantLock` kết hợp với `Condition` (notFull, notEmpty) để quản lý thread coordination.

### 1.2 Các Implementations

#### 🔹 ArrayBlockingQueue
```java
// Cấu trúc: Array có kích thước cố định + Single Lock
private final ReentrantLock lock;
private final Condition notEmpty;
private final Condition notFull;
```

- **Lock Strategy:** Single lock cho cả put và take → contention cao khi throughput lớn
- **Memory:** Pre-allocated array → memory usage ổn định, ít GC pressure
- **Use case:** Bounded buffer, producer-consumer với số lượng items giới hạn

**⚠️ Anti-pattern:** Không set capacity → mặc định Integer.MAX_VALUE (gần như unbounded, gây OOM)

#### 🔹 LinkedBlockingQueue
```java
// Cấu trúc: Linked list + Two Locks (putLock, takeLock)
private final ReentrantLock takeLock = new ReentrantLock();
private final Condition notEmpty = takeLock.newCondition();
private final ReentrantLock putLock = new ReentrantLock();
private final Condition notFull = putLock.newCondition();
```

- **Lock Strategy:** Two-lock design (separate locks cho head và tail) → higher concurrency
- **Memory:** Node allocation khi insert → GC pressure cao hơn ArrayBlockingQueue
- **Use case:** Thread pools (ThreadPoolExecutor sử dụng mặc định), pipelines

**⚠️ Performance Trap:** LinkedBlockingQueue mặc định unbounded (capacity = Integer.MAX_VALUE). Trong production **luôn luôn** set capacity:
```java
// SAI - Có thể gây OOM
new LinkedBlockingQueue<>();

// ĐÚNG
new LinkedBlockingQueue<>(10000);
```

#### 🔹 PriorityBlockingQueue
```java
// Cấu trúc: Binary heap + Single Lock
private final ReentrantLock lock = new ReentrantLock();
private final Condition notEmpty = lock.newCondition();
```

- **Ordering:** Dựa trên `Comparator` hoặc `Comparable` tự nhiên
- **Complexity:** O(log n) cho insert/remove, O(1) cho peek
- **Memory:** Dynamic resize (grow khi đầy)

**⚠️ Anti-pattern:** Thay đổi priority của element sau khi đã insert → heap corruption

#### 🔹 DelayQueue
```java
// Chuyên biệt cho delayed scheduling
public E take() throws InterruptedException {
    final ReentrantLock lock = this.lock;
    lock.lockInterruptibly();
    try {
        for (;;) {
            E first = q.peek();
            if (first == null) {
                available.await();
            } else {
                long delay = first.getDelay(NANOSECONDS);
                if (delay <= 0)
                    return q.poll();
                // ... wait logic
            }
        }
    } finally {
        lock.unlock();
    }
}
```

- **Use case:** Scheduled tasks, cache expiration, rate limiting
- **Yêu cầu:** Elements phải implement `Delayed`

#### 🔹 SynchronousQueue

**Đặc biệt:** Queue với capacity = 0. Mỗi insert phải chờ một remove tương ứng (và ngược lại).

```java
// Không có backing store - chỉ là handoff giữa threads
SynchronousQueue<Integer> queue = new SynchronousQueue<>();

// Thread A: put() sẽ block cho đến khi Thread B gọi take()
```

- **Use case:** Direct handoff, tối ưu cho zero-capacity scenarios
- **Fairness:** Có thể chọn fair (FIFO) hoặc unfair (LIFO, mặc định)
- **Performance:** Lower latency nhưng lower throughput

#### 🔹 LinkedTransferQueue (Java 7+)

Kết hợp tính năng của `SynchronousQueue` và `LinkedBlockingQueue`:

```java
// Có thể transfer trực tiếp hoặc buffer
transfer(E e);    // Block cho đến khi consumer nhận
tryTransfer(E e); // Non-blocking
tryTransfer(E e, timeout, unit); // Timeout-based
```

- **Use case:** Work stealing algorithms, high-performance message passing

---

## 2. ConcurrentSkipListMap

### 2.1 Bản chất cấu trúc dữ liệu

**Skip List** là cấu trúc dữ liệu probabilistic thay thế cho Balanced Tree (như Red-Black Tree trong TreeMap). Thay vì rebalancing phức tạp, Skip List sử dụng nhiều levels của linked lists.

```
Level 3:  head -------------------------> tail
Level 2:  head --------> 4 -----------> tail
Level 1:  head --> 2 --> 4 --> 6 -----> tail
Level 0:  head --> 2 --> 4 --> 6 --> 8 -> tail
```

### 2.2 Lock-free Implementation

```java
// Mỗi node chứa array của forward references
class Node<K,V> {
    final K key;
    volatile V value;
    volatile Node<K,V>[] next;  // Mảng các forward pointers
}
```

**Concurrency Strategy:**
- **Reads:** Lock-free, sử dụng `volatile` và memory ordering
- **Writes:** Fine-grained locking với CAS operations
- **Complexity:** O(log n) average cho get/put/remove

### 2.3 So sánh với ConcurrentHashMap

| Tiêu chí | ConcurrentHashMap | ConcurrentSkipListMap |
|----------|-------------------|----------------------|
| **Ordering** | Không có | Sorted (theo key) |
| **Null keys** | Không cho phép | Không cho phép |
| **Null values** | Không cho phép | Không cho phép |
| **Range queries** | Không hỗ trợ | `subMap()`, `headMap()`, `tailMap()` |
| **Ceiling/Floor** | Không có | `ceilingKey()`, `floorKey()` |
| **Performance** | O(1) average | O(log n) |
| **Memory** | Thấp hơn | Cao hơn (multiple levels) |

### 2.4 Use Cases Production

```java
// 1. Time-series data với automatic expiration
ConcurrentSkipListMap<Long, Event> events = new ConcurrentSkipListMap<>();
events.headMap(System.currentTimeMillis() - 3600000).clear(); // Remove events older than 1h

// 2. Leaderboard/Range queries
ConcurrentSkipListMap<Integer, User> scores = new ConcurrentSkipListMap<>();
// Lấy top 10
scores.descendingMap().values().stream().limit(10).collect(Collectors.toList());

// 3. Rate limiting với sliding window
ConcurrentSkipListMap<Long, Integer> requests = new ConcurrentSkipListMap<>();
```

**⚠️ Anti-pattern:** Sử dụng SkipList khi chỉ cần point lookups (ConcurrentHashMap nhanh hơn đáng kể)

---

## 3. CopyOnWriteArrayList

### 3.1 Cơ chế "Copy-on-Write"

```java
// Write operation: Tạo bản sao, modify, sau đó publish reference mới
public boolean add(E e) {
    final ReentrantLock lock = this.lock;
    lock.lock();
    try {
        Object[] elements = getArray();
        int len = elements.length;
        Object[] newElements = Arrays.copyOf(elements, len + 1);
        newElements[len] = e;
        setArray(newElements);  // Atomic reference update
        return true;
    } finally {
        lock.unlock();
    }
}
```

**Characteristics:**
- **Immutable snapshots:** Iterator thấy array tại thời điểm iteration bắt đầu (không bao giờ throw ConcurrentModificationException)
- **Read-heavy:** Read không cần lock (chỉ đọc volatile reference)
- **Write-expensive:** Mỗi write tạo array mới (O(n))

### 3.2 Memory & Performance Analysis

**Memory Overhead:**
```
List size: 10,000 elements
ArrayList overhead: ~40KB + object headers
CopyOnWriteArrayList write: Tạo bản sao 40KB mỗi lần
```

**Use case phù hợp:**
- Read frequency >> Write frequency (tỷ lệ 10:1 hoặc cao hơn)
- Event listener lists
- Configuration caches

**⚠️ Thảm họa nếu dùng sai:**
```java
// KHÔNG BAO GIỜ làm điều này trong production
CopyOnWriteArrayList<Integer> list = new CopyOnWriteArrayList<>();
for (int i = 0; i < 1000000; i++) {
    list.add(i);  // Mỗi lần add tạo array mới! O(n^2) complexity
}
// Memory usage: ~4GB (cumulative allocations), GC thrashing
```

### 3.3 CopyOnWriteArraySet

Implementation dựa trên CopyOnWriteArrayList, sử dụng `equals()` để kiểm tra duplicates:

```java
public boolean add(E e) {
    return al.addIfAbsent(e);
}
```

**Complexity:** O(n) cho mỗi add (phải scan toàn bộ list để check duplicate)

---

## 4. Anti-Patterns và Best Practices

### ❌ Anti-Pattern 1: "Thread-safe" Iteration hiểu nhầm

```java
// SAI - ConcurrentModificationException vẫn có thể xảy ra
for (String item : concurrentHashMap.keySet()) {
    concurrentHashMap.remove(item);  // Không an toàn!
}

// ĐÚNG - Sử dụng iterator hoặc removeIf
concurrentHashMap.entrySet().removeIf(e -> e.getValue().isExpired());
```

### ❌ Anti-Pattern 2: Size-based loops với concurrent collections

```java
// NGUY HIỂM - Size có thể thay đổi giữa các lần check
for (int i = 0; i < queue.size(); i++) {
    process(queue.poll());  // Có thể miss elements hoặc null
}

// ĐÚNG
while ((item = queue.poll()) != null) {
    process(item);
}
```

### ❌ Anti-Pattern 3: Composite operations không atomic

```java
// SAI - Check-then-act race condition
if (!map.containsKey(k)) {
    map.put(k, v);  // Race condition ở đây!
}

// ĐÚNG - Sử dụng atomic operations
map.putIfAbsent(k, v);
map.computeIfAbsent(k, k -> createValue());
```

### ✅ Best Practice: Producer-Consumer với graceful shutdown

```java
public class GracefulProducerConsumer {
    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>(1000);
    private volatile boolean running = true;
    
    public void produce(Task task) throws InterruptedException {
        queue.put(task);  // Block nếu queue đầy
    }
    
    public void consume() {
        while (running || !queue.isEmpty()) {
            try {
                Task task = queue.poll(100, TimeUnit.MILLISECONDS);
                if (task != null) {
                    process(task);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    public void shutdown() {
        running = false;
    }
}
```

---

## 5. Java 21+ Updates

### 5.1 Sequenced Collections (Java 21)

```java
// Mới trong Java 21 - Unified interface cho ordered collections
SequencedCollection<E> extends Collection<E> {
    void addFirst(E e);
    void addLast(E e);
    E getFirst();
    E getLast();
    E removeFirst();
    E removeLast();
    SequencedCollection<E> reversed();
}
```

**Concurrent implementations hiện chưa hỗ trợ SequencedCollection** vì difficulty trong maintaining ordering guarantees thread-safely.

### 5.2 Virtual Threads (Project Loom) Impact

```java
// Với Virtual Threads, blocking operations trở nên "cheap"
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    BlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    
    // Có thể tạo hàng nghìn threads block trên queue.take()
    for (int i = 0; i < 10000; i++) {
        executor.submit(() -> {
            while (!Thread.interrupted()) {
                Task task = queue.take();  // Block nhưng không tie up OS thread
                process(task);
            }
        });
    }
}
```

**Impact:** Virtual threads làm giảm importance của non-blocking data structures trong một số use cases, nhưng concurrent collections vẫn critical cho shared state management.

### 5.3 Foreign Function & Memory API (Preview)

Trong tương lai, có thể có off-heap concurrent collections sử dụng MemorySegments cho ultra-low latency scenarios.

---

## 6. Demo: Web Crawler Concurrent

```java
package demo;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Web Crawler sử dụng multiple concurrent collections
 * 
 * Architecture:
 * - BlockingQueue: Task queue cho URLs cần crawl
 * - ConcurrentSkipListSet: Dedup URLs đã crawl (sorted để debug)
 * - ConcurrentHashMap: Store kết quả
 * - CopyOnWriteArrayList: Event listeners (cấu hình ít thay đổi)
 */
public class ConcurrentWebCrawler {
    
    // Task queue với backpressure
    private final BlockingQueue<URI> urlQueue = new LinkedBlockingQueue<>(1000);
    
    // Visited URLs - SkipList cho sorted ordering khi debug
    private final Set<URI> visitedUrls = new ConcurrentSkipListSet<>();
    
    // Results store
    private final ConcurrentHashMap<URI, PageResult> results = new ConcurrentHashMap<>();
    
    // Event listeners - CopyOnWrite vì listeners ít thay đổi
    private final CopyOnWriteArrayList<CrawlListener> listeners = new CopyOnWriteArrayList<>();
    
    private final HttpClient httpClient;
    private final AtomicInteger activeWorkers = new AtomicInteger(0);
    private volatile boolean shutdown = false;
    
    public ConcurrentWebCrawler() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newVirtualThreadPerTaskExecutor())  // Java 21+
            .build();
    }
    
    public void addListener(CrawlListener listener) {
        listeners.add(listener);
    }
    
    public CompletableFuture<Void> start(int workerCount) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        
        CompletableFuture<?>[] futures = new CompletableFuture[workerCount];
        for (int i = 0; i < workerCount; i++) {
            futures[i] = CompletableFuture.runAsync(this::workerLoop, executor);
        }
        
        return CompletableFuture.allOf(futures);
    }
    
    private void workerLoop() {
        activeWorkers.incrementAndGet();
        try {
            while (!shutdown) {
                URI url = urlQueue.poll(1, TimeUnit.SECONDS);
                if (url == null) continue;
                
                // Atomic check-and-set
                if (!visitedUrls.add(url)) {
                    continue;  // Already visited
                }
                
                try {
                    HttpRequest request = HttpRequest.newBuilder(url)
                        .GET()
                        .timeout(Duration.ofSeconds(30))
                        .build();
                    
                    HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString()
                    );
                    
                    PageResult result = new PageResult(
                        url, 
                        response.statusCode(),
                        response.body().length(),
                        System.currentTimeMillis()
                    );
                    
                    // Concurrent put - thread-safe
                    results.put(url, result);
                    
                    // Notify listeners
                    listeners.forEach(l -> l.onPageCrawled(result));
                    
                } catch (Exception e) {
                    results.put(url, new PageResult(url, -1, 0, System.currentTimeMillis()));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            activeWorkers.decrementAndGet();
        }
    }
    
    public boolean submitUrl(URI url) throws InterruptedException {
        return urlQueue.offer(url, 5, TimeUnit.SECONDS);  // Timeout để tránh block vô hạn
    }
    
    public void shutdown() {
        shutdown = true;
    }
    
    public int getQueueSize() {
        return urlQueue.size();
    }
    
    public int getVisitedCount() {
        return visitedUrls.size();
    }
    
    public int getResultCount() {
        return results.size();
    }
    
    // Records cho Java 16+
    record PageResult(URI url, int statusCode, int contentLength, long timestamp) {}
    
    @FunctionalInterface
    interface CrawlListener {
        void onPageCrawled(PageResult result);
    }
    
    // Demo
    public static void main(String[] args) throws Exception {
        ConcurrentWebCrawler crawler = new ConcurrentWebCrawler();
        
        // Add logging listener
        crawler.addListener(result -> 
            System.out.printf("Crawled: %s (status=%d, size=%d)%n",
                result.url(), result.statusCode(), result.contentLength())
        );
        
        // Start workers
        CompletableFuture<Void> completion = crawler.start(10);
        
        // Submit seed URLs
        String[] seeds = {
            "https://example.com",
            "https://httpbin.org/get",
            "https://jsonplaceholder.typicode.com/posts/1"
        };
        
        for (String url : seeds) {
            if (!crawler.submitUrl(URI.create(url))) {
                System.err.println("Queue full, dropped: " + url);
            }
        }
        
        // Wait và report
        Thread.sleep(10000);
        crawler.shutdown();
        
        System.out.printf("%nStats:%n");
        System.out.printf("- Queue remaining: %d%n", crawler.getQueueSize());
        System.out.printf("- Visited URLs: %d%n", crawler.getVisitedCount());
        System.out.printf("- Results stored: %d%n", crawler.getResultCount());
    }
}
```

---

## 7. Performance Benchmarking

### 7.1 Throughput Comparison

```bash
# JMH Benchmark results (simplified)
Benchmark                          Mode  Cnt   Score   Error  Units
ArrayBlockingQueue.throughput     thrpt   25  15.234 ± 0.456  ops/us
LinkedBlockingQueue.throughput    thrpt   25  22.456 ± 0.678  ops/us
ConcurrentLinkedQueue.throughput  thrpt   25  45.678 ± 1.234  ops/us
```

**Key findings:**
- `ConcurrentLinkedQueue` (lock-free) có throughput cao nhất nhưng không hỗ trợ blocking
- `LinkedBlockingQueue` hai lock tốt hơn `ArrayBlockingQueue` một lock
- `CopyOnWriteArrayList` throughput cực thấp cho writes nhưng rất nhanh cho reads

### 7.2 Memory Footprint

| Collection | Empty | 10K Elements | Per-element Overhead |
|------------|-------|--------------|---------------------|
| ArrayBlockingQueue | ~1.2KB | ~42KB | 4 bytes (reference) |
| LinkedBlockingQueue | ~1.5KB | ~180KB | ~16 bytes (node object) |
| ConcurrentSkipListMap | ~2.1KB | ~320KB | ~28 bytes (node + levels) |
| CopyOnWriteArrayList | ~0.8KB | ~40KB | 4 bytes (nhưng copy trên write) |

---

## 📚 Tài liệu tham khảo

1. **Java Concurrency in Practice** - Brian Goetz (Chương 5)
2. **JEP 431: Sequenced Collections** - Java 21
3. **JEP 444: Virtual Threads** - Java 21
4. Doug Lea's `java.util.concurrent` package documentation
5. [JMH - Java Microbenchmark Harness](https://openjdk.org/projects/code-tools/jmh/)

---

## 🎯 Tóm tắt cho Senior Engineer

1. **Chọn BlockingQueue dựa trên use case:** 
   - Bounded buffer → ArrayBlockingQueue
   - High throughput producer-consumer → LinkedBlockingQueue
   - Direct handoff → SynchronousQueue
   - Priority scheduling → DelayQueue/PriorityBlockingQueue

2. **CopyOnWriteArrayList chỉ khi read >> write** (10:1 ratio hoặc cao hơn)

3. **ConcurrentSkipListMap cho sorted data + range queries**, không phải cho point lookups thuần túy

4. **Luôn giới hạn capacity** của LinkedBlockingQueue trong production

5. **Virtual Threads (Java 21)** thay đổi equation - blocking trở nên cheap, nhưng shared mutable state vẫn cần concurrent collections
