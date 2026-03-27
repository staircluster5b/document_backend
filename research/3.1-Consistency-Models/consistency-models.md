# Consistency Models trong Distributed Systems

> **Mục tiêu:** Hiểu sâu các mô hình consistency, bản chất trade-offs, và ứng dụng thực tiễn trong thiết kế hệ thống phân tán.

---

## 1. Tổng quan: Vì sao Consistency là vấn đề cốt lõi?

### 1.1. Bản chất vấn đề

Trong hệ thống đơn node, consistency là hiển nhiên: một write xảy ra trước read thì read phải thấy kết quả write đó. Nhưng khi dữ liệu được replicate sang nhiều node:

- **Network delay:** Tin nhắn propagation không thể vượt tốc độ ánh sáng
- **Partition:** Mạng có thể bị chia cắt giữa các node
- **Concurrency:** Nhiều client ghi đồng thồi vào các node khác nhau
- **Failure:** Node có thể crash giữa chừng replication

> **Chân lý:** Không thể đồng thờc đạt được cả 3: Consistency mạnh, Availability 100%, và Partition tolerance. Phải chấp nhận trade-off.

---

## 2. Phân loại Consistency Models

### 2.1. Linearizability (Strong Consistency)

#### Bản chất cơ chế

Linearizability là tiêu chuẩn consistency cao nhất trong distributed systems. Nó đảm bảo:

1. **Mọi operation xuất hiện như thể xảy ra tại một điểm thờ gian duy nhất** (atomic point)
2. **Thứ tự tương đương với real-time:** Nếu operation A hoàn thành trước khi B bắt đầu, thì A "happens-before" B
3. **Read phải trả về giá trị của write gần nhất theo thứ tự linearization**

```
Timeline view:

Client 1: ──[W(x=1)]──────────────────────
Client 2: ─────────────[R(x)?]────────────
Client 3: ───────────────────────[R(x)?]──
                  ↑
           Linearization point
           của W(x=1)

Kết quả: Cả C2 và C3 đều đọc được x=1
```

#### Cách triển khai

| Approach | Cơ chế | Trade-off |
|----------|--------|-----------|
| **Single Leader** | Mọi write qua leader, replicate to followers | Leader bottleneck, higher latency cross-region |
| **Consensus (Paxos/Raft)** | Quorum-based voting (W + R > N) | Expensive coordination, lower throughput |
| **Atomic Broadcast** | Total order broadcast với causal delivery | Complexity, latency proportional to network |

#### Production Concerns

**Latency penalty:**
- Trong cùng datacenter: +1-2ms round-trip
- Cross-region (US-East ↔ US-West): +60-100ms
- Global deployment: +200-400ms (đủ để user nhận ra lag)

**Throughput limitation:**
- Leader-based: Tối đa bằng throughput của single node
- Quorum-based: Write phải đợi acknowledgment từ majority

> **Anti-pattern:** Dùng Linearizability cho read-heavy workload không cần real-time consistency. Lãng phí latency và availability.

---

### 2.2. Sequential Consistency

#### Bản chất cơ chế

Sequential consistency relax real-time constraint của Linearizability. Nó chỉ yêu cầu:

1. **Mọi node nhìn thấy các operations theo cùng một global order**
2. **Order này phải tôn trọng program order của mỗi process** (các operation của cùng một client phải xuất hiện đúng thứ tự)

Khác biệt chính với Linearizability:
- Không yêu cầu alignment với real-time
- Cho phép reorder operations của các client khác nhau, miễn là mỗi client thấy cùng một order

```
Ví dụ phân biệt:

Timeline thực tế:
P1: ──[W(x=1)]───────────────
P2: ───────────[W(x=2)]──────
P3: ─────────────────[R(x)?]─

Linearizability: P3 phải đọc x=2 (vì W(x=2) hoàn thành trước R)
Sequential: P3 có thể đọc x=1 HOẶC x=2, miễn là mọi node thấy cùng order
```

#### Ứng dụng thực tế

**Shared memory multiprocessors (SMP):**
- CPU cache coherence protocols (MESI, MOESI)
- Hardware yêu cầu sequential consistency cho correctness

**Distributed databases với causal requirements:**
- Facebook's Tao (một phần)
- Các hệ thống social graph

---

### 2.3. Causal Consistency

#### Bản chất cơ chế

Causal consistency chỉ đảm bảo ordering cho các operations có quan hệ nhân-quả (causal relationship):

- **Nếu event A causally precedes B, mọi node phải thấy A trước B**
- **Các events concurrent (không có quan hệ nhân-quả) có thể được nhìn thấy theo thứ tự khác nhau**

**Định nghĩa causal relationship:**
1. **Program order:** A và B cùng process, A trước B
2. **Read-from:** B đọc giá trị A ghi
3. **Transitivity:** A causally precedes B, B causally precedes C → A causally precedes C

```
Ví dụ causal vs concurrent:

Client 1: ──[Post("Hello")]──────────────[Read feed?]──
Client 2: ───────────────────[Post("World")]───────────
                  ↑
           C1 đọc feed của C2?
           
→ "Hello" và "World" là concurrent (không biết cái nào trước)
→ C1 có thể không thấy "World" ngay lập tức
→ Nhưng nếu C1 reply "Hello", C2 phải thấy reply sau "Hello"
```

#### Vector Clocks - Cơ chế implementation

```
Mỗi node duy trì vector V[1..N]:
- V[i] = số events của node i mà node hiện tại đã biết

Rules:
1. Local increment: Trước khi ghi, V[i]++
2. Send: Gửi kèm message
3. Receive: V[j] = max(V[j], received_V[j]) cho mọi j, sau đó V[i]++

So sánh vector:
- V1 ≤ V2 nếu V1[i] ≤ V2[i] ∀i
- V1 < V2 nếu V1 ≤ V2 và V1 ≠ V2
- Concurrent nếu không V1 ≤ V2 và không V2 ≤ V1
```

**Trade-offs của Vector Clocks:**
- **Space:** O(N) với N = số nodes
- **Pruning:** Cần garbage collection cho old entries
- **Version vectors:** Optimization cho replicated objects

---

### 2.4. Eventual Consistency

#### Bản chất cơ chế

Eventual consistency là weakest form: 

> **Nếu không có updates nào mới trong đủ lâu, mọi replicas sẽ converge về cùng giá trị.**

Không có guarantee về:
- Thờ điểm read thấy write
- Thứ tự các writes
- Giá trị đọc được trong quá trình convergence

#### Conflict Resolution Strategies

| Strategy | Khi nào dùng | Hạn chế |
|----------|--------------|---------|
| **Last-Write-Wins (LWW)** | Simple key-value, timestamp đáng tin cậy | Data loss khi clock skew |
| **Vector Clocks + Merge** | Document stores (CouchDB, Riak) | Complex merge logic |
| **CRDTs** | Collaborative editing, counters | Limited data types |
| **Application-level resolution** | Business logic quan trọng | Developer burden |

#### CRDTs (Conflict-free Replicated Data Types)

**Bản chất:** Cấu trúc dữ liệu được thiết kế để merge tự động không cần coordination.

```
Ví dụ: G-Counter (Grow-only Counter)

Mỗi node duy trì vector count[N]:
- Increment: tăng count[i] của mình
- Merge: lấy max của từng component
- Value: tổng tất cả components

Properties:
- Commutative: A merge B = B merge A
- Associative: (A merge B) merge C = A merge (B merge C)
- Idempotent: A merge A = A
```

**Loại CRDTs:**
- **State-based:** G-Counter, PN-Counter, G-Set, 2P-Set, LWW-Register
- **Operation-based:** Cần reliable broadcast, nhưng state nhỏ hơn

---

## 3. CAP Theorem

### 3.1. Bản chất và chứng minh

**CAP Theorem (Brewer, 2000; Gilbert & Lynch, 2002):**

> Trong hệ thống distributed data store, không thể đồng thờc đảm bảo cả 3:
> - **C**onsistency: Mọi read nhận được write gần nhất hoặc error
> - **A**vailability: Mọi request nhận được non-error response (không guarantee chứa latest write)
> - **P**artition tolerance: Hệ thống tiếp tục hoạt động dù network partition xảy ra

**Chứng minh ngắn:**

```
Giả sử network partition chia cluster thành 2 phần: G1 và G2

1. Nếu write xảy ra ở G1, và ngay sau đó read từ G2:
   - Để đảm bảo Consistency: phải block read hoặc return error → mất Availability
   - Để đảm bảo Availability: phải return giá trị stale → mất Consistency
   
2. Do đó, với P (partition) xảy ra, chỉ có thể chọn C hoặc A
```

### 3.2. Thực tế: CAP không phải binary

**Hiểu nhầm phổ biến:** "Chọn CP hoặc AP" như switch on/off.

**Thực tế:**
- CAP chỉ áp dụng khi partition xảy ra (rare event)
- Hệ thống có thể hybrid: AP by default, degrade to CP khi cần
- Consistency spectrum: không chỉ có "strong" hay "eventual"

```
┌─────────────────────────────────────────────────────────┐
│           CONSISTENCY ─────────────────────►            │
│   Eventual ─┬─ Causal ─┬─ Sequential ─┬─ Linearizable   │
│      ▲      │    ▲     │      ▲       │       ▲        │
│      │      │    │     │      │       │       │        │
│   Dynamo  MongoDB   COPS      ZooKeeper   Spanner      │
│   Cassandra (tune)  Bayou     etcd        (TrueTime)   │
└─────────────────────────────────────────────────────────┘
```

---

## 4. PACELC Theorem

### 4.1. Mở rộng CAP

**PACELC (Abadi, 2010):** Mở rộng CAP để mô tả trade-offs cả khi **không** có partition.

> **Nếu có Partition (P), phải chọn giữa Availability (A) và Consistency (C)**
> **Else (E), khi chạy bình thường, phải chọn giữa Latency (L) và Consistency (C)**

### 4.2. Phân loại hệ thống theo PACELC

| Type | Hệ thống | Đặc điểm |
|------|----------|----------|
| **PA/EL** | Dynamo, Cassandra, Riak | AP khi partition, chấp nhận latency thấp hơn consistency khi normal |
| **PA/EC** | MongoDB (default), BigTable, HBase | AP khi partition, nhưng consistency khi normal (đợi replication) |
| **PC/EL** | Không phổ biến | CP khi partition, nhưng latency trước consistency khi normal |
| **PC/EC** | Spanner, CockroachDB, etcd, ZooKeeper | Luôn consistency-first, chấp nhận latency penalty |

**Ví dụ cụ thể:**

```
MongoDB (PA/EC):
- Default write concern: w=1 (write to primary only)
- Read preference: primary (consistent reads)
- → EL khi normal (fast but potentially stale if read from secondary)
- Nhưng có thể tune thành EC với w="majority", readPreference="primary"

Spanner (PC/EC):
- TrueTime API để đảm bảo external consistency
- Read/write transactions dùng two-phase commit
- Luôn consistency, latency bị ảnh hưởng bởi clock synchronization
```

---

## 5. Trade-offs và Decision Matrix

### 5.1. Khi nào chọn mô hình nào?

```
┌─────────────────────────────────────────────────────────────────┐
│                    WORKLOAD CHARACTERISTICS                     │
├──────────────────┬─────────────────┬────────────────────────────┤
│   Consistency    │   Latency Req   │   Use Case                 │
├──────────────────┼─────────────────┼────────────────────────────┤
│ Linearizability  │   Acceptable    │ Banking, Inventory, Leader │
│                  │   10-100ms+     │ election, Locks            │
├──────────────────┼─────────────────┼────────────────────────────┤
│ Sequential       │   Moderate      │ Social graph, comments     │
│                  │   5-20ms        │ (within user session)      │
├──────────────────┼─────────────────┼────────────────────────────┤
│ Causal           │   Low-moderate  │ Chat, collaboration,       │
│                  │   1-10ms        │ shopping cart              │
├──────────────────┼─────────────────┼────────────────────────────┤
│ Eventual         │   Critical      │ Analytics, recommendations,│
│                  │   sub-ms        │ counters, cache            │
└──────────────────┴─────────────────┴────────────────────────────┘
```

### 5.2. Anti-patterns và Pitfalls

**1. Premature Linearizability:**
```
❌ Lỗi: Dùng Spanner cho user preferences (rarely change, tolerate stale)
✓ Đúng: Spanner cho payment processing, Redis cho session cache
```

**2. Inconsistent failure handling:**
```
❌ Lỗi: Hệ thống chọn AP nhưng client expect strong consistency
→ Silent data loss, split-brain writes

✓ Đúng: Explicit contract, version vectors, conflict detection
```

**3. Clock skew blindness:**
```
❌ Lỗi: Dùng system clock cho LWW mà không có NTP sync
→ Updates bị "lost" do clock của node A chậm hơn B

✓ Đúng: Logical clocks, TrueTime (Spanner), hoặc vector clocks
```

**4. Ignoring conflict window:**
```
❌ Lỗi: "Eventual consistency trong 100ms" nhưng không handle conflicts
→ Race conditions, last-write-wins data loss

✓ Đúng: CRDTs, application merge, hoặc operational transform
```

---

## 6. Production Implementation Guide

### 6.1. Monitoring và Observability

**Metrics cần track:**
- **Replication lag:** Time giữa write ở primary và apply ở replica
- **Read-your-writes consistency:** Tỷ lệ reads sau write thấy đúng giá trị
- **Monotonic reads:** Tỷ lệ reads tiến triển đúng hướng
- **Conflict rate:** Số conflicts cần resolve mỗi giây

### 6.2. Testing Consistency

**Jepsen testing:**
```
Framework để verify consistency claims của databases:
1. Generate operations (read/write/cas)
2. Inject failures (partition, delay, node kill)
3. Check history against consistency model
4. Report violations

Các hệ thống đã được Jepsen test:
- etcd, Consul, MongoDB, CockroachDB, TiDB
```

**Chaos engineering:**
- Regular partition simulation
- Measure recovery time và data divergence

### 6.3. Hybrid Architectures

```
┌────────────────────────────────────────────────────────────┐
│                    HYBRID SYSTEM EXAMPLE                   │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  ┌──────────────┐        ┌──────────────┐                 │
│  │   Spanner    │        │   Redis      │                 │
│  │  (Payments)  │        │  (Sessions)  │                 │
│  │   PC/EC      │        │   PA/EL      │                 │
│  └──────────────┘        └──────────────┘                 │
│         │                       │                         │
│         └───────────┬───────────┘                         │
│                     │                                      │
│            ┌────────▼────────┐                            │
│            │  Kafka/Kinesis  │  ← Event sourcing          │
│            │   (Causal log)  │                            │
│            └────────┬────────┘                            │
│                     │                                      │
│         ┌───────────┼───────────┐                         │
│         │           │           │                         │
│  ┌──────▼────┐ ┌────▼────┐ ┌────▼────┐                    │
│  │ Analytics │ │ Search  │ │  Cache  │                    │
│  │  (Event.) │ │ (Event.)│ │ (Event.)│                    │
│  └───────────┘ └─────────┘ └─────────┘                    │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

---

## 7. Kết luận

### Bản chất cần nhớ

1. **Consistency là spectrum, không phải binary:** Từ Eventual đến Linearizability, mỗi level có use case riêng.

2. **CAP là về trade-off khi failure, không phải thiết kế thường ngày:** Hầu hết thờ gian hệ thống không partition.

3. **PACELC mô tả trade-off thực tế hơn:** Latency vs Consistency trong điều kiện bình thường quan trọng hơn CAP.

4. **Clock là enemy:** Distributed systems không có global clock. Logical clocks (vector clocks, Lamport timestamps) quan trọng hơn system time.

5. **Consistency model phải match business requirements:** Không phải mọi thứ đều cần strong consistency.

### Quyết định thiết kế

```
┌────────────────────────────────────────────────────────────────┐
│                     DECISION FLOWCHART                         │
│                                                                │
│  Cần xử lý financial transactions?                             │
│         │                                                      │
│    YES ─┴─► Linearizability (Spanner, CockroachDB, etcd)       │
│         NO                                                     │
│         │                                                      │
│  Cần real-time collaboration?                                  │
│         │                                                      │
│    YES ─┴─► Causal Consistency (CRDTs, operational transform)  │
│         NO                                                     │
│         │                                                      │
│  Read-heavy, tolerate stale?                                   │
│         │                                                      │
│    YES ─┴─► Eventual Consistency (Cassandra, DynamoDB)         │
│         NO                                                     │
│         │                                                      │
│  Hybrid approach: Strong cho writes, eventual cho reads        │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

> **Final thought:** Hiểu được trade-offs và chọn đúng consistency model cho từng use case là dấu hiệu của Senior Backend Engineer. Không phải lúc nào cũng cần "strongest" consistency - mà là "right" consistency.
