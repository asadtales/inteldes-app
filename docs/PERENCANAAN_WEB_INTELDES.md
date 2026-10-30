# Perencanaan IntelDes Web (inteldes.at-in.site) — sinkron dengan app Android

## Context

IntelDes adalah app Android (Kotlin, Compose, Room) untuk merekam rapat desa, mentranskrip (Deepgram atau Whisper on-device), lalu menyusun notulensi/berita acara lewat Gemini atau Claude. Saat ini semuanya berjalan di HP: kunci API tertanam di APK, dan sinkron antarperangkat memakai Google Drive.

Tujuan pekerjaan berikutnya: versi web di **inteldes.at-in.site** yang (1) punya login, (2) sinkron dua arah dengan HP, (3) bisa mendengarkan rekaman sumber, (4) bisa membuat notulensi dari web, dan (5) memuat semua fitur app Android. Desain web (7 layar) sudah ada di artifact claude.ai `Vmv7g2giSjiVnJTFTAYC8L` (belum saya baca langsung; isinya saya ambil dari ringkasan Anda).

Konsekuensi utama: **backend wajib ada**. Kunci API pindah ke server, pemrosesan AI berjalan di server, dan server menjadi sumber kebenaran data. Pengembangan dilakukan di VPS Anda (bukan di mesin lokal ini), jadi dokumen ini juga berisi paket serah-terima ke VPS.

### Keputusan yang sudah diambil
| Topik | Keputusan |
|---|---|
| Backend | Node.js + TypeScript (Fastify) |
| Deploy | Langsung di host dengan systemd (tanpa Docker) |
| Cakupan | Satu organisasi dulu; skema multi-tenant (`org_id`, peran) dirancang dari awal |
| Google Drive | Diganti server sebagai sumber kebenaran; Drive tetap jadi opsi ekspor/cadangan |

### Koreksi atas fakta yang beredar (hasil baca kode terbaru)
- "Sisa dua pekerjaan Android: pemilih mikrofon dan impor teks" **sudah selesai**: `MicPickerDialog`/`MicSources` dan `TextFileImporter`/`ChatImport` (WhatsApp .txt/.zip, format Android dan iOS) sudah ada.
- Deteksi panggilan WhatsApp **tidak ada** di working tree; hanya ada di riwayat git (commit `c0e6b1e` dan sebelumnya). Anggap fitur ini belum aktif.
- Working tree ≠ APK terbaru: `versionName` 1.1.0 / code 2, padahal ada `inteldes_2.0.apk`. HEAD `9e02933`, banyak berkas staged belum di-commit. **Commit dulu sebelum mulai migrasi** agar ada titik acuan.
- `DRIVE_SETUP.md` sudah usang (masih menjelaskan tata letak berkas lama). Label "Gemini 2.5 Pro" di `Enums.kt` tidak cocok dengan model yang dipakai. "Ekspor otomatis" di Pengaturan hanya placeholder.
- Rekomendasi sebelumnya "tanpa server" gugur karena web membutuhkan server.

---

## 1. Inventaris fitur Android dan padanannya di web

DB Room versi 7 (`IntelDesDatabase.kt`, migrasi 1→7, juga `fallbackToDestructiveMigration`). Entitas: recordings, note_sections, transcript_segments, recording_marks, photos, chat_messages, notulensi_history, categories, templates, knowledge_contexts, context_sources.

| Fitur Android | Web | Fase |
|---|---|---|
| Arsip rapat: cari judul/nama/isi transkrip, filter kategori, status (Belum diproses / Memproses / Notulensi siap) | Sama | 2 |
| Kategori: tambah, hapus (rapat dipindah ke "Lainnya") | Sama; **sinkronkan hapus dan urutan** (sekarang belum) | 2 |
| Rekam rapat (waveform, jeda, penanda, mic, foto, lokasi, tombol volume) | **Hanya Android** (web: lihat penanda). Rekam lewat browser = opsional fase 5 | — |
| Unggah audio m4a/mp3/wav + nama, kategori, template, lokasi | Sama ("Buat notulensi") | 2 |
| Impor teks percakapan (WA .txt/.zip, "Nama: ucapan") | Port parser `ChatImport` ke TypeScript | 3 |
| Proses: PREP, TRANSCRIBE, DIARIZE, SUMMARIZE, LINK, ulang, latar belakang | Job di server + progres via SSE | 2 |
| Transkripsi parsial (pilih rentang waktu) | Sama (parameter `rangeStartSec/EndSec`) | 3 |
| Pemutar audio, ±15 dtk, klik baris transkrip untuk lompat, sorot baris aktif | Sama (HTML audio + Range request) | 2 |
| Penanda (tambah saat putar, hapus) | Sama | 2 |
| Transkrip: edit teks, ganti nama pembicara, daftar perangkat desa | Sama | 3 |
| Notulensi per template; SUSUN ULANG; GANTI template; GANTI agent; riwayat versi | Sama | 2–3 |
| Foto dokumentasi (menit, caption, hapus, tambah kapan saja) | Unggah/caption/hapus | 3 |
| Tanya AI per rapat (chip saran, konteks tambahan) | Sama, jawaban streaming | 3 |
| Konteks pengetahuan (gabung banyak rapat, tanya, "Perbaiki konteks" dengan usulan lalu terapkan) | Sama | 3 |
| Template: 4 bawaan + custom (6 tipe bagian) | Editor bagian (sudah ada di desain) | 3 |
| Potong audio (m4a saja) | Server memakai ffmpeg, simpan sebagai rapat baru | 4 |
| Ekspor .txt, .docx (dengan foto), bagikan audio | Ekspor server-side; **PDF baru** | 4 |
| Pengaturan: agent, engine, ringkas/sedang/detail, bahasa, hemat token, auto-proses, simpan audio | Pengaturan organisasi di server (+ preferensi per pengguna) | 3 |
| Google Drive sync | Diganti server; Drive jadi ekspor opsional | 1 |
| On-device Whisper (whisper.cpp, 1 pembicara) | Tetap Android; HP unggah transkrip dengan `engine=ON_DEVICE`, server hanya menyusun notulensi | 1–2 |

**Celah di desain web** (7 layar: Login, Arsip, Detail, Buat notulensi, Template, Perangkat & sinkron, Pengaturan) yang perlu ditambahkan: layar **Konteks**, **impor teks percakapan**, **riwayat versi notulensi**, **pilih rentang transkripsi**, **potong audio**, dan **manajemen kategori**.

---

## 2. Arsitektur

```
Android (offline-first, Room, WorkManager) ──┐
                                             ├─HTTPS─ Caddy/Nginx ─ API Fastify ─ PostgreSQL
Web SPA (React + Vite) ──────────────────────┘                    │       └ pg-boss (antrean di Postgres)
                                                                  ├ Worker (Deepgram, Gemini, Claude, ffmpeg, DOCX/PDF)
                                                                  └ Penyimpanan blob (disk lokal → S3-compatible)
```

- **API**: Node 22 LTS, TypeScript, Fastify, Zod (validasi), Drizzle ORM, argon2id.
- **Antrean**: `pg-boss` (memakai Postgres, jadi tidak perlu Redis). Dipilih agar komponen di VPS tetap sedikit.
- **Web**: React + Vite SPA, dilayani sebagai berkas statis. Gaya Modernist, aksen `#c4260c`.
- **Realtime**: SSE untuk progres pemrosesan dan notifikasi perubahan sinkron.
- **Blob** (audio, foto, ekspor): antarmuka `StorageAdapter`; mulai dari disk lokal `/var/lib/inteldes/blobs`, pindah ke S3-compatible (Backblaze B2, R2, MinIO) saat disk >70%.
- **Unggah audio besar**: protokol tus (resumable). Audio 4 jam ≈ 170 MB; jangan memakai satu POST besar.
- **Tanpa GPU**: transkripsi tetap Deepgram. Whisper di server tidak direncanakan.

### Autentikasi
- Email + sandi (argon2id). Web: cookie sesi httpOnly, `SameSite=Lax`, token CSRF. Android: access token pendek + refresh token yang diputar, disimpan di EncryptedSharedPreferences (`SecurePrefs`).
- **Masuk web lewat QR**: web menampilkan QR berisi kode pairing sekali pakai; HP yang sudah login memindai dan menyetujui; web menerima sesi.
- **Tambah HP lewat QR**: kebalikannya, web yang sudah login menampilkan QR; HP memindai dan mendapat token perangkat.
- Peran: `admin`, `operator`, `pembaca` (pembaca hanya melihat/mengekspor). Tabel `devices` memungkinkan pencabutan per perangkat.
- Kunci API (Deepgram, Gemini, Claude) disimpan **per organisasi**, dienkripsi AES-GCM dengan kunci master dari `/etc/inteldes/env`; tidak pernah dikirim ke klien.

---

## 3. Model data server (PostgreSQL)

Prinsip: semua baris punya **UUID dibuat klien** (sekarang tabel anak Android memakai id autoincrement lokal, perlu diubah), `updated_at`, `version`, dan `deleted_at` (soft delete). Setiap perubahan menaikkan `server_seq` global per organisasi untuk pengambilan perubahan.

Tabel: `organizations`, `users`, `memberships(role)`, `devices`, `refresh_tokens`, `pair_codes`, `categories`, `recordings`, `transcript_segments`, `note_sections` (payload JSONB, sama dengan `SectionCodec`), `marks`, `photos`, `chat_messages`, `notulensi_revisions` (pengganti `notulensi_history`), `templates`, `knowledge_contexts`, `context_sources`, `blobs(sha256, size, mime)`, `org_settings`, `org_secrets`, `audit_log`, `changes(server_seq, entity, id, op)`.

Kolom `recordings` mengikuti entitas Android: judul, kategori, `createdAt`, `durationSec`, `audioMimeType`, `status` (RAW/WORKING/DONE), `speakerCount`, `engine` (ON_DEVICE/CLOUD), `templateId/Name`, `summary`, `provider`, `notesGeneratedAt`, `notulensiDensity`, `locationName`, `latitude`, `longitude`, `errorMessage` (yang terakhir ini **belum** ikut sinkron di Drive; sertakan). Template bawaan disimpan sebagai data seed server (`builtin_standard`, `builtin_berita_acara`, `builtin_ringkas`, `builtin_tindak_lanjut`) dengan ID yang sama dengan di Android.

---

## 4. Protokol sinkron

Mengganti `SyncEngine` berbasis hash dan Drive. Yang dipertahankan: `SyncWorker` (WorkManager, Wi-Fi only, backoff, notifikasi), `SyncStore`, serta `RoomSyncLocal.snapshot/apply` sebagai dasar pembentuk agregat.

- `POST /v1/auth/login | refresh | logout`, `POST /v1/auth/pair/start | claim | approve`.
- `GET /v1/sync/changes?since=<seq>` → daftar entitas berubah (rekaman, kategori, template, konteks, tombstone penghapusan) + `next_seq`.
- `PUT /v1/recordings/:id` dengan `If-Match: <version>`. Sukses → versi baru. Bentrok → **409** beserta salinan server.
- Bagian anak punya aturan sendiri: **chat dan riwayat notulensi append-only** (digabung berdasar UUID); foto digabung per UUID; transkrip, bagian notulensi, dan penanda dikirim sebagai kumpulan per rapat dengan versi bagian.
- `POST /v1/uploads` (tus) untuk audio dan foto; `GET /v1/blobs/:sha256` memakai URL bertanda tangan masa berlaku pendek. Foto dedupe berdasar hash isi (seperti `Foto-<sha1>` sekarang).
- **Aturan bentrok** (sesuai analisis Anda): versi dengan `updated_at` terbaru menang; yang kalah **disimpan di riwayat revisi**, tidak dibuang. Menghapus = tombstone, dan tombstone dibawa oleh `changes`.
- Status `WORKING` tidak disinkronkan dari HP (sama seperti sekarang). Server menjadi pemilik status saat memproses.
- Klien offline-first: tulis ke Room + outbox; dorong saat ada jaringan; ambil `changes` berkala dan saat SSE memberi sinyal.

### Perubahan Android (DB v8)
- Migrasi `Migration7To8` (**tulis migrasi nyata**, jangan andalkan fallback destruktif): tambah `uuid` pada `recording_marks`, `photos`, `chat_messages`, `notulensi_history`; tambah `updatedAt`, `version`, `deletedAt`, `syncState` pada recordings/categories/templates/contexts; tabel `outbox`.
- Layar login, status sinkron, penautan lewat QR. `SecurePrefs` menyimpan token, bukan kunci AI.
- Selama transisi, mode langsung (HP memanggil AI) tetap ada sebagai cadangan offline/pilot; dihapus setelah fase 4. Kunci tertanam di APK dibuang pada saat itu.
- `DriveClient`/ekspor Drive dipertahankan hanya sebagai ekspor opsional.

---

## 5. Pemrosesan di server (port dari Android)

Port, bukan tulis ulang dari nol. Sumber acuan di repo Android:
- `WhisperCloudClient.kt`: Deepgram `/v1/listen`, model `whisper-large`, `language=id`, `diarize`, `punctuate`, `utterances`, `smart_format`; pembicara "Pembicara N".
- `NotulensiGenerator.kt`, `NotulensiSchema.kt` (`buildTranscriptPrompt`, `notulensiJsonSchema`, `toGeminiSchema`), `GeminiClient.kt` (JSON schema terstruktur, suhu 0.4), `ClaudeClient.kt` (tool `submit_notulensi` dipaksa, `max_tokens` 8192; 16.000 untuk konteks).
- Aturan: AUTO → Gemini bila durasi > 5400 dtk (konteks > 60.000 karakter), selain itu Claude; mode hemat memakai model ringan; kepadatan Ringkas/Sedang/Detail; bahasa Indonesia/Inggris; larangan mengarang nama/PIC/tanggal ("Belum ditentukan"/"Tidak disebutkan").
- Retry: 429 dan `IOException` backoff mulai 30 dtk, maks 5 percobaan; `ApiException` lain gagal langsung. Job unik per rapat.
- Nama model **jangan di-hardcode**; ambil dari konfigurasi server yang diisi dari kode Android saat ini (jangan menyalin nama dari dokumen ini).
- Pipeline: `transcribe` (kecuali engine ON_DEVICE atau impor teks) → simpan transkrip → `summarize` → simpan notulensi + arsip versi lama → `DONE`. Hapus audio bila kebijakan retensi menyuruh.
- Untuk audio panjang, kirim **URL bertanda tangan** ke Deepgram alih-alih mengunggah ulang berkas (Android sekarang tanpa chunking). Verifikasi batas Deepgram saat implementasi.
- Endpoint tambahan: Tanya AI (`ask`), konteks (`askContext`, `reviseContext` dengan batas 40.000 karakter), ekspor.

---

## 6. VPS: spesifikasi dan penyiapan

Server berada di VPS terpisah (belum saya akses), jadi **Fase 0 adalah audit VPS** sebelum apa pun dipasang: OS, RAM/disk, apakah Nginx/Apache sudah memakai port 80/443 untuk situs at-in.site lain, versi Node/Postgres yang ada.

**Spesifikasi**
- Minimum: 2 vCPU, 4 GB RAM, 80 GB SSD. Disarankan: 4 vCPU, 8 GB RAM, 160 GB SSD. Tambah swap 2 GB. Tanpa GPU.
- Ukuran: audio 96 kbps ≈ **43 MB/jam**; foto ≈ 0,3–0,5 MB. Contoh: 40 rapat/bulan × 1,5 jam ≈ 2,6 GB/bulan, sehingga 160 GB cukup bertahun-tahun untuk satu desa. Hitung ulang bila pengguna bertambah.

**Penyiapan (Ubuntu LTS, bila belum ada)**
1. DNS: rekaman A (dan AAAA) `inteldes.at-in.site` → IP VPS. Bila memakai CDN Cloudflare, ingat batas ukuran unggahan 100 MB (sebab itu tus + potongan).
2. Pengguna layanan `inteldes` (tanpa login), direktori `/opt/inteldes`, `/var/lib/inteldes`, `/etc/inteldes/env` (0600, rahasia ada di sini, bukan di git).
3. Paket: Node 22 LTS, PostgreSQL 16, ffmpeg, Caddy (TLS otomatis). Bila Nginx sudah terpakai di VPS, tambahkan server block + certbot, jangan menimpa.
4. systemd: `inteldes-api.service` dan `inteldes-worker.service` (User=inteldes, `Restart=on-failure`, `EnvironmentFile`, pembatasan hardening dasar). Reverse proxy ke `127.0.0.1:3000`; SPA statis di `/var/www/inteldes`.
5. Firewall `ufw` (22, 80, 443), `fail2ban`, `unattended-upgrades`, login SSH kunci saja.
6. Cadangan: `pg_dump` harian + `restic` ke penyimpanan luar server (mis. Backblaze B2); uji pemulihan sekali. Log lewat journald; pemeriksaan uptime + Sentry.
7. Batas unggahan di proxy, `Content-Range` untuk audio, dan header keamanan (CSP, HSTS).

**Privasi (UU PDP)**: rekaman rapat adalah data pribadi. Catat persetujuan peserta, kebijakan retensi audio (menggantikan `keepOriginalAudio`), log audit, URL bertanda tangan pendek, dan hak hapus data.

---

## 7. Roadmap bertahap

| Fase | Isi | Selesai bila |
|---|---|---|
| 0 | Commit kondisi Android; audit VPS; bootstrap (DNS, TLS, Postgres, systemd kosong); repo `inteldes-web` | `https://inteldes.at-in.site/health` hidup |
| 1 | Backend inti: auth, perangkat, rapat, kategori; unggah/unduh blob; `changes` + PUT berversi; Android v8 + login + sinkron | Rekaman dari HP muncul di server, dan sebaliknya |
| 2 | Worker: Deepgram + Gemini/Claude; web: login, arsip, detail, pemutar, unggah + generate | Unggah audio di web → notulensi jadi → muncul di HP |
| 3 | Template, foto, konteks, Tanya AI, impor teks, peran, pengaturan, QR perangkat | Paritas fitur Android |
| 4 | Ekspor DOCX/PDF, potong audio, hardening, cadangan, **cut-over**: buang kunci tertanam | HP hanya lewat server |
| 5 (opsional) | Rekam lewat browser, ekspor otomatis ke grup WhatsApp, multi-desa penuh | — |

Risiko utama: migrasi id anak Android (UUID), bentrok sinkron, biaya API tanpa kuota (beri kuota per organisasi), batas unggahan di belakang proxy, dan kunci API lama yang sudah terbaca di sesi sebelumnya (**ganti semuanya**).

---

## 8. Serah-terima ke VPS

Paket berikut sudah disiapkan di repo `inteldes-app` (tanpa rahasia):
- `docs/PERENCANAAN_WEB_INTELDES.md` (dokumen ini; tabel paritas fitur ada di bagian 1).
- `docs/handoff/api-contract.md` (draf kontrak API), `data-model.sql` (skema Postgres), `sync-protocol.md`, `builtin-templates.json` (4 template bawaan dengan ID asli), `ai-pipeline.md` (peta port prompt, skema, retry dari kode Android).
- `docs/handoff/VPS_BOOTSTRAP.md`: skrip audit read-only dan langkah Fase 0.
- `docs/handoff/CLAUDE.md`: titik awal `CLAUDE.md` untuk repo `inteldes-web`.

Cara memindahkan: buat repo privat `inteldes-web`, commit `docs/`, lalu di VPS `git clone`, pasang Claude Code, dan mulai dari Fase 0. Berkas rahasia (`.env`, `.env.txt`, `real_*.db`, `real_prefs.bin`, APK) **tidak** ikut. Kunci API diisi manual di `/etc/inteldes/env` di VPS. Artifact desain bersifat privat; ekspor HTML-nya (atau bagikan lewat menu Share) dan taruh di `docs/design/`.

## 9. Verifikasi (untuk fase pengembangan)

- Port `SyncEngineTest.kt` menjadi uji kontrak server: salin dua arah, bentrok (409 + revisi), tombstone, rapat `WORKING` dilewati.
- E2E: rekam di HP → muncul di web < 1 menit; edit di web → HP terbarui; hapus di satu sisi → hilang di sisi lain.
- Unggah audio 2 jam lewat web → notulensi per template; uji pemutusan jaringan (tus melanjutkan).
- Uji peran (pembaca tak bisa mengubah), pencabutan perangkat, kuota, dan pemulihan cadangan.
- Playwright untuk alur web utama; pemeriksaan kontras warna `#c4260c`.

## Berkas kritis di repo Android (untuk dibaca saat port)
`data/db/{Entities,Daos,IntelDesDatabase,SectionCodec,NotulensiSnapshot}.kt`, `data/sync/{SyncEngine,RoomSyncLocal,SyncStore,DriveClient}.kt`, `data/work/{ProcessRecordingWorker,SyncWorker}.kt`, `data/network/{WhisperCloudClient,GeminiClient,ClaudeClient,NotulensiGenerator,NotulensiSchema}.kt`, `data/model/{Enums,Domain,BuiltInTemplates}.kt`, `data/prefs/{AppSettingsStore,SecurePrefs}.kt`, `ui/.../NotulensiExporter`, `ChatImport`.
