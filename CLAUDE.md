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
| POST | `/register` | Public | Register applicant; sets HttpOnly `auth_token` cookie + returns profile |
| POST | `/login` | Public | Login; sets HttpOnly `auth_token` cookie + returns profile |
| POST | `/logout` | Public | Clears the `auth_token` cookie (maxAge=0) |
| GET | `/me` | Cookie/Bearer | Restore session — validates cookie, returns fresh profile + token |
| GET | `/profile/{userId}` | Public | Internal use by loan-service |
| POST | `/admin/create-admin` | Cookie ADMIN | Create admin user; sets cookie for created admin |

**Cookie auth pattern:**
- `auth_token` cookie: `HttpOnly; Secure; SameSite=${COOKIE_SAME_SITE}`
- Local dev defaults: `COOKIE_SECURE=false`, `COOKIE_SAME_SITE=Lax`
- Production (Railway): set `COOKIE_SECURE=true`, `COOKIE_SAME_SITE=None` (cross-site Vercel → Railway)
- JWT filter reads cookie first, falls back to `Authorization: Bearer` header (for internal service calls)

**Key files:**
- `model/User.java` — id (UUID), email, password (BCrypt), fullName, phone, panCard, employmentType, monthlyIncome, address, role (APPLICANT/ADMIN), kycStatus (PENDING/VERIFIED/REJECTED)
- `dto/AuthDto.java` — `RegisterRequest`, `LoginRequest`, `AuthResponse` (includes `userId`, `token`, `role`, `monthlyIncome`, `employmentType`), `UserProfileResponse`
- `security/JwtUtil.java` — signs/validates JWT; secret from `JWT_SECRET` env var
- `config/SecurityConfig.java` — JWT filter reads `auth_token` cookie then `Authorization` header; `/admin/**` requires ROLE_ADMIN; `/logout` and `/me` are also declared here
- `service/AuthService.java` — `register()`, `login()`, `createAdmin()`, `getMyProfile(userId)` (used by /me)
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
- `client/AiScoringClient.java` — HTTP call to ai-scoring; **Resilience4j circuit breaker** (`ai-scoring` instance, 30s open state); fallback uses DTI-ratio rule-based scoring
- `client/UserServiceClient.java` — calls `GET /api/auth/profile/{userId}` on user-service; **Resilience4j circuit breaker** (`user-service` instance, 15s open state); fallback returns null (LoanService handles gracefully)
- `config/LoanSecurityConfig.java` — same pattern as user-service SecurityConfig; JSON 401/403 handlers wired via `exceptionHandling()`
- `config/RabbitMQConfig.java` — `TopicExchange("smartlend.exchange")`, queue `loan.events`
- `filter/RequestLoggingFilter.java` — same MDC pattern as user-service
- `exception/GlobalExceptionHandler.java` — same error format as user-service

**RabbitMQ routing** (all → queue `loan.events.queue` on exchange `smartlend.exchange`):

| Routing key | Publisher | `type` field | Payload fields |
|---|---|---|---|
| `user.registered` | user-service | `USER_REGISTERED` | `userId`, `userEmail`, `userName`, `userPhone` |
| `loan.applied` | loan-service | `LOAN_APPLIED` | `loanId`, `userId`, `userEmail`, `userName`, `userPhone`, `amount`, `tenureMonths`, `creditScore`, `riskLabel` |
| `loan.approved` | loan-service | `APPROVED` | `loanId`, `userId`, `userEmail`, `userName`, `userPhone`, `amount`, `tenureMonths`, `interestRate`, `emiAmount` |
| `loan.rejected` | loan-service | `REJECTED` | `loanId`, `userId`, `userEmail`, `userName`, `userPhone` |
| `loan.emi.due` | (scheduled) | `EMI_DUE` | `loanId`, `userId`, `userEmail`, `userName`, `userPhone`, `amount`, `dueDate` |

- **`type` field is mandatory** — `LoanEventConsumer` switches on it; missing → silent drop + warn log
- **Bindings are owned by notification-service** — `notification-service/config/RabbitMQConfig.java` declares all 5 bindings via `Declarables`. Do NOT rely on loan-service or user-service to declare bindings for notification routing.

---

### notification-service — port 8083
**Tech:** Spring Boot 3.2, Java 17, RabbitMQ consumer, SendGrid Java SDK (`sendgrid-java:4.10.2`)  
**Package root:** `com.smartlend.notification`

**Extensible channel architecture** — add a new delivery mechanism by implementing `NotificationChannel` and annotating with `@Component`; the dispatcher auto-discovers it via Spring DI with no other changes needed.

**Key files:**
- `channel/NotificationChannel.java` — interface: `channelName()`, `isEnabled()`, `send(NotificationPayload)`
- `channel/NotificationPayload.java` — immutable record: `eventType`, `recipientEmail`, `recipientName`, `recipientPhone`, `subject`, `body`, `htmlBody`, `metadata`
- `channel/NotificationDispatcher.java` — injects `List<NotificationChannel>`; filters by `isEnabled()`, catches per-channel exceptions
- `channel/email/SendGridEmailChannel.java` — sends HTML (or plain-text fallback) via SendGrid REST API; reads config from `sendgrid.*` props
- `channel/whatsapp/WhatsAppChannel.java` — sends pre-approved template messages via Meta Graph API (`POST /v19.0/{phoneNumberId}/messages`); normalises Indian phone numbers to E.164; skips silently if `recipientPhone` is null
- `channel/sms/SmsChannel.java` — Twilio stub; `isEnabled()` returns false; wire Twilio SDK here when ready
- `channel/push/PushChannel.java` — Firebase FCM stub; same pattern
- `handler/LoanNotificationHandler.java` — HTML templates for all 5 event types: `handleUserRegistered`, `handleLoanApplied`, `handleLoanApproved`, `handleLoanRejected`, `handleEmiDue`
- `consumer/LoanEventConsumer.java` — `@RabbitListener`; routes `type` field to handler methods
- `config/RabbitMQConfig.java` — declares `TopicExchange`, durable `loan.events.queue`, ALL 5 bindings via `Declarables`, `Jackson2JsonMessageConverter`, `SimpleRabbitListenerContainerFactory`
- `webhook/WhatsAppWebhookController.java` — `GET /webhook/whatsapp` handles Meta's one-time verification challenge; `POST /webhook/whatsapp` logs delivery statuses (sent/delivered/read/failed) and incoming messages; always returns 200 to prevent Meta retry storms

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
WHATSAPP_ACCESS_TOKEN           — permanent/temporary token from Meta Developer console
                                  (temporary token expires every 24h — use System User token in prod)
WHATSAPP_PHONE_NUMBER_ID        — phone number ID from Meta WhatsApp product settings
WHATSAPP_API_VERSION            — Graph API version (default: v19.0)
WHATSAPP_LANGUAGE_CODE          — template language (default: en)
WHATSAPP_WEBHOOK_VERIFY_TOKEN   — any secret string YOU define; must match what's entered in
                                  Meta Console → WhatsApp → Configuration → Verify token
WHATSAPP_TEMPLATE_LOAN_APPROVED — approved template name (default: loan_approved)
WHATSAPP_TEMPLATE_LOAN_REJECTED — approved template name (default: loan_rejected)
WHATSAPP_TEMPLATE_EMI_DUE       — approved template name (default: emi_due)

# WhatsApp template parameter order (must match templates created in Meta Business Manager):
#   loan_approved → {{1}} recipientName  {{2}} amount  {{3}} emiAmount  {{4}} tenureMonths
#   loan_rejected → {{1}} recipientName  {{2}} loanId
#   emi_due       → {{1}} recipientName  {{2}} amount  {{3}} dueDate

# WhatsApp sandbox limits:
#   - Test phone number can only send to numbers added under API Setup → To field
#   - Max 5 test recipient numbers on free sandbox
#   - Templates must be UTILITY category (not MARKETING) for faster approval (<1hr)
#   - Production requires a real business phone number and Meta Business verification

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

### Cloud (production)

| Service | Provider | Notes |
|---------|----------|-------|
| PostgreSQL (userdb) | Neon | pooler endpoint; `sslmode=require` |
| PostgreSQL (loandb) | Neon | same project, different database |
| Redis | Upstash | TLS (`rediss://`); `REDIS_SSL=true` |
| RabbitMQ | CloudAMQP | Little Lemur (free); AMQPS port 5671; `RABBITMQ_SSL=true` |

### Local (docker-compose)
Local DB/Redis/RabbitMQ containers were removed — replaced by cloud providers above. `docker compose up -d` now starts the 4 microservices + ai-scoring + Prometheus, all wired to cloud infra via `.env`.

**Prometheus** — still runs locally on port 9090; scrapes `/actuator/prometheus` on all Spring Boot services; config at `monitoring/prometheus.yml`. Pair with Grafana for dashboards if needed.

**JWT secret:** `smartlend-super-secret-jwt-key-change-in-prod`  
**Apple Silicon fix:** All Dockerfiles use `--platform=linux/arm64` + `eclipse-temurin:17-jre-jammy`

### Env var names (changed from defaults)

| Variable | Service | Purpose |
|----------|---------|---------|
| `USER_DB_URL` / `USER_DB_USERNAME` / `USER_DB_PASSWORD` | user-service | Neon userdb JDBC |
| `LOAN_DB_URL` / `LOAN_DB_USERNAME` / `LOAN_DB_PASSWORD` | loan-service | Neon loandb JDBC |
| `RABBITMQ_HOST/PORT/USERNAME/PASSWORD/VHOST/SSL` | loan + notification | CloudAMQP |
| `REDIS_HOST/PORT/USERNAME/PASSWORD/SSL` | user-service | Upstash Redis |

---

## Production Deployment

### Backend — Railway

| Service | Public URL | Internal URL | Port |
|---------|-----------|--------------|------|
| user-service | `user-service-smartlend.up.railway.app` | `user-service-smartlend.railway.internal` | 8081 |
| loan-service | `loan-service-smartlend.up.railway.app` | `loan-service-smartlend.railway.internal` | 8082 |
| notification-service | `notification-service-smarlend.up.railway.app` | `notification-service-smartlend.railway.internal` | 8083 |
| ai-scoring | `ai-scoring-smartlend.up.railway.app` | `ai-scoring-smartlend.railway.internal` | 8000 |

**Inter-service communication uses private Railway hostnames** (free, no egress):
- `AI_SCORING_URL=http://ai-scoring-smartlend.railway.internal:8000`
- `USER_SERVICE_URL=http://user-service-smartlend.railway.internal:8081`

**Health checks:**
```
https://user-service-smartlend.up.railway.app/actuator/health
https://loan-service-smartlend.up.railway.app/actuator/health
https://notification-service-smarlend.up.railway.app/actuator/health
https://ai-scoring-smartlend.up.railway.app/health
https://ai-scoring-smartlend.up.railway.app/docs
```

### Frontend — Vercel

- **URL:** `https://smartlend-ebon.vercel.app`
- **Repo root:** `frontend/`
- **Build:** Vercel auto-runs `npm run build`; picks up `frontend/.env.production` automatically
- **`.npmrc`:** `legacy-peer-deps=true` committed in `frontend/.npmrc` — fixes CRA 5 + TS 5 peer dep conflict for both local installs and Vercel builds

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

`RegisterRequest` field: `fullName` (NOT `name`) — backend `@NotBlank` on `fullName`; sending `name` causes a 422

### Auth flow
Login/Register → backend sets `auth_token` HttpOnly cookie + returns `AuthUser` → `AuthContext.login()` puts token in module-level `_token` var (in-memory only, never localStorage) + stores profile (without token) in localStorage → `userHttp` sends cookie automatically (`withCredentials: true`) → `loanHttp` sends `Authorization: Bearer <_token>` header → 401 on either clears session and redirects `/login`

**Session restore on page refresh:** `AuthContext` on mount reads profile from localStorage; if present, calls `GET /api/auth/me` using the HttpOnly cookie to get a fresh token; restores `_token` in memory. `isRestoring: true` during this; route guards show a spinner. If cookie is expired, localStorage is cleared and user is redirected to login.

**`AuthUser.token` is optional** — only present on login/register/me API response. Never stored in localStorage. Use `setInMemoryToken()` / `getToken()` from `services/api.ts` to manage it.

### Role routing
- Unauthenticated → `<Landing />`
- APPLICANT → `/dashboard`
- ADMIN → `/admin`

---

## Common Dev Commands

```bash
# Start all services (uses cloud infra from .env)
docker compose up -d

# Rebuild a single service after code change
docker compose up -d --build user-service
docker compose up -d --build loan-service

# Frontend dev server
cd frontend && npm install --legacy-peer-deps
cd frontend && npm start

# TypeScript type check (zero errors expected)
cd frontend && npm run type-check

# Run unit tests
cd user-service && mvn test          # 15 tests — JwtUtil, AuthService
cd loan-service && mvn test           # 6 tests  — LoanService (EMI formula, apply, decision)
cd notification-service && mvn test   # 19 tests — Dispatcher, WhatsApp, NotificationHandler
cd ai-scoring && python3 -m pytest tests/ -v  # 17 tests — scoring logic

# Promote user to ADMIN (Neon — run in Neon SQL editor or psql)
UPDATE users SET role='ADMIN' WHERE email='admin@example.com';

# Tail logs
docker logs -f smartlend-user-service
docker logs -f smartlend-loan-service

# Railway CLI logs (production)
railway logs --service user-service-smartlend
railway logs --service loan-service-smartlend
railway logs --service notification-service-smarlend
railway logs --service ai-scoring-smartlend
```

---

## Logging Pattern

- **Spring Boot services:** Logback `SizeAndTimeBasedRollingPolicy` (daily, 100MB, 30-day, gzip); MDC `requestId` in every log line; `RequestLoggingFilter` generates UUID per request
- **ai-scoring:** Python logging middleware with UUID `request_id` injected via `logging.Filter`
- **Pattern:** `METHOD /uri → STATUS (Xms)` logged after response; MDC cleared after each request
- **Usage:** `@Slf4j` (Lombok) for all new Java code; log business events at INFO, debug details at DEBUG

---

## Key Design Decisions & Gotchas

- **Cookie auth cross-domain:** user-service sets `auth_token` HttpOnly cookie. For production (Vercel → Railway = cross-site), Railway user-service needs `COOKIE_SECURE=true` and `COOKIE_SAME_SITE=None`. For local dev (same-site localhost), defaults `COOKIE_SECURE=false` and `COOKIE_SAME_SITE=Lax` apply. The cookie is only sent back to user-service (same domain). Loan-service (different Railway domain) cannot receive the cookie, so it continues to read `Authorization: Bearer` header. Frontend keeps the token in a module-level `_token` variable (api.ts) for this.

- **`AuthUser.token` is optional:** Only populated on login/register/me responses. The profile stored in localStorage deliberately omits `token`. `_token` in api.ts is the single source of truth for the JWT in the browser — it lives in module scope, is reset on login, and is cleared on logout/401.

- **`/api/auth/me` is authenticated:** Requires a valid `auth_token` cookie. Called by `AuthContext` on mount to restore the in-memory token after a page refresh. Returns full `AuthResponse` including a fresh token.

- **`fullName` field (AuthUser):** Backend sends `fullName` (matches DTO field). Frontend `AuthUser` type has `fullName` (was `name` — now fixed). Layout sidebar uses `user?.fullName`.

- **`monthlyIncome` / `employmentType` in AuthResponse:** Included so `ApplyLoan.tsx` can read them from `useAuth()` without a separate profile call. If you add fields, update `AuthDto.AuthResponse` and `AuthService.buildAuthResponse()`.

- **`X-User-Id` header pattern:** loan-service reads the caller's userId from `X-User-Id` header (not from JWT). Frontend Axios interceptor sends both `Authorization` and this header. If you ever add a gateway, it should forward this header downstream.

- **RabbitMQ `type` field is mandatory:** `LoanEventConsumer` switches on `event.get("type")`. Publishing without it → silent drop + warn log.

- **notification-service owns all bindings:** Do NOT add `user.registered` or `loan.applied` bindings in loan-service or user-service. `notification-service/config/RabbitMQConfig.java` declares all 5 bindings via `Declarables` — this ensures bindings exist as soon as the consumer starts, regardless of publisher startup order.

- **Spring Security 401/403 bypass `@RestControllerAdvice`:** These are written by `ExceptionTranslationFilter` before the dispatcher servlet. JSON responses require `.exceptionHandling(AuthenticationEntryPoint, AccessDeniedHandler)` in the security config — not in the advice class.

- **Admin decision HTTP method is PUT:** `PUT /api/loans/admin/{loanId}/decision` — not POST. Body field is `decision`, not `status`.

- **Resilience4j circuit breaker — `@CircuitBreaker` requires AOP:** `spring-boot-starter-aop` must be on the classpath or the annotation is silently ignored. Both are declared in `loan-service/pom.xml`. Fallback method signature must match the primary method signature **plus a trailing `Throwable` parameter**. The circuit breaker name in `@CircuitBreaker(name="ai-scoring")` must match the key under `resilience4j.circuitbreaker.instances` in `application.yml`. Health state is exposed at `/actuator/health` (`management.health.circuitbreakers.enabled: true`).

- **CRA + TypeScript 5:** CRA 5 peer dep declares `typescript@^4` but works with TS5. Always install with `npm install --legacy-peer-deps`. `frontend/.npmrc` has `legacy-peer-deps=true` so Vercel and CI pick it up automatically.

- **No API gateway:** Frontend calls user-service and loan-service directly. CRA proxy in `package.json` routes `/api` to loan-service `:8082` for dev. User-service calls go direct to `:8081`. In production (Vercel), `frontend/.env.production` sets `REACT_APP_USER_URL` and `REACT_APP_LOAN_URL` to the Railway public URLs — proxy is ignored.

- **`fullName` in RegisterRequest:** Backend `@NotBlank` field is `fullName`. Frontend `api.ts` `RegisterRequest` interface and `Register.tsx` form must use `fullName` — not `name`. Sending `name` causes a silent 422 VALIDATION_ERROR.

---

## Files to Read First for Any Change

| What you're changing | Read first |
|---|---|
| Auth endpoints / JWT | `AuthController.java`, `AuthService.java`, `SecurityConfig.java` |
| Loan endpoints | `LoanController.java`, `LoanService.java`, `LoanDto.java` |
| Frontend API calls | `services/api.ts`, `types/index.ts` |
| Error handling | `GlobalExceptionHandler.java` (both services) + security config `writeError()` |
| Credit scoring logic | `ai-scoring/app/main.py` |
| RabbitMQ events | `LoanService.java` + `AuthService.java` (publishers) + `LoanEventConsumer.java` (consumer) |
| Notification channels | `NotificationChannel.java` (interface) + `NotificationDispatcher.java` + `handler/LoanNotificationHandler.java` |
| Email templates | `handler/LoanNotificationHandler.java` — HTML bodies for USER_REGISTERED / LOAN_APPLIED / APPROVED / REJECTED / EMI_DUE |
| Adding a new channel | Implement `NotificationChannel`, annotate `@Component` — dispatcher auto-discovers it |
| Adding a new event | 1. Publish from service with `type` field  2. Add binding in `notification-service/config/RabbitMQConfig.java`  3. Add handler method in `LoanNotificationHandler`  4. Add case in `LoanEventConsumer` |
| S3 document upload | `config/S3Config.java`, `service/DocumentStorageService.java`, `controller/LoanDocumentController.java` |
| DynamoDB audit log | `config/DynamoDbConfig.java`, `audit/LoanAuditService.java`, `audit/LoanAuditEvent.java`, `controller/LoanAuditController.java` |

---

## LocalStack AWS Features (added 2026-07-05/06)

LocalStack runs at `http://localhost:4566` and emulates all AWS services locally.
AWS region is permanently **ap-south-1 (Mumbai)** for all SmartLend resources.

### AWS Config (flat namespace — shared by S3 + DynamoDB)

`loan-service/src/main/resources/application.yml`:
```yaml
aws:
  region: ${AWS_REGION:ap-south-1}
  endpoint: ${AWS_ENDPOINT:http://localhost:4566}   # blank = real AWS in prod
  access-key: ${AWS_ACCESS_KEY_ID:test}
  secret-key: ${AWS_SECRET_ACCESS_KEY:test}
  s3:
    bucket-name: ${AWS_S3_BUCKET:smartlend-documents}
    presigned-url-expiry-minutes: ${AWS_S3_PRESIGNED_EXPIRY:15}
  dynamodb:
    table-name: ${AWS_DYNAMODB_AUDIT_TABLE:loan-audit-log}
```

Both `S3Config` and `DynamoDbConfig` read `${aws.endpoint}`, `${aws.region}`, `${aws.access-key}`, `${aws.secret-key}`.
Blank `endpoint` = real AWS. Non-blank = LocalStack override.

### Feature 1 — S3 Document Upload

**Purpose:** KYC document upload (PDF/JPEG/PNG ≤10MB) stored in S3 with presigned download URLs.

**New files in `loan-service/src/main/java/com/smartlend/loan/`:**

| File | Role |
|------|------|
| `config/S3Config.java` | S3Client + S3Presigner beans; `forcePathStyle(true)` required for LocalStack; `ensureBucketExists()` on startup |
| `model/DocumentType.java` | Enum: INCOME_PROOF, IDENTITY_PROOF, ADDRESS_PROOF, BANK_STATEMENT, EMPLOYMENT_LETTER, OTHER |
| `model/LoanDocument.java` | JPA entity: `loan_documents` table; fields: id (UUID), loanId, userId, docType, s3Key, originalFilename, contentType, fileSize, uploadedAt |
| `repository/LoanDocumentRepository.java` | `findByLoanId`, `findByLoanIdAndUserId` |
| `dto/DocumentDto.java` | `UploadResponse`, `DocumentInfo`, `PresignedUrlResponse` |
| `service/DocumentStorageService.java` | Upload (validates type+size, streams to S3), list (admin=all / applicant=own), presigned URL generation (15-min expiry) |
| `controller/LoanDocumentController.java` | REST endpoints (see below) |

**Endpoints** (all under `/api/loans`):

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/{loanId}/documents` | Bearer + X-User-Id | Multipart upload; `docType` query param |
| GET | `/{loanId}/documents` | Bearer + X-User-Id | List document metadata |
| GET | `/{loanId}/documents/{documentId}/url` | Bearer + X-User-Id | 15-min presigned S3 URL |

**S3 key pattern:** `{loanId}/{docType}/{UUID}-{originalFilename}`

**Critical gotcha:** `forcePathStyle(true)` on `S3Client` is mandatory for LocalStack. Without it, the SDK generates `bucket.localhost` virtual-hosted URLs that LocalStack cannot resolve.

**LocalStack setup:**
```bash
# Create bucket (if not auto-created by S3Config startup check)
awslocal s3 mb s3://smartlend-documents --region ap-south-1

# List uploaded documents
awslocal s3 ls s3://smartlend-documents/ --recursive --region ap-south-1
```

### Feature 2 — DynamoDB Immutable Audit Log

**Purpose:** Compliance-grade append-only audit trail for every loan state transition and document upload. Stored in DynamoDB (never in PostgreSQL) for unbounded growth without vacuum/reindex overhead.

**New files in `loan-service/src/main/java/com/smartlend/loan/`:**

| File | Role |
|------|------|
| `config/DynamoDbConfig.java` | DynamoDbClient bean with LocalStack override; `ensureTableExists()` on startup (describeTable → if ResourceNotFoundException → createTable) |
| `audit/LoanAuditEvent.java` | Immutable `@Value @Builder` record — loanId, sk, eventId, eventType, fromStatus, toStatus, actorId, actorRole, metadata (JSON string), timestamp |
| `audit/LoanAuditService.java` | `record(event)` → putItem with `attribute_not_exists(sk)` condition; `getHistory(loanId)` → Query by PK; `buildEvent(...)` → constructs SK |
| `controller/LoanAuditController.java` | `GET /api/loans/admin/{loanId}/audit` — admin-only, returns chronological history |

**DynamoDB table schema:**
```
Table: loan-audit-log
  PK:  loanId  (String)  — partition: all events for one loan live together
  SK:  sk      (String)  — format: {ISO-8601-timestamp}#{UUID}
                           ISO prefix gives chronological lexicographic sort
Billing: PAY_PER_REQUEST
```

**Events recorded:**

| Trigger | eventType | fromStatus | toStatus | metadata |
|---------|-----------|-----------|---------|---------|
| Loan applied | `LOAN_APPLIED` | null | PENDING | amount, tenureMonths, creditScore, riskLabel |
| Admin approves | `LOAN_APPROVED` | PENDING | APPROVED | interestRate, emiAmount, totalPayable, adminNote |
| Admin rejects | `LOAN_REJECTED` | PENDING | REJECTED | adminNote |
| Document uploaded | `DOCUMENT_UPLOADED` | null | null | documentId, docType, filename, fileSize |

**Key design decisions:**
- `attribute_not_exists(sk)` condition → duplicate write on retry is rejected silently (idempotent)
- Audit failures never propagate → catch block logs error but does NOT rethrow; a DynamoDB outage cannot block a loan approval
- Chronological sort is free — ISO-8601 strings sort lexicographically, so `scanIndexForward(true)` returns events in time order with no application-side sorting

**LocalStack setup:**
```bash
# Create table (if not auto-created by DynamoDbConfig startup)
awslocal dynamodb create-table \
  --table-name loan-audit-log \
  --attribute-definitions AttributeName=loanId,AttributeType=S AttributeName=sk,AttributeType=S \
  --key-schema AttributeName=loanId,KeyType=HASH AttributeName=sk,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  --region ap-south-1

# Query audit history for a loan
awslocal dynamodb query \
  --table-name loan-audit-log \
  --key-condition-expression "loanId = :pk" \
  --expression-attribute-values '{":pk":{"S":"your-loan-id"}}' \
  --region ap-south-1
```

**Production path:** In real AWS, add DynamoDB Streams → Lambda → S3 → Athena for a regulatory compliance data lake with zero changes to this service.

**Test coverage:** `LoanServiceTest` mocks `LoanAuditService` (`@Mock`); all 6 tests pass. Audit calls fire-and-forget, so mocking is trivial.

---

## LocalStack Feature Roadmap

### Feature 3 — SQS + Dead Letter Queue (added 2026-07-11)

**Purpose:** Replace RabbitMQ for loan events with AWS SQS + DLQ. `AWS_SQS_ENABLED` toggle lets both brokers coexist for demos. User-service stays on RabbitMQ.

**New/changed files:**

| Service | File | Role |
|---------|------|------|
| loan-service | `config/SqsConfig.java` | `SqsClient` bean + `loan-events` + `loan-events-dlq` bootstrap on startup (`@ConditionalOnProperty`) |
| loan-service | `service/LoanService.java` | Private `publishEvent()` helper — SQS branch when `sqsClient != null`, RabbitMQ fallback |
| notification-service | `config/SqsConfig.java` | Identical `SqsClient` bean + idempotent queue bootstrap |
| notification-service | `consumer/SqsConsumerService.java` | `@SqsListener` routing to `LoanNotificationHandler` (`@ConditionalOnProperty`) |

**Toggle behaviour:**
- `AWS_SQS_ENABLED=false` (default): unchanged — loan-service publishes to RabbitMQ; `@RabbitListener` handles all events
- `AWS_SQS_ENABLED=true`: loan-service publishes to SQS; `@SqsListener` handles loan events; `@RabbitListener` stays active for `user.registered` from user-service

**Key gotchas:**
- Spring Cloud AWS BOM artifact ID is `spring-cloud-aws-dependencies` (not `spring-cloud-aws-bom` — that doesn't exist on Maven Central); version `3.1.1`
- `CreateQueueRequest.attributes()` takes `Map<QueueAttributeName, String>` — use `QueueAttributeName.REDRIVE_POLICY`, not a plain string key
- `spring.cloud.aws.sqs.enabled: ${AWS_SQS_ENABLED:false}` required in notification-service `application.yml` — prevents Spring Cloud AWS auto-config from connecting when SQS is off
- DLQ ARN format for LocalStack: `arn:aws:sqs:ap-south-1:000000000000:loan-events-dlq`
- Visibility timeout: 30s (SQS default) — redelivers up to `maxReceiveCount=3` times before routing to DLQ

**Test coverage:** loan-service 8 tests (6 existing + 2 SQS-path); notification-service 25 tests (19 existing + 6 routing)

**LocalStack CLI:**
```bash
# Queues are auto-created by SqsConfig on startup; manual bootstrap if needed:
awslocal sqs create-queue --queue-name loan-events-dlq --region ap-south-1
awslocal sqs create-queue \
  --queue-name loan-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"arn:aws:sqs:ap-south-1:000000000000:loan-events-dlq\",\"maxReceiveCount\":\"3\"}"}' \
  --region ap-south-1

# Inspect DLQ after failures
awslocal sqs receive-message \
  --queue-url http://localhost:4566/000000000000/loan-events-dlq \
  --region ap-south-1
```

| File to read | When changing |
|---|---|
| `loan-service/config/SqsConfig.java` | SQS queue config or DLQ policy |
| `loan-service/service/LoanService.java` → `publishEvent()` | Adding new event types to publish |
| `notification-service/consumer/SqsConsumerService.java` | Adding new SQS event routes |

---

## LocalStack Feature Roadmap

Features to add next (in suggested order):

### Next up — AWS SES — Transactional Email (replace SendGrid)

**Why:** SES costs $0.10/1000 emails vs SendGrid free tier limits. LocalStack emulates SES sending + can verify sandbox domains locally.

**Plan:**
1. Add `ses` to pom.xml
2. Create `SesEmailChannel.java` implementing `NotificationChannel` — replaces `SendGridEmailChannel`
3. HTML templates are already in `LoanNotificationHandler.java` — reuse them
4. Toggle: `NOTIFICATION_SES_ENABLED=true` → SES active; `NOTIFICATION_EMAIL_ENABLED=true` → SendGrid

### Secrets Manager — Secure Credential Rotation

**Why:** Currently DB passwords + API keys are plain env vars. Secrets Manager enables rotation without redeployment and is a standard enterprise pattern.

**Plan:**
1. Add `secretsmanager` to pom.xml
2. `SecretsConfig.java` — on startup, loads secrets from `smartlend/db-credentials` and `smartlend/api-keys`
3. Override DataSource password from Secrets Manager value
4. In production: enable auto-rotation (30-day) for DB creds

### Step Functions — Loan Lifecycle Orchestration

**Why:** Replaces the imperative `if (APPROVED) ... else ...` block in `LoanService.processAdminDecision()` with a state machine that has explicit retries, compensating transactions, and a visible audit trail in the AWS console.

**States:** PENDING → [admin decision] → APPROVED (with EMI generation + notification) / REJECTED (with notification) → ACTIVE → CLOSED

**Plan:**
1. Add `sfn` to pom.xml
2. Define state machine JSON in `loan-service/src/main/resources/loan-lifecycle-sfn.json`
3. `StepFunctionsConfig.java` — creates state machine via LocalStack
4. `LoanService.processAdminDecision()` → starts execution instead of direct business logic
5. Lambda functions per state (or Activity tasks polling from loan-service)

### Bedrock — AI Credit Scoring (upgrade ai-scoring service)

**Why:** Replace the rule-based scoring model in `ai-scoring/app/main.py` with a real foundation model call (Titan, Claude Haiku). LocalStack Pro emulates Bedrock endpoints.

**Plan:**
1. Add Bedrock SDK to `ai-scoring` requirements
2. `POST /score` → format loan data as a prompt → call `bedrock-runtime:InvokeModel` with Titan or Claude
3. Parse structured JSON response back to `ScoringResponse` format
4. Add `AI_PROVIDER` env var toggle: `rule-based` (current) vs `bedrock`
