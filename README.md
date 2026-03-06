# ThingsBoard Bot

An AI-powered chatbot assistant for ThingsBoard IoT platforms. This bot connects to your ThingsBoard tenant, retrieves telemetry and attribute data from your devices, and uses OpenAI to provide insightful answers and real-time alerts.

## Features

- **Context-Aware Q&A:** Ask questions about your IoT devices, their status, health, and recent telemetry.
- **Multi-Device Support:** Retrieve data across multiple devices within your tenant.
- **Real-time Alerts:** Automatically polls for and displays active alerts from ThingsBoard.
- **Floating Chat Widget:** A sleek, "Acid Industrial" themed chat interface that can be embedded into ThingsBoard dashboards.
- **Smart Data Filtering:** Aggressively filters raw JSON data from ThingsBoard to minimize OpenAI token usage while retaining essential context.
- **Caching:** 1-minute caching for device data to optimize performance and reduce API load.

## Tech Stack

- **Backend:** Java 21, Spring Boot 4.0.3
- **Database:** MySQL
- **APIs:** ThingsBoard REST API, OpenAI API
- **Frontend:** HTML, CSS (Vanilla), JavaScript (Vanilla)

## Setup and Installation

1. Clone the repository.
2. Ensure you have Java 21 and Maven installed.
3. Configure your `application.properties` with your database credentials, ThingsBoard URL, Tenant credentials, and OpenAI API Key.
4. Run the application: `mvn spring-boot:run`

## Usage

The application exposes a web interface at `http://localhost:8080`. The chatbot widget will appear in the bottom right corner, ready to assist you.

## Endpoints

- `POST /api/v1/chat/ask`: Interact with the chatbot.
- `GET /api/v1/alerts/check`: Check for active device alerts.
- `GET /api/v1/data/full`: Retrieve full, unfiltered data for a specific device.
- `GET /api/v1/data/all-devices`: Retrieve a summary of all devices (count, ID, name).
