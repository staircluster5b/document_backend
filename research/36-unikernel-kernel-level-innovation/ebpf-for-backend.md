# eBPF for Backend - Deep Dive Research

## 1. Mục tiêu của Task

Hiểu sâu bản chất eBPF (extended Berkeley Packet Filter) như một công nghệ kernel-level programming, phân tích các ứng dụng thực tế trong backend systems (observability, security, networking), và đánh giá trade-off khi áp dụng trong production.

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1. eBPF là gì? Khác biệt với BPF gốc

**BPF (Berkeley Packet Filter)** ban đầu được thiết kế năm 1992 tại Berkeley để filter network packets trong kernel space mà không cần copy data sang user space. Cú pháp giới hạn, chỉ hỗ trợ 2 registers.

**eBPF (extended BPF)** từ Linux 3.18+ (2014) là cuộc cách mạng:
- 10 64-bit registers (giống x86-64)
- 512 bytes stack
- Unbounded storage via Maps
- Helper functions để tương tác kernel
- Verifier đảm bảo safety
- JIT compilation sang native code

> **Bản chất cốt lõi**: eBPF cho phép **chạy sandboxed programs trong kernel space** mà không cần modify kernel source code hay load kernel modules. Đây là sự kết hợp giữa hiệu năng của kernel-level execution và safety của user-space programming.

### 2.2. Kiến trúc eBPF Runtime

```
┌─────────────────────────────────────────────────────────────────┐
│                        USER SPACE                                │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │  User App   │  │  CLI Tools  │  │  eBPF Loader/Verifier   │  │
│  │  (C/Go/Rust)│  │ (bpftool)   │  │  (libbpf, cilium/ebpf)  │  │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼ syscall (bpf())
┌─────────────────────────────────────────────────────────────────┐
│                      KERNEL SPACE                                │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │              eBPF Verifier (Security)                    │    │
│  │  • Loop detection & bounded execution                   │    │
│  │  • Type checking & pointer arithmetic validation        │    │
│  │  • Uninitialized variable detection                     │    │
│  │  • Out-of-bounds access prevention                      │    │
│  └─────────────────────────────────────────────────────────┘    │
│                              │                                   │
│                              ▼                                   │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │           JIT Compiler (x86_64/ARM64/...)               │    │
│  │     eBPF bytecode ──► Native machine code               │    │
│  └─────────────────────────────────────────────────────────┘    │
│                              │                                   │
│                              ▼                                   │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │              eBPF Program (attached to hook)            │    │
│  │  • Kprobes/Kretprobes (kernel functions)                │    │
│  │  • Uprobes/Uretprobes (user functions)                  │    │
│  │  • Tracepoints (static kernel markers)                  │    │
│  │  • XDP/TC (network packets)                             │    │
│  │  • LSM (security hooks)                                 │    │
│  └─────────────────────────────────────────────────────────┘    │
│                              │                                   │
│                              ▼                                   │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │              eBPF Maps (KV Store)                        │    │
│  │  • Hash maps, Arrays, LRU, LPM Trie, Ring Buffer, ...   │    │
│  │  • Sharing data giữa kernel ↔ user space                │    │
│  │  • Sharing data giữa các eBPF programs                  │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

### 2.3. Cơ Chế Attach Points (Hook Types)

| Hook Type | Use Case | Backend Application |
|-----------|----------|-------------------|
| **Kprobe/Kretprobe** | Dynamic tracing kernel functions | System call monitoring, kernel behavior analysis |
| **Uprobe/Uretprobe** | Dynamic tracing user-space functions | Application profiling, function-level tracing |
| **Tracepoint** | Static tracing (stable kernel API) | Reliable monitoring, standardized events |
| **XDP (eXpress Data Path)** | Packet processing at earliest point | High-performance load balancing, DDoS protection |
| **TC (Traffic Control)** | Packet filtering/classification | Traffic shaping, rate limiting |
| **Socket Filter** | Socket-level packet filtering | Custom load balancing, protocol analysis |
| **LSM (Linux Security Modules)** | Security policy enforcement | Mandatory access control, sandboxing |
| **Cgroup** | Resource control and monitoring | Container resource limits, billing |
| **Perf Events** | Hardware/software performance counters | CPU profiling, cache analysis |

### 2.4. eBPF Verifier - "The Gatekeeper"

Verifier là thành phần **không thể tách rồi** của eBPF, chạy trước khi program được load:

**Những gì Verifier kiểm tra:**
1. **Termination guarantee**: Mọi path phải kết thúc (no infinite loops)
2. **Memory safety**: Tất cả memory access phải trong bounds
3. **Type safety**: Pointer arithmetic được kiểm soát chặt chẽ
4. **No uninitialized reads**: Mọi variable phải được gán trước khi đọc
5. **Stack limits**: Không vượt quá 512 bytes stack
6. **Complexity limits**: Số lượng instructions có giới hạn (~1M instructions)

> **Trade-off quan trọng**: Verifier đảm bảo safety nhưng là nguồn lớn nhất của **frustration cho developers**. Code logic đúng có thể bị reject vì verifier không chứng minh được safety. Kỹ thuật viết code "verifier-friendly" là một skill riêng.

**Ví dụ code bị verifier reject:**
```c
// ❌ Bị reject: verifier không thể chứng minh loop kết thúc
for (int i = 0; i < variable; i++) { ... }  // variable từ map

// ✅ Accepted: static bounds
for (int i = 0; i < 10; i++) { ... }

// ✅ Accepted: với explicit unroll hoặc bounded variable
int limit = bpf_map_lookup_elem(...);
if (limit > 100) limit = 100;  // Clamp to known bound
for (int i = 0; i < limit; i++) { ... }
```

### 2.5. eBPF Maps - Data Sharing Mechanism

Maps là cấu trúc dữ liệu **key-value** dùng để:
- Lưu state giữa các invocation của eBPF program
- Giao tiếp giữa kernel eBPF và user-space applications
- Chia sẻ dữ liệu giữa các eBPF programs

| Map Type | Đặc điểm | Use Case |
|----------|----------|----------|
| `BPF_MAP_TYPE_HASH` | Generic hash table | General purpose storage |
| `BPF_MAP_TYPE_ARRAY` | Fixed size, O(1) access | Fast counters, configuration |
| `BPF_MAP_TYPE_LRU_HASH` | Auto-eviction khi full | Cache với memory limits |
| `BPF_MAP_TYPE_PERCPU_*` | Per-CPU instances | Lock-free counters, performance |
| `BPF_MAP_TYPE_RINGBUF` | MPSC lock-free buffer | High-throughput events |
| `BPF_MAP_TYPE_LPM_TRIE` | Longest prefix match | IP routing tables |
| `BPF_MAP_TYPE_SOCKHASH` | Socket references | Socket redirection, load balancing |
| `BPF_MAP_TYPE_STACK_TRACE` | Stack trace storage | Profiling, debugging |

> **Performance note**: `PERCPU` variants loại bỏ cache coherency overhead bằng cách có instance riêng cho mỗi CPU. Aggregation sang global value được thực hiện trong user-space.

## 3. Ứng Dụng trong Backend Systems

### 3.1. Observability - "Seeing Inside the Black Box"

#### 3.1.1. Continuous Profiling

**Bản chất**: Thay vì sampling-based profiling (gây overhead cao), eBPF trace function entry/exit với overhead <1%.

```
┌─────────────────────────────────────────┐
│         Application Process             │
│              main()                     │
│                │                        │
│                ▼                        │
│           processRequest()              │
│     ┌────────┼────────┐                 │
│     ▼        ▼        ▼                 │
│  dbQuery() cache()  validate()          │
│                                         │
│  ←── Uprobe/Uretprobe attached here     │
└─────────────────────────────────────────┘
              │
              ▼
    ┌─────────────────────┐
    │   eBPF Program      │
    │  • Record timestamp │
    │  • Build stack trace│
    │  • Update histogram │
    └─────────────────────┘
              │
              ▼
    ┌─────────────────────┐
    │   Ring Buffer Map   │
    │  (batch transfer)   │
    └─────────────────────┘
              │
              ▼
    ┌─────────────────────┐
    │   User-space Agent  │
    │  (Parca, Pyroscope) │
    └─────────────────────┘
```

**Công cụ**: Parca, Pyroscope, Pixie, Odigos

#### 3.1.2. Distributed Tracing tại Kernel Level

Vấn đề: Context propagation qua nhiều services thường đòi hỏi instrumentation trong code.

eBPF approach:
- Hook vào network syscalls (`sendto`, `recvfrom`)
- Tự động inject/extract trace context vào HTTP headers
- Không cần modify application code

**Trade-off**:
| Approach | Pros | Cons |
|----------|------|------|
| Manual instrumentation | Full control, rich context | Tedious, incomplete coverage |
| eBPF auto-instrumentation | Complete coverage, no code change | Limited to syscalls, may miss app-level context |
| Hybrid | Best of both | Complexity |

#### 3.1.3. Network Flow Monitoring

eBPF có thể capture **tất cả** network traffic với metadata:
- Process ID, container ID, pod name
- Connection duration, bytes transferred
- DNS queries/responses
- TCP state transitions

> **Production insight**: Cilium Hubble cung cấp network observability cho Kubernetes clusters dựa hoàn toàn trên eBPF.

### 3.2. Security - "Kernel-Level Enforcement"

#### 3.2.1. Runtime Security (Falco, Tetragon)

**Bản chất**: Monitor system calls để phát hiện suspicious behavior theo thời gian thực.

```
Syscall Flow:
┌──────────┐    ┌─────────────────┐    ┌────────────────┐
│  Process │───►│  Tracepoint/    │───►│  eBPF Program  │
│  execve  │    │  Raw Tracepoint │    │  (Falco rule)  │
└──────────┘    └─────────────────┘    └───────┬────────┘
                                               │
                              Match rule?     │
                              ┌───────────────┘
                              ▼
                    ┌─────────────────┐
                    │  Send alert to  │
                    │  user-space     │
                    └─────────────────┘
```

**Falco rules ví dụ**:
```yaml
- rule: Unauthorized container escape
  desc: Detect process running outside container cgroup
  condition: >
    spawned_process and
    container and
    proc.vpgid != host_vpgid
  output: >
    Potential container escape
    (user=%user.name command=%proc.cmdline)
  priority: CRITICAL
```

#### 3.2.2. LSM (Linux Security Module) eBPF Programs

Từ Linux 5.7+, eBPF có thể attach vào LSM hooks để enforce security policies:

```c
// Ví dụ: Prevent execution of specific binaries
SEC("lsm/file_mprotect")
int BPF_PROG(prevent_exec, struct vm_area_struct *vma,
             unsigned long reqprot, unsigned long prot)
{
    // Chỉ cho phép executable memory nếu process authorized
    if (prot & PROT_EXEC && !is_authorized(current)) {
        return -EPERM;  // Deny
    }
    return 0;  // Allow
}
```

> **So sánh với SELinux/AppArmor**: eBPF LSM linh hoạt hơn (programmable policies) nhưng đòi hỏi kernel 5.7+.

#### 3.2.3. Network Security (XDP-based DDoS Protection)

XDP (eXpress Data Path) chạy **trước cả khi packet đến network stack**:

```
Packet Flow với XDP:

    Network Interface Card (NIC)
              │
              ▼
    ┌─────────────────────┐
    │     XDP Hook        │  ← eBPF program chạy ở đây
    │  (Driver level)     │
    └─────────────────────┘
              │
        ┌─────┴─────┐
        ▼           ▼
    ┌───────┐   ┌────────┐
    │ XDP_  │   │ XDP_   │
    │ DROP  │   │ PASS   │
    └───────┘   └────────┘
                    │
                    ▼
            Kernel Network Stack
```

**Performance**: XDP có thể drop packets ở rate **millions/second** trên single core.

**Use cases**:
- DDoS protection: Drop malicious traffic trước khi consume resources
- IP allowlisting: Fast-path cho trusted IPs
- SYN flood protection: Rate limiting connection attempts

### 3.3. Networking - "In-Kernel Load Balancing"

#### 3.3.1. Cilium: eBPF-based Kubernetes CNI

Cilium là **production-grade** implementation của eBPF networking:

```
┌─────────────────────────────────────────────────────────────┐
│                     Kubernetes Pod                          │
│  ┌─────────────────┐      ┌─────────────────────────────┐   │
│  │   App Container │      │   Sidecar (optional Envoy)  │   │
│  │   (no iptables) │      │   (for L7 filtering)        │   │
│  └────────┬────────┘      └──────────────┬──────────────┘   │
│           │                              │                   │
│           └──────────────┬───────────────┘                   │
│                          ▼                                   │
│              ┌───────────────────────┐                       │
│              │   eBPF Socket Filter  │  ← Fast-path          │
│              │   (sockops/sk_msg)    │     for L3/L4         │
│              └───────────┬───────────┘                       │
│                          │                                   │
│           ┌──────────────┼──────────────┐                   │
│           ▼              ▼              ▼                   │
│      ┌─────────┐   ┌──────────┐   ┌──────────┐              │
│      │ Pod A   │   │ Pod B    │   │ External │              │
│      └─────────┘   └──────────┘   └──────────┘              │
└─────────────────────────────────────────────────────────────┘
```

**Lợi ích của Cilium so với iptables-based CNIs**:
| Aspect | Iptables | Cilium eBPF |
|--------|----------|-------------|
| Complexity | O(n) rules | O(1) lookup |
| Performance | Degrades with scale | Consistent |
| Observability | Limited | Rich L3-L7 visibility |
| Policy enforcement | Stateful tracking | Efficient maps |
| Identity-based | IP-based | Security identity |

#### 3.3.2. eBPF-based Service Mesh (Cilium Service Mesh)

Thay vì sidecar proxies (Istio), Cilium cung cấp:
- **Sidecar-less**: eBPF programs trong kernel xử lý L3-L4
- **Envoy per-node**: Shared Envoy cho L7 (giảm memory footprint)

**Trade-off**:
| Feature | Istio (Sidecar) | Cilium Service Mesh |
|---------|-----------------|---------------------|
| Resource overhead | High (1 sidecar/pod) | Low (shared proxy) |
| mTLS termination | Pod-level | Node-level |
| L7 features | Full Envoy features | Limited by shared model |
| Security isolation | Strong pod boundary | Weaker (shared proxy) |

### 3.4. Custom Load Balancing

#### 3.4.1. Maglev Consistent Hashing trong eBPF

Cilium implement Maglev consistent hashing (Google's algorithm) trong eBPF:

```c
// Simplified concept
struct lb_key {
    __u32 src_ip;
    __u32 dst_ip;
    __u16 src_port;
    __u16 dst_port;
};

__section("bpf_sock")
int lb_connect(struct bpf_sock_addr *ctx)
{
    struct lb_key key = extract_key(ctx);
    
    // Maglev lookup: O(1), minimal disruption khi backend changes
    __u32 backend_id = maglev_lookup(&maglev_table, &key);
    
    struct backend *be = bpf_map_lookup_elem(&backends, &backend_id);
    if (be) {
        ctx->user_ip = be->ip;  // Redirect connection
    }
    
    return SK_PASS;
}
```

**Đặc điểm**:
- Connection affinity mà không cần state table
- Backend failure chỉ ảnh hưởng 1/N connections
- Lookup O(1)

## 4. So Sánh Các Lựa Chọn

### 4.1. eBPF vs Traditional Approaches

| Aspect | eBPF | Kernel Module | Userspace | Interpretation |
|--------|------|---------------|-----------|----------------|
| **Performance** | Native speed | Native speed | Lower (context switches) | eBPF JIT = native |
| **Safety** | Verifier đảm bảo | Risky (kernel panic) | Safe (isolated) | eBPF an toàn hơn modules |
| **Flexibility** | Limited by verifier | Full kernel API | Full userspace API | Trade-off safety/flexibility |
| **Deployment** | Runtime loadable | Requires build/install | Simple | eBPF linh hoạt |
| **Portability** | Stable khi dùng CO-RE | Kernel version specific | High | CO-RE quan trọng |
| **Development** | Steep learning curve | Complex | Easier | eBPF khó học |

### 4.2. CO-RE: Compile Once, Run Everywhere

**Vấn đề**: Kernel data structures thay đổi giữa versions → eBPF programs cần recompile.

**Giải pháp CO-RE** (Linux 5.2+):
- BTF (BPF Type Format): Metadata về kernel types
- Relocation: Điều chỉnh field offsets tại load time
- Vmlinux.h: Generated header từ kernel BTF

```c
// Code CO-RE compatible
struct task_struct *task = (struct task_struct *)bpf_get_current_task();

// Read field với CO-RE relocation
pid_t pid = BPF_CORE_READ(task, pid);
uid_t uid = BPF_CORE_READ(task, real_cred, uid.val);
```

> **Production requirement**: CO-RE là **bắt buộc** cho production deployments vì không thể rebuild eBPF programs cho mọi kernel version.

### 4.3. eBPF Development Stacks

| Stack | Use Case | Maturity |
|-------|----------|----------|
| **libbpf + C** | Production tools, maximum control | Stable |
| **Cilium/ebpf (Go)** | Go-based infrastructure tools | Production-ready |
| **Aya (Rust)** | Rust ecosystem, memory safety | Growing |
| **bpftrace** | One-liners, ad-hoc debugging | Stable |
| **BCC (Python/C)** | Rapid prototyping | Legacy (chuyển sang libbpf) |

## 5. Rủi Ro, Anti-patterns, Lỗi Thường Gặp

### 5.1. Common Pitfalls

#### 5.1.1. Verifier Complexity Limits

```c
// ❌ Code phức tạp có thể hit complexity limit
if (condition1) {
    if (condition2) {
        if (condition3) {
            // ... nested quá sâu
        }
    }
}

// ✅ Refactor thành functions, dùng early returns
if (!condition1) return;
if (!condition2) return;
// ...
```

#### 5.1.2. Map Lookup Without NULL Check

```c
// ❌ DANGEROUS: Dereference không kiểm tra
struct config *cfg = bpf_map_lookup_elem(&config_map, &key);
return cfg->value;  // Có thể crash nếu key không tồn tại

// ✅ SAFE: Luôn check NULL
struct config *cfg = bpf_map_lookup_elem(&config_map, &key);
if (!cfg) return -1;
return cfg->value;
```

> **Lưu ý**: Verifier **sẽ reject** code không check NULL, nhưng trong một số edge cases, verifier có thể miss → kernel crash.

#### 5.1.3. Ring Buffer Overflows

```c
// ❌ PROBLEM: Submit event không kiểm tra
bpf_ringbuf_submit(event, 0);  // Có thể drop events nếu buffer full

// ✅ BETTER: Handle backpressure
long err = bpf_ringbuf_reserve(...);
if (err) {
    // Increment dropped counter
    __atomic_add_fetch(&dropped_count, 1, __ATOMIC_RELAXED);
}
```

### 5.2. Performance Anti-patterns

| Anti-pattern | Impact | Solution |
|--------------|--------|----------|
| Frequent map lookups | Cache thrashing | Batch operations, cache locally |
| String operations | Verifier complexity | Use integer IDs, pre-hash strings |
| Unbounded loops | Verifier rejection | Unroll loops, use bounded iterators |
| Heavy computation in XDP | Packet loss | Move to TC or user-space |
| Too many programs | Overhead | Consolidate, use tail calls |

### 5.3. Security Considerations

1. **Privileged Operation**: Loading eBPF requires `CAP_BPF` hoặc root
   - **Risk**: Compromised eBPF loader = kernel compromise
   - **Mitigation**: Audit tất cả eBPF programs, dùng signed programs

2. **Spectre/Meltdown**: eBPF programs có thể exploit speculative execution
   - **Mitigation**: Kernel mitigations, bounded speculation

3. **Verifier Bypass**: Bugs trong verifier cho phép unsafe operations
   - **History**: Multiple CVEs (CVE-2020-8835, CVE-2021-3490)
   - **Mitigation**: Keep kernel updated

## 6. Khuyến Nghị Thực Chiến trong Production

### 6.1. Khi nào NÊN dùng eBPF

✅ **Nên dùng**:
- High-throughput packet processing (XDP)
- Cluster-wide observability (Cilium Hubble)
- Runtime security monitoring (Falco, Tetragon)
- Continuous profiling (Parca, Pyroscope)
- Custom load balancing với connection affinity
- Kernel-level network policies

### 6.2. Khi nào KHÔNG NÊN dùng eBPF

❌ **Không nên dùng**:
- Simple tasks có thể làm bằng userspace (overkill)
- Applications cần complex logic không verifier-friendly
- Environments với kernel versions cũ (< 4.18)
- Teams không có eBPF expertise (learning curve cao)
- Use cases yêu cầu rapid iteration (deployment cycle chậm hơn)

### 6.3. Production Checklist

- [ ] **Kernel version**: 5.10+ recommended (5.4+ minimum for most features)
- [ ] **BTF enabled**: CONFIG_DEBUG_INFO_BTF=y (cho CO-RE)
- [ ] **Resource limits**: Map size limits, instruction limits
- [ ] **Monitoring**: Track eBPF program load failures, map full conditions
- [ ] **Testing**: Test trên exact kernel version của production
- [ ] **Rollback plan**: Có thể unload eBPF programs nếu có vấn đề
- [ ] **Security audit**: Review tất cả eBPF programs trước deployment

### 6.4. Debugging eBPF

```bash
# Check loaded programs
sudo bpftool prog list

# Dump bytecode
sudo bpftool prog dump xlated id <id>

# Dump JIT-ed assembly
sudo bpftool prog dump jited id <id>

# Inspect maps
sudo bpftool map list
sudo bpftool map dump id <id>

# Trace verifier logs (khi load fail)
sudo bpftool prog load prog.o /sys/fs/bpf/prog 2>&1

# Kernel trace events
cat /sys/kernel/debug/tracing/trace_pipe
```

### 6.5. Observability của... Observability Tools

Ironically, eBPF tools cũng cần được monitor:

```c
// Example: Self-monitoring eBPF program
struct {
    __uint(type, BPF_MAP_TYPE_PERCPU_ARRAY);
    __uint(max_entries, 3);
    __type(key, __u32);
    __type(value, __u64);
} stats SEC(".maps");

// Stats indices
#define STAT_INVOCATIONS    0
#define STAT_DROPPED        1
#define STAT_ERRORS         2

static __always_inline void record_stat(__u32 stat_idx)
{
    __u64 *count = bpf_map_lookup_elem(&stats, &stat_idx);
    if (count) __sync_fetch_and_add(count, 1);
}
```

## 7. Kết Luận

### Bản Chất Cốt Lõi
eBPF là **kernel-level virtual machine** cho phép chạy sandboxed programs trong kernel với:
- **Safety**: Verifier đảm bảo programs không crash kernel
- **Performance**: JIT compilation sang native code
- **Flexibility**: Attach vào hàng trăm hook points

### Trade-off Quan Trọng Nhất
| Trade-off | Decision Factor |
|-----------|-----------------|
| Safety vs Flexibility | Verifier limits những gì có thể làm |
| Performance vs Complexity | Native speed nhưng learning curve cao |
| Power vs Portability | Kernel-dependent nhưng CO-RE giảm bớt |

### Rủi Ro Lớn Nhất trong Production
1. **Kernel dependency**: Kernel upgrade có thể break eBPF programs
2. **Verifier complexity**: Logic đúng có thể bị reject
3. **Security surface**: Root access để load eBPF = high privilege
4. **Debugging difficulty**: Kernel-level debugging khó hơn userspace

### Tư Duy Kiến Trúc
eBPF không phải là silver bullet. Nó là **optimization cuối cùng** cho các vấn đề:
- Đã được giải quyết bằng userspace nhưng cần thêm performance
- Yêu cầu visibility vào kernel behavior
- Cần enforcement ở lowest possible level

> **Quy tắc vàng**: Start với userspace solution. Chuyển sang eBPF khi profiling chứng minh bottleneck ở kernel-user boundary.

---

## Tài Liệu Tham Khảo

1. **Kernel Documentation**: https://www.kernel.org/doc/html/latest/bpf/
2. **Cilium eBPF Guide**: https://docs.cilium.io/en/stable/bpf/
3. **BPF Performance Tools** (book) - Brendan Gregg
4. **eBPF Labs**: https://ebpf.io/what-is-ebpf
5. **Cilium/ebpf Go Library**: https://github.com/cilium/ebpf
6. **libbpf**: https://github.com/libbpf/libbpf
7. **BCC Tools**: https://github.com/iovisor/bcc

---

*Document Version: 1.0*  
*Research Date: 2026-03-28*  
*Author: Senior Backend Architect*
