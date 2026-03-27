# eBPF for Observability & Security: Deep Dive Research

## 1. Mục tiêu của Task

Nghiên cứu bản chất và ứng dụng của eBPF (Extended Berkeley Packet Filter) trong:
- Kernel-level observability: tracing, profiling, monitoring không cần instrumentation
- Network filtering và security policies ở tầng kernel
- Cilium service mesh: sidecar-less service mesh sử dụng eBPF
- Falco runtime security: threat detection trong containers
- Continuous profiling: Parca và Pyroscope cho production profiling

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 eBPF là gì? Bản chất kỹ thuật

eBPF là một công nghệ **in-kernel virtual machine** cho phép thực thi sandboxed programs trong kernel space mà không cần thay đổi kernel source code hay load kernel modules.

**Lịch sử phát triển:**
| Giai đoạn | Tính năng | Ý nghĩa |
|-----------|-----------|---------|
| **1992 - BPF** | Classic BPF (cBPF) | Packet filtering trong tcpdump |
| **2014 - eBPF** | Extended BPF, maps, helper functions | General-purpose kernel programming |
| **2020+** | CO-RE (Compile Once - Run Everywhere), BTF | Portable programs across kernel versions |

**Kiến trúc cốt lõi:**

```
┌─────────────────────────────────────────────────────────────────────┐
│                         User Space                                   │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌────────────┐ │
│  │   bpftool   │  │   kubectl   │  │  bcc tools  │  │   perf     │ │
│  │  (loader)   │  │  (cilium)   │  │  (tracing)  │  │ (profiler) │ │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └─────┬──────┘ │
│         │                │                │               │        │
│         └────────────────┴────────────────┴───────────────┘        │
│                                  │                                 │
│                    ┌─────────────┴─────────────┐                   │
│                    ▼                           ▼                   │
│         ┌──────────────────┐      ┌──────────────────┐             │
│         │  libbpf (CO-RE)  │      │   LLVM/Clang     │             │
│         │  Skeleton loader │      │   (compile C→BPF)│             │
│         └─────────┬────────┘      └──────────────────┘             │
│                   │                                                │
└───────────────────┼────────────────────────────────────────────────┘
                    │
                    ▼ (bpf() syscall)
┌─────────────────────────────────────────────────────────────────────┐
│                      Kernel Space                                    │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                    eBPF Verifier                             │    │
│  │  - Static analysis ensures safety                           │    │
│  │  - Loop detection and bounded execution                     │    │
│  │  - No null dereferences, no out-of-bounds access            │    │
│  └──────────────────────┬──────────────────────────────────────┘    │
│                         │                                           │
│  ┌──────────────────────┴──────────────────────────────────────┐    │
│  │                    eBPF Virtual Machine                      │    │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌─────────┐│    │
│  │  │   Network  │  │   Tracing  │  │   LSM/     │  │  XDP    ││    │
│  │  │  (tc/xdp)  │  │  (kprobe/  │  │  Security  │  │ (fast   ││    │
│  │  │            │  │  tracepoint)│  │  (LSM)     │  │  path)  ││    │
│  │  └────────────┘  └────────────┘  └────────────┘  └─────────┘│    │
│  └──────────────────────────────────────────────────────────────┘    │
│                         │                                           │
│  ┌──────────────────────┴──────────────────────────────────────┐    │
│  │                    eBPF Maps (Shared Memory)                 │    │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │    │
│  │  │ HashMap  │  │  Array   │  │  Ring    │  │  Stack Trace │  │    │
│  │  │ (key-val)│  │ (per-cpu)│  │  Buffer  │  │    (maps)    │  │    │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────────┘  │    │
│  └──────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

**Bản chất quan trọng nhất:** eBPF programs chạy trong **kernel context** nhưng với **guarantees an toàn** được enforce bởi verifier:
- Không thể access arbitrary kernel memory
- Không thể infinite loop (tất cả loops phải có bounded iterations)
- Không thể crash kernel (tất cả memory accesses được validate)
- Không thể hang kernel (mọi program phải terminate trong giới hạn instructions)

### 2.2 eBPF Verifier: Cổng kiểm soát bảo mật

Verifier là thành phần **tối quan trọng** đảm bảo eBPF không thể phá hỏng kernel:

```
┌────────────────────────────────────────────────────────────────┐
│                    eBPF Verifier Flow                          │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐ │
│  │  Parse   │───▶│  Control │───▶│   Data   │───▶│  Bound   │ │
│  │  ELF     │    │  Flow    │    │  Flow    │    │  Check   │ │
│  │  (BTF)   │    │  Graph   │    │  Analysis│    │          │ │
│  └──────────┘    └──────────┘    └──────────┘    └──────────┘ │
│       │                                               │        │
│       ▼                                               ▼        │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │              Safety Guarantees Checked:                  │ │
│  │  ✓ No unreachable instructions                         │ │
│  │  ✓ All loops have upper bound                          │ │
│  │  ✓ No out-of-bounds memory access                      │ │
│  │  ✓ Stack depth bounded (MAX_BPF_STACK = 512 bytes)     │ │
│  │  ✓ No uninitialized variable access                    │ │
│  │  ✓ Helper function args validated                      │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

> **Lưu ý quan trọng:** Verifier có thể từ chối valid programs nếu không thể prove safety. Đây là trade-off giữa expressiveness và security.

### 2.3 Program Types và Hook Points

eBPF programs attach vào **specific hook points** trong kernel:

| Program Type | Hook Point | Use Case |
|--------------|------------|----------|
| **`kprobe/kretprobe`** | Kernel function entry/return | Function tracing, latency measurement |
| **`tracepoint`** | Static kernel instrumentation points | Stable tracing interface |
| **`uprobe/uretprobe`** | User-space function entry/return | Application tracing mà không cần recompile |
| **`xdp`** | Network driver level (RX path) | Packet filtering/DDoS mitigation (10M+ pps) |
| **`tc` (traffic control)** | Traffic classifier/egress | Traffic shaping, QoS, load balancing |
| **`socket_filter`** | Socket receive path | Monitoring network traffic |
| **`lsm` (Linux Security Modules)** | Security hooks (MAC) | Runtime security policy enforcement |
| **`cgroup_skb/cgroup_sock`** | Cgroup networking | Per-container network policies |
| **`perf_event`** | Hardware/software PMU | CPU profiling, cache analysis |
| **`fentry/fexit`** | Function entry/exit (newer, faster than kprobe) | Low-overhead tracing |

### 2.4 eBPF Maps: Shared Memory giữa Kernel và User Space

Maps là cấu trúc dữ liệu **key-value store** cho phép kernel và user space communicate:

```
┌─────────────────────────────────────────────────────────────────┐
│                      eBPF Maps                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Kernel Space                     User Space                    │
│  ┌──────────────┐                ┌──────────────┐               │
│  │  eBPF Prog   │                │  User App    │               │
│  │              │                │  (Go/Rust/C) │               │
│  │  bpf_map_    │◄──────────────▶│  bpf_map_    │               │
│  │  lookup()    │   (syscall)    │  lookup()    │               │
│  │  bpf_map_    │                │  bpf_map_    │               │
│  │  update()    │◄──────────────▶│  update()    │               │
│  └──────┬───────┘                └──────────────┘               │
│         │                                                        │
│         ▼                                                        │
│  ┌─────────────────────────────────────────────────────────┐     │
│  │  Map Types:                                             │     │
│  │  - BPF_MAP_TYPE_HASH: Generic key-value lookup         │     │
│  │  - BPF_MAP_TYPE_ARRAY: Fast index-based access         │     │
│  │  - BPF_MAP_TYPE_PERF_EVENT_ARRAY: Send events to userspace│   │
│  │  - BPF_MAP_TYPE_RINGBUF: Lock-free multi-producer queue│     │
│  │  - BPF_MAP_TYPE_LRU_HASH: Auto-evict old entries       │     │
│  │  - BPF_MAP_TYPE_STACK_TRACE: Store stack traces        │     │
│  └─────────────────────────────────────────────────────────┘     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. Kiến Trúc và Luồng Xử Lý

### 3.1 Observability Pipeline với eBPF

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    eBPF Observability Architecture                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Target Applications          eBPF Collectors              Storage          │
│  ┌──────────┐ ┌──────────┐   ┌──────────┐ ┌──────────┐   ┌──────────────┐  │
│  │  App A   │ │  App B   │   │  kprobe  │ │ tracepoint│   │  Prometheus  │  │
│  │ (Java)   │ │ (Go)     │   │  (func   │ │  (sched) │   │   (metrics)  │  │
│  └────┬─────┘ └────┬─────┘   │  lat)    │ │  (disk)  │   └──────────────┘  │
│       │            │         └────┬─────┘ └────┬─────┘   ┌──────────────┐  │
│       │            │              │            │         │   Grafana    │  │
│       │            │              └────────────┘         │  (visualize) │  │
│       │            │                     │               └──────────────┘  │
│       │            │                     ▼               ┌──────────────┐  │
│       │            │              ┌──────────────┐       │    Tempo/    │  │
│       │            │              │   eBPF Maps  │       │   Jaeger     │  │
│       │            │              │  (ringbuf)   │       │   (traces)   │  │
│       │            │              └──────┬───────┘       └──────────────┘  │
│       │            │                     │                                  │
│       │            │                     ▼                                  │
│       │            │              ┌──────────────┐                          │
│       │            │              │  Export      │                          │
│       │            │              │  (OTLP/      │                          │
│       │            │              │  Prometheus) │                          │
│       │            │              └──────────────┘                          │
│       │            │                                                       │
│       └────────────┴───────────────────────────────────────────────────────│
│                          (No instrumentation required)                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Flow:**
1. eBPF programs attach vào kernel functions (kprobe) hoặc tracepoints
2. Khi event xảy ra, eBPF program capture data (latency, args, stack trace)
3. Data được đưa vào maps (ringbuf/perfbuf)
4. Userspace exporter đọc từ maps và gửi đến monitoring backend
5. **Zero overhead khi không sử dụng** - chỉ active khi events fire

### 3.2 Cilium Service Mesh: Sidecar-less Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Cilium Service Mesh (eBPF-based)                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Traditional Istio (Sidecar)           Cilium (eBPF)                        │
│                                                                              │
│  ┌──────────┐  ┌──────────┐           ┌──────────┐  ┌──────────┐           │
│  │   App    │  │   App    │           │   App    │  │   App    │           │
│  │ Container│  │ Container│           │ Container│  │ Container│           │
│  └────┬─────┘  └────┬─────┘           └────┬─────┘  └────┬─────┘           │
│       │             │                      │             │                 │
│       ▼             ▼                      └─────────────┘                 │
│  ┌──────────┐  ┌──────────┐                     │                          │
│  │ Envoy    │  │ Envoy    │                     ▼                          │
│  │ Sidecar  │  │ Sidecar  │           ┌───────────────────┐                │
│  └────┬─────┘  └────┬─────┘           │  eBPF Socket      │                │
│       │             │                 │  Layer            │                │
│       └──────┬──────┘                 │  (sockops)        │                │
│              │                        └─────────┬─────────┘                │
│              ▼                                  │                          │
│       ┌────────────┐                            ▼                          │
│       │  Network   │                   ┌───────────────────┐                │
│       └────────────┘                   │  eBPF Network     │                │
│                                        │  Layer (TC/XDP)   │                │
│  Latency: ~3-5ms added                 └───────────────────┘                │
│  Memory: ~150MB per proxy                    │                              │
│                                              ▼                              │
│                                       ┌────────────┐                        │
│                                       │  Network   │                        │
│  Latency: ~0.3ms added                └────────────┘                        │
│  Memory: ~5MB node-level              Latency reduced 90%                  │
│                                       Memory reduced 95%                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Kiến trúc Cilium eBPF:**
- **Socket Layer (sockops, sockredirect):** Accelerate intra-node communication bằng cách bypass TCP stack
- **Network Layer (TC, XDP):** L4 load balancing, network policies, encryption
- **Identity-based Security:** Security dựa trên workload identity (labels) thay vì IP
- **No sidecar injection:** Không cần restart pods, không có startup latency

### 3.3 Falco Runtime Security

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Falco Runtime Security (eBPF Driver)                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Container Runtime                                                         │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  Kernel Events                                                      │    │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │    │
│  │  │  open()  │ │  execve()│ │  connect()│ │  setuid()│ │ mount()  │  │    │
│  │  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘  │    │
│  │       │            │            │            │            │        │    │
│  │       └────────────┴────────────┴────────────┴────────────┘        │    │
│  │                              │                                     │    │
│  │                              ▼                                     │    │
│  │  ┌─────────────────────────────────────────────────────────────┐   │    │
│  │  │              Falco eBPF Probe (modern_probe)                │   │    │
│  │  │  - attach to tracepoints: sys_enter_openat, sys_enter_execve │   │    │
│  │  │  - capture process context, args, file descriptors          │   │    │
│  │  └──────────────────────────┬──────────────────────────────────┘   │    │
│  │                             │                                      │    │
│  │                             ▼                                      │    │
│  │  ┌─────────────────────────────────────────────────────────────┐   │    │
│  │  │                     Ring Buffer                              │   │    │
│  │  │         (kernel → userspace event streaming)                 │   │    │
│  │  └──────────────────────────┬──────────────────────────────────┘   │    │
│  └─────────────────────────────┼──────────────────────────────────────┘    │
│                                ▼                                           │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        Falco Userspace Engine                        │   │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────┐  │   │
│  │  │   Rule Engine   │  │   Output        │  │   Alert Manager     │  │   │
│  │  │   (YAML rules)  │  │   (JSON/gRPC)   │  │   (priority-based)  │  │   │
│  │  │                 │  │                 │  │                     │  │   │
│  │  │  - shell in     │  │  → FalcoSidekick│  │  → Slack/PagerDuty │  │   │
│  │  │    container    │  │  → stdout       │  │  → Webhook          │  │   │
│  │  │  - priv esc     │  │  → file         │  │                     │  │   │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  Example Detection Rules:                                                    │
│  - Write to /etc inside container                                            │
│  - Spawn shell in production workload                                        │
│  - Outbound connection from known-sensitive pod                              │
│  - Privilege escalation (setuid binary execution)                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.4 Continuous Profiling (Parca/Pyroscope)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Continuous Profiling với eBPF                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Traditional Profiling                eBPF-based Profiling                  │
│  (Stop-the-world)                     (Always-on, low overhead)             │
│                                                                              │
│  ┌──────────────────┐                 ┌──────────────────┐                   │
│  │  Manual trigger  │                 │  Continuous      │                   │
│  │  heap dump/      │                 │  19Hz sampling   │                   │
│  │  CPU profile     │                 │  (every ~50ms)   │                   │
│  └────────┬─────────┘                 └────────┬─────────┘                   │
│           │                                    │                            │
│           ▼                                    ▼                            │
│  ┌──────────────────┐                 ┌──────────────────┐                   │
│  │  Application     │                 │  eBPF perf_event │                   │
│  │  pauses          │                 │  program         │                   │
│  │  (10s-60s)       │                 │  (no stop-world) │                   │
│  └──────────────────┘                 └────────┬─────────┘                   │
│                                                │                            │
│  Overhead: ~100% during capture                ▼                            │
│  (application blocked)                ┌──────────────────┐                   │
│                                       │  Stack unwinding │                   │
│                                       │  via BPF_MAP_    │                   │
│                                       │  TYPE_STACK_TRACE│                   │
│                                       └────────┬─────────┘                   │
│                                                │                            │
│                                                ▼                            │
│                                       ┌──────────────────┐                   │
│                                       │  Symbolization   │                   │
│                                       │  (address → func)│                   │
│                                       │  debuginfo/BTF   │                   │
│                                       └────────┬─────────┘                   │
│                                                │                            │
│  Overhead: <1% continuous                      ▼                            │
│  (no application impact)              ┌──────────────────┐                   │
│                                       │  Storage         │                   │
│                                       │  (parca/pyroscope│                   │
│                                       │   time-series DB)│                   │
│                                       └──────────────────┘                   │
│                                                                              │
│  Flame Graph Analysis:                                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  main()                                                              │    │
│  │  ┌──────────────────────────────────────────────────────────────┐   │    │
│  │  │  processRequest()                                             │   │    │
│  │  │  ┌──────────────┐ ┌──────────────────────────────────────┐   │   │    │
│  │  │  │ db.Query()   │ │  handleBusinessLogic()               │   │   │    │
│  │  │  │  (30%)       │ │  ┌──────────────┐  ┌──────────────┐ │   │   │    │
│  │  │  │              │ │  │ calcTax()    │  │ validate()   │ │   │   │    │
│  │  │  │              │ │  │  (15%)       │  │  (25%)       │ │   │   │    │
│  │  │  └──────────────┘ │  └──────────────┘  └──────────────┘ │   │   │    │
│  │  │                   └──────────────────────────────────────┘   │   │    │
│  │  └──────────────────────────────────────────────────────────────┘   │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. So Sánh Các Lựa Chọn

### 4.1 eBPF vs Traditional Approaches

| Aspect | eBPF Approach | Traditional Approach | Winner |
|--------|--------------|---------------------|--------|
| **Instrumentation** | Kernel-level, no app changes | Code changes, libraries | eBPF |
| **Overhead** | <1% for most use cases | 5-20% (APM agents) | eBPF |
| **Granularity** | Kernel function-level | Application-level | Depends |
| **Deployment** | Load once, monitor all | Per-application setup | eBPF |
| **Security** | Sandboxed by verifier | Full kernel access if root | eBPF |
| **Flexibility** | Limited by verifier | Unlimited | Traditional |
| **Debugging** | Challenging (verifier errors) | Standard tools | Traditional |

### 4.2 Service Mesh: Cilium vs Istio

| Feature | Istio (Envoy Sidecar) | Cilium (eBPF) |
|---------|----------------------|---------------|
| **Latency** | +3-5ms per hop | +0.3ms per hop |
| **Memory per pod** | ~150MB (Envoy) | ~5MB (node-level) |
| **CPU overhead** | 15-30% | 2-5% |
| **Startup time** | +2-5s (Envoy init) | Instant |
| **L7 protocol support** | Excellent (Envoy filters) | Growing (HTTP, gRPC, Kafka) |
| **mTLS termination** | Sidecar | Node-level (ipsec/wireguard) |
| **Observability** | Full L7 | L3/L4 native + L7 via proxy |

### 4.3 Runtime Security: Falco vs Alternatives

| Tool | Mechanism | Overhead | Best For |
|------|-----------|----------|----------|
| **Falco (eBPF)** | Kernel tracepoints | Low | Container runtime security |
| **Falco (kernel module)** | Kernel module | Lower | Environments cho phép kernel module |
| **Sysdig** | Kernel module + userspace | Medium | Deep system visibility |
| **Tetragon (Cilium)** | eBPF | Low | Kubernetes-aware security |
| **Aqua/Twistlock** | Agent-based | Higher | Commercial full-stack security |

---

## 5. Rủi Ro, Anti-Patterns và Lỗi Thường Gặp

### 5.1 Production Risks

| Rủi ro | Mô tả | Mitigation |
|--------|-------|------------|
| **Verifier rejection** | Valid logic bị từ chối vì verifier không thể prove safety | Rewrite logic, unroll loops, use bounded operations |
| **Kernel version compatibility** | eBPF features khác nhau giữa kernel versions | Use CO-RE (Compile Once Run Everywhere), BTF |
| **Instruction complexity limit** | MAX_BPF_INST (1M instructions) có thể hit với complex logic | Tách thành multiple programs, dùng tail calls |
| **Map memory exhaustion** | Unbounded map growth gây OOM | Dùng LRU maps, set max entries |
| **Probe effect** | Quá nhiều probes làm chậm system | Sampling, selective attachment |
| **Privileged access** | eBPF load yêu cầu CAP_BPF hoặc root | Dùng unprivileged BPF nếu kernel hỗ trợ |

### 5.2 Anti-Patterns

```c
// ❌ ANTI-PATTERN: Unbounded loop
for (int i = 0; i < n; i++) {  // n không được verify là bounded
    // ...
}

// ✅ CORRECT: Bounded loop với constant max
for (int i = 0; i < 10 && i < n; i++) {  // verifier thấy max 10 iterations
    // ...
}
```

```c
// ❌ ANTI-PATTERN: Null pointer dereference không check
struct task_struct *task = bpf_get_current_task();
bpf_probe_read(&pid, sizeof(pid), &task->pid);  // task có thể NULL

// ✅ CORRECT: Validate trước khi dereference
struct task_struct *task = bpf_get_current_task();
if (!task)
    return 0;
bpf_probe_read(&pid, sizeof(pid), &task->pid);
```

### 5.3 Kernel Version Gotchas

| Feature | Minimum Kernel | Notes |
|---------|---------------|-------|
| **BTF (BPF Type Format)** | 5.8 | Required cho CO-RE |
| **Ring buffer** | 5.8 | Thay thế perf buffer, multi-producer safe |
| **Fentry/Fexit** | 5.5 | Nhanh hơn kprobe, nhưng cần BCC hoặc BTF |
| **LSM BPF** | 5.7 | Security hooks, thay thế kernel modules |
| **BPF iterators** | 5.8 | Iterate kernel data structures |
| **BPF linked programs** | 5.12 | Multi-program coordination |

---

## 6. Khuyến Nghị Thực Chiến trong Production

### 6.1 Tooling Stack

| Layer | Recommended Tool | Alternative |
|-------|-----------------|-------------|
| **eBPF Loader** | libbpf (CO-RE) | bpftrace (one-liners), bcc (Python) |
| **Observability** | Pixie, groundcover | Grafana Beyla, Odigos |
| **Service Mesh** | Cilium | Istio ambient mesh |
| **Security** | Falco + Tetragon | Aqua, Sysdig Secure |
| **Profiling** | Parca, Pyroscope | Grafana Pyroscope (cloud) |
| **Network** | Cilium CNI | Calico eBPF mode |

### 6.2 Security Checklist

```
□ Không dùng legacy kernel module nếu eBPF alternative có sẵn
□ Audit tất cả eBPF programs loaded trong cluster (bpftool prog list)
□ Giới hạn CAP_BPF chỉ cho trusted service accounts
□ Monitor map memory usage (bpftool map show)
□ Dùng signed BTF files nếu có thể
□ Kiểm tra verifier logs khi development
□ Không bypass verifier với kernel module workarounds
```

### 6.3 Performance Tuning

```yaml
# Các tunables cần consider:

# 1. Sampling rate cho profiling
perf_event_sample_rate: 99  # Hz, tránh harmonic với timer interrupts

# 2. Ring buffer size
ring_buffer_pages: 262144   # 1GB buffer cho high-throughput events

# 3. Map sizes
max_entries_hash_map: 10000  # LRU để auto-evict nếu hit limit

# 4. Batch processing trong userspace
batch_read_size: 100  # Đọc nhiều events một lần từ ring buffer
```

---

## 7. Kết Luận

### Bản Chất Cốt Lõi

eBPF là một **paradigm shift** trong kernel engineering:
- Trước eBPF: Kernel development đòi hỏi C skills, kernel rebuild, module loading risks
- Sau eBPF: Safe, sandboxed programs có thể load/unload dynamically mà không reboot

### Trade-off Quan Trọng Nhất

| Wins | Costs |
|------|-------|
| Zero instrumentation overhead | Verifier complexity và learning curve |
| Kernel-level visibility | Limited by what's exposed (kprobes, tracepoints) |
| Safe sandboxed execution | Không phải arbitrary code (limited expressiveness) |
| CO-RE portability | Kernel version dependencies vẫn tồn tại |

### Rủi Ro Lớn Nhất

1. **Verifier complexity**: Programs phức tạp có thể bị từ chối mặc dù logic đúng
2. **Kernel dependency**: Một số features cần kernel mới (5.8+ cho BTF/CO-RE)
3. **Debugging difficulty**: Khó debug hơn userspace programs

### Khi Nào Nên Áp Dụng

✅ **Nên dùng:**
- Observability mà không thể instrument applications
- High-performance networking (DDoS mitigation, load balancing)
- Runtime security cho containers (Falco, Tetragon)
- Sidecar-less service mesh (Cilium)
- Continuous profiling không overhead

❌ **Không nên dùng:**
- Complex business logic (verifier limitations)
- Environments với legacy kernels (< 4.18)
- Khi simple userspace solutions đủ tốt

---

## 8. Tham Khảo

- eBPF Documentation: https://ebpf.io/
- Cilium Docs: https://docs.cilium.io/
- Falco Rules: https://falco.org/docs/rules/
- BPF CO-RE Reference: https://github.com/libbpf/libbpf
- Linux Kernel BPF Docs: https://www.kernel.org/doc/html/latest/bpf/
- BCC Tools: https://github.com/iovisor/bcc
- Parca: https://www.parca.dev/
- Pyroscope: https://pyroscope.io/
