# CLAUDE.md — repo inteldes-web (awal)

Taruh berkas ini sebagai `CLAUDE.md` di akar repo `inteldes-web`, bersama folder `docs/` (salinan `docs/` dari `inteldes-app`).

## Tujuan
Backend + web IntelDes di https://inteldes.at-in.site, sinkron dua arah dengan app Android `com.inteldes.app` (repo `inteldes-app`). Rencana lengkap: `docs/PERENCANAAN_WEB_INTELDES.md`. Kerjakan **per fase** (0 → 4) dan hentikan di tiap "selesai bila".

## Keputusan tetap
- Node 22 + TypeScript, Fastify, Zod, Drizzle, argon2id, `pg-boss` (antrean di Postgres, tanpa Redis). Web: React + Vite SPA.
- Deploy langsung di host dengan systemd (tanpa Docker). Satu organisasi dulu, tetapi semua tabel punya `org_id`.
- Server sumber kebenaran; Google Drive hanya ekspor/cadangan opsional.
- Peran: admin, operator, pembaca.

## Aturan kerja
- Kontrak data ada di `docs/handoff/` (`data-model.sql`, `api-contract.md`, `sync-protocol.md`, `ai-pipeline.md`, `builtin-templates.json`). Ubah dokumen itu bersama kodenya.
- Pipeline AI adalah **port** dari kode Android; baca berkas sumbernya (daftar di `ai-pipeline.md`) sebelum menulis, jangan mengarang prompt atau nama model.
- **Rahasia**: jangan pernah menulis kunci API, `.env`, token, atau isi `/etc/inteldes/env` ke git, log, atau jawaban. Kunci AI per organisasi disimpan terenkripsi lewat `PUT /secrets/:name`.
- Jangan menyentuh situs lain di domain `at-in.site`; konfigurasi proxy hanya untuk `inteldes.at-in.site`. Audit dulu (`docs/handoff/VPS_BOOTSTRAP.md`), baru pasang.
- Tindakan merusak (drop tabel, hapus blob, ubah firewall/DNS) hanya setelah konfirmasi pemilik.
- Rekaman rapat adalah data pribadi (UU PDP): URL blob bertanda tangan pendek, audit log pada perubahan, tidak ada data nyata di fixture/tes.

## Uji
- Port `SyncEngineTest` (repo Android) menjadi uji kontrak server: salin dua arah, bentrok 409 + revisi, tombstone, rapat `WORKING` dilewati.
- E2E dengan Playwright untuk alur web; uji unggah audio panjang dengan putus-sambung (tus).

## Desain
Artifact desain web dibagikan di claude.ai (privat); ekspor HTML-nya ke `docs/design/`. Gaya Modernist, aksen `#c4260c` (teks/tombol), pastikan kontras terbaca. Layar yang belum ada di desain: Konteks, impor teks percakapan, riwayat versi, rentang transkripsi, potong audio, manajemen kategori.
