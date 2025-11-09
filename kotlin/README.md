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

### Run KV Store Server
```bash
./gradlew run
# or explicitly:
./gradlew runKVStore
```

The KV store server will start on port 10000.

### Run Sequencer Server
```bash
./gradlew runSequencer --args="--initial=1 --port=10001"
```

The sequencer server will start on port 10001.

### Test with telnet

#### KV Store (Port 10000)
```bash
telnet localhost 10000
```

Example transaction:
```
BEGIN
:1

PUT :1 user:alice {"name":"Alice","age":30}
+OK

GET :1 user:alice
{"name":"Alice","age":30}

COMMIT :1
+OK
```

#### Sequencer (Port 10001)
```bash
telnet localhost 10001
```

Get sequence numbers:
```
GETSEQ
:1

GETSEQ
:2

CURRENT
:3
```

Press Ctrl+] then type `quit` to exit.

### Run Tests
```bash
./gradlew test
```

All tests should pass.

### View Code Coverage
```bash
./gradlew test
open build/reports/jacoco/test/html/index.html
```
