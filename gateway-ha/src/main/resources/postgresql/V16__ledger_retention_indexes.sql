-- Indexes for ledger retention. Each build is concurrent so a large ledger keeps accepting writes;
-- a cancelled concurrent build leaves an invalid index behind, so a retry must replace it.

-- Expired terminal queries in cursor order, without scanning the per-incarnation retained index.
DROP INDEX CONCURRENTLY IF EXISTS transaction_query_expiry_idx;
CREATE INDEX CONCURRENTLY transaction_query_expiry_idx ON transaction_query(retain_until, query_id) WHERE terminal;

-- A COMPLETE admission without a query is a rejection, so the index holds nothing but retention candidates.
DROP INDEX CONCURRENTLY IF EXISTS transaction_admission_rejected_idx;
CREATE INDEX CONCURRENTLY transaction_admission_rejected_idx ON transaction_admission(created_at, admission_id) WHERE query_id IS NULL AND state = 'COMPLETE';

-- Deleting a closed transaction binding checks both referencing foreign keys.
DROP INDEX CONCURRENTLY IF EXISTS transaction_query_transaction_idx;
CREATE INDEX CONCURRENTLY transaction_query_transaction_idx ON transaction_query(transaction_id) WHERE transaction_id IS NOT NULL;
DROP INDEX CONCURRENTLY IF EXISTS transaction_admission_transaction_idx;
CREATE INDEX CONCURRENTLY transaction_admission_transaction_idx ON transaction_admission(transaction_id) WHERE transaction_id IS NOT NULL;
