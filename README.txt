Lift Log (self-hosted) for jlog.courieros.tech

1. DNS (Cloudflare > courieros.tech > DNS > Add record):
     Type: A    Name: jlog    IPv4: your VPS IP    Proxy status: DNS only (grey cloud) for now
   After step 3 finishes, switch it to Proxied (orange cloud). Keep SSL/TLS mode on Full (strict).
2. Copy this folder to the VPS, e.g.  scp -r jlog root@YOUR_VPS_IP:/root/
3. On the VPS:  cd /root/jlog && sudo bash install.sh
   It asks you to choose a passcode, then sets up the service, nginx and HTTPS.
4. Open https://jlog.courieros.tech on your phone, sign in, then Add to Home Screen.

Move your data from the Claude version: in the Claude app go to Plan > Backup > Export backup,
then on jlog.courieros.tech go to Plan > Backup > Import backup and pick that file.

Your data lives in /var/lib/jlog/store.json, with a daily copy kept in /var/lib/jlog/backups (last 30 days).
Other sites on the VPS are not changed. To remove: sudo systemctl disable --now jlog; sudo rm /etc/nginx/sites-enabled/jlog.courieros.tech; sudo systemctl reload nginx
