# Memory Leak Demo

Các file demo minh họa memory leak và cách khắc phục.

## Cấu trúc

```
demo/
├── MemoryLeakDemo.java    # ❌ Demo memory leak với static unbounded cache
├── FixedMemoryDemo.java   # ✅ Fixed version sử dụng Caffeine Cache
├── pom.xml                # Maven configuration
└── README.md              # File này
```

## Chạy Demo

### 1. Memory Leak Demo (Sẽ bị OOM)

```bash
# Compile
mvn compile

# Chạy với heap dump khi OOM
java -cp target/classes:$(mvn dependency:build-classpath -q -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout) \
     -Xms64m -Xmx64m \
     -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=./memory_leak.hprof \
     demo.MemoryLeakDemo
```

Kết quả mong đợi:
```
❌ OutOfMemoryError occurred at iteration ~60
💡 Check the generated heap dump (.hprof file) with MAT or VisualVM
```

### 2. Fixed Demo (Chạy hoàn thành)

```bash
# Chạy version đã fix
java -cp target/classes:$(mvn dependency:build-classpath -q -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout) \
     -Xms64m -Xmx64m \
     demo.FixedMemoryDemo
```

Kết quả mong đợi:
```
✅ Completed without OOM!
📊 Final Statistics:
   - Hit rate: 0.00%
   - Miss rate: 100.00%
   - Eviction count: ~90
   - Current size: 10
```

## Phân tích Heap Dump

Sau khi chạy MemoryLeakDemo và nhận được file `memory_leak.hprof`:

### Với VisualVM
1. File → Load → Chọn `memory_leak.hprof`
2. Classes tab → Tìm `byte[]`
3. Click chuột phải → "Find nearest GC root"

### Với Eclipse MAT
1. File → Open Heap Dump
2. Chạy "Leak Suspects Report"
3. Xem kết quả phân tích

Kết quả sẽ cho thấy:
- `byte[]` chiếm phần lớn heap
- Được giữ bởi `HashMap` trong `MemoryLeakDemo.CACHE`
- Root cause: Static field reference

## Giải thích

### Tại sao MemoryLeakDemo bị leak?

```java
private static final Map<String, byte[]> CACHE = new HashMap<>();
//          ^^^^^^                            ^^^^^^^^^^
//          Static = GC root                  Unbounded growth
```

- `static` nghĩa là field tồn tại đến khi class unload
- HashMap không có giới hạn → tăng vô hạn
- Mỗi entry giữ 1MB byte[] → 60 entries = 60MB > 64MB heap

### Tại sao FixedMemoryDemo không bị leak?

```java
private static final Cache<String, byte[]> CACHE = Caffeine.newBuilder()
    .maximumSize(10)                           // Giới hạn 10 entries
    .expireAfterWrite(5, TimeUnit.SECONDS)     // Tự động xóa
    .build();
```

- `maximumSize(10)`: Chỉ giữ tối đa 10 entries (~10MB)
- `expireAfterWrite`: Tự động xóa entries cũ
- Khi đạt giới hạn, Caffeine tự động evict entries ít dùng

## Tham số JVM hữu ích cho Debug Memory

```bash
# Hiển thị GC logs
-XX:+PrintGCDetails -XX:+PrintGCTimeStamps -Xloggc:gc.log

# Tạo heap dump khi OOM
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./dump.hprof

# Hiển thị histogram objects khi OOM
-XX:+CrashOnOutOfMemoryError

# Giới hạn heap nhỏ để dễ trigger OOM trong demo
-Xms64m -Xmx64m
```
