-- Additive release ledger. Existing allocation and inventory rows remain intact.
ALTER TABLE allocations
    ADD COLUMN released_quantity INTEGER NOT NULL DEFAULT 0;

-- Existing RELEASED rows represent a full return under the original API.
UPDATE allocations SET released_quantity = quantity WHERE status = 'RELEASED';

ALTER TABLE allocations
    ADD CONSTRAINT chk_allocation_released_quantity
        CHECK (released_quantity >= 0 AND released_quantity <= quantity),
    ADD CONSTRAINT chk_allocation_release_state
        CHECK ((status = 'ACTIVE' AND released_quantity < quantity)
            OR (status = 'RELEASED' AND released_quantity = quantity));

CREATE TABLE allocation_releases (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    allocation_id BIGINT NOT NULL REFERENCES allocations(id),
    resource_id BIGINT NOT NULL REFERENCES resources(id),
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    released_quantity INTEGER NOT NULL CHECK (released_quantity > 0),
    released_at TIMESTAMPTZ NOT NULL,
    released_by_user_id BIGINT REFERENCES app_users(id) ON DELETE SET NULL
);

CREATE INDEX idx_allocation_releases_allocation_time
    ON allocation_releases (allocation_id, released_at DESC, id DESC);
CREATE INDEX idx_allocation_releases_org_time
    ON allocation_releases (organization_id, released_at DESC);

-- Preserve timestamps and known actors for pre-ledger full releases. Never invent an actor.
INSERT INTO allocation_releases
    (allocation_id, resource_id, organization_id, released_quantity, released_at, released_by_user_id)
SELECT a.id, a.resource_id, q.organization_id, a.quantity, a.released_at,
       (SELECT al.actor_user_id FROM audit_logs al
        WHERE al.action = 'ALLOCATION_RELEASED' AND al.target_type = 'ALLOCATION'
          AND al.target_id = a.id::text
        ORDER BY al.created_at DESC, al.id DESC LIMIT 1)
FROM allocations a
JOIN resource_requests q ON q.id = a.request_id
WHERE a.status = 'RELEASED';
