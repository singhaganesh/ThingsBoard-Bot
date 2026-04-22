# ThingsBoard Bot v4.0 — Implementation Plan

## Project Overview

| Attribute | Value |
|-----------|-------|
| Project Name | ThingsBoard Bot (SAI - Smart Assistant for IoT) |
| Architecture Version | v4.0 |
| Target Platform | ThingsBoard Private Cloud PE |
| Platform Operator | Seple Novaedge Pvt. Ltd. |
| Target Scale | 100+ devices, 10+ customers |
| Implementation Duration | 5-6 weeks |

---

## Current State

| Aspect | Current | Target |
|--------|---------|--------|
| Data Source | ThingsBoard REST API polling | Webhook push |
| Storage | In-memory ConcurrentHashMap | Redis + TimescaleDB |
| Multi-customer | Not supported | Full isolation |
| Hierarchy | Not supported | Dynamic tree (any depth) |
| Query Speed | 500-1600ms | <50ms (Redis) |
| Durability | None | Event store (TimescaleDB) |

---

## Customer Hierarchy Summary

### 10 Customers with Different Hierarchy Depths

| Customer | Pattern | Levels | Structure |
|----------|---------|--------|-----------|
| BOI | `BOI-{branch}` | 4 | Client → HO → FGMO → ZO → Branch |
| BOB | `BOB-{branch}` | 4 | Client → HO → ZO → RO → Branch |
| SBI | `SBI-{branch}` | 5 | Client → HO → LHO → ZO → RBO → Branch |
| CB | `CB-{branch}` | 4 | Client → HO → CO → RO → Branch |
| IB | `IB-{branch}` | 4 | Client → HO → ZO → RO → Branch |
| PNB | `PNB-{branch}` | 4 | Client → HO → ZO → CO → Branch |
| UBI | `UBI-{branch}` | 4 | Client → HO → ZO → RO → Branch |
| CBI | `CBI-{branch}` | 4 | Client → HO → ZO → RO → Branch |
| IOB | `IOB-{branch}` | 3 | Client → HO → RO → Branch |
| UCO | `UCO-{branch}` | 3 | Client → HO → ZO → Branch |

### Customer Prefix List

```
BOI, BOB, SBI, CB, IB, PNB, UBI, CBI, IOB, UCO
```

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         THINGSBOARD PE (Cloud)                              │
│  [Devices] → [Rule Engine] → [Webhook POST]                                │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │ HTTP POST
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         EC2 - SPRING BOOT                                   │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  Webhook Controller                                                  │   │
│  │  - HMAC Validation                                                   │   │
│  │  - Event Parsing (device_name → customer_id)                        │   │
│  └─────────────────────────┬────────────────────────────────────────────┘   │
│                            │                                                 │
│  ┌─────────────────────────▼────────────────────────────────────────────┐   │
│  │  Event Consumer Service                                              │   │
│  │  1. Idempotency Check (TB Message ID)                              │   │
│  │  2. Write to TimescaleDB (System of Record)                        │   │
│  │  3. Load Ancestor Path from Cache                                   │   │
│  │  4. Execute Lua Script → Redis                                      │   │
│  │  5. ACK to Queue                                                    │   │
│  └─────────────────────────┬────────────────────────────────────────────┘   │
│                            │                                                 │
│         ┌──────────────────┴──────────────────┐                              │
│         ▼                                     ▼                              │
│  ┌─────────────────────┐           ┌─────────────────────┐                 │
│  │   Redis             │           │  TimescaleDB        │                 │
│  │   (Caching)        │           │  (Event Store)      │                 │
│  │                     │           │                     │                 │
│  │ {c}:global:counters│           │  device_events      │                 │
│  │ {c}:node:counters  │           │  hierarchy_nodes    │                 │
│  │ {c}:device:state   │           │  branch_ancestor_   │                 │
│  │                     │           │       paths          │                 │
│  └─────────────────────┘           └─────────────────────┘                 │
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  Query Layer                                                         │   │
│  │  - Rule-Based Router (direct Redis reads)                          │   │
│  │  - Deterministic Answer Service                                     │   │
│  │  - OpenAI Client (LLM for complex queries)                        │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  Authentication Layer                                                │   │
│  │  - JWT with customer_id                                            │   │
│  │  - Redis key isolation (prefixed by customer)                      │   │
│  │  - RBAC (node-level access)                                        │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Implementation Phases

### Phase 1: Database & Schema (Week 1)

#### 1.1 TimescaleDB Schema

```sql
-- ============================================================
-- Customer table
-- ============================================================
CREATE TABLE customers (
    customer_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(256) NOT NULL,
    display_name VARCHAR(256),
    hierarchy_template VARCHAR(32),  -- "BOI_4LEVEL", "SBI_5LEVEL", etc.
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- Hierarchy node tree — stores every customer's org structure
-- ============================================================
CREATE TABLE hierarchy_nodes (
    node_id VARCHAR(128) NOT NULL PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL REFERENCES customers(customer_id),
    parent_id VARCHAR(128),
    node_type VARCHAR(32) NOT NULL,  -- "CLIENT","HO","FGMO","ZO","RO","LHO","RBO","CO","BRANCH"
    node_level INT NOT NULL,          -- 1=CLIENT, 2=HO, 3=first intermediate, etc.
    display_name VARCHAR(256) NOT NULL,
    is_leaf BOOLEAN NOT NULL DEFAULT FALSE,
    tb_device_id UUID,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX ON hierarchy_nodes (customer_id);
CREATE INDEX ON hierarchy_nodes (parent_id);
CREATE INDEX ON hierarchy_nodes (customer_id, is_leaf);

-- ============================================================
-- Pre-computed ancestor paths — one row per branch (leaf) node
-- ============================================================
CREATE TABLE branch_ancestor_paths (
    branch_node_id VARCHAR(128) NOT NULL PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    ancestor_path VARCHAR(128)[] NOT NULL,  -- ordered: [ho_id, intermediate..., parent_id]
    path_depth INT NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX ON branch_ancestor_paths (customer_id);

-- ============================================================
-- Device event log (TimescaleDB hypertable)
-- ============================================================
CREATE TABLE device_events (
    id BIGSERIAL,
    customer_id VARCHAR(64) NOT NULL,
    branch_node_id VARCHAR(128) NOT NULL,
    tb_message_id UUID,
    log_type VARCHAR(64),
    field VARCHAR(64),
    prev_value VARCHAR(64),
    new_value VARCHAR(64),
    event_time TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    raw_payload JSONB,
    PRIMARY KEY (customer_id, event_time, id)
);

SELECT create_hypertable('device_events', 'event_time',
    partitioning_column => 'customer_id',
    number_partitions => 4);

CREATE INDEX ON device_events (customer_id, branch_node_id, event_time DESC);
CREATE INDEX ON device_events (tb_message_id);
CREATE INDEX ON device_events USING GIN (raw_payload);

SELECT add_retention_policy('device_events', INTERVAL '2 years');
SELECT add_compression_policy('device_events', INTERVAL '30 days');
```

#### 1.2 Hierarchy Templates

| Customer | Template Name | Depth | Node Types |
|----------|--------------|-------|-----------|
| BOI | BOI_4LEVEL | 4 | CLIENT, HO, FGMO, ZO, BRANCH |
| BOB | BOB_4LEVEL | 4 | CLIENT, HO, ZO, RO, BRANCH |
| SBI | SBI_5LEVEL | 5 | CLIENT, HO, LHO, ZO, RBO, BRANCH |
| CB | CB_4LEVEL | 4 | CLIENT, HO, CO, RO, BRANCH |
| IB | IB_4LEVEL | 4 | CLIENT, HO, ZO, RO, BRANCH |
| PNB | PNB_4LEVEL | 4 | CLIENT, HO, ZO, CO, BRANCH |
| UBI | UBI_4LEVEL | 4 | CLIENT, HO, ZO, RO, BRANCH |
| CBI | CBI_4LEVEL | 4 | CLIENT, HO, ZO, RO, BRANCH |
| IOB | IOB_3LEVEL | 3 | CLIENT, HO, RO, BRANCH |
| UCO | UCO_3LEVEL | 3 | CLIENT, HO, ZO, BRANCH |

#### 1.3 Files to Create

| File | Path | Purpose |
|------|------|---------|
| Customer.java | src/main/java/.../entity/Customer.java | Customer entity |
| HierarchyNode.java | src/main/java/.../entity/HierarchyNode.java | Node entity |
| BranchAncestorPath.java | src/main/java/.../entity/BranchAncestorPath.java | Path entity |
| DeviceEvent.java | src/main/java/.../entity/DeviceEvent.java | Event entity |
| CustomerRepository.java | src/main/java/.../repository/CustomerRepository.java | Customer CRUD |
| HierarchyNodeRepository.java | src/main/java/.../repository/HierarchyNodeRepository.java | Node CRUD |
| BranchAncestorPathRepository.java | src/main/java/.../repository/BranchAncestorPathRepository.java | Path CRUD |
| DeviceEventRepository.java | src/main/java/.../repository/DeviceEventRepository.java | Event CRUD |

#### 1.4 Data Loading Strategy

Since only branch name is available in raw data:

```
Device Name: "BOI-PIPARIYA"
     │
     ▼
extractCustomer("BOI-PIPARIYA") → "BOI"
extractBranch("BOI-PIPARIYA") → "PIPARIYA"
     │
     ▼
Lookup: customer_id = "BOI"
        template = "BOI_4LEVEL"
        depth = 4
     │
     ▼
Generate node IDs:
  - node_boi_client
  - node_boi_ho
  - node_boi_fgmo (auto-assigned or from device attribute)
  - node_boi_zo (auto-assigned or from device attribute)
  - node_boi_pipariya (branch)
```

---

### Phase 2: Webhook & Event Pipeline (Week 2)

#### 2.1 Webhook Controller

```java
@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    @PostMapping("/thingsboard")
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "X-HMAC-SHA256", required = false) String hmac,
            @RequestBody String rawBody) {
        
        // Step 1: Validate HMAC (optional for dev)
        // if (!hmacValidator.isValid(rawBody, hmac)) {
        //     return ResponseEntity.status(401).build();
        // }
        
        // Step 2: Parse payload
        TbEventPayload event = parsePayload(rawBody);
        
        // Step 3: Extract customer from device name pattern
        String customerId = extractCustomerId(event.getDeviceName());
        String branchName = extractBranchName(event.getDeviceName());
        
        event.setCustomerId(customerId);
        event.setBranchName(branchName);
        
        // Step 4: Push to buffer or queue
        eventBuffer.offer(event);
        
        return ResponseEntity.ok().build();
    }
}
```

#### 2.2 Event Consumer Service

```java
@Service
public class EventConsumerService {

    @RabbitListener(queues = "iot.events")
    public void consume(TbEventPayload event) {
        
        // Step 1: Idempotency Check (TB Message ID)
        if (!idempotencyService.checkAndMark(event.getTbMessageId())) {
            log.info("[IDEM] Duplicate message: {}", event.getTbMessageId());
            return;
        }
        
        // Step 2: Write to TimescaleDB (System of Record)
        try {
            deviceEventRepository.save(toEntity(event));
        } catch (Exception e) {
            log.error("[DB] Failed to write event", e);
            return;
        }
        
        // Step 3: Resolve branch_node_id from cache
        String branchNodeId = resolveBranchNodeId(event);
        
        // Step 4: Load ancestor path
        List<String> ancestorPath = ancestorPathCache.getAncestors(
            event.getCustomerId(), branchNodeId
        );
        
        // Step 5: Execute Lua script → Redis
        luaScriptExecutor.execute(
            event.getCustomerId(),
            branchNodeId,
            event.getField(),
            event.getNewValue(),
            event.getPrevValue(),
            ancestorPath
        );
    }
}
```

#### 2.3 Files to Create

| File | Path | Purpose |
|------|------|---------|
| WebhookController.java | src/main/java/.../controller/WebhookController.java | Accept TB webhooks |
| TbEventPayload.java | src/main/java/.../model/TbEventPayload.java | Event DTO |
| EventConsumerService.java | src/main/java/.../service/EventConsumerService.java | Process events |
| IdempotencyService.java | src/main/java/.../service/IdempotencyService.java | UUID deduplication |
| EventParseService.java | src/main/java/.../service/EventParseService.java | Parse device name |
| AncestorPathCache.java | src/main/java/.../cache/AncestorPathCache.java | Ancestor path cache |
| LuaScriptService.java | src/main/java/.../service/LuaScriptService.java | Lua execution |

---

### Phase 3: Redis Integration (Week 2-3)

#### 3.1 Redis Key Design

```
Keys Pattern:
─────────────────────────────────────────────────────────────
{customer_id}:global:counters
    └── {total_online: N, total_offline: N, total_fault: N, ...}

{customer_id}:node:counters:{node_id}
    └── {total_online: N, total_offline: N, total_branches: N, ...}

{customer_id}:device:state:{device_id}
    └── {gateway_status: ONLINE, battery_voltage: 12.5, cctv: ON, ...}

{customer_id}:device:meta:{device_id}
    └── {customer_id: BOI, branch_node_id: node_boi_xxx, branch_name: xxx, ...}

{customer_id}:idem:{tb_message_id}
    └── TTL: 24 hours (idempotency check)
```

#### 3.2 Lua Script (update_counters.lua)

```lua
-- ARGV[1] = customer_id
-- ARGV[2] = branch_node_id
-- ARGV[3] = field
-- ARGV[4] = new_value
-- ARGV[5] = prev_value
-- ARGV[6] = epoch_seconds
-- ARGV[7..N] = ancestor node_ids (variable length)

local customer_id = ARGV[1]
local branch_id = ARGV[2]
local field = ARGV[3]
local new_val = ARGV[4]
local prev_val = ARGV[5]

-- 1. Update device state hash
local device_key = customer_id .. ":device:state:" .. branch_id
redis.call("HSET", device_key, field, new_val)
redis.call("HSET", device_key, "last_updated", ARGV[6])

-- 2. Update global counter
local global_key = customer_id .. ":global:counters"
if prev_val ~= "" and prev_val ~= new_val then
    redis.call("HINCRBY", global_key, "total_" .. prev_val, -1)
end
redis.call("HINCRBY", global_key, "total_" .. new_val, 1)

-- 3. Walk up the ancestor chain
for i = 7, #ARGV do
    local node_key = customer_id .. ":node:counters:" .. ARGV[i]
    if prev_val ~= "" and prev_val ~= new_val then
        redis.call("HINCRBY", node_key, "total_" .. prev_val, -1)
    end
    redis.call("HINCRBY", node_key, "total_" .. new_val, 1)
end

return "UPDATED"
```

#### 3.3 Files to Create

| File | Path | Purpose |
|------|------|---------|
| RedisConfig.java | src/main/java/.../config/RedisConfig.java | Redis connection |
| RedisCacheService.java | src/main/java/.../service/RedisCacheService.java | Cache operations |
| LuaScriptService.java | src/main/java/.../service/LuaScriptService.java | Lua execution |
| update_counters.lua | src/main/resources/lua/update_counters.lua | Atomic counter script |

---

### Phase 4: Query Layer (Week 3)

#### 4.1 Query Classification

```
User Query
    │
    ▼
┌─────────────────────────────┐
│  Query Router              │
│  - Pattern Matching        │
└─────────────────────────────┘
    │
    ├─► SIMPLE (Rule-Based) ──► Direct Redis Read ──► Answer
    │    Examples:
    │    - "how many online"
    │    - "branches in FGMO"
    │    - "status of PIPARIYA"
    │
    └─► COMPLEX (LLM) ──► Redis Context ──► OpenAI ──► Answer
         Examples:
         - "why is branch offline"
         - "compare FGMO vs ZO"
         - "summary of network health"
```

#### 4.2 Rule-Based Patterns

| Pattern | Redis Key | Example Answer |
|---------|----------|----------------|
| `how many * online` | `{c}:global:counters → total_online` | "5,847 branches are online" |
| `how many * offline` | `{c}:global:counters → total_offline` | "153 branches are offline" |
| `branches in {node}` | `{c}:node:counters:{id} → total_branches` | "FGMO NORTH has 48 branches" |
| `offline in {node}` | `{c}:node:counters:{id} → total_offline` | "3 offline in ZO KOLKATA" |
| `status of {branch}` | `{c}:device:state:{id}` | "PIPARIYA: ONLINE, Battery: 12.5V" |

#### 4.3 Files to Create/Modify

| File | Path | Purpose |
|------|------|---------|
| QueryRouterService.java | src/main/java/.../service/query/QueryRouterService.java | Route queries |
| NodeNameResolver.java | src/main/java/.../service/query/NodeNameResolver.java | Resolve node names |
| RedisQueryService.java | src/main/java/.../service/query/RedisQueryService.java | Direct Redis reads |
| QueryIntent.java | src/main/java/.../service/query/QueryIntent.java | (Existing - extend) |
| QueryIntentResolver.java | src/main/java/.../service/query/QueryIntentResolver.java | (Existing - extend) |
| DeterministicAnswerService.java | src/main/java/.../service/query/DeterministicAnswerService.java | (Existing - extend) |

---

### Phase 5: Multi-Customer Auth (Week 4)

#### 5.1 JWT Structure

```json
{
  "sub": "user@boi.co.in",
  "customer_id": "BOI",
  "allowed_nodes": [
    "node_boi_fgmo_north",
    "node_boi_fgmo_south",
    "node_boi_zo_east"
  ],
  "roles": ["USER", "VIEWER"],
  "exp": 1735689600
}
```

#### 5.2 Security Flow

```
User Request
    │
    ▼
JWT Token (X-TB-Token header)
    │
    ▼
JwtAuthenticationFilter
    │
    ├─► Validate JWT signature
    ├─► Extract customer_id
    ├─► Extract allowed_nodes
    │
    ▼
SecurityContext.setAuthentication()
    │
    ▼
Controller receives request
    │
    ├─► Get customer_id from SecurityContext
    ├─► Prefix all Redis keys: {customer_id}:...
    ├─► Validate node access (if specified)
    │
    ▼
Response
```

#### 5.3 Files to Create/Modify

| File | Path | Purpose |
|------|------|---------|
| JwtCustomerService.java | src/main/java/.../service/auth/JwtCustomerService.java | JWT parsing |
| SecurityConfig.java | src/main/java/.../config/SecurityConfig.java | Spring Security |
| JwtAuthenticationFilter.java | src/main/java/.../filter/JwtAuthenticationFilter.java | JWT filter |
| ChatController.java | src/main/java/.../controller/ChatController.java | (Modify - add auth) |
| ChatService.java | src/main/java/.../service/ChatService.java | (Modify - add customer isolation) |

---

## Configuration Files

### application-dev.properties

```properties
# ============================================================
# Database Configuration
# ============================================================
spring.datasource.url=jdbc:postgresql://localhost:5432/iot_platform
spring.datasource.username=iot_user
spring.datasource.password=iot_pass
spring.datasource.driver-class-name=org.postgresql.Driver

# ============================================================
# Redis Configuration
# ============================================================
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.password=

# ============================================================
# ThingsBoard Configuration
# ============================================================
iotchatbot.thingsboard.url=https://your-thingsboard-pe-url.com
iotchatbot.thingsboard.username=your_tb_username
iotchatbot.thingsboard.password=your_tb_password

# ============================================================
# OpenAI Configuration
# ============================================================
iotchatbot.openai.api-key=your_openai_api_key
iotchatbot.openai.model=gpt-4o-mini
iotchatbot.openai.max-tokens=1000

# ============================================================
# Chatbot Configuration
# ============================================================
iotchatbot.chatbot.max-context-tokens=10000
iotchatbot.chatbot.deterministic-answers-enabled=true
iotchatbot.chatbot.log-decision-metadata=true

# ============================================================
# Customer Configuration
# ============================================================
iotchatbot.customers.prefixes=BOI,BOB,SBI,CB,IB,PNB,UBI,CBI,IOB,UCO
iotchatbot.customers.default=BOI
```

### application-prod.properties

```properties
# Production settings with actual values
spring.datasource.url=jdbc:postgresql://X.X.X.X:5432/iot_platform
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}

spring.data.redis.host=${REDIS_HOST}
spring.data.redis.port=6379
spring.data.redis.password=${REDIS_PASSWORD}

iotchatbot.thingsboard.url=${TB_URL}
iotchatbot.thingsboard.username=${TB_USERNAME}
iotchatbot.thingsboard.password=${TB_PASSWORD}

iotchatbot.openai.api-key=${OPENAI_API_KEY}
```

---

## File Creation Summary

| Phase | Files to Create | Files to Modify | Total |
|-------|----------------|-----------------|-------|
| 1: Database | 8 | 0 | 8 |
| 2: Webhook | 7 | 0 | 7 |
| 3: Redis | 4 | 1 | 5 |
| 4: Query | 3 | 3 | 6 |
| 5: Auth | 3 | 2 | 5 |
| **Total** | **25** | **6** | **31** |

---

## Data Flow Examples

### Example 1: Device State Change Event

```
ThingsBoard Device: "BOI-PIPARIYA"
    │
    ▼ (Webhook POST)
WebhookController
    │
    ├─► deviceName = "BOI-PIPARIYA"
    ├─► extractCustomerId() → "BOI"
    ├─► extractBranchName() → "PIPARIYA"
    │
    ▼ (Event to Queue)
EventConsumerService
    │
    ├─► Write to TimescaleDB: device_events
    ├─► Lookup branch_node_id = "node_boi_pipariya"
    ├─► Get ancestor path = [node_boi_client, node_boi_ho, node_boi_fgmo_xxx, node_boi_zo_xxx]
    ├─► Execute Lua script
    │    ├─► SET device:state:node_boi_pipariya → gateway_status=ONLINE
    │    ├─► INCR global:counters → total_online +1
    │    ├─► INCR node:counters:node_boi_zo_xxx → total_online +1
    │    ├─► INCR node:counters:node_boi_fgmo_xxx → total_online +1
    │    └─► INCR node:counters:node_boi_ho → total_online +1
    │
    ▼ (ACK)
```

### Example 2: Query - "How many branches offline?"

```
User: "How many branches offline?"
    │
    ▼
ChatController (with JWT: customer_id=BOI)
    │
    ▼
QueryRouterService
    │
    ├─► Pattern: "how many * offline"
    ├─► Intent: GLOBAL_OFFLINE_COUNT
    │
    ▼
RedisQueryService
    │
    ├─► Key: BOI:global:counters
    ├─► GET: total_offline
    │
    ▼
Answer: "**153 branches are currently OFFLINE across all BOI branches.**"
```

### Example 3: Query - "Status of PIPARIYA"

```
User: "Status of PIPARIYA branch?"
    │
    ▼
ChatController (with JWT: customer_id=BOI)
    │
    ▼
QueryRouterService
    │
    ├─► Pattern: "status of {branch}"
    ├─► Extract: branchName = "PIPARIYA"
    │
    ▼
RedisQueryService
    │
    ├─► Key: BOI:device:state:node_boi_pipariya
    ├─► GET ALL: gateway_status, battery_voltage, cctv_status, fas_status, ...
    │
    ▼
Answer: "**Branch PIPARIYA:**
- Gateway: ONLINE
- Battery: 12.5V DC
- CCTV: 12 cameras online
- Fire Alarm: NORMAL
- Intrusion: NORMAL
- Last Updated: 2 minutes ago"
```

---

## Testing Checklist

### Unit Tests

- [ ] Customer extraction from device name
- [ ] Branch name extraction
- [ ] Node ID generation
- [ ] Ancestor path computation
- [ ] Lua script execution
- [ ] JWT parsing
- [ ] Redis key generation

### Integration Tests

- [ ] Webhook receiving
- [ ] Event consumer processing
- [ ] TimescaleDB write/read
- [ ] Redis counter updates
- [ ] Query routing (rule-based)
- [ ] Multi-customer isolation

### Load Tests

- [ ] 100+ devices @ 10 events/sec
- [ ] Query response time < 50ms
- [ ] Redis memory usage
- [ ] TimescaleDB write throughput

---

## Timeline

| Week | Phase | Deliverables |
|------|-------|--------------|
| Week 1 | Phase 1 | Database schema, entities, repositories, customer data loading |
| Week 2 | Phase 2 | Webhook controller, event consumer, event pipeline |
| Week 3 | Phase 3 | Redis integration, Lua scripts, caching |
| Week 4 | Phase 4 | Query router, rule-based patterns, deterministic answers |
| Week 5 | Phase 5 | JWT auth, security, multi-customer isolation |
| Week 6 | Testing | Integration tests, load tests, bug fixes |

---

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Device name pattern changes | High | Make pattern configurable, support multiple patterns |
| Hierarchy inference incorrect | Medium | Add device attributes in TB for explicit mapping |
| Redis connection failure | Medium | Fallback to TimescaleDB direct queries |
| LLM API rate limits | Low | Rule-based router handles 60%+ queries |

---

## Next Steps

1. **Confirm customer prefixes** - Verify all 10 customer codes
2. **Setup TimescaleDB** - Create database and run schema
3. **Configure connection** - Update application-dev.properties
4. **Start Phase 1** - Create entities and repositories
5. **Test connectivity** - Verify DB and Redis connections

---

*Document Version: 1.0*
*Created: April 2026*
*Author: Implementation Team*
