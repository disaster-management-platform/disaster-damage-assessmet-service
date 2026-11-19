"""Batch and snapshot-cursor regressions; isolated PostgreSQL and local WildFly only."""
import base64
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import subprocess
import time
import uuid
import sys
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from urllib.parse import urlsplit
import psycopg2
from psycopg2.extensions import parse_dsn

base=os.environ['DAMAGE_TEST_URL'];assert urlsplit(base).hostname in ('localhost','127.0.0.1')
dsn=os.environ['DAMAGE_TEST_POSTGRES_CONNECTION'];assert parse_dsn(dsn).get('host') in ('localhost','127.0.0.1')
key=Path(os.environ['DAMAGE_TEST_KEY'])
test_organization='sync-'+uuid.uuid4().hex
def token(actor='sync-test',organization=test_organization,grants=None):
 def encode(v):return base64.urlsafe_b64encode(v).rstrip(b'=')
 claims={'iss':'https://identity.test','aud':'platform','sub':actor,'organization_id':organization,'role':'damage.write','disaster_id':grants or [],'iat':int(time.time()),'exp':int(time.time())+600}
 data=encode(b'{"alg":"RS256","typ":"JWT"}')+b'.'+encode(json.dumps(claims).encode());signature=subprocess.run(['openssl','dgst','-sha256','-sign',str(key)],input=data,capture_output=True,check=True).stdout
 return (data+b'.'+encode(signature)).decode()
auth=token()
def call(path,method='GET',body=None,bearer=auth,target=None):
 headers={'Authorization':'Bearer '+bearer,'Content-Type':'application/json'}
 try:r=urlopen(Request((target or base)+'/api/evaluaciones'+path,method=method,data=None if body is None else json.dumps(body).encode(),headers=headers),timeout=20)
 except HTTPError as e:r=e
 with r:return r.status,json.load(r)
def operation(budget=12.34):return {'clientRequestId':str(uuid.uuid4()),'evaluation':{'disasterId':None,'assetId':str(uuid.uuid4()),'assetTypeId':'Bridge','damageCategoryId':'Severe','evaluatedAt':'2026-11-18T00:00:00Z','location':'Batch delta local','description':'Synthetic','latitude':None,'longitude':None,'geometry':None,'evidence':[],'budget':budget}}
if '--scope-only' in sys.argv:
 disaster=int(os.environ['DAMAGE_TEST_DISASTER_ID'])
 op=operation();status,result=call('/batch','POST',{'operations':[op]});assert status==200,(status,result)
 first=result['results'][0]['evaluation'];status,initial=call('/sync');assert status==200
 scoped=token(grants=[str(disaster)])
 status,target=call('/sync?disasterId='+str(disaster),bearer=scoped);assert status==200
 status,associated=call('/'+first['evaluationId']+'/desastre','PUT',{'disasterId':disaster,'expectedVersion':first['version']},scoped);assert status==200,(status,associated)
 status,moved=call('/sync?disasterId='+str(disaster)+'&cursor='+target['nextCursor'],bearer=scoped);assert status==200
 assert any(v['evaluationId']==first['evaluationId'] for v in moved['evaluations'])
 for path in ['/sync?cursor='+initial['nextCursor'],'/sync']:
  status,removed=call(path);assert status==200,(status,removed)
  assert not any(v['evaluationId']==first['evaluationId'] for v in removed['evaluations'])
  tombstones=[v for v in removed['removed'] if v['evaluationId']==first['evaluationId']]
  assert len(tombstones)==1 and set(tombstones[0])=={'evaluationId','version'},tombstones
 print('PASS authorized destination and preliminary-scope invalidation, including initial/reset cursor, without target disclosure')
 sys.exit(0)
a=operation();bad=operation(-1);b=operation()
status,batch=call('/batch','POST',{'operations':[a,bad,b]});assert status==200,(status,batch)
assert [v['status'] for v in batch['results']]==['Created','ValidationError','Created'],batch
replayed=call('/batch','POST',{'operations':[a]})[1]['results'][0];assert replayed['status']=='AlreadyProcessed' and replayed['evaluation']==batch['results'][0]['evaluation']
changed={**a,'evaluation':{**a['evaluation'],'description':'different'}};assert call('/batch','POST',{'operations':[changed]})[1]['results'][0]['status']=='Conflict'
assert call('/batch','POST',{'operations':[operation() for _ in range(51)]})[0]==400
assert call('/batch','POST',{'operations':[a,a]})[0]==400
status,page=call('/sync?limit=1');assert status==200,(status,page)
ids=[]
while True:
 ids.extend(v['evaluationId'] for v in page['evaluations']);cursor=page['nextCursor']
 if not page['hasMore']:break
 status,page=call('/sync?limit=1&cursor='+cursor);assert status==200
assert set(ids)=={v['evaluation']['evaluationId'] for v in batch['results'] if 'evaluation' in v} and len(ids)==2
assert call('/sync?cursor='+cursor,bearer=token(organization='another-org'))[0]==409
assert call('/sync?cursor='+cursor,bearer=token(actor='another-user'))[0]==409
assert call('/sync?cursor='+cursor,bearer=token(grants=['1']))[0]==409
assert call('/sync?disasterId=1')[0]==403
assert call('/sync?limit=101')[0]==400
assert call('/sync?cursor='+cursor)[1]['evaluations']==[]
# A commits after B despite receiving its transaction ID first.
first=batch['results'][0]['evaluation'];second=batch['results'][2]['evaluation'];database=psycopg2.connect(dsn);database.autocommit=True;query=database.cursor()
query.execute("CREATE FUNCTION assessments.sync_delay_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(74005,1); RETURN NEW; END $$")
query.execute("CREATE CONSTRAINT TRIGGER sync_delay_test AFTER INSERT ON assessments.evaluation_history DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.evaluationId='"+first['evaluationId']+"'::uuid) EXECUTE FUNCTION assessments.sync_delay_test()")
database.autocommit=False;query.execute('SELECT pg_advisory_xact_lock(74005,1)')
try:
 with ThreadPoolExecutor(max_workers=1) as pool:
  future=pool.submit(call,'/'+first['evaluationId'],'PUT',{**a['evaluation'],'description':'commits late','expectedVersion':first['version']})
  monitor=psycopg2.connect(dsn);monitor.autocommit=True
  with monitor.cursor() as q:
   deadline=time.monotonic()+5
   while True:
    q.execute("SELECT count(*) FROM pg_stat_activity WHERE usename='damage_assessment_api' AND wait_event='advisory'")
    if q.fetchone()[0]:break
    assert time.monotonic()<deadline;time.sleep(.05)
  monitor.close()
  assert call('/'+second['evaluationId'],'PUT',{**b['evaluation'],'description':'commits first','expectedVersion':second['version']})[0]==200
  status,early=call('/sync?cursor='+cursor);assert status==200
  assert [v['evaluationId'] for v in early['evaluations']]==[second['evaluationId']],early
  database.rollback();assert future.result()[0]==200
 status,late=call('/sync?cursor='+early['nextCursor']);assert status==200
 assert [v['evaluationId'] for v in late['evaluations']]==[first['evaluationId']],late
 assert late['evaluations'][0]['description']=='commits late'
finally:
 database.rollback();database.autocommit=True;query.execute('DROP TRIGGER sync_delay_test ON assessments.evaluation_history; DROP FUNCTION assessments.sync_delay_test()');database.close()
replica=os.environ.get('DAMAGE_TEST_REPLICA')
if replica:
 assert urlsplit(replica).hostname in ('localhost','127.0.0.1')
 assert call('/sync?cursor='+late['nextCursor'],target=replica)[0]==200
# Association moves a preliminary evaluation into the authorized disaster stream.
scoped=token(grants=['1'])
status,empty=call('/sync?disasterId=1',bearer=scoped);assert status==200
status,associated=call('/'+first['evaluationId']+'/desastre','PUT',{'disasterId':1,'expectedVersion':late['evaluations'][0]['version']},scoped);assert status==200,(status,associated)
status,moved=call('/sync?disasterId=1&cursor='+empty['nextCursor'],bearer=scoped);assert status==200
assert any(v['evaluationId']==first['evaluationId'] and v['disasterId']==1 for v in moved['evaluations'])
print('PASS: independent batch outcomes, immutable idempotent replay, limits, initial pages, scoped cursors, incremental updates and out-of-order transaction commits without omissions')

# The original preliminary scope must receive only the removal, even without target grants.
status,removed=call('/sync?cursor='+late['nextCursor']);assert status==200,(status,removed)
assert not any(v['evaluationId']==first['evaluationId'] for v in removed['evaluations'])
tombstones=[v for v in removed['removed'] if v['evaluationId']==first['evaluationId']]
assert len(tombstones)==1 and set(tombstones[0])=={'evaluationId','version'},tombstones
print('PASS: preliminary scope removal without target disaster disclosure')
