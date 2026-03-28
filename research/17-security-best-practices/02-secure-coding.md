# Secure Coding: SQL Injection, XSS, CSRF, Secrets Management

## 1. Mục tiêu của task

Hiểu sâu bản chất các lỗ hổng bảo mật phổ biến nhất trong ứng dụng Java backend, cơ chế khai thác, tác động thực tế, và chiến lược phòng thủ production-grade. Tập trung vào:
- SQL Injection: Tại sao prepared statement thực sự an toàn
- XSS: Sự khác biệt giữa các loại XSS và defense in depth
- CSRF: Bản chất của SameSite cookies và token-based protection
- Secrets Management: Anti-patterns nguy hiểm và giải pháp enterprise

---

## 2. Bản chất và cơ chế hoạt động

### 2.1 SQL Injection (SQLi)

#### Bản chất cơ chế

SQLi xảy ra khi **input của user được đưa trực tiếp vào câu SQL** mà không qua quá trình escape/parameterization đúng cách. Bản chất là sự **phá vỡ boundary giữa code và data**.

```
┌─────────────────────────────────────────────────────────────┐
│  APPLICATION                                                │
│  String query = "SELECT * FROM users WHERE id = " + userId; │
│                              │                              │
│                              ▼                              │
│  "SELECT * FROM users WHERE id = 1 OR 1=1 -- "              │
│                              │                              │
└──────────────────────────────┼──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│  DATABASE                                                   │
│  Parser nhìn thấy:                                          │
│  - SELECT (keyword)                                         │
│  - * (token)                                                │
│  - FROM (keyword)                                           │
│  - users (identifier)                                       │
│  - WHERE (keyword)                                          │
│  - id = 1 OR 1=1 (boolean expression → ALWAYS TRUE)        │
│  - -- (comment)                                             │
│                                                             │
│  → Trả về toàn bộ bảng users                                │
└─────────────────────────────────────────────────────────────┘
```

#### Tại sao String concatenation thất bại

Cơ chế parsing của SQL engine không phân biệt được đâu là **code intent** (do developer viết) và **user data** (do attacker inject):

| Input | Kết quả parse | Ý định attacker |
|-------|--------------|-----------------|
| `1` | `WHERE id = 1` | Normal query |
| `1 OR 1=1` | `WHERE id = 1 OR 1=1` | Boolean-based blind SQLi |
| `1; DROP TABLE users--` | Multi-statement execution | Stacked queries |
| `1' UNION SELECT * FROM admin--` | `UNION` injection | Data exfiltration |

#### Prepared Statement: Bản chất bảo vệ

```
┌─────────────────────────────────────────────────────────────┐
│  PREPARED STATEMENT FLOW                                    │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  1. PREPARE PHASE (1 lần duy nhất)                         │
│     "SELECT * FROM users WHERE id = ?"                      │
│                              │                              │
│                              ▼                              │
│     Database parse → Query plan → Placeholder slots         │
│     (Parser xác định structure, ? được đánh dấu là slot)   │
│                                                             │
│  2. EXECUTE PHASE (có thể lặp lại nhiều lần)               │
│     setInt(1, userInput)                                    │
│                              │                              │
│                              ▼                              │
│     Database nhận userInput AS DATA, NOT CODE              │
│     → Input được đưa vào slot đã định nghĩa                 │
│     → Không qua parser lần nữa                              │
│     → Không thể thay đổi structure của query                │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Critical distinction:**
- `Statement`: Parse → Execute (cùng lúc, input đã là một phần của string)
- `PreparedStatement`: Parse (trước) → Bind (data) → Execute (sau)

```java
// ANTI-PATTERN: Vulnerable
String query = "SELECT * FROM users WHERE name = '" + name + "'";
statement.executeQuery(query);

// PATTERN: Safe - Parser không thấy user input
PreparedStatement ps = conn.prepareStatement(
    "SELECT * FROM users WHERE name = ?"
);
ps.setString(1, name);  // Input đi vào ở phase BIND, không qua PARSE
ps.executeQuery();
```

#### Các biến thể SQLi và cách phòng thủ

| Loại SQLi | Cơ chế | Ví dụ | Defense |
|-----------|--------|-------|---------|
| **In-band** | Kết quả hiển thị trực tiếp | `UNION SELECT` | Prepared Statement |
| **Blind (Boolean)** | True/false inference | `AND 1=1` vs `AND 1=2` | Prepared Statement + ORM |
| **Blind (Time-based)** | Delay inference | `AND SLEEP(5)` | Input validation |
| **Out-of-band** | DNS/HTTP exfiltration | `LOAD_FILE()` | Network segmentation |
| **Second-order** | Payload stored, execute later | Comment → Admin view | Consistent parameterization |

#### Trade-off: Prepared Statement vs ORM

| Approach | Performance | Security | Flexibility | Use Case |
|----------|-------------|----------|-------------|----------|
| Raw PreparedStatement | High | High | Low | Complex native queries |
| JPA/Hibernate | Medium | High | Medium | Standard CRUD |
| JOOQ | High | High | High | Type-safe SQL |
| QueryDSL | Medium | High | Medium | Dynamic queries |

> **Lưu ý quan trọng:** ORM không tự động an toàn nếu dùng `createNativeQuery()` với string concatenation. JPA Criteria API và JPQL parameter binding mới thực sự an toàn.

---

### 2.2 Cross-Site Scripting (XSS)

#### Bản chất cơ chế

XSS là sự **tiêm mã độc (JavaScript) vào context mà browser sẽ thực thi**. Bản chất: **phá vỡ trust boundary giữa server-generated content và user-controlled data**.

```
┌─────────────────────────────────────────────────────────────┐
│                    XSS ATTACK FLOW                          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Attacker ──payload──► Server ──store──► Database          │
│      │                             │                        │
│      │                             │                        │
│      │    ┌────────────────────────┘                        │
│      │    │                                                 │
│      │    ▼                                                 │
│      │  Victim requests page                                │
│      │    │                                                 │
│      │    ▼                                                 │
│      └──► Server renders: "Hello <script>alert(1)</script>" │
│            │                                                │
│            ▼                                                │
│         Victim's browser executes script                    │
│            │                                                │
│            ▼                                                │
│         Session cookie sent to attacker server              │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

#### Ba loại XSS và bản chất khác biệt

| Loại | Storage | Execution trigger | Persistence | Phức tạp |
|------|---------|-------------------|-------------|----------|
| **Stored** | Database | Victim views page | Permanent | Thấp |
| **Reflected** | URL/Request | Victim clicks link | One-time | Trung bình |
| **DOM-based** | Client-side | JavaScript processes URL | One-time | Cao |

**DOM-based XSS đặc biệt nguy hiểm:**
```javascript
// Vulnerable: Direct assignment to dangerous sink
const hash = location.hash.slice(1);
document.write(hash);  // If hash = #<img src=x onerror=alert(1)>

// Safe: Text assignment escapes automatically
document.body.textContent = hash;
```

#### Context-aware Output Encoding

Một ký tự đã được escape trong HTML context có thể vẫn nguy hiểm trong JavaScript context:

```html
<!-- HTML Context -->
<div>"&lt;script&gt;"</div>  <!-- Safe: rendered as text -->

<!-- JavaScript Context -->
<script>
  var data = "</script><script>alert(1)</script>";  // DANGEROUS!
</script>

<!-- URL Context -->
<a href="javascript:alert(1)">Click</a>  <!-- DANGEROUS! -->
```

Bảng encoding theo context:

| Context | Dangerous chars | Encoding |
|---------|-----------------|----------|
| HTML Body | `< > & " '` | HTML entities |
| HTML Attribute | `" ' space` | HTML entities + quote escape |
| JavaScript | `" ' \ \n \r` | Unicode escape + quote handling |
| URL | Space, special chars | Percent-encoding |
| CSS | `; { } \ " '` | CSS escape |

#### Content Security Policy (CSP) - Defense in Depth

```
┌─────────────────────────────────────────────────────────────┐
│  CSP WORKFLOW                                               │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Server sends header:                                       │
│  Content-Security-Policy:                                   │
│    default-src 'self';                                      │
│    script-src 'self' 'nonce-{random}';                      │
│    object-src 'none';                                       │
│    base-uri 'self';                                         │
│                                                             │
│  Browser behavior:                                          │
│  ─────────────────────────────────────────────────────────  │
│  <script>alert(1)</script>        → BLOCKED (no nonce)     │
│  <script nonce="xyz">legit()</script>  → ALLOWED           │
│  <img src=x onerror=alert(1)>     → BLOCKED (inline event) │
│  eval("alert(1)")                 → BLOCKED (no unsafe-eval)│
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

### 2.3 Cross-Site Request Forgery (CSRF)

#### Bản chất cơ chế

CSRF khai thác **tính năng tự động gửi cookies của browser** để thực hiện hành động không mong muốn thay mặt victim đã authenticated.

```
┌─────────────────────────────────────────────────────────────┐
│  CSRF ATTACK FLOW                                           │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  1. User đăng nhập bank.com, cookie session được set       │
│                                                             │
│  2. User không logout, mở tab mới truy cập evil.com        │
│                                                             │
│  3. evil.com chứa:                                         │
│     <img src="https://bank.com/transfer?to=attacker&amount=1000000">
│                                                             │
│  4. Browser TỰ ĐỘNG gửi cookie session kèm theo request    │
│     (đây là behavior chuẩn của browser)                    │
│                                                             │
│  5. bank.com nhận request:                                 │
│     - Có cookie hợp lệ → Xác thực thành công              │
│     - Không có cách nào biết request đến từ evil.com       │
│     → Thực hiện transfer                                    │
│                                                             │
│  BẢN CHẤT LỖ HỔNG:                                          │
│  Server không thể phân biệt request từ:                    │
│  - Form hợp lệ của chính mình                             │
│  - Form giả mạo từ site khác                              │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

#### CSRF Token Pattern

```
┌─────────────────────────────────────────────────────────────┐
│  CSRF TOKEN DEFENSE                                         │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Server generates token:                                    │
│  CSRF-TOKEN = HMAC(sessionId + timestamp, serverSecret)    │
│                                                             │
│  Token được gửi qua 2 channels:                            │
│  1. Cookie (HttpOnly: false, SameSite: Strict)            │
│  2. Response body/meta tag/form hidden field               │
│                                                             │
│  Legitimate request flow:                                   │
│  ┌──────────┐    Form submit     ┌──────────┐             │
│  │ Browser  │ ─────────────────► │  Server  │             │
│  │          │  Cookie: CSRF-TOKEN │          │             │
│  │          │  Body: csrf-token=same_value   │             │
│  └──────────┘                    └──────────┘             │
│                                       │                     │
│                                       ▼                     │
│                              Compare cookie vs body         │
│                              Match → Process                │
│                                                             │
│  CSRF attack flow:                                          │
│  ┌──────────┐    <img> request   ┌──────────┐             │
│  │ Browser  │ ─────────────────► │  Server  │             │
│  │          │  Cookie: CSRF-TOKEN (auto-send)              │
│  │          │  (không thể set custom body/header từ cross-origin)
│  └──────────┘                    └──────────┘             │
│                                       │                     │
│                                       ▼                     │
│                              Missing body token             │
│                              Reject with 403                │
│                                                             │
│  BẢN CHẤT BẢO VỆ:                                          │
│  Attacker không thể đọc cookie (Same-Origin Policy)        │
│  → Không thể lấy token để gửi kèm request                  │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

#### SameSite Cookies: Defense hiện đại

```
┌─────────────────────────────────────────────────────────────┐
│  SAMESITE COOKIE BEHAVIOR                                   │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Set-Cookie: session=abc123; SameSite=Strict               │
│                                                             │
│  ┌─────────────────┬──────────────────┬──────────────────┐ │
│  │   Request Type  │  SameSite=Strict │ SameSite=Lax     │ │
│  ├─────────────────┼──────────────────┼──────────────────┤ │
│  │ Link từ site A  │                  │                  │ │
│  │ → Site B        │  ❌ KHÔNG gửi    │  ✅ Gửi cookie   │ │
│  ├─────────────────┼──────────────────┼──────────────────┤ │
│  │ Form POST từ A  │                  │                  │ │
│  │ → Site B        │  ❌ KHÔNG gửi    │  ❌ KHÔNG gửi    │ │
│  ├─────────────────┼──────────────────┼──────────────────┤ │
│  │ GET từ A → B    │  ❌ KHÔNG gửi    │  ✅ Gửi cookie   │ │
│  │ (top-level nav) │                  │                  │ │
│  ├─────────────────┼──────────────────┼──────────────────┤ │
│  │ AJAX/Fetch từ A │                  │                  │ │
│  │ → B (CORS)      │  ❌ KHÔNG gửi    │  ❌ KHÔNG gửi    │ │
│  └─────────────────┴──────────────────┴──────────────────┘ │
│                                                             │
│  RECOMMENDATION:                                            │
│  - API endpoints: SameSite=Strict + CSRF Token             │
│  - Regular navigation: SameSite=Lax                        │
│  - Third-party integrations: SameSite=None + Secure        │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

### 2.4 Secrets Management

#### Bản chất vấn đề

Secrets (passwords, API keys, private keys, tokens) là **high-value targets**. Anti-patterns phổ biến:

```
┌─────────────────────────────────────────────────────────────┐
│  ANTI-PATTERNS NGUY HIỂM                                    │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ❌ Hardcoded trong source code                            │
│     String dbPassword = "SuperSecret123!";                  │
│     → Git history forever, visible to all devs             │
│                                                             │
│  ❌ Environment variables trên shared server               │
│     export DB_PASSWORD=secret  (trong .bashrc)             │
│     → Visible via /proc/<pid>/environ to all users         │
│     → Logged by process monitoring tools                   │
│                                                             │
│  ❌ Configuration files không encrypted                    │
│     application.yml chứa plaintext secrets                 │
│     → Backup leaks, file permission mistakes               │
│                                                             │
│  ❌ Sharing secrets qua chat/email                         │
│     → Searchable, forwarded, retained indefinitely         │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

#### Defense in Depth Strategy

```
┌─────────────────────────────────────────────────────────────┐
│  SECRETS MANAGEMENT HIERARCHY                               │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  LAYER 1: Source Code                               │   │
│  │  - NO secrets in code                               │   │
│  │  - Use placeholder: ${DB_PASSWORD}                  │   │
│  │  - Git hooks scan for patterns                      │   │
│  └─────────────────────────────────────────────────────┘   │
│                          │                                  │
│  ┌───────────────────────▼───────────────────────────────┐ │
│  │  LAYER 2: Build Process                               │ │
│  │  - CI/CD injects secrets vào build environment       │ │
│  │  - Secrets từ vault (HashiCorp Vault, AWS Secrets)   │ │
│  │  - Short-lived credentials where possible            │ │
│  └───────────────────────────────────────────────────────┘ │
│                          │                                  │
│  ┌───────────────────────▼───────────────────────────────┐ │
│  │  LAYER 3: Runtime Environment                         │ │
│  │  - Container secrets (Kubernetes Secrets, Docker)    │ │
│  │  - Memory-only, not written to disk                  │ │
│  │  - Rotate frequently                                  │ │
│  └───────────────────────────────────────────────────────┘ │
│                          │                                  │
│  ┌───────────────────────▼───────────────────────────────┐ │
│  │  LAYER 4: Application                                 │ │
│  │  - Secrets loaded vào memory                          │ │
│  │  - Zero out memory khi không cần (sensitive data)    │ │
│  │  - No logging of secrets                              │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

#### HashiCorp Vault Architecture (Enterprise standard)

```
┌─────────────────────────────────────────────────────────────┐
│  VAULT DYNAMIC SECRETS FLOW                                 │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────┐    Auth (JWT/AppRole)    ┌───────────────┐    │
│  │   App   │ ───────────────────────► │  Vault Server │    │
│  │         │ ◄──────Token + Lease──── │               │    │
│  └────┬────┘                          │  ┌─────────┐  │    │
│       │                               │  │ Secret  │  │    │
│       │  Request DB credentials       │  │ Engine  │  │    │
│       ├──────────────────────────────►│  │ (MySQL) │  │    │
│       │                               │  └────┬────┘  │    │
│       │  ◄──Username/Password + TTL───│       │       │    │
│       │                               │       ▼       │    │
│       │                               │  CREATE USER  │    │
│       │                               │  'v-token-xxx'│    │
│       │                               │  WITH PASSWORD│    │
│       │                               │  'random-pwd' │    │
│       │                               │  GRANT SELECT │    │
│       ▼                               │               │    │
│  ┌─────────┐                          └───────────────┘    │
│  │Database │                                                │
│  │         │  Credentials auto-expire after TTL            │
│  │         │  Vault can revoke immediately if needed       │
│  └─────────┘                                                │
│                                                             │
│  BENEFIT:                                                   │
│  - Credentials short-lived (minutes/hours, not months)     │
│  - Each service có credentials riêng                       │
│  - Audit log đầy đủ                                         │
│  - Instant revocation khi compromise                       │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Rủi ro, Anti-patterns, Lỗi thường gặp

### SQL Injection

| Anti-pattern | Tại sao nguy hiểm | Detection |
|--------------|-------------------|-----------|
| `+ "'" + input + "'"` | Dễ bypass với `'` | Code review, SAST |
| `LIKE '%" + input + "%'` | Partial protection | Pattern matching |
| Dynamic column names: `"ORDER BY " + column` | No placeholder support | Architecture review |
| `IN (?)` với list concatenation | String building for IN clause | Code review |

### XSS

| Anti-pattern | Tại sao nguy hiểm | Detection |
|--------------|-------------------|-----------|
| `innerHTML = userInput` | Executes scripts | SAST, DAST |
| `"onclick=" + userInput` | Event handler injection | Code review |
| JSON without proper Content-Type | MIME sniffing attacks | Security headers scan |
| Template engines without auto-escape | Developer forgets | Framework audit |

### CSRF

| Anti-pattern | Tại sao nguy hiểm | Detection |
|--------------|-------------------|-----------|
| GET requests có side effects | Link-based attacks | API design review |
| Missing CSRF token on AJAX | Modern apps forget | DAST |
| Token in cookie only | Not actually protected | Code review |
| Predictable token (timestamp-based) | Brute-forcable | Token analysis |

### Secrets

| Anti-pattern | Tại sao nguy hiểm | Detection |
|--------------|-------------------|-----------|
| Committed to git | Permanent exposure | git-secrets, truffleHog |
| In environment variables | /proc exposure, logging | Process inspection |
| No rotation | Long attack window | Secret scanning |
| Same secret for all envs | Dev breach → prod breach | Architecture review |

---

## 4. Khuyến nghị thực chiến trong Production

### SQL Injection

1. **Bắt buộc PreparedStatement** cho TẤT CẢ user input
2. **Whitelist validation** cho dynamic identifiers (table/column names)
3. **ORM với parameterized queries** cho đa số use case
4. **WAF rules** nhưng không tin tưởng hoàn toàn
5. **Database user permissions** theo principle of least privilege

### XSS

1. **Template engine với auto-escape** (Thymeleaf, Handlebars)
2. **Content Security Policy** với strict directives
3. **Context-aware encoding libraries** (OWASP Java Encoder)
4. **HttpOnly cookies** cho session tokens
5. **X-XSS-Protection: 0** (disable browser XSS filter, unreliable)

### CSRF

1. **SameSite=Lax/Strict** cho session cookies
2. **Double-submit cookie pattern** cho stateless APIs
3. **CSRF token** cho form submissions
4. **Proper CORS configuration** - không dùng `*`
5. **Custom headers** cho AJAX (simple requests exempt)

### Secrets Management

1. **HashiCorp Vault hoặc cloud-native** (AWS Secrets Manager, Azure Key Vault)
2. **Dynamic secrets** nếu có thể
3. **Secret rotation automation**
4. **No secrets in logs** (redaction filters)
5. **Memory-only storage** (không write ra disk)

---

## 5. Kết luận

| Lỗ hổng | Bản chất gốc | Defense cốt lõi | Defense bổ sung |
|---------|--------------|-----------------|-----------------|
| **SQL Injection** | Code/Data boundary violation | Prepared Statement | ORM, least privilege |
| **XSS** | Trust boundary violation | Output encoding | CSP, HttpOnly |
| **CSRF** | Implicit authentication abuse | SameSite + Token | Custom headers, CORS |
| **Secrets Leak** | Long-lived, high-value exposure | Dynamic secrets + Vault | Rotation, redaction |

**Tư duy cốt lõi:** Security không phải là thêm các tính năng, mà là **thiết kế đúng các boundary** và **validate mọi assumption** về trust.

**Nguyên tắc vàng:**
- Never trust user input
- Defense in depth
- Fail securely
- Least privilege
- Assume breach

---

## 6. Code References (Khi thật sự cần)

### Spring Security CSRF Configuration

```java
@Configuration
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) {
        http.csrf(csrf -> csrf
            .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
        );
        return http.build();
    }
}
```

### Vault Integration (Spring)

```java
@Configuration
public class VaultConfig extends AbstractVaultConfiguration {
    
    @Override
    public ClientAuthentication clientAuthentication() {
        return new KubernetesAuthentication(
            KubernetesAuthenticationOptions.builder()
                .role("my-app-role")
                .build(),
            restOperations()
        );
    }
    
    @Value("${database.credentials}")
    private DatabaseCredentials dbCreds;  // Auto-rotated by Vault
}
```

### Content Security Policy Header

```java
@Component
public class SecurityHeadersFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        HttpServletResponse response = (HttpServletResponse) res;
        response.setHeader("Content-Security-Policy", 
            "default-src 'self'; " +
            "script-src 'self' 'nonce-{random}'; " +
            "object-src 'none'; " +
            "base-uri 'self';"
        );
        chain.doFilter(req, res);
    }
}
```
