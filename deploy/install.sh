#!/bin/sh
set -eu
cd /opt/fuguang
docker compose build api
docker compose up -d api
for attempt in 1 2 3 4 5 6 7 8 9 10; do
    curl -fsS http://127.0.0.1:18081/healthz && break
    sleep 2
done
curl -fsS http://127.0.0.1:18081/healthz >/dev/null
stamp=$(date +%Y%m%d-%H%M%S)
mkdir -p /opt/fuguang/edge-backups
cp -p /opt/xingke-v2/backend/acme-http-nginx.conf "/opt/fuguang/edge-backups/http-$stamp.conf"
cp -p /opt/xingke-v2/compose.v2.ip-test.yaml "/opt/fuguang/edge-backups/https-compose-$stamp.yaml"
# Validate the combined config in a disposable container before replacing the edge.
docker compose --project-directory /opt/xingke-v2 -f /opt/xingke-v2/compose.v2.yaml -f /opt/xingke-v2/compose.v2.ip-test.yaml -f /opt/fuguang/deploy/edge.override.yaml run --rm --no-deps ip-test-proxy nginx -t
docker compose --project-directory /opt/xingke-v2 -f /opt/xingke-v2/compose.v2.yaml -f /opt/xingke-v2/compose.v2.ip-test.yaml -f /opt/fuguang/deploy/edge.override.yaml up -d --no-deps ip-test-proxy
docker exec xingke-v2-ip-test-proxy-1 nginx -t
if ! grep -q 'server_name fuguang.startimetable.xyz' /opt/xingke-v2/backend/acme-http-nginx.conf; then
    cat /opt/fuguang/deploy/fuguang.nginx.conf >> /opt/xingke-v2/backend/acme-http-nginx.conf
fi
if ! docker exec xingke-download nginx -t; then
    cat "/opt/fuguang/edge-backups/http-$stamp.conf" > /opt/xingke-v2/backend/acme-http-nginx.conf
    exit 1
fi
docker exec xingke-download nginx -s reload
install -m 0644 deploy/fuguang-certificate.service /etc/systemd/system/fuguang-certificate.service
install -m 0644 deploy/fuguang-certificate.timer /etc/systemd/system/fuguang-certificate.timer
systemctl daemon-reload
systemctl enable --now fuguang-certificate.timer
curl -fsS https://fuguang.startimetable.xyz/healthz
printf '\nStar Orbit HTTP status: '
curl -fsS -o /dev/null -w '%{http_code}\n' http://startimetable.xyz/
printf 'Star Orbit admin status: '
curl -fsS -o /dev/null -w '%{http_code}\n' https://8.154.43.154:8443/admin/
