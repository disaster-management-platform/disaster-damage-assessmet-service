CREATE POLICY assessment_evidence_select ON storage.objects FOR SELECT TO authenticated
 USING (bucket_id='assessments-evidence' AND (select auth.uid())=:'storage_identity'::uuid);
CREATE POLICY assessment_evidence_insert ON storage.objects FOR INSERT TO authenticated
 WITH CHECK (bucket_id='assessments-evidence' AND (select auth.uid())=:'storage_identity'::uuid);
CREATE POLICY assessment_evidence_delete ON storage.objects FOR DELETE TO authenticated
 USING (bucket_id='assessments-evidence' AND (select auth.uid())=:'storage_identity'::uuid AND name LIKE 'pending/%');
