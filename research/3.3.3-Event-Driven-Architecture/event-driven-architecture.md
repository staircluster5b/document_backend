# Event-Driven Architecture: Kafka, RabbitMQ Internals, Event Sourcing, CQRS & Production Patterns

## 1. Mục tiêu của Task

Hiểu sâu bản chất của Event-Driven Architecture (EDA) - không chỉ là "dùng message queue", mà là thiết kế hệ thống xoay quanh sự kiện như đơn vị thay đổi trạng thái duy nhất. Phân tích cơ chế vận hành của Kafka và RabbitMQ ở tầng thấp, các pattern như Event Sourcing, CQRS, Outbox, và cách xử lý idempotency trong môi trường phân tán.

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 Event-Driven Architecture là gì - thực sự?

> **Định nghĩa lõi:** EDA là kiến trúc mà sự thay đổi trạng thái được capture thành event, broadcast đến các consumers, và các consumers phản ứng lại event đó.

Khác với request-driven (gọi API đồng bộ), EDA decouple producer và consumer về:
- **Thờii gian:** Consumer không cần online khi event được produce
- **Không gian:** Producer không cần biết consumer là ai, ở đâu
- **Giao thức:** Không cần cùng protocol (HTTP vs binary protocol)

**Trade-off cơ bản:**
| Aspect | Request-Driven | Event-Driven |
|--------|---------------|--------------|
| Coupling | Tight (caller biết callee) | Loose (via event schema) |
| Latency | Thấp (sync) | Cao hơn (async + network) |
| Consistency | Strong | Eventual |
| Complexity | Dễ hiểu, debug khó khi scale | Khó hiểu, debug khó hơn |
| Failure handling | Retry đơn giản | Cần dead letter queue, idempotency |

### 2.2 Kafka Internals - Tại sao nó nhanh?

#### Log-Centric Architecture

Kafka không phải là message queue truyền thống - nó là **distributed commit log**:

```
┌─────────────────────────────────────────────────────────────┐
│                         Topic: orders                        │
├────────┬────────┬────────┬────────┬────────┬────────┬───────┤
│Offset 0│Offset 1│Offset 2│Offset 3│Offset 4│Offset 5│  ...  │
│  P0    │  P0    │  P1    │  P1    │  P0    │  P2    │       │
├────────┴────────┴────────┴────────┴────────┴────────┴───────┤
│Partition 0          │Partition 1          │Partition 2      │
└─────────────────────┴─────────────────────┴─────────────────┘
```

**Bản chất hiệu năng cao:**

1. **Sequential I/O thay vì Random I/O:**
   - Producer append vào cuối file → O(1)
   - Consumer read tuần tự → OS page cache friendly
   - Không cần index phức tạp như database

2. **Zero-Copy Transfer:**
   ```
   Traditional: Disk → Kernel Buffer → User Buffer → Socket Buffer → Network
   Kafka:       Disk → Kernel Buffer ──────────────────→ Network (sendfile syscall)
   ```
   - Giảm 2 lần copy, 2 lần context switch
   - Tăng throughput từ ~50MB/s lên ~500MB/s+

3. **Batching & Compression:**
   - Gom nhiều message thành batch → giảm network round-trip
   - Compression ở producer → giảm bandwidth

#### Replication & Consistency

Kafka dùng **leader-follower replication** với ISR (In-Sync Replicas):

```
┌────────────────────────────────────────────┐
│           Partition Leader (Broker 1)      │
│  ┌─────────┐    ┌─────────┐    ┌─────────┐ │
│  │ Offset  │    │ Offset  │    │ Offset  │ │
│  │   0-99  │    │ 100-199 │    │ 200-299 │ │
│  └─────────┘    └─────────┘    └─────────┘ │
└────────┬──────────────┬─────────────────────┘
         │              │
    ┌────▼────┐    ┌────▼────┐
    │Follower │    │Follower │
    │Broker 2 │    │Broker 3 │
    └─────────┘    └─────────┘
```

**ACK modes và trade-off:**
- `acks=0`: Fire-and-forget, fastest, có thể mất message
- `acks=1`: Leader nhận là OK, có thể mất nếu leader crash trước khi replicate
- `acks=all`: ISR phải commit, slowest nhất, safest

**Mối nguy:** `min.insync.replicas` cấu hình sai → ghi vào 1 replica rồi nghĩ là safe.

#### Consumer Groups & Rebalancing

```
┌─────────────────────────────────────────┐
│           Consumer Group: payment       │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐ │
│  │Consumer1│  │Consumer2│  │Consumer3│ │
│  │  P0,P1  │  │  P2,P3  │  │  P4,P5  │ │
│  └─────────┘  └─────────┘  └─────────┘ │
└─────────────────────────────────────────┘
```

- Mỗi partition chỉ được consume bởi 1 consumer trong group
- Consumer crash → rebalance, partition chuyển sang consumer khác
- **Vấn đề:** Rebalance storm khi consumer join/leave liên tục

### 2.3 RabbitMQ Internals - Tại sao nó linh hoạt?

#### AMQP Model Deep Dive

RabbitMQ dựa trên AMQP 0.9.1 với 4 khái niệm cốt lõi:

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Publisher  │────▶│   Exchange  │────▶│    Queue    │────▶│  Consumer   │
└─────────────┘     └─────────────┘     └─────────────┘     └─────────────┘
                          │
                    ┌─────┴─────┐
                    │  Binding  │ (routing key pattern)
                    └───────────┘
```

**Exchange types và use case:**
| Type | Behavior | Use Case |
|------|----------|----------|
| Direct | Routing key exact match | Point-to-point |
| Topic | Pattern match (*, #) | Pub/sub với filtering |
| Fanout | Broadcast to all queues | Fan-out |
| Headers | Match header values | Complex routing rules |

#### Queue Internals

RabbitMQ queues có thể hoạt động ở 2 mode:

1. **RAM mode:** Message trong memory → nhanh, mất khi restart
2. **Disk mode:** Message persisted → chậm hơn 10-100x, survive restart

**Lazy Queues (RabbitMQ 3.6+):**
- Message luôn write disk trước, load vào RAM khi consumer ready
- Trade-off: Latency cao hơn, nhưng RAM usage ổn định

**Quorum Queues (RabbitMQ 3.8+):**
- Raft-based replication (thay vì mirror queue cũ)
- Leader election nhanh hơn, consistency tốt hơn
- Không hỗ trợ: TTL per-message, priority queue, lazy mode

#### Flow Control & Backpressure

RabbitMQ có **Credit-based Flow Control**:
- Consumer prefetch count (QoS) giới hạn message unacknowledged
- Producer bị block khi memory/disk threshold vượt quá
- **Vấn đề:** Block toàn bộ connection thay vì chỉ block producer

### 2.4 Kafka vs RabbitMQ - Khi nào dùng cái nào?

| Dimension | Kafka | RabbitMQ |
|-----------|-------|----------|
| **Design** | Log, stream processing | Queue, task distribution |
| **Message model** | Pull (consumer chủ động) | Push (broker đẩy) |
| **Ordering** | Strong (trong partition) | Weak (trừ khi dùng single queue) |
| **Retention** | Configurable (days/weeks) | Ack+delete (có thể persist) |
| **Replay** | Có thể replay từ offset | Không (message đã xóa) |
| **Throughput** | 100K-1M msg/s | 10K-50K msg/s |
| **Latency** | ~10-100ms | ~1ms |
| **Routing** | Basic (topic/partition) | Complex (exchange + binding) |
| **Scenarios** | Event sourcing, analytics | RPC, task queue, complex routing |

**Rule of thumb:**
- Cần **event sourcing** hoặc **stream processing** → Kafka
- Cần **complex routing**, **low latency RPC** → RabbitMQ
- Cần **cả hai** → Cân nhắc dùng cả hai (Kafka cho event log, RabbitMQ cho command)

---

## 3. Kiến Trúc và Luồng Xử Lý

### 3.1 Event Sourcing - Source of Truth là Event Stream

#### Bản chất

Thay vì lưu **current state**, lưu **sequence of events**:

```
┌─────────────────────────────────────────────────────────────┐
│                    Event Store (Kafka)                       │
├─────────────┬─────────────┬─────────────┬───────────────────┤
│ OrderCreated│ItemAdded    │ShippingAddr │PaymentProcessed   │
│ {id: 123}   │{sku: A1}    │Updated      │{amt: 100}         │
└─────────────┴─────────────┴─────────────┴───────────────────┘
                    │
                    ▼
        ┌─────────────────────┐
        │   Projection/View   │
        │  (Current State)    │
        │  Order #123:        │
        │  items: [A1]        │
        │  addr: xxx          │
        │  paid: true         │
        └─────────────────────┘
```

**Mô hình tính toán:**
- `State = foldl(apply, initialState, events)`
- State là derived, có thể rebuild từ events

#### Trade-off quan trọng

| Ưu điểm | Nhược điểm |
|---------|------------|
| Audit trail hoàn chỉnh | Storage tăng nhanh |
| Replay để debug/fix | Event schema evolution phức tạp |
| Temporal queries (state at time T) | Learning curve cao |
| Event-driven tự nhiên | Consistency model phức tạp |

#### Event Schema Evolution

```
┌──────────────────────────────────────────────────────────────┐
│  V1: OrderCreated { orderId, amount }                        │
│  V2: OrderCreated { orderId, amount, currency }  ← Thêm field│
│  V3: OrderCreated { orderId, items[], total }    ← Breaking! │
└──────────────────────────────────────────────────────────────┘
```

**Strategies:**
1. **Forward compatibility:** New code đọc old events (default values)
2. **Backward compatibility:** Old code đọc new events (ignore unknown fields)
3. **Schema Registry (Confluent):** Enforce compatibility rules

**Khuyến nghị:** Dùng Avro/Protobuf với Schema Registry, không dùng JSON cho event sourcing.

### 3.2 CQRS - Tách Command và Query

#### Bản chất

```
┌──────────────────────────────────────────────────────────────┐
│                        Client                                │
└───────┬──────────────────────────────┬───────────────────────┘
        │ Command                      │ Query
        ▼                              ▼
┌─────────────────┐          ┌──────────────────┐
│  Command Side   │          │   Query Side     │
│  (Write Model)  │          │  (Read Model)    │
│                 │          │                  │
│  Domain Model   │          │  Denormalized    │
│  Validation     │          │  Views           │
│  Business Rules │          │  Optimized reads │
└────────┬────────┘          └────────┬─────────┘
         │                           │
         ▼                           ▼
┌─────────────────┐          ┌──────────────────┐
│   Event Store   │─────────▶│  Read Database   │
│   (Kafka)       │  Events  │  (Elasticsearch, │
│                 │          │   Mongo, Redis)  │
└─────────────────┘          └──────────────────┘
```

**Tại sao tách?**
- Write model: Normalize, consistency, transaction
- Read model: Denormalize, performance, specialized queries

#### Eventual Consistency

> **Vấn đề:** Sau khi write thành công, read model chưa update → user thấy data cũ.

**Giải pháp:**
1. **Command returns Event ID** → Client poll/push khi projection ready
2. **Optimistic UI** → Giả định thành công, rollback nếu event failed
3. **Synchronous Projection** (tránh nếu có thể) → Write đợi read update

### 3.3 Outbox Pattern - Ghi DB và Publish Event Atomically

#### Vấn đề cần giải quyết

```
┌─────────────────────────────────────────────────────────────┐
│  Transaction thành công  │  Publish event                    │
│  ──────────────────────  │  ─────────────                    │
│  COMMIT OK               │  NETWORK ERROR → Event lost!      │
└─────────────────────────────────────────────────────────────┘
```

**Hoặc ngược lại:** Publish trước, commit sau → Event published nhưng DB rollback.

#### Cơ chế Outbox

```
┌──────────────────────────────────────────────────────────────┐
│                    Database (PostgreSQL)                     │
│  ┌─────────────────┐        ┌──────────────────┐            │
│  │  orders table   │        │   outbox table   │            │
│  ├─────────────────┤        ├──────────────────┤            │
│  │ id, status, ... │        │ id, topic,       │            │
│  └─────────────────┘        │ payload, headers │            │
│                             └──────────────────┘            │
└──────────────────────┬───────────────────────────────────────┘
                       │
                       │ Triggers / Polling
                       ▼
              ┌─────────────────┐
              │  Relay Process  │
              │  (Debezium /    │
              │   Polling App)  │
              └────────┬────────┘
                       │ Publish
                       ▼
              ┌─────────────────┐
              │     Kafka       │
              └─────────────────┘
```

**Cách triển khai:**

1. **Polling Publisher:** App query outbox table định kỳ, publish rồi xóa
   - Đơn giản, nhưng có delay (polling interval)

2. **Transaction Log Tailing (Debezium):**
   - Đọc WAL (Write-Ahead Log) của PostgreSQL/MySQL
   - Capture change real-time, không cần polling
   - Requires CDC setup, phức tạp hơn

**Trade-off:**
| Approach | Latency | Complexity | Resource Usage |
|----------|---------|------------|----------------|
| Polling | High (100ms-1s) | Low | DB load cao |
| Debezium | Low (<10ms) | High | Additional service |

### 3.4 Idempotent Consumers - Xử lý Duplicate Events

#### Vấn đề

At-least-once delivery → Message có thể được deliver nhiều lần:
- Consumer crash sau khi xử lý nhưng trước khi ack
- Network timeout, producer retry
- Rebalance trong Kafka

#### Các giải pháp Idempotency

**1. Idempotent Operations:**
```java
// Thay vì: UPDATE balance = balance + 100
// Dùng:    UPDATE balance = 1000 WHERE id = 123 AND version = 5
// Hoặc:    INSERT ... ON CONFLICT DO NOTHING
```

**2. Deduplication Table:**
```sql
CREATE TABLE processed_events (
    event_id VARCHAR(255) PRIMARY KEY,
    processed_at TIMESTAMP,
    result_id VARCHAR(255)
);

-- Trước khi xử lý:
INSERT INTO processed_events (event_id, processed_at)
VALUES ('evt-123', NOW())
ON CONFLICT DO NOTHING;

-- Nếu không insert được → duplicate, skip
```

**3. Event Store as Check:**
```
Consumer nhận event với ID
→ Query event store xem event đã processed chưa
→ Nếu chưa: process + ghi vào event store
→ Nếu rồi: skip
```

**4. Business Key Idempotency:**
```
OrderCreated event với orderId = "ORD-123"
→ Dùng orderId làm idempotency key
→ Nếu order đã tồn tại → skip
```

#### Khuyến nghị Production

- **TTL cho dedup table:** Xóa record cũ (>7-30 ngày) để tránh bloat
- **Distributed cache (Redis):** Lưu recent event IDs cho check nhanh
- **Idempotency key trong message:** Producer gửi kèm unique key

---

## 4. Rủi ro, Anti-Patterns và Lỗi Thường Gặp

### 4.1 Message Ordering Assumptions

**Lỗi:** Giả định message đến đúng thứ tự gửi.

**Thực tế:**
- Kafka: Ordering chỉ đảm bảo trong partition
- RabbitMQ: Ordering có thể bị xáo trộn do retry, multiple consumers
- Network: Message A gửi trước B nhưng B đến trước hoàn toàn có thể

**Giải pháp:**
- Dùng **event timestamp** hoặc **version number**
- **Causality tracking:** Vector clocks nếu cần strict ordering
- **Saga pattern:** Orchestrate các step có thứ tự

### 4.2 Event Payload Bloat

**Lỗi:** Nhét cả object state vào event:
```json
{
  "event": "OrderUpdated",
  "order": { /* 50 fields, nested objects */ }
}
```

**Hệ quả:**
- Message size lớn → network overhead, storage cost
- Schema evolution khó khăn
- Consumer bị ảnh hưởng khi producer thay đổi

**Giải pháp:**
- **Event-Carried State Transfer:** Chỉ gửi delta hoặc minimal state
- **Reference data:** Consumer tự query nếu cần chi tiết

### 4.3 Synchronous Event Processing

**Lỗi:** Consumer xử lý event đồng bộ, block thread:
```java
@KafkaListener
def onEvent(event) {
    callExternalAPI(event)  // Block, timeout risk
    saveToDB(event)
}
```

**Giải pháp:**
- Async processing với CompletableFuture/Reactor
- Dead Letter Queue cho message fail
- Retry với exponential backoff

### 4.4 Missing Dead Letter Queue

**Lỗi:** Message fail liên tục → block consumer hoặc infinite retry.

**Best Practice:**
```
┌─────────────┐    fail    ┌──────────────┐    fail    ┌──────────────┐
│   Topic     │───────────▶│ Retry Topic  │───────────▶│     DLQ      │
│             │  (delay)   │  (3 retries) │            │  (manual     │
└─────────────┘            └──────────────┘            │   review)    │
                                                       └──────────────┘
```

### 4.5 Schema Evolution Breakages

**Lỗi:** Đổi event schema mà không xử lý backward/forward compatibility.

**Ví dụ:**
- Đổi field name: `amount` → `totalAmount`
- Đổi type: `amount: int` → `amount: decimal`
- Xóa field: Remove `discountCode`

**Giải pháp:**
- Schema Registry với compatibility check
- Versioning: `OrderCreated_v1`, `OrderCreated_v2`
- Consumer-driven contracts

---

## 5. Khuyến Nghị Thực Chiến trong Production

### 5.1 Monitoring & Observability

**Metrics cần theo dõi:**

| Metric | Ngưỡng cảnh báo | Ý nghĩa |
|--------|-----------------|---------|
| Consumer Lag | > 1000 messages | Consumer không kịp xử lý |
| Retry Rate | > 5% | Vấn đề downstream |
| DLQ Size | > 0 | Có message cần xử lý thủ công |
| End-to-end Latency | > P99 threshold | Performance degradation |
| Broker Disk Usage | > 70% | Risk full disk |

**Distributed Tracing:**
- Propagate traceId từ producer → consumer
- Xử lý event có thể span nhiều services
- Dùng OpenTelemetry + Jaeger/Zipkin

### 5.2 Capacity Planning

**Kafka:**
- Retention: `retention.bytes` và `retention.ms`
- Partition count: N = max(throughput / consumer_throughput, consumer_count)
- Replication factor: 3 cho production

**RabbitMQ:**
- Queue length limit: Tránh memory pressure
- Lazy queues cho large backlog
- Federation/Shovel cho cross-cluster

### 5.3 Security

**Authentication & Authorization:**
- Kafka: SASL/SSL + ACL (Kafka 2.0+)
- RabbitMQ: User/permission per vhost
- mTLS giữa services và broker

**Data Encryption:**
- At-rest: Disk encryption
- In-transit: TLS 1.3
- Sensitive data: Field-level encryption trong payload

### 5.4 Testing Strategy

**Contract Testing:**
- Producer test: Verify event schema tuân thủ contract
- Consumer test: Verify có thể consume events từ contract
- Dùng Pact hoặc Spring Cloud Contract

**Chaos Engineering:**
- Kill broker mid-transaction
- Network partition test
- Consumer lag simulation

---

## 6. Kết Luận

Event-Driven Architecture không phải là silver bullet - nó là **trade-off** giữa:
- **Coupling giảm** vs **Complexity tăng**
- **Scalability** vs **Consistency**
- **Flexibility** vs **Observability**

**Khi nào nên dùng:**
- Hệ thống phân tán với nhiều services
- Cần audit trail đầy đủ → Event Sourcing
- Read/write pattern khác biệt lớn → CQRS
- Cần decouple producer/consumer về thời gian

**Khi nào KHÔNG nên dùng:**
- Đơn giản, ít services → Over-engineering
- Cần strong consistency tuyệt đối
- Team chưa có kinh nghiệm debug distributed systems

**Chốt lại:** EDA là công cụ mạnh nhưng đắt giá. Hiểu sâu cơ chế Kafka/RabbitMQ, nắm vững các pattern (Outbox, Idempotency, Saga), và chuẩn bị infrastructure cho observability từ ngày đầu.
