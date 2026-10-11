#!/usr/bin/env bash
# Idempotent VM bootstrap. Deployment config contains no secret values.
set -euo pipefail
umask 077
exec 9>/run/ogu-bootstrap.lock
flock 9

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq docker.io docker-compose-v2 python3 curl e2fsprogs cron

disk=/dev/disk/by-id/google-ogu-data
for _ in $(seq 1 60); do
  [[ -b "$disk" ]] && break
  sleep 1
done
[[ -b "$disk" ]] || { echo 'Data disk is missing'; exit 1; }

filesystem="$(blkid -s TYPE -o value "$disk" || true)"
if [[ -z "$filesystem" ]]; then
  # Format only a new, blank disk. Partition tables/other signatures are not blank.
  [[ -z "$(wipefs --no-act --noheadings "$disk")" ]] || { echo 'Unknown disk signature; refusing format'; exit 1; }
  mkfs.ext4 -q "$disk"
elif [[ "$filesystem" != ext4 ]]; then
  echo 'Unexpected filesystem; refusing format'
  exit 1
fi
mkdir -p /var/lib/ogu /opt/ogu
uuid="$(blkid -s UUID -o value "$disk")"
if ! grep -qF "UUID=$uuid " /etc/fstab; then
  printf 'UUID=%s /var/lib/ogu ext4 defaults,nofail 0 2\n' "$uuid" >> /etc/fstab
fi
mountpoint -q /var/lib/ogu || mount /var/lib/ogu
# Never run Postgres on an unmounted directory of the boot disk.
mountpoint -q /var/lib/ogu
[[ "$(findmnt -n -o UUID --mountpoint /var/lib/ogu)" == "$uuid" ]] || { echo 'Data disk identity does not match'; exit 1; }
mkdir -p /var/lib/ogu/postgres /var/lib/ogu/caddy-data /var/lib/ogu/caddy-config

if [[ ! -f /swapfile ]]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
fi
swapon --show=NAME --noheadings | grep -q '^/swapfile$' || swapon /swapfile
grep -q '^/swapfile ' /etc/fstab || printf '/swapfile none swap sw 0 0\n' >> /etc/fstab

python3 - <<'PY'
import base64
import pathlib
import urllib.request
root = pathlib.Path('/opt/ogu')
for key, filename in [('compose-base64', 'compose.yaml'), ('caddy-base64', 'Caddyfile'),
                      ('runtime-base64', 'runtime.py'), ('backup-base64', 'backup.py')]:
    url = 'http://metadata.google.internal/computeMetadata/v1/instance/attributes/' + key
    request = urllib.request.Request(url, headers={'Metadata-Flavor': 'Google'})
    with urllib.request.urlopen(request, timeout=10) as response:
        (root / filename).write_bytes(base64.b64decode(response.read()))
    (root / filename).chmod(0o600)
PY

mkdir -p /etc/systemd/system/docker.service.d
cat > /etc/systemd/system/docker.service.d/ogu-data.conf <<'UNIT'
[Unit]
RequiresMountsFor=/var/lib/ogu

[Service]
ExecStartPre=/usr/bin/python3 /opt/ogu/runtime.py --check-data-disk
UNIT

cat > /etc/systemd/system/ogu.service <<'UNIT'
[Unit]
Description=Ogu API deployment
After=docker.service network-online.target
Requires=docker.service
Wants=network-online.target
RequiresMountsFor=/var/lib/ogu

[Service]
Type=oneshot
ExecStart=/usr/bin/python3 /opt/ogu/runtime.py
RemainAfterExit=yes
TimeoutStartSec=600

[Install]
WantedBy=multi-user.target
UNIT

cat > /etc/cron.d/ogu-backup <<'CRON'
# UTC 18:30 = 03:30 KST. Journal receives success/failure, never dump contents.
30 18 * * * root /usr/bin/python3 /opt/ogu/backup.py 2>&1 | /usr/bin/logger -t ogu-backup
CRON
chmod 644 /etc/cron.d/ogu-backup
systemctl daemon-reload
systemctl enable --now docker
systemctl enable --now cron
systemctl enable ogu.service
systemctl restart ogu.service
