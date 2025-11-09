# Network Layer Design

## Overview

A high-performance, coroutine-based network layer for the distributed KV store. Built on Kotlin coroutines with multi-core support and non-blocking I/O.

## Architecture

### Components

```
┌─────────────────────────────────────────────────────────────┐
│                      DKVServer                              │
│  - Lifecycle management (start, stop, graceful shutdown)   │
│  - Configuration (port, timeouts, buffer sizes)             │
│  - Metrics/monitoring hooks                                 │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                   ConnectionManager                          │
│  - Accept incoming connections                              │
│  - Connection pooling/limits                                │
│  - Per-connection coroutine spawning                        │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                   ConnectionHandler                          │
│  - Per-connection state machine                             │
│  - Read loop (non-blocking)                                 │
│  - Write loop (non-blocking)                                │
│  - Line buffering                                           │
│  - Timeout handling                                         │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                   RequestHandler (Interface)                │
│  - Process requests (pluggable)                             │
│  - Generate responses                                       │
│  - Future: Protocol parser will implement this              │
└─────────────────────────────────────────────────────────────┘
```

## Technology Choices

### Option 1: ktor-network (Recommended)
- **Pros**:
  - Built for coroutines
  - Handles low-level NIO details
  - Well-tested and maintained
  - Good performance
- **Cons**:
  - Additional dependency
  - Less control over internals

### Option 2: Raw NIO with Coroutines
- **Pros**:
  - Full control
  - Learning experience
  - No extra dependencies
- **Cons**:
  - More complexity
  - Need to handle edge cases
  - More boilerplate

**Recommendation**: Start with **ktor-network** for rapid development, can switch to raw NIO later if needed.

## Detailed Component Design

### 1. Server Configuration

```kotlin
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 10000,
    val maxConnections: Int = 1000,
    val readTimeoutMs: Long = 30_000,
    val writeTimeoutMs: Long = 30_000,
    val idleTimeoutMs: Long = 300_000,  // 5 minutes
    val maxLineSize: Int = 64 * 1024,   // 64KB
    val socketBufferSize: Int = 8192,
    val backlogSize: Int = 128
)
```

### 2. DKVServer

**Responsibilities**:
- Server lifecycle (start, stop, shutdown)
- Connection acceptance
- Metrics aggregation
- Graceful shutdown coordination

**Key Methods**:
```kotlin
interface Server {
    suspend fun start()
    suspend fun stop()
    suspend fun awaitTermination()
    fun isRunning(): Boolean
}
```

**Coroutine Structure**:
```
Main Server Coroutine (Dispatchers.IO)
├── Accept Loop Coroutine
│   └── Spawns Connection Handler per client
├── Metrics Collection Coroutine (optional)
└── Health Check Coroutine (optional)
```

### 3. ConnectionManager

**Responsibilities**:
- Accept incoming TCP connections
- Track active connections
- Enforce connection limits
- Coordinate shutdown

**State**:
- Active connection count
- Connection registry (for graceful shutdown)
- Accept socket

### 4. ConnectionHandler

**Responsibilities**:
- Handle single client connection lifecycle
- Read from socket (line-buffered)
- Write to socket
- Handle timeouts
- Close connection on error or client disconnect

**Coroutine Structure** (per connection):
```
Connection Scope
├── Read Loop Coroutine (Dispatchers.IO)
│   ├── Read bytes from socket
│   ├── Buffer until CRLF
│   └── Pass complete line to handler
└── Write Loop Coroutine (Dispatchers.IO)
    ├── Receive responses from channel
    └── Write to socket
```

**State Machine**:
```
[CONNECTED]
    │
    ├─ Read line → [PROCESSING]
    │                   │
    │                   └─ Handler processes → [WRITING]
    │                                               │
    │                                               └─ Write response → [CONNECTED]
    │
    ├─ Timeout → [CLOSED]
    └─ Error/EOF → [CLOSED]
```

### 5. RequestHandler Interface

**Purpose**:
- Decouple network layer from protocol/business logic
- Allow pluggable implementations
- Enable testing with mock handlers

```kotlin
interface RequestHandler {
    /**
     * Process a request line and return a response
     * This will be async to allow transaction processing
     */
    suspend fun handle(request: String): String
}
```

**Initial Implementation** (for testing):
```kotlin
class EchoHandler : RequestHandler {
    override suspend fun handle(request: String): String {
        return "+ECHO: $request\r\n"
    }
}
```

## Multi-Core Strategy

### Dispatcher Configuration

```kotlin
// Dedicated dispatcher for network I/O
val networkDispatcher = Dispatchers.IO.limitedParallelism(
    parallelism = 4  // Or Runtime.getRuntime().availableProcessors()
)

// Separate dispatcher for request processing (CPU-bound work)
val processorDispatcher = Dispatchers.Default

// Usage pattern:
withContext(networkDispatcher) {
    // Socket I/O operations
}

withContext(processorDispatcher) {
    // Request processing, transaction logic
}
```

### Thread Pool Sizing

**Network I/O Threads** (Dispatchers.IO):
- Recommendation: `min(num_cores * 2, max_connections / 100)`
- Handles socket operations (read/write)
- Blocking I/O operations

**Processing Threads** (Dispatchers.Default):
- Recommendation: `num_cores`
- Handles business logic (future: transaction management)
- CPU-bound operations

### Load Distribution

Connections are naturally distributed across threads:
- Each connection runs in its own coroutine
- Coroutines are scheduled across thread pool
- No manual thread assignment needed
- Kotlin runtime handles work-stealing

## Buffer Management

### Read Buffer
```kotlin
class LineBuffer(private val maxLineSize: Int) {
    private val buffer = ByteArrayOutputStream()

    fun append(bytes: ByteArray, length: Int): List<String> {
        // Append bytes to buffer
        // Scan for CRLF sequences
        // Return complete lines
        // Keep partial line in buffer
    }

    fun reset()
}
```

### Write Buffer
- Use Kotlin's `ByteReadChannel` / `ByteWriteChannel`
- Automatic backpressure handling
- Channel-based coordination between handler and writer

## Error Handling

### Connection-Level Errors
- **Timeout**: Close connection, log warning
- **Parse Error**: Send error response, close connection
- **I/O Error**: Close connection, log error
- **Handler Exception**: Send error response, keep connection open (optional)

### Server-Level Errors
- **Bind Error**: Fail fast, log error, exit
- **Resource Exhaustion**: Reject new connections, log error
- **Shutdown Signal**: Graceful shutdown initiated

## Graceful Shutdown

**Shutdown Sequence**:
1. Stop accepting new connections
2. Wait for active connections to complete (with timeout)
3. Force-close remaining connections
4. Release resources
5. Cancel all coroutines

```kotlin
suspend fun shutdown(gracePeriodMs: Long = 5000) {
    // 1. Stop accepting
    acceptJob.cancel()

    // 2. Wait for active connections
    withTimeoutOrNull(gracePeriodMs) {
        activeConnections.awaitAll()
    }

    // 3. Force close remaining
    activeConnections.forEach { it.close() }

    // 4. Cleanup
    serverScope.cancel()
}
```

## Metrics & Observability

**Key Metrics** (future):
- Active connections count
- Total connections (lifetime)
- Requests per second
- Bytes sent/received
- Average request latency
- Error rate

**Implementation**:
- Use atomic counters for thread-safe metrics
- Expose via separate metrics endpoint or logging

## Testing Strategy

### Unit Tests
- `LineBuffer` parsing logic
- Configuration validation
- State machine transitions

### Integration Tests
- Connect multiple clients
- Send/receive messages
- Test timeouts
- Test graceful shutdown
- Load testing (1K+ concurrent connections)

### Tools
- `kotest` for testing framework
- `kotlinx-coroutines-test` for coroutine testing
- `telnet` / `nc` for manual testing

## Implementation Plan

### Phase 1: Basic Server (Milestone 1)
1. ✅ Design document (this file)
2. Create project structure
3. Implement `ServerConfig`
4. Implement basic `DKVServer` with ktor-network
5. Implement `ConnectionHandler` with echo handler
6. Basic integration test (telnet connectivity)

### Phase 2: Robustness (Milestone 1 continued)
1. Add line buffering
2. Add timeout handling
3. Add graceful shutdown
4. Add connection limits
5. Integration tests for edge cases

### Phase 3: Performance (Milestone 2)
1. Multi-core dispatcher tuning
2. Load testing (benchmark with multiple clients)
3. Metrics collection
4. Performance profiling

### Phase 4: Protocol Integration (Milestone 2)
1. Implement protocol parser as `RequestHandler`
2. Integrate with transaction manager
3. End-to-end tests

## Code Structure

```
src/main/kotlin/com/dolmaengi/kvstore/
├── network/
│   ├── Server.kt                    # Interface + Config
│   ├── DKVServer.kt                 # Main server implementation
│   ├── ConnectionHandler.kt         # Per-connection handler
│   ├── LineBuffer.kt                # Line buffering utility
│   └── RequestHandler.kt            # Handler interface
├── handlers/
│   └── EchoHandler.kt               # Echo implementation (testing)
└── Main.kt                          # Entry point

src/test/kotlin/com/dolmaengi/kvstore/
├── network/
│   ├── DKVServerTest.kt
│   ├── ConnectionHandlerTest.kt
│   └── LineBufferTest.kt
└── integration/
    └── ServerIntegrationTest.kt
```

## Dependencies

### Gradle Dependencies (build.gradle.kts)

```kotlin
dependencies {
    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.3")

    // ktor network
    implementation("io.ktor:ktor-network:2.3.7")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:5.1.0")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Testing
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
```

## Open Questions

1. **Connection pooling**: Should we implement connection pooling for reuse?
   - **Decision**: Not needed for now, one connection per client session

2. **Pipelining**: How to handle pipelined requests?
   - **Decision**: Process requests sequentially per connection for simplicity

3. **Backpressure**: What happens if handler is slower than client sends?
   - **Decision**: Use channel with bounded capacity, apply backpressure on read

4. **SSL/TLS**: When to add encryption support?
   - **Decision**: Phase 4 (future), focus on plaintext TCP first

## Performance Targets

**Initial Goals** (Phase 1-2):
- Support 1,000+ concurrent connections
- < 1ms p99 latency for echo responses
- Handle 10K+ requests/sec on single machine

**Future Goals** (Phase 3+):
- Support 10,000+ concurrent connections
- < 5ms p99 latency for transactional operations
- Handle 50K+ requests/sec

## References

- [Kotlin Coroutines Guide](https://kotlinlang.org/docs/coroutines-guide.html)
- [ktor Network API](https://ktor.io/docs/servers-raw-sockets.html)
- [Redis Protocol Specification](https://redis.io/docs/reference/protocol-spec/) (inspiration)
- [The C10K Problem](http://www.kegel.com/c10k.html) (background)

---

**Next Steps**: Review this design, get feedback, then proceed with implementation of Phase 1.
