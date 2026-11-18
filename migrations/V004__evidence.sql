BEGIN;
CREATE TABLE assessments.evidence (
 evidenceId uuid PRIMARY KEY,evaluationId uuid NOT NULL REFERENCES assessments.evaluations(evaluationId) ON DELETE RESTRICT,
 revision bigint NOT NULL CHECK(revision>0),clientRequestId uuid NOT NULL UNIQUE,
 organizationId varchar(200) NOT NULL,uploadedBy varchar(200) NOT NULL,
 originalFileName varchar(200) NOT NULL,contentType varchar(100) NOT NULL,
 size bigint NOT NULL CHECK(size>0),sha256 varchar(64) NOT NULL,storageObjectKey varchar(300) NOT NULL UNIQUE,
 status varchar(20) NOT NULL CHECK(status IN ('PendingUpload','Verified','Rejected','Expired')),
 createdAt timestamptz NOT NULL,confirmedAt timestamptz,expiresAt timestamptz NOT NULL,
 temporaryDeletedAt timestamptz,leaseId uuid,leaseUntil timestamptz,lockVersion bigint NOT NULL DEFAULT 0
);
CREATE INDEX evidence_pending_organization ON assessments.evidence(organizationId,status,expiresAt);
CREATE INDEX evidence_evaluation ON assessments.evidence(evaluationId,status);
INSERT INTO assessments.schema_history(version) VALUES(4);
COMMIT;
