# Repository Guidelines

## Project Structure & Module Organization
Source lives in `src/main/kotlin/dolmeangi/kotlin` with subpackages for `common`, `kvstore`, `sequencer`, and request `handlers`; shared configs and assets sit under `src/main/resources`. Integration and unit tests mirror the main tree in `src/test/kotlin`, while build outputs and reports land in `build/`.

## Build, Test, and Development Commands
- `./gradlew build` compiles Kotlin 1.9 code, runs the test suite, and assembles distributables.
- `./gradlew runKVStore` launches the transactional KV server (defaults to port 10000); pass `--args="--port=... --replicaId=..."` for overrides.
- `./gradlew runSequencer --args="--initial=1 --port=10001"` starts the standalone sequencer for global IDs.
- `./gradlew test` runs Kotest + JUnit 5 suites; `./gradlew jacocoTestReport` regenerates coverage HTML in `build/reports/jacoco/test/html/index.html`.

## Coding Style & Naming Conventions
Follow Kotlin official style: 4-space indentation, braces on the same line, trailing commas for multiline literals, and immutable `val` defaults. Public functions should declare explicit return types and stay under ~30 lines; split protocol/state logic into dedicated classes under `common.*`. File names mirror the primary class (`SequencerMain.kt`, `MvccTransactionManager.kt`). Use IntelliJ’s Kotlin formatter or the official style guide to keep diffs consistent.

## Testing Guidelines
Prefer Kotest `DescribeSpec`/`ShouldSpec` styles with names like `SequencerServiceTest` describing behavior (`should allocate monotonically increasing ids`). Use coroutine test dispatchers from `kotlinx-coroutines-test` for suspending flows. Keep coverage at least at the current Jacoco baseline (see CI summary) and add regression tests whenever protocol messages or transaction semantics change.

## Commit & Pull Request Guidelines
Commits follow the existing imperative, sentence-case style (`Implement DKSP protocol and MVCC transaction manager`). Keep related changes squashed, reference issue IDs in the body, and describe observable effects. Pull requests must include: concise summary, testing evidence (`./gradlew test` output or screenshots for tooling), mention of protocol/port changes, and checklist items for docs or configuration updates. Request review from a domain owner before merge.

## Configuration & Ops Tips
Runtime ports default to 10000 (KV) and 10001 (sequencer); expose them via `--port` flags or container maps. When running both services locally, start the sequencer first so the KV store can fetch sequence numbers immediately. Persisted data currently stays in-memory; add external durability modules under `src/main/kotlin/dolmeangi/kotlin/storage` as they are introduced.
