# Pipeline AI: apa yang harus di-port dari Android ke server

Sumber kebenaran adalah kode Android (jalur `app/src/main/java/com/inteldes/app/data/`). Dokumen ini hanya peta; **baca berkas aslinya saat porting** dan jangan menyalin nama model dari sini.

## Peta berkas
| Kebutuhan | Berkas Android |
|---|---|
| Transkripsi awan | `network/WhisperCloudClient.kt` |
| Pemilihan provider, mode hemat | `network/NotulensiGenerator.kt` |
| Skema JSON, prompt, parsing hasil | `network/NotulensiSchema.kt` |
| Gemini (structured output) | `network/GeminiClient.kt` |
| Claude (tool use) | `network/ClaudeClient.kt` |
| Prompt Konteks (tanya, revisi) | `network/ContextPrompts.kt` |
| Orkestrasi, retry | `work/ProcessRecordingWorker.kt` |
| Template bawaan | `model/BuiltInTemplates.kt` → `builtin-templates.json` |
| Bentuk payload bagian | `db/SectionCodec.kt` |
| Impor percakapan WhatsApp | `ChatImport` + `TextFileImporter` |
| Ekspor txt/docx | `NotulensiExporter` (`sectionLines`) |

## Alur server
```
job process(recordingId, opts):
  PREP        validasi, ambil kunci org dari org_secrets, status=WORKING
  TRANSCRIBE  lewati bila engine=ON_DEVICE atau rapat impor teks (transkrip sudah ada)
              Deepgram /v1/listen: model whisper-large, language=id, diarize, punctuate,
              utterances, smart_format, header "Authorization: Token <key>"
              pembicara → "Pembicara N", waktu mulai = detik (+ rangeStartSec bila parsial)
              hasil kosong = galat permanen
  DIARIZE     simpan transkrip + speaker_count
  SUMMARIZE   provider = resolve(provider, durasi): AUTO → Gemini bila > 5400 dtk, selain itu Claude
              prompt = buildTranscriptPrompt(judul, transkrip, density, template, bahasa, lokasi)
              skema  = notulensiJsonSchema(template)  → properti "summary" + "section_N" per bagian
  LINK        parse → note_sections, arsipkan notulensi lama ke notulensi_revisions, status=DONE
              bila retensi menuntut: hapus audio
```

## Skema keluaran (ringkas)
- Objek `{ summary, section_1 … section_N }`, semua wajib. Tipe tiap `section_N` mengikuti tipe bagian:
  - PARAGRAPH: string. BULLETS dan DECISIONS: array string.
  - TOPICS: `{timestampSec:int, title, body}`.
  - QUESTIONS: `{question, status (enum label QuestionStatus), note}`.
  - ACTIONS: `{task, pic, due, sourceTimestampSec:int}`.
- Gemini: `responseMimeType=application/json`, `responseSchema` = skema yang sama dengan tipe HURUF BESAR (`toGeminiSchema`), suhu 0.4.
- Claude: tool `submit_notulensi` dengan `input_schema` = skema yang sama, `tool_choice` dipaksa; `max_tokens` 8192 (16.000 untuk operasi konteks).
- Parsing toleran: daftar string boleh berupa satu string multibaris atau objek `{text}`; status pertanyaan dipetakan dari teks ("sebagian" → PARTIAL, "terjawab" → ANSWERED, lainnya UNANSWERED); PIC/tenggat kosong → "Belum ditentukan"; keputusan diberi nomor "01", "02", … saat dibaca.

## Aturan prompt yang wajib dipertahankan
- Kepadatan RINGKAS / SEDANG / DETAIL dengan kalimat panduan masing-masing (lihat `buildTranscriptPrompt`).
- Bahasa keluaran dari `OutputLanguage.promptName`, apa pun bahasa transkrip.
- Larangan mengarang nama, PIC, tenggat, tanggal, tempat → "Belum ditentukan" / "Tidak disebutkan"; bagian kosong → daftar/teks kosong.
- Baris lokasi disisipkan bila ada.
- Transkrip dikirim dengan format `[mm:ss] Pembicara: ucapan`.

## Retry dan kegagalan
- 429 dan `IOException`: backoff eksponensial mulai 30 dtk, maksimal 5 percobaan; setelah itu tampilkan galat laju/koneksi.
- `ApiException` lain: gagal langsung → status kembali RAW + `error_message`.
- Pembatalan oleh pengguna → "Dibatalkan oleh pengguna."
- Satu job unik per rapat; antrean satu-per-satu per organisasi agar biaya dan laju terkendali (Android memakai `Mutex` global).

## Perbaikan yang disarankan di server
- Audio panjang: beri Deepgram **URL bertanda tangan** (bukan unggah ulang); verifikasi batas ukuran/durasi Deepgram saat implementasi.
- Nama model dan ambang (5400 dtk, 60.000 karakter, 40.000 karakter revisi) di konfigurasi server, bukan di kode.
- Catat penggunaan token/menit audio per organisasi untuk kuota.
- Label "Gemini 2.5 Pro" di `Enums.kt` Android tidak cocok dengan model yang dipakai; samakan saat label itu dipindah ke API.
