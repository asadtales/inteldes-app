# Analisis rencana perbaikan dari pemilik (4 November 2026)

Sumber: `docs/server/RENCANA_PERBAIKAN_APP_2026-11.md` (bagian 1–8), `STATUS_KERJA_SERVER.md`, snapshot OpenAPI web `9ac00bf`, dan kode server di `inteldes-web`. Dibandingkan dengan app 2.5.0 (commit `6a9566f`) serta perubahan di working tree yang disebut di bawah.

Ukuran usaha: **S** setengah hari, **M** 1–2 hari, **L** beberapa hari. Perkiraan kasar, belum termasuk uji di HP.

## 0. Temuan yang sudah ditindaklanjuti hari ini

### Geotag foto di server bisa terhapus oleh app 2.5.0 (risiko kehilangan data), SUDAH DIPERBAIKI di working tree
Server kini menyimpan geotag per foto (migrasi 0010). Pada `PUT /v1/recordings/{id}/photos` bidang geotag yang **tidak dikirim** dibaca server sebagai `null` dan **menimpa** geotag yang ada (`latitude ?? null` lalu `excluded.latitude`, selama foto belum dihapus). App 2.5.0 tidak mengenal geotag, jadi setiap kali ia mengirim daftar foto (misalnya sesudah mengubah teks keterangan) geotag yang diisi di web akan hilang.

Perbaikan (belum di-commit): kolom foto `latitude`, `longitude`, `accuracyM`, `placeName` di Room (DB **v10**, `MIGRATION_9_10`, skema `10.json`), model sinkron membawa dan mengirim balik geotag, geotag yang ditambahkan di server turun ke foto yang sudah ada di HP, dan geotag ikut hash foto hanya bila ada (foto tanpa geotag tidak dikirim ulang sia-sia). Tes: 131 tes unit, `verify_migration.py` (7→10), `MigrationTest` 4 tes **lolos di HP**, DB HP naik ke v10 dengan 14 rekaman utuh dan cadangan otomatis `inteldes_v9.bak`.

Sebelum perbaikan ini dipasang di semua HP, jangan mengisi geotag di web untuk foto yang juga ada di HP.

### Login Google/SSO: server sudah menyala
`GET /v1/auth/providers` sekarang `appLogin: true` (diperiksa 4 Nov). Tombol "Masuk dengan Google" dan "Masuk dengan Portal SSO" harus muncul di layar masuk app 2.5.0. Belum bisa saya lihat sendiri karena HP sedang dalam keadaan masuk; perlu keluar akun untuk melihat layar masuk (lihat bagian 3).

## 1. Peta rencana pemilik terhadap app

| Bagian | Isi | Keadaan app | Selisih | Usaha |
|---|---|---|---|---|
| 1 | Nama "Arsip", logo (ikon launcher adaptif, notifikasi, wordmark) | `HomeScreen.kt:130` masih "Arsip rapat"; `app_name` = INTELDES; ikon launcher lama; tidak ada nama desa di string, hanya contoh isian "Balai Desa Tales" di `LocationField.kt:64` | ganti teks, buat ikon adaptif (tiga batang putih di `#C4260C`), ikon monokrom dan notifikasi, wordmark di layar Masuk/Tentang | S–M |
| 2 | Judul rekaman bisa diedit | Belum ada. Sinkron sudah siap: judul masuk bagian "meta" yang dikirim dengan `If-Match` | dialog ubah judul di Detail, `updateTitle` di repository | S |
| 3 | Login | Sudah: PKCE, `inteldes://auth/callback`, state dan verifier disimpan di preferensi (bukan memori), `launchMode=singleTop`, konfirmasi akun | pesan galat per kasus (sekarang satu toast), opsional Custom Tab | S |
| 4 | Kendala potong saat pemrosesan | Sebagian: format WebM/m4a/AMR dipotong di HP, mp3/wav lewat server dengan pesan jelas; sha256 dihitung dari berkas final saat kirim | lihat 2 di bawah | M |
| 5 | Editor potong gaya web | Ada editor sendiri (mode hapus/simpan, rentang, deteksi hening langsung memilih, draf lokal) | desain baru: gelombang, area Pertahankan/Hapus, urungkan/ulangi, ringkasan dengan pegangan, panel analisa hening, draf di server | L |
| 6 | Foto bergeotag + cap + logo; video singkat | Belum ada. Izin lokasi sudah ada di manifes (`LocationHelper`). Penerusan geotag dari server sudah ada (bagian 0) | pengambilan titik, cap piksel, EXIF, sakelar, logo organisasi; video (rekam/pilih, kompres 720p, tus, `PUT /videos`, pemutaran) | L (geotag M, video L) |
| 7 | Arsip seperti web, semua jam WIB | Daftar masih gaya lama; jam memakai zona perangkat | pencarian + tombol Filter, kartu padat; formatter ke `Asia/Jakarta` | M |
| 8 | OpenRouter dan model AI per penyedia | `AiProvider` hanya `AUTO/GEMINI/CLAUDE`; `when` di `DetailScreen.kt` dan `NotulensiGenerator.kt` eksaustif; belum ada layar model | enum, kunci, layar Model AI (`/v1/ai-models`), uji model, label | M–L |

## 2. Bagian 4 (kendala potong), butir per butir

1. **Format**: selesai. WebM/Opus dan m4a/AAC/AMR dipotong di HP (terbukti di HP: WebM 3 rentang, 44,98 dtk). mp3/wav otomatis lewat server bila masuk dan sinkron aktif; selebihnya pesan menyebut jenis yang bisa.
2. **Rekaman panjang**: **belum**. Potong berjalan di `viewModelScope` pada `Dispatchers.IO` tetapi tanpa notifikasi progres, tanpa persen, tanpa tombol batal, dan terhenti bila layar ditutup. Perlu WorkManager/foreground service, progres dari sampel tertulis, dan batal. (M)
3. **Sinkron setelah potong**: sha256 dihitung dari berkas final saat unggah (aman). **`durationSec` masih jumlah rentang yang diminta**, bukan durasi hasil nyata; perlu dibaca dari berkas hasil (`MediaExtractor` atau sampel terakhir). (S)
4. **Rentang pecahan detik**: **belum**. `TimeRange` dan editor memakai `Int` detik; server sudah menerima pecahan (≥ 0,2 dtk). `AudioTrimmer` sudah bekerja dalam mikrodetik, jadi perubahan terutama di `TrimPlan`, editor, dan tes. Terkait bagian 5 (area dengan `Double`). (M)
5. **Galat muxer**: pesan sudah memuat alasan (jenis tidak didukung, tidak ada audio). Belum ada log lokal yang bisa dikirim pemilik. (S)

## 3. Bagian 3 (login): yang perlu Anda lakukan dulu

Sebelum menulis kode apa pun: buka layar masuk app 2.5.0 dan periksa tombol Google/SSO muncul. Layar masuk hanya tampil bila belum masuk; HP sedang masuk sebagai `dasadarma`, jadi harus keluar dulu (Pengaturan > Akun > keluar). Data lokal tidak terhapus saat keluar. Bila tombol muncul, ujilah alur Google: browser, login, tombol "Buka IntelDes", layar konfirmasi akun. Catat bila halaman server berhenti di browser (meta refresh tanpa skrip: server menyebut pembukaan otomatis tidak dijamin, tombol adalah jalurnya).

## 4. Bagian 5 (editor potong): perbandingan dan saran pecahan

Yang sudah setara: banyak rentang, mode hapus dan pertahankan, penanda ikut bergeser, deteksi hening (hasil langsung dipilih, bisa diurungkan), draf (lokal, per HP), simpan lalu pilih template/mesin.

Yang berbeda dari rencana web:
- Model area `Region(start, end, mode)` dengan `uncovered`, bukan daftar rentang + satu mode.
- Gelombang besar dan ringkasan seluruh rekaman dengan dua pegangan.
- Panel hasil analisa hening (daftar bagian, centang, dengar, bantalan 0,5 dtk) alih-alih langsung memilih.
- Urungkan/ulangi berlapis (sekarang hanya satu langkah untuk deteksi hening).
- Pemutaran melewati bagian yang dihapus.
- Draf di server per pengguna (`/trim-draft`), bisa dilanjutkan dari web dan antar HP.
- Gelombang dari server (`/waveform`, `202 pending` untuk rekaman panjang) atau dihitung lokal.

Saran urutan (tiap langkah bisa diuji dan dikirim sendiri):
1. **5a Model dan logika murni + tes** (`Region`, `addRegion` memotong sebagian, hasil untuk kedua mode `uncovered`, riwayat urungkan/ulangi, bantalan hening, contoh lima area pemilik). Pecahan detik (`Double`). Tidak ada UI. (M)
2. **5b Gelombang**: hitung lokal dari berkas (sudah ada dekoder PCM) dan pakai `/waveform` untuk yang sudah di server; cache per sha256; rumus tinggi batang dari rencana. (M)
3. **5c Layar baru**: gelombang berjendela + zoom, ringkasan dengan pegangan, tiga aksi, daftar area, pemutaran melewati bagian dihapus. Mengganti `TrimEditor`. (L)
4. **5d Panel analisa hening**. (M)
5. **5e Draf di server** + lencana "Potong audio · draf" di daftar/detail; tetap simpan lokal sebagai cadangan offline. (M)

Draf lokal yang sudah ada (`TrimDraft`) tidak cocok dengan skema `TrimDraftState` server (`version`, `regions[{start,end,mode}]`, `uncovered`, `sel`, `pos`, `zoomIdx`, `analysis`, `title`, `kind`, `copyMarks`), jadi diganti pada 5e; tidak ada draf lama yang penting (fitur baru).

## 5. Bagian 6 (geotag, cap, logo, video): catatan

- **Geotag masuk akal dikerjakan dulu**: fondasi (kolom dan sinkron) sudah ada. Sisa: pengambilan titik per foto (batas 5 detik, lokasi terakhir ≤ 60 dtk bila akurasi ≤ 50 m), cap pada piksel (pita dengan logo, tanggal WIB, koordinat, akurasi, tempat), EXIF GPS, tiga sakelar di Pengaturan (bawaan **mati**), logo organisasi (≤ 1 MB). Privasi: koordinat ikut ke tautan bagikan publik, jelaskan di layar izin.
- **Logo organisasi per organisasi**: rencana menyebut "disimpan per organisasi", tetapi server belum punya tempat penyimpanan logo (tidak ada endpoint di OpenAPI). Sementara per HP; tanyakan ke tim web bila harus ikut ke perangkat lain.
- **Video**: batas server 100 MB dan 120 dtk, tus dengan `filetype` video, `PUT /videos`, maksimal 20 per rapat. Perlu kompresi H.264 720p di HP (kandidat: Media3 Transformer, dependensi baru yang belum ada), pemutar (Media3), antrean unggah offline. Ini bagian terbesar dan paling berisiko ukuran APK; saran: tahap terakhir dan putuskan dulu apakah perlu.

## 6. Bagian 8 (OpenRouter dan model): catatan

- Mode **proses di server** sudah berjalan: server memilih penyedia dan model. Yang perlu ada di app terutama layar Model AI (membaca `GET /v1/ai-models`, `effective`, `catalog`, uji lewat `/check`, simpan lewat `PUT` oleh admin) dan label.
- **Proses langsung dari HP** memakai klien sendiri (`GeminiClient`, `ClaudeClient`). Menambah `OPENROUTER` di sana berarti klien baru (API setara OpenAI) dan isian model; pilihan produk: dukung OpenRouter hanya lewat server, atau juga lokal. Saran: **hanya lewat server** untuk tahap pertama (lebih kecil dan kunci tidak perlu diketik di HP).
- Penyedia `OPENROUTER` pada rekaman yang dibuat di web terbaca app sebagai tanpa penyedia (dekoder sudah menoleransi nilai tak dikenal lewat `runCatching`); `Converters.toAiProvider` tidak melindungi nilai tak dikenal, perlu dilindungi saat enum diperluas.

## 7. Urutan yang disarankan

1. **Sekarang**: commit perbaikan geotag (bagian 0) dan pasang ke HP yang dipakai; uji login Google (bagian 3).
2. **Cepat dan kelihatan** (S): nama "Arsip", judul bisa diedit, durasi hasil potong nyata, pesan galat login, jam WIB.
3. **Logo** (S–M).
4. **Potong andal** (M): progres/batal/layanan latar, log galat.
5. **Editor potong baru** 5a → 5e (L, bertahap).
6. **Geotag foto + cap + logo** (M); **model AI/OpenRouter** (M); **Arsip gaya web** (M).
7. **Video** (L), bila tetap diperlukan.

## 8. Keputusan yang dibutuhkan dari pemilik

1. Editor potong lama (yang sekarang terpasang) diganti seluruhnya oleh desain web; setuju?
2. Video singkat: perlu sekarang, atau ditunda?
3. OpenRouter hanya lewat server dulu?
4. Logo organisasi: per HP dulu, atau tunggu server menyimpannya?
5. Boleh mulai dari urutan di bagian 7?
