# Kontrak API IntelDes v1 (DRAF)

Basis: `https://inteldes.at-in.site/v1`. JSON UTF-8. Waktu: epoch milidetik (`createdAt` dst., sama dengan Android) di payload; kolom database memakai `timestamptz`.
Autentikasi: web = cookie sesi httpOnly + header `X-CSRF-Token`; Android = `Authorization: Bearer <access>`. Semua rute (kecuali `/auth/*` dan `/health`) membutuhkan peran. Format galat: `{ "error": { "code": "...", "message": "..." } }`.

Peran: **admin** (semua), **operator** (buat/ubah rapat, template, konteks; tidak mengubah pengguna/kunci/pengaturan), **pembaca** (baca + ekspor).

## Auth dan perangkat
| Metode & rute | Fungsi | Peran |
|---|---|---|
| `POST /auth/login` | email + sandi → sesi/token + daftar organisasi | publik |
| `POST /auth/refresh` | putar refresh token | publik |
| `POST /auth/logout` | cabut perangkat saat ini | semua |
| `POST /auth/pair/start` | `{kind:"web_login"\|"add_phone"}` → kode + payload QR (berlaku 2 menit) | publik / admin,operator |
| `POST /auth/pair/approve` | HP yang login menyetujui kode web | semua |
| `POST /auth/pair/claim` | pemohon menukar kode yang disetujui dengan sesi | publik |
| `GET /devices`, `DELETE /devices/:id` | daftar dan cabut perangkat | admin |
| `GET /audit` | log aktivitas (halaman) | admin |

## Sinkron dan data
| Rute | Fungsi |
|---|---|
| `GET /sync/changes?since=<seq>&limit=` | `{items:[{entity, id, op, version}], next_seq, more}` |
| `GET /recordings?q=&kind=&status=&cursor=` | daftar (cari judul/nama/isi transkrip) |
| `GET /recordings/:id` | agregat penuh: metadata, transkrip, bagian notulensi, penanda, foto, versi per bagian |
| `PUT /recordings/:id` | buat/ubah; header `If-Match: <version>`; 409 berisi salinan server |
| `PUT /recordings/:id/transcript` · `/notes` · `/marks` · `/photos` | per bagian, masing-masing berversi |
| `POST /recordings/:id/chat` | tambah pesan chat (append-only) |
| `DELETE /recordings/:id` | soft delete + tombstone |
| `GET/PUT/DELETE /categories[/:id]` | termasuk urutan |
| `GET/PUT/DELETE /templates[/:id]` | bawaan hanya baca; custom ber-ID `custom_<uuid>` |
| `GET/PUT/DELETE /contexts[/:id]` | konteks pengetahuan + sumber |

## Blob
| Rute | Fungsi |
|---|---|
| `POST /uploads` … (tus) | unggah resumable audio/foto; hasil `sha256` terverifikasi server |
| `GET /blobs/:sha256/url` | URL bertanda tangan (5–15 menit) untuk unduh/putar; mendukung `Range` |

## Pemrosesan dan AI
| Rute | Fungsi |
|---|---|
| `POST /recordings/:id/process` | `{provider?, engine?, density?, templateId?, language?, economy?, rangeStartSec?, rangeEndSec?}` → antre job; 202 |
| `POST /recordings/:id/regenerate` | susun ulang dari transkrip (arsip versi lama ke `notulensi_revisions`) |
| `GET /recordings/:id/events` | SSE progres: PREP, TRANSCRIBE, DIARIZE, SUMMARIZE, LINK, DONE/FAILED |
| `POST /recordings/:id/ask` | Tanya AI, jawaban streaming (SSE); menyimpan ke chat |
| `POST /contexts/:id/ask` · `/revise` | tanya konteks; usulan revisi (batas 40.000 karakter); terapkan lewat `PUT` |
| `POST /import/chat` | unggah `.txt`/`.zip` WhatsApp → rapat tanpa audio (parser port `ChatImport`) |
| `POST /recordings/:id/trim` | `{startSec,endSec}` → rapat baru (ffmpeg) |

## Ekspor
`GET /recordings/:id/export?format=txt|docx|pdf` · `GET /recordings/:id/transcript.txt`

## Pengaturan (admin)
`GET/PUT /settings` (provider, engine, kepadatan, bahasa, hemat token, auto-proses, template bawaan, daftar perangkat desa, retensi audio, kuota) · `PUT /secrets/:name` (deepgram|gemini|claude; hanya tulis, tidak pernah dibaca balik) · `GET/POST/PATCH /users`.

## Ketentuan umum
- Batas ukuran unggahan di proxy ≥ 8 MB per potongan; bukan satu POST audio utuh.
- Rate limit per perangkat dan per organisasi; kuota token AI per organisasi per bulan.
- Semua perubahan menulis `audit_log` dan baris `changes`.
- Draf ini harus dijadikan OpenAPI 3.1 (`openapi.yaml`) di repo `inteldes-web` pada Fase 1 dan dipakai untuk menghasilkan tipe klien web.
