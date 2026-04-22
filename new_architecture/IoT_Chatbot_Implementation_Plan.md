# IoT Chatbot — Step-by-Step Implementation Plan
## Architecture v4.0 | Current State → Target State

> **Audience:** Engineering team (Backend, DevOps, DBA, QA, TB Admin)
> **Reference Architecture:** IoT_Chatbot_Architecture_v4.md
> **Platform Operator:** Seple Novaedge Pvt. Ltd. (single deployment)
> **Team Size:** 3 people | **Total Estimated Time:** 7–8 weeks

---

## How to Read This Document

Each step shows:
- **Current State** — what exists in the codebase today (based on the live ThingsBoard-Bot repo)
- **Code Reference** — the specific file/class that is affected
- **What to Build** — the exact change required
- **State After** — what will be true when the step is complete

Steps are grouped by priority: **P0 (Blockers) → P1 (Required) → P2 (Operational) → P3 (Scale)**.

---

## Hierarchy Model Quick Reference

Before reading the steps, understand the data model this plan implements:

```
Seple Novaedge (Single Platform Deployment)
    │
    ├── customer_id: BOI  →  HO → FGMO → ZO → Branch (device)
    ├── customer_id: BOB  →  HO → ZO → RO → Branch
    ├── customer_id: SBI  →  HO → LHO → ZO → RBO → Branch
    ├── customer_id: IOB  →  HO → RO → Branch
    └── customer_id: UCO  →  HO → ZO → Branch
```

Every node (HO, FGMO, ZO, RO, LHO, RBO, CO, and Branch) is a row in `hierarchy_nodes`. The depth and label names vary per customer — no hierarchy level is hardcoded. Branches are leaf nodes with a ThingsBoard device ID. The full ancestor path (e.g. `[ho_id, fgmo_id, zo_id]`) is pre-computed per branch and stored in `branch_ancestor_paths`, then cached in memory so the Lua script can update every ancestor's Redis counter in a single atomic call.

---

## Phase 0 — Pre-Work (Day 0, No Code)

### Step 0.1 — Submit ThingsBoard Support Requests

**Effort:** 0.5 days | **Who:** DevOps

**Current State**
The bot uses the TB Cloud PE REST API directly with a tenant admin JWT. No isolated Rule Engine mode, no custom MVEL error limits.

**What to Do**
Submit the following requests to ThingsBoard support **before any code is written**:

| Setting | Value | Why |
|---|---|---|
| Rule Engine mode | Isolated | Required for `metadata.tenantId` and `metadata.msgId` to be populated in TBEL |
| MVEL_MAX_ERRORS | 10 (from 3) | Prevents TBEL blacklisting on transient errors |
| MVEL_MAX_BLACKLIST_DURATION_SEC | 300s (from 60s) | Reduces downtime if script is blacklisted |
| TB_RE_HTTP_CLIENT_POOL_MAX_CONNECTIONS | 100 | Supports webhook burst from 6,000 devices |
| Customer transport rate limit | 2000:1, 60000:60 | Prevents throttling at scale |

**State After**
TB support tickets open. Confirmation that `metadata.msgId` is available as a UUID in isolated RE mode. This UUID is required for idempotency in Step 1.4.

---

### Step 0.2 — Set TB Device Attributes on All Devices

**Effort:** 1 day | **Who:** TB Admin

**Current State**
Devices in ThingsBoard have `device_name` and `device_type`. The attributes `customer_id` and `branch_node_id` do not exist. The current bot reads `device_name` as the branch identifier and has no concept of hierarchy.

```java
// BranchSnapshotMapper.java — current state
// customer_id, branch_node_id: NOT READ — fields don't exist on devices yet
String branchName = choose(raw, "branchName", "formattedBranchName", "device_name", "deviceName");
```

**What to Do**
Set the following **Server-Scope attributes** on every device using TB UI or bulk import CSV:

| Attribute | Example Value | Required For |
|---|---|---|
| `customer_id` | `BOI` | Customer isolation in every layer |
| `branch_node_id` | `node_boi_br042` | Links the TB device to its row in `hierarchy_nodes` |
| `branch_name` | `BALLY BAZAR` | Human-readable display name |

Also set device inactivity timeout = 600s on every Device Profile (TB UI, not per-device attribute).

**State After**
All devices carry `customer_id` and `branch_node_id`. The TBEL script (Step 1.2) can now include these in every webhook payload. The Lua script (Step 1.5) can use them to route counter updates to the correct hierarchy node.

---

## Phase 1 — P0 Blockers (~4 Weeks)

### Step 1.1 — Add TimescaleDB with Hierarchy Schema

**Effort:** 3 days | **Who:** Backend + DBA

**Current State**
The project uses an **in-memory H2 database** only. There is no persistent event store, no hierarchy table, and no ancestor path table. All state lives in `UserDataService`'s `ConcurrentHashMap` and ThingsBoard.

```properties
# application.properties — current state
spring.datasource.url=jdbc:h2:mem:thingsboard_bot_db;DB_CLOSE_DELAY=-1
spring.datasource.driver-class-name=org.h2.Driver
```

**What to Build**

1. Add PostgreSQL dependency to `pom.xml`:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

2. Create `application-prod.properties`:
```properties
spring.datasource.url=jdbc:postgresql://timescaledb:5432/iot_platform
spring.datasource.username=${DB_USER}
spring.datasource.password=${DB_PASSWORD}
spring.datasource.driver-class-name=org.postgresql.Driver
spring.jpa.hibernate.ddl-auto=validate
```

3. Execute full schema DDL (run once by DBA — see Architecture Section 4.2 for complete SQL). Key tables:

```sql
-- Hierarchy node tree
CREATE TABLE hierarchy_nodes (
    node_id         VARCHAR(128) NOT NULL PRIMARY KEY,
    customer_id     VARCHAR(64)  NOT NULL,
    parent_id       VARCHAR(128) REFERENCES hierarchy_nodes(node_id),
    node_type_label VARCHAR(64)  NOT NULL,  -- "HO","FGMO","ZO","RO","LHO","RBO","CO"
    node_level      INT          NOT NULL,  -- 1=HO, 2=first intermediate, etc.
    display_name    VARCHAR(256) NOT NULL,
    is_leaf         BOOLEAN      NOT NULL DEFAULT FALSE,
    tb_device_id    UUID                   -- leaf nodes only
);

-- Pre-computed ancestor paths (one row per branch)
CREATE TABLE branch_ancestor_paths (
    branch_node_id  VARCHAR(128)  NOT NULL PRIMARY KEY,
    customer_id     VARCHAR(64)   NOT NULL,
    ancestor_path   VARCHAR(128)[] NOT NULL,  -- [ho_id, ..., parent_id]
    path_depth      INT           NOT NULL
);

-- Device event log (TimescaleDB hypertable, partitioned by customer_id + event_time)
CREATE TABLE device_events (
    id              BIGSERIAL,
    customer_id     VARCHAR(64)  NOT NULL,
    branch_node_id  VARCHAR(128) NOT NULL,
    tb_message_id   UUID         NOT NULL UNIQUE,  -- idempotency key
    log_type        VARCHAR(64)  NOT NULL,
    field           VARCHAR(64)  NOT NULL,
    prev_value      VARCHAR(64),
    new_value       VARCHAR(64)  NOT NULL,
    event_time      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (customer_id, event_time, id)
);
SELECT create_hypertable('device_events', 'event_time',
    partitioning_column => 'customer_id', number_partitions => 4);
```

4. Create JPA entities: `HierarchyNode.java`, `BranchAncestorPath.java`, `DeviceEvent.java` and their Spring Data repositories.

**State After**
TimescaleDB is running. All three tables exist with correct indices, retention policy (2 years), and compression policy (30 days). Spring Boot can read/write to all three. H2 remains active for local dev profiles.

---

### Step 1.2 — Load All Customer Hierarchy Trees

**Effort:** 2 days | **Who:** TB Admin + DBA

**Current State**
No hierarchy data exists anywhere. The `hierarchy_nodes` and `branch_ancestor_paths` tables are empty (created in Step 1.1). There is no concept of FGMO, ZO, RO, LHO, etc. in the current bot.

**What to Build**

Create a `HierarchyLoader` admin service that accepts a CSV/JSON import and populates `hierarchy_nodes`. For each of the 10 current customers, load their full tree:

```
BOI (4-level):  HO → FGMO → ZO → Branch
BOB (4-level):  HO → ZO → RO → Branch
SBI (5-level):  HO → LHO → ZO → RBO → Branch
CB  (4-level):  HO → CO → RO → Branch
IB  (4-level):  HO → ZO → RO → Branch
PNB (4-level):  HO → ZO → CO → Branch
UBI (4-level):  HO → ZO → RO → Branch
CBI (4-level):  HO → ZO → RO → Branch
IOB (3-level):  HO → RO → Branch
UCO (3-level):  HO → ZO → Branch
```

After loading, run the ancestor path computation:

```java
@Service
public class AncestorPathComputer {

    public void computeForCustomer(String customerId) {
        List<HierarchyNode> branches = nodeRepo.findByCustomerIdAndIsLeaf(customerId, true);
        for (HierarchyNode branch : branches) {
            List<String> path = new ArrayList<>();
            HierarchyNode current = nodeRepo.findById(branch.getParentId()).orElse(null);
            while (current != null) {
                path.add(0, current.getNodeId()); // prepend to get HO-first order
                current = current.getParentId() != null
                    ? nodeRepo.findById(current.getParentId()).orElse(null)
                    : null;
            }
            ancestorPathRepo.save(new BranchAncestorPath(
                branch.getNodeId(), customerId, path.toArray(new String[0]), path.size()
            ));
        }
    }
}
```

**State After**
All 10 customer hierarchy trees are in `hierarchy_nodes`. Every branch (leaf) node has a pre-computed ancestor path row in `branch_ancestor_paths`. The `AncestorPathCache` (Step 1.3) can now load from this data.

---

### Step 1.3 — Build the AncestorPathCache

**Effort:** 1 day | **Who:** Backend

**Current State**
No ancestor path cache exists. The current bot has no concept of hierarchy nodes — it only knows device names.

**What to Build**

Create `AncestorPathCache.java` — loaded at startup, refreshed via API:

```java
@Component
public class AncestorPathCache {

    // customer_id → (branch_node_id → ordered ancestor List)
    private final Map<String, Map<String, List<String>>> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void loadAll() {
        List<String> customerIds = customerRepo.findAllCustomerIds();
        customerIds.forEach(this::loadForCustomer);
        log.info("[HIERARCHY] Ancestor path cache loaded for {} customers", customerIds.size());
    }

    public void loadForCustomer(String customerId) {
        List<BranchAncestorPath> paths = ancestorPathRepo.findByCustomerId(customerId);
        Map<String, List<String>> customerCache = new ConcurrentHashMap<>();
        for (BranchAncestorPath path : paths) {
            customerCache.put(path.getBranchNodeId(), Arrays.asList(path.getAncestorPath()));
        }
        cache.put(customerId, customerCache);
        log.info("[HIERARCHY] Loaded {} branch paths for customer={}", customerCache.size(), customerId);
    }

    public List<String> getAncestors(String customerId, String branchNodeId) {
        Map<String, List<String>> customerPaths = cache.get(customerId);
        if (customerPaths == null) return Collections.emptyList();
        return customerPaths.getOrDefault(branchNodeId, Collections.emptyList());
    }
}
```

Also expose a reload endpoint:
```java
@PostMapping("/actuator/hierarchy/reload")
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<Void> reloadHierarchy(@RequestParam String customer) {
    ancestorPathComputer.computeForCustomer(customer);
    ancestorPathCache.loadForCustomer(customer);
    return ResponseEntity.ok().build();
}
```

**State After**
On Spring Boot startup, the ancestor path for every branch of every customer is pre-loaded into memory. Event consumers call `cache.getAncestors(customerId, branchNodeId)` and get back the full path in < 1ms — no DB query during event processing.

---

### Step 1.4 — Rewrite the TB Rule Chain + TBEL Scripts

**Effort:** 3 days | **Who:** Backend + TB Admin

**Current State**
The current bot does **not use a TB Rule Chain at all**. It polls ThingsBoard via REST API on a 5-minute schedule (`UserDataService.fetchUserDevicesData()`). There is no webhook receiver, no Kafka event pipeline, and no event-driven architecture.

```java
// UserDataService.java — current state: pure REST polling, no webhook, no Rule Chain
private List<Map<String, Object>> fetchUserDevicesData(String userToken) {
    List<Map<String, String>> devices = userTbClient.getUserDevices(userToken);
    for (Map<String, String> device : devices) {
        deviceData.putAll(userTbClient.getTelemetry(userToken, deviceId));
    }
    return allDevicesData; // 5-minute TTL cache — no event-driven updates
}
```

**What to Build**

Create **3 Rule Chains** in TB:

**Chain 1 — State Change Detector:**
- Filter node: only pass if value has actually changed (compare vs last known state via TBEL checkpoint)
- Output: route to Chain 2

**Chain 2 — Payload Formatter (TBEL script):**
```javascript
// Node 3 TBEL: format event payload
msg.tb_message_id  = metadata.msgId;       // UUID — globally unique
msg.customer_id    = metadata.customerId;  // from device attribute (Step 0.2)
msg.branch_node_id = metadata.branchNodeId; // from device attribute (Step 0.2)
msg.log_type       = determineLogType(msgType, msg);
msg.field          = mapLogTypeToField(msg.log_type);
msg.prev_value     = metadata.prevValue;
msg.new_value      = msg[msg.field];
msg.event_time     = metadata.ts;
// Remove old timestamp-based idempotency key — no longer used
```

**Chain 3 — Webhook Dispatcher:**
- REST API Call node: `POST /webhooks/tb`
- Header: `X-HMAC-SHA256: {computed_signature}`
- Retry strategy: RETRY_ALL, 3 retries, 5s backoff

**State After**
Every device state change in TB triggers a webhook HTTP POST to Spring Boot. Payload includes `tb_message_id` (UUID), `customer_id`, and `branch_node_id`. The REST polling approach (`fetchUserDevicesData`) is **no longer the primary data source** — it becomes a cold-start fallback only.

---

### Step 1.5 — Build the Webhook Receiver

**Effort:** 2 days | **Who:** Backend

**Current State**
No webhook receiver exists. Spring Boot only exposes `/api/v1/chat/ask` (chat) and `/api/v1/data/full` (data dump). All data flows via REST polling, not push.

**What to Build**

Create `WebhookController.java`:

```java
@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private final BlockingDeque<TbEventPayload> buffer = new LinkedBlockingDeque<>(10_000);

    @PostMapping("/tb")
    public ResponseEntity<Void> receive(
            @RequestHeader("X-HMAC-SHA256") String hmac,
            @RequestBody String rawBody) {

        // Step 1: Validate HMAC (< 0.5ms)
        if (!hmacValidator.isValid(rawBody, hmac)) {
            return ResponseEntity.status(401).build();
        }

        // Step 2: Push to in-memory buffer (non-blocking)
        TbEventPayload event = objectMapper.readValue(rawBody, TbEventPayload.class);
        buffer.offer(event);

        // Step 3: Return 200 immediately (< 5ms total)
        return ResponseEntity.ok().build();

        // Background thread drains buffer → RabbitMQ customer queue
    }

    @PreDestroy
    public void drainOnShutdown() {
        drainBuffer(Duration.ofSeconds(5)); // JVM shutdown hook
    }
}
```

`TbEventPayload` must include: `tb_message_id`, `customer_id`, `branch_node_id`, `log_type`, `field`, `prev_value`, `new_value`, `event_time`.

**State After**
Spring Boot accepts webhook POSTs from TB Rule Engine. HMAC validated. Events buffered in-memory and dispatched to `iot.{customer_id}.events` RabbitMQ queue asynchronously. TB receives HTTP 200 in < 5ms.

---

### Step 1.6 — Build the Event Consumer: TimescaleDB FIRST + Ancestor Path + Lua

**Effort:** 4 days | **Who:** Backend

**Current State**
No event consumer exists. `ChatService.java` reads directly from `UserDataService` which polls ThingsBoard REST. There is no RabbitMQ consumer, no Lua script execution, and no write to any persistent store.

```java
// ChatService.java — current state: reads from REST poll cache
List<BranchSnapshot> snapshots = userDataService.getUserBranchSnapshots(userToken);
// snapshots come from: ThingsBoard REST API → BranchSnapshotMapper (5-min cache)
```

**What to Build**

Create `EventConsumer.java`:

```java
@RabbitListener(queues = "#{customerQueueNames}")
public void consume(TbEventPayload event) {

    String customerId    = event.getCustomerId();
    String branchNodeId  = event.getBranchNodeId();

    // Step 1: TimescaleDB idempotency — UNIQUE constraint on tb_message_id
    try {
        deviceEventRepository.save(toEntity(event));
    } catch (DataIntegrityViolationException e) {
        log.info("[IDEM] Duplicate {}, discarding.", event.getTbMessageId());
        return; // ACK — already processed
    }

    // Step 2: Redis secondary idempotency check (fast path during replay)
    String idemKey = customerId + ":idem:" + event.getTbMessageId();
    if (!redis.opsForValue().setIfAbsent(idemKey, "1", Duration.ofHours(24))) {
        return;
    }

    // Step 3: Load ancestor path from cache (< 1ms, no DB call)
    List<String> ancestorPath = ancestorPathCache.getAncestors(customerId, branchNodeId);

    // Step 4: Execute Lua script — atomic update at branch + all ancestor nodes + global
    luaScriptExecutor.execute(
        customerId,
        branchNodeId,
        event.getField(),
        event.getNewValue(),
        event.getPrevValue(),
        event.getEventTime(),
        ancestorPath   // variable-length: 2 nodes for IOB, 4 nodes for SBI, etc.
    );
    // RabbitMQ auto-ACKs if no exception thrown
}
```

**Mandatory write order:**
1. TimescaleDB INSERT (system of record)
2. Redis Lua (serving layer)
3. RabbitMQ ACK

**State After**
Every webhook event is durably written to TimescaleDB **before** Redis is updated. The Lua script correctly increments counters at every ancestor node level regardless of how deep the customer's hierarchy is. Duplicate events (same `tb_message_id`) are silently discarded.

---

### Step 1.7 — Build the Dynamic Lua Script

**Effort:** 2 days | **Who:** Backend

**Current State**
No Lua script exists. Redis is not used at all in the current bot. `CacheConfig.java` sets up a simple `ConcurrentMapCacheManager` for device snapshot caching — not Redis, just JVM memory.

```java
// CacheConfig.java — current state: no Redis, pure JVM cache
@Bean
public CacheManager cacheManager() {
    return new ConcurrentMapCacheManager("device-data", "critical-data");
}
```

**What to Build**

Create `lua/update_counters.lua` (see Architecture Section 2.5 for full script). Key design points:

- `ARGV[1]` = `customer_id`
- `ARGV[2]` = `branch_node_id`
- `ARGV[3]` = `field`, `ARGV[4]` = `new_value`, `ARGV[5]` = `prev_value`, `ARGV[6]` = epoch
- `ARGV[7..N]` = ancestor node_ids (variable length — 2 for IOB, 4 for SBI, etc.)
- Updates `{customer_id}:branch:state:{branch_node_id}` hash
- Updates `{customer_id}:global:counters` hash
- Loops over `ARGV[7..N]` and updates `{customer_id}:node:counters:{node_id}` for every ancestor

Wire `LuaScriptExecutor.java` to load this script via `RedisScript<String>` and call it with the correct ARGV list built from the ancestor path.

**State After**
All Redis keys are prefixed with `customer_id`. Counter updates are atomic across the full ancestor chain — one Lua call handles a 3-level (IOB) and a 5-level (SBI) hierarchy equally correctly. BOI and BOB data never collide.

---

### Step 1.8 — Update ChatService to Read from Redis

**Effort:** 2 days | **Who:** Backend

**Current State**
`ChatService.java` reads branch snapshots from `UserDataService.getUserBranchSnapshots()`, which polls ThingsBoard REST API. All query latency is tied to TB API availability and the 5-minute cache TTL.

```java
// ChatService.java — current state
List<BranchSnapshot> snapshots = userDataService.getUserBranchSnapshots(userToken);
// This calls: ThingsBoard REST → getAttributes() + getTelemetry() → BranchSnapshotMapper
```

**What to Build**

Create `RedisSnapshotReader.java`:

```java
@Service
public class RedisSnapshotReader {

    public BranchSnapshot readBranch(String customerId, String branchNodeId) {
        String key = customerId + ":branch:state:" + branchNodeId;
        Map<Object, Object> hash = redis.opsForHash().entries(key);
        return branchHashMapper.fromRedisHash(branchNodeId, hash);
    }

    public List<BranchSnapshot> readAllBranches(String customerId) {
        // leaf node_ids for this customer are in the hierarchy cache
        List<String> branchIds = hierarchyCache.getLeafNodeIds(customerId);
        return branchIds.stream()
            .map(id -> readBranch(customerId, id))
            .toList();
    }

    public Map<String, Long> readNodeCounters(String customerId, String nodeId) {
        String key = customerId + ":node:counters:" + nodeId;
        return redis.opsForHash().entries(key).entrySet().stream()
            .collect(Collectors.toMap(
                e -> e.getKey().toString(),
                e -> Long.parseLong(e.getValue().toString())
            ));
    }
}
```

Update `ChatService.answerQuestion()`:
```java
// NEW: extract customer_id from JWT, read from Redis (< 10ms)
String customerId = jwtContext.getCustomerId(userToken);
List<BranchSnapshot> snapshots = redisSnapshotReader.readAllBranches(customerId);
```

The existing `QueryIntentResolver`, `DeterministicAnswerService`, and `BranchSnapshotMapper` continue to work — only the data source changes from ThingsBoard HTTP to Redis.

**State After**
Chat queries read branch state from Redis in < 10ms. ThingsBoard REST API is **no longer in the hot query path**. The 5-minute polling cache (`UserDataService`) is retained as a cold-start fallback only.

---

## Phase 2 — P1 Required (~4 Weeks, Parallel with P0 Completion)

### Step 2.1 — Build the Replay Service

**Effort:** 3 days | **Who:** Backend

**Current State**
No replay mechanism exists. If the JVM restarts, `UserDataService` refetches from ThingsBoard REST. No event log exists to replay from (resolved in Step 1.1).

**What to Build**

Create `ReplayService.java`:

```java
@Service
public class ReplayService {

    public void replayForCustomer(String customerId, Instant from, Instant to) {
        log.info("[REPLAY] customer={} from={} to={}", customerId, from, to);

        redis.flushCustomerKeys(customerId);
        ancestorPathCache.loadForCustomer(customerId); // reload paths before replay

        deviceEventRepository.streamByCustomerAndTimeRange(customerId, from, to)
            .forEach(event -> {
                List<String> ancestorPath = ancestorPathCache.getAncestors(
                    customerId, event.getBranchNodeId()
                );
                luaScriptExecutor.execute(
                    customerId,
                    event.getBranchNodeId(),
                    event.getField(),
                    event.getNewValue(),
                    event.getPrevValue(),
                    event.getEventTime().getEpochSecond(),
                    ancestorPath
                );
            });

        log.info("[REPLAY] Complete for customer={}", customerId);
    }
}
```

Expose via secured actuator:
```java
@PostMapping("/actuator/replay")
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<Void> replay(
        @RequestParam String customer,
        @RequestParam Instant from,
        @RequestParam Instant to) {
    replayService.replayForCustomer(customer, from, to);
    return ResponseEntity.ok().build();
}
```

**State After**
If Redis is wiped, an admin POSTs to `/actuator/replay` and rebuilds complete Redis state — including correct counters at every hierarchy level for every customer — from TimescaleDB in under 10 minutes for 6,000 branches. Cold-start TB polling is no longer needed for recovery.

---

### Step 2.2 — Deploy RabbitMQ with Per-Customer Queues

**Effort:** 2 days | **Who:** DevOps

**Current State**
No RabbitMQ instance. The current bot is purely REST-pull. No message queue of any kind is in use.

**What to Build**

Deploy RabbitMQ 3.x (quorum, 3-node). Create one queue per customer at startup:

```java
@Bean
public List<Queue> customerQueues() {
    return customerRepo.findAllCustomerIds().stream()
        .map(id -> QueueBuilder.durable("iot." + id + ".events")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", "iot.dlx")
            .build())
        .toList();
}
```

**State After**
Each customer has a dedicated durable quorum queue `iot.{customer_id}.events` with DLQ. Adding a new customer = inserting a new `customer_id` record and creating one queue config entry. No code changes.

---

### Step 2.3 — Deploy Redis Sentinel (3-Node)

**Effort:** 2 days | **Who:** DevOps

**Current State**
No Redis instance. Branch state lives in `ConcurrentHashMap<String, CachedUserData>` inside `UserDataService.java` — pure JVM heap.

```java
// UserDataService.java — current state: no Redis, pure in-memory JVM cache
private final ConcurrentHashMap<String, CachedUserData> userCacheMap = new ConcurrentHashMap<>();
```

**What to Build**

Add Redis Sentinel:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

```properties
# application-prod.properties
spring.data.redis.sentinel.master=mymaster
spring.data.redis.sentinel.nodes=sentinel1:26379,sentinel2:26379,sentinel3:26379
spring.data.redis.password=${REDIS_PASSWORD}
```

Redis config: `appendonly yes`, `save 900 1`, `maxmemory-policy noeviction`.

**State After**
Redis Sentinel cluster (3-node) running with AOF + RDB persistence. Sentinel auto-promotes replica on primary failure. Spring Boot connects via Sentinel and is transparent to failover.

---

### Step 2.4 — Build the Rule-Based Query Router with Node-Name Resolution

**Effort:** 4 days | **Who:** Backend

**Current State**
`QueryIntentResolver.java` already does intent classification via keyword matching (e.g. `CCTV_STATUS`, `BATTERY_VOLTAGE`, `GATEWAY_STATUS`). `DeterministicAnswerService.java` already builds template-based answers. This is effectively a proto-router — but it reads from REST-polled snapshots, not Redis, and it has no concept of intermediate nodes (FGMO, ZO, RO, LHO, etc.).

```java
// QueryIntentResolver.java — current state: intent classification exists
// DeterministicAnswerService.java — current state: template answers exist
// BUT: no node-name resolution, no global count queries, no Redis direct reads
```

**What to Build**

1. **Node-Name Resolver** — built from `hierarchy_nodes` at startup:

```java
@Component
public class NodeNameResolver {
    // display_name → node_id index, scoped by customer_id
    private final Map<String, Map<String, String>> nameIndex = new ConcurrentHashMap<>();

    @PostConstruct
    public void build() {
        for (HierarchyNode node : nodeRepo.findAll()) {
            nameIndex
                .computeIfAbsent(node.getCustomerId(), k -> new ConcurrentHashMap<>())
                .put(node.getDisplayName().toUpperCase(), node.getNodeId());
        }
    }

    public Optional<String> resolve(String customerId, String displayName) {
        Map<String, String> customerIndex = nameIndex.get(customerId);
        if (customerIndex == null) return Optional.empty();
        return Optional.ofNullable(customerIndex.get(displayName.toUpperCase()));
    }
}
```

2. **Extend `QueryIntentResolver`** to handle global-scope patterns and node-scoped patterns:

```java
// NEW patterns to add to detectIntent():
if (hasGlobalMarkers(question) && question.contains("OFFLINE")) {
    return QueryIntent.GLOBAL_OFFLINE_COUNT;  // → read {customer_id}:global:counters
}
if (hasNodeNameInQuestion(question, customerId)) {
    return QueryIntent.NODE_COUNTER_QUERY;    // → read {customer_id}:node:counters:{node_id}
}
```

3. **Extend `DeterministicAnswerService`** to read directly from Redis for node counter queries:

```java
case GLOBAL_OFFLINE_COUNT -> {
    String key = customerId + ":global:counters";
    Object val = redis.opsForHash().get(key, "total_offline");
    yield "**" + val + " branches are currently OFFLINE across all " 
          + nodeNameResolver.getCustomerDisplayName(customerId) + " branches.**";
}
case NODE_COUNTER_QUERY -> {
    String nodeId = nodeNameResolver.resolve(customerId, query.getTargetNodeName()).orElse(null);
    if (nodeId == null) return null; // fallback to LLM
    String key = customerId + ":node:counters:" + nodeId;
    Map<Object, Object> counters = redis.opsForHash().entries(key);
    yield answerTemplateService.renderNodeCounters(query.getTargetNodeName(), counters);
}
```

**State After**
Simple count and status queries (estimated 60–70% of all queries) return in < 50ms from Redis with zero LLM API calls. Node-name resolution works for any label type (FGMO, ZO, RO, LHO, RBO, CO) — the same code path handles all of them.

---

### Step 2.5 — Add Customer JWT Auth to the Chatbot API

**Effort:** 3 days | **Who:** Backend

**Current State**
`ChatController.java` accepts an optional `X-TB-Token` header and passes it to `ChatService`. The token is used to call ThingsBoard's `/api/auth/user` to identify the user. There is no `customer_id` extraction, no node-level RBAC, and no Spring Security enforcement.

```java
// ChatController.java — current state
@PostMapping("/ask")
public ResponseEntity<ChatResponse> askQuestion(
        @RequestBody ChatRequest request,
        @RequestHeader(value = "X-TB-Token", required = false) String userToken) {
    // userToken passed directly to ThingsBoard REST — no customer_id enforcement
}
```

**What to Build**

1. Replace TB token passthrough with a platform-issued JWT containing `customer_id` and `allowed_node_ids`:

```java
@Component
public class JwtCustomerContext {
    public String extractCustomerId(String jwt) {
        Claims claims = Jwts.parserBuilder()
            .setSigningKey(jwtSigningKey).build()
            .parseClaimsJws(jwt).getBody();
        String customerId = claims.get("customer_id", String.class);
        if (customerId == null) throw new UnauthorizedException("No customer_id in JWT");
        return customerId;
    }

    public List<String> getAllowedNodeIds(String jwt) {
        // returns allowed_node_ids claim — empty list = access to all nodes for customer
        return claims.get("allowed_node_ids", List.class);
    }
}
```

2. Update `ChatService.answerQuestion()` to enforce customer scoping:
```java
String customerId = jwtContext.extractCustomerId(userToken);
// ALL Redis reads now prefixed: customerId + ":" + ...
List<BranchSnapshot> snapshots = redisSnapshotReader.readAllBranches(customerId);
```

3. Add Spring Security config: all `/api/v1/chat/**` endpoints require a valid JWT.

**State After**
Every chatbot API request is authenticated. `customer_id` from the JWT is prepended to every Redis key. A BOI user can never access `BOB:*` or `SBI:*` keys — enforced by the prefix, not by trust.

---

### Step 2.6 — Build the Reconciliation Job

**Effort:** 2 days | **Who:** Backend

**Current State**
`UserDataService.java` has a `@Scheduled` daily cache wipe at 1:00 AM IST. No drift detection, no counter accuracy check.

```java
// UserDataService.java — current state: only a daily memory wipe
@Scheduled(cron = "0 0 1 * * ?", zone = "Asia/Kolkata")
public void dailyCacheMemoryWipe() {
    userCacheMap.clear();
}
```

**What to Build**

Create `ReconciliationJob.java`:

```java
@Scheduled(fixedDelay = 15 * 60 * 1000)
public void reconcile() {
    for (String customerId : customerRepo.findAllCustomerIds()) {
        long redisOnline = Long.parseLong(
            redis.opsForHash().get(customerId + ":global:counters", "total_online").toString()
        );
        long dbOnline = deviceEventRepository.countCurrentOnlineForCustomer(customerId);

        if (Math.abs(redisOnline - dbOnline) > DRIFT_THRESHOLD) {
            log.warn("[RECONCILE] Drift detected: customer={} redis={} db={}",
                     customerId, redisOnline, dbOnline);
            meterRegistry.counter("reconcile.drift.detected", "customer", customerId).increment();
            // Trigger selective replay for last 1 hour
            replayService.replayForCustomer(customerId,
                Instant.now().minus(Duration.ofHours(1)), Instant.now());
        }
    }
}
```

**State After**
Every 15 minutes, Redis global counters are verified against TimescaleDB for every active customer. Drift is detected and auto-corrected via selective replay. Grafana alerts fire on `reconcile.drift.detected`.

---

## Phase 3 — P2 Operational (~2 Weeks After P1)

### Step 3.1 — TimescaleDB Row-Level Security

**Effort:** 1 day | **Who:** DBA

**What to Build**

```sql
ALTER TABLE device_events    ENABLE ROW LEVEL SECURITY;
ALTER TABLE hierarchy_nodes  ENABLE ROW LEVEL SECURITY;

CREATE POLICY customer_isolation_events ON device_events
    USING (customer_id = current_setting('app.current_customer'));

CREATE POLICY customer_isolation_hierarchy ON hierarchy_nodes
    USING (customer_id = current_setting('app.current_customer'));
```

Configure Spring Boot `ConnectionPreparer` to set `SET app.current_customer = '{customerId}'` on every JDBC connection checkout.

**State After**
Even if a repository query omits `WHERE customer_id = ?`, PostgreSQL RLS returns zero rows for the wrong customer. Cross-customer data leakage is impossible at the database layer for both event data and hierarchy data.

---

### Step 3.2 — Hierarchy Admin UI

**Effort:** 3 days | **Who:** Backend + Frontend

**Current State**
No admin UI exists for hierarchy management. Adding a new customer requires direct DB inserts.

**What to Build**

Create a simple admin REST API (with minimal frontend or Swagger UI access):

```java
@RestController
@RequestMapping("/api/v1/admin/hierarchy")
@PreAuthorize("hasRole('ADMIN')")
public class HierarchyAdminController {

    @PostMapping("/customer/{customerId}/import")
    public ResponseEntity<Void> importTree(
            @PathVariable String customerId,
            @RequestBody List<HierarchyNodeDto> nodes) {
        hierarchyLoader.importTree(customerId, nodes);
        ancestorPathComputer.computeForCustomer(customerId);
        ancestorPathCache.loadForCustomer(customerId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/customer/{customerId}/node")
    public ResponseEntity<HierarchyNodeDto> addNode(
            @PathVariable String customerId,
            @RequestBody HierarchyNodeDto node) {
        HierarchyNode saved = hierarchyService.addNode(customerId, node);
        ancestorPathComputer.recomputeSubtree(customerId, saved.getNodeId());
        ancestorPathCache.loadForCustomer(customerId);
        return ResponseEntity.ok(toDto(saved));
    }
}
```

**State After**
Operations team can add a new customer hierarchy or add a new intermediate node (e.g. a new RO under an existing ZO) without DBA involvement or application restart.

---

### Step 3.3 — Grafana Dashboard Extensions

**Effort:** 2 days | **Who:** DevOps

**Current State**
No Grafana dashboard. No Micrometer metrics beyond Spring Boot Actuator health.

**What to Build**

Add Micrometer metrics:

```java
// EventConsumer.java
meterRegistry.counter("events.consumed",       "customer", customerId).increment();
meterRegistry.counter("events.idem.skipped",   "customer", customerId).increment();
meterRegistry.timer("timescaledb.write.ms").record(dbWriteTime, MILLISECONDS);
meterRegistry.timer("lua.execute.ms").record(luaTime, MILLISECONDS);

// ReplayService.java
meterRegistry.counter("replay.events.processed", "customer", customerId).increment();

// QueryRouter
meterRegistry.counter("query.route", "type", "rule_based").increment();
meterRegistry.counter("query.route", "type", "llm").increment();
meterRegistry.timer("query.response.ms", "type", queryType).record(elapsed, MILLISECONDS);

// ReconciliationJob
meterRegistry.counter("reconcile.drift.detected", "customer", customerId).increment();
```

Grafana dashboard panels:
- Events consumed per second per customer
- TimescaleDB write latency p99
- Lua execution latency p99
- Idempotency skip rate (duplicate detection efficiency)
- Rule-based vs LLM query split (target: 60%+ rule-based)
- Replay events per second
- Reconciliation drift events per hour per customer
- Node counter accuracy (Redis vs TimescaleDB spot-check)

**State After**
Full observability across event pipeline, hierarchy correctness, Redis health, and chatbot query routing. On-call team can diagnose any issue from Grafana within minutes.

---

### Step 3.4 — Redis Security Hardening + Secrets in Vault

**Effort:** 2 days | **Who:** DevOps

**What to Build**

```
# redis.conf additions
requirepass ${REDIS_PASSWORD}
tls-port 6380
rename-command FLUSHALL ""
rename-command FLUSHDB  ""
rename-command DEBUG    ""
rename-command KEYS     ""
```

Redis ACL per service role (note: chatbot API reads only its own customer namespace):
```
ACL SETUSER webhook-receiver on >pwd ~* +SET +LPUSH
ACL SETUSER event-consumer   on >pwd ~* +HSET +HINCRBY +HGET +SET +EVAL
ACL SETUSER chatbot-api      on >pwd ~{customer_id}:* +HGET +HGETALL +SMEMBERS
ACL SETUSER replay-service   on >pwd ~* +HSET +HINCRBY +DEL +HGET +SMEMBERS +EVAL
```

Store all secrets (Redis password, HMAC key, DB password, JWT signing key) in HashiCorp Vault. Rotate quarterly.

**State After**
Redis is TLS-encrypted, per-service ACL controlled, and has dangerous commands disabled. All secrets in Vault — nothing sensitive in config files or Git.

---

## Phase 4 — P3 Scale (Milestone-Triggered)

### Step 4.1 — Load Test at 6,000-Device Scale

**Effort:** 3 days | **Who:** QA

**Trigger:** Before going above 1,000 branches in production.

**Pass Criteria** (from Architecture Section 13.3):

| Metric | Pass Threshold |
|---|---|
| 6,000 devices @ 100 events/sec sustained | Zero event loss; TimescaleDB write latency p99 < 50ms |
| TimescaleDB write throughput | > 200 inserts/sec sustained |
| Rule-based query p99 | < 50ms |
| LLM query p99 | < 3 seconds |
| Full replay for 6,000 branches | < 10 minutes |
| Counter accuracy after 1M events | 100% Redis vs TimescaleDB match at every node level |
| 2 customers, different hierarchy depths | Zero cross-customer data; counters independent |

Also verify: SBI's 5-level hierarchy and IOB's 3-level hierarchy both produce correct node-level counter increments under sustained load.

---

### Step 4.2 — Kafka Migration (at 5,000 Branches)

**Effort:** 5 days | **Who:** Backend + DevOps

**Trigger:** Branch count crosses 5,000.

Replace RabbitMQ with Kafka (3 brokers, 6 partitions, partitioned by `customer_id:branch_node_id` for ordering):

1. Add `spring-kafka`; remove `spring-boot-starter-amqp`
2. Replace `@RabbitListener` with `@KafkaListener` in `EventConsumer.java`
3. Partition key: `customerId + ":" + branchNodeId` — preserves per-device ordering
4. Topic: `iot.events` with 6 partitions (or per-customer topics at enterprise scale)

**State After**
Kafka handles the event ingestion pipeline. No application logic changes — only messaging infrastructure swapped. Consumer groups per customer maintain isolation.

---

### Step 4.3 — Redis Cluster Migration (at 5,000 Branches)

**Effort:** 5 days | **Who:** DevOps

**Trigger:** Redis memory exceeds 60% of `maxmemory` on Sentinel primary.

Migrate from Redis Sentinel to Redis Cluster (6 nodes, 3 primary + 3 replica). Use `{customer_id}` as hash tag in all Redis keys to ensure all keys for one customer land on the same slot, preserving Lua atomicity:

```
# All keys use {customer_id} as hash tag:
{BOI}:global:counters
{BOI}:branch:state:BR_042
{BOI}:node:counters:node_boi_fgmo_north
```

Update `application-prod.properties` for cluster mode. Lua scripts work unchanged because hash tag ensures all KEYS are on the same slot.

---

## Summary Table

| Step | What | Priority | Days | Who | Blocker? |
|---|---|---|---|---|---|
| 0.1 | Submit TB support requests | P0 | 0.5 | DevOps | YES |
| 0.2 | Set TB device attributes (`customer_id`, `branch_node_id`) | P0 | 1.0 | TB Admin | YES |
| 1.1 | TimescaleDB: `hierarchy_nodes` + `branch_ancestor_paths` + `device_events` hypertable | P0 | 3.0 | Backend + DBA | YES |
| 1.2 | Load all 10 customer hierarchy trees; compute ancestor paths | P0 | 2.0 | TB Admin + DBA | YES |
| 1.3 | `AncestorPathCache`: startup load + refresh API | P0 | 1.0 | Backend | YES |
| 1.4 | TB Rule Chain + TBEL scripts (3 chains, `customer_id`, `branch_node_id`, `msgId`) | P0 | 3.0 | Backend | YES |
| 1.5 | Webhook Receiver: HMAC, in-memory buffer, < 5ms ACK | P0 | 2.0 | Backend | YES |
| 1.6 | Event Consumer: idempotency + TimescaleDB FIRST + ancestor path + Lua | P0 | 4.0 | Backend | YES |
| 1.7 | Dynamic Lua script: variable-depth ancestor walk | P0 | 2.0 | Backend | YES |
| 1.8 | ChatService reads from Redis, not ThingsBoard REST | P0 | 2.0 | Backend | YES |
| 2.1 | Replay Service: customer scope + ancestor reload + admin JWT | P1 | 3.0 | Backend | Required |
| 2.2 | RabbitMQ: per-customer queues, DLQ, quorum 3-node | P1 | 2.0 | DevOps | Required |
| 2.3 | Redis Sentinel 3-node + AOF + noeviction | P1 | 2.0 | DevOps | Required |
| 2.4 | Rule-based router: node-name resolver + all patterns | P1 | 4.0 | Backend | Required |
| 2.5 | Customer JWT auth + Redis prefix enforcement + node RBAC | P1 | 3.0 | Backend | Required |
| 2.6 | Reconciliation job: drift detection + selective replay per customer | P1 | 2.0 | Backend | Required |
| 3.1 | TimescaleDB RLS on `device_events` and `hierarchy_nodes` | P2 | 1.0 | DBA | Before multi-customer |
| 3.2 | Hierarchy admin UI: import tree, add node, trigger path recompute | P2 | 3.0 | Backend + Frontend | Before new customer |
| 3.3 | Grafana: event pipeline, node counter, query routing, drift metrics | P2 | 2.0 | DevOps | Before 1,000 branches |
| 3.4 | Redis ACL + TLS + secrets in Vault | P2 | 2.0 | DevOps | Before 1,000 branches |
| 4.1 | Load test: 6,000-device scale pass criteria | P3 | 3.0 | QA | Before 5,000 branches |
| 4.2 | Kafka migration (replace RabbitMQ) | P3 | 5.0 | Backend + DevOps | At 5,000 branches |
| 4.3 | Redis Cluster migration (hash tag: `{customer_id}`) | P3 | 5.0 | DevOps | At 5,000 branches |

---

## Current vs Target State Summary

| Concern | Current State (Live Repo) | Target State (v4.0) |
|---|---|---|
| **Data ingestion** | REST polling every 5 min (`UserDataService`) | Webhook push from TB Rule Engine (event-driven) |
| **Event durability** | None — lost on restart | Every event in TimescaleDB before Redis write |
| **Hierarchy model** | None — flat device list only | Generic tree: `hierarchy_nodes` + `branch_ancestor_paths`; any depth, any label |
| **State store** | In-process `ConcurrentHashMap` | Redis Sentinel with `customer_id`-namespaced keys |
| **Counter updates** | None | Lua script walks full ancestor chain atomically (variable depth) |
| **Idempotency** | None | TB Message ID (UUID) in TimescaleDB UNIQUE constraint |
| **Replay / recovery** | Re-poll ThingsBoard REST | Replay Service reads TimescaleDB; rebuilds correct node counters in < 10 min |
| **Multi-customer** | Single-customer only (`X-TB-Token` header) | `customer_id` first-class in every layer; each customer has its own hierarchy |
| **New customer onboarding** | Code change required | Upload hierarchy tree + create queue config; zero code changes |
| **Query routing** | All queries: LLM or deterministic from REST snapshot | 60–70% rule-based (< 50ms, Redis); 30–40% LLM |
| **Query latency (simple)** | 600–1600ms (LLM) or ~500ms (REST snapshot) | **< 50ms** (direct Redis, customer + node scoped) |
| **Node-level queries** | Not possible (no hierarchy concept) | Any node at any level: FGMO, ZO, RO, LHO, RBO, CO — same query pattern |
| **Audit / compliance** | None | 2 years of queryable event history; node-level aggregation via ancestor path join |
| **Scalability** | ~50 branches (REST poll) | Phase 1: 1,000 \| Phase 2: 5,000 \| Phase 4: 50,000 |

---

*Implementation Plan v1.0 | References Architecture v4.0 | April 2026 | Seple Novaedge Pvt. Ltd. | Confidential*
