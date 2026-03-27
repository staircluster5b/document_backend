# Netty Deep Dive: Kiến Trúc Event Loop, Channel Pipeline và Memory Management

> **Mục tiêu:** Hiểu sâu bản chất hoạt động của Netty - từ event loop đến zero-copy, từ memory pool đến backpressure - để thiết kế và vận hành hệ thống network high-performance trong production.

---

## 1. Bản Chất Vấn Đề: Tại Sao Cần Netty?

### 1.1 Giới Hạn Củа Blocking I/O Truyền Thống

Trước Netty, Java network programming chủ yếu dựa vào:
- **java.net.Socket**: Mỗi connection = 1 thread → Thread explosion với C10K problem
- **Thread-per-connection model**: Context switch cost cao, memory overhead lớn
- **Blocking operations**: Thread bị block → Resource idle → Throughput kém

### 1.2 Tối Ưu Hóa Theo Chiều Ngang vs Chiều Sâu

| Mô hình | Thread Count | Memory/Connection | Context Switch | Scalability |
|---------|--------------|-------------------|----------------|-------------|
| Thread-per-connection | O(n) | ~1MB | High | Low |
| Thread pool + blocking | O(pool) | ~1MB | Medium | Medium |
| **Event-driven (Netty)** | O(cores) | ~KB | Minimal | **High** |

> **Insight:** Netty không "nhanh hơn" ở từng request đơn lẻ. Nó "nhanh hơn" ở scale vì giảm overhead hệ điều hành, không phải tối ưu algorithm.

---

## 2. Event Loop Architecture: Trái Tim Củа Netty

### 2.1 Bản Chất Event Loop

Event loop là một thread đơn lẻ chạy vòng lặp vô hạn:

```
while (!terminated) {
    // 1. Chờ và chọn events ready (non-blocking)
    readyChannels = selector.select();
    
    // 2. Xử lý từng event
    for (channel : readyChannels) {
        process(channel);
    }
    
    // 3. Thực thi các task đã submit vào queue
    runAllTasks();
}
```

**Bản chất:**
- **Single-threaded execution**: Mỗi channel được "gắn" với một event loop cố định
- **Non-blocking I/O**: Một thread xử lý hàng nghìn connections
- **Task queue**: Cho phép submit task từ thread khác vào event loop

### 2.2 Boss Group vs Worker Group

Netty tách biệt rõ ràng hai vai trò:

| Thành phần | Nhiệm vụ | Số lượng khuyến nghị |
|------------|----------|---------------------|
| **Boss Group** | Accept incoming connections | 1 (hoặc 2 cho HA) |
| **Worker Group** | Read/write data, business logic | 2 × CPU cores |

**Tại sao tách biệt?**
- Accept operation rất nhanh (chỉ tạo socket)
- I/O operations có thể chậm (đọc/ghi data, xử lý logic)
- Tách biệt để accept không bị block bởi heavy I/O

### 2.3 Channel Registration và Thread Affinity

```
Khi connection được accept:
1. ServerSocketChannel accept() tạo SocketChannel
2. Boss group chọn Worker EventLoop theo round-robin
3. SocketChannel.register(workerEventLoop)
4. Tất cả I/O events của channel này sẽ được xử lý bởi EventLoop này mãi mãi
```

> **Critical:** Thread affinity đảm bảo không có race condition khi xử lý cùng channel, nhưng cũng có nghĩa là **không được block event loop**.

### 2.4 Trade-off của Event Loop Model

**Ưu điểm:**
- Thread count thấp (thường = CPU cores)
- Không context switching
- CPU cache-friendly (data locality)

**Nhược điểm / Rủi ro:**
- **Blocking là tử thần**: Một blocking call block cả event loop
- **CPU-intensive tasks**: Tính toán nặng làm chậm tất cả connections trong cùng event loop
- **Debugging khó khăn**: Stack trace không rõ ràng, async error handling phức tạp

---

## 3. Channel Pipeline: Xử Lý Dữ Liệu Theo Kiểu Chain-of-Responsibility

### 3.1 Kiến Trúc Pipeline

Pipeline trong Netty là một linked-list của ChannelHandler, xử lý data theo hai hướng:

**Inbound (Read flow):**
```
Socket → Head → Decoder → Logging → Auth → Business Logic → Tail
```

**Outbound (Write flow):**
```
Business Logic → Auth → Logging → Encoder → Head → Socket
```

**Bản chất:**
- Mỗi handler là một node trong doubly-linked list
- Context (ChannelHandlerContext) giữ reference đến next/prev handler
- Event được propagate qua từng handler

### 3.2 Inbound vs Outbound Handlers

| Loại | Interface | Trigger | Ví dụ |
|------|-----------|---------|-------|
| **Inbound** | ChannelInboundHandler | Socket events | Decoder, Auth, Business logic |
| **Outbound** | ChannelOutboundHandler | Write operations | Encoder, Compressor |

**Quan trọng:** Outbound handlers chỉ được gọi khi có explicit write operation. Nếu bạn gọi `ctx.write()`, nó đi ngược lại trong pipeline. Nếu gọi `ctx.channel().write()`, nó đi từ tail.

### 3.3 Decoder/Encoder Pattern

**Decoder (ByteToMessageDecoder):**
```
Socket read (ByteBuf) → decode() → Java Object → next handler
```

**Encoder (MessageToByteEncoder):**
```
Java Object → encode() → ByteBuf → Socket write
```

**Bản chất cơ chế decode:**
- Netty gọi `decode()` mỗi khi có data mới
- Decoder phải kiểm tra đủ bytes để decode một message
- Nếu chưa đủ, return và đợi thêm data
- **Composite buffer**: Netty tự động accumulate bytes cho đến khi đủ

### 3.4 Pipeline Modification (Dynamic)

Netty cho phép thêm/xóa handler runtime:
- `pipeline.addLast("decoder", new MyDecoder())`
- `pipeline.remove("decoder")`
- `pipeline.replace("decoder", "newDecoder", new NewDecoder())`

> **Production Concern:** Modification phải thread-safe. Pipeline modification nên làm trong event loop hoặc dùng `channel.eventLoop().submit()`.

---

## 4. ByteBuf: Memory Management Tinh Vi

### 4.1 Tại Sao Không Dùng Java NIO ByteBuffer?

| Vấn đề với ByteBuffer | Giải pháp của ByteBuf |
|----------------------|----------------------|
| Fixed capacity | Dynamic expansion |
| Single reader/writer index | Separate read/write index |
| No reference counting | Reference counting cho pooling |
| API cumbersome | Rich API, fluent interface |

### 4.2 Reference Counting: Bản Chất

ByteBuf sử dụng reference counting để quản lý lifecycle:

```
allocate() → refCnt = 1
retain()   → refCnt++
release()  → refCnt--
if refCnt == 0 → deallocate
```

**Bản chất memory leak:**
- Mỗi lần `retain()` mà không có `release()` tương ứng
- Handler downstream `retain()` nhưng upstream không `release()`
- Exception path bị miss release

> **Critical Rule:** Ai gọi `retain()` thì phải gọi `release()`. ByteBuf phải được release trong cùng event loop.

### 4.3 ByteBuf Types

| Type | Storage | Mô tả |
|------|---------|-------|
| **Heap** | JVM Heap | Garbage collected, slower I/O |
| **Direct** | Native memory | Zero-copy I/O, explicit deallocation |
| **Composite** | Multiple buffers | Zero-copy composition |

**Khi nào dùng gì:**
- I/O operations (socket read/write): Direct buffer
- Business logic xử lý data: Heap buffer (hoặc direct nếu cần)
- Message composition: Composite buffer

### 4.4 Pooled vs Unpooled

| Loại | Allocation | Deallocation | Use case |
|------|------------|--------------|----------|
| **Unpooled** | malloc/jvm new | GC/free | Short-lived, small buffers |
| **Pooled** | Pre-allocated arena | Return to pool | High-throughput, frequent allocation |

**PooledByteBufAllocator:**
- Sử dụng memory arenas để giảm contention
- Tiny (0-512B), Small (512B-8KB), Normal (8KB-16MB) pools
- Thread-local caches cho allocation/deallocation nhanh

**Trade-off:**
- Pooling giảm allocation cost nhưng tăng memory footprint
- Pooling phức tạp debugging (buffer leak khó trace)
- Unpooled đơn giản nhưng high allocation pressure

---

## 5. Zero-Copy: Tối Ưu Memory Movement

### 5.1 Bản Chất Zero-Copy

Zero-copy không có nghĩa là "không copy", mà là **"không copy trong user space"**.

**Traditional copy:**
```
Disk → Kernel buffer → User buffer (JVM) → Kernel buffer → Socket
         (DMA copy)   (CPU copy)          (CPU copy)    (DMA copy)
```

**Zero-copy (sendfile):**
```
Disk → Kernel buffer → Socket
         (DMA copy)    (DMA gather)
```

### 5.2 Netty Zero-Copy Techniques

**1. CompositeByteBuf:**
```java
// Thay vì copy 2 buffers thành 1
ByteBuf header = ...;
ByteBuf body = ...;
CompositeByteBuf composite = Unpooled.compositeBuffer();
composite.addComponents(true, header, body);
```
- Không copy data, chỉ tạo linked list của buffers
- Single virtual buffer từ multiple physical buffers

**2. FileRegion (sendfile):**
```java
FileRegion region = new DefaultFileRegion(
    new FileInputStream(file).getChannel(), 
    0, 
    file.length()
);
channel.writeAndFlush(region);
```
- Kernel-level zero-copy từ disk đến network
- Không đi qua user space

**3. Slice/Duplicate:**
```java
ByteBuf slice = buffer.slice(0, 100);  // Shared storage, independent indices
ByteBuf copy = buffer.copy(0, 100);    // Deep copy
```
- `slice()`: Zero-copy view, share backing array
- `copy()`: Deep copy, independent storage

### 5.3 Trade-off và Giới Hạn

**Ưu điểm:**
- Giảm memory bandwidth usage
- Giảm CPU cycles cho memory copy
- Giảm GC pressure

**Nhược điểm / Rủi ro:**
- Composite buffers phức tạp hơn single buffer
- Slice cần careful reference counting (share backing array)
- FileRegion chỉ work với files, không work với processed data

---

## 6. Backpressure: Kiểm Soát Luồng Dữ Liệu

### 6.1 Bản Chất Vấn Đề

**Backpressure scenario:**
- Producer (upstream) nhanh hơn Consumer (downstream)
- Buffer tích lũy không giới hạn → OutOfMemoryError
- Hoặc packet drop → Data loss

### 6.2 Netty Backpressure Mechanisms

**1. Channel writability:**
```java
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (!ctx.channel().isWritable()) {
        // Channel buffer đầy, không nên write thêm
        // Hoặc apply backpressure upstream
    }
}
```

- Netty track high/low watermarks cho outbound buffer
- `isWritable()` return false khi buffer size > high watermark
- Return true khi < low watermark (sau khi flush)

**2. Auto-read control:**
```java
// Tạm dừng đọc data từ socket
channel.config().setAutoRead(false);

// Resume đọc khi sẵn sàng xử lý
channel.config().setAutoRead(true);
```

**3. Flow control trong application:**
```java
// Bounded queue cho inbound messages
BlockingQueue<Message> queue = new ArrayBlockingQueue<>(1000);

// Khi queue full, block hoặc drop
if (!queue.offer(msg)) {
    // Apply backpressure: pause auto-read hoặc drop
}
```

### 6.3 Watermark Configuration

```java
channel.config().setWriteBufferHighWaterMark(64 * 1024);  // 64KB
channel.config().setWriteBufferLowWaterMark(32 * 1024);   // 32KB
```

**Logic:**
- Write buffer > 64KB → `isWritable()` = false
- Write buffer < 32KB → `isWritable()` = true
- Ngăn chặn oscillation giữa writable/non-writable

### 6.4 Backpressure Anti-Patterns

> **Fatal:** Luôn luôn kiểm tra `isWritable()` trước khi write trong high-throughput scenarios.

```java
// ❌ WRONG: Unbounded write
for (Message msg : messages) {
    ctx.write(msg);  // Buffer có thể grow vô hạn
}
ctx.flush();

// ✅ CORRECT: Respect writability
for (Message msg : messages) {
    if (!ctx.channel().isWritable()) {
        // Pause hoặc queue để xử lý sau
        break;
    }
    ctx.write(msg);
}
ctx.flush();
```

---

## 7. Production Concerns và Failure Modes

### 7.1 Common Failure Modes

| Symptom | Nguyên nhân | Khắc phục |
|---------|-------------|-----------|
| **OutOfDirectMemoryError** | ByteBuf leak hoặc quá nhiều concurrent connections | Profiling reference counting, giới hạn concurrent connections |
| **Event loop blocked** | Blocking call trong handler | Chuyển sang separate thread pool |
| **Handler order wrong** | Decoder sau Business Logic | Sắp xếp lại pipeline |
| **Memory leak (slow)** | Quên release ByteBuf | Code review, sử dụng ResourceLeakDetector |
| **High GC pressure** | Quá nhiều ByteBuf allocation | Enable pooling, tăng buffer sizes |

### 7.2 ResourceLeakDetector

Netty cung cấp built-in memory leak detection:

```java
// JVM parameter
-Dio.netty.leakDetectionLevel=ADVANCED

// Levels: DISABLED, SIMPLE, ADVANCED, PARANOID
```

**Levels:**
- **SIMPLE**: 1% sampling, minimal overhead
- **ADVANCED**: 1% sampling, full stack trace
- **PARANOID**: 100% sampling, for testing only

> **Production tip:** Chạy với ADVANCED trong staging, SIMPLE trong production.

### 7.3 Monitoring và Observability

**Metrics quan trọng:**
- Event loop task queue size
- ByteBuf allocator statistics (pooled/unpooled)
- Channel writability changes
- Handler execution time
- ByteBuf leak detection reports

**Implementation:**
```java
// Custom EventLoopGroup với monitoring
public class MonitoredEventLoopGroup extends NioEventLoopGroup {
    @Override
    protected EventLoop newChild(Executor executor, Object... args) {
        return new MonitoredEventLoop(this, executor, (SelectorProvider) args[0]);
    }
}
```

### 7.4 Security Concerns

| Vấn đề | Mô tả | Giải pháp |
|--------|-------|-----------|
| **Decoder bomb** | Malicious input làm decoder allocate quá nhiều memory | Giới hạn max frame size |
| **Resource exhaustion** | Slowloris attack giữ connections mở | Connection timeouts, rate limiting |
| **Codec vulnerabilities** | Deserialization of untrusted data | Whitelist classes, custom ObjectDecoder |

---

## 8. So Sánh và Lựa Chọn

### 8.1 Netty vs Alternatives

| Framework | Mô hình | Use case |
|-----------|---------|----------|
| **Netty** | Event-driven, low-level | High-performance protocols, custom protocols |
| **Spring WebFlux** | Reactive, high-level | HTTP APIs, microservices |
| **Vert.x** | Event-driven, polyglot | Polyglot systems, event bus |
| **Apache MINA** | Event-driven, older | Legacy systems, simpler protocols |

**Khi nào dùng Netty trực tiếp:**
- Custom protocols (không phải HTTP)
- Cần tối ưu hóa tối đa performance
- High-throughput, low-latency requirements
- Protocol translation/proxy

**Khi nào dùng abstraction trên Netty:**
- HTTP APIs → Spring WebFlux
- Quick prototyping → Vert.x
- Không cần protocol-level control

### 8.2 Netty 4 vs Netty 5

Netty 5 đã bị hủy bỏ. Netty 4.x vẫn là LTS.

**Lessons:**
- Netty 5 cố gắng simplify threading model nhưng phá vỡ backward compatibility
- Community vẫn dùng Netty 4
- Netty 4.x tiếp tục được maintain với Java 21+ support

---

## 9. Kiến Trúc Đề Xuất Cho Production

### 9.1 Thread Model

```
┌─────────────────────────────────────────┐
│         Boss Group (N=1)                │
│      Accept connections                 │
└─────────────────┬───────────────────────┘
                  │
┌─────────────────▼───────────────────────┐
│       Worker Group (N=cores*2)          │
│  I/O operations + lightweight tasks     │
└─────────────────┬───────────────────────┘
                  │
┌─────────────────▼───────────────────────┐
│    Business Thread Pool (bounded)       │
│  CPU-intensive or blocking operations   │
└─────────────────────────────────────────┘
```

### 9.2 Pipeline Pattern

```
┌─────────────────────────────────────────┐
│  [SSL Handler] - Optional               │
│  [Idle State Handler] - Keep-alive      │
│  [Decoder] - Frame decoding             │
│  [Encoder] - Frame encoding             │
│  [Rate Limiter] - DoS protection        │
│  [Auth Handler] - Authentication        │
│  [Business Handler] - Your logic        │
└─────────────────────────────────────────┘
```

### 9.3 Memory Configuration

```java
// Prefer direct buffers for I/O
System.setProperty("io.netty.noPreferDirect", "false");

// Pooled allocator
ByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;

// Arena configuration
-Dio.netty.allocator.numDirectArenas=8
-Dio.netty.allocator.numHeapArenas=8
-Dio.netty.allocator.pageSize=8192
-Dio.netty.allocator.maxOrder=11
```

---

## 10. Kết Luận

### Bản Chất Netty

Netty là một **network application framework** dựa trên các nguyên tắc:

1. **Event-driven architecture**: Một thread xử lý nhiều connections, không blocking
2. **Pipeline pattern**: Modular, composable protocol handling
3. **Explicit memory management**: Reference counting, pooling, zero-copy
4. **Backpressure**: Explicit flow control, không unbounded buffering

### Trade-off Chính

| Lựa chọn | Ưu điểm | Chi phí |
|----------|---------|---------|
| Event loop | Scalability, performance | Complexity, no blocking |
| ByteBuf pooling | Low allocation rate | Memory overhead, leak risk |
| Zero-copy | Reduced memory bandwidth | Complex buffer management |
| Direct buffers | Fast I/O | Explicit deallocation |

### Rủi Ro Lớn Nhất Trong Production

1. **Blocking event loop**: Dẫn đến timeout cascade
2. **ByteBuf leaks**: Gradual memory exhaustion
3. **Unbounded queues**: OOM under load
4. **Wrong handler order**: Silent failures, security issues

### Lời Khuyên Cuối Cùng

> **Netty không phải là "better Servlet container". Nó là công cụ để xây dựng protocol engines. Nếu bạn chỉ cần HTTP API, dùng abstraction. Nếu bạn cần kiểm soát từng byte trên wire, Netty là lựa chọn đúng.**

> **Bắt đầu với default configuration, đo lường, sau đó tối ưu. Netty có quá nhiều tuning knobs - đừng đoán, hãy measure.**

---

## Tài Liệu Tham Khảo

1. Netty Project Documentation: https://netty.io/wiki/
2. "Netty in Action" - Norman Maurer
3. Java NIO documentation: https://docs.oracle.com/javase/8/docs/api/java/nio/package-summary.html
4. Linux epoll man pages: https://man7.org/linux/man-pages/man7/epoll.7.html
