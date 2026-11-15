-- Additive authentication, approval, audit, and alert tables.
-- Existing V1/V2 objects and operational records are intentionally untouched.

CREATE TABLE app_users (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email            VARCHAR(160) NOT NULL,
    password_hash    VARCHAR(100) NOT NULL,
    full_name        VARCHAR(120) NOT NULL,
    contact_info     VARCHAR(200),
    global_role      VARCHAR(30)
                     CHECK (global_role IS NULL OR global_role IN ('SYSTEM_ADMIN', 'DISPATCHER')),
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING_APPROVAL'
                     CHECK (status IN ('PENDING_APPROVAL', 'ACTIVE', 'DISABLED')),
    token_version    INTEGER NOT NULL DEFAULT 0 CHECK (token_version >= 0),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_app_users_email_lower ON app_users (lower(email));
CREATE INDEX idx_app_users_status_created ON app_users (status, created_at DESC);

CREATE TABLE organization_memberships (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT NOT NULL UNIQUE REFERENCES app_users(id) ON DELETE CASCADE,
    organization_id  BIGINT NOT NULL REFERENCES organizations(id),
    role             VARCHAR(30) NOT NULL
                     CHECK (role IN ('ORGANIZATION_MANAGER', 'ORGANIZATION_MEMBER')),
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING_APPROVAL'
                     CHECK (status IN ('PENDING_APPROVAL', 'INVITED', 'ACTIVE', 'DISABLED')),
    invited_by       BIGINT REFERENCES app_users(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, user_id)
);

CREATE INDEX idx_memberships_org_status ON organization_memberships (organization_id, status);

CREATE TABLE account_approval_requests (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    organization_id  BIGINT NOT NULL REFERENCES organizations(id),
    requested_role   VARCHAR(30) NOT NULL DEFAULT 'ORGANIZATION_MEMBER'
                     CHECK (requested_role IN ('ORGANIZATION_MANAGER', 'ORGANIZATION_MEMBER')),
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reviewed_by      BIGINT REFERENCES app_users(id),
    reviewed_at      TIMESTAMPTZ,
    review_note      VARCHAR(300),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_approval_review_state CHECK (
        (status = 'PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL) OR
        (status <> 'PENDING' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_pending_approval_per_user
    ON account_approval_requests (user_id) WHERE status = 'PENDING';
CREATE INDEX idx_approval_queue ON account_approval_requests (created_at) WHERE status = 'PENDING';

CREATE TABLE refresh_tokens (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    token_hash       CHAR(64) NOT NULL UNIQUE,
    expires_at       TIMESTAMPTZ NOT NULL,
    revoked_at       TIMESTAMPTZ,
    replaced_by      BIGINT REFERENCES refresh_tokens(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user_live ON refresh_tokens (user_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE audit_logs (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id    BIGINT REFERENCES app_users(id) ON DELETE SET NULL,
    action           VARCHAR(80) NOT NULL,
    target_type      VARCHAR(60),
    target_id        VARCHAR(100),
    details          JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_created ON audit_logs (created_at DESC);
CREATE INDEX idx_audit_logs_actor ON audit_logs (actor_user_id, created_at DESC);
CREATE INDEX idx_audit_logs_target ON audit_logs (target_type, target_id, created_at DESC);

CREATE TABLE emergency_alerts (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_key        VARCHAR(160) NOT NULL UNIQUE,
    level            VARCHAR(15) NOT NULL CHECK (level IN ('CRITICAL', 'WARNING', 'INFO')),
    title            VARCHAR(150) NOT NULL,
    message          VARCHAR(400) NOT NULL,
    source           VARCHAR(60) NOT NULL,
    status           VARCHAR(15) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at      TIMESTAMPTZ
);

CREATE INDEX idx_emergency_alerts_open ON emergency_alerts (level, created_at DESC)
    WHERE status = 'OPEN';

CREATE TABLE alert_acknowledgements (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_id         BIGINT NOT NULL REFERENCES emergency_alerts(id) ON DELETE CASCADE,
    user_id          BIGINT NOT NULL REFERENCES app_users(id),
    acknowledged_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (alert_id, user_id)
);

CREATE INDEX idx_alert_ack_user_time ON alert_acknowledgements (user_id, acknowledged_at DESC);
