# Service Discovery - Eureka, Consul, Kubernetes DNS

## 1. Mục tiêu của Task

Hiểu bản chất của Service Discovery trong hệ thống phân tán: tại sao cần, các cơ chế triển khai khác nhau, trade-off giữa client-side vs server-side discovery, và cách vận hành thực tế trong production.

## 2. Bản chất và cơ chế hoạt động

### 2.1 Tại sao cần Service Discovery?

Trong monolith, service location là static (hardcoded config). Trong microservices:
- Services scale horizontally → IP là ephemeral
- Auto-scaling → instances xuất hiện/biến mất liên tục  
- Multi-environment (dev/staging/prod) → cấu hình khác nhau
- Cloud infrastructure → IP không predictable

> **Bản chất**: Service Discovery là một **database phân tán** lưu trữ ánh xạ `service name → (IP, port, metadata)` với tính chất **eventually consistent** và **high availability**.

### 2.2 Hai mô hình Discovery

#### Client-Side Discovery

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client    │────▶│   Registry  │     │  Service A  │
│  (knows SDK)│◀────│  (Eureka)   │     │  (instance) │
└─────────────┘     └─────────────┘     └─────────────┘
       │                                          ▲
       └──────────────────────────────────────────┘
              (gọi trực tiếp instance)
```

**Cơ chế**:
1. Service register/de-register với registry
2. Client query registry để lấy danh sách instances
3. Client tự load balance (chọn instance)
4. Client cache danh sách locally

#### Server-Side Discovery

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client    │────▶│    Load     │────▶│  Service A  │
│  (dumb)     │     │  Balancer   │     │  (instance) │
└─────────────┘     │  (knows all)│     └─────────────┘
                    └─────────────┘
                           │
                    ┌─────────────┐
                    │   Registry  │
                    └─────────────┘
```

**Cơ chế**:
1. Client gọi vào load balancer (DNS name)
2. Load balancer query registry
3. Load balancer chọn instance và forward request

### 2.3 CAP Trade-off trong Service Discovery

| Đặc tính | Yêu cầu | Lý do |
|----------|---------|-------|
| **Availability** | Cao nhất | Discovery down = toàn hệ thống down |
| **Partition Tolerance** | Bắt buộc | Network partition luôn xảy ra |
| **Consistency** | Có thể relax | Stale data một vài giây chấp nhận được |

> **Quyết định**: Service Discovery chọn **AP** (Availability + Partition tolerance). Consistency được đánh đổi lấy availability.

## 3. Kiến trúc chi tiết từng giải pháp

### 3.1 Netflix Eureka

#### Kiến trúc

```
┌─────────────────────────────────────────────────────────┐
│                    Eureka Cluster                       │
│  ┌─────────────┐      ┌─────────────┐                  │
│  │   Server 1  │◀────▶│   Server 2  │  (peer-to-peer)  │
│  │  (us-east)  │      │  (us-west)  │                  │
│  └─────────────┘      └─────────────┘                  │
│       ▲                      ▲                         │
└───────┼──────────────────────┼─────────────────────────┘
        │                      │
   ┌────┴────┐            ┌────┴────┐
   │ Service │            │ Service │
   │    A    │            │    B    │
   └─────────┘            └─────────┘
```

**Đặc điểm kiến trúc**:
- **Peer-to-peer replication**: Không có master, tất cả nodes equal
- **Self-preservation mode**: Khi heartbeat mất, không xóa ngay instance
- **Client-side caching**: Client cache registry locally, survive khi server down

#### Cơ chế hoạt động

**Service Registration**:
```
1. Service khởi động → POST /eureka/apps/{appName}
2. Gửi metadata: instanceId, hostName, port, healthCheckUrl
3. Gửi heartbeat mỗi 30s (lease renewal)
4. Lease expiration: 90s không heartbeat → marked for eviction
```

**Self-Preservation Mode**:
```
Khi % instances mất heartbeat vượt ngưỡng (ví dụ: 85%):
├── Server ngừng eviction instances cũ
├── Giữ lại stale data để tránh mass deregistration
└── Bật cảnh báo (amber alert)

Tại sao cần?
- Network partition giữa client và server
- Client vẫn healthy nhưng không gửi được heartbeat
- Tránh scenario: toàn bộ instances bị xóa khỏi registry
```

#### Trade-off Eureka

| Ưu điểm | Nhược điểm |
|---------|------------|
| Client-side caching = resilience cao | Client phải implement load balancing |
| Self-preservation chống false negative | Stale data khi partition |
| Simple, battle-tested | Netflix abandoned, community maintained |
| No single point of failure | Chỉ phù hợp Java ecosystem |

### 3.2 HashiCorp Consul

#### Kiến trúc

```
┌─────────────────────────────────────────────────────────┐
│                    Consul Cluster                       │
│                                                         │
│  ┌─────────────┐      ┌─────────────┐                  │
│  │    Server   │◀────▶│    Server   │                  │
│  │   (Leader)  │      │  (Follower) │  (Raft consensus)│
│  └─────────────┘      └─────────────┘                  │
│        ▲                       ▲                       │
│        └───────────┬───────────┘                       │
│                    Gossip                               │
│              (Serf protocol)                            │
└─────────────────────────────────────────────────────────┘
         │
    ┌────┴────┐
    │  Client │  (Agent chạy local, không lưu data)
    │  Agent  │
    └────┬────┘
         │
    ┌────┴────┐
    │ Service │
    └─────────┘
```

**Đặc điểm kiến trúc**:
- **Raft consensus**: Leader election, strong consistency cho writes
- **Gossip protocol**: Serf cho member discovery và failure detection
- **Multi-datacenter**: WAN gossip kết nối các clusters
- **Client agents**: Chạy local, cache data, health check

#### Cơ chế hoạt động

**Consensus (Raft)**:
```
1. All writes go to Leader
2. Leader replicate to Followers
3. Majority ack = write committed
4. Leader failure → new election (< 1s typically)

Trade-off: 
- Write latency cao hơn (phải đợi majority)
- Read consistency configurable: default/stale/consistent
```

**Health Checking**:
```
Consul hỗ trợ 4 loại health check:
├── HTTP: GET endpoint, expect 200
├── TCP: Connection test
├── Script/Command: Execute script, check exit code  
├── TTL: Service báo "tôi còn sống" qua HTTP
└── gRPC: Native gRPC health check (cho microservices)
```

#### Service Mesh Integration

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Service   │────▶│   Envoy     │────▶│   Service   │
│      A      │     │  (Sidecar)  │     │      B      │
└─────────────┘     └──────┬──────┘     └─────────────┘
                           │
                    ┌──────┴──────┐
                    │   Consul    │
                    │  (Control   │
                    │   Plane)    │
                    └─────────────┘
```

Consul cung cấp:
- **Service mesh**: mTLS tự động giữa services
- **Intentions**: ACL cho service-to-service communication
- **Traffic management**: canary, blue-green via Envoy

#### Trade-off Consul

| Ưu điểm | Nhược điểm |
|---------|------------|
| Multi-datacenter native | Resource-heavy (3-5 servers cho production) |
| Strong consistency option | Learning curve cao |
| Built-in health checking | Write throughput thấp hơn (Raft) |
| Service mesh built-in | Operational complexity |
| KV store, ACLs, intentions | |

### 3.3 Kubernetes DNS (CoreDNS)

#### Kiến trúc

```
┌─────────────────────────────────────────────────────────┐
│                   Kubernetes Cluster                    │
│                                                         │
│  ┌─────────────┐      ┌─────────────┐                  │
│  │   CoreDNS   │      │   kube-     │                  │
│  │   (Pods)    │◀────▶│  apiserver  │                  │
│  │  (HA mode)  │      │  (etcd)     │                  │
│  └──────┬──────┘      └─────────────┘                  │
│         │                                               │
│    DNS queries                                          │
│         │                                               │
│  ┌──────┴──────┐                                        │
│  │   Service   │  my-service.namespace.svc.cluster.local │
│  │   Record    │                                        │
│  └─────────────┘                                        │
└─────────────────────────────────────────────────────────┘
```

**Đặc điểm kiến trúc**:
- **DNS-based**: Không cần SDK, sử dụng DNS standard
- **Kube-proxy**: iptables/IPVS rules cho load balancing
- **etcd as source of truth**: Controller sync Service/Endpoint objects

#### Cơ chế hoạt động

**DNS Resolution**:
```
Client query: my-service.my-namespace.svc.cluster.local
                │           │        │      │
                │           │        │      └── Cluster domain
                │           │        └── Service subdomain  
                │           └── Namespace
                └── Service name

Record types:
├── A/AAAA: ClusterIP (virtual IP)
├── SRV: Port information
├── PTR: Reverse DNS
└── Headless service: Returns Pod IPs directly
```

**Endpoint Management**:
```
Pod created/destroyed
        ↓
Endpoint Controller detects change
        ↓
Updates Endpoint/EndpointSlice object
        ↓
kube-proxy sync iptables/IPVS rules
        ↓
Traffic routed to healthy Pods
```

#### Headless Services

```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-db
spec:
  clusterIP: None  # <-- Headless
  selector:
    app: my-db
```

**Khi nào dùng Headless**:
- StatefulSets (cần stable network identity)
- Client cần biết tất cả Pod IPs (custom load balancing)
- Database clusters (cần direct Pod-to-Pod communication)

#### Trade-off K8s DNS

| Ưu điểm | Nhược điểm |
|---------|------------|
| Native, không cần extra infrastructure | Chỉ trong Kubernetes |
| No SDK required (DNS standard) | Limited metadata (chỉ IP/port) |
| Tích hợp sẵn health checking (readiness probe) | No client-side load balancing |
| ExternalTrafficPolicy, sessionAffinity | DNS caching issues |
| Mature, production-ready | Cross-cluster discovery phức tạp |

## 4. So sánh các giải pháp

### 4.1 Feature Comparison

| Feature | Eureka | Consul | K8s DNS |
|---------|--------|--------|---------|
| **Discovery Model** | Client-side | Client-side | Server-side (LB) |
| **Consistency** | Eventually consistent | Strong (Raft) | Strong (etcd) |
| **Health Check** | Client heartbeat | Multiple types | Readiness probe |
| **Multi-DC** | Có (replicate) | Native | Federation |
| **Load Balancing** | Client | Client/Sidecar | kube-proxy |
| **Service Mesh** | Không | Consul Connect | Istio/Linkerd |
| **Language Support** | Java native | Universal | Universal |
| **KV Store** | Không | Có | ConfigMap/Secret |
| **ACL/Security** | Cơ bản | Rich | RBAC |

### 4.2 Decision Matrix

| Scenario | Khuyến nghị | Lý do |
|----------|-------------|-------|
| Pure Spring Cloud ecosystem | Eureka | Native integration, simple |
| Multi-datacenter, multi-cloud | Consul | Native WAN gossip, mesh |
| Chỉ Kubernetes | K8s DNS | No extra infra, DNS-based |
| Hybrid (VM + K8s) | Consul | Universal, bridge gap |
| Need service mesh | Consul / Istio | mTLS, traffic mgmt |
| Legacy migration | Consul | Dần dần, không breaking |

### 4.3 Performance Considerations

| Metric | Eureka | Consul | K8s DNS |
|--------|--------|--------|---------|
| Registry size | ~10K instances | ~100K instances | ~10K services |
| Latency (read) | < 1ms (local cache) | < 1-5ms | < 1ms (DNS cache) |
| Latency (write) | < 10ms | < 50-100ms (Raft) | < 100ms (apiserver) |
| Memory/server | 2-4 GB | 4-8 GB | 100-500 MB (CoreDNS) |

## 5. Rủi ro, Anti-patterns, Lỗi thường gặp

### 5.1 Common Pitfalls

**1. Thundering Herd Problem**
```
Scenario: 100 instances khởi động cùng lúc
         → Query registry cùng lúc
         → Registry overload
         → Cascade failure

Giải pháp:
├── Client-side caching với jitter
├── Exponential backoff cho retries
└── Warm cache trước khi traffic vào
```

**2. Split-Brain trong Eureka**
```
Khi network partition giữa Eureka nodes:
├── Các node không thấy nhau
├── Mỗi node giữ subset của registry
└── Client thấy inconsistent view

Giải pháp:
├── Self-preservation mode
├── Client cache lâu hơn lease expiration
└── Prefer same-zone instances
```

**3. DNS Caching Issues**
```
Problem: Java DNS cache mặc định cache forever
         (security manager enabled)

JVM flag: -Dnetworkaddress.cache.ttl=10

Hoặc trong code:
java.security.Security.setProperty(
    "networkaddress.cache.ttl", "10"
);
```

**4. Health Check False Positives**
```
Scenario: 
- Health check đơn giản (chỉ check TCP port open)
- Service "sống" nhưng không xử lý được request
- Registry vẫn route traffic vào

Giải pháp:
├── Deep health check (database, downstream)
├── Circuit breaker ở client
└── Graceful degradation
```

### 5.2 Anti-Patterns

| Anti-Pattern | Tại sao sai | Làm đúng |
|--------------|-------------|----------|
| Hardcode service URLs | Mất discovery benefits | Dùng service name |
| Synchronous registration | Block startup | Async registration |
| No retry logic | Single point of failure | Exponential backoff |
| Ignore zone awareness | Cross-AZ latency cao | Prefer local zone |
| Stale cache không expire | Route vào dead instances | TTL + health check |

## 6. Khuyến nghị thực chiến trong Production

### 6.1 Monitoring & Observability

**Metrics cần track**:
```
Registry:
├── registry_size (instances per service)
├── registration_rate (registrations/sec)
├── renewal_rate (heartbeats/sec)
├── eviction_rate (expired leases/sec)
└── cache_hit_ratio (client-side)

Health:
├── failed_health_checks
├── self_preservation_mode_enabled
└── quorum_health (Consul)

Network:
├── dns_query_rate
├── dns_response_time
└── dns_error_rate
```

### 6.2 Sizing Guidelines

**Eureka**:
- 2-3 instances cho HA
- 4GB RAM, 2 CPU mỗi instance
- Chịu được ~10K instances

**Consul**:
- 3-5 server nodes (odd number cho quorum)
- 8GB RAM, 4 CPU mỗi server
- 1 agent mỗi node (client)

**CoreDNS**:
- 2+ replicas cho HA
- 500MB RAM, 0.5 CPU mỗi replica
- Autoscale theo DNS QPS

### 6.3 Disaster Recovery

**Backup strategy**:
```
Eureka:
├── Backup: Export registry data định kỳ
├── Restore: Re-register từ client (auto-recovery)
└── RPO: Không quan trọng (ephemeral data)

Consul:
├── Backup: consul snapshot save
├── Restore: consul snapshot restore
└── RPO: Vài giờ (config + KV store)

K8s:
├── Backup: etcd snapshot (bao gồm services)
├── Restore: etcd restore
└── RPO: Theo etcd backup policy
```

### 6.4 Security Best Practices

```
1. Encryption in transit:
   ├── Eureka: TLS cho replication
   ├── Consul: TLS + mTLS (mesh)
   └── K8s: Pod security policies

2. Authentication:
   ├── Eureka: Basic auth (cơ bản)
   ├── Consul: ACL tokens
   └── K8s: ServiceAccount + RBAC

3. Network segmentation:
   ├── Restrict registry access
   ├── Whitelist client IPs
   └── Internal LB only
```

## 7. Kết luận

Service Discovery là **nền tảng** của microservices, nhưng không phải one-size-fits-all:

| Bản chất | Ý nghĩa |
|----------|---------|
| **Eventually consistent database** | Chấp nhận stale data để đổi lấy availability |
| **Client vs Server-side** | Trade-off giữa complexity và flexibility |
| **Health checking** | Critical - không có = blind routing |
| **Caching** | Must-have cho resilience |

**Lựa chọn nên dựa trên**:
1. **Ecosystem hiện tại** (Spring Cloud → Eureka, HashiCorp → Consul)
2. **Infrastructure** (K8s-only → DNS, Hybrid → Consul)
3. **Team expertise** (Consul phức tạp hơn nhưng powerful hơn)
4. **Scale requirements** (10K+ instances → Consul, <1K → bất kỳ)

**Rule of thumb**: Bắt đầu đơn giản (K8s DNS nếu có K8s, Eureka nếu Spring), tăng dần complexity khi có requirement rõ ràng.

---

## 8. Tham khảo

- [Eureka Wiki](https://github.com/Netflix/eureka/wiki)
- [Consul Architecture](https://developer.hashicorp.com/consul/docs/architecture)
- [Kubernetes DNS](https://kubernetes.io/docs/concepts/services-networking/dns-pod-service/)
- [Raft Consensus](https://raft.github.io/)
