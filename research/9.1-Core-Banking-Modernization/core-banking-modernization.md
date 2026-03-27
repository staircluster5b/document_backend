# Core Banking Modernization: Từ Mainframe Legacy đến API-First Architecture

## 1. Mục tiêu của Task

Nghiên cứu chiến lược chuyển đổi hệ thống Core Banking từ kiến trúc mainframe monolithic sang kiến trúc hiện đại, tập trung vào:
- **Legacy Mainframe Integration:** Kết nối và tương tác với hệ thống mainframe hiện có mà không làm gián đoạn hoạt động
- **API-First Core Banking:** Thiết kế kiến trúc ngân hàng lõi với API làm trung tâm
- **Event-Sourced Account Management:** Quản lý tài khoản dựa trên event sourcing
- **Strangler Fig Pattern:** Chiến lược migration từng phần an toàn
- **Dual-Write Consistency:** Đảm bảo tính nhất quán dữ liệu trong quá trình chuyển đổi

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 Legacy Mainframe Integration - Bài Toán Tồn Tại Song Song

#### Bản Chất Vấn Đề

Mainframe không phải là "legacy" theo nghĩa lỗi thờimà là **hệ thống đã được tối ưu trong 30-50 năm** với đặc tính:
- **Reliability 99.999%+** (5 nines availability)
- **Throughput cực cao** cho batch processing
- **ACID compliance nghiêm ngặt** cho financial transactions

> **Quan trọng:** Mainframe không phải để "thay thế" mà là "tích hợp với" trong 5-10 năm tới.

#### Kiến Trúc Tích Hợp Mainframe

```
┌─────────────────────────────────────────────────────────────┐
│                    API Gateway / ESB                        │
│         (Rate limiting, Authentication, Routing)            │
└──────────────┬──────────────────────────────┬───────────────┘
               │                              │
    ┌──────────▼──────────┐        ┌──────────▼──────────┐
    │   Modern Services   │        │   Mainframe         │
    │   (Microservices)   │◄──────►│   Adapter Layer     │
    │                     │        │                     │
    │  • Account Service  │        │  • CICS Transaction │
    │  • Payment Service  │        │  • IMS Database     │
    │  • Customer Service │        │  • COBOL Programs   │
    └─────────────────────┘        └─────────────────────┘
```

**Các Pattern Tích Hợp Chính:**

| Pattern | Use Case | Trade-off | Khi Nào Dùng |
|---------|----------|-----------|--------------|
| **Screen Scraping** | Đọc 3270 terminal stream | Fragile, slow, hard to maintain | Legacy không có API, tạm thờ |
| **JCA (J2EE Connector)** | Java ↔ Mainframe qua CICS/IMS | Tightly coupled, requires mainframe expertise | High-volume sync transactions |
| **MQ Series Bridge** | Async message exchange | Complexity, eventual consistency | Batch processing, non-critical path |
| **API Emulator** | Expose REST/JSON facade | Double maintenance, mapping complexity | Phased migration, API-first strategy |
| **Event Replication** | CDC từ mainframe DB | Latency, ordering guarantees | Read-heavy workloads, analytics |

#### Technical Deep Dive: CICS Transaction Gateway

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Java Client    │────►│  CICS TG         │────►│  CICS Region    │
│  (CICS API)     │     │  (TCP/IP)        │     │  (z/OS)         │
└─────────────────┘     └──────────────────┘     └─────────────────┘
         │                       │                        │
         │         ECI (External│ Call Interface)        │
         │         EPI (External│ Presentation Interface)│
         ▼                       ▼                        ▼
    Pooling                    Protocol                  Transaction
    Management:                Conversion:               ACID
    - EXCI (fast)              - CTG protocol            - Syncpoint
    - TCP/IP (remote)          - ECI request/response    - Resource recovery
```

**Critical Considerations:**
1. **LUW (Logical Unit of Work):** Transaction boundary phải match giữa Java và CICS
2. **Codepage Conversion:** EBCDIC ↔ UTF-8, đặc biệt cho tiếng Việt (VNI, TCVN, Unicode)
3. **Transaction Timeout:** Mainframe thường timeout ngắn hơn (30-60s), cần tuning phù hợp

---

### 2.2 API-First Core Banking Architecture

#### Bản Chất "API-First"

Không phải là "viết API trước" mà là **thiết kế domain model và contract trước khi implementation**.

> **API-First = Domain-Driven Design + Contract-First Development**

#### Layered Architecture cho Core Banking

```
┌─────────────────────────────────────────────────────────────────┐
│  PRESENTATION LAYER                                             │
│  Mobile App │ Web Portal │ Partner APIs │ Branch Terminal       │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  API GATEWAY LAYER                                              │
│  Authentication │ Rate Limiting │ Routing │ Protocol Conversion │
│  (Kong / Apigee / AWS API Gateway / Azure API Management)       │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  BFF (Backend-for-Frontend)                                     │
│  Mobile BFF │ Web BFF │ Partner BFF                              │
│  (Aggregate, Optimize, Transform cho từng client type)          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  CORE BANKING DOMAIN SERVICES                                   │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │
│  │   Account    │ │   Payment    │ │   Customer   │            │
│  │   Service    │ │   Service    │ │   Service    │            │
│  └──────────────┘ └──────────────┘ └──────────────┘            │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │
│  │   Ledger     │ │   Product    │ │   Limit      │            │
│  │   Service    │ │   Service    │ │   Service    │            │
│  └──────────────┘ └──────────────┘ └──────────────┘            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  INFRASTRUCTURE SERVICES                                        │
│  Event Bus │ Workflow Engine │ Document Store │ Cache            │
│  (Kafka / Temporal / MongoDB / Redis)                           │
└─────────────────────────────────────────────────────────────────┘
```

#### Domain Model cho Core Banking

**Account Aggregate (Event-Sourced):**

```
┌─────────────────────────────────────────────────────────────────┐
│  ACCOUNT AGGREGATE                                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │ AccountId   │  │ AccountType │  │ Currency    │              │
│  │ (UUID)      │  │ (Savings,   │  │ (ISO 4217)  │              │
│  │             │  │  Current,   │  │             │              │
│  │             │  │  Loan)      │  │             │              │
│  └─────────────┘  └─────────────┘  └─────────────┘              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │ Balance     │  │ Status      │  │ Version     │              │
│  │ (BigDecimal)│  │ (Active,    │  │ (Optimistic │              │
│  │             │  │  Frozen,    │  │  Locking)   │              │
│  │             │  │  Closed)    │  │             │              │
│  └─────────────┘  └─────────────┘  └─────────────┘              │
│                                                                 │
│  EVENT STREAM:                                                  │
│  1. AccountOpenedEvent                                          │
│  2. AmountDepositedEvent                                        │
│  3. AmountWithdrawnEvent                                        │
│  4. AccountFrozenEvent                                          │
└─────────────────────────────────────────────────────────────────┘
```

**Bounded Contexts trong Banking:**

| Context | Responsibility | Key Aggregates | Integration Pattern |
|---------|---------------|----------------|---------------------|
| **Account Management** | Account lifecycle, balance tracking | Account, AccountHolder | Event-sourced |
| **Payment Processing** | Fund movement, transaction execution | Payment, Settlement | Saga + Outbox |
| **Customer Management** | KYC, customer 360° view | Customer, Contact, Document | CRUD + Events |
| **Product Catalog** | Account types, fees, interest rates | Product, FeeSchedule | Reference data |
| **Limit & Risk** | Transaction limits, fraud rules | LimitProfile, RiskRule | Real-time API |
| **Ledger & Accounting** | Double-entry bookkeeping | JournalEntry, GLAccount | Eventually consistent |

---

### 2.3 Event-Sourced Account Management

#### Bản Chất Event Sourcing

Thay vì lưu **state hiện tại**, lưu **chuỗi events** đã tạo ra state đó.

```
Traditional (State-Based):
┌─────────────────────────────────────┐
│  Account Table                      │
│  ┌──────────┬──────────┬──────────┐ │
│  │ accountId│ balance  │ version  │ │
│  │ ACC-001  │  5000.00 │    5     │ │
│  └──────────┴──────────┴──────────┘ │
└─────────────────────────────────────┘

Event Sourcing:
┌─────────────────────────────────────────────────────────────────┐
│  Event Store (Append-only)                                      │
│  ┌────────┬──────────┬─────────────────┬──────────┬──────────┐  │
│  │seq_num │stream_id │ event_type      │ payload  │ metadata │  │
│  │   1    │ ACC-001  │ AccountOpened   │ {...}    │ {...}    │  │
│  │   2    │ ACC-001  │ AmountDeposited │ {...}    │ {...}    │  │
│  │   3    │ ACC-001  │ AmountDeposited │ {...}    │ {...}    │  │
│  │   4    │ ACC-001  │ AmountWithdrawn │ {...}    │ {...}    │  │
│  │   5    │ ACC-001  │ AmountDeposited │ {...}    │ {...}    │  │
│  └────────┴──────────┴─────────────────┴──────────┴──────────┘  │
│                                                                 │
│  Projection (Derived):                                          │
│  balance = reduce(events, (acc, e) => acc + e.amount, 0)        │
│  = 0 + 1000 + 2000 - 500 + 2500 = 5000.00                       │
└─────────────────────────────────────────────────────────────────┘
```

#### Why Event Sourcing cho Banking?

| Benefit | Explanation | Banking Application |
|---------|-------------|---------------------|
| **Audit Trail** | Every change recorded | Regulatory compliance (SOX, PCI-DSS) |
| **Temporal Queries** | Query state at any point | "What was balance on March 15?" |
| **Replay Capability** | Rebuild projections | Analytics, reporting, debugging |
| **Event-Driven Integration** | Natural async communication | Cross-service consistency |
| **CQRS Support** | Separate read/write models | High-performance queries |

#### Event Sourcing Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  COMMAND SIDE                                                   │
│                                                                 │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐      │
│  │   Command    │───►│   Aggregate  │───►│   Event      │      │
│  │   Handler    │    │   (Domain)   │    │   Store      │      │
│  └──────────────┘    └──────────────┘    └──────┬───────┘      │
│         ▲                    │                   │              │
│         │         Business   │                   │              │
│         │         Rules      │                   │              │
│         │         Validation │                   │              │
│         │                    │                   ▼              │
│  ┌──────┴──────┐            ┌┴┐      ┌───────────────────┐     │
│  │  Command    │            │ │      │  Event Bus        │     │
│  │  (Intent)   │            │ │      │  (Kafka/RabbitMQ) │     │
│  └─────────────┘            └─┘      └─────────┬─────────┘     │
│                                               │                │
└───────────────────────────────────────────────┼────────────────┘
                                                │
                                                ▼
┌───────────────────────────────────────────────┼────────────────┐
│  QUERY SIDE                                   │                │
│                                               │                │
│                               ┌───────────────┘                │
│                               ▼                                │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐     │
│  │   Query      │───►│  Projection  │───►│   Read Model │     │
│  │   Handler    │    │  (Event      │    │  (Optimized) │     │
│  └──────────────┘    │   Handler)   │    │              │     │
│                      └──────────────┘    └──────────────┘     │
│                           │                                    │
│                           ▼                                    │
│                      ┌──────────────┐                          │
│                      │  Event Store │ (same or replica)       │
│                      └──────────────┘                          │
└────────────────────────────────────────────────────────────────┘
```

**Critical Implementation Details:**

1. **Optimistic Concurrency Control:**
   ```sql
   -- Event store với optimistic locking
   INSERT INTO events (stream_id, version, event_type, payload)
   VALUES ('ACC-001', 6, 'AmountWithdrawn', '{...}')
   WHERE version = 5;  -- Only succeeds if current version is 5
   ```

2. **Snapshot Strategy:**
   - Không replay tất cả events từ đầu
   - Snapshot mỗi N events (e.g., 100)
   - Balance = snapshot.balance + sum(events sau snapshot)

3. **Event Schema Evolution:**
   ```json
   {
     "event_type": "AmountDeposited",
     "version": 2,
     "payload": {
       "amount": 1000.00,
       "currency": "USD",
       "source_account": "EXT-123",  // Added in v2
       "_upcast_from_v1": {
         "source_account": null  // Default for old events
       }
     }
   }
   ```

---

### 2.4 Strangler Fig Pattern - Migration Strategy

#### Bản Chất Pattern

Giống như cây strangler fig (cây sung) bao phủ cây chủ, dần dần thay thế hoàn toàn.

```
Phase 1: Establish Proxy Layer
┌─────────────────────────────────────────────────────────────┐
│  Client Requests                                             │
└──────────────┬──────────────────────────────────────────────┘
               ▼
┌─────────────────────────────────────────────────────────────┐
│  API Gateway / Facade (NEW)                                  │
│  - Routes traffic to Legacy                                  │
│  - Logs requests/responses                                   │
│  - Prepares for gradual migration                            │
└──────────────┬──────────────────────────────────────────────┘
               ▼
┌─────────────────────────────────────────────────────────────┐
│  LEGACY SYSTEM (Mainframe/Monolith)                         │
│  - 100% functionality                                        │
│  - 100% traffic                                              │
└─────────────────────────────────────────────────────────────┘

Phase 2: Extract First Service
┌─────────────────────────────────────────────────────────────┐
│  Client Requests                                             │
└──────────────┬──────────────────────────────────────────────┘
               ▼
┌─────────────────────────────────────────────────────────────┐
│  API Gateway / Facade                                        │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  Routing Logic:                                       │  │
│  │  IF /accounts/*        → NEW Account Service          │  │
│  │  ELSE                  → Legacy System                │  │
│  └───────────────────────────────────────────────────────┘  │
└──────┬──────────────────────────────┬───────────────────────┘
       │                              │
       ▼                              ▼
┌──────────────┐              ┌─────────────────┐
│  NEW         │              │  LEGACY SYSTEM  │
│  Account     │              │  (Other funcs)  │
│  Service     │              │                 │
│  (10% traffic)│             │  (90% traffic)  │
└──────────────┘              └─────────────────┘

Phase 3: Progressive Extraction
┌─────────────────────────────────────────────────────────────┐
│  Client Requests                                             │
└──────────────┬──────────────────────────────────────────────┘
               ▼
┌─────────────────────────────────────────────────────────────┐
│  API Gateway / Facade                                        │
│  - /accounts/*    → Account Service    (100%)               │
│  - /payments/*    → Payment Service    (100%)               │
│  - /customers/*   → Customer Service   (50%)                │
│  - /loans/*       → Legacy             (100%)               │
└──────────────┬──────────────┬──────────────┬────────────────┘
               │              │              │
       ┌───────┘      ┌──────┘      ┌──────┘
       ▼              ▼             ▼
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│ Account  │  │ Payment  │  │ Customer │  │ Legacy   │
│ Service  │  │ Service  │  │ Service  │  │ (Loans)  │
└──────────┘  └──────────┘  └──────────┘  └──────────┘

Phase 4: Legacy Decommissioned
┌─────────────────────────────────────────────────────────────┐
│  Client Requests                                             │
└──────────────┬──────────────────────────────────────────────┘
               ▼
┌─────────────────────────────────────────────────────────────┐
│  API Gateway                                                 │
└──────┬──────────────┬──────────────┬──────────────┬─────────┘
       │              │              │              │
       ▼              ▼              ▼              ▼
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│ Account  │  │ Payment  │  │ Customer │  │ Loans    │
│ Service  │  │ Service  │  │ Service  │  │ Service  │
└──────────┘  └──────────┘  └──────────┘  └──────────┘
```

#### Routing Strategies

| Strategy | Implementation | Risk Level | Use Case |
|----------|---------------|------------|----------|
| **Path-based** | URL path prefix | Low | Clear domain boundaries |
| **Header-based** | Custom header (e.g., X-Route-To: new) | Medium | Canary testing |
| **Percentage-based** | Weighted routing | Medium | Gradual rollout |
| **User-segment-based** | Customer type, region | Medium | Pilot programs |
| **Feature-flag-based** | LaunchDarkly/etc | Low | Controlled rollout |

---

### 2.5 Dual-Write Consistency

#### Bản Chất Vấn Đề

Trong quá trình migration, cần ghi dữ liệu vào **cả hai hệ thống** (legacy và new) để đảm bảo tính nhất quán.

```
┌─────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Request   │────►│  Dual-Write     │────►│  Legacy System  │
│   (Write)   │     │  Coordinator    │     │  (Mainframe)    │
└─────────────┘     └────────┬────────┘     └─────────────────┘
                             │
                             │
                             ▼
                    ┌─────────────────┐
                    │  New System     │
                    │  (Microservice) │
                    └─────────────────┘
```

**The Hard Problem:** Đảm bảo cả hai hệ thống đều write thành công, hoặc rollback cả hai.

#### Dual-Write Patterns

**1. Synchronous Dual-Write (Strong Consistency):**

```java
// Anti-pattern - NEVER do this
public void updateBalance(AccountId id, Money amount) {
    legacyService.updateBalance(id, amount);  // If this succeeds...
    newService.updateBalance(id, amount);      // ...but this fails?
    // INCONSISTENCY!
}

// Better: Two-Phase Commit (2PC) - but heavy coordination
public void updateBalance(AccountId id, Money amount) {
    Transaction tx = transactionManager.begin();
    try {
        legacyService.prepare(tx, id, amount);  // Phase 1: Prepare
        newService.prepare(tx, id, amount);
        tx.commit();                             // Phase 2: Commit
    } catch (Exception e) {
        tx.rollback();
    }
}
```

> **Trade-off:** 2PC đảm bảo consistency nhưng giảm availability, tăng latency. Banking thường chấp nhận complexity này cho critical transactions.

**2. Asynchronous Dual-Write with Outbox Pattern:**

```
┌─────────────────────────────────────────────────────────────┐
│  APPLICATION                                                │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  Transactional Outbox Table                         │   │
│  │  ┌──────────┬──────────┬──────────┬──────────────┐  │   │
│  │  │ id       │ payload  │ headers  │ status       │  │   │
│  │  │ UUID     │ JSON     │ JSON     │ PENDING      │  │   │
│  │  └──────────┴──────────┴──────────┴──────────────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
│                            │                                │
│  ┌─────────────────────────┼──────────────────────────┐    │
│  │                         ▼                          │    │
│  │  ┌──────────────────────────────────────────────┐  │    │
│  │  │  Database Transaction                        │  │    │
│  │  │  1. UPDATE business_table                    │  │    │
│  │  │  2. INSERT into outbox_table                 │  │    │
│  │  │  COMMIT (atomic)                             │  │    │
│  │  └──────────────────────────────────────────────┘  │    │
│  └────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  CDC / Polling Publisher                                    │
│  - Debezium (Kafka Connect)                                 │
│  - OR Polling with SELECT FOR UPDATE                        │
└──────────────┬──────────────────────────────────────────────┘
               │
       ┌───────┴───────┐
       ▼               ▼
┌──────────────┐ ┌──────────────┐
│  Legacy      │ │  New System  │
│  Consumer    │ │  Consumer    │
└──────────────┘ └──────────────┘
```

**3. CDC (Change Data Capture) with Event Sourcing:**

```
┌─────────────────────────────────────────────────────────────┐
│  Legacy Database                                            │
│  (DB2/Oracle on Mainframe)                                  │
└──────────────┬──────────────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────────────┐
│  CDC Connector (Debezium/IBM InfoSphere)                    │
│  - Captures transaction log (redo/undo logs)                │
│  - Converts to events                                       │
└──────────────┬──────────────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────────────┐
│  Event Transformation Layer                                 │
│  - Schema mapping                                           │
│  - Data type conversion                                     │
│  - Business logic enrichment                                │
└──────────────┬──────────────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────────────┐
│  Event Store (Kafka)                                        │
└──────────────┬──────────────────────────────────────────────┘
               │
       ┌───────┴───────┐
       ▼               ▼
┌──────────────┐ ┌──────────────┐
│  Legacy      │ │  New System  │
│  (Read-only) │ │  (Primary)   │
└──────────────┘ └──────────────┘
```

---

## 3. So Sánh Các Lựa Chọn

### 3.1 Migration Strategies Comparison

| Strategy | Time to Market | Risk | Cost | Best For |
|----------|---------------|------|------|----------|
| **Big Bang** | Short (1-2 years) | Very High | Lower | Small systems, startup |
| **Strangler Fig** | Long (3-5 years) | Low-Medium | Higher | Enterprise, critical systems |
| **Parallel Run** | Long | Low | Very High | Regulatory-heavy (banking) |
| **Replatform (Lift & Shift)** | Short | Medium | Medium | Quick wins, not core |
| **Retire** | Immediate | Low | Low | Unused functionality |

### 3.2 Event Store Technologies

| Feature | Axon Server | EventStoreDB | Kafka (as log) | PostgreSQL |
|---------|-------------|--------------|----------------|------------|
| **Event Sourcing Native** | Yes | Yes | Partial | No |
| **Projection Support** | Built-in | Built-in | External | External |
| **Snapshotting** | Yes | Yes | Manual | Manual |
| **Subscription** | Push | Push | Pull | Pull |
| **Scalability** | Good | Excellent | Excellent | Moderate |
| **Banking Production** | Yes | Yes | Yes (indirect) | Yes (with care) |

### 3.3 Dual-Write Consistency Patterns

| Pattern | Consistency | Latency | Complexity | Availability | Banking Suitability |
|---------|-------------|---------|------------|--------------|---------------------|
| **Synchronous 2PC** | Strong | High | High | Lower | Critical transactions |
| **Outbox + CDC** | Eventual | Low | Medium | High | Most use cases |
| **Saga Pattern** | Eventual | Low-Med | Medium | High | Long-running ops |
| **CDC Only** | Eventual | Low | Low | High | Read replicas |

---

## 4. Rủi Ro, Anti-Patterns và Lỗi Thường Gặp

### 4.1 Critical Anti-Patterns

**1. Distributed Monolith:**
> Tách thành microservices nhưng vẫn deploy cùng lúc, share database, tightly coupled.

**Dấu hiệu:**
- 5 services cùng release trong 1 PR
- Transaction ACID across services
- Circular dependencies

**2. Big Bang Migration:**
> Tắt legacy, bật new system trong một đêm.

**Hậu quả:**
- 1999: London Stock Exchange migration failure → £400M loss
- 2012: RBS batch job failure → 6.5M customers affected

**3. Golden Hammer:**
> "Event Sourcing cho tất cả!" hoặc "Microservices cho tất cả!"

**Quy tắc:** 80% use cases CRUD đơn giản không cần event sourcing.

### 4.2 Dual-Write Failure Modes

| Scenario | Root Cause | Mitigation |
|----------|-----------|------------|
| **Split Brain** | Network partition giữa 2 systems | Circuit breaker, health checks |
| **Data Drift** | Silent failures in one system | Reconciliation jobs, checksums |
| **Ordering Issues** | Events processed out of sequence | Sequence numbers, causality tracking |
| **Performance Cascade** | Slow legacy impacts new system | Async processing, timeouts, bulkheads |

### 4.3 Event Sourcing Pitfalls

**Schema Evolution Nightmare:**
```java
// BAD: Breaking change
public record AmountDeposited(BigDecimal amount) {}
// Changed to:
public record AmountDeposited(BigDecimal amount, String currency) {}
// Old events fail to deserialize!

// GOOD: Versioned events with upcasting
public interface DomainEvent {
    int getVersion();
    default DomainEvent upcast() { return this; }
}
```

**Projection Rebuild Time:**
- 100M events × 10ms/event = 1M seconds ≈ 11.5 days!
- **Giải pháp:** Snapshotting, parallel projection, projection sharding

---

## 5. Khuyến Nghị Thực Chiến Production

### 5.1 Phased Migration Roadmap (3-5 năm)

**Year 1: Foundation**
- Thiết lập API Gateway, Strangler Fig facade
- Implement CDC từ mainframe
- Build event infrastructure (Kafka)
- Extract non-critical services (customer lookup, static data)

**Year 2: Core Domain**
- Extract Account Management với Event Sourcing
- Parallel run cho balance inquiries
- Implement dual-write cho account updates
- Start projection rebuild optimization

**Year 3: Transaction Processing**
- Extract Payment Processing
- Implement Saga pattern cho cross-service transactions
- Migrate daily transaction volume 10% → 50%
- Chaos engineering, resilience testing

**Year 4: Complete Migration**
- Migrate remaining domains
- Decommission mainframe for online transactions
- Retain mainframe cho batch/reporting
- Full observability, SLO monitoring

**Year 5: Optimization**
- Decommission mainframe hoàn toàn
- Optimize new architecture
- Advanced patterns: multi-region, edge computing

### 5.2 Monitoring & Observability

**Critical Metrics:**

| Metric | Target | Alert Threshold |
|--------|--------|-----------------|
| **Consistency Lag** | < 1s | > 5s |
| **Dual-Write Mismatch** | 0 | > 0.001% |
| **Reconciliation Errors** | 0 | > 0 |
| **Event Processing Lag** | < 100ms | > 500ms |
| **Projection Staleness** | < 1s | > 5s |

**Health Checks:**
- `/health/legacy-connection` - Kiểm tra kết nối mainframe
- `/health/event-store` - Event store availability
- `/health/projection-lag` - Projection freshness

### 5.3 Security Considerations

**Data in Transit:**
- mTLS giữa tất cả services
- Token-based auth (OAuth 2.0, SPIFFE)
- API Gateway rate limiting

**Data at Rest:**
- Event encryption (AES-256)
- PII tokenization trong events
- Audit logging cho tất cả access

**Compliance:**
- Immutable audit trail (event sourcing tự nhiên phù hợp)
- Data retention policies
- Right to be forgotten (event deletion - complex với event sourcing!)

---

## 6. Kết Luận

### Bản Chất Cốt Lõi

**Core Banking Modernization** không phải là technical upgrade mà là **business transformation**:

1. **Mainframe Integration** là transition state, không phải end state. Mục tiêu là coexistence có kiểm soát.

2. **Event Sourcing** phù hợp với banking vì audit requirements, nhưng **không phải silver bullet** - cost cao, complexity cao.

3. **Strangler Fig Pattern** là **chiến lược duy nhất khả thi** cho enterprise banking - gradual migration minimizes risk.

4. **Dual-Write Consistency** là bài toán distributed systems cổ điển - trade-off giữa consistency và availability là không tránh khỏi.

### Trade-off Quan Trọng Nhất

| Dimension | Legacy Mainframe | Modern Architecture |
|-----------|-----------------|---------------------|
| **Consistency** | Strong (ACID) | Eventual (default) hoặc Strong (complex) |
| **Availability** | 99.999% | 99.99% (higher complexity cost) |
| **Throughput** | Excellent (batch) | Excellent (real-time) |
| **Flexibility** | Poor | Excellent |
| **Cost (TCO)** | High (license, skills) | High (infrastructure, engineering) |

### Rủi Ro Lớn Nhất

**Data Inconsistency trong Dual-Write Phase** - Một giao dịch ghi thành công ở legacy nhưng fail ở new system (hoặc ngược lại) có thể gây **financial loss** hoặc **regulatory violation**.

**Mitigation:**
- Strong reconciliation processes
- Automated drift detection
- Circuit breakers và graceful degradation
- Comprehensive testing: unit, integration, contract, chaos

---

## 7. Tài Liệu Tham Khảo

1. **"Building Microservices"** - Sam Newman (O'Reilly)
2. **"Implementing Domain-Driven Design"** - Vaughn Vernon
3. **"Enterprise Integration Patterns"** - Gregor Hohpe, Bobby Woolf
4. **"Designing Data-Intensive Applications"** - Martin Kleppmann
5. **"The Data Mesh"** - Zhamak Dehghani
6. **Debezium Documentation** - CDC best practices
7. **Axon Framework Reference** - Event Sourcing patterns
8. **IBM CICS Transaction Gateway Documentation**
9. **"Strangler Fig Application"** - Martin Fowler (martinfowler.com)
10. **"Event Sourcing Pattern"** - Microsoft Azure Architecture Center
