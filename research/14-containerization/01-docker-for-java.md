# Docker for Java - Multi-stage builds, JIB, Layer caching

## 1. Mục tiêu của task

Hiểu sâu cơ chế Docker image cho Java applications, bao gồm:
- Bản chất layering trong Docker image và tối ưu hóa
- Multi-stage builds để giảm attack surface và image size
- JIB như một paradigm shift so với Dockerfile truyền thống
- Layer caching strategies cho CI/CD pipeline

## 2. Bản chất và cơ chế hoạt động

### 2.1 Docker Layer Architecture

Docker image được xây dựng từ các **read-only layers** stacked theo Union File System:

```
Layer N (Application classes - thay đổi thường xuyên)
Layer N-1 (Dependencies - thay đổi khi pom.xml thay đổi)
Layer N-2 (JDK/JRE - thay đổi khi upgrade Java version)
Layer N-3 (Base OS - thay đổi hiếm)
```

> **Quan trọng**: Mỗi layer chỉ lưu **diff** so với layer dưới. Layer đã cached sẽ không rebuild nếu instruction không đổi.

**Copy-on-Write (CoW) Strategy**:
- Container layer là writable layer duy nhất
- Khi ghi file, CoW copy từ image layer xuống container layer
- Java app ghi log → I/O overhead do CoW

### 2.2 Java Application Layering Problem

**Anti-pattern phổ biến**:
```dockerfile
COPY . /app           # Layer 1: Code + dependencies (thay đổi liên tục)
RUN mvn package       # Layer 2: Build artifacts
```

Vấn đề: Mỗi lần thay đổi 1 dòng code → rebuild toàn bộ dependencies → cache miss → CI chậm.

**Giải pháp - Layer ordering theo volatility**:
```dockerfile
COPY pom.xml .        # Layer 1: Dependency descriptor (ít thay đổi)
RUN mvn dependency:go-offline  # Layer 2: Download dependencies (nặng, cache được)
COPY src /app/src     # Layer 3: Source code (thay đổi thường xuyên)
RUN mvn package       # Layer 4: Compilation
```

### 2.3 Multi-stage Builds - Bản chất

Multi-stage không chỉ là "copy file từ stage này sang stage khác". Bản chất là **tạo nhiều intermediate images**, chỉ giữ lại artifacts cần thiết.

```
Stage 1: Build environment (JDK + Maven + source)
    ↓ produces JAR only
Stage 2: Runtime environment (JRE only)
    ↓ COPY --from=stage1 /app.jar
Final Image: Chỉ chứa JRE + JAR
```

**Trade-off analysis**:

| Aspect | Single Stage | Multi-stage |
|--------|--------------|-------------|
| Image size | 500MB+ (full JDK) | 150-200MB (JRE only) |
| Attack surface | Cao (compiler, tools) | Thấp |
| Build time | Nhanh hơn 1 lần | Chậm hơn (2 stages) |
| Cache efficiency | Thấp | Cao (runtime layer tách biệt) |
| Debuggability | Dễ (có tools) | Khó (cần debug image) |

## 3. JIB - Containerization without Docker

### 3.1 Bản chất JIB

JIB (Google) không chạy Docker daemon. Thay vào đó:

1. **Phân tích Maven/Gradle project** để xác định layer structure
2. **Tạo image layers trực tiếp** vào Docker Registry (hoặc local daemon)
3. **Sử dụng Reproducible Builds** - cùng input → cùng image digest

```
Truyền thống: Source → JAR → Dockerfile → Docker daemon → Image
JIB: Source → [JIB] → Image layers → Registry (bypass Docker daemon)
```

### 3.2 JIB Layer Strategy

JIB chia Java app thành 3 layers tối ưu:

```
Layer 1: Dependencies (snapshot vs release separation)
Layer 2: Snapshot dependencies (nếu có)
Layer 3: Project classes & resources
```

> **Key insight**: JIB đặt classes của project ở layer riêng, dependencies ở layer riêng. Thay đổi code không invalidate dependency layer.

### 3.3 JIB vs Dockerfile

| Criteria | Dockerfile | JIB |
|----------|------------|-----|
| Docker daemon | Required | Not required |
| Layer optimization | Manual | Automatic |
| Reproducible builds | Hard | Built-in (timestamp stripping) |
| Distroless base images | Manual config | Default (gcr.io/distroless) |
| Build tool integration | External | Native Maven/Gradle |
| Customization | Full control | Limited (entrypoint, JVM flags) |
| Multi-arch support | Complex | Built-in |

**Khi nào dùng JIB**:
- Standard Spring Boot apps không cần customization phức tạp
- CI/CD cần reproducible builds
- Muốn distroless images mặc định
- Build trên environments không có Docker

**Khi nào dùng Dockerfile**:
- Cần custom OS packages
- Native images (GraalVM)
- Multi-stage phức tạp với sidecar patterns
- Custom entrypoint scripts

## 4. Layer Caching Strategies

### 4.1 CI/CD Cache Optimization

**Problem**: Mỗi build là fresh environment → download dependencies lại.

**Giải pháp - BuildKit cache mounts**:
```dockerfile
RUN --mount=type=cache,target=/root/.m2 mvn package
```

Mount `/root/.m2` vào cache volume được preserve giữa các build.

**So sánh approaches**:

| Approach | Build time | Cache persistence | Complexity |
|----------|------------|-------------------|------------|
| Docker layer cache | Medium | Registry/locally | Low |
| BuildKit cache mount | Fast | BuildKit daemon | Medium |
| External cache (S3) | Fast | S3 bucket | High |
| Volume mounts | Fast | Host filesystem | Medium |

### 4.2 Spring Boot Layered JAR (Boot 2.3+)

Spring Boot 2.3+ tạo **layered JAR** với `layers.idx`:

```
dependencies/
spring-boot-loader/
snapshot-dependencies/
application/
```

Extract và COPY riêng từng layer:
```dockerfile
COPY --from=builder application/dependencies/ ./
COPY --from=builder application/spring-boot-loader/ ./
COPY --from=builder application/snapshot-dependencies/ ./
COPY --from=builder application/application/ ./
```

> **Lưu ý**: Thứ tự COPY quan trọng - dependencies trước, application sau.

## 5. Rủi ro, Anti-patterns, Lỗi thường gặp

### 5.1 Fat JAR Problem

**Anti-pattern**: Copy cả JAR vào container và `java -jar`

Vấn đề:
- Spring Boot Fat JAR = dependencies + loader + app
- Thay đổi 1 class → rebuild toàn bộ JAR → invalidate cache
- Startup chậm (explode JAR at runtime)

**Solution**: Use layered JAR hoặc `spring-boot:build-image`.

### 5.2 Wrong Base Image

**Anti-pattern**:
```dockerfile
FROM openjdk:11          # 600MB+ (full JDK)
```

**Production-ready**:
```dockerfile
FROM eclipse-temurin:17-jre-alpine  # ~60MB JRE only
```

Hoặc distroless (Google):
```dockerfile
FROM gcr.io/distroless/java17-debian11
```

### 5.3 Running as Root

**Security risk**: Java processes chạy root có thể escape container.

```dockerfile
# Tạo non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
```

### 5.4 Memory Settings Ignorance

**Pitfall**: Không set JVM heap limits → container bị OOM killed.

```dockerfile
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
```

> **Java 10+**: `-XX:+UseContainerSupport` đọc cgroup limits tự động.
> **Java 8u191+**: Backported nhưng cần enable manually.

### 5.5 Graceful Shutdown

**Problem**: `docker stop` gửi SIGTERM, Java không handle → SIGTERM ignored → SIGKILL sau 10s.

**Solution**:
```dockerfile
ENTRYPOINT ["java", "-jar", "app.jar"]
# Không dùng shell form: ENTRYPOINT java -jar app.jar (shell không forward signals)
```

Và implement ShutdownHook hoặc Spring Boot graceful shutdown.

## 6. Khuyến nghị thực chiến trong Production

### 6.1 Dockerfile Template cho Spring Boot

```dockerfile
# Build stage
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN ./mvnw dependency:go-offline

COPY src ./src
RUN ./mvnw clean package -DskipTests

# Extract layers
RUN java -Djarmode=layertools -jar target/*.jar extract

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Security: non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy layers in order of change frequency
COPY --from=builder --chown=appuser:appgroup app/dependencies/ ./
COPY --from=builder --chown=appuser:appgroup app/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appgroup app/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appgroup app/application/ ./

USER appuser

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+OptimizeStringConcat \
               -XX:+UseStringDeduplication"

# Use exec form for proper signal handling
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
```

### 6.2 JIB Configuration (Maven)

```xml
<plugin>
    <groupId>com.google.cloud.tools</groupId>
    <artifactId>jib-maven-plugin</artifactId>
    <configuration>
        <from>
            <image>eclipse-temurin:17-jre-alpine</image>
        </from>
        <to>
            <image>myapp:${project.version}</image>
        </to>
        <container>
            <jvmFlags>
                <jvmFlag>-XX:+UseContainerSupport</jvmFlag>
                <jvmFlag>-XX:MaxRAMPercentage=75.0</jvmFlag>
            </jvmFlags>
            <ports>
                <port>8080</port>
            </ports>
        </container>
    </configuration>
</plugin>
```

### 6.3 Monitoring Docker Images

Track metrics:
- **Image size**: Target < 200MB cho JRE-based apps
- **Layer count**: Minimize (< 15 layers)
- **Build time**: Target < 2 phút với cache
- **Vulnerability scan**: Snyk/Trivy trước deploy

### 6.4 Java Version Considerations

| Java Version | Container Support | Recommendations |
|--------------|-------------------|-----------------|
| 8 | Basic (8u191+) | Migrate ASAP |
| 11 | Full | LTS, stable |
| 17 | Full + improvements | Recommended LTS |
| 21 | Full + Virtual Threads | Best for new projects |

## 7. Kết luận

**Bản chất vấn đề**: Docker cho Java không chỉ là "đóng gói app", mà là tối ưu layer caching, giảm attack surface, và đảm bảo container-aware resource management.

**Trade-off chính**:
- Multi-stage builds: **image size vs build complexity**
- JIB vs Dockerfile: **convenience vs flexibility**  
- Base image: **security (distroless) vs debuggability (full OS)**

**Quyết định production**:
1. Dùng **JIB** cho standard Spring Boot apps (zero-config, distroless default)
2. Dùng **multi-stage Dockerfile** khi cần customization (native images, custom OS)
3. Luôn **layer theo volatility** - dependencies tách biệt application code
4. **Never run as root** - tạo dedicated user
5. **Always set JVM container flags** - tránh OOM kills
6. **Use JRE base images** - JDK chỉ cần ở build stage

**Tư duy kiến trúc**: Coi Docker image như immutable artifact - cùng JAR input luôn cho cùng image output. Reproducible builds là foundation của reliable deployments.
