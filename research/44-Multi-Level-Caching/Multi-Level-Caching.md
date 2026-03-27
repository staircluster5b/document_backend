# Multi-Level Caching: Hierarchy, CDN, Edge Caching & Invalidation Strategies

## 1. Mục tiêu của Task

Hiểu sâu kiến trúc caching đa tầng trong hệ thống production hiện đại, tập trung vào:
- **L1/L2/L3 Cache Hierarchy** - Kiến trúc phân tầng từ CPU cache đến distributed cache
- **CDN Caching** - Cơ chế cache tại edge locations, content delivery optimization
- **Edge Caching** - Caching tại network edge, giảm latency cho ngườ dùng toàn cầu
- **Cache Invalidation Strategies** - Các chiến lược đồng bộ dữ liệu giữa các tầng cache, giải quyết vấn đề cache coherence

> **Bối cảnh quan trọng:** Multi-level caching không chỉ là "thêm nhiều lớp cache". Đó là hệ thống phức tạp đòi hỏi hiểu rõ về consistency models, invalidation patterns, và trade-off giữa latency, hit rate, và operational complexity.

---

## 2. Bản chất và Cơ chế Hoạt động

### 2.1 Cache Hierarchy Architecture

#### Tổng quan kiến trúc đa tầng

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        CACHE HIERARCHY PYRAMID                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│    ┌─────────────┐                                                           │
│    │   L1 CPU    │  < 1ns    ▲ Speed                                        │
│    │   Cache     │           │                                              │
│    ├─────────────┤           │                                              │
│    │   L2 CPU    │  ~4ns     │                                              │
│    │   Cache     │           │                                              │
│    ├─────────────┤           │                                              │
│    │   L3 CPU    │  ~10ns    │                                              │
│    │   Cache     │           │                                              │
│    ├─────────────┤           │                                              │
│    │  In-Memory  │  ~100ns   │                                              │
│    │  (Caffeine) │           │                                              │
│    ├─────────────┤           │                                              │
│    │  Local JVM  │  ~1μs     │                                              │
│    │  (EhCache)  │           │                                              │
│    ├─────────────┤           │                                              │
│    │  Distributed│  ~1ms     │                                              │
│    │  (Redis)    │           ▼                                              │
│    ├─────────────┤                                                          │
│    │   CDN Edge  │  ~10ms    ▼ Latency                                      │
│    │   (CloudFlare)                                                         │
│    ├─────────────┤                                                          │
│    │   Origin    │  ~100ms   ▼                                              │
│    │   Server    │                                                          │
│    └─────────────┘              ▼ Cost per GB                               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Bản chất của từng tầng

| Tầng | Đặc điểm | Trade-off chính | Use case |
|------|----------|-----------------|----------|
| **L1/L2/L3 CPU** | Hardware-managed, cache line (64B), coherence protocol | Tốc độ vs kích thước | Hot data trong application logic |
| **In-Memory (L1 App)** | Application-local, process-bound, ultra-low latency | Hit rate vs memory usage | Entity lookups, computed values |
| **Local JVM (L2 App)** | On-heap/off-heap, GC impact, serialized | Flexibility vs GC pressure | Large objects, cross-request caching |
| **Distributed (L3)** | Network round-trip, consistency protocols | Scalability vs latency | Shared state, cross-instance data |
| **CDN Edge** | Geographic distribution, TTL-based | Global latency vs consistency | Static assets, API responses |
| **Origin** | Source of truth, durability | Consistency vs throughput | Database, primary storage |

### 2.2 Caffeine Cache - L1 Application Cache

#### Kiến trúc nội bộ

```
┌─────────────────────────────────────────────────────────────────┐
│                    Caffeine Cache Architecture                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────────┐    ┌─────────────────┐    ┌───────────────┐│
│  │   Read Buffer   │    │  Write Buffer   │    │  Timer Wheel  ││
│  │  (Ring Buffer)  │    │  (MPSC Queue)   │    │ (Expiration)  ││
│  │   Lock-free     │    │   Lock-free     │    │  O(1) remove  ││
│  └────────┬────────┘    └────────┬────────┘    └───────┬───────┘│
│           │                      │                      │       │
│           └──────────────────────┼──────────────────────┘       │
│                                  ▼                              │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │              ConcurrentHashMap (Segmented)                   ││
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   ││
│  │  │ Segment 0│  │ Segment 1│  │ Segment 2│  │ Segment N│   ││
│  │  │ [Entry]  │  │ [Entry]  │  │ [Entry]  │  │ [Entry]  │   ││
│  │  │ [Entry]  │  │ [Entry]  │  │ [Entry]  │  │ [Entry]  │   ││
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘   ││
│  └─────────────────────────────────────────────────────────────┘│
│                                  │                              │
│                                  ▼                              │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │              Window TinyLFU (Eviction Policy)                ││
│  │                                                              ││
│  │   ┌──────────────┐      ┌──────────────────────────┐       ││
│  │   │   Window     │      │         Main             │       ││
│  │   │   (1%)       │─────►│      (99%)               │       ││
│  │   │  LinkedDeque │      │  ┌────────┐  ┌────────┐  │       ││
│  │   │  (FIFO)      │      │  │Probation│  │Protected│  │       ││
│  │   └──────────────┘      │  │ (0%)   │  │  (80%)  │  │       ││
│  │                         │  └────────┘  └────────┘  │       ││
│  │                         │         Segmented LRU    │       ││
│  │                         └──────────────────────────┘       ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### Window TinyLFU - Cơ chế eviction thông minh

**Vấn đề với LRU truyền thống:**
- **Scan resistance kém:** Sequential scan đẩy hết hot data ra khỏi cache
- **Bursty traffic:** Traffic spike ngắn hạn làm polluted cache

**TinyLFU giải quyết bằng frequency sketch:**

```
Count-Min Sketch (4-bit counter matrix):
┌─────────────────────────────────────────────────┐
│  Hash 0 │  3  │  0  │  5  │  1  │  7  │  2  │  │
│  Hash 1 │  1  │  4  │  2  │  6  │  0  │  3  │  │
│  Hash 2 │  5  │  2  │  1  │  3  │  4  │  0  │  │
│  Hash 3 │  2  │  7  │  0  │  1  │  3  │  5  │  │
└─────────────────────────────────────────────────┘
                    ▲
                    │
              Index = hash(key) % width

Ước lượng frequency = min(counter[hash_i])
```

**Window (1%) + Main (99%) phân chia:**
- **Window:** Cho phép new entries có cơ hội prove themselves
- **Probation (trong Main):** Candidate bị đuổi ra, so sánh frequency với victim
- **Protected (trong Main):** Đã chứng minh value, khó bị đuổi hơn

> **Bản chất:** TinyLFU chấp nhận approximation (count-min sketch) để đạt O(1) memory overhead (~1% của cache size), trade-off giữa accuracy và resource usage.

### 2.3 CDN Caching Architecture

#### Kiến trúc phân phối toàn cầu

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      GLOBAL CDN ARCHITECTURE                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│                           ┌─────────────────┐                               │
│                           │   Origin Server  │                               │
│                           │  (Your Backend)  │                               │
│                           └────────┬────────┘                               │
│                                    │                                        │
│                    ┌───────────────┼───────────────┐                        │
│                    │               │               │                        │
│              ┌─────▼─────┐   ┌─────▼─────┐   ┌─────▼─────┐                 │
│              │   Tier 1  │   │   Tier 1  │   │   Tier 1  │                 │
│              │   (Tier)  │   │   (Tier)  │   │   (Tier)  │                 │
│              │  SJC/LAX  │   │  FRA/AMS  │   │  SIN/HKG  │                 │
│              └─────┬─────┘   └─────┬─────┘   └─────┬─────┘                 │
│                    │               │               │                        │
│         ┌─────────┼─────────┐     │     ┌─────────┼─────────┐              │
│         │         │         │     │     │         │         │              │
│    ┌────▼───┐ ┌───▼───┐ ┌───▼───┐ │ ┌───▼───┐ ┌───▼───┐ ┌───▼───┐         │
│    │ Edge   │ │ Edge  │ │ Edge  │ │ │ Edge  │ │ Edge  │ │ Edge  │         │
│    │ SEA    │ │ DEN   │ │ ORD   │ │ │ LHR   │ │ MUC   │ │ NRT   │         │
│    └────────┘ └───────┘ └───────┘ │ └───────┘ └───────┘ └───────┘         │
│                                   │                                        │
│                              ┌────▼────┐                                   │
│                              │  Tier 2 │                                   │
│                              │  (Tier) │                                   │
│                              │  BOM/DXB│                                   │
│                              └────┬────┘                                   │
│                                   │                                        │
│                              ┌────▼────┐                                   │
│                              │  Edge   │                                   │
│                              │  Tier 3 │                                   │
│                              │  Local  │                                   │
│                              └─────────┘                                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Cơ chế Cache Key & Vary Headers

**Cache Key Composition (CloudFlare/AWS CloudFront):**

```
Full URL: https://api.example.com/v1/users/123?expand=profile&format=json

Cache Key Components:
┌─────────────────────────────────────────────────────────────────┐
│  Scheme:    https                                               │
│  Host:      api.example.com                                     │
│  URI:       /v1/users/123                                       │
│  Query:     expand=profile&format=json                          │
│  Headers:   Accept-Language (if Vary)                           │
│             Authorization (if included)                         │
│             Cookie (if included) ⚠️ DANGER                      │
└─────────────────────────────────────────────────────────────────┘

Normalized Key: https|api.example.com|/v1/users/123|expand=profile,format=json
```

**Vary Header - Content Negotiation:**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: public, max-age=3600
Vary: Accept-Encoding, Accept-Language

// Tạo ra multiple cache variants cho cùng URL:
// Variant 1: gzip + en
// Variant 2: gzip + vi  
// Variant 3: br + en
// Variant 4: br + vi
```

> **Rủi ro production:** Vary header với nhiều giá trị có thể tạo ra cache key explosion. Ví dụ: Vary: User-Agent với 1000+ variants = 1000x cache size.

### 2.4 Edge Caching & Edge Computing

#### Kiến trúc Edge Computing (CloudFlare Workers / Lambda@Edge)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         EDGE COMPUTING LIFECYCLE                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Client Request                                                              │
│       │                                                                      │
│       ▼                                                                      │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                         Edge Location (PoP)                          │   │
│  │  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐  │   │
│  │  │   CDN Cache     │    │  Edge Worker    │    │  KV/Cache API   │  │   │
│  │  │   (Static)      │◄──►│  (Compute)      │◄──►│  (Dynamic)      │  │   │
│  │  │                 │    │                 │    │                 │  │   │
│  │  │ • Images        │    │ • Auth check    │    │ • Session state │  │   │
│  │  │ • CSS/JS        │    │ • A/B testing   │    │ • Rate limit    │  │   │
│  │  │ • HTML pages    │    │ • Personalize   │    │ • Config        │  │   │
│  │  └─────────────────┘    └─────────────────┘    └─────────────────┘  │   │
│  │           │                      │                      │           │   │
│  │           └──────────────────────┼──────────────────────┘           │   │
│  │                                  ▼                                  │   │
│  │  ┌─────────────────────────────────────────────────────────────────┐│   │
│  │  │              Cache Reserve / Tiered Cache                        ││   │
│  │  │         (Persistent cache across PoPs)                           ││   │
│  │  └─────────────────────────────────────────────────────────────────┘│   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                        │
│       Cache MISS ──────────────────┼─────────────────► Origin Fetch         │
│                                    │                                        │
│  ┌─────────────────────────────────▼─────────────────────────────────────┐  │
│  │                         Origin Server                                  │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Latency Comparison:**

| Operation | Typical Latency | Use Case |
|-----------|-----------------|----------|
| Edge Cache Hit | 10-50ms | Static assets, cached API |
| Edge Worker + KV | 50-200ms | Auth, personalization |
| Worker fetching Origin | 200-500ms | Dynamic content |
| Direct Origin | 100-1000ms | Uncached dynamic data |

---

## 3. Cache Invalidation Strategies

### 3.1 The Hard Problem of Cache Invalidation

> "There are only two hard things in Computer Science: cache invalidation and naming things." - Phil Karlton

**Tại sao cache invalidation khó:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CACHE INVALIDATION COMPLEXITY                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. DISTRIBUTION: Nhiều cache instances, nhiều levels                        │
│     ├── L1: Application cache (Caffeine) - 10 instances                      │
│     ├── L2: Distributed cache (Redis) - 6 nodes cluster                      │
│     ├── L3: CDN cache - 200+ edge locations                                  │
│     └── L4: Browser cache - Millions of clients                              │
│                                                                              │
│  2. TIMING: Race conditions giữa invalidation và updates                     │
│                                                                              │
│     T1: Read request ──► Cache miss ──► Fetch from DB (value = A)           │
│     T2: Update DB (value = B)                                                │
│     T3: Invalidate cache                                                     │
│     T4: Read request ──► Cache miss ──► Fetch from DB (value = B) ✓         │
│     T5: T1 writes stale value A to cache ⚠️ RACE CONDITION                   │
│                                                                              │
│  3. CONSISTENCY: CAP theorem trade-offs                                      │
│     ├── Strong consistency: Synchronous invalidation → High latency          │
│     └── Eventual consistency: Async invalidation → Stale reads               │
│                                                                              │
│  4. CASCADE: Invalidation một entry trigger nhiều related entries            │
│     ├── User profile update → Invalidate user cache                          │
│     ├── User profile update → Invalidate all user's posts cache              │
│     ├── User profile update → Invalidate search index cache                  │
│     └── User profile update → Invalidate recommendation cache                │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Cache Invalidation Patterns

#### Pattern 1: TTL-Based (Time To Live)

```java
// Caffeine với TTL
Caffeine.newBuilder()
    .expireAfterWrite(5, TimeUnit.MINUTES)  // Write → Expire sau 5 phút
    .expireAfterAccess(1, TimeUnit.MINUTES) // Read → Reset access timer
    .refreshAfterWrite(1, TimeUnit.MINUTES) // Async refresh trước khi expire
    .build();
```

**Trade-offs:**

| Ưu điểm | Nhược điểm |
|---------|------------|
| Đơn giản, không cần coordination | Stale data trong TTL window |
| No network overhead | Không phù hợp data thay đổi liên tục |
| Automatic cleanup | Hard to tune TTL cho mọi use case |

#### Pattern 2: Write-Through + Immediate Invalidation

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    WRITE-THROUGH INVALIDATION                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Client ──► Update DB ──► Commit Transaction                                │
│                              │                                              │
│                              ▼                                              │
│                    Invalidate Cache (Synchronous)                           │
│                              │                                              │
│              ┌───────────────┼───────────────┐                              │
│              ▼               ▼               ▼                              │
│        ┌─────────┐    ┌─────────┐    ┌─────────┐                           │
│        │   L1    │    │   L2    │    │   L3    │                           │
│        │ Caffeine│    │  Redis  │    │   CDN   │                           │
│        └─────────┘    └─────────┘    └─────────┘                           │
│                                                                              │
│  ✓ Đảm bảo consistency sau khi transaction commit                           │
│  ✗ Tăng write latency (phải đợi invalidation hoàn thành)                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Implementation với Spring Cache + Redis Pub/Sub:**

```java
@Service
public class UserService {
    
    @Cacheable(value = "users", key = "#id")
    public User getUser(Long id) {
        return userRepository.findById(id).orElseThrow();
    }
    
    @Caching(
        put = @CachePut(value = "users", key = "#user.id"),
        evict = @CacheEvict(value = "user-list", allEntries = true)
    )
    @Transactional
    public User updateUser(User user) {
        User saved = userRepository.save(user);
        // Publish invalidate event cho other instances
        redisTemplate.convertAndSend("cache:invalidate", 
            new CacheInvalidateEvent("users", user.getId()));
        return saved;
    }
}
```

#### Pattern 3: Cache-Aside + Background Refresh

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CACHE-ASIDE WITH BACKGROUND REFRESH                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Read Path:                                                                  │
│  ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐                   │
│  │ Client  │───►│  Cache  │───►│  Hit?   │───►│ Return  │                   │
│  └─────────┘    └─────────┘    └────┬────┘    └─────────┘                   │
│                                     │ Miss                                  │
│                                     ▼                                       │
│                              ┌─────────┐                                    │
│                              │   DB    │                                    │
│                              └────┬────┘                                    │
│                                   │                                         │
│                                   ▼                                         │
│                              ┌─────────┐                                    │
│                              │ Populate│                                    │
│                              │  Cache  │                                    │
│                              └─────────┘                                    │
│                                                                              │
│  Background Refresh (Caffeine):                                             │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │  refreshAfterWrite(5, TimeUnit.MINUTES)                             │    │
│  │                                                                     │    │
│  │  T+0:   Write value A to cache                                      │    │
│  │  T+4:   Read request → Return A (stale) → Trigger async refresh     │    │
│  │  T+4.1: Fetch B from DB → Update cache                              │    │
│  │  T+5:   TTL expires → N/A (đã refresh)                              │    │
│  │                                                                     │    │
│  │  ✓ Không block read path                                            │    │
│  │  ✓ Giảm thundering herd (chỉ 1 thread refresh)                      │    │
│  │  ✗ Brief stale read window                                          │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Pattern 4: Event-Driven Invalidation (CDC - Change Data Capture)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CDC-BASED CACHE INVALIDATION                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────┐         ┌─────────────┐         ┌─────────────────────────┐   │
│  │   DB    │────────►│  Debezium   │────────►│    Kafka Topic          │   │
│  │ (MySQL) │  Binlog │  (CDC)      │  Events │    (db.users.changes)   │   │
│  └─────────┘         └─────────────┘         └─────────────────────────┘   │
│                                                          │                  │
│                              ┌───────────────────────────┼───────────┐     │
│                              │                           │           │     │
│                              ▼                           ▼           ▼     │
│  ┌─────────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │
│  │  Cache Inv.     │  │  Search     │  │  Analytics  │  │  Other      │   │
│  │  Service        │  │  Indexer    │  │  Pipeline   │  │  Services   │   │
│  │                 │  │             │  │             │  │             │   │
│  │ • Invalidate L1 │  │ • Update ES │  │ • Stream    │  │ • Notify    │   │
│  │ • Invalidate L2 │  │   index     │  │   to DW     │  │   clients   │   │
│  │ • Purge CDN     │  │             │  │             │  │             │   │
│  └─────────────────┘  └─────────────┘  └─────────────┘  └─────────────┘   │
│                                                                              │
│  ✓ Loose coupling giữa write path và cache                                  │
│  ✓ Multiple consumers từ một event stream                                   │
│  ✗ Operational complexity (Kafka + Debezium)                                │
│  ✗ Eventual consistency (lag giữa DB write và invalidation)                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.3 Cache Coherence Protocols

#### Local Cache Coherence trong Multi-Instance Application

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CACHE COHERENCE STRATEGIES                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Strategy 1: TTL-Only (Eventual Consistency)                                │
│  ─────────────────────────────────────────────                              │
│                                                                              │
│  Instance A: [user:123 = "Alice"] ──TTL=60s──► Expire                       │
│  Instance B: [user:123 = "Alice"] ──TTL=60s──► Expire                       │
│                                                                              │
│  Update: Alice → Bob (Instance A updates DB)                                │
│  Instance A: [user:123 = "Bob"] (fresh write)                               │
│  Instance B: [user:123 = "Alice"] (stale until TTL expires) ⚠️              │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  Strategy 2: Redis Pub/Sub (Synchronous Coordination)                       │
│  ─────────────────────────────────────────────                              │
│                                                                              │
│  Instance A: Write DB → Invalidate Local Cache                              │
│              │                                                              │
│              ▼                                                              │
│         PUBLISH cache:invalidate:user:123                                   │
│              │                                                              │
│              ├───► Instance B: Receive → Invalidate Local Cache             │
│              ├───► Instance C: Receive → Invalidate Local Cache             │
│              └───► Instance D: Receive → Invalidate Local Cache             │
│                                                                              │
│  ✓ Strong consistency sau khi pub/sub deliver                               │
│  ✗ Network overhead, potential message loss                                 │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  Strategy 3: Cache-Aside with Version Stamps (Optimistic)                   │
│  ─────────────────────────────────────────────                              │
│                                                                              │
│  Cache Entry: { value: User, version: 42, timestamp: 1234567890 }           │
│                                                                              │
│  Read:                                                                       │
│    1. Read from local cache                                                 │
│    2. If timestamp older than threshold → Async validate với Redis          │
│    3. If version mismatch → Invalidate and refetch                          │
│                                                                              │
│  ✓ Read path không block                                                    │
│  ✓ Gradual consistency repair                                               │
│  ✗ Additional storage for metadata                                          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. So sánh các Cache Invalidation Strategies

| Strategy | Consistency | Latency Impact | Complexity | Best For |
|----------|-------------|----------------|------------|----------|
| **TTL-Only** | Eventual | None | Low | Static data, low change frequency |
| **Write-Through** | Strong | High (write) | Medium | Critical data, small clusters |
| **Cache-Aside + Refresh** | Eventual | Low | Low | Read-heavy, tolerate stale |
| **CDC/Event-Driven** | Eventual | Low | High | Microservices, audit requirements |
| **Version Stamps** | Eventual→Strong | Very Low | Medium | Balance consistency/performance |

---

## 5. Rủi ro, Anti-patterns, Lỗi thường gặp

### 5.1 Cache Stampede (Thundering Herd)

**Vấn đề:** Nhiều requests cùng cache miss đồng thồi, đều fetch từ origin.

```
T+0: Cache expires
T+1: 1000 requests arrive → All cache miss
T+2: 1000 DB queries executed simultaneously
T+3: DB overload, response time spikes
```

**Giải pháp:**
- **Probabilistic Early Expiration:** X% chance refresh before TTL
- **Lock/Lease:** Chỉ 1 thread được phép fetch
- **External Refresh:** Background worker refresh, không để user trigger

```java
// Caffeine: refreshAfterWrite giải quyết tự động
Caffeine.newBuilder()
    .refreshAfterWrite(4, TimeUnit.MINUTES)  // Start refresh at 4min
    .expireAfterWrite(5, TimeUnit.MINUTES)   // Hard expire at 5min
    .build(key -> fetchFromDatabase(key));   // Async refresh function
```

### 5.2 Cache Key Explosion

**Vary header abuse:**
```http
Vary: User-Agent  ← Có 1000+ variants
→ Cache size tăng 1000x
→ Hit rate giảm đáng kể
```

**Query parameter abuse:**
```
/track?event=click&id=123&timestamp=1699999999
/track?event=click&id=123&timestamp=1700000000
→ Mỗi timestamp = cache entry mới (VÔ HẠN)
```

### 5.3 Inconsistent Cache State

**Race condition trong cache-aside:**
```
Thread A: Read cache → Miss → Fetch DB (value = old)
Thread B: Update DB (value = new) → Invalidate cache
Thread A: Write old value to cache ← STALE DATA PERSISTED
```

**Giải pháp:**
- **Cache-Aside with Lease:** Set lease token, invalidate kiểm tra token
- **Write-Through:** Đảm bảo cache update trong cùng transaction
- **Delete instead of Update:** Xóa cache thay vì update, để read path populate

### 5.4 Cache Warming Anti-patterns

**Đổ lỗi cache warming không đúng cách:**
- Warm tất cả keys cùng lúc → Thundering herd vào DB
- Warm sequential không parallel → Mất nhiều thời gian
- Warm data không được access → Waste memory

```java
// ❌ BAD: Sequential warming
for (Long id : allUserIds) {
    cache.get(id);  // Một thread, tuần tự, chậm
}

// ✅ GOOD: Parallel warming với rate limit
allUserIds.parallelStream()
    .forEach(id -> {
        cache.get(id, k -> fetchWithCircuitBreaker(k));
    });
```

---

## 6. Khuyến nghị thực chiến trong Production

### 6.1 Multi-Level Cache Strategy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    RECOMMENDED CACHE ARCHITECTURE                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                         L1: Caffeine (Local)                         │   │
│  │  • Size: 10,000 entries (~10MB)                                      │   │
│  │  • TTL: 30s hoặc refreshAfterWrite                                   │   │
│  │  • Content: Ultra-hot entities, computed values                      │   │
│  │  • Policy: TinyLFU, expireAfterAccess                                │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │ Miss                                  │
│                                    ▼                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                         L2: Redis (Distributed)                      │   │
│  │  • Size: GBs (cluster mode)                                          │   │
│  │  • TTL: 5-60 minutes tùy use case                                    │   │
│  │  • Content: Session data, user profiles, rate limit counters         │   │
│  │  • Serialization: Protocol Buffers (compact)                         │   │
│  │  • Eviction: allkeys-lru when maxmemory reached                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │ Miss                                  │
│                                    ▼                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                         L3: CDN (Edge)                               │   │
│  │  • Static assets: Images, CSS, JS (immutable with hash in filename)  │   │
│  │  • API responses: Cacheable GET endpoints                            │   │
│  │  • TTL: 1h-24h cho static, 1-5m cho API                              │   │
│  │  • Invalidation: Purge API cho emergency only                        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │ Miss                                  │
│                                    ▼                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                         Origin: Database                             │   │
│  │  • Query cache disabled (MySQL) - cache ở application level          │   │
│  │  • Read replicas cho read-heavy workloads                            │   │
│  │  • Connection pooling: HikariCP với appropriate sizing               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.2 Cache Monitoring & Alerting

**Key Metrics:**

| Metric | Target | Alert Threshold |
|--------|--------|-----------------|
| Hit Rate | >90% | <80% |
| Miss Rate | <10% | >20% |
| Eviction Rate | Low | Spike indicates undersized |
| Latency (p99) | <1ms L1, <5ms L2 | 2x baseline |
| Stale Data Rate | 0% | >0.1% |
| Cache Size | 70-80% of max | >90% |

**Implementation với Micrometer:**

```java
@Configuration
public class CacheMetricsConfig {
    
    @Bean
    public CacheMetricsRegistrar cacheMetricsRegistrar(
            MeterRegistry meterRegistry,
            CacheManager cacheManager) {
        
        CacheMetricsRegistrar registrar = new CacheMetricsRegistrar(
            cacheManager, meterRegistry);
        
        // Auto-register all caches
        return registrar;
    }
}

// Alert rules (Prometheus)
// cache_hit_ratio < 0.8
// cache_size / cache_max_size > 0.9
```

### 6.3 Cache Invalidation Best Practices

**Golden Rules:**

1. **Invalidate Wide, Then Narrow:**
   ```
   User update → Invalidate user:* (broad) → Sau đó populate specific keys
   ```

2. **Use Lazy Loading:**
   - Không warm cache trừ khi necessary
   - Let cache populate organically qua read path

3. **Version Your Cache Keys:**
   ```
   users:v1:123  ← Thêm version cho breaking changes
   ```

4. **Monitor Stale Data:**
   - Log khi phát hiện stale read
   - Track stale data rate metric

5. **Graceful Degradation:**
   ```java
   @CircuitBreaker(name = "cache", fallbackMethod = "fallback")
   public User getUser(Long id) {
       return cache.get(id);
   }
   
   private User fallback(Long id, Exception ex) {
       // Cache service unavailable → Query DB directly
       return userRepository.findById(id).orElse(null);
   }
   ```

---

## 7. Kết luận

**Bản chất của Multi-Level Caching:**

Multi-level caching là **bài toán trade-off giữa latency, consistency và complexity**. Mỗi tầng cache đại diện cho một sự đánh đổi:

- **L1 (Local):** Tốc độ tuyệt đối nhưng giới hạn bởi memory và consistency challenges
- **L2 (Distributed):** Scalability nhưng network latency
- **L3 (CDN/Edge):** Global reach nhưng eventual consistency và limited programmability

**Cốt lõi kiến thức:**

1. **Cache invalidation không có giải pháp perfect** - Chọn consistency model phù hợp use case
2. **TinyLFU > LRU** cho eviction policy trong hầu hết scenarios
3. **CDC/Event-driven** là xu hướng modern cho cache coherence trong microservices
4. **Monitor hit rate và stale data** - Metrics quan trọng hơn implementation details

**Trade-off quan trọng nhất:**

| Chọn | Khi |
|------|-----|
| TTL-Only | Data ít thay đổi, chấp nhận stale nhất định |
| Write-Through | Data critical, cluster nhỏ, write volume thấp |
| CDC + Eventual | Microservices, scale lớn, audit requirements |

**Rủi ro lớn nhất:**

**Cache Stampede trong high-traffic scenarios** - Một cache miss đúng thời điểm có thể trigger cascade failure. Giải pháp: `refreshAfterWrite`, probabilistic early expiration, và circuit breaker.

---

*Document này tập trung vào bản chất kiến trúc và trade-off. Cho implementation details cụ thể, tham khảo tài liệu của Caffeine, Redis, và CDN provider tương ứng.*
