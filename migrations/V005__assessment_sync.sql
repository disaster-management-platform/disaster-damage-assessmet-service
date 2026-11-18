BEGIN;
ALTER TABLE assessments.evaluation_history ADD COLUMN transactionId xid8 NOT NULL DEFAULT pg_current_xact_id();
CREATE TABLE assessments.sync_cursors (
 id uuid PRIMARY KEY,actor varchar(200) NOT NULL,organization varchar(200) NOT NULL,
 scope varchar(64) NOT NULL,disasterId integer,
 lowerSnapshot text,upperSnapshot text,lastEvaluation uuid,expiresAt timestamptz NOT NULL
);
INSERT INTO assessments.schema_history(version) VALUES(5);
COMMIT;
