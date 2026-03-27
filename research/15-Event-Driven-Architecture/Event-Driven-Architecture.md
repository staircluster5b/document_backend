# Event-Driven Architecture: Kafka, RabbitMQ Internals & Production Patterns

> **Mục tiêu:** Thấu hiểu bản chất messaging systems, các pattern phân tán và rủi ro production khi xây dựng hệ thống event-driven.

---

## 1. Mục tiêu của Task

Nghiên cứu này tập trung vào:
- **Bản chất cơ chế** của Kafka và RabbitMQ - chúng khác nhau căn bản như thế nào ở tầng kiến trúc
- **Event Sourcing** - lưu trữ state như chuỗi events thay vì current state
- **CQRS** - tách biệt read model và write model
- **Outbox Pattern** - giải quyết dual-write problem
- **Idempotent Consumers** - đảm bảo xử lý an toàn khi duplicate messages

---

## 2. Bản chất và Cơ chế Hoạt động

### 2.1 Kafka Internals: Log-Centric Architecture

Kafka không phải là message queue truyền thống - nó là **distributed commit log**.

#### Core Design Philosophy

```
┌─────────────────────────────────────────────────────────────┐
│                    KAFKA ARCHITECTURE                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────┐     ┌─────────┐     ┌─────────┐               │
│  │Producer1│     │Producer2│     │Producer3│               │
│  └────┬────┘     └────┬────┘     └────┬────┘               │
│       │               │               │                     │
│       └───────────────┼───────────────┘                     │
│                       │                                      │
│                       ▼                                      │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                    BROKER CLUSTER                    │   │
│  │  ┌─────────────────────────────────────────────┐    │   │
│  │  │  Topic: "orders"                             │    │   │
│  │  │  ┌─────────────┐  ┌─────────────┐           │    │   │
│  │  │  │ Partition 0 │  │ Partition 1 │ ...       │    │   │
│  │  │  │  (Leader)   │  │  (Leader)   │           │    │   │
│  │  │  │  Offset: 0  │  │  Offset: 0  │           │    │   │
│  │  │  │  Offset: 1  │  │  Offset: 1  │           │    │   │
│  │  │  │  Offset: 2  │  │  Offset: 2  │           │    │   │
│  │  │  │     ...     │  │     ...     │           │    │   │
│  │  │  └─────────────┘  └─────────────┘           │    │   │
│  │  └─────────────────────────────────────────────┘    │   │
│  │                                                      │   │
│  │  Replication: Each partition has Leader + Followers  │   │
│  │  ISR (In-Sync Replicas): min.insync.replicas config  │   │
│  └─────────────────────────────────────────────────────┘   │
│                       │                                      │
│       ┌───────────────┼───────────────┐                     │
│       ▼               ▼               ▼                     │
│  ┌─────────┐     ┌─────────┐     ┌─────────┐               │
│  │Consumer1│     │Consumer2│     │Consumer3│               │
│  │ (Group) │     │ (Group) │     │ (Group) │               │
│  └─────────┘     └─────────┘     └─────────┘               │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

#### Bản chất Partition & Offset

| Khái niệm | Bản chất | Ý nghĩa Production |
|-----------|----------|-------------------|
| **Partition** | Một log file được append-only | Message order chỉ được đảm bảo trong cùng partition |
| **Offset** | Vị trí byte trong log | Consumer tự quản lý offset = consumer có thể replay |
| **Segment** | File vật lý trên disk (default 1GB) | Quyết định retention và cleanup performance |
| **Replication Factor** | Số copies của partition | Trade-off: durability vs throughput |

> **Quan trọng:** Kafka không xóa message sau khi consume - message tồn tại đến khi hết retention period. Đây là điểm khác biệt căn bản với traditional queues.

#### Producer Acknowledgments & Durability

```java
// Cấu hình durability levels
props.put("acks", "all");          // Chờ tất cả ISR ack
props.put("acks", "1");            // Chờ leader ack
props.put("acks", "0");            // Fire-and-forget

// min.insync.replicas = 2 nghĩa là:
// - Cần ít nhất 2 broker ghi thành công
// - Nếu chỉ còn 1 broker sống, producer sẽ nhận NOT_ENOUGH_REPLICAS
```

**Trade-off Durability vs Latency:**

| Config | Durability | Latency | Use Case |
|--------|-----------|---------|----------|
| `acks=0` | Thấp nhất | < 1ms | Metrics, logs không critical |
| `acks=1` | Trung bình | ~10ms | Event tracking, analytics |
| `acks=all` + `min.insync.replicas=2` | Cao | ~50-100ms | Financial transactions |

#### Consumer Group Rebalancing

```
Consumer Group: "payment-processors"

Trước rebalancing:
┌────────────────────────────────────────────┐
│ Consumer-1: [Partition-0, Partition-1]     │
│ Consumer-2: [Partition-2, Partition-3]     │
└────────────────────────────────────────────┘

Consumer-3 join → Trigger rebalancing:
┌────────────────────────────────────────────┐
│ Consumer-1: [Partition-0]                  │
│ Consumer-2: [Partition-1, Partition-2]     │
│ Consumer-3: [Partition-3]                  │
└────────────────────────────────────────────┘
```

> **Cảnh báo Production:** Rebalancing là "stop-the-world" event cho consumer group. Một consumer chậm/crash có thể khiến cả nhóm dừng xử lý trong giây lát.

**Strategies giảm thiểu rebalancing:**
- `session.timeout.ms` vs `heartbeat.interval.ms` - cân bằng failure detection và unnecessary rebalancing
- `max.poll.interval.ms` - consumer phải poll trong khoảng này, nếu không bị coi là dead
- Static membership (`group.instance.id`) - consumer giữ partition khi restart

### 2.2 RabbitMQ Internals: Queue-Centric Architecture

RabbitMQ implement **AMQP 0-9-1** - protocol designed cho enterprise messaging.

```
┌─────────────────────────────────────────────────────────────┐
│                   RABBITMQ ARCHITECTURE                      │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────┐     ┌─────────┐     ┌─────────┐               │
│  │Producer1│     │Producer2│     │Producer3│               │
│  └────┬────┘     └────┬────┘     └────┬────┘               │
│       │               │               │                     │
│       └───────────────┼───────────────┘                     │
│                       ▼                                      │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                    EXCHANGE LAYER                    │   │
│  │                                                      │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐          │   │
│  │  │  direct  │  │   topic  │  │  fanout  │          │   │
│  │  │  (routing│  │ (pattern │  │ (broadcast│         │   │
│  │  │   key)   │  │  match)  │  │         )  │         │   │
│  │  └────┬─────┘  └────┬─────┘  └────┬─────┘          │   │
│  │       │             │             │                 │   │
│  └───────┼─────────────┼─────────────┼─────────────────┘   │
│          │             │             │                      │
│          ▼             ▼             ▼                      │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                    QUEUE LAYER                       │   │
│  │                                                      │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │   │
│  │  │   Queue-A   │  │   Queue-B   │  │   Queue-C   │  │   │
│  │  │  (durable)  │  │  (transient)│  │  (durable)  │  │   │
│  │  │  messages   │  │  messages   │  │  messages   │  │   │
│  │  │  persistent │  │  in memory  │  │  persistent │  │   │
│  │  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  │   │
│  │         │                │                │         │   │
│  └─────────┼────────────────┼────────────────┼─────────┘   │
│            │                │                │              │
│            ▼                ▼                ▼              │
│       ┌─────────┐      ┌─────────┐      ┌─────────┐        │
│       │Consumer1│      │Consumer2│      │Consumer3│        │
│       └─────────┘      └─────────┘      └─────────┘        │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

#### Bản chất Exchange Types

| Exchange | Routing Logic | Use Case | Performance |
|----------|---------------|----------|-------------|
| **Direct** | Exact match routing key | Task distribution | Fastest |
| **Topic** | Pattern matching (`order.*.created`) | Event filtering | Medium |
| **Fanout** | Broadcast to all bound queues | Notifications | Fast |
| **Headers** | Match header attributes | Complex filtering | Slowest |

#### Message Persistence & Durability

RabbitMQ có 2 dimensions độc lập:

1. **Queue Durability:** Queue có tồn tại sau restart không?
   - `durable=true`: Queue metadata được lưu
   - `durable=false`: Queue mất sau restart

2. **Message Persistence:** Message có được ghi xuống disk không?
   - `delivery_mode=2` (persistent): Ghi xuống disk
   - `delivery_mode=1` (transient): Chỉ trong memory

> **Pitfall thường gặp:** Tạo durable queue nhưng gửi transient messages = mất message khi restart!

#### Flow Control & Backpressure

RabbitMQ implement **credit-based flow control**:

```
Producer ──► Exchange ──► Queue ──► Consumer
                │           │
                │           ├── Memory threshold (vm_memory_high_watermark)
                │           │   → Block producers khi > 40% RAM
                │           │
                │           └── Disk threshold (disk_free_limit)
                │               → Block producers khi disk < limit
                │
                └── Per-connection flow control
                    → Consumer ack tốc độ nào, producer gửi tốc độ đó
```

**Memory Alarm:** Khi RabbitMQ chạm `vm_memory_high_watermark` (default 40% RAM), tất cả connections bị block cho đến khi memory giảm. Đây là global pause - rất nguy hiểm trong production.

### 2.3 So sánh Kafka vs RabbitMQ: Bản chất khác biệt

| Aspect | Kafka | RabbitMQ |
|--------|-------|----------|
| **Data Model** | Distributed log (append-only) | Queue (FIFO, delete after ack) |
| **Message Lifecycle** | Retention-based (hours/days) | Ack-based (delete immediately) |
| **Replay** | Có thể replay từ đầu | Không thể (đã xóa) |
| **Ordering** | Partition-level ordering | Queue-level ordering |
| **Consumer Model** | Pull-based | Push-based |
| **Scaling Unit** | Partition | Queue |
| **Best For** | Event streaming, big data | Task queues, RPC, routing |
| **Message Size** | Default 1MB (configurable) | Theo AMQP, practical < 512MB |
| **Delivery Guarantee** | At-least-once, at-most-once | At-most-once, at-least-once, exactly-once |

> **Quyết định kiến trúc:** Kafka cho event sourcing và log aggregation. RabbitMQ cho complex routing và task queuing. Không có "cái nào tốt hơn" - chỉ có "cái nào phù hợp hơn".

---

## 3. Kiến trúc Pattern trong Event-Driven Systems

### 3.1 Event Sourcing: State as Derivative

```
Traditional CRUD:                    Event Sourcing:
┌──────────────┐                   ┌──────────────────┐
│  Current     │                   │   Event Store    │
│    State     │                   │  (immutable log) │
│   (Table)    │                   └────────┬─────────┘
└──────────────┘                            │
      │                                     ▼
   UPDATE                          ┌─────────────────┐
      │                            │ Event 1: Created│
      ▼                            │ Event 2: Updated│
┌──────────────┐                   │ Event 3: Paid   │
│  New State   │                   │ Event 4: Shipped│
│  (overwrite) │                   └─────────────────┘
└──────────────┘                            │
                                     ┌──────┴──────┐
                                     ▼             ▼
                              ┌──────────┐  ┌──────────┐
                              │  State   │  │  State   │
                              │ View 1   │  │ View 2   │
                              │(materialized│ │(CQRS)   │
                              └──────────┘  └──────────┘
```

#### Bản chất Event Sourcing

Event sourcing lưu trữ **tất cả thay đổi state** như một chuỗi events, không chỉ lưu current state.

| Aspect | CRUD | Event Sourcing |
|--------|------|----------------|
| **Storage** | Current state only | Immutable event history |
| **Audit** | Difficult/impossible | Built-in, complete |
| **Debugging** | Limited | Replay events để reproduce bugs |
| **Temporal Queries** | Hard | Query state at any point in time |
| **Complexity** | Low | High |

#### Aggregate Reconstruction

```java
// Mỗi aggregate được rebuild bằng cách replay events
public Order rebuildFromEvents(List<OrderEvent> events) {
    Order order = new Order(); // empty aggregate
    
    for (OrderEvent event : events) {
        order.apply(event); // mutate state based on event
    }
    
    return order;
}

// Event application (no business logic, just state mutation)
void apply(OrderCreatedEvent e) {
    this.id = e.getOrderId();
    this.status = CREATED;
    this.createdAt = e.getTimestamp();
}

void apply(OrderPaidEvent e) {
    this.status = PAID;
    this.paidAt = e.getTimestamp();
}
```

> **Performance Concern:** Nếu aggregate có 10,000 events, reconstruction chậm. Giải pháp: **Snapshotting** - lưu state định kỳ, chỉ replay events sau snapshot.

### 3.2 CQRS: Command Query Responsibility Segregation

CQRS tách biệt model cho **write** và **read**.

```
┌─────────────────────────────────────────────────────────────┐
│                       CQRS ARCHITECTURE                      │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────────┐          ┌─────────────────┐           │
│  │   COMMAND SIDE  │          │    QUERY SIDE   │           │
│  │                 │          │                 │           │
│  │  ┌───────────┐  │          │  ┌───────────┐  │           │
│  │  │  Command  │  │          │  │   Query   │  │           │
│  │  │  Handler  │  │          │  │  Handler  │  │           │
│  │  └─────┬─────┘  │          │  └─────┬─────┘  │           │
│  │        │        │          │        │        │           │
│  │        ▼        │          │        ▼        │           │
│  │  ┌───────────┐  │          │  ┌───────────┐  │           │
│  │  │  Domain   │  │          │  │  Read     │  │           │
│  │  │  Model    │  │          │  │  Model    │  │           │
│  │  │ (Rich)    │  │          │  │ (Denormalized)│          │
│  │  └─────┬─────┘  │          │  └─────┬─────┘  │           │
│  │        │        │          │        │        │           │
│  │        ▼        │          │        │        │           │
│  │  ┌───────────┐  │          │  ┌───────────┐  │           │
│  │  │  Event    │──┼──────────┼─►│  Event    │  │           │
│  │  │  Store    │  │          │  │  Handler  │  │           │
│  │  └───────────┘  │          │  └─────┬─────┘  │           │
│  │                 │          │        │        │           │
│  └─────────────────┘          │        ▼        │           │
│                               │  ┌───────────┐  │           │
│                               │  │  Read DB  │  │           │
│                               │  │(Optimized)│  │           │
│                               │  └───────────┘  │           │
│                               └─────────────────┘           │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

#### Tại sao CQRS?

| Problem | Traditional | CQRS Solution |
|---------|-------------|---------------|
| **Conflicting models** | One model serve both read/write | Separate optimized models |
| **Complex queries** | JOINs nhiều bảng | Pre-computed read model |
| **Performance** | Write pattern ảnh hưởng read | Scale read/write independently |
| **Team autonomy** | Coupled schema | Team tự quản lý read model |

#### Eventual Consistency in CQRS

> **Trade-off quan trọng:** Khi command hoàn thành, read model chưa cập nhật ngay. Gap này gọi là "eventual consistency window".

```
Timeline:
T0: Command "CreateOrder" processed → Event published
T1: Event propagating → Read model chưa cập nhật
T2: User query → Thấy old state (hoặc not found)
T3: Read model updated
T4: Query → Thấy new state

Vấn đề UX: User tạo xong query không thấy!
Giải pháp:
1. Optimistic UI - hiển thị kết quả dự đoán
2. Command returns version/token, client poll/check
3. Accept eventual consistency trong UI design
```

### 3.3 Outbox Pattern: Solving Dual-Write Problem

#### The Problem

```
Without Outbox (Dual-Write):
┌──────────────┐     ┌──────────────┐
│   Service    │────►│  Database    │
│              │     └──────────────┘
│  1. Save()   │            ✓ Success
│  2. Publish  │────►┌──────────────┐
│     Event    │     │    Kafka     │
└──────────────┘     └──────────────┘
                            ✗ Fail

Kết quả: Database có data, Kafka không có event
         → Inconsistency!
```

#### The Solution: Outbox Pattern

```
With Outbox Pattern:
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│   Service    │────►│  Database    │     │    Kafka     │
│              │     │              │     │              │
│  1. Save()   │     │  ┌────────┐  │     │              │
│  2. Insert   │────►│  │Outbox  │  │     │              │
│     Outbox   │     │  │ Table  │  │     │              │
└──────────────┘     │  └────────┘  │     │              │
                     └──────────────┘     │              │
                            │              │              │
┌──────────────┐            │              │              │
│  Outbox      │◄───────────┘              │              │
│  Publisher   │     Poll Outbox           │              │
│  (Relay)     │──────────────────────────►│  Publish     │
└──────────────┘                           └──────────────┘

Ghi chú: Transaction bao gồm cả business data và outbox
         → Atomic operation, không thể partial success
```

#### Implementation Detail

```sql
-- Outbox table schema
CREATE TABLE outbox (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(255),  -- e.g., "Order"
    aggregate_id VARCHAR(255),    -- e.g., orderId
    event_type VARCHAR(255),      -- e.g., "OrderCreated"
    payload JSONB,                -- Event data
    created_at TIMESTAMP,
    published BOOLEAN DEFAULT FALSE,
    published_at TIMESTAMP
);

-- Transactional insert
BEGIN;
    INSERT INTO orders (id, ...) VALUES (...);
    INSERT INTO outbox (id, aggregate_type, ...) VALUES (...);
COMMIT;
```

**Outbox Publisher (Relay):**
- Poll outbox table cho unpublished events
- Publish lên message broker
- Mark as published (hoặc delete)
- Handle duplicates (idempotent consumers)

> **Trade-off:** Latency cao hơn (phải qua database). Nhưng đảm bảo consistency.

### 3.4 Idempotent Consumers

#### Why Idempotency Matters

```
At-least-once delivery guarantee:
┌──────────┐     ┌──────────┐     ┌──────────┐
│  Kafka   │────►│ Consumer │────►│ Database │
└──────────┘     └────┬─────┘     └──────────┘
                      │
                      ▼
              ┌──────────────┐
              │ Process msg  │
              │ 1. Update DB │  ◄── Success
              │ 2. Commit    │  ◄── Fail!
              └──────────────┘

Result: Message processed, nhưng not committed
        → Consumer restart → Process again!
        → Duplicate update!
```

#### Idempotency Strategies

| Strategy | Mechanism | Use Case |
|----------|-----------|----------|
| **Database UPSERT** | `INSERT ... ON CONFLICT` | Natural idempotency |
| **Idempotency Key** | Store processed message IDs | Generic solution |
| **State Machine** | Check current state before process | Business logic level |
| **Conditional Update** | `UPDATE ... WHERE version = X` | Optimistic locking |

#### Implementation: Idempotency Key Store

```sql
-- Idempotency tracking table
CREATE TABLE idempotency_keys (
    key VARCHAR(255) PRIMARY KEY,      -- messageId or correlationId
    processor VARCHAR(255),             -- consumer identifier
    processed_at TIMESTAMP,
    result JSONB                        -- cached result if needed
);

-- Consumer logic
BEGIN;
    -- Check if already processed
    SELECT 1 FROM idempotency_keys WHERE key = :messageId;
    
    IF NOT FOUND THEN
        -- Process message
        UPDATE accounts SET balance = balance + :amount WHERE id = :accountId;
        
        -- Record idempotency
        INSERT INTO idempotency_keys (key, processor, processed_at)
        VALUES (:messageId, 'payment-consumer', NOW());
    END IF;
COMMIT;
```

> **TTL Consideration:** Idempotency keys không thể lưu mãi mãi. Set TTL (e.g., 7 days) dựa trên message retention period.

---

## 4. Rủi ro, Anti-patterns, Lỗi thường gặp

### 4.1 Kafka Pitfalls

#### The "Too Many Partitions" Problem

```
Mỗi partition là:
- 1 file trên disk (segment files)
- 1 entry trong ZooKeeper/KRaft
- Memory overhead trong broker
- Thread overhead cho replication

Guideline: Max ~10,000 partitions per broker
           Max ~200,000 partitions per cluster

Nếu vượt quá: Startup time chậm, rebalancing khủng khiếp
```

#### Consumer Lag & Autocommit Trap

```java
// Cấu hình nguy hiểm mặc định
props.put("enable.auto.commit", "true");
props.put("auto.commit.interval.ms", "5000");

// Vấn đề: Commit mỗi 5s, không liên quan đến việc đã process xong chưa
// Nếu consumer crash giữa chừng → Messages lost!

// Cách an toàn:
props.put("enable.auto.commit", "false");

// Manual commit SAU KHI process xong
try {
    process(record);
    consumer.commitSync(); // Đảm bảo committed
} catch (Exception e) {
    // Không commit → Message sẽ được reprocess
    handleError(e);
}
```

#### Reprocessing Storm

Khi consumer group bị rebalancing liên tục (ví dụ: consumer chậm, timeout), các message bị reassign liên tục → Không tiến triển.

**Dấu hiệu:**
- Consumer lag tăng mãi
- Rebalance logs liên tục
- CPU thấp nhưng không xử lý được messages

**Giải pháp:**
- Giảm `max.poll.records` để consumer xử lý nhanh hơn
- Tăng `session.timeout.ms` nếu processing thực sự chậm
- Scale consumer instances

### 4.2 RabbitMQ Pitfalls

#### Memory Alarm Death Spiral

```
Scenario:
1. Consumers chậm → Queue buildup
2. Queue đầy memory → Memory alarm trigger
n3. All producers blocked globally
4. Hệ thống dừng hoạt động hoàn toàn

Giải pháp:
- Set queue length limits (x-max-length)
- Set message TTL (x-message-ttl)
- Dead letter overflow messages
- Monitor và alert sớm
```

#### Durable Queue + Non-persistent Messages

```java
// Tạo durable queue
channel.queueDeclare("orders", true, false, false, null);

// Nhưng gửi non-persistent message
channel.basicPublish("", "orders", 
    null,  // MessageProperties.PERSISTENT_TEXT_PLAIN nên dùng ở đây
    message.getBytes());

// Kết quả: Queue tồn tại sau restart, nhưng messages mất hết!
```

#### Connection/Channel Leak

```
RabbitMQ resource model:
Connection (TCP) → Heavyweight (hundreds per broker)
  └── Channel (lightweight) → Thousands per connection

Anti-pattern: Tạo connection mới cho mỗi message
Result: Exhaust file descriptors, memory

Best practice: 
- 1 connection per process
- Channel pool cho threads
- Hoặc 1 channel per thread (channels không thread-safe)
```

### 4.3 Event Sourcing Anti-patterns

#### Snapshots Without Versioning

```
Snapshot schema thiếu version:
┌─────────────┬──────────────┬─────────┐
│ aggregate_id│ state_data   │ version │
└─────────────┴──────────────┴─────────┘

Vấn đề: Event schema evolve, nhưng snapshot không biết
        → Deserialization fail hoặc silent corruption

Solution: Thêm snapshot_version, migrate khi cần
```

#### Event as Notification Only

```
Bad Event (Notification):
{
  "type": "OrderUpdated",
  "orderId": "123"
}
→ Consumer phải query API để lấy data = Tight coupling

Good Event (Event-Carried State Transfer):
{
  "type": "OrderUpdated",
  "orderId": "123",
  "customerId": "456",
  "items": [...],
  "total": 100.00,
  "status": "PAID"
}
→ Consumer self-contained
```

### 4.4 CQRS Anti-patterns

#### Premature CQRS

> Không phải mọi hệ thống đều cần CQRS. Nếu read/write model gần như giống nhau, CQRS chỉ thêm complexity không cần thiết.

#### Synchronous Read Model Update

```
Anti-pattern:
Command → Write DB → Đợi → Update Read DB → Return

Vấn đề: 
- Latency tăng gấp đôi
- Nếu read DB fail → Command fail = không đúng semantics

Đúng:
Command → Write DB → Return
              ↓
         Event published
              ↓
         Async update Read DB
```

---

## 5. Khuyến nghị thực chiến trong Production

### 5.1 Kafka Production Checklist

| Concern | Recommendation | Rationale |
|---------|---------------|-----------|
| **Replication** | `replication.factor=3`, `min.insync.replicas=2` | Survive 2 broker failures |
| **Acks** | `acks=all` cho critical data | Durability over latency |
| **Partitions** | Start với 6-12, scale khi cần | Avoid over-partitioning |
| **Retention** | Time-based + Size-based | Prevent disk full |
| **Monitoring** | Consumer lag, ISR shrink, under-replicated | Early warning |
| **Schema** | Use Schema Registry | Evolution safety |

### 5.2 RabbitMQ Production Checklist

| Concern | Recommendation | Rationale |
|---------|---------------|-----------|
| **HA** | Mirror queues (classic) hoặc Quorum queues | Automatic failover |
| **Resource Limits** | Queue TTL, max-length, overflow=reject | Prevent memory death |
| **Connections** | Connection pooling, channel limits | Resource management |
| **Monitoring** | Queue depth, memory usage, connections | Early warning |
| **DLX** | Dead Letter Exchange cho mọi queue | Error handling |

### 5.3 Event Sourcing Production Tips

1. **Event Schema Versioning:**
   ```json
   {
     "specversion": "1.0",
     "type": "OrderCreated",
     "datacontenttype": "application/json",
     "dataschema": "https://schemas.example.com/order/v2",
     "data": {...}
   }
   ```

2. **Snapshot Strategy:**
   - Snapshot mỗi N events (e.g., 100)
   - Hoặc khi aggregate > threshold size
   - Async snapshot không block command processing

3. **Event Store Selection:**
   - Kafka: Good cho event streaming, replay
   - EventStoreDB: Purpose-built, projection support
   - PostgreSQL: Simple, familiar, JSONB support

### 5.4 Outbox Pattern Implementation Tips

- **Polling interval:** 100ms - 1s (đủ nhanh, không quá tải DB)
- **Batch size:** 10-100 events per poll
- **Delete vs Update:** Delete published events để table nhỏ, HOẶC mark published với TTL
- **Relay redundancy:** Nên có 2+ relay instances, partition by aggregate để tránh conflict

---

## 6. Kết luận

### Bản chất cốt lõi

| Pattern | Bản chất | Khi nào dùng |
|---------|----------|--------------|
| **Kafka** | Distributed commit log, pull-based | Event streaming, high throughput, replay needed |
| **RabbitMQ** | Queue-based, push-based, AMQP | Complex routing, RPC, task queuing |
| **Event Sourcing** | State = fold(events), immutable history | Audit requirements, temporal queries, complex domains |
| **CQRS** | Separate read/write models | Read/write asymmetry, scale independently |
| **Outbox** | Atomic DB + message publish | Dual-write problem, consistency required |
| **Idempotent Consumer** | Duplicate detection/protection | At-least-once delivery, exactly-once semantics |

### Trade-off tổng hợp

```
Event-Driven Architecture = Power + Complexity

Lợi ích:
+ Loose coupling giữa services
+ Scale independently  
+ Natural audit trail (events)
+ Handle traffic spikes (queue buffering)

Chi phí:
- Eventual consistency
- Operational complexity
- Debugging distributed transactions khó
- Schema evolution challenges
- Message delivery guarantees require careful design
```

### Quyết định kiến trúc

> **Không phải lúc nào cũng cần event-driven.** CRUD đơn giản + relational database vẫn là lựa chọn đúng cho nhiều use case. Event-driven phát huy khi:
> 
> - Multiple services cần phản ứng với cùng event
> - Scale requirements không phù hợp synchronous calls
> - Business logic phức tạp, cần audit trail
> - Cần temporal queries (state at time T)

---

## 7. Tài liệu tham khảo

1. **Kafka:** "Kafka: The Definitive Guide" - Gwen Shapira et al.
2. **RabbitMQ:** "RabbitMQ in Depth" - Gavin M. Roy
3. **Event Sourcing:** "Implementing Domain-Driven Design" - Vaughn Vernon
4. **CQRS:** "Patterns, Principles, and Practices of Domain-Driven Design" - Scott Millett
5. **Microservices Patterns:** "Microservices Patterns" - Chris Richardson
6. **Kafka Internals:** https://kafka.apache.org/documentation/#design
7. **RabbitMQ Tutorials:** https://www.rabbitmq.com/tutorials
