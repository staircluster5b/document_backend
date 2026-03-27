# Blocking vs Non-blocking I/O: Java IO, NIO, và NIO.2 Deep Dive

> **Tóm tắt:** Phân tích sâu về cơ chế I/O trong Java từ Blocking IO truyền thống đến Non-blocking NIO và Asynchronous NIO.2, bao gồm Selector, Channel, Buffer, và ứng dụng thực tế trong high-performance systems.

---

## 📊 Bảng So Sánh Tổng Quan

| Đặc điểm | Java IO (java.io) | Java NIO (java.nio) | Java NIO.2 (java.nio.file) |
|----------|-------------------|---------------------|----------------------------|
| **API Style** | Stream-oriented | Channel + Buffer | Asynchronous + Path-based |
| **Blocking** | Blocking hoàn toàn | Non-blocking (configurable) | Asynchronous |
| **Data Handling** | Byte-by-byte/Char-by-char | Block-based (Buffer) | CompletionHandler/Future |
| **Multiplexing** | 1 thread/connection | Selector (1 thread/N connections) | AsynchronousChannelGroup |
| **Use Case** | Simple file operations | High-performance networking | Async file operations |
| **Java Version** | Since 1.0 | Java 1.4 (2002) | Java 7 (2011) |

---

## 🔴 1. Java IO (Blocking I/O) - Bản Chất

### 1.1 Cơ chế hoạt động

```
Thread-per-Connection Model
┌─────────────────────────────────────────────────────────────┐
│                    Server Socket                            │
│                         │                                   │
│                         ▼                                   │
│              accept() ──► BLOCKING                          │
│                         │                                   │
│              ┌──────────┴──────────┐                       │
│              ▼                     ▼                       │
│         Client 1              Client 2                     │
│      (Thread #1)           (Thread #2)                     │
│      read() ──► BLOCK    read() ──► BLOCK                  │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 Cấu trúc nội tại

```java
// Java IO sử dụng Stream abstraction
public class InputStream {
    // Native call đến OS read()
    public abstract int read() throws IOException;
    
    // Blocking cho đến khi có data
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }
}
```

**Cơ chế Blocking:**
- Mỗi `read()`/`write()` là system call đến kernel
- Thread chuyển sang `WAITING` state
- OS scheduler context switch thread khác
- Khi data sẵn sàng, thread được đánh thức

### 1.3 Vấn đề với Thread-per-Connection

```java
// ❌ Anti-pattern: Thread-per-connection
public class BlockingServer {
    public void start(int port) throws IOException {
        ServerSocket server = new ServerSocket(port);
        
        while (true) {
            Socket client = server.accept(); // BLOCKING
            // Mỗi client = 1 thread mới
            new Thread(() -> handle(client)).start();
        }
    }
    
    private void handle(Socket client) {
        BufferedReader in = new BufferedReader(
            new InputStreamReader(client.getInputStream())
        );
        String line;
        while ((line = in.readLine()) != null) { // BLOCKING
            process(line);
        }
    }
}
```

> 🚨 **Rủi ro C10K Problem:**
> - 10,000 connections = 10,000 threads
> - Mỗi thread stack: ~1MB (mặc định) → 10GB memory!
> - Context switching overhead
> - Thread creation/destruction cost

### 1.4 Big O Analysis

| Thao tác | Độ phức tạp | Ghi chú |
|----------|-------------|---------|
| `read()` | **O(1)** | System call, nhưng BLOCKING |
| `write()` | **O(n)** | n = bytes written |
| Thread creation | **O(1)** | ~ms overhead |
| Context switch | **O(1)** | ~μs overhead |
| Memory/thread | **O(1)** | ~1MB stack |

---

## 🟢 2. Java NIO (Non-blocking I/O) - Bản Chất

### 2.1 Three Core Components

```
┌───────────────────────────────────────────────────────────────┐
│                     NIO Architecture                           │
├───────────────────────────────────────────────────────────────┤
│                                                               │
│   ┌─────────────┐    ┌─────────────┐    ┌─────────────┐      │
│   │  Channel    │◄──►│   Buffer    │    │  Selector   │      │
│   │             │    │             │    │             │      │
│   │ • File      │    │ • Capacity  │    │ • Register  │      │
│   │ • Socket    │    │ • Position  │    │ • Select    │      │
│   │ • Server    │    │ • Limit     │    │ • Keys      │      │
│   │   Socket    │    │ • Mark      │    │             │      │
│   └─────────────┘    └─────────────┘    └─────────────┘      │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

### 2.2 Buffer - Trái tim của NIO

```java
// Buffer là container cho primitive data types
public abstract class Buffer {
    // Invariant: mark <= position <= limit <= capacity
    private int mark = -1;
    private int position = 0;  // Vị trí đọc/ghi tiếp theo
    private int limit;         // Giới hạn đọc/ghi
    private int capacity;      // Dung lượng tối đa
    
    // State transitions
    public final Buffer flip() {    // Write mode → Read mode
        limit = position;
        position = 0;
        mark = -1;
        return this;
    }
    
    public final Buffer clear() {   // Reset về write mode
        position = 0;
        limit = capacity;
        mark = -1;
        return this;
    }
}
```

**Buffer State Machine:**

```
        clear()                flip()                clear()
    ┌─────────────┐       ┌─────────────┐       ┌─────────────┐
    │  WRITE MODE │ ─────►│  READ MODE  │ ─────►│  WRITE MODE │
    │  [0000....] │       │  [PPPP0000] │       │  [0000....] │
    │    position │       │  P = data   │       │  (data bị   │
    │    ▲        │       │  position   │       │   ghi đè)   │
    └─────────────┘       └─────────────┘       └─────────────┘
```

### 2.3 Channel - Đường ống dữ liệu

```java
// Channel là gateway để đọc/ghi với Buffer
public interface Channel extends Closeable {
    boolean isOpen();
    void close() throws IOException;
}

public interface ReadableByteChannel extends Channel {
    int read(ByteBuffer dst) throws IOException; // Đọc vào buffer
}

public interface WritableByteChannel extends Channel {
    int write(ByteBuffer src) throws IOException; // Ghi từ buffer
}
```

**Channel types:**

| Channel | Mục đích | Blocking Mode |
|---------|----------|---------------|
| `FileChannel` | File operations | Có thể non-blocking (với pipe) |
| `SocketChannel` | TCP client | Configurable |
| `ServerSocketChannel` | TCP server | Configurable |
| `DatagramChannel` | UDP | Configurable |
| `Pipe.Sink/SourceChannel` | Inter-thread communication | Non-blocking |

### 2.4 Selector - Multiplexing Magic

```java
// 1 thread quản lý N channels
public class Selector {
    // Đăng ký channel với các operation quan tâm
    public abstract SelectionKey register(
        Selector sel, 
        int ops,      // OP_READ | OP_WRITE | OP_CONNECT | OP_ACCEPT
        Object att    // Attachment (context)
    );
    
    // Blocking cho đến khi ít nhất 1 channel ready
    public abstract int select() throws IOException;
    public abstract int select(long timeout) throws IOException;
    public abstract int selectNow() throws IOException; // Non-blocking
}
```

**Selector Architecture:**

```
              Selector (1 Thread)
                    │
    ┌───────────────┼───────────────┐
    ▼               ▼               ▼
 Channel 1      Channel 2      Channel 3  ...  Channel N
 (OP_READ)     (OP_WRITE)     (OP_READ)       (OP_ACCEPT)
    │               │               │               │
    ▼               ▼               ▼               ▼
  Ready?          Ready?          Ready?          Ready?
    │               │               │               │
    └───────────────┴───────────────┴───────────────┘
                    │
              Selected Keys
                    │
            Process từng ready key
```

### 2.5 Reactor Pattern với NIO

```java
// ✅ High-performance NIO Server (Reactor Pattern)
public class NIOServer {
    private Selector selector;
    
    public void start(int port) throws IOException {
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false); // NON-BLOCKING
        
        selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        
        while (true) {
            selector.select(); // BLOCKING chỉ ở đây
            
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> it = selectedKeys.iterator();
            
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove(); // Quan trọng: phải remove!
                
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
        SocketChannel client = server.accept(); // Non-blocking
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ);
    }
    
    private void read(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        int read = channel.read(buffer); // Non-blocking
        
        if (read == -1) {
            key.cancel();
            channel.close();
            return;
        }
        
        buffer.flip();
        // Process data...
        
        // Chuyển sang write mode nếu cần response
        key.interestOps(SelectionKey.OP_WRITE);
    }
}
```

> ⚠️ **Rủi ro:**
> - **Busy-waiting:** Nếu dùng `selectNow()` liên tục mà không sleep
> - **Buffer underflow/overflow:** Không kiểm tra remaining()
> - **Partial writes:** `write()` có thể không ghi hết, cần re-register OP_WRITE

---

## 🔵 3. Java NIO.2 (Asynchronous I/O) - Bản Chất

### 3.1 Cơ chế Asynchronous

```
Traditional NIO:                         NIO.2 Async:
┌───────────────┐                       ┌───────────────┐
│ Thread gọi    │                       │ Thread gọi    │
│ select()      │                       │ read/write    │
│       │       │                       │       │       │
│       ▼       │                       │       ▼       │
│  BLOCKING     │                       │  Return       │
│  (chờ ready)  │                       │  ngay lập tức │
│       │       │                       │       │       │
│       ▼       │                       │       ▼       │
│  Process      │                       │  OS Kernel    │
│               │                       │  xử lý I/O    │
│               │                       │       │       │
│               │                       │       ▼       │
│               │                       │  Callback/    │
│               │                       │  Future       │
└───────────────┘                       └───────────────┘
```

### 3.2 AsynchronousChannel API

```java
// Callback-based API
public interface AsynchronousByteChannel 
    extends AsynchronousChannel {
    
    <A> void read(
        ByteBuffer dst,
        A attachment,
        CompletionHandler<Integer, ? super A> handler
    );
    
    Future<Integer> read(ByteBuffer dst);
}

// CompletionHandler callback
public interface CompletionHandler<V, A> {
    void completed(V result, A attachment); // Success callback
    void failed(Throwable exc, A attachment); // Error callback
}
```

### 3.3 AsynchronousFileChannel

```java
public class AsyncFileExample {
    public void readFile(String path) throws IOException {
        AsynchronousFileChannel channel = 
            AsynchronousFileChannel.open(
                Paths.get(path),
                StandardOpenOption.READ
            );
        
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        long position = 0;
        
        // Callback-based
        channel.read(buffer, position, buffer,
            new CompletionHandler<Integer, ByteBuffer>() {
                @Override
                public void completed(Integer bytesRead, ByteBuffer buf) {
                    System.out.println("Read " + bytesRead + " bytes");
                    buf.flip();
                    // Process data...
                    
                    // Continue reading
                    if (bytesRead > 0) {
                        channel.read(buf, position + bytesRead, buf, this);
                    }
                }
                
                @Override
                public void failed(Throwable exc, ByteBuffer buf) {
                    exc.printStackTrace();
                }
            }
        );
        
        // Future-based (blocking style)
        Future<Integer> future = channel.read(buffer, position);
        Integer bytesRead = future.get(); // Blocking
    }
}
```

### 3.4 AsynchronousServerSocketChannel

```java
// High-performance async server
public class AsyncServer {
    private AsynchronousServerSocketChannel server;
    
    public void start(int port) throws IOException {
        server = AsynchronousServerSocketChannel.open();
        server.bind(new InetSocketAddress(port));
        
        // Bắt đầu accept - không blocking
        server.accept(null, new AcceptHandler());
    }
    
    private class AcceptHandler 
        implements CompletionHandler<AsynchronousSocketChannel, Void> {
        
        @Override
        public void completed(AsynchronousSocketChannel client, Void att) {
            // Tiếp tục accept connection tiếp theo
            server.accept(null, this);
            
            // Xử lý client mới
            handleClient(client);
        }
        
        @Override
        public void failed(Throwable exc, Void att) {
            exc.printStackTrace();
        }
    }
    
    private void handleClient(AsynchronousSocketChannel client) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        
        client.read(buffer, buffer, new CompletionHandler<>() {
            @Override
            public void completed(Integer bytesRead, ByteBuffer buf) {
                if (bytesRead == -1) {
                    close(client);
                    return;
                }
                
                buf.flip();
                process(buf);
                
                // Đọc tiếp
                buf.clear();
                client.read(buf, buf, this);
            }
            
            @Override
            public void failed(Throwable exc, ByteBuffer buf) {
                exc.printStackTrace();
                close(client);
            }
        });
    }
}
```

### 3.5 File System API (java.nio.file)

```java
// Modern file operations
public class ModernFileOps {
    public void examples() throws IOException {
        Path path = Paths.get("/tmp/data.txt");
        
        // Đọc toàn bộ file
        List<String> lines = Files.readAllLines(path, UTF_8);
        byte[] bytes = Files.readAllBytes(path);
        
        // Ghi file
        Files.write(path, "Hello".getBytes());
        Files.write(path, lines, UTF_8, 
            StandardOpenOption.APPEND,
            StandardOpenOption.CREATE
        );
        
        // Stream API - lazy loading
        try (Stream<String> stream = Files.lines(path)) {
            stream.filter(line -> line.contains("ERROR"))
                  .forEach(System.out::println);
        }
        
        // Walk directory tree
        try (Stream<Path> paths = Files.walk(Paths.get("/tmp"))) {
            paths.filter(Files::isRegularFile)
                 .forEach(System.out::println);
        }
        
        // WatchService - File system monitoring
        WatchService watchService = FileSystems.getDefault()
            .newWatchService();
        path.getParent().register(watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE
        );
    }
}
```

---

## 📈 4. So Sánh Performance và Use Cases

### 4.1 Performance Benchmark

```
Scenario: 10,000 concurrent connections, xử lý message 1KB

                    Blocking IO    NIO         NIO.2 Async
─────────────────────────────────────────────────────────────
Threads             10,000         2-4         2-4
Memory (MB)         ~10,000        ~50         ~50
Latency (avg)       5ms            2ms         1.5ms
Throughput          2,000 req/s    50,000+     60,000+ req/s
CPU Usage           High           Medium      Low-Medium
Complexity          Low            High        Medium
─────────────────────────────────────────────────────────────
```

### 4.2 Decision Matrix

| Use Case | Khuyến nghị | Lý do |
|----------|-------------|-------|
| Simple file read/write | `Files.readAllLines()` | Code ngắn, đủ nhanh |
| Large file processing | `FileChannel` + `MappedByteBuffer` | Memory-mapped, zero-copy |
| Simple TCP server (<1000 conn) | Blocking IO + ThreadPool | Đơn giản, dễ maintain |
| High-concurrency server (>10K conn) | NIO Selector | Resource efficient |
| Async file operations | NIO.2 `AsynchronousFileChannel` | Non-blocking I/O |
| File watching/monitoring | `WatchService` | Native OS support |

---

## ⚠️ 5. Rủi Ro và Anti-patterns

### 5.1 NIO Buffer Anti-patterns

```java
// ❌ Quên flip() trước khi đọc
buffer.put(data);
channel.write(buffer); // Ghi cả phần trống!

// ✅ Đúng
buffer.put(data);
buffer.flip(); // Giới hạn limit = position, position = 0
channel.write(buffer);
buffer.clear(); // Reset cho lần ghi tiếp

// ❌ Không kiểm tra remaining()
while (buffer.hasRemaining()) {
    channel.write(buffer); // Có thể partial write
}

// ✅ Đúng: loop cho đến khi hết
while (buffer.hasRemaining()) {
    if (channel.write(buffer) == 0) {
        // Buffer full, cần register OP_WRITE và đợi
        key.interestOps(SelectionKey.OP_WRITE);
        break;
    }
}
```

### 5.2 Selector Anti-patterns

```java
// ❌ Không remove() key sau khi process
while (it.hasNext()) {
    SelectionKey key = it.next();
    // Process...
} // Key vẫn còn trong selectedKeys!

// ✅ Đúng
while (it.hasNext()) {
    SelectionKey key = it.next();
    it.remove(); // Remove ngay
    // Process...
}

// ❌ Select trong loop không sleep (busy-wait)
while (true) {
    selector.selectNow(); // Non-blocking
    // Process... (có thể không có gì để process)
}

// ✅ Đúng
while (true) {
    selector.select(); // Blocking, hiệu quả hơn
    // Process...
}
```

### 5.3 Asynchronous Anti-patterns

```java
// ❌ Blocking trong CompletionHandler (giết chết lợi ích async)
channel.read(buffer, null, new CompletionHandler<>() {
    @Override
    public void completed(Integer result, Void att) {
        blockingDatabaseCall(); // ❌ KHÔNG!
        // Làm chậm thread pool của async channel
    }
});

// ✅ Đúng: Delegate đến thread pool riêng
ExecutorService executor = Executors.newFixedThreadPool(10);

channel.read(buffer, null, new CompletionHandler<>() {
    @Override
    public void completed(Integer result, Void att) {
        executor.submit(() -> {
            blockingDatabaseCall(); // ✅ OK
        });
    }
});
```

### 5.4 Memory Leaks

```java
// ❌ Không đóng Channel/Selector
Selector selector = Selector.open();
// ... use selector
// Quên close() → File descriptor leak

// ✅ Đúng: try-with-resources
try (Selector selector = Selector.open();
     ServerSocketChannel channel = ServerSocketChannel.open()) {
    // ... use
} // Auto-close

// ❌ Không clear buffer pool
List<ByteBuffer> pool = new ArrayList<>();
// Lấy buffer nhưng không trả lại

// ✅ Đúng: Object pooling đúng cách
Queue<ByteBuffer> pool = new ArrayDeque<>();
ByteBuffer buf = pool.poll(); // Lấy
// ... use
pool.offer(buf); // Trả lại
```

---

## 🚀 6. Java 21+ Virtual Threads

### 6.1 Virtual Threads và I/O

```java
// Java 21+: Virtual threads + Blocking I/O = Best of both worlds
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 100000; i++) {
        executor.submit(() -> {
            // Blocking I/O nhưng không block OS thread!
            try (var socket = new Socket(host, port)) {
                // ... handle connection
            }
        });
    }
}
```

### 6.2 Virtual Threads vs NIO

| Aspect | Virtual Threads | NIO Selector |
|--------|-----------------|--------------|
| Code complexity | Simple (blocking style) | Complex (callback/state machine) |
| Scalability | 1M+ concurrent | 100K+ concurrent |
| Migration cost | Low | High |
| Debugging | Easy (stack traces) | Hard (async stack) |
| Use case | New projects | Legacy optimization |

> 💡 **Recommendation:** Với Java 21+, ưu tiên Virtual Threads thay vì NIO cho đa số use cases. Chỉ dùng NIO khi cần absolute maximum performance hoặc legacy support.

---

## 📝 7. Tóm Tắt

### 7.1 Key Takeaways

1. **Blocking IO:** Đơn giản, dễ code, nhưng không scalable (>1K connections)
2. **NIO:** High-performance, resource-efficient, nhưng phức tạp, dễ sai
3. **NIO.2:** Async API, cleaner code hơn NIO, tốt cho file operations
4. **Java 21 Virtual Threads:** Lựa chọn tốt nhất cho new projects

### 7.2 Checklist khi chọn I/O approach

```
□ Cần xử lý >10K concurrent connections?
   ├── NO → Blocking IO hoặc Virtual Threads
   └── YES → Virtual Threads (Java 21+) hoặc NIO/NIO.2

□ Code phải chạy trên Java < 21?
   ├── NO → Virtual Threads
   └── YES → NIO cho networking, NIO.2 cho file async

□ Performance là critical hơn maintainability?
   ├── NO → Virtual Threads hoặc Blocking IO
   └── YES → NIO với careful optimization
```

---

## 🔗 References

1. [OpenJDK NIO Source](https://github.com/openjdk/jdk/tree/master/src/java.base/share/classes/java/nio)
2. [Java NIO Tutorial - Oracle](https://docs.oracle.com/javase/tutorial/essential/io/index.html)
3. [Scalable IO in Java - Doug Lea](http://gee.cs.oswego.edu/dl/cpjslides/nio.pdf)
4. [Java 21 Virtual Threads - JEP 444](https://openjdk.org/jeps/444)
5. [Netty Framework](https://netty.io/) - Production NIO framework

---

*Research completed: 2026-03-27*  
*Author: Senior Backend Architect Agent*
