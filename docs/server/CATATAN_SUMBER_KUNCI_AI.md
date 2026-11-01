# Catatan: sumber kunci AI (kunci IntelDes atau kunci sendiri)

Untuk pengembang Android. Dari sisi web, kunci AI sekarang satu pilihan eksplisit per akun, tidak lagi campur per kunci.

## Kontrak

`GET /v1/settings` memuat:
- `settings.aiKeySource`: `"platform"` (kunci IntelDes, kuota harian) atau `"own"` (kunci yang diisi akun itu di web).
- `aiAccess`: `{ source, platformAvailable, jobs:{used,limit}, asks:{used,limit}, platformLimit:{jobs,asks} }` (24 jam terakhir).
- `ai`: `{deepgram, gemini, claude}` = kesiapan kunci **yang benar-benar dipakai** (sesuai sumber).
`PUT /v1/settings` `{ "aiKeySource": "own"|"platform" }` hanya admin akun; `platform` ditolak 409 `platform_unavailable` bila tidak ada penyedia.
Proses di server (`POST /v1/recordings/{id}/process`, tanya AI) memakai sumber itu dan menjawab `429 quota_exceeded` (pesan menyarankan pindah ke kunci sendiri) bila kuota kunci IntelDes habis.

## Yang perlu dirapikan di app (masalah "kunci dobel")

Sekarang app punya penyimpanan kunci sendiri (`SecurePrefs`, `AppSettingsStore`, layar pengaturan "Kunci API yang Anda ketik...") dan server juga punya kunci, sehingga pengguna bisa mengisi dua kali dan tidak jelas mana yang dipakai. Usulan:
1. Saat app **masuk ke server** dan proses AI dijalankan lewat server, app **tidak meminta kunci**. Tampilkan kartu "Kunci AI" hanya-baca: sumber (`aiKeySource`), kesiapan (`ai`), dan pemakaian 24 jam (`aiAccess`), dengan tautan ke web untuk mengubahnya.
2. Kolom kunci lokal hanya muncul untuk **mode tanpa server** (pemrosesan langsung dari HP seperti dulu), diberi label jelas "dipakai hanya bila tidak masuk ke server".
3. Tangani `429 quota_exceeded` dengan pesan yang sama dari server; jangan mencoba ulang otomatis.
4. Kunci yang tertanam di APK lama (rencana cut-over) tetap harus dibuang dan diganti; jangan dipakai sebagai cadangan diam-diam.
