# Lock-Free Programming: CAS, Atomic Classes & ABA Problem

## 1. Mục tiêu của Task

Hiểu sâu bản chất lock-free programming trong Java: cơ chế CAS (Compare-And-Swap) tại tầng hardware, cách Java Exposed qua `Unsafe` và `VarHandle`, kiến trúc các `Atomic` classes, và hiện tượng ABA problem cùng giải pháp. Mục tiêu cuối cùng là biết **khi nào nên dùng** lock-free, **khi nào không nên**, và **rủi ro production** khi áp dụng.

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1. Tại sao cần Lock-Free?

Traditional locking (synchronized, ReentrantLock) có những hạn chế căn bản:

| Vấn đề | Mô tả | Hệ quả |
|--------|-------|--------|
| **Context Switch** | Thread bị block → OS phải switch context | 1-10μs overhead |
| **Priority Inversion** | Low-priority thread giữ lock, high-priority thread đợi | Unbounded latency |
| **Convoying** | Nhiều thread xếp hàng đợi lock | Throughput giảm |
| **Deadlock/Livelock** | Circular dependency hoặc starvation | System hang |

Lock-free programming hứa hẹn:
- **Wait-freedom**: Mỗi operation hoàn thành trong bounded steps
- **Lock-freedom**: Ít nhất một thread tiến triển
- **Obstruction-freedom**: Thread chạy đơn lẻ sẽ hoàn thành

> **Quan trọng**: Lock-free không có nghĩa là "nhanh hơn" trong mọi trường hợp. Nó giải quyết vấn đề **latency predictability** và **scalability** chứ không phải raw throughput.

### 2.2. CAS (Compare-And-Swap) - Trái Tim của Lock-Free

#### Hardware Foundation

CAS là atomic instruction được hỗ trợ trực tiếp bởi CPU:

```
x86: LOCK CMPXCHG
ARM: LDREX/STREX (Load-Exclusive/Store-Exclusive)
RISC-V: AMOCAS (Atomic Memory Operation CAS)
```

Pseudocode logic:
```
function CAS(address, expectedValue, newValue):
    if *address == expectedValue:
        *address = newValue
        return true
    return false
```

Operation này **atomic tại hardware level** - không thể bị interrupt giữa chừng.

#### Java Implementation

Java expose CAS qua `sun.misc.Unsafe` (cũ) và `java.lang.invoke.VarHandle` (Java 9+):

```java
// Unsafe path (internal)
public final native boolean compareAndSwapInt(
    Object o, long offset, int expected, int x
);

// VarHandle path (public API Java 9+)
VarHandle handle = MethodHandles.lookup()
    .findVarHandle(MyClass.class, "value", int.class);
handle.compareAndSet(obj, expected, newValue);
```

**Memory semantics**: CAS trong Java có **full volatile semantics** - tức là memory barrier (StoreLoad fence) được insert, đảm bảo:
- Không reordering trước CAS
- Tất cả writes trước CAS visible sau CAS

### 2.3. Retry Loop - Pattern Cơ Bản

CAS thường đi kèm với retry loop:

```java
// Pattern: Read-Modify-Write với CAS
while (true) {
    int current = atomicInt.get();      // Read
    int next = current + 1;              // Modify
    if (atomicInt.compareAndSet(current, next)) {  // Write
        break; // Success
    }
    // Failed - contention, retry
}
```

**Vấn đề**: Under high contention, retry loop có thể:
- Tiêu tốn CPU cycles (busy-waiting)
- Gây cache thrashing (liên tục invalidate cache lines)
- Starvation cho một số thread

> **Trade-off**: Lock-free phù hợp khi contention **thấp đến trung bình**. High contention → locking có thể tốt hơn (OS scheduler có thể yield thread).

---

## 3. Kiến Trúc Atomic Classes

### 3.1. Phân Cấp và Use Case

```
java.util.concurrent.atomic
├── AtomicInteger/AtomicLong/AtomicBoolean
├── AtomicReference<V>
├── AtomicIntegerArray/AtomicLongArray/AtomicReferenceArray
├── AtomicMarkableReference<V>  (giải quyết ABA)
├── AtomicStampedReference<V>   (giải quyết ABA)
└── LongAdder/LongAccumulator   (high contention optimization)
```

### 3.2. AtomicInteger/Long Internals

```java
public class AtomicInteger extends Number {
    private volatile int value;  // Volatile đảm bảo visibility
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long valueOffset;
    
    static {
        valueOffset = unsafe.objectFieldOffset(
            AtomicInteger.class.getDeclaredField("value")
        );
    }
    
    public final boolean compareAndSet(int expect, int update) {
        return unsafe.compareAndSwapInt(this, valueOffset, expect, update);
    }
}
```

**Design insight**:
- `volatile` đảm bảo visibility cross-thread
- `valueOffset` là memory offset đến field, được cache để CAS nhanh
- Không dùng synchronized → no monitor overhead

### 3.3. AtomicReference - Cơ Chế ABA

```java
// Vấn đề ABA đơn giản
AtomicReference<Node> head = new AtomicReference<>();

// Thread A: read head → Node A
Node current = head.get();

// Thread B: pop A, push B, push A lại
head.compareAndSet(A, B);
head.compareAndSet(B, A);

// Thread A: CAS thành công (vẫn là A), nhưng reference chain đã thay đổi!
head.compareAndSet(current, newNode); // SUCCESS - nhưng không an toàn!
```

**Vấn đề**: Giá trị A quay lại, nhưng **version/history đã thay đổi**. Trong linked structures, điều này có thể gây memory corruption.

### 3.4. Giải Pháp ABA: Stamping và Marking

#### AtomicStampedReference

```java
// Stamp là version number, tăng mỗi lần update
AtomicStampedReference<Node> stampedRef = 
    new AtomicStampedReference<>(initialRef, initialStamp);

// CAS kèm stamp check
stampedRef.compareAndSet(expectedRef, newRef, expectedStamp, newStamp);
```

Trade-offs:
- ✅ An toàn trước ABA
- ❌ Memory overhead (2 words: reference + stamp)
- ❌ GC pressure (stamp boxing)

#### AtomicMarkableReference

Simplified version cho boolean flag (thường dùng cho logical deletion trong lock-free structures):

```java
// Node bị đánh dấu deleted, nhưng vẫn trong list
AtomicMarkableReference<Node> markableRef = 
    new AtomicMarkableReference<>(node, false);

// Mark node as deleted logically
markableRef.attemptMark(node, true);
```

### 3.5. LongAdder - Optimization cho High Contention

**Vấn đề**: `AtomicLong` dùng single CAS → cache line bouncing dưới high contention.

**Giải pháp**: `LongAdder` dùng **striped counters**:

```
+------------------------------------------+
|              LongAdder                   |
|  +--------+  +--------+  +--------+     |
|  | Cell 0 |  | Cell 1 |  | Cell 2 | ... |  ← Mỗi thread map vào cell khác nhau
|  +--------+  +--------+  +--------+     |
|       ↑           ↑           ↑         |
|   Thread A    Thread B    Thread C      |
+------------------------------------------+
|              Base value                  |
+------------------------------------------+
```

- Thread hash vào cell riêng → giảm contention
- `sum()` đọc tất cả cells + base (eventually consistent)
- `add()` chỉ CAS trên cell của mình

**When to use**:
- ✅ Counters, metrics collection
- ✅ High-write, low-read scenarios
- ❌ Cần consistent read (sum() là approximation)

---

## 4. Lock-Free Data Structures

### 4.1. ConcurrentLinkedQueue (Michael-Scott Queue)

```
Head                    Tail
  ↓                      ↓
+-----+    +-----+    +-----+    +-----+
| A   |───→| B   |───→| C   |───→| D   |───→ null
+-----+    +-----+    +-----+    +-----+
```

**Algorithm**:
- `offer()`: CAS tail.next = newNode, sau đó CAS tail = newNode
- `poll()`: CAS head = head.next, return head.item

**Key insight**: Dùng **two CAS operations** cho mỗi operation, nhưng luôn tiến triển.

### 4.2. ConcurrentSkipListMap

Lock-free sorted map dựa trên Skip List:
- Mỗi level là linked list
- Search: top-down
- Insert/Delete: CAS node links
- Expected complexity: O(log n)

---

## 5. Rủi Ro, Anti-Patterns, Lỗi Thường Gặp

### 5.1. ABA Problem trong Production

**Scenario thực tế**: Lock-free stack

```java
// Thread A: pop → thấy node X
Node top = stack.get();
Node next = top.next;

// Thread B: pop X, pop Y, push X lại
// Stack state thay đổi hoàn toàn, nhưng head lại là X!

// Thread A: CAS thành công, nhưng next có thể đã bị free/recycle
stack.compareAndSet(top, next); // MEMORY CORRUPTION!
```

**Impact**: Use-after-free, JVM crash, data corruption.

### 5.2. Busy-Waiting và CPU Burning

```java
// ANTI-PATTERN: Spin không giới hạn
while (!atomicRef.compareAndSet(expected, update)) {
    // Không có backoff, thread sẽ chiếm CPU 100%
}
```

**Fix**: Exponential backoff hoặc yield:
```java
int retries = 0;
while (!atomicRef.compareAndSet(expected, update)) {
    if (++retries > SPIN_THRESHOLD) {
        Thread.yield(); // Hoặc LockSupport.parkNanos()
    }
}
```

### 5.3. False Sharing

```java
// ANTI-PATTERN: Các atomic fields cạnh nhau
public class CacheLine {
    AtomicLong counter1;  // 8 bytes
    AtomicLong counter2;  // 8 bytes - cùng cache line!
}
```

**Problem**: Hai thread modify hai fields khác nhau nhưng cùng cache line → cache thrashing.

**Fix**: Padding đến 64 bytes (cache line size):
```java
@Contended  // Java 8+, cần -XX:-RestrictContended
public class PaddedAtomic extends AtomicLong {
    // Tự động padding
}
```

### 5.4. Memory Ordering Misconceptions

**Myth**: CAS là "atomic nên tự động sequential consistent".

**Truth**: 
- CAS có **acquire-release** semantics
- Không đảm bảo total ordering của tất cả operations
- Cần careful reasoning về happens-before relationships

---

## 6. So Sánh Lock-Free vs Lock-Based

| Tiêu chí | Lock-Free | Lock-Based |
|----------|-----------|------------|
| **Latency** | Predictable (no unbounded wait) | Unbounded (priority inversion) |
| **Throughput** | Tốt ở low contention | Tốt ở high contention |
| **Scalability** | Linear scaling | Đỉnh rồi giảm |
| **Complexity** | Rất cao | Trung bình |
| **Debugging** | Khó (race conditions subtle) | Dễ hơn (thread dump shows locks) |
| **Composability** | Khó (không thể combine operations) | Dễ (nested locks, though risky) |
| **Memory** | Thường nhiều hơn (versioning) | Ít hơn |

**Rule of thumb**:
- Đơn giản counters/metrics → LongAdder
- Single reference update → AtomicReference
- Complex data structures → Dùng đã test (j.u.c)
- **Không tự implement** lock-free structures trừ khi thực sự cần và đã test kỹ

---

## 7. Production Concerns

### 7.1. Monitoring và Debugging

**Problem**: Lock-free code không có thread dump showing blocked threads.

**Solutions**:
- Metrics: Retry rate, CAS failure rate
- JFR (Java Flight Recorder): Monitor contention
- Custom probes: Track operation latency percentiles

### 7.2. Testing

Lock-free code đặc biệt khó test vì race conditions:
- **JCStress** (Java Concurrency Stress): Official OpenJDK tool
- **ThreadSanitizer**: Có thể dùng với JNI
- **Model checking**: Formal verification cho algorithms quan trọng

### 7.3. JVM và CPU Considerations

| Yếu tố | Ảnh hưởng |
|--------|-----------|
| **CPU architecture** | x86 có strong memory model (less reordering), ARM weaker |
| **SMT/Hyperthreading** | CAS operations có thể starve sibling thread |
| **NUMA** | Cross-socket CAS expensive hơn |
| **GC** | ABA problem worse với object reuse (G1/Shenandoah regions) |

### 7.4. Modern Java (21+) Features

**VarHandle** (Java 9, stable Java 21):
```java
// Fine-grained memory ordering control
VarHandle handle = MethodHandles.lookup()
    .findVarHandle(Counter.class, "value", int.class);

handle.setRelease(obj, newValue);      // Release semantics
handle.setOpaque(obj, newValue);       // Opaque (cheapest)
handle.compareAndSet(obj, e, n);       // Full volatile
```

**Foreign Function & Memory API** (Java 21, finalized):
- Direct access đến off-heap memory với atomic operations
- Có thể dùng cho zero-copy IPC

**Virtual Threads** (Java 21):
- Lock-free vẫn beneficial vì virtual threads không nên block
- Nhưng pinning (synchronized/native methods) vẫn là vấn đề

---

## 8. Kết Luận

### Bản Chất

Lock-free programming dựa trên **hardware atomic primitives** (CAS) để đạt được thread-safety mà không cần OS-level synchronization. Nó trade **implementation complexity** lấy **latency predictability** và **scalability**.

### Khi nào nên dùng

✅ **Nên dùng**:
- Counters, metrics, statistics (dùng LongAdder)
- Low-latency systems (trading, real-time)
- Config/state thay đổi ít, read nhiều
- Thread-safe singleton initialization (lazy initialization)

❌ **Không nên dùng**:
- Thay thế tất cả synchronized blocks (overkill)
- Complex invariants cần multi-field updates
- Team không có expertise về memory models
- High contention scenarios (contention → locking tốt hơn)

### Rủi ro lớn nhất

1. **ABA Problem**: Dễ bị bỏ qua, gây crashes khó debug
2. **Memory model bugs**: Subtle ordering issues chỉ reproduce ở specific CPUs
3. **Maintenance burden**: Code khó hiểu, khó modify an toàn

### Recommend cuối cùng

> **Đừng tự viết lock-free data structures.** Dùng `java.util.concurrent` - đã được test kỹ bởi experts. Chỉ implement lock-free khi:
> 1. Profile chứng minh bottleneck thực sự
> 2. Đã đọc và hiểu papers/algorithms
> 3. Có comprehensive testing (JCStress, stress tests)
> 4. Team có capability maintain

---

## Tham khảo

1. **"Java Concurrency in Practice"** - Goetz et al. (Chương về Atomic Variables và Nonblocking Synchronization)
2. **"The Art of Multiprocessor Programming"** - Herlihy & Shavit
3. **JEP 188: Java Memory Model Update** (Java 9)
4. **JEP 193: Variable Handles** (Java 9)
5. **OpenJDK JCStress**: https://openjdk.org/projects/code-tools/jcstress/
6. **LMAX Disruptor**: Case study của lock-free ring buffer trong production trading systems
