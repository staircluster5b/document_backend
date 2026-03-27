# Caching Patterns & Strategies - Deep Dive Research

## 1. Mục tiêu của Task

Hiểu sâu các pattern caching phổ biến, cơ chế eviction, và chiến lược TTL design để áp dụng đúng đắn trong hệ thống production.

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 Tại sao cần Caching?

Bản chất của caching là **đánh đổi không gian bộ nhớ lấy thởi gian truy cập**. Mọi quyết định về caching đều xoay quanh ba yếu tố:

| Yếu tố | Mô tả | Ảnh hưởng |
|--------|-------|-----------|
| **Hit Rate** | Tỷ lệ tìm thấy dữ liệu trong cache | Càng cao càng giảm tải cho database |
| **Consistency** | Độ nhất quán giữa cache và source | Đánh đổi với performance |
| **Overhead** | Chi phí quản lý cache (memory, network, CPU) | Có thể vượt qua lợi ích nếu không đúng pattern |

### 2.2 Các Caching Patterns Chính

#### 2.2.1 Cache-Aside (Lazy Loading)

**Cơ chế:** Application tự quản lý cache. Khi cần dữ liệu:
1. Kiểm tra cache trước
2. Nếu có → return (cache hit)
3. Nếu không → query database, lưu vào cache, return (cache miss)

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Client     │────▶│  Application│────▶│    Cache    │
└─────────────┘     └──────┬──────┘     └──────┬──────┘
                           │                   │
                           │ cache miss        │
                           ▼                   │
                    ┌─────────────┐            │
                    │   Database  │◀───────────┘
                    └─────────────┘   write
```

**Bản chất hoạt động:**
- Cache chỉ là **optional layer**, không phải source of truth
- Application chịu trách nhiệm hoàn toàn cho việc read/write cache
- Lazy population: data chỉ vào cache khi được yêu cầu

**Trade-offs:**
| Ưu điểm | Nhược điểm |
|---------|-----------|
| Đơn giản, dễ implement | Cache miss penalty cao (phải wait database) |
| Cache không phụ thuộc vào database | Dữ liệu có thể stale giữa cache và DB |
| Failure isolation tốt | Thundering herd trên cache expiry |

**Failure Modes:**
- **Thundering Herd:** Khi cache expire, nhiều request đồng thợi hit database. Giải pháp: per-lock, stale-while-revalidate.
- **Cache Penetration:** Query dữ liệu không tồn tại (cả cache và DB đều miss). Giải pháp: cache empty result với TTL ngắn, Bloom filter.

---

#### 2.2.2 Read-Through

**Cơ chế:** Cache provider tự động load dữ liệu từ database khi miss.

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Client     │────▶│  Application│────▶│    Cache    │
└─────────────┘     └─────────────┘     └──────┬──────┘
                                               │
                                         cache miss
                                               │
                                               ▼
                                        ┌─────────────┐
                                        │   Database  │
                                        └─────────────┘
```

**Bản chất:** Cache trở thành ** façade** - application không biết sự tồn tại của database. Cache provider implement `CacheLoader` interface.

**Trade-offs:**
| Ưu điểm | Nhược điểm |
|---------|-----------|
| Code application sạch hơn | Coupling với cache provider |
| Tự động handle cache miss | Khó customize logic load |
| Dễ switching cache implementation | Vendor lock-in potential |

**Khi nào dùng:**
- Khi muốn cache layer hoàn toàn transparent
- Data access pattern đơn giản, ít business logic
- Dùng managed cache services (ElastiCache, Redis Enterprise)

---

#### 2.2.3 Write-Through

**Cơ chế:** Ghi đồng thợi vào cache và database. Application chỉ write vào cache, cache provider đảm bảo persistence.

```
                    write
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Client     │────▶│    Cache    │────▶│   Database  │
└─────────────┘     └─────────────┘     └─────────────┘
```

**Bản chất:** Strong consistency giữa cache và database tại thởi điểm write. Latency của write operation = cache write + DB write.

**Trade-offs:**
| Ưu điểm | Nhược điểm |
|---------|-----------|
| Strong consistency | Write latency cao (chained writes) |
| Không lo cache inconsistency | Write amplification nếu data ít đọc |
| Dễ recovery sau crash | Cache phải durable/reliable |

**Anti-pattern:** Không nên dùng cho write-heavy workloads với data ít khi đọc lại.

---

#### 2.2.4 Write-Behind (Write-Back)

**Cơ chế:** Ghi vào cache ngay, ghi vào database asynchronous (batch hoặc delayed).

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Client     │────▶│    Cache    │────▶│   Database  │
└─────────────┘     └─────────────┘     └─────────────┘
                            │                  ▲
                            └──────────────────┘
                              async/batch write
```

**Bản chất:** Tối ưu write performance bằng cách coi cache là **temporary source of truth**. Data được flush xuống DB theo schedule hoặc trigger.

**Trade-offs:**
| Ưu điểm | Nhược điểm |
|---------|-----------|
| Write latency cực thấp | Data loss risk nếu cache crash trước flush |
| Batch writes → giảm DB load | Complexity của write queue management |
| Tốt cho write-heavy bursty traffic | Khó đảm bảo strong consistency |

**Production Concerns:**
- **Durability:** Cần replication/persistence cho cache để tránh mất data
- **Flush strategy:** Time-based (every N seconds) vs Size-based (every N writes)
- **Failure recovery:** Replay unflushed writes sau restart

---

### 2.5 Write-Around

**Cơ chế:** Write vào database, bypass cache. Cache chỉ được populate qua read operations.

**Dùng khi:** Data vừa được tạo, chưa chắc sẽ được đọc lại ngay. Tránh cache pollution.

---

## 3. Cache Eviction Policies

### 3.1 Bản chất Eviction

Cache có giới hạn memory. Khi đầy, phải **loại bỏ entries** để make room. Quyết định loại bỏ entry nào là **eviction policy**.

### 3.2 Các Policy Phổ Biến

| Policy | Thuật toán | Khi nào dùng | Trade-off |
|--------|-----------|--------------|-----------|
| **LRU** (Least Recently Used) | Doubly-linked list + HashMap | Access pattern có locality | O(1) operations, không tốt cho scan |
| **LFU** (Least Frequently Used) | Min-heap + counters | Popular items stable over time | O(log n), counters overhead |
| **FIFO** (First In First Out) | Queue | Đơn giản, không cần tracking | Không consider access pattern |
| **TTL-based** | Priority queue theo expiry | Time-sensitive data | Không consider access frequency |
| **Random** | Random selection | Đơn giản, low overhead | Unpredictable, thường kém hiệu quả |

### 3.3 Redis Eviction Strategies

Redis cung cấp 8 strategies kết hợp `volatile-*` (chỉ evict keys có TTL) và `allkeys-*` (evict bất kỳ key nào):

```
volatile-lru      → Chỉ evict keys có TTL, theo LRU
allkeys-lru       → Evict bất kỳ key, theo LRU
volatile-lfu      → Chỉ evict keys có TTL, theo LFU
allkeys-lfu       → Evict bất kỳ key, theo LFU
volatile-random   → Random trong keys có TTL
allkeys-random    → Random tất cả keys
volatile-ttl      → Evict key có TTL nhỏ nhất (sắp hết hạn)
noeviction        → Không evict, return error khi full
```

**Khuyến nghị:**
- `allkeys-lru` cho general caching
- `volatile-lru` khi có mix của cache data và persistent data
- `volatile-ttl` cho session/cache với TTL rõ ràng

### 3.4 Approximated LRU trong Redis

Redis không implement "true LRU" (quá tốn memory). Thay vào đó:

- Mẫu ngẫu nhiên 5 keys, evict key LRU nhất trong sample
- Có thể tăng sample size qua `maxmemory-samples` (default 5, max 10)
- Trade-off: tăng samples → chính xác hơn nhưng CPU cost cao hơn

---

## 4. TTL Design Strategies

### 4.1 Bản chất TTL

TTL (Time To Live) xác định **lifespan** của cached entry. TTL design ảnh hưởng trực tiếp đến:
- Hit rate
- Data freshness
- Memory usage
- Database load patterns

### 4.2 TTL Patterns

#### Pattern 1: Fixed TTL

```
TTL = constant (e.g., 3600 seconds)
```

**Vấn đề:** Thundering herd khi nhiều keys expire cùng lúc.

#### Pattern 2: TTL with Jitter (Recommended)

```
TTL = base_ttl + random(0, jitter_range)
# Ví dụ: TTL = 3600 + random(0, 300)
```

**Lợi ích:** Spread expiry times, tránh thundering herd đồng loạt.

#### Pattern 3: Sliding TTL

```
TTL được reset mỗi lần cache hit
```

**Dùng khi:** Hot data nên được giữ lại lâu hơn cold data.

**Trade-off:** Cold data có thể never expire nếu không được accessed.

#### Pattern 4: Tiered TTL

```
Hot data:   TTL = 1 hour + jitter
Warm data:  TTL = 10 minutes + jitter  
Cold data:  TTL = 2 minutes + jitter
```

**Implementation:** Dựa trên access frequency hoặc business importance.

#### Pattern 5: Stale-While-Revalidate

```
Cache entry có 2 timestamps:
- fresh_until: Trả về ngay không cần revalidate
- stale_until: Trả về stale data trong khi async revalidate
```

**HTTP Cache-Control analogy:**
```
Cache-Control: max-age=3600, stale-while-revalidate=600
```

**Lợi ích:** Giảm perceived latency, không bao giờ block user cho cache miss.

---

## 5. So Sánh Các Pattern

```
┌────────────────────────────────────────────────────────────────────────┐
│                         PATTERN COMPARISON MATRIX                       │
├──────────────┬──────────┬──────────┬──────────┬──────────┬──────────────┤
│   Aspect     │Cache-Aside│Read-Thru │Write-Thru│Write-Back│Write-Around  │
├──────────────┼──────────┼──────────┼──────────┼──────────┼──────────────┤
│ Complexity   │   Low    │  Medium  │  Medium  │   High   │     Low      │
│ Consistency  │  Eventual│  Eventual│  Strong  │  Weak    │   Eventual   │
│ Read Latency │   Low*   │   Low    │   Low    │   Low    │     High     │
│ Write Latency│   Low    │   Low    │   High   │   Low    │     Low      │
│ Data Safety  │   High   │   High   │   High   │   Low**  │     High     │
│ Code Changes │   High   │   Low    │   Low    │   Low    │     Medium   │
└──────────────┴──────────┴──────────┴──────────┴──────────┴──────────────┘

* Cache miss = high latency
** Phụ thuộc vào flush reliability
```

---

## 6. Rủi Ro, Anti-Patterns và Pitfalls

### 6.1 Thundering Herd

**Mô tả:** Nhiều requests đồng thợi trigger cache miss và query database.

**Giải pháp:**
1. **Per-key locking:** Chỉ 1 request được phép load từ DB
2. **Stale-while-revalidate:** Trả về stale data trong khi refresh
3. **Probabilistic early expiry:** X% keys expire sớm hơn expected

```java
// Pattern: Lease-based caching
public V getWithLease(K key) {
    ValueWrapper wrapper = cache.get(key);
    if (wrapper != null && !wrapper.isExpired()) {
        return wrapper.getValue(); // Cache hit
    }
    
    // Try acquire lease to load from DB
    if (acquireLease(key)) {
        try {
            V value = loadFromDatabase(key);
            cache.put(key, new ValueWrapper(value, ttl));
            return value;
        } finally {
            releaseLease(key);
        }
    } else {
        // Another thread is loading, wait or return stale
        return waitOrReturnStale(key);
    }
}
```

### 6.2 Cache Penetration

**Mô tả:** Query data không tồn tại (cả cache và DB đều miss).

**Giải pháp:**
1. **Cache null/empty:** Lưu "not found" với TTL ngắn (e.g., 60s)
2. **Bloom Filter:** Kiểm tra existence trước khi query

### 6.3 Cache Stampede (Cascading Failure)

**Mô tả:** Cache layer failure → all traffic đổ về DB → DB overload → system down.

**Giải pháp:**
1. **Circuit breaker:** Ngắt kết nối khi DB quá tải
2. **Cache warming:** Pre-populate cache sau restart
3. **Graceful degradation:** Serve stale data nếu DB unavailable

### 6.4 Large Object Cache

**Anti-pattern:** Cache objects quá lớn (MBs).

**Hệ quả:**
- Memory fragmentation
- Serialization/deserialization overhead
- Network bandwidth consumption

**Giải pháp:**
- Split thành smaller chunks
- Cache references/pointers thay vì full object
- Compression (lưu ý CPU cost)

### 6.5 Cache Key Design

**Bad practices:**
- Keys quá dài (memory waste)
- Keys không có namespace (collision risk)
- Keys chứa user input (injection risk)

**Best practices:**
```
format: {service}:{entity}:{identifier}:{version}
example: user:profile:12345:v2
hash nếu key quá dài: user:profile:sha256(identifier)
```

---

## 7. Khuyến Nghị Thực Chiến Production

### 7.1 Monitoring & Observability

**Metrics cần track:**
| Metric | Formula | Target |
|--------|---------|--------|
| Hit Rate | hits / (hits + misses) | > 90% |
| Miss Rate | misses / total | < 10% |
| Eviction Rate | evictions / time | Stable, không spike |
| Memory Usage | used / max | < 80% |
| Latency | p50, p99 | < 1ms for hits |

### 7.2 Multi-Layer Caching

```
┌─────────────────────────────────────────┐
│         Multi-Layer Architecture        │
├─────────────────────────────────────────┤
│  L1: In-process (Caffeine, Guava)       │
│      → Microseconds latency             │
│      → Per-JVM, no network              │
│                                         │
│  L2: Distributed (Redis)                │
│      → Milliseconds latency             │
│      → Shared across instances          │
│                                         │
│  L3: CDN / Edge (CloudFront, CloudFlare)│
│      → Global distribution              │
│      → Static/read-heavy content        │
└─────────────────────────────────────────┘
```

**Cache coherence:** L1 cần invalidation mechanism khi L2 update.

### 7.3 Invalidation Strategies

| Strategy | Khi nào dùng | Trade-off |
|----------|--------------|-----------|
| **TTL-based** | Data có predictable lifecycle | Đơn giản, eventual consistency |
| **Event-driven** | Cần strong consistency | Phức tạp, requires message queue |
| **Version-based** | Data có versions rõ ràng | No invalidation needed, auto-expire old versions |

**Event-driven invalidation:**
```
DB Update → Publish event → Cache consumers invalidate
```

### 7.4 Redis-specific Recommendations

1. **Use Connection Pooling:** Lettuce hoặc Jedis pool
2. **Pipeline commands:** Giảm round-trip cho batch operations
3. **Monitor slowlog:** `SLOWLOG GET` để phát hiện expensive operations
4. **Use appropriate data structures:**
   - Strings: Simple key-value
   - Hashes: Object caching (field-level access)
   - Sets/Sorted Sets: Relationships, rankings
5. **Enable persistence (AOF/RDB)** cho critical data

---

## 8. Kết Luận

### Bản chất cốt lõi:

> **Caching là việc đánh đổi consistency lấy performance.** Không có pattern nào tối ưu cho mọi trường hợp - quyết định phụ thuộc vào consistency requirements, access patterns, và tolerance cho complexity.

### Trade-off quan trọng nhất:

| Yếu tố | Cache-Aside | Write-Through | Write-Behind |
|--------|-------------|---------------|--------------|
| **Consistency** | Eventual | Strong | Weak |
| **Complexity** | Low | Medium | High |
| **Performance** | Read-optimized | Balanced | Write-optimized |

### Rủi ro lớn nhất:

**Thundering herd và cache stampede** có thể gây cascading failure toàn hệ thống. Phòng ngừa bằng per-key locking, stale-while-revalidate, và circuit breakers.

### Khuyến nghị cuối cùng:

1. **Bắt đầu với Cache-Aside** - đơn giản, dễ hiểu, dễ debug
2. **Luôn dùng TTL with jitter** - tránh thundering herd đồng loạt
3. **Implement monitoring từ đầu** - hit rate, memory, latency
4. **Thiết kế cache keys cẩn thận** - namespace, hashing, no injection
5. **Plan cho failure** - graceful degradation khi cache unavailable

---

## 9. Tài liệu Tham Khảo

- Redis Documentation: https://redis.io/documentation
- Caffeine Cache: https://github.com/ben-manes/caffeine
- AWS Caching Best Practices
- Martin Fowler - Patterns of Enterprise Application Architecture
