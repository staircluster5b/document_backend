# Quantum-Ready Cryptography Preparation

## 1. Mục tiêu của Task

Hiểu sâu mối đe dọa từ máy tính lượng tử (Quantum Computing) đối với hệ thống mã hóa hiện tại, nắm vững các thuật toán Post-Quantum Cryptography (PQC) được NIST chuẩn hóa, và xây dựng chiến lược migration có hệ thống từ RSA/ECC sang PQC trong môi trường enterprise mà không gây gián đoạn dịch vụ.

> **Bottom line:** Máy tính lượng tử đủ mạnh sẽ phá vỡ RSA-2048 và ECC P-256 trong vòng 10-15 năm. Dữ liệu được mã hóa ngày hôm nay có thể bị lưu trữ và giải mã sau (Harvest Now, Decrypt Later). Hành động cần bắt đầu **ngay bây giờ**.

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1. Quantum Threat Model: Tại sao RSA/ECC sụp đổ?

Các thuật toán mã hóa hiện tại dựa trên **bài toán toán học khó**:

| Algorithm | Hard Problem | Classical Complexity | Quantum Complexity |
|-----------|-------------|---------------------|-------------------|
| RSA | Integer Factorization | Sub-exponential | **Polynomial** (Shor's) |
| ECC (ECDSA/ECDH) | Discrete Log trên Curve | Exponential | **Polynomial** (Shor's) |
| AES-256 | Brute force | Exponential | Quadratic speedup (Grover's) → cần AES-512 tương đương |
| SHA-256 | Collision/Preimage | Exponential | Quadratic speedup (Grover's) → cần SHA-512 tương đương |

**Shor's Algorithm (1994):** Thuật toán lượng tử giải bài toán factorization và discrete log trong thờ gian đa thức. Với ~4000 logical qubits ổn định, RSA-2048 sụp đổ trong vài giờ.

**Grover's Algorithm (1996):** Tăng tốc tìm kiếm không có cấu trúc, giảm độ phức tạp từ O(N) xuống O(√N). AES-256 trở nên tương đương AES-128 về độ an toàn.

> **Key insight:** Symmetric cryptography (AES, SHA) chỉ cần **double key size** để chống lại quantum. Asymmetric cryptography (RSA, ECC, DSA, Diffie-Hellman) cần **thuật toán hoàn toàn mới**.

### 2.2. NIST PQC Standardization: 8 năm, 82 submissions, 4 winners

NIST bắt đầu quá trình chuẩn hóa PQC từ 2016, hoàn thành vòng 1 vào tháng 8/2024:

#### **Primary Algorithms (đã chuẩn hóa FIPS)**

| Algorithm | Type | Security Level | Use Case | Core Hard Problem |
|-----------|------|---------------|----------|-------------------|
| **ML-KEM** (Kyber) | KEM (Key Encapsulation) | Level 5 (AES-256 equivalent) | Key exchange, TLS handshake | Module Learning With Errors (MLWE) |
| **ML-DSA** (Dilithium) | Digital Signature | Level 3 (AES-192 equivalent) | Code signing, certificates, TLS auth | Module Learning With Errors |
| **SLH-DSA** (SPHINCS+) | Digital Signature | Level 1-5 | High-assurance, stateless | Hash-based signatures |
| **FN-DSA** (FALCON) | Digital Signature | Level 5 | Resource-constrained devices | Short integer solution over NTRU lattices |

#### **Candidate Algorithms (under consideration)**

- **BIKE, HQC, Classic McEliece**: Alternative KEMs với trade-offs khác nhau
- **XMSS, LMS**: Stateful hash-based signatures (đã chuẩn hóa RFC 8391)

### 2.3. Lattice-Based Cryptography: Cơ chế bên trong ML-KEM/ML-DSA

Cả ML-KEM (Kyber) và ML-DSA (Dilithium) đều dựa trên **Lattice problems** - cấu trúc đại số trừu tượng trong không gian nhiều chiều.

#### Module Learning With Errors (MLWE)

```
Bản chất: Tìm vector s ngắn trong lattice L(A) = {Ax : x ∈ Z_q^n}

Dữ kiện: A ∈ R_q^{k×k} (public matrix), t = A·s + e (public vector)
          s, e là vectors "nhỏ" (small error terms)
          
Bài toán: Từ (A, t), tính toán s là NP-hard trong worst-case

An toàn quantum: Không có thuật toán lượng tử nào giải MLWE 
                 với advantage đáng kể trong thờ gian đa thức
```

**Tại sao lattice-based an toàn trước quantum:**
- Shor's algorithm yêu cầu cấu trúc nhóm cyclic (như Z_p*)
- Lattice problems không có cấu trúc nhóm như vậy
- Các bài toán lattice (SVP, CVP, SIVP) vẫn hard cho cả classical và quantum algorithms

**Trade-offs của lattice-based:**
| Pros | Cons |
|------|------|
| Key/signature sizes nhỏ hơn other PQC | Vẫn lớn hơn RSA/ECC (~1.5-3KB vs 256-512 bytes) |
| Fast operations (polynomial multiplication via NTT) | Chưa được cryptanalyzed lâu dài như RSA |
| Flexible security levels | Implementation timing side-channels |
| Underlying hardness well-studied | Patent concerns (NTRU history) |

---

## 3. Kiến Trúc và Chiến Lược Migration

### 3.1. Cryptographic Agility: Nền tảng của Quantum-Ready Architecture

> **Definition:** Khả năng thay đổi thuật toán mã hóa mà không ảnh hưởng đến ứng dụng - giống như thay đổi engine mà không phải đóng cửa nhà máy.

#### Anti-Pattern: Hard-coded Cryptography

```java
// ❌ WRONG: Hard-coded algorithm, impossible to migrate
public byte[] signData(byte[] data) {
    Signature sig = Signature.getInstance("SHA256withRSA"); // Stuck forever
    sig.initSign(privateKey);
    sig.update(data);
    return sig.sign();
}

// ✅ RIGHT: Algorithm-agnostic with configuration
public byte[] signData(byte[] data, String algorithm) {
    Signature sig = Signature.getInstance(algorithm); // Configurable
    sig.initSign(privateKey);
    sig.update(data);
    return sig.sign();
}
```

#### Architecture Pattern: Crypto Provider Abstraction

```
┌─────────────────────────────────────────────────────────────┐
│                    Application Layer                        │
├─────────────────────────────────────────────────────────────┤
│              Crypto Service Interface                       │
│    (sign(), verify(), encapsulate(), decapsulate())         │
├─────────────────────────────────────────────────────────────┤
│  Classical Provider  │  Hybrid Provider  │  PQC Provider   │
│    (RSA/ECC)         │  (RSA+ML-KEM)     │  (ML-KEM/ML-DSA)│
├─────────────────────────────────────────────────────────────┤
│              JCA/JCE (Java Cryptography Architecture)       │
├─────────────────────────────────────────────────────────────┤
│  SunEC  │  BouncyCastle  │  BouncyCastle-PQC  │  AWS-LC    │
└─────────────────────────────────────────────────────────────┘
```

### 3.2. Hybrid Cryptography: Chiến lược migration an toàn

**Hybrid approach:** Kết hợp cả classical và PQC algorithms, một bên bị phá thì bên còn lại vẫn bảo vệ.

**Các chế độ hybrid:**

| Mode | Description | Use Case |
|------|-------------|----------|
| **Dual-signature** | Sign cùng dữ liệu bằng cả RSA/ECC và ML-DSA | Code signing, certificates |
| **Dual-KEM** | Encapsulate key cho cả classical và PQC, XOR kết quả | TLS 1.3 key exchange |
| **KEM-combiner** | KDF(key1 || key2) → shared secret | VPN, IPsec |

> **NIST Recommendation:** "Hybrid modes are recommended during transition period to hedge against unknown vulnerabilities in either algorithm family."

#### TLS 1.3 with Hybrid Key Exchange

```
ClientHello
├── supported_groups: secp256r1, X25519, Kyber768
├── key_share: [ECC ephemeral key], [Kyber768 public key]
└── signature_algorithms: ecdsa_secp256r1_sha256, ml-dsa-65

ServerHello
├── selected_group: hybrid (X25519Kyber768)
├── key_share: [ECC ephemeral key], [Kyber768 ciphertext]
└── certificate: Dual-signed (RSA + ML-DSA)

Shared Secret = KDF(ECDH_shared || Kyber_shared)
```

**Java Implementation (BouncyCastle):**

```java
// Hybrid KEM using BouncyCastle PQC
KeyPairGenerator ecKemGen = KeyPairGenerator.getInstance("X25519", "BC");
KeyPairGenerator pqKemGen = KeyPairGenerator.getInstance("Kyber768", "BCPQC");

KeyPair ecPair = ecKemGen.generateKeyPair();
KeyPair pqPair = pqKemGen.generateKeyPair();

// Encapsulation (client side)
KeyAgreement ecAgreement = KeyAgreement.getInstance("X25519", "BC");
ecAgreement.init(ecPair.getPrivate());

KEM kem = KEM.getInstance("Kyber768", "BCPQC");
KEM.Encapsulator encapsulator = kem.newEncapsulator(pqPair.getPublic());
KEM.Encapsulated encapsulated = encapsulator.encapsulate();

// Combined secret
byte[] combinedSecret = concat(ecAgreement.generateSecret(), 
                               encapsulated.key().getEncoded());
byte[] finalKey = hkdf.deriveKey(combinedSecret, "tls13 hybrid");
```

### 3.3. Migration Timeline và Phases

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    QUANTUM MIGRATION ROADMAP                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  2024─────2025─────2026─────2027─────2028─────2029─────2030─────Y2Q   │
│    │        │        │        │        │        │        │        │    │
│    ▼        ▼        ▼        ▼        ▼        ▼        ▼        ▼    │
│  ┌────┐  ┌────┐  ┌────┐  ┌────┐  ┌────┐  ┌────┐  ┌────┐  ┌────┐     │
│  │ P1 │→│ P2 │→│ P3 │→│ P4 │→│ P5 │→│ P6 │→│ P7 │→│ P8 │     │
│  └────┘  └────┘  └────┘  └────┘  └────┘  └────┘  └────┘  └────┘     │
│                                                                         │
│ P1: Inventory & Discovery (2024-2025)                                   │
│     - Catalog all crypto usage: TLS certs, API keys, DB encryption      │
│     - Identify hard-coded algorithms                                    │
│     - Assess data sensitivity & longevity                               │
│                                                                         │
│ P2: Crypto Agility Foundation (2025)                                    │
│     - Implement provider abstraction layer                              │
│     - Upgrade libraries (OpenSSL 3.x, BouncyCastle 1.78+)               │
│     - Deploy crypto inventory monitoring                                │
│                                                                         │
│ P3: Hybrid Deployment - Internal (2025-2026)                            │
│     - Internal services: hybrid TLS, dual-signed certs                  │
│     - Test performance impact (~10-20% latency increase)                │
│     - Train security teams on PQC                                       │
│                                                                         │
│ P4: External-Facing Hybrid (2026-2027)                                  │
│     - Customer-facing APIs: hybrid key exchange                         │
│     - Code signing: dual signatures                                     │
│     - Certificate chains: classical + PQC CA                            │
│                                                                         │
│ P5: PQC-First Policy (2027-2028)                                        │
│     - New systems: PQC-only (with classical fallback)                   │
│     - Legacy systems: maintain hybrid                                   │
│     - Cryptographic inventory: automated compliance                     │
│                                                                         │
│ P6: Classical Deprecation (2028-2029)                                   │
│     - Disable RSA < 3072, ECC < P-256                                   │
│     - Remove classical-only paths from critical systems                 │
│     - Emergency rollback procedures tested                              │
│                                                                         │
│ P7: PQC-Only Mode (2029-2030)                                           │
│     - Critical systems: PQC-only                                        │
│     - Legacy compatibility: isolated                                    │
│     - Continuous crypto-agility monitoring                              │
│                                                                         │
│ P8: Y2Q Ready (2030+)                                                   │
│     - Full PQC deployment                                               │
│     - Quantum-safe key management (QKD where applicable)                │
│     - Post-quantum PKI operational                                      │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 4. So Sánh Các Lựa Chọn

### 4.1. PQC Algorithm Comparison

| Algorithm | Type | Public Key | Secret Key | Signature | Security Level | Performance | Best For |
|-----------|------|-----------|-----------|-----------|---------------|-------------|----------|
| **ML-KEM-768** | KEM | 1,184 B | 2,400 B | N/A | 3 (AES-192) | Very Fast | TLS, VPN key exchange |
| **ML-KEM-1024** | KEM | 1,568 B | 3,168 B | N/A | 5 (AES-256) | Fast | High-security applications |
| **ML-DSA-65** | Sign | 1,952 B | 4,032 B | 3,293 B | 3 | Fast | General-purpose signing |
| **ML-DSA-87** | Sign | 2,592 B | 4,896 B | 4,595 B | 5 | Medium | High-assurance systems |
| **SLH-DSA-SHA2-128s** | Sign | 32 B | 64 B | 7,856 B | 1 | Slow | Minimal trust assumptions |
| **FN-DSA-512** | Sign | 897 B | 1,281 B | 666 B | 5 | Medium | Constrained devices |
| **RSA-2048** | Both | 256 B | 256 B | 256 B | Broken by quantum | Very Fast | Legacy only |
| **ECC P-256** | Both | 32 B | 32 B | 64 B | Broken by quantum | Very Fast | Legacy only |

**Key Observations:**
- PQC public keys lớn hơn 10-50x so với ECC
- PQC signatures lớn hơn 50-100x so với ECDSA
- ML-KEM/ML-DSA nhanh về computation nhưng cần nhiều bandwidth
- SLH-DSA rất chậm (~1000x slower than ECDSA) nhưng minimal assumptions

### 4.2. Library Support Matrix

| Library | ML-KEM | ML-DSA | SLH-DSA | Hybrid TLS | Status |
|---------|--------|--------|---------|------------|--------|
| **OpenSSL 3.4+** | ✅ | ✅ | ✅ | ✅ (provider) | Production-ready |
| **BouncyCastle 1.78+** | ✅ | ✅ | ✅ | ✅ | Production-ready |
| **AWS-LC** | ✅ | ✅ | ❌ | ✅ | Optimized for AWS |
| **WolfSSL 5.7+** | ✅ | ✅ | ❌ | ✅ | Embedded/IoT |
| **Java 23 (JEP 496)** | ✅ | ✅ | ✅ | ❌ (planned) | JDK native |
| **Go 1.24+** | ✅ (x/crypto) | ✅ | ✅ | ❌ | Experimental |
| **Rust pqcrypto** | ✅ | ✅ | ✅ | ✅ (third-party) | Community |

---

## 5. Rủi Ro, Anti-Patterns, và Lỗi Thường Gặp

### 5.1. High-Risk Anti-Patterns

#### ❌ Anti-Pattern 1: "Harvest Now, Decrypt Later" Blindness

```
Sai lầm: "Dữ liệu của chúng tôi không quan trọng sau 5 năm"

Thực tế: 
- Hồ sơ y tế: vĩnh viễn
- Bí mật thương mại: 10-20 năm
- Financial records: 7-10 năm (regulatory)
- Personal data: lifetime

Mọi dữ liệu được mã hóa today bằng RSA/ECC có thể bị lưu lại 
và giải mã khi quantum computer đủ mạnh.
```

#### ❌ Anti-Pattern 2: PQC Algorithm Substitution Without Review

```java
// Sai lầm: Chỉ đơn giản thay thế algorithm string
Signature.getInstance("ML-DSA-65"); // Thay cho "SHA256withRSA"

// Vấn đề:
// 1. Key sizes khác nhau - buffer overflows
// 2. Signature sizes khác nhau - database schema changes
// 3. Performance characteristics khác - DoS vectors
// 4. Randomness requirements khác - entropy exhaustion
```

#### ❌ Anti-Pattern 3: Side-Channel Ignorance

Lattice-based algorithms (ML-KEM, ML-DSA) dễ bị tấn công:
- **Timing attacks:** Polynomial multiplication không constant-time
- **Power analysis:** NTT butterfly operations leak thông tin
- **Fault injection:** Bit-flips trong decryption cho biết secret key

**Mitigation:**
- Chỉ dùng validated implementations (NIST CAVP)
- Constant-time arithmetic (dùng libraries như PQClean)
- Side-channel testing trong CI/CD

#### ❌ Anti-Pattern 4: "Wait and See" Strategy

```
Rủi ro của việc chờ đợi:

2026: NIST standards finalized ✓
2027: Major vendors support PQC ✓
2028: Your competitor deploys hybrid TLS
2029: Regulatory requirements (EU, US)
2030: "Q-Day" - quantum computer breaks RSA

Bạn còn: 2 năm để migrate toàn bộ hệ thống
Reality: Migration enterprise crypto takes 5-7 years minimum

Kết quả: Miss the boat, breach exposure
```

### 5.2. Production Failures

| Failure Mode | Root Cause | Detection | Mitigation |
|--------------|------------|-----------|------------|
| **TLS handshake failures** | Client không support PQC | Connection logs, error rates | Fallback to classical, monitoring |
| **Certificate bloat** | Dual-signed certs 3x size | Latency increase, timeouts | CDN optimization, OCSP stapling |
| **Memory exhaustion** | PQC keys 10x larger | OOM errors, GC pressure | Heap sizing, off-heap storage |
| **Signature verification DoS** | PQC verify slower | CPU spikes, latency | Rate limiting, async verification |
| **Incompatibility** | Hard-coded algorithm assumptions | Integration test failures | Crypto agility testing |

---

## 6. Khuyến Nghị Thực Chiến trong Production

### 6.1. Immediate Actions (2025)

#### 1. Cryptographic Inventory

```bash
# Sử dụng công cụ để scan codebase
cryptochecker scan --lang=java --output=inventory.json

# Output cần bao gồm:
# - All getInstance() calls với algorithm names
# - Certificate types và key sizes
# - Hard-coded keys (rotation needed)
# - TLS configuration
# - Database encryption schemes
```

#### 2. Library Upgrades

```xml
<!-- pom.xml - BouncyCastle PQC -->
<dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcprov-jdk18on</artifactId>
    <version>1.79</version> <!-- Latest with ML-KEM/ML-DSA -->
</dependency>
<dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcpqc-jdk18on</artifactId>
    <version>1.79</version>
</dependency>
```

#### 3. Configuration-Driven Algorithms

```yaml
# application.yml
crypto:
  kem:
    algorithm: ML-KEM-768      # or X25519Kyber768 (hybrid)
    fallback: ECDH             # classical fallback
  signature:
    algorithm: ML-DSA-65
    hybrid: true               # dual-sign with RSA/ECDSA
  tls:
    groups: [X25519Kyber768, X25519, secp256r1]
    sigalgs: [ecdsa_secp256r1_sha256, ml-dsa-65]
```

### 6.2. Architecture Decisions

#### Decision Matrix: Khi nào dùng gì?

| Scenario | Recommendation | Rationale |
|----------|---------------|-----------|
| **New system 2025** | Hybrid (PQC + Classical) | Hedge against unknowns |
| **High-value long-term data** | PQC-only encryption | HNDL threat model |
| **Public API endpoints** | Hybrid TLS | Max compatibility |
| **Internal microservices** | PQC-first with fallback | Controlled environment |
| **Code signing** | Dual signatures (ECDSA + ML-DSA) | Gradual transition |
| **Resource-constrained IoT** | FN-DSA or wait | Size/performance constraints |
| **Post-Y2Q system** | PQC-only | Quantum-safe |

#### Performance Optimization

```java
// Thread-local key pair cache cho high-throughput
public class PQCCryptoProvider {
    private static final ThreadLocal<KeyPair> KYBER_KEY_CACHE = 
        ThreadLocal.withInitial(() -> {
            try {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("Kyber768", "BCPQC");
                return gen.generateKeyPair();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    
    // Key pairs có thể tái sử dụng cho ephemeral KEM
    // Cải thiện 10x throughput trong benchmarks
}
```

### 6.3. Monitoring và Observability

```yaml
# Prometheus metrics
crypto_pqc_enabled{algorithm="ML-KEM-768"} 1
crypto_handshake_duration_seconds{type="hybrid"} 0.045
crypto_fallback_total{reason="client_no_pqc"} 127
crypto_certificate_size_bytes{type="dual-signed"} 4892
```

**Critical Alerts:**
- `crypto_fallback_rate > 1%` - Clients không support PQC tăng đột biến
- `crypto_handshake_p99 > 500ms` - Performance regression
- `crypto_version{algo="RSA"} < 3072` - Weak keys detected

### 6.4. Testing Strategy

```java
@Test
public void testCryptoAgility() {
    // Test tất cả supported algorithms
    String[] algorithms = {"ML-KEM-768", "ML-DSA-65", "ECDH", "ECDSA"};
    
    for (String alg : algorithms) {
        CryptoProvider provider = new CryptoProvider(alg);
        assertTrue(provider.isAvailable());
        assertNotNull(provider.generateKeyPair());
    }
}

@Test
public void testHybridCompatibility() {
    // Test hybrid mode
    HybridKEM hybrid = new HybridKEM("X25519", "Kyber768");
    KeyPair clientKey = hybrid.generateKeyPair();
    
    // Verify both components work
    assertNotNull(hybrid.getClassicalSharedSecret());
    assertNotNull(hybrid.getPQCSharedSecret());
    assertNotEquals(hybrid.getClassicalSharedSecret(), 
                    hybrid.getPQCSharedSecret());
}
```

---

## 7. Kết Luận

### Bản Chất Vấn Đề

Máy tính lượng tử không phải là "có thể" mà là "khi nào". Shor's algorithm đã chứng minh RSA/ECC sẽ sụp đổ. Câu hỏi duy nhất là timeline - và mọi dự đoán đều cho thấy **đã quá muộn để bắt đầu từ năm 2030**.

**Ba điểm then chốt:**

1. **Harvest Now, Decrypt Later là real threat.** Dữ liệu mã hóa ngày hôm nay có giá trị trong 10-20 năm tới. Nếu chứa secrets quan trọng, cần PQC encryption **ngay bây giờ**.

2. **Cryptographic agility là prerequisite, không phải nice-to-have.** Không thể migration hàng nghìn services nếu algorithms bị hard-coded. Investment vào abstraction layer sẽ trả dividend trong 10 năm tới.

3. **Hybrid là chiến lược chuyển đổi an toàn nhất.** Kết hợp PQC + classical trong transition period - một bên bị phá thì bên còn lại vẫn đứng vững.

### Hành Động Tiếp Theo

| Priority | Action | Timeline | Owner |
|----------|--------|----------|-------|
| P0 | Crypto inventory scan | Q2 2025 | Security Team |
| P0 | Upgrade BouncyCastle/OpenSSL | Q2 2025 | Platform Team |
| P1 | Implement crypto abstraction layer | Q3 2025 | Architecture |
| P1 | Pilot hybrid TLS on internal services | Q4 2025 | SRE |
| P2 | External-facing hybrid deployment | 2026 | Product Teams |
| P2 | Crypto agility testing in CI/CD | 2026 | QA |
| P3 | PQC-first policy for new systems | 2027 | Architecture |
| P3 | Classical deprecation roadmap | 2028 | CISO |

---

> **"Cryptography doesn't get broken, it gets circumvented. The only thing worse than no security is a false sense of security."**
> 
> — Adapting from Bruce Schneier, applied to PQC transition

**Bottom line:** Bắt đầu ngày hôm nay. Càng chờ đợi, chi phí và rủi ro càng tăng theo cấp số nhân.
