# Menyiapkan sinkronisasi Google Drive (sekali, oleh pengembang)

Sinkronisasi memakai login Google bawaan Android. Agar Google mau menerima aplikasi ini,
aplikasi harus didaftarkan **sekali** di Google Cloud. Setelah itu, setiap pengguna / perangkat
cukup menekan **Pengaturan → Sinkronisasi Google Drive → Hubungkan** dan memilih akun Google-nya
sendiri. Tidak ada kunci rahasia di dalam aplikasi.

1. Buka https://console.cloud.google.com → buat project (mis. `IntelDes`).
2. **APIs & Services → Library** → aktifkan **Google Drive API**.
3. **APIs & Services → OAuth consent screen**
   - User type: *External*, isi nama aplikasi (INTELDES) dan email.
   - **Scopes**: tambahkan `.../auth/drive.file` (hanya berkas yang dibuat aplikasi ini).
   - Selama status *Testing*, tambahkan email pengguna di **Test users** (maks. 100).
     Untuk dipakai umum, klik **Publish app**; scope `drive.file` tidak memerlukan verifikasi berat.
4. **APIs & Services → Credentials → Create credentials → OAuth client ID**
   - Application type: **Android**
   - Package name: `com.inteldes.app`
   - SHA-1: sidik jari kunci penandatangan APK.
     - Build debug (mesin ini): `F8:8A:3F:A8:C6:10:58:3B:A3:8F:E7:27:23:06:5A:49:78:89:D6:31`
     - Build rilis: buat client kedua dengan SHA-1 keystore rilis
       (`keytool -list -v -keystore <keystore> -alias <alias>`).
5. Selesai. Tidak perlu menyalin Client ID ke kode — Google mencocokkan package + SHA-1.

Jika tombol *Hubungkan* menampilkan "Aplikasi belum terdaftar di Google Cloud", langkah 4
belum cocok (package atau SHA-1 berbeda dengan APK yang terpasang).

## Cara kerja singkat

- Folder **INTELDES** dibuat di Drive pengguna. Isi: `rec_<id>.json` (data rekaman), `aud_<id>.<ext>`
  (audio), `pho_<id>__<hash>.jpg` (foto), `categories.json`, `deleted.json`.
- Dua arah: rekaman baru di satu perangkat muncul di perangkat lain. Jika sama-sama diubah,
  perangkat yang sedang menyinkronkan menang.
- Menghapus rekaman ikut menghapusnya dari Drive dan perangkat lain (lewat `deleted.json`).
- Riwayat versi notulensi, obrolan Tanya AI, dan template buatan sendiri belum disinkronkan.
