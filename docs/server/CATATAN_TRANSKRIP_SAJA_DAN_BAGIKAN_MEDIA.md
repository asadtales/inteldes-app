# Transkrip saja, bagikan foto/video (server, November 2026)

Untuk pengembang inteldes-app. Spesifikasi lengkap ada di `openapi.snapshot.yaml` (sudah diperbarui).

## 1. Transkrip saja

- Endpoint baru `POST /v1/recordings/{id}/transcribe` (operator ke atas, badan sama dengan `/process` dan boleh kosong). Server mentranskripsi lalu berhenti; status rapat kembali `RAW`, `errorMessage` kosong, transkrip tersimpan. Setelah itu `/regenerate` menyusun notulensi dari transkrip yang sudah disunting.
- Hanya untuk rapat CLOUD yang audionya ada di server. Galat: `422 nothing_to_transcribe`, `422 secret_missing` (hanya kunci Deepgram), `409 busy`, `429` kuota.
- Dihitung sebagai satu pemrosesan pada kuota.
- Saran untuk app: pada layar Buat notulensi tambahkan pilihan "Transkrip saja (sunting dulu)"; setelah selesai, buka penyunting transkrip. Jangan memanggil `/process` untuk rapat yang sudah punya transkrip hasil sunting: itu mentranskripsi ulang dan menimpa sunting. Pakai `/regenerate`.

## 2. Bagikan foto/video

- `POST /v1/recordings/{id}/shares` punya kolom baru: `includeNotes` (bawaan true), `includePhotos`, `includeVideos` (bawaan false), `mediaIds` (daftar id foto/video; hanya itu yang dibagikan). Tautan lama tanpa kolom ini tetap sama. `ShareLink` di respons memuat kolom yang sama dan `mediaCount`.
- Halaman publik `/s/<token>` menampilkan galeri foto dan pemutar video; tidak perlu login.
- Untuk app: tombol Bagikan pada sebuah foto/video memanggil endpoint di atas dengan `mediaIds: [id]`, `includeNotes: false`, `includeAudio: false`, lalu menyalin `url` ke pengirim (share sheet).

## 3. Lihat dan unduh

- Berkas foto/video diambil lewat `GET /v1/blobs/{sha256}/url` lalu `GET /v1/blobs/{sha256}?t=...`; tambahkan `&dl=1` bila ingin disimpan (Content-Disposition attachment).
