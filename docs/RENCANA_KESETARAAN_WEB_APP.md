# Rencana bertahap: web dan app IntelDes setara

Disusun 1 November 2026 dari pemeriksaan kode app (`D:\project\inteldes-app`) terhadap `origin/main` web (`71bdd4d`) dan dokumen di `docs/server/`. Status "ada" berarti sudah ada kodenya di app; "terpasang di HP" dicatat terpisah karena belum semua diuji di perangkat.

## 0. Prinsip yang tidak berubah

1. Rekaman di HP tidak boleh hilang. Cadangkan HP sebelum memasang build apa pun.
2. Server tidak pernah menghapus data HP. Rekaman yang dihapus di web diabaikan oleh app.
3. HP menang saat bentrok per bagian; yang kosong tidak menimpa yang berisi.
4. Tidak ada kunci AI di dalam APK. Kunci hanya di server, atau diketik pengguna di HP untuk mode tanpa server.
5. Setiap tahap: uji unit hijau, cadangan HP, pasang, uji di HP asli, baru commit atas permintaan.

## 1. Peta kesetaraan (keadaan sekarang)

| Area | Web | App | Celah |
|---|---|---|---|
| Rekam, penanda saat merekam | ada | ada | tidak ada |
| Penanda: tambah saat meninjau | ada | ada (tombol di pemutar) | tidak ada |
| Penanda: **ubah nama** | ada | **ada** | uji di HP |
| Penanda: bendera di pemutar | ada | ada (tick + chip) | periksa tampilan |
| Penanda di transkrip (segmen terdekat, klik lompat) | ada | **ada** | uji di HP |
| "Potong dari sini" dari penanda | ada | **ada** | uji di HP |
| Potong audio banyak rentang, mode hapus/simpan | ada | **ada (baru, belum diuji di HP)** | tahap 1 |
| Pilihan template/mesin sebelum proses hasil potong | ada | **ada (baru)** | tahap 1 |
| Deteksi bagian hening | ada (server) | **ada (di HP, offline)** | uji di HP |
| Potong audio non-m4a (mp3/webm/wav) | ada (ffmpeg) | **m4a/AAC/AMR/WebM di HP; mp3/wav lewat server** | uji di HP |
| Transkrip: sunting, label pembicara | ada | ada | tidak ada |
| Notulensi: template, kepadatan, susun ulang, riwayat versi | ada | ada | tidak ada |
| Tanya AI per rapat | ada | ada | tidak ada |
| Konteks: gabung dari rapat, tanya, perbaiki | ada | ada | tidak ada |
| Konteks: **keluarkan satu sumber** | belum dicek di web | **ada (baru)** | cek web |
| Foto dokumentasi | ada | ada | tidak ada |
| Impor percakapan WhatsApp | ada | ada | tidak ada |
| Ekspor Word / teks / PDF | ada | **ada (PDF baru, dibuat di HP)** | selesai di kode, belum diuji di HP |
| Tautan bagikan publik (`/s/<token>`) | ada | **ada (baru)**: buat, salin/bagikan, daftar, cabut | selesai di kode, belum diuji di HP |
| Retensi audio otomatis | ada (admin) | hanya menerima efeknya | tahap 5 |
| Sumber kunci AI (IntelDes / sendiri) + kuota | ada | **belum**: app punya kolom kunci sendiri | tahap 5 |
| Proses AI di server dari app | ada | **ada, berdampingan dengan proses di HP** | paket/kuota: `PERMINTAAN_PAKET_KUOTA_SERVER.md` |
| Akun pribadi vs tim | ada | ada | tidak ada |
| Sinkron dua arah | ada | ada | uji ubah nama penanda web→HP |
| Pilih rekaman yang ikut sinkron | tidak ada | **ada (baru)**, hanya di app | tidak perlu di web |
| Sinkron template/konteks: edit & hapus | ada | **ada** | uji dengan server nyata |
| Login Google, SSO, QR | ada | email + QR; **Google/SSO lewat browser (baru, tersembunyi sampai server siap)** | butuh server: `PERMINTAAN_LOGIN_APP_OAUTH.md` |

## 2. Tahap-tahap

### Tahap 0 — Cabut kunci dari APK (selesai di kode, menunggu langkah pemilik)

- Selesai di kode: `EMBEDDED_*` dihapus dari `build.gradle.kts`, `SecurePrefs.hasEmbedded*` dihapus, `effective*Key` hanya memakai kunci yang diketik pengguna, layar Pengaturan tidak lagi bercerita "kunci tertanam".
- Dampak: build ini tidak bisa memproses AI dari HP sebelum kunci diketik di Pengaturan, **sampai tahap 5** (proses lewat server) selesai.
- Menunggu pemilik (`docs/server/CUTOVER_KUNCI_APK.md` bagian 3): kunci baru di tiap penyedia, dipasang di server; kunci lama dicabut.
- Pemeriksaan sebelum APK dibagikan: pola kunci di dex, riwayat git, dan `.env` lama dikosongkan.

### Tahap 1 — Uji dan rapikan potong audio (kode ada)

1. Uji di HP: potong m4a menjadi beberapa rentang; periksa durasi hasil (jumlah rentang, ±0,1 detik per sambungan) dan putar penuh tanpa klik berat di sambungan.
2. Uji mode hapus dan simpan, "Proses sekarang" dan "Simpan saja", rekaman dari unggahan mp3 (harus pesan jelas, tanpa crash).
3. Judul hasil: satu rentang `... (potongan mm:ss–mm:ss)`, banyak rentang `... (potongan N rentang)` (sekarang masih `(hasil potong)`; samakan dengan rencana web).
4. Uji instrumentasi `trimRanges` (belum ada; hanya logika murni `TrimPlan` yang teruji unit).

### Tahap 2 — Penanda setara web

1. Ubah nama penanda: ketuk lama pada chip di pemutar, dialog nama. Perlu `RecordingMarkDao.updateLabel`. Sinkron sudah benar secara kontrak (kirim ulang `id` sama dengan `label` baru); **uji web→HP** apakah label baru menimpa label lama di Room (ini belum dipastikan).
2. Penanda di transkrip: tampilkan bendera kecil pada segmen terdekat dengan waktu penanda; ketuk untuk lompat.
3. "Potong dari sini" di daftar penanda: membuka editor potong dengan rentang awal mulai di penanda itu (web: 60 detik pertama, bisa diubah).

### Tahap 3 — Potong lanjutan

1. Deteksi hening di perangkat: dekode PCM dengan `AudioDecoder.decodeToWhisperPcm`, RMS per jendela 50 ms, ambang bersih −42 / normal −35 / berisik −28 dB, hening minimal 2/3/5/10 detik, bantalan 0,5 detik, buang bagian bicara < 1 detik. Hasilnya mengisi daftar rentang di editor (mode simpan) dan bisa diurungkan.
   Vektor uji dari server: nada 3 dtk, hening 5 dtk, nada 3 dtk → rentang bicara [0..4] dan [7..11].
2. Potong audio non-m4a: untuk rekaman yang sudah tersinkron, panggil `POST /v1/recordings/{id}/trim` di server (ffmpeg), lalu tarik rekaman baru. Syarat: audio masih ada di server (bukan terhapus retensi). Tanpa jaringan tetap hanya m4a.

### Tahap 4 — Bagikan dan ekspor setara web

1. Tautan bagikan: `POST /v1/recordings/{id}/shares` untuk rekaman tersinkron; tampilkan tautan `/s/<token>`, pilihan kedaluwarsa, cabut. Hanya jika masuk ke server.
2. Ekspor PDF (selain DOCX dan teks): lewat server (`export`) bila tersinkron, atau `PdfDocument` Android untuk offline.
3. Pastikan isi DOCX dari app dan dari web memuat bagian yang sama (ringkasan, bagian template, foto, transkrip opsional).

### Tahap 5 — Satu sumber kunci, proses lewat server

Ini tahap yang menggantikan kunci tertanam.

1. Kartu "Kunci AI" hanya-baca di Pengaturan bila masuk ke server: sumber (`aiKeySource`), kesiapan (`ai`), pemakaian 24 jam (`aiAccess`) dari `GET /v1/settings`, tautan ke web untuk mengubah.
2. Kolom kunci lokal hanya muncul untuk mode tanpa server, dengan label "dipakai hanya bila tidak masuk ke server".
3. Proses lewat server: setelah rekaman tersinkron, tombol "Proses di server" memanggil `POST /v1/recordings/{id}/process` lalu menarik hasilnya lewat sinkron biasa. Jalur lama (langsung dari HP) tetap ada untuk mode tanpa server.
4. Tangani `429 quota_exceeded` dengan pesan dari server; jangan coba ulang otomatis. Tangani `422 secret_missing`.
5. Pengaturan retensi audio: tampilkan nilai dari server (hanya baca di app; admin mengubahnya di web).
6. Keputusan yang dibutuhkan: apakah proses di HP tetap ditawarkan saat masuk ke server, atau server menjadi jalur utama.

### Tahap 6 — Perpustakaan tersinkron penuh

1. Edit dan hapus template dan konteks diteruskan (sekarang tambah saja): `PUT/DELETE /v1/templates/custom_<uuid>`, `PUT/DELETE /v1/contexts/{id}` dengan `If-Match`.
2. Urutan chat dengan `orderIndex` lalu `createdAt`.
3. Normalisasi jenis audio (415) dan penanganan `Retry-After` untuk 429.
4. Uji migrasi dengan database v7 sungguhan (`MigrationTestHelper`), simpan skema.

### Tahap 7 — Opsional

- Masuk dengan Google/SSO di app (saat ini email + QR).
- `assetlinks.json` dengan SHA-256 rilis.

## 3. Urutan kerja yang disarankan

0 → 1 → 2 → 5 → 3 → 4 → 6 → 7.

Tahap 5 diletakkan sebelum 3 dan 4 karena build tanpa kunci membuat proses dari HP bergantung pada kunci yang diketik pengguna; selama tahap 5 belum ada, pengguna HP butuh kunci sendiri.

## 4. Risiko dan hal yang belum diverifikasi

- Potong banyak rentang belum diuji di HP asli; kualitas sambungan belum didengar.
- Perilaku ubah nama penanda web→HP belum diuji.
- Hapus sumber konteks hanya mengenali bagian yang judulnya belum diubah pengguna.
- Sinkron terpilih membatasi apa yang dikirim; rekaman yang sudah terlanjur terkirim tetap di server.
- Retensi di server nyata belum pernah dialami app (hanya uji unit).


## 5. Status per 1 November 2026 (malam)

Tahap 1, 2, 3.1, 3.2, 4, 5 (kecuali paket/kuota di server), 6 (termasuk 6.4) dan 7 sudah dikerjakan di kode app; tahap 7 menunggu server. Rincian perubahan dan cara uji: `CATATAN_PERUBAHAN_APP.md`. Kerja server: `server/DAFTAR_KERJA_SERVER.md`.
