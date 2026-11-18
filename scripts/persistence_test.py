"""Persistence regressions against two isolated local WildFly instances sharing PostgreSQL."""
import base64
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import subprocess
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
import uuid

urls=os.environ['DAMAGE_TEST_URLS'].split(',')
assert len(set(urls))==2 and all(urlsplit(u).hostname in ('localhost','127.0.0.1') for u in urls)
key=Path(os.environ['DAMAGE_TEST_KEY'])
def encode(value):return base64.urlsafe_b64encode(value).rstrip(b'=')
claims={'iss':'https://identity.test','aud':'platform','sub':'persistence-test','organization_id':'persistence-org','role':'damage.write','iat':int(time.time()),'exp':int(time.time())+600}
content=encode(b'{"alg":"RS256","typ":"JWT"}')+b'.'+encode(json.dumps(claims).encode())
signature=subprocess.run(['openssl','dgst','-sha256','-sign',str(key)],input=content,capture_output=True,check=True).stdout
auth=(content+b'.'+encode(signature)).decode()
def call(instance,path,method='GET',body=None,identity=None):
    headers={'Authorization':'Bearer '+auth,'Content-Type':'application/json'}
    if identity:headers['Idempotency-Key']=identity
    request=Request(urls[instance]+path,method=method,headers=headers,data=None if body is None else json.dumps(body).encode())
    try:response=urlopen(request,timeout=15)
    except HTTPError as error:response=error
    with response:return response.status,json.loads(response.read())
for instance in range(2):assert call(instance,'/api/evaluaciones/diagnostico/ready')[0]==200
payload={'disasterId':None,'assetId':'persistence-'+str(uuid.uuid4()),'assetTypeId':'Bridge','damageCategoryId':'Severe','evaluatedAt':'2026-11-18T00:00:00Z','location':'Local','latitude':4.7,'longitude':-74,'geometry':None,'description':'Original','evidence':[],'budget':123456.78}
identity=str(uuid.uuid4())
with ThreadPoolExecutor(max_workers=2) as pool:
    results=list(pool.map(lambda i:call(i,'/api/evaluaciones','POST',payload,identity),range(2)))
assert sorted(r[0] for r in results)==[200,201],results
assert results[0][1]==results[1][1]
original=results[0][1];path='/api/evaluaciones/'+original['evaluationId']
assert original['budget']==123456.78 and original['revision']==1 and original['version']==0
updates=[{**payload,'description':'Edit '+str(i),'expectedVersion':0} for i in range(2)]
with ThreadPoolExecutor(max_workers=2) as pool:
    results=list(pool.map(lambda i:call(i,path,'PUT',updates[i]),range(2)))
assert sorted(r[0] for r in results)==[200,409],results
current=next(value for status,value in results if status==200)
assert current['version']==1 and current['revision']==1
first=call(0,path);second=call(1,path)
assert first==second,(first,second)
assert first[0]==200
assert {k:v for k,v in first[1].items() if k!='updatedAt'}=={k:v for k,v in current.items() if k!='updatedAt'}
assert abs((datetime.fromisoformat(first[1]['updatedAt'])-datetime.fromisoformat(current['updatedAt'])).total_seconds())<=0.000001
current=first[1]
assert call(1,'/api/evaluaciones','POST',payload,identity)==(200,original)
assert call(0,'/api/evaluaciones','POST',{**payload,'budget':1},identity)[0]==409
invalid={**payload,'expectedVersion':1,'description':'Must roll back','damageCategoryId':'missing'}
assert call(1,path,'PUT',invalid)[0]==422
assert call(0,path)==(200,current)
assert call(1,'/api/evaluaciones','POST',invalid,str(uuid.uuid4()))[0]==422
print('PASS: two instances, concurrent idempotency, optimistic locking, immutable receipt, monetary precision, failed update rollback')
print(json.dumps({'evaluationId':original['evaluationId'],'current':current,'identity':identity,'payload':payload,'original':original}))

if os.environ.get('DAMAGE_TEST_POSTGRES_CONNECTION'):
    import psycopg2
    from psycopg2.extensions import parse_dsn
    connection=os.environ['DAMAGE_TEST_POSTGRES_CONNECTION']
    assert parse_dsn(connection).get('host') in ('localhost','127.0.0.1'), 'Local PostgreSQL only'
    database=psycopg2.connect(connection);database.autocommit=True;query=database.cursor()
    query.execute("CREATE FUNCTION assessments.fail_history_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test rollback' USING ERRCODE='23514'; END $$; CREATE TRIGGER fail_history_test BEFORE INSERT ON assessments.evaluation_history FOR EACH ROW WHEN (NEW.action='Edited') EXECUTE FUNCTION assessments.fail_history_test()")
    try:
        assert call(0,path,'PUT',{**payload,'expectedVersion':current['version'],'description':'Must roll back'},None)[0]==500
    finally:
        query.execute('DROP TRIGGER fail_history_test ON assessments.evaluation_history; DROP FUNCTION assessments.fail_history_test()')
    assert call(1,path)==(200,current)
    assert len(call(1,path+'/historial')[1])==2
    database.autocommit=False
    query.execute("SELECT id FROM assessments.asset_types WHERE id='Bridge' FOR UPDATE")
    try:
        with ThreadPoolExecutor(max_workers=6) as pool:
            futures=[pool.submit(call,1,'/api/evaluaciones','POST',{**payload,'assetId':str(uuid.uuid4())},str(uuid.uuid4())) for _ in range(6)]
            time.sleep(2);database.rollback();results=[f.result() for f in futures]
        assert all(status in (201,503) for status,_ in results),results
        assert any(status==503 for status,_ in results),results
    finally:
        database.rollback();database.close()
    assert call(1,'/api/evaluaciones/diagnostico/ready')[0]==200
    assert call(1,'/api/evaluaciones','POST',{**payload,'assetId':str(uuid.uuid4())},str(uuid.uuid4()))[0]==201
    print('PASS: history failure rolls back entity/version, pool exhaustion returns 503 and recovers')
