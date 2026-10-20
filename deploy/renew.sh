#!/bin/sh
set -eu
exec 9>/run/fuguang-certificate.lock
flock -n 9 || exit 0
docker run --rm -v /etc/letsencrypt:/etc/letsencrypt -v /opt/xingke-acme/webroot:/var/www/acme -v /opt/fuguang/acme-work:/var/lib/letsencrypt -v /opt/fuguang/acme-logs:/var/log/letsencrypt certbot/certbot:v5.5.0 renew --cert-name fuguang.startimetable.xyz --non-interactive --no-random-sleep-on-renew
docker exec xingke-v2-ip-test-proxy-1 nginx -t
docker exec xingke-v2-ip-test-proxy-1 nginx -s reload
