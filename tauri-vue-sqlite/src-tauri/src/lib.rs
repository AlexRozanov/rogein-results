pub mod archive;
pub mod archive_orient;
pub mod domain;
pub mod phone_sync;
pub mod site_publish;

use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;

use archive::{ActiveArchiveInfo, ArchiveActionResult, ArchiveListItem};
use domain::{
    AnomalyBulkActionsState, AnomalyListResponse, AnomalyScanSummary, AwardGroupRow,
    BulkAnomalyCorrectionSummary, BulkAnomalyRollbackSummary, CorrectionRow,
    CourseMapSpecialPoints, CourseRow, CoursesImportSummary, CpLegendImportSummary, CpLegendRow,
    CpLegendTypeRow,
    CpRemapAnalyzeSummary, CpRemapApplyItem, CpRemapApplySummary, ErrorRow, ExclusionRuleRow,
    FormatCpTypeRuleInput, FormatCpTypeRulesBundle, FormatSettings, ImportSummary,
    PhoneImportSummary,
    MapGeorefInfo, MapGpsAnchor, MapGpsAnchorUpsert, ParticipantDetails, ParticipantPathDistance,
    ParticipantRow, ResultRow, SearchSuggestion, Settings, StartProtocolFormatRow,
    StartProtocolImportSummary, StartProtocolRow, DataPresenceCounts,
};
use serde::Serialize;
use archive_orient::ArchiveOrientPreview;
use site_publish::{SitePublishResult, SitePublishSettings};
use tauri::{AppHandle, Emitter, Manager, State, WebviewUrl, WebviewWindowBuilder};

struct AppState {
    app_data_dir: PathBuf,
    db_path: PathBuf,
    db_lock: Mutex<()>,
}

const COURSE_MAP_FILE: &str = "course_map.bin";
const COURSE_MAP_NAME_KEY: &str = "course_map_file_name";
const COURSE_MAP_MIME_KEY: &str = "course_map_mime";

#[derive(Serialize)]
struct PhoneReadSummary {
    imported: i64,
    already_imported: i64,
    unmatched: i64,
    skipped_empty: i64,
    participants_count: i64,
    results_count: i64,
    snapshot_deleted: bool,
    snapshot_note: String,
}

#[derive(Serialize)]
struct CourseMapInfo {
    has_map: bool,
    file_name: Option<String>,
    mime_type: Option<String>,
}

#[derive(Serialize)]
struct CourseMapPayload {
    file_name: String,
    mime_type: String,
    data_base64: String,
}

fn course_map_path(state: &AppState) -> PathBuf {
    state.app_data_dir.join(COURSE_MAP_FILE)
}

fn recalculate_and_notify(app: &AppHandle, conn: &mut rusqlite::Connection) -> Result<(), String> {
    domain::recalculate(conn)?;
    let _ = app.emit("results-updated", ());
    Ok(())
}

fn guess_image_mime(file_name: &str) -> String {
    let ext = PathBuf::from(file_name)
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    match ext.as_str() {
        "jpg" | "jpeg" => "image/jpeg".to_string(),
        "png" => "image/png".to_string(),
        "gif" => "image/gif".to_string(),
        "webp" => "image/webp".to_string(),
        "bmp" => "image/bmp".to_string(),
        "svg" => "image/svg+xml".to_string(),
        "tif" | "tiff" => "image/tiff".to_string(),
        "ico" => "image/x-icon".to_string(),
        "avif" => "image/avif".to_string(),
        "heic" | "heif" => "image/heic".to_string(),
        _ => "application/octet-stream".to_string(),
    }
}

#[derive(Serialize)]
struct ResultsResponse {
    rows: Vec<ResultRow>,
    counts: std::collections::BTreeMap<String, i64>,
    total_count: i64,
    offset: i64,
    limit: i64,
}

#[derive(Serialize)]
struct StartProtocolResponse {
    rows: Vec<StartProtocolRow>,
    total_count: i64,
    offset: i64,
    limit: i64,
}

#[tauri::command]
fn get_data_presence_counts(state: State<'_, AppState>) -> Result<DataPresenceCounts, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::get_data_presence_counts(&conn)
}

#[tauri::command]
fn get_archives_dir(state: State<'_, AppState>) -> Result<String, String> {
    let dir = archive::ensure_archives_dir(&state.app_data_dir)?;
    Ok(dir.to_string_lossy().to_string())
}

#[tauri::command]
fn list_start_archives(state: State<'_, AppState>) -> Result<Vec<ArchiveListItem>, String> {
    archive::list_archives(&state.app_data_dir)
}

#[tauri::command]
fn get_active_start_archive(state: State<'_, AppState>) -> Result<Option<ActiveArchiveInfo>, String> {
    Ok(archive::get_active_archive(&state.app_data_dir))
}

#[tauri::command]
fn suggest_finish_start_name(state: State<'_, AppState>) -> Result<String, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    let settings = domain::get_settings(&conn)?;
    Ok(archive::default_archive_title(&settings.competition_date))
}

#[tauri::command]
fn get_site_publish_settings(state: State<'_, AppState>) -> Result<SitePublishSettings, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    let active_title = archive::get_active_archive(&state.app_data_dir).map(|a| a.title);
    site_publish::get_settings(
        &state.app_data_dir,
        &conn,
        active_title.as_deref(),
    )
}

#[tauri::command]
fn save_site_publish_settings(
    state: State<'_, AppState>,
    api_base_url: String,
    publish_token: String,
    slug: String,
    title: String,
) -> Result<SitePublishSettings, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    site_publish::save_connection(
        &state.app_data_dir,
        &site_publish::SiteConnection {
            api_base_url,
            publish_token,
        },
    )?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    if !slug.trim().is_empty() || !title.trim().is_empty() {
        let settings = domain::get_settings(&conn)?;
        let active_title = archive::get_active_archive(&state.app_data_dir).map(|a| a.title);
        let slug_value = if slug.trim().is_empty() {
            site_publish::suggested_slug(&settings.competition_date)
        } else {
            slug
        };
        let title_value = if title.trim().is_empty() {
            site_publish::suggested_title(&settings.competition_date, active_title.as_deref())
        } else {
            title
        };
        if !slug_value.is_empty() && !title_value.is_empty() {
            site_publish::save_event_meta(&conn, &slug_value, &title_value)?;
        }
    }
    let active_title = archive::get_active_archive(&state.app_data_dir).map(|a| a.title);
    site_publish::get_settings(
        &state.app_data_dir,
        &conn,
        active_title.as_deref(),
    )
}

#[tauri::command]
fn test_site_publish_connection(api_base_url: String) -> Result<String, String> {
    site_publish::test_connection(&api_base_url)
}

#[tauri::command]
fn preview_archive_orient_csv(
    csv_content: String,
    competition_date: String,
) -> Result<ArchiveOrientPreview, String> {
    archive_orient::preview_csv(&csv_content, &competition_date)
}

#[tauri::command]
fn publish_archive_orient_csv(
    state: State<'_, AppState>,
    csv_content: String,
    title: String,
    slug: String,
    competition_date: String,
    api_base_url: String,
    publish_token: String,
) -> Result<SitePublishResult, String> {
    archive_orient::publish_csv(
        &state.app_data_dir,
        &csv_content,
        &title,
        &slug,
        &competition_date,
        &api_base_url,
        &publish_token,
    )
}

#[tauri::command]
fn publish_current_start(
    state: State<'_, AppState>,
    api_base_url: String,
    publish_token: String,
    slug: String,
    title: String,
) -> Result<SitePublishResult, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let map_path = course_map_path(&state);
    site_publish::publish_current_start(
        &state.app_data_dir,
        &state.db_path,
        &map_path,
        &api_base_url,
        &publish_token,
        &slug,
        &title,
    )
}

#[tauri::command]
fn finish_current_start(
    state: State<'_, AppState>,
    mode: String,
    archive_name: Option<String>,
    overwrite: Option<bool>,
) -> Result<ArchiveActionResult, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;

    let overwrite = overwrite.unwrap_or(false);
    let map_path = course_map_path(&state);
    let (archive_path, title) = match mode.as_str() {
        "overwrite_active" => {
            let active = archive::get_active_archive(&state.app_data_dir).ok_or_else(|| {
                "Нет открытого архива для перезаписи. Сохраните старт под новым именем.".to_string()
            })?;
            let path = PathBuf::from(&active.path);
            if !path.exists() {
                let _ = archive::clear_active_archive(&state.app_data_dir);
                return Err("Файл открытого архива больше не найден. Сохраните под новым именем.".into());
            }
            let title = active.title.clone();
            (path, title)
        }
        "save_as" => {
            let name = archive_name.unwrap_or_default();
            let stem = archive::sanitize_archive_stem(&name)?;
            let archives = archive::ensure_archives_dir(&state.app_data_dir)?;
            let archive_path = archives.join(format!("{stem}.rogein"));
            if archive_path.exists() && !overwrite {
                return Err(format!(
                    "Архив уже существует: {}. Укажите другое имя или подтвердите перезапись.",
                    archive_path
                        .file_name()
                        .and_then(|n| n.to_str())
                        .unwrap_or("archive.rogein")
                ));
            }
            (archive_path, stem)
        }
        other => {
            return Err(format!(
                "Неизвестный режим завершения старта: {other}. Ожидается overwrite_active или save_as."
            ));
        }
    };

    let manifest =
        archive::create_start_archive(&state.db_path, &map_path, &archive_path, &title)?;
    archive::wipe_working_start(&state.app_data_dir, &state.db_path, &map_path)?;

    Ok(ArchiveActionResult {
        archive_path: archive_path.to_string_lossy().to_string(),
        title: manifest.title,
        cleared: true,
    })
}

#[tauri::command]
fn open_start_archive(
    state: State<'_, AppState>,
    archive_path: String,
    archive_current_first: Option<bool>,
) -> Result<ArchiveActionResult, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;

    let path = archive::resolve_archive_path(&state.app_data_dir, &archive_path)?;
    if !path.exists() {
        return Err(format!("Файл архива не найден: {}", path.display()));
    }

    let map_path = course_map_path(&state);
    if archive_current_first.unwrap_or(false) {
        let auto_name = {
            let conn = domain::open_and_init_db(&state.db_path)?;
            let settings = domain::get_settings(&conn)?;
            archive::default_archive_title(&settings.competition_date)
        };
        let stem = archive::sanitize_archive_stem(&auto_name)?;
        let archives = archive::ensure_archives_dir(&state.app_data_dir)?;
        let mut dest = archives.join(format!("{stem}.rogein"));
        let mut idx = 2u32;
        while dest.exists() {
            dest = archives.join(format!("{stem}_{idx}.rogein"));
            idx += 1;
        }
        let title = dest
            .file_stem()
            .and_then(|s| s.to_str())
            .unwrap_or(&stem)
            .to_string();
        archive::create_start_archive(&state.db_path, &map_path, &dest, &title)?;
    }

    let manifest = archive::restore_start_archive(&path, &state.db_path, &map_path)?;
    archive::set_active_archive(&state.app_data_dir, &path, &manifest.title)?;
    Ok(ArchiveActionResult {
        archive_path: path.to_string_lossy().to_string(),
        title: manifest.title,
        cleared: false,
    })
}

#[tauri::command]
fn pick_start_archive_file() -> Result<Option<String>, String> {
    Ok(archive::pick_archive_file().map(|p| p.to_string_lossy().to_string()))
}

#[tauri::command]
fn import_csv_content(
    state: State<'_, AppState>,
    csv_content: String,
    reset: bool,
) -> Result<ImportSummary, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    domain::import_csv_content(&mut conn, &csv_content, reset)
}

#[tauri::command]
fn import_phone_snapshot(state: State<'_, AppState>) -> Result<PhoneReadSummary, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let read = phone_sync::read_phone_snapshot()?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    let summary: PhoneImportSummary = domain::import_phone_snapshot(&mut conn, &read.body)?;
    let deleted = phone_sync::delete_snapshot_if_unchanged(&read)?;
    Ok(PhoneReadSummary {
        imported: summary.imported,
        already_imported: summary.already_imported,
        unmatched: summary.unmatched,
        skipped_empty: summary.skipped_empty,
        participants_count: summary.participants_count,
        results_count: summary.results_count,
        snapshot_deleted: deleted.deleted,
        snapshot_note: deleted.note,
    })
}

#[tauri::command]
fn import_start_protocol_content(
    state: State<'_, AppState>,
    csv_content: String,
    reset: bool,
) -> Result<StartProtocolImportSummary, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::import_start_protocol_content(&conn, &csv_content, reset)
}

#[tauri::command]
fn recalculate_results(state: State<'_, AppState>) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    domain::recalculate(&mut conn)
}

#[tauri::command]
fn get_settings(state: State<'_, AppState>) -> Result<Settings, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::get_settings(&conn)
}

#[tauri::command]
fn set_settings(
    state: State<'_, AppState>,
    control_minutes: Option<i64>,
    penalty_per_minute: Option<i64>,
    dq_minutes: Option<i64>,
    finish_cp: Option<i64>,
    start_mode: Option<String>,
    start_cp: Option<i64>,
    competition_date: Option<String>,
    competition_start_time: Option<String>,
    sport_kind: Option<String>,
) -> Result<Settings, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::set_settings(
        &conn,
        control_minutes,
        penalty_per_minute,
        dq_minutes,
        finish_cp,
        start_mode,
        start_cp,
        competition_date,
        competition_start_time,
        sport_kind,
    )
}

#[derive(Debug, Serialize)]
struct SwitchSportKindResult {
    settings: Settings,
    archived: bool,
    archive_title: Option<String>,
}

#[tauri::command]
fn switch_sport_kind(
    state: State<'_, AppState>,
    sport_kind: String,
    archive_current_first: Option<bool>,
    archive_name: Option<String>,
    overwrite: Option<bool>,
) -> Result<SwitchSportKindResult, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let kind = domain::normalize_sport_kind(&sport_kind)?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    let current = domain::get_settings(&conn)?;
    if current.sport_kind == kind {
        return Ok(SwitchSportKindResult {
            settings: current,
            archived: false,
            archive_title: None,
        });
    }
    let has_data = domain::working_start_has_data(&conn)?;
    drop(conn);

    let mut next = current.clone();
    next.sport_kind = kind.clone();
    if kind == "orient" {
        next.start_mode = "station".to_string();
    }

    let mut archived = false;
    let mut archive_title = None;
    let map_path = course_map_path(&state);
    if archive_current_first.unwrap_or(false) {
        if !has_data {
            return Err("Нечего сохранять в архив: рабочая база пуста.".into());
        }
        let name = archive_name.unwrap_or_default();
        let stem = archive::sanitize_archive_stem(&name)?;
        let archives = archive::ensure_archives_dir(&state.app_data_dir)?;
        let archive_path = archives.join(format!("{stem}.rogein"));
        if archive_path.exists() && !overwrite.unwrap_or(false) {
            return Err(format!(
                "Архив уже существует: {}. Укажите другое имя или подтвердите перезапись.",
                archive_path
                    .file_name()
                    .and_then(|n| n.to_str())
                    .unwrap_or("archive.rogein")
            ));
        }
        let manifest =
            archive::create_start_archive(&state.db_path, &map_path, &archive_path, &stem)?;
        archived = true;
        archive_title = Some(manifest.title);
        archive::wipe_working_start(&state.app_data_dir, &state.db_path, &map_path)?;
    } else if has_data {
        archive::wipe_working_start(&state.app_data_dir, &state.db_path, &map_path)?;
    }

    let conn = domain::open_and_init_db(&state.db_path)?;
    let settings = domain::put_settings(&conn, &next)?;
    Ok(SwitchSportKindResult {
        settings,
        archived,
        archive_title,
    })
}

#[tauri::command]
fn get_results(
    state: State<'_, AppState>,
    limit: Option<i64>,
    offset: Option<i64>,
    status: Option<String>,
    search: Option<String>,
    format_id: Option<i64>,
    only_missing_format: Option<bool>,
    award_group_id: Option<i64>,
    course_name: Option<String>,
    sort_by: Option<String>,
    sort_dir: Option<String>,
) -> Result<ResultsResponse, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    let safe_limit = limit.unwrap_or(50);
    let safe_offset = offset.unwrap_or(0);
    let missing = only_missing_format.unwrap_or(false);
    let (rows, total_count) = domain::query_results(
        &conn,
        safe_limit,
        safe_offset,
        status,
        search,
        format_id,
        missing,
        award_group_id,
        course_name.clone(),
        sort_by,
        sort_dir,
    )?;
    let counts = domain::query_status_counts(
        &conn,
        format_id,
        missing,
        award_group_id,
        course_name,
    )?;
    Ok(ResultsResponse {
        rows,
        counts,
        total_count,
        offset: safe_offset,
        limit: safe_limit,
    })
}

#[tauri::command]
fn get_error_list(state: State<'_, AppState>) -> Result<Vec<ErrorRow>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::query_error_list(&conn)
}

#[tauri::command]
fn get_participants(state: State<'_, AppState>, limit: Option<i64>) -> Result<Vec<ParticipantRow>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::query_participants(&conn, limit.unwrap_or(200))
}

#[tauri::command]
fn suggest_results_search(
    state: State<'_, AppState>,
    query: String,
    limit: Option<i64>,
) -> Result<Vec<SearchSuggestion>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::suggest_results_search(&conn, &query, limit.unwrap_or(12))
}

#[tauri::command]
fn suggest_start_protocol_search(
    state: State<'_, AppState>,
    query: String,
    limit: Option<i64>,
) -> Result<Vec<SearchSuggestion>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::suggest_start_protocol_search(&conn, &query, limit.unwrap_or(12))
}

#[tauri::command]
fn get_start_protocol(
    state: State<'_, AppState>,
    limit: Option<i64>,
    offset: Option<i64>,
    search: Option<String>,
    format_id: Option<i64>,
    only_incomplete: Option<bool>,
    only_missing_format: Option<bool>,
) -> Result<StartProtocolResponse, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    let safe_limit = limit.unwrap_or(200);
    let safe_offset = offset.unwrap_or(0);
    let (rows, total_count) = domain::query_start_protocol(
        &conn,
        safe_limit,
        safe_offset,
        search,
        format_id,
        only_incomplete.unwrap_or(false),
        only_missing_format.unwrap_or(false),
    )?;
    Ok(StartProtocolResponse {
        rows,
        total_count,
        offset: safe_offset,
        limit: safe_limit,
    })
}

#[tauri::command]
fn get_start_protocol_formats(state: State<'_, AppState>) -> Result<Vec<String>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::query_start_protocol_formats(&conn)
}

#[tauri::command]
fn get_start_protocol_format_rows(
    state: State<'_, AppState>,
) -> Result<Vec<StartProtocolFormatRow>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::query_start_protocol_format_rows(&conn)
}

#[tauri::command]
fn add_start_protocol_format(
    state: State<'_, AppState>,
    format_name: String,
) -> Result<Vec<StartProtocolFormatRow>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::add_start_protocol_format(&conn, format_name)
}

#[tauri::command]
fn rename_start_protocol_format(
    state: State<'_, AppState>,
    format_id: i64,
    new_format_name: String,
) -> Result<Vec<StartProtocolFormatRow>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::rename_start_protocol_format(&conn, format_id, new_format_name)
}

#[tauri::command]
fn delete_start_protocol_format(
    state: State<'_, AppState>,
    format_id: i64,
) -> Result<Vec<StartProtocolFormatRow>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::delete_start_protocol_format(&conn, format_id)
}

#[tauri::command]
fn get_award_groups(state: State<'_, AppState>) -> Result<Vec<AwardGroupRow>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::query_award_groups(&conn)
}

#[tauri::command]
fn upsert_award_group(
    state: State<'_, AppState>,
    group_id: Option<i64>,
    name: String,
    gender_mode: String,
    format_ids: Vec<i64>,
    min_age: Option<i64>,
    sort_order: Option<i64>,
) -> Result<AwardGroupRow, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::upsert_award_group(&conn, group_id, name, gender_mode, format_ids, min_age, sort_order)
}

#[tauri::command]
fn delete_award_group(state: State<'_, AppState>, group_id: i64) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::delete_award_group(&conn, group_id)
}

#[tauri::command]
fn get_cp_legends(state: State<'_, AppState>) -> Result<Vec<CpLegendRow>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::query_cp_legends(&conn)
}

#[tauri::command]
fn get_cp_legend_type_rows(state: State<'_, AppState>) -> Result<Vec<CpLegendTypeRow>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::query_cp_legend_type_rows(&conn)
}

#[tauri::command]
fn add_cp_legend_type(
    state: State<'_, AppState>,
    type_name: String,
) -> Result<Vec<CpLegendTypeRow>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::add_cp_legend_type(&conn, type_name)
}

#[tauri::command]
fn rename_cp_legend_type(
    state: State<'_, AppState>,
    type_id: i64,
    new_type_name: String,
) -> Result<Vec<CpLegendTypeRow>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::rename_cp_legend_type(&conn, type_id, new_type_name)
}

#[tauri::command]
fn delete_cp_legend_type(
    state: State<'_, AppState>,
    type_id: i64,
) -> Result<Vec<CpLegendTypeRow>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::delete_cp_legend_type(&conn, type_id)
}

#[tauri::command]
fn upsert_cp_legend(
    state: State<'_, AppState>,
    legend_id: Option<i64>,
    cp_number: i64,
    name: String,
    cp_type_id: Option<i64>,
) -> Result<CpLegendRow, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::upsert_cp_legend(&conn, legend_id, cp_number, name, cp_type_id)
}

#[tauri::command]
fn set_cp_legend_map_position(
    state: State<'_, AppState>,
    legend_id: i64,
    map_x: Option<f64>,
    map_y: Option<f64>,
) -> Result<CpLegendRow, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::set_cp_legend_map_position(&conn, legend_id, map_x, map_y)
}

#[tauri::command]
fn get_course_map_special_points(
    state: State<'_, AppState>,
) -> Result<CourseMapSpecialPoints, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::get_course_map_special_points(&conn)
}

#[tauri::command]
fn set_course_map_special_position(
    state: State<'_, AppState>,
    point: String,
    map_x: Option<f64>,
    map_y: Option<f64>,
) -> Result<CourseMapSpecialPoints, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::set_course_map_special_position(&conn, point, map_x, map_y)
}

#[tauri::command]
fn delete_cp_legend(state: State<'_, AppState>, legend_id: i64) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::delete_cp_legend(&conn, legend_id)
}

#[tauri::command]
fn import_cp_legends_content(
    state: State<'_, AppState>,
    csv_content: String,
    reset: bool,
) -> Result<CpLegendImportSummary, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::import_cp_legends_content(&conn, &csv_content, reset)
}

#[tauri::command]
fn import_courses_content(
    state: State<'_, AppState>,
    csv_content: String,
    reset: bool,
) -> Result<CoursesImportSummary, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::import_courses_content(&conn, &csv_content, reset)
}

#[tauri::command]
fn get_courses(state: State<'_, AppState>) -> Result<Vec<CourseRow>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::list_courses(&conn)
}

#[tauri::command]
fn save_course(
    state: State<'_, AppState>,
    course_id: Option<i64>,
    name: String,
    controls: Vec<i64>,
) -> Result<CourseRow, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    let row = domain::save_course(&conn, course_id, name, controls)?;
    domain::recalculate(&mut conn)?;
    Ok(row)
}

#[tauri::command]
fn delete_course(state: State<'_, AppState>, course_id: i64) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    domain::delete_course(&conn, course_id)?;
    domain::recalculate(&mut conn)?;
    Ok(())
}

#[tauri::command]
fn get_course_map_info(state: State<'_, AppState>) -> Result<CourseMapInfo, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    let path = course_map_path(&state);
    let has_file = path.is_file();
    let file_name = domain::get_setting_value(&conn, COURSE_MAP_NAME_KEY)?;
    let mime_type = domain::get_setting_value(&conn, COURSE_MAP_MIME_KEY)?;
    Ok(CourseMapInfo {
        has_map: has_file && file_name.is_some(),
        file_name,
        mime_type,
    })
}

#[tauri::command]
fn get_course_map_payload(state: State<'_, AppState>) -> Result<Option<CourseMapPayload>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    let path = course_map_path(&state);
    if !path.is_file() {
        return Ok(None);
    }
    let file_name = domain::get_setting_value(&conn, COURSE_MAP_NAME_KEY)?
        .unwrap_or_else(|| "map".to_string());
    let mime_type = domain::get_setting_value(&conn, COURSE_MAP_MIME_KEY)?
        .unwrap_or_else(|| "application/octet-stream".to_string());
    let bytes = fs::read(&path).map_err(|e| format!("read course map: {e}"))?;
    Ok(Some(CourseMapPayload {
        file_name,
        mime_type,
        data_base64: base64::Engine::encode(&base64::engine::general_purpose::STANDARD, bytes),
    }))
}

#[tauri::command]
fn save_course_map(
    state: State<'_, AppState>,
    file_name: String,
    content_base64: String,
    mime_type: Option<String>,
) -> Result<CourseMapInfo, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let name = file_name.trim().to_string();
    if name.is_empty() {
        return Err("Имя файла карты пустое".to_string());
    }
    let bytes = base64::Engine::decode(
        &base64::engine::general_purpose::STANDARD,
        content_base64.trim(),
    )
    .map_err(|e| format!("decode course map: {e}"))?;
    if bytes.is_empty() {
        return Err("Файл карты пустой".to_string());
    }
    let mime = mime_type
        .map(|m| m.trim().to_string())
        .filter(|m| !m.is_empty())
        .unwrap_or_else(|| guess_image_mime(&name));
    if !mime.starts_with("image/") && mime != "application/octet-stream" {
        return Err(format!(
            "Ожидается изображение, получен тип «{mime}»"
        ));
    }

    let path = course_map_path(&state);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("create map dir: {e}"))?;
    }
    fs::write(&path, &bytes).map_err(|e| format!("write course map: {e}"))?;

    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::set_setting_value(&conn, COURSE_MAP_NAME_KEY, &name)?;
    domain::set_setting_value(&conn, COURSE_MAP_MIME_KEY, &mime)?;
    Ok(CourseMapInfo {
        has_map: true,
        file_name: Some(name),
        mime_type: Some(mime),
    })
}

#[tauri::command]
fn clear_course_map(
    state: State<'_, AppState>,
    clear_positions: Option<bool>,
) -> Result<CourseMapInfo, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let path = course_map_path(&state);
    if path.exists() {
        fs::remove_file(&path).map_err(|e| format!("remove course map: {e}"))?;
    }
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::delete_setting_value(&conn, COURSE_MAP_NAME_KEY)?;
    domain::delete_setting_value(&conn, COURSE_MAP_MIME_KEY)?;
    if clear_positions.unwrap_or(false) {
        domain::clear_all_course_map_positions(&conn)?;
    }
    Ok(CourseMapInfo {
        has_map: false,
        file_name: None,
        mime_type: None,
    })
}

#[tauri::command]
fn get_map_georef_info(state: State<'_, AppState>) -> Result<MapGeorefInfo, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::get_map_georef_info(&conn)
}

#[tauri::command]
fn set_map_scale_denominator(
    state: State<'_, AppState>,
    denominator: Option<i64>,
) -> Result<MapGeorefInfo, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::set_map_scale_denominator(&conn, denominator)?;
    domain::get_map_georef_info(&conn)
}

#[tauri::command]
fn set_course_map_image_size(
    state: State<'_, AppState>,
    width: f64,
    height: f64,
) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::set_course_map_image_size(&conn, width, height)
}

#[tauri::command]
fn list_map_gps_anchors(state: State<'_, AppState>) -> Result<Vec<MapGpsAnchor>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::list_map_gps_anchors(&conn)
}

#[tauri::command]
fn upsert_map_gps_anchor(
    state: State<'_, AppState>,
    item: MapGpsAnchorUpsert,
) -> Result<MapGpsAnchor, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::upsert_map_gps_anchor(&conn, item)
}

#[tauri::command]
fn delete_map_gps_anchor(state: State<'_, AppState>, anchor_id: i64) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::delete_map_gps_anchor(&conn, anchor_id)
}

#[tauri::command]
fn get_participant_path_distance(
    state: State<'_, AppState>,
    result_id: i64,
) -> Result<ParticipantPathDistance, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    domain::participant_path_distance_m(&mut conn, result_id)
}

#[tauri::command]
fn get_format_cp_type_rules(
    state: State<'_, AppState>,
) -> Result<Vec<FormatCpTypeRulesBundle>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::query_format_cp_type_rules(&conn)
}

#[tauri::command]
fn set_format_cp_type_rules(
    state: State<'_, AppState>,
    format_id: i64,
    rules: Vec<FormatCpTypeRuleInput>,
) -> Result<Vec<FormatCpTypeRulesBundle>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::set_format_cp_type_rules(&conn, format_id, rules)
}

#[tauri::command]
fn delete_format_cp_type_rules(
    state: State<'_, AppState>,
    format_id: i64,
) -> Result<Vec<FormatCpTypeRulesBundle>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::delete_format_cp_type_rules(&conn, format_id)
}

#[tauri::command]
fn get_format_settings(state: State<'_, AppState>) -> Result<Vec<FormatSettings>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::query_format_settings(&conn)
}

#[tauri::command]
fn set_format_settings(
    state: State<'_, AppState>,
    format_id: i64,
    control_minutes: Option<i64>,
    penalty_per_minute: Option<i64>,
    dq_minutes: Option<i64>,
    finish_cp: Option<i64>,
    competition_start_time: Option<String>,
) -> Result<FormatSettings, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::set_format_settings(
        &conn,
        format_id,
        control_minutes,
        penalty_per_minute,
        dq_minutes,
        finish_cp,
        competition_start_time,
    )
}

#[tauri::command]
fn add_start_protocol_entry(
    state: State<'_, AppState>,
    participant_id: String,
    name: String,
    format_id: Option<i64>,
    gender: Option<String>,
    birth_date_raw: Option<String>,
) -> Result<StartProtocolRow, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::add_start_protocol_entry(
        &conn,
        participant_id,
        name,
        format_id,
        gender,
        birth_date_raw,
    )
}

#[tauri::command]
fn update_start_protocol_entry(
    state: State<'_, AppState>,
    entry_id: i64,
    participant_id: String,
    name: String,
    format_id: Option<i64>,
    gender: Option<String>,
    birth_date_raw: Option<String>,
) -> Result<StartProtocolRow, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::update_start_protocol_entry(
        &conn,
        entry_id,
        participant_id,
        name,
        format_id,
        gender,
        birth_date_raw,
    )
}

#[tauri::command]
fn merge_start_protocol_team(
    state: State<'_, AppState>,
    entry_ids: Vec<i64>,
) -> Result<i64, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    let team_id = domain::merge_start_protocol_team(&conn, entry_ids)?;
    domain::recalculate(&mut conn)?;
    Ok(team_id)
}

#[tauri::command]
fn leave_start_protocol_team(
    state: State<'_, AppState>,
    entry_ids: Vec<i64>,
) -> Result<i64, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    let left = domain::leave_start_protocol_teams(&conn, entry_ids)?;
    domain::recalculate(&mut conn)?;
    Ok(left)
}

#[tauri::command]
fn dissolve_start_protocol_team(
    state: State<'_, AppState>,
    team_id: i64,
) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    domain::dissolve_start_protocol_team(&conn, team_id)?;
    domain::recalculate(&mut conn)
}

#[tauri::command]
fn get_participant_details(
    state: State<'_, AppState>,
    result_id: i64,
) -> Result<ParticipantDetails, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    domain::query_participant_details(&mut conn, result_id)
}

#[tauri::command]
fn find_result_id(
    state: State<'_, AppState>,
    participant_id: String,
    name: String,
) -> Result<Option<i64>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::find_result_id(&conn, &participant_id, &name)
}

#[tauri::command]
fn add_cp_correction(
    app: AppHandle,
    state: State<'_, AppState>,
    participant_id: String,
    cp_number: i64,
    mark_time: Option<String>,
) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    domain::add_cp_correction(
        &conn,
        &participant_id,
        cp_number,
        mark_time.unwrap_or_default().as_str(),
    )?;
    recalculate_and_notify(&app, &mut conn)
}

#[tauri::command]
fn assign_course_correction(
    app: AppHandle,
    state: State<'_, AppState>,
    participant_id: String,
    course_name: String,
) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    domain::assign_course_correction(&conn, &participant_id, &course_name)?;
    recalculate_and_notify(&app, &mut conn)
}

#[tauri::command]
fn set_start_mark_correction(
    app: AppHandle,
    state: State<'_, AppState>,
    participant_id: String,
    mark_time: String,
) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    domain::set_start_mark_correction(&conn, &participant_id, mark_time)?;
    recalculate_and_notify(&app, &mut conn)
}

#[tauri::command]
fn add_anomaly_day_shift_correction(
    app: AppHandle,
    state: State<'_, AppState>,
    participant_id: String,
) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    domain::add_anomaly_day_shift_correction(&conn, &participant_id)?;
    recalculate_and_notify(&app, &mut conn)
}

#[tauri::command]
fn add_anomaly_day_shift_corrections_for_all(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<BulkAnomalyCorrectionSummary, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    let summary = domain::add_anomaly_day_shift_corrections_for_all(&conn)?;
    recalculate_and_notify(&app, &mut conn)?;
    Ok(summary)
}

#[tauri::command]
fn rollback_anomaly_day_shift_corrections_for_all(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<BulkAnomalyRollbackSummary, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    let summary = domain::rollback_anomaly_day_shift_corrections_for_all(&conn)?;
    recalculate_and_notify(&app, &mut conn)?;
    Ok(summary)
}

#[tauri::command]
fn get_anomaly_bulk_actions_state(
    state: State<'_, AppState>,
) -> Result<AnomalyBulkActionsState, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::get_anomaly_bulk_actions_state(&conn)
}

#[tauri::command]
fn remove_cp_correction(
    app: AppHandle,
    state: State<'_, AppState>,
    participant_id: Option<String>,
    cp_number: i64,
    remove_mode: Option<String>,
    participant_scope: Option<String>,
) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    domain::remove_cp_correction(
        &conn,
        participant_id,
        cp_number,
        remove_mode.unwrap_or_else(|| "remove_legs".to_string()),
        participant_scope.unwrap_or_else(|| "one".to_string()),
    )?;
    recalculate_and_notify(&app, &mut conn)
}

#[tauri::command]
fn add_exclusion_rule(
    state: State<'_, AppState>,
    participant_scope: String,
    participant_id: Option<String>,
    format_id: Option<i64>,
    course_id: Option<i64>,
    from_cp: i64,
    to_cp: i64,
    direction: String,
    apply_mode: String,
    max_leg_seconds: Option<i64>,
) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::add_exclusion_rule(
        &conn,
        participant_scope,
        participant_id,
        format_id,
        course_id,
        from_cp,
        to_cp,
        direction,
        apply_mode,
        max_leg_seconds,
    )
}

#[tauri::command]
fn update_exclusion_rule(
    state: State<'_, AppState>,
    rule_id: i64,
    participant_scope: String,
    participant_id: Option<String>,
    format_id: Option<i64>,
    course_id: Option<i64>,
    from_cp: i64,
    to_cp: i64,
    direction: String,
    apply_mode: String,
    max_leg_seconds: Option<i64>,
) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::update_exclusion_rule(
        &conn,
        rule_id,
        participant_scope,
        participant_id,
        format_id,
        course_id,
        from_cp,
        to_cp,
        direction,
        apply_mode,
        max_leg_seconds,
    )
}

#[tauri::command]
fn delete_exclusion_rule(state: State<'_, AppState>, rule_id: i64) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::delete_exclusion_rule(&conn, rule_id)
}

#[tauri::command]
fn get_exclusion_rules(
    state: State<'_, AppState>,
    participant_id: Option<String>,
    format_id: Option<i64>,
) -> Result<Vec<ExclusionRuleRow>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::query_exclusion_rules(&conn, participant_id, format_id)
}

#[tauri::command]
fn get_manual_corrections(
    state: State<'_, AppState>,
    participant_id: Option<String>,
) -> Result<Vec<CorrectionRow>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::query_manual_corrections(&conn, participant_id)
}

#[tauri::command]
fn get_anomaly_list(state: State<'_, AppState>) -> Result<AnomalyListResponse, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::query_anomaly_list(&conn)
}

#[tauri::command]
fn analyze_cp_station_remap(
    state: State<'_, AppState>,
    from_cp: i64,
    to_cp: i64,
) -> Result<CpRemapAnalyzeSummary, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::analyze_cp_station_remap(&conn, from_cp, to_cp)
}

#[tauri::command]
fn apply_cp_remap_corrections(
    app: AppHandle,
    state: State<'_, AppState>,
    items: Vec<CpRemapApplyItem>,
) -> Result<CpRemapApplySummary, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    let summary = domain::apply_cp_remap_corrections(&conn, &items)?;
    recalculate_and_notify(&app, &mut conn)?;
    Ok(summary)
}

#[tauri::command]
fn open_aux_window(
    app: AppHandle,
    label: String,
    title: String,
    url: String,
    width: Option<f64>,
    height: Option<f64>,
) -> Result<(), String> {
    let label = label.trim().to_string();
    if label.is_empty() {
        return Err("label is empty".to_string());
    }
    let raw_url = url.trim().to_string();
    if raw_url.is_empty() {
        return Err("url is empty".to_string());
    }
    if let Some(existing) = app.get_webview_window(&label) {
        let _ = existing.close();
    }
    // Allow a short moment for the previous window to release the label.
    std::thread::sleep(std::time::Duration::from_millis(40));

    let fullscreen = app.webview_windows().values().any(|w| w.is_fullscreen().ok().unwrap_or(false));

    let webview_url = resolve_aux_window_url(&app, &raw_url)?;
    WebviewWindowBuilder::new(&app, &label, webview_url)
        .title(title)
        .inner_size(width.unwrap_or(1220.0), height.unwrap_or(900.0))
        .fullscreen(fullscreen)
        .resizable(true)
        .maximizable(true)
        .minimizable(true)
        .closable(true)
        .focused(true)
        .visible(true)
        .build()
        .map_err(|e| format!("create window '{label}': {e}"))?;
    Ok(())
}

fn resolve_aux_window_url(app: &AppHandle, raw_url: &str) -> Result<WebviewUrl, String> {
    let fragment = raw_url
        .strip_prefix('#')
        .unwrap_or(raw_url)
        .trim_start_matches('/');

    // Match participant-card behavior: same origin as main window + hash route.
    if let Some(main) = app.get_webview_window("main") {
        if let Ok(mut current) = main.url() {
            current.set_path("/");
            current.set_query(None);
            current.set_fragment(Some(fragment));
            return Ok(WebviewUrl::External(current));
        }
    }

    Ok(WebviewUrl::App(format!("index.html#{fragment}").into()))
}

#[tauri::command]
fn scan_anomalies(state: State<'_, AppState>) -> Result<AnomalyScanSummary, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::scan_anomalies(&conn)
}

#[tauri::command]
fn delete_correction_entry(
    app: AppHandle,
    state: State<'_, AppState>,
    source_table: String,
    correction_id: i64,
) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    domain::delete_correction_entry(&conn, source_table, correction_id)?;
    recalculate_and_notify(&app, &mut conn)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_sql::Builder::new().build())
        .setup(|app| {
            let app_data_dir = app
                .path()
                .app_data_dir()
                .map_err(|e| format!("resolve app data dir: {e}"))?;
            fs::create_dir_all(&app_data_dir).map_err(|e| format!("create app data dir: {e}"))?;
            archive::ensure_archives_dir(&app_data_dir)?;
            let db_path = app_data_dir.join("rogein_v01.sqlite3");
            let conn = domain::open_and_init_db(&db_path)?;
            drop(conn);
            app.manage(AppState {
                app_data_dir,
                db_path,
                db_lock: Mutex::new(()),
            });

            if cfg!(debug_assertions) {
                app.handle().plugin(
                    tauri_plugin_log::Builder::default()
                        .level(log::LevelFilter::Info)
                        .build(),
                )?;
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            import_csv_content,
            import_phone_snapshot,
            import_start_protocol_content,
            get_data_presence_counts,
            get_archives_dir,
            list_start_archives,
            get_active_start_archive,
            suggest_finish_start_name,
            finish_current_start,
            open_start_archive,
            pick_start_archive_file,
            get_site_publish_settings,
            save_site_publish_settings,
            test_site_publish_connection,
            preview_archive_orient_csv,
            publish_archive_orient_csv,
            publish_current_start,
            recalculate_results,
            get_settings,
            set_settings,
            switch_sport_kind,
            get_results,
            get_error_list,
            get_participants,
            suggest_results_search,
            suggest_start_protocol_search,
            get_start_protocol,
            get_start_protocol_formats,
            get_start_protocol_format_rows,
            add_start_protocol_format,
            rename_start_protocol_format,
            delete_start_protocol_format,
            get_award_groups,
            upsert_award_group,
            delete_award_group,
            get_cp_legends,
            get_cp_legend_type_rows,
            add_cp_legend_type,
            rename_cp_legend_type,
            delete_cp_legend_type,
            upsert_cp_legend,
            set_cp_legend_map_position,
            get_course_map_special_points,
            set_course_map_special_position,
            delete_cp_legend,
            import_cp_legends_content,
            import_courses_content,
            get_courses,
            save_course,
            delete_course,
            get_course_map_info,
            get_course_map_payload,
            save_course_map,
            clear_course_map,
            get_map_georef_info,
            set_map_scale_denominator,
            set_course_map_image_size,
            list_map_gps_anchors,
            upsert_map_gps_anchor,
            delete_map_gps_anchor,
            get_participant_path_distance,
            get_format_cp_type_rules,
            set_format_cp_type_rules,
            delete_format_cp_type_rules,
            get_format_settings,
            set_format_settings,
            add_start_protocol_entry,
            update_start_protocol_entry,
            merge_start_protocol_team,
            leave_start_protocol_team,
            dissolve_start_protocol_team,
            get_participant_details,
            find_result_id,
            add_cp_correction,
            assign_course_correction,
            set_start_mark_correction,
            add_anomaly_day_shift_correction,
            add_anomaly_day_shift_corrections_for_all,
            rollback_anomaly_day_shift_corrections_for_all,
            get_anomaly_bulk_actions_state,
            get_anomaly_list,
            analyze_cp_station_remap,
            apply_cp_remap_corrections,
            open_aux_window,
            remove_cp_correction,
            add_exclusion_rule,
            update_exclusion_rule,
            delete_exclusion_rule,
            get_exclusion_rules,
            get_manual_corrections,
            delete_correction_entry,
            scan_anomalies
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
