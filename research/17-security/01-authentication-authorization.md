# Authentication & Authorization: JWT, OAuth2, OIDC, RBAC

## 1. Mục tiêu của task

Hiểu sâu bản chất các cơ chế xác thực (Authentication) và phân quyền (Authorization) trong hệ thống phân tán hiện đại. Phân tích cơ chế hoạt động tầng thấp, trade-off giữa các phương án, rủi ro production và cách triển khai đúng.

---

## 2. Bản chất và cơ chế hoạt động

### 2.1 Authentication vs Authorization: Phân biệt bản chất

| Aspect | Authentication | Authorization |
|--------|---------------|---------------|
| **Câu hỏi** | "Bạn là ai?" | "Bạn được phép làm gì?" |
| **Thứ điểm** | Boundary entry | Resource access |
| **Dữ liệu** | Identity credentials | Permissions/Claims |
| **Thứ tự** | Luôn trước | Sau authentication |
| **Ví dụ** | Username/password, MFA | RBAC roles, ABAC policies |

> **Lưu ý quan trọng**: Authentication xác định identity, nhưng identity ≠ authorization. Một user authenticated vẫn có thể bị từ chối truy cập resource.

### 2.2 JWT (JSON Web Token) - Bản chất Stateless Authentication

#### Cấu trúc thực sự

JWT không phải là "mã hóa" - nó là **signed assertion**:

```
eyJhbGciOiJSUzI1NiJ9.  ← Header (Base64Url)
eyJzdWIiOiIxMjM0NSJ9.   ← Payload (Base64Url)  
SflKxwRJSMeKKF2QT4fwpMe... ← Signature (HMAC/RSA/ECDSA)
```

**Bản chất cơ chế**:
- Payload là **plain text Base64Url** - bất kỳ ai cũng decode được
- Signature đảm bảo **integrity + authenticity** (ai ký?)
- Không đảm bảo **confidentiality** (phải dùng JWE nếu cần)

#### Cơ chế ký và xác thực

```
┌─────────────────────────────────────────────────────────────┐
│                    JWT SIGNATURE FLOW                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Server (Issuer)                    Client (Verifier)       │
│  ┌─────────────────┐                ┌─────────────────┐    │
│  │ 1. Create claims │                │ 4. Receive JWT  │    │
│  │    {sub,exp,...} │───────────────▶│                 │    │
│  └────────┬────────┘                └────────┬────────┘    │
│           ▼                                  │              │
│  ┌─────────────────┐                         │              │
│  │ 2. Base64Url    │                         │              │
│  │    encode       │                         │              │
│  └────────┬────────┘                         │              │
│           ▼                                  ▼              │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 3. Sign with Private Key (RS256)                     │   │
│  │    signature = RSA_SIGN(                            │   │
│  │      SHA256(base64(header) + "." + base64(payload)),│   │
│  │      privateKey                                     │   │
│  │    )                                                 │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  Verification at Client:                                    │
│  ───────────────────────                                    │
│  a. Split JWT → [header, payload, signature]                │
│  b. Base64Url decode header + payload                       │
│  c. Verify signature with Public Key                        │
│     RSA_VERIFY(signature, publicKey) == expected_hash       │
│  d. Check exp, nbf, iss claims                              │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

#### Trade-off: Symmetric (HS256) vs Asymmetric (RS256)

| | HS256 (HMAC) | RS256 (RSA) |
|---|---|---|
| **Key** | Single shared secret | Key pair (private/public) |
| **Verification** | Cần secret (tất cả services) | Chỉ cần public key |
| **Performance** | Nhanh (~10x) | Chậm hơn |
| **Use case** | Monolith, internal | Microservices, 3rd party |
| **Rủi ro** | Secret leakage = total compromise | Private key safe ở issuer |
| **Key rotation** | Khó (tất cả services đổi) | Dễ (chỉ issuer đổi private) |

> **Khuyến nghị**: Luôn dùng RS256/ES256 cho distributed systems. HS256 chỉ khi bạn có 1 service duy nhất.

#### JWT Claims - Design Intent

```json
{
  "sub": "user123",           // Subject: identity chính
  "iss": "auth.example.com",  // Issuer: ai phát hành
  "aud": "api.example.com",   // Audience: token cho ai
  "exp": 1711530000,          // Expiration: UNIX timestamp
  "nbf": 1711526400,          // Not Before: không dùng trước
  "iat": 1711526400,          // Issued At: thởi điểm phát hành
  "jti": "uuid-v4-here",      // JWT ID: unique token ID (revocation)
  "scope": "read write",      // OAuth2 scopes
  "custom_claim": "value"     // Application-specific
}
```

> **Nguyên tắc thiết kế**: JWT chứa **claims** (tuyên bố) về identity/authorization, không phải session state. Nó là "bằng chứng" đã được ký, không phải "container" dữ liệu.

### 2.3 OAuth2 - Delegated Authorization Framework

#### Bản chất: "Valet Key" Pattern

OAuth2 không phải authentication protocol - nó là **authorization delegation framework**:

> Tưởng tượng: Bạn đưa chìa khóa xe cho nhân viên gửi xe (valet). Bạn không đưa chìa khóa nhà, chìa khóa két sắt. Chỉ đưa quyền cần thiết (lái xe) trong thởi gian giới hạn.

```
┌────────────────────────────────────────────────────────────────┐
│                   OAUTH2 ACTORS                                │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│   ┌──────────────┐         ┌──────────────┐                   │
│   │   Resource   │         │   Resource   │                   │
│   │    Owner     │◄───────►│    Server    │                   │
│   │   (User)     │  owns   │  (Your API)  │                   │
│   └──────┬───────┘         └──────┬───────┘                   │
│          │                        │                           │
│          │  1. Authorize access   │                           │
│          │───────────────────────▶│                           │
│          │                        │                           │
│          │  2. Grant token        │                           │
│          │◄───────────────────────│                           │
│          │                        │                           │
│          │  3. Access with token  │                           │
│          └───────────────────────►│                           │
│                                   │                           │
│   ┌──────────────┐                │                           │
│   │    Client    │────────────────┘                           │
│   │ (3rd Party   │  4. Validate token                         │
│   │   App)       │◄────────────────                           │
│   └──────────────┘                                            │
│                                                                │
│   ┌──────────────┐                                             │
│   │ Authorization│                                             │
│   │   Server     │                                             │
│   │ (Auth0/Keycloak│                                           │
│   │  /Custom)    │                                             │
│   └──────────────┘                                             │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

#### OAuth2 Grant Types - When to Use What

| Grant Type | Use Case | Security Level | Notes |
|------------|----------|----------------|-------|
| **Authorization Code** | Web apps, mobile apps | ⭐⭐⭐⭐⭐ | PKCE required for public clients |
| **PKCE Extension** | SPAs, mobile apps | ⭐⭐⭐⭐⭐ | Bắt buộc cho public clients (RFC 7636) |
| **Client Credentials** | Server-to-server | ⭐⭐⭐⭐ | M2M communication |
| **Device Code** | Smart TVs, IoT | ⭐⭐⭐ | Input-constrained devices |
| **Refresh Token** | Long-lived access | ⭐⭐⭐⭐ | Rotate on use (best practice) |
| ~~Implicit~~ | ~~SPAs~~ | ⭐ | **DEPRECATED** - dùng PKCE thay thế |
| ~~Password~~ | ~~Legacy~~ | ⭐⭐ | **DEPRECATED** - tránh dùng |

#### Authorization Code Flow + PKCE (Modern Standard)

```
┌─────────────────────────────────────────────────────────────────────┐
│           AUTHORIZATION CODE FLOW WITH PKCE                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Client                                        Authorization Server │
│  ┌─────────────────┐                              ┌───────────────┐ │
│  │ 1. Generate:    │                              │               │ │
│  │    code_verifier = random(128 bytes)          │               │ │
│  │    code_challenge = SHA256(code_verifier)     │               │ │
│  │    state = random(32 bytes)  ← CSRF protection│               │ │
│  └────────┬────────┘                              │               │ │
│           │                                       │               │ │
│           │ 2. Redirect to /authorize             │               │ │
│           │    ?response_type=code                │               │ │
│           │    &client_id=xxx                     │               │ │
│           │    &redirect_uri=xxx                  │               │ │
│           │    &scope=read+write                  │               │ │
│           │    &state=xxx                         │               │ │
│           │    &code_challenge=xxx                │               │ │
│           │    &code_challenge_method=S256        │               │ │
│           ▼                                       │               │ │
│  ┌─────────────────┐                              │               │ │
│  │ Browser/User    │─────────────────────────────►│               │ │
│  │ authenticates   │                              │               │ │
│  └─────────────────┘                              │               │ │
│           │                                       │               │ │
│           │ 3. Redirect back with authorization   │               │ │
│           │    code + state (verify CSRF)         │               │ │
│           │◄─────────────────────────────────────│               │ │
│           │                                       │               │ │
│           │ 4. POST /token                        │               │ │
│           │    grant_type=authorization_code      │               │ │
│           │    code=xxx                           │               │ │
│           │    code_verifier=xxx  ← Proof!        │               │ │
│           │─────────────────────────────────────►│               │ │
│           │                                       │               │ │
│           │ 5. Return access_token + refresh_token│               │ │
│           │◄─────────────────────────────────────│               │ │
│           │                                       │               │ │
│  ┌─────────────────┐                              └───────────────┘ │
│  │ Use access_token│                                                 │
│  │ on API calls    │                                                 │
│  └─────────────────┘                                                 │
│                                                                     │
│  PKCE Purpose: Đảm bảo client đứng ở step 4 chính là client        │
│  đã tạo code_challenge ở step 1 (chứng minh sở hữu code_verifier)  │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

> **Tại sao cần PKCE?**: Authorization code có thể bị intercept (mobile app deep link hijacking, HTTP log). PKCE đảm bảo attacker không dùng được code dù có steal được.

### 2.4 OIDC (OpenID Connect) - Identity Layer trên OAuth2

#### Bản chất: OAuth2 + Identity

OAuth2 nói "được phép làm gì", OIDC nói "là ai":

```
┌────────────────────────────────────────────────────────────────┐
│  OAuth2                        OIDC (built on OAuth2)          │
├────────────────────────────────────────────────────────────────┤
│  access_token                 access_token + id_token          │
│       │                              │                         │
│       ▼                              ▼                         │
│  "Bearer xyz"              JWT chứa identity claims            │
│  Opaque hoặc JWT           {sub, name, email, email_verified}  │
│  Authorization only        Authentication + Authorization      │
│                                                                │
│  OIDC Endpoints thêm vào OAuth2:                               │
│  ────────────────────────────────                              │
│  • /authorize (OAuth2) - thêm scope=openid                     │
│  • /token (OAuth2)     - trả về id_token                       │
│  • /userinfo           - lấy claims bổ sung                    │
│  • /.well-known/openid-configuration - discovery               │
│  • /logout             - RP-initiated logout                   │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

#### ID Token Structure

```json
{
  "iss": "https://auth.example.com",
  "sub": "auth0|123456789",      // Unique user ID
  "aud": "my-client-app",         // OAuth2 client_id
  "exp": 1711530000,
  "iat": 1711526400,
  "auth_time": 1711526400,        // When user authenticated
  "nonce": "random-value",        // Replay attack protection
  "acr": "urn:mace:incommon:iap:silver", // Auth assurance level
  "amr": ["pwd", "mfa"],          // Authentication methods
  "name": "John Doe",
  "email": "john@example.com",
  "email_verified": true,
  "picture": "https://..."
}
```

> **OIDC Discovery**: `GET /.well-known/openid-configuration` trả về metadata (endpoints, scopes, claims supported, signing algorithms) - enables dynamic client configuration.

### 2.5 RBAC (Role-Based Access Control) - Permission Model

#### Bản chất: Role as Indirection

```
┌────────────────────────────────────────────────────────────────┐
│                    RBAC CORE CONCEPT                           │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│   User ──► Role ──► Permission ──► Resource                    │
│                                                                │
│   Không gán quyền trực tiếp cho user!                          │
│                                                                │
│   Ví dụ:                                                       │
│   ───────                                                      │
│   user:alice ──► roles:[ADMIN] ──► permissions:[               │
│     "user:read", "user:write", "user:delete",                  │
│     "order:read", "order:write",                               │
│     "system:config"                                            │
│   ]                                                            │
│                                                                │
│   user:bob ──► roles:[CUSTOMER_SUPPORT] ──► permissions:[      │
│     "user:read",                                               │
│     "order:read", "order:write",                               │
│     "ticket:read", "ticket:write"                              │
│   ]                                                            │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

#### RBAC Hierarchy (RBAC1)

```
┌────────────────────────────────────────────────────────────────┐
│                 ROLE HIERARCHY                                 │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│                         SUPER_ADMIN                            │
│                              │                                 │
│          ┌───────────────────┼───────────────────┐             │
│          │                   │                   │             │
│       ADMIN            FINANCE_ADMIN       TECH_ADMIN          │
│          │                   │                   │             │
│    ┌─────┴─────┐             │                   │             │
│    │           │        ACCOUNTANT        DEVOPS_ENGINEER      │
│ USER_MANAGER  ...              │                   │             │
│                                │             ┌────┴────┐        │
│                         JUNIOR_ACCOUNTANT  SRE      BACKEND_DEV│
│                                                                │
│   Permission inheritance: child role có tất cả permissions     │
│   của parent role.                                             │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

#### RBAC vs ABAC (Attribute-Based Access Control)

| | RBAC | ABAC |
|---|---|---|
| **Policy** | Static roles | Dynamic rules based on attributes |
| **Flexibility** | Thấp - cần tạo role mới | Cao - rules evaluate runtime |
| **Complexity** | Đơn giản | Phức tạp (policy engine) |
| **Example** | "ADMIN can delete users" | "User can delete IF owner=true AND account_age>30d" |
| **Use case** | Org structure rõ ràng | Fine-grained, context-aware |
| **Tools** | Spring Security, Keycloak | AWS IAM, OPA (Open Policy Agent), XACML |

> **Kết hợp**: Nhiều hệ thống dùng RBAC cho coarse-grained + ABAC cho fine-grained (e.g., ADMIN role + ownership check).

---

## 3. Kiến trúc và luồng xử lý

### 3.1 Microservices Auth Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│              MICROSERVICES AUTHENTICATION FLOW                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Client                        Gateway        Services              │
│    │                             │              │                   │
│    │ 1. POST /auth/login         │              │                   │
│    │────────────────────────────►│              │                   │
│    │                             │              │                   │
│    │ 2. Validate credentials     │              │                   │
│    │    Generate JWT pair        │              │                   │
│    │◄────────────────────────────│              │                   │
│    │    {access_token, refresh_token}           │                   │
│    │                             │              │                   │
│    │ 3. API call + Bearer token  │              │                   │
│    │────────────────────────────►│              │                   │
│    │                             │              │                   │
│    │              4. Verify JWT  │              │                   │
│    │                 Check exp   │              │                   │
│    │                 Validate sig│              │                   │
│    │                             │              │                   │
│    │              5. Enrich request              │                   │
│    │                 + X-User-Id: 123            │                   │
│    │                 + X-User-Roles: admin       │                   │
│    │                 + X-Request-Id: uuid        │                   │
│    │                             │              │                   │
│    │                             │ 6. Forward   │                   │
│    │                             │─────────────►│                   │
│    │                             │              │                   │
│    │                             │              │ 7. RBAC check     │
│    │                             │              │    - Extract roles│
│    │                             │              │    - Match perms  │
│    │                             │              │                   │
│    │                             │ 8. Response  │                   │
│    │                             │◄─────────────│                   │
│    │ 9. Response                 │              │                   │
│    │◄────────────────────────────│              │                   │
│    │                             │              │                   │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 Token Lifecycle Management

```
┌─────────────────────────────────────────────────────────────────────┐
│                    TOKEN LIFECYCLE                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│   ┌──────────────┐                                                  │
│   │   Issuance   │                                                  │
│   └──────┬───────┘                                                  │
│          │ POST /auth/login                                          │
│          │ Generate:                                                 │
│          │   - access_token (15 min)                                 │
│          │   - refresh_token (7 days)                                │
│          ▼                                                          │
│   ┌──────────────┐                                                  │
│   │    Usage     │◄──────────────────┐                              │
│   └──────┬───────┘                   │                              │
│          │ API requests              │                              │
│          │ Authorization: Bearer xxx │                              │
│          ▼                          │ Token expired                  │
│   ┌──────────────┐                   │                              │
│   │  Validation  │───────────────────┘                              │
│   └──────┬───────┘                                                  │
│          │ Check exp, sig, claims                                    │
│          ▼                                                          │
│   ┌──────────────┐                                                  │
│   │   Refresh    │◄── POST /auth/refresh                             │
│   └──────┬───────┘    Validate refresh_token                        │
│          │            Rotate: new pair                               │
│          │            Invalidate old refresh                         │
│          ▼                                                          │
│   ┌──────────────┐                                                  │
│   │  Revocation  │◄── POST /auth/logout                              │
│   └──────────────┘    Blacklist tokens                               │
│                       Or: short expiry + wait                        │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 4. So sánh các lựa chọn

### 4.1 Session Cookie vs JWT

| | Session Cookie | JWT (Stateless) |
|---|---|---|
| **Storage** | Server (Redis/DB) | Client (localStorage/cookie) |
| **Lookup** | DB round-trip per request | Zero lookup (verify signature) |
| **Revocation** | Instant (delete from store) | Delayed (wait expiry) hoặc blacklist |
| **Scale** | Cần sticky session hoặc shared store | Stateless, scale freely |
| **Size** | Small (session ID) | Large (KB range with claims) |
| **XSS risk** | Low (httpOnly cookie) | High (if localStorage) |
| **CSRF risk** | Cần CSRF token | None (Bearer header) |
| **Logout** | Clear cookie + server delete | Wait expiry hoặc blacklist |
| **Use case** | Web apps, cần instant revoke | Mobile APIs, microservices |

> **Hybrid approach**: Dùng short-lived JWT (5-15 min) + refresh token rotation. Kết hợp scale của JWT + revocation capability.

### 4.2 OAuth2 Providers Comparison

| | Auth0 | Keycloak | AWS Cognito | Firebase Auth |自建 |
|---|---|---|---|---|---|
| **Cost** | $$$ | Free | Pay-per-MAU | Free tier | Infra cost |
| **Customization** | Limited | Full | Limited | Limited | Unlimited |
| **Social login** | Built-in | Via broker | Limited | Excellent | Tự tích hợp |
| **Enterprise** | Excellent | Good | Good | Basic | Depends |
| **Complexity** | Low | High | Medium | Low | Highest |
| **OIDC compliant** | ✅ | ✅ | ✅ | ✅ | Self-verify |

---

## 5. Rủi ro, Anti-patterns, Lỗi thường gặp

### 5.1 JWT Security Pitfalls

#### ❌ Lỗi 1: Dùng HS256 với secret ngắn/yếu

```java
// ANTI-PATTERN: Hardcoded weak secret
String secret = "mysecret";  // CRACKABLE!

// CORRECT: RS256 với private key an toàn
// Hoặc HS256 với secret 256-bit random
codec = "HS256"
secret = generateSecureRandom(32 bytes)
```

#### ❌ Lỗi 2: Không verify signature ("none" algorithm)

```java
// ATTACK: Attacker gửi JWT với alg: "none"
// Vulnerable code:
if (token.getAlgorithm().equals(expectedAlg)) {
    verifySignature(token);  // Bỏ qua nếu alg mismatch!
}

// CORRECT: Explicit algorithm whitelist
JwtParser parser = Jwts.parser()
    .verifyWith(publicKey)
    .requireIssuer("auth.example.com")
    .requireAudience("api.example.com")
    .build();
```

#### ❌ Lỗi 3: Lưu JWT ở localStorage

```javascript
// ANTI-PATTERN: XSS steals your token
localStorage.setItem('jwt', token);

// CORRECT: httpOnly, secure, sameSite cookie
Set-Cookie: jwt=xxx; HttpOnly; Secure; SameSite=Strict; Max-Age=900
```

> **Exception**: Mobile apps có thể lưu ở Keychain/Keystore với proper encryption.

#### ❌ Lỗi 4: Long-lived access tokens

```java
// ANTI-PATTERN: 30-day access token
.expiration(new Date(System.currentTimeMillis() + 30 * DAYS))

// CORRECT: Short access + refresh rotation
.expiration(new Date(System.currentTimeMillis() + 15 * MINUTES))
// + refresh token 7 days, rotate on use
```

### 5.2 OAuth2/OIDC Anti-patterns

#### ❌ Lỗi 5: Missing PKCE for public clients

```
// ANTI-PATTERN: Authorization code flow without PKCE cho mobile app
// Attacker có thể steal authorization code qua deep link hijacking

// CORRECT: Luôn dùng PKCE cho SPA, mobile
// RFC 8252 (OAuth for Native Apps) bắt buộc PKCE
```

#### ❌ Lỗi 6: Missing state parameter

```
// ANTI-PATTERN: Không dùng state parameter
// Vulnerable to CSRF login attacks

// CORRECT:
state = generateCSRFToken()  // Lưu vào session/cookie
redirect_to = /authorize?...&state={state}
// Callback: verify state matches
```

### 5.3 RBAC Anti-patterns

#### ❌ Lỗi 7: Role explosion

```java
// ANTI-PATTERN: Tạo role cho mọi permission combination
// ROLE_US_EAST_ADMIN, ROLE_US_WEST_ADMIN, ROLE_EU_ADMIN...

// CORRECT: RBAC + ABAC hybrid
// Roles: ADMIN, USER, SUPPORT
// ABAC rules check region, ownership, etc.
```

#### ❌ Lỗi 8: Hardcoded role checks

```java
// ANTI-PATTERN: Logic nghiệp vụ trong code
if (user.hasRole("ADMIN")) {
    // business logic
}

// CORRECT: Permission-based checks
if (user.hasPermission("order:delete")) {
    // business logic
}
// Permission mapping trong database/config
```

### 5.4 Failure Modes

| Scenario | Impact | Mitigation |
|---|---|---|
| **Token theft** | Attacker impersonates user | Short expiry, refresh rotation, device binding |
| **Key compromise** | All tokens compromised | Key rotation, short token expiry, immediate revocation |
| **Clock skew** | Token rejected valid/early | Allow nbf/exp leeway (~60s) |
| **Replay attack** | Token reused | JTI blacklist, short expiry, nonce for OIDC |
| **Scope escalation** | User modifies token claims | Signature verification (server-side only) |

---

## 6. Khuyến nghị thực chiến trong Production

### 6.1 JWT Best Practices

```yaml
# Production Configuration
token:
  # Algorithm: RS256 or ES256 (asymmetric)
  algorithm: RS256
  
  # Key management
  private_key: ${JWT_PRIVATE_KEY}  # From vault/secret manager
  public_key: ${JWT_PUBLIC_KEY}
  key_rotation_interval: 90d
  
  # Expiration
  access_token_ttl: 15m
  refresh_token_ttl: 7d
  refresh_token_rotation: true  # Rotate on use
  
  # Claims
  issuer: "auth.yourcompany.com"
  audience: "api.yourcompany.com"
  require_exp: true
  require_iss: true
  require_aud: true
  
  # Security
  max_token_size: 8KB
  allowed_algorithms: [RS256, ES256]  # Whitelist only
  
# Storage: httpOnly cookie for web, secure storage for mobile
storage:
  web:
    type: cookie
    http_only: true
    secure: true
    same_site: Strict
  mobile:
    type: keychain  # iOS Keychain / Android Keystore
```

### 6.2 Key Rotation Strategy

```
┌────────────────────────────────────────────────────────────────┐
│              ZERO-DOWNTIME KEY ROTATION                        │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  T0: Rotate keys                                              │
│  ├── Generate new key pair                                    │
│  ├── Sign NEW tokens with new key                             │
│  └── Keep OLD key for verification (grace period)             │
│                                                                │
│  T0 to T0+24h: Dual verification                              │
│  ├── Try verify with NEW public key                           │
│  └── Fallback to OLD public key if fail                       │
│                                                                │
│  T0+24h: Deprecate old key                                    │
│  └── Remove OLD key from verification set                     │
│  └── Any token signed with old key = expired                  │
│                                                                │
│  Key ID (kid) header: cho phép verifier chọn đúng public key  │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

### 6.3 Monitoring & Observability

```java
// Metrics cần track
meterRegistry.counter("auth.login.success").increment();
meterRegistry.counter("auth.login.failure", "reason", "invalid_credentials").increment();
meterRegistry.counter("auth.token.refresh").increment();
meterRegistry.counter("auth.token.revoked").increment();
meterRegistry.counter("auth.forbidden", "resource", "/api/admin").increment();

// Distributed tracing
Span.current().setAttribute("auth.user_id", userId);
Span.current().setAttribute("auth.client_id", clientId);
Span.current().setAttribute("auth.method", "password"); // or mfa, oauth

// Alerting
- Login failure rate > 10% in 5min → Alert
- Token validation failure spike → Possible attack
- Unusual login patterns (geo, time) → Anomaly detection
```

### 6.4 Revocation Strategy (Logout)

```java
// Option 1: Blacklist (Redis)
@Component
public class TokenBlacklist {
    private final RedisTemplate<String, String> redis;
    
    public void revoke(String jti, Duration ttl) {
        redis.opsForValue().set("blacklist:" + jti, "revoked", ttl);
    }
    
    public boolean isRevoked(String jti) {
        return redis.hasKey("blacklist:" + jti);
    }
}

// Option 2: Short expiry (preferred for scale)
// Access token 5-15 min, không cần blacklist
// Refresh token rotation, revoke = stop issuing new tokens

// Option 3: Version-based (user-wide revocation)
// Token chứa "token_version": user.token_version
// Logout → increment user.token_version
// Verify: token.version == user.token_version
```

### 6.5 Modern Java 21+ Integration

```java
// Virtual Threads cho I/O bound auth operations (OIDC, DB)
@Async
public CompletableFuture<TokenResponse> authenticateAsync(Credentials creds) {
    return CompletableFuture.supplyAsync(() -> {
        // Runs on virtual thread
        return authenticate(creds);
    });
}

// Sealed classes for AuthResult
public sealed interface AuthResult 
    permits AuthSuccess, AuthFailure, AuthMFARequired {}

// Pattern matching for exhaustive handling
return switch (result) {
    case AuthSuccess success -> handleSuccess(success);
    case AuthFailure failure -> handleFailure(failure);
    case AuthMFARequired mfa -> handleMFA(mfa);
};
```

---

## 7. Kết luận

### Bản chất cốt lõi

1. **JWT là signed assertion, không phải encrypted data** - Anyone can read, only issuer can forge. Dùng cho stateless authentication nhưng không chứa sensitive data.

2. **OAuth2 là authorization delegation, không phải authentication** - Dùng để cấp quyền hạn chế cho 3rd party. OIDC thêm identity layer lên trên.

3. **PKCE là bắt buộc cho public clients** - Không phải optional. Prevents authorization code interception attacks.

4. **RBAC là indirection layer** - Không gán quyền trực tiếp cho user. Sử dụng role hierarchy + permission-based checks thay vì role-based logic.

### Trade-off quan trọng nhất

**Stateless (JWT) vs Stateful (Session)**:
- Stateless = scale tốt, revocation khó
- Stateful = revocation tức thở, cần shared store
- **Giải pháp**: Short-lived JWT + refresh rotation = balance tốt nhất

### Rủi ro lớn nhất

1. **Algorithm confusion attacks** - Verify algorithm whitelist
2. **XSS via localStorage** - Dùng httpOnly cookies
3. **Missing PKCE** - Bắt buộc cho public clients
4. **Long-lived tokens** - Short expiry + rotation
5. **Key management** - RS256 + proper rotation + secret manager

### Checklist Production

- [ ] RS256/ES256, không phải HS256 cho distributed systems
- [ ] Short access tokens (5-15 min) + refresh rotation
- [ ] httpOnly, Secure, SameSite=Strict cookies
- [ ] PKCE cho tất cả public clients
- [ ] Key rotation mechanism với zero downtime
- [ ] Token blacklist hoặc version-based revocation
- [ ] Comprehensive logging và anomaly detection
- [ ] Regular security audits và penetration testing
