# Circuit Breaker & Bulkhead Pattern: Phân Tích Chuyên Sâu

> **Mục tiêu:** Thấu hiểu bản chất cách ly lỗi (failure isolation) trong microservices, từ cơ chế state machine đến chiến lược pool isolation và các rủi ro production.

---

## 1. Mục Tiêu Củng Cố Hệ Thống Phân Tán

Khi một microservice gọi đến service khác, nó chịu rủi ro từ:
- **Network latency** không ổn định
- **Service downstream** bị quá tải hoặc crash
- **Cascading failure** lan truyền từ service này sang service khác

Hai pattern này giải quyết bài toán:
- **Circuit Breaker:** Ngăn chặn việc gọi liên tục vào service đã fail
- **Bulkhead:** Cách ly tài nguyên để failure không làm sập toàn bộ hệ thống

> **Chân lý:** Không thiết kế để "không bao giờ fail", mà thiết kế để "fail một cách an toàn".

---

## 2. Circuit Breaker: Bản Chất State Machine

### 2.1 Cơ Chế Hoạt Động Ở Tầng Thấp

Circuit Breaker là một **finite state machine** với 3 trạng thái:

```
┌─────────────┐
│   CLOSED    │ ← Bình thường, request đi qua
│  (Đóng)     │
└──────┬──────┘
       │ Failure rate > threshold
       ▼
┌─────────────┐     Timeout        ┌─────────────┐
│    OPEN     │◄───────────────────│  HALF-OPEN  │
│   (Mở)      │                    │ (Thử nghiệm)│
│Request bị   │───────────────────►└──────┬──────┘
│chặn ngay    │  Probe call fail           │
└─────────────┘                            │ 1 probe call thành công
                                           │
                                    (Sau timeout)
```

#### State: CLOSED
- **Hành vi:** Mọi request đều được chuyển tiếp đến downstream service
- **Theo dõi:** Đếm số failure/success trong một **rolling window** (sliding window)
- **Chuyển trạng thái:** Khi failure rate vượt ngưỡng (vd: 50% trong 10 giây) → chuyển OPEN

#### State: OPEN
- **Hành vi:** Request bị **fail-fast** ngay lập tức, không gọi downstream
- **Mục đích:** Giảm tải cho service đang gặp vấn đề, tránh "gào thét" (retry storm)
- **Chuyển trạng thái:** Sau **wait duration** (vd: 30s) → chuyển HALF-OPEN

#### State: HALF-OPEN
- **Hành vi:** Cho phép **một số lượng request giới hạn** đi qua để "thăm dò"
- **Logic quyết định:**
  - Nếu probe thành công → CLOSED
  - Nếu probe thất bại → OPEN (reset wait duration)

### 2.2 Sliding Window Algorithm

Resilience4j sử dụng **sliding window** để tính failure rate:

```
Time →  ┌────┬────┬────┬────┬────┬────┐
Window  │ W1 │ W2 │ W3 │ W4 │ W5 │ W6 │
        └────┴────┴────┴────┴────┴────┘
          ↑_____________________↑
              Current Window
```

**Count-based window:** Giữ N kết quả gần nhất
**Time-based window:** Tính trong khoảng thờigian T

> **Trade-off quan trọng:** Window càng ngắn → phản ứng càng nhanh nhưng dễ bị false positive. Window dài → ổn định hơn nhưng chậm phản ứng.

### 2.3 Vấn Đề "Thundering Herd" Khi Chuyển Trạng Thái

Khi Circuit Breaker chuyển từ OPEN → HALF-OPEN, nếu không giới hạn:
- Tất cả các request đang chờ đồng loạt "ồ ạt" gọi downstream
- Service vừa hồi phục có thể lại bị quá tải ngay lập tức

**Giải pháp:** Giới hạn số request trong HALF-OPEN (thường là 1-3 request).

---

## 3. Bulkhead Pattern: Cách Ly Tài Nguyên

### 3.1 Bản Chất Cách Ly

Bulkhead lấy cảm hứng từ **vách ngăn tàu thủy** (ship compartments):
- Một khoang bị thủng → nước không tràn sang khoang khác
- Tương tự: Một component fail → không chiếm dụng toàn bộ tài nguyên

### 3.2 Hai Chiến Lược Cách Ly

#### Semaphore Isolation (Counting Semaphore)
```
┌─────────────────────────────────────────┐
│           Service Instance              │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐   │
│  │ Thread 1│ │ Thread 2│ │ Thread 3│   │
│  └────┬────┘ └────┬────┘ └────┬────┘   │
│       │           │           │         │
│       └───────────┼───────────┘         │
│                   ▼                     │
│         ┌─────────────┐                 │
│         │  Semaphore  │  maxConcurrentCalls = 10
│         │  (Counter)  │                 │
│         └──────┬──────┘                 │
│                │                        │
│         ┌──────┴──────┐                 │
│         │ Downstream  │                 │
│         │  Service    │                 │
│         └─────────────┘                 │
└─────────────────────────────────────────┘
```

- Dùng **Semaphore** đếm số concurrent call
- Thread vẫn giữ nguyên, chỉ giới hạn số call đồng thờii
- **Ưu điểm:** Nhẹ, không tạo thread mới, ít overhead
- **Nhược điểm:** Không timeout được nếu downstream không phản hồi

#### Thread Pool Isolation
```
┌─────────────────────────────────────────┐
│           Service Instance              │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐   │
│  │ Thread  │ │ Thread  │ │ Thread  │   │
│  │ Pool A  │ │ Pool B  │ │ Pool C  │   │
│  │ (DB)    │ │ (Auth)  │ │ (API)   │   │
│  │ 10 thrd │ │ 5 thrd  │ │ 20 thrd │   │
│  └────┬────┘ └────┬────┘ └────┬────┘   │
│       │           │           │         │
│       └───────────┼───────────┘         │
│                   ▼                     │
│         ┌─────────────┐                 │
│         │  Executor   │                 │
│         │  Service    │                 │
│         └─────────────┘                 │
└─────────────────────────────────────────┘
```

- Mỗi downstream service có **thread pool riêng**
- **Ưu điểm:** Timeout được (thread bị giết khi quá hạn), true isolation
- **Nhược điểm:** Context switching overhead, memory cho mỗi thread (~1MB stack)

### 3.3 So Sánh Chi Tiết

| Tiêu chí | Semaphore | Thread Pool |
|----------|-----------|-------------|
| **Overhead** | Thấp | Cao |
| **Timeout** | Không hỗ trợ native | Hỗ trợ |
| **Isolation level** | Tốt | Xuất sắc |
| **Use case** | Các call nhanh, ít rủi ro timeout | Các call chậm, cần hard limit |
| **Memory** | Ít | Nhiều (mỗi thread ~1MB) |

---

## 4. Resilience4j: Kiến Trúc và Implementation

### 4.1 Tại Sao Không Hystrix?

Netflix Hystrix đã **end-of-life** (v1.5.18, 2018). Lý do:
- Không hỗ trợ Java 9+ modules
- Thread-local based không phù hợp với Reactive Programming
- Không hỗ trợ Functional/Lambda style

Resilience4j được thiết kế cho **Java 8+**, functional, lightweight.

### 4.2 Kiến Trúc Decorator Pattern

```
┌─────────────────────────────────────────┐
│         Resilience4j Core               │
├─────────────────────────────────────────┤
│  CircuitBreaker  │  Retry  │  RateLimiter│
│  Bulkhead        │  Cache  │  TimeLimiter│
└─────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────┐
│      Functional Interface (Supplier)    │
│         decorateSupplier()              │
└─────────────────────────────────────────┘
```

**Cơ chế:** Sử dụng **decorator pattern** để wrap function call. Không dùng AOP hoặc proxy như Hystrix.

### 4.3 Event Publishing và Observability

Resilience4j publish events:
- `CircuitBreakerOnSuccessEvent`
- `CircuitBreakerOnErrorEvent`
- `CircuitBreakerOnStateTransitionEvent`

Integration với Micrometer/Prometheus để export metrics.

---

## 5. Failure Modes và Anti-Patterns

### 5.1 Circuit Breaker Anti-Patterns

#### Anti-Pattern 1: Circuit Breaker trên mọi method
```java
// SAI - Không cần thiết cho in-memory operations
@circuitBreaker(name = "calculateDiscount")  // ❌
public BigDecimal calculateDiscount(Order order) {
    return order.getTotal().multiply(new BigDecimal("0.1"));
}
```

#### Anti-Pattern 2: Không có Fallback
```java
// SAI - Người dùng nhận lỗi ngay khi CB open
@circuitBreaker(name = "payment")
public PaymentResult processPayment(PaymentRequest req) {
    return paymentGateway.charge(req);  // ❌ Không fallback
}
```

#### Anti-Pattern 3: Threshold quá nhạy
```java
// SAI - 10% failure trong 5s là quá nhạy
CircuitBreakerConfig config = CircuitBreakerConfig.custom()
    .failureRateThreshold(10)  // ❌ Quá thấp
    .waitDurationInOpenState(Duration.ofSeconds(5))  // ❌ Quá ngắn
    .build();
```

### 5.2 Bulkhead Anti-Patterns

#### Anti-Pattern 1: Thread Pool quá nhỏ
```java
// SAI - 2 thread cho cả service
ThreadPoolBulkheadConfig config = ThreadPoolBulkheadConfig.custom()
    .coreThreadPoolSize(1)
    .maxThreadPoolSize(2)  // ❌ Quá ít
    .build();
```

#### Anti-Pattern 2: Không giới hạn queue
```java
// SAI - Queue không giới hạn = OOM risk
ThreadPoolBulkheadConfig config = ThreadPoolBulkheadConfig.custom()
    .queueCapacity(Integer.MAX_VALUE)  // ❌
    .build();
```

### 5.3 Edge Cases Nguy Hiểm

| Edge Case | Hậu quả | Giải pháp |
|-----------|---------|-----------|
| **Half-open thundering herd** | Service vừa recover lại bị quá tải | Giới hạn 1 probe request |
| **Context propagation loss** | Tracing/MIC bị mất qua thread pool | Dùng DelegatingContextExecutor |
| **Circular dependency CB** | A gọi B, B gọi A, cả hai đều OPEN | Design separate failure domains |
| **Thundering herd on recovery** | Nhiều instance cùng thử lại | Jitter backoff + randomization |

---

## 6. Production Concerns

### 6.1 Monitoring và Alerting

**Metrics cần theo dõi:**

```yaml
# Prometheus metrics
resilience4j_circuitbreaker_state:
  - state: "closed/open/half_open"
  
resilience4j_circuitbreaker_calls:
  - kind: "successful/failed/ignored/not_permitted"
  
resilience4j_bulkhead_available_concurrent_calls:
  - available_calls
  
resilience4j_bulkhead_max_allowed_concurrent_calls:
  - max_calls
```

**Alert rules:**
- CB OPEN > 5 phút → Page on-call
- Bulkhead saturation > 80% liên tục → Scale ngay

### 6.2 Tuning Guidelines

#### Circuit Breaker Tuning

| Parameter | Conservative | Aggressive | Lý do |
|-----------|--------------|------------|-------|
| failureRateThreshold | 50% | 80% | Ngưỡng chịu đựng lỗi |
| waitDurationInOpenState | 60s | 10s | Thời gian "nghỉ" |
| permittedNumberOfCallsInHalfOpenState | 1 | 5 | Số probe call |
| slidingWindowSize | 100 | 10 | Độ nhạy |

#### Bulkhead Tuning

```
Công thức ước tính thread pool:

maxThreads = (maxLatency / targetLatency) * targetThroughput

Ví dụ:
- maxLatency = 500ms
- targetLatency = 100ms  
- targetThroughput = 100 req/s

maxThreads = (500/100) * 100 = 500 threads
```

### 6.3 Testing Circuit Breaker

**Unit test với Resilience4j:**
```java
@Test
void shouldOpenCircuitAfterFailures() {
    CircuitBreaker cb = CircuitBreaker.ofDefaults("test");
    
    // Simulate failures
    IntStream.range(0, 10).forEach(i -
        cb.executeSupplier(() -
            { throw new RuntimeException("fail"); })
    );
    
    assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
}
```

**Chaos Engineering:**
- Dùng Chaos Monkey hoặc Toxiproxy để inject latency/failure
- Verify CB + Bulkhead hoạt động đúng

### 6.4 Distributed Context Propagation

Khi dùng ThreadPoolBulkhead với Distributed Tracing:

```java
// SAI - Context bị mất
ThreadPoolBulkhead bulkhead = ThreadPoolBulkhead.ofDefaults("api");
Supplier<String> decorated = Bulkhead.decorateSupplier(bulkhead, 
    () -> apiClient.call()  // Trace context lost!
);

// ĐÚNG - Giữ context
ThreadPoolBulkhead bulkhead = ThreadPoolBulkhead.ofDefaults("api");
Supplier<String> decorated = Bulkhead.decorateSupplier(bulkhead,
    ContextSnapshot.captureAll().wrap(() -> apiClient.call())
);
```

---

## 7. Kết Luận: Bản Chất Của Resilience

### Tóm Tắt Trade-offs

| Pattern | Ưu điểm Chính | Chi Phí | Khi Nào Dùng |
|---------|---------------|---------|--------------|
| **Circuit Breaker** | Ngăn cascading failure | Có thể false positive | Service-to-service calls |
| **Semaphore Bulkhead** | Nhẹ, nhanh | Không timeout native | Fast calls, low latency |
| **ThreadPool Bulkhead** | True isolation, timeout | Memory + context switch | Slow calls, I/O bound |

### Nguyên Tắc Vàng

1. **Fail Fast > Fail Slow:** Circuit breaker ngăn resource exhaustion
2. **Isolate > Share:** Bulkhead ngăn một failure domain ảnh hưởng toàn hệ thống
3. **Observe > Guess:** Metrics và alerting là bắt buộc
4. **Test > Assume:** Chaos engineering validate assumptions

### Quyết Định Kiến Trúc

```
Người dùng ──► API Gateway
                   │
     ┌─────────────┼─────────────┐
     ▼             ▼             ▼
  ┌──────┐     ┌──────┐     ┌──────┐
  │CB+   │     │CB+   │     │CB+   │
  │BH    │     │BH    │     │BH    │
  └──┬───┘     └──┬───┘     └──┬───┘
     │            │            │
  Service A   Service B   Service C
```

> **Cốt lõi:** Circuit Breaker và Bulkhead không phải để ngăn lỗi, mà để **kiểm soát phạm vi ảnh hưởng** của lỗi. Đó là sự khác biệt giữa "không bao giờ fail" và "fail gracefully".

---

## 8. Tài Liệu Tham Khảo

1. **Resilience4j Documentation** - https://resilience4j.readme.io/
2. **Release It!** by Michael Nygard (Circuit Breaker pattern origin)
3. **AWS Builders' Library** - Circuit Breaker and Retries
4. **Netflix Tech Blog** - Hystrix design evolution

---

*Research completed: 2026-03-27*
*Senior Backend Architect Perspective*