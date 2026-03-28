# Behavioral Design Patterns - Strategy, Observer, Template Method, Command

## 1. Mục tiêu của Task

Nghiên cứu 4 behavioral patterns cốt lõi trong Java: **Strategy**, **Observer**, **Template Method**, và **Command**. Mục tiêu là hiểu bản chất cơ chế, trade-offs trong triển khai, rủi ro production, và cách áp dụng đúng trong hệ thống thực tế.

> **Behavioral Patterns** tập trung vào cách các objects tương tác, giao tiếp, và phân chia trách nhiệm - khác với Creational (tạo objects) và Structural (tổ chức objects).

---

## 2. Strategy Pattern

### 2.1 Bản chất và cơ chế hoạt động

**Strategy** định nghĩa một họ thuật toán, đóng gói từng thuật toán, và làm chúng hoán đổi lẫn nhau. Pattern cho phép thuật toán thay đổi độc lập với clients sử dụng chúng.

**Cơ chế cốt lõi:**
- **Composition over Inheritance**: Thay vì subclass override behavior, Strategy dùng composition để inject behavior
- **Interface-based polymorphism**: Client phụ thuộc vào abstraction (Strategy interface), không phụ thuộc concrete implementation
- **Runtime flexibility**: Behavior có thể thay đổi trong lúc chạy mà không cần restart/modify code

```
┌─────────────────────────────────────────────────────────────┐
│                     Context                                  │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ - strategy: Strategy                                  │  │
│  │ + executeStrategy() → strategy.doAlgorithm()          │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ┌───────────────────┐
                    │   <<interface>>   │
                    │     Strategy      │
                    │  + doAlgorithm()  │
                    └───────────────────┘
                              │
            ┌─────────────────┼─────────────────┐
            ▼                 ▼                 ▼
    ┌───────────────┐ ┌───────────────┐ ┌───────────────┐
    │ ConcreteStrA  │ │ ConcreteStrB  │ │ ConcreteStrC  │
    │ + doAlgorithm │ │ + doAlgorithm │ │ + doAlgorithm │
    └───────────────┘ └───────────────┘ └───────────────┘
```

### 2.2 Trade-offs và so sánh

| Aspect | Strategy | Alternative (Inheritance) |
|--------|----------|---------------------------|
| **Flexibility** | Runtime changeable | Compile-time fixed |
| **Coupling** | Loose (interface) | Tight (parent class) |
| **Code reuse** | High (composition) | Limited (hierarchy) |
| **Memory** | Extra object per strategy | Shared in inheritance |
| **Testing** | Easy mock strategies | Hard to isolate |

### 2.3 Rủi ro và Anti-patterns

**1. Strategy Explosion**
- Khi có quá nhiều strategies, codebase trở nên khó quản lý
- **Giải pháp**: Factory pattern + Registry pattern để centralize strategy creation

**2. Over-engineering**
- Dùng Strategy cho behavior đơn giản, ít thay đổi
- **Nguyên tắc**: Chỉ dùng khi có ≥3 cách triển khai hoặc behavior thay đổi runtime

**3. Context bloat**
- Context class chứa quá nhiều state để pass cho strategies
- **Giải pháp**: Strategy chỉ nhận parameters cần thiết, không nhận cả Context object

### 2.4 Production Concerns

**Thread Safety**: Strategies nên là stateless hoặc immutable. Nếu cần state:
```java
// ❌ Rủi ro - Strategy có mutable state
public class StatefulStrategy implements PaymentStrategy {
    private double transactionFee; // Shared state!
}

// ✅ An toàn - Stateless hoặc immutable
public class StatelessStrategy implements PaymentStrategy {
    private final double feeRate; // Final, set once
    
    public StatelessStrategy(double feeRate) {
        this.feeRate = feeRate;
    }
}
```

**Memory Management**: Cache strategies nếu chúng expensive để tạo:
```java
public class StrategyRegistry {
    private final Map<String, PaymentStrategy> cache = new ConcurrentHashMap<>();
    
    public PaymentStrategy get(String key) {
        return cache.computeIfAbsent(key, this::createStrategy);
    }
}
```

### 2.5 Modern Java (21+)

**Records cho Immutable Strategies**:
```java
public record PercentageDiscountStrategy(double percentage) 
    implements DiscountStrategy {
    
    @Override
    public BigDecimal applyDiscount(BigDecimal price) {
        return price.multiply(BigDecimal.ONE.subtract(
            BigDecimal.valueOf(percentage / 100)));
    }
}
```

**Sealed Interfaces** (Java 17+) cho compile-time exhaustiveness:
```java
public sealed interface PaymentStrategy 
    permits CreditCardStrategy, PayPalStrategy, CryptoStrategy {}
```

---

## 3. Observer Pattern

### 3.1 Bản chất và cơ chế hoạt động

**Observer** định nghĩa một mối quan hệ one-to-many giữa các objects. Khi một object (Subject) thay đổi state, tất cả dependents (Observers) được notify và update tự động.

**Cơ chế cốt lõi:**
- **Publish-Subscribe model**: Subject không biết cụ thể Observers, chỉ biết interface
- **Push vs Pull**: Subject push thay đổi (push model) hoặc Observer pull khi cần (pull model)
- **Loose coupling**: Subject và Observer độc lập, có thể thay đổi/reuse riêng biệt

```
┌──────────────────────────────────────────────────────────────┐
│                      Subject                                 │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ - observers: List<Observer>                            │ │
│  │ + attach(Observer)                                     │ │
│  │ + detach(Observer)                                     │ │
│  │ + notify() ───────► for each obs: obs.update()        │ │
│  └────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
                               │
            ┌──────────────────┼──────────────────┐
            ▼                  ▼                  ▼
    ┌───────────────┐  ┌───────────────┐  ┌───────────────┐
    │   Observer A  │  │   Observer B  │  │   Observer C  │
    │   + update()  │  │   + update()  │  │   + update()  │
    └───────────────┘  └───────────────┘  └───────────────┘
```

### 3.2 Kiến trúc Push vs Pull

| Aspect | Push Model | Pull Model |
|--------|-----------|------------|
| **Data transfer** | Subject gửi data trong notify | Observer query Subject khi cần |
| **Coupling** | Chặt hơn (cần biết data structure) | Lỏng hơn |
| **Performance** | Có thể send data không cần | Lazy, chỉ lấy khi cần |
| **Memory** | Có thể duplicate data | Reference đến Subject |

**Best Practice**: Push model cho small/simple data, Pull model cho complex/large data.

### 3.3 Rủi ro và Failure Modes

**1. Memory Leaks (Critical)**
Observers không được unregister khi không cần nữa → Subject giữ reference mãi mãi.

```
Scenario: Activity/Controller register Observer nhưng quên unregister
         → Subject vẫn giữ reference → GC không thu hồi → Memory leak
```

**Giải pháp**:
- **WeakReference**: Subject giữ WeakReference đến Observers
- **Lifecycle-aware**: Tự động unregister khi Observer "chết"
- **Explicit cleanup**: Luôn cung cấp unregister mechanism

**2. Cascading Updates**
Observer A update → notify → Observer B update → notify → Observer C update... Có thể gây infinite loop hoặc long chain.

**Giải pháp**:
- Set flag `isNotifying` để block recursive updates
- Queue updates và batch process
- Event sourcing pattern (lưu events, process async)

**3. Notification Storm**
Subject thay đổi nhiều lần liên tiếp → notify liên tục.

**Giải pháp - Debounce/Throttle**:
```java
public class DebouncedSubject {
    private volatile boolean pendingNotification = false;
    
    public void stateChanged() {
        pendingNotification = true;
        // Chỉ notify sau khi "settle"
        scheduler.schedule(this::notifyObservers, 100, TimeUnit.MILLISECONDS);
    }
}
```

### 3.4 Production Concerns

**Concurrency Issues**:
```java
public class ThreadSafeSubject {
    private final CopyOnWriteArrayList<Observer> observers = 
        new CopyOnWriteArrayList<>();
    // Hoặc dùng ReadWriteLock nếu write hiếm
    
    public void notifyObservers() {
        // Snapshot iteration - an toàn cho concurrent modification
        for (Observer obs : observers) {
            try {
                obs.update(this);
            } catch (Exception e) {
                // Log nhưng đừng để một observer failed làm failed cả batch
                logger.error("Observer failed", e);
            }
        }
    }
}
```

**Async Notification**:
```java
public void notifyAsync() {
    observers.forEach(obs -> 
        executor.submit(() -> {
            try {
                obs.update(this);
            } catch (Exception e) {
                logger.error("Async observer failed", e);
            }
        })
    );
}
```

### 3.5 Modern Java - Flow API (Java 9+)

Java 9+ cung cấp `java.util.concurrent.Flow` - reactive streams implementation:

```java
public class ModernSubject implements Flow.Publisher<Event> {
    private final SubmissionPublisher<Event> publisher = 
        new SubmissionPublisher<>(ForkJoinPool.commonPool(), 256);
    
    public void emit(Event event) {
        publisher.submit(event);
    }
    
    @Override
    public void subscribe(Flow.Subscriber<? super Event> subscriber) {
        publisher.subscribe(subscriber);
    }
}
```

**Ưu điểm**: Backpressure handling, async built-in, standard API.

---

## 4. Template Method Pattern

### 4.1 Bản chất và cơ chế hoạt động

**Template Method** định nghĩa skeleton của một thuật toán trong method, deferring một số steps cho subclasses. Template method cho phép subclasses redefine certain steps mà không thay đổi algorithm's structure.

**Cơ chế cốt lõi:**
- **Hollywood Principle**: "Don't call us, we'll call you" - base class điều khiển flow, subclasses cung cấp implementation
- **Inversion of Control**: Control flow ngược lại so với thông thường (client code gọi library)
- **Template + Hook**: Template method cố định, Hook methods customizable

```
┌──────────────────────────────────────────────────────────────┐
│                    AbstractClass                             │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ templateMethod() {      // FINAL - không thể override  │ │
│  │   step1();              // Fixed                        │ │
│  │   step2();              // Hook - subclass implements   │ │
│  │   step3();              // Fixed                        │ │
│  │   hook();               // Optional override            │ │
│  │ }                                                       │ │
│  └────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
                               ▲
                               │ extends
            ┌──────────────────┼──────────────────┐
            ▼                  ▼                  ▼
    ┌───────────────┐  ┌───────────────┐  ┌───────────────┐
│ ConcreteClassA  │  │ ConcreteClassB  │
│  + step2()      │  │  + step2()      │
│  + hook()       │  │  // dùng default│
└─────────────────┘  └─────────────────┘
```

### 4.2 Trade-offs vs Strategy Pattern

| Aspect | Template Method | Strategy |
|--------|----------------|----------|
| **Relationship** | Is-a (inheritance) | Has-a (composition) |
| **Flexibility** | Compile-time | Runtime |
| **Code sharing** | High (common code in base) | Lower (each strategy standalone) |
| **Coupling** | Chặt (coupled to base class) | Lỏng |
| **Visibility** | Protected methods | Public interface |

**Quy tắc chọn**:
- Dùng **Template Method** khi: nhiều classes share common algorithm structure, chỉ khác một số steps
- Dùng **Strategy** khi: cần runtime flexibility, behaviors hoàn toàn khác nhau

### 4.3 Rủi ro và Anti-patterns

**1. Fragile Base Class Problem**
Base class thay đổi → tất cả subclasses có thể break. Đây là vấn đề inherent của inheritance.

**Giải pháp**:
- Template method phải `final` để ngăn override
- Document rõ ràng which methods are "internal" vs "public API"
- Versioning cho base class changes

**2. God Class**
Base class chứa quá nhiều logic, trở thành "god class" khó maintain.

**Giải pháp**:
- Composition: Base class delegate cho helper classes
- Strategy within Template: Template method dùng Strategy cho các steps

**3. Leaky Abstraction**
Subclasses phụ thuộc vào implementation details của base class.

**Giải pháp**:
- Minimize protected methods
- Document contract rõ ràng
- Unit test base class behavior độc lập

### 4.4 Production Concerns

**Testing Strategy**:
```java
// Test Template Method behavior
@Test
void templateMethodShouldCallStepsInOrder() {
    List<String> calls = new ArrayList<>();
    
    AbstractClass template = new AbstractClass() {
        @Override protected void step2() { calls.add("step2"); }
        @Override protected void hook() { calls.add("hook"); }
    };
    
    template.templateMethod();
    
    assertEquals(List.of("step1", "step2", "step3", "hook"), calls);
}
```

**Extension Points**:
```java
public abstract class DataProcessor {
    // Template method - FINAL
    public final void process(String data) {
        validate(data);
        String transformed = transform(data);
        save(transformed);
        // Hook cho post-processing
        afterProcess();
    }
    
    // Abstract - subclass MUST implement
    protected abstract String transform(String data);
    
    // Hook - subclass CAN override
    protected void afterProcess() {
        // Default: do nothing
    }
    
    // Fixed - subclass CANNOT change
    private void validate(String data) { ... }
    private void save(String data) { ... }
}
```

### 4.5 Modern Java - Functional Approach

**Functional Template Method** (Java 8+):
```java
public class FunctionalTemplate {
    private final Consumer<String> validator;
    private final Function<String, String> transformer;
    private final Consumer<String> saver;
    
    public void process(String data) {
        validator.accept(data);
        String result = transformer.apply(data);
        saver.accept(result);
    }
}

// Usage - composition thay vì inheritance
var processor = new FunctionalTemplate(
    DataValidator::validate,
    DataTransformer::transform,
    DataSaver::save
);
```

**Ưu điểm**: No inheritance, testable, có thể combine behaviors linh hoạt.

---

## 5. Command Pattern

### 5.1 Bản chất và cơ chế hoạt động

**Command** encapsulates a request as an object, cho phép parametrize clients với different requests, queue or log requests, và support undoable operations.

**Cơ chế cốt lõi:**
- **Encapsulation của action**: Command object chứa tất cả thông tin cần thiết để thực thi một action
- **Decoupling**: Invoker không biết về Receiver, chỉ biết Command interface
- **First-class operation**: Operations có thể được stored, passed, manipulated như objects

```
┌──────────────┐      ┌─────────────────────────────┐
│   Invoker    │──────│  Command (interface)        │
│ - command    │      │  + execute()                │
│ + setCommand │      │  + undo()                   │
│ + invoke()   │      └─────────────────────────────┘
└──────────────┘                    │
                                    ▼
                         ┌────────────────────┐
                         │   ConcreteCommand  │
                         │  - receiver        │
                         │  - state           │
                         │  + execute()       │
                         │  + undo()          │
                         └────────────────────┘
                                    │
                                    ▼
                         ┌────────────────────┐
                         │     Receiver       │
                         │  + action()        │
                         └────────────────────┘
```

### 5.2 Các biến thể và use cases

| Use Case | Implementation |
|----------|---------------|
| **Undo/Redo** | Command lưu previous state, undo() restore |
| **Macro/Composite** | CompositeCommand chứa list commands |
| **Queue/Scheduler** | Commands vào queue, process async |
| **Logging/Audit** | Serialize commands để replay |
| **Transaction** | Batch commands, rollback nếu failed |

### 5.3 Rủi ro và Anti-patterns

**1. Command Proliferation**
Mỗi action tạo một class → class explosion.

**Giải pháp**:
- Generic Command với lambda:
```java
public class FunctionalCommand implements Command {
    private final Runnable executeAction;
    private final Runnable undoAction;
    
    public FunctionalCommand(Runnable execute, Runnable undo) {
        this.executeAction = execute;
        this.undoAction = undo;
    }
    
    @Override public void execute() { executeAction.run(); }
    @Override public void undo() { undoAction.run(); }
}
```

**2. State Synchronization (Undo)**
Undo phức tạp khi state được modify bởi nhiều sources.

**Giải pháp**:
- Memento Pattern: Lưu snapshot state thay vì inverse operation
- Event Sourcing: Lưu events, replay để rebuild state
- Immutable state: Mỗi change tạo new state

**3. Long-lived Commands**
Commands giữ reference đến large objects → memory leak.

**Giải pháp**:
- Command chỉ giữ necessary state (IDs, values), không giữ cả object
- Clear references sau khi execute
- Soft references cho large cached state

### 5.4 Production Concerns

**Idempotency**:
Commands trong distributed systems phải idempotent để safe retry:
```java
public class IdempotentCommand implements Command {
    private final String idempotencyKey;
    
    @Override
    public void execute() {
        if (idempotencyStore.isProcessed(idempotencyKey)) {
            return; // Already processed
        }
        // ... actual logic
        idempotencyStore.markProcessed(idempotencyKey);
    }
}
```

**Command Queue và Backpressure**:
```java
public class BoundedCommandQueue {
    private final BlockingQueue<Command> queue = 
        new ArrayBlockingQueue<>(1000);
    
    public void enqueue(Command cmd) throws QueueFullException {
        if (!queue.offer(cmd)) {
            throw new QueueFullException();
        }
    }
    
    // Consumer
    public void process() {
        while (running) {
            Command cmd = queue.take();
            try {
                cmd.execute();
            } catch (Exception e) {
                // Log và có thể retry/dead-letter
                handleFailedCommand(cmd, e);
            }
        }
    }
}
```

**Persistence và Recovery**:
```java
public class PersistentCommand implements Serializable {
    private final UUID commandId;
    private final Instant createdAt;
    private final CommandPayload payload;
    
    // Serialize để log/store
    public String toJson() { ... }
    
    // Deserialize để replay
    public static PersistentCommand fromJson(String json) { ... }
}
```

### 5.5 Modern Java - Records và Sealed Classes

**Record cho Immutable Commands** (Java 16+):
```java
public record CreateOrderCommand(
    UUID orderId,
    UUID customerId,
    List<OrderLine> items,
    Instant timestamp
) implements Command {
    
    @Override
    public void execute() {
        // OrderService.createOrder(this);
    }
}
```

**Sealed Interface cho Command Hierarchy** (Java 17+):
```java
public sealed interface Command 
    permits CreateOrderCommand, UpdateInventoryCommand, 
            SendNotificationCommand { }
```

**CompletableFuture cho Async Commands**:
```java
public class AsyncCommandExecutor {
    public CompletableFuture<Void> executeAsync(Command command) {
        return CompletableFuture.runAsync(command::execute, executor)
            .exceptionally(ex -> {
                logger.error("Command failed", ex);
                return null;
            });
    }
}
```

---

## 6. So sánh tổng quát 4 Patterns

| Pattern | Intent | Relationship | Primary Use Case |
|---------|--------|--------------|------------------|
| **Strategy** | Interchangeable algorithms | Has-a (composition) | Runtime algorithm selection |
| **Observer** | State change notification | One-to-many | Event handling, pub-sub |
| **Template Method** | Algorithm skeleton | Is-a (inheritance) | Code reuse, fixed structure |
| **Command** | Encapsulate request | Object as operation | Undo, queue, audit, async |

---

## 7. Anti-patterns chung cho Behavioral Patterns

### 7.1 Over-engineering
- **Triệu chứng**: Dùng pattern cho vấn đề đơn giản
- **Giải pháp**: YAGNI (You Aren't Gonna Need It) - đợi đến khi thực sự cần

### 7.2 Premature Abstraction
- **Triệu chứng**: Abstract quá sớm, trước khi hiểu rõ requirements
- **Giải pháp**: Concrete implementation trước, abstract sau khi patterns emerge

### 7.3 Hidden Dependencies
- **Triệu chứng**: Observers/Strategies giữ reference đến nhiều objects khác
- **Giải pháp**: Dependency injection, explicit constructor parameters

### 7.4 Testing Challenges
- **Triệu chứng**: Patterns làm code khó test vì indirection
- **Giải pháp**: 
  - Unit test components độc lập
  - Dùng mocks cho dependencies
  - Integration test cho full flow

---

## 8. Khuyến nghị thực chiến

### 8.1 Khi nào dùng pattern nào

**Strategy**:
- ✅ Nhiều cách triển khai cho cùng một operation
- ✅ Cần đổi algorithm runtime
- ✅ Tránh large if-else/switch statements

**Observer**:
- ✅ Event-driven architecture
- ✅ Decoupling publishers và subscribers
- ⚠️ Cẩn thận memory leaks và notification storms

**Template Method**:
- ✅ Common algorithm với customizable steps
- ✅ Framework/library design
- ⚠️ Tránh nếu cần runtime flexibility

**Command**:
- ✅ Undo/redo functionality
- ✅ Audit log và replay
- ✅ Async/queued processing
- ✅ Transaction management

### 8.2 Best Practices Production

1. **Composition over Inheritance**: Ưu tiên Strategy và Command hơn Template Method
2. **Immutability**: Strategies và Commands nên immutable khi có thể
3. **Error Handling**: Observer và Command cần robust error handling
4. **Monitoring**: Track metrics (queue size, execution time, failure rate)
5. **Testing**: Unit test từng component, integration test full flow

### 8.3 Modern Java Recommendations

- Dùng **Records** (Java 16+) cho immutable commands và events
- Dùng **Sealed Classes** (Java 17+) cho exhaustive pattern matching
- Dùng **Flow API** (Java 9+) thay vì custom Observer cho reactive streams
- Dùng **CompletableFuture** cho async command execution
- Dùng **Functional interfaces** để reduce boilerplate

---

## 9. Kết luận

4 behavioral patterns này giải quyết các vấn đề cốt lõi trong software design:

1. **Strategy**: Tách biệt algorithm khỏi context, cho phép linh hoạt runtime
2. **Observer**: Decouple state changes từ state consumers
3. **Template Method**: Định nghĩa algorithm structure, delegate specific steps
4. **Command**: Treat operations as first-class citizens

**Bản chất chung**: Tất cả đều nhằm **giảm coupling** và **tăng cohesion**, dù bằng composition (Strategy, Command, Observer) hay inheritance (Template Method).

**Trade-off quan trọng nhất**: Composition (flexibility, testability) vs Inheritance (code reuse, simplicity). Trong production Java hiện đại, **composition được ưu tiên**.

**Rủi ro lớn nhất**: 
- **Observer**: Memory leaks từ forgotten unregistration
- **Command**: State synchronization trong undo/redo
- **Template Method**: Fragile base class problem
- **Strategy**: Strategy explosion khi quá nhiều variants

**Lời khuyên cuối**: Patterns là tools, không phải goals. Hiểu bản chất vấn đề trước, chọn pattern sau. Đôi khi một simple lambda expression hiệu quả hơn cả pattern hierarchy.
