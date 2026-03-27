# Authentication & Authorization Patterns: Deep Dive Analysis

## 1. Mục tiêu của Task

Hiểu sâu các pattern xác thực và phân quyền trong hệ thống enterprise, bao gồm:
- Cơ chế JWT (JWS/JWE) ở tầng protocol
- OAuth 2.0/OpenID Connect flows và security implications
- Trade-off giữa session-based và stateless authentication
- Token lifecycle management: storage, rotation, revocation
- Spring Security internals: filter chain, authentication providers, authorization decision managers

---

## 2. JWT Internals: JWS vs JWE

### 2.1 Cấu trúc bản chất của JWT

JWT không phải là một chuẩn mã hóa - nó là **định dạng container** (RFC 7519). Bản chất là Base64Url-encoded JSON với chữ ký hoặc mã hóa.

```
JWT = JWS (JSON Web Signature) HOẶC JWE (JSON Web Encryption)
```

**JWS Structure (Signed but NOT encrypted):**
```
base64url(header) + "." + base64url(payload) + "." + base64url(signature)
```

**JWE Structure (Encrypted):**
```
base64url(protectedHeader) + "." + 
base64url(encryptedKey) + "." + 
base64url(iv) + "." + 
base64url(ciphertext) + "." + 
base64url(authTag)
```

### 2.2 JWS - Chi tiết cơ chế

**Header chứa gì:**
```json
{
  "alg": "RS256",    // Algorithm - QUAN TRỌNG: phải validate
  "typ": "JWT",      // Type
  "kid": "key-2024"  // Key ID cho key rotation
}
```

> ⚠️ **CRITICAL VULNERABILITY**: Algorithm confusion attack. Nếu server tin `alg` từ client, attacker có thể đổi RS256 → HS256 và dùng public key làm HMAC secret.

**Cách tính signature:**
```
HMACSHA256(
  base64url(header) + "." + base64url(payload),
  secret
)
```

**RSA Signature (RS256):**
```
RSASSA-PKCS1-v1_5-SIGN(
  SHA256(base64url(header) + "." + base64url(payload)),
  privateKey
)
```

### 2.3 JWE - Khi nào cần mã hóa?

JWE sử dụng **hybrid encryption**:
1. **Key Encryption**: RSA/ECDH để mã hóa Content Encryption Key (CEK)
2. **Content Encryption**: AES-GCM/AES-CBC để mã hóa payload

**Use cases cho JWE:**
- Token chứa PII (Personally Identifiable Information)
- Token truyền qua third-party systems
- Client-side storage của sensitive claims

**Trade-off:**
| Aspect | JWS | JWE |
|--------|-----|-----|
| Size | ~30% smaller | ~40% larger |
| CPU | Fast (1 hash/sign) | Slow (asymmetric + symmetric) |
| Security | Integrity only | Confidentiality + Integrity |
| Debug | Readable payload | Opaque |

### 2.4 JWT Security Pitfalls

**1. None Algorithm Attack:**
```json
{"alg": "none"}  // Một số thư viện cũ chấp nhận!
```

**2. Key Confusion (RS256 → HS256):**
```java
// VULNERABLE CODE - Đừng làm thế này
Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) publicKey);
// Attacker gửi token với alg=HS256, dùng public key làm secret
```

**3. Expiration Bypass:**
- `exp` claim là **suggestion**, không phải enforcement
- Clock skew attacks nếu server chấp nhận drift lớn

**4. Token Binding Issues:**
- JWT không có cơ chế "this token is bound to this session/device"
- Token stolen = attacker có full quyền cho đến expiration

---

## 3. OAuth 2.0 & OpenID Connect

### 3.1 OAuth 2.0 Grant Types - Security Analysis

**Authorization Code Flow (MOST SECURE for SPAs):**
```
Browser ──GET /authorize────→ Authorization Server
    │                         (redirect with code)
    │←────redirect_uri────────┘
    │
    └──POST /token (code + client_secret)──→ AS
                          (access_token + refresh_token)
```

**PKCE Extension (RFC 7636) - BẮT BUỘC cho public clients:**
```
Client tạo: code_verifier = random_string(128)
            code_challenge = BASE64URL(SHA256(code_verifier))
            
/authorize?code_challenge=xxx&code_challenge_method=S256

/token?code=xxx&code_verifier=original_random_string
```

> PKCE chống lại authorization code interception attacks khi custom URL schemes bị compromised.

**Client Credentials Flow:**
- Server-to-server only
- Không có user context
- Risk: Secret rotation trong microservices phức tạp

**Password Grant (DEPRECATED):**
- Client thấy user credentials
- Không dùng trong production modern systems

### 3.2 OIDC - Identity Layer trên OAuth 2.0

OIDC thêm **ID Token** (JWT) chứa user claims:

```json
{
  "iss": "https://auth.example.com",
  "sub": "user-123",           // Immutable user identifier
  "aud": "my-app-client-id",   // Audience - MUST validate
  "exp": 1234567890,
  "iat": 1234567800,
  "auth_time": 1234567800,     // When user actually authenticated
  "nonce": "random-value",     // Chống replay attacks
  "at_hash": "abc123...",      // Hash của access_token
  "c_hash": "def456..."        // Hash của authorization_code
}
```

**ID Token vs Access Token:**
| | ID Token | Access Token |
|----|----------|--------------|
| Purpose | WHO the user is | WHAT the user CAN DO |
| Audience | Client application | Resource Server |
| Contains | Identity claims | Scopes/permissions |
| Validate | Signature + iss + aud | Signature + scopes |

### 3.3 Token Storage Strategies

**Browser Storage Comparison:**

| Storage | XSS Risk | CSRF Protection | Size Limit | Persistence |
|---------|----------|-----------------|------------|-------------|
| `localStorage` | **HIGH** - JS accessible | No | ~5-10MB | Permanent |
| `sessionStorage` | **HIGH** - JS accessible | No | ~5-10MB | Tab lifetime |
| `httpOnly cookie` | **LOW** - No JS access | Needs SameSite | ~4KB | Configurable |
| `IndexedDB` | **HIGH** | No | Large | Permanent |

**RECOMMENDED PATTERN: Backend-for-Frontend (BFF) with httpOnly Cookies:**
```
Browser ──httpOnly cookie (session)──→ BFF Server ──JWT──→ APIs
         (No direct token access)              (Secure storage)
```

**Mobile Storage:**
- iOS: Keychain (secure enclave)
- Android: EncryptedSharedPreferences / Keystore
- **NEVER**: Regular SharedPreferences, unencrypted SQLite

### 3.4 Refresh Token Patterns

**Refresh Token Rotation (RECOMMENDED):**
```
Client ──refresh_token_1──→ Authorization Server
                              ↓
                    Issue: access_token + refresh_token_2
                    Invalidate: refresh_token_1
```

**Benefits:**
- Token theft detection (reuse detection)
- Automatic session revocation chain
- Limited blast radius

**Refresh Token Family:**
- Mỗi refresh token track "family tree"
- Nếu token N được dùng sau khi token N+1 đã issued → potential theft → revoke whole family

**Offline Access Considerations:**
- Long-lived refresh tokens (months) cho mobile apps
- Short-lived (hours/days) cho web apps
- Device fingerprinting để detect token export

### 3.5 Logout Handling trong Stateless Systems

**Challenge:** JWT không thể "revoke" vì stateless

**Solutions:**

**1. Short Expiration + Silent Refresh:**
```
Access token: 15 minutes
Refresh token: 7 days (with rotation)
Logout: Clear tokens client-side + revoke refresh token
```

**2. Token Blacklist ( compromize statelessness):**
```java
// Redis blacklist
SETEX jwt:jti:abc123 900 "revoked"  // TTL = token remaining life
```

**3. Back-Channel Logout (OIDC):**
```
OP ──POST /backchannel_logout──→ RP
    (logout_token with events: {"http://schemas.../logout": {}})
```

**4. Session ID Pattern:**
```json
{
  "sub": "user-123",
  "sid": "session-xyz",  // Reference to server session
  "exp": 1234567890
}
```
- Validate `sid` against session store
- Revoke session = immediate logout

---

## 4. Session-Based vs Stateless Authentication

### 4.1 Session-Based (Traditional)

**Architecture:**
```
Client ──sessionId (cookie)──→ Load Balancer ──→ Server A
                                      └──→ Server B (shared session store)
```

**Session Store Options:**
| Store | Pros | Cons |
|-------|------|------|
| In-memory | Fastest | No horizontal scaling |
| Redis | Fast, pub/sub, TTL | Extra infrastructure |
| Database | Persistent, large data | Slower, DB load |
| Sticky sessions | Simple | Uneven load, failover issues |

**Advantages:**
- Immediate revocation (delete session from store)
- Server controls session lifecycle
- Can store arbitrary session state
- CSRF protection built-in (SameSite cookies)

**Disadvantages:**
- Server-side state (scaling complexity)
- Session store SPOF
- Cross-domain challenges
- Memory pressure với long sessions

### 4.2 Stateless (JWT)

**Architecture:**
```
Client ──JWT──→ CDN ──→ API Gateway ──→ Service A
                              └──→ Service B (no shared state)
```

**Advantages:**
- Horizontal scaling dễ dàng
- No shared session store
- Cross-domain friendly (CORS + Authorization header)
- Microservices-friendly (self-contained claims)

**Disadvantages:**
- No immediate revocation (without compromise)
- Token size overhead (HTTP headers)
- Cryptographic verification cost
- Secret/key rotation complexity

### 4.3 Hybrid Approaches

**1. Signed Cookies with Server State:**
```
Cookie chứa: session_id + signature (HMAC)
Server validates signature, lookup Redis for session data
```

**2. JWT + Redis Validation:**
```
JWT chứa: user claims + jti (JWT ID)
Each request: validate JWT signature + check jti không trong blacklist
```

**3. Multi-Token Pattern:**
```
ID Token (JWT): Identity claims, short-lived
Access Token (opaque): Reference to authorization server
Refresh Token (JWT/JWE): Long-lived, stored securely
```

---

## 5. Spring Security Internals

### 5.1 Filter Chain Architecture

**Request Flow:**
```
Client Request
    ↓
SecurityContextPersistenceFilter  // Load SecurityContext from session
    ↓
HeaderWriterFilter               // Security headers (HSTS, X-Frame-Options)
    ↓
CsrfFilter                       // CSRF token validation
    ↓
LogoutFilter                     // Handle /logout
    ↓
UsernamePasswordAuthenticationFilter  // Form login
    ↓
BearerTokenAuthenticationFilter  // JWT validation (Spring Security OAuth2)
    ↓
RequestCacheAwareFilter          // Redirect after login
    ↓
SecurityContextHolderAwareRequestFilter
    ↓
AnonymousAuthenticationFilter    // If no auth, set anonymous
    ↓
SessionManagementFilter          // Concurrency control
    ↓
ExceptionTranslationFilter       // Handle AccessDeniedException
    ↓
FilterSecurityInterceptor        // Authorization decisions
    ↓
Servlet (Controller)
```

### 5.2 Authentication Provider Chain

**AuthenticationManager (interface):**
```java
public interface AuthenticationManager {
    Authentication authenticate(Authentication authentication) 
        throws AuthenticationException;
}
```

**ProviderManager implementation:**
```
ProviderManager
├── DaoAuthenticationProvider (username/password)
├── JwtAuthenticationProvider (JWT validation)
├── LdapAuthenticationProvider
├── PreAuthenticatedAuthenticationProvider
└── ... custom providers
```

**DaoAuthenticationProvider Flow:**
```java
1. UserDetailsService.loadUserByUsername(username)
2. PasswordEncoder.matches(rawPassword, encodedPassword)
3. UserDetailsChecker (accountNonExpired, etc.)
4. Return UsernamePasswordAuthenticationToken
```

### 5.3 JWT Integration (Spring Security 6.x)

**NimbusJwtDecoder (Production-grade):**
```java
@Bean
public JwtDecoder jwtDecoder() {
    NimbusJwtDecoder decoder = JwtDecoder.withJwkSetUri("https://auth/.well-known/jwks.json")
        .build();
    
    // Add custom validators
    OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer("https://auth");
    OAuth2TokenValidator<Jwt> withAudience = audienceValidator();
    decoder.setJwtValidator(withIssuer.and(withAudience));
    
    return decoder;
}
```

**JWT Validation MUST check:**
- Signature (algorithm, key)
- Expiration (exp)
- Not before (nbf)
- Issuer (iss)
- Audience (aud)
- Custom claims nếu cần

### 5.4 Authorization Decision Managers

**AccessDecisionManager (Spring Security 5.x - deprecated pattern):**
```
AccessDecisionManager
├── AffirmativeBased (default) - any voter grants = access
├── ConsensusBased - majority wins
└── UnanimousBased - all must grant
```

**AuthorizationManager (Spring Security 6.x - current):**
```java
public interface AuthorizationManager<T> {
    AuthorizationDecision check(Supplier<Authentication> authentication, T object);
}
```

**RequestMatcher pattern:**
```java
http.authorizeHttpRequests(auth -> auth
    .requestMatchers("/public/**").permitAll()
    .requestMatchers("/admin/**").hasRole("ADMIN")
    .requestMatchers("/api/**").hasAnyRole("USER", "ADMIN")
    .requestMatchers(HttpMethod.POST, "/api/orders").hasAuthority("order:create")
    .anyRequest().authenticated()
);
```

### 5.5 Method-Level Security

**@PreAuthorize - SpEL expressions:**
```java
@PreAuthorize("hasRole('ADMIN') or #userId == authentication.principal.id")
public User getUser(@PathVariable Long userId) { }

@PreAuthorize("@securityService.canEditOrder(#orderId, authentication)")
public void updateOrder(@PathVariable Long orderId) { }
```

**@PostAuthorize - Filter return values:**
```java
@PostAuthorize("returnObject.owner == authentication.name")
public Document getDocument(@PathVariable Long id) { }
```

**@PreFilter / @PostFilter - Collection filtering:**
```java
@PreFilter("filterObject.owner == authentication.name")
public void deleteDocuments(List<Document> documents) { }
```

---

## 6. Production Concerns & Trade-offs

### 6.1 Secret Management

**NEVER hardcode secrets!**

**HashiCorp Vault Integration:**
```java
@Value("${vault://secret/data/jwt#signing-key}")
private String jwtSigningKey;
```

**AWS Secrets Manager:**
- Automatic rotation
- IAM-based access control
- Audit logging via CloudTrail

**Kubernetes:**
```yaml
apiVersion: v1
kind: Secret
type: Opaque
stringData:
  jwt-key: "..."
```
- Mount as file, không phải env var (tránh ps ax)
- Use sealed-secrets hoặc external-secrets operator

### 6.2 Observability

**Security Events to Log:**
- Authentication success/failure (username, IP, timestamp)
- Authorization failures (resource, required permission, actual permission)
- Token validation failures (reason, token jti if available)
- Session lifecycle events (creation, invalidation, timeout)

**Metrics:**
- Authentication latency histogram
- Token validation cache hit/miss ratio
- Active sessions (nếu dùng session-based)
- Rate limit triggers

### 6.3 Scaling Considerations

**JWT Verification Bottleneck:**
```
CPU-intensive operations:
- RSA signature verification: ~0.5-1ms per token
- ECDSA: ~0.3-0.5ms
- HMAC: ~0.1ms
```

**Solutions:**
1. Token caching (trong request lifecycle)
2. Asymmetric key → Symmetric key internally (API Gateway verifies JWT, services dùng HMAC)
3. JWKS caching với reasonable TTL

**Session Store Scaling:**
- Redis Cluster với hash slots
- Write-through cache pattern
- Session affinity cho WebSocket connections

### 6.4 Backward Compatibility

**Token Versioning:**
```json
{
  "ver": "2",
  "sub": "user-123",
  "scope": "read write"
}
```

**Key Rotation Strategy:**
```
1. Generate new key pair
2. Add new public key to JWKS (both keys valid)
3. Issue new tokens with new key
4. After token TTL expires, remove old key
```

---

## 7. Rủi Ro, Anti-Patterns & Lỗi Thường Gặp

### 7.1 Critical Vulnerabilities

**1. Hardcoded Secrets:**
```java
// VULNERABLE
private static final String SECRET = "my-super-secret-key";
```

**2. Weak JWT Algorithms:**
```java
// VULNERABLE - HS256 với secret ngắn dễ brute-force
Algorithm algorithm = HMAC256("short");
```

**3. Missing Token Validation:**
```java
// VULNERABLE - Chỉ parse, không verify
Jws<Claims> claims = Jwts.parser().parseClaimsJws(token);
// Phải dùng: .setSigningKey(key).parseClaimsJws(token)
```

**4. Information Leakage in Tokens:**
```json
{
  "sub": "admin",
  "password": "hashed_value",  // ĐỪNG BAO GIỜ!
  "ssn": "123-45-6789"         // PII không thuộc về JWT
}
```

### 7.2 Common Misconfigurations

**Spring Security:**
```java
// WRONG - Disable CSRF cho stateless
http.csrf().disable();  // Chỉ disable nếu REALLY stateless!

// WRONG - Permissive CORS
cors.configurationSource(request -> {
    CorsConfiguration config = new CorsConfiguration();
    config.addAllowedOrigin("*");  // Chỉ định rõ origins!
    return config;
});
```

### 7.3 Session Fixation Attacks
```java
// PROTECTION
ttp.sessionManagement(session -> session
    .sessionFixation().migrateSession()  // Hoặc newSession
);
```

---

## 8. Khuyến Nghị Thực Chiến Production

### 8.1 Token Design

**Access Token:**
- Lifetime: 5-15 minutes
- Chứa: user ID, roles/scopes, minimal claims
- Signing: RS256 (asymmetric) nếu multiple consumers

**Refresh Token:**
- Lifetime: 7-30 days (rotation mỗi lần dùng)
- Storage: httpOnly, SameSite=strict cookie HOẶC secure enclave (mobile)
- Binding: Device fingerprint, IP (optional)

**ID Token (OIDC):**
- Lifetime: Match access token
- Validate: iss, aud, nonce, iat, exp
- Không dùng để authorization (không chứa scopes)

### 8.2 Spring Security Configuration

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/**")  // Stateless APIs
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder())
                    .jwtAuthenticationConverter(jwtAuthConverter())
                )
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                .accessDeniedHandler(new BearerTokenAccessDeniedHandler())
            )
            .build();
    }
    
    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
            .withJwkSetUri("https://auth.example.com/.well-known/jwks.json")
            .build();
        
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://auth.example.com"));
        return decoder;
    }
}
```

### 8.3 Key Rotation Checklist

- [ ] New key generated with sufficient entropy
- [ ] Old key retained trong JWKS during token TTL window
- [ ] All services updated to fetch JWKS dynamically
- [ ] Monitoring alerts cho signature failures
- [ ] Rollback plan nếu issues

---

## 9. Kết Luận

### Bản chất cốt lõi

1. **JWT là container**, không phải security solution. Nó đảm bảo integrity (JWS) và có thể confidentiality (JWE), nhưng không thể thay thế cho secure transport (TLS), secure storage, hay proper validation.

2. **OAuth 2.0 là delegation framework**, không phải authentication protocol. OIDC thêm identity layer, nhưng core của OAuth vẫn là "ủy quyền truy cập tài nguyên".

3. **Stateless không phải lúc nào cũng tốt hơn stateful**. Trade-off giữa scalability và control - JWT phù hợp cho read-heavy, cross-domain scenarios; sessions phù hợp cho immediate revocation requirements.

4. **Spring Security là framework, không phải black box**. Hiểu filter chain, authentication providers, và authorization managers để customize đúng cách thay vì bypass security.

### Trade-off quan trọng nhất

| Scenario | Stateless (JWT) | Stateful (Session) |
|----------|-----------------|-------------------|
| Microservices | ✅ Self-contained | ❌ Session store complexity |
| Immediate logout | ❌ Blacklist needed | ✅ Delete from store |
| Mobile apps | ✅ Cross-domain | ⚠️ Custom headers |
| Server-rendered web | ⚠️ Cookie handling | ✅ Native support |
| High-frequency APIs | ⚠️ Verification overhead | ❌ Session lookup latency |

### Rủi ro lớn nhất

**Token theft với long-lived tokens**. Một JWT stolen có thể được sử dụng cho đến expiration. Giải pháp:
- Short access token TTL (minutes)
- Refresh token rotation
- Binding tokens to device/session
- Monitoring abnormal usage patterns

---

*Document version: 1.0*
*Research date: 2026-03-27*
*Applicable for: Java 17-21, Spring Boot 3.x, Spring Security 6.x*
