"""Targeted HTTP checks against isolated local WildFly, PostgreSQL and the real Disaster API."""
import base64
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import subprocess
import time
import uuid
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from urllib.parse import urlsplit

base=os.environ['DAMAGE_TEST_URL'].rstrip('/')
disasters=os.environ['DISASTERS_TEST_URL'].rstrip('/')
assert all(urlsplit(url).hostname in ('localhost','127.0.0.1') for url in [base,disasters]), 'Local isolated services only'
key=Path(os.environ['DAMAGE_TEST_KEY'])
def encode(value):return base64.urlsafe_b64encode(value).rstrip(b'=')
def token(role='damage.write', organization='org-test', permitted=None):
    claims={'iss':'https://identity.test','aud':'platform','sub':'evaluator-test','organization_id':organization,'role':role,'disaster_id':str(permitted or disaster_id),'iat':int(time.time()),'exp':int(time.time())+600}
    content=encode(b'{"alg":"RS256","typ":"JWT"}')+b'.'+encode(json.dumps(claims).encode())
    signed=subprocess.run(['openssl','dgst','-sha256','-sign',str(key)],input=content,capture_output=True,check=True).stdout
    return (content+b'.'+encode(signed)).decode()
def call(path,method='GET',body=None,bearer=None,identity=None,url=base):
    headers={'Content-Type':'application/json'}
    if bearer:headers['Authorization']='Bearer '+bearer
    if identity:headers['Idempotency-Key']=identity
    req=Request(url+path,method=method,data=json.dumps(body).encode() if body is not None else None,headers=headers)
    try:response=urlopen(req,timeout=10)
    except HTTPError as error:response=error
    with response:
        raw=response.read()
        try:data=json.loads(raw)
        except (ValueError,UnicodeDecodeError):data=raw.decode()
        return response.status,data
report={'nombre':'Damage test '+str(uuid.uuid4()),'ubicacion':'Local','severidad':3,'descripcion':'Isolated assessment test','latitud':4.7,'longitud':-74}
status,created=call('/api/desastres','POST',report,identity=str(uuid.uuid4()),url=disasters)
assert status==201,(status,created)
disaster_id=created['id'];auth=token()
assert call('/api/evaluaciones/catalogos')[0]==401
assert call('/api/evaluaciones/catalogos',bearer=auth)[0]==200
assert call('/api/evaluaciones/catalogos',bearer=token(organization=None))[0]==403
assert call('/api/evaluaciones/openapi',bearer=auth)[1]['openapi']=='3.0.3'
payload={'disasterId':None,'assetId':'asset-'+str(uuid.uuid4()),'assetTypeId':'Bridge','damageCategoryId':'Severe','evaluatedAt':'2026-11-18T00:00:00Z','location':'Zona Norte','latitude':4.7,'longitude':-74,'geometry':None,'description':'Daño estructural','evidence':[],'budget':150000.00}
identity=str(uuid.uuid4());status,original=call('/api/evaluaciones','POST',payload,auth,identity)
assert status==201,(status,original)
assert original['status']=='Draft' and original['revision']==1 and original['evaluator']=='evaluator-test' and original['organization']=='org-test'
evaluation_id=original['evaluationId'];version=original['version']
assert call('/api/evaluaciones','POST',payload,auth,identity)==(200,original)
assert call('/api/evaluaciones','POST',{**payload,'description':'Different'},auth,identity)[0]==409
assert call('/api/evaluaciones/'+evaluation_id,bearer=token(organization='another-org'))[0]==404
assert call('/api/evaluaciones','POST',payload,token(role='reconstruction.read'),str(uuid.uuid4()))[0]==403
updated={**payload,'description':'Edited Draft','expectedVersion':version}
status,current=call('/api/evaluaciones/'+evaluation_id,'PUT',updated,auth);assert status==200,(status,current)
assert current['version']>version and current['revision']==1
assert call('/api/evaluaciones/'+evaluation_id,'PUT',updated,auth)[0]==409
assert call('/api/evaluaciones','POST',payload,auth,identity)==(200,original)
status,linked=call('/api/evaluaciones/'+evaluation_id+'/desastre','PUT',{'disasterId':disaster_id,'expectedVersion':current['version']},auth)
assert status==200,(status,linked)
status,snapshot=call('/api/evaluaciones?disasterId='+str(disaster_id),bearer=token(role='reconstruction.read'))
assert status==200 and snapshot['schemaVersion']==1 and snapshot['disasterId']==disaster_id
assert any(row['evaluationId']==evaluation_id and row['status']=='Draft' for row in snapshot['evaluations'])
assert call('/api/evaluaciones/mapa?disasterId='+str(disaster_id),bearer=auth)[1]==snapshot
for invalid in [{'budget':-1},{'budget':1.234},{'latitude':91},{'assetTypeId':'unknown'},{'damageCategoryId':'unknown'},{'geometry':{'type':'Polygon','coordinates':[[[0,0],[1,1],[0,1],[1,0],[0,0]]]}}]:
    result=call('/api/evaluaciones','POST',{**payload,**invalid},auth,str(uuid.uuid4()))
    assert result[0] in (400,422),result
concurrent_id=str(uuid.uuid4());value={**payload,'assetId':'concurrent-'+str(uuid.uuid4())}
with ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda _:call('/api/evaluaciones','POST',value,auth,concurrent_id),range(2)))
assert sorted(r[0] for r in results)==[200,201],results
assert results[0][1]==results[1][1]
admin=token(role='damage.admin');catalog_id='type-'+uuid.uuid4().hex
assert call('/api/evaluaciones/catalogos/tipos-activo','POST',{'id':catalog_id,'name':'Configurable'},auth)[0]==403
assert call('/api/evaluaciones/catalogos/tipos-activo','POST',{'id':catalog_id,'name':'Configurable'},admin)[0]==201
assert call('/api/evaluaciones/catalogos/tipos-activo/'+catalog_id+'/desactivar','PUT',{},admin)[0] in (200,204)
print('PASS: JWT authorization, preliminary Draft, idempotency/concurrency, versioned edits, immutable receipt, real Disaster association, full reconstruction snapshot, map, catalogs and validation')

# Lifecycle and revisions keep one current evaluation per asset and immutable history.
path='/api/evaluaciones/'+evaluation_id
change={'status':'Validated','expectedVersion':linked['version'],'reason':'Inspección aprobada'}
assert call(path+'/estado','PUT',change,auth)[0]==403
status,validated=call(path+'/estado','PUT',change,admin);assert status==200,(status,validated)
assert validated['status']=='Validated'
assert call(path,'PUT',{**payload,'disasterId':disaster_id,'expectedVersion':validated['version']},auth)[0]==422
assert call(path,bearer=token(organization='another-org'))[0]==404
assert call(path,bearer=token(permitted=disaster_id+1))[0]==403
assert call(path,bearer=token(role='reconstruction.read',organization='reconstruction-org'))[0]==200
revised={**payload,'disasterId':disaster_id,'description':'Nueva inspección','expectedVersion':validated['version']}
status,current=call(path+'/revisiones','POST',revised,auth);assert status==200,(status,current)
assert current['evaluationId']==evaluation_id and current['revision']==2 and current['status']=='Draft'
assert call(path+'/revisiones','POST',revised,auth)[0]==409
status,rejected=call(path+'/estado','PUT',{'status':'Rejected','expectedVersion':current['version'],'reason':'Evidencia insuficiente'},admin)
assert status==200 and rejected['status']=='Rejected',(status,rejected)
status,current=call(path+'/revisiones','POST',{**revised,'expectedVersion':rejected['version']},auth);assert status==200
status,current=call(path+'/estado','PUT',{'status':'Validated','expectedVersion':current['version'],'reason':'Revisión completa'},admin);assert status==200
status,history=call(path+'/historial',bearer=auth);assert status==200
assert [h['version'] for h in history]==list(range(current['version']+1))
assert all(h['actor']=='evaluator-test' and h['recordedAt'] for h in history)
assert json.loads(history[0]['snapshot'])==original
assert any(json.loads(h['snapshot'])['status']=='Rejected' for h in history)
assert call('/api/evaluaciones','POST',payload,auth,identity)==(200,original)
scoped='category-'+uuid.uuid4().hex
assert call('/api/evaluaciones/catalogos/categorias-dano','POST',{'id':scoped,'name':'Specific','damageLevel':'Severe','assetTypeId':'Housing'},admin)[0]==201
assert call('/api/evaluaciones','POST',{**payload,'damageCategoryId':scoped},auth,str(uuid.uuid4()))[0]==422
for state,level in [('Draft','Severe'),('Rejected','Severe'),('Validated','None')]:
    status,row=call('/api/evaluaciones','POST',{**payload,'disasterId':disaster_id,'assetId':str(uuid.uuid4()),'damageCategoryId':level},auth,str(uuid.uuid4()));assert status==201
    if state!='Draft':assert call('/api/evaluaciones/'+row['evaluationId']+'/estado','PUT',{'status':state,'expectedVersion':row['version'],'reason':'Lifecycle test'},admin)[0]==200
print('PASS: authorized lifecycle, immutable snapshots, functional revisions, current snapshot, scoped classification, organization/disaster isolation')
print(json.dumps({'disasterId':disaster_id,'evaluationId':evaluation_id,'revision':current['revision']}))
