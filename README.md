# Soulous

> AI-driven full-stack gamified learning closed-loop: AI planning chat → task execution → study room focus → proof submission → multi-model AI audit → RAG long-term memory → experience rewards → virtual pet growth.

Turn fragmented learning into a quantifiable, traceable, and positive feedback closed-loop system.

---

## Tech Stack

**Backend** Spring Boot 3.4 · Java 21 · Maven · Spring Data JPA · Spring Security · JWT (jjwt 0.12) · Flyway · Bucket4j · Micrometer / Prometheus · H2 (Default) / MySQL · Thumbnailator

**Frontend** React 19 · Vite 6 · TypeScript 5 · Vitest · Recharts (Lazy-loaded) · lucide-react

**AI** Pluggable `LlmService` strategy: mock (default) / DeepSeek / OpenAI-compatible / Anthropic, integrated with unified LRU+TTL cache and failure telemetry.

---

## Core Capabilities

- **Account & Security**: Registration via **email verification code** (required email; dev fallback logs the code when SMTP unset), login with SVG graphical CAPTCHA, a marketing **landing page** for logged-out visitors, password strength policies, JWT double tokens (1h access + 30d refresh, HttpOnly cookie, SHA-256 database storage, automatic rotation, multi-device logout on replay detection), and `audit_log` full-audit tracking. The **Settings** page bundles self-service panels: device/security activity (own `audit_log` events), a lightweight storage-asset view (aggregated avatars + task proofs with usage estimate, deletable avatars), AI privacy/memory management, and an about/logout section.
- **AI Decompose Chat**: Gemini-style interface (category → conversation → message with collapsible sidebar, conversation move/categorization), stream replies, file uploads (md/pdf/txt text extraction), and one-click task conversion from AI-generated draft plans.
- **Study Room / Verification**: **solo** stopwatch focus timer (theme backgrounds + ambient sound/music, full-screen immersive mode, custom scene/music uploads) and **shared** study rooms (lightweight presence via heartbeat polling — see who's online and their focus time, no WebSocket); credential submission (text / code snippet / image proofs, auto-compressed to 1920px JPEG 85% with 5MB limit), authorized downloads under `/uploads/**`.
- **Timetable**: Sync course timetables directly from the university academic system (Playwright crawler with student credentials), single/double week displays, manual entries, and semester start week alignment. The same one-click sync also pulls **exam schedules** and **course grades** (with per-semester GPA/credit summaries), all viewable in the timetable page and switchable by semester.
- **AI Hub**:
  - AI Decompose Chat (continuous messaging + `PLAN_JSON` to task conversion).
  - Credential Verification Engine (multi-dimensional assessment: relevance, completeness, and quality score, with automated experience points reward). Reviews run on the local LLM (`AiService.review`) with a deterministic rule-based scorer as fallback — see `docs/ai-review-rules.md`.
  - Daily Review (dynamic summaries based on user activity logs).
  - RAG Long-term Memory + Time-decay Retrieval:
    $$\text{Score} = \text{CosineSimilarity} \times 0.5^{\frac{\text{AgeDays}}{\text{HalfLife}}}$$
    (Default half-life of 90 days). Indexes cover `GOAL_MEMORY`, `SESSION_SUMMARY`, `COMPLETED_TASK`, and `DAILY_REVIEW`. The memory is retrieved and injected into system prompts during AI Reviews (`AiService.review`), task follow-ups (`generateQuestion`), goal decomposition (`decompose`), and daily reviews (`DailyReviewService`), giving the AI a personalized "memory" of the user. Users can **opt out per-account** (`user_account.ai_memory_enabled`, gated centrally in `RetrievalService`) and view/delete/clear their stored memories from the Settings page.
  - Context-aware Content Moderation (bidirectional input/output wind control: PASS/FLAG/BLOCK verdicts, violating records saved to `moderation_log`).
- **Pet, Economy & Analytics**: **Pet market** (species catalog; new users adopt a free starter, then buy more with coins and switch the active pet — each pet levels independently). A **companion nickname** (`user_account.companion_nickname`, set via `PATCH /api/pet`) is global and follows you across pets, displayed alongside each pet's fixed species name as "nickname · species". Experience curve to max Lv30 with level-gated actions, mood + check-in-streak multipliers, gentle inactivity decay. **Coins** earned from tasks/focus/check-ins (`coin_ledger`). **Daily check-in** with streak rewards, **daily email reminders** for missed check-ins. Spritesheet animations ([`frontend/public/pets/`](frontend/public/pets/)). Interactive dashboard for daily metrics, 7-day trends, category distributions; "My" page shows coins / streak / pet collection / coin ledger.
- **Admin Dashboard**: Site-wide submissions queue, manual override of AI audit results (approve / reject / request additions), appeal handling, admin user provisioning, and role assignment. All operations logged in `admin_audit_log`.
- **Notification Center**: AI review events, appeal statuses, and other alerts logged to `notification`, exposed via SSE (`GET /api/notifications/stream`) with 60s polling fallback. Optional email notification sink (`soulous.notification.email.enabled`). The top-nav bell (`NavBell`) is a separate **lightweight client-side aggregation** computed from already-loaded pet/task state (hungry/low-mood pet, tasks under review or rejected) — no persistence, no push.

### Production Reliability (Phase 1 Hardening)

- **Flyway Migrations**: Baseline schemas for both H2 and MySQL; `baseline-on-migrate` for zero-downtime deployment; prod profiles enforce `ddl-auto=validate`.
- **Application Rate Limiting**: `@RateLimit` annotations backed by Bucket4j. Limits: Login/Registration 5/min (by IP), AI API calls 60/h ∧ 200/day (by user), returning `429 + Retry-After`.
- **Storage GC**: Scheduled job runs at 03:00 daily to garbage-collect unreferenced uploads older than 24 hours (default dry-run).
- **Observability**: Spring Actuator + Prometheus metrics exporter.

---

## Quick Start

> Prerequisites: JDK 21+, Maven 3.9+, Node 18+

```bash
# Run Backend
cd backend && mvn spring-boot:run

# Run Frontend
cd frontend && npm install && npm run dev
```

Open http://localhost:5173.

**First Administrator**: The repository does not seed default credentials. Bootstrap the initial admin user using environment variables:

```bash
SOULOUS_BOOTSTRAP_ADMIN_USERNAME=admin
SOULOUS_BOOTSTRAP_ADMIN_PASSWORD=<strong-password>
SOULOUS_BOOTSTRAP_ADMIN_NICKNAME=Admin
```

Subsequent accounts can be registered through the frontend or provisioned by existing administrators.

---

## Verification & Testing

```bash
cd backend && mvn test         # Backend Unit / Integration Tests (243 test cases)
cd frontend && npm test        # Frontend Vitest (41 test cases)
cd frontend && npm run build   # Frontend Production Build
cd frontend && npm run test:e2e  # Playwright End-to-End Tests (requires backend running)
```

### E2E Tests

Playwright scripts are located in `frontend/e2e/`. Start the backend with testing profiles enabled (disabling captchas and rate-limits):

```bash
cd backend
SOULOUS_CAPTCHA_ENABLED=false \
SOULOUS_LLM_PROVIDER=mock \
SOULOUS_RATE_LIMIT_ENABLED=false \
mvn spring-boot:run
```

Then run `cd frontend && npm run test:e2e` (or `npm run test:e2e:ui` for Playwright's graphical test environment).

---

## Switching to MySQL

```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

Default connection: `jdbc:mysql://localhost:3306/soulous` (`root` with blank password), configured in [`backend/src/main/resources/application.yml`](backend/src/main/resources/application.yml).

---

## Database Migrations (Flyway)

Scripts located at: `backend/src/main/resources/db/migration/{h2,mysql}/V<n>__<desc>.sql`

- **Existing Database**: Auto baselines to V1, running incremental V2+ migrations.
- **New Database**: Runs migrations sequentially from V1.
- **Production**: Enforces `spring.jpa.hibernate.ddl-auto=validate`; schemas are strictly managed via Flyway.
- **Development**: Retains `update` for fast prototyping.

---

## Audit Logs

Security actions are audited in `audit_log`:
- `LOGIN_SUCCESS` / `LOGIN_FAILED` (captures username; API response keeps generic message to prevent username enumeration).
- `LOGOUT` / `LOGOUT_ALL` (tracks invalidation of refresh tokens).
- `PASSWORD_CHANGED`.
- `REFRESH_TOKEN_REPLAYED` (triggers cascading invalidation of all sessions for the compromised user; audit records are written in a `REQUIRES_NEW` transaction to preserve logs even during authentication rollbacks).

Query: `GET /api/admin/audit-log` (ADMIN only, standard pagination).

`audit_log`, `admin_audit_log` (review operations), and `moderation_log` (content moderation) are distinct tables and are not automatically cleared.

---

## Observability Endpoints

| Endpoint | Access | Purpose |
| --- | --- | --- |
| `GET /actuator/health` | Public | Liveness probe; details hidden by default |
| `GET /actuator/info` | Public | Application metadata |
| `GET /actuator/prometheus` | ADMIN | Prometheus metrics scrape target |
| `GET /actuator/metrics/**` | ADMIN | Ad-hoc metric details query |

### Custom Business Metrics

| Metric | Type | Tags | Description |
| --- | --- | --- | --- |
| `soulous_llm_calls_total` | counter | provider, model, outcome | Total LLM calls |
| `soulous_llm_latency` | timer | provider, model | Latency distribution of LLM calls |
| `soulous_rate_limit_blocked_total` | counter | rule | Total rate limit block counts |
| `soulous_moderation_verdict_total` | counter | verdict, target | Moderation decisions (PASS/FLAG/BLOCK) |
| `soulous_storage_gc_deleted_total` | counter | — | Garbage-collected unreferenced file count |
| `soulous_refresh_token_replayed_total`| counter | — | Security alerts for refresh token replay |
| `soulous_notification_pushed_total` | counter | type | Notifications pushed |

---

## Configuration Reference

| Environment Variable | Default | Purpose |
| --- | --- | --- |
| `SOULOUS_FLYWAY_ENABLED` | `true` | Enable/Disable Flyway migrations |
| `SOULOUS_JPA_DDL_AUTO` | `update`(dev) / `validate`(prod) | Hibernate schema behavior |
| `SOULOUS_RATE_LIMIT_ENABLED` | `true` | Rate-limiting master switch |
| `SOULOUS_STORAGE_GC_ENABLED` | `true` | Unreferenced uploads garbage collection |
| `SOULOUS_STORAGE_GC_DRY_RUN` | `true` | GC logs files without deleting them |
| `SOULOUS_RAG_ENABLED` | `false` | RAG memory master switch |
| `SOULOUS_EMBEDDING_PROVIDER` | `mock` | `mock` / `ollama` / `openai` / `google` |
| `SOULOUS_EMBEDDING_MODEL` | — | Target model (e.g., `nomic-embed-text`, `text-embedding-3-small`) |
| `SOULOUS_LLM_PROVIDER` | `mock` | Pluggable LLM client: `mock` / `openai` / `anthropic` |

---

## Nginx SSE Configuration

For production reverse proxy environments, disable response buffering to prevent Nginx from caching SSE stream tokens:

```nginx
location ~ ^/api/(notifications/stream|chat/conversations/[0-9]+/messages/stream)$ {
    proxy_pass http://localhost:8080;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 3600s;
    chunked_transfer_encoding on;
}
```

---

## 🗺️ Roadmap

### Shipped — AI Cognitive Sidecar
A standalone Python/FastAPI + LangGraph service (`agent-service/`) now backs the chat decompose, credential review, and daily-review chains, with entity-grounded sqlite-vec RAG. Spring calls it via `com.soulous.agent.AgentClient` and auto-falls back to the local `LlmService` / rule path when it is disabled or unavailable. See [agent-service/README.md](agent-service/README.md).

### Next
- **Multi-Agent Collaboration**: specialized agents (e.g. a Planning Coach and a Reviewer) collaborating on task management and credential validation.
- **Evaluation & Prompt-Regression Harness**: a programmatic benchmark suite scoring LLM output accuracy, consistency, and latency across providers, wired into CI to guard prompt changes.

---

## Documents

- [Architecture Design](docs/architecture.md)
- [API Spec Sheet](docs/api.md)
- [Database Schema](docs/database.md)
- [AI Review Guidelines](docs/ai-review-rules.md)
- [User Manual](docs/user-guide.md)
- [Deployment Manual](DEPLOY.md)
- [Agent Service (AI cognitive sidecar)](agent-service/README.md)
