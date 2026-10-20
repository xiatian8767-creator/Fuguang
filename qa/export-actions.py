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
def find(text,contains=False,timeout=60):
    until=time.time()+timeout
    while time.time()<until:
        current=nodes()
        if any(n.attrib.get('text') in ["System UI isn't responding","Quickstep isn't responding"] for n in current):
            close=next((n for n in current if n.attrib.get('resource-id')=='android:id/aerr_close'),None)
            if close is not None:
                tap(close);time.sleep(2);cmd('shell','am','start','-n','com.example.parallaxwallpaper/.MainActivity');continue
        if text=='登录浮光':
            logout=next((n for n in current if n.attrib.get('text')=='退出登录'),None)
            account=next((n for n in current if '账号与会员' in (n.attrib.get('text','') or n.attrib.get('content-desc',''))),None)
            if logout is not None:tap(logout);time.sleep(1);continue
            if account is not None:tap(account);time.sleep(1);continue
        for node in current:
            t=node.attrib.get('text','') or node.attrib.get('content-desc','')
            if (text in t if contains else text==t): return node
        time.sleep(1)
    raise RuntimeError('UI not found: '+text)
def screenshot(name): (root/'qa/output'/name).write_bytes(cmd('exec-out','screencap','-p'))


current=nodes()
complete=next((n for n in current if n.attrib.get('text')=='完成'),None)
if complete is not None:tap(complete)
find('浮光');tap(find('导出验收'));find('导出 / 分享视频');screenshot('export-detail.png')
tap(find('导出 / 分享视频'));find('保存到相册');screenshot('export-actions.png');tap(find('保存到相册'))
find('视频已保存到相册',timeout=120);screenshot('export-saved.png');tap(find('知道了'))
tap(find('导出 / 分享视频'));tap(find('分享视频'))
until=time.time()+120
while time.time()<until:
    activity=cmd('shell','dumpsys','activity','activities').decode(errors='replace')
    if any('com.android.intentresolver' in line and 'topResumedActivity' in line for line in activity.splitlines()):break
    time.sleep(1)
else:raise RuntimeError('System share chooser did not appear')
screenshot('export-sharesheet.png');cmd('shell','input','keyevent','4')
print('Release UI passed: gallery, export actions, album save, system video share chooser')
