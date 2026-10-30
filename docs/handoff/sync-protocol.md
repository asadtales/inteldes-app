# Protokol sinkron IntelDes (HP ↔ server ↔ web)

Menggantikan `SyncEngine` berbasis hash + Google Drive. Server adalah sumber kebenaran. Skema tabel: `data-model.sql`.

## Perilaku Drive yang berlaku sekarang (acuan, bukan target)
- Deteksi perubahan: SHA-256 JSON snapshot lokal vs `RecState.localHash`; md5 Drive vs `RecState.remoteMd5`.
- Bentrok: perangkat yang sedang sinkron menang (cek `localChanged` lebih dulu). Tidak ada `updatedAt`/versi.
- Hapus: `deleted.json` + `pendingDeletes`, diterapkan paling awal, tombstone tidak pernah dibersihkan.
- Rapat berstatus `WORKING` dilewati.
- **Tidak ikut sinkron**: chat Tanya AI, riwayat notulensi, template custom, konteks, `errorMessage`, pengaturan, kunci API, hapus dan urutan kategori.
- ID anak (marks, photos, chat, history, sections) adalah autoincrement lokal → tidak stabil antar perangkat.

## Prinsip baru
1. Semua entitas punya UUID dari klien, `updated_at`, `version`, `deleted_at`.
2. Server menaikkan `server_seq` per perubahan (tabel `changes`). Klien menyimpan kursor `since`.
3. Klien offline-first: tulis ke Room + `outbox`, dorong saat ada jaringan (WorkManager, Wi-Fi only bisa dimatikan).
4. Penghapusan = soft delete, ikut `changes` sebagai `op=delete`. Tombstone boleh dibersihkan server setelah 90 hari **dan** semua perangkat aktif sudah melewati seq-nya.

## Alur klien
```
loop (SyncWorker):
  1. push: untuk tiap item outbox → PUT/POST dengan If-Match: <version>
       200 → simpan version baru, hapus dari outbox
       409 → ambil salinan server, jalankan aturan bentrok (di bawah), ulangi push
  2. pull: GET /v1/sync/changes?since=<cursor>  (halaman demi halaman)
       tiap upsert → ambil agregat → terapkan ke Room dalam satu transaksi
       tiap delete → hapus lokal (termasuk berkas audio/foto)
       simpan next_seq
  3. blob: unggah audio/foto yang belum ada (tus); unduh blob yang dibutuhkan saja (lazy)
```
Rapat `WORKING` di HP tidak dikirim. Saat server yang memproses, server pemilik status; HP hanya membaca.

## Aturan bentrok
| Bagian | Aturan |
|---|---|
| Metadata rapat (judul, kategori, lokasi, template) | Versi dengan `updated_at` terbaru menang. Yang kalah disimpan sebagai `notulensi_revisions` (reason=`conflict`) hanya bila ia mengubah transkrip/notulensi; untuk metadata cukup dicatat di `audit_log`. |
| Transkrip dan bagian notulensi | Satu kumpulan per rapat, versi `transcript_version` / `notes_version`. Terbaru menang; yang kalah masuk `notulensi_revisions`. |
| Penanda, foto | Gabung per UUID (union). Hapus mengalahkan edit bila `deleted_at` lebih baru. |
| Chat Tanya AI, riwayat notulensi | Append-only, gabung per UUID. |
| Kategori, template, konteks | Per entitas, terbaru menang. Hapus kategori memindahkan rapat ke "Lainnya" di server. |
| Pengaturan | Level organisasi di server; preferensi per perangkat (mis. Wi-Fi only) tetap lokal. |

## Blob
- Kunci isi: `sha256`. Foto dedupe otomatis. Audio disimpan sekali per rapat.
- Unggah: tus (resumable), potongan 5–8 MB. Unduh: URL bertanda tangan pendek (5–15 menit); pemutar web memakai `Range`.
- Audio boleh dihapus server menurut kebijakan retensi (`org_settings.settings.audioRetentionDays`); metadata `audio_blob_sha256` dikosongkan dan klien menampilkan "audio dihapus".

## Hal yang harus ditangani di Android (DB v8)
- Tambah `uuid` ke `recording_marks`, `photos`, `chat_messages`, `notulensi_history`, `note_sections`, `transcript_segments` (diisi `UUID.randomUUID()` saat migrasi).
- Tambah `updatedAt`, `version`, `deletedAt`, `syncState` ke recordings/categories/templates/knowledge_contexts.
- Tabel `outbox(id, entity, entityId, op, createdAt, attempts)`.
- Tulis `Migration7To8` nyata; jangan mengandalkan `fallbackToDestructiveMigration`.
- Pertahankan `RoomSyncLocal.snapshot/apply` sebagai pembentuk agregat; ganti pembanding hash dengan versi dari server.
- Port `SyncEngineTest.kt` menjadi uji kontrak terhadap server (lihat bagian Verifikasi di dokumen perencanaan).
