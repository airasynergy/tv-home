#!/usr/bin/env bash
# Lift Log installer for an Ubuntu/Debian VPS running nginx.
# Run from the unzipped folder:  sudo bash install.sh
# It only ADDS things (a service, one nginx site file, a certificate). It does not touch your other sites.
set -euo pipefail

DOMAIN="${DOMAIN:-jlog.courieros.tech}"
PORT="${PORT:-3790}"
APP_DIR=/opt/jlog
DATA_DIR=/var/lib/jlog
SVC=jlog

[ "$(id -u)" -eq 0 ] || { echo "Run with sudo: sudo bash install.sh"; exit 1; }
command -v node >/dev/null || { echo "Node.js is not installed. Install Node 18 or newer first."; exit 1; }
NODE_MAJOR=$(node -p 'process.versions.node.split(".")[0]')
[ "$NODE_MAJOR" -ge 18 ] || { echo "Node $NODE_MAJOR found; need 18 or newer."; exit 1; }
if ss -ltn 2>/dev/null | grep -q ":$PORT "; then
  if ! systemctl is-active --quiet "$SVC"; then echo "Port $PORT is in use. Rerun with another port: sudo PORT=3791 bash install.sh"; exit 1; fi
fi

echo "==> Installing app to $APP_DIR (data in $DATA_DIR)"
id -u jlog >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin jlog
mkdir -p "$APP_DIR" "$DATA_DIR"
cp -r server.js public "$APP_DIR"/
chown -R root:root "$APP_DIR"
chown -R jlog:jlog "$DATA_DIR"; chmod 700 "$DATA_DIR"

if [ ! -f "$DATA_DIR/config.json" ]; then
  echo "==> Choose the passcode you'll type to open the app"
  sudo -u jlog env DATA_DIR="$DATA_DIR" node "$APP_DIR/server.js" --set-code
fi

echo "==> Creating service $SVC"
cat > /etc/systemd/system/$SVC.service <<EOF
[Unit]
Description=Lift Log
After=network.target

[Service]
User=jlog
Environment=PORT=$PORT
Environment=HOST=127.0.0.1
Environment=DATA_DIR=$DATA_DIR
ExecStart=$(command -v node) $APP_DIR/server.js
Restart=always
RestartSec=3
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=$DATA_DIR
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now $SVC
systemctl restart $SVC
sleep 1
curl -fsS -o /dev/null "http://127.0.0.1:$PORT/robots.txt" && echo "    App is running on port $PORT"

if command -v nginx >/dev/null; then
  echo "==> Adding nginx site for $DOMAIN"
  SITE=/etc/nginx/sites-available/$DOMAIN
  cat > "$SITE" <<EOF
server {
    listen 80;
    server_name $DOMAIN;
    client_max_body_size 20m;
    location / {
        proxy_pass http://127.0.0.1:$PORT;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
EOF
  ln -sf "$SITE" /etc/nginx/sites-enabled/$DOMAIN
  nginx -t && systemctl reload nginx
  if command -v certbot >/dev/null; then
    echo "==> Getting an HTTPS certificate (the DNS record must already point here)"
    certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos --register-unsafely-without-email --redirect \
      || echo "    Certificate step failed. Check the DNS record, then run: sudo certbot --nginx -d $DOMAIN"
  else
    echo "    certbot not found. Install it (sudo apt install certbot python3-certbot-nginx), then run: sudo certbot --nginx -d $DOMAIN"
  fi
else
  echo "nginx not found. Point your web server at http://127.0.0.1:$PORT for $DOMAIN and pass X-Forwarded-Proto."
fi

echo
echo "Done. Open https://$DOMAIN on your phone and sign in with your passcode."
echo "Change passcode later:  sudo -u jlog env DATA_DIR=$DATA_DIR node $APP_DIR/server.js --set-code && sudo systemctl restart $SVC"
