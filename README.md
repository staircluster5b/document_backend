# 🚀 Lộ Trình Nghiên Cứu: Từ Java Core Đến Senior Backend, Spring Boot, Spring Cloud Chuyên Nghiệp. Giải phál hệ thống, giải pháp kiến trúc hệ thống ngân hàng

> **Mục tiêu:** Làm chủ ngôn ngữ, thấu hiểu kiến trúc hệ thống, quản trị rủi ro và tối ưu hóa hiệu năng.

---

## ☕ PHẦN 1: JAVA CORE MASTERY (NỀN TẢNG CHUYÊN SÂU)
*Tập trung vào cách ngôn ngữ vận hành dưới "nắp máy" thay vì chỉ sử dụng cú pháp.*

### 1.1 Quản lý Bộ nhớ & JVM (Java Virtual Machine)
- [x] **Tìm hiểu cấu trúc bộ nhớ JVM:** Phân biệt rõ Heap, Stack, Metaspace (trước đây là PermGen).
- [x] **Cơ chế Garbage Collection (GC):** Nghiên cứu các thuật toán G1, ZGC, Shenandoah. Hiểu khi nào thì xảy ra "Stop-the-world".
- [x] **JVM Tuning:** Cách sử dụng các tham số `-Xms`, `-Xmx`, `-XX:MaxMetaspaceSize` để tối ưu ứng dụng.
- [x] **Memory Leak:** Nhận diện rủi ro rò rỉ bộ nhớ (Static references, Unclosed resources) và cách dùng công cụ Profiler (VisualVM, JProfiler).

### 1.2 Deep Dive into Collections Framework
- [x] **Mã nguồn (Source code) phân tích:** Xem cách `HashMap` xử lý Collision (Hash bucket, Treeify) và `ConcurrentHashMap` (Lock stripping).
- [x] **Lựa chọn cấu trúc dữ liệu:** Khi nào dùng `ArrayList` vs `LinkedList`, `HashSet` vs `TreeSet` dựa trên độ phức tạp thuật toán (Big O).
- [x] **Fail-fast vs Fail-safe:** Phân biệt cơ chế Iteration trong môi trường đa luồng.
- [x] **Biến và Kiểu dữ liệu:** Phân biệt kiểu nguyên thủy (int, double, boolean, char) và kiểu tham chiếu (String).

### 1.3 Multithreading & Concurrency (Lập trình đa luồng)
- [x] **Thread Lifecycle & Synchronization:** Hiểu sâu cơ chế `synchronized`, `volatile`, `ReentrantLock`, và `ReadWriteLock`.
- [x] **Executor Framework:** ThreadPoolExecutor, ForkJoinPool, CompletableFuture, và Reactive Programming.
- [x] **Concurrent Collections:** Sử dụng `BlockingQueue`, `ConcurrentSkipListMap`, `CopyOnWriteArrayList` trong môi trường production.

### 1.4 Java I/O và NIO (New I/O)
- [x] **Blocking vs Non-blocking I/O:** Phân biệt IO truyền thống và NIO/NIO.2, Selector, Channel, Buffer.
- [x] **File System API (NIO.2):** Path, Files, FileVisitor, WatchService cho file monitoring.
- [x] **Asynchronous I/O:** `AsynchronousFileChannel`, callback vs future-based APIs.

---

## 🔬 PHẦN 2: JVM INTERNALS & ADVANCED JAVA
*Đi sâu vào cách JVM thực sự vận hành và các tính năng Java hiện đại.*

### 2.1 Class Loading & Bytecode
- [x] **ClassLoader Hierarchy:** Bootstrap, Extension, Application ClassLoaders. Custom ClassLoader implementation và ứng dụng (hot-reload, plugin architecture, isolation).
- [x] **JVM Bytecode & JIT Compilation:** Cách JVM biên dịch bytecode, JIT tiers (C1, C2, Graal), inlining, escape analysis, và on-stack replacement (OSR).
- [x] **Java Agents & Instrumentation:** Premain, agentmain, Javassist, ByteBuddy cho AOP và monitoring.

### 2.2 Java Memory Model (JMM)
- [x] **Happens-Before Relationship:** Định nghĩa chính xác, các rules (program order, monitor unlock/lock, volatile, thread start/join, final fields), và ứng dụng để viết lock-free algorithms đúng đắn.
- [x] **Memory Barriers & Fences:** LoadLoad, StoreStore, LoadStore, StoreLoad fences. Cách JVM/hardware reorder instructions và cách prevent.

### 2.3 Modern Java Features (Java 17-21 LTS)
- [x] **Virtual Threads Deep Dive:** Carrier threads, pinning, structured concurrency, scoped values. So sánh với reactive programming và traditional threads.
- [x] **Pattern Matching & Sealed Classes:** switch expressions, record patterns, sealed class hierarchy. Design patterns mới và migration từ OOP truyền thống.

---

## 🏗️ PHẦN 3: DISTRIBUTED SYSTEMS & BACKEND ARCHITECTURE
*Chuyển đổi từ lập trình viên Java thành kiến trúc sư hệ thống phân tán.*

### 3.1 Distributed Systems Fundamentals
- [x] **Consistency Models:** Linearizability vs Serializability vs Eventual Consistency. CAP theorem đúng đắn, PACELC theorem thực tế. Trade-off trong thiết kế database và cache.
- [x] **Consensus Algorithms:** Raft và Paxos - cơ chế leader election, log replication. So sánh implementation (etcd, Consul, Zookeeper).
- [x] **Distributed Transactions:** 2PC, 3PC, Saga pattern. Compensating transactions và idempotency trong microservices.

### 3.2 High-Performance Networking
- [x] **Netty Deep Dive:** Event loop architecture, Channel pipeline, ByteBuf memory management. Zero-copy và backpressure handling.
- [x] **gRPC Internals:** HTTP/2 flow control, protobuf serialization, load balancing strategies. Deadlines, retries, và circuit breaker pattern.
- [x] **Reactive Streams:** Publisher-Subscriber model, backpressure protocol. Project Reactor vs RxJava vs Kotlin Flow.

### 3.3 Microservices & Cloud-Native Patterns
- [x] **Service Mesh Deep Dive:** Istio/Linkerd architecture, sidecar pattern, mTLS, traffic management. Data plane vs control plane.
- [x] **Circuit Breaker & Bulkhead Pattern:** Implementation với Resilience4j, failure isolation, cascading failure prevention.
- [x] **Event-Driven Architecture:** Kafka/RabbitMQ internals, event sourcing, CQRS, outbox pattern, idempotent consumers.

---

## 🗄️ PHẦN 4: DATABASE & PERFORMANCE OPTIMIZATION
*Tối ưu hóa hiệu năng và thiết kế dữ liệu ở quy mô lớn.*

### 4.1 Database Internals & Optimization
- [x] **B-Tree vs LSM-Tree:** Storage engine internals, write amplification, read amplification. Trade-off giữa OLTP và OLAP.
- [x] **Indexing Strategies:** B-Tree, Hash, GiST, GIN indexes. Composite index design, covering index, index-only scans.
- [x] **Query Optimization:** Execution plans, cost-based optimizer, statistics, histograms. Common query anti-patterns.

### 4.2 Caching at Scale
- [x] **Caching Patterns & Strategies:** Cache-aside, read-through, write-through, write-behind. Cache eviction policies, TTL design.
- [x] **Distributed Cache:** Redis Cluster internals, consistent hashing, cache warming, thundering herd problem.
- [x] **Multi-Level Caching:** L1/L2/L3 cache hierarchy, CDN, edge caching. Cache invalidation strategies.

---

## 🍃 PHẦN 5: SPRING FRAMEWORK INTERNALS (SẮP TỚI)
*Đi sâu vào cách Spring Boot và Spring Cloud thực sự hoạt động.*

### 5.1 Spring Core & Boot Internals
- [x] **Spring IoC Container:** Bean lifecycle, BeanFactory vs ApplicationContext, BeanPostProcessor, BeanDefinition. Cơ chế dependency injection và circular dependency resolution.
- [x] **Spring Boot Auto-Configuration:** @Conditional annotations, spring.factories, auto-configuration class ordering. Custom starter development và best practices.
- [x] **Spring Transaction Management:** @Transactional internals, proxy-based vs aspectj weaving, propagation levels, isolation levels. Transaction rollback behavior và common pitfalls.

### 5.2 Spring Web & Reactive Stack
- [x] **Spring MVC Internals:** DispatcherServlet, HandlerMapping, HandlerAdapter, ViewResolver flow. Request mapping resolution, content negotiation, và message converters.
- [x] **Spring WebFlux & Project Reactor:** Mono vs Flux, backpressure handling, event loop model vs thread-per-request. Performance comparison và migration strategies từ MVC.

---

## 🔐 PHẦN 6: SECURITY, OBSERVABILITY & PRODUCTION SYSTEMS
*Kiến trúc bảo mật, giám sát và vận hành hệ thống backend ở quy mô enterprise.*

### 6.1 Security Architecture & Implementation
- [x] **Authentication & Authorization Patterns:** JWT internals (header/payload/signature, JWS/JWE), OAuth 2.0/OpenID Connect flows, session-based vs stateless auth. Token storage strategies, refresh token rotation, logout handling. Spring Security filter chain architecture, authentication providers, và authorization decision managers.
- [x] **API Security Deep Dive:** Input validation frameworks (Bean Validation, custom validators), CSRF protection cho SPAs, rate limiting algorithms (token bucket, sliding window), SQL injection prevention, XSS mitigation. Secret management (HashiCorp Vault, AWS Secrets Manager) trong containerized environments.
- [x] **Transport & Infrastructure Security:** mTLS implementation, certificate pinning, TLS 1.3 handshake optimization. Service-to-service authentication trong microservices, SPIFFE/SPIRE workload identity. Network policies, WAF rules, và zero-trust architecture principles.

### 6.2 Observability & Monitoring at Scale
- [x] **Metrics & Alerting Systems:** Micrometer integration, Prometheus data model (counters, gauges, histograms, summaries), recording rules, federation. Alertmanager routing, silencing, inhibition. RED method (Rate, Errors, Duration) vs USE method (Utilization, Saturation, Errors) cho SLO/SLI definition.
- [x] **Distributed Tracing:** OpenTelemetry collector architecture, span context propagation (W3C trace context, B3 headers), sampling strategies (head-based vs tail-based). Jaeger/Tempo storage backends, trace-to-log correlation. Performance overhead của instrumentation và head-based sampling optimization.
- [x] **Log Management & Analysis:** Structured logging (JSON format), log levels và cardinality concerns. Centralized logging với ELK/Loki, log aggregation patterns, retention policies. Correlation IDs cho request tracing across services, sensitive data masking trong logs.

### 6.3 Production Operations & SRE Practices
- [x] **Deployment Strategies & Release Engineering:** Blue-green deployments, canary releases, feature flags (LaunchDarkly, Unleash). Database migration strategies (Flyway, Liquibase), backward compatibility trong schema changes. CI/CD pipeline optimization, build caching, artifact management.
- [x] **Chaos Engineering & Resilience Testing:** Chaos Monkey principles, failure injection (network latency, packet loss, service killing). Game day exercises, post-mortem culture, blameless incident reviews. Automated rollback triggers, circuit breaker monitoring, graceful degradation patterns.
- [x] **Performance Tuning & Capacity Planning:** Load testing với k6/Gatling, throughput vs latency trade-offs. JVM tuning trong containerized environments (CGroup awareness, container-aware ergonomics). Connection pool sizing (HikariCP optimization), thread pool tuning, backpressure handling. Vertical vs horizontal scaling decisions, autoscaling policies (KEDA, HPA).

---

## 🚀 PHẦN 7: ADVANCED BACKEND ARCHITECTURE (ĐỀ XUẤT NGHIÊN CỨU TIẾP THEO)

### 7.1 Financial Systems Architecture & Banking Domain
- [x] **Core Banking Integration Patterns:** ISO 20022 message standards, SWIFT MT/MX migration, payment gateways (NAPAS, Visa, Mastercard). Transaction integrity trong high-value transfers, reconciliation processes, end-of-day batch processing.
- [x] **Regulatory Compliance & Audit:** PCI-DSS implementation cho card data, GDPR data handling, SOX compliance cho financial reporting. Immutable audit logs, tamper-evident storage, data retention policies.
- [x] **High-Frequency Trading & Low-Latency Systems:** Lock-free algorithms, memory-mapped files, kernel bypass networking (DPDK). Time synchronization (PTP), co-location strategies, market data feed handlers.

### 7.2 Advanced Data Architecture
- [x] **Data Mesh & Domain-Oriented Ownership:** Self-serve data infrastructure, domain data products, federated governance. Transition từ monolithic data lake sang distributed data mesh.
- [x] **Stream Processing at Scale:** Apache Flink internals (checkpointing, state backends, watermarking). Exactly-once processing semantics, windowing strategies, joining streams với different time characteristics.
- [x] **Vector Databases & AI Integration:** Embedding storage, similarity search (HNSW, IVF), hybrid search (dense + sparse). RAG architecture, prompt engineering infrastructure, model serving patterns.

### 7.3 Emerging Technologies & Future Trends
- [x] **WebAssembly (WASM) in Backend:** WASI runtime, language-agnostic plugins, sandboxed execution. Edge computing với WASM, Cloudflare Workers architecture, performance comparison với containers.
- [x] **eBPF for Observability & Security:** Kernel-level tracing, network filtering, security policies. Cilium service mesh, Falco runtime security, continuous profiling (Parca, Pyroscope).
- [x] **Green Computing & Sustainable Software:** Carbon-aware computing, energy-efficient algorithms, carbon footprint measurement. Locale shifting, demand shifting, sustainable architecture patterns.

### 7.4 Next-Generation Infrastructure & AI-Native Systems
- [x] **Serverless Architecture Deep Dive:** AWS Lambda internals, cold start optimization strategies, provisioned concurrency. Function-as-a-Service patterns, step functions, event-driven serverless workflows. Trade-offs: latency vs cost, vendor lock-in mitigation with Knative/OpenFaaS.
- [x] **Post-Quantum Cryptography & Zero-Knowledge Proofs:** Lattice-based cryptography, CRYSTALS-Kyber/Dilithium algorithms. zk-SNARKs/zk-STARKs for privacy-preserving verification, homomorphic encryption use cases. Migration strategies từ RSA/ECC trong hệ thống legacy.
- [x] **AI-Native Application Architecture:** LLM serving infrastructure (vLLM, TensorRT-LLM), model quantization & distillation. Retrieval-Augmented Generation (RAG) at scale, vector search pipelines, agent orchestration frameworks (LangChain, AutoGPT). Cost optimization, token management, prompt caching strategies.

---

## 🌍 PHẦN 8: ADVANCED DISTRIBUTED SYSTEMS & EDGE COMPUTING (ĐỀ XUẤT NGHIÊN CỨU TIẾP THEO)

### 8.1 Global Distributed Systems & Multi-Region Architecture
- [x] **Active-Active Multi-Region Design:** Conflict-free Replicated Data Types (CRDTs), conflict resolution strategies, vector clocks. Global consensus trong WAN environment, latency optimization qua geographic distribution.
- [x] **Edge Computing & CDN Deep Dive:** Cloudflare Workers, Fastly Compute@Edge, AWS Lambda@Edge. Edge caching strategies, request routing, và real-time personalization at edge.
- [x] **Global Database Systems:** Spanner TrueTime, CockroachDB distributed SQL, YugabyteDB. Linearizability across regions, clock synchronization, và trade-off availability vs consistency trong global context.

### 8.2 Advanced Event-Driven Patterns & State Management
- [x] **Event Sourcing at Scale:** Event Store internals, temporal queries, event versioning. Snapshot strategies, projection rebuild optimization, và event schema evolution trong multi-year systems.
- [ ] **CQRS Advanced Patterns:** Read model optimization, materialized view strategies. Separate write và read path optimization, eventual consistency mitigation patterns.
- [ ] **State Machine Orchestration:** Temporal/Cadence workflow engines, saga pattern implementation. Long-running transaction orchestration, failure compensation, và deterministic replay.

### 8.3 Hardware-Accelerated Backend Systems
- [ ] **GPU Computing in Backend:** CUDA kernels, cuDNN optimization. AI inference acceleration, matrix multiplication optimization, và GPU memory management trong microservices.
- [ ] **Custom Hardware Acceleration:** FPGA trong high-frequency trading, SmartNICs (AWS Nitro, Azure Boost). Offloading networking stack, zero-copy data path, và kernel bypass techniques.
- [ ] **Quantum-Ready Cryptography Preparation:** Cryptographic agility, algorithm migration strategies. Quantum threat timeline, hybrid classical-quantum systems preparation.
