# Lựa Chọn Cấu Trúc Dữ Liệu: ArrayList vs LinkedList, HashSet vs TreeSet

> **Phân tích độ phức tạp thuật toán và đánh đổi trong thực tế hệ thống production**

---

## 📋 Tóm Tắt Chuyên Gia

| Cấu trúc dữ liệu | Ưu điểm chính | Nhược điểm chính | Use case lý tưởng |
|-----------------|---------------|------------------|-------------------|
| **ArrayList** | O(1) random access, cache-friendly | O(n) insert/delete giữa list | Random access nhiều, thêm/xóa ít ở cuối |
| **LinkedList** | O(1) insert/delete đầu/cuối | O(n) access, overhead bộ nhớ lớn | Queue/Deque, thêm/xóa liên tục ở 2 đầu |
| **HashSet** | O(1) add/contains/remove | Không có thứ tự, worst-case O(n) | Kiểm tra tồn tại nhanh, không cần order |
| **TreeSet** | O(log n) có thứ tự tự nhiên | Chậm hơn HashSet ~5-10x | Range query, cần sorted iteration |

---

## 1. ArrayList vs LinkedList - Phân Tích Tầng Thấp

### 1.1 Bản Chất Bộ Nhớ

```
┌─────────────────────────────────────────────────────────────┐
│                    ARRAYLIST MEMORY LAYOUT                   │
├─────────────────────────────────────────────────────────────┤
│  [0]    [1]    [2]    [3]    [4]    [5]    ...    [n-1]     │
│  ├───┬───┬───┬───┬───┬───┬───────┬───────┬─────────────────┤
│  │Ref│Ref│Ref│Ref│Ref│Ref│  ...  │  ...  │  (contiguous)   │
│  └───┴───┴───┴───┴───┴───┴───────┴───────┴─────────────────┘
│  ↑                                                           │
│  └── Cache Line: Các phần tử liền kề → Prefetch hiệu quả    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                   LINKEDLIST MEMORY LAYOUT                   │
├─────────────────────────────────────────────────────────────┤
│  Node 1          Node 2          Node 3                     │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐              │
│  │prev│data│next│→│prev│data│next│→│prev│data│next│→ null  │
│  │null│ A  │  ──┘│←──│ B  │  ──┘│←──│ C  │ null│           │
│  └──────────┘    └──────────┘    └──────────┘              │
│       ↑                                                     │
│       └── Phân tán khắp heap → Cache miss, pointer chasing  │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 Phân Tích Big O Chi Tiết

| Operation | ArrayList | LinkedList | Implementation Detail |
|-----------|-----------|------------|----------------------|
| `get(index)` | **O(1)** | O(n) | Array: direct offset calculation. LinkedList: traverse from head/tail |
| `add(E)` | **O(1)** amortized | **O(1)** | ArrayList: grow array if needed (System.arraycopy). LinkedList: link tail |
| `add(index, E)` | O(n) | O(n) | ArrayList: System.arraycopy shift. LinkedList: traverse to index |
| `remove(index)` | O(n) | O(n) | Tương tự add - phải shift hoặc traverse |
| `remove(Object)` | O(n) | O(n) | ArrayList: tìm + shift. LinkedList: tìm + unlink |
| `contains(Object)` | O(n) | O(n) | Linear search cả hai |
| `iterator.remove()` | **O(n)** | **O(1)** | ⭐ LinkedList thắng khi xóa trong iteration |
| Memory overhead | ~4 bytes/object | ~24 bytes/node | LinkedList: 2 references + object header |

### 1.3 Cache Locality - Yếu Tố Quyết Định Performance

> **"Cache miss đắt gấp 100-1000 lần cache hit"**

```java
// Benchmark: Duyệt 1M elements
// Kết quả thực tế (JMH benchmark):
// ArrayList:  ~2-3ms
// LinkedList: ~50-100ms (20-50x slower!)

for (int i = 0; i < list.size(); i++) {
    // ArrayList: CPU prefetch hoạt động hiệu quả
    // LinkedList: Pointer chasing liên tục, cache miss
    process(list.get(i)); 
}
```

**Tại sao LinkedList chậm hơn nhiều trong thực tế?**

1. **Cache Line**: ArrayList elements nằm liền kề → 1 cache line chứa nhiều references
2. **Prefetcher**: CPU dự đoán và load trước, giảm latency
3. **TLB pressure**: LinkedList trải rộng heap → nhiều page faults
4. **Branch prediction**: Array indexing predictable, pointer chasing không

### 1.4 Khi Nào Dùng LinkedList?

**Chỉ khi thỏa mãn TẤT CẢ các điều kiện:**

```java
// ✅ Use case 1: Queue/Deque với thêm/xóa 2 đầu liên tục
Deque<String> queue = new LinkedList<>();
queue.addLast("task");    // O(1)
queue.removeFirst();       // O(1)

// ✅ Use case 2: Xóa liên tục trong iteration
ListIterator<Node> it = list.listIterator();
while (it.hasNext()) {
    if (shouldRemove(it.next())) {
        it.remove(); // ArrayList: O(n), LinkedList: O(1)
    }
}

// ❌ KHÔNG dùng LinkedList cho:
// - Random access: list.get(i) trong loop
// - Chỉ thêm/xóa ở cuối
// - Data size < 10,000 (overhead không đáng)
```

---

## 2. HashSet vs TreeSet - Trade-off Rõ Ràng

### 2.1 Bản Chất Implementation

```
┌─────────────────────────────────────────────────────────────┐
│                      HASHSET INTERNALS                       │
├─────────────────────────────────────────────────────────────┤
│  HashMap<E, Object> (dummy value PRESENT)                   │
│                                                             │
│  Bucket 0    Bucket 1    Bucket 2    Bucket 3               │
│  ┌─────┐    ┌─────┐    ┌─────┐    ┌─────┐                  │
│  │ A   │    │ B→C │    │ D   │    │ E→F │                  │
│  └─────┘    └─────┘    └─────┘    └─────┘                  │
│             (linked list khi collision)                     │
│             (tree khi bucket > 8 elements - Java 8+)        │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                     TREESET INTERNALS                        │
├─────────────────────────────────────────────────────────────┤
│  NavigableMap<E, Object> = TreeMap                          │
│                                                             │
│              50 (root, black)                               │
│             /    \                                          │
│          30        70                                       │
│         /  \      /  \                                      │
│       20   40   60    80                                    │
│      /                                                      │
│    10                                                       │
│                                                             │
│  Red-Black Tree: self-balancing BST                         │
│  Height ≤ 2*log₂(n+1) - đảm bảo O(log n)                    │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Phân Tích Performance

| Operation | HashSet | TreeSet | Notes |
|-----------|---------|---------|-------|
| `add(E)` | **O(1)** avg, O(n) worst | **O(log n)** | HashSet: resize khi load > 0.75 |
| `remove(E)` | **O(1)** avg, O(n) worst | **O(log n)** | HashSet worst: all collide |
| `contains(E)` | **O(1)** avg, O(n) worst | **O(log n)** | TreeSet: always balanced |
| `iteration` | O(n) | O(n) | HashSet: insertion order (Java 8+ LinkedHashSet). TreeSet: sorted |
| `first()/last()` | N/A | **O(log n)** | TreeSet exclusive |
| `subSet(a, b)` | N/A | **O(log n + k)** | ⭐ Range query - TreeSet thắng |
| `comparator()` | N/A | O(1) | TreeSet cần Comparable/Comparator |

### 2.3 Memory Overhead Comparison

| Structure | Overhead per entry | Total overhead 1M entries |
|-----------|-------------------|---------------------------|
| HashSet | ~32 bytes (HashMap.Node) | ~32 MB |
| TreeSet | ~40 bytes (TreeMap.Entry) | ~40 MB |
| LinkedHashSet | ~40 bytes | ~40 MB |

### 2.4 Khi Nào Chọn TreeSet?

```java
// ✅ Use case 1: Range queries - tìm kiếm khoảng
TreeSet<Integer> scores = new TreeSet<>();
// ... add scores ...
SortedSet<Integer> top10 = scores.tailSet(90); // ≥90
SortedSet<Integer> mid = scores.subSet(60, 80); // [60, 80)

// ✅ Use case 2: Cần thứ tự sorted iteration
for (Integer score : scores.descendingSet()) {
    // Duyệt từ cao đến thấp
}

// ✅ Use case 3: Cần floor/ceiling operations
Integer nearestLower = scores.floor(target);  // ≤ target
Integer nearestHigher = scores.ceiling(target); // ≥ target

// ❌ KHÔNG dùng TreeSet cho:
// - Chỉ cần kiểm tra tồn tại (contains)
// - Không cần thứ tự
// - Cần O(1) operations (HashSet nhanh hơn 5-10x)
```

---

## 3. Anti-patterns & Rủi Ro Production

### 3.1 ArrayList Anti-pattern: Khởi tạo không capacity

```java
// ❌ BAD: Resize 10→16→25→40→64... nhiều lần copy
List<String> list = new ArrayList<>();
for (int i = 0; i < 1000000; i++) {
    list.add("item" + i); // 20+ resizes!
}

// ✅ GOOD: Pre-size nếu biết trước
List<String> list = new ArrayList<>(1_000_000);

// ✅ BETTER: Stream API tự optimize
List<String> list = IntStream.range(0, 1_000_000)
    .mapToObj(i -> "item" + i)
    .collect(Collectors.toList());
```

### 3.2 LinkedList trong Loop - Performance Killer

```java
// ❌ CRITICAL: O(n²) cho operation tưởng chừng O(n)
List<String> linkedList = new LinkedList<>();
// ... populate ...
for (int i = 0; i < linkedList.size(); i++) {
    // get(i) trên LinkedList: O(n) mỗi lần!
    // Total: O(n²) - chết với 10k+ elements
    process(linkedList.get(i));
}

// ✅ GOOD: Iterator O(n)
for (String item : linkedList) {
    process(item);
}
```

### 3.3 HashSet: Mutable Keys = Disaster

```java
// ❌ CRITICAL BUG: Mutable object as HashSet key
class User {
    String name;
    int hashCode = name.hashCode(); // cached
    
    // ... equals/hashCode based on name ...
}

Set<User> users = new HashSet<>();
User u = new User("Alice");
users.add(u);
u.name = "Bob"; // Mutate after insert!

// Now: u.hashCode() changed, but bucket index không đổi
users.contains(u); // FALSE! Object "lost" in set
```

### 3.4 TreeSet với Comparator không consistent with equals

```java
// ❌ VIOLATION: Comparator inconsistent with equals
TreeSet<String> set = new TreeSet<>(
    (a, b) -> a.length() - b.length() // only compare length!
);
set.add("abc");
set.add("def"); // "def" = "abc" theo comparator → KHÔNG được add!

// ✅ RULE: (compare(a,b) == 0) MUST imply a.equals(b)
// Hoặc dùng Comparator.comparing().thenComparing()
```

---

## 4. Java 21+ Modern Features

### 4.1 Sequenced Collections (Java 21)

```java
// Mới trong Java 21: SequencedCollection interface
SequencedSet<String> set = new LinkedHashSet<>();
set.addFirst("first");   // Java 21+
set.addLast("last");     // Java 21+
String first = set.getFirst();
String last = set.getLast();
SequencedSet<String> reversed = set.reversed();
```

### 4.2 Immutable Collections (Java 9+)

```java
// ✅ Prefer immutable cho thread-safety
List<String> immutable = List.of("a", "b", "c"); // Java 9+
Set<String> immutableSet = Set.of("a", "b", "c");

// ❌ CopyOf khi cần defensive copy
List<String> copy = List.copyOf(original); // Immutable copy
```

### 4.3 Stream API Best Practices

```java
// ❌ BAD: toList() trả mutable list (Java 16+)
// Có thể gây UnsupportedOperationException nếu modify
List<String> list = stream.map(...).toList();

// ✅ GOOD: Explicit collector
List<String> mutable = stream.collect(Collectors.toList());
List<String> immutable = stream.collect(Collectors.toUnmodifiableList());
```

---

## 5. Decision Flowchart

```
                    Bắt đầu chọn Collection
                           │
              ┌────────────┴────────────┐
              │                         │
        Cần List?                   Cần Set?
              │                         │
      ┌───────┴───────┐         ┌───────┴───────┐
      │               │         │               │
  Random access   Thêm/xóa   Cần sorted      Chỉ cần
  nhiều?          2 đầu?     order?          uniqueness?
      │               │         │               │
   [Yes]           [Yes]     [Yes]           [No]
      │               │         │               │
  ARRAYLIST     LINKEDLIST  TREESET        HASHSET
      │               │         │               │
   [No]            [No]      [No]           LinkedHashSet
      │               │         │          (giữ insertion order)
  Thêm/xóa      ARRAYLIST   HASHSET
  cuối?                       (rồi sort
  [Yes]                       khi cần)
      │
  ARRAYLIST
```

---

## 6. Kết Luận Senior

> **"Không có cấu trúc dữ liệu tốt nhất - chỉ có cấu trúc phù hợp nhất với use case"**

### Key Takeaways:

1. **ArrayList thắng 90% trường hợp** - cache locality quan trọng hơn theoretical complexity
2. **LinkedList chỉ dùng cho Queue/Deque** - và cân nhắc ArrayDeque nhẹ hơn
3. **HashSet là default cho Set** - nhanh, đơn giản, không cần Comparable
4. **TreeSet cho range operations** - floor/ceiling/subSet là unique selling points
5. **Luôn nghĩ đến memory** - overhead của wrapper objects trong collection lớn hơn bạn nghĩ
6. **Prefer immutable** - List.of(), Set.of(), copyOf() từ Java 9+

### Performance Rule of Thumb:
- N < 100: Không quan trọng, chọn dễ đọc
- N < 10,000: ArrayList/HashSet luôn thắng
- N > 100,000: Cần benchmark thực tế với JMH
- Concurrent access: CopyOnWriteArrayList/ConcurrentHashMap.newKeySet()
