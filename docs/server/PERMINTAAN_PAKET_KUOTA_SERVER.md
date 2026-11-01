# Permintaan untuk web: paket dan kuota pemrosesan AI di server

Dari pengembang app (inteldes-app) ke pengembang web (inteldes-web). Ditulis 1 November 2026.

## 1. Keputusan produk (dari pemilik)

- Pemrosesan AI **berdampingan**: app tetap bisa memproses langsung dari HP dengan kunci API yang diisi pengguna sendiri. Bila pengguna **tidak mengisi kunci** (Deepgram, Gemini/Claude), app menawarkan **proses lewat server** yang memakai kunci milik server.
- Pemakaian kunci server harus **dibatasi**, baik **menit audio** maupun **intensitas** (jumlah pekerjaan).
- Pembatasan memakai **paket** yang bisa **dibuat dan diatur oleh superadmin**, lalu dipasang ke organisasi atau akun pribadi.

## 2. Yang sudah dikerjakan di app (menunggu server)

App sudah memakai kontrak yang ada sekarang:
- `POST /v1/recordings/{id}/process` (opsi: provider, density, language, economy, templateId), lalu memantau status rapat sampai bukan `WORKING`, lalu menarik hasil lewat sinkron biasa.
- `GET /v1/settings` → `aiAccess` untuk kartu "Kunci AI di server" (hanya baca).
- Kegagalan `429 quota_exceeded`, `422 secret_missing` dll. ditampilkan apa adanya (pesan bahasa Indonesia dari server dipakai langsung, tanpa coba ulang otomatis).

Batas yang ada sekarang di server (per 24 jam per organisasi: `AI_DAILY_JOBS_*`, `AI_DAILY_ASKS_*`) **tidak menghitung menit audio** dan tidak bisa diatur per organisasi. Itu yang diminta diperluas.

## 3. Yang diminta dari server

### 3.1 Paket (dibuat superadmin)

Tabel `plans`:

| Kolom | Arti |
|---|---|
| `id`, `name`, `description` | identitas paket |
| `period` | `day` \| `week` \| `month`: jendela hitung kuota |
| `audioMinutes` | batas menit audio yang boleh ditranskripsi per periode (`null` = tanpa batas) |
| `jobs` | batas jumlah pemrosesan (`process`/`regenerate`) per periode |
| `asks` | batas pertanyaan AI (Tanya AI, konteks) per periode |
| `maxRecordingMinutes` | batas panjang satu rekaman yang boleh diproses |
| `allowedProviders` | mis. `["GEMINI","CLAUDE"]`; kosong = semua |
| `active` | paket nonaktif tidak bisa dipasang baru |

Pemasangan: `organizations.planId` (nullable). Aturan bawaan bila kosong: paket bawaan sistem untuk akun pribadi dan untuk organisasi tim (atau nilai `AI_DAILY_*` yang sekarang, agar tidak ada perubahan perilaku sebelum superadmin mengatur apa pun).

### 3.2 Pencatatan pemakaian

Tabel pemakaian per organisasi per pekerjaan: `orgId`, `recordingId`, `kind` (`process`/`regenerate`/`ask`), `audioSeconds` (durasi audio yang benar-benar dikirim ke Deepgram), `at`. Kuota dihitung dari jumlah di jendela periode paket. Hanya pekerjaan yang memakai **kunci server** dihitung; organisasi yang memakai kunci sendiri (`aiKeySource = own`) tidak terkena paket.

Pengecekan **sebelum** mengantre: bila `audioMinutes` tersisa lebih kecil dari durasi rekaman, tolak seketika (jangan terima lalu gagal di tengah).

### 3.3 API superadmin

- `GET /v1/admin/plans`, `POST /v1/admin/plans`, `PUT /v1/admin/plans/{id}`, `DELETE /v1/admin/plans/{id}` (tolak hapus bila masih dipakai).
- `PUT /v1/admin/orgs/{orgId}/plan` `{ "planId": "..." | null }`.
- `GET /v1/admin/orgs` dengan ringkasan pemakaian per organisasi (menit terpakai/batas, pekerjaan terpakai/batas).
- Halaman web superadmin untuk ketiganya.

### 3.4 Kontrak yang dibaca app

`GET /v1/settings` → `aiAccess` ditambah (semua opsional agar app lama tidak rusak):

```json
"aiAccess": {
  "source": "platform",
  "plan": { "id": "p_basic", "name": "Paket Dasar" },
  "minutes": { "used": 42, "limit": 120 },
  "jobs":    { "used": 3,  "limit": 20 },
  "asks":    { "used": 5,  "limit": 50 },
  "resetsAt": 1793600000000
}
```

Galat kuota yang membedakan sebabnya, dengan `error.details` berisi `limit`, `used`, `resetsAt`:
- `429 quota_minutes_exceeded`: menit audio habis (atau rekaman lebih panjang dari sisa).
- `429 quota_jobs_exceeded`: jumlah pekerjaan habis.
- `403 plan_provider_not_allowed`: provider yang diminta tidak ada di paket.
- `422 recording_too_long`: melewati `maxRecordingMinutes`.

Pesan `error.message` sudah dalam bahasa Indonesia dan memuat kapan kuota pulih; app menampilkannya apa adanya.

## 4. Urutan yang disarankan

1. Tabel `plans`, kolom `organizations.planId`, pencatatan `audioSeconds` di jalur `process`.
2. Pengecekan menit dan jumlah pekerjaan di `process`/`regenerate`/`ask`, kode galat di atas.
3. Perluas `aiAccess`.
4. API dan halaman superadmin.

App akan menampilkan `plan`, `minutes`, `resetsAt` begitu field itu ada; sebelum itu app hanya menampilkan `jobs` dan `asks` seperti sekarang.
