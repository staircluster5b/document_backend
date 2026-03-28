# Apache Kafka - Architecture, Partitioning, Replication, Consumer Groups

## 1. Mục tiêu của Task

Hiểu sâu bản chất của Apache Kafka như một distributed commit log system: cách dữ liệu được lưu trữ vật lý, cơ chế partition và replication đảm bảo throughput và fault tolerance, cùng với consumer group coordination - những kiến thức cốt lõi để thiết kế và vận hành hệ thống streaming production.

---

## 2. Bản chất và Cơ chế Hoạt động

### 2.1. Kafka là Distributed Commit Log, không chỉ là Message Queue

> **Cốt lõi**: Kafka không "lưu trữ message để consumer lấy đi". Kafka **append message vào log và giữ nguyên** - consumer tự theo dõi offset của mình.

Sự khác biệt căn bản so với traditional message queue:

| Đặc điểm | Traditional MQ (RabbitMQ) | Apache Kafka |
|----------|---------------------------|--------------|
| **Mô hình lưu trữ** | Queue - message bị xóa sau khi consumed | Log - message được giữ, consumer tự quản lý offset |
| **Consumer pattern** | Push-based, competitive consumption | Pull-based, consumer chủ động fetch |
| **Retention** | Xóa ngay khi acknowledged | Cấu hình retention (time/size-based) |
| **Replay capability** | Không | Có thể replay từ bất kỳ offset nào |
| **Throughput** | ~10-50K msg/s | ~1M+ msg/s trên commodity hardware |

**Bản chất vật lý**: Kafka lưu message như **append-only log files trên disk**, không phải in-memory queue. Đây là lý do Kafka đạt throughput cao - sequential disk I/O nhanh hơn random memory access trong nhiều trường hợp.

---

### 2.2. Log Storage Internals - Segment Architecture

#### Physical Storage Structure

```
/topic-foo-partition-0/
├── 00000000000000000000.log      # Segment file chứa messages
├── 00000000000000000000.index    # Offset → physical position mapping
├── 00000000000000000000.timeindex # Timestamp → offset mapping
├── 00000000000000345192.log      # Segment tiếp theo (khi đủ 1GB)
├── 00000000000000345192.index
├── 00000000000000345192.timeindex
└── leader-epoch-checkpoint       # Leader epoch tracking cho recovery
```

**Segment là gì?**
- Mỗi partition được chia thành nhiều **segment files** (mặc định 1GB/log.segment.bytes)
- Chỉ có **active segment** (segment cuối) mới ghi thêm được
- Các segment cũ là immutable - chỉ đọc, không sửa

> **Tại sao chia segment?**
> 1. **Efficient deletion**: Retention xóa cả segment cũ, không cần xóa từng message
> 2. **Index size control**: Index cho mỗi segment nhỏ, vừa memory
> 3. **Compaction efficiency**: Chỉ compact segment cũ, không ảnh hưởng ghi mới

#### Message Format (Record Batch)

```
┌─────────────────────────────────────────────────────────────┐
│ Record Batch Header                                         │
│ - Base Offset: offset đầu tiên trong batch                  │
│ - Batch Length: kích thước toàn bộ batch                    │
│ - Partition Leader Epoch: để phát hiện divergent replica    │
│ - CRC32: checksum toàn bộ batch                             │
├─────────────────────────────────────────────────────────────┤
│ Records[]                                                   │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ Record                                                  │ │
│ │ - Length, Attributes, Timestamp, Offset Delta           │ │
│ │ - Key Length, Key, Value Length, Value                  │ │
│ │ - Headers[]                                             │ │
│ └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

> **Quan trọng**: Kafka 0.11+ dùng **Record Batch** - nhiều messages gộp thành 1 batch. Đây là yếu tố then chốt cho throughput cao: giảm syscall, tối ưu compression, giảm metadata overhead.

#### Index Mechanism - O(1) Offset Lookup

Kafka không scan toàn bộ log để tìm message. Thay vào đó:

**Offset Index** (sparse index):
- Mỗi entry: `(relativeOffset → physicalPosition)`
- Ghi mỗi `log.index.interval.bytes` (default 4KB)
- Memory-mapped file cho fast lookup

**Tìm offset = 12345**:
1. Binary search trên index tìm entry gần nhất ≤ 12345
2. Seek đến physical position tương ứng
3. Scan từ đó trong log file (tối đa 4KB)

> **Time-based lookup**: `timeindex` map `timestamp → offset`, cho phép `offsetsForTimes()` API - critical cho replay và recovery.

---

## 3. Partitioning - Cơ chế Phân phối và Parallelism

### 3.1. Partition là Unit of Parallelism

```
┌─────────────────────────────────────────────────────────────┐
│                    Topic: user-events                       │
├─────────────────────────────────────────────────────────────┤
│ Partition 0  │  Partition 1  │  Partition 2  │  Partition 3 │
│  P0-leader   │   P1-leader   │   P2-leader   │   P3-leader  │
│    [0-999]   │  [1000-1999]  │  [2000-2999]  │  [3000-...]  │
└─────────────────────────────────────────────────────────────┘
```

**Cốt lõi**:
- **Message trong cùng partition được đảm bảo ordering**
- **Message khác partition không có ordering guarantee**
- Số partition = max parallelism cho consumers (1 consumer/partition tối đa)

### 3.2. Partitioning Strategies

#### Default Partitioner (Sticky with Key Hash)

```
if (record.key() == null):
    // Sticky partitioning - batch messages vào 1 partition
    // cho đến khi batch full hoặc timeout
    return stickyPartition()
else:
    // Murmur2 hash của key % numPartitions
    return Utils.murmur2(keyBytes) % numPartitions
```

| Strategy | Use Case | Trade-off |
|----------|----------|-----------|
| **Key-based** | Ordering cho cùng entity (userId, orderId) | Hot partition risk |
| **Round-robin** | Cân bằng load, không cần ordering | Mất ordering guarantee |
| **Custom** | Business logic (geography, tenant) | Complexity, cần hiểu data distribution |

> **Anti-pattern**: Hash bằng `key.hashCode()` - Java hashCode không consistent cross-platform, dẫn đến partition mismatch giữa Java và non-Java producers.

### 3.3. Partition Count - Trade-off Analysis

| Yếu tố | Partition thấp | Partition cao |
|--------|----------------|---------------|
| **Throughput** | Giới hạn parallelism | Tối đa parallelism |
| **Ordering** | Tập trung, dễ quản lý | Phân tán, khó đảm bảo |
| **Consumer scaling** | Giới hạn | Linh hoạt |
| **Replication lag** | Thấp | Cao (nhiều fetcher threads) |
| **Broker memory** | Ít | Nhiều (mỗi partition ~MB overhead) |
| **Recovery time** | Nhanh | Chậm (nhiều segment files) |
| **ZooKeeper/KRaft load** | Thấp | Cao |

**Rule of thumb**:
- Start với: `max(expected throughput / consumer throughput, expected consumers)`
- Hard limit: Kafka 3.x khuyến nghị < 4,000 partitions/broker, < 200K partitions/cluster

> **Không thể giảm partition count** - chỉ có thể tăng. Plan carefully!

---

## 4. Replication - High Availability và Durability

### 4.1. Replication Architecture

```
┌───────────────────────────────────────────────────────────────┐
│                    Partition 0 (3 replicas)                   │
├───────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────┐         ┌──────────────┐         ┌─────────┐│
│  │   Leader     │◄────────│  Follower 1  │◄────────│Follower2││
│  │  (Broker 1)  │  Fetch  │  (Broker 2)  │  Fetch  │(Broker3)││
│  │              │         │              │         │         ││
│  │  HW = 100    │         │  HW = 98     │         │ HW = 97 ││
│  │  LEO = 102   │         │  LEO = 100   │         │ LEO = 98││
│  └──────────────┘         └──────────────┘         └─────────┘│
│         │                                                     │
│         ▼                                                     │
│    Producer writes                                            │
└───────────────────────────────────────────────────────────────┘
```

**Cốt lõi**:
- Mỗi partition có 1 **Leader** và n-1 **Followers** (ISR - In-Sync Replicas)
- Chỉ Leader nhận write từ producer và read từ consumer
- Followers pull data từ leader (asynchronous replication)

#### Critical Concepts: LEO và HW

| Metric | Ý nghĩa | Vai trò |
|--------|---------|---------|
| **LEO** (Log End Offset) | Offset cao nhất + 1 của replica | Theo dõi tiến độ replication |
| **HW** (High Watermark) | Offset cao nhất đã replicated đến **tất cả ISR** | Consumer chỉ đọc đến HW |

> **Guarantee**: Message chưa đến HW có thể mất nếu leader crash. Message đã đến HW được đảm bảo durability (với acks=all).

### 4.2. Producer Acknowledgment Levels

```
┌─────────────────────────────────────────────────────────────┐
│  acks=0 (Fire and Forget)                                   │
│  └── Producer không đợi acknowledgment                     │
│  └── Throughput max, durability min                        │
│  └── Không thể biết message có đến broker không            │
├─────────────────────────────────────────────────────────────┤
│  acks=1 (Leader Ack)                                        │
│  └── Đợi leader ghi vào local log                          │
│  └── Balance giữa throughput và durability                 │
│  └── Risk: Leader crash trước khi followers replicate      │
├─────────────────────────────────────────────────────────────┤
│  acks=all (ISR Ack)                                         │
│  └── Đợi tất cả ISR replicas ghi xong                      │
│  └── Durability max, throughput thấp nhất                  │
│  └── min.insync.replicas điều khiển số replica tối thiểu   │
└─────────────────────────────────────────────────────────────┘
```

> **Production recommendation**:
> - Critical data (payments, orders): `acks=all`, `min.insync.replicas=2`, `replication.factor=3`
> - Metrics/logs: `acks=1` hoặc `acks=0`

### 4.3. Leader Election và Failover

**Khi leader crash**:
1. Controller broker phát hiện (qua ZooKeeper/KRaft session timeout)
2. Chọn follower có **ISR** và **highest LEO** làm leader mới
3. Update metadata, notify producers/consumers

**Unclean Leader Election** (controlled by `unclean.leader.election.enable`):
- **Disabled** (default, recommended): Chỉ chọn leader từ ISR → no data loss, nhưng unavailable nếu ISR rỗng
- **Enabled**: Chọn leader từ out-of-sync replicas → risk mất data, nhưng có availability

> **Trade-off**: Consistency vs Availability. Financial systems: disable. Analytics systems: có thể enable.

### 4.4. Replica Fetcher Mechanism

```
┌────────────────────────────────────────────────────────────┐
│                  Follower Fetch Request                    │
├────────────────────────────────────────────────────────────┤
│ FetchOffset: LEO của follower                              │
│ MaxBytes: fetch.max.bytes (default 52MB)                   │
│ MinBytes: fetch.min.bytes (default 1 byte)                 │
│ MaxWait: fetch.max.wait.ms (default 500ms)                 │
└────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────┐
│ Leader Response                                            │
├────────────────────────────────────────────────────────────┤
│ - Trả về records từ FetchOffset đến HW                     │
│ - Nếu không đủ minBytes, wait đến maxWait                  │
│ - Kèm theo HW mới nhất                                     │
└────────────────────────────────────────────────────────────┘
```

> **Tuning replica lag**: `replica.lag.time.max.ms` (default 30s). Follower không fetch trong khoảng này bị loại khỏi ISR → ảnh hưởng durability với acks=all.

---

## 5. Consumer Groups - Coordination và Rebalancing

### 5.1. Consumer Group Mechanics

```
┌─────────────────────────────────────────────────────────────┐
│ Consumer Group: order-processor-group                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Partition 0  ←──────── Consumer 1                          │
│  Partition 1  ←──────── Consumer 2                          │
│  Partition 2  ←──────── Consumer 3                          │
│  Partition 3  ←──────── Consumer 4                          │
│                                                             │
│  (4 partitions, 4 consumers = 1:1 mapping)                  │
└─────────────────────────────────────────────────────────────┘
```

**Cốt lõi**:
- Mỗi partition chỉ được assign cho **1 consumer duy nhất** trong group
- 1 consumer có thể giữ **nhiều partitions**
- Số consumers > partitions: thừa consumers idle
- Số consumers < partitions: một số consumers process nhiều partitions

### 5.2. Group Coordination Protocol

#### Heartbeat Mechanism

```
┌──────────────┐         Heartbeat         ┌──────────────┐
│   Consumer   │ ◄────────────────────────► │ Group        │
│              │  session.timeout.ms        │ Coordinator  │
│              │  heartbeat.interval.ms     │ (1 broker)   │
└──────────────┘                            └──────────────┘
```

| Config | Default | Ý nghĩa |
|--------|---------|---------|
| `session.timeout.ms` | 45s | Consumer không heartbeat trong khoảng này → considered dead |
| `heartbeat.interval.ms` | 3s | Tần suất gửi heartbeat |
| `max.poll.interval.ms` | 5m | Thời gian tối đa giữa 2 lần `poll()` |

> **Critical**: `max.poll.interval.ms` phải > processing time của message batch. Nếu process chậm, consumer bị remove khỏi group dù vẫn heartbeat!

#### Offset Management

**Committed Offset** là vị trí consumer đã xử lý xong:
- Stored tại `__consumer_offsets` topic (internal, compacted)
- 50 partitions, replication factor 3

**Commit strategies**:

| Strategy | Implementation | Trade-off |
|----------|----------------|-----------|
| **Auto commit** | `enable.auto.commit=true` | Dễ dùng, risk duplicate/miss processing |
| **Sync manual** | `commitSync()` | Reliable, block until success/fail |
| **Async manual** | `commitAsync()` | Non-blocking, risk commit out-of-order |
| **Transaction** | `sendOffsetsToTransaction()` | Exactly-once semantics (Kafka 0.11+) |

> **Best practice**: Manual commit với `commitSync()` sau khi process batch xong. Chỉ auto commit cho non-critical data.

### 5.3. Rebalancing - The Nightmare

**Khi nào rebalance xảy ra**:
- Consumer join/leave group
- New partition added
- Coordinator failure

**Rebalance process** (Eager protocol - default):
1. Consumers stop consuming
2. Revoke all partitions
3. Coordinator chọn partition assignment strategy
4. Phân chia partitions lại
5. Consumers resume

> **Problem**: "Stop the world" - không consume trong quá trình rebalance. Với nhiều consumers/partitions, có thể mất vài giây.

#### Rebalance Strategies

| Strategy | Algorithm | Best for |
|----------|-----------|----------|
| **Range** (default) | Chia đều theo range partition | Few topics, nhiều consumers |
| **RoundRobin** | Lần lượt gán | Nhiều topics, consumers đồng đều |
| **Sticky** | Giữ assignment cũ, chỉ thay đổi khi cần | Minimize partition movement |
| **Cooperative** (Kafka 2.4+) | Incremental rebalance - không revoke all | Large consumer groups |

> **Kafka 2.4+**: `partition.assignment.strategy=org.apache.kafka.clients.consumer.CooperativeStickyAssignor` - giảm rebalance time đáng kể cho large groups.

### 5.4. Static Membership (Kafka 2.3+)

```
// Gán consumer.instance.id cố định
props.put("group.instance.id", "consumer-1");
```

Khi consumer với `group.instance.id` restart:
- Coordinator nhận ra là "rejoin" không phải "new member"
- **Không rebalance** - giữ nguyên partition assignment
- Chỉ khi `session.timeout.ms` expired mới trigger rebalance

> **Use case**: Cloud-native deployments, frequent restarts. Giảm rebalance storm khi rolling restart.

---

## 6. Rủi ro, Anti-patterns và Lỗi Thường gặp

### 6.1. Producer Side

| Anti-pattern | Hậu quả | Solution |
|--------------|---------|----------|
| **Dùng default partitioner với null key** | Uneven distribution, hot partition | Dùng key hoặc custom partitioner |
| **acks=0 cho critical data** | Silent data loss | acks=all cho important messages |
| **Không handle timeout/retries** | Message loss | Cấu hình `retries`, `delivery.timeout.ms` |
| **Sync send mỗi message** | Throughput thấp | Batch messages, async send |
| **Không close producer** | Resource leak, message loss | Always use try-with-resources |

### 6.2. Consumer Side

| Anti-pattern | Hậu quả | Solution |
|--------------|---------|----------|
| **Process > max.poll.interval.ms** | Consumer bị kick, rebalance liên tục | Giảm `max.poll.records` hoặc tăng interval |
| **Auto commit với processing failure** | Message "đánh dấu xong" nhưng chưa process | Manual commit sau process thành công |
| **Blocking trong poll loop** | Heartbeat không gửi được, session timeout | Move blocking work ra thread pool |
| **Không handle RebalanceException** | Duplicate processing | Implement proper rebalancing logic |
| **Multi-threaded processing không đúng** | Race condition, offset commit sai | 1 thread poll, N threads process |

### 6.3. Operational Issues

| Issue | Nguyên nhân | Detection | Mitigation |
|-------|-------------|-----------|------------|
| **Consumer lag** | Process chậm hoặc under-scale | `kafka-consumer-groups.sh --describe` | Scale consumers, optimize processing |
| **Partition skew** | Key distribution uneven | Partition metrics | Salting key, custom partitioner |
| **Replication lag** | Network slow, broker overloaded | `UnderReplicatedPartitions` | Tăng bandwidth, scale brokers |
| **Disk full** | Retention quá dài, burst traffic | Disk monitoring | Adjust retention, add storage |
| **ZooKeeper session timeout** | GC pause, network issue | ZooKeeper logs | Tune JVM, network redundancy |

---

## 7. Khuyến nghị Thực chiến Production

### 7.1. Broker Configuration

```properties
# Replication và Durability
default.replication.factor=3
min.insync.replicas=2
unclean.leader.election.enable=false

# Retention (điều chỉnh theo use case)
log.retention.hours=168          # 7 ngày cho default
log.retention.bytes=107374182400 # 100GB per partition
log.segment.bytes=1073741824     # 1GB segments

# Performance
num.network.threads=3            # Tăng nếu nhiều connections
num.io.threads=8                 # Tăng theo disk count
log.flush.interval.messages=10000
log.flush.interval.ms=1000
```

### 7.2. Producer Configuration

```properties
# Critical data
acks=all
retries=2147483647               # Max int, effectively infinite
enable.idempotence=true          # Exactly-once semantics
delivery.timeout.ms=120000

# Batching (tune theo latency requirements)
batch.size=32768                 # 32KB
linger.ms=5                      # Wait up to 5ms to batch
compression.type=lz4             # Hoặc zstd cho better ratio
max.in.flight.requests=5         # Tăng throughput, vẫn đảm bảo ordering với idempotence
```

### 7.3. Consumer Configuration

```properties
# Reliability
enable.auto.commit=false
isolation.level=read_committed   # Chỉ đọc committed messages (transaction)

# Performance
max.poll.records=500             # Giảm nếu process chậm
fetch.min.bytes=1
fetch.max.wait.ms=500

# Static membership (Kafka 2.3+)
group.instance.id=${HOSTNAME}    # Static ID qua restarts
```

### 7.4. Monitoring Essentials

| Metric | Threshold | Action |
|--------|-----------|--------|
| `UnderReplicatedPartitions` | > 0 | Check broker/network health |
| `OfflinePartitions` | > 0 | Immediate intervention |
| `ActiveControllerCount` | != 1 | Controller election issue |
| Consumer Lag | > 10000 messages | Scale consumers hoặc investigate |
| Request Latency (p99) | > 100ms | Tune brokers, investigate GC |
| Disk Usage | > 85% | Add storage hoặc reduce retention |

---

## 8. Kết luận

### Bản chất cốt lõi của Kafka

1. **Log-centric, not queue-centric**: Kafka là distributed commit log - immutable, append-only, pull-based. Điều này cho phép replay, high throughput, và separation of producers/consumers.

2. **Partition = Unit of parallelism**: Ordering chỉ trong partition, throughput scale bằng cách tăng partitions. Partition count là quyết định kiến trúc quan trọng, không thể giảm.

3. **Replication theo pull model**: Followers pull từ leader, HW đảm bảo durability. Trade-off giữa acks level và latency/throughput.

4. **Consumer groups via coordination**: Group coordinator quản lý membership và offset. Rebalance là operational challenge cần minimize.

### Trade-off Summary

| Aspect | Option A | Option B | When to choose |
|--------|----------|----------|----------------|
| **Durability vs Latency** | acks=all | acks=1 | Critical data vs metrics |
| **Ordering vs Parallelism** | Few partitions | Many partitions | Business requirement |
| **Availability vs Consistency** | Unclean election | Clean election | Financial vs analytics |
| **Commit frequency** | Per-message | Batch | At-least-once vs duplicate risk |
| **Consumer scaling** | More partitions | Better processing | Long-term flexibility |

### Khi nào KHÔNG nên dùng Kafka

- **Message size > 1MB** (default limit, configurable nhưng không khuyến khích)
- **Require strict global ordering** (phải single partition → no parallelism)
- **Complex routing/filtering logic** (RabbitMQ exchanges phù hợp hơn)
- **Request-response pattern** (synchronous RPC)

Kafka là công cụ mạnh cho event streaming, nhưng đòi hỏi hiểu sâu operational characteristics để vận hành production hiệu quả.
