# Structural Design Patterns - Adapter, Decorator, Proxy, Facade

> **Mục tiêu:** Hiểu bản chất các pattern cấu trúc, khi nào nên dùng, trade-off cốt lõi, và các rủi ro thực tế trong production.

---

## 1. Bản Chất Structural Patterns

Structural patterns giải quyết một bài toán cốt lõi: **làm thế nào để các components với interfaces khác nhau có thể làm việc cùng nhau một cách linh hoạt và maintainable**.

### 1.1 Vấn Đề Cốt Lõi

Khi hệ thống phát triển, chúng ta đối mặt với:
- **Incompatible interfaces**: Legacy code, third-party libraries, microservices khác nhau
- **Explosion of subclasses**: Inheritance dẫn đến class hierarchy phức tạp
- **Cross-cutting concerns**: Logging, security, caching lặp lại ở nhiều nơi
- **Subsystem complexity**: Ngườì dùng không cần biết chi tiết implementation

### 1.2 Nguyên Lý Chung

| Pattern | Kỹ Thuật Chính | Bài Toán Giải Quyết |
|---------|---------------|---------------------|
| **Adapter** | Interface translation | Tích hợp incompatible interfaces |
| **Decorator** | Composition + delegation | Thêm behavior dynamically |
| **Proxy** | Controlled access | Kiểm soát access, lazy initialization |
| **Facade** | Unified interface | Đơn giản hóa complex subsystems |

> **Quan trọng:** Tất cả 4 patterns đều dựa trên **composition over inheritance** - đây là sự khác biệt cốt lõi so với lập trình hướng đối tượng ngây thơ.

---

## 2. Adapter Pattern

### 2.1 Bản Chất Cơ Chế

Adapter pattern hoạt động như một **translator** giữa hai interfaces không tương thích. Thay vì sửa đổi code gốc (violation của Open/Closed Principle), ta tạo một wrapper chuyển đổi calls.

#### Cơ Chế Hoạt Động

```
Client ──► Target Interface ◄── Adapter ──► Adaptee
```

**Implementation approaches:**

1. **Object Adapter** (Composition) - *Recommended*
   - Adapter giữ reference đến Adaptee
   - Flexible: có thể adapt nhiều subclasses
   - Loose coupling

2. **Class Adapter** (Inheritance) - *Rarely used*
   - Adapter extends Adaptee
   - Requires multiple inheritance (not in Java)
   - Tight coupling

### 2.2 Mục Tiêu Thiết Kế & Trade-offs

**Khi nào dùng:**
- Tích hợp legacy systems không thể sửa đổi
- Sử dụng third-party libraries với interface khác
- Migration từ old API sang new API

**Trade-offs:**

| Ưu Điểm | Nhược Điểm |
|---------|-----------|
| Không cần sửa code gốc | Thêm một layer indirection |
| Tái sử dụng existing classes | Extra object allocation |
| Single Responsibility: separation of concerns | Debugging phức tạp hơn (stack trace dài) |
| Open/Closed Principle compliant | Performance overhead nhỏ (method call delegation) |

### 2.3 Production Concerns

#### Stack Trace Pollution

```
java.lang.IllegalArgumentException: Invalid user ID
    at LegacyUserService.findUser(LegacyUserService.java:45)
    at UserServiceAdapter.findUser(UserServiceAdapter.java:23)  // ← Extra frame
    at UserController.getUser(UserController.java:18)
```

**Mitigation:**
- Sử dụng `@SneakyThrows` (Lombok) hoặc exception wrapping với context
- Document rõ ràng trong adapter JavaDoc

#### Performance Considerations

- **Micro-benchmark**: Adapter delegation thêm ~1-2ns per call (negligible)
- **Memory**: Mỗi adapter instance thêm 16 bytes (object header) + reference
- **Hot path**: Tránh adapter trong tight loops nếu throughput là critical

### 2.4 Anti-patterns & Pitfalls

| Anti-pattern | Hệ Quả | Giải Pháp |
|-------------|--------|-----------|
| **Mega Adapter** | Một adapter cho quá nhiều methods | Split into focused adapters |
| **Stateful Adapter** | Adapter giữ state không cần thiết | Stateless design, immutable |
| **Leaky Abstraction** | Expose adaptee details | Strict interface compliance |
| **Bi-directional Adapter** | Adapter biết cả hai chiều | Two separate adapters |

### 2.5 Modern Java Context

- **Java 8+**: Interface default methods có thể thay thế một số use cases
- **Spring**: `WebMvcConfigurerAdapter` deprecated vì default methods
- **Records**: Java 16+ records có thể là lightweight DTO adapters

---

## 3. Decorator Pattern

### 3.1 Bản Chất Cơ Chế

Decorator pattern cho phép **thêm behavior dynamically** vào object mà không thay đổi code của nó. Đây là sự thay thế mạnh mẽ cho inheritance khi cần combine behaviors.

#### Cơ Chế Hoạt Động

```
Component (interface)
    ▲
    ├── ConcreteComponent
    └── Decorator (abstract)
            ├── ConcreteDecoratorA
            └── ConcreteDecoratorB
```

**Key mechanism:**
1. Decorator và Component implement cùng interface
2. Decorator wrap một Component instance (composition)
3. Decorator delegate calls đến wrapped component
4. Decorator có thể modify input/output hoặc add behavior

### 3.2 Composition vs Inheritance Analysis

#### Vấn Đề Củả Inheritance

```
InputStream
├── FileInputStream
├── ByteArrayInputStream
├── BufferedInputStream extends FileInputStream  // ❌ Wrong!
└── DataInputStream extends BufferedInputStream  // ❌ Tight coupling
```

**Vấn đề:**
- BufferedFileInputStream? BufferedByteArrayInputStream?
- Class explosion: 2^n combinations cho n features
- Static binding: cannot change at runtime

#### Giải Pháp Decorator

```
InputStream (interface)
├── FileInputStream
├── ByteArrayInputStream
├── BufferedInputStream (decorator)  // Wraps any InputStream
└── DataInputStream (decorator)      // Wraps any InputStream

// Usage: Composable at runtime
new BufferedInputStream(new FileInputStream("file.txt"))
new DataInputStream(new BufferedInputStream(new FileInputStream("file.txt")))
```

### 3.3 Mục Tiêu Thiết Kế & Trade-offs

**Khi nào dùng:**
- Thêm responsibilities dynamically
- Behaviors có thể được withdrawn
- Extension bằng subclassing là impractical

**Trade-offs:**

| Ưu Điểm | Nhược Điểm |
|---------|-----------|
| Flexible hơn inheritance | Nhiều small objects (hard to debug) |
| Single Responsibility per decorator | Layer initialization order quan trọng |
| Composable at runtime | Type identification phức tạp (`instanceof` breaks) |
| Open/Closed Principle | Potential for deep call stacks |

### 3.4 Stack Depth & Performance

```java
// Each decorator adds a frame to the call stack
new MetricDecorator(
    new LoggingDecorator(
        new RetryDecorator(
            new CachingDecorator(
                new ConcreteService()
            )
        )
    )
);
// Stack depth: 5 frames for each method call
```

**Production Impact:**
- StackOverflowError nếu chain quá dài (>1000 decorators)
- JIT inlining có thể bị hindered nếu chain quá phức tạp
- Latency: mỗi layer thêm ~1-5ns (method dispatch)

### 3.5 Java I/O - Case Study Điển Hình

Java I/O là implementation hoàn hảo của decorator pattern:

```java
// Base component
InputStream fis = new FileInputStream("data.txt");

// Add buffering
InputStream bis = new BufferedInputStream(fis, 8192);

// Add data reading capability
DataInputStream dis = new DataInputStream(bis);

// Add decompression
GZIPInputStream gzis = new GZIPInputStream(dis);

// Add object deserialization
ObjectInputStream ois = new ObjectInputStream(gzis);
```

**Bản chất:** Mỗi layer wrap layer trước, delegate read() calls và add functionality.

### 3.6 Anti-patterns & Pitfalls

| Anti-pattern | Hệ Quả | Giải Pháp |
|-------------|--------|-----------|
| **Transparent Decorator** | Decorator không giữ interface contract | Strict Liskov Substitution |
| **Stateful Decorator Chaos** | Decorators share mutable state | Immutable decorators |
| **Ordering Dependencies** | A→B khác B→A | Document order requirements |
| **Over-decoration** | 10+ layers cho đơn giản operation | Consider strategy pattern |

---

## 4. Proxy Pattern

### 4.1 Bản Chất Cơ Chế

Proxy pattern cung cấp **placeholder hoặc surrogate** cho một object khác để kiểm soát access. Đây là pattern có nhiều variation nhất và phức tạp nhất trong nhóm structural.

#### Các Loại Proxy

| Loại | Mục Đích | Implementation |
|------|----------|----------------|
| **Virtual Proxy** | Lazy initialization | Create expensive object on-demand |
| **Remote Proxy** | Location transparency | Handle network communication |
| **Protection Proxy** | Access control | Verify permissions |
| **Smart Reference** | Additional bookkeeping | Reference counting, logging |
| **Caching Proxy** | Performance | Cache results |

### 4.2 Virtual Proxy & Lazy Initialization

**Cơ chế:**

```
Client ──► Proxy ──► ? (RealSubject not created yet)
              │
              └── First access triggered
                  └── Create RealSubject
                      └── Delegate
```

**Production Concern - Thread Safety:**

```java
public class VirtualProxy {
    private volatile ExpensiveService realService;  // volatile for visibility
    
    public void operation() {
        if (realService == null) {
            synchronized (this) {
                if (realService == null) {  // Double-checked locking
                    realService = new ExpensiveService();
                }
            }
        }
        realService.operation();
    }
}
```

**Double-checked locking pattern:**
- First check (no locking): Fast path cho subsequent calls
- Second check (with locking): Prevent multiple instantiation
- `volatile`: Ensure visibility across threads (Java 5+ memory model)

### 4.3 Dynamic Proxy - Java Core Mechanism

Java cung cấp `java.lang.reflect.Proxy` cho dynamic proxy creation:

```java
InvocationHandler handler = (proxy, method, args) -> {
    // Pre-processing: logging, metrics, auth
    log.info("Calling {}.{} with args {}", 
             target.getClass(), method.getName(), args);
    
    Object result = method.invoke(target, args);
    
    // Post-processing
    log.info("Method {} completed in {}ms", 
             method.getName(), elapsed);
    return result;
};

Service proxy = (Service) Proxy.newProxyInstance(
    loader,
    new Class<?>[] { Service.class },
    handler
);
```

**Bản chất cơ chế:**

1. **Bytecode Generation**: JDK tạo class mới tại runtime
2. **Interface Implementation**: Generated class implements target interfaces
3. **InvocationHandler Dispatch**: Tất cả calls routed qua handler
4. **Method.invoke()**: Reflection-based delegation

#### Performance Trade-offs

| Aspect | Static Proxy | Dynamic Proxy |
|--------|-------------|---------------|
| **Creation cost** | Compile-time (0 runtime) | Expensive (bytecode generation) |
| **Invocation cost** | Direct call | Reflection overhead (~10-50ns) |
| **Flexibility** | Fixed at compile-time | Runtime configuration |
| **Memory** | One class | One class per proxy type |

**Java 8+ Optimization:**
- MethodHandle thay thế Method.invoke() (faster)
- LambdaMetafactory cho lightweight proxies

### 4.4 CGLIB & Bytecode Manipulation

Khi target không implement interface, dùng CGLIB:

```java
Enhancer enhancer = new Enhancer();
enhancer.setSuperclass(TargetClass.class);
enhancer.setCallback((MethodInterceptor) (obj, method, args, proxy) -> {
    // Pre-processing
    Object result = proxy.invokeSuper(obj, args);
    // Post-processing
    return result;
});
TargetClass proxy = (TargetClass) enhancer.create();
```

**CGLIB vs JDK Proxy:**

| Feature | JDK Proxy | CGLIB |
|---------|-----------|-------|
| **Requirements** | Interfaces | Non-final classes |
| **Mechanism** | Implement interfaces | Extend class (subclassing) |
| **Final methods** | N/A | Cannot intercept |
| **Constructor** | No constraints | Requires no-arg constructor |
| **Performance** | Slower (reflection) | Faster (bytecode) |
| **Memory** | Less | More (more bytecode) |

**Spring AOP sử dụng:**
- JDK Proxy nếu target implements interfaces
- CGLIB nếu không (hoặc `@EnableAspectJAutoProxy(proxyTargetClass = true)`)

### 4.5 Production Concerns

#### Memory Leaks in Proxy

```java
// Problem: Handler giữ reference đến large object
public class BadProxy implements InvocationHandler {
    private LargeObject heavyData;  // ❌ Held for proxy lifetime
    
    public Object invoke(...) {
        // Only uses heavyData occasionally
    }
}

// Solution: WeakReference or lazy loading
public class GoodProxy implements InvocationHandler {
    private WeakReference<LargeObject> heavyDataRef;
}
```

#### Debugging Proxies

```java
// Proxy class name không giúp debugging
System.out.println(proxy.getClass()); 
// Output: com.sun.proxy.$Proxy42

// Solution: Custom toString() trong InvocationHandler
@Override
public Object invoke(Object proxy, Method method, Object[] args) {
    if (method.getName().equals("toString")) {
        return "Proxy for " + target.getClass().getName();
    }
    // ...
}
```

#### Monitoring Proxy Overhead

```java
// Metrics collection trong proxy
public Object invoke(Object proxy, Method method, Object[] args) {
    Timer.Sample sample = Timer.start(registry);
    try {
        return method.invoke(target, args);
    } catch (InvocationTargetException e) {
        registry.counter("proxy.errors", 
            "method", method.getName()).increment();
        throw e.getCause();
    } finally {
        sample.stop(registry.timer("proxy.duration",
            "method", method.getName()));
    }
}
```

### 4.6 Anti-patterns & Pitfalls

| Anti-pattern | Hệ Quả | Giải Pháp |
|-------------|--------|-----------|
| **Proxy bloat** | Mọi thứ đều qua proxy | Selective proxying |
| **Self-invocation** | `@Transactional` không work khi internal call | AspectJ weaving hoặc self-injection |
| **Infinite recursion** | Proxy gọi chính nó | Careful handler logic |
| **ClassLoader leaks** | Dynamic proxy classes không unloaded | Proper ClassLoader management |

---

## 5. Facade Pattern

### 5.1 Bản Chất Cơ Chế

Facade pattern cung cấp **unified, simplified interface** cho một tập hợp các interfaces trong subsystem. Nó không ẩn subsystem, mà đơn giản hóa việc sử dụng.

#### Cơ Chế Hoạt Động

```
Client ──► Facade ──► SubsystemA
               ├───► SubsystemB
               ├───► SubsystemC
               └───► SubsystemD
```

**Key insight:**
- Facade là **convenience layer**, không phải **enforcement layer**
- Subsystem vẫn accessible trực tiếp nếu cần
- Facade reduce learning curve và coupling

### 5.2 Mục Tiêu Thiết Kế & Trade-offs

**Khi nào dùng:**
- Subsystem phức tạp với nhiều moving parts
- Cần layer abstraction để isolate changes
- Reduce dependencies giữa client và subsystem

**Trade-offs:**

| Ưu Điểm | Nhược Điểm |
|---------|-----------|
| Đơn giản hóa interface | Có thể thành "God Object" |
| Decouple client từ subsystem | Thêm một layer indirection |
| Centralize subsystem usage | Không prevent direct access |
| Dễ refactoring subsystem | Over-simplification mất flexibility |

### 5.3 Granularity Levels

#### High-Level Facade (Coarse-grained)

```java
// One method does everything
public class OrderServiceFacade {
    public OrderResult placeOrder(OrderRequest request) {
        validate(request);
        var inventory = inventoryService.check(request.items());
        var payment = paymentService.charge(request.payment());
        var shipping = shippingService.schedule(request.address());
        return new OrderResult(inventory, payment, shipping);
    }
}
```

#### Medium-Level Facade (Workflow-based)

```java
// Expose workflow steps
public class OrderWorkflowFacade {
    public InventoryCheck checkInventory(List<Item> items) { ... }
    public PaymentResult processPayment(PaymentRequest req) { ... }
    public ShippingSchedule scheduleShipping(Address addr) { ... }
    public void confirmOrder(OrderConfirmation conf) { ... }
}
```

#### Low-Level Facade (Thin wrapper)

```java
// Just group related operations
public class NotificationFacade {
    public void sendEmail(String to, String subject, String body) { ... }
    public void sendSMS(String phone, String message) { ... }
    public void sendPush(String deviceId, Notification notif) { ... }
}
```

### 5.4 Multi-Facade Architecture

Trong hệ thống lớn, có thể cần multiple facades:

```
                    ┌─► InternalOperationsFacade
AdminWebApp ────────┤
                    └─► ReportingFacade

                    ┌─► CheckoutFacade
CustomerMobileApp ──┤
                    └─► AccountManagementFacade

                    ┌─► APIGatewayFacade
ThirdPartyAPI ──────┤
                    └─► WebhookFacade
```

**Principle:** Mỗi facade thiết kế cho specific client/use case.

### 5.5 Production Concerns

#### Transaction Boundaries

```java
// Problem: Implicit transaction scope
public class OrderFacade {
    public void createOrder(OrderRequest req) {
        orderRepo.save(req);           // Transaction 1
        inventoryService.deduct(req);  // Transaction 2  ← Risk!
        paymentService.charge(req);    // Transaction 3  ← Risk!
    }
}

// Solution: Explicit distributed transaction or Saga
@Transactional  // If in same database
public void createOrder(OrderRequest req) {
    // All in one transaction
}

// Or Saga pattern for distributed
public void createOrder(OrderRequest req) {
    sagaOrchestrator.execute(
        new CreateOrderStep(req),
        new DeductInventoryStep(req),
        new ProcessPaymentStep(req)
    );
}
```

#### Observability

```java
public class MonitoredFacade {
    private final MeterRegistry registry;
    
    public Result operation(Request req) {
        Timer.Sample sample = Timer.start(registry);
        try {
            Result result = delegate.operation(req);
            registry.counter("facade.success", "method", "operation").increment();
            return result;
        } catch (Exception e) {
            registry.counter("facade.errors", 
                "method", "operation",
                "exception", e.getClass().getSimpleName()
            ).increment();
            throw e;
        } finally {
            sample.stop(registry.timer("facade.duration", "method", "operation"));
        }
    }
}
```

### 5.6 Anti-patterns & Pitfalls

| Anti-pattern | Hệ Quả | Giải Pháp |
|-------------|--------|-----------|
| **Leaky Facade** | Expose subsystem details | Strict encapsulation |
| **Bloated Facade** | Hundreds of methods | Split into multiple facades |
| **Facade overuse** | Facade cho simple class | YAGNI - don't use pattern blindly |
| **Hidden dependencies** | Facade giấu expensive operations | Document cost trong JavaDoc |
| **State management** | Facade giữ business state | Stateless facade |

---

## 6. So Sánh Tổng Hợp

### 6.1 Decision Matrix

| Nhu Cầu | Pattern | Lý Do |
|---------|---------|-------|
| Tích hợp incompatible APIs | **Adapter** | Interface translation |
| Add behavior dynamically | **Decorator** | Composition stack |
| Control access to object | **Proxy** | Interception point |
| Simplify complex subsystem | **Facade** | Unified interface |
| Lazy initialization | **Virtual Proxy** | Defer object creation |
| Access control | **Protection Proxy** | Permission checking |
| Cross-cutting concerns | **Dynamic Proxy** | AOP implementation |

### 6.2 Khi Nào KHÔNG Nên Dùng

| Pattern | Không Dùng Khi | Giải Pháp Thay Thế |
|---------|---------------|-------------------|
| **Adapter** | Có thể sửa code gốc | Refactoring |
| **Decorator** | Cần add method mới | Strategy pattern |
| **Proxy** | Simple delegation | Direct call |
| **Facade** | Subsystem đơn giản | Direct usage |

### 6.3 Kết Hợp Patterns

```java
// Facade + Proxy + Decorator
public class ServiceFacade {
    private final Service service;
    
    public ServiceFacade() {
        // Virtual Proxy: lazy initialization
        this.service = (Service) Proxy.newProxyInstance(
            loader,
            new Class<?>[] { Service.class },
            new VirtualProxyHandler(() -> {
                // Decorator chain
                return new LoggingDecorator(
                    new MetricsDecorator(
                        new CachingDecorator(
                            new ConcreteService()
                        )
                    )
                );
            })
        );
    }
}
```

---

## 7. Rủi Ro Production

### 7.1 Debugging Challenges

| Pattern | Vấn Đề Debugging | Giải Pháp |
|---------|------------------|-----------|
| Adapter | Stack trace dài | Exception wrapping với context |
| Decorator | Deep call stacks | Limit decorator depth, use MDC logging |
| Proxy | Generated class names | Custom toString(), proper naming |
| Facade | Hidden complexity | Document internal calls, metrics |

### 7.2 Performance Anti-patterns

| Anti-pattern | Impact | Detection |
|-------------|--------|-----------|
| Nested proxies | 10+ layers | Profiling, call stack analysis |
| Proxy trong hot loop | 10-50ns overhead per call | Micro-benchmarking |
| Facade gọi nhiều services | High latency | Distributed tracing |
| Adapter tạo object mỗi call | GC pressure | Heap profiling |

### 7.3 Testing Strategies

```java
// Decorator: Test in isolation
@Test
void loggingDecoratorLogsMethodCalls() {
    Service delegate = mock(Service.class);
    LoggingDecorator decorator = new LoggingDecorator(delegate);
    
    decorator.doSomething();
    
    verify(delegate).doSomething();
    verify(logger).info(contains("doSomething"));
}

// Proxy: Test handler logic
@Test
void proxyHandlerAddsMetrics() {
    InvocationHandler handler = new MetricsHandler(target, registry);
    Service proxy = (Service) Proxy.newProxyInstance(..., handler);
    
    proxy.operation();
    
    verify(registry).timer("proxy.duration", ...);
}

// Facade: Integration test
@Test
void orderFacadeCoordinatesServices() {
    // Given
    when(inventoryService.check(...)).thenReturn(available);
    when(paymentService.charge(...)).thenReturn(success);
    
    // When
    OrderResult result = facade.placeOrder(request);
    
    // Then
    verify(inventoryService).check(...);
    verify(paymentService).charge(...);
    verify(shippingService).schedule(...);
}
```

---

## 8. Khuyến Nghị Thực Chiến

### 8.1 Cho Từng Pattern

**Adapter:**
- Dùng cho third-party integrations
- Document expected behavior differences
- Consider bidirectional adapters cho sync scenarios

**Decorator:**
- Limit depth to < 10 layers
- Đảm bảo order independence nếu có thể
- Dùng cho cross-cutting concerns

**Proxy:**
- Virtual proxy cho expensive resources
- Dynamic proxy cho AOP/logging/metrics
- Consider CGLIB cho performance-critical paths

**Facade:**
- Thiết kế cho specific use cases
- Document transaction boundaries
- Không giấu quá nhiều - để escape hatch

### 8.2 Metrics & Observability

Tất cả structural patterns nên expose:
- Call count (success/error)
- Latency distribution (p50, p95, p99)
- Active instances (cho proxy)
- Chain depth (cho decorator)

### 8.3 Java 21+ Modernizations

- **Records**: Lightweight DTOs cho adapter translation
- **Sealed classes**: Control inheritance trong decorator
- **Virtual threads**: Proxy không cần pool management
- **Pattern matching**: Simpler type checking trong handlers

---

## 9. Kết Luận

Structural patterns giải quyết vấn đề cốt lõi của software engineering: **composition và abstraction**.

| Pattern | Bản Chất Quan Trọng Nhất |
|---------|-------------------------|
| **Adapter** | Interface compatibility không cần modify source |
| **Decorator** | Behavior extension qua composition, không phải inheritance |
| **Proxy** | Controlled access với interception capability |
| **Facade** | Simplified interface cho complex subsystems |

**Trade-off cốt lõi:** Flexibility vs Complexity. Mỗi pattern thêm một abstraction layer - chỉ dùng khi benefit justify cost.

**Rủi ro lớn nhất trong production:** Over-engineering và hidden complexity. Patterns làm code "clean" nhưng debugging khó hơn. Luôn đi kèm với proper observability.

**Quy tắc vàng:**
> "Người đọc code của bạn 6 tháng sau (có thể là chính bạn) sẽ thank bạn vì simplicity, không phải vì pattern usage."
