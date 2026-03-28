# Data Protection: Encryption at Rest/Transit, Key Management, HSM

## 1. Mục tiêu của Task

Hiểu sâu cơ chế bảo vệ dữ liệu trong hệ thống Java enterprise: mã hóa dữ liệu tĩnh (at rest), mã hóa dữ liệu truyền (in transit), quản lý khóa mật mã, và tích hợp Hardware Security Module (HSM).

Mục tiêu cuối cùng: thiết kế chiến lược bảo vệ dữ liệu toàn diện, cân bằng giữa security, performance, compliance, và operational complexity.

---

## 2. Bản chất và Cơ chế Hoạt động

### 2.1 Encryption at Rest - Bản chất

**Vấn đề cốt lõi:** Dữ liệu lưu trữ (database, file system, object storage) có thể bị truy cập trái phép nếu attacker chiếm được physical storage hoặc backup.

**Cơ chế hoạt động:**

```
┌─────────────────────────────────────────────────────────────┐
│                    ENCRYPTION AT REST                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────┐         ┌─────────────┐         ┌────────┐ │
│  │   Plaintext  │ ──DEK──▶│  Cipher     │ ──────▶│ Ciphertext│
│  │   (Data)     │         │  (AES-GCM)  │         │ (Storage)│
│  └──────────────┘         └─────────────┘         └────────┘ │
│         │                                                    │
│         │         ┌─────────────┐                            │
│         └────────▶│  KEK Wrap   │◀──── Master Key (HSM/KMS)  │
│                   │  (RSA/AES)  │                            │
│                   └─────────────┘                            │
│                                                              │
│  DEK = Data Encryption Key (per-data, randomly generated)   │
│  KEK = Key Encryption Key (protects DEKs, stored in HSM)    │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

**Envelope Encryption - Tại sao cần 2 lớp khóa?**

| Khía cạnh | Single Key | Envelope Encryption |
|-----------|------------|---------------------|
| Key rotation | Rotate tất cả data | Chỉ rotate KEK, DEK unwrap + re-wrap |
| Performance | N/A | DEK cached, KEK ops minimal |
| Access control | All-or-nothing | Granular DEK access |
| Audit | Limited | KEK access logged per DEK unwrap |

> **Quan trọng:** Luôn dùng envelope encryption. Không bao giờ encrypt trực tiếp data bằng master key.

### 2.2 Encryption in Transit - Bản chất

**Vấn đề cốt lõi:** Dữ liệu truyền qua network có thể bị intercept, tamper, replay attack.

**TLS 1.3 Architecture:**

```
┌────────────────────────────────────────────────────────────────────┐
│                      TLS 1.3 HANDSHAKE                              │
├────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Client                              Server                        │
│    │                                    │                          │
│    │ ───── ClientHello + KeyShare ────▶│  (Chọn cipher suite)     │
│    │                                    │                          │
│    │ ◀──── ServerHello + KeyShare ────│  (Xác định shared secret)│
│    │      {EncryptedExtensions}        │                          │
│    │      {Certificate}                │  (X.509 chain)           │
│    │      {CertificateVerify}          │  (Signature proof)       │
│    │      {Finished}                   │                          │
│    │                                    │                          │
│    │ ───── {Finished} ────────────────▶│  (1-RTT complete)        │
│    │                                    │                          │
│    │ ═════ Application Data ══════════▶│  (Encrypted, AEAD)       │
│    │                                    │                          │
└────────────────────────────────────────────────────────────────────┘
```

**Java TLS Implementation chi tiết:**

```java
// JSSE (Java Secure Socket Extension) Architecture
// Provider: SunJSSE (default), Bouncy Castle, Conscrypt

// KeyManager: Xác thực server/client với certificate
// TrustManager: Xác thực peer certificate

SSLContext context = SSLContext.getInstance("TLSv1.3");
context.init(keyManagers, trustManagers, secureRandom);

// Cipher suite selection - ưu tiên hiện đại
// TLS_AES_256_GCM_SHA384 (AES-GCM, hardware-accelerated)
// TLS_CHACHA20_POLY1305_SHA256 (software-optimized)
```

### 2.3 Key Management - Bản chất

**Vòng đỳ khóa mật mã (Cryptographic Key Lifecycle):**

```
┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐
│Generate │───▶│  Store  │───▶│Distribute│───▶│  Use    │───▶│ Rotate/ │
│         │    │         │    │          │    │         │    │ Destroy │
└─────────┘    └─────────┘    └─────────┘    └─────────┘    └─────────┘
      │                              │             │               │
      ▼                              ▼             ▼               ▼
  CSPRNG                        Secure channel  Monitoring    Cryptographic
  (SecureRandom)                (mTLS, HSM)     Audit log     erasure
```

**Key Derivation - Tại sao không dùng random key trực tiếp?**

| Phương pháp | Use case | Đặc điểm |
|-------------|----------|----------|
| PBKDF2 (Password-based) | User password → key | Slow, config iterations (600k+) |
| HKDF (Extract-then-Expand) | Shared secret → multiple keys | Fast, provable secure |
| Scrypt/Argon2 | Password hashing | Memory-hard, chống ASIC/FPGA |

### 2.4 Hardware Security Module (HSM) - Bản chất

**Vấn đề:** Software key storage vulnerable to memory dump, cold boot attack, insider threat.

**HSM Value Proposition:**

```
┌─────────────────────────────────────────────────────────────────────┐
│                         HSM SECURITY BOUNDARY                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                    APPLICATION SERVER                        │   │
│   │  ┌─────────────┐                                           │   │
│   │  │   App Code  │────▶ API Call (PKCS#11/JCE/JCA)            │   │
│   │  └─────────────┘            │                               │   │
│   └─────────────────────────────┼───────────────────────────────┘   │
│                                 │                                    │
│   ┌─────────────────────────────▼───────────────────────────────┐   │
│   │                    HSM (Tamper-resistant Hardware)           │   │
│   │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │   │
│   │  │  Secure CPU │  │  Crypto     │  │  Protected Memory   │  │   │
│   │  │  (No JTAG)  │  │  Accelerator│  │  (Keys never leave) │  │   │
│   │  └─────────────┘  └─────────────┘  └─────────────────────┘  │   │
│   │                                                              │   │
│   │  Operations: Sign, Decrypt, Unwrap ──▶ Kết quả trả về       │   │
│   │  Keys: Chỉ reference (handle), never exposed                 │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│   Attack surface: Physical tamper ◀───▶ Zeroization tự động         │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

**HSM Security Levels (FIPS 140-2/140-3):**

| Level | Yêu cầu | Use case |
|-------|---------|----------|
| 1 | Software crypto, production-grade | Standard enterprise |
| 2 | Tamper-evident coating, role-based auth | Financial services |
| 3 | Tamper-responsive, zeroization | High-value transactions |
| 4 | Environmental failure protection | Military, government |

---

## 3. Kiến trúc và Luồng Xử lý

### 3.1 Enterprise Data Protection Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         APPLICATION LAYER                                    │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                    Encryption Service                                │    │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │    │
│  │  │   Config     │  │   DEK Cache  │  │   Policy     │              │    │
│  │  │   (rotation) │  │   (Caffeine) │  │   Engine     │              │    │
│  │  └──────────────┘  └──────────────┘  └──────────────┘              │    │
│  └──────────────────────────┬─────────────────────────────────────────┘    │
└─────────────────────────────┼───────────────────────────────────────────────┘
                              │
┌─────────────────────────────▼───────────────────────────────────────────────┐
│                         KEY MANAGEMENT LAYER                                 │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                    Key Management Service (KMS)                      │    │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │    │
│  │  │  Key Store   │  │  Access      │  │  Audit       │              │    │
│  │  │  (metadata)  │  │  Control     │  │  Logging     │              │    │
│  │  └──────────────┘  └──────────────┘  └──────────────┘              │    │
│  └──────────────────────────┬─────────────────────────────────────────┘    │
└─────────────────────────────┼───────────────────────────────────────────────┘
                              │
┌─────────────────────────────▼───────────────────────────────────────────────┐
│                         HSM LAYER (FIPS 140-2 Level 3)                       │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │    │
│  │  │ Master Key 0 │  │ Master Key 1 │  │ Master Key N │              │    │
│  │  │ (KEK Root)   │  │ (KEK App A)  │  │ (KEK App B)  │              │    │
│  │  └──────────────┘  └──────────────┘  └──────────────┘              │    │
│  │                                                                      │    │
│  │  Operations: Generate, Wrap, Unwrap, Sign (keys never exported)     │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 End-to-End Encryption Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         DATA WRITE FLOW                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. App generates random DEK (256-bit AES)                                  │
│        ↓                                                                     │
│  2. App encrypts data: ciphertext = AES-GCM(DEK, plaintext)                 │
│        ↓                                                                     │
│  3. App sends DEK to KMS: wrappedDEK = HSM.wrap(DEK, KEK_id)                │
│        ↓                                                                     │
│  4. App stores: {ciphertext, wrappedDEK, KEK_version, iv, tag}              │
│        ↓                                                                     │
│  5. DEK immediately cleared from memory (Arrays.fill)                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                         DATA READ FLOW                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. App retrieves from storage: wrappedDEK + ciphertext                     │
│        ↓                                                                     │
│  2. App sends to KMS: DEK = HSM.unwrap(wrappedDEK, KEK_id)                  │
│        ↓                                                                     │
│  3. App caches DEK (TTL 5 min, encrypted in memory with app-level key)      │
│        ↓                                                                     │
│  4. App decrypts: plaintext = AES-GCM(DEK, ciphertext)                      │
│        ↓                                                                     │
│  5. App uses data, clears plaintext, DEK remains in cache                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. So sánh các Lựa chọn

### 4.1 Java Cryptographic Providers

| Provider | Performance | FIPS Certified | Maturity | Use case |
|----------|-------------|----------------|----------|----------|
| **SunJCE** | Baseline | No (SunJSSE có FIPS mode) | Very High | Development, testing |
| **Bouncy Castle** | Good | Yes (BC-FIPS) | Very High | Flexible, extensive algorithms |
| **Conscrypt** | Excellent | Yes | High | High-throughput, mobile-friendly |
| **AWS-LC** | Excellent | Yes | Medium | AWS-optimized |

### 4.2 Encryption Algorithms

| Algorithm | Mode | Security Level | Performance | Java 21+ | Use case |
|-----------|------|----------------|-------------|----------|----------|
| **AES-256** | GCM | 256-bit | Fast (AES-NI) | ✅ Default | General purpose, authenticated |
| **AES-128** | GCM | 128-bit | Faster (AES-NI) | ✅ | Performance-critical |
| **ChaCha20-Poly1305** | Stream | 256-bit | Fast (software) | ✅ | Mobile, no AES-NI |
| **AES** | CBC | 128/256-bit | Fast | ⚠️ Legacy | Only with HMAC |

> **Quan trọng:** Không dùng AES-CBC không có authentication. GCM và ChaCha20-Poly1305 cung cấp authenticated encryption (AEAD).

### 4.3 Key Storage Options

| Storage | Security | Performance | Cost | Complexity | Use case |
|---------|----------|-------------|------|------------|----------|
| **Java KeyStore (JKS)** | Low | High | Free | Low | Development only |
| **Java KeyStore (PKCS12)** | Medium | High | Free | Low | Standalone apps |
| **AWS KMS** | High | Medium | Pay per op | Low | Cloud-native |
| **HashiCorp Vault** | High | Medium | License | Medium | Hybrid/multi-cloud |
| **HSM (Thales, Entrust)** | Very High | Medium-High | Very High | High | Financial, government |
| **Cloud HSM (AWS, GCP, Azure)** | Very High | Medium | High | Medium | Cloud with compliance |

### 4.4 mTLS vs Application-Level Encryption

| Aspect | mTLS | Application Encryption |
|--------|------|------------------------|
| **Layer** | Transport (L4) | Application (L7) |
| **Scope** | All traffic | Selective data |
| **Key management** | Certificate-based | Symmetric keys |
| **Performance** | Minimal overhead | Variable (depends on data) |
| **Granularity** | Per-connection | Per-field/row |
| **Use case** | Service-to-service | Sensitive PII/PCI fields |

> **Best practice:** Dùng cả hai. mTLS cho service communication, app-level encryption cho sensitive data fields.

---

## 5. Rủi ro, Anti-patterns, Lỗi thường gặp

### 5.1 Critical Anti-patterns

| Anti-pattern | Tại sao nguy hiểm | Cách fix |
|--------------|-------------------|----------|
| **Hardcode key** | Source code leak = full compromise | Externalize to KMS/Vault |
| **ECB mode** | Pattern leak, no semantic security | Dùng GCM hoặc CBC+HMAC |
| **Static IV** | Ciphertext deterministic, leak information | Random IV per encryption |
| **Skip auth tag verification** | Active attacks (tampering) | Always verify GCM tag |
| **Weak PRNG** | Predictable keys | `SecureRandom`, never `Random` |
| **Key in logs** | Audit trail exposure | Redact keys in MDC/logging |

### 5.2 Java-Specific Pitfalls

```java
// ❌ SAI: String cho sensitive data
String password = request.getPassword(); // Immutable, GC không xóa ngay

// ✅ ĐÚNG: char[] hoặc byte[] có thể clear
char[] password = request.getPassword();
try {
    // process
} finally {
    Arrays.fill(password, '\0'); // Cryptographic erasure
}

// ❌ SAI: Không verify GCM tag
byte[] decrypted = cipher.doFinal(ciphertext); // Có thể tamper

// ✅ ĐÚNG: GCM tự động verify, nhưng catch AEADBadTagException
try {
    byte[] decrypted = cipher.doFinal(ciphertext);
} catch (AEADBadTagException e) {
    // Authentication failed - potential attack
    throw new SecurityException("Data integrity check failed");
}
```

### 5.3 Key Rotation Failure Modes

| Scenario | Rủi ro | Giải pháp |
|----------|--------|-----------|
| **Emergency rotation** | Data inaccessible nếu old key destroyed | Soft delete, retention policy |
| **Gradual rotation** | Multiple key versions active | Version metadata với ciphertext |
| **Cross-region** | Key sync lag | Eventual consistency, retry logic |

### 5.4 HSM Operational Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| **HSM failure** | Cannot decrypt data | HSM cluster, backup KEK offline |
| **Network partition** | Service unavailable | Local crypto fallback (cache KEK) |
| **Latency spike** | Application slowdown | Connection pooling, async ops |
| **Cost explosion** | Per-operation billing | Batch operations, caching |

---

## 6. Khuyến nghị Thực chiến Production

### 6.1 Java Implementation Guidelines

```java
// 1. Algorithm selection - AES-256-GCM
cipher = Cipher.getInstance("AES/GCM/NoPadding", "BC");

// 2. Random IV (96-bit cho GCM)
byte[] iv = new byte[12];
SecureRandom.getInstanceStrong().nextBytes(iv);

// 3. Explicit tag length
cipher.init(Cipher.ENCRYPT_MODE, key, 
    new GCMParameterSpec(128, iv)); // 128-bit auth tag

// 4. Associated Data (context binding)
cipher.updateAAD(aad); // Prevent context confusion attacks
```

### 6.2 Key Management Best Practices

| Practice | Implementation |
|----------|----------------|
| **Key hierarchy** | Master → KEK → DEK (3 levels) |
| **Rotation schedule** | KEK: 90 days, DEK: per-data hoặc 1 year |
| **Key versioning** | Include version in ciphertext metadata |
| **Access logging** | Log tất cả HSM/KMS operations |
| **Least privilege** | Service account chỉ có quyền unwrap, không export |

### 6.3 Observability

```java
// Metrics cần track
micrometer.counter("crypto.encrypt", "algorithm", "AES-256-GCM");
micrometer.counter("crypto.decrypt", "algorithm", "AES-256-GCM");
micrometer.timer("crypto.latency", "operation", "encrypt");
micrometer.gauge("crypto.dek_cache.size", cache::size);

// Alerts
- HSM latency > 100ms p99
- Encryption failure rate > 0.1%
- KEK rotation approaching deadline
```

### 6.4 Compliance Mapping

| Standard | Yêu cầu | Implementation |
|----------|---------|----------------|
| **PCI DSS** | Encrypt PAN at rest/transit | AES-256, HSM for key storage |
| **GDPR** | Pseudonymization | Field-level encryption |
| **HIPAA** | ePHI protection | mTLS + at-rest encryption |
| **SOC 2** | Key access audit | Comprehensive audit logging |

---

## 7. Kết luận

### Bản chất vấn đề

Bảo vệ dữ liệu không phải là "mã hóa và xong" - đó là hệ thống gồm:

1. **Envelope encryption** - Phân tách DEK/KEK để scale và rotate
2. **AEAD algorithms** - AES-GCM hoặc ChaCha20-Poly1305 (authenticated)
3. **HSM for KEK** - Keys không bao giờ rời khỏi tamper-resistant hardware
4. **Defense in depth** - mTLS + app-level encryption cho sensitive fields

### Trade-off chính

| Chiều | Ưu tiên khi... |
|-------|----------------|
| Security → | Compliance requirement (PCI, FIPS), high-value data |
| Performance → | High-throughput, latency-sensitive, cost-constrained |
| Simplicity → | Startup, rapid iteration, limited security team |

### Quyết định thiết kế

> **80% use case:** AWS KMS + AES-256-GCM + mTLS (service mesh)

> **High-security:** Cloud HSM (FIPS 140-2 L3) + field-level encryption + comprehensive audit

> **Performance-critical:** Local KEK caching (encrypted) + batch HSM operations

---

## 8. Reference

- NIST SP 800-175B: Guideline for Using Cryptographic Standards
- FIPS 140-3: Security Requirements for Cryptographic Modules
- AWS KMS Best Practices
- Java Cryptography Architecture (JCA) Reference Guide
- Bouncy Castle FIPS Documentation
