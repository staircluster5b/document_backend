# Java Core → Senior Backend Expert Roadmap

Lộ trình nghiên cứu chuyên sâu để chuyển đổi từ Java Core lên Senior Backend Expert.

## 📋 Phase 1: Java Core Deep Dive (Foundation)

### 1. JVM Architecture & Memory Management
- [x] JVM Architecture Overview - Runtime Data Areas, Class Loader Subsystem
- [x] Garbage Collection Deep Dive - Algorithms (CMS, G1, ZGC, Shenandoah), Tuning, GC Logs
- [x] Memory Management - Heap Structure, Metaspace, Stack vs Heap, Memory Leaks Detection

### 2. Java Concurrency & Multithreading
- [x] Thread Lifecycle & Synchronization - wait/notify, join, synchronized, volatile
- [x] java.util.concurrent Package - ExecutorService, ThreadPool, CompletableFuture, Fork/Join
- [x] Lock-Free Programming - CAS, Atomic classes, ABA Problem
- [x] Concurrency Patterns - Producer-Consumer, Reader-Writer, Dining Philosophers

### 3. Java Collections Framework Deep Dive
- [x] HashMap Implementation - Hash collision, Treeify, Resize mechanism, Load factor
- [x] ConcurrentHashMap - Segment locking vs CAS, Size estimation
- [x] List Implementations - ArrayList vs LinkedList internals, CopyOnWriteArrayList

### 4. Java I/O & NIO
- [x] Blocking I/O (java.io) - Streams, Readers/Writers, Serialization
- [x] Non-blocking I/O (java.nio) - Channels, Buffers, Selectors, ByteBuffer
- [x] NIO.2 (java.nio.file) - Path, Files, WatchService, FileVisitor

## 🏗️ Phase 2: System Design & Architecture

### 5. Design Patterns in Java
- [x] Creational Patterns - Singleton, Factory, Builder, Object Pool
- [x] Structural Patterns - Adapter, Decorator, Proxy, Facade
- [x] Behavioral Patterns - Strategy, Observer, Template Method, Command

### 6. Distributed Systems Fundamentals
- [x] CAP Theorem & PACELC - Trade-offs in distributed systems
- [x] Consistency Models - Strong, Eventual, Causal, Read-your-writes
- [x] Distributed Transactions - 2PC, 3PC, Saga Pattern, Outbox Pattern

### 7. Caching Strategies
- [x] Cache Patterns - Cache-Aside, Read-Through, Write-Through, Write-Behind
- [x] Eviction Policies - LRU, LFU, FIFO, Custom
- [x] Distributed Caching - Redis Cluster, Consistent Hashing

### 8. Message Queues & Event-Driven Architecture
- [ ] Message Queue Patterns - Pub/Sub, Point-to-Point, Dead Letter Queue
- [ ] Apache Kafka - Architecture, Partitioning, Replication, Consumer Groups
- [ ] Event Sourcing & CQRS - Event Store, Projection, Snapshots

## 🔧 Phase 3: Database & Persistence

### 9. Relational Database Internals
- [ ] Indexing Strategies - B-Tree, B+Tree, Hash Index, Covering Index
- [ ] Query Optimization - Execution Plan, Cost-based optimizer, Join algorithms
- [ ] Transaction Isolation - ACID, Isolation Levels, MVCC, Phantom Reads

### 10. NoSQL Databases
- [ ] MongoDB - Document Model, Sharding, Replication, Aggregation Pipeline
- [ ] Cassandra - Wide-column store, Tunable consistency, LSM Tree
- [ ] Elasticsearch - Inverted index, Sharding, Relevance scoring

### 11. ORM & Data Access Patterns
- [ ] Hibernate Internals - Session, Cache levels, Lazy loading, N+1 problem
- [ ] Connection Pooling - HikariCP, Connection lifecycle, Pool sizing
- [ ] Database Migration - Flyway, Liquibase, Versioning strategies

## 🌐 Phase 4: Microservices & Cloud-Native

### 12. Microservices Architecture
- [ ] Service Decomposition - Domain-driven design, Bounded contexts
- [ ] Inter-service Communication - REST, gRPC, GraphQL comparison
- [ ] Service Discovery - Eureka, Consul, Kubernetes DNS

### 13. API Gateway & Load Balancing
- [ ] API Gateway Patterns - Routing, Rate limiting, Authentication aggregation
- [ ] Load Balancing Algorithms - Round-robin, Least connections, Consistent hashing
- [ ] Circuit Breaker & Bulkhead - Resilience patterns, Fallback strategies

### 14. Containerization & Orchestration
- [ ] Docker for Java - Multi-stage builds, JIB, Layer caching
- [ ] Kubernetes - Deployments, Services, ConfigMaps, Health probes
- [ ] Helm Charts - Templating, Values, Releases

## 📊 Phase 5: Observability & Production

### 15. Logging & Monitoring
- [ ] Structured Logging - SLF4J, Logback, MDC, Correlation IDs
- [ ] Metrics Collection - Micrometer, Prometheus, Custom metrics
- [ ] Distributed Tracing - OpenTelemetry, Jaeger, Trace propagation

### 16. Performance Optimization
- [ ] Profiling Techniques - CPU profiling, Memory profiling, Flame graphs
- [ ] JVM Tuning - Heap sizing, GC selection, JIT compiler flags
- [ ] Application Profiling - Async-profiler, JFR, JMC

### 17. Security Best Practices
- [ ] Authentication & Authorization - JWT, OAuth2, OIDC, RBAC
- [ ] Secure Coding - SQL Injection, XSS, CSRF, Secrets management
- [ ] API Security - Rate limiting, Input validation, CORS

---

## 📁 Research Output Structure

```
research/
├── 01-jvm-architecture/
│   ├── 01-jvm-architecture-overview.md
│   ├── 02-garbage-collection-deep-dive.md
│   └── 03-memory-management.md
├── 02-concurrency/
│   ├── 01-thread-lifecycle-synchronization.md
│   ├── 02-util-concurrent-package.md
│   ├── 03-lock-free-programming.md
│   └── 04-concurrency-patterns.md
└── ...
```

---

## 🎯 Research Principles

1. **Depth over Breadth**: Tập trung vào bản chất, không dừng ở mô tả bề mặt
2. **Trade-off Analysis**: Luôn phân tích đánh đổi giữa các lựa chọn
3. **Production-Ready**: Tập trung vào vận hành thực tế, monitoring, debugging
4. **Modern Java**: Cập nhật các cải tiến từ Java 21+
5. **Minimal Code**: Ưu tiên sơ đồ, luồng xử lý, giải thích tư duy

## 📝 Notes

- Mỗi task nghiên cứu được lưu tại `research/[phase]/[task-name].md`
- Đánh dấu [x] khi hoàn thành task
- Tự động thêm task mới khi cần mở rộng
