# Distributed Tracing - Nghiên Cứu Chuyên Sâu

## 1. Mục Tiêu Task

Hiểu sâu cơ chế distributed tracing trong hệ thống phân tán: kiến trúc OpenTelemetry, cách lan truyền context giữa các service, chiến lược sampling, backend storage, và các vấn đề production thực tế như overhead và correlation với logs.

> **Tầm quan trọng:** Trong hệ thống microservices, một request có thể đi qua 10-50 service. Không có distributed tracing, debug latency hoặc failure trở thành "bài toán mò kim đáy bể".

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 Trace Data Model - Nền Tảng

Distributed tracing dựa trên **3 khái niệm cốt lõi**:

| Khái niệm | Định nghĩa | Ví dụ thực tế |
|-----------|------------|---------------|
| **Trace** | Toàn bộ hành trình của một request qua hệ thống | User click "Checkout" → Gateway → Auth → Cart → Payment → Notification |
| **Span** | Một đơn vị công việc trong trace (thường = 1 service call) | `POST /api/payment` trong service Payment, duration: 150ms |
| **Span Context** | Metadata để liên kết spans (Trace ID + Span ID + Flags) | `trace_id: abc123, span_id: def456, parent_span_id: xyz789` |

**Cấu trúc Span chi tiết:**

```
Span {
  trace_id: 16-bytes (128-bit)      // UUID định danh toàn trace
  span_id: 8-bytes (64-bit)          // UUID định danh span hiện tại
  parent_span_id: 8-bytes (optional) // Liên kết parent-child
  
  name: "HTTP POST /api/orders"      // Operation name
  kind: CLIENT | SERVER | PRODUCER | CONSUMER | INTERNAL
  
  start_time: 1703275200000000000    // Unix nanoseconds
  end_time: 1703275200150000000      // Tính duration = end - start
  
  attributes: {                      // Key-value metadata
    "http.method": "POST",
    "http.status_code": 201,
    "db.statement": "INSERT INTO orders...",
    "user.id": "user_12345"
  }
  
  events: [                          // Time-stamped annotations
    { time: t1, name: "cache_miss" },
    { time: t2, name: "db_query_start" },
    { time: t3, name: "db_query_end" }
  ]
  
  status: OK | ERROR | UNSET
  links: [...]                       // Cross-trace references (async)
}
```

> **Quan trọng:** Trace ID phải được sinh ở **entry point** (gateway/edge) và lan truyền xuyên suốt. Nếu mỗi service tự sinh trace_id mới, trace bị "gãy" thành nhiều mảnh riêng lẻ.

---

### 2.2 Context Propagation - Cơ Chế Lan Truyền

Vấn đề cốt lõi: Làm sao để span của service B biết nó là con của span từ service A?

#### W3C Trace Context (Chuẩn hiện đại - Recommend)

```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
             │─│ └────── 32 hex chars = trace_id ──────┘ └─span_id─┘ │
             │─│                                                      │
             │ └─ version (00)                                flags (01 = sampled)
             └─── format indicator
```

**Header:** `traceparent: 00-<trace_id>-<parent_span_id>-<flags>`

**Ví dụ flow:**

```
Client Browser          API Gateway           Order Service          Payment Service
     │                       │                       │                       │
     │ POST /checkout        │                       │                       │
     ├──────────────────────>│                       │                       │
     │ traceparent: 00-abc..-xyz..-01                │                       │
     │                       │ POST /orders          │                       │
     │                       ├──────────────────────>│                       │
     │                       │ traceparent: 00-abc..-new_span..-01           │
     │                       │                       │ POST /payment         │
     │                       │                       ├──────────────────────>│
     │                       │                       │ traceparent: 00-abc..-pay_span..-01
```

**Tracestate (bổ sung metadata vendor-specific):**
```
tracestate: vendor1=value1,vendor2=value2
```

#### B3 Propagation (Zipkin - Legacy nhưng phổ biến)

```
X-B3-TraceId: 463ac35c9f6413ad48485a3953bb6124
X-B3-SpanId: a2fb4a1d1a96d312
X-B3-ParentSpanId: 0020000000000001
X-B3-Sampled: 1          // 0 = drop, 1 = sampled, d = debug (force sample)
X-B3-Flags: 1            // 1 = debug
```

**So sánh W3C vs B3:**

| Tiêu chí | W3C Trace Context | B3 (Zipkin) |
|----------|-------------------|-------------|
| **Chuẩn hóa** | W3C Recommendation (2020) | Zipkin de-facto |
| **Format** | Single header, hyphen-separated | Multiple headers |
| **Trace ID** | 32 hex (128-bit) | 16 hoặc 32 hex |
| **Span ID** | 16 hex (64-bit) | 16 hex (64-bit) |
| **Flags** | Bitmask (sampled=bit-0) | Integer (0/1/d) |
| **Tracestate** | Có, hỗ trợ vendor metadata | Không có |
| **Support** | Jaeger, OTel, AWS X-Ray, GCP | Zipkin, Brave, OTel |

> **Khuyến nghị:** Dùng W3C cho hệ thống mới. Nếu có legacy Zipkin, OTel Collector có thể translate giữa các format.

#### Baggage - Lan Truyền Business Context

Baggage cho phép attach key-value pairs đi kèm trace context:

```
baggage: userId=12345,tenantId=acmeCorp,region=ap-southeast-1
```

**Use cases:**
- Carry `userId`, `tenantId` qua tất cả services để filtering/debug
- A/B testing metadata
- Feature flag context

**Trade-off:** Baggage phải được parse ở mỗi hop → overhead. **Không nên** để > 1-2KB.

---

### 2.3 OpenTelemetry Collector - Kiến Trúc Trung Tâm

OTel Collector là **data pipeline** độc lập, tách biệt instrumentation khỏi backend.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     OpenTelemetry Collector                              │
│                                                                          │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌───────────┐ │
│  │  Receivers  │───>│  Processors │───>│   Exporters │───>│ Backends  │ │
│  │             │    │             │    │             │    │           │ │
│  │ OTLP (gRPC) │    │ Batch       │    │ Jaeger      │    │ Jaeger    │ │
│  │ OTLP (HTTP) │    │ Memory Limit│    │ Zipkin      │    │ Zipkin    │ │
│  │ Zipkin      │    │ Attributes  │    │ Prometheus  │    │ Tempo     │ │
│  │ Jaeger      │    │ Resource    │    │ OTLP        │    │ Datadog   │ │
│  │ Prometheus  │    │ Sampling    │    │ Kafka       │    │ ...       │ │
│  └─────────────┘    └─────────────┘    └─────────────┘    └───────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

**Cấu hình pipeline (YAML):**

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  batch:                    # Gom spans thành batch để giảm network calls
    timeout: 1s
    send_batch_size: 1024
  memory_limiter:           # Bảo vệ collector khỏi OOM
    limit_mib: 512
    spike_limit_mib: 128
  resource:                 # Thêm metadata
    attributes:
      - key: environment
        value: production
        action: upsert

exporters:
  jaeger:
    endpoint: jaeger:14250
    tls:
      insecure: true
  prometheusremotewrite:
    endpoint: http://prometheus:9090/api/v1/write

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, resource, batch]
      exporters: [jaeger]
    metrics:
      receivers: [otlp]
      processors: [batch]
      exporters: [prometheusremotewrite]
```

**Vai trò của Collector:**

1. **Vendor-agnostic instrumentation:** App chỉ gửi OTLP → Collector xử lý routing đến backend bất kỳ
2. **Centralized configuration:** Thay đổi sampling, filtering mà không cần redeploy app
3. **Data enrichment:** Thêm host metadata, k8s attributes
4. **Reliability:** Retry, queuing, backpressure handling

---

## 3. Sampling Strategies - Chiến Lược Lấy Mẫu

Trong hệ thống high-traffic, trace 100% requests là không khả thi:
- **Cost:** Storage tăng linear với traffic
- **Overhead:** CPU/memory instrumentation
- **Diminishing returns:** Trace thứ 1 triệu của cùng một endpoint pattern ít giá trị

### 3.1 Head-Based Sampling (Quyết định ở entry point)

```
Request vào Gateway
        │
        ▼
┌───────────────┐
│ Random() < 1% │  ◄── Quyết định NGAY ở entry
│    (1%)       │      và propagate "sampled" flag
└───────┬───────┘
        │
   ┌────┴────┐
   ▼         ▼
sampled   dropped
 (1%)      (99%)
   │         │
   ▼         ▼
 Propagate Không trace
 sampled=1
```

**Ưu điểm:**
- Simple, consistent
- Mọi service biết ngay có cần trace không
- Không overhead cho dropped requests

**Nhược điểm:**
- Không biết được trace nào "interesting" (lỗi, high latency) để sample
- Nếu 1% sample rate, trace của request 99th percentile latency sẽ bị miss 99% thời gian

**Cấu hình OTel:**

```yaml
processors:
  probabilistic_sampler:    # Head-based
    sampling_percentage: 1.0
    hash_seed: 22
```

### 3.2 Tail-Based Sampling (Quyết định sau khi hoàn thành)

```
Request qua các service
        │
        ▼
┌───────────────┐    ┌───────────────┐    ┌───────────────┐
│   Service A   │───>│   Service B   │───>│   Service C   │
│  (span data)  │    │  (span data)  │    │  (span data)  │
└───────┬───────┘    └───────┬───────┘    └───────┬───────┘
        │                    │                    │
        └────────────────────┼────────────────────┘
                             ▼
                    ┌─────────────────┐
                    │ Collector Buffer│  ◄── Tạm lưu spans
                    │   (in-memory)   │      cho đến khi trace complete
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ Decision Engine │  ◄── Evaluate policies
                    │  (tail-based)   │      sau khi có full trace
                    └────────┬────────┘
                             │
                   ┌─────────┴─────────┐
                   ▼                   ▼
            Match policy         Không match
            (error=true          (bình thường)
            OR latency>500ms)
                   │                   │
                   ▼                   ▼
              Keep 100%            Drop/Có thể
                                    rate limit
```

**Policies phổ biến:**

```yaml
processors:
  tail_sampling:
    decision_wait: 10s              # Chờ spans trong 10s trước khi quyết định
    num_traces: 100000              # Max traces in memory
    expected_new_traces_per_sec: 1000
    
    policies:
      - name: errors                # Luôn giữ traces có error
        type: status_code
        status_code: {status_codes: [ERROR]}
      
      - name: slow_requests         # Giữ traces chậm
        type: latency
        latency: {threshold_ms: 500}
      
      - name: probabilistic         # + random sampling
        type: probabilistic
        probabilistic: {sampling_percentage: 10}
```

**Ưu điểm:**
- Có thể giữ 100% traces "interesting" (errors, high latency)
- Hiệu quả storage hơn - sample ra những gì thực sự cần debug

**Nhược điểm:**
- **Complexity:** Cần buffer traces in-memory trong Collector
- **Memory risk:** Nếu trace không complete (timeout), buffer leak
- **Delay:** Phải chờ `decision_wait` trước khi export

### 3.3 So Sánh Chiến Lược Sampling

| Chiến lược | Khi nào dùng | Trade-off | Risk |
|------------|--------------|-----------|------|
| **Head-based (1%)** | High volume (>10K req/s), uniform traffic | Simple, low overhead | Miss rare events (errors <1%) |
| **Head-based (100%)** | Low volume, critical path | Complete visibility | High cost, high overhead |
| **Tail-based** | Need visibility into errors/outliers | Smart sampling | Memory pressure, complexity |
| **Adaptive** | Traffic pattern thay đổi | Auto-adjust rate | Complex tuning |

> **Anti-pattern:** "We'll just sample 0.1% and hope we catch errors" → Errors thường < 0.1%, bạn sẽ miss hầu hết.

> **Best practice:** Dùng tail-based cho error paths + head-based cho normal traffic.

---

## 4. Backend Storage - Jaeger vs Tempo

### 4.1 Jaeger Architecture

```
Jaeger Collector ──> Storage Backend
       │                    │
       │            ┌───────┴───────┐
       │            ▼               ▼
       │      in-memory      Elasticsearch/Cassandra
       │      (dev/test)     (production - search)
       │
       ▼
Jaeger Query ◄─── UI/API
```

**Storage options:**

| Backend | Use case | Trade-off |
|---------|----------|-----------|
| **in-memory** | Dev/testing | Mất data khi restart, không scalable |
| **Badger** | Single-node | Embedded, simple, không distributed |
| **Elasticsearch** | Search-heavy | Query mạnh, expensive ở scale lớn |
| **Cassandra** | Write-heavy | Scalable, eventual consistency |
| **Kafka** | Streaming | Buffer, không query trực tiếp |

**Jaeger Query capabilities:**
- Search by service, operation, tags, duration, time range
- Trace view (waterfall)
- Service dependencies graph
- Compare traces

### 4.2 Grafana Tempo (Object Storage-native)

Tempo khác biệt: **"Store everything, index nothing (or minimal)"**

```
┌─────────────────────────────────────────────────────────────┐
│                       Tempo Architecture                     │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│   Distributors ──> Ingester ──> Backend Storage              │
│        │                              │                      │
│        │                        ┌─────┴─────┐                │
│        │                        ▼           ▼                │
│        │                   S3/GCS/Azure  Memcached/Redis      │
│        │                   (trace data)    (index)            │
│        │                                                     │
│        └──────────────> Compactor ──> Index/Block management │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

**Design philosophy:**

| Traditional (Jaeger/ES) | Tempo approach |
|-------------------------|----------------|
| Index mọi span attributes | Chỉ index trace ID |
| Search by tags | Search bằng external tool (Loki metrics) |
| Expensive storage | Cheap object storage (S3) |
| Query latency thấp | Query latency cao hơn nhưng acceptable |

**Tempo query pattern:**

```
Loki logs ──> Extract traceID ──> Query Tempo by traceID
    │                              │
    │                              ▼
    │                         Full trace data
    │                         (waterfall view)
    ▼
"Error rate by service"
"Latency percentile"
```

### 4.3 So Sánh Jaeger vs Tempo

| Tiêu chí | Jaeger (ES-backed) | Grafana Tempo |
|----------|-------------------|---------------|
| **Storage cost** | Cao (index all) | Thấp (S3 object storage) |
| **Search capability** | Mạnh (ad-hoc query) | Hạn chế (traceID lookup) |
| **Query latency** | Thấp | Cao hơn (object storage) |
| **Scale** | TBs | PBs |
| **Best for** | Dev/debug, explore unknown | Production, known queries |
| **Integration** | Native Jaeger | Grafana ecosystem |

> **Khuyến nghị:**
> - Development/Staging: Jaeger (dễ explore)
> - Production high-volume: Tempo (cost-effective)
> - Hoặc dùng cả hai: Jaeger cho real-time debug, Tempo cho long-term storage

---

## 5. Trace-to-Log Correlation

### 5.1 Vấn đề

Logs và traces là hai hệ thống data riêng biệt:
- Logs: Text, event-driven, high volume
- Traces: Structured, span-based, request-scoped

**Correlation goal:** Từ trace → xem logs liên quan. Từ log line → xem full request trace.

### 5.2 Cơ Chế Correlation

**Inject trace context vào logs:**

```java
// MDC (Mapped Diagnostic Context) - SLF4J/Logback
MDC.put("trace_id", span.getSpanContext().getTraceId());
MDC.put("span_id", span.getSpanContext().getSpanId());

// Log output (JSON structured)
{
  "timestamp": "2024-01-15T10:30:00Z",
  "level": "ERROR",
  "message": "Payment failed: insufficient funds",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "span_id": "00f067aa0ba902b7",
  "service": "payment-service",
  "user_id": "12345"
}
```

**LogQL query (Loki):**
```
{app="payment-service"} 
  | json 
  | trace_id="4bf92f3577b34da6a3ce929d0e0e4736"
```

### 5.3 Exemplars - Liên Kết Metrics → Traces

Exemplars là trace samples được embed vào metrics:

```
http_request_duration_seconds_bucket{le="0.5"} 1000 # @1743086400 trace_id=abc123 span_id=def456
http_request_duration_seconds_bucket{le="1.0"} 1500 # @1743086410 trace_id=xyz789 span_id=uvw012
```

**Workflow:**
1. Metrics show p99 latency spike
2. Click exemplar trong Grafana → jump đến trace tương ứng
3. Trace cho context chi tiết: spans, attributes
4. Logs cho chi tiết business logic

> **Tóm lại:** Metrics (WHAT) → Exemplars (WHICH) → Traces (WHERE) → Logs (WHY)

---

## 6. Rủi Ro, Anti-Patterns và Lỗi Thường Gặp

### 6.1 Context Propagation Failures

| Lỗi | Triệu chứng | Nguyên nhân | Fix |
|-----|-------------|-------------|-----|
| **Broken trace** | Trace gãy thành nhiều phần | Context không propagate qua async/thread pool | Dùng context executor hoặc manual propagate |
| **Missing outbound** | Trace chỉ có 1 span | HTTP client không inject headers | Cấu hình instrumentation cho HTTP client |
| **Duplicated trace_id** | Multiple traces cùng ID | Random seed không tốt hoặc clock skew | Dùng crypto-secure random generator |
| **Orphan spans** | Span không có parent | Service tạo span mới thay vì extract context | Kiểm tra propagation config |

**Ví dụ async context propagation (Java):**

```java
// ❌ Sai: Context bị mất khi submit async task
executor.submit(() -> {
    // Span này sẽ là orphan - không có parent
    Span span = tracer.spanBuilder("async-task").startSpan();
});

// ✅ Đúng: Propagate context
Context context = Context.current();
executor.submit(Context.current().wrap(() -> {
    // Context được restore, parent relationship maintained
    Span span = tracer.spanBuilder("async-task").startSpan();
}));
```

### 6.2 Sampling Anti-Patterns

**1. "Sample the errors" (Head-based pitfall):**
```
if (response.isError()) {
    span.setAttribute("sampled", true);  // Too late!
}
```
- Head-based đã quyết định ở entry → không thể "sample more" ở downstream

**2. Per-service sampling:**
- Service A sample 10%, Service B sample 50%
- Result: Incomplete traces, parent sampled nhưng child không

**3. Not handling unsampled:**
```java
// ❌ Overhead dù không sample
Span span = tracer.spanBuilder("expensive-op").startSpan();
span.setAttribute("large_payload", serializeHugeObject());  // Waste!

// ✅ Check sampling trước
if (Span.current().getSpanContext().isSampled()) {
    // Only do expensive work when sampled
}
```

### 6.3 Instrumentation Overhead

**Measured overhead (typical):**

| Operation | Baseline | With Tracing | Overhead |
|-----------|----------|--------------|----------|
| Simple HTTP request | 5ms | 5.5ms | ~10% |
| Complex DB query | 100ms | 102ms | ~2% |
| High-throughput service | 10K req/s | 9.5K req/s | ~5% CPU |

**Factors affecting overhead:**
1. **Span creation:** Nếu tạo span cho mọi method → overhead cao
2. **Attribute serialization:** Large payloads, deep objects
3. **Export frequency:** Batch size, timeout settings
4. **Network I/O:** Collector latency, retries

**Mitigation:**
- Sampling (obviously)
- Batch export (mặc định OTel là 512 spans/batch)
- Async export (không block request path)
- Limit attributes (chỉ các attributes quan trọng)

### 6.4 Cardinality Explosion

**Vấn đề:** Thêm high-cardinality attributes vào spans:

```java
// ❌ Cardinality explosion
span.setAttribute("user_id", userId);           // 1M unique values
span.setAttribute("request_id", requestId);     // 1M unique values
span.setAttribute("session_id", sessionId);     // 1M unique values
```

**Hậu quả:**
- Backend index bloat
- Query performance degradation
- Storage cost tăng đột biến

**Best practice:**
- Chỉ dùng low-cardinality attributes cho search: `http.method`, `http.status_code`, `service.name`
- High-cardinality để ở logs: `user_id`, `request_id`, `transaction_id`

---

## 7. Khuyến Nghị Thực Chiến Production

### 7.1 Deployment Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                         Kubernetes Cluster                      │
│                                                                 │
│  ┌──────────────┐                                              │
│  │  Ingress/    │                                              │
│  │  API Gateway │  ◄── Sinh trace_id, inject context           │
│  └──────┬───────┘                                              │
│         │ traceparent                                           │
│  ┌──────┴───────┐    ┌──────────────┐    ┌──────────────┐      │
│  │  Service A   │───>│  Service B   │───>│  Service C   │      │
│  │  (app +      │    │  (app +      │    │  (app +      │      │
│  │   OTel SDK)  │    │   OTel SDK)  │    │   OTel SDK)  │      │
│  └──────┬───────┘    └──────┬───────┘    └──────┬───────┘      │
│         │ OTLP/gRPC         │ OTLP/gRPC         │              │
│         └───────────────────┼───────────────────┘              │
│                             ▼                                  │
│                    ┌─────────────────┐                         │
│                    │  OTel Collector │  ◄── DaemonSet/Sidecar  │
│                    │   (DaemonSet)   │      trên mỗi node      │
│                    └────────┬────────┘                         │
│                             │                                  │
│              ┌──────────────┼──────────────┐                   │
│              ▼              ▼              ▼                   │
│          ┌──────┐      ┌────────┐      ┌────────┐              │
│          │Tempo │      │Prometheus│    │  Loki  │              │
│          │(trace│      │(metrics) │    │ (logs) │              │
│          │store)│      └────────┘      └────────┘              │
│          └──────┘                                              │
└────────────────────────────────────────────────────────────────┘
```

### 7.2 Configuration Best Practices

**Sampling strategy cho production:**

```yaml
# 1. Default: Head-based với rate cao ở dev, thấp ở prod
processors:
  probabilistic_sampler:
    sampling_percentage: ${SAMPLING_RATE:1.0}  # 1% prod, 100% dev

# 2. Error-biased: Luôn sample errors
  tail_sampling:
    policies:
      - name: errors
        type: status_code
        status_code: {status_codes: [ERROR]}
      - name: slow
        type: latency
        latency: {threshold_ms: ${SLOW_THRESHOLD:500}}
      - name: normal
        type: probabilistic
        probabilistic: {sampling_percentage: 0.5}
```

**Resource attributes (auto-detect):**

```yaml
processors:
  resource:
    attributes:
      - key: k8s.cluster.name
        from_attribute: k8s.cluster.name
        action: upsert
      - key: k8s.namespace.name
        from_attribute: k8s.namespace.name
        action: upsert
      - key: k8s.pod.name
        from_attribute: k8s.pod.name
        action: upsert
      - key: deployment.environment
        value: ${ENV:production}
        action: upsert
```

### 7.3 Alerting và SLOs

**Trace-related SLIs:**

| SLI | Target | Measurement |
|-----|--------|-------------|
| Trace coverage | >95% | % requests có trace header |
| Sampling accuracy | >99% | Sampled rate ≈ configured rate |
| Collector availability | 99.9% | Uptime của OTel Collector |
| Trace completeness | >90% | % spans không orphan |
| Export latency | <5s | P99 time from span creation → backend |

**Alert rules:**

```yaml
# Collector queue buildup
groups:
  - name: tracing
    rules:
      - alert: CollectorQueueHigh
        expr: otelcol_exporter_queue_size > 1000
        for: 5m
        
      - alert: TraceExportErrors
        expr: rate(otelcol_exporter_send_failed_spans[5m]) > 10
        
      - alert: OrphanSpansHigh
        expr: rate(jaeger_orphan_spans_total[5m]) > 100
```

### 7.4 Security Considerations

1. **PII in traces:** Không để email, phone, SSN vào span attributes
   - Dùng sanitization processors trong Collector
   - Hoặc dùng attribute allow-list

2. **Trace context forgery:** Validate traceparent format, reject malformed

3. **Collector authentication:**
   - mTLS giữa SDK và Collector
   - API keys cho external services

4. **Retention policies:**
   - Personal data: 30 days (GDPR)
   - Debug traces: 7 days
   - Aggregated metrics: 1 year

---

## 8. Kết Luận

### Bản Chất Cốt Lõi

Distributed tracing không chỉ là "logging on steroids". Nó là **causal tracking system** - theo dõi quan hệ nhân-quả giữa các operations trong hệ thống phân tán.

**3 pillars của tracing:**
1. **Context propagation:** Lan truyền metadata xuyên suốt (traceparent)
2. **Span modeling:** Đo thời gian và quan hệ parent-child
3. **Sampling:** Trade-off giữa visibility và cost

### Trade-off Quan Trọng Nhất

| Aspect | Trade-off |
|--------|-----------|
| **Storage** | Head-based (cheap, dumb) vs Tail-based (expensive, smart) |
| **Query** | Jaeger (searchable, expensive) vs Tempo (traceID-only, cheap) |
| **Overhead** | 100% tracing (complete, slow) vs 1% sampling (partial, fast) |
| **Complexity** | Auto-instrumentation (easy, less detail) vs Manual (hard, complete) |

### Rủi Ro Lớn Nhất

1. **Context propagation failures** → Broken traces, orphan spans
2. **Cardinality explosion** → Backend meltdown, cost spike
3. **Wrong sampling strategy** → Miss critical events hoặc drown in data
4. **PII leakage** → Compliance violation, security incident

### Checklist Production

- [ ] W3C trace context ở entry points
- [ ] Async/thread pool context propagation tested
- [ ] Tail-based sampling cho errors + high latency
- [ ] Cardinality limits enforced
- [ ] PII sanitization trong pipeline
- [ ] Exemplars kết nối metrics → traces
- [ ] MDC logging với trace_id
- [ ] Collector HA deployment (multi-replica)
- [ ] Retention policies configured
- [ ] Alert rules cho tracing infrastructure

---

*Document version: 1.0*
*Research date: 2026-03-27*
*Scope: OpenTelemetry, Jaeger, Tempo, W3C Trace Context, B3*
