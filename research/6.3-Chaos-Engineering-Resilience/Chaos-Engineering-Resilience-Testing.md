# Chaos Engineering & Resilience Testing

## 1. Mục tiêu của Task

Hiểu sâu bản chất của Chaos Engineering như một kỹ thuật kỹ thuật (engineering discipline) chứ không chỉ là "phá hoại hệ thống". Nắm vững các nguyên tắc thiết kế hệ thống chống chịu lỗi (resilience patterns), kỹ thuật kiểm thử hỗn loạn trong môi trường production, và xây dựng văn hóa SRE (Site Reliability Engineering) thông qua blameless post-mortem.

> **Chaos Engineering không phải để chứng minh hệ thống tồi.** Nó để khám phá những điểm yếu mà bạn chưa biết mình có.

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 Định nghĩa đúng đắn

Chaos Engineering là **khoa học thực nghiệm** về hệ thống phân tán, dựa trên việc **giả định hệ thống sẽ thất bại** và kiểm chứng các giả định về khả năng chống chịu.

**Nguyên tắc cốt lõi (Chaos Monkey Principles):**

| Nguyên tắc | Ý nghĩa thực tế |
|-----------|-----------------|
| **Build a hypothesis around steady-state behavior** | Định nghĩa "normal" bằng metrics đo lường được, không phải cảm tính |
| **Vary real-world events** | Giả lập các failure modes thực tế: network partition, disk full, CPU saturation, latency spike |
| **Run experiments in production** | Staging không bao giờ giống production 100% về traffic patterns, data volume, và network topology |
| **Automate experiments to run continuously** | Chaos là practice, không phải one-time event. CI/CD cho chaos experiments |
| **Minimize blast radius** | Canary chaos: test trên subset traffic, tự động rollback khi breach SLO |

> **Lưu ý quan trọng:** Chaos Engineering ≠ Chaos Monkey. Monkey chỉ là một implementation. Engineering là discipline.

### 2.2 Cơ chế Failure Injection

```
┌─────────────────────────────────────────────────────────────────┐
│                    CHAOS EXPERIMENT LIFECYCLE                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. DEFINE STEADY STATE                                         │
│     └── Metrics: p99 latency < 200ms, error rate < 0.1%        │
│                                                                 │
│  2. FORM HYPOTHESIS                                             │
│     └── "Khi database primary failover, read replica sẽ       │
│          đảm nhận traffic trong < 5s với < 1% error"          │
│                                                                 │
│  3. INJECT FAULT                                                │
│     └── Terminate primary DB pod, network partition,          │
│         disk latency injection                                  │
│                                                                 │
│  4. OBSERVE DEVIATION                                           │
│     └── Monitor SLO breach, error rate spike,                 │
│         cascading failures                                      │
│                                                                 │
│  5. ROLLBACK & REMEDIATE                                        │
│     └── Automated rollback, fix root cause,                   │
│         rerun experiment                                        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2.3 Các loại Failure Injection

**Infrastructure Level:**

| Failure Type | Công cụ | Kịch bản Production |
|-------------|---------|---------------------|
| **Instance termination** | Chaos Monkey, AWS Fault Injection Simulator | EC2/Container bị terminate ngẫu nhiên, test auto-healing |
| **Network partition** | Toxiproxy, Chaos Mesh, iptables | Split-brain scenarios, CAP theorem in action |
| **Resource exhaustion** | Stress-ng, Chaos Mesh | CPU/Memory saturation, OOM killer behavior |
| **Disk failure** | Device mapper, ChaosBlade | Disk full, I/O latency spike, read-only filesystem |
| **DNS failure** | Toxiproxy-DNS | Service discovery failures, retry storm |

**Application Level:**

| Failure Type | Công cụ | Kịch bản Production |
|-------------|---------|---------------------|
| **Latency injection** | Resilience4j, Chaos Monkey for Spring Boot | Downstream service chậm, timeout cascade |
| **Error injection** | Gremlin, custom filters | 5xx errors, exception throwing |
| **Request timeout** | Istio fault injection | HTTP timeout, gRPC deadline exceeded |
| **Dependency failure** | Hoverfly, WireMock | Third-party API outage simulation |

---

## 3. Kiến trúc và Luồng Xử Lý

### 3.1 Chaos Engineering Platform Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│                      CHAOS ENGINEERING PLATFORM                    │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────────────┐    │
│  │   Control   │────│ Experiment  │────│   Blast Radius      │    │
│  │   Plane     │    │   Engine    │    │   Controller        │    │
│  │             │    │             │    │                     │    │
│  │ - Schedule  │    │ - Hypothesis│    │ - Target selection  │    │
│  │ - Safety    │    │ - Injection │    │ - Canary %          │    │
│  │   checks    │    │ - Monitor   │    │ - Auto-rollback     │    │
│  │ - Audit log │    │ - Abort     │    │   triggers          │    │
│  └─────────────┘    └─────────────┘    └─────────────────────┘    │
│          │                 │                    │                  │
│          └─────────────────┼────────────────────┘                  │
│                            │                                       │
│  ┌─────────────────────────┼───────────────────────────────┐       │
│  │                         ▼                               │       │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐ │       │
│  │  │ Network  │  │ Compute  │  │ Storage  │  │   App    │ │       │
│  │  │ Chaos    │  │ Chaos    │  │ Chaos    │  │  Chaos   │ │       │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘ │       │
│  │                                                         │       │
│  │              TARGET SYSTEM (Production)                 │       │
│  └─────────────────────────────────────────────────────────┘       │
│                                                                    │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                     OBSERVABILITY STACK                     │   │
│  │  Prometheus/Grafana │ Jaeger/Tempo │ ELK/Loki │ PagerDuty   │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

### 3.2 Luồng tự động hóa Game Day

```
┌─────────┐     ┌──────────┐     ┌───────────┐     ┌───────────┐
│  SCHED  │────▶│  SAFETY  │────▶│  INJECT   │────▶│  MONITOR  │
│ TRIGGER │     │   CHECK  │     │  FAULT    │     │   SLO     │
└─────────┘     └──────────┘     └───────────┘     └─────┬─────┘
                                                         │
                              ┌──────────────────────────┼───────┐
                              │                          │       │
                              ▼                          ▼       │
                         ┌────────┐                ┌────────┐   │
                         │  PASS  │                │  FAIL  │   │
                         │Continue│                │ Abort  │───┘
                         └────────┘                └────────┘
                              │                          │
                              ▼                          ▼
                         ┌────────┐                ┌────────┐
                         │ REPORT │                │ ALERT  │
                         │        │                │ ONCALL │
                         └────────┘                └────────┘
```

---

## 4. So Sánh Các Lựa Chọn

### 4.1 Chaos Engineering Tools Comparison

| Tool | Strengths | Weaknesses | Best For |
|------|-----------|------------|----------|
| **Chaos Monkey** (Netflix) | Đơn giản, open-source, cloud-agnostic | Chỉ có instance termination | Bắt đầu với chaos engineering |
| **Chaos Mesh** (PingCAP) | Kubernetes-native, rich fault types, visualization | K8s only, complex RBAC | K8s-native applications |
| **Gremlin** | Enterprise features, safety controls, SaaS | Commercial, expensive | Enterprise environments |
| **ChaosBlade** (Alibaba) | Alibaba-proven at scale, diverse scenarios | Documentation tiếng Anh hạn chế | Large-scale Chinese cloud |
| **Litmus** (CNCF) | Cloud-native, GitOps integration, declarative | Mới hơn, ecosystem đang phát triển | GitOps workflows |
| **AWS FIS** | Native AWS integration, managed service | AWS lock-in | AWS-heavy environments |

### 4.2 Testing Strategies Comparison

| Strategy | Cost | Risk | Value | When to Use |
|----------|------|------|-------|-------------|
| **Unit Chaos** | Thấp | Thấp | Trung bình | Development phase, validate retry logic |
| **Integration Chaos** | Trung bình | Trung bình | Cao | Pre-production, validate service interactions |
| **Staging Chaos** | Trung bình | Thấp | Trung bình | Regression testing, don't catch prod-specific issues |
| **Production Chaos** | Cao | Cao | Rất cao | Validate real resilience, build confidence |
| **Chaos in CI/CD** | Thấp | Thấp | Cao | Prevent regression, shift-left resilience |

---

## 5. Rủi Ro, Anti-Patterns, và Lỗi Thường Gặp

### 5.1 Anti-Patterns nguy hiểm

> **"Chaos without monitoring is just chaos"**

| Anti-Pattern | Tại sao nguy hiểm | Cách làm đúng |
|--------------|-------------------|---------------|
| **"Big Bang" Chaos** | Inject failures toàn bộ hệ thống cùng lúc | Bắt đầu từ 0.1% traffic, tăng dần |
| **Blind Chaos** | Không có baseline metrics, không biết "normal" là gì | Định nghĩa steady-state rõ ràng |
| **Friday Afternoon Chaos** | Chạy experiment cuối tuần, không có oncall | Schedule trong giờ làm việc với team đủ người |
| **Chaos without rollback plan** | Không có abort criteria hoặc không thể revert | Automated abort on SLO breach |
| **One-off Chaos** | Chạy một lần rồi quên | Continuous chaos, chaos as code |

### 5.2 Failure Modes thường gặp trong Chaos Experiments

```
CASCADING FAILURE CHAIN
─────────────────────────

  ┌─────────────┐
  │  Service A  │ ◄─── Inject: 500ms latency
  └──────┬──────┘
         │
         │ Timeout: 1s
         │ Retry: 3x
         ▼
  ┌─────────────┐
  │  Service B  │ ◄─── Timeout threshold breached
  └──────┬──────┘
         │
         │ Connection pool exhausted
         ▼
  ┌─────────────┐
  │  Service C  │ ◄─── Circuit breaker opens
  └─────────────┘
         │
         │ Fallback: degraded mode
         ▼
  ┌─────────────┐
  │  Database   │ ◴─── Unexpected: connection spike
  └─────────────┘       from retries across services
```

### 5.3 Common Pitfalls

1. **Retry Storm**: Khi service A retry liên tục, service B cũng retry, tạo amplification factor.
   - **Fix**: Exponential backoff + jitter, circuit breaker, rate limiting per-client

2. **Hidden Dependency Discovery**: Chaos experiment phát hiện service "tưởng không liên quan" lại critical path.
   - **Fix**: Service dependency mapping, architecture documentation

3. **Data Corruption**: Network partition trong distributed transaction không có proper saga/compensation.
   - **Fix**: Idempotency keys, outbox pattern, proper distributed transaction design

4. **Blast Radius Leakage**: Chaos trên service A ảnh hưởng service Z qua 5 bước indirect.
   - **Fix**: Dependency graph analysis, blast radius calculation, canary by user segment

---

## 6. Khuyến Nghị Thực Chiến trong Production

### 6.1 Maturity Model (Chaos Maturity Ladder)

```
Level 5: Chaos as Culture
         └─▶ Automated chaos in CI/CD, Game Days monthly,
             Blameless post-mortems, resilience SLOs

Level 4: Automated Chaos
         └─▶ Continuous chaos experiments, auto-abort,
             chaos-as-code, chaos metrics in dashboards

Level 3: Production Chaos
         └─▶ Scheduled chaos in production, safety controls,
             blast radius management, oncall aware

Level 2: Staging Chaos  
         └─▶ Regular chaos in pre-prod, manual execution,
             basic hypothesis definition

Level 1: Ad-hoc Chaos
         └─▶ Sporadic testing, development environment,
             exploratory, no formal process

Level 0: No Chaos
         └─▶ "We don't need chaos, our system is stable"
             (famous last words)
```

### 6.2 Production Checklist

**Trước khi chạy Chaos Experiment:**

- [ ] Steady-state metrics được định nghĩa và monitor
- [ ] Abort criteria rõ ràng (SLO breach thresholds)
- [ ] Rollback plan tested và ready
- [ ] Oncall team được notify và available
- [ ] Blast radius giới hạn (canary, subset traffic)
- [ ] Emergency stop button accessible
- [ ] Stakeholders đã approve

**Trong lúc chạy:**

- [ ] Real-time monitoring dashboard
- [ ] Oncall response time < 2 minutes
- [ ] Auto-abort triggers active
- [ ] Communication channel mở (Slack/Teams)

**Sau khi chạy:**

- [ ] Post-mortem document trong 24h
- [ ] Action items với owner và timeline
- [ ] Run lại experiment sau khi fix
- [ ] Update runbook/playbook

### 6.3 Blameless Post-Mortem Template

```markdown
# Post-Mortem: [Incident Title]
Date: YYYY-MM-DD
Severity: SEV-1/2/3/4
Duration: XX minutes

## Timeline (Facts only)
- HH:MM - Event A occurred
- HH:MM - Alert fired
- HH:MM - Oncall acknowledged

## Root Cause (5 Whys)
1. Why did X happen? → Y
2. Why did Y happen? → Z
...
5. Root cause: [Underlying systemic issue]

## Impact
- Users affected: X
- Data affected: Y
- Revenue impact: $Z

## Lessons Learned
- What went well:
- What went wrong:
- Where we got lucky:

## Action Items
| Priority | Action | Owner | Due Date |
|----------|--------|-------|----------|
| P0 | Fix X | @name | YYYY-MM-DD |
```

### 6.4 Chaos Engineering Metrics

| Metric | Target | Ý nghĩa |
|--------|--------|---------|
| **Mean Time To Detect (MTTD)** | < 2 minutes | How fast monitoring catches issues |
| **Mean Time To Resolve (MTTR)** | < 30 minutes | How fast team fixes issues |
| **Chaos Experiment Success Rate** | > 80% | Experiments không breach abort criteria |
| **Blast Radius Accuracy** | > 95% | Predicted vs actual impact |
| **Rollback Time** | < 60 seconds | Time to abort experiment |

---

## 7. Kết Luận

Chaos Engineering là **discipline kỹ thuật** về việc **chủ động khám phá điểm yếu** trong hệ thống phân tán trước khi chúng gây incident thực sự.

**Bản chất cốt lõi:**
- Giả định failure sẽ xảy ra, kiểm chứng giả định về resilience
- Production là môi trường duy nhất cho thấy behavior thực sự
- Automated, continuous, và safe

**Trade-off quan trọng nhất:**
- **Risk của Chaos** vs **Risk của không biết điểm yếu**
- Giải pháp: Blast radius control, automated abort, canary approach

**Rủi ro lớn nhất:**
- Chaos without safety → Production incident
- Chaos without follow-up → Wasted effort
- Chaos as one-time event → False confidence

> **"The best time to introduce chaos engineering was when you built the system. The second best time is now."**

Chaos Engineering không chỉ về công cụ - nó về **văn hóa**: từ blame culture sang learning culture, từ "tại sao lại fail" sang "tại sao hệ thống không chịu được failure này".
