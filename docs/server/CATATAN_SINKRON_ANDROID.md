# Catatan sinkron web untuk pengembang Android

Hasil analisis kode `inteldes-app` (sampai commit `41c15d3`) terhadap server `inteldes-web`. Tiap butir punya status:
**[DIKERJAKAN]** sudah ada perubahan di commit ini dan **perlu Anda uji**, **[SERVER]** sudah diperbaiki di server,
**[TODO]** perlu dikerjakan di Android.

> Perubahan Kotlin di commit ini **belum dikompilasi dan belum diuji** (tidak ada toolchain Android di server). Anda yang
> menjalankan uji unit dan mencobanya di HP. Perintah dan daftar periksa ada di bagian "Cara menguji".

## Yang sudah cocok dengan server (tidak perlu diubah)

Migrasi v7→v8 aditif dan sama dengan `8.json`; tanpa fallback destruktif; salinan `inteldes_v<n>.bak`; urutan kirim audio → metadata
→ transkrip → notulensi → penanda → chat → foto; nama enum (`status`, `engine`, `provider`, `notulensiDensity`, tipe bagian); ID turunan
UUID untuk segmen/penanda/foto/chat; ID template `custom_<uuid>` dan ID konteks UUID; `If-Match` berkutip (`"3"`, server menerima
`3`, `"3"`, `W/"3"`); lokasi tus relatif; konfirmasi bila ganti organisasi; peran pembaca tidak mengirim.

## 1. [DIKERJAKAN] Penanda, foto, dan chat dari web tidak sampai ke HP

**Masalah.** `pullRecording` memutuskan "tidak ada yang baru" bila `serverVersion`, `transcriptVersion`, dan `notesVersion` sama dengan
yang tersimpan. Server **tidak** menaikkan satu pun versi itu saat penanda, foto, atau chat berubah (hanya `updatedAt`, terbukti lewat
uji di server). Akibatnya, untuk rapat yang sudah pernah sinkron, penanda/foto yang ditambah di web dan jawaban Tanya AI dari web tidak
pernah muncul di HP sampai metadata/transkrip/notulensi berubah.

**Perbaikan di commit ini.**
- `WebState.serverUpdatedAt` (kolom baru `web_sync_state.serverUpdatedAt`, **`MIGRATION_8_9`**, DB versi 9, default 0).
- `WebSyncEngine.pullRecording`: bila versi sama tetapi `updatedAt` server berbeda → `refreshChildren`, yang mengunduh foto yang
  belum ada, memanggil `WebLocal.applyChildren` (hanya penanda/chat/foto, tidak menyentuh metadata, transkrip, notulensi), lalu menyimpan
  `updatedAt` baru. Bila ada foto yang gagal diunduh atau rekaman sedang `WORKING`, `updatedAt` tidak disimpan dan rapat itu masuk
  antrean ulang (`addRetry`).
- Hash anak (`marksHash`, `chatHash`, `photosHash`) hanya digeser maju bila HP tidak punya penanda/chat/foto yang belum terkirim; kalau
  punya, tetap "kotor" dan dorongan berikutnya mengirim gabungannya (server menggabung per id).
- `RoomWebLocal.applyChildren` + `mergeChildren` (dipakai juga oleh `applyServer`, perilakunya sama).
- Setelah HP sendiri mendorong penanda/chat/foto, `updatedAt` server naik tanpa HP tahu nilainya, jadi satu kali sinkron berikutnya
  menarik ulang rapat itu (sekali, tanpa efek). Itu disengaja agar tidak ada celah balapan.
- `backUpBeforeMigration` kini berlaku untuk file versi lama < 9 (jadi ada `inteldes_v8.bak` bila pengguna sudah di v8).

**Yang harus Anda lakukan.**
1. Jalankan uji unit (lihat bawah). Empat uji baru di `WebSyncEngineTest` mencakup: penanda+chat dari web sampai ke HP tanpa menyentuh
   transkrip, refresh hanya sekali, rapat `WORKING` ditunda lalu menyusul, penanda lokal yang belum terkirim tetap terkirim.
2. **Bangun proyek lalu commit `app/schemas/.../9.json`** yang dibuat Room (saya tidak bisa membuatnya). Tanpa berkas itu, uji migrasi
   tidak bisa jalan.
3. Bila v8 belum pernah terpasang di HP mana pun, `MIGRATION_8_9` boleh dilebur ke `MIGRATION_7_8` (versi tetap 8, `8.json` dibuat
   ulang). Bila sudah terpasang di HP uji, biarkan seperti ini.
4. Foto yang dihapus di web **tidak** dihapus di HP (`mergeChildren` hanya menambah foto; penanda yang dihapus di web memang dihapus).
   Ini sengaja demi keselamatan data; putuskan kebijakan produknya.

## 2. [SERVER] Batas laju 300 permintaan/menit per IP bisa menggagalkan sinkron awal

Beberapa HP di satu Wi-Fi desa memakai satu IP publik, sehingga sinkron awal (puluhan potongan tus per rekaman) bisa kena `429`.
**Server sudah diperbaiki:** permintaan dengan token akses yang sah dihitung **per perangkat** (bawaan 1500/menit, `RATE_LIMIT_DEVICE_PER_MIN`);
yang anonim tetap per IP (300/menit, `RATE_LIMIT_IP_PER_MIN`). Token palsu/kedaluwarsa jatuh ke jatah IP.

**Android [TODO] (kecil):**
- Perlakukan `429` sebagai bisa-diulang: baca header `Retry-After` (detik) dan tunda sebelum mencoba lagi, jangan hitung sebagai
  kegagalan permanen. Sekarang `ServerApi` hanya menerjemahkan 429 menjadi pesan "Terlalu banyak percobaan".
- Pertimbangkan opsi "hanya lewat Wi-Fi" untuk sinkron awal (unggah audio besar).

## 3. [TODO] Migrasi belum pernah diuji dengan database v7 sungguhan

Skema v7 tidak pernah diekspor (`exportSchema` dulu `false`), jadi tidak ada `MigrationTestHelper` yang bisa memulai dari v7. SQL
`MIGRATION_7_8` cocok dengan `8.json`, dan `MIGRATION_8_9` akan cocok dengan `9.json` setelah dibuat, tetapi **belum pernah dijalankan
terhadap data nyata**. Sebelum rilis:
- Ambil `inteldes.db` dari HP yang berisi rekaman RAW, DONE, penanda, foto, chat, riwayat, template, dan konteks.
- Tulis uji instrumented yang menyalin berkas itu ke `databases/`, membukanya lewat `IntelDesDatabase.get`, lalu memastikan jumlah baris
  tiap tabel sama dan `web_sync_state` ada.
- Pastikan juga `inteldes_v7.bak` terbentuk dan bisa dibuka.

## 4. [TODO] Edit dan hapus template/konteks belum diteruskan

`WebLibrarySync` hanya menyalin yang belum ada di sisi lain (aditif, disengaja). Ganti nama template/konteks di web tidak sampai ke HP dan
sebaliknya. Server sudah mendukung `PUT /v1/templates/{id}` dan `PUT /v1/contexts/{id}` ber-`If-Match`, `DELETE` (soft delete), dan feed
`GET /v1/sync/changes` sudah memuat entitas `template` dan `context` dengan `version`. Saran: simpan versi terakhir per id, lalu
dorong/tarik bila versinya berubah, dengan aturan "kosong tidak menimpa isi" seperti di `WebSyncEngine`.

## 5. [TODO] Konteks kehilangan sumbernya bila rapat sumber belum ada di server

`WebLibrarySync.contexts` mengirim ulang tanpa sumber bila server menjawab `bad_source`, lalu karena hanya membuat (`If-Match: 0`), sumber itu
tidak pernah ditambahkan belakangan. Perbaikan: setelah rapat sumber tersinkron, `PUT /v1/contexts/{id}` dengan `If-Match: <versi>` dan daftar
sumber lengkap (server mengganti sumber utuh pada tiap `PUT`).

## 6. [TODO] Urutan chat saat pesan dibuat di HP dan di web

HP memberi `orderIndex = jumlah pesan lokal` (`RecordingRepository`), server memberi `orderIndex = terakhir + 1`. Jika Tanya AI dipakai di
dua sisi sebelum sinkron, dua pesan bisa punya `orderIndex` sama. `ChatDao` mengurutkan hanya `ORDER BY orderIndex`, sehingga urutan antar
yang sama tidak tentu. Saran: `ORDER BY orderIndex, createdAt` pada `observeMessages` dan `list`.

## 7. [TODO] Jenis berkas audio

Server hanya menerima `audio/mp4`, `audio/m4a`, `audio/x-m4a`, `audio/aac`, `audio/mpeg`, `audio/wav`, `audio/x-wav`, `audio/ogg`,
`audio/webm` (foto: `image/jpeg`, `image/png`, `image/webp`). Jenis lain dibalas `415`. Rekaman bawaan app (m4a) aman; untuk berkas yang
diimpor pengguna, normalkan `audioMimeType` ke salah satu di atas atau lewati dengan pesan jelas, jangan biarkan gagal berulang tiap sinkron.

## 8. Catatan kecil

- Komentar di `WebApi.kt` ("the server caps one piece at 8 MB") keliru: batas server 50 MB (`UPLOAD_CHUNK_LIMIT_BYTES`). Potongan 4 MB aman.
- Mesin `WebSyncEngine` mengabaikan entitas feed selain `recording` (benar); template/konteks hanya lewat `WebLibrarySync`.
- Sinkron web mati secara bawaan dan harus dinyalakan di Pengaturan setelah login; pastikan teks bantuan di layar menjelaskannya.

## Cara menguji

```
./gradlew :app:testDebugUnitTest --tests "com.inteldes.app.data.web.*"
```
- Uji yang harus hijau: `WebSyncEngineTest` (termasuk 4 uji baru di bagian akhir), `WebLibrarySyncTest`, `WebApiTest`.
- Uji tangan di HP:
  1. Pasang build baru di atas versi lama berisi rekaman; buka app; semua rekaman lama harus utuh (cek `inteldes_v8.bak`/`inteldes_v7.bak`).
  2. Login, nyalakan sinkron web, tunggu selesai; rekaman muncul di web.
  3. Di web, tambah penanda pada rapat itu, tambah foto, dan jalankan Tanya AI; sinkron ulang di HP; ketiganya harus muncul di rapat yang sama.
  4. Tambah penanda di HP (tanpa sinkron) dan di web, lalu sinkron: kedua penanda ada di HP dan di web.
