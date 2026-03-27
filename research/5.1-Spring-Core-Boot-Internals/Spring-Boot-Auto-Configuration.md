# Spring Boot Auto-Configuration: Cơ Chế Phía Sau Sự Tự Động Hóa

## 1. Mục Tiêu Nghiên Cứu

Hiểu sâu cơ chế **Auto-Configuration** của Spring Boot - cách framework tự động cấu hình beans dựa trên classpath và properties mà không cần @Configuration thủ công. Phân tích các khía cạnh:

- Cơ chế phát hiện và load auto-configuration classes
- @Conditional annotations và quyết định có/không cấu hình
- Thứ tự ưu tiên và xử lý conflicts
- Custom starter development
- Production concerns và debugging

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 Entry Point: @SpringBootApplication

Annotation này là composition của 3 annotation:

```java
@SpringBootConfiguration  // == @Configuration
@EnableAutoConfiguration  // Bật auto-configuration
@ComponentScan           // Scan components
```

**@EnableAutoConfiguration** là điểm khởi đầu. Nó import `AutoConfigurationImportSelector`:

```java
@Import(AutoConfigurationImportSelector.class)
public @interface EnableAutoConfiguration {
    Class<?>[] exclude() default {};  // Loại trừ specific configs
    String[] excludeName() default {};
}
```

### 2.2 Cơ Chế Discovery: META-INF/spring.factories

Spring Boot 2.x trở xuống sử dụng file `META-INF/spring.factories`:

```properties
# Key quy ước
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
  com.example.MyAutoConfiguration,\
  com.example.OtherAutoConfiguration
```

**Cách hoạt động:**
1. `SpringFactoriesLoader.loadFactoryNames()` scan tất cả JAR files trong classpath
2. Đọc file `META-INF/spring.factories` từ mỗi JAR
3. Gom tất cả class names có key `EnableAutoConfiguration`
4. Load và đăng ký các auto-configuration classes

**Spring Boot 2.7+ / 3.x: Chuyển đổi sang META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports**

```
# File: META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
com.example.MyAutoConfiguration
com.example.OtherAutoConfiguration
```

> **Lưu ý quan trọng:** Spring Boot 3.x không còn hỗ trợ spring.factories cho auto-configuration. Migration bắt buộc.

### 2.3 Filtering và Điều Kiện Hóa

Sau khi load danh sách, Spring Boot **không đăng ký tất cả**. Mỗi auto-configuration class được annotated với @Conditional để quyết định có active hay không.

**Luồng xử lý:**

```
┌─────────────────────────────────────────────────────────────────┐
│  Scan classpath → Find all spring.factories / .imports files   │
└─────────────────────────────┬───────────────────────────────────┘
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  Load AutoConfiguration classes list                           │
└─────────────────────────────┬───────────────────────────────────┘
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  For each class:                                               │
│  - Evaluate @ConditionalOnClass (class có trong classpath?)    │
│  - Evaluate @ConditionalOnProperty (property đúng giá trị?)    │
│  - Evaluate @ConditionalOnMissingBean (bean chưa tồn tại?)     │
│  - Evaluate các conditions khác...                             │
└─────────────────────────────┬───────────────────────────────────┘
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  Filter: Chỉ giữ lại classes pass tất cả conditions            │
└─────────────────────────────┬───────────────────────────────────┘
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  Sort theo @AutoConfigureOrder và @ConditionalOn...            │
└─────────────────────────────┬───────────────────────────────────┘
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  Register beans vào ApplicationContext                         │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. Kiến Trúc Conditional System

### 3.1 Core Conditional Annotations

| Annotation | Mục đích | Ví dụ thực tế |
|------------|----------|---------------|
| `@ConditionalOnClass` | Class có trong classpath | Chỉ cấu hình DataSource khi có JDBC driver |
| `@ConditionalOnMissingClass` | Class KHÔNG có trong classpath | Fallback configuration |
| `@ConditionalOnBean` | Bean type cụ thể đã tồn tại | Cấu hình transaction manager sau khi có DataSource |
| `@ConditionalOnMissingBean` | Bean type chưa tồn tại | Không override user-defined bean |
| `@ConditionalOnProperty` | Property match giá trị | Bật/tắt feature qua application.properties |
| `@ConditionalOnResource` | Resource tồn tại | Cấu hình khi có file schema.sql |
| `@ConditionalOnWebApplication` | Là web application | Chỉ cấu hình web-specific beans |
| `@ConditionalOnExpression` | SpEL expression đúng | Điều kiện phức tạp |
| `@ConditionalOnJava` | Java version range | Feature cho Java 17+ |
| `@ConditionalOnJndi` | JNDI lookup thành công | Enterprise environment |
| `@ConditionalOnSingleCandidate` | Chỉ 1 candidate bean | Tránh ambiguity trong DI |

### 3.2 Custom Condition Implementation

Khi cần logic phức tạp hơn, implement `Condition`:

```java
public class DatabaseTypeCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        // Truy cập environment
        String dbType = context.getEnvironment()
            .getProperty("app.database.type");
        
        // Truy cập bean factory
        boolean hasDataSource = context.getBeanFactory()
            .containsBeanDefinition("dataSource");
        
        // Truy cập resource loader
        boolean hasConfig = context.getResourceLoader()
            .getResource("classpath:db-config.yml").exists();
        
        return "postgresql".equals(dbType) && hasDataSource && hasConfig;
    }
}

// Sử dụng
@Configuration
@Conditional(DatabaseTypeCondition.class)
public class PostgreSQLAutoConfiguration {
    // ...
}
```

### 3.3 Condition Evaluation Order

Spring Boot đánh giá conditions theo phase:

1. **PARSE_CONFIGURATION phase:** @ConditionalOnClass, @ConditionalOnMissingClass
   - Đánh giá sớm nhất, dựa trên classpath
   - Dùng `@ConditionalOnClass(name = "...")` thay vì `value = Class.class` để tránh ClassNotFoundException

2. **REGISTER_BEAN phase:** @ConditionalOnBean, @ConditionalOnMissingBean
   - Đánh giá khi bean definitions đã được load
   - Phụ thuộc vào thứ tự đăng ký

> **Cảnh báo:** @ConditionalOnBean trong @Configuration class có thể cho kết quả không như mong đợi nếu bean chưa được đăng ký. Dùng @ConditionalOnBean trên @Bean method thay vì class level.

---

## 4. Ordering và Conflict Resolution

### 4.1 @AutoConfigureOrder

Định nghĩa thứ tự giữa các auto-configurations:

```java
@Configuration(proxyBeanMethods = false)
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE - 10)  // Ưu tiên thấp
public class MyAutoConfiguration { }

// Constants phổ biến trong Spring Boot:
Ordered.HIGHEST_PRECEDENCE      // -2147483648
Ordered.LOWEST_PRECEDENCE       // 2147483647
DataSourceAutoConfiguration.ORDER  // Ordered.HIGHEST_PRECEDENCE + 10
HibernateJpaAutoConfiguration.ORDER // After DataSource
```

**Nguyên tắc:** Số nhỏ hơn = ưu tiên cao hơn (được xử lý trước).

### 4.2 @AutoConfigureBefore và @AutoConfigureAfter

Thể hiện rõ ràng dependency giữa auto-configurations:

```java
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
@AutoConfigureBefore(HibernateJpaAutoConfiguration.class)
public class MyDatabaseAutoConfiguration {
    // Đảm bảo chạy sau DataSource được tạo
    // Nhưng trước khi Hibernate JPA cấu hình
}
```

**Ví dụ thực tế - DataAccess hierarchy:**

```
DataSourceAutoConfiguration (HIGHEST + 10)
    ↓
MyBatisAutoConfiguration
    ↓
HibernateJpaAutoConfiguration
    ↓
TransactionAutoConfiguration (LOWEST - 10)
```

### 4.3 ImportSelector và Deferred Import

`AutoConfigurationImportSelector` implements `DeferredImportSelector`:

```java
public class AutoConfigurationImportSelector implements 
    DeferredImportSelector,  // Quan trọng!
    BeanClassLoaderAware, 
    ResourceLoaderAware { }
```

**Tại sao cần Deferred?**

- Regular ImportSelector chạy ngay trong parsing phase
- DeferredImportSelector chạy **sau** tất cả @Configuration beans được parse
- Cho phép @ConditionalOnBean, @ConditionalOnMissingBean đánh giá chính xác

---

## 5. So Sánh Các Phương Án Conditional

### 5.1 @ConditionalOnClass vs try-catch Class.forName()

| Phương án | Ưu điểm | Nhược điểm | Khi nào dùng |
|-----------|---------|------------|--------------|
| `@ConditionalOnClass` | Clean, declarative, tích hợp Spring Boot | Chỉ dùng được ở class level | Auto-configuration classes |
| `Class.forName()` try-catch | Linh hoạt, dùng được trong code | Verbose, runtime exception risk | Runtime feature detection |
| `ClassUtils.isPresent()` | Safe, không throw | Vẫn cần logic bổ sung | Utility methods, conditions |

### 5.2 @ConditionalOnProperty vs Feature Flags

**@ConditionalOnProperty approach:**
```java
@ConditionalOnProperty(
    prefix = "feature.payment",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false  // Mặc định tắt
)
```

**Runtime Feature Toggle approach:**
```java
@Component
public class PaymentService {
    @Value("${feature.payment.enabled:false}")
    private boolean enabled;
    
    public void process() {
        if (!enabled) throw new FeatureDisabledException();
        // ...
    }
}
```

| Tiêu chí | @ConditionalOnProperty | Runtime Toggle |
|----------|------------------------|----------------|
| Bean creation | Không tạo bean nếu disabled | Bean vẫn tồn tại, check runtime |
| Memory | Tiết kiệm (không có bean) | Chiếm memory |
| Flexibility | Static tại startup | Dynamic, có thể reload |
| Use case | Feature ổn định, ít thay đổi | Feature thay đổi thường xuyên |

> **Khuyến nghị:** Dùng @ConditionalOnProperty cho features không cần toggle runtime. Dùng runtime toggle nếu cần đổi behavior không restart.

### 5.3 @ConditionalOnMissingBean vs @Primary

Cả 2 đều giải quyết vấn đề "nhiều bean cùng type":

**@ConditionalOnMissingBean - User override:**
```java
@Configuration
public class AutoConfig {
    @Bean
    @ConditionalOnMissingBean  // Chỉ tạo nếu user chưa định nghĩa
    public MyService myService() {
        return new DefaultMyService();
    }
}

// User code override
@Bean
public MyService myService() {
    return new CustomMyService();  // Auto-config bị skip
}
```

**@Primary - Default selection:**
```java
@Configuration
public class AutoConfig {
    @Bean
    @Primary  // Mặc định dùng bean này
    public MyService myService() {
        return new DefaultMyService();
    }
}

// User code
@Bean
public MyService customMyService() {  // Cả 2 cùng tồn tại
    return new CustomMyService();
}

// Injection
@Autowired
private MyService myService;  // Auto-wired DefaultMyService (Primary)
```

| Trường hợp | Dùng gì |
|------------|---------|
| User có thể completely replace implementation | @ConditionalOnMissingBean |
| Cần default + optional extensions | @Primary |
| Nhiều implementations, cần 1 default | @Primary + @Qualifier |
| Conditional creation phức tạp | @ConditionalOnMissingBean + custom conditions |

---

## 6. Rủi Ro, Anti-Patterns và Lỗi Thường Gặp

### 6.1 Component Scan trong Auto-Configuration

**ANTI-PATTERN:**
```java
@Configuration
@ComponentScan("com.example.mylib")  // ❌ KHÔNG BAO GIỜ làm điều này
public class MyAutoConfiguration {
}
```

**Rủi ro:**
- Scan nhầm components của user
- Performance degradation
- Unexpected bean registration
- Difficult to debug

**Đúng:** Chỉ định nghĩa beans explicitly:
```java
@Configuration(proxyBeanMethods = false)
public class MyAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public MyService myService() {
        return new MyServiceImpl();
    }
}
```

### 6.2 @ConditionalOnClass với Class Literal

**Lỗi:**
```java
@ConditionalOnClass(MyOptionalDependency.class)  // ❌ ClassNotFoundException nếu không có dependency
public class MyAutoConfiguration { }
```

**Đúng:**
```java
@ConditionalOnClass(name = "com.example.MyOptionalDependency")  // ✅ String name
public class MyAutoConfiguration { }
```

### 6.3 Cyclic Dependencies trong Auto-Configuration

**Vấn đề:**
```java
@Configuration
@AutoConfigureAfter(BConfig.class)
public class AConfig { }

@Configuration
@AutoConfigureAfter(AConfig.class)  // ❌ Cycle!
public class BConfig { }
```

**Giải pháp:**
- Loại bỏ cycle bằng cách refactor
- Dùng @ConditionalOnBean thay vì ordering nếu có thể
- Extract shared configuration ra class riêng

### 6.4 @ConditionalOnBean ở Class Level

**Vấn đề:**
```java
@Configuration
@ConditionalOnBean(DataSource.class)  // ❤️ Có thể không hoạt động như mong đợi
public class MyRepositoryAutoConfiguration {
    // DataSource có thể chưa được register khi condition đánh giá
}
```

**Đúng:**
```java
@Configuration(proxyBeanMethods = false)
public class MyRepositoryAutoConfiguration {
    @Bean
    @ConditionalOnBean(DataSource.class)  // ✅ Method level
    public MyRepository myRepository(DataSource dataSource) {
        return new MyRepository(dataSource);
    }
}
```

### 6.5 Không Dùng proxyBeanMethods = false

**Mặc định:** `@Configuration` tạo CGLIB proxy để đảm bảo @Bean methods return singleton.

**Vấn đề trong auto-configuration:**
- Tăng startup time (CGLIB proxy generation)
- Tăng memory footprint
- Không cần thiết nếu không có inter-bean method calls

**Best practice:**
```java
@Configuration(proxyBeanMethods = false)  // ✅ Lightweight
public class MyAutoConfiguration {
    // Không gọi @Bean method từ method khác trong class này
}
```

---

## 7. Custom Starter Development

### 7.1 Cấu Trúc Starter Project

```
my-spring-boot-starter/
├── src/main/java/
│   └── com/example/autoconfigure/
│       ├── MyAutoConfiguration.java      # Main auto-config
│       ├── MyProperties.java             # @ConfigurationProperties
│       └── MyService.java                # Core service
├── src/main/resources/
│   └── META-INF/
│       └── spring/
│           └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
├── pom.xml
└── README.md
```

### 7.2 pom.xml Dependencies

```xml
<dependencies>
    <!-- Compile dependencies -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
    
    <!-- Optional: chỉ dùng khi có trong classpath -->
    <dependency>
        <groupId>com.optional</groupId>
        <artifactId>optional-lib</artifactId>
        <optional>true</optional>
    </dependency>
    
    <!-- Annotation processor cho metadata -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-configuration-processor</artifactId>
        <optional>true</optional>
    </dependency>
    
    <!-- Auto-configure processor -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-autoconfigure-processor</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

### 7.3 @ConfigurationProperties

```java
@ConfigurationProperties(prefix = "my.service")
public class MyProperties {
    private boolean enabled = true;  // Default value
    private String endpoint = "http://localhost:8080";
    private Duration timeout = Duration.ofSeconds(30);
    private List<String> features = new ArrayList<>();
    
    // Nested properties
    private Security security = new Security();
    
    public static class Security {
        private String apiKey;
        private boolean validateSsl = true;
        // getters, setters
    }
    // getters, setters
}
```

**Enable properties:**
```java
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MyProperties.class)
@ConditionalOnClass(MyService.class)
@ConditionalOnProperty(prefix = "my.service", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MyAutoConfiguration {
    
    @Bean
    @ConditionalOnMissingBean
    public MyService myService(MyProperties properties) {
        return new MyService(properties.getEndpoint(), properties.getTimeout());
    }
}
```

### 7.4 Metadata cho IDE Support

Spring Boot Configuration Processor tạo `spring-configuration-metadata.json`:

```json
{
  "groups": [{
    "name": "my.service",
    "type": "com.example.autoconfigure.MyProperties",
    "sourceType": "com.example.autoconfigure.MyProperties"
  }],
  "properties": [{
    "name": "my.service.enabled",
    "type": "java.lang.Boolean",
    "description": "Whether to enable My Service.",
    "defaultValue": true
  }, {
    "name": "my.service.endpoint",
    "type": "java.lang.String",
    "description": "Endpoint URL for My Service."
  }]
}
```

> Điều này cho phép IDE (IntelliJ, VS Code) auto-complete properties trong application.properties/yaml.

---

## 8. Production Concerns

### 8.1 Debugging Auto-Configuration

**1. Condition Evaluation Report:**
```yaml
# application.yml
debug: true  # Hoặc --debug command line
```

Output sẽ hiển thị:
- Auto-configurations đã match
- Auto-configurations đã bị loại và lý do
- Thứ tự evaluation

**2. Programmatic Access:**
```java
@Autowired
private ConditionEvaluationReport report;

public void printAutoConfigReport() {
    report.getConditionAndOutcomesBySource().forEach((source, outcomes) -> {
        System.out.println("Source: " + source);
        outcomes.forEach(outcome -> {
            System.out.println("  " + outcome.getCondition() + 
                ": " + outcome.isMatch());
        });
    });
}
```

**3. Actuator Endpoint:**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: conditions
```

Truy cập `/actuator/conditions` để xem JSON report.

### 8.2 Startup Time Optimization

**Vấn đề:** Quá nhiều auto-configurations làm chậm startup.

**Giải pháp:**

1. **Exclude không cần thiết:**
```java
@SpringBootApplication(exclude = {
    DataSourceAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class,
    JacksonAutoConfiguration.class
})
```

2. **Dùng spring.autoconfigure.exclude:**
```properties
spring.autoconfigure.exclude=\
  org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,\
  org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
```

3. **Spring Boot 2.3+: Lazy Initialization:**
```properties
spring.main.lazy-initialization=true
```

> **Cảnh báo:** Lazy initialization ẩn lỗi configuration đến lúc bean được sử dụng.

### 8.3 Testing Auto-Configurations

```java
@TestConfiguration
public static class TestConfig {
    @Bean
    @Primary  // Override auto-configured bean trong test
    public MyService mockMyService() {
        return mock(MyService.class);
    }
}

@SpringBootTest(classes = {MyAutoConfiguration.class, TestConfig.class})
@AutoConfigureMockMvc
public class MyAutoConfigurationTest {
    
    @Test
    void contextLoads() {
        // Verify auto-configuration hoạt động đúng
    }
}
```

### 8.4 Version Compatibility

**Spring Boot 2.x → 3.x Migration Checklist:**

| Item | 2.x | 3.x | Action |
|------|-----|-----|--------|
| Auto-config file | spring.factories | .imports | Move content |
| Java version | 8+ | 17+ | Upgrade JDK |
| Jakarta EE | javax.* | jakarta.* | Update imports |
| Security | WebSecurityConfigurerAdapter | SecurityFilterChain | Refactor config |

---

## 9. Kết Luận

**Bản chất của Auto-Configuration:**

Spring Boot Auto-Configuration là **convention-over-configuration** pattern đưa ra quyết định thông minh dựa trên classpath. Nó không phải "magic" mà là kết hợp của:

1. **Discovery mechanism** (spring.factories / .imports)
2. **Conditional evaluation** (@Conditional annotations)
3. **Ordering system** (@AutoConfigureOrder, Before/After)
4. **Bean registration** với user override support

**Trade-off chính:**
- **Pros:** Giảm boilerplate, nhanh prototype, consistent defaults
- **Cons:** Ẩn complexity, khó debug khi không hiểu cơ chế, unexpected behavior nếu conditions conflict

**Key Takeaways cho Production:**

1. **Hiểu rõ conditions** - Đừng đoán, dùng debug report
2. **Explicit > Implicit** - Khi system quan trọng, định nghĩa beans explicit thay vì dựa hoàn toàn vào auto-config
3. **Test với profile giống production** - Conditions có thể khác nhau giữa dev và prod
4. **Monitor startup time** - Too many auto-configs = slow startup
5. **Document overrides** - Nếu exclude auto-configs, ghi chú lý do trong README

**Khi nào nên dùng Auto-Configuration:**
- ✅ Prototype/MVP nhanh
- ✅ Standard patterns (web, data, security)
- ✅ Internal/shared libraries với sensible defaults

**Khi nào KHÔNG nên dùng:**
- ❌ Highly customized requirements
- ❌ Security-critical configurations
- ❌ Khi cần full control và auditability

---

## 10. Tài Liệu Tham Khảo

1. Spring Boot Reference Documentation - Auto-configuration
2. Spring Boot 3.0 Migration Guide
3. "Spring Boot in Action" - Craig Walls
4. Spring Boot GitHub: spring-boot-autoconfigure module
