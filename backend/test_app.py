import os
import tempfile
import unittest
import uuid
from datetime import datetime, timezone
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

temp = tempfile.TemporaryDirectory()
os.environ['FUGUANG_DB'] = temp.name + '/test.sqlite3'
os.environ['FUGUANG_LOCAL'] = '1'
from backend import app as service
from fastapi.testclient import TestClient


class Entitlements(unittest.TestCase):
    def setUp(self):
        with service.db() as c:
            for table in ['campaign_receipts','campaigns','sessions','jobs','codes','users','adminsessions','audit','attempts']:
                c.execute('DELETE FROM '+table)
        self.client=TestClient(service.app)
        self.admin=TestClient(service.app)
        os.environ['FUGUANG_ADMIN_USERNAME']='admin'
        os.environ['FUGUANG_ADMIN_HASH']=service.password_hash('admin-test-password')
        response=self.admin.post('/api/admin/login',json={'username':'admin','password':'admin-test-password'})
        self.csrf={'X-CSRF-Token':response.json()['csrf']}
        r=self.client.post('/api/register',json={'username':'testuser','password':'password123'})
        self.assertEqual(r.status_code,200,r.text)
        self.auth={'Authorization':'Bearer '+r.json()['token']}
        self.uid=r.json()['user']['id']

    def test_admin_requires_matching_username_and_password(self):
        for payload in [
            {'username':'wrong','password':'admin-test-password'},
            {'username':'admin','password':'wrong'},
        ]:
            response=self.client.post('/api/admin/login',json=payload)
            self.assertEqual(response.status_code,401)
            self.assertNotIn('fg_admin',response.cookies)
        self.assertEqual(self.client.post('/api/admin/login',json={'password':'admin-test-password'}).status_code,422)

    def reserve(self):
        job=str(uuid.uuid4())
        return job,self.client.post('/api/generations/reserve',json={'id':job},headers=self.auth)

    def code(self,plan):
        return self.admin.post('/api/admin/codes',json={'plan':plan,'count':1},headers=self.csrf).json()['codes'][0]

    def test_five_successes_and_idempotency(self):
        for i in range(5):
            job,r=self.reserve(); self.assertEqual(r.status_code,200,r.text)
            for _ in range(2): self.assertEqual(self.client.post(f'/api/generations/{job}/commit',json={},headers=self.auth).status_code,200)
        self.assertEqual(self.reserve()[1].status_code,403)
        self.assertEqual(self.client.get('/api/me',headers=self.auth).json()['used'],5)

    def test_failures_release_and_concurrent_cap(self):
        job,_=self.reserve()
        self.client.post(f'/api/generations/{job}/cancel',json={},headers=self.auth)
        with ThreadPoolExecutor(max_workers=8) as pool:
            results=list(pool.map(lambda _:self.reserve(),range(8)))
        self.assertEqual(sum(r.status_code==200 for _,r in results),3)
        self.assertEqual(self.client.get('/api/me',headers=self.auth).json()['remaining'],2)

    def test_week_reset_and_expiry(self):
        job,_=self.reserve(); self.client.post(f'/api/generations/{job}/commit',json={},headers=self.auth)
        with patch.object(service,'week_key',return_value='2099-01-05'):
            self.assertEqual(self.client.get('/api/me',headers=self.auth).json()['remaining'],5)
        with service.db() as c: c.execute('UPDATE users SET expires=? WHERE id=?',(service.now()-1,self.uid))
        self.assertFalse(self.client.get('/api/me',headers=self.auth).json()['member'])

    def test_week_boundary_and_legacy_migration(self):
        before=datetime(2026,10,25,15,59,59,tzinfo=timezone.utc).timestamp()
        self.assertEqual(service.week_key(before),'2026-10-19')
        self.assertEqual(service.week_key(before+1),'2026-10-26')
        with service.db() as c:
            c.execute('DROP INDEX jobs_user_week')
            c.execute('ALTER TABLE jobs DROP COLUMN week')
            c.execute("INSERT INTO jobs VALUES(?,?,?,'done',?,1)",(str(uuid.uuid4()),self.uid,service.month(),service.now()))
        service.initialize()
        self.assertEqual(self.client.get('/api/me',headers=self.auth).json()['used'],1)
        self.assertEqual(self.client.get('/api/me',headers=self.auth).json()['remaining'],4)

    def test_code_single_use_stacking_and_lifetime(self):
        code=self.code('month')
        r=self.client.post('/api/redeem',json={'code':code.lower()},headers=self.auth)
        self.assertTrue(r.json()['member']); expires=r.json()['expires']
        self.assertEqual(self.client.post('/api/redeem',json={'code':code},headers=self.auth).status_code,400)
        r=self.client.post('/api/redeem',json={'code':self.code('year')},headers=self.auth)
        self.assertGreater(r.json()['expires'],expires+360*86400)
        self.client.post('/api/redeem',json={'code':self.code('lifetime')},headers=self.auth)
        self.assertEqual(self.client.post('/api/redeem',json={'code':self.code('month')},headers=self.auth).status_code,409)
        for _ in range(7):
            job,r=self.reserve();self.assertEqual(r.status_code,200)
            self.client.post(f'/api/generations/{job}/commit',json={},headers=self.auth)

    def test_admin_auth_csrf_disable_reset(self):
        self.assertEqual(self.client.get('/api/admin/users',headers=self.auth).status_code,401)
        self.assertEqual(self.admin.post('/api/admin/codes',json={'plan':'month','count':1}).status_code,403)
        self.assertEqual(self.admin.patch('/api/admin/users/'+self.uid,json={'password':'changed123'},headers=self.csrf).status_code,200)
        self.assertEqual(self.client.get('/api/me',headers=self.auth).status_code,401)
        self.admin.patch('/api/admin/users/'+self.uid,json={'disabled':True},headers=self.csrf)
        self.assertEqual(self.client.post('/api/login',json={'username':'testuser','password':'changed123'}).status_code,403)

    def test_ownership_and_cancel_after_commit(self):
        job,_=self.reserve()
        other=self.client.post('/api/register',json={'username':'otheruser','password':'password123'}).json()
        h={'Authorization':'Bearer '+other['token']}
        self.assertEqual(self.client.post(f'/api/generations/{job}/commit',json={},headers=h).status_code,404)
        self.client.post(f'/api/generations/{job}/commit',json={},headers=self.auth)
        self.assertEqual(self.client.post(f'/api/generations/{job}/cancel',json={},headers=self.auth).status_code,409)

    def test_disabled_code_and_validation(self):
        code=self.code('year')
        self.admin.post('/api/admin/codes/'+service.digest(code)+'/disable',json={},headers=self.csrf)
        self.assertEqual(self.client.post('/api/redeem',json={'code':code},headers=self.auth).status_code,400)
        self.assertEqual(self.admin.post('/api/admin/codes',json={'plan':'month','count':101},headers=self.csrf).status_code,422)
        self.assertEqual(self.client.get('/').status_code,200)
        self.assertEqual(self.client.get('/admin/').status_code,200)

    def test_campaign_version_schedule_receipts_and_revision(self):
        config={'title':'新版更新','url':'https://fuguang.startimetable.xyz/#download','target_version':4,'enabled':True}
        r=self.admin.post('/api/admin/campaigns',json=config,headers=self.csrf)
        self.assertEqual(r.status_code,200,r.text)
        cid=r.json()['id']
        self.assertIsNone(self.client.get('/api/campaigns?version=4',headers=self.auth).json()['campaign'])
        self.assertEqual(self.client.get('/api/campaigns?version=3',headers=self.auth).json()['campaign']['id'],cid)
        self.client.post('/api/campaigns/'+cid+'/displayed',json={'revision':1},headers=self.auth)
        self.assertIsNone(self.client.get('/api/campaigns?version=3',headers=self.auth).json()['campaign'])
        self.assertEqual(self.admin.put('/api/admin/campaigns/'+cid,json=config|{'revision':0},headers=self.csrf).status_code,409)
        self.assertEqual(self.admin.post('/api/admin/campaigns',json=config|{'url':'https://example.com'},headers=self.csrf).status_code,422)
        self.assertEqual(self.admin.post('/api/admin/campaigns',json=config|{'starts':service.now()+1000},headers=self.csrf).status_code,200)
        self.assertIsNone(self.client.get('/api/campaigns?version=3',headers=self.auth).json()['campaign'])

    def test_delete_campaign_checks_and_receipt_cleanup(self):
        config={'title':'删除测试','enabled':True,'frequency':'always'}
        cid=self.admin.post('/api/admin/campaigns',json=config,headers=self.csrf).json()['id']
        url='/api/admin/campaigns/'+cid
        self.assertEqual(self.client.request('DELETE',url,json={'revision':1}).status_code,401)
        self.assertEqual(self.admin.request('DELETE',url,json={'revision':1}).status_code,403)
        self.client.post('/api/campaigns/'+cid+'/displayed',json={'revision':1},headers=self.auth)
        self.assertEqual(self.admin.request('DELETE',url,json={'revision':2},headers=self.csrf).status_code,409)
        with service.db() as c:
            self.assertEqual(c.execute('SELECT count(*) FROM campaign_receipts WHERE cid=?',(cid,)).fetchone()[0],1)
        self.assertEqual(self.admin.request('DELETE',url,json={'revision':1},headers=self.csrf).status_code,200)
        self.assertEqual(self.admin.get('/api/admin/campaigns').json(),[])
        self.assertIsNone(self.client.get('/api/campaigns',headers=self.auth).json()['campaign'])
        with service.db() as c:
            self.assertEqual(c.execute('SELECT count(*) FROM campaign_receipts WHERE cid=?',(cid,)).fetchone()[0],0)
            self.assertEqual(c.execute("SELECT count(*) FROM audit WHERE action='delete-campaign' AND target=?",(cid,)).fetchone()[0],1)
        self.assertEqual(self.admin.request('DELETE',url,json={'revision':1},headers=self.csrf).status_code,404)

    def test_reset_campaign_restores_eligibility_without_changing_config(self):
        config={'title':'重置测试','enabled':True,'frequency':'once','target_version':4}
        cid=self.admin.post('/api/admin/campaigns',json=config,headers=self.csrf).json()['id']
        other=self.admin.post('/api/admin/campaigns',json=config|{'enabled':False},headers=self.csrf).json()['id']
        self.client.post('/api/campaigns/'+cid+'/displayed',json={'revision':1},headers=self.auth)
        with service.db() as c:
            original=c.execute('SELECT config FROM campaigns WHERE id=?',(cid,)).fetchone()[0]
            c.execute('INSERT INTO campaign_receipts VALUES(?,?,?,?)',(other,self.uid,'once',service.now()))
        self.assertIsNone(self.client.get('/api/campaigns?version=3',headers=self.auth).json()['campaign'])
        url='/api/admin/campaigns/'+cid+'/reset'
        self.assertEqual(self.client.post(url,json={'revision':1}).status_code,401)
        self.assertEqual(self.admin.post(url,json={'revision':1}).status_code,403)
        self.assertEqual(self.admin.post(url,json={'revision':2},headers=self.csrf).status_code,409)
        self.assertEqual(self.admin.post(url,json={'revision':1},headers=self.csrf).json()['revision'],2)
        campaign=self.client.get('/api/campaigns?version=3',headers=self.auth).json()['campaign']
        self.assertEqual((campaign['id'],campaign['revision']),(cid,2))
        self.assertEqual(self.client.post('/api/campaigns/'+cid+'/displayed',json={'revision':1},headers=self.auth).status_code,409)
        with service.db() as c:
            self.assertEqual(c.execute('SELECT config FROM campaigns WHERE id=?',(cid,)).fetchone()[0],original)
            self.assertEqual(c.execute('SELECT count(*) FROM campaign_receipts WHERE cid=?',(cid,)).fetchone()[0],0)
            self.assertEqual(c.execute('SELECT count(*) FROM campaign_receipts WHERE cid=?',(other,)).fetchone()[0],1)
        self.assertEqual(self.admin.post('/api/admin/campaigns/missing/reset',json={'revision':1},headers=self.csrf).status_code,404)

    def test_apk_download_headers_and_range(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            downloads=root/'static/site/downloads'
            downloads.mkdir(parents=True)
            for filename in ['Fuguang-3D-Album.apk','Fuguang-3D-Album-6-test.apk','Fuguang-3D-Album-7-test.apk']:
                (downloads/filename).write_bytes(b'PK-test-apk')
                with patch.object(service,'ROOT',root):
                    url='/downloads/'+filename
                    head=self.client.head(url)
                    self.assertEqual(head.status_code,200)
                    self.assertEqual(head.headers['content-type'],'application/vnd.android.package-archive')
                    self.assertIn('attachment',head.headers['content-disposition'])
                    self.assertIn(filename,head.headers['content-disposition'])
                    self.assertEqual(head.headers['cache-control'],'no-cache')
                    response=self.client.get(url,headers={'Range':'bytes=0-1'})
                    self.assertEqual(response.status_code,206)
                    self.assertEqual(response.content,b'PK')
                    self.assertEqual(response.headers['content-type'],'application/vnd.android.package-archive')
                    self.assertIn('attachment',response.headers['content-disposition'])
            with patch.object(service,'ROOT',root):
                self.assertEqual(self.client.get('/downloads/Fuguang-3D-Album-999-test.apk').status_code,404)
                self.assertEqual(self.client.get('/downloads/secret.txt').status_code,404)



if __name__=='__main__': unittest.main()
