# IoT Branch Monitoring Platform
## LLM Chatbot — Production Architecture v4.0
### All CTO Go-Live Review Items Resolved

> **ThingsBoard Private Cloud PE | 6,000 Branches | Scalable to 50,000**
>
> Event Durability • Replay Capability • Dynamic Hierarchy Isolation • Sub-10ms Query Response

---

| Attribute | Value |       
|---|---|
| Document Version | 4.0 — CTO Review Gate: All items resolved |
| Supersedes | v3.0 (TB Private Cloud PE Architecture) |
| CTO Review Date | April 2026 |
| New in v4.0 | Event Store (TimescaleDB), Replay Service, Dynamic Hierarchy Tree, Rule-based query router, Idempotency fix |
| Platform | ThingsBoard Private Cloud Professional Edition |
| Operator | Seple Novaedge Pvt. Ltd. (Single Platform Operator) |
| Classification | Confidential — Internal R&D |
| Status | **Ready for Production Deployment** |

---

## Table of Contents

1. [CTO Go-Live Review: Complete Resolution Matrix](#0-cto-go-live-review-complete-resolution-matrix)
2. [What Is New in v4.0](#1-what-is-new-in-v40)
3. [Platform Tenancy & Hierarchy Model](#2-platform-tenancy--hierarchy-model)
4. [Revised End-to-End Architecture](#3-revised-end-to-end-architecture)
5. [Event Store — Durability, Audit Trail & Replay](#4-event-store--durability-audit-trail--replay-cr1--cr2)
6. [Customer & Hierarchy Isolation](#5-customer--hierarchy-isolation-cr3)
7. [Idempotency Fix — TB Message ID](#6-idempotency-fix-c1--tb-message-id)
8. [ThingsBoard Rule Engine](#7-thingsboard-rule-engine-unchanged-from-v30)
9. [Query Engine: Rule-Based Router + LLM](#8-query-engine-rule-based-router--llm-c2)
10. [In-Memory Buffer: Accepted Trade-Off](#9-in-memory-buffer-accepted-trade-off-analysis-c4)
11. [Scalability Roadmap](#10-scalability-roadmap-c3--reframed)
12. [High Availability & Failure Analysis](#11-high-availability--failure-analysis)
13. [Security](#12-security)
14. [Testing Strategy](#13-testing-strategy)
15. [Operational Runbooks](#14-operational-runbooks-v40)
16. [Implementation Roadmap](#15-implementation-roadmap--final-v40)

---

## 0. CTO Go-Live Review: Complete Resolution Matrix

This section maps every item from the CTO review to its resolution in this document. Read this first. Each item is addressed in full in its referenced section.

> **❌ CTO Final Decision (v3.0): NOT production-ready**
>
> - **Reason 1:** No durability layer — events lost on failure, no audit trail, no compliance posture.
> - **Reason 2:** No replay capability — cannot rebuild Redis from events if state corrupts.
> - **Reason 3:** No customer isolation — cannot evolve Dexter HMS as a multi-bank product.
>
> v4.0 resolves all three critical gaps plus all Change Required items.

| CTO Item | Category | Issue Raised | Resolution in v4.0 | Section |
|---|---|---|---|---|
| A1–A6 | Approved | Architecture fundamentals correct | Retained unchanged. See Section 4. | 4 |
| C1 | Change Required | Idempotency uses timestamp — collision risk | Replaced with TB Message ID (UUID, globally unique, provided by TB PE natively) | 6 |
| C2 | Change Required | LLM used for simple count/status queries | Rule-based query router added: counts/status = direct Redis read; summaries/analysis = LLM | 8.2 |
| C3 | Change Required | "100k without redesign" over-claim | Reframed as "scales to 50k with planned phase enhancements"; honest roadmap added | 10 |
| C4 | Change Required | In-memory buffer has small data-loss window | Acknowledged as accepted design trade-off with documented conditions; alternative analysed | 9 |
| CR1 | **CRITICAL** | No historical / event storage layer | TimescaleDB event store added: all events persisted before Redis write; full audit trail | 4 |
| CR2 | **CRITICAL** | No event replay capability | Replay Service added: rebuilds Redis from TimescaleDB event log; invalidates cold-start dependency | 4.4 |
| CR3 | **CRITICAL** | No customer isolation | Dynamic hierarchy tree with `customer_id` scoping, Redis key isolation, and RBAC layer added throughout | 5, 8 |

> **✅ v4.0 Verdict**
>
> All three critical gaps are resolved. All four change-required items are resolved. All six approved items are retained unchanged.
>
> This document is the complete, authorised implementation specification for production deployment.

---

## 1. What Is New in v4.0

### 1.1 The Three Critical Additions

| Addition | Purpose | Technology | Impact |
|---|---|---|---|
| Event Store | Durable log of every device state transition. Audit trail. Compliance posture. | TimescaleDB (PostgreSQL extension for time-series) | Satisfies CR1: durability layer |
| Replay Service | Rebuild Redis state from the event log at any time. Replaces cold-start TB polling. | Spring Boot service reading from TimescaleDB | Satisfies CR2: replay capability |
| Dynamic Hierarchy Layer | Model each customer's unique org structure as a generic tree. Namespace all Redis keys and DB rows by `customer_id`. RBAC on chatbot queries scoped to hierarchy nodes. | PostgreSQL hierarchy tree + Redis key prefix + Spring Security | Satisfies CR3: customer isolation |

### 1.2 The Three Improvements

| Improvement | Change Made | Satisfies |
|---|---|---|
| Idempotency fix | Replace timestamp-based key with TB Message ID (UUID from TB PE message envelope). Globally unique, no collision possible. | C1 |
| Rule-based query router | Direct Redis read for count/status queries. LLM invoked only for summaries, analysis, and multi-entity queries. | C2 |
| Scalability reframing | Remove "100k without redesign" claim. Replace with honest 4-phase roadmap with explicit per-phase enhancements. | C3 |

### 1.3 What Remains Unchanged from v3.0

- TB → Kafka → Webhook → RabbitMQ → Redis event pipeline (A1)
- CQRS: write path and read path fully separated (A2)
- SequentialByOriginator queue for per-device ordering (A3)
- Atomic Lua script updating all counter levels (A4) — now generalised to dynamic depth
- Sub-5ms webhook ACK with in-memory buffer (A5, C4 accepted as trade-off)
- TB native inactivity/activity events for stale detection (A6)
- TBEL scripts, TB support requests, Redis schema core structure

---

## 2. Platform Tenancy & Hierarchy Model

### 2.1 Single Operator, Multiple Customers

Seple Novaedge Pvt. Ltd. is the **single platform operator**. There is only one deployment of this system. The platform serves multiple **bank customers** (BOI, BOB, SBI, PNB, etc.), each identified by a `customer_id` within that single deployment.

```
Seple Novaedge (Platform Operator — Single Deployment)
    ├── customer: BOI  (Bank of India)
    │       └── HO → FGMO → ZO → Branch (Device)
    ├── customer: BOB  (Bank of Baroda)
    │       └── HO → ZO → RO → Branch (Device)
    ├── customer: SBI  (State Bank of India)
    │       └── HO → LHO → ZO → RBO → Branch (Device)
    ├── customer: PNB  (Punjab National Bank)
    │       └── HO → ZO → CO → Branch (Device)
    └── ... (new customers added as new HO trees, zero code changes)
```

Adding a new bank customer = creating a new `customer_id` and registering its hierarchy tree. **Zero code changes required.**

### 2.2 The Dynamic Hierarchy Problem

Each bank customer uses a different internal org structure with different depths and different names for intermediate levels:

| Customer | Level 1 | Level 2 | Level 3 | Level 4 | Leaf |
|---|---|---|---|---|---|
| BOI | HO | FGMO | ZO | — | Branch |
| BOB | HO | ZO | RO | — | Branch |
| SBI | HO | LHO | ZO | RBO | Branch |
| CB | HO | CO | RO | — | Branch |
| IB | HO | ZO | RO | — | Branch |
| PNB | HO | ZO | CO | — | Branch |
| UBI | HO | ZO | RO | — | Branch |
| CBI | HO | ZO | RO | — | Branch |
| IOB | HO | RO | — | — | Branch |
| UCO | HO | ZO | — | — | Branch |

**Key observations:**
- Depth ranges from 3 levels (IOB, UCO) to 5 levels (SBI).
- Intermediate node names (FGMO, LHO, ZO, RO, CO, RBO) are **display labels only** — all intermediate nodes behave identically as aggregation containers.
- The system must aggregate counter data (online/offline/alarm counts) at **every level** of any customer's hierarchy, regardless of depth or label names.
- No hierarchy level is hardcoded anywhere in application logic.

### 2.3 The Generic Hierarchy Tree Solution

Every node in every customer's org structure is stored as a row in a single `hierarchy_nodes` table. Each node knows its parent. Branches (TB devices) are leaf nodes of this tree.

| Field | Description | Example |
|---|---|---|
| `node_id` | Unique identifier | `node_boi_fgmo_north` |
| `customer_id` | Which bank customer | `BOI` |
| `node_type_label` | Bank-specific display name for this level | `FGMO`, `ZO`, `RBO`, `LHO`, `CO` |
| `node_level` | Depth from HO (HO = 1, first intermediate = 2, …) | `2` |
| `parent_id` | Parent node's `node_id` (NULL for HO) | `node_boi_ho` |
| `is_leaf` | `true` only for Branch (TB device) nodes | `false` |
| `tb_device_id` | ThingsBoard device UUID (leaf nodes only) | `uuid...` |
| `display_name` | Human-readable name | `FGMO NORTH` |

**Ancestor path (pre-computed):** For every branch (leaf), the full path from HO down to the branch's parent is pre-computed and stored in `branch_ancestor_paths`. This path is loaded into the `AncestorPathCache` at application startup and refreshed when the hierarchy changes. The Lua script receives this path as arguments — the DB is never queried during live event processing.

### 2.4 Redis Key Design — Generic Node Counters

Because hierarchy levels are dynamic, Redis counter keys use `node_id` rather than level-specific names:

| Key Pattern | Stores | Example |
|---|---|---|
| `{customer_id}:global:counters` | Total counts across the entire customer | `BOI:global:counters` |
| `{customer_id}:node:counters:{node_id}` | Counts for any node at any level | `BOI:node:counters:node_boi_fgmo_north` |
| `{customer_id}:branch:state:{branch_node_id}` | Full state hash for a leaf branch | `BOI:branch:state:BR_042` |
| `{customer_id}:hierarchy:branch:{id}:ancestors` | Cached ancestor path for a branch | `BOI:hierarchy:branch:BR_042:ancestors` |
| `{customer_id}:hierarchy:node:{node_id}:children` | Set of direct children of any node | `BOI:hierarchy:node:node_boi_fgmo_north:children` |
| `{customer_id}:heartbeat:registry` | Last-seen timestamps for stale detection | `BOI:heartbeat:registry` |
| `{customer_id}:idem:{tb_message_id}` | Idempotency secondary check (24h TTL) | `BOI:idem:{uuid}` |

### 2.5 Lua Script — Dynamic Ancestor Walk

The Lua script no longer hardcodes 4 fixed levels. It receives the full ancestor path as ARGV and increments counters at every level:

```lua
-- ARGV[1] = customer_id
-- ARGV[2] = branch_node_id
-- ARGV[3] = field (e.g. "gateway_status")
-- ARGV[4] = new_value (e.g. "ONLINE")
-- ARGV[5] = prev_value (e.g. "OFFLINE")
-- ARGV[6] = epoch_seconds
-- ARGV[7..N] = ancestor node_ids from HO down to parent of branch
--              (pre-computed, passed in at runtime — length varies per customer)

local customer_id = ARGV[1]
local branch_id   = ARGV[2]
local field       = ARGV[3]
local new_val     = ARGV[4]
local prev_val    = ARGV[5]

-- 1. Update branch state hash
local branch_key = customer_id .. ":branch:state:" .. branch_id
redis.call("HSET", branch_key, field, new_val)
redis.call("HSET", branch_key, "last_updated", ARGV[6])

-- 2. Update global counter
local global_key = customer_id .. ":global:counters"
if prev_val ~= "" and prev_val ~= new_val then
    redis.call("HINCRBY", global_key, "total_" .. prev_val, -1)
end
redis.call("HINCRBY", global_key, "total_" .. new_val, 1)

-- 3. Walk up the ancestor chain — length varies (2 for IOB, 4 for SBI, etc.)
for i = 7, #ARGV do
    local node_key = customer_id .. ":node:counters:" .. ARGV[i]
    if prev_val ~= "" and prev_val ~= new_val then
        redis.call("HINCRBY", node_key, "total_" .. prev_val, -1)
    end
    redis.call("HINCRBY", node_key, "total_" .. new_val, 1)
end

return "UPDATED"
```

The ancestor path `ARGV[7..N]` is loaded from `branch_ancestor_paths` at startup into `AncestorPathCache`. When an event arrives, the consumer fetches the path from cache (< 1ms) and passes it to Lua. The DB is never hit during event processing.

---

## 3. Revised End-to-End Architecture

### 3.1 Architecture Golden Rules (Updated)

> **★ v4.0 Architecture Invariants**
>
> ① Reads (chatbot) **NEVER** touch ThingsBoard or TimescaleDB. They read Redis only.
>
> ② Every event is written to TimescaleDB **FIRST**, then Redis. TimescaleDB is the system of record.
>
> ③ Redis is the serving layer — a materialised view of TimescaleDB, always rebuildable.
>
> ④ Every Redis key and DB row is scoped by `customer_id`. No two customers share any key or row.
>
> ⑤ The Lua script walks the full ancestor path atomically — counter accuracy guaranteed at every hierarchy level, regardless of depth.
>
> ⑥ Simple queries (counts, status) never invoke the LLM. LLM is for summaries and analysis only.
>
> ⑦ In-memory buffer data-loss window (~50ms on crash) is accepted. TimescaleDB + Replay closes the gap.
>
> ⑧ The hierarchy tree is the source of truth for org structure. No hierarchy logic is hardcoded anywhere.

### 3.2 Complete System Data Flow (v4.0)

```
┌───────────────────────────────────────────────────────┐
│ THINGSBOARD PRIVATE CLOUD PE                          │
│   [Device] → MQTT/HTTP                                │
│   [TB Internal Kafka 50GB] → [Rule Engine]            │
│   [TBEL script: formats payload with customer_id,     │
│    branch_node_id, tb_message_id (UUID)]              │
│   [REST API Call: POST /webhooks/tb + HMAC header]    │
└───────────────────────────────────────────────────────┘
                    │ HTTP POST + HMAC-SHA256
┌───────────────────────────────────────────────────────┐
│ WEBHOOK RECEIVER (Spring Boot)                        │
│   1. Validate HMAC (< 0.5ms)                          │
│   2. Push to in-memory buffer                         │
│   3. Return HTTP 200 (< 5ms total)                    │
│   4. Background: publish to RabbitMQ (customer queue) │
└───────────────────────────────────────────────────────┘
                    │
     [RabbitMQ: iot.{customer_id}.events]  ← per-customer queue
                    │
┌───────────────────────────────────────────────────────┐
│ EVENT CONSUMER (Spring Boot)                          │
│   1. Check idempotency: TB Message ID (UUID)          │
│   2. Persist to TimescaleDB (system of record)        │
│   3. Load branch ancestor path from AncestorPathCache │
│   4. Execute Lua: update branch + ALL ancestor nodes  │
│      + global counter — atomically in one round-trip  │
│   5. ACK RabbitMQ only after BOTH DB + Redis succeed  │
└───────────────────────────────────────────────────────┘
         │                          │
  [TimescaleDB]            [Redis: {customer_id}:*]
  System of record          Serving layer (materialised view)
  Audit / Compliance        < 10ms query response
  Source for Replay         Pre-aggregated node counters
  Hierarchy tree store      Dynamic-depth ancestor counters

══════════════════════════ QUERY PATH ══════════════════════════

[User: chatbot query]
        │
[Auth: validate JWT → extract customer_id + allowed node_ids]
        │
[Rule-Based Router: node-name resolution + pattern matching < 5ms]
        │
  YES (simple) → Read Redis (customer-scoped) → format answer → return (no LLM)
  NO (complex) → NLU classify → resolve node_id → read Redis → build context → LLM
        │
[Answer returned to user in < 2 seconds]
```

### 3.3 Component Inventory (v4.0 Complete)

| Component | Technology | Role | New in v4? |
|---|---|---|---|
| TB transport + internal Kafka | TB Private Cloud PE | Device event ingestion, burst absorption | — |
| TB Rule Engine (isolated) | TB PE, TBEL scripts | Event formatting, queue routing, webhook dispatch | — |
| TB native inactivity/activity | TB PE device profiles | Stale branch detection without external polling | — |
| Webhook receiver | Spring Boot | HMAC validation, < 5ms ACK, in-memory buffer | — |
| External queue | RabbitMQ (quorum, 3-node) | Per-customer durable queues, DLQ, retry | ✓ Customer-isolated |
| **Event Store** | **TimescaleDB** | **System of record, audit trail, replay source, hierarchy store** | **✓ NEW** |
| Event Consumer | Spring Boot | Idempotency (TB Msg ID), DB write, ancestor path lookup, Lua execution | ✓ Revised |
| **Replay Service** | **Spring Boot** | **Rebuild Redis from TimescaleDB on demand** | **✓ NEW** |
| **Hierarchy Tree** | **PostgreSQL `hierarchy_nodes` table** | **Stores every customer's org tree; source for ancestor paths** | **✓ NEW** |
| **Ancestor Path Cache** | **Spring Boot in-memory** | **Pre-computed branch→ancestor paths; fed to Lua at event time** | **✓ NEW** |
| Redis State Store | Redis Sentinel (3-node) | Pre-aggregated node counters and branch state (materialised view) | ✓ Customer-namespaced |
| **Rule-based router** | **Spring Boot** | **Direct answers for simple queries; no LLM cost** | **✓ NEW** |
| NLU classifier + LLM gateway | OpenAI / Azure OpenAI | Complex queries: summaries, analysis, multi-entity | ✓ Revised scope |
| **Customer auth layer** | **Spring Security + JWT** | **Customer isolation at query level; node-level RBAC** | **✓ NEW** |
| Reconciliation job | Spring Boot @Scheduled | 15-min drift correction as safety net | — |
| Observability | Micrometer + Grafana | Metrics, alerts, dashboards | ✓ Extended |

---

## 4. Event Store — Durability, Audit Trail & Replay (CR1 + CR2)

> **✅ CTO Issues Resolved**
>
> **CR1:** No historical/event storage layer — no audit trail, no RCA, no compliance readiness.
>
> **CR2:** No event replay capability — cannot rebuild Redis if corrupted.
>
> **Resolution:** TimescaleDB is introduced as the system of record. Every event is persisted **BEFORE** the Redis write. Redis is now a materialised view of TimescaleDB — always rebuildable, never the source of truth.

### 4.1 Why TimescaleDB

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| PostgreSQL (plain) | Simple, widely understood | No time-series optimisation; slow range queries at millions of rows | Acceptable for < 1M events/month |
| **TimescaleDB** | PostgreSQL extension; automatic time partitioning; 10-100x faster time-series queries; familiar SQL; same JDBC driver | Slightly more setup than plain Postgres | **Recommended — best fit** |
| ClickHouse | Extreme throughput; best for analytics | Complex ops; not ACID; harder RCA queries | Overkill for current scale; consider at 50k+ devices |
| Cassandra | High write throughput | Complex; no SQL; operational overhead; hard to query for audit | Not recommended |

### 4.2 TimescaleDB Schema

```sql
-- ============================================================
-- Hierarchy node tree — stores every customer's org structure
-- ============================================================
CREATE TABLE hierarchy_nodes (
    node_id          VARCHAR(128)  NOT NULL PRIMARY KEY,
    customer_id      VARCHAR(64)   NOT NULL,
    parent_id        VARCHAR(128)  REFERENCES hierarchy_nodes(node_id),
    node_type_label  VARCHAR(64)   NOT NULL,  -- "HO","FGMO","ZO","RO","LHO","RBO","CO" etc.
    node_level       INT           NOT NULL,  -- 1=HO, 2=first intermediate, etc.
    display_name     VARCHAR(256)  NOT NULL,
    is_leaf          BOOLEAN       NOT NULL DEFAULT FALSE,
    tb_device_id     UUID,                    -- only set on leaf (branch) nodes
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX ON hierarchy_nodes (customer_id);
CREATE INDEX ON hierarchy_nodes (parent_id);
CREATE INDEX ON hierarchy_nodes (customer_id, is_leaf);

-- ============================================================
-- Pre-computed ancestor paths — one row per branch (leaf) node
-- ============================================================
CREATE TABLE branch_ancestor_paths (
    branch_node_id   VARCHAR(128)  NOT NULL PRIMARY KEY
                     REFERENCES hierarchy_nodes(node_id),
    customer_id      VARCHAR(64)   NOT NULL,
    ancestor_path    VARCHAR(128)[] NOT NULL,  -- ordered: [ho_id, intermediate..., parent_id]
    path_depth       INT           NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX ON branch_ancestor_paths (customer_id);

-- ============================================================
-- Device event log (TimescaleDB hypertable)
-- ============================================================
CREATE TABLE device_events (
    id               BIGSERIAL,
    customer_id      VARCHAR(64)   NOT NULL,
    branch_node_id   VARCHAR(128)  NOT NULL,
    tb_message_id    UUID          NOT NULL UNIQUE,  -- idempotency key
    log_type         VARCHAR(64)   NOT NULL,
    field            VARCHAR(64)   NOT NULL,
    prev_value       VARCHAR(64),
    new_value        VARCHAR(64)   NOT NULL,
    event_time       TIMESTAMPTZ   NOT NULL,
    received_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    lua_result       VARCHAR(16),
    PRIMARY KEY (customer_id, event_time, id)
);

SELECT create_hypertable('device_events', 'event_time',
    partitioning_column => 'customer_id',
    number_partitions   => 4);

CREATE INDEX ON device_events (customer_id, branch_node_id, event_time DESC);
CREATE INDEX ON device_events (tb_message_id);

SELECT add_retention_policy('device_events', INTERVAL '2 years');
SELECT add_compression_policy('device_events', INTERVAL '30 days');
```

### 4.3 Write Order in Event Consumer (CR1 Fix)

TimescaleDB is written **FIRST**. Redis is written **SECOND**. If Redis fails, the event is durable in TimescaleDB and can be replayed. If TimescaleDB fails, the message is NACK'd — Redis is never written without a durable record.

```
[RabbitMQ delivers message]
        │
[Step 1: Idempotency check — tb_message_id UNIQUE constraint]
  Exists?     → ACK, skip
  Not exists? → continue
        │
[Step 2: Write to TimescaleDB]  ← SYSTEM OF RECORD WRITE
  On DB failure → NACK (RabbitMQ retries; Redis not touched)
        │
[Step 3: Load ancestor path from AncestorPathCache]
  cache.getAncestors(customer_id, branch_node_id)
  → returns ordered List<String> of node_ids (variable length)
        │
[Step 4: Execute Lua script → Redis]
  Pass customer_id + branch_node_id + field + values + ancestor_path[] as ARGV
  Lua walks the full ancestor chain atomically
  On Redis failure → NACK (RabbitMQ retries)
        │
[Step 5: ACK to RabbitMQ]
  Only after BOTH TimescaleDB write AND Redis Lua succeed
```

### 4.4 Replay Service (CR2 Fix)

The Replay Service reads all events from TimescaleDB for a given customer and replays them in chronological order to rebuild Redis state.

| Trigger | When to Use |
|---|---|
| Redis complete data loss | Full wipe: replay all events for customer since beginning of time |
| Redis counter drift (severe) | Selective replay: replay last N hours for affected node subtree |
| New Redis Cluster shard added | Replay customer partition to populate new shard |
| New customer onboarded (migration) | Replay historical events if migrating from another system |
| Post-incident forensics | Replay to reconstruct exact state at any point in time |

```java
@Service
public class ReplayService {

    public void replayForCustomer(String customerId, Instant from, Instant to) {
        log.info("[REPLAY] Starting for customer={} from={} to={}", customerId, from, to);

        redis.flushCustomerKeys(customerId);

        // Reload ancestor path cache before replaying
        ancestorPathCache.loadForCustomer(customerId);

        eventStore.streamEvents(customerId, from, to, event -> {
            List<String> ancestorPath = ancestorPathCache.getAncestors(
                customerId, event.getBranchNodeId()
            );
            luaScript.execute(
                customerId,
                event.getBranchNodeId(),
                event.getField(),
                event.getNewValue(),
                event.getPrevValue(),
                event.getEventTime().getEpochSecond(),
                ancestorPath  // variable-length list
            );
        });

        log.info("[REPLAY] Complete for customer={}", customerId);
    }
}
```

Expose via secured actuator endpoint: `POST /actuator/replay?customer={id}&from={iso}&to={iso}`. Require admin JWT. Log all invocations to the audit table.

### 4.5 Audit & Compliance Queries (Examples)

```sql
-- Branches that went OFFLINE in the last 24 hours for a customer
SELECT hn.display_name AS branch_name, de.event_time, de.prev_value, de.new_value
FROM device_events de
JOIN hierarchy_nodes hn ON hn.node_id = de.branch_node_id
WHERE de.customer_id = 'BOI'
  AND de.field = 'gateway_status'
  AND de.new_value = 'OFFLINE'
  AND de.event_time > NOW() - INTERVAL '24 hours'
ORDER BY de.event_time DESC;

-- Count alarms under any intermediate node (FGMO, ZO, RO — same query regardless of label)
SELECT COUNT(*) FROM device_events de
JOIN branch_ancestor_paths bap ON bap.branch_node_id = de.branch_node_id
WHERE de.customer_id = 'BOI'
  AND 'node_boi_fgmo_north' = ANY(bap.ancestor_path)
  AND de.log_type = 'fas_alarm'
  AND de.event_time >= DATE_TRUNC('month', NOW());

-- Full event history for a specific branch (for RCA)
SELECT de.log_type, de.prev_value, de.new_value, de.event_time
FROM device_events de
WHERE de.customer_id = 'BOI' AND de.branch_node_id = 'node_boi_br042'
ORDER BY de.event_time DESC LIMIT 200;
```

---

## 5. Customer & Hierarchy Isolation (CR3)

> **✅ CTO Issue Resolved**
>
> **CR3:** Current design is single-customer only. Cannot scale Dexter HMS as a multi-bank product.
>
> **Resolution:** `customer_id` is a first-class citizen in every layer. Each customer's hierarchy is a separate subtree in `hierarchy_nodes`. Redis keys, RabbitMQ queues, TimescaleDB rows, and chatbot queries are all scoped by `customer_id`. Adding a new bank = registering its `customer_id` and uploading its hierarchy tree. **Zero code changes required.**

### 5.1 Isolation Across Every Layer

| Layer | Isolation Mechanism | Example |
|---|---|---|
| Redis keys | Prefix all keys with `customer_id` | `BOI:global:counters` \| `BOB:global:counters` |
| RabbitMQ queues | One queue per customer | `iot.BOI.events` \| `iot.BOB.events` |
| TimescaleDB `device_events` | `customer_id` column + row-level security | `WHERE customer_id = 'BOI'` |
| TimescaleDB `hierarchy_nodes` | `customer_id` column; each bank's tree is a separate subtree | `WHERE customer_id = 'BOB'` |
| Spring Boot consumers | Consumer group per customer queue | Each consumer loaded with its customer context |
| Chatbot API | JWT includes `customer_id` claim; all Redis reads prefixed | User from BOI can only query `BOI:*` keys |
| Replay Service | Replay scoped to single `customer_id` | Cannot replay across customer boundary |
| Lua script | `customer_id` passed as `ARGV[1]`; prefixed on all keys | `BOI:branch:state:BR_042` |

### 5.2 Customer Provisioning (Onboarding a New Bank)

Adding a new customer requires only configuration and data entry — no code deployment:

1. Create `customer_id` record (e.g. `PNB`): display name, TB organisation ID, inactivity timeout
2. Upload the customer's hierarchy tree to `hierarchy_nodes` via admin UI or CSV import
3. System auto-computes `branch_ancestor_paths` from the uploaded tree
4. Create RabbitMQ queue: `iot.{customer_id}.events` with DLQ
5. Configure Spring Boot consumer instance for this customer's queue
6. Set device attributes in TB: `customer_id`, `branch_node_id`, `branch_name` on all devices
7. Set TB Device Profile inactivity timeout = 600s for this customer's devices
8. Run cold-start sync for new customer (first deployment only; no events to replay yet)
9. Issue JWT signing keys for this customer's chatbot users
10. Smoke test: verify event in TimescaleDB and Redis with correct `customer_id` prefix and correct ancestor node counter increments

### 5.3 Hierarchy Change Handling (Adding a New Node Mid-Operation)

When a customer adds a new regional office mid-operation:

1. Insert the new node into `hierarchy_nodes` with its `parent_id` and `customer_id`
2. Insert or re-parent branch nodes under the new node
3. Re-compute `branch_ancestor_paths` for affected branches
4. Trigger `AncestorPathCache.reloadForCustomer(customerId)` — picks up new paths within seconds
5. Initialise Redis counter key for new node automatically on first event
6. No replay required — new events pick up the new ancestor path immediately

### 5.4 Customer Authentication on Chatbot API

```json
// JWT structure for chatbot users
{
    "sub": "user@boi.co.in",
    "customer_id": "BOI",
    "roles": ["branch_viewer", "node_viewer"],
    "allowed_node_ids": ["node_boi_fgmo_north", "node_boi_fgmo_east"],
    "exp": 1712400000
}
```

```java
// Query router enforces customer scope on every Redis read
String customerId = jwtContext.getCustomerId();
// "BOI:node:counters:node_boi_fgmo_north"
// User from BOI can never access BOB:* or SBI:* keys
```

### 5.5 Redis Isolation Strategy

| Approach | Pros | Cons | When to Use |
|---|---|---|---|
| **Key namespacing (v4.0 choice)** | Simple; single Redis cluster; low ops overhead | Noisy neighbour on memory/CPU | **Up to ~10 customers** |
| Separate Redis DB (0–15) | DB-level flush possible | Limited to 16 DBs; still shares memory | Up to 15 customers; not recommended |
| Separate Redis instance per customer | Full isolation; independent scaling | High ops overhead; many Sentinel clusters | > 10 customers or regulated customers requiring data residency |

v4.0 uses key namespacing. Migrate to per-instance isolation when onboarding a regulated customer with strict data residency requirements.

---

## 6. Idempotency Fix (C1) — TB Message ID

> **✅ CTO Issue Resolved**
>
> **C1:** Current idempotency uses `branch_id + log_type + timestamp`. Collision risk: two events of the same type for the same branch within the same second produce identical keys.
>
> **Resolution:** Use TB Message ID (UUID). ThingsBoard PE includes a unique message ID in every Rule Engine message envelope. This UUID is globally unique and collision-free.

### 6.1 Reading TB Message ID in TBEL

```javascript
// TBEL script: add TB message ID and customer metadata to payload
msg.tb_message_id  = metadata.msgId;       // UUID — "550e8400-e29b-41d4-a716-446655440000"
msg.customer_id    = metadata.customerId;  // set as device attribute in TB
msg.branch_node_id = metadata.branchNodeId; // set as device attribute in TB
```

### 6.2 Idempotency Check (v4.0)

```java
// TimescaleDB: primary idempotency check (UNIQUE constraint on tb_message_id)
try {
    eventStore.insert(event);
} catch (DataIntegrityViolationException e) {
    log.info("[IDEM] Duplicate TB message ID: {}. Discarding.", event.getTbMessageId());
    return; // ACK without Redis write
}

// Redis secondary check (fast path for in-flight duplicates during replay)
// Key: {customer_id}:idem:{tb_message_id}  TTL: 24h
Boolean isNew = redis.opsForValue().setIfAbsent(
    customerId + ":idem:" + tbMessageId, "1", Duration.ofHours(24)
);
if (!isNew) { return; }
```

---

## 7. ThingsBoard Rule Engine (Unchanged from v3.0)

The Rule Engine design from v3.0 is retained in full. The only addition: `customer_id` and `branch_node_id` are extracted from device metadata and included in every webhook payload.

### 7.1 TBEL Payload Script Change for v4.0

```javascript
// Add to Node 3 TBEL Payload Format Script:
msg.customer_id    = metadata.customerId;   // set as device attribute in TB
msg.branch_node_id = metadata.branchNodeId; // set as device attribute in TB
msg.tb_message_id  = metadata.msgId;        // UUID from TB PE message envelope
// Remove old timestamp-based idempotency key construction — no longer used
```

### 7.2 TB Support Requests (Carry Forward from v3.0)

| Setting | Requested Value | Status |
|---|---|---|
| Rule Engine mode | Isolated | Submit before go-live |
| MVEL_MAX_ERRORS | 10 (up from 3) | Submit before go-live |
| MVEL_MAX_BLACKLIST_DURATION_SEC | 300s (up from 60s) | Submit before go-live |
| TB_RE_HTTP_CLIENT_POOL_MAX_CONNECTIONS | 100 | Submit before go-live |
| Customer transport rate limit | 2000:1, 60000:60 | Submit before go-live |
| Device inactivity timeout | 600 seconds (in Device Profile) | Configure in TB UI |

---

## 8. Query Engine: Rule-Based Router + LLM (C2)

> **✅ CTO Issue Resolved**
>
> **C2:** Not all queries need LLM. Using LLM for simple count/status queries adds 500–1500ms latency and unnecessary API cost.
>
> **Resolution:** A rule-based query router intercepts simple queries and returns direct Redis answers. LLM is invoked **ONLY** for summaries, trend analysis, or multi-entity comparative queries.

### 8.1 Query Classification Decision Tree

```
[User query arrives]
        │
[Auth: extract customer_id + allowed node_ids from JWT]
        │
[Node-Name Resolver: "FGMO NORTH" → node_boi_fgmo_north  (< 1ms, from startup index)]
        │
[RULE-BASED ROUTER: pattern matching < 5ms]
        │
  "how many * offline"           → SIMPLE: {c}:global:counters → total_offline
  "how many * online"            → SIMPLE: {c}:global:counters → total_online
  "how many * fas alarm"         → SIMPLE: {c}:global or node counter → fas_alarm
  "status of branch {name}"      → SIMPLE: resolve branch_node_id → branch:state
  "is {branch} online"           → SIMPLE: branch:state → gateway_status
  "branches in {any node name}"  → SIMPLE: {c}:node:counters:{node_id} → total_branches
  "offline in {any node name}"   → SIMPLE: {c}:node:counters:{node_id} → total_offline
        │
  YES → Read Redis (customer-scoped, < 5ms) → Format answer → Return (no LLM, < 50ms)
        │
  NO  → NLU Classifier (cheap LLM, < 100ms) → resolve scope + node_id
        → Read Redis (tiered context, < 10ms) → Build context JSON → LLM (500–1500ms)
        → Return natural language answer
```

### 8.2 Rule-Based Router: Pattern Table

| Pattern | Redis Key Read | Answer Format | LLM? |
|---|---|---|---|
| `"how many.*offline"` at customer scope | `{c}:global:counters → total_offline` | "153 branches are currently offline as of 14:23 UTC." | No |
| `"how many.*online"` at customer scope | `{c}:global:counters → total_online` | "5,847 branches online." | No |
| `"fas alarm"` / `"fire alarm"` count | `{c}:global:counters → fas_alarm` | "4 branches have active FAS alarms." | No |
| `"status of {branch name}"` | `{c}:branch:state:{id} → all fields` | "BR-042 Howrah: OFFLINE \| FAS: NORMAL \| Power: MAINS" | No |
| `"is {branch} online"` | `{c}:branch:state:{id} → gateway_status` | "No. BR-042 is currently OFFLINE." | No |
| `"branches in {any node name}"` | `{c}:node:counters:{node_id} → total_branches` | "FGMO NORTH has 48 branches." | No |
| `"offline in {any node name}"` | `{c}:node:counters:{node_id} → total_offline` | "ZO KOLKATA: 3 offline branches." | No |
| `"summary of {any node name}"` | `{c}:node:counters:{node_id}` | Structured paragraph via LLM | **Yes** |
| `"why is {branch} offline"` | `branch:state` + TimescaleDB last 10 events | LLM narrative from event history | **Yes** |
| `"compare {node1} vs {node2}"` | Two `node:counters` keys | LLM comparative analysis | **Yes** |
| `"trend / history / over time"` | TimescaleDB query | LLM narrative from historical data | **Yes** |

> **Node-Name Resolution:** The query router resolves plain-language names ("FGMO NORTH", "ZO KOLKATA", "RO DELHI", "LHO MUMBAI") to `node_id` values using a name index built from `hierarchy_nodes` at startup. The same resolution logic works regardless of node type label — "FGMO NORTH" (BOI) and "LHO MUMBAI" (SBI) are resolved identically.

### 8.3 LLM Usage: When and Why

| Query Type | Why LLM is Needed | Example |
|---|---|---|
| Summaries | Narrative generation from aggregated numbers | Give me a summary of FGMO NORTH health |
| Root cause / analysis | Reasoning from event history | Why has BR-042 been going offline repeatedly? |
| Multi-entity comparison | Synthesising across multiple nodes | How does ZO KOLKATA compare to ZO DELHI this week? |
| Trend analysis | TimescaleDB historical data narrativised | How many FAS alarms occurred in the last 30 days? |
| Ambiguous queries | NLU required to resolve intent | Show me problem branches |

### 8.4 LLM Context Format

Context is tiered based on query scope resolved from the hierarchy tree:
- **Customer (global):** 50 tokens — `global:counters` only
- **HO level:** 80 tokens — `node:counters` for that HO
- **Any intermediate node (FGMO / ZO / RO / LHO etc.):** 100–400 tokens — `node:counters` + immediate children summaries
- **Branch:** 80 tokens — full `branch:state` hash

Never send raw device lists to LLM. Always include `data_age_sec`; if > 900, LLM appends staleness notice.

### 8.5 Performance Comparison

| Query Type | v3.0 Latency | v4.0 Latency | Saving |
|---|---|---|---|
| Simple count (e.g. how many offline) | 600–1600ms (LLM always) | **< 50ms** (rule-based) | > 95% reduction |
| Branch status query | 600–1600ms | **< 50ms** (rule-based) | > 95% reduction |
| Node summary (any level) | 600–1600ms | 600–1600ms (LLM still used) | No change (LLM justified) |
| Customer network overview | 600–1600ms | 600–1600ms | No change (LLM justified) |

Estimated **60–70% of all chatbot queries** are simple count or status queries — sub-50ms with zero LLM API cost.

---

## 9. In-Memory Buffer: Accepted Trade-Off Analysis (C4)

> **⚠ CTO Issue: Accepted as Design Trade-off**
>
> **C4:** The in-memory buffer between webhook receiver and RabbitMQ has a small data-loss window on Spring Boot crash. This is **accepted** because TimescaleDB closes the gap via replay, and the sync alternative violates the < 5ms TB ACK requirement.

### 9.1 Quantifying the Risk

| Scenario | Events at Risk | Recovery Path |
|---|---|---|
| Spring Boot pod crashes (rolling deploy) | Events in buffer (~5–50ms of traffic) | TB retries 3x (RETRY_ALL strategy) |
| Spring Boot OOM kill | Same as above | TB retry + reconciliation job (15-min cycle) |
| RabbitMQ publish timeout (< 1s) | Buffer fills; Spring Boot returns 503 | TB retries; buffer drains on recovery |
| Normal restart (graceful shutdown) | Buffer drained with 5s timeout | Zero loss |

### 9.2 Mitigation Measures

- JVM shutdown hook to drain buffer before Spring Boot stops (5s timeout)
- TB Rule Chain RETRY_ALL strategy: 3 retries, 5s backoff
- TimescaleDB + Replay Service closes any remaining gap
- Accepted residual risk: 0–3 device events lost on hard crash + TB retry exhaustion — all detectable via reconciliation

### 9.3 Alternative: Synchronous RabbitMQ Publish (Not Recommended)

| Metric | Async buffer (v4.0) | Sync publish |
|---|---|---|
| Webhook response time | **< 5ms** | 50–200ms |
| TB ACK timeout risk | None | High risk: TB drops message if > 10s |
| Data loss window | ~5–50ms on hard crash | Zero on crash; TB drop risk far worse |
| **Verdict** | **Accepted** | **Rejected** |

---

## 10. Scalability Roadmap (C3 — Reframed)

> **✅ CTO Issue Resolved**
>
> **C3:** Reframed as "scales to 50,000 with planned phase enhancements". No phase requires application logic redesign — only infrastructure scaling.

### 10.1 Phase Architecture

| Phase | Branches | Redis | Queue | DB | Spring Boot |
|---|---|---|---|---|---|
| **1 (Now)** | < 1,000 | Single + AOF | RabbitMQ quorum (3-node) | TimescaleDB single node | 2 instances |
| **2 (Growth)** | 1,000–5,000 | Sentinel (3-node) | RabbitMQ quorum (3-node) | TimescaleDB + read replica | 2–4 instances |
| **3 (Scale)** | 5,000–20,000 | Cluster (6-node) | Kafka (3 brokers, 6 partitions) | TimescaleDB cluster | 4–8 auto-scaled |
| **4 (Enterprise)** | 20,000–50,000 | Cluster + read replicas | Kafka (12+ partitions) | ClickHouse for analytics + TimescaleDB for audit | Auto-scaled |

### 10.2 Upgrade Triggers

| Metric | Threshold | Action |
|---|---|---|
| Branch count | Crosses 1,000 | Migrate Redis to Sentinel; verify TimescaleDB write throughput |
| Branch count | Crosses 5,000 | Migrate queue to Kafka; upgrade Redis to Cluster |
| TimescaleDB write latency p99 | > 50ms | Add read replica; tune chunk interval |
| Redis memory | > 60% of maxmemory | Upscale instance or add Cluster shard |
| Replay duration | > 30 min for full customer | Parallelise replay by intermediate node subtree |
| Rule-based router miss rate | > 40% | Add more pattern rules; review query analytics |

---

## 11. High Availability & Failure Analysis

### 11.1 Failure Mode Matrix (v4.0)

| Component Fails | Data at Risk | Recovery Mechanism | RTO |
|---|---|---|---|
| TB Rule Engine | Events in TB Kafka queue | TB auto-restarts; Kafka buffers replay automatically | < 2 min |
| Spring Boot webhook receiver | Events in in-memory buffer (~5–50ms) | TB retries 3x; replay service corrects residual drift | < 5 min |
| RabbitMQ node | None (quorum queue promotes) | Automatic quorum promotion | < 30s |
| TimescaleDB | None (NACK prevents Redis write without DB write) | Postgres replication promotes replica | < 2 min with replication |
| Redis primary | < 30s of writes | Sentinel promotes replica; replay fills gap | < 5 min |
| Redis complete loss | All Redis state | **Replay Service rebuilds from TimescaleDB** | < 10 min for 6,000 branches |
| TBEL script blacklisted | Events for 60s window | Reconciliation corrects; raise MVEL_MAX_ERRORS | < 15 min |
| LLM API unavailable | Chatbot complex queries only | Rule-based router still answers simple queries | Immediate for simple queries |
| Ancestor path cache lost | Events route to wrong node counters | Reload from `branch_ancestor_paths` table | < 1 min (startup reload) |

### 11.2 Cold-Start vs Replay (v4.0 Preference)

| Scenario | v3.0 Approach | v4.0 Approach | Why Better |
|---|---|---|---|
| Redis complete loss | Cold-start: poll TB REST API (~2 min for 6k devices) | **Replay from TimescaleDB** | No TB API load; perfectly accurate |
| New environment | Cold-start from TB | Cold-start from TB (still valid for first-ever deployment) | No change for true first deployment |
| Post-incident drift | Reconciliation job | Selective replay for affected node subtree + time window | Pinpoint accuracy |
| New customer onboarded (migration) | Manual state import | Replay from migrated event log | Fully automated |

---

## 12. Security

### 12.1 Customer Security Boundaries

| Layer | Enforcement | Failure Mode if Violated |
|---|---|---|
| JWT `customer_id` claim | Every chatbot API request validated | User from BOI could read BOB Redis keys |
| Redis key prefix | All reads/writes prefixed with `customer_id` from JWT | Cross-customer data leak |
| RabbitMQ queue per customer | Consumer subscribes only to its own queue | BOI events processed in BOB context |
| TimescaleDB RLS | `WHERE customer_id = ?` enforced + RLS policy on both tables | Audit queries return cross-customer data |
| Replay scope | Admin JWT required; `customer_id` scoped in payload | Replay could overwrite wrong customer's Redis state |
| Node-level RBAC | JWT `allowed_node_ids` restricts which nodes a user can query | Branch_viewer could query entire customer tree |

### 12.2 TimescaleDB Row-Level Security

```sql
ALTER TABLE device_events    ENABLE ROW LEVEL SECURITY;
ALTER TABLE hierarchy_nodes  ENABLE ROW LEVEL SECURITY;

CREATE POLICY customer_isolation_events ON device_events
    USING (customer_id = current_setting('app.current_customer'));

CREATE POLICY customer_isolation_hierarchy ON hierarchy_nodes
    USING (customer_id = current_setting('app.current_customer'));
-- Application sets: SET app.current_customer = 'BOI' on every JDBC connection checkout
```

### 12.3 Security Carry-Forward from v3.0

- Webhook HMAC-SHA256 validation — mandatory, implemented in webhook receiver
- Redis: internal subnet only, `requirepass`, TLS 1.2+, ACL per service role
- Dangerous Redis commands disabled: `FLUSHALL`, `FLUSHDB`, `DEBUG`, `KEYS`
- Secrets in HashiCorp Vault; rotated quarterly; never in config files or Git
- Spring Boot webhook endpoint whitelisted to TB Private Cloud IP range only

---

## 13. Testing Strategy

### 13.1 New Unit Tests (v4.0 Additions)

| Test Case | Expected Result |
|---|---|
| TB Message ID idempotency: same UUID twice | Second call exits after TimescaleDB duplicate key exception; Redis unchanged |
| TimescaleDB write failure: DB down | NACK to RabbitMQ; Redis not touched; message redelivered |
| Replay: 1,000 events in order | Final Redis state matches expected; all node counters correct at every level |
| Ancestor path: 3-level hierarchy (IOB — HO→RO→Branch) | Lua called with 2 ancestor nodes; counters correct at HO and RO |
| Ancestor path: 5-level hierarchy (SBI — HO→LHO→ZO→RBO→Branch) | Lua called with 4 ancestor nodes; counters correct at all 4 intermediate levels |
| Hierarchy change: new node added mid-test | Ancestor path cache refreshes; subsequent events route to new node correctly |
| Customer isolation: BOI query cannot read BOB keys | Redis read with BOB prefix returns nil; query returns "not found" |
| Rule-based router: "how many offline" | Returns direct Redis answer without LLM call; < 50ms |
| Node-name resolver: "FGMO NORTH" (BOI) and "LHO MUMBAI" (SBI) | Both resolve to correct `node_id`; no cross-customer collision |
| JWT: missing `customer_id` claim | 401 Unauthorised; no Redis read attempted |
| TimescaleDB RLS: wrong customer in session | Zero rows returned even if SQL omits WHERE customer_id |

### 13.2 Integration Tests (Extended for v4.0)

- Spin up: Redis, RabbitMQ, TimescaleDB (Testcontainers), mock TB webhook sender
- Load BOI hierarchy (4-level) and SBI hierarchy (5-level)
- Fire 1,000 events for BOI: verify TimescaleDB row count = 1,000; Redis node counters correct at all 4 levels
- Fire same 1,000 events again: verify TimescaleDB still = 1,000 (idempotency); Redis unchanged
- Fire 500 events for SBI: verify counters correct at all 5 levels independently; no cross-customer leak
- Kill Spring Boot mid-processing: restart; verify RabbitMQ retry completes; no double-writes
- Run Replay Service after clearing Redis: verify final state matches original at every node level
- Add new ZO node to BOI mid-test: verify subsequent events route correctly to new node
- Rule-based router: 20 query patterns including node-name resolution; 0 LLM calls for simple patterns

### 13.3 Load Test Pass Criteria (v4.0)

| Metric | Pass Threshold |
|---|---|
| 6,000 devices @ 100 events/sec sustained | Zero event loss; TimescaleDB write latency p99 < 50ms |
| TimescaleDB write throughput | > 200 inserts/sec sustained |
| Rule-based query response p99 | < 50ms |
| LLM-backed query response p99 | < 3 seconds |
| Replay: 6,000 branches full rebuild | < 10 minutes |
| Counter accuracy after 1M events | 100% match between Redis and TimescaleDB at every node level |
| 2 customers simultaneous (different hierarchy depths) | No cross-customer data leak; each customer's node counters independent |

---

## 14. Operational Runbooks (v4.0)

### 14.1 Redis Complete Data Loss

1. Confirm Redis is empty or corrupt: `redis-cli INFO keyspace`
2. Pause RabbitMQ consumers: `POST /actuator/consumer/pause`
3. Reload ancestor path cache: `POST /actuator/hierarchy/reload?customer={id}`
4. Execute replay: `POST /actuator/replay?customer={id}&from={start}&to={now}`
5. Monitor in Grafana: `replay.events_processed_per_sec`
6. Wait for completion (estimated 5–10 min for 6,000 branches)
7. Resume consumers: `POST /actuator/consumer/resume`
8. Spot-check 5 branch statuses against ThingsBoard UI

### 14.2 Counter Drift Detected

1. Identify affected scope: which customer, which node shows inconsistency?
2. Run TimescaleDB audit query: count expected state from event log vs Redis node counter
3. Selective replay for affected node subtree and time window
4. `POST /actuator/replay?customer={id}&node={node_id}&from={drift_start}&to={now}`
5. Verify counters post-replay; log correction and root cause

### 14.3 New Customer Onboarding

1. Create `customer_id` record: display name, TB organisation ID
2. Upload hierarchy tree to `hierarchy_nodes` via admin UI or CSV
3. Verify `branch_ancestor_paths` auto-computed for all branches
4. Create RabbitMQ queue: `iot.{customer_id}.events` with DLQ
5. Configure Spring Boot consumer for new customer queue
6. Set device attributes in TB: `customer_id`, `branch_node_id`, `branch_name` on all devices
7. Set TB Device Profile inactivity timeout = 600s
8. Run cold-start sync (first deployment only)
9. Issue JWT signing keys for this customer's users
10. Smoke test: verify event in TimescaleDB and Redis with correct `customer_id` prefix and correct node counter increments

### 14.4 Hierarchy Change (New Node Added Mid-Operation)

1. Insert new node into `hierarchy_nodes` with `parent_id` and `customer_id`
2. Insert or re-parent branch nodes under the new node
3. `POST /actuator/hierarchy/recompute?customer={id}&node={new_node_id}`
4. Redis counter key for new node initialises automatically on first event
5. No replay required — new ancestor path picked up immediately

### 14.5 Audit / Compliance Report Request

1. Use TimescaleDB audit queries (see Section 4.5) — no Redis involvement
2. All queries scoped to `customer_id` — RLS enforces automatically
3. Standard reports: event history by branch, alarm count by period, offline duration, node-level aggregations
4. For custom reports: provide SQL to DBA; TimescaleDB compression allows queries back 2 years

### 14.6 LLM API Outage

1. Alert: `chatbot.llm.api_failures > 0` in Grafana
2. Rule-based router handles count, status, and branch queries automatically (60–70% of all queries)
3. Complex queries return: *"Detailed analysis temporarily unavailable. Basic status queries are working normally."*
4. Recovery automatic; no Redis or TimescaleDB state affected

---

## 15. Implementation Roadmap — Final (v4.0)

### 15.1 Priority Stack

| Priority | Work Item | Days | Who | Blocker? |
|---|---|---|---|---|
| **P0** | TB device attributes: `customer_id`, `branch_node_id`, `branch_name` on ALL devices | 1 | TB Admin | **YES** |
| **P0** | TB Device Profile: inactivity timeout = 600s | 0.5 | TB Admin | **YES** |
| **P0** | TB Rule Chain: 3 chains + TBEL scripts + `customer_id` + `branch_node_id` + TB Message ID | 3 | Backend | **YES** |
| **P0** | TimescaleDB schema: `hierarchy_nodes`, `branch_ancestor_paths`, `device_events` hypertable, RLS | 3 | Backend + DBA | **YES** |
| **P0** | Load all 10 customer hierarchy trees into `hierarchy_nodes`; verify ancestor paths | 2 | TB Admin + DBA | **YES** |
| **P0** | Event consumer: TB Msg ID idempotency + TimescaleDB write FIRST + ancestor path lookup + Lua | 4 | Backend | **YES** |
| **P0** | Dynamic Lua script: walks variable-depth ancestor path | 2 | Backend | **YES** |
| **P0** | `AncestorPathCache`: loads from TimescaleDB at startup; refresh API endpoint | 1 | Backend | **YES** |
| **P0** | Webhook HMAC-SHA256 validation | 1 | Backend | **YES** |
| **P0** | Redis persistence: RDB + AOF + noeviction | 0.5 | DevOps | **YES** |
| **P0** | Submit TB support requests (isolated RE, MVEL limits, pool size) | 0.5 | DevOps | **YES** |
| **P0** | Cold-start sync (first deployment only) | 2 | Backend | **YES** |
| **P1** | Replay Service: customer scope + ancestor path reload + admin JWT | 3 | Backend | Required |
| **P1** | RabbitMQ: per-customer queues, DLQ, quorum (3-node) | 2 | DevOps | Required |
| **P1** | Redis Sentinel (3-node) | 2 | DevOps | Required |
| **P1** | TimescaleDB replication / read replica | 2 | DBA/DevOps | Required |
| **P1** | Reconciliation job + stale branch detector | 2 | Backend | Required |
| **P1** | Rule-based router: node-name resolver + all patterns in Section 8.2 | 4 | Backend | Required |
| **P1** | LLM chatbot: dynamic hierarchy context builder + staleness flag | 4 | AI/ML | Required |
| **P1** | Customer JWT auth + Redis prefix enforcement + node RBAC | 3 | Backend | Required |
| **P2** | TimescaleDB audit query REST endpoints (compliance reports) | 2 | Backend | Before audit request |
| **P2** | Hierarchy admin UI: add/edit/remove nodes; trigger path recompute | 3 | Backend + Frontend | Before new customer onboard |
| **P2** | Grafana: event pipeline, node counters, hierarchy, replay, query-routing metrics | 2 | DevOps | Before 1,000 branches |
| **P2** | Redis ACL per service + TLS + secrets in Vault | 2 | DevOps | Before 1,000 branches |
| **P2** | TimescaleDB RLS deployment + connection session variable setup | 1 | DBA | Before multi-customer |
| **P3** | Load test: 6,000-device scale (Section 13.3 pass criteria) | 3 | QA | Before 5,000 branches |
| **P3** | Kafka migration (replace RabbitMQ) | 5 | Backend + DevOps | At 5,000 branches |
| **P3** | Redis Cluster migration | 5 | DevOps | At 5,000 branches |

### 15.2 Effort Summary

| Band | Effort | Calendar Time (3-person team) |
|---|---|---|
| P0 Blockers | 20.5 days | ~4 weeks |
| P1 Required | 22 days | ~4 weeks (parallel with P0 completion) |
| P2 Operational | 10 days | ~2 weeks after P1 |
| P3 Scale | 13 days | Milestone-triggered |
| **P0+P1 to customer-facing deployment** | **~42 days** | **~7–8 weeks with 3-person team** |

### 15.3 CTO Sign-Off Checklist (Pre-Go-Live)

| Check | Status Required |
|---|---|
| TimescaleDB: `hierarchy_nodes` + `branch_ancestor_paths` + `device_events` with RLS active | PASS |
| All 10 customer hierarchy trees loaded and ancestor paths verified | PASS |
| Every event: TB Message ID stored as idempotency key in DB | PASS |
| Consumer write order: TimescaleDB FIRST, Redis SECOND | PASS |
| Lua script: correctly walks variable-depth ancestor path; counters accurate at every level | PASS |
| Replay Service: verified rebuilds correct Redis state including all node-level counters | PASS |
| Redis keys: all prefixed with `customer_id` throughout | PASS |
| RabbitMQ: one queue per customer, DLQ configured | PASS |
| Chatbot: JWT `customer_id` enforced on every Redis read | PASS |
| Rule-based router: node-name resolution works for all hierarchy label types | PASS |
| HMAC webhook validation active | PASS |
| Redis persistence (RDB+AOF) + noeviction | PASS |
| Redis Sentinel deployed (3-node) | PASS |
| TB support requests confirmed (isolated RE, MVEL limits) | PASS |
| All unit tests pass including variable-depth hierarchy tests | PASS |
| Integration: two-customer isolation verified (different hierarchy depths) | PASS |
| Load test: 6,000 device scale pass criteria met | PASS |
| Audit query: compliance report generated for test data | PASS |

---

*Document Version 4.0 | CTO Review Gate: All items resolved | April 2026 | Seple Novaedge Pvt. Ltd. | Confidential*
