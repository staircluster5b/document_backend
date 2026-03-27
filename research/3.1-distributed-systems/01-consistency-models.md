# Consistency Models: Từ Lý Thuyết đến Thực Chiến Production

## 1. Mục tiêu của Task

Hiểu sâu các mô hình consistency trong hệ thống phân tán: **Linearizability**, **Serializability**, **Eventual Consistency** và các biến thể. Phân biệt đúng đắn CAP theorem, nắm vững PACELC theorem, và biết cách đánh đổi trong thiết kế database cùng caching layer.

> **Chốt lại:** Consistency không phải là "có" hay "không" - nó là một phổ các đảm bảo khác nhau, mỗi điểm trên phổ có chi phí và giới hạn riêng.

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 Linearizability - "Strongest Single-Object Consistency"

**Định nghĩa chính xác:** Mọi operation phải xuất hiện như thể nó xảy ra tại một thứ điểm duy nhất, atomically, giữa lúc bắt đầu và kết thúc của nó. Tất cả clients nhìn thấy cùng một total order của operations.

**Cơ chế thực hiện:**

```
Timeline visualization:
Client A: ----[W(x=1)]------------------->
Client B: --------[R(x)?]--------------->  
Client C: --------------[R(x)?]--------->
             ↑
        Linearization point
        (atomic, instantaneous)
```

Để đảm bảo Linearizability, hệ thống phải:
1. **Total Order Broadcast:** Mọi operation được gán một global timestamp logic (Lamport clock, vector clock, hoặc dedicated sequencer node)
2. **Read Repair/Sync:** Read phải contact majority quorum hoặc leader để đảm bảo nhận được giá trị mới nhất
3. **Write Confirmation:** Write chỉ acknowledged sau khi đã durable trên majority

**Chi phí thực tế:**
- **Latency:** Tối thiểu 1 RTT đến leader/majority (thường cross-region)
- **Throughput:** Giới hạn bởi throughput của single sequencer node
- **Availability:** Partition tolerance yêu cầu trade-off (theo CAP)

### 2.2 Serializability - "Strongest Multi-Object Consistency"

**Định nghĩa chính xác:** Kết quả thực thi của một tập concurrent transactions phải tương đương với kết quả của một execution sequential nào đó của các transactions đó.

**Phân biệt quan trọng với Linearizability:**

| Aspect | Linearizability | Serializability |
|--------|----------------|-----------------|
| **Scope** | Single object/operation | Multi-object transactions |
| **Ordering** | Real-time order matters | Total order exists, real-time không bắt buộc |
| **Context** | Distributed systems, registers | Database transactions |
| **Implication** | Mọi read thấy write mới nhất | Transactions không interfere với nhau |

**Cơ chế thực hiện:**

1. **2-Phase Locking (2PL):** 
   - Growing phase: acquire locks khi cần
   - Shrinking phase: release locks khi commit
   - **Vấn đề:** Lock contention giảm concurrency, deadlock risk

2. **Optimistic Concurrency Control (OCC):**
   - Execute locally với versioning
   - Validate tại commit time
   - Abort và retry nếu conflict
   - **Phù hợp:** Conflict ít, read-heavy workloads

3. **Serializable Snapshot Isolation (SSI):**
   - PostgreSQL's default for SERIALIZABLE
   - Track rw-dependencies giữa transactions
   - Abort transaction khi phát hiện cycle trong dependency graph

### 2.3 Eventual Consistency - "Weak but Available"

**Định nghĩa chính xác:** Nếu không có updates nào nữa cho một object, eventually tất cả reads sẽ trả về cùng một giá trị - giá trị mới nhất.

**Cơ chế hoạt động (Anti-Entropy):**

```
Node A: v1 → v2 → v3
              ↓    ↓
Node B: v1 → v2 → v3 (sau delay)
              ↑
          Gossip protocol
          hoặc Merkle tree sync
```

**Các biến thể quan trọng:**

| Biến thể | Đảm bảo | Use Case |
|----------|---------|----------|
| **Read-your-writes** | Client luôn thấy writes của chính nó | User profile updates |
| **Monotonic reads** | Không đọc lại giá trị cũ sau khi đã thấy mới | Social feed pagination |
| **Consistent prefix** | Thấy writes theo đúng thứ tự (có thể không đầy đủ) | Chat messages |
| **Bounded staleness** | Read không quá X giây cũ | CDN caching |

---

## 3. CAP Theorem - Hiểu Đúng, Không Phải Hiểu Sai

### 3.1 Định lý Đúng Đắn

**Eric Brewer, 2000:** Một hệ thống phân tán không thể đồng thợi đảm bảo cả 3:
- **C**onsistency: Mọi read nhận được giá trị mới nhất hoặc error
- **A**vailability: Mọi request nhận được response (không phải error)
- **P**artition tolerance: Hệ thống tiếp tục hoạt động dù network partition

### 3.2 Điều Quan Trọng Mà Hầu Hết Ngường Hiểu Sai

> **"Partition tolerance is NOT optional in distributed systems."**

Network partitions xảy ra không phải "có thể" mà là "chắc chắn":
- Packet loss, asymmetric routing
- GC pause kéo dài
- NIC failure, switch misconfiguration
- Cross-region latency spike

Do đó, thực tế là **CP vs AP**, không phải chọn có P hay không.

### 3.3 CAP trong Thực Chiến - Nó Phức Tạp Hơn

**Myth: "Hệ thống phải chọn CP hoặc AP"**

**Reality:**
- CAP chỉ áp dụng trong thờ gian partition
- Nhiều hệ thống cho phép **tunable consistency** per-operation
- Có thể partition dữ liệu: metadata = CP, content = AP

**Ví dụ thực tế:**
```
Amazon Dynamo: AP cho shopping cart (merge conflicts)
                 CP cho inventory (overselling là unacceptable)
                 
Cassandra: ONE, QUORUM, ALL per query

MongoDB: Primary read (CP) vs Secondary read (AP tùy cấu hình)
```

---

## 4. PACELC Theorem - CAP Thực Dụng Hơn

### 4.1 Định lý

**Daniel J. Abadi, 2010:** 

**If** there is a **P**artition:
- **E**lse how does the system trade-off between **L**atency and **C**onsistency?

Mở rộng: **PA/EL** (If Partition, choose Availability; Else, choose Latency) hoặc **PC/EC**, v.v.

### 4.2 Ý Nghĩa Thực Tế

| Type | Mô tả | Ví dụ |
|------|-------|-------|
| **PA/EL** | Partition → Available; Normal → Low Latency | Dynamo, Cassandra, Riak |
| **PC/EC** | Partition → Consistent; Normal → Consistent | Spanner, CockroachDB |
| **PA/EC** | Partition → Available; Normal → Consistent | MongoDB (default) |
| **PC/EL** | Partition → Consistent; Normal → Low Latency | Khó thực hiện, ít gặp |

**Insight quan trọng:** Ngay cả khi không có partition, bạn vẫn phải trade-off giữa latency và consistency.

---

## 5. Trade-off trong Thiết Kế

### 5.1 Database Design

**Scenario: User balance updates**

```
┌─────────────────────────────────────────────────────────┐
│  Yêu cầu: Không được oversell, nhưng accept slight      │
│  delay trong visibility của balance mới                 │
├─────────────────────────────────────────────────────────┤
│  Option A: Serializable + Synchronous Replication       │
│  - Đảm bảo: No oversell                                 │
│  - Chi phí: 100-200ms latency, limited throughput       │
│  - Phù hợp: Banking core, stock trading                 │
├─────────────────────────────────────────────────────────┤
│  Option B: Read Committed + Async Replication           │
│  - Đảm bảo: No dirty reads, eventual visibility         │
│  - Chi phí: <10ms latency, high throughput              │
│  - Phù hợp: E-commerce inventory, social counters       │
├─────────────────────────────────────────────────────────┤
│  Option C: Optimistic + Merge Conflicts                 │
│  - Đảm bảo: Availability cao, conflict rare             │
│  - Chi phí: Retry logic phức tạp, user-facing errors    │
│  - Phù hợp: Shopping cart, collaborative editing        │
└─────────────────────────────────────────────────────────┘
```

### 5.2 Caching Design

**Cache consistency strategies:**

| Strategy | Consistency | Availability | Complexity | Use Case |
|----------|-------------|--------------|------------|----------|
| **Cache-aside** | Eventual | High | Low | Read-heavy, stale OK |
| **Read-through** | Eventual | High | Medium | Uniform access patterns |
| **Write-through** | Strong | Medium | Medium | Write-heavy, read-after-write |
| **Write-behind** | Eventual | High | High | High write throughput |
| **Write-around** | Weak | High | Low | Write-heavy, rare reads |

**Cache invalidation challenge:**

```
Problem: "There are only two hard things in Computer Science: 
          cache invalidation and naming things."
          
Solution approaches:
1. TTL-based: Simple, but stale data hoặc unnecessary DB hits
2. Active invalidation: Complex, but precise
3. Version-based: Add version key, client checks before use
4. Lease-based: Rent cache entry, auto-expire nếu holder dies
```

---

## 6. Rủi Ro, Anti-Patterns, và Lỗi Thường Gặp

### 6.1 Anti-Patterns Chết Ngường

**1. "We need strong consistency everywhere"**
- Chi phí: Latency unacceptable, throughput giảm 10x
- Giải pháp: Xác định thực sự cần gì, phân vùng theo domain

**2. "Eventual consistency means immediate inconsistency"**
- Sai lầm: Nghĩ rằng eventual = always stale
- Thực tế: Staleness có bound, có thể control bằng SLAs

**3. "CAP means we must choose CP or AP at system level"**
- Sai lầm: Đóng khung toàn bộ hệ thống vào một lựa chọn
- Thực tế: Per-operation, per-data-type tuning

### 6.2 Failure Modes Production

**1. Split-Brain trong Quorum Systems**
```
Scenario: Network partition, minority partition vẫn accept writes
Result: Divergent histories, difficult/impossible to merge
Prevention: Fencing tokens, epoch numbers, strict majority requirement
```

**2. Clock Skew trong Linearizability**
```
Scenario: NTP drift, physical clock không đồng bộ
Result: Operations "travel back in time", violate causality
Solution: Logical clocks (Lamport, Vector), TrueTime (Spanner)
```

**3. Thundering Herd sau Cache Expiry**
```
Scenario: Hot key expire, 1000 clients đồng loạt query DB
Result: DB overload, cascading failure
Solution: Probabilistic early expiration, request coalescing, lease tokens
```

---

## 7. Khuyến Nghị Thực Chiến Production

### 7.1 Decision Framework

```
START: What is the consequence of inconsistency?
│
├─> Financial loss / Legal risk / Safety critical
│   └─> Serializable + Synchronous replication
│       Consider: Spanner, CockroachDB, or custom 2PC
│
├─> User confusion / Duplicate processing / Minor errors
│   └─> Read Committed + Optimistic locking
│       Consider: PostgreSQL, MySQL InnoDB
│
└─> Stale data temporarily acceptable
    └─> Eventual consistency + Conflict resolution
        Consider: Cassandra, DynamoDB, Redis
```

### 7.2 Monitoring và Observability

**Metrics cần track:**
- **Replication lag:** P50, P99 lag giữa primary và replicas
- **Conflict rate:** Số conflicts cần resolve per minute
- **Stale read ratio:** % reads trả về data cũ hơn X seconds
- **Linearizability violations:** Sử dụng Jepsen-style testing trong staging

**Alerting thresholds:**
```
CRITICAL: Replication lag > 5 seconds (có thể expose stale data)
WARNING: Conflict rate > 1% (application logic có thể có vấn đề)
INFO: Stale read ratio trending up
```

### 7.3 Testing Strategy

**1. Jepsen Testing:**
- Simulate network partitions, clock skew, node failures
- Verify consistency guarantees bằng formal verification
- **Lưu ý:** Jepsen test PASS không đồng nghĩa bug-free, chỉ là chưa tìm thấy

**2. Chaos Engineering:**
- Randomly kill nodes, introduce latency, drop packets
- Measure: Error rate, recovery time, data integrity

**3. Property-Based Testing:**
- Generate random operation sequences
- Verify invariants (e.g., balance không âm, inventory không oversell)

---

## 8. Kết Luận

### Chốt Lại Bản Chất

1. **Consistency là spectrum, không phải boolean.** Mỗi điểm trên spectrum có cost và constraint riêng.

2. **CAP không phải để chọn C hay A, mà để hiểu trade-off.** Partition tolerance là mandatory.

3. **PACELC mở rộng CAP vào thực tế.** Ngay cả khi không có partition, latency vs consistency vẫn là trade-off.

4. **Strong consistency có cost thực sự.** Latency, throughput, availability đều bị ảnh hưởng. Chỉ dùng khi thực sự cần.

5. **Eventual consistency không phải "yếu" - nó là "đúng tool cho đúng job".** Hầu hết use cases trong production đều tolerate eventual consistency.

### Câu Hỏi Quyết Định Trước Khi Thiết Kế

- Nếu user A thấy update của user B sau 1 giây, có chấp nhận được không?
- Nếu 2 users thấy 2 trạng thái khác nhau trong 500ms, hệ quả là gì?
- Chi phí của inconsistency so với chi phí của latency cao?

**Trả lời được 3 câu này, bạn sẽ chọn đúng consistency model.**

---

## 9. Tài Liệu Tham Khảo

1. **"Consistency Models"** - Murat Demirbas (blog post series)
2. **"Designing Data-Intensive Applications"** - Martin Kleppmann (Chapters 7, 8, 9)
3. **"Jepsen"** - Aphyr (aphyr.com/tags/jepsen)
4. **"CAP Twelve Years Later"** - Eric Brewer (IEEE Computer, 2012)
5. **"PACELC Theorem"** - Daniel J. Abadi (blog post, 2010)
6. **"Spanner: Google's Globally-Distributed Database"** - OSDI 2012
7. **"Dynamo: Amazon's Highly Available Key-Value Store"** - SOSP 2007