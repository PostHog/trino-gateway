-- A cancelled concurrent build leaves an invalid index behind; retry must replace it.
DROP INDEX CONCURRENTLY IF EXISTS transaction_admission_query_idx;
CREATE INDEX CONCURRENTLY transaction_admission_query_idx ON transaction_admission(query_id);
