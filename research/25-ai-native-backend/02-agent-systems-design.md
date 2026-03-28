# Agent Systems Design: Multi-Agent Orchestration, Tool Calling & State Management

## 1. Mục tiêu của Task

Hiểu sâu bản chất thiết kế hệ thống Agent trong kiến trúc AI-Native:
- Cơ chế orchestration giữa nhiều agents hoạt động cùng lúc
- Mô hình tool calling và cách agents tương tác với external systems
- Chiến lược quản lý state trong hệ thống phân tán, stateful agents
- Trade-off giữa các kiến trúc: Supervisor, Hierarchical, Collaborative
- Production concerns: observability, error handling, recovery, scaling

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 Định Nghĩa Agent trong Kiến Trúc Phần Mềm

Agent là một đơn vị tự trị (autonomous unit) có khả năng:
- **Perceive**: Nhận input từ environment (user query, system events, sensor data)
- **Reason**: Suy luận sử dụng LLM hoặc rule-based logic để quyết định hành động
- **Act**: Thực thi hành động thông qua tool calling hoặc state transitions
- **Learn**: Cập nhật behavior dựa trên feedback (optional, in advanced systems)

```
┌─────────────────────────────────────────┐
│              AGENT LOOP                 │
├─────────────────────────────────────────┤
│  Input → Reason → Plan → Act → Observe │
│    ↑                                    │
│    └────────────────────────────────────┘
└─────────────────────────────────────────┘
```

### 2.2 Kiến Trúc Cognitive Core

Bản chất của một agent nằm ở **Cognitive Core** - thành phần đưa ra quyết định:

| Thành phần | Vai trò | Tần suất thay đổi |
|------------|---------|-------------------|
| System Prompt | Định nghĩa personality, constraints, goals | Thấp (version-controlled) |
| Context Window | Lưu trữ conversation history, working memory | Cao (per-session) |
| Tool Registry | Danh sách available tools với schemas | Trung bình (feature releases) |
| State Store | Persistent data về task progress, user prefs | Cao (real-time updates) |

> **Core Insight**: Agent không "thông minh" - nó là một **state machine được điều khiển bởi LLM**, với prompt engineering là cầu nối giữa natural language và deterministic execution.

### 2.3 Tool Calling Mechanism

Tool calling là giao thức giúp agent tương tác với external world. Cơ chế hoạt động:

#### Phase 1: Tool Discovery & Registration
```
Tool Registry chứa:
- name: unique identifier
- description: natural language mô tả khi nào dùng
- parameters: JSON Schema cho input validation
- handler: reference đến implementation
```

#### Phase 2: Intent Recognition
LLM nhận user input → xác định có cần tool không → nếu có, generate tool call với arguments.

#### Phase 3: Execution & Result Integration
```
┌──────────┐    tool_call request    ┌─────────────┐
│   LLM    │ ──────────────────────→ │ Tool Bridge │
│          │ ←────────────────────── │             │
└──────────┘    JSON result          └──────┬──────┘
                                            │
                                            ▼
                                      ┌─────────────┐
                                      │  Tool Impl  │
                                      │ (Idempotent)│
                                      └─────────────┘
```

> **Quan trọng**: Tool phải được thiết kế **idempotent** - cùng một tool call với cùng arguments phải cho cùng kết quả, an toàn khi retry.

---

## 3. Kiến Trúc Multi-Agent Orchestration

### 3.1 Supervisor Pattern (Centralized)

```
                    ┌─────────────┐
                    │  Supervisor │
                    │   Agent     │
                    └──────┬──────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
        ▼                  ▼                  ▼
   ┌─────────┐       ┌─────────┐       ┌─────────┐
   │ Agent A │       │ Agent B │       │ Agent C │
   │Research │       │  Code   │       │  Test   │
   └─────────┘       └─────────┘       └─────────┘
```

**Cơ chế**:
1. Supervisor nhận task từ user
2. Phân tích và quyết định delegate cho agent phù hợp
3. Agent thực thi và return kết quả
4. Supervisor có thể: (a) return kết quả, (b) delegate tiếp, (c) orchestrate workflow phức tạp

**Trade-offs**:
| Ưu điểm | Nhược điểm |
|---------|------------|
| Clear accountability | Single point of failure |
| Centralized logic dễ debug | Bottleneck khi scale |
| Consistent decision making | Supervisor prompt phức tạp, dễ hallucinate |
| Dễ implement rate limiting, auth | Latency cao hơn do "hops" qua supervisor |

**Khi nào dùng**: Workflow rõ ràng, cần centralized control, team nhỏ, độ phức tạp thấp-trung bình.

### 3.2 Hierarchical Pattern (Tree Structure)

```
              ┌──────────────┐
              │ Root Manager │
              └──────┬───────┘
                     │
        ┌────────────┼────────────┐
        │            │            │
        ▼            ▼            ▼
   ┌────────┐   ┌────────┐   ┌────────┐
   │Team Lead│   │Team Lead│   │Team Lead│
   │ (API)   │   │ (Data)  │   │ (Infra) │
   └────┬───┘   └────┬───┘   └────┬───┘
        │            │            │
   ┌────┴────┐  ┌────┴────┐  ┌────┴────┐
   │A1 │A2  │  │A3 │A4  │  │A5 │A6  │
   └─────────┘  └─────────┘  └─────────┘
```

**Cơ chế**: Mỗi node có thể là supervisor hoặc worker. Quyết định được đẩy xuống levels, giảm cognitive load cho root.

**Trade-offs**:
| Ưu điểm | Nhược điểm |
|---------|------------|
| Natural fit organizational structure | Debugging phức tạp (deep call stacks) |
| Better load distribution | Latency tăng theo tree depth |
| Specialization theo domain | Context loss khi đi qua nhiều layers |
| Scales better than flat supervisor | Higher operational complexity |

### 3.3 Collaborative Pattern (Peer-to-Peer / Mesh)

```
   ┌─────────┐ ←────────→ ┌─────────┐
   │ Agent A │              │ Agent B │
   │(Planner)│ ←────────→ │(Coder)  │
   └────┬────┘              └────┬────┘
        │                        │
        └──────────┬─────────────┘
                   │
                   ▼
              ┌─────────┐
              │ Agent C │
              │(Reviewer)│
              └─────────┘
```

**Cơ chế**: Agents giao tiếp trực tiếp với nhau thông qua message bus hoặc shared memory. Không có central coordinator.

**Implementation approaches**:
1. **Blackboard Pattern**: Shared state space, agents đọc/ghi vào common workspace
2. **Message Passing**: Async communication qua message queue (Redis Pub/Sub, RabbitMQ)
3. **Actor Model**: Each agent là một actor với mailbox riêng

**Trade-offs**:
| Ưu điểm | Nhược điểm |
|---------|------------|
| High scalability, no bottleneck | Hard to debug (distributed state) |
| Resilient (no single point of failure) | Difficult to ensure consistency |
| Natural for emergent behavior | Risk of circular dependencies |
| Flexible topology | Requires sophisticated consensus mechanisms |

**Khi nào dùng**: Complex problem solving, research tasks, creative work, systems requiring emergent intelligence.

### 3.4 Pipeline Pattern (Sequential)

```
Input → [Agent A] → [Agent B] → [Agent C] → Output
        (Extract)   (Transform)  (Validate)
```

**Trade-offs**: Predictable, dễ debug, nhưng inflexible, high latency do sequential processing.

---

## 4. State Management trong Agent Systems

### 4.1 State Taxonomy

```
State Categories:
├── Ephemeral (Per-turn)
│   └── Context window, current reasoning trace
├── Session (Per-conversation)  
│   └── Conversation history, user preferences accumulated
├── Task (Per-workflow)
│   └── Multi-step task progress, intermediate results
├── Persistent (Cross-session)
│   └── User profile, learned patterns, cached knowledge
└── Global (Shared)
    └── Tool availability, system configuration, rate limits
```

### 4.2 State Persistence Strategies

| Strategy | Use Case | Trade-off |
|----------|----------|-----------|
| **In-Memory** | Ephemeral state, high-frequency access | Fast, but lost on restart |
| **Redis** | Session state, distributed access | Fast, TTL support, but eventual consistency |
| **PostgreSQL** | Persistent state, complex queries | ACID, but higher latency |
| **Event Store** | Audit trail, event sourcing | Immutable, replayable, but storage heavy |
| **Vector DB** | Semantic memory, RAG context | Similarity search, but approximate |

### 4.3 Context Window Management

> **Critical Constraint**: LLM có context limit (4K-200K tokens). State management = **compression strategy**.

**Techniques**:

1. **Summarization**: Periodically summarize old conversation, drop raw messages
```
Messages: [M1, M2, M3, M4, M5, M6] 
→ Summarize(M1-M3) = S1
→ State: [S1, M4, M5, M6]
```

2. **Selective Loading**: Chỉ load relevant context dựa trên current intent
   - RAG-based retrieval từ conversation history
   - Keyword/sentiment-based filtering

3. **Hierarchical Memory**: 
   - Working memory (immediate context)
   - Short-term memory (recent summaries)
   - Long-term memory (key facts in vector DB)

### 4.4 State Consistency trong Multi-Agent

**Problem**: Nhiều agents cùng read/write shared state → race conditions, stale data.

**Solutions**:

| Approach | Mechanism | Complexity |
|----------|-----------|------------|
| **Optimistic Locking** | Version numbers, detect conflicts on write | Medium |
| **Pessimistic Locking** | Acquire lock before state mutation | Medium |
| **Event Sourcing** | Append-only events, replay to rebuild state | High |
| **CRDTs** | Conflict-free replicated data types | High |
| **Single Writer** | Chỉ một agent được phép write specific state | Low |

> **Recommendation**: Bắt đầu với **Single Writer principle** - mỗi state domain có một owner agent duy nhất. Đơn giản, dễ debug, tránh race condition.

---

## 5. Tool Calling Deep Dive

### 5.1 Tool Schema Design

Good tool schema = LLM dễ hiểu + validation chặt chẽ:

```yaml
# Anti-pattern: Vague description
bad_tool:
  name: "search"
  description: "Search for things"
  
# Pattern: Precise, with examples
good_tool:
  name: "search_documents"
  description: |
    Search internal document repository using semantic similarity.
    Use when user asks about: company policies, past decisions, 
    technical documentation, or archived discussions.
    
    Example queries:
    - "What's our vacation policy?"
    - "How did we implement auth last year?"
    - "Find discussions about database migration"
  parameters:
    query:
      type: string
      description: "Natural language search query"
      minLength: 3
      maxLength: 500
    limit:
      type: integer
      description: "Max results to return (1-20)"
      default: 5
      minimum: 1
      maximum: 20
```

### 5.2 Tool Execution Patterns

**Pattern 1: Synchronous (Blocking)**
```
Agent → Tool Call → Wait → Result → Continue
```
- Pros: Simple, predictable
- Cons: Wastes context window waiting, long latency

**Pattern 2: Asynchronous (Non-blocking)**
```
Agent → Tool Call → Continue Other Work → Callback with Result
```
- Pros: Better resource utilization
- Cons: Complex state management, callback handling

**Pattern 3: Streaming (Progressive)**
```
Agent → Tool Call → Chunk 1 → Chunk 2 → ... → Done
```
- Pros: Real-time feedback, better UX
- Cons: Complex cancellation, partial failure handling

### 5.3 Tool Safety & Sandboxing

| Risk | Mitigation |
|------|------------|
| SQL Injection trong DB tool | Parameterized queries, read-only accounts |
| File system traversal | Path validation, chroot jail |
| Network abuse | Allowlist domains, rate limiting |
| Resource exhaustion | Timeout, memory limits, circuit breaker |
| Secret exposure | No credentials in tool schemas, use IAM roles |

> **Golden Rule**: Tools execute với **least privilege**. Never expose raw SQL, file paths, or shell commands directly cho LLM.

---

## 6. So Sánh Các Framework & Implementation

| Framework | Pattern | Language | Best For | Trade-off |
|-----------|---------|----------|----------|-----------|
| **LangGraph** | Graph-based | Python | Complex workflows, state machines | Learning curve, Python-only |
| **AutoGen** | Conversational | Python | Multi-agent chat, research | Microsoft ecosystem, less flexible |
| **CrewAI** | Role-based | Python | Task delegation, business workflows | Opinionated, simpler use cases |
| **LlamaIndex** | RAG-focused | Python/TS | Knowledge-heavy agents | Less focus on multi-agent |
| **OpenAI Assistants** | Thread-based | Any (API) | Quick start, managed state | Vendor lock-in, limited control |
| **Semantic Kernel** | Multi-pattern | .NET/Python | Enterprise, Microsoft stack | Complex, heavy |

**Custom Implementation vs Framework**:

| Yếu tố | Custom | Framework |
|--------|--------|-----------|
| Control | Full | Limited |
| Time to market | Slow | Fast |
| Debuggability | Harder (your code) | Mixed (framework bugs) |
| Flexibility | Unlimited | Framework constraints |
| Maintenance burden | High | Lower |

> **Khuyến nghị**: Prototype với framework (LangGraph/AutoGen), sau đó extract core patterns cho custom implementation nếu cần fine-grained control.

---

## 7. Rủi Ro, Anti-Patterns, Lỗi Thường Gặp

### 7.1 Agent Hallucination trong Tool Selection

**Problem**: Agent chọn sai tool hoặc generate sai arguments.

**Mitigation**:
- **Few-shot examples** trong system prompt
- **Strict JSON schema validation** trước execution
- **Tool confirmation** cho destructive operations
- **Retry với error feedback** (LLM tự sửa lỗi)

### 7.2 Infinite Loops

```
Agent A → "Ask Agent B" → Agent B → "Ask Agent A" → ...
```

**Prevention**:
- Max turn limits
- Loop detection (track conversation path)
- Timeout trên mỗi agent interaction
- Clear termination conditions trong prompts

### 7.3 Context Window Overflow

**Symptom**: Agent "quên" thông tin từ đầu conversation.

**Fixes**:
- Aggressive summarization
- Structured output để extract key facts
- External memory (vector DB) cho long-term facts
- Sliding window với overlap

### 7.4 Tool Sprawl

**Anti-pattern**: Tạo quá nhiều tools, LLM confused về khi nào dùng cái nào.

**Solution**: 
- Tool categorization, chỉ expose relevant tools per context
- Composite tools (high-level operations)
- Tool deprecation process

### 7.5 State Corruption

**Scenario**: Agent crash giữa multi-step operation, state inconsistent.

**Mitigation**:
- **Transactional state updates**: Save state + mark complete atomically
- **Checkpointing**: Periodic save state, enable resume
- **Idempotent operations**: Safe to retry
- **Dead letter queue**: Failed operations for manual review

---

## 8. Khuyến Nghị Thực Chiến trong Production

### 8.1 Observability Stack

```
┌─────────────────────────────────────────┐
│            OBSERVABILITY                │
├─────────────────────────────────────────┤
│  Tracing: OpenTelemetry + Jaeger       │
│  → Track request qua multiple agents    │
│                                         │
│  Metrics: Prometheus + Grafana         │
│  → Tool call latency, success rates     │
│  → Token usage, context window fill     │
│                                         │
│  Logging: Structured JSON              │
│  → Agent decisions, tool calls, errors  │
│  → Include conversation IDs, trace IDs  │
│                                         │
│  LLM-specific: LangSmith/Langfuse      │
│  → Prompt versioning, token costs       │
│  → A/B testing prompts                  │
└─────────────────────────────────────────┘
```

### 8.2 Error Handling Strategy

| Error Type | Handling |
|------------|----------|
| LLM API Error (rate limit, timeout) | Exponential backoff, circuit breaker |
| Tool Execution Error | Return error cho LLM, let it retry/recover |
| Invalid Tool Arguments | Schema validation + retry với feedback |
| Agent Timeout | Graceful degradation, partial results |
| Hallucination | Human-in-the-loop cho critical decisions |

### 8.3 Scaling Patterns

1. **Horizontal Pod Autoscaling**: Scale agent instances dựa trên queue depth
2. **Tool Service Separation**: Tools chạy như independent microservices
3. **Caching**: Cache frequent LLM calls (semantic caching)
4. **Async Processing**: Long-running tasks qua background job queue

### 8.4 Security Checklist

- [ ] Input validation trên tất cả tool parameters
- [ ] Output sanitization trước khi return cho user
- [ ] Tool permissions: principle of least privilege
- [ ] Audit logging cho tất cả tool calls
- [ ] Rate limiting per user, per tool
- [ ] PII detection và masking trong logs

### 8.5 Testing Strategy

| Test Type | Approach |
|-----------|----------|
| Unit | Mock LLM responses, test tool logic |
| Integration | Test tool calls với real dependencies (staging) |
| Prompt Testing | A/B test prompts, measure task completion rate |
| Chaos | Inject tool failures, test recovery |
| Load | Simulate nhiều concurrent agents |

---

## 9. Kết Luận

### Bản Chất Cốt Lõi

Agent system là **distributed state machine với LLM như transition function**:
- State = context + memory + task progress
- Transition = LLM reasoning + tool calling
- Orchestration = protocol để nhiều state machines phối hợp

### Trade-off Quan Trọng Nhất

**Flexibility vs. Predictability**:
- LLM cho phép natural language interface và adaptive behavior
- Nhưng đổi lại là non-determinism, khó debug, khó test
- **Solution**: Hard boundaries (strict schemas, timeouts, validation) cho phép soft logic (LLM) hoạt động an toàn.

### Rủi Ro Lớn Nhất

**Compound Failure**: Một agent lỗi → cascade qua hệ thống → state corruption hoặc infinite loop.

**Mitigation**: 
1. Circuit breakers giữa agents
2. Idempotent operations
3. Clear ownership (single writer per state)
4. Comprehensive observability

### Khuyến Nghị Cuối Cùng

Bắt đầu đơn giản: **Single agent + curated tools + explicit state management**. Scale lên multi-agent chỉ khi:
- Đã có observability tốt
- Đã xử lý được error cases cơ bản
- Có clear boundaries giữa agent responsibilities

Multi-agent không phải là "more intelligent" - nó là **more complex**. Complexity phải được justified bởi clear requirements (parallelism, specialization, fault isolation).

---

## References

- [LangGraph Documentation](https://langchain-ai.github.io/langgraph/)
- [Microsoft AutoGen Paper](https://arxiv.org/abs/2308.08155)
- [OpenAI Function Calling Guide](https://platform.openai.com/docs/guides/function-calling)
- [Google's Agent Whitepaper](https://www.kaggle.com/whitepaper-agents)
- [Multi-Agent Reinforcement Learning Survey](https://arxiv.org/abs/2312.05117)
