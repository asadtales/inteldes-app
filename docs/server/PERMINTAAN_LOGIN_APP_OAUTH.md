# Permintaan untuk web: login Google dan portal SSO dari app Android

Dari pengembang app (inteldes-app) ke pengembang web (inteldes-web). Ditulis 1 November 2026.
Status app: **sudah dikerjakan dan teruji unit**; tombolnya tersembunyi sampai server mengaktifkan `appLogin`.

## 1. Prinsip

- **Kredensial OAuth tetap di server.** Client ID, client secret, redirect ke Google/portal, penukaran code→token Google, dan penentuan akun, semuanya sudah ada di `src/auth/google.ts`, `src/auth/sso/*`, `src/http/routes/google.ts`, `sso.ts`. App tidak menyimpan dan tidak mengirim satu pun.
- App hanya membuka halaman server di **browser**, lalu menerima **kode sekali pakai** lewat tautan `inteldes://auth/callback`. Kode itu baru berguna bersama **verifier rahasia** yang hanya dipegang HP yang memulai login (**PKCE**, RFC 7636).
- Hasil akhirnya sama dengan login sandi: token perangkat (`platform: android`) di `POST /v1/auth/login`.

## 2. Alur

```
App                        Browser HP                      Server                          Google / Portal
 |  buat verifier+state       |                              |                                   |
 |  challenge=S256(verifier)  |                              |                                   |
 |-- buka browser ----------->|  GET /auth/app/start?provider=google&challenge=..&state=..       |
 |                            |----------------------------->| simpan {challenge,state} di cookie|
 |                            |<-- 302 /auth/google/login ---|  bertanda tangan (idapp)          |
 |                            |---------------------------------------------------------------->| (alur yang sekarang)
 |                            |<-- 302 /auth/google/callback?code=..&state=.. -------------------|
 |                            |----------------------------->| verifikasi seperti biasa          |
 |                            |                              | akun dikenali dan boleh masuk     |
 |                            |                              | buat kode sekali pakai (2 menit)  |
 |                            |<-- halaman "Buka IntelDes" --| → inteldes://auth/callback?code=..&state=..
 |<-- app terbuka (intent) ---|                              |                                   |
 |  cek state; tampilkan "masuk sebagai ..."                 |                                   |
 |-- POST /v1/auth/app/exchange {code, verifier, deviceName} ->  cek S256(verifier)==challenge, tandai terpakai
 |<-- {user, org, role, deviceId, accessToken, refreshToken} -|                                   |
```

Pengguna di app melihat layar konfirmasi akun (sama dengan "Tambah HP lewat QR") dan menekan LANJUT atau BATAL; BATAL mencabut perangkat yang baru dibuat.

## 3. Yang perlu dikerjakan di server

### 3.1 Konfigurasi
- Env `APP_LOGIN=true` (bawaan **mati**, supaya penerapan aman). Selama mati: `appLogin` bernilai `false` di `/v1/auth/providers`, semua rute di bawah menjawab 404.
- Tidak ada kredensial baru. Google/SSO harus sudah terkonfigurasi (`google`/`sso` bukan null).

### 3.2 `GET /v1/auth/providers`
Tambah field `appLogin: boolean` = `APP_LOGIN && (google !== null || sso !== null)`.
(Rute yang sekarang ada di `src/http/routes/auth.ts`, baris `app.get('/auth/providers' ...)`; app memanggil `/v1/auth/providers`.)

### 3.3 `GET /auth/app/start` (publik, dibuka di browser)
Query: `provider` (`google`|`sso`), `challenge` (`^[A-Za-z0-9_-]{43}$`, S256 base64url tanpa padding), `state` (`^[A-Za-z0-9_-]{16,128}$`).
- Tolak (halaman galat sederhana, bukan redirect ke mana pun) bila provider tidak terkonfigurasi atau parameter tidak valid.
- Set cookie bertanda tangan `idapp` (`signToken('app-login', { challenge, state, provider, exp })`, path `/auth`, 10 menit, `HttpOnly`, `Secure`, `SameSite=Lax`).
- `302` ke `/auth/google/login` atau rute login portal yang sudah ada. **Tidak ada parameter redirect dari klien**; tujuan akhir selalu konstan `inteldes://auth/callback`.

### 3.4 Callback yang sudah ada (`/auth/google/callback`, `/auth/sso/callback`)
Setelah akun lokal ditemukan dan lolos pemeriksaan yang sudah ada (`googleLoginDenied`, organisasi/pengguna nonaktif, domain), **cek cookie `idapp`**:
- Ada dan valid → **jangan** memanggil `startGoogleSession`/`setSessionCookies`. Buat satu baris `app_login_codes`, hapus cookie `idapp`, lalu arahkan ke tautan app (lihat 3.6).
- Tidak ada → perilaku sekarang (cookie sesi web, redirect `/`). Tidak berubah.
- Setiap `loginError(...)` saat `idapp` ada → arahkan ke `inteldes://auth/callback?error=<pesan>&state=<state>` (pesan sudah bahasa Indonesia; app menampilkannya sebagai toast).
- **Akun belum tertaut** (yang sekarang mengarah ke halaman "Tautkan akun Google"): untuk app, jangan buka halaman tautan. Kembalikan `error=` "Akun Google ini belum tertaut ke akun IntelDes. Masuk di web, tautkan di Pengaturan, atau minta undangan admin." Pendaftaran Google (`GOOGLE_SIGNUP=true`) tetap berlaku seperti di web: akun pribadi dibuat, lalu lanjut ke kode.

### 3.5 Tabel
```sql
create table app_login_codes (
  code_hash   text primary key,            -- sha256 hex dari kode; kode asli tidak disimpan
  user_id     uuid not null references users(id),
  org_id      uuid not null references organizations(id),
  challenge   text not null,               -- S256 dari verifier
  provider    text not null,               -- 'google' | 'sso'
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null,        -- now() + 120 detik
  used_at     timestamptz
);
create index on app_login_codes (expires_at);
```
Kode: 32 byte acak, base64url (43 karakter). Pembersihan: hapus baris > 1 hari (di tugas berkala yang sudah ada).

### 3.6 Menyerahkan kode ke app
Jangan hanya `302` ke skema khusus: Chrome Android bisa memblokir pembukaan app dari redirect tanpa gestur. Jawab `200` dengan halaman kecil `noindex`:
- tombol besar **"Buka IntelDes"** → `inteldes://auth/callback?code=<kode>&state=<state>`,
- skrip yang mencobanya otomatis sekali (`location.href`), dan teks cadangan "Jika app tidak terbuka, kembali ke app dan coba lagi".
Header: `Cache-Control: no-store`, `Referrer-Policy: no-referrer`. Jangan mencatat kode di log.

### 3.7 `POST /v1/auth/app/exchange` (publik, batas laju 10/menit/IP)
Body: `{ code, verifier, platform: "android", deviceName }`.
1. Hitung `sha256(code)`; ambil baris yang `used_at is null and expires_at > now()`.
2. Cocokkan `base64url(sha256(verifier)) == challenge` dengan perbandingan waktu-konstan.
3. Tandai terpakai **atomik** (`update ... set used_at = now() where code_hash = $1 and used_at is null returning ...`); bila 0 baris → gagal. Satu kode hanya bisa ditukar sekali, apa pun hasilnya.
4. Periksa lagi pengguna/organisasi tidak dinonaktifkan; buat sesi perangkat seperti login sandi (`createDeviceSession`, `platform: 'android'`, nama = `deviceName`), audit `auth.login.google.app` (atau `.sso.app`).
5. Jawab persis seperti `POST /v1/auth/login` (`user`, `org`, `role`, `deviceId`, `accessToken`, `refreshToken`, `expiresInSec`).
Galat: `400 invalid_code` (tidak ada, kedaluwarsa, terpakai, atau verifier salah; satu pesan yang sama agar tidak bisa dibedakan), `429`.

## 4. Pemeriksaan keamanan (mohon dipenuhi)
- Redirect akhir **hanya** `inteldes://auth/callback` (konstan di server).
- `state` dari app dikembalikan apa adanya; app menolak callback dengan `state` yang bukan miliknya.
- Kode: sekali pakai, 2 menit, disimpan sebagai hash, terikat pada `challenge`.
- Tanpa verifier yang benar, kode yang dicegat (tautan skema khusus bisa dibuka app lain) tidak berguna.
- `idapp` tidak boleh mengubah perilaku login web biasa bila tidak ada.
- Pembatasan laju pada `/auth/app/start` dan `/v1/auth/app/exchange`.

## 5. Pengujian yang disarankan (vitest)
1. `start` menolak provider/challenge/state tidak valid; menyetel cookie `idapp` untuk yang valid.
2. Callback dengan `idapp` membuat kode dan tidak membuat cookie sesi web; tanpa `idapp` perilaku lama.
3. `exchange`: verifier benar → token android; verifier salah → 400 dan kode **hangus**; kode kedaluwarsa → 400; pakai dua kali → 400.
4. Akun belum tertaut → `error=` ke tautan app, bukan halaman tautan.
5. `providers.appLogin` mengikuti `APP_LOGIN` dan keberadaan Google/SSO.
6. Pengguna/organisasi nonaktif ditolak di `exchange`.

## 6. Tambahan OpenAPI
`GET /auth/app/start` (tag Auth, respons 302/200), `POST /v1/auth/app/exchange` (respons = login), field `appLogin` di `AuthProviders`.

## 7. Sisi app (sudah ada, untuk referensi)
- `AppLogin.kt`: PKCE (`Pkce`), pembentuk URL, pembaca callback yang memeriksa `state`, penyimpanan verifier 10 menit.
- `AndroidManifest.xml`: intent-filter `inteldes://auth/callback`.
- `LoginScreen`: tombol "Masuk dengan Google" / "Masuk dengan Portal SSO" hanya bila `providers.external`.
- Alur konfirmasi akun memakai layar Claim yang sama dengan QR.
- Tes: `AppLoginTest` (vektor RFC 7636, penolakan state/skema/kode palsu).
