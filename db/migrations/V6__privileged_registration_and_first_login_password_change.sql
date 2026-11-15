-- Allow privileged signup requests and enforce one-time password change for provisioned accounts.
-- No granted role is stored for an applicant until an authorized administrator approves the request.
ALTER TABLE app_users
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE auth_bootstrap_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    initialized_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Normalize legacy manager applications: V3 required organization_id even though
-- manager applications are now name requests. Preserve the old association as a review match.
ALTER TABLE account_approval_requests DROP CONSTRAINT chk_approval_requested_association;
UPDATE account_approval_requests a
   SET matched_organization_id = COALESCE(a.matched_organization_id, a.organization_id),
       organization_id = NULL
 WHERE a.requested_role = 'ORGANIZATION_MANAGER'
   AND a.organization_id IS NOT NULL;

ALTER TABLE account_approval_requests
    DROP CONSTRAINT account_approval_requests_requested_role_check,
    ADD CONSTRAINT account_approval_requests_requested_role_check
        CHECK (requested_role IN ('SYSTEM_ADMIN', 'DISPATCHER', 'ORGANIZATION_MANAGER', 'ORGANIZATION_MEMBER')),
    DROP CONSTRAINT account_approval_requests_approved_role_check,
    ADD CONSTRAINT account_approval_requests_approved_role_check
        CHECK (approved_role IS NULL OR approved_role IN ('SYSTEM_ADMIN', 'DISPATCHER', 'ORGANIZATION_MANAGER', 'ORGANIZATION_MEMBER')),
    DROP CONSTRAINT chk_approval_approved_association,
    ADD CONSTRAINT chk_approval_approved_association CHECK (
        status <> 'APPROVED' OR
        (approved_role IS NOT NULL AND (
            (approved_role IN ('ORGANIZATION_MANAGER', 'ORGANIZATION_MEMBER') AND approved_organization_id IS NOT NULL) OR
            (approved_role IN ('SYSTEM_ADMIN', 'DISPATCHER') AND approved_organization_id IS NULL)
        ))
    ),
    ADD CONSTRAINT chk_approval_requested_association CHECK (
        (requested_role = 'ORGANIZATION_MEMBER' AND organization_id IS NOT NULL) OR
        (requested_role = 'ORGANIZATION_MANAGER' AND organization_id IS NULL AND requested_organization_name IS NOT NULL) OR
        (requested_role IN ('SYSTEM_ADMIN', 'DISPATCHER') AND organization_id IS NULL AND requested_organization_name IS NULL)
    );