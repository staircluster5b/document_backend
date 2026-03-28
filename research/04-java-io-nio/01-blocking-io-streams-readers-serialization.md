# Blocking I/O (java.io) - Streams, Readers/Writers, Serialization

## 1. Mục tiêu của task

Hiểu sâu bản chất của Blocking I/O trong Java, cơ chế hoạt động tại tầng kernel và JVM, các trade-off khi sử dụng, và tại sao Java chuyển sang NIO trong các hệ thống high-performance.

---

## 2. Bản chất và cơ chế hoạt động

### 2.1. Blocking I/O là gì ở tầng Kernel?

Blocking I/O không phải là "đặc tính của Java" — nó là **hành vi mặc định của system calls trong UNIX/Linux**. Khi một thread gọi `read()` hoặc `write()`, kernel sẽ:

1. **Chuyển thread sang trạng thái `TASK_INTERRUPTIBLE`** — thread ngủ, nhường CPU
2. **Đưa thread vào wait queue** của file descriptor đó
3. **Chờ dữ liệu sẵn sàng** (đĩa seek xong, packet đến từ network...)
4. **Wake up thread** và copy dữ liệu từ kernel space → user space

```
User Space                    Kernel Space
┌─────────┐                   ┌─────────────────────┐
│ Thread  │ ──read(fd)──────→ │ VFS Layer           │
│ (RUN)   │                   │ → File System Driver│
└─────────┘                   │ → Block I/O Layer   │
     │                        │ → Device Driver     │
     │ WAITING                │ → Disk Controller   │
     ↓                        └─────────────────────┘
┌─────────┐                           │
│ Thread  │ ←────data─────────────── wait queue
│(SLEEP)  │                           │
└─────────┘                    [Interrupt from disk]
```

> **Điểm then chốt**: Thread bị block ở **kernel level**, không phải JVM level. Dù bạn có 1000 thread, mỗi lần I/O đều tốn 1 context switch (~1-10μs) + stack memory (~1MB mặc định).

### 2.2. Java I/O Architecture - Các tầng abstraction

```
┌─────────────────────────────────────────────────────────┐
│                    Application Layer                     │
│  ObjectInputStream / ObjectOutputStream (Serialization) │
├─────────────────────────────────────────────────────────┤
│                   Decorator Layer                        │
│  BufferedInputStream / DataInputStream / PushbackStream │
├─────────────────────────────────────────────────────────┤
│                    Bridge Layer                          │
│  InputStreamReader / OutputStreamWriter (bytes→chars)   │
├─────────────────────────────────────────────────────────┤
│                   Core Stream Layer                      │
│  FileInputStream / FileOutputStream / SocketInputStream │
├─────────────────────────────────────────────────────────┤
│                   Native Layer                           │
│  JNI → native read() / write() system calls             │
└─────────────────────────────────────────────────────────┘
```

### 2.3. Stream vs Reader/Writer - Sự khác biệt căn bản

| Aspect | Stream (byte) | Reader/Writer (char) |
|--------|---------------|----------------------|
| **Đơn vị** | 1 byte (8-bit) | 1 char (16-bit UTF-16) |
| **Mục đích** | Binary data | Text data |
| **Encoding** | Không có | Phải chỉ định (UTF-8, ISO-8859-1...) |
| **Bridge class** | N/A | InputStreamReader / OutputStreamWriter |

**Quan trọng**: `Reader/Writer` là **abstraction cao hơn** dựa trên Stream. Khi bạn dùng `FileReader`, bên trong nó vẫn là `FileInputStream` + `StreamDecoder`.

```
FileReader
    │
    ├── extends InputStreamReader
    │       ├── wraps FileInputStream (byte stream)
    │       └── StreamDecoder: byte[] → char[] (encoding)
```

> **Pitfall thường gặp**: `new FileReader(file)` dùng **platform default encoding** — không portable, gây mojibake khi deploy cross-platform. Luôn dùng `new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)`.

---

## 3. Kiến trúc Decorator Pattern trong Java I/O

Java I/O sử dụng **Decorator Pattern** — đây là lý do API có vẻ "verbose" nhưng lại cực kỳ flexible.

```
InputStream (abstract)
    ├── FileInputStream (concrete source)
    ├── ByteArrayInputStream (concrete source)
    ├── SocketInputStream (concrete source)
    │
    └── FilterInputStream (decorator base)
            ├── BufferedInputStream (adds buffering)
            ├── DataInputStream (adds typed read methods)
            ├── PushbackInputStream (adds pushback)
            └── InflaterInputStream (adds decompression)
```

### 3.1. Tại sao lại thiết kế như vậy?

**Problem**: Muốn kết hợp các tính năng: buffered + compressed + data-typed reading.

**Without Decorator**:
- `BufferedFileInputStream`
- `CompressedFileInputStream`
- `DataBufferedCompressedFileInputStream`
- ...combinatorial explosion (n! subclasses)

**With Decorator**:
```java
new DataInputStream(
    new BufferedInputStream(
        new GZIPInputStream(
            new FileInputStream("data.gz")
        )
    )
);
```

> **Trade-off**: Runtime overhead của delegation chain (~nanoseconds per call) vs compile-time type safety và flexibility.

### 3.2. Buffered I/O - Tại sao quan trọng?

```
Unbuffered read (1 byte at a time):
├─ 1,000,000 bytes
├─ 1,000,000 system calls
├─ 1,000,000 context switches
└─ ~100-1000x slower

Buffered read (8KB buffer):
├─ 1,000,000 bytes
├─ ~123 system calls (1,000,000 / 8192)
├─ ~123 context switches
└─ Optimal throughput
```

**Buffer size trade-off**:

| Buffer Size | Memory | System Calls | Latency (first byte) | Throughput |
|-------------|--------|--------------|----------------------|------------|
| 1 byte | Minimal | Maximum | Minimum | Worst |
| 8KB (default) | Low | Low | Low | Good |
| 64KB | Medium | Lower | Medium | Better |
| 1MB | High | Lowest | High | Best (streaming) |

> **Rule of thumb**: 8KB-64KB là sweet spot cho hầu hết use cases. Buffer > 1MB rarely helps do L1/L2 cache miss.

---

## 4. Serialization - Cơ chế và Rủi ro

### 4.1. Object Serialization Protocol

Java Serialization không chỉ là "convert object to bytes" — nó là **full object graph persistence** với type information.

```
Object Graph:
┌─────────────┐
│   Person    │ ──► name: "John"
│  (serial)   │ ──► age: 30
└──────┬──────┘ ──► address: ───────┐
       │                            ▼
       └─────────────────────► ┌──────────┐
                               │ Address  │
                               │ (serial) │
                               └──────────┘

Serialized Format (simplified):
┌─────────────┬─────────────┬─────────────────────────────────┐
│ 0xACED      │ 0x0005      │ class descriptor + field data   │
│ (magic)     │ (version)   │ + object refs + primitive data  │
└─────────────┴─────────────┴─────────────────────────────────┘
```

**Cơ chế writeObject**:
1. Ghi `ObjectStreamClass` descriptor (class name, SUID, field signatures)
2. Duyệt object graph theo chiều sâu (DFS)
3. Ghi primitive fields trực tiếp
4. Ghi object references (handle hoặc inline)
5. Xử lý circular references (handle table)

### 4.2. serialVersionUID - Tại sao quan trọng?

```java
private static final long serialVersionUID = 1L;
```

**Mặc định nếu không khai báo**: JVM tính hash từ tất cả field, method signatures, superclass...

**Rủi ro**:
- Thêm 1 method private → hash đổi → `InvalidClassException`
- Build với compiler khác → hash có thể khác
- Không backward compatible

> **Khuyến nghị**: Luôn khai báo explicit `serialVersionUID`. Nếu class thay đổi incompatible, đổi SUID để fail fast.

### 4.3. Custom Serialization

```java
private void writeObject(ObjectOutputStream out) throws IOException {
    out.defaultWriteObject(); // Ghi default fields
    out.writeObject(sensitiveData.encrypt()); // Thêm custom logic
}

private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
    in.defaultReadObject();
    this.sensitiveData = decrypt(in.readObject());
    // Validation logic
    validateState();
}
```

**Use cases**:
- **Encryption**: Dữ liệu nhạy cảm trong object
- **Validation**: Check invariants sau deserialization
- **Transient handling**: Tính toán lại derived fields
- **Backward compatibility**: Xử lý old format

---

## 5. So sánh: Blocking I/O vs Non-blocking I/O

| Aspect | Blocking I/O (java.io) | Non-blocking I/O (java.nio) |
|--------|------------------------|----------------------------|
| **Thread model** | 1 thread/connection | Few threads, many connections |
| **Scalability** | ~10K threads max | 1M+ connections |
| **Latency** | Thread wake-up overhead | Selector notification |
| **Throughput** | Good for large data | Good for many connections |
| **Complexity** | Simple, linear code | Complex, event-driven |
| **Memory** | ~1MB/thread stack | ~KB/channel |
| **Use case** | File I/O, simple apps | High-concurrency servers |

### 5.1. Khi nào dùng Blocking I/O?

✅ **Nên dùng**:
- File I/O (disk không nhanh hơn blocking anyway)
- Ứng dụng đơn giản, ít concurrent connections (< 1000)
- Code readability quan trọng hơn scalability
- Batch processing, data pipelines

❌ **Không nên dùng**:
- Web server xử lý 100K+ concurrent connections
- Real-time chat/gaming servers
- API Gateway, proxy servers

---

## 6. Rủi ro, Anti-patterns, và Pitfall

### 6.1. Resource Leaks - Không đóng stream

```java
// ❌ ANTI-PATTERN: Resource leak nếu exception
try {
    InputStream is = new FileInputStream("file.txt");
    // ... read ...
    is.close(); // Không chạy nếu exception
} catch (IOException e) {
    // handle
}
```

```java
// ✅ CORRECT: try-with-resources (Java 7+)
try (InputStream is = new FileInputStream("file.txt")) {
    // ... read ...
} catch (IOException e) {
    // handle
}
// Auto-close, ngay cả khi exception
```

> **Rule**: Mọi Closeable phải được đóng. Dùng try-with-resources hoặc try-finally.

### 6.2. Unbuffered I/O trong loops

```java
// ❌ ANTI-PATTERN: 1M system calls
InputStream is = ...;
while ((b = is.read()) != -1) {  // read() = 1 byte
    process(b);
}
```

```java
// ✅ CORRECT: Buffered
BufferedInputStream bis = new BufferedInputStream(is);
while ((b = bis.read()) != -1) {
    process(b);
}
```

### 6.3. Not specifying charset

```java
// ❌ ANTI-PATTERN: Platform-dependent
FileReader reader = new FileReader("text.txt");

// ✅ CORRECT: Explicit charset
try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(
            new FileInputStream("text.txt"), 
            StandardCharsets.UTF_8))) {
    // ...
}
```

### 6.4. Serialization Security - **CRITICAL**

```java
// ❌ CRITICAL VULNERABILITY: Deserializing untrusted data
ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
Object obj = ois.readObject(); // Remote code execution!
```

**Java Deserialization Vulnerabilities**:
- **Gadget chains**: Attacker gửi crafted serialized object → trigger method chain → RCE
- **Known vulnerable libraries**: Commons Collections, Groovy,...

**Mitigation**:
```java
// ✅ Use ObjectInputFilter (Java 9+)
ObjectInputFilter filter = ObjectInputFilter.Config.createFilter(
    "!com.vulnerable.package.*;java.base/*;!*"
);
ois.setObjectInputFilter(filter);
```

> **Recommendation**: Tránh Java Serialization cho external APIs. Dùng JSON (Jackson), Protobuf, Avro.

### 6.5. Large file với ByteArrayOutputStream

```java
// ❌ ANTI-PATTERN: OOM với file lớn
ByteArrayOutputStream baos = new ByteArrayOutputStream();
Files.copy(Path.of("huge.iso"), baos); // Heap explosion!
```

```java
// ✅ CORRECT: Stream processing
Files.copy(Path.of("huge.iso"), Path.of("dest.iso"));
```

---

## 7. Khuyến nghị thực chiến trong Production

### 7.1. File I/O Best Practices

```java
// Modern approach (Java 11+)
try {
    String content = Files.readString(Path.of("file.txt"));
    // Hoặc
    List<String> lines = Files.readAllLines(Path.of("file.txt"));
    // Hoặc streaming
    try (Stream<String> lines = Files.lines(Path.of("file.txt"))) {
        lines.filter(...).forEach(...);
    }
} catch (IOException e) {
    throw new UncheckedIOException(e);
}
```

### 7.2. Network I/O với timeout

```java
// ❌ Không timeout = treo vĩnh viễn
Socket socket = new Socket(host, port);

// ✅ Luôn set timeout
Socket socket = new Socket();
socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
socket.setSoTimeout(readTimeoutMs);
```

### 7.3. Copy streams efficiently

```java
// Java 9+ - transferTo (zero-copy nếu kernel hỗ trợ)
try (InputStream in = ...; OutputStream out = ...) {
    in.transferTo(out);
}
```

### 7.4. Monitoring và Debugging

**Metrics cần theo dõi**:
- I/O wait time (iostat, /proc/diskstats)
- Thread state: BLOCKED threads
- File descriptor usage: `lsof -p <pid> | wc -l`
- Buffer pool hit ratio

**Tools**:
- `async-profiler`: Xem threads đang blocked ở đâu
- `strace -f -e trace=read,write`: Trace system calls
- `jfr` (Java Flight Recorder): I/O events

---

## 8. Kết luận

### Bản chất cốt lõi

**Blocking I/O** không phải là "lỗi thời" — nó là abstraction đơn giản, phù hợp cho hầu hết use cases thông thường. Rủi ro thực sự không nằm ở blocking nature, mà ở:

1. **Thread per connection** model không scalable
2. **Resource leaks** từ không đóng stream đúng cách
3. **Security vulnerabilities** từ deserialization
4. **Platform-dependent behavior** từ default encoding

### Trade-off quan trọng nhất

**Simplicity vs Scalability**: Blocking I/O code dễ viết, dễ debug, nhưng không scale với 10K+ connections. Chuyển sang NIO/Reactive khi và chỉ khi measurements chứng minh cần thiết.

### Quyết định kiến trúc

| Tình huống | Lựa chọn |
|------------|----------|
| File processing | Blocking I/O với buffering |
| < 1000 concurrent connections | Blocking I/O + Thread pool |
| > 10K connections, real-time | NIO/Netty/Async |
| Inter-service communication | gRPC (HTTP/2) hoặc Message Queue |
| Caching layer | Redis (không dùng Java I/O trực tiếp) |

---

## 9. Code minh họa (chỉ khi cần thiết)

### Ví dụ: Đọc file text hiệu quả

```java
public List<String> readLinesEfficiently(Path path) throws IOException {
    // BufferedReader: 8KB buffer mặc định
    // UTF-8: explicit encoding
    // try-with-resources: auto-close
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
        return reader.lines()
                     .filter(line -> !line.trim().isEmpty())
                     .collect(Collectors.toList());
    }
}
```

### Ví dụ: Custom serialization an toàn

```java
public class SecureData implements Serializable {
    private static final long serialVersionUID = 2024L;
    
    private transient String sensitiveInfo; // Không serialize trực tiếp
    private final String encryptedData;
    
    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        // Encrypt trước khi ghi
        out.writeObject(AESUtil.encrypt(sensitiveInfo));
    }
    
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        // Decrypt sau khi đọc
        String encrypted = (String) in.readObject();
        this.sensitiveInfo = AESUtil.decrypt(encrypted);
        
        // Validation
        if (this.sensitiveInfo == null || this.sensitiveInfo.isEmpty()) {
            throw new InvalidObjectException("Invalid sensitive data");
        }
    }
}
```

---

## References

- [Java I/O, NIO and NIO.2 - Jeff Friesen (Apress)](https://www.apress.com/gp/book/9781484215661)
- [UNIX Network Programming - W. Richard Stevens](https://www.amazon.com/UNIX-Network-Programming-Sockets-Networking/dp/0131411551)
- [Java Object Serialization Specification](https://docs.oracle.com/javase/8/docs/platform/serialization/spec/serialTOC.html)
- [OWASP Deserialization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Deserialization_Cheat_Sheet.html)
