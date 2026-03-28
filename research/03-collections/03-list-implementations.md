# List Implementations Deep Dive

> **Mục tiêu**: Hiểu bản chất cấu trúc dữ liệu, cơ chế hoạt động ở tầng JVM, và trade-off thực sự giữa ArrayList, LinkedList và CopyOnWriteArrayList - không chỉ dừng ở "ArrayList truy cập nhanh, LinkedList thêm/xóa nhanh".

---

## 1. Bản Chất Cấu Trúc Dữ Liệu

### 1.1 ArrayList: Dynamic Array với Memory Contiguity

ArrayList không phải là "array động đơn thuần". Nó là **contiguous memory block** với chiến lược growth cụ thể.

```
┌─────────────────────────────────────────────────────────────────┐
│                         ArrayList Structure                       │
├─────────────────────────────────────────────────────────────────┤
│  elementData (Object[])                                          │
│  ┌─────────┬─────────┬─────────┬─────────┬─────────┬─────────┐  │
│  │  Ref A  │  Ref B  │  Ref C  │  null   │  null   │  null   │  │
│  └────┬────┴────┬────┴────┬────┴─────────┴─────────┴─────────┘  │
│       │         │         │                                      │
│       ▼         ▼         ▼                                      │
│    Object A   Object B  Object C   (Heap objects)                │
│                                                                  │
│  size = 3                                                        │
│  capacity = 6  (elementData.length)                              │
└─────────────────────────────────────────────────────────────────┘
```

**Bản chất bộ nhớ quan trọng:**

- `elementData` là mảng Object references (4 bytes mỗi ref trên 32-bit, 8 bytes trên 64-bit với compressed oops)
- Các objects thực tế nằm rải rác trên heap, không contiguous với array
- Traversal nhanh nhờ **cache locality của references** - CPU prefetch hiệu quả

**Growth Strategy (JDK 8+):**

```java
// Khi add() vượt quá capacity
grow(minCapacity):
    oldCapacity = elementData.length
    newCapacity = oldCapacity + (oldCapacity >> 1)  // Tăng 50%
    // Hoặc minCapacity nếu vẫn chưa đủ
    elementData = Arrays.copyOf(elementData, newCapacity)
```

> ⚠️ **Trade-off Growth**: Tăng 50% là đánh đổi giữa số lần resize (CPU) và memory waste. Tăng 100% (double) giảm resize nhưng lãng phí nhiều hơn. Growth factor càng cao, memory overhead càng lớn.

**Amortized Analysis của `add()`:**

| Operation | Worst Case | Amortized | Memory |
|-----------|------------|-----------|--------|
| add() at end | O(n) khi resize | O(1) | O(n) |
| add() at index | O(n) | O(n) | O(1) |
| get(index) | O(1) | O(1) | O(1) |
| remove(index) | O(n) | O(n) | O(1) |

**Tại sao `add(E)` là O(1) amortized?**

Giả sử N phần tử được thêm vào, capacity ban đầu là C:
- Số lần resize: log₁.₅(N/C)
- Mỗi resize copy: C, 1.5C, 2.25C, ... tổng < 3N
- Tổng cost resize: O(N)
- Tổng cost thêm N phần tử: O(N)
- Amortized per operation: O(N)/N = O(1)

---

### 1.2 LinkedList: Doubly-Linked List với Node Allocation

LinkedList trong Java là **doubly-linked list với hai con trỏ đầu/cuối**.

```
┌─────────────────────────────────────────────────────────────────┐
│                        LinkedList Structure                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  first                              last                         │
│    │                                  │                          │
│    ▼                                  ▼                          │
│  ┌────────┐    ┌────────┐    ┌────────┐    ┌────────┐          │
│  │  Node A│───▶│  Node B│───▶│  Node C│───▶│  Node D│          │
│  │item: A │    │item: B │    │item: C │    │item: D │          │
│  │prev:null│◀───│prev: A │◀───│prev: B │◀───│prev: C │          │
│  │next: B │    │next: C │    │next: D │    │next:null│          │
│  └────────┘    └────────┘    └────────┘    └────────┘          │
│                                                                  │
│  size = 4                                                        │
│  (Không có capacity - chỉ có size)                              │
└─────────────────────────────────────────────────────────────────┘
```

**Node Structure:**

```java
private static class Node<E> {
    E item;           // Reference to object (4/8 bytes)
    Node<E> next;     // Reference (4/8 bytes)
    Node<E> prev;     // Reference (4/8 bytes)
    // Object header: 12 bytes (64-bit JVM)
    // Total: ~24-32 bytes per node + object header overhead
}
```

**Memory Overhead Analysis (64-bit, compressed oops):**

| Component | Size per Element |
|-----------|-----------------|
| ArrayList | 4 bytes (reference) |
| LinkedList | 24 bytes (Node object) + 4 bytes (item ref) + alignment = ~32 bytes |
| **Ratio** | **LinkedList ~8x memory so với ArrayList cho cùng data** |

**Add Operation Detail:**

```java
// add(E e) - append at end
linkLast(E e):
    final Node<E> l = last
    final Node<E> newNode = new Node<>(l, e, null)  // 1. Allocate node
    last = newNode
    if (l == null)
        first = newNode
    else
        l.next = newNode
    size++
    modCount++
```

**Complexity Analysis LinkedList:**

| Operation | Complexity | Thực tế |
|-----------|------------|---------|
| add(E) | O(1) | Thực sự O(1), nhưng có allocation cost |
| add(index, E) | O(n) | Phải traverse đến index trước |
| get(index) | O(n) | Traverse từ đầu hoặc cuối (index < size/2) |
| remove(index) | O(n) | Traverse + unlink |
| remove(Object) | O(n) | Linear search + unlink |
| listIterator | O(1) | Iterator duyệt tuần tự hiệu quả |

---

### 1.3 CopyOnWriteArrayList: Immutable Snapshot Semantics

**Bản chất**: Mỗi mutation tạo **bản sao mới của array**, reads nhìn vào snapshot immutable.

```
┌─────────────────────────────────────────────────────────────────┐
│                 CopyOnWriteArrayList Structure                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Thread 1 đọc ───────▶  ┌─────────────┐                         │
│  Thread 2 đọc ───────▶  │ array v1    │  [A, B, C]              │
│  Thread 3 đọc ───────▶  │ snapshot    │                         │
│                         └─────────────┘                         │
│                                   │                              │
│                                   ▼ (khi add("D"))               │
│  Thread 4 write ──────▶  ┌─────────────┐                         │
│                          │ array v2    │  [A, B, C, D] (new)    │
│                          │ (atomic set)│                         │
│                          └─────────────┘                         │
│                                                                  │
│  - Không có lock khi read                                       │
│  - Write tạo copy mới + atomic replace                          │
│  - Iterator không throw ConcurrentModificationException         │
└─────────────────────────────────────────────────────────────────┘
```

**Write Operation (add):**

```java
public boolean add(E e) {
    synchronized (lock) {           // 1. Lock để serialize writes
        Object[] es = getArray();
        int len = es.length;
        es = Arrays.copyOf(es, len + 1);  // 2. Copy toàn bộ array
        es[len] = e;                     // 3. Thêm element
        setArray(es);                    // 4. Atomic replace
        return true;
    }
}
```

**Iterator Behavior:**

```java
CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();
list.add("A");
list.add("B");

Iterator<String> itr = list.iterator();  // Snapshot tại thời điểm này
list.add("C");                           // Write không ảnh hưởng iterator

while (itr.hasNext()) {
    System.out.println(itr.next());  // In: A, B (không có C)
}
```

**Complexity:**

| Operation | Complexity | Cost thực |
|-----------|------------|-----------|
| get(index) | O(1) | No lock, direct array access |
| add(E) | O(n) | Copy toàn bộ array |
| remove(index) | O(n) | Copy toàn bộ array trừ phần tử bị xóa |
| contains | O(n) | Linear search |
| size() | O(1) | Direct field |

---

## 2. So Sánh Chi Tiết và Trade-off

### 2.1 ArrayList vs LinkedList

| Tiêu chí | ArrayList | LinkedList | Winner |
|----------|-----------|------------|--------|
| **Random Access** | O(1) - direct array indexing | O(n) - traverse | ArrayList |
| **Sequential Access** | O(n), cache-friendly | O(n), pointer chasing | ArrayList |
| **Add at end** | O(1) amortized, occasional O(n) resize | O(1) true, allocation per op | ArrayList* |
| **Add at middle** | O(n) - shift elements | O(n) - traverse + link | ArrayList |
| **Remove middle** | O(n) - shift elements | O(n) - traverse + unlink | ArrayList |
| **Memory overhead** | ~4 bytes/element | ~32 bytes/element | ArrayList |
| **Cache locality** | Excellent | Poor | ArrayList |
| **Iterator remove** | O(n) | O(1) | LinkedList |
| **ListIterator bidirectional** | Supported | Native, efficient | LinkedList |

**Khi nào LinkedList thực sự tốt hơn?**

Chỉ khi:
1. **Frequent insertions/removals tại vị trí đã biết** (ví dụ: dùng ListIterator, không phải index)
2. **Không cần random access**
3. **Memory không phải vấn đề**
4. **Dataset nhỏ** (cache miss impact thấp)

> ⚠️ **Thực tế**: Trong 95% use cases, ArrayList outperform LinkedList ngay cả khi thêm/xóa ở giữa, nhờ cache locality và CPU prefetching.

### 2.2 CopyOnWriteArrayList vs Synchronized List

```java
// Cách 1: Collections.synchronizedList
List<String> syncList = Collections.synchronizedList(new ArrayList<>());
// Lock trên mọi read/write

// Cách 2: CopyOnWriteArrayList  
List<String> cowList = new CopyOnWriteArrayList<>();
// Lock chỉ trên write, no lock read
```

| Scenario | Collections.synchronizedList | CopyOnWriteArrayList |
|----------|------------------------------|---------------------|
| **Read-heavy, rare writes** | Poor - lock contention | Excellent |
| **Write-heavy** | Okay | Poor - copy cost |
| **Iterator safety** | ConcurrentModificationException | Safe snapshot |
| **Memory consistency** | Weak consistency (latest value) | Eventual consistency |
| **Memory overhead writes** | None | O(n) per write |

---

## 3. Anti-patterns và Pitfall

### 3.1 ArrayList Pitfalls

**Pitfall 1: Không set initial capacity**

```java
// BAD: 11 lần resize cho 1000 elements
List<Data> list = new ArrayList<>();
for (int i = 0; i < 1000; i++) {
    list.add(new Data());
}

// GOOD: Single allocation
List<Data> list = new ArrayList<>(1000);
```

**Pitfall 2: add(index, element) trong loop**

```java
// BAD: O(n²) total
for (int i = 0; i < n; i++) {
    list.add(0, element);  // Shift n elements mỗi lần
}

// GOOD: Add at end rồi reverse, hoặc dùng LinkedList với ListIterator
```

**Pitfall 3: ConcurrentModificationException**

```java
// BAD
for (String s : list) {
    if (s.startsWith("temp")) {
        list.remove(s);  // ConcurrentModificationException
    }
}

// GOOD: Iterator.remove() hoặc removeIf()
list.removeIf(s -> s.startsWith("temp"));
```

### 3.2 LinkedList Pitfalls

**Pitfall 1: Sử dụng get(index) trong loop**

```java
// BAD: O(n²) - LinkedList.get(i) là O(n)
for (int i = 0; i < list.size(); i++) {
    process(list.get(i));
}

// GOOD: O(n) - Iterator duyệt tuần tự
for (String s : list) {
    process(s);
}
```

**Pitfall 2: Memory pressure với large lists**

```java
// 1M elements trong LinkedList ≈ 32MB overhead
// Cùng data trong ArrayList ≈ 4MB
// Trong heap pressure situations, điều này quan trọng
```

### 3.3 CopyOnWriteArrayList Pitfalls

**Pitfall 1: Sử dụng cho write-heavy workloads**

```java
// BAD: Copy 1000 elements mỗi lần add
CopyOnWriteArrayList<Event> events = new CopyOnWriteArrayList<>();
for (int i = 0; i < 10000; i++) {
    events.add(new Event());  // O(n) mỗi lần = O(n²) total
}
```

**Pitfall 2: Giả định iterator thấy writes mới nhất**

```java
Iterator<String> it = cowList.iterator();
cowList.add("new");  
// Iterator không thấy "new" - snapshot tại thời điểm tạo iterator
```

---

## 4. Production Concerns

### 4.1 Memory Monitoring

**ArrayList capacity waste:**

```java
// Kiểm tra wasted capacity
int wastedCapacity = list.capacity() - list.size();  // Không có API trực tiếp
// Phải dùng reflection hoặc tracking

// Trim nếu cần
((ArrayList<?>) list).trimToSize();  // Release excess capacity
```

**LinkedList node overhead:**

```java
// Với heap dump analysis, tìm:
// - java.util.LinkedList$Node instances
// - Object thực tế chỉ chiếm 1/8 memory của LinkedList
```

### 4.2 Performance Tuning

**ArrayList initial capacity guidelines:**

| Use Case | Recommended Initial Capacity |
|----------|------------------------------|
| Known size upfront | Exact size |
| Database result | fetchSize * 1.1 |
| Stream processing | 100-1000 (benchmark) |
| Unknown, growing | Default (10) có thể ổn |

**GC Considerations:**

- ArrayList resize tạo **temporary arrays** cho đến khi old array được GC
- LinkedList tạo **nhiều small objects** - pressure lên young generation
- CopyOnWriteArrayList tạo **array mới mỗi write** - old arrays cần GC

### 4.3 Thread Safety Patterns

| Pattern | Implementation | Use Case |
|---------|---------------|----------|
| **Read-only after publish** | `Collections.unmodifiableList()` | Immutable config |
| **Copy-on-write** | `CopyOnWriteArrayList` | Event listeners, read-heavy |
| **Synchronized** | `Collections.synchronizedList()` | General purpose, low contention |
| **Concurrent modifications** | External locking | Complex operations |

---

## 5. Khuyến Nghị Thực Chiến

### Default Choice

```
ArrayList là default choice cho hầu hết use cases.
Chỉ chọn LinkedList khi profiling chứng minh ArrayList chậm.
Chỉ chọn CopyOnWriteArrayList cho event listeners/read-heavy.
```

### Decision Tree

```
                    ┌──────────────────┐
                    │ Cần thread-safe? │
                    └────────┬─────────┘
                             │
            ┌────────────────┼────────────────┐
            ▼                ▼                ▼
         Không          Read-heavy         Write-heavy
            │          (rare writes)      hoặc balanced
            ▼                │                ▼
     ┌──────────────┐        │      ┌──────────────────┐
     │ Random access│   CopyOnWrite    │SynchronizedList  │
     │ thường xuyên?│   ArrayList      │hoặc concurrent   │
     └──────┬───────┘   hoặc          │collections       │
            │          immutable       └──────────────────┘
     ┌──────┴───────┐
     ▼              ▼
    Yes            No
     │              │
     ▼              ▼
  ArrayList    LinkedList
  (default)    (chỉ khi
               insert/remove
               tại iterator)
```

### Java 21+ Improvements

**Sequenced Collections (Java 21):**

```java
// List hiện implement SequencedCollection
List<String> list = new ArrayList<>();

// New methods
list.addFirst("first");    // Chính xác hơn add(0, e)
list.addLast("last");      // Chính xác hơn add(e)
String first = list.getFirst();
String last = list.getLast();
List<String> reversed = list.reversed();  // View, không copy
```

**Virtual Threads và List:**

```java
// Virtual threads (Project Loom) giảm impact của blocking operations
// Nhưng không thay đổi List implementation choice
// CopyOnWriteArrayList vẫn tốt cho event dispatch trong virtual thread environment
```

---

## 6. Kết Luận

**ArrayList**:
- **Bản chất**: Dynamic array với contiguous references
- **Điểm mạnh**: Cache locality, memory efficiency, O(1) random access
- **Điểm yếu**: O(n) insert/remove giữa, resize cost
- **Dùng khi**: 95% use cases, đặc biệt random access hoặc append

**LinkedList**:
- **Bản chất**: Doubly-linked list với per-node allocation
- **Điểm mạnh**: O(1) insert/remove tại iterator position
- **Điểm yếu**: ~8x memory overhead, cache miss, O(n) random access
- **Dùng khi**: Chỉ khi frequent insert/remove tại known positions, profiling chứng minh cần thiết

**CopyOnWriteArrayList**:
- **Bản chất**: Immutable snapshot semantics, lock-free reads
- **Điểm mạnh**: Thread-safe reads không cần lock, safe iteration
- **Điểm yếu**: O(n) write cost, eventual consistency
- **Dùng khi**: Read-heavy concurrent access, event listeners, config that rarely changes

> **Rule of thumb**: Bắt đầu với ArrayList. Chỉ chọn implementation khác khi có profiling data hoặc rõ ràng biết use case đặc biệt. Đừng optimize sớm.
