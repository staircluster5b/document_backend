# Transaction Isolation - ACID, Isolation Levels, MVCC, Phantom Reads

## 1. Mục tiêu của Task

Hiểu sâu bản chất cách database đảm bảo tính nhất quán khi xử lý đồng thủy nhiều transaction. Tập trung vào:
- Cơ chế hoạt động thực sự của các Isolation Level (không chỉ dừng ở định nghĩa)
- MVCC - cách triển khai trong các hệ thống production
- Phantom Read - tại sao nó khó xử lý và các giải pháp thực tế
- Trade-off giữa Consistency và Performance trong hệ thống thực tế

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 ACID - Phân Tích Thực Dụng

ACID không phải là "tất cả hoặc không có gì". Mỗi hệ thống database triển khai ACID với mức độ khác nhau.

| Property | Bản Chất Thực Sự | Trade-off Chính |
|----------|------------------|-----------------|
| **Atomicity** | Không phải "tất cả thành công hoặc rollback toàn bộ". Đó là cơ chế UNDO logging. WAL (Write-Ahead Log) ghi lại before-image của mọi thay đổi để có thể revert. | Ghi log = overhead I/O. Mỗi write thực tế = 2 writes (data + log). |
| **Consistency** | Database constraint enforcement + application-level invariants. Không phải tính chất độc lập mà là kết quả của A-I-D. | Constraint checking = lock contention. Foreign key checks block concurrent updates. |
| **Isolation** | Ảo tưởng rằng transaction chạy "đơn luồng". Đạt được bằng locking hoặc MVCC. | Locking = blocking. MVCC = snapshot overhead + version cleanup. |
| **Durability** | Data phải survive crash. Đạt được qua fsync() xuống disk. | fsync = latency. Group commit và write-back caching để giảm overhead. |

> **Lưu ý quan trọng**: Atomicity và Durability đảm bảo bằng **WAL (Write-Ahead Log)**. Đây là cấu trúc dữ liệu append-only, sequential I/O nhanh hơn random I/O của data pages. Đây là lý do PostgreSQL/MySQL đều dùng WAL.

### 2.2 Isolation Levels - Cơ Chế Bên Trong

SQL-92 định nghĩa 4 isolation levels dựa trên phenomena cần ngăn chặn. Nhưng **hầu hết database không tuân theo nghiêm ngặt** - mỗi engine có implementation riêng.

| Level | Phenomena Cho Phép | PostgreSQL Implementation | MySQL InnoDB Implementation |
|-------|-------------------|---------------------------|----------------------------|
| **READ UNCOMMITTED** | Dirty Read, Non-repeatable Read, Phantom | Không hỗ trợ (fall back to Read Committed) | Dirty read thực sự (đọc uncommitted changes) |
| **READ COMMITTED** | Non-repeatable Read, Phantom | Mỗi query lấy snapshot mới | Mỗi query lấy snapshot mới |
| **REPEATABLE READ** | Phantom (theo SQL-92) | Snapshot isolation (không có phantom) | Next-key locking (ngăn phantom) |
| **SERIALIZABLE** | None | Serializable Snapshot Isolation (SSI) | 2PL (Two-Phase Locking) truyền thống |

#### PostgreSQL vs MySQL - Triển Khai Khác Biệt

```
PostgreSQL: MVCC-based (Snapshot Isolation)
├── Mỗi transaction thấy snapshot của database tại thởi điểm start
├── Không cần read locks → readers không block writers
└── Serializable = SSI (optimistic, check conflicts at commit)

MySQL InnoDB: MVCC + Locking hybrid
├── REPEATABLE READ: Consistent read (snapshot) + next-key locks
├── Next-key lock = record lock + gap lock
└── Serializable = 2PL (pessimistic, lock tất cả)
```

> **Trade-off then chốt**: PostgreSQL ưu tiên concurrency (optimistic), MySQL ưu tiên strictness (pessimistic). SSI của PostgreSQL có thể rollback transaction ở commit nếu phát hiện serialization anomaly.

---

## 3. MVCC - Multi-Version Concurrency Control

### 3.1 Bản Chất MVCC

MVCC không phải "một công nghệ" mà là **chiến lược quản lý versions**. Mỗi update tạo version mới, giữ version cũ để readers thấy consistent snapshot.

**Các cách triển khai version storage:**

| Approach | Database | Cơ Chế | Ưu Điểm | Nhược Điểm |
|----------|----------|--------|---------|------------|
| **Append-only** | PostgreSQL | Update = insert row mới, mark old as dead | Simple, fast rollback | Table bloat, cần VACUUM |
| **Undo log** | MySQL, Oracle | Update ghi vào page, old version vào undo log | No table bloat | Undo log management, long query = large undo |
| **Delta storage** | SQL Server | Chỉ lưu changed columns | Space efficient | Complex reconstruction |

### 3.2 PostgreSQL MVCC Deep Dive

```
Heap Tuple Structure:
+----------------+----------------+----------------+
| xmin (4 bytes) | xmax (4 bytes) | t_ctid (6 bytes)| ... data ...
+----------------+----------------+----------------+

- xmin: Transaction ID inserted this row
- xmax: Transaction ID deleted/updated this row (0 = alive)
- t_ctid: Pointer to newer version (if updated)
```

**Visibility Check** (đơn giản hóa):
```
Một row visible với transaction T nếu:
1. xmin đã commit VÀ xmin < T.snapshot_xmin
2. xmax = 0 HOẶC xmax chưa commit HOẶC xmax > T.snapshot_xmax
```

**Transaction ID Wraparound** - Vấn đề production nghiêm trọng:
- Transaction ID là 32-bit → 4 billion limit
- XID counter wraparound sau ~2 billion transactions
- PostgreSQL chạy VACUUM FREEZE để "đóng băng" old tuples
- **Production risk**: Nếu wraparound đến gần, database tự động shutdown để bảo vệ dữ liệu

### 3.3 MySQL InnoDB MVCC

```
Undo Log Structure:
+------------------+------------------+
| DB_ROW_ID        | DB_TRX_ID        |
| (hidden PK)      | (6 bytes)        |
+------------------+------------------+
| DB_ROLL_PTR      | ... user data ...|
| (7 bytes)        |                  |
+------------------+------------------+

DB_ROLL_PTR trỏ đến undo log entry chứa previous version
```

**ReadView** - Snapshot của transaction:
```
ReadView chứa:
- m_ids: Set of active transaction IDs at snapshot time
- min_trx_id: Minimum active ID
- max_trx_id: Next ID to be assigned
- creator_trx_id: ID của transaction tạo ReadView
```

**Visibility Rule**:
- Row với trx_id < min_trx_id: Visible (đã commit trước snapshot)
- Row với trx_id trong m_ids: Invisible (đang active)
- Row với trx_id > max_trx_id: Invisible (created after snapshot)
- Row với trx_id = creator_trx_id: Visible (tự tạo)

> **Production concern**: Long-running transaction giữ ReadView cũ → undo log không thể purge → tablespace bloat → performance degradation.

---

## 4. Phantom Read - Vấn Đề và Giải Pháp

### 4.1 Tại Sao Phantom Read Khác Biệt

| Phenomenon | Mô Tả | Cách Ngăn Chặn |
|------------|-------|----------------|
| **Dirty Read** | Đọc uncommitted data | Không cho phép đọc data của transaction chưa commit |
| **Non-repeatable Read** | Cùng query 2 lần, row khác nhau | Lock rows đã đọc, hoặc snapshot isolation |
| **Phantom Read** | Cùng query 2 lần, **số lượng rows khác nhau** | Cần lock cả range + gaps (next-key locks) hoặc SSI |

**Phantom khó xử lý vì:**
- Insert của transaction khác tạo row mới "phantom" match WHERE clause
- Row-level lock không lock "khoảng trống" nơi row mới sẽ insert
- Cần mechanism để lock "potential rows"

### 4.2 Next-Key Locks (MySQL Approach)

```
Index structure (B+Tree):
                    [10 | 20 | 30]
                   /     |      \
[1,5,8,10]  [12,15,18,20]  [25,28,30]

Next-key lock trên 15 = Lock [10, 15] (gap) + Lock 15 (record)

Query: SELECT * FROM t WHERE id > 10 AND id < 20 FOR UPDATE;
Locks acquired:
- Gap lock (10, 15]
- Gap lock (15, 20]
- Record lock on 15

→ Transaction khác không thể insert id=12, 17 (nằm trong gap)
```

**Trade-offs của Next-Key Locks:**
- ✅ Ngăn phantom read hoàn toàn ở REPEATABLE READ
- ❌ Lock gap = blocking inserts → concurrency giảm
- ❌ Deadlock probability tăng (2 transactions lock overlapping gaps)

### 4.3 Serializable Snapshot Isolation - SSI (PostgreSQL Approach)

SSI là optimistic concurrency control cho Serializable isolation.

**Cơ chế:**
- Cho phép transactions chạy concurrently
- Theo dõi read-write dependencies giữa transactions
- Phát hiện serialization anomaly (rw-conflict cycles)
- Abort một transaction nếu cycle detected

**rw-conflict detection:**
```
T1 đọc row X, T2 write row X → rw-dependency T1 → T2
T2 đọc row Y, T1 write row Y → rw-dependency T2 → T1

Cycle T1 → T2 → T1 = Serialization anomaly
→ Abort một transaction
```

**Trade-offs của SSI:**
- ✅ No locking overhead cho reads
- ✅ Better concurrency than 2PL
- ❌ Aborts ở commit time (wasted work)
- ❌ Overhead tracking read sets

### 4.4 Predicate Locks (Theoretical)

```
Predicate lock: Lock tất cả rows thỏa mãn predicate (WHERE clause)

SELECT * FROM orders WHERE amount > 1000
→ Lock predicate: "amount > 1000"
→ Block insert/update of any row with amount > 1000
```

- Được đề xuất trong SQL-92 nhưng ít database implement (quá expensive)
- PostgreSQL 9.1+ có predicate locks nhưng chỉ cho serializable SSI
- Index-only scans có thể dùng page-level locks làm approximation

---

## 5. So Sánh Các Lựa Chọn

### 5.1 Isolation Level Selection - Decision Matrix

| Use Case | Recommended Level | Lý Do |
|----------|-------------------|-------|
| Reporting/Analytics | READ COMMITTED | Không cần strict consistency, snapshot mỗi query = fresh data |
| OLTP với critical financial data | SERIALIZABLE | Anomalies không chấp nhận được |
| General OLTP | REPEATABLE READ | Balance giữa consistency và concurrency |
| Read-heavy cache warming | READ UNCOMMITTED | Chấp nhận dirty read để avoid locking (nếu DB hỗ trợ) |

### 5.2 PostgreSQL vs MySQL - Khi Nào Dùng Gì

| Factor | PostgreSQL | MySQL (InnoDB) |
|--------|-----------|----------------|
| **Complex queries** | ✅ Optimizer tốt hơn | ⚠️ Đơn giản hơn |
| **High concurrency writes** | ✅ SSI ít blocking | ⚠️ Gap locks block inserts |
| **Strict locking needs** | ⚠️ Optimistic (may abort) | ✅ Pessimistic (no surprise aborts) |
| **Replication** | ✅ Logical replication flexible | ⚠️ Physical replication simpler |
| **MVCC overhead** | ⚠️ VACUUM maintenance | ✅ Undo purge automatic |

---

## 6. Rủi Ro, Anti-Patterns, Lỗi Thường Gặp

### 6.1 Production Issues

| Issue | Nguyên Nhân | Phát Hiện | Khắc Phục |
|-------|-------------|-----------|-----------|
| **XID Wraparound** (PostgreSQL) | Long-running transaction/block vacuum | `age(datfrozenxid)` > 2B | Kill long queries, manual vacuum freeze |
| **Undo Log Bloat** (MySQL) | Long transaction giữ old versions | `information_schema.innodb_metrics` | Kill transaction, optimize purge |
| **Serialization Failures** (PostgreSQL) | SSI aborts ở high concurrency | `pg_stat_database.conflicts` | Retry logic, reduce transaction scope |
| **Deadlocks** (MySQL) | Next-key lock contention | `SHOW ENGINE INNODB STATUS` | Consistent lock ordering, shorten transactions |
| **Lock Wait Timeout** | Lock held quá lâu | Application timeout errors | Index optimization, transaction splitting |

### 6.2 Anti-Patterns

**1. Long-running transaction trong batch job**
```java
// ANTI-PATTERN
begin transaction;
for (each row in millions) {
    process(row);  // Hours of work
    update(row);
}
commit;
// → Giữ snapshot cũ, prevent vacuum/undo purge

// BETTER
for (batch : batches) {
    begin transaction;
    for (row : batch) {
        process(row);
        update(row);
    }
    commit;  // Frequent commits
}
```

**2. Không handle serialization failures**
```java
// ANTI-PATTERN
try {
    jdbcTemplate.update(sql);  // May fail in SSI
} catch (DataAccessException e) {
    throw new RuntimeException(e);  // No retry
}

// BETTER
int maxRetries = 3;
for (int i = 0; i < maxRetries; i++) {
    try {
        return transactionTemplate.execute(status -> {
            // business logic
            return result;
        });
    } catch (SerializationFailureException e) {
        if (i == maxRetries - 1) throw e;
        Thread.sleep((long)(Math.random() * 100 * (i + 1)));  // Exponential backoff
    }
}
```

**3. Snapshot query trong transaction dài**
```java
// ANTI-PATTERN - Phantom read risk
@Transactional(isolation = Isolation.REPEATABLE_READ)
public void processOrders() {
    List<Order> orders = orderRepo.findByStatus("PENDING");  // T0
    // ... some processing ...
    List<Order> orders2 = orderRepo.findByStatus("PENDING"); // T1 - May see new orders!
    // orders.size() != orders2.size() → Phantom read
}
```

**4. SELECT FOR UPDATE không có index**
```sql
-- ANTI-PATTERN
SELECT * FROM orders WHERE status = 'PENDING' FOR UPDATE;
-- → Table scan + lock tất cả rows = concurrency killer

-- BETTER
CREATE INDEX idx_orders_status ON orders(status);
SELECT * FROM orders WHERE status = 'PENDING' FOR UPDATE;
-- → Index scan + lock chỉ matching rows
```

### 6.3 Lost Update Problem

Khi 2 transactions đọc cùng data, sau đó cùng update:

```
T1: READ balance = 100
T2: READ balance = 100
T1: WRITE balance = 120 (100 + 20)
T2: WRITE balance = 130 (100 + 30)  -- Lost T1's update!
T1: COMMIT
T2: COMMIT  -- Final balance = 130 (wrong, should be 150)
```

**Giải pháp:**
1. **Optimistic Locking**: Version column, check at update
2. **Pessimistic Locking**: SELECT FOR UPDATE
3. **Atomic operations**: `UPDATE account SET balance = balance + 20`
4. **Compare-and-set**: `UPDATE ... WHERE balance = 100`

---

## 7. Khuyến Nghị Thực Chiến Production

### 7.1 Transaction Design

**Scope ngắn nhất có thể:**
```java
// BAD - Business logic trong transaction
@Transactional
public void process() {
    validate();      // 50ms
    callExternalAPI(); // 500ms - Network!
    saveToDB();      // 10ms
}

// GOOD
public void process() {
    validate();
    Result result = callExternalAPI();
    saveInTransaction(result);  // Only DB work in tx
}

@Transactional
private void saveInTransaction(Result r) {
    saveToDB(r);
}
```

### 7.2 Isolation Level Strategy

```
Default: READ COMMITTED
├── Phần lớn queries: Dùng default
├── Critical financial: SERIALIZABLE (với retry)
├── Batch processing: REPEATABLE READ (nhưng transactions ngắn)
└── Read-only reports: READ COMMITTED (fresh data each query)
```

### 7.3 Monitoring

**PostgreSQL:**
```sql
-- XID wraparound risk
SELECT datname, age(datfrozenxid) 
FROM pg_database 
WHERE age(datfrozenxid) > 1000000000;

-- Long-running transactions
SELECT pid, usename, state, 
       now() - query_start as duration,
       left(query, 100) as query_snippet
FROM pg_stat_activity
WHERE state != 'idle' 
  AND now() - query_start > interval '1 minute';

-- Serialization failures
SELECT conflicts, deadlocks 
FROM pg_stat_database 
WHERE datname = current_database();
```

**MySQL:**
```sql
-- Long transactions
SELECT trx_id, trx_mysql_thread_id, 
       TIMESTAMPDIFF(SECOND, trx_started, NOW()) as seconds,
       LEFT(trx_query, 100) as query
FROM information_schema.innodb_trx
WHERE TIMESTAMPDIFF(SECOND, trx_started, NOW()) > 60;

-- Lock waits
SELECT * FROM sys.innodb_lock_waits;

-- Undo log size
SELECT SUM(data_length) / 1024 / 1024 / 1024 as undo_gb
FROM information_schema.innodb_sys_tablespaces
WHERE name LIKE '%undo%';
```

### 7.4 Retry Strategy

```java
@Component
public class TransactionRetry {
    
    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 50;
    
    public <T> T executeWithRetry(Supplier<T> operation) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return operation.get();
            } catch (TransientDataAccessException e) {  // Serialization failure, deadlock
                if (attempt == MAX_RETRIES) {
                    throw new RuntimeException("Max retries exceeded", e);
                }
                // Exponential backoff with jitter
                long delay = BASE_DELAY_MS * (1L << (attempt - 1));
                long jitter = ThreadLocalRandom.current().nextLong(delay / 2);
                try {
                    Thread.sleep(delay + jitter);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted", ie);
                }
            }
        }
        throw new IllegalStateException("Unreachable");
    }
}
```

---

## 8. Kết Luận

**Bản chất của Transaction Isolation:**
- Không phải "magic" đảm bảo consistency, mà là **trade-off giữa correctness và concurrency**
- MVCC giải quyết reader-writer conflict bằng cách giữ nhiều versions
- Phantom read là vấn đề khó nhất, đòi hỏi gap locking hoặc optimistic detection

**Quyết định thiết kế quan trọng:**
1. **Mặc định dùng READ COMMITTED** - Đủ cho hầu hết use cases
2. **Chỉ dùng SERIALIZABLE khi thực sự cần** - Với retry logic robust
3. **Transaction càng ngắn càng tốt** - Tránh long-running transactions
4. **SELECT FOR UPDATE phải có index** - Tránh table locks
5. **Monitoring XID age (PostgreSQL) và undo log (MySQL)** - Prevent bloat

**Cập nhật hiện đại:**
- PostgreSQL 12+: SSI improvements, reduced false positives
- MySQL 8.0: Instant DDL giảm lock time cho schema changes
- Java 21: Virtual threads giúp handle nhiều concurrent transactions (nhưng không giải quyết fundamental isolation issues)

---

*Research completed: Transaction Isolation - ACID, Isolation Levels, MVCC, Phantom Reads*
