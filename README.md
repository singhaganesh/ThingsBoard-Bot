# ThingsBoard Bot 🤖

> **AI-Powered Chatbot for IoT Device Management**

A intelligent chatbot that connects to your ThingsBoard IoT platform and uses OpenAI to answer questions about your devices in plain English. No technical knowledge required!

---

## 📖 Choose Your Guide

- **👥 [For Non-Technical Users](#-for-non-technical-users)** - Simple explanation of what this does
- **👨‍💻 [For Developers](#-for-developers)** - Technical setup and architecture details

---

# 👥 For Non-Technical Users

## What Is This? 🤔

Imagine you have many IoT devices (sensors, thermostats, cameras, etc.) spread across your facility. Instead of logging into a complicated dashboard, you can simply **ask questions in a chat window**:

- "How many devices are offline right now?"
- "What's the current temperature in Building A?"
- "Which sensor has low battery?"
- "What happened to Device X yesterday?"

The chatbot will read data from all your devices and give you the answer **immediately**.

## The Problem It Solves ✅

| Before | After |
|--------|-------|
| Manually log into dashboard to check each device | Ask chatbot one question, get instant answer |
| Hard to understand complex technical data | Chatbot explains in simple terms |
| Finding device problems takes time | Chatbot alerts you to issues immediately |
| Hard to track historical events | Chatbot remembers past events |

## How It Works (Simple Version) 🔄

```
You type a question in the chat box
         ↓
Chatbot reads all device data from ThingsBoard
         ↓
AI assistant (OpenAI) understands your question
         ↓
AI looks through device data for the answer
         ↓
Chatbot explains the answer in simple English
         ↓
You get your answer instantly!
```

## Key Features 🌟

✨ **Easy Chat Interface** - Just type your question, no training needed  
📊 **Real-Time Data** - Always sees your latest device information  
🧠 **Intelligent Answers** - Understands context and gives meaningful responses  
💬 **Conversation History** - Remembers previous questions  
🔒 **Secure** - Only shows data you have permission to see  

## Setup for Users (Simple) 🚀

Your IT team will set this up for you. Once they do:

1. **Open this URL in your browser:** `http://localhost:8080` (your IT will give you the actual URL)
2. **Look for the chat box** in the bottom-right corner
3. **Type your question** and press Enter
4. **Get your answer!** 💡

That's it! You don't need to know anything about databases, APIs, or code.

---

# 👨‍💻 For Developers

## 📌 Project Overview

A **context-augmented generation (CAG) chatbot** built on Spring Boot that intelligently routes IoT device queries to live ThingsBoard telemetry or historical analysis. The system fetches real-time device data, filters it for token efficiency, and leverages OpenAI to provide context-aware answers.

## 🎯 Purpose & Problem Statement

| Aspect | Details |
|--------|---------|
| **Purpose** | Provide a natural language interface to IoT device data via ThingsBoard without requiring technical dashboard skills |
| **Problem Solved** | Eliminates manual dashboard navigation; enables historical context retrieval; reduces token overhead through smart filtering |
| **Use Case** | Facility managers, operations teams querying device status, anomalies, and trends |

## 🛠️ Tech Stack

| Layer | Technology |
|-------|-----------|
| **Backend** | Java 21, Spring Boot 4.0.3, Spring Data JPA, Spring Cache |
| **Database** | MySQL 8.0+ (conversation history, entities) |
| **APIs** | ThingsBoard REST API, OpenAI Chat API |
| **Frontend** | HTML5, Vanilla CSS3, Vanilla JavaScript |
| **Build** | Maven 3.8+, Java Compiler |

## 🏗️ System Architecture (Brief)

```
┌─ Browser ─────────────┐
│ Chat Widget UI        │
└──────────┬────────────┘
           │ HTTP
┌──────────▼──────────────────────┐
│ Spring Boot Backend             │
│ ┌────────────────────────────┐  │
│ │ ChatService (Orchestrator) │  │
│ │ 1. Fetch device data       │  │
│ │ 2. Filter context          │  │
│ │ 3. Call OpenAI             │  │
│ │ 4. Return response         │  │
│ └────────────────────────────┘  │
└──────────┬──────────┬──────────┬─┘
           │          │          │
     ┌─────▼─┐  ┌────▼───┐  ┌──▼──────┐
     │Things │  │ OpenAI │  │ MySQL   │
     │Board  │  │ API    │  │ DB      │
     └───────┘  └────────┘  └─────────┘
```

## 📋 Features

- ✅ Context-aware Q&A from live telemetry
- ✅ Multi-device support with tenant/customer/user scopes
- ✅ 60-second device data caching
- ✅ Token counting & context filtering
- ✅ Multi-turn conversation memory
- ✅ Real-time vs. historical data routing
- ✅ Floating chat widget (Acid Industrial theme)

## 📦 Project Structure

```
src/main/java/com/seple/ThingsBoard_Bot/
├── client/              # External API integrations
│   ├── ThingsBoardClient.java
│   ├── UserAwareThingsBoardClient.java
│   └── OpenAIClient.java
├── config/              # Spring beans & properties
├── controller/          # REST endpoints
├── service/             # Business logic
│   ├── ChatService.java (core orchestrator)
│   ├── DataService.java
│   ├── ChatMemoryService.java
│   └── ChartService.java
├── util/                # Helpers
│   ├── ContextFilterUtil.java
│   └── TokenCounterService.java
├── model/               # DTOs & Entities
├── repository/          # JPA repositories
└── exception/           # Custom exceptions
```

## ⚙️ Prerequisites

- **Java 21+** (OpenJDK or Oracle JDK)
- **Maven 3.8+**
- **MySQL 8.0+** (running locally or remote)
- **ThingsBoard account** (cloud or self-hosted)
- **OpenAI API key** (paid or trial)
- **Git** (for cloning)

## 🚀 Setup & Run Locally

### Step 1: Clone Repository
```bash
git clone https://github.com/your-org/ThingsBoard-Bot.git
cd ThingsBoard-Bot
```

### Step 2: Create MySQL Database
```bash
mysql -u root -p
```
```sql
CREATE DATABASE thingsboard_bot_db CHARACTER SET utf8mb4;
CREATE USER 'tb_bot'@'localhost' IDENTIFIED BY 'secure_password';
GRANT ALL PRIVILEGES ON thingsboard_bot_db.* TO 'tb_bot'@'localhost';
FLUSH PRIVILEGES;
```

### Step 3: Configure Application
Edit `src/main/resources/application.properties`:

```properties
# Spring
spring.application.name=ThingsBoard-Bot
server.port=8080

# Database
spring.datasource.url=jdbc:mysql://localhost:3306/thingsboard_bot_db
spring.datasource.username=tb_bot
spring.datasource.password=secure_password
spring.jpa.hibernate.ddl-auto=update

# ThingsBoard
iotchatbot.thingsboard.url=https://your-thingsboard-url.com
iotchatbot.thingsboard.username=admin@thingsboard.io
iotchatbot.thingsboard.password=your_tb_password

# OpenAI
iotchatbot.openai.api-key=sk-your-api-key-here
iotchatbot.openai.model=gpt-4
```

### Step 4: Build & Run
```bash
# Clean build
mvn clean install

# Run with Maven
mvn spring-boot:run

# OR package & run JAR
mvn clean package
java -jar target/ThingsBoard-Bot-0.0.1-SNAPSHOT.jar
```

### Step 5: Access Application
- **Open browser:** `http://localhost:8080`
- **Chat widget:** Bottom-right corner

## 📡 API Documentation

### Chat Endpoint
```http
POST /api/v1/chat/ask
Content-Type: application/json

{
  "question": "How many devices are offline?",
  "userId": "user123"
}
```

**Response:**
```json
{
  "response": "2 out of 10 devices are currently offline...",
  "metadata": {
    "tokensUsed": 342,
    "executionTime": 1250
  }
}
```

### Data Endpoints
```http
GET /api/v1/data/all-devices
GET /api/v1/data/full
Header: X-TB-Token: your_customer_token (optional)
```

## 👥 User Flows

### Flow 1: Basic Q&A
```
User → Question in chat → ChatService fetches live data → 
OpenAI generates response → User sees answer
```

### Flow 2: Multi-Turn Conversation
```
Q1: "What's device X status?"
→ [System stores in ChatMemoryService]
Q2: "Show me its history"
→ [System includes previous context]
A2: "Yesterday it was..."
```

### Flow 3: User-Scoped Access
```
Customer provides X-TB-Token → 
UserAwareThingsBoardClient filters data to customer scope →
Only authorized device data shown
```

## ✅ Testing

### Unit Test Locations
```
src/test/java/com/seple/ThingsBoard_Bot/
├── service/ChatServiceTest.java
├── util/TokenCounterServiceTest.java
├── util/ContextFilterUtilTest.java
└── client/OpenAIClientTest.java
```

### Run Tests
```bash
# All tests
mvn test

# Specific test class
mvn test -Dtest=ChatServiceTest

# With coverage
mvn test jacoco:report
```

### Manual Testing (cURL)
```bash
# Test chat endpoint
curl -X POST http://localhost:8080/api/v1/chat/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "List all devices", "userId": "test"}'

# Test data endpoint
curl http://localhost:8080/api/v1/data/all-devices

# With authentication
curl -H "X-TB-Token: your_token" http://localhost:8080/api/v1/data/full
```

## 🔧 Troubleshooting

| Issue | Solution |
|-------|----------|
| **Build fails** | `mvn clean install -U` (update dependencies) |
| **ThingsBoard connection error** | Verify credentials, check URL accessibility |
| **OpenAI API error** | Check API key validity, confirm billing, verify model name |
| **Database connection refused** | Ensure MySQL running, verify credentials in properties |
| **Chat widget not showing** | Clear browser cache, check `index.html` in `src/main/resources/static` |

## 📝 Environment Variables (Alternative to properties)
```bash
export TB_URL="https://your-thingsboard-url.com"
export TB_USER="admin@thingsboard.io"
export TB_PASS="password"
export OPENAI_KEY="sk-..."
export DB_URL="jdbc:mysql://localhost:3306/thingsboard_bot_db"
export DB_USER="tb_bot"
export DB_PASS="secure_password"
```

---

## 📞 Support

For issues, check troubleshooting section or create an issue in the repository.

**Last Updated:** 2026-03-11  
**Version:** 0.0.1-SNAPSHOT
