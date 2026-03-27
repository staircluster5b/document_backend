# Blocking vs Non-blocking I/O: Phân tích chuyên sâu từ góc nhìn Senior Backend

> **Mục tiêu:** Thấu hiểu cơ chế vận hành tầng thấp của I/O trong Java, đánh đổi giữa các mô hình, và lựa chọn đúng cho từng kịch bản production.

---

## 📋 Tóm tắt kiến thức cốt lõi

| Mô hình | Đặc điểm | Use Case | Rủi ro chính |
|---------|----------|----------|--------------|
| **Blocking I/O (java.io)** | 1 thread = 1 connection | Ứng dụng đơn giản, ít concurrent | Thread exhaustion, memory bloat |
| **NIO (java.nio)** | Non-blocking, Selector multiplexing | High concurrent, low latency | Complex state machine, debugging khó |
| **NIO.2 (Asynchronous I/O)** | Async callback/Future | File I/O heavy, true async | Callback hell, resource leak |

---

## 1. Bản chất tầng thấp: Tại sao lại có sự khác biệt?

### 1.1 System Call Level

```
┌─────────────────────────────────────────────────────────────┐
│                    User Space (Java)                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │ Blocking I/O │  │     NIO      │  │   NIO.2 (AIO)    │  │
│  │  (java.io)   │  │  (java.nio)  │  │ (java.nio.channels)│ │
│  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘  │
└─────────┼─────────────────┼───────────────────┼────────────┘
          │                 │                   │
          ▼                 ▼                   ▼
┌─────────────────────────────────────────────────────────────┐
│              Kernel Space (Linux/Windows)                    │
│                                                              │
│   read()/write()        select()/poll()/epoll    io_uring   │
│   (blocking)               (non-blocking)       (async)     │
│                                                              │
│   → Thread blocked      → Thread continues      → Kernel     │
│   until data ready        to do other work      notifies     │
│                                                      later   │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 File Descriptor và Kernel Buffer

**Blocking I/O:**
```c
// System call - thread chuyển sang WAITING state
ssize_t read(int fd, void *buf, size_t count) {
    // Kernel copies data từ kernel buffer → user buffer
    // Thread blocked cho đến khi data sẵn sàng
}
```

**NIO (Non-blocking):**
```c
// Fcntl set O_NONBLOCK
fcntl(fd, F_SETFL, O_NONBLOCK);

// read() trả về ngay lập tức
// - Nếu có data: trả về số bytes đọc được
// - Nếu chưa có data: trả về EAGAIN/EWOULDBLOCK
```

**NIO.2 (Asynchronous - Linux io_uring/Windows IOCP):**
```c
// Gửi request đến kernel, không đợi
// Kernel gọi callback khi hoàn thành
struct io_uring_sqe *sqe = io_uring_get_sqe(&ring);
io_uring_prep_read(sqe, fd, buf, size, offset);
io_uring_submit(&ring);
```

---

## 2. Java I/O Models Deep Dive

### 2.1 Traditional Blocking I/O (java.io)

```java
// ⚠️ ANTI-PATTERN trong high-concurrency
public class BlockingServer {
    public void start(int port) throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        
        while (true) {
            // ⚠️ BLOCKING: Main thread đợi connection
            Socket clientSocket = serverSocket.accept();
            
            // ⚠️ Mỗi connection = 1 thread mới
            new Thread(() -> handleClient(clientSocket)).start();
        }
    }
    
    private void handleClient(Socket socket) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream()))) {
            String line;
            // ⚠️ BLOCKING: Thread đợi cho đến khi có dữ liệu
            while ((line = in.readLine()) != null) {
                process(line);
            }
        }
    }
}
```

**Vấn đề tầng thấp:**
- Mỗi thread tiêu tốn ~1MB stack (mặc định -Xss1m)
- 10,000 connections = ~10GB memory chỉ cho thread stack
- Context switching giữa threads rất tốn kém

### 2.2 NIO (Non-blocking I/O)

```java
// ✅ PRODUCTION-READY pattern với Selector
public class NIOServer {
    private Selector selector;
    private ByteBuffer buffer = ByteBuffer.allocate(1024);
    
    public void start(int port) throws IOException {
        selector = Selector.open();
        
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false); // ⭐ KEY: Non-blocking mode
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        
        while (true) {
            // ⭐ BLOCK ở đây 1 lần cho TẤT CẢ channels
            selector.select();
            
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> iter = selectedKeys.iterator();
            
            while (iter.hasNext()) {
                SelectionKey key = iter.next();
                iter.remove();
                
                if (key.isAcceptable()) {
                    accept(key);
                } else if (key.isReadable()) {
                    read(key);
                } else if (key.isWritable()) {
                    write(key);
                }
            }
        }
    }
    
    private void accept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        client.configureBlocking(false);
        
        // ⭐ Đăng ký read interest, không tạo thread mới
        client.register(selector, SelectionKey.OP_READ);
    }
    
    private void read(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        buffer.clear();
        
        int bytesRead = channel.read(buffer);
        if (bytesRead == -1) {
            // Connection closed
            key.cancel();
            channel.close();
            return;
        }
        
        buffer.flip();
        // Process data...
        
        // ⭐ Chuyển sang write mode nếu cần
        key.interestOps(SelectionKey.OP_WRITE);
    }
}
```

**Cơ chế Selector (Multiplexing):**

```
┌─────────────────────────────────────────────────────────┐
│                    Single Thread                         │
│                         │                               │
│                         ▼                               │
│  ┌─────────────────────────────────────────────┐       │
│  │              Selector.select()               │       │
│  │  (blocks until at least 1 channel ready)     │       │
│  └─────────────────────────────────────────────┘       │
│                         │                               │
│                         ▼                               │
│  ┌─────────────────────────────────────────────┐       │
│  │         selectedKeys (ready set)             │       │
│  │  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐   │       │
│  │  │ Ch1 │ │ Ch2 │ │ Ch3 │ │ Ch4 │ │ Ch5 │   │       │
│  │  │ READ│ │WRITE│ │ READ│ │ACCEPT│ │WRITE│   │       │
│  │  └─────┘ └─────┘ └─────┘ └─────┘ └─────┘   │       │
│  └─────────────────────────────────────────────┘       │
│                         │                               │
│                         ▼                               │
│              Process each ready key                     │
│         (non-blocking, no thread per conn)              │
└─────────────────────────────────────────────────────────┘
```

### 2.3 NIO.2 Asynchronous I/O (Java 7+)

```java
// ✅ True async - Kernel handles notification
public class AioServer {
    
    public void start(int port) throws IOException {
        AsynchronousServerSocketChannel server = 
            AsynchronousServerSocketChannel.open();
        server.bind(new InetSocketAddress(port));
        
        // ⭐ Non-blocking accept với callback
        server.accept(null, new CompletionHandler<>() {
            @Override
            public void completed(AsynchronousSocketChannel client, Void attachment) {
                // ⭐ Tiếp tục accept connection khác
                server.accept(null, this);
                
                // Handle client
                handleClient(client);
            }
            
            @Override
            public void failed(Throwable exc, Void attachment) {
                exc.printStackTrace();
            }
        });
        
        // Main thread free to do other work
        // (hoặc block để giữ chương trình chạy)
    }
    
    private void handleClient(AsynchronousSocketChannel client) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        
        // ⭐ Async read với callback
        client.read(buffer, buffer, new CompletionHandler<>() {
            @Override
            public void completed(Integer bytesRead, ByteBuffer buf) {
                if (bytesRead == -1) {
                    close(client);
                    return;
                }
                
                buf.flip();
                process(buf);
                
                // Chain async operations
                client.write(buf, null, new CompletionHandler<>() {
                    @Override
                    public void completed(Integer result, Void att) {
                        // Continue reading
                        ByteBuffer newBuf = ByteBuffer.allocate(1024);
                        client.read(newBuf, newBuf, this);
                    }
                    
                    @Override
                    public void failed(Throwable exc, Void att) {
                        exc.printStackTrace();
                    }
                });
            }
            
            @Override
            public void failed(Throwable exc, ByteBuffer buf) {
                exc.printStackTrace();
            }
        });
    }
}
```

---

## 3. Buffer - Trái tim của NIO

### 3.1 Buffer States

```
┌─────────────────────────────────────────────────────────────┐
│                    ByteBuffer Structure                      │
├─────────────────────────────────────────────────────────────┤
│  Position: Con trỏ đến vị trí next read/write               │
│  Limit: Giới hạn không được vượt qua                        │
│  Capacity: Kích thước tối đa của buffer                     │
└─────────────────────────────────────────────────────────────┘

State Transition:

Empty buffer (new):
┌─────────────────────────────────────┐
│ [   |   |   |   |   |   |   |   ] │
│  P,L                               C│
│  0,0                               8│
└─────────────────────────────────────┘

After put() 3 bytes:
┌─────────────────────────────────────┐
│ [ H | e | l |   |   |   |   |   ] │
│              P  L                  C│
│              3  8                  8│
└─────────────────────────────────────┘

After flip() (chuẩn bị read):
┌─────────────────────────────────────┐
│ [ H | e | l |   |   |   |   |   ] │
│  P         L                       C│
│  0         3                       8│
└─────────────────────────────────────┘

After get() 2 bytes:
┌─────────────────────────────────────┐
│ [ H | e | l |   |   |   |   |   ] │
│          P L                       C│
│          2 3                       8│
└─────────────────────────────────────┘

After clear() (reset để write):
┌─────────────────────────────────────┐
│ [ H | e | l |   |   |   |   |   ] │
│  P              L                  C│
│  0              8                  8│
└─────────────────────────────────────┘
```

### 3.2 Direct vs Heap Buffer

```java
// Heap Buffer - Data trong Java Heap
ByteBuffer heapBuf = ByteBuffer.allocate(1024);
//  ┌──────────┐     ┌──────────┐
//  │  JVM     │ →   │  Java    │
//  │  Heap    │     │  Heap    │
//  └──────────┘     └──────────┘
// Copy data từ JVM heap → Native memory khi I/O

// Direct Buffer - Data trong Native memory (off-heap)
ByteBuffer directBuf = ByteBuffer.allocateDirect(1024);
//  ┌──────────┐
//  │  Native  │ ← Không qua JVM Heap
//  │  Memory  │
//  └──────────┘
// Zero-copy khi I/O, nhưng allocate/free chậm hơn
```

| Đặc điểm | Heap Buffer | Direct Buffer |
|----------|-------------|---------------|
| Vị trí | JVM Heap | Native Memory |
| Allocation | Nhanh | Chậm (~10x) |
| I/O Performance | Chậm (1 extra copy) | Nhanh (zero-copy) |
| Memory footprint | GC-managed | Manual cleanup (Cleaner) |
| Use case | Small, short-lived | Large, long-lived, frequent I/O |

---

## 4. Channel - Abstraction của I/O

```
┌─────────────────────────────────────────────────────────┐
│                    Channel Types                        │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  FileChannel        ───────→  File I/O (random access) │
│  │                                                      │
│  SocketChannel      ───────→  TCP Client               │
│  │                                                      │
│  ServerSocketChannel ──────→  TCP Server               │
│  │                                                      │
│  DatagramChannel    ───────→  UDP                      │
│  │                                                      │
│  AsynchronousFileChannel ──→  Async File I/O (NIO.2)   │
│  AsynchronousSocketChannel →  Async TCP (NIO.2)        │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 4.1 FileChannel - Zero Copy với transferTo

```java
// ✅ ZERO-COPY: File → Socket (Kernel space only)
public void zeroCopyTransfer(String filePath, SocketChannel dest) 
        throws IOException {
    
    try (RandomAccessFile file = new RandomAccessFile(filePath, "r");
         FileChannel sourceChannel = file.getChannel()) {
        
        long position = 0;
        long count = sourceChannel.size();
        
        // ⭐ Không qua user space! Kernel → Kernel directly
        sourceChannel.transferTo(position, count, dest);
    }
}

// Traditional way (2 copies + 2 context switches):
// File → Kernel Buffer → User Buffer (Java) → Kernel Buffer → Socket
//                                        ↑
//                                    This is eliminated!
```

---

## 5. Performance Benchmark

| Metric | Blocking I/O | NIO | NIO.2 | Netty (NIO wrapper) |
|--------|--------------|-----|-------|---------------------|
| Concurrent connections | ~1,000 | ~100,000 | ~100,000 | ~1,000,000+ |
| Memory per connection | ~1MB | ~4KB | ~4KB | ~2KB |
| Latency (p99) | High | Low | Low | Very Low |
| Throughput | Medium | High | High | Very High |
| Code complexity | Low | High | Medium | Low |

---

## 6. Anti-patterns và Rủi ro

### 6.1 Buffer Leak

```java
// ❌ ANTI-PATTERN: Không giải phóng Direct Buffer
public void process() {
    ByteBuffer buf = ByteBuffer.allocateDirect(1024 * 1024); // 1MB
    // Sử dụng...
    // ⚠️ Không có buf.clear() hoặc để GC
    // Direct buffer không được GC ngay lập tức!
}

// ✅ Cách xử lý:
// 1. Dùng try-with-resources pattern (Java 13+)
// 2. Hoặc dùng MemorySegment (Java 21 - see below)
```

### 6.2 Selector Key Leak

```java
// ❌ ANTI-PATTERN: Không remove key từ selectedKeys
while (true) {
    selector.select();
    Set<SelectionKey> keys = selector.selectedKeys();
    for (SelectionKey key : keys) {
        if (key.isReadable()) {
            read(key);
        }
        // ⚠️ Forgot: keys.remove(key);
    }
    // Key vẫn còn trong set, sẽ được xử lý lại!
}

// ✅ Luôn dùng Iterator và remove()
Iterator<SelectionKey> iter = keys.iterator();
while (iter.hasNext()) {
    SelectionKey key = iter.next();
    iter.remove(); // ⭐ Quan trọng!
    // Process...
}
```

### 6.3 Blocking trong Non-blocking Code

```java
// ❌ ANTI-PATTERN: Gọi blocking operation trong Selector thread
if (key.isReadable()) {
    // ⚠️ Database query blocking!
    ResultSet rs = jdbcTemplate.query(...);
    // Selector thread bị block, tất cả connections bị ảnh hưởng
}

// ✅ Tách biệt: Selector thread chỉ làm I/O
// Chuyển business logic sang worker thread pool
if (key.isReadable()) {
    ByteBuffer buf = read(channel);
    workerPool.submit(() -> processAndRespond(buf, channel));
}
```

---

## 7. Java 21+ Updates: Virtual Threads + NIO

```java
// Java 21: Virtual Threads giải quyết vấn đề của Blocking I/O
public class VirtualThreadServer {
    public void start(int port) throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        
        while (true) {
            Socket clientSocket = serverSocket.accept();
            
            // ⭐ Tạo virtual thread cho mỗi connection
            // Nhẹ như NIO nhưng code đơn giản như Blocking I/O
            Thread.ofVirtual().start(() -> handleClient(clientSocket));
        }
    }
    
    private void handleClient(Socket socket) {
        // Code blocking thông thường...
        // Nhưng virtual thread được unmount khi block
        // Không tiêu tốn OS thread!
    }
}
```

**Kết hợp Virtual Threads + NIO:**
- Virtual Threads: Giải quyết vấn đề thread count
- NIO: Giải quyết vấn đề latency và throughput
- Có thể dùng Blocking I/O API với Virtual Threads cho đơn giản

### 7.2 Foreign Function & Memory API (Java 21 - Preview)

```java
// Thay thế ByteBuffer.allocateDirect với quản lý tốt hơn
try (Arena arena = Arena.ofConfined()) {
    MemorySegment segment = arena.allocate(1024);
    // Sử dụng segment...
    // Tự động cleanup khi arena close
}
```

---

## 8. Công cụ và Thư viện Production

| Công cụ | Mục đích | Link |
|---------|----------|------|
| **Netty** | High-performance NIO framework | <https://netty.io/> |
| **Vert.x** | Reactive toolkit | <https://vertx.io/> |
| **Project Reactor** | Reactive streams | <https://projectreactor.io/> |
| **async-profiler** | Profile I/O bottlenecks | <https://github.com/jvm-profiling-tools/async-profiler> |
| **tcpkali** | Load testing TCP servers | <https://github.com/satori-com/tcpkali> |

---

## 9. Kết luận và Khuyến nghị

### Khi nào dùng cái gì?

```
┌─────────────────────────────────────────────────────────────┐
│                    Decision Tree                            │
│                                                             │
│  ┌──────────────────┐                                       │
│  │ Cần đơn giản?    │                                       │
│  └────────┬─────────┘                                       │
│           │                                                 │
│     ┌─────┴─────┐                                           │
│     ▼           ▼                                           │
│    Yes          No                                          │
│     │           │                                           │
│     ▼           ▼                                           │
│  ┌────────┐  ┌──────────────────┐                           │
│  │Java 21+│  │ High concurrency?│                           │
│  │Virtual │  └────────┬─────────┘                           │
│  │Thread  │           │                                     │
│  └────────┘     ┌─────┴─────┐                               │
│                 ▼           ▼                               │
│                Yes          No                              │
│                 │           │                               │
│                 ▼           ▼                               │
│              ┌────────┐  ┌────────┐                         │
│              │Netty   │  │Blocking│                         │
│              │or NIO  │  │I/O     │                         │
│              └────────┘  └────────┘                         │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Key Takeaways

1. **Blocking I/O:** Chỉ dùng cho ứng dụng đơn giản, ít concurrent connections (< 100)
2. **NIO:** Dùng khi cần high concurrency, low latency. Đòi hỏi kỹ năng cao
3. **NIO.2:** Dùng cho async file I/O hoặc khi cần callback-style programming
4. **Netty:** Wrapper chuẩn công nghiệp cho NIO, dùng cho production systems
5. **Java 21 Virtual Threads:** Game changer - có thể dùng blocking code style với performance gần NIO

---

## 10. References

- [Java NIO Tutorial - Jenkov.com](http://tutorials.jenkov.com/java-nio/index.html)
- [Linux io_uring](https://kernel.dk/io_uring.pdf)
- [Netty in Action - Norman Maurer](https://www.manning.com/books/netty-in-action)
- [Java 21 Virtual Threads - JEP 444](https://openjdk.org/jeps/444)
- [Java 21 Foreign Function & Memory API - JEP 454](https://openjdk.org/jeps/454)
