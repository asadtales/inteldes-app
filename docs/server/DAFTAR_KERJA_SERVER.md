# Daftar kerja untuk sisi server (inteldes-web)

Dari pengembang app ke pengembang web. 1 November 2026. Menggabungkan semua yang app sekarang butuhkan atau andalkan dari server, urut prioritas. Yang bertanda **WAJIB** membuat fitur app tidak muncul/berjalan sampai selesai; **PERIKSA** berarti kemungkinan sudah benar tetapi app bergantung padanya.

Dokumen rinci: `PERMINTAAN_LOGIN_APP_OAUTH.md`, `PERMINTAAN_PAKET_KUOTA_SERVER.md`.

## A. WAJIB

### A1. Login Google / portal SSO dari app
Rincian lengkap di `PERMINTAAN_LOGIN_APP_OAUTH.md`. Ringkas:
- Env `APP_LOGIN=true` (bawaan mati); field `appLogin` di `GET /v1/auth/providers`.
- `GET /auth/app/start` (PKCE challenge + state, cookie `idapp`, lalu ke alur Google/SSO yang sudah ada).
- Callback Google/SSO: bila ada `idapp`, buat kode sekali pakai (tabel `app_login_codes`) dan serahkan ke `inteldes://auth/callback` lewat halaman "Buka IntelDes".
- `POST /v1/auth/app/exchange` menukar kode + verifier dengan token perangkat Android.
- Tanpa ini, tombol Google/SSO di app **tidak tampil** (aman).

### A2. Paket dan kuota pemrosesan AI
Rincian di `PERMINTAAN_PAKET_KUOTA_SERVER.md`: tabel `plans`, `organizations.planId`, pencatatan menit audio, pengecekan sebelum mengantre, API dan halaman superadmin, field `plan`/`minutes`/`resetsAt` di `aiAccess`, kode galat `quota_minutes_exceeded`, `quota_jobs_exceeded`, `plan_provider_not_allowed`, `recording_too_long`.
- App sudah memanggil `POST /v1/recordings/{id}/process` untuk HP tanpa kunci. Sampai paket ada, pembatas hanya `AI_DAILY_*` per organisasi yang sekarang.

## B. PERIKSA (app sudah bergantung padanya)

| # | Hal | Mengapa |
|---|---|---|
| B1 | Pekerjaan `process` yang **gagal** mengembalikan rapat ke `status = RAW` dengan `errorMessage` terisi | App menunggu status bukan `WORKING`, lalu menampilkan `errorMessage` ke pengguna |
| B2 | `POST /process` untuk rapat `engine = CLOUD` tanpa transkrip memakai audio di server | App mengubah engine ke CLOUD sebelum mengirim; rapat ON_DEVICE tanpa transkrip akan `nothing_to_process` |
| B3 | Ubah nama penanda di web (kirim ulang UUID yang sama dengan `label` baru) **menaikkan `recording.updatedAt`** | App menarik label baru lewat jalur "anak berubah" yang dipicu `updatedAt` |
| B4 | `PUT /v1/recordings/{id}/marks` dengan UUID yang sama dan label berbeda **menimpa** label (bukan menolak/menggandakan) | Ubah nama penanda dari HP |
| B5 | `PUT /v1/templates/{id}` dan `/v1/contexts/{id}` dengan `If-Match` versi sekarang mengembalikan objek dengan `version` baru; `GET` daftar tidak memuat yang sudah dihapus (soft delete); `DELETE` mengembalikan 204/404 | Sinkron edit dan hapus template/konteks dari HP |
| B6 | Header `Retry-After` (detik) pada `429` dari pembatas laju | App menunggu sampai 30 detik lalu mengulang (maks 2 kali); lebih lama diserahkan ke pekerja latar |
| B7 | `POST /v1/recordings/{id}/shares` mengembalikan `url` **absolut** (`https://inteldes.at-in.site/s/<token>`) | App menangani path relatif juga, tetapi absolut lebih aman untuk disalin |
| B8 | `POST /v1/recordings/{id}/trim` pada rapat yang audionya sudah dihapus retensi → `422 no_audio` dengan pesan jelas | App meneruskan pesan server apa adanya |
| B9 | Jenis audio yang diterima unggahan (`ALLOWED_MIME`): `audio/mp4`, `audio/aac`, `audio/mpeg`, `audio/wav`, `audio/ogg`, `audio/webm` | App sekarang menormalkan nama (`audio/x-m4a`→`audio/mp4`, `audio/mp3`→`audio/mpeg`, `audio/x-wav`→`audio/wav`, parameter `;codecs=` dibuang). Jenis lain (`audio/flac`, `audio/3gpp`) tetap ditolak 415; app menyebutnya |

## C. Disarankan

- C1. Sama dengan `ANDROID_FASE3.md` bagian 4: `GET /v1/sync/changes` memuat `template`/`context` dengan `version` (sudah). Pastikan ubah nama penanda tidak membuat entri `recording` baru dengan `version` sama tanpa `updatedAt` berubah.
- C2. `openapi/openapi.yaml` + `api-types.d.ts` diperbarui untuk semua endpoint baru (A1, A2) agar `docs/server/openapi.snapshot.yaml` di app bisa disalin.
- C3. Tes server untuk setiap butir A dan B di atas.

## D. Saran pembagian commit di repo inteldes-web

1. `feat(auth): login Google/SSO untuk app Android (PKCE, kode sekali pakai)` — A1, termasuk migrasi `app_login_codes`, tes, dan OpenAPI.
2. `feat(ai): paket dan kuota pemrosesan (menit audio, pekerjaan) dengan pengaturan superadmin` — A2, migrasi, API admin, halaman superadmin, `aiAccess`.
3. `fix/test: pemeriksaan B1–B9` — tes yang mengunci perilaku yang diandalkan app (dan perbaikan bila ada yang belum benar).
4. `docs: perbarui openapi dan README` — C2.
Urutan rilis: 1 dan 2 boleh paralel; deploy dengan `APP_LOGIN` mati, aktifkan setelah uji.

## E. Hal yang sengaja TIDAK diminta

- Tidak ada kunci OAuth atau kunci AI yang pindah ke app. App tidak membawa kunci apa pun (build tanpa `EMBEDDED_*`).
- Tidak ada perubahan pada `assetlinks.json`: login app memakai skema khusus + PKCE, bukan App Link.
