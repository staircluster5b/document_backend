# Distributed Tracing: OpenTelemetry, Jaeger & Trace Propagation

## 1. Mục tiêu của Task

Hiểu sâu bản chất distributed tracing - cơ chế theo dõi request xuyên suốt hệ thống phân tán, từ đó thiết kế và vận hành hệ thống observability production-grade có khả năng debug latency, identify bottlenecks và troubleshoot distributed failures hiệu quả.

---

## 2. Bản chất và Cơ chế Hoạt động

### 2.1. Vấn đề cơ bản: Tại sao cần Distributed Tracing?

Trong monolith, một request = một process. Debug đơn giản: stack trace đầy đủ, logs tuần tự.

Trong microservices, một request = N service calls qua network. Problems:
- **Latency ambiguity**: Tổng latency 500ms, nhưng service nào gây ra?
- **Failure propagation**: Service A lỗi nhưng root cause ở service C?
- **Concurrency complexity**: Multiple parallel calls, retry storms, cascading failures
- **Context loss**: Mỗi service chỉ thấy "slice" của request

> **Distributed tracing sinh ra để reconstruct "story" của một request xuyên suốt toàn bộ hệ thống.**

### 2.2. Data Model: Trace, Span, Context

**Trace**: Một end-to-end user request (ví dụ: "User checkout order")
- Global unique ID: `trace_id` (16 bytes, W3C format)
- Represented as DAG (Directed Acyclic Graph) of spans

**Span**: Một unit of work trong trace (ví dụ: "POST /api/orders", "SELECT * FROM products")
```
Span Structure:
├── span_id (8 bytes, unique within trace)
├── parent_span_id (8 bytes, null for root)
├── trace_id (16 bytes)
├── name (operation name)
├── start_time / end_time
├── status (Ok, Error, Unset)
├── attributes (key-value metadata)
├── events (timestamped logs within span)
└── links (references to other spans)
```

**Context**: Trạng thái "in-flight" của trace
- Must be propagated across process boundaries
- Carried in headers (HTTP/gRPC), message metadata (Kafka), or async context

### 2.3. Trace Context Propagation: Cơ chế cốt lõi

Đây là phần **quan trọng nhất** - tracing chỉ hoạt động nếu context được truyền đúng.

**Propagation Models:**

| Model | Use Case | Trade-off |
|-------|----------|-----------|
| **In-Process** | Same JVM, async threads | ThreadLocal, Reactor Context, Kotlin Coroutines |
| **Inter-Process** | HTTP/gRPC calls | Headers: W3C traceparent, tracestate |
| **Message Queue** | Kafka, RabbitMQ | Message headers, có thể bị strip bởi consumer |
| **Database** | Stored procedures | Limited support, thường dùng comment-based |

**W3C Trace Context Standard** (de facto standard):
```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
            ^^ ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ ^^^^^^^^^^^^^^^^ ^^
            |  |                                |                |
         version                          trace_id          parent_span_id
                                                             flags (sampled)

tracestate: vendor=value1,rojo=00f067aa0ba902b7
```

**Propagation Flow:**
```
┌─────────────┐     HTTP Request      ┌─────────────┐
│   Client    │ ─────────────────────>│   Service A  │
│ (trace_id=T)│  traceparent: T,spanA │  span_id=A   │
└─────────────┘                       └──────┬──────┘
                                             │
                    ┌────────────────────────┼────────────────────────┐
                    │                        │                        │
                    ▼                        ▼                        ▼
            ┌─────────────┐          ┌─────────────┐          ┌─────────────┐
            │  Service B   │          │  Service C   │          │   Kafka     │
            │ span_id=B   │          │ span_id=C   │          │  (message)  │
            └─────────────┘          └─────────────┘          └─────────────┘
```

### 2.4. Sampling: Quyết định trace nào được giữ lại

**Vấn đề**: 100% tracing = overhead lớn (10-30% latency, memory). Cần smart sampling.

**Sampling Strategies:**

| Strategy | Cách hoạt động | Ưu điểm | Nhược điểm |
|----------|----------------|---------|------------|
| **Head-based** | Quyết định tại entry point (gateway) | Đơn giản, consistent | Không biết sẽ error hay không |
| **Tail-based** | Collect tất cả spans, quyết định sau khi complete | Capture 100% errors/latency outliers | Tốn memory, complex |
| **Probability** | Random sampling (e.g., 1%) | Predictable overhead | Có thể miss rare errors |
| **Rate-limited** | Fixed traces/second | Bounded overhead | Burst traffic = under-sample |

**Production Recommendation**:
- Head-based sampling mặc định 1-10%
- Tail-based hoặc always-on sampling cho errors/high-latency requests
- Adaptive sampling (OpenTelemetry Adaptive Sampling Processor)

---

## 3. Kiến trúc Hệ thống Tracing

### 3.1. Component Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Application Layer                            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │   Auto-      │  │   Manual     │  │   Library    │              │
│  │   Instrument │  │   Instrument │  │   Instrument │              │
│  │  (Agent/SDK) │  │  (Custom)    │  │ (Framework)  │              │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘              │
└─────────┼─────────────────┼─────────────────┼──────────────────────┘
          │                 │                 │
          └─────────────────┼─────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      OpenTelemetry SDK                               │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │
│  │   Tracer    │  │  Sampler    │  │  Processor  │  │  Exporter   │ │
│  │  Provider   │  │             │  │   (Batch)   │  │  (OTLP)     │ │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
                            │
                            ▼ OTLP (gRPC/HTTP)
┌─────────────────────────────────────────────────────────────────────┐
│                      Collector Layer                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │
│  │  Receiver   │  │  Processor  │  │   Batch     │  │   Exporter  │ │
│  │  (OTLP)     │  │  (Filter)   │  │   Queue     │  │  (Jaeger)   │ │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      Storage Layer                                   │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                  │
│  │   Jaeger    │  │   Tempo     │  │   ClickHouse│                  │
│  │  (Badger/   │  │  (object    │  │  (columnar) │                  │
│  │   ES)       │  │   storage)  │  │             │                  │
│  └─────────────┘  └─────────────┘  └─────────────┘                  │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2. OpenTelemetry: Unified Observability Standard

**Lịch sử**: Merge của OpenTracing (CNCF) + OpenCensus (Google) → OpenTelemetry (CNCF)

**Kiến trúc 3 pillars**:
1. **Traces**: Request flows (distributed tracing)
2. **Metrics**: Aggregated measurements (counters, gauges, histograms)
3. **Logs**: Discrete events (structured logging)

**Correlation**: Exemplars (link metrics → traces)

### 3.3. Jaeger Architecture (Production Deployment)

```
┌─────────────────────────────────────────────────────────────────┐
│                         Jaeger Query                            │
│                    (REST API + Web UI)                          │
└────────────────────────────┬────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│                      Jaeger Collector                           │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             │
│  │   SPAN      │  │   QUEUE     │  │   WRITER    │             │
│  │  PROCESSOR  │  │  (Kafka/    │  │  (Storage)  │             │
│  │             │  │  In-Mem)    │  │             │             │
│  └─────────────┘  └─────────────┘  └─────────────┘             │
└─────────────────────────────────────────────────────────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        ▼                    ▼                    ▼
┌───────────────┐   ┌───────────────┐   ┌───────────────┐
│  Badger/      │   │ Elasticsearch │   │    Kafka      │
│  Cassandra    │   │   (indices)   │   │   (streaming) │
│  (embedded)   │   │               │   │               │
└───────────────┘   └───────────────┘   └───────────────┘
```

**Storage Backends Comparison**:

| Backend | Scale | Query Speed | Ops Complexity | Use Case |
|---------|-------|-------------|----------------|----------|
| **Badger** | Single node | Fast | Low | Dev/Test, small prod |
| **Cassandra** | Multi-node | Medium | High | Large scale, write-heavy |
| **Elasticsearch** | Multi-node | Fast | Medium | Complex queries, analytics |
| **Kafka + ClickHouse** | Massive | Very Fast | Very High | Enterprise, real-time analytics |

---

## 4. So sánh các Giải pháp Tracing

### 4.1. OpenTelemetry vs Jaeger vs Zipkin

| Aspect | OpenTelemetry | Jaeger | Zipkin |
|--------|---------------|--------|--------|
| **Role** | SDK/Standard | Backend/UI | Backend/UI |
| **Instrumentation** | ✅ Auto + Manual | Via OTEL | Via Brave |
| **Language Support** | 11+ languages | Via OTEL | JVM-focused |
| **Storage Options** | Pluggable | ES/Cassandra/Badger | ES/Cassandra/MySQL |
| **Sampling** | Sophisticated | Head/Tail-based | Basic |
| **Service Dependencies** | Via Jaeger/Tempo | ✅ Built-in | ✅ Built-in |
| **Log Correlation** | ✅ Native | Via OTEL | Limited |

**Modern Stack Recommendation**: OpenTelemetry SDK → OTLP → Jaeger/Tempo

### 4.2. Jaeger vs Grafana Tempo

| Feature | Jaeger | Grafana Tempo |
|---------|--------|---------------|
| **Architecture** | Separate services | Single binary possible |
| **Storage** | Requires ES/Cassandra | Object storage (S3/GCS) |
| **Cost** | Higher (compute + storage) | Lower (S3 is cheap) |
| **Query** | Jaeger UI, limited Grafana | Native Grafana integration |
| **Trace by ID** | ✅ Fast | ✅ Very fast |
| **Search/Filter** | ✅ Powerful | Limited (needs TraceQL) |
| **Scaling** | Complex | Simpler |

**Decision Matrix**:
- Need powerful search → Jaeger + Elasticsearch
- Cost-sensitive, already using Grafana → Tempo + S3

---

## 5. Rủi ro, Anti-patterns, Lỗi thường gặp

### 5.1. High Cardinality Attributes

**Vấn đề**: `user_id`, `request_id` as span attributes = cardinality explosion

**Impact**: Storage explosion, query performance degradation, memory issues

**Solution**:
```
❌ WRONG:
span.setAttribute("user.id", userId)  // Millions of unique values

✅ RIGHT:
span.setAttribute("user.tier", userTier)  // 3-5 values: free/pro/enterprise
```

**Cardinality Guidelines**:
- Low (< 100): service.name, http.method, error.type
- Medium (100-1000): http.route, db.table
- **NEVER**: user IDs, session IDs, timestamps as attributes

### 5.2. Broken Context Propagation

**Triệu chứng**: Trace "breaks" giữa các service, orphan spans

**Nguyên nhân phổ biến**:
1. Missing tracing interceptor/middleware
2. Async calls không propagate context
3. Message queue consumer strip headers
4. Custom HTTP client không inject headers

**Detection**:
```bash
# Orphan spans in Jaeger
jaeger_analytics orphan_spans rate > 0.01
```

**Fix**:
```java
// Spring WebClient with OTEL context propagation
webClient.get()
    .uri("/api/service")
    .headers(headers -> {
        // ContextPropagation automatically handles this
        // Just ensure WebClient instrumentation is enabled
    })
    .retrieve()
    .bodyToMono(String.class);

// Manual propagation if needed
TextMapSetter<HttpHeaders> setter = (headers, key, value) -> headers.add(key, value);
W3CTraceContextPropagator.getInstance().inject(context, headers, setter);
```

### 5.3. Tracing Overhead

**Measurements thực tế**:
- CPU overhead: 2-5% (auto-instrumentation)
- Memory overhead: Buffers ~1000 spans default
- Latency overhead: 1-3ms per span (export async)

**Mitigation**:
- Batch span processor (export mỗi 100 spans hoặc 5s)
- P1 sampling for production
- Async export (không block request path)

### 5.4. Trace-Log Mismatch

**Vấn đề**: Traces và logs không correlate được

**Root causes**:
- Different trace IDs between systems
- Missing trace_id trong log MDC
- Clock skew giữa services

**Solution**:
```java
// Logback configuration
default.xml:
<appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
        <pattern>%d{HH:mm:ss.SSS} [%thread] trace_id=%X{trace_id} span_id=%X{span_id} %-5level %logger{36} - %msg%n</pattern>
    </encoder>
</appender>

// Spring Boot + Micrometer Tracing automatically injects
logging.pattern.level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
```

---

## 6. Khuyến nghị Thực chiến trong Production

### 6.1. Deployment Strategy

**Phase 1: Low Risk Entry** (Week 1-2)
```yaml
# docker-compose for dev/testing
services:
  jaeger:
    image: jaegertracing/all-in-one:latest
    environment:
      - COLLECTOR_OTLP_ENABLED=true
    ports:
      - "16686:16686"  # UI
      - "4317:4317"    # OTLP gRPC
      - "4318:4318"    # OTLP HTTP
```

**Phase 2: Production-Ready** (Week 3-4)
```yaml
# Kubernetes deployment
apiVersion: v1
kind: ConfigMap
metadata:
  name: otel-collector-config
data:
  otel-collector-config.yaml: |
    receivers:
      otlp:
        protocols:
          grpc:
            endpoint: 0.0.0.0:4317
          http:
            endpoint: 0.0.0.0:4318
    processors:
      batch:
        timeout: 1s
        send_batch_size: 1024
      memory_limiter:
        limit_mib: 512
    exporters:
      jaeger:
        endpoint: jaeger-collector:14250
        tls:
          insecure: true
    service:
      pipelines:
        traces:
          receivers: [otlp]
          processors: [memory_limiter, batch]
          exporters: [jaeger]
```

**Phase 3: Enterprise Scale**
- Dedicated OpenTelemetry Collector fleet
- Kafka as buffer layer
- Separate hot/warm storage (recent traces in fast storage)

### 6.2. Java Spring Boot Integration

```gradle
// build.gradle
dependencies {
    implementation 'io.micrometer:micrometer-tracing-bridge-otel'
    implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
}
```

```yaml
# application.yml
management:
  tracing:
    sampling:
      probability: 0.1  # 10% sampling
    propagation:
      type: w3c  # W3C tracecontext
  otlp:
    tracing:
      endpoint: http://otel-collector:4318/v1/traces
  endpoints:
    web:
      exposure:
        include: health,info,prometheus

logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
```

**Auto-instrumentation covers**:
- Spring MVC/WebFlux HTTP requests
- Spring Data JDBC/JPA queries
- Spring Cloud Gateway routing
- RestTemplate/WebClient calls
- @Scheduled methods
- @Async methods (with proper config)

### 6.3. Custom Instrumentation Patterns

```java
@Service
public class PaymentService {
    
    private final Tracer tracer;
    private final PaymentGatewayClient gatewayClient;
    
    public PaymentResult processPayment(Order order) {
        Span span = tracer.spanBuilder("payment.process")
            .setAttribute("payment.amount", order.getAmount())
            .setAttribute("payment.currency", order.getCurrency())
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan();
            
        try (Scope scope = span.makeCurrent()) {
            // Validation phase
            Span validationSpan = tracer.spanBuilder("payment.validate")
                .setParent(Context.current().with(span))
                .startSpan();
            try {
                validateOrder(order);
            } catch (Exception e) {
                validationSpan.recordException(e);
                validationSpan.setStatus(StatusCode.ERROR, "Validation failed");
                throw e;
            } finally {
                validationSpan.end();
            }
            
            // External call
            return gatewayClient.charge(order);
            
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }
}
```

### 6.4. Alerting & SLOs

**Critical Metrics**:
```promql
# Error rate by service
sum(rate(traces_spanmetrics_latency_count{status_code="STATUS_CODE_ERROR"}[5m])) by (service_name)
/
sum(rate(traces_spanmetrics_latency_count[5m])) by (service_name)

# P99 latency by operation
histogram_quantile(0.99, 
  sum(rate(traces_spanmetrics_latency_bucket[5m])) by (le, span_name)
)

# Tracing coverage (orphan span rate)
rate(jaeger_analytics_orphan_spans[5m])
```

**SLOs Recommend**:
- Error rate < 0.1% for critical paths
- P99 latency < 500ms for API calls
- 100% of error traces sampled
- Trace coverage > 95% (orphan spans < 5%)

---

## 7. Kết luận

**Bản chất của Distributed Tracing**:
> Distributed tracing không phải là "logging nâng cao" hay "metrics chi tiết". Đó là **hệ thống reconstruct luồng request** để trả lời câu hỏi: "What happened to THIS specific request across THIS specific path?"

**Trade-off chính**:
- **Completeness vs Cost**: Full tracing = visibility hoàn hảo nhưng overhead và chi phí cao
- **Head vs Tail sampling**: Predictable overhead vs Error coverage
- **Storage granularity**: Retention dài = lịch sử tốt nhưng query chậm

**Quyết định quan trọng nhất trong production**:
1. Sampling strategy phù hợp traffic pattern
2. Storage backend phù hợp query pattern và budget
3. Context propagation được implement đúng ở tất cả integration points
4. Correlation giữa traces-metrics-logs để giảm MTTR

**Trend hiện đại (2024-2025)**:
- OpenTelemetry becoming universal standard
- eBPF-based auto-instrumentation (zero-code overhead)
- AI-assisted root cause analysis trên trace data
- TraceQL và query languages mạnh mẽ hơn

---

## 8. Tham khảo

- [OpenTelemetry Specification](https://opentelemetry.io/docs/specs/otel/)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [Jaeger Architecture](https://www.jaegertracing.io/docs/1.52/architecture/)
- [Google Dapper Paper](https://research.google/pubs/pub36356/)
- [Mastering Distributed Tracing (Yuri Shkuro)](https://www.oreilly.com/library/view/mastering-distributed-tracing/9781788628464/)
