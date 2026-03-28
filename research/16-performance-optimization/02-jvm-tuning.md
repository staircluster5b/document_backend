# JVM Tuning - Heap Sizing, GC Selection, JIT Compiler Flags

## 1. Mục tiêu của task

Nắm vững nghệ thuật tinh chỉnh JVM để đạt được hiệu suất tối ưu trong môi trường production. Bao gồm:
- Xác định kích thước heap phù hợp với workload
- Lựa chọn garbage collector dựa trên đặc thù ứng dụng và SLAs
- Tinh chỉnh JIT compiler để cân bằng giữa startup time và peak performance
- Hiểu rõ các flags quan trọng và ý nghĩa thực sự của chúng

> **Nguyên tắc vàng của JVM Tuning**: Không tuning khi chưa đo lường. Mọi quyết định tuning phải dựa trên dữ liệu, không phải giả định.

---

## 2. Bản chất và cơ chế hoạt động

### 2.1. Heap Memory Architecture

JVM heap được chia thành các vùng với mục đích và behavior khác nhau:

```
┌─────────────────────────────────────────────────────────────┐
│                        HEAP MEMORY                          │
├─────────────────────────────────────────────────────────────┤
│  Young Generation (New)                                     │
│  ┌──────────────┬────────────────┬──────────────────────┐   │
│  │   Eden       │   Survivor 0   │    Survivor 1        │   │
│  │   (8:1:1)    │   (From)       │    (To)              │   │
│  └──────────────┴────────────────┴──────────────────────┘   │
│  [Minor GC: Copying algorithm]                              │
├─────────────────────────────────────────────────────────────┤
│  Old Generation (Tenured)                                   │
│  [Major GC / Full GC: Mark-Sweep-Compact / G1 regions]      │
├─────────────────────────────────────────────────────────────┤
│  Humongous Objects (G1/ZGC/Shenandoah only)                 │
│  [Objects > 50% region size, allocated directly in Old]     │
└─────────────────────────────────────────────────────────────┘
```

**Bản chất của generational hypothesis**:
- Weak generational hypothesis: Đa số objects die young (khoảng 98% objects trong Eden không sống qua 1 Minor GC)
- Strong generational hypothesis: Objects sống lâu thường tiếp tục sống lâu (tenured objects ít khi trở thành garbage)

> **Tại sao cần hai Survivor spaces?** Copying algorithm cần một vùng trống để copy survivors. Nếu chỉ có 1 Survivor, sau mỗi Minor GC phải compact - chi phí O(n). Với 2 Survivors (From/To), chỉ cần copy và swap pointer - chi phí O(number_of_survivors).

### 2.2. GC Root và Reachability Analysis

Garbage Collector xác định "sống/chết" qua **tri-color marking**:

```
Bắt đầu từ GC Roots:
├── Local variables trong stack frames
├── Static fields của loaded classes
├── JNI references
├── JVM internal references (classloaders, exceptions, etc.)
└── Synchronized monitors

Marking process:
WHITE (chưa thăm) → GRAY (đang thăm) → BLACK (đã thăm xong)

Objects không reachable từ GC Roots = Garbage
```

**Điểm mấu chốt**: Full GC phải pause để đảm bảo consistency của object graph. Concurrent GC (G1, ZGC, Shenandoah) dùng techniques như:
- Snapshot-At-The-Beginning (SATB): G1
- Colored pointers / Load barriers: ZGC, Shenandoah

### 2.3. JIT Compiler Pipeline

```
Java Source
     │
     ▼
Bytecode (interpreted - slow)
     │
     ▼
C1 Compiler (Client Compiler)
├─ Tier 1: Simple profiling + native code
├─ Tier 2: More profiling
└─ Tier 3: Full profiling
     │
     ▼
C2 Compiler (Server Compiler)
├─ Aggressive optimizations
├─ Inlining, escape analysis, loop unrolling
└─ Vectorization, lock elision
     │
     ▼
Peak Performance (but longer warmup)
```

**HotSpot detection**: Method được gọi đủ số lần (CompileThreshold, default ~10,000 invocations) sẽ trigger compilation.

---

## 3. Heap Sizing - Chiến lược và Trade-offs

### 3.1. Các tham số cốt lõi

| Flag | Mặc định | Ý nghĩa thực sự |
|------|----------|-----------------|
| `-Xms` | OS dependent | Initial heap size. Nếu < `-Xmx`, heap sẽ grow theo demand, gây allocation pauses |
| `-Xmx` | 1/4 physical memory | Maximum heap ceiling. JVM sẽ throw OOM khi vượt quá, không phải khi hết RAM |
| `-Xmn` / `-XX:NewRatio` | 2 (Old:Young = 2:1) | Size của Young Gen. Ảnh hưởng trực tiếp frequency của Minor GC |
| `-XX:SurvivorRatio` | 8 (Eden:S0:S1 = 8:1:1) | Phân bổ trong Young Gen. Tăng nếu có nhiều medium-lived objects |
| `-XX:MaxMetaspaceSize` | Unlimited | Class metadata limit. Java 8+ dùng native memory, không còn PermGen |
| `-XX:MaxDirectMemorySize` | `-Xmx` | NIO direct buffers limit. OOM khi vượt quá dù heap còn trống |

### 3.2. Chiến lược sizing theo workload type

**Memory-intensive (Big Data, Analytics)**:
```bash
# Ưu tiên throughput, chấp nhận longer pauses
-Xms64g -Xmx64g           # Fixed heap tránh resizing cost
-XX:NewRatio=3            # Larger Old Gen (48g), smaller Young (16g)
-XX:+UseG1GC              # Hoặc ZGC nếu latency-critical
```

**Latency-sensitive (Trading, Gaming, Microservices)**:
```bash
# Ưu tiên low latency, chấp nhận lower throughput
-Xms4g -Xmx4g
-XX:+UseZGC               # Sub-millisecond pauses
-XX:MaxGCPauseMillis=5    # Mục tiêu GC pause
```

**Containerized (Kubernetes)**:
```bash
# Critical: JVM phải nhận diện container limits
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0  # % của container memory limit
-XX:InitialRAMPercentage=50.0
```

> **Container Pitfall**: Không có `-XX:+UseContainerSupport`, JVM nhìn thấy toàn bộ host memory, có thể OOM-killed bởi Kubernetes dù `-Xmx` chưa đạt.

### 3.3. Sizing Anti-patterns

| Anti-pattern | Hậu quả | Cách làm đúng |
|--------------|---------|---------------|
| `-Xms` << `-Xmx` | Heap growth pauses, memory fragmentation | `-Xms` = `-Xmx` cho production |
| Quá lớn Young Gen | Minor GC takes too long, pause time spikes | Monitor GC time, adjust ratio |
| Quá nhỏ Survivor space | Premature promotion, Old Gen pollution | Objects die in Survivor, không promote sớm |
| Ignoring Metaspace | Classloader leaks, eventual OOM | Set `-XX:MaxMetaspaceSize`, monitor usage |

---

## 4. GC Selection - Decision Framework

### 4.1. So sánh các Garbage Collectors

| Collector | Target | Pause Time | Throughput | Memory Overhead | Best For |
|-----------|--------|------------|------------|-----------------|----------|
| **Serial** | < 100MB heap | ~100ms | Good | Minimal | CLI tools, embedded |
| **Parallel** | Throughput | ~100-500ms | Excellent | Low | Batch jobs, scientific computing |
| **G1** | Balanced | Target-driven (~20-200ms) | Good | Medium | General purpose, Java 9+ default |
| **ZGC** | Ultra-low latency | < 1ms (sub-millisecond) | Good | Higher (colored pointers) | Latency-critical, heaps > 8GB |
| **Shenandoah** | Low latency | < 10ms typical | Good | Medium | Similar to ZGC, Red Hat backed |

### 4.2. G1GC Deep Dive

**Region-based architecture**:
```
Heap chia thành regions (1MB-32MB, default 2048 regions)
┌─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┐
│ Eden│ Eden│Surv │ Old │ Old │ Old │Humon│ Free│
│     │     │     │     │     │     │     │     │
└─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┘

G1 collects regions có nhiều garbage nhất trước (Garbage First)
```

**Key flags**:
| Flag | Mặc định | Ý nghĩa |
|------|----------|---------|
| `-XX:MaxGCPauseMillis` | 200ms | Target pause time (G1 cố gắng đạt được) |
| `-XX:G1HeapRegionSize` | Computed | Nên để auto, hoặc set nếu có humongous objects |
| `-XX:G1NewSizePercent` | 5% | Min Young Gen size |
| `-XX:G1MaxNewSizePercent` | 60% | Max Young Gen size |
| `-XX:InitiatingHeapOccupancyPercent` | 45% | Trigger concurrent mark khi heap % occupied |
| `-XX:G1MixedGCCountTarget` | 8 | Số mixed GC để reclaim old gen regions |

**G1 Failure Modes**:
1. **To-space exhausted**: Young Gen quá nhỏ, objects không fit trong Survivor
   - Fix: Tăng `-XX:G1MaxNewSizePercent` hoặc giảm allocation rate
2. **Humongous allocation**: Objects > 50% region size
   - Fix: Tăng region size hoặc chia nhỏ objects
3. **Evacuation failure**: Không đủ free regions để copy
   - Fix: Tăng heap size hoặc giảm `-XX:G1MixedGCCountTarget`

### 4.3. ZGC và Shenandoah - Ultra-low Latency

**Colored Pointers (ZGC)**:
```
64-bit reference: [16-bit unused][4-bit color][44-bit address]
                         │
                         ├── Finalizable (finalizer processing)
                         ├── Remapped (pointer đã relocated)
                         ├── Marked0 (marking phase 0)
                         └── Marked1 (marking phase 1)

Load barrier: Mỗi load reference kiểm tra color, redirect nếu cần
```

**Concurrent capabilities**:
- Concurrent marking
- Concurrent reference processing
- Concurrent relocation (unique to ZGC/Shenandoah)
- **No stop-the-world compaction**

**Trade-offs**:
- Memory overhead: Colored pointers đòi hỏi address space (không phải physical memory)
- CPU overhead: Load barriers thêm ~5-15% CPU
- Throughput: Thấp hơn G1 ~5-10% (acceptable cho latency-sensitive)

### 4.4. GC Selection Decision Tree

```
Heap Size?
├── < 4GB
│   ├── Latency requirement < 100ms? → G1
│   └── Throughput priority? → Parallel
├── 4-32GB
│   ├── Latency requirement < 10ms? → ZGC / Shenandoah
│   └── Latency 10-200ms? → G1 with tuning
└── > 32GB
    ├── Java 21+ with ZGC? → ZGC (generational since Java 21)
    └── Older Java? → Consider multiple JVMs, không nên > 64GB heap
```

---

## 5. JIT Compiler Tuning

### 5.1. Compilation Thresholds

| Flag | Mặc định | Ý nghĩa |
|------|----------|---------|
| `-XX:CompileThreshold` | 10000 (client), 10000 (server) | Số invocations trước khi compile |
| `-XX:TieredCompilation` | true (Java 8+) | Enable tiered (C1 → C2) |
| `-XX:TieredStopAtLevel` | 4 | Max tier (4 = C2) |
| `-XX:ReservedCodeCacheSize` | 240MB | Max native code cache |
| `-XX:InitialCodeCacheSize` | 160MB | Initial code cache |

### 5.2. On-Stack Replacement (OSR)

OSR cho phép compile **đang chạy** method nếu loop count đủ cao:

```java
void longRunningMethod() {
    for (int i = 0; i < 100_000_000; i++) {
        // Hot loop - JVM sẽ OSR compile method này
        // ngay cả khi method chưa return
    }
}
```

| Flag | Mặc định | Ý nghĩa |
|------|----------|---------|
| `-XX:OnStackReplacePercentage` | 933 (C1), 140 (C2) | % của CompileThreshold để trigger OSR |

### 5.3. Compiler Tuning Scenarios

**Fast Startup (Microservices, Serverless)**:
```bash
# Giảm warmup time, chấp nhận peak performance thấp hơn
-XX:TieredStopAtLevel=1      # Chỉ dùng C1
-XX:CompileThreshold=100     # Compile sớm hơn
```

**Maximum Throughput (Long-running)**:
```bash
# Peak performance tối đa
-XX:+TieredCompilation
-XX:ReservedCodeCacheSize=512m   # Lớn hơn cho nhiều compiled methods
-XX:+UseCodeCacheFlushing        # Flush cold code khi đầy
```

**Diagnosing Compilation Issues**:
```bash
-XX:+PrintCompilation        # Xem methods đang được compile
-XX:+LogCompilation          # Chi tiết (phải dùng with hsdis)
-XX:+PrintCodeCache          # Xem code cache usage
```

> **Code Cache Full**: Khi code cache đầy, JVM stop compiling → interpreted methods → massive performance drop. Always monitor và size appropriately.

---

## 6. Production Monitoring & Observability

### 6.1. Essential GC Logging (Java 9+)

```bash
# Unified logging format
-Xlog:gc*:file=/var/log/app/gc.log:time,uptime,level,tags:filecount=10,filesize=100m

# Chi tiết hơn cho debugging
-Xlog:gc+heap=debug,gc+phases=debug,gc+ergo=debug
```

### 6.2. Key Metrics to Track

| Metric | Alert Threshold | Ý nghĩa |
|--------|-----------------|---------|
| GC Pause Time | > P99 SLA | Direct latency impact |
| GC Frequency | > 1/min (Full GC) | Memory pressure / leak |
| Heap Usage % | > 85% sustained | Sizing issue hoặc leak |
| Metaspace Usage | > 90% | Classloader leak |
| Code Cache Usage | > 90% | Deoptimization risk |
| Allocation Rate | Baseline deviation | Sudden traffic spikes |

### 6.3. GC Log Analysis Tools

- **GCViewer**: Visualize GC logs, tính throughput, pause time
- **gceasy.io**: Online analyzer với recommendations
- **jcmd**: `jcmd <pid> GC.run` (trigger GC), `jcmd <pid> VM.gc_info`

---

## 7. Rủi ro, Anti-patterns, và Pitfalls

### 7.1. Common Tuning Mistakes

| Mistake | Why It Hurts | Detection | Fix |
|---------|--------------|-----------|-----|
| **System.gc() calls** | Force Full GC, disrupts GC heuristics | GC logs có "System.gc()" | Remove code, dùng `-XX:+DisableExplicitGC` |
| **Finalizers** | Delay GC, unpredictable cleanup | GC logs reference processing time | Dùng `try-with-resources`, `Cleaner` (Java 9+) |
| **Large static caches** | Old Gen pressure, long GC pauses | Heap dump, dominator tree | SoftReferences, size limits, eviction |
| **Thread-local storage abuse** | Memory leak khi thread pool tăng | TLAB usage trong GC logs | Dùng bounded pools, clear on return |
| **Humongous allocations** | G1 fragmentation, frequent Full GC | GC logs "humongous" warnings | Object pooling, chia nhỏ, tăng region size |

### 7.2. JVM Flags to Avoid in Production

| Flag | Problem | Alternative |
|------|---------|-------------|
| `-Xnoclassgc` | Metaspace leak, never unloads classes | N/A (don't use) |
| `-XX:+UseMembar` | Deprecated, unnecessary overhead | Remove |
| `-XX:MaxPermSize` | Java 7 only, ignored in 8+ | `-XX:MaxMetaspaceSize` |
| Aggressive tuning without data | Unknown side effects | Measure, then tune |

### 7.3. Container-specific Risks

1. **OOMKilled mặc dù heap chưa đầy**:
   - Native memory (JNI, NIO direct buffers, Metaspace) không counted trong `-Xmx`
   - Solution: Set `-XX:MaxRAMPercentage` < 100%, monitor RSS

2. **CPU throttling**:
   - GC threads có thể bị throttle → longer pauses
   - Solution: Set CPU limits = requests, không overcommit

3. **Wrong memory detection**:
   - Pre-Java 10: JVM nhìn thấy host memory
   - Solution: Java 10+ với `-XX:+UseContainerSupport`

---

## 8. Khuyến nghị thực chiến trong Production

### 8.1. Baseline Configuration Template

```bash
#!/bin/bash
# Production-ready JVM options starter template

JAVA_OPTS="
  # Heap sizing - fixed để tránh resize pauses
  -Xms${HEAP_SIZE}g
  -Xmx${HEAP_SIZE}g
  
  # Container awareness
  -XX:+UseContainerSupport
  -XX:MaxRAMPercentage=75.0
  
  # GC selection (chọn 1)
  # -XX:+UseG1GC
  # -XX:+UseZGC
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=100
  
  # GC logging (Java 9+)
  -Xlog:gc*:file=/var/log/app/gc.log:time,uptime,level,tags:filecount=10,filesize=100m
  
  # Crash handling
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/var/log/app/heapdump.hprof
  -XX:ErrorFile=/var/log/app/hs_err_pid_%p.log
  
  # Performance
  -XX:+AlwaysPreTouch           # Pre-allocate heap pages
  -XX:+DisableExplicitGC        # Prevent System.gc() abuse
  -XX:+ParallelRefProcEnabled   # Parallel reference processing
  
  # Debugging (remove khi stable)
  # -XX:+PrintFlagsFinal
  # -XX:+PrintCommandLineFlags
"
```

### 8.2. Tuning Workflow

```
1. Establish baseline
   └── Run with default GC, collect metrics (latency P50/P99/P999, throughput)

2. Identify bottleneck
   ├── GC pauses too long? → Try ZGC/Shenandoah
   ├── Throughput too low? → Try Parallel GC, tune heap
   └── Frequent GC? → Tăng heap, giảm allocation rate

3. Make ONE change at a time
   └── Scientific method: hypothesis → change → measure → conclude

4. Load test với production-like patterns
   └── Don't trust synthetic benchmarks

5. Gradual rollout
   └── Canary → 10% → 50% → 100%
```

### 8.3. Java 21+ Modernizations

| Feature | Benefit | Flag |
|---------|---------|------|
| **Generational ZGC** | Lower allocation stall, better performance for diverse object lifetimes | `-XX:+ZGenerational` (default in Java 23+) |
| **Virtual Threads** | Millions of lightweight threads, reduced memory per thread | Không cần JVM flag, code change |
| **Scoped Values** | Immutable, thread-local-like data không leak memory | Preview feature |

---

## 9. Kết luận

**Bản chất của JVM Tuning** là cân bằng giữa 3 trade-off chính:

1. **Latency vs Throughput**: Low-pause collectors (ZGC) đánh đổi một phần throughput và memory overhead để đạt sub-millisecond pauses.

2. **Startup vs Peak Performance**: Tiered compilation tối ưu cho cả hai, nhưng microservices có thể prefer nhanh startup với C1-only.

3. **Memory vs CPU**: Larger heap giảm GC frequency nhưng tăng GC pause time và RSS memory.

**Quy tắc vàng**:
- Không tune khi chưa đo lường
- Một thay đổi tại một thời điểm
- Production data là king, benchmarks là queen
- Simple config > Complex tuning

**Checklist trước khi production**:
- [ ] Heap sizing phù hợp (Xms = Xmx)
- [ ] GC log enabled với rotation
- [ ] Heap dump on OOM configured
- [ ] Container limits recognized (UseContainerSupport)
- [ ] GC type phù hợp với SLA
- [ ] Code cache không risk đầy
- [ ] No explicit System.gc() calls
- [ ] Metaspace có limit

---

## 10. Tham khảo

- [Java SE HotSpot Virtual Machine Garbage Collection Tuning Guide](https://docs.oracle.com/en/java/javase/21/gctuning/)
- [ZGC: A Scalable Low-Latency Garbage Collector](https://openjdk.org/projects/zgc/)
- [G1GC Tuning](https://docs.oracle.com/en/java/javase/21/gctuning/g1-garbage-collector-tuning.html)
- [Understanding Java JIT with JITWatch](https://github.com/AdoptOpenJDK/jitwatch)
- [Java Performance: The Definitive Guide - Scott Oaks](https://www.oreilly.com/library/view/java-performance-the/9781449363512/)
