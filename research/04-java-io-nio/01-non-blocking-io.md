# Non-blocking I/O (java.nio) - Channels, Buffers, Selectors, ByteBuffer

## 1. Mục tiêu của task

Hiểu bản chất của Java NIO (New I/O) - tại sao nó được sinh ra, cơ chế non-blocking hoạt động như thế nào ở tầng OS, và khi nào nên dùng NIO thay vì traditional I/O. Tập trung vào 4 thành phần cốt lõi: Channels, Buffers, Selectors, và ByteBuffer internals.

---

## 2. Bản chất và cơ chế hoạt động

### 2.1. Tại sao cần NIO? Bài toán traditional I/O không giải quyết được

**Traditional I/O (java.io) vấn đề gì?**

```
Thread-per-connection model:
┌─────────────────────────────────────────────────────────────┐
│  ServerSocket                                               │
│       │                                                     │
│       ▼                                                     │
│  accept() ──► Client 1 ──► Thread 1 (blocked on read/write) │
│       │                                                     │
│  accept() ──► Client 2 ──► Thread 2 (blocked on read/write) │
│       │                                                     │
│  accept() ──► Client 3 ──► Thread 3 (blocked on read/write) │
└─────────────────────────────────────────────────────────────┘
```

**Vấn đề cốt lõi:**
1. **Thread overhead**: Mỗi client = 1 thread. 10K concurrent clients = 10K threads
   - Stack size mặc định 1MB → 10K threads = 10GB memory chỉ cho stack
   - Context switch cost: ~1-10μs mỗi lần chuyển đổi
2. **Blocking by design**: `read()`/`write()` block đến khi data sẵn sàng
3. **Kernel copy overhead**: Data copy từ kernel space → user space cho mỗi operation

**Công thức đau thương:**
```
Max concurrent connections ≈ (Available Memory) / (Thread Stack Size + Thread Overhead)
                         ≈ 8GB / (1MB + 2MB) ≈ 2-3K connections
```

### 2.2. NIO Philosophy: Reactor Pattern + Non-blocking I/O

**Nguyên lý cốt lõi:**
> "Don't call us, we'll call you" - Inversion of control cho I/O events

Thay vì thread chờ data (blocking), thread đăng ký interest và được notify khi event xảy ra.

```
Reactor Pattern với NIO:
┌────────────────────────────────────────────────────────────────┐
│  Main Thread (Selector)                                        │
│       │                                                        │
│       ▼                                                        │
│  select() ◄───────────────────────┐                            │
│       │                           │                            │
│       ▼                           │                            │
│  [Channel 1: READABLE]            │                            │
│  [Channel 2: WRITABLE]            │                            │
│  [Channel 3: ACCEPT]              │                            │
│       │                           │                            │
│       ▼                           │                            │
│  Dispatch to Worker Thread        │                            │
│  (non-blocking processing)        │                            │
│       │                           │                            │
│       └───────────────────────────┘                            │
└────────────────────────────────────────────────────────────────┘
```

**Key insight**: 1 thread xử lý N channels → Scalability tăng N lần

### 2.3. Architecture của NIO

```
┌─────────────────────────────────────────────────────────────────┐
│                        Java Application                         │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │   Channel    │  │    Buffer    │  │      Selector        │  │
│  │  (Pipeline)  │  │  (Data Cont) │  │   (Event Multiplexer)│  │
│  └──────┬───────┘  └──────┬───────┘  └──────────┬───────────┘  │
│         │                  │                      │             │
│         └──────────────────┼──────────────────────┘             │
│                            ▼                                    │
│                    ByteBuffer (Direct vs Heap)                  │
├─────────────────────────────────────────────────────────────────┤
│                      JNI / Native Code                          │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │  epoll (L)   │  │  kqueue (B)  │  │  IOCP (W)            │  │
│  │   /dev/poll  │  │              │  │  (completion port)   │  │
│  └──────────────┘  └──────────────┘  └──────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                    Operating System Kernel
```

---

## 3. Kiến trúc chi tiết từng thành phần

### 3.1. ByteBuffer - Trái tim của NIO

**Bản chất:** ByteBuffer là một "window" nhìn vào vùng nhớ liên tục (contiguous memory). Không phải container, mà là view.

```
ByteBuffer Internal Structure:
┌─────────────────────────────────────────────────────────────────┐
│  Heap ByteBuffer                                                │
├─────────────────────────────────────────────────────────────────┤
│  byte[] hb (backing array trong heap)                           │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  [0] [1] [2] [3] [4] [5] [6] [7] [8] [9] [10] ...       │   │
│  └─────────────────────────────────────────────────────────┘   │
│        ▲              ▲                    ▲                    │
│        │              │                    │                    │
│       mark           position            limit              capacity │
│  (reset point)    (next write/read)    (boundary)         (max size) │
└─────────────────────────────────────────────────────────────────┘

Direct ByteBuffer:
┌─────────────────────────────────────────────────────────────────┐
│  Memory address trỏ đến native memory (off-heap)                │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  Native Memory (không nằm trong Java Heap)               │   │
│  │  Không bị GC scan, không copy qua JNI boundary          │   │
│  └─────────────────────────────────────────────────────────┘   │
│  address: 0x7f8b2c400000 (native pointer)                       │
└─────────────────────────────────────────────────────────────────┘
```

**Flip operation - Cơ chế switch giữa write mode và read mode:**

```
Write Mode (chuẩn bị nhận data từ channel):
┌────────────────────────────────────────────┐
│  [P] [P] [P] [ ] [ ] [ ] [ ] [ ] [ ] [ ] │
│   0   1   2   3   4   5   6   7   8   9  │
│        ▲              ▲                  │
│     position=3     limit=10              │
└────────────────────────────────────────────┘

flip() được gọi ──►

Read Mode (chuẩn bị đọc data đã nhận):
┌────────────────────────────────────────────┐
│  [D] [D] [D] [ ] [ ] [ ] [ ] [ ] [ ] [ ] │
│   0   1   2   3   4   5   6   7   8   9  │
│   ▲              ▲                       │
│ position=0    limit=3                    │
└────────────────────────────────────────────┘
```

**Trade-off: Heap vs Direct ByteBuffer**

| Aspect | Heap ByteBuffer | Direct ByteBuffer |
|--------|----------------|-------------------|
| **Memory Location** | Java Heap | Native Memory (off-heap) |
| **Allocation Cost** | Cheap (~ns) | Expensive (~μs, malloc + zero) |
| **I/O Performance** | Slow (1 extra copy: heap → native) | Fast (zero copy to channel) |
| **GC Impact** | Subject to GC | Outside GC, but has Cleaner phantom reference |
| **Memory Limit** | -Xmx | Total system RAM (có thể OOM native) |
| **Best For** | Small, short-lived buffers | Large, long-lived, frequent I/O |
| **Max Size** | Integer.MAX_VALUE (2GB) | Platform dependent (~2-4GB practical) |

> **Production Warning**: Direct buffer allocation là synchronous blocking operation. Nếu allocate trong hot path, sẽ thành bottleneck. Dùng pool (Netty's ByteBuf pool) cho high-throughput scenarios.

**Critical Methods và hệ quả:**

```
ByteBuffer API và state transitions:

allocate(int capacity) ──► empty buffer
     │
     ▼
┌─────────────────┐
│ position = 0    │
│ limit = capacity│
│ capacity = N    │
└────────┬────────┘
         │
    put() / read from channel
         │
         ▼
┌─────────────────┐
│ position = N    │
│ limit = capacity│
└────────┬────────┘
         │
       flip()
         │
         ▼
┌─────────────────┐
│ position = 0    │
│ limit = N       │ ← old position
└────────┬────────┘
         │
    get() / write to channel
         │
         ▼
┌─────────────────┐
│ position = N    │
│ limit = N       │
└────────┬────────┘
         │
      clear() / compact()
         │
         ▼
   back to start
```

### 3.2. Channel - Abstraction của I/O Connection

**Bản chất:** Channel là một "pipe" mở đến entity có thể thực hiện I/O operations. Khác với Stream:
- **Stream**: One-way (InputStream OR OutputStream)
- **Channel**: Two-way (đọc và ghi cùng channel)
- **Stream**: Blocking only
- **Channel**: Can be non-blocking

**Channel Hierarchy:**

```
Channel (base interface)
    │
    ├── ReadableByteChannel ──► ScatteringByteChannel
    │                              (scatter: 1 read → N buffers)
    │
    ├── WritableByteChannel ──► GatheringByteChannel
    │                              (gather: N buffers → 1 write)
    │
    ├── InterruptibleChannel (có thể interrupt thread bị block)
    │
    └── Network Channels:
            │
            ├── ServerSocketChannel (non-blocking accept)
            ├── SocketChannel (TCP, non-blocking read/write)
            └── DatagramChannel (UDP)

    └── File Channels:
            │
            ├── FileChannel (memory-mapped files, zero-copy transfer)
            └── Pipe.SourceChannel / Pipe.SinkChannel
```

**Non-blocking SocketChannel:**

```java
SocketChannel.configureBlocking(false);
```

Khi gọi `read()` trên non-blocking channel:
- **Data available**: Trả về số bytes đọc được (> 0)
- **No data**: Trả về 0 immediately (KHÔNG BLOCK)
- **Connection closed**: Trả về -1
- **Error**: Throw IOException

**FileChannel và Zero-Copy:**

```
Traditional file transfer (4 copies, 4 context switches):
┌──────────┐  1   ┌──────────────┐  2   ┌──────────────┐  3   ┌──────────┐  4   ┌──────────┐
│   Disk   │ ───► │ Kernel Cache │ ───► │  User Buffer │ ───► │ Socket   │ ───► │  NIC     │
└──────────┘      └──────────────┘      └──────────────┘      │  Buffer  │      └──────────┘
                                                              └──────────┘

Zero-copy với transferTo() (2 copies, 2 context switches):
┌──────────┐      ┌──────────────┐      ┌──────────────┐      ┌──────────┐
│   Disk   │ ───► │ Kernel Cache │ ───► │ NIC directly │ ───► │ Network  │
└──────────┘      └──────────────┘      │ via DMA      │      └──────────┘
                                        └──────────────┘
```

```java
// Zero-copy file transfer - kernel handles tất cả
try (FileChannel source = FileChannel.open(sourcePath, StandardOpenOption.READ);
     FileChannel dest = FileChannel.open(destPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
    
    long transferred = source.transferTo(0, source.size(), dest);
    // Hoặc: source.transferFrom(dest, 0, source.size());
}
```

**Trade-off zero-copy:**
- ✅ Không dùng CPU để copy data
- ✅ Không allocate buffer trong Java
- ✅ Không qua user space
- ❌ File phải nằm trong page cache (nếu không, kernel phải read vào cache trước)
- ❌ Limited to file sizes (2GB per call trên một số platforms)
- ❌ Không thể transform data (encryption, compression phải qua user space)

### 3.3. Selector - Event Multiplexer

**Bản chất:** Selector là Java wrapper quanh OS-level I/O multiplexing mechanism:
- **Linux**: epoll (edge-triggered hoặc level-triggered)
- **BSD/macOS**: kqueue
- **Windows**: IOCP (I/O Completion Ports)

**Selector Architecture:**

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Selector Implementation                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Set<SelectionKey> keys      (all registered keys)          │   │
│  │  Set<SelectionKey> selectedKeys (ready for operation)       │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                           │                                         │
│  ┌────────────────────────▼─────────────────────────────────────┐  │
│  │  select() → native call → epoll_wait() / kevent() / Wait... │  │
│  │  (BLOCKS until at least 1 channel ready OR timeout)          │  │
│  └─────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  Key: Channel + Selector + interestOps + readyOps + attachment     │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**SelectionKey Operations:**

| Operation | Value | Meaning | Event Trigger |
|-----------|-------|---------|---------------|
| OP_ACCEPT | 16 | Server ready to accept new connection | Client connects |
| OP_CONNECT | 8 | Client connection established | connect() completes |
| OP_READ | 1 | Data available to read | Data arrives |
| OP_WRITE | 4 | Channel ready for writing | Send buffer has space |

**Selector Lifecycle:**

```
Selector.open()
    │
    ▼
channel.register(selector, ops, attachment)
    │
    ▼
while (running) {
    int readyCount = selector.select();  // blocking
    if (readyCount > 0) {
        Set<SelectionKey> keys = selector.selectedKeys();
        for (SelectionKey key : keys) {
            if (key.isAcceptable()) { /* handle accept */ }
            if (key.isReadable()) { /* handle read */ }
            if (key.isWritable()) { /* handle write */ }
            if (key.isConnectable()) { /* finish connect */ }
        }
        keys.clear();  // IMPORTANT!
    }
}
    │
    ▼
selector.close()
```

> **CRITICAL**: `selectedKeys()` trả về mutable set. Phải `clear()` sau mỗi iteration, nếu không keys sẽ accumulate và memory leak.

---

## 4. So sánh các lựa chọn

### 4.1. Traditional I/O vs NIO

| Aspect | java.io (Traditional) | java.nio (Non-blocking) |
|--------|----------------------|------------------------|
| **Thread Model** | Thread-per-connection | Few threads, many connections |
| **Max Connections** | ~1K-3K (memory limited) | ~10K-1M (depending on OS) |
| **Latency** | Low (immediate processing) | Higher (selector overhead) |
| **Throughput** | Good for few connections | Better for many connections |
| **Complexity** | Simple, sequential | Complex, event-driven |
| **Data Handling** | Stream-based | Buffer-based |
| **Blocking** | Always blocking | Can be non-blocking |
| **Use Case** | File I/O, few clients | High-concurrency network |

### 4.2. NIO vs NIO.2 (Java 7+)

| Feature | NIO (Java 1.4) | NIO.2 (Java 7) |
|---------|---------------|----------------|
| **Package** | java.nio | java.nio.file |
| **Path** | java.io.File | java.nio.file.Path |
| **Async I/O** | Không | AsynchronousFileChannel, AsynchronousSocketChannel |
| **File Watcher** | Không | WatchService |
| **File Attributes** | Limited | POSIX, ACL, user-defined |
| **File Visitor** | Không | Walk file tree recursively |
| **Completion Handler** | Không | Callback-based async operations |

### 4.3. Selector Strategies

| Strategy | Pros | Cons | Use Case |
|----------|------|------|----------|
| **Single Selector** | Simple, no coordination overhead | Bottleneck at 10K+ connections | < 10K connections |
| **Multi-Selector (Sharding)** | Scale beyond 10K, better CPU utilization | Complex, connection migration hard | > 10K connections |
| **Acceptor + Worker Selectors** | Separation of concerns | More threads, coordination | Production servers |
| **Netty's EventLoop** | Optimized, battle-tested | Framework dependency | Enterprise apps |

---

## 5. Rủi ro, Anti-patterns, và Lỗi thường gặp

### 5.1. Critical Bugs

**1. Không clear() selectedKeys**
```java
// BUG: Memory leak, keys accumulate
while (true) {
    selector.select();
    Set<SelectionKey> keys = selector.selectedKeys();
    for (SelectionKey key : keys) {
        // process
    }
    // FORGOT: keys.clear()
}
```

**2. Sử dụng Buffer sai sau flip()**
```java
ByteBuffer buf = ByteBuffer.allocate(1024);
channel.read(buf);
buf.flip();  // Switch to read mode

// BUG: Sau khi read hết, buffer ở state: position == limit
// Không thể write tiếp mà không clear() hoặc compact()
channel.read(buf);  // Returns 0, không đọc được gì!
```

**3. Direct Buffer Memory Leak**
```java
// BUG: Direct buffer không được GC ngay, tích lũy native memory
while (true) {
    ByteBuffer buf = ByteBuffer.allocateDirect(1024 * 1024);  // 1MB
    // use buf...
    // buf goes out of scope nhưng Cleaner chưa chạy
    // Native memory OOM sau vài nghìn iterations
}
```

**4. Busy-wait với selectNow()**
```java
// BUG: 100% CPU usage
while (true) {
    if (selector.selectNow() > 0) {  // Non-blocking, returns immediately
        // process
    }
    // Không sleep → CPU spin
}
```

### 5.2. Anti-patterns

**Anti-pattern 1: Blocking operations trong Selector loop**
```java
// BAD: Block cả selector thread
if (key.isReadable()) {
    processLargeRequest(channel);  // Block 500ms
}
```

**Anti-pattern 2: OP_WRITE luôn registered**
```java
// BAD: Selector liên tục wake up khi buffer có space
channel.register(selector, OP_READ | OP_WRITE);  // Always writable

// GOOD: Chỉ register OP_WRITE khi write bị block
channel.register(selector, OP_READ);
// Khi write() trả về 0:
channel.register(selector, OP_READ | OP_WRITE);
```

**Anti-pattern 3: allocateDirect trong hot path**
```java
// BAD: Mỗi request allocate direct buffer
void handleRequest() {
    ByteBuffer buf = ByteBuffer.allocateDirect(8192);  // Expensive!
    // ...
}

// GOOD: ThreadLocal buffer pool
private static final ThreadLocal<ByteBuffer> BUFFER = 
    ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(8192));
```

### 5.3. Edge Cases và Failure Modes

| Scenario | Symptom | Root Cause | Fix |
|----------|---------|------------|-----|
| Native memory OOM | `OutOfMemoryError: Direct buffer memory` | Too many direct buffers | Pool buffers, limit max direct memory (`-XX:MaxDirectMemorySize`) |
| Selector spin | 100% CPU, low throughput | selectNow() loop hoặc bug trong wake up | Use select() với timeout, check JDK version |
| Silent data loss | Client không nhận được response | Partial write, không check return value | Loop write đến khi hết data hoặc register OP_WRITE |
| Connection leak | FD exhaustion, can't accept new | Không close channel khi error | try-finally hoặc try-with-resources |
| Stuck connections | Clients timeout | Slow consumer, buffer full | Backpressure, timeout, circuit breaker |

---

## 6. Khuyến nghị Production

### 6.1. Architecture Pattern

```
┌──────────────────────────────────────────────────────────────────────┐
│                     Production-Grade NIO Server                       │
├──────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌───────────────────────────────────────────────────────────────┐   │
│  │                    Boss/Acceptor Thread                        │   │
│  │  - Chỉ xử lý accept()                                          │   │
│  │  - Register accepted channel vào Worker Selector              │   │
│  └────────────────────────────┬──────────────────────────────────┘   │
│                               │                                       │
│                               ▼                                       │
│  ┌───────────────────────────────────────────────────────────────┐   │
│  │                   Worker Selector Thread(s)                    │   │
│  │  - 1 thread per CPU core                                       │   │
│  │  - Mỗi thread: 1 selector, N channels (N ~= connections/cores)│   │
│  │  - Non-blocking read/write                                     │   │
│  └────────────────────────────┬──────────────────────────────────┘   │
│                               │                                       │
│                               ▼                                       │
│  ┌───────────────────────────────────────────────────────────────┐   │
│  │                   Business Thread Pool                         │   │
│  │  - Xử lý blocking operations: DB, external API                 │   │
│  │  - Độc lập với I/O threads                                     │   │
│  └───────────────────────────────────────────────────────────────┘   │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

### 6.2. Configuration Guidelines

```
JVM Flags cho NIO applications:

# Direct memory limit (mặc định = -Xmx)
-XX:MaxDirectMemorySize=1g

# Off-heap nếu dùng Memory-Mapped Files
-XX:MaxRAMPercentage=75.0

# Epoll trên Linux (Netty tự động chọn)
# Không cần flag, nhưng đảm bảo dùng epoll không phải poll/select

# File descriptor limit
ulimit -n 65535  # hoặc higher
```

### 6.3. Monitoring Metrics

| Metric | What to monitor | Alert threshold |
|--------|----------------|-----------------|
| `selector.select()` latency | Selector processing time | > 50ms p99 |
| Direct memory usage | `BufferPoolMXBean` | > 80% MaxDirectMemorySize |
| Channel count per selector | Connections per selector | > 10,000 |
| Partial writes | Write didn't complete | > 1% of writes |
| Selection key churn | Keys created/destroyed rate | Sudden spikes |
| File descriptor usage | Open sockets | > 80% of ulimit |

### 6.4. Khi nào dùng gì?

**Use Traditional I/O khi:**
- File I/O đơn giản
- < 100 concurrent connections
- Code cần đơn giản, dễ maintain
- Không cần high throughput

**Use NIO khi:**
- > 1K concurrent connections
- Cần non-blocking behavior
- Real-time chat, game servers, proxies
- Bạn control cả client và server

**Use Netty (framework) khi:**
- Production system
- Cần protocol support (HTTP/2, WebSocket, Protobuf)
- Backpressure, flow control
- Không muốn debug selector bugs

**Use NIO.2 Async Channels khi:**
- Cần async I/O mà không muốn dependency
- CompletionHandler pattern phù hợp architecture
- Java 7+ available

---

## 7. Kết luận

**Bản chất cốt lõi của Java NIO:**

1. **Buffer-based**: Dữ liệu luân chuyển qua Buffer (heap hoặc direct), không qua Stream. Buffer là stateful object với position/limit/capacity.

2. **Channel abstraction**: Một channel = một kết nối I/O hai chiều. Có thể configure non-blocking.

3. **Selector multiplexing**: Một thread kiểm soát nhiều channel bằng cách đăng ký interest và được kernel notify khi event xảy ra.

4. **Zero-copy capability**: FileChannel.transferTo/From cho phép kernel chuyển data trực tiếp giữa file và socket, bypass user space.

**Trade-off quan trọng nhất:**
> Complexity vs Scalability. NIO cho phép xử lý 10K+ connections với vài thread, nhưng đòi hỏi event-driven programming model phức tạp hơn, dễ mắc lỗi state management.

**Rủi ro production lớn nhất:**
> Direct buffer memory leaks (khó detect, crash JVM), và selector thread bị block (freeze toàn bộ I/O).

**Quyết định kiến trúc:**
- Nếu team có kinh nghiệm: Custom NIO implementation cho maximum control.
- Nếu cần ship nhanh, production-stable: Dùng Netty, đừng tự viết selector loop.

---

## 8. References

- Java NIO by Ron Hitchens (O'Reilly)
- JDK Source: `sun.nio.ch` package
- Linux man pages: epoll(7), select(2), poll(2)
- Netty documentation: Threading model and ByteBuf
- "Scalable IO in Java" - Doug Lea
