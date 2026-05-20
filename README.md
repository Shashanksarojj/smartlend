# SmartLend — AI-Powered Loan Management Platform

A production-grade full-stack fintech application built with microservices, AI credit scoring, async messaging, and a fully typed React + TypeScript frontend.

> Portfolio project demonstrating Fintech SDE skills: distributed systems, JWT security, ML-powered decisioning, event-driven architecture, and modern frontend engineering.

**Live:** [smartlend-ebon.vercel.app](https://smartlend-ebon.vercel.app)

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
│  RabbitMQ publisher  │   │  (LOAN_APPLIED, APPROVED,         │
│  (USER_REGISTERED)   │   │   REJECTED events)                │
└──────────┬──────────┘   └──────┬──────────────┬────────────┘
           │ Neon PostgreSQL      │ RabbitMQ      │ HTTP
      (userdb)                    │ CloudAMQP     │
                        ┌─────────▼────┐  ┌───────▼──────────┐
                        │notification- │  │   ai-scoring      │
                        │   service    │  │  Python FastAPI   │
                        │ Spring Boot  │  │  Rule-based ML    │
                        │  SendGrid    │  │  Credit Score     │
                        │  WhatsApp    │  │  300–900 range    │
                        └─────────────┘  └──────────────────-┘
                        Neon PostgreSQL (loandb) · Upstash Redis
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Frontend | React 18, TypeScript 5, Tailwind CSS 3, React Router v6, Recharts, Axios |
| Backend | Java 17, Spring Boot 3.2, Spring Security, JJWT 0.12.3 |
| AI Service | Python 3.11, FastAPI, NumPy |
| Database | PostgreSQL 15 via Neon (one DB per service), Redis via Upstash |
| Messaging | RabbitMQ via CloudAMQP (TopicExchange, async events) |
| Email | SendGrid (`sendgrid-java:4.10.2`) — HTML transactional email |
| DevOps | Docker, Docker Compose |
| Deployment | Railway (backend), Vercel (frontend) |
| Monitoring | Prometheus |
| API Docs | SpringDoc OpenAPI (Swagger UI) |

---

## Features

- **AI Credit Scoring** — Rule-based model (DTI ratio, employment type, tenure, income band) produces a 300–900 credit score and risk label (LOW / MEDIUM / HIGH) in milliseconds
- **HttpOnly Cookie Auth** — JWT served as `HttpOnly; Secure; SameSite` cookie (not localStorage); immune to XSS token theft. In-memory token for cross-domain loan-service calls. Session restored on page refresh via `GET /api/auth/me` using the persisted cookie.
- **JWT Role-based Auth** — APPLICANT and ADMIN roles; same portal, different views; admin endpoints protected at filter chain level
- **EMI Engine** — Standard reducing-balance amortization on loan approval; full installment schedule with principal, interest, and balance per month
- **Transactional Email at Every Step** — Emails sent on registration (welcome), loan application received (with AI credit score), approval, and rejection via SendGrid HTML templates
- **Async Notifications** — RabbitMQ TopicExchange decouples all events from delivery; notification-service uses an extensible `NotificationChannel` interface — SendGrid email and WhatsApp (Meta Cloud API) are live, SMS (Twilio) and Push (Firebase) are stubbed
- **Structured Logging** — Logback rolling files + MDC `requestId` propagated across every log line; `X-Request-Id` response header for end-to-end tracing
- **Global Error Format** — All 4xx/5xx responses (including Spring Security 401/403) return consistent JSON; never HTML or empty body
- **Admin Panel** — Approve/reject loans with custom interest rate and admin note; inline form per row; optimistic UI update
- **Post-submission Status** — Loan application result shown immediately with AI credit score bar, risk badge, and next-steps guide
- **EMI Schedule** — Recharts line chart + full amortization table per loan

---

## Quick Start

### Prerequisites
- Docker & Docker Compose
- Node.js 18+
- Accounts on: [Neon](https://neon.tech) (PostgreSQL), [CloudAMQP](https://cloudamqp.com) (RabbitMQ), [Upstash](https://upstash.com) (Redis), [SendGrid](https://sendgrid.com) (email)

### 1. Configure environment variables

```bash
cp .env.example .env
```

Fill in your credentials in `.env` — see `.env.example` for all required variables.

> The sender address in `SENDGRID_FROM_EMAIL` must be verified in your SendGrid account (single sender verification or domain authentication).

### 2. Start all backend services

```bash
docker compose up -d
```

Services connect to your cloud providers (Neon, CloudAMQP, Upstash) via `.env`.

### 3. Start frontend

```bash
cd frontend
npm install --legacy-peer-deps
npm start
```

Open [http://localhost:3000](http://localhost:3000)

### 4. Create your first admin

Register a user, then run this SQL in the **Neon SQL editor**:

```sql
UPDATE users SET role='ADMIN' WHERE email='admin@example.com';
```

Or use the API (requires an existing admin cookie session):
```
POST /api/auth/admin/create-admin
Cookie: auth_token=<jwt>
```

### 5. Set cookie env vars on Railway (production only)

On the Railway **user-service** deployment, add:
```
COOKIE_SECURE=true
COOKIE_SAME_SITE=None
```
These are required for cross-site cookie delivery (Vercel → Railway). Local dev uses `SameSite=Lax` by default.

---

## Production URLs

| Component | URL |
|---|---|
| Frontend | https://smartlend-ebon.vercel.app |
| user-service | https://user-service-smartlend.up.railway.app |
| loan-service | https://loan-service-smartlend.up.railway.app |
| notification-service | https://notification-service-smarlend.up.railway.app |
| ai-scoring | https://ai-scoring-smartlend.up.railway.app |

**Health checks:**
```
https://user-service-smartlend.up.railway.app/actuator/health
https://loan-service-smartlend.up.railway.app/actuator/health
https://ai-scoring-smartlend.up.railway.app/health
https://ai-scoring-smartlend.up.railway.app/docs
```

---

## API Reference

### User Service — `http://localhost:8081`

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/register` | Public | Register applicant; sets HttpOnly `auth_token` cookie + returns profile; triggers welcome email |
| POST | `/api/auth/login` | Public | Login; sets HttpOnly `auth_token` cookie + returns profile |
| POST | `/api/auth/logout` | Public | Clears the `auth_token` cookie (maxAge=0) |
| GET | `/api/auth/me` | Cookie | Restore session — validates cookie, returns fresh profile + token |
| GET | `/api/auth/profile/{userId}` | Public | Internal use by loan-service |
| POST | `/api/auth/admin/create-admin` | Cookie (ADMIN) | Create a new admin user |

### Loan Service — `http://localhost:8082`

| Method | Endpoint | Required Headers | Description |
|---|---|---|---|
| POST | `/api/loans/apply` | `X-User-Id`, `X-Monthly-Income`, `X-Employment-Type` | Apply for loan; triggers AI scoring + confirmation email |
| GET | `/api/loans/my` | `X-User-Id` | Get caller's loan history |
| GET | `/api/loans/{loanId}/emi-schedule` | — | Full amortization table |
| GET | `/api/loans/admin/all` | Bearer (ADMIN) | All loans in the system |
| PUT | `/api/loans/admin/{loanId}/decision` | Bearer (ADMIN) | Approve or reject; triggers approval/rejection email |
| GET | `/api/loans/admin/summary` | Bearer (ADMIN) | Portfolio summary stats |

### AI Scoring — `http://localhost:8000`

| Method | Endpoint | Description |
|---|---|---|
| POST | `/score` | Returns `credit_score`, `risk_label`, `suggested_rate`, `recommendation` |
| GET | `/health` | Health check |

### Notification Service — `http://localhost:8083`

Consumes from RabbitMQ queue `loan.events.queue`. Notification-service owns and declares all queue bindings.

**Supported event types:**

| Event type | Publisher | Trigger | Email |
|---|---|---|---|
| `USER_REGISTERED` | user-service | New user registers | Welcome email |
| `LOAN_APPLIED` | loan-service | Loan application submitted | Application received + credit score |
| `APPROVED` | loan-service | Admin approves loan | Approval with EMI details |
| `REJECTED` | loan-service | Admin rejects loan | Rejection with reapply info |
| `EMI_DUE` | (scheduled) | EMI reminder | EMI due reminder |

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
| GET | `/webhook/whatsapp` | Meta verification challenge |
| POST | `/webhook/whatsapp` | Delivery status updates and incoming messages |

**WhatsApp setup:**
1. Create Meta App → add WhatsApp product → copy Phone Number ID and Access Token
2. Create 3 UTILITY templates in Meta Business Manager (`loan_approved`, `loan_rejected`, `emi_due`)
3. Register webhook: Meta Console → WhatsApp → Configuration → Callback URL = `https://<host>/webhook/whatsapp`
4. Set `NOTIFICATION_WHATSAPP_ENABLED=true` once templates are approved

**Adding a new notification channel:**
1. Create a class implementing `NotificationChannel` in a sub-package of `channel/`
2. Annotate with `@Component`
3. Toggle via `notification.channels.<name>.enabled` env var

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
    "fullName": "must not be blank"
  },
  "path": "/api/auth/register",
  "timestamp": "2026-05-17T08:30:00Z"
}
```

---

## Project Structure

```
smartlend/
├── docker-compose.yml
├── .env.example
├── CLAUDE.md                          # AI assistant context file
├── SmartLend.postman_collection.json
├── monitoring/
│   └── prometheus.yml
│
├── user-service/                      # Spring Boot — Auth, User management, Registration events
│   └── src/main/java/com/smartlend/user/
│       ├── config/        SecurityConfig.java, RabbitMQConfig.java
│       ├── controller/    AuthController.java
│       ├── service/       AuthService.java (publishes USER_REGISTERED)
│       ├── model/         User.java
│       ├── security/      JwtUtil.java
│       ├── filter/        RequestLoggingFilter.java
│       ├── exception/     GlobalExceptionHandler.java
│       └── dto/           AuthDto.java
│
├── loan-service/                      # Spring Boot — Loans, EMI, Decisions
│   └── src/main/java/com/smartlend/loan/
│       ├── config/        LoanSecurityConfig.java, RabbitMQConfig.java
│       ├── controller/    LoanController.java
│       ├── service/       LoanService.java (publishes LOAN_APPLIED, APPROVED, REJECTED)
│       ├── model/         Loan.java, EmiPayment.java
│       ├── client/        AiScoringClient.java, UserServiceClient.java
│       ├── filter/        RequestLoggingFilter.java
│       └── exception/     GlobalExceptionHandler.java
│
├── notification-service/              # Spring Boot — Extensible Async Notifications
│   └── src/main/java/com/smartlend/notification/
│       ├── config/        RabbitMQConfig.java (owns ALL exchange bindings)
│       ├── channel/       NotificationChannel.java (interface)
│       │                  NotificationPayload.java
│       │                  NotificationDispatcher.java
│       ├── channel/email/ SendGridEmailChannel.java
│       ├── channel/whatsapp/ WhatsAppChannel.java
│       ├── channel/sms/   SmsChannel.java (stub)
│       ├── channel/push/  PushChannel.java (stub)
│       ├── handler/       LoanNotificationHandler.java (HTML templates for all 5 event types)
│       ├── consumer/      LoanEventConsumer.java
│       └── webhook/       WhatsAppWebhookController.java
│
├── ai-scoring/                        # Python FastAPI — Credit Scoring Engine
│   └── app/main.py
│
└── frontend/                          # React 18 + TypeScript + Tailwind CSS
    ├── .env.production                # Points to Railway service URLs
    ├── .npmrc                         # legacy-peer-deps=true (CRA 5 + TS 5 fix)
    └── src/
        ├── types/         index.ts
        ├── services/      api.ts
        ├── context/       AuthContext.tsx
        ├── hooks/         useLoans.ts, useAdminLoans.ts
        ├── components/    ui/, layout/
        └── pages/         Landing, auth/, applicant/, admin/
```

---

## Testing

### Java services — JUnit 5 + Mockito

Run tests for any service with Maven:

```bash
cd user-service && mvn test        # 15 tests
cd loan-service && mvn test        # 6 tests
cd notification-service && mvn test # 19 tests
```

| Service | Test classes | Coverage |
|---|---|---|
| user-service | `JwtUtilTest`, `AuthServiceTest` | Token generation/validation, register/login/createAdmin flows, error cases |
| loan-service | `LoanServiceTest` | Loan apply, approve/reject decisions, EMI formula accuracy, not-found errors |
| notification-service | `NotificationDispatcherTest`, `WhatsAppChannelTest`, `LoanNotificationHandlerTest` | Channel routing, phone normalisation (4 formats), template selection, all 5 event handlers |

All tests are unit tests (`@ExtendWith(MockitoExtension.class)`) — no database or external service required.

### AI Scoring — pytest

```bash
cd ai-scoring && python3 -m pytest tests/ -v  # 17 tests
```

Covers: DTI adjustments, tenure penalties, employment multipliers, existing-loan penalties, income-band bonuses, score clamping (300–900), risk/rate mapping, approval threshold.

The scoring logic lives in `app/scoring.py` (pure Python, no FastAPI dependency) so tests run without any installed framework.

---

## Postman Collection

Import `SmartLend.postman_collection.json` — login/register requests automatically save the JWT token so all subsequent requests work without manual copy-paste.

---

Built as a portfolio project demonstrating microservices architecture, fintech domain knowledge, AI integration, and production-grade engineering practices.