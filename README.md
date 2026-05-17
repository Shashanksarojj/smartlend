# SmartLend — AI-Powered Loan Management Platform

A production-grade full-stack fintech application built with microservices, AI credit scoring, async messaging, and a fully typed React + TypeScript frontend.

> Portfolio project demonstrating Fintech SDE skills: distributed systems, JWT security, ML-powered decisioning, event-driven architecture, and modern frontend engineering.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│          React 18 + TypeScript + Tailwind CSS                │
│        Applicant Portal  ──  Admin Portal (role-based)       │
└──────────┬───────────────────────────┬───────────────────────┘
           │ :8081                     │ :8082
┌──────────▼──────────┐   ┌───────────▼──────────────────────┐
│    user-service      │   │         loan-service              │
│  Spring Boot 3.2     │   │       Spring Boot 3.2             │
│  JWT · BCrypt · Redis│   │  EMI Engine · RabbitMQ publisher  │
└──────────┬──────────┘   └──────┬──────────────┬────────────┘
           │ PostgreSQL           │ RabbitMQ      │ HTTP
      (userdb:5432)               │               │
                        ┌─────────▼────┐  ┌───────▼──────────┐
                        │notification- │  │   ai-scoring      │
                        │   service    │  │  Python FastAPI   │
                        │ Spring Boot  │  │  Rule-based ML    │
                        │  SendGrid    │  │  Credit Score     │
                        └─────────────┘  └──────────────────-┘
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Frontend | React 18, TypeScript 5, Tailwind CSS 3, React Router v6, Recharts, Axios |
| Backend | Java 17, Spring Boot 3.2, Spring Security, JJWT 0.12.3 |
| AI Service | Python 3.11, FastAPI, NumPy |
| Database | PostgreSQL 15 (one per service), Redis 7 |
| Messaging | RabbitMQ 3 (TopicExchange, async loan events) |
| Email | SendGrid (`sendgrid-java:4.10.2`) — HTML transactional email |
| DevOps | Docker, Docker Compose |
| Monitoring | Prometheus |
| API Docs | SpringDoc OpenAPI (Swagger UI) |

---

## Features

- **AI Credit Scoring** — Rule-based model (DTI ratio, employment type, tenure, income band) produces a 300–900 credit score and risk label (LOW / MEDIUM / HIGH) in milliseconds
- **JWT Role-based Auth** — APPLICANT and ADMIN roles; same portal, different views; admin endpoints protected at filter chain level
- **EMI Engine** — Standard reducing-balance amortization on loan approval; full installment schedule with principal, interest, and balance per month
- **Async Notifications** — RabbitMQ TopicExchange decouples loan approval/rejection events from delivery; notification-service uses an extensible `NotificationChannel` interface — SendGrid email is live, SMS (Twilio) and Push (Firebase) are stubbed and ready to enable
- **HTML Transactional Emails** — Branded HTML email templates for loan approval, rejection, and EMI reminders delivered via SendGrid; plain-text fallback included
- **Structured Logging** — Logback rolling files + MDC `requestId` propagated across every log line; `X-Request-Id` response header for end-to-end tracing
- **Global Error Format** — All 4xx/5xx responses (including Spring Security 401/403) return consistent JSON; never HTML or empty body
- **Admin Panel** — Approve/reject loans with custom interest rate and admin note; inline form per row; optimistic UI update
- **Post-submission Status** — Loan application result shown immediately with AI credit score bar, risk badge, and next-steps guide
- **EMI Schedule** — Recharts line chart + full amortization table per loan

---

## Quick Start

### Prerequisites
- Docker & Docker Compose
- Node.js 18+ (for frontend dev)
- A SendGrid account with an API key ([free tier](https://sendgrid.com) is sufficient)

### 1. Configure environment variables

```bash
cp .env.example .env   # or edit .env directly
```

Set your SendGrid key in `.env`:

```env
SENDGRID_API_KEY=SG.your-api-key-here
SENDGRID_FROM_EMAIL=noreply@yourdomain.com
SENDGRID_FROM_NAME=SmartLend
```

> **Note:** The sender address must be verified in your SendGrid account (single sender verification or domain authentication).

### 2. Start all backend services

```bash
cd smartlend
docker compose up -d
```

### 3. Start frontend

```bash
cd frontend
npm install --legacy-peer-deps
npm start
```

Open [http://localhost:3000](http://localhost:3000)

### 4. Create your first admin

Register a user normally, then promote via DB:

```bash
docker exec -it smartlend-user-db psql -U smartlend -d userdb \
  -c "UPDATE users SET role='ADMIN' WHERE email='admin@example.com';"
```

Or use the API (requires an existing admin token):
```
POST http://localhost:8081/api/auth/admin/create-admin
Authorization: Bearer <admin_token>
```

---

## Service URLs

| Service | URL | Credentials |
|---|---|---|
| Frontend | http://localhost:3000 | — |
| User Service | http://localhost:8081/swagger-ui.html | — |
| Loan Service | http://localhost:8082/swagger-ui.html | — |
| AI Scoring | http://localhost:8000/docs | — |
| RabbitMQ UI | http://localhost:15672 | smartlend / smartlend123 |
| Prometheus | http://localhost:9090 | — |

---

## API Reference

### User Service — `http://localhost:8081`

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/register` | Public | Register applicant; returns JWT + profile |
| POST | `/api/auth/login` | Public | Login; returns JWT + profile |
| GET | `/api/auth/profile/{userId}` | Public | Internal use by loan-service |
| POST | `/api/auth/admin/create-admin` | Bearer (ADMIN) | Create a new admin user |

### Loan Service — `http://localhost:8082`

| Method | Endpoint | Required Headers | Description |
|---|---|---|---|
| POST | `/api/loans/apply` | `X-User-Id`, `X-Monthly-Income`, `X-Employment-Type` | Apply for loan; triggers AI scoring |
| GET | `/api/loans/my` | `X-User-Id` | Get caller's loan history |
| GET | `/api/loans/{loanId}/emi-schedule` | — | Full amortization table |
| GET | `/api/loans/admin/all` | Bearer (ADMIN) | All loans in the system |
| PUT | `/api/loans/admin/{loanId}/decision` | Bearer (ADMIN) | Approve or reject a loan |
| GET | `/api/loans/admin/summary` | Bearer (ADMIN) | Portfolio summary stats |

### AI Scoring — `http://localhost:8000`

| Method | Endpoint | Description |
|---|---|---|
| POST | `/score` | Returns `credit_score`, `risk_label`, `suggested_rate`, `recommendation` |
| GET | `/health` | Health check |

### Notification Service — `http://localhost:8083`

Consumes from RabbitMQ queue `loan.events.queue`. Also exposes a webhook endpoint for Meta.

**Supported event types** (sent by loan-service on admin decision):

| Event type | Trigger | Channels |
|---|---|---|
| `APPROVED` | Admin approves loan | Email (HTML) + WhatsApp template |
| `REJECTED` | Admin rejects loan | Email (HTML) + WhatsApp template |
| `EMI_DUE` | (future) Scheduled reminder | Email (HTML) + WhatsApp template |

**Active channels:**

| Channel | Provider | Free tier | Status |
|---|---|---|---|
| Email | SendGrid | 100 emails/day | ✅ Live |
| WhatsApp | Meta Cloud API | 1,000 conversations/month | ✅ Live (enable via env) |
| SMS | Twilio | $15 trial | Stub (ready to wire) |
| Push | Firebase FCM | Unlimited | Stub (ready to wire) |

**WhatsApp webhook endpoints:**

| Method | Endpoint | Description |
|---|---|---|
| GET | `/webhook/whatsapp` | Meta verification challenge (called once during setup) |
| POST | `/webhook/whatsapp` | Delivery status updates (sent / delivered / read / failed) and incoming messages |

**WhatsApp setup:**
1. Create Meta App → add WhatsApp product → copy Phone Number ID and Access Token
2. Create 3 UTILITY templates in Meta Business Manager (`loan_approved`, `loan_rejected`, `emi_due`)
3. Register webhook: Meta Console → WhatsApp → Configuration → Callback URL = `https://<host>/webhook/whatsapp`, Verify token = `WHATSAPP_WEBHOOK_VERIFY_TOKEN`
4. Subscribe to `messages` webhook field
5. Add recipient numbers under API Setup (sandbox: max 5 test numbers)
6. Set `NOTIFICATION_WHATSAPP_ENABLED=true` once templates are approved

**WhatsApp template parameters** (must match order in Meta Business Manager):
```
loan_approved → {{1}} name  {{2}} amount  {{3}} emi  {{4}} tenure months
loan_rejected → {{1}} name  {{2}} loanId
emi_due       → {{1}} name  {{2}} amount  {{3}} dueDate
```

**Production webhook note:** On Render the URL is permanent — update Meta's webhook config once from ngrok → Render URL. Free tier cold starts (~30s) are fine; Meta retries on timeout so no events are lost.

**Adding a new notification channel:**
1. Create a class implementing `NotificationChannel` in a sub-package of `channel/`
2. Annotate with `@Component`
3. Toggle via `notification.channels.<name>.enabled` config / env var

No changes to dispatcher or consumer needed.

---

## Error Response Format

Every error — including Spring Security 401/403 — returns consistent JSON:

```json
{
  "status": 403,
  "error": "FORBIDDEN",
  "message": "You do not have permission to access this resource. Required role may be ADMIN.",
  "path": "/api/loans/admin/all",
  "timestamp": "2026-05-17T08:30:00Z"
}
```

Validation errors include a `fields` map:

```json
{
  "status": 422,
  "error": "VALIDATION_ERROR",
  "message": "Request validation failed. Check the 'fields' map for details.",
  "fields": {
    "principalAmount": "must be greater than or equal to 10000",
    "purpose": "must not be blank"
  },
  "path": "/api/loans/apply",
  "timestamp": "2026-05-17T08:30:00Z"
}
```

| Code | Error Key | Meaning |
|---|---|---|
| 400 | `BAD_REQUEST` | Business rule violation |
| 400 | `MISSING_HEADER` | Required header absent (e.g. `X-User-Id`) |
| 400 | `INVALID_PARAMETER` | Path/query param wrong type |
| 401 | `UNAUTHORIZED` | No token or token expired |
| 403 | `FORBIDDEN` | Valid token but insufficient role |
| 404 | `NOT_FOUND` | Endpoint does not exist |
| 422 | `VALIDATION_ERROR` | Bean validation failed |
| 500 | `INTERNAL_SERVER_ERROR` | Unexpected server error |

---

## Project Structure

```
smartlend/
├── docker-compose.yml
├── CLAUDE.md                          # AI assistant context file
├── SmartLend.postman_collection.json  # Full Postman collection (auto token injection)
├── SmartLend.postman_environment.json # Postman environment variables
├── monitoring/
│   └── prometheus.yml
│
├── user-service/                      # Spring Boot — Auth & User management
│   └── src/main/java/com/smartlend/user/
│       ├── config/        SecurityConfig.java (JWT filter + CORS + error handlers)
│       ├── controller/    AuthController.java
│       ├── service/       AuthService.java
│       ├── model/         User.java (Role, KycStatus, EmploymentType enums)
│       ├── security/      JwtUtil.java
│       ├── filter/        RequestLoggingFilter.java (MDC requestId)
│       ├── exception/     GlobalExceptionHandler.java
│       └── dto/           AuthDto.java
│
├── loan-service/                      # Spring Boot — Loans, EMI, Decisions
│   └── src/main/java/com/smartlend/loan/
│       ├── config/        LoanSecurityConfig.java, RabbitMQConfig.java
│       ├── controller/    LoanController.java
│       ├── service/       LoanService.java (AI call + EMI engine + RabbitMQ)
│       ├── model/         Loan.java, EmiPayment.java
│       ├── client/        AiScoringClient.java, UserServiceClient.java
│       ├── filter/        RequestLoggingFilter.java
│       └── exception/     GlobalExceptionHandler.java
│
├── notification-service/              # Spring Boot — Extensible Async Notifications
│   └── src/main/java/com/smartlend/notification/
│       ├── config/        RabbitMQConfig.java (queue, JSON converter, listener factory)
│       ├── channel/       NotificationChannel.java (interface)
│       │                  NotificationPayload.java (record — includes recipientPhone)
│       │                  NotificationDispatcher.java (auto-discovers channels)
│       ├── channel/email/ SendGridEmailChannel.java (HTML email via SendGrid)
│       ├── channel/whatsapp/ WhatsAppChannel.java (Meta Cloud API template messages)
│       ├── channel/sms/   SmsChannel.java (Twilio stub — disabled)
│       ├── channel/push/  PushChannel.java (Firebase stub — disabled)
│       ├── handler/       LoanNotificationHandler.java (HTML templates + WA params per event)
│       ├── consumer/      LoanEventConsumer.java (RabbitMQ listener)
│       └── webhook/       WhatsAppWebhookController.java (Meta verification + delivery status)
│
├── ai-scoring/                        # Python FastAPI — Credit Scoring Engine
│   └── app/main.py                   (DTI + employment + tenure + income scoring)
│
└── frontend/                          # React 18 + TypeScript + Tailwind CSS
    └── src/
        ├── types/         index.ts   (all interfaces matching backend DTOs exactly)
        ├── constants/     index.ts   (status configs with Tailwind badge classes)
        ├── utils/         cn.ts, formatters.ts
        ├── services/      api.ts     (typed Axios instances, interceptors)
        ├── context/       AuthContext.tsx
        ├── hooks/         useLoans.ts, useAdminLoans.ts
        ├── components/
        │   ├── ui/        Button, Input, Select, Badge, Alert
        │   └── layout/    Layout.tsx (role-aware sidebar)
        └── pages/
            ├── Landing.tsx
            ├── auth/      Login.tsx, Register.tsx
            ├── applicant/ Dashboard.tsx, ApplyLoan.tsx, EmiSchedule.tsx
            └── admin/     AdminDashboard.tsx
```

---

## Postman Collection

Import `SmartLend.postman_collection.json` and `SmartLend.postman_environment.json`.

Login and Register requests automatically save the JWT token to collection variables — all subsequent requests use it without any manual copy-paste.

---

## Deployment (Free Tier)

| Component | Platform |
|---|---|
| Frontend | Vercel |
| Spring Boot services | Render |
| PostgreSQL | Neon |
| Redis | Upstash |
| RabbitMQ | CloudAMQP |
| Monitoring | Grafana Cloud |

---

Built as a portfolio project demonstrating microservices architecture, fintech domain knowledge, AI integration, and production-grade engineering practices.
