# Consistency Models, CAP & PACELC Theorem: Bản Chất Trade-off Trong Distributed Systems

## 1. Mục Tiêu Nghiên Cứu

Hiểu sâu bản chất các consistency model trong hệ thống phân tán, phân biệt rõ sự khác nhau giữa Linearizability, Sequential Consistency và Eventual Consistency. Phân tích CAP theorem và PACELC theorem không chỉ ở mức định nghĩa mà còn hiểu tại sao chúng tồn tại, khi nào áp dụng, và trade-off thực sự khi thiết kế hệ thống production.

---

## 2. Bản Chất Cổ Điển: Từ Single Node Đến Distributed

### 2.1 Điểm Khởi Đầu: Consistency Trong Single Node

Trên một máy đơn, consistency là hiển nhiên - một write xảy ra trước read thì read phải thấy giá trị mới. Nhưng khi dữ liệu được replicate sang nhiều node, định nghĩa "trước" và "sau" trở nên mơ hồ vì:

- **Clock không đồng bộ:** Mỗi node có clock riêng, không thể đồng bộ hoàn hảo
- **Network delay:** Tin nhắn truyền qua network có độ trễ không xác định
- **Partition có thể xảy ra:** Network có thể bị chia cắt bất cứ lúc nào

> **Insight cốt lõi:** Consistency model là một **contract** giữa hệ thống và ngườidùng. Nó định nghĩa: "Nếu bạn tuân thủ các quy tắc này, hệ thống đảm bảo điều này."

---

## 3. Linearizability: Consistency Mạnh Nhất

### 3.1 Định Nghĩa Chính Xác

Linearizability (còn gọi là atomic consistency) yêu cầu:

> **Mỗi operation xuất hiện như thể nó xảy ra tại một thứ điểm duy nhất, ngay lập tức, giữa invocation và completion của nó.**

Hiểu theo cách khác: Tồn tại một **total order** của tất cả operations trên toàn hệ thống, và thứ tự này **tôn trọng real-time** (nếu operation A kết thúc trước khi B bắt đầu, A phải xuất hiện trước B trong total order).

### 3.2 Cơ Chế Hoạt Động

```
Timeline thực tế:
Client 1: [====Write x=1====>]
Client 2:          [<====Read x====]
Client 3:    [<====Read x====]

Linearizable view:
Time ----->
Write x=1 (atomic point)
   |<-- Read x=1 (Client 2)
|<-- Read x=0 (Client 3 - before write)
```

**Yêu cầu kỹ thuật:**
1. **Single coordination point:** Mọi write phải đi qua một leader/master
2. **Synchronous replication:** Write chỉ được ack sau khi replicate đến majority
3. **Read from leader hoặc sync replica:** Đảm bảo read thấy latest value

### 3.3 Bản Chất Chi Phí

| Aspect | Chi Phí | Giải Thích |
|--------|---------|------------|
| **Latency** | Cao | Mọi write phải đợi replicate đến majority |
| **Throughput** | Thấp | Leader là bottleneck |
| **Availability** | Giảm khi partition | Không thể write nếu không có majority |
| **Geo-distribution** | Khó khăn | Cross-region latency làm chậm mọi operation |

### 3.4 Khi Nào Dùng Linearizability

**Nên dùng:**
- Distributed locks (etcd, ZooKeeper)
- Leader election
- Configuration management (consensus on config value)
- Bank account balance (nếu yêu cầu strict)
- Inventory systems (tránh overselling)

**Ví dụ thực tế:**
- etcd (mặc định linearizable reads)
- ZooKeeper
- Spanner (TrueTime API giúp global linearizability)

---

## 4. Sequential Consistency: Cân Bằng Tốt Hơn

### 4.1 Định Nghĩa

Sequential consistency yêu cầu:

> **Tồn tại một total order của tất cả operations sao cho:**
> 1. **Tôn trọng program order** của mỗi process (operations của cùng một client phải xuất hiện theo đúng thứ tự client gửi)
> 2. **Không yêu cầu tôn trọng real-time**

### 4.2 Sự Khác Biệt Với Linearizability

```
Timeline:
P1: [Write x=1>]      [Write x=2>]
P2:      [<Read x=1]      [<Read x=2]
P3:           [<Read x=2]      [<Read x=1]  

Sequential consistent? ✅ YES
- P3 thấy x=2 trước x=1, vi phạm real-time nhưng...
- P1's writes theo đúng order (1 trước 2)
- P2's reads theo đúng order (1 trước 2)
- P3's reads theo đúng order (2 trước 1)
- Total order tồn tại: W(x=1), W(x=2), R(x=2), R(x=1), R(x=2), R(x=1)

Linearizable? ❌ NO
- W(x=2) kết thúc sau khi P3 đã read x=2
- Real-time constraint bị vi phạm
```

### 4.3 Trade-off

| | Linearizability | Sequential Consistency |
|---|---|---|
| **Latency** | Cao (cross-node sync) | Trung bình (local batching possible) |
| **Implementation** | Phức tạp | Đơn giản hơn |
| **Real-time guarantee** | Có | Không |
| **Use case** | Global coordination | Multi-core CPU, cache coherence |

> **Lưu ý:** Multi-core processors thường implement sequential consistency chứ không phải linearizability hoàn hảo vì chi phí quá cao.

---

## 5. Eventual Consistency: Chấp Nhận Để Scale

### 5.1 Định Nghĩa

> **Nếu không có write mới nào, eventually tất cả replicas sẽ converge về cùng một giá trị.**

**Không đảm bảo:**
- Thứ tự của writes
- Read thấy latest write ngay lập tức
- Các replicas nhất quán với nhau tại mọi thờidiểm

### 5.2 Cơ Chế Hoạt Động

**Replication Models:**

1. **Primary-Backup (Async)**
   - Write vào primary, ack ngay
   - Background replication sang backups
   - Risk: Data loss nếu primary crash trước khi replicate

2. **Multi-Master (Active-Active)**
   - Writes accepted ở mọi node
   - Conflict resolution: Last-Write-Wins (LWW), vector clocks, CRDTs

3. **Gossip Protocol**
   - Nodes trao đổi state với neighbors ngẫu nhiên
   - Epidemic spread của updates
   - Không có coordination center

### 5.3 Conflict Resolution

**Vấn đề:** Hai clients viết cùng key ở hai nodes khác nhau, cùng thờidiểm.

```
Node A: Write x = "Alice"
Node B: Write x = "Bob"

Conflict! Các cách resolve:
1. Last-Write-Wins (LWW): Dựa timestamp → mất dữ liệu
2. Vector clocks: Detect conflict, cần application resolve
3. CRDTs: Conflict-free replicated data types (automatic merge)
```

### 5.4 Khi Nào Dùng Eventual Consistency

**Phù hợp:**
- Social media feeds (Facebook, Twitter timelines)
- Comments, likes, view counts
- Shopping cart (Amazon Dynamo origin)
- DNS
- CDN cache
- Recommendations

**Không phù hợp:**
- Bank transactions
- Inventory management (nếu có rủi ro oversell)
- Medical records
- Authentication/Authorization

---

## 6. CAP Theorem: Không Thể Có Đủ Cả Ba

### 6.1 Định Nghĩa Chính Xác

> **Trong một hệ thống phân tán, khi xảy ra network partition, bạn chỉ có thể chọn một trong hai: Consistency hoặc Availability.**

**Ba thuộc tính:**

| Thuộc tính | Định nghĩa |
|------------|------------|
| **Consistency (C)** | Mọi read nhận được giá trị của write gần nhất hoặc error |
| **Availability (A)** | Mọi request nhận được non-error response (không guarantee data mới nhất) |
| **Partition Tolerance (P)** | Hệ thống tiếp tục hoạt động dù network bị chia cắt |

### 6.2 Bản Chất: Tại Sao Chỉ Chọn Được 2/3?

**Scenario:** Network partition xảy ra, node A và B không thể communicate.

Client gửi write đến node A:

**Option 1: Chọn CP (Consistency + Partition Tolerance)**
- Node A phải replicate sang B trước khi ack
- Nhưng A không thể contact B (partition)
- **→ Từ chối write (sacrifice Availability)**

**Option 2: Chọn AP (Availability + Partition Tolerance)**
- Node A accept và ack write ngay
- Nhưng B không biết về write này
- Client đọc từ B sẽ thấy stale data
- **→ Violate Consistency**

### 6.3 Hiểu Đúng CAP

> **Misconception phổ biến:** "Hệ thống là CP hoặc AP"

**Sự thật:**
- CAP chỉ áp dụng khi **có partition**
- Khi không có partition, hệ thống có thể đạt cả C và A
- Nhiều hệ thống có thể **configurable** (tune consistency level per request)

**Ví dụ Cassandra:**
```sql
-- Tune consistency per query
CONSISTENCY ALL;      -- CP-like behavior
CONSISTENCY ONE;      -- AP-like behavior
CONSISTENCY QUORUM;   -- Balance
```

### 6.4 Hệ Thống Thực Tế

| Hệ thống | Mặc định | Ghi chú |
|----------|----------|---------|
| **etcd/ZooKeeper** | CP | Sacrifice availability trong partition để giữ consistency |
| **Cassandra** | AP | Tunable consistency |
| **MongoDB** | CP (với majority write) | Có thể cấu hình |
| **DynamoDB** | AP | Có thể dùng strong consistency reads |
| **CockroachDB** | CP | Serializable default |
| **Riak** | AP | Eventual consistency |

---

## 7. PACELC Theorem: Mở Rộng CAP

### 7.1 Tại Sao Cần PACELC?

CAP chỉ nói về behavior khi có partition. Nhưng trong thực tế:
- **Phần lớn thờigian, partition không xảy ra**
- Khi không có partition, vẫn có trade-off giữa **Latency** và **Consistency**

### 7.2 Định Nghĩa PACELC

> **Nếu có Partition (P), chọn giữa Availability (A) và Consistency (C)**
> **Else (E), chọn giữa Latency (L) và Consistency (C)**

```
PACELC:
┌─────────────────────────────────────────┐
│  IF Partition?                          │
│    ├─ YES → Chọn (A) hoặc (C)          │
│    └─ NO  → Chọn (L) hoặc (C)          │
└─────────────────────────────────────────┘
```

### 7.3 Trade-off Latency vs Consistency

**Khi không có partition:**

| Strategy | Latency | Consistency | Ví dụ |
|----------|---------|-------------|-------|
| **Sync replication to all nodes** | Cao | Cao | Synchronous mirroring |
| **Sync to majority** | Trung bình | Khá cao | Paxos, Raft |
| **Async replication** | Thấp | Eventual | Master-slave async |
| **Read from local replica** | Thấp nhất | Có thể stale | CDN, local DB replica |

### 7.4 Áp Dụng PACELC Trong Thiết Kế

**Scenario: E-commerce checkout**

```
1. Add to cart (AP/E-L): 
   - Cần availability cao, latency thấp
   - Eventual consistency OK
   
2. Checkout (CP/E-C):
   - Cần strong consistency
   - Check inventory, apply promotion
   - Chấp nhận latency cao hơn
   
3. Payment (CP):
   - Strong consistency bắt buộc
   - Distributed transaction hoặc Saga
```

---

## 8. Failure Modes & Anti-patterns

### 8.1 Các Lỗi Thường Gặp

**1. Hiểu Sai CAP**
- Nghĩ rằng "chọn CP" = "luôn có consistency"
- Thực tế: CAP chỉ nói về behavior trong partition
- Consistency còn phụ thuộc vào implementation

**2. Quên mất Latency Trade-off**
- Chọn strong consistency mà không đo latency impact
- User experience tệ do request chậm

**3. Dual Writes**
```java
// ANTI-PATTERN: Không bao giờ làm thế này
db.write(data);
cache.write(data);  // Nếu crash ở đây → db và cache inconsistent
```

**4. Không Handle Stale Reads**
- App đọc từ async replica nhưng expect latest data
- Hiển thị outdated information cho user

**5. Read-Your-Write Consistency Bug**
```
User: Write comment → POST /api/comments
Backend: Write vào primary DB, ack ngay
User: Refresh page → GET /api/comments (read from replica)
Problem: Replica chưa có data mới → User không thấy comment vừa viết
```

### 8.2 Production Concerns

**Monitoring:**
- Replication lag metrics
- Read consistency violations (nếu detect được)
- Conflict rate (với multi-master)

**Testing:**
- Jepsen testing (formal verification của distributed systems)
- Chaos engineering (inject network partition)
- Load testing với various consistency levels

**Operational:**
- Runbook cho consistency violations
- Manual repair procedures
- Data reconciliation jobs

---

## 9. Khuyến Nghị Thực Chiến

### 9.1 Quy Trình Quyết Định

```
Bắt đầu thiết kế feature:
│
├─ Dữ liệu có cần strong consistency? 
│  ├─ KHÔNG (social feed, analytics)
│  │   └─ Eventual consistency + async replication
│  │
│  └─ CÓ (financial, inventory, config)
│      ├─ Cần global hay partition-tolerant?
│      │   ├─ Global → Spanner-like (TrueTime)
│      │   └─ Partition-tolerant → Consensus (Raft/Paxos)
│      └─ Chấp nhận unavailability trong partition?
│          ├─ KHÔNG → AP + conflict resolution
│          └─ CÓ → CP + proper failover
```

### 9.2 Pattern Phổ Biến

**1. CQRS (Command Query Responsibility Segregation)**
- Writes: Strong consistency path
- Reads: Eventually consistent replicas
- Phù hợp: Read-heavy workloads

**2. Saga Pattern**
- Distributed transactions qua local transactions + compensating actions
- Mỗi service thực hiện local transaction, publish event
- Nếu lỗi: Compensating actions để undo

**3. Read-Through/Write-Through Cache**
- Cache layer đảm bảo consistency với DB
- Không dual write từ application

### 9.3 Công Cụ & Thư Viện

| Mục đích | Công cụ |
|----------|---------|
| **Consensus** | etcd, Consul, ZooKeeper |
| **Distributed Transactions** | Saga framework (Axon, Seata), Outbox pattern |
| **CRDTs** | Akka Distributed Data, Riak DT |
| **Testing** | Jepsen, TLA+, Chaos Monkey |
| **Monitoring** | Prometheus (replication lag metrics) |

---

## 10. Kết Luận

### Bản Chất Cốt Lõi

1. **Consistency model là contract, không phải implementation.** Bạn có thể implement eventual consistency bằng nhiều cách (async replication, gossip, CRDTs), mỗi cách có trade-off riêng.

2. **CAP không phải là lựa chọn nhị phân.** Nhiều hệ thống cho phép tune consistency theo từng operation. Điều quan trọng là hiểu trade-off và chọn đúng cho use case.

3. **PACELC mở rộng CAP đúng hướng.** Phần lớn thờigian partition không xảy ra, nhưng trade-off giữa latency và consistency vẫn tồn tại.

4. **Eventual consistency không phải là "lỗi".** Nó là engineering decision chấp nhận complexity để đổi lấy scalability và availability. Quan trọng là biết handle conflicts và set đúng kỳ vọng cho users.

### Trade-off Quan Trọng Nhất

| Yêu cầu | Chọn |
|---------|------|
| Correctness > Availability | Linearizability + Consensus |
| Availability > Correctness | Eventual Consistency + CRDTs |
| Latency > Everything | Local reads, async writes |
| Global distribution | Spanner (TrueTime) hoặc accept eventual |

### Rủi Ro Lớn Nhất Trong Production

**Không phải là chọn sai consistency model** - mà là **không hiểu rõ behavior của hệ thống đang dùng**, dẫn đến:
- Expect strong consistency từ async replicated system
- Không handle conflicts trong multi-master setup
- Không monitor replication lag đến khi quá muộn

> **Nguyên tắc vàng:** Luôn document consistency guarantee của mỗi API endpoint, và test behavior trong partition scenarios.
