# SAI Bot Evolution: Roadmap to Production Architecture v4.0 🚀

## 1. Executive Summary
This proposal outlines the transformation of the current SAI Prototype into the **v4.0 Production Architecture**. We are moving from a "Search-based" chatbot to a "Deterministic-first" hybrid platform capable of monitoring up to 50,000 branches with sub-10ms response times.

---

## 2. Core Architectural Shifts

| Feature | Current Prototype (v1.0) | Production Target (v4.0) |
| :--- | :--- | :--- |
| **Data Persistence** | Transient (Live Fetch Only) | Durable (TimescaleDB Event Store) |
| **Tenancy** | Single Customer | Multi-Bank Isolation (`customer_id`) |
| **Query Logic** | AI-Reasoning (Slow) | Rule-Based Router + LLM (Fast) |
| **Hierarchy** | Flat (Branch Name) | Dynamic Tree (HO -> ZO -> RO -> Branch) |
| **Recovery** | Restart/Re-fetch | Event Replay Service |

---

## 3. The "Hybrid Brain" Strategy (C2 Resolution)
To solve the CTO's concern about LLM costs and latency, we will implement a **Query Router**:

1.  **Simple Queries (<50ms):** "How many branches offline?" or "Status of Branch X" are answered by reading pre-calculated counters in **Redis**. No LLM is used.
2.  **Complex Queries (<2s):** "Why is this branch failing?" or "Compare North vs East region" use the **OpenAI LLM** to analyze historical event logs from TimescaleDB.

---

## 4. Multi-Customer Isolation (CR3 Resolution)
The system will be "Bank-Agnostic." We will add a `customer_id` to every layer:
*   **JWT Token:** Includes `customer_id: "BOI"`.
*   **Redis:** Keys are namespaced as `BOI:branch:state:042`.
*   **Database:** Row-Level Security (RLS) ensures a BOI analyst can never see SBI data.

---

## 5. Implementation Phases

### **Phase 1: The Foundation (Current Focus)**
*   Define the **Dynamic Hierarchy Tree** in PostgreSQL.
*   Update `ThingsBoardClient` to include `customer_id` and `node_id` in all data fetches.
*   Implement the initial **Rule-Based Router** for basic status counts.

### **Phase 2: The Event Store**
*   Deploy **TimescaleDB** to store every state change (Gateway Online -> Offline).
*   Build the **Audit Trail API** for compliance reporting.

### **Phase 3: The Replay Service**
*   Develop the service to "rebuild" the Redis cache from the database if the server crashes.
*   Transition to **Redis Sentinel** for high availability.

---

## 6. Strategic Benefits
1.  **Zero Hallucination:** Mathematical counts are 100% accurate because they come from Redis, not AI "guessing."
2.  **Extreme Speed:** Simple status checks are now instant (<10ms).
3.  **Audit Ready:** Every question and every device change is logged for security compliance.

---
*Prepared by SAI Architectural Development Team.*
