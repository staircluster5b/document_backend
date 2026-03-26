# Fail-fast vs Fail-safe: Phân biệt cơ chế Iteration trong môi trường đa luồng

> **Mức độ:** Senior Backend | **Thờigian nghiên cứu:** ~4-6 giờ | **Liên quan:** Concurrency, Collections, JVM Internals

---

## 1. 🎯 Bản Chất: Cơ chế hoạt động tầng thấp

### 1.1 Fail-fast Iterator - "Phát hiện sớm, thất bại nhanh"

#### Cơ chế ModCount

```java
// Trích từ java.util.ArrayList (OpenJDK 21)
public abstract class AbstractList<E> extends AbstractCollection<E> implements List<E> {
    protected transient int modCount = 0;  // Đếm số lần cấu trúc thay đổi
    
    public boolean add(E e) {
        modCount++;  // Tăng mỗi khi có structural modification
        add(e, elementData, size);
        return true;
    }
    
    public E remove(int index) {
        modCount++;  // Tăng khi xóa phần tử
        // ... implementation
    }
}
```

**Nguyên lý hoạt động:**

| Thành phần | Mô tả |
|-----------|-------|
| `modCount` | Counter volatile, tăng khi có structural modification (add/remove/clear) |
| `expectedModCount` | Iterator lưu snapshot của modCount khi khởi tạo |
| `checkForComodification()` | So sánh hai giá trị trước mỗi thao tác iterator |

```java
// Trong ArrayList.Itr
private class Itr implements Iterator<E> {
    int expectedModCount = modCount;
    
    public E next() {
        checkForComodification();  // Kiểm tra trước mọi thao tác
        // ...
    }
    
    final void checkForComodification() {
        if (modCount != expectedModCount)
            throw new ConcurrentModificationException();
    }
}
```

> ⚠️ **QUAN TRỌNG:** Fail-fast **KHÔNG ĐẢM BẢO** phát hiện mọi trường hợp concurrent modification - nó chỉ là "best-effort". Trong môi trường multi-threaded không có synchronization, modCount có thể corrupt hoặc race condition xảy ra giữa lúc check và lúc throw exception.

### 1.2 Fail-safe Iterator - "Làm việc với snapshot"

#### Cơ chế Copy-on-Write (COW)

```java
// Trích từ java.util.concurrent.CopyOnWriteArrayList
public class CopyOnWriteArrayList<E> implements List<E> {
    private transient volatile Object[] array;  // Volatile đảm bảo visibility
    
    final Object[] getArray() {
        return array;
    }
    
    public boolean add(E e) {
        synchronized (lock) {
            Object[] es = getArray();
            int len = es.length;
            Object[] newEs = Arrays.copyOf(es, len + 1);  // Tạo bản sao
            newEs[len] = e;
            array = newEs;  // Atomic update reference
            return true;
        }
    }
    
    // Iterator không bao giờ throw ConcurrentModificationException
    public Iterator<E> iterator() {
        return new COWIterator<E>(getArray(), 0);  // Snapshot tại thởi điểm tạo
    }
}
```

**Cơ chế Snapshot:**

```
Thread-1 (Iterator)          Thread-2 (Modifier)
     |                              |
     v                              v
+---------+                   +-----------+
| Lấy ref |                   | synchronized
| array[] |                   | lock        |
+---------+                   +-----------+
     |                              |
     |                         +-----------+
     |                         | Copy array|
     |                         | Modify    |
     |                         | array = new|
     |                         +-----------+
     v                              |
+---------+                         |
| Iterate |<--- Không ảnh hưởng ----+
| old ref |    (khác reference)
+---------+
```

### 1.3 So sánh cơ chế lõi

| Đặc điểm | Fail-fast | Fail-safe |
|---------|-----------|-----------|
| **Cơ chế phát hiện** | ModCount comparison | Snapshot isolation |
| **Memory overhead** | ~4 bytes (int) | O(n) - toàn bộ collection copy |
| **Behavior khi modify** | Throw `ConcurrentModificationException` | Không throw, iterate trên snapshot cũ |
| **Consistency** | Strong (latest state) | Weak (point-in-time) |
| **Thread-safety** | Không (cần external sync) | Có (internal synchronization) |
| **Performance read** | O(1) per element | O(1) per element |
| **Performance write** | O(1) amortized | O(n) - copy toàn bộ array |

---

## 2. 🛠️ Giải pháp & Công cụ chuẩn công nghiệp

### 2.1 Bản đồ Iterator trong Java Collections

```
┌─────────────────────────────────────────────────────────────────┐
│                      JAVA COLLECTIONS                            │
├─────────────────────────────┬───────────────────────────────────┤
│      FAIL-FAST              │          FAIL-SAFE                │
├─────────────────────────────┼───────────────────────────────────┤
│ ArrayList                   │ CopyOnWriteArrayList              │
│ LinkedList                  │ CopyOnWriteArraySet               │
│ HashSet                     │ ConcurrentHashMap (keySet/values) │
│ HashMap                     │ ConcurrentLinkedQueue             │
│ TreeSet                     │ ConcurrentLinkedDeque             │
│ TreeMap                     │ BlockingQueue implementations     │
│ LinkedHashSet/Map           │ ConcurrentSkipListSet/Map         │
├─────────────────────────────┴───────────────────────────────────┤
│  Cần external synchronization      │  Thread-safe out-of-the-box    │
│  trong multi-threaded env          │  nhưng có trade-offs           │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 ConcurrentHashMap - Trường hợp đặc biệt

**KHÔNG hoàn toàn Fail-safe:**

```java
ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
map.put("A", "1");
map.put("B", "2");

// Iterator là weakly consistent - không throw CME nhưng có thể thấy hoặc không thấy concurrent updates
for (String key : map.keySet()) {
    map.put("C", "3");  // ✅ Không throw exception
    // Nhưng có thể thấy hoặc không thấy "C" trong iteration hiện tại
}
```

> 📌 **Weakly Consistent (Javadoc):** "reflects the state of the map at some point at or since the creation of the iterator"

**Cơ chế Segment Lock (Java 7) vs CAS + synchronized (Java 8+):**

```java
// Java 8+ ConcurrentHashMap - Node-level locking
final V putVal(K key, V value, boolean onlyIfAbsent) {
    if (key == null || value == null) throw new NullPointerException();
    int hash = spread(key.hashCode());
    int binCount = 0;
    for (Node<K,V>[] tab = table;;) {
        Node<K,V> f; int n, i, fh; K fk; V fv;
        if (tab == null || (n = tab.length) == 0)
            tab = initTable();
        else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
            // CAS attempt - lock-free nếu bucket empty
            if (casTabAt(tab, i, null, new Node<K,V>(hash, key, value)))
                break;
        }
        else {
            synchronized (f) {  // Fine-grained lock trên node
                // ... double-check và insert/update
            }
        }
    }
}
```

### 2.3 Tooling & Monitoring

| Công cụ | Mục đích | Lệnh/Ví dụ |
|---------|---------|-----------|
| **JCStress** | Test concurrency correctness | `@JCStressTest`, `@Outcome` annotations |
| **Thread Sanitizer (TSAN)** | Phát hiện data race | `-fsanitize=thread` (JNI/native) |
| **VisualVM/JConsole** | Monitor thread contention | "Threads" tab → "Deadlock Detection" |
| **async-profiler** | Profile lock contention | `./profiler.sh -e lock -d 30 -f locks.html <pid>` |
| **Byteman** | Inject fault để test behavior | Rule-based bytecode injection |

---

## 3. ⚠️ Rủi ro & Đánh đổi (Senior-level Analysis)

### 3.1 Anti-patterns thường gặp

#### ❌ Anti-pattern 1: "Catch và Ignore"

```java
// KHÔNG BAO GIỜ LÀM ĐIỀU NÀY
for (Item item : items) {
    try {
        process(item);
        items.remove(item);  // Throw CME
    } catch (ConcurrentModificationException e) {
        // Ignore and continue?!?
        log.warn("Ignored CME");
    }
}
```

> 🚨 **Nguy hiểm:** State corruption, infinite loops, hoặc silent data loss. CME là symptom, không phải root cause.

#### ❌ Anti-pattern 2: Synchronized wrapper + Fail-fast

```java
// Vô ích - vẫn có thể CME trong cùng thread
List<String> syncList = Collections.synchronizedList(new ArrayList<>());

synchronized (syncList) {
    for (String s : syncList) {  // Iterator tạo RA ngoài synchronized block
        syncList.remove(s);      // CME vẫn xảy ra!
    }
}
```

**Fix đúng:**
```java
synchronized (syncList) {
    Iterator<String> it = syncList.iterator();  // Iterator tạo TRONG synchronized
    while (it.hasNext()) {
        String s = it.next();
        it.remove();  // ✅ Sử dụng iterator.remove()
    }
}
```

#### ❌ Anti-pattern 3: CopyOnWriteArrayList cho write-heavy workload

```java
// Write-heavy: Mỗi write O(n) copy - performance disaster
CopyOnWriteArrayList<Event> events = new CopyOnWriteArrayList<>();

// 1000 writes/sec với list size 10,000 = 10M phần tử copy/sec
for (int i = 0; i < 1000; i++) {
    events.add(new Event());  // O(n) mỗi lần!
}
```

**Khi nào dùng COW:**
- Read-heavy (1000:1 read:write ratio hoặc cao hơn)
- Small to medium collection size (< 1000 elements)
- Event listener lists, configuration caches

### 3.2 Performance & Memory Analysis

#### Memory Footprint của Fail-safe

```java
@Test
public void measureCowMemory() {
    Runtime runtime = Runtime.getRuntime();
    
    // 10,000 elements
    List<String> cowList = new CopyOnWriteArrayList<>();
    for (int i = 0; i < 10000; i++) cowList.add("item" + i);
    
    long before = runtime.totalMemory() - runtime.freeMemory();
    
    // Create 5 iterators = 5 snapshots
    List<Iterator<String>> iterators = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
        iterators.add(cowList.iterator());  // Mỗi iterator giữ ref đến array[] riêng
    }
    
    long after = runtime.totalMemory() - runtime.freeMemory();
    System.out.println("Memory overhead: " + (after - before) / 1024 + " KB");
    // Output: ~400KB cho 5 snapshots (mỗi String ref ~8 bytes + object overhead)
}
```

#### Benchmark: Fail-fast vs Fail-safe

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class IteratorBenchmark {
    
    @Param({"100", "10000"})
    private int size;
    
    private List<Integer> arrayList;
    private List<Integer> cowList;
    
    @Setup
    public void setup() {
        arrayList = new ArrayList<>();
        cowList = new CopyOnWriteArrayList<>();
        for (int i = 0; i < size; i++) {
            arrayList.add(i);
            cowList.add(i);
        }
    }
    
    @Benchmark
    public int arrayListIterate() {
        int sum = 0;
        for (int i : arrayList) sum += i;
        return sum;
    }
    
    @Benchmark
    public int cowListIterate() {
        int sum = 0;
        for (int i : cowList) sum += i;
        return sum;
    }
    
    @Benchmark
    public void cowListWrite() {
        cowList.add(999);  // O(n) copy
    }
}
```

**Kết quả điển hình (JMH):**

| Operation | ArrayList | COWArrayList | Factor |
|-----------|-----------|--------------|--------|
| Read (size=100) | ~500 ops/ms | ~480 ops/ms | 1x |
| Read (size=10000) | ~50 ops/ms | ~48 ops/ms | 1x |
| Write (size=100) | ~1000 ops/ms | ~50 ops/ms | 20x slower |
| Write (size=10000) | ~800 ops/ms | ~1 ops/ms | 800x slower |

### 3.3 Security Implications

#### Iterator Invalidation Attack

```java
// Malicious code có thể exploit iterator behavior
public void processRequest(List<String> headers) {
    Iterator<String> it = headers.iterator();
    
    // Thread từ bên ngoài modify list...
    executor.submit(() -> headers.clear());
    
    while (it.hasNext()) {
        String header = it.next();  // CME hoặc null pointer
        if (header.contains("Authorization")) {
            // Security check bypassed nếu exception xảy ra
        }
    }
}
```

**Mitigation:**
- Defensive copy ở entry point
- Sử dụng immutable collections (Java 9+ `List.of()`, Guava `ImmutableList`)

---

## 4. 🚀 Java 21+ Features liên quan

### 4.1 Sequenced Collections (JEP 431 - Java 21)

```java
// Java 21+: Ordered collections có API mới
SequencedSet<String> set = new LinkedHashSet<>();
set.addFirst("A");  // Thay vì workaround với iterator
set.addLast("Z");

// reversed() trả về reversed view - không copy
for (String s : set.reversed()) {
    // Iterate ngược
}
```

**Impact lên Iterator:** Reversed view cũng maintain fail-fast behavior như original.

### 4.2 Virtual Threads (Project Loom) - Java 21

```java
// Với Virtual Threads, blocking trên iteration có thể dẫn đến carrier thread pinning
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    for (var item : list) {  // Có thể block trong I/O
        scope.fork(() -> process(item));
    }
    scope.join();
}

// Best practice: Tách iteration khỏi blocking operations
var snapshot = List.copyOf(list);  // Defensive copy
for (var item : snapshot) {
    // blocking OK ở đây
}
```

### 4.3 Foreign Function & Memory API (Java 22 Preview)

```java
// Off-heap collections có thể implement custom iterator
// với memory segment bounds checking
Arena arena = Arena.ofConfined();
MemorySegment segment = arena.allocate(1024);

// Custom iterator với bounds checking tự động
OffHeapIterator it = new OffHeapIterator(segment);
```

### 4.4 Unnamed Patterns (Java 21 Preview, Java 22)

```java
// Enhanced for với record destructuring
for (var Entry(var key, _) : map.entrySet()) {
    // Chỉ cần key, ignore value
}
```

---

## 5. 💻 Code Demo

### Demo 1: Fail-fast Behavior Demonstration

```java
import java.util.*;

public class FailFastDemo {
    public static void main(String[] args) {
        List<String> list = new ArrayList<>(Arrays.asList("A", "B", "C", "D"));
        
        System.out.println("=== Demo 1: Concurrent Modification từ cùng thread ===");
        try {
            for (String s : list) {
                System.out.println("Reading: " + s);
                if (s.equals("B")) {
                    list.remove(s);  // ❌ Throw CME ở lần next() tiếp theo
                }
            }
        } catch (ConcurrentModificationException e) {
            System.out.println("❌ Caught ConcurrentModificationException!");
            System.out.println("   Reason: modCount changed from 4 to 5");
        }
        
        System.out.println("\n=== Demo 2: Iterator.remove() - Cách đúng ===");
        list = new ArrayList<>(Arrays.asList("A", "B", "C", "D"));
        Iterator<String> it = list.iterator();
        while (it.hasNext()) {
            String s = it.next();
            if (s.equals("B")) {
                it.remove();  // ✅ Update modCount thông qua iterator
                System.out.println("✅ Removed: " + s);
            }
        }
        System.out.println("Final list: " + list);
        
        System.out.println("\n=== Demo 3: Multi-threaded CME ===");
        List<Integer> numbers = new ArrayList<>();
        for (int i = 0; i < 1000; i++) numbers.add(i);
        
        Thread reader = new Thread(() -> {
            for (int num : numbers) {
                try { Thread.sleep(1); } catch (InterruptedException e) {}
            }
        });
        
        Thread writer = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) {}
            numbers.add(9999);  // Modify trong khi đang iterate
        });
        
        reader.start();
        writer.start();
        
        try {
            reader.join();
            System.out.println("✅ Reader completed without CME (best-effort check)");
        } catch (ConcurrentModificationException e) {
            System.out.println("❌ CME in multi-threaded scenario");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
```

### Demo 2: Fail-safe vs Snapshot Isolation

```java
import java.util.concurrent.*;
import java.util.*;

public class FailSafeDemo {
    public static void main(String[] args) throws InterruptedException {
        CopyOnWriteArrayList<String> cowList = new CopyOnWriteArrayList<>();
        cowList.add("A");
        cowList.add("B");
        cowList.add("C");
        
        System.out.println("=== Fail-safe Iterator Demo ===");
        System.out.println("Initial list: " + cowList);
        
        // Tạo iterator (snapshot tại thởi điểm này)
        Iterator<String> it = cowList.iterator();
        
        // Modify list SAU KHI iterator được tạo
        cowList.add("D");
        cowList.remove("B");
        System.out.println("After modification: " + cowList);
        
        // Iterator vẫn thấy snapshot cũ
        System.out.print("Iterator sees: ");
        while (it.hasNext()) {
            System.out.print(it.next() + " ");  // Output: A B C
        }
        System.out.println("\n");
        
        System.out.println("=== Weakly Consistent Iterator (ConcurrentHashMap) ===");
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        map.put("1", "One");
        map.put("2", "Two");
        
        Iterator<String> keyIt = map.keySet().iterator();
        System.out.println("First key: " + keyIt.next());  // "1"
        
        map.put("3", "Three");  // Concurrent modification
        
        // Iterator có thể hoặc không thấy "3" - weakly consistent
        System.out.print("Remaining keys: ");
        while (keyIt.hasNext()) {
            System.out.print(keyIt.next() + " ");
        }
        System.out.println("\n(Note: May or may not include '3')");
    }
}
```

### Demo 3: Performance Comparison

```java
import java.util.*;
import java.util.concurrent.*;

public class PerformanceComparison {
    private static final int ITERATIONS = 100_000;
    
    public static void main(String[] args) {
        System.out.println("=== Performance: ArrayList vs CopyOnWriteArrayList ===\n");
        
        // Test 1: Read-heavy workload
        System.out.println("1. Read-heavy (1000 reads : 1 write)");
        testReadHeavy(new ArrayList<>(), "ArrayList");
        testReadHeavy(new CopyOnWriteArrayList<>(), "COWArrayList");
        
        // Test 2: Write-heavy workload
        System.out.println("\n2. Write-heavy (1 read : 100 writes)");
        testWriteHeavy(new ArrayList<>(), "ArrayList");
        testWriteHeavy(new CopyOnWriteArrayList<>(), "COWArrayList");
    }
    
    static void testReadHeavy(List<Integer> list, String name) {
        for (int i = 0; i < 1000; i++) list.add(i);
        
        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            // 1000 reads
            for (int j = 0; j < 1000; j++) {
                list.get(j % list.size());
            }
            // 1 write
            list.add(i);
            list.remove(0);
        }
        long duration = System.nanoTime() - start;
        System.out.printf("   %s: %.2f ms%n", name, duration / 1_000_000.0);
    }
    
    static void testWriteHeavy(List<Integer> list, String name) {
        for (int i = 0; i < 100; i++) list.add(i);
        
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            // 1 read
            list.get(0);
            // 100 writes
            for (int j = 0; j < 100; j++) {
                list.add(i * 100 + j);
            }
        }
        long duration = System.nanoTime() - start;
        System.out.printf("   %s: %.2f ms%n", name, duration / 1_000_000.0);
    }
}
```

### Demo 4: Thread-safe Patterns

```java
import java.util.*;
import java.util.concurrent.*;

public class ThreadSafePatterns {
    
    // Pattern 1: Defensive Copy
    public List<String> getUsersDefensiveCopy(List<String> users) {
        return new ArrayList<>(users);  // Return copy
    }
    
    // Pattern 2: Unmodifiable View (Java 9+)
    public List<String> getUsersUnmodifiable(List<String> users) {
        return List.copyOf(users);  // Immutable copy
    }
    
    // Pattern 3: Concurrent Collection cho multi-threaded
    public void processConcurrently(List<Task> tasks) {
        BlockingQueue<Task> queue = new LinkedBlockingQueue<>(tasks);
        
        // Multiple consumers
        ExecutorService executor = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 4; i++) {
            executor.submit(() -> {
                while (!queue.isEmpty()) {
                    Task task = queue.poll();
                    if (task != null) task.execute();
                }
            });
        }
        executor.shutdown();
    }
    
    // Pattern 4: Snapshot iteration với COW
    public void notifyListeners(CopyOnWriteArrayList<Listener> listeners, Event event) {
        // Snapshot được tạo tự động, an toàn để iterate
        for (Listener listener : listeners) {
            listener.onEvent(event);  // Có thể add/remove listeners trong callback
        }
    }
    
    interface Task { void execute(); }
    interface Listener { void onEvent(Event e); }
    class Event {}
}
```

---

## 6. 📊 Decision Matrix

| Scenario | Khuyến nghị | Lý do |
|----------|------------|-------|
| Single-threaded + cần modify trong loop | `Iterator.remove()` | Clean, efficient |
| Multi-threaded read-heavy | `CopyOnWriteArrayList` | No locking overhead on read |
| Multi-threaded write-heavy | `ConcurrentLinkedQueue` hoặc `BlockingQueue` | Lock-free hoặc fine-grained locking |
| Cần random access + thread-safe | `Collections.synchronizedList` + manual sync | Direct index access |
| Event listeners/observers | `CopyOnWriteArrayList` | Safe iteration during notification |
| Cache configuration | `ConcurrentHashMap` | High throughput, atomic operations |
| Financial transactions | Immutable collections (Guava) | Strong consistency guarantee |

---

## 7. 📚 Tài liệu tham khảo

1. **Java Documentation:**
   - [`ConcurrentModificationException`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/ConcurrentModificationException.html)
   - [`CopyOnWriteArrayList`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CopyOnWriteArrayList.html)
   - [Collections Framework Overview](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/package-summary.html)

2. **Books:**
   - "Java Concurrency in Practice" - Brian Goetz (Chapter 5: Building Blocks)
   - "Effective Java" - Joshua Bloch (Item 81: Prefer concurrency utilities to wait and notify)

3. **JEPs:**
   - [JEP 431: Sequenced Collections](https://openjdk.org/jeps/431) (Java 21)
   - [JEP 444: Virtual Threads](https://openjdk.org/jeps/444) (Java 21)

---

*Last updated: 2026-03-26 | Research by: Senior Backend Architect*
