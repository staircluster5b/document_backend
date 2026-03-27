# Spring Boot Auto-Configuration: Bản Chất Cơ Chế và Thực Chiến Production

## 1. Mục Tiêu của Task

Hiểu sâu cơ chế **Auto-Configuration** trong Spring Boot - một trong những tính năng cốt lõi tạo nên sự khác biệt giữa Spring Framework truyền thống và Spring Boot. Mục tiêu:

- Thấu hiểu **cách Spring Boot quyết định** tự động cấu hình bean nào, khi nào, và tại sao
- Nắm vững **@Conditional hierarchy** và evaluation order
- Biết cách **debug và kiểm soát** auto-configuration trong production
- Phát triển **custom starter** đúng chuẩn, có thể tái sử dụng

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 Kiến Trúc Tổng Thể

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           SPRING BOOT STARTUP FLOW                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. @SpringBootApplication                                                  │
│     ├── @Configuration                                                      │
│     ├── @ComponentScan                                                      │
│     └── @EnableAutoConfiguration  ◄── Bật cơ chế auto-configuration        │
│                              │                                              │
│                              ▼                                              │
│  2. AutoConfigurationImportSelector                                         │
│     └── loadFactoryNames() ◄── Đọc META-INF/spring/org.springframework.     │
│        from spring.factories    boot.autoconfigure.EnableAutoConfiguration  │
│                              │                                              │
│                              ▼                                              │
│  3. ConfigurationClassParser                                                │
│     └── Xử lý từng AutoConfigurationClass                                   │
│         ├── Đọc @ConditionalOnClass                                         │
│         ├── Đọc @ConditionalOnProperty                                      │
│         ├── Đọc @ConditionalOnMissingBean                                   │
│         └── ... các điều kiện khác                                          │
│                              │                                              │
│                              ▼                                              │
│  4. ConditionEvaluator                                                      │
│     └── Outcome: MATCH / NO_MATCH                                           │
│                                                                             │
│  5. Bean Definition Registration                                            │
│     └── Chỉ các class thỏa mãn condition mới được đăng ký bean             │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Điểm Khởi Đầu: @EnableAutoConfiguration

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@AutoConfigurationPackage
@Import(AutoConfigurationImportSelector.class)  // ← Đây là entry point
public @interface EnableAutoConfiguration {
    String ENABLED_OVERRIDE_PROPERTY = "spring.boot.enableautoconfiguration";
    Class<?>[] exclude() default {};           // Loại trừ bằng class
    String[] excludeName() default {};         // Loại trừ bằng FQCN
}
```

**Bản chất quan trọng:**

> Auto-configuration xảy ra **trước** component scanning. Điều này có nghĩa:
> - Beans defined trong @Configuration thường được ưu tiên override auto-configured beans
> - `@ConditionalOnMissingBean` kiểm tra sự tồn tại của bean từ **tất cả các nguồn** (auto-config + component scan + explicit config)

### 2.3 Spring.Factories: Registry của Auto-Configuration

**Spring Boot 2.x:**
```
META-INF/spring.factories
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
  org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,\
  org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,\
  ...
```

**Spring Boot 3.x (Breaking Change):**
```
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
...
```

**Tại sao thay đổi?**

| Spring Boot 2.x (spring.factories) | Spring Boot 3.x (.imports files) |
|-----------------------------------|----------------------------------|
| Một file chứa nhiều loại factories | Mỗi file chỉ chứa một loại |
| Khó đọc, dễ conflict khi merge | Modular hơn, dễ quản lý |
| Load toàn bộ file vào memory | Lazy loading theo type |
| String parsing phức tạp | Đơn giản, một dòng một entry |

### 2.4 @Conditional Hierarchy - "Bộ Lọc" của Auto-Configuration

Spring Boot cung cấp 10+ conditional annotations, được phân loại:

#### A. Class-based Conditions

| Annotation | Điều kiện | Use Case |
|-----------|-----------|----------|
| `@ConditionalOnClass` | Class có trong classpath | Kiểm tra thư viện tồn tại (ví dụ: `DataSource` khi có HikariCP) |
| `@ConditionalOnMissingClass` | Class KHÔNG có trong classpath | Tránh conflict với thư viện cũ |

**Cơ chế nội bộ:**
```java
// OnClassCondition sử dụng ASM để đọc bytecode mà không load class
// Điều này quan trọng vì nếu load class trực tiếp sẽ gây NoClassDefFoundError
Class<?> candidate = ClassUtils.forName(className, classLoader);
```

#### B. Bean-based Conditions

| Annotation | Điều kiện | Timing Check |
|-----------|-----------|--------------|
| `@ConditionalOnBean` | Bean (type/name) đã tồn tại | Sau khi tất cả auto-config load xong |
| `@ConditionalOnMissingBean` | Bean (type/name) chưa tồn tại | Same |
| `@ConditionalOnSingleCandidate` | Chỉ có đúng 1 primary bean | Khi cần inject đơn giản |

**⚠️ PITFALL quan trọng:**

> `@ConditionalOnMissingBean` kiểm tra **BEAN DEFINITION**, không phải bean instance. Nếu bean được định nghĩa nhưng chưa instantiate (lazy), vẫn bị coi là "existing".

#### C. Property-based Conditions

| Annotation | Điều kiện |
|-----------|-----------|
| `@ConditionalOnProperty` | Property trong Environment thỏa mãn |
| `@ConditionalOnExpression` | SpEL expression evaluate to true |

```java
// Ví dụ phức tạp
@ConditionalOnProperty(
    prefix = "spring.datasource",
    name = {"url", "username"},  // Cả 2 phải tồn tại
    havingValue = "",           // Không kiểm tra giá trị cụ thể
    matchIfMissing = false      // Mặc định là không match nếu thiếu
)
```

#### D. Resource & Web Conditions

| Annotation | Điều kiện |
|-----------|-----------|
| `@ConditionalOnResource` | Resource tồn tại (classpath/file) |
| `@ConditionalOnWebApplication` | Là web application (servlet/reactive) |
| `@ConditionalOnNotWebApplication` | Không phải web application |

#### E. Custom Conditions

```java
public class OnCustomEnvironmentCondition extends SpringBootCondition {
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String env = context.getEnvironment().getProperty("app.environment");
        boolean isProd = "production".equals(env);
        return isProd ? ConditionOutcome.match() : ConditionOutcome.noMatch("Not production");
    }
}

// Sử dụng
@Conditional(OnCustomEnvironmentCondition.class)
public class ProductionSecurityConfig { }
```

### 2.5 Auto-Configuration Class Structure

```java
// Ví dụ: DataSourceAutoConfiguration
@Configuration(proxyBeanMethods = false)  // proxyBeanMethods=false: optimization
@ConditionalOnClass({ DataSource.class, EmbeddedDatabaseType.class })
@EnableConfigurationProperties(DataSourceProperties.class)  // Bind properties
@Import({ DataSourcePoolMetadataProvidersConfiguration.class,
          DataSourceInitializationConfiguration.class })
public class DataSourceAutoConfiguration {

    // Inner class cho embedded database
    @Configuration(proxyBeanMethods = false)
    @Conditional(EmbeddedDatabaseCondition.class)
    @ConditionalOnMissingBean({ DataSource.class, XADataSource.class })
    @Import(EmbeddedDataSourceConfiguration.class)
    protected static class EmbeddedDatabaseConfiguration { }

    // Inner class cho pooled datasource
    @Configuration(proxyBeanMethods = false)
    @Conditional(PooledDataSourceCondition.class)
    @ConditionalOnMissingBean({ DataSource.class, XADataSource.class })
    @Import({ DataSourceConfiguration.Hikari.class,    // Ưu tiên HikariCP
              DataSourceConfiguration.Tomcat.class,
              DataSourceConfiguration.Dbcp2.class,
              DataSourceConfiguration.Generic.class })
    protected static class PooledDataSourceConfiguration { }
}
```

**Pattern quan trọng:** Các auto-configuration classes thường:
1. **Không dùng `@Configuration` trực tiếp** trên class chính mà dùng inner classes
2. **proxyBeanMethods = false** để tối ưu performance (không cần CGLIB proxy)
3. **@Import** để chia nhỏ configuration, dễ maintain

---

## 3. Thứ Tự Đánh Giá và Ordering

### 3.1 @AutoConfigureOrder và @AutoConfigureAfter/Before

```java
@AutoConfiguration  // Thay thế @Configuration trong Spring Boot 2.7+
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE - 10)  // Số nhỏ = ưu tiên cao
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
@AutoConfigureBefore(HibernateJpaAutoConfiguration.class)
public class MyCustomAutoConfiguration { }
```

**Thứ tự mặc định của Spring Boot:**

| Order | Auto-Configuration Category |
|-------|----------------------------|
| -2147483648 (HIGHEST) | `EmbeddedWebServerFactoryCustomizerAutoConfiguration` |
| ... | Security, Data, Transaction |
| 0 | Most auto-configurations |
| ... | Cloud, Messaging |
| 2147483647 (LOWEST) | User custom starters |

### 3.2 Cơ Chế Topological Sort

Spring Boot sử dụng **DAG (Directed Acyclic Graph)** để sắp xếp auto-configurations:

```
                    DataSourceAutoConfiguration
                              │
                              ▼
                    HibernateJpaAutoConfiguration
                              │
                              ▼
                    TransactionAutoConfiguration
```

Nếu có cycle dependency trong `@AutoConfigureAfter/Before`, Spring Boot sẽ throw `IllegalStateException`.

---

## 4. Trade-off: Phân Tích Chiến Lược

### 4.1 Auto-Configuration vs Manual Configuration

| Tiêu chí | Auto-Configuration | Manual Configuration |
|----------|-------------------|---------------------|
| **Time-to-market** | 🟢 Nhanh, convention over config | 🟡 Chậm, phải định nghĩa tất cả |
| **Khả năng kiểm soát** | 🟡 Gián tiếp qua properties | 🟢 Hoàn toàn explicit |
| **Độ phức tạp cognitive** | 🟡 "Magic" - khó debug khi lỗi | 🟢 Dễ theo dõi, dễ debug |
| **Startup time** | 🟡 Chậm hơn do evaluation | 🟢 Nhanh hơn |
| **Binary size** | 🟡 Nặng hơn (nhiều dependencies) | 🟢 Nhẹ hơn |
| **Team scaling** | 🟡 Cần hiểu convention | 🟢 Code tự giải thích |

### 4.2 Khi Nào Nên Tắt Auto-Configuration?

```java
// 1. Tắt cụ thể một auto-configuration
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})

// 2. Tắt qua property
spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration

// 3. Tắt toàn bộ (KHÔNG KHUYẾN NGHỊ)
spring.boot.enableautoconfiguration=false
```

**Các trường hợp nên tắt:**

1. **Legacy migration**: Ứng dụng cũ có configuration phức tạp, conflict với auto-config
2. **GraalVM Native Image**: Auto-config không tương thích, cần explicit config
3. **Microservice cực nhỏ**: Chỉ vài beans, auto-config overhead không đáng
4. **Testing**: Cần control chính xác context

---

## 5. Failure Modes và Anti-Patterns

### 5.1 Các Lỗi Thường Gặp

#### A. Bean Override Confusion

```java
// ❌ ANTI-PATTERN: Không hiểu priority
@Configuration
public class MyConfig {
    @Bean
    public DataSource dataSource() {  // Override auto-configured DataSource
        return new HikariDataSource();
    }
}

// Problem: Không biết bean nào thực sự active
```

**Solution:**
```java
// ✅ Sử dụng @ConditionalOnMissingBean để đảm bảo chỉ tạo khi cần
@Configuration
public class MyConfig {
    @Bean
    @ConditionalOnMissingBean  // Chỉ tạo nếu Spring Boot chưa cấu hình
    public DataSource dataSource() {
        return new HikariDataSource();
    }
}
```

#### B. Circular Auto-Configuration Dependency

```java
// ❌ ERROR: A after B, B after C, C after A
@AutoConfigureAfter(BConfig.class)
public class AConfig { }

@AutoConfigureAfter(CConfig.class)
public class BConfig { }

@AutoConfigureAfter(AConfig.class)
public class CConfig { }
```

#### C. Wrong Conditional Usage

```java
// ❌ ANTI-PATTERN: @ConditionalOnBean trong @Configuration thường
@Configuration
@ConditionalOnBean(DataSource.class)  // Kiểm tra quá sớm!
public class MyRepositoryConfig {
    // Bean này chỉ được tạo nếu DataSource đã được định nghĩa
    // NHƯNG: Auto-config có thể chưa chạy xong!
}

// ✅ SOLUTION: Để @ConditionalOnBean ở method level
@Configuration
public class MyRepositoryConfig {
    @Bean
    @ConditionalOnBean(DataSource.class)  // Kiểm tra khi cần tạo bean này
    public MyRepository myRepository(DataSource ds) { }
}
```

#### D. Classpath Pollution

```java
// ❌ ANTI-PATTERN: Thư viện "optional" nhưng vẫn có trong classpath
// Có thể kích hoạt auto-config không mong muốn

// Ví dụ: Có `spring-security-core` trong classpath (transitive dependency)
// → SecurityAutoConfiguration tự động kích hoạt
// → Ứng dụng bắt đầu require authentication!
```

### 5.2 Debugging Auto-Configuration

```bash
# 1. Xem báo cáo auto-configuration
curl http://localhost:8080/actuator/conditions

# 2. Log chi tiết khi startup
java -jar app.jar --debug
# hoặc
logging.level.org.springframework.boot.autoconfigure=DEBUG
```

**Conditional Evaluation Report** (từ `/actuator/conditions`):

```json
{
  "contexts": {
    "application": {
      "positiveMatches": {
        "DataSourceAutoConfiguration": [
          {
            "condition": "OnClassCondition",
            "message": "@ConditionalOnClass found required classes 'javax.sql.DataSource', 'org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType'"
          }
        ]
      },
      "negativeMatches": {
        "RabbitAutoConfiguration": [
          {
            "condition": "OnClassCondition",
            "message": "@ConditionalOnClass did not find required class 'com.rabbitmq.client.Channel'"
          }
        ]
      },
      "exclusions": [
        "org.springframework.boot.autoconfigure.security.SecurityAutoConfiguration"
      ]
    }
  }
}
```

---

## 6. Production Concerns

### 6.1 Monitoring và Observability

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: conditions, beans, configprops, env
  endpoint:
    conditions:
      show-components: always
      show-details: always
```

### 6.2 Startup Time Optimization

Spring Boot 3.x+ cung cấp AOT (Ahead-of-Time) compilation để giảm overhead auto-configuration:

```bash
# Generate AOT sources
./mvnw spring-boot:process-aot

# Kết quả: Thay vì evaluate conditions at runtime,
# các conditions được evaluate at build time
```

**Performance comparison (typical):**

| Mode | Startup Time | Memory (baseline) |
|------|-------------|-------------------|
| JVM + Auto-config | ~3-5s | 100% |
| JVM + AOT | ~1-2s | 80% |
| Native Image (GraalVM) | ~0.1s | 40% |

### 6.3 Backward Compatibility

Spring Boot 2.7+ deprecation warning:
- `spring.factories` vẫn được support nhưng sẽ remove trong 3.x
- Nên migrate sang `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```java
// Migration guide:
// Spring Boot 2.7: Cảnh báo deprecation
// Spring Boot 3.0: spring.factories bị ignore cho auto-config
// Spring Boot 3.2: spring.factories hoàn toàn bị remove
```

---

## 7. Custom Starter Development

### 7.1 Project Structure

```
my-custom-spring-boot-starter/
├── pom.xml
└── src/main/
    ├── java/
    │   └── com/example/autoconfigure/
    │       ├── MyServiceAutoConfiguration.java
    │       ├── MyServiceProperties.java
    │       └── MyService.java
    └── resources/
        └── META-INF/spring/
            └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

### 7.2 Implementation

**Step 1: Properties Class**

```java
@ConfigurationProperties(prefix = "my.service")
@Validated
public class MyServiceProperties {
    
    private boolean enabled = true;
    
    @NotEmpty
    private String endpoint = "https://api.example.com";
    
    @Min(1000)
    @Max(60000)
    private int timeout = 5000;
    
    // getters/setters
}
```

**Step 2: Auto-Configuration Class**

```java
@AutoConfiguration  // Spring Boot 2.7+ (thay thế @Configuration)
@ConditionalOnClass(MyService.class)  // Chỉ kích hoạt khi có class này
@ConditionalOnProperty(
    prefix = "my.service",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true  // Mặc định enabled
)
@EnableConfigurationProperties(MyServiceProperties.class)
public class MyServiceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean  // Chỉ tạo nếu user chưa định nghĩa
    public MyService myService(MyServiceProperties properties) {
        return new MyService(properties.getEndpoint(), properties.getTimeout());
    }
}
```

**Step 3: Registration**

```
# META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
com.example.autoconfigure.MyServiceAutoConfiguration
```

**Step 4: Metadata (Optional - for IDE auto-completion)**

```json
// META-INF/additional-spring-configuration-metadata.json
{
  "properties": [
    {
      "name": "my.service.enabled",
      "type": "java.lang.Boolean",
      "description": "Whether to enable MyService auto-configuration.",
      "defaultValue": true
    },
    {
      "name": "my.service.endpoint",
      "type": "java.lang.String",
      "description": "The API endpoint URL."
    }
  ]
}
```

### 7.3 Best Practices

| Practice | Lý do |
|----------|-------|
| **Đặt tên theo convention** `xxx-spring-boot-starter` | User dễ tìm kiếm |
| **Tách `xxx-spring-boot-autoconfigure`** | Starter chỉ là POM aggregator, logic ở autoconfigure module |
| **Luôn có `@ConditionalOnMissingBean`** | Cho phép user override dễ dàng |
| **Sensible defaults** | Không bắt user config quá nhiều |
| **Document properties đầy đủ** | IDE auto-completion hoạt động tốt |

---

## 8. So Sánh Các Phiên Bản

| Feature | Spring Boot 2.5 | Spring Boot 2.7 | Spring Boot 3.0+ |
|---------|-----------------|-----------------|------------------|
| spring.factories | ✅ Full support | ⚠️ Deprecated | ❌ Removed |
| @AutoConfiguration | ❌ Không có | ✅ New annotation | ✅ Primary |
| AOT Processing | ❌ Không | ⚠️ Experimental | ✅ Stable |
| Jakarta EE | ❌ javax | ⚠️ Migration | ✅ jakarta |
| Native Image | ❌ Không | ⚠️ Experimental | ✅ Stable |

---

## 9. Kết Luận

### Bản Chất Cốt Lõi

Spring Boot Auto-Configuration là **convention-over-configuration framework** hoạt động theo nguyên tắc:

1. **Detection**: Quét classpath để phát hiện thư viện có mặt
2. **Conditional Evaluation**: Áp dụng các điều kiện để quyết định cấu hình nào phù hợp
3. **Ordered Registration**: Sắp xếp theo DAG, đảm bảo dependencies được resolve đúng thứ tự
4. **Override Support**: Cung cấp hooks cho user override thông qua `@ConditionalOnMissingBean`

### Trade-off Quan Trọng Nhất

> **Developer velocity vs. Runtime transparency**
> 
> Auto-configuration giúp khởi động nhanh, nhưng tạo ra "magic" khiến debug khó khăn khi có vấn đề. Đòi hỏi team phải hiểu cơ chế để giải quyết conflicts.

### Rủi Ro Lớn Nhất

> **Classpath pollution kích hoạt unexpected auto-configurations**
> 
> Một transitive dependency có thể kích hoạt security, caching, hoặc monitoring configs không mong muốn, gây behavior changes khó trace.

### Khuyến Nghị Production

1. **Luôn enable actuator conditions endpoint** để inspect auto-config state
2. **Explicit exclude** các auto-config không cần thiết để giảm startup time
3. **Sử dụng AOT compilation** (Spring Boot 3+) cho production deployments
4. **Viết integration tests** verify correct auto-config behavior với custom configs

---

## 10. Tài Liệu Tham Khảo

- [Spring Boot Auto-configuration Documentation](https://docs.spring.io/spring-boot/docs/current/reference/html/auto-configuration-classes.html)
- [Creating Your Own Auto-configuration](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.developing-auto-configuration)
- [Spring Boot 3.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide)
