BEGIN;
DO $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='damage_assessment_migrator') THEN
  CREATE ROLE damage_assessment_migrator NOLOGIN NOINHERIT;
 END IF;
 EXECUTE format('GRANT damage_assessment_migrator TO %I',current_user);
END $$;
ALTER SCHEMA assessments OWNER TO damage_assessment_migrator;
ALTER TABLE assessments.asset_types OWNER TO damage_assessment_migrator;
ALTER TABLE assessments.damage_categories OWNER TO damage_assessment_migrator;
ALTER TABLE assessments.evaluations OWNER TO damage_assessment_migrator;
ALTER TABLE assessments.schema_history OWNER TO damage_assessment_migrator;
REVOKE ALL ON SCHEMA assessments FROM PUBLIC,damage_assessment_api;
REVOKE ALL ON ALL TABLES IN SCHEMA assessments FROM PUBLIC,damage_assessment_api;
GRANT USAGE ON SCHEMA assessments TO damage_assessment_api;
GRANT SELECT,INSERT ON assessments.asset_types,assessments.damage_categories,assessments.evaluations TO damage_assessment_api;
GRANT UPDATE(active) ON assessments.asset_types,assessments.damage_categories TO damage_assessment_api;
GRANT UPDATE(disasterid,assettype,assettypeid,damagecategoryid,evaluatedat,location,latitude,longitude,geometry,damagelevel,description,evidence,budget,version,updatedat) ON assessments.evaluations TO damage_assessment_api;
ALTER DEFAULT PRIVILEGES FOR ROLE damage_assessment_migrator IN SCHEMA assessments REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE damage_assessment_migrator IN SCHEMA assessments REVOKE ALL ON SEQUENCES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE damage_assessment_migrator IN SCHEMA assessments REVOKE ALL ON FUNCTIONS FROM PUBLIC;
DO $$
DECLARE identity text;
BEGIN
 FOREACH identity IN ARRAY ARRAY['anon','authenticated'] LOOP
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname=identity) THEN
   EXECUTE format('REVOKE ALL ON SCHEMA assessments FROM %I',identity);
   EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA assessments FROM %I',identity);
   EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE damage_assessment_migrator IN SCHEMA assessments REVOKE ALL ON TABLES FROM %I',identity);
   EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE damage_assessment_migrator IN SCHEMA assessments REVOKE ALL ON SEQUENCES FROM %I',identity);
   EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE damage_assessment_migrator IN SCHEMA assessments REVOKE ALL ON FUNCTIONS FROM %I',identity);
  END IF;
 END LOOP;
END $$;
COMMIT;
