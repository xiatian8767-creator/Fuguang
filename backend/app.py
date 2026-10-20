"""Fuguang's independent identity, entitlements and generation ledger."""
import calendar
import json
import hashlib
import hmac
import os
import re
import secrets
import sqlite3
import time
from contextlib import contextmanager
from datetime import datetime, timezone, timedelta
from pathlib import Path
from urllib.parse import urlsplit

from fastapi import FastAPI, HTTPException, Request, Depends
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

DB = os.getenv("FUGUANG_DB", "backend/data/fuguang.sqlite3")
ROOT = Path(__file__).resolve().parent
TZ = timezone(timedelta(hours=8))
app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)


@contextmanager
def db():
    Path(DB).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB, timeout=20)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    try:
        conn.execute("BEGIN IMMEDIATE")
        yield conn
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    finally:
        conn.close()


def initialize():
    with db() as c:
        c.executescript('''
        CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL,
          password TEXT NOT NULL, disabled INTEGER NOT NULL DEFAULT 0,
          expires INTEGER NOT NULL DEFAULT 0, lifetime INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, uid TEXT NOT NULL REFERENCES users(id), expires INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS codes(hash TEXT PRIMARY KEY, hint TEXT, plan TEXT NOT NULL,
          redeemed_by TEXT REFERENCES users(id), redeemed_at INTEGER, disabled INTEGER NOT NULL DEFAULT 0,
          created INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY, uid TEXT NOT NULL REFERENCES users(id),
          month TEXT NOT NULL, state TEXT NOT NULL, created INTEGER NOT NULL, charged INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS adminsessions(token TEXT PRIMARY KEY, csrf TEXT NOT NULL, expires INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY, action TEXT NOT NULL, target TEXT, created INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS attempts(key TEXT PRIMARY KEY, count INTEGER NOT NULL, until INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS campaigns(id TEXT PRIMARY KEY, config TEXT NOT NULL, revision INTEGER NOT NULL, created INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS campaign_receipts(cid TEXT NOT NULL REFERENCES campaigns(id), uid TEXT NOT NULL REFERENCES users(id), period TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY(cid,uid,period));
        CREATE INDEX IF NOT EXISTS jobs_user_month ON jobs(uid,month);
        ''')
        columns={row['name'] for row in c.execute('PRAGMA table_info(jobs)')}
        if 'week' not in columns:
            c.execute('ALTER TABLE jobs ADD COLUMN week TEXT')
        for row in c.execute('SELECT id,created FROM jobs WHERE week IS NULL').fetchall():
            c.execute('UPDATE jobs SET week=? WHERE id=?',(week_key(row['created']),row['id']))
        c.execute('CREATE INDEX IF NOT EXISTS jobs_user_week ON jobs(uid,week)')


def now(): return int(time.time())
def digest(value): return hashlib.sha256(value.encode()).hexdigest()
def password_hash(value):
    salt = secrets.token_hex(16)
    return salt + ":" + hashlib.scrypt(value.encode(), salt=salt.encode(), n=16384, r=8, p=1).hex()


def password_ok(value, stored):
    try:
        salt, expected = stored.split(":")
        actual = hashlib.scrypt(value.encode(), salt=salt.encode(), n=16384, r=8, p=1).hex()
        return hmac.compare_digest(actual, expected)
    except ValueError: return False


def month(): return datetime.now(TZ).strftime("%Y-%m")
def week_key(timestamp=None):
    dt=datetime.fromtimestamp(now() if timestamp is None else timestamp,TZ)
    return (dt-timedelta(days=dt.weekday())).strftime('%Y-%m-%d')
def member(user): return bool(user["lifetime"] or user["expires"] > now())
def limit(request, scope, maximum):
    key = scope + ":" + (request.client.host if request.client else "unknown")
    blocked = False
    with db() as c:
        c.execute("DELETE FROM attempts WHERE until < ?", (now(),))
        row = c.execute("SELECT * FROM attempts WHERE key=?", (key,)).fetchone()
        if row and row["count"] >= maximum: blocked = True
        elif row: c.execute("UPDATE attempts SET count=count+1 WHERE key=?", (key,))
        else: c.execute("INSERT INTO attempts VALUES(?,1,?)", (key, now()+900))
    if blocked: raise HTTPException(429, "操作频繁，请15分钟后重试")


def current(request: Request):
    token = request.headers.get("authorization", "").removeprefix("Bearer ")
    with db() as c:
        row = c.execute("SELECT users.* FROM sessions JOIN users ON users.id=sessions.uid WHERE token=? AND sessions.expires>?", (digest(token),now())).fetchone()
    if not row: raise HTTPException(401, "请先登录")
    if row["disabled"]: raise HTTPException(403, "账号已停用，请联系管理员")
    return dict(row)


def admin(request: Request):
    with db() as c:
        row = c.execute("SELECT * FROM adminsessions WHERE token=? AND expires>?", (digest(request.cookies.get("fg_admin", "")),now())).fetchone()
    if not row: raise HTTPException(401, "请登录管理后台")
    if request.method != "GET" and not hmac.compare_digest(request.headers.get("x-csrf-token", ""),row["csrf"]):
        raise HTTPException(403, "请求校验失败，请重新登录")
    return row


def profile(c, user):
    used = c.execute("SELECT count(*) FROM jobs WHERE uid=? AND week=? AND state='done' AND charged=1", (user["id"],week_key())).fetchone()[0]
    pending = c.execute("SELECT count(*) FROM jobs WHERE uid=? AND week=? AND state='pending' AND charged=1 AND created>?", (user["id"],week_key(),now()-1800)).fetchone()[0]
    return {k: user[k] for k in ("id","username","expires","lifetime","disabled")} | {"member":member(user),"month":month(),"week":week_key(),"used":used,"remaining":max(0,10-used-pending),"limit":10}


class Credentials(BaseModel):
    username: str = Field(min_length=3,max_length=40,pattern=r"^[A-Za-z0-9_@.\-]+$")
    password: str = Field(min_length=8,max_length=128)


@app.middleware("http")
async def headers(request, call_next):
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "same-origin"
    if "/api/" in request.url.path: response.headers["Cache-Control"] = "no-store"
    return response


@app.get("/healthz")
def health(): return {"ok":True,"service":"fuguang","version":"2.1.1"}


@app.post("/api/register")
def register(data: Credentials, request: Request):
    limit(request,"register",10)
    with db() as c:
        try: c.execute("INSERT INTO users(id,username,password,created) VALUES(?,?,?,?)",(secrets.token_hex(12),data.username.lower(),password_hash(data.password),now()))
        except sqlite3.IntegrityError: raise HTTPException(409,"用户名已被使用")
    return login(data,request)


@app.post("/api/login")
def login(data: Credentials, request: Request):
    limit(request,"login",40)
    with db() as c:
        user = c.execute("SELECT * FROM users WHERE username=?", (data.username.lower(),)).fetchone()
        if not password_ok(data.password,user["password"] if user else "0:"+"0"*128): raise HTTPException(401,"用户名或密码不正确")
        if user["disabled"]: raise HTTPException(403,"账号已停用")
        token = secrets.token_urlsafe(32)
        c.execute("DELETE FROM sessions WHERE expires<?",(now(),))
        c.execute("INSERT INTO sessions VALUES(?,?,?)",(digest(token),user["id"],now()+30*86400))
        return {"token":token,"user":profile(c,user)}


@app.get("/api/me")
def me(user=Depends(current)):
    with db() as c: return profile(c,user)


@app.post("/api/logout")
def logout(request: Request, user=Depends(current)):
    with db() as c: c.execute("DELETE FROM sessions WHERE token=?",(digest(request.headers["authorization"].removeprefix("Bearer ")),))
    return {"ok":True}


class Redeem(BaseModel): code: str = Field(min_length=8,max_length=100)


@app.post("/api/redeem")
def redeem(data: Redeem, request: Request, user=Depends(current)):
    limit(request,"redeem",30)
    with db() as c:
        code = c.execute("SELECT * FROM codes WHERE hash=?",(digest(data.code.strip().upper()),)).fetchone()
        if not code or code["disabled"] or code["redeemed_by"]: raise HTTPException(400,"兑换码无效、已停用或已使用")
        user = c.execute("SELECT * FROM users WHERE id=?",(user["id"],)).fetchone()
        if user["lifetime"]: raise HTTPException(409,"你已经是永久会员，无需继续兑换")
        expires = user["expires"]
        if code["plan"] != "lifetime":
            dt = datetime.fromtimestamp(max(now(),expires),TZ)
            n = 1 if code["plan"] == "month" else 12
            y,m = divmod(dt.year*12+dt.month-1+n,12)
            expires = int(dt.replace(year=y,month=m+1,day=min(dt.day,calendar.monthrange(y,m+1)[1])).timestamp())
        c.execute("UPDATE users SET expires=?,lifetime=? WHERE id=?",(expires,int(code["plan"]=="lifetime"),user["id"]))
        c.execute("UPDATE codes SET redeemed_by=?,redeemed_at=? WHERE hash=?",(user["id"],now(),code["hash"]))
        c.execute("INSERT INTO audit(action,target,created) VALUES('redeem',?,?)",(user["id"],now()))
        return profile(c,c.execute("SELECT * FROM users WHERE id=?",(user["id"],)).fetchone())


class Job(BaseModel): id: str = Field(min_length=16,max_length=80,pattern=r"^[a-zA-Z0-9-]+$")


@app.post("/api/generations/reserve")
def reserve(data: Job, user=Depends(current)):
    with db() as c:
        user = c.execute("SELECT * FROM users WHERE id=?",(user["id"],)).fetchone()
        old = c.execute("SELECT * FROM jobs WHERE id=?",(data.id,)).fetchone()
        if old:
            if old["uid"] != user["id"]: raise HTTPException(409,"请求编号已使用")
            if old["state"] == "cancelled" or (old["state"] == "pending" and old["created"] <= now()-1800): raise HTTPException(409,"生成授权已失效，请重新导入")
            return {"id":data.id,"state":old["state"],"uid":user["id"]}
        pending = c.execute("SELECT count(*) FROM jobs WHERE uid=? AND state='pending' AND created>?",(user["id"],now()-1800)).fetchone()[0]
        if pending >= 3: raise HTTPException(429,"已有照片正在生成，请稍后重试")
        if not member(user) and profile(c,user)["remaining"]<=0: raise HTTPException(403,"本周额度已用完")
        c.execute("INSERT INTO jobs(id,uid,month,state,created,charged,week) VALUES(?,?,?,'pending',?,?,?)",(data.id,user["id"],month(),now(),int(not member(user)),week_key()))
        return {"id":data.id,"state":"pending","uid":user["id"]}


@app.post("/api/generations/{job_id}/{action}")
def finish(job_id: str, action: str, user=Depends(current)):
    if action not in ("commit","cancel"): raise HTTPException(404)
    with db() as c:
        job = c.execute("SELECT * FROM jobs WHERE id=? AND uid=?",(job_id,user["id"])).fetchone()
        if not job: raise HTTPException(404,"生成记录不存在")
        target = "done" if action=="commit" else "cancelled"
        if job["state"]==target: return {"ok":True}
        if job["state"]!="pending" or job["created"]<=now()-1800: raise HTTPException(409,"生成授权已失效，请重新导入")
        c.execute("UPDATE jobs SET state=? WHERE id=?",(target,job_id))
    return {"ok":True}


class AdminCredentials(BaseModel): password: str = Field(min_length=1,max_length=128)


@app.post("/api/admin/login")
def admin_login(data: AdminCredentials, request: Request):
    limit(request,"admin-login",10)
    stored = os.getenv("FUGUANG_ADMIN_HASH", "")
    if not stored or not password_ok(data.password,stored): raise HTTPException(401,"管理员密码不正确")
    token, csrf = secrets.token_urlsafe(32),secrets.token_urlsafe(24)
    with db() as c:
        c.execute("DELETE FROM adminsessions WHERE expires<?",(now(),))
        c.execute("INSERT INTO adminsessions VALUES(?,?,?)",(digest(token),csrf,now()+8*3600))
    response = JSONResponse({"csrf":csrf})
    response.set_cookie("fg_admin",token,httponly=True,secure=os.getenv("FUGUANG_LOCAL")!="1",samesite="strict",max_age=8*3600,path="/api/admin")
    return response


@app.get("/api/admin/session")
def admin_session(session=Depends(admin)): return {"csrf":session["csrf"]}


@app.post("/api/admin/logout")
def admin_logout(request: Request, session=Depends(admin)):
    with db() as c: c.execute("DELETE FROM adminsessions WHERE token=?",(session["token"],))
    response=JSONResponse({"ok":True}); response.delete_cookie("fg_admin",path="/api/admin"); return response


@app.get("/api/admin/users")
def users(q: str="", offset: int=0, session=Depends(admin)):
    with db() as c:
        rows=c.execute("SELECT * FROM users WHERE username LIKE ? ORDER BY created DESC LIMIT 100 OFFSET ?",("%"+q[:40]+"%",max(0,offset))).fetchall()
        return [profile(c,u) for u in rows]


class UserUpdate(BaseModel):
    disabled: bool | None = None
    plan: str | None = Field(default=None,pattern="^(free|month|year|lifetime)$")
    password: str | None = Field(default=None,min_length=8,max_length=128)


@app.patch("/api/admin/users/{uid}")
def update_user(uid: str, data: UserUpdate, session=Depends(admin)):
    with db() as c:
        if not c.execute("SELECT 1 FROM users WHERE id=?",(uid,)).fetchone(): raise HTTPException(404,"用户不存在")
        if data.disabled is not None:
            c.execute("UPDATE users SET disabled=? WHERE id=?",(int(data.disabled),uid))
            if data.disabled: c.execute("DELETE FROM sessions WHERE uid=?",(uid,))
        if data.password:
            c.execute("UPDATE users SET password=? WHERE id=?",(password_hash(data.password),uid))
            c.execute("DELETE FROM sessions WHERE uid=?",(uid,))
        if data.plan:
            dt=datetime.now(TZ); n={"month":1,"year":12}.get(data.plan,0)
            y,m=divmod(dt.year*12+dt.month-1+n,12)
            expires=int(dt.replace(year=y,month=m+1,day=min(dt.day,calendar.monthrange(y,m+1)[1])).timestamp()) if n else 0
            c.execute("UPDATE users SET expires=?,lifetime=? WHERE id=?",(expires,int(data.plan=="lifetime"),uid))
        c.execute("INSERT INTO audit(action,target,created) VALUES(?,?,?)",("update-user:"+','.join(data.model_fields_set),uid,now()))
    return {"ok":True}


class CodeBatch(BaseModel):
    plan: str = Field(pattern="^(month|year|lifetime)$")
    count: int = Field(ge=1,le=100)


@app.post("/api/admin/codes")
def create_codes(data: CodeBatch, session=Depends(admin)):
    codes=[]
    with db() as c:
        for _ in range(data.count):
            code="FG-"+secrets.token_hex(12).upper()
            c.execute("INSERT INTO codes(hash,hint,plan,created) VALUES(?,?,?,?)",(digest(code),code[-6:],data.plan,now()))
            codes.append(code)
        c.execute("INSERT INTO audit(action,target,created) VALUES(?,?,?)",("create-codes",data.plan+":"+str(data.count),now()))
    return {"codes":codes,"notice":"完整兑换码仅本次展示，请立即保存"}


@app.get("/api/admin/codes")
def list_codes(offset: int=0, session=Depends(admin)):
    with db() as c: return [dict(r) for r in c.execute("SELECT hash,hint,plan,redeemed_by,redeemed_at,disabled,created FROM codes ORDER BY created DESC,rowid DESC LIMIT 100 OFFSET ?",(max(0,offset),))]


@app.post("/api/admin/codes/{code_hash}/disable")
def disable_code(code_hash: str, session=Depends(admin)):
    with db() as c:
        changed=c.execute("UPDATE codes SET disabled=1 WHERE hash=? AND redeemed_by IS NULL",(code_hash,)).rowcount
        if not changed: raise HTTPException(409,"兑换码已使用或不存在")
        c.execute("INSERT INTO audit(action,target,created) VALUES('disable-code',?,?)",(code_hash,now()))
    return {"ok":True}


@app.get("/api/admin/audit")
def audit(session=Depends(admin)):
    with db() as c: return [dict(r) for r in c.execute("SELECT * FROM audit ORDER BY id DESC LIMIT 100")]


class Campaign(BaseModel):
    title: str = Field(min_length=1,max_length=80)
    body: str = Field(default="",max_length=4000)
    url: str = Field(default="",max_length=2000)
    button: str = Field(default="立即更新",min_length=1,max_length=20)
    mode: str = Field(default="text",pattern="^(text|web)$")
    target_version: int = Field(default=2147483647,ge=1,le=2147483647)
    starts: int = Field(default=0,ge=0)
    ends: int = Field(default=0,ge=0)
    frequency: str = Field(default="once",pattern="^(once|daily|weekly|always)$")
    enabled: bool = False
    audience: str = Field(default="all",pattern="^(all|custom)$")
    user_ids: list[str] = Field(default_factory=list,max_length=2000)
    width: int = Field(default=88,ge=60,le=96)
    height: int = Field(default=75,ge=30,le=90)
    revision: int = Field(default=0,ge=0)


def validate_campaign(data):
    # Only the official HTTPS origin can launch updates or embedded content.
    if data.url:
        u=urlsplit(data.url)
        if u.scheme!='https' or u.hostname!='fuguang.startimetable.xyz' or u.port not in (None,443) or u.username or u.password:
            raise HTTPException(422,"链接必须使用浮光官网 HTTPS 地址")
    if data.mode=='web' and not data.url: raise HTTPException(422,"网页弹窗需要填写官网页面链接")
    if data.audience=='custom' and not data.user_ids: raise HTTPException(422,"请填写测试用户ID")
    if data.ends and data.ends<=data.starts: raise HTTPException(422,"结束时间必须晚于开始时间")


def campaign_period(config):
    dt=datetime.now(TZ)
    if config['frequency']=='once': return 'once'
    if config['frequency']=='weekly': return dt.strftime('%G-%V')
    return dt.strftime('%Y-%m-%d')


def eligible(config,user,version):
    return config['enabled'] and config['starts']<=now() and (not config['ends'] or config['ends']>now()) and version<config['target_version'] and (config['audience']=='all' or user['id'] in config['user_ids'])


@app.get('/api/campaigns')
def get_campaign(version: int=0,user=Depends(current)):
    with db() as c:
        for row in c.execute('SELECT * FROM campaigns ORDER BY created DESC,rowid DESC'):
            config=json.loads(row['config'])
            if not eligible(config,user,version): continue
            if config['frequency']!='always' and c.execute('SELECT 1 FROM campaign_receipts WHERE cid=? AND uid=? AND period=?',(row['id'],user['id'],campaign_period(config))).fetchone(): continue
            return {'campaign': config | {'id':row['id'],'revision':row['revision']}}
    return {'campaign':None}


class DisplayReceipt(BaseModel): revision: int


@app.post('/api/campaigns/{cid}/displayed')
def displayed(cid: str,data: DisplayReceipt,user=Depends(current)):
    with db() as c:
        row=c.execute('SELECT * FROM campaigns WHERE id=?',(cid,)).fetchone()
        if not row: raise HTTPException(404)
        if row['revision']!=data.revision: raise HTTPException(409,"活动已更新")
        config=json.loads(row['config'])
        if not eligible(config,user,0): raise HTTPException(409,"活动已结束")
        c.execute('INSERT OR IGNORE INTO campaign_receipts VALUES(?,?,?,?)',(cid,user['id'],campaign_period(config),now()))
    return {'ok':True}


@app.get('/api/admin/campaigns')
def campaigns(session=Depends(admin)):
    with db() as c: return [json.loads(r['config']) | {'id':r['id'],'revision':r['revision']} for r in c.execute('SELECT * FROM campaigns ORDER BY created DESC,rowid DESC')]


@app.post('/api/admin/campaigns')
def create_campaign(data: Campaign,session=Depends(admin)):
    validate_campaign(data); cid=secrets.token_hex(12)
    with db() as c:
        c.execute('INSERT INTO campaigns VALUES(?,?,1,?)',(cid,json.dumps(data.model_dump()),now()))
        c.execute("INSERT INTO audit(action,target,created) VALUES('create-campaign',?,?)",(cid,now()))
    return {'id':cid,'revision':1}


@app.put('/api/admin/campaigns/{cid}')
def update_campaign(cid: str,data: Campaign,session=Depends(admin)):
    validate_campaign(data)
    with db() as c:
        changed=c.execute('UPDATE campaigns SET config=?,revision=revision+1 WHERE id=? AND revision=?',(json.dumps(data.model_dump()),cid,data.revision)).rowcount
        if not changed: raise HTTPException(409,"配置已被修改，请刷新后重试")
        c.execute("INSERT INTO audit(action,target,created) VALUES('update-campaign',?,?)",(cid,now()))
    return {'ok':True}


@app.api_route('/downloads/Fuguang-3D-Album.apk',methods=['GET','HEAD'])
def download_apk():
    path=ROOT/'static'/'site'/'downloads'/'Fuguang-3D-Album.apk'
    if not path.is_file(): raise HTTPException(404,'安装包正在准备')
    return FileResponse(path,media_type='application/vnd.android.package-archive',filename='Fuguang-3D-Album.apk',headers={'Cache-Control':'no-cache'})


@app.api_route('/downloads/Fuguang-3D-Album-2.1.1-test.apk',methods=['GET','HEAD'])
def download_test_apk():
    path=ROOT/'static'/'site'/'downloads'/'Fuguang-3D-Album-2.1.1-test.apk'
    if not path.is_file(): raise HTTPException(404,'测试包正在准备')
    return FileResponse(path,media_type='application/vnd.android.package-archive',filename=path.name,headers={'Cache-Control':'no-cache'})


initialize()
app.mount("/admin",StaticFiles(directory=ROOT/"static"/"admin",html=True),name="admin")
app.mount("/",StaticFiles(directory=ROOT/"static"/"site",html=True),name="site")
