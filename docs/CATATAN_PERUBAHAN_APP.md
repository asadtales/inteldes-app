# Catatan perubahan app (belum di-commit) — 1 November 2026

> Dokumen ini di-push lebih dulu supaya tim web bisa membacanya. **Kode yang dijelaskannya belum ada di GitHub**; menyusul setelah diuji di HP.

Semua di bawah ada di working tree `D:\project\inteldes-app`, **belum di-commit dan belum di-push**. Tes unit: **128 lolos**. Belum satu pun diuji di HP asli (HP tidak tersambung saat pengerjaan); apa yang sudah dan belum terbukti ditulis per butir.

Rencana dan peta kesetaraan: `docs/RENCANA_KESETARAAN_WEB_APP.md`. Yang harus dikerjakan di server: `docs/server/DAFTAR_KERJA_SERVER.md`.

## 1. Perubahan per fitur

### 1.1 Kunci AI dicabut dari APK (tahap 0)
- `app/build.gradle.kts`: `EMBEDDED_GEMINI_KEY`/`CLAUDE`/`DEEPGRAM` dihapus total.
- `SecurePrefs`: `hasEmbedded*` dihapus; `effective*Key` hanya memakai kunci yang diketik pengguna.
- `SettingsScreen`/`SettingsViewModel`: tidak ada lagi "sudah tertanam"; kolom kunci selalu tampil dengan keterangan "hanya untuk memproses langsung dari HP".
- **Dampak:** build ini tidak bisa memproses AI dari HP sebelum kunci diketik, atau sebelum memakai proses lewat server.

### 1.2 Potong audio (tahap 1, 3.1, 3.2)
- `TrimPlan.kt`: aritmetika murni rentang (gabung, kurangi, mode hapus/simpan, geser penanda, judul bawaan).
- `AudioTrimmer.kt`: `trimRanges` banyak rentang tanpa sandi ulang; keluaran MP4 (AAC/AMR) atau WebM (Opus/Vorbis); `outputFor`.
- `AudioFormats.kt`: tabel jenis audio dan normalisasi nama jenis (dipakai juga saat unggah blob).
- `SilenceDetector.kt`: deteksi hening di HP (RMS 50 ms, bacaan 10 menit sekali).
- `TrimEditor.kt` (menggantikan `TrimAudioDialog.kt`): mode hapus/simpan, garis waktu, bagian antar penanda, deteksi hening, langkah 2 (nama, template, mesin, proses sekarang atau simpan).
- `TrimDraft.kt`: draf potong tersimpan otomatis per rekaman; tombol "Simpan draf & tutup".
- `DetailViewModel.trimOnServer`: mp3/wav dipotong di server (kirim, `POST /trim`, tarik hasilnya).
- `RecordingRepository.createTrimmedRecording`: banyak rentang, ekstensi/jenis mengikuti sumber.

### 1.3 Penanda setara web (tahap 2)
- Ubah nama penanda (id web dikunci dulu agar tidak jadi penanda ganda), "Potong dari sini", bendera ⚑ di transkrip.
- Sinkron: ubah nama dari web turun ke HP; bentrok → HP menang (`WebSyncEngine.keptMarks`, `agreedMarks`, `RoomWebLocal.mergeChildren`).

### 1.4 Konteks
- Satu rekaman bisa dikeluarkan dari konteks (✕ di chip sumber); bagian teksnya ikut dihapus (`ContextBuilder.withoutSection`).

### 1.5 Sinkron terpilih
- `SyncExclusions.kt`: rekaman yang dikeluarkan dari sinkron (Drive dan web). Daftar centang di Pengaturan ("Rekaman yang ikut sinkron").

### 1.6 Sinkron template dan konteks dua arah (tahap 6)
- `WebLibrarySync.kt` ditulis ulang: edit dari HP terkirim, edit web turun bila HP tidak mengubah, hapus dari HP menghapus di server, hapus di web diabaikan. Status kesepakatan di `WebSyncStore.library`.
- Urutan chat `orderIndex, createdAt, id`. `Retry-After` pada 429 (`RetryAfterInterceptor`).
- Migrasi: `app/schemas/.../7.json` ditambahkan; `tools/verify_migration.py` (terbukti di SQLite); `MigrationTest` instrumentasi (belum dijalankan).

### 1.7 Proses AI berdampingan (tahap 5)
- Kartu "Kunci AI di server" (hanya baca) dari `GET /v1/settings`.
- Pilihan "Diproses di: HP ini / Server IntelDes" di dialog proses; otomatis ke server bila HP tidak punya kunci. `ServerProcessor`, `ServerProcessWorker`, `ServerJobs`.

### 1.8 Ekspor PDF
- `PdfLayout.kt` (tata letak murni, teruji) dan `PdfExporter.kt` (`PdfDocument`); tombol "Ekspor PDF" di tab Notulensi.

### 1.9 Tautan bagikan (tahap 4)
- `ShareLinkDialog.kt`, `ShareModels.kt`, `WebApi.createShare/listShares/revokeShare`, `DetailViewModel.openShare/createShare/revokeShare`, tombol "Bagikan lewat tautan" di tab Notulensi.
- Memilih: sertakan transkrip, audio, masa berlaku (7/30/90 hari, tanpa batas). Rekaman dikirim ke server lebih dulu bila perlu. Tautan tampil sekali (salin/bagikan); daftar tautan dengan tombol CABUT.
- Tidak butuh perubahan server (endpoint sudah ada); butuh sinkron web aktif.

### 1.10 Login Google dan portal SSO (tahap 7)
- `AppLogin.kt` (PKCE, URL awal, pembaca callback bertanda `state`, penyimpanan verifier 10 menit), intent-filter `inteldes://auth/callback`, `AccountService.providers/beginAppLogin/exchangeAppLogin`, tombol di `LoginScreen`, konfirmasi akun lewat layar Claim.
- **Tombol tidak tampil sampai server mengaktifkan `appLogin`** (`DAFTAR_KERJA_SERVER.md` A1).
- Tidak ada client id/secret di app.

## 2. Status pembuktian

| Hal | Bukti |
|---|---|
| Logika murni (potong, deteksi hening, PDF, PKCE, draf, sinkron) | tes unit (128) |
| Sinkron penanda/template/konteks, proses server, bagikan | server **palsu** (MockWebServer/fake) |
| SQL migrasi v7→v9 | `tools/verify_migration.py` di SQLite nyata |
| Potong audio nyata, WebM, deteksi hening di rekaman asli, tampilan PDF, dialog bagikan, login browser, draf | **belum diuji di HP** |
| Semua dengan server nyata | **belum** (login app, paket, proses server perlu server baru) |

## 3. Cara menguji di HP (setelah tersambung)

1. Cadangkan HP (db + files) lalu pasang `inteldes_uji.apk`.
2. `gradlew :app:connectedDebugAndroidTest` (migrasi di perangkat).
3. Potong 3 rentang dari m4a; putar penuh; ukur dengan ffmpeg. Ulangi dengan rekaman WebM dari web.
4. Deteksi hening pada rekaman rapat asli; cek hasilnya wajar dan bisa diurungkan.
5. Tab Notulensi: Ekspor PDF, buka di pembaca PDF; cek foto dan nomor halaman.
6. Ubah nama penanda di HP lalu di web; sinkron; cek tidak ada penanda ganda.
7. Bagikan lewat tautan (perlu sinkron web aktif): buat, buka tautan di browser lain, cabut, buka lagi.
8. Setelah server siap: login Google/SSO dan proses lewat server.

## 4. Saran pembagian commit (repo inteldes-app)

Urut; masing-masing hijau di `testDebugUnitTest`. Jangan menambahkan `real_inteldes.db`, `real_prefs.bin`, `*.apk` (jangan `git add -A`; tambahkan berkas per daftar).

1. **`feat: cabut kunci AI dari APK`** — `app/build.gradle.kts`, `SecurePrefs.kt`, `SettingsScreen.kt`, `SettingsViewModel.kt`.
2. **`feat: potong audio banyak rentang, deteksi hening, draf, WebM`** — `data/audio/{TrimPlan,AudioTrimmer,AudioFormats,SilenceDetector,TrimDraft,AudioPlayerManager}.kt`, `ui/components/{TrimEditor.kt (baru), TrimAudioDialog.kt (hapus), EnginePickerDialog.kt}`, `RecordingRepository.kt`, `DetailScreen.kt`, `DetailViewModel.kt` (bagian potong), `IntelDesApp.kt` (trimDrafts), tes `data/audio/*`.
3. **`feat: penanda setara web (ubah nama, potong dari sini, bendera di transkrip)`** — `Daos.kt` (rename/get), `WebSyncEngine.kt`, `RoomWebLocal.kt`, `DetailScreen.kt`/`DetailViewModel.kt` (bagian penanda), tes sinkron penanda.
4. **`feat: keluarkan sumber dari konteks`** — `ContextRepository.kt`, `ContextsViewModel.kt`, `ContextsScreens.kt`, `Daos.kt` (deleteSource/deleteSources).
5. **`feat: pilih rekaman yang ikut sinkron`** — `SyncExclusions.kt`, `SyncEngine.kt`, `WebSyncEngine.kt` (excluded), `SyncWorker.kt`, `WebSyncWorker.kt`, `SettingsScreen.kt`/`SettingsViewModel.kt`, `ViewModelFactory.kt`, `IntelDesApp.kt`.
6. **`feat: sinkron edit/hapus template dan konteks, urutan chat, Retry-After`** — `WebLibrarySync.kt`, `RoomWebLibrary.kt`, `WebApi.kt` (update/delete), `WebSyncStore.kt`, `RetryAfterInterceptor.kt`, `ServerApi.kt` (interceptor), `Daos.kt` (urutan chat), tes.
7. **`test: skema v7 dan uji migrasi`** — `app/schemas/.../7.json`, `app/src/androidTest/**`, `tools/verify_migration.py`, `app/build.gradle.kts` (runner, room-testing, assets), `IntelDesDatabase.kt` (`ALL_MIGRATIONS`).
8. **`feat: proses AI di server berdampingan + kartu kunci AI`** — `ServerProcessor.kt`, `ServerProcessWorker.kt`, `ServerJobs.kt`, `ServerModels.kt`, `ServerApi.kt` (`settings`), `WebApi.kt` (`processRecording`), `DetailScreen.kt`/`DetailViewModel.kt`, `EnginePickerDialog.kt`, `SettingsScreen.kt`, tes.
9. **`feat: ekspor PDF`** — `PdfLayout.kt`, `PdfExporter.kt`, `NotulensiExporter.kt`, `NotesTab.kt`, tes.
10. **`feat: tautan bagikan notulensi`** — `ShareLinkDialog.kt`, `ShareModels.kt`, `WebApi.kt` (shares), `DetailViewModel.kt`, `DetailScreen.kt`, `NotesTab.kt`, tes.
11. **`feat: login Google dan SSO lewat browser (PKCE), kredensial di server`** — `AppLogin.kt`, `AccountService.kt`, `AccountViewModel.kt`, `AccountScreens.kt`, `ServerApi.kt`, `MainActivity.kt`, `AppNavGraph.kt`, `AndroidManifest.xml`, `IntelDesApp.kt`, tes.
12. **`docs: rencana kesetaraan, permintaan server, snapshot openapi`** — `docs/**`, `tools/`.

Karena beberapa berkas disentuh banyak fitur (`DetailScreen.kt`, `DetailViewModel.kt`, `WebApi.kt`, `SettingsScreen.kt`), cara paling aman bila tidak ingin memecah per hunk: satu commit besar `feat: ...` dengan isi 1–12 ini di badan pesan. Pecah per berkas hanya untuk berkas yang berdiri sendiri.

Pesan commit diakhiri baris `Co-Authored-By` sesuai kebiasaan repo.

## 5. Urutan push dan deploy
1. Server dulu: A1/A2 di `DAFTAR_KERJA_SERVER.md`, deploy dengan `APP_LOGIN` mati, uji, lalu aktifkan.
2. App: commit dan push; bangun APK baru (naikkan `versionCode`); uji sesuai bagian 3.
3. Cut-over kunci (`CUTOVER_KUNCI_APK.md` bagian 3) hanya oleh pemilik: kunci baru ke server, kunci lama dicabut.
