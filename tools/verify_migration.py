"""Checks the Room migrations 7 -> 8 -> 9 on a real SQLite file, without a phone.

Builds a v7 database from schemas/7.json, fills it with rows, runs the SQL that the Kotlin migrations execute
(read from Migration7To8.kt / Migration8To9.kt), then checks that
  * no row was lost or changed,
  * the resulting tables have the same columns, nullability, defaults and indices as schemas/9.json.

Run:  python -X utf8 -I tools/verify_migration.py
"""
import json
import os
import re
import sqlite3
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCHEMAS = os.path.join(ROOT, "app", "schemas", "com.inteldes.app.data.db.IntelDesDatabase")
DB_DIR = os.path.join(ROOT, "app", "src", "main", "java", "com", "inteldes", "app", "data", "db")


def schema(version):
    return json.load(open(os.path.join(SCHEMAS, f"{version}.json"), encoding="utf-8"))["database"]


def create_statements(db):
    out = []
    for e in db["entities"]:
        out.append(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
        for ix in e.get("indices", []):
            out.append(ix["createSql"].replace("${TABLE_NAME}", e["tableName"]))
    return out


def kotlin_sql(path):
    """The execSQL(...) statements of a Kotlin migration, string concatenations joined."""
    text = open(path, encoding="utf-8").read()
    stmts = []
    for m in re.finditer(r"execSQL\(\s*(.*?)\s*,?\s*\)\s*(?=\n\s*(?:db\.|\}))", text, re.S):
        literals = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
        stmts.append("".join(l.replace('\\"', '"').replace("\\n", "\n") for l in literals))
    return stmts


def table_dump(conn, table):
    cols = [r[1] for r in conn.execute(f"PRAGMA table_info(`{table}`)")]
    return cols, conn.execute(f"SELECT * FROM `{table}` ORDER BY 1").fetchall()


def main():
    conn = sqlite3.connect(":memory:")
    conn.execute("PRAGMA foreign_keys=ON")
    for sql in create_statements(schema(7)):
        conn.execute(sql)

    # --- v7 data: every table that exists in v7, with realistic rows (found out from the schema itself)
    conn.execute(
        "INSERT INTO recordings (id,title,kind,createdAt,durationSec,audioFilePath,audioMimeType,status,speakerCount,engine,templateId,templateName,locationName,latitude,longitude,errorMessage,summary,provider,notesGeneratedAt,notulensiDensity) "
        "VALUES ('r1','Musyawarah Desa','Rapat',1700000000000,3600,'/files/a.m4a','audio/mp4','DONE',3,'CLOUD','standard','Notulensi Standar','Balai Desa',-6.2,106.8,NULL,'Ringkasan','GEMINI',1700000001000,'SEDANG')"
        if "notulensiDensity" in [f["columnName"] for e in schema(7)["entities"] if e["tableName"] == "recordings" for f in e["fields"]] else "SELECT 1"
    )
    seeded = {"recordings": 1}
    for table, row in {
        "recording_marks": "INSERT INTO recording_marks (recordingId,timestampSec,label,createdAt) VALUES ('r1',120,'Keputusan',1700000002000),('r1',900,'Penanda 2',1700000003000)",
        "photos": "INSERT INTO photos (recordingId,filePath,takenAtSec,caption,createdAt) VALUES ('r1','/files/p1.jpg',300,'Foto 1',1700000004000)",
        "chat_messages": "INSERT INTO chat_messages (recordingId,orderIndex,isUser,text,createdAt) VALUES ('r1',0,1,'Apa keputusannya?',1700000005000),('r1',1,0,'Anggaran disetujui.',1700000006000)",
        "transcript_segments": "INSERT INTO transcript_segments (recordingId,orderIndex,speaker,timestampSec,text) VALUES ('r1',0,'Pembicara 1',0,'Selamat pagi')",
    }.items():
        try:
            conn.execute(row)
        except sqlite3.Error as e:
            print(f"! could not seed {table}: {e}")
            return 2
        seeded[table] = conn.execute(f"SELECT COUNT(*) FROM `{table}`").fetchone()[0]
    conn.commit()
    before = {t: table_dump(conn, t) for t in seeded}

    # --- run the real migrations' SQL
    steps = [(7, 8, "Migration7To8.kt"), (8, 9, "Migration8To9.kt")]
    for old, new, name in steps:
        stmts = kotlin_sql(os.path.join(DB_DIR, name))
        if not stmts:
            print(f"! no SQL found in {name}")
            return 2
        for sql in stmts:
            conn.execute(sql)
        conn.execute(f"PRAGMA user_version={new}")
        print(f"v{old} -> v{new}: {len(stmts)} statements")
    conn.commit()

    problems = []

    # --- nothing lost or altered (old columns keep their values; new ones are NULL / default)
    for table, (cols, rows) in before.items():
        ncols, nrows = table_dump(conn, table)
        if len(nrows) != len(rows):
            problems.append(f"{table}: {len(rows)} rows before, {len(nrows)} after")
            continue
        idx = [ncols.index(c) for c in cols]
        for a, b in zip(rows, nrows):
            if tuple(b[i] for i in idx) != tuple(a):
                problems.append(f"{table}: a row changed {a} -> {b}")
        extra = [c for c in ncols if c not in cols]
        if extra:
            print(f"{table}: new columns {extra}, values {[tuple(r[ncols.index(c)] for c in extra) for r in nrows][:1]}")

    # --- same shape as schemas/9.json
    for e in schema(9)["entities"]:
        t = e["tableName"]
        info = {r[1]: r for r in conn.execute(f"PRAGMA table_info(`{t}`)")}
        want = {f["columnName"]: f for f in e["fields"]}
        if set(info) != set(want):
            problems.append(f"{t}: columns differ: only-db={sorted(set(info)-set(want))} only-schema={sorted(set(want)-set(info))}")
            continue
        for name, f in want.items():
            _, _, typ, notnull, dflt, pk = info[name]
            if bool(notnull) != bool(f["notNull"]) and not pk:
                problems.append(f"{t}.{name}: notNull db={bool(notnull)} schema={f['notNull']}")
            if typ.upper() != f["affinity"].upper():
                problems.append(f"{t}.{name}: type db={typ} schema={f['affinity']}")
            wd = f.get("defaultValue")
            if wd not in (None, "undefined") and str(dflt) != str(wd):
                problems.append(f"{t}.{name}: default db={dflt} schema={wd}")
        have_ix = {r[1] for r in conn.execute(f"PRAGMA index_list(`{t}`)") if not r[1].startswith("sqlite_")}
        want_ix = {ix["name"] for ix in e.get("indices", [])}
        if have_ix != want_ix:
            problems.append(f"{t}: indices differ db={sorted(have_ix)} schema={sorted(want_ix)}")
    have_tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'")}
    want_tables = {e["tableName"] for e in schema(9)["entities"]}
    if have_tables != want_tables:
        problems.append(f"tables differ: only-db={sorted(have_tables-want_tables)} only-schema={sorted(want_tables-have_tables)}")

    fk = conn.execute("PRAGMA foreign_key_check").fetchall()
    if fk:
        problems.append(f"foreign key violations: {fk}")

    print("seeded rows:", seeded)
    if problems:
        print("PROBLEMS:")
        for p in problems:
            print(" -", p)
        return 1
    print("OK: v7 data intact after 7->8->9 and the layout matches schemas/9.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
