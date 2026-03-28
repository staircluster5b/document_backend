# API Security: Rate Limiting, Input Validation, CORS

## 1. Mục tiêu của task

Nghiên cứu ba trụ cột bảo mật API trong production:
- **Rate Limiting**: Kiểm soát lưu lượng request để bảo vệ hệ thống khỏi abuse và DDoS
- **Input Validation**: Xác thực và làm sạch dữ liệu đầu vào để ngăn chặn injection attacks
- **CORS**: Quản lý cross-origin requests một cách an toàn

> **Tầm quan trọng**: Theo OWASP API Security Top 10 2023, Broken Object Level Authorization (#1) và Broken Authentication (#2) liên quan trực tiếp đến input validation, còn Lack of Resources & Rate Limiting (#4) là nguyên nhân hàng đầu gây sập hệ thống.

---

## 2. Rate Limiting - Bản chất và cơ chế

### 2.1. Bản chất vấn đề

Rate limiting không chỉ là "đếm request" - đó là việc **quản lý tài nguyên có hạn** trong distributed system:

```
Client Request → Gateway/Proxy → Backend Service → Database
                    ↑
              Rate Limiter
              (Resource Guard)
```

**Mục tiêu thiết kế**:
1. **Fairness**: Ngăn single client chiếm dụng tài nguyên
2. **Protection**: Bảo vệ backend khỏi overload
3. **Cost Control**: Giới hạn resource consumption (đặc biệt với cloud billing)

### 2.2. Các thuật toán Rate Limiting

#### Fixed Window Counter
```
Time:  |--0-10s--|--10-20s--|--20-30s--|
Limit:     100        100        100
Count:      95         98        102 (REJECT)
```

- **Triển khai**: Đơn giản, dùng Redis INCR với TTL
- **Vấn đề**: "Thundering herd" ở window boundary - client có thể gửi 2x limit trong 1s cuối và 1s đầu window mới

#### Sliding Window Log
Lưu timestamp từng request, đếm trong khoảng thờI gian trượt.

- **Ưu điểm**: Chính xác, không có burst ở boundary
- **Nhược điểm**: Memory overhead O(n), cleanup cost cao

#### Sliding Window Counter (Hybrid)
Kết hợp fixed window hiện tại + weighted previous window.

```
Current: 50 requests trong window hiện tại (đã qua 30%)
Previous: 80 requests trong window trước
Estimate: 50 + (80 * 0.7) = 106
```

- **Trade-off**: 90% accuracy, nhưng O(1) memory và computation
- **Khuyến nghị**: Đây là giải pháp thực tế nhất cho hệ thống lớn

#### Token Bucket
```
Bucket capacity: 100 tokens
Refill rate: 10 tokens/second

Mỗi request tiêu thụ 1 token.
Nếu bucket rỗng → reject.
```

- **Ưu điểm**: Cho phép burst ngắn hạn (burst = capacity)
- **Ứng dụng**: Phù hợp cho API có traffic pattern không đều
- **Triển khai**: Redis Lua script để đảm bảo atomicity

#### Leaky Bucket
Request vào queue, xử lý với tốc độ cố định.

- **Ưu điểm**: Smooth traffic, predictability
- **Nhược điểm**: Không cho phép burst, phức tạp implement

### 2.3. Triển khai trong Java/Spring Boot

**Redis + Lua Script (Token Bucket)**:
```lua
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(bucket[1]) or capacity
local last_refill = tonumber(bucket[2]) or now

local delta = math.max(0, now - last_refill)
tokens = math.min(capacity, tokens + delta * refill_rate)

if tokens >= 1 then
    tokens = tokens - 1
    redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
    redis.call('EXPIRE', key, 60)
    return 1  -- Allowed
else
    redis.call('HSET', key, 'last_refill', now)
    return 0  -- Rejected
end
```

**Tại sao dùng Lua?** Đảm bảo atomicity - race condition giữa GET và SET sẽ bypass limit.

### 2.4. Distributed Rate Limiting Challenges

| Challenge | Giải pháp | Trade-off |
|-----------|-----------|-----------|
| Redis single point of failure | Redis Cluster / Sentinel | Complexity tăng, consistency eventual |
| Cross-datacenter latency | Local cache + async sync | Stale limit, burst tolerance |
| Hot key problem | Hash tag hoặc multiple keys | Sharding complexity |
| Clock skew | Logical timestamps / vector clocks | Performance overhead |

**Hot Key Problem**: Với 100k req/s đến cùng 1 key Redis, single thread của Redis sẽ bottleneck.
- Giải pháp: Sharding key thành `rate_limit:{user_id}:0`, `rate_limit:{user_id}:1`,... và lấy min/max tùy use case.

### 2.5. Multi-Level Rate Limiting

Production nên áp dụng nhiều tầng:

```
Layer 1: Edge/CDN (CloudFlare, AWS WAF) - IP-based, 10k req/s
Layer 2: API Gateway (Kong, Envoy) - User/API-key based, 1k req/s  
Layer 3: Service mesh (Istio) - Service-to-service, 100 req/s
Layer 4: Application (Spring Boot) - Business logic, 10 req/s
```

> **Nguyên tắc**: Fail fast - reject càng sớm càng tốt để tiết kiệm resource.

### 2.6. Rate Limit Response Headers

Theo RFC 6585 và industry standard:

```http
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1640995200
Retry-After: 3600
```

- **429 vs 503**: 429 = client fault (too many requests), 503 = server fault (overloaded)
- **Retry-After**: Cho phép client exponential backoff đúng cách

---

## 3. Input Validation - Bản chất và cơ chế

### 3.1. Bản chất vấn đề

Input validation là **biên giới tin cậy (trust boundary)** giữa thế giới bên ngoài (không tin cậy) và hệ thống bên trong (tin cậy).

```
Untrusted Input → Validation Layer → Sanitization → Business Logic
                        ↓
                   Reject if invalid
```

**Các dạng tấn công qua input**:
1. **Injection**: SQL, NoSQL, Command, LDAP, XPath
2. **Path Traversal**: `../../../etc/passwd`
3. **XXE**: XML External Entity attacks
4. **Deserialization**: Malicious object injection
5. **File Upload**: Web shell qua image upload

### 3.2. Defense in Depth

Không nên dựa vào 1 layer validation:

| Layer | Validation Type | Ví dụ |
|-------|----------------|-------|
| Client | UX improvement | HTML5 form validation, JavaScript |
| Gateway | Format check | JSON schema, size limit |
| Controller | Business rule | @Valid, @NotNull, @Size |
| Service | Domain invariant | Custom validator |
| Database | Integrity constraint | NOT NULL, CHECK, FOREIGN KEY |

> **Quy tắc vàng**: Client validation là cho UX, server validation là cho security. Never trust client.

### 3.3. Java Bean Validation (Jakarta Validation)

**Built-in constraints**:
```java
public class UserRegistration {
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 255)
    private String email;
    
    @NotNull
    @Size(min = 8, max = 128)
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
             message = "Password must contain uppercase, lowercase, and digit")
    private String password;
    
    @Min(18)
    @Max(120)
    private Integer age;
}
```

**Custom validator cho business rule phức tạp**:
```java
@Constraint(validatedBy = ValidOrderValidator.class)
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidOrder {
    String message() default "Invalid order";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

public class ValidOrderValidator implements ConstraintValidator<ValidOrder, Order> {
    @Override
    public boolean isValid(Order order, ConstraintValidatorContext context) {
        // Business rule: delivery date must be after order date + 1 day
        if (order.getDeliveryDate() != null && order.getOrderDate() != null) {
            return order.getDeliveryDate().isAfter(
                order.getOrderDate().plusDays(1)
            );
        }
        return true;
    }
}
```

### 3.4. SQL Injection Prevention

**Vulnerability**:
```java
// NEVER DO THIS
String query = "SELECT * FROM users WHERE username = '" + username + "'";
```

**Parameterized Queries (PreparedStatement)**:
```java
// JDBC
String sql = "SELECT * FROM users WHERE username = ? AND status = ?";
PreparedStatement stmt = conn.prepareStatement(sql);
stmt.setString(1, username);
stmt.setString(2, status);
```

**JPA/Hibernate**:
```java
// JPQL với named parameters
@Query("SELECT u FROM User u WHERE u.username = :username")
User findByUsername(@Param("username") String username);

// Method derivation - an toàn
User findByUsernameAndStatus(String username, UserStatus status);
```

**Tại sao parameterized query an toàn?** 
- Parameters được xử lý như **data**, không phải **code**
- Database driver escape special characters tự động
- Query plan được compile trước khi bind parameters

### 3.5. Mass Assignment Protection

**Vấn đề**: Client gửi thêm field không mong muốn:
```json
{
    "username": "john",
    "email": "john@example.com",
    "role": "ADMIN",  // ← Malicious!
    "isAdmin": true   // ← Malicious!
}
```

**Giải pháp**:
```java
// 1. DTO pattern - chỉ expose fields cần thiết
public class UserRegistrationDto {
    private String username;
    private String email;
    private String password;
    // Không có role, isAdmin
}

// 2. @JsonIgnoreProperties(ignoreUnknown = true) - reject unknown fields
// 3. whitelist approach: chỉ copy explicitly allowed fields
```

### 3.6. File Upload Security

**Rủi ro**:
1. **Extension spoofing**: `shell.php.jpg`
2. **MIME type bypass**: Gửi PHP nhưng claim là image/jpeg
3. **Path traversal**: `../../../var/www/shell.php`
4. **Size DoS**: Upload file 10GB

**Best practices**:
```java
@Service
public class FileUploadService {
    
    private static final long MAX_SIZE = 10 * 1024 * 1024; // 10MB
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png");
    
    public String upload(MultipartFile file) {
        // 1. Size check
        if (file.getSize() > MAX_SIZE) {
            throw new FileTooLargeException();
        }
        
        // 2. Content type validation (don't trust file.getContentType())
        String detectedType = detectMimeType(file.getInputStream());
        if (!ALLOWED_TYPES.contains(detectedType)) {
            throw new InvalidFileTypeException();
        }
        
        // 3. Extension whitelist
        String extension = getExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new InvalidFileExtensionException();
        }
        
        // 4. Store outside web root, random filename
        String storedName = UUID.randomUUID() + "." + extension;
        Path target = uploadDir.resolve(storedName);
        
        // 5. Verify it's actually an image (magic bytes)
        if (!isValidImage(file.getInputStream())) {
            throw new InvalidImageException();
        }
        
        Files.copy(file.getInputStream(), target);
        return storedName;
    }
}
```

### 3.7. Output Encoding

Validation là "đầu vào", encoding là "đầu ra". Cả hai cần thiết:

```java
// XSS prevention
String safeHtml = HtmlUtils.htmlEscape(userInput);

// URL parameter encoding  
String safeUrl = UriUtils.encodePathSegment(userInput, StandardCharsets.UTF_8);

// JavaScript context
String safeJs = StringEscapeUtils.escapeEcmaScript(userInput);
```

---

## 4. CORS - Cross-Origin Resource Sharing

### 4.1. Bản chất Same-Origin Policy (SOP)

Browser implement **Same-Origin Policy** để ngăn malicious website đọc dữ liệu từ origin khác:

```
Origin A: https://bank.com
Origin B: https://evil.com

SOP: evil.com KHÔNG ĐƯỢC đọc response từ bank.com
```

**Origin = Protocol + Host + Port**:
- `https://example.com:443` ≠ `http://example.com:80`
- `https://api.example.com` ≠ `https://app.example.com`

### 4.2. CORS là gì?

CORS là cơ chế **nới lỏng** SOP một cách có kiểm soát. Server chủ động cho phép origin nào được access.

**CORS là server-side security**, không phải client-side. Browser chỉ enforce quyết định của server.

### 4.3. CORS Request Types

#### Simple Request
Điều kiện:
- Method: GET, HEAD, POST
- Headers: chỉ CORS-safelisted headers (Accept, Content-Type: application/x-www-form-urlencoded, multipart/form-data, text/plain)
- Không có custom headers

```http
GET /api/data HTTP/1.1
Host: api.example.com
Origin: https://app.example.com

HTTP/1.1 200 OK
Access-Control-Allow-Origin: https://app.example.com
Content-Type: application/json
```

#### Preflight Request (OPTIONS)
Với các request "phức tạp" hơn, browser gửi OPTIONS trước:

```http
OPTIONS /api/data HTTP/1.1
Host: api.example.com
Origin: https://app.example.com
Access-Control-Request-Method: PUT
Access-Control-Request-Headers: Content-Type, Authorization

HTTP/1.1 204 No Content
Access-Control-Allow-Origin: https://app.example.com
Access-Control-Allow-Methods: GET, POST, PUT
Access-Control-Allow-Headers: Content-Type, Authorization
Access-Control-Max-Age: 86400
```

> **Cache preflight**: Max-age cho phép browser cache kết quả preflight, giảm latency.

### 4.4. CORS Headers Chi tiết

| Header | Ý nghĩa | Security Note |
|--------|---------|---------------|
| `Access-Control-Allow-Origin` | Origin được phép | `*` chỉ được dùng với unauthenticated requests |
| `Access-Control-Allow-Credentials` | Cho phép cookies/auth headers | Khi true, origin KHÔNG ĐƯỢC là `*` |
| `Access-Control-Allow-Methods` | Methods được phép | Nên whitelist thay vì reflect |
| `Access-Control-Allow-Headers` | Headers được phép | Validate, không reflect client input |
| `Access-Control-Expose-Headers` | Headers client JS có thể đọc | Mặc định chỉ 6 simple response headers |
| `Access-Control-Max-Age` | Preflight cache duration | Balance giữa flexibility và security |

### 4.5. Spring Boot CORS Configuration

**Global configuration**:
```java
@Configuration
public class CorsConfig {
    
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        
        // Whitelist origins - KHÔNG dùng * trong production
        config.setAllowedOrigins(Arrays.asList(
            "https://app.example.com",
            "https://admin.example.com"
        ));
        
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE"));
        config.setAllowedHeaders(Arrays.asList("Content-Type", "Authorization"));
        config.setExposedHeaders(Arrays.asList("X-Request-Id"));
        config.setAllowCredentials(true); // Cho phép cookies
        config.setMaxAge(3600L); // 1 giờ cache
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
```

**Annotation-based**:
```java
@RestController
@CrossOrigin(
    origins = {"https://trusted-domain.com"},
    methods = {RequestMethod.GET, RequestMethod.POST},
    allowedHeaders = {"Content-Type", "Authorization"},
    allowCredentials = "true",
    maxAge = 3600
)
public class MyController { }
```

### 4.6. CORS Security Pitfalls

#### Pitfall 1: Dynamic Origin Reflection
```java
// VULNERABLE - Đừng bao giờ làm thế này
@GetMapping
public ResponseEntity<?> get(HttpServletRequest request) {
    String origin = request.getHeader("Origin");
    return ResponseEntity.ok()
        .header("Access-Control-Allow-Origin", origin) // ← REFLECT
        .header("Access-Control-Allow-Credentials", "true")
        .body(data);
}
```
**Tấn công**: Attacker tạo site `evil.com`, victim đăng nhập bank.com (có session cookie), truy cập evil.com → evil.com gửi request đến bank.com với Origin: evil.com → bank reflect `Access-Control-Allow-Origin: evil.com` → Browser cho phép evil.com đọc response!

#### Pitfall 2: Wildcard với Credentials
```http
Access-Control-Allow-Origin: *
Access-Control-Allow-Credentials: true
```
**Browser sẽ REJECT** - đây là spec violation vì security risk.

#### Pitfall 3: Null Origin
```java
// VULNERABLE
if (origin == null || allowedOrigins.contains(origin)) {
    allow(origin);
}
```
File:// URL, sandboxed iframe, redirect đều có thể gửi `Origin: null`. Null là valid origin và có thể bypass whitelist!

#### Pitfall 4: Subdomain Takeover
```java
config.setAllowedOrigins(Arrays.asList("*.example.com"));
```
Nếu attacker chiếm subdomain `expired.example.com`, họ có quyền CORS!

### 4.7. CORS vs CSRF Protection

CORS và CSRF là **2 vấn đề khác nhau**:

| Aspect | CORS | CSRF |
|--------|------|------|
| **Loại request** | Cross-origin | Same-origin (forged) |
| **Mục tiêu** | Read cross-origin data | Perform action as victim |
| **Protection** | Server headers | CSRF tokens, SameSite cookies |

**SameSite Cookies** (modern CSRF protection):
```http
Set-Cookie: session=abc123; SameSite=Strict; Secure; HttpOnly
```

- `Strict`: Cookie không gửi khi cross-origin navigation
- `Lax`: Cho phép top-level GET (khi click link)
- `None`: Cho phép cross-origin, bắt buộc `Secure`

---

## 5. So sánh và Trade-offs

### 5.1. Rate Limiting Algorithms

| Algorithm | Accuracy | Memory | Burst Support | Best For |
|-----------|----------|--------|---------------|----------|
| Fixed Window | Low | O(1) | No | Simple use cases |
| Sliding Window Log | High | O(n) | No | Strict compliance |
| Sliding Window Counter | Medium | O(1) | No | General purpose |
| Token Bucket | Medium | O(1) | Yes | Bursty traffic |
| Leaky Bucket | Medium | O(1) | No | Smooth output |

### 5.2. Validation Strategies

| Strategy | Performance | Security | Maintainability | When to Use |
|----------|-------------|----------|-----------------|-------------|
| Bean Validation | Good | Good | Excellent | Standard input |
| Manual validation | Best | Best | Poor | Complex business rules |
| Schema validation (JSON) | Medium | Good | Good | API contracts |
| Type safety (records) | Best | Good | Excellent | Java 16+ |

### 5.3. CORS Approaches

| Approach | Security | Flexibility | Complexity |
|----------|----------|-------------|------------|
| Global whitelist | High | Low | Low |
| Per-endpoint annotation | Medium | High | Medium |
| Dynamic validation | High (if done right) | Highest | High |
| Wildcard (*) | Low | Highest | Lowest |

---

## 6. Rủi ro và Anti-patterns

### Rate Limiting

❌ **Anti-pattern 1**: Chỉ limit ở application layer
```
Attacker → Load Balancer → [100 instances] → Each allows 100 req/s
Total: 10,000 req/s bypass!
```
✅ **Fix**: Limit ở edge/gateway layer.

❌ **Anti-pattern 2**: Không có graceful degradation
- Reject hoàn toàn khi limit hit
✅ **Fix**: Queue, throttle, hoặc return cached response.

❌ **Anti-pattern 3**: Global limit không có per-user limit
- 1 user có thể consume toàn bộ quota
✅ **Fix**: Multi-tier: global + per-user + per-IP.

### Input Validation

❌ **Anti-pattern 1**: Blacklist (block known bad)
```java
if (input.contains("<script>")) reject(); // Bypass: <ScRiPt>
```
✅ **Fix**: Whitelist (allow known good), hoặc proper encoding.

❌ **Anti-pattern 2**: Validation ở client-side only
✅ **Fix**: Server-side validation là bắt buộc.

❌ **Anti-pattern 3**: Log injection
```java
logger.info("User login: " + username); // username = "admin\nWARN: Password expired"
```
✅ **Fix**: Sanitize input trước khi log.

### CORS

❌ **Anti-pattern 1**: `Access-Control-Allow-Origin: *` + `Allow-Credentials: true`
✅ **Fix**: Không bao giờ kết hợp 2 thằng này.

❌ **Anti-pattern 2**: Reflect origin header
✅ **Fix**: Strict whitelist.

❌ **Anti-pattern 3**: Allowing null origin
✅ **Fix**: Explicitly reject null nếu không cần thiết.

---

## 7. Khuyến nghị Production

### Rate Limiting

1. **Sử dụng dedicated service**: Envoy, Kong, AWS API Gateway thay vì implement trong app
2. **Implement circuit breaker**: Khi rate limiter fail (Redis down), fail open (allow) hay fail closed (reject)? Phụ thuộc vào business criticality.
3. **Differentiated limits**:
   - Free tier: 100 req/hour
   - Pro tier: 10,000 req/hour
   - Internal: Unlimited
4. **Monitoring**: Alert khi rate limit hit > 5% requests (có thể là misconfigured)

### Input Validation

1. **Fail fast**: Validate ở controller layer, tránh xử lý business logic với invalid data
2. **Standardize error response**:
```json
{
    "error": "VALIDATION_ERROR",
    "message": "Request validation failed",
    "details": [
        {"field": "email", "message": "Invalid format"},
        {"field": "age", "message": "Must be at least 18"}
    ]
}
```
3. **Use Bean Validation groups** cho different validation contexts (create vs update)
4. **Regular security scanning**: OWASP Dependency Check, Snyk

### CORS

1. **Strict whitelist**: Không dùng wildcard trong production
2. **Validate origin format**: Chống null origin bypass
3. **Consider CSP (Content Security Policy)** như layer bổ sung:
```http
Content-Security-Policy: default-src 'self'; connect-src https://api.example.com
```
4. **Monitor CORS errors**: Log preflight failures để detect misconfiguration

---

## 8. Monitoring và Observability

### Metrics cần track

| Category | Metric | Alert Threshold |
|----------|--------|----------------|
| Rate Limit | `rate_limit_rejected_total` | > 10% of total requests |
| Validation | `validation_failed_total` | Spike detection |
| CORS | `cors_preflight_total` | Baseline + 50% |
| Security | `suspicious_input_detected` | > 0 |

### Logging best practices

```java
// Correlation ID cho trace request
MDC.put("traceId", UUID.randomUUID().toString());

// Log validation failures với context (nhưng không log sensitive data!)
log.warn("Validation failed: field={}, error={}, traceId={}", 
    field, error, MDC.get("traceId"));

// Log CORS violations
log.warn("CORS blocked: origin={}, method={}, path={}",
    origin, method, path);
```

---

## 9. Kết luận

### Tóm tắt bản chất

**Rate Limiting**:
- Không phải là "chặn request", mà là **quản lý resource** trong distributed system
- Trade-off chính: Fairness vs Complexity vs Performance
- Token Bucket/Sliding Window là lựa chọn thực tế cho hầu hết hệ thống

**Input Validation**:
- Là **trust boundary** - biên giới giữa untrusted input và trusted system
- Defense in depth: validate ở mọi layer
- Whitelist > Blacklist, parameterized query > concatenation

**CORS**:
- Là **nới lỏng** Same-Origin Policy, không phải security feature mới
- Server quyết định, browser enforce
- Nguy hiểm nhất: wildcard + credentials, origin reflection

### Checklist Production

- [ ] Rate limit implement ở nhiều tầng (edge → gateway → app)
- [ ] Input validation whitelist-based, server-side mandatory
- [ ] SQL injection prevention qua parameterized queries
- [ ] CORS strict whitelist, không dùng wildcard với credentials
- [ ] File upload: type detection, size limit, random filename
- [ ] CSRF protection (tokens hoặc SameSite cookies)
- [ ] Security headers (HSTS, CSP, X-Frame-Options)
- [ ] Monitoring và alerting cho security events

### Tài liệu tham khảo

- OWASP API Security Top 10 2023
- RFC 6585 - Additional HTTP Status Codes
- RFC 7234 - HTTP Caching (Rate Limit headers)
- MDN Web Docs - CORS
- Spring Security Reference Documentation
