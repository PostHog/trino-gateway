-- Service grants use an explicitly published namespace instead of one row per short-lived credential.
-- The pool lock serializes this mapping with persistent principals and query admission.
CREATE TABLE pool_tenant_service_principal (
    pool_id VARCHAR(256) NOT NULL REFERENCES pool(pool_id),
    service_principal_prefix VARCHAR(68) NOT NULL,
    tenant VARCHAR(256) NOT NULL,
    revision VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (pool_id, service_principal_prefix),
    UNIQUE (pool_id, tenant),
    CHECK (service_principal_prefix ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?[.]svc_$')
);

-- Prefix conflicts use an indexed lookup while the caller holds the pool lock.
CREATE INDEX pool_tenant_principal_service_prefix_idx
    ON pool_tenant_principal (pool_id, (left(principal, length(principal) - 24)))
    WHERE length(principal) <= 92
      AND principal ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?[.]svc_[0-9a-f]{24}$';
