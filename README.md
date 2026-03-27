# 🚀 Lộ Trình Nghiên Cứu: Từ Java Core Đến Senior Backend Chuyên Nghiệp

> **Mục tiêu:** Làm chủ ngôn ngữ, thấu hiểu kiến trúc hệ thống, quản trị rủi ro và tối ưu hóa hiệu năng.

---

## ☕ PHẦN 1: JAVA CORE MASTERY (NỀN TẢNG CHUYÊN SÂU)
*Tập trung vào cách ngôn ngữ vận hành dưới "nắp máy" thay vì chỉ sử dụng cú pháp.*

### 1.1 Quản lý Bộ nhớ & JVM (Java Virtual Machine)
- [x] **Tìm hiểu cấu trúc bộ nhớ JVM:** Phân biệt rõ Heap, Stack, Metaspace (trước đây là PermGen).
- [x] **Cơ chế Garbage Collection (GC):** Nghiên cứu các thuật toán G1, ZGC, Shenandoah. Hiểu khi nào thì xảy ra "Stop-the-world".
- [x] **JVM Tuning:** Cách sử dụng các tham số `-Xms`, `-Xmx`, `-XX:MaxMetaspaceSize` để tối ưu ứng dụng.
- [x] **Memory Leak:** Nhận diện rủi ro rò rỉ bộ nhớ (Static references, Unclosed resources) và cách dùng công cụ Profiler (VisualVM, JProfiler).

### 1.2 Deep Dive into Collections Framework
- [x] **Mã nguồn (Source code) phân tích:** Xem cách `HashMap` xử lý Collision (Hash bucket, Treeify) và `ConcurrentHashMap` (Lock stripping).
- [x] **Lựa chọn cấu trúc dữ liệu:** Khi nào dùng `ArrayList` vs `LinkedList`, `HashSet` vs `TreeSet` dựa trên độ phức tạp thuật toán (Big O).
- [x] **Fail-fast vs Fail-safe:** Phân biệt cơ chế Iteration trong môi trường đa luồng.
- [x] **Biến và Kiểu dữ liệu:** Phân biệt kiểu nguyên thủy (int, double, boolean, char) và kiểu tham chiếu (String).

### 1.3 Multithreading & Concurrency (Lập trình đa luồng)
- [x] **Thread Lifecycle & Synchronization:** Hiểu sâu cơ chế `synchronized`, `volatile`, `ReentrantLock`, và `ReadWriteLock`.
- [x] **Executor Framework:** ThreadPoolExecutor, ForkJoinPool, CompletableFuture, và Reactive Programming.
- [x] **Concurrent Collections:** Sử dụng `BlockingQueue`, `ConcurrentSkipListMap`, `CopyOnWriteArrayList` trong môi trường production.

### 1.4 Java I/O và NIO (New I/O)
- [x] **Blocking vs Non-blocking I/O:** Phân biệt IO truyền thống và NIO/NIO.2, Selector, Channel, Buffer.
- [x] **File System API (NIO.2):** Path, Files, FileVisitor, WatchService cho file monitoring.
- [x] **Asynchronous I/O:** `AsynchronousFileChannel`, callback vs future-based APIs.

---

## 🔬 PHẦN 2: JVM INTERNALS & ADVANCED JAVA
*Đi sâu vào cách JVM thực sự vận hành và các tính năng Java hiện đại.*

### 2.1 Class Loading & Bytecode
- [x] **ClassLoader Hierarchy:** Bootstrap, Extension, Application ClassLoaders. Custom ClassLoader implementation và ứng dụng (hot-reload, plugin architecture, isolation).
- [ ] **JVM Bytecode & JIT Compilation:** Cách JVM biên dịch bytecode, JIT tiers (C1, C2, Graal), inlining, escape analysis, và on-stack replacement (OSR).
- [ ] **Java Agents & Instrumentation:** Premain, agentmain, Javassist, ByteBuddy cho AOP và monitoring.

### 2.2 Java Memory Model (JMM)
- [ ] **Happens-Before Relationship:** Định nghĩa chính xác, các rules (program order, monitor unlock/lock, volatile, thread start/join, final fields), và ứng dụng để viết lock-free algorithms đúng đắn.
- [ ] **Memory Barriers & Fences:** LoadLoad, StoreStore, LoadStore, StoreLoad fences. Cách JVM/hardware reorder instructions và cách prevent.

### 2.3 Modern Java Features (Java 17-21 LTS)
- [ ] **Virtual Threads Deep Dive:** Carrier threads, pinning, structured concurrency, scoped values. So sánh với reactive programming và traditional threads.
- [ ] **Pattern Matching & Sealed Classes:** switch expressions, record patterns, sealed class hierarchy. Design patterns mới và migration từ OOP truyền thống.
