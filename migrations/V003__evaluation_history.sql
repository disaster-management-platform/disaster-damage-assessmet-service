BEGIN;
ALTER TABLE assessments.damage_categories ADD COLUMN assetTypeId varchar(120) REFERENCES assessments.asset_types(id) ON DELETE RESTRICT;
CREATE TABLE assessments.evaluation_history (
 id uuid PRIMARY KEY,evaluationId uuid NOT NULL REFERENCES assessments.evaluations(evaluationId) ON DELETE RESTRICT,
 revision bigint NOT NULL CHECK(revision>0),version bigint NOT NULL CHECK(version>=0),
 actor varchar(200),organization varchar(200) NOT NULL,recordedAt timestamptz NOT NULL,
 action varchar(40) NOT NULL,reason varchar(2000),snapshot text NOT NULL,
 UNIQUE(evaluationId,version)
);
INSERT INTO assessments.evaluation_history(id,evaluationId,revision,version,organization,recordedAt,action,snapshot)
 SELECT evaluationId,evaluationId,revision,version,organization,CURRENT_TIMESTAMP,'Baseline',row_to_json(e)::text FROM assessments.evaluations e;
INSERT INTO assessments.schema_history(version) VALUES(3);
COMMIT;
