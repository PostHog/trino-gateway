-- Admissions are inserted PENDING and completed by a non-HOT update, so every completed admission
-- leaves a dead entry in transaction_admission_pending_idx. The table keeps every completed row, so
-- the default 10% scale factor waits for millions of dead tuples while the index's live set stays
-- tiny, and every reader of pending admissions scans the dead entries in the meantime.
-- Trigger vacuum on a fixed number of dead tuples instead, and always vacuum indexes so the
-- PostgreSQL 14+ bypass for few dead heap pages cannot leave the dead index entries behind.
-- Changing these options takes a SHARE UPDATE EXCLUSIVE lock, which does not block reads or writes.
ALTER TABLE transaction_admission SET (
    autovacuum_vacuum_scale_factor = 0,
    autovacuum_vacuum_threshold = 20000,
    vacuum_index_cleanup = on);
