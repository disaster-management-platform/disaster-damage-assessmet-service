BEGIN;
CREATE SCHEMA damage_assessment;
CREATE TABLE damage_assessment.asset_types (id varchar(120) PRIMARY KEY,name varchar(200) NOT NULL,active boolean NOT NULL DEFAULT true);
CREATE TABLE damage_assessment.damage_categories (id varchar(120) PRIMARY KEY,name varchar(200) NOT NULL,damageLevel varchar(20) NOT NULL CHECK (damageLevel IN ('None','Minor','Moderate','Severe','Destroyed')),active boolean NOT NULL DEFAULT true);
CREATE TABLE damage_assessment.evaluations (
 evaluationId uuid PRIMARY KEY,clientRequestId uuid NOT NULL UNIQUE,disasterId integer CHECK(disasterId>0),
 assetId varchar(200) NOT NULL,assetType varchar(200) NOT NULL,
 assetTypeId varchar(120) NOT NULL REFERENCES damage_assessment.asset_types(id) ON DELETE RESTRICT,
 damageCategoryId varchar(120) NOT NULL REFERENCES damage_assessment.damage_categories(id) ON DELETE RESTRICT,
 evaluatedAt timestamptz NOT NULL,evaluator varchar(200) NOT NULL,organization varchar(200) NOT NULL,
 location varchar(200) NOT NULL,latitude double precision CHECK(latitude BETWEEN -90 AND 90),longitude double precision CHECK(longitude BETWEEN -180 AND 180),
 geometry text,damageLevel varchar(20) NOT NULL CHECK(damageLevel IN ('None','Minor','Moderate','Severe','Destroyed')),
 description varchar(2000) NOT NULL,evidence text NOT NULL,budget numeric(19,2) NOT NULL CHECK(budget>=0),
 status varchar(20) NOT NULL CHECK(status IN ('Draft','Validated','Rejected')),revision bigint NOT NULL CHECK(revision>0),version bigint NOT NULL,
 createdAt timestamptz NOT NULL,updatedAt timestamptz NOT NULL,creationCommand text NOT NULL,creationResult text NOT NULL,
 CHECK ((latitude IS NULL)=(longitude IS NULL))
);
CREATE UNIQUE INDEX evaluation_asset_disaster ON damage_assessment.evaluations(disasterId,assetId) WHERE disasterId IS NOT NULL;
INSERT INTO damage_assessment.asset_types(id,name) VALUES
 ('Housing','Housing'),('PublicBuilding','PublicBuilding'),('Hospital','Hospital'),('School','School'),('Bridge','Bridge'),('Road','Road'),('Water','Water'),('Electricity','Electricity'),('Telecommunications','Telecommunications'),('Industrial','Industrial'),('CriticalAsset','CriticalAsset');
INSERT INTO damage_assessment.damage_categories(id,name,damageLevel) VALUES
 ('None','Sin daño','None'),('Minor','Daño leve','Minor'),('Moderate','Daño moderado','Moderate'),('Severe','Daño grave','Severe'),('Destroyed','Destrucción total','Destroyed');
CREATE TABLE damage_assessment.schema_history(version integer PRIMARY KEY,applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP);
INSERT INTO damage_assessment.schema_history(version) VALUES(1);
REVOKE ALL ON SCHEMA damage_assessment FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA damage_assessment FROM PUBLIC;
COMMIT;
