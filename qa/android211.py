"""Bounded UI acceptance against the existing TIM emulator and the real HTTPS service."""
import json
import re
import secrets
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path
import httpx

root=Path(__file__).resolve().parents[1]
adb=r'C:\Users\15072\Desktop\TIM\test\toolchain\sdk\platform-tools\adb.exe'
serial='emulator-5554'
def cmd(*args): return subprocess.check_output([adb,'-s',serial,*args],stderr=subprocess.STDOUT,timeout=180)
def nodes():
    cmd('shell','uiautomator','dump','/sdcard/fuguang-ui.xml')
    return list(ET.fromstring(cmd('exec-out','cat','/sdcard/fuguang-ui.xml')).iter('node'))
def tap(node):
    bounds=list(map(int,re.findall(r'\d+',node.attrib['bounds'])))
    cmd('shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2))
def find(text,contains=False,timeout=35):
    until=time.time()+timeout
    while time.time()<until:
        for node in nodes():
            t=node.attrib.get('text','') or node.attrib.get('content-desc','')
            if (text in t if contains else text==t): return node
        time.sleep(1)
    raise RuntimeError('UI not found: '+text)
def screenshot(name): (root/'qa/output'/name).write_bytes(cmd('exec-out','screencap','-p'))
apk=root/'dist/Fuguang-3D-Album-2.1.1-test.apk'
result=cmd('install','--no-streaming','-r',str(apk)).decode(errors='replace')
assert 'Success' in result,result
cmd('shell','pm','clear','com.example.parallaxwallpaper')
cmd('shell','am','start','-n','com.example.parallaxwallpaper/.MainActivity')
find('登录浮光');screenshot('android211-first-login.png')
cmd('shell','input','keyevent','4');find('登录浮光')
assert not any(n.attrib.get('text')=='完成' for n in nodes())
find('还没有账号？注册')
tap(find('还没有账号？注册'))
fields=[n for n in nodes() if n.attrib.get('class')=='android.widget.EditText']
assert len(fields)==2
username='acceptance_android211_'+secrets.token_hex(3)
password='Test'+secrets.token_hex(8)
for index,value in enumerate([username,password]):
    fields=[n for n in nodes() if n.attrib.get('class')=='android.widget.EditText']
    tap(fields[index]);cmd('shell','input','text',value)
    cmd('shell','input','keyevent','111')
    time.sleep(0.4)
tap(find('注册并登录'));find('浮光');screenshot('android211-home.png')
find('本周剩余 5 / 5 次',contains=True)
tap(find('账号与会员',contains=True));find(username);screenshot('android211-account.png');tap(find('完成'))
client=httpx.Client(base_url='https://fuguang.startimetable.xyz',timeout=30)
adminpass=(root/'private/管理员凭据.txt').read_text(encoding='utf8').split('管理员密码：')[1].splitlines()[0]
r=client.post('/api/admin/login',json={'username':(root/'private/管理员凭据.txt').read_text(encoding='utf8').split('管理员账号：')[1].splitlines()[0],'password':adminpass});r.raise_for_status()
headers={'X-CSRF-Token':r.json()['csrf']}
r=client.get('/api/admin/users',params={'q':username});r.raise_for_status();uid=r.json()[0]['id']
fixture={'uid':uid,'username':username}
(root/'.local/android211-fixture.json').write_text(json.dumps(fixture))
config={'title':'浮光更新验收','body':'正式版更新弹窗展示测试','url':'https://fuguang.startimetable.xyz/#download','enabled':True,'target_version':5,'audience':'custom','user_ids':[uid]}
r=client.post('/api/admin/campaigns',json=config,headers=headers);r.raise_for_status();cid=r.json()['id'];fixture['campaign']=cid
(root/'.local/android211-fixture.json').write_text(json.dumps(fixture))
cmd('shell','am','force-stop','com.example.parallaxwallpaper');cmd('shell','am','start','-n','com.example.parallaxwallpaper/.MainActivity')
find('浮光更新验收');screenshot('android211-update-popup.png');tap(find('关闭活动'))
time.sleep(1)
webconfig=config|{'title':'浮光更新网页验收','mode':'web','frequency':'always','url':'https://fuguang.startimetable.xyz/updates/2.1.1/'}
client.put('/api/admin/campaigns/'+cid,json=webconfig|{'revision':1},headers=headers).raise_for_status()
cmd('shell','am','force-stop','com.example.parallaxwallpaper');cmd('shell','am','start','-n','com.example.parallaxwallpaper/.MainActivity')
find('2.1.1 更新');find('每周',contains=True);screenshot('android211-update-web.png');tap(find('关闭活动'))
client.put('/api/admin/campaigns/'+cid,json=webconfig|{'revision':2,'enabled':False},headers=headers).raise_for_status()
client.patch('/api/admin/users/'+uid,json={'plan':'month'},headers=headers).raise_for_status()
tap(find('账号与会员',contains=True));find('会员有效至',contains=True);screenshot('android211-member.png');tap(find('完成'))
client.patch('/api/admin/users/'+uid,json={'disabled':True},headers=headers).raise_for_status()
cmd('shell','am','force-stop','com.example.parallaxwallpaper')
client.post('/api/admin/logout',headers=headers,json={}).raise_for_status()
print('Android release UI passed: install, register, Keystore session restore, version popup, membership refresh.')
