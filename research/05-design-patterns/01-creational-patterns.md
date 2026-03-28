# Creational Patterns - Singleton, Factory, Builder, Object Pool

## 1. Mục tiêu của Task

Nghiên cứu sâu 4 creational patterns cơ bản trong Java: Singleton, Factory, Builder, và Object Pool. 
Tập trung vào bản chất thiết kế, cơ chế hoạt động tầng thấp, trade-offs trong production, và các pitfall thực tế mà senior engineer cần nhận thức.

---

## 2. Singleton Pattern

### 2.1 Bản chất và cơ chế hoạt động

> Singleton đảm bảo **một class chỉ có một instance duy nhất** và cung cấp **global access point** tới instance đó.

Bản chất vấn đề Singleton giải quyết:
- **Controlled access to sole instance**: Ngăn chặn việc tạo nhiều instance của resource đắt đỏ (database connection pool, thread pool, cache manager)
- **Lazy initialization**: Instance chỉ được tạo khi thực sự cần
- **Global state management**: State được share xuyên suốt application

### 2.2 Các cách triển khai và so sánh chi tiết

| Approach | Thread-Safe | Lazy Init | Performance | Use Case |
|----------|-------------|-----------|-------------|----------|
| Eager Initialization | ✅ Có | ❌ Không | ⭐⭐⭐⭐⭐ Tốt nhất | Instance nhẹ, luôn cần |
| Synchronized Method | ✅ Có | ✅ Có | ⭐⭐⭐ Tệ nhất (bottleneck) | Không khuyến khích |
| Double-Checked Locking | ✅ Có | ✅ Có | ⭐⭐⭐⭐⭐ Tốt | Java 4+ |
| Bill Pugh (Static Holder) | ✅ Có | ✅ Có | ⭐⭐⭐⭐⭐ Tốt | **Khuyến nghị** Java 5+ |
| Enum | ✅ Có | ❌ Không | ⭐⭐⭐⭐⭐ Tốt | **Best practice**, chống reflection/serialization attacks |

#### Bill Pugh Pattern (Khuyến nghị cho Java 5-20)

```java
public class Singleton {
    private Singleton() {}
    
    private static class SingletonHolder {
        private static final Singleton INSTANCE = new Singleton();
    }
    
    public static Singleton getInstance() {
        return SingletonHolder.INSTANCE;
    }
}
```

**Cơ chế hoạt động tầng JVM:**
- Static inner class `SingletonHolder` **không được load** khi outer class được load
- Class loading là **lazy và thread-safe** theo Java Language Specification (JLS 12.4.2)
- JVM đảm bảo class initialization là sequential, do đó thread-safe mà không cần synchronization

#### Enum Pattern (Best Practice Java 5+)

```java
public enum Singleton {
    INSTANCE;
    
    // methods and fields
}
```

**Tại sao Enum là best practice?**
- JVM đảm bảo chỉ một instance duy nhất
- **Serialization-safe**: `readResolve()` được tự động xử lý
- **Reflection-safe**: Constructor của enum không thể bị gọi qua reflection
- **Thread-safe**: Instance được tạo khi enum được load, và class loading là thread-safe

### 2.3 Trade-offs và Production Concerns

**Khi nào KHÔNG nên dùng Singleton:**
- **Unit testing**: Singleton tạo hidden dependency, khó mock
- **Multi-JVM/Cluster**: Singleton chỉ đảm bảo uniqueness trong một JVM, không phải cluster
- **Stateful singletons**: Gây khó khăn cho horizontal scaling, state phải externalize (Redis, DB)
- **Microservices**: Mỗi instance service có singleton riêng → cần distributed lock nếu cần uniqueness

**Anti-patterns và Pitfalls:**

| Anti-Pattern | Vấn đề | Giải pháp |
|-------------|--------|-----------|
| God Singleton | Class phình to, làm quá nhiều việc | Tách thành specialized singletons hoặc dependency injection |
| Hidden Dependencies | Các class gọi `Singleton.getInstance()` trực tiếp | Dùng Dependency Injection (Spring, Guice) |
| Premature Singleton | Đánh dấu singleton khi chưa cần | YAGNI - chỉ áp dụng khi thực sự cần controlled instance |
| Testing Nightmare | Khó mock, khó reset state giữa tests | Dùng interface + DI, hoặc package-private constructor cho tests |

**Production Concerns:**
- **Memory leaks**: Singleton giữ reference suốt vòng đỏi app → đảm bảo không giữ reference tới short-lived objects
- **ClassLoader issues**: Trong app servers (Tomcat), singleton có thể bị load bởi nhiều ClassLoader
- **Shutdown hooks**: Singleton cần cleanup (connection pool, thread pool) khi app shutdown

---

## 3. Factory Pattern

### 3.1 Bản chất và phân loại

> Factory pattern **tách rồi quá trình khởi tạo object** khỏi code sử dụng object, cho phép **tạo object mà không cần chỉ định exact class**.

Ba biến thể chính:
1. **Simple Factory** (Không phải GoF pattern nhưng phổ biến)
2. **Factory Method** (GoF)
3. **Abstract Factory** (GoF)

### 3.2 Factory Method Pattern

**Bản chất thiết kế:**
- Định nghĩa interface cho việc tạo object, nhưng để **subclass quyết định** class nào được instantiate
- "Tạo object" được推迟 (defer) tới subclass

**Luồng hoạt động:**

```
Client → Creator.createProduct()
              ↓
         ConcreteCreator.createProduct()
              ↓
         new ConcreteProduct()
```

**Ví dụ thực tế - Database Connection Factory:**

```java
// Creator
public abstract class ConnectionFactory {
    public abstract Connection createConnection();
    
    // Template method pattern kết hợp
    public Connection getConnection() {
        Connection conn = createConnection();
        configureConnection(conn);
        return conn;
    }
}

// Concrete Creators
public class PostgreSQLConnectionFactory extends ConnectionFactory {
    @Override
    public Connection createConnection() {
        // PostgreSQL-specific logic
        return DriverManager.getConnection(url);
    }
}

public class MySQLConnectionFactory extends ConnectionFactory {
    @Override
    public Connection createConnection() {
        // MySQL-specific logic
        return DriverManager.getConnection(url);
    }
}
```

### 3.3 Abstract Factory Pattern

**Bản chất thiết kế:**
- Tạo **families of related objects** mà không cần chỉ định concrete classes
- Đảm bảo objects trong cùng family tương thích với nhau

**Use case: Cross-platform UI toolkit**

```
GUIFactory (Abstract Factory)
├── createButton() → Button
├── createCheckbox() → Checkbox
└── createTextField() → TextField

WinFactory implements GUIFactory → WinButton, WinCheckbox, WinTextField
MacFactory implements GUIFactory → MacButton, MacCheckbox, MacTextField
```

**Luồng hoạt động:**

```
Application ← uses → GUIFactory (interface)
                           ↓
              ┌────────────┴────────────┐
         WinFactory                 MacFactory
              ↓                          ↓
    WinButton | WinCheckbox     MacButton | MacCheckbox
    
→ Application chỉ biết GUIFactory, không biết platform cụ thể
```

### 3.4 Trade-offs và Production Concerns

**Factory Method vs Abstract Factory:**

| Tiêu chí | Factory Method | Abstract Factory |
|----------|----------------|------------------|
| **Mục tiêu** | Tạo **single product** | Tạo **family of products** |
| **Mở rộng** | Thêm subclass của Creator | Thêm factory mới cho family mới |
| **Complexity** | Thấp | Cao hơn |
| **Use case** | Một loại object, nhiều cách tạo | Nhiều loại object liên quan |

**Khi nào dùng Factory:**
- ✅ Không biết trước exact types của objects cần tạo (runtime decision)
- ✅ Tạo objects phức tạp với nhiều dependencies
- ✅ Muốn cung cấp hook cho extension qua subclassing
- ✅ Decouple object creation khỏi business logic

**Khi nào KHÔNG nên dùng:**
- ❌ Simple object creation (over-engineering)
- ❌ Không có variation trong cách tạo object
- ❌ Chỉ có một concrete implementation

**Production Concerns:**
- **Performance overhead**: Virtual dispatch qua factory method có cost nhỏ (nanoseconds)
- **Testing**: Factory làm dễ mock, nhưng static factories khó test hơn instance factories
- **Configuration-driven**: Factory thường kết hợp với configuration để chọn implementation

---

## 4. Builder Pattern

### 4.1 Bản chất và cơ chế hoạt động

> Builder pattern **tách rời construction của complex object** từ representation của nó, cho phép **cùng một construction process tạo ra different representations**.

**Vấn đề Builder giải quyết:**
- Telescoping constructor anti-pattern
- Mutable objects trong multi-step construction
- Optional parameters với nhiều combinations
- Immutable objects với nhiều fields

### 4.2 Telescoping Constructor Anti-Pattern

```java
// BAD: Telescoping constructor
public class Pizza {
    public Pizza(int size) { ... }
    public Pizza(int size, boolean cheese) { ... }
    public Pizza(int size, boolean cheese, boolean pepperoni) { ... }
    public Pizza(int size, boolean cheese, boolean pepperoni, boolean bacon) { ... }
    // ... explosion!
}
```

**Vấn đề:**
- 2^n constructors cho n optional parameters
- Khó đọc: `new Pizza(12, true, false, true)` - không biết boolean nào là gì
- Error-prone: Thứ tự parameters dễ nhầm lẫn

### 4.3 Classic Builder Pattern

```java
public class Pizza {
    private final int size;
    private final boolean cheese;
    private final boolean pepperoni;
    
    private Pizza(Builder builder) {
        this.size = builder.size;
        this.cheese = builder.cheese;
        this.pepperoni = builder.pepperoni;
    }
    
    public static class Builder {
        private int size;  // required
        private boolean cheese = false;  // optional, default
        private boolean pepperoni = false;
        
        public Builder(int size) {
            this.size = size;
        }
        
        public Builder cheese(boolean val) {
            cheese = val;
            return this;
        }
        
        public Builder pepperoni(boolean val) {
            pepperoni = val;
            return this;
        }
        
        public Pizza build() {
            validate();
            return new Pizza(this);
        }
        
        private void validate() {
            if (size < 8 || size > 16) {
                throw new IllegalArgumentException("Invalid size");
            }
        }
    }
}

// Usage
Pizza pizza = new Pizza.Builder(12)
    .cheese(true)
    .pepperoni(true)
    .build();
```

**Cơ chế hoạt động:**
1. Outer class constructor là `private`, chỉ accessible từ Builder
2. Builder là `static inner class` → không cần outer class instance
3. Fluent API (method chaining) → readable, self-documenting
4. Validation tập trung tại `build()` method
5. Result object là **immutable** → thread-safe

### 4.4 Step Builder Pattern (Advanced)

**Use case:** Bắt buộc thứ tự build, compile-time safety

```java
public interface PizzaBuilder {
    CrustBuilder size(int size);
}

public interface CrustBuilder {
    SauceBuilder crust(String crust);
}

public interface SauceBuilder {
    ToppingBuilder sauce(String sauce);
}

public interface ToppingBuilder {
    BuildBuilder topping(String topping);
}

public interface BuildBuilder {
    Pizza build();
}

public class PizzaStepBuilder implements 
    PizzaBuilder, CrustBuilder, SauceBuilder, ToppingBuilder, BuildBuilder {
    
    // Implementation with state tracking
    // Each interface method returns next step interface
}

// Usage - enforced order
Pizza pizza = PizzaStepBuilder.newBuilder()
    .size(12)        // returns CrustBuilder
    .crust("thin")   // returns SauceBuilder
    .sauce("tomato") // returns ToppingBuilder
    .topping("cheese")
    .build();
```

### 4.5 Lombok @Builder - Cẩn trọng khi dùng

```java
@Builder
public class User {
    private final String name;
    private final String email;
}
```

**Lombok @Builder trade-offs:**

| Ưu điểm | Nhược điểm |
|---------|-----------|
| Code ngắn gọn | Không thể thêm custom validation logic dễ dàng |
| Không boilerplate | Không thể thiết lập default values phức tạp |
| ToBuilder() support | Không có step builder |

**Best practice:**
- Dùng Lombok cho simple DTOs
- Viết manual builder cho complex domain objects cần validation

### 4.6 Trade-offs và Production Concerns

**Builder vs Constructor vs Factory:**

| Pattern | Use Case | Pros | Cons |
|---------|----------|------|------|
| **Constructor** | ≤3 parameters, all required | Simple, performant | Telescoping với nhiều params |
| **Static Factory** | Named constructors, caching | Readable | Không scale với optional params |
| **Builder** | ≥4 parameters, nhiều optional | Fluent, flexible, immutable | Verbose, extra class |

**Performance consideration:**
- Builder tạo **extra object** (Builder instance) → GC pressure nhỏ
- Nếu tạo hàng triệu objects, consider object pooling hoặc constructor

**Production Concerns:**
- **Partially built objects**: Builder pattern ngăn chặn việc này (object chỉ tồn tại sau `build()`)
- **Thread safety**: Builder không thread-safe → không share builder giữa threads
- **Copy constructor**: `toBuilder()` cho phép modify existing immutable objects

---

## 5. Object Pool Pattern

### 5.1 Bản chất và cơ chế hoạt động

> Object Pool quản lý **tập hợp reusable objects**, giảm overhead của frequent creation và destruction.

**Vấn đề Object Pool giải quyết:**
- Object creation cost cao (database connections, threads)
- Garbage collection pressure từ short-lived objects
- Resource limits (số connections tới DB giới hạn)

**Luồng hoạt động:**

```
┌─────────────────────────────────────────────────────┐
│                    Object Pool                       │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────┐  │
│  │ Available   │    │   In Use    │    │ Max     │  │
│  │   Pool      │    │    Pool     │    │ Limit   │  │
│  │  [obj1]     │    │   [obj3]    │    │  = 10   │  │
│  │  [obj2]     │    │   [obj4]    │    │         │  │
│  └─────────────┘    └─────────────┘    └─────────┘  │
└─────────────────────────────────────────────────────┘

Client → acquire() → Available pool empty? 
    Yes → Create new (nếu < max) hoặc Wait/Block
    No → Pop from Available → Add to In Use → Return object

Client → release(object) → Remove from In Use → 
    Validate → Reset state → Push to Available
```

### 5.2 Các thành phần cốt lõi

| Component | Trách nhiệm |
|-----------|-------------|
| **Pool** | Quản lý available/in-use collections |
| **PooledObject** | Wrapper object chứa state (creation time, last used, borrow count) |
| **Factory** | Tạo, validate, destroy objects |
| **Eviction Policy** | Quyết định khi nào remove objects khỏi pool |

### 5.3 Apache Commons Pool 2 - Implementation Reference

```java
public interface ObjectPool<T> {
    T borrowObject() throws Exception;
    void returnObject(T obj) throws Exception;
    void invalidateObject(T obj) throws Exception;
    int getNumActive();
    int getNumIdle();
    void clear();
    void close();
}
```

**GenericObjectPoolConfig quan trọng:**

| Property | Ý nghĩa | Trade-off |
|----------|---------|-----------|
| `maxTotal` | Max objects trong pool | Cao = nhiều resource, Thấp = contention |
| `maxIdle` | Max idle objects | Cao = nhanh borrow, Thấp = tiết kiệm memory |
| `minIdle` | Min idle objects duy trì | Warm pool vs resource waste |
| `maxWaitMillis` | Thời gian chờ borrow | Block vs fail-fast |
| `testOnBorrow` | Validate trước khi cho mượn | Latency vs lỗi runtime |
| `timeBetweenEvictionRuns` | Chu kỳ kiểm tra idle objects | Tính realtime vs overhead |

### 5.4 Connection Pool (HikariCP) - Case Study

**Tại sao HikariCP nhanh?**

| Optimization | Cơ chế |
|-------------|--------|
| **FastList** | ArrayList custom bỏ range check, không synchronized |
| **ConcurrentBag** | Lock-free structure cho connection handoff |
| **Byte-code generation** | Tạo proxy class ở runtime thay vì reflection |
| **Minimal locks** | Synchronization chỉ khi thực sự cần |

**HikariCP Configuration Trade-offs:**

```properties
# Tính toán pool size optimal
# Formula: connections = ((core_count * 2) + effective_spindle_count)
# Với SSD: effective_spindle_count = core_count
# connections = core_count * 3

maximumPoolSize=20
minimumIdle=10
connectionTimeout=30000
idleTimeout=600000
maxLifetime=1800000
```

### 5.5 Trade-offs và Production Concerns

**Khi nào dùng Object Pool:**
- ✅ Object creation cost > object reuse cost (DB connections, threads)
- ✅ Objects là scarce resources (giới hạn số lượng)
- ✅ Objects stateless hoặc dễ reset
- ✅ High-frequency usage

**Khi nào KHÔNG nên dùng:**
- ❌ Object creation cheap (POJOs, DTOs) → GC tối ưu cho short-lived objects
- ❌ Objects stateful phức tạp → Reset state bug-prone
- ❌ Low-frequency usage → Pool overhead > creation cost

**Anti-patterns và Pitfalls:**

| Anti-Pattern | Vấn đề | Giải pháp |
|-------------|--------|-----------|
| **Pool exhaustion** | Tất cả objects đang dùng, request mới block/timeout | Proper sizing, circuit breaker |
| **Resource leak** | Borrow mà không return → pool drain | try-finally hoặc try-with-resources pattern |
| **Stale objects** | Object trong pool đã invalid (DB connection closed) | testOnBorrow/testWhileIdle |
| **Object contamination** | State từ previous use còn sót | Reset state trong returnObject |

**Production Monitoring:**

```java
// HikariCP metrics
HikariPoolMXBean poolMXBean = hikariDataSource.getHikariPoolMXBean();

int activeConnections = poolMXBean.getActiveConnections();
int idleConnections = poolMXBean.getIdleConnections();
int totalConnections = poolMXBean.getTotalConnections();
int pendingThreads = poolMXBean.getThreadsAwaitingConnection();

// Alerts:
// - pendingThreads > 0: Pool undersized hoặc query chậm
// - activeConnections ≈ totalConnections: Cần scale
```

---

## 6. So sánh tổng quan Creational Patterns

```
┌─────────────────────────────────────────────────────────────────────┐
│                    CREATIONAL PATTERNS COMPARISON                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   Singleton: "Chỉ một, duy nhất, toàn cục"                          │
│   ├── Use: Resource đắt đỏ, global state                            │
│   ├── Risk: Testing, hidden coupling, distributed systems           │
│   └── Modern alternative: Spring @Component singleton scope         │
│                                                                      │
│   Factory: "Tạo object mà không cần biết class cụ thể"              │
│   ├── Use: Polymorphic creation, complex initialization             │
│   ├── Risk: Over-engineering, extra abstraction layer               │
│   └── Modern: Supplier<T>, Method Reference                         │
│                                                                      │
│   Builder: "Từng bước xây dựng object phức tạp"                     │
│   ├── Use: Nhiều optional params, immutable objects                 │
│   ├── Risk: Verbose, extra allocations                              │
│   └── Modern: Lombok @Builder, Records with compact constructors    │
│                                                                      │
│   Object Pool: "Tái sử dụng thay vì tạo mới"                        │
│   ├── Use: Expensive creation, resource limits                      │
│   ├── Risk: Leaks, stale objects, sizing complexity                 │
│   └── Modern: HikariCP, Apache Commons Pool, custom ThreadPool      │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.1 Modern Java Alternatives

| Pattern | Traditional | Modern Java (8+) |
|---------|-------------|------------------|
| Singleton | Enum/Holder | Spring/CDI @Singleton |
| Factory | Factory Method | Supplier<T>, Dependency Injection |
| Builder | Manual Builder | Lombok, Records, MapStruct |
| Object Pool | Custom pool | HikariCP, ForkJoinPool, parallel streams |

---

## 7. Anti-Patterns và Common Mistakes

### 7.1 Service Locator vs Dependency Injection

**Service Locator (Anti-pattern):**
```java
// BAD: Hidden dependency, khó test
public class OrderService {
    public void processOrder() {
        PaymentService payment = ServiceLocator.getPaymentService();
        // ...
    }
}
```

**Dependency Injection (Best practice):**
```java
// GOOD: Explicit dependency, dễ test
public class OrderService {
    private final PaymentService payment;
    
    public OrderService(PaymentService payment) {
        this.payment = payment;
    }
}
```

### 7.2 God Object / God Factory

Một class/factory tạo quá nhiều loại objects không liên quan → Violates Single Responsibility Principle.

### 7.3 Premature Abstraction

Áp dụng pattern khi chưa cần → Over-engineering. YAGNI principle.

---

## 8. Khuyến nghị thực chiến Production

### 8.1 Decision Tree cho Creational Patterns

```
Cần tạo object?
├── Chỉ cần 1 instance duy nhất?
│   ├── Stateless, shareable? → Singleton (Enum hoặc DI framework)
│   └── Stateful per request? → Prototype/Request scope
│
├── Object creation phức tạp, nhiều bước?
│   ├── Nhiều optional parameters? → Builder Pattern
│   └── Tạo family of related objects? → Abstract Factory
│
├── Không biết trước exact type cần tạo?
│   └── Factory Method hoặc Dependency Injection
│
├── Object expensive để tạo, dùng lại được?
│   └── Object Pool (DB connections, threads)
│
└── Đơn giản, ít params?
    └── Constructor hoặc Static Factory Method
```

### 8.2 Checklist khi áp dụng

| Pattern | Pre-implementation Checklist |
|---------|------------------------------|
| **Singleton** | Có thực sự cần global state? Đã cân nhắc DI thay thế? Test strategy? |
| **Factory** | Có nhiều implementation? Cần decouple creation? |
| **Builder** | ≥4 params? Nhiều optional? Cần immutable? |
| **Object Pool** | Creation cost > reuse cost? Limited resource? |

### 8.3 Monitoring và Observability

- **Pool metrics**: Active/idle/total connections, wait time
- **Object creation rate**: Objects created/sec qua Factory/Builder
- **Memory usage**: Pool size impact on heap
- **Latency**: Borrow time từ pool

---

## 9. Kết luận

### Bản chất cốt lõi

| Pattern | Bản chất | Bài toán giải quyết |
|---------|----------|---------------------|
| **Singleton** | Controlled single instance + global access | Resource đắt đỏ, global coordination |
| **Factory** | Encapsulate creation logic | Decouple, enable polymorphic creation |
| **Builder** | Step-by-step construction | Complex initialization, immutability |
| **Object Pool** | Reuse over recreate | Resource efficiency, GC optimization |

### Trade-off quan trọng nhất

> **Abstraction vs Complexity**: Mỗi pattern thêm layer abstraction. Abstraction có ích khi giải quyết vấn đề thực sự, nhưng trở thành burden khi over-applied.

### Rủi ro lớn nhất trong production

1. **Hidden coupling** qua Singleton static access
2. **Resource leaks** trong Object Pool (borrow mà không return)
3. **Telescoping complexity** khi pattern lồng nhau (Builder cho Factory tạo Singleton)

### Tư duy áp dụng đúng

> "Patterns are solutions to problems, not recipes to apply."

- Hiểu **problem** trước khi áp dụng pattern
- Bắt đầu **đơn giản**, refactor khi complexity yêu cầu
- **Dependency Injection frameworks** (Spring, Guice) đã implement tốt Singleton/Factory → tận dụng
- **Testability** là thước đo tốt cho creational design: code dễ test thường có creational pattern tốt

---

## 10. Tài liệu tham khảo

- GoF Design Patterns (Gamma, Helm, Johnson, Vlissides)
- Effective Java - Joshua Bloch (Item 1-5 về creational)
- Java Concurrency in Practice - Brian Goetz
- HikariCP source code và benchmarks
- Apache Commons Pool 2 documentation
