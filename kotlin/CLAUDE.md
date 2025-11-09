# Distributed Transactional Key-Value Store

## Rules to follow

- CLAUDE.md must remain compact.
- CLAUDE.md should contain only project milestones and top-level decisions.
- Please use the [docs](docs) directory for detailed information.
- Do not modify decisions that have already been made.

## Project Overview

A learning-focused implementation of a distributed, transactional key-value database with in-memory performance and disk persistence. This project explores database internals from storage engines to distributed consensus.

## Learning Objectives

- **Transaction Management**: ACID properties, isolation levels, concurrency control
- **Storage Engines**: In-memory structures, persistence mechanisms, recovery
- **Distributed Systems**: Consensus protocols, replication, distributed transactions
- **Performance**: Memory vs disk trade-offs, optimization strategies

## Architecture Phases

### Phase 1: Single-Node Transactional KV Store (Foundation)

**Goal**: Build a working transactional database on a single machine

**Components**:
- **Storage Layer**
  - In-memory hash table for active data
  - Write-Ahead Log (WAL) for durability
  - Simple serialization format

- **Transaction Manager**
  - Multi-Version Concurrency Control (MVCC)
  - Snapshot isolation
  - Transaction lifecycle: begin, read, write, commit, abort

- **Recovery Manager**
  - WAL replay on startup
  - Checkpoint mechanism

**Key Concepts to Implement**:
- Transaction IDs and versioning
- Read/write sets tracking
- Conflict detection at commit time
- Atomicity through WAL
- Durability guarantees

### Phase 2: Storage Engine Sophistication

**Goal**: Optimize for larger datasets and better performance

**Enhancements**:
- **LSM-Tree Implementation**
  - MemTable (in-memory write buffer)
  - Immutable MemTables
  - SSTable (Sorted String Table) files on disk
  - Compaction strategies (size-tiered, leveled)
  - Bloom filters for efficient lookups

- **OR B-Tree Implementation** (alternative approach)
  - Node structure and splitting
  - Page-based storage
  - Buffer pool management

- **Garbage Collection**
  - Old version cleanup
  - WAL truncation after checkpoints

**Performance Considerations**:
- Write amplification
- Read amplification
- Space amplification
- Benchmark suite for measuring trade-offs

### Phase 3: Distribution

**Goal**: Scale across multiple nodes with fault tolerance

**Components**:

#### 3A: Replication with Raft
- **Raft Consensus**
  - Leader election
  - Log replication
  - Safety guarantees
  - Configuration changes

- **Replication Strategy**
  - State machine replication
  - Snapshot mechanism
  - Log compaction

#### 3B: Sharding
- **Partitioning**
  - Hash-based or range-based sharding
  - Consistent hashing for rebalancing
  - Shard metadata management

- **Routing Layer**
  - Client request routing
  - Shard discovery

#### 3C: Distributed Transactions
- **Two-Phase Commit (2PC)**
  - Transaction coordinator
  - Prepare phase
  - Commit phase
  - Handling coordinator failures

- **Cross-Shard Transactions**
  - Distributed deadlock detection
  - Timestamp-based ordering (optional)

**Failure Scenarios to Handle**:
- Node crashes
- Network partitions
- Slow/unresponsive nodes
- Split-brain prevention

### Phase 4: Optimization & Production-Ready Features

**Goal**: Performance tuning and operational features

**Optimizations**:
- **Caching**
  - Read cache
  - Write batching
  - Connection pooling

- **Smart Conflict Resolution**
  - Early abort detection
  - Read-only transaction optimization
  - Write skew prevention

**Operational Features**:
- Metrics and monitoring
- Admin API (cluster status, rebalancing)
- Backup and restore
- Schema-less vs typed values

## Technical Decisions

### Language Choice: Kotlin

**Why Kotlin:**
- JVM ecosystem with excellent tooling and libraries
- Coroutines for elegant async/concurrent programming
- Strong type system with null safety
- Interoperability with Java libraries (RocksDB, Netty, etc.)
- Expressive syntax reduces boilerplate
- Spring Boot integration for production features

**Kotlin Advantages for This Project:**
- **Coroutines**: Natural fit for async I/O, network operations, and concurrent transactions
- **Sealed classes**: Perfect for modeling transaction states and consensus messages
- **Data classes**: Clean representation of WAL entries, messages, and storage structures
- **Extension functions**: Elegant APIs for storage and transaction operations
- **Collections API**: Rich functional operations for managing in-memory data structures

**Trade-offs:**
- GC pauses (can be tuned with GC options like G1 or ZGC)
- Slightly higher memory overhead than Rust/C++
- Less control over memory layout than systems languages

### Transaction Model Choice: Optimistic MVCC

**Optimistic MVCC**
- Each transaction operates on a snapshot
- No locks during reads/writes
- Conflict detection at commit time
- Better for read-heavy workloads

### Consensus Protocol Choice: Raft

**Raft**
- Understandable algorithm
- Well-documented
- Many reference implementations
- Used by etcd, TiKV

## Development Milestones

### Milestone 1: Single-Node CRUD
- [ ] Basic in-memory hash table
- [ ] Simple get/put/delete operations
- [ ] No transactions yet

### Milestone 2: Single-Node Transactions
- [ ] MVCC implementation
- [ ] Transaction lifecycle
- [ ] Conflict detection
- [ ] WAL for durability

### Milestone 3: Recovery
- [ ] WAL replay
- [ ] Crash recovery tests
- [ ] Checkpointing

### Milestone 4: Advanced Storage
- [ ] LSM-tree or B-tree
- [ ] Compaction
- [ ] Performance benchmarks

### Milestone 5: Basic Distribution
- [ ] Raft consensus
- [ ] Leader election
- [ ] Log replication
- [ ] 3-node cluster

### Milestone 6: Sharding
- [ ] Hash-based partitioning
- [ ] Routing layer
- [ ] Metadata management

### Milestone 7: Distributed Transactions
- [ ] 2PC implementation
- [ ] Cross-shard transactions
- [ ] Failure handling

### Milestone 8: Production Features
- [ ] Metrics/monitoring
- [ ] Admin tools
- [ ] Documentation

## Testing Strategy

### Unit Tests
- Transaction isolation
- Conflict detection
- WAL serialization/deserialization
- Recovery logic

### Integration Tests
- Multi-transaction scenarios
- Concurrent transactions
- Crash and recovery
- Network partition simulation

### Property-Based Tests
- Invariants (e.g., read-your-writes)
- Serializability checking
- Jepsen-style testing for distributed scenarios

### Benchmarks
- Throughput (transactions/sec)
- Latency (p50, p95, p99)
- Scalability (performance vs. node count)

## Success Metrics

**Phase 1 Success**:
- Single-node transactions work correctly
- ACID properties maintained
- Survives crashes and recovers

**Phase 2 Success**:
- Handles datasets larger than memory
- Read/write performance benchmarked
- Compaction works correctly

**Phase 3 Success**:
- 3+ node cluster
- Tolerates single node failure
- Distributed transactions work
- No data loss during failures

**Final Success**:
- Deep understanding of all components
- Well-tested, documented codebase
- Can explain design trade-offs
- Portfolio-worthy project

## Next Steps

1. ✅ **Language chosen**: Kotlin with Spring Boot
2. ✅ **Project structure set up**: Gradle + Spring Boot initialized
3. **Start with Milestone 1**: Simple in-memory KV store
4. **Read about MVCC** before starting transactions
5. **Keep a design journal** documenting decisions and learnings

## Kotlin-Specific Implementation Considerations

### Serialization
- **kotlinx.serialization**: Kotlin-native, compile-time safe
- **Protocol Buffers**: Industry standard, good for RPC
- **Apache Avro**: Schema evolution support
- **kryo**: Fast JVM serialization

### Async I/O & Concurrency
- **Kotlin Coroutines**: Use for async WAL writes, network I/O
- **Channels**: For communication between coroutines
- **Flow**: Reactive streams for event processing
- **Mutex/Semaphore**: Coroutine-safe synchronization

### Networking
- **ktor**: Kotlin-native async HTTP/RPC framework
- **gRPC-Kotlin**: Type-safe RPC with coroutines
- **Netty**: High-performance NIO (Java interop)

### Testing
- **kotest**: Kotlin testing framework with property-based testing
- **MockK**: Kotlin mocking library
- **testcontainers**: Integration testing with Docker

### Performance Tuning
- JVM GC options (G1GC, ZGC for low latency)
- Use `inline` functions for hot paths
- Array vs List trade-offs
- Avoid excessive object allocation

---

**Remember**: This is a learning project. The goal is understanding, not building the next production database. Take time to experiment, break things, and deeply understand each concept before moving on.

# References

You can find a detailed documents of the project in the [docs](docs) folder.

Good luck! 🚀
