# ThingsBoard Bot

An AI-powered chatbot assistant for ThingsBoard IoT platforms. This bot connects to your ThingsBoard tenant, retrieves telemetry and attribute data from your devices, and uses OpenAI to provide insightful answers and real-time alerts.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         User/Browser                                 │
│                   (Chat Widget: index.html)                         │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ HTTP Requests
┌──────────────────────────▼──────────────────────────────────────────┐
│                      Spring Boot Backend                            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │ ChatController│  │AlertController│ │DataController│            │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘              │
│         │                 │                  │                      │
│  ┌──────▼─────────────────▼──────────────────▼───────┐             │
│  │              ChatService (Core Logic)              │             │
│  │  1. Fetch device data (cached)                     │             │
│  │  2. Filter context (reduce tokens)                  │             │
│  │  3. Count tokens (validate)                         │             │
│  │  4. Call OpenAI with prompt + context              │             │
│  │  5. Return response                                │             │
│  └───────────────────────┬─────────────────────────────┘             │
└──────────────────────────┼────────────────────────────────────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
┌───────▼───────┐  ┌──────▼───────┐  ┌──────▼───────┐
│ ThingsBoard   │  │   OpenAI     │  │    MySQL     │
│   Client      │  │   Client     │  │  Database    │
│               │  │              │  │              │
│ - Auth        │  │ - GPT calls  │  │ - Messages   │
│ - Get devices │  │ - Token mgmt │  │ - Converstns │
│ - Telemetry   │  │              │  │              │
└───────────────┘  └──────────────┘  └──────────────┘
```

## How It Works

### 1. Chat Flow
- User sends a question via the floating chat widget
- [`ChatController`](src/main/java/com/seple/ThingsBoard_Bot/controller/ChatController.java) receives the request
- [`ChatService`](src/main/java/com/seple/ThingsBoard_Bot/service/ChatService.java) orchestrates:
  1. **Fetches device data** from ThingsBoard (cached for 60 seconds)
  2. **Filters context** using [`ContextFilterUtil`](src/main/java/com/seple/ThingsBoard_Bot/util/ContextFilterUtil.java) to reduce tokens
  3. **Counts tokens** to validate before OpenAI call
  4. **Calls OpenAI** with system prompt + filtered context + user question
  5. **Returns formatted response**

### 2. Data Flow
- [`ThingsBoardClient`](src/main/java/com/seple/ThingsBoard_Bot/client/ThingsBoardClient.java) handles ThingsBoard REST API
- Supports both **Tenant Admin** (all devices) and **Customer/User** (scoped devices via `X-TB-Token`)
- Device data includes: telemetry, CLIENT_SCOPE attributes, SERVER_SCOPE attributes, SHARED_SCOPE attributes

### 3. Alert Flow
- [`AlertService`](src/main/java/com/seple/ThingsBoard_Bot/service/AlertService.java) polls ThingsBoard for alerts
- Checks battery levels, temperature, connection status
- Can be polled via [`AlertController`](src/main/java/com/seple/ThingsBoard_Bot/controller/AlertController.java)

## Features

- **Context-Aware Q&A:** Ask questions about your IoT devices, their status, health, and recent telemetry.
- **Multi-Device Support:** Retrieve data across multiple devices within your tenant.
- **Real-time Alerts:** Automatically polls for and displays active alerts from ThingsBoard.
- **Floating Chat Widget:** A sleek, "Acid Industrial" themed chat interface.
- **Smart Data Filtering:** Aggressively filters raw JSON data to minimize OpenAI token usage.
- **Caching:** 1-minute caching for device data to optimize performance.
- **Multi-turn Conversations:** Maintains chat history for contextual responses.

## Tech Stack

- **Backend:** Java 21, Spring Boot 4.0.3
- **Database:** MySQL
- **APIs:** ThingsBoard REST API, OpenAI API
- **Frontend:** HTML, CSS (Vanilla), JavaScript (Vanilla)

## Quick Setup

### 1. Prerequisites
- Java 21+
- Maven 3.8+
- MySQL 8.0+
- ThingsBoard account (self-hosted or cloud)
- OpenAI API key

### 2. Configure Application
Edit `src/main/resources/application.properties`:

```properties
# Database
spring.datasource.url=jdbc:mysql://localhost:3306/thingsboard_bot_db
spring.datasource.username=your_db_user
spring.datasource.password=your_db_password

# ThingsBoard
iotchatbot.thingsboard.url=https://your-thingsboard-url.com
iotchatbot.thingsboard.username=your_tb_username
iotchatbot.thingsboard.password=your_tb_password

# OpenAI
iotchatbot.openai.api-key=your_openai_api_key
```

### 3. Create Database
```sql
CREATE DATABASE thingsboard_bot_db;
```

### 4. Run the Application
```bash
# Using Maven
mvn spring-boot:run

# Or build and run
mvn clean package
java -jar target/ThingsBoard-Bot-0.0.1-SNAPSHOT.jar
```

### 5. Access the Application
- Open `http://localhost:8080` in your browser
- The chat widget appears in the bottom-right corner

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/chat/ask` | POST | Send a question to the chatbot |
| `/api/v1/alerts/check` | GET | Check for active device alerts |
| `/api/v1/data/full` | GET | Get full device data (unfiltered) |
| `/api/v1/data/all-devices` | GET | Get list of all devices |

### Authentication
For user-scoped data, include the `X-TB-Token` header with a valid ThingsBoard customer/user token:
```bash
curl -H "X-TB-Token: your_user_token" http://localhost:8080/api/v1/data/full
```

## Project Structure

```
src/main/java/com/seple/ThingsBoard_Bot/
├── ThingsBoardBotApplication.java    # Main entry point
├── client/                           # External API clients
│   ├── ThingsBoardClient.java        # ThingsBoard REST API
│   ├── UserAwareThingsBoardClient.java # User-scoped TB API
│   └── OpenAIClient.java             # OpenAI API
├── config/                           # Spring configuration
│   ├── ThingsBoardConfig.java        # ThingsBoard properties
│   ├── OpenAIConfig.java             # OpenAI properties
│   └── ChatbotConfig.java            # Chatbot settings
├── controller/                       # REST controllers
│   ├── ChatController.java           # /api/v1/chat
│   ├── AlertController.java          # /api/v1/alerts
│   └── DataController.java           # /api/v1/data
├── service/                          # Business logic
│   ├── ChatService.java              # Main chatbot logic
│   ├── AlertService.java             # Alert checking
│   ├── DataService.java              # Device data fetching
│   ├── UserDataService.java          # User-scoped data
│   └── ChatMemoryService.java        # Conversation history
├── util/                             # Utilities
│   ├── ContextFilterUtil.java        # Data filtering
│   └── TokenCounterService.java      # Token counting
├── model/                            # Data models
│   ├── dto/                          # Data transfer objects
│   └── entity/                       # JPA entities
└── repository/                       # Database repositories
```

## Troubleshooting

### Check ThingsBoard Connection
- Verify credentials in `application.properties`
- Check ThingsBoard URL is accessible
- Ensure user has permission to access devices

### Check OpenAI API
- Verify API key is valid
- Check API key has sufficient credits
- Verify model name is correct

### Check Database
- Ensure MySQL is running
- Verify database credentials
- Check database user has proper permissions
