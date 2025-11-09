# Distributed Transactional Key-Value Store

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

**API Design**:
```
begin_transaction() -> TxnID
get(txn_id, key) -> value
put(txn_id, key, value)
delete(txn_id, key)
commit(txn_id) -> success/abort
abort(txn_id)
```

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

### Language Options

**Rust** (Recommended for deep learning)
- Pros: Memory safety, performance, forces understanding of ownership
- Cons: Steeper learning curve, slower initial development
- Great for: Understanding low-level details, zero-cost abstractions

**Go** (Recommended for faster iteration)
- Pros: Simple concurrency, faster development, good stdlib
- Cons: GC pauses, less control over memory
- Great for: Distributed systems, network programming

**C++**
- Pros: Maximum control, performance
- Cons: Memory management complexity, longer development
- Great for: If you want absolute control

### Transaction Model

**Optimistic MVCC (Recommended)**
- Each transaction operates on a snapshot
- No locks during reads/writes
- Conflict detection at commit time
- Better for read-heavy workloads

**Timestamp Ordering**
- Each transaction gets a timestamp
- Operations must respect timestamp order
- Can abort transactions early

**Pessimistic Locking**
- 2PL (Two-Phase Locking)
- Simpler to reason about
- Can lead to deadlocks

### Storage Format

**WAL Format**:
```
[Transaction ID | Operation Type | Key | Value | Timestamp | Checksum]
```

**LSM-Tree SSTables**:
```
[Index Block | Data Blocks | Bloom Filter | Footer]
```

### Consensus Protocol

**Raft** (Recommended)
- Understandable algorithm
- Well-documented
- Many reference implementations
- Used by etcd, TiKV

**Paxos**
- More complex
- Academic interest
- Harder to implement correctly

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

## Resources & References

### Papers
- "Designing Data-Intensive Applications" by Martin Kleppmann (book)
- "In Search of an Understandable Consensus Algorithm (Raft)" - Ongaro & Ousterhout
- "Bigtable: A Distributed Storage System" - Google
- "Spanner: Google's Globally-Distributed Database"
- "Calvin: Fast Distributed Transactions"

### Open Source Inspiration
- **etcd**: Go, Raft-based, production KV store
- **TiKV**: Rust, distributed transactional KV store (from TiDB)
- **FoundationDB**: Inspiration for architecture
- **RocksDB**: LSM-tree storage engine (C++)
- **BadgerDB**: LSM-tree in Go

### Educational Resources
- CMU Database Systems course (15-445/645)
- MIT 6.824 Distributed Systems
- PingCAP Talent Plan (Rust-based DB course)

## Project Structure Suggestion

```
/
├── src/
│   ├── storage/
│   │   ├── memtable.rs
│   │   ├── wal.rs
│   │   ├── sstable.rs
│   │   └── lsm.rs
│   ├── transaction/
│   │   ├── mvcc.rs
│   │   ├── txn_manager.rs
│   │   └── conflict_detector.rs
│   ├── consensus/
│   │   ├── raft.rs
│   │   └── log.rs
│   ├── distributed/
│   │   ├── coordinator.rs
│   │   ├── two_phase_commit.rs
│   │   └── shard_manager.rs
│   ├── network/
│   │   ├── rpc.rs
│   │   └── client.rs
│   └── main.rs
├── tests/
│   ├── integration/
│   ├── property/
│   └── benchmark/
├── docs/
│   ├── architecture.md
│   ├── api.md
│   └── design-decisions.md
├── Cargo.toml (or go.mod)
└── README.md
```

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

## Common Pitfalls to Avoid

1. **Skipping Phase 1**: Don't go distributed too early
2. **Ignoring Testing**: Distributed bugs are hard to debug
3. **Premature Optimization**: Get correctness first
4. **Scope Creep**: Stick to the core features first
5. **Not Writing Docs**: Document decisions as you go
6. **Reinventing Everything**: Use existing libraries for non-core features (networking, serialization)

## Next Steps

1. **Choose your language** (Rust or Go recommended)
2. **Set up project structure**
3. **Start with Milestone 1**: Simple in-memory KV store
4. **Read about MVCC** before starting transactions
5. **Keep a design journal** documenting decisions and learnings

## Questions to Consider

- How will you handle schema evolution?
- What serialization format? (Protocol Buffers, MessagePack, bincode)
- Sync vs async I/O?
- Threading model?
- Error handling strategy?
- Configuration management?

---

**Remember**: This is a learning project. The goal is understanding, not building the next production database. Take time to experiment, break things, and deeply understand each concept before moving on.

Good luck! 🚀
