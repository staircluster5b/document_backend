# Transport & Infrastructure Security

## 1. Mục tiêu của Task

Hiểu sâu các cơ chế bảo mật tầng giao thức và hạ tầng trong hệ thống phân tán enterprise, bao gồm:
- **mTLS (Mutual TLS)**: Xác thực hai chiều giữa services
- **Certificate Pinning**: Chống man-in-the-middle attacks
- **TLS 1.3**: Tối ưu handshake và security
- **Service-to-Service Authentication**: SPIFFE/SPIRE workload identity
- **Zero-Trust Architecture**: "Never trust, always verify"

> **Tầm quan trọng**: Khi hệ thống chuyển từ monolith sang microservices, attack surface tăng theo cấp số nhân. Mỗi service-to-service call là một vector tấn công tiềm năng.

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 TLS 1.3 - Tối Ưu Giao Thức Bảo Mật

#### Bản Chất Thiết Kế

TLS 1.3 được thiết kế với **tối giản (simplicity)** và **forward secrecy mặc định**:

| Đặc điểm | TLS 1.2 | TLS 1.3 |
|----------|---------|---------|
| Handshake round trips | 2-RTT (1-RTT với resumption) | 1-RTT (0-RTT với early data) |
| Cipher suites | 37+ options (nhiều yếu) | 5 AEAD-only (ChaCha20, AES-GCM) |
| Key exchange | RSA, DH, ECDH, DHE | Chỉ DH/ECDHE (forward secrecy bắt buộc) |
| Compression | Hỗ trợ (CRIME attack) | Removed |
| Custom DHE groups | Hỗ trợ (Logjam attack) | Removed, chỉ predefined groups |

#### Cơ Chế 1-RTT Handshake

```
Client                                           Server
  |                                                 |
  | ---- ClientHello + KeyShare ------------------> |
  |     [Supported groups, cipher suites, SNI]      |
  |                                                 |
  | <--- ServerHello + KeyShare + {Finished} ------ |
  |     [EncryptedExtensions, Server Cert]          |
  |                                                 |
  | ---- {Finished} + Application Data -----------> |
  |                                                 |
  | <=============================================> |
  |           Encrypted Application Data            |

{} = encrypted with handshake keys
```

**Bản chất của tối ưu**: 
- Client gửi **KeyShare ngay lần đầu** (đoán trước server sẽ chọn group nào)
- Server trả về **Finished ngay trong ServerHello**, không cần chờ client verify
- **Server Certificate được mã hóa** (ESNI → ECH - Encrypted Client Hello)

#### 0-RTT (Early Data) - Trade-off Nguy Hiểm

```
Client                                           Server
  | ---- ClientHello + KeyShare + [Early Data] --> |
  |                                                 |
  | <--- ServerHello + Finished + Application ---> |
  |                                                 |
  | ---- Finished + More Application Data -------> |
```

> ⚠️ **Rủi ro 0-RTT: Replay Attack**
> - Attacker có thể capture và replay early data
> - Không nên dùng 0-RTT cho POST/PUT requests
> - Server phải implement anti-replay mechanisms (ticket age validation, single-use tickets)

**Production Recommendation**:
```
Chỉ dùng 0-RTT cho:
- GET requests (idempotent, no side effects)
- Static content serving
- Metrics/scraping endpoints

TẮT 0-RTT cho:
- API mutations
- Payment processing
- Authentication endpoints
```

---

### 2.2 mTLS - Mutual TLS Authentication

#### Bản Chất Xác Thực Hai Chiều

Standard TLS: **Server proves identity to Client** (browser kiểm tra server certificate)
mTLS: **Both parties prove identity to each other** (service A kiểm tra service B và ngược lại)

```
Standard TLS Handshake:
  Client                       Server
    |                            |
    | ---- ClientHello --------> |
    | <--- ServerHello --------- |
    | <--- Server Certificate -- |  ← Client verifies server
    | ---- Client KeyExchange --> |
    | ...                        |

mTLS Handshake (thêm bước):
  Client                       Server
    |                            |
    | <--- Request Certificate - |  ← Server yêu cầu client cert
    | ---- Client Certificate --> |  ← Client gửi cert
    | ---- CertificateVerify ---> |  ← Client chứng minh sở hữu private key
    |                            |
```

#### Certificate Chain Verification

```
Root CA (Self-signed)
    |
Intermediate CA A (signed by Root)
    |
Intermediate CA B (signed by Intermediate A)
    |
Server/Client Certificate (signed by Intermediate B)
```

**Bản chất verification**:
1. Verify signature từng cấp (tính toán hash, decrypt bằng public key của issuer)
2. Check validity period (notBefore, notAfter)
3. Check revocation (OCSP/CRL)
4. Check certificate purpose (keyUsage, extendedKeyUsage)
5. **CN/SAN matching** (hostname phải match certificate subject)

#### mTLS trong Java/Spring Boot

**Configuration phân tích**:

```yaml
server:
  ssl:
    enabled: true
    key-store: classpath:service-keystore.p12
    key-store-password: ${KEYSTORE_PASSWORD}
    key-alias: service-a
    trust-store: classpath:truststore.p12
    trust-store-password: ${TRUSTSTORE_PASSWORD}
    client-auth: need  # ← Bắt buộc mTLS
```

| `client-auth` value | Ý nghĩa | Use case |
|---------------------|---------|----------|
| `none` | Không yêu cầu client cert | Public APIs |
| `want` | Yêu cầu nếu có, optional | Mixed traffic |
| `need` | Bắt buộc client cert | Internal services |

**Trust Store vs Key Store**:

| | Key Store | Trust Store |
|--|-----------|-------------|
| **Chứa** | Private key + certificate của mình | Các CA certificates để verify ngưởi khác |
| **Dùng để** | Chứng minh identity của mình | Verify identity của peer |
| **Bảo vệ** | Cực kỳ quan trọng (private key!) | Quan trọng (compromise = có thể trust attacker) |
| **Rotation** | Khó khăn (phải cập nhật tất cả clients) | Dễ hơn (thêm CA mới, giữ cũ) |

---

### 2.3 Certificate Pinning - Chống Man-in-the-Middle

#### Bản Chất

**Vấn đề**: CA compromise hoặc rogue CA có thể issue fake certificates cho domain của bạn.

**Giải pháp**: Application "ghim" (pin) một public key hoặc certificate cụ thể, chỉ chấp nhận đúng key đó.

```
Normal TLS:          Certificate Pinning:
                     
Client              Client (có embedded public key hash)
  |                    |
  |<--- Cert -----     |<--- Cert -----
  |  [Any valid CA]    |  Compare với pinned key
  | Verify OK          | Match? → Accept
  |                    | No match → Reject (MITM detected!)
```

#### Pinning Strategies

| Strategy | Implementation | Trade-off |
|----------|---------------|-----------|
| **Certificate Pinning** | Pin toàn bộ certificate | Quá chặt (cert rotate = crash app) |
| **Public Key Pinning** | Pin public key (SPKI hash) | Linh hoạt hơn (giữ key, đổi cert) |
| **CA Pinning** | Pin intermediate/root CA | An toàn hơn, vẫn bị compromise nếu CA bị hack |

**SPKI (Subject Public Key Info) Hash**:
```
Certificate
├── Subject: CN=example.com
├── Issuer: CN=Let's Encrypt
├── Validity: ...
├── Subject Public Key Info ← PIN THIS
│   ├── Algorithm: RSA/ECDSA
│   └── SubjectPublicKey: [bytes...]
└── Signature: ...
```

#### HTTP Public Key Pinning (HPKP) - DEPRECATED

> ⚠️ **HPKP đã bị deprecated** (Chrome 72+) vì rủi ro "death by pinning mistake"
> - Một lỗi pinning = website inaccessible cho tất cả users
> - Được thay thế bởi **Expect-CT** và **Certificate Transparency**

**Pinning trong Mobile Apps (hiện tại)**:
```java
// Android Network Security Config
<network-security-config>
    <domain-config>
        <domain includeSubdomains="true">api.example.com</domain>
        <pin-set expiration="2025-01-01">
            <pin digest="SHA-256">sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=</pin>
            <pin digest="SHA-256">sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=</pin>
        </pin-set>
    </domain-config>
</network-security-config>
```

> **Backup pin**: Luôn có ít nhất 2 pins - primary và backup (đề phòng key rotation)

---

### 2.4 SPIFFE/SPIRE - Workload Identity

#### Bản Chất Vấn Đề

**Challenge trong microservices**:
- 1000+ services, mỗi service có certificate riêng
- Certificate rotation, renewal, revocation
- Service-to-service authentication phức tạp
- Secret sprawl (passwords, API keys khắp nơi)

**SPIFFE (Secure Production Identity Framework For Everyone)**: Standard cho workload identity
**SPIRE**: Implementation của SPIFFE

#### SPIFFE ID - Identity Format

```
spiffe://trust-domain/workload-identifier

Ví dụ:
spiffe://production.mycompany.com/ns/payments/sa/payment-service
spiffe://staging.mycompany.com/host/vm-12345
```

| Component | Ý nghĩa |
|-----------|---------|
| `spiffe://` | URI scheme, không phải HTTP |
| `trust-domain` | Root of trust (tương đương root CA) |
| `/ns/payments/sa/payment-service` | Workload selector (Kubernetes: namespace + service account) |

#### SPIRE Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Control Plane                           │
│  ┌──────────────┐         ┌──────────────────────────────┐  │
│  │   SPIRE      │────────▶│       SPIRE Server           │  │
│  │   Agent      │  mTLS   │  - CA/Key management         │  │
│  │  (per node)  │◀────────│  - Node attestation          │  │
│  └──────────────┘         │  - Workload registration     │  │
│          │                └──────────────────────────────┘  │
│          │ SVID (SPIFFE Verifiable Identity Document)       │
│          ▼                                                   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Workload (Your Application)              │   │
│  │  - X.509-SVID: Certificate + private key (short-lived)│   │
│  │  - JWT-SVID: For services không hỗ trợ mTLS          │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

#### X.509-SVID (Service Identity Document)

```
Certificate:
    Subject: O = SPIRE, CN = payment-service
    Issuer: O = SPIRE, CN = spire-server
    Validity: 1 hour (short-lived by default)
    
    X509v3 Subject Alternative Name:
        URI:spiffe://production.mycompany.com/ns/payments/sa/payment-service
        
    X509v3 Key Usage: critical
        Digital Signature, Key Encipherment
        
    X509v3 Extended Key Usage:
        TLS Web Server Authentication,
        TLS Web Client Authentication
```

**Bản chất bảo mật**:
- **Short-lived certificates** (mặc định 1 giờ): Compromised cert tự expire nhanh
- **No private key persistence**: Key được mount vào memory (tmpfs), không lưu disk
- **Automatic rotation**: SPIRE agent tự renew trước khi expire

#### Node Attestation - Trust Establishment

SPIRE Server phải xác minh "node này là thật" trước khi cấp identity:

| Platform | Attestation Method |
|----------|-------------------|
| Kubernetes | Service account token của node |
| AWS | Instance Identity Document (signed by AWS) |
| GCP | GCP Instance Identity Token |
| Azure | Azure Managed Identity token |
| Bare metal | TPM attestation, join tokens |

```
Node boot ──▶ Attestation ──▶ Node SVID ──▶ Workload attestation ──▶ Workload SVID
                ("Tôi là node-5")          ("Tôi là payment-service pod-123")
```

---

### 2.5 Zero-Trust Architecture

#### Bản Chất Triết Lý

**Mô hình truyền thống (Perimeter-based)**:
```
Internet ──[Firewall]──[DMZ]──[Internal Network: Trusted]──▶ Resources
            
Once inside → Free access to everything
```

**Zero-Trust**:
```
Every access request ──▶ Verify identity ──▶ Verify device health ──▶ Check authorization ──▶ Grant minimal access
        │                        │                     │                        │
        └────────────────────────┴─────────────────────┴────────────────────────┘
                              Never trust, always verify
                              (Every request, every time)
```

#### Zero-Trust Pillars

| Pillar | Implementation | Verification |
|--------|---------------|--------------|
| **Identity** | SPIFFE/SPIRE, OAuth 2.0, SAML | Who is requesting? |
| **Device** | MDM, device certificates | Is device trusted/healthy? |
| **Network** | Micro-segmentation, mTLS | Is connection secured? |
| **Application** | RBAC, ABAC, policy engine | Is action authorized? |
| **Data** | Encryption at rest/transit | Is data protected? |

#### BeyondCorp - Google's Zero-Trust Implementation

```
┌─────────────────────────────────────────────────────────────┐
│                     Unmanaged Network                        │
│  (Any network - coffee shop, home, office - same treatment)  │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    Access Proxy ( IAP )                      │
│  - Terminates TLS                                            │
│  - Enforces global access policies                           │
│  - Single point for logging/auditing                         │
└─────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
      ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
      │   Access     │ │   Device     │ │   Identity   │
      │   Policy     │ │   Inventory  │ │   Provider   │
      │   Engine     │ │   (Health)   │ │   (SSO)      │
      └──────────────┘ └──────────────┘ └──────────────┘
              │               │               │
              └───────────────┴───────────────┘
                              │
                              ▼
                   ┌──────────────────────┐
                   │   Allow / Deny       │
                   │   + Access Level     │
                   └──────────────────────┘
```

**Access Levels**:
- **Basic**: Any authenticated user, any device
- **Sensitive**: Corporate device, basic health checks
- **Highly Sensitive**: Corporate device, full compliance, specific geo

---

## 3. Kiến Trúc và Luồng Xử Lý

### 3.1 Service-to-Service mTLS trong Kubernetes

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          Kubernetes Cluster                              │
│                                                                          │
│  ┌────────────────────┐              ┌────────────────────┐             │
│  │   Pod: Order       │              │   Pod: Payment     │             │
│  │   Service          │              │   Service          │             │
│  │                    │              │                    │             │
│  │  ┌──────────────┐  │              │  ┌──────────────┐  │             │
│  │  │ Application  │  │◀──mTLS──────▶│  │ Application  │  │             │
│  │  │   (Java)     │  │              │  │   (Java)     │  │             │
│  │  └──────────────┘  │              │  └──────────────┘  │             │
│  │         │          │              │         │          │             │
│  │  ┌──────────────┐  │              │  ┌──────────────┐  │             │
│  │  │ Envoy Proxy  │  │              │  │ Envoy Proxy  │  │             │
│  │  │  (Sidecar)   │──┘              └──│  (Sidecar)   │  │             │
│  │  │              │───Certificate───────▶│              │  │             │
│  │  └──────────────┘    from SPIRE      └──────────────┘  │             │
│  └────────────────────┘              └────────────────────┘             │
│           │                                    │                         │
│           └───────────────┬────────────────────┘                         │
│                           │                                             │
│                    ┌──────────────┐                                      │
│                    │ SPIRE Agent  │                                      │
│                    │ (per node)   │                                      │
│                    └──────────────┘                                      │
│                           │                                             │
│                    ┌──────────────┐                                      │
│                    │ SPIRE Server │                                      │
│                    │ (control)    │                                      │
│                    └──────────────┘                                      │
└─────────────────────────────────────────────────────────────────────────┘
```

**Luồng xử lý request**:
1. Order Service gọi Payment Service
2. **Sidecar (Envoy/Istio)** intercepts traffic (iptables redirect)
3. Sidecar lấy X.509-SVID từ SPIRE Agent (UDS - Unix Domain Socket)
4. **Client Sidecar** presents certificate trong TLS handshake
5. **Server Sidecar** verifies:
   - Certificate valid (not expired, signed by trusted CA)
   - SPIFFE ID matches expected service (authorization check)
   - Revocation status (nếu enable)
6. **mTLS established** - traffic encrypted end-to-end
7. Sidecar forwards plaintext tới application

### 3.2 Certificate Rotation Flow

```
Time ──────────────────────────────────────────────────────────▶

     ┌──────────────────────────────────────────────────────┐
     │  Validity: 1 hour                                    │
     │  ┌────────┐                                          │
Cert │  │ Active │                                          │
  1  │  └────────┘                                          │
     └──────────────────────────────────────────────────────┘
     
     ─────────────────────────────────────────────────────────
     
     ┌──────────────────────────────────────────────────────┐
     │  Renew at 50% = 30 min                               │
     │              ┌────────┐                              │
Cert │              │ Active │                              │
  2  │              └────────┘                              │
     └──────────────────────────────────────────────────────┘
     
     |<──Active──>|<──────────Overlap──────────>|<──Active──>|
     Cert 1       Cert 1 + Cert 2               Cert 2
     
     
Rotation Strategy: HOT-SWAP
- SPIRE agent mounts new cert vào shared volume
- Application/Sidecar detect file change (inotify)
- Reload cert WITHOUT restart (zero-downtime)
```

---

## 4. So Sánh Các Lựa Chọn

### 4.1 Authentication Methods Comparison

| Method | Security | Complexity | Latency | Best For |
|--------|----------|------------|---------|----------|
| **API Keys** | Low (static, easily leaked) | Low | Low | Internal debugging only |
| **JWT (RS256)** | Medium (signed, can verify offline) | Low | Low | User auth, stateless |
| **mTLS** | High (mutual auth, short-lived certs) | High | Medium | Service-to-service |
| **SPIFFE/SPIRE** | Very High (auto-rotation, workload-bound) | Very High | Medium | Large-scale microservices |

### 4.2 TLS Termination Options

```
┌─────────────────────────────────────────────────────────────────┐
│ Option 1: Edge Termination (Nginx/Ingress)                      │
│ Internet ──[TLS]──▶ Nginx ──[Plain HTTP]──▶ App                 │
│                                                                 │
│ Pros: Simple, Centralized cert management                       │
│ Cons: Internal traffic unencrypted (compliance risk)            │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ Option 2: Re-encryption (Edge + mTLS)                           │
│ Internet ──[TLS]──▶ Nginx ──[TLS]──▶ App                        │
│                                                                 │
│ Pros: End-to-end encrypted                                      │
│ Cons: Double TLS overhead, complexity                           │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ Option 3: Service Mesh (Sidecar mTLS)                           │
│ App ──[Plain]──▶ Envoy Sidecar ──[mTLS]──▶ Envoy ──[Plain]──▶ App│
│                                                                 │
│ Pros: Transparent, automatic mTLS, authz policies               │
│ Cons: Resource overhead, debugging complexity                   │
└─────────────────────────────────────────────────────────────────┘
```

### 4.3 Service Mesh Comparison

| Feature | Istio | Linkerd | Consul Connect |
|---------|-------|---------|----------------|
| **Control Plane** | Complex (istiod) | Simple | Integrated with Consul |
| **mTLS** | Automatic (Citadel) | Automatic | Automatic |
| **Performance** | Higher overhead | Lightweight | Medium |
| **Policy Engine** | Extensive (OPA, RBAC) | Basic | Basic |
| **Learning Curve** | Steep | Gentle | Medium |
| **Best For** | Enterprise, complex policies | Simplicity, performance | HashiCorp ecosystem |

---

## 5. Rủi Ro, Anti-Patterns, Lỗi Thường Gặp

### 5.1 Certificate Management Failures

#### **Certificate Expiry - #1 Cause of Outages**

> 🔴 **Famous outages**:
> - LinkedIn (2019): Certificate expiry → 1 hour downtime
> - Microsoft Teams (2020): Expired cert → global outage
> - Spotify (2021): Cert expiry → hours of degraded service

**Anti-pattern**: Manual certificate tracking
**Solution**:
- Automated renewal (SPIRE: auto-rotation)
- Certificate expiry monitoring (alert at 30, 14, 7, 1 days)
- Staging environment with shorter-lived certs (test rotation)

#### **Certificate Chain Issues**

```
Lỗi: "unable to find valid certification path to requested target"

Nguyên nhân:
1. Missing intermediate certificate
2. Wrong trust store (didn't include root/intermediate CA)
3. Self-signed cert trong production
4. Certificate format issues (PEM vs DER)

Debug:
openssl s_client -connect server:port -showcerts
keytool -list -v -keystore truststore.jks
```

### 5.2 mTLS Misconfigurations

#### **Weak Cipher Suites**

```
Anti-pattern (Spring Boot):
server.ssl.enabled-protocols=TLSv1,TLSv1.1,TLSv1.2  ❌ TLS 1.0/1.1 vulnerable

Correct:
server.ssl.enabled-protocols=TLSv1.3,TLSv1.2        ✅
server.ssl.ciphers=TLS_AES_256_GCM_SHA384,...       ✅ AEAD only
```

#### **Certificate Validation Bypass**

```java
// ☠️ DEADLY - NEVER DO THIS
TrustManager[] trustAllCerts = new TrustManager[] {
    new X509TrustManager() {
        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
        public X509Certificate[] getAcceptedIssuers() { return null; }
    }
};
// Điều này disable TẤT CẢ certificate validation!
```

> 💀 **Consequence**: Man-in-the-middle attacks, credential theft

### 5.3 Zero-Trust Pitfalls

#### **Overly Restrictive Policies**

```
Policy: "Block all traffic not explicitly allowed"
Problem: Breaks health checks, monitoring, debugging
Solution: Explicit allow rules for:
- /health, /ready, /metrics endpoints
- Monitoring scrapers (Prometheus)
- CI/CD pipelines
```

#### **Trust on First Use (TOFU)**

```
Mô hình: "Trust device lần đầu tiên nó đăng ký"
Rủi ro: Attacker có thể đăng ký device trước owner
Solution: Require corporate attestation (MDM enrollment)
```

### 5.4 SPIRE/Workload Identity Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| **Node compromise** | Attacker gets all workload identities | Node attestation + short-lived SVIDs |
| **Agent compromise** | Can issue arbitrary identities | Agent runs as unprivileged user |
| **Registration misconfig** | Wrong workload gets wrong identity | Code review registration entries |
| **SVID key leak** | Impersonate workload | Short TTL (1hr), memory-only keys |

---

## 6. Khuyến Nghị Thực Chiến trong Production

### 6.1 Certificate Lifecycle Management

```yaml
# Policy Recommendations

certificate_validity:
  public_facing: 90_days  # Let's Encrypt style
  internal_mtls: 24_hours  # SPIRE default
  root_ca: 10_years
  intermediate_ca: 5_years
  
rotation_schedule:
  renew_at: "50% of validity"  # 12h cho cert 24h
  emergency_rotation: "Immediate revoke + reissue"
  
monitoring:
  expiry_alerts: [30d, 14d, 7d, 1d, 6h]
  rotation_success_rate: ">99.9%"
  failed_handshake_rate: "<0.1%"
```

### 6.2 mTLS Configuration Best Practices

```yaml
# Java/Spring Boot Production Config
server:
  ssl:
    enabled: true
    protocol: TLS
    enabled-protocols: TLSv1.3,TLSv1.2
    ciphers:
      - TLS_AES_256_GCM_SHA384
      - TLS_AES_128_GCM_SHA256
      - TLS_CHACHA20_POLY1305_SHA256
      - TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
      - TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
    client-auth: need
    key-store-type: PKCS12
    trust-store-type: PKCS12

# Connection pool config (mTLS reuse)
httpclient:
  ssl:
    connection-timeout: 5s
    handshake-timeout: 10s
    session-timeout: 24h  # TLS session resumption
    session-cache-size: 10000
```

### 6.3 Observability for Security

**Metrics to track**:
```
# TLS Handshake
tls_handshake_duration_seconds{status="success|failure"}
tls_handshake_failures_total{reason="expired|untrusted|mismatch"}
tls_cert_expiry_timestamp

# mTLS
mtls_connections_active
mtls_handshake_duration
mtls_client_auth_failures_total

# Certificate
x509_cert_expiry_timestamp
cert_rotation_duration_seconds
cert_rotation_failures_total
```

**Distributed Tracing**:
```
Span attributes:
- tls.version: "1.3"
- tls.cipher: "TLS_AES_256_GCM_SHA384"
- tls.client.spiffe_id: "spiffe://..."
- tls.server.name: "payment-service"
```

### 6.4 Incident Response Playbook

```
🚨 Certificate Expiry Alert

1. Immediate (0-5 min):
   - Confirm alert (avoid false positive)
   - Check if auto-renewal is working
   - 
2. Short-term (5-30 min):
   - If auto-renew failed: manual emergency renewal
   - Update load balancers with new cert
   - Verify all endpoints serving new cert

3. Post-incident:
   - Root cause: Why auto-renew failed?
   - Update monitoring thresholds
   - Document for next time

🚨 Suspicious mTLS Failures Spike

1. Check if it's:
   - Deployment with old cert? → Rollback/Update
   - New service not registered? → Add to trust
   - Attack attempt? → Analyze source IPs

2. Temporary mitigation:
   - Rate limit suspicious sources
   - Enable additional logging
```

---

## 7. Kết Luận

### Bản Chất Cốt Lõi

| Khái niệm | Bản chất |
|-----------|----------|
| **TLS 1.3** | Giảm latency (1-RTT), loại bỏ legacy insecure options, mandatory forward secrecy |
| **mTLS** | Mutual authentication qua certificates, không cần shared secrets |
| **Certificate Pinning** | Explicit trust, chống rogue CA, nhưng risk rotation outages |
| **SPIFFE/SPIRE** | Workload identity thay vì network identity, automatic short-lived credentials |
| **Zero-Trust** | "Never trust, always verify" - mọi request đều được xác minh, mọi lúc |

### Trade-off Quan Trọng Nhất

> **Security vs Complexity vs Operability**
> 
> - mTLS everywhere = Security tốt nhưng debugging khó khăn
> - SPIFFE = Tự động hóa hoàn hảo nhưng learning curve cao
> - Zero-Trust = Bảo mật tối đa nhưng UX impact cho developers

**Lựa chọn thực tế**:
- **Small scale (< 20 services)**: mTLS với manually managed certs
- **Medium scale (20-100 services)**: SPIFFE/SPIRE hoặc Service Mesh
- **Large scale (> 100 services)**: Full Zero-Trust với BeyondCorp model

### Rủi Ro Lớn Nhất

**Certificate expiry causing cascade failure** - Đây là #1 cause của security-related outages. Không phải hacking, mà là quản lý certificates kém.

> **Nguyên tắc vàng**: Short-lived certificates + automated rotation + aggressive monitoring = Không bao giờ expiry outage

---

## Tài Liệu Tham Khảo

1. [RFC 8446 - TLS 1.3 Specification](https://datatracker.ietf.org/doc/html/rfc8446)
2. [SPIFFE Specification](https://spiffe.io/docs/latest/spiffe-about/overview/)
3. [BeyondCorp - Google Research Paper](https://research.google/pubs/pub43231/)
4. [OWASP Transport Layer Protection Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Transport_Layer_Protection_Cheat_Sheet.html)
5. [NIST SP 800-207 - Zero Trust Architecture](https://csrc.nist.gov/publications/detail/sp/800-207/final)
