# Cross-Border Payments & Settlement: Kiến Trúc Hệ Thống Thanh Toán Xuyên Biên Giới

## 1. Mục tiêu của Task

Hiểu sâu kiến trúc và cơ chế vận hành của hệ thống thanh toán xuyên biên giới, bao gồm:
- SWIFT gpi (global payments innovation) và mạng lưới ngân hàng đại lý (correspondent banking)
- Blockchain-based settlement (Ripple, Stellar, Central Bank Digital Currencies)
- Tuân thủ quy định AML/KYC trong bối cảnh đa quốc gia
- Hệ thống kiểm tra trừng phạt (sanctions screening)
- Quản lý rủi ro tỷ giá hối đoái (FX risk management)

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 SWIFT gpi: Sự Tiến Hóa từ "Black Box" sang Real-Time Tracking

**Bản chất vấn đề truyền thống:**
Trước gpi, chuyển khoản xuyên biên giới là "hộp đen": sau khi gửi MT103, ngân hàng gửi mất hoàn toàn visibility về trạng thái giao dịch cho đến khi nhận được confirmation hoặc inquiry. Thủ công, không real-time, không minh bạch về phí.

**Kiến trúc SWIFT gpi:**

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Ordering  │────▶│  Intermediary│────▶│  Intermediary│────▶│   Account   │
│   Customer  │     │   Bank A     │     │   Bank B     │     │   With Bank │
│  (Ngườigửi) │     │  (Ngân hàng  │     │  (Ngân hàng  │     │ (Ngân hàng  │
│             │     │   trung gian)│     │   trung gian)│     │  beneficiar)│
└─────────────┘     └──────┬──────┘     └──────┬──────┘     └─────────────┘
                           │                   │
                           ▼                   ▼
                    ┌─────────────────────────────────────┐
                    │         SWIFT gpi Tracker           │
                    │    (Cloud-based, ISO 20022)         │
                    │   - Real-time status updates        │
                    │   - Fee transparency ( deduction)   │
                    │   - End-to-end tracking (UETR)      │
                    └─────────────────────────────────────┘
```

**UETR (Unique End-to-end Transaction Reference):**
- UUID 36 ký tự, được generate bởi ordering institution
- Theo suốt vòng đờigiao dịch qua tất cả các banks trong chain
- Khác với reference number nội bộ từng bank - UETR là cross-institutional

**Cơ chế status update:**
Mỗi bank trong chain gửi `gpi payment status report` (camt.056/057) lên SWIFT gpi Cloud khi có event:
- ACSP (Accepted Settlement in Process)
- ACSC (Accepted Settlement Completed)
- RJCT (Rejected)
- BLCK (Blocked - often sanctions/compliance hold)

> **Lưu ý quan trọng:** gpi không thay đổi core settlement mechanism. Nó là **layer of visibility** trên nền correspondent banking truyền thống. Payment vẫn đi qua NOSTRO/VOSTRO accounts, vẫn có thể mất 1-5 ngày nếu chain dài.

### 2.2 Correspondent Banking Network: NOSTRO/VOSTRO và Liquidity Trap

**Bản chất NOSTRO/VOSTRO:**
- **NOSTRO account:** "Our account with you" - Tài khoản ngân hàng A mở tại ngân hàng B (thường ở currency của B)
- **VOSTRO account:** "Your account with us" - Tài khoản ngân hàng B mở tại ngân hàng A (từ góc nhìn của A)

**Cơ chế chuyển khoản xuyên biên giới qua correspondent banking:**

```
Ví dụ: Customer ở VN gửi USD đến Customer ở Đức

┌─────────────────┐         ┌─────────────────┐         ┌─────────────────┐
│   Vietcombank   │         │   JPMorgan      │         │   Deutsche Bank │
│   (VND/USD)     │────────▶│   (USD clearing)│────────▶│   (USD/EUR)     │
│                 │  MT103  │                 │  MT103  │                 │
│   [NOSTRO USD   │         │   [VOSTRO USD   │         │   [VOSTRO USD   │
│    tại JPM]     │         │    của VCB]     │         │    của JPM]     │
└─────────────────┘         └─────────────────┘         └─────────────────┘
        │                           │                           │
        │                           │                           │
        ▼                           ▼                           ▼
    Debit VCB                    Credit VCB                 Debit JPM
    NOSTRO @ JPM                 VOSTRO @ JPM               NOSTRO @ DB
                                                            (hoặc SEPA)
```

**Vấn đề thanh khoản (Liquidity Trap):**
Mỗi ngân hàng phải pre-fund NOSTRO accounts ở multiple currencies tại multiple correspondent banks:
- Opportunity cost: Tiền nằm trong NOSTRO không sinh lợi, chỉ để "chờ" thanh toán
- FX exposure: Nếu EUR trong NOSTRO tại Deutsche Bank mất giá so với functional currency
- Capital efficiency: Tier-1 banks có thể có hundreds of NOSTRO accounts globally

**Pre-funding optimization:**
Ngân hàng sử dụng various techniques:
- **Liquidity pooling:** Tổng hợp dự báo inflow/outflow để minimize required balance
- **Intraday overdraft:** Một số correspondent cho phép intraday credit (phải settle EOD)
- **CLS (Continuous Linked Settlement):** Netting system cho FX trades, giảm settlement risk

### 2.3 Blockchain-Based Settlement: Cuộc Cách Mạng Dở Dang

**Bản chất vấn đề:**
Correspondent banking là "hub-and-spoke" với Tier-1 banks là hubs. Blockchain hứa hẹn "mesh network" - peer-to-peer settlement không cần intermediaries.

**Ripple (RippleNet & ODL):**

```
Traditional Correspondent Banking:
Sender Bank ──▶ Correspondent A ──▶ Correspondent B ──▶ Receiver Bank
     (3-5 ngày, nhiều fees, không minh bạch)

Ripple ODL (On-Demand Liquidity):
Sender Bank ──▶ XRP Ledger ──▶ Receiver Bank
     (3-5 giây, source liquidity từ local markets, không cần NOSTRO)

Chi tiết flow:
1. Sender mua XRP trên local exchange (local fiat → XRP)
2. XRP chuyển cross-border trong 3-5 giây
3. Receiver bán XRP trên local exchange (XRP → local fiat)
```

**Trade-off của ODL:**
- **Pros:** Không cần pre-fund NOSTRO accounts, settlement near-instant, 24/7 operation
- **Cons:** XRP volatility risk (3-5 giây vẫn có thể di chuyển 1-2%), regulatory uncertainty, limited corridor liquidity

**Stellar (IBM World Wire):**
Tương tự Ripple nhưng:
- Focus vào developing markets và remittances
- Stablecoins là phương tiện chuyển giá trị chính (ít volatile hơn XRP)
- Hỗ trợ CBDC integration

**Central Bank Digital Currencies (CBDCs):**

```
Mô hình CBDC Cross-Border:

┌─────────────┐                    ┌─────────────┐
│  Bank of    │◀──────Bridge──────▶│   ECB       │
│  England    │    (mBridge,       │  (Digital   │
│   (Digital  │     Dunbar,        │    Euro)    │
│    Pound)   │     or bilateral)  │             │
└──────┬──────┘                    └──────┬──────┘
       │                                  │
       │ PvP (Payment vs Payment)         │
       │ settlement on common platform    │
       ▼                                  ▼
┌─────────────┐                    ┌─────────────┐
│ UK Business │───────────────────▶│ EU Business │
│   pays GBP  │    FX conversion   │  receives   │
│             │    atomic swap     │    EUR      │
└─────────────┘                    └─────────────┘
```

**Các dự án CBDC cross-border lớn:**
- **mBridge:** Trung Quốc, UAE, Thailand, Hong Kong + BIS - DLT-based common platform
- **Dunbar:** Australia, Malaysia, Singapore, South Africa + BIS - Multi-CBDC platform
- **Project Jura:** Banque de France + Swiss National Bank - Wholesale CBDC settlement

> **Lưu ý quan trọng:** CBDC cross-border vẫn ở giai đoạn pilot. Thách thức lớn nhất là **sovereignty concerns** - không central bank nào muốn cede control over monetary policy. Bridge models phải balance interoperability với sovereignty preservation.

---

## 3. Kiến Trúc và Luồng Xử Lý

### 3.1 High-Level Architecture của Hệ Thống Cross-Border Payment

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CLIENT FACING LAYER                                  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │  Internet   │  │   Mobile    │  │   Branch    │  │   API (Corporate)   │ │
│  │   Banking   │  │    App      │  │   System    │  │                     │ │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘ │
└─────────┼────────────────┼────────────────┼────────────────────┼────────────┘
          │                │                │                    │
          └────────────────┴────────────────┴────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                       PAYMENT INITIATION & VALIDATION                        │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │  Sanctions  │  │    KYC/     │  │   Amount    │  │   Schema Validation │ │
│  │  Screening  │  │   Identity  │  │   Limits    │  │   (ISO 20022)       │ │
│  │  (OFAC, EU, │  │   Check     │  │             │  │                     │ │
│  │   UN, HMT)  │  │             │  │             │  │                     │ │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘ │
└─────────┼────────────────┼────────────────┼────────────────────┼────────────┘
          │                │                │                    │
          └────────────────┴────────────────┴────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      ROUTING & FEE CALCULATION                               │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │   Routing   │  │   Corresp-  │  │    FX Rate  │  │   Fee Engine        │ │
│  │   Engine    │  │   ondent    │  │   Determin- │  │   (OUR/SHA/BEN)     │ │
│  │  (Shortest  │  │   Selection │  │   ation     │  │                     │ │
│  │   path,     │  │  (NOSTRO    │  │             │  │                     │ │
│  │   cost,     │  │   optimiza- │  │             │  │                     │ │
│  │   speed)    │  │   tion)     │  │             │  │                     │ │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘ │
└─────────┼────────────────┼────────────────┼────────────────────┼────────────┘
          │                │                │                    │
          └────────────────┴────────────────┴────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      SETTLEMENT & CLEARING LAYER                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │   SWIFT     │  │   RTGS      │  │   CLS       │  │   Blockchain        │ │
│  │   Network   │  │   Systems   │  │  (FX        │  │   (Ripple,          │ │
│  │  (MT/MX)    │  │  (TARGET2,  │  │   netting)  │  │   Stellar, CBDC)    │ │
│  │             │  │   CHIPS,    │  │             │  │                     │ │
│  │             │  │   CNAPS)    │  │             │  │                     │ │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘ │
└─────────┼────────────────┼────────────────┼────────────────────┼────────────┘
          │                │                │                    │
          └────────────────┴────────────────┴────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      RECONCILIATION & REPORTING                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │   NOSTRO/   │  │   gpi       │  │   Regulatory│  │   Customer          │ │
│  │   VOSTRO    │  │   Reconcile │  │   Reporting │  │   Confirmation      │ │
│  │   Reconcile │  │             │  │   (CB,      │  │                     │ │
│  │             │  │             │  │   FinCEN)   │  │                     │ │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Chi Tiết Luồng Xử Lý Giao Dịch

**Phase 1: Initiation (0-500ms)**
1. Validate message format (ISO 20022 pain.001 or MT103)
2. Check customer limits (daily, per-transaction)
3. Basic sanctions screening (real-time list check)
4. FX rate quote (if needed) - usually cached + spread

**Phase 2: Compliance (500ms - 2s)**
1. Enhanced sanctions screening (fuzzy matching, aliases)
2. KYC/CDD check (for new beneficiaries)
3. AML risk scoring (rule-based or ML model)
4. Documentation completeness check (purpose of payment)

**Phase 3: Routing (2-5s)**
1. Determine optimal correspondent path
2. Calculate fees (OUR/SHA/BEN)
3. Reserve liquidity (NOSTRO balance check)
4. Generate UETR (if gpi payment)

**Phase 4: Execution (5s - 5 days)**
1. Send MT103/MX message to next hop
2. Update gpi tracker (if applicable)
3. Wait for acknowledgment (MT199/MT299)
4. Handle exceptions (repair, return, inquiry)

**Phase 5: Settlement (varies)**
1. Debit ordering customer
2. Credit intermediary accounts (chain)
3. Final credit to beneficiary
4. Generate confirmation (camt.054)

---

## 4. So Sánh Các Giải Pháp

### 4.1 SWIFT gpi vs Blockchain Networks

| Criteria | SWIFT gpi | Ripple ODL | CBDC (mBridge) |
|----------|-----------|------------|----------------|
| **Settlement Time** | Hours to days | 3-5 seconds | Real-time |
| **Cost Structure** | Fixed + chain fees | Low, variable | Minimal |
| **Liquidity Model** | Pre-funded NOSTRO | On-demand | Direct central bank |
| **Availability** | Business hours | 24/7/365 | 24/7 (if DLT-based) |
| **Regulatory Status** | Established | Uncertain in many jurisdictions | Pilot/WIP |
| **Interoperability** | Universal (11,000+ banks) | Growing network | Bilateral/multilateral |
| **Finality** | Conditional (reversible) | Irreversible | Irreversible |
| **FX Transparency** | Limited (deducted fees) | Real-time quote | Real-time |

### 4.2 Correspondent Banking vs Direct Clearing

| Aspect | Correspondent Banking | Direct Clearing (CLS, bilateral) |
|--------|----------------------|----------------------------------|
| **Network Effect** | High - one connection reaches all | Low - need bilateral agreements |
| **Settlement Risk** | Herstatt risk present | PvP eliminates Herstatt risk |
| **Cost** | High (multiple fees) | Lower (direct relationship) |
| **Speed** | Slow (multi-hop) | Fast (direct) |
| **Complexity** | Low (standardized) | High (custom integration) |
| **Use Case** | Low-value, high-volume | High-value FX settlement |

---

## 5. Rủi Ro, Anti-Patterns và Lỗi Thường Gặp

### 5.1 Sanctions Screening Failures

**Anti-Pattern: "String Matching Naive"**
```java
// BAD - Chỉ check exact match
def isSanctioned(name: String): Boolean = {
  sanctionsList.contains(name)
}

// GOOD - Fuzzy matching với nhiều variations
// - Phonetic matching (Soundex, Metaphone)
// - Name parsing (first/middle/last, titles)
// - Transliteration (Arabic, Cyrillic, Chinese)
// - Entity resolution (corporate structures)
```

**Production Concern:**
- **False Positive Rate:** Có thể 5-10% giao dịch bị flag, gây delay và customer friction
- **Alert Fatigue:** Analysts bị overwhelm bởi volume, bỏ sót true positives
- **List Latency:** Sanctions list update real-time nhưng screening system cache có thể stale

### 5.2 FX Risk Management Anti-Patterns

**Anti-Pattern: "Natural Hedge Wishful Thinking"**
```
Tình huống: Ngân hàng có inflow EUR và outflow EUR cùng ngày
Sai lầm: "Tự nhiên hedge rồi, không cần action"
Thực tế: Timing mismatch 2 giờ có thể gây loss lớn trong biến động
```

**Anti-Pattern: "Rate Quote Stale"**
```java
// BAD - Cache FX rate quá lâu
@Cacheable(value = "fxRate", ttl = 3600) // 1 hour!
public BigDecimal getFXRate(String pair) { ... }

// GOOD - Short TTL + volatility adjustment
@Cacheable(value = "fxRate", ttl = 30) // 30 seconds
public BigDecimal getFXRate(String pair) {
    // Plus circuit breaker cho high volatility periods
}
```

### 5.3 Settlement và Liquidity Risks

**Herstatt Risk (Settlement Risk):**
```
Timeline:
09:00 VN: Ngân hàng A chuyển USD 10M cho ngân hàng B
09:05 US: Ngân hàng B phá sản (Herstatt Bank 1974)
09:10 VN: A đã debit, B không thể credit EUR
→ A mất 10M USD, không nhận được EUR

Mitigation: CLS Bank - Payment-versus-Payment (PvP)
→ Chỉ settle khi cả hai legs sẵn sàng, atomic transaction
```

**Liquidity Trap:**
- Over-optimization: Trying to minimize NOSTRO balances quá mức
- Intraday stress: Market volatility khiến expected inflows không đến
- Correspondent limit: Counterparty bank giảm credit limit đột ngột

### 5.4 Message Format và Schema Issues

**ISO 20022 Migration Challenges:**
```
MX (ISO 20022) vs MT (SWIFT proprietary):
- MT103: ~200 fields, flat structure
- pacs.008: 500+ fields, nested XML/JSON
- Parsing complexity tăng 5-10x
- Validation rules phức tạp hơn nhiều

Production Issue: "Schema version drift"
- Bank A dùng schema version 2019
- Bank B dùng schema version 2023
- Optional fields trở thành mandatory
→ Message bị reject giữa đường
```

---

## 6. Khuyến Nghị Thực Chiến trong Production

### 6.1 Kiến Trúc Microservices cho Payment Processing

```
Recommended Service Boundaries:

┌─────────────────────────────────────────────────────────────┐
│                    PAYMENT PLATFORM                          │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │  Initiation │  │  Compliance │  │   Routing Engine    │  │
│  │   Service   │  │   Service   │  │                     │  │
│  │             │  │             │  │   (Graph-based      │  │
│  │  - Validate │  │  - Sanctions│  │    pathfinding)     │  │
│  │  - Enrich   │  │  - AML      │  │                     │  │
│  │  - Rate     │  │  - KYC      │  │   - Cost optimizer  │  │
│  │    quote    │  │             │  │   - Speed optimizer │  │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │
│         │                │                    │             │
│         └────────────────┼────────────────────┘             │
│                          │                                  │
│                          ▼                                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              SETTLEMENT ORCHESTRATOR                │   │
│  │                                                     │   │
│  │   - Saga pattern cho multi-hop payments            │   │
│  │   - Compensation logic cho failures                │   │
│  │   - Idempotency handling                           │   │
│  └─────────────────────────────────────────────────────┘   │
│                          │                                  │
│          ┌───────────────┼───────────────┐                  │
│          ▼               ▼               ▼                  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │   SWIFT     │  │   RTGS      │  │ Blockchain  │         │
│  │  Connector  │  │  Connector  │  │  Connector  │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
└─────────────────────────────────────────────────────────────┘
```

### 6.2 Observability Requirements

**Metrics cần track:**
- **End-to-end latency:** P50, P95, P99 từ initiation đến confirmation
- **Settlement success rate:** Theo corridor, currency, correspondent
- **Sanctions hit rate:** False positive ratio
- **NOSTRO utilization:** % của balance được sử dụng thực sự
- **FX slippage:** Chênh lệch giữa quoted rate và actual settlement rate

**Distributed Tracing:**
- Propagate UETR qua tất cả services
- Correlation với SWIFT gpi tracker events
- Trace qua correspondent banks (nếu có visibility)

### 6.3 Resilience Patterns

**Circuit Breaker cho Correspondent Connections:**
```java
// Nếu correspondent bank reject rate > 20%, open circuit
// Fall back to alternative route
@CircuitBreaker(name = "correspondentJPM", fallbackMethod = "fallbackToCiti")
public PaymentResult sendViaJPM(Payment payment) { ... }
```

**Saga Pattern cho Multi-Hop:**
```java
// Nếu payment failed ở hop thứ 3, compensate hop 1 và 2
@Saga
public class CrossBorderPaymentSaga {
    
    @StartSaga
    @SagaEventHandler(associationProperty = "paymentId")
    public void handle(InitiatedEvent event) {
        // Step 1: Debit sender
    }
    
    @SagaEventHandler(associationProperty = "paymentId")
    @EndSaga
    public void on(BeneficiaryCreditedEvent event) {
        // Success path
    }
    
    @SagaEventHandler(associationProperty = "paymentId")
    public void on(PaymentFailedEvent event) {
        // Compensate: Credit sender back
    }
}
```

### 6.4 Data Consistency và Idempotency

**Idempotency Key Strategy:**
```
Client-generated idempotency key: hash(senderAccount + beneficiaryAccount + amount + timestamp + sequence)
Store: idempotency key → payment status mapping
TTL: 24-48 hours (SWIFT inquiry window)

Edge case: Client retry với cùng key nhưng different amount
→ Reject as "duplicate with different payload"
```

**Event Sourcing cho Payment State:**
```
Payment không phải là single state mà là log of events:
- Initiated
- SanctionsChecked
- Routed
- SentToCorrespondent
- Acknowledged
- Settled
- Confirmed

Benefits:
- Audit trail hoàn chỉnh cho regulators
- Replay capability cho debugging
- Multiple read models (gpi tracker, customer view, reconcile view)
```

---

## 7. Kết Luận

**Bản chất cốt lõi:**

Cross-border payment là bài toán **trust minimization** và **information asymmetry reduction** trong môi trường multi-jurisdiction, multi-currency, multi-regulatory.

**Các paradigm đang cạnh tranh:**

1. **SWIFT gpi:** Evolution của incumbent, tối ưu visibility mà không thay đổi fundamental correspondent banking model. **Best cho:** High-value, low-frequency, universal reach.

2. **Blockchain (Ripple, Stellar):** Disruption model, thay thế NOSTRO pre-funding bằng on-demand liquidity. **Best cho:** High-frequency corridors với sufficient liquidity, cost-sensitive use cases.

3. **CBDC:** Sovereign-controlled evolution, kết hợp efficiency của blockchain với trust của central bank money. **Best cho:** Regional economic blocks, bilateral agreements.

**Trade-off quan trọng nhất:**

| Speed | Cost | Universal Reach | Regulatory Certainty |
|-------|------|-----------------|---------------------|
| Pick 2-3, không thể có cả 4 |

- **gpi:** Cost + Reach + Certainty (thiếu Speed)
- **Blockchain:** Speed + Cost (thiếu Reach + Certainty)
- **CBDC:** Speed + Cost + Certainty (thiếu Reach - chưa universal)

**Rủi ro lớn nhất trong production:**

1. **Sanctions screening failure:** Regulatory penalty có thể destroy institution
2. **Liquidity mismatch:** Inability to settle dù có sufficient assets
3. **Message format incompatibility:** Silent failures trong ISO 20022 migration
4. **Herstatt risk:** Counterparty failure trong settlement window

**Hướng đi tương lai:**

Convergence giữa các models - gpi linking với CBDC, blockchain bridges cho corridors không có correspondent banking. Key enabler là **standardization** (ISO 20022 universal adoption) và **interoperability protocols** (giống như internet protocols cho money).

> **Final Thought:** Cross-border payment infrastructure là "plumbing" của global economy. Changes chậm vì network effects và regulatory complexity, nhưng direction rõ ràng hướng tới real-time, transparent, low-cost settlement.

---

## 8. Tài Liệu Tham Khảo

1. SWIFT gpi - https://www.swift.com/our-solutions/swift-gpi
2. BIS Innovation Hub - mBridge, Dunbar reports
3. RippleNet - https://ripple.net/
4. CLS Bank - https://www.cls-group.com/
5. ISO 20022 - https://www.iso20022.org/
6. FATF Cross-Border Payments - https://www.fatf-gafi.org/
