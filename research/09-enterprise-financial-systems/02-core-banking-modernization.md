# Core Banking Modernization: Từ Mainframe sang Kiến Trúc Hiện Đại

> **Mục tiêu nghiên cứu:** Hiểu sâu bản chất việc hiện đại hóa hệ thống core banking - từ legacy mainframe sang kiến trúc phân tán, event-driven, API-first. Phân tích các pattern migration, chiến lược đảm bảo consistency trong quá trình chuyển đổi, và các rủi ro production.

---

## 1. Bối Cảnh và Thách Thức

### 1.1. Tại Sao Core Banking Modernization Là Bài Toán Khó Nhất?

Core banking systems là **hệ thống mission-critical** xử lý các giao dịch tài chính cốt lõi:
- Quản lý tài khoản và số dư (account management)
- Xử lý giao dịch thanh toán (payment processing)
- Tính toán lãi suất và phí (interest & fee calculation)
- Báo cáo tuân thủ (regulatory reporting)
- Quản lý rủi ro (risk management)

**Đặc thù độc nhất của bài toán:**

| Khía cạnh | Thách thức | Hệ quả |
|-----------|------------|--------|
| **Zero Downtime** | Hệ thống phải hoạt động 24/7/365 | Không thể shutdown để migration |
| **Data Consistency** | Số dư tài khoản phải chính xác tuyệt đối | Mất consistency = mất tiền thật |
| **Complexity** | 30-40 năm business logic tích lũy | Legacy code không có documentation |
| **Integration** | Hàng trăm downstream systems | Change ripple effect khổng lồ |
| **Compliance** | Tuân thủ pháp lý nghiêm ngặt | Audit trail phải hoàn chỉnh |

> **Quy tắc vàng:** Trong core banking, **correctness > availability > performance**. Một giao dịch chậm có thể chấp nhận được, nhưng giao dịch sai là không thể chấp nhận.

---

## 2. Bản Chất Legacy Mainframe Architecture

### 2.1. Kiến Trúc Mainframe Truyền Thống

```
┌─────────────────────────────────────────────────────────────┐
│                    MAINFRAME SYSTEM                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │   CICS/IMS   │  │   Batch      │  │   Database       │  │
│  │  (OLTP)      │  │  Processing  │  │   (DB2/VSAM)     │  │
│  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘  │
└─────────┼─────────────────┼───────────────────┼────────────┘
          │                 │                   │
          ▼                 ▼                   ▼
   Online Transactions   Nightly Batch    Hierarchical Data
   (Real-time)          (End-of-Day)     (COBOL COPYBOOKS)
```

**Đặc điểm kỹ thuật:**

| Thành phần | Công nghệ | Hạn chế |
|------------|-----------|---------|
| **Compute** | IBM z/OS, COBOL, PL/I | Vertical scaling only, license cost cao |
| **Transaction Monitor** | CICS (Customer Information Control System) | Tight coupling, proprietary API |
| **Database** | DB2, IMS, VSAM | Hierarchical model, không linh hoạt |
| **Batch Processing** | JCL (Job Control Language) | Nightly batch window, latency cao |
| **Data Format** | EBCDIC, Fixed-width records | Integration difficulty |

### 2.2. Vấn Đề Cốt Lõi: Tight Coupling

Mainframe systems được thiết kế theo **monolithic architecture** với đặc điểm:

1. **Presentation + Business Logic + Data Access** đều nằm trong cùng COBOL programs
2. **Shared Database Pattern:** Hàng trăm applications trực tiếp đọc/ghi cùng database tables
3. **Synchronous Processing:** Giao dịch xử lý tuần tự, blocking
4. **Nightly Batch Dependency:** End-of-day (EOD) batch để cập nhật số dư, tính lãi

> **Hệ quả:** Thay đổi một field trong database có thể ảnh hưởng đến 50+ applications. Không có bounded context rõ ràng.

---

## 3. API-First Core Banking Architecture

### 3.1. Bản Chất "API-First"

**API-First không chỉ là "viết REST API".** Đây là mindset thiết kế:

1. **Contract-First Development:** API specification (OpenAPI) được định nghĩa trước implementation
2. **Domain-Driven Design:** Bounded contexts rõ ràng, mỗi domain có API riêng
3. **Consumer-Centric:** Thiết kế API từ perspective của ngườiconsume
4. **Layered Architecture:** Presentation layer hoàn toàn tách biệt từ business logic

```
┌─────────────────────────────────────────────────────────────────┐
│                    API GATEWAY LAYER                            │
│  (Kong / AWS API Gateway / Azure APIM)                          │
│  - Authentication & Authorization (OAuth 2.0, mTLS)              │
│  - Rate Limiting & Throttling                                    │
│  - Request Routing & Load Balancing                              │
│  - Protocol Translation (REST ↔ gRPC)                            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    CORE BANKING SERVICES                        │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────────┐ │
│  │   Account    │ │  Transaction │ │      Customer            │ │
│  │   Service    │ │   Service    │ │      Service             │ │
│  │              │ │              │ │                          │ │
│  │ - Open/Close │ │ - Transfer   │ │ - KYC/AML                │ │
│  │ - Balance    │ │ - Deposit    │ │ - Profile                │ │
│  │ - Statement  │ │ - Withdrawal │ │ - Segmentation           │ │
│  └──────┬───────┘ └──────┬───────┘ └──────────┬───────────────┘ │
│         │                │                    │                 │
│         └────────────────┼────────────────────┘                 │
│                          │                                      │
│                   ┌──────▼──────┐                              │
│                   │ Event Bus   │ (Domain Events)              │
│                   │ (Kafka)     │                              │
│                   └─────────────┘                              │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    DATA LAYER                                   │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────────┐ │
│  │  Event Store │ │  Read Model  │ │   External Systems       │ │
│  │  (Primary)   │ │  (Projected) │ │   (Legacy Bridge)        │ │
│  └──────────────┘ └──────────────┘ └──────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2. Domain Decomposition Strategy

**Cách chia bounded contexts cho core banking:**

| Bounded Context | Trách nhiệm | Key APIs |
|-----------------|-------------|----------|
| **Account Management** | Quản lý vòng đời tài khoản | `POST /accounts`, `GET /accounts/{id}/balance` |
| **Transaction Processing** | Thực thi giao dịch, đảm bảo consistency | `POST /transactions/transfer`, `POST /transactions/deposit` |
| **Customer Management** | Thông tin KH, phân loại, KYC | `GET /customers/{id}`, `POST /customers/{id}/kyc` |
| **Product Catalog** | Định nghĩa sản phẩm (savings, loans, etc.) | `GET /products`, `POST /products/{id}/rates` |
| **Pricing & Interest** | Tính lãi, phí, điều kiện | `POST /calculate/interest`, `GET /accounts/{id}/fees` |
| **Limit Management** | Hạn mức giao dịch, overdraft | `GET /limits/{accountId}`, `PUT /limits/{accountId}` |
| **Notification** | Gửi SMS, email, push | `POST /notifications` |

> **Nguyên tắc:** Mỗi bounded context có database riêng (Database-per-Service). Không có shared database.

### 3.3. Event Sourcing trong Account Management

**Tại sao Event Sourcing phù hợp với Core Banking?**

```
Traditional CRUD:                    Event Sourcing:
┌─────────────┐                     ┌─────────────────┐
│ Account     │                     │ Account Events  │
│ Table       │                     │ Stream          │
├─────────────┤                     ├─────────────────┤
│ id: 123     │                     │ AccountOpened   │ ───┐
│ balance:    │                     │ DepositMade     │    │
│   $1,000    │  ❌ Mất history     │ WithdrawalMade  │    │ Projection
│ last_tx:    │                     │ DepositMade     │    │
│   tx_456    │                     │ InterestApplied │    │
└─────────────┘                     └─────────────────┘ ◄──┘
                                                          │
                                                          ▼
                                                    ┌─────────────┐
                                                    │ Current     │
                                                    │ Balance     │
                                                    │ = $1,050    │
                                                    └─────────────┘
```

**Bản chất cơ chế:**

1. **Source of Truth là Event Store:** Lưu trữ mọi thay đổi state dưới dạng immutable events
2. **Current State là Projection:** Tính toán từ event stream (có thể rebuild bất cứ lúc nào)
3. **Audit Trail hoàn chỉnh:** Mọi thay đổi đều được ghi lại với timestamp, metadata
4. **Temporal Query:** Có thể query state của tài khoản tại bất kỳ thời điểm nào trong quá khứ

```java
// Event Definitions (Immutable)
public sealed interface AccountEvent {
    String accountId();
    Instant timestamp();
    long version();
}

public record AccountOpened(
    String accountId,
    String customerId,
    String accountType,
    Currency currency,
    Instant openedAt,
    Instant timestamp,
    long version
) implements AccountEvent {}

public record DepositMade(
    String accountId,
    BigDecimal amount,
    String transactionId,
    String description,
    Instant timestamp,
    long version
) implements AccountEvent {}

public record WithdrawalMade(
    String accountId,
    BigDecimal amount,
    String transactionId,
    String description,
    Instant timestamp,
    long version
) implements AccountEvent {}

// Aggregate Root
public class Account {
    private String accountId;
    private String customerId;
    private BigDecimal balance;
    private AccountStatus status;
    private List<AccountEvent> uncommittedEvents = new ArrayList<>();
    private long version;
    
    // State reconstruction from events
    public void apply(List<AccountEvent> events) {
        for (AccountEvent event : events) {
            apply(event);
            this.version = event.version();
        }
    }
    
    private void apply(AccountEvent event) {
        switch (event) {
            case AccountOpened e -> {
                this.accountId = e.accountId();
                this.customerId = e.customerId();
                this.balance = BigDecimal.ZERO;
                this.status = AccountStatus.ACTIVE;
            }
            case DepositMade e -> 
                this.balance = this.balance.add(e.amount());
            case WithdrawalMade e -> {
                if (balance.compareTo(e.amount()) < 0) {
                    throw new InsufficientFundsException();
                }
                this.balance = this.balance.subtract(e.amount());
            }
            // ... other events
        }
    }
    
    // Command handlers
    public void deposit(BigDecimal amount, String txId, String desc) {
        var event = new DepositMade(
            accountId, amount, txId, desc, 
            Instant.now(), version + 1
        );
        apply(event);
        uncommittedEvents.add(event);
    }
    
    public void withdraw(BigDecimal amount, String txId, String desc) {
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException();
        }
        var event = new WithdrawalMade(
            accountId, amount, txId, desc,
            Instant.now(), version + 1
        );
        apply(event);
        uncommittedEvents.add(event);
    }
}
```

### 3.4. Event Store Implementation

**Lựa chọn Event Store:**

| Giải pháp | Ưu điểm | Nhược điểm | Use Case |
|-----------|---------|------------|----------|
| **Apache Kafka** | High throughput, durability, ecosystem | Không phải purpose-built event store | High-volume event streaming |
| **EventStoreDB** | Purpose-built, projection support, subscriptions | Vendor-specific, smaller community | Domain-focused event sourcing |
| **PostgreSQL** (with jsonb) | Familiar, ACID, flexible schema | Performance với high volume | Small-medium scale |
| **Axon Server** | Integrated framework, saga support | Vendor lock-in | Axon Framework users |
| **ScyllaDB/Cassandra** | Write-optimized, time-series | Eventual consistency, complexity | Massive scale (millions events/sec) |

**Kafka as Event Store - Key Considerations:**

```yaml
# Kafka Configuration cho Event Sourcing
topics:
  account-events:
    partitions: 12  # Parallel processing by account_id hash
    replication: 3  # Durability
    retention: forever  # Không xóa events!
    compaction: true    # Chỉ giữ lại latest event per key (cẩn thận!)
    
  # Lưu ý: Event sourcing thường KHÔNG dùng compaction 
  # vì cần giữ full history. Dùng retention: unlimited.
```

> **Quan trọng:** Trong banking, events **KHÔNG BAO GIỜ** được xóa. Retention policy phải là "infinite" hoặc archival sang cold storage (S3 Glacier, etc.).

---

## 4. Migration Pattern: Strangler Fig

### 4.1. Bản Chất Strangler Fig Pattern

**Tên gọi từ loài cây Strangler Fig trong tự nhiên:**
- Hạt giống nảy mầm trên cây chủ
- Rễ dần bao quanh, hút chất dinh dưỡng từ cây chủ
- Cuối cùng thay thế hoàn toàn cây chủ

**Áp dụng vào software migration:**

```
Phase 1: Anti-Corruption Layer                       Phase 2: Parallel Run
┌────────────────────────────────┐                  ┌────────────────────────────────┐
│         Clients                │                  │         Clients                │
└─────────────┬──────────────────┘                  └─────────────┬──────────────────┘
              │                                                   │
              ▼                                                   ▼
┌────────────────────────────────┐                  ┌────────────────────────────────┐
│    Anti-Corruption Layer       │                  │    API Gateway / Router        │
│    (Translate & Route)         │                  │    (Feature Flag Controlled)   │
└─────────────┬──────────────────┘                  └──────┬─────────────┬─────────────┘
              │                                            │             │
              ▼                                            ▼             ▼
┌────────────────────────────────┐                  ┌────────────┐  ┌──────────────┐
│      Legacy Mainframe          │                  │   Legacy   │  │   New        │
│      (Still primary)           │                  │   System   │  │   Service    │
└────────────────────────────────┘                  └────────────┘  └──────────────┘
                                                         │                │
                                                         └──────┬─────────┘
                                                                ▼
                                                         ┌──────────────┐
                                                         │  Comparator  │
                                                         │  (Verify)    │
                                                         └──────────────┘

Phase 3: Gradual Cutover                           Phase 4: Legacy Retirement
┌────────────────────────────────┐                ┌────────────────────────────────┐
│         Clients                │                │         Clients                │
└─────────────┬──────────────────┘                └─────────────┬──────────────────┘
              │                                                 │
              ▼                                                 ▼
┌────────────────────────────────┐                ┌────────────────────────────────┐
│    API Gateway                 │                │    API Gateway                 │
│    (Traffic % based routing)   │                │    (100% New)                  │
└─────────────┬──────────────────┘                └─────────────┬──────────────────┘
              │                                                 │
      ┌───────┴───────┐                                         ▼
      ▼               ▼                           ┌────────────────────────────────┐
┌──────────┐   ┌──────────────┐                   │      New Core Banking          │
│  Legacy  │   │     New      │                   │      (Modern Architecture)     │
│  (10%)   │   │   (90%)      │                   └────────────────────────────────┘
└──────────┘   └──────────────┘
```

### 4.2. Anti-Corruption Layer (ACL) Implementation

**Vai trò của ACL:**

1. **Translation:** Chuyển đổi giữa legacy data format (COBOL COPYBOOK) và modern format (JSON/Protobuf)
2. **Protocol Bridge:** Kết nối modern REST/gRPC APIs với legacy protocols (CICS Transaction Gateway, MQ Series)
3. **Data Mapping:** Field mapping, value transformation, code set conversion
4. **Isolation:** Ngăn domain model của legacy "leak" vào new system

```java
// Anti-Corruption Layer - Legacy Adapter
@Component
public class LegacyAccountAdapter {
    
    private final CicsGateway cicsGateway;
    private final CopybookMapper copybookMapper;
    
    // Translate modern request to legacy format
    public LegacyAccountResponse getAccount(String accountId) {
        // 1. Build COBOL COMMAREA (fixed-width format)
        byte[] commarea = copybookMapper.toCommarea(
            Map.of("ACCT_ID", padRight(accountId, 12)),
            "ACCTINQ"
        );
        
        // 2. Call CICS via CTG (CICS Transaction Gateway)
        byte[] response = cicsGateway.execute(
            "ACCT",           // CICS Program name
            "ACCTINQ",        // Transaction ID
            commarea
        );
        
        // 3. Parse COBOL response to domain object
        return copybookMapper.fromCommarea(response, LegacyAccountResponse.class);
    }
    
    // Translation: Legacy COBOL comp-3 (packed decimal) to BigDecimal
    private BigDecimal parsePackedDecimal(byte[] data, int offset, int length) {
        // COBOL packed decimal: mỗi byte chứa 2 digits, byte cuối chứa sign
        // Implementation chi tiết...
    }
    
    private String padRight(String s, int length) {
        return String.format("%-" + length + "s", s);
    }
}

// Modern API sử dụng ACL
@RestController
public class AccountController {
    
    private final LegacyAccountAdapter legacyAdapter;
    private final NewAccountService newService;
    private final FeatureFlagService featureFlags;
    
    @GetMapping("/accounts/{id}")
    public AccountDTO getAccount(@PathVariable String id) {
        // Feature flag quyết định route
        if (featureFlags.isEnabled("use-new-account-service", id)) {
            return newService.getAccount(id);
        } else {
            // Thông qua ACL
            var legacyResponse = legacyAdapter.getAccount(id);
            return mapToDTO(legacyResponse);
        }
    }
}
```

### 4.3. Dual-Write Consistency Pattern

**Thách thức:** Trong quá trình migration, cần ghi dữ liệu vào cả legacy và new system để đảm bảo consistency.

**Vấn đề:** Làm sao đảm bảo atomicity khi write vào 2 hệ thống khác nhau?

```
┌─────────────────────────────────────────────────────────────────┐
│                     DUAL-WRITE PATTERNS                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Pattern 1: Synchronous Dual-Write                               │
│  ┌─────────┐    ┌──────────┐    ┌─────────┐    ┌─────────┐      │
│  │ Client  │───▶│ Service  │───▶│ Legacy  │───▶│   New   │      │
│  │         │◀───│          │◀───│ (Sync)  │◀───│ (Sync)  │      │
│  └─────────┘    └──────────┘    └─────────┘    └─────────┘      │
│                                                                  │
│  ✅ Đơn giản, strong consistency                                  │
│  ❌ Latency cao, availability = availability(legacy) ∩ availability(new)│
│                                                                  │
│  Pattern 2: Async via Outbox (Recommended)                       │
│  ┌─────────┐    ┌──────────┐    ┌────────┐                       │
│  │ Client  │───▶│ Service  │───▶│ Legacy │ (Primary)             │
│  │         │◀───│          │◀───│        │                       │
│  └─────────┘    └────┬─────┘    └────────┘                       │
│                      │                                           │
│                      ▼                                           │
│                 ┌────────┐    ┌─────────┐    ┌─────────┐         │
│                 │ Outbox │───▶│  Kafka  │───▶│   New   │         │
│                 │ Table  │    │ (Event) │    │ Service │         │
│                 └────────┘    └─────────┘    └─────────┘         │
│                                                                  │
│  ✅ Decoupled, better performance                                 │
│  ❌ Eventual consistency, cần xử lý failures                      │
│                                                                  │
│  Pattern 3: Change Data Capture (CDC)                            │
│  ┌─────────┐    ┌──────────┐    ┌────────┐    ┌─────────┐        │
│  │ Client  │───▶│ Service  │───▶│ Legacy │───▶│ CDC     │        │
│  │         │◀───│          │◀───│ DB     │    │ (Debezium)       │
│  └─────────┘    └──────────┘    └────────┘    └────┬────┘        │
│                                                    │              │
│                                                    ▼              │
│                                               ┌─────────┐         │
│                                               │   New   │         │
│                                               │ Service │         │
│                                               └─────────┘         │
│                                                                  │
│  ✅ No code change in legacy, captures all changes                │
│  ❌ Lag time, schema changes require coordination                 │
└─────────────────────────────────────────────────────────────────┘
```

**Outbox Pattern Implementation:**

```java
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id
    private UUID id;
    private String aggregateType;    // "Account"
    private String aggregateId;      // accountId
    private String eventType;        // "AccountOpened"
    private String payload;          // JSON
    private Instant createdAt;
    private OutboxStatus status;     // PENDING, PROCESSED, FAILED
    private int retryCount;
}

@Service
@Transactional
public class AccountService {
    
    private final AccountRepository accountRepo;
    private final OutboxRepository outboxRepo;
    private final LegacyAccountClient legacyClient;
    
    public void openAccount(OpenAccountCommand cmd) {
        // 1. Save to new system (event sourcing)
        var account = Account.open(cmd);
        accountRepo.save(account);
        
        // 2. Sync call to legacy (primary system during migration)
        legacyClient.createAccount(cmd);
        
        // 3. Write to outbox for downstream systems
        var event = new OutboxEvent(
            UUID.randomUUID(),
            "Account",
            account.getId(),
            "AccountOpened",
            toJson(account.getUncommittedEvents()),
            Instant.now(),
            OutboxStatus.PENDING
        );
        outboxRepo.save(event);
        
        // Transaction commit: cả account và outbox được commit atomically
    }
}

// Outbox Relay (Background process)
@Component
public class OutboxRelay {
    
    private final OutboxRepository outboxRepo;
    private final KafkaTemplate<String, String> kafka;
    
    @Scheduled(fixedDelay = 1000) // Mỗi giây
    public void relay() {
        var pending = outboxRepo.findByStatusAndCreatedAtBefore(
            OutboxStatus.PENDING, 
            Instant.now().minusSeconds(30)
        );
        
        for (OutboxEvent event : pending) {
            try {
                kafka.send(
                    "account-events",
                    event.getAggregateId(),
                    event.getPayload()
                ).get(); // Wait for acknowledgment
                
                event.markProcessed();
                outboxRepo.save(event);
                
            } catch (Exception e) {
                event.incrementRetry();
                if (event.getRetryCount() > 3) {
                    event.markFailed();
                    // Alert to monitoring
                }
                outboxRepo.save(event);
            }
        }
    }
}
```

### 4.4. Change Data Capture (CDC) với Debezium

**Khi nào dùng CDC:**
- Legacy system không thể modify để thêm outbox
- Cần capture tất cả changes (kể cả từ batch jobs)
- Real-time sync requirement

```yaml
# Debezium Connector Configuration
{
  "name": "legacy-db-connector",
  "config": {
    "connector.class": "io.debezium.connector.db2.Db2Connector",
    "database.hostname": "legacy-mainframe",
    "database.port": "50000",
    "database.user": "db2inst1",
    "database.password": "${secrets.db2.password}",
    "database.dbname": "BANKDB",
    "table.include.list": "BANK.ACCOUNTS,BANK.TRANSACTIONS",
    
    "tombstones.on.delete": false,
    "decimal.handling.mode": "string",  // Để tránh precision loss
    
    // Capture cả before và after image
    "capture.mode": "CHANGE",
    
    // Kafka topic naming
    "topic.prefix": "legacy.cdc",
    
    // Schema evolution
    "schema.history.internal.kafka.bootstrap.servers": "kafka:9092",
    "schema.history.internal.kafka.topic": "schema-changes.legacy"
  }
}
```

---

## 5. Data Consistency và Transaction Management

### 5.1. Saga Pattern trong Distributed Transactions

**Bản chất:** Saga là chuỗi các local transactions, mỗi transaction cập nhật database và publish event/message để trigger transaction tiếp theo.

```
┌─────────────────────────────────────────────────────────────────┐
│                    SAGA PATTERN FLOW                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Transfer Saga: Debit Account A → Credit Account B              │
│                                                                  │
│  ┌─────────────┐     ┌─────────────┐     ┌─────────────┐        │
│  │  Saga       │────▶│  Step 1     │────▶│  Step 2     │        │
│  │  Orchestrator      │  Debit A    │      │  Credit B   │        │
│  └─────────────┘     └─────────────┘     └─────────────┘        │
│         │                   │                   │               │
│         │                   ▼                   ▼               │
│         │            ┌─────────────┐     ┌─────────────┐        │
│         │            │  Success    │     │  Success    │        │
│         │            │  Event      │     │  Event      │        │
│         │            └─────────────┘     └─────────────┘        │
│         │                                                        │
│         ▼  (If Step 2 fails)                                    │
│  ┌─────────────┐     ┌─────────────┐                            │
│  │  Compensation      │  Credit A   │  (Rollback)                │
│  │  (Reverse debit)   │  (Refund)   │                            │
│  └─────────────┘     └─────────────┘                            │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Orchestration vs Choreography:**

| Pattern | Ưu điểm | Nhược điểm | Use Case |
|---------|---------|------------|----------|
| **Orchestration** | Central control, dễ debug, dễ monitor | Single point of logic complexity | Complex sagas với nhiều steps |
| **Choreography** | Decoupled, services autonomous | Hard to trace, distributed logic | Simple, linear flows |

**Implementation với Temporal (Cadence):**

```java
// Saga Definition
@WorkflowInterface
public interface TransferWorkflow {
    @WorkflowMethod
    void transfer(TransferRequest request);
    
    @QueryMethod
    TransferStatus getStatus();
}

public class TransferWorkflowImpl implements TransferWorkflow {
    
    private final AccountActivities activities;
    private final Saga saga;
    
    @Override
    public void transfer(TransferRequest request) {
        Saga.Options sagaOptions = new Saga.Options.Builder()
            .setParallelCompensation(false)
            .build();
        this.saga = new Saga(sagaOptions);
        
        try {
            // Step 1: Debit source account
            DebitResult debit = activities.debitAccount(
                request.getFromAccount(),
                request.getAmount()
            );
            saga.addCompensation(
                () -> activities.creditAccount(
                    request.getFromAccount(), 
                    request.getAmount()
                )
            );
            
            // Step 2: Credit destination account
            activities.creditAccount(
                request.getToAccount(),
                request.getAmount()
            );
            saga.addCompensation(
                () -> activities.debitAccount(
                    request.getToAccount(),
                    request.getAmount()
                )
            );
            
            // Step 3: Record transaction
            activities.recordTransaction(request, debit.getTransactionId());
            
        } catch (Exception e) {
            saga.compensate();  // Auto-compensate on failure
            throw e;
        }
    }
}
```

### 5.2. Idempotency Design

**Tại sao idempotency quan trọng trong banking:**

1. **Network timeouts:** Client không biết request đã được xử lý hay chưa
2. **Retry logic:** Hệ thống tự động retry failed requests
3. **Duplicate submissions:** User click nút 2 lần

```java
// Idempotency Key Pattern
@RestController
public class TransactionController {
    
    private final IdempotencyKeyRepository idempotencyRepo;
    private final TransactionService transactionService;
    
    @PostMapping("/transactions")
    public ResponseEntity<Transaction> createTransaction(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody TransactionRequest request) {
        
        // 1. Check if already processed
        Optional<IdempotencyRecord> existing = 
            idempotencyRepo.findByKey(idempotencyKey);
        
        if (existing.isPresent()) {
            // Return cached response
            return ResponseEntity.ok(existing.get().getResponse());
        }
        
        // 2. Process transaction (within transaction)
        Transaction tx = transactionService.execute(request);
        
        // 3. Store idempotency record
        idempotencyRepo.save(new IdempotencyRecord(
            idempotencyKey,
            tx,
            Instant.now().plus(24, ChronoUnit.HOURS) // TTL
        ));
        
        return ResponseEntity.created(...).body(tx);
    }
}

// Idempotency Key Generation Strategy
public class IdempotencyKeyGenerator {
    
    // Option 1: Client-generated UUID
    // Client: UUID.randomUUID().toString()
    
    // Option 2: Deterministic hash (idempotent by request content)
    public static String generate(Object request, String clientId) {
        String content = clientId + ":" + serialize(request);
        return Hashing.sha256().hashString(content, UTF_8).toString();
    }
    
    // Option 3: Natural key from business context
    // "TRANSFER:{fromAccount}:{toAccount}:{amount}:{date}:{sequence}"
}
```

---

## 6. Production Concerns và Operational Excellence

### 6.1. Monitoring và Observability

**Key Metrics cho Core Banking:**

| Category | Metric | Threshold | Alert |
|----------|--------|-----------|-------|
| **Consistency** | Reconciliation diff count | = 0 | P0 - Immediate |
| **Performance** | p99 transaction latency | < 200ms | P1 - 5 min |
| **Throughput** | TPS (transactions/sec) | > target | P2 - 15 min |
| **Availability** | Error rate | < 0.01% | P0 - Immediate |
| **Data Quality** | Event processing lag | < 5 seconds | P1 - 5 min |

**Reconciliation - Kiểm tra consistency:**

```java
// Nightly reconciliation job
@Component
public class ReconciliationJob {
    
    @Scheduled(cron = "0 0 2 * * ?") // 2 AM daily
    public void reconcile() {
        // 1. Sum all debits and credits in new system
        var newSystemBalances = newSystemRepository
            .calculateBalances();
        
        // 2. Get balances from legacy (source of truth during migration)
        var legacyBalances = legacyClient
            .getAllBalances();
        
        // 3. Compare
        List<ReconciliationDiff> diffs = compareBalances(
            newSystemBalances, 
            legacyBalances
        );
        
        if (!diffs.isEmpty()) {
            // P0 Alert
            alertService.sendP0Alert(
                "RECONCILIATION_MISMATCH",
                "Found " + diffs.size() + " discrepancies",
                diffs
            );
            
            // Auto-rollback if configured
            if (config.isAutoRollbackEnabled()) {
                rollbackService.initiateRollback(diffs);
            }
        }
    }
}
```

### 6.2. Testing Strategy cho Migration

**Shadow Testing / Parallel Run:**

```
┌─────────────────────────────────────────────────────────────────┐
│                    SHADOW TESTING                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Production Traffic                                              │
│         │                                                        │
│         ▼                                                        │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐     │
│  │   Router     │────▶│   Legacy     │────▶│   Legacy     │     │
│  │   (Async     │     │   System     │     │   Response   │     │
│  │    Mirror)   │     │   (Primary)  │     │   (Used)     │     │
│  └──────┬───────┘     └──────────────┘     └──────────────┘     │
│         │                                                        │
│         │ Async (non-blocking)                                   │
│         ▼                                                        │
│  ┌──────────────┐     ┌──────────────┐                           │
│  │    New       │────▶│  Comparator  │                           │
│  │   System     │     │  (Log diff)  │                           │
│  │  (Shadow)    │     │              │                           │
│  └──────────────┘     └──────────────┘                           │
│                                                                  │
│  Metrics to track:                                               │
│  - Response time comparison (p50, p95, p99)                      │
│  - Response payload diff rate                                    │
│  - Error rate comparison                                         │
│  - Data consistency verification                                 │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Contract Testing:**

```java
// Pact - Consumer-Driven Contract Testing
@Pact(consumer = "mobile-app", provider = "account-service")
public RequestResponsePact accountBalancePact(PactDslWithProvider builder) {
    return builder
        .given("account exists")
        .uponReceiving("get account balance")
        .path("/accounts/12345/balance")
        .method("GET")
        .willRespondWith()
        .status(200)
        .body(new PactDslJsonBody()
            .decimalType("availableBalance")
            .decimalType("currentBalance")
            .stringType("currency", "USD")
        )
        .toPact();
}
```

### 6.3. Rollback Strategy

**Rollback là không thể tránh khỏi trong migration phức tạp:**

| Rollback Type | Khi nào dùng | Implementation |
|---------------|--------------|----------------|
| **Feature Flag** | Phát hiện lỗi sớm | Toggle routing 100% về legacy |
| **Data Fix** | Data inconsistency | Compensation transactions |
| **Full Rollback** | System failure | Restore từ backup, replay events |

```java
// Feature Flag-controlled Routing
@Component
public class MigrationRouteController {
    
    private final FeatureFlagClient featureFlags;
    
    public <T> T executeWithFallback(
            String featureName,
            String entityId,
            Supplier<T> newImplementation,
            Supplier<T> legacyImplementation) {
        
        try {
            if (featureFlags.isEnabled(featureName, entityId)) {
                return newImplementation.get();
            }
        } catch (Exception e) {
            // Log và alert
            monitoring.recordMigrationFailure(featureName, e);
            
            // Auto-disable feature flag nếu error rate cao
            if (isErrorRateHigh(featureName)) {
                featureFlags.emergencyDisable(featureName);
            }
            
            // Fallback to legacy
            return legacyImplementation.get();
        }
        
        return legacyImplementation.get();
    }
}
```

---

## 7. Trade-offs và Quyết Định Kiến Trúc

### 7.1. Event Sourcing vs CRUD

| Criteria | Event Sourcing | CRUD | Khuyến nghị |
|----------|----------------|------|-------------|
| **Audit Requirement** | ✅ Native support | ❌ Cần thêm audit table | ES cho financial audit |
| **Complexity** | ❌ High | ✅ Low | ES chỉ khi cần audit/time-travel |
| **Learning Curve** | ❌ Steep | ✅ Gentle | Team phải trained |
| **Query Performance** | ❌ Cần projections | ✅ Direct query | ES + CQRS pattern |
| **Storage Cost** | ❌ Higher (all events) | ✅ Lower | Event compression, cold storage |
| **Debugging** | ✅ Full history | ❌ Current state only | ES rất valuable cho debug |

### 7.2. Strangler Fig vs Big Bang

| Criteria | Strangler Fig | Big Bang | Khuyến nghị |
|----------|---------------|----------|-------------|
| **Risk** | ✅ Low, incremental | ❌ High, all-or-nothing | Strangler Fig cho banking |
| **Duration** | ❌ Longer (months/years) | ✅ Faster (if successful) | Strangler Fig cho zero downtime |
| **Cost** | ❌ Higher (run dual systems) | ✅ Lower (one-time) | Strangler Fig đáng giá cho risk reduction |
| **Rollback** | ✅ Easy per component | ❌ Difficult/impossible | Strangler Fig |
| **Complexity** | ❌ Higher (integration) | ✅ Simpler | Strangler Fig cần experienced team |

> **Quyết định:** Trong core banking, **Strangler Fig là bắt buộc**. Big bang migration có risk không thể chấp nhận được với hệ thống xử lý tiền thật.

### 7.3. Dual-Write vs CDC

| Criteria | Dual-Write (Outbox) | CDC (Debezium) | Khuyến nghị |
|----------|---------------------|----------------|-------------|
| **Consistency** | ✅ Strong (same TX) | ❌ Eventual | Dual-Write cho critical data |
| **Code Change** | ❌ Cần modify legacy | ✅ No code change | CDC nếu không thể modify legacy |
| **Latency** | ✅ Low | ❌ Higher (polling/mining) | Dual-Write cho real-time |
| **Schema Change** | ✅ Controlled | ❌ Cần coordination | Dual-Write cho controlled evolution |
| **Complexity** | ❌ Higher (outbox relay) | ✅ Lower (config-based) | CDC cho simple use cases |

---

## 8. Anti-Patterns và Pitfalls

### 8.1. Common Mistakes

1. **"Lift and Shift" Mentality**
   - ❌ Chỉ rewrite COBOL thành Java mà không redesign
   - ✅ Tận dụng cơ hội để áp dụng DDD, bounded contexts

2. **Ignoring Data Migration Complexity**
   - ❤ Đánh giá thấp effort để cleanse, transform 30 năm data
   - ✅ Data migration là 50-70% effort, plan accordingly

3. **Over-Engineering Event Sourcing**
   - ❌ Áp dụng ES cho mọi entity (customers, addresses, configs)
   - ✅ ES chỉ cho entities có audit requirement và complex lifecycle

4. **Neglecting Reconciliation**
   - ❌ Không có cơ chế verify consistency giữa old và new
   - ✅ Daily reconciliation là mandatory, auto-alert on mismatch

5. **Big Bang Cutover**
   - ❌ Chuyển 100% traffic trong một đêm
   - ✅ Gradual cutover: 1% → 5% → 10% → 50% → 100%

6. **Inadequate Testing in Production**
   - ❌ Chỉ test trong staging (không đủ realistic)
   - ✅ Shadow testing với production traffic là bắt buộc

### 8.2. Technical Debt Traps

```
⚠️ WARNING: Những quyết định hôm nay sẽ đeo bám 10-20 năm

1. Shared Database giữa old và new system
   → Coupling không bao giờ được giải quyết
   
2. Synchronous calls qua lại giữa services
   → Cascading failures, tight coupling
   
3. Không có version strategy cho events
   → Schema evolution nightmare
   
4. Thiếu correlation IDs cho distributed tracing
   → Không thể debug production issues
   
5. Không plan cho disaster recovery
   → Data loss khi incident xảy ra
```

---

## 9. Kết Luận

### Bản Chất Cốt Lõi

Core Banking Modernization không phải là kỹ thuật problem - đây là **organizational và risk management problem**. Các nguyên tắc bất biến:

1. **Correctness Over Speed:** Trong banking, một giao dịch sai tệ hơn giao dịch chậm
2. **Incremental Over Big Bang:** Strangler Fig là pattern duy nhất chấp nhận được
3. **Verification Over Trust:** Daily reconciliation và shadow testing là bắt buộc
4. **Rollback Over Recovery:** Luôn có đường lui (feature flags) cho mọi thay đổi

### Trade-off Quan Trọng Nhất

**Consistency vs Availability trong quá trình migration:**

- Chọn **strong consistency** (dual-write synchronous) cho critical paths
- Chấp nhận **eventual consistency** (CDC/outbox) cho non-critical read models
- Không bao giờ compromise trên data integrity của số dư tài khoản

### Rủi Ro Lớn Nhất

**Data Corruption không phát hiện:** Migration có thể appear thành công trong tuần đầu, nhưng subtle data inconsistencies accumulate và chỉ phát hiện khi đã quá muộn.

→ **Giải pháp:** 
- Daily automated reconciliation
- Shadow testing trong production ít nhất 3-6 tháng
- Gradual cutover với monitoring từng % traffic
- Emergency rollback procedures tested regularly

### Khuyến Nghị Thực Chiến

| Giai đoạn | Timeline | Focus |
|-----------|----------|-------|
| **Phase 1** | 6-12 tháng | Anti-corruption layer, API gateway, read-only APIs |
| **Phase 2** | 12-24 tháng | Event sourcing cho new accounts, parallel run |
| **Phase 3** | 24-36 tháng | Gradual migration existing accounts, 1% → 100% |
| **Phase 4** | 36-48 tháng | Legacy retirement, optimization, feature expansion |

> **Final Thought:** Core banking modernization là marathon, không phải sprint. Thành công được đo bằng **zero data loss** và **zero downtime**, không phải bằng tốc độ delivery.
