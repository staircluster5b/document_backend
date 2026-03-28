# Disaster Recovery: RTO/RPO, Backup Strategies & Multi-Region Failover

## 1. Mục tiêu của task

Hiểu sâu về Disaster Recovery (DR) trong hệ thống phân tán:
- Xác định và đo lường RTO/RPO - hai chỉ số then chốt quyết định chiến lược DR
- Thiết kế backup strategies phù hợp với từng loại dữ liệu và yêu cầu business
- Kiến trúc multi-region failover đảm bảo availability và consistency
- Trade-offs giữa chi phí, complexity, và mức độ bảo vệ

> ⚠️ **Lưu ý quan trọng**: DR không phải "backup và restore đơn thuần". Đây là discipline về **business continuity** - duy trì hoạt động kinh doanh trong điều kiện bất thường.

---

## 2. Bản chất và cơ chế hoạt động

### 2.1 RTO (Recovery Time Objective)

**Định nghĩa**: ThờI gian tối đa hệ thống có thể offline từ lúc disaster xảy ra đến khi fully operational.

**Bản chất cơ chế**:

```
Timeline của sự cố:
├─ T0: Disaster occurs
├─ Tdetect: Phát hiện sự cố (monitoring/alerting)
├─ Tevaluate: Đánh giá và quyết định activate DR
├─ Tfailover: Thực hiện failover (DNS switch, DB promotion, etc.)
├─ Tverify: Verify systems operational
└─ Trestore: Full service restoration

RTO = Trestore - T0 (must be ≤ target RTO)
```

**Các thành phần ảnh hưởng đến RTO**:

| Thành phần | Yếu tố ảnh hưởng | Tối ưu |
|-----------|------------------|--------|
| Detection | Monitoring granularity, alert routing | Sub-minute alerting with PagerDuty/Opsgenie |
| Decision | Runbook clarity, on-call response | Automated decision trees, pre-approved failover |
| Execution | Automation level, dependency chain | Infrastructure as Code, pre-staged resources |
| Verification | Health check comprehensiveness | Synthetic monitoring, automated smoke tests |

**Trade-off chính của RTO**:
- RTO thấp (phút) → Chi phí cao (hot standby, active-active), complexity cao
- RTO cao (giờ/ngày) → Chi phí thấp (cold standby), rủi ro business cao

> 💡 **Pattern quan trọng**: "Failover time ≠ Recovery time". Hệ thống có thể "up" nhưng chưa "healthy". RTO phải tính đến thờI gian đạt steady state.

### 2.2 RPO (Recovery Point Objective)

**Định nghĩa**: Lượng dữ liệu tối đa có thể mất (thờI gian giữa lần backup/replication cuối và thờI điểm disaster).

**Bản chất cơ chế**:

```
Data timeline:
├─ T-24h: Full backup
├─ T-6h:  Incremental backup  
├─ T-1h:  Incremental backup
├─ T-10m: Replication sync (last successful)
├─ T0:    DISASTER OCCURS ← 10 minutes data at risk
└─        → RPO = 10 minutes
```

**RPO và cơ chế replication**:

| Replication Mode | RPO đạt được | Trade-offs |
|-----------------|--------------|-----------|
| Synchronous | ~0 (zero RPO) | Latency cao, distance limit (~100km), throughput giảm |
| Asynchronous | Seconds - Minutes | RPO > 0, eventual consistency, distance không giới hạn |
| Near-synchronous | Sub-second | Hybrid, complexity cao, cần specialized hardware |

**Synchronous Replication - Cơ chế sâu**:

```
Write Path (Synchronous):
1. Client ghi vào Primary
2. Primary forward write đến Secondary (qua dedicated link)
3. Secondary ack sau khi ghi vào local journal
4. Primary ack client sau khi nhận Secondary ack
5. Latency = Network RTT + Secondary write time

Constraint: Primary và Secondary phải trong cùng metro region
```

**Điểm mù của RPO**:
- RPO chỉ đo "committed data". Transactions in-flight không được bảo vệ.
- Application-level buffering (e.g., Kafka producer buffer) có thể gây mất data ngay cả với sync replication.
- Split-brain scenarios: Network partition giữa regions có thể gây mất data khi chuyển về primary cũ.

### 2.3 Mối quan hệ RTO-RPO

```
              RPO thấp (zero data loss)
                      ↑
                      │
    Hot Standby ──────┼────── Active-Active
    (High Cost)       │       (Highest Cost)
                      │
RTO thấp ←────────────┼────────────→ RTO cao
  (Phút)              │               (Ngày)
                      │
    Warm Standby ─────┼────── Cold Standby
    (Medium Cost)     │       (Low Cost)
                      │
                      ↓
              RPO cao (24h+ backup)
```

**Quy tắc vàng**: Không thể đạt cả RTO=0 và RPO=0 với chi phí hợp lý. Phải chấp nhận trade-off.

---

## 3. Kiến trúc / Luồng xử lý

### 3.1 Backup Strategies Architecture

#### 3.1.1 Backup Types - Bản chất và use case

**Full Backup**:
- Bản chất: Copy toàn bộ dataset
- Storage: N × dataset size (N = số backup giữ lại)
- RPO: Cao (thờI gian giữa các full backup)
- ThờI gian restore: Nhanh (single restore)
- Use case: Weekly foundation, databases nhỏ

**Incremental Backup**:
- Bản chất: Chỉ backup thay đổi từ lần backup trước
- Storage: Efficient (~10-20% của full backup cho daily)
- RPO: Trung bình
- ThờI gian restore: Chậm (phải restore chain: full + tất cả incremental)
- Use case: Daily backup, databases lớn

**Differential Backup**:
- Bản chất: Backup thay đổi từ lần full backup gần nhất
- Storage: Trung bình (tăng dần theo thờI gian từ full backup)
- RPO: Trung bình
- ThờI gian restore: Trung bình (full + latest differential)
- Use case: Mid-week backup khi incremental chain quá dài

**Continuous Data Protection (CDP)**:
- Bản chất: Ghi log mọi thay đổi real-time
- Storage: Rất cao
- RPO: Near-zero
- ThờI gian restore: Flexible (point-in-time recovery)
- Use case: Critical databases, compliance requirements

#### 3.1.2 Backup Strategy Matrix

| Data Type | Criticality | Backup Strategy | Retention | Storage |
|-----------|-------------|-----------------|-----------|---------|
| User data (OLTP) | Critical | Full weekly + Incremental daily + WAL streaming | 30 days | Primary: SSD, Archive: S3 Glacier |
| Analytics (OLAP) | High | Full weekly + Incremental daily | 90 days | S3 Standard-IA |
| Logs | Medium | Incremental daily | 1 year | S3 Glacier Deep Archive |
| Config/Code | Low | Git + Infrastructure as Code | Forever | Git + S3 versioning |

#### 3.1.3 3-2-1 Backup Rule

```
3 copies của data:
  ├─ 1: Production data (primary)
  ├─ 2: On-site backup (local, nhanh restore)
  └─ 3: Off-site backup (remote, chống disaster vật lý)

2 different media types:
  ├─ Disk (hot, fast)
  └─ Tape/Cloud (cold, durable)

1 copy off-site:
  └─ Geographic separation (min 100km)
```

**Mở rộng thành 3-2-1-1-0**:
- 1 offline/air-gapped (chống ransomware)
- 0 errors (verified restore testing)

### 3.2 Multi-Region Failover Architecture

#### 3.2.1 DR Patterns

```
┌─────────────────────────────────────────────────────────────────┐
│                        ACTIVE-ACTIVE                             │
├─────────────────────────────────────────────────────────────────┤
│  Region A (Active)          Region B (Active)                   │
│  ┌─────────────┐            ┌─────────────┐                     │
│  │   Traffic   │◄──────────►│   Traffic   │                     │
│  │   50%       │   Sync     │   50%       │                     │
│  └──────┬──────┘  Replic.   └──────┬──────┘                     │
│         │                          │                            │
│  ┌──────▼──────┐            ┌──────▼──────┐                     │
│  │    DB       │◄──────────►│    DB       │                     │
│  │  (Master)   │   Sync     │  (Master)   │                     │
│  └─────────────┘            └─────────────┘                     │
│                                                                 │
│  RTO: ~0 (seamless)                                             │
│  RPO: ~0 (sync replication)                                     │
│  Cost: 2x infrastructure + sync overhead                        │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                       ACTIVE-STANDBY (Hot)                       │
├─────────────────────────────────────────────────────────────────┤
│  Region A (Active)          Region B (Hot Standby)              │
│  ┌─────────────┐            ┌─────────────┐                     │
│  │   Traffic   │            │  (Ready to  │                     │
│  │   100%      │            │   serve)    │                     │
│  └──────┬──────┘            └──────┬──────┘                     │
│         │                          │                            │
│  ┌──────▼──────┐            ┌──────▼──────┐                     │
│  │    DB       │───────────►│    DB       │                     │
│  │  (Primary)  │   Async    │  (Replica)  │                     │
│  └─────────────┘  Replic.   └─────────────┘                     │
│                                                                 │
│  RTO: Minutes (DNS switch + DB promotion)                       │
│  RPO: Seconds-Minutes (replication lag)                         │
│  Cost: 2x infrastructure, 1x active traffic                     │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                       ACTIVE-STANDBY (Warm)                      │
├─────────────────────────────────────────────────────────────────┤
│  Region A (Active)          Region B (Warm Standby)             │
│  ┌─────────────┐            ┌─────────────┐                     │
│  │   Traffic   │            │  (Scaled    │                     │
│  │   100%      │            │   down)     │                     │
│  └──────┬──────┘            └──────┬──────┘                     │
│         │                          │                            │
│  ┌──────▼──────┐            ┌──────▼──────┐                     │
│  │    DB       │───────────►│    DB       │                     │
│  │  (Primary)  │   Async    │  (Replica)  │                     │
│  └─────────────┘  Replic.   └─────────────┘                     │
│                                                                 │
│  RTO: 15-60 minutes (scale up + verification)                   │
│  RPO: Minutes (replication lag)                                 │
│  Cost: ~1.3x infrastructure (replica running)                   │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                       ACTIVE-STANDBY (Cold)                      │
├─────────────────────────────────────────────────────────────────┤
│  Region A (Active)          Region B (Cold Standby)             │
│  ┌─────────────┐            ┌─────────────┐                     │
│  │   Traffic   │            │  (Not       │                     │
│  │   100%      │            │  running)   │                     │
│  └──────┬──────┘            └──────┬──────┘                     │
│         │                          │                            │
│  ┌──────▼──────┐            ┌──────▼──────┐                     │
│  │    DB       │───────────►│   Backup    │                     │
│  │  (Primary)  │   Backup   │   Storage   │                     │
│  └─────────────┘   to S3     └─────────────┘                     │
│                                                                 │
│  RTO: Hours-Days (provision + restore from backup)              │
│  RPO: Hours (backup frequency)                                  │
│  Cost: ~1.1x infrastructure (storage only)                      │
└─────────────────────────────────────────────────────────────────┘
```

#### 3.2.2 Failover Decision Matrix

| Trigger | Automated Failover? | Considerations |
|---------|---------------------|----------------|
| Single AZ failure | Yes | If multi-AZ within region |
| Full region failure | Usually No* | Need human verification to avoid split-brain |
| Database corruption | No | Failover sẽ replicate corruption |
| Performance degradation | Maybe | Depends on threshold tuning |
| Manual DR test | Yes | Scheduled, pre-approved |

*Trừ khi đã có mature automated decision system với multiple verification sources.

#### 3.2.3 Split-Brain Prevention

**Vấn đề**: Network partition khiến cả hai regions đều nghĩ mình là primary.

```
                    Network Partition
    Region A ◄────────────────────────────► Region B
    (nghĩ B down)                           (nghĩ A down)
         │                                        │
         ▼                                        ▼
    Promote self                            Promote self
    Accept writes                           Accept writes
         │                                        │
         └───────────────► ◄──────────────────────┘
              Divergent data (irreconcilable)
```

**Giải pháp - Quorum-based approach**:

```
Consul/etcd Zookeeper
    │    │    │
    └────┼────┘
         │
    Witness Node (3rd region/Cloud)
         │
    ┌────┴────┐
    ▼         ▼
 Region A  Region B
```

- Region chỉ promote khi có quorum (majority của witness + self)
- Pattern: "fencing token" - stamp mọi write với token từ coordinator
- STONITH (Shoot The Other Node In The Head): Force shutdown primary cũ

---

## 4. So sánh các lựa chọn

### 4.1 Database DR Strategies

| Strategy | RTO | RPO | Complexity | Cost | Use Case |
|----------|-----|-----|------------|------|----------|
| Cross-region read replica | Minutes | Minutes | Low | Medium | PostgreSQL, MySQL |
| Aurora Global Database | Seconds | Seconds | Low | High | AWS-native, MySQL/PG |
| CockroachDB Spanner | ~0 | ~0 | Medium | High | Global distributed SQL |
| DynamoDB Global Tables | ~0 | ~0 | Low | Variable | NoSQL, eventual consistency |
| MongoDB Atlas Multi-Region | Minutes | Minutes | Medium | Medium | Document DB |
| Self-managed Streaming Replication | Minutes | Seconds | High | Medium | Custom control |

### 4.2 Storage DR Options

| Solution | Replication | Consistency | Failover | Notes |
|----------|-------------|-------------|----------|-------|
| S3 Cross-Region Replication | Async | Eventual | Manual | Versioning required |
| EBS Snapshots + Cross-region copy | Async | RPO = snapshot interval | Manual | Slow restore |
| EFS Cross-region replication | Async | Eventual | Manual | NFS compatible |
| FSx Multi-AZ (same region) | Sync | Strong | Automatic | Not cross-region |
| DRBD (self-managed) | Sync/Async | Configurable | Manual | Linux block device |

### 4.3 Application Layer DR

| Pattern | Availability | Complexity | Data Loss Risk |
|---------|--------------|------------|----------------|
| Stateless + DB replication | High | Low | DB-dependent |
| Session replication (sticky) | Medium | Medium | Session data |
| JWT stateless tokens | High | Low | None (stateless) |
| Event sourcing + CQRS | Very High | High | Minimal |
| Multi-master active-active | Very High | Very High | Conflict resolution |

---

## 5. Rủi ro, Anti-patterns, Lỗi thường gặp

### 5.1 Common Failures

**"We have backups" fallacy**:
```
Công ty X có backup đầy đủ → Ransomware encrypts production
→ Phát hiện backup cũng bị encrypt (shared credentials)
→ Không có air-gapped backup
→ Pay ransom or lose data
```

**Replication lag blindness**:
```
Primary DB crash → Failover to replica
→ Replica 30 phút behind (undetected replication lag)
→ 30 phút giao dịch bị mất
→ Không có cơ chế detect lag trước khi failover
```

**DNS TTL trap**:
```
Failover: Switch DNS từ Region A → Region B
→ DNS TTL = 1 giờ (quên giảm trước)
→ 50% traffic vẫn đến Region A (down) trong 1 giờ
→ RTO thực tế = 1 giờ, không phải 5 phút như kỳ vọng
```

**Orphaned transactions**:
```
Application ghi vào DB Primary
→ Primary crash sau khi commit nhưng trước khi replicate
→ Client nhận success
→ Data mất sau failover
→ "At-least-once" không được đảm bảo
```

### 5.2 Anti-patterns

| Anti-pattern | Tại sao nguy hiểm | Giải pháp |
|--------------|-------------------|-----------|
| Chỉ test DR annually | Skill atrophy, undetected bit-rot | Quarterly automated + annual full test |
| Shared network between regions | Single point of failure | Dedicated interconnect hoặc internet với redundancy |
| Backup encryption key in same region | Key mất cùng data | Multi-region key storage (KMS replica) |
| No runbook hoặc runbook outdated | Chaos trong crisis | Automated runbook, tested mỗi quarter |
| Single person biết cách failover | Bus factor = 1 | Cross-training, automated failover |
| Ignore application-level consistency | DB consistent nhưng app inconsistent | Distributed transaction hoặc sagas |

### 5.3 Edge Cases

**Cascading failures**:
```
Region A failure → Traffic flood vào Region B
→ Region B overload → Region B failure
→ Total system outage (worse than single region failure)

Giải pháp: Circuit breaker + rate limiting trước khi failover
```

**Clock skew**:
```
Timestamp-based conflict resolution
→ Region A và B có clock skew 500ms
→ Writes cùng record ở cả 2 regions → Wrong winner

Giải pháp: Vector clocks hoặc logical timestamps
```

**Data sovereignty**:
```
Failover từ EU region → US region
→ Vi phạm GDPR (data không được rờI EU)

Giải pháp: Geo-restricted failover, chỉ EU↔EU regions
```

---

## 6. Khuyến nghị thực chiến trong Production

### 6.1 Monitoring & Alerting

**Metrics then chốt**:
- `replication_lag_seconds`: Alert if > 30s (RPO monitoring)
- `backup_age_seconds`: Alert if > target RPO
- `dr_drill_success_rate`: Track tỷ lệ thành công DR tests
- `failover_time_seconds`: Measure actual RTO mỗi test

**Health check depth**:
```
Liveness: Process running?
Readiness: Can accept traffic?
Deep health: Database connection, cache, external APIs
DR health: Replication lag, backup freshness, cross-region latency
```

### 6.2 Automation Requirements

**Must automate**:
- Backup verification (restore test)
- Failover execution (sau khi human approval)
- DNS/Traffic management switch
- Health check post-failover
- Rollback preparation

**Should NOT automate** (without extreme maturity):
- Decision to failover (need human verification)
- Corruption detection (complex, error-prone)
- Data reconciliation sau split-brain

### 6.3 Runbook Structure

```
DR Runbook Template:
1. Incident Classification
   - Severity levels và criteria
   - When to activate DR vs in-region recovery

2. Pre-flight Checklist
   - Confirm primary region status
   - Check replication lag
   - Verify backup integrity (nếu cần restore)

3. Failover Steps
   - Step-by-step commands (copy-paste ready)
   - Expected output mỗi step
   - Rollback procedure mỗi step

4. Verification
   - Smoke test commands
   - Key metrics to check
   - Customer-facing validation

5. Communication
   - Stakeholder notification template
   - Status page update
   - Post-incident review scheduling

6. Rollback
   - When to rollback (criteria)
   - Rollback procedure (reverse của failover)
```

### 6.4 Testing Strategy

**Tabletop Exercise** (Monthly):
- Walk through runbook verbally
- Identify gaps, update documentation
- No actual system impact

**Chaos Engineering** (Quarterly):
- Simulate AZ failure: Terminate instances
- Measure actual RTO/RPO
- Validate alerting và runbook

**Full DR Drill** (Annually):
- Complete region failover
- Run production workload trên DR region
- Business validation
- Failback to primary

### 6.5 Cost Optimization

**Tiered approach**:
```
Tier 1 (Critical): Hot standby, active-active, sync replication
Tier 2 (Important): Warm standby, async replication
Tier 3 (Standard): Cold standby, backup-based
Tier 4 (Low): Backup only, manual restore
```

**Reserved Capacity**:
- DR region: Run minimum capacity (t3.micro)
- Scale up on-demand khi failover (auto-scaling groups)
- Pre-warm critical services trước khi redirect traffic

---

## 7. Kết luận

**Bản chất của Disaster Recovery**:

> DR không phải technical problem - đây là **business risk management problem**. Công nghệ chỉ là công cụ để đạt mục tiêu business continuity.

**Những điểm then chốt cần ghi nhớ**:

1. **RTO và RPO là business metrics, không phải technical metrics**. Chúng được xác định bởi "business có thể chịu đựng được gì" chứ không phải "technical có thể làm được gì".

2. **Synchronous replication = Strong consistency + High latency + Geographic constraint**. Không thể có cả 3. Chọn 2.

3. **Backup không có verified restore = Không có backup**. Untested backup là optimistic data deletion.

4. **Automated failover không tested = Automated outage maker**. Automation without testing increases risk.

5. **DR is not set-and-forget**. Architecture changes, data growth, team turnover - tất cả đều làm DR plan lỗi thờI.

**Trade-off summary**:

| Goal | Cost | Complexity | Risk |
|------|------|------------|------|
| RTO=0, RPO=0 | 2x+ infrastructure | Very High | Split-brain |
| RTO=minutes, RPO=minutes | 1.5x infrastructure | Medium | Replication lag |
| RTO=hours, RPO=hours | 1.1x infrastructure | Low | Extended outage |

**Cuối cùng**: Perfect DR không tồn tại. Mục tiêu là **acceptable risk với acceptable cost**.

---

## 8. Tài liệu tham khảo

- AWS Well-Architected Framework - Reliability Pillar
- Google SRE Book - Chapter: Managing Load
- Azure Resilience Best Practices
- ISO 22301 Business Continuity Management
- NIST SP 800-34 Contingency Planning Guide
