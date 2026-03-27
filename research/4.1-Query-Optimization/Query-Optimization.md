# Query Optimization - Tối ưu hóa Truy vấn Database

## 1. Mục tiêu của Task

Hiểu sâu cơ chế database engine phân tích, lập kế hoạch và thực thi truy vấn SQL. Nắm vững:
- **Execution Plan**: Cách database "nghĩ" và thực thi query
- **Cost-Based Optimizer (CBO)**: Thuật toán chọn execution plan tối ưu
- **Statistics & Histograms**: Dữ liệu metadata quyết định chất lượng optimization
- **Query Anti-patterns**: Các lỗi thiết kế truy vấn gây hiệu năng kém trong production

> **Tại sao quan trọng:** 90% vấn đề hiệu năng database không phải do thiếu hardware, mà do query kém và optimizer chọn sai plan. Senior engineer phải "đọc được" execution plan như đọc stack trace.

---

## 2. Bản chất và Cơ chế Hoạt động

### 2.1 Query Processing Pipeline

```
SQL Query → Parser → Analyzer → Rewriter → Optimizer → Executor → Storage
                ↓          ↓          ↓           ↓          ↓
            Syntax    Semantics   Rules    Cost-based   Physical
             Tree      Checks    Rewrite    Search      Plan
```

**Giai đoạn quan trọng:**

| Giai đoạn | Vai trò | Ảnh hưởng Performance |
|-----------|---------|----------------------|
| **Parser** | Chuyển SQL thành AST | Minimal - lỗi syntax dừng sớm |
| **Analyzer** | Kiểm tra table/column tồn tại, phân giải alias | Minimal |
| **Rewriter** | Áp dụng view expansion, rule-based transformation | Trung bình |
| **Optimizer** | Chọn access path, join order, join algorithm | **Cực kỳ quan trọng** |
| **Executor** | Thực thi physical plan | Phụ thuộc vào plan |

### 2.2 Execution Plan - Physical vs Logical

**Logical Plan:** Biểu diễn "WHAT" - câu truy vấn cần làm gì
- Độc lập implementation
- Dạng cây các relational operators (σ, π, ⋈)

**Physical Plan:** Biểu diễn "HOW" - thực thi như thế nào
- Chọn index nào, join algorithm nào, scan method nào
- Có cost estimate kèm theo

```
Logical:    σ(salary>5000)(Employees)
Physical:   Index Range Scan on idx_salary (cost=0.42..123.45 rows=1000)
```

---

## 3. Cost-Based Optimizer (CBO) Deep Dive

### 3.1 Bản chất của "Cost"

CBO đánh giá mỗi plan dựa trên **cost model** - không phải thởi gian thực tế, mà là **ước tính tài nguyên tiêu thụ**:

```
Total Cost = I/O Cost + CPU Cost + Memory Cost + Network Cost
```

**Unit of Measurement:**
- PostgreSQL: arbitrary units (seq_page_cost, cpu_tuple_cost)
- MySQL: không hiển thị rõ ràng, nhưng vẫn tính toán tương tự
- Oracle: một đơn vị = thởi gian thực thi ước tính

### 3.2 Cardinality Estimation - Gốc rễ của mọi vấn đề

```
Estimated Rows = Table Rows × Selectivity
```

**Ví dụ:**
```sql
SELECT * FROM orders WHERE status = 'completed' AND created_at > '2024-01-01';
```

Nếu statistics cho biết:
- `status = 'completed'`: 70% rows
- `created_at > '2024-01-01'`: 20% rows
- Assuming independence: Selectivity = 0.7 × 0.2 = 0.14

> **Pitfall quan trọng:** CBO assumes column independence. Trong thực tế, `status='completed'` và `created_at` có correlation cao (đơn hàng hoàn thành thường là cũ) → estimate sai lầm nghiêm trọng.

### 3.3 Search Space & Join Ordering

Với n tables, số cách join theo associative property:

```
(n-1)! × C(n-1)  -- C(n-1) là Catalan number cho cây join

n=3:  6 possible plans
n=4:  120 possible plans  
n=5:  1680 possible plans
n=10: ~17 billion plans
```

**Chiến lược tìm kiếm:**

| Algorithm | Độ phức tạp | Khi nào dùng | Trade-off |
|-----------|------------|--------------|-----------|
| **Exhaustive** | O(n!) | n ≤ 7 | Tối ưu nhất, chậm |
| **Greedy (Greedy Join Ordering)** | O(n²) | Default | Nhanh, có thể tối ưu cục bộ |
| **Genetic Algorithm** | Configurable | PostgreSQL geqo | Large joins (>12 tables) |
| **DPsize / DPsub** | O(2ⁿ) | Medium queries | Cân bằng tốt |

### 3.4 Access Path Selection

**Sequential Scan vs Index Scan:**

```
Cost(Seq Scan) = pages × seq_page_cost + tuples × cpu_tuple_cost

Cost(Index Scan) = tree_height × random_page_cost + 
                   matching_tuples × (random_page_cost + cpu_tuple_cost)
```

**Break-even point:** PostgreSQL thường chọn index khi selectivity < ~5-10%, nhưng phụ thuộc vào:
- `random_page_cost` (default 4.0) - điều chỉnh cho SSD vs HDD
- Index correlation với physical order
- HOT (Heap Only Tuple) updates ratio

---

## 4. Statistics & Histograms

### 4.1 Table-Level Statistics

```sql
-- PostgreSQL pg_class
SELECT relname, reltuples, relpages, relallvisible 
FROM pg_class WHERE relname = 'orders';

-- Result: ước tính số rows và pages của table
```

**Cập nhật statistics:**
```sql
ANALYZE orders;  -- PostgreSQL/MySQL
EXEC sp_updatestats;  -- SQL Server
```

> **Production concern:** `ANALYZE` locks không cao nhưng tiêu thụ I/O. Chạy auto-vacuum/analyze với tần suất phù hợp với data churn rate.

### 4.2 Column-Level Statistics

**MOST COMMON VALUES (MCV):**
- Lưu giá trị xuất hiện nhiều nhất và tần suất
- Tối đa 100-1000 values (phụ thuộc `default_statistics_target`)

```sql
SELECT * FROM pg_stats WHERE tablename = 'orders' AND attname = 'status';
-- most_common_vals: {completed,pending,cancelled}
-- most_common_freqs: {0.65,0.25,0.08}
```

**HISTOGRAM:**
- Dùng cho data phân bố không đều, không nằm trong MCV
- PostgreSQL: equi-depth histogram (mỗi bucket có ~số lượng equal)
- MySQL 8.0: singleton histogram cho discrete values

```sql
-- Xem histogram bounds trong PostgreSQL
SELECT histogram_bounds FROM pg_stats 
WHERE tablename = 'orders' AND attname = 'amount';
-- {1.00,5.50,12.30,50.00,150.00,500.00,1200.00}
```

### 4.3 Histogram Types So sánh

| Loại | Đặc điểm | Khi nào tốt | Rủi ro |
|------|---------|-------------|--------|
| **Equi-width** | Mỗi bucket cùng range | Uniform distribution | Skewed data → estimate sai |
| **Equi-depth** | Mỗi bucket cùng số rows (PostgreSQL) | Skewed data | Range queries biên giới |
| **Singleton** | Mỗi value một bucket (MySQL 8) | Low cardinality | High cardinality → memory |
| **Top-N** | Giữ N values phổ biến nhất | Power law distribution | Long tail estimate sai |

### 4.4 Extended Statistics (PostgreSQL 10+)

**Functional Dependencies:**
```sql
-- Nếu biết zip_code, có thể suy ra city → không tính independence
CREATE STATISTICS stats_orders_dep (dependencies) ON zip_code, city FROM orders;
```

**Multivariate Histograms (ndistinct, mcv):**
```sql
CREATE STATISTICS stats_orders_mcv (mcv) ON status, priority FROM orders;
-- Lưu correlation giữa status và priority
```

> **Use case thực tế:** Query có `WHERE status='urgent' AND priority=1` thường có correlation (urgent thường là priority cao). Extended stats giúp estimate chính xác hơn.

---

## 5. Execution Plan Reading

### 5.1 PostgreSQL EXPLAIN ANALYZE

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT u.name, COUNT(o.id)
FROM users u
JOIN orders o ON u.id = o.user_id
WHERE u.created_at > '2024-01-01'
  AND o.status = 'completed'
GROUP BY u.name
HAVING COUNT(o.id) > 5;
```

**Output interpretation:**

```
GroupAggregate  (cost=1234.56..2345.67 rows=100 width=36) (actual time=45.2..89.3 rows=42 loops=1)
  Group Key: u.name
  Filter: (count(o.id) > 5)
  Buffers: shared hit=5234 read=1234
  ->  Hash Join  (cost=500.00..1000.00 rows=10000 width=12)
        Hash Cond: (o.user_id = u.id)
        ->  Seq Scan on orders o  (cost=0.00..300.00 rows=5000 width=8)
              Filter: (status = 'completed')
              Rows Removed by Filter: 45000
        ->  Hash  (cost=400.00..400.00 rows=8000 width=8)
              ->  Index Scan using idx_users_created on users u
                    Index Cond: (created_at > '2024-01-01')
```

**Các chỉ số quan trọng:**

| Chỉ số | Ý nghĩa | Ngưỡng cảnh báo |
|--------|---------|-----------------|
| **cost** | Ước tính cost (start..total) | So sánh giữa các plans |
| **actual time** | Thởi gian thực tế (ms) | Chênh lệch lớn vs cost → stats cũ |
| **rows** | Số rows ước tính | Estimate/Actual > 10x là vấn đề |
| **loops** | Số lần lặp (nested loop) | >1 với hash/merge = warning |
| **Buffers** | I/O operations | High read = cache miss |

### 5.2 MySQL EXPLAIN FORMAT=TREE

```sql
EXPLAIN ANALYZE FORMAT=TREE
SELECT * FROM orders 
WHERE user_id = 12345 ORDER BY created_at DESC LIMIT 10;
```

**Key indicators:**
- `type`: ALL (full table scan) vs range vs ref vs eq_ref
- `rows`: Số rows examined estimate
- `filtered`: % rows thỏa mãn điều kiện sau index
- `Extra`: Using filesort, Using temporary, Using index

> **Anti-pattern detection:**
> - `Using filesort` với LIMIT nhỏ: Có thể chấp nhận
> - `Using filesort` với large result set: Cần composite index
> - `Using temporary` trong GROUP BY: Xem xét loại bỏ DISTINCT

---

## 6. Query Anti-patterns & Rủi ro Production

### 6.1 SELECT *

**Vấn đề:**
- Network overhead: Truyền thừa columns không cần
- Memory pressure: Buffer pool chứa data không dùng
- Covering index không hiệu quả

**Giải pháp:**
```sql
-- Thay vì
SELECT * FROM users WHERE id = 123;

-- Dùng
SELECT id, username, email FROM users WHERE id = 123;
```

### 6.2 Implicit Type Conversion

```sql
-- Anti-pattern: Column bên trái bị function/convert
SELECT * FROM orders WHERE YEAR(created_at) = 2024;
-- → Full table scan, không dùng được index

-- Tốt hơn
SELECT * FROM orders WHERE created_at >= '2024-01-01' 
                       AND created_at < '2025-01-01';
```

**Nguyên tắc SARGable:**
- Search ARGuments - điều kiện cho phép index range scan
- Tránh function/operation trên indexed column

### 6.3 OFFSET Pagination

```sql
-- Trang 10000, page size 20
SELECT * FROM orders ORDER BY id LIMIT 20 OFFSET 200000;
-- → Scan 200020 rows, discard 200000
```

**Giải pháp Keyset Pagination:**
```sql
-- Lấy page tiếp theo
SELECT * FROM orders 
WHERE id > :last_seen_id 
ORDER BY id LIMIT 20;
```

| Method | Page đầu | Page 10000 | Random jump | Implementation |
|--------|----------|------------|-------------|----------------|
| OFFSET | O(limit) | O(offset+limit) | Yes | Đơn giản |
| Keyset | O(limit) | O(limit) | No | Cần cursor state |
| Seek | O(limit) | O(limit) | Complex | Composite key |

### 6.4 N+1 Query Problem

```java
// Anti-pattern: N+1 queries
List<User> users = userRepository.findAll();  // 1 query
for (User user : users) {
    List<Order> orders = orderRepository.findByUserId(user.getId());  // N queries
}
// Total: N+1 queries
```

**Giải pháp:**
```sql
-- JOIN FETCH (JPA)
SELECT u FROM User u LEFT JOIN FETCH u.orders;

-- Hoặc batch fetch
SELECT * FROM orders WHERE user_id IN (?, ?, ?, ...);
```

### 6.5 Missing Index / Wrong Index Order

**Composite Index Ordering Rules:**
```sql
CREATE INDEX idx_orders ON orders (status, created_at, user_id);

-- Query sử dụng index: ✓
WHERE status = 'completed' AND created_at > '2024-01-01'
WHERE status = 'completed'  (leftmost prefix)

-- Query KHÔNG sử dụng index hiệu quả: ✗
WHERE created_at > '2024-01-01'  (skip status)
WHERE user_id = 123  (skip first 2 columns)
```

**Quy tắc Cardinality:** Đặt column có cardinality cao nhất trước khi query có equality trên column đó.

### 6.6 Large IN Clauses

```sql
-- Anti-pattern: IN với 10000+ values
SELECT * FROM users WHERE id IN (1,2,3,...,10000);
```

**Vấn đề:**
- Parser overhead
- Query plan cache pollution
- Memory usage trong execution

**Giải pháp:**
```sql
-- Dùng temporary table
CREATE TEMP TABLE tmp_user_ids (id INT PRIMARY KEY);
INSERT INTO tmp_user_ids VALUES (1), (2), ...;

SELECT u.* FROM users u
JOIN tmp_user_ids t ON u.id = t.id;
```

### 6.7 SELECT FOR UPDATE không cần thiết

```sql
-- Anti-pattern: Lock toàn bộ range
SELECT * FROM inventory WHERE product_id = 123 FOR UPDATE;
UPDATE inventory SET quantity = quantity - 1 WHERE product_id = 123;

-- Tốt hơn: Optimistic locking hoặc single statement
UPDATE inventory SET quantity = quantity - 1 
WHERE product_id = 123 AND quantity >= 1;
```

---

## 7. Khuyến nghị Thực chiến Production

### 7.1 Monitoring & Observability

**Metrics cần theo dõi:**

| Metric | Ngưỡng cảnh báo | Ý nghĩa |
|--------|----------------|---------|
| **Query duration p99** | > 100ms | Slow query detection |
| **Seq scan frequency** | > 10% queries | Missing index |
| **Cache hit ratio** | < 99% | Memory pressure |
| **Plan cache hit ratio** | < 90% | Parse overhead |
| **Lock wait time** | > 10ms | Contention |

**Tools:**
- PostgreSQL: `pg_stat_statements`, `auto_explain`, `pg_stat_user_tables`
- MySQL: `performance_schema`, `slow_query_log`, `sys` schema
- Cloud: AWS Performance Insights, Azure Query Performance Insight

### 7.2 Query Plan Management

**Plan Regression Prevention:**
```sql
-- PostgreSQL: Save stable plan
CREATE EXTENSION IF NOT EXISTS pg_plan_advisor;

-- Oracle: SQL Plan Baselines
-- SQL Server: Query Store forced plans
```

**Hints - Dùng có chừng mực:**
```sql
-- PostgreSQL
SET enable_seqscan = off;  -- Session level, tránh production

-- MySQL
SELECT /*+ INDEX(orders idx_status) */ * FROM orders;

-- Chỉ dùng hints khi:
-- 1. Đã verify là optimizer chọn sai
-- 2. Stats đã up-to-date
-- 3. Không có cách khác (index, rewrite)
```

### 7.3 Partitioning Strategy

**Khi nào partition:**
- Table > 100GB hoặc > 100M rows
- Query pattern có natural partition key (time, region)
- Data lifecycle requirements (archive old partitions)

**Partition Pruning:**
```sql
-- Table partitioned by RANGE(created_at)
SELECT * FROM events WHERE created_at >= '2024-01-01';
-- → Chỉ scan partition liên quan, không scan toàn bộ table
```

### 7.4 Backward Compatibility

**Schema Evolution:**
- Không drop column ngay - dùng DEPRECATION period
- Index mới: `CREATE INDEX CONCURRENTLY` (PostgreSQL) để không lock table
- Thêm column: DEFAULT value hoặc nullable trước, backfill sau

---

## 8. Kết luận

**Bản chất của Query Optimization:**

Query optimization không phải là "viết SQL đúng" mà là **hiểu cách database engine "nghĩ"** và **cung cấp đúng thông tin** (statistics) để nó ra quyết định tốt.

**Chốt lại các điểm then chốt:**

1. **Cardinality estimation là gốc rễ** - Stats sai → plan sai → performance kém. `ANALYZE` thường xuyên.

2. **Execution plan là ngôn ngữ chung** - Senior engineer đọc plan như đọc code. Cost estimate/actual mismatch là red flag.

3. **Trade-off chính:** Index tăng read performance, giảm write performance, tốn disk. Không phải index càng nhiều càng tốt.

4. **Anti-patterns có pattern:** N+1, SELECT *, OFFSET pagination, implicit conversion - nhận diện và refactor sớm.

5. **Production = Observability:** Không optimize những gì không đo được. pg_stat_statements và slow query log là bạn.

**Thứ tự ưu tiên khi gặp query chậm:**
1. Đọc execution plan (EXPLAIN ANALYZE)
2. Kiểm tra statistics freshness
3. Rewrite query (SARGable, avoid anti-patterns)
4. Thêm index (cân nhắc write overhead)
5. Partition/archiving
6. Scale hardware (cuối cùng)

---

## 9. Tài liệu Tham khảo

- PostgreSQL Documentation: Chapter 70 - Query Planning
- "High Performance PostgreSQL" - Gregory Smith
- "SQL Performance Explained" - Markus Winand
- MySQL 8.0 Reference: Optimization
- "Database Internals" - Alex Petrov (O'Reilly)
