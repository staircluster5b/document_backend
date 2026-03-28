# Stream Processing - Kafka Streams, Flink, Stateful Operations

## 1. Mục tiêu của Task

Hiểu sâu bản chất của Stream Processing: tại sao cần stateful processing, cách các hệ thống xử lý state distributed, trade-off giữa Kafka Streams và Apache Flink, và các rủi ro production khi vận hành hệ thống stateful ở scale lớn.

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 Vấn đề cốt lõi: Stateful Stream Processing

Stream processing ban đầu chỉ là **stateless transformation**: map, filter, simple routing. Nhưng hầu hết use cases thực tế đều cần **state**:
- Aggregation (count, sum, average theo window)
- Join 2 streams (need to buffer records)
- Deduplication (need to track seen IDs)
- Sessionization (need to track user activity window)

**Vấn đề nan giải**: State trong stream processing là **unbounded và distributed**:
- Unbounded: Stream không kết thúc → state có thể grow vô hạn
- Distributed: Data partitioned across nodes → state cũng phải partitioned
- Fault-tolerance: Node crash → state phải recoverable

> **Insight**: Stateful stream processing = State management problem masquerading as a data processing problem.

### 2.2 Kiến trúc cơ bản

```
┌─────────────────────────────────────────────────────────────┐
│                    STREAM PROCESSING ENGINE                  │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │   Source    │→ │ Processing  │→ │       Sink          │  │
│  │  (Kafka/    │  │  (Operators)│  │   (Kafka/DB/        │  │
│  │   Pulsar)   │  │             │  │    Filesystem)      │  │
│  └─────────────┘  └──────┬──────┘  └─────────────────────┘  │
│                          │                                  │
│                          ↓                                  │
│                 ┌─────────────────┐                         │
│                 │  State Store    │                         │
│                 │  (RocksDB/      │                         │
│                 │   Heap/Memory)  │                         │
│                 └────────┬────────┘                         │
│                          │                                  │
│                 ┌────────▼────────┐                         │
│                 │  Checkpoint/    │                         │
│                 │  State Backend  │                         │
│                 │  (Kafka/DFS/    │                         │
│                 │   Object Store) │                         │
│                 └─────────────────┘                         │
└─────────────────────────────────────────────────────────────┘
```

### 2.3 Event Time vs Processing Time

| Dimension | Processing Time | Event Time |
|-----------|----------------|------------|
| **Definition** | Thờ điểm operator nhận record | Timestamp gốc của event |
| **Accuracy** | Bị ảnh hưởng bởi delay, network | Phản ánh reality chính xác |
| **Complexity** | Đơn giản | Cần watermark mechanism |
| **Use case** | Monitoring, alerting | Business metrics, billing |
| **Reordering** | Không xử lý được | Xử lý out-of-order events |

**Watermark**: Heuristic về "tính completeness" của stream.
```
Watermark(t) = "Tất cả events có timestamp ≤ t đã đến (hoặc sẽ không đến)"
```

Trade-off của watermark:
- **Quá nhanh**: Có thể drop late events (data loss)
- **Quá chậm**: Tăng latency của output

---

## 3. Kiến trúc chi tiết

### 3.1 Apache Flink Architecture

Flink được thiết kế từ đầu cho stateful processing với exactly-once semantics.

```
┌────────────────────────────────────────────────────────────┐
│                      Flink JobManager                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │  Dispatcher  │  │  Job Master  │  │ Resource Manager │  │
│  └──────────────┘  └──────────────┘  └──────────────────┘  │
└────────────────────┬───────────────────────────────────────┘
                     │
        ┌────────────┼────────────┐
        ↓            ↓            ↓
┌──────────────┐ ┌──────────┐ ┌──────────────┐
│ TaskManager  │ │TaskManager│ │ TaskManager  │
│ ┌──────────┐ │ │┌────────┐ │ │ ┌──────────┐ │
│ │ Slot 1   │ │ ││Slot 1│ │ │ │ │ Slot 1   │ │
│ │ ┌──────┐ │ │ │└──────┘ │ │ │ │ ┌──────┐ │
│ │ │Task A│ │ │ │  ...   │ │ │ │ │Task C│ │ │
│ │ └──┬───┘ │ │ │        │ │ │ │ └──┬───┘ │ │
│ │    │     │ │ │        │ │ │ │    │     │ │
│ │┌───▼───┐ │ │ │        │ │ │ │┌───▼───┐ │ │
│ ││ State │ │ │ │        │ │ │ ││ State │ │ │
│ ││(Local)│ │ │ │        │ │ │ │(Local)│ │ │
│ │└───────┘ │ │ │        │ │ │ │└───────┘ │ │
│ └──────────┘ │ └──────────┘ │ └──────────┘ │
└──────────────┘ └──────────────┘ └──────────────┘
```

**Checkpointing Mechanism (Chandy-Lamport)**:
1. JM trigger checkpoint barrier → broadcast to all sources
2. Sources snapshot state (offset) → emit barrier downstream
3. Operators nhận barrier → snapshot local state → forward barrier
4. Sink nhận barrier → confirm external write → checkpoint complete

**State Backends**:

| Backend | Storage | Use Case | Trade-off |
|---------|---------|----------|-----------|
| **MemoryStateBackend** | Heap memory | Testing, small state (<100MB) | Nhanh, không persistent |
| **FsStateBackend** | Heap + Checkpoint to FS | Medium state (<1GB) | Cân bằng speed/durability |
| **RocksDBStateBackend** | RocksDB (disk) | Large state (>1GB), incremental | Chậm hơn, scalable |

**Key insight**: RocksDB backend hỗ trợ **incremental checkpoint** - chỉ lưu SST files thay đổi, giảm network I/O đáng kể.

### 3.2 Kafka Streams Architecture

Kafka Streams embedded trong application - không cần cluster riêng.

```
┌─────────────────────────────────────────────────────────────┐
│                  Kafka Streams Application                   │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   ┌──────────────┐        ┌──────────────┐                 │
│   │  Stream 1    │───────→│              │                 │
│   │  (Input)     │        │   Topology   │                 │
│   └──────────────┘        │   (DSL/      │                 │
│                           │   Processor) │                 │
│   ┌──────────────┐        │              │                 │
│   │  Stream 2    │───────→│   ┌────────┐ │                 │
│   │  (Input)     │        │   │ State  │ │                 │
│   └──────────────┘        │   │ Store  │ │                 │
│                           │   │(Local) │ │                 │
│   ┌──────────────┐        │   └────────┘ │                 │
│   │  Changelog   │←───────│              │                 │
│   │  Topic       │        └──────┬───────┘                 │
│   └──────────────┘               │                         │
│                                  ↓                         │
│                           ┌──────────────┐                 │
│                           │    Sink      │                 │
│                           │   (Output)   │                 │
│                           └──────────────┘                 │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**State Store trong Kafka Streams**:
- Local state: RocksDB hoặc in-memory
- Changelog topic: Kafka topic dùng để recover state khi restart/rebalance
- Standby replicas: Optional, đọc changelog để có replica sẵn

**KStream vs KTable**:

| Aspect | KStream | KTable |
|--------|---------|--------|
| **Semantics** | Append-only stream | Upsert (updateable view) |
| **Analogy** | Event log | Materialized view |
| **Use case** | Raw events, logging | Aggregations, joins |
| **Log compaction** | Không | Có (topic backing) |
| **Processing** | Process từng record | Process key-based |

**Stream-Table Duality**: Một KTable có thể convert thành KStream (emit changes), và ngược lại (materialize stream thành table).

### 3.3 Stateful Operations

#### Windowing

| Window Type | Characteristics | Use Case |
|-------------|-----------------|----------|
| **Tumbling** | Fixed size, non-overlapping | Hourly metrics |
| **Sliding** | Fixed size, overlapping | Moving average |
| **Session** | Dynamic size, gap-based | User sessions |
| **Global** | All data in one window | Global aggregate |

**Window state management**:
- Window = key + time range + aggregate value
- Problem: Số lượng windows có thể rất lớn (sliding window mỗi 1ms)
- Optimization: Flink dùng **window merging** cho session windows

#### Joins

| Join Type | State Required | Complexity | Latency |
|-----------|---------------|------------|---------|
| **Stream-Stream** | Buffer cả 2 streams | High | Window-based |
| **Stream-Table** | Table state only | Medium | Real-time |
| **Table-Table** | Full table states | High | Materialized view |

**Stream-Stream Join** (most complex):
```
Stream A: ─────[a1]──────────[a2]───────►
Stream B: ───────────[b1]────────[b2]───►
                ↓           ↓
              Join(a1,b1) Join(a2,b2)
```
- Cần buffer records trong window
- Memory usage = rate × window size
- Watermark để trigger join và clean up

---

## 4. So sánh Kafka Streams vs Apache Flink

| Dimension | Kafka Streams | Apache Flink |
|-----------|---------------|--------------|
| **Architecture** | Embedded library | Distributed cluster |
| **Deployment** | App chạy standalone/YARN/K8s | Requires Flink cluster |
| **State size** | <10GB per instance | TB scale |
| **Latency** | Milliseconds (low) | Sub-second to minutes |
| **Throughput** | High (millions/sec) | Very high (billions/sec) |
| **Exactly-once** | Có (with EOS) | Có (2-phase commit) |
| **Event time** | Hỗ trợ cơ bản | Native, advanced |
| **Windowing** | Basic (tumbling, session) | Rich (custom, SQL) |
| **SQL support** | KSQL (separate) | Flink SQL (native) |
| **CEP** | Không có | Complex Event Processing |
| **Operational** | Đơn giản | Complex (JM/TM) |
| **Backpressure** | Implicit (Kafka lag) | Explicit (credit-based) |
| **Replay** | Native (seek offset) | Checkpoint-based |

### Khi nào chọn cái nào?

**Chọn Kafka Streams khi**:
- Use case đơn giản (aggregations, simple joins)
- Muốn operational simplicity (không cần vận hành cluster)
- State size nhỏ đến trung bình (<100GB total)
- Team đã familiar với Kafka ecosystem
- Cần embedded trong microservices

**Chọn Flink khi**:
- Cần complex event processing
- State size lớn (TB+)
- Cần advanced windowing/event time handling
- SQL-based processing requirements
- Cần high-throughput với exactly-once
- Có dedicated ops team

**Trade-off tóm lại**:
> Kafka Streams = Simplicity + Kafka-native, nhưng limited scale và features
> Flink = Power + Scale, nhưng operational overhead cao hơn

---

## 5. Rủi ro, Anti-patterns, và Lỗi thường gặp

### 5.1 Stateful Operations Risks

**1. State size explosion**
```
// ANTI-PATTERN: Unbounded state
groupByKey()
  .windowedBy(TimeWindows.of(Duration.ofDays(7)))
  .aggregate(...)  // State grow 7 days worth of data!
```
- **Impact**: OOM, GC pressure, checkpoint timeout
- **Solution**: Window compaction, TTL, incremental aggregation

**2. Hot partitions**
- Một key chiếm phần lớn traffic → một instance bị quá tải
- **Symptom**: Skewed lag, CPU/memory imbalance
- **Solution**: Key salting, repartition with random prefix

**3. Checkpoint failures**
- State quá lớn → checkpoint timeout
- **Impact**: Không recover được, data loss
- **Solution**: Incremental checkpoint, rocksdb tuning, async snapshots

### 5.2 Time and Watermark Issues

**Late events**:
- Event đến sau watermark → bị drop hoặc vào side output
- **Trade-off**: Accuracy vs Latency
- **Solution**: Allow late data with bounded delay, side output analysis

**Clock skew**:
- Producers có clock khác nhau → event time không monotonic
- **Impact**: Watermark không advance, windows không trigger
- **Solution**: Max allowed lateness, idempotent processing

### 5.3 Kafka Streams Specific

**Rebalance storms**:
- Consumer group rebalancing quá thường xuyên
- **Cause**: Processing time > max.poll.interval.ms
- **Impact**: Stop processing, state restore chậm
- **Solution**: Tăng max.poll.interval.ms, tối ưu processing logic

**Changelog topic bloat**:
- Changelog không được compact → disk full
- **Solution**: Enable compaction, set retention, monitor size

### 5.4 Flink Specific

**Backpressure cascading**:
- Một operator chậm → toàn pipeline chậm
- **Debugging**: Flink UI → Backpressure tab
- **Solution**: Scale bottleneck operator, tune buffer timeout

**Savepoint compatibility**:
- Code change không compatible → savepoint restore fail
- **Impact**: Không upgrade được job mất state
- **Solution**: State evolution strategies, schema registry

---

## 6. Khuyến nghị Production

### 6.1 Monitoring & Observability

**Metrics cần theo dõi**:

| Metric | Threshold | Action |
|--------|-----------|--------|
| **Consumer lag** | > 10,000 records | Scale up hoặc optimize |
| **Checkpoint duration** | > 90% of interval | Tune state backend |
| **State size** | > 80% allocated | Scale hoặc reduce state |
| **Watermark lag** | > 5 minutes | Check source delay |
| **GC time** | > 10% of runtime | Tune heap size |

**Tools**:
- Flink: Prometheus + Grafana, Flink UI
- Kafka Streams: Kafka Streams metrics export, Burrow for lag

### 6.2 State Management Best Practices

1. **State TTL**: Luôn set TTL cho state không cần giữ lâu
   ```java
   // Flink
   StateTtlConfig ttl = StateTtlConfig
       .newBuilder(Time.hours(24))
       .setUpdateType(UpdateType.OnCreateAndWrite)
       .setStateVisibility(StateVisibility.NeverReturnExpired)
       .build();
   ```

2. **State partitioning**: Design key để distribute đều
   ```java
   // GOOD: Distributed key
   String key = userId + ":" + (timestamp % 10);
   
   // BAD: Hot key
   String key = "GLOBAL";
   ```

3. **State cleanup**: Dùng timer hoặc window để clean state cũ

### 6.3 Deployment Patterns

**Kafka Streams**:
- Chạy multiple instances (containerized) với cùng application id
- Use sticky partitions (cooperative rebalancing)
- Deploy with rolling restart strategy

**Flink**:
- JobManager HA (multiple JM với ZooKeeper/K8s)
- TaskManager resource: 1 CPU per slot
- Checkpoint to distributed storage (S3, HDFS)
- Savepoint trước khi deploy

### 6.4 Disaster Recovery

| Scenario | Strategy | RTO | RPO |
|----------|----------|-----|-----|
| **Instance crash** | Automatic restart + state restore | Minutes | 0 (exactly-once) |
| **Code bug** | Rollback to savepoint | Minutes | Since last savepoint |
| **Region failure** | Multi-region replication | Hours | Minutes |
| **Data corruption** | Replay from source offset | Hours | Depends on retention |

---

## 7. Kết luận

**Bản chất của Stream Processing** là quản lý state distributed với fault-tolerance. Cả Kafka Streams và Flink đều giải quyết bài toán này nhưng với trade-off khác nhau:

- **Kafka Streams**: Đơn giản, embedded, phù hợp microservices pattern
- **Flink**: Mạnh mẽ, scalable, phù hợp complex analytics

**Key takeaways**:
1. State là "source of truth" - cần checkpoint và backup
2. Time semantics quan trọng hơn processing logic
3. Hot partitioning là enemy #1 của performance
4. Monitoring state metrics là critical
5. Có strategy cho state evolution và version migration

> **Final thought**: Stream processing không khó ở việc viết code - mà khó ở việc hiểu state behavior, time semantics, và failure modes. Invest time vào design và monitoring hơn là implementation.

---

## 8. Tham khảo

- Flink Documentation: https://nightlies.apache.org/flink/flink-docs-stable/
- Kafka Streams Documentation: https://kafka.apache.org/documentation/streams/
- "Designing Data-Intensive Applications" - Martin Kleppmann
- "Streaming Systems" - Tyler Akidau
