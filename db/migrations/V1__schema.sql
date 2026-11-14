-- =====================================================================
-- Disaster Resource Allocation System -- schema (PostgreSQL 14+)
-- Academic simulator. Not connected to any real emergency service.
-- =====================================================================

-- ---------- Organizations (hospitals, shelters, rescue teams) --------
CREATE TABLE organizations (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         VARCHAR(120) NOT NULL UNIQUE,
    org_type     VARCHAR(20)  NOT NULL
                 CHECK (org_type IN ('HOSPITAL', 'SHELTER', 'RESCUE_TEAM')),
    location     VARCHAR(120) NOT NULL,
    contact_info VARCHAR(200),
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------- Resources (limited inventory) ----------------------------
-- The invariant total = available + allocated is enforced by the DATABASE,
-- so even a buggy application cannot store an inconsistent count.
-- reusable = TRUE  : units come back after use (ambulances, beds, staff)
--                    -> classic Banker's Algorithm model applies.
-- reusable = FALSE : consumable stock (food, water, medicine)
--                    -> plain inventory check; NOT treated as deadlock risk.
CREATE TABLE resources (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name               VARCHAR(120) NOT NULL UNIQUE,
    category           VARCHAR(30)  NOT NULL
                       CHECK (category IN ('AMBULANCE', 'MEDICINE', 'FOOD', 'WATER',
                                           'HOSPITAL_BED', 'MEDICAL_STAFF')),
    total_quantity     INTEGER      NOT NULL CHECK (total_quantity     >= 0),
    available_quantity INTEGER      NOT NULL CHECK (available_quantity >= 0),
    allocated_quantity INTEGER      NOT NULL CHECK (allocated_quantity >= 0),
    reusable           BOOLEAN      NOT NULL DEFAULT TRUE,
    location           VARCHAR(120) NOT NULL,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    last_updated       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_resource_invariant
        CHECK (total_quantity = available_quantity + allocated_quantity)
);

-- ---------- Declared maximum claims (Banker's Algorithm "Max" matrix) -
-- Each organization declares, per resource, the most it could ever need.
CREATE TABLE organization_claims (
    organization_id BIGINT  NOT NULL REFERENCES organizations(id),
    resource_id     BIGINT  NOT NULL REFERENCES resources(id),
    max_claim       INTEGER NOT NULL CHECK (max_claim >= 0),
    PRIMARY KEY (organization_id, resource_id)
);

-- ---------- Simulation runs (declared before requests: FK target) ----
CREATE TABLE simulation_runs (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            VARCHAR(120),
    algorithm       VARCHAR(20) NOT NULL
                    CHECK (algorithm IN ('FCFS', 'PRIORITY', 'ROUND_ROBIN')),
    time_quantum    INTEGER CHECK (time_quantum IS NULL OR time_quantum > 0),
    workload_size   INTEGER NOT NULL CHECK (workload_size >= 0),
    random_seed     BIGINT,
    safe_state_check BOOLEAN NOT NULL DEFAULT TRUE,
    allow_partial   BOOLEAN NOT NULL DEFAULT FALSE,
    worker_threads  INTEGER NOT NULL DEFAULT 1 CHECK (worker_threads >= 1),
    status          VARCHAR(12) NOT NULL DEFAULT 'RUNNING'
                    CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    duration_ms     BIGINT CHECK (duration_ms IS NULL OR duration_ms >= 0),
    is_simulation   BOOLEAN NOT NULL DEFAULT TRUE   -- generated workloads are labelled
);

-- ---------- Resource requests ----------------------------------------
-- arrival_seq is a strictly increasing number: it gives FCFS a
-- deterministic order even when two requests share a timestamp.
CREATE TABLE resource_requests (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id     BIGINT      NOT NULL REFERENCES organizations(id),
    resource_id         BIGINT      NOT NULL REFERENCES resources(id),
    simulation_run_id   BIGINT      REFERENCES simulation_runs(id),
    requested_quantity  INTEGER     NOT NULL CHECK (requested_quantity > 0),
    allocated_quantity  INTEGER     NOT NULL DEFAULT 0 CHECK (allocated_quantity >= 0),
    priority            SMALLINT    NOT NULL CHECK (priority BETWEEN 1 AND 5),
    max_claim           INTEGER     NOT NULL,
    work_units          INTEGER     NOT NULL DEFAULT 1 CHECK (work_units > 0),
    arrival_seq         BIGINT GENERATED ALWAYS AS IDENTITY (START WITH 1) UNIQUE,
    arrival_time        TIMESTAMPTZ NOT NULL DEFAULT now(),
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'WAITING', 'PROCESSING',
                                          'PARTIALLY_ALLOCATED', 'ALLOCATED',
                                          'COMPLETED', 'REJECTED')),
    status_reason       VARCHAR(300),
    processing_started_at TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    CONSTRAINT chk_alloc_le_requested CHECK (allocated_quantity <= requested_quantity),
    CONSTRAINT chk_claim_ge_request   CHECK (max_claim >= requested_quantity)
);

-- ---------- Allocations (one row per allocation event) ---------------
CREATE TABLE allocations (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id   BIGINT      NOT NULL REFERENCES resource_requests(id),
    resource_id  BIGINT      NOT NULL REFERENCES resources(id),
    quantity     INTEGER     NOT NULL CHECK (quantity > 0),
    status       VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
                 CHECK (status IN ('ACTIVE', 'RELEASED')),
    allocated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at  TIMESTAMPTZ,
    CONSTRAINT chk_release_time CHECK (
        (status = 'ACTIVE'   AND released_at IS NULL) OR
        (status = 'RELEASED' AND released_at IS NOT NULL))
);

-- ---------- Resource history (append-only audit trail) ---------------
CREATE TABLE resource_history (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    resource_id      BIGINT      NOT NULL REFERENCES resources(id),
    request_id       BIGINT      REFERENCES resource_requests(id),
    action           VARCHAR(10) NOT NULL
                     CHECK (action IN ('CREATE', 'UPDATE', 'ALLOCATE', 'RELEASE')),
    quantity_change  INTEGER     NOT NULL,
    available_before INTEGER     NOT NULL,
    available_after  INTEGER     NOT NULL,
    note             VARCHAR(300),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- Simulation metrics (one row per metric per run) ----------
CREATE TABLE simulation_metrics (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id       BIGINT           NOT NULL REFERENCES simulation_runs(id) ON DELETE CASCADE,
    metric_name  VARCHAR(60)      NOT NULL,
    metric_value DOUBLE PRECISION NOT NULL,
    unit         VARCHAR(20)      NOT NULL,
    UNIQUE (run_id, metric_name)
);

-- ---------- Failure / recovery log -----------------------------------
-- Written in its OWN transaction so the log survives the rollback it describes.
CREATE TABLE failure_recovery_logs (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id          BIGINT REFERENCES simulation_runs(id) ON DELETE SET NULL,
    request_id      BIGINT REFERENCES resource_requests(id),
    event_type      VARCHAR(20) NOT NULL
                    CHECK (event_type IN ('INJECTED_FAILURE', 'ROLLBACK', 'RECOVERY',
                                          'UNSAFE_REJECTION', 'DB_ERROR')),
    injection_point VARCHAR(40),
    detail          VARCHAR(500),
    state_verified  BOOLEAN,   -- TRUE once invariants were re-checked after the event
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================================================================
-- Indexes -- each one is tied to a query the application actually runs.
-- (Their benefit must be MEASURED before being claimed; see README.)
-- =====================================================================

-- Q1: "next requests to schedule" -> pending queue ordered by priority/arrival.
--     Partial index: it only contains requests still waiting to be served.
CREATE INDEX idx_requests_queue
    ON resource_requests (priority DESC, arrival_seq)
    WHERE status IN ('PENDING', 'WAITING', 'PARTIALLY_ALLOCATED');

-- Q2: all requests for one resource in a given status (allocation / dashboard).
CREATE INDEX idx_requests_resource_status
    ON resource_requests (resource_id, status);

-- Q3: all requests of one organization (Banker's allocation matrix, org page).
CREATE INDEX idx_requests_org ON resource_requests (organization_id);

-- Q4: requests that belong to one simulation run (metrics calculation).
CREATE INDEX idx_requests_run ON resource_requests (simulation_run_id)
    WHERE simulation_run_id IS NOT NULL;

-- Q5: active allocations per request / per resource (Banker's Allocation matrix).
CREATE INDEX idx_alloc_request ON allocations (request_id);
CREATE INDEX idx_alloc_resource_active ON allocations (resource_id) WHERE status = 'ACTIVE';

-- Q6: history of one resource, newest first.
CREATE INDEX idx_history_resource_time ON resource_history (resource_id, created_at DESC);

-- Q7: recovery log lookup per run.
CREATE INDEX idx_failure_run ON failure_recovery_logs (run_id);
