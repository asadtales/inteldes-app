# Panduan kirim ulang rekaman HP ke server (setelah penataan ulang 5 November 2026)

Untuk pemilik dan pengelola. Disusun dari kode app 2.5.2 (`WebSyncEngine`, `WebSyncWorker`, `WebSyncStore`) dan keadaan server setelah penataan. Belum diuji di HP sungguhan.

## 1. Keadaan sekarang

| Akun | Organisasi sekarang | Isi arsip di server |
|---|---|---|
| `desatales.kdr@gmail.com` (2 HP) | Desa tales, admin | kosong |
| `gimanfathur@gmail.com` (3 HP) | Desa tales, operator | kosong |
| `ghenergi88@gmail.com` (1 HP) | akun pribadi "ghenergi" | kosong |
| `asadtales@gmail.com` (2 HP) | IntelDes Platform (superadmin, tanpa arsip kerja) | tidak ada arsip |

Arsip Desa tales dikosongkan (65 rapat, 82 berkas). Berkasnya masih ada di folder cadangan server selama 14 hari.

## 2. Jaminan: HP tidak menghapus apa pun

- App tidak pernah menghapus rekaman lokal karena perubahan di server. Penghapusan dari server/web dihitung lalu **diabaikan** (komentar kode: "NOTHING here deletes a local recording").
- Pengosongan arsip di server tidak mengirim perintah hapus ke HP (tidak ada penanda hapus di umpan perubahan), jadi tidak ada yang bisa memicu penghapusan di HP.
- Saat akun HP berpindah organisasi, app **berhenti dan bertanya** dulu; tidak ada yang dikirim atau diubah sebelum Anda memilih.

## 3. Langkah per HP

Buka app → Pengaturan → **Akun & sinkron web**. Di HP yang organisasinya berubah muncul pesan "Perlu konfirmasi: akun berbeda dari yang terakhir dipakai" dengan tiga pilihan:

| Pilihan di app | Artinya |
|---|---|
| **Kirim juga ke organisasi/akun ini** | Status sinkron lama dihapus dan **semua rekaman HP dikirim ulang sebagai rekaman baru** ke arsip tujuan. Rekaman di HP tidak disentuh. |
| **Hanya ambil dari server (disarankan)** | HP hanya menerima dari server, tidak mengirim apa pun. Aman bila rekaman HP bukan milik arsip ini. Nanti bisa kirim lewat tombol "Kirim rekaman HP juga…". |
| **Tidak, matikan sinkron** | Sinkron web dimatikan. |

Pilih menurut milik siapa rekaman di HP itu:

- **`desatales.kdr` (2 HP)**: bila rekaman di HP memang untuk Desa tales, pilih **Kirim juga ke organisasi ini**. Bila isinya bukan milik Desa tales, pilih **Hanya ambil**.
- **`ghenergi88` (1 HP)**: bila rekaman di HP itu milik pribadi ghenergi, pilih **Kirim juga ke akun pribadi ini**. Bila bukan, pilih **Hanya ambil**.
- **`asadtales` (2 HP)**: organisasi platform tidak punya arsip kerja, jadi pilih **Hanya ambil** atau **Matikan sinkron**. Untuk menyinkronkan rekaman, masuk dengan akun anggota Desa tales atau akun pribadi.
- **`gimanfathur` (3 HP)**: organisasinya **tidak berubah**, jadi tidak ada pesan konfirmasi dan **tidak ada kirim ulang otomatis**. Menurut audit server, akun ini tidak pernah mengirim rapat ke Desa tales, jadi tidak ada data yang hilang untuknya.

Setelah memilih "Kirim juga": tekan **Sinkronkan sekarang**, tunggu, lalu lihat kartu "Terkirim N · Diterima N · Gagal N". Rekaman panjang atau berkas besar bisa memakan waktu; biarkan HP terhubung ke internet.

## 4. Memeriksa hasilnya

- Di web: Arsip akun itu menampilkan rapat yang terkirim. Superadmin bisa melihat jumlah rapat per organisasi di menu Organisasi (isi arsip tidak bisa dibaca dari sana).
- Bila "Gagal" > 0, pesan pertama tampil di kartu sinkron. Penyebab yang umum: tidak ada sinyal, kuota paket tercapai, atau peran Pembaca (tidak boleh mengirim).

## 5. Yang perlu diperhatikan

- **Jangan** memilih "Kirim juga" untuk arsip yang salah: data dua arsip tercampur dan tidak bisa ditarik kembali lewat app.
- Pada HP yang organisasinya tidak berubah (mis. `gimanfathur`), rekaman yang HP anggap "sudah tersinkron" **tidak akan dikirim ulang** sampai rekamannya berubah, karena app hanya mengirim bagian yang berubah. App belum punya tombol "Kirim ulang semua"; ini sudah diminta ke pengembang app (bagian 10 di `RENCANA_PERBAIKAN_APP_2026-11.md`).
- Bila suatu hari rekaman perlu dikembalikan dari cadangan server (maksimal 14 hari setelah dihapus), minta pengelola server memulihkannya; jangan mengandalkan app.
