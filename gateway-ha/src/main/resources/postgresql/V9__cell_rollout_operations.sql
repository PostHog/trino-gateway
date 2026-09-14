CREATE TABLE transaction_rollout (
    operation_id VARCHAR(256) PRIMARY KEY,
    routing_group VARCHAR(256) NOT NULL REFERENCES transaction_route(routing_group),
    plan JSONB NOT NULL,
    phase VARCHAR(16) NOT NULL CHECK (phase IN ('CLAIMED', 'WARMED', 'VERIFIED', 'CUTOVER', 'DRAINING', 'SEALED', 'STOPPED', 'COMPLETE')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    evidence JSONB NOT NULL DEFAULT '{}',
    publications JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX transaction_rollout_active_group ON transaction_rollout (routing_group) WHERE phase <> 'COMPLETE';
