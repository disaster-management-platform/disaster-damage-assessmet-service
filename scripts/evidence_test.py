"""Evidence integration: isolated local metadata, real private Storage, signed test identities."""
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import uuid
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request,urlopen

urls=os.environ['DAMAGE_TEST_URLS'].split(',');assert len(urls)==2 and all(urlsplit(u).hostname in ('localhost','127.0.0.1') for u in urls)
key=Path(os.environ['DAMAGE_TEST_KEY'])
def token(org='evidence-test'):
    def encode(value):return base64.urlsafe_b64encode(value).rstrip(b'=')
    claims={'iss':'https://identity.test','aud':'platform','sub':'evidence-test','organization_id':org,'role':'damage.write','iat':int(time.time()),'exp':int(time.time())+600}
    data=encode(b'{"alg":"RS256","typ":"JWT"}')+b'.'+encode(json.dumps(claims).encode())
    signature=subprocess.run(['openssl','dgst','-sha256','-sign',str(key)],input=data,capture_output=True,check=True).stdout
    return (data+b'.'+encode(signature)).decode()
def request(url,method='GET',body=None,headers=None):
    req=Request(url,method=method,data=body,headers=headers or {})
    try:r=urlopen(req,timeout=45)
    except HTTPError as e:r=e
    with r:
        data=r.read()
        try:data=json.loads(data)
        except (ValueError,UnicodeDecodeError):pass
        return r.status,data
headers={'Authorization':'Bearer '+token(),'Content-Type':'application/json'}
def api(path,method='GET',body=None,identity=None,instance=0,org=None):
    h=dict(headers)
    if identity:h['Idempotency-Key']=identity
    if org:h['Authorization']='Bearer '+token(org)
    return request(urls[instance]+path,method,None if body is None else json.dumps(body).encode(),h)
payload={'assetId':'evidence-'+str(uuid.uuid4()),'assetTypeId':'Bridge','damageCategoryId':'Severe','evaluatedAt':'2026-11-18T00:00:00Z','location':'Isolated Storage test','description':'No personal data','evidence':[],'budget':0}
status,evaluation=api('/api/evaluaciones','POST',payload,str(uuid.uuid4()));assert status==201,(status,evaluation)
path='/api/evaluaciones/'+evaluation['evaluationId'];evidence=path+'/evidencias'
objects=[b'<< /Type /Catalog /Pages 2 0 R >>',b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 4 0 R >>',b'<< /Length 0 >>\nstream\n\nendstream']
file=b'%PDF-1.4\n';offsets=[0]
for i,obj in enumerate(objects,1):
    offsets.append(len(file));file+=str(i).encode()+b' 0 obj\n'+obj+b'\nendobj\n'
xref=len(file)
file+=b'xref\n0 5\n0000000000 65535 f \n'+b''.join(f'{offset:010} 00000 n \n'.encode() for offset in offsets[1:])+f'trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode()
meta={'originalFileName':'test.pdf','contentType':'application/pdf','size':len(file),'sha256':hashlib.sha256(file).hexdigest()}
identity=str(uuid.uuid4());status,intent=api(evidence,'POST',meta,identity);assert status==200,status
assert intent['status']=='PendingUpload' and intent['method']=='PUT' and intent['reference'] is None
fileid=intent['evidenceId'];confirmation=evidence+'/'+fileid+'/confirmar'
assert api(evidence,'POST',{**meta,'sha256':'0'*64},identity)[0]==409
assert api(evidence,org='another-org')[0]==404
assert api(evidence+'/'+fileid+'/descarga')[0]==422
assert api(confirmation,'POST')[0]==422
assert api(evidence+'/'+fileid)[1]['status']=='PendingUpload'
status,data=request(intent['uploadUrl'],'PUT',file,intent['headers']);assert status in (200,201),(status,data)
assert request(intent['uploadUrl'],'PUT',file+b'changed',intent['headers'])[0]>=400
with ThreadPoolExecutor(max_workers=2) as pool:
    results=list(pool.map(lambda i:api(confirmation,'POST',instance=i),range(2)))
assert all(s in (200,409) for s,_ in results),results
status,verified=api(confirmation,'POST');assert status==200 and verified['status']=='Verified',(status,verified)
assert verified['sha256']==meta['sha256'] and verified['size']==len(file)
assert api(confirmation,'POST',instance=1)==(200,verified)
assert api(evidence,'POST',meta,identity)[1]['evidenceId']==fileid
status,download=api(evidence+'/'+fileid+'/descarga');assert status==200
assert request(download['url'])[1]==file
if os.environ.get('DAMAGE_TEST_POSTGRES_CONNECTION'):
    import psycopg2
    from psycopg2.extensions import parse_dsn
    dsn=os.environ['DAMAGE_TEST_POSTGRES_CONNECTION'];assert parse_dsn(dsn).get('host') in ('127.0.0.1','localhost')
    with psycopg2.connect(dsn) as database:
        with database.cursor() as query:query.execute("UPDATE assessments.evidence SET status='PendingUpload',confirmedat=NULL WHERE evidenceid=%s",(fileid,))
    assert api(confirmation,'POST')[1]['status']=='Verified'
status,updated=api(path,'PUT',{**payload,'expectedVersion':evaluation['version'],'evidence':[verified['reference']]});assert status==200,(status,updated)
assert updated['evidence']==['attachment:'+fileid]
assert api(path,'PUT',{**payload,'expectedVersion':updated['version'],'evidence':['https://external.invalid/file.pdf']})[0]==422
assert json.loads(api(path+'/historial')[1][-1]['snapshot'])['evidence']==updated['evidence']
status,bad=api(evidence,'POST',{**meta,'sha256':hashlib.sha256(b'not pdf').hexdigest(),'size':7},str(uuid.uuid4()));assert status==200
assert request(bad['uploadUrl'],'PUT',b'not pdf',bad['headers'])[0] in (200,201)
status,rejected=api(evidence+'/'+bad['evidenceId']+'/confirmar','POST');assert status==422 and rejected['status']=='Rejected',(status,rejected)
assert api(evidence,'POST',{**meta,'size':6291457},str(uuid.uuid4()))[0]==413
# The multipart compatibility route uses the same durable intention and confirmation.
boundary='evidence-'+uuid.uuid4().hex
parts=[]
for name,value in [('size',str(len(file))),('sha256',meta['sha256'])]:parts.append(('--'+boundary+'\r\nContent-Disposition: form-data; name="'+name+'"\r\n\r\n'+value+'\r\n').encode())
parts.append(('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="test.pdf"\r\nContent-Type: application/pdf\r\n\r\n').encode()+file+('\r\n--'+boundary+'--\r\n').encode())
status,multipart=request(urls[0]+evidence,'POST',b''.join(parts),{'Authorization':headers['Authorization'],'Content-Type':'multipart/form-data; boundary='+boundary,'Idempotency-Key':str(uuid.uuid4())});assert status==200 and multipart['status']=='Verified',(status,multipart)
# Storage itself enforces the limit, even when a client lies about the body.
status,large=api(evidence,'POST',meta,str(uuid.uuid4()));assert status==200
status,_=request(large['uploadUrl'],'PUT',b'%PDF-'+b'x'*6291452,large['headers']);assert status>=400
assert request(large['uploadUrl'],'PUT',file,large['headers'])[0] in (200,201)
assert api(evidence+'/'+large['evidenceId']+'/confirmar','POST')[1]['status']=='Verified'
# Historical references survive rejection and explicit reuse in a new revision.
current=api(path)[1];history=api(path+'/historial')[1]
claims={'iss':'https://identity.test','aud':'platform','sub':'evidence-test','organization_id':'evidence-test','role':'damage.admin','iat':int(time.time()),'exp':int(time.time())+600}
def encode(value):return base64.urlsafe_b64encode(value).rstrip(b'=')
content=encode(b'{"alg":"RS256","typ":"JWT"}')+b'.'+encode(json.dumps(claims).encode())
signature=subprocess.run(['openssl','dgst','-sha256','-sign',str(key)],input=content,capture_output=True,check=True).stdout
admin=(content+b'.'+encode(signature)).decode()
status,rejected=request(urls[0]+path+'/estado','PUT',json.dumps({'status':'Rejected','expectedVersion':current['version'],'reason':'Revision preservation test'}).encode(),{'Authorization':'Bearer '+admin,'Content-Type':'application/json'});assert status==200
assert api(evidence,'POST',meta,str(uuid.uuid4()))[0]==409
status,revised=api(path+'/revisiones','POST',{**payload,'expectedVersion':rejected['version'],'evidence':[verified['reference']]});assert status==200 and revised['revision']==2
assert api(path+'/historial')[1][:len(history)]==history
assert api(evidence+'/'+fileid)[1]['revision']==1
assert request(api(evidence+'/'+fileid+'/descarga')[1]['url'])[1]==file
print('PASS: durable Valet Key, real PDF upload, no overwrite, concurrent/repeated confirmation, signed download, organization isolation, invalid/oversized files, immutable history references and multipart compatibility')
print(json.dumps({'evaluationId':evaluation['evaluationId'],'evidenceIds':[fileid,bad['evidenceId'],multipart['evidenceId'],large['evidenceId']]}))
