use std::fs::{self, File};
use std::io::{Cursor, Read, Write};
use std::path::{Path, PathBuf};

use chrono::Local;
use rusqlite::Connection;
use serde::{Deserialize, Serialize};
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipArchive, ZipWriter};

use crate::domain;

pub const ARCHIVE_SCHEMA_VERSION: u32 = 1;
pub const ARCHIVE_FORMAT: &str = "rogein-archive";
pub const ARCHIVES_DIR_NAME: &str = "archives";
pub const MANIFEST_ENTRY: &str = "manifest.json";
pub const DB_ENTRY: &str = "data.sqlite3";
pub const MAP_ENTRY: &str = "course_map.bin";

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ArchiveManifest {
    pub format: String,
    pub schema_version: u32,
    pub app_id: String,
    pub title: String,
    pub created_at: String,
    pub has_course_map: bool,
    #[serde(default)]
    pub competition_date: Option<String>,
}

#[derive(Debug, Serialize, Clone)]
pub struct ArchiveListItem {
    pub file_name: String,
    pub path: String,
    pub title: String,
    pub created_at: String,
    pub competition_date: Option<String>,
    pub size_bytes: u64,
}

#[derive(Debug, Serialize, Clone)]
pub struct ArchiveActionResult {
    pub archive_path: String,
    pub title: String,
    pub cleared: bool,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ActiveArchiveInfo {
    pub path: String,
    pub title: String,
    pub file_name: String,
}

const ACTIVE_ARCHIVE_FILE: &str = "active_archive.json";

pub fn active_marker_path(app_data_dir: &Path) -> PathBuf {
    app_data_dir.join(ACTIVE_ARCHIVE_FILE)
}

pub fn get_active_archive(app_data_dir: &Path) -> Option<ActiveArchiveInfo> {
    let path = active_marker_path(app_data_dir);
    let bytes = fs::read(path).ok()?;
    let info: ActiveArchiveInfo = serde_json::from_slice(&bytes).ok()?;
    if !PathBuf::from(&info.path).exists() {
        let _ = clear_active_archive(app_data_dir);
        return None;
    }
    Some(info)
}

pub fn set_active_archive(
    app_data_dir: &Path,
    archive_path: &Path,
    title: &str,
) -> Result<(), String> {
    let file_name = archive_path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("archive.rogein")
        .to_string();
    let info = ActiveArchiveInfo {
        path: archive_path.to_string_lossy().to_string(),
        title: title.to_string(),
        file_name,
    };
    let bytes =
        serde_json::to_vec_pretty(&info).map_err(|e| format!("serialize active archive: {e}"))?;
    fs::write(active_marker_path(app_data_dir), bytes)
        .map_err(|e| format!("write active archive marker: {e}"))
}

pub fn clear_active_archive(app_data_dir: &Path) -> Result<(), String> {
    let path = active_marker_path(app_data_dir);
    if path.exists() {
        fs::remove_file(&path).map_err(|e| format!("clear active archive marker: {e}"))?;
    }
    Ok(())
}

pub fn archives_dir(app_data_dir: &Path) -> PathBuf {
    app_data_dir.join(ARCHIVES_DIR_NAME)
}

pub fn ensure_archives_dir(app_data_dir: &Path) -> Result<PathBuf, String> {
    let dir = archives_dir(app_data_dir);
    fs::create_dir_all(&dir).map_err(|e| format!("create archives dir: {e}"))?;
    Ok(dir)
}

pub fn sanitize_archive_stem(raw: &str) -> Result<String, String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err("Укажите имя архива старта.".into());
    }
    let mut out = String::new();
    for ch in trimmed.chars() {
        if ch.is_alphanumeric() || ch == '_' || ch == '-' || ch == ' ' || ch == '.' {
            if ch == ' ' {
                out.push('_');
            } else {
                out.push(ch);
            }
        }
    }
    let out = out.trim_matches(|c| c == '.' || c == '_').to_string();
    let out = out.trim_end_matches(".rogein").trim_end_matches(".zip").to_string();
    if out.is_empty() {
        return Err("Имя архива содержит недопустимые символы.".into());
    }
    if out.len() > 120 {
        return Err("Имя архива слишком длинное (макс. 120 символов).".into());
    }
    Ok(out)
}

pub fn default_archive_title(competition_date: &str) -> String {
    let date = competition_date.trim();
    let stamp = Local::now().format("%Y-%m-%d_%H%M").to_string();
    if date.is_empty() {
        format!("Старт_{stamp}")
    } else {
        format!("Старт_{date}")
    }
}

fn remove_sqlite_sidecar_files(db_path: &Path) {
    let wal = PathBuf::from(format!("{}-wal", db_path.display()));
    let shm = PathBuf::from(format!("{}-shm", db_path.display()));
    let _ = fs::remove_file(wal);
    let _ = fs::remove_file(shm);
}

pub fn wipe_working_start(
    app_data_dir: &Path,
    db_path: &Path,
    course_map_path: &Path,
) -> Result<(), String> {
    if db_path.exists() {
        fs::remove_file(db_path).map_err(|e| format!("remove db: {e}"))?;
    }
    remove_sqlite_sidecar_files(db_path);
    if course_map_path.exists() {
        fs::remove_file(course_map_path).map_err(|e| format!("remove course map: {e}"))?;
    }
    clear_active_archive(app_data_dir)?;
    let _conn = domain::open_and_init_db(db_path)?;
    Ok(())
}

fn read_setting(conn: &Connection, key: &str) -> Option<String> {
    conn.query_row(
        "SELECT value FROM settings WHERE key = ?1",
        [key],
        |r| r.get::<_, String>(0),
    )
    .ok()
}

fn build_manifest(
    conn: &Connection,
    title: &str,
    has_course_map: bool,
) -> ArchiveManifest {
    ArchiveManifest {
        format: ARCHIVE_FORMAT.to_string(),
        schema_version: ARCHIVE_SCHEMA_VERSION,
        app_id: "ru.rogein.desktop.v01".to_string(),
        title: title.to_string(),
        created_at: Local::now().to_rfc3339(),
        has_course_map,
        competition_date: read_setting(conn, "competition_date").filter(|v| !v.trim().is_empty()),
    }
}

fn zip_write_file<W: Write + std::io::Seek>(
    zip: &mut ZipWriter<W>,
    name: &str,
    bytes: &[u8],
) -> Result<(), String> {
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
    zip.start_file(name, options)
        .map_err(|e| format!("zip start {name}: {e}"))?;
    zip.write_all(bytes)
        .map_err(|e| format!("zip write {name}: {e}"))?;
    Ok(())
}

pub fn create_start_archive(
    db_path: &Path,
    course_map_path: &Path,
    archive_path: &Path,
    title: &str,
) -> Result<ArchiveManifest, String> {
    if let Some(parent) = archive_path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("create archive parent: {e}"))?;
    }

    let snapshot_path = archive_path.with_extension("sqlite3.part");
    let _ = fs::remove_file(&snapshot_path);

    {
        let conn = Connection::open(db_path).map_err(|e| format!("open db for snapshot: {e}"))?;
        conn.execute_batch("PRAGMA wal_checkpoint(FULL);")
            .map_err(|e| format!("wal checkpoint: {e}"))?;
        let dest = snapshot_path
            .to_str()
            .ok_or_else(|| "snapshot path is not valid UTF-8".to_string())?;
        conn.execute("VACUUM INTO ?1", [dest])
            .map_err(|e| format!("VACUUM INTO snapshot: {e}"))?;
    }

    let db_bytes = fs::read(&snapshot_path).map_err(|e| format!("read snapshot: {e}"))?;
    let map_bytes = if course_map_path.exists() {
        Some(fs::read(course_map_path).map_err(|e| format!("read course map: {e}"))?)
    } else {
        None
    };

    let conn = Connection::open(db_path).map_err(|e| format!("open db for manifest: {e}"))?;
    let manifest = build_manifest(&conn, title, map_bytes.is_some());
    drop(conn);

    let manifest_json =
        serde_json::to_vec_pretty(&manifest).map_err(|e| format!("serialize manifest: {e}"))?;

    let tmp_zip = archive_path.with_extension("rogein.part");
    let _ = fs::remove_file(&tmp_zip);
    {
        let file = File::create(&tmp_zip).map_err(|e| format!("create archive temp: {e}"))?;
        let mut zip = ZipWriter::new(file);
        zip_write_file(&mut zip, MANIFEST_ENTRY, &manifest_json)?;
        zip_write_file(&mut zip, DB_ENTRY, &db_bytes)?;
        if let Some(bytes) = &map_bytes {
            zip_write_file(&mut zip, MAP_ENTRY, bytes)?;
        }
        zip.finish().map_err(|e| format!("finalize zip: {e}"))?;
    }

    fs::rename(&tmp_zip, archive_path).map_err(|e| format!("move archive into place: {e}"))?;
    let _ = fs::remove_file(&snapshot_path);
    Ok(manifest)
}

fn read_zip_entry_bytes(archive: &mut ZipArchive<Cursor<Vec<u8>>>, name: &str) -> Result<Vec<u8>, String> {
    let mut file = archive
        .by_name(name)
        .map_err(|e| format!("archive missing {name}: {e}"))?;
    let mut buf = Vec::new();
    file.read_to_end(&mut buf)
        .map_err(|e| format!("read archive entry {name}: {e}"))?;
    Ok(buf)
}

pub fn read_manifest_from_archive(archive_path: &Path) -> Result<ArchiveManifest, String> {
    let bytes = fs::read(archive_path).map_err(|e| format!("read archive: {e}"))?;
    let mut zip = ZipArchive::new(Cursor::new(bytes)).map_err(|e| format!("open zip: {e}"))?;
    let manifest_bytes = read_zip_entry_bytes(&mut zip, MANIFEST_ENTRY)?;
    let manifest: ArchiveManifest = serde_json::from_slice(&manifest_bytes)
        .map_err(|e| format!("parse manifest: {e}"))?;
    if manifest.format != ARCHIVE_FORMAT {
        return Err(format!("Неизвестный формат архива: {}", manifest.format));
    }
    if manifest.schema_version > ARCHIVE_SCHEMA_VERSION {
        return Err(format!(
            "Архив создан более новой версией программы (схема {}). Обновите приложение.",
            manifest.schema_version
        ));
    }
    Ok(manifest)
}

pub fn restore_start_archive(
    archive_path: &Path,
    db_path: &Path,
    course_map_path: &Path,
) -> Result<ArchiveManifest, String> {
    let bytes = fs::read(archive_path).map_err(|e| format!("read archive: {e}"))?;
    let mut zip = ZipArchive::new(Cursor::new(bytes)).map_err(|e| format!("open zip: {e}"))?;
    let manifest_bytes = read_zip_entry_bytes(&mut zip, MANIFEST_ENTRY)?;
    let manifest: ArchiveManifest = serde_json::from_slice(&manifest_bytes)
        .map_err(|e| format!("parse manifest: {e}"))?;
    if manifest.format != ARCHIVE_FORMAT {
        return Err(format!("Неизвестный формат архива: {}", manifest.format));
    }
    if manifest.schema_version > ARCHIVE_SCHEMA_VERSION {
        return Err(format!(
            "Архив создан более новой версией программы (схема {}). Обновите приложение.",
            manifest.schema_version
        ));
    }

    let db_bytes = read_zip_entry_bytes(&mut zip, DB_ENTRY)?;
    let map_bytes = match zip.by_name(MAP_ENTRY) {
        Ok(mut file) => {
            let mut buf = Vec::new();
            file.read_to_end(&mut buf)
                .map_err(|e| format!("read course map from archive: {e}"))?;
            Some(buf)
        }
        Err(zip::result::ZipError::FileNotFound) => None,
        Err(e) => return Err(format!("read course map from archive: {e}")),
    };

    let tmp_db = db_path.with_extension("sqlite3.restore");
    let _ = fs::remove_file(&tmp_db);
    fs::write(&tmp_db, &db_bytes).map_err(|e| format!("write restored db temp: {e}"))?;

    // Validate / migrate schema before swapping live DB.
    {
        let _conn = domain::open_and_init_db(&tmp_db)?;
    }

    if db_path.exists() {
        fs::remove_file(db_path).map_err(|e| format!("remove live db: {e}"))?;
    }
    remove_sqlite_sidecar_files(db_path);
    fs::rename(&tmp_db, db_path).map_err(|e| format!("install restored db: {e}"))?;

    if let Some(map) = map_bytes {
        fs::write(course_map_path, map).map_err(|e| format!("restore course map: {e}"))?;
    } else if course_map_path.exists() {
        fs::remove_file(course_map_path).map_err(|e| format!("clear course map: {e}"))?;
    }

    let _conn = domain::open_and_init_db(db_path)?;
    Ok(manifest)
}

pub fn list_archives(app_data_dir: &Path) -> Result<Vec<ArchiveListItem>, String> {
    let dir = ensure_archives_dir(app_data_dir)?;
    let mut items = Vec::new();
    let entries = fs::read_dir(&dir).map_err(|e| format!("read archives dir: {e}"))?;
    for entry in entries {
        let entry = entry.map_err(|e| format!("read archives entry: {e}"))?;
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        let ext = path
            .extension()
            .and_then(|e| e.to_str())
            .unwrap_or("")
            .to_ascii_lowercase();
        if ext != "rogein" {
            continue;
        }
        let meta = entry.metadata().map_err(|e| format!("archive metadata: {e}"))?;
        let file_name = path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("archive.rogein")
            .to_string();
        let (title, created_at, competition_date) = match read_manifest_from_archive(&path) {
            Ok(m) => (m.title, m.created_at, m.competition_date),
            Err(_) => (file_name.clone(), String::new(), None),
        };
        items.push(ArchiveListItem {
            file_name,
            path: path.to_string_lossy().to_string(),
            title,
            created_at,
            competition_date,
            size_bytes: meta.len(),
        });
    }
    items.sort_by(|a, b| b.created_at.cmp(&a.created_at).then(b.file_name.cmp(&a.file_name)));
    Ok(items)
}

pub fn resolve_archive_path(app_data_dir: &Path, path_or_name: &str) -> Result<PathBuf, String> {
    let raw = path_or_name.trim();
    if raw.is_empty() {
        return Err("Не указан путь к архиву.".into());
    }
    let path = PathBuf::from(raw);
    if path.is_absolute() {
        return Ok(path);
    }
    Ok(archives_dir(app_data_dir).join(path))
}

pub fn pick_archive_file() -> Option<PathBuf> {
    rfd::FileDialog::new()
        .add_filter("Архив старта Rogein", &["rogein"])
        .add_filter("ZIP", &["zip"])
        .pick_file()
}
