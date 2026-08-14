#!/bin/sh
set -e

# vP1: 无挂载证书时自动生成自签证书，保证 443 可启动；生产挂载正式证书覆盖。
if [ ! -f /etc/nginx/certs/fullchain.pem ] || [ ! -f /etc/nginx/certs/privkey.pem ]; then
    echo "[entrypoint] generating self-signed certificate"
    mkdir -p /etc/nginx/certs
    openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
        -keyout /etc/nginx/certs/privkey.pem \
        -out /etc/nginx/certs/fullchain.pem \
        -subj "/CN=aicasetest.local"
fi

exec nginx -g "daemon off;"
