# Project Structure

```
kotlin/
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   └── dolmeangi/kotlin/
│   │   │       ├── storage/
│   │   │       │   ├── MemTable.kt
│   │   │       │   ├── WriteAheadLog.kt
│   │   │       │   ├── SSTable.kt
│   │   │       │   └── LSMTree.kt
│   │   │       ├── transaction/
│   │   │       │   ├── MVCC.kt
│   │   │       │   ├── TransactionManager.kt
│   │   │       │   ├── Transaction.kt
│   │   │       │   └── ConflictDetector.kt
│   │   │       ├── consensus/
│   │   │       │   ├── Raft.kt
│   │   │       │   ├── RaftLog.kt
│   │   │       │   └── RaftState.kt
│   │   │       ├── distributed/
│   │   │       │   ├── Coordinator.kt
│   │   │       │   ├── TwoPhaseCommit.kt
│   │   │       │   └── ShardManager.kt
│   │   │       ├── network/
│   │   │       │   ├── RpcServer.kt
│   │   │       │   └── RpcClient.kt
│   │   │       └── KotlinApplication.kt
│   │   └── resources/
│   │       └── application.properties
│   └── test/
│       └── kotlin/
│           └── dolmeangi/kotlin/
│               ├── integration/
│               ├── property/
│               └── benchmark/
├── docs/
│   ├── architecture.md
│   ├── api.md
│   └── design-decisions.md
├── build.gradle.kts
├── settings.gradle.kts
└── CLAUDE.md
```

