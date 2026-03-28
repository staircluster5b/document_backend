# Saga Pattern Implementation: Orchestration vs Choreography, Compensation

## 1. Mục tiêu của Task

Hiểu sâu bản chất Saga Pattern - pattern phân tán quản lý transaction dài hạn (long-running transactions) trong kiến trúc microservices. Tập trung vào:
- Cơ chế hoạt động ở tầng kiến trúc
- So sánh Orchestration vs Choreography
- Chiến lược Compensation và xử lý failure
- Thực tiễn production và các rủi ro ẩn

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1. Vấn Đề Saga Pattern Giải Quyết

Trong monolith, ACID transaction đảm bảo tính nhất quán. Trong microservices:
- Mỗi service có database riêng
- Không thể dùng 2PC (Two-Phase Commit) vì lock resource quá lâu
- Network partition và partial failure là chuyện thường ngày

**Saga Pattern** chia transaction lớn thành chuỗi các local transaction. Nếu một bước thất bại, thực hiện **compensating transactions** để rollback các bước đã hoàn thành.

### 2.2. Bản Chất Củi (Core Mechanism)

```
┌─────────────────────────────────────────────────────────────────┐
│                    SAGA TRANSACTION FLOW                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   T1 Success ──► T2 Success ──► T3 FAIL                        │
│      │              │              │                           │
│      ▼              ▼              ▼                           │
│   [Commit]       [Commit]      [Abort]                         │
│      │              │              │                           │
│      └──────────────┴──────► C2 ──► C1 (Compensation Chain)    │
│                              (Reverse order)                   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Nguyên tắc bất biến:**
1. Mỗi local transaction phải idempotent (có thể retry)
2. Compensation phải undo được tác động của original transaction
3. Compensation có thể fail → cần retry mechanism
4. Thứ tự compensation ngược với thứ tự execution

### 2.3. Saga Log - "Source of Truth"

```
┌──────────────────────────────────────────────────────────────┐
│                      SAGA LOG ENTRY                          │
├──────────────────────────────────────────────────────────────┤
│ saga_id:      uuid (correlation key)                         │
│ step:         integer (execution order)                      │
│ service:      service_name                                   │
│ action:       EXECUTE | COMPENSATE                           │
│ status:       PENDING | SUCCESS | FAILED | COMPENSATED       │
│ payload:      JSON (business data)                           │
│ timestamp:    epoch_millis                                   │
│ parent_id:    saga_id của saga cha (nesting)                 │
└──────────────────────────────────────────────────────────────┘
```

> **Critical:** Saga log phải durable và replicated. Mất saga log = mất khả năng recover.

---

## 3. Orchestration vs Choreography

### 3.1. Kiến Trúc Orchestration (Command-Oriented)

```
┌────────────────────────────────────────────────────────────────┐
│                    ORCHESTRATION PATTERN                       │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│                    ┌─────────────┐                              │
│                    │  Saga       │                              │
│                    │ Orchestrator│◄────── Saga Definition       │
│                    │  (Brain)    │         (State Machine)      │
│                    └──────┬──────┘                              │
│                           │                                     │
│         ┌─────────────────┼─────────────────┐                   │
│         │ Command         │ Command         │ Command           │
│         ▼                 ▼                 ▼                   │
│    ┌─────────┐      ┌─────────┐      ┌─────────┐               │
│    │Service A│      │Service B│      │Service C│               │
│    │(Payment)│      │(Inventory)│    │(Shipping)│              │
│    └────┬────┘      └────┬────┘      └────┬────┘               │
│         │ Event          │ Event          │ Event               │
│         └─────────────────┴─────────────────┘                   │
│                           │                                     │
│                    ┌──────┴──────┐                              │
│                    │  Event Store │                             │
│                    │  (Kafka/DB)  │                             │
│                    └─────────────┘                              │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

**Luồng xử lý:**
1. Orchestrator nhận request, khởi tạo saga instance
2. Gửi command tới Service A, chờ event phản hồi
3. Dựa trên kết quả, quyết định bước tiếp theo (routing logic)
4. Nếu fail, gửi compensation commands theo thứ tự ngược

**State Machine của Orchestrator:**

```
                    ┌─────────┐
                    │ STARTED │
                    └────┬────┘
                         │ execute(T1)
                         ▼
              ┌──────────────────────┐
              │   AWAITING_T1_RESULT │
              └───────┬──────┬───────┘
                      │      │
                 success│      │fail
                      ▼      ▼
              ┌─────────┐  ┌──────────────┐
              │ T1_DONE │  │ COMPENSATING │
              └────┬────┘  │     T1       │
                   │       └──────────────┘
         execute(T2)│
                   ▼
              ... (continue)
```

### 3.2. Kiến Trúc Choreography (Event-Driven)

```
┌────────────────────────────────────────────────────────────────┐
│                    CHOREOGRAPHY PATTERN                        │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────┐      ┌─────────┐      ┌─────────┐                 │
│  │Service A│─────►│Service B│─────►│Service C│                 │
│  │(Payment)│Event │(Inventory)│Event│(Shipping)│                │
│  │         │      │         │      │         │                 │
│  │ Listens │◄─────│ Listens │◄─────│ Listens │                 │
│  │Failure  │      │Failure  │      │Failure  │                 │
│  └────┬────┘      └────┬────┘      └────┬────┘                 │
│       │                │                │                       │
│       └────────────────┴────────────────┘                       │
│                    │                                            │
│                    ▼                                            │
│           ┌─────────────┐                                       │
│           │ Event Bus   │                                       │
│           │ (Kafka/     │                                       │
│           │  RabbitMQ)  │                                       │
│           └─────────────┘                                       │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

**Luồng xử lý:**
1. Service A hoàn thành work, publish event "PaymentProcessed"
2. Service B subscribe event, thực hiện work, publish "InventoryReserved"
3. Service C subscribe và tiếp tục
4. Nếu Service C fail, publish "ShippingFailed"
5. Service B nhận event, thực hiện compensation
6. Service A nhận event từ B, thực hiện compensation

**Event Contract (Quan trọng):**

```java
// Domain Event phải chứa đủ context để downstream xử lý
public interface SagaEvent {
    UUID sagaId();           // Correlation key
    int stepNumber();        // Thứ tự trong saga
    String eventType();      // SUCCESS | FAILURE | COMPENSATION
    JsonNode payload();      // Business data
    Instant timestamp();     // Ordering
    List<String> visited();  // Audit trail (services đã qua)
}
```

### 3.3. So Sánh Chi Tiết

| Aspect | Orchestration | Choreography |
|--------|--------------|--------------|
| **Control Flow** | Centralized (Orchestrator) | Decentralized (Event-driven) |
| **Coupling** | Logic coupling với Orchestrator | Event schema coupling |
| **Visibility** | Dễ trace (một nơi xem toàn bộ) | Khó trace (phải aggregate từ nhiều logs) |
| **Complexity** | Central complexity | Distributed complexity |
| **Single Point of Failure** | Orchestrator (cần HA) | Event Bus (cần HA) |
| **Testing** | Dễ unit test saga flow | Khó test end-to-end |
| **Business Logic Location** | Trong Orchestrator | Phân tán trong các service |
| **Latency** | Thêm 1 network hop (orchestrator) | Direct service-to-service |
| **Flexibility** | Thay đổi flow cần deploy orchestrator | Thay đổi flow cần update nhiều service |

### 3.4. Khi Nào Dùng Cái Nào?

**Chọn Orchestration khi:**
- Saga phức tạp (nhiều nhánh, conditional routing)
- Cần central visibility cho compliance/audit
- Team muốn control flow rõ ràng, dễ debug
- Có dedicated team maintain orchestrator

**Chọn Choreography khi:**
- Saga đơn giản (linear flow)
- Các service đã có sẵn event-driven architecture
- Muốn loose coupling tối đa
- Có robust event infrastructure (Kafka với log retention đủ dài)

> **Anti-pattern:** Dùng Orchestration cho saga đơn giản 2-3 bước → Over-engineering. Dùng Choreography cho saga phức tạp → Debugging nightmare.

---

## 4. Compensation Strategies

### 4.1. Bản Chất Compensation

Compensation **KHÔNG PHẢI** database rollback. Compensation là **business operation** undo tác động của original transaction.

**Ví dụ:**
- Original: `chargeCustomer($100)`
- Compensation: `refundCustomer($100)` - không phải rollback DB, mà là business operation mới

### 4.2. Compensation Patterns

```
┌────────────────────────────────────────────────────────────────┐
│                  COMPENSATION STRATEGIES                       │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. PHYSICAL UNDO                                              │
│     ┌──────────────┐     ┌──────────────┐                      │
│     │ Deduct Stock │ ──► │ Restore Stock│                      │
│     └──────────────┘     └──────────────┘                      │
│                                                                 │
│  2. LOGICAL UNDO (Soft Delete)                                 │
│     ┌──────────────┐     ┌──────────────┐                      │
│     │ Create Order │ ──► │ Cancel Order │ (status = CANCELLED)│
│     └──────────────┘     └──────────────┘                      │
│                                                                 │
│  3. FINANCIAL REVERSAL                                         │
│     ┌──────────────┐     ┌──────────────┐                      │
│     │ Charge $100  │ ──► │ Refund $100  │ (new transaction)    │
│     └──────────────┘     └──────────────┘                      │
│                                                                 │
│  4. CREDIT/REWARD COMPENSATION                                 │
│     ┌──────────────┐     ┌──────────────┐                      │
│     │ Charge $100  │ ──► │ Add $100     │ (store credit)       │
│     └──────────────┘     └──────────────┘                      │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

### 4.3. Compensation Ordering

```
Execution:    T1 ──► T2 ──► T3 ──► T4 (FAIL)
Compensation:              C3 ──► C2 ──► C1
                            │      │      │
                            ▼      ▼      ▼
                         Sync   Sync   Sync
                         hoặc   hoặc   hoặc
                         Async  Async  Async
```

**Quy tắc:**
1. Compensation phải theo thứ tự ngược (LIFO)
2. Compensation T(n) phải thành công trước khi bắt đầu C(n-1)
3. Compensation có thể chạy sync hoặc async tùy business requirement

### 4.4. Compensation Failure Handling

```
┌─────────────────────────────────────────────────────────────┐
│           COMPENSATION FAILURE SCENARIOS                    │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Scenario 1: Compensation Timeout                          │
│  ───────────────────────────────                           │
│  Action: Retry với exponential backoff                     │
│  Alert: PagerDuty nếu retry exhausted                      │
│                                                              │
│  Scenario 2: Compensation Business Error                   │
│  ─────────────────────────────────                         │
│  Example: Refund failed vì account closed                  │
│  Action: Escalate to manual reconciliation queue           │
│  Alert: High priority - cần intervention                   │
│                                                              │
│  Scenario 3: Partial Compensation                          │
│  ──────────────────────────────                            │
│  C3 success, C2 fail, C1 pending                           │
│  Action: Block saga, alert, manual fix                     │
│  State: INCONSISTENT (requires human intervention)         │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 5. Rủi Ro, Anti-Patterns, và Lỗi Thường Gặp

### 5.1. Critical Failure Modes

| Failure Mode | Nguyên nhân | Hậu quả | Phòng tránh |
|-------------|-------------|---------|-------------|
| **Saga Log Loss** | DB crash không replicate | Mất trạng thái, không recover được | Saga log phải sync replicate, backup |
| **Duplicate Execution** | Retry khi đã success | Double charge, double ship | Idempotency key trên mọi operation |
| **Compensation Loop** | Logic sai, C fail lại trigger C | Infinite loop | Max retry, circuit breaker |
| **Orphaned Saga** | Service crash giữa chừng | Resource locked, timeout không rõ | Timeout watchdog, saga cleanup job |
| **Event Loss** | Choreography, consumer lỗi | Saga stuck không tiến triển | Dead letter queue, monitoring |
| **Ordering Violation** | Event đến sai thứ tự | State machine bị hỏng | Sequential event processing |

### 5.2. Anti-Patterns

**1. Synchronous Saga trong HTTP Request**
```java
// ❌ SAI: HTTP request chờ saga hoàn thành
@PostMapping("/orders")
public Order createOrder() {
    return sagaOrchestrator.executeSaga(); // Có thể mất 30s+
}

// ✅ ĐÚNG: Async, return immediately với saga_id
@PostMapping("/orders")
public SagaReference createOrder() {
    UUID sagaId = sagaOrchestrator.startSagaAsync();
    return new SagaReference(sagaId); // Client poll hoặc webhook
}
```

**2. No Timeout Watchdog**
- Saga có thể stuck ở trạng thái PENDING vĩnh viễn
- Cần timeout policy: nếu saga chưa complete sau X phút → auto-fail

**3. Compensation Without Idempotency**
```java
// ❌ SAI: Compensation có thể chạy 2 lần
public void compensate() {
    refund(customer, amount); // Nếu retry → double refund
}

// ✅ ĐÚNG: Idempotent compensation
public void compensate(String idempotencyKey) {
    if (compensationLog.exists(idempotencyKey)) {
        return; // Already compensated
    }
    refund(customer, amount);
    compensationLog.save(idempotencyKey);
}
```

**4. Long-Running Saga Without Checkpoint**
- Saga 100 bước, fail ở bước 99 → phải compensate 98 bước
- Giải pháp: Snapshot pattern, chia saga thành sub-sagas

### 5.3. Edge Cases

**Nested Saga:**
- Saga A gọi Saga B, Saga B fail → A phải compensate
- Cần parent-child relationship trong saga log

**Parallel Execution:**
- T1, T2 chạy song song, T3 cần cả 2
- Nếu T1 success, T2 fail → compensate T1
- Race condition khi compensate T1 trong khi T3 đang chạy

**External System:**
- Compensation cần gọi external API
- External API không hỗ trợ idempotency → manual reconciliation

---

## 6. Khuyến Nghị Thực Chiến Production

### 6.1. Monitoring & Observability

```yaml
# Prometheus metrics cần có
saga_active_total{status="running"}           # Gauge
saga_duration_seconds_bucket{saga_type}       # Histogram
saga_compensation_total{reason}               # Counter
saga_timeout_total                            # Counter
saga_stuck_duration_seconds                   # Gauge (saga quá lâu chưa xong)
```

**Alert Rules:**
- `saga_timeout_total > 0` → Warning
- `saga_stuck_duration_seconds > 600` → Critical
- `saga_compensation_total` tăng đột biến → Investigation

### 6.2. Saga Schema Design

```sql
-- Bảng saga_instances
CREATE TABLE saga_instances (
    saga_id UUID PRIMARY KEY,
    saga_type VARCHAR(100) NOT NULL,
    status VARCHAR(50), -- STARTED, COMPLETED, FAILED, COMPENSATING
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    current_step INTEGER DEFAULT 0,
    total_steps INTEGER,
    context JSONB, -- Business data
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    parent_saga_id UUID REFERENCES saga_instances(saga_id),
    
    INDEX idx_status_started (status, started_at),
    INDEX idx_parent (parent_saga_id)
);

-- Bảng saga_steps
CREATE TABLE saga_steps (
    step_id UUID PRIMARY KEY,
    saga_id UUID REFERENCES saga_instances(saga_id),
    step_number INTEGER NOT NULL,
    service VARCHAR(100),
    action VARCHAR(50), -- EXECUTE, COMPENSATE
    status VARCHAR(50),
    request_payload JSONB,
    response_payload JSONB,
    executed_at TIMESTAMP,
    completed_at TIMESTAMP,
    idempotency_key VARCHAR(255) UNIQUE,
    
    INDEX idx_saga_step (saga_id, step_number)
);
```

### 6.3. Implementation Checklist

**Before Production:**
- [ ] Idempotency test cho mọi operation
- [ ] Compensation test cho mọi step
- [ ] Timeout scenario test
- [ ] Network partition simulation (Chaos Engineering)
- [ ] Saga log backup/restore tested
- [ ] Runbook cho manual intervention
- [ ] Dashboard monitoring saga health

**Runtime Safeguards:**
- Circuit breaker trên mọi service call
- Rate limiting để tránh cascade
- Dead letter queue cho event retry exhausted
- Max saga duration enforced
- Automatic cleanup cho completed sagas (retention policy)

### 6.4. Framework Selection

| Framework | Ngôn ngữ | Pattern | Phù hợp |
|-----------|----------|---------|---------|
| **Temporal** | Java/Go | Orchestration | Complex workflows, cần durability |
| **Camunda** | Java | Orchestration | BPMN, cần visual modeling |
| **Axon Framework** | Java | Both | Event sourcing + Saga |
| **Netflix Conductor** | Java | Orchestration | Microservices, cần scalability |
| **Saga Choreography DIY** | Any | Choreography | Đơn giản, có Kafka sẵn |

---

## 7. Kết Luận

**Bản chất Saga Pattern:**
- Không phải giải pháp "distributed transaction" theo nghĩa ACID
- Là **eventual consistency** pattern với khả năng recovery
- Compensation là business logic, không phải technical rollback

**Trade-off cốt lõi:**
- Orchestration: Central control ↔ Single point of complexity
- Choreography: Loose coupling ↔ Distributed reasoning difficulty
- Compensation: Business correctness ↔ Implementation complexity

**Quyết định quan trọng nhất:**
> Không phải chọn Orchestration hay Choreography, mà là thiết kế **compensation đúng** và đảm bảo **idempotency** trên mọi operation.

**Khi nào KHÔNG dùng Saga:**
- Real-time requirement (cần response < 100ms)
- Không thể định nghĩa compensation (vd: gửi email đã gửi)
- Strong consistency bắt buộc → xem lại service boundaries

**Production reality:**
- Saga sẽ fail, compensation sẽ fail, manual intervention sẽ cần thiết
- Đầu tư vào monitoring và runbook quan trọng hơn perfect code

---

## 8. Code Minimization (Chỉ khi cần)

### 8.1. Idempotency Handler Pattern

```java
@Component
public class IdempotentSagaStep {
    
    @Transactional
    public StepResult execute(String idempotencyKey, Supplier<StepResult> action) {
        // 1. Check if already processed
        Optional<SagaStep> existing = stepRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get().getResult(); // Return cached result
        }
        
        // 2. Execute with retry
        StepResult result = retryTemplate.execute(context -> action.get());
        
        // 3. Save result (unique constraint đảm bảo chỉ 1 lần)
        stepRepository.save(new SagaStep(idempotencyKey, result));
        
        return result;
    }
}
```

### 8.2. Timeout Watchdog

```java
@Scheduled(fixedDelay = 60000) // Every minute
public void checkStuckSagas() {
    List<Saga> stuckSagas = sagaRepository.findByStatusAndStartedAtBefore(
        Status.RUNNING, 
        Instant.now().minus(timeoutDuration)
    );
    
    for (Saga saga : stuckSagas) {
        log.error("Saga {} stuck for {}, initiating failure", saga.getId(), timeoutDuration);
        sagaOrchestrator.failSaga(saga.getId(), new TimeoutException());
    }
}
```

---

*Document này tập trung vào bản chất và trade-off. Implementation cụ thể cần điều chỉnh theo tech stack và business domain.*
