mod domain;

use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;

use domain::{
    AnomalyBulkActionsState, AnomalyScanSummary, BulkAnomalyCorrectionSummary,
    BulkAnomalyRollbackSummary, CorrectionRow, ExclusionRuleRow, ImportSummary, ParticipantDetails,
    ParticipantRow, ResultRow, Settings,
};
use serde::Serialize;
use tauri::{Manager, State};

struct AppState {
    db_path: PathBuf,
    db_lock: Mutex<()>,
}

#[derive(Serialize)]
struct ResultsResponse {
    rows: Vec<ResultRow>,
    counts: std::collections::BTreeMap<String, i64>,
    total_count: i64,
    offset: i64,
    limit: i64,
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
    competition_date: Option<String>,
    competition_start_time: Option<String>,
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
        competition_date,
        competition_start_time,
    )
}

#[tauri::command]
fn get_results(
    state: State<'_, AppState>,
    limit: Option<i64>,
    offset: Option<i64>,
    status: Option<String>,
    search: Option<String>,
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
    let (rows, total_count) = domain::query_results(
        &conn,
        safe_limit,
        safe_offset,
        status,
        search,
        sort_by,
        sort_dir,
    )?;
    let counts = domain::query_status_counts(&conn)?;
    Ok(ResultsResponse {
        rows,
        counts,
        total_count,
        offset: safe_offset,
        limit: safe_limit,
    })
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
fn get_participant_details(
    state: State<'_, AppState>,
    participant_id: String,
) -> Result<ParticipantDetails, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let mut conn = domain::open_and_init_db(&state.db_path)?;
    domain::query_participant_details(&mut conn, &participant_id)
}

#[tauri::command]
fn add_cp_correction(
    state: State<'_, AppState>,
    participant_id: String,
    cp_number: i64,
    mark_time: Option<String>,
) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::add_cp_correction(
        &conn,
        &participant_id,
        cp_number,
        mark_time.unwrap_or_default().as_str(),
    )
}

#[tauri::command]
fn add_anomaly_day_shift_correction(
    state: State<'_, AppState>,
    participant_id: String,
) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::add_anomaly_day_shift_correction(&conn, &participant_id)
}

#[tauri::command]
fn add_anomaly_day_shift_corrections_for_all(
    state: State<'_, AppState>,
) -> Result<BulkAnomalyCorrectionSummary, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::add_anomaly_day_shift_corrections_for_all(&conn)
}

#[tauri::command]
fn rollback_anomaly_day_shift_corrections_for_all(
    state: State<'_, AppState>,
) -> Result<BulkAnomalyRollbackSummary, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::rollback_anomaly_day_shift_corrections_for_all(&conn)
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
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::remove_cp_correction(
        &conn,
        participant_id,
        cp_number,
        remove_mode.unwrap_or_else(|| "remove_legs".to_string()),
        participant_scope.unwrap_or_else(|| "one".to_string()),
    )
}

#[tauri::command]
fn add_exclusion_rule(
    state: State<'_, AppState>,
    participant_scope: String,
    participant_id: Option<String>,
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
) -> Result<Vec<ExclusionRuleRow>, String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::query_exclusion_rules(&conn, participant_id)
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
    state: State<'_, AppState>,
    source_table: String,
    correction_id: i64,
) -> Result<(), String> {
    let _guard = state
        .db_lock
        .lock()
        .map_err(|_| "database lock poisoned".to_string())?;
    let conn = domain::open_and_init_db(&state.db_path)?;
    domain::delete_correction_entry(&conn, source_table, correction_id)
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
            let db_path = app_data_dir.join("rogein_v01.sqlite3");
            let conn = domain::open_and_init_db(&db_path)?;
            drop(conn);
            app.manage(AppState {
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
            recalculate_results,
            get_settings,
            set_settings,
            get_results,
            get_participants,
            get_participant_details,
            add_cp_correction,
            add_anomaly_day_shift_correction,
            add_anomaly_day_shift_corrections_for_all,
            rollback_anomaly_day_shift_corrections_for_all,
            get_anomaly_bulk_actions_state,
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
