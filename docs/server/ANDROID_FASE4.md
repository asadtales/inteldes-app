# Serah terima server Fase 4 untuk Android

Semua fitur Fase 4 sudah **hidup di produksi** (`https://inteldes.at-in.site`) dan tercatat di `openapi.snapshot.yaml` (disalin dari web `71bdd4d`). Dokumen ini merangkum yang relevan untuk app. Semuanya aditif: app yang ada tidak rusak, dan tidak ada yang wajib dipakai sekarang.

Peran: semua rute di bawah memakai Bearer seperti rute lain. "Operator+" = operator atau admin (pembaca 403).

## 1. Yang dulu "belum ada di server" dan kini ada

Bagian 7 di `ANDROID_FASE3.md` kini usang. Sekarang:

| Kebutuhan | Server |
|---|---|
| Ekspor DOCX/PDF/TXT | `GET /v1/recordings/{id}/export?format=docx\|pdf\|txt&include=notes,transcript` (semua peran). Respons berkas unduhan (`Content-Disposition: attachment`). Foto belum ikut diekspor. |
| Potong audio | `POST /v1/recordings/{id}/trim` (Operator+) |
| Transkripsi sebagian rentang | Tidak ada parameter `rangeStartSec/EndSec`. Caranya: **potong dulu** lalu proses rekaman barunya (`/process`). |
| Deteksi bagian hening | `GET /v1/recordings/{id}/silence` (Operator+) |
| Bagikan lewat tautan publik | `POST/GET /v1/recordings/{id}/shares`, `DELETE /v1/shares/{id}`, halaman publik `/s/{token}` |

### Potong audio
`POST /v1/recordings/{id}/trim`
```json
{ "ranges": [{"startSec": 10, "endSec": 20}, {"startSec": 70, "endSec": 92}],
  "title": "Agenda anggaran", "kind": "Musdes", "copyMarks": true }
```
Alternatif satu rentang: `startSec` + `endSec` tanpa `ranges`. Hasil: **201** + `Recording` baru (status `RAW`, `source: web`, `engine: CLOUD`); rekaman sumber tidak berubah. Aturan yang sama dengan rencana di `RENCANA_POTONG_MULTI_RENTANG.md`: rentang diurutkan, dipotong ke durasi, rentang yang tumpang tindih/bersentuhan digabung, maksimum 100, penanda dalam rentang (batas akhir inklusif) ikut dengan waktu digeser.
Galat: `400 invalid_range`, `404`, `422 no_audio` (audio belum ada di server atau sudah dihapus retensi), `422 unsupported_audio`, `429` (20 per menit).
Server memakai ffmpeg tanpa sandi ulang, jadi m4a, mp3, wav, ogg, webm semua bisa. Hanya untuk rapat yang audionya sudah ada di server.

### Deteksi hening
`GET /v1/recordings/{id}/silence?noiseDb=-35&minSilenceSec=3&padSec=0.5` mengembalikan `{ durationSec, silences[], speech[], speechSec, savedSec }`. `speech` berisi rentang bulat yang bisa langsung dikirim sebagai `ranges` ke `/trim`. Tidak mengubah apa pun. Disarankan app menghitung sendiri di perangkat (offline); endpoint ini opsional.

### Bagikan lewat tautan
`POST /v1/recordings/{id}/shares` `{includeTranscript, includeAudio, expiresInDays}` (`expiresInDays: null` = sampai dicabut) mengembalikan `ShareLink` + `url` (**hanya tampil sekali**, tidak bisa diambil lagi). Penerima membuka `url` di peramban tanpa akun dan mengunduh notulensi (Word/PDF/teks), serta transkrip dan audio bila diizinkan. `GET .../shares` mendaftar tautan (tanpa token), `DELETE /v1/shares/{id}` mencabut. Cocok sebagai opsi "Bagikan lewat tautan" di samping berbagi berkas lewat Intent yang ada.

## 2. Audio dihapus retensi server

Admin akun bisa mengatur `audioRetentionDays` (`PUT /v1/settings`): `null` selamanya, `0` hapus ±1 jam setelah notulensi siap, selain itu jumlah hari sejak rapat direkam. Hanya rapat `DONE`. Efeknya di rekaman: `audioBlobSha256` menjadi `null`, `version` naik, dan perubahan masuk `sync/changes`. Transkrip, notulensi, penanda, dan foto tetap. Penanganan `AUDIO_PURGED` di app (tidak mengunggah ulang, salinan HP tetap) sudah sesuai.
Catatan: server tidak punya penanda terpisah "audio dihapus"; app menyimpulkannya dari `audioBlobSha256` yang tadinya ada lalu `null`. Pada HP baru yang belum pernah melihat rekaman itu, hasilnya sama dengan "tidak ada audio". Bila itu mengganggu, minta server menambah field `audioPurgedAt` (belum dibuat).

## 3. Akun, kunci AI, dan kuota

- `/v1/auth/me`: `org.kind` (`team` atau `personal`) dan `superadmin`. Akun pribadi: arsip terpisah total, tidak bisa menambah pengguna (`POST /v1/users` 409 `personal_org`).
- Sumber kunci AI per akun (kunci IntelDes dengan kuota, atau kunci sendiri): lihat `CATATAN_SUMBER_KUNCI_AI.md`. Respons `429 quota_exceeded` dari `/process` dan tanya AI menyebut batasnya dan menyarankan pindah ke kunci sendiri; jangan diulang otomatis.
- Rute `/v1/admin/*` hanya untuk superadmin dan **tidak dipakai app**.

## 4. Keamanan dan cut-over

- Kunci AI tidak boleh lagi tertanam di APK: `CUTOVER_KUNCI_APK.md` (perubahan build sudah ada; perlu build, uji, dan verifikasi oleh pengembang Android, serta penggantian kunci oleh pemilik).
- Akses PostgreSQL sudah dikeraskan; tidak ada dampak ke app.

## 5. Snapshot OpenAPI

`docs/server/openapi.snapshot.yaml` sudah diperbarui ke web `71bdd4d`. Skema yang berubah sejak snapshot sebelumnya (`7ea7f04`): `OrgSettings`/`SettingsView` (`aiKeySource`, `aiAccess`, `platformLimit`), `TrimRequest` (`ranges`), rute `silence`, dan `AuthProviders.googleSignup`. Semua field tambahan bersifat opsional untuk klien; Gson mengabaikan field yang tak dikenal.
