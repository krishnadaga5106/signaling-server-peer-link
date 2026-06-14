# Signaling Server PeerLink

**WebSocket signaling server for the [PeerLink](https://github.com/krishnadaga5106/peerlink) P2P file transfer application**

[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.0.0-6DB33F?logo=springboot)](https://spring.io/projects/spring-boot)
[![Redis](https://img.shields.io/badge/Redis-required-DC382D?logo=redis)](https://redis.io/)
[![Maven](https://img.shields.io/badge/Build-Maven-C71A36?logo=apachemaven)](https://maven.apache.org/)

This is the signaling server that bootstraps WebRTC peer-to-peer connections for PeerLink. Its sole responsibility is to relay SDP offers/answers and ICE candidates between two peers long enough for them to establish a direct DataChannel connection. Once the channel is open, the server plays no further role — it never sees any file data or chat messages.

---

## Table of Contents

- [Role in the PeerLink Architecture](#role-in-the-peerlink-architecture)
- [How It Works](#how-it-works)
  - [Connection Lifecycle](#connection-lifecycle)
  - [Room Registry](#room-registry)
  - [Session Management](#session-management)
  - [Disconnect Handling](#disconnect-handling)
- [Project Structure](#project-structure)
- [API — Message Protocol](#api--message-protocol)
  - [Inbound Messages (Client → Server)](#inbound-messages-client--server)
  - [Outbound Messages (Server → Client)](#outbound-messages-server--client)
- [Tech Stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Configuration](#configuration)
- [Running Locally](#running-locally)
- [Deployment](#deployment)
- [Design Decisions](#design-decisions)

---

## Role in the PeerLink Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                     SIGNALING PHASE ONLY                         │
│                                                                  │
│  PeerLink CLI (A)          THIS SERVER          PeerLink CLI (B) │
│       │                        │                       │         │
│       │── CREATE ─────────────►│                       │         │
│       │◄─ JOINED (roomCode) ───│                       │         │
│       │                        │◄──── JOIN ────────────│         │
│       │◄─ PEER_JOIN ───────────│                       │         │
│       │                        │──── JOINED ──────────►│         │
│       │── OFFER (SDP) ────────►│──────────────────────►│         │
│       │                        │◄──── ANSWER (SDP) ────│         │
│       │◄─ ANSWER ──────────────│                       │         │
│       │── ICE candidate ──────►│──────────────────────►│         │
│       │◄─ ICE candidate ───────│◄──────────────────────│         │
│       │                        │                       │         │
│       │◄══════ RTCDataChannel OPEN — server now idle ═►│         │
└──────────────────────────────────────────────────────────────────┘
```

After the `RTCDataChannel` opens, both peers close their WebSocket connections to this server. All subsequent data — files, chat messages, control frames — flows directly between peers with no server involvement.

---

## How It Works

### Connection Lifecycle

1. **Peer A connects** via WebSocket to `/ws` and sends a `CREATE` message.
2. The server generates a unique 6-character alphanumeric room code, creates a `Room` entry in Redis, and responds with `JOINED` carrying the room code.
3. **Peer B connects**, sends a `JOIN` message with the room code.
4. The server validates the room, adds Peer B, responds with `JOINED`, and pushes a `PEER_JOIN` notification to Peer A.
5. Peer A sends an `OFFER` (SDP). The server looks up the other occupant of the room and forwards the message — it does not inspect the SDP payload.
6. Peer B replies with `ANSWER`. The server forwards it to Peer A.
7. Both peers exchange `ICE` candidates asynchronously. Each is forwarded to the other peer in the same room.
8. Once the DataChannel is established, both clients disconnect. `afterConnectionClosed` fires on the server, the session is removed from Redis, and if the room becomes empty it is deleted.

### Room Registry

`RoomRegistry` is the core stateful component. It maintains two data structures:

| Store | Key | Value | Purpose |
|---|---|---|---|
| Redis (`RedisTemplate<String, Room>`) | `rc:<roomCode>` | `Room` object (JSON) | Room state — up to two session IDs and peer names |
| Redis (`StringRedisTemplate`) | `sid:<sessionId>` | room code | Reverse lookup: session → room |
| In-memory (`ConcurrentHashMap`) | session ID | `WebSocketSession` | Live session references (not serialisable to Redis) |

Room state is stored in Redis so it survives server restarts and is ready for horizontal scaling. Live `WebSocketSession` objects cannot be serialised, so they remain in a local `ConcurrentHashMap` keyed by session ID. The two stores are kept consistent on every join and disconnect operation.

**Room code generation** uses a cryptographically simple but collision-safe approach: randomly sample from the 36-character set `[A-Z0-9]` until a 6-character code is produced that does not already exist as a Redis key. Collision probability at low room counts is negligible; at high concurrency the recursive retry handles the rare duplicate.

### Session Management

`joinRoom` is `synchronized` to prevent two peers from simultaneously winning the second slot in a room. Outside of join, reads are Redis-level atomic and the `ConcurrentHashMap` handles concurrent session lookups safely.

Signal forwarding in `manageSignal` performs three safety checks before forwarding any payload:

1. The sender's session maps to a room code in Redis.
2. The room code in the request matches that mapping (prevents cross-room injection).
3. The other peer's session exists and is `isOpen()`.

### Disconnect Handling

`afterConnectionClosed` is guaranteed to fire even on abnormal closures (network drop, process kill). The handler:

1. Looks up the room code for the disconnecting session.
2. Removes the session from the room in Redis and from the local session map.
3. Deletes the room from Redis entirely if both slots are now empty.
4. Notifies the remaining peer (if any) with an `INFO` message containing the departed peer's name.

This means rooms are self-cleaning — there is no background TTL job or orphan-room accumulation.

---

## Project Structure

```
signaling-server-peer-link/
└── src/
    ├── main/java/com/inhuman/serverpeerlink/
    │   ├── ServerPeerLinkApplication.java     # Spring Boot entry point
    │   │
    │   ├── Config/
    │   │   ├── WebSocketConfig.java           # Registers /ws endpoint; allows all origins
    │   │   └── RedisConfig.java               # RedisTemplate<String, Room> with Jackson serializer
    │   │
    │   ├── Services/
    │   │   ├── SignalingHandler.java           # TextWebSocketHandler — thin dispatcher
    │   │   ├── SignalingService.java           # Business logic: CREATE, JOIN, signal forwarding, close
    │   │   └── RoomRegistry.java              # Room lifecycle, session lookup, Redis/ConcurrentHashMap
    │   │
    │   └── Models/
    │       ├── WebRequest.java                # Inbound frame: {roomCode, username, messageType, message}
    │       ├── Response.java                  # Outbound frame: {responseType, message}
    │       ├── Room.java                      # Redis entity: roomCode, two sessionIds, two peer names
    │       ├── MessageType.java               # CREATE, JOIN, LEAVE, OFFER, ANSWER, ICE
    │       └── ResponseType.java              # JOINED, PEER_JOIN, OFFER, ANSWER, ICE, INFO, ERROR
    │
    └── test/java/com/inhuman/serverpeerlink/
        └── ServerPeerLinkApplicationTests.java
```

---

## API — Message Protocol

All messages are JSON-encoded text frames over WebSocket. The wire format is intentionally minimal.

### Inbound Messages (Client → Server)

```json
{
  "messageType": "<type>",
  "roomCode":    "<6-char code or null>",
  "username":    "<display name>",
  "message":     "<SDP string or ICE candidate or null>"
}
```

| `messageType` | `roomCode` required | `message` | Description |
|---|---|---|---|
| `CREATE` | No | — | Generate a new room and join it as Peer 1 |
| `JOIN` | Yes | — | Join an existing room as Peer 2 |
| `OFFER` | Yes | SDP offer string | Forward SDP offer to the other peer |
| `ANSWER` | Yes | SDP answer string | Forward SDP answer to the other peer |
| `ICE` | Yes | ICE candidate string | Forward ICE candidate to the other peer |
| `LEAVE` | Yes | — | Gracefully close connection and notify peer |

### Outbound Messages (Server → Client)

```json
{
  "responseType": "<type>",
  "message":      "<payload or null>"
}
```

| `responseType` | Sent to | `message` | Trigger |
|---|---|---|---|
| `JOINED` | Requesting peer | The room code | Successful `CREATE` or `JOIN` |
| `PEER_JOIN` | The waiting peer | `"<username> joined the room"` | Second peer joins the room |
| `OFFER` | Peer B | SDP offer string | Peer A sends `OFFER` |
| `ANSWER` | Peer A | SDP answer string | Peer B sends `ANSWER` |
| `ICE` | Other peer | ICE candidate string | Either peer sends `ICE` |
| `INFO` | Remaining peer | `"<username> left the room"` | A peer disconnects |
| `ERROR` | Requesting peer | Human-readable error text | Invalid room, full room, bad request |

**Error messages:**

| Scenario | Error text |
|---|---|
| `JOIN` with non-existent room code | `"Room does not exists"` |
| `JOIN` when room already has two peers | `"Room is Full"` |
| Signal sent for a room the sender isn't in | `"Room does not exists or Not in the room"` |
| Signal sent when other peer has disconnected | `"Room does not exists or No other peer"` |

---

## Tech Stack

| Concern | Library / Tool | Version |
|---|---|---|
| Language | Java | 21 |
| Framework | Spring Boot | 4.0.0 |
| WebSocket | Spring WebSocket (built-in) | — |
| Room state | Spring Data Redis | — |
| JSON | Jackson (Jackson 3 via Spring) | — |
| Boilerplate | Lombok | — |
| Build | Maven + Spring Boot Maven Plugin | — |

---

## Prerequisites

- **Java 21** or later
- **Maven 3.8+**
- **Redis** running and accessible (default: `localhost:6379`)

---

## Configuration

All configuration lives in `src/main/resources/application.properties`:

```properties
spring.application.name=ServerPeerLink

# Room code generation
roomCode.charSet=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789
roomCode.length=6

# Redis connection
spring.data.redis.host=localhost
spring.data.redis.port=6379
```

| Property | Default | Description |
|---|---|---|
| `roomCode.charSet` | `A-Z0-9` | Character pool for room code generation |
| `roomCode.length` | `6` | Length of generated room codes (36⁶ ≈ 2.1 billion combinations) |
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.data.redis.port` | `6379` | Redis port |

To override any property at runtime without modifying the file:

```bash
java -jar target/ServerPeerLink-0.0.1-SNAPSHOT.jar \
  --spring.data.redis.host=my-redis-host \
  --server.port=9090
```

---

## Running Locally

### 1. Start Redis

```bash
# With Docker (easiest)
docker run -d -p 6379:6379 redis:latest

# Or via your package manager (macOS)
brew services start redis

# Or directly
redis-server
```

### 2. Clone and build

```bash
git clone https://github.com/krishnadaga5106/signaling-server-peer-link.git
cd signaling-server-peer-link

mvn clean package -DskipTests
```

### 3. Run the server

```bash
java -jar target/ServerPeerLink-0.0.1-SNAPSHOT.jar
```

The server starts on port **8080** by default. PeerLink clients connect to:

```
ws://<host>:8080/ws
```

### Verifying the server is up

```bash
# Should return HTTP 200 (Spring Boot actuator or default Whitelabel page)
curl -I http://localhost:8080

# Or check with a WebSocket client (wscat)
npx wscat -c ws://localhost:8080/ws
```

---

## Deployment

The server is stateless at the application layer (all room and session-to-room mappings live in Redis), which makes it straightforward to run behind a load balancer.

**Important:** `WebSocketSession` objects are held in a local `ConcurrentHashMap` inside `RoomRegistry`. This means sessions are node-local — a load balancer must use **sticky sessions** (IP hash or session cookie affinity) to ensure that both peers in a room hit the same server instance. Alternatively, replace the in-memory session map with a shared session store if fully stateless horizontal scaling is required.

### Running behind a reverse proxy (nginx example)

```nginx
location /ws {
    proxy_pass         http://localhost:8080;
    proxy_http_version 1.1;
    proxy_set_header   Upgrade $http_upgrade;
    proxy_set_header   Connection "upgrade";
    proxy_set_header   Host $host;
    proxy_read_timeout 3600s;
}
```

---

## Design Decisions

**Why Redis for room state?**
Storing `Room` objects in Redis instead of a local `HashMap` means the server can restart without orphaning rooms that were created milliseconds before the restart. It also provides a clear path to horizontal scaling with sticky sessions.

**Why `ConcurrentHashMap` for sessions alongside Redis?**
`WebSocketSession` is a live network object — it holds an open socket and cannot be serialised. Redis stores the mappings (session ID → room code, room code → session IDs); the `ConcurrentHashMap` stores the actual sessions. This hybrid gives durability for metadata and correctness for live object access.

**Why not inspect SDP or ICE payloads?**
The server treats `OFFER`, `ANSWER`, and `ICE` messages as opaque strings. It validates that the sender is in the room and that the recipient exists, then forwards verbatim. This keeps the server completely media-agnostic — it works for any WebRTC application, not just PeerLink.

**Why `synchronized` only on `joinRoom`?**
The critical section is the two-slot check: read the room, see if a slot is free, write the updated room. Without synchronisation, two peers could simultaneously read an empty room and both believe they are Peer 1. `joinRoom` is the only method with this read-modify-write pattern on shared state; all other operations are either purely additive or protected by Redis atomicity.

**Why self-cleaning rooms on disconnect?**
Orphaned rooms with no peers waste Redis memory and pollute the room-code namespace. By deleting the room when the last peer disconnects, the server remains lean without needing a background expiry job. If a peer disconnects mid-signaling (before the other peer joined), the room is cleaned up immediately rather than expiring after a TTL.
