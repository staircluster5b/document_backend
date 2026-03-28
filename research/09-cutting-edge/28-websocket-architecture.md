# WebSocket Architecture - Horizontal Scaling, Connection Pooling, Fallback Strategies

## 1. Mục tiêu của Task

Tìm hiểu bản chất kiến trúc WebSocket trong hệ thống phân tán, các thách thức khi scale horizontally, cơ chế quản lý connection ở quy mô lớn, và chiến lược fallback khi WebSocket không khả dụng.

---

## 2. Bản Chất và Cơ Chế Hoạt Động

### 2.1 WebSocket là gì ở tầng giao thức?

WebSocket là **giao thức full-duplex, stateful** trên nền TCP, được thiết kế để khắc phục hạn chế của HTTP request/response model.

```
┌─────────────────────────────────────────────────────────────┐
│                    HTTP Long Polling                        │
│  Client ──► Server (wait) ◄── Response ──► Repeat...        │
│         latency: high    overhead: high (headers per req)   │
├─────────────────────────────────────────────────────────────┤
│                    Server-Sent Events (SSE)                 │
│  Client ──► Server ◄── Stream (one-way only)               │
│         direction: server→client only                       │
├─────────────────────────────────────────────────────────────┤
│                    WebSocket                                │
│  Client ◄═══════► Server (bidirectional, persistent)        │
│         latency: low     overhead: minimal after handshake  │
└─────────────────────────────────────────────────────────────┘
```

**Bản chất thay đổi mô hình kết nối:**

| Aspect | HTTP/1.1 | WebSocket |
|--------|----------|-----------|
| Connection | Short-lived, request-based | Long-lived, message-based |
| Direction | Client-initiated only | Bidirectional |
| Overhead per message | ~800-1200 bytes headers | 2-14 bytes frame header |
| Server push | Not native | Native |
| State | Stateless | Stateful (connection-bound) |

### 2.2 WebSocket Handshake - Nâng cấp từ HTTP

WebSocket không phải "song song" với HTTP mà là **upgrade từ HTTP**:

```
Client                                          Server
   │ ── GET /ws HTTP/1.1 ──────────────────────► │
   │    Connection: Upgrade                       │
   │    Upgrade: websocket                        │
   │    Sec-WebSocket-Key: dGhlIHNhbXBsZQ==      │
   │                                              │
   │ ◄── HTTP/1.1 101 Switching Protocols ────── │
   │    Upgrade: websocket                        │
   │    Sec-WebSocket-Accept: s3pPL...           │
   │                                              │
   │ ╔═══════════════════════════════════════╗    │
   │ ║  TCP connection now WebSocket tunnel  ║    │
   │ ║  (framed messages, full-duplex)       ║    │
   │ ╚═══════════════════════════════════════╝    │
   │ ◄═══════════ WebSocket Frames ═════════════► │
```

**Key insight:** Sau handshake 101, cùng một TCP connection được "re-purpose" thành WebSocket tunnel. Điều này có ý nghĩa:
- **Tái sử dụng cơ sở hạ tầng HTTP**: Load balancers, firewalls, proxies đều hiểu upgrade
- **Stateful từ góc độ server**: Connection object giữ trạng thái user/session
- **Resource binding**: Server thread/memory gắn với connection

### 2.3 WebSocket Frame Structure

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-------+-+-------------+-------------------------------+
|F|R|R|R| opcode|M| Payload len |    Extended payload length    |
|I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
|N|V|V|V|       |S|             |   (if payload len==126/127)   |
| |1|2|3|       |K|             |                               |
+-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
|     Extended payload length continued, if payload len == 127  |
+ - - - - - - - - - - - - - - - +-------------------------------+
|                               |Masking-key, if MASK set to 1  |
+-------------------------------+-------------------------------+
| Masking-key (continued)       |          Payload Data         |
+-------------------------------- - - - - - - - - - - - - - - -+
:                     Payload Data continued ...                :
+ - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
|                     Payload Data continued ...                |
+---------------------------------------------------------------+
```

**Ý nghĩa thiết kế:**
- **Mask bit (client→server only)**: Ngăn cache poisoning attack trên proxies
- **Opcode**: Text (0x1), Binary (0x2), Close (0x8), Ping/Pong (0x9/0xA)
- **Max payload**: 2^63 bytes (thực tế thường giới hạn ở MBs)

---

## 3. Kiến Trúc và Luồng Xử Lý

### 3.1 Horizontal Scaling Challenge

Vấn đề cốt lõi của WebSocket scaling: **Connections là stateful và sticky**.

```
                    ┌─────────────────────────────────────────┐
                    │           Load Balancer                 │
                    │    (Layer 4 - TCP / Layer 7 - HTTP)     │
                    └──────────────┬──────────────────────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              │                    │                    │
              ▼                    ▼                    ▼
    ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
    │   Server A      │  │   Server B      │  │   Server C      │
    │  (Connections)  │  │  (Connections)  │  │  (Connections)  │
    │                 │  │                 │  │                 │
    │  User:1 ────────┼──┼──► User:2       │  │  User:3 ────────┤
    │  User:4 ────────┤  │  User:5 ────────┤  │  User:6 ────────┤
    └─────────────────┘  └─────────────────┘  └─────────────────┘
              │                    │                    │
              │    ❌ PROBLEM:    │                    │
              │    User 1 gửi     │                    │
              │    message cho    │                    │
              │    User 2, nhưng  │                    │
              │    họ ở 2 server  │                    │
              │    khác nhau!     │                    │
              └────────────────────┴────────────────────┘
```

**Bản chất vấn đề:**
- WebSocket connections được duy trì trên một server cụ thể
- Message routing giữa các users cần cross-server communication
- Load balancer phải ensure **session affinity** (sticky sessions) cho reconnects

### 3.2 Giải pháp: Pub/Sub Message Broker Pattern

```
┌─────────────────────────────────────────────────────────────────────┐
│                    WebSocket Cluster Architecture                    │
└─────────────────────────────────────────────────────────────────────┘

    Clients                    Load Balancer              Servers
       │                           │                        │
       │  ┌─ WebSocket Conn ────┐  │                        │
       ├──┤   User A (sticky)   ├──┼───────────────────────►│
       │  └─────────────────────┘  │              ┌─────────┴────────┐
       │                           │              │   Server 1       │
       │  ┌─ WebSocket Conn ────┐  │              │  ┌─────────────┐ │
       ├──┤   User B (sticky)   ├──┼──────────────┼─►│ Connection  │ │
       │  └─────────────────────┘  │              │  │   Manager   │ │
       │                           │              │  └──────┬──────┘ │
       │                           │              │         │        │
       │                           │              │    ┌────┴────┐   │
       │                           │              │    │ Pub/Sub │◄──┼──┐
       │                           │              │    │ Adapter │   │  │
       │                           │              │    └────┬────┘   │  │
       │                           │              │         │        │  │
       │                           │              └─────────┼────────┘  │
       │                           │                        │           │
       │                           │              ┌─────────┴────────┐  │
       │                           │              │   Server 2       │  │
       │  ┌─ WebSocket Conn ────┐  │              │  ┌─────────────┐ │  │
       └──┤   User C (sticky)   ├──┼──────────────┼─►│ Connection  │ │  │
          └─────────────────────┘  │              │  │   Manager   │ │  │
                                   │              │  └──────┬──────┘ │  │
                                   │              │         │        │  │
                                   │              │    ┌────┴────┐   │  │
                                   │              │    │ Pub/Sub │◄──┼──┘
                                   │              │    │ Adapter │   │
                                   │              │    └────┬────┘   │
                                   │              │         │        │
                                   │              └─────────┼────────┘
                                   │                        │
                              ┌────┴────────────────────────┘
                              │
                    ┌─────────▼──────────┐
                    │   Message Broker   │
                    │  (Redis/RabbitMQ/  │
                    │   Kafka/NATS)      │
                    └────────────────────┘
```

**Luồng message routing giữa users trên các server khác nhau:**

1. User A (Server 1) gửi message cho User B
2. Server 1 kiểm tra: User B có connected locally không? → No
3. Server 1 publish message vào channel `user:B` trên Message Broker
4. Server 2 (nơi User B connected) subscribe channel `user:B`
5. Server 2 nhận message và gửi qua WebSocket connection đến User B

### 3.3 Connection Pooling Ở Client-Side

```
┌─────────────────────────────────────────────────────────────┐
│              WebSocket Client Connection Pool               │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐     │
│  │ Connection 1│    │ Connection 2│    │ Connection N│     │
│  │ (READY)     │    │ (BUSY)      │    │ (READY)     │     │
│  │ ◄───────►   │    │ ◄───────►   │    │ ◄───────►   │     │
│  │ Server A    │    │ Server B    │    │ Server C    │     │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘     │
│         │                  │                  │            │
│         └──────────────────┼──────────────────┘            │
│                            │                               │
│                   ┌────────▼────────┐                      │
│                   │   Pool Manager  │                      │
│                   │  - Round-robin  │                      │
│                   │  - Least-loaded │                      │
│                   │  - Health check │                      │
│                   └────────┬────────┘                      │
│                            │                               │
│                   ┌────────▼────────┐                      │
│                   │   Application   │                      │
│                   │   (send/recv)   │                      │
│                   └─────────────────┘                      │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Khi nào cần connection pooling ở client?**
- Browser: Mỗi tab 1-6 connections đến cùng domain (HTTP/1.1 limit)
- Mobile apps: Connection pool cho multiple data streams
- Microservices: Service-to-service WebSocket communication

**Trade-off:**
- Pooling giảm connection overhead nhưng tăng complexity
- Browser thường không cần (single user context)
- Backend services gọi nhau qua WebSocket: pooling critical

---

## 4. So Sánh Các Giải Pháp

### 4.1 Message Broker Options

| Broker | Use Case | Pros | Cons | WebSocket Integration |
|--------|----------|------|------|----------------------|
| **Redis** | Real-time chat, notifications | Simple, fast, pub/sub native | No persistence, single-node bottleneck | Socket.io, Spring WebFlux |
| **RabbitMQ** | Enterprise messaging, routing | Routing patterns, reliability | Higher latency, complex | STOMP over WebSocket |
| **Kafka** | Event streaming, analytics | High throughput, persistence | Higher latency, not real-time native | Kafka WebSocket proxy |
| **NATS** | Cloud-native, microservices | Lightweight, fast, clustering | Smaller ecosystem | Native WebSocket support |
| **Pulsar** | Multi-tenant streaming | Tiered storage, geo-replication | Operational complexity | Pulsar WebSocket API |

### 4.2 Scaling Strategies Comparison

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Scaling Strategy Decision Matrix                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  │
│  │   STICKY LOAD    │  │   STATE SHARDING │  │  FULL MESH       │  │
│  │   BALANCER       │  │   (User-based)   │  │  (P2P Broadcast) │  │
│  ├──────────────────┤  ├──────────────────┤  ├──────────────────┤  │
│  │ • IP Hash        │  │ • User ID % N    │  │ • Each server    │  │
│  │ • Cookie-based   │  │ • Consistent Hash│  │   knows all      │  │
│  │ • Session        │  │ • Route by room  │  │   connections    │  │
│  ├──────────────────┤  ├──────────────────┤  ├──────────────────┤  │
│  │ Pros:            │  │ Pros:            │  │ Pros:            │  │
│  │ - Simple         │  │ - No broadcast   │  │ - No broker      │  │
│  │ - No broker      │  │ - Predictable    │  │ - Simple routing │  │
│  ├──────────────────┤  ├──────────────────┤  ├──────────────────┤  │
│  │ Cons:            │  │ Cons:            │  │ Cons:            │  │
│  │ - Hot spots      │  │ - Complex LB     │  │ - O(n²) traffic  │  │
│  │ - Reconnect UX   │  │ - Rebalance cost │  │ - Not scalable   │  │
│  └──────────────────┘  └──────────────────┘  └──────────────────┘  │
│                                                                      │
│  ⭐ RECOMMENDED: Sticky LB + Redis Pub/Sub cho real-time chat       │
│  ⭐ RECOMMENDED: State Sharding cho game servers, room-based apps   │
│  ⭐ AVOID: Full Mesh cho >10 nodes                                   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 5. Rủi Ro, Anti-patterns, Lỗi Thường Gặp

### 5.1 Connection Leak

```java
// ❌ ANTI-PATTERN: Không đóng connection khi exception xảy ra
@OnOpen
public void onOpen(Session session) {
    connections.add(session);  // Added
    // Exception xảy ra ở đây → không remove
}

@OnClose
public void onClose(Session session) {
    connections.remove(session);  // Never called
}

// ✅ PATTERN: Try-with-resources hoặc finally block
@OnOpen
public void onOpen(Session session) {
    connections.put(session.getId(), session);
    metrics.incrementGauge("websocket.connections.active");
}

@OnError
public void onError(Session session, Throwable error) {
    log.error("WebSocket error for session {}", session.getId(), error);
    cleanup(session);  // Always cleanup
}

@OnClose
public void onClose(Session session, CloseReason reason) {
    cleanup(session);
}

private void cleanup(Session session) {
    Session removed = connections.remove(session.getId());
    if (removed != null) {
        metrics.decrementGauge("websocket.connections.active");
        // Notify other services user disconnected
        eventPublisher.publishEvent(new UserDisconnectedEvent(session));
    }
}
```

### 5.2 Message Loss During Deployment

> **Vấn đề:** Khi redeploy server, tất cả WebSocket connections bị đóng. Messages gửi trong window này bị mất.

**Giải pháp:**

1. **Graceful Shutdown Sequence:**
   ```
   1. Stop accepting new connections
   2. Drain: Wait for existing connections to close (or timeout)
   3. Persist undelivered messages to Redis
   4. Shutdown
   5. New instance: Recover messages from Redis
   ```

2. **Client Reconnect with Message Replay:**
   - Client lưu last message ID
   - Khi reconnect, gửi `?since=message_id`
   - Server trả lại messages từ đó

### 5.3 Thundering Herd (Reconnect Storm)

```
Scenario: Server restart

T=0: Server goes down
T=1: 10,000 clients detect disconnect
T=2: All 10,000 try reconnect immediately
T=3: Server overwhelmed → fails again
T=4: Loop continues...
```

**Giải pháp: Exponential Backoff + Jitter**

```javascript
// Client-side reconnect strategy
const reconnect = (attempt) => {
    const baseDelay = 1000;      // 1 second
    const maxDelay = 30000;      // 30 seconds
    const jitter = Math.random() * 1000;  // Random 0-1s
    
    const delay = Math.min(
        baseDelay * Math.pow(2, attempt),  // Exponential
        maxDelay
    ) + jitter;
    
    setTimeout(() => connect(), delay);
};
```

### 5.4 Memory Pressure từ Message Buffering

```java
// ❌ ANTI-PATTERN: Unbounded queue
@Component
public class MessageHandler {
    private final Queue<Message> pendingMessages = new LinkedList<>();
    // Có thể grow vô hạn → OOM
}

// ✅ PATTERN: Bounded queue với backpressure
@Component
public class MessageHandler {
    private final BlockingQueue<Message> pendingMessages = 
        new ArrayBlockingQueue<>(10000);  // Bounded
    
    public void sendMessage(Message msg) throws BackPressureException {
        boolean accepted = pendingMessages.offer(msg, 100, TimeUnit.MILLISECONDS);
        if (!accepted) {
            // Queue full → apply backpressure
            throw new BackPressureException("Server overloaded");
        }
    }
}
```

### 5.5 Security Pitfalls

| Attack Vector | Mitigation |
|--------------|------------|
| **DoS via connection exhaustion** | Connection limits per IP, rate limiting |
| **Message flooding** | Rate limiting per user, message size limits |
| **Cross-site WebSocket hijacking** | Origin validation, CSRF tokens |
| **Man-in-the-middle** | WSS (TLS), certificate pinning |
| **Authentication timing** | Authenticate during handshake, not after |

---

## 6. Khuyến Nghị Thực Chiến Production

### 6.1 Architecture Patterns

#### Pattern 1: Gateway Aggregation (Microservices)

```
┌─────────────────────────────────────────────────────────────┐
│                      API Gateway                            │
│         (Spring Cloud Gateway / Kong / Envoy)              │
│                    • Auth/Rate Limit                        │
│                    • Route to services                      │
└──────────────┬──────────────────────────────────────────────┘
               │
    ┌──────────┴──────────┬───────────────┐
    │                     │               │
┌───▼────┐          ┌────▼────┐    ┌────▼────┐
│User    │          │Chat     │    │Order    │
│Service │          │Service  │    │Service  │
│(HTTP)  │          │(WebSocket│    │(WebSocket│
└────────┘          │ Gateway) │    │ Gateway) │
                    └────┬─────┘    └────┬─────┘
                         │               │
                    ┌────▼───────────────▼─────┐
                    │     Message Broker       │
                    │      (Redis/Kafka)       │
                    └──────────────────────────┘
```

**Lý do:** Mỗi service quản lý WebSocket connections riêng, tránh single point of failure.

#### Pattern 2: CQRS cho Message History

```
┌─────────────────┐         ┌─────────────────┐
│  Command Side   │         │   Query Side    │
│                 │         │                 │
│  WebSocket      │         │  Message        │
│  Handler        │         │  History API    │
│       │         │         │       ▲         │
│       ▼         │         │       │         │
│  Event Store    │────────►│  Read Model     │
│  (Kafka/Pulsar) │         │  (Elasticsearch)│
└─────────────────┘         └─────────────────┘
```

**Lý do:** WebSocket xử lý real-time; lịch sử query qua separate optimized read model.

### 6.2 Monitoring & Observability

**Metrics cần theo dõi:**

```yaml
# Prometheus metrics example
websocket_connections_active:    # Gauge - connections hiện tại
websocket_connections_total:     # Counter - tổng connections tạo ra
websocket_messages_sent_total:   # Counter - messages đã gửi
websocket_messages_received_total: # Counter - messages nhận
websocket_message_latency_seconds: # Histogram - latency gửi message
websocket_errors_total:          # Counter - lỗi
websocket_buffer_utilization:    # Gauge - % buffer đã dùng
```

**Distributed Tracing:**

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client    │────►│  Gateway    │────►│   Service   │
│  (Browser)  │     │  (trace:1)  │     │  (trace:1)  │
└─────────────┘     └─────────────┘     └──────┬──────┘
                                                │
                                           ┌────┴────┐
                                           │  Redis  │
                                           │ Publish │
                                           └────┬────┘
                                                │
┌─────────────┐     ┌─────────────┐     ┌───────▼───────┐
│   Client B  │◄────│  Gateway B  │◄────│  Subscribe    │
│  (Browser)  │     │  (trace:1)  │     │  (trace:1)    │
└─────────────┘     └─────────────┘     └───────────────┘

Trace ID: propagated qua tất cả hops
```

### 6.3 Java Implementation với Spring Boot

**Dependencies (Spring Boot 3.x):**

```xml
<dependencies>
    <!-- WebSocket support -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-websocket</artifactId>
    </dependency>
    
    <!-- Redis for pub/sub clustering -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    
    <!-- Reactive WebSocket (alternative) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webflux</artifactId>
    </dependency>
</dependencies>
```

**Configuration:**

```yaml
spring:
  websocket:
    # Message size limits
    max-text-message-size: 64KB
    max-binary-message-size: 1MB
    # Buffer sizes
    send-buffer-size: 512KB
    # Timeouts
    send-timeout: 5000ms
    
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    lettuce:
      pool:
        max-active: 50
        max-idle: 20
```

### 6.4 Fallback Strategies

```
┌─────────────────────────────────────────────────────────────┐
│                  Fallback Strategy Matrix                    │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  WebSocket Available?    No     →     Use SSE               │
│       │                                                      │
│       ▼                                                      │
│  SSE Available?          No     →     Use Long Polling      │
│       │                                                      │
│       ▼                                                      │
│  All Fails?              Yes    →     Graceful Degradation  │
│                                      (Polling every 30s)    │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

**Auto-detect và fallback ở client:**

```javascript
class ResilientSocket {
    constructor(url) {
        this.url = url;
        this.transport = null;
        this.fallbackChain = [
            () => this.tryWebSocket(),
            () => this.trySSE(),
            () => this.tryLongPolling()
        ];
    }
    
    async connect() {
        for (const attempt of this.fallbackChain) {
            try {
                this.transport = await attempt();
                console.log(`Connected via ${this.transport.type}`);
                return;
            } catch (e) {
                console.warn(`${attempt.name} failed:`, e);
            }
        }
        throw new Error('All transports failed');
    }
}
```

---

## 7. Kết Luận

### Bản chất cốt lõi:

1. **WebSocket là stateful connection** → Scaling đòi hỏi cross-server coordination qua message broker

2. **Trade-off chính:** 
   - Real-time latency vs Operational complexity
   - Horizontal scale vs Session affinity requirements
   - Connection state vs Fault tolerance

3. **Architecture đúng:**
   - Sticky load balancer cho connection routing
   - Redis/RabbitMQ pub/sub cho cross-server messaging
   - Graceful shutdown + exponential backoff cho deployment

4. **Production checklist:**
   - [ ] Connection limits và rate limiting
   - [ ] Message buffering có bounds
   - [ ] Monitoring: connections, latency, errors
   - [ ] Fallback: SSE → Long polling → Graceful degradation
   - [ ] Security: WSS, origin validation, authentication handshake

### Khi nào KHÔNG nên dùng WebSocket:

- Client chỉ nhận data (dùng SSE - đơn giản hơn)
- Yêu cầu stateless, RESTful (WebSocket phá vỡ điều này)
- Quá ít clients (overhead không đáng)
- Cần caching / CDN (WebSocket không cacheable)

### Java 21+ Considerations:

- **Virtual Threads:** Giảm memory footprint mỗi connection
- **Structured Concurrency:** Quản lý lifecycle của message handlers
- **Foreign Function API:** Integration với native WebSocket libraries nếu cần

---

> **TL;DR:** WebSocket scaling = Sticky sessions + Pub/Sub broker + Graceful degradation. Stateful nature là trade-off chấp nhận để có real-time. Production cần monitoring, backpressure, và fallback strategies.
