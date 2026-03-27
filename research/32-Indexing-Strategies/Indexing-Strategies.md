# Indexing Strategies: Bản Chất và Thiết Kế Index Hiệu Quả

## 1. Mục Tiêu

Hiểu sâu bản chất các loại index trong database systems:
- **B-Tree Index**: Index đa năng, phổ biến nhất
- **Hash Index**: Index cho equality lookups
- **GiST Index**: Generalized Search Tree cho dữ liệu phức tạp
- **GIN Index**: Generalized Inverted Index cho multi-value data

Ngoài ra, nắm vững:
- **Composite Index Design**: Thứ tự cột, selectivity, cardinality
- **Covering Index**: Index chứa đủ dữ liệu để tránh table access
- **Index-Only Scan**: Khi nào optimizer chọn đọc index mà không cần table

Mục tiêu cuối cùng: **thiết kế index đúng**, **hiểu tại sao index lại chậm**, và **tối ưu queries** ở quy mô production.

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 B-Tree Index: Kiến Trúc và Cơ Chế

#### Bản Chất Cấu Trúc

B-Tree index là **sorted, balanced tree structure** lưu trữ ánh xạ từ **index key → row pointer** (ROWID, TID, hoặc physical address).

```
Internal Nodes ( chỉ chứa key + pointer )
─────────────────────────────────────
[10 | 25 | 40 | 55]        ← Root node
 |    |    |    |
 v    v    v    v
[Leaf Nodes - chứa key + row pointer]
─────────────────────────────────────
[5,ptr]→[8,ptr]→[10,ptr]→[15,ptr]→[20,ptr]→[22,ptr]→[25,ptr]→...
   |___________|    |______________|     |______________|
        |                  |                    |
     Page 1             Page 2               Page 3
```

**Đặc điểm quan trọng:**

| Đặc điểm | Ý nghĩa |
|----------|---------|
| **Sorted** | Keys được sắp xếp → range queries hiệu quả |
| **Balanced** | Mọi leaf cách root cùng độ sâu → predictable performance |
| **Multi-way** | Mỗi node chứa nhiều keys → giảm tree height |
| **Linked leaves** | Leaf nodes liên kết → sequential scan range queries |

#### Cơ Chế Point Query

```
Tìm kiếm key = 25:

1. Đọc root node từ disk
   [10 | 25 | 40 | 55]
         ↑
   25 >= 25, 25 < 40 → đi theo pointer thứ 2

2. Đọc internal/leaf node tiếp theo
   [22 | 25 | 28 | 31]
         ↑
   25 == 25 → found!

3. Đọc row từ table qua row pointer

Tổng: ~3-4 disk reads (với tree height = 3)
```

**Time Complexity: O(log_b N)** với b = branching factor (thường ~100-500)

Với 1 triệu records và b=500: height ≈ log₅₀₀(1,000,000) ≈ 3-4 levels

#### Cơ Chế Range Query

```
Tìm kiếm 20 <= key < 35:

1. Point query tìm 20 (hoặc giá trị >= 20 đầu tiên)
2. Follow linked list của leaf nodes
3. Đọc liên tiếp [20,22,25,28,31] → sequential I/O hiệu quả

Leaf scan: O(K) với K = số keys trong range
```

> **Quan trọng:** Range scan hiệu quả nhờ **leaf node linkage** và **sequential disk reads**.

#### Cơ Chế Insert/Delete

**Insert:**
```
1. Tìm leaf node phù hợp (O(log N))
2. Nếu còn chỗ: insert vào vị trí sorted (shift keys trong page)
3. Nếu đầy: SPLIT NODE
   - Tách thành 2 nodes
   - Promote middle key lên parent
   - Có thể cascade split lên root
```

**Node Split là expensive operation:**
- Allocate new page
- Copy half keys sang page mới
- Update parent (có thể trigger thêm splits)
- **Write amplification**: 1 insert → nhiều page writes

**Delete:**
```
1. Tìm và xóa key trong leaf (mark deleted hoặc shift)
2. Nếu node < 50% full: có thể MERGE với neighbor
   - Hoặc REBALANCE (redistribute keys)
```

#### B+Tree vs B-Tree trong Database Context

| Feature | B-Tree | B+Tree (phổ biến hơn) |
|---------|--------|----------------------|
| Data storage | Mọi node | Chỉ leaf nodes |
| Internal node size | Lớn hơn (có data) | Nhỏ hơn (chỉ keys) |
| Range scan | Less efficient | Highly efficient (linked leaves) |
| Cache efficiency | Lower | Higher (more keys per node) |
| Sequential access | Requires tree traversal | Direct leaf traversal |

**Tất cả major databases (PostgreSQL, MySQL/InnoDB, Oracle, SQL Server) dùng B+Tree.**

---

### 2.2 Hash Index: Cơ Chế và Giới Hạn

#### Bản Chất

Hash index sử dụng **hash table** thay vì tree:

```
Key → Hash Function → Bucket Index → (Key, RowPointer) list

Ví dụ: hash(key) % 1024 = bucket_number
```

Cấu trúc:
```
Buckets (array of pointers)
───────────────────────────
[0] → nullptr
[1] → [(key=A,ptr=0x1000)] → [(key=B,ptr=0x2000)]  ← Collision chain
[2] → nullptr
[3] → [(key=C,ptr=0x3000)]
...
[1023] → nullptr
```

#### Cơ Chế Operations

**Lookup:**
```
1. Tính hash(key) → bucket index
2. Đọc bucket từ disk (hoặc cache)
3. Linear search trong collision chain
4. So sánh key đầy đủ (do hash collision)
5. Trả về row pointer

Time: O(1) average, O(n) worst case (n = keys trong bucket)
```

**Insert:**
```
1. Tính hash(key) → bucket
2. Thêm (key, ptr) vào đầu/cuối chain
3. Nếu bucket quá đầy: REBUILD/REHASH toàn bộ index
```

#### Trade-off: Hash vs B-Tree

| Aspect | Hash Index | B-Tree Index |
|--------|------------|--------------|
| **Point query** | O(1) - nhanh hơn | O(log N) |
| **Range query** | ❌ Không hỗ trợ | ✅ O(log N + K) |
| **ORDER BY** | ❌ Không hỗ trợ | ✅ Natural order |
| **MIN/MAX** | ❌ Full scan | ✅ O(log N) |
| **Partial key match** | ❌ Không hỗ trợ | ✅ Prefix match |
| **Disk space** | Thường nhỏ hơn | Lớn hơn |
| **Concurrency** | Bucket-level locking | Page-level locking |
| **Collision handling** | Cần chain/rehash | Không có collision |

> **Quy tắc vàng:** Chỉ dùng Hash Index khi **100% queries là equality lookups** (WHERE key = value) và **KHÔNG BAO GIỜ** cần range, sort, hoặc partial match.

#### PostgreSQL Hash Index Implementation

PostgreSQL sử dụng **extendable hashing** (dynamic hashing):

```
- Không cần rehash toàn bộ khi grow
- Split buckets incrementally
- Sử dụng high bits của hash để xác định bucket
- Overflow pages cho buckets đầy
```

**Lưu ý quan trọng:**
- Trước PostgreSQL 10: Hash indexes không WAL-logged (mất khi crash)
- PostgreSQL 10+: Hash indexes đã durable nhưng vẫn ít dùng hơn B-Tree

---

### 2.3 GiST Index: Generalized Search Tree

#### Bản Chất

GiST (Generalized Search Tree) là **framework** cho phép xây dựng index cho **bất kỳ data type nào** thỏa mãn một số properties.

**Ý tưởng cốt lõi:**
- B-Tree chỉ hỗ trợ total order (so sánh <, =, >)
- GiST hỗ trợ **arbitrary partitioning strategies**
- Mỗi internal node chứa **bounding predicate** (không phải exact key)

```
Ví dụ: R-Tree (spatial index) dùng GiST

Internal node chứa bounding boxes:
┌─────────────────────────────────────┐
│ Node: [(0,0)-(100,100)]             │ ← Bounding box chứa tất cả children
│   ├── Child 1: [(0,0)-(30,50)]      │
│   ├── Child 2: [(25,25)-(75,75)]    │
│   └── Child 3: [(60,40)-(100,100)]  │
└─────────────────────────────────────┘

Leaf node chứa actual objects:
[(10,20), (45,55), (80,90), ...]
```

#### Các Loại GiST Operator Classes

| Operator Class | Use Case | Operators |
|----------------|----------|-----------|
| **btree_gist** | B-tree-like operations trên GiST | <, <=, =, >=, > |
| **intarray** | Integer arrays ( containment, overlap) | &&, @>, <@, = |
| **ltree** | Label trees (hierarchical data) | @>, <@, ~, @ |
| **cube** | Multi-dimensional cubes | @>, <@, &&, ~ |
| **seg** | Line segments (time ranges, intervals) | @>, <@, &&, -|- |
| **tsvector** | Full-text search | @@@ (deprecated by GIN) |

#### Cơ Chế Search trong GiST

```
Tìm kiếm: "Tìm tất cả objects chứa trong region R"

1. Bắt đầu từ root
2. Với mỗi internal node:
   - Kiểm tra bounding predicate có overlap với query không
   - Nếu KHÔNG overlap → prune (cắt nhánh này)
   - Nếu overlap → đệ quy xuống children
3. Ở leaf nodes:
   - Kiểm tra exact condition
   - Trả về matching rows

Key insight: GiST dùng **lossy bounding predicates** để **prune search space**
```

#### GiST vs B-Tree cho Non-Scalar Data

| Data Type | B-Tree | GiST |
|-----------|--------|------|
| Numbers, Strings | ✅ Perfect | ✅ Hoạt động nhưng chậm hơn |
| Geometric (point, box) | ❌ Không hỗ trợ | ✅ Native support |
| Arrays | ❌ Không hỗ trợ | ✅ Containment, overlap |
| Ranges (int4range, tsrange) | ❌ Không hỗ trợ | ✅ Range operations |
| Full-text | ❌ Không hỗ trợ | ⚠️ Có thể nhưng GIN tốt hơn |

---

### 2.4 GIN Index: Generalized Inverted Index

#### Bản Chất

GIN (Generalized Inverted Index) thiết kế cho **multi-value data** (arrays, full-text, JSONB).

**Ý tưởng từ Information Retrieval:**
```
Documents chứa terms → Inverted index: term → list of documents

Ví dụ:
Doc 1: "the quick brown fox"
Doc 2: "the lazy dog"
Doc 3: "quick brown dog"

Inverted Index:
"brown" → [Doc 1, Doc 3]
"dog" → [Doc 2, Doc 3]
"fox" → [Doc 1]
"lazy" → [Doc 2]
"quick" → [Doc 1, Doc 3]
"the" → [Doc 1, Doc 2]
```

**Áp dụng cho Database:**
```
Table: articles
┌────┬──────────────────────────────────┐
│ id │ tags (text[])                    │
├────┼──────────────────────────────────┤
│ 1  │ {java, spring, backend}          │
│ 2  │ {python, django, backend}        │
│ 3  │ {java, microservices, cloud}     │
└────┴──────────────────────────────────┘

GIN Index trên tags:
"backend" → [1, 2]
"cloud" → [3]
"django" → [2]
"java" → [1, 3]
"microservices" → [3]
"python" → [2]
"spring" → [1]
```

#### Cấu Trúc GIN Index

```
GIN Index = B-Tree của (element → posting list)

                      ┌─────────────────┐
                      │ B-Tree Root     │
                      │ (sorted keys)   │
                      └────────┬────────┘
                               │
          ┌────────────────────┼────────────────────┐
          │                    │                    │
          v                    v                    v
   ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
   │ "backend"   │     │ "cloud"     │     │ "java"      │
   │ [1,2,4,7]   │     │ [3,5,8]     │     │ [1,3,6,9]   │
   └─────────────┘     └─────────────┘     └─────────────┘
        Leaf nodes (posting lists)
```

**Posting list** = sorted array of row IDs chứa element đó

#### Cơ Chế Query

```
Query: SELECT * FROM articles WHERE tags @> '{java, backend}'

1. Parse query: cần tìm docs chứa cả "java" VÀ "backend"
2. Lookup "java" → posting list [1, 3, 6, 9]
3. Lookup "backend" → posting list [1, 2, 4, 7]
4. Intersect: [1, 3, 6, 9] ∩ [1, 2, 4, 7] = [1]
5. Fetch row 1 từ table

Time: O(log M + n) với M = số unique elements, n = posting list size
```

#### Fast Update vs Pending List

**Vấn đề:** GIN index updates chậm vì phải insert vào posting lists

**Giải pháp - Fast Update (pending list):**
```
Insert/Update:
  ┌────────────────────────────────────────┐
  │ 1. Insert vào pending list (unordered) │ ← Rất nhanh
  │ 2. Flush pending list định kỳ:         │
  │    - Sort pending entries              │
  │    - Merge vào main B-Tree             │ ← Chậm nhưng async
  └────────────────────────────────────────┘

Trade-off:
- Fast update: giảm insert latency
- Query cost: phải scan cả pending list + main tree
```

**Configuration:**
```sql
-- PostgreSQL: gin_fuzzy_search_limit, gin_pending_list_limit
-- Fast update enabled by default
```

#### GIN Use Cases

| Use Case | Example Operators | Data Type |
|----------|-------------------|-----------|
| **Full-text search** | @@, @@@ | tsvector |
| **Array containment** | &&, @>, <@ | anyarray |
| **JSONB containment** | @>, @?, @@ | jsonb |
| **Range overlap** | && | anyrange |
| **Trigram similarity** | %, <-> | text (pg_trgm) |

---

## 3. Composite Index Design

### 3.1 Bản Chất Composite Index

Composite index = index trên **nhiều cột** (column1, column2, ..., columnN)

**Cấu trúc:**
```
Index trên (last_name, first_name, birth_date)

Leaf node structure:
[("Anderson", "Alice", 1985-03-15) → ptr1]
[("Anderson", "Bob", 1990-07-22) → ptr2]
[("Brown", "Carol", 1988-11-08) → ptr3]
[("Brown", "David", 1992-04-30) → ptr4]
[("Chen", "Eve", 1987-09-12) → ptr5]
...

Sorted theo thứ tự: last_name → first_name → birth_date
```

**Quan trọng:** Composite index là **một tree duy nhất** với keys được **concatenate và sorted theo column order**.

### 3.2 Leftmost Prefix Rule

**Quy tắc vàng:** Composite index (A, B, C) có thể được sử dụng cho:

```
✅ WHERE A = ?
✅ WHERE A = ? AND B = ?
✅ WHERE A = ? AND B = ? AND C = ?
✅ WHERE A = ? AND C = ? (chỉ dùng phần A)
⚠️ WHERE B = ? (KHÔNG dùng được)
⚠️ WHERE B = ? AND C = ? (KHÔNG dùng được)
✅ WHERE A = ? ORDER BY B (có thể dùng index để sort)
```

**Visualization:**
```
Index (last_name, first_name):

                    [Adams | Brown | Chen]
                       |       |       |
           ┌───────────┘       |       └───────────┐
           v                   v                   v
    [Adams,Ann]            [Brown,Bob]        [Chen,Alice]
    [Adams,Bob]            [Brown,Carol]      [Chen,Bob]
    [Adams,Carol]          [Brown,David]      [Chen,Carol]

WHERE last_name = 'Brown':
→ Tìm 'Brown' trong level 1 → follow pointer → scan Brown subtree

WHERE first_name = 'Bob':
→ Không thể tìm vì tree được sort theo last_name trước
→ Phải full index scan
```

### 3.3 Column Order Strategy

#### Yếu tố 1: Selectivity (Cardinality)

**Quy tắc:** Cột có **selectivity cao** (nhiều distinct values) nên đặt **trước**.

```
Giả sử table users với:
- gender: 2 distinct values (M/F) → cardinality thấp
- email: ~10M distinct values → cardinality cao
- country: ~200 distinct values → cardinality trung bình

Index (gender, country, email) vs (email, country, gender):

(gender, country, email):
- gender='M' → filter ~50% rows → còn ~5M
- country='US' → filter ~10% trong số đó → còn ~500K
- email='...' → tìm trong 500K

(email, country, gender):
- email='...' → filter ngay xuống ~1-2 rows
- country và gender chỉ để verify

→ (email, country, gender) hiệu quả hơn nhiều
```

#### Yếu tố 2: Query Pattern

```
Queries phổ biến:
1. WHERE country = ? AND created_at > ? (90% queries)
2. WHERE user_id = ? (10% queries)

Index tối ưu: (country, created_at)
- Hỗ trợ query 1 hoàn hảo
- Không hỗ trợ query 2 (không có user_id)

Nếu query 2 quan trọng → cần separate index (user_id)
```

#### Yếu tố 3: Range Queries

**Quy tắc:** Đặt **equality columns trước**, **range columns sau**.

```
WHERE status = 'active' AND created_at > '2024-01-01'

Index (status, created_at):
1. Tìm status='active' → range trong index
2. Trong đó, scan created_at > '2024-01-01' → sequential
→ Hiệu quả

Index (created_at, status):
1. Tìm created_at > '2024-01-01' → phải scan nhiều
2. Trong đó filter status='active'
→ Không tận dụng được index structure
```

#### Yếu tố 4: Sorting

```
Query: WHERE category = ? ORDER BY created_at DESC LIMIT 10

Index (category, created_at DESC):
- Tìm category trong index
- Trong category đó, data đã sorted theo created_at
- Lấy 10 rows đầu tiên là xong
→ No additional sort needed

Index (created_at, category):
- Không hỗ trợ WHERE category (không phải leftmost)
- Hoặc phải scan toàn bộ index
```

### 3.4 Composite Index Design Checklist

| Criterion | Question |
|-----------|----------|
| **Equality first** | Cột nào thường dùng với `=` nhất? |
| **High cardinality** | Cột nào có nhiều distinct values nhất? |
| **Range second** | Cột nào dùng với `>`, `<`, `BETWEEN`? |
| **Sorting** | Có cần ORDER BY không? Thứ tự ASC/DESC? |
| **Coverage** | Index có thể covering được không? |
| **Write cost** | Tần suất insert/update/delete? |

---

## 4. Covering Index và Index-Only Scan

### 4.1 Bản Chất Covering Index

**Covering index** = index chứa **tất cả columns cần thiết** cho query (cả trong WHERE, SELECT, JOIN, ORDER BY).

```
Query: SELECT user_id, username FROM users WHERE email = ?

Normal Index (email):
1. Tìm email trong index → get row pointer
2. Đọc row từ table để lấy user_id, username
→ 2 disk operations (index + table)

Covering Index (email, user_id, username):
1. Tìm email trong index
2. user_id và username đã có trong index
→ 1 disk operation (index only)
→ **Index-Only Scan**
```

### 4.2 Include Columns (Non-Key Columns)

**Vấn đề:** Thêm quá nhiều columns vào composite index key làm tăng index size và giảm fanout.

**Giải pháp - INCLUDE columns:**
```sql
-- PostgreSQL 11+, SQL Server
CREATE INDEX idx_users_email ON users (email) 
INCLUDE (user_id, username, created_at);

Structure:
- Index key: email (dùng để tìm kiếm và sort)
- Included columns: user_id, username, created_at (chỉ để đọc, không dùng để tìm)

Benefits:
- Index tree chỉ sort theo email → nhỏ gọn
- Vẫn covering cho query
```

### 4.3 Index-Only Scan Optimization

**Visibility Map (PostgreSQL):**
```
PostgreSQL cần verify row visibility cho Index-Only Scan:

1. Nếu page đã được vacuumed (visible in VM):
   → Index-Only Scan thực sự
   
2. Nếu page chưa vacuumed:
   → Phải đọc heap để check visibility
   → "Index-Only Scan" với heap fetches

Tối ưu: Đảm bảo autovacuum chạy đủ thường xuyên
```

**Bitmap Index Scan:**
```
Khi query trả về nhiều rows (ví dụ: 50% table):

1. Index scan tạo bitmap của matching rows
2. Bitmap được sort theo physical order
3. Sequential read từ table theo bitmap
→ Giảm random I/O so với row-by-row index scan
```

### 4.4 Trade-off của Covering Index

| Benefit | Cost |
|---------|------|
| **Read performance** | ✅ Eliminate table access |
| **Less I/O** | ✅ Index thường nhỏ hơn table |
| **Cache efficiency** | ✅ Index có thể fit trong memory |
| **Index size** | ❌ Lớn hơn nhiều so với minimal index |
| **Write amplification** | ❌ Mỗi INSERT/UPDATE phải update nhiều columns |
| **Maintenance** | ❌ Harder to maintain, more versions |

> **Quy tắc:** Covering index cho **read-heavy, rarely updated** tables. Tránh cho **write-heavy** tables.

---

## 5. So Sánh và Lựa Chọn Index

### 5.1 Decision Matrix

| Scenario | Recommended Index | Lý do |
|----------|------------------|-------|
| Primary key, unique constraints | B-Tree | Sorted, supports range |
| Equality lookup, no range/sort | B-Tree hoặc Hash | Hash nhanh hơn nhưng ít flexible |
| Range queries (<, >, BETWEEN) | B-Tree | Sorted structure |
| Text search (LIKE '%word%') | GIN + trigram | Inverted index cho substrings |
| Full-text search | GIN | Token → documents mapping |
| Array operations | GIN | Element → rows mapping |
| JSONB containment | GIN | Key-path → rows mapping |
| Geospatial data | GiST (R-Tree) | Bounding box hierarchy |
| Range types (tsrange, int4range) | GiST hoặc GIN | Overlap operations |
| High-cardinality + Low-cardinality composite | B-Tree (high first) | Prune sớm |
| Covering read-only queries | Covering B-Tree | Eliminate table access |

### 5.2 Anti-Patterns và Pitfalls

#### ❌ Index Everything
```
Vấn đề:
- Mỗi index làm chậm INSERT/UPDATE/DELETE
- Index chiếm disk space
- Query planner phải chọn giữa nhiều options → overhead
- Cache pollution

Giải pháp:
- Index chỉ cho queries frequent và slow
- Measure trước khi index
- Xóa unused indexes định kỳ
```

#### ❌ Low Cardinality Columns
```
Index trên gender (2 values), status với 3-4 values:
→ Index scan trả về 30-50% rows
→ Bitmap scan hoặc sequential scan nhanh hơn
→ Query planner sẽ ignore index anyway

Ngoại lệ: Composite index với high-cardinality column khác
```

#### ❌ Leading Wildcard LIKE
```sql
-- Index không hỗ trợ
WHERE name LIKE '%smith%'  -- Không dùng được index

-- Có thể dùng index
WHERE name LIKE 'smith%'   -- B-Tree prefix scan

-- Giải pháp cho substring search
CREATE EXTENSION pg_trgm;
CREATE INDEX ON users USING GIN (name gin_trgm_ops);
```

#### ❌ Functions on Indexed Columns
```sql
-- Index không hỗ trợ (function applied trước lookup)
WHERE UPPER(email) = 'USER@EXAMPLE.COM'

-- Giải pháp 1: Functional index
CREATE INDEX ON users (UPPER(email));

-- Giải pháp 2: Store normalized data
ALTER TABLE users ADD COLUMN email_normalized TEXT;
UPDATE users SET email_normalized = LOWER(email);
CREATE INDEX ON users (email_normalized);
```

#### ❌ Implicit Conversions
```sql
-- phone_number là VARCHAR, so sánh với number
WHERE phone_number = 1234567890
→ Database convert cả cột sang number → index không dùng được

-- Đúng
WHERE phone_number = '1234567890'
```

#### ❌ OR Conditions
```sql
-- Index chỉ dùng cho 1 phần, còn lại sequential scan
WHERE user_id = 1 OR email = 'test@example.com'

-- Giải pháp 1: UNION
SELECT * FROM users WHERE user_id = 1
UNION ALL
SELECT * FROM users WHERE email = 'test@example.com';

-- Giải pháp 2: Separate indexes + BitmapOr
```

---

## 6. Production Concerns

### 6.1 Index Monitoring

**PostgreSQL:**
```sql
-- Index usage stats
SELECT 
    schemaname,
    tablename,
    indexname,
    idx_scan,           -- Số lần index được sử dụng
    idx_tup_read,
    idx_tup_fetch
FROM pg_stat_user_indexes
ORDER BY idx_scan DESC;

-- Unused indexes (candidates for removal)
SELECT 
    schemaname || '.' || tablename AS table,
    indexname,
    idx_scan
FROM pg_stat_user_indexes
WHERE idx_scan = 0 
  AND indexrelname NOT LIKE 'pg_toast%'
ORDER BY pg_relation_size(indexrelid) DESC;

-- Index bloat (fragmentation)
SELECT 
    schemaname || '.' || tablename AS table,
    indexname,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size,
    idx_scan
FROM pg_stat_user_indexes
WHERE schemaname = 'public';
```

**MySQL:**
```sql
-- Performance Schema
SELECT 
    OBJECT_SCHEMA,
    OBJECT_NAME,
    INDEX_NAME,
    COUNT_FETCH,
    COUNT_INSERT,
    COUNT_UPDATE,
    COUNT_DELETE
FROM performance_schema.table_io_waits_summary_by_index_usage
WHERE OBJECT_SCHEMA = 'mydb'
ORDER BY COUNT_FETCH DESC;
```

### 6.2 Index Maintenance

**Bloat (Fragmentation):**
```
Nguyên nhân:
- Updates tạo "dead" versions trong index
- Deletes để lại "holes"
- Page splits tạo fragmentation

PostgreSQL:
- REINDEX: rebuild index từ đầu (locks table)
- REINDEX CONCURRENTLY: rebuild không lock (PostgreSQL 12+)

MySQL:
- OPTIMIZE TABLE: rebuild indexes
- ALTER TABLE ... ENGINE=InnoDB: rebuild
```

**Vacuum/Analyze:**
```sql
-- PostgreSQL: Update statistics cho query planner
ANALYZE users;

-- Vacuum để reclaim space và update visibility map
VACUUM ANALYZE users;

-- Full vacuum (locks table, reclaims more space)
VACUUM FULL users;
```

### 6.3 Index Creation Strategies

**Concurrent Index Creation (PostgreSQL):**
```sql
-- Không lock table cho writes
CREATE INDEX CONCURRENTLY idx_users_email ON users(email);

Trade-offs:
- Chậm hơn 2-3x
- Tốn nhiều I/O hơn
- Không thể trong transaction
- Nếu fail, phải DROP và retry
```

**Online DDL (MySQL):**
```sql
-- MySQL 8.0+ supports online index operations
ALTER TABLE users ADD INDEX idx_email (email), ALGORITHM=INPLACE, LOCK=NONE;
```

### 6.4 Partial Indexes

**Khi nào dùng:**
```sql
-- Chỉ index rows phổ biến trong queries
-- VD: Index active users, bỏ qua deleted/archived

CREATE INDEX idx_orders_active ON orders (created_at)
WHERE status IN ('pending', 'processing', 'shipped');

Benefits:
- Index nhỏ hơn nhiều
- Cache efficiency cao hơn
- Queries với WHERE status='active' sử dụng
```

### 6.5 Expression Indexes

```sql
-- Case-insensitive search
CREATE INDEX ON users (LOWER(email));

-- JSONB path
CREATE INDEX ON orders USING GIN ((data -> 'items'));

-- Computed columns
CREATE INDEX ON events (date_trunc('day', created_at));
```

---

## 7. Kết Luận

### Bản Chất Cốt Lõi

| Index Type | Core Principle | Best For | Avoid When |
|------------|---------------|----------|------------|
| **B-Tree** | Sorted tree, range pruning | 95% use cases | - |
| **Hash** | Direct bucket lookup | Exact equality only | Range, sort, partial |
| **GiST** | Bounding predicate tree | Complex data types, spatial | Simple scalar data |
| **GIN** | Inverted index | Multi-value, containment | Single-value lookups |

### Nguyên Tắc Thiết Kế

1. **Leftmost prefix rule:** Column order trong composite index là **quan trọng nhất**
2. **Equality before range:** Đặt `=` columns trước `>`, `<` columns
3. **High cardinality first:** Cột nhiều distinct values đặt trước
4. **Cover for hot queries:** Include columns để enable index-only scan cho frequent queries
5. **Measure before indexing:** Index không miễn phí - monitor và remove unused

### Trade-off Quan Trọng Nhất

> **Read vs Write:** Mỗi index là một **write amplification** - INSERT/UPDATE/DELETE phải maintain index. Chỉ index khi read benefit > write cost.

### Rủi Ro Production Lớn Nhất

1. **Index bloat** - Fragmentation làm giảm performance theo thời gian
2. **Wrong column order** - Composite index không dùng được do vi phạm leftmost prefix
3. **Implicit conversions** - Functions/casts làm index "invisible"
4. **Over-indexing** - Too many indexes kill write performance
5. **Missing vacuum/analyze** - Stale statistics lead to wrong query plans

---

## 8. Tham Khảo

- PostgreSQL Documentation: Chapter 11 - Indexes
- "Database Internals" by Alex Petrov (O'Reilly)
- "High Performance MySQL" by Baron Schwartz et al.
- PostgreSQL Wiki: Index Types
- Use The Index, Luke (use-the-index-luke.com)
