# Idempotency & Exactly-Once Semantics

> **Mục tiêu:** Hiểu bản chất các delivery guarantees trong distributed messaging, phân tích trade-off giữa các semantic levels, và thiết kế idempotent systems trong production.

---

## 1. Mục tiêu của Task

- Phân biệt rõ 3 loại delivery guarantees: At-most-once, At-least-once, Exactly-once
- Hiểu sâu bản chất **idempotency** - tại sao nó là nền tảng của exactly-once
- Phân tích các deduplication patterns và trade-offs
- Thiết kế hệ thống chịu lỗi với exactly-once semantics trong thực tế

---

## 2. Bản Chất Delivery Guarantees

### 2.1. Three Levels of Message Delivery

| Guarantee | Behavior | Trade-off | Use Case |
|-----------|----------|-----------|----------|
| **At-most-once** | Gửi 0 hoặc 1 lần, không retry | Fastest, có thể mất message | Metrics, logs, real-time analytics |
| **At-least-once** | Gửi 1+ lần, retry khi nghi ngờ loss | Reliable nhưng có duplicates | Order processing, notifications |
| **Exactly-once** | Gửi đúng 1 lần, guaranteed | Slowest, complex, expensive | Financial transactions, inventory |

> **Quan trọng:** Exactly-once **không phải** là network guarantee mà là **end-to-end guarantee**. Nó đòi hỏi sự phối hợp giữa producer, broker, và consumer.

### 2.2. Tại Sao Exactly-Once Lại Khó?

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Producer   │────▶│   Broker    │────▶│  Consumer   │
└─────────────┘     └─────────────┘     └─────────────┘
```

**Failure scenarios tạo ra uncertainty:**

1. **Producer → Broker:** Gửi message, nhận timeout. Message đã đến broker chưa? Gửi lại → duplicate.
2. **Broker internal:** Message được replicate chưa? Leader crash trước khi follower sync.
3. **Broker → Consumer:** Consumer xử lý xong, gửi ack, nhưng ack mất. Broker redeliver → duplicate xử lý.
4. **Consumer processing:** Consumer crash giữa chừng processing và commit offset. Redeliver → duplicate.

**Bản chất vấn đề:** Distributed systems không thể phân biệt "chậm" và "mất" message. Timeout → uncertainty → phải retry → risk of duplication.

---

## 3. Idempotency: Nền Tảng Củng Cố

### 3.1. Định nghĩa Chính Xác

> **Idempotency:** Một operation được gọi là idempotent nếu thực hiện nhiều lần vẫn cho kết quả giống như thực hiện một lần.

**Phân biệt quan trọng:**

| Aspect | Idempotent | Non-idempotent |
|--------|------------|----------------|
| `SET x = 5` | ✅ Có | Gán lại vẫn ra 5 |
| `x = x + 1` | ❌ Không | Mỗi lần chạy tăng thêm 1 |
| `INSERT` (không check) | ❌ Không | Duplicate rows |
| `UPSERT` với key | ✅ Có | Dòng đã tồn tại thì update |
| `DELETE WHERE id=X` | ✅ Có | Xóa lần 1 đã xong, lần 2 không có gì để xóa |

### 3.2. Cơ Chế Triển Khai Idempotency

#### 3.2.1. Idempotency Keys

Pattern phổ biến nhất: Client tạo unique key cho mỗi operation.

```
Request: POST /payments
Headers: Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
Body: { amount: 100, to: "acc-123" }
```

**Storage của idempotency keys:**

```
┌─────────────────────────────────────────────────────┐
│              Idempotency Store                       │
├───────────────┬───────────────┬─────────────────────┤
│ Key           │ Response      │ Expiry              │
├───────────────┼───────────────┼─────────────────────┤
│ 550e8400...   │ { paid: true }│ 2026-03-28 11:00:00 │
└───────────────┴───────────────┴─────────────────────┘
```

**Trade-offs của idempotency store:**

| Storage Type | Pros | Cons |
|--------------|------|------|
| **Redis** | Fast, TTL native | Memory limit, durability risk |
| **Database** | Durable, transactional | Slower, connection overhead |
| **Hybrid** (Redis + DB) | Speed + durability | Complexity, consistency issues |

#### 3.2.2. Natural Idempotency (State-Based)

Một số operations tự nhiên idempotent:

```sql
-- Ví dụ: Update status nếu chưa phải target
UPDATE orders 
SET status = 'PAID', paid_at = NOW()
WHERE order_id = 'ORD-123' 
  AND status != 'PAID';  -- Idempotent: chạy lại không đổi gì
```

**Key insight:** Điều kiện `WHERE` đảm bảo operation chỉ có effect khi state chưa đạt target.

#### 3.2.3. Entity State Machines

```
CREATED ──▶ PROCESSING ──▶ PAID ──▶ SHIPPED ──▶ DELIVERED
              │              │
              ▼              ▼
           FAILED        REFUNDED
```

Với state machine, mỗi transition có điều kiện tiên quyết rõ ràng:
- `PROCESSING → PAID`: Chỉ được phép nếu `payment_id` chưa tồn tại
- Nếu message duplicate: Check thấy đã PAID → no-op (idempotent)

---

## 4. Exactly-Once Semantics: Các Giải Pháp

### 4.1. End-to-End Exactly-Once (The "Holy Grail")

Exactly-once đòi hỏi cả 3 thành phần phối hợp:

```
┌──────────────┐          ┌──────────────┐          ┌──────────────┐
│   PRODUCER   │          │    BROKER    │          │  CONSUMER    │
│              │          │              │          │              │
│ Deduplicate  │◀────────▶│  Transaction │◀────────▶│ Deduplicate  │
│   Outbound   │          │   Log + ACK  │          │   Inbound    │
└──────────────┘          └──────────────┘          └──────────────┘
```

**Kafka's Exactly-Once (EOS) Implementation:**

```
┌────────────────────────────────────────────────────────────────┐
│                     PRODUCER                                    │
│  ┌─────────────┐    ┌─────────────┐    ┌────────────────────┐ │
│  │ idempotent  │───▶│ transaction │───▶│ exactly-once       │ │
│  │ producer    │    │ coordinator │    │ delivery guarantee │ │
│  └─────────────┘    └─────────────┘    └────────────────────┘ │
└────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────────┐
│                     KAFKA BROKER                                │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              TRANSACTION LOG                             │  │
│  │  PID: 12345 | Seq: 1 | Topic: orders | Partition: 0      │  │
│  │  PID: 12345 | Seq: 2 | Topic: orders | Partition: 0      │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────────┐
│                     CONSUMER                                    │
│  ┌─────────────┐    ┌─────────────┐    ┌────────────────────┐ │
│  │ consume     │───▶│ process     │───▶│ commit offset +    │ │
│  │ from source │    │ + produce   │    │ output transaction │ │
│  └─────────────┘    └─────────────┘    └────────────────────┘ │
└────────────────────────────────────────────────────────────────┘
```

**Cơ chế của Kafka EOS:**

1. **Producer ID (PID):** Mỗi producer instance được gán PID unique
2. **Sequence Numbers:** Mỗi message có sequence number tăng dần theo partition
3. **Deduplication at Broker:** Broker track (PID, Seq) → reject duplicates
4. **Transactions:** Producer gửi `initTransactions()` → broker tạo transactional ID
5. **Atomic Commit:** `sendOffsetsToTransaction()` + `commitTransaction()` atomic

### 4.2. Consumer-Side Deduplication

Khi broker không hỗ trợ EOS (như RabbitMQ, SQS), deduplication phải xử lý ở consumer.

#### 4.2.1. Deduplication Window Pattern

```
Message Stream:  A ──▶ B ──▶ C ──▶ B(duplicate) ──▶ D ──▶ A(duplicate)
                     │
                     ▼
         ┌───────────────────────┐
         │   Deduplication Window │
         │   (e.g., last 5 min)   │
         ├───────────────────────┤
         │  Seen: {A, B, C}      │
         └───────────────────────┘
```

**Implementation:**

```
Strategy 1: In-Memory Cache (Guava/Caffeine)
├── Pros: Fast, no external dependency
├── Cons: Lost on restart, limited by memory
└── Best for: Short windows, single instance

Strategy 2: Redis Set with TTL
├── Pros: Survive restart, distributed
├── Cons: Network latency, Redis availability
└── Best for: Medium windows, multi-instance

Strategy 3: Database Unique Constraint
├── Pros: Durable, transactional
├── Cons: Write amplification, DB load
└── Best for: Long windows, critical data
```

#### 4.2.2. Bloom Filters cho Large-Scale Deduplication

Khi window quá lớn để lưu trữ tất cả IDs:

```
┌─────────────────────────────────────────────────────┐
│                 Bloom Filter                        │
├─────────────────────────────────────────────────────┤
│  Bit Array: [0,1,0,0,1,0,1,1,0,0,1,0,0,1,0,1,...]  │
│                                                     │
│  Hash Functions: h1, h2, h3                        │
│                                                     │
│  Check "msg-123":                                   │
│    h1("msg-123") = 5   → bit[5] = 1 ✓              │
│    h2("msg-123") = 12  → bit[12] = 0 ✗             │
│    → Definitely NOT seen                            │
│                                                     │
│  Check "msg-456":                                   │
│    h1("msg-456") = 3   → bit[3] = 1 ✓              │
│    h2("msg-456") = 7   → bit[7] = 1 ✓              │
│    h3("msg-456") = 15  → bit[15] = 1 ✓             │
│    → Probably seen (might be false positive)        │
└─────────────────────────────────────────────────────┘
```

**Trade-offs của Bloom Filter:**

| Parameter | Effect | Typical Value |
|-----------|--------|---------------|
| Bit array size (m) | Larger = lower false positive | 1M - 100M bits |
| Hash functions (k) | More = slower but more accurate | 3-7 |
| Expected items (n) | Determines error rate | Based on throughput |
| False positive rate | p ≈ (1 - e^(-kn/m))^k | 0.1% - 1% |

### 4.3. At-Least-Once + Idempotency = Exactly-Once

**Pattern được khuyến nghị cho hầu hết systems:**

```
┌─────────────────────────────────────────────────────────────┐
│                    SYSTEM DESIGN                             │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  1. Broker: At-least-once delivery (reliable, fast)         │
│                                                              │
│  2. Consumer: Idempotent processing                          │
│     ├── Extract idempotency key from message                │
│     ├── Check if already processed                          │
│     ├── If yes: skip + ack                                  │
│     ├── If no: process + store key + ack (atomic)           │
│     └── Handle failures with retry + exponential backoff    │
│                                                              │
│  3. Database: Upsert/Conditional updates                    │
│     ├── Natural idempotency via constraints                 │
│     └── Optimistic locking for concurrent updates           │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 5. So Sánh Các Giải Pháp Exactly-Once

### 5.1. Kafka EOS vs Consumer-Side Deduplication

| Aspect | Kafka EOS (Transactions) | Consumer-Side Dedup |
|--------|--------------------------|---------------------|
| **Complexity** | High (requires transaction coordinator) | Medium |
| **Performance** | Lower (2PC overhead) | Higher |
| **Failure recovery** | Built-in (transaction abort) | Manual handling |
| **Cross-system** | Limited to Kafka ecosystem | Universal |
| **Ordering** | Maintained within transaction | Depends on implementation |
| **Best for** | Kafka-to-Kafka pipelines | Heterogeneous systems |

### 5.2. Storage Options cho Deduplication

| Storage | Latency | Durability | Scalability | Cost | Best For |
|---------|---------|------------|-------------|------|----------|
| **Local Cache** | μs | Low | Single node | Free | Non-critical, short window |
| **Redis** | ms | Medium | Horizontal | Low-Medium | General purpose |
| **Cassandra** | ms | High | Massive | Medium | Large window, high throughput |
| **PostgreSQL** | ms | Very High | Vertical | Medium | Critical data, ACID needed |

---

## 6. Rủi Ro, Anti-Patterns, và Pitfall

### 6.1. Common Failure Modes

#### 6.1.1. The "Dual Write" Problem

```
❌ ANTI-PATTERN: Non-atomic dual write

Consumer nhận message:
  1. Write to database
  2. Write dedup key to Redis
  
Crash giữa 1 và 2:
  → Message được xử lý, nhưng key chưa lưu
  → Redeliver → Duplicate processing

✅ SOLUTION: Single transactional write

  1. BEGIN TRANSACTION
  2. INSERT INTO processed_events (key, processed_at)
  3. INSERT/UPDATE business_table ...
  4. COMMIT
```

#### 6.1.2. Clock Skew trong Distributed Deduplication

```
Problem: Consumer A và B có clock khác nhau

Consumer A (clock fast): 
  Thấy event từ 10:00:00, lưu với TTL 5 phút
  
Consumer B (clock slow):
  Thấy cùng event từ 10:00:01 (wall clock khác)
  Kiểm tra: "chưa thấy trong window" → xử lý lại

✅ SOLUTION: Logical clocks hoặc broker timestamps
```

#### 6.1.3. Window Size Mismatch

```
❌ ANTI-PATTERN: Window quá ngắn

Window: 1 minute
Retry delay của broker: 2 minutes
  → Message redeliver sau khi đã rời window
  → Duplicate processing

✅ SOLUTION: Window > max retry delay + clock skew buffer
```

### 6.2. Idempotency Key Generation Pitfalls

| Pitfall | Why It's Bad | Solution |
|---------|--------------|----------|
| **Client-generated UUID** không persist | Crash → retry với key mới → duplicate | Persist key trước khi gửi request |
| **Key quá generic** | Different operations share key → lost updates | Include operation type + entity ID |
| **Key quá specific** | Same operation, different keys → no dedup | Scope key đúng business boundary |

---

## 7. Khuyến Nghị Thực Chiến Production

### 7.1. Decision Framework

```
Bắt đầu: Bạn cần exactly-once semantics?
           │
           ▼
    ┌──────────────┐
    │  Critical?   │──NO──▶ At-least-once + monitoring
    │  Financial?  │              + manual reconciliation
    └──────────────┘
           │ YES
           ▼
    ┌──────────────┐
    │ Kafka only?  │──YES──▶ Kafka EOS (transactions)
    └──────────────┘
           │ NO
           ▼
    ┌──────────────┐
    │ High volume? │──YES──▶ Consumer-side + Bloom Filter
    │  > 100K/s?   │              + short window
    └──────────────┘
           │ NO
           ▼
    Consumer-side + Database dedup table
    (transactional, reliable)
```

### 7.2. Implementation Checklist

#### Producer Side:
- [ ] Generate idempotency key trước khi gửi request
- [ ] Persist key (client-side) trước network call
- [ ] Include key trong message metadata
- [ ] Handle 409 Conflict (already processed)

#### Consumer Side:
- [ ] Extract và validate idempotency key
- [ ] Atomic check-and-process (database transaction)
- [ ] Idempotent business logic (UPSERT, conditional UPDATE)
- [ ] Dead letter queue cho non-retryable errors
- [ ] Metrics: duplication rate, processing latency

#### Infrastructure:
- [ ] Monitoring: message lag, retry rate, dedup hit rate
- [ ] Alert: duplication spike, window saturation
- [ ] Runbook: manual dedup, window extension

### 7.3. Mẫu Code Tham Khảo (Tối Thiểu)

**Consumer với database deduplication:**

```java
@Transactional
public void processPayment(PaymentMessage msg) {
    // 1. Deduplication check (unique constraint on idempotency_key)
    try {
        eventLogRepository.insert(new EventLog(
            msg.getIdempotencyKey(),
            msg.getEventType(),
            Instant.now()
        ));
    } catch (DuplicateKeyException e) {
        log.info("Duplicate event ignored: {}", msg.getIdempotencyKey());
        return; // Idempotent: already processed
    }
    
    // 2. Idempotent business logic
    // UPSERT: nếu đã tồn tại thì không đổi, nếu chưa thì insert
    paymentRepository.upsert(Payment.builder()
        .id(msg.getPaymentId())
        .amount(msg.getAmount())
        .status(PAID)
        .processedAt(Instant.now())
        .build());
}
```

**Giải thích:**
- `event_log` table có unique constraint trên `idempotency_key`
- DuplicateKeyException → message đã được xử lý → skip
- `upsert` đảm bảo business operation cũng idempotent
- Cả 2 operation trong cùng transaction → atomic

---

## 8. Kết Luận

### Bản Chất Cốt Lõi

1. **Exactly-once là end-to-end guarantee**, không phải feature của message broker. Đòi hỏi coordination giữa producer, broker, và consumer.

2. **Idempotency là prerequisite** cho bất kỳ exactly-once implementation nào. Không có idempotency, không thể đạt exactly-once.

3. **At-least-once + Idempotency = Exactly-once** trong thực tế. Pattern này đơn giản hơn, hiệu quả hơn, và áp dụng được cho mọi message broker.

4. **Trade-off chính:** Complexity vs Reliability. Kafka EOS phức tạp và chậm hơn nhưng tự động handle nhiều edge case. Consumer-side dedup đơn giản hơn nhưng đòi hỏi careful implementation.

5. **Storage choice quyết định scalability:** In-memory cho speed, database cho durability, Bloom filter cho large-scale với acceptable false positives.

### Khi Nào Dùng Gì

| Scenario | Recommendation |
|----------|----------------|
| Financial transactions | Database dedup + strict ACID |
| Order processing | Consumer-side + Redis window |
| Log aggregation | At-least-once, skip dedup |
| Kafka-only pipeline | Kafka EOS nếu complexity acceptable |
| Multi-protocol | Consumer-side idempotency |

### Final Thought

> Đừng chase "exactly-once" như một ideal. Trong distributed systems, exactly-once là illusion được tạo ra bởi careful deduplication và idempotent design. Focus vào making your operations idempotent first - exactly-once sẽ theo sau.
