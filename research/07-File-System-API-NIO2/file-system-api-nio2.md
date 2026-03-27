# File System API (NIO.2): Phân tích chuyên sâu Path, Files, FileVisitor, WatchService

> **Mục tiêu:** Thấu hiểu bản chất NIO.2 File System API, cơ chế vận hành tầng kernel, trade-off giữa các phương pháp monitoring, và áp dụng đúng trong production.

---

## 📋 Tóm tắt kiến thức cốt lõi

| Thành phần | Bản chất | Use Case chính | Rủi ro production |
|------------|----------|----------------|-------------------|
| **Path** | Immutable sequence của path components | Thay thế `java.io.File`, symbolic link aware | Path normalization, relative vs absolute confusion |
| **Files** | Utility class static methods | CRUD operations, attributes, permissions | Race conditions, TOCTOU attacks |
| **FileVisitor** | Visitor pattern cho tree traversal | Recursive operations, backup, indexing | Stack overflow với deep hierarchy, circular symlink |
| **WatchService** | Kernel-level event notification | Real-time file monitoring | Resource exhaustion, event loss, platform differences |
| **FileSystem** | Abstraction cho file system providers | ZIP/JAR file system, custom providers | Resource leak, provider lifecycle |

---

## 1. Bản chất tầng thấp: Kernel vs Userspace

### 1.1 Kiến trúc tầng hệ điều hành

```
┌─────────────────────────────────────────────────────────────────────┐
│                         User Space (JVM)                             │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐     │
│  │    Path    │  │   Files    │  │ FileVisitor│  │WatchService│     │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘     │
└────────┼───────────────┼───────────────┼───────────────┼────────────┘
         │               │               │               │
         ▼               ▼               ▼               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      JNI / Native Code                               │
│         (libnio.so, libjava.so trên Linux/macOS)                     │
└─────────────────────────────────────────────────────────────────────┘
         │               │               │               │
         ▼               ▼               ▼               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Kernel Space                                     │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐     │
│  │  open()    │  │   stat()   │  │  nftw()    │  │inotify/    │     │
│  │  read()    │  │  chmod()   │  │  fts_*()   │  │FSEvents/   │     │
│  │  write()   │  │  chown()   │  │            │  │kqueue/     │     │
│  │  close()   │  │  unlink()  │  │            │  │ReadDirectory│    │
│  │            │  │  rename()  │  │            │  │Events      │     │
│  └────────────┘  └────────────┘  └────────────┘  └────────────┘     │
│                                                                      │
│  Linux: inotify (efficient) / poll (fallback)                        │
│  macOS: FSEvents (stream-based) / kqueue (BSD legacy)               │
│  Windows: ReadDirectoryChangesW (overlapped I/O)                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.2 Path - Tại sao thay thế java.io.File?

```java
// ❌ ANTI-PATTERN: File class vấn đề
checkFile.exists(); // Race condition! File có thể bị xóa ngay sau khi check
checkFile.delete(); // Không có thông báo lỗi chi tiết

// ✅ Path là immutable, thread-safe, symbolic-link aware
Path path = Paths.get("/home/user/data");
Path normalized = path.normalize();     // Loại bỏ ".." và "."
Path absolute = path.toAbsolutePath();  // Resolve relative path
Path realPath = path.toRealPath();      // Resolve symbolic links
```

**Bản chất Path:**
- Immutable value object - an toàn cho concurrency
- Platform-independent abstraction (Windows `\` vs Unix `/`)
- URI-compatible: `Paths.get(URI.create("file:///home/user/file.txt"))`
- Symbolic link aware: `toRealPath(LinkOption.NOFOLLOW_LINKS)`

---

## 2. Files Class - Operations & Atomicity

### 2.1 Race Conditions & TOCTOU (Time-of-Check to Time-of-Use)

```java
// ❌ TOCTOU Vulnerability - Security Risk
if (Files.exists(path)) {           // CHECK
    // Attacker deletes file here!
    Files.delete(path);              // USE - Ném NoSuchFileException
}

// ✅ Atomic Operations - No race condition
try {
    Files.deleteIfExists(path);      // Atomic check-and-delete
} catch (DirectoryNotEmptyException e) {
    // Xử lý rõ ràng
}

// ✅ Atomic Move (rename) - Không có window giữa copy và delete
Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
```

### 2.2 Copy Options & Trade-offs

```java
// High-level copy với metadata preservation
Files.copy(source, target,
    StandardCopyOption.REPLACE_EXISTING,     // Ghi đè nếu tồn tại
    StandardCopyOption.COPY_ATTRIBUTES,      // Giữ permissions, timestamps
    LinkOption.NOFOLLOW_LINKS                // Copy symlink thay vì target
);

// Bulk copy với progress tracking (Production pattern)
public void copyWithProgress(Path source, Path target, 
                             Consumer<Long> progressCallback) throws IOException {
    long fileSize = Files.size(source);
    long copied = 0;
    
    try (InputStream in = Files.newInputStream(source);
         OutputStream out = Files.newOutputStream(target)) {
        
        byte[] buffer = new byte[8192]; // 8KB optimal cho disk I/O
        int bytesRead;
        
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            copied += bytesRead;
            progressCallback.accept((copied * 100) / fileSize);
        }
    }
}
```

| Option | Ý nghĩa | Trade-off |
|--------|---------|-----------|
| `REPLACE_EXISTING` | Ghi đè file đích | Mất data cũ - cần backup |
| `COPY_ATTRIBUTES` | Copy POSIX permissions, timestamps | Chậm hơn ~10% |
| `ATOMIC_MOVE` | Move atomic (rename) | Chỉ trong cùng file system |
| `NOFOLLOW_LINKS` | Copy symlink thay vì target | Có thể tạo broken symlink |

---

## 3. FileVisitor - Tree Traversal Pattern

### 3.1 Cơ chế Depth-First Traversal

```
Directory Structure:
/home/user/
├── docs/
│   ├── file1.txt
│   └── file2.txt
├── data/ (symlink → /mnt/data)
└── temp/
    └── nested/
        └── deep.txt

Traversal Order (preVisitDirectory):
1. preVisitDirectory(/home/user)
2. visitFile(file1.txt)
3. visitFile(file2.txt)
4. postVisitDirectory(/home/user/docs)
5. preVisitDirectory(/home/user/data) [symlink]
6. preVisitDirectory(/home/user/temp)
7. preVisitDirectory(/home/user/temp/nested)
8. visitFile(deep.txt)
9. postVisitDirectory(nested)
10. postVisitDirectory(temp)
11. postVisitDirectory(/home/user)
```

### 3.2 Implementation Pattern - Backup Tool

```java
public class BackupFileVisitor extends SimpleFileVisitor<Path> {
    private final Path sourceRoot;
    private final Path backupRoot;
    private final List<Path> failedPaths = new ArrayList<>();
    private final Set<FileVisitOption> options;
    
    public BackupFileVisitor(Path sourceRoot, Path backupRoot, 
                            boolean followSymlinks) {
        this.sourceRoot = sourceRoot;
        this.backupRoot = backupRoot;
        this.options = followSymlinks 
            ? EnumSet.of(FileVisitOption.FOLLOW_LINKS)
            : EnumSet.noneOf(FileVisitOption.class);
    }
    
    @Override
    public FileVisitResult preVisitDirectory(Path dir, 
                                             BasicFileAttributes attrs) {
        try {
            Path relative = sourceRoot.relativize(dir);
            Path backupDir = backupRoot.resolve(relative);
            Files.createDirectories(backupDir);
            
            // Copy directory permissions
            Files.setPosixFilePermissions(backupDir, 
                Files.getPosixFilePermissions(dir));
            
            return FileVisitResult.CONTINUE;
        } catch (IOException e) {
            failedPaths.add(dir);
            // SKIP_SUBTREE: Bỏ qua toàn bộ subtree này
            return FileVisitResult.SKIP_SUBTREE;
        }
    }
    
    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        try {
            Path relative = sourceRoot.relativize(file);
            Path backupFile = backupRoot.resolve(relative);
            
            // Chỉ copy nếu file thay đổi (size hoặc modified time)
            if (!Files.exists(backupFile) || 
                Files.getLastModifiedTime(file).compareTo(
                    Files.getLastModifiedTime(backupFile)) > 0) {
                Files.copy(file, backupFile, StandardCopyOption.REPLACE_EXISTING,
                          StandardCopyOption.COPY_ATTRIBUTES);
            }
            
            return FileVisitResult.CONTINUE;
        } catch (IOException e) {
            failedPaths.add(file);
            // CONTINUE: Tiếp tục với file khác
            return FileVisitResult.CONTINUE;
        }
    }
    
    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
        // Permission denied, broken symlink, etc.
        failedPaths.add(file);
        logger.warn("Failed to visit: {}", file, exc);
        return FileVisitResult.CONTINUE;
    }
    
    // Usage
    public void execute() throws IOException {
        Files.walkFileTree(sourceRoot, options, Integer.MAX_VALUE, this);
    }
}
```

### 3.3 Circular Symlink Detection

```java
// ❌ StackOverflowError với circular symlink
// /a/b/c → /a (circular reference)

// ✅ Detection với cycle tracking
public class SafeFileVisitor extends SimpleFileVisitor<Path> {
    private final Set<Path> visitedRealPaths = Collections.newSetFromMap(
        new ConcurrentHashMap<>() // Thread-safe nếu cần
    );
    
    @Override
    public FileVisitResult preVisitDirectory(Path dir, 
                                             BasicFileAttributes attrs) 
            throws IOException {
        Path realPath = dir.toRealPath();
        
        if (!visitedRealPaths.add(realPath)) {
            // Circular symlink detected!
            logger.warn("Circular symlink detected: {}", dir);
            return FileVisitResult.SKIP_SUBTREE;
        }
        
        return FileVisitResult.CONTINUE;
    }
}
```

---

## 4. WatchService - Kernel-Level File Monitoring

### 4.1 Cơ chế Kernel Implementation

```
┌─────────────────────────────────────────────────────────────────┐
│                    Application (JVM)                             │
│                     WatchService Thread                          │
│                          │                                       │
│                          ▼                                       │
│  ┌─────────────────────────────────────────┐                    │
│  │  watchService.take() / poll()           │  ← Blocks đợi event│
│  │  (Queue của WatchKey)                   │                    │
│  └─────────────────────────────────────────┘                    │
└─────────────────────────────────────────────────────────────────┘
                          │
                          ▼ JNI
┌─────────────────────────────────────────────────────────────────┐
│                       Kernel Space                               │
│                                                                  │
│  Linux: inotify                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  inotify_init() → fd                                    │    │
│  │  inotify_add_watch(fd, "/path", IN_MODIFY|IN_CREATE)   │    │
│  │  read(fd) → events (struct inotify_event)               │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  macOS: FSEvents (Grand Central Dispatch)                       │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  FSEventStreamCreate() → StreamRef                      │    │
│  │  FSEventStreamStart()                                   │    │
│  │  Callback on dispatch queue khi có thay đổi             │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  Windows: ReadDirectoryChangesW                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  CreateFile() với FILE_FLAG_BACKUP_SEMANTICS            │    │
│  │  ReadDirectoryChangesW() với overlapped I/O             │    │
│  │  GetQueuedCompletionStatus() đợi completion             │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 4.2 Production-Ready WatchService Pattern

```java
public class FileWatcherService implements AutoCloseable {
    private final WatchService watchService;
    private final Map<WatchKey, Path> keyToPath = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final Consumer<WatchEvent<?>> eventHandler;
    private volatile boolean running = true;
    
    public FileWatcherService(Consumer<WatchEvent<?>> eventHandler) 
            throws IOException {
        this.watchService = FileSystems.getDefault().newWatchService();
        this.eventHandler = eventHandler;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "file-watcher");
            t.setDaemon(true);
            return t;
        });
    }
    
    public void register(Path path, WatchEvent.Kind<?>... kinds) 
            throws IOException {
        // SensitivityWatchEventModifier.HIGH cho real-time monitoring
        // (Platform-specific, may be ignored)
        WatchKey key = path.register(watchService, kinds,
            SensitivityWatchEventModifier.HIGH);
        keyToPath.put(key, path);
    }
    
    public void start() {
        executor.submit(this::watchLoop);
    }
    
    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                // Block cho đến khi có event
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            
            Path basePath = keyToPath.get(key);
            if (basePath == null) {
                continue;
            }
            
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                
                // OVERFLOW: Buffer đầy, events bị mất!
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    logger.error("WatchService overflow! Events may be lost.");
                    handleOverflow(basePath);
                    continue;
                }
                
                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path context = ev.context(); // File name (không phải full path)
                Path fullPath = basePath.resolve(context);
                
                logger.debug("Event: {} on {}", kind.name(), fullPath);
                eventHandler.accept(event);
            }
            
            // ⭐ QUAN TRỌNG: Reset key để tiếp tục nhận events
            boolean valid = key.reset();
            if (!valid) {
                // Directory bị xóa hoặc không còn accessible
                keyToPath.remove(key);
                logger.warn("WatchKey no longer valid: {}", basePath);
            }
        }
    }
    
    private void handleOverflow(Path basePath) {
        // Thực hiện full rescan khi overflow
        try {
            Files.walk(basePath)
                .forEach(p -> logger.info("Rescan found: {}", p));
        } catch (IOException e) {
            logger.error("Rescan failed", e);
        }
    }
    
    @Override
    public void close() {
        running = false;
        executor.shutdownNow();
        try {
            watchService.close();
        } catch (IOException e) {
            logger.error("Error closing watchService", e);
        }
    }
}
```

### 4.3 Platform Differences & Limitations

| Platform | Implementation | Events | Limitations |
|----------|---------------|--------|-------------|
| **Linux** | inotify | CREATE, DELETE, MODIFY, ATTRIBUTES | Max watches (~8192), max instances, overflow possible |
| **macOS** | FSEvents (kqueue fallback) | Stream-based, coalesced | Latency ~1s, coalescing mất events trùng lặp |
| **Windows** | ReadDirectoryChangesW | All standard events | Directory-only (không watch file cụ thể) |
| **Network FS** | Polling fallback | Limited | NFS, SMB không hỗ trợ native events |

> **⚠️ Rủi ro production:** Network file systems (NFS, SMB) thường fallback về polling, gây high CPU và latency. Luôn kiểm tra `FileSystems.getDefault().newWatchService()` behavior trên target deployment environment.

---

## 5. Memory-Mapped Files (Advanced)

```java
// ✅ High-performance file I/O cho large files
public class MemoryMappedFileProcessor {
    
    public void processLargeFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            
            // Map 100MB chunks để tránh OutOfMemoryError
            long fileSize = channel.size();
            long chunkSize = 100 * 1024 * 1024; // 100MB
            
            for (long offset = 0; offset < fileSize; offset += chunkSize) {
                long size = Math.min(chunkSize, fileSize - offset);
                
                // Map vùng nhớ ảo vào file
                MappedByteBuffer buffer = channel.map(
                    FileChannel.MapMode.READ_ONLY, 
                    offset, 
                    size
                );
                
                // Process buffer như regular ByteBuffer
                processBuffer(buffer);
                
                // ⭐ Gợi ý unmap (Java không có unmap trực tiếp)
                // Buffer sẽ được unmap khi GC, nhưng...
                ((DirectBuffer) buffer).cleaner().clean(); // Unsafe but effective
            }
        }
    }
}
```

**Trade-offs Memory-Mapped Files:**
- ✅ Zero-copy I/O, kernel handles caching
- ✅ Random access nhanh
- ✅ Shared memory giữa processes
- ❌ Limited by virtual address space (4GB trên 32-bit)
- ❌ Page fault latency cho lần đầu truy cập
- ❌ Khó unmap (rely on GC)

---

## 6. Asynchronous File I/O (NIO.2)

```java
// ✅ True async file I/O với callback
public class AsyncFileProcessor {
    
    public void readAsync(Path path) throws IOException {
        AsynchronousFileChannel channel = AsynchronousFileChannel.open(
            path, 
            StandardOpenOption.READ,
            Executors.newThreadPoolExecutor(
                4, 8, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new ThreadFactoryBuilder().setNameFormat("async-io-%d").build()
            )
        );
        
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        
        // Async read với callback
        channel.read(buffer, 0, buffer, new CompletionHandler<Integer, ByteBuffer>() {
            @Override
            public void completed(Integer bytesRead, ByteBuffer attachment) {
                attachment.flip();
                // Process data...
                
                // Chain next operation
                if (bytesRead > 0) {
                    channel.read(attachment, attachment.position(), 
                                attachment, this);
                } else {
                    closeChannel(channel);
                }
            }
            
            @Override
            public void failed(Throwable exc, ByteBuffer attachment) {
                logger.error("Async read failed", exc);
                closeChannel(channel);
            }
        });
    }
}
```

---

## 7. Anti-patterns & Rủi ro Production

### 7.1 Resource Leak - WatchService không đóng

```java
// ❌ Resource leak
public void badMethod() throws IOException {
    WatchService ws = FileSystems.getDefault().newWatchService();
    path.register(ws, StandardWatchEventKinds.ENTRY_MODIFY);
    // Method kết thúc, ws không được close → Kernel resources leak
}

// ✅ Try-with-resources
public void goodMethod() throws IOException {
    try (WatchService ws = FileSystems.getDefault().newWatchService()) {
        path.register(ws, StandardWatchEventKinds.ENTRY_MODIFY);
        // Process events...
    } // Auto-close
}
```

### 7.2 Missing key.reset()

```java
// ❌ Không nhận events tiếp theo
while (true) {
    WatchKey key = watchService.take();
    for (WatchEvent<?> event : key.pollEvents()) {
        process(event);
    }
    // Forgot: key.reset()!
}

// ✅ Luôn reset
while (true) {
    WatchKey key = watchService.take();
    for (WatchEvent<?> event : key.pollEvents()) {
        process(event);
    }
    if (!key.reset()) { // Key không còn valid
        break;
    }
}
```

### 7.3 Assumption về Event Timing

```java
// ❌ Giả định CREATE rồi đến MODIFY
// Thực tế: Có thể chỉ nhận MODIFY (file tạo và modify nhanh)
// Hoặc nhận nhiều MODIFY liên tiếp (coalescing trên macOS)
```

---

## 8. So sánh giải pháp Monitoring

| Giải pháp | Latency | Resource | Reliability | Use Case |
|-----------|---------|----------|-------------|----------|
| **WatchService** | Low (ms) | Low | Platform-dependent | Production monitoring |
| **Polling** | High (seconds) | Medium | High | Network FS, fallback |
| **Apache Commons IO** | Configurable | Medium | High | Legacy, simple use |
| **inotifywait** (shell) | Low | Low | Linux only | Scripting |
| **JNotify** (JNI) | Low | Low | Cross-platform | Pre-Java 7 |

---

## 9. Java 21+ Updates

```java
// Java 21: MemorySegment thay thế ByteBuffer cho file I/O
try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
     Arena arena = Arena.ofConfined()) {
    
    MemorySegment segment = channel.map(
        FileChannel.MapMode.READ_ONLY,
        0,
        channel.size(),
        arena
    );
    
    // Process segment...
    // Auto-cleanup khi arena close
}
```

---

## 10. Kết luận & Khuyến nghị

### Khi nào dùng gì?

```
┌─────────────────────────────────────────────────────────────────┐
│                      Decision Tree                              │
│                                                                 │
│  ┌─────────────────────┐                                       │
│  │ Cần monitoring      │                                       │
│  │ real-time?          │                                       │
│  └──────────┬──────────┘                                       │
│             │                                                  │
│        ┌────┴────┐                                             │
│        ▼         ▼                                             │
│       Yes        No                                            │
│        │         │                                             │
│        ▼         ▼                                             │
│  ┌──────────┐  ┌────────────────┐                              │
│  │Network FS?│  │ Files.walk()   │                              │
│  └─────┬────┘  │ one-time scan   │                              │
│        │       └────────────────┘                              │
│   ┌────┴────┐                                                   │
│   ▼         ▼                                                   │
│  Yes        No                                                  │
│   │          │                                                  │
│   ▼          ▼                                                  │
│  Polling   WatchService                                         │
│  (5s+)     + Overflow handling                                  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Key Takeaways

1. **Path/Files:** Luôn dùng thay cho `java.io.File` - immutable, thread-safe, symbolic-link aware
2. **Atomic Operations:** Sử dụng `move` với `ATOMIC_MOVE`, `deleteIfExists` để tránh race conditions
3. **FileVisitor:** Implement cycle detection cho symbolic links, handle exceptions gracefully
4. **WatchService:** Luôn handle OVERFLOW events, reset keys, và close resources properly
5. **Platform Awareness:** Test trên target OS - Linux inotify vs macOS FSEvents khác biệt lớn
6. **Network FS:** Fallback về polling hoặc periodic scanning cho NFS/SMB

---

## References

- [Java NIO.2 FileSystem Tutorial - Oracle](https://docs.oracle.com/javase/tutorial/essential/io/fileio.html)
- [Linux inotify man page](https://man7.org/linux/man-pages/man7/inotify.7.html)
- [macOS FSEvents Programming Guide](https://developer.apple.com/library/archive/documentation/Darwin/Conceptual/FSEvents_ProgGuide/)
- [Java 21 Foreign Function & Memory API](https://openjdk.org/jeps/454)
