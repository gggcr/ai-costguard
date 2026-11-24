-- gateway_feed takes its next seq as max(seq) + 1 under the writers' advisory lock (ADR 0080) instead of a sequence:
-- the backup role reads tables only (tools/backup/grant-backup-role.sql), and pg_dump stopped on the first sequence
-- of the schema with "permission denied for sequence gateway_feed_seq".
drop sequence gateway_feed_seq;
