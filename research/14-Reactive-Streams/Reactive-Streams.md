# Reactive Streams: Publisher-Subscriber Model, Backpressure & Implementation Comparison

> **Mục tiêu:** Thấu hiểu bản chất Reactive Streams, cơ chế backpressure, và lựa chọn đúng đắn giữa các implementation trong production.

---

## 1. Mục tiêu của Task

Reactive Streams không chỉ là "lập trình bất đồng bộ đẹp hơn". Nó giải quyết bài toán cốt lõi trong xử lý luồng dữ liệu lớn: **làm sao để Producer không đè bẹp Consumer mà không cần buffer vô hạn**.

Task này tập trung vào:
- Bản chất Reactive Streams Specification (không phải một thư viện cụ thể)
- Cơ chế backpressure thực sự hoạt động như thế nào
- So sánh thực tế: Project Reactor vs RxJava vs Kotlin Flow
- Trade-off trong lựa chọn implementation cho production

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 Vấn đề cốt lõi: Tại sao cần Reactive Streams?

Trước khi có Reactive Streams, chúng ta có hai extreme:

| Cách tiếp cận | Vấn đề |
|--------------|--------|
| **Synchronous Blocking** | Thread bị block, resource lãng phí, không scale |
| **Async với Unbounded Buffer** | Consumer có thể bị overwhelm, OOM khi buffer đầy |

> **Reactive Streams giải quyết:** Cho phép Consumer "nói" với Producer tốc độ nó có thể xử lý.

### 2.2 Bốn Interface cốt lõi

Reactive Streams Specification định nghĩa 4 interface trong `org.reactivestreams`:

```java
// Producer
public interface Publisher<T> {
    void subscribe(Subscriber<? super T> s);
}

// Consumer
public interface Subscriber<T> {
    void onSubscribe(Subscription s);  // Điểm khởi đầu
    void onNext(T t);                  // Nhận data
    void onError(Throwable t);         // Xử lý lỗi
    void onComplete();                 // Kết thúc
}

// Kênh điều khiển tốc độ
public interface Subscription {
    void request(long n);  // Consumer yêu cầu n items
    void cancel();         // Consumer dừng nhận
}

// Processor = Publisher + Subscriber
public interface Processor<T, R> extends Subscriber<T>, Publisher<R> {}
```

**Điểm then chốt:** `Subscription.request(n)` là nơi **backpressure** xảy ra. Consumer chủ động kéo (pull) data thay vì Producer đẩy (push) liên tục.

### 2.3 Cơ chế Backpressure chi tiết

```
┌─────────────┐     subscribe()      ┌─────────────┐
│  Publisher  │ ───────────────────> │  Subscriber │
└─────────────┘                      └─────────────┘
       │                                   │
       │         onSubscribe(sub)          │
       │ <─────────────────────────────────│
       │                                   │
       │         request(10)               │
       │ <─────────────────────────────────│
       │                                   │
       │         onNext(data[0])           │
       │         onNext(data[1])           │
       │         ... (10 items)            │
       │ ─────────────────────────────────>│
       │                                   │
       │         request(5)                │
       │ <─────────────────────────────────│
       │         ...                       │
```

**Luồng điều khiển:**
1. Subscriber gọi `subscribe()` → Publisher nhận biết
2. Publisher gọi `onSubscribe(subscription)` → Subscriber có quyền điều khiển
3. Subscriber gọi `request(n)` → "Tôi sẵn sàng nhận n items"
4. Publisher gửi tối đa n items qua `onNext()`
5. Lặp lại bước 3-4 hoặc `cancel()` để dừng

> **Rule quan trọng:** Publisher KHÔNG ĐƯỢC gửi more than requested items. Violate = spec violation.

### 2.4 Memory Model & Thread Safety

Reactive Streams Specification đảm bảo:

| Yêu cầu | Ý nghĩa |
|---------|---------|
| **Happens-before** | `request()` happens-before `onNext()` |
| **Serialization** | Calls to `onNext()`, `onError()`, `onComplete()` phải serialized (không concurrent) |
| **Non-reentrant** | `onNext()` không được gọi reentrant từ cùng một Subscriber |

**Hệ quả production:** Không cần synchronize trong Subscriber nếu tuân thủ spec. Nhưng implementation phải đảm bảo happens-before (thường dùng `volatile` hoặc atomic operations).

---

## 3. Kiến trúc & Luồng Xử Lý

### 3.1 Operator Chaining & Lazy Evaluation

Reactive Streams là lazy. Không có gì xảy ra cho đến khi có subscription:

```
Flux.range(1, 1000)      // Tạo Publisher
    .map(x -> x * 2)     // Transform - chưa chạy
    .filter(x -> x > 10) // Filter - chưa chạy
    .subscribe();        // Mới bắt đầu thực thi
```

**Kiến trúc internal:** Mỗi operator tạo một `Processor` wrapper quanh `Publisher` upstream. Dữ liệu chảy downstream, request chảy upstream.

### 3.2 Scheduler & Thread Model

```
Observable.just(data)
    .map(transform)                    // Thread gọi subscribe
    .subscribeOn(Schedulers.io())      // Chuyển sang IO thread
    .map(anotherTransform)             // Vẫn trên IO thread
    .observeOn(Schedulers.computation()) // Chuyển sang computation thread
    .subscribe(consume);               // Trên computation thread
```

**subscribeOn vs observeOn:**
- `subscribeOn`: Quyết định thread cho toàn bộ upstream (chỉ ảnh hưởng 1 lần)
- `observeOn`: Chuyển thread cho downstream từ điểm gọi trở đi (có thể gọi nhiều lần)

> **Production Pitfall:** Dùng `subscribeOn` sai chỗ có thể chạy trên wrong thread pool, ảnh hưởng performance.

### 3.3 Signal Types & Error Handling

```
onSubscribe ──> onNext*(0..N) ──> [onError | onComplete]
```

- **Terminal signal:** `onError` hoặc `onComplete` - chỉ một trong hai, không bao giờ cả hai
- **After terminal:** Không được gọi thêm `onNext` sau terminal signal
- **Error propagation:** Unhandled error phải được báo lên (thường qua `onErrorDropped` hook)

---

## 4. So Sánh Implementation

### 4.1 Project Reactor (Spring Ecosystem)

**Đặc điểm:**
- **Reactive Streams compliant:** Tuân thủ spec chặt chẽ
- **Flux (0..N) vs Mono (0..1):** Type-safe, compile-time distinction
- **Spring Integration:** Native support trong Spring WebFlux, Spring Data Reactive
- **Backpressure strategies:** IGNORE, ERROR, DROP, LATEST, BUFFER

**When to use:**
- Dự án Spring Boot/WebFlux
- Cần type safety giữa single và multi-value
- Enterprise integration với Spring ecosystem

**Trade-off:**
- Learning curve cao hơn RxJava
- API surface lớn, nhiều operator để học

### 4.2 RxJava (Netflix)

**RxJava 2 vs 3:**
- RxJava 2: Reactive Streams compliant, nhưng có thêm `Observable` (non-backpressure)
- RxJava 3: Tương tự 2, improved API consistency

**Đặc điểm:**
- **Observable vs Flowable:** `Observable` không có backpressure (unbounded), `Flowable` có
- **Mature ecosystem:** Nhiều operator, community lớn, tài liệu phong phú
- **Android support:** RxAndroid cho mobile

**When to use:**
- Android development
- Legacy codebases đã dùng RxJava
- Cần operator cụ thể chưa có trong Reactor

**Trade-off:**
- `Observable` dễ misuse (quên chuyển sang `Flowable` khi cần backpressure)
- Less Spring-native support

### 4.3 Kotlin Flow (Coroutine-based)

**Bản chất khác biệt:**
- **Suspend functions:** Không dùng callback, dùng `suspend`
- **Sequential by default:** Mã nhìn giống synchronous nhưng chạy async
- **Cold flow:** Mỗi collector nhận sequence riêng
- **Hot flow:** `SharedFlow`, `StateFlow` cho broadcast

```kotlin
// Cold Flow
flow {
    for (i in 1..10) {
        emit(i)  // Suspend point
    }
}.collect { value ->  // Suspend until done
    println(value)
}
```

**When to use:**
- Kotlin codebase
- Team thích imperative programming style
- Android với Coroutines

**Trade-off:**
- Kotlin-only
- Interoperability với Java reactive code phức tạp hơn
- Less mature ecosystem so với RxJava/Reactor

### 4.4 Bảng so sánh chi tiết

| Criteria | Project Reactor | RxJava 3 | Kotlin Flow |
|----------|----------------|----------|-------------|
| **Language** | Java/Kotlin | Java/Kotlin | Kotlin only |
| **Threading** | Thread pools (Schedulers) | Thread pools (Schedulers) | Coroutines (Dispatchers) |
| **Backpressure** | Built-in | Flowable only | Buffer/collect operator |
| **Learning Curve** | Medium | Medium | Low (nếu quen Coroutines) |
| **Spring Integration** | Native | Via adapter | Via adapter |
| **Type Safety** | Flux/Mono distinction | Observable/Flowable | Flow/SharedFlow/StateFlow |
| **Performance** | Good | Good | Better (suspend cheaper than thread) |
| **Debugging** | Hooks, checkRequest() | Plugins, debug mode | Coroutine debugger |

---

## 5. Rủi Ro, Anti-Patterns & Lỗi Thường Gặp

### 5.1 Blocking trong Reactive Code

**Anti-pattern:**
```java
// ❌ WRONG: Block trong reactive chain
flux.map(item -> blockingDatabaseCall(item))
```

**Giải pháp:**
```java
// ✅ CORRECT: Offload to blocking-compatible scheduler
flux.flatMap(item -> 
    Mono.fromCallable(() -> blockingDatabaseCall(item))
        .subscribeOn(Schedulers.boundedElastic())
)
```

### 5.2 Ignoring Backpressure

**Vấn đề:**
```java
// Consumer chậm, Producer nhanh
Flux.interval(Duration.ofMillis(10))  // 100 items/sec
    .subscribe(i -> {
        Thread.sleep(100);  // Process 10 items/sec
    });
// → Buffer overflow, OOM
```

**Các strategy:**
```java
.onBackpressureDrop()      // Bỏ items không kịp xử lý
.onBackpressureLatest()     // Giữ item mới nhất
.onBackpressureBuffer(100)  // Buffer có giới hạn
.onBackpressureError()      // Throw exception
```

### 5.3 Memory Leak trong Subscription

**Vấn đề:** Subscription không được dispose:
```java
// ❌ WRONG: Không unsubscribe
disposable = observable.subscribe(...);
// Component destroyed nhưng disposable vẫn active
```

**Giải pháp:**
```java
// ✅ CORRECT: Quản lý lifecycle
disposable.dispose();  // Khi component destroyed

// Hoặc dùng composite
disposable.add(subscription);  // Add vào composite
disposable.clear();  // Clear all
```

### 5.4 Nested Subscriptions

**Anti-pattern:**
```java
// ❌ WRONG: Nested subscribe
service.getData()
    .subscribe(data -> {
        service.process(data)
            .subscribe(result -> {...});  // Nested!
    });
```

**Giải pháp:**
```java
// ✅ CORRECT: Flatten
service.getData()
    .flatMap(data -> service.process(data))
    .subscribe(result -> {...});
```

### 5.5 Swallowing Errors

**Vấn đề:**
```java
// ❌ WRONG: Silent failure
.doOnError(e -> log.error(e))  // Chỉ log, không xử lý
```

**Giải pháp:**
```java
// ✅ CORRECT: Error handling explicit
.onErrorResume(e -> {
    log.error(e);
    return Mono.empty();  // Hoặc fallback
})
// Hoặc để error propagate lên subscriber
```

---

## 6. Khuyến Nghị Thực Chiến Production

### 6.1 Chọn Implementation

| Scenario | Recommendation |
|----------|---------------|
| Spring Boot/WebFlux | **Project Reactor** (native support) |
| Spring Boot + Kotlin | **Kotlin Flow** hoặc **Project Reactor** |
| Android | **Kotlin Flow** (Google recommended) hoặc **RxJava** |
| Legacy Rx codebase | **RxJava 3** (migration path rõ ràng) |
| Mixed Java/Kotlin team | **Project Reactor** (common ground) |

### 6.2 Monitoring & Observability

**Metrics cần track:**
- **Pending requests:** Số items đang chờ xử lý
- **Backpressure events:** Bao nhiêu lần drop/buffer
- **Subscriber count:** Số active subscription
- **Stream duration:** Thời gian từ subscribe đến complete

**Reactor Hooks:**
```java
Hooks.onOperatorDebug();  // Stack trace chi tiết (chi phí performance)

// Custom hook cho metrics
Hooks.onEachOperator("metrics", Operators.lift((scannable, subscriber) -> {
    // Record metrics
    return subscriber;
}));
```

### 6.3 Testing

**StepVerifier (Reactor):**
```java
StepVerifier.create(flux)
    .expectNext(1, 2, 3)
    .expectComplete()
    .verify();
```

**TestScheduler (RxJava):**
```java
TestScheduler scheduler = new TestScheduler();
Observable.interval(1, TimeUnit.SECONDS, scheduler)
    .test()
    .assertNoValues();
    
scheduler.advanceTimeBy(2, TimeUnit.SECONDS);
// Now assert values
```

### 6.4 Performance Tuning

- **Buffer sizes:** Điều chỉnh `prefetch` trong `flatMap`, `concatMap`
- **Scheduler selection:** `Schedulers.parallel()` cho CPU-bound, `Schedulers.boundedElastic()` cho IO-bound
- **Batching:** Dùng `buffer()`, `window()` để giảm overhead
- **Fuseable operators:** Một số operator có thể "fuse" để giảm overhead context switching

---

## 7. Kết Luận

**Bản chất của Reactive Streams:** Một contract (specification) cho phép **Consumer kiểm soát tốc độ dữ liệu** thông qua cơ chế **demand-based pull**. Không phải magic, không phải framework - là một cách thiết kế API để giải quyết bài toán **bounded buffer trong xử lý luồng dữ liệu không giới hạn**.

**Trade-off quan trọng nhất:**
- **Complexity vs Scalability:** Reactive code khó viết, khó debug, nhưng cho phép xử lý throughput cao với ít thread
- **Memory vs Latency:** Backpressure đảm bảo không OOM, nhưng có thể tăng latency nếu phải drop/buffer

**Rủi ro lớn nhất trong production:**
1. **Blocking trong reactive chain** - Triệt tiêu lợi ích của reactive
2. **Memory leak từ undisposed subscriptions** - Giống memory leak trong listener pattern
3. **Wrong backpressure strategy** - Drop data quan trọng hoặc vẫn bị OOM

**Quyết định thiết kế:**
- Không phải mọi API đều cần reactive. Nếu request/response đơn giản, imperative có thể đủ.
- Reactive shine khi: streaming data, real-time updates, high throughput with resource constraints, composing multiple async sources.

---

## 8. Tài liệu tham khảo

1. [Reactive Streams Specification](https://github.com/reactive-streams/reactive-streams-jvm)
2. [Project Reactor Reference](https://projectreactor.io/docs/core/release/reference/)
3. [RxJava 3 Wiki](https://github.com/ReactiveX/RxJava/wiki)
4. [Kotlin Flow Documentation](https://kotlinlang.org/docs/flow.html)
5. [Reactive Manifesto](https://www.reactivemanifesto.org/)
