# Distributed Transactional Key-Value Store

A learning-focused implementation of a distributed, transactional key-value database with in-memory performance and disk persistence.

## Project Goals

This project explores database internals from storage engines to distributed consensus:

- **Transaction Management**: ACID properties, MVCC, isolation levels
- **Storage Engines**: In-memory structures, persistence, recovery
- **Distributed Systems**: Consensus protocols (Raft), replication
- **Network Protocols**: Custom RESP-like protocol (DKSP)

## Quick Start

### Prerequisites
- JDK 17 or higher
- Gradle (wrapper included)

### Build
```bash
./gradlew build
```

### Run Server
```bash
./gradlew run
```

The server will start on port 10000.

### Test with telnet
```bash
telnet localhost 10000
```

Type any message and it will be echoed back:
```
Hello!
+ECHO: Hello!
```

Press Ctrl+] then type `quit` to exit.

### Run Tests
```bash
./gradlew test
```

All tests should pass.

## Development

### Run with custom port
Edit `Main.kt`:
```kotlin
val config = ServerConfig(
    host = "0.0.0.0",
    port = 8080  // Change port
)
```

### Run tests continuously
```bash
./gradlew test --continuous
```

### Generate distribution
```bash
./gradlew installDist
./build/install/kotlin/bin/kotlin
```
