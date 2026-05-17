# SmartLend — Project Context for Claude

AI-powered loan management platform. Portfolio project targeting Fintech SDE roles.

---

## Architecture Overview

```
Browser (React 18 / TypeScript / Tailwind)
    │
    ├─ POST /api/auth/*      → user-service   :8081  (Spring Boot 3.2)
    ├─ POST /api/loans/*     → loan-service   :8082  (Spring Boot 3.2)
    │       │
    │       ├─ HTTP → ai-scoring :8000  (FastAPI / Python 3.11)
    │       └─ RabbitMQ → notification-service :8083  (Spring Boot 3.2)
    │
    └─ Infra: PostgreSQL ×2, Redis, RabbitMQ, Prometheus
```

All services run via `docker-compose.yml` at the project root.

---

## Services

### user-service — port 8081
**Tech:** Spring Boot 3.2, Java 17, Spring Security, JJWT 0.12.3, PostgreSQL (userdb:5432), Redis  
**Package root:** `com.smartlend.user`

**Endpoints** (`/api/auth`):
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/register` | Public | Register applicant; returns JWT + full profile |
| POST | `/login` | Public | Login; returns JWT + full profile |
| GET | `/profile/{userId}` | Public | Internal use by loan-service |
| POST | `/admin/create-admin` | Bearer ADMIN | Create admin user |

**Key files:**
- `model/User.java` — id (UUID), email, password (BCrypt), fullName, phone, panCard, employmentType, monthlyIncome, address, role (APPLICANT/ADMIN), kycStatus (PENDING/VERIFIED/REJECTED)
- `dto/AuthDto.java` — `RegisterRequest`, `LoginRequest`, `AuthResponse` (includes `userId`, `token`, `role`, `monthlyIncome`, `employmentType`), `UserProfileResponse`
- `security/JwtUtil.java` — signs/validates JWT; secret from `JWT_SECRET` env var
- `config/SecurityConfig.java` — JWT `OncePerRequestFilter`; `exceptionHandling` with JSON `AuthenticationEntryPoint` (401) and `AccessDeniedHandler` (403); `/admin/**` requires ROLE_ADMIN; CORS open for `*`
- `service/AuthService.java` — `register()`, `login()`, `createAdmin()` (sets role=ADMIN + kycStatus=VERIFIED), `buildAuthResponse()`
- `filter/RequestLoggingFilter.java` — MDC `requestId` (8-char UUID), `X-Request-Id` response header, logs `METHOD /uri → STATUS (Xms)`
- `exception/GlobalExceptionHandler.java` — `@RestControllerAdvice` covering 400 / 401 / 403 / 404 / 422 / 500; see error format section below

---

### loan-service — port 8082
**Tech:** Spring Boot 3.2, Java 17, PostgreSQL (loandb:5433), RabbitMQ, calls ai-scoring via HTTP  
**Package root:** `com.smartlend.loan`

**Endpoints** (`/api/loans`):
| Method | Path | Required Headers | Description |
|--------|------|------------------|-------------|
| POST | `/apply` | `X-User-Id`, `X-Monthly-Income`, `X-Employment-Type` + Bearer | Apply for loan |
| GET | `/my` | `X-User-Id` + Bearer | Get caller's loans |
| GET | `/{loanId}/emi-schedule` | Bearer | Amortization table |
| GET | `/admin/all` | Bearer (ADMIN) | All loans |
| PUT | `/admin/{loanId}/decision` | Bearer (ADMIN) | Approve / reject |
| GET | `/admin/summary` | Bearer (ADMIN) | Portfolio summary stats |

**`LoanDto.LoanResponse` field names (exact — frontend types must match):**
`id`, `userId`, `principalAmount`, `tenureMonths`, `interestRate`, `emiAmount`, `totalPayable`, `creditScore`, `riskLabel`, `status`, `purpose`, `adminNote`, `disbursedDate`, `nextEmiDate`, `outstandingAmount`, `appliedAt`

**`LoanDto.EmiScheduleItem` field names:**
`installmentNumber`, `emiAmount`, `principalComponent`, `interestComponent`, `remainingBalance`, `dueDate`, `status`

**`LoanDto.AdminDecisionRequest` field names:**
`decision` (APPROVED/REJECTED), `adminNote`, `interestRate`

**Loan flow:**
1. `POST /apply` → receives request body + three custom headers
2. Calls `ai-scoring:8000/score` synchronously
3. Stores loan with `creditScore` + `riskLabel` from AI
4. On admin `PUT /decision`:
   - APPROVED → computes amortization, generates `EmiPayment` rows, publishes to RabbitMQ with `"type": "APPROVED"`
   - REJECTED → publishes to RabbitMQ with `"type": "REJECTED"`

**Key files:**
- `service/LoanService.java` — full loan flow; standard reducing-balance EMI formula; fetches user profile via `UserServiceClient` before publishing events
- `client/AiScoringClient.java` — HTTP call to ai-scoring; fallback rule-based scoring if service is down
- `client/UserServiceClient.java` — calls `GET /api/auth/profile/{userId}` on user-service to get `email` and `fullName` for RabbitMQ events
- `config/LoanSecurityConfig.java` — same pattern as user-service SecurityConfig; JSON 401/403 handlers wired via `exceptionHandling()`
- `config/RabbitMQConfig.java` — `TopicExchange("smartlend.exchange")`, queue `loan.events`
- `filter/RequestLoggingFilter.java` — same MDC pattern as user-service
- `exception/GlobalExceptionHandler.java` — same error format as user-service

**RabbitMQ routing:**
- Approval: routing key `loan.approved` → queue `loan.events`
- Rejection: routing key `loan.rejected` → queue `loan.events`
- **Payload MUST include `"type": "APPROVED"` or `"type": "REJECTED"`** — `LoanEventConsumer` switches on this field; missing it causes silent drop with a warn log
- **APPROVED payload fields:** `loanId`, `userId`, `userEmail`, `userName`, `userPhone`, `amount`, `tenureMonths`, `interestRate`, `emiAmount`, `type`
- **REJECTED payload fields:** `loanId`, `userId`, `userEmail`, `userName`, `userPhone`, `type`

---

### notification-service — port 8083
**Tech:** Spring Boot 3.2, Java 17, RabbitMQ consumer, SendGrid Java SDK (`sendgrid-java:4.10.2`)  
**Package root:** `com.smartlend.notification`

**Extensible channel architecture** — add a new delivery mechanism by implementing `NotificationChannel` and annotating with `@Component`; the dispatcher auto-discovers it via Spring DI with no other changes needed.

**Key files:**
- `channel/NotificationChannel.java` — interface: `channelName()`, `isEnabled()`, `send(NotificationPayload)`
- `channel/NotificationPayload.java` — immutable record: `eventType`, `recipientEmail`, `recipientName`, `subject`, `body`, `htmlBody`, `metadata`
- `channel/NotificationDispatcher.java` — injects `List<NotificationChannel>`; filters by `isEnabled()`, catches per-channel exceptions
- `channel/email/SendGridEmailChannel.java` — sends HTML (or plain-text fallback) via SendGrid REST API; reads config from `sendgrid.*` props
- `channel/whatsapp/WhatsAppChannel.java` — sends pre-approved template messages via Meta Graph API (`POST /v19.0/{phoneNumberId}/messages`); normalises Indian phone numbers to E.164; skips silently if `recipientPhone` is null
- `channel/sms/SmsChannel.java` — Twilio stub; `isEnabled()` returns false; wire Twilio SDK here when ready
- `channel/push/PushChannel.java` — Firebase FCM stub; same pattern
- `handler/LoanNotificationHandler.java` — converts raw `Map<String,Object>` loan events into `NotificationPayload` with HTML bodies and phone; one method per event type (`handleLoanApproved`, `handleLoanRejected`, `handleEmiDue`)
- `consumer/LoanEventConsumer.java` — `@RabbitListener`; routes `type` field to `LoanNotificationHandler`
- `config/RabbitMQConfig.java` — declares durable `loan.events.queue`, `Jackson2JsonMessageConverter`, and `SimpleRabbitListenerContainerFactory` (notification-service is consumer-only; no exchange/binding declared here)

**Config env vars** (from `.env` / docker-compose):
```
# SendGrid (email channel)
SENDGRID_API_KEY               — SendGrid API key (required for email delivery)
SENDGRID_FROM_EMAIL            — sender address (default: noreply@smartlend.com)
SENDGRID_FROM_NAME             — sender display name (default: SmartLend)

# Channel toggles
NOTIFICATION_EMAIL_ENABLED     — true/false (default: true)
NOTIFICATION_WHATSAPP_ENABLED  — true/false (default: false)
NOTIFICATION_SMS_ENABLED       — true/false (default: false)
NOTIFICATION_PUSH_ENABLED      — true/false (default: false)

# WhatsApp Cloud API (Meta Graph API)
WHATSAPP_ACCESS_TOKEN          — permanent/temporary token from Meta Developer console
WHATSAPP_PHONE_NUMBER_ID       — phone number ID from Meta WhatsApp product settings
WHATSAPP_API_VERSION           — Graph API version (default: v19.0)
WHATSAPP_LANGUAGE_CODE         — template language (default: en)
WHATSAPP_TEMPLATE_LOAN_APPROVED — approved template name (default: loan_approved)
WHATSAPP_TEMPLATE_LOAN_REJECTED — approved template name (default: loan_rejected)
WHATSAPP_TEMPLATE_EMI_DUE      — approved template name (default: emi_due)

# WhatsApp template parameter order (must match templates created in Meta Business Manager):
#   loan_approved → {{1}} recipientName  {{2}} amount  {{3}} emiAmount  {{4}} tenureMonths
#   loan_rejected → {{1}} recipientName  {{2}} loanId
#   emi_due       → {{1}} recipientName  {{2}} amount  {{3}} dueDate

# Twilio SMS — populate when enabling SMS channel
TWILIO_ACCOUNT_SID         — Twilio account SID
TWILIO_AUTH_TOKEN          — Twilio auth token
TWILIO_FROM_NUMBER         — sender phone number (E.164 format)

# Firebase Push — populate when enabling push channel
FIREBASE_SERVER_KEY        — Firebase server key (FCM legacy API)
```

**`NotificationPayload` fields** (record — all channels receive this):
`eventType`, `recipientEmail`, `recipientName`, `recipientPhone`, `subject`, `body`, `htmlBody`, `metadata`
— `recipientPhone` is nullable; WhatsApp channel skips with a warn log if null

---

### ai-scoring — port 8000
**Tech:** Python 3.11, FastAPI, NumPy  
**Entry:** `ai-scoring/app/main.py`

**`POST /score`** — Body: `{monthly_income, requested_amount, tenure_months, employment_type, existing_loans?}`  
Returns: `{credit_score (300–900), risk_label, suggested_rate, recommendation (APPROVE/REJECT), reasoning}`

**Scoring model** (rule-based, swap for sklearn/joblib later):
- Base 500 + DTI adjustment (< 1 → +200, < 2 → +130, < 3 → +60, < 5 → -20, else → -150)
- Tenure: ≤12mo → +80, ≤36mo → +40, >60mo → -50
- Employment multiplier: SALARIED ×1.0, BUSINESS ×0.90, SELF_EMPLOYED ×0.85
- Existing loans: -30 each; Income band bonus: ≥100k → +60, ≥50k → +30, ≥25k → +10
- ≥ 580 → APPROVE, else REJECT

**Risk/rate mapping:** ≥750 → LOW 10.5% / ≥650 → MEDIUM 13.5% / ≥550 → MEDIUM 16% / <550 → HIGH 20%

Middleware logs every request with UUID `requestId` and sets `X-Request-Id` response header.

---

## Global Error Response Format

**Critical:** Spring Security's 401/403 bypass `@RestControllerAdvice`. Both security configs wire a `writeError()` helper into `.exceptionHandling(AuthenticationEntryPoint, AccessDeniedHandler)` to ensure JSON is always returned.

**Standard error envelope:**
```json
{
  "status": 403,
  "error": "FORBIDDEN",
  "message": "You do not have permission to access this resource. Required role may be ADMIN.",
  "path": "/api/loans/admin/all",
  "timestamp": "2026-05-17T08:30:00Z"
}
```

**Validation error envelope** (422 only):
```json
{
  "status": 422,
  "error": "VALIDATION_ERROR",
  "message": "Request validation failed. Check the 'fields' map for details.",
  "fields": { "principalAmount": "must be >= 10000" },
  "path": "/api/loans/apply",
  "timestamp": "2026-05-17T08:30:00Z"
}
```

| Code | Error Key | Trigger |
|------|-----------|---------|
| 400 | `BAD_REQUEST` | RuntimeException / IllegalArgumentException |
| 400 | `MISSING_HEADER` | `MissingRequestHeaderException` (e.g. `X-User-Id`) |
| 400 | `INVALID_PARAMETER` | `MethodArgumentTypeMismatchException` |
| 401 | `UNAUTHORIZED` | No token / expired token (filter chain level) |
| 403 | `FORBIDDEN` | Valid token but wrong role (filter chain level) |
| 404 | `NOT_FOUND` | `NoResourceFoundException` |
| 422 | `VALIDATION_ERROR` | `MethodArgumentNotValidException` |
| 500 | `INTERNAL_SERVER_ERROR` | Any unhandled `Exception` |

---

## Infrastructure

| Service | Container | Port(s) |
|---------|-----------|---------|
| PostgreSQL (users) | smartlend-user-db | 5432 |
| PostgreSQL (loans) | smartlend-loan-db | 5433 |
| Redis | smartlend-redis | 6379 |
| RabbitMQ | smartlend-rabbitmq | 5672 / 15672 (UI) |
| Prometheus | smartlend-prometheus | 9090 |

**Dev credentials:** PostgreSQL + RabbitMQ: `smartlend` / `smartlend123`  
**JWT secret:** `smartlend-super-secret-jwt-key-change-in-prod`  
**Apple Silicon fix:** All Dockerfiles use `--platform=linux/arm64` + `eclipse-temurin:17-jre-jammy`

---

## Frontend

**Stack:** React 18, TypeScript 5.3 (strict), Tailwind CSS 3.4, React Router v6, Axios, Recharts  
**Port:** 3000 — CRA proxy `/api` → `http://localhost:8082`  
**Install:** Always use `npm install --legacy-peer-deps` (CRA 5 declares `typescript@^4` peer dep but works fine with TS5)

### Folder structure
```
src/
├── types/index.ts          — All TS interfaces; field names match backend DTOs exactly
├── constants/index.ts      — LOAN_STATUS_CONFIG, RISK_CONFIG, PAYMENT_STATUS_CONFIG (Tailwind badge classes)
├── utils/cn.ts             — cn() = twMerge(clsx(...))
├── utils/formatters.ts     — formatCurrency (₹ en-IN), formatDate, formatPercent, shortId
├── services/api.ts         — userHttp (:8081), loanHttp (:8082); Axios interceptors; authApi, loanApi
├── context/AuthContext.tsx — AuthProvider; stores AuthUser in localStorage key "smartlend_user"
├── hooks/useLoans.ts       — Applicant: /loans/my → {loans, isLoading, error, refetch}
├── hooks/useAdminLoans.ts  — Admin: /loans/admin/all; decide(loanId, req) with optimistic update
├── components/ui/          — Button (variants+isLoading), Input, Select, Badge, Alert (all forwardRef)
├── components/layout/Layout.tsx — Role-aware sidebar; violet for ADMIN, blue for APPLICANT
└── pages/
    ├── Landing.tsx         — Public dark-navy hero page
    ├── auth/Login.tsx      — Split-screen: dark-blue gradient left / white form right
    ├── auth/Register.tsx   — Split-screen: emerald gradient left / white form right
    ├── applicant/Dashboard.tsx    — Status tabs + summary cards; uses useLoans
    ├── applicant/ApplyLoan.tsx    — Form + post-submission success screen with credit score bar
    ├── applicant/EmiSchedule.tsx  — Recharts line chart + amortization table
    └── admin/AdminDashboard.tsx   — Loan table with inline approve/reject inputs; uses useAdminLoans
```

### Critical field name mapping (frontend ↔ backend)
The frontend `Loan` type matches `LoanDto.LoanResponse` exactly:
- `loan.id` (NOT `loanId`)
- `loan.principalAmount` (NOT `amount`)
- `loan.tenureMonths` (NOT `termMonths`)

The frontend `EmiPayment` type matches `LoanDto.EmiScheduleItem` exactly:
- `principalComponent`, `interestComponent`, `emiAmount`, `remainingBalance`

`AdminDecisionRequest` body field: `decision` (NOT `status`)

### Auth flow
Login/Register → backend returns `AuthUser` (with `token`, `role`, `monthlyIncome`, `employmentType`) → localStorage → Axios request interceptor injects `Authorization: Bearer <token>` → 401 response clears storage and redirects `/login`

### Role routing
- Unauthenticated → `<Landing />`
- APPLICANT → `/dashboard`
- ADMIN → `/admin`

---

## Common Dev Commands

```bash
# Start everything
docker compose up -d

# Rebuild a single service after code change
docker compose up -d --build user-service
docker compose up -d --build loan-service

# Frontend dev server
cd frontend && npm start

# TypeScript type check (zero errors expected)
cd frontend && npm run type-check

# Promote user to ADMIN
docker exec -it smartlend-user-db psql -U smartlend -d userdb \
  -c "UPDATE users SET role='ADMIN' WHERE email='admin@example.com';"

# Tail logs
docker logs -f smartlend-user-service
docker logs -f smartlend-loan-service

# RabbitMQ UI
open http://localhost:15672   # smartlend / smartlend123
```

---

## Logging Pattern

- **Spring Boot services:** Logback `SizeAndTimeBasedRollingPolicy` (daily, 100MB, 30-day, gzip); MDC `requestId` in every log line; `RequestLoggingFilter` generates UUID per request
- **ai-scoring:** Python logging middleware with UUID `request_id` injected via `logging.Filter`
- **Pattern:** `METHOD /uri → STATUS (Xms)` logged after response; MDC cleared after each request
- **Usage:** `@Slf4j` (Lombok) for all new Java code; log business events at INFO, debug details at DEBUG

---

## Key Design Decisions & Gotchas

- **`monthlyIncome` / `employmentType` in AuthResponse:** Included so `ApplyLoan.tsx` can read them from `useAuth()` without a separate profile call. If you add fields, update `AuthDto.AuthResponse` and `AuthService.buildAuthResponse()`.

- **`X-User-Id` header pattern:** loan-service reads the caller's userId from `X-User-Id` header (not from JWT). Frontend Axios interceptor sends both `Authorization` and this header. If you ever add a gateway, it should forward this header downstream.

- **RabbitMQ `type` field is mandatory:** `LoanEventConsumer` switches on `event.get("type")`. Publishing without it → silent drop + warn log.

- **Spring Security 401/403 bypass `@RestControllerAdvice`:** These are written by `ExceptionTranslationFilter` before the dispatcher servlet. JSON responses require `.exceptionHandling(AuthenticationEntryPoint, AccessDeniedHandler)` in the security config — not in the advice class.

- **Admin decision HTTP method is PUT:** `PUT /api/loans/admin/{loanId}/decision` — not POST. Body field is `decision`, not `status`.

- **CRA + TypeScript 5:** CRA 5 peer dep declares `typescript@^4` but works with TS5. Always install with `npm install --legacy-peer-deps`.

- **No API gateway:** Frontend calls user-service and loan-service directly. CRA proxy in `package.json` routes `/api` to loan-service `:8082` for dev. User-service calls go direct to `:8081`.

---

## Files to Read First for Any Change

| What you're changing | Read first |
|---|---|
| Auth endpoints / JWT | `AuthController.java`, `AuthService.java`, `SecurityConfig.java` |
| Loan endpoints | `LoanController.java`, `LoanService.java`, `LoanDto.java` |
| Frontend API calls | `services/api.ts`, `types/index.ts` |
| Error handling | `GlobalExceptionHandler.java` (both services) + security config `writeError()` |
| Credit scoring logic | `ai-scoring/app/main.py` |
| RabbitMQ events | `LoanService.java` (publisher) + `LoanEventConsumer.java` (consumer) |
| Notification channels | `NotificationChannel.java` (interface) + `NotificationDispatcher.java` + `handler/LoanNotificationHandler.java` |
| Email templates | `handler/LoanNotificationHandler.java` — HTML bodies for APPROVED / REJECTED / EMI_DUE |
| Adding a new channel | Implement `NotificationChannel`, annotate `@Component` — dispatcher auto-discovers it |
