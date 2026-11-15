# DRAS 2.0 Implementation Progress & Architecture Tracking

> **Disaster Resource Allocation System (DRAS 2.0)**  
> Complete Frontend Redesign, Authentication, Role-Based Access Control, and Advanced EOC Dashboard Implementation.

---

## 1. Baseline System Audit (Phase 0)

### 1.1 Architecture Summary
* **Application Type**: Academic Emergency Operations Center (EOC) Simulator uniting Operating Systems (OS) scheduling and deadlock avoidance with Database Management Systems (DBMS) concurrency control, row-level locking, and ACID transactional integrity.
* **Technology Stack**:
  * **Backend**: Java 21 LTS, Spring Boot 3.3.5, Spring JDBC (`JdbcTemplate`), HikariCP connection pool.
  * **Database**: PostgreSQL 18.x with 9 core relational tables, relational foreign keys, CHECK constraints, and 8 performance-oriented partial/composite indexes.
  * **Frontend**: Vanilla JavaScript SPA with hash-based routing (`index.html`, `css/app.css`, `js/app.js`).
* **Design Tenet**: Separation of concerns:
  1. *Schedulers* determine order only.
  2. *Allocation Engine* manages transactional state and locks.
  3. *Safety Module* evaluates Banker's deadlock safety.
  4. *Database Engine* enforces absolute invariant consistency: `CHECK (total_quantity = available_quantity + allocated_quantity)`.

### 1.2 Baseline Test Results
* **Test Suite Execution**: `mvn clean test` with live PostgreSQL database configured.
* **Total Tests**: **55**
* **Failures**: **0**
* **Errors**: **0**
* **Skipped**: **0**
* **Result**: **BUILD SUCCESS**
* **Coverage Breakdown**:
  * `BankersAlgorithmTest`: 11/11 pass (pure unit tests).
  * `SchedulingTest`: 13/13 pass (FCFS, Priority, Round Robin unit tests).
  * `BankersSnapshotTest`: 5/5 pass (matrix compilation from DB models).
  * `AllocationEngineIT`: 7/7 pass (live PostgreSQL row-locking and allocation tests).
  * `ApiAndConstraintsIT`: 6/6 pass (REST endpoints, CHECK constraint enforcement).
  * `ConcurrencyIT`: 11/11 pass (multi-threaded racing requests, strict inventory caps).
  * `RollbackIT`: 2/2 pass (failure injection, autonomous recovery logging).

---

## 2. Image Asset Audit

Source Directory: `design-assets/selected/`  
Destination Directory: `src/main/resources/static/images/sections/`

All 9 assigned section images were located and inspected:

| # | Target Section | Source Filename | Dimensions | Aspect Ratio | Format & Visual Notes | Processing Plan |
|---|---|---|---|---|---|---|
| 1 | **Command Center** | `command-center.jpg.jpg` | 736 × 919 | ~4:5 (Portrait) | Electrical cables & pole; monochrome composition. | Luminance-balanced dark graphite background blend; right-anchored. |
| 2 | **Resource Inventory** | `resource-inventory.jpg.jpg` | 1199 × 801 | ~3:2 (Landscape) | Abandoned industrial warehouse; strong architectural depth. | Low-opacity industrial grain, dark vignette gradient. |
| 3 | **Organization Directory** | `organization-directory.jpg.jpg` | 1200 × 675 | 16:9 (Landscape) | Modern hospital exterior; strong geometric lines. | Contrast-calibrated dark overlay, left text gradient. |
| 4 | **Requests Queue** | `requests-queue.jpg.jpg` | 735 × 490 | ~3:2 (Landscape) | Dark control room with dispatch monitoring screens. | Screen luminosity preserved; dark cinematic blending. |
| 5 | **Allocation Center** | `allocation-center.jpg.jpg` | 1200 × 861 | ~7:5 (Landscape) | Ambulance deployment & emergency medical responders. | Outer white border removal; ambulance focus; dark overlay. |
| 6 | **Scheduling Simulator** | `scheduling-simulator.jpg.jpg` | 601 × 800 | ~3:4 (Portrait) | Vintage mechanical clock face and gears. | Mechanical gear details preserved; right-aligned split layout. |
| 7 | **Banker's Simulator** | `bankers-simulator.jpg.jpg` | 609 × 1000 | ~3:5 (Portrait) | Human silhouette network. **Embedded grey/white checkerboard pattern detected.** | Checkerboard background removal / luminance masking into `#191A1D`. |
| 8 | **Performance Metrics** | `performance-metrics.jpg.jpg` | 736 × 554 | ~4:3 (Landscape) | Abstract waveform with analytical peaks. **Solid white background.** | White background converted to graphite `#191A1D`; luminous wave preserved. |
| 9 | **System Status** | `system-status.jpg.jpg` | 768 × 1408 | ~1:2 (Tall Portrait) | Vintage mainframe computing hardware and server cables. | Right-aligned tall composition, graphite gradient, no stretching. |

*Asset Availability*: **9/9 available (100%)**. Source files have double `.jpg.jpg` extension and will be normalized during generation of web derivatives.

---

## 3. Proposed Frontend Architecture

### 3.1 Modular File Structure
The single monolithic `app.css` (49 lines) and `app.js` (408 lines) will be decomposed into clean, maintainable modules without disrupting static hosting:

```
src/main/resources/static/
├── index.html
├── css/
│   ├── tokens.css          (Locked color palette, typography scale, spacing)
│   ├── base.css            (Reset, typography, dark background defaults)
│   ├── layout.css          (Sidebar, header, main viewport, responsive grid)
│   ├── components.css      (Physical push buttons, cards, tables, forms, modals)
│   ├── dashboard.css       (Double-exposure headers, layout families A/B/C)
│   ├── alerts.css           (Pulsing critical red / warning amber animations)
│   ├── animations.css      (Transitions, button push translateY(2px) scale(0.98))
│   └── responsive.css      (Breakpoints for tablet, laptop, and mobile)
├── js/
│   ├── api.js              (Centralized fetch wrapper, token injector, error handling)
│   ├── auth.js             (JWT storage, login/logout state, session management)
│   ├── router.js           (Hash-based page router with permission guards)
│   ├── permissions.js      (Role matrix checker: Admin, Dispatcher, OrgMgr, OrgMember)
│   ├── components/
│   │   ├── sidebar.js      (Branded navigation, user profile pill, role badge)
│   │   ├── header.js       (Page title, live DB badge, alerts bell, command mode)
│   │   ├── cards.js        (Interactive stat cards with utilization bars)
│   │   ├── tables.js       (Standardized sortable/filterable table builders)
│   │   ├── modals.js       (Accessible modal dialog controller)
│   │   ├── notifications.js (Toast notification dispatcher)
│   │   └── alerts.js       (Critical/Warning alert banner & acknowledgement system)
│   ├── pages/
│   │   ├── dashboard.js    (Command Center overview, recent audit activity)
│   │   ├── inventory.js    (Resource management, history modal, low-stock alerts)
│   │   ├── organizations.js (Directory, max claims editor, approval controls)
│   │   ├── requests.js     (Priority-treated request queue & submission)
│   │   ├── allocations.js  (Algorithm execution, quantum control, rollback demo)
│   │   ├── scheduling.js   (Interactive Gantt chart, metrics comparison)
│   │   ├── bankers.js      (Live DB state + Custom matrix solver with SAFE sequence)
│   │   ├── metrics.js      (Historical simulation charts, failure logs)
│   │   ├── system.js       (PostgreSQL latency, invariant checks, index stats)
│   │   ├── login.js        (Authentication screen with cinematic double-exposure)
│   │   └── register.js     (Public registration flow with organization selection)
│   └── app.js              (Bootstrap orchestrator loading modules)
└── images/
    └── sections/           (Processed WebP / optimized JPG section double-exposure assets)
```

### 3.2 Visual Design System (Tokens)
* **Main Surfaces**:
  * Background: `#191A1D` (Graphite)
  * Sidebar: `#101114` (Deep Black)
  * Cards: `#23252A` (Dark Charcoal)
  * Elevated Panels: `#2B2D32` (Soft Charcoal)
  * Borders: `#383B42` (Graphite Grey)
* **Typography**:
  * Headings: `Space Grotesk`, sans-serif
  * Body: `Inter`, system-ui, sans-serif
  * Text Colors: Primary `#F4F4F2`, Secondary `#A5A7AD`, Muted `#777B83`
* **Semantic Operational Colors**:
  * Critical: `#EF5350` (Red)
  * Warning: `#F0A443` (Amber)
  * Success: `#60B98B` (Muted Green)
  * Info: `#8DAFC4` (Silver Blue)

---

## 4. Proposed Authentication & Authorization Architecture

### 4.1 Four-Tier Role Matrix
1. **`SYSTEM_ADMIN`**: Global administrative control over all organizations, resources, users, security approvals, claims, and system diagnostics.
2. **`DISPATCHER`**: Operational EOC controller with global view to monitor inventory, prioritize queues, execute allocations, release units, and acknowledge emergency alerts.
3. **`ORGANIZATION_MANAGER`**: Scoped strictly to their own organization (e.g. City General Hospital). Can submit requests, view organization allocations and history, manage member invites, and configure organization profile.
4. **`ORGANIZATION_MEMBER`**: Restricted member scoped to their organization. Can submit requests, track personal requests, and monitor resource availability.

### 4.2 Database Schema Additions (`V3__auth_and_rbac.sql`)
* **`app_users`**:
  * `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`
  * `email VARCHAR(160) NOT NULL UNIQUE`
  * `password_hash VARCHAR(100) NOT NULL` (BCrypt)
  * `full_name VARCHAR(120) NOT NULL`
  * `contact_info VARCHAR(200)`
  * `role VARCHAR(30) NOT NULL CHECK (role IN ('SYSTEM_ADMIN', 'DISPATCHER', 'ORGANIZATION_MANAGER', 'ORGANIZATION_MEMBER'))`
  * `organization_id BIGINT REFERENCES organizations(id)` (Nullable for Admin/Dispatcher)
  * `status VARCHAR(20) NOT NULL DEFAULT 'PENDING_APPROVAL' CHECK (status IN ('PENDING_APPROVAL', 'ACTIVE', 'DISABLED'))`
  * `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
  * `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`
* **`refresh_tokens`**:
  * `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`
  * `user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE`
  * `token_hash VARCHAR(128) NOT NULL UNIQUE`
  * `expires_at TIMESTAMPTZ NOT NULL`
  * `revoked_at TIMESTAMPTZ`
  * `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
* **`emergency_alerts`**:
  * `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`
  * `level VARCHAR(15) NOT NULL CHECK (level IN ('CRITICAL', 'WARNING', 'INFO'))`
  * `title VARCHAR(150) NOT NULL`
  * `message VARCHAR(400) NOT NULL`
  * `source VARCHAR(60) NOT NULL`
  * `acknowledged BOOLEAN NOT NULL DEFAULT FALSE`
  * `acknowledged_by BIGINT REFERENCES app_users(id)`
  * `acknowledged_at TIMESTAMPTZ`
  * `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`

### 4.3 Data Isolation & IDOR Protection
* Requests and organization endpoints verify the authenticated principal.
* If user role is `ORGANIZATION_MANAGER` or `ORGANIZATION_MEMBER`, any attempt to query `organization_id != principal.organizationId` returns `403 Forbidden`.
* All request submissions automatically bind `organizationId = principal.organizationId` for tenant users.

---

## Phase 0 Audit Results (verified 2026-11-15)

### 5.1 Repository state
* Branch: `feature/dras-2-redesign`.
* The worktree already contained staged image assets and the image manifest, plus uncommitted changes in `app.js` and `components.css` for the Max Claims bulk-save control. These were preserved.
* The application remains Spring Boot 3.3.5 / Java 21 / Spring JDBC with a single Vanilla JavaScript hash-routed UI.

### 5.2 Existing API inventory
* Health/system: `GET /api/health`, `GET /api/system/status`.
* Dashboard/metrics: `GET /api/dashboard/stats`, `GET /api/metrics`.
* Resources: `GET/POST /api/resources`, `GET/PUT/DELETE /api/resources/{id}`, `GET /api/resources/history`.
* Organizations: `GET/POST /api/organizations`, `GET/PUT/DELETE /api/organizations/{id}`, `GET /api/organizations/{id}/claims`, `PUT /api/organizations/{id}/claims`.
* Requests: `GET/POST /api/requests`, `GET /api/requests/{id}`, `POST /api/requests/{id}/allocate`, `PUT /api/requests/{id}/status`.
* Allocations: `GET /api/allocations`, `GET /api/allocations/{id}`, `POST /api/allocations/{id}/release`, `POST /api/allocations/process-queue`.
* Scheduling: `GET /api/scheduling/queue-preview`, `POST /api/scheduling/simulate`.
* Banker's simulator: `GET /api/bankers/state`, `POST /api/bankers/evaluate`.

### 5.3 Persistence and tests
* Existing migrations are `V1__schema.sql` and `V2__seed.sql`; both are retained unchanged. Existing tables hold organization, resource, claim, request, allocation, history, simulation, metric, and recovery-log data.
* The verified baseline command was `set -a && source .env && set +a && mvn clean test` from WSL. Result: **55 tests, 0 failures, 0 errors, 0 skipped**. Running Maven without `.env` skips PostgreSQL integration tests, so future full-suite runs must source `.env` without printing its values.
* Integration tests use uniquely named test records and cleanup their own rows. No production-like records were removed by the baseline run.

### 5.4 Existing feature coverage and gaps
* The nine hash-routed sections and modular CSS files already exist. The Phase 1/2 image work and manifest were already staged before this audit and were preserved.
* The UI has real dashboard, inventory, organization, request, allocation, scheduling, Banker's, metrics, and system APIs. Claims use the existing per-claim `PUT /api/organizations/{id}/claims` API.
* No Spring Security/JWT dependency, authentication controllers, user/account tables, authorization policy, tenant isolation, alert acknowledgement API, or map/location coordinates currently exist.
* Request-state timestamps and resource history are available, but a complete append-only request event timeline and live activity stream are not yet exposed.
* The specification continuation through section 16 is available; its implementation phases are tracked below as existing work is validated and remaining work is completed.

---

## 7. Phase Tracker

| Phase | State | Notes |
|---|---|---|
| 0 — Audit | Complete | Branch, worktree, source layout, API routes, migrations, assets, and 55-test DB-backed baseline recorded above. |
| 1 — Design system | Existing implementation present | Modular dark graphite tokens/layout/components; retain and verify accessibility/responsive details. |
| 2 — Image processing | Existing assets committed | Nine supplied source files; processed section assets and manifest committed as `743a1e2`. |
| 3 — Interactions/alerts | Implemented | Claim bulk-save states and alert generation/acknowledgement have backend and UI support. Claim fix committed as `5722e88`. |
| 4 — Nine-section redesign | Implemented | Existing nine sections continue to use live API-backed data. |
| 5 — Authentication | Implemented | Registration, approval, BCrypt login, JWT access, HttpOnly rotating refresh tokens, logout, and one-time admin bootstrap. |
| 6 — RBAC/isolation | Implemented | Backend tenant and role boundaries have regression coverage for all four roles. |
| 7 — Analytics/monitoring | Partial | Real request event timeline, scoped metrics, and alerts exist; polling activity feed and additional charts remain. |
| 8 — Command mode/map | Partial | No verified coordinates are stored; no map points are fabricated. Dedicated Command Mode remains outstanding. |
| 9 — Final verification | Partial | 65 tests pass; provisioned roles and first-login gate verified through live APIs. Browser form screenshots and console review were interrupted by Escape. |

### 7.1 Recent Max Claims verification
* Browser opened the Max Claims editor for the existing demo organization (ID 298).
* Set its demo WATER resource claim (resource ID 174) from 7 to 8 and used **Save Claims**; the UI confirmed `Saved 1 claim`.
* Reloaded the organization page and reopened the editor; the stored value remained 8. The live health badge remained `DB Active · 11 Orgs · 8 Resources`.
* No browser-console errors were recorded during the save/reload check.

---

## 5. Potential Risks & Mitigation Strategy

1. **Risk: Breaking Existing Integration Tests during Spring Security Integration**
   * *Mitigation*: Ensure `WebSecurityConfig` permits internal integration test requests (or configures a mock security context / dedicated test security profile), so existing 55 integration tests pass without regression.
2. **Risk: IDOR / Leakage in `/api/requests` and `/api/organizations`**
   * *Mitigation*: Implement repository-level query filtering based on principal scope, not frontend filtering.
3. **Risk: Checkerboard Pattern & White Background in Selected Images**
   * *Mitigation*: Process `bankers-simulator.jpg.jpg` and `performance-metrics.jpg.jpg` with tailored alpha-channel and luminance filters to blend seamlessly into `#191A1D`.
4. **Risk: High-Contention Locking during Queue Processing**
   * *Mitigation*: Preserve the strict ascending resource ID ordering (`ORDER BY id FOR UPDATE`) in `AllocationEngine`.

---

## 6. Execution Roadmap & Phase Breakdown

* **Phase 0**: Codebase Audit, Asset Discovery, Progress Documentation (Current)
* **Phase 1**: Design System, Monochromatic Tokens, Physical Buttons, Base Layout
* **Phase 2**: Image Processing & Double-Exposure Section Integration (All 9 Images)
* **Phase 3**: Interaction Engine, Alert Animations (Pulsing Red / Amber), Reduced Motion
* **Phase 4**: Full Redesign of All 9 Core Dashboard Sections
* **Phase 5**: Authentication (Spring Security, JWT, Refresh Tokens, BCrypt, Login/Register)
* **Phase 6**: Authorization & Role-Based Access (Admin, Dispatcher, OrgMgr, OrgMember, IDOR Defense)
* **Phase 7**: Analytics & Advanced Visualizations (Real data Gantt, Latency, Outcome distributions)
* **Phase 8**: Live Operational Monitoring, Activity Feed, Emergency Alert Center, Command Mode
* **Phase 9**: Comprehensive Automated & End-to-End Verification

---

## Current Implementation Update (2026-11-15)

This update supersedes the original proposed tracker where it conflicts with verified work above.

* **Migrations:** V3 and V4 are additive, applied to the existing PostgreSQL database, and recorded in `schema_migrations`. V1/V2 were left unchanged; no operational rows were deleted or backfilled with invented history.
* **Authentication:** Spring Security, JWT access tokens, BCrypt passwords, pending registration approvals, HttpOnly refresh cookies, refresh rotation/replay rejection, logout, and admin approval/user APIs are implemented. Normal startup provisions missing privileged-role accounts once, with random temporary credentials stored in an owner-only local config file; see README for the exact path and first-login procedure.
* **Authorization:** Global operations are limited to dispatchers/system administrators. Organization reads and claims are scoped to the token organization; tenant request creation binds to that organization, and member request reads filter by creator. Full IDOR and role-matrix coverage remains to be expanded.
* **Auditing and events:** Request submission/status/allocation/release events and timeline API/UI use recorded database events. Resource/organization/claim changes, approval/role changes, login failures, and acknowledgements write audit rows. Existing requests have no fabricated event history.
* **Alerts:** Current exhausted and low inventory conditions create/update real alert rows; resolved conditions close those alerts. Acknowledgements are tracked per user.
* **Still outstanding:** Command Mode, a verified geographic map (the current schema has no coordinates), live activity polling, broader analytics charts, responsive/accessibility review, full final regression run, and browser verification of the new secured build.
* **Verification:** Final `mvn clean test` passed 65 tests with no failures, errors, or skips; local APIs and initial-account first-login gating were verified after startup.

## Authentication, approvals, and tenant enforcement update (2026-11-15)

* **Additive schema:** V3 adds authentication, memberships, approval requests, refresh tokens, audit rows, and alerts; V4 adds request ownership/event data; V5 adds role-aware member/manager approval fields. V1 and V2 remain unchanged. V5 was applied to the configured PostgreSQL database and recorded in `schema_migrations`; existing useful records were retained.
* **Public signup:** all four roles can be requested. Members select an active organization; managers request a proposed organization; privileged-role applications remain pending and carry no organization association. Approval can associate an active organization or create a new one in the same transaction. Signup never grants a global role.
* **Provisioning:** no `SYSTEM_ADMIN` existed in the inspected database. Missing initial global-role accounts are provisioned once by the database-locked startup runner; credentials are kept outside the repository and require a first-login password change.
* **Authorization:** system administration and dispatch are distinct global roles; managers are scoped to their organization; members are scoped to their own requests. Organization summaries, inventory projections, allocations, dashboard metrics, request views, member administration, alerts, and approval/audit routes now apply server-side role checks. Protected APIs use the production Spring Security chain in the `auth-test` profile; the legacy `test` profile remains permissive only for existing business-logic integration tests.
* **Browser:** public registration uses `/api/auth/organizations` for minimal active organization fields and `/api/auth/organization-matches` for manager-name review feedback. Session state and role navigation remain in the existing vanilla-JS application.
* **Known scope limits:** no map markers are shown because the stored organization schema has no verified coordinates. A continuously polling feed and a dedicated Command Mode remain future work; this auth/tenancy update does not fabricate such data.
* **Test-profile finding:** the shared `test` profile intentionally permits legacy integration requests. Because the auth test class inherited it, the new tenant tests were initially exercising an all-access profile. The `auth-test` expression now activates the production filter chain and production `AccessControl`, while legacy integration tests retain their existing permissive behavior. This preserves the old test harness without masking tenant checks.
* **Final test result:** `mvn clean test` with `.env` loaded in WSL passed **65 tests, 0 failures, 0 errors, 0 skipped**. `node --check` passed for `static/js/app.js`; `git diff --check` passed.
* **Process and profile:** port 8080 was served by the project's `DisasterAllocationApplication` process (PID 65569 before restart), parented by `mvn spring-boot:run`, with working directory at the project root. It had no profile override and used the default profile and `application.properties`. After the clean build, the app was restarted from the same project and rebuilt classes (PID 86036).
* **Actual browser result:** `http://localhost:8080/` rendered the sign-in page. Opening public registration showed the searchable organization selector with 11 active organization name/type entries, including City General Hospital. Selecting it produced a selected-state display and enabled submit. Switching to manager registration and entering a near-match returned the real City General Hospital match. The browser console had no errors. No registration was submitted in this browser pass; PostgreSQL-backed tests exercised member and manager registration, approval, persistence, duplicate email, and invalid organization cases.
* **Live API result:** `/api/health` returned HTTP 200 with `{"status":"UP"}` via `127.0.0.1`, `localhost`, and `[::1]`. `GET /api/auth/organizations` returned HTTP 200 with 11 minimal active organization summaries and no contact fields. An unauthenticated `GET /api/dashboard/stats` returned HTTP 401, as required by the new security policy (not an HTTP 500).
* **Admin availability:** pre-start inspection found no System Administrator or Dispatcher. The normal application startup provisioned one of each; both are ACTIVE, have no organization membership, and require a first-login password change.

## Four-role registration and initial account provisioning update (2026-11-15)

* Public registration now offers SYSTEM_ADMIN, DISPATCHER, ORGANIZATION_MANAGER, and ORGANIZATION_MEMBER requests. Only the two organization roles collect an organization field; privileged requests carry no organization association and remain pending.
* Approval logic rejects self-review, requires an existing System Administrator through the secured admin controller, and only grants the exact requested privileged role. Dispatcher has no approval or user-management endpoint access. Administrative role changes remove any stale organization membership when assigning a global role.
* Migration V6 adds the one-time bootstrap state, `must_change_password`, and constraints for four requested/approved roles while retaining V1/V2 data. Existing manager request associations are preserved in the match field when normalized.
* Database inspection before V6 found no System Administrator or Dispatcher and no existing manager request rows needing normalization. V1–V5 were applied; V6 has now been applied.
* Initial-account provisioning is disabled under both test profiles. On ordinary startup it is guarded by database locks and a singleton marker, hashes distinct random temporary passwords with BCrypt, creates active global accounts only when missing, and records credentials in the owner-only local config file. First-login tokens are limited to the password-change route; changing a password revokes refresh sessions and invalidates prior access tokens.
* Integration coverage was updated for pending privileged signup, administrator-only approval, self-approval rejection, manager self-assignment rejection, and the forced password-change gate. `mvn clean test` passed **65/65**; actual local login for both provisioned roles returned 200 with `mustChangePassword=true`, while the protected dashboard returned 403. Restarting the default-profile app left exactly one account per global role and did not rewrite the credential file. Health and organization discovery returned 200; 11 active organizations were returned. Browser automation was stopped by Escape before the final form-state screenshots and console inspection could be completed.