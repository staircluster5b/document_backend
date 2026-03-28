# Profiling Techniques - CPU Profiling, Memory Profiling, Flame Graphs

## 1. Mục tiêu của Task

Task này nghiên cứu các kỹ thuật profiling trong Java ở tầng production - cách xác định bottleneck về CPU, memory leak, và hiển thị chúng qua flame graphs. Mục tiêu không phải biết cách chạy công cụ, mà là hiểu **cơ chế hoạt động bên trong**, **trade-off giữa các phương pháp**, và **khi nào dùng cái nào trong production**.

> **Quan trọng**: Profiling trong production khác profiling trong development ở overhead, sampling frequency, và impact đến latency.

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1. CPU Profiling - Cơ Chế Tầng Thấp

#### A. Sampling vs Instrumentation

| Cơ chế | Nguyên lý | Overhead | Độ chính xác | Production-safe |
|--------|-----------|----------|--------------|-----------------|
| **Sampling** | Dừng VM định kỳ, lấy stack trace | Thấp (~1-5%) | Gần đúng | ✅ Yes |
| **Instrumentation** | Chèn bytecode đo thờ gian | Cao (50-1000%) | Chính xác | ❌ No |
| **Async** (eBPF/perf_events) | Kernel gửi signal, không dừng VM | Rất thấp (<1%) | Gần đúng | ✅ Yes |

**Bản chất sampling trong JVM:**
- JVM sử dụng `SIGPROF` (Unix signal) hoặc JVMTI `GetStackTrace`
- Mỗi 10ms (mặc định), thread đang chạy bị interrupt
- Stack trace được ghi lại, sau đó thread tiếp tục
- Kết quả là **tần suất xuất hiện** của method trong mẫu → suy ra thờ gian tiêu tốn

```
┌─────────────────────────────────────────────────────────┐
│                    CPU Sampling Flow                     │
├─────────────────────────────────────────────────────────┤
│  Timer (10ms) ──► SIGPROF ──► JVM Signal Handler        │
│                                    │                    │
│                                    ▼                    │
│                         ┌──────────────────┐            │
│                         │  Lấy Stack Trace │            │
│                         │  (cứ thread đang │            │
│                         │   chạy trên CPU) │            │
│                         └────────┬─────────┘            │
│                                  │                      │
│                                  ▼                      │
│                         ┌──────────────────┐            │
│                         │  Aggregate Tree  │            │
│                         │  (call tree)     │            │
│                         └──────────────────┘            │
└─────────────────────────────────────────────────────────┘
```

**Vấn đề của Safe-point Bias:**

Traditional sampling (JVisualVM, JConsole) chỉ lấy stack trace tại **safe point** - thờ điểm JVM biết trạng thái nhất quán của tất cả thread. Điều này tạo ra skew:

- Methods ngắn, chạy nhanh **có thể bị bỏ qua** nếu thực thi giữa 2 safe points
- Code inlined biến mất khỏi stack trace
- JIT-compiled code có thể không xuất hiện chính xác

> **Giải pháp**: Async-profiler dùng `AsyncGetCallTrace` - lấy stack trace không cần safe point, khử bias.

#### B. Async-Profiler Cơ Chế

Async-profiler sử dụng **perf_events** (Linux kernel) + **wall-clock/fd-based sampling**:

```
┌────────────────────────────────────────────────────────────┐
│                   Async-Profiler Architecture               │
├────────────────────────────────────────────────────────────┤
│                                                            │
│   ┌──────────────┐        ┌──────────────┐                │
│   │  perf_events │        │  AsyncGetCall│                │
│   │  (kernel)    │◄──────►│  Trace (JVM) │                │
│   └──────┬───────┘        └──────┬───────┘                │
│          │                       │                         │
│          │    Stack Walking      │                         │
│          │    (merge native      │                         │
│          │     + Java stacks)    │                         │
│          │                       │                         │
│          └──────────┬────────────┘                         │
│                     │                                      │
│                     ▼                                      │
│           ┌─────────────────┐                              │
│           │  Symbol Resolution│                            │
│           │  - perf_map file │                              │
│           │  - DWARF debug   │                              │
│           └────────┬────────┘                              │
│                    │                                       │
│                    ▼                                       │
│           ┌─────────────────┐                              │
│           │   Flame Graph   │                              │
│           │   Collapsed Form│                              │
│           └─────────────────┘                              │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

**Quan trọng**: Async-profiler có thể hoạt động ở 3 mode:
1. **itimer**: User-space sampling (ít overhead, có thể bị safe-point bias nhẹ)
2. **perf**: Kernel PMU counters (chính xác nhất, cần kernel.perf_event_paranoid ≤ 2)
3. **wall**: Wall-clock sampling (bao gồm cả thờ gian chờ I/O, không chỉ CPU)

### 2.2. Memory Profiling - Cơ Chế Phân Tích Heap

#### A. Allocation Profiling

| Phương pháp | Cơ chế | Overhead | Use case |
|-------------|--------|----------|----------|
| **TLAB sampling** | Sample 1/N allocation trong TLAB | Thấp | Phát hiện allocation hotspot |
| **Instrument allocation** | JVMTI callback mỗi `new` | Cao | Chi tiết từng object |
| **Heap dump** | Dừng world, dump toàn bộ heap | Cao (pause) | Phân tích sau sự cố |
| **JFR allocation** | Flight Recorder events | Thấp | Continuous monitoring |

**TLAB (Thread Local Allocation Buffer) Sampling:**

```
┌─────────────────────────────────────────────────────────────┐
│                    Allocation Path trong JVM                 │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  new Object()                                               │
│       │                                                     │
│       ▼                                                     │
│  ┌─────────────┐    Y    ┌────────────┐                     │
│  │ TLAB còn    │────────►│ Bump pointer│ (nhanh, lock-free)│
│  │ chỗ?        │         │ allocation  │                     │
│  └──────┬──────┘         └────────────┘                     │
│         │ N                                                 │
│         ▼                                                   │
│  ┌─────────────┐    Y    ┌────────────┐                     │
│  │ Retire TLAB │────────►│ Sample để  │                     │
│  │ rồi lấy     │         │ profiling  │                     │
│  │ TLAB mới?   │         └────────────┘                     │
│  └──────┬──────┘                                            │
│         │ N                                                 │
│         ▼                                                   │
│  ┌─────────────┐                                            │
│  │ Shared heap │ (chậm, cần synchronization)                │
│  │ allocation  │                                            │
│  └─────────────┘                                            │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

> **Mẹo**: `-XX:YoungPLABSize` và `-XX:OldPLABSize` điều chỉnh kích thước TLAB. TLAB lớn = ít sample hơn, nhưng allocation nhanh hơn.

#### B. Memory Leak Detection

**Cơ chế phát hiện leak:**

1. **Growth pattern analysis**: Heap tăng theo thờ gian, không giảm sau GC
2. **Dominators tree**: Object nào giữ reference đến nhiều memory nhất
3. **Path to GC roots**: Chuỗi reference từ object đến root (static, thread local, JNI)

```
┌─────────────────────────────────────────────────────────────┐
│              Memory Leak Analysis Flow                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  1. Heap dump (hprof/jfr)                                   │
│            │                                                │
│            ▼                                                │
│  2. Dominator calculation                                   │
│     - Object A dominates B nếu mọi path đến B               │
│       đều qua A                                             │
│            │                                                │
│            ▼                                                │
│  3. Find "retained size" lớn                                │
│     - Shallow size: kích thước object                       │
│     - Retained size: shallow + tất cả object                │
│       bị giữ lại bởi object này                             │
│            │                                                │
│            ▼                                                │
│  4. Path to GC Roots                                        │
│     - Tìm ai đang giữ reference                             │
│     - Thường là: static field, thread local,                │
│       connection pool, cache không giới hạn                 │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Ví dụ pattern leak phổ biến:**

```
// Static cache không bounded - CLASSIC LEAK
public class UserCache {
    private static final Map<String, User> cache = new HashMap<>(); // ❌
    
    // Fix: Dùng WeakHashMap hoặc Caffeine với expire
    private static final Cache<String, User> cache = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build();
}

// ThreadLocal không remove - Tomcat leak
public class Context {
    private static final ThreadLocal<SimpleDateFormat> df = 
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));
    
    public void process() {
        df.get().parse(...);
        // ❌ Quên df.remove() ở cuối request
    }
}
```

### 2.3. Flame Graphs - Visualization Cơ Chế

#### A. Flame Graph Structure

Flame graph không phải timeline - nó là **histogram xếp chồng**:

```
┌─────────────────────────────────────────────────────────────┐
│                   Flame Graph Anatomy                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Width = Frequency xuất hiện trong samples                  │
│  Height = Độ sâu của stack                                  │
│  Y-axis = Không phải thờ gian!                              │
│                                                             │
│  Ví dụ:                                                     │
│                                                             │
│  ┌─────────────────────────────────────────┐                 │
│  │          main()                         │ ◄── Root       │
│  └────────────┬────────────────────────────┘                 │
│       ┌───────┴────────┐                                    │
│  ┌────┴────┐      ┌────┴────┐                               │
│  │methodA()│      │methodB()│                                │
│  └────┬────┘      └─────────┘                               │
│  ┌────┴────┐                                                │
│  │methodC()│ ◄── Leaf, nếu rộng = hot spot                  │
│  └─────────┘                                                │
│                                                             │
│  → methodC() chiếm nhiều CPU nhất (width lớn nhất)         │
│  → Nhìn từ dưới lên: "Ai gọi hàm này?"                     │
│  → Nhìn từ trên xuống: "Hàm này gọi gì?"                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

#### B. Collapsed Stack Format

Input cho flame graph script:

```
java/lang/Thread.run;java/util/concurrent/ThreadPoolExecutor$Worker.run;...
java/lang/Thread.run;java/util/concurrent/ThreadPoolExecutor$Worker.run;...
java/lang/Object.wait;[native];...
```

Format: `method1;method2;method3;... count`

> **Ưu điểm của collapsed format**: Có thể merge từ nhiều server, filter, và diff giữa 2 trạng thái.

#### C. Differential Flame Graphs

So sánh 2 trạng thái để tìm regression:

```
# Tạo diff flame graph
./difffolded.pl baseline.collapsed current.collapsed > diff.collapsed
./flamegraph.pl --negcolor red --poscolor green diff.collapsed > diff.svg
```

- **Màu xanh**: Code path tăng thờ gian (hotter)
- **Màu đỏ**: Code path giảm thờ gian (cooler)

---

## 3. Kiến Trúc và Luồng Xử Lý

### 3.1. Production Profiling Pipeline

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Production Profiling Architecture                     │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   ┌──────────────┐    ┌──────────────┐    ┌──────────────┐             │
│   │   Agent      │    │   Storage    │    │  Visualizer  │             │
│   │  (Collect)   │───►│  (TSDB/S3)   │───►│  (Grafana/   │             │
│   └──────────────┘    └──────────────┘    │   FlameGraph)│             │
│           │                               └──────────────┘             │
│           ▼                                                             │
│   ┌────────────────────────────────────────────────────────┐           │
│   │                    Collection Strategies                │           │
│   ├────────────────────────────────────────────────────────┤           │
│   │  Continuous (low freq):                                 │           │
│   │  • JFR (Java Flight Recorder) - 1-3% overhead           │           │
│   │  • Async-profiler in background - 0.5-1% overhead       │           │
│   │                                                         │           │
│   │  On-Demand (triggered):                                 │           │
│   │  • High CPU alert → 60s profiling                       │           │
│   │  • OOM → automatic heap dump                            │           │
│   │  • Latency spike → wall-clock profiling                 │           │
│   │                                                         │           │
│   │  Scheduled:                                             │           │
│   │  • Nightly canary profiling                             │           │
│   │  • Post-deployment validation                           │           │
│   └────────────────────────────────────────────────────────┘           │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.2. JFR (Java Flight Recorder) Architecture

JFR là công cụ profiling chính thức của Oracle, thiết kế để chạy 24/7:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      JFR Architecture                                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                        JVM Events                                │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐           │   │
│  │  │  CPU     │ │ Memory   │ │  I/O     │ │  Lock    │           │   │
│  │  │  Load    │ │ Alloc    │ │  File/   │ │  Contention│         │   │
│  │  │          │ │          │ │  Socket  │ │          │           │   │
│  │  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘           │   │
│  │       └─────────────┴────────────┴────────────┘                 │   │
│  │                          │                                      │   │
│  │                          ▼                                      │   │
│  │                 ┌──────────────────┐                           │   │
│  │                 │  Event Stream    │                           │   │
│  │                 │  (lock-free ring │                           │   │
│  │                 │   buffer)        │                           │   │
│  │                 └────────┬─────────┘                           │   │
│  │                          │                                      │   │
│  │       ┌──────────────────┼──────────────────┐                  │   │
│  │       ▼                  ▼                  ▼                  │   │
│  │  ┌──────────┐      ┌──────────┐      ┌──────────┐             │   │
│  │  │ In-Memory│      │ Disk     │      │ Remote   │             │   │
│  │  │ Buffer   │      │ Recording│      │ Stream   │             │   │
│  │  │ (circular│      │ (.jfr)   │      │ (JMX)    │             │   │
│  │  │  buffer) │      │          │      │          │             │   │
│  │  └──────────┘      └──────────┘      └──────────┘             │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  Key points:                                                            │
│  • Events được ghi vào lock-free ring buffer (no contention)           │
│  • Circular buffer giữ last N events (configurable)                    │
│  • Recording có thể dump on-demand hoặc continuous                     │
│  • Overhead target: <1% khi dùng default profile                        │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 4. So Sánh Các Công Cụ

### 4.1. CPU Profiling Tools Comparison

| Tool | Cơ chế | Overhead | Production | Native Code | Điểm mạnh |
|------|--------|----------|------------|-------------|-----------|
| **Async-profiler** | perf_events + AsyncGetCallTrace | <1% | ✅ | ✅ | Best cho production, flame graphs |
| **JFR** | JFR events | <1% | ✅ | ❌ | Tích hợp sẵn, low overhead |
| **Honest Profiler** | AsyncGetCallTrace | ~2% | ✅ | ❌ | Open source, log format |
| **YourKit/JProfiler** | Instrumentation + Sampling | 5-50% | ⚠️ | ❌ | Rich UI, dev debugging |
| **Java VisualVM** | Sampling (safe-point) | 5-10% | ❌ | ❌ | Free, nhưng safe-point bias |
| **perf** | PMU counters | <1% | ✅ | ✅ | Native tool, cần symbol map |

### 4.2. Memory Profiling Tools

| Tool | Cơ chế | Overhead | Production | Strengths |
|------|--------|----------|------------|-----------|
| **JFR (alloc)** | TLAB sampling | <2% | ✅ | Allocation rate, live objects |
| **Async-profiler (alloc)** | TLAB sample + live set | <3% | ✅ | Native memory, flame graphs |
| **Eclipse MAT** | Heap dump analysis | N/A (offline)| N/A | Dominator tree, leak suspects |
| **YourKit** | Instrumentation | High | ❌ | Object count, allocation site |
| **JCMD + jhat** | Heap dump | Pause | ❌ | Built-in, basic analysis |

---

## 5. Rủi Ro, Anti-Patterns, Lỗi Thường Gặp

### 5.1. Safe-Point Bias (Most Common)

**Vấn đề**: Methods ngắn, inlined biến mất khỏi profile.

**Triệu chứng**:
- Profile cho thấy `java.util.HashMap.get` tốn nhiều thờ gian
- Nhưng thực tế là method đã bị inlined, không có safe point

**Fix**: Dùng async-profiler hoặc `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints`

### 5.2. Observer Effect

**Vấn đề**: Công cụ profiling làm thay đổi behavior của ứng dụng.

**Ví dụ**:
- Instrumentation thêm bytecode → JIT optimize khác đi
- Sampling làm thay đổi timing của race condition

**Fix**: 
- Dùng sampling thay vì instrumentation trong production
- Profile ở canary trước khi áp dụng rộng

### 5.3. Profile Wall Time thay vì CPU Time

**Vấn đề**: Application chậm do I/O nhưng profile chỉ cho thấy CPU.

**Fix**: 
- Dùng `itimer` (CPU) vs `wall` (wall-clock) phù hợp
- Async-profiler: `-e wall` để bắt cả thờ gian chờ

### 5.4. Missing Native/Kernel Time

**Vấn đề**: Java profiler chỉ thấy Java code, bỏ qua GC thread, JNI, kernel.

**Triệu chứng**: CPU high nhưng profile không thấy hot method.

**Fix**: 
- Async-profiler bao gồm native code
- Xem GC logs (`-Xlog:gc*`)

### 5.5. Flame Graph Misinterpretation

**Lỗi phổ biến**:
- Nghĩ Y-axis là timeline (không phải)
- Chỉ nhìn leaf nodes (phải xem cả stack context)
- Ignore thin but frequent paths (có thể là optimization opportunity)

---

## 6. Khuyến Nghị Thực Chiến trong Production

### 6.1. Setup Continuous Profiling

```bash
# Khởi động với async-profiler agent (continuous mode)
java -agentpath:/path/to/libasyncProfiler.so=start,file=/var/log/profile.jfr,interval=10ms,event=cpu

# Hoặc dùng JFR (Java 11+)
java -XX:StartFlightRecording:settings=profile,disk=true,filename=/var/log/recording.jfr,dumponexit=true
```

### 6.2. On-Demand Profiling Playbook

```bash
#!/bin/bash
# profile-trigger.sh - Triggered by monitoring alert

PID=$(pgrep -f "my-application")
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
OUTPUT_DIR="/var/log/profiles"

# 1. CPU profiling (60 seconds)
./async-profiler/profiler.sh -d 60 -f "$OUTPUT_DIR/cpu-$TIMESTAMP.svg" $PID

# 2. Allocation profiling (30 seconds)
./async-profiler/profiler.sh -e alloc -d 30 -f "$OUTPUT_DIR/alloc-$TIMESTAMP.svg" $PID

# 3. Wall-clock profiling nếu latency issue
./async-profiler/profiler.sh -e wall -d 30 -f "$OUTPUT_DIR/wall-$TIMESTAMP.svg" $PID

# 4. Heap dump nếu memory issue
jcmd $PID GC.heap_dump "$OUTPUT_DIR/heap-$TIMESTAMP.hprof"
```

### 6.3. Integration với Monitoring

```yaml
# Prometheus + JFR exporter
scrape_configs:
  - job_name: 'jfr-metrics'
    static_configs:
      - targets: ['localhost:8080']

# Alerting rules
groups:
  - name: profiling
    rules:
      - alert: HighAllocationRate
        expr: jfr_memory_allocation_rate > 100MB
        for: 5m
        annotations:
          summary: "High allocation rate detected"
          runbook_url: "https://wiki/profiling-playbook"
```

### 6.4. Profile-Guided Optimization Workflow

```
┌─────────────────────────────────────────────────────────────┐
│              Profile-Driven Optimization Loop                │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  1. Baseline ──► Deploy ──► Profile ──► Analyze             │
│                    │              │                         │
│                    │              ▼                         │
│                    │       Identify bottleneck               │
│                    │       (CPU/memory/allocation)          │
│                    │              │                         │
│                    │              ▼                         │
│                    │       Hypothesis                        │
│                    │       (cache/memoization/pooling)      │
│                    │              │                         │
│                    └──────────────┘                         │
│                                   │                         │
│                                   ▼                         │
│                            Implement fix                    │
│                                   │                         │
│                                   ▼                         │
│                            Measure delta                    │
│                            (diff flame graph)               │
│                                   │                         │
│                                   ▼                         │
│                            Verify regression test           │
│                                   │                         │
│                                   ▼                         │
│                            Deploy to production             │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 6.5. Decision Tree: Chọn Công Cụ

```
Bạn cần profile cái gì?
│
├─► CPU hot method?
│   ├─► Production environment? ──► Async-profiler (perf mode)
│   └─► Development debugging? ──► JProfiler/YourKit (rich UI)
│
├─► Memory leak?
│   ├─► Can reproduce? ──► Heap dump + Eclipse MAT
│   └─► Production, intermittent? ──► JFR allocation profiling continuous
│
├─► Allocation rate cao?
│   └─► Async-profiler -e alloc hoặc JFR allocation events
│
├─► Latency spike (không rõ nguyên nhân)?
│   └─► Async-profiler -e wall (wall-clock profiling)
│
└─► Native memory (off-heap)?
    └─► Async-profiler -e malloc hoặc NMT (Native Memory Tracking)
```

---

## 7. Kết Luận

### Bản Chất Cốt Lõi

1. **Sampling vs Instrumentation**: Sampling là trade-off giữa độ chính xác và overhead - production chỉ nên dùng sampling (async/perf-based).

2. **Safe-Point Bias**: Traditional JVM profilers bị skew vì chỉ lấy mẫu tại safe points. Async-profiler khử bias này bằng `AsyncGetCallTrace`.

3. **Flame Graphs**: Không phải timeline, mà là histogram xếp chồng. Width = frequency, height = stack depth. Dùng để nhanh chóng identify hotspot patterns.

4. **Memory Profiling**: TLAB sampling cho phép tracking allocation với overhead thấp. Heap dumps dùng cho deep analysis nhưng gây pause.

5. **Production Strategy**: Continuous low-frequency profiling (JFR/async-profiler) + on-demand triggered profiling khi alert.

### Trade-Off Quan Trọng Nhất

| Yếu tố | Development | Production |
|--------|-------------|------------|
| Tool | Instrumentation (YourKit/JProfiler) | Sampling (async-profiler/JFR) |
| Frequency | High detail, short bursts | Low detail, continuous |
| Overhead | 10-50% acceptable | <3% hard limit |
| Goal | Find bugs | Detect anomalies + quick diagnosis |

### Rủi Ro Lớn Nhất

- **Safe-point bias** dẫn đến misdiagnosis - optimize nhầm chỗ
- **Observer effect** làm thay đổi behavior khi profiling
- **Profile không đầy đủ** - chỉ nhìn Java code mà bỏ qua GC, I/O, native

### Best Practice Cuối Cùng

> Profiling là skill, không chỉ là tool. Đọc flame graph đòi hỏi hiểu cả application architecture lẫn JVM internals. Luôn validate hypothesis bằng multiple data sources (profile + logs + metrics) trước khi optimize.
