# Metrics & Alerting Systems: Deep Dive cho Production Systems

> **Mục tiêu:** Hiểu sâu cơ chế thu thập, lưu trữ, và cảnh báo metrics trong hệ thống phân tán quy mô lớn. Không chỉ dừng ở cách dùng tool mà phải nắm bản chất data model, trade-off, và failure modes.

---

## 1. Mục tiêu của Task

Xây dựng khả năng:
- Thiết kế metrics collection architecture phù hợp với scale và use case
- Hiểu sâu Prometheus data model để viết queries hiệu quả và tránh cardinality explosion
- Áp dụng RED/USE methods để định nghĩa SLO/SLI có ý nghĩa
- Thiết lập alerting pipeline với routing, silencing, inhibition hiệu quả
- Nhận diện anti-patterns và production pitfalls trong monitoring

---

## 2. Bản chất và Cơ chế Hoạt động

### 2.1 Kiến trúc Tổng quan của Metrics Pipeline

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Application   │────▶│  Metrics Client  │────▶│   Prometheus    │────▶│   Alertmanager  │
│   (Micrometer)  │     │  (Push/Pull)     │     │   Server        │     │                 │
└─────────────────┘     └──────────────────┘     └─────────────────┘     └─────────────────┘
                                                               │                │
                                                               ▼                ▼
                                                      ┌─────────────────┐  ┌─────────────────┐
                                                      │   Grafana/      │  │   PagerDuty/    │
                                                      │   Dashboard     │  │   Slack/Email   │
                                                      └─────────────────┘  └─────────────────┘
```

#### Bản chất của Time-Series Data

Metrics là **time-series data**: mỗi điểm dữ liệu gồm `(timestamp, value, labels)`. Bản chất của time-series database (TSDB) khác relational database ở chỗ:

| Đặc điểm | Relational DB | Time-Series DB |
|----------|---------------|----------------|
| **Write pattern** | Random access, frequent updates | Append-only, immutable |
| **Read pattern** | Point lookups, joins | Range scans, aggregations |
| **Data volume** | GB-TB | TB-PB |
| **Compression** | Row/column based | Delta encoding, gorilla compression |
| **Retention** | Keep all | Automated downsampling, TTL |

> **Cơ chế lưu trữ của Prometheus:** Prometheus dùng custom TSDB với 2-hour blocks. Mỗi block chứa: (1) **chunks** - compressed time-series data, (2) **index** - label → series mapping, (3) **tombstones** - deleted series markers. Dữ liệu in-memory được flush định kỳ. Compaction nén các blocks nhỏ thành blocks lớn hơn theo thứ bậc: 2h → 1d → 1w.

### 2.2 Micrometer: Abstraction Layer

**Bản chất:** Micrometer là **facade** (giống SLF4J cho logging) cung cấp unified API để instrument code, delegate đến các backend khác nhau (Prometheus, Datadog, CloudWatch, etc.).

#### Cơ chế hoạt động

```
Application Code
      │
      ▼
┌─────────────┐
│  Counter.   │
│  increment()│  ← Vendor-neutral API
└──────┬──────┘
       │
       ▼
┌──────────────┐
│  MeterRegistry│  ← Central registry, holds all meters
│  (Composite)  │     Mỗi meter = name + tags + type
└───────┬──────┘
        │
   ┌────┴────┐
   ▼         ▼
┌───────┐  ┌────────┐
│Prometheus│  │Datadog │  ← Backend-specific registries
│Registry │  │Registry│
└───────┘  └────────┘
```

#### Các Meter Types và Bản chất

| Type | Bản chất | Use case | Không dùng khi |
|------|----------|----------|----------------|
| **Counter** | Monotonically increasing (chỉ tăng, reset khi restart) | Request count, error count | Đo giá trị có thể giảm (dùng Gauge) |
| **Gauge** | Arbitrary value (có thể tăng/giảm) | Queue size, memory usage, active connections | Đếm sự kiện (dùng Counter) |
| **Timer** | Measures duration + count | API latency, DB query time | Không cần histogram percentiles |
| **DistributionSummary** | Measures arbitrary value distribution | Request size, payload size | Không cần phân phối |
| **LongTaskTimer** | Measures duration of in-flight tasks | Active long-running jobs | Tasks ngắn (< 1 phút) |

> **Quan trọng:** Timer trong Micrometer không chỉ đo thờigian. Nó tạo ra **nhiều time-series** gồm: `_count`, `_sum`, `_max`, và `_bucket` (nếu histogram enabled). Đây là nguyên nhân cardinality explosion phổ biến.

### 2.3 Prometheus Data Model

#### Cấu trúc cơ bản

```
http_request_duration_seconds_bucket{method="POST",endpoint="/api/users",status="200",le="0.1"} 1024
http_request_duration_seconds_bucket{method="POST",endpoint="/api/users",status="200",le="0.5"} 2048
http_request_duration_seconds_bucket{method="POST",endpoint="/api/users",status="200",le="1.0"} 3000
http_request_duration_seconds_bucket{method="POST",endpoint="/api/users",status="200",le="+Inf"} 3000
http_request_duration_seconds_sum{method="POST",endpoint="/api/users",status="200"} 1500.5
http_request_duration_seconds_count{method="POST",endpoint="/api/users",status="200"} 3000
```

**Phân tích:**
- **Metric name:** `http_request_duration_seconds` (phải có đơn vị trong tên)
- **Labels:** `method`, `endpoint`, `status`, `le` (bucket boundary)
- **Time-series cardinality:** Mỗi combination unique của labels = 1 time-series
- **Histogram buckets:** Cumulative distribution (bucket 0.5 chứa tất cả ≤ 0.5s)

#### Cardinality: Kẻ thù số một

**Cardinality** = số lượng unique time-series cho một metric. Ví dụ:

```
# Cardinality = 3 methods × 50 endpoints × 5 status × 10 buckets = 7,500 series
http_request_duration_seconds_bucket{method,endpoint,status,le}
```

**Giới hạn thực tế của Prometheus:**
- **~10 million** active series trên single server (thường 2-5M an toàn)
- **~100,000** samples/second ingestion rate
- **~64KB** limit cho labels (name + value)
- **~30** labels max per metric (khuyến nghị: < 10)

> **Anti-pattern nguy hiểm:** Thêm `user_id`, `request_id`, hoặc `timestamp` vào labels. Điều này tạo unlimited cardinality và sẽ crash Prometheus.

#### Metric Types trong Prometheus

| Type | Ý nghĩa | Ví dụ |
|------|---------|-------|
| **Counter** | Cumulative, monotonic | `http_requests_total`, `errors_total` |
| **Gauge** | Current value, up/down | `memory_usage_bytes`, `queue_size` |
| **Histogram** | Distribution thành buckets | `request_duration_seconds_bucket` |
| **Summary** | Quantiles computed client-side | `request_duration_seconds{quantile="0.95"}` |

**Histogram vs Summary:**

| Đặc điểm | Histogram | Summary |
|----------|-----------|---------|
| Aggregation | Server-side (across instances) | Client-side (không aggregable) |
| Quantile accuracy | Configured by buckets | Configured by error margin |
| Resource cost | High (nhiều buckets) | Lower |
| Use case | Distributed systems, SLAs | Client-side latency requirements |

### 2.4 Recording Rules và Federation

#### Recording Rules

Bản chất: Pre-compute expensive queries và lưu kết quả thành new metric.

```yaml
# prometheus-rules.yml
groups:
  - name: api_rules
    interval: 30s
    rules:
      # Aggregate: tính rate cho từng endpoint
      - record: job:http_request_duration_seconds:rate5m
        expr: |
          sum by (job, endpoint) (
            rate(http_request_duration_seconds_count[5m])
          )
      
      # Pre-compute: error rate
      - record: job:http_requests_error_rate:ratio_rate5m
        expr: |
          sum by (job) (rate(http_requests_total{status=~"5.."}[5m]))
          /
          sum by (job) (rate(http_requests_total[5m]))
```

**Lợi ích:**
- Giảm query latency cho dashboards
- Giảm computation load khi query
- Cho phép aggregation phức tạp

**Trade-off:**
- Tăng storage (thêm metrics)
- Delay 1 evaluation interval
- Cần manage thêm rules

#### Federation

Dùng để scale Prometheus horizontally hoặc aggregate từ multiple clusters.

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Prometheus │     │  Prometheus │     │  Prometheus │  ← Leaf (per-cluster)
│  (Cluster A)│     │  (Cluster B)│     │  (Cluster C)│
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
       └───────────────────┼───────────────────┘
                           │ scrape /federate
                           ▼
                  ┌─────────────────┐
                  │ Global Prometheus│  ← Aggregated view
                  └─────────────────┘
```

**Match rules cho federation:**
```yaml
scrape_configs:
  - job_name: 'federate'
    static_configs:
      - targets: ['prometheus-a:9090', 'prometheus-b:9090']
    params:
      'match[]':
        - '{job=~"kubernetes-.*"}'
        - '{__name__=~"job:.*"}'  # Chỉ lấy recording rules
```

---

## 3. RED Method và USE Method

### 3.1 RED Method (cho Microservices)

**Áp dụng cho:** Request-driven services (APIs, HTTP handlers, gRPC services)

| Letter | Metric | Ý nghĩa | Cách đo |
|--------|--------|---------|---------|
| **R** | Rate | Requests per second | `rate(http_requests_total[5m])` |
| **E** | Errors | Error rate | `rate(http_requests_total{status=~"5.."}[5m])` |
| **D** | Duration | Response time | `histogram_quantile(0.99, rate(http_request_duration_seconds_bucket[5m]))` |

**Ví dụ Query RED cho Service:**

```promql
# Rate: Requests/second cho mỗi endpoint
sum by (endpoint) (rate(http_requests_total[5m]))

# Error Rate: % requests thất bại
sum by (endpoint) (rate(http_requests_total{status=~"5.."}[5m]))
/ 
sum by (endpoint) (rate(http_requests_total[5m]))

# Duration: 99th percentile latency
histogram_quantile(0.99, 
  sum by (endpoint, le) (rate(http_request_duration_seconds_bucket[5m]))
)
```

> **Lưu ý:** `histogram_quantile` tính **ước lượng** dựa trên buckets. Độ chính xác phụ thuộc vào bucket boundaries. Không nên dùng cho SLIs nếu cần precision cao.

### 3.2 USE Method (cho Resources)

**Áp dụng cho:** Resources (CPU, memory, disk, network, connection pools)

| Letter | Metric | Ý nghĩa | Cách đo |
|--------|--------|---------|---------|
| **U** | Utilization | % resource đang dùng | `process_cpu_usage`, `jvm_memory_used_bytes / jvm_memory_max_bytes` |
| **S** | Saturation | Extra work waiting | `jvm_threads_blocked_count`, `hikaricp_connections_pending` |
| **E** | Errors | Error events | `disk_io_errors_total` |

**Ví dụ USE cho JVM:**

```promql
# Utilization: Heap usage %
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}

# Saturation: GC pressure
delta(jvm_gc_pause_seconds_count[5m])  # Số GC cycles

# Errors: Không có metric native, cần instrument
```

### 3.3 Từ Method đến SLO/SLI

**SLI (Service Level Indicator):** Metric đo chất lượng dịch vụ
**SLO (Service Level Objective):** Target cho SLI (ví dụ: 99.9% requests < 200ms)
**SLA (Service Level Agreement):** Hợp đồng với khách hàng, thường nghiêm ngặt hơn SLO

**Ví dụ SLOs dựa trên RED:**

| Service | SLI | SLO |
|---------|-----|-----|
| API Gateway | Availability | 99.9% requests thành công |
| API Gateway | Latency | 99% requests < 100ms |
| Payment Service | Error Rate | < 0.1% failed transactions |
| Auth Service | Latency | 99% token validation < 50ms |

**Cách chọn percentile cho SLO:**

| Percentile | Use case | Rủi ro |
|------------|----------|--------|
| p50 (median) | Average user experience | Không bắt outliers |
| p95 | User experience cho hầu hết users | Có thể miss tail latency |
| p99 | Power users, bulk operations | Rất nhạy với outliers |
| p99.9 | Financial systems, critical paths | Resource intensive |

> **Quy tắc thực chiến:** Đừng đặt SLO quá cao. 99.99% availability = 52.6 phút downtime/năm. Chi phí đạt 99.99% vs 99.9% thường gấp 10x nhưng lợi ích marginal.

---

## 4. Alertmanager: Cơ chế và Best Practices

### 4.1 Kiến trúc Alertmanager

```
┌─────────────┐     ┌──────────────┐     ┌─────────────────┐
│  Prometheus │────▶│ Alertmanager │────▶│  Routing Tree   │
│   (Alerts)  │     │              │     │  (Matchers)     │
└─────────────┘     └──────────────┘     └────────┬────────┘
                                                  │
                    ┌─────────────┬───────────────┼─────────────┐
                    ▼             ▼               ▼             ▼
              ┌─────────┐  ┌──────────┐   ┌──────────┐  ┌──────────┐
              │ PagerDuty│  │  Slack   │   │  Email   │  │  Webhook │
              │(Critical)│  │(Warning) │   │(Info)    │  │(Custom)  │
              └─────────┘  └──────────┘   └──────────┘  └──────────┘
```

#### Alert Lifecycle

1. **Firing:** Prometheus đánh giá rule = true
2. **Grouping:** Alerts cùng labels được group lại
3. **Inhibition:** Alert này suppress alert khác
4. **Silencing:** Alert bị mute (planned maintenance)
5. **Routing:** Dựa trên labels, gửi đến receiver
6. **Notification:** Gửi qua PagerDuty/Slack/Email

### 4.2 Routing Tree

```yaml
# alertmanager.yml
route:
  receiver: 'default'
  group_by: ['alertname', 'severity', 'service']
  group_wait: 30s      # Chờ 30s để group alerts
  group_interval: 5m   # Gửi group notification mỗi 5m
  repeat_interval: 4h  # Không spam, tối đa mỗi 4h
  
  routes:
    # Critical alerts → PagerDuty
    - match:
        severity: critical
      receiver: pagerduty-critical
      continue: false
    
    # Database alerts → DBA team
    - match:
        service: database
      receiver: slack-dba
      routes:
        - match:
            severity: warning
          receiver: slack-dba-warn
    
    # Production only → SRE channel
    - match_re:
        environment: production|prod
      receiver: slack-sre
```

**Cơ chế matching:**
- `match:` exact label match
- `match_re:` regex match
- `continue: true` → alert tiếp tục đến routes khác
- Route được evaluate theo thứ tự, dừng khi match (trừ khi continue)

### 4.3 Grouping, Inhibition và Silencing

#### Grouping

```yaml
group_by: ['alertname', 'cluster', 'service']
```

**Ví dụ:** 100 pods cùng fail → 1 notification thay vì 100.

#### Inhibition

```yaml
inhibit_rules:
  # Nếu có alert 'database_down', suppress tất cả 'high_latency' alerts
  - source_match:
      alertname: 'DatabaseDown'
    target_match:
      alertname: 'HighLatency'
    equal: ['datacenter', 'service']
```

**Use case:** Không spam khi root cause đã biết.

#### Silencing

Có thể tạo silence qua UI hoặc API:

```bash
# Silence tất cả warnings trong maintenance window
curl -X POST http://alertmanager:9093/api/v1/silences \
  -d '{
    "matchers": [
      {"name": "severity", "value": "warning", "isRegex": false},
      {"name": "service", "value": "payment-api", "isRegex": false}
    ],
    "startsAt": "2026-03-27T10:00:00Z",
    "endsAt": "2026-03-27T12:00:00Z",
    "createdBy": "sre-oncall",
    "comment": "Planned deployment"
  }'
```

### 4.4 Alerting Rules: Cách viết đúng

#### Cấu trúc Alerting Rule

```yaml
groups:
  - name: api_alerts
    rules:
      - alert: HighErrorRate
        expr: |
          (
            sum by (service) (rate(http_requests_total{status=~"5.."}[5m]))
            /
            sum by (service) (rate(http_requests_total[5m]))
          ) > 0.01
        for: 5m  # Phải sustain 5m mới fire
        labels:
          severity: critical
          team: sre
        annotations:
          summary: "High error rate on {{ $labels.service }}"
          description: "Error rate is {{ $value | humanizePercentage }}"
          runbook_url: "https://wiki.internal/runbooks/high-error-rate"
```

#### Tham số `for` - Tại sao quan trọng?

```
Without 'for':
Metric spike ──▶ Alert fires immediately ──▶ Auto-resolve 10s sau
      │              │ (false positive)
      └──────────────┘

With 'for: 5m':
Metric spike ──▶ Alert PENDING ──▶ Still firing after 5m ──▶ Alert FIRING
      │              │                  │ (real issue)
      └──────────────┘──────────────────┘
```

> **Quy tắc:** `for` duration phải đủ dài để loại bỏ transient spikes, nhưng đủ ngắn để catch real issues. Thường: 2-5 phút cho lỗi, 10-15 phút cho saturation warnings.

---

## 5. Rủi ro, Anti-patterns và Lỗi Thường gặp

### 5.1 Cardinality Explosion

**Anti-pattern:**
```java
// ❌ KHÔNG BAO GIỜ làm điều này
counter.increment("requests", "user_id", userId);  // Unbounded cardinality!
counter.increment("requests", "timestamp", String.valueOf(System.currentTimeMillis()));
```

**Giải pháp:**
```java
// ✅ Chỉ dùng high-cardinality fields như span tags trong tracing, không phải metrics
// Nếu cần debug individual requests → dùng logging hoặc tracing
counter.increment("requests", "endpoint", endpoint, "status", status);
```

### 5.2 Histogram Bucket Selection

**Anti-pattern:**
```yaml
# ❌ Buckets không phù hợp với use case
buckets: [0.001, 0.01, 0.1, 1, 10]  # API thường 200-500ms
```

**Vấn đề:** API latency 200ms sẽ rơi vào bucket 1s. p95 estimate sẽ rất sai.

**Giải pháp:**
```yaml
# ✅ Buckets dựa trên SLO
# Nếu SLO: 95% requests < 300ms
buckets: [0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10]
#       25ms  50ms 100ms 250ms 500ms
```

### 5.3 Alert Fatigue

**Anti-pattern:**
```yaml
# ❌ Quá nhiều alerts không actionable
- alert: CPUHigh
  expr: cpu_usage > 80  # Bình thường trong burst
  for: 0s               # Fire ngay lập tức
  
- alert: MemoryHigh  
  expr: memory_usage > 70  # Normal cho JVM heap
```

**Giải pháp:**
```yaml
# ✅ Chỉ alert khi actionable và sustained
- alert: CPUThrottling
  expr: |
    (
      rate(container_cpu_cfs_throttled_seconds_total[5m]) > 0.1
    and
      container_cpu_usage_seconds_total / container_spec_cpu_quota > 0.8
    )
  for: 10m
  labels:
    severity: warning
  annotations:
    action: "Consider increasing CPU limits or optimizing code"
```

### 5.4 Missing Context trong Alerts

**Anti-pattern:**
```yaml
# ❌ Alert không giúp gì khi wake up 3 AM
annotations:
  summary: "Service is down"
```

**Giải pháp:**
```yaml
# ✅ Đầy đủ context và actionable
annotations:
  summary: "Payment service error rate > 5%"
  description: |
    Service: {{ $labels.service }}
    Error rate: {{ $value | humanizePercentage }}
    Duration: {{ $duration }}
  impact: "Users cannot complete payments"
  action: "1. Check DB connection pool
           2. Review recent deployment
           3. Runbook: https://go/payment-runbook"
  dashboard: "https://grafana/d/payment-dashboard"
```

### 5.5 Recording Rule Complexity

**Anti-pattern:**
```yaml
# ❌ Rule quá complex, khó debug
expr: |
  sum by (cluster) (
    rate(
      container_cpu_usage_seconds_total{
        namespace=~"prod-.*",
        pod=~"api-.*",
        container!="POD"
      }[5m]
    )
  ) / on(cluster) group_left
  sum by (cluster) (
    machine_cpu_cores
  )
```

**Giải pháp:**
```yaml
# ✅ Chia thành rules nhỏ, intermediate
rules:
  - record: namespace:container_cpu_usage:rate5m
    expr: |
      sum by (cluster, namespace) (
        rate(container_cpu_usage_seconds_total[5m])
      )
  
  - record: cluster:node_cpu_cores:sum
    expr: |
      sum by (cluster) (machine_cpu_cores)
  
  - record: cluster:container_cpu_utilization:ratio
    expr: |
      namespace:container_cpu_usage:rate5m
      / 
      cluster:node_cpu_cores:sum
```

---

## 6. Khuyến nghị Thực chiến trong Production

### 6.1 Micrometer Configuration cho Spring Boot

```yaml
# application.yml
management:
  metrics:
    export:
      prometheus:
        enabled: true
    
    distribution:
      percentiles-histogram:
        http.server.requests: true  # Enable histogram
      slo:
        http.server.requests: 50ms, 100ms, 200ms, 500ms, 1s, 5s
      
    tags:
      application: ${spring.application.name}
      environment: ${ENV:development}
      region: ${AWS_REGION:unknown}
  
  endpoint:
    metrics:
      enabled: true
    prometheus:
      enabled: true
  
  server:
    port: 8081  # Separate port để tránh expose trong public
```

### 6.2 Prometheus Performance Tuning

```yaml
# prometheus.yml
global:
  scrape_interval: 15s      # Default
  evaluation_interval: 15s  # Alert rule evaluation
  external_labels:
    cluster: production
    replica: prom-0

storage:
  tsdb:
    retention.time: 30d     # Hoặc retention.size: 500GB
    retention.size: 500GB
    min-block-duration: 2h
    max-block-duration: 2h
    wal-compression: true   # Nén WAL để giảm I/O

# Remote storage cho long-term (optional)
remote_write:
  - url: "http://thanos-receive:19291/api/v1/receive"
    queue_config:
      capacity: 10000
      max_samples_per_send: 2000
```

### 6.3 Alerting Strategy

**Tầng lớp Alerts:**

| Severity | Response Time | Channel | Ví dụ |
|----------|---------------|---------|-------|
| **Critical** | 5 phút | PagerDuty + Phone | Service down, Data loss risk |
| **Warning** | 30 phút | Slack | High latency, Error rate tăng |
| **Info** | Next business day | Email | Capacity planning, Trends |

**Quy tắc Alert Quality:**
1. **Actionable:** Mỗi alert phải có hành động cụ thể
2. **Relevant:** Không alert cho expected behavior
3. **Unique:** Một sự kiện = một notification
4. **Timely:** Đủ nhanh để hành động, đủ chậm để tránh false positive

### 6.4 Grafana Dashboard Design

**Template variables để reuse:**
```promql
# Query cho template variable 'service'
label_values(http_requests_total, service)

# Dashboard query sử dụng variable
rate(http_requests_total{service="$service"}[5m])
```

**Dashboard structure:**
1. **Overview row:** RED metrics cho tất cả services
2. **Service-specific row:** Chi tiết cho selected service
3. **Resource row:** USE metrics (CPU, memory, JVM)
4. **Business row:** Custom business metrics

### 6.5 Migration Strategy

**Từ legacy monitoring → Prometheus:**

| Phase | Timeline | Hoạt động |
|-------|----------|-----------|
| **1. Discovery** | 1-2 tuần | Inventory existing metrics, dashboards, alerts |
| **2. Dual-write** | 4-6 tuần | Instrument với Micrometer, chạy song song |
| **3. Migration** | 2-4 tuần | Convert dashboards, rewrite alerts, training |
| **4. Decommission** | 1-2 tuần | Remove legacy, cleanup |

---

## 7. Kết luận

### Bản chất cốt lõi

1. **Metrics là time-series data:** Append-only, high-cardinality-sensitive, optimized cho range queries
2. **Cardinality là constraint chính:** 10M series limit, mỗi label dimension nhân cardinality
3. **Histogram vs Summary:** Histogram cho aggregatable server-side quantiles, Summary cho client-side precision
4. **RED cho services, USE cho resources:** Hai framework bổ sung nhau cho complete observability
5. **Alertmanager là routing engine:** Grouping, inhibition, silencing để giảm noise

### Trade-off quan trọng nhất

| Lựa chọn | Option A | Option B | Khi nào chọn |
|----------|----------|----------|--------------|
| **Histogram buckets** | Nhiều buckets (chính xác) | Ít buckets (performance) | Chọn nhiều nếu SLO khắt khe |
| **Scrape interval** | 15s (nhanh) | 60s (nhẹ) | 15s cho dynamic, 60s cho stable |
| **Retention** | Local 30d | Remote (Thanos/Cortex) | Local cho debugging, remote cho compliance |
| **Recording rules** | Pre-compute | Real-time query | Pre-compute cho queries chậm hay dùng |

### Rủi ro lớn nhất

**Cardinality explosion** là rủi ro #1 có thể crash Prometheus và làm mất visibility toàn hệ thống. Prevention:
- Cardinality limits trong application
- Code review cho instrumentation
- Alerting trên series count tăng đột biến

---

## 8. References

- [Prometheus Documentation](https://prometheus.io/docs/introduction/overview/)
- [Micrometer Documentation](https://micrometer.io/docs)
- [Google SRE Book - Monitoring](https://sre.google/sre-book/monitoring-distributed-systems/)
- [RED Method](https://grafana.com/blog/2018/08/02/the-red-method-how-to-instrument-your-services/)
- [USE Method](http://www.brendangregg.com/usemethod.html)
- [Prometheus Best Practices](https://prometheus.io/docs/practices/)
- [Alertmanager Configuration](https://prometheus.io/docs/alerting/latest/configuration/)
