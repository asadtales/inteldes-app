# Serah terima app 2.5.2 untuk tim web (4 November 2026)

Dari pengembang app (inteldes-app, commit `ede437a`) ke pengembang web. Ini rangkuman **apa yang sudah ada di app**, kontrak server yang dipakainya, dan apa yang perlu dicek di web agar keduanya sinkron. Rinci per fitur ada di `../CATATAN_PERUBAHAN_APP.md`; kerja server yang diminta app ada di `DAFTAR_KERJA_SERVER.md`.

Status pembuktian: semua di bawah punya tes unit (162) dan sebagian diuji di HP asli (Galaxy M51). **Pengiriman foto dan video ke server asli belum diuji dari HP uji** (HP itu mode "hanya menerima"); yang terbukti dengan server asli adalah pembacaan: kartu kunci AI, model AI, uji model, daftar paket.

## 1. Foto: geotag (app ke web dan sebaliknya)

- Sebelum 2.5.0 app mengirim foto **tanpa** bidang geotag, dan `PUT /v1/recordings/{id}/photos` membaca bidang yang tidak ada sebagai `null` lalu **menimpa** geotag di server. **Sudah diperbaiki** (commit `7a46669`): app menyimpan `latitude`, `longitude`, `accuracyM`, `placeName` per foto (Room v10) dan mengirimnya kembali; geotag yang ditambahkan di server turun ke foto yang sudah ada di HP.
- App yang lebih lama dari 2.5.1 masih bisa menghapus geotag di server. Jangan mengandalkan geotag dari web untuk foto yang juga ada di HP yang belum diperbarui.
- Pengambilan foto di app (2.5.2): sakelar **Geotag foto** (bawaan mati). Posisi dicari paling lama 5 detik; posisi terakhir dipakai hanya bila ≤ 60 detik dan akurasi ≤ 50 m. Geotag disimpan, ditulis di EXIF (GPS dan DateTimeOriginal WIB), dan ikut sinkron.
- **Cap pada piksel foto** (sakelar "Tulis geotag pada foto" dan "Pakai logo", bawaan mati): pita di dasar foto berisi logo, waktu WIB, koordinat, akurasi, nama tempat, dan judul rekaman. Foto yang diunggah **adalah foto ber-cap** (server tidak perlu menulis apa pun). Logo sendiri disimpan **per HP** (belum per organisasi: server belum punya tempat penyimpanan logo; lihat bagian 8).

## 2. Video singkat (app ke web)

Kontrak yang dipakai (sesuai `openapi.snapshot.yaml`, migrasi 0010):
1. Unggah berkas lewat tus (`POST /v1/uploads`) dengan `Upload-Length` diumumkan dan `filetype` salah satu dari `video/mp4`, `video/webm`, `video/quicktime`, `video/3gpp`. Potongan 5 MB seperti audio.
2. Daftarkan: `PUT /v1/recordings/{id}/videos` body `{ videos: [VideoInput] }` dengan `id` (UUID dari app), `blobSha256`, `takenAtSec`, `caption` (≤ 1000), `createdAt`, geotag (`latitude`, `longitude`, `accuracyM`, `placeName`), `deletedAt` (kosong).
3. Hapus: video yang dihapus di HP dikirim sebagai `PUT` dengan `deletedAt` terisi (hanya bila server pernah menerimanya).

Perilaku app:
- Batas diperiksa **di HP sebelum disalin**: 100 MB, 120 detik, 20 video per rekaman, jenis MP4/WebM/MOV/3GP. Pesan penolakan muncul sebagai toast.
- Video dikirim **hanya untuk rekaman yang sudah ada di server** (sudah pernah disinkron). Selama belum, statusnya "Menunggu sinkron ke web".
- Penolakan karena videonya sendiri (`413`, atau `422` selain `blob_missing`, mis. `not_video`) dicatat dengan alasannya di HP ("Ditolak server: …") dan **tidak dicoba lagi**. Kegagalan lain (jaringan, `409 blob_missing`) dicoba lagi pada sinkron berikutnya.
- `takenAtSec` dikirim `null`: app belum mengaitkan video dengan menit di audio.
- **App belum menarik video dari server.** Video yang diunggah dari web tidak muncul di app (belum ada). Video belum ikut ekspor Word/PDF/tautan bagikan (sama seperti web).
- Video tidak dikompres di HP. Kamera sistem diminta berhenti otomatis di 2 menit dan sedikit di bawah 100 MB.

## 3. Mengapa foto atau video di app belum muncul di web (daftar periksa)

Kasus dari pemilik: foto dan video ada di app dengan keterangan "Menunggu sinkron ke web", tetapi tidak tampil di web. Penyebab yang mungkin, berurutan dari yang paling sering:
1. **Sinkron ke web mati** di HP itu (Pengaturan > Akun & sinkron web). Bawaannya mati.
2. **Mode "Hanya ambil dari server"** (dipilih saat akun beda): HP tidak mengirim apa pun.
3. **Rekaman dikeluarkan dari sinkron** (Pengaturan > Rekaman yang ikut sinkron).
4. **Rekaman belum ada di server** (belum pernah tersinkron); video menunggu rekamannya dulu. Foto ikut terkirim bersama rekamannya.
5. **Rekaman sedang diproses di HP** (status WORKING): tidak dikirim sampai selesai.
6. **Akun berbeda** dari organisasi yang dikonfirmasi: app berhenti dan menunggu konfirmasi di Pengaturan.
7. **Offline atau sinkron gagal**: pesan terakhir ada di kartu "Sinkron ke web".
8. Penolakan server (hanya video): alasan tampil di baris videonya.

Yang dicek di sisi web: rapat itu ada di arsip akun yang sama; `GET /v1/recordings/{id}` memuat `photos[]` dan `videos[]`; tidak ada galat 415 pada unggahan (jenis tidak diizinkan) di log server.

## 4. Yang sudah ada di app dan memakai kontrak server

| Fitur app | Panggilan server | Catatan |
|---|---|---|
| Ubah judul rekaman | `PUT /v1/recordings/{id}` (`If-Match`) | judul masuk bagian "meta"; HP menang bila bentrok per bagian |
| Ubah nama penanda | `PUT .../marks` dengan UUID yang sama | id penanda dikunci dulu di HP agar tidak jadi penanda kedua |
| Editor potong | `POST .../trim` (mp3/wav, rentang pecahan detik), `GET/PUT/DELETE .../trim-draft` | draf memakai skema `TrimDraftState` persis; deteksi hening dan gelombang dihitung di HP |
| Model AI | `GET/PUT /v1/ai-models`, `POST /v1/ai-models/check`, `GET /v1/settings` | kartu hanya-baca bila kunci IntelDes; OpenRouter hanya lewat server |
| Proses di server | `POST .../process`, polling `GET /v1/recordings/{id}` | pemeriksaan kuota di app sebelum mengunggah audio |
| Tautan bagikan | `POST/GET .../shares`, `DELETE /v1/shares/{id}` | rekaman dikirim dulu bila belum ada |
| Login Google/SSO | `/auth/app/start`, `POST /v1/auth/app/exchange` | PKCE, tujuan konstan `inteldes://auth/callback` |
| Template dan konteks | `PUT/DELETE /v1/templates|contexts/{id}` | edit dan hapus dari HP diteruskan; hapus dari web diabaikan HP |
| Jam | tidak ada | seluruh tampilan app WIB (Asia/Jakarta) |

## 5. Yang app **tidak** lakukan (agar tidak diharapkan)

- Tidak menarik video dari server; tidak mengirim video untuk rekaman yang belum ada di server.
- Tidak mengirim logo organisasi (per HP saja).
- Tidak memakai `GET /waveform` dan `GET /silence` (dihitung di HP); tidak memakai ekspor server (PDF dibuat di HP).
- Tidak menanamkan kunci AI apa pun di APK.

## 6. Yang diminta dari web

1. **Logo organisasi** disimpan di server (per organisasi) bila ingin sama di semua HP: usulan `PUT/GET/DELETE /v1/org/logo` (PNG/JPG/WebP ≤ 1 MB). Sampai ada, app memakai logo per HP.
2. **Tarik video ke app**: app akan memakai `GET /v1/recordings/{id}` → `videos[]` dan unduhan lewat `/v1/blobs/{sha256}/url` bila diinginkan; konfirmasi bahwa video dari web memang ingin muncul di app, dan batas ukuran unduhan lewat data seluler.
3. **`takenAtSec` video**: bila web menampilkan video di garis waktu, app perlu mengisinya (saat ini `null`).
4. **Kunci Claude di server ditolak penyedia** (`POST /v1/ai-models/check` memberi `HTTP 401` untuk Claude, sedangkan Gemini menjawab). Mohon diperiksa; kunci OpenRouter belum terisi.
5. Konfirmasi bahwa `PUT /v1/recordings/{id}/videos` untuk rekaman berstatus RAW (belum diproses) diterima.

## 7. Versi dan cara memeriksa app

App 2.6.0 (build 8: kirim ulang setelah server terhapus; transkrip saja lalu susun notulensi lewat `/transcribe` dan `/regenerate`; menu Dokumentasi lapangan: foto/video + geotag tanpa audio, rekaman `kind` = "Dokumentasi", tanpa audio); layar Pengaturan > Tentang menampilkan versi, waktu build (WIB), dan "Kunci AI: tidak ada di dalam aplikasi". Migrasi database terbaru: v11 (`videos`), dengan `MigrationTest` 7 sampai 11 lolos di HP.
