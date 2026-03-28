# Secrets Management - HashiCorp Vault, AWS Secrets Manager Rotation

## 1. Mục tiêu của Task

Hiểu sâu bản chất Secrets Management: tại sao hardcode secrets là anti-pattern nghiêm trọng, cơ chế bảo mật và rotation của HashiCorp Vault và AWS Secrets Manager hoạt động như thế nào, và các trade-off giữa self-hosted vs managed solutions trong production.

---

## 2. Bản chất và Cơ chế Hoạt động

### 2.1. Vấn đề của Hardcoded Secrets

**Anti-pattern phổ biến:**
```yaml
# ❌ application.yml - Secrets lộ thẳng
spring:
  datasource:
    url: jdbc:postgresql://db:5432/mydb
    username: admin
    password: "SuperSecret123!"  # Lộ trong Git, logs, environment
```

**Hậu quả thực tế:**
- **Git history contamination**: Secret committed một lần = mãi mãi trong history
- **Log leakage**: Frameworks có thể log toàn bộ config object khi startup
- **Environment variable exposure**: `ps aux`, `/proc/[pid]/environ` readable bởi root
- **Secret sprawl**: Cùng một secret copy khắp nơi → rotation trở thành ác mộng
- **Blast radius**: Một secret bị leak ảnh hưởng toàn bộ hệ thống

### 2.2. Cơ chế Bảo mật của Secrets Management

**Core Principle: Secret không bao giờ persistent ở client**

```
┌─────────────────────────────────────────────────────────────────┐
│                        SECRETS MANAGER                           │
│                    (Vault / AWS Secrets Manager)                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │   Storage    │  │   Encryption │  │   Access Control     │  │
│  │  (Encrypted) │  │   (Auto-unseal│  │  (ACL/Policies/RBAC) │  │
│  └──────────────┘  └──────────────┘  └──────────────────────┘  │
│           ▲                                                      │
│           │ Request secret (with auth token)                     │
│           ▼                                                      │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                     APPLICATION                           │   │
│  │  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐  │   │
│  │  │Short-lived  │    │ Use secret  │    │  Discard    │  │   │
│  │  │credential   │───►│  in-memory  │───►│   secret    │  │   │
│  │  │(TTL-based)  │    │  (ephemeral)│    │  on exit    │  │   │
│  │  └─────────────┘    └─────────────┘    └─────────────┘  │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

**Key properties:**
- **Ephemeral access**: Secret chỉ tồn tại trong bộ nhớ, không persistent
- **TTL enforcement**: Token hết hạn → secret không thể retrieve được nữa
- **Audit logging**: Mọi access đều được log (who, what, when)
- **Dynamic secrets**: Mỗi instance nhận unique credentials

### 2.3. HashiCorp Vault Architecture Deep Dive

**Vault Components:**

```
┌──────────────────────────────────────────────────────────────────┐
│                      VAULT SERVER                                │
│                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐   │
│  │   HTTP API   │  │    Core      │  │    Secret Engines    │   │
│  │   Layer      │◄─┤   (Router)   │◄─┤  - KV v1/v2          │   │
│  └──────────────┘  └──────────────┘  │  - Database           │   │
│           │                          │  - PKI                │   │
│           │                          │  - AWS/Azure/GCP      │   │
│           ▼                          │  - TOTP/SSH/Transit   │   │
│  ┌──────────────┐                    └──────────────────────┘   │
│  │   Auth       │                                               │
│  │   Methods    │  ┌──────────────┐  ┌──────────────────────┐   │
│  │ - Token      │  │   Barrier    │  │    Storage Backend   │   │
│  │ - Kubernetes │◄─┤  (Encryption)│◄─┤  - Consul            │   │
│  │ - AppRole    │  │              │  │  - Raft (integrated) │   │
│  │ - AWS/IAM    │  │ - Unseal     │  │  - S3/GCS/Azure      │   │
│  │ - OIDC/LDAP  │  │ - Master key │  │  - PostgreSQL/MySQL  │   │
│  └──────────────┘  └──────────────┘  └──────────────────────┘   │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

**Unseal Mechanism - Bảo vệ Master Key:**

```
Master Key (256-bit AES)
    │
    ├──► Shamir's Secret Sharing ──► N shards, K required to reconstruct
    │
    └──► Auto-unseal (AWS KMS/Azure Key Vault/GCP CKM) ──► Cloud HSM
```

**Bản chất Unseal:**
- Vault khởi động ở **sealed state** - không thể decrypt storage
- Master key được split theo Shamir's Secret Sharing (m-of-n)
- Hoặc dùng Auto-unseal với cloud KMS (HSM-backed)
- **Trade-off**: Auto-unseal tiện lợi nhưng tạo external dependency

**Secret Engines - Pluggable Backends:**

| Engine Type | Use Case | Dynamic? | TTL Control |
|-------------|----------|----------|-------------|
| KV v1 | Static config | No | No |
| KV v2 | Versioned config | No | No |
| Database | DB credentials | Yes | Yes |
| PKI | X.509 certificates | Yes | Yes |
| AWS | IAM credentials | Yes | Yes |
| Azure | Service Principal | Yes | Yes |
| Transit | Encryption-as-a-Service | N/A | Key rotation |

**Dynamic Secrets - Cơ chế tạo credentials on-the-fly:**

```
┌─────────────┐     Request DB credentials     ┌─────────────┐
│ Application │ ─────────────────────────────► │    Vault    │
│             │     (with valid token)         │             │
│             │◄────────────────────────────── │   Database  │
│             │     {username, password, TTL}  │   Engine    │
└──────┬──────┘                                 └──────┬──────┘
       │                                              │
       │ Connect to PostgreSQL                        │ CREATE ROLE
       │ username: v-token-app-xxx                    │ WITH LOGIN
       │ password: [random 32-char]                   │ PASSWORD '...'
       │                                              │ VALID UNTIL 'TTL'
       ▼                                              ▼
┌─────────────┐                                 ┌─────────────┐
│ PostgreSQL  │                                 │   Revocation  │
│             │◄──── Auto-revoke on TTL expiry ─┤    Queue    │
└─────────────┘                                 └─────────────┘
```

**Cơ chế tạo dynamic secret:**
1. App gửi request kèm Vault token
2. Vault engine tạo unique credentials trong target system
3. Trả về cho app kèm TTL
4. Vault tự động revoke khi TTL hết hạn
5. App có thể renew nếu cần extend

### 2.4. AWS Secrets Manager Architecture

**AWS-native Integration:**

```
┌──────────────────────────────────────────────────────────────────┐
│                    AWS SECRETS MANAGER                           │
│                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐   │
│  │   Secret     │  │   Rotation   │  │   Replication        │   │
│  │   Metadata   │  │   Lambda     │  │   (Multi-region)     │   │
│  └──────────────┘  └──────────────┘  └──────────────────────┘   │
│         │                  │                    │               │
│         ▼                  ▼                    ▼               │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              AWS KMS (AES-256-GCM)                       │   │
│  │         Envelope encryption for secret value             │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

**Envelope Encryption:**
```
Plaintext Secret
       │
       ├──► Data Key (DEK) ──► Encrypt secret ──► Ciphertext
       │                           │
       │                           ▼
       │                    KMS Key (KEK) ──► Encrypt DEK
       │                           │
       ▼                           ▼
Stored: Ciphertext + Encrypted DEK (both in Secrets Manager)
```

**Automatic Rotation Architecture:**

```
┌─────────────────────────────────────────────────────────────────────┐
│                     ROTATION WORKFLOW                               │
│                                                                     │
│   ┌──────────┐                                                      │
│   │ Secret   │──► Rotation enabled                                  │
│   │ Version  │    Rotation schedule (days)                          │
│   │ AWSCURRENT│    Rotation Lambda function                          │
│   └────┬─────┘                                                      │
│        │                                                            │
│        ▼                                                            │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  Phase 1: Create AWSPENDING version with new credentials     │  │
│   │  Phase 2: Update target service (database, API key, etc.)    │  │
│   │  Phase 3: Test connection with new credentials               │  │
│   │  Phase 4: Move AWSPENDING → AWSCURRENT                       │  │
│   │  Phase 5: Archive old version (AWSPREVIOUS)                  │  │
│   └──────────────────────────────────────────────────────────────┘  │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**Rotation Lambda Execution Flow:**
```python
# Pseudo-code for rotation Lambda
def lambda_handler(event, context):
    secret_id = event['SecretId']
    token = event['ClientRequestToken']  # New version identifier
    step = event['Step']  # createSecret/setSecret/testSecret/finishSecret
    
    if step == 'createSecret':
        # Generate new credentials
        new_password = generate_random_password()
        put_secret_value(secret_id, token, new_password, 'AWSPENDING')
    
    elif step == 'setSecret':
        # Update database with new credentials
        # IMPORTANT: Must support both old and new during transition
        pending_secret = get_secret(secret_id, 'AWSPENDING')
        update_database_password(pending_secret['username'], pending_secret['password'])
    
    elif step == 'testSecret':
        # Verify new credentials work
        pending_secret = get_secret(secret_id, 'AWSPENDING')
        test_connection(pending_secret)
    
    elif step == 'finishSecret':
        # Promote AWSPENDING to AWSCURRENT
        update_secret_version_stage(secret_id, token, 'AWSCURRENT')
```

---

## 3. Kiến trúc / Luồng xử lý

### 3.1. Application Integration Patterns

**Pattern 1: Sidecar/Init Container (Kubernetes)**
```yaml
apiVersion: v1
kind: Pod
spec:
  initContainers:
    - name: vault-agent
      image: hashicorp/vault:1.15
      args:
        - agent
        - -config=/etc/vault/config.hcl
      volumeMounts:
        - name: vault-config
          mountPath: /etc/vault
        - name: shared-data
          mountPath: /vault/secrets
      # Vault agent authenticates và ghi secrets ra file
  containers:
    - name: app
      image: myapp:latest
      volumeMounts:
        - name: shared-data
          mountPath: /secrets  # Đọc secrets từ đây
          readOnly: true
```

**Vault Agent Config:**
```hcl
auto_auth {
  method "kubernetes" {
    config = {
      role = "my-app"
    }
  }
  sink "file" {
    config = {
      path = "/vault/.vault-token"
    }
  }
}

template {
  destination = "/vault/secrets/config.properties"
  contents = <<EOT
{{ with secret "database/creds/my-app" }}
spring.datasource.username={{ .Data.username }}
spring.datasource.password={{ .Data.password }}
{{ end }}
EOT
}
```

**Pattern 2: Runtime SDK Integration**
```java
// Java Spring Boot với Vault
@Configuration
@VaultPropertySource("secret/my-app")
public class VaultConfig {
    
    @Value("${database.username}")
    private String dbUsername;
    
    @Value("${database.password}")
    private String dbPassword;
    
    // Secrets được inject, không hardcode
}
```

**Pattern 3: AWS Secrets Manager với SDK**
```java
// Java AWS SDK v2
SecretsManagerClient client = SecretsManagerClient.create();

GetSecretValueRequest request = GetSecretValueRequest.builder()
    .secretId("prod/myapp/database")
    .build();

GetSecretValueResponse response = client.getSecretValue(request);
String secretString = response.secretString();
// Parse JSON, extract credentials
```

**Pattern 4: Lambda Extension (AWS)**
```python
# Secrets được cache trong Lambda extension
# Không cần gọi API mỗi invocation
import json
import os
from urllib.request import urlopen

def get_secret():
    # Gọi local extension endpoint
    secret_name = os.environ['SECRET_ARN']
    url = f"http://localhost:2773/secretsmanager/get?secretId={secret_name}"
    headers = {"X-Aws-Parameters-Secrets-Token": os.environ['AWS_SESSION_TOKEN']}
    
    response = urlopen(url, headers=headers)
    return json.loads(response.read())
```

### 3.2. Rotation Strategies Comparison

| Strategy | Implementation | Use Case | Pros | Cons |
|----------|---------------|----------|------|------|
| **Single-user rotation** | AWS managed Lambda | RDS, DocumentDB | Đơn giản, native support | Downtime ngắn trong rotation |
| **Multi-user rotation** | Custom Lambda | High-availability DB | Zero-downtime rotation | Complex, cần 2 users |
| **Dynamic secrets** | Vault Database engine | Microservices | Mỗi instance unique creds | Vault dependency |
| **Client-side caching** | SDK with TTL | Read-heavy apps | Giảm API calls | Stale secret risk |
| **Sidecar pattern** | Vault Agent/Kubernetes | Containerized apps | Transparent to app | Extra resource usage |

---

## 4. So sánh các Lựa chọn

### 4.1. HashiCorp Vault vs AWS Secrets Manager

| Criteria | Hashiorp Vault | AWS Secrets Manager |
|----------|----------------|---------------------|
| **Deployment** | Self-hosted or HCP Vault | Fully managed AWS service |
| **Multi-cloud** | ✅ Yes (AWS/Azure/GCP) | ❌ AWS only |
| **Dynamic secrets** | ✅ Native (database, cloud) | ⚠️ Limited (RDS native) |
| **Secret engines** | ✅ 20+ engines (PKI, SSH, etc.) | ❌ Key-value only |
| **Rotation** | ✅ Flexible (custom logic) | ✅ Native Lambda integration |
| **HSM support** | ✅ PKCS#11, cloud HSM | ✅ AWS CloudHSM |
| **Audit logging** | ✅ Detailed audit logs | ✅ CloudTrail integration |
| **Pricing** | License + infra cost | Pay per secret + API call |
| **Operational burden** | High (cluster management) | Low (fully managed) |
| **Scaling** | Manual/Auto-scaling config | Automatic |
| **Secret replication** | Performance/DR replicas | Multi-region replication |

### 4.2. When to choose what?

**Choose HashiCorp Vault when:**
- Multi-cloud hoặc hybrid cloud environment
- Cần dynamic secrets cho nhiều loại resources (DB, cloud, PKI)
- Encryption-as-a-Service (Transit engine)
- Complex access policies (Sentinel, ACLs)
- PKI certificate management
- SSH certificate signing (signed SSH)

**Choose AWS Secrets Manager when:**
- Pure AWS environment
- RDS/MemoryDB/DocumentDB rotation là priority
- Muốn giảm operational burden
- Cost optimization cho small-medium scale
- Native AWS service integration
- Không cần advanced features của Vault

### 4.3. Hybrid Approach

```
┌──────────────────────────────────────────────────────────────┐
│                    HYBRID ARCHITECTURE                       │
│                                                              │
│   ┌──────────────┐                                          │
│   │   AWS ECS    │                                          │
│   │   Lambda     │◄──── AWS Secrets Manager                 │
│   │   RDS        │         (AWS-native secrets)             │
│   └──────────────┘                                          │
│                                                              │
│   ┌──────────────┐                                          │
│   │   EKS/GKE    │◄──── HashiCorp Vault                     │
│   │   On-prem    │         (Cross-platform secrets)         │
│   │   Azure AKS  │                                          │
│   └──────────────┘                                          │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

---

## 5. Rủi ro, Anti-patterns, Lỗi thường gặp

### 5.1. Critical Anti-patterns

**❌ Anti-pattern 1: Secret trong Environment Variables**
```yaml
# Vấn đề: env vars visible trong /proc
env:
  - name: DB_PASSWORD
    valueFrom:
      secretKeyRef:
        name: db-secret
        key: password
```

> **Why dangerous**: Container runtime, orchestrator, monitoring tools đều có thể dump env vars. `/proc/[pid]/environ` readable bởi root.

**✅ Better approach**: Mount secrets vào file, read from file, keep in memory only.

**❌ Anti-pattern 2: Không validate rotation success**
```python
# ❌ Không check kết quả rotation
def rotate_secret():
    update_password_in_db()
    update_secret_in_manager()  # Có thể fail ở đây!
    # DB có password mới nhưng secret manager vẫn cũ
```

**✅ Correct approach**: Two-phase commit pattern với rollback capability.

**❌ Anti-pattern 3: Secret caching without TTL**
```java
// ❌ Cache vĩnh viễn
@Cacheable("secrets")
public String getSecret() {
    return secretsManager.getSecretValue();
}
```

**✅ Better approach**: Short TTL (5-15 min) + force refresh mechanism.

### 5.2. Production Failure Modes

**Scenario 1: Rotation race condition**
```
T0: App A retrieves secret v1
T1: Rotation starts → secret v2 created
T2: App B retrieves secret v2
T3: DB password updated to v2
T4: App A connect with v1 → ❌ CONNECTION FAILURE
```

**Mitigation**: Support dual-credentials trong rotation window, hoặc use connection pooling với retry.

**Scenario 2: Vault seal/unseal storm**
- Vault cluster unseal fail do KMS throttling
- All secrets unavailable → cascade failure

**Mitigation**: Auto-unseal với multiple KMS keys, standby cluster.

**Scenario 3: Lambda rotation timeout**
- Rotation Lambda timeout sau 15 phút
- Secret ở trạng thái inconsistent

**Mitigation**: Idempotent rotation steps, proper error handling, DLQ.

### 5.3. Security Vulnerabilities

| Vulnerability | Impact | Mitigation |
|---------------|--------|------------|
| **Secret logging** | Secrets lộ trong logs | Log filtering, structured logging with redaction |
| **Memory dump** | Secrets extractable từ core dump | Disable core dumps, use memory-safe languages |
| **Token leakage** | Vault token lộ → full access | Short TTL, response wrapping, cubbyhole |
| **Replay attack** | Stolen token reused | Single-use tokens, bind to IP/CN |
| **Privilege escalation** | Overly permissive policies | Principle of least privilege, ACLs |

---

## 6. Khuyến nghị thực chiến trong Production

### 6.1. Vault Production Checklist

**Infrastructure:**
```hcl
# High Availability setup
storage "raft" {
  path = "/opt/vault/data"
  node_id = "node1"
}

cluster_addr = "https://vault-1:8201"
api_addr = "https://vault.service.consul:8200"

# Performance tuning
listener "tcp" {
  address = "0.0.0.0:8200"
  tls_cert_file = "/opt/vault/tls/tls.crt"
  tls_key_file = "/opt/vault/tls/tls.key"
  tls_min_version = "tls12"
  
  # Connection tuning
  max_request_size = 33554432  # 32MB
  max_request_duration = "90s"
}

# Audit logging
audit "file" {
  path = "/var/log/vault/audit.log"
}
```

**Token Policies:**
```hcl
# Principle of least privilege
path "database/creds/my-app" {
  capabilities = ["read"]
}

# Deny everything else
path "*" {
  capabilities = ["deny"]
}
```

**Monitoring Metrics:**
- `vault.core.unsealed`: Cluster seal status
- `vault.token.lease.expiration`: Token TTL monitoring
- `vault.audit.log_failure`: Audit log failures (critical!)
- `vault.database.create-credentials`: Dynamic secret creation rate

### 6.2. AWS Secrets Manager Best Practices

**Rotation Configuration:**
```python
# CloudFormation/Terraform
resource "aws_secretsmanager_secret_rotation" "example" {
  secret_id           = aws_secretsmanager_secret.example.id
  rotation_lambda_arn = aws_lambda_function.rotation.arn
  
  rotation_rules {
    automatically_after_days = 30
    schedule_expression      = "rate(30 days)"  # Hoặc cron
  }
}
```

**Cost Optimization:**
- Use secret replication cho multi-region (không tạo duplicate)
- Batch secret retrieval để giảm API calls
- Dùng Lambda extension caching
- Delete unused secret versions (giữ lại cho rollback)

### 6.3. Secret Rotation trong Microservices

```
┌────────────────────────────────────────────────────────────────┐
│              SECRET ROTATION WORKFLOW                          │
│                                                                │
│  1. Detection: Secret sắp expire (alert 7 ngày trước)         │
│                                                                │
│  2. Preparation:                                               │
│     - Tạo secret mới (AWSPENDING / v2)                        │
│     - Test với staging environment                            │
│                                                                │
│  3. Gradual Rollout:                                           │
│     - Canary: 5% instances dùng secret mới                    │
│     - Monitor error rate                                       │
│     - Full rollout khi stable                                 │
│                                                                │
│  4. Cleanup:                                                   │
│     - Promote v2 → current                                    │
│     - Archive v1 (AWSPREVIOUS)                                │
│     - Revoke old credentials sau 24h grace period            │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

---

## 7. Kết luận

**Bản chất của Secrets Management:**

1. **Không phải là storage**, mà là **access control system** - Quản lý ai có quyền truy cập gì, khi nào, trong bao lâu

2. **Dynamic > Static** - Mỗi instance/service nhận unique, short-lived credentials giảm blast radius

3. **Rotation không optional** - Secret phải được rotate thường xuyên, automated, với zero-downtime strategy

4. **Observability is security** - Mọi access đều phải được audit, alert trên anomalous patterns

**Trade-off chính:**
- **Vault**: Power + Flexibility vs Operational Complexity
- **AWS Secrets Manager**: Simplicity + Integration vs Vendor lock-in

**Khuyến nghị cuối cùng:**
- Start với AWS Secrets Manager nếu AWS-only
- Migrate sang Vault khi cần multi-cloud hoặc advanced features
- Implement secret rotation từ day one, không để technical debt
- Monitoring và audit logging phải là first-class citizen

---

## 8. Code tham khảo (Minimal)

**Vault Database Dynamic Secret (Java):**
```java
@Configuration
public class DatabaseConfig {
    
    private final VaultTemplate vaultTemplate;
    
    @Bean
    public DataSource dataSource() {
        // Request dynamic credentials from Vault
        VaultResponse response = vaultTemplate.read("database/creds/my-app");
        
        Map<String, Object> data = response.getData();
        String username = (String) data.get("username");
        String password = (String) data.get("password");
        
        // Build DataSource with dynamic credentials
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://db:5432/mydb");
        config.setUsername(username);
        config.setPassword(password);
        
        // TTL handling - lease renewal
        Duration ttl = Duration.parse((String) data.get("lease_duration"));
        scheduleRenewal(response.getLeaseId(), ttl);
        
        return new HikariDataSource(config);
    }
    
    private void scheduleRenewal(String leaseId, Duration ttl) {
        // Renew lease at 2/3 of TTL
        long renewAfter = ttl.toMillis() * 2 / 3;
        // Implementation using ScheduledExecutorService
    }
}
```

**Key points trong code:**
- Credentials được retrieve runtime, không hardcode
- TTL được extract và schedule renewal
- Lease ID được track để revoke khi cần
- Connection pooling với HikariCP

**AWS Secrets Manager Rotation Handler (Python Lambda):**
```python
import boto3
import pg8000

def lambda_handler(event, context):
    arn = event['SecretId']
    token = event['ClientRequestToken']
    step = event['Step']
    
    secret_client = boto3.client('secretsmanager')
    
    if step == 'createSecret':
        # Generate new password
        new_password = generate_secure_password(32)
        secret_client.put_secret_value(
            SecretId=arn,
            ClientRequestToken=token,
            SecretString=json.dumps({
                'username': 'myuser',
                'password': new_password
            }),
            VersionStages=['AWSPENDING']
        )
    
    elif step == 'setSecret':
        # Get pending secret
        pending = secret_client.get_secret_value(
            SecretId=arn, VersionStage='AWSPENDING'
        )
        creds = json.loads(pending['SecretString'])
        
        # Update database - MUST support both old and new
        conn = pg8000.connect(
            host=os.environ['DB_HOST'],
            user='admin',  # Master user
            password=get_master_password()
        )
        cursor = conn.cursor()
        cursor.execute(
            "ALTER USER %s WITH PASSWORD %s",
            (creds['username'], creds['password'])
        )
        conn.commit()
    
    elif step == 'testSecret':
        pending = secret_client.get_secret_value(
            SecretId=arn, VersionStage='AWSPENDING'
        )
        creds = json.loads(pending['SecretString'])
        
        # Verify connection works
        conn = pg8000.connect(
            host=os.environ['DB_HOST'],
            user=creds['username'],
            password=creds['password']
        )
        conn.cursor().execute('SELECT 1')
    
    elif step == 'finishSecret':
        # Move AWSPENDING to AWSCURRENT
        secret_client.update_secret_version_stage(
            SecretId=arn,
            VersionStage='AWSCURRENT',
            MoveToVersionId=token,
            RemoveFromVersionId=get_current_version(arn)
        )
```

**Key points trong code:**
- 4-phase rotation: create → set → test → finish
- Each phase idempotent (có thể retry)
- Database update supports dual credentials
- Explicit testing trước khi promote
