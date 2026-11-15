-- Keep requested registration details separate from the approved membership.
-- Manager applicants do not receive an organization membership until review.
ALTER TABLE account_approval_requests
    ALTER COLUMN organization_id DROP NOT NULL;

ALTER TABLE account_approval_requests
    ADD COLUMN requested_organization_name VARCHAR(120),
    ADD COLUMN matched_organization_id BIGINT REFERENCES organizations(id),
    ADD COLUMN approved_role VARCHAR(30)
        CHECK (approved_role IS NULL OR approved_role IN ('ORGANIZATION_MANAGER', 'ORGANIZATION_MEMBER')),
    ADD COLUMN approved_organization_id BIGINT REFERENCES organizations(id);

UPDATE account_approval_requests a
SET requested_organization_name = o.name
FROM organizations o
WHERE a.organization_id = o.id AND a.requested_organization_name IS NULL;

UPDATE account_approval_requests a
SET approved_role = m.role, approved_organization_id = m.organization_id
FROM organization_memberships m
WHERE a.user_id = m.user_id AND a.status = 'APPROVED'
  AND a.approved_role IS NULL;

ALTER TABLE account_approval_requests
    ADD CONSTRAINT chk_approval_approved_association CHECK (
        status <> 'APPROVED' OR (approved_role IS NOT NULL AND approved_organization_id IS NOT NULL)
    ),
    ADD CONSTRAINT chk_approval_requested_association CHECK (
        (requested_role = 'ORGANIZATION_MEMBER' AND organization_id IS NOT NULL)
        OR (requested_role = 'ORGANIZATION_MANAGER' AND requested_organization_name IS NOT NULL)
    );

CREATE INDEX idx_approval_matched_org
    ON account_approval_requests (matched_organization_id)
    WHERE matched_organization_id IS NOT NULL;
