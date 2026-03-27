# Metrics & Alerting Systems - Deep Dive Research

## 1. Mục tiêu của Task

Hiểu sâu hệ thống metrics và alerting trong kiến trúc observable, tập trung vào:
- Bản chất cơ chế thu thập, lưu trữ, và truy vấn metrics
- Thiết kế data model của time-series database (TSDB)
- Cơ chế alerting: từ rule evaluation đến notification routing
- Trade-off giữa cardinality, retention, và query performance
- Production concerns: SLO/SLI definition, alert fatigue prevention

> **Tầm quan trọng:** Metrics là nền tảng của observability. Không có metrics đúng, mọi quyết định scaling, debugging, và capacity planning đều dựa trên "cảm giác" thay vì dữ kiện.

---

## 2. Bản chất và Cơ chế Hoạt động

### 2.1 Time-Series Data Model

Metrics là **time-series data**: một chuỗi các điểm dữ liệu được đánh dấu thờ gian, mỗi điểm chứa:
- **Timestamp**: thờ điểm đo (millisecond precision)
- **Value**: giá trị đo được (numeric)
- **Labels/Dimensions**: các cặp key-value để phân loại và filter

#### Data Model của Prometheus (de facto standard):

```
<metric_name>{<label_1>=<value_1>, <label_2>=<value_2>, ...}
```

Ví dụ:
```
http_request_duration_seconds{method="POST", endpoint="/api/users", status="200"}
```

#### Bản chất Cardinality:

Cardinality = số lượng unique time-series được tạo ra bởi một metric.

```
Cardinality = |metric_name| × |label_1_values| × |label_2_values| × ... × |label_n_values|
```

**Ví dụ thực tế:**
- Metric `http_requests_total` với labels: `method` (4 values), `endpoint` (100 values), `status` (10 values)
- Cardinality = 1 × 4 × 100 × 10 = **4,000 series**
- Nếu thêm `user_id` (10,000 users): Cardinality = **40,000,000 series** → **Database death**

> ⚠️ **Cardinaliry Explosion** là nguyên nhân #1 khiến TSDB sập hoặc trở nên không query được trong production.

### 2.2 Metric Types - Semantic Meaning

Micrometer/Prometheus định nghĩa 4 loại metric với ý nghĩa semantic khác nhau:

| Type | Ý nghĩa | Use case | Ví dụ |
|------|---------|----------|-------|
| **Counter** | Giá trị chỉ tăng, reset khi restart | Đếm sự kiện | request_count, error_count |
| **Gauge** | Giá trị tăng/giảm tùy ý | Đo trạng thái hiện tại | memory_usage, queue_size, temperature |
| **Histogram** | Phân phối giá trị vào buckets | Đo latency, request size | request_duration_seconds{bucket="0.1"} |
| **Summary** | Tương tự histogram nhưng quantile được tính client-side | Đo quantile chính xác tại client | request_duration_seconds{quantile="0.99"} |

#### Histogram vs Summary - Trade-off quan trọng:

```
Histogram (server-side quantile):
  ✓ Có thể aggregate từ nhiều instance
  ✓ Có thể tính quantile mới sau này
  ✗ Buckets phải định nghĩa trước → quantile không chính xác nếu bucket không phù hợp
  ✗ Cardinality cao: mỗi bucket là một series

Summary (client-side quantile):
  ✓ Chính xác hơn với bất kỳ phân phối nào
  ✗ Không thể aggregate từ nhiều instance (quantile của quantile != quantile của tổng)
  ✗ Tốn CPU/RAM tại client
```

> **Khuyến nghị:** Dùng Histogram cho latency metrics của microservices (cần aggregate cross-instance). Dùng Summary chỉ khi cần đo client-side độc lập.

### 2.3 Pull vs Push Architecture

Prometheus sử dụng **pull model** - đây là quyết định kiến trúc có ảnh hưởng sâu:

#### Pull Model (Prometheus):

```
Prometheus Server ──HTTP GET /metrics──► Target Application
              ▲                           (exposes /metrics endpoint)
              └──scrapes every X seconds──┘
```

**Ưu điểm:**
- Single source of truth: Prometheus biết chính xác khi nào scrape, không lo mất data do network partition
- Dễ detect down target: nếu không scrape được → target down (chứ không phải "không có metrics")
- Control: rate limiting, retry logic tập trung tại server

**Nhược điểm:**
- Target phải expose HTTP endpoint (khó với short-lived jobs, batch processes)
- NAT/firewall issues khi target ở network khác
- Scaling bottleneck: một Prometheus server có giới hạn số targets có thể scrape

#### Push Model (InfluxDB, CloudWatch, Datadog):

```
Application ──UDP/HTTP POST──► Metrics Collector/Gateway ──► TSDB
```

**Ưu điểm:**
- Phù hợp short-lived/batch jobs: push trước khi exit
- Firewall-friendly: outbound connection từ app
- Decoupled: app không cần expose endpoint

**Nhược điểm:**
- Mất data nếu collector unavailable (cần buffering tại client)
- Khó phân biệt "no data" vs "target down"
- Timestamp sync issues giữa các clients

> **Kiến trúc hybrid:** Prometheus + Pushgateway cho batch jobs. Hoặc OTLP (OpenTelemetry) hỗ trợ cả push và pull.

### 2.4 Storage Engine Internals

#### Prometheus TSDB Architecture:

```
┌─────────────────────────────────────────────────────────────┐
│                     Prometheus Server                        │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐   │
│  │   Head Block │  │ WAL (Write-Ahead Log) │             │   │
│  │  (in-memory) │  │   (durability)        │             │   │
│  └──────────────┘  └──────────────┘  └─────────────────┘   │
│           │                                                 │
│           ▼ (compaction every 2h)                           │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  Persistent Blocks (2h chunks, immutable)           │   │
│  │  ├── index (inverted index cho labels)              │   │
│  │  ├── chunks (compressed time-series data)           │   │
│  │  └── tombstones (soft delete markers)               │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

**Key mechanisms:**

1. **Head Block**: In-memory buffer chứa recent data (~2 hours). Query recent = nhanh.
2. **WAL**: Write-ahead log để recovery sau crash. Append-only, sequential write = fast.
3. **Compaction**: Sau 2h, head block được flush thành persistent block. Mục đích:
   - Nén dữ liệu (gorilla compression: XOR of consecutive values)
   - Tạo inverted index cho fast label lookup
   - Giảm số lượng files (too many small files = slow)
4. **Retention**: Blocks cũ hơn retention period bị xóa. Default 15 days.

#### Inverted Index:

Để query `method="POST" AND endpoint="/api/users"` nhanh, TSDB cần inverted index:

```
Index structure:
  method="POST" → [series_1, series_5, series_10, ...]
  method="GET"  → [series_2, series_3, ...]
  endpoint="/api/users" → [series_1, series_2, ...]

Query intersection: [series_1, series_5, series_10] ∩ [series_1, series_2] = [series_1]
```

**Trade-off:** Index tăng write amplification (phải update nhiều posting lists khi có new series) nhưng cho phép query nhanh.

---

## 3. Kiến trúc và Luồng Xử Lý

### 3.1 End-to-End Metrics Pipeline

```mermaid
flowchart LR
    A[Application Code] -->|Micrometer/Prometheus Client| B[In-Process Registry]
    B -->|HTTP /metrics| C[Prometheus Server]
    C -->|Scrape| D[TSDB Storage]
    D -->|Query| E[PromQL Engine]
    E -->|Rule Evaluation| F[Alertmanager]
    F -->|Route/Silence/Inhibit| G[Notification Channels]
    G -->|PagerDuty/Slack/Email| H[On-call Engineer]
```

### 3.2 Alerting Pipeline Deep Dive

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Prometheus Alerting Flow                         │
│                                                                      │
│  1. Recording Rules (optional, pre-compute expensive queries)       │
│     job:http_request_duration_99th:99quantile =                      │
│         histogram_quantile(0.99, sum(rate(http_request_duration...   │
│                                                                      │
│  2. Alerting Rules (evaluate định kỳ, default 1m)                    │
│     - expr: job:http_request_duration_99th:99quantile > 0.5         │
│       for: 5m  ← "phải vi phạm liên tục 5 phút mới fire"            │
│       labels: severity=critical, team=backend                        │
│       annotations: summary="High latency detected"                   │
│                                                                      │
│  3. Alert State Machine:                                             │
│     Inactive → Pending (expr=true) → Firing (for duration met)      │
│                     ↑________________________________________↓      │
│                     (expr=false, reset về Inactive)                  │
│                                                                      │
│  4. Alertmanager nhận alerts, thực hiện:                            │
│     - Grouping (gộp alerts liên quan thành một notification)        │
│     - Inhibition (nếu critical firing, suppress warning)            │
│     - Silencing (mute alerts trong maintenance window)              │
│     - Routing (dựa trên labels, gửi đến team phù hợp)               │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.3 RED vs USE Methods

**RED Method** ( cho request-driven services):
| Metric | Ý nghĩa | Ví dụ PromQL |
|--------|---------|--------------|
| **R**ate | Requests per second | `sum(rate(http_requests_total[1m]))` |
| **E**rrors | Error rate | `sum(rate(http_requests_total{status=~"5.."}[1m]))` |
| **D**uration | Request latency | `histogram_quantile(0.99, sum(rate(http_request_duration_bucket[5m])) by (le))` |

**USE Method** (cho resources/infrastructure):
| Metric | Ý nghĩa | Ví dụ |
|--------|---------|-------|
| **U**tilization | % resource đang dùng | CPU %, Memory %, Disk % |
| **S**aturation | % resource cần nhưng không có | Request queue length, thread pool exhaustion |
| **E**rrors | Error count | Disk I/O errors, network drops |

> **Sự khác biệt:** RED cho services (user-facing), USE cho resources. Một service healthy (RED OK) vẫn có thể gặp resource issues (USE alerting).

---

## 4. So Sánh Các Lựa Chọn

### 4.1 Prometheus vs InfluxDB vs TimescaleDB

| Tiêu chí | Prometheus | InfluxDB | TimescaleDB |
|----------|------------|----------|-------------|
| **Data Model** | Multi-dimensional labels | Field + Tags (similar) | SQL table with time column |
| **Query Language** | PromQL | InfluxQL/Flux | SQL |
| **Storage** | Local TSDB (single node) | TSI (Time Series Index) | PostgreSQL extension |
| **Scaling** | Federation / Thanos / Cortex | Clustering (enterprise) | PostgreSQL partitioning |
| **Retention** | Block-based deletion | Retention policies | Hypertable chunking |
| **Best For** | Cloud-native, k8s, pull model | High cardinality, IoT | SQL ecosystem, complex joins |

### 4.2 Metric Collection Libraries

| Library | Language | Characteristics |
|---------|----------|-----------------|
| **Micrometer** | Java | Vendor-neutral facade, auto-configuration với Spring Boot |
| **Prometheus Java Client** | Java | Low-level, full control, verbose |
| **OpenTelemetry** | Multi | Cloud-native standard, unified traces/metrics/logs |
| **StatsD** | Multi | Simple UDP protocol, fire-and-forget |

> **Khuyến nghị Java:** Micrometer cho ứng dụng Spring Boot (convention over configuration). OpenTelemetry nếu cần correlate traces + metrics.

---

## 5. Rủi ro, Anti-patterns, và Lỗi Thường Gặp

### 5.1 Cardinality Explosion - Case Study

**Scenario:** Một team quyết định thêm `user_id` vào HTTP request metrics để "debug dễ hơn".

```java
// ANTI-PATTERN - ĐỪNG LÀM THẾ NÀY
Counter.builder("http.requests")
    .tag("user_id", userId)  // 1M users = 1M series!
    .tag("endpoint", endpoint)
    .register(registry)
    .increment();
```

**Hệ quả:**
- Prometheus memory tăng từ 2GB → 50GB
- Query timeout sau 30s
- Scrapes bị miss (targets appear "down")
- Chi phí storage tăng 25x

**Giải pháp:**
```java
// Tách bạch: High-cardinality labels chỉ trong traces/logs
// Metrics chỉ giữ low-cardinality dimensions
Counter.builder("http.requests")
    .tag("endpoint", endpoint)      // 100 endpoints = OK
    .tag("status_code", statusCode) // 10 codes = OK
    // .tag("user_id", userId)      // KHÔNG!
    .register(registry)
    .increment();
```

### 5.2 Histogram Bucket Design

```java
// ANTI-PATTERN: Buckets không phù hợp với SLI
Histogram.builder("http.request.duration")
    .serviceLevelObjectives(
        Duration.ofMillis(1),    // Quá nhỏ, 99% requests > 1ms
        Duration.ofMillis(10),   
        Duration.ofSeconds(1)    // Quá lớn, gap 10ms→1s mất resolution
    )
    .register(registry);

// Sẽ dẫn đến: SLI "99th percentile < 100ms" không thể tính chính xác
// vì không có bucket nào gần 100ms!
```

**Best Practice:**
```java
// Buckets dựa trên SLI thực tế
Histogram.builder("http.request.duration")
    .serviceLevelObjectives(
        Duration.ofMillis(25),   // dưới 25ms
        Duration.ofMillis(50),   // dưới 50ms  
        Duration.ofMillis(100),  // dưới 100ms ← SLI chính
        Duration.ofMillis(250),
        Duration.ofMillis(500),
        Duration.ofSeconds(1)    // +Inf bucket tự động có
    )
    .register(registry);
```

### 5.3 Alert Anti-patterns

| Anti-pattern | Vấn đề | Giải pháp |
|--------------|--------|-----------|
| **Alert trên symptoms** | "CPU > 80%" không nói lên user impact | Alert trên SLI: "Error rate > 0.1%" hoặc "Latency p99 > 500ms" |
| **Quá nhiều thresholds** | Warning/Critical/Info cho cùng metric | Chỉ 2 levels: Page (wake up) và Ticket (next business day) |
| **No `for` duration** | Alert fire ngay khi spike 1s | Dùng `for: 5m` để tránh flapping |
| **Missing runbook links** | Engineer không biết xử lý | Mọi alert phải có annotation: `runbook_url` |

### 5.4 Common PromQL Mistakes

```promql
-- ANTI-PATTERN: rate() trên Gauge (counter mới dùng rate)
rate(memory_usage_bytes[5m])  -- VÔ NGHĨA

-- Đúng: rate chỉ cho counter
cpu_usage_seconds_total  -- counter (tích lũy)
rate(cpu_usage_seconds_total[5m])  -- OK

-- ANTI-PATTERN: irate() cho alerting (quá nhạy)
irate(errors_total[5m]) > 0.1  -- Alert flapping

-- Đúng: rate() cho alerting, irate() chỉ cho dashboards
rate(errors_total[5m]) > 0.1   -- Ổn định hơn
```

---

## 6. Khuyến nghị Thực chiến Production

### 6.1 SLI/SLO/SLA Framework

```
SLI (Service Level Indicator) - Đo lường gì?
  → Error rate, latency p99, throughput

SLO (Service Level Objective) - Mục tiêu bao nhiêu?
  → "Error rate < 0.1% trong 30 ngày"
  → "Latency p99 < 200ms"

SLA (Service Level Agreement) - Cam kết với khách hàng?
  → "Nếu uptime < 99.9%, refund 10% monthly fee"
```

**Quy tắc SLO:**
- Không đặt SLO quá cao (99.99% = chỉ 52 phút downtime/năm)
- Có error budget: nếu còn 20% budget, có thể deploy risky change
- SLO phải measurable qua metrics

### 6.2 Alerting Hierarchy

```
┌─────────────────────────────────────────────────────────────┐
│  Symptom-based Alerts (Page người on-call ngay lập tức)     │
│  - Error rate > SLO threshold                               │
│  - Latency p99 > SLO threshold                              │
│  - Availability drops below target                          │
├─────────────────────────────────────────────────────────────┤
│  Cause-based Alerts (Ticket, không page)                    │
│  - Resource exhaustion (CPU/Mem > 80%)                      │
│  - Predictive alerts (disk will fill in 24h)                │
├─────────────────────────────────────────────────────────────┤
│  Tooling Alerts (Auto-remediate nếu có thể)                 │
│  - Auto-scale khi queue depth > threshold                   │
│  - Restart unhealthy instances                              │
└─────────────────────────────────────────────────────────────┘
```

### 6.3 Production Checklist

**Instrumentation:**
- [ ] RED metrics cho mọi service
- [ ] USE metrics cho infrastructure
- [ ] Business metrics (orders/min, revenue/hour)
- [ ] Custom metrics cho domain-specific KPIs

**Cardinality Guardrails:**
- [ ] Limit unique series per metric (< 10,000)
- [ ] No user IDs, session IDs, request IDs trong labels
- [ ] Review cardinality trước khi deploy new metrics

**Alerting:**
- [ ] Symptom-based > cause-based
- [ ] Every alert có runbook
- [ ] Alert fatigue review: < 5 alerts/shift là healthy
- [ ] Silences có expiration (không silence forever)

**Reliability:**
- [ ] Thanos/Cortex cho long-term storage (> 15 days)
- [ ] Multiple Prometheus instances (HA)
- [ ] Alertmanager clustering (không single point of failure)

### 6.4 Java/Spring Boot Specific

```java
@Configuration
public class MetricsConfig {
    
    @Bean
    MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
            .commonTags("application", "order-service")
            .commonTags("environment", "production");
    }
    
    // Custom SLA-based histogram
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}

// Sử dụng @Timed với SLA
@Timed(value = "order.processing", 
       histogram = true, 
       sla = {100, 200, 500, 1000, 2000})  // milliseconds
public Order processOrder(OrderRequest request) {
    // ...
}
```

---

## 7. Kết luận

**Bản chất của Metrics & Alerting:**

1. **Metrics là time-series data** với trade-off cardinality vs granularity. Hiểu cardinality là chìa khóa để không làm sập hệ thống.

2. **Pull vs Push** là quyết định kiến trúc ảnh hưởng đến reliability, scalability, và operational complexity. Prometheus pull model phù hợp cloud-native; push model cần thiết cho short-lived workloads.

3. **Histogram vs Summary** quan trọng hơn syntax - ảnh hưởng đến khả năng aggregate và accuracy của percentiles.

4. **Alerting là state machine** từ expression evaluation đến notification routing. `for` duration và grouping là công cụ chống alert fatigue.

5. **RED cho services, USE cho resources** - dùng đúng method để bao phủ đủ góc nhìn.

6. **Cardinaliry explosion là nguy cơ số 1** - một metric với high-cardinality labels có thể đánh sập cả monitoring cluster.

**Chốt lại:** Metrics không phải là "nice to have" - là nền tảng của mọi quyết định engineering. Nhưng metrics sai (high cardinality, wrong aggregation) còn tệ hơn không có metrics. Senior engineer không chỉ biết cách viết query, mà còn biết **khi nào không nên thêm metric**.

---

## 8. Tài liệu Tham khảo

- [Prometheus Documentation](https://prometheus.io/docs/introduction/overview/)
- [Google SRE Book - Monitoring Distributed Systems](https://sre.google/sre-book/monitoring-distributed-systems/)
- [The RED Method: A New Approach to Monitoring](https://www.weave.works/blog/the-red-method-a-new-approach-to-monitoring)
- [Micrometer Documentation](https://micrometer.io/docs)
- [OpenTelemetry Metrics](https://opentelemetry.io/docs/concepts/signals/metrics/)
