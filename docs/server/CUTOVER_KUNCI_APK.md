# Cut-over: kunci AI tidak lagi tertanam di APK

Untuk pemilik dan pengembang Android. Status: **perubahan build sudah dibuat** (`app/build.gradle.kts`); **langkah pemilik (ganti dan cabut kunci) belum dilakukan**; verifikasi APK dan pembersihan kode menunggu pengembang Android.

## 1. Masalahnya

`app/build.gradle.kts` dulu membaca `GEMINI_API_KEY`, `CLAUDE_API_KEY`, dan `DEEPGRAM_API_KEY` dari berkas `.env` lalu menanamnya ke `BuildConfig` (`EMBEDDED_GEMINI_KEY`, `EMBEDDED_CLAUDE_KEY`, `EMBEDDED_DEEPGRAM_KEY`). `SecurePrefs.effective*Key` memakai kunci tertanam itu lebih dulu.

Akibatnya setiap APK yang dibangun dari mesin dengan `.env` berisi kunci **membawa kunci asli di dalamnya**. APK bisa dibongkar (jadx, `strings`, apktool) dalam hitungan menit oleh siapa pun yang memegang berkasnya, dan kunci terbaca polos. Anggap **semua APK yang pernah dibagikan dari build seperti itu sudah bocor**, termasuk yang dikirim lewat chat atau Drive. Menghapus kunci dari build berikutnya saja tidak cukup: kunci lama harus **diganti dan dicabut**.

Dengan model akun baru, kunci AI dipegang server (kunci IntelDes dengan kuota, atau kunci milik akun itu sendiri); app tidak perlu lagi membawa kunci.

## 2. Yang sudah diubah di repo

`app/build.gradle.kts`:
- Pembacaan `.env` dan fungsi `buildConfigString` dihapus.
- `EMBEDDED_GEMINI_KEY`, `EMBEDDED_CLAUDE_KEY`, `EMBEDDED_DEEPGRAM_KEY` sekarang konstanta kosong (`""`). Field dipertahankan hanya supaya kode lama (`SecurePrefs.hasEmbedded*`, `effective*Key`, `SettingsViewModel`) tetap terkompilasi tanpa perubahan Kotlin. Efeknya: `hasEmbedded*` selalu `false` dan `effective*Key` hanya memakai kunci yang diketik pengguna di Pengaturan.

Belum dikompilasi di server ini; pengembang Android perlu menjalankan build dan uji (lihat bagian 5).

## 3. Langkah pemilik (urut; tidak bisa dilakukan oleh kode)

1. **Buat kunci BARU** di tiap penyedia, jangan memakai ulang yang lama:
   - Deepgram: console.deepgram.com, API Keys, buat kunci baru (beri nama mis. `inteldes-server`).
   - Gemini: aistudio.google.com/apikey, buat kunci baru (idealnya proyek terpisah agar bisa diberi batas kuota/anggaran).
   - Claude: console.anthropic.com, API Keys, buat kunci baru, dan setel batas pengeluaran bulanan.
2. **Pasang kunci baru di server**, bukan di app: web IntelDes, Pengaturan, Kunci AI, pilih "Kunci API sendiri" pada organisasi penyedia (Desa tales), isi ketiga kunci. Akun pribadi lalu memakainya lewat "Kunci IntelDes" dengan kuota harian.
3. Bangun **APK rilis baru** dari kode ini (naikkan `versionCode`), verifikasi tanpa kunci (bagian 4), lalu bagikan.
4. **Cabut kunci lama** di console tiap penyedia. Waktunya: segera bila APK lama sudah beredar luas atau tersebar ke pihak luar; kalau masih terkendali, setelah semua pengguna memperbarui. Konsekuensinya jelas dan memang tujuannya: APK lama yang mengandalkan kunci tertanam berhenti memproses. Beri tahu pengguna agar memperbarui dan masuk ke server.
5. Pantau tagihan/pemakaian ketiga penyedia selama sekitar seminggu terhadap pemakaian yang tidak dikenal dari kunci lama sebelum dicabut.
6. Pastikan `.env` lama berisi kunci lama dihapus atau dikosongkan dari mesin pengembangan; kunci lama tidak boleh disimpan di mana pun setelah dicabut.

## 4. Verifikasi APK sebelum dibagikan

Jalankan terhadap APK rilis (`app-release.apk`) yang akan dibagikan:

```powershell
# a) tidak ada pola kunci di kode terkompilasi (hasil harus kosong)
mkdir apkcheck; Expand-Archive app-release.apk apkcheck -Force
Get-ChildItem apkcheck -Filter classes*.dex | ForEach-Object { strings $_.FullName } | Select-String -Pattern 'AIza[0-9A-Za-z_-]{30,}','sk-ant-[A-Za-z0-9_-]{20,}'
```

```bash
# b) konstanta BuildConfig kosong (dengan jadx): harus tampil = ""
jadx -d out app-release.apk && grep -rn "EMBEDDED_" out | head
# c) riwayat git tidak pernah memuat kunci (hasil harus kosong)
git log --all -p | grep -E "AIza[0-9A-Za-z_-]{30,}|sk-ant-[A-Za-z0-9_-]{20,}"
```

Kunci Deepgram berupa heksadesimal 40 karakter dan tidak punya awalan khas; untuk kunci itu andalkan pemeriksaan (b) dan pastikan tidak ada nilai heksadesimal panjang di `BuildConfig`/`strings.xml`.

## 5. Tugas pengembang Android

1. Bangun debug dan rilis; jalankan uji unit dan instrumentasi yang ada. Pastikan build berhasil tanpa `.env`.
2. Uji jalur **tanpa kunci**:
   - Sudah masuk ke server: pemrosesan lewat server berjalan (`POST /v1/recordings/{id}/process`); tidak ada permintaan kunci.
   - Belum masuk dan tanpa kunci lokal: app menampilkan pesan jelas ("masuk ke server atau isi kunci di Pengaturan"), tidak galat samar atau crash.
   - `SettingsViewModel`: `geminiEmbedded/claudeEmbedded/whisperEmbedded` bernilai `false`, jadi kolom kunci tampil normal.
3. Pembersihan kode (setelah cut-over stabil): hapus `EMBEDDED_*` dari `build.gradle.kts`, hapus `SecurePrefs.hasEmbedded*`, ubah `effective*Key` menjadi hanya kunci dari `securePrefs`, dan rapikan `SettingsScreen`. Selaraskan dengan `CATATAN_SUMBER_KUNCI_AI.md` (kartu kunci baca-saja bila masuk ke server).
4. Pencegahan: jangan pernah menaruh `*_API_KEY` di `.env` untuk build app; bila diperlukan untuk eksperimen lokal, jangan bangun APK yang dibagikan dari mesin itu.
5. Sebelum merilis: pasang pemeriksaan pada langkah rilis (skrip atau CI) yang menjalankan perintah bagian 4 (a) dan gagal bila ada hasil.

## 6. Pemulihan

Perubahan build ini tidak perlu dipulihkan; mengembalikan pembacaan `.env` justru membuka kebocoran lagi. Bila ada kebutuhan pemrosesan tanpa server untuk pengguna tertentu, pakai kunci yang mereka isi sendiri di Pengaturan app.
