# eBPF for Observability & Security: Kernel-Level Superpowers

> **Tóm tắt:** eBPF (extended Berkeley Packet Filter) là công nghệ cho phép chạy sandboxed programs trong kernel space mà không cần thay đổi kernel source code hay load kernel module. Bài viết này đi sâu vào bản chất cơ chế, kiến trúc internals, trade-offs giữa safety và performance, và ứng dụng thực tế trong observability và security tại quy mô production.

---

## 1. Mục tiêu của Task

Hiểu sâu bản chất eBPF:
- **Cơ chế hoạt động:** Làm sao code userspace có thể chạy an toàn trong kernel?
- **Kiến trúc:** Verifier, JIT compiler, Maps, Helper functions - chúng tương tác thế nào?
- **Hook points:** Những điểm nào trong kernel có thể attach eBPF program?
- **Trade-offs:** Safety vs Performance vs Flexibility
- **Production concerns:** Overhead, debugging, version compatibility, security risks
- **So sánh:** eBPF vs kernel module vs systemtap vs perf

---

## 2. Bản Chất và Cơ Chế Hoạt động

### 2.1. Vấn đề eBPF giải quyết

Truyền thống, để quan sát hoặc can thiệp vào kernel behavior, ta có 3 cách:

| Phương pháp | Ưu điểm | Nhược điểm |
|------------|---------|-----------|
| **Kernel Module** | Full access, high performance | Risky (crash kernel), complex development, maintenance burden |
| **System Call Tracing** (ptrace, /proc) | Safe, no kernel change | High overhead, limited visibility, coarse-grained |
| **Recompile Kernel** | Maximum flexibility | Impractical, maintenance nightmare |

**eBPF là giải pháp thứ tư:** Run sandboxed code in kernel space với safety guarantees của userspace.

### 2.2. Kiến trúc tổng quan

```
┌─────────────────────────────────────────────────────────────┐
│                     USER SPACE                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │  eBPF Tool  │  │  User App   │  │  Control Plane      │  │
│  │  (bcc/bpftrace)│  │  (C/Rust/Go) │  │  (Cilium/Falco)     │  │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │
└─────────┼────────────────┼────────────────────┼─────────────┘
          │                │                    │
          ▼                ▼                    ▼
┌─────────────────────────────────────────────────────────────┐
│                  eBPF SYSCALL (bpf())                       │
│         Load programs, create maps, attach probes           │
└─────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│                     KERNEL SPACE                            │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              eBPF VERIFIER                          │   │
│  │  - Static analysis for safety                       │   │
│  │  - Loop detection, bounds checking                  │   │
│  │  - No null dereference, no out-of-bounds access     │   │
│  └─────────────────────────────────────────────────────┘   │
│                          │                                  │
│                          ▼                                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              JIT COMPILER                           │   │
│  │  - Compile bytecode → native machine code           │   │
│  │  - x86_64, ARM64, RISC-V support                    │   │
│  └─────────────────────────────────────────────────────┘   │
│                          │                                  │
│                          ▼                                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │           eBPF PROGRAM (attached)                   │   │
│  │  - Runs in kernel context                           │   │
│  │  - Limited to ~1M instructions                      │   │
│  │  - No blocking, no loops (bounded)                  │   │
│  └─────────────────────────────────────────────────────┘   │
│                          │                                  │
│                          ▼                                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │           eBPF MAPS (shared memory)                 │   │
│  │  - Hash maps, arrays, ring buffers                  │   │
│  │  - Sharing data: kernel ↔ userspace                 │   │
│  │  - Persistent across program lifecycle              │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 2.3. Luồng xử lý chi tiết

**Bước 1: Compile (Userspace)**
```
C/Rust Source ──► LLVM Frontend ──► LLVM IR ──► eBPF Bytecode (.o file)
```

**Bước 2: Load và Verify (Kernel)**
```
sys_bpf(BPF_PROG_LOAD) 
    ├──► Verifier (static analysis)
    │       ├──► Check all code paths terminate
    │       ├──► Verify memory access bounds
    │       ├──► Ensure no uninitialized variables
    │       └──► Limit complexity (~1M instructions)
    │
    └──► JIT Compiler
            ├──► x86_64: bytecode → native x86
            ├──► ARM64: bytecode → native ARM
            └──► RISC-V: bytecode → native RISC-V
```

**Bước 3: Attach và Execute**
```
Kernel Event (syscall, network packet, tracepoint)
    └──► eBPF Program executes
            ├──► Read kernel data (ctx pointer)
            ├──► Call helper functions (bpf_get_current_pid, etc.)
            ├──► Update maps (shared state)
            └──► Return action (PASS/DROP/redirect/etc.)
```

### 2.4. eBPF Verifier - Cơ chế Safety

Verifier là "security gate" của eBPF. Nó đảm bảo:

| Kiểm tra | Mục đích | Hệ quả nếu fail |
|----------|----------|-----------------|
| **Termination** | Đảm bảo program kết thúc | Reject unbounded loops |
| **Bounds checking** | Mọi memory access đều hợp lệ | Prevent buffer overflow |
| **Null checks** | Không dereference null pointer | Prevent kernel crash |
| **Stack depth** | Giới hạn stack usage (~512 bytes) | Prevent stack overflow |
| **Instruction limit** | Max ~1M instructions | Limit complexity |
| **Helper whitelist** | Chỉ gọi approved kernel functions | Prevent arbitrary kernel access |

> **Quan trọng:** Verifier chạy ở load-time, không phải run-time. Một khi pass verification, program được JIT compile và chạy với native speed mà không cần thêm overhead kiểm tra.

### 2.5. eBPF Maps - Shared Memory

Maps là cấu trúc dữ liệu key-value chia sẻ giữa kernel và userspace:

| Map Type | Use Case | Performance |
|----------|----------|-------------|
| `BPF_MAP_TYPE_HASH` | Generic key-value storage | O(1) lookup |
| `BPF_MAP_TYPE_ARRAY` | Fixed-size indexed data | O(1) direct access |
| `BPF_MAP_TYPE_RINGBUF` | High-throughput events | Zero-copy, multi-producer |
| `BPF_MAP_TYPE_PERF_EVENT_ARRAY` | Profile samples, tracing | Batch processing |
| `BPF_MAP_TYPE_LRU_HASH` | Cache with eviction | Auto memory management |
| `BPF_MAP_TYPE_SOCKHASH` | Socket redirection | Load balancing |

**Ring Buffer** (Linux 5.8+) là breakthrough cho high-performance observability:
- Zero-copy từ kernel → userspace (via mmap)
- Multi-producer lockless (per-CPU buffers)
- Automatic backpressure handling

---

## 3. Hook Points và Ứng dụng

### 3.1. Network Stack (XDP, TC, Socket)

```
┌─────────────────────────────────────────────────────────┐
│  XDP (eXpress Data Path) - Earliest hook                │
│  ├── Driver level (before sk_buff allocation)           │
│  ├── Actions: DROP, PASS, TX, REDIRECT                  │
│  └── Performance: ~10M+ packets/sec per core            │
├─────────────────────────────────────────────────────────┤
│  TC (Traffic Control) - Classifier/Action               │
│  ├── Ingress/Egress at network stack                    │
│  ├── Access to full sk_buff                             │
│  └── Use: QoS, rate limiting, packet mangling           │
├─────────────────────────────────────────────────────────┤
│  Socket Hooks                                           │
│  ├── sockops: TCP state machine hooks                   │
│  ├── sk_msg: L7 message boundaries (HTTP/2, gRPC)       │
│  └── sk_skb: Socket buffer operations                   │
└─────────────────────────────────────────────────────────┘
```

**XDP** đặc biệt quan trọng cho:
- DDoS mitigation (drop malicious packets at earliest point)
- Load balancing (L4 redirect without userspace)
- Packet filtering (pre-firewall)

### 3.2. Tracing và Observability

| Hook Type | Event Source | Use Case |
|-----------|--------------|----------|
| **kprobe/kretprobe** | Kernel function entry/return | Trace any kernel function |
| **uprobe/uretprobe** | Userspace function entry/return | Trace application internals |
| **tracepoint** | Kernel-defined static probes | Stable ABI, recommended |
| **perf_event** | Hardware counters (PMC) | CPU profiling |
| **fentry/fexit** | Function entry/exit (BPF-only) | Lower overhead than kprobe |

### 3.3. Security (LSM Hooks)

Linux Security Module (LSM) hooks cho phép eBPF can thiệp vào security decisions:

```
Application syscall
    └──► LSM Hook (e.g., security_file_open)
            └──► eBPF LSM Program
                    ├──► ALLOW: Proceed normally
                    ├──► DENY: Return -EPERM
                    └──► Audit: Log và allow
```

**Use cases:**
- Container escape prevention
- File access control
- Network policy enforcement
- Capability monitoring

---

## 4. Ứng dụng trong Observability

### 4.1. Continuous Profiling (Parca, Pyroscope)

Truyền thống: Sampling profiler gây overhead đáng kể (~5-10%)

eBPF approach:
```
Perf Event (CPU cycle counter overflow)
    └──► eBPF program
            ├──► Read stack trace (kernel + userspace)
            ├──► Symbolize (pid + instruction pointer)
            └──► Ring buffer → userspace
```

**Trade-offs:**
- ✅ Overhead: <1% (asynchronous, no ptrace)
- ✅ Full system view (all processes)
- ✅ Kernel + userspace stacks
- ❌ Requires debug symbols for userspace
- ❌ Stack depth limits (kernel: 127 frames)

### 4.2. Distributed Tracing (OpenTelemetry eBPF)

eBPF có thể tự động instrument applications mà không cần code change:

```
HTTP Request
    └──► uprobe on SSL_read/SSL_write (capture payload)
            └──► Parse HTTP headers (extract trace context)
                    └──► Generate span → ring buffer
```

**Limitations:**
- Chỉ capture data tại syscall boundary
- Không thấy application internal spans
- TLS encryption cần SSL_UPROBE tricks

### 4.3. Network Observability (Cilium Hubble, Pixie)

```
┌─────────────────────────────────────────────────────────┐
│  Kernel Space                                           │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐ │
│  │  kprobe on  │───►│  eBPF prog  │───►│  Ring Buffer│ │
│  │  tcp_sendmsg│    │  parse L7   │    │  (events)   │ │
│  └─────────────┘    └─────────────┘    └──────┬──────┘ │
└────────────────────────────────────────────────┼────────┘
                                                 │
┌────────────────────────────────────────────────┼────────┐
│  Userspace                                     │        │
│  ┌─────────────────────────────────────────────▼──────┐ │
│  │  Protocol parsers (HTTP, gRPC, Kafka, DNS...)      │ │
│  │  ├─ Extract: latency, status codes, payload size   │ │
│  │  ├─ Correlate: requests ↔ responses               │ │
│  │  └─ Export: metrics, traces, logs                 │ │
│  └────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

---

## 5. Ứng dụng trong Security

### 5.1. Cilium - eBPF-based Service Mesh

**Kiến trúc Cilium:**
```
┌─────────────────────────────────────────────────────────┐
│  Control Plane (cilium-operator)                        │
│  ├── Monitor network policies                           │
│  ├── Sync with Kubernetes API                           │
│  └── Distribute policies to agents                      │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│  Data Plane (cilium-agent, per-node)                    │
│  ┌─────────────────────────────────────────────────┐   │
│  │  eBPF Programs                                  │   │
│  │  ├── XDP: DDoS protection, LB                   │   │
│  │  ├── TC: Network policy enforcement             │   │
│  │  ├── Socket: L7 protocol visibility             │   │
│  │  └── LSM: Security policy enforcement           │   │
│  └─────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────┐   │
│  │  eBPF Maps                                      │   │
│  │  ├── Policy cache (allow/deny rules)            │   │
│  │  ├── Connection tracking (conntrack)            │   │
│  │  ├── Load balancing (service backends)          │   │
│  │  └── Identity cache (pod → security identity)   │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

**Key capabilities:**
- **Identity-based security:** Pod identity thay vì IP-based (dynamic IP problem)
- **Layer 7 policies:** HTTP path, method, header-based rules
- **mTLS:** Certificate management và encryption
- **Observability:** Built-in metrics và flow logs

**Trade-off vs Istio (sidecar):**
| Aspect | Cilium (eBPF) | Istio (Sidecar) |
|--------|---------------|-----------------|
| Latency | Lower (no proxy hop) | Higher (envoy hop) |
| Resource | Lower (no sidecar) | Higher (per-pod envoy) |
| L7 Protocols | Limited (HTTP, gRPC, Kafka...) | Full (any via envoy) |
| Wasm Extensions | Limited | Rich |
| Maturity | Newer | More established |

### 5.2. Falco - Runtime Security

Falco sử dụng eBPF để monitor system calls và detect suspicious behavior:

```
Syscall event (execve, open, connect...)
    └──► eBPF program (collect metadata)
            └──► Ring buffer → Falco engine
                    └──► Rule evaluation
                            ├──► Match → Alert
                            └──► No match → Continue
```

**Default rules detect:**
- Privilege escalation (sudo, setuid)
- Sensitive file access (/etc/shadow)
- Outbound connections from sensitive processes
- Container escapes (privileged mode abuse)
- Shell execution trong containers

**Production concerns:**
- Rule tuning critical (false positives)
- Event volume có thể rất cao
- Kernel version compatibility

### 5.3. Tetragon - eBPF-based Security Observability

Tetragon (của Cilium authors) đi sâu hơn:
- **Process execution tracking:** Binary hash, ancestry
- **File integrity monitoring:** Access patterns
- **Network security:** Deep packet inspection
- **Kill switch:** Có thể kill process khi detect threat

---

## 6. Trade-offs và Production Concerns

### 6.1. Performance Trade-offs

| Aspect | Cost | Mitigation |
|--------|------|------------|
| **Verifier time** | Load latency (ms to seconds) | Pre-compile, cache verified programs |
| **JIT compilation** | One-time cost at load | Acceptable for long-running programs |
| **Runtime overhead** | Varies by hook type | Optimize bytecode, use batching |
| **Memory (maps)** | Kernel memory pressure | Size limits, LRU eviction |
| **Ring buffer** | Memory + CPU for high throughput | Proper sizing, batch reads |

### 6.2. Hook-specific Overhead

```
Overhead (low to high):
XDP (fast path) < TC < Tracepoints < Kprobes < Uprobes

XDP:        ~10-50ns per packet (can handle 10Mpps+ per core)
TC:         ~100-500ns per packet
Tracepoint: ~100-1000ns per event
Kprobe:     ~500-2000ns (function entry/return)
Uprobe:     ~1-5μs (userspace trapping)
```

### 6.3. Kernel Version Compatibility

| Feature | Minimum Kernel | Notes |
|---------|----------------|-------|
| Basic eBPF | 3.18 | Limited functionality |
| Maps | 3.19 | Hash, array maps |
| Tracepoints | 4.7 | Stable tracing |
| XDP | 4.8 | Driver support varies |
| BPF Type Format (BTF) | 5.2 | Portable CO-RE |
| Ring buffer | 5.8 | High-perf events |
| LSM hooks | 5.7 | Security policies |
| BPF iterators | 5.8 | Walking kernel data |

> **CO-RE (Compile Once, Run Everywhere):** Nhờ BTF (BPF Type Format), eBPF programs có thể compile một lần và chạy trên nhiều kernel versions mà không cần recompile. Điều này critical cho production deployment.

### 6.4. Debugging và Observability của chính eBPF

| Tool | Purpose |
|------|---------|
| `bpftool` | List programs, maps, dump bytecode, pin/unpin |
| `/sys/kernel/debug/tracing/` | Tracepoint debug |
| `perf` | Profile eBPF program execution |
| `bpftrace` | Quick ad-hoc debugging |
| `libbpf` logs | Verifier error messages |

**Common verifier errors:**
- `invalid memory access`: Bounds checking failed
- `back-edge from insn X to Y`: Loop detected
- `R0 invalid mem access 'scalar'`: Return value not checked
- `invalid map pointer value`: Invalid map FD

### 6.5. Security Risks của eBPF

Mặc dù eBPF được thiết kế safe, vẫn có risks:

| Risk | Description | Mitigation |
|------|-------------|------------|
| **DoS via complexity** | Crafted program làm verifier chạy lâu | Instruction limits, complexity limits |
| **Speculative execution** | Spectre-like attacks via eBPF | Kernel mitigations, constant blinding |
| **Privilege escalation** | Bug trong verifier hoặc JIT | Regular kernel updates, lockdown mode |
| **Information leak** | Read arbitrary kernel memory | Pointer masking, range checks |
| **Resource exhaustion** | Large maps, many programs | RLIMIT_MEMLOCK, cgroup limits |

> **Kernel Lockdown Mode:** Có thể restrict eBPF loading để chỉ privileged users (CAP_BPF) hoặc signed programs.

### 6.6. Deployment Best Practices

1. **Development:**
   - Use `bpftrace` cho quick prototyping
   - Test trên nhiều kernel versions
   - Validate với verifier strict mode

2. **Testing:**
   - Load test với realistic traffic
   - Monitor eBPF program memory usage
   - Test error paths (map full, ring buffer overflow)

3. **Production:**
   - Pin programs để survive process restart
   - Use CO-RE cho portability
   - Set resource limits (RLIMIT_MEMLOCK)
   - Monitor: `bpftool prog show`, map fullness
   - Graceful degradation khi eBPF unavailable

---

## 7. So Sánh với Các Giải Pháp Khác

### 7.1. eBPF vs Kernel Module

| Criteria | eBPF | Kernel Module |
|----------|------|---------------|
| **Safety** | Verifier guarantees | No guarantees (can crash kernel) |
| **Development** | C/Rust, userspace tooling | C, kernel build system |
| **Deployment** | Runtime load, no reboot | Module load, version-dependent |
| **Performance** | Native (JIT) | Native |
| **Flexibility** | Limited (verified) | Unlimited |
| **Maintenance** | Kernel API stable | Frequent breaking changes |
| **Debugging** | Easier (userspace tools) | Kernel debugger |

**Khi nào dùng Kernel Module?**
- Cần access hardware trực tiếp
- Device drivers
- Performance counter access không có trong eBPF helpers

### 7.2. eBPF vs SystemTap / DTrace

| Feature | eBPF | SystemTap | DTrace |
|---------|------|-----------|--------|
| **Portability** | Linux only | Linux only | Solaris, macOS, FreeBSD |
| **Overhead** | Low (JIT) | High (script → C → module) | Low (DIF VM) |
| **Safety** | Verifier | Module-based | DIF verifier |
| **Maturity** | Rapidly evolving | Mature, declining | Mature, stable |
| **Ecosystem** | Rich (Cilium, Falco, etc.) | Limited | Solaris-focused |

### 7.3. eBPF vs ptrace-based Tools (strace, gdb)

| Aspect | eBPF | ptrace |
|--------|------|--------|
| **Mechanism** | Kernel hooks | Process signal interception |
| **Overhead** | Low (<1%) | High (context switches) |
| **Scope** | System-wide | Single process |
| **Information** | Rich (kernel state) | Limited (registers, memory) |
| **Intrusiveness** | Non-intrusive | Can affect target behavior |

---

## 8. Khuyến nghị Thực chiến trong Production

### 8.1. Observability Stack Recommendation

```
┌─────────────────────────────────────────────────────────┐
│  Visualization Layer                                    │
│  ├── Grafana (metrics, logs, traces)                   │
│  └── Jaeger/Tempo (distributed tracing)                │
├─────────────────────────────────────────────────────────┤
│  Data Collection                                        │
│  ├── Prometheus (metrics)                              │
│  ├── Loki (logs)                                       │
│  └── OpenTelemetry Collector (traces)                  │
├─────────────────────────────────────────────────────────┤
│  eBPF Data Sources                                      │
│  ├── Pixie/Cilium Hubble (auto-instrumentation)        │
│  ├── Parca/Pyroscope (continuous profiling)            │
│  └── Custom eBPF exporters                             │
└─────────────────────────────────────────────────────────┘
```

### 8.2. Security Stack Recommendation

```
┌─────────────────────────────────────────────────────────┐
│  Security Operations                                    │
│  ├── SIEM (Splunk, ELK, Sentinel)                      │
│  └── SOAR (automation, response)                       │
├─────────────────────────────────────────────────────────┤
│  eBPF Security Tools                                    │
│  ├── Falco (runtime threat detection)                  │
│  ├── Cilium (network security, L7 policies)            │
│  └── Tetragon (deep observability + enforcement)       │
├─────────────────────────────────────────────────────────┤
│  Enforcement                                            │
│  ├── Network policies (Cilium)                         │
│  ├── Runtime policies (Falco rules)                    │
│  └── Process killing (Tetragon)                        │
└─────────────────────────────────────────────────────────┘
```

### 8.3. Migration Path

| Phase | Action | Timeline |
|-------|--------|----------|
| **1. Assessment** | Audit current observability/security gaps | Week 1-2 |
| **2. Pilot** | Deploy eBPF tool trên non-critical cluster | Week 3-4 |
| **3. Integration** | Connect eBPF data vào existing monitoring | Week 5-8 |
| **4. Scale** | Rollout toàn bộ production | Week 9-12 |
| **5. Optimize** | Tune rules, reduce false positives | Ongoing |

---

## 9. Kết Luận

### Bản chất cốt lõi

eBPF là **kernel extension mechanism** an toàn và hiệu quả:
- **Safety:** Verifier đảm bảo code không crash kernel
- **Performance:** JIT compilation cho native speed
- **Flexibility:** Hook tại nhiều layer (network, tracing, security)

### Trade-off quan trọng nhất

> **Safety vs Flexibility:** Verifier giới hạn những gì eBPF có thể làm để đảm bảo safety. Điều này đồng nghĩa với việc eBPF không thể thay thế hoàn toàn kernel modules cho mọi use case, nhưng đủ mạnh cho 95% observability và security needs.

### Rủi ro lớn nhất trong production

1. **Kernel version compatibility:** CO-RE giảm nhưng không loại bỏ vấn đề này
2. **Verifier complexity:** Complex programs có thể bị reject hoặc load chậm
3. **Resource limits:** Unbounded map growth có thể OOM kernel
4. **Tooling maturity:** Debugging eBPF vẫn khó hơn userspace code

### Khi nào nên dùng eBPF

| Use Case | Recommendation |
|----------|----------------|
| **High-performance networking** | ✅ XDP/TC cho DDoS, LB |
| **Kubernetes observability** | ✅ Cilium, Pixie cho auto-instrumentation |
| **Continuous profiling** | ✅ Parca/Pyroscope cho low-overhead profiling |
| **Runtime security** | ✅ Falco/Tetragon cho threat detection |
| **Custom device drivers** | ❌ Kernel module vẫn cần thiết |
| **Deep kernel modification** | ❌ eBPF không đủ flexible |

### Tương lai

- **eBPF in userspace (uBPF, rbpf):** Running eBPF outside kernel
- **eBPF for Windows:** Microsoft đang port eBPF sang Windows
- **Hardware offload:** SmartNICs hỗ trợ eBPF (NVIDIA BlueField, AWS Nitro)
- **AI/ML integration:** eBPF cho model serving observability

---

## Tài liệu tham khảo

1. **Linux Kernel Documentation:** https://docs.kernel.org/bpf/
2. **BPF Performance Tools** - Brendan Gregg
3. **Cilium Documentation:** https://docs.cilium.io/
4. **Falco Documentation:** https://falco.org/
5. **eBPF.io Community Resources:** https://ebpf.io/
6. **BPF CO-RE Reference:** https://facebookmicrosites.github.io/bpf/blog/2020/02/19/bpf-portability-and-co-re.html

---

*Research completed: 2026-03-27*
