# Global Database Architecture: Spanner, CockroachDB, Geo-partitioning & Conflict Resolution

## 1. Mục tiêu của task

Hiểu sâu kiến trúc cơ sở dữ liệu phân tán toàn cầu (Globally Distributed Database), tập trung vào:
- Bản chất của **TrueTime API** và **External Consistency** trong Google Spanner
- Kiến trúc **Multi-raft** và **Serializability** của CockroachDB
- Cơ chế **Geo-partitioning** - phân vùng dữ liệu theo vị trí địa lý
- **Conflict Resolution** trong hệ thống distributed database có latency cao giữa các region

> **Bối cảnh sử dụng**: Khi hệ thống cần ACID transactions xuyên suốt multiple regions, latency < 100ms cho read/write toàn cầu, và không chấp nhận eventual consistency.

---

## 2. Bản chất và cơ chế hoạt động

### 2.1. Google Spanner: External Consistency qua TrueTime

#### Bản chất vấn đề

Trong distributed database truyền thống, đảm bảo **Serializability** xuyên suốt multiple datacenter là bài toán "nearly impossible" vì:
- Clock skew giữa các node có thể lên đến 10-100ms
- NTP (Network Time Protocol) chỉ đảm bảo độ chính xác ~1-10ms
- Không thể xác định chính xác "before/after" giữa các events ở different locations

#### TrueTime API: Bước đột phá

Spanner không dùng single timestamp. Thay vào đó, mỗi event nhận một **time interval**: `[earliest, latest]`

```
TT.now() → interval [t_earliest, t_latest]
           where: t_latest - t_earliest = ε (uncertainty interval)

TT.after(t)  → true nếu t < t_earliest của current time
TT.before(t) → true nếu t > t_latest của current time
```

**Cơ chế giảm ε (uncertainty):**
- Reference clocks: GPS + Atomic clocks trong mỗi datacenter
- Reference masters per datacenter đồng bộ với time masters khác
- ε thường < 7ms, worst case < 10ms sau khi đồng bộ

#### External Consistency Guarantee

Spanner đảm bảo: **Nếu transaction T1 commit trước T2 start, thì T1's commit timestamp < T2's commit timestamp**

**Cơ chế đạt được:**

```
Commit Wait: Một transaction chỉ được báo "commit success" sau khi:
    t_commit + ε < TT.now().earliest

Nghĩa là: Chờ cho đến khi chắc chắn toàn bộ hệ thống đã vượt qua t_commit
```

**Tác động đến latency:**
- Write latency tối thiểu = 2 × ε (round-trip để commit)
- Với ε = 7ms → write latency ~14ms (chấp nhận được)
- Không có clock sync này, không thể có external consistency với latency thấp

### 2.2. CockroachDB: Serializability qua Multi-Raft

#### Kiến trúc tổng quan

CockroachDB (CRDB) implement **Serializable Default Isolation** mà không cần specialized hardware clocks như Spanner.

**Các thành phần chính:**

| Component | Chức năng | Đặc điểm |
|-----------|-----------|----------|
| **Node** | Một instance CRDB | Chứa nhiều ranges, mỗi range là 1 Raft group |
| **Range** | Shard của dữ liệu (~64MB default) | Mỗi range = 1 Raft group, 3-5 replicas |
| **Store** | Physical storage trên 1 node | RocksDB/Pebble engine |
| **Raft Group** | Consensus cho 1 range | Leader election, log replication |

#### Hybrid Logical Clock (HLC)

Thay vì TrueTime, CRDB dùng **HLC** - kết hợp physical clock + logical counter:

```
HLC = (physical_time, logical_counter)

So sánh: (pt1, lc1) > (pt2, lc2) nếu:
    pt1 > pt2, HOẶC
    pt1 == pt2 VÀ lc1 > lc2
```

**Tính chất:**
- Monotonic: Không bao giờ giảm
- Causal tracking: Capture happens-before relationships
- Khi physical clock tiến → reset logical counter về 0

#### Transaction Protocol: Parallel Commits

CRDB optimize cho latency thấp với **Parallel Commits**:

**Truyền thống (2PC - Two Phase Commit):**
```
1. Prepare phase: Hỏi tất cả participants "Can you commit?"
2. Commit phase: Nếu all yes → broadcast commit
3. Latency: 2 network round-trips
```

**Parallel Commits optimization:**
```
1. Write intents + STAGING record trong 1 round-trip
2. Write to all ranges song song
3. STAGING record chứa list của tất cả writes
4. Nếu coordinator fail, bất kỳ reader nào cũng có thể "recover" transaction bằng cách check STAGING
5. Không cần phase 2 nếu tất cả writes thành công
```

**Kết quả:** Commit latency giảm từ 2 RTT xuống ~1 RTT.

#### Serializable Isolation: Timestamp Allocation & Conflict Detection

**Read Timestamp:**
- Mỗi transaction nhận 1 timestamp khi bắt đầu
- All reads see data "as of" that timestamp (Snapshot Isolation cơ bản)

**Write Timestamp:**
- Khi transaction commit, cần write timestamp
- Nếu write timestamp > read timestamp → Có thể serializable conflict

**Conflict Detection:**
```
Giao dịch T1 đọc key K tại timestamp 100
Giao dịch T2 ghi key K, commit tại timestamp 150

→ Khi T1 muốn commit, CRDB detect: 
   "T1 read K at 100, nhưng K đã bị modify sau đó (at 150)"
   
→ T1 bị **Restart** với timestamp mới (155), read lại K
```

**Read Refresh:**
- Thay vì restart toàn bộ, CRDB thử "refresh" reads: Kiểm tra xem data đã đọc có bị modify sau read timestamp không
- Nếu không → tiếp tục commit, chỉ tăng commit timestamp
- Nếu có → buộc restart

---

## 3. Geo-partitioning: Phân vùng dữ liệu theo địa lý

### 3.1. Bản chất và mục tiêu

**Bài toán:** Dữ liệu của user ở US nên lưu ở US, user EU lưu ở EU để:
- Tuân thủ GDPR (data residency)
- Giảm read latency cho local users
- Duy trì fault tolerance (replicas cross-region)

**Challenge:** Vẫn muốn xử lý transactions xuyên suốt tất cả data (e.g., global analytics, cross-region transfers).

### 3.2. Spanner: Partitioning qua Split & Movement

**Spanner organization:**
```
Database → Directories → Fragments

Directory = continuous key range (table prefix + primary key range)
Fragment = 1 hoặc nhiều directories được replicate together
```

**Geo-partitioning strategy:**

```sql
-- Định nghĩa placement policy
CREATE PLACEMENT POLICY us_policy
    OPTIONS (region = 'us-central1', replicas = 3);

CREATE PLACEMENT POLICY eu_policy
    OPTIONS (region = 'europe-west1', replicas = 3);

-- Table với partitioned key
CREATE TABLE users (
    region STRING NOT NULL,  -- Partition key
    user_id INT64 NOT NULL,
    data STRING
) PRIMARY KEY (region, user_id)
PARTITION BY REGION;
```

**Automatic rebalancing:**
- Spanner tự động split directories khi lớn
- Dựa trên access patterns, di chuyển fragments đến optimal locations
- Admin có thể override với placement policies

### 3.3. CockroachDB: Partitioning qua Zone Configs & Table Partitions

**Zone Configuration:**
```sql
-- Tạo replication zone cho US data
CREATE DATABASE us_customers;
ALTER DATABASE us_customers CONFIGURE ZONE USING
    constraints = '[+region=us-east]',
    num_replicas = 3;

-- Partition table theo region
CREATE TABLE orders (
    region STRING NOT NULL,
    order_id UUID DEFAULT gen_random_uuid(),
    amount DECIMAL,
    PRIMARY KEY (region, order_id)
) PARTITION BY LIST (region) (
    PARTITION us VALUES IN ('us-east', 'us-west'),
    PARTITION eu VALUES IN ('eu-west', 'eu-central'),
    PARTITION asia VALUES IN ('ap-south', 'ap-northeast')
);

-- Áp dụng zone config cho từng partition
ALTER PARTITION us OF TABLE orders CONFIGURE ZONE USING
    constraints = '[+region=us-east]',
    lease_preferences = '[[+region=us-east]]';
```

**Leaseholder Placement:**
- Mỗi range có 1 **leaseholder** (raft leader) xử lý writes và coordinate reads
- Leaseholder nên ở region có nhiều traffic nhất cho key range đó
- CockroachDB tự động chuyển leaseholder dựa trên latency

**Follower Reads:**
```sql
-- Cho phép read từ follower replicas (stale but fast)
SET SESSION CHARACTERISTICS AS TRANSACTION AS OF SYSTEM TIME '-10s';
SELECT * FROM orders WHERE region = 'us-east';
```
- Follower reads giảm latency cho read-heavy workloads
- Chấp nhận staleness (data 1-10s old) để đổi lấy <10ms read latency

### 3.4. Trade-offs trong Geo-partitioning

| Chiến lược | Pros | Cons |
|------------|------|------|
| **Partition by Region** | Low local latency, GDPR compliance | Cross-region transactions slower |
| **Duplicate Global Data** | Fast reads everywhere | Write amplification, consistency issues |
| **Centralized + Caching** | Simple, fast writes | Cache invalidation, eventual consistency |

> **Quy tắc vàng:** Geo-partition by tenant/region cho OLTP, replicate read-only aggregates cho analytics.

---

## 4. Conflict Resolution trong Global Distributed Database

### 4.1. Types of Conflicts

**1. Write-Write Conflicts:**
```
T1 writes K=V1 tại us-east (timestamp 100)
T2 writes K=V2 tại eu-west (timestamp 100)  -- Clock skew!

→ Same key, "simultaneous" writes
```

**2. Read-Write Conflicts (Serialization Failures):**
```
T1 reads K, sees V1 tại timestamp 100
T2 writes K=V2, commit tại timestamp 105
T1 tries to commit → Read dependency violated
```

**3. Constraint Violations:**
- Unique constraint xuyên suốt regions
- Foreign key references cross-region
- Check constraints

### 4.2. Spanner: External Consistency + Timestamp Ordering

Spanner **không có write-write conflicts** trong nghĩa truyền thống vì:

**Paxos Group + Timestamp:**
- Mỗi key range thuộc 1 Paxos group
- Paxos leader assign monotonic timestamps
- Write chỉ thành công nếu timestamp > tất cả previous writes

**Timestamp assignment:**
```
Nếu 2 transactions cùng write K:
- Paxos leader assign timestamp t1 và t2
- Nếu t1 < t2: T2's write thắng (last-write-wins)
- Không cần conflict resolution phức tạp
```

**Chờ đợi (Commit Wait):**
- Khi T1 commit tại timestamp t, chờ đến khi `t + ε < now`
- Đảm bảo T2 (start sau T1) nhận timestamp > t

### 4.3. CockroachDB: Write Intents & Transaction Restart

**Write Intent mechanism:**
```
Khi T1 ghi key K:
1. Ghi "intent" vào K: (value=V1, txn_id=T1, timestamp=100)
2. Intent chưa visible cho other transactions
3. Khi T1 commit, intents được "resolve" thành real values
```

**Conflict detection:**
```
T2 muốn ghi key K (timestamp 105):
- Thấy intent của T1 tại K
- Check status của T1:
  * Nếu T1 PENDING → T2 phải wait hoặc push T1 (depend on priority)
  * Nếu T1 COMMITTED → T2 phải restart với timestamp > 100
  * Nếu T1 ABORTED → T2 "resolve" intent và tiếp tục
```

**Deadlock Prevention:**
- CRDB không dùng deadlock detection (expensive)
- Dùng **contention-based restart** với exponential backoff
- Mỗi transaction có priority (random initially, tăng khi restart)
- Higher priority transaction có thể abort lower priority

### 4.4. Conflict Resolution Strategies Comparison

| Database | Strategy | Trade-off |
|----------|----------|-----------|
| **Spanner** | Timestamp ordering + Paxos | Tốn latency (commit wait), nhưng no conflicts |
| **CockroachDB** | Optimistic concurrency + Restart | No commit wait, nhưng restart overhead khi contention cao |
| **Cassandra** | Last-write-wins (LWW) | Simple, fast, nhưng mất data |
| **DynamoDB** | Conditional writes | Application-level conflict handling |

---

## 5. Rủi ro, Anti-patterns, và Lỗi thường gặp

### 5.1. Hotspotting (Write Hotspots)

**Vấn đề:**
- Sequential writes (e.g., auto-increment ID, timestamps) tập trung vào 1 range
- Single range phải handle tất cả writes → Leader node bị quá tải

**Anti-pattern:**
```sql
-- KHÔNG NÊN: Sequential UUID hoặc auto-increment
CREATE TABLE events (
    id BIGSERIAL PRIMARY KEY,  -- Sequential = hotspot
    data JSONB
);
```

**Giải pháp:**
```sql
-- NÊN: Random UUID hoặc hash prefix
CREATE TABLE events (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,  -- Distributed
    data JSONB
);

-- Hoặc hash sharding trong Spanner
CREATE TABLE events (
    shard_id INT64 NOT NULL,  -- hash(user_id) % 100
    event_id INT64 NOT NULL,
    ...
) PRIMARY KEY (shard_id, event_id);
```

### 5.2. Cross-Region Transactions không cần thiết

**Anti-pattern:**
```sql
-- Transaction xuyên suốt nhiều regions
BEGIN;
UPDATE accounts SET balance = balance - 100 
    WHERE id = 'us-user-123';  -- ở us-east
UPDATE accounts SET balance = balance + 100 
    WHERE id = 'eu-user-456';  -- ở eu-west
COMMIT;
```

**Hệ quả:**
- 2-phase commit xuyên Atlantic → 200-500ms latency
- Higher failure rate
- Block resources trong thời gian dài

**Giải pháp - Saga Pattern:**
```sql
-- Local transaction 1 (us-east)
BEGIN;
UPDATE accounts SET balance = balance - 100 
    WHERE id = 'us-user-123';
INSERT INTO outgoing_transfers (id, to_user, amount, status)
    VALUES ('tx-789', 'eu-user-456', 100, 'pending');
COMMIT;

-- Async message queue → eu-west

-- Local transaction 2 (eu-west)  
BEGIN;
UPDATE accounts SET balance = balance + 100
    WHERE id = 'eu-user-456';
UPDATE outgoing_transfers SET status = 'completed'
    WHERE id = 'tx-789';
COMMIT;
```

### 5.3. Ignoring Clock Skew

**Lỗi trong deployment:**
- Không đồng bộ NTP/GPS cho Spanner-like systems
- CockroachDB nodes có clock skew > 500ms

**Hệ quả:**
- Spanner: Uncertainty interval ε tăng → write latency tăng
- CockroachDB: False positive serialization failures, unnecessary restarts

**Best practice:**
- Đảm bảo clock sync (chrony, PTP) trên tất cả nodes
- Monitor clock skew metrics
- Alert khi skew > 100ms

### 5.4. Over-partitioning

**Anti-pattern:**
- Partition mỗi user = 1 range
- 1M users = 1M ranges
- Raft group overhead (memory, CPU) quá cao

**Guidelines:**
- Spanner: Target ~1GB per directory/fragment
- CockroachDB: Target ~64-512MB per range
- Số lượng ranges/node: 1,000-10,000 tùy hardware

### 5.5. Quên Follower Reads

**Anti-pattern:**
- Mọi read đều đi qua leaseholder
- Cross-region reads → 100-200ms latency

**Giải pháp:**
```sql
-- Spanner: Stale reads
SELECT * FROM users 
WHERE user_id = 123
OPTIONS (max_staleness = '10s');

-- CockroachDB: Follower reads
SELECT * FROM users AS OF SYSTEM TIME '-10s'
WHERE user_id = 123;
```

---

## 6. Khuyến nghị thực chiến trong Production

### 6.1. Khi nào nên dùng Global Distributed Database?

| Use Case | Phù hợp? | Lý do |
|----------|----------|-------|
| **Multi-region OLTP** | ✅ Yes | ACID transactions xuyên suốt regions |
| **GDPR Data Residency** | ✅ Yes | Geo-partitioning enforce location |
| **Global Analytics** | ⚠️ Maybe | Query complexity, consider BigQuery/Databricks |
| **Write-heavy, single-region** | ❌ No | Overhead không cần thiết |
| **Eventual consistency OK** | ❌ No | Dùng DynamoDB/Cassandra rẻ hơn |

### 6.2. Schema Design Best Practices

**1. Primary Key Design:**
```sql
-- Bad: Sequential
PRIMARY KEY (id)  -- id = 1, 2, 3, 4...

-- Good: Distributed
PRIMARY KEY (region, tenant_id, id)  -- Region prefix để geo-partition
-- Hoặc
PRIMARY KEY (hash_shard, id)  -- Hash sharding để distribute
```

**2. Interleaved Tables (Spanner):**
```sql
-- Parent-child relationship trong 1 directory
CREATE TABLE customers (
    customer_id INT64 NOT NULL,
    name STRING(100)
) PRIMARY KEY (customer_id);

CREATE TABLE orders (
    customer_id INT64 NOT NULL,
    order_id INT64 NOT NULL,
    amount NUMERIC
) PRIMARY KEY (customer_id, order_id),
  INTERLEAVE IN PARENT customers;
-- Orders cùng location với customer → efficient joins
```

**3. Secondary Indexes:**
```sql
-- Index cũng cần partition-aware
CREATE INDEX idx_orders_date ON orders(order_date)
STORING (amount, status)
PARTITION BY RANGE (order_date);
```

### 6.3. Monitoring & Observability

**Metrics cần theo dõi:**

| Metric | Alert Threshold | Ý nghĩa |
|--------|-----------------|---------|
| **Transaction Restart Rate** | > 1% | Contention cao, cần optimize schema |
| **Clock Skew** | > 100ms | Node desynchronization |
| **Range Count / Node** | > 50,000 | Over-partitioning |
| **Follower Read Ratio** | < 80% for read-heavy | Có thể optimize với follower reads |
| **Commit Latency P99** | > 200ms | Network issues hoặc hotspot |

### 6.4. Migration từ Single-region

**Step-by-step migration:**

1. **Phase 1: Dual-write**
   - Write vào cả old DB và new global DB
   - Read từ old DB

2. **Phase 2: Backfill**
   - Migrate historical data
   - Verify consistency

3. **Phase 3: Read shadowing**
   - Read từ cả 2 DB, compare results
   - Log discrepancies

4. **Phase 4: Cutover**
   - Switch reads sang global DB
   - Keep dual-write cho safety

5. **Phase 5: Cleanup**
   - Remove old DB writes

---

## 7. Kết luận

### Bản chất cốt lõi

**Google Spanner** đạt được **External Consistency** (mạnh hơn Serializable) thông qua:
- **TrueTime API**: Time intervals thay vì single timestamps
- **Commit Wait**: Đảm bảo causality xuyên suốt global system
- **Paxos replication**: Consensus cho mỗi shard

**Trade-off**: Write latency tối thiểu 2×ε (~14ms), cần specialized hardware (GPS/atomic clocks).

**CockroachDB** đạt được **Serializable** mà không cần specialized hardware:
- **HLC**: Hybrid Logical Clock cho causal ordering
- **Parallel Commits**: Optimize 2PC xuống ~1 RTT
- **Optimistic concurrency**: Restart khi conflict, phù hợp workload low-contention

**Trade-off**: Contention cao → nhiều restart → throughput giảm.

### Geo-partitioning

Không phải magic. Là sự đánh đổi có chủ đích:
- **Partition theo region**: Giảm local latency, nhưng cross-region transactions expensive
- **Sử dụng follower reads**: Chấp nhận staleness để đổi lấy latency
- **Schema design quan trọng hơn tuning**: Hotspot prevention > parameter tuning

### Conflict Resolution

Trong global distributed database:
- **Spanner**: Timestamp ordering, "no conflicts" nhưng tốn latency
- **CRDB**: Optimistic detection + restart, phù hợp low-contention
- **Không có silver bullet**: Chọn dựa trên workload pattern

### Quyết định kiến trúc

> **Dùng Global Distributed Database khi:** ACID transactions multi-region là bắt buộc, và bạn sẵn sàng trả giá bằng complexity + cost.
>
> **Đừng dùng khi:** Eventual consistency chấp nhận được, hoặc workload primarily single-region.

---

## 8. Tài liệu tham khảo

1. **Spanner: Google's Globally-Distributed Database** (OSDI 2012)
2. **CockroachDB Architecture** - cockroachlabs.com/docs/stable/architecture
3. **Spanner, TrueTime, and the CAP Theorem** - Google Cloud Blog
4. **Serializable Snapshot Isolation in PostgreSQL** (basis cho CRDB approach)
5. **The CalvinDB Paper** - Deterministic distributed transactions

---

*Document này tập trung vào bản chất cơ chế và trade-offs. Để implementation details, tham khảo official documentation của từng hệ thống.*
