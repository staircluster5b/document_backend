# 🔍 Deep Research: Java NIO.2 FileSystem API (Path, Files, FileVisitor, WatchService)

> **Phân tích chuyên sâu:** Từ cơ chế kernel-level đến ứng dụng production-grade

---

## 📋 Tóm tắt điều hành

| Thuộc tính | Giá trị |
|------------|---------|
| **Java Version** | Java 7+ (NIO.2) - JSR 203 |
| **Package** | `java.nio.file` |
| **Use Cases** | File monitoring, batch operations, async I/O, symbolic links |
| **Performance Characteristic** | Memory-mapped, event-driven, non-blocking |

---

## 1. 🧠 Bản Chất: Cơ Chế Tầng Thấp (Low-Level Mechanisms)

### 1.1 Kiến trúc NIO.2 FileSystem

```
┌─────────────────────────────────────────────────────────────┐
│                    Application Layer                        │
│         Path, Files, FileVisitor, WatchService              │
├─────────────────────────────────────────────────────────────┤
│              FileSystemProvider (SPI)                       │
│    ┌──────────┐  ┌──────────┐  ┌──────────────────────┐    │
│    │  Unix    │  │ Windows  │  │    Zip/JAR/ZFS       │    │
│    │  FileSystemProvider  │  │   (Custom FS)        │    │
│    └──────────┘  └──────────┘  └──────────────────────┘    │
├─────────────────────────────────────────────────────────────┤
│              JNI → Native System Calls                      │
│    inotify (Linux) / FSEvents (macOS) / ReadDirectoryChangesW (Windows)  │
├─────────────────────────────────────────────────────────────┤
│                    Kernel Space                             │
│              VFS (Virtual File System)                      │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 Path - Bản chất bất biến (Immutable)

```java
// Path KHÔNG phải là File. Path là "pointer" đến location trong filesystem.
public final class UnixPath implements Path {
    private final UnixFileSystem fs;
    private final byte[] path;  // Raw bytes - platform encoding
    private volatile int[] offsets;  // Cached offsets for name elements
    private volatile int hash;  // Lazy-computed hash
}
```

**Tại sao dùng `Path` thay vì `String` hoặc `File`?**

| Tiêu chí | `java.io.File` | `java.nio.file.Path` | Lý do |
|----------|---------------|---------------------|-------|
| **Immutable** | ❌ Mutable | ✅ Immutable | Thread-safe, an toàn cho concurrent |
| **Separator** | Hardcoded `/` hoặc `\` | OS-agnostic | `path.resolve("subdir")` hoạt động đa nền tảng |
| **Symbolic Link** | ❌ Không hỗ trợ native | ✅ Hỗ trợ đầy đủ | `path.toRealPath()` giải quyết symlink |
| **Performance** | Tạo object nhiều lần | Flyweight pattern | Path được cache và reuse |

### 1.3 WatchService - Kernel Event Notification

> **Cơ chế cốt lõi:** WatchService sử dụng **platform-specific kernel APIs**, KHÔNG phải polling!

| Hệ điều hành | Kernel API | Cơ chế | Giới hạn |
|--------------|-----------|--------|----------|
| **Linux** | `inotify` | Event-driven, kernel buffer | Max watches: `/proc/sys/fs/inotify/max_user_watches` (default ~8192) |
| **macOS** | `FSEvents` | Coalesced events, disk-level | Latency cao hơn inotify (~1s) |
| **Windows** | `ReadDirectoryChangesW` | Overlapped I/O | Giới hạn bởi buffer size |

```java
// Cấu trúc dữ liệu kernel-level (Linux inotify)
struct inotify_event {
    int      wd;       // Watch descriptor
    uint32_t mask;     // Event mask (CREATE, MODIFY, DELETE...)
    uint32_t cookie;   // Unique cookie để nhận diện related events
    uint32_t len;      // Length of name field
    char     name[];   // File name (null-terminated)
};
```

**⚠️ QUAN TRỌNG:** WatchService sử dụng **edge-triggered** events. Nếu bạn không consume event kịp thờ, bạn sẽ mất event!

### 1.4 FileVisitor - Depth-First Search Pattern

```java
// Tương đương với lệnh: find /path -type f -exec {}
public interface FileVisitor<T> {
    FileVisitResult preVisitDirectory(T dir, BasicFileAttributes attrs);
    FileVisitResult visitFile(T file, BasicFileAttributes attrs);
    FileVisitResult visitFileFailed(T file, IOException exc);
    FileVisitResult postVisitDirectory(T dir, IOException exc);
}
```

**Thứ tự duyệt:**
```
preVisitDirectory(/root)
  ├── visitFile(/root/file1.txt)
  ├── preVisitDirectory(/root/subdir)
  │   ├── visitFile(/root/subdir/file2.txt)
  │   └── postVisitDirectory(/root/subdir)
  └── postVisitDirectory(/root)
```

---

## 2. 🛠️ Giải Pháp & Công Cụ Chuẩn Công Nghiệp

### 2.1 So sánh Pattern: Files.walk() vs FileVisitor

| Pattern | Use Case | Performance | Complexity |
|---------|----------|-------------|------------|
| `Files.walk()` | Đơn giản, functional style | Stream-based, lazy | Thấp |
| `SimpleFileVisitor` | Custom logic tại mỗi node | Tương đương | Trung bình |
| `FileVisitor` custom | Bỏ qua nhánh, retry, async | Tối ưu nhất | Cao |

### 2.2 FileSystem Providers (Third-party)

| Provider | Mục đích | Production Ready |
|----------|----------|------------------|
| **Jimfs** (Google) | In-memory filesystem cho testing | ✅ Google Guava project |
| **ZipFileSystemProvider** | Xử lý ZIP/JAR như filesystem | ✅ Built-in Java |
| **S3FileSystemProvider** | Amazon S3 như local filesystem | ⚠️ Cần evaluate |
| **SSHJ/SFTP** | Remote filesystem qua SSH | ⚠️ Performance concerns |

### 2.3 WatchService Anti-Patterns & Best Practices

```java
// ❌ ANTI-PATTERN: Blocking chính thread
WatchService watchService = FileSystems.getDefault().newWatchService();
WatchKey key = watchService.take(); // BLOCKING FOREVER!

// ✅ BEST PRACTICE: Dedicated thread với graceful shutdown
ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "file-watcher-" + directory.getFileName());
    t.setDaemon(true); // Không ngăn JVM exit
    return t;
});

executor.submit(() -> {
    while (!Thread.currentThread().isInterrupted()) {
        WatchKey key = watchService.poll(1, TimeUnit.SECONDS); // Non-blocking
        if (key != null) processEvents(key);
    }
});
```

---

## 3. ⚠️ Rủi Ro & Đánh Đổi (Risks & Trade-offs)

### 3.1 Performance Considerations

| Vấn đề | Nguyên nhân | Giải pháp |
|--------|-------------|-----------|
| **Memory bloat** | WatchService buffer đầy | Tăng `max_user_watches`, consume events nhanh |
| **Event loss** | Kernel drop events khi quá tải | Monitor `/proc/sys/fs/inotify/max_queued_events` |
| **Symbolic link loop** | Circular symlink trong FileVisitor | Sử dụng `FileVisitOption.FOLLOW_LINKS` cẩn thận |
| **Metadata operations** | `Files.getAttribute()` chậm trên network FS | Cache metadata, dùng `BasicFileAttributes` thay vì `FileTime` |

### 3.2 Security Risks

```java
// ❌ RỦI RO: Path Traversal Attack
Path base = Paths.get("/var/www/uploads");
Path userPath = base.resolve(request.getFilename()); 
// Attacker gửi: "../../../etc/passwd"

// ✅ BẢO MẬT: Normalize và validate
Path normalized = userPath.normalize();
if (!normalized.startsWith(base)) {
    throw new SecurityException("Path traversal detected!");
}
```

### 3.3 Anti-Patterns thường gặp

| Anti-Pattern | Hậu quả | Giải pháp |
|--------------|---------|-----------|
| Tạo WatchService mới cho mỗi directory | Resource exhaustion | Dùng 1 WatchService cho nhiều directories |
| Quên `key.reset()` | WatchKey invalid, không nhận event nữa | Luôn gọi `reset()` sau khi xử lý |
| Dùng `take()` thay vì `poll()` | Thread không thể interrupt | Dùng `poll(timeout)` với interruption handling |
| Không handle `OVERFLOW` event | Mất event không báo trước | Luôn check `StandardWatchEventKinds.OVERFLOW` |

---

## 4. 🚀 Cập nhật Java 21+ Features

### 4.1 Virtual Threads (Project Loom) với NIO.2

```java
// Java 21+: Dùng Virtual Threads cho file operations
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Path> files = Files.walk(startPath)
        .filter(Files::isRegularFile)
        .toList();
    
    files.forEach(file -> executor.submit(() -> {
        // Mỗi file operation chạy trên virtual thread
        // Không block platform thread
        processFile(file);
    }));
}
```

### 4.2 Sequenced Collections (Java 21)

```java
// Java 21+: SequencedPath - Thứ tự duyệt có thể đảo ngược
// (Nếu bạn cần duyệt ngược directory tree)
List<Path> pathList = Files.walk(startPath).toList();
// Dùng SequencedCollection methods
Path last = pathList.getLast();
Path first = pathList.getFirst();
```

---

## 5. 💻 Code Demo

### Demo 1: Safe FileVisitor với Progress Tracking

```java
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicLong;

public class SafeFileTreeWalker extends SimpleFileVisitor<Path> {
    private final AtomicLong fileCount = new AtomicLong(0);
    private final AtomicLong totalSize = new AtomicLong(0);
    private final long maxDepth;
    private final Path basePath;
    
    public SafeFileTreeWalker(Path basePath, long maxDepth) {
        this.basePath = basePath;
        this.maxDepth = maxDepth;
    }
    
    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        if (attrs.isRegularFile()) {
            fileCount.incrementAndGet();
            totalSize.addAndGet(attrs.size());
        }
        return FileVisitResult.CONTINUE;
    }
    
    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
        // Không throw exception - log và tiếp tục
        System.err.println("Không thể truy cập: " + file + " - " + exc.getMessage());
        return FileVisitResult.CONTINUE;
    }
    
    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        long depth = basePath.relativize(dir).getNameCount();
        if (depth > maxDepth) {
            return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
    }
    
    public void printStats() {
        System.out.printf("Files: %d | Total Size: %d bytes%n", 
            fileCount.get(), totalSize.get());
    }
    
    public static void main(String[] args) throws IOException {
        Path start = Paths.get(".");
        SafeFileTreeWalker walker = new SafeFileTreeWalker(start, 3);
        Files.walkFileTree(start, walker);
        walker.printStats();
    }
}
```

### Demo 2: Production-Grade WatchService

```java
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class RobustFileWatcher implements AutoCloseable {
    private final WatchService watchService;
    private final ExecutorService executor;
    private final ConcurrentHashMap<WatchKey, Path> keyToPath = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    public RobustFileWatcher() throws IOException {
        this.watchService = FileSystems.getDefault().newWatchService();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "file-watcher");
            t.setDaemon(true);
            return t;
        });
    }
    
    public void watch(Path directory, FileChangeListener listener) throws IOException {
        WatchKey key = directory.register(watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE,
            StandardWatchEventKinds.OVERFLOW  // Quan trọng!
        );
        keyToPath.put(key, directory);
    }
    
    public void start() {
        if (running.compareAndSet(false, true)) {
            executor.submit(this::watchLoop);
        }
    }
    
    private void watchLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                // Dùng poll với timeout để có thể interrupt
                key = watchService.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            
            if (key == null) continue;
            
            Path dir = keyToPath.get(key);
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                
                // ⚠️ Xử lý OVERFLOW - event bị mất!
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    System.err.println("⚠️ OVERFLOW: Events bị mất, cần rescan!");
                    continue;
                }
                
                @SuppressWarnings("unchecked")
                Path filename = ((WatchEvent<Path>) event).context();
                Path fullPath = dir.resolve(filename);
                
                System.out.printf("[%s] %s%n", kind.name(), fullPath);
            }
            
            // ⚠️ QUAN TRỌNG: Reset key để tiếp tục nhận events
            boolean valid = key.reset();
            if (!valid) {
                keyToPath.remove(key);
                System.out.println("WatchKey invalid, stopping watch for: " + dir);
            }
        }
    }
    
    @Override
    public void close() {
        running.set(false);
        executor.shutdownNow();
        try {
            watchService.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    @FunctionalInterface
    public interface FileChangeListener {
        void onChange(WatchEvent.Kind<?> kind, Path path);
    }
    
    public static void main(String[] args) throws Exception {
        try (RobustFileWatcher watcher = new RobustFileWatcher()) {
            Path watchDir = Paths.get("/tmp/watch-test");
            Files.createDirectories(watchDir);
            
            watcher.watch(watchDir, (kind, path) -> 
                System.out.println("Changed: " + path));
            watcher.start();
            
            System.out.println("Watching " + watchDir + "... (Press Enter to stop)");
            System.in.read();
        }
    }
}
```

### Demo 3: Path Manipulation & Security

```java
import java.nio.file.*;

public class SecurePathUtils {
    
    /**
     * Validate path không vượt ra ngoài base directory.
     * Ngăn chặn path traversal attacks.
     */
    public static Path safeResolve(Path base, String userInput) {
        // Bước 1: Parse và normalize
        Path resolved = base.resolve(userInput).normalize();
        
        // Bước 2: Kiểm tra vẫn nằm trong base
        if (!resolved.startsWith(base.normalize())) {
            throw new SecurityException("Path traversal detected: " + userInput);
        }
        
        // Bước 3: Kiểm tra không phải symbolic link ra ngoài
        try {
            Path realPath = resolved.toRealPath();
            Path realBase = base.toRealPath();
            if (!realPath.startsWith(realBase)) {
                throw new SecurityException("Symlink escape detected: " + userInput);
            }
        } catch (IOException e) {
            throw new SecurityException("Cannot validate path: " + e.getMessage());
        }
        
        return resolved;
    }
    
    /**
     * Tạo relative path giữa 2 paths, xử lý cross-platform.
     */
    public static Path createRelative(Path from, Path to) {
        return from.relativize(to);
    }
    
    public static void main(String[] args) {
        Path base = Paths.get("/var/www/uploads");
        
        // Test cases
        String[] inputs = {
            "document.pdf",           // ✅ OK
            "subdir/image.png",       // ✅ OK
            "../../../etc/passwd",    // ❌ Blocked
            "../secret.txt"           // ❌ Blocked
        };
        
        for (String input : inputs) {
            try {
                Path result = safeResolve(base, input);
                System.out.println("✅ " + input + " -> " + result);
            } catch (SecurityException e) {
                System.out.println("🚫 " + input + " -> " + e.getMessage());
            }
        }
    }
}
```

---

## 📚 Tài liệu tham khảo

1. [Java NIO.2 File System](https://docs.oracle.com/javase/tutorial/essential/io/fileio.html) - Oracle Docs
2. [inotify man page](https://man7.org/linux/man-pages/man7/inotify.7.html) - Linux Programmer's Manual
3. [WatchService API](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/WatchService.html) - Java 17 API
4. [FileVisitor Interface](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/FileVisitor.html) - Java 17 API

---

> **Key Takeaway:** NIO.2 FileSystem API cung cấp cơ chế hiệu quả, platform-native để làm việc với files. Hiểu rõ kernel-level mechanisms (inotify, FSEvents) và các anti-patterns (quên reset key, không handle overflow) là bắt buộc cho production systems.
