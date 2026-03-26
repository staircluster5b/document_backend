# Phân Tích Mã Nguồn: HashMap & ConcurrentHashMap

> **Mức độ:** Senior Backend Architect  
> **Chủ đề:** Java Collections Framework Internals  
> **Ngôn ngữ:** Java 8+ (tập trung Java 17/21 enhancements)

---

## 📋 Tóm Tắt

Bài nghiên cứu này phân tích sâu cơ chế hoạt động bên trong của `HashMap` và `ConcurrentHashMap` - hai cấu trúc dữ liệu được sử dụng nhiều nhất trong Java. Hiểu rõ internals giúp tránh các lỗi hiệu năng nghiêm trọng và anti-patterns phổ biến.

---

## 1. HashMap Internals

### 1.1 Cấu Trúc Dữ Liệu Bên Trong

```
┌─────────────────────────────────────────────────────────────┐
│                        HashMap                              │
├─────────────────────────────────────────────────────────────┤
│  Node<K,V>[] table;        // Mảng buckets (resize được)   │
│  int size;                 // Số phần tử thực tế           │
│  int threshold;            // Ngưỡng resize = capacity * LF │
│  float loadFactor;         // Hệ số tải mặc định 0.75f     │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 Hash Function - Tại Sao Lại Phức Tạp Vậy?

```java
// Java 8+ HashMap.hash(Object key)
static final int hash(Object key) {
    int h;
    return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
}
```

**Phân tích Senior:**

| Thao tác | Mục đích | Hậu quả nếu thiếu |
|----------|----------|-------------------|
| `key.hashCode()` | Lấy hash gốc từ object | Không có hash để phân phối |
| `h >>> 16` | XOR higher 16 bits với lower 16 bits | Poor distribution khi table nhỏ |
| `^ (XOR)` | Kết hợp 2 nửa | Giảm collision cho keys gần nhau |

> **💡 Insight:** XOR với higher bits giải quyết vấn đề khi `table.length` nhỏ (thường là 2^n). Nếu chỉ dùng `hashCode()` nguyên bản, các keys có cùng lower bits sẽ đổ vào cùng bucket dù higher bits khác nhau.

### 1.3 Bucket Index Calculation

```java
// (n - 1) & hash tương đương hash % n khi n là lũy thừa của 2
// NHƯNG nhanh hơn 10-20x (bitwise vs modulo)
if ((p = tab[i = (n - 1) & hash]) == null)
    tab[i] = newNode(hash, key, value, null);
```

**Tại sao capacity luôn là 2^n?**
- `(n - 1)` tạo ra mask với tất cả bits = 1 (ví dụ: 15 = 1111)
- `& hash` giữ lại lower bits của hash
- Tương đương `hash % n` nhưng không cần division hardware

### 1.4 Collision Resolution: Chained Hashing → Treeify

#### Phase 1: Linked List (O(n) worst case)

```
Bucket[3]: [Node A: hash=3] → [Node B: hash=19] → [Node C: hash=35]
            (19 & 15 = 3)      (35 & 15 = 3)
```

#### Phase 2: Treeify (O(log n)) - Java 8+

```java
// Ngưỡng treeify
static final int TREEIFY_THRESHOLD = 8;
static final int UNTREEIFY_THRESHOLD = 6;
static final int MIN_TREEIFY_CAPACITY = 64;
```

**Điều kiện Treeify:**
1. Một bucket có ≥ 8 nodes VÀ
2. `table.length >= 64`

> **⚠️ Quan trọng:** Nếu table < 64, HashMap ưu tiên resize() thay vì treeify. Tại sao? Vì resize phân tán lại entries rẻ hơn cân bằng cây.

```java
final void treeifyBin(Node<K,V>[] tab, int hash) {
    int n, index; Node<K,V> e;
    // Điều kiện: tab == null || (n = tab.length) < MIN_TREEIFY_CAPACITY
    // → resize thay vì treeify
    if (tab == null || (n = tab.length) < MIN_TREEIFY_CAPACITY)
        resize();
    else if ((e = tab[index = (n - 1) & hash]) != null) {
        // Chuyển linked list → Red-Black Tree
        TreeNode<K,V> hd = null, tl = null;
        do {
            TreeNode<K,V> p = replacementTreeNode(e, null);
            if (tl == null)
                hd = p;
            else {
                p.prev = tl;
                tl.next = p;
            }
            tl = p;
        } while ((e = e.next) != null);
        
        if ((tab[index] = hd) != null)
            hd.treeify(tab); // Xây dựng cây cân bằng
    }
}
```

### 1.5 Resize Mechanism - O(n) nhưng Amortized O(1)

```java
final Node<K,V>[] resize() {
    Node<K,V>[] oldTab = table;
    int oldCap = (oldTab == null) ? 0 : oldTab.length;
    int oldThr = threshold;
    int newCap, newThr = 0;
    
    if (oldCap > 0) {
        if (oldCap >= MAXIMUM_CAPACITY) {
            threshold = Integer.MAX_VALUE;
            return oldTab;
        }
        else if ((newCap = oldCap << 1) < MAXIMUM_CAPACITY &&
                 oldCap >= DEFAULT_INITIAL_CAPACITY)
            newThr = oldThr << 1; // Double threshold
    }
    // ... tạo table mới
    
    // Rehash tất cả entries - ĐÂY LÀ O(n)
    Node<K,V>[] newTab = (Node<K,V>[])new Node[newCap];
    table = newTab;
    // ... chuyển nodes sang table mới
}
```

**Phân tích Performance:**

| Thao tác | Average | Worst Case | Ghi chú |
|----------|---------|------------|---------|
| `get()` | O(1) | O(log n) | Với treeified bucket |
| `put()` | O(1) | O(log n) | Resize là O(n) nhưng hiếm |
| `remove()` | O(1) | O(log n) | - |
| Resize | O(n) | O(n) | Amortized O(1) per op |

---

## 2. ConcurrentHashMap Internals

### 2.1 Evolution: Segments → Lock Stripping

```
Java 7 (Segment-based):
┌─────────────────────────────────────────────┐
│  Segment[16] - Mỗi segment là 1 ReentrantLock│
│  ├── HashEntry[] (segment 0)               │
│  ├── HashEntry[] (segment 1)               │
│  └── ...                                    │
│  Lock granularity: Segment level            │
│  Max concurrency: 16 (mặc định)            │
└─────────────────────────────────────────────┘

Java 8+ (Lock Stripping on Buckets):
┌─────────────────────────────────────────────┐
│  Node[] table - Không còn segments!        │
│  ├── synchronized on bucket head node      │
│  ├── volatile + CAS cho resize/helpTransfer │
│  └── TreeBin cho treeified buckets         │
│  Lock granularity: Bucket level (fine-grained)│
│  Max concurrency: #buckets (hundreds-thousands)│
└─────────────────────────────────────────────┘
```

### 2.2 Lock Stripping Mechanism

```java
// Java 8+ ConcurrentHashMap.putVal()
final V putVal(K key, V value, boolean onlyIfAbsent) {
    if (key == null || value == null) throw new NullPointerException();
    int hash = spread(key.hashCode()); // Hash spreading tốt hơn
    int binCount = 0;
    
    for (Node<K,V>[] tab = table;;) {
        Node<K,V> f; int n, i, fh;
        
        // Case 1: Table chưa khởi tạo → initTable()
        if (tab == null || (n = tab.length) == 0)
            tab = initTable();
        
        // Case 2: Bucket trống → CAS insertion (không cần lock!)
        else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
            if (casTabAt(tab, i, null, new Node<K,V>(hash, key, value, null)))
                break; // CAS thành công
            // CAS thất bại → retry loop
        }
        
        // Case 3: Bucket đang được transfer (resize)
        else if ((fh = f.hash) == MOVED)
            tab = helpTransfer(tab, f);
        
        // Case 4: Bucket có dữ liệu → SYNCHRONIZED on bucket head
        else {
            V oldVal = null;
            synchronized (f) { // 🔒 LOCK chỉ trên bucket này!
                if (tabAt(tab, i) == f) {
                    if (fh >= 0) {
                        // Linked list traversal + update/insert
                        binCount = 1;
                        for (Node<K,V> e = f;; ++binCount) {
                            K ek;
                            if (e.hash == hash &&
                                ((ek = e.key) == key ||
                                 (ek != null && key.equals(ek)))) {
                                oldVal = e.val;
                                if (!onlyIfAbsent)
                                    e.val = value;
                                break;
                            }
                            Node<K,V> pred = e;
                            if ((e = e.next) == null) {
                                pred.next = new Node<K,V>(hash, key, value, null);
                                break;
                            }
                        }
                    }
                    else if (f instanceof TreeBin) {
                        // Red-Black Tree operations
                        Node<K,V> p;
                        binCount = 2;
                        if ((p = ((TreeBin<K,V>)f).putTreeVal(hash, key, value)) != null) {
                            oldVal = p.val;
                            if (!onlyIfAbsent)
                                p.val = value;
                        }
                    }
                }
            }
            if (binCount != 0) {
                if (binCount >= TREEIFY_THRESHOLD)
                    treeifyBin(tab, i);
                if (oldVal != null)
                    return oldVal;
                break;
            }
        }
    }
    addCount(1L, binCount);
    return null;
}
```

### 2.3 Key Concurrent Mechanisms

#### 2.3.1 Volatile + Unsafe CAS

```java
// Truy cập bucket an toàn không cần lock
static final <K,V> Node<K,V> tabAt(Node<K,V>[] tab, int i) {
    return (Node<K,V>)U.getObjectVolatile(tab, ((long)i << ASHIFT) + ABASE);
}

static final <K,V> boolean casTabAt(Node<K,V>[] tab, int i,
                                    Node<K,V> c, Node<K,V> v) {
    return U.compareAndSwapObject(tab, ((long)i << ASHIFT) + ABASE, c, v);
}
```

#### 2.3.2 Counter Cells - Giảm Contention trên Size

```java
// Thay vì 1 atomic counter bị contention cao:
// @jdk.internal.vm.annotation.Contended  // Padding để tránh false sharing
private transient volatile CounterCell[] counterCells;

// Size được tính: baseCount + sum(counterCells[i].value)
```

### 2.4 Get Operation - Lock-Free

```java
public V get(Object key) {
    Node<K,V>[] tab; Node<K,V> e, p; int n, eh; K ek;
    int h = spread(key.hashCode());
    
    // Chỉ cần volatile read - KHÔNG CẦN LOCK!
    if ((tab = table) != null && (n = tab.length) > 0 &&
        (e = tabAt(tab, (n - 1) & h)) != null) {
        
        if ((eh = e.hash) == h) {
            if ((ek = e.key) == key || (ek != null && key.equals(ek)))
                return e.val;
        }
        else if (eh < 0) // Negative hash = special node (TreeBin, ForwardingNode)
            return (p = e.find(h, key)) != null ? p.val : null;
        
        // Traverse linked list
        while ((e = e.next) != null) {
            if (e.hash == h &&
                ((ek = e.key) == key || (ek != null && key.equals(ek))))
                return e.val;
        }
    }
    return null;
}
```

---

## 3. So Sánh Hiệu Năng & Use Cases

### 3.1 HashMap vs ConcurrentHashMap

| Tiêu chí | HashMap | ConcurrentHashMap |
|----------|---------|-------------------|
| Thread-safe | ❌ Không | ✅ Có |
| Null keys/values | ✅ Cho phép | ❌ Ném NPE |
| Iterator | Fail-fast | Weakly consistent |
| Memory overhead | Thấp | Cao (~20-30%) |
| Single-thread perf | Cao nhất | Cao (gần HashMap) |
| Multi-thread read | Không an toàn | Lock-free, rất nhanh |
| Multi-thread write | Không an toàn | Lock per bucket |

### 3.2 Khi Nào Dùng Gì?

```java
// ✅ Thread-local data - HashMap tốt hơn
ThreadLocal<Map<String, Config>> threadConfigs = ThreadLocal.withInitial(HashMap::new);

// ✅ Single-thread hoặc synchronized bên ngoài - HashMap
private final Map<String, User> users = new HashMap<>();
public synchronized User getUser(String id) { return users.get(id); }

// ✅ Multi-thread concurrent access - ConcurrentHashMap
private final Map<String, Session> sessions = new ConcurrentHashMap<>();

// ⚠️ Cần atomic compound operations?
// computeIfAbsent, compute, merge đã được implement atomic
sessions.computeIfAbsent(userId, k -> createExpensiveSession(k));
```

---

## 4. Anti-Patterns & Rủi Ro

### 4.1 HashMap Anti-Patterns

#### ❌ Bad: Key Mutable với Hashcode thay đổi

```java
// ANTI-PATTERN: Key có thể thay đổi sau khi put
public class MutableKey {
    private int id;
    private String data; // Thay đổi data ảnh hưởng hashCode?
    
    @Override
    public int hashCode() { return Objects.hash(id, data); }
}

Map<MutableKey, Value> map = new HashMap<>();
MutableKey key = new MutableKey(1, "original");
map.put(key, value);

key.setData("modified"); // 💥 Hash code thay đổi!
// Giờ map.get(key) trả về null dù key đó vẫn "bên trong"
```

#### ✅ Solution: Immutable Keys

```java
public final class ImmutableKey {
    private final int id;
    private final String data;
    
    // Constructor, getters
    // hashCode và equals chỉ dựa trên final fields
}
```

### 4.2 ConcurrentHashMap Anti-Patterns

#### ❌ Bad: Size() trong Loop

```java
// ANTI-PATTERN: size() không phải O(1) trong CHM
while (map.size() > MAX_SIZE) {  // Mỗi lần là O(n)!
    map.remove(someKey);
}
```

#### ✅ Solution: Dùng bounding methods

```java
// Java 8+ có newKeySet() cho bounded sets
// Hoặc tracking size riêng với LongAdder

// Hoặc dùng ConcurrentLinkedQueue + Map cho LRU
```

#### ❌ Bad: computeIfAbsent với Blocking Operation

```java
// NGUY HIỂM: Block trong computeIfAbsent giữ lock!
map.computeIfAbsent(key, k -> {
    // ⚠️ Giữ bucket lock trong khi...
    return callExternalApi(k); // ...chờ network!
});
```

#### ✅ Solution: Double-checked pattern

```java
// Pattern: Check-Compute-Check
V value = map.get(key);
if (value == null) {
    V newValue = createExpensiveValue(key); // Không giữ lock
    V existing = map.putIfAbsent(key, newValue);
    value = (existing != null) ? existing : newValue;
}
```

---

## 5. Java 21+ Enhancements

### 5.1 Sequenced Collections (Java 21)

```java
// LinkedHashMap là SequencedMap
SequencedMap<String, Integer> map = new LinkedHashMap<>();
map.putFirst("a", 1); // Java 21+
map.putLast("z", 26);
String first = map.firstEntry().getKey();
```

### 5.2 Record Classes làm Keys (Java 16+)

```java
// Record: Auto-generated immutable, equals, hashCode
public record UserKey(String email, UUID tenantId) {}

// Perfect Map key - immutable, proper hash distribution
Map<UserKey, UserProfile> userCache = new ConcurrentHashMap<>();
```

---

## 6. Demo Code

### Demo 1: Hash Collision Visualization

```java
import java.util.*;

public class HashCollisionDemo {
    
    // Class tạo intentional collision
    static class CollisionKey {
        private final String value;
        private final int forcedHash;
        
        CollisionKey(String value, int forcedHash) {
            this.value = value;
            this.forcedHash = forcedHash;
        }
        
        @Override 
        public int hashCode() { return forcedHash; }
        
        @Override 
        public boolean equals(Object o) {
            return o instanceof CollisionKey && 
                   ((CollisionKey)o).value.equals(value);
        }
        
        @Override
        public String toString() { return value + "(hash=" + forcedHash + ")"; }
    }
    
    public static void main(String[] args) {
        // Tạo 10 keys với cùng hash code
        Map<CollisionKey, String> map = new HashMap<>();
        
        System.out.println("=== Hash Collision Demo ===");
        for (int i = 0; i < 10; i++) {
            CollisionKey key = new CollisionKey("key-" + i, 12345);
            map.put(key, "value-" + i);
            System.out.println("Inserted: " + key);
        }
        
        System.out.println("\nMap size: " + map.size());
        System.out.println("All keys hash to: 12345");
        System.out.println("Internal: Linked list → Tree (n >= 8)");
    }
}
```

### Demo 2: ConcurrentHashMap vs SynchronizedHashMap

```java
import java.util.*;
import java.util.concurrent.*;

public class ConcurrentPerformanceDemo {
    
    private static final int THREADS = 16;
    private static final int OPERATIONS = 1_000_000;
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Concurrent Map Performance ===\n");
        
        // Test 1: Synchronized HashMap
        Map<String, Integer> syncMap = Collections.synchronizedMap(new HashMap<>());
        long syncTime = benchmark(syncMap);
        System.out.printf("SynchronizedHashMap: %,d ms%n", syncTime);
        
        // Test 2: ConcurrentHashMap
        Map<String, Integer> concurrentMap = new ConcurrentHashMap<>();
        long concurrentTime = benchmark(concurrentMap);
        System.out.printf("ConcurrentHashMap:   %,d ms%n", concurrentTime);
        
        System.out.printf("\nSpeedup: %.2fx%n", (double)syncTime / concurrentTime);
    }
    
    static long benchmark(Map<String, Integer> map) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch latch = new CountDownLatch(THREADS);
        long start = System.currentTimeMillis();
        
        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            executor.submit(() -> {
                for (int i = 0; i < OPERATIONS / THREADS; i++) {
                    String key = "key-" + threadId + "-" + i;
                    map.put(key, i);
                    map.get(key);
                }
                latch.countDown();
            });
        }
        
        latch.await();
        executor.shutdown();
        return System.currentTimeMillis() - start;
    }
}
```

### Demo 3: Memory Overhead Analysis

```java
import java.lang.instrument.Instrumentation;
import java.util.*;
import java.util.concurrent.*;

public class MemoryOverheadDemo {
    
    public static void main(String[] args) {
        Runtime runtime = Runtime.getRuntime();
        
        int size = 100_000;
        
        // Warmup
        System.gc();
        
        long mem1 = measureMemory(() -> {
            Map<String, String> map = new HashMap<>();
            for (int i = 0; i < size; i++) {
                map.put("key" + i, "value" + i);
            }
            return map;
        });
        
        System.gc();
        
        long mem2 = measureMemory(() -> {
            Map<String, String> map = new ConcurrentHashMap<>();
            for (int i = 0; i < size; i++) {
                map.put("key" + i, "value" + i);
            }
            return map;
        });
        
        System.out.println("=== Memory Overhead (" + size + " entries) ===");
        System.out.printf("HashMap:             ~%,d bytes%n", mem1);
        System.out.printf("ConcurrentHashMap:   ~%,d bytes%n", mem2);
        System.out.printf("Overhead:            %.1f%%%n", 100.0 * (mem2 - mem1) / mem1);
    }
    
    static long measureMemory(Supplier<Map<String, String>> factory) {
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long before = rt.totalMemory() - rt.freeMemory();
        
        Map<String, String> map = factory.get();
        
        long after = rt.totalMemory() - rt.freeMemory();
        return after - before;
    }
}
```

---

## 7. Tóm Tắt Cho Senior Engineer

### Key Takeaways

1. **HashMap**: 
   - Treeify ở threshold = 8, Untreeify = 6
   - Resize x2 capacity khi load factor > 0.75
   - Hash = hashCode ^ (hashCode >>> 16) để cải thiện distribution

2. **ConcurrentHashMap**:
   - Java 8+: Lock stripping ở bucket level thay vì segment level
   - Get là lock-free (volatile reads)
   - CAS cho empty bucket insertion
   - Counter cells giảm contention trên size()

3. **Rủi ro thường gặp**:
   - Mutable keys trong HashMap
   - Blocking operations trong computeIfAbsent
   - Sử dụng size() trong vòng lặp với CHM

4. **Performance**:
   - CHM read ~ gần bằng HashMap (lock-free)
   - CHM write >> Synchronized HashMap (fine-grained locking)
   - Memory overhead ~ 20-30% so với HashMap

---

## 📚 References

1. [OpenJDK HashMap Source](https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/HashMap.java)
2. [OpenJDK ConcurrentHashMap Source](https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/concurrent/ConcurrentHashMap.java)
3. "Java Performance: The Definitive Guide" - Scott Oaks
4. "Java Concurrency in Practice" - Brian Goetz
5. [JEP 186: Collection Streams](https://openjdk.org/jeps/186)

---

*Research completed: 2026-03-26*  
*Senior Backend Architect - Deep Research Series*
