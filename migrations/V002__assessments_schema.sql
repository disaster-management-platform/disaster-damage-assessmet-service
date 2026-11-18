BEGIN;
DO $$
BEGIN
 IF to_regnamespace('assessments') IS NOT NULL THEN
  IF to_regnamespace('damage_assessment') IS NOT NULL THEN
   RAISE EXCEPTION 'Both assessment schemas exist; reconcile before migrating';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM assessments.schema_history WHERE version=2) THEN
   RAISE EXCEPTION 'Unexpected assessments migration history';
  END IF;
 ELSE
  IF NOT EXISTS (SELECT 1 FROM damage_assessment.schema_history WHERE version=1) THEN
   RAISE EXCEPTION 'Apply V001 before V002';
  END IF;
  ALTER SCHEMA damage_assessment RENAME TO assessments;
  INSERT INTO assessments.schema_history(version) VALUES(2);
 END IF;
END $$;
COMMIT;
