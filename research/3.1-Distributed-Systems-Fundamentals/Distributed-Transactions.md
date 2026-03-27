# Distributed Transactions: 2PC, 3PC, Saga Pattern và Idempotency

## 1. Mục tiêu của Task

Hiểu sâu cơ chế đảm bảo tính nhất quán dữ liệu trong hệ thống phân tán, phân tích các phương pháp giao dịch phân tán (2PC, 3PC, Saga), thiết kế compensating transactions và xây dựng idempotency trong kiến trúc microservices.

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 Bài Toán Cốt Lõi: ACID trong Môi Trường Phân Tán

Trong hệ thống monolith với single database, ACID được đảm bảo tự nhiên bởi database engine. Khi phân tán:

```
┌─────────────────────────────────────────────────────────────────┐
│                     DISTRIBUTED TRANSACTION                     │
├─────────────────────────────────────────────────────────────────┤
│  Service A          Service B          Service C               │
│  ┌─────────┐       ┌─────────┐       ┌─────────┐               │
│  │  DB A   │◄─────►│  DB B   │◄─────►│  DB C   │               │
│  └─────────┘       └─────────┘       └─────────┘               │
│       │                 │                 │                    │
│       └─────────────────┴─────────────────┘                    │
│                    Network (unreliable)                        │
└─────────────────────────────────────────────────────────────────┘
```

**Vấn đề cơ bản:** Network partition, node failure, message loss khiến atomicity và consistency trở thành bài toán phức tạp.

> **Nguyên tắc quan trọng:** Trong hệ thống phân tán, "perfect consistency" và "100% availability" không thể đạt được đồng thủ (CAP theorem). Distributed transaction là cơ chế "đủ tốt" để cân bằng giữa consistency và availability.

---

### 2.2 Two-Phase Commit (2PC): Cơ Chế Đồng Thuận Cổ Điển

#### Kiến Trúc

```
                    ┌─────────────────┐
                    │  Coordinator    │
                    │  (TM - Transaction │
                    │   Manager)      │
                    └────────┬────────┘
                             │
            ┌────────────────┼────────────────┐
            │                │                │
     ┌──────▼──────┐  ┌──────▼──────┐  ┌──────▼──────┐
     │  Cohort A   │  │  Cohort B   │  │  Cohort C   │
     │  (RM -      │  │  (RM)       │  │  (RM)       │
     │   Resource  │  │             │  │             │
     │   Manager)  │  │             │  │             │
     └─────────────┘  └─────────────┘  └─────────────┘
```

#### Luồng Xử Lý Chi Tiết

**Phase 1: Voting Phase**

```
Coordinator                    Cohorts (A, B, C)
     │                               │
     │── 1. PREPARE ────────────────►│
     │   (with transaction context)  │
     │                               │
     │◄── 2. YES/NO ────────────────│
     │   (vote result)               │
     │                               │
```

- Coordinator gửi PREPARE đến tất cả cohorts
- Mỗi cohort thực hiện local transaction nhưng **chưa commit** (giữ locks)
- Cohort trả về YES (có thể commit) hoặc NO (không thể commit - abort)

**Phase 2: Commit/Abort Phase**

```
Nếu tất cả vote YES:                    Nếu có ít nhất 1 vote NO:
┌─────────────────────────┐            ┌─────────────────────────┐
│  3. COMMIT              │            │  3. ABORT               │
│  (broadcast to all)     │            │  (broadcast to all)     │
│                         │            │                         │
│  4. ACK ───────────────►│            │  4. ACK ───────────────►│
│     (confirmation)      │            │     (confirmation)      │
└─────────────────────────┘            └─────────────────────────┘
```

#### Bản Chất "Nắp Máy"

**Write-Ahead Logging (WAL):**
Cả coordinator và cohorts đều phải ghi log trước khi trả lờii:

```
Cohort Log Entry (Phase 1):
┌─────────────────────────────────────────────────────────────┐
│ [LSN: 1001] PREPARE record                                  │
│   - Transaction ID: TX_123                                  │
│   - State: PREPARED                                         │
│   - Data modifications: (before/after images)               │
│   - Vote: YES/NO                                            │
└─────────────────────────────────────────────────────────────┘

Coordinator Log Entry (Phase 2):
┌─────────────────────────────────────────────────────────────┐
│ [LSN: 2001] DECISION record                                 │
│   - Transaction ID: TX_123                                  │
│   - Decision: COMMIT/ABORT                                  │
│   - Participants: [A, B, C]                                 │
└─────────────────────────────────────────────────────────────┘
```

**Tại sao cần log?** Recovery sau crash:
- Nếu cohort crash sau khi vote YES: Sau restart, đọc log, biết mình đã PREPARE, contact coordinator để hỏi kết quả
- Nếu coordinator crash: Sau restart, đọc log để biết transaction nào chưa hoàn thành, tiếp tục quy trình

---

### 2.3 Three-Phase Commit (3PC): Giảm Blocking

#### Vấn Đề Củaa 2PC

2PC là **blocking protocol**: Nếu coordinator crash sau khi nhận YES votes nhưng trước khi gửi COMMIT, cohorts giữ locks và chờ vô thờii hạn.

#### Giải Pháp: Thêm Phase CanCommit

```
┌─────────────────────────────────────────────────────────────────┐
│                      3PC PROTOCOL                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Phase 1: CanCommit                                             │
│  Coordinator ──CanCommit?──► Cohorts                            │
│  ◄──────────Yes/No─────────                                     │
│  (Chỉ kiểm tra khả năng, chưa giữ locks)                        │
│                                                                 │
│  Phase 2: PreCommit                                             │
│  Coordinator ──PreCommit──► Cohorts                             │
│  ◄──────────ACK───────────                                      │
│  (Giữ locks, ghi log PREPARED)                                  │
│                                                                 │
│  Phase 3: DoCommit/Abort                                        │
│  Coordinator ──DoCommit──► Cohorts                              │
│  ◄──────────ACK───────────                                      │
│  (Hoặc timeout sau đó tự commit)                                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

#### Cơ Chế Timeout Quyết Định

| Scenario | Hành Động của Cohort |
|----------|---------------------|
| Timeout sau Phase 1 (CanCommit) | Abort (an toàn) |
| Timeout sau Phase 2 (PreCommit) | **Commit** (vì đã nhận PreCommit, coordinator chắc chắn đã quyết định) |
| Coordinator crash, cohort nhận PreCommit | Có thể tự commit sau timeout mà không cần coordinator |

> **Điểm mấu chốt:** 3PC đánh đổi 1 round-trip để giảm blocking window. Nhưng vẫn không hoàn hảo - nếu coordinator crash và cohort chưa nhận PreCommit, vẫn cần recovery.

---

### 2.4 Saga Pattern: Chấp Nhận Eventual Consistency

#### Triết Lý Thiết Kế

Thay vì giữ ACID trong transaction lớn, Saga chia thành **chuỗi local transactions**:

```
┌─────────────────────────────────────────────────────────────────┐
│                      SAGA PATTERN                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  T1 ──► T2 ──► T3 ──► T4 ──► ... ──► Tn                        │
│  │      │      │      │            │                           │
│  ▼      ▼      ▼      ▼            ▼                           │
│  S1     S2     S3     S4          Sn                           │
│  (Local transactions, mỗi cái commit ngay)                     │
│                                                                 │
│  Nếu T3 fail:                                                  │
│  T1 ──► T2 ──► ❌T3                                             │
│            ▲                                                   │
│            └── C3 ──► C2 ──► C1 (Compensating - đảo ngược)     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

#### Hai Cách Triển Khai Saga

**1. Choreography-Based (Event-Driven)**

```
┌──────────┐     OrderCreated      ┌──────────┐
│  Order   │──────────────────────►│ Payment  │
│  Service │                       │  Service │
│          │◄──────────────────────│          │
│          │    PaymentProcessed   │          │
│          │                       │          │
│          │     PaymentFailed     │          │
│          │◄──────────────────────│          │
│          │                       │          │
│          │  CompensateOrder      │          │
│          │◄──────────────────────│          │
└──────────┘                       └──────────┘
        │                               │
        │  InventoryReserved            │
        └──────────────────────────────►│
                ▲                       │
                │ InventoryReleased     │
                └───────────────────────┘
```

- Mỗi service tự định nghĩa event nó publish và subscribe
- Không cần orchestrator trung tâm
- **Trade-off:** Khó theo dõi flow tổng thể, coupling giữa services qua event schema

**2. Orchestration-Based (Central Coordinator)**

```
                    ┌─────────────────┐
                    │  Saga           │
                    │  Orchestrator   │
                    │  (Process       │
                    │   Manager)      │
                    └────────┬────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
   ┌────▼─────┐        ┌─────▼────┐         ┌─────▼────┐
   │  Order   │        │ Payment  │         │ Inventory│
   │  Service │        │  Service │         │  Service │
   └──────────┘        └──────────┘         └──────────┘

Flow:
1. Orchestrator ──CreateOrder──► Order Service
2. ◄────────────OrderCreated────
3. ──ProcessPayment────────────► Payment Service
4. ◄────────────PaymentCompleted
5. ──ReserveInventory──────────► Inventory Service
6. ◄────────────InventoryReserved
7. ──ConfirmOrder──────────────► Order Service
```

- Orchestrator quản lý toàn bộ flow
- Dễ monitoring, debugging, retry
- **Trade-off:** Orchestrator là single point of complexity (không phải SPOF vì có thể replicate)

---

### 2.5 Compensating Transactions: Nghệ Thuật "Undo"

#### Bản Chất

Compensating transaction không phải "rollback" database truyền thống mà là **business operation đảo ngược**:

```
┌─────────────────────────────────────────────────────────────────┐
│            FORWARD vs COMPENSATING ACTIONS                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Forward Action          │  Compensating Action                 │
│  ────────────────────────┼────────────────────────               │
│  Debit $100 from user    │  Credit $100 back to user            │
│  Reserve inventory       │  Release reservation                 │
│  Create shipment         │  Cancel shipment + notify warehouse  │
│  Send email notification │  Send correction email               │
│  Update loyalty points   │  Deduct loyalty points               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

#### Đặc Điểm Quan Trọng

1. **Không phải technical rollback:** Có thể có side effects không thể xóa (email đã gửi, notification đã push)
2. **Business logic:** Compensation được thiết kế bởi domain expert, không tự động
3. **Có thể fail:** Compensation cũng cần retry mechanism riêng
4. **Không instantaneous:** Có thể mất thờii gian (approval, manual review)

---

### 2.6 Idempotency: Chìa Khóa Reliability

#### Định Nghĩa Chính Xác

```
f(x) = f(f(x)) = f(f(f(x))) = ...

Trong HTTP API:
POST /orders {idempotency-key: "abc-123"}

Lần 1: 201 Created, Order #12345
Lần 2: 201 Created, Order #12345 (cùng kết quả, không tạo order mới)
Lần 3: 201 Created, Order #12345
```

#### Cơ Chế Triển Khai

**1. Idempotency Key Pattern**

```
Client                                  Server
  │                                       │
  │  POST /payment                        │
  │  Idempotency-Key: uuid-789            │
  │  ───────────────────────────────────► │
  │                                       │
  │                              ┌─────────────────┐
  │                              │ 1. Check Redis: │
  │                              │    uuid-789?    │
  │                              │                 │
  │                              │ 2. If not exist:│
  │                              │    - Process    │
  │                              │    - Store      │
  │                              │      result     │
  │                              │    - Set TTL    │
  │                              │                 │
  │                              │ 3. If exists:   │
  │                              │    - Return     │
  │                              │      cached     │
  │                              │      result     │
  │                              └─────────────────┘
  │◄──────────────────────────────────────│
  │  200 OK / 201 Created                 │
  │  (Same result for same key)           │
```

**2. Storage Strategy**

```
Redis Key Structure:
┌─────────────────────────────────────────────────────────────┐
│ idempotency:{key}                                           │
│   ├── request_hash (để detect request khác cùng key)       │
│   ├── response_body                                          │
│   ├── response_status                                        │
│   ├── created_at                                             │
│   └── ttl: 24h (configurable)                                │
└─────────────────────────────────────────────────────────────┘
```

**3. Edge Cases Xử Lý**

| Scenario | Xử Lý |
|----------|-------|
| Same key, different payload | 409 Conflict hoặc overwrite policy |
| Concurrent requests cùng key | Distributed lock hoặc DB unique constraint |
| Request đang xử lý, có request mới cùng key | 409 Conflict + Retry-After header |
| Key expired | Xử lý như request mới |

---

## 3. So Sánh Các Phương Pháp

### 3.1 Ma Trận So Sánh Chi Tiết

| Tiêu Chí | 2PC | 3PC | Saga (Choreography) | Saga (Orchestration) |
|----------|-----|-----|---------------------|---------------------|
| **Consistency** | Strong | Strong | Eventual | Eventual |
| **Availability** | Thấp (blocking) | Trung bình | Cao | Cao |
| **Latency** | Cao (2-3 round trips) | Cao hơn 2PC | Thấp (async) | Thấp (async) |
| **Complexity** | Trung bình | Cao | Trung bình | Cao |
| **Failure Recovery** | Log-based | Log-based | Compensation | Compensation |
| **Monitoring** | Dễ | Dễ | Khó (distributed) | Dễ (centralized) |
| **Coupling** | Tight (shared TM) | Tight | Loose (event) | Loose (explicit) |
| **Throughput** | Thấp | Thấp | Cao | Cao |

### 3.2 Khi Nào Dùng Gì?

```
┌─────────────────────────────────────────────────────────────────┐
│                    DECISION TREE                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Yêu cầu Strong Consistency?                                    │
│  │                                                              │
│  ├─ YES ──► Chấp nhận Blocking?                                 │
│  │           │                                                  │
│  │           ├─ YES ──► 2PC/3PC                                 │
│  │           │         (Financial core, inventory critical)     │
│  │           │                                                  │
│  │           └─ NO ──► Redesign (CQRS, event sourcing)         │
│  │                                                              │
│  └─ NO ──► Cần Orchestrator visibility?                         │
│            │                                                    │
│            ├─ YES ──► Saga Orchestration                        │
│            │         (Order processing, booking flow)           │
│            │                                                    │
│            └─ NO ──► Saga Choreography                          │
│                      (Event-driven, loose coupling)             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. Rủi Ro, Anti-Patterns và Lỗi Thường Gặp

### 4.1 2PC/3PC - Các Failure Mode

**1. Heuristic Completion (2PC)**

```
Scenario: Cohort tự quyết định commit/abort vì timeout

Coordinator: "Tôi chưa quyết định COMMIT hay ABORT"
Cohort A:    "Tôi đã tự COMMIT rồi (heuristic commit)"
Cohort B:    "Tôi vẫn đang chờ quyết định"

→ **Inconsistency!** Cần manual intervention
```

**2. Orphan Transaction**

Transaction không có coordinator (coordinator crash permanently) → cohorts giữ locks vô thờii hạn.

**Giải pháp:** Timeout + heuristic decision + admin alert.

### 4.2 Saga - Các Trap Phổ Biến

**1. Missing Compensation**

```java
// ❌ ANTI-PATTERN: Quên compensating action
@Saga
public void processOrder() {
    orderService.create(order);        // T1 - có compensation
    paymentService.charge(payment);    // T2 - có compensation  
    emailService.sendConfirmation(order); // ❌ Không có compensation!
    loyaltyService.addPoints(order);   // T4 - có compensation
}

// ✅ PATTERN: Mọi step đều phải có compensation
// Email đã gửi thì không "unsend" được, nên design:
// - Gửi email ở cuối cùng (sau khi mọi thứ chắc chắn thành công)
// - Hoặc gửi correction email nếu saga fail
```

**2. Compensation Failure Loop**

```
T1: Create order ✓
T2: Charge payment ✓
T3: Reserve inventory ✗ (fail)
C3: Release inventory ✓
C2: Refund payment ✗ (fail - payment gateway timeout)
C2: Refund payment (retry) ✗
C2: Refund payment (retry) ✗

→ **Dead saga!** Cần: DLQ (Dead Letter Queue) + manual intervention
```

**3. Shared State trong Saga**

```java
// ❌ ANTI-PATTERN: Step phụ thuộc vào state từ step trước
@Saga
public void process() {
    var result1 = step1();  // Trả về intermediate ID
    var result2 = step2(result1.id);  // ❌ Nếu step2 retry, result1.id có thể đổi!
    step3(result2.id);
}

// ✅ PATTERN: Deterministic identifiers
@Saga
public void process(String businessId) {
    step1(businessId);  // Dùng business key
    step2(businessId);
    step3(businessId);
}
```

### 4.3 Idempotency - Lỗi Thiết Kế

**1. Key Collision**

```java
// ❌ ANTI-PATTERN: Client tự sinh key không unique
String key = UUID.randomUUID().toString(); // Mỗi lần gọi là key khác

// ✅ PATTERN: Deterministic key từ business context
String key = hash(userId + orderId + timestamp); // Cùng context = cùng key
```

**2. Race Condition**

```java
// ❌ ANTI-PATTERN: Check-then-act không atomic
if (!cache.exists(key)) {  // Request A check → false
    process(request);      // Request B check → false
    cache.set(key, result); // Cả A và B đều process!
}

// ✅ PATTERN: Atomic check-and-set hoặc distributed lock
// Redis: SET key value NX EX 3600 (set nếu not exists)
// Hoặc DB unique constraint
```

---

## 5. Khuyến Nghị Thực Chiến Production

### 5.1 Saga Implementation với Java

**Framework phổ biến:**

| Framework | Đặc Điểm | Khi Nào Dùng |
|-----------|----------|--------------|
| **Camunda** | BPMN engine, visual designer | Complex business processes, cần business user involvement |
| **Temporal** | Code-first, durable execution | Developer-friendly, complex async workflows |
| **Axon Saga** | Event sourcing native | Dùng Axon framework |
| **Spring State Machine** | Lightweight, state-based | Simple flows, Spring ecosystem |
| **Custom** | Full control | Specific requirements, team có expertise |

### 5.2 Monitoring và Observability

**Metrics cần track:**

```
saga_started_total{flow="order_processing"}
saga_completed_total{flow="order_processing", status="success|failed"}
saga_duration_seconds{flow="order_processing", step="*"}
saga_compensation_executed_total{flow="order_processing", step="*"}
saga_compensation_failed_total{flow="order_processing", step="*"}
idempotency_cache_hit_total
idempotency_cache_miss_total
```

**Distributed Tracing:**

```
Trace: order-12345
├── Span: saga_orchestrator (trace_id, span_id)
│   ├── Span: step_1_create_order (parent_id)
│   ├── Span: step_2_process_payment (parent_id)
│   │   └── Event: payment_processed
│   ├── Span: step_3_reserve_inventory (parent_id)
│   │   └── Tag: error=true
│   └── Span: compensate_step_2 (parent_id)
│       └── Event: refund_initiated
```

### 5.3 Security Considerations

**1. Idempotency Key Trust:**
```java
// ❌ Không tin tưởng client-generated key hoàn toàn
// ✅ Validate: Key format, entropy, rate limit per client
```

**2. Compensation Authorization:**
```java
// Compensation có thể là operation nhạy cảm (refund)
// ✅ Thêm authorization check trong compensating action
// ✅ Audit log mọi compensation
```

### 5.4 Performance Tuning

**2PC Optimization:**
- Presumed Abort: Nếu coordinator không tìm thấy record của transaction, coi như abort
- Read-only optimization: Cohort chỉ read thì không cần phase 2

**Saga Optimization:**
- Parallel steps: Các step không phụ thuộc chạy song song
- Batch compensation: Gom nhiều compensation chạy 1 lần

---

## 6. Kết Luận

### Bản Chất Cốt Lõi

| Pattern | Bản Chất | Trade-off Chính |
|---------|----------|-----------------|
| **2PC/3PC** | Distributed consensus qua coordinator | Consistency cao ↔ Availability thấp |
| **Saga** | Eventual consistency qua compensation | Availability cao ↔ Complexity quản lý |
| **Idempotency** | Safe retry thông qua deduplication | Reliability ↔ Storage overhead |

### Quyết Định Kiến Trúc

> **Nguyên tắc vàng:** Đừng dùng distributed transaction khi có thể tránh. Thiết kế bounded context sao cho mỗi context tự consistent, giao tiếp qua events.

**Hệ thống hiện đại (2024+):**
- **Không dùng 2PC/3PC** cho business logic thông thường (quá nặng, khó scale)
- **Saga orchestration** là default pattern cho microservices
- **Idempotency** là non-negotiable requirement cho mọi mutation API
- **Event sourcing + CQRS** là extension tự nhiên của Saga pattern

### Anti-Patterns Tuyệt Đối Tránh

1. Distributed transaction qua nhiều bounded contexts
2. Saga không có compensation strategy
3. API không idempotent
4. Compensation không có retry và DLQ
5. Coordinator là SPOF không replicated

---

## 7. Tài Liệu Tham Khảo

- "Distributed Systems" - Maarten van Steen & Andrew S. Tanenbaum
- "Enterprise Integration Patterns" - Gregor Hohpe & Bobby Woolf
- "Building Microservices" - Sam Newman
- "Designing Data-Intensive Applications" - Martin Kleppmann
- Saga pattern original paper (1987) - Hector Garcia-Molina & Kenneth Salem
- 2PC specification - X/Open XA standard
