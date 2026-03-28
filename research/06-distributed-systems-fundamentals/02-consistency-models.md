# Consistency Models trong Distributed Systems

## 1. Mục tiêu của Task

Hiểu sâu bản chất các consistency models trong distributed systems: Strong Consistency, Eventual Consistency, Causal Consistency, và Read-Your-Writes. Phân tích cơ chế hoạt động, trade-offs, failure modes, và khuyến nghị áp dụng trong production.

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 Khái niệm cốt lõi: Consistency là gì?

**Consistency** trong distributed systems đề cập đến **mức độ đồng nhất** của dữ liệu giữa các replicas tại các thờii điểm khác nhau. 

> **Bản chất:** Consistency không phải là "có" hay "không có" — đó là **spectrum** (quang phổ) với nhiều mức độ, mỗi mức đánh đổi giữa **availability** và **data correctness**.

Khi client đọc dữ liệu sau khi write, consistency model quyết định:
- Client có **đảm bảo** nhìn thấy giá trị mới nhất?
- Client có thể nhìn thấy giá trị **cũ** trong bao lâu?
- Các clients khác nhau có nhìn thấy **cùng một thứ tự** các updates?

### 2.2 Consistency Models theo thứ tự độ mạnh

```
Linearizability (Strong) → Sequential → Causal → Session → Eventual (Weak)
     ↑                           ↑          ↑         ↑          ↑
  Highest                    Strong     Moderate   Weak     Lowest
  Correctness                Consistency         Consistency
  Lowest                                             
  Availability                                      Highest
                                                   Availability
```

---

## 3. Strong Consistency (Linearizability)

### 3.1 Định nghĩa chính xác

Linearizability đảm bảo:
1. **Mọi operation** xuất hiện như thể xảy ra **tại một điểm thờii gian duy nhất** (atomic)
2. **Thứ tự** của operations tương ứng với **real-time order**
3. **Read** phải trả về giá trị của **write gần nhất** đã complete

### 3.2 Cơ chế hoạt động ở tầng thấp

#### Synchronous Replication (Primary-Backup)

```
┌─────────┐     Write(x=5)     ┌──────────┐
│ Client  │ ─────────────────→ │ Primary  │
└─────────┘                    │  (Leader)│
                               └────┬─────┘
                                    │ Synchronous
                                    │ Replication
                   ┌────────────────┼────────────────┐
                   ↓                ↓                ↓
              ┌─────────┐     ┌─────────┐     ┌─────────┐
              │Replica 1│     │Replica 2│     │Replica N│
              │  x=5    │     │  x=5    │     │  x=5    │
              └─────────┘     └─────────┘     └─────────┘
                   │                │                │
                   └────────────────┼────────────────┘
                                    ↓ Ack All
                              ┌──────────┐
                              │  OK to   │
                              │  Client  │
                              └──────────┘
```

**Bản chất cơ chế:**
- Write không được coi là **complete** cho đến khi **tất cả replicas** xác nhận đã persist
- Read từ bất kỳ replica nào đều nhận được giá trị mới nhất
- **Paxos/Raft** protocols đảm bảo consensus trên write order

#### Quorum-based Replication

```
N = total replicas
W = write quorum (số replicas cần xác nhận write)
R = read quorum (số replicas cần đọc)

Điều kiện cho Linearizability: W + R > N

Ví dụ: N=5, W=3, R=3 → 3+3=6 > 5 ✓
```

### 3.3 Trade-offs sâu

| Khía cạnh | Impact | Chi tiết |
|-----------|--------|----------|
| **Latency** | Cao | Phải đợi network round-trip đến remote replicas. Cross-region → 100-300ms |
| **Availability** | Thấp | Nếu minority replicas down → vẫn OK. Nếu majority down → unavailable |
| **Throughput** | Giảm | Mỗi write phải đợi nhiều acknowledgments |
| **Consistency** | Hoàn hảo | Không có stale read, race condition trong reads |

### 3.4 Failure Modes

**Scenario 1: Network Partition**
```
Partition A (Majority)          Partition B (Minority)
┌─────────┐  ┌─────────┐        ┌─────────┐
│Primary  │  │Replica 2│        │Replica 3│  ← Không thể xử lý writes
│(Active) │  │(Active) │        │(Blocked)│     vì không đủ quorum
└─────────┘  └─────────┘        └─────────┘
```
- Hệ thống phải **chọn**: availability (cho phép writes ở cả 2 partition → mất consistency) hoặc consistency (block writes ở minority)
- Linearizability chọn **consistency** → minority partition reject writes

**Scenario 2: Leader Failure**
- Cần **leader election** mới có thể tiếp tục writes
- Election timeout (thường 1-10s) → unavailability window

---

## 4. Eventual Consistency

### 4.1 Định nghĩa chính xác

> **Eventual Consistency:** Nếu không có updates nào mới trong một khoảng thờii gian đủ dài, **cuối cùng** tất cả replicas sẽ converge về cùng giá trị.

**Keywords:** "Không có updates mới" + "đủ lâu" + "cuối cùng"

### 4.2 Cơ chế hoạt động

#### Asynchronous Replication (Fire-and-Forget)

```
┌─────────┐     Write(x=5)     ┌──────────┐
│ Client  │ ─────────────────→ │  Node A  │
└─────────┘                    │ (Primary)│
                               └────┬─────┘
                                    │
                              ACK to Client
                                    │ (Write considered complete)
                                    ↓
                               Async Queue
                                    │
                   ┌────────────────┼────────────────┐
                   ↓ (async)        ↓ (async)        ↓ (async)
              ┌─────────┐     ┌─────────┐     ┌─────────┐
              │ Node B  │     │ Node C  │     │ Node D  │
              │  x=5    │     │pending  │     │pending  │
              └─────────┘     └─────────┘     └─────────┘
                   ↑
              Client 2 đọc ở đây → thấy x=5 ✓
                   ↓
              Client 3 đọc ở Node C → thấy x=old ✗ (stale)
```

**Bản chất:**
- Write **complete ngay** khi primary persist
- Replicate **async** → không đợi replicas
- Replicas nhận updates **out-of-order** hoặc **delayed**

#### Conflict Resolution Strategies

Khi 2 nodes cùng modify cùng key:

| Strategy | Khi nào dùng | Trade-off |
|----------|--------------|-----------|
| **Last-Write-Wins (LWW)** | Dữ liệu có thể overwrite | Dễ implement, có thể mất updates |
| **Vector Clocks** | Cần detect concurrent writes | Phức tạp, cần client resolve |
| **CRDTs** | Collaborative editing | Tự động merge, nhưng limited data types |

```
Vector Clock Example:
Node A: [2, 0, 0]  ← A đã thực hiện 2 writes
Node B: [1, 3, 0]  ← B đã thực hiện 3 writes  
Node C: [1, 2, 1]  ← C đã thực hiện 1 write

So sánh: [2,0,0] vs [1,3,0] → Concurrent (không thể ordering)
         [2,0,0] vs [1,0,0] → [2,0,0] is descendant (mới hơn)
```

### 4.3 Trade-offs sâu

| Khía cạnh | Impact | Chi tiết |
|-----------|--------|----------|
| **Latency** | Thấp | Write complete ngay, không đợi replication |
| **Availability** | Cao | Bất kỳ node nào cũng có thể accept writes |
| **Throughput** | Cao | Không bị chặn bởi replication latency |
| **Consistency** | Yếu | Stale reads, read-your-own-writes không đảm bảo |

### 4.4 Anti-patterns và Pitfalls

**Anti-pattern 1: Read sau Write Expect Immediate Consistency**
```java
// ❌ SAI: Giả định read sau write sẽ thấy dữ liệu mới
db.write(key, value);           // Write vào Node A
result = db.read(key);          // Read từ Node B (chưa replicate)
// result có thể là giá trị CŨ!
```

**Anti-pattern 2: Không handle conflicts**
```java
// ❌ SAI: Giả định writes không bao giờ conflict
user.updateEmail(newEmail);     // Write ở Datacenter 1
user.updatePhone(newPhone);     // Write ở Datacenter 2 (concurrent)
// Kết quả: Một trong hai bị mất nếu dùng LWW!
```

---

## 5. Causal Consistency

### 5.1 Định nghĩa chính xác

> **Causal Consistency:** Nếu operation B **causally depends on** operation A (A "happens-before" B), thì mọi node phải nhìn thấy A trước B. Các operations **concurrent** (không có quan hệ causal) có thể nhìn thấy trong bất kỳ thứ tự nào.

**"Happens-before" relationship:**
- A và B cùng process, A trước B → A → B
- A là send message, B là receive → A → B
- A → B và B → C → A → C (transitive)

### 5.2 Cơ chế hoạt động

#### Logical Clocks (Lamport Timestamps / Vector Clocks)

```
┌─────────┐              ┌─────────┐              ┌─────────┐
│Process 0│              │Process 1│              │Process 2│
└────┬────┘              └────┬────┘              └────┬────┘
     │                        │                        │
     │ W(x=1)                 │                        │
     │ VC: [1,0,0]            │                        │
     ↓                        │                        │
     │ ──────────────────────→│ R(x=1) → W(y=2)        │
     │      VC: [1,0,0]       │ VC: [1,2,0]            │
     │                        │ (causal: read x trước) │
     │                        ↓                        │
     │                        │ ──────────────────────→│
     │                        │      VC: [1,2,0]       │
     │                        │                        │ R(y=2)
     │                        │                        │ VC: [1,2,1]
     │                        │                        │ (nhìn thấy x=1
     │                        │                        │  vì [1,2,1] > [1,0,0])
```

**Vector Clock Comparison:**
- VC1 ≤ VC2 nếu mọi element VC1[i] ≤ VC2[i]
- VC1 < VC2 nếu VC1 ≤ VC2 và VC1 ≠ VC2
- Nếu không thể so sánh → **concurrent** (không có causal relationship)

#### Implementation: Causal Broadcast

```
Mỗi node maintain:
- Local vector clock VC[1..N]
- Delivery buffer cho out-of-order messages

Khi node i send message:
  VC[i]++
  Send message với timestamp VC

Khi node j receive message từ node i với timestamp T:
  Nếu T[i] == VC[i] + 1 và T[k] ≤ VC[k] ∀k≠i:
     → Deliver ngay (causally ready)
  Ngược lại:
     → Buffer, đợi các message trước đó
```

### 5.3 Trade-offs sâu

| Khía cạnh | So với Strong | So với Eventual |
|-----------|---------------|-----------------|
| **Latency** | Thấp hơn (không cần global sync) | Cao hơn (phải track dependencies) |
| **Availability** | Cao hơn | Tương đương |
| **Correctness** | Weaker (chỉ đảm bảo causal) | Stronger (đảm bảo causal) |
| **Overhead** | Thấp hơn linearizability | Cao hơn (vector clocks) |

### 5.4 Use Cases

**Phù hợp:**
- Social media comments (comment reply phải thấy comment gốc)
- Collaborative editing (thứ tự typing matters)
- Distributed transactions có dependencies

**Không phù hợp:**
- Banking (cần strong consistency)
- Real-time bidding (cần linearizability)

---

## 6. Read-Your-Writes Consistency

### 6.1 Định nghĩa chính xác

> **Read-Your-Writes:** Nếu một client thực hiện write, các **subsequent reads** của cùng client đó **phải** nhìn thấy giá trị đã write (hoặc newer).

Đây là **session guarantee** — chỉ áp dụng cho cùng một client/session, không phải global property.

### 6.2 Cơ chế hoạt động

#### Session Stickiness (Read from Same Node)

```
┌─────────┐     Write(x=5)     ┌──────────┐
│ Client  │ ─────────────────→ │  Node A  │
└────┬────┘                    │ (Sticky) │
     │                         └────┬─────┘
     │                              │
     │ Read(x)                      │
     │─────────────────────────────→│
     │                              │
     │←─────────────────────────────│ Return x=5
     │     (Đảm bảo thấy x=5        │
     │      vì đọc cùng node)       │
```

**Bản chất:** Client gắn vào một node cụ thể (sticky session). Write và read cùng node → consistency.

#### Client-side Caching with Write-through

```
┌─────────┐
│ Client  │
├─────────┤
│ Cache   │ ← Lưu giá trị vừa write
└────┬────┘
     │
     │ Read(x)
     │
     ↓ Cache hit?
  ┌───────┐
  │ Yes   │ → Return cached value
  │       │   (đảm bảo read-your-writes)
  │ No    │ → Read từ server
  └───────┘
```

**Bản chất:** Client tự cache writes của mình. Khi read, ưu tiên check cache trước.

#### Monotonic Reads Token

```
Mỗi write trả về token (timestamp/version)
Client gửi token khi read
Server đảm bảo data ≥ token mới trả về

Client: Write(x=5) → Server → OK, token=t1
Client: Read(x), token=t1 → Server
        Server: Nếu local version < t1 → forward đến node có t1
        → Return x=5
```

### 6.3 Trade-offs

| Khía cạnh | Impact |
|-----------|--------|
| **Implementation Complexity** | Trung bình (cần session tracking hoặc client-side logic) |
| **Availability** | Cao (không block toàn hệ thống) |
| **Scope** | Chỉ đảm bảo cho cùng client |
| **Use case** | User experience (user thấy hành động của mình) |

### 6.4 Failure Modes

**Problem: Session Migration**
```
Client write ở Node A → Node A fail
Client reconnect ở Node B → Node B chưa replicate
→ Client không thấy write của mình!
```

**Giải pháp:**
- Force replication trước khi switch node
- Hoặc forward read đến node cũ (nếu còn alive)
- Hoặc dùng quorum read để đảm bảo thấy latest

---

## 7. So Sánh Chi Tiết Các Consistency Models

### 7.1 Bảng so sánh toàn diện

| Model | Latency | Availability | Stale Read? | Implementation | Best For |
|-------|---------|--------------|-------------|----------------|----------|
| **Linearizable** | High | Low | Never | Paxos/Raft, Quorum | Banking, Inventory |
| **Sequential** | High | Low | Never | Total order broadcast | Transaction logs |
| **Causal** | Medium | Medium | Concurrent ops | Vector clocks | Social, Comments |
| **Read-Your-Writes** | Low | High | Others' writes | Session stickiness | User sessions |
| **Eventual** | Low | High | Possible | Async replication | Analytics, Caching |

### 7.2 Consistency Models trong thực tế

| System | Default Consistency | Configurable? |
|--------|-------------------|---------------|
| **Spanner** | External consistency (linearizable) | No |
| **etcd/ZooKeeper** | Linearizable | No |
| **MongoDB** | Eventual | Yes (readConcern/writeConcern) |
| **Cassandra** | Eventual | Yes (ONE, QUORUM, ALL) |
| **DynamoDB** | Eventual | Yes (strongly consistent read) |
| **Redis Cluster** | Eventual | Partial |
| **CockroachDB** | Serializable | No |

---

## 8. Rủi Ro, Anti-patterns, và Lỗi Thường Gặp

### 8.1 Anti-pattern: Mixing Consistency Levels

```java
// ❌ SAI: Write với weak consistency, read với strong expectation
db.write(key, value, WriteConcern.W1);      // Chờ 1 node
result = db.read(key, ReadConcern.MAJORITY); // Đọc majority
// Không có guarantee gì về ordering!
```

### 8.2 Anti-pattern: Không Handle Stale Reads

```java
// ❌ SAI: Code giả định mọi read đều consistent
void updateInventory(String productId, int delta) {
    int current = inventory.get(productId);  // Có thể stale!
    inventory.put(productId, current + delta);
    // Race condition: concurrent updates mất dữ liệu
}

// ✅ ĐÚNG: Dùng atomic operations hoặc versioning
void updateInventory(String productId, int delta) {
    inventory.updateOne(
        Filters.eq("_id", productId),
        Updates.inc("quantity", delta)  // Atomic increment
    );
}
```

### 8.3 Edge Case: Clock Skew

```
Node A clock: 10:00:00 → Write với timestamp 10:00:00
Node B clock: 10:00:05 → Write với timestamp 10:00:05 (LWW)

Nhưng: Node A clock nhanh hơn 10 giây!
Thực tế: Write A xảy ra SAU write B, nhưng timestamp sớm hơn
→ Write B bị overwrite (mất dữ liệu)

Giải pháp: Dùng logical clocks (vector clocks, Lamport) hoặc
          Spanner's TrueTime API (atomic clocks + GPS)
```

### 8.4 Failure Mode: Thundering Herd sau Replication Lag

```
Scenario:
1. Write x=5 vào primary
2. Replication lag 500ms
3. 1000 clients đọc x từ replicas (đều thấy x=old)
4. Cache miss → 1000 requests đến primary để lấy fresh data

Giải pháp:
- Staggered cache expiration
- Lease-based caching
- Read-through with write-invalidation
```

---

## 9. Khuyến Nghị Thực Chiến trong Production

### 9.1 Chọn Consistency Model theo Use Case

```
┌─────────────────────────────────────────────────────────────┐
│                    CHOOSE CONSISTENCY                        │
└─────────────────────────────────────────────────────────────┘
                              │
         ┌────────────────────┼────────────────────┐
         ↓                    ↓                    ↓
    Financial Data      User Session Data      Analytics Data
    (balance, stock)    (profile, prefs)       (metrics, logs)
         │                    │                    │
         ↓                    ↓                    ↓
    ┌─────────┐         ┌─────────┐          ┌─────────┐
    │LINEARIZABLE│      │READ-YOUR│          │EVENTUAL │
    │  + MVCC   │        │-WRITES  │          │         │
    └─────────┘         └─────────┘          └─────────┘
         │                    │                    │
    Spanner,           Sticky sessions,     Cassandra,
    CockroachDB        Redis + session,    S3, CDN
    Serializable       Client cache
```

### 9.2 Monitoring và Observability

**Metrics cần track:**
```yaml
replication_lag_seconds:     # P99 replication lag
stale_read_rate:             # % reads trả về stale data
conflict_resolution_count:   # Số conflicts phải resolve
vector_clock_size:           # Kích thước vector clocks (growth)
consistency_violations:      # Phát hiện violations (nếu có)
```

**Alerting:**
```yaml
Critical: replication_lag > 10s
Warning:  stale_read_rate > 1%
Info:     conflict_resolution_count spike
```

### 9.3 Testing Consistency

**Jepsen Testing:** Framework để verify consistency claims
```
Tests:
1. Linearizability: Kiểm tra history có linearizable không
2. Sequential: Kiểm tra total order
3. Causal: Kiểm tra happens-before relationships
4. Read-your-writes: Kiểm tra session guarantees
```

**Chaos Engineering:**
```
- Random network partitions
- Clock skew injection
- Node failures during writes
- Measure consistency violations
```

### 9.4 Java Implementation Tips

**For Strong Consistency (etcd/ZooKeeper):**
```java
// Sử dụng Curator Framework cho ZooKeeper
CuratorFramework client = CuratorFrameworkFactory.newClient(
    "zk1:2181,zk2:2181",
    new ExponentialBackoffRetry(1000, 3)
);

// Write với sync
client.create().withMode(CreateMode.PERSISTENT).forPath("/key", data);

// Read đảm bảo fresh
client.getData().storingStatIn(stat).forPath("/key");
```

**For Eventual Consistency (Cassandra):**
```java
// Tune consistency levels
session.execute(
    SimpleStatement.builder("INSERT INTO ...")
        .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
        .build()
);

// Read với CL mạnh hơn nếu cần
session.execute(
    SimpleStatement.builder("SELECT ...")
        .setConsistencyLevel(ConsistencyLevel.QUORUM)
        .build()
);
```

**For Read-Your-Writes (Redis):**
```java
// Sticky connection cho session
JedisPool pool = new JedisPool("redis-node-1", 6379);
Jedis jedis = pool.getResource();

jedis.set("session:data", value);    // Write
String data = jedis.get("session:data");  // Read - same connection
```

---

## 10. Kết Luận

### Bản chất cần nhớ

1. **Consistency là trade-off:** Không có "tốt nhất" — chỉ có "phù hợp nhất" cho use case.

2. **Strong consistency = Synchronous coordination:** Đánh đổi latency và availability lấy correctness.

3. **Eventual consistency = Optimistic replication:** Chấp nhận temporary inconsistency lấy availability.

4. **Causal consistency = Sweet spot:** Đảm bảo ordering có ý nghĩa mà không tốn chi phí global coordination.

5. **Read-your-writes = User experience:** Đảm bảo người dùng thấy hành động của họ, không nhất thiết phải strong consistency toàn hệ thống.

### Quyết định kiến trúc

| Nếu bạn cần... | Hãy chọn... | Ví dụ hệ thống |
|----------------|-------------|----------------|
| Không bao giờ sai về data | Linearizability + MVCC | Banking, Healthcare |
| UX tốt + scale tốt | Read-your-writes + Eventual | Social media, E-commerce |
| Real-time collaboration | Causal consistency | Google Docs, Figma |
| Max availability | Eventual + CRDTs | Global CDN, Analytics |

### Câu hỏi tự kiểm tra

1. Hệ thống của bạn có thể chấp nhận stale read không? Trong bao lâu?
2. Nếu user A thấy update của user B, thì user C có cần thấy cùng thứ tự?
3. Chi phí của consistency violation là gì? (Tiền, reputation, legal?)
4. Bạn có monitoring để phát hiện consistency violations?

---

## 11. Tài Liệu Tham Khảo

1. **"Consistency Models"** - Jepsen.io Documentation
2. **"Designing Data-Intensive Applications"** - Martin Kleppmann (Chapters 5, 7, 9)
3. **"Causal Memory: Definitions, Implementation, and Programming"** - Ahamad et al.
4. **"Dynamo: Amazon's Highly Available Key-value Store"** - DeCandia et al.
5. **"Spanner: Google's Globally-Distributed Database"** - Corbett et al.
6. **"Brewer's Conjecture and the Feasibility of Consistent, Available, Partition-Tolerant Web Services"** - Gilbert & Lynch
