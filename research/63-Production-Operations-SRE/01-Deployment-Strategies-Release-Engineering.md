# Deployment Strategies & Release Engineering

## 1. Mục tiêu của Task

Hiểu sâu các chiến lược triển khai (deployment strategies) trong môi trường production, cách quản lý database migration không gián đoạn, và tối ưu hóa CI/CD pipeline. Tập trung vào:
- **Zero-downtime deployment** - đảm bảo tính liên tục của dịch vụ
- **Risk mitigation** - giảm thiểu blast radius khi release lỗi
- **Database compatibility** - migration schema không phá vỡ phiên bản cũ
- **Pipeline efficiency** - tối ưu thởi gian build và deploy

---

## 2. Bản chất và Cơ chế Hoạt động

### 2.1 Blue-Green Deployment

#### Bản chất cơ chế

Blue-Green deployment duy trì **hai production environments hoàn chỉnh** (Blue = active, Green = standby). Traffic được chuyển hoàn toàn từ Blue sang Green trong một thao tác atomic.

```
┌─────────────────────────────────────────────────────────────────┐
│                     BLUE-GREEN ARCHITECTURE                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   ┌─────────────┐         ┌─────────────┐                       │
│   │   Load      │◄────────┤   Load      │                       │
│   │   Balancer  │         │   Balancer  │                       │
│   │   (Active)  │         │   (Standby) │                       │
│   └──────┬──────┘         └──────┬──────┘                       │
│          │                       │                              │
│          ▼                       ▼                              │
│   ┌─────────────┐         ┌─────────────┐                       │
│   │  BLUE ENV   │         │ GREEN ENV   │                       │
│   │  (v1.0.0)   │         │  (v1.1.0)   │                       │
│   │             │         │  [TESTING]  │                       │
│   │  ◄──────────┼─────────┼── Traffic   │                       │
│   │   ACTIVE    │         │   STANDBY   │                       │
│   └─────────────┘         └─────────────┘                       │
│          ▲                       │                              │
│          └───────────────────────┘                              │
│              (Switchover - Instant)                             │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### Cơ chế switchover

Traffic switch trong Blue-Green không phải là "chuyển dần" mà là **instant flip** ở tầng Load Balancer:

1. **DNS-level**: Thay đổi DNS record (slow, có TTL cache)
2. **Load Balancer-level**: Cập nhật target group (fast, ~seconds)
3. **Service Mesh-level**: Traffic split via sidecar proxy (instant)
4. **API Gateway-level**: Route rewrite (instant)

> **Lưu ý quan trọng**: Database và shared resources (Redis, message queue) phải **backward compatible** giữa Blue và Green. Schema changes không được phá vỡ version cũ.

#### Trade-offs

| Aspect | Blue | Green | Impact |
|--------|------|-------|--------|
| **Infrastructure Cost** | 1x | 2x | Green doubles infrastructure cost |
| **Switchover Time** | Gradual | Instant | Blue instant rollback capability |
| **Risk Exposure** | Distributed | Concentrated | Green has all-or-nothing risk |
| **Testing Surface** | Limited | Full | Green tests against real production data |
| **Data Consistency** | Complex | Simple | Blue needs data sync mechanisms |

### 2.2 Canary Deployment

#### Bản chất cơ chế

Canary deployment **gradually shifts traffic** từ old version sang new version dựa trên các metrics và health checks. Cơ chế này dựa trên quan sát thực tế (real user traffic) để quyết định rollout hoặc rollback.

```
┌─────────────────────────────────────────────────────────────────┐
│                    CANARY DEPLOYMENT FLOW                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Phase 1: 0% → 5%                                               │
│  ┌─────────────┐                                                │
│  │   Load      │──┬──────────────────┐                          │
│  │   Balancer  │  │  95% → Stable    │                          │
│  │   (Traffic  │  ▼                  │                          │
│  │   Router)   │ ┌──────────┐        │                          │
│  └─────────────┘ │  v1.0.0  │        │                          │
│                  │ (Stable) │        │                          │
│                  └──────────┘        │                          │
│                       ▲              │                          │
│                       │  5% → Canary │                          │
│                       │              ▼                          │
│                  ┌──────────┐  ┌──────────┐                    │
│                  │ Rollback │  │  v1.1.0  │                    │
│                  │  Path    │  │ (Canary) │                    │
│                  └──────────┘  └──────────┘                    │
│                                                                  │
│  Phase 2: 5% → 25% → 50% → 100% (nếu metrics OK)               │
│                                                                  │
│  Key Metrics: Error rate, Latency p99, CPU/Memory, Business KPI │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### Cơ chế traffic splitting

**Layer 4 (Transport Layer) - TCP/UDP:**
- AWS ALB/NLB weighted target groups
- Nginx/HAProxy weighted upstream
- Kubernetes Ingress traffic splitting

**Layer 7 (Application Layer) - HTTP:**
- Header-based routing (X-Canary: true)
- Cookie-based sticky canary
- User segment routing (user_id % 100 < 5)
- Geographic routing

**Service Mesh (Layer 7+ with observability):**
- Istio VirtualService weight-based routing
- Linkerd traffic split
- Automatic rollback dựa trên success rate

> **Sự khác biệt cốt lõi**: Canary = **risk-based progressive rollout**, Blue-Green = **instant switch with full rollback capability**

#### Canary Analysis - Cơ chế tự động đánh giá

```
┌─────────────────────────────────────────────────────────────┐
│              AUTOMATED CANARY ANALYSIS                       │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Metrics Collection (30s-5m window)                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Error      │  │   Latency    │  │   Custom     │      │
│  │   Rate       │  │   p99/p95    │  │   KPIs       │      │
│  │   < 0.1%     │  │   < baseline │  │   (revenue)  │      │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘      │
│         │                 │                 │               │
│         └─────────────────┼─────────────────┘               │
│                           ▼                                 │
│                  ┌────────────────┐                         │
│                  │  Canary Judge  │                         │
│                  │  (Spinnaker/   │                         │
│                  │   Flagger)     │                         │
│                  └────────┬───────┘                         │
│                           │                                 │
│              ┌────────────┼────────────┐                    │
│              ▼            ▼            ▼                    │
│        ┌─────────┐  ┌─────────┐  ┌─────────┐               │
│        │ PROMOTE │  │  HOLD   │  │ ROLLBACK│               │
│        │ (+25%)  │  │ (monitor)│  │ (100%)  │               │
│        └─────────┘  └─────────┘  └─────────┘               │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

**Automated Canary Tools:**
- **Spinnaker Kayenta**: Statistical analysis so sánh canary vs baseline
- **Flagger (FluxCD)**: Progressive delivery với Prometheus metrics
- **Argo Rollouts**: Kubernetes-native canary with automated promotion

#### Canary Best Practices

1. **Canary Duration**: 15-60 minutes mỗi phase để detect issues
2. **Metrics Baseline**: Thu thập baseline từ stable version trước khi compare
3. **Rollback Speed**: Automated rollback phải trigger trong < 2 phút
4. **Canary Size**: Bắt đầu với internal users → 1% → 5% → 25% → 50% → 100%
5. **Business Hours**: Canary trong giờ làm việc để team response kịp thời

### 2.3 Feature Flags (Feature Toggles)

#### Bản chất kiến trúc

Feature flags là **conditional logic trong code** cho phép enable/disable features mà không cần redeploy. Đây là **decoupling mechanism** giữa code deployment và feature release.

```
┌─────────────────────────────────────────────────────────────────┐
│                 FEATURE FLAG ARCHITECTURE                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    Application Code                      │   │
│  │  ┌──────────────────────────────────────────────────┐   │   │
│  │  │  if (featureFlags.isEnabled("new-checkout-flow")) │   │   │
│  │  │      newCheckoutService.process(order);          │   │   │
│  │  │  else                                            │   │   │
│  │  │      oldCheckoutService.process(order);          │   │   │
│  │  └──────────────────────────────────────────────────┘   │   │
│  └───────────────────────┬─────────────────────────────────┘   │
│                          │                                       │
│                          ▼                                       │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              Feature Flag SDK (in-app)                   │   │
│  │  - Local evaluation (in-memory rules)                   │   │
│  │  - Async config updates                                 │   │
│  │  - Fallback strategies (default: off)                   │   │
│  └───────────────────────┬─────────────────────────────────┘   │
│                          │                                       │
│              ┌───────────┴───────────┐                          │
│              ▼                       ▼                          │
│  ┌─────────────────┐     ┌─────────────────┐                   │
│  │   Flag Server   │     │   Local Cache   │                   │
│  │  (LaunchDarkly/ │     │   (TTL: 30s)    │                   │
│  │   Unleash)      │     │                 │                   │
│  │                 │     │  Rules:         │                   │
│  │  Rule Engine:   │◄────┤  - user_id % 10 │                   │
│  │  - Segments     │     │  - region=US    │                   │
│  │  - Percentages  │     │  - beta_users   │                   │
│  │  - Targeting    │     │                 │                   │
│  └─────────────────┘     └─────────────────┘                   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### Phân loại Feature Flags

| Type | Purpose | Lifespan | Example |
|------|---------|----------|---------|
| **Release Toggle** | Hide incomplete features | Days-Weeks | New UI hidden until ready |
| **Experiment Toggle** | A/B testing | Weeks-Months | Test checkout flow variants |
| **Ops Toggle** | Circuit breaker/operational | Permanent | Disable heavy feature during peak |
| **Permission Toggle** | User entitlement | Permanent | Premium features for paid users |
| **Kill Switch** | Emergency disable | Permanent | Turn off feature causing issues |

> **Nguyên tắc**: Release toggles phải có **technical debt plan** - remove sau khi feature stable. Permanent toggles (permission, kill switch) cần documentation và monitoring.

#### Cơ chế evaluation

**Server-side Evaluation (recommended for backend):**
- Rules evaluated trong application process
- Latency: < 1ms (in-memory)
- Không phụ thuộc external call trong critical path
- Config sync qua WebSocket/SSE/polling

**Client-side Evaluation:**
- SDK nhận full ruleset (có thể expose sensitive logic)
- Phù hợp mobile/web apps
- Cần obfuscation cho sensitive rules

**Targeting Rules (evaluation order):**
1. **Individual targeting**: Specific user IDs
2. **Segment membership**: Beta users, employees
3. **Custom attributes**: `region=US AND subscription=premium`
4. **Percentage rollout**: Gradual percentage increase
5. **Default value**: Fallback nếu không match rule nào

#### Feature Flag Anti-patterns

> **❌ Dead Flags**: Toggles không bao giờ removed, clutter codebase
> **❌ Nested Flags**: `flagA && flagB && flagC` tạo combinatorial explosion
> **❌ Flag in Data Model**: Persisting flag state vào database
> **❌ No Observability**: Không biết feature nào đang active ở đâu
> **❌ Cross-service Dependencies**: Service A depend on flag state của Service B

### 2.4 Database Migration Strategies

#### Bản chất vấn đề

Database migration trong zero-downtime deployment đòi hỏi **backward compatibility** giữa:
- **Old application code** (v1) đọc/write schema cũ
- **New application code** (v2) đọc/write schema mới
- **Overlapping period**: Cả hai version chạy song song

```
┌─────────────────────────────────────────────────────────────────┐
│           ZERO-DOWNTIME DATABASE MIGRATION PATTERN               │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────┐     ┌──────────┐     ┌──────────┐                 │
│  │  Step 1  │────►│  Step 2  │────►│  Step 3  │                 │
│  │  Expand  │     │  Migrate │     │  Contract│                 │
│  └──────────┘     └──────────┘     └──────────┘                 │
│                                                                  │
│  Database State Evolution:                                       │
│                                                                  │
│  Step 1 (Expand) - Add new column/table (nullable/default)      │
│  ┌──────────────────────────────────────┐                       │
│  │  Table: users                        │                       │
│  │  ├── id: PK                          │                       │
│  │  ├── email: VARCHAR(255)             │                       │
│  │  ├── name: VARCHAR(100)              │                       │
│  │  └── phone: VARCHAR(20)  ◄── NEW     │                       │
│  │      (nullable, no index yet)        │                       │
│  └──────────────────────────────────────┘                       │
│       ▲                                                          │
│       │  v1 code: INSERT chỉ có email, name                       │
│       │  v2 code: INSERT có thêm phone (nullable)                 │
│       │                                                          │
│       Both versions compatible - v1 ignores new column           │
│                                                                  │
│  Step 2 (Migrate) - Backfill data, add constraints gradually     │
│  ┌──────────────────────────────────────┐                       │
│  │  Table: users                        │                       │
│  │  ├── id: PK                          │                       │
│  │  ├── email: VARCHAR(255)             │                       │
│  │  ├── name: VARCHAR(100)              │                       │
│  │  └── phone: VARCHAR(20) NOT NULL ◄── │                       │
│  │      (backfilled, has default)       │                       │
│  └──────────────────────────────────────┘                       │
│       ▲                                                          │
│       │  v2 code: Read/write phone column                         │
│       │  v1 code: Still works (doesn't touch phone)               │
│       │                                                          │
│                                                                  │
│  Step 3 (Contract) - Remove old columns when v1 retired          │
│  ┌──────────────────────────────────────┐                       │
│  │  Table: users                        │                       │
│  │  ├── id: PK                          │                       │
│  │  ├── email: VARCHAR(255)             │                       │
│  │  └── phone: VARCHAR(20) NOT NULL     │                       │
│  │      (name column REMOVED)           │                       │
│  └──────────────────────────────────────┘                       │
│                                                                  │
│  Pattern: EXPAND → MIGRATE → CONTRACT (Strangler Fig)            │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### Expand-Contract Pattern chi tiết

**PHASE 1: EXPAND (Deploy v2 code + DB migration)**
```sql
-- Migration: Add new column (nullable or with default)
ALTER TABLE users ADD COLUMN phone VARCHAR(20) NULL;

-- Add index CONCURRENTLY (PostgreSQL) or ONLINE (MySQL)
-- Không lock table trong quá trình tạo index
CREATE INDEX CONCURRENTLY idx_users_phone ON users(phone);
```

> **Lưu ý**: `CONCURRENTLY` (PostgreSQL) hoặc `ALGORITHM=INPLACE` (MySQL) tránh table lock

**PHASE 2: DUAL WRITE (v2 writes to both old and new)**
```java
// v2 code: Write to both columns during transition
public void updateUser(User user) {
    // Write to old column (for v1 compatibility)
    user.setEmail(user.getEmail());
    
    // Write to new column
    user.setPhone(user.getPhone());
    
    userRepository.save(user);
}
```

**PHASE 3: BACKFILL (Populate new column cho existing rows)**
```sql
-- Batch update với limit để tránh long transaction
UPDATE users 
SET phone = 'N/A' 
WHERE phone IS NULL 
AND id > :last_processed_id
LIMIT 1000;
```

**PHASE 4: VALIDATE & CONSTRAINT**
```sql
-- Sau khi all rows populated
ALTER TABLE users ALTER COLUMN phone SET NOT NULL;
```

**PHASE 5: CONTRACT (Remove old column khi v1 retired)**
```sql
-- Only after v1 completely decommissioned
ALTER TABLE users DROP COLUMN email;
```

#### Breaking Changes Handling

| Change Type | Zero-Downtime Approach | Risk Level |
|-------------|----------------------|------------|
| **Add column** | Add as nullable/with default | Low |
| **Add index** | `CONCURRENTLY` / `ONLINE` | Low |
| **Add constraint** | Add as `NOT VALID` then validate | Medium |
| **Rename column** | Add new → dual write → drop old | Medium |
| **Drop column** | 3-phase: ignore → stop using → drop | High |
| **Change data type** | Add new column → migrate → drop old | High |
| **Primary key change** | New table + data migration | Very High |
| **Sharding change** | Application-level routing layer | Very High |

#### Migration Tools - Flyway vs Liquibase

**Flyway - Convention over Configuration:**
```
migrations/
├── V1__Initial_schema.sql
├── V2__Add_users_table.sql
├── V3__Add_phone_column.sql
└── R__Repeatable_migration.sql  (re-run khi checksum change)
```

- **Versioned migrations**: `V{version}__{description}.sql` - run once, ordered
- **Repeatable migrations**: `R__{description}.sql` - run khi checksum change
- **Java-based migrations**: Cho complex logic
- **Baseline**: Bắt đầu từ existing database

**Liquibase - XML/YAML/JSON DSL:**
```xml
<changeSet id="3" author="developer">
    <addColumn tableName="users">
        <column name="phone" type="varchar(20)">
            <constraints nullable="true"/>
        </column>
    </addColumn>
    <rollback>
        <dropColumn tableName="users" columnName="phone"/>
    </rollback>
</changeSet>
```

- **ChangeLog**: Master file references individual changeSets
- **Rollback support**: Tự động rollback definitions
- **Database abstraction**: Same changeset cho multiple DB types
- **Preconditions**: Conditional migration execution

**Tool Comparison:**

| Aspect | Flyway | Liquibase |
|--------|--------|-----------|
| **Learning Curve** | Low (SQL native) | Medium (DSL) |
| **Rollback** | Manual (undo scripts) | Built-in |
| **Multi-DB Support** | SQL per DB | Abstraction layer |
| **Version Control** | File naming | Database changelog table |
| **Team Size** | Small-Medium | Enterprise |
| **Complex Logic** | Java migrations | Groovy/Custom |

> **Khuyến nghị**: Flyway cho teams ưa SQL và simple workflows. Liquibase cho enterprise với complex rollback requirements và multi-DB environments.

### 2.5 CI/CD Pipeline Optimization

#### Pipeline Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                  OPTIMIZED CI/CD PIPELINE                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐         │
│  │  Code   │──►│  Build  │──►│  Test   │──►│  Scan   │         │
│  │  Push   │   │  & Unit │   │  & Int  │   │  & Sec  │         │
│  └─────────┘   └────┬────┘   └────┬────┘   └────┬────┘         │
│                     │             │             │               │
│              Parallel Jobs:  │      │             │               │
│              ├─ Compile      │      │             │               │
│              ├─ Unit Tests    │      │             │               │
│              └─ Static Analysis│     │             │               │
│                     │             │             │               │
│                     ▼             ▼             ▼               │
│              ┌─────────────────────────────────────────┐       │
│              │      ARTIFACT REGISTRY (immutable)       │       │
│              │  myapp:1.2.3-sha8f3d2a (image + jar)     │       │
│              └─────────────────────────────────────────┘       │
│                              │                                   │
│              ┌───────────────┼───────────────┐                   │
│              ▼               ▼               ▼                   │
│        ┌─────────┐    ┌─────────┐    ┌─────────┐               │
│        │   Dev   │    │  Staging│    │  Prod   │               │
│        │ Deploy  │    │ Deploy  │    │ Deploy  │               │
│        │ (auto)  │    │ (manual)│    │(canary) │               │
│        └─────────┘    └─────────┘    └─────────┘               │
│                                                                  │
│  Artifacts: Immutable, tagged with git SHA + semantic version   │
│  Promotion: Same artifact qua các environments (không rebuild)  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### Build Optimization Strategies

**1. Layer Caching (Docker builds):**
```dockerfile
# Layer 1: Dependencies (rarely changes)
COPY pom.xml .
RUN mvn dependency:go-offline

# Layer 2: Source code (changes frequently)
COPY src ./src
RUN mvn package -DskipTests
```

**2. Incremental Builds:**
```bash
# Maven - chỉ build changed modules
mvn install -pl :changed-module -am

# Gradle - build cache
./gradlew build --build-cache
```

**3. Parallel Test Execution:**
```xml
<!-- Maven Surefire -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <parallel>methods</parallel>
        <threadCount>4</threadCount>
    </configuration>
</plugin>
```

**4. Test Categorization (Test Pyramid):**

| Test Type | Count | Time | Parallel | Pipeline Stage |
|-----------|-------|------|----------|----------------|
| **Unit Tests** | 1000s | < 2 min | Yes | Pre-commit/Build |
| **Integration Tests** | 100s | 5-10 min | Yes | Post-build |
| **E2E Tests** | 10s | 10-30 min | Limited | Staging only |
| **Contract Tests** | 100s | 2-5 min | Yes | Post-build |

> **Nguyên tắc**: Fail fast - unit tests chạy trước, expensive tests sau. Không chạy full E2E suite trên mỗi commit.

#### Artifact Management

**Immutable Artifacts Principle:**
- Artifact được build **một lần** và promote qua environments
- Không rebuild cho mỗi environment (dev rebuild vs prod rebuild = risk)
- Tagging: `{app}:{semver}-{git-sha}` (e.g., `payment-service:2.3.1-a8f3d2a`)

**Artifact Registry Features:**
- **Retention policies**: Auto-delete artifacts older than X days
- **Vulnerability scanning**: Integrated CVE scanning (Trivy, Snyk)
- **Signature verification**: Cosign/Notary for image signing
- **Replication**: Geo-replicated cho multi-region deployments

---

## 3. So sánh Các Chiến lược Deployment

| Strategy | Downtime | Risk Level | Rollback Speed | Infrastructure Cost | Use Case |
|----------|----------|------------|----------------|-------------------|----------|
| **Recreate** | High (minutes) | High | Slow (redeploy) | 1x | Non-critical dev/test |
| **Rolling** | Zero | Medium | Slow (redeploy) | 1x | Stateful services, gradual rollout |
| **Blue-Green** | Zero | Low | Instant (switch) | 2x | Critical services, instant rollback needed |
| **Canary** | Zero | Very Low | Fast (traffic shift) | 1-1.2x | High-risk releases, gradual validation |
| **A/B Testing** | Zero | Low | Fast | 1-1.2x | Product experiments, user segmentation |
| **Shadow/Mirror** | Zero | Minimal | N/A (no user impact) | 2x | Load testing, security validation |

---

## 4. Rủi ro, Anti-patterns, và Lỗi Thường Gặp

### 4.1 Deployment Anti-patterns

> **❌ Big Bang Deployment**: Deploy tất cả changes một lúc. Risk: Khó debug, khó rollback.
> **✅ Khuyến nghị**: Break thành small, independently deployable units.

> **❌ Environment Drift**: Dev/Staging/Prod khác nhau về config, data, infrastructure.
> **✅ Khuyến nghị**: Infrastructure as Code (Terraform), same container image mọi environment.

> **❌ Database Migration in Application Startup**: App khởi động chạy migration.
> **✅ Khuyến nghị**: Separate migration job/job pre-deploy, không phụ thuộc app startup.

> **❌ Friday Deployments**: Deploy trước weekend khi on-call coverage thấp.
> **✅ Khuyến nghị**: Deploy Tuesday-Thursday, peak business hours.

### 4.2 Feature Flag Technical Debt

**Debt Accumulation:**
- Flags không removed sau release tạo `if/else` spaghetti code
- Nested flags tạo combinatorial explosion (2^n code paths)
- Performance impact: Flag evaluation trong hot path

**Mitigation:**
- Ticket tracking cho flag removal (Jira/GitHub issue per flag)
- Automated flag cleanup detection (static analysis)
- TTL (time-to-live) trên flags - auto-expire sau X days

### 4.3 Database Migration Failures

**Common Failures:**

| Failure | Cause | Prevention |
|---------|-------|------------|
| **Lock timeout** | Long-running migration giữ lock | Use `CONCURRENTLY`, batch updates |
| **Constraint violation** | Data không match new constraint | Validate data trước migration |
| **Disk full** | Large table rebuild | Monitor disk space, online DDL |
| **Replication lag** | Slave không kịp replicate | Wait for replication catch-up |
| **Rollback failure** | Migration irreversible | Test rollback trên staging |

### 4.4 Canary Pitfalls

> **❌ Insufficient Metrics**: Canary chỉ dựa trên error rate, miss latency spike.
> **✅ Khuyến nghị**: Multi-dimensional metrics (errors, latency, throughput, business KPIs).

> **❌ Short Canary Duration**: 5-minute canary miss gradual memory leak.
> **✅ Khuyến nghíh**: Minimum 30-60 minutes, longer cho stateful services.

> **❌ Ignoring Baseline**: So sánh canary với historical average thay vì concurrent baseline.
> **✅ Khuyến nghị**: Compare canary vs stable version chạy song song.

---

## 5. Khuyến nghị Thực chiến trong Production

### 5.1 Deployment Strategy Selection Matrix

| Service Type | Recommended Strategy | Backup Strategy |
|--------------|---------------------|-----------------|
| **Stateless API** | Canary with automated analysis | Blue-Green |
| **Stateful Service** | Rolling with health checks | Blue-Green (data sync) |
| **Database** | Blue-Green (master swap) | Read replica promotion |
| **Mobile Backend** | Feature flags + Canary | Shadow deployment |
| **Critical Payment** | Blue-Green + Manual approval | Feature flags |

### 5.2 Monitoring & Observability

**Deployment Dashboard cần hiển thị:**
```
┌─────────────────────────────────────────────────────────────┐
│                 DEPLOYMENT DASHBOARD                         │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Deployment: payment-service v2.3.1-canary                   │
│  Status:     ████████████████████░░░░░  75% traffic         │
│                                                              │
│  Metrics (Canary vs Baseline):                               │
│  ┌────────────────┬───────────┬───────────┬──────────┐     │
│  │ Metric         │ Canary    │ Baseline  │ Status   │     │
│  ├────────────────┼───────────┼───────────┼──────────┤     │
│  │ Error Rate     │ 0.05%     │ 0.04%     │ ✓ PASS   │     │
│  │ Latency p99    │ 145ms     │ 142ms     │ ✓ PASS   │     │
│  │ Latency p95    │ 89ms      │ 85ms      │ ✓ PASS   │     │
│  │ CPU Usage      │ 45%       │ 42%       │ ✓ PASS   │     │
│  │ Memory         │ 78%       │ 75%       │ ⚠ WARN   │     │
│  │ Checkout $     │ $12.4K    │ $12.1K    │ ✓ PASS   │     │
│  └────────────────┴───────────┴───────────┴──────────┘     │
│                                                              │
│  Actions: [Promote to 100%] [Hold] [Rollback]                │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

**Key Metrics cho Deployment:**
- **RED**: Rate (requests/sec), Errors (4xx/5xx %), Duration (p50/p99 latency)
- **USE**: Utilization (CPU/Memory), Saturation (queue depth), Errors (system-level)
- **Business**: Conversion rate, revenue per minute, user engagement

### 5.3 Rollback Strategy

**Rollback Hierarchy (fastest to slowest):**

1. **Traffic Shift** (Canary/Blue-Green): < 30 seconds
   - Route traffic away from failed version
   - Zero code change, instant

2. **Feature Flag Disable**: < 1 minute
   - Kill switch cho specific feature
   - Code chạy nhưng feature disabled

3. **Container Rollback**: 1-5 minutes
   - Redeploy previous image version
   - Requires old artifact available

4. **Database Rollback**: 10+ minutes (avoid if possible)
   - Restore from backup (data loss risk)
   - Reverse migration (complex, risky)

> **Quy tắc**: Design cho rollback trước khi design cho rollout. Mọi deployment phải có rollback plan tested.

### 5.4 Secrets & Configuration Management

**Environment Separation:**
```
┌─────────────────────────────────────────────────────────────┐
│              CONFIGURATION LAYERS                            │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Application Config (git-managed)                           │
│  ├── application.yml          # Default config               │
│  ├── application-dev.yml      # Development overrides        │
│  └── application-prod.yml     # Production overrides         │
│                                                              │
│  Environment Secrets (Vault/AWS Secrets Manager)            │
│  ├── Database credentials     # Rotated, encrypted           │
│  ├── API keys                 # Scoped per environment       │
│  └── TLS certificates         # Auto-renewal                 │
│                                                              │
│  Runtime Config (Feature Flags)                             │
│  ├── Feature toggles          # LaunchDarkly/Unleash         │
│  └── Circuit breaker thresholds # Dynamic tuning             │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

**Secret Injection Patterns:**
- **Init containers**: Fetch secrets trước app start
- **Sidecar agents**: HashiCorp Vault agent auto-rotate
- **CSI drivers**: Kubernetes Secrets Store CSI driver
- **Environment variables**: Simple nhưng visible trong `ps`

### 5.5 Java-Specific Considerations

**JVM Warmup trong Deployment:**
- JIT compilation cần thời gian để đạt optimal performance
- Cấu trúc canary cần account cho warm-up period (5-10 minutes)
- Consider AppCDS (Application Class Data Sharing) để reduce startup time

**Graceful Shutdown:**
```java
// Kubernetes preStop hook + SIGTERM handling
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    // 1. Stop accepting new requests
    server.stopAccepting();
    
    // 2. Wait for in-flight requests (terminationGracePeriodSeconds)
    server.awaitRequestsComplete(Duration.ofSeconds(30));
    
    // 3. Close resources
    connectionPool.close();
    executorService.shutdown();
}));
```

> **Kubernetes**: `terminationGracePeriodSeconds` mặc định 30s, có thể cần increase cho long-running requests.

---

## 6. Kết luận

### Bản chất cốt lõi

**Deployment không chỉ là "đưa code lên production"** - đó là quá trình quản lý rủi ro, đảm bảo tính liên tục của dịch vụ, và tối ưu feedback loop giữa development và users.

Các chiến lược deployment (Blue-Green, Canary, Feature Flags) là **risk mitigation tools** - mỗi công cụ phù hợp với risk profile và constraints khác nhau:

- **Blue-Green**: Dành cho situations đòi hỏi instant rollback, cost là infrastructure duplication
- **Canary**: Dành cho gradual risk exposure, cost là complexity trong monitoring và automation
- **Feature Flags**: Dành cho decoupling deployment và release, cost là technical debt nếu không manage properly

### Database Migration - The Hard Problem

Database là **hardest part** của zero-downtime deployment. Expand-Contract pattern đòi hỏi discipline và planning, nhưng là cách duy nhất đảm bảo backward compatibility giữa multiple code versions.

### Trade-off Summary

| Decision | Trade-off |
|----------|-----------|
| Fast rollback vs Low cost | Blue-Green (fast) costs 2x infrastructure |
| Feature velocity vs Stability | Feature flags increase velocity nhưng add complexity |
| Automation vs Control | Automated canary nhanh nhưng có thể miss subtle issues |
| Database migration speed vs Safety | Fast migration = risk of lock, slow = extended dual-write period |

### Production Checklist

- [ ] Rollback plan tested trên staging
- [ ] Database migrations chạy trước app deployment
- [ ] Feature flags có removal timeline
- [ ] Monitoring dashboard ready cho canary analysis
- [ ] Secrets rotated và không hardcoded
- [ ] Graceful shutdown handling implemented
- [ ] Post-deployment validation automated
- [ ] On-call team notified và runbook accessible

---

## References

- "Continuous Delivery" - Jez Humble, David Farley
- "Database Migrations Done Right" - principled approach to schema changes
- "Feature Toggles" - Martin Fowler, Pete Hodgson
- Kubernetes Deployment Strategies documentation
- Flyway & Liquibase best practices guides
