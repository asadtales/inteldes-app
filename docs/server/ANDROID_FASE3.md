# Android: update yang hanya "membungkus" login, tanpa kehilangan rekaman

Untuk pengembang `inteldes-app`. Server (inteldes-web) sudah siap; dokumen ini menjelaskan apa yang harus dijaga di
sisi HP dan kontrak Fase 3 yang baru. Spesifikasi mesin: `openapi.yaml` di samping dokumen ini.

## 1. Prinsip

1. **Memasang versi baru tidak boleh mengubah data.** Rekaman, transkrip, notulensi, foto, template, dan konteks di
   HP tetap seperti sebelumnya sampai pengguna sendiri masuk (login). Tanpa login, app berjalan seperti versi lama.
2. **Login hanya menambah sinkron.** Setelah login, rekaman lokal diunggah ke server; tidak ada yang dihapus di HP.
3. **Server tidak pernah menghapus data HP.** Hapus dari web diabaikan dan dihitung (sudah diterapkan di
   `WebSyncEngine`: `ignoredServerDeletes`). Hanya hapus yang dilakukan pengguna di HP yang dikirim ke server.

## 2. BAHAYA: `fallbackToDestructiveMigration()` di `IntelDesDatabase.kt`

Room sekarang versi 7 dengan `fallbackToDestructiveMigration()`. Itu berarti **setiap kenaikan versi tanpa migrasi
yang lengkap menghapus seluruh database di HP**, termasuk rekaman yang belum pernah disinkronkan. Saat DB v8 dibuat
(UUID untuk anak, `updatedAt`/`version`/`deletedAt`, tabel `outbox`, status sinkron):

- Tulis `MIGRATION_7_8` yang nyata (ALTER TABLE + isi UUID dengan `UUID.randomUUID()` untuk baris yang sudah ada).
- **Hapus `.fallbackToDestructiveMigration()`.** Lebih baik app gagal terbuka dengan galat jelas daripada diam-diam
  menghapus data.
- Setel `exportSchema = true` dan simpan skema v7 di repo agar migrasi bisa diuji dengan `MigrationTestHelper`.
- Uji dengan DB v7 sungguhan berisi rekaman RAW, DONE, penanda, foto, chat, riwayat, template, dan konteks.
- Sebelum menjalankan migrasi, salin berkas database ke penyimpanan app (mis. `databases/intel_des_v7.bak`) dan simpan
  sampai sinkron pertama sukses. `allowBackup` sudah memakai aturan ekstraksi; jangan sertakan sesi login di cadangan.
- Perubahan di commit `2c1a355` (login, pairing, `WebSyncEngine`) belum menyentuh Room, jadi aman; risikonya baru
  muncul saat DB v8 dipasang.

## 3. Sinkron pertama: rekaman yang belum dan sudah dibuat notulensinya

`WebSyncEngine` sudah menerapkan aturannya (kaya mengalahkan miskin; HP menang bila seri; kosong tidak menimpa isi;
`WORKING` dilewati). Urutan yang harus dihasilkan per rekaman supaya **web bisa menyusun notulensinya**:

| Kondisi di HP | Dikirim | Hasil di web |
|---|---|---|
| RAW, ada audio, belum ada transkrip | `PUT /recordings/{id}` (status `RAW`, `engine=CLOUD`) lalu unggah audio (tus), lalu `PUT` ulang dengan `audioBlobSha256` | Tombol "Buat notulensi": server transkrip + susun |
| RAW, transkrip dari HP (`engine=ON_DEVICE`) | metadata + `PUT /transcript` (`If-Match: 0`) | "Buat notulensi" memakai transkrip itu (tanpa transkripsi ulang) |
| DONE | metadata + transkrip + `PUT /notes` + penanda + foto + chat | Semua terlihat; bisa "Susun ulang" |
| WORKING | tidak dikirim | menunggu selesai |

Audio besar: tus potongan 5 MB (batas Cloudflare 100 MB per permintaan). Urutan aman: unggah blob dulu, baru kirim
metadata yang menyebut `audioBlobSha256` (kalau tidak, `422 blob_missing`).

## 4. Kontrak baru Fase 3

- `PUT /v1/recordings/{id}/photos` — body `{photos:[{id, blobSha256, takenAtSec, caption, createdAt, deletedAt?}]}`.
  Unggah berkas foto lewat tus lebih dulu (`filetype` `image/jpeg|png|webp`). Bila blob belum ada → `409 blob_missing`
  dengan `error.details.missing = [sha256…]`: unggah yang kurang lalu kirim ulang. Gabung per UUID; hapus mengalahkan
  edit. `WebIds.forPhoto` sudah menghasilkan id stabil.
- `PUT|DELETE /v1/templates/custom_<uuid>` — template buatan sendiri (`{name, description, sections:[{title,type,instruction}]}`),
  `If-Match` seperti kategori; 409 membawa salinan server. Bawaan tidak bisa diubah.
- `GET|PUT|DELETE /v1/contexts[/{id}]` — konteks dengan `sources` (diganti utuh pada tiap `PUT`), `If-Match` berversi,
  teks maksimal 40.000 karakter.
- `GET /v1/sync/changes` kini juga memuat entitas `template` dan `context` (dengan `version`).
- `GET /v1/recordings/{id}/revisions` — notulensi lama (susun ulang, sunting, sisi yang kalah saat bentrok).
- `POST /v1/import/chat` — `{text, title, kind?, templateId?, templateName?, id?}` mengubah teks percakapan menjadi rapat
  `RAW` tanpa audio (`engine=ON_DEVICE`, parser sama dengan `ChatImport.kt`; cap waktu WhatsApp dibaca WIB). Susun
  notulensinya dengan `POST /recordings/{id}/regenerate`.

### Format `If-Match`
Server menerima `5`, `"5"`, dan `W/"5"` (diuji). Kirim angka versi saja atau berkutip; `0` atau tanpa header hanya untuk
membuat. Nilai lain → `400 bad_if_match`.

## 5. Multi-pengguna

- Satu organisasi, banyak pengguna (admin, operator, pembaca). **Semua anggota organisasi melihat semua rapat**; tidak ada
  kepemilikan per pengguna. Peran dibaca server tiap permintaan: perubahan peran atau penonaktifan berlaku seketika.
- Pembaca tidak boleh menulis (semua `PUT`/`POST` data → 403). UI Android sebaiknya menyembunyikan aksi tulis untuk peran itu
  (`GET /v1/auth/me` memberi `role`).
- Admin menambah pengguna lewat web (tautan undangan sekali pakai) dan menyetujui permintaan akses; HP tidak perlu membuat akun.
- **Ganti akun di HP.** Jika pengguna keluar lalu masuk ke organisasi yang *berbeda*, jangan mengunggah rekaman lokal ke
  organisasi baru tanpa konfirmasi eksplisit (data satu desa bisa tercampur ke desa lain). Keluar tidak menghapus data lokal.
- Perangkat yang dicabut admin menerima 401 dan harus login lagi; data lokal tetap utuh.

## 6. Yang belum ada di server (jangan dipakai dulu)

Tanya AI (`/ask`), tanya/perbaiki konteks (`/contexts/{id}/ask|revise`), transkripsi sebagian rentang waktu
(`rangeStartSec/EndSec`, butuh ffmpeg), ekspor DOCX/PDF, potong audio. Semuanya tetap jalan lokal di app sampai server siap.
