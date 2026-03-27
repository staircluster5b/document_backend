# 1.4.3 Asynchronous I/O: AsynchronousFileChannel, Callback vs Future-based APIs

> **Mục tiêu:** Thấu hiểu bản chất AIO trong Java, phân tích sâu cơ chế hoạt động của `AsynchronousFileChannel`, đánh giá trade-offs giữa callback và future-based patterns, và áp dụng đúng trong production.

---

## 📋 Mục Lục

1. [Mục Tiêu Nghiên Cứu](#1-mục-tiêu-nghiên-cứu)
2. [Bản Chất AIO trong Java](#2-bản-chất-aio-trong-java)
3. [AsynchronousFileChannel - Cơ Chế Tầng Thấp](#3-asynchronousfilechannel---cơ-chế-tầng-thấp)
4. [CompletionHandler (Callback-based)](#4-completionhandler-callback-based)
5. [Future-based API](#5-future-based-api)
6. [So Sánh Callback vs Future](#6-so-sánh-callback-vs-future)
7. [Thread Pool và Resource Management](#7-thread-pool-và-resource-management)
8. [Rủi Ro và Anti-patterns](#8-rủi-ro-và-anti-patterns)
9. [Khuyến Nghị Production](#9-khuyến-nghị-production)
10. [Kết Luận](#10-kết-luận)

---

## 1. Mục Tiêu Nghiên Cứu

**Vấn đề cần giải quyết:**

I/O operations (đọc/ghi file, network) là blocking theo mặc định - thread phải đợi kernel hoàn thành operation. Với hệ thống cần xử lý hàng nghìn concurrent I/O operations, mô hình blocking dẫn đến:
- Thread exhaustion (mỗi I/O cần 1 thread)
- Context switching overhead
- Memory overhead (mỗi thread ~1MB stack)

**AIO (Asynchronous I/O) Java NIO.2 (Java 7+) giải quyết bằng cách:**
- Thread gọi I/O không đợi (non-blocking)
- Kernel thông báo khi I/O hoàn thành
- Callback/Future nhận kết quả

---

## 2. Bản Chất AIO trong Java

### 2.1 Kiến Trúc Tổng Quan

```
┌─────────────────────────────────────────────────────────────┐
│                     Application Layer                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Thread 1   │  │   Thread 2   │  │   Thread N   │      │
│  │  (business)  │  │  (business)  │  │  (business)  │      │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘      │
└─────────┼─────────────────┼─────────────────┼───────────────┘
          │                 │                 │
          ▼                 ▼                 ▼
┌─────────────────────────────────────────────────────────────┐
│                   AsynchronousChannelGroup                  │
│              (ThreadPoolExecutor / custom)                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  I/O Thread Pool (handles completion callbacks)     │   │
│  │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐       │   │
│  │  │Worker 1│ │Worker 2│ │Worker 3│ │Worker n│       │   │
│  │  └────────┘ └────────┘ └────────┘ └────────┘       │   │
│  └─────────────────────────────────────────────────────┘   │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼ JNI / Native
┌─────────────────────────────────────────────────────────────┐
│                    Operating System                         │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  Async I/O Subsystem (io_uring, kqueue, IOCP)       │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │   │
│  │  │I/O Request 1│  │I/O Request 2│  │I/O Request N│ │   │
│  │  │  (pending)  │  │  (pending)  │  │  (pending)  │ │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘ │   │
│  └─────────────────────────────────────────────────────┘   │
│                         │                                   │
│                         ▼ Hardware Interrupt                │
│                    Disk/Network Controller                  │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Native Implementation Mapping

| OS | Native API | Java Binding |
|----|------------|--------------|
| Linux | io_uring (kernel 5.1+), epoll | Native POSIX Thread Library (NPTL) |
| macOS | kqueue | BSD layer |
| Windows | I/O Completion Ports (IOCP) | Windows API |

> **⚠️ Quan trọng:** Java AIO implementation sử dụng native async I/O nếu OS hỗ trợ. Nếu không, nó fallback sang simulated async bằng thread pool - **KHÔNG phải true async**.

### 2.3 Sự Khác Biệt: NIO (Selector) vs AIO

```
NIO (Selector-based):
┌──────────┐     ┌─────────┐     ┌────────────┐
│ Selector │────▶│ Channel │────▶│ Check ready│
│  (poll)  │◀────│   set   │◀────│  (non-blk) │
└──────────┘     └─────────┘     └────────────┘
   │
   ▼ (when ready)
Read/Write (still needs thread)

AIO (Completion-based):
┌──────────┐     ┌─────────────┐
│ Submit   │────▶│ Kernel queue│
│ I/O req  │     │ (no thread) │
└──────────┘     └─────────────┘
                      │
                      ▼ (interrupt when done)
                ┌─────────────┐
                │ Completion  │
                │ Handler     │
                └─────────────┘
```

| Đặc điểm | NIO (Selector) | AIO (CompletionHandler) |
|----------|----------------|-------------------------|
| Thread model | 1 thread monitors many channels | Thread only on completion |
| Readiness check | Application polls | OS notifies |
| I/O operation | Non-blocking but immediate | Truly async, deferred |
| Complexity | Moderate (state machine) | Higher (callback hell) |
| Scalability | 10K+ connections | 100K+ operations |
| Use case | Network servers (Netty) | File I/O, high-volume async |

---

## 3. AsynchronousFileChannel - Cơ Chế Tầng Thấp

### 3.1 Internal Architecture

```java
public abstract class AsynchronousFileChannel 
    implements AsynchronousChannel {
    
    // Core components:
    // 1. FileDescriptor (native handle)
    // 2. AsynchronousChannelGroup (thread pool)
    // 3. Pending I/O queue
    // 4. Completion dispatch mechanism
}
```

**State Machine:**

```
┌──────────┐   open()    ┌──────────┐
│  CLOSED  │◀────────────│   OPEN   │
└──────────┘  close()    └────┬─────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
        ▼                     ▼                     ▼
   ┌─────────┐          ┌─────────┐          ┌─────────┐
   │ READING │          │ WRITING │          │ LOCKING │
   │(pending)│          │(pending)│          │(pending)│
   └────┬────┘          └────┬────┘          └────┬────┘
        │                     │                     │
        ▼                     ▼                     ▼
   ┌─────────┐          ┌─────────┐          ┌─────────┐
   │COMPLETED│          │COMPLETED│          │COMPLETED│
   │ callback│          │ callback│          │ callback│
   └─────────┘          └─────────┘          └─────────┘
```

### 3.2 Creation Patterns

```java
// Pattern 1: Default thread pool (ForkJoinPool.commonPool())
AsynchronousFileChannel channel = AsynchronousFileChannel.open(
    Paths.get("data.txt"),
    StandardOpenOption.READ
);

// Pattern 2: Custom AsynchronousChannelGroup
ExecutorService executor = Executors.newFixedThreadPool(4);
AsynchronousChannelGroup group = AsynchronousChannelGroup.withThreadPool(executor);

AsynchronousFileChannel channel = AsynchronousFileChannel.open(
    Paths.get("data.txt"),
    Set.of(StandardOpenOption.READ),
    group,
    PosixFilePermissions.asFileAttribute(
        PosixFilePermissions.fromString("rw-r--r--")
    )
);

// Pattern 3: Cached thread pool (auto-grow/shrink)
AsynchronousChannelGroup cachedGroup = AsynchronousChannelGroup.withCachedThreadPool(
    Executors.defaultThreadFactory(),
    10  // initial size
);
```

### 3.3 ByteBuffer Lifecycle trong AIO

```
┌────────────────────────────────────────────────────────────┐
│                    ByteBuffer States                       │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  APP                        CHANNEL                     OS  │
│   │                            │                        │   │
│   │ 1. allocate()              │                        │   │
│   ▼                            │                        │   │
│ ┌─────────┐                    │                        │   │
│ │  EMPTY  │                    │                        │   │
│ │position │                    │                        │   │
│ │  = 0    │                    │                        │   │
│ └────┬────┘                    │                        │   │
│      │                         │                        │   │
│      │ 2. put() / read()       │                        │   │
│      ▼                         │                        │   │
│ ┌─────────┐   3. flip()   ┌─────────┐   4. write()   ┌────▼───┐
│ │  DATA   │──────────────▶│  READY  │───────────────▶│ KERNEL │
│ │ (fill)  │               │ (limit= │                │ QUEUE  │
│ └─────────┘               │  position,               └────────┘
│                           │ position=0)                    │
│                           └─────────┘                        │
│                                                           │
│  ┌─────────┐   6. clear()  ┌─────────┐   5. completion   │
│  │  EMPTY  │◀──────────────│  DONE   │◀──────────────────┘
│  │ (reuse) │               │callback │
│  └─────────┘               └─────────┘
│
└────────────────────────────────────────────────────────────┘
```

> **⚠️ Critical:** Buffer phải ở trạng thái READY (flip()) trước khi write. Nếu không, position=limit=0 → zero bytes written.

---

## 4. CompletionHandler (Callback-based)

### 4.1 Bản Chất Callback Pattern

```
┌─────────────────────────────────────────────────────────────┐
│                    Callback Flow                            │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Main Thread              AsynchronousChannelGroup         │
│       │                           │                         │
│       │ 1. read(buffer, 0, null,  │                         │
│       │    new CompletionHandler())│                         │
│       ├──────────────────────────▶│                         │
│       │                           │ 2. Submit to kernel     │
│       │                           │    (non-blocking)       │
│       │                           │                         │
│       │ 3. Continue business      │                         │
│       │    logic immediately      │                         │
│       │                           │                         │
│       │                           │ 4. Kernel completes I/O │
│       │                           │    (interrupt-driven)   │
│       │                           │                         │
│       │                           │ 5. Dispatch to worker   │
│       │                           │    thread               │
│       │                           │                         │
│       │                           │ 6. completed() callback │
│       │                           │    ┌─────────────────┐  │
│       │                           └───▶│  ATTACHMENT     │  │
│       │                                │  (context data) │  │
│       │                                └─────────────────┘  │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 CompletionHandler Interface Deep Dive

```java
public interface CompletionHandler<V, A> {
    /**
     * Invoked when operation completes successfully.
     * V = result type (Integer for read/write = bytes transferred)
     * A = attachment type (context object passed through)
     */
    void completed(V result, A attachment);
    
    /**
     * Invoked when operation fails.
     * Exception delivered here, NOT thrown to caller.
     */
    void failed(Throwable exc, A attachment);
}
```

**Generic Type Parameters:**

| Type | Meaning | Example |
|------|---------|---------|
| `V` | Result of operation | `Integer` (bytes read/written) |
| `A` | Attachment/Context | Custom context object |

### 4.3 Chained I/O Pattern (Read → Process → Write)

```java
public class AsyncIOChain {
    
    private final AsynchronousFileChannel source;
    private final AsynchronousFileChannel destination;
    
    // Context object để maintain state across callbacks
    private static class IOContext {
        final ByteBuffer buffer;
        final long position;
        final int expectedBytes;
        
        IOContext(ByteBuffer buffer, long position, int expected) {
            this.buffer = buffer;
            this.position = position;
            this.expectedBytes = expected;
        }
    }
    
    public void copyChunk(long position, int size) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(size);
        
        // STEP 1: Read
        source.read(buffer, position, 
            new IOContext(buffer, position, size),
            new CompletionHandler<Integer, IOContext>() {
                
                @Override
                public void completed(Integer bytesRead, IOContext ctx) {
                    if (bytesRead == -1) {
                        return; // EOF
                    }
                    
                    // Prepare buffer for write
                    ctx.buffer.flip();
                    
                    // STEP 2: Write
                    destination.write(ctx.buffer, ctx.position, ctx,
                        new CompletionHandler<Integer, IOContext>() {
                            
                            @Override
                            public void completed(Integer bytesWritten, IOContext ctx) {
                                if (bytesWritten < ctx.buffer.remaining()) {
                                    // Partial write - retry with adjusted position
                                    ctx.buffer.compact();
                                    // ... retry logic
                                }
                                // Write complete
                            }
                            
                            @Override
                            public void failed(Throwable exc, IOContext ctx) {
                                handleWriteError(exc, ctx);
                            }
                        }
                    );
                }
                
                @Override
                public void failed(Throwable exc, IOContext ctx) {
                    handleReadError(exc, ctx);
                }
            }
        );
    }
}
```

> **🚨 Anti-pattern alert:** Nested callbacks = "Callback Hell". Với Java 8+, dùng CompletableFuture wrapper.

### 4.4 Error Handling trong Callback

```java
public class RobustCompletionHandler implements CompletionHandler<Integer, Attachment> {
    
    private static final Logger logger = LoggerFactory.getLogger(...);
    private final RetryPolicy retryPolicy;
    private final int maxRetries;
    
    @Override
    public void completed(Integer result, Attachment attachment) {
        try {
            if (result < 0) {
                handleEOF(attachment);
                return;
            }
            
            if (result < attachment.expectedBytes) {
                // Partial I/O - cần continue
                handlePartialIO(result, attachment);
                return;
            }
            
            processSuccess(result, attachment);
            
        } catch (Exception e) {
            // Callback exceptions KHÔNG throw - phải catch tại đây
            logger.error("Unexpected error in completion callback", e);
            cleanup(attachment);
        }
    }
    
    @Override
    public void failed(Throwable exc, Attachment attachment) {
        // Phân loại exception
        if (exc instanceof AsynchronousCloseException) {
            logger.warn("Channel closed during operation");
            cleanup(attachment);
        } else if (exc instanceof ClosedByInterruptException) {
            logger.warn("Operation interrupted");
            Thread.currentThread().interrupt();
            cleanup(attachment);
        } else if (exc instanceof IOException && attachment.retryCount < maxRetries) {
            // Retryable error
            scheduleRetry(attachment);
        } else {
            // Non-retryable error
            logger.error("Fatal I/O error", exc);
            failPermanently(exc, attachment);
        }
    }
}
```

---

## 5. Future-based API

### 5.1 Bản Chất Future Pattern

```
┌─────────────────────────────────────────────────────────────┐
│                   Future-based Flow                         │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Main Thread              AsynchronousChannelGroup         │
│       │                           │                         │
│       │ 1. Future<Integer> future │                         │
│       │    = channel.read(...)    │                         │
│       ├──────────────────────────▶│                         │
│       │                           │ 2. Submit to kernel     │
│       │◀──────────────────────────┤                         │
│       │  (returns immediately     │                         │
│       │   with Future object)     │                         │
│       │                           │                         │
│       │ 3. Option A: future.get() │                         │
│       │    (BLOCKING - wait)      │                         │
│       │                           │                         │
│       │ 4. Option B: future.get(  │                         │
│       │    timeout, unit)         │                         │
│       │    (BLOCKING with timeout)│                         │
│       │                           │                         │
│       │ 5. Option C: polling      │                         │
│       │    while(!future.isDone())│                         │
│       │      { doOtherWork() }    │                         │
│       │                           │                         │
└─────────────────────────────────────────────────────────────┘
```

### 5.2 Future API Usage Patterns

```java
public class FutureBasedAIO {
    
    /**
     * Pattern 1: Blocking wait - defeats async purpose
     * Chỉ dùng khi cần synchronous behavior từ async API
     */
    public byte[] readFullyBlocking(Path path) throws Exception {
        try (AsynchronousFileChannel channel = AsynchronousFileChannel.open(path)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
            Future<Integer> future = channel.read(buffer, 0);
            
            // BLOCKS here until I/O completes
            Integer bytesRead = future.get();
            
            buffer.flip();
            byte[] data = new byte[bytesRead];
            buffer.get(data);
            return data;
        }
    }
    
    /**
     * Pattern 2: Timeout-based wait
     */
    public Optional<ByteBuffer> readWithTimeout(Path path, long timeoutMs) {
        try (AsynchronousFileChannel channel = AsynchronousFileChannel.open(path)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            Future<Integer> future = channel.read(buffer, 0);
            
            try {
                Integer result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                buffer.flip();
                return Optional.of(buffer);
            } catch (TimeoutException e) {
                future.cancel(true); // Interrupt if still pending
                return Optional.empty();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * Pattern 3: Polling (non-blocking check)
     */
    public void readWithPolling(Path path) throws Exception {
        try (AsynchronousFileChannel channel = AsynchronousFileChannel.open(path)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            Future<Integer> future = channel.read(buffer, 0);
            
            // Do other work while waiting
            while (!future.isDone()) {
                // Process other tasks, check progress
                Thread.sleep(10); // Avoid busy-waiting
            }
            
            // Check if cancelled or failed
            if (future.isCancelled()) {
                throw new InterruptedException("Operation cancelled");
            }
            
            Integer result = future.get(); // Won't block now
            processResult(buffer, result);
        }
    }
}
```

### 5.3 Future Limitations trong AIO

```java
// ❌ PROBLEM: Future doesn't support chaining
Future<Integer> readFuture = channel.read(buffer1, 0);
Integer bytesRead = readFuture.get(); // Block

Future<Integer> writeFuture = channel.write(buffer1, 0); // Must wait for read

// ❌ PROBLEM: No callback on completion (must poll or block)
// ❌ PROBLEM: Exception handling is clunky

try {
    future.get();
} catch (ExecutionException e) {
    // Actual exception wrapped
    Throwable actual = e.getCause();
}
```

> **💡 Insight:** Future-based AIO là "half-async" - submit async nhưng consume blocking. Chỉ phù hợp khi cần kết hợp với legacy synchronous code.

---

## 6. So Sánh Callback vs Future

### 6.1 Decision Matrix

| Tiêu chí | CompletionHandler | Future |
|----------|-------------------|--------|
| **Thread blocking** | Không bao giờ block | Block tại `get()` |
| **Composition** | Khó (nested callbacks) | Không hỗ trợ native |
| **Error handling** | Tách biệt (`failed()` method) | Wrapped trong `ExecutionException` |
| **Cancellation** | Không hỗ trợ trực tiếp | `cancel()` method |
| **Chaining** | Manual (callback hell) | Không |
| **Timeout** | Không built-in | `get(timeout, unit)` |
| **Backpressure** | Dễ control | Khó |
| **Resource cleanup** | Trong callback | Sau `get()` returns |

### 6.2 Performance Comparison

```
Scenario: 10,000 concurrent file reads

Callback-based:
┌────────────────────────────────────────┐
│ Peak memory:     ~50MB (buffers only)  │
│ Active threads:  4 (group pool size)   │
│ Latency (p99):   12ms                  │
│ Throughput:      8,500 ops/sec         │
└────────────────────────────────────────┘

Future-based (blocking get):
┌────────────────────────────────────────┐
│ Peak memory:     ~2GB (10K buffers)    │
│ Active threads:  10,000 (1 per op)     │
│ Latency (p99):   2,400ms (queuing)     │
│ Throughput:      1,200 ops/sec         │
└────────────────────────────────────────┘
```

### 6.3 Khi Nào Dùng Gì

```
Bạn cần AIO?
    │
    ├── Cần cancel operations?
    │   └── Future-based
    │
    ├── Cần timeout built-in?
    │   └── Future-based (hoặc wrapper)
    │
    ├── Cần high throughput, many operations?
    │   └── CompletionHandler (non-blocking)
    │
    ├── Cần integrate với synchronous code?
    │   └── Future-based
    │
    └── Cần complex chaining, composition?
        └── CompletionHandler + CompletableFuture wrapper
```

### 6.4 Best of Both: CompletableFuture Bridge

```java
public class AIOCompletableFuture {
    
    /**
     * Bridge CompletionHandler sang CompletableFuture
     * Kết hợp non-blocking AIO với composable async
     */
    public static <V> CompletableFuture<V> toCompletableFuture(
            BiConsumer<CompletionHandler<V, Void>, Void> aioOperation) {
        
        CompletableFuture<V> future = new CompletableFuture<>();
        
        aioOperation.accept(new CompletionHandler<V, Void>() {
            @Override
            public void completed(V result, Void attachment) {
                future.complete(result);
            }
            
            @Override
            public void failed(Throwable exc, Void attachment) {
                future.completeExceptionally(exc);
            }
        }, null);
        
        return future;
    }
    
    // Usage:
    public CompletableFuture<byte[]> asyncReadFile(Path path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return AsynchronousFileChannel.open(path);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).thenCompose(channel -> {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            
            return toCompletableFuture((handler, att) -> {
                channel.read(buffer, 0, att, handler);
            }).thenApply(bytesRead -> {
                buffer.flip();
                byte[] data = new byte[bytesRead];
                buffer.get(data);
                return data;
            }).whenComplete((result, error) -> {
                try {
                    channel.close();
                } catch (IOException ignored) {}
            });
        });
    }
    
    // Chaining operations:
    public CompletableFuture<Void> copyFileAsync(Path source, Path dest) {
        return asyncReadFile(source)
            .thenCompose(data -> asyncWriteFile(dest, data))
            .thenApply(bytesWritten -> {
                System.out.println("Copied " + bytesWritten + " bytes");
                return null;
            })
            .exceptionally(error -> {
                System.err.println("Copy failed: " + error.getMessage());
                return null;
            });
    }
}
```

---

## 7. Thread Pool và Resource Management

### 7.1 AsynchronousChannelGroup Types

```java
// Type 1: Fixed thread pool
ExecutorService fixedExecutor = Executors.newFixedThreadPool(
    Runtime.getRuntime().availableProcessors(),
    new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger();
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "aio-worker-" + count.incrementAndGet());
            t.setDaemon(true); // Don't prevent JVM exit
            return t;
        }
    }
);

AsynchronousChannelGroup fixedGroup = 
    AsynchronousChannelGroup.withThreadPool(fixedExecutor);

// Type 2: Cached thread pool (auto-scale)
AsynchronousChannelGroup cachedGroup = 
    AsynchronousChannelGroup.withCachedThreadPool(
        Executors.defaultThreadFactory(),
        10  // initial threads
    );

// Type 3: Fixed thread pool with custom thread factory
ThreadFactory aioThreadFactory = r -> {
    Thread t = new Thread(r);
    t.setName("aio-handler-" + t.getId());
    t.setUncaughtExceptionHandler((thread, ex) -> {
        LoggerFactory.getLogger("AIO").error(
            "Uncaught exception in " + thread.getName(), ex
        );
    });
    return t;
};

ExecutorService executor = Executors.newFixedThreadPool(4, aioThreadFactory);
```

### 7.2 Thread Pool Sizing

| Workload Type | Pool Size | Lý do |
|---------------|-----------|-------|
| CPU-bound | cores | Không cần nhiều thread hơn cores |
| I/O-bound | cores * 2-4 | Handle completion callbacks nhanh |
| Mixed | cores + 2 | Balance giữa compute và I/O |

### 7.3 Resource Lifecycle Management

```java
public class ManagedAIOChannel implements AutoCloseable {
    
    private final AsynchronousFileChannel channel;
    private final AsynchronousChannelGroup group;
    private final boolean ownsGroup;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    public ManagedAIOChannel(Path path, OpenOption... options) throws IOException {
        // Use default group
        this.channel = AsynchronousFileChannel.open(path, options);
        this.group = null;
        this.ownsGroup = false;
    }
    
    public ManagedAIOChannel(Path path, int poolSize, OpenOption... options) 
            throws IOException {
        this.ownsGroup = true;
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        this.group = AsynchronousChannelGroup.withThreadPool(executor);
        this.channel = AsynchronousFileChannel.open(path, 
            Set.of(options), group, null);
    }
    
    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            // 1. Close channel first (wait for pending ops)
            channel.close();
            
            // 2. Shutdown group if we own it
            if (ownsGroup && group != null) {
                group.shutdown();
                try {
                    if (!group.awaitTermination(30, TimeUnit.SECONDS)) {
                        group.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    group.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
```

---

## 8. Rủi Ro và Anti-patterns

### 8.1 Callback Blocking

```java
// ❌ ANTI-PATTERN: Blocking trong callback
channel.read(buffer, 0, null, new CompletionHandler<>() {
    @Override
    public void completed(Integer result, Void att) {
        // Blocking call trong callback - làm nghẽn worker pool
        database.save(buffer); // DB query blocking!
        
        // Hoặc
        anotherChannel.write(otherBuffer, 0).get(); // Blocking Future!
    }
});

// ✅ FIX: Chain async operations
channel.read(buffer, 0, null, new CompletionHandler<>() {
    @Override
    public void completed(Integer result, Void att) {
        // Async database write
        database.saveAsync(buffer)
            .thenRun(() -> continueProcessing());
    }
});
```

### 8.2 Buffer Sharing Race Condition

```java
// ❌ ANTI-PATTERN: Share mutable buffer
ByteBuffer sharedBuffer = ByteBuffer.allocate(1024);

for (int i = 0; i < 100; i++) {
    // Buffer được dùng cho nhiều concurrent operations
    channel.read(sharedBuffer, i * 1024, null, handler);
}

// ✅ FIX: Mỗi operation có buffer riêng
for (int i = 0; i < 100; i++) {
    ByteBuffer buffer = ByteBuffer.allocateDirect(1024);
    channel.read(buffer, i * 1024, buffer, handler); // buffer làm attachment
}
```

### 8.3 Unbounded Submission

```java
// ❌ ANTI-PATTERN: Submit không giới hạn
List<Path> files = listAllFiles(); // 1M files
for (Path file : files) {
    AsynchronousFileChannel channel = AsynchronousFileChannel.open(file);
    channel.read(buffer, 0, null, handler); // OOM risk!
}

// ✅ FIX: Semaphore-based backpressure
Semaphore semaphore = new Semaphore(100); // Max 100 concurrent

for (Path file : files) {
    semaphore.acquire(); // Block if at limit
    
    AsynchronousFileChannel channel = AsynchronousFileChannel.open(file);
    channel.read(buffer, 0, null, new CompletionHandler<>() {
        @Override
        public void completed(Integer result, Void att) {
            try {
                process(result);
            } finally {
                semaphore.release(); // Always release
                close(channel);
            }
        }
        
        @Override
        public void failed(Throwable exc, Void att) {
            semaphore.release();
            close(channel);
        }
    });
}
```

### 8.4 Exception Swallowing

```java
// ❌ ANTI-PATTERN: Empty failed() method
new CompletionHandler<Integer, Void>() {
    @Override
    public void completed(Integer result, Void att) {
        // Process result
    }
    
    @Override
    public void failed(Throwable exc, Void att) {
        // EMPTY - exception lost forever!
    }
};

// ✅ FIX: Always handle or propagate
new CompletionHandler<Integer, Void>() {
    @Override
    public void failed(Throwable exc, Void att) {
        logger.error("Async I/O failed", exc);
        
        // Notify caller
        completionPromise.completeExceptionally(exc);
        
        // Cleanup
        cleanupResources();
    }
};
```

### 8.5 Direct Buffer Memory Leak

```java
// ❌ ANTI-PATTERN: Allocate không giới hạn
while (true) {
    ByteBuffer buffer = ByteBuffer.allocateDirect(100 * 1024 * 1024); // 100MB
    channel.read(buffer, 0, null, handler);
    // Buffer references accumulated → Direct memory OOM
}

// ✅ FIX: Pool buffers
class BufferPool {
    private final Queue<ByteBuffer> pool = new ConcurrentLinkedQueue<>();
    private final int bufferSize;
    private final int maxPoolSize;
    private final AtomicInteger created = new AtomicInteger();
    
    ByteBuffer acquire() {
        ByteBuffer buffer = pool.poll();
        if (buffer != null) {
            buffer.clear();
            return buffer;
        }
        
        if (created.incrementAndGet() <= maxPoolSize) {
            return ByteBuffer.allocateDirect(bufferSize);
        }
        
        throw new IllegalStateException("Pool exhausted");
    }
    
    void release(ByteBuffer buffer) {
        if (pool.size() < maxPoolSize) {
            pool.offer(buffer);
        } else {
            created.decrementAndGet(); // Allow GC
        }
    }
}
```

---

## 9. Khuyến Nghị Production

### 9.1 Configuration Template

```java
public class ProductionAIOConfig {
    
    /**
     * Tạo AsynchronousChannelGroup tối ưu cho production
     */
    public static AsynchronousChannelGroup createOptimizedGroup() 
            throws IOException {
        
        int threadCount = Runtime.getRuntime().availableProcessors() * 2;
        
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();
            
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "aio-worker-" + counter.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY);
                t.setUncaughtExceptionHandler((thread, ex) -> {
                    // Log to centralized logging
                    System.err.printf("Uncaught in %s: %s%n", 
                        thread.getName(), ex.getMessage());
                });
                return t;
            }
        };
        
        return AsynchronousChannelGroup.withFixedThreadPool(threadCount, factory);
    }
    
    /**
     * Channel options tối ưu
     */
    public static Set<OpenOption> optimizedReadOptions() {
        return Set.of(
            StandardOpenOption.READ,
            StandardOpenOption.DIRECT  // Bypass page cache nếu OS hỗ trợ
        );
    }
}
```

### 9.2 Monitoring và Observability

```java
public class AIOMetrics {
    
    private final AtomicLong pendingOps = new AtomicLong();
    private final AtomicLong completedOps = new AtomicLong();
    private final AtomicLong failedOps = new AtomicLong();
    private final Histogram latencyHistogram = new Histogram();
    
    public <V, A> CompletionHandler<V, A> instrumentedHandler(
            CompletionHandler<V, A> delegate, String operation) {
        
        long startTime = System.nanoTime();
        pendingOps.incrementAndGet();
        
        return new CompletionHandler<>() {
            @Override
            public void completed(V result, A attachment) {
                pendingOps.decrementAndGet();
                completedOps.incrementAndGet();
                latencyHistogram.record(System.nanoTime() - startTime);
                
                delegate.completed(result, attachment);
            }
            
            @Override
            public void failed(Throwable exc, A attachment) {
                pendingOps.decrementAndGet();
                failedOps.incrementAndGet();
                
                // Log với context
                System.err.printf("AIO failed [%s]: %s%n", operation, exc.getMessage());
                
                delegate.failed(exc, attachment);
            }
        };
    }
    
    public void report() {
        System.out.printf(
            "AIO Stats: pending=%d, completed=%d, failed=%d, latency(p99)=%.2fms%n",
            pendingOps.get(),
            completedOps.get(),
            failedOps.get(),
            latencyHistogram.getP99() / 1_000_000.0
        );
    }
}
```

### 9.3 When to Use AIO

| Scenario | Recommendation |
|----------|----------------|
| Simple file read/write | **NIO (Files.readAllBytes)** - đơn giản hơn |
| High-volume concurrent file I/O | **AIO** - scale tốt hơn |
| Network I/O | **Netty** hoặc **Java 21+ Virtual Threads** - ecosystem tốt hơn |
| Database operations | **Connection Pool + JDBC** - AIO không hỗ trợ JDBC |
| Log writing | **Buffered + async appender** - AIO overkill |
| Large file processing | **AIO + memory-mapped** - kết hợp cả hai |

---

## 10. Kết Luận

### Bản Chất Cốt Lõi

**AIO trong Java NIO.2** là cơ chế I/O **completion-based** thay vì **readiness-based** như Selector-based NIO:

1. **Thread efficiency:** 1 thread có thể khởi tạo hàng nghìn I/O operations mà không block
2. **Kernel integration:** Sử dụng native async I/O (io_uring, IOCP) khi có thể
3. **Callback-driven:** Kết quả delivered qua CompletionHandler trên worker thread pool

### Trade-offs Chính

| Lợi ích | Chi phí |
|---------|---------|
| Cực kỳ scalable (100K+ concurrent I/O) | Complexity cao (callback hell) |
| Hiệu quả memory (không cần 1 thread/op) | Khó debug (stack trace fragmented) |
| Non-blocking hoàn toàn | Error handling phức tạp hơn |

### Khuyến Nghị Cuối Cùng

> **Dùng AIO khi:**
> - Cần xử lý hàng nghìn concurrent file operations
> - Hệ thống I/O-bound với volume cao
> - Đã quen với async programming model

> **Không dùng AIO khi:**
> - Đơn giản hơn dùng `Files.read/write` utilities
> - Java 21+ available → Virtual Threads đơn giản hơn nhiều
> - Team không familiar với callback patterns
> - Network I/O → Netty/Reactor là lựa chọn tốt hơn

### Tương Lai: Java 21+ Virtual Threads

```java
// Java 21+ - code đơn giản nhưng hiệu năng như AIO
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (Path file : files) {
        executor.submit(() -> {
            // Blocking I/O nhưng scale như AIO
            byte[] data = Files.readAllBytes(file);
            process(data);
        });
    }
}
```

**Virtual Threads (Project Loom)** thay đổi cán cân: code blocking đơn giản với hiệu năng async. AIO vẫn relevant cho extreme throughput scenarios, nhưng majority use cases nên migrate sang Virtual Threads.

---

## 📚 Tài Liệu Tham Khảo

1. **Java NIO.2 Documentation** - docs.oracle.com/javase/8/docs/api/java/nio/channels/package-summary.html
2. **Linux io_uring** - kernel.org/doc/html/latest/io_uring.html
3. **Project Loom** - openjdk.org/projects/loom/
4. **Java Concurrency in Practice** - Brian Goetz (Chương về I/O)

---

> **💡 Senior Takeaway:** AIO là công cụ mạnh cho specific high-scale scenarios. Hiểu rõ bản chất completion-based vs readiness-based, và trade-off giữa complexity và scalability. Với Java 21+, Virtual Threads cung cấp abstraction tốt hơn cho đa số use cases, nhưng AIO vẫn valuable khi cần maximum control và performance.
