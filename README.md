# Disaster Resource Allocation System (DRAS)

An **academic simulator** in which hospitals, shelters and rescue teams compete for limited resources
(ambulances, beds, staff, medicine, food, water). It demonstrates Operating Systems concepts (FCFS / Priority /
Round Robin scheduling, Banker's Algorithm) together with DBMS concepts (ACID transactions, row-level locking,
constraints, indexes, rollback) in one working application with an Emergency Operations Center dashboard.

It is **not** connected to real emergency services and makes no real dispatch decisions.

Team: Himanshu Pokhriyal, Abhinav Gupta, Aayush Nautiyal, Rishabh Joshi.

## Verification status (read this first)

The environment this version was completed in had **no Maven, no PostgreSQL and no network access**. So:

| Item | Status |
|---|---|
| Scheduler + Banker's code (unchanged) and its 24 original tests | compiled with `javac -Xlint:all`; **24/24 pass** (stand-in JUnit runner) |
| `BankersSnapshotTest` (matrices built from claims / holdings / inventory) | compiled; **5/5 pass** (same runner) |
| All new backend code (controllers, services, repositories, engine) | **type-checked only** against hand-written stand-ins for the Spring/JDBC APIs. Not built with real Spring Boot. |
| Dashboard JavaScript | `node --check` syntax OK. **Never opened in a browser, never run against the API.** |
| PostgreSQL connectivity, SQL statements, `SELECT ... FOR UPDATE`, constraints | **written, not executed**. No database was available. |
| Integration tests (`*IT.java`: CRUD, allocation, rollback, 7+7 vs 10 race, constraints, REST) | **written, never run**. They need PostgreSQL and are skipped when `DB_PASSWORD` is unset. |

So the claim "works end to end" has **not** been verified yet. The first real run on your machine
(`mvn clean package` with the database up) is the verification step. If anything fails, send the error output.

## What is implemented

* **Database** (unchanged): 9 tables, CHECK/FK/UNIQUE constraints, 7 indexes, seed data, idempotent `scripts/setup-db.sh`.
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
* No authentication; the API is open (academic simulator).
* The dashboard has no automated browser tests; it was checked for JavaScript syntax only.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the architecture and ER diagrams.
