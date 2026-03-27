# Audit Trail & Non-Repudiation: Kiến trúc Ghi nhận và Chứng minh Toàn vẹn Dữ liệu

## 1. Mục tiêu của Task

Hiểu sâu cơ chế đảm bảo tính **bất biến (immutability)** và **chống chối bỏ (non-repudiation)** trong hệ thống enterprise, đặc biệt trong ngữ cảnh tài chính-ngân hàng và regulatory compliance. Tập trung vào:

- Bản chất cryptographic của tamper-evident systems
- Kiến trúc lưu trữ log bất biến ở quy mô lớn
- Cơ chế chứng minh toàn vẹn dữ liệu trước regulatory bodies
- Trade-off giữa security, performance, và operational complexity

---

## 2. Bản chất và Cơ chế hoạt động

### 2.1 Tamper-Evident vs Tamper-Proof

| Thuộc tính | Tamper-Evident | Tamper-Proof |
|-----------|----------------|--------------|
| **Định nghĩa** | Phát hiện được khi dữ liệu bị sửa đổi | Ngăn chặn hoàn toàn việc sửa đổi |
| **Cơ chế** | Cryptographic hashes, chained signatures | Hardware security modules, air-gapped systems |
| **Chi phí** | Thấp - Medium | Cao (chuyên biệt) |
| **Use case** | Audit logs, transaction records | Root keys, certificate authorities |

> **Quan trọng:** Hầu hết hệ thống enterprise chỉ cần **tamper-evident**, không cần tamper-proof. Việc phát hiện sửa đổi thường đủ để đáp ứng regulatory requirements.

### 2.2 Cryptographic Foundation

#### Hash Chaining (Merkle Tree + Chain)

```
Block N:    [Data_N] + [Hash(Data_N)] + [Hash(Block_N-1)]
                ↓
Block N+1:  [Data_N+1] + [Hash(Data_N+1)] + [Hash(Block_N)]
                ↓
Block N+2:  [Data_N+2] + [Hash(Data_N+2)] + [Hash(Block_N+1)]
```

**Cơ chế bảo vệ:**
- Mỗi block chứa hash của block trước đó
- Sửa đổi bất kỳ block nào làm đứt chuỗi hash
- Verifier có thể detect bằng cách re-compute hash chain

#### Cryptographic Accumulators (Alternative to Merkle)

| Phương pháp | Đặc điểm | Use case |
|-------------|----------|----------|
| **Merkle Tree** | O(log n) proof size, dễ parallelize | Blockchain, certificate transparency |
| **RSA Accumulator** | Constant-size proof, dynamic set | Revocation lists, membership proofs |
| ** bilinear Accumulator** | Shorter proofs, quantum-resistant prep | Next-gen systems |
| **Bloom Filter + Hash** | Probabilistic, space-efficient | Cache, preliminary filtering |

### 2.3 Cơ chế Timestamping Độc lập (Trusted Timestamping)

**Vấn đề:** Hash chaining chỉ đảm bảo thứ tự, không đảm bảo thờ gian thực.

**Giải pháp:**
1. **RFC 3161 Timestamp Authority (TSA)**
   - Third-party timestamp từ trusted authority
   - Mã hóa hash của data + thờ gian bằng private key của TSA
   - Có thể verify bằng public key của TSA

2. **Decentralized Timestamp (Blockchain anchoring)**
   - Ghi hash của Merkle root lên public blockchain (Bitcoin, Ethereum)
   - Không phụ thuộc vào single trusted party
   - Chi phí cao hơn, latency cao hơn

3. **Hybrid Approach**
   - Local Merkle tree + periodic blockchain anchoring
   - Balances between cost và independence

---

## 3. Kiến trúc Hệ thống Audit Trail

### 3.1 Multi-Layer Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    INGESTION LAYER                          │
│  (Log shippers: Filebeat, Fluentd, Vector)                  │
│  → Normalization → Enrichment → Signing                     │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                   STREAMING LAYER                           │
│  (Kafka/Kinesis - append-only, partitioned)                 │
│  → Partition by entity (account_id, transaction_id)         │
│  → Retention: hot (7d) → warm (90d) → cold (years)         │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                  IMMUTABLE STORAGE                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Hot Index    │  │ Warm Storage │  │ Cold Archive │      │
│  │ (Elasticsearch)│  │ (S3/GCS)    │  │ (Glacier/     │      │
│  │ + daily hash │  │ + Merkle tree│  │ Tape + hash)  │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│              PROOF GENERATION & VERIFICATION                │
│  → Merkle proofs cho individual records                     │
│  → Periodic aggregate signatures (TSA/blockchain)           │
│  → Query API cho auditors (read-only, audited access)       │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 Write-Once-Read-Many (WORM) Implementation

**Cơ chế đảm bảo WORM ở từng layer:**

| Layer | Cơ chế WORM | Verification |
|-------|-------------|--------------|
| **Application** | Append-only event sourcing | Event store version check |
| **Message Queue** | Kafka log retention, min.insync.replicas | Log segment CRC |
| **Object Storage** | S3 Object Lock (Compliance mode), GCS retention policies | Object checksums |
| **Database** | Append-only tables, insert-only permissions | Row-level hash + audit trigger |

### 3.3 Segregation of Duties trong Key Management

```
┌────────────────────────────────────────────────────────────┐
│                    KEY HIERARCHY                          │
├────────────────────────────────────────────────────────────┤
│  Master Key (HSM-protected)                               │
│       ↓                                                    │
│  Data Encryption Keys (DEKs) - rotated monthly            │
│       ↓                                                    │
│  Signing Keys - separated by function:                    │
│    ├─ ingestion_sign_key (application)                   │
│    ├─ archive_sign_key (batch process)                   │
│    └─ proof_sign_key (auditor access)                    │
└────────────────────────────────────────────────────────────┘
```

> **Nguyên tắc:** Keys dùng để sign logs phải khác keys dùng để encrypt data. Nếu cùng một team có cả encryption và signing keys, họ có thể tamper logs rồi re-sign.

---

## 4. So sánh Các Lựa chọn Triển khai

### 4.1 Database-Centric vs Log-Centric

| Tiêu chí | Database-Centric | Log-Centric |
|----------|-----------------|-------------|
| **Ví dụ** | PostgreSQL + pgaudit, CockroachDB | Kafka + S3 + Trino |
| **Query pattern** | SQL, random access | Sequential scan, time-range |
| **Tamper evidence** | Row-level hash, WAL archiving | Immutable log segments |
| **Retention** | Complex partitioning | Natural log rotation |
| **Compliance** | SOC2, PCI-DSS | Stronger cho e-discovery |
| **Chi phí storage** | Cao (index overhead) | Thấp (compressed Parquet) |
| **Use case** | Financial transactions, compliance DB | Audit logs, event sourcing |

### 4.2 On-Premise vs Cloud-Native Audit

| Yếu tố | On-Premise | Cloud-Native (AWS) |
|--------|-----------|-------------------|
| **Immutable storage** | WORM tapes, append-only NAS | S3 Object Lock, Glacier Vault Lock |
| **Key management** | HSM (Thales, SafeNet) | AWS KMS + CloudHSM |
| **Timestamp** | Internal TSA | AWS Timestamp Authority |
| **Backup** | Offsite tapes | Cross-region replication |
| **Regulatory** | Full control, audit physical access | Shared responsibility, rely on provider attestations |
| **Chi phí CAPEX** | Cao | Chuyển sang OPEX |

### 4.3 Commercial vs Open-Source Solutions

| Solution | Loại | Điểm mạnh | Điểm yếu |
|----------|------|----------|----------|
| **Splunk** | Commercial | Mature, rich analytics | Chi phí cao theo data volume |
| **Chronicle (Google)** | Commercial | Unlimited retention, security focus | Vendor lock-in |
| **Elastic Security** | Open/Comm | Flexible, good query | Self-managed complexity |
| **Sigstore** | Open Source | Supply chain transparency | Emerging, limited enterprise support |
| **Trillian** | Open Source | Google's CT implementation | Requires customization |
| **Immutable Storage (MinIO)** | Open Source | S3-compatible WORM | Self-hosted operational burden |

---

## 5. Rủi ro, Anti-patterns, và Lỗi thường gặp

### 5.1 Critical Failure Modes

#### 1. **Hash Chain Breakage (Silent Corruption)**
```
Nguyên nhân: Storage corruption, bit rot, network errors
Impact: Tất cả subsequent proofs trở nên invalid
Phát hiện: Periodic integrity checks (scrubbing)
Mitigation: Erasure coding, replication, frequent checkpointing
```

#### 2. **Key Compromise**
```
Nguyên nhân: Signing key leaked, insider threat
Impact: Attacker có thể forge valid-looking logs
Mitigation: 
- Key rotation với overlapping validity
- Multi-signature (M-of-N)
- Hardware-backed keys (HSM)
```

#### 3. **Clock Skew trong Distributed Systems**
```
Nguyên nhân: NTP issues, VM clock drift
Impact: Log entries có thờ gian sai, audit trail inconsistencies
Phát hiện: Monotonic clock checks, cross-node timestamp validation
Mitigation: 
- TrueTime-like APIs (Spanner)
- Logical clocks (Lamport, vector clocks) kết hợp physical timestamps
- Accept clock uncertainty trong proofs
```

### 5.2 Anti-patterns Cần Tránh

| Anti-pattern | Tại sao nguy hiểm | Cách làm đúng |
|--------------|-------------------|---------------|
| **"Hash ở application layer"** | Attacker compromise app có thể hash fake data | Hash ở independent logging service, separate credentials |
| **"Shared signing key"** | Không trace được ai sign, insider threat | Per-service keys, key rotation audit |
| **"Log aggregation trước khi sign"** | Mất granularity, không prove được individual records | Sign tại source, verify tại aggregate |
| **"Trust NTP hoàn toàn"** | Clock manipulation attacks | Use redundant time sources, logical sequencing |
| **"Delete old logs để tiết kiệm cost"** | Regulatory violation, không prove historical state | Tiered storage, automated archival, WORM policies |

### 5.3 Edge Cases trong E-Discovery

1. **Right to be Forgotten (GDPR) vs Immutability**
   - Xung đột: GDPR yêu cầu xóa, audit yêu cầu giữ
   - Giải pháp: Cryptographic deletion (destroy decryption keys), redaction markers với proof of redaction

2. **PII trong Audit Logs**
   - Hash PII trước khi log, hoặc tokenize
   - Maintain mapping table trong separate secured system

3. **Cross-Border Data Transfer**
   - Audit logs có thể chứa data subject to data residency
   - Geo-distributed audit infrastructure, local aggregation trước global analysis

---

## 6. Khuyến nghị Thực chiến trong Production

### 6.1 Implementation Checklist

#### Phase 1: Foundation (Weeks 1-4)
- [ ] Chọn cryptographic primitives (SHA-256/BLAKE3, ECDSA/Ed25519)
- [ ] Implement Merkle tree builder cho log batches
- [ ] Setup separated signing service với HSM-backed keys
- [ ] Define log schema với mandatory fields: timestamp, entity_id, hash_chain

#### Phase 2: Hardening (Weeks 5-8)
- [ ] Implement tamper detection daemon (periodic integrity scans)
- [ ] Setup multi-region replication với conflict detection
- [ ] Integrate với external TSA hoặc blockchain anchoring
- [ ] Implement proof generation API cho auditors

#### Phase 3: Compliance (Weeks 9-12)
- [ ] Document evidence procedures cho regulatory audits
- [ ] Implement e-discovery query interface (read-only, query audit log)
- [ ] Setup automated retention policies (hot→warm→cold→archive)
- [ ] Train operations team với incident response procedures

### 6.2 Monitoring và Alerting

| Metric | Threshold | Action |
|--------|-----------|--------|
| `audit_log_lag_seconds` | > 30s | Alert - potential ingestion bottleneck |
| `hash_verification_failures` | > 0 | P0 Alert - potential tampering |
| `signing_key_age_days` | > 85 (of 90) | Rotate keys |
| `archive_storage_cost` | > budget | Optimize retention hoặc tiering |
| `proof_query_latency_p99` | > 5s | Optimize indices hoặc scale |

### 6.3 Regulatory Mapping

| Regulation | Audit Trail Requirements | Implementation Strategy |
|------------|-------------------------|------------------------|
| **SOX** | Immutable financial records, 7+ years retention | Database-level WORM, annual third-party attestation |
| **PCI-DSS** | Access logs, 1 year retention | Network + application logs, quarterly scans |
| **GDPR** | Processing records, accountability | Privacy-preserving logs, data subject request tracking |
| **MiFID II** | Transaction reporting, 5 years | Trade lifecycle audit, timestamp synchronization |
| **HIPAA** | Access to PHI audit, 6 years | PHI tokenization, access pattern analysis |

---

## 7. Kết luận

**Bản chất của Audit Trail & Non-Repudiation:**

> Không phải là ngăn chặn mọi sự tấn công, mà là **làm cho việc tấn công trở nên detectable và không đáng giá**.

**Các nguyên tắc then chốt:**

1. **Cryptographic chaining** (Merkle/hash) đảm bảo tính toàn vẹn có thể verify được
2. **Segregation of duties** trong key management ngăn insider threats
3. **External timestamping** cung cấp independent time attestation
4. **Tiered storage** balances cost với accessibility
5. **Tamper-evident > Tamper-proof** cho hầu hết use cases

**Trade-off quan trọng nhất:** Security guarantees đối lập với operational complexity và cost. Hệ thống "hoàn hảo" mà không ai dùng được hoặc quá đắt để maintain thì không có giá trị.

**Rủi ro lớn nhất:** Key compromise và clock manipulation. Cả hai đều có thể làm sụp đổ toàn bộ audit infrastructure nếu không có detection và mitigation đúng đắn.

---

## References

1. RFC 3161 - Internet X.509 Public Key Infrastructure Time-Stamp Protocol
2. NIST SP 800-92 - Guide to Computer Security Log Management
3. PCI-DSS v4.0 - Requirement 10: Log and Monitor Access
4. "Tamper-Evident Logging'' - Schneier, Kelsey (1998)
5. Certificate Transparency RFC 6962
6. Sigstore.dev - Software Supply Chain Security
7. AWS Well-Architected: Security Pillar - Logging và Audit
