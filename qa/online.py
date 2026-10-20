"""Exercise production HTTPS routing; writes only a bounded, named acceptance account."""
import json
import secrets
import uuid
from pathlib import Path
import httpx

root=Path(__file__).resolve().parents[1]
base='https://fuguang.startimetable.xyz'
client=httpx.Client(base_url=base,timeout=30)
username='acceptance_'+secrets.token_hex(4)
password=secrets.token_urlsafe(20)
def ok(r):
    if r.status_code!=200: raise RuntimeError(f'{r.request.url.path}: {r.status_code}: {r.text[:200]}')
    return r.json()
r=ok(client.post('/api/register',json={'username':username,'password':password}))
uid=r['user']['id']; auth={'Authorization':'Bearer '+r['token']}
path=root/'.local/online-fixture.json'
path.write_text(json.dumps({'uid':uid,'username':username}))
jobs=[str(uuid.uuid4()) for _ in range(2)]
for job in jobs:ok(client.post('/api/generations/reserve',headers=auth,json={'id':job}))
ok(client.post('/api/generations/'+jobs[0]+'/commit',headers=auth,json={}))
ok(client.post('/api/generations/'+jobs[1]+'/cancel',headers=auth,json={}))
assert ok(client.get('/api/me',headers=auth))['remaining']==4
adminpass=(root/'private/管理员凭据.txt').read_text(encoding='utf8').split('管理员密码：')[1].splitlines()[0]
csrf=ok(client.post('/api/admin/login',json={'password':adminpass}))['csrf']; h={'X-CSRF-Token':csrf}
code=ok(client.post('/api/admin/codes',json={'plan':'month','count':1},headers=h))['codes'][0]
assert ok(client.post('/api/redeem',json={'code':code},headers=auth))['member']
assert client.post('/api/redeem',json={'code':code},headers=auth).status_code==400
config={'title':'联调测试（停用后不可见）','body':'HTTPS端到端验收','url':base+'/updates/2.1.0/','target_version':4,'enabled':True,'audience':'custom','user_ids':[uid]}
campaign=ok(client.post('/api/admin/campaigns',json=config,headers=h))
path.write_text(json.dumps({'uid':uid,'username':username,'campaign':campaign['id']}))
assert ok(client.get('/api/campaigns?version=3',headers=auth))['campaign']['id']==campaign['id']
ok(client.post('/api/campaigns/'+campaign['id']+'/displayed',headers=auth,json={'revision':1}))
assert ok(client.get('/api/campaigns?version=3',headers=auth))['campaign'] is None
ok(client.put('/api/admin/campaigns/'+campaign['id'],headers=h,json=config|{'enabled':False,'revision':1}))
ok(client.patch('/api/admin/users/'+uid,headers=h,json={'disabled':True}))
assert client.get('/api/me',headers=auth).status_code==401
ok(client.post('/api/admin/logout',headers=h,json={}))
print('Production HTTPS checks passed: login, quota commit/cancel, redeem once, campaign receipt, disable and logout.')
