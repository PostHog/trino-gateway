-- Slots for lead members: members of the canary revision that serve only canary requests. They sit
-- outside max_surge and outside every serving, surge and repair count of the main pool.
ALTER TABLE pool ADD COLUMN lead_surge INT NOT NULL DEFAULT 0 CHECK (lead_surge >= 0);

-- The extra build that lead members run. Empty means the pool has no canary.
ALTER TABLE pool ADD COLUMN canary_revision TEXT NOT NULL DEFAULT '';

-- A lead is flagged when it registers and never changes. It is never a repair, because a repair
-- restores main-pool capacity.
ALTER TABLE transaction_backend ADD COLUMN lead BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE transaction_backend ADD CONSTRAINT transaction_backend_lead_check
    CHECK (NOT lead OR (pool_id IS NOT NULL AND NOT repair));
