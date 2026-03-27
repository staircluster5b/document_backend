# GPU Computing trong Backend Systems

## 1. Mục tiêu của Task

Hiểu sâu cơ chế tận dụng GPU (Graphics Processing Unit) để tăng tốc xử lý trong hệ thống backend, đặc biệt cho AI inference và các tác vụ compute-intensive. Nắm vững kiến trúc CUDA, quản lý bộ nhớ GPU, tích hợp vào microservices, và các trade-off quan trọng.

> **Tại sao cần nghiên cứu:** Traditional CPU-centric backend đang bị giới hạn bởi Moore's Law. AI workloads (LLM inference, recommendation systems, computer vision) đòi hỏi throughput tính toán vượt xa khả năng của CPU. GPU computing không còn là "nice-to-have" mà là requirement cho competitive AI-native applications.

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 Kiến trúc GPU vs CPU: Bản Chất Khác Biệt

| Aspect | CPU | GPU |
|--------|-----|-----|
| **Design Philosophy** | Latency-optimized | Throughput-optimized |
| **Core Count** | 8-64 cores | 5,000-20,000+ CUDA cores |
| **Clock Speed** | 3-5 GHz | 1-2 GHz |
| **Control Logic** | Complex (branch prediction, speculative execution) | Minimal (in-order execution) |
| **Memory Hierarchy** | Large L1/L2/L3 caches | Small L1, shared L2, large HBM |
| **Best For** | Sequential tasks, complex branching | Parallel data processing, matrix ops |

> **Bản chất cốt lõi:** GPU được thiết kế theo kiến trúc **SIMT (Single Instruction, Multiple Threads)**. Hàng nghìn threads thực thi cùng instruction trên dữ liệu khác nhau. Điều này phù hợp hoàn hảo với matrix multiplication - xương sống của neural network inference.

### 2.2 CUDA Architecture: Từ Host đến Device

```
┌─────────────────────────────────────────────────────────────┐
│                         HOST (CPU)                          │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────────┐  │
│  │ Application │───▶│ CUDA Driver │───▶│ CUDA Runtime API│  │
│  └─────────────┘    └─────────────┘    └─────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                             │
                    PCIe Bus (16-32 GB/s)
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                        DEVICE (GPU)                         │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              GLOBAL MEMORY (HBM2e/HBM3)             │   │
│  │              (40-80 GB, 1.5-3 TB/s)                 │   │
│  └─────────────────────────────────────────────────────┘   │
│                             │                               │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  SM (Streaming Multiprocessor) Array                │   │
│  │  ┌───────────────────────────────────────────────┐ │   │
│  │  │  Warp Scheduler  │  Registers  │  Shared Mem  │ │   │
│  │  └───────────────────────────────────────────────┘ │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐  │   │
│  │  │ CUDA    │ │ CUDA    │ │ CUDA    │ │ CUDA    │  │   │
│  │  │ Core 0  │ │ Core 1  │ │ Core 2  │ │ Core N  │  │   │
│  │  └─────────┘ └─────────┘ └─────────┘ └─────────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

**Execution Flow:**

1. **Host khởi tạo** - CPU cấp phát device memory với `cudaMalloc()`
2. **Data Transfer** - Copy input data từ Host RAM → GPU HBM qua PCIe
3. **Kernel Launch** - CPU gọi kernel function chạy trên GPU
4. **Grid/Block/Thread Hierarchy**:
   - **Grid**: Toàn bộ kernel execution
   - **Block**: Group of threads (max 1,024 threads/block)
   - **Thread**: Đơn vị thực thi nhỏ nhất
5. **Warp Execution** - 32 threads được nhóm thành 1 warp, scheduler phát lệnh cho toàn bộ warp
6. **Result Transfer** - Copy output từ GPU → Host

> **Lưu ý quan trọng:** PCIe bottleneck (16-32 GB/s) so với GPU memory bandwidth (1.5-3 TB/s) tạo ra **memory wall**. Tối ưu data locality là then chốt.

### 2.3 Memory Hierarchy trong GPU

```
Latency (cycles)    Memory Type              Size          Access Pattern
───────────────────────────────────────────────────────────────────────────
    ~1              Registers         Per-thread (256KB/SM)  Fastest, private
    ~20             Shared Memory     48-164 KB/block        L1-like, block-scoped
    ~200            L2 Cache          4-6 MB                 Unified, automatic
    ~400            Global Memory     40-80 GB HBM           Manual management
    ~1000+          Pageable Host     System RAM             Avoid in hot path
```

**Memory Access Patterns:**

- **Coalesced Access**: Các threads trong cùng warp truy cập contiguous memory → 1 transaction
- **Strided Access**: Truy cập cách đều nhưng không contiguous → nhiều transactions
- **Random Access**: Không có pattern → worst case, memory divergence

> **Rule of thumb:** Shared memory nhanh gấp 100x global memory. Coalesced access cải thiện bandwidth lên đến 10x.

### 2.4 cuDNN: CUDA Deep Neural Network Library

cuDNN là low-level primitive library tối ưu cho deep learning:

**Core Primitives:**
- **Convolution Forward/Backward**: Tích chập với nhiều algorithm (GEMM, FFT, Winograd)
- **Activation Functions**: ReLU, Sigmoid, Tanh (fused với convolution để giảm memory)
- **Normalization**: Batch Norm, Layer Norm, Group Norm
- **RNN/LSTM/GRU**: Recurrent layer primitives
- **Transformer Optimizations**: Multi-head attention, fused MHA

**Convolution Algorithms trong cuDNN:**

| Algorithm | Memory Usage | Speed | Use Case |
|-----------|--------------|-------|----------|
| `CUDNN_CONVOLUTION_FWD_ALGO_IMPLICIT_GEMM` | Thấp | Trung bình | Small batch, edge cases |
| `CUDNN_CONVOLUTION_FWD_ALGO_IMPLICIT_PRECOMP_GEMM` | Trung bình | Cao | General purpose |
| `CUDNN_CONVOLUTION_FWD_ALGO_GEMM` | Cao | Rất cao | Large batch, consistent shapes |
| `CUDNN_CONVOLUTION_FWD_ALGO_WINOGRAD` | Trung bình | Cao | Small kernels (3x3) |
| `CUDNN_CONVOLUTION_FWD_ALGO_FFT` | Rất cao | Cao với large kernels | Large spatial dimensions |

cuDNN tự động chọn algorithm tốt nhất qua **heuristic API** hoặc **auto-tuning**.

---

## 3. Kiến trúc Tích hợp GPU trong Microservices

### 3.1 Deployment Patterns

```
Pattern 1: Dedicated GPU Service (Sidecar Pattern)
┌─────────────────────────────────────────────┐
│           Kubernetes Pod                    │
│  ┌───────────────┐  ┌───────────────────┐  │
│  │  Inference    │  │  GPU Inference    │  │
│  │  Controller   │──│  Service (gRPC)   │  │
│  │  (CPU)        │  │  (NVIDIA GPU)     │  │
│  └───────────────┘  └───────────────────┘  │
│                         │                   │
│                    ┌────┴────┐              │
│                    │ GPU Mem │              │
│                    │ (Shared)│              │
│                    └─────────┘              │
└─────────────────────────────────────────────┘

Pattern 2: GPU-Only Microservice
┌─────────────────────────────────────────────┐
│     GPU Pool (Multiple Pods)                │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐       │
│  │Pod GPU-0│ │Pod GPU-1│ │Pod GPU-2│ ...   │
│  │A100/40GB│ │A100/40GB│ │A100/40GB│       │
│  └────┬────┘ └────┬────┘ └────┬────┘       │
│       └────────────┼───────────┘            │
│                    ▼                        │
│            Load Balancer                    │
│         (Model-specific routing)            │
└─────────────────────────────────────────────┘

Pattern 3: Multi-Model GPU Sharing
┌─────────────────────────────────────────────┐
│        Single GPU Pod (MPS/MIG)             │
│  ┌───────────────────────────────────────┐  │
│  │         NVIDIA A100 80GB              │  │
│  │  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐     │  │
│  │  │MIG-0│ │MIG-1│ │MIG-2│ │MIG-3│     │  │
│  │  │10GB │ │10GB │ │10GB │ │10GB │     │  │
│  │  │     │ │     │ │     │ │     │     │  │
│  │  │BERT │ │ResNet│ │GPT │ │ CLIP│     │  │
│  │  └─────┘ └─────┘ └─────┘ └─────┘     │  │
│  └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

### 3.2 GPU Sharing Technologies

**NVIDIA MPS (Multi-Process Service):**
- Cho phép multiple processes chia sẻ cùng GPU context
- Giảm memory overhead của context switching
- Phù hợp khi multiple models cùng nằm trên 1 GPU
- **Trade-off:** Không có hardware isolation, một process crash có thể ảnh hưởng others

**NVIDIA MIG (Multi-Instance GPU):**
- Chia physical GPU thành multiple isolated instances
- Mỗi instance có dedicated memory và compute resources
- Hardware-level isolation (A100+, H100)
- **Trade-off:** Instance size cố định, không thể resize dynamically

| Feature | MPS | MIG |
|---------|-----|-----|
| Isolation Level | Process-level | Hardware-level |
| Overhead | Thấp | Cao (partitioning) |
| Flexibility | Cao (dynamic) | Thấp (fixed partitions) |
| Error Containment | Không | Có |
| GPU Support | All CUDA GPUs | A100, H100 |

### 3.3 Inference Serving Architecture

**Batching Strategy:**

```
Static Batching vs Dynamic Batching vs Continuous Batching

Static:     [req1]─wait─[req2]─wait─[req3]─batch─▶GPU─wait─results
Dynamic:    [req1]▶buffer──[req2]▶buffer──timeout─batch─▶GPU
Continuous: [req1]▶GPU──[req2]▶GPU──[req3]▶GPU (iteration-level scheduling)
            PagedAttention: KV cache management cho LLMs
```

**vLLM - State-of-the-art LLM Inference:**

```
Traditional:          vLLM with PagedAttention:
┌──────────┐         ┌─────────────────────────┐
│ KV Cache │         │  Logical KV Blocks      │
│ Contiguous│        │  ┌─┬─┬─┐┌─┬─┬─┐┌─┬─┬─┐ │
│ Memory   │         │  │1│2│3││4│5│6││7│8│ │
│          │         │  └─┴─┴─┘└─┴─┴─┘└─┴─┴─┘ │
│ Physical │         │         ↓ Mapping       │
│ Layout   │         │  Physical GPU Memory    │
│          │         │  ┌─┬─┬─┬─┬─┬─┬─┬─┐     │
│ Internal │         │  │3│7│1│8│4│2│5│6│     │
│ Fragment-│         │  └─┴─┴─┴─┴─┴─┴─┴─┘     │
│ ation    │         │  (Non-contiguous OK)    │
└──────────┘         └─────────────────────────┘
```

vLLM giải quyết **memory fragmentation** trong LLM inference bằng cách áp dụng virtual memory concept (paging) cho KV cache.

---

## 4. So sánh Các Framework và Triển khai

### 4.1 GPU Serving Frameworks

| Framework | Ngôn ngữ | Model Support | Batching | Unique Features |
|-----------|----------|---------------|----------|-----------------|
| **NVIDIA Triton** | C++/Python | Universal | Dynamic, Ensemble | Model ensemble, multiple backends (ONNX, TensorRT, PyTorch) |
| **vLLM** | Python | LLM-focused | Continuous (PagedAttention) | High throughput LLM, OpenAI-compatible API |
| **TensorRT-LLM** | C++/Python | LLM | In-flight | Optimized kernels, quantization |
| **TorchServe** | Python | PyTorch | Dynamic | Native PyTorch, easy deployment |
| **Ray Serve** | Python | Universal | Dynamic | Multi-model composition, autoscaling |
| **BentoML** | Python | Universal | Dynamic | Model versioning, edge deployment |

### 4.2 Java Integration Options

**Option 1: JNI/JNA với CUDA Native**
```
Java App → JNI → CUDA Runtime → GPU
```
- **Ưu điểm:** Full control, lowest latency
- **Nhược điểm:** Complex, error-prone, maintenance burden

**Option 2: gRPC/HTTP Service (Recommended)**
```
Spring Boot ─HTTP/gRPC─▶ Python GPU Service ─CUDA─▶ GPU
```
- **Ưu điểm:** Decoupled, language-agnostic, independent scaling
- **Nhược điểm:** Network overhead (mitigate với Unix socket/localhost)

**Option 3: Java bindings (Deep Java Library - DJL)**
```
Java App ─DJL─▶ Engine (PyTorch/TensorFlow) ─CUDA─▶ GPU
```
- **Ưu điểm:** Native Java API, no Python required
- **Nhược điểm:** Limited model support, JNI overhead

> **Khuyến nghị:** Pattern 2 (microservice) cho production. Java excels at orchestration; Python dominates ML ecosystem. Chấp nhận network overhead để đạt flexibility và maintainability.

### 4.3 Matrix Multiplication Optimization

**GEMM (General Matrix Multiply) Optimization Techniques:**

```
Naive: O(n³) operations, poor memory access
    for i in M:
        for j in N:
            for k in K:
                C[i,j] += A[i,k] * B[k,j]

Optimized cuBLAS/cuDNN:
┌─────────────────────────────────────────┐
│  Tiling + Shared Memory Reuse          │
│  ┌───────┐    ┌───────┐    ┌───────┐  │
│  │Tile A │ ×  │Tile B │ =  │Tile C │  │
│  │(SMEM) │    │(SMEM) │    │(SMEM) │  │
│  └───────┘    └───────┘    └───────┘  │
│                                        │
│  + Register blocking                   │
│  + Warp-level primitives               │
│  + Tensor Cores (mixed precision)      │
└─────────────────────────────────────────┘
```

**Precision Trade-offs:**

| Precision | Memory | Speed | Accuracy | Use Case |
|-----------|--------|-------|----------|----------|
| FP32 | 4 bytes | 1x | Reference | Training, precision-critical |
| TF32 | 4 bytes | 8x | ~FP32 | Mixed precision training |
| FP16/BF16 | 2 bytes | 2x | Good | Inference, compatible models |
| INT8 | 1 byte | 4x | Degraded | Quantized inference |
| INT4 | 0.5 byte | 8x | Significant degradation | Extreme compression |

---

## 5. Rủi ro, Anti-patterns, và Lỗi Thường Gặp

### 5.1 Memory Management Anti-patterns

**❌ Anti-pattern 1: Frequent Host↔Device Transfers**
```
// BAD: Transfer cho mỗi request
for each request:
    cudaMemcpy(host→device)  // PCIe bottleneck
    kernel_launch
    cudaMemcpy(device→host)  // PCIe bottleneck
```
**✅ Solution:** Pinned memory + batching + zero-copy khi có thể

**❌ Anti-pattern 2: Memory Leak trong GPU**
```
// C++ CUDA: Quên cudaFree()
float* d_data;
cudaMalloc(&d_data, size);
// ... sử dụng ...
// Oops! Không gọi cudaFree(d_data)
```
GPU memory leak **không tự recover** khi process kết thúc - cần GPU reset.

**❌ Anti-pattern 3: Excessive Synchronization**
```cuda
kernel1<<<blocks, threads>>>();
cudaDeviceSynchronize();  // Không cần thiết ở đây
kernel2<<<blocks, threads>>>();  // Có thể overlap với kernel1
cudaDeviceSynchronize();
```
**✅ Solution:** Async execution, CUDA streams cho concurrent kernels

### 5.2 Performance Pitfalls

| Pitfall | Symptom | Root Cause | Fix |
|---------|---------|------------|-----|
| Low GPU Utilization | < 50% util | Small batch size, CPU bottleneck | Increase batch size, async pipeline |
| Memory Thrashing | OOM errors | Oversized batch, fragmentation | Gradient checkpointing, PagedAttention |
| Kernel Launch Overhead | High latency | Too many small kernels | Kernel fusion, larger kernels |
| PCIe Bottleneck | Copy dominates | Large transfers | Zero-copy, unified memory |
| Warp Divergence | Low occupancy | Branching trong kernel | Restructure algorithm |

### 5.3 Production Failure Modes

**1. GPU Timeout (Watchdog)**
- Windows: TDR (Timeout Detection and Recovery) sau 2s
- Linux: X11 watchdog (nếu có display)
- **Fix:** Chia kernel thành smaller chunks, headless GPU cho compute

**2. ECC Memory Errors**
- GPUs workstation/datacenter có ECC (A100, H100)
- Uncorrectable errors → process termination
- **Monitor:** `nvidia-smi -q | grep -i ecc`

**3. Thermal Throttling**
- GPU giảm clock khi nhiệt độ cao
- **Monitor:** Temperature thresholds, improve cooling

**4. CUDA Version Incompatibility**
- Driver-CUDA mismatch
- Runtime library version mismatch
- **Best Practice:** Containerization với base image chuẩn (NVIDIA CUDA images)

### 5.4 Container/Kubernetes Gotchas

```yaml
# CORRECT: GPU resource request
resources:
  limits:
    nvidia.com/gpu: 1  # Must be integer in Kubernetes < 1.27
  requests:
    nvidia.com/gpu: 1
```

> **Lưu ý:** Kubernetes Device Plugin không hỗ trợ fractional GPU allocation. Dùng MIG hoặc MPS cho sharing.

---

## 6. Khuyến nghị Thực chiến trong Production

### 6.1 Observability Stack

**Metrics cần monitor:**

```
GPU-Level (nvidia-smi/prometheus):
├── gpu_utilization_percent      # Compute utilization
├── gpu_memory_used_bytes        # VRAM consumption
├── gpu_temperature_celsius      # Thermal throttling indicator
├── gpu_power_draw_watts         # Power consumption
└── pcie_throughput_bytes        # Transfer bottleneck

Application-Level:
├── inference_latency_p99        # End-to-end latency
├── batch_size_histogram         # Batching efficiency
├── queue_wait_time_ms           # Scheduling delay
├── gpu_queue_depth              # Pending requests
└── model_load_time_seconds      # Cold start metric
```

**Tools:**
- **DCGM (Data Center GPU Manager):** NVIDIA's production monitoring
- **Prometheus + Grafana:** Standard metrics pipeline
- **Nsight Systems:** Kernel-level profiling
- **TensorBoard/Nsight Compute:** Detailed kernel analysis

### 6.2 Deployment Checklist

**Pre-Production:**
- [ ] Model đã được convert sang optimized format (TensorRT, ONNX)
- [ ] Quantization tested (FP16/INT8) nếu accuracy acceptable
- [ ] Batch size benchmarked cho latency/throughput trade-off
- [ ] Memory limits configured (CUDA_MPS_PIPE/DIRECTORY)
- [ ] Health checks implemented (readiness/liveness probes)
- [ ] Graceful shutdown handling (drain in-flight requests)

**Production:**
- [ ] Autoscaling policy (GPU-based HPA với custom metrics)
- [ ] Circuit breaker cho GPU service failures
- [ ] Fallback strategy (CPU inference khi GPU unavailable)
- [ ] Model versioning và rollback capability
- [ ] Cost monitoring (GPU hours, per-inference cost)

### 6.3 Cost Optimization

| Strategy | Savings | Implementation Complexity |
|----------|---------|---------------------------|
| Spot/Preemptible Instances | 60-90% | High (need checkpointing) |
| Multi-Model GPU Sharing | 30-50% | Medium (MPS/MIG) |
| Model Quantization (INT8) | 2-4x throughput | Low-Medium |
| Request Batching | 2-10x throughput | Low |
| Right-sizing GPU Type | 20-40% | Medium (benchmarking) |

### 6.4 Security Considerations

**GPU trong Multi-tenant Environment:**
- **Vulnerability:** GPU memory không bị clear giữa contexts → data leakage risk
- **Mitigation:** MIG cho hardware isolation, clear memory between tenants

**Model Security:**
- Encrypt model weights at rest
- Runtime decryption trong GPU memory
- Model watermarking để detect theft

---

## 7. Kết luận

### Bản Chất Cốt lõi

GPU computing trong backend là sự **trade-off giữa throughput và latency, complexity và performance**. Điểm mấu chốt:

1. **GPU không phải silver bullet** - Chỉ hiệu quả với parallelizable workloads (matrix ops, batch inference)

2. **Memory là bottleneck chính** - PCIe transfer, memory capacity, bandwidth đều quan trọng hơn raw compute

3. **Batching là king** - Throughput scales gần như linear với batch size (đến saturation point)

4. **Decoupling bằng microservices** - Java/Python service separation là pattern ổn định nhất cho production

5. **Observability không thể thiếu** - GPU failures opaque, cần metrics và profiling tools

### Khi nào nên dùng GPU

✅ **Nên dùng:**
- LLM inference (GPT, LLaMA, v.v.)
- Computer vision models (ResNet, YOLO)
- Recommendation systems (matrix factorization)
- Real-time feature extraction
- Batch inference với high throughput requirement

❌ **Không nên dùng:**
- Simple rule-based logic
- High-frequency low-latency (< 1ms) requirements
- Sequential tasks không parallelizable
- Workloads memory-bound trên CPU đã đủ nhanh

### Xu hướng Tương lai

- **Unified Memory (CUDA 12+):** Tự động migration data giữa CPU/GPU, giảm explicit management
- **Grace Hopper Superchip:** Tight CPU-GPU integration, NVLink-C2C thay thế PCIe
- **Cloud GPU Evolution:** Serverless GPU (Cloudflare, Modal) abstracting infrastructure
- **Alternative Accelerators:** AMD ROCm, Intel oneAPI, Apple Metal cạnh tranh NVIDIA dominance

---

## 8. Tài liệu Tham khảo

1. **NVIDIA CUDA C Programming Guide** - Official documentation
2. **CUDA Best Practices Guide** - Performance optimization patterns
3. **vLLM Paper (SOSP 2023)** - PagedAttention for efficient LLM serving
4. **NVIDIA Triton Inference Server Documentation**
5. **Deep Learning Inference on AWS** - Production deployment patterns
6. **MLPerf Inference Benchmarks** - Industry-standard performance metrics

---

> *"Premature optimization is the root of all evil. But premature GPU allocation is the root of all cloud bills."* - Adapted from Donald Knuth
