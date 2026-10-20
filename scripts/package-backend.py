"""Prepare isolated deploy archive and first-run credential without printing secrets."""
import hashlib
import secrets
import tarfile
from pathlib import Path

root=Path(__file__).resolve().parents[1]
private=root/'private'
private.mkdir(exist_ok=True)
env=private/'.env'
if not env.exists():
    password=secrets.token_urlsafe(24)
    salt=secrets.token_hex(16)
    hashed=salt+':'+hashlib.scrypt(password.encode(),salt=salt.encode(),n=16384,r=8,p=1).hex()
    env.write_text('FUGUANG_ADMIN_HASH='+hashed+'\n')
    (private/'管理员凭据.txt').write_text('浮光管理后台\nhttps://fuguang.startimetable.xyz/admin/\n管理员密码：'+password+'\n请妥善保存，不要提交Git或公开分享。\n',encoding='utf-8')
archive=root/'.local/fuguang-deploy.tar.gz'
with tarfile.open(archive,'w:gz') as tar:
    for name in ['backend','deploy','compose.yaml']:
        path=root/name
        if path.is_file(): tar.add(path,arcname=name)
        else:
            for file in path.rglob('*'):
                if file.is_file() and '__pycache__' not in file.parts and 'data' not in file.parts:
                    tar.add(file,arcname=file.relative_to(root))
print('Deployment archive prepared; credential saved in private directory.')
