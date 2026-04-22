# Target Flow Architecture (v4.0 — Event-Driven)

## 1. Flowchart Visualization (Mermaid)
*Note: This diagram will render visually in Markdown viewers that support Mermaid.js.*

```mermaid
flowchart TD
    subgraph TB["THINGSBOARD PRIVATE CLOUD PE"]
        Devices["[6,000 IoT Devices]"] -- MQTT --> Kafka["[TB Internal Kafka (50GB)]"]
        subgraph RE["Rule Engine — 3 Chains"]
            direction TB
            C1["Chain 1: State Change Detector<br/>(Filter: pass if changed)"]
            C2["Chain 2: Payload Formatter (TBEL)<br/>msg.tb_message_id = metadata.msgId<br/>msg.new_value = msg[msg.field]<br/>msg.event_time = metadata.ts"]
            C3["Chain 3: Webhook Dispatcher<br/>POST /webhooks/tb<br/>Header: X-HMAC-SHA256<br/>Retry: 3x, 5s backoff"]
            C1 --> C2 --> C3
        end
        Kafka --> C1
        
    end

    C3 -- "HTTP POST + HMAC" --> WR

    subgraph WR["WEBHOOK RECEIVER (Spring Boot)"]
        direction TB
        W1["1. Validate HMAC-SHA256 (< 0.5ms)"]
        W2["2. Parse TbEventPayload"]
        W3["3. Push to in-memory buffer<br/>(BlockingDeque, cap 10,000)"]
        W4["4. Return HTTP 200 (< 5ms total)"]
        W1 --> W2 --> W3 --> W4
        W3 -. "Background thread drains" .-> RMQ[/"RabbitMQ<br/>queue: iot.{customer_id}.events"/]
    end

    RMQ -- "RabbitMQ (per-customer quorum queue)" --> EC

    subgraph EC["EVENT CONSUMER (Spring Boot)"]
        direction TB
        E0["@RabbitListener(queues = 'iot.{customerId}.events')"]
        E1["Step 1: IDEMPOTENCY CHECK<br/>INSERT into device_events (UNIQUE)<br/>Duplicate? → ACK + discard"]
        E2["Step 2: TIMESCALEDB WRITE<br/>device_events table (hypertable)<br/>DB failure → NACK (RabbitMQ retries)"]
        E3["Step 3: LOAD ANCESTOR PATH<br/>AncestorPathCache.getAncestors() (< 1ms)"]
        E4["Step 4: EXECUTE LUA SCRIPT (Redis, atomic)<br/>HSET state, HINCRBY global/node counters"]
        E5["Step 5: ACK RabbitMQ<br/>Only after BOTH DB write AND Redis Lua succeed"]

        E0 --> E1 --> E2 --> E3 --> E4 --> E5
    end

    E4 -. "Redis has current state for<br/>ALL branches & customers" .-> Redis[(Redis)]

    subgraph QP["QUERY PATH (User asks a question)"]
        direction TB
        Q1["1. AUTH<br/>JWT → extract customer_id & allowed_node_ids"]
        Q2["2. RULE-BASED QUERY ROUTER<br/>QueryIntentResolver + NodeNameResolver"]

        Q1 --> Q2

        Q2 --> Q3{"Query Complexity"}
        
        Q3 -- "SIMPLE QUERY (< 50ms)<br/>Redis direct read + template render<br/>0 tokens, deterministic" --> Q4
        Q3 -- "COMPLEX QUERY (needs LLM)<br/>Redis → build context → OpenAI API<br/>Token cost, deterministic = false" --> Q4
        
        Q4["3. CHAT RESPONSE<br/>{ answer, metadata, tokensUsed, timestamp, error }<br/>+ ChatMemoryService.recordInteraction()"]
    end

    Redis -. "Direct Read / Context Build" .-> Q3