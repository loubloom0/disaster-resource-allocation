-- Add real request ownership and event history without backfilling invented user events.
ALTER TABLE resource_requests
    ADD COLUMN created_by_user_id BIGINT REFERENCES app_users(id) ON DELETE SET NULL;

CREATE INDEX idx_requests_created_by ON resource_requests (created_by_user_id, arrival_seq DESC)
    WHERE created_by_user_id IS NOT NULL;

CREATE TABLE request_events (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id      BIGINT NOT NULL REFERENCES resource_requests(id) ON DELETE CASCADE,
    actor_user_id   BIGINT REFERENCES app_users(id) ON DELETE SET NULL,
    event_type      VARCHAR(60) NOT NULL,
    from_status     VARCHAR(30),
    to_status       VARCHAR(30),
    details         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_request_events_timeline ON request_events (request_id, created_at, id);
CREATE INDEX idx_request_events_recent ON request_events (created_at DESC, id DESC);
