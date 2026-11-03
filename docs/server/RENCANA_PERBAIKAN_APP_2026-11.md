# Rencana perbaikan inteldes-app (dari pemilik, 2026-11-03)

Untuk pengembang Android. Ini rencana dan instruksi, **belum ada kode di app**: komputer pengembangan app sedang mati, jadi semua ditulis di sini agar bisa langsung dikerjakan saat aktif lagi. Rujukan perilaku: web inteldes-web (produksi https://inteldes.at-in.site, commit `75c5ed1` editor potong dan `df988c4` kerangka). Snapshot OpenAPI di `docs/server/openapi.snapshot.yaml` sudah mutakhir.

Urutan yang disarankan: (1) nama dan logo, (2) judul rekaman bisa diedit, (3) login, (4) kendala potong saat pemrosesan, (5) editor potong baru, (6) geotag + logo pada foto.

---

## 1. Logo dan nama: samakan dengan web

**Nama.** Di seluruh app: "Arsip rapat" menjadi **"Arsip"** (judul layar daftar, tab/menu bawah, judul kosong, remah roti/kembali, string `strings.xml` dan terjemahan). Alasan: produk dipasarkan umum, bukan hanya untuk rapat desa. Cari juga teks lain yang menyebut "Desa Tales" atau desa tertentu di layar Tentang/Masuk dan hapus; nama produk cukup **IntelDes**.

**Logo.** Web memakai dua elemen yang harus sama persis di app:

1. **Ikon aplikasi / favicon**: persegi merah `#C4260C`, di dalamnya tiga batang putih tegak (`#FFFFFF`) berjarak sama. SVG acuan (viewBox 32×32):
   ```svg
   <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">
     <rect width="32" height="32" fill="#c4260c"/>
     <path d="M8 8h4v16H8zM14 8h4v16h-4zM20 8h4v16h-4z" fill="#fff"/>
   </svg>
   ```
   Buat `ic_launcher` adaptif: latar `#C4260C`, foreground tiga batang putih (batang 4/32 lebar, tinggi 16/32, di tengah, dalam zona aman 66% ikon adaptif), ikon monokrom untuk Android 13 (hanya tiga batang), dan ikon notifikasi (tiga batang putih polos). Ganti semua `mipmap-*` dan `ic_stat_*`.
2. **Wordmark**: teks **INTELDES** huruf kapital, tebal (berat 800), jarak huruf renggang (`letter-spacing` ≈ 0,06 em; di bilah atas web 0,1 em, ukuran 14–18 sp). Warna tinta `#201E1D` di atas latar `#F3F2F2`. Dipakai di layar Masuk, Tentang, dan header; tanpa embel-embel nama desa.

Cek: layar splash/Masuk/Tentang, ikon launcher, notifikasi perekaman (foreground service), widget bila ada.

---

## 2. Judul rekaman bisa diedit

Sekarang judul tidak bisa diubah di app. Server sudah mendukung: `PUT /v1/recordings/{id}` dengan `If-Match: <version>` dan body `RecordingInput` (`title` 1–300 karakter). Dalam arsitektur offline-first app: ubah `title` di Room, naikkan penanda "perlu sinkron", dan pada sinkron kirim PUT; bentrok versi (`412`/`409`) ditangani seperti entitas lain (ambil versi server, simpan sisi yang kalah sebagai revisi bila sudah ada mekanismenya).

UI: di layar detail, ketuk judul atau ikon pensil → dialog satu kolom (isi awal judul sekarang, tombol Simpan/Batal, `maxLength 300`, tidak boleh kosong). Judul ikut tampil di daftar Arsip seketika. Web: halaman detail punya tombol Ubah (dialog "Ubah rapat") sebagai acuan.

---

## 3. Proses login yang belum jalan

Rujukan lengkap: `PERMINTAAN_LOGIN_APP_OAUTH.md` dan `STATUS_KERJA_SERVER.md`. Yang perlu dipastikan:

1. **Server sudah siap**: `APP_LOGIN=true` di produksi, kredensial Google web terisi. Tombol Google/SSO di app baru muncul bila konfigurasi publik server (bidang `appLogin`) mengembalikan `true`; **APK lama tidak punya tombolnya**, jadi pasang APK versi 2.5.0 atau lebih baru.
2. Alur: PKCE ke `/auth/app/start`, buka Custom Tab, pulang lewat tujuan konstan `inteldes://auth/callback`, tukar kode (berlaku 2 menit, sekali pakai) di `/v1/auth/app/exchange`. Pastikan `intent-filter` untuk skema `inteldes://auth/callback` terdaftar dan `launchMode` tidak membuat instans ganda.
3. Cek gejala yang pernah terlihat: tombol tidak muncul (konfigurasi tidak terbaca atau APK lama), kembali dari browser tanpa masuk (intent-filter/state PKCE hilang saat proses app dimatikan sistem: simpan `state` + `verifier` di penyimpanan, bukan di memori), pesan "Login tidak bisa dimulai" (parameter `redirect` berbeda dari konstanta).
4. Tambahkan layar galat yang jelas per kasus, bukan satu pesan umum.

Catatan keamanan: jangan menanam kunci apa pun di APK; kunci AI sedang dipindah ke server (`CUTOVER_KUNCI_APK.md`).

---

## 4. Kendala potong (trim) saat pemrosesan

Gejala dari pemilik: masih ada kendala di app saat memotong, pada tahap pemrosesan. Penyebab yang perlu diperiksa berurutan (belum bisa diverifikasi karena app belum bisa dijalankan):

1. **Format.** `AudioTrimmer` memakai `MediaExtractor` + `MediaMuxer` (MPEG-4) yang hanya menerima AAC/AMR. Rekaman webm/opus (dari web) atau mp3/ogg gagal. Perbaikan: bila sumber bukan m4a/AAC, **jangan potong di HP**; pakai potong di server (bagian 5.5) atau tampilkan pesan yang menjelaskan.
2. **Rekaman panjang.** Berkas ratusan MB di thread utama atau tanpa progres tampak "menggantung". Jalankan potong di `Dispatchers.IO`, di dalam WorkManager/foreground service dengan notifikasi progres, dan izinkan batal. Tampilkan persen berdasarkan sampel yang sudah ditulis.
3. **Sinkron setelah potong.** Rekaman hasil potong harus lolos alur unggah: hitung `sha256` setelah berkas final ditutup (bukan sebelum `muxer.stop()`), `durationSec` dari hasil nyata (bukan jumlah rentang yang diminta; server menghitung `round` pecahan), lalu `PUT /v1/recordings/{id}` setelah `tus` selesai (`422 blob_missing` bila belum selesai).
4. **Rentang pecahan detik.** Server dan web kini menerima rentang pecahan detik (≥ 0,2 dtk; dibulatkan milidetik). Pastikan app tidak membulatkan ke detik penuh hingga rentang berdempet jadi kosong.
5. Tangkap `IllegalStateException`/`IOException` dari muxer dan laporkan alasan nyata di layar (jangan "gagal" tanpa sebab); catat ke log lokal agar pemilik bisa mengirimkannya.

Server membantu: `POST /v1/recordings/{id}/trim` memotong di server dengan ffmpeg (semua format, presisi pecahan detik, banyak rentang). Ini jalan keluar untuk kasus 1 dan 2 (lihat 5.5).

---

## 5. Editor potong: samakan mekanisme dan tampilan dengan web

Ini menggantikan `TrimAudioDialog` satu-rentang dan melanjutkan `RENCANA_POTONG_MULTI_RENTANG.md` (rencana itu tetap berlaku untuk normalisasi rentang, penanda, dan pengujian; dokumen ini menambah desain tampilan baru dari web).

### 5.1 Layar dan tata letak (urut dari atas, satu kolom di HP)

1. **Bilah atas**: `Batal` (kiri), tombol **Urungkan** dan **Ulangi** (tengah, ikon panah melengkung), `Simpan sebagai…` (kanan, nonaktif sampai ada perubahan).
2. Judul rekaman, durasi asli, dan satu baris status draf ("Draf tersimpan 2 Nov 2026 14.30 WIB").
3. **Gelombang utama** (tinggi ±210 dp): penggaris waktu di atas, batang gelombang rapat (lebar 2 px, celah 1 px; bagian sunyi menjadi titik), kepala putar (garis + bulatan di atas dan bawah), penanda berupa bendera merah. Jendela waktu bergerak mengikuti kepala putar; ketuk atau geser = pindahkan kepala putar. Zoom jendela dengan tombol − dan + (15, 30, 60, 120, 300, 600 detik).
4. Waktu besar `mm:ss.d / total`.
5. **Tombol putar** (bulat gelap, besar) diapit **"Mulai di sini"** (kiri) dan **"Selesai di sini"** (kanan) yang mengatur awal/akhir pilihan di posisi putar.
6. **Ringkasan seluruh rekaman** (tinggi ±72 dp): gelombang kecil + bingkai pilihan merah dengan dua pegangan yang bisa diseret (area sentuh ≥ 40 dp), pita tipis di dasar penanda area (hijau = pertahankan, gelap = hapus), ketuk di luar bingkai = pindahkan kepala putar.
7. **Ringkasan hasil**: "Hasil: 06:00 dipertahankan dalam 3 bagian · 04:32 dihapus" dan "Pilihan 00:03 – 00:09 (00:06)".
8. **Tiga aksi** (kartu sejajar, ikon + label): **Deteksi hening**, **Pertahankan**, **Hapus**.
9. Panel **Hasil analisa hening** (hanya setelah Deteksi hening).
10. Daftar **Area bertanda** + pilihan "Bagian yang belum ditandai: Dipertahankan | Dihapus".
11. Bagian lipat "Atur waktu pilihan secara manual" (dua isian `mm:ss`, menerima `1:02:05` dan `23:10.5`).

Warna: tema yang sama dengan web (aksen `#C4260C`, tinta `#201E1D`, latar `#F3F2F2`, gelombang `#6B6666` terpilih / `#8A8585` lainnya / `#BDB8B8` bagian dihapus, bagian dihapus diarsir diagonal, area Pertahankan diwarnai hijau lembut, hasil hening biru lembut `rgba(43,108,176,.16)`).

### 5.2 Model data (satu sumber kebenaran)

```kotlin
enum class Mode { KEEP, CUT }
data class Region(val start: Double, val end: Double, val mode: Mode)
data class TrimModel(val regions: List<Region>, val uncovered: Mode)   // uncovered default KEEP
```
- Area **tidak pernah tumpang tindih**: `addRegion` memotong bagian area lama yang tertimpa, lalu menggabungkan area bersentuhan dengan jenis sama (toleransi 0,05 dtk).
- Bagian yang dipertahankan: bila `uncovered == CUT` → gabungan area `KEEP`; bila `KEEP` → seluruh rekaman dikurangi area `CUT`. Tolak aksi yang menyisakan nol bagian ("sisakan setidaknya satu bagian").
- **Urungkan/Ulangi**: riwayat snapshot `(TrimModel, pilihan)`; setiap tanda, buang, ubah, terapkan hasil hening, atau ganti "belum ditandai" adalah satu langkah.
- Setelah menandai, **pilihan otomatis pindah ke sisa rekaman setelah area itu** (`[end, durasi]`), jadi menandai berurutan cukup mengatur akhir pilihan lalu menekan tombol. Contoh pemilik: hapus 0:01–0:50, pertahankan 0:51–1:20, hapus 1:21–2:00, pertahankan 2:01–3:00, hapus 3:01–akhir (3:50) harus bisa dibuat dengan lima kali tandai.
- Pemutaran **melewati bagian yang dihapus** (loncat ke awal bagian dipertahankan berikutnya), seperti pratinjau hasil.
- Area dapat **Dengar** (putar dari awal area), **Ubah** (buang tanda dan pindahkan ke pilihan), **Buang**.

### 5.3 Deteksi hening: tampilkan hasil analisa, jangan langsung memotong

Perilaku lama ("langsung memotong") ditinggalkan. Alur baru:

1. Ketuk **Deteksi hening** → panggil `GET /v1/recordings/{id}/silence?noiseDb=-35&minSilenceSec=3` (`noiseDb`: −42 bersih, −35 normal, −28 berisik; `minSilenceSec`: 2, 3, 5, 10). Respons: `silences[{startSec,endSec}]`, `speech`, `speechSec`, `savedSec`. Untuk rekaman yang belum ada di server, hitung lokal (nilai bantalan sama).
2. Tampilkan **panel hasil analisa**: tiga kartu (jumlah bagian hening; total hening + persen dari rekaman; sisa durasi bila diterapkan), pengaturan jenis rekaman dan hening minimal dengan tombol **Analisis ulang**, daftar bagian hening (centang, `mm:ss – mm:ss`, lama dalam detik, tombol **Dengar** yang memutar 1 detik sebelum bagian itu), **Pilih semua / Kosongkan**.
3. Tombol akhir **"Tandai N bagian hening sebagai Hapus"**: tiap bagian terpilih menjadi area `CUT` dengan **bantalan 0,5 dtk dipertahankan di tiap sisi** (area = `[start+0,5, end−0,5]`; di tepi rekaman tanpa bantalan di sisi tepi; bagian < 0,5 dtk setelah bantalan dilewati). Pita biru pada gelombang menandai bagian terpilih sebelum diterapkan. Setelah diterapkan panel menutup dan satu langkah masuk riwayat.
4. Bagian hening yang sudah dihapus tetap bisa diubah/dibuang lewat daftar area.

### 5.4 Draf: simpan dulu, lanjutkan nanti

Sesi potong yang belum selesai disimpan di server per pengguna per rapat, jadi bisa dilanjutkan dari perangkat mana pun (web atau app).

- `GET /v1/recordings/{id}/trim-draft` → `{ draft: { state, updatedAt } | null }`
- `PUT /v1/recordings/{id}/trim-draft` body `{ state }` → `{ updatedAt }` (operator ke atas; 60 permintaan/menit)
- `DELETE /v1/recordings/{id}/trim-draft` → 204 (aman diulang)

`state` (skema `TrimDraftState`, bidang asing ditolak): `version: 1`, `regions[{start,end,mode:'keep'|'cut'}]` (maks 300), `uncovered`, `sel{start,end}`, `pos`, `zoomIdx` (0–10, indeks daftar zoom di atas), `analysis{noiseDb,minSilence,silences[{startSec,endSec,use}]} | null` (maks 2000 bagian), `title`, `kind`, `copyMarks`.

Perilaku UI (sama dengan web):
- Saat editor dibuka: ambil draf; bila ada, pulihkan (area, pilihan, posisi, zoom, hasil analisa, judul/kategori hasil) dan tampilkan "Melanjutkan draf yang disimpan … WIB" dengan tautan **Mulai dari awal** (menghapus draf).
- **Simpan otomatis** 1,5 detik setelah perubahan terakhir, dan saat app ke latar belakang (`onStop`). Tombol **Simpan draf sekarang**. Status: "Menyimpan draf…", "Draf tersimpan …", "Draf gagal tersimpan, coba lagi".
- **Batal**/kembali saat ada pekerjaan: dialog tiga pilihan **Lanjut mengedit / Buang / Simpan draf dan keluar**.
- Setelah **Simpan sebagai…** berhasil, hapus draf.
- Offline: simpan draf juga ke Room (antrean) lalu kirim saat tersambung; yang terbaru menang menurut `updatedAt`.
- Layar daftar/detail: tampilkan lencana **"Potong audio · draf"** pada rapat yang punya draf (belum ada di web; untuk keduanya, tahap berikut).

### 5.5 Menjalankan hasil potong

Kirim bagian yang **dipertahankan** (hasil `TrimModel`) ke `POST /v1/recordings/{id}/trim`:
```json
{ "ranges": [{"startSec": 51, "endSec": 80}, {"startSec": 121, "endSec": 180}], "title": "… (potongan)", "kind": "Musdes", "copyMarks": true }
```
Rentang boleh pecahan detik (≥ 0,2 dtk, dibulatkan milidetik), dinormalisasi server (urut, gabung tumpang tindih). Hasil: rapat baru berstatus belum diproses, penanda yang masuk ikut (digeser), audio asli tidak berubah. Pemotongan di HP (`AudioTrimmer.trimRanges`) tetap boleh untuk m4a/AAC yang ada lokal dan tanpa sinyal; selebihnya pakai endpoint ini.

### 5.6 Gelombang

`GET /v1/recordings/{id}/waveform` → `{binsPerSec: 20, bins, peaks: base64}`; byte berselang-seling `[puncak, RMS]` per bin. **Rekaman panjang (berjam-jam)**: permintaan pertama bisa menjawab **`202 {status:"pending"}`** dengan `Retry-After: 3` selagi server menghitung (gelombang rekaman 6,3 jam butuh ±2 menit); ulangi tiap 3 detik (maks sekitar 10 menit), tampilkan "Memuat gelombang…". Setelah selesai di-cache di server dan bisa di-cache di app (kunci: `sha256` audio). Untuk rekaman lokal yang belum diunggah, hitung gelombang dari berkas di HP.

Cara menggambar tinggi batang (samakan agar tampilan serupa): `nilai = max(puncak·0,55, rms·1,9)`; acuan = persentil 97 seluruh bin (minimal 8); `tinggi = min(1, nilai/acuan)^0,7`; di bawah 0,05 digambar titik 2 px.

### 5.7 Pengujian

Normalisasi/penggabungan area; `addRegion` menimpa sebagian (kiri, kanan, tengah, penuh); hasil dipertahankan untuk kedua mode `uncovered`; contoh lima area pemilik menghasilkan rentang `[51–80]`, `[121–180]` dan sebaliknya; pergeseran penanda; bantalan hening di tepi; pulih draf = state yang sama; Urungkan/Ulangi. Uji manual di HP: rekaman 10 menit, 6 jam, m4a dan webm, mode pesawat (draf lokal).

---

## 6. Foto bergeotag + logo (fitur baru)

Kebutuhan pemilik: foto yang diambil saat rapat memuat **geotag** (koordinat) dan bisa diberi **logo**; geotag juga **ikut tertulis di foto itu**.

### 6.1 Perilaku di app
1. **Izin lokasi** diminta saat pertama kali memakai foto (`ACCESS_FINE_LOCATION`, ditolak = foto tetap jalan tanpa geotag). Ambil titik sekali per foto (`FusedLocationProvider`, akurasi tinggi, batas waktu 5 detik, boleh memakai lokasi terakhir ≤ 60 detik bila akurasi ≤ 50 m); simpan `lat`, `lng`, `akurasi (m)`, waktu fix.
2. Setelah foto diambil, bangun **cap (watermark)** pada piksel foto:
   - Pita informasi di dasar foto: **logo** di kiri, lalu baris teks: tanggal dan jam **WIB (GMT+7)**, **koordinat** (`-7.12345, 110.12345`), akurasi, nama tempat bila tersedia (geocoder, opsional dan hanya bila tersambung), judul/kategori rapat.
   - Teks putih dengan bayangan atau latar semitransparan agar terbaca di foto terang/gelap; ukuran relatif lebar foto (±3,2%).
   - **Logo**: bawaan = logo IntelDes (persegi merah + tiga batang) bila pengguna tidak memilih; pengguna dapat **memasukkan logo sendiri** (PNG/JPG/WebP, maks 1 MB, disimpan per organisasi; pratinjau + tombol hapus) lewat Pengaturan. Logo diskalakan ke tinggi pita, rasio dijaga.
   - Sakelar di Pengaturan: **Geotag foto** (hidup/mati), **Tulis geotag pada foto**, **Pakai logo**.
3. Simpan **dua hal**: berkas JPEG ber-cap (yang diunggah sebagai foto rapat) **dan** metadata EXIF GPS di dalam JPEG (`ExifInterface`: `GPSLatitude/Longitude/Altitude/DateStamp`, `DateTimeOriginal` dengan zona), supaya geotag tetap terbaca pemutar galeri walau cap dipangkas. Berkas asli tanpa cap boleh disimpan lokal opsional (bukan diunggah).
4. Foto tetap mengikuti `takenAtSec` (offset detik di audio) seperti sekarang.

### 6.2 Kontrak server yang diusulkan (belum ada di server)
Server saat ini menyimpan foto dengan `id, blobSha256, takenAtSec, caption, createdAt, deletedAt` saja (`PhotoInput`). Agar geotag terstruktur bisa dicari/ditampilkan di web dan ekspor, usulan penambahan **bidang opsional** pada `PhotoInput`/`Photo`:
```
latitude:  number | null   (−90..90)
longitude: number | null   (−180..180)
accuracyM: number | null   (≥ 0)
placeName: string | null   (≤ 200)
```
Kompatibel mundur (opsional). Pekerjaan server (migrasi kolom, validasi, OpenAPI, tampilan peta/koordinat di web, ekspor DOCX/PDF memuat koordinat) akan dikerjakan di inteldes-web **setelah** app siap mengirimnya; beri tahu lewat repo ini bila format di atas perlu diubah. Sebelum itu, app cukup menaruh koordinat di cap foto dan di EXIF, dan boleh menambahkan ringkasan koordinat ke `caption` agar terlihat di web.

Privasi: koordinat adalah data sensitif; sakelar default **mati**, jelaskan di layar izin bahwa koordinat ikut tersimpan di foto yang bisa dibagikan lewat tautan publik, dan jangan menyimpan koordinat bila izin ditolak.

---

## 7. Catatan lain
- Web kini punya sidebar yang bisa disembunyikan (hamburger), tombol layar penuh, dan arsip HP dengan filter ringkas (kategori tersembunyi sampai tombol **Filter** dibuka). Untuk app: daftar Arsip sebaiknya serupa (pencarian + tombol Filter yang memuat kategori dan status; kartu padat dengan judul, lalu kategori · sumber · durasi, status di kanan).
- Semua jam ditampilkan **WIB (GMT+7)**, juga pada penanda dan label foto.
- Setelah mengerjakan, perbarui `CATATAN_PERUBAHAN_APP.md` dan beri tahu server bila ada kontrak yang perlu diubah.
