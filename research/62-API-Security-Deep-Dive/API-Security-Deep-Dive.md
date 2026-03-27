# API Security Deep Dive: Bảo Mật API Ở Tầng Doanh Nghiệp

> **Mục tiêu:** Hiểu sâu các cơ chế bảo mật API, trade-off giữa các phương pháp, và áp dụng đúng trong production.

---

## 1. Mục tiêu của Task

Task này tập trung vào các lớp bảo mật **tầng ứng dụng** cho API - nơi mà các lỗ hổng phổ biến nhất xảy ra trong thực tế. Khác với Authentication/Authorization (đã xác thực "ai" và "được làm gì"), API Security tập trung vào:

- **Input validation:** Dữ liệu đầu vào có độc hại không?
- **Transport security:** Dữ liệu truyền tải có an toàn không?
- **Attack prevention:** Có chống lại các tấn công phổ biến (SQLi, XSS, CSRF) không?
- **Rate limiting:** Có bảo vệ khỏi abuse và DDoS không?
- **Secret management:** Credentials và keys được lưu trữ an toàn không?

> **Quan trọng:** Theo OWASP Top 10 2021, "Broken Access Control" và "Cryptographic Failures" chiếm 2 vị trí đầu tiên. API Security không phải là "tính năng bổ sung" - nó là yêu cầu cơ bản.

---

## 2. Input Validation Frameworks

### 2.1 Bản chất vấn đề

Input validation là **tuyến phòng thủ đầu tiên và quan trọng nhất**. Nguyên tắt cốt lõi:

> **"Never trust user input"** - Dữ liệu từ bên ngoài luôn được coi là độc hại cho đến khi chứng minh ngược lại.

Vấn đề không chỉ là "check null hay không" - mà là:
- **Type safety:** String nhận được có phải là email thật không?
- **Range validation:** Số có nằm trong kỳ vọng không?
- **Format compliance:** JSON/XML có cấu trúc đúng không?
- **Business rules:** Dữ liệu có hợp lệ trong ngữ cảnh nghiệp vụ không?

### 2.2 Bean Validation (Jakarta Validation)

**Cơ chế hoạt động:**

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   HTTP Request  │────▶│  DTO + @Valid    │────▶│ Constraint      │
│   (JSON/XML)    │     │  @NotNull, etc   │     │ ValidatorImpl   │
└─────────────────┘     └──────────────────┘     └─────────────────┘
                                                          │
                              ┌───────────────────────────┘
                              ▼
                    ┌──────────────────┐
                    │ Validation Pass  │───▶ Business Logic
                    │ or Fail (400)    │
                    └──────────────────┘
```

**Vòng đờ validation trong Spring Boot:**

1. `RequestMappingHandlerAdapter` nhận request
2. `ServletModelAttributeMethodProcessor` hoặc `RequestResponseBodyMethodProcessor` xử lý
3. Nếu parameter có `@Valid`, `MethodValidationInterceptor` được gọi
4. `LocalValidatorFactoryBean` (wrapper của Hibernate Validator) thực thi validation
5. Nếu fail, `MethodArgumentNotValidException` được throw

**Các annotation cốt lõi và cách dùng đúng:**

| Annotation | Dùng khi | Lưu ý quan trọng |
|------------|----------|------------------|
| `@NotNull` | Field bắt buộc | "" và [] vẫn pass - dùng `@NotEmpty` |
| `@NotEmpty` | Collection/String/Array không rỗng | Null sẽ fail |
| `@NotBlank` | String không chỉ whitespace | Trim trước check |
| `@Size` | String/Collection trong range | Dùng cho cả min và max |
| `@Pattern` | Regex validation | Cẩn thận ReDoS với regex phức tạp |
| `@Email` | Email format | Chỉ check format cơ bản, không verify domain |
| `@Min/@Max` | Numeric bounds | Dùng với BigDecimal cho precision |
| `@Digits` | Số chữ số chính xác | Integer + fraction digits |
| `@Future/@Past` | Date validation | Có thể dùng với Clock để test |
| `@Valid` | Nested object validation | Đệ quy xuống nested objects |

**Ví dụ validation phức tạp:**

```java
public class CreateOrderRequest {
    @NotNull
    @Size(min = 1, max = 100)
    private String customerName;
    
    @NotEmpty
    @Valid  // Đệ quy validation cho nested objects
    private List<OrderItem> items;
    
    @DecimalMin("0.01")
    @Digits(integer = 10, fraction = 2)
    private BigDecimal totalAmount;
    
    @Future
    private LocalDate expectedDeliveryDate;
    
    // Custom validation annotation
    @ValidOrderStatus
    private String status;
}

public class OrderItem {
    @NotNull
    private Long productId;
    
    @Min(1)
    @Max(999)
    private Integer quantity;
}
```

### 2.3 Custom Validators

Khi business rules phức tạp hơn annotation có thể express:

**Cách 1: ConstraintValidator (phổ biến nhất)**

```java
// Step 1: Define annotation
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = OrderTotalValidator.class)
public @interface ValidOrderTotal {
    String message() default "Order total mismatch";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

// Step 2: Implement validator
public class OrderTotalValidator 
    implements ConstraintValidator<ValidOrderTotal, CreateOrderRequest> {
    
    @Override
    public boolean isValid(CreateOrderRequest request, 
                          ConstraintValidatorContext context) {
        if (request == null || request.getItems() == null) {
            return true; // Let @NotNull handle this
        }
        
        BigDecimal calculated = request.getItems().stream()
            .map(item -> item.getPrice().multiply(
                BigDecimal.valueOf(item.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
            
        return calculated.compareTo(request.getTotalAmount()) == 0;
    }
}
```

**Cách 2: Spring Validator (per-class)**

```java
@Component
public class OrderValidator implements Validator {
    @Override
    public boolean supports(Class<?> clazz) {
        return CreateOrderRequest.class.equals(clazz);
    }
    
    @Override
    public void validate(Object target, Errors errors) {
        CreateOrderRequest req = (CreateOrderRequest) target;
        
        if (req.getItems().size() > 100) {
            errors.rejectValue("items", "order.items.too-many",
                "Maximum 100 items per order");
        }
        
        // Complex cross-field validation
        if (req.isExpressDelivery() && req.getTotalAmount().compareTo(
            new BigDecimal("500")) < 0) {
            errors.reject("order.express.min-amount",
                "Express delivery requires minimum $500");
        }
    }
}
```

**Cách 3: Service Layer Validation (cuối cùng)**

```java
@Service
public class OrderService {
    public Order createOrder(CreateOrderRequest request) {
        // Validate business constraints không thể express qua annotation
        Product product = productRepo.findById(request.getProductId())
            .orElseThrow(() -> new ProductNotFoundException());
            
        if (!product.isAvailableInRegion(request.getRegion())) {
            throw new ValidationException("Product not available in region");
        }
        
        // ... proceed
    }
}
```

### 2.4 Validation Strategy - Defense in Depth

```
┌─────────────────────────────────────────────────────────────────┐
│                    LAYER 1: Client-side                          │
│  (UX improvement only - không phải security control)             │
├─────────────────────────────────────────────────────────────────┤
│                    LAYER 2: API Gateway                          │
│  Schema validation, size limits, rate limiting                   │
├─────────────────────────────────────────────────────────────────┤
│                    LAYER 3: Controller                           │
│  @Valid, @Validated, format validation                           │
├─────────────────────────────────────────────────────────────────┤
│                    LAYER 4: Service Layer                        │
│  Business rule validation, cross-field checks                    │
├─────────────────────────────────────────────────────────────────┤
│                    LAYER 5: Database                             │
│  Constraints, foreign keys, type enforcement                     │
└─────────────────────────────────────────────────────────────────┘
```

> **Lưu ý quan trọng:** Client-side validation có thể bypass hoàn toàn. Luôn validate ở server.

### 2.5 Common Pitfalls

| Pitfall | Vấn đề | Cách tránh |
|---------|--------|------------|
| `@Valid` quên ở nested objects | Nested objects không được validate | Luôn thêm `@Valid` cho nested objects |
| Validation groups không dùng đúng | Create vs Update validation khác nhau | Dùng `@Validated(OnCreate.class)` |
| Custom validator không thread-safe | Sử dụng instance variables | Không dùng instance state, hoặc `@Scope("prototype")` |
| ReDoS trong regex | `@Pattern` với regex phức tạp | Giới hạn input length trước regex, hoặc dùng RE2/J |
| Mass Assignment | Client gửi field không mong đợi | Dùng DTO, không dùng Entity trực tiếp |
| Type confusion | JSON number → Java Integer overflow | Validate range, dùng BigDecimal cho tiền tệ |

---

## 3. CSRF Protection cho Single Page Applications (SPAs)

### 3.1 Bản chất CSRF

**CSRF (Cross-Site Request Forgery)** là tấn công buộc user thực hiện action không mong muốn trên website đã authenticate.

**Cơ chế tấn công:**

```
1. User đăng nhập bank.com, cookie session được set
2. User visit evil.com trong tab khác
3. evil.com có: <form action="https://bank.com/transfer" method="POST">
                  <input name="to" value="attacker">
                  <input name="amount" value="10000">
                </form>
                <script>document.forms[0].submit()</script>
4. Browser tự động gửi cookie của bank.com theo request
5. bank.com nhận request hợp lệ với session hợp lệ → Transfer executed!
```

> **Điều kiện CSRF thành công:**
> - User đã authenticated với target site
> - Target site dùng cookie-based authentication
> - Attacker biết được URL và parameters của action

### 3.2 CSRF Defense Mechanisms

#### 3.2.1 SameSite Cookies (Modern Solution)

**Cơ chế:** Cookie chỉ được gửi khi request xuất phát từ same site.

```java
// Spring Boot configuration
@Configuration
public class CookieConfig {
    @Bean
    public CookieSameSiteSupplier cookieSameSiteSupplier() {
        return CookieSameSiteSupplier.ofStrict(); // or Lax
    }
}

// Hoặc programmatic
ResponseCookie cookie = ResponseCookie.from("session", token)
    .sameSite("Strict")  // Không gửi khi cross-site
    .httpOnly(true)
    .secure(true)
    .build();
```

| SameSite Value | Behavior | Dùng khi |
|----------------|----------|----------|
| `Strict` | Không gửi cookie cho cross-site request | Banking, high-security apps |
| `Lax` | Gửi cho top-level GET, không gửi cho POST/iframe | Most web apps (default) |
| `None` | Luôn gửi, cần `Secure` flag | Cross-site APIs (rare) |

> **Trade-off:** Strict bảo mật cao hơn nhưng có thể break user experience (ví dụ: click link từ email → phải login lại).

#### 3.2.2 Synchronizer Token Pattern (Traditional)

**Cơ chế:** Server sinh random token, client phải gửi token này trong mỗi state-changing request.

```
┌──────────┐                      ┌──────────┐
│  Client  │◄──── GET /form ─────►│  Server  │
│          │     (nhận CSRF token)│  Sinh    │
│          │◄──── Form + Token ───┤  Token   │
│          │                      │  Lưu     │
│   Lưu    │                      │  Session │
│  Token   │                      └──────────┘
│  (hidden)│
│          │──── POST /action ───►┌──────────┐
│  Gửi     │     + CSRF Token     │  Verify  │
│  Token   │                      │  Token   │
│  trong   │◄──── Success/Error ──┤  match?  │
│  header  │                      └──────────┘
│  hoặc    │
│  body    │
└──────────┘
```

**Implementation trong Spring Security:**

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                // Token lưu trong cookie, JS có thể đọc
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
            );
        return http.build();
    }
}
```

**Client-side (React/Vue/Angular):**

```javascript
// Axios interceptor - tự động gửi CSRF token
axios.interceptors.request.use(config => {
    const csrfToken = document.cookie
        .split('; ')
        .find(row => row.startsWith('XSRF-TOKEN='))
        ?.split('=')[1];
    
    if (csrfToken) {
        config.headers['X-XSRF-TOKEN'] = decodeURIComponent(csrfToken);
    }
    return config;
});
```

#### 3.2.3 Double Submit Cookie Pattern

**Cơ chế:** Token được gửi trong cookie VÀ trong header/body. Server so sánh 2 giá trị.

```
Server Response:          Client Request:
Set-Cookie: csrf=abc123   X-CSRF-Token: abc123
                           Cookie: csrf=abc123
                           
Server verifies: header token == cookie token
```

**Ưu điểm:** Không cần server-side state (scalable hơn).

**Nhược điểm:** Nếu attacker có thể set cookie (subdomain takeover, XSS), pattern bị phá vỡ.

### 3.3 CSRF cho SPAs vs Traditional Apps

| Approach | Traditional Server-Rendered | SPA (React/Vue/Angular) |
|----------|----------------------------|-------------------------|
| **Primary Defense** | SameSite=Lax + CSRF Token | SameSite=Strict/Lax |
| **Secondary Defense** | CSRF Token in form | Double-submit cookie hoặc custom header |
| **Token Storage** | Hidden form field | Memory (không lưu localStorage) |
| **Header Name** | N/A hoặc X-CSRF-Token | X-Requested-With, X-CSRF-Token |

### 3.4 Custom Headers - Implicit CSRF Defense

```javascript
// SPA tự động gửi custom header
fetch('/api/transfer', {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'X-Requested-With': 'XMLHttpRequest'  // Custom header
    },
    body: JSON.stringify(data)
});
```

**Cơ chế bảo vệ:** Browser CORS preflight sẽ block cross-origin request với custom headers trừ khi server explicitly allow.

> **Lưu ý:** Đây là defense phụ, không thay thế SameSite cookies hoặc CSRF tokens.

### 3.5 Common Pitfalls

| Pitfall | Vấn đề | Cách tránh |
|---------|--------|------------|
| CSRF token trong localStorage | XSS có thể đọc và sử dụng | Lưu trong httpOnly cookie hoặc memory |
| GET request có side effects | Attacker có thể dùng `<img src=...>` | Tuân thủ HTTP semantics - GET không modify state |
| CORS misconfiguration | `Access-Control-Allow-Origin: *` với credentials | Luôn specify origin cụ thể |
| Subdomain shared cookie | `Domain=.example.com` cho phép subdomain set cookie | Dùng `__Host-` prefix hoặc Strict SameSite |

---

## 4. Rate Limiting Algorithms

### 4.1 Bản chất và Mục tiêu

**Mục tiêu của rate limiting:**
1. **Abuse prevention:** Ngăn brute force, scraping
2. **Resource protection:** Tránh resource exhaustion
3. **Fair usage:** Đảm bảo service cho tất cả users
4. **Cost control:** Giới hạn API calls cho billing

**Các chiều rate limiting:**
- **User-based:** Per user/account
- **IP-based:** Per IP address
- **Endpoint-based:** Per API endpoint
- **Global:** Toàn hệ thống

### 4.2 Token Bucket Algorithm

**Cơ chế:** Bucket có capacity cố định, tokens được thêm vào với tốc độ constant.

```
Bucket Capacity: 10 tokens
Refill Rate: 1 token/second

Time 0:  [██████████]  (10 tokens) - Request uses 1 → [████████░] (9)
Time 1:  [██████████]  (refill 1)  - Request uses 1 → [████████░] (9)  
Time 2:  [██████████]  (refill 1)  - Request burst 5  → [████░] (5)
```

**Đặc điểm:**
- **Burst-friendly:** Cho phép burst requests đến capacity
- **Smooth refill:** Token thêm đều đặn theo thời gian
- **Memory efficient:** Chỉ cần lưu: current tokens, last refill time

**Implementation với Redis:**

```java
@Component
public class TokenBucketRateLimiter {
    private final StringRedisTemplate redis;
    
    public boolean allowRequest(String key, int capacity, int refillRate) {
        String lua = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            
            local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
            local tokens = tonumber(bucket[1]) or capacity
            local last_refill = tonumber(bucket[2]) or now
            
            -- Calculate tokens to add
            local elapsed = now - last_refill
            local new_tokens = math.min(capacity, tokens + elapsed * refill_rate)
            
            if new_tokens >= 1 then
                new_tokens = new_tokens - 1
                redis.call('HMSET', key, 'tokens', new_tokens, 'last_refill', now)
                redis.call('EXPIRE', key, 60)
                return 1
            else
                redis.call('HMSET', key, 'tokens', new_tokens, 'last_refill', now)
                return 0
            end
            """;
            
        Long result = redis.execute(
            new DefaultRedisScript<>(lua, Long.class),
            Collections.singletonList(key),
            String.valueOf(capacity),
            String.valueOf(refillRate),
            String.valueOf(System.currentTimeMillis() / 1000)
        );
        
        return result == 1;
    }
}
```

### 4.3 Sliding Window Algorithm

**Cơ chế:** Đếm requests trong window time cố định (ví dụ: 100 requests/15 phút), nhưng window "trượt" theo thời gian.

```
Fixed Window:                    Sliding Window:

Hour 1: [|||||.....] 5/10       T+0:   [|||.......] 3/10
Hour 2: [||||||||||] 10/10      T+30:  [.|||......] 3/10 (1 expired, 1 new)
                                T+60:  [..|||.....] 3/10
                                
Fixed window bug at boundary: User có thể gửi 10 requests at 11:59 và 10 at 12:01
= 20 requests trong 2 phút với limit 10/hour!
```

**Sliding Window Log (chính xác nhất):**

```java
@Component
public class SlidingWindowRateLimiter {
    private final StringRedisTemplate redis;
    
    public boolean allowRequest(String key, int capacity, Duration window) {
        String lua = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            
            -- Remove old entries outside window
            local window_start = now - window
            redis.call('ZREMRANGEBYSCORE', key, 0, window_start)
            
            -- Count current requests
            local current = redis.call('ZCARD', key)
            
            if current < capacity then
                redis.call('ZADD', key, now, now .. ':' .. redis.call('INCR', key .. ':counter'))
                redis.call('EXPIRE', key, window)
                return 1
            else
                return 0
            end
            """;
            
        Long result = redis.execute(
            new DefaultRedisScript<>(lua, Long.class),
            Collections.singletonList(key),
            String.valueOf(capacity),
            String.valueOf(window.toMillis()),
            String.valueOf(System.currentTimeMillis())
        );
        
        return result == 1;
    }
}
```

**Memory cost:** O(n) với n = số requests trong window.

### 4.4 Sliding Window Counter (approximation)

**Cơ chế:** Kết hợp fixed window đơn giản với ước lượng từ window trước.

```
Current: 10 requests in current window (50% elapsed)
Previous: 20 requests in previous window

Estimated = Current + (Previous × (1 - elapsed%))
          = 10 + (20 × 0.5) = 20
```

**Ưu điểm:** O(1) memory, không cần lưu individual timestamps.

### 4.5 So sánh các Algorithms

| Algorithm | Burst Handling | Memory | Precision | Implementation Complexity |
|-----------|---------------|--------|-----------|--------------------------|
| **Token Bucket** | Tốt (đến capacity) | O(1) | Cao | Trung bình |
| **Fixed Window** | Không tốt | O(1) | Thấp (boundary issue) | Đơn giản |
| **Sliding Window Log** | Không tốt | O(n) | Cao nhất | Phức tạp |
| **Sliding Window Counter** | Không tốt | O(1) | Trung bình | Trung bình |
| **Leaky Bucket** | Không cho phép | O(1) | Cao | Trung bình |

> **Leaky Bucket:** Request vào queue, xử lý với tốc độ constant. Không cho burst nhưng smoothing tốt hơn.

### 4.6 Rate Limiting trong Production

**Response headers (RFC 6585):**

```http
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1640995200
Retry-After: 3600
```

**Tiered rate limits:**

```java
@Component
public class TieredRateLimiter {
    public boolean checkRateLimit(String apiKey, String endpoint) {
        Tier tier = getTier(apiKey); // FREE, BASIC, PRO, ENTERPRISE
        
        return switch (tier) {
            case FREE -> checkLimit(apiKey, 100, Duration.ofHours(1));
            case BASIC -> checkLimit(apiKey, 1000, Duration.ofHours(1));
            case PRO -> checkLimit(apiKey, 10000, Duration.ofMinutes(1));
            case ENTERPRISE -> checkLimit(apiKey, 100000, Duration.ofMinutes(1));
        };
    }
}
```

**Distributed rate limiting:**
- Centralized: Redis, Memcached
- Local + Sync: Each node tracks locally, periodically sync to central
- Gossip protocol: Nodes exchange rate limit state

---

## 5. SQL Injection Prevention

### 5.1 Bản chất SQL Injection

**Cơ chế tấn công:** User input được chèn trực tiếp vào SQL query mà không validate/escape.

**Ví dụ tấn công:**

```java
// Vulnerable code
String query = "SELECT * FROM users WHERE username = '" + username + "'";
// Input: username = "admin' OR '1'='1"
// Result: SELECT * FROM users WHERE username = 'admin' OR '1'='1'
//         → Returns all users!

// Input: username = "'; DROP TABLE users; --"
// Result: SELECT * FROM users WHERE username = ''; DROP TABLE users; --'
//         → Users table deleted!
```

### 5.2 Parameterized Queries (Prepared Statements)

**Cơ chế bảo vệ:** SQL structure và data được tách biệt hoàn toàn.

```
Client: "SELECT * FROM users WHERE username = ? AND password = ?"
        params: ["admin' OR '1'='1", "password123"]
        
Server parse: Query structure được parse trước
Server execute: Parameters được bind vào placeholders
              → 'admin' OR '1'='1' được treat như string literal
              → Không thể thay đổi query structure
```

**Implementation:**

```java
// JDBC - Correct
String sql = "SELECT * FROM users WHERE username = ? AND active = ?";
try (PreparedStatement stmt = conn.prepareStatement(sql)) {
    stmt.setString(1, username);  // Escaped automatically
    stmt.setBoolean(2, true);
    ResultSet rs = stmt.executeQuery();
}

// Spring Data JPA - Correct
@Query("SELECT u FROM User u WHERE u.username = :username AND u.active = true")
User findByUsername(@Param("username") String username);

// MyBatis - Correct
<select id="findUser" resultType="User">
    SELECT * FROM users WHERE username = #{username}
</select>
```

### 5.3 ORM và SQL Injection

| ORM | Safe? | Lưu ý |
|-----|-------|-------|
| **JPA/Hibernate** | ✅ An toàn với JPQL/HQL | ⚠️ Native queries có thể vulnerable |
| **MyBatis** | ✅ An toàn với `#{}` | ❌ `${}` không safe - dùng cho column/table names only |
| **jOOQ** | ✅ Type-safe | ✅ Generated code prevents injection |
| **Spring JDBC** | ✅ An toàn với `?` placeholders | ❌ String concatenation vẫn vulnerable |

**Hibernate native query risk:**

```java
// Vulnerable!
@Query(value = "SELECT * FROM users WHERE name = '" + name + "'", nativeQuery = true)
List<User> findByNameUnsafe(String name);

// Safe
@Query(value = "SELECT * FROM users WHERE name = :name", nativeQuery = true)
List<User> findByNameSafe(@Param("name") String name);
```

### 5.4 MyBatis - Phân biệt #{} và ${}

```xml
<!-- #{} - Parameterized (SAFE) -->
<select id="findById" resultType="User">
    SELECT * FROM users WHERE id = #{id}
    <!-- Generates: SELECT * FROM users WHERE id = ? -->
</select>

<!-- ${} - String substitution (UNSAFE for user input) -->
<select id="findByColumn" resultType="User">
    SELECT * FROM users ORDER BY ${columnName}
    <!-- Generates: SELECT * FROM users ORDER BY username -->
</select>
```

> **Rule:** `${}` chỉ dùng cho column/table names từ whitelist, KHÔNG BAO GIỜ cho user input.

### 5.5 Additional Defenses

**Input validation:**

```java
public class UserSearchRequest {
    @Pattern(regexp = "^[a-zA-Z0-9_]{3,20}$")
    private String username;
    
    @Min(1)
    @Max(1000)
    private Integer pageSize;
}
```

**Least privilege database user:**

```sql
-- Application user - READ ONLY cho báo cáo
CREATE USER 'app_readonly'@'%' IDENTIFIED BY 'password';
GRANT SELECT ON report_schema.* TO 'app_readonly'@'%';

-- Application user - FULL ACCESS cho business logic
CREATE USER 'app_write'@'%' IDENTIFIED BY 'password';
GRANT SELECT, INSERT, UPDATE ON business_schema.* TO 'app_write'@'%';
REVOKE DELETE ON business_schema.* FROM 'app_write'@'%';  -- No DELETE
```

**Web Application Firewall (WAF):**
- ModSecurity với OWASP Core Rule Set
- CloudFlare, AWS WAF
- Pattern matching cho SQL keywords

---

## 6. XSS (Cross-Site Scripting) Mitigation

### 6.1 Bản chất và Các loại XSS

**Cơ chế:** Attacker chèn malicious script vào web page, thực thi trong browser của victim.

**Các loại XSS:**

| Type | Cơ chế | Ví dụ |
|------|--------|-------|
| **Stored XSS** | Script lưu trong database, hiển thị cho nhiều users | Comment section, profile bio |
| **Reflected XSS** | Script trong URL parameter, phản hồi ngay | Search results, error messages |
| **DOM-based XSS** | Script chèn qua client-side JavaScript | Hash fragment, postMessage |

### 6.2 XSS Prevention - Defense in Depth

#### Layer 1: Output Encoding

**Context-aware encoding:**

```java
// HTML context
String safeHtml = HtmlUtils.htmlEscape(userInput);
// <script>alert('xss')</script> → &lt;script&gt;alert('xss')&lt;/script&gt;

// JavaScript context
String safeJs = StringEscapeUtils.escapeEcmaScript(userInput);

// URL context
String safeUrl = URLEncoder.encode(userInput, StandardCharsets.UTF_8);

// CSS context
String safeCss = StringEscapeUtils.escapeCss(userInput);
```

**Spring Boot tự động:**

```java
@Controller
public class UserController {
    // Thymeleaf tự động escape HTML entities
    @GetMapping("/profile")
    public String profile(Model model) {
        model.addAttribute("bio", user.getBio()); // Auto-escaped by Thymeleaf
        return "profile";
    }
}
```

Thymeleaf syntax:
- `${user.bio}` → HTML escaped (safe)
- `*{user.bio}` → HTML escaped (safe)
- `${user.bio}?: _` → HTML escaped với default
- `${#strings.unescape(user.bio)}` → **DANGEROUS - unescaped**

#### Layer 2: Content Security Policy (CSP)

**Cơ chế:** HTTP header chỉ định nguồn nào được phép load script, style, v.v.

```http
Content-Security-Policy: 
    default-src 'self';
    script-src 'self' https://cdn.example.com 'nonce-r4nd0m';
    style-src 'self' 'unsafe-inline';
    img-src 'self' data: https:;
    connect-src 'self' https://api.example.com;
    frame-ancestors 'none';
    base-uri 'self';
    form-action 'self';
```

**Spring Security CSP configuration:**

```java
@Configuration
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) {
        http
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'self'; " +
                        "script-src 'self' 'nonce-{nonce}'; " +
                        "style-src 'self' 'unsafe-inline'")
                )
            );
        return http.build();
    }
}
```

**Nonce-based CSP (khuyến nghị):**

```html
<!-- Server generates random nonce for each request -->
<script nonce="r4nd0m123">
    // This inline script is allowed because nonce matches
</script>

<script>
    // This script is BLOCKED - no nonce
</script>
```

#### Layer 3: HttpOnly và Secure Cookies

```java
ResponseCookie cookie = ResponseCookie.from("session", token)
    .httpOnly(true)   // JavaScript cannot access
    .secure(true)     // HTTPS only
    .sameSite("Strict")
    .build();
```

> **HttpOnly flag:** Cookie không thể bị đọc bởi JavaScript → Bảo vệ session khỏi XSS theft.

#### Layer 4: X-XSS-Protection Header

```http
X-XSS-Protection: 1; mode=block
```

> **Note:** Modern browsers đã deprecated header này. CSP là defense chính.

### 6.3 Rich Text Editor và Sanitization

Khi cần cho phép HTML (WYSIWYG editor), dùng HTML sanitizer:

```java
// OWASP Java HTML Sanitizer
PolicyFactory policy = new HtmlPolicyBuilder()
    .allowElements("p", "b", "i", "u", "a", "ul", "ol", "li")
    .allowAttributes("href").onElements("a")
    .requireRelNofollowOnLinks()
    .toFactory();

String sanitized = policy.sanitize(untrustedHtml);
```

### 6.4 Modern XSS Defenses

**Trusted Types (Google Chrome):**

```javascript
// Require trusted type for innerHTML
if (window.trustedTypes && trustedTypes.createPolicy) {
    const policy = trustedTypes.createPolicy('default', {
        createHTML: string => string.replace(/</g, '&lt;')
    });
}
```

**Subresource Integrity (SRI):**

```html
<script src="https://cdn.example.com/lib.js"
        integrity="sha384-abc123..."
        crossorigin="anonymous"></script>
```

---

## 7. Secret Management trong Containerized Environments

### 7.1 Bản chất vấn đề

**Vấn đề với secrets trong containers:**

1. **Image layering:** Secrets trong Dockerfile → Có thể leak qua image history
2. **Environment variables:** Dễ bị expose qua logs, ps, /proc
3. **Multi-tenancy:** Nhiều services chung cluster → Risk của lateral movement
4. **Ephemeral nature:** Containers restart → Secrets cần được inject động

> **Nguyên tắt:** Secrets không bao giờ được hardcode, không commit vào git, không log ra console.

### 7.2 Container Secret Management Patterns

#### Pattern 1: Build-time Secrets (DANGEROUS)

```dockerfile
# ❌ NEVER DO THIS
FROM openjdk:17
ENV DB_PASSWORD=mysecretpassword  # Leaked in image layers
COPY target/app.jar app.jar
```

#### Pattern 2: Runtime Environment Variables (Better but not ideal)

```yaml
# docker-compose.yml - Secrets in env vars
version: '3.8'
services:
  app:
    image: myapp
    environment:
      - DB_PASSWORD=${DB_PASSWORD}  # From host env
```

**Vấn đề:**
- `docker inspect` → thấy env vars
- Process listing: `ps eww <pid>` → thấy env vars
- Log aggregation có thể capture

#### Pattern 3: Secret Files (Recommended for simple cases)

```yaml
# docker-compose.yml
services:
  app:
    image: myapp
    secrets:
      - db_password
      - api_key
    environment:
      - DB_PASSWORD_FILE=/run/secrets/db_password
      
secrets:
  db_password:
    file: ./secrets/db_password.txt
```

```java
// Java read secret from file
@Component
public class SecretLoader {
    @Value("${DB_PASSWORD_FILE}")
    private String passwordFilePath;
    
    @Bean
    public DataSource dataSource() {
        String password = Files.readString(Path.of(passwordFilePath)).trim();
        // ... configure datasource
    }
}
```

**Ưu điểm:**
- Secrets không trong environment
- File permissions có thể control
- Swarm/Kubernetes mount secrets như files

### 7.3 HashiCorp Vault Integration

**Kiến trúc Vault:**

```
┌──────────────┐
│   Vault      │
│   Server     │
│  (Sealed)    │
└──────┬───────┘
       │
       │ mTLS/TLS
       │
┌──────┴───────┐     ┌──────────────┐
│  Application │◄────┤  Vault Agent │
│              │     │  (Sidecar)   │
└──────────────┘     └──────────────┘
```

**Spring Cloud Vault integration:**

```yaml
# bootstrap.yml (Spring Boot 2.x) hoặc application.yml (3.x)
spring:
  cloud:
    vault:
      enabled: true
      uri: https://vault.example.com:8200
      authentication: KUBERNETES  # or APPROLE, TOKEN, etc.
      kubernetes:
        role: myapp
        service-account-token-file: /var/run/secrets/kubernetes.io/serviceaccount/token
      kv:
        enabled: true
        backend: secret
        default-context: myapp
```

**Vault secret path:**

```
vault kv put secret/myapp \
    db.password=supersecret \
    api.key=abc123 \
    jwt.secret=xyz789
```

**Dynamic secrets (database credentials):**

```java
@Configuration
public class VaultDatabaseConfig {
    @Autowired
    private Environment env;
    
    @Bean
    public DataSource dataSource() {
        // Vault injects credentials dynamically
        String username = env.getProperty("db.username");
        String password = env.getProperty("db.password");
        
        HikariConfig config = new HikariConfig();
        config.setUsername(username);
        config.setPassword(password);
        // ...
        return new HikariDataSource(config);
    }
}
```

### 7.4 AWS Secrets Manager

**Cách hoạt động:**

1. Secrets lưu trong AWS Secrets Manager
2. Application dùng IAM Role để retrieve
3. AWS SDK tự động cache và refresh

```java
@Component
public class AwsSecretManager {
    private final SecretsManagerClient client;
    
    public String getSecret(String secretName) {
        GetSecretValueRequest request = GetSecretValueRequest.builder()
            .secretId(secretName)
            .build();
            
        GetSecretValueResponse response = client.getSecretValue(request);
        return response.secretString();
    }
    
    // Parse JSON secret
    public DatabaseCredentials getDatabaseCredentials() {
        String secretJson = getSecret("prod/myapp/database");
        return objectMapper.readValue(secretJson, DatabaseCredentials.class);
    }
}
```

**IAM Policy cho ECS/EKS Task:**

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "secretsmanager:GetSecretValue"
            ],
            "Resource": "arn:aws:secretsmanager:*:*:secret:prod/myapp/*"
        }
    ]
}
```

### 7.5 Kubernetes Secrets

**Cách hoạt động:**

```yaml
# Create secret
apiVersion: v1
kind: Secret
metadata:
  name: myapp-secrets
type: Opaque
data:
  # echo -n 'supersecret' | base64
  db-password: c3VwZXJzZWNyZXQ=
  api-key: YWJjMTIz
```

**Mount vào Pod:**

```yaml
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    spec:
      containers:
      - name: app
        image: myapp
        volumeMounts:
        - name: secrets
          mountPath: "/etc/secrets"
          readOnly: true
      volumes:
      - name: secrets
        secret:
          secretName: myapp-secrets
```

**Security considerations:**

| Issue | Mitigation |
|-------|------------|
| Secrets stored in etcd | Enable etcd encryption at rest |
| RBAC exposure | Restrict secret access with RBAC |
| Secret in container memory | Use tmpfs, restart containers periodically |
| Audit logging | Enable Kubernetes audit logs |

### 7.6 Spring Boot 3.x - New Secret Features

```yaml
# Import secrets from external sources
spring:
  config:
    import:
      - vault://
      - aws-secretsmanager:myapp/
      - file:/run/secrets/
```

```java
// Constructor injection của secrets
@Service
public class PaymentService {
    public PaymentService(
        @Value("${payment.api.key}") String apiKey,
        @Value("${payment.api.secret}") String apiSecret) {
        // ...
    }
}
```

### 7.7 Secret Rotation

**Tại sao cần rotation:**
- Compromised credentials
- Former employees
- Compliance requirements (PCI-DSS, SOC2)

**Rotation strategies:**

```
Active-Passive Rotation:
┌─────────────┐         ┌─────────────┐
│  Version 1  │◄────────│  Version 2  │
│  (Active)   │         │  (Passive)  │
└─────────────┘         └─────────────┘

Dual-Active Rotation:
┌─────────────┐         ┌─────────────┐
│  Version 1  │◄───────►│  Version 2  │
│  (Active)   │         │  (Active)   │
└─────────────┘         └─────────────┘
```

**Implementation với Vault:**

```java
@Component
public class RotatingSecret {
    private final LeaseAwareVaultTemplate vault;
    
    @EventListener
    public void onSecretRotation(LeaseEvent event) {
        // Handle rotation - reload datasource, reconnect APIs
        if (event instanceof SecretLeaseCreatedEvent) {
            refreshDatabaseConnection();
        }
    }
}
```

---

## 8. Khuyến nghị Thực Chiến trong Production

### 8.1 API Security Checklist

```
☐ Input Validation
   ☐ Tất cả endpoints đều có @Valid
   ☐ Custom validators cho business rules
   ☐ Validation ở cả gateway và service layer

☐ Authentication & Authorization
   ☐ JWT signature verification strict
   ☐ Short-lived access tokens (15-30 min)
   ☐ Refresh token rotation
   ☐ Proper scope/permission checks

☐ Transport Security
   ☐ TLS 1.2+ only
   ☐ HSTS enabled
   ☐ Certificate pinning cho mobile apps

☐ CSRF Protection
   ☐ SameSite=Strict/Lax cho session cookies
   ☐ CSRF tokens cho state-changing operations
   ☐ Custom headers cho AJAX requests

☐ Rate Limiting
   ☐ Per-user và per-IP limits
   ☐ Burst allowance với token bucket
   ☐ Different tiers cho different user types
   ☐ Proper 429 responses với Retry-After

☐ SQL Injection Prevention
   ☐ 100% parameterized queries
   ☐ No dynamic SQL concatenation
   ☐ MyBatis #{} thay vì ${}
   ☐ Least privilege database users

☐ XSS Prevention
   ☐ Output encoding ở tất cả contexts
   ☐ CSP header configured
   ☐ HttpOnly cookies
   ☐ HTML sanitization cho user-generated content

☐ Secret Management
   ☐ No secrets in code/repos
   ☐ Vault/AWS Secrets Manager cho production
   ☐ Secret rotation policy
   ☐ Audit logging cho secret access
```

### 8.2 Monitoring và Alerting

**Security events cần log:**

```java
@Component
public class SecurityEventLogger {
    private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");
    
    public void logAuthFailure(String username, String reason, HttpServletRequest req) {
        securityLog.warn("AUTH_FAILURE: user={}, reason={}, ip={}, user-agent={}",
            username, reason, req.getRemoteAddr(), req.getHeader("User-Agent"));
    }
    
    public void logRateLimitExceeded(String identifier, String endpoint) {
        securityLog.warn("RATE_LIMIT_EXCEEDED: identifier={}, endpoint={}",
            identifier, endpoint);
    }
    
    public void logSuspiciousActivity(String activity, Map<String, Object> context) {
        securityLog.error("SUSPICIOUS_ACTIVITY: type={}, context={}",
            activity, context);
    }
}
```

**Prometheus alerts:**

```yaml
groups:
- name: security
  rules:
  - alert: HighAuthFailureRate
    expr: rate(security_auth_failures_total[5m]) > 10
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "High authentication failure rate"
      
  - alert: PossibleBruteForce
    expr: rate(security_rate_limit_exceeded_total[1m]) > 100
    for: 2m
    labels:
      severity: critical
```

### 8.3 Security Headers Checklist

```java
@Configuration
public class SecurityHeadersConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) {
        http
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> 
                    csp.policyDirectives("default-src 'self'"))
                .frameOptions(frame -> frame.sameOrigin())
                .xssProtection(xss -> xss.disable()) // Use CSP instead
                .httpStrictTransportSecurity(hsts -> 
                    hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                .contentTypeOptions(contentType -> contentType.disable())
                .referrerPolicy(referrer -> 
                    referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .permissionsPolicy(permissions ->
                    permissions.policy("geolocation=(), microphone=(), camera=()"))
            );
        return http.build();
    }
}
```

---

## 9. Kết luận

API Security là một **hệ thống phòng thủ nhiều lớp** (defense in depth), không phải một giải pháp duy nhất:

### Trade-offs quan trọng

| Layer | Độ bảo mật | Ảnh hưởng UX | Chi phí triển khai |
|-------|-----------|--------------|-------------------|
| Input Validation | Cao | Thấp | Thấp |
| SameSite=Strict | Rất cao | Trung bình | Thấp |
| CSRF Tokens | Cao | Thấp | Trung bình |
| Rate Limiting | Trung bình | Thấp | Trung bình |
| Parameterized Queries | Rất cao | Không có | Thấp |
| CSP | Rất cao | Cao | Cao |
| Secret Management | Rất cao | Thấp | Cao |

### Nguyên tắt cốt lõi

1. **Validate everything:** Không bao giờ trust input từ client
2. **Fail securely:** Khi có lỗi, hệ thống phải ở trạng thái an toàn
3. **Principle of least privilege:** Cấp quyền tối thiểu cần thiết
4. **Defense in depth:** Không dựa vào một lớp bảo vệ duy nhất
5. **Security by design:** Bảo mật không phải feature thêm vào sau

### Rủi ro lớn nhất trong production

1. **Mass Assignment:** Client gửi field không mong đợi → Bypass validation
2. **Timing Attacks:** So sánh string không constant-time → Leak thông tin
3. **Information Disclosure:** Error messages chi tiết → Leak internal structure
4. **Insecure Defaults:** Framework default không secure → Developer quên customize
5. **Dependency Vulnerabilities:** Third-party libraries có CVE → Chưa patch

### Lộ trình tiếp theo

- **Section 6.1.3:** Transport & Infrastructure Security (mTLS, TLS 1.3, zero-trust)
- **Section 6.2:** Observability & Monitoring (Security-focused logging, SIEM)
- **Section 6.3:** Production Operations (Chaos engineering cho security)

---

## 10. Tài liệu tham khảo

- [OWASP API Security Top 10](https://owasp.org/www-project-api-security/)
- [OWASP Cheat Sheet Series](https://cheatsheetseries.owasp.org/)
- [Spring Security Documentation](https://docs.spring.io/spring-security/reference/)
- [Mozilla Web Security Guidelines](https://infosec.mozilla.org/guidelines/web_security)
- [HashiCorp Vault Documentation](https://developer.hashicorp.com/vault/docs)
- [CSP Quick Reference Guide](https://content-security-policy.com/)