# Consensus Algorithms: Raft và Paxos

> **Mục tiêu:** Thấu hiểu bản chất consensus trong distributed systems, phân tích sâu cơ chế Paxos và Raft, so sánh các implementations (etcd, Consul, Zookeeper), và nắm vững rủi ro production.

---

## 1. Tổng quan: Bản chất Consensus Problem

### 1.1. Vấn đề cốt lõi

Consensus là bài toán đồng thuận trong hệ thống phân tán: **làm sao để nhiều node độc lập thống nhất về một giá trị**, ngay cả khi một số node fail hoặc network partition xảy ra.

> **Định nghĩa chính xác (formal):** Một protocol consensus phải thỏa mãn 3 thuộc tính:
> - **Agreement:** Mọi node quyết định cùng một giá trị
> - **Validity:** Giá trị quyết định phải được đề xuất bởi ít nhất một node (không thể "bịa" ra)
> - **Termination:** Mọi node không failed cuối cùng phải đưa ra quyết định

### 1.2. FLP Impossibility Result

**Kết quả nổi tiếng của Fischer, Lynch, Paterson (1985):**

> **Trong hệ thống asynchronous (không có upper bound về message delay), không tồn tại deterministic consensus algorithm có thể tolerate ngay cả chỉ 1 faulty process.**

**Ý nghĩa thực tế:**
- Không thể đảm bảo safety và liveness đồng thờ trong pure asynchronous system
- Mọi consensus protocol đều phải **hy sinh liveness** (có thể không terminate trong một số scenarios) hoặc **dựa vào timing assumptions** (partial synchrony)
- Đây là lý do các thuật toán consensus thực tế đều có **timeout** và **leader election** - để break symmetry bằng cách giả định partial synchrony

### 1.3. Consensus vs Byzantine Faults

| Loại Fault | Đặc điểm | Thuật toán |
|------------|----------|------------|
| **Crash-stop** | Node dừng hoạt động, không gửi message nữa | Paxos, Raft, Viewstamped Replication |
| **Crash-recovery** | Node có thể recover và rejoin | Raft với persistent log, Paxos với stable storage |
| **Byzantine** | Node có thể lie, send arbitrary messages | PBFT, Tendermint, HotStuff (blockchain) |

> **Quan trọng:** Paxos và Raft chỉ handle crash-recovery faults, **KHÔNG** chống được Byzantine faults. Đừng dùng Raft cho blockchain hay hệ thống cần chống malicious nodes.

---

## 2. Paxos: The Classic Consensus Algorithm

### 2.1. Bản chất cơ chế

Paxos được Leslie Lamport đề xuất năm 1989, trở thành nền tảng lý thuyết cho hầu hết consensus algorithms sau này.

**Vai trò trong Paxos:**
- **Proposer:** Đề xuất giá trị mới
- **Acceptor:** Vote cho giá trị, quyết định giá trị nào được chọn
- **Learner:** Biết giá trị đã được chọn (không tham gia voting)

**Quorum trong Paxos:**
- Cần majority của acceptors để chọn giá trị
- Nếu có N acceptors, cần ít nhất ⌊N/2⌋ + 1 votes
- Đảm bảo bất kỳ 2 quorums nào cũng overlap ít nhất 1 node (intersection property)

### 2.2. Paxos Two-Phase Protocol

```
Phase 1: Prepare-Promise (Quorum establishment)

Proposer                          Acceptors
   |  1. Prepare(n)  ─────────────────>|
   |          (n là proposal number mới)|
   |                                   |
   |<──────────────── Promise(n, v)    |
   |    (hứa không accept n' < n,      |
   |     trả về giá trị v cao nhất đã  |
   |     accept trước đó)              |
   |<──────────────── Promise(n, null) |
   |<──────────────── Promise(n, null) |
   |                                   |
   [Đủ majority promises -> Phase 2]   |

Phase 2: Accept-Learn (Value commitment)

Proposer                          Acceptors
   |  2. Accept(n, v) ────────────────>|
   |  (v là giá trị từ promise cao nhất|
   |   hoặc giá trị mới nếu chưa có)   |
   |                                   |
   |<──────────────── Accepted(n, v)   |
   |<──────────────── Accepted(n, v)   |
   |<──────────────── Accepted(n, v)   |
   |                                   |
   [Đủ majority accepts -> Value chosen]
   
   3. Broadcast chosen value to learners
```

**Tại sao cần 2 phases?**

- **Phase 1 (Prepare):** Đảm bảo safety - nếu một giá trị đã được chosen, proposer mới phải chọn lại giá trị đó
- **Phase 2 (Accept):** Thực sự commit giá trị
- **Quorum overlap:** Đảm bảo nếu 2 proposers cùng chạy, ít nhất 1 acceptor sẽ biết về giá trị đã chosen

### 2.3. Multi-Paxos (Log Replication)

Single-value Paxos chỉ chọn 1 giá trị. Để replicate log (nhiều entries), cần Multi-Paxos:

**Cơ chế leader-based:**
1. Một proposer được chọn làm **leader** cho 1 term (hoặc until failure)
2. Leader chạy Phase 1 một lần để establish authority cho 1 range của log entries
3. Sau đó, leader có thể skip Phase 1 và chỉ chạy Phase 2 cho từng entry

```
Leader Election trong Multi-Paxos:

Term 1: Node A là leader, phục vụ entries 1-100
        (Chạy Phase 1 cho tất cả entries này một lần)

Term 2: Node A fail, Node B phát hiện qua heartbeat timeout
        Node B chạy Phase 1 với higher term
        Nếu đủ promises, B trở thành leader mới
        
Term 3: B phục vụ entries 101-200
```

**Trade-off:** Leader-based giảm message count từ 4n (2 round-trips per entry) xuống 2n (1 round-trip sau khi có leadership), nhưng tạo bottleneck và single point of contention.

### 2.4. Paxos complexities và "The Part-time Parliament"

Lamport mô tả Paxos qua metaphor "Part-time Parliament" - các đại biểu (parliamentarians) tham dự không đều đặn, vẫn phải đồng thuận được luật.

**Vấn đề thực tế của Paxos:**
1. **Challenging to implement đúng:** Có nhiều edge cases (network partition, delayed messages, crashed proposers)
2. **Liveness issues:** Nhiều proposers cùng chạy có thể dẫn đến livelock (proposal numbers tăng mãi, không ai được chọn)
3. **No explicit leader election:** Phải implement thêm layer trên
4. **Log compaction phức tạp:** Khi log dài, cần snapshot mechanism

> **Quote từ Diego Ongaro (tác giả Raft):** "Paxos là thuật toán tuyệt vờ cho việc giảng dạy về consensus, nhưng vận hành production system dựa trên Paxos là địa ngục."

---

## 3. Raft: Consensus Designed for Understandability

### 3.1. Design Philosophy

Raft được Diego Ongaro và John Ousterhout (Stanford) thiết kế năm 2014 với mục tiêu: **"Understandability First"**.

**Raft tách biệt rõ ràng 3 sub-problems:**
1. **Leader Election:** Chọn leader mới khi cũ fail
2. **Log Replication:** Leader replicate log entries cho followers
3. **Safety:** Đảm bảo state machine safety (cùng index = cùng command)

### 3.2. Raft Node States

```
┌─────────────┐
│  Follower   │ ◄── Mặc định, passive, chỉ phản hồi RPC
└──────┬──────┘
       │ Election timeout (không nhận heartbeat)
       ▼
┌─────────────┐
│  Candidate  │ ◄── Yêu cầu votes từ peers
└──────┬──────┘
       │ Nhận majority votes
       ▼
┌─────────────┐
│   Leader    │ ◄── Gửi heartbeats, xử lý client requests,
└─────────────┘     replicate log
```

**State transitions chi tiết:**
- **Follower → Candidate:** Khi election timeout (randomized, typically 150-300ms) mà không nhận được heartbeat từ leader
- **Candidate → Leader:** Nhận được votes từ majority (including self)
- **Candidate → Follower:** Phát hiện leader khác với term cao hơn, hoặc election timeout mà không đủ votes (split vote)
- **Leader → Follower:** Phát hiện leader khác với term cao hơn (step down)

### 3.3. Leader Election Deep Dive

```
Timeline: Leader Election Process

T+0ms:    Node A (Leader, Term 1) gửi heartbeat
          Node B, C (Followers) reset election timeout

T+150ms:  Node A crash
          
T+300ms:  Node B election timeout (random 300ms)
          B chuyển → Candidate, Term=2
          B gửi RequestVote RPC đến A, C
          
T+305ms:  Node C election timeout (random 350ms)
          C chuyển → Candidate, Term=2
          C gửi RequestVote RPC đến A, B

T+310ms:  B nhận RequestVote từ C (cùng term)
          B reject vì đã vote cho chính mình
          
T+315ms:  C nhận RequestVote từ B (cùng term)
          C reject vì đã vote cho chính mình
          
T+320ms:  B nhận vote từ C? Không, C từ chối
          B chỉ có 1 vote (chính mình) - chưa đủ
          
T+600ms:  B election timeout lần nữa
          B tăng Term=3, gửi RequestVote mới
          
T+605ms:  C nhận RequestVote(Term=3) > C.currentTerm(2)
          C step down → Follower, vote cho B
          
T+610ms:  B nhận vote từ C, đủ majority
          B trở thành Leader (Term 3)
```

**Tại sao cần randomized election timeout?**

- Nếu tất cả nodes có cùng timeout, khi leader fail, tất cả đều trở thành candidates đồng thờ
- Split vote: Không ai đủ majority, lại timeout, lại split vote... (livelock)
- Randomization (e.g., 150-300ms) đảm bảo xác suất cao chỉ có 1 node timeout trước
- Sau khi 1 node trở thành leader, nó gửi heartbeats, reset timeouts của followers

**RequestVote RPC logic:**
```
Receiver (Follower/Candidate) implementation:

1. Nếu term < currentTerm: reject
2. Nếu votedFor là null hoặc candidateId, VÀ candidate's log 
   ít nhất up-to-date như receiver's log: grant vote
   
Log "up-to-date" criteria:
- So sánh lastLogTerm trước, lastLogIndex sau
- Higher lastLogTerm = more up-to-date
- Nếu bằng term, longer log = more up-to-date

Điều này đảm bảo chỉ node có đầy đủ log mới được làm leader
→ Tránh mất committed entries
```

### 3.4. Log Replication Deep Dive

```
Log Structure:

Index:    1      2      3      4      5      6
        ┌─────┬─────┬─────┬─────┬─────┬─────┐
Term:   │  1  │  1  │  1  │  2  │  2  │  2  │
        ├─────┼─────┼─────┼─────┼─────┼─────┤
Command│ set │ set │ del │ set │ set │ set │
        │ x=1 │ y=2 │ z   │ x=2 │ a=5 │ b=3 │
        └─────┴─────┴─────┴─────┴─────┴─────┘
        ▲                           ▲
     committed                 leader's last
     (apply to SM)              entry
```

**AppendEntries RPC flow:**

```
Client     Leader     Follower A    Follower B    Follower C
  │          │            │             │             │
  │─request─►│            │             │             │
  │          │            │             │             │
  │◄─ack────┤            │             │             │
  │          │            │             │             │
  │          ├──AppendEntries──────────►│             │
  │          │(prevLogIndex=5,          │             │
  │          │ prevLogTerm=2,           │             │
  │          │ entries=[set c=4],       │             │
  │          │ leaderCommit=5)          │             │
  │          │            │             │             │
  │          │◄───────────Success───────┤             │
  │          │            │             │             │
  │          │            │             │             │
  │          ├──AppendEntries─────────────────────────►│
  │          │            │             │             │
  │          │◄────────────────────────────────Success┤
  │          │            │             │             │
  │          ├──AppendEntries──────────►│             │
  │          │            │             │             │
  │          │◄───────────Success───────┤             │
  │          │            │             │             │
  │          │ [Majority (3/5) acked,   │             │
  │          │  commit index 6]         │             │
  │          │            │             │             │
  │◄─result─┤            │             │             │
```

**Log Matching Property:**

> Nếu 2 logs có cùng index và term, thì:
> 1. Chúng chứa cùng một command
> 2. Logs giống hệt nhau ở tất cả preceding entries

Raft đảm bảo điều này bằng **consistency check** trong AppendEntries:
- Mỗi AppendEntries chứa `prevLogIndex` và `prevLogTerm`
- Follower chỉ append nếu nó có entry matching tại prevLogIndex với prevLogTerm
- Nếu không match: follower reject, leader decrements nextIndex và thử lại

**Log divergence handling:**

```
Scenario: Leader change gây log divergence

Term 1: Node A (Leader) ──[x=1]──[y=2]──►
        Node B ───────────[x=1]──[y=2]──►
        Node C ───────────[x=1]─────────► (chậm hơn)

Term 2: A crash, B thành Leader
        B replicate [z=3] đến A, B (chưa đến C)
        
        Node A (dead)  [x=1][y=2]
        Node B (Leader)[x=1][y=2][z=3]
        Node C         [x=1]        [w=4] (đã nhận từ A cũ)
        
Term 3: B crash, C thành Leader (với [x=1][w=4])
        C phát hiện B có [y=2][z=3] mà C không có
        
        C gửi AppendEntries với prevLogIndex=1 (x=1)
        B reject (expecting prevLogIndex=2)
        
        C giảm nextIndex cho B, thử prevLogIndex=0 (empty)
        B accept, xóa [y=2][z=3], nhận [w=4] từ C
        
        Log cuối cùng: [x=1][w=4] ← Lost [y=2][z=3]!
```

**Vấn đề:** Entries chưa committed (chỉ replicate đến minority) có thể mất khi leader change.

**Raft solution:** Leader chỉ replicate entries từ **current term** của nó. Entries từ previous terms được replicate "indirectly" khi replicate current entries. Committed entries từ previous terms thì safe.

### 3.5. Safety Proof Sketch

Raft đảm bảo State Machine Safety: Nếu một server đã apply log entry tại index n, thì không server nào apply different entry tại cùng index.

**Chứng minh (intuition):**

1. Leader Completeness: Nếu entry được commit ở term T, nó sẽ có mặt trong log của mọi leader với term > T
   - Chứng minh: Entry commit cần majority. Leader mới cần majority votes. 2 majorities overlap → leader mới phải có committed entry

2. State Machine Safety: Follow from Leader Completeness + Log Matching
   - Một entry chỉ được apply khi committed
   - Committed entry sẽ có mặt trong mọi future leader
   - Leader chỉ replicate entries của nó, và committed entries không bị overwrite

---

## 4. So sánh Paxos vs Raft

| Aspect | Paxos | Raft |
|--------|-------|------|
| **Understandability** | Difficult, nhiều variants | Designed for clarity, tách biệt concerns |
| **Leader** | Implicit, optional optimization | Explicit, core to protocol |
| **Log replication** | Multi-Paxos phức tạp | Tích hợp sẵn, straightforward |
| **Membership changes** | Complex (Joint Consensus trong paper gốc) | Joint Consensus được mô tả rõ ràng |
| **Message complexity** | 2-4 RTTs per entry (single leader optimization) | 1 RTT per entry (leader-based) |
| **Liveness** | Có thể livelock với nhiều proposers | Randomized timeout tránh split vote |
| **Implementation** | Challenging, nhiều bug potential | Straightforward, dễ verify |
| **Performance** | Tương đương khi optimize | Tương đương, dễ optimize hơn |
| **Academic foundation** | Chặt chẽ, lâu đời | Dựa trên Paxos, thêm practical details |

**Trade-off summary:**
- **Raft thắng:** Dễ hiểu, dễ implement, dễ debug, đủ tốt cho hầu hết use cases
- **Paxos thắng:** Được nghiên cứu sâu hơn, có variants chuyên biệt (Fast Paxos, Flexible Paxos), một số systems vẫn dùng (Chubby, Spanner)

> **Khuyến nghị:** Dùng Raft cho hệ thống mới. Chỉ dùng Paxos nếu có requirements đặc biệt hoặc cần tận dụng existing implementations.

---

## 5. Production Implementations: etcd vs Consul vs Zookeeper

### 5.1. etcd (CoreOS → CNCF)

**Kiến trúc:**
- Sử dụng Raft consensus (Raft paper được implement đầu tiên trong etcd)
- Written in Go
- HTTP/gRPC API
- Key-value store với watches

**Đặc điểm:**
```
Cluster setup: 3, 5, hoặc 7 nodes (odd number để tránh split brain)
               ┌─────────┐
               │  etcd-1 │ (Leader)
               │  :2379  │
               └────┬────┘
                    │ heartbeat
        ┌───────────┼───────────┐
        │           │           │
   ┌────┴────┐ ┌────┴────┐ ┌────┴────┐
   │  etcd-2 │ │  etcd-3 │ │  etcd-4 │
   │:2379    │ │:2379    │ │:2379    │
   │Follower │ │Follower │ │Follower │
   └─────────┘ └─────────┘ └─────────┘
```

**Use cases:**
- Kubernetes backing store (cluster state, configs, secrets)
- Service discovery (CoreDNS integration)
- Distributed locking (với lease mechanism)
- Configuration management

**Trade-offs:**
- ✅ Đơn giản, dễ operate
- ✅ Raft implementation reference
- ✅ Giao tiếp qua HTTP/gRPC (dễ debug)
- ❌ Giới hạn 8GB data size (design choice vì dữ liệu phải fit trong memory)
- ❌ Write throughput bị giới hạn bởi single leader

### 5.2. Consul (HashiCorp)

**Kiến trúc:**
- Cũng sử dụng Raft
- Multi-datacenter support (Wan gossip)
- Built-in service mesh (Consul Connect)
- Health checking tích hợp

```
Datacenter A                    Datacenter B
┌─────────────────┐            ┌─────────────────┐
│  ┌───────────┐  │            │  ┌───────────┐  │
│  │ Server-1  │  │◄──Wan───►  │  │ Server-1  │  │
│  │  (Leader) │  │   Gossip   │  │  (Leader) │  │
│  └─────┬─────┘  │            │  └─────┬─────┘  │
│        │        │            │        │        │
│  ┌─────┴─────┐  │            │  ┌─────┴─────┐  │
│  │Server-2,3 │  │            │  │Server-2,3 │  │
│  └───────────┘  │            │  └───────────┘  │
│        ▲        │            │        ▲        │
│   Local Gossip  │            │   Local Gossip  │
│        ▼        │            │        ▼        │
│  ┌───────────┐  │            │  ┌───────────┐  │
│  │  Agents   │  │            │  │  Agents   │  │
│  │ (clients) │  │            │  │ (clients) │  │
│  └───────────┘  │            │  └───────────┘  │
└─────────────────┘            └─────────────────┘
```

**Đặc điểm:**
- **Server nodes:** Chạy Raft, lưu state (3-5 nodes)
- **Client agents:** Không chạy Raft, forward requests đến servers, health checks
- **Gossip protocol:** Serf library cho membership và failure detection

**Trade-offs:**
- ✅ Feature-rich (service discovery, health check, KV, mesh)
- ✅ Multi-datacenter native
- ✅ Flexible (chạy agent trên mọi node)
- ❌ Phức tạp hơn etcd
- ❌ Resource usage cao hơn
- ❌ HashiCorp BSL license (recently controversial)

### 5.3. Zookeeper (Apache)

**Kiến trúc:**
- Sử dụng **ZAB (Zookeeper Atomic Broadcast)** - variant của Paxos
- Written in Java
- Hierarchical namespace (giống filesystem)
- Sequential nodes, ephemeral nodes, watches

```
Zookeeper Ensemble

     ┌─────────────┐
     │   Client    │
     └──────┬──────┘
            │ connect to any server
            ▼
    ┌───────┴───────┐
    │   ┌─────┐     │
    │   │Leader│◄────┼───┐
    │   └──┬──┘     │   │ write requests
    │      │        │   │ (forward to leader)
    │   ┌──┴──┐     │   │
    └───┤Follow├──┬──┘   │
        │ ers   │        │
        └───────┘        │
          ▲ ▲            │
          │ └────────────┘
          │   sync (ZAB)
        read requests
```

**ZAB (Zookeeper Atomic Broadcast):**
- Similar to Multi-Paxos/ Raft nhưng có differences
- **Leader activation:** Leader phải sync với majority trước khi accept writes
- **Active messaging:** Leader gửi proposals, followers ack, leader commit
- **Total order:** Mọi change đều có zxid (64-bit: high 32-bit epoch, low 32-bit counter)

**Trade-offs:**
- ✅ Mature, battle-tested (Hadoop, Kafka, old systems)
- ✅ Rich data model (hierarchical, ephemeral, sequential)
- ✅ Java ecosystem integration
- ❌ Complex operational (ZAB khó understand hơn Raft)
- ❌ Không dùng standard consensus (Raft/Paxos)
- ❌ Scaling limitations (write phải qua leader)

### 5.4. Comparison Matrix

| Feature | etcd | Consul | Zookeeper |
|---------|------|--------|-----------|
| **Consensus** | Raft | Raft | ZAB (Paxos-like) |
| **Language** | Go | Go | Java |
| **API** | HTTP/gRPC | HTTP/DNS | Custom binary protocol |
| **Data model** | Flat KV | Hierarchical KV | Hierarchical znode |
| **Max data size** | 8MB/key, 8GB total | Limited by RAM | 1MB/node |
| **Multi-DC** | No (cần mirror) | Yes (native) | Observer nodes |
| **Service discovery** | Via integration | Native | Via Curator |
| **Health checks** | No | Native | No |
| **Mesh/Connect** | No | Yes | No |
| **K8s integration** | Native | Via CRDs | Legacy |
| **License** | Apache 2.0 | BSL/Apache | Apache 2.0 |

---

## 6. Failure Modes và Anti-patterns

### 6.1. Split Brain Scenarios

**Scenario 1: Network Partition với 5-node cluster**

```
Before partition:
┌─────┬─────┬─────┬─────┬─────┐
│  A  │  B  │  C  │  D  │  E  │
│Lead │     │     │     │     │
└─────┴─────┴─────┴─────┴─────┘

After partition:
┌─────────────┐     ┌─────────────┐
│  A  │  B  │     │  C  │  D  │ E │
│Lead │     │     │ New │     │   │
│     │     │     │Lead │     │   │
└─────────────┘     └─────────────┘
    Zone A             Zone B
   (Majority)        (Minority)

Kết quả:
- Zone A (A,B): Majority (2/5)? KHÔNG! Chỉ có 2 nodes
- Zone B (C,D,E): Majority (3/5) - C được elect làm leader

A vẫn tưởng mình là leader nhưng không thể commit writes
(needs majority ack, only has 1 follower)
Có thể stale read nếu client vẫn connect A
```

**Prevention:**
- Luôn dùng odd number of nodes (3, 5, 7)
- Implement fencing/staleness detection ở client
- etcd: `serializable` vs `linearizable` read (default linearizable)

### 6.2. Leader Election Churn

**Scenario: Flapping leader do network instability**

```
T+0:    Node A là leader
T+10ms: Network glitch, A không gửi được heartbeat đến B,C
T+15ms: B election timeout, trở thành candidate, elect làm leader
T+20ms: Network recover, A vẫn tưởng mình là leader (term cũ)
T+25ms: A nhận heartbeat từ B (higher term), step down
T+30ms: A lại trở thành candidate do không chịu được latency với B
...

Kết quả: Liên tục đổi leader, throughput = 0
```

**Mitigation:**
- Tăng heartbeat interval và election timeout
- Pre-vote phase (Raft optimization): Candidate gửi pre-vote trước, chỉ tăng term nếu có khả năng thắng
- Checkquorum: Leader phải nhận heartbeat ack từ majority

### 6.3. Log Unbounded Growth

**Vấn đề:** Log tăng dài vô hạn → disk full, memory pressure, slow restart

**Giải pháp - Log Compaction (Snapshotting):**

```
Raft Log with Snapshots:

Index:  1   2   3   4   5   6   7   8   9   10  11  12
       ┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
       │x=1│y=2│z=3│x=4│y=5│...│   │   │   │   │   │   │
       └───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
                           ▲
                    Snapshot at index 1000
                    (State: {x: 4, y: 5, ...})
                           │
       ┌───────────────────┘
       ▼
Index:  1      (Snapshot)      1001 1002 1003 1004
       ┌─────────────────────┬────┬────┬────┬────┐
       │ Snapshot (index 1000)│x=99│y=88│z=77│...│
       │ State machine state │    │    │    │    │
       └─────────────────────┴────┴────┴────┴────┘

New follower join: Nhận snapshot trước, sau đó nhận incremental updates
```

### 6.4. Anti-patterns trong Production

| Anti-pattern | Tại sao nguy hiểm | Cách khắc phục |
|--------------|-------------------|----------------|
| **Even number of nodes** | Risk split brain (2-2 partition) | Always use 3, 5, or 7 |
| **Cross-region leader** | Write latency cao (~100ms RTT) | Pin leader ở primary region |
| **Heavy writes qua consensus** | Leader bottleneck, high latency | Buffer, batch, hoặc dùng CRDTs |
| **Large values (>1MB)** | Slow replication, GC pressure | Split into chunks, use object storage |
| **Frequent membership changes** | Disrupts cluster stability | Throttle changes, pre-validate |
| **Ignoring disk latency** | Slow disk = leader timeout | SSD required, monitor fsync latency |
| **No backup strategy** | Corruption = total data loss | Automated snapshots, offsite backup |

---

## 7. Production Khuyến nghị

### 7.1. Cluster Sizing

| Use case | Nodes | Rationale |
|----------|-------|-----------|
| **Development/Testing** | 1 (non-voting) or 3 | Cost, không cần HA |
| **Production critical** | 3 | Tolerate 1 failure, cost-effective |
| **High availability** | 5 | Tolerate 2 failures, higher read throughput |
| **Global distributed** | 5-7 + observers | Quorum ở 2+ regions, observers for local reads |

### 7.2. Monitoring Essentials

**Metrics phải track:**
```yaml
Consensus Health:
  - raft.leader: Which node is leader (should be stable)
  - raft.term: Term number (spike = leader change)
  - raft.commit_index: Lag between leader and followers
  - raft.last_index: Log growth rate
  
Performance:
  - write_latency_p99: Leader consensus latency
  - fsync_latency: Disk latency (critical for Raft)
  - network.latency: RTT between peers
  
Cluster Stability:
  - leader.election.count: Spike = instability
  - follower.lag.entries: Replication lag per follower
  - proposal.failures: Failed consensus attempts
```

**Alerts:**
- Leader election trong 5 phút > 3 lần
- Replication lag > 1000 entries
- Disk usage > 80%
- fsync latency > 100ms (risk false leader failure)

### 7.3. Performance Tuning

**etcd tuning example:**
```bash
# etcd startup flags
etcd \
  --heartbeat-interval=100 \        # Default 100ms
  --election-timeout=1000 \         # Default 1000ms (10x heartbeat)
  --snapshot-count=100000 \         # Snapshot mỗi 100k writes
  --quota-backend-bytes=8589934592 \ # 8GB limit
  --max-request-bytes=1572864 \      # 1.5MB max request
  --max-txn-ops=128                  # Max operations per transaction
```

**Trade-offs:**
- **Lower heartbeat** = Faster failure detection, nhưng nhiều false positive
- **Higher snapshot-count** = Less frequent disk I/O, nhưng restart/recovery chậm hơn
- **Higher quota** = Nhiều data hơn, nhưng memory usage cao

### 7.4. Backup và Recovery

**3-2-1 Backup Rule cho Consensus Data:**
- **3** copies of data
- **2** different media types
- **1** offsite

**etcd backup:**
```bash
# Consistent snapshot
etcdctl snapshot save backup.db

# Restore (phải restore vào toàn bộ cluster)
etcdctl snapshot restore backup.db \
  --data-dir=/var/lib/etcd-new \
  --name=etcd-1 \
  --initial-cluster=etcd-1=http://host1:2380,...
```

**Lưu ý quan trọng:**
- Không thể restore 1 node rồi replicate - sẽ gây inconsistency
- Phải restore toàn bộ cluster từ cùng 1 snapshot point
- Backup phải consistent (dùng snapshot API, không copy file)

---

## 8. Kết luận

### Bản chất Consensus

Consensus là bài toán **agreement trong môi trường unreliable**. Cả Paxos và Raft đều giải quyết bằng cách:
1. **Quorum-based voting:** Majority agreement để commit
2. **Leader-based coordination:** Một leader điều phối để tối ưu throughput
3. **Safety through term/epoch:** Higher term wins để đảm bảo progress

### Trade-offs cốt lõi

| Trade-off | Paxos/Raft Choice | Alternative |
|-----------|-------------------|-------------|
| **Consistency vs Availability** | Strong consistency, sacrifice availability during partition | Eventual consistency (Dynamo, Cassandra) |
| **Latency vs Safety** | Synchronous replication, higher latency | Asynchronous replication (risk data loss) |
| **Complexity vs Understandability** | Raft optimize for clarity | Paxos optimize for theoretical elegance |
| **Throughput vs Fault-tolerance** | Leader bottleneck | Multi-leader (CRDTs, conflict resolution) |

### Khi nào dùng Consensus?

**NÊN dùng:**
- Configuration management (etcd cho K8s)
- Service discovery (Consul)
- Distributed locking (cần strong consistency)
- Coordination (leader election, barrier, queue)

**KHÔNG NÊN dùng:**
- High-throughput data store (dùng database có replication built-in)
- Cache (cần speed, không cần strong consistency)
- Metrics/Logs (dùng stream processing)
- Large blob storage (dùng object storage)

### Final Thought

> Consensus algorithms như Raft và Paxos là **building blocks**, không phải **solutions hoàn chỉnh**. Chúng giải quyết bài toán agreement, nhưng bạn vẫn phải thiết kế system của mình để handle failure modes, monitor health, và optimize performance.

Raft đã trở thành **de facto standard** cho new systems nhờ tính understandability. etcd và Consul là những implementations production-ready dựa trên Raft. Hiểu sâu bản chất giúp bạn operate, debug, và scale hệ thống consensus một cách hiệu quả.
