# LLM Integration Architecture: RAG Pipelines, Embedding Services & Vector Databases

## 1. Mục tiêu của task

Task này nghiên cứu kiến trúc tích hợp LLM vào hệ thống backend production, tập trung vào ba trụ cột:
- **RAG (Retrieval-Augmented Generation)**: Kỹ thuật kết hợp retrieval với generation để tăng độ chính xác và giảm hallucination
- **Embedding Services**: Cơ chế biến đổi dữ liệu thành vector không gian semantic
- **Vector Databases**: Hệ thống lưu trữ và tìm kiếm vector hiệu quả ở scale lớn

Mục tiêu cuối cùng: Hiểu bản chất tại sao RAG là pattern chủ đạo, trade-off giữa các chiến lược retrieval, và vận hành vector database trong production.

---

## 2. Bản chất và cơ chế hoạt động

### 2.1 RAG: Tại sao không fine-tune?

**Bản chất vấn đề:**
LLM là "frozen knowledge" tại thỉ điểm training. Hai hướng tiếp cận cập nhật kiến thức:

| Approach | Bản chất | Trade-off | Production Reality |
|----------|----------|-----------|-------------------|
| **Fine-tuning** | Điều chỉnh weights của model | Tốn compute, mất kiến thức cũ (catastrophic forgetting), không real-time | Phù hợp behavior/style, không phù hợp factual updates |
| **RAG** | Retrieve context → inject vào prompt | Latency tăng, context limit, retrieval quality dependency | Real-time knowledge, có thể verify sources, dễ rollback |

> **Insight cốt lõi:** RAG tách biệt "knowledge storage" (vector DB) khỏi "reasoning engine" (LLM). Điều này cho phép:
> - Knowledge updates không cần retrain model
> - Source attribution (trích dẫn nguồn)
> - Versioning của knowledge base

### 2.2 Cơ chế RAG Pipeline

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            RAG PIPELINE FLOW                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   ┌──────────────┐     ┌──────────────┐     ┌──────────────────────────┐   │
│   │   Ingestion  │────▶│  Embedding   │────▶│   Vector Database        │   │
│   │   Pipeline   │     │   Service    │     │   (Index + Metadata)     │   │
│   └──────────────┘     └──────────────┘     └──────────────────────────┘   │
│          │                                                            │    │
│          │    Documents ──▶ Chunks ──▶ Embeddings ──▶ HNSW/IVF Index       │
│          │                    │              │              │              │
│          │              Chunking Strategy   Model         ANN Search       │
│          │              (Fixed/Recursive/   (dim: 768-     (approximate    │
│          │               Semantic)          1536)         nearest neighbor)│
│                                                                             │
│   ╔═════════════════════════════════════════════════════════════════════╗  │
│   ║                         QUERY FLOW                                  ║  │
│   ╠═════════════════════════════════════════════════════════════════════╣  │
│   ║                                                                     ║  │
│   ║   User Query ──▶ Query Embedding ──▶ Vector Search ──▶ Top-K Docs ║  │
│   ║        │                                                         │  ║  │
│   ║        │                                                    Reranking ║  │
│   ║        │                                                    (Optional) ║  │
│   ║        ▼                                                         ▼  ║  │
│   ║   ┌─────────────────────────────────────────────────────────────┐   ║  │
│   ║   │  Prompt Construction                                        │   ║  │
│   ║   │  "Dựa trên tài liệu sau: {retrieved_context}                │   ║  │
│   ║   │   Trả lởi câu hỏi: {user_query}"                           │   ║  │
│   ║   └─────────────────────────────────────────────────────────────┘   ║  │
│   ║                              │                                      ║  │
│   ║                              ▼                                      ║  │
│   ║                      LLM Generation                                 ║  │
│   ║                              │                                      ║  │
│   ║                              ▼                                      ║  │
│   ║   ┌─────────────────────────────────────────────────────────────┐   ║  │
│   ║   │  Response + Source Attribution                              │   ║  │
│   ║   └─────────────────────────────────────────────────────────────┘   ║  │
│   ╚═════════════════════════════════════════════════════════════════════╝  │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Các thành phần then chốt:**

1. **Chunking Strategy**: Không chỉ là "cắt nhỏ văn bản"
   - **Fixed-size**: Đơn giản nhưng có thể cắt ngang câu/ý
   - **Recursive**: Ưu tiên cắt ở boundary (paragraph, sentence)
   - **Semantic**: Dùng model nhỏ để detect topic shift
   - **Agentic**: LLM quyết định chunk boundary (tốn compute, chất lượng cao)

2. **Embedding Model**: Biến semantics thành vector space
   - Models phổ biến: OpenAI text-embedding-3-large (3072d), sentence-transformers/all-MiniLM-L6-v2 (384d), E5, BGE
   - **Trade-off quan trọng**: Dimensionality vs. Retrieval quality vs. Storage cost
   - 1536-dim vector với 1M documents = ~6GB storage (float32)

3. **Retrieval Strategy**:
   - **Dense retrieval**: Vector similarity (cosine/dot product)
   - **Sparse retrieval**: BM25, TF-IDF (tốt cho exact match)
   - **Hybrid**: Kết hợp cả hai (thường dùng Reciprocal Rank Fusion)

### 2.3 Vector Database Internals

**Bản chất tìm kiếm vector:**
Exact K-nearest neighbor (KNN) với 1M+ vectors là O(n) - quá chậm. Solution: **Approximate Nearest Neighbor (ANN)**.

| Algorithm | Bản chất | Complexity | Trade-off | Best For |
|-----------|----------|------------|-----------|----------|
| **HNSW** | Graph-based: nodes connected to similar neighbors | Build: O(n log n)<br>Search: O(log n) | Memory intensive, khó delete/update | High recall, dynamic datasets |
| **IVF** | Inverted File: cluster vectors, search nearest centroids | Build: O(n)<br>Search: O(√n) | Lower memory, cần retrain khi thêm data | Static/append-only datasets |
| **PQ** | Product Quantization: nén vector thành codebook | Compression: 10-100x | Lossy, recall giảm | Memory-constrained |
| **DiskANN** | Graph + disk-based | VCL (Vector Cache Layer) | Latency cao hơn nhưng scale to 100B+ | Extreme scale |

> **Production insight:** HNSW là lựa chọn mặc định cho hầu hết use cases nhờ balance giữa speed/recall. Nhưng memory cost đáng kể - cần tính toán: `memory = num_vectors × dim × 4 bytes × 1.5 (overhead)`

---

## 3. Kiến trúc & luồng xử lý

### 3.1 Multi-Tier Retrieval Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     MULTI-TIER RAG ARCHITECTURE                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                        QUERY LAYER                               │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │   │
│  │  │   Query     │  │   Query     │  │   Intent Classification │  │   │
│  │  │ Rewriting   │  │ Expansion   │  │   (Router: RAG/NLU/...) │  │   │
│  │  │ (HyDE)      │  │ (Synonyms)  │  │                         │  │   │
│  │  └─────────────┘  └─────────────┘  └─────────────────────────┘  │   │
│  └──────────────────────────┬──────────────────────────────────────┘   │
│                             │                                           │
│                             ▼                                           │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                     RETRIEVAL LAYER (Multi-Stage)                │   │
│  │                                                                  │   │
│  │   Stage 1: Candidate Generation                                  │   │
│  │   ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐ │   │
│  │   │ Keyword Index   │  │ Vector Index    │  │ Knowledge Graph │ │   │
│  │   │ (BM25/TF-IDF)   │  │ (HNSW/IVF)      │  │ (Entities)      │ │   │
│  │   │ Top 100         │  │ Top 100         │  │ Top 50          │ │   │
│  │   └─────────────────┘  └─────────────────┘  └─────────────────┘ │   │
│  │            │                    │                    │          │   │
│  │            └────────────────────┼────────────────────┘          │   │
│  │                                 ▼                               │   │
│  │                        Fusion & Deduplication                   │   │
│  │                        (RRF: Reciprocal Rank Fusion)            │   │
│  │                                 │                               │   │
│  │                                 ▼                               │   │
│  │   Stage 2: Reranking                                            │   │
│  │   ┌─────────────────────────────────────────────────────┐      │   │
│  │   │ Cross-Encoder Reranker (e.g., BGE-Reranker)         │      │   │
│  │   │ Dùng full query + document để tính relevance chính xác│     │   │
│  │   │ Top 10-20 được chọn                                 │      │   │
│  │   └─────────────────────────────────────────────────────┘      │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                             │                                           │
│                             ▼                                           │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                     GENERATION LAYER                             │   │
│  │                                                                  │   │
│  │   Context Assembly:                                              │   │
│  │   - Reorder by relevance (giữa context quan trọng nhất ở đầu)    │   │
│  │   - Truncate to fit context window                               │   │
│  │   - Inject metadata (source, timestamp, confidence)              │   │
│  │                                                                  │   │
│  │   Prompt Engineering:                                            │   │
│  │   - System prompt với role definition                            │   │
│  │   - Context boundary markers                                     │   │
│  │   - Instruction về style/format của response                     │   │
│  │                                                                  │   │
│  │   Generation:                                                    │   │
│  │   - Temperature/C điều chỉnh theo use case                        │   │
│  │   - Streaming response cho UX                                    │   │
│  │   - Citations (mapping response về source chunks)                │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Embedding Service Architecture

**Challenge:** Embedding model inference là bottleneck khi ingestion scale.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    EMBEDDING SERVICE ARCHITECTURE                       │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   ┌─────────────────────────────────────────────────────────────────┐  │
│   │                    Load Balancer / API Gateway                   │  │
│   └─────────────────────────────────────────────────────────────────┘  │
│                                    │                                    │
│                    ┌───────────────┼───────────────┐                    │
│                    ▼               ▼               ▼                    │
│   ┌─────────────────────┐ ┌──────────────┐ ┌─────────────────────┐     │
│   │ Embedding Worker 1  │ │ Worker 2     │ │ Worker N            │     │
│   │ ┌─────────────────┐ │ │ ┌──────────┐ │ │ ┌─────────────────┐ │     │
│   │ │ Batching Layer  │ │ │ │ Batching │ │ │ │ Batching Layer  │ │     │
│   │ │ (dynamic batch) │ │ │ │ Layer    │ │ │ │ (dynamic batch) │ │     │
│   │ │ Batch size: 32  │ │ │ │ size: 32 │ │ │ │ Batch size: 32  │ │     │
│   │ └─────────────────┘ │ │ └──────────┘ │ │ └─────────────────┘ │     │
│   │         │           │ │      │       │ │         │           │     │
│   │         ▼           │ │      ▼       │ │         ▼           │     │
│   │ ┌─────────────────┐ │ │ ┌──────────┐ │ │ ┌─────────────────┐ │     │
│   │ │ ONNX/TensorRT   │ │ │ │ ONNX/    │ │ │ │ ONNX/TensorRT   │ │     │
│   │ │ Optimized Model │ │ │ │ TensorRT │ │ │ │ Optimized Model │ │     │
│   │ │ GPU/CPU inference│ │ │ │          │ │ │ │ GPU/CPU inference│ │     │
│   │ └─────────────────┘ │ │ └──────────┘ │ │ └─────────────────┘ │     │
│   └─────────────────────┘ └──────────────┘ └─────────────────────┘     │
│                                                                         │
│   Optimizations:                                                        │
│   - Model quantization (FP16/INT8) giảm memory & tăng throughput        │
│   - ONNX Runtime / TensorRT cho inference optimization                  │
│   - Dynamic batching: accumulate requests đến đủ batch size hoặc timeout│
│   - Caching: Redis cho duplicate content (content hash → embedding)     │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 4. So sánh các lựa chọn

### 4.1 Vector Database Comparison

| Database | Architecture | Best For | Trade-offs | Pricing Model |
|----------|--------------|----------|------------|---------------|
| **Pinecone** | Managed, metadata filtering | Đơn giản, không muốn ops | Vendor lock-in, cost cao ở scale | Per-pod (bắt đầu ~$70/tháng) |
| **Weaviate** | GraphQL interface, modular AI | Multi-modal, hybrid search | HNSW memory hungry, learning curve | Open source + managed cloud |
| **Milvus/Zilliz** | Cloud-native, distributed | Petabyte scale, high availability | Complex local setup, overhead | Open source + managed tiers |
| **pgvector** | Postgres extension | Đã có Postgres, simple use cases | Limited scalability, single-node | Free (tự host) |
| **Chroma** | Developer-friendly, ephemeral | Prototyping, local dev | Không production-ready cho scale | Open source |
| **Qdrant** | Rust-based, fast, filterable | Self-hosted, high performance | Cần manage infrastructure | Open source + cloud |
| **Redis** | In-memory, với RediSearch | Real-time, low-latency, <1M vectors | Memory constraints, limited ANN | Redis Enterprise |

### 4.2 Embedding Models

| Model | Dimensions | Strength | Weakness | Use Case |
|-------|-----------|----------|----------|----------|
| **text-embedding-3-large** | 3072 | Best quality, MTEB leaderboard | Cost, latency | High-value enterprise search |
| **text-embedding-3-small** | 1536 | Good balance cost/quality | Không tốt như large | General purpose production |
| **ada-002** | 1536 | Legacy, widely supported | Worse than v3 | Migration/legacy systems |
| **all-MiniLM-L6-v2** | 384 | Fast, small, free | Quality thấp hơn SOTA | Latency-critical, edge devices |
| **BGE-large-en** | 1024 | Open source leader | Cần self-host | Cost-sensitive, privacy |
| **E5-large-v2** | 1024 | Strong on long documents | Chậm hơn MiniLM | Legal/medical docs |
| **Cohere embed-v3** | 1024 | Multilingual tốt | Vendor dependency | Non-English content |

**Trade-off analysis:**
- **Latency**: Small dims (384) → 2-3x nhanh hơn large dims (3072)
- **Storage**: 3072-dim = 8x storage so với 384-dim
- **Quality**: Trên MTEB, text-embedding-3-large ~64.6 vs all-MiniLM-L6-v2 ~56 (càng cao càng tốt)

### 4.3 RAG vs Fine-tuning vs Prompt Engineering

```
┌─────────────────────────────────────────────────────────────────────────┐
│              WHEN TO USE WHAT: DECISION FRAMEWORK                       │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   Need to add knowledge?                                                │
│        │                                                                │
│        ├── Domain-specific behavior/style ───────▶ FINE-TUNING         │
│        │                    (tone, format, task-specific reasoning)    │
│        │                                                                │
│        └── Factual/structured data ──────────────▶ RAG                  │
│                             (docs, policies, real-time data)           │
│                                                                         │
│   RAG complexity decision tree:                                         │
│        │                                                                │
│        ├── <1000 documents, simple queries ──────▶ Basic RAG            │
│        │                    (single retrieval, direct answer)          │
│        │                                                                │
│        ├── Diverse document types ───────────────▶ Multi-Index RAG     │
│        │                    (separate indices, router agent)           │
│        │                                                                │
│        ├── Complex multi-hop questions ──────────▶ Agentic RAG          │
│        │                    (iterative retrieval, reasoning loops)     │
│        │                                                                │
│        └── Need 100% accuracy ───────────────────▶ RAG + Guardrails     │
│                             (fact-checking, confidence thresholds)     │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Rủi ro, anti-patterns, lỗi thường gặp

### 5.1 Critical Failure Modes

| Anti-Pattern | Hậu quả | Detection | Mitigation |
|--------------|---------|-----------|------------|
| **Chunk size quá lớn** | Context pollution, LLM bị "lạc" giữa nhiều thông tin | Query-to-chunk relevance scores thấp | Optimal 200-500 tokens, overlap 10-20% |
| **No reranking** | Top-K retrieval có nhiều false positive | High retrieval but low answer accuracy | Cross-encoder reranker, top 10 → top 3 |
| **Ignoring metadata** | Trả về outdated/irrelevant documents | Timestamp/version checks | Metadata filtering trong retrieval |
| **Single embedding model** | Không capture all query intents | Query classification accuracy | Intent-based model routing |
| **Naive hybrid search** | Simple weighted sum không optimal | Retrieval metrics degrade | RRF hoặc learned fusion |
| **No query rewriting** | User query không match document language | Low recall despite relevant docs | Query expansion, HyDE |
| **Context stuffing** | Đổ quá nhiều context vào prompt | Token usage cao, latency tăng | Selective context, summarization |

### 5.2 Production Incidents

> **Case 1: Context Window Overflow**
> - Symptom: LLM responses bị cắt ngang hoặc ignore phần context sau
> - Root cause: 20 chunks × 1000 tokens = 20K tokens, vượt quá context limit
> - Fix: Dynamic truncation, priority scoring, recursive summarization

> **Case 2: Embedding Drift**
> - Symptom: Retrieval quality giảm dần theo thời gian
> - Root cause: Fine-tune embedding model mới không compatible với old vectors
> - Fix: Versioning strategy, re-indexing pipeline, backward compatibility tests

> **Case 3: Thundering Herd on Index Update**
> - Symptom: Vector DB crash khi bulk update
> - Root cause: HNSW graph rebuild block queries
> - Fix: Blue-green index deployment, incremental updates, read replicas

### 5.3 Security & Privacy Concerns

1. **Data Leakage**: Embeddings có thể reverse-engineer để extract original text
   - Mitigation: Differential privacy, tenant isolation, encryption at rest
   
2. **Prompt Injection**: Attacker inject malicious content vào knowledge base
   - Mitigation: Content validation, input sanitization, output filtering
   
3. **Privilege Escalation**: User access document không được phép qua clever queries
   - Mitigation: Metadata-based ACL, query-time filtering, audit logging

---

## 6. Khuyến nghị thực chiến trong production

### 6.1 Architecture Decisions

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    PRODUCTION RAG CHECKLIST                             │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   ✅ INGESTION PIPELINE                                                 │
│   [ ] Incremental updates (chỉ re-embed changed documents)              │
│   [ ] Dead letter queue cho failed embeddings                           │
│   [ ] Idempotency keys để tránh duplicate processing                    │
│   [ ] Versioning: document v1.2 → embedding v1.2                        │
│   [ ] CDC (Change Data Capture) từ source systems                       │
│                                                                         │
│   ✅ RETRIEVAL LAYER                                                    │
│   [ ] Multi-tier: Keyword + Vector + Knowledge Graph                    │
│   [ ] Query rewriting/caching                                           │
│   [ ] A/B testing framework cho retrieval strategies                    │
│   [ ] Fallback: nếu retrieval confidence thấp → direct LLM              │
│                                                                         │
│   ✅ OBSERVABILITY                                                      │
│   [ ] Retrieval metrics: MRR, NDCG@K, Recall@K                          │
│   [ ] End-to-end metrics: Answer relevance, Latency P99                 │
│   [ ] Tracing: query → retrieval → generation flow                      │
│   [ ] Feedback loop: thumbs up/down capture                             │
│                                                                         │
│   ✅ SCALABILITY                                                        │
│   [ ] Horizontal scaling: stateless embedding workers                   │
│   [ ] Vector DB sharding strategy (theo tenant/time)                    │
│   [ ] CDN cho document storage                                          │
│   [ ] Async processing cho non-critical updates                         │
│                                                                         │
│   ✅ COST OPTIMIZATION                                                  │
│   [ ] Caching: embedding cache, query cache, response cache             │
│   [ ] Tiered storage: hot (SSD) → warm (S3) → cold (glacier)            │
│   [ ] Model distillation: smaller model cho simple queries              │
│   [ ] Batch processing cho bulk ingestion                               │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 6.2 Performance Benchmarks & SLAs

| Metric | Target | Monitoring |
|--------|--------|------------|
| Embedding latency (p99) | <100ms | Worker queue depth, GPU utilization |
| Retrieval latency (p99) | <50ms | ANN index stats, cache hit rate |
| End-to-end latency (p99) | <2s | Full pipeline, streaming TTFB |
| Retrieval Recall@10 | >90% | Eval dataset, golden queries |
| Answer relevance | >4.0/5.0 | Human evaluation, LLM-as-judge |

### 6.3 Vector Database Sizing

```
# Memory calculation for HNSW
memory_bytes = num_vectors × dimension × 4 × 1.5

# Ví dụ: 10M vectors, 1536 dimensions
10_000_000 × 1536 × 4 × 1.5 = ~92 GB RAM

# Optimization strategies:
# 1. Quantization (PQ): Giảm 4x memory, trade-off ~5% recall
# 2. Dimension reduction (PCA): 1536 → 768, giảm 2x
# 3. Hybrid approach: IVF cho coarse, HNSW cho fine
```

---

## 7. Kết luận

**Bản chất của RAG architecture:**

1. **Decoupling là then chốt**: Tách knowledge storage khỏi reasoning cho phép independent scaling và updating.

2. **Retrieval quality > Generation quality**: Một LLM tốt với context kém tạo ra câu trả lời kém. "Garbage in, garbage out" applies mạnh mẽ ở đây.

3. **Trade-off chính**:
   - Latency vs. Accuracy (multi-stage retrieval)
   - Cost vs. Quality (embedding model selection)
   - Memory vs. Scale (HNSW vs. IVF)

4. **Production là về monitoring**: RAG systems có nhiều moving parts. Without proper observability (retrieval metrics, latency breakdowns, feedback loops), debugging là impossible.

5. **Tương lai**: 
   - **Late Interaction Models** (ColBERT) kết hợp efficiency của bi-encoder với accuracy của cross-encoder
   - **Learned Sparse Retrieval** (SPLADE) thay thế BM25 cho better lexical matching
   - **Multimodal RAG**: Unified retrieval cho text, image, audio, video

**Quyết định kiến trúc quan trọng nhất:** Không phải chọn vector DB hay embedding model, mà là thiết kế **chunking strategy** và **retrieval pipeline** phù hợp với data characteristics và user query patterns.

---

## 8. Tham khảo

- **MTEB Leaderboard**: https://huggingface.co/spaces/mteb/leaderboard
- **Pinecone RAG Guide**: https://www.pinecone.io/learn/retrieval-augmented-generation/
- **LangChain RAG Templates**: https://github.com/langchain-ai/langchain/tree/master/templates
- **HNSW Paper**: https://arxiv.org/abs/1603.09320
- **Google's RAG Best Practices**: https://cloud.google.com/architecture/rag-for-llms
- **Anthropic's Contextual Retrieval**: https://www.anthropic.com/news/contextual-retrieval

---

*Research completed: March 28, 2026*
