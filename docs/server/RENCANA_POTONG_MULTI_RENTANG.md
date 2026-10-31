# Rencana: potong audio banyak rentang dan penanda yang sama dengan web

Untuk pengembang Android. Ini rencana, belum ada kode di app. Web (inteldes-web) sudah memakai perilaku di bawah ini sejak commit f8366a4; tujuan rencana ini adalah app berperilaku sama.

## 1. Kondisi sekarang di app

- `AudioTrimmer.trim(source, dest, startSec, endSec)`: **satu rentang**, memakai `MediaExtractor` + `MediaMuxer` (salin sampel apa adanya, tanpa sandi ulang). Muxer MPEG-4 hanya menerima AAC/AMR, jadi hanya **m4a/AAC**; mp3/ogg gagal dengan pesan jelas.
- `RecordingRepository.createTrimmedRecording(source, startSec, endSec, outputDir)`: membuat rekaman baru (judul `... (potongan mm:ss–mm:ss)`), menyalin penanda yang jatuh di rentang dengan waktu `ts - startSec`.
- `TrimAudioDialog`: satu pasang waktu mulai/selesai.
- `WhisperCloudClient` memakai `AudioTrimmer.trim` untuk mengunggah satu sub-rentang saja ke transkripsi awan; `OnDeviceWhisperClient` punya `rangeStartSec/rangeEndSec`.
- Penanda: ketukan saat merekam sudah ada (`addMarks` memberi label `Penanda N`).

## 2. Perilaku target (sama dengan web)

1. **Beberapa rentang** dalam satu pemotongan, mis. 0:10–0:20 dan 1:10–1:32, disambung berurutan menjadi satu rekaman baru. Tujuannya membuang bagian hening/tak penting supaya biaya transkrip dan ukuran berkas turun.
2. Rentang diurutkan, dipotong ke durasi rekaman, yang kosong dibuang, dan yang **tumpang tindih atau bersentuhan digabung** (`start <= lastEnd`). Maksimum 100 rentang.
3. Durasi hasil = jumlah panjang rentang (setelah digabung). Waktu rekaman (`createdAt`) = waktu sumber + awal rentang pertama.
4. **Penanda dalam rentang ikut**, batas akhir **inklusif**, waktunya digeser:
   `ts_baru = offset + (ts - rentang.start)`, dengan `offset` = jumlah panjang semua rentang sebelumnya.
5. Rekaman baru berstatus belum diproses; transkrip dan notulensi sumber tidak ikut. Sumber tidak berubah.
6. Pintu masuk tambahan: dari daftar penanda, tombol **"Potong dari sini"** membuka editor dengan rentang awal dimulai di penanda itu (web: 60 detik pertama dari penanda, bisa diubah).

## 3. Rancangan perubahan

### 3.1 Model dan normalisasi (Kotlin murni, mudah diuji)
`data/audio/TrimRange.kt`
```kotlin
data class TrimRange(val startSec: Int, val endSec: Int)
fun normalizeRanges(ranges: List<TrimRange>, durationSec: Int): List<TrimRange>  // urut, clamp, buang kosong, gabung tumpang tindih
fun shiftMarks(marks: List<Mark>, ranges: List<TrimRange>): List<Mark>           // rumus butir 4
```

### 3.2 `AudioTrimmer.trimRanges(source, dest, ranges)`
- Satu `MediaMuxer`, satu `addTrack`, lalu untuk tiap rentang: `extractor.seekTo(startUs, SEEK_TO_CLOSEST_SYNC)`, baca sampel sampai `sampleTime > endUs`, tulis dengan `presentationTimeUs = (sampleTime - startUs) + outputOffsetUs`. Setelah rentang selesai: `outputOffsetUs += (endUs - startUs)`.
- Tanpa berkas antara dan tanpa sandi ulang. Format tetap m4a/AAC; batasan lama (mp3/ogg) dipertahankan, pesannya sama.
- Risiko: batas rentang jatuh di tengah frame AAC (±23 ms), kemungkinan klik kecil di sambungan. Terima; jangan menambah sandi ulang.
- Pertahankan `trim(...)` lama sebagai pembungkus `trimRanges(listOf(...))` agar pemanggil lama (`WhisperCloudClient`) tidak rusak.

### 3.3 `RecordingRepository.createTrimmedRecording`
Ganti tanda tangan menjadi `(source, ranges: List<TrimRange>, outputDir)`. Judul: satu rentang `... (potongan mm:ss–mm:ss)`, banyak rentang `... (potongan N rentang)`. Penanda lewat `shiftMarks`. Durasi = jumlah rentang.

### 3.4 `TrimAudioDialog` (UI)
- Daftar rentang berupa kartu (`Rentang 1 · 00:10–00:20 (00:10)`, tombol hapus bila lebih dari satu), tombol **+ Tambah rentang** (rentang baru mulai satu detik setelah akhir terjauh, panjang 10 detik, atau dari 0 bila tak ada ruang).
- Waktu mulai/selesai mengedit rentang yang aktif; bendera penanda bisa dipakai sebagai titik awal/akhir; tampilkan total durasi dan jumlah penanda yang ikut; peringatan "rentang tumpang tindih akan digabung".
- Judul default "<judul sumber> (potongan)".

### 3.5 Transkripsi sebagian rentang
`WhisperCloudClient`/`OnDeviceWhisperClient`: bila pengguna memilih beberapa rentang untuk ditranskripsi, jalur paling sederhana dan sama dengan web adalah **potong dulu lalu proses rekaman barunya** (tidak perlu parameter rentang jamak). Parameter `rangeStartSec/EndSec` yang ada boleh tetap untuk satu rentang.

### 3.6 Sinkron
Tidak ada perubahan server atau API. Rekaman hasil potongan adalah rekaman baru biasa: ikut sinkron lewat jalur yang ada (audio via tus, metadata, penanda). Penanda yang digeser dibuat dengan UUID baru.

### 3.6b Deteksi hening (saran rentang)
Web punya tombol **Deteksi bagian hening** yang mengganti daftar rentang dengan saran rentang bicara (bisa diurungkan dan disunting). Server:  mengembalikan ,  (rentang bulat siap dikirim ke ), , . Pilihan di UI: jenis rekaman (bersih -42 dB, normal -35 dB, berisik -28 dB) dan hening minimal (2/3/5/10 detik); bantalan 0,5 detik di sekitar suara agar kata tidak terpotong; bagian bicara di bawah 1 detik dibuang.
Untuk app ada dua pilihan: (a) **di perangkat**: decode PCM dengan  (sudah ada ), hitung RMS per jendela 50 ms, tandai hening bila RMS di bawah ambang selama >= minSilenceSec, lalu pakai rumus yang sama dengan  (kebalikan hening, bantalan, buang < 1 detik, ); atau (b) panggil endpoint server bila rekaman sudah tersinkron. Disarankan (a) agar jalan offline, dengan vektor uji dari server (nada 3 dtk, hening 5 dtk, nada 3 dtk menghasilkan dua rentang bicara [0..4] dan [7..11]).

### 3.7 Opsional, fase kemudian: potong di server
Untuk audio non-m4a (mp3/webm/wav dari web) atau rekaman besar, app bisa memanggil `POST /v1/recordings/{id}/trim` dengan `{ "ranges": [{"startSec":10,"endSec":20}], "title":"...", "kind":"...", "copyMarks":true }` setelah rekaman tersinkron. Server memotong dengan ffmpeg dan mengembalikan rekaman baru (201). Hanya untuk rekaman yang audionya sudah ada di server; rekaman yang audionya sudah dihapus retensi tidak bisa.

## 4. Penanda: kesetaraan dengan web

Web sekarang punya (cek padanan di app, tambahkan yang belum ada):

| Fitur | Web | App |
|---|---|---|
| Tandai satu ketukan saat merekam, label otomatis `Penanda N` | ada | ada (`addMarks`) |
| Tombol Tandai di pemutar saat meninjau rekaman (tanpa mengetik) | ada | **periksa; tambahkan bila belum** |
| Ubah nama penanda | ada | **periksa** |
| Bendera penanda di atas gelombang pemutar (klik = lompat) | ada | **periksa** |
| Penanda tampil di transkrip pada segmen terdekat (klik = lompat) | ada | **periksa** |
| "Potong dari sini" dari daftar penanda | ada | **tambahkan** |

Kontrak sinkron penanda tidak berubah: `PUT /v1/recordings/{id}/marks` dengan UUID klien; mengubah nama = kirim ulang `id` yang sama dengan `label` baru (server menggabung per UUID, hapus mengalahkan edit).

## 5. Pengujian

- Unit (JVM): `normalizeRanges` dan `shiftMarks` dengan vektor yang sama dengan uji server: rentang `[6–8],[1–3]` dengan penanda di 1, 3, 8 detik menghasilkan durasi 4 dan penanda di 0, 2, 4; rentang `[1–4],[3–6]` menjadi satu rentang 5 detik; rentang terbalik/kosong ditolak.
- Instrumentasi: potong m4a 10 detik menjadi `[1–3],[5–8]`; durasi hasil 5 detik ±0,1 detik per sambungan; berkas bisa diputar penuh.
- Manual: potong dari penanda; tambah/hapus rentang; mp3 menampilkan pesan batasan; hasil tersinkron ke web dan bisa diproses di sana.

## 6. Urutan kerja yang disarankan

1. `TrimRange` + `normalizeRanges`/`shiftMarks` + uji unit.
2. `AudioTrimmer.trimRanges` + uji instrumentasi.
3. `createTrimmedRecording` multi-rentang.
4. `TrimAudioDialog` multi-rentang + "Potong dari sini".
5. Kesetaraan penanda (butir 4), sesuai hasil pemeriksaan.
6. Deteksi hening di perangkat (3.6b) dan tombolnya di editor potong.
7. (Opsional) potong di server untuk non-m4a.

## 7. Catatan lain dari perubahan web terbaru

- Judul bawaan rekaman web memakai **WIB (Asia/Jakarta)**; sebelumnya salah memakai UTC (mis. tertulis 14.22 padahal 21.22). App memakai jam perangkat, jadi tidak terpengaruh, tetapi pastikan format judul bawaan konsisten.
- Respons `/v1/auth/me` kini punya field tambahan `org.kind` dan `superadmin` (Gson mengabaikan field yang tidak dikenal). Akun pribadi (`org.kind = "personal"`) memakai arsip terpisah dan kunci AI platform dengan kuota harian lebih kecil.
