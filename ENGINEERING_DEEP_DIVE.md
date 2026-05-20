
# SmartLend — Engineering Deep Dive

> A complete learning guide: how the system was designed, why every decision was made,
> the design patterns used, LLD per service, system design, and the hardest questions answered.

---

## How to Read This Guide

Work through the parts in order. Each part builds on the last.

1. **Mental Model** — understand the big picture before touching any code
2. **Build Order** — how you would build this from scratch, step by step
3. **Design Patterns** — every pattern used and *why* it was chosen
4. **LLD per Service** — class relationships, data flow, key decisions
5. **System Design (HLD)** — the whole picture: tradeoffs, bottlenecks, scalability
6. **Core Algorithm Walkthroughs** — JWT, EMI, credit scoring explained line by line
7. **Q&A** — the hardest "why" questions answered in depth

---

## Part 1 — The Mental Model

Before reading any code, you need one mental picture of how all pieces connect.

```
┌─────────────────────────────────────────────────────────────┐
│                        BROWSER                              │
│  React 18 + TypeScript + Tailwind                           │
│                                                             │
│  Two Axios instances:                                       │
│    userHttp  → localhost:8081  (sends cookie automatically) │
│    loanHttp  → localhost:8082  (sends Bearer token header)  │
└────────┬───────────────────────────┬────────────────────────┘
         │ HttpOnly cookie            │ Authorization: Bearer <jwt>
         │                            │ X-User-Id header
         ▼                            ▼
┌─────────────────┐        ┌──────────────────────────────────┐
│  user-service   │        │         loan-service              │
│  :8081          │        │         :8082                     │
│                 │        │                                   │
│  • Register     │        │  • Apply for loan                 │
│  • Login        │        │    → calls ai-scoring HTTP        │
│  • Logout       │        │    → saves loan to DB             │
│  • /me          │        │    → publishes to RabbitMQ        │
│                 │        │                                   │
│  Neon userdb    │        │  • Admin approve/reject           │
│  (PostgreSQL)   │        │    → calculates EMI               │
│  Upstash Redis  │        │    → generates schedule           │
└────────┬────────┘        │    → publishes to RabbitMQ        │
         │ RabbitMQ        │                                   │
         │ user.registered │  Neon loandb (PostgreSQL)         │
         │                 └──────────┬───────────────────────┘
         │                            │ RabbitMQ
         │                            │ loan.applied
         │                            │ loan.approved
         │                            │ loan.rejected
         ▼                            ▼
┌─────────────────────────────────────────────────────────────┐
│              notification-service  :8083                    │
│                                                             │
│  RabbitMQ consumer → LoanEventConsumer                      │
│       ↓                                                     │
│  LoanNotificationHandler (builds HTML templates)            │
│       ↓                                                     │
│  NotificationDispatcher (routes to enabled channels)        │
│       ↓                        ↓                            │
│  SendGridEmailChannel    WhatsAppChannel                    │
│  (HTML email)            (Meta Graph API template)          │
└─────────────────────────────────────────────────────────────┘

           ┌──────────────────┐
           │   ai-scoring     │
           │   :8000          │
           │   FastAPI/Python │
           │                  │
           │  POST /score     │
           │  → DTI calc      │
           │  → tenure adj    │
           │  → employment    │
           │  → income band   │
           │  → 300-900 score │
           └──────────────────┘
```

**Three fundamental communication styles in this system:**
1. **Synchronous HTTP** — Browser ↔ Services, loan-service → ai-scoring (needs result immediately)
2. **Asynchronous Messaging** — Services → RabbitMQ → notification-service (fire and forget)
3. **Cookie** — Browser ↔ user-service only (HttpOnly, not accessible by JavaScript)

---

## Part 2 — Build Order (How to Build This From Scratch)

If you were building SmartLend from zero, here is the exact sequence and the *thinking* behind each step.

### Step 1 — Define the Domain Model

Before writing any code, answer: *what are the core entities?*

```
User
  id (UUID)
  email (unique)
  password (BCrypt hash — NEVER store plaintext)
  fullName
  phone
  monthlyIncome
  employmentType (SALARIED | SELF_EMPLOYED | BUSINESS)
  role (APPLICANT | ADMIN)
  kycStatus (PENDING | VERIFIED | REJECTED)

Loan
  id (UUID)
  userId (foreign key to User — but in a different DB!)
  principalAmount
  tenureMonths
  interestRate (set by admin on approval)
  emiAmount (calculated on approval)
  totalPayable (emi × tenure)
  creditScore (from AI service)
  riskLabel (LOW | MEDIUM | HIGH)
  status (PENDING | APPROVED | REJECTED | ACTIVE | CLOSED | DEFAULTED)
  purpose
  adminNote

EmiPayment
  loanId
  installmentNumber
  amount (= emiAmount)
  principalComponent (how much goes to reducing principal)
  interestComponent (how much is interest)
  remainingBalance (outstanding after this payment)
  dueDate
  status (PENDING | PAID | OVERDUE)
```

**Key insight:** `Loan` stores `userId` as a plain string, not a JPA foreign key. Why?
Because `User` lives in a completely different database (`userdb`) on a different service.
JPA foreign keys only work within the same database. Cross-service relationships are
maintained by convention (the ID string), not by database constraint.

### Step 2 — Design the API Contract (Before Writing Any Code)

Define your REST endpoints as a contract. Downstream teams (or your future self) depend on them.

```
user-service:
  POST /api/auth/register     → RegisterRequest → AuthResponse + cookie
  POST /api/auth/login        → LoginRequest    → AuthResponse + cookie
  POST /api/auth/logout       →                 → clears cookie
  GET  /api/auth/me           → (cookie)        → AuthResponse + fresh token
  GET  /api/auth/profile/{id} → (internal)      → UserProfileResponse

loan-service:
  POST /api/loans/apply           → ApplyRequest + 3 headers → LoanResponse
  GET  /api/loans/my              → X-User-Id header          → LoanResponse[]
  GET  /api/loans/{id}/emi-schedule →                         → EmiScheduleItem[]
  GET  /api/loans/admin/all       → (ADMIN)                   → LoanResponse[]
  PUT  /api/loans/admin/{id}/decision → AdminDecisionRequest  → LoanResponse
  GET  /api/loans/admin/summary   → (ADMIN)                   → LoanSummary

ai-scoring:
  POST /score → ScoringRequest → ScoringResponse
  GET  /health
```

**Rule:** Finalize DTO field names BEFORE frontend development starts.
Changing `loanId` to `id` after the frontend is built breaks everything silently.

### Step 3 — Implement user-service

Order of implementation within the service:

1. `User.java` — JPA entity with Lombok annotations
2. `UserRepository.java` — Spring Data JPA (one interface, zero SQL for basic CRUD)
3. `JwtUtil.java` — JWT generation and validation (stateless auth)
4. `SecurityConfig.java` — filter chain, JWT filter, CORS, BCrypt bean
5. `AuthService.java` — business logic: register, login, createAdmin
6. `AuthController.java` — HTTP layer: validates input, calls service, sets cookies
7. `GlobalExceptionHandler.java` — consistent error format for all 4xx/5xx
8. `RequestLoggingFilter.java` — MDC request ID for distributed tracing

### Step 4 — Implement ai-scoring (Python)

This is intentionally a separate service because:
- ML model iterations don't need Java recompilation
- Python ecosystem (sklearn, numpy, pandas) is richer for data science
- Can be swapped from rule-based → trained model without touching Java

Implementation order:
1. Define `ScoringRequest` and `ScoringResponse` Pydantic models
2. Write `compute_credit_score()` — pure function, no I/O
3. Write `get_risk_and_rate()` — pure function
4. Wire into FastAPI `/score` endpoint
5. Add logging middleware with `request_id`

### Step 5 — Implement loan-service

1. `Loan.java`, `EmiPayment.java` — entities
2. `LoanRepository.java`, `EmiPaymentRepository.java`
3. `AiScoringClient.java` — HTTP call to ai-scoring with fallback
4. `UserServiceClient.java` — HTTP call to user-service for email/name/phone
5. `LoanService.java` — core logic: apply, EMI calculation, decision
6. `RabbitMQConfig.java` — exchange declaration, message converter
7. `LoanController.java` — HTTP layer
8. `LoanSecurityConfig.java` — same JWT pattern as user-service

### Step 6 — Implement notification-service

Design this as a plugin architecture from the start.

1. `NotificationPayload.java` — immutable record (all channels share this)
2. `NotificationChannel.java` — interface (the extension point)
3. `SendGridEmailChannel.java` — first implementation
4. `WhatsAppChannel.java` — second implementation
5. `NotificationDispatcher.java` — routes to all enabled channels
6. `LoanNotificationHandler.java` — builds HTML, calls dispatcher
7. `LoanEventConsumer.java` — RabbitMQ listener, routes by `type` field
8. `RabbitMQConfig.java` — declares exchange + ALL bindings (ownership matters)
9. `WhatsAppWebhookController.java` — Meta's verification + delivery webhooks

### Step 7 — Build the Frontend

1. Define TypeScript interfaces in `types/index.ts` matching backend DTOs exactly
2. Create Axios instances in `services/api.ts` (two instances — different auth strategies)
3. Implement `AuthContext.tsx` — in-memory token, localStorage profile, cookie restore
4. Build auth pages (Login, Register)
5. Build applicant dashboard and loan application form
6. Build admin dashboard with decision panel
7. Add EMI schedule with Recharts

---

## Part 3 — Design Patterns

### 1. Strategy Pattern — NotificationChannel

**Where:** `notification-service`
**Files:** `NotificationChannel.java`, `SendGridEmailChannel.java`, `WhatsAppChannel.java`

The Strategy Pattern defines a family of algorithms (notification delivery mechanisms),
encapsulates each one, and makes them interchangeable without changing the client.

```
«interface»
NotificationChannel
─────────────────────
+ channelName(): String
+ isEnabled(): boolean
+ send(payload): void
        ▲
        │ implements
        ├─────────────────────── SendGridEmailChannel
        ├─────────────────────── WhatsAppChannel
        ├─────────────────────── SmsChannel (stub)
        └─────────────────────── PushChannel (stub)
```

**Why this pattern?**
Without Strategy, you'd write:
```java
if (emailEnabled) sendEmail(payload);
if (whatsappEnabled) sendWhatsApp(payload);
if (smsEnabled) sendSms(payload);
```
Every new channel requires modifying the dispatcher. With Strategy + Spring DI, you
just create a new `@Component` class that implements `NotificationChannel` — the
dispatcher discovers it automatically via `List<NotificationChannel>` injection.

**The Dispatcher auto-discovers all channels:**
```java
@RequiredArgsConstructor  // Spring injects ALL NotificationChannel beans as a List
public class NotificationDispatcher {
    private final List<NotificationChannel> channels;  // auto-populated by Spring

    public void dispatch(NotificationPayload payload) {
        channels.stream()
            .filter(NotificationChannel::isEnabled)
            .forEach(channel -> channel.send(payload));  // each strategy executes
    }
}
```
**Open/Closed Principle:** open for extension (new channels), closed for modification (dispatcher never changes).

---

### 2. Repository Pattern — Spring Data JPA

**Where:** Every service
**Files:** `UserRepository.java`, `LoanRepository.java`, `EmiPaymentRepository.java`

```java
// This interface has ZERO implementation code
public interface UserRepository extends JpaRepository<User, String> {
    boolean existsByEmail(String email);
    Optional<User> findByEmail(String email);
}
```

Spring generates the full implementation at runtime. The repository abstracts
persistence from business logic — `AuthService` never writes SQL. If you
swapped PostgreSQL for MongoDB, only the repository changes.

**Why UUID as primary key instead of Long?**
- UUID is globally unique — safe to generate in application code without DB roundtrip
- Prevents ID enumeration attacks (`GET /users/1`, `/users/2`, etc.)
- Works across microservices without ID collision

---

### 3. Builder Pattern — Lombok @Builder

**Where:** Every entity and DTO
**Example:**
```java
User user = User.builder()
    .email(request.getEmail())
    .password(passwordEncoder.encode(request.getPassword()))
    .role(User.Role.APPLICANT)
    .kycStatus(User.KycStatus.PENDING)
    .build();
```

Without Builder, you'd either:
- Use a constructor with 10 parameters (easy to pass arguments in wrong order)
- Use setters (object is mutable during construction — dangerous)

Builder makes construction readable and prevents partially-constructed objects.

---

### 4. Filter Chain Pattern — Spring Security

**Where:** `SecurityConfig.java`, `RequestLoggingFilter.java`

Every HTTP request passes through a chain of filters before reaching a controller.

```
HTTP Request
     │
     ▼
RequestLoggingFilter        ← assigns requestId, starts timer
     │
     ▼
JwtAuthFilter               ← reads cookie/header, validates JWT, sets SecurityContext
     │
     ▼
Spring Security Filters      ← checks authorization rules
     │
     ▼
DispatcherServlet           ← routes to controller
     │
     ▼
Controller Method           ← your business code
     │
     ▼
(response travels back up through filters)
     │
RequestLoggingFilter        ← logs "GET /api/loans → 200 (45ms)", clears MDC
```

`OncePerRequestFilter` guarantees each filter runs exactly once per request (not
multiple times due to forward/include chains).

---

### 5. Factory + Decorator Pattern — `createHttpClient` & Axios Interceptors

**Where:** `frontend/src/services/api.ts`

#### The problem with manual axios instances

The naive approach creates each instance separately and duplicates interceptor
logic across them:

```typescript
// ❌ Duplicated — both instances wire the same 401 handler separately
userHttp.interceptors.response.use(res => res, err => {
    if (err.response?.status === 401) handleUnauthorized();
    return Promise.reject(err);
});
loanHttp.interceptors.response.use(res => res, err => {
    if (err.response?.status === 401) handleUnauthorized();
    return Promise.reject(err);
});
// No timeout. No retry. No request tracing. Each new service doubles the boilerplate.
```

#### The factory solution

```typescript
function createHttpClient({
  baseURL,
  withCredentials = false,
  timeout = 10_000,   // fail after 10s — not hang forever
  retries = 2,
  getToken,           // lazy callback — reads _token at request time
  onUnauthorized,     // called exactly once on 401
}: HttpClientOptions): AxiosInstance {
  const instance = axios.create({ baseURL, withCredentials, timeout,
    headers: { 'Content-Type': 'application/json' } });

  // 1. Inject Bearer token + correlation ID
  instance.interceptors.request.use((config) => {
    const token = getToken?.();
    if (token) config.headers.Authorization = `Bearer ${token}`;
    config.headers['X-Request-Id'] = crypto.randomUUID(); // traces request in server logs
    return config;
  });

  // 2. Retry on transient failures only
  instance.interceptors.response.use(res => res, async (err) => {
    const status = err.response?.status;
    if (status === 401) { onUnauthorized?.(); return Promise.reject(err); }

    // 4xx = client mistake → surface immediately, never retry
    const isClientError = status >= 400 && status < 500;
    const canRetry = !isClientError && config._retryCount < retries;
    if (canRetry) {
      config._retryCount++;
      await sleep(2 ** config._retryCount * 300); // 300ms → 600ms exponential backoff
      return instance(config);
    }
    return Promise.reject(err);
  });

  return instance;
}
```

Three instances, zero duplicated interceptor logic:

```typescript
const userHttp = createHttpClient({ baseURL: USER_BASE, withCredentials: true,
                                    onUnauthorized: handleUnauthorized });
const loanHttp = createHttpClient({ baseURL: LOAN_BASE, getToken,
                                    onUnauthorized: handleUnauthorized });
const aiHttp   = createHttpClient({ baseURL: AI_BASE }); // no auth
```

#### What each feature buys you

| Feature | Without it | With it |
|---|---|---|
| `timeout: 10_000` | Request hangs forever if server stalls | Throws after 10s, triggers retry |
| `retries: 2` with backoff | One flaky request = user sees error | 2 auto-retries (300ms, 600ms) absorb transient spikes |
| 4xx never retried | — | `422 VALIDATION_ERROR` surfaces immediately instead of retrying 3× |
| `X-Request-Id` header | Can't correlate browser request with server log line | UUID matches `[requestId]` in Logback MDC — trace end-to-end |
| `getToken` callback | Stale closure captures token value at creation time | Reads current `_token` lazily at request time |
| `onUnauthorized` callback | Each instance re-implements logout logic | Single handler, wired once per instance |

#### Interceptors are the Decorator pattern

The calling code never knows cross-cutting concerns exist:

```typescript
// This is all the page sees — no headers, no retry logic, no 401 handling
loanApi.myLoans(userId);

// What actually happens under the hood:
// 1. Request interceptor adds Authorization + X-Request-Id
// 2. Axios sends the request (with 10s timeout)
// 3. If 5xx → retry up to 2× with exponential backoff
// 4. If 401 → handleUnauthorized() clears session and redirects
// 5. Page receives clean data or a normalized error
```

This is the Decorator pattern: behavior layered onto the base HTTP call
transparently, without modifying the caller.

---

### 6. Generic Hook Factory Pattern — `useQuery`

**Where:** `frontend/src/hooks/useQuery.ts`

Every page that loads data had the same ~15 lines of boilerplate:

```typescript
// ❌ Repeated in useLoans, useAdminLoans, EmiSchedule, and every future page
const [loans,     setLoans    ] = useState<Loan[]>([]);
const [isLoading, setIsLoading] = useState(true);
const [error,     setError    ] = useState<string | null>(null);

const fetch = useCallback(async () => {
  setIsLoading(true);
  setError(null);
  try {
    setLoans(await loanApi.myLoans(userId));
  } catch (err) {
    setError(getErrorMessage(err));
  } finally {
    setIsLoading(false);
  }
}, [userId]);

useEffect(() => { fetch(); }, [fetch]);
```

The `useQuery` hook extracts this into a single reusable abstraction:

```typescript
export function useQuery<T>(
  queryFn: () => Promise<T>,
  deps: DependencyList,
  { enabled = true, initialData }: QueryOptions<T> = {},
): QueryResult<T> {
  const [data,      setData     ] = useState<T | null>(initialData ?? null);
  const [isLoading, setIsLoading] = useState(enabled);
  const [error,     setError    ] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const run = useCallback(async () => {
    if (!enabled) return;
    abortRef.current?.abort();                    // cancel any in-flight request
    abortRef.current = new AbortController();
    const { signal } = abortRef.current;

    setIsLoading(true); setError(null);
    try {
      const result = await queryFn();
      if (!signal.aborted) setData(result);       // ignore stale responses
    } catch (err) {
      if (!signal.aborted) setError(getErrorMessage(err));
    } finally {
      if (!signal.aborted) setIsLoading(false);
    }
  }, [enabled, ...deps]);   // queryFn excluded — callers use stable arrow fns

  useEffect(() => {
    void run();
    return () => abortRef.current?.abort();       // cleanup on unmount
  }, [run]);

  return { data, isLoading, error, refetch: run };
}
```

Every hook and page reduces to one call:

```typescript
// useLoans.ts — was 35 lines, now 5
const { data, ...rest } = useQuery(() => loanApi.myLoans(userId), [userId]);
return { loans: data ?? [], ...rest };

// EmiSchedule.tsx — was 8 lines of useEffect+useState, now 4
const { data, isLoading, error } = useQuery(
  () => loanApi.emiSchedule(loanId!),
  [loanId],
  { enabled: !!loanId },   // don't fire until loanId is available from useParams
);
const schedule = data ?? [];
```

#### Key design decisions

**`data: T | null` not `T | undefined`**

TypeScript's destructuring default (`= []`) only fires for `undefined`, not `null`.
`useState` initialises to `null`. If you type `data: T | undefined` but the
runtime value is `null`, you get `TS2322` at every call site. Keeping `data: T | null`
consistently forces callers to use nullish coalescing: `data ?? []` — which handles
both `null` and `undefined`.

```typescript
// ❌ Only fires when data is undefined — breaks with null from useState
const { data: schedule = [] } = useQuery(...);

// ✓ Fires for both null and undefined — correct
const schedule = data ?? [];
```

**AbortController prevents stale state**

Without abort, this race condition is possible:

```
t=0ms  deps change (userId A) → fetch A starts
t=50ms deps change (userId B) → fetch B starts
t=100ms fetch B completes  → setData(B's data) ✓
t=200ms fetch A completes  → setData(A's data) ✗ — old data overwrites new
```

With abort, when deps change `run()` calls `abortRef.current?.abort()` first.
The signal is checked in `.then()` — if aborted, `setData` is skipped.

**`void run()` in useEffect**

`useEffect` must return either `undefined` or a cleanup function. `run()` returns
a `Promise`. Returning a Promise from `useEffect` is silently ignored by React but
TypeScript flags it. `void run()` explicitly discards the Promise so the return
type is `undefined`, satisfying both React and TypeScript.

**`enabled` option**

Prevents firing before data is ready:
```typescript
const { loanId } = useParams();   // string | undefined

// Without enabled: runs immediately with loanId = undefined, crashes the API call
// With enabled: waits until loanId is a string
useQuery(() => loanApi.emiSchedule(loanId!), [loanId], { enabled: !!loanId });
```

---

### 7. Circuit Breaker / Fallback Pattern — AiScoringClient & UserServiceClient

**Where:** `loan-service/client/AiScoringClient.java`, `loan-service/client/UserServiceClient.java`

**The problem with plain try/catch:**

```java
// ❌ Naive — waits for every timeout, no protection
public ScoringDto.ScoringResponse getScore(ScoringDto.ScoringRequest request) {
    try {
        return restTemplate.postForObject(aiScoringUrl + "/score", request, ...);
    } catch (Exception e) {
        return fallbackScore(request);
    }
}
```

If ai-scoring is down, every single loan application waits the full 5-second read
timeout before hitting the fallback. Under load, you accumulate hundreds of blocked
threads — this cascades into your loan-service becoming unresponsive.

**The circuit breaker solution (Resilience4j):**

```java
@CircuitBreaker(name = "ai-scoring", fallbackMethod = "fallbackScore")
public ScoringDto.ScoringResponse getScore(ScoringDto.ScoringRequest request) {
    return restTemplate.postForObject(aiScoringUrl + "/score", request,
        ScoringDto.ScoringResponse.class);
}

// Signature must match getScore() PLUS a trailing Throwable parameter
private ScoringDto.ScoringResponse fallbackScore(ScoringDto.ScoringRequest req, Throwable t) {
    log.error("AI scoring circuit breaker fallback triggered. Cause: {}", t.getMessage());
    // DTI-ratio rule: amount / (monthlyIncome * 12)
    double ratio = req.getRequestedAmount() / (req.getMonthlyIncome() * 12);
    if (ratio < 2)      return score(750, "LOW",    10.5, "APPROVE");
    else if (ratio < 5) return score(620, "MEDIUM", 14.0, "APPROVE");
    else                return score(480, "HIGH",   18.0, "REJECT");
}
```

**Three states — how the circuit behaves:**

```
CLOSED (normal)
  │  All calls go through to ai-scoring
  │  Sliding window tracks last 10 calls
  │  If ≥50% fail → trip to OPEN
  ▼
OPEN (tripped)
  │  All calls SHORT-CIRCUIT immediately → fallbackScore() called instantly
  │  No network calls, no thread blocking, no timeout wait
  │  After 30s → transition to HALF-OPEN
  ▼
HALF-OPEN (probing)
  │  Allow 3 test calls through
  │  If they succeed → back to CLOSED
  │  If they fail → back to OPEN for another 30s
```

**Key difference:** When the circuit is OPEN, `fallbackScore()` is called in
microseconds — no 5-second timeout. This protects the entire loan-service from
being held hostage by a single downstream dependency.

**Two breakers, different tolerances:**

| Breaker | Open state | Rationale |
|---|---|---|
| `ai-scoring` | 30s | Scoring is critical path — give the model time to recover |
| `user-service` | 15s | Profile lookup is best-effort — shorter wait, null fallback is fine |

**Configuration in `application.yml`:**

```yaml
resilience4j:
  circuitbreaker:
    instances:
      ai-scoring:
        sliding-window-size: 10
        minimum-number-of-calls: 5        # need at least 5 before evaluating
        failure-rate-threshold: 50        # trip if ≥50% of last 10 calls fail
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true
        record-exceptions:
          - java.lang.Exception
      user-service:
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 15s
        permitted-number-of-calls-in-half-open-state: 2
        automatic-transition-from-open-to-half-open-enabled: true
```

**AOP requirement:** Resilience4j's `@CircuitBreaker` works via Spring AOP proxy.
The proxy wraps the bean method call and routes to fallback when the breaker is open.
This requires `spring-boot-starter-aop` on the classpath — without it, the annotation
is silently ignored and you get no protection at all.

**Health visibility:** Circuit breaker state (CLOSED/OPEN/HALF-OPEN) is exposed at
`/actuator/health` via `management.health.circuitbreakers.enabled: true`. This lets
you see in production exactly which downstream dependency is tripped and when it
recovered — invaluable for incident response.

If the AI service is down, the loan application still works using a simpler
rule-based fallback. The system degrades gracefully instead of failing completely.

---

### 7. DTO Pattern — Separating API Contract from Domain Model

**Where:** `AuthDto.java`, `LoanDto.java`

The `User` entity has fields like `password`, `createdAt`, `updatedAt` that
should never be returned in an API response. DTOs create a separate API contract:

```
User (domain model — internal)     AuthResponse (DTO — external API)
────────────────────────────       ─────────────────────────────────
id                                 token
email                              userId
password ← NEVER exposed           email
fullName                           fullName
phone                              role
panCard                            kycStatus
role                               monthlyIncome
kycStatus                          employmentType
createdAt ← internal only
updatedAt ← internal only
```

**Rule:** Never return JPA entities from controllers. Always map to DTOs.

---

### 8. Singleton Pattern — Spring Beans

Every `@Component`, `@Service`, `@Repository`, `@Controller` is a singleton by default.
Spring creates one instance and reuses it for every request.

This is safe because services are **stateless** — they don't store per-request data
as instance fields. All per-request state goes into method parameters or MDC.

---

### 9. Observer/Event Pattern — RabbitMQ

**Where:** AuthService (publisher) → CloudAMQP → LoanEventConsumer (subscriber)

```
Publisher                    Message Broker              Subscriber
AuthService.register()  →   RabbitMQ Exchange      →   LoanEventConsumer
                            smartlend.exchange           .handleLoanEvent()
                            routing: user.registered
                                                         ↓
                                                    LoanNotificationHandler
                                                    .handleUserRegistered()
                                                         ↓
                                                    NotificationDispatcher
                                                    → SendGrid email
                                                    → WhatsApp message
```

The publisher doesn't know anything about notifications. It just fires an event
and moves on. This is **temporal decoupling** — publisher and subscriber don't
need to be running simultaneously.

---

## Part 4 — Low-Level Design per Service

### user-service LLD

```
AuthController
──────────────────────────────────────────────────────
• @PostMapping /register  → register(request, response)
• @PostMapping /login     → login(request, response)
• @PostMapping /logout    → logout(response)
• @GetMapping  /me        → me(principal, response)
• @GetMapping  /profile/{userId} → getProfile(userId)
• @PostMapping /admin/create-admin → createAdmin(request, response)

Responsibilities:
  1. Validate HTTP input (@Valid annotation)
  2. Call AuthService
  3. Set/clear HttpOnly cookie on response
  4. Return AuthResponse JSON

────────────────────────────────────────────────────────

AuthService
──────────────────────────────────────────────────────
Dependencies: UserRepository, PasswordEncoder, JwtUtil, RabbitTemplate

+ register(RegisterRequest) → AuthResponse
    1. existsByEmail? throw "Email already registered"
    2. build User with hashed password + APPLICANT role
    3. userRepository.save(user) — transactional
    4. rabbitTemplate.convertAndSend(exchange, "user.registered", Map{...})
       NOTE: if RabbitMQ fails AFTER save, we have an inconsistency!
       That's why register() is @Transactional — if RabbitMQ throws, DB rolls back
    5. generateToken + buildAuthResponse

+ login(LoginRequest) → AuthResponse
    1. findByEmail or throw "Invalid credentials"
    2. passwordEncoder.matches(raw, hashed)? or throw "Invalid credentials"
    3. generateToken + buildAuthResponse

────────────────────────────────────────────────────────

JwtUtil
──────────────────────────────────────────────────────
+ generateToken(userId, email, role) → String
    → Jwts.builder()
         .subject(userId)         ← who this token represents
         .claim("email", email)   ← custom claims
         .claim("role", role)
         .issuedAt(now)
         .expiration(now + 24h)
         .signWith(hmacKey)       ← HMAC-SHA256 signature
         .compact()               ← serialize to "header.payload.signature"

+ validateToken(token) → Claims
    → Jwts.parser().verifyWith(key).parseSignedClaims(token).getPayload()
    → throws JwtException if signature invalid or expired

────────────────────────────────────────────────────────

SecurityConfig (filter chain)
──────────────────────────────────────────────────────
JwtAuthFilter (OncePerRequestFilter)
  1. Look for "auth_token" cookie
  2. If not found, look for "Authorization: Bearer <token>" header
  3. If token found: validateToken → extract userId + role
  4. Set SecurityContext: UsernamePasswordAuthenticationToken(userId, null, [ROLE_APPLICANT])
  5. chain.doFilter(request, response) — continue

Authorization rules:
  /register, /login, /logout, /profile/** → permitAll()
  /admin/**                               → hasRole("ADMIN")
  everything else                         → authenticated()

Cookie flow:
  On login/register → response.addCookie(new Cookie("auth_token", token) {
      setHttpOnly(true)
      setSecure(true in prod)
      setSameSite("Lax" local / "None" prod)
      setMaxAge(86400)  ← 24 hours
  })
  On logout → same cookie but maxAge=0 (browser deletes it)
```

---

### loan-service LLD

```
LoanService — the most complex class in the system
──────────────────────────────────────────────────────

applyForLoan(userId, ApplyRequest, monthlyIncome, employmentType):
  1. Build ScoringRequest → call aiScoringClient.getScore()
     (synchronous HTTP — we NEED the score to store the loan)
  2. Build Loan entity with score + PENDING status
  3. loanRepository.save(loan)
  4. Call userServiceClient.getProfile(userId)
     (needed for email/name/phone in the RabbitMQ event)
  5. rabbitTemplate.convertAndSend(..., "loan.applied", Map{...})
  6. Return LoanResponse

processAdminDecision(loanId, AdminDecisionRequest):
  1. loanRepository.findById or throw "Loan not found"
  2. loan.setStatus(APPROVED or REJECTED)
  3. If APPROVED:
     a. Set interestRate (from admin input, default 12%)
     b. calculateEmi(principal, rate, months) — EMI formula
     c. setEmiAmount, setTotalPayable
     d. setDisbursedDate(today), setNextEmiDate(today + 1 month)
     e. generateEmiSchedule(loan) — creates N EmiPayment rows
     f. Publish "loan.approved" event to RabbitMQ
  4. If REJECTED:
     a. Publish "loan.rejected" event to RabbitMQ
  5. loanRepository.save(loan) → return LoanResponse

────────────────────────────────────────────────────────

EMI Formula:
  Standard reducing-balance amortization
  
  EMI = P × r × (1+r)^n
        ─────────────────
           (1+r)^n − 1
  
  where:
    P = principal amount
    r = monthly interest rate = annualRate / 12 / 100
    n = tenure in months
  
  Example: P=300000, rate=12%, n=24
    r = 12/12/100 = 0.01
    (1.01)^24 = 1.2697...
    EMI = 300000 × 0.01 × 1.2697 / (1.2697 − 1)
        = 3809.1 / 0.2697
        ≈ ₹14,124/month

────────────────────────────────────────────────────────

EMI Schedule generation (reducing balance):
  balance = principalAmount
  for each installment 1..n:
    interest   = balance × monthlyRate       ← interest on CURRENT balance
    principal  = emiAmount − interest        ← remainder reduces balance
    balance    = balance − principal         ← outstanding after payment
    save EmiPayment(installment, emiAmount, principal, interest, balance, dueDate)

  Key insight: early installments are mostly interest, later ones mostly principal
  Month 1 of ₹300k loan at 12%: interest = ₹3000, principal = ₹11,124
  Month 24: interest ≈ ₹112, principal ≈ ₹14,012

────────────────────────────────────────────────────────

Header-based user identity:
  loan-service has NO user database. It can't look up a user from a JWT alone
  because JWT only contains userId (UUID), not income or employment type.

  Solution: frontend sends custom headers with every loan request:
    X-User-Id: <userId>
    X-Monthly-Income: 50000
    X-Employment-Type: SALARIED

  The JWT still validates the *identity*. The headers carry the *data*.
  This is a deliberate tradeoff — avoids an extra HTTP call on every loan request.
```

---

### notification-service LLD

```
Event flow (bottom-up trace):

RabbitMQ delivers message
         ↓
@RabbitListener LoanEventConsumer.handleLoanEvent(Map<String,Object> event)
  - extracts event.get("type")
  - routes via switch:
    "APPROVED" → notificationHandler.handleLoanApproved(event)
         ↓
LoanNotificationHandler.handleLoanApproved(event)
  - extracts: loanId, userEmail, userName, amount, emiAmount, tenure, rate
  - builds: subject (String), plain text body (String), HTML body (String)
  - constructs: NotificationPayload record
  - calls: dispatcher.dispatch(payload)
         ↓
NotificationDispatcher.dispatch(payload)
  - iterates List<NotificationChannel> (Spring-injected)
  - filters: channel.isEnabled()
  - for each enabled: channel.send(payload)  ← try/catch so one failure doesn't stop others
         ↓
SendGridEmailChannel.send(payload)           WhatsAppChannel.send(payload)
  - constructs SendGrid Mail object            - normalizePhone() → E.164 format
  - sets from, to, subject                     - buildRequestBody() → Map template
  - sets html content (payload.htmlBody())     - POST to Meta Graph API
  - sendGrid.api(mail)                         - log success/failure

────────────────────────────────────────────────────────

Phone normalisation in WhatsAppChannel:
  Input → Output (all become 12-digit without '+')
  "9876543210"     → "919876543210"   (10-digit: prepend "91")
  "+919876543210"  → "919876543210"   (strip '+', digits only)
  "919876543210"   → "919876543210"   (already correct: 91+10 = 12 digits)
  "09876543210"    → "919876543210"   (11-digit with leading 0: replace 0 with 91)

────────────────────────────────────────────────────────

Why notification-service OWNS all RabbitMQ bindings:
  A binding connects a routing key to a queue.
  If user-service declares "user.registered → loan.events.queue", but
  notification-service starts before user-service, the binding doesn't exist yet
  and messages are lost.

  Solution: notification-service (the CONSUMER) declares ALL bindings via Declarables.
  This way, as soon as the consumer starts, it guarantees the bindings exist.
  Publishers don't need to know which queues exist — they just send to the exchange.
```

---

### ai-scoring LLD

```
compute_credit_score(req: ScoringRequest) → int [300-900]:

  score = 500  ← base score

  Step 1: DTI (Debt-to-Income) adjustment
    DTI = requestedAmount / (monthlyIncome × 12)
    DTI < 1.0 : +200   ← borrowing less than 1 year of income = very safe
    DTI < 2.0 : +130
    DTI < 3.0 : +60
    DTI < 5.0 : -20
    DTI ≥ 5.0 : -150   ← borrowing 5+ years of income = very risky

  Step 2: Tenure adjustment
    ≤ 12 months : +80  ← shorter loan = less time to default
    ≤ 36 months : +40
    ≤ 60 months : +0
    > 60 months : -50

  Step 3: Employment multiplier (applied to entire score)
    SALARIED      : × 1.00  ← most stable income
    BUSINESS      : × 0.90  ← somewhat stable
    SELF_EMPLOYED : × 0.85  ← least predictable

  Step 4: Existing loans penalty
    -30 per existing loan

  Step 5: Income band bonus
    ≥ ₹100,000/month : +60
    ≥ ₹50,000/month  : +30
    ≥ ₹25,000/month  : +10

  Step 6: Clamp to [300, 900]

  Example:
    income=50000, amount=100000, tenure=24, SALARIED, 0 loans
    DTI = 100000 / 600000 = 0.167 → +200 → score=700
    tenure=24 → +40 → score=740
    SALARIED × 1.0 → score=740
    income ≥ 50000 → +30 → score=770
    clamp: 770

Risk/Rate mapping:
  Score ≥ 750 : LOW    10.5%
  Score ≥ 650 : MEDIUM 13.5%
  Score ≥ 550 : MEDIUM 16.0%
  Score < 550 : HIGH   20.0%

Approval: score ≥ 580 → APPROVE, else REJECT
```

---

## Part 5 — System Design (High-Level)

### The Overall Architecture Decision Tree

**Q: Why microservices instead of a monolith?**

For a real fintech at scale, each service scales independently:
- During peak loan application hours, scale loan-service × 5
- AI scoring is CPU-heavy — run it on GPU/ML nodes
- Notification delivery is I/O-heavy — scale independently

For a portfolio, it demonstrates distributed systems understanding.

**Q: Why no API Gateway?**

The frontend calls user-service and loan-service directly. An API Gateway
(like Kong or Spring Cloud Gateway) would:
- Centralize auth (one JWT filter instead of two)
- Rate limit at the edge
- Route by path prefix

In production you'd add this. For a portfolio, it adds complexity without benefit.

### Communication Pattern Decision

```
When to use synchronous HTTP:
  ✓ You need the result to continue (loan apply needs credit score)
  ✓ Result must be consistent with the response (user profile lookup)

When to use asynchronous messaging:
  ✓ Notification delivery (user doesn't need to wait for email to send)
  ✓ Downstream system may be slow or temporarily down
  ✓ Multiple consumers need the same event (future: analytics, CRM)
  ✗ Do NOT use when you need consistency (e.g., payment processing)
```

### Database per Service

```
user-service  → Neon userdb    (users table)
loan-service  → Neon loandb    (loans, emi_payments tables)
```

**Why separate databases?**
1. Services can evolve schema independently
2. One DB failure doesn't cascade to other services
3. Each DB optimized for its access patterns

**The tradeoff:** No foreign key constraints across services. Referential integrity is
the application's responsibility. If you delete a user, orphaned loans remain in loandb.
For production, you'd add a saga or compensating transaction.

### Authentication Architecture

```
                                                     Why this design?
                                                     ─────────────────

Browser ─── HttpOnly cookie ──► user-service        Cookie never accessible
           (auth_token)                              to JavaScript → immune
                                                     to XSS attacks

Browser ─── Bearer token ──────► loan-service       loan-service is a
           (Authorization header)                    different domain than
                                                     user-service. Cookies
                                                     are domain-bound, so
                                                     the cookie can't reach
                                                     loan-service.

                                                     The token lives in a
                                                     module-level variable
                                                     in api.ts — NOT in
                                                     localStorage (vulnerable
                                                     to XSS) or sessionStorage.
```

**Session restore on page refresh:**
```
Page loads
    ↓
AuthContext mounts (useEffect)
    ↓
Read localStorage → profile exists? (no token — that was cleared)
    ↓ yes
Call GET /api/auth/me (sends HttpOnly cookie automatically)
    ↓ 200
Receive fresh token → store in _token (memory)
Display authenticated app
    ↓ 401 (cookie expired)
Clear localStorage → redirect to /login
```

### Redis — Session / Token Blacklist

Redis is wired into user-service but functions as a **distributed cache** for:
- Token blacklisting on logout (optional)
- Rate limiting per IP
- Session metadata

For this portfolio, Redis is configured but its blacklist feature is available
for extension. The key insight: stateless JWT + Redis blacklist = stateful logout
without database writes.

### RabbitMQ — Topic Exchange

```
Exchange: smartlend.exchange (TopicExchange)

Routing key        Queue                  Who binds it
────────────────── ─────────────────────  ───────────────────────
user.registered  → loan.events.queue   ← notification-service
loan.applied     → loan.events.queue   ← notification-service
loan.approved    → loan.events.queue   ← notification-service
loan.rejected    → loan.events.queue   ← notification-service
loan.emi.due     → loan.events.queue   ← notification-service
```

**Why TopicExchange instead of DirectExchange?**
TopicExchange supports wildcard routing: `loan.#` would match all loan events.
This lets you add future consumers (analytics service listening to `loan.*`)
without changing publishers.

**Why does notification-service declare bindings?**
The consumer owns its own bindings. If user-service declared "user.registered → loan.events.queue",
that binding only exists when user-service is running. If notification-service
starts first, messages are lost. Consumer ownership ensures bindings exist the
moment the consumer is ready.

### Scalability Analysis

| Component | Bottleneck | Solution |
|---|---|---|
| user-service | BCrypt is intentionally slow (12 rounds) | Horizontal scaling, async login |
| loan-service | AI scoring call latency | Cache scores, async scoring |
| ai-scoring | Python GIL limits concurrency | Uvicorn workers, containerize separately |
| notification-service | SendGrid rate limits (100/day free tier) | Batch, upgrade plan |
| RabbitMQ | Queue depth under load | Consumer concurrency, prefetch count |
| PostgreSQL | Connection pool exhaustion | PgBouncer, read replicas |

---

## Part 6 — Core Algorithm Walkthroughs

### JWT Flow (End to End)

```
REGISTRATION:
user-service generates:
  header  = base64({"alg":"HS256","typ":"JWT"})
  payload = base64({"sub":"user-uuid","email":"x@x.com","role":"APPLICANT","iat":...,"exp":...})
  signature = HMAC-SHA256(header + "." + payload, secret)
  token = header + "." + payload + "." + signature

Set in HttpOnly cookie: auth_token=<token>; HttpOnly; Secure; SameSite=Lax; Max-Age=86400

SUBSEQUENT REQUEST:
Browser sends cookie automatically (browser built-in behavior, no JS needed)
JwtAuthFilter receives request:
  1. Read cookie "auth_token"
  2. Split token into 3 parts
  3. Verify signature: HMAC-SHA256(header+"."+payload, secret) == provided signature?
     NO  → 401 Unauthorized
     YES → parse payload → extract sub (userId), role
  4. Set SecurityContext with userId as principal and role as authority
  5. Controller receives Principal = userId, role = APPLICANT/ADMIN

WHY STATELESS:
  Server never stores the token. It only verifies the signature.
  Any server instance can verify any token independently.
  No session storage, no database lookup per request.
```

### BCrypt Password Hashing

```
REGISTRATION:
  passwordEncoder.encode("mypassword123")
  → $2a$12$eImiTXuWVxfM37uY4JANjQ==.hashedvalue
         │  │  └─ random salt (22 chars)
         │  └─ cost factor (2^12 = 4096 rounds)
         └─ BCrypt version

LOGIN:
  passwordEncoder.matches("mypassword123", storedHash)
  → re-runs BCrypt with the SAME salt extracted from storedHash
  → compares result with stored hash
  
WHY NOT MD5/SHA:
  MD5/SHA hash in microseconds → rainbow tables work
  BCrypt takes ~100ms → brute force infeasible (10^9 attempts/sec = 31 years for 8-char password)
```

### EMI Amortization (Month-by-Month)

```
Loan: ₹1,00,000 at 12% annual for 3 months (simplified)

Monthly rate r = 12/12/100 = 0.01
EMI = 100000 × 0.01 × (1.01)^3 / ((1.01)^3 - 1)
    = 100000 × 0.01 × 1.030301 / 0.030301
    = 1030.301 / 0.030301
    = ₹34,002.22 / month

Month 1:
  Interest   = 100000 × 0.01 = ₹1,000
  Principal  = 34002.22 - 1000 = ₹33,002.22
  Balance    = 100000 - 33002.22 = ₹66,997.78

Month 2:
  Interest   = 66997.78 × 0.01 = ₹669.98
  Principal  = 34002.22 - 669.98 = ₹33,332.24
  Balance    = 66997.78 - 33332.24 = ₹33,665.54

Month 3:
  Interest   = 33665.54 × 0.01 = ₹336.66
  Principal  = 34002.22 - 336.66 = ₹33,665.56
  Balance    ≈ 0 (rounding)

Total paid = 34002.22 × 3 = ₹1,02,006.66
Total interest paid = ₹2,006.66

Observation: As balance decreases, interest decreases and principal component grows.
```

---

## Part 7 — Major Questions & Answers

### Q1: Why HttpOnly cookie for user-service but Bearer token for loan-service?

**A:** Cookies are scoped to a domain. The browser automatically sends `auth_token` to
`user-service-smartlend.up.railway.app` but NOT to `loan-service-smartlend.up.railway.app`
because they're different subdomains.

If we sent the JWT in a cookie to loan-service, we'd need `domain=.railway.app` which
would share it with ALL Railway deployments — a security catastrophe.

Instead: browser keeps JWT in a module-level variable (`_token` in api.ts). For loan-service,
it sends `Authorization: Bearer <_token>`. This variable is reset on page refresh, which is
why `AuthContext` calls `GET /me` on mount to restore it from the HttpOnly cookie.

**The chain:**
1. Login → user-service sets HttpOnly cookie + returns token in JSON body
2. Frontend stores token in `_token` (memory) — NOT localStorage
3. On page refresh, `_token` is lost → `AuthContext` calls `/me` → user-service validates the
   cookie and returns a fresh token → `_token` is restored

### Q2: Why is `AuthService.register()` marked `@Transactional`?

**A:** Consider what happens without `@Transactional`:
1. `userRepository.save(user)` → user saved to DB ✓
2. `rabbitTemplate.convertAndSend(...)` → RabbitMQ is down → throws exception
3. User is in DB but no welcome email event was ever published
4. The user exists but was never "welcomed" — inconsistent state

With `@Transactional`: if step 2 throws, Spring rolls back the DB save.
The user doesn't exist in the DB. They can try registering again later.

**Caveat:** This only works if RabbitMQ throws before the `@Transactional` method returns.
If the message is accepted by the broker but notification-service consumes it after
the DB rollback, you'd get an orphaned notification. This is the fundamental challenge
of distributed transactions — full ACID across services requires a saga pattern.

### Q3: Why does loan-service read user details via HTTP instead of from the JWT?

**A:** The JWT contains `userId`, `email`, and `role`. It does NOT contain `fullName`, `phone`,
`monthlyIncome`, `employmentType` — those are omitted to keep the token small.

For RabbitMQ events (loan applied/approved/rejected), notification-service needs
`email`, `fullName`, and `phone` to deliver notifications. loan-service fetches these
from user-service via `GET /api/auth/profile/{userId}`.

**Why not include them in the JWT?**
- JWT is sent with EVERY request — keeping it small is important
- User profile data changes (phone number update) — a JWT with old phone would be stale
- JWT payload isn't encrypted — don't put PII you don't need in it

### Q4: What happens if notification-service is down when a loan is approved?

**A:** Nothing is lost. RabbitMQ is a message broker, not a fire-and-forget UDP socket.

When loan-service publishes `loan.approved`, the message sits in the `loan.events.queue`
until a consumer reads it. The queue persists on CloudAMQP's disk (durable queue).

When notification-service comes back online, it reads all pending messages and
processes them. Users get their notifications — just delayed.

This is the fundamental advantage of async messaging over synchronous HTTP for notifications.

### Q5: Why does the frontend have TWO Axios instances instead of one?

**A:** Two different authentication strategies:
- `userHttp` → `withCredentials: true` → browser auto-sends the HttpOnly cookie
- `loanHttp` → manually sets `Authorization: Bearer <token>` header (different Railway domain, cookie never reaches it)
- `aiHttp` → no auth at all (public scoring endpoint)

If you used one Axios instance for all three, you'd need to conditionally add/remove
headers based on which service you're calling — messy and error-prone.

All three are created via the `createHttpClient()` factory so timeout, retry, and
request tracing are consistent without any per-instance boilerplate.

### Q6: Why Redis in user-service? What does it actually do here?

**A:** Redis is wired in but currently used as infrastructure for:
- **Token blacklisting:** On logout, you could add the token to a Redis set with TTL = token's
  remaining lifetime. Any subsequent request with that token is rejected even though the
  signature is valid.
- **Session caching:** Cache `/me` results for 5 minutes so every page refresh doesn't hit
  the database.
- **Rate limiting:** Block an IP after 10 failed login attempts in 60 seconds.

The `@EnableRedisHttpSession` is available to activate distributed sessions if needed.

### Q7: Why is the credit score in the range 300–900? Who decides this?

**A:** This mimics India's CIBIL score range (300–900), where 750+ is considered excellent.
This is a deliberate design choice for authenticity — interviewers recognize it.

The scoring model is rule-based (not ML) because:
1. No historical loan repayment data to train on (it's a portfolio project)
2. Rules are explainable — you can see exactly why a score is 720
3. The architecture supports swapping to an ML model: `app/scoring.py` is a pure function
   that could be replaced with `joblib.load("model.pkl").predict(features)`

### Q8: Why separate `app/scoring.py` from `app/main.py`?

**A:** Two reasons:

**Testability:** `main.py` imports FastAPI. Tests would need FastAPI installed, which
creates environment dependencies. `scoring.py` uses only Python stdlib dataclasses —
tests run anywhere with just `python3`.

**Single Responsibility:** `main.py` handles HTTP (middleware, routing, serialization).
`scoring.py` handles business logic (credit scoring algorithm). Changing the HTTP
framework (FastAPI → Flask) doesn't touch the scoring logic. Changing the scoring
algorithm doesn't touch the HTTP layer.

### Q9: What is the `X-Request-Id` header and why does every service generate one?

**A:** `X-Request-Id` is a distributed tracing header. When a request enters any service,
`RequestLoggingFilter` generates a UUID (first 8 chars), puts it in `MDC` (Mapped Diagnostic
Context), and adds it to every log line for that request's duration.

```
2026-05-20 10:30:15 INFO [user-service] [a3f9bc12] POST /api/auth/login → 200 (45ms)
2026-05-20 10:30:15 INFO [user-service] [a3f9bc12] User logged in: user@example.com
```

If something fails across services, you search logs by the same `X-Request-Id`
to trace the full path of that request across all services. Without this, debugging
distributed failures is nearly impossible.

### Q10: Why use `Map<String, Object>` for RabbitMQ events instead of strongly-typed classes?

**A:** The event payload crosses service boundaries. `LoanApprovedEvent.java` in loan-service
can't be imported into notification-service (different Maven projects, no shared library).

Options:
1. **Shared library (smartlend-events-api.jar):** Couples all services to the same release cycle
2. **Map<String, Object>:** Flexible, no coupling — each service extracts what it needs
3. **JSON Schema / Avro / Protobuf:** Strong typing with versioning — overkill for a portfolio

`Map<String, Object>` is chosen for simplicity. The `type` field acts as a discriminator.
The `default → log.warn("Unknown type")` ensures unknown types fail silently rather than
crashing the consumer.

**Rule:** Never crash the consumer on unknown message types — it would stop processing
all subsequent messages in the queue.

### Q11: Why `PUT` for the admin decision endpoint, not `POST`?

**A:** REST semantics:
- `POST /decisions` → creates a new decision resource
- `PUT /loans/{id}/decision` → updates an existing loan with a decision (idempotent)

`PUT` is idempotent: calling it twice with the same data has the same effect as calling
it once. An admin accidentally double-clicking "Approve" should not create two approvals.
The loan status is set (not appended), so the second call is a no-op.

### Q12: How does Spring Security know the user's role without a database lookup?

**A:** The role is embedded in the JWT payload:
```json
{"sub": "user-uuid-123", "email": "user@x.com", "role": "ADMIN", "iat": 1716..., "exp": 1716...}
```

When `JwtAuthFilter` validates the token, it reads `claims.get("role")` and creates
a `SimpleGrantedAuthority("ROLE_ADMIN")`. Spring Security's `hasRole("ADMIN")` check
matches this authority.

**No database lookup per request.** This is stateless JWT authentication — the role is
cryptographically verified (the JWT signature ensures it wasn't tampered with) without
touching the database.

### Q13: Why use Resilience4j circuit breaker instead of a simple try/catch with fallback?

**A:** Both provide graceful degradation — the difference is what happens under sustained failure.

**Plain try/catch:**
```
Request 1 → ai-scoring DOWN → wait 5s timeout → fallback ✓
Request 2 → ai-scoring DOWN → wait 5s timeout → fallback ✓
Request 3 → ai-scoring DOWN → wait 5s timeout → fallback ✓
... (every request burns 5s, threads pile up)
```

**Circuit breaker (OPEN state):**
```
Request 1  → ai-scoring DOWN → wait 5s → fallback ✓  (counts as failure)
Request 2  → ai-scoring DOWN → wait 5s → fallback ✓  (5/10 failures = 50%)
Request 3  → CIRCUIT OPENS → fallback instantly ✓  (no network call)
Request 4  → CIRCUIT OPEN  → fallback instantly ✓
...
After 30s → HALF-OPEN → 3 test calls → service back → CLOSED
```

The circuit breaker pattern solves three problems that try/catch does not:
1. **Thread exhaustion:** Blocked threads waiting for timeouts can exhaust the thread pool. An open circuit returns immediately — zero threads blocked.
2. **Thundering herd on recovery:** When a service comes back, 100 queued requests all hit it simultaneously. The HALF-OPEN state gates them to 3 probe calls first.
3. **Observability:** Circuit state (CLOSED/OPEN/HALF-OPEN) is exposed at `/actuator/health`. A try/catch is invisible.

**Interview angle:** The circuit breaker is a *stability pattern*, not a *reliability pattern*. It doesn't make the AI service more reliable — it prevents a single slow/failing dependency from taking down your whole service.

### Q14: Why retry only on 5xx and network errors — why not retry on 4xx too?

**A:** Because 4xx means the client sent a bad request. Retrying it will produce the exact same 4xx every time — it just adds latency and hides the real bug.

```
400 BAD_REQUEST        → client sent malformed data → fix the payload, don't retry
401 UNAUTHORIZED       → sign out immediately, never retry (retrying makes no sense without re-auth)
403 FORBIDDEN          → wrong role → retrying won't change your role mid-request
404 NOT_FOUND          → resource doesn't exist → retrying won't create it
422 VALIDATION_ERROR   → field failed @NotBlank → retrying identical data fails identically
```

5xx is different — it means the server failed, not the client:
```
500 INTERNAL_SERVER_ERROR → unhandled exception → might succeed on retry
502 BAD_GATEWAY           → upstream timeout → transient, worth retrying
503 SERVICE_UNAVAILABLE   → temporary overload → backoff + retry is correct response
```

Network errors (no `response` at all) are also retryable: connection reset, DNS hiccup,
Railway cold-start. The exponential backoff (300ms → 600ms) gives the server time to recover
before the second attempt.

**Rule:** `status >= 400 && status < 500` → reject immediately. Anything else → retry up to N times with backoff.

### Q15: What is `X-Request-Id` and why does the frontend generate it?

**A:** Every `createHttpClient` request interceptor does:
```typescript
config.headers['X-Request-Id'] = crypto.randomUUID();
```

The backend `RequestLoggingFilter` reads this header and puts it in the MDC:
```
2026-05-21 10:30:15 INFO [loan-service] [a3f9bc12] POST /api/loans/apply → 201 (312ms)
2026-05-21 10:30:15 INFO [loan-service] [a3f9bc12] AI score result — user=abc score=720
```

Every log line for that request carries the same UUID. When a user reports a bug,
you ask them to open browser devtools → Network tab → copy the `X-Request-Id` from
any request header. Paste it into your log aggregator — all log lines for that exact
user action appear instantly, across services.

Without this, debugging a failed loan application means grepping by timestamp and
hoping no other requests interleaved.

---

## Quick Reference: File to Read First by Topic

| What you want to understand | Start here |
|---|---|
| How JWT auth works end-to-end | `SecurityConfig.java` (jwtAuthFilter bean) |
| How cookies are set/cleared | `AuthController.java` (setCookie/clearCookie methods) |
| How EMI is calculated | `LoanService.java` (calculateEmi + generateEmiSchedule) |
| How credit scoring works | `ai-scoring/app/scoring.py` |
| How notifications are extensible | `NotificationChannel.java` → `NotificationDispatcher.java` |
| How RabbitMQ events are published | `AuthService.java` (register) + `LoanService.java` (processAdminDecision) |
| How events are consumed | `LoanEventConsumer.java` |
| How the frontend manages auth | `AuthContext.tsx` + `services/api.ts` |
| How HTTP clients are built (retry, timeout, tracing) | `services/api.ts` — `createHttpClient()` factory |
| How data fetching is standardised across pages | `hooks/useQuery.ts` — `useQuery()` generic hook |
| How errors are standardized | `GlobalExceptionHandler.java` + `SecurityConfig.java` (writeError) |
| How distributed tracing works | `RequestLoggingFilter.java` |
| How circuit breaker protects loan-service | `AiScoringClient.java` + `UserServiceClient.java` + `application.yml` (resilience4j section) |

---

*This document was written to help you understand not just WHAT the code does, but WHY every decision was made. That "why" is what you'll be asked in interviews.*