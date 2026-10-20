#!/bin/sh
set -eu
cd /opt/fuguang
stamp=$(date +%Y%m%d-%H%M%S)
# Preserve the current public site and official APK throughout the test release.
tar -czf "releases/site-before-211-$stamp.tar.gz" -C backend/static site
sha256sum downloads/Fuguang-3D-Album.apk > "releases/official-apk-before-211-$stamp.sha256"
docker exec -i fuguang-api-1 python - <<'PY'
import sqlite3,os,time
from pathlib import Path
folder=Path('/data/backups');folder.mkdir(exist_ok=True)
source=sqlite3.connect(os.environ['FUGUANG_DB'])
target=sqlite3.connect(folder/('before-211-'+str(int(time.time()))+'.sqlite3'))
source.backup(target);target.close();source.close()
PY
tar -xzf releases/2.1.1-test.tar.gz
tar -xzf "releases/site-before-211-$stamp.tar.gz" -C backend/static
docker compose build api
docker compose up -d --no-deps api
for attempt in 1 2 3 4 5 6 7 8 9 10; do
    if curl -fsS https://fuguang.startimetable.xyz/healthz; then break; fi
    sleep 2
done
curl -fsS https://fuguang.startimetable.xyz/healthz
sha256sum -c "releases/official-apk-before-211-$stamp.sha256"
