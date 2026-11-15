# Disaster Resource Allocation System (DRAS)

An **academic simulator** in which hospitals, shelters and rescue teams compete for limited resources
(ambulances, beds, staff, medicine, food, water). It demonstrates Operating Systems concepts (FCFS / Priority /
Round Robin scheduling, Banker's Algorithm) together with DBMS concepts (ACID transactions, row-level locking,
constraints, indexes, rollback) in one working application with an Emergency Operations Center dashboard.

It is **not** connected to real emergency services and makes no real dispatch decisions.

Team: Himanshu Pokhriyal, Abhinav Gupta, Aayush Nautiyal, Rishabh Joshi.

## Verification status

Verified in the configured WSL environment on 2026-11-15:

| Item | Status |
|---|---|
| Maven test suite | `mvn clean test` with `.env` loaded: **65 tests passed, 0 failures, 0 errors, 0 skipped** |
| PostgreSQL integration coverage | CRUD, constraints, allocation, concurrency, rollback, authentication, approvals, and tenant-access tests ran against PostgreSQL |
| Dashboard JavaScript | `node --check src/main/resources/static/js/app.js` passed |
| Browser | Current app launch was confirmed by the local browser tab title; browser automation was stopped by Escape before the final registration selector/screenshots could be captured. Live API discovery returned 11 organizations. |
| Security check | Health/discovery APIs returned 200; protected dashboard returned 401 without a session |

Startup provisioned the initial System Administrator and Dispatcher. Their one-time credentials are stored outside the repository at
`~/.config/dras/initial-accounts.txt`; both accounts must change their passwords on first sign-in.

## What is implemented

* **Database**: V1/V2 retain the original resource-allocation schema and seed data. Additive V3–V6 migrations add auth, membership, auditing, alerts, request ownership/events, and manager approval support without resetting existing records. `scripts/setup-db.sh` is idempotent.
* **REST API** (`controller/`, `service/`, `repository/` with parameterised JDBC SQL, no ORM):
  organizations, maximum claims, resources, history, requests, allocations, scheduling, Banker's, dashboard,
  metrics, system status. Validation via Bean Validation; errors via `GlobalExceptionHandler` (400/404/409; PostgreSQL
  constraint violations become 409).
* **Allocation engine** (`allocation/AllocationEngine.java`): one transaction per request:
  lock request row -> lock resource rows (`FOR UPDATE`, fixed id order) -> recheck stock -> Banker's check ->
  update inventory, insert allocation, update request, write history -> commit. Any exception rolls everything back.
* **Banker's integration** (`allocation/BankersSnapshot.java`): processes = organizations, Max = `organization_claims`,
  Allocation = ACTIVE allocation rows, Available = inventory. Only *reusable* resources are modelled.
* **Schedulers** drive the real queue: `POST /api/allocations/process-queue` orders PENDING/WAITING requests with the
  chosen algorithm, then allocates in that order. Runs and metrics are saved in `simulation_runs` / `simulation_metrics`.
* **Failure injection** (off by default; `DRAS_FAILURE_INJECTION=true`) to demonstrate rollback; the log row is written
  outside the rolled-back transaction.
* **Dashboard** (`src/main/resources/static/`): Command Center, Resource Inventory, Organization Directory (with
  Max-claims editor), Requests Queue, Allocation Center, Scheduling Simulator (Gantt + metrics), Banker's Simulator
  (live DB state and custom matrices), Performance Metrics, System Status.

## Design decisions worth knowing

* A request needs a **declared maximum claim** for that organization/resource (set in Organization Directory ->
  "Max claims"). Without it the request is refused, because Banker's needs a Max matrix.
* Allocation is all-or-nothing (no partial allocation). Insufficient stock or an unsafe state -> `WAITING` (can be
  retried); exceeding the remaining claim or inactive org/resource -> `REJECTED`.
* Consumable resources (food/water/medicine) get a plain inventory check under a row lock; Banker's applies to
  reusable ones. For a reusable request **all reusable rows are locked**, because safety looks at every type at once.
* All queued requests are treated as arriving together (time 0) when scheduled; `arrival_seq` keeps FCFS order.
  For Round Robin, the serving order is the order requests *complete* in the simulated schedule.
* "Release" returns units to inventory for any resource (for consumables, read it as "return unused stock").

## Setup on Ubuntu / Linux

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk maven postgresql
sudo systemctl start postgresql

export DB_PASSWORD='choose-a-password'      # never commit this
./scripts/setup-db.sh                       # role + database + schema + seed data (re-runnable)

cp .env.example .env                        # set DB_PASSWORD to the same value
set -a; source .env; set +a
```

Defaults: host `localhost`, port `5432`, database `dras`, user `dras_app` (override with `DB_HOST`, `DB_PORT`,
`DB_NAME`, `DB_USER`). There is deliberately **no default password**.

## Build, test, run

```bash
mvn clean package                  # unit tests always; *IT integration tests when DB_PASSWORD is set
mvn spring-boot:run                # http://localhost:8080  (dashboard at /)
curl http://localhost:8080/api/health          # {"status":"UP","organizations":6,"resources":6}
```

Run only the integration tests: `mvn test -Dtest='*IT'`. Demo with rollback enabled:
`DRAS_FAILURE_INJECTION=true mvn spring-boot:run`.

## API reference

| Area | Endpoints |
|---|---|
| Authentication | `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`, `GET /api/auth/me`, `POST /api/auth/change-password`, public `GET /api/auth/organizations`, public `GET /api/auth/organization-matches?name=` |
| Administration | `GET /api/admin/approvals`, `POST /api/admin/approvals/{id}/approve`, `POST /api/admin/approvals/{id}/approve-new-organization`, `POST /api/admin/approvals/{id}/reject`, `GET /api/admin/users`, `PUT /api/admin/users/{id}/role`, `GET /api/admin/audit-logs` |
| Organization members / alerts | `GET /api/organizations/{id}/members`, `PUT /api/organizations/{id}/members/{userId}/status`, `GET /api/alerts`, `POST /api/alerts/{id}/acknowledge` |
| Organizations | `GET/POST /api/organizations`, `GET/PUT/DELETE /api/organizations/{id}`, `GET/PUT /api/organizations/{id}/claims` |
| Resources | `GET/POST /api/resources`, `GET/PUT/DELETE /api/resources/{id}`, `GET /api/resources/history?resourceId=&limit=` |
| Requests | `GET /api/requests?status=`, `POST /api/requests`, `GET /api/requests/{id}`, `PUT /api/requests/{id}/status`, `POST /api/requests/{id}/allocate` |
| Allocations | `GET /api/allocations?status=`, `GET /api/allocations/{id}`, `POST /api/allocations/{id}/release`, `POST /api/allocations/process-queue` |
| Scheduling | `POST /api/scheduling/simulate`, `GET /api/scheduling/queue-preview?algorithm=&quantum=` |
| Banker's | `POST /api/bankers/evaluate`, `GET /api/bankers/state?organizationId=&resourceId=&quantity=` |
| Dashboard / system | `GET /api/dashboard/stats`, `GET /api/metrics`, `GET /api/system/status`, `GET /api/health` |

Example calls:

```bash
curl -X POST localhost:8080/api/organizations -H 'Content-Type: application/json' \
  -d '{"name":"Test Clinic","orgType":"HOSPITAL","location":"Dehradun"}'
curl -X PUT localhost:8080/api/organizations/7/claims -H 'Content-Type: application/json' \
  -d '{"resourceId":1,"maxClaim":4}'
curl -X POST localhost:8080/api/requests -H 'Content-Type: application/json' \
  -d '{"organizationId":7,"resourceId":1,"quantity":2,"priority":5}'
curl -X POST localhost:8080/api/allocations/process-queue -H 'Content-Type: application/json' \
  -d '{"algorithm":"PRIORITY"}'
curl -X POST localhost:8080/api/scheduling/simulate -H 'Content-Type: application/json' \
  -d '{"algorithm":"ROUND_ROBIN","quantum":4,"jobs":[{"id":1,"priority":3,"arrivalTime":0,"workUnits":24},{"id":2,"priority":3,"arrivalTime":0,"workUnits":3},{"id":3,"priority":3,"arrivalTime":0,"workUnits":3}]}'
curl -X POST localhost:8080/api/bankers/evaluate -H 'Content-Type: application/json' \
  -d '{"available":[3,3,2],"max":[[7,5,3],[3,2,2],[9,0,2],[2,2,2],[4,3,3]],"allocation":[[0,1,0],[2,0,0],[3,0,2],[2,1,1],[0,0,2]],"process":1,"request":[1,0,2]}'
```

## Authentication and organization access

The current DRAS 2.0 build adds password-based sign-in, short-lived JWT access tokens, rotating HttpOnly refresh
cookies, pending account approval, and role-aware API access. Public registration offers all four requested roles.
Organization members select an active organization; managers request a proposed organization name; System Administrator
and Dispatcher selections create privileged approval requests only. Every registration remains `PENDING_APPROVAL`; a
requested role is never granted until an existing System Administrator approves it. Manager applications create neither
an organization nor a membership until approval.

| Role | Access |
|---|---|
| `SYSTEM_ADMIN` | Global operations, organization/resource administration, account approvals, role management, alerts, and audit logs. |
| `DISPATCHER` | Global operational dashboard, queues, inventory, allocations, scheduling, Banker's checks, metrics, status, and alerts; no user or approval administration. |
| `ORGANIZATION_MANAGER` | Own organization's dashboard, profile, claims, requests, allocations, metrics, and members. |
| `ORGANIZATION_MEMBER` | Own organization summary, resource availability, and the member's own requests. |
The roles are `SYSTEM_ADMIN`, `DISPATCHER`, `ORGANIZATION_MANAGER`, and `ORGANIZATION_MEMBER`. Tenant endpoints
derive organization scope from the authenticated token; member request data is further scoped to the submitting
member. Admin-only approval and audit APIs are under `/api/admin/**`; organization membership operations are under
`/api/organizations/{organizationId}/members`. Public organization discovery is limited to
`GET /api/auth/organizations` (active ID, name, and type only), plus the name-match helper used for manager
applications. Organization management endpoints remain protected.

Authentication schema changes are additive migrations V3–V6. V1 and V2 remain unchanged, and migrations do not reset
existing records. At first normal application startup, a database-locked, idempotent provisioner creates
`admin@dras.local` and `dispatcher@dras.local` only when no account with the corresponding global role exists. It
creates distinct random temporary passwords, stores only BCrypt hashes in PostgreSQL, and writes the passwords to
`~/.config/dras/initial-accounts.txt` with owner-only permissions. Both accounts must change their password at first
sign-in; only the password-change endpoint is available until that succeeds. Open the file locally for the first sign-in,
then remove it after changing both passwords: `rm ~/.config/dras/initial-accounts.txt`. The application never logs or
returns the generated credentials. If a role already exists, bootstrap does not duplicate or reset that account; use its
existing administrator-managed password-change process. For a lost existing administrator credential, perform recovery
only through an authorized database operator in a controlled maintenance window, rotate the password hash, set
`must_change_password=true`, increment `token_version`, and invalidate existing refresh tokens.

For full database-backed verification, load the local `.env` in WSL without echoing it, then run:

```bash
set -a; source .env; set +a
mvn clean test
```

The `*IT` integration tests use the configured PostgreSQL database and clean only the uniquely created test rows.
Never use a database reset to run the suite against a database containing useful records.

`./scripts/api-demo.sh` runs the two-organizations-7-units-each-vs-10-available race against a running server.

## Demonstration guide

1. **Scheduling (OS).** Scheduling Simulator -> load the default 24/3/3 workload -> run. FCFS average waiting = 17,
   Round Robin (q=4) = 5.67 (matches the textbook, covered by `SchedulingTest`). Priority gives priority-5 requests the
   fastest response. Then submit several requests with different priorities and use Allocation Center -> "Preview order"
   to see each algorithm order the real queue.
2. **Banker's Algorithm (OS).** Banker's Simulator -> the textbook matrices are preloaded: SAFE, sequence P1 -> P3 -> P0 -> P2 -> P4.
   Request P1 = [1,0,2] is granted; P0 = [0,2,0] after that is refused as UNSAFE although units are free.
   The "Live system state" panel does the same with real organizations, claims and allocations.
3. **Atomicity (DBMS).** Start with `DRAS_FAILURE_INJECTION=true`, tick "Inject failure before commit" in Allocation
   Center and run the queue: outcome `ROLLED_BACK`, inventory unchanged, the failure log (Performance Metrics) keeps the
   record because it is written in a separate transaction; running again without injection allocates.
4. **Isolation / concurrency (DBMS).** `scripts/api-demo.sh` or `ConcurrencyIT`: both requests race; one blocks on the
   row lock, re-reads the committed quantity and ends `WAITING`. System Status shows 0 invariant violations.
5. **Consistency (DBMS).** `ApiAndConstraintsIT` shows PostgreSQL itself rejecting `available + allocated != total`,
   priority 9, request > max_claim and unknown foreign keys, independent of application code.
6. **Durability.** Committed rows survive an application or database restart (PostgreSQL write-ahead log).

## Remaining / not done

* Everything in the "Verification status" table marked unverified: **run the build and the integration tests first.**
* No partial allocation (schema has `allow_partial`, engine is all-or-nothing); no worker-pool / semaphore layer;
  no automatic workload generator or benchmark of the indexes (no performance benefit is claimed).
* Authentication and backend role checks are implemented for the current four-role account model; map tracking and a dedicated Command Mode remain unimplemented because the project has no verified coordinates.
* The dashboard has no automated browser tests; it was checked for JavaScript syntax only.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the architecture and ER diagrams.
