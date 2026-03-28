# CAP Theorem & PACELC - Deep Dive Research

## 1. Mục tiêu của task

Hiểu sâu bản chất của CAP Theorem và mở rộng PACELC, phân tích các trade-off trong thiết kế hệ thống phân tán, và áp dụng đúng đắn trong các kịch bản production thực tế.

## 2. Bản chất và cơ chế hoạt động

### 2.1 CAP Theorem - Nguồn gốc và định nghĩa

**Eric Brewer** đề xuất năm 2000, chứng minh chính thức năm 2002 bởi **Seth Gilbert & Nancy Lynch** (Berkley).

> **CAP Theorem**: Trong một hệ thống phân tán chia sẻ dữ liệu (shared-data distributed system), bạn chỉ có thể đảm bảo **tối đa 2 trong 3 thuộc tính** đồng thời:
> - **C**onsistency: Tất cả nodes nhìn thấy cùng một dữ liệu tại cùng một thờ điểm
> - **A**vailability: Mọi request đều nhận được response (không lỗi, không timeout)
> - **P**artition Tolerance: Hệ thống tiếp tục hoạt động dù network bị partition (mất kết nối giữa các nodes)

### 2.2 Bản chất của "Partition"

```
┌─────────────────────────────────────────────────────────┐
│                    NETWORK PARTITION                     │
├─────────────────────────────────────────────────────────┤
│                                                          │
│   ┌──────────┐         X         ┌──────────┐          │
│   │ Node A   │ ◄──── broken ───► │ Node B   │          │
│   │ (Master) │      network      │ (Replica)│          │
│   └────┬─────┘                   └────┬─────┘          │
│        │                              │                │
│     Write X=1                     Read X=?              │
│        │                              │                │
│   What happens?                                           │
│                                                          │
│   Options:                                               │
│   1. Wait for B → Unavailable (sacrifice A)             │
│   2. Return stale data → Inconsistent (sacrifice C)     │
│   3. Refuse all writes → Unavailable (sacrifice A)      │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

**Partition là hiện tượng không thể tránh khỏi** trong distributed systems:
- Network timeout, packet loss, congestion
- Node crash, hardware failure
- Data center outage, region failure
- **Không thể đảm bảo 100% network reliability**

> ⚠️ **Quan trọng**: Trong thực tế, P (Partition Tolerance) là **bắt buộc phải có**, không phải lựa chọn. Do đó CAP thực chất là lựa chọn giữa **CP** và **AP**.

### 2.3 Các lựa chọn CAP trong thực tế

| Hệ thống | Lựa chọn | Đặc điểm | Use Case |
|----------|----------|----------|----------|
| **ZooKeeper, etcd, Consul** | **CP** | Strong consistency, leader election, có thể reject writes khi partition | Coordination, configuration, service discovery |
| **Cassandra, Riak, DynamoDB** | **AP** | Eventual consistency, highly available, hỗ trợ conflicts resolution | High write throughput, global distribution |
| **MongoDB** | **CP mặc định**, có thể tune | Configurable, replica sets với majority writes | General purpose, có thể adjust theo nhu cầu |
| **HBase** | **CP** | Strong consistency dựa trên HDFS và ZooKeeper | Big data analytics |
| **Amazon S3** | **AP** | Eventual consistency cho overwrite, strong cho new objects | Object storage |

### 2.4 PACELC Theorem - Mở rộng của CAP

**Daniel J. Abadi** (2010) mở rộng CAP để giải thích rõ hơn về trade-off trong hệ thống **non-partitioned**.

> **PACELC**: Nếu có Partition (P), phải chọn giữa Availability (A) và Consistency (C); **Else** (E - không có partition), phải chọn giữa Latency (L) và Consistency (C).

```
┌────────────────────────────────────────────────────────────┐
│                      PACELC Decision                        │
├────────────────────────────────────────────────────────────┤
│                                                            │
│                    P (Partition?)                          │
│                      /    \                                │
│                    Yes    No                               │
│                    /        \                              │
│                   A/C        L/C                           │
│                  /    \     /    \                         │
│                Avail. Consist. Lat. Consist.              │
│                                                            │
│   Ví dụ: DynamoDB                                         │
│   - P → A: Có partition, vẫn available (eventual)         │
│   - E → L: Không partition, chọn latency over consistency │
│                                                            │
│   Ví dụ: Spanner                                          │
│   - P → C: Có partition, chọn consistency                 │
│   - E → C: Không partition, vẫn chọn consistency          │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

**Ý nghĩa của Else (L vs C)**:
- **Latency (L)**: Synchronous replication đến tất cả nodes → high latency
- **Consistency (C)**: Chỉ cần replicate đến quorum → lower latency, có thể stale

**Ví dụ cụ thể DynamoDB**:
- Write operation: Replicate đến 3 AZ (Availability Zones)
- `W=2, R=2` (quorum): Đảm bảo consistency nhưng tăng latency
- `W=1, R=1` (fast): Giảm latency nhưng risk inconsistent

## 3. Kiến trúc và luồng xử lý

### 3.1 CP System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                  CP System (ZooKeeper)                      │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   ┌──────────┐     ┌──────────┐     ┌──────────┐          │
│   │ Leader   │◄───►│ Follower │◄───►│ Follower │          │
│   │ (Write)  │     │          │     │          │          │
│   └────┬─────┘     └──────────┘     └──────────┘          │
│        │                                                    │
│        │ Write Path (Synchronous)                          │
│        │ 1. Propose to Leader                              │
│        │ 2. Leader generates zxid                           │
│        │ 3. Propose to majority (including self)           │
│        │ 4. Wait for ACK from majority                     │
│        │ 5. Commit and ACK to client                       │
│        │                                                    │
│   Partition Scenario:                                       │
│   - Leader mất kết nối với majority → Step down           │
│   - New leader election (ZAB protocol)                    │
│   - Không nhận write trong election period                │
│   → Sacrifice Availability                                 │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**CP Implementation Details**:

1. **Consensus Protocol**: Paxos, Raft, ZAB
2. **Leader Election**: Cần majority để elect leader mới
3. **Write Quorum**: `W > N/2` (strict majority)
4. **Read Quorum**: `R > N/2` hoặc read from leader

**Trade-offs CP**:
- ✅ Strong consistency (linearizable)
- ✅ No conflicts, no data loss
- ❌ Unavailable during partition/network issues
- ❌ Higher latency (round-trip to majority)
- ❌ Lower write throughput

### 3.2 AP System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                  AP System (Cassandra)                      │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   ┌──────────┐     ┌──────────┐     ┌──────────┐          │
│   │ Node A   │◄───►│ Node B   │◄───►│ Node C   │          │
│   │ (Token 0-50)   │ (Token 51-100)  │ (Token 101-150)      │
│   └────┬─────┘     └────┬─────┘     └────┬─────┘          │
│        │                │                │                │
│        └────────────────┴────────────────┘                │
│                    Gossip Protocol                          │
│                                                             │
│   Write Path:                                               │
│   1. Client writes to ANY node (coordinator)              │
│   2. Coordinator forwards to N replicas (based on RF)     │
│   3. Wait for CL (Consistency Level) ACKs                 │
│   4. Return success (even if some nodes failed)           │
│                                                             │
│   CL Options:                                               │
│   - ONE: Ack from 1 replica (fastest)                     │
│   - QUORUM: Ack from majority (balance)                   │
│   - ALL: Ack from all replicas (strongest)                │
│                                                             │
│   Partition Handling:                                       │
│   - Node unreachable → Hinted Handoff (queue writes)      │
│   - Node comeback → Read Repair (fix inconsistencies)     │
│   - Anti-entropy repair (Merkle trees)                    │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**AP Implementation Details**:

1. **Gossip Protocol**: Nodes định kỳ trao đổi state
2. **Vector Clocks**: Track causality cho conflict detection
3. **Hinted Handoff**: Tạm thờ lưu write cho node down
4. **Read Repair**: Sửa inconsistency trong lúc read
5. **Anti-Entropy Repair**: Background reconciliation

**Trade-offs AP**:
- ✅ High availability (99.99%+)
- ✅ Low latency (local writes)
- ✅ High write throughput
- ✅ Geographic distribution
- ❌ Eventual consistency (có thể stale)
- ❌ Conflict resolution complexity
- ❌ Application-level handling required

### 3.3 Mô hình CAP trong Microservices

```
┌─────────────────────────────────────────────────────────────┐
│           CAP trong Microservices Architecture              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   Service A (CP)          Service B (AP)                   │
│   ┌─────────────┐         ┌─────────────┐                  │
│   │ Inventory   │         │ User Session│                  │
│   │ - Stock     │         │ - Login     │                  │
│   │ - Pricing   │         │ - Cart      │                  │
│   │ - Orders    │         │ - Preferences│                 │
│   └─────────────┘         └─────────────┘                  │
│                                                             │
│   Lý do chọn:                                              │
│   - Inventory: Không thể bán quá stock (consistency)      │
│   - Session: Có thể relogin, dùng cache (availability)    │
│                                                             │
│   Hybrid Pattern:                                          │
│   ┌─────────────────────────────────────────┐              │
│   │ Order Service                           │              │
│   │ ┌──────────┐      ┌──────────────┐     │              │
│   │ │ Order DB │(CP)  │ Event Store  │(AP) │              │
│   │ │ (PostgreSQL)    │ (Kafka)      │     │              │
│   │ └──────────┘      └──────────────┘     │              │
│   └─────────────────────────────────────────┘              │
│                                                             │
│   - DB: Strong consistency cho business logic             │
│   - Events: Availability cho async processing             │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## 4. So sánh các lựa chọn

### 4.1 CP vs AP - Decision Matrix

| Tiêu chí | CP Systems | AP Systems |
|----------|------------|------------|
| **Consistency** | Strong (Linearizable) | Eventual |
| **Availability** | Reduced during partition | Always available |
| **Latency** | Higher (quorum round-trip) | Lower (local response) |
| **Throughput** | Lower | Higher |
| **Conflict Resolution** | None needed | Application-level |
| **Use Case** | Financial, Inventory, Booking | Session, Cache, Analytics |
| **Examples** | ZooKeeper, etcd, Spanner, HBase | Cassandra, DynamoDB, Riak, Couchbase |
| **Protocol** | Paxos, Raft, ZAB | Gossip, Vector Clocks, CRDTs |

### 4.2 PACELC trong các hệ thống thực tế

```
┌─────────────────────────────────────────────────────────────┐
│                   PACELC Taxonomy                           │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   PA/EL:  DynamoDB, Cassandra, Riak                        │
│   → Partition: Available                                   │
│   → No Partition: Low Latency (sacrifice consistency)     │
│                                                             │
│   PA/EC:  MongoDB (default), Couchbase                     │
│   → Partition: Available                                   │
│   → No Partition: Consistent (sacrifice latency)          │
│                                                             │
│   PC/EL:  (Hiếm)                                           │
│   → Partition: Consistent                                  │
│   → No Partition: Low Latency                              │
│                                                             │
│   PC/EC:  Spanner, CockroachDB, etcd, ZooKeeper           │
│   → Partition: Consistent                                  │
│   → No Partition: Consistent (always consistent)          │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 4.3 Khi nào chọn CP, khi nào chọn AP

**Chọn CP khi**:
1. Dữ liệu **không thể inconsistent** (bank balance, inventory, ticket booking)
2. Cần **strong guarantees** cho business logic
3. Có thể chấp nhận **downtime ngắn** trong partition
4. **Write volume thấp**, read volume cao
5. Cần **transactions** và ACID properties

**Chọn AP khi**:
1. **Availability là critical** (user sessions, shopping carts)
2. Có thể chấp nhận **temporary inconsistency**
3. **High write throughput** và geographic distribution
4. Application có thể **handle conflicts** (last-write-wins, merge functions)
5. **Eventual consistency** là đủ (analytics, recommendations)

## 5. Rủi ro, Anti-patterns, và Lỗi thường gặp

### 5.1 Anti-pattern: "CAP là binary choice"

> ❌ **Sai lầm**: Nghĩ rằng phải chọn hoàn toàn CP hoặc hoàn toàn AP

```
┌─────────────────────────────────────────────────────────────┐
│              CAP là Spectrum, không phải Binary            │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   Consistency ◄────────────────────────────────► Availability│
│                                                             │
│   Strong    ─┬─ Session    ─┬─ Bounded    ─┬─ Eventual     │
│   (Spanner)  │  Consistency │  Staleness   │  (Cassandra)  │
│              │  (COPS)      │  (PNUTS)     │               │
│                                                             │
│   Tunable Consistency:                                      │
│   - Cassandra: CL=ONE → CL=QUORUM → CL=ALL               │
│   - MongoDB: writeConcern: 1 → majority → all             │
│   - DynamoDB: ConsistentRead=true/false                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 5.2 Anti-pattern: Ignore Network Partitions

```java
// ❌ BAD: Giả định network luôn available
public void placeOrder(Order order) {
    inventoryService.deductStock(order.getItems());  // Network call
    paymentService.charge(order.getPayment());       // Network call  
    orderRepository.save(order);                     // Local DB
    // What if inventory succeeds but payment fails?
    // Partial state, inconsistent data!
}

// ✅ GOOD: Handle partitions, use sagas
@Transactional
public void placeOrder(Order order) {
    try {
        sagaOrchestrator.start()
            .step("deductStock", () -> inventoryService.deduct(order.getItems()))
            .step("chargePayment", () -> paymentService.charge(order.getPayment()))
            .step("saveOrder", () -> orderRepository.save(order))
            .onFailure("deductStock", () -> inventoryService.rollback(order.getItems()))
            .execute();
    } catch (PartitionException e) {
        // Queue for retry, mark as pending
        pendingOrderQueue.enqueue(order);
    }
}
```

### 5.3 Rủi ro: Split-Brain trong CP Systems

```
┌─────────────────────────────────────────────────────────────┐
│              Split-Brain Scenario                           │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   Before Partition:                                         │
│   ┌─────────┐         ┌─────────┐         ┌─────────┐     │
│   │ Node A  │◄───────►│ Node B  │◄───────►│ Node C  │     │
│   │ Leader  │         │ Follower│         │ Follower│     │
│   └─────────┘         └─────────┘         └─────────┘     │
│                                                             │
│   After Partition (Network Split):                         │
│   ┌─────────┐         X         ┌─────────┐ ┌─────────┐   │
│   │ Node A  │◄────broken──────►│ Node B  │◄►│ Node C  │   │
│   │ Leader  │                   │ Leader? │  │ Follower│   │
│   └─────────┘                   └─────────┘  └─────────┘   │
│                                                             │
│   Rủi ro: Cả hai bên đều nghĩ mình là leader               │
│   → Cả hai đều accept writes                               │
│   → Data divergence, không thể merge                       │
│                                                             │
│   Giải pháp:                                                │
│   - Quorum-based voting (Raft: cần majority)              │
│   - Fencing tokens (prevent stale leader writes)          │
│   - Leader lease with timeout                              │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 5.4 Lỗi thường gặp: Monotonic Reads Violation

```
Scenario: User đọc dữ liệu không theo thứ tự thờ gian

T0: Write X=1 to Node A (replicated to B)
T1: Read X from Node B → Returns 1 (OK)
T2: Read X from Node A → Returns stale value 0 (STALE!)

Giải pháp:
1. Read-your-writes: Ghi nhớ node đã write, đọc từ đó
2. Session consistency: Bind session to specific replica
3. Quorum reads: Đọc từ majority
```

### 5.5 Edge Cases và Failure Modes

| Scenario | CP System Behavior | AP System Behavior |
|----------|-------------------|-------------------|
| **Slow network** | Timeout, reject writes | Continue with stale data |
| **Node crash** | Unavailable until recovery | Available with reduced redundancy |
| **Data center outage** | Failover to backup DC | Continue serving from other DCs |
| **Clock skew** | Có thể inconsistent (dùng logical clocks) | Vector clock conflicts |
| **Byzantine failures** | Cần BFT consensus (PBFT) | Có thể corrupt data |
| **Cascade failures** | Election storm, multiple failovers | Gossip amplification |

## 6. Khuyến nghị thực chiến trong Production

### 6.1 Thiết kế theo Business Domain

```
┌─────────────────────────────────────────────────────────────┐
│         Domain-Driven CAP Selection                         │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   ┌─────────────────────────────────────────────────────┐  │
│   │              E-Commerce Platform                      │  │
│   ├─────────────────────────────────────────────────────┤  │
│   │                                                       │  │
│   │  CP Systems:         AP Systems:                     │  │
│   │  ├─ Inventory        ├─ User Session                 │  │
│   │  ├─ Pricing          ├─ Shopping Cart                │  │
│   │  ├─ Order State      ├─ Product Catalog (cache)      │  │
│   │  ├─ Payment          ├─ Recommendations              │  │
│   │  └─ Stock Reservation └─ Analytics                   │  │
│   │                                                       │  │
│   │  Hybrid trong cùng flow:                            │  │
│   │  1. Check inventory (CP) → Confirm available         │  │
│   │  2. Reserve stock (CP) → Prevent oversell            │  │
│   │  3. Add to cart (AP) → Fast response                 │  │
│   │  4. Process payment (CP) → Financial consistency     │  │
│   │                                                       │  │
│   └─────────────────────────────────────────────────────┘  │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 6.2 Monitoring và Observability

**Metrics cần theo dõi**:

```yaml
# CP Systems
leader_election_duration_seconds: histogram  # Quá lâu = unavailable
raft_commit_latency_seconds: histogram       # High = performance issue
cluster_nodes_healthy: gauge                 # < quorum = unavailable
consensus_rounds_total: counter              # Too high = instability

# AP Systems
read_repair_rate: gauge                      # High = nhiều inconsistency
hinted_handoff_queue_size: gauge             # High = nodes down lâu
anti_entropy_repair_duration: histogram      # Long = large divergence
data_divergence_detected: counter            # Cần investigation
```

### 6.3 Configuration Best Practices

**Cassandra (Tunable Consistency)**:
```yaml
# Production recommendations
read_consistency: LOCAL_QUORUM  # Balance consistency và latency
write_consistency: LOCAL_QUORUM # Đảm bảo majority trong local DC

# Critical operations (financial)
critical_read_consistency: EACH_QUORUM  # All DCs

# Background repair
repair_schedule: weekly  # Anti-entropy
```

**MongoDB (Replica Sets)**:
```javascript
// Write concern theo mức độ quan trọng
db.orders.insertOne(order, { writeConcern: { w: "majority", j: true }});
db.sessions.updateOne(query, update, { writeConcern: { w: 1 }});

// Read preference
db.inventory.find().readPref("primary");      // Strong consistency
db.catalog.find().readPref("nearest");        // Low latency
```

### 6.4 Testing Strategies

```
┌─────────────────────────────────────────────────────────────┐
│              Chaos Engineering for CAP                      │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   1. Network Partition Tests:                              │
│      - Block traffic giữa các nodes                        │
│      - Verify behavior: reject writes (CP) vs continue (AP)│
│                                                             │
│   2. Latency Injection:                                    │
│      - Add delay vào network calls                         │
│      - Measure timeout handling                            │
│                                                             │
│   3. Node Failure:                                         │
│      - Kill nodes ngẫu nhiên                               │
│      - Verify failover time và data integrity              │
│                                                             │
│   4. Split-Brain Simulation:                               │
│      - Tạo network partition đối xứng                     │
│      - Verify fencing và leader election                   │
│                                                             │
│   Tools: Gremlin, Chaos Monkey, Toxiproxy                 │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## 7. Kết luận

### Bản chất cốt lõi

1. **CAP không phải là lựa chọn giữa 3 thứ**, mà là **bắt buộc P**, rồi chọn giữa C và A.

2. **PACELC mở rộng** để giải thích trade-off ngay cả khi không có partition (Latency vs Consistency).

3. **Thực tế là spectrum**: Không có hệ thống hoàn toàn CP hoặc AP, mà có thể tune consistency level.

4. **Business domain quyết định**: Cùng một platform có thể dùng cả CP và AP cho các use case khác nhau.

### Quyết định thiết kế

| Situation | Recommendation |
|-----------|----------------|
| Financial data | CP + Strong consistency |
| User sessions | AP + TTL |
| Inventory | CP + Optimistic locking |
| Product catalog | AP + Cache |
| Order processing | Hybrid: CP cho state, AP cho events |

### Pitfall cần tránh

> Không cố gắng "có cả C và A" trong cùng một data store. Thay vào đó, **segregate theo domain** và dùng **eventual consistency patterns** (Saga, CQRS) để liên kết các systems.

---

*Research completed: Understanding the trade-offs is more important than memorizing the theorem.*
