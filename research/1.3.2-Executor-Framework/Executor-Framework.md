# 1.3.2 Executor Framework: ThreadPoolExecutor, ForkJoinPool, CompletableFuture và Reactive Programming

> **Mục tiêu:** Thấu hiểu bản chất vận hành của các cơ chế xử lý bất đồng bộ trong Java, từ thread pool cơ bản đến reactive programming, phân tích trade-offs và áp dụng đúng ngữ cảnh production.

---

## 📋 Mục Lục

1. [Tổng Quan Executor Framework](#1-tổng-quan-executor-framework)
2. [ThreadPoolExecutor - Cơ Chế Tầng Thấp](#2-threadpoolexecutor---cơ-chế-tầng-thấp)
3. [ForkJoinPool - Divide và Conquer](#3-forkjoinpool---divide-và-conquer)
4. [CompletableFuture - Async Composition](#4-completablefuture---async-composition)
5. [Reactive Programming với Flow API](#5-reactive-programming-với-flow-api)
6. [Java 21+ Virtual Threads](#6-java-21-virtual-threads)
7. [So Sánh và Lựa Chọn](#7-so-sánh-và-lựa-chọn)
8. [Code Demo](#8-code-demo)
9. [Rủi Ro và Anti-patterns](#9-rủi-ro-và-anti-patterns)

---

## 1. Tổng Quan Executor Framework

### Bản Chất Thiết Kế

Executor Framework (Java 5+) tách biệt **task submission** khỏi **task execution**:

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Task Producer  │────▶│  Executor Queue │────▶│  Worker Threads │
│   (Business)    │     │  (BlockingQueue)│     │  (Thread Pool)  │
└─────────────────┘     └─────────────────┘     └─────────────────┘
```

**Lợi ích cốt lõi:**
- **Giảm overhead tạo thread:** Thread creation ~1ms + ~1MB stack → Thread reuse
- **Resource throttling:** Giới hạn concurrent tasks → tránh OOM
- **Decoupling:** Business logic không quan tâm đến thread management

### Class Hierarchy

```
Executor (interface)
    └── ExecutorService (interface)
            ├── AbstractExecutorService (abstract)
            │       └── ThreadPoolExecutor
            │       └── ScheduledThreadPoolExecutor
            └── ForkJoinPool
```

---

## 2. ThreadPoolExecutor - Cơ Chế Tầng Thấp

### 2.1 Kiến Trúc Bên Trong

```java
public class ThreadPoolExecutor extends AbstractExecutorService {
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
    private final BlockingQueue<Runnable> workQueue;
    private final HashSet<Worker> workers = new HashSet<>();
    // ...
}
```

**State Machine (Integer 32-bit packed):**
| Bits | Ý nghĩa |
|------|---------|
| 29 bits cao | Worker count (max: 2^29 - 1 ≈ 536M threads) |
| 3 bits thấp | Run state (RUNNING, SHUTDOWN, STOP, TIDYING, TERMINATED) |

### 2.2 Luồng Xử Lý Task

```
submit(task)
    │
    ▼
┌────────────────────────┐
│  Core pool chưa đầy?   │──Yes──▶ Tạo thread mới
└────────────────────────┘
    │ No
    ▼
┌────────────────────────┐
│  Queue còn chỗ?        │──Yes──▶ Đưa vào queue
└────────────────────────┘
    │ No
    ▼
┌────────────────────────┐
│  Max pool chưa đầy?    │──Yes──▶ Tạo thread non-core
└────────────────────────┘
    │ No
    ▼
┌────────────────────────┐
│  Rejection Policy      │──▶ Xử lý tùy theo policy
└────────────────────────┘
```

### 2.3 Các Tham Số Quan Trọng

| Tham số | Ý nghĩa | Best Practice |
|---------|---------|---------------|
| `corePoolSize` | Số thread duy trì tối thiểu | Đặt = max nếu load ổn định |
| `maximumPoolSize` | Số thread tối đa | `corePoolSize + queueSize` |
| `keepAliveTime` | Thờigian giữ thread dư | 60s cho async, 0s cho compute |
| `workQueue` | Hàng đợi tasks | ArrayBlockingQueue (bounded) |
| `rejectedExecutionHandler` | Xử lý overflow | CallerRunsPolicy (backpressure) |

### 2.4 Rejection Policies

```java
// 1. AbortPolicy (default) - Throw exception
new ThreadPoolExecutor.AbortPolicy(); // RejectedExecutionException

// 2. CallerRunsPolicy - Backpressure tự nhiên
new ThreadPoolExecutor.CallerRunsPolicy(); // Caller thread chạy task

// 3. DiscardPolicy - Lặng lẽ bỏ qua
new ThreadPoolExecutor.DiscardPolicy(); // ⚠️ Mất dữ liệu

// 4. DiscardOldestPolicy - Bỏ task cũ nhất
new ThreadPoolExecutor.DiscardOldestPolicy(); // ⚠️ Unfair
```

> **⚠️ Lưu ý quan trọng:** `CallerRunsPolicy` tạo backpressure tự nhiên - khi hệ thống quá tải, caller thread bị block, giảm tốc độ submit task.

### 2.5 Queue Types - Ảnh hưởng Performance

| Queue Type | Đặc điểm | Use Case |
|------------|----------|----------|
| `LinkedBlockingQueue` | Unbounded, FIFO | ⚠️ Nguy cơ OOM |
| `ArrayBlockingQueue` | Bounded, pre-allocated | Production preferred |
| `SynchronousQueue` | Zero-capacity, direct handoff | High-throughput, immediate execute |
| `PriorityBlockingQueue` | Priority-based | Scheduled tasks |

---

## 3. ForkJoinPool - Divide và Conquer

### 3.1 Work-Stealing Algorithm

```
┌─────────────────────────────────────────────┐
│            ForkJoinPool                     │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐     │
│  │Worker 0 │  │Worker 1 │  │Worker 2 │     │
│  │[T1][T2] │  │[T3]     │  │[]       │     │
│  │ deque   │  │ deque   │  │ deque   │     │
│  └────┬────┘  └────┬────┘  └────┬────┘     │
│       │            │            │          │
│       │ steal ◀────┼────────────┘          │
│       │            │                       │
│       └────────────┼───────────────────────┘
│                    │
│            LIFO (own) / FIFO (steal)
└─────────────────────────────────────────────┘
```

**Nguyên tắc:**
- Worker xử lý task của mình theo LIFO (stack)
- Khi rỗi, steal từ worker khác theo FIFO (queue) - giảm contention

### 3.2 RecursiveTask vs RecursiveAction

```java
// Có return value
class SumTask extends RecursiveTask<Long> {
    @Override
    protected Long compute() {
        if (threshold reached) return computeDirectly();
        SumTask left = new SumTask(...);
        SumTask right = new SumTask(...);
        left.fork();
        right.fork();
        return left.join() + right.join(); // Đợi kết quả
    }
}

// Không return value
class LogTask extends RecursiveAction {
    @Override
    protected void compute() { /* ... */ }
}
```

### 3.3 Common Pool

```java
// Java 8+ - shared instance
ForkJoinPool.commonPool();
// parallelism = Runtime.availableProcessors() - 1
```

> **⚠️ Cảnh báo:** Common pool được dùng bởi `parallelStream()`, `CompletableFuture.async()`. Đừng block nó!

---

## 4. CompletableFuture - Async Composition

### 4.1 Bản Chất Non-blocking

```java
// Thread A gọi supplyAsync
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
    // Chạy trên ForkJoinPool.commonPool() hoặc custom executor
    return fetchData();
});

// Thread A tiếp tục xử lý khác mà không đợi

// Callback khi có kết quả
future.thenApply(result -> process(result))  // Transform
      .thenAccept(result -> save(result))     // Consume
      .exceptionally(ex -> handle(ex));       // Error handling
```

### 4.2 Chaining Methods

| Method | Executor | Input | Output | Mục đích |
|--------|----------|-------|--------|----------|
| `thenApply` | Same thread | T | U | Transform |
| `thenApplyAsync` | Async | T | U | Transform async |
| `thenAccept` | Same thread | T | void | Consume |
| `thenRun` | Same thread | - | void | Side effect |
| `thenCompose` | Same thread | T | CompletionStage<U> | FlatMap |
| `whenComplete` | Same thread | T/Throwable | T | Logging/error |
| `handle` | Same thread | T/Throwable | U | Recovery |
| `exceptionally` | Same thread | Throwable | T | Fallback |

### 4.3 Combining Futures

```java
// Chạy song song, đợi cả 2
CompletableFuture.allOf(future1, future2, future3)
    .thenRun(() -> /* all done */);

// Chạy song song, lấy kết quả đầu tiên
CompletableFuture.anyOf(future1, future2)
    .thenAccept(result -> /* first done */);

// Combine 2 futures
future1.thenCombine(future2, (r1, r2) -> r1 + r2);
```

### 4.4 Timeout (Java 9+)

```java
future.orTimeout(5, TimeUnit.SECONDS)        // TimeoutException
      .exceptionally(ex -> fallbackValue);

future.completeOnTimeout(defaultValue, 5, TimeUnit.SECONDS);
```

---

## 5. Reactive Programming với Flow API

### 5.1 Reactive Streams Specification

```
Publisher ──subscribe()──▶ Subscriber
    │                          │
    │◀────request(n)───────────┤
    │                          │
    │────onNext(data)─────────▶│
    │────onNext(data)─────────▶│
    │────onComplete()─────────▶│
    │                          │
    │◀────cancel()─────────────┤
```

### 5.2 Flow API (Java 9+)

```java
// Publisher
SubmissionPublisher<String> publisher = new SubmissionPublisher<>();

// Subscriber
Flow.Subscriber<String> subscriber = new Flow.Subscriber<>() {
    private Flow.Subscription subscription;
    
    @Override
    public void onSubscribe(Flow.Subscription s) {
        this.subscription = s;
        s.request(1); // Backpressure: chỉ nhận 1 item
    }
    
    @Override
    public void onNext(String item) {
        process(item);
        subscription.request(1); // Request tiếp
    }
    // ... onError, onComplete
};

publisher.subscribe(subscriber);
```

### 5.3 So Sánh với Callback/Future

| Đặc điểm | Callback | Future | Reactive Streams |
|----------|----------|--------|------------------|
| Backpressure | ❌ Không | ❌ Không | ✅ Có |
| Composition | Khó | Trung bình | Dễ |
| Memory safety | ⚠️ Risk | ⚠️ Risk | ✅ Bounded |
| Multiple values | ❌ | ❌ | ✅ Stream |

---

## 6. Java 21+ Virtual Threads

### 6.1 Bản Chất Virtual Threads (Project Loom)

```
┌────────────────────────────────────────────┐
│           Virtual Threads (JVM)            │
│  VT1 ──┐                                   │
│  VT2 ──┼──▶ Carrier Thread Pool (OS)      │
│  VT3 ──┘    ┌─────┐┌─────┐┌─────┐        │
│  ...        │CT 1 ││CT 2 ││CT 3 │        │
│  VT1M ────▶ └─────┘└─────┘└─────┘        │
│             (Default = availableProcessors)│
└────────────────────────────────────────────┘
```

**Đặc điểm:**
- Virtual thread ~几百 bytes (vs OS thread ~1-2MB)
- Mất ~1μs để tạo (vs ~1ms)
- Auto-mounted/unmounted khi blocking

### 6.2 ExecutorService với Virtual Threads

```java
// Java 21+
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    IntStream.range(0, 1_000_000).forEach(i -> {
        executor.submit(() -> {
            Thread.sleep(Duration.ofSeconds(1));
            return i;
        });
    });
} // Auto-close, đợi tất cả tasks
```

### 6.3 Structured Concurrency (Java 21 Preview)

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Future<String> user = scope.fork(() -> fetchUser(id));
    Future<Integer> order = scope.fork(() -> fetchOrder(id));
    
    scope.join();           // Đợi cả 2
    scope.throwIfFailed();  // Ném exception nếu 1 trong 2 fail
    
    return new Response(user.resultNow(), order.resultNow());
}
```

---

## 7. So Sánh và Lựa Chọn

### 7.1 Decision Tree

```
Bạn cần xử lý concurrent?
    │
    ├── Có I/O blocking (HTTP, DB, File)?
    │       ├── Java 21+ ──▶ Virtual Thread Executor
    │       └── Java <21 ──▶ CachedThreadPool (bounded)
    │
    ├── CPU-intensive (compute, transform)?
    │       └── FixedThreadPool (cores x 2)
    │
    ├── Recursive/Divide & Conquer?
    │       └── ForkJoinPool
    │
    ├── Nhiều async tasks cần compose?
    │       └── CompletableFuture + custom executor
    │
    └── Stream lớn cần backpressure?
            └── Reactive Streams (Flow API / RxJava / Project Reactor)
```

### 7.2 Performance Characteristics

| Pattern | Throughput | Latency | Memory | Use Case |
|---------|------------|---------|--------|----------|
| ThreadPoolExecutor | Cao | Thấp | Trung bình | General purpose |
| ForkJoinPool | Rất cao (compute) | Thấp | Thấp | Parallel streams |
| CompletableFuture | Cao | Thấp | Trung bình | Async composition |
| Virtual Threads | Rất cao (I/O) | Thấp | Rất thấp | Million concurrent I/O |
| Reactive Streams | Cao | Thấp | Kiểm soát | Streaming data |

---

## 8. Code Demo

### 8.1 ThreadPoolExecutor Production-Ready

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ProductionThreadPool {
    
    public static ThreadPoolExecutor createExecutor() {
        int corePoolSize = 10;
        int maxPoolSize = 50;
        long keepAliveTime = 60L;
        
        // Bounded queue - tránh OOM
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(1000);
        
        // Named threads để dễ debug
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "worker-" + counter.incrementAndGet());
                t.setUncaughtExceptionHandler((thread, ex) -> {
                    System.err.println("Uncaught in " + thread.getName() + ": " + ex);
                });
                return t;
            }
        };
        
        // Backpressure policy
        RejectedExecutionHandler rejectionHandler = new ThreadPoolExecutor.CallerRunsPolicy();
        
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            corePoolSize,
            maxPoolSize,
            keepAliveTime,
            TimeUnit.SECONDS,
            queue,
            threadFactory,
            rejectionHandler
        );
        
        // Prestart core threads
        executor.prestartAllCoreThreads();
        
        // Monitoring hook
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
        monitor.scheduleAtFixedRate(() -> {
            System.out.printf("Pool: %d/%d, Active: %d, Queue: %d/%d, Completed: %d%n",
                executor.getPoolSize(),
                executor.getMaximumPoolSize(),
                executor.getActiveCount(),
                executor.getQueue().size(),
                ((ArrayBlockingQueue<?>) executor.getQueue()).remainingCapacity() 
                    + executor.getQueue().size(),
                executor.getCompletedTaskCount()
            );
        }, 0, 5, TimeUnit.SECONDS);
        
        return executor;
    }
    
    public static void main(String[] args) throws Exception {
        ThreadPoolExecutor executor = createExecutor();
        
        // Submit tasks
        for (int i = 0; i < 100; i++) {
            final int taskId = i;
            executor.submit(() -> {
                try {
                    Thread.sleep(100); // Simulate work
                    System.out.println("Task " + taskId + " completed by " + Thread.currentThread().getName());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);
    }
}
```

### 8.2 CompletableFuture Chain Pattern

```java
import java.util.concurrent.*;
import java.net.http.*;
import java.net.URI;

public class AsyncOrderProcessing {
    
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private final ExecutorService executor = Executors.newFixedThreadPool(20);
    
    public CompletableFuture<OrderResult> processOrder(String orderId) {
        return fetchOrder(orderId)
            .thenComposeAsync(order -> validateStock(order), executor)
            .thenComposeAsync(validated -> processPayment(validated), executor)
            .thenApplyAsync(paid -> createShipment(paid), executor)
            .orTimeout(30, TimeUnit.SECONDS)  // Java 9+
            .exceptionally(ex -> {
                // Log và trả về fallback
                System.err.println("Order failed: " + ex.getMessage());
                return OrderResult.failed(orderId, ex);
            });
    }
    
    private CompletableFuture<Order> fetchOrder(String orderId) {
        return CompletableFuture.supplyAsync(() -> {
            // Simulate API call
            try {
                Thread.sleep(50);
                return new Order(orderId, "PRODUCT-123", 100.0);
            } catch (InterruptedException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    private CompletableFuture<Order> validateStock(Order order) {
        return CompletableFuture.supplyAsync(() -> {
            if (order.amount > 1000) {
                throw new IllegalStateException("Insufficient stock");
            }
            return order;
        }, executor);
    }
    
    private CompletableFuture<Order> processPayment(Order order) {
        return CompletableFuture.supplyAsync(() -> {
            // Payment logic
            return order;
        }, executor);
    }
    
    private OrderResult createShipment(Order order) {
        return new OrderResult(order.id, "SHIPPED", System.currentTimeMillis());
    }
    
    record Order(String id, String productId, double amount) {}
    record OrderResult(String orderId, String status, long timestamp) {
        static OrderResult failed(String id, Throwable ex) {
            return new OrderResult(id, "FAILED: " + ex.getMessage(), 0);
        }
    }
    
    public static void main(String[] args) throws Exception {
        AsyncOrderProcessing service = new AsyncOrderProcessing();
        
        // Process multiple orders concurrently
        CompletableFuture<?>[] futures = IntStream.range(0, 100)
            .mapToObj(i -> service.processOrder("ORDER-" + i))
            .map(f -> f.thenAccept(System.out::println))
            .toArray(CompletableFuture[]::new);
        
        CompletableFuture.allOf(futures).join();
        service.executor.shutdown();
    }
}
```

### 8.3 ForkJoinPool Parallel Processing

```java
import java.util.concurrent.*;
import java.util.*;

public class ParallelDataProcessing {
    
    static class SumTask extends RecursiveTask<Long> {
        private static final int THRESHOLD = 10_000;
        private final int[] data;
        private final int start, end;
        
        SumTask(int[] data, int start, int end) {
            this.data = data;
            this.start = start;
            this.end = end;
        }
        
        @Override
        protected Long compute() {
            if (end - start <= THRESHOLD) {
                // Compute directly
                long sum = 0;
                for (int i = start; i < end; i++) {
                    sum += data[i];
                }
                return sum;
            }
            
            // Split
            int mid = start + (end - start) / 2;
            SumTask left = new SumTask(data, start, mid);
            SumTask right = new SumTask(data, mid, end);
            
            left.fork();
            long rightResult = right.compute();
            long leftResult = left.join();
            
            return leftResult + rightResult;
        }
    }
    
    public static void main(String[] args) {
        int[] data = new int[100_000_000];
        Random random = new Random();
        for (int i = 0; i < data.length; i++) {
            data[i] = random.nextInt(100);
        }
        
        // Using ForkJoinPool
        ForkJoinPool pool = new ForkJoinPool();
        long start = System.currentTimeMillis();
        long result = pool.invoke(new SumTask(data, 0, data.length));
        long duration = System.currentTimeMillis() - start;
        
        System.out.println("Sum: " + result);
        System.out.println("Time: " + duration + "ms");
        System.out.println("Pool size: " + pool.getPoolSize());
        
        pool.shutdown();
    }
}
```

### 8.4 Virtual Threads (Java 21+)

```java
import java.time.Duration;
import java.util.concurrent.*;
import java.util.stream.IntStream;

public class VirtualThreadExample {
    
    public static void main(String[] args) throws Exception {
        // Traditional threads - sẽ crash với 100k threads
        // testWithPlatformThreads();
        
        // Virtual threads - handle 100k+ dễ dàng
        testWithVirtualThreads();
    }
    
    static void testWithVirtualThreads() throws Exception {
        long start = System.currentTimeMillis();
        
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, 100_000)
                .mapToObj(i -> executor.submit(() -> {
                    Thread.sleep(Duration.ofMillis(100));
                    return i;
                }))
                .toList();
            
            // Wait for all
            for (var future : futures) {
                future.get();
            }
        }
        
        long duration = System.currentTimeMillis() - start;
        System.out.println("100k virtual threads completed in " + duration + "ms");
    }
    
    static void testWithPlatformThreads() throws Exception {
        long start = System.currentTimeMillis();
        
        try (var executor = Executors.newCachedThreadPool()) {
            var futures = IntStream.range(0, 10_000)
                .mapToObj(i -> executor.submit(() -> {
                    Thread.sleep(Duration.ofMillis(100));
                    return i;
                }))
                .toList();
            
            for (var future : futures) {
                future.get();
            }
        }
        
        long duration = System.currentTimeMillis() - start;
        System.out.println("10k platform threads completed in " + duration + "ms");
    }
}
```

---

## 9. Rủi Ro và Anti-patterns

### 9.1 Deadlock trong Thread Pool

```java
// ❌ ANTI-PATTERN: Nested executor usage
task1.submit(() -> {
    // Đang chiếm 1 thread
    task2.submit(() -> {
        // Đợi thread khác... nhưng pool có thể đã hết!
    }).join();
});

// ✅ FIX: Dùng cùng executor hoặc increase pool size
```

### 9.2 Blocking Common ForkJoinPool

```java
// ❌ ANTI-PATTERN: Blocking trong parallel stream
List<Data> result = dataList.parallelStream()
    .map(d -> blockingHttpCall(d)) // Block common pool!
    .collect(Collectors.toList());

// ✅ FIX: Dùng CompletableFuture với custom executor
List<CompletableFuture<Data>> futures = dataList.stream()
    .map(d -> CompletableFuture.supplyAsync(
        () -> blockingHttpCall(d), 
        customExecutor
    ))
    .toList();
```

### 9.3 Silent Failures

```java
// ❌ ANTI-PATTERN: Không handle exception
executor.submit(() -> {
    riskyOperation(); // Exception bị nuốt!
});

// ✅ FIX: Always handle exceptions
executor.submit(() -> {
    try {
        riskyOperation();
    } catch (Exception e) {
        logger.error("Task failed", e);
        throw e; // Re-throw để rejection handler biết
    }
});

// Hoặc với CompletableFuture
CompletableFuture.runAsync(() -> riskyOperation())
    .exceptionally(ex -> {
        logger.error("Failed", ex);
        return null;
    });
```

### 9.4 Memory Leak trong ThreadLocal

```java
// ❌ ANTI-PATTERN: ThreadLocal với thread pool
private static final ThreadLocal<SimpleDateFormat> sdf = 
    ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

// Thread pool reuse thread → ThreadLocal không được clean

// ✅ FIX: Dùng DateTimeFormatter (immutable) hoặc remove()
try {
    sdf.get().parse(date);
} finally {
    sdf.remove(); // Clean up
}
```

### 9.5 Unbounded Queue

```java
// ❌ ANTI-PATTERN: LinkedBlockingQueue() - unbounded
new ThreadPoolExecutor(10, 10, 0L, TimeUnit.MILLISECONDS, 
    new LinkedBlockingQueue<>()); // OOM risk!

// ✅ FIX: Always bound the queue
new ThreadPoolExecutor(10, 50, 60L, TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(1000),
    new ThreadPoolExecutor.CallerRunsPolicy());
```

### 9.6 Tóm Tắt Risk Matrix

| Risk | Severity | Detection | Prevention |
|------|----------|-----------|------------|
| Thread exhaustion | 🔴 High | Thread dump | Bounded pools, CallerRunsPolicy |
| Memory leak (ThreadLocal) | 🔴 High | Heap dump | Clean up, avoid ThreadLocal |
| Deadlock | 🔴 High | Thread dump | Timeout, avoid nested submits |
| Silent failures | 🟡 Medium | Logging | Always attach exception handlers |
| Context switching overhead | 🟡 Medium | Profiling | Right pool size |
| Unbounded queue growth | 🔴 High | Monitoring | ArrayBlockingQueue |

---

## 📚 Tài Liệu Tham Khảo

1. **Java Concurrency in Practice** - Brian Goetz (Bible)
2. **Project Loom** - OpenJDK (Virtual Threads)
3. **Reactive Streams Specification** - reactive-streams.org
4. **Java 21 Documentation** - docs.oracle.com

---

> **💡 Senior Takeaway:** Executor Framework là công cụ mạnh nhưng cần hiểu rõ trade-offs. Java 21+ Virtual Threads thay đổi game hoàn toàn cho I/O-bound applications - đơn giản hóa code mà vẫn giữ hiệu năng cao. Tuy nhiên, cho compute-intensive tasks, traditional thread pools vẫn phù hợp hơn.
