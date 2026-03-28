# Cassandra - Wide-Column Store, Tunable Consistency, LSM Tree

## 1. Mục tiêu của Task

Hiểu sâu bản chất Cassandra như một distributed wide-column store, phân tích:
- Kiến trúc LSM Tree và cách nó định hình performance characteristics
- Cơ chế tunable consistency (từ Eventually Consistent đến Strong Consistent)
- Trade-offs giữa write-heavy vs read-heavy workloads
- Production concerns: compaction, repair, partition sizing, anti-patterns

## 2. Bản chất và Cơ chế Hoạt động

### 2.1 Wide-Column Store - Khác biệt căn bản

Cassandra không phải "wide-column" theo nghĩa đơn giản (như Excel). Đây là **sparse column-family store** với đặc điểm:

| Khái niệm | Giải thích | Ý nghĩa thiết kế |
|-----------|-----------|------------------|
| **Row Key** | Partition key xác định node chứa data | Data locality trên single node |
| **Column Family** | Tập hợp các cột liên quan | Schema flexible, add column on-the-fly |
| **Wide Row** | Một partition chứa hàng triệu columns | Time-series data, event streams |
| **Cell** | Atomic unit (value + timestamp) | Conflict resolution dựa trên timestamp |

> **Quan trọng**: Cassandra lưu data theo **partition** (row key), không phải theo row như RDBMS. Một partition có thể chứa hàng triệu "columns" (thực chất là key-value pairs với timestamp).

**Thiết kế vật lý:**
```
Partition Key → [Column1: Value@TS] [Column2: Value@TS] ... [ColumnN: Value@TS]
```

Điều này khác hoàn toàn RDBMS:
- RDBMS: Row-oriented, fixed schema, normalized
- Cassandra: Partition-oriented, dynamic columns, denormalized by design

### 2.2 LSM Tree - Write-Optimized Storage Engine

Cassandra sử dụng **Log-Structured Merge Tree** - kiến trúc write-optimized thay vì B-Tree read-optimized.

#### Cấu trúc LSM Tree

```
┌─────────────────────────────────────────────────────────────┐
│                     WRITE PATH                              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌──────────┐    ┌─────────────┐    ┌──────────────────┐   │
│  │  Client  │───→│ Commit Log  │───→│  MemTable (RAM)  │   │
│  └──────────┘    │  (WAL)      │    └──────────────────┘   │
│                  └─────────────┘              │            │
│                                               ↓            │
│                              ┌──────────────────────────┐  │
│                              │  Flush to SSTable (disk) │  │
│                              └──────────────────────────┘  │
│                                             │              │
└─────────────────────────────────────────────┼──────────────┘
                                              ↓
┌─────────────────────────────────────────────────────────────┐
│                     STORAGE LAYERS                          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   Level 0 (MemTable): C0 - In-memory, sorted, mutable       │
│                    │                                        │
│                    ↓ Flush (threshold: memtable_cleanup_    │
│   Level 1 (SSTable): C1 - On-disk, immutable, sorted       │
│                    │        (tạo ra SSTable mới)            │
│                    ↓ Compaction (SizeTiered/Leveled/TWCS)   │
│   Level 2..N: C2..Cn - SSTables được merge, deduplicate    │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

#### Write Path chi tiết

1. **Ghi vào Commit Log (WAL)**
   - Append-only, sequential I/O
   - Nhanh nhất có thể (bypass filesystem cache, O_DIRECT)
   - Chỉ để recovery sau crash

2. **Ghi vào MemTable**
   - In-memory sorted structure (ConcurrentSkipListMap)
   - Sorted by (Partition Key → Clustering Key → Column)
   - Khi đạt threshold (default 1.6GB), flush xuống SSTable

3. **Flush thành SSTable**
   - Immutable file trên disk
   - Chứa: Data file + Index file + Filter file (Bloom filter)
   - Sorted, nén (compression), không bao giờ modify

> **Bản chất**: SSTable = **S**orted **S**tring **Table** (thuật ngữ từ LevelDB/Bigtable). Immutable, sorted, dễ compress.

#### Read Path và Complexity

```
Read Request
     │
     ├─→ Check Row Cache (rarely hit)
     │
     ├─→ Check MemTable (O(log n))
     │
     ├─→ Check Bloom Filters (O(1), false positive rate ~1%)
     │
     └─→ Read từ SSTables (N file cần check)
              │
              ├─→ Binary search trên Index
              ├─→ Seek đến offset trong Data file
              └─→ Merge kết quả từ nhiều SSTables
```

**Vấn đề cốt lõi**: Read amplification
- 1 read có thể phải check nhiều SSTables
- Mỗi SSTable có thể chứa version cũ của data
- Phải merge và resolve conflicts bằng timestamp

**Giải pháp**: Compaction - merge SSTables để giảm số lượng file

### 2.3 Compaction Strategies - Trade-off Central

Đây là **decision quan trọng nhất** khi triển khai Cassandra. Ba chiến lược chính:

| Strategy | Khi nào dùng | Write Amplification | Read Amplification | Space Amplification |
|----------|-------------|---------------------|-------------------|---------------------|
| **SizeTiered** (Default) | Write-heavy, general purpose | Thấp | Cao | Cao |
| **Leveled** | Read-heavy, range scans | Cao | Thấp | Thấp |
| **TimeWindow** (TWCS) | Time-series, TTL data | Thấp | Thấp | Thấp |

#### SizeTiered Compaction (STCS)

```
┌─────────────────────────────────────────────────────────┐
│  Trigger: Khi có N SSTables cùng size tier              │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Tier 0: [4MB] [4MB] [4MB] [4MB]  → Merge thành [16MB] │
│  Tier 1: [16MB] [16MB] [16MB] [16MB] → Merge [64MB]     │
│  Tier 2: [64MB] [64MB] [64MB] → Merge [192MB]           │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**Pros:**
- Write amplification thấp: mỗi dòng được viết 1-2 lần
- Sequential I/O khi compact (giống copy file)

**Cons:**
- Read amplification cao: nhiều SSTables cần check
- Space amplification cao: có thể cần 50% extra space trong compaction
- Read latency không ổn định (spike khi compact)

**Production concern:**
- Không nên dùng cho data không có TTL
- Không nên dùng khi read-heavy
- `min_threshold` và `max_threshold` cần tune cẩn thận

#### Leveled Compaction (LCS)

```
┌─────────────────────────────────────────────────────────┐
│  Fixed-size levels, mỗi level 10x size level trước      │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Level 0: MemTable flush → [SSTables không overlap]     │
│  Level 1: [10MB] [10MB] [10MB] ... (tối đa 10 SSTables) │
│  Level 2: [100MB] ... (tối đa 100 SSTables)             │
│  Level 3: [1GB] ...                                     │
│                                                         │
│  Level n chỉ chứa data overlap với Level n+1            │
└─────────────────────────────────────────────────────────┘
```

**Pros:**
- Read amplification thấp: chỉ cần check 1 SSTable/level
- Space amplification thấp: ~10% extra space
- Read latency ổn định

**Cons:**
- Write amplification cao: 10-30x (mỗi write có thể cascade qua nhiều levels)
- Compaction I/O overhead cao

**Khi nào dùng:**
- Read-heavy workloads (>90% reads)
- Range scan queries nhiều
- Không có TTL

#### TimeWindow Compaction (TWCS)

```
┌─────────────────────────────────────────────────────────┐
│  Compaction dựa trên time window                        │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Window 1h: [00:00-01:00] → SSTable 1                   │
│  Window 2h: [01:00-02:00] → SSTable 2                   │
│  Window 3h: [02:00-03:00] → SSTable 3                   │
│                                                         │
│  SSTables trong cùng window được compact                │
│  SSTables khác window không bao giờ compact cùng nhau   │
└─────────────────────────────────────────────────────────┘
```

**Pros:**
- Hoàn hảo cho time-series data
- Efficient TTL expiration (có thể drop cả SSTable)
- Read/Write amplification đều thấp

**Cons:**
- Chỉ dùng được khi có time-based clustering key
- Không tốt cho updates (data nằm ở nhiều windows)

**Best practice:**
- Set `compaction_window_unit` và `compaction_window_size` khớp với TTL
- Ví dụ: TTL 30 days → window size 1 day

### 2.4 Tunable Consistency - Linh hoạt trong CAP

Cassandra cho phép điều chỉnh Consistency Level (CL) **per-query**, tạo nên sự linh hoạt độc đáo.

#### Consistency Levels

| CL | Write | Read | Ý nghĩa | Khi nào dùng |
|----|-------|------|---------|--------------|
| **ANY** | 1 node (có thể hint) | - | Write nhanh nhất | Logging, metrics |
| **ONE** | 1 replica | 1 replica | Low latency | Cache, non-critical |
| **TWO** | 2 replicas | 2 replicas | Medium | Balanced |
| **QUORUM** | RF/2+1 | RF/2+1 | Strong consistency | Default cho production |
| **ALL** | Tất cả replicas | Tất cả replicas | Highest consistency | Critical data |
| **LOCAL_QUORUM** | Quorum trong DC local | Quorum trong DC local | Cross-DC balance | Multi-DC default |
| **EACH_QUORUM** | Quorum mỗi DC | Quorum mỗi DC | Strong cross-DC | Financial data |

#### Cơ chế Read Repair

```
┌─────────────────────────────────────────────────────────┐
│  Read với CL.QUORUM (RF=3)                              │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Coordinator ──→ Replica 1 (value=v1, ts=100)           │
│         │                                          │    │
│         ├──→ Replica 2 (value=v2, ts=200) ←── Mới nhất  │
│         │                                          │    │
│         └──→ Replica 3 (value=v1, ts=100)               │
│                                                         │
│  1. Đợi 2 replicas trả về (QUORUM = 2)                  │
│  2. So sánh timestamp                                   │
│  3. Return giá trị mới nhất (v2)                        │
│  4. Background repair: update replicas cũ (v1→v2)       │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**Read Repair**: Tự động sửa inconsistency trong lúc read
- Đắt: phải đọc từ nhiều replicas
- Chỉ chạy khi phát hiện mismatch
- Có thể disable cho read-heavy workloads

#### Hinted Handoff - Write availability

Khi replica down, coordinator lưu "hint" và replay sau:
- Tăng write availability
- Có thể gây hot spots nếu nhiều node down
- Hinted data có thể expire

#### Lightweight Transactions (LWT) - Compare-and-Set

Paxos-based consensus cho compare-and-set operations:

```sql
-- LWT với IF NOT EXISTS (INSERT nếu chưa tồn tại)
INSERT INTO users (id, email) VALUES (1, 'a@b.com')
IF NOT EXISTS;

-- LWT với IF (UPDATE nếu điều kiện đúng)
UPDATE accounts SET balance = 100
WHERE id = 1
IF balance = 200;
```

**Trade-off:**
- 4 round-trips (prepare → propose → commit)
- Significantly slower (~10x regular write)
- Chỉ dùng khi thực sự cần linearizable consistency

> **Quy tắc**: Tránh LWT. Nếu cần frequent LWT, reconsider data model hoặc chọn database khác.

## 3. Kiến trúc và Luồng Xử lý

### 3.1 Distributed Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Cassandra Cluster                         │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐     │
│  │   Node 1    │◄──→│   Node 2    │◄──→│   Node 3    │     │
│  │  (Token     │    │  (Token     │    │  (Token     │     │
│  │   Range A)  │    │   Range B)  │    │   Range C)  │     │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘     │
│         │                  │                  │            │
│         └──────────────────┼──────────────────┘            │
│                            │                               │
│                    Gossip Protocol                         │
│              (Node discovery & failure detection)          │
└─────────────────────────────────────────────────────────────┘
```

**Token Ring & Partitioning**
- Consistent hashing: MD5/Murmur3 partition key → token (64-bit)
- Token range được chia cho các nodes
- Virtual nodes (vnodes): mỗi physical node có nhiều token ranges (default 256)

**Replication Factor (RF)**
- RF=3: mỗi partition lưu trên 3 nodes
- Rack-aware và DC-aware replica placement

### 3.2 Coordinator Flow

```
Client Request
      │
      ↓
┌─────────────┐
│ Coordinator │──→ 1. Tính token từ partition key
│  (Random    │──→ 2. Xác định replica nodes
│   hoặc     │──→ 3. Forward request
│  Stickiness)│
└──────┬──────┘
       │
       ├──→ Replica 1 (primary)
       ├──→ Replica 2
       └──→ Replica 3

       ↓
   Đợi CL nodes respond
       ↓
   Return kết quả client
```

**Coordinator responsibilities:**
- Điều phối request đến replicas
- Thực hiện read repair nếu cần
- Trả về result sau khi đạt CL

## 4. So sánh với các lựa chọn khác

### Cassandra vs MongoDB vs DynamoDB

| Aspect | Cassandra | MongoDB | DynamoDB |
|--------|-----------|---------|----------|
| **Data Model** | Wide-column, partition-oriented | Document, rich query | Key-value + Document |
| **Scaling** | Linear scale-out | Scale-out (sharding) | Managed, auto-scale |
| **Consistency** | Tunable (ONE → ALL) | Strong (default) | Tunable |
| **Query** | Limited (partition key required) | Rich (secondary indexes) | Limited (GSIs costly) |
| **Best for** | Time-series, write-heavy, massive scale | General purpose, complex queries | AWS-native, variable load |
| **Ops overhead** | Cao (cần expertise) | Trung bình | Thấp (managed) |

### Khi nào chọn Cassandra

**Phù hợp:**
- Time-series data (metrics, logs, IoT)
- Write-heavy workloads (10:1 write:read)
- Global distribution, multi-DC
- Massive scale (TBs đến PBs)
- High availability yêu cầu (99.99%)

**Không phù hợp:**
- Complex queries, joins, aggregations
- Strong consistency requirements mặc định
- Small data (< 100GB)
- Ad-hoc analytics
- ACID transactions

## 5. Rủi ro, Anti-patterns, Lỗi thường gặp

### 5.1 Partition Key Anti-patterns

> **Unbounded partition growth**
```sql
-- ❌ SAI: Partition key không đổi, partition sẽ lớn vô hạn
CREATE TABLE events_by_type (
    type text,           -- Chỉ có vài giá trị: ERROR, INFO, WARN
    time timestamp,
    data text,
    PRIMARY KEY (type, time)
);
```

**Vấn đề:**
- Partition "ERROR" chứa tất cả error events
- SSTable lớn → read latency spike
- Repair và compaction chậm
- Memory pressure

**Giải pháp:**
```sql
-- ✅ ĐÚNG: Composite partition key
CREATE TABLE events_by_type_daily (
    type text,
    date text,           -- "2024-01-01"
    time timestamp,
    data text,
    PRIMARY KEY ((type, date), time)
);
```

### 5.2 Secondary Index Anti-pattern

```sql
-- ❌ SAI: Secondary index trên high-cardinality column
CREATE INDEX ON users(email);  -- Mỗi user có email khác nhau
```

**Vấn đề:**
- Index query phải contact **tất cả nodes**
- Coordinator bottleneck
- Query latency ~O(n nodes)

**Giải pháp:**
```sql
-- ✅ ĐÚNG: Materialized View hoặc denormalized table
CREATE TABLE users_by_email (
    email text PRIMARY KEY,
    user_id uuid
);
```

### 5.3 Read-before-Write Anti-pattern

```java
// ❌ SAI: Read trước khi update
Row existing = session.execute(
    "SELECT * FROM counters WHERE id = ?", id
).one();

int newValue = existing.getInt("value") + 1;
session.execute(
    "UPDATE counters SET value = ? WHERE id = ?",
    newValue, id
);
```

**Vấn đề:**
- Race condition: read và write không atomic
- Lost update trong concurrent environment
- Double round-trip

**Giải pháp:**
```sql
-- ✅ ĐÚNG: Counter columns (nhưng có limitations)
UPDATE counters SET value = value + 1 WHERE id = ?;
```

### 5.4 ALLOW FILTERING Anti-pattern

```sql
-- ❌ SAI: ALLOW FILTERING scan toàn bộ partition
SELECT * FROM events 
WHERE type = 'ERROR' 
  AND data CONTAINS 'timeout' 
ALLOW FILTERING;
```

**Vấn đề:**
- Scan toàn bộ partition trong memory
- OOM risk
- Timeout

### 5.5 Large Row / Wide Partition

**Symptoms:**
- `nodetool tpstats` showing high pending compactions
- Read latency spikes
- GC pressure

**Metrics cần monitor:**
```bash
# Partition size
nodetool tablestats keyspace.table | grep "Maximum partition size"

# Số partitions
nodetool tablestats keyspace.table | grep "Number of partitions"
```

**Rule of thumb:**
- Partition size < 100MB (ideally < 10MB)
- Số cells per partition < 100,000

## 6. Khuyến nghị Production

### 6.1 JVM Tuning

```bash
# G1GC cho Cassandra (không dùng ZGC vì không support large heap tốt)
-XX:+UseG1GC
-Xms31G -Xmx31G  # Heap < 32GB để compressed OOPs
-XX:MaxGCPauseMillis=200

# GC logging
-Xlog:gc*:file=/var/log/cassandra/gc.log:time,uptime:filecount=10,filesize=10m
```

### 6.2 Monitoring Essentials

| Metric | Alert Threshold | Ý nghĩa |
|--------|-----------------|---------|
| Pending compactions | > 20 | Compaction không kịp |
| Read latency p99 | > 50ms | Performance degradation |
| Write latency p99 | > 10ms | Disk/flush bottleneck |
| SSTable count | > 50 per read | Read amplification cao |
| Repair sessions failed | > 0 | Data inconsistency |
| Nodetool status DOWN | Any node | Node failure |

### 6.3 Backup Strategy

**Snapshot-based:**
```bash
# Tạo snapshot (hard links, nhanh)
nodetool snapshot keyspace_name

# Backup incremental: commit logs
# Enable incremental backups trong cassandra.yaml
incremental_backups: true
```

### 6.4 Repair Strategy

```bash
# Incremental repair (Cassandra 3.0+)
nodetool repair -pr --full  # Primary range only

# Hoặc dùng repair scheduler:
# - Repairs chia nhỏ theo subranges
# - Chạy trong maintenance window
# - Theo dõi pending repairs
```

### 6.5 Security

```yaml
# cassandra.yaml
cassandra.yaml
  authenticator: PasswordAuthenticator
  authorizer: CassandraAuthorizer
  client_encryption_options:
    enabled: true
    optional: false
    keystore: conf/.keystore
    keystore_password: cassandra
```

### 6.6 Data Modeling Best Practices

1. **Query-first design**: Xác định queries trước, thiết kế tables sau
2. **Denormalization là norm**: Một query = một table
3. **Avoid updates và deletes**: Dùng TTL, hoặc append-only
4. **Batch statements**: Chỉ dùng cho partition cùng key
5. **Prepared statements**: Luôn dùng để tránh parsing overhead

## 7. Kết luận

**Bản chất của Cassandra:**

1. **Log-Structured Merge Tree** là cốt lõi - trade-off ghi nhanh đọc chậm, giải quyết bằng compaction
2. **Tunable consistency** - linh hoạt trong CAP, từ eventual đến strong per-query
3. **Wide-column model** - partition-oriented, không phải row-oriented
4. **Distributed by design** - không có single point of failure

**Trade-off quan trọng nhất:**
- **Write path**: Nhanh (sequential I/O, append-only)
- **Read path**: Chậm hơn (merge từ nhiều SSTables)
- **Compaction**: Chi phí nền để duy trì read performance

**Rủi ro production lớn nhất:**
1. Partition key thiết kế sai → unbounded partitions
2. Secondary index lạm dụng → coordinator bottleneck
3. Compaction không kịp → read latency spike
4. Repair không chạy → data inconsistency

**Khi nào nên dùng:**
- Time-series, metrics, IoT, logs
- Write-heavy, multi-DC, massive scale
- Chấp nhận eventual consistency

**Khi nào KHÔNG nên dùng:**
- Cần complex queries, joins
- Cần ACID transactions
- Small dataset, single DC
- Ad-hoc analytics

---

*Cassandra là công cụ mạnh nhưng đòi hỏi expertise. Thiết kế data model đúng là 80% thành công.*
