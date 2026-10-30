-- IntelDes server — skema PostgreSQL 16 (DRAF untuk Fase 1)
-- Prinsip: UUID dibuat klien, updated_at + version + deleted_at di semua entitas yang disinkronkan,
-- org_id di semua tabel (satu organisasi dulu, tetapi multi-tenant sejak awal).
-- Antrean kerja memakai skema pg-boss sendiri (tidak didefinisikan di sini).

create table organizations (
  id uuid primary key,
  name text not null,
  created_at timestamptz not null default now()
);

create table users (
  id uuid primary key,
  email text not null,
  name text not null,
  password_hash text not null,                 -- argon2id
  created_at timestamptz not null default now(),
  disabled_at timestamptz
);
create unique index users_email_uq on users (lower(email));

create table memberships (
  org_id uuid not null references organizations(id),
  user_id uuid not null references users(id),
  role text not null check (role in ('admin','operator','pembaca')),
  primary key (org_id, user_id)
);

create table devices (
  id uuid primary key,
  org_id uuid not null references organizations(id),
  user_id uuid not null references users(id),
  name text not null,
  platform text not null check (platform in ('android','web')),
  created_at timestamptz not null default now(),
  last_seen_at timestamptz,
  revoked_at timestamptz
);

create table refresh_tokens (
  id uuid primary key,
  device_id uuid not null references devices(id),
  token_hash text not null,
  expires_at timestamptz not null,
  rotated_at timestamptz,
  revoked_at timestamptz
);

create table pair_codes (                       -- login web lewat QR / tambah HP lewat QR
  code_hash text primary key,
  org_id uuid not null references organizations(id),
  kind text not null check (kind in ('web_login','add_phone')),
  created_by_user uuid references users(id),
  approved_by_device uuid references devices(id),
  expires_at timestamptz not null,
  claimed_at timestamptz
);

-- Sumber untuk GET /v1/sync/changes?since=<seq>
create table changes (
  server_seq bigserial primary key,
  org_id uuid not null references organizations(id),
  entity text not null,                        -- recording | category | template | context
  entity_id text not null,
  op text not null check (op in ('upsert','delete')),
  at timestamptz not null default now()
);
create index changes_org_seq on changes (org_id, server_seq);

create table categories (
  id uuid primary key,
  org_id uuid not null references organizations(id),
  label text not null,
  order_index int not null,
  version int not null default 1,
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);
create unique index categories_label_uq on categories (org_id, lower(label)) where deleted_at is null;

create table blobs (                            -- audio, foto, ekspor; kunci isi = sha256
  sha256 text primary key,
  org_id uuid not null references organizations(id),
  size_bytes bigint not null,
  mime text not null,
  storage_key text not null,
  created_at timestamptz not null default now()
);

create table recordings (
  id uuid primary key,
  org_id uuid not null references organizations(id),
  title text not null,
  kind text not null,                           -- label kategori (Android menyimpan teks bebas)
  created_at timestamptz not null,              -- waktu rekaman
  duration_sec int not null,
  audio_blob_sha256 text references blobs(sha256),
  audio_mime_type text not null default 'audio/mp4',
  status text not null check (status in ('RAW','WORKING','DONE')),
  speaker_count int not null default 0,
  engine text not null check (engine in ('ON_DEVICE','CLOUD')),
  template_id text not null default 'builtin_standard',
  template_name text not null default 'Notulensi Standar',
  summary text,
  provider text check (provider in ('AUTO','GEMINI','CLAUDE')),
  notes_generated_at timestamptz,
  notulensi_density text check (notulensi_density in ('RINGKAS','SEDANG','DETAIL')),
  location_name text,
  latitude double precision,
  longitude double precision,
  error_message text,                           -- di Android ada, tapi belum ikut sinkron Drive
  version int not null default 1,               -- metadata
  transcript_version int not null default 0,
  notes_version int not null default 0,
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);
create index recordings_org_created on recordings (org_id, created_at desc) where deleted_at is null;

create table transcript_segments (
  id uuid primary key,
  recording_id uuid not null references recordings(id) on delete cascade,
  order_index int not null,
  speaker text not null,
  timestamp_sec int not null,
  text text not null
);
create index transcript_rec on transcript_segments (recording_id, order_index);
-- Pencarian isi transkrip: tambah kolom tsvector + indeks GIN (config 'simple') di Fase 2.

create table note_sections (
  id uuid primary key,
  recording_id uuid not null references recordings(id) on delete cascade,
  order_index int not null,
  title text not null,
  type text not null check (type in ('PARAGRAPH','BULLETS','TOPICS','DECISIONS','QUESTIONS','ACTIONS')),
  payload jsonb not null                        -- bentuk sama dengan SectionCodec.kt di Android
);
create index note_sections_rec on note_sections (recording_id, order_index);

create table marks (
  id uuid primary key,
  recording_id uuid not null references recordings(id) on delete cascade,
  timestamp_sec int not null,
  label text not null,
  created_at timestamptz not null,
  deleted_at timestamptz
);

create table photos (
  id uuid primary key,
  recording_id uuid not null references recordings(id) on delete cascade,
  blob_sha256 text not null references blobs(sha256),
  taken_at_sec int,                             -- offset menit dalam audio
  caption text not null default '',
  created_at timestamptz not null,
  deleted_at timestamptz
);

create table chat_messages (                    -- append-only
  id uuid primary key,
  recording_id uuid not null references recordings(id) on delete cascade,
  order_index int not null,
  is_user boolean not null,
  text text not null,
  created_at timestamptz not null
);

create table notulensi_revisions (              -- pengganti notulensi_history; juga menampung sisi yang kalah saat bentrok
  id uuid primary key,
  recording_id uuid not null references recordings(id) on delete cascade,
  generated_at timestamptz not null,
  density text not null,
  provider text not null,
  template_name text,
  reason text not null default 'regenerate' check (reason in ('regenerate','conflict','edit')),
  snapshot jsonb not null                       -- {summary, provider, templateName, sections:[{title,type,payload}]}
);

create table templates (                        -- built-in: org_id null, builtin = true (seed dari builtin-templates.json)
  id text primary key,                          -- builtin_* atau custom_<uuid>
  org_id uuid references organizations(id),
  name text not null,
  description text not null default '',
  sections jsonb not null,                      -- [{title,type,instruction}]
  builtin boolean not null default false,
  version int not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table knowledge_contexts (
  id uuid primary key,
  org_id uuid not null references organizations(id),
  name text not null,
  text text not null,
  version int not null default 1,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  deleted_at timestamptz
);

create table context_sources (
  id uuid primary key,
  context_id uuid not null references knowledge_contexts(id) on delete cascade,
  recording_id uuid,                            -- sengaja tanpa FK (seperti di Android)
  recording_title text not null,
  kind text not null check (kind in ('NOTULENSI','TRANSCRIPT','BOTH')),
  added_at timestamptz not null
);

create table org_settings (
  org_id uuid primary key references organizations(id),
  settings jsonb not null default '{}'          -- provider, defaultEngine, density, outputLanguage, economyMode,
                                                -- autoProcessAfterRecording, defaultTemplateId, speakerRoster, retensi audio
);

create table org_secrets (                      -- kunci API Deepgram/Gemini/Claude, terenkripsi AES-GCM
  org_id uuid not null references organizations(id),
  name text not null check (name in ('deepgram','gemini','claude')),
  ciphertext bytea not null,
  nonce bytea not null,
  updated_at timestamptz not null default now(),
  primary key (org_id, name)
);

create table audit_log (
  id bigserial primary key,
  org_id uuid not null references organizations(id),
  user_id uuid references users(id),
  device_id uuid references devices(id),
  action text not null,
  entity text,
  entity_id text,
  at timestamptz not null default now(),
  meta jsonb
);
