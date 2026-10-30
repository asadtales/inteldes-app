# Fase 0 — audit dan penyiapan VPS untuk inteldes.at-in.site

Jalankan di **VPS**, bukan di mesin lokal. Mulai dengan audit read-only; jangan memasang apa pun sebelum hasilnya dibaca.

## 1. Audit read-only
Simpan sebagai `audit.sh`, jalankan `bash audit.sh | tee audit.txt`. Tidak mengubah apa pun.

```bash
#!/usr/bin/env bash
echo "== OS =="; cat /etc/os-release | head -4; uname -r
echo "== CPU/RAM/swap =="; nproc; free -h
echo "== Disk =="; df -hT / /var 2>/dev/null; lsblk
echo "== Port yang dipakai =="; ss -tlnp 2>/dev/null | head -40
echo "== Web server =="; for s in nginx apache2 caddy httpd; do systemctl is-active $s 2>/dev/null | sed "s/^/$s: /"; done
echo "== Situs nginx (nama server) =="; grep -rhs "server_name" /etc/nginx/ 2>/dev/null | sort -u
echo "== Versi =="; node -v 2>&1; psql --version 2>&1; ffmpeg -version 2>&1 | head -1; docker --version 2>&1
echo "== Firewall =="; ufw status 2>/dev/null | head -15
echo "== Layanan systemd berjalan =="; systemctl list-units --type=service --state=running --no-pager | head -40
echo "== DNS inteldes.at-in.site =="; getent hosts inteldes.at-in.site; curl -s https://ifconfig.me; echo
echo "== Pengguna =="; getent passwd | awk -F: '$3>=1000{print $1}'
```

Bawa `audit.txt` ke sesi pengembangan. Keputusan yang bergantung padanya:
- Port 80/443 sudah dipakai (nginx/apache)? → pakai server block + certbot, **jangan** pasang Caddy yang berebut port.
- RAM < 4 GB atau disk < 80 GB? → naikkan paket atau tunda Fase 2 (pemrosesan audio).
- Node < 22 atau Postgres < 16? → pasang versi yang diminta dari repositori resmi.

## 2. DNS
Rekaman `A` (dan `AAAA` bila ada IPv6) `inteldes` → IP publik VPS di zona `at-in.site`. Bila zona di Cloudflare: awalnya **DNS only** (abu-abu) agar batas 100 MB unggahan tidak mengganggu; unggahan memakai potongan sehingga proxy oranye juga memungkinkan nanti.

## 3. Pengguna, direktori, rahasia
```bash
sudo adduser --system --group --home /opt/inteldes inteldes
sudo mkdir -p /opt/inteldes /var/lib/inteldes/blobs /var/www/inteldes /etc/inteldes
sudo chown -R inteldes:inteldes /opt/inteldes /var/lib/inteldes
sudo install -m 600 -o root -g inteldes /dev/null /etc/inteldes/env   # isi manual, JANGAN di git
sudo chmod 640 /etc/inteldes/env
```
Isi `/etc/inteldes/env` (contoh nama saja, nilai diisi di VPS):
`DATABASE_URL`, `SESSION_SECRET`, `MASTER_KEY` (32 byte, untuk enkripsi `org_secrets`), `PUBLIC_URL=https://inteldes.at-in.site`, `BLOB_DIR=/var/lib/inteldes/blobs`, `SENTRY_DSN` (opsional).
Kunci Deepgram/Gemini/Claude **tidak** diisi di sini, melainkan lewat `PUT /secrets/:name` oleh admin setelah server hidup. Gunakan kunci **baru**; kunci lama sudah terpapar.

## 4. Paket
Node 22 LTS, PostgreSQL 16, ffmpeg, `ufw`, `fail2ban`, `unattended-upgrades`; tambah swap 2 GB bila RAM ≤ 4 GB. Proxy: Caddy atau Nginx sesuai audit.

Postgres: buat role `inteldes`, database `inteldes`, hanya mendengarkan `127.0.0.1`.

## 5. Layanan systemd (kerangka)
`/etc/systemd/system/inteldes-api.service`
```ini
[Unit]
Description=IntelDes API
After=network.target postgresql.service

[Service]
User=inteldes
Group=inteldes
WorkingDirectory=/opt/inteldes/current
EnvironmentFile=/etc/inteldes/env
ExecStart=/usr/bin/node dist/api/main.js
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=/var/lib/inteldes

[Install]
WantedBy=multi-user.target
```
`inteldes-worker.service` sama, dengan `ExecStart=/usr/bin/node dist/worker/main.js`.

## 6. Reverse proxy (Caddy; bila memakai Nginx, padanannya)
```
inteldes.at-in.site {
  encode zstd gzip
  handle /v1/* { reverse_proxy 127.0.0.1:3000 }
  handle { root * /var/www/inteldes
           try_files {path} /index.html
           file_server }
  header { Strict-Transport-Security "max-age=31536000"; X-Content-Type-Options nosniff; Referrer-Policy no-referrer }
}
```
Tambahkan batas ukuran badan permintaan ≥ 16 MB pada `/v1/uploads*`.

## 7. Keamanan dan cadangan
- `ufw allow 22,80,443/tcp`; SSH hanya kunci publik, `PermitRootLogin no`.
- `pg_dump` harian ke `/var/backups/inteldes`, lalu `restic` ke penyimpanan luar server (Backblaze B2 atau sejenis); uji pemulihan satu kali sebelum Fase 2.
- Pantau: `/health` + uptime monitor, `journalctl -u inteldes-*`, Sentry.

## 8. Kriteria selesai Fase 0
- `curl https://inteldes.at-in.site/health` mengembalikan 200 dengan sertifikat TLS valid.
- Layanan `inteldes-api` aktif dan otomatis hidup ulang setelah `systemctl kill`.
- Database terhubung; migrasi awal dari `data-model.sql` berhasil.
- Cadangan pertama berhasil dipulihkan di database uji.
- Situs lain di `at-in.site` tidak terganggu.
