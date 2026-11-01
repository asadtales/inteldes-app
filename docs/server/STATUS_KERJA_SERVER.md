# Status pengerjaan daftar kerja server (`DAFTAR_KERJA_SERVER.md`)

Dari pengembang web ke pengembang app, 1 November 2026. Semua butir A dan B sudah dikerjakan dan **ter-deploy di produksi** (`https://inteldes.at-in.site`). Snapshot OpenAPI di `openapi.snapshot.yaml` sudah disamakan dengan web. Server: 287 uji lolos.

## A1. Login Google / portal SSO dari app: SELESAI, **belum dinyalakan**

Sesuai `PERMINTAAN_LOGIN_APP_OAUTH.md`. Dikirim dengan `APP_LOGIN` **mati** (bawaan): `providers.appLogin = false`, `GET /auth/app/start` dan `POST /v1/auth/app/exchange` menjawab 404, jadi tombol di app tetap tersembunyi.

Aktivasi (oleh pemilik, setelah Google dan/atau SSO terkonfigurasi di server): tambahkan `APP_LOGIN=true` ke berkas env produksi lalu restart `inteldes-api`. Google belum terkonfigurasi di produksi (menunggu Client ID/Secret dari pemilik); SSO portal sudah, jadi tombol "Masuk dengan Portal SSO" akan lebih dulu bisa dipakai.

Detail yang perlu diketahui app, termasuk yang spesifik implementasi:
- `GET /auth/app/start`: parameter tidak valid atau penyedia belum diaktifkan = halaman galat HTML 400 tanpa redirect dan tanpa cookie. Valid = cookie `idapp` (HttpOnly, 10 menit, path `/`) + `302` ke `/auth/google/login` atau `/sso/login`. Batas laju 30/menit/IP.
- Serah-terima: halaman HTML 200 dengan tombol **"Buka IntelDes"** dan `<meta http-equiv="refresh">` ke `inteldes://auth/callback?code=<43 karakter>&state=<state>`. **Tidak ada skrip sebaris** (CSP server melarangnya), jadi pembukaan otomatis bergantung pada meta refresh; tombolnya adalah cadangan yang sah, jangan mengandalkan pembukaan otomatis.
- Galat dikembalikan ke app dengan cara yang sama: `inteldes://auth/callback?error=<pesan Indonesia>&state=<state>` (halaman yang sama, tombol "Buka IntelDes"). Mencakup: login dibatalkan, sesi tidak valid, email Google belum terverifikasi, domain tidak diizinkan, akun nonaktif, penyedia tidak mengembalikan akun, dan **akun belum tertaut** ("Akun Google/portal ini belum tertaut ke akun IntelDes. Masuk di web, tautkan di Pengaturan, atau minta undangan admin."). Pada akun belum tertaut server **tidak** membuka halaman penautan.
- Pendaftaran Google terbuka (`GOOGLE_SIGNUP=true`, bawaan mati) tetap berlaku: akun pribadi dibuat lalu app menerima kode seperti biasa.
- `idapp` hanya berlaku untuk penyedia yang dimintanya; cookie milik Google dipakai di callback SSO akan diabaikan (alur web biasa).
- `POST /v1/auth/app/exchange` body `{ code, verifier, platform: "android", deviceName }`. `verifier` harus cocok `^[A-Za-z0-9._~-]{43,128}$` (RFC 7636). Batas laju 10/menit/IP.
  - Sukses `200` = bentuk `POST /v1/auth/login` untuk android: `user`, `org`, `role`, `deviceId`, `accessToken`, `refreshToken`, `expiresInSec`.
  - `400 invalid_code`: kode tidak ada, kedaluwarsa (2 menit), sudah dipakai, atau verifier salah. Kode **hangus pada percobaan pertama** apa pun hasilnya, jadi setelah 400 app harus memulai login dari awal (state dan verifier baru).
  - `403 account_disabled`: pengguna nonaktif atau tidak lagi terdaftar di organisasi itu (kode lain tak berlaku).
  - `404` bila `APP_LOGIN` mati.
- Audit: `auth.login.google.app`, `auth.login.sso.app`. Perangkat tercatat `platform: android`, nama = `deviceName` (default "Android").

## A2. Paket dan kuota pemrosesan AI: SELESAI

Sesuai `PERMINTAAN_PAKET_KUOTA_SERVER.md`, dengan ketentuan implementasi berikut.

**Berlaku hanya untuk kunci IntelDes** (`aiKeySource = platform`). Organisasi dengan kunci sendiri tidak terkena paket; mereka hanya kena pagar anti-penyalahgunaan 24 jam bergulir (`AI_DAILY_JOBS_PER_ORG` dan `AI_DAILY_ASKS_PER_ORG`) dengan kode lama `429 quota_exceeded`.

**Periode** mengikuti kalender WIB (Asia/Jakarta): `day` mulai 00.00, `week` mulai Senin 00.00, `month` mulai tanggal 1 00.00. `resetsAt` = akhir periode berjalan.

**Paket bawaan sistem**: organisasi tanpa paket memakai paket virtual `day` dengan `jobs = AI_DAILY_JOBS_PERSONAL` (5) dan `asks = AI_DAILY_ASKS_PERSONAL` (50), tanpa batas menit dan panjang. Jadi tidak ada perubahan perilaku sebelum superadmin mengatur apa pun. Catatan: periode "hari" kini kalender WIB, bukan 24 jam bergulir.

**`GET /v1/settings` > `aiAccess`** (semua field baru ditambahkan; yang lama tetap):
```json
{ "source": "platform", "platformAvailable": true,
  "plan": { "id": "…" | null, "name": "Paket Dasar" },
  "jobs":    { "used": 3,  "limit": 20 },
  "asks":    { "used": 5,  "limit": 50 },
  "minutes": { "used": 42, "limit": 120 },
  "resetsAt": 1793600000000,
  "platformLimit": { "jobs": 20, "asks": 50, "minutes": 120 } }
```
- `limit: null` = **tanpa batas** (paket tanpa angka itu). Perhatian untuk model Kotlin: jadikan `Int?`; bila memakai `Int` non-null, Gson akan membiarkannya 0 dan layar terbaca "0 dari 0".
- `source: "own"`: `plan = null`, `resetsAt = null`, `minutes.limit = null`; `jobs`/`asks` memakai pagar 24 jam.
- `plan.id = null` berarti paket bawaan sistem.
- `platformLimit` = batas yang akan berlaku bila memakai kunci IntelDes (paket organisasi atau bawaan), untuk ditampilkan sebelum memilihnya.
- `minutes.used` dibulatkan ke atas dari detik audio yang dikirim ke transkripsi (`process`); `regenerate` dan tanya AI tidak memakan menit.

**Pemeriksaan sebelum mengantre** (`POST /v1/recordings/{id}/process` dan `/regenerate`): ditolak seketika, rapat tidak berubah menjadi `WORKING`, dan penolakan tidak dihitung sebagai pemakaian. Urutan: penyedia, panjang rekaman, menit, lalu jumlah pekerjaan. Semua galat membawa `error.details = { limit, used, resetsAt }` (`resetsAt` ms epoch, null bila tidak berlaku) dan pesan Indonesia yang menyebut kapan kuota pulih (WIB):
- `403 plan_provider_not_allowed` (`details.allowedProviders`)
- `422 recording_too_long` (`details.limit` dalam menit)
- `429 quota_minutes_exceeded` (juga bila sisa menit lebih kecil dari durasi rekaman)
- `429 quota_jobs_exceeded`
- Pertanyaan AI (Tanya AI, konteks) yang melewati `asks` tetap `429 quota_exceeded` (kode lama, dipertahankan agar app lama tak rusak).
Semua galat ini sudah dipakai app "apa adanya" (pesan server ditampilkan, tanpa coba ulang otomatis).

**Superadmin** (`/v1/admin/*`, tidak dipakai app): `GET/POST /plans`, `PUT/DELETE /plans/{id}` (hapus ditolak `409 plan_in_use` bila masih dipakai), `PUT /orgs/{id}/plan {planId|null}` (paket nonaktif ditolak `409 plan_inactive`), dan `GET /orgs` memuat `planId` serta `usage`. Halaman web Superadmin punya bagian "Paket kuota".

## B. Pemeriksaan: semuanya sudah benar, kini dikunci uji

| # | Hasil | Uji |
|---|---|---|
| B1 | Pekerjaan gagal mengembalikan rapat ke `RAW` dengan `errorMessage` terisi (hasil sudah ada sebelumnya). | `test/ai.test.ts` |
| B2 | `process` untuk `engine = CLOUD` tanpa transkrip memakai audio di server; ON_DEVICE tanpa transkrip = `422 nothing_to_process`. | `test/ai.test.ts` |
| B3 | Ubah nama penanda menaikkan `recording.updatedAt` dan rapat muncul lagi di `sync/changes` setelah kursor (`next_seq`). | `test/app-contract.test.ts` |
| B4 | `PUT .../marks` dengan UUID sama dan label beda **menimpa** label (tidak menggandakan). | `test/app-contract.test.ts` |
| B5 | `PUT /templates|contexts/{id}` dengan `If-Match` mengembalikan `version` baru; daftar tidak memuat yang dihapus; `DELETE` 204 lalu 404. | `test/app-contract.test.ts` |
| B6 | `429` dari pembatas laju membawa header `Retry-After` (detik, 1 sampai 60). | `test/ratelimit.test.ts` |
| B7 | `url` bagikan absolut dari `PUBLIC_URL` (`https://inteldes.at-in.site/s/<token>`). | `test/app-contract.test.ts` |
| B8 | Potong rapat tanpa audio di server = `422 no_audio`, pesan menyebut audio. | `test/app-contract.test.ts` |
| B9 | Unggahan tus menerima `audio/mp4`, `audio/aac`, `audio/mpeg`, `audio/wav`, `audio/ogg`, `audio/webm` (serta `audio/m4a`, `audio/x-m4a`, `audio/x-wav`); `audio/flac` dan `audio/3gpp` = `415`. | `test/app-contract.test.ts` |

Catatan B9: server **tidak** menerima `audio/mp3` (hanya `audio/mpeg`), jadi normalisasi di app memang diperlukan seperti yang sudah Anda lakukan.

## C. Dokumentasi

- C2 selesai: `openapi/openapi.yaml` dan `api-types.d.ts` memuat semua endpoint baru; snapshot di repo ini sudah disamakan (web `29e402f`).
- C3 selesai: lihat tabel uji di atas serta `test/app-login.test.ts` (16 uji) dan `test/plans.test.ts` (12 uji).

## Yang masih menunggu keputusan pemilik (bukan pekerjaan kode)

1. Kredensial Google (Client ID dan Secret) agar tombol Google aktif; lalu `APP_LOGIN=true`.
2. Menyalakan `APP_LOGIN` sama sekali: dianjurkan setelah `AppLoginTest` dan alur di HP teruji, karena bawaannya mati agar aman.
3. Membuat paket di halaman Superadmin dan memasangnya; sampai itu, semua akun pribadi memakai paket bawaan sistem.
4. Cut-over kunci APK (`CUTOVER_KUNCI_APK.md`): pemilik mengganti dan mencabut kunci lama.
