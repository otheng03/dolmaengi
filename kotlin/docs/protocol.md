# DKSP - Distributed KV Store Protocol (Version 1.0)

## Overview

DKSP is a simple, text-based protocol for communicating with the distributed transactional key-value store. Inspired by Redis RESP, it is designed to be:

- **Simple**: Easy to implement in any programming language
- **Human-readable**: Can be tested and debugged with telnet or netcat
- **Efficient**: Minimal overhead for parsing
- **Type-safe**: Clear distinction between different data types
- **Line-based**: Each command and response is a single line (CRLF-terminated)

## Protocol Specification

### Data Types

DKSP uses prefix characters to denote data types, followed by CRLF (`\r\n`) as line terminators.

| Type | Prefix | Description | Example |
|------|--------|-------------|---------|
| Status | `+` | Status messages | `+OK\r\n` |
| Error | `-` | Error messages | `-ERR Unknown command\r\n` |
| Integer | `:` | Signed 64-bit integers | `:1234567890\r\n` |
| String | (none) | Simple text values | `hello\r\n` |
| Null | `$-1` | Null value | `$-1\r\n` |

### Encoding Rules

#### Status
```
+<string>\r\n
```
- Used for success responses
- Cannot contain CR or LF characters
- Examples: `+OK\r\n`

#### Error
```
-<error-type> <message>\r\n
```
- Used for error responses
- Error type prefix helps clients handle errors programmatically
- Examples:
  - `-ERR Unknown command\r\n`
  - `-CONFLICT Write-write conflict on key 'user:123'\r\n`
  - `-NOTFOUND Transaction not found\r\n`

#### Integer
```
:<number>\r\n
```
- Signed 64-bit integer
- Used for transaction IDs
- Examples: `:0\r\n`, `:1234567890\r\n`, `:-1\r\n`

#### String
```
<text>\r\n
```
- Simple text string for keys and values
- Cannot contain CR or LF characters
- Whitespace is significant
- Used for keys and values in responses

#### Null
```
$-1\r\n
```
- Represents null/missing value
- Used when a key doesn't exist

## Command Protocol

### Sending Commands

Commands are sent as a command name followed by space-separated arguments:
```
<COMMAND> <arg1> <arg2> ...\r\n
```

- Command names are **case-insensitive** but conventionally uppercase
- Transaction IDs are prefixed with `:` (e.g., `:1001`)
- Keys and values are plain text strings
- Arguments are separated by single space characters
- Each command ends with CRLF (`\r\n`)

**Format Rules**:
- Transaction IDs: `:12345`
- Keys: alphanumeric + `:` `-` `_` `.` (no spaces)
- Values: any text without newlines (spaces allowed)

### Command Reference

#### BEGIN

Start a new transaction.

**Request**:
```
BEGIN\r\n
```

**Response (Success)**:
```
:1234567890\r\n
```
Returns the transaction ID as an integer.

**Example**:
```
Client: BEGIN\r\n
Server: :1001\r\n
```

---

#### COMMIT

Commit a transaction.

**Request**:
```
COMMIT :1234567890\r\n
```

**Response (Success)**:
```
+OK\r\n
```

**Response (Conflict)**:
```
-CONFLICT Write-write conflict on key 'counter'\r\n
```

**Response (Not Found)**:
```
-NOTFOUND Transaction not found\r\n
```

**Example**:
```
Client: COMMIT :1001\r\n
Server: +OK\r\n
```

---

#### ABORT

Abort a transaction.

**Request**:
```
ABORT :1234567890\r\n
```

**Response**:
```
+OK\r\n
```

**Example**:
```
Client: ABORT :1001\r\n
Server: +OK\r\n
```

---

#### GET

Read a value within a transaction.

**Request**:
```
GET :1234567890 user:alice\r\n
```

**Response (Key exists)**:
```
{'name': 'Alice', 'age': 30}\r\n
```

**Response (Key not found)**:
```
$-1\r\n
```

**Example**:
```
Client: GET :1001 user:alice\r\n
Server: {'name': 'Alice', 'age': 30}\r\n
```

---

#### PUT

Write a key-value pair within a transaction.

**Request**:
```
PUT :1234567890 counter 42\r\n
```

**Response**:
```
+OK\r\n
```

**Example**:
```
Client: PUT :1001 counter 42\r\n
Server: +OK\r\n
```

**Note**: Value extends from the third argument to the end of the line, so it can contain spaces:
```
Client: PUT :1001 user:name Alice Smith\r\n
Server: +OK\r\n
```

---

#### DELETE

Delete a key within a transaction.

**Request**:
```
DELETE :1234567890 user:alice\r\n
```

**Response**:
```
+OK\r\n
```

**Example**:
```
Client: DELETE :1001 user:alice\r\n
Server: +OK\r\n
```

---

## Error Codes

Standard error prefixes for programmatic error handling:

| Error Code | Description | Example |
|------------|-------------|---------|
| `ERR` | Generic error | `-ERR Unknown command 'FOOBAR'\r\n` |
| `CONFLICT` | Transaction conflict | `-CONFLICT Write-write conflict on key 'x'\r\n` |
| `NOTFOUND` | Transaction/resource not found | `-NOTFOUND Transaction not found\r\n` |
| `INVALID` | Invalid argument | `-INVALID Empty key not allowed\r\n` |
| `ABORTED` | Transaction already aborted | `-ABORTED Transaction was aborted\r\n` |
| `STORAGE` | Storage layer error | `-STORAGE Failed to write to WAL\r\n` |

## Complete Transaction Example

```
Client: BEGIN\r\n
Server: :1001\r\n

Client: GET :1001 counter\r\n
Server: 5\r\n

Client: PUT :1001 counter 6\r\n
Server: +OK\r\n

Client: COMMIT :1001\r\n
Server: +OK\r\n
```

## Pipelining

Clients MAY send multiple commands without waiting for responses (pipelining):

```
Client: BEGIN\r\n
(wait for response)

Server: :1001\r\n

Client: GET :1001 counter\r\n
Client: PUT :1001 counter 6\r\n
Client: COMMIT :1001\r\n

Server: 5\r\n
Server: +OK\r\n
Server: +OK\r\n
```

**Important**: Transaction IDs from BEGIN must be known before sending dependent commands. Best practice:
1. Send BEGIN and wait for the transaction ID response
2. Pipeline all subsequent commands using that transaction ID
3. This maximizes throughput while ensuring correctness

## Implementation Considerations

### Server Requirements

1. **Line buffering**: Server must buffer input until complete CRLF-terminated lines are received
2. **Token parsing**: Split command lines into tokens (command, txn_id, key, value)
3. **Connection handling**: One connection per client session
4. **Timeout**: Server may close idle connections after timeout period
5. **Max line size**: Enforce maximum line length to prevent DoS (e.g., 64KB)

### Client Libraries

A simple client library should provide:
1. Connection management (connect, disconnect, reconnect)
2. Command serialization (type-safe API to DKSP encoding)
3. Response parsing (DKSP decoding to native types)
4. Error handling (exception mapping from error codes)
5. Transaction context management

### Example Client API (Conceptual Kotlin)

```kotlin
interface DKSPClient {
    suspend fun connect(host: String, port: Int)
    suspend fun disconnect()

    suspend fun beginTransaction(): TransactionId
    suspend fun commit(txnId: TransactionId): CommitResult
    suspend fun abort(txnId: TransactionId)

    suspend fun get(txnId: TransactionId, key: String): String?
    suspend fun put(txnId: TransactionId, key: String, value: String)
    suspend fun delete(txnId: TransactionId, key: String)
}
```

## Future Extensions

### Planned Features

**Binary Data Support**:
When binary-safe storage is needed, we can introduce bulk strings:
```
$<length>\r\n<data>\r\n
```
This would allow storing arbitrary binary data, images, serialized objects, etc.

**Batch Operations**:
- `MGET` - Read multiple keys in one transaction
- `MPUT` - Write multiple key-value pairs atomically
- `SCAN` - Iterate over keys matching a pattern

**Cluster Operations**:
- `CLUSTER STATUS` - View cluster topology
- `CLUSTER NODES` - List all nodes

The current protocol focuses on simplicity and human-readability, making it easy to debug and test with basic tools like telnet.

## Security Considerations

**Current Version (1.0)**:
- No authentication (trusted network only)
- No encryption (use VPN or SSH tunnel)

**Future Versions**:
- AUTH command for password authentication

## Version History

- **1.0** (2025-11-09): Initial protocol specification
  - Five basic data types (Status, Error, Integer, String, Null)
  - Human-readable text-only protocol (no binary support yet)
  - Simple space-separated command format
  - Transaction lifecycle commands (BEGIN, COMMIT, ABORT)
  - Data operation commands (GET, PUT, DELETE)
  - Focus on simplicity and ease of debugging with telnet

---

**Next Steps**:
1. Implement server-side protocol parser
2. Implement client library in Kotlin
3. Add telnet-friendly debug mode
4. Performance benchmarking
