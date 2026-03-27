# Vector Databases & AI Integration: Deep Dive Research

> **Mục tiêu:** Hiểu sâu bản chất vector databases, các thuật toán similarity search, kiến trúc RAG, và pattern triển khai AI trong production.

---

## 1. Mục Tiêu Củа Task

Nghiên cứu này tập trung vào 4 trụ cột chính:

1. **Vector Storage & Indexing:** Cách lưu trữ và đánh index embedding vectors hiệu quả
2. **Similarity Search Algorithms:** HNSW, IVF và các thuật toán approximate nearest neighbor (ANN)
3. **Hybrid Search:** Kết hợp dense vectors với sparse vectors (BM25, TF-IDF)
4. **RAG Architecture:** Kiến trúc Retrieval-Augmented Generation trong production

---

## 2. Bản Chất Và Cơ Chế Hoạt Động

### 2.1 Embedding Vectors - Bản Chất Toán Học

**Embedding** là ánh xạ từ không gian dữ liệu gốc (text, image, audio) sang không gian vector liên tục n-chiều (thường 384-4096 dimensions).

```
Văn bản: "Hệ thống ngân hàng cần high availability"
↓ Embedding Model (e.g., text-embedding-3-large)
Vector: [0.023, -0.156, 0.892, ..., 0.341] ∈ ℝ³⁰⁷²
```

**Bản chất quan trọng:**
- **Semantic similarity** được biểu diễn bằng **khoảng cách không gian**
- Cosine similarity giữa 2 vector tỷ lệ thuận với ý nghĩa ngữ nghĩa tương đồng
- Không gian embedding là **latent space** - các chiều không có ý nghĩa riêng lẻ mà là tổ hợp phi tuyến của features

### 2.2 The Curse of Dimensionality Trong Vector Search

Khi số chiều tăng, các hiện tượng sau xảy ra:

| Chiều | Khoảng cách max trong unit cube | Sparsity |
|-------|----------------------------------|----------|
| 2D    | √2 ≈ 1.414                      | Thấp     |
| 100D  | √100 = 10                       | Cao      |
| 1000D | √1000 ≈ 31.6                    | Rất cao  |

> **Lưu ý quan trọng:** Trong không gian cao chiều, khoảng cách Euclidean trở nên "phẳng" - tất cả các điểm đều xa nhau gần như nhau. Đây là lý do tại sao **cosine similarity** thường được ưu tiên hơn Euclidean distance cho embedding.

### 2.3 Exact Nearest Neighbor vs Approximate Nearest Neighbor (ANN)

**Exact NN:** Tính khoảng cách đến tất cả vectors → O(n) per query
- Với 10M vectors, 3072 chiều: ~30 tỷ phép tính
- Không khả thi cho production scale

**ANN:** Chấp nhận đánh đổi accuracy để đạt sub-linear complexity
- **Recall@k:** Tỷ lệ kết quả thực sự gần nhất nằm trong k kết quả trả về
- **Query latency:** Thường < 100ms cho billion-scale datasets

---

## 3. Kiến Trúc Và Thuật Toán Similarity Search

### 3.1 HNSW (Hierarchical Navigable Small World)

**Bản chất:** Xây dựng đồ thị phân cấp với tính chất "small world" - bất kỳ 2 node nào cũng có thể đến nhau qua số bước ngắn.

```
Mermaid Diagram:

Layer 2 (Sparse):    [A] ←──────→ [C]
                     ↓            ↓
Layer 1 (Medium):   [A] ←→ [B] ←→ [C] ←→ [D]
                     ↓      ↓      ↓      ↓
Layer 0 (Dense):    [A]←→[A2]←→[B]←→[B2]←→[C]←→[C2]←→[D]
                      (All data points connected)
```

**Cơ chế tìm kiếm:**
1. Bắt đầu từ entry point tại layer cao nhất
2. Greedy search: luôn đi đến neighbor gần target nhất
3. Khi không còn tiến bộ, xuống layer thấp hơn
4. Lặp lại cho đến layer 0

**Độ phức tạp:**
- **Build:** O(n log n) - xây dựng đồ thị phân cấp
- **Search:** O(log n) - đi xuống theo chiều sâu layers
- **Memory:** O(n × M) - M là số connections per node

**Tham số tuning quan trọng:**
- `M`: Số connections tối đa per node (thường 8-64)
  - M cao → recall cao, build chậm, memory nhiều
  - M thấp → recall thấp, build nhanh, memory ít
- `efConstruction`: Beam width khi build (thường 64-200)
- `efSearch`: Beam width khi search (thường 64-400)

### 3.2 IVF (Inverted File Index)

**Bản chất:** Clustering-based index - chia không gian thành các Voronoi cells.

```
Mermaid Diagram:

Training Phase:
┌─────────────────────────────────────┐
│  K-means clustering on vectors      │
│  → K centroids (e.g., 1024-16384)   │
│  → Assign each vector to nearest    │
│    centroid (inverted list)         │
└─────────────────────────────────────┘
                 ↓
┌─────────────────────────────────────┐
│  Centroid A: [v1, v5, v12, v99]     │
│  Centroid B: [v2, v8, v15, v23]     │
│  Centroid C: [v3, v7, v11, ...]     │
└─────────────────────────────────────┘

Query Phase:
1. Find nprobe nearest centroids
2. Search only in those inverted lists
3. Rerank exact k-NN trong candidates
```

**Trade-offs:**
- `nprobe` (số centroid tìm kiếm): recall vs latency
  - nprobe = 1: chỉ tìm trong 1 cell → nhanh nhất, recall thấp nhất
  - nprobe = nlist: tìm tất cả → exact search (chậm)
- `nlist` (số clusters): nhiều clusters → ít vectors per cell → tìm nhanh hơn nhưng overhead cao

### 3.3 Product Quantization (PQ)

**Bản chất:** Nén vector bằng cách chia thành sub-vectors và quantize mỗi phần.

```
Vector gốc (128-dim): [0.23, -0.15, 0.89, ..., 0.12] (512 bytes float32)

Chia thành m=8 sub-vectors, mỗi sub-vector 16-dim:
  [0.23, ..., 0.11] | [-0.15, ..., 0.34] | ... | [0.45, ..., 0.12]
        ↓                    ↓                      ↓
   Codebook 1           Codebook 2             Codebook 8
   (256 centroids)      (256 centroids)        (256 centroids)
        ↓                    ↓                      ↓
      ID: 142               ID: 89                 ID: 201

Kết quả: 8 bytes (mỗi byte là index trong codebook)
Nén: 512 bytes → 8 bytes (64x compression)
```

**Đánh đổi:**
- Memory giảm 10-100x
- Distance computation nhanh hơn (lookup bảng thay vì tính toán)
- **Quantization error:** Giảm độ chính xác của distance

### 3.4 So Sánh Các Thuật Toán ANN

| Algorithm | Memory | Build Time | Query Latency | Recall | Best For |
|-----------|--------|------------|---------------|--------|----------|
| **Flat (Exact)** | 100% | O(1) | O(n) - chậm | 100% | Small datasets (<100K) |
| **IVF** | 5-20% | O(n × k × iter) | Fast | 80-95% | Medium datasets, RAM constrained |
| **HNSW** | 20-50% | O(n log n) | Very Fast | 90-99% | General purpose, high recall |
| **IVF-PQ** | 1-5% | Slow | Fast | 70-90% | Billion-scale, memory critical |
| **HNSW+PQ** | 5-10% | Slow | Very Fast | 80-95% | Large-scale, balanced |

---

## 4. Hybrid Search: Dense + Sparse

### 4.1 Bản Chất Vấn Đề

**Dense vectors (embedding):**
- ✅ Bắt được semantic similarity
- ✅ Generalization tốt
- ❌ Miss exact keyword matches
- ❌ Khó interpret

**Sparse vectors (BM25, TF-IDF):**
- ✅ Exact keyword matching
- ✅ Interpretable
- ✅ Tốt cho rare terms, proper nouns
- ❌ Không bắt được semantic

### 4.2 Kiến Trúc Hybrid Search

```
┌─────────────────────────────────────────────────────────────┐
│                        Query                                │
└─────────────────────┬───────────────────────────────────────┘
                      │
        ┌─────────────┴─────────────┐
        ▼                           ▼
┌───────────────┐           ┌────────────────┐
│ Dense Path    │           │ Sparse Path    │
│               │           │                │
│ Query →       │           │ Query →        │
│ Embedding     │           │ Tokenization   │
│ Model         │           │ ↓              │
│ ↓             │           │ BM25/TF-IDF    │
│ Vector DB     │           │ Inverted Index │
│ (ANN Search)  │           │ ↓              │
└───────┬───────┘           │ Top-K Docs     │
        │                   └───────┬────────┘
        │                           │
        └───────────┬───────────────┘
                    ▼
┌──────────────────────────────────────────┐
│  Reciprocal Rank Fusion (RRF)            │
│                                          │
│  score = Σ 1 / (k + rank)               │
│  k = thường 60 (constant)               │
│                                          │
│  Kết hợp rankings từ cả 2 paths          │
└───────────────────┬──────────────────────┘
                    ▼
┌──────────────────────────────────────────┐
│  Reranking (Optional)                    │
│  - Cross-encoder model                   │
│  - More accurate but slower              │
└──────────────────────────────────────────┘
```

### 4.3 Reciprocal Rank Fusion (RRF)

**Công thức:**
```
RRF_score(d) = Σᵢ 1 / (k + rankᵢ(d))

Trong đó:
- i: source (dense, sparse, hoặc các signals khác)
- rankᵢ(d): thứ hạng của document d trong source i
- k: hằng số (thường 60) để giảm impact của top ranks
```

**Ví dụ:**
```
Document A: Dense rank = 1, Sparse rank = 5
Document B: Dense rank = 3, Sparse rank = 2
k = 60

RRF(A) = 1/(60+1) + 1/(60+5) = 0.0164 + 0.0154 = 0.0318
RRF(B) = 1/(60+3) + 1/(60+2) = 0.0159 + 0.0161 = 0.0320

→ Document B được xếp cao hơn (tốt ở cả 2 sources)
```

### 4.4 Learned Sparse Retrieval (SPLADE, BGE-M3)

Thay vì dùng BM25, dùng neural model để tạo sparse vectors:

```
Input: "ngân hàng số"
↓ SPLADE/BGE-M3
Output sparse vector:
  "ngân": 0.8
  "hàng": 0.1
  "số": 0.9
  "digital": 0.85     ← learned expansion
  "banking": 0.82    ← learned expansion
  "fintech": 0.75    ← learned expansion
```

**Lợi ích:**
- Kết hợp interpretability của sparse vectors
- Semantic expansion của neural models
- Efficient storage và retrieval

---

## 5. RAG Architecture Trong Production

### 5.1 Kiến Trúc Tổng Quan

```
┌─────────────────────────────────────────────────────────────────┐
│                        RAG Pipeline                             │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  PHASE 1: INGESTION                                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Raw Documents → Chunking → Embedding → Vector DB + Metadata    │
│       ↓            ↓           ↓           ↓                    │
│    (PDF, HTML,   (Recursive   (BERT,     (Pinecone,            │
│     DB, etc.)     Tokenizer)   OpenAI)    pgvector, etc.)       │
│                                                                  │
│  Key Decisions:                                                  │
│  - Chunk size: 256-2048 tokens (trade-off: context vs precision)│
│  - Overlap: 10-20% (maintain context continuity)                │
│  - Metadata: source, timestamp, access control                  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  PHASE 2: RETRIEVAL                                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  User Query → Query Rewriting → Embedding → Vector Search       │
│       ↓              ↓              ↓              ↓            │
│   "Lãi suất    "Lãi suất      [0.23, -0.15...]   Top-K         │
│    hiện tại?"   tiết kiệm                        chunks        │
│                 VCB 2024"                                       │
│                                                                  │
│  Advanced Techniques:                                           │
│  - Query expansion (HyDE)                                       │
│  - Multi-vector retrieval                                       │
│  - Reranking (cross-encoder)                                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  PHASE 3: GENERATION                                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Prompt = System Instruction + Retrieved Context + User Query   │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Bạn là trợ lý ngân hàng. Trả lời dựa trên thông tin sau:│   │
│  │                                                         │   │
│  │ [Context 1] Lãi suất tiết kiệm VCB 2024: 6.5%/năm...   │   │
│  │ [Context 2] Điều kiện áp dụng: Từ 500 triệu...         │   │
│  │                                                         │   │
│  │ Câu hỏi: Lãi suất hiện tại?                             │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              ↓                                  │
│                       LLM Generation                            │
│                              ↓                                  │
│  "Lãi suất tiết kiệm VCB hiện tại là 6.5%/năm cho kỳ hạn..."    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 Chunking Strategies

| Strategy | Khi nào dùng | Ưu điểm | Nhược điểm |
|----------|--------------|---------|------------|
| **Fixed Size** | Văn bản đồng nhất | Đơn giản, nhanh | Có thể cắt giữa câu |
| **Recursive** | Văn bản có cấu trúc | Giữ nguyên ngữ nghĩa | Phức tạp hơn |
| **Semantic** | Cần semantic coherence | Chất lượng tốt nhất | Tốn compute |
| **Agentic** | Documents phức tạp | Thông minh nhất | Chậm nhất |

### 5.3 Query Transformation Techniques

**1. HyDE (Hypothetical Document Embeddings):**
```
Query: "Lãi suất VCB"
↓ LLM tạo hypothetical answer
"Lãi suất tiết kiệm VCB năm 2024 là 6.5%..."
↓ Embedding của hypothetical doc
→ Tìm kiếm vectors tương đồng
```
- Tốt cho queries ngắn, thiếu context
- Tăng recall nhưng có thể introduce bias

**2. Step-back Prompting:**
```
Query cụ thể: "Lãi suất kỳ hạn 6 tháng VCB"
↓ Step-back
Query tổng quát: "Lãi suất tiết kiệm ngân hàng"
↓ Tìm kiếm cả 2 levels
→ Kết hợp kết quả
```

**3. Multi-Query:**
```
Original: "Lãi suất VCB"
Generated variants:
- "Lãi suất tiết kiệm Vietcombank 2024"
- "Interest rate VCB savings"
- "Biểu lãi suất gửi tiết kiệm VCB"
↓ Tìm kiếm tất cả → deduplicate → rerank
```

### 5.4 Reranking Pattern

```
Phase 1: Retrieval (Fast, approximate)
  Vector Search → Top 100 candidates

Phase 2: Reranking (Slow, accurate)
  Cross-encoder hoặc ColBERT
  → Score exact relevance
  → Return Top 5-10

Trade-off: Latency tăng 50-200ms nhưng precision tăng đáng kể
```

---

## 6. Model Serving Patterns

### 6.1 Embedding Model Deployment

| Pattern | Latency | Throughput | Cost | Best For |
|---------|---------|------------|------|----------|
| **SaaS (OpenAI, Cohere)** | Low | High | Per token | MVP, burst traffic |
| **Self-hosted (GPU)** | Very low | High | Fixed | High volume, data privacy |
| **On-device** | Zero network | Limited | None | Mobile, edge |
| **Batch inference** | N/A | Max | Min | Offline processing |

### 6.2 Caching Strategy

```
┌─────────────────────────────────────────────────────┐
│                 Embedding Cache                     │
├─────────────────────────────────────────────────────┤
│                                                      │
│  Query: "Lãi suất VCB"                               │
│           ↓                                          │
│  ┌─────────────────┐    Cache Hit?    ┌───────────┐ │
│  │  Hash(query)    │ ───────────────→ │  Redis    │ │
│  │  = sha256(...)  │                  │  (TTL 1h) │ │
│  └─────────────────┘                  └───────────┘ │
│           ↓ Miss                                    │
│  ┌─────────────────┐                               │
│  │ Embedding Model │                               │
│  │ (GPU/CPU)       │                               │
│  └─────────────────┘                               │
│           ↓                                        │
│  Store in Redis (query_hash → embedding_vector)    │
│                                                      │
└─────────────────────────────────────────────────────┘
```

### 6.3 Load Balancing & Scaling

**Challenges:**
- Embedding models là **stateless** nhưng **GPU-bound**
- Batch processing hiệu quả hơn single queries
- Cold start time cho GPU instances

**Patterns:**
1. **Batched inference:** Tích lũy requests trong 10-50ms rồi batch process
2. **Queue-based:** SQS/Kafka → workers với GPU → results
3. **Serverless GPU:** RunPod, Modal, Replicate cho burst traffic

---

## 7. Rủi Ro, Anti-Patterns, Và Lỗi Thường Gặp

### 7.1 Catastrophic Forgetting Trong RAG

**Vấn đề:** LLM ưu tiên knowledge đã có sẵn hơn retrieved context.

**Giải pháp:**
- System prompt mạnh: "Chỉ dùng thông tin trong context"
- Citation enforcement: Yêu cầu LLM cite sources
- Temperature thấp (0.0-0.3) để giảm hallucination

### 7.2 Context Window Overflow

**Vấn đề:** Retrieved chunks quá dài, vượt quá context window.

**Giải pháp:**
- Map-reduce pattern: Tóm tắt từng chunk rồi combine
- Iterative refinement: Nhiều vòng retrieval
- Compression: Dùng model nhỏ hơn compress context

### 7.3 Vector DB Anti-Patterns

| Anti-Pattern | Tại sao sai | Giải pháp |
|--------------|-------------|-----------|
| **Không normalize vectors** | Cosine similarity bị ảnh hưởng | Normalize trước khi insert/query |
| **Quá nhiều dimensions** | Curse of dimensionality | Dùng dimensionality reduction |
| **Single tenant cho multi-tenant** | Security, performance issues | Namespace/partitioning |
| **No versioning** | Không rollback được | Version vectors cùng với code |
| **Ignoring deletion** | Stale data tích lũy | Soft delete + periodic cleanup |

### 7.4 Embedding Model Mismatch

**Vấn đề:** Train embedding model A nhưng dùng model B cho query.

**Hệ quả:** Semantic space không align → recall giảm mạnh.

**Giải pháp:**
- Cùng model cho indexing và querying
- Hoặc dùng model cùng "family" (ví dụ: cùng OpenAI embeddings)

### 7.5 The "Lost In The Middle" Problem

**Vấn đề:** LLM tập trung vào context ở đầu và cuối, bỏ qua giữa.

**Giải pháp:**
- Rerank và chỉ gửi top-K most relevant
- Place most important context ở đầu
- Dùng models với long context window

---

## 8. So Sánh Vector Databases

### 8.1 Feature Matrix

| Database | Open Source | Local/Cloud | Index Types | Hybrid Search | Scaling |
|----------|-------------|-------------|-------------|---------------|---------|
| **pgvector** | ✅ | Local | HNSW, IVF | ✅ (via PostgreSQL) | Vertical |
| **Pinecone** | ❌ | Cloud only | Proprietary | ✅ | Auto |
| **Weaviate** | ✅ | Both | HNSW, BM25 | ✅ | Horizontal |
| **Milvus/Zilliz** | ✅ | Both | HNSW, IVF, ANNOY | ✅ | Horizontal |
| **Qdrant** | ✅ | Both | HNSW | ✅ | Horizontal |
| **Chroma** | ✅ | Local | HNSW | ❌ | Limited |
| **Redis** | ✅ | Both | Flat, HNSW | ❌ | Vertical |
| **Elasticsearch** | ✅ | Both | HNSW | ✅ (native) | Horizontal |

### 8.2 Decision Tree

```
Bạn cần vector database?
        │
        ├── Scale < 1M vectors?
        │   └── YES → pgvector (nếu đã dùng PostgreSQL)
        │       hoặc Chroma (local dev)
        │
        ├── Cần hybrid search mạnh?
        │   └── YES → Weaviate, Elasticsearch, Milvus
        │
        ├── Cần managed service?
        │   └── YES → Pinecone, Zilliz
        │
        ├── Cần open source + self-host?
        │   └── YES → Qdrant, Milvus, Weaviate
        │
        └── Cần multi-modal (image, audio)?
            └── YES → Weaviate, Milvus
```

### 8.3 pgvector - Production Considerations

**Ưu điểm:**
- Không cần thêm infrastructure (nếu đã có PostgreSQL)
- ACID compliance
- Full SQL support + vector operations

**Giới hạn:**
- Single-node only (pgvecto.rs có clustering nhưng còn mới)
- Index build có thể lock table (dùng `CREATE INDEX CONCURRENTLY`)
- `ivfflat` index không support DELETE/UPDATE (chỉ HNSW support)

**Tuning:**
```sql
-- Chunk size lớn hơn = index build nhanh hơn nhưng nhiều memory
SET maintenance_work_mem = '2GB';

-- HNSW parameters
CREATE INDEX ON items USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

-- Query tuning
SET hnsw.ef_search = 100;  -- Trade-off recall vs speed
```

---

## 9. Khuyến Nghị Thực Chiến Trong Production

### 9.1 Architecture Recommendations

**Small Scale (< 1M docs, < 100 QPS):**
```
PostgreSQL + pgvector
Single embedding model (OpenAI text-embedding-3-small)
Simple RAG pipeline
```

**Medium Scale (1M-100M docs, 100-10K QPS):**
```
Dedicated Vector DB (Weaviate, Qdrant, Milvus)
Hybrid search (dense + BM25)
Caching layer (Redis)
Reranking pipeline
```

**Large Scale (> 100M docs, > 10K QPS):**
```
Distributed vector DB (Milvus cluster, Elasticsearch)
Multiple embedding models (domain-specific fine-tuned)
Multi-region deployment
Advanced RAG (HyDE, multi-query, agentic retrieval)
```

### 9.2 Monitoring & Observability

**Metrics quan trọng:**

| Metric | Target | Ý nghĩa |
|--------|--------|---------|
| **Recall@10** | > 0.95 | Độ chính xác retrieval |
| **Query Latency (p99)** | < 100ms | Trải nghiệm người dùng |
| **Embedding Latency** | < 50ms | Bottleneck thường gặp |
| **Index Build Time** | < 1 hour | Operational overhead |
| **Memory Usage** | < 80% | Avoid OOM |

**Distributed tracing:**
```
Request Path:
User → API Gateway → Query Rewriting (span) → Embedding (span)
  → Vector Search (span) → Reranking (span) → LLM (span) → Response
```

### 9.3 Security Considerations

1. **Data Privacy:**
   - Embedding có thể reverse-engineer để lấy lại text gốc
   - Nên encrypt vectors at rest
   - Access control ở cả application và DB level

2. **Prompt Injection:**
   - Sanitize user input trước khi đưa vào context
   - Dùng delimiters rõ ràng: `### Context ###`
   - Validate retrieved chunks (rate limiting, content filtering)

3. **Model Security:**
   - Embedding models có thể bị adversarial attacks
   - Version pinning cho models
   - Input validation

### 9.4 Cost Optimization

| Strategy | Tiết kiệm | Trade-off |
|----------|-----------|-----------|
| **Quantization (int8)** | 4x memory | < 2% recall drop |
| **Dimensionality reduction** | Nx memory | Information loss |
| **Caching** | 80-90% embedding cost | Stale data risk |
| **Batching** | 50-70% compute | Latency increase |
| **Smaller models** | 50% cost | Lower quality |

---

## 10. Kết Luận

### Bản Chất Của Vector Databases

Vector databases không phải là "database với vector" mà là **specialized storage systems** tối ưu cho:
1. **High-dimensional similarity search** với **sub-linear complexity**
2. **Approximate nearest neighbor** - chấp nhận trade-off giữa accuracy và speed
3. **Semantic retrieval** - tìm kiếm dựa trên ý nghĩa thay vì exact match

### Key Takeaways

1. **Algorithm matters:** HNSW cho general purpose, IVF cho memory-constrained, PQ cho billion-scale

2. **Hybrid is king:** Dense + sparse (BM25) + reranking = production-grade RAG

3. **RAG là pattern, không phải product:** Chunking, query transformation, và context management quan trọng hơn vector DB

4. **Trade-offs everywhere:**
   - Recall vs latency (ef_search)
   - Memory vs accuracy (quantization)
   - Throughput vs cost (batching)

5. **Production concerns:**
   - Versioning (embedding models evolve)
   - Monitoring (recall@k, latency)
   - Security (prompt injection, data privacy)

### When To Use / When Not To Use

| Use Vector DB | Don't Use Vector DB |
|---------------|---------------------|
| Semantic search | Exact keyword search đơn thuần |
| Recommendation systems | Relational queries phức tạp |
| RAG applications | Small dataset (<10K) - brute force |
| Similarity detection | Real-time requirements < 10ms |

---

## References

1. **HNSW Paper:** "Efficient and robust approximate nearest neighbor search using Hierarchical Navigable Small World graphs" (Malkov & Yashunin, 2016)
2. **FAISS:** Facebook AI Similarity Search - https://github.com/facebookresearch/faiss
3. **Microsoft RAG Survey:** "Retrieval-Augmented Generation for Large Language Models: A Survey" (2024)
4. **Vector DB Comparison:** db-engines.com/en/ranking/vector+dbms
5. **pgvector:** https://github.com/pgvector/pgvector

---

*Research completed: March 27, 2026*
*Author: Senior Backend Architect Research Team*
