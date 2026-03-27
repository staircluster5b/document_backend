# High-Frequency Trading & Low-Latency Systems

> **Mục tiêu:** Hiểu sâu các kỹ thuật tối ưu độ trễ (latency) ở mức microsecond và nanosecond trong hệ thống giao dịch tần suất cao.

---

## 1. Mục tiêu của Task

High-Frequency Trading (HFT) đặt ra những thách thức cực đoan về latency - mỗi microsecond có thể quyết định lợi nhuận hay thua lỗ. Task này nghiên cứu:

- Cơ chế **lock-free programming** để loại bỏ contention
- **Memory-mapped files** cho I/O tốc độ cao
- **Kernel bypass networking** (DPDK) để giảm độ trễ network
- **Time synchronization** (PTP) cho timestamp chính xác
- **Co-location strategies** và market data feed handlers

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 Lock-Free Algorithms: Loại Bỏ Synchronization Overhead

#### Bản chất vấn đề

Traditional synchronization (`synchronized`, `ReentrantLock`) tạo ra **contention** - khi nhiều threads cạnh tranh cùng một lock, hệ điều hành phải thực hiện **context switch**, đưa thread vào sleep và wake up sau. Mỗi context switch tốn **1-10 microseconds** - quá chậm cho HFT.

> **Latency Reality Check:**
> - Context switch: ~1-10 μs
> - L1 cache hit: ~1 ns
> - L2 cache hit: ~4 ns
> - L3 cache hit: ~10-20 ns
> - Main memory access: ~100 ns
> - Lock acquisition (uncontended): ~20-100 ns
> - Lock acquisition (contended): ~1-10 μs

#### Lock-Free với CAS (Compare-And-Swap)

Cơ chế CAS là nền tảng của lock-free programming:

```
Atomic Operation tại hardware level:
- Đọc giá trị tại địa chỉ memory
- So sánh với expected value
- Nếu khớp: ghi new value
- Nếu không khớp: retry
```

**Trong Java:** `AtomicLong`, `AtomicReference`, `VarHandle` (Java 9+) sử dụng CAS.

#### ABA Problem và Giải Pháp

**Vấn đề:** Thread A đọc value X → Thread B đổi X→Y→X → Thread A CAS thành công nhưng state đã thay đổi.

**Giải pháp:**
- **Versioned references:** Kết hợp pointer với version counter (64-bit: 32-bit pointer + 32-bit version)
- **Hazard pointers:** Delay deallocation cho đến khi confirmed no thread đang truy cập
- **Java:** `AtomicStampedReference`, `AtomicMarkableReference`

#### Lock-Free Data Structures

| Data Structure | Lock-Based | Lock-Free | Trade-off |
|---------------|------------|-----------|-----------|
| Queue | `LinkedBlockingQueue` | `ConcurrentLinkedQueue` | Lock-free: ~100ns/op, Lock-based: ~500ns+ (contended) |
| Stack | `Stack` + synchronized | `ConcurrentLinkedDeque` | Lock-free stack dùng Treiber's algorithm |
| Map | `ConcurrentHashMap` | `ConcurrentSkipListMap` | CHM dùng lock stripping, CSLM lock-free |
| Ring Buffer | `ArrayBlockingQueue` | LMAX Disruptor | Disruptor: <50ns latency vs ABQ: ~200ns |

**LMAX Disruptor Pattern:**
- Single-producer hoặc Multi-producer với sequence claiming
- No locks, chỉ dùng memory barriers
- Cache-line padding để tránh false sharing
- Batch processing để tăng throughput

### 2.2 Memory-Mapped Files: Zero-Copy I/O

#### Bản chất Memory Mapping

Thay vì: `read() → kernel buffer → user buffer → process`

Memory mapping: `file ↔ virtual memory address space` (trực tiếp)

```
┌─────────────────────────────────────────────────────────────┐
│                     USER SPACE                              │
│  ┌──────────────┐        ┌──────────────────────────────┐  │
│  │ Application  │◄──────►│  Memory-Mapped Region        │  │
│  │   Code       │        │  (Directly accessible)       │  │
│  └──────────────┘        └──────────────────────────────┘  │
│            ▲                          │                     │
└────────────┼──────────────────────────┼─────────────────────┘
             │ Page Fault               │ Physical Memory
             │ (lazy loading)           ▼
┌────────────┼──────────────────────────┼─────────────────────┐
│  KERNEL    │                          │                     │
│  ┌─────────┴──────────┐    ┌───────────┴──────────┐         │
│  │  Page Cache        │◄──►│   File System        │         │
│  │  (buffered pages)  │    │   (disk storage)     │         │
│  └────────────────────┘    └──────────────────────┘         │
└─────────────────────────────────────────────────────────────┘
```

#### Java NIO Memory-Mapped Files

```java
// MappedByteBuffer là cốt lõi
try (RandomAccessFile raf = new RandomAccessFile("data.bin", "rw")) {
    FileChannel channel = raf.getChannel();
    
    // Map 1GB file vào memory
    MappedByteBuffer buffer = channel.map(
        FileChannel.MapMode.READ_WRITE, 0, 1024 * 1024 * 1024
    );
    
    // Đọc/ghi như bộ nhớ thường - không qua system call
    buffer.putLong(0, System.nanoTime());
    long value = buffer.getLong(8);
}
```

**Latency Comparison:**
| Method | Read Latency | Write Latency | Notes |
|--------|-------------|---------------|-------|
| Traditional I/O | ~5-10 μs | ~10-20 μs | System call + copy |
| Buffered I/O | ~100 ns | ~100 ns | Sau warmup |
| Memory-mapped | ~50 ns | ~50 ns | Direct access |

#### Page Cache và Warmup

- **Page fault:** Lần đầu truy cập mapped region → kernel load page từ disk (~milliseconds)
- **Warmup strategy:** Sequential read toàn bộ file sau mapping để pre-load vào page cache
- **madvise:** `MADV_SEQUENTIAL`, `MADV_RANDOM` - hint cho kernel về access pattern

#### Chronicle Queue: HFT-Grade Persistence

Chronicle Queue sử dụng memory-mapped files cho messaging:
- **Zero-copy:** Message written directly to mapped memory
- **Lock-free:** Single-writer append-only log
- **Sub-microsecond latency:** <1 μs end-to-end
- **Replication:** TCP-based hoặc replication engine tùy chỉnh

```
Architecture:
┌──────────┐    ┌─────────────┐    ┌──────────────────┐
│ Producer │───►│ Append-only │───►│ Memory-mapped    │
│ Thread   │    │ Ring Buffer │    │ Queue File       │
└──────────┘    └─────────────┘    └──────────────────┘
                                            │
                                    ┌───────┴────────┐
                                    ▼                ▼
                            ┌──────────────┐  ┌──────────────┐
                            │ Tailer       │  │ Replicator   │
                            │ (Consumer)   │  │ (Secondary)  │
                            └──────────────┘  └──────────────┘
```

### 2.3 Kernel Bypass Networking: DPDK

#### Vấn đề với Traditional Networking

```
Traditional Path (high latency):
App ──► Socket ──► TCP/IP Stack ──► Kernel ──► NIC Driver ──► NIC
     (user)      (kernel)         (kernel)    (kernel)     (hardware)

Latency breakdown:
- System call: ~100 ns
- TCP/IP processing: ~1-5 μs
- Kernel scheduling: ~variable
- Total: ~5-50 μs
```

#### DPDK (Data Plane Development Kit)

**Bản chất:** Direct access từ user-space tới NIC, bypass hoàn toàn kernel network stack.

```
DPDK Path (ultra-low latency):
App ──► DPDK PMD ──► NIC
     (user-space)   (hardware)

Latency: ~500 ns - 2 μs (10-100x faster)
```

**Key Components:**
- **PMD (Poll Mode Driver):** Thay vì interrupt-driven, dùng busy-polling để kiểm tra NIC
- **Huge Pages:** 2MB hoặc 1GB pages thay vì 4KB → giảm TLB misses
- **NUMA-awareness:** Pin threads và memory vào cùng NUMA node với NIC
- **Zero-copy:** Packet data trực tiếp trong DPDK mbuf, không copy

#### DPDK trong Java

Java không truy cập trực tiếp DPDK được (native C library), cần JNI hoặc Foreign Function API (Java 22+):

**Aeron:** High-performance messaging sử dụng kỹ thuật tương tự DPDK:
- Lock-free ring buffers
- Memory-mapped files cho IPC
- Busy-spinning cho sub-microsecond latency
- **Latency:** ~50-100 ns (intra-process), ~1 μs (inter-process/network)

**Aeron Architecture:**
```
┌──────────────────────────────────────────────────────────────┐
│                    Aeron Media Driver                        │
│  ┌─────────────┐    ┌──────────────┐    ┌─────────────────┐  │
│  │ Send Buffer │───►│   Sender     │───►│ Network Channel │  │
│  │  (Ring)     │    │   Thread     │    │ (UDP)           │  │
│  └─────────────┘    └──────────────┘    └─────────────────┘  │
│  ┌─────────────┐    ┌──────────────┐    ┌─────────────────┐  │
│  │ Receive Buf │◄───│   Receiver   │◄───│ Network Channel │  │
│  │  (Ring)     │    │   Thread     │    │ (UDP)           │  │
│  └─────────────┘    └──────────────┘    └─────────────────┘  │
└──────────────────────────────────────────────────────────────┘
         ▲                                            ▲
         │                                            │
┌────────┴────────┐                          ┌────────┴────────┐
│  Publisher      │                          │   Subscriber    │
│  Application    │                          │   Application   │
└─────────────────┘                          └─────────────────┘
```

### 2.4 Time Synchronization: PTP (Precision Time Protocol)

#### Bản chất Timestamp Accuracy

Trong HFT, timestamp chính xác là bắt buộc cho:
- Order matching fairness
- Latency measurement
- Regulatory compliance (MiFID II yêu cầu 1 microsecond accuracy)
- Forensics và debugging

**NTP vs PTP:**
| Protocol | Accuracy | Use Case |
|----------|----------|----------|
| NTP | ~1-10 ms | General time sync |
| PTP | ~100 ns - 1 μs | Financial trading, industrial control |

#### PTP (IEEE 1588) Mechanism

```
PTP Synchronization Flow:

Master                                          Slave
   │                                              │
   │  1. Sync (t1) ──────────────────────────────►│
   │                                              │  t2 = receive time
   │  2. Follow_Up (t1) ◄─────────────────────────│
   │                                              │
   │  3. Delay_Req ──────────────────────────────►│ t3 = send time
   │                                              │
   │  4. Delay_Resp (t4) ◄────────────────────────│
   │                                              │
   
Offset calculation:
  Offset = [(t2 - t1) - (t4 - t3)] / 2
Delay calculation:
  Delay = [(t2 - t1) + (t4 - t3)] / 2
```

**Hardware Timestamping:**
- NIC hỗ trợ PTP hardware timestamping (Intel i210, Mellanox ConnectX)
- Timestamp được đánh ngay khi packet rời/arrive NIC → loại bỏ kernel/network stack jitter
- **Accuracy:** <100 nanoseconds

#### Java Timestamping

```java
// High-resolution timestamp
long nanos = System.nanoTime();  // Monotonic, không phải wall-clock

// Wall-clock với microsecond precision (Java 9+)
Instant instant = Instant.now();  // Độ chính xác phụ thuộc OS

// Best practice cho HFT: CPU timestamp counter (TSC)
// JNI wrapper hoặc JNI access tới RDTSC instruction
```

**TSC (Time Stamp Counter):**
- Mỗi CPU có TSC register, incremented mỗi clock cycle
- Latency: ~10-20 cycles (~3-6 ns trên 3GHz CPU)
- **Vấn đề:** TSC không đồng bộ giữa các core trên một số CPU models
- **Giải pháp:** `constant_tsc`, `nonstop_tsc` CPU flags; hoặc dùng `clock_gettime(CLOCK_MONOTONIC)`

### 2.5 Co-Location và Market Data Feed Handlers

#### Co-Location Strategy

**Bản chất:** Đặt trading servers trong cùng data center với exchange matching engine.

```
Latency by Distance:
─────────────────────────────────────────────────────────────
Co-located (same DC)     : ~10-100 microseconds
Same city (10km)         : ~100-500 microseconds
Cross-country (1000km)   : ~10-20 milliseconds
Trans-atlantic           : ~70-100 milliseconds
─────────────────────────────────────────────────────────────
```

**Exchange Co-location Services:**
- Dedicated rack space trong exchange facility
- Cross-connect trực tiếp tới exchange switches
- Deterministic latency (sub-10 microseconds)

#### Market Data Feed Handlers

**Bản chất:** Component nhận multicast market data từ exchange, parse, và publish tới trading strategies.

**Challenges:**
- **High throughput:** 10M+ messages/second (market data bursts)
- **Low latency:** <1 microsecond từ network tới strategy
- **No GC pauses:** Object pooling, off-heap memory

**Architecture:**
```
┌─────────────────────────────────────────────────────────────┐
│                    Feed Handler Architecture                │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────┐    ┌──────────────┐    ┌────────────────┐ │
│  │  NIC        │───►│  Kernel      │───►│  DPDK/socket   │ │
│  │  (Multicast)│    │  (AIO/poll)  │    │  receiver      │ │
│  └─────────────┘    └──────────────┘    └────────────────┘ │
│                                                   │         │
│  ┌─────────────┐    ┌──────────────┐    ┌─────────▼──────┐ │
│  │  Strategy   │◄───│  Ring Buffer │◄───│  Parser        │ │
│  │  (Consumer) │    │  (Disruptor) │    │  (SBE/Protobuf)│ │
│  └─────────────┘    └──────────────┘    └────────────────┘ │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**SBE (Simple Binary Encoding):**
- FIX protocol replacement cho HFT
- Zero-copy parsing: schema-aware, fixed-position fields
- **Latency:** ~50-100 ns per message parse vs ~1-5 μs cho FIX text

---

## 3. Kiến Trúc và Luồng Xử Lý

### 3.1 End-to-End Low-Latency Trading System

```
┌──────────────────────────────────────────────────────────────────────┐
│                    HFT System Architecture                           │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  MARKET DATA INGESTION                    ORDER EXECUTION           │
│  ┌─────────────────────────┐             ┌─────────────────────────┐ │
│  │ Exchange Multicast      │             │   Order Gateway         │ │
│  │ Feed (UDP)              │             │   (TCP/FIX)             │ │
│  └───────────┬─────────────┘             └───────────┬─────────────┘ │
│              │                                      │               │
│              ▼                                      ▼               │
│  ┌─────────────────────────┐             ┌─────────────────────────┐ │
│  │ DPDK/Socket Receiver    │             │   TCP Stack (Kernel     │ │
│  │ (Kernel Bypass)         │             │   or Kernel Bypass)     │ │
│  └───────────┬─────────────┘             └───────────┬─────────────┘ │
│              │                                      │               │
│              ▼                                      ▼               │
│  ┌─────────────────────────┐             ┌─────────────────────────┐ │
│  │ SBE/FIX Parser          │             │   Order Builder         │ │
│  │ (Zero-copy)             │             │   (Pre-allocated)       │ │
│  └───────────┬─────────────┘             └───────────┬─────────────┘ │
│              │                                      │               │
│              ▼                                      ▼               │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                    Disruptor Ring Buffer                       │  │
│  │         (Lock-free, Cache-line padded, Batch processing)       │  │
│  └───────────────────────────┬───────────────────────────────────┘  │
│                              │                                       │
│                              ▼                                       │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                    Trading Strategy Engine                     │  │
│  │  - State machines (no allocations)                             │  │
│  │  - Pre-compiled decision trees                                 │  │
│  │  - No GC, deterministic latency                                │  │
│  └───────────────────────────┬───────────────────────────────────┘  │
│                              │                                       │
│  ┌───────────────────────────┴───────────────────────────────────┐  │
│  │                    Risk Management                             │  │
│  │  - Pre-trade risk checks (position limits, rate limits)        │  │
│  │  - Lock-free position tracking                                 │  │
│  └───────────────────────────┬───────────────────────────────────┘  │
│                              │                                       │
└──────────────────────────────┼───────────────────────────────────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │   PTP Timestamp     │
                    │   (Sub-microsecond) │
                    └─────────────────────┘
```

### 3.2 Memory Layout Optimization

```
Cache Hierarchy và False Sharing:

CPU Core 0                              CPU Core 1
┌──────────────┐                       ┌──────────────┐
│ L1 Cache     │                       │ L1 Cache     │
│ (32KB)       │                       │ (32KB)       │
└──────┬───────┘                       └──────┬───────┘
       │                                      │
       ▼                                      ▼
┌─────────────────────────────────────────────────────────────┐
│  Cache Line 0 (64 bytes)                                    │
│  ┌──────────┬──────────┬──────────────────────────────────┐ │
│  │ Var A    │ Var B    │        ...padding...             │ │
│  │ (Core 0) │ (Core 1) │                                  │ │
│  └──────────┴──────────┴──────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘

False Sharing Problem:
- Core 0 ghi Var A → Invalidate entire cache line trên Core 1
- Core 1 ghi Var B → Invalidate entire cache line trên Core 0
- Result: Constant cache misses, performance degradation

Solution: Cache-line padding (64 bytes alignment)
```

**Java Cache-Line Padding:**
```java
@Contended  // Java 8+ (requires -XX:-RestrictContended)
public class PaddedAtomicLong {
    private volatile long value;
    // Padding được tự động thêm bởi JVM
}

// Manual padding (Java 7 trở xuống)
public class ManualPaddedLong {
    long p1, p2, p3, p4, p5, p6, p7;  // 56 bytes padding
    volatile long value;               // 8 bytes
    long p8, p9, p10, p11, p12, p13, p14; // 56 bytes padding
}
```

---

## 4. So Sánh Các Lựa Chọn

### 4.1 Synchronization Strategies

| Strategy | Latency (uncontended) | Latency (contended) | Throughput | Complexity |
|----------|----------------------|---------------------|------------|------------|
| `synchronized` | ~20 ns | ~1-10 μs | Medium | Low |
| `ReentrantLock` | ~50 ns | ~1-10 μs | Medium | Low |
| `AtomicLong` (CAS) | ~10 ns | ~100-500 ns | High | Medium |
| `LongAdder` | ~10 ns | ~50 ns | Very High | Medium |
| Lock-free queue | ~100 ns | ~100 ns | Very High | High |
| Single-threaded | ~1 ns | N/A | Highest | Low |

**Recommendation:**
- Single-writer scenarios: Single-threaded event loop (Redis, Netty model)
- Multi-writer, read-heavy: `LongAdder` cho counters, `ConcurrentHashMap`
- Multi-writer, queue-based: LMAX Disruptor

### 4.2 Networking Approaches

| Approach | Latency | Throughput | Complexity | Use Case |
|----------|---------|------------|------------|----------|
| Standard Socket | ~10-50 μs | ~100K msg/s | Low | Traditional apps |
| Java NIO (epoll) | ~5-20 μs | ~1M msg/s | Medium | High-performance servers |
| Netty | ~5-15 μs | ~5M msg/s | Medium | Microservices |
| DPDK | ~500 ns - 2 μs | ~100M msg/s | Very High | HFT, Telco |
| Aeron | ~1-5 μs | ~10M msg/s | Medium | Trading, Gaming |
| Shared Memory | ~50-100 ns | Unlimited | Medium | Same-machine IPC |

### 4.3 Persistence Options

| Method | Latency | Durability | Complexity | Use Case |
|--------|---------|------------|------------|----------|
| `FileOutputStream` | ~10 ms | High | Low | General logging |
| `BufferedOutputStream` | ~100 μs | Medium | Low | Batch writes |
| Memory-mapped | ~50 ns | Eventually | Medium | Chronicle Queue |
| Database | ~1-10 ms | High | Low | Transactional data |
| SSD Direct I/O | ~10 μs | High | High | High-throughput WAL |

---

## 5. Rủi Ro, Anti-Patterns, và Lỗi Thường Gặp

### 5.1 Critical Anti-Patterns

> **1. Object Allocation trong Hot Path**
> 
> ```java
> // ANTI-PATTERN: Allocation mỗi message
> public void onMarketData(MarketData md) {
>     Order order = new Order();  // Allocation!
>     order.setPrice(md.getPrice());
>     order.setQuantity(md.getQuantity());
>     send(order);
> }
> 
> // CORRECT: Object pooling
> private final Order order = new Order();  // Pre-allocated
> public void onMarketData(MarketData md) {
>     order.reset();
>     order.setPrice(md.getPrice());
>     order.setQuantity(md.getQuantity());
>     send(order);  // Reuse same object
> }
> ```

> **2. False Sharing**
> 
> ```java
> // ANTI-PATTERN: Các biến hot trên cùng cache line
> public class Counter {
>     volatile long count1;  // Thread 1 ghi
>     volatile long count2;  // Thread 2 ghi → false sharing!
> }
> 
> // CORRECT: Cache-line padding
> public class PaddedCounter {
>     @Contended
>     volatile long count1;
>     
>     @Contended  
>     volatile long count2;
> }
> ```

> **3. Unnecessary System Calls**
> 
> ```java
> // ANTI-PATTERN: System call mỗi iteration
> while (true) {
>     System.currentTimeMillis();  // System call!
>     // process
> }
> 
> // CORRECT: TSC hoặc batching
> long start = System.nanoTime();
> while (true) {
>     // process nhiều items
>     if (++counter % 1000 == 0) {
>         checkTimeout();
>     }
> }
> ```

### 5.2 JVM GC Considerations

**Vấn đề:** GC pause có thể kéo dài milliseconds → death sentence cho HFT.

**Giải pháp:**
- **Zero-allocation path:** Pre-allocate tất cả objects, reuse trong vòng đời ứng dụng
- **Off-heap memory:** `sun.misc.Unsafe`, `ByteBuffer.allocateDirect()`, Chronicle Bytes
- **Low-latency GC:** ZGC (pause <1 ms), Shenandoah (pause <10 ms)
- **Epsilon GC:** No-op GC cho short-lived applications

### 5.3 Network Stack Pitfalls

| Issue | Impact | Solution |
|-------|--------|----------|
| Nagle's algorithm | ~40ms delay | `TCP_NODELAY` |
| Delayed ACK | ~200ms delay | `TCP_QUICKACK` (Linux) |
| Interrupt coalescing | ~100μs jitter | Disable hoặc tune NIC |
| CPU frequency scaling | Variable latency | Lock CPU governor to 'performance' |
| CPU migration | Cache misses | Pin threads với `taskset` hoặc `isolcpus` |

### 5.4 Timestamp Accuracy Traps

> **Wall-clock vs Monotonic:**
> - `System.currentTimeMillis()`: Wall-clock, có thể jump forward/backward (NTP sync)
> - `System.nanoTime()`: Monotonic, chỉ dùng cho elapsed time measurement
> 
> **Mistake:** Dùng `nanoTime()` để correlate events giữa các machines → không valid vì không synchronized.

---

## 6. Khuyến Nghị Thực Chiến trong Production

### 6.1 Development Practices

1. **Measure Everything:**
   - Histograms, không chỉ averages (p50, p99, p99.9, p99.99)
   - Coordinated omission problem: measure service time, không phải response time
   - JMH (Java Microbenchmark Harness) cho micro-benchmarks

2. **Latency Budget:**
   ```
   Total budget: 10 microseconds
   - Network receive: 2 μs
   - Parsing: 1 μs
   - Strategy logic: 3 μs
   - Risk check: 2 μs
   - Network send: 2 μs
   ```

3. **Testing:**
   - Unit tests cho business logic
   - Integration tests với simulated market data
   - Performance tests với realistic load patterns
   - Chaos testing: network latency injection, packet loss

### 6.2 Deployment Configuration

**JVM Flags:**
```bash
# Garbage Collection
-XX:+UseZGC                    # Sub-millisecond pauses
-XX:MaxGCPauseMillis=1         # Target pause time

# JIT Compilation
-XX:+UseTransparentHugePages   # Huge page support
-XX:+AlwaysPreTouch            # Pre-touch heap pages

# Threading
-XX:+UseNUMA                   # NUMA-aware allocation
-XX:-RestrictContended         # Enable @Contended

# Debugging (disable in production)
-XX:-OmitStackTraceInFastThrow # Full stack traces
```

**OS Configuration:**
```bash
# CPU isolation
isolcpus=2,3,4,5                # Isolate cores cho application

# Kernel parameters
vm.swappiness=0                 # Disable swap
net.core.rmem_max=134217728     # Socket buffer sizes
net.core.wmem_max=134217728
net.ipv4.tcp_low_latency=1      # Low latency TCP

# CPU governor
cpufreq-set -g performance      # Disable frequency scaling
```

### 6.3 Monitoring và Alerting

**Metrics cần theo dõi:**
| Metric | Target | Alert Threshold |
|--------|--------|-----------------|
| End-to-end latency (p99) | <10 μs | >50 μs |
| Network receive latency | <2 μs | >5 μs |
| GC pause time | <1 ms | >10 ms |
| CPU cache miss rate | <5% | >10% |
| Context switches/sec | <1000 | >5000 |

**Tooling:**
- **async-profiler:** CPU profiling không sampling overhead
- **perf:** Linux performance counters
- **Intel VTune:** Cache miss analysis, branch prediction
- **eBPF/bcc:** Kernel-level tracing

---

## 7. Kết Luận

High-Frequency Trading systems đòi hỏi **radical elimination của mọi latency source**. Bản chất của optimization ở đây không phải là "faster code" mà là **deterministic, predictable performance**.

**Các nguyên tắc cốt lõi:**

1. **Zero-allocation hot path:** Pre-allocate, reuse, pool - không allocation trong critical path

2. **Lock-free synchronization:** CAS-based algorithms, single-writer patterns - không locks, không contention

3. **Cache-friendly data structures:** Cache-line padding, sequential access patterns, false sharing elimination

4. **Kernel bypass:** DPDK, busy-spinning - không interrupts, không context switches

5. **Hardware timestamping:** PTP, TSC - không software timestamping trong critical path

6. **NUMA-aware placement:** Pin threads, memory, NIC cùng NUMA node

**Trade-off quan trọng nhất:**

> Low-latency programming luôn đánh đổi **developer productivity**, **code maintainability**, và **portability** lấy **performance**. Mỗi microsecond saved đều có cost: phức tạp hơn, khó test hơn, khó maintain hơn.

**Khi nào nên dùng:**
- ✅ HFT, market making, arbitrage
- ✅ Real-time bidding (ad tech)
- ✅ Gaming servers (tick-based games)
- ✅ Industrial control systems

**Khi nào KHÔNG nên dùng:**
- ❌ Traditional web applications (overkill)
- ❌ Systems yêu cầu rapid iteration
- ❌ Small teams với limited expertise
- ❌ When throughput matters more than latency

---

## 8. Tham Khảo

### 8.1 Sách và Tài Liệu
- *"Java Concurrency in Practice"* - Brian Goetz
- *"Mechanical Sympathy"* - Martin Thompson (blog)
- *"Low Latency Trading"* - Peter Lawrey (Chronicle Software)
- DPDK Documentation: https://doc.dpdk.org/
- Aeron Wiki: https://github.com/real-logic/aeron/wiki

### 8.2 Công Cụ và Libraries

| Library | Purpose | License |
|---------|---------|---------|
| **LMAX Disruptor** | Lock-free ring buffer | Apache 2.0 |
| **Chronicle Queue** | Persistence messaging | Apache 2.0 |
| **Chronicle Map** | Off-heap concurrent map | Apache 2.0 |
| **Aeron** | Ultra-low-latency messaging | Apache 2.0 |
| **JCTools** | Lock-free data structures | Apache 2.0 |
| **HdrHistogram** | Latency histograms | Public Domain |

### 8.3 Videos và Talks
- Martin Thompson: "Designing for Performance" (QCon)
- Todd Montgomery: "Aeron: The Open Source OSI Model" (GOTO)
- Peter Lawrey: "Chronicle Queue: Microsecond Latency"
