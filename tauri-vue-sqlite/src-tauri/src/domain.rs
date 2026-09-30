use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::Path;

use chrono::{Datelike, Duration, NaiveDate, NaiveDateTime, NaiveTime, Utc};
use csv::StringRecord;
use rusqlite::{params, Connection, OptionalExtension, Transaction};
use rusqlite::functions::FunctionFlags;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

const FOOTER_PREFIX: &str = "Результаты выгружены из SFR Reader";

const STATUS_OK: &str = "OK";
const STATUS_DQ: &str = "Дисквалификация";
const STATUS_ERR: &str = "Ошибка";
/// In start protocol, no finish dump row.
const STATUS_DNS: &str = "Не стартовал";
/// In finish dump, not matched to start protocol.
const STATUS_NOT_IN_PROTOCOL: &str = "Нет в протоколе";

fn is_known_status(status: &str) -> bool {
    matches!(
        status,
        STATUS_OK | STATUS_DQ | STATUS_ERR | STATUS_DNS | STATUS_NOT_IN_PROTOCOL
    )
}

#[derive(Debug, Serialize)]
pub struct ImportSummary {
    pub participants_count: i64,
    pub results_count: i64,
}

#[derive(Debug, Serialize)]
pub struct StartProtocolImportSummary {
    pub imported_rows: i64,
    pub total_rows: i64,
}

#[derive(Debug)]
pub struct CsvFormatError(pub String);

impl std::fmt::Display for CsvFormatError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl std::error::Error for CsvFormatError {}

#[derive(Debug, Clone)]
struct Mark {
    cp_number: i64,
    mark_time: NaiveDateTime,
    seq: i64,
}

#[derive(Debug, Clone)]
struct ExclusionRule {
    from_cp: i64,
    to_cp: i64,
    direction: String,
    apply_mode: String,
    max_leg_seconds: Option<i64>,
    course_id: Option<i64>,
    source: String,
}

#[derive(Debug, Clone)]
struct ManualCorrection {
    finish_participant_id: Option<i64>,
    correction_type: String,
    payload: Value,
}

#[derive(Debug)]
struct Participant {
    /// Surrogate PK of finish dump row.
    id: i64,
    /// Logical / start-protocol id (CSV column C `id`).
    participant_id: String,
    /// Optional physical chip id (CSV column B `chip raw id`).
    chip_raw_id: Option<String>,
    name: String,
    /// Finish dump column E (`course`) — used in orienteering.
    course_name: String,
    #[allow(dead_code)]
    start_station_id: i64,
    #[allow(dead_code)]
    start_time: String,
}

#[derive(Debug, Serialize, Clone)]
pub struct Settings {
    pub control_minutes: i64,
    pub penalty_per_minute: i64,
    pub dq_minutes: i64,
    pub finish_cp: i64,
    /// "station" | "time"
    pub start_mode: String,
    pub start_cp: Option<i64>,
    pub competition_date: String,
    pub competition_start_time: String,
    /// "rogaine" | "orient"
    pub sport_kind: String,
}

#[derive(Debug, Serialize)]
pub struct ResultRow {
    pub id: i64,
    pub finish_participant_id: Option<i64>,
    pub chip_raw_id: Option<String>,
    pub participant_id: String,
    pub name: String,
    pub status: String,
    pub format_id: Option<i64>,
    pub format_name: String,
    pub team_id: Option<i64>,
    pub team_size: i64,
    pub teammates: String,
    pub gender: Option<String>,
    pub age: Option<i64>,
    pub has_personal_corrections: bool,
    pub has_anomalies: bool,
    pub anomaly_count: i64,
    pub points_raw: i64,
    pub penalty_points: i64,
    pub points_final: i64,
    pub elapsed_seconds: i64,
    pub delay_seconds: i64,
    pub penalty_minutes: i64,
    pub diagnostics_json: String,
    pub computed_at: String,
    pub place: Option<i64>,
}

#[derive(Debug, Serialize)]
pub struct ParticipantRow {
    pub participant_id: String,
    pub name: String,
}

#[derive(Debug, Serialize)]
pub struct SearchSuggestion {
    pub participant_id: String,
    pub name: String,
}

#[derive(Debug, Serialize)]
pub struct StartProtocolRow {
    pub id: i64,
    pub participant_id: String,
    pub name: String,
    pub format_id: Option<i64>,
    pub format_name: String,
    pub gender: Option<String>,
    pub birth_date_raw: Option<String>,
    pub birth_date_iso: Option<String>,
    pub source_row: Option<i64>,
    pub team_id: Option<i64>,
    pub team_size: i64,
    pub teammates: String,
    pub has_result: bool,
    pub is_incomplete: bool,
    pub missing_format: bool,
}

#[derive(Debug, Serialize)]
pub struct StartProtocolFormatRow {
    pub id: i64,
    pub format_name: String,
    pub usage_count: i64,
}

#[derive(Debug, Serialize, Clone)]
pub struct AwardGroupRow {
    pub id: i64,
    pub name: String,
    pub gender_mode: String,
    /// Minimum age as of 31 Dec of the competition year. `None` = no age restriction.
    pub min_age: Option<i64>,
    pub sort_order: i64,
    pub format_ids: Vec<i64>,
    pub format_names: Vec<String>,
}

#[derive(Debug, Serialize, Clone)]
pub struct CpLegendRow {
    pub id: i64,
    pub cp_number: i64,
    pub name: String,
    pub cp_type_id: Option<i64>,
    pub cp_type_name: String,
    /// Normalized map X in 0..=1, or null if not placed.
    pub map_x: Option<f64>,
    /// Normalized map Y in 0..=1, or null if not placed.
    pub map_y: Option<f64>,
}

#[derive(Debug, Serialize, Clone)]
pub struct CpLegendTypeRow {
    pub id: i64,
    pub type_name: String,
    pub usage_count: i64,
}

#[derive(Debug, Serialize)]
pub struct CpLegendImportSummary {
    pub imported_rows: i64,
    pub total_rows: i64,
}

#[derive(Debug, Serialize, Clone)]
pub struct CourseRow {
    pub id: i64,
    pub name: String,
    pub controls: Vec<i64>,
}

#[derive(Debug, Serialize)]
pub struct CoursesImportSummary {
    pub imported_rows: i64,
    pub total_rows: i64,
}

#[derive(Debug, Serialize, Clone)]
pub struct FormatCpTypeRuleItem {
    pub cp_type_id: i64,
    pub cp_type_name: String,
    pub max_count: Option<i64>,
}

#[derive(Debug, Serialize, Clone)]
pub struct FormatCpTypeRulesBundle {
    pub format_id: i64,
    pub format_name: String,
    pub rules: Vec<FormatCpTypeRuleItem>,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct FormatCpTypeRuleInput {
    pub cp_type_id: i64,
    pub max_count: Option<i64>,
}

/// Gender filter for an award group:
/// - `any` — без ограничения по полу
/// - `male` — мужчины и мужские команды
/// - `female` — женщины и женские команды
/// - `mixed` — только смешанные команды
pub const AWARD_GENDER_ANY: &str = "any";
pub const AWARD_GENDER_MALE: &str = "male";
pub const AWARD_GENDER_FEMALE: &str = "female";
pub const AWARD_GENDER_MIXED: &str = "mixed";

fn normalize_award_gender_mode(raw: &str) -> Result<String, String> {
    let mode = raw.trim().to_lowercase();
    match mode.as_str() {
        "any" | "все" | "" => Ok(AWARD_GENDER_ANY.to_string()),
        "male" | "мужской" | "мужчины" | "мужские" => Ok(AWARD_GENDER_MALE.to_string()),
        "female" | "женский" | "женщины" | "женские" => Ok(AWARD_GENDER_FEMALE.to_string()),
        "mixed" | "смешанный" | "смешанные" => Ok(AWARD_GENDER_MIXED.to_string()),
        _ => Err(format!(
            "Неизвестный режим пола «{raw}». Допустимо: any, male, female, mixed"
        )),
    }
}

fn normalize_award_min_age(raw: Option<i64>) -> Result<Option<i64>, String> {
    match raw {
        None | Some(0) => Ok(None),
        Some(age) if (1..=120).contains(&age) => Ok(Some(age)),
        Some(age) => Err(format!(
            "Минимальный возраст {age} недопустим. Укажите значение от 1 до 120 или оставьте пустым."
        )),
    }
}

/// Birth year of a start_protocol alias, or NULL if unknown.
fn sql_birth_year(alias: &str) -> String {
    format!(
        r#"CASE
            WHEN TRIM(IFNULL({alias}.birth_date_iso, '')) <> ''
              THEN CAST(strftime('%Y', {alias}.birth_date_iso) AS INTEGER)
            WHEN TRIM(IFNULL({alias}.birth_date_raw, '')) GLOB '[0-9][0-9].[0-9][0-9].[0-9][0-9][0-9][0-9]'
              THEN CAST(substr(TRIM({alias}.birth_date_raw), -4) AS INTEGER)
            ELSE NULL
          END"#
    )
}

/// Personal age on 31 December of the competition year.
fn sql_person_age(alias: &str, competition_year: i64) -> String {
    let birth_year = sql_birth_year(alias);
    format!("({competition_year} - {birth_year})")
}

/// Award age: personal, or youngest teammate. NULL if any teammate has no birth date.
fn sql_award_age(competition_year: i64) -> String {
    let person = sql_person_age("sp", competition_year);
    let teammate = sql_person_age("tm", competition_year);
    format!(
        r#"(CASE
            WHEN sp.team_id IS NULL THEN {person}
            WHEN EXISTS (
                SELECT 1 FROM start_protocol tm
                WHERE tm.team_id = sp.team_id AND ({teammate}) IS NULL
            ) THEN NULL
            ELSE (
                SELECT MIN({teammate})
                FROM start_protocol tm
                WHERE tm.team_id = sp.team_id
            )
          END)"#
    )
}

fn competition_year(conn: &Connection) -> Option<i64> {
    let settings = get_settings(conn).ok()?;
    NaiveDate::parse_from_str(settings.competition_date.trim(), "%Y-%m-%d")
        .ok()
        .map(|d| i64::from(d.year()))
}

#[derive(Debug, Serialize, Clone)]
pub struct FormatSettings {
    pub format_id: i64,
    pub format_name: String,
    pub control_minutes: Option<i64>,
    pub penalty_per_minute: Option<i64>,
    pub dq_minutes: Option<i64>,
    pub finish_cp: Option<i64>,
    pub competition_start_time: Option<String>,
}

#[derive(Debug, Serialize, Clone)]
pub struct ParticipantMeta {
    pub id: Option<i64>,
    pub chip_raw_id: Option<String>,
    pub participant_id: String,
    pub name: String,
    pub start_station_id: i64,
    pub start_time: String,
    pub source_row: Option<i64>,
    /// Original finish-dump course (CSV). Orienteering scoring may use assign_course instead.
    pub course_name: String,
}

#[derive(Debug, Serialize)]
pub struct MarkRow {
    pub seq: i64,
    pub cp_number: i64,
    pub mark_time: String,
}

#[derive(Debug, Serialize)]
pub struct ExcludedLegRow {
    pub from_cp: i64,
    pub to_cp: i64,
    pub direction: String,
    pub apply_mode: String,
    pub max_leg_seconds: Option<i64>,
    pub source: String,
}

#[derive(Debug, Serialize)]
pub struct CorrectionRow {
    pub id: i64,
    /// Surrogate finish-dump id; NULL = global correction.
    pub finish_participant_id: Option<i64>,
    pub scope: String,
    pub source_table: String,
    pub correction_type: String,
    pub payload: Value,
    pub created_at: String,
}

#[derive(Debug, Serialize)]
pub struct ExclusionRuleRow {
    pub id: i64,
    /// Surrogate finish-dump id; NULL = global/format rule.
    pub finish_participant_id: Option<i64>,
    pub format_id: Option<i64>,
    pub format_name: Option<String>,
    pub course_id: Option<i64>,
    pub course_name: Option<String>,
    pub from_cp: i64,
    pub to_cp: i64,
    pub direction: String,
    pub apply_mode: String,
    pub max_leg_seconds: Option<i64>,
    pub created_at: String,
    pub scope: String,
}

#[derive(Debug, Serialize)]
pub struct ParticipantDetails {
    pub participant: ParticipantMeta,
    pub result: Option<ResultRow>,
    pub anomalies: Vec<ParticipantAnomaly>,
    pub anomalies_history: Vec<ParticipantAnomaly>,
    pub raw_marks: Vec<MarkRow>,
    pub corrected_marks: Vec<MarkRow>,
    pub excluded_legs: Vec<ExcludedLegRow>,
    pub corrections: Vec<CorrectionRow>,
}

#[derive(Debug, Serialize, Clone)]
pub struct ParticipantAnomaly {
    pub anomaly_type: String,
    pub title: String,
    pub details: String,
    pub payload: Value,
    pub resolved: bool,
    pub resolved_correction_id: Option<i64>,
    pub resolved_at: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct AnomalyScanSummary {
    pub participants_checked: i64,
    pub participants_with_anomalies: i64,
    pub anomalies_total: i64,
    pub by_type: BTreeMap<String, i64>,
    pub potential_anomalies: Vec<PotentialAnomaly>,
}

#[derive(Debug, Serialize, Clone)]
pub struct PotentialAnomaly {
    pub anomaly_type: String,
    pub title: String,
    pub details: String,
    pub payload: Value,
}

#[derive(Debug, Serialize, Clone)]
pub struct AnomalyListItem {
    pub id: i64,
    pub scope: String,
    pub anomaly_type: String,
    pub title: String,
    pub details: String,
    pub is_potential: bool,
    pub resolved: bool,
    pub participant_id: Option<String>,
    pub name: Option<String>,
    pub result_id: Option<i64>,
}

#[derive(Debug, Serialize, Clone)]
pub struct AnomalyListResponse {
    pub items: Vec<AnomalyListItem>,
    pub day_shift_total: i64,
    pub day_shift_resolved: i64,
}

#[derive(Debug, Serialize, Clone)]
pub struct CpRemapSuggestion {
    pub finish_participant_id: i64,
    pub participant_id: String,
    pub name: String,
    pub result_id: Option<i64>,
    pub mark_seq: i64,
    pub mark_time: String,
    pub from_cp: i64,
    pub to_cp: i64,
    pub suggested_cp: i64,
    pub action: String,
    pub confidence: String,
    pub cost_keep: f64,
    pub cost_remap: f64,
    pub reason: String,
    pub already_applied: bool,
    pub prev_cp: Option<i64>,
    pub next_cp: Option<i64>,
    pub context_cps: Vec<i64>,
}

#[derive(Debug, Serialize, Clone)]
pub struct CpRemapAnalyzeSummary {
    pub from_cp: i64,
    pub to_cp: i64,
    pub participants_with_from_cp: i64,
    pub suggestions: Vec<CpRemapSuggestion>,
    pub remap_suggested: i64,
    pub keep_suggested: i64,
    pub high_confidence_remaps: i64,
    pub missing_map_positions: bool,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct CpRemapApplyItem {
    pub finish_participant_id: i64,
    pub from_cp: i64,
    pub to_cp: i64,
    pub mark_time: String,
    pub seq: Option<i64>,
}

#[derive(Debug, Serialize, Clone)]
pub struct CpRemapApplySummary {
    pub inserted: i64,
    pub skipped_existing: i64,
    pub failed: i64,
}

#[derive(Debug, Serialize)]
pub struct ErrorRow {
    pub result_id: i64,
    pub participant_id: String,
    pub name: String,
    pub description: String,
    pub diagnostics_json: String,
}

#[derive(Debug, Serialize)]
pub struct BulkAnomalyCorrectionSummary {
    pub participants_with_anomaly: i64,
    pub inserted: i64,
    pub already_corrected: i64,
}

#[derive(Debug, Serialize)]
pub struct BulkAnomalyRollbackSummary {
    pub removed: i64,
}

#[derive(Debug, Serialize)]
pub struct AnomalyBulkActionsState {
    pub can_apply_day_shift_24h: bool,
    pub can_rollback_day_shift_24h: bool,
    pub available_apply_count: i64,
    pub applied_count: i64,
}

pub fn open_and_init_db(db_path: &Path) -> Result<Connection, String> {
    let conn = Connection::open(db_path).map_err(|e| format!("open db: {e}"))?;
    register_search_functions(&conn)?;
    init_db(&conn)?;
    ensure_schema_extras(&conn)?;
    Ok(conn)
}

/// Unicode-aware lowercase for search (SQLite LOWER/LIKE are ASCII-only for Cyrillic).
fn normalize_search_text(value: &str) -> String {
    value
        .to_lowercase()
        .replace('ё', "е")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

fn search_like_pattern(query: &str) -> Option<String> {
    let normalized = normalize_search_text(query);
    if normalized.is_empty() {
        None
    } else {
        Some(format!("%{normalized}%"))
    }
}

fn register_search_functions(conn: &Connection) -> Result<(), String> {
    conn.create_scalar_function(
        "u_lower",
        1,
        FunctionFlags::SQLITE_UTF8 | FunctionFlags::SQLITE_DETERMINISTIC,
        |ctx| {
            let value = ctx.get::<Option<String>>(0)?.unwrap_or_default();
            Ok(normalize_search_text(&value))
        },
    )
    .map_err(|e| format!("register u_lower: {e}"))?;
    Ok(())
}

fn table_has_column(conn: &Connection, table: &str, column: &str) -> Result<bool, String> {
    let mut stmt = conn
        .prepare(&format!("PRAGMA table_info({table})"))
        .map_err(|e| format!("pragma table_info({table}): {e}"))?;
    let rows = stmt
        .query_map([], |r| r.get::<_, String>(1))
        .map_err(|e| format!("query table_info({table}): {e}"))?;
    for row in rows {
        let name = row.map_err(|e| format!("read table_info({table}): {e}"))?;
        if name == column {
            return Ok(true);
        }
    }
    Ok(false)
}

fn ensure_schema_extras(conn: &Connection) -> Result<(), String> {
    if !table_has_column(conn, "start_protocol", "team_id")? {
        conn.execute(
            "ALTER TABLE start_protocol ADD COLUMN team_id INTEGER NULL",
            [],
        )
        .map_err(|e| format!("add start_protocol.team_id: {e}"))?;
    }
    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_start_protocol_team ON start_protocol(team_id)",
        [],
    )
    .map_err(|e| format!("create idx_start_protocol_team: {e}"))?;
    conn.execute_batch(
        r#"
        CREATE TABLE IF NOT EXISTS award_groups (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE,
            gender_mode TEXT NOT NULL DEFAULT 'any',
            sort_order INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS award_group_formats (
            award_group_id INTEGER NOT NULL,
            format_id INTEGER NOT NULL,
            PRIMARY KEY (award_group_id, format_id),
            FOREIGN KEY (award_group_id) REFERENCES award_groups(id) ON DELETE CASCADE,
            FOREIGN KEY (format_id) REFERENCES start_protocol_formats(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_award_group_formats_format
            ON award_group_formats(format_id);
        CREATE TABLE IF NOT EXISTS cp_legend_types (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            type_name TEXT NOT NULL UNIQUE
        );
        CREATE TABLE IF NOT EXISTS cp_legends (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            cp_number INTEGER NOT NULL UNIQUE,
            name TEXT NOT NULL,
            cp_type_id INTEGER NULL,
            map_x REAL NULL,
            map_y REAL NULL,
            FOREIGN KEY (cp_type_id) REFERENCES cp_legend_types(id)
        );
        CREATE INDEX IF NOT EXISTS idx_cp_legends_number ON cp_legends(cp_number);
        "#,
    )
    .map_err(|e| format!("ensure award_groups schema: {e}"))?;
    if !table_has_column(conn, "award_groups", "min_age")? {
        conn.execute(
            "ALTER TABLE award_groups ADD COLUMN min_age INTEGER NULL",
            [],
        )
        .map_err(|e| format!("add award_groups.min_age: {e}"))?;
    }
    migrate_cp_legends_type_dictionary(conn)?;
    migrate_cp_legends_map_positions(conn)?;
    ensure_map_georef_schema(conn)?;
    conn.execute_batch(
        r#"
        CREATE TABLE IF NOT EXISTS format_cp_type_rules (
            format_id INTEGER NOT NULL,
            cp_type_id INTEGER NOT NULL,
            max_count INTEGER NULL,
            PRIMARY KEY (format_id, cp_type_id),
            FOREIGN KEY (format_id) REFERENCES start_protocol_formats(id) ON DELETE CASCADE,
            FOREIGN KEY (cp_type_id) REFERENCES cp_legend_types(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_format_cp_type_rules_type
            ON format_cp_type_rules(cp_type_id);
        CREATE TABLE IF NOT EXISTS event_anomalies (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            anomaly_type TEXT NOT NULL,
            title TEXT NOT NULL,
            details TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now'))
        );
        "#,
    )
    .map_err(|e| format!("ensure format_cp_type_rules schema: {e}"))?;
    conn.execute_batch(
        r#"
        CREATE TABLE IF NOT EXISTS courses (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE
        );
        CREATE TABLE IF NOT EXISTS course_controls (
            course_id INTEGER NOT NULL,
            seq INTEGER NOT NULL,
            cp_number INTEGER NOT NULL,
            PRIMARY KEY (course_id, seq),
            FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_course_controls_course ON course_controls(course_id);
        "#,
    )
    .map_err(|e| format!("ensure courses schema: {e}"))?;
    if !table_has_column(conn, "participants", "course_name")? {
        conn.execute(
            "ALTER TABLE participants ADD COLUMN course_name TEXT NOT NULL DEFAULT ''",
            [],
        )
        .map_err(|e| format!("add participants.course_name: {e}"))?;
    }
    if !table_has_column(conn, "leg_exclusion_rules", "course_id")? {
        conn.execute(
            "ALTER TABLE leg_exclusion_rules ADD COLUMN course_id INTEGER NULL",
            [],
        )
        .map_err(|e| format!("add leg_exclusion_rules.course_id: {e}"))?;
    }
    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_leg_exclusion_rules_course ON leg_exclusion_rules(course_id)",
        [],
    )
    .map_err(|e| format!("create idx_leg_exclusion_rules_course: {e}"))?;
    Ok(())
}

fn migrate_cp_legends_type_dictionary(conn: &Connection) -> Result<(), String> {
    conn.execute_batch(
        r#"
        CREATE TABLE IF NOT EXISTS cp_legend_types (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            type_name TEXT NOT NULL UNIQUE
        );
        "#,
    )
    .map_err(|e| format!("ensure cp_legend_types: {e}"))?;

    if !table_has_column(conn, "cp_legends", "cp_type_id")? {
        conn.execute(
            "ALTER TABLE cp_legends ADD COLUMN cp_type_id INTEGER NULL",
            [],
        )
        .map_err(|e| format!("add cp_legends.cp_type_id: {e}"))?;
    }
    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_cp_legends_type ON cp_legends(cp_type_id)",
        [],
    )
    .map_err(|e| format!("create idx_cp_legends_type: {e}"))?;

    // Migrate legacy free-text cp_type column into the dictionary, if present.
    if table_has_column(conn, "cp_legends", "cp_type")? {
        let mut stmt = conn
            .prepare(
                r#"
                SELECT DISTINCT TRIM(cp_type)
                FROM cp_legends
                WHERE cp_type IS NOT NULL
                  AND TRIM(cp_type) <> ''
                  AND cp_type_id IS NULL
                "#,
            )
            .map_err(|e| format!("prepare cp_type migration: {e}"))?;
        let names: Vec<String> = stmt
            .query_map([], |r| r.get::<_, String>(0))
            .map_err(|e| format!("query distinct cp_type: {e}"))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| format!("read distinct cp_type: {e}"))?;
        drop(stmt);
        for name in names {
            let type_id = ensure_cp_type_id(conn, &name)?;
            conn.execute(
                r#"
                UPDATE cp_legends
                SET cp_type_id = ?
                WHERE cp_type_id IS NULL
                  AND TRIM(IFNULL(cp_type, '')) = ?
                "#,
                params![type_id, name],
            )
            .map_err(|e| format!("backfill cp_type_id for «{name}»: {e}"))?;
        }
    }
    Ok(())
}

fn migrate_cp_legends_map_positions(conn: &Connection) -> Result<(), String> {
    if !table_has_column(conn, "cp_legends", "map_x")? {
        conn.execute("ALTER TABLE cp_legends ADD COLUMN map_x REAL NULL", [])
            .map_err(|e| format!("add cp_legends.map_x: {e}"))?;
    }
    if !table_has_column(conn, "cp_legends", "map_y")? {
        conn.execute("ALTER TABLE cp_legends ADD COLUMN map_y REAL NULL", [])
            .map_err(|e| format!("add cp_legends.map_y: {e}"))?;
    }
    Ok(())
}

const START_PROTOCOL_ROW_SELECT: &str = r#"
    sp.id,
    sp.participant_id,
    sp.name,
    sp.format_id,
    sp.format_name,
    sp.gender,
    sp.birth_date_raw,
    sp.birth_date_iso,
    sp.source_row,
    sp.team_id,
    CASE
        WHEN sp.team_id IS NULL THEN 0
        ELSE (
            SELECT COUNT(*) FROM start_protocol tsz
            WHERE tsz.team_id = sp.team_id
        )
    END AS team_size,
    CASE
        WHEN sp.team_id IS NULL THEN ''
        ELSE COALESCE((
            SELECT GROUP_CONCAT(tsz.participant_id || ' ' || tsz.name, ' · ')
            FROM start_protocol tsz
            WHERE tsz.team_id = sp.team_id AND tsz.id <> sp.id
        ), '')
    END AS teammates,
    EXISTS(
        SELECT 1 FROM participants p
        WHERE p.participant_id = sp.participant_id
    ) as has_result
"#;

fn map_start_protocol_row(
    r: &rusqlite::Row<'_>,
    incomplete_idx: usize,
    missing_idx: usize,
) -> rusqlite::Result<StartProtocolRow> {
    Ok(StartProtocolRow {
        id: r.get(0)?,
        participant_id: r.get(1)?,
        name: r.get(2)?,
        format_id: r.get(3)?,
        format_name: r.get(4)?,
        gender: r.get(5)?,
        birth_date_raw: r.get(6)?,
        birth_date_iso: r.get(7)?,
        source_row: r.get(8)?,
        team_id: r.get(9)?,
        team_size: r.get(10)?,
        teammates: r.get(11)?,
        has_result: r.get(12)?,
        is_incomplete: r.get(incomplete_idx)?,
        missing_format: r.get(missing_idx)?,
    })
}

pub fn init_db(conn: &Connection) -> Result<(), String> {
    conn.execute_batch(
        r#"
        PRAGMA foreign_keys = ON;

        CREATE TABLE IF NOT EXISTS participants (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            participant_id TEXT NOT NULL,
            chip_raw_id TEXT NULL,
            name TEXT NOT NULL,
            start_station_id INTEGER NOT NULL,
            start_time TEXT NOT NULL,
            source_row INTEGER,
            course_name TEXT NOT NULL DEFAULT ''
        );
        CREATE TABLE IF NOT EXISTS courses (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE
        );
        CREATE TABLE IF NOT EXISTS course_controls (
            course_id INTEGER NOT NULL,
            seq INTEGER NOT NULL,
            cp_number INTEGER NOT NULL,
            PRIMARY KEY (course_id, seq),
            FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_course_controls_course ON course_controls(course_id);

        CREATE TABLE IF NOT EXISTS marks_raw (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            finish_participant_id INTEGER NOT NULL,
            seq INTEGER NOT NULL,
            cp_number INTEGER NOT NULL,
            mark_time TEXT NOT NULL,
            FOREIGN KEY (finish_participant_id) REFERENCES participants(id) ON DELETE CASCADE
        );

        CREATE TABLE IF NOT EXISTS corrections (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            finish_participant_id INTEGER NOT NULL,
            correction_type TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            FOREIGN KEY (finish_participant_id) REFERENCES participants(id) ON DELETE CASCADE
        );

        CREATE TABLE IF NOT EXISTS leg_exclusion_rules (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            finish_participant_id INTEGER NULL,
            format_id INTEGER NULL,
            from_cp INTEGER NOT NULL,
            to_cp INTEGER NOT NULL,
            direction TEXT NOT NULL DEFAULT 'forward',
            apply_mode TEXT NOT NULL DEFAULT 'once',
            max_leg_seconds INTEGER NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            FOREIGN KEY (finish_participant_id) REFERENCES participants(id) ON DELETE CASCADE
        );

        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS manual_corrections (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            finish_participant_id INTEGER NULL,
            correction_type TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            FOREIGN KEY (finish_participant_id) REFERENCES participants(id) ON DELETE CASCADE
        );

        CREATE TABLE IF NOT EXISTS results (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            finish_participant_id INTEGER NULL,
            chip_raw_id TEXT NULL,
            participant_id TEXT NOT NULL,
            name TEXT NOT NULL,
            status TEXT NOT NULL,
            points_raw INTEGER NOT NULL,
            penalty_points INTEGER NOT NULL,
            points_final INTEGER NOT NULL,
            elapsed_seconds INTEGER NOT NULL,
            delay_seconds INTEGER NOT NULL,
            penalty_minutes INTEGER NOT NULL,
            diagnostics_json TEXT NOT NULL,
            computed_at TEXT NOT NULL DEFAULT (datetime('now'))
        );
        CREATE INDEX IF NOT EXISTS idx_results_chip ON results(participant_id, name);

        CREATE TABLE IF NOT EXISTS participant_anomalies (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            finish_participant_id INTEGER NOT NULL,
            anomaly_type TEXT NOT NULL,
            title TEXT NOT NULL,
            details TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            FOREIGN KEY (finish_participant_id) REFERENCES participants(id) ON DELETE CASCADE
        );

        CREATE TABLE IF NOT EXISTS event_anomalies (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            anomaly_type TEXT NOT NULL,
            title TEXT NOT NULL,
            details TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now'))
        );

        CREATE TABLE IF NOT EXISTS start_protocol (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            participant_id TEXT NOT NULL,
            name TEXT NOT NULL,
            format_id INTEGER NULL,
            format_name TEXT NOT NULL DEFAULT '',
            gender TEXT NULL,
            birth_date_raw TEXT NULL,
            birth_date_iso TEXT NULL,
            source_row INTEGER NULL,
            team_id INTEGER NULL,
            UNIQUE(participant_id, name)
        );
        CREATE TABLE IF NOT EXISTS start_protocol_formats (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            format_name TEXT NOT NULL UNIQUE
        );
        CREATE TABLE IF NOT EXISTS format_settings (
            format_id INTEGER PRIMARY KEY,
            control_minutes INTEGER NULL,
            penalty_per_minute INTEGER NULL,
            dq_minutes INTEGER NULL,
            finish_cp INTEGER NULL,
            competition_start_time TEXT NULL
        );
        CREATE TABLE IF NOT EXISTS award_groups (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE,
            gender_mode TEXT NOT NULL DEFAULT 'any',
            min_age INTEGER NULL,
            sort_order INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS award_group_formats (
            award_group_id INTEGER NOT NULL,
            format_id INTEGER NOT NULL,
            PRIMARY KEY (award_group_id, format_id),
            FOREIGN KEY (award_group_id) REFERENCES award_groups(id) ON DELETE CASCADE,
            FOREIGN KEY (format_id) REFERENCES start_protocol_formats(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_award_group_formats_format
            ON award_group_formats(format_id);
        CREATE TABLE IF NOT EXISTS cp_legend_types (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            type_name TEXT NOT NULL UNIQUE
        );
        CREATE TABLE IF NOT EXISTS cp_legends (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            cp_number INTEGER NOT NULL UNIQUE,
            name TEXT NOT NULL,
            cp_type_id INTEGER NULL,
            map_x REAL NULL,
            map_y REAL NULL,
            FOREIGN KEY (cp_type_id) REFERENCES cp_legend_types(id)
        );
        CREATE INDEX IF NOT EXISTS idx_cp_legends_number ON cp_legends(cp_number);
        CREATE TABLE IF NOT EXISTS format_cp_type_rules (
            format_id INTEGER NOT NULL,
            cp_type_id INTEGER NOT NULL,
            max_count INTEGER NULL,
            PRIMARY KEY (format_id, cp_type_id),
            FOREIGN KEY (format_id) REFERENCES start_protocol_formats(id) ON DELETE CASCADE,
            FOREIGN KEY (cp_type_id) REFERENCES cp_legend_types(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_format_cp_type_rules_type
            ON format_cp_type_rules(cp_type_id);
        CREATE INDEX IF NOT EXISTS idx_participants_bib ON participants(participant_id);
        CREATE INDEX IF NOT EXISTS idx_marks_raw_finish ON marks_raw(finish_participant_id);
        CREATE INDEX IF NOT EXISTS idx_corrections_finish ON corrections(finish_participant_id, id);
        CREATE INDEX IF NOT EXISTS idx_leg_exclusion_rules_finish ON leg_exclusion_rules(finish_participant_id, id);
        CREATE INDEX IF NOT EXISTS idx_leg_exclusion_rules_format ON leg_exclusion_rules(format_id, id);
        CREATE INDEX IF NOT EXISTS idx_manual_corrections_finish ON manual_corrections(finish_participant_id, id);
        CREATE INDEX IF NOT EXISTS idx_results_finish ON results(finish_participant_id);
        CREATE INDEX IF NOT EXISTS idx_results_chip_raw ON results(chip_raw_id);
        CREATE INDEX IF NOT EXISTS idx_participant_anomalies_finish
            ON participant_anomalies(finish_participant_id, anomaly_type);
        CREATE INDEX IF NOT EXISTS idx_start_protocol_chip ON start_protocol(participant_id);
        CREATE INDEX IF NOT EXISTS idx_start_protocol_format ON start_protocol(format_name);
        CREATE INDEX IF NOT EXISTS idx_start_protocol_name ON start_protocol(name);
        CREATE INDEX IF NOT EXISTS idx_start_protocol_format_id ON start_protocol(format_id);
        "#,
    )
    .map_err(|e| format!("init schema: {e}"))?;

    for (k, v) in [
        ("control_minutes", "240".to_string()),
        ("penalty_per_minute", "1".to_string()),
        ("dq_minutes", "15".to_string()),
        ("finish_cp", "240".to_string()),
        ("start_mode", "station".to_string()),
        ("start_cp", "".to_string()),
        ("competition_date", "".to_string()),
        ("competition_start_time", "".to_string()),
        ("sport_kind", "rogaine".to_string()),
    ] {
        conn.execute(
            "INSERT OR IGNORE INTO settings(key, value) VALUES(?, ?)",
            params![k, v],
        )
        .map_err(|e| format!("init settings: {e}"))?;
    }
    Ok(())
}

#[derive(Debug, Serialize, Clone)]
pub struct DataPresenceCounts {
    pub finish_participants: i64,
    pub start_protocol: i64,
    pub cp_legends: i64,
    pub courses: i64,
}

pub fn get_data_presence_counts(conn: &Connection) -> Result<DataPresenceCounts, String> {
    let finish_participants: i64 = conn
        .query_row("SELECT COUNT(*) FROM participants", [], |r| r.get(0))
        .map_err(|e| format!("count participants: {e}"))?;
    let start_protocol: i64 = conn
        .query_row("SELECT COUNT(*) FROM start_protocol", [], |r| r.get(0))
        .map_err(|e| format!("count start_protocol: {e}"))?;
    let cp_legends: i64 = conn
        .query_row("SELECT COUNT(*) FROM cp_legends", [], |r| r.get(0))
        .map_err(|e| format!("count cp_legends: {e}"))?;
    let courses: i64 = conn
        .query_row("SELECT COUNT(*) FROM courses", [], |r| r.get(0))
        .map_err(|e| format!("count courses: {e}"))?;
    Ok(DataPresenceCounts {
        finish_participants,
        start_protocol,
        cp_legends,
        courses,
    })
}

pub fn import_csv_content(
    conn: &mut Connection,
    csv_content: &str,
    reset: bool,
) -> Result<ImportSummary, String> {
    let delimiter = detect_csv_delimiter(csv_content);
    let mut reader = csv::ReaderBuilder::new()
        .delimiter(delimiter)
        .has_headers(true)
        .flexible(true)
        .from_reader(csv_content.as_bytes());

    let headers = reader
        .headers()
        .map_err(|e| CsvFormatError(format!("CSV read header: {e}")).to_string())?
        .clone();
    if headers.len() < 10 {
        return Err(
            CsvFormatError("CSV header has too few columns (expected at least 10)".into())
                .to_string(),
        );
    }

    // (optional chip_raw_id, bib_id/C, name, course/E, start_station, start_time, source_row, marks)
    let mut rows_to_insert: Vec<(
        Option<String>,
        String,
        String,
        String,
        i64,
        String,
        i64,
        Vec<(i64, i64, String)>,
    )> = Vec::new();

    for (idx, row) in reader.records().enumerate() {
        let source_row = idx as i64 + 2;
        let rec = row.map_err(|e| CsvFormatError(format!("Row {source_row}: {e}")).to_string())?;
        if record_is_blank(&rec) {
            continue;
        }
        let first = rec.get(0).unwrap_or("").trim();
        if first.starts_with(FOOTER_PREFIX) {
            continue;
        }
        if rec.len() < 10 {
            return Err(CsvFormatError(format!(
                "Row {source_row}: too few columns (expected at least 10)"
            ))
            .to_string());
        }

        let chip_raw_id = optional_chip_raw_id(&field(&rec, 1));
        let bib_id = field(&rec, 2);
        let name = field(&rec, 3);
        let course_name = field(&rec, 4);
        let start_station_raw = field(&rec, 7);
        let start_time_raw = field(&rec, 8);

        // Trailing empty / summary rows from SFR export.
        if bib_id.is_empty() && name.is_empty() {
            continue;
        }

        let mut marks: Vec<(i64, i64, String)> = Vec::new();
        let mut seq: i64 = 1;
        let mut i = 9;
        while i + 1 < rec.len() {
            let cp_raw = field(&rec, i);
            let ts_raw = field(&rec, i + 1);
            if cp_raw.is_empty() && ts_raw.is_empty() {
                i += 2;
                continue;
            }
            if cp_raw.is_empty() || ts_raw.is_empty() {
                return Err(CsvFormatError(format!(
                    "Row {source_row}: CP/time pair is incomplete at columns {}/{}",
                    i + 1,
                    i + 2
                ))
                .to_string());
            }
            let cp_number = cp_raw.parse::<i64>().map_err(|_| {
                CsvFormatError(format!("Row {source_row}: invalid CP number '{cp_raw}'"))
                    .to_string()
            })?;
            parse_time(&ts_raw).map_err(|_| {
                CsvFormatError(format!("Row {source_row}: invalid mark time '{ts_raw}'"))
                    .to_string()
            })?;
            marks.push((seq, cp_number, ts_raw));
            seq += 1;
            i += 2;
        }

        // DNS / no start punch: SFR leaves start st id and Start time empty
        // but may still include later punches. Take the first punch as fallback.
        let start_station_id = if start_station_raw.is_empty() {
            match marks.first() {
                Some((_, cp, _)) => *cp,
                None => continue,
            }
        } else {
            start_station_raw.parse::<i64>().map_err(|_| {
                CsvFormatError(format!(
                    "Row {source_row}: invalid start station id '{start_station_raw}'"
                ))
                .to_string()
            })?
        };
        let start_time = if start_time_raw.is_empty() {
            match marks.first() {
                Some((_, _, ts)) => ts.clone(),
                None => continue,
            }
        } else {
            start_time_raw
        };
        if bib_id.is_empty() || name.is_empty() {
            return Err(CsvFormatError(format!(
                "Row {source_row}: required columns invalid (id/name/start station/start time)"
            ))
            .to_string());
        }
        parse_time(&start_time).map_err(|_| {
            CsvFormatError(format!(
                "Row {source_row}: invalid start time '{start_time}'"
            ))
            .to_string()
        })?;

        rows_to_insert.push((
            chip_raw_id,
            bib_id,
            name,
            course_name,
            start_station_id,
            start_time,
            source_row,
            marks,
        ));
    }

    if rows_to_insert.is_empty() {
        return Err(CsvFormatError("CSV has no valid participants".into()).to_string());
    }

    // Orient: one bib per course is required. Rogaine: same bib on two chips is an
    // anomaly after scoring (`duplicate_bib_in_finish`), not an import reject.
    if get_settings(conn)?.sport_kind == "orient" {
        let mut first_row_by_entry: BTreeMap<(String, String), i64> = BTreeMap::new();
        for (_chip_raw_id, bib, _name, course, _st, _st_time, source_row, _) in &rows_to_insert {
            let key = (bib.clone(), course_key(course));
            if let Some(prev_row) = first_row_by_entry.insert(key, *source_row) {
                let course_label = if course.trim().is_empty() {
                    "без дистанции".to_string()
                } else {
                    format!("дистанция «{}»", course.trim())
                };
                return Err(CsvFormatError(format!(
                    "Row {source_row}: повторяется id '{bib}' ({course_label}, также строка {prev_row})."
                ))
                .to_string());
            }
        }
    }

    let tx = conn
        .transaction()
        .map_err(|e| format!("start transaction: {e}"))?;
    if reset {
        reset_import_data(&tx).map_err(|e| format!("reset data: {e}"))?;
    }
    // merge (reset=false): keep existing finish dump; only insert new participant_ids below.

    {
        let mut stmt = tx
            .prepare(
                r#"
                INSERT INTO participants(
                    participant_id, chip_raw_id, name, start_station_id, start_time, source_row, course_name
                ) VALUES(?, ?, ?, ?, ?, ?, ?)
                "#,
            )
            .map_err(|e| format!("prepare participants insert: {e}"))?;
        let mut mark_stmt = tx
            .prepare(
                "INSERT INTO marks_raw(finish_participant_id, seq, cp_number, mark_time) VALUES(?, ?, ?, ?)",
            )
            .map_err(|e| format!("prepare marks insert: {e}"))?;
        let mut exists_stmt = tx
            .prepare(
                r#"
                SELECT 1 FROM participants
                WHERE participant_id = ?1
                  AND TRIM(IFNULL(course_name, '')) = TRIM(IFNULL(?2, ''))
                LIMIT 1
                "#,
            )
            .map_err(|e| format!("prepare participants exists: {e}"))?;

        for (chip_raw_id, bib_id, name, course_name, st, st_time, source_row, marks) in &rows_to_insert {
            if !reset {
                let exists = exists_stmt
                    .exists(params![bib_id, course_name])
                    .map_err(|e| format!("check existing participant: {e}"))?;
                if exists {
                    continue;
                }
            }
            stmt.execute(params![
                bib_id,
                chip_raw_id,
                name,
                st,
                st_time,
                source_row,
                course_name
            ])
                .map_err(|e| format!("insert participant row {source_row}: {e}"))?;
            let finish_id = tx.last_insert_rowid();
            for (seq, cp_number, mark_time) in marks {
                mark_stmt
                    .execute(params![finish_id, seq, cp_number, mark_time])
                    .map_err(|e| format!("insert mark for finish {finish_id}: {e}"))?;
            }
        }
    }

    recalculate_tx(&tx)?;
    let participants_count: i64 = tx
        .query_row("SELECT COUNT(*) FROM participants", [], |r| r.get(0))
        .map_err(|e| format!("count participants: {e}"))?;
    let results_count: i64 = tx
        .query_row("SELECT COUNT(*) FROM results", [], |r| r.get(0))
        .map_err(|e| format!("count results: {e}"))?;

    tx.commit()
        .map_err(|e| format!("commit import transaction: {e}"))?;
    Ok(ImportSummary {
        participants_count,
        results_count,
    })
}

fn detect_csv_delimiter(content: &str) -> u8 {
    let first = content.lines().next().unwrap_or("");
    let commas = first.matches(',').count();
    let semis = first.matches(';').count();
    if commas > semis {
        b','
    } else {
        b';'
    }
}

fn optional_chip_raw_id(raw: &str) -> Option<String> {
    let compact = raw.split_whitespace().collect::<Vec<_>>().join(" ");
    if compact.is_empty() {
        None
    } else {
        Some(compact)
    }
}

pub fn import_start_protocol_content(
    conn: &Connection,
    csv_content: &str,
    reset: bool,
) -> Result<StartProtocolImportSummary, String> {
    let mut reader = csv::ReaderBuilder::new()
        .delimiter(b';')
        .has_headers(true)
        .flexible(true)
        .from_reader(csv_content.as_bytes());

    let headers = reader
        .headers()
        .map_err(|e| format!("start protocol header read: {e}"))?
        .clone();
    if headers.len() < 5 {
        return Err("Стартовый протокол: ожидается минимум 5 колонок".to_string());
    }

    let tx = conn
        .unchecked_transaction()
        .map_err(|e| format!("start protocol transaction: {e}"))?;
    if reset {
        tx.execute("DELETE FROM start_protocol", [])
            .map_err(|e| format!("clear start protocol: {e}"))?;
    }

    let mut imported_rows = 0_i64;
    let upsert_sql = if reset {
        r#"
            INSERT INTO start_protocol(
                participant_id, name, format_id, format_name, gender, birth_date_raw, birth_date_iso, source_row
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(participant_id, name) DO UPDATE SET
                format_id=excluded.format_id,
                format_name=excluded.format_name,
                gender=excluded.gender,
                birth_date_raw=excluded.birth_date_raw,
                birth_date_iso=excluded.birth_date_iso,
                source_row=excluded.source_row
            "#
    } else {
        r#"
            INSERT INTO start_protocol(
                participant_id, name, format_id, format_name, gender, birth_date_raw, birth_date_iso, source_row
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(participant_id, name) DO NOTHING
            "#
    };
    let mut stmt = tx
        .prepare(upsert_sql)
        .map_err(|e| format!("prepare start protocol upsert: {e}"))?;

    for (idx, row) in reader.records().enumerate() {
        let source_row = idx as i64 + 2;
        let rec = row.map_err(|e| format!("Строка {source_row}: {e}"))?;
        if record_is_blank(&rec) {
            continue;
        }
        let participant_id = field(&rec, 0);
        let name = field(&rec, 1);
        let format_name = field(&rec, 2).trim().to_string();
        let gender = field(&rec, 3);
        let birth_date_raw = field(&rec, 4);
        if participant_id.is_empty() || name.is_empty() {
            return Err(format!(
                "Строка {source_row}: обязательные поля id/имя не заполнены"
            ));
        }
        let format_id = if format_name.is_empty() {
            None
        } else {
            Some(ensure_format_id_tx(&tx, &format_name)?)
        };
        let birth_date_iso = parse_birth_date_ru(&birth_date_raw);
        let changed = stmt
            .execute(params![
                participant_id,
                name,
                format_id,
                format_name,
                if gender.is_empty() { None::<String> } else { Some(gender) },
                if birth_date_raw.is_empty() {
                    None::<String>
                } else {
                    Some(birth_date_raw)
                },
                birth_date_iso,
                source_row,
            ])
            .map_err(|e| format!("upsert start protocol row {source_row}: {e}"))?;
        if changed > 0 {
            imported_rows += 1;
        }
    }
    drop(stmt);
    sync_start_protocol_format_ids_tx(&tx)?;

    let total_rows: i64 = tx
        .query_row("SELECT COUNT(*) FROM start_protocol", [], |r| r.get(0))
        .map_err(|e| format!("count start protocol rows: {e}"))?;
    tx.commit()
        .map_err(|e| format!("commit start protocol import: {e}"))?;

    Ok(StartProtocolImportSummary {
        imported_rows,
        total_rows,
    })
}

pub fn recalculate(conn: &mut Connection) -> Result<(), String> {
    let tx = conn
        .transaction()
        .map_err(|e| format!("start recalculate transaction: {e}"))?;
    recalculate_tx(&tx)?;
    tx.commit()
        .map_err(|e| format!("commit recalculate transaction: {e}"))?;
    Ok(())
}

fn recalculate_tx(tx: &Transaction<'_>) -> Result<(), String> {
    let global_settings = get_settings_tx(tx)?;
    tx.execute("DELETE FROM results", [])
        .map_err(|e| format!("clear results: {e}"))?;

    let cp_type_by_number = load_cp_type_by_number_tx(tx)?;
    let format_type_rules = load_format_cp_type_rules_map_tx(tx)?;

    // Start ↔ finish are linked by bib id (CSV column C) only — never by name.
    let mut start_by_bib: BTreeMap<String, Vec<String>> = BTreeMap::new();
    {
        let mut sp_stmt = tx
            .prepare(
                "SELECT participant_id, name FROM start_protocol ORDER BY participant_id, name",
            )
            .map_err(|e| format!("prepare start protocol for recalculate: {e}"))?;
        let sp_rows = sp_stmt
            .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))
            .map_err(|e| format!("query start protocol for recalculate: {e}"))?;
        for row in sp_rows {
            let (bib, name) = row.map_err(|e| format!("read start protocol row: {e}"))?;
            start_by_bib.entry(bib).or_default().push(name);
        }
    }

    let mut participants_stmt = tx
        .prepare(
            r#"
            SELECT id, participant_id, chip_raw_id, name, start_station_id, start_time, course_name
            FROM participants
            ORDER BY id
            "#,
        )
        .map_err(|e| format!("prepare participants query: {e}"))?;

    let participants_iter = participants_stmt
        .query_map([], |row| {
            Ok(Participant {
                id: row.get(0)?,
                participant_id: row.get(1)?,
                chip_raw_id: row.get(2)?,
                name: row.get(3)?,
                start_station_id: row.get(4)?,
                start_time: row.get(5)?,
                course_name: row.get(6)?,
            })
        })
        .map_err(|e| format!("query participants: {e}"))?;

    let mut participants: Vec<Participant> = Vec::new();
    for p in participants_iter {
        participants.push(p.map_err(|e| format!("read participant row: {e}"))?);
    }
    drop(participants_stmt);

    if global_settings.sport_kind == "orient" {
        return recalculate_orient_tx(tx, &global_settings, &participants);
    }

    let mut finish_bib_counts: BTreeMap<String, i64> = BTreeMap::new();
    for p in &participants {
        *finish_bib_counts
            .entry(p.participant_id.clone())
            .or_insert(0) += 1;
    }

    // (bib_id, name) pairs that already received a finish-based result.
    let mut emitted_finish_keys: HashSet<(String, String)> = HashSet::new();

    for participant in participants {
        let finish_id = participant.id;
        let bib = participant.participant_id.clone();
        let mut marks = load_marks(tx, finish_id)?;
        let mut exclusion_rules = load_leg_exclusion_rules(tx, finish_id, &bib)?;
        let manual_corrections = load_manual_corrections(tx, finish_id)?;
        apply_legacy_corrections(tx, finish_id, &mut marks, &mut exclusion_rules)?;
        apply_manual_mark_corrections(&mut marks, &manual_corrections);
        marks.sort_by_key(|m| (m.mark_time, m.seq));
        let has_dup_seq = marks_have_duplicate_seq(&marks);
        let start_names = start_by_bib.get(&bib).cloned().unwrap_or_default();
        let duplicate_finish_bib = finish_bib_counts.get(&bib).copied().unwrap_or(0) > 1;

        let mut result = calculate_result_for_chip_entry(
            tx,
            &global_settings,
            &participant,
            start_names
                .iter()
                .find(|n| *n == &participant.name)
                .map(|n| (bib.as_str(), n.as_str())),
            &marks,
            &exclusion_rules,
            &manual_corrections,
            &cp_type_by_number,
            &format_type_rules,
        )?;
        if has_dup_seq {
            prepend_diag(&mut result, "duplicate_mark_seq");
        }

        if start_names.is_empty() {
            prepend_diag(&mut result, "not_in_start_protocol");
            // Ошибка имеет приоритет над «Нет в протоколе».
            apply_finish_not_in_start_status(&mut result, duplicate_finish_bib);
            insert_result_row(
                tx,
                Some(finish_id),
                participant.chip_raw_id.as_deref(),
                &bib,
                &participant.name,
                &result,
            )?;
            emitted_finish_keys.insert((bib, participant.name.clone()));
            continue;
        }

        if start_names.len() > 1 {
            prepend_diag(&mut result, "duplicate_chip_in_start_protocol");
            result.status = STATUS_ERR.to_string();
            insert_result_row(
                tx,
                Some(finish_id),
                participant.chip_raw_id.as_deref(),
                &bib,
                &participant.name,
                &result,
            )?;
            emitted_finish_keys.insert((bib, participant.name.clone()));
            continue;
        }

        let start_name = &start_names[0];
        if &participant.name == start_name {
            if start_name_missing_format_tx(tx, &bib, start_name)? {
                prepend_diag(&mut result, "missing_format");
            }
            // Не повышаем missing_format/прочие диагностики до «Ошибка» —
            // «Ошибка» только для явных конфликтных случаев (дубль bib и т.п.).
            insert_result_row(
                tx,
                Some(finish_id),
                participant.chip_raw_id.as_deref(),
                &bib,
                start_name,
                &result,
            )?;
            emitted_finish_keys.insert((bib, start_name.clone()));
        } else {
            prepend_diag(&mut result, "not_in_start_protocol");
            apply_finish_not_in_start_status(&mut result, duplicate_finish_bib);
            insert_result_row(
                tx,
                Some(finish_id),
                participant.chip_raw_id.as_deref(),
                &bib,
                &participant.name,
                &result,
            )?;
            emitted_finish_keys.insert((bib, participant.name.clone()));
        }
    }

    // Start-protocol people without a matching finish dump (same bib id).
    for (bib, names) in &start_by_bib {
        let duplicate_chip = names.len() > 1;
        for name in names {
            if emitted_finish_keys.contains(&(bib.clone(), name.clone())) {
                continue;
            }
            let mut diagnostics = vec!["no_finish_data".to_string()];
            if duplicate_chip {
                diagnostics.insert(0, "duplicate_chip_in_start_protocol".to_string());
            }
            if start_name_missing_format_tx(tx, bib, name)? {
                diagnostics.insert(0, "missing_format".to_string());
            }
            insert_result_row(
                tx,
                None,
                None,
                bib,
                name,
                &ResultCalc {
                    status: STATUS_DNS.to_string(),
                    points_raw: 0,
                    penalty_points: 0,
                    points_final: 0,
                    elapsed_seconds: 0,
                    delay_seconds: 0,
                    penalty_minutes: 0,
                    diagnostics,
                },
            )?;
        }
    }

    Ok(())
}

fn prepend_diag(result: &mut ResultCalc, code: &str) {
    result.diagnostics.insert(0, code.to_string());
}

fn course_key(name: &str) -> String {
    name.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase()
}

/// Greedy subsequence: how many required CPs appear in order in `punches`.
#[cfg(test)]
fn course_subsequence_taken(punches: &[i64], required: &[i64]) -> usize {
    course_subsequence_taken_with_adds(punches, required, &HashMap::new())
}

/// Same as `course_subsequence_taken`, but a missing required CP can be filled
/// from `add_cp` counts instead of inventing a punch time.
fn course_subsequence_taken_with_adds(
    punches: &[i64],
    required: &[i64],
    add_counts: &HashMap<i64, usize>,
) -> usize {
    if required.is_empty() {
        return 0;
    }
    let mut remaining = add_counts.clone();
    let mut p = 0usize;
    let mut taken = 0usize;
    for &cp in required {
        let mut q = p;
        while q < punches.len() && punches[q] != cp {
            q += 1;
        }
        if q < punches.len() {
            p = q + 1;
            taken += 1;
            continue;
        }
        if let Some(left) = remaining.get_mut(&cp) {
            if *left > 0 {
                *left -= 1;
                taken += 1;
                continue;
            }
        }
        break;
    }
    taken
}

fn orient_add_cp_counts(
    corrections: &[ManualCorrection],
    required: &[i64],
) -> HashMap<i64, usize> {
    let mut remaining = HashMap::new();
    for c in corrections {
        if c.correction_type != "add_cp" {
            continue;
        }
        let Ok(cp) = payload_int(&c.payload, "cp_number") else {
            continue;
        };
        if !required.contains(&cp) {
            continue;
        }
        *remaining.entry(cp).or_insert(0) += 1;
    }
    remaining
}

fn filter_course_cps(cps: &[i64], removed: &HashSet<i64>) -> Vec<i64> {
    cps.iter()
        .copied()
        .filter(|cp| !removed.contains(cp))
        .collect()
}

fn load_global_removed_course_cps(conn: &Connection) -> Result<HashSet<i64>, String> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT CAST(json_extract(payload_json, '$.cp_number') AS INTEGER)
            FROM manual_corrections
            WHERE finish_participant_id IS NULL
              AND correction_type = 'remove_cp'
            "#,
        )
        .map_err(|e| format!("prepare global removed CPs: {e}"))?;
    let rows = stmt
        .query_map([], |r| r.get::<_, Option<i64>>(0))
        .map_err(|e| format!("query global removed CPs: {e}"))?;
    let mut out = HashSet::new();
    for row in rows {
        if let Some(cp) = row.map_err(|e| format!("read global removed CP: {e}"))? {
            out.insert(cp);
        }
    }
    Ok(out)
}

fn cp_exists_on_any_course(conn: &Connection, cp_number: i64) -> Result<bool, String> {
    let n: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM course_controls WHERE cp_number = ?",
            params![cp_number],
            |r| r.get(0),
        )
        .map_err(|e| format!("check CP on courses: {e}"))?;
    Ok(n > 0)
}

fn load_courses_map_tx(tx: &Transaction<'_>) -> Result<HashMap<String, Vec<i64>>, String> {
    let mut stmt = tx
        .prepare(
            r#"
            SELECT c.name, cc.cp_number
            FROM courses c
            JOIN course_controls cc ON cc.course_id = c.id
            ORDER BY c.id ASC, cc.seq ASC
            "#,
        )
        .map_err(|e| format!("prepare courses map: {e}"))?;
    let rows = stmt
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)))
        .map_err(|e| format!("query courses map: {e}"))?;
    let mut out: HashMap<String, Vec<i64>> = HashMap::new();
    for row in rows {
        let (name, cp) = row.map_err(|e| format!("read course map row: {e}"))?;
        out.entry(course_key(&name)).or_default().push(cp);
    }
    Ok(out)
}

fn load_course_ids_map_tx(tx: &Transaction<'_>) -> Result<HashMap<String, i64>, String> {
    let mut stmt = tx
        .prepare("SELECT id, name FROM courses")
        .map_err(|e| format!("prepare course ids map: {e}"))?;
    let rows = stmt
        .query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?)))
        .map_err(|e| format!("query course ids map: {e}"))?;
    let mut out = HashMap::new();
    for row in rows {
        let (id, name) = row.map_err(|e| format!("read course id row: {e}"))?;
        out.insert(course_key(&name), id);
    }
    Ok(out)
}

fn recalculate_orient_tx(
    tx: &Transaction<'_>,
    settings: &Settings,
    participants: &[Participant],
) -> Result<(), String> {
    let courses = load_courses_map_tx(tx)?;
    let course_ids = load_course_ids_map_tx(tx)?;
    let removed_cps = load_global_removed_course_cps(tx)?;
    for participant in participants {
        let mut marks = load_marks(tx, participant.id)?;
        let manual_corrections = load_manual_corrections(tx, participant.id)?;
        apply_manual_mark_corrections(&mut marks, &manual_corrections);
        let course_name = effective_course_name(
            &participant.course_name,
            participant.id,
            &manual_corrections,
        );
        let required = courses
            .get(&course_key(&course_name))
            .map(|cps| filter_course_cps(cps, &removed_cps))
            .unwrap_or_default();
        let add_cp_counts = orient_add_cp_counts(&manual_corrections, &required);
        marks.sort_by_key(|m| (m.mark_time, m.seq));
        let exclusion_rules =
            load_leg_exclusion_rules(tx, participant.id, &participant.participant_id)?;
        let course_id = course_ids.get(&course_key(&course_name)).copied();
        let result = calculate_orient_result(
            &marks,
            settings,
            &course_name,
            course_id,
            &courses,
            &removed_cps,
            &exclusion_rules,
            &add_cp_counts,
        );
        insert_result_row(
            tx,
            Some(participant.id),
            participant.chip_raw_id.as_deref(),
            &participant.participant_id,
            &participant.name,
            &result,
        )?;
    }
    Ok(())
}

fn calculate_orient_result(
    marks: &[Mark],
    settings: &Settings,
    course_name: &str,
    course_id: Option<i64>,
    courses: &HashMap<String, Vec<i64>>,
    removed_cps: &HashSet<i64>,
    exclusion_rules: &[ExclusionRule],
    add_cp_counts: &HashMap<i64, usize>,
) -> ResultCalc {
    let mut diagnostics: Vec<String> = Vec::new();
    let course_name = course_name.trim();
    let required: Option<Vec<i64>> = if course_name.is_empty() {
        diagnostics.push("missing_course".to_string());
        None
    } else {
        match courses.get(&course_key(course_name)) {
            Some(cps) if !cps.is_empty() => Some(filter_course_cps(cps, removed_cps)),
            Some(_) => {
                diagnostics.push("course_empty".to_string());
                None
            }
            None => {
                diagnostics.push("course_unknown".to_string());
                None
            }
        }
    };
    let required = required.as_deref();

    let Some(start_cp) = settings.start_cp else {
        diagnostics.push("start_cp_not_configured".to_string());
        return ResultCalc {
            status: STATUS_ERR.to_string(),
            points_raw: 0,
            penalty_points: 0,
            points_final: 0,
            elapsed_seconds: 0,
            delay_seconds: 0,
            penalty_minutes: 0,
            diagnostics,
        };
    };

    if marks.is_empty() {
        diagnostics.push("no_marks".to_string());
        return ResultCalc {
            status: STATUS_DNS.to_string(),
            points_raw: 0,
            penalty_points: 0,
            points_final: 0,
            elapsed_seconds: 0,
            delay_seconds: 0,
            penalty_minutes: 0,
            diagnostics,
        };
    }

    let start_idx = marks.iter().position(|m| m.cp_number == start_cp);
    let Some(start_idx) = start_idx else {
        diagnostics.push("start_missing".to_string());
        return ResultCalc {
            status: STATUS_DNS.to_string(),
            points_raw: 0,
            penalty_points: 0,
            points_final: 0,
            elapsed_seconds: 0,
            delay_seconds: 0,
            penalty_minutes: 0,
            diagnostics,
        };
    };

    let finish_idx = marks
        .iter()
        .enumerate()
        .rev()
        .find(|(i, m)| *i > start_idx && m.cp_number == settings.finish_cp)
        .map(|(i, _)| i);

    let window_end = finish_idx.unwrap_or(marks.len().saturating_sub(1));
    if finish_idx.is_none() {
        diagnostics.push("finish_missing".to_string());
    }

    let window = &marks[start_idx..=window_end];
    let punches: Vec<i64> = window.iter().map(|m| m.cp_number).collect();
    let taken = required
        .map(|cps| course_subsequence_taken_with_adds(&punches, cps, add_cp_counts))
        .unwrap_or(0);
    let complete = required
        .map(|cps| taken == cps.len())
        .unwrap_or(false);
    if required.is_some() && !complete {
        diagnostics.push("course_incomplete".to_string());
    }

    let start_time = marks[start_idx].mark_time;
    let end_time = marks[window_end].mark_time;
    let elapsed = (end_time - start_time).num_seconds().max(0);
    let elapsed = if let Some(cps) = required {
        apply_orient_exclusion_rules(
            window,
            cps,
            start_cp,
            settings.finish_cp,
            course_id,
            exclusion_rules,
            elapsed,
        )
    } else {
        elapsed
    };
    let control_seconds = settings.control_minutes.max(0) * 60;
    let delay_seconds = (elapsed - control_seconds).max(0);
    let penalty_minutes = if delay_seconds > 0 {
        (delay_seconds + 59) / 60
    } else {
        0
    };
    if elapsed > control_seconds {
        diagnostics.push("overtime".to_string());
    }

    let status = if diagnostics.iter().any(|d| {
        matches!(
            d.as_str(),
            "missing_course" | "course_unknown" | "course_empty" | "start_cp_not_configured"
        )
    }) {
        STATUS_ERR.to_string()
    } else if !complete || finish_idx.is_none() || elapsed > control_seconds {
        STATUS_DQ.to_string()
    } else {
        STATUS_OK.to_string()
    };

    ResultCalc {
        status,
        points_raw: taken as i64,
        penalty_points: 0,
        points_final: 0,
        elapsed_seconds: elapsed,
        delay_seconds,
        penalty_minutes,
        diagnostics,
    }
}

/// Finish row not matched to start: «Нет в протоколе», or «Ошибка» if bib is duplicated in finish.
fn apply_finish_not_in_start_status(result: &mut ResultCalc, duplicate_finish_bib: bool) {
    if duplicate_finish_bib {
        prepend_diag(result, "duplicate_bib_in_finish");
        result.status = STATUS_ERR.to_string();
    } else {
        result.status = STATUS_NOT_IN_PROTOCOL.to_string();
    }
}

fn describe_result_error(
    participant_id: &str,
    name: &str,
    diagnostics: &[String],
) -> String {
    if diagnostics.iter().any(|d| d == "duplicate_bib_in_finish") {
        return format!(
            "В финишном протоколе несколько записей с id {participant_id}. Участник «{name}» отсутствует в стартовом протоколе для этого id."
        );
    }
    if diagnostics
        .iter()
        .any(|d| d == "duplicate_chip_in_start_protocol")
    {
        return format!(
            "В стартовом протоколе несколько записей с id {participant_id}. Конфликт для участника «{name}»."
        );
    }
    if diagnostics.is_empty() {
        format!("Ошибка для участника «{name}» (id {participant_id}).")
    } else {
        format!(
            "Ошибка для участника «{name}» (id {participant_id}): {}.",
            diagnostics.join(", ")
        )
    }
}

fn calculate_result_for_chip_entry(
    tx: &Transaction<'_>,
    global_settings: &Settings,
    finish_participant: &Participant,
    start_entry: Option<(&str, &str)>,
    marks: &[Mark],
    exclusion_rules: &[ExclusionRule],
    manual_corrections: &[ManualCorrection],
    cp_type_by_number: &HashMap<i64, i64>,
    format_type_rules: &HashMap<i64, Vec<(i64, Option<i64>)>>,
) -> Result<ResultCalc, String> {
    let (settings, format_id) = match start_entry {
        Some((chip, name)) => {
            let format_id = load_format_id_for_start_entry_tx(tx, chip, name)?;
            let settings = apply_format_settings_tx(tx, global_settings, format_id)?;
            (settings, format_id)
        }
        None => (global_settings.clone(), None),
    };
    let type_rules = format_id
        .and_then(|fid| format_type_rules.get(&fid))
        .map(|v| v.as_slice())
        .filter(|v| !v.is_empty());
    calculate_result(
        finish_participant,
        marks,
        exclusion_rules,
        manual_corrections,
        &settings,
        cp_type_by_number,
        type_rules,
    )
}

fn load_format_id_for_start_entry_tx(
    tx: &Transaction<'_>,
    participant_id: &str,
    name: &str,
) -> Result<Option<i64>, String> {
    Ok(tx
        .query_row(
            "SELECT format_id FROM start_protocol WHERE participant_id = ? AND name = ? LIMIT 1",
            params![participant_id, name],
            |r| r.get::<_, Option<i64>>(0),
        )
        .optional()
        .map_err(|e| format!("load format_id for start entry: {e}"))?
        .flatten())
}

fn insert_result_row(
    tx: &Transaction<'_>,
    finish_participant_id: Option<i64>,
    chip_raw_id: Option<&str>,
    participant_id: &str,
    name: &str,
    result: &ResultCalc,
) -> Result<(), String> {
    tx.execute(
        r#"
        INSERT INTO results(
            finish_participant_id, chip_raw_id, participant_id, name, status,
            points_raw, penalty_points, points_final,
            elapsed_seconds, delay_seconds, penalty_minutes, diagnostics_json, computed_at
        ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        "#,
        params![
            finish_participant_id,
            chip_raw_id,
            participant_id,
            name,
            result.status,
            result.points_raw,
            result.penalty_points,
            result.points_final,
            result.elapsed_seconds,
            result.delay_seconds,
            result.penalty_minutes,
            serde_json::to_string(&result.diagnostics).map_err(|e| format!("diag json: {e}"))?,
            Utc::now().naive_utc().to_string(),
        ],
    )
    .map_err(|e| format!("insert result: {e}"))?;
    Ok(())
}

fn start_name_missing_format_tx(
    tx: &Transaction<'_>,
    participant_id: &str,
    name: &str,
) -> Result<bool, String> {
    let row = tx
        .query_row(
            r#"
            SELECT format_id, TRIM(IFNULL(format_name, ''))
            FROM start_protocol
            WHERE participant_id = ? AND name = ?
            LIMIT 1
            "#,
            params![participant_id, name],
            |r| Ok((r.get::<_, Option<i64>>(0)?, r.get::<_, String>(1)?)),
        )
        .optional()
        .map_err(|e| format!("check start protocol format: {e}"))?;
    Ok(match row {
        None => true,
        Some((format_id, format_name)) => format_id.is_none() || format_name.is_empty(),
    })
}

fn marks_have_duplicate_seq(marks: &[Mark]) -> bool {
    let mut seen = HashSet::new();
    for m in marks {
        if !seen.insert(m.seq) {
            return true;
        }
    }
    false
}

fn apply_format_settings_tx(
    tx: &Transaction<'_>,
    global: &Settings,
    format_id: Option<i64>,
) -> Result<Settings, String> {
    let Some(format_id) = format_id else {
        return Ok(global.clone());
    };
    let overrides = tx
        .query_row(
            r#"
            SELECT control_minutes, penalty_per_minute, dq_minutes, finish_cp, competition_start_time
            FROM format_settings
            WHERE format_id = ?
            "#,
            params![format_id],
            |r| {
                Ok((
                    r.get::<_, Option<i64>>(0)?,
                    r.get::<_, Option<i64>>(1)?,
                    r.get::<_, Option<i64>>(2)?,
                    r.get::<_, Option<i64>>(3)?,
                    r.get::<_, Option<String>>(4)?,
                ))
            },
        )
        .optional()
        .map_err(|e| format!("load format settings: {e}"))?;
    let Some((control_minutes, penalty_per_minute, dq_minutes, finish_cp, competition_start_time)) =
        overrides
    else {
        return Ok(global.clone());
    };
    let start_time_override = competition_start_time
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty());
    Ok(Settings {
        control_minutes: control_minutes.unwrap_or(global.control_minutes),
        penalty_per_minute: penalty_per_minute.unwrap_or(global.penalty_per_minute),
        dq_minutes: dq_minutes.unwrap_or(global.dq_minutes),
        finish_cp: finish_cp.unwrap_or(global.finish_cp),
        start_mode: global.start_mode.clone(),
        start_cp: global.start_cp,
        competition_date: global.competition_date.clone(),
        competition_start_time: start_time_override
            .unwrap_or_else(|| global.competition_start_time.clone()),
        sport_kind: global.sport_kind.clone(),
    })
}

#[derive(Debug, Clone)]
struct ResultCalc {
    status: String,
    points_raw: i64,
    penalty_points: i64,
    points_final: i64,
    elapsed_seconds: i64,
    delay_seconds: i64,
    penalty_minutes: i64,
    diagnostics: Vec<String>,
}

fn calculate_result(
    _participant: &Participant,
    marks: &[Mark],
    exclusion_rules: &[ExclusionRule],
    manual_corrections: &[ManualCorrection],
    settings: &Settings,
    cp_type_by_number: &HashMap<i64, i64>,
    type_rules: Option<&[(i64, Option<i64>)]>,
) -> Result<ResultCalc, String> {
    let mut diagnostics: Vec<String> = Vec::new();
    let control_seconds = settings.control_minutes * 60;
    let dq_seconds = (settings.control_minutes + settings.dq_minutes) * 60;
    let mut adjusted_elapsed = 0_i64;

    let effective_marks: &[Mark] = match (settings.start_mode.as_str(), settings.start_cp) {
        ("station", None) => {
            diagnostics.push("start_cp_not_configured".to_string());
            &[]
        }
        (_, Some(start_cp)) => match marks.iter().position(|m| m.cp_number == start_cp) {
            Some(idx) => &marks[idx..],
            None => {
                diagnostics.push("start_missing".to_string());
                &[]
            }
        },
        (_, None) => marks,
    };

    if effective_marks.is_empty() {
        if marks.is_empty() {
            diagnostics.push("no_marks".to_string());
        }
    } else {
        if !effective_marks
            .iter()
            .any(|m| m.cp_number == settings.finish_cp)
        {
            diagnostics.push("finish_missing".to_string());
        }
        if effective_marks
            .last()
            .map(|m| m.cp_number != settings.finish_cp)
            .unwrap_or(false)
        {
            diagnostics.push("finish_not_last".to_string());
        }

        let clock_start = match settings.start_mode.as_str() {
            "time" => match competition_start_datetime(settings) {
                Some(dt) => Some(dt),
                None => {
                    diagnostics.push("start_time_not_configured".to_string());
                    None
                }
            },
            _ => {
                // station mode: from first mark at start station (already trimmed)
                effective_marks.first().map(|m| m.mark_time)
            }
        };

        if let Some(start_time) = clock_start {
            let finish_time = effective_marks
                .last()
                .map(|m| m.mark_time)
                .unwrap_or(start_time);
            adjusted_elapsed = (finish_time - start_time).num_seconds().max(0);
            adjusted_elapsed =
                apply_exclusion_rules(effective_marks, exclusion_rules, adjusted_elapsed);
        }
    }

    let start_cp_for_points = settings.start_cp;
    let mut ordered_cps: Vec<i64> = Vec::new();
    let mut seen_cps: HashSet<i64> = HashSet::new();
    for m in effective_marks {
        if Some(m.cp_number) == start_cp_for_points || m.cp_number == settings.finish_cp {
            continue;
        }
        if seen_cps.insert(m.cp_number) {
            ordered_cps.push(m.cp_number);
        }
    }
    let mut removed_point_cps: HashSet<i64> = HashSet::new();
    let mut added_point_cps: HashSet<i64> = HashSet::new();
    let mut remove_legs_cps: HashSet<i64> = HashSet::new();
    let mut anomaly_elapsed_subtract_seconds = 0_i64;
    for c in manual_corrections {
        match c.correction_type.as_str() {
            "remove_cp" => {
                if let Some(cp) = c.payload.get("cp_number").and_then(|v| v.as_i64()) {
                    removed_point_cps.insert(cp);
                    let mode = c
                        .payload
                        .get("remove_mode")
                        .and_then(|v| v.as_str())
                        .unwrap_or("remove_legs");
                    if mode == "remove_legs" {
                        remove_legs_cps.insert(cp);
                    }
                }
            }
            "add_cp" => {
                if let Some(cp) = c.payload.get("cp_number").and_then(|v| v.as_i64()) {
                    added_point_cps.insert(cp);
                }
            }
            "anomaly_day_shift_24h" => {
                let seconds = c
                    .payload
                    .get("seconds")
                    .and_then(|v| v.as_i64())
                    .unwrap_or(86_400);
                anomaly_elapsed_subtract_seconds += seconds.max(0);
            }
            _ => {}
        }
    }
    if !remove_legs_cps.is_empty() {
        adjusted_elapsed =
            apply_removed_cp_legs(effective_marks, &remove_legs_cps, adjusted_elapsed);
    }
    if anomaly_elapsed_subtract_seconds > 0 {
        adjusted_elapsed = (adjusted_elapsed - anomaly_elapsed_subtract_seconds).max(0);
    }

    ordered_cps.retain(|cp| !removed_point_cps.contains(cp));
    seen_cps = ordered_cps.iter().copied().collect();
    for cp in &added_point_cps {
        if Some(*cp) == start_cp_for_points || *cp == settings.finish_cp {
            continue;
        }
        if seen_cps.insert(*cp) {
            ordered_cps.push(*cp);
        }
    }

    let scored_cps = filter_cps_by_format_type_rules(&ordered_cps, cp_type_by_number, type_rules);

    let points_raw: i64 = scored_cps.iter().map(|cp| cp / 10).sum();
    let delay_seconds = (adjusted_elapsed - control_seconds).max(0);
    let penalty_minutes = if delay_seconds > 0 {
        (delay_seconds + 59) / 60
    } else {
        0
    };
    let penalty_points = penalty_minutes * settings.penalty_per_minute;
    let points_final = points_raw - penalty_points;

    let status = if !diagnostics.is_empty() {
        STATUS_ERR.to_string()
    } else if adjusted_elapsed > dq_seconds {
        STATUS_DQ.to_string()
    } else {
        STATUS_OK.to_string()
    };

    Ok(ResultCalc {
        status,
        points_raw,
        penalty_points,
        points_final,
        elapsed_seconds: adjusted_elapsed,
        delay_seconds,
        penalty_minutes,
        diagnostics,
    })
}

fn load_cp_type_by_number_tx(tx: &Transaction<'_>) -> Result<HashMap<i64, i64>, String> {
    let mut stmt = tx
        .prepare(
            r#"
            SELECT cp_number, cp_type_id
            FROM cp_legends
            WHERE cp_type_id IS NOT NULL
            "#,
        )
        .map_err(|e| format!("prepare cp_legends type map: {e}"))?;
    let rows = stmt
        .query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, i64>(1)?)))
        .map_err(|e| format!("query cp_legends type map: {e}"))?;
    let mut out = HashMap::new();
    for row in rows {
        let (cp_number, cp_type_id) = row.map_err(|e| format!("read cp type map row: {e}"))?;
        out.insert(cp_number, cp_type_id);
    }
    Ok(out)
}

fn load_format_cp_type_rules_map_tx(
    tx: &Transaction<'_>,
) -> Result<HashMap<i64, Vec<(i64, Option<i64>)>>, String> {
    let mut stmt = tx
        .prepare(
            r#"
            SELECT format_id, cp_type_id, max_count
            FROM format_cp_type_rules
            ORDER BY format_id ASC, cp_type_id ASC
            "#,
        )
        .map_err(|e| format!("prepare format_cp_type_rules map: {e}"))?;
    let rows = stmt
        .query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, i64>(1)?,
                r.get::<_, Option<i64>>(2)?,
            ))
        })
        .map_err(|e| format!("query format_cp_type_rules map: {e}"))?;
    let mut out: HashMap<i64, Vec<(i64, Option<i64>)>> = HashMap::new();
    for row in rows {
        let (format_id, cp_type_id, max_count) =
            row.map_err(|e| format!("read format_cp_type_rules row: {e}"))?;
        out.entry(format_id).or_default().push((cp_type_id, max_count));
    }
    Ok(out)
}

/// Keep CPs allowed by format type rules, respecting optional per-type max_count.
/// First visits (order of `ordered_cps`) are preferred when capping.
fn filter_cps_by_format_type_rules(
    ordered_cps: &[i64],
    cp_type_by_number: &HashMap<i64, i64>,
    type_rules: Option<&[(i64, Option<i64>)]>,
) -> Vec<i64> {
    let Some(rules) = type_rules.filter(|r| !r.is_empty()) else {
        return ordered_cps.to_vec();
    };
    let mut max_by_type: HashMap<i64, Option<i64>> = HashMap::new();
    for (type_id, max_count) in rules {
        max_by_type.insert(*type_id, *max_count);
    }
    let mut taken_by_type: HashMap<i64, i64> = HashMap::new();
    let mut kept = Vec::new();
    for cp in ordered_cps {
        let Some(&type_id) = cp_type_by_number.get(cp) else {
            // No type in legends → excluded when rules are active.
            continue;
        };
        let Some(max_count) = max_by_type.get(&type_id) else {
            continue;
        };
        let taken = taken_by_type.entry(type_id).or_insert(0);
        if let Some(limit) = *max_count {
            if *taken >= limit {
                continue;
            }
        }
        *taken += 1;
        kept.push(*cp);
    }
    kept
}

fn resolve_orient_leg_to(
    from_cp: i64,
    required: &[i64],
    start_cp: i64,
    finish_cp: i64,
) -> Option<i64> {
    if from_cp == start_cp {
        return required.first().copied();
    }
    let i = required.iter().position(|cp| *cp == from_cp)?;
    Some(required.get(i + 1).copied().unwrap_or(finish_cp))
}

/// Consecutive take-order legs: start → first CP, each course CP to the next, last → finish.
/// The same CP can appear more than once (e.g. 58→68 and later 58→finish).
fn orient_course_legs(
    controls: &[i64],
    start_cp: Option<i64>,
    finish_cp: i64,
    removed: &HashSet<i64>,
) -> Vec<(i64, i64)> {
    let kept: Vec<i64> = controls
        .iter()
        .copied()
        .filter(|cp| !removed.contains(cp))
        .collect();
    let mut seq = Vec::new();
    if let Some(start) = start_cp {
        if kept.first().copied() != Some(start) {
            seq.push(start);
        }
    }
    seq.extend(kept);
    if seq.last().copied() != Some(finish_cp) && finish_cp > 0 {
        seq.push(finish_cp);
    }
    seq.windows(2)
        .filter(|pair| pair[0] != pair[1])
        .map(|pair| (pair[0], pair[1]))
        .collect()
}

/// Subtract time from `from_cp` to the next course control (extras in between count).
/// Direction is always forward; every completed crossing is excluded.
fn apply_orient_exclusion_rules(
    window: &[Mark],
    required: &[i64],
    start_cp: i64,
    finish_cp: i64,
    course_id: Option<i64>,
    rules: &[ExclusionRule],
    elapsed_seconds: i64,
) -> i64 {
    if window.len() < 2 || rules.is_empty() {
        return elapsed_seconds;
    }
    let mut adjusted = elapsed_seconds;
    for rule in rules {
        if let Some(rule_course_id) = rule.course_id {
            if course_id != Some(rule_course_id) {
                continue;
            }
        } else if course_id.is_some()
            && rules.iter().any(|other| {
                other.from_cp == rule.from_cp && other.course_id == course_id
            })
        {
            continue;
        }
        let to_cp = if rule.to_cp > 0 {
            rule.to_cp
        } else {
            match resolve_orient_leg_to(rule.from_cp, required, start_cp, finish_cp) {
                Some(cp) => cp,
                None => continue,
            }
        };
        let mut pending: Option<NaiveDateTime> = None;
        for m in window {
            if m.cp_number == rule.from_cp {
                pending = Some(m.mark_time);
            } else if m.cp_number == to_cp {
                if let Some(t0) = pending.take() {
                    let delta = (m.mark_time - t0).num_seconds().max(0);
                    let cap = match rule.max_leg_seconds {
                        Some(limit) => delta.min(limit),
                        None => delta,
                    };
                    adjusted = (adjusted - cap).max(0);
                }
            }
        }
    }
    adjusted
}

fn apply_exclusion_rules(marks: &[Mark], rules: &[ExclusionRule], elapsed_seconds: i64) -> i64 {
    if marks.len() < 2 || rules.is_empty() {
        return elapsed_seconds;
    }
    let mut adjusted = elapsed_seconds;
    let legs: Vec<(i64, i64, i64)> = marks
        .windows(2)
        .map(|w| {
            let from = w[0].cp_number;
            let to = w[1].cp_number;
            let delta = (w[1].mark_time - w[0].mark_time).num_seconds().max(0);
            (from, to, delta)
        })
        .collect();
    for rule in rules {
        for (from_cp, to_cp, delta) in &legs {
            if !rule_matches(rule, *from_cp, *to_cp) {
                continue;
            }
            let cap = match rule.max_leg_seconds {
                Some(limit) => (*delta).min(limit),
                None => *delta,
            };
            adjusted = (adjusted - cap.max(0)).max(0);
            if rule.apply_mode == "once" {
                break;
            }
        }
    }
    adjusted
}

fn apply_removed_cp_legs(marks: &[Mark], removed_cps: &HashSet<i64>, elapsed_seconds: i64) -> i64 {
    if marks.len() < 2 || removed_cps.is_empty() {
        return elapsed_seconds;
    }
    let mut leg_indexes: HashSet<usize> = HashSet::new();
    for (idx, m) in marks.iter().enumerate() {
        if !removed_cps.contains(&m.cp_number) {
            continue;
        }
        if idx > 0 {
            leg_indexes.insert(idx - 1);
        }
        if idx + 1 < marks.len() {
            leg_indexes.insert(idx);
        }
    }
    let mut subtract = 0_i64;
    for leg_idx in leg_indexes {
        let delta = (marks[leg_idx + 1].mark_time - marks[leg_idx].mark_time)
            .num_seconds()
            .max(0);
        subtract += delta;
    }
    (elapsed_seconds - subtract).max(0)
}

fn rule_matches(rule: &ExclusionRule, from_cp: i64, to_cp: i64) -> bool {
    match rule.direction.as_str() {
        "forward" => from_cp == rule.from_cp && to_cp == rule.to_cp,
        "reverse" => from_cp == rule.to_cp && to_cp == rule.from_cp,
        "both" => {
            (from_cp == rule.from_cp && to_cp == rule.to_cp)
                || (from_cp == rule.to_cp && to_cp == rule.from_cp)
        }
        _ => false,
    }
}

fn apply_legacy_corrections(
    tx: &Transaction<'_>,
    finish_participant_id: i64,
    marks: &mut Vec<Mark>,
    exclusion_rules: &mut Vec<ExclusionRule>,
) -> Result<(), String> {
    let mut stmt = tx
        .prepare(
            "SELECT id, correction_type, payload_json FROM corrections WHERE finish_participant_id = ? ORDER BY id",
        )
        .map_err(|e| format!("prepare corrections query: {e}"))?;
    let rows = stmt
        .query_map(params![finish_participant_id], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
            ))
        })
        .map_err(|e| format!("query corrections: {e}"))?;

    for row in rows {
        let (id, correction_type, payload_json) =
            row.map_err(|e| format!("read correction row: {e}"))?;
        let payload: Value = serde_json::from_str(&payload_json)
            .map_err(|e| format!("parse correction payload: {e}"))?;
        match correction_type.as_str() {
            "add_cp" => {
                let cp_number = payload_int(&payload, "cp_number")?;
                let mark_time = parse_time(&payload_str(&payload, "mark_time")?)
                    .map_err(|e| format!("parse add_cp time: {e}"))?;
                marks.push(Mark {
                    cp_number,
                    mark_time,
                    seq: 1_000_000 + id,
                });
            }
            "remove_cp" => {
                let cp_number = payload_int(&payload, "cp_number")?;
                let remove_all = payload
                    .get("remove_all")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false);
                let mark_time_exact = payload
                    .get("mark_time")
                    .and_then(|v| v.as_str())
                    .and_then(|v| parse_time(v).ok());
                let mut indexes: Vec<usize> = Vec::new();
                for (idx, mark) in marks.iter().enumerate() {
                    if mark.cp_number != cp_number {
                        continue;
                    }
                    if let Some(exact) = mark_time_exact {
                        if mark.mark_time != exact {
                            continue;
                        }
                    }
                    indexes.push(idx);
                    if !remove_all {
                        break;
                    }
                }
                for idx in indexes.into_iter().rev() {
                    marks.remove(idx);
                }
            }
            "exclude_leg_time" => {
                exclusion_rules.push(ExclusionRule {
                    from_cp: payload_int(&payload, "from_cp")?,
                    to_cp: payload_int(&payload, "to_cp")?,
                    direction: payload
                        .get("direction")
                        .and_then(|v| v.as_str())
                        .unwrap_or("forward")
                        .to_string(),
                    apply_mode: payload
                        .get("apply_mode")
                        .and_then(|v| v.as_str())
                        .unwrap_or("once")
                        .to_string(),
                    max_leg_seconds: payload.get("max_leg_seconds").and_then(|v| v.as_i64()),
                    course_id: None,
                    source: format!("legacy:{id}"),
                });
            }
            _ => {}
        }
    }
    Ok(())
}

fn load_leg_exclusion_rules(
    tx: &Transaction<'_>,
    finish_participant_id: i64,
    bib_id: &str,
) -> Result<Vec<ExclusionRule>, String> {
    let format_id: Option<i64> = tx
        .query_row(
            "SELECT format_id FROM start_protocol WHERE participant_id = ? LIMIT 1",
            params![bib_id],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("load participant format for exclusions: {e}"))?
        .flatten();

    let mut stmt = tx
        .prepare(
            r#"
            SELECT id, finish_participant_id, format_id, from_cp, to_cp, direction, apply_mode, max_leg_seconds, course_id
            FROM leg_exclusion_rules
            WHERE finish_participant_id = ?
               OR (
                    finish_participant_id IS NULL
                    AND (
                        format_id IS NULL
                        OR (? IS NOT NULL AND format_id = ?)
                    )
               )
            ORDER BY id
            "#,
        )
        .map_err(|e| format!("prepare leg exclusion rules query: {e}"))?;
    let rows = stmt
        .query_map(params![finish_participant_id, format_id, format_id], |r| {
            let id: i64 = r.get(0)?;
            let personal: Option<i64> = r.get(1)?;
            let rule_format_id: Option<i64> = r.get(2)?;
            let course_id: Option<i64> = r.get(8)?;
            let source = if personal.is_some() {
                format!("rule:{id}:participant")
            } else if rule_format_id.is_some() {
                format!("rule:{id}:format")
            } else if course_id.is_some() {
                format!("rule:{id}:course")
            } else {
                format!("rule:{id}:global")
            };
            Ok(ExclusionRule {
                from_cp: r.get(3)?,
                to_cp: r.get(4)?,
                direction: r.get::<_, String>(5)?,
                apply_mode: r.get::<_, String>(6)?,
                max_leg_seconds: r.get(7)?,
                course_id,
                source,
            })
        })
        .map_err(|e| format!("query leg exclusion rules: {e}"))?;
    let mut out = Vec::new();
    for row in rows {
        out.push(row.map_err(|e| format!("read leg rule: {e}"))?);
    }
    Ok(out)
}

fn require_course_id(conn: &Connection, course_id: i64) -> Result<(), String> {
    let exists = conn
        .query_row(
            "SELECT id FROM courses WHERE id = ?",
            params![course_id],
            |_| Ok(()),
        )
        .optional()
        .map_err(|e| format!("check course exists: {e}"))?;
    if exists.is_none() {
        return Err(format!("Дистанция id={course_id} не найдена"));
    }
    Ok(())
}

fn require_format_id(conn: &Connection, format_id: i64) -> Result<(), String> {
    let exists = conn
        .query_row(
            "SELECT id FROM start_protocol_formats WHERE id = ?",
            params![format_id],
            |_| Ok(()),
        )
        .optional()
        .map_err(|e| format!("check format exists: {e}"))?;
    if exists.is_none() {
        return Err(format!("Format {format_id} not found"));
    }
    Ok(())
}

fn resolve_exclusion_scope(
    conn: &Connection,
    participant_scope: &str,
    participant_id: Option<String>,
    format_id: Option<i64>,
) -> Result<(Option<i64>, Option<i64>), String> {
    match participant_scope {
        "one" => {
            if format_id.is_some() {
                return Err(
                    "format_id must be empty for personal exclusion rules (participant_scope=one)"
                        .to_string(),
                );
            }
            let raw = participant_id
                .filter(|x| !x.trim().is_empty())
                .ok_or_else(|| {
                    "finish participant id is required for participant_scope=one".to_string()
                })?;
            let finish_id = parse_finish_participant_id(&raw)?;
            require_finish_participant(conn, finish_id)?;
            Ok((Some(finish_id), None))
        }
        "all" => {
            if let Some(fid) = format_id {
                require_format_id(conn, fid)?;
                Ok((None, Some(fid)))
            } else {
                Ok((None, None))
            }
        }
        "format" => {
            let fid = format_id
                .ok_or_else(|| "format_id is required for participant_scope=format".to_string())?;
            require_format_id(conn, fid)?;
            Ok((None, Some(fid)))
        }
        _ => Err("participant_scope must be one of: one, all, format".to_string()),
    }
}

fn sql_effective_course_name(participant_alias: &str) -> String {
    format!(
        r#"COALESCE(
            NULLIF(TRIM(IFNULL((
                SELECT json_extract(mc.payload_json, '$.to')
                FROM manual_corrections mc
                WHERE mc.finish_participant_id = {participant_alias}.id
                  AND mc.correction_type = 'assign_course'
                ORDER BY mc.id DESC
                LIMIT 1
            ), '')), ''),
            NULLIF(TRIM(IFNULL({participant_alias}.course_name, '')), '')
        )"#
    )
}

fn assigned_course_from_corrections(
    finish_id: i64,
    corrections: &[ManualCorrection],
) -> Option<String> {
    corrections
        .iter()
        .rev()
        .filter(|c| {
            c.correction_type == "assign_course" && c.finish_participant_id == Some(finish_id)
        })
        .find_map(|c| {
            c.payload
                .get("to")
                .and_then(|v| v.as_str())
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(|s| s.to_string())
        })
}

fn effective_course_name(stored: &str, finish_id: i64, corrections: &[ManualCorrection]) -> String {
    assigned_course_from_corrections(finish_id, corrections)
        .unwrap_or_else(|| stored.trim().to_string())
}

fn load_manual_corrections(
    conn: &Connection,
    finish_participant_id: i64,
) -> Result<Vec<ManualCorrection>, String> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT id, finish_participant_id, correction_type, payload_json
            FROM manual_corrections
            WHERE finish_participant_id = ? OR finish_participant_id IS NULL
            ORDER BY id
            "#,
        )
        .map_err(|e| format!("prepare manual corrections query: {e}"))?;
    let rows = stmt
        .query_map(params![finish_participant_id], |r| {
            let payload_raw: String = r.get(3)?;
            let payload: Value = serde_json::from_str(&payload_raw).map_err(|e| {
                rusqlite::Error::FromSqlConversionFailure(
                    3,
                    rusqlite::types::Type::Text,
                    Box::new(std::io::Error::new(std::io::ErrorKind::InvalidData, e)),
                )
            })?;
            Ok(ManualCorrection {
                finish_participant_id: r.get(1)?,
                correction_type: r.get(2)?,
                payload,
            })
        })
        .map_err(|e| format!("query manual corrections: {e}"))?;
    let mut out = Vec::new();
    for row in rows {
        out.push(row.map_err(|e| format!("read manual correction row: {e}"))?);
    }
    Ok(out)
}

fn load_course_controls_by_id(conn: &Connection, course_id: i64) -> Result<Vec<i64>, String> {
    let mut stmt = conn
        .prepare(
            "SELECT cp_number FROM course_controls WHERE course_id = ? ORDER BY seq ASC",
        )
        .map_err(|e| format!("prepare course controls by id: {e}"))?;
    let rows = stmt
        .query_map(params![course_id], |r| r.get::<_, i64>(0))
        .map_err(|e| format!("query course controls by id: {e}"))?;
    let mut out = Vec::new();
    for row in rows {
        out.push(row.map_err(|e| format!("read course control: {e}"))?);
    }
    Ok(out)
}

fn load_course_controls(conn: &Connection, course_name: &str) -> Result<Vec<i64>, String> {
    let key = course_key(course_name);
    if key.is_empty() {
        return Ok(Vec::new());
    }
    let mut stmt = conn
        .prepare(
            r#"
            SELECT c.name, cc.cp_number
            FROM courses c
            JOIN course_controls cc ON cc.course_id = c.id
            ORDER BY c.id ASC, cc.seq ASC
            "#,
        )
        .map_err(|e| format!("prepare course controls: {e}"))?;
    let rows = stmt
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)))
        .map_err(|e| format!("query course controls: {e}"))?;
    let mut out = Vec::new();
    for row in rows {
        let (name, cp) = row.map_err(|e| format!("read course control: {e}"))?;
        if course_key(&name) == key {
            out.push(cp);
        }
    }
    Ok(out)
}

fn apply_manual_mark_corrections(marks: &mut Vec<Mark>, corrections: &[ManualCorrection]) {
    for c in corrections {
        match c.correction_type.as_str() {
            "remap_cp" => {
                let Ok(from_cp) = payload_int(&c.payload, "from_cp") else {
                    continue;
                };
                let Ok(to_cp) = payload_int(&c.payload, "to_cp") else {
                    continue;
                };
                let Some(mark_time_raw) = c.payload.get("mark_time").and_then(|v| v.as_str()) else {
                    continue;
                };
                let Ok(mark_time) = parse_time(mark_time_raw) else {
                    continue;
                };
                let seq_filter = c.payload.get("seq").and_then(|v| v.as_i64());
                if let Some(mark) = marks.iter_mut().find(|m| {
                    m.cp_number == from_cp
                        && m.mark_time == mark_time
                        && seq_filter.map(|s| m.seq == s).unwrap_or(true)
                }) {
                    mark.cp_number = to_cp;
                }
            }
            "set_start_mark" => {
                let Ok(cp) = payload_int(&c.payload, "cp_number") else {
                    continue;
                };
                let Some(mark_time_raw) = c.payload.get("mark_time").and_then(|v| v.as_str()) else {
                    continue;
                };
                let Ok(mark_time) = parse_time(mark_time_raw) else {
                    continue;
                };
                if let Some(mark) = marks.iter_mut().find(|m| m.cp_number == cp) {
                    mark.mark_time = mark_time;
                } else {
                    marks.push(Mark {
                        cp_number: cp,
                        mark_time,
                        seq: 0,
                    });
                }
            }
            _ => {}
        }
    }
}

fn load_marks(conn: &Connection, finish_participant_id: i64) -> Result<Vec<Mark>, String> {
    let mut stmt = conn
        .prepare(
            "SELECT cp_number, mark_time, seq FROM marks_raw WHERE finish_participant_id = ? ORDER BY seq",
        )
        .map_err(|e| format!("prepare marks query: {e}"))?;
    let rows = stmt
        .query_map(params![finish_participant_id], |r| {
            let ts: String = r.get(1)?;
            Ok(Mark {
                cp_number: r.get(0)?,
                mark_time: parse_time(&ts).map_err(|e| {
                    rusqlite::Error::FromSqlConversionFailure(
                        1,
                        rusqlite::types::Type::Text,
                        Box::new(std::io::Error::new(std::io::ErrorKind::InvalidData, e)),
                    )
                })?,
                seq: r.get(2)?,
            })
        })
        .map_err(|e| format!("query marks: {e}"))?;
    let mut out = Vec::new();
    for row in rows {
        out.push(row.map_err(|e| format!("read mark row: {e}"))?);
    }
    Ok(out)
}

fn get_settings_tx(tx: &Transaction<'_>) -> Result<Settings, String> {
    let mut stmt = tx
        .prepare("SELECT key, value FROM settings")
        .map_err(|e| format!("prepare settings query: {e}"))?;
    let rows = stmt
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))
        .map_err(|e| format!("query settings: {e}"))?;
    let mut map = BTreeMap::new();
    for row in rows {
        let (k, v) = row.map_err(|e| format!("read settings row: {e}"))?;
        map.insert(k, v);
    }
    settings_from_map(map)
}

pub fn get_settings(conn: &Connection) -> Result<Settings, String> {
    let mut stmt = conn
        .prepare("SELECT key, value FROM settings")
        .map_err(|e| format!("prepare settings query: {e}"))?;
    let rows = stmt
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))
        .map_err(|e| format!("query settings: {e}"))?;
    let mut map = BTreeMap::new();
    for row in rows {
        let (k, v) = row.map_err(|e| format!("read settings row: {e}"))?;
        map.insert(k, v);
    }
    settings_from_map(map)
}

pub fn get_setting_value(conn: &Connection, key: &str) -> Result<Option<String>, String> {
    conn.query_row(
        "SELECT value FROM settings WHERE key = ? LIMIT 1",
        params![key],
        |r| r.get::<_, String>(0),
    )
    .optional()
    .map_err(|e| format!("get setting {key}: {e}"))
}

pub fn set_setting_value(conn: &Connection, key: &str, value: &str) -> Result<(), String> {
    conn.execute(
        r#"
        INSERT INTO settings(key, value) VALUES(?, ?)
        ON CONFLICT(key) DO UPDATE SET value=excluded.value
        "#,
        params![key, value],
    )
    .map_err(|e| format!("set setting {key}: {e}"))?;
    Ok(())
}

pub fn delete_setting_value(conn: &Connection, key: &str) -> Result<(), String> {
    conn.execute("DELETE FROM settings WHERE key = ?", params![key])
        .map_err(|e| format!("delete setting {key}: {e}"))?;
    Ok(())
}

fn settings_from_map(map: BTreeMap<String, String>) -> Result<Settings, String> {
    let mut control_minutes = 240_i64;
    let mut penalty_per_minute = 1_i64;
    let mut dq_minutes = 15_i64;
    let mut finish_cp = 240_i64;
    let mut start_mode = "station".to_string();
    let mut start_cp: Option<i64> = None;
    let mut competition_date = String::new();
    let mut competition_start_time = String::new();
    let mut sport_kind = "rogaine".to_string();
    for (k, v) in map {
        let parsed = v.parse::<i64>().unwrap_or(0);
        match k.as_str() {
            "control_minutes" => control_minutes = parsed,
            "penalty_per_minute" => penalty_per_minute = parsed,
            "dq_minutes" => dq_minutes = parsed,
            "finish_cp" => finish_cp = parsed,
            "start_mode" => {
                let mode = v.trim().to_ascii_lowercase();
                if mode == "time" || mode == "station" {
                    start_mode = mode;
                }
            }
            "start_cp" => {
                let trimmed = v.trim();
                if trimmed.is_empty() {
                    start_cp = None;
                } else if let Ok(cp) = trimmed.parse::<i64>() {
                    start_cp = if cp > 0 { Some(cp) } else { None };
                }
            }
            "competition_date" => competition_date = v,
            "competition_start_time" => competition_start_time = normalize_competition_time(&v)?,
            "sport_kind" => {
                if let Ok(kind) = normalize_sport_kind(&v) {
                    sport_kind = kind;
                }
            }
            _ => {}
        }
    }
    Ok(Settings {
        control_minutes,
        penalty_per_minute,
        dq_minutes,
        finish_cp,
        start_mode,
        start_cp,
        competition_date,
        competition_start_time,
        sport_kind,
    })
}

fn validate_settings(settings: &Settings) -> Result<(), String> {
    match settings.start_mode.as_str() {
        "station" => {
            if settings.start_cp.is_none() {
                return Err(
                    "Для режима «старт по станции» укажите номер стартовой станции.".to_string(),
                );
            }
        }
        "time" => {
            if settings.competition_date.trim().is_empty() {
                return Err(
                    "Для режима «старт по времени» укажите дату соревнования.".to_string(),
                );
            }
            if settings.competition_start_time.trim().is_empty() {
                return Err(
                    "Для режима «старт по времени» укажите стартовое время.".to_string(),
                );
            }
            if competition_start_datetime(settings).is_none() {
                return Err(
                    "Некорректные дата/время старта. Ожидаются: дата YYYY-MM-DD и время HH:MM[:SS]."
                        .to_string(),
                );
            }
        }
        other => {
            return Err(format!("Неизвестный тип старта: {other}"));
        }
    }
    Ok(())
}

pub fn normalize_sport_kind(raw: &str) -> Result<String, String> {
    match raw.trim().to_ascii_lowercase().as_str() {
        "rogaine" => Ok("rogaine".to_string()),
        "orient" | "orienteering" => Ok("orient".to_string()),
        other => Err(format!(
            "Неизвестный вид соревнования: {other}. Ожидается rogaine или orient."
        )),
    }
}

pub fn working_start_has_data(conn: &Connection) -> Result<bool, String> {
    let counts = get_data_presence_counts(conn)?;
    Ok(
        counts.finish_participants > 0
            || counts.start_protocol > 0
            || counts.cp_legends > 0
            || counts.courses > 0,
    )
}

pub fn set_settings(
    conn: &Connection,
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
    let only_sport_kind = sport_kind.is_some()
        && control_minutes.is_none()
        && penalty_per_minute.is_none()
        && dq_minutes.is_none()
        && finish_cp.is_none()
        && start_mode.is_none()
        && start_cp.is_none()
        && competition_date.is_none()
        && competition_start_time.is_none();
    for (key, maybe_value) in [
        ("control_minutes", control_minutes),
        ("penalty_per_minute", penalty_per_minute),
        ("dq_minutes", dq_minutes),
        ("finish_cp", finish_cp),
    ] {
        if let Some(value) = maybe_value {
            conn.execute(
                r#"
                INSERT INTO settings(key, value) VALUES(?, ?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value
                "#,
                params![key, value.to_string()],
            )
            .map_err(|e| format!("set setting {key}: {e}"))?;
        }
    }
    if let Some(mode_value) = start_mode {
        let mode = mode_value.trim().to_ascii_lowercase();
        if mode != "station" && mode != "time" {
            return Err("start_mode must be 'station' or 'time'".to_string());
        }
        conn.execute(
            r#"
            INSERT INTO settings(key, value) VALUES(?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value
            "#,
            params!["start_mode", mode],
        )
        .map_err(|e| format!("set setting start_mode: {e}"))?;
        // UI saves start mode together with start station; None/empty clears.
        let cp_value = match start_cp {
            Some(cp) if cp > 0 => cp.to_string(),
            _ => String::new(),
        };
        conn.execute(
            r#"
            INSERT INTO settings(key, value) VALUES(?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value
            "#,
            params!["start_cp", cp_value],
        )
        .map_err(|e| format!("set setting start_cp: {e}"))?;
    } else if let Some(cp) = start_cp {
        let cp_value = if cp > 0 {
            cp.to_string()
        } else {
            String::new()
        };
        conn.execute(
            r#"
            INSERT INTO settings(key, value) VALUES(?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value
            "#,
            params!["start_cp", cp_value],
        )
        .map_err(|e| format!("set setting start_cp: {e}"))?;
    }
    if let Some(date_value) = competition_date {
        let normalized_date = date_value.trim().to_string();
        if !normalized_date.is_empty() {
            NaiveDate::parse_from_str(&normalized_date, "%Y-%m-%d")
                .map_err(|_| "competition_date must be in YYYY-MM-DD format".to_string())?;
        }
        conn.execute(
            r#"
            INSERT INTO settings(key, value) VALUES(?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value
            "#,
            params!["competition_date", normalized_date],
        )
        .map_err(|e| format!("set setting competition_date: {e}"))?;
    }
    if let Some(time_value) = competition_start_time {
        let normalized_time = normalize_competition_time(&time_value)?;
        conn.execute(
            r#"
            INSERT INTO settings(key, value) VALUES(?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value
            "#,
            params!["competition_start_time", normalized_time],
        )
        .map_err(|e| format!("set setting competition_start_time: {e}"))?;
    }
    if let Some(kind_value) = sport_kind {
        let kind = normalize_sport_kind(&kind_value)?;
        let current = get_settings(conn)?;
        if current.sport_kind != kind && working_start_has_data(conn)? {
            return Err(
                "Смена вида соревнования удалит текущие данные. Сохраните старт в архив или подтвердите очистку."
                    .to_string(),
            );
        }
        conn.execute(
            r#"
            INSERT INTO settings(key, value) VALUES(?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value
            "#,
            params!["sport_kind", kind],
        )
        .map_err(|e| format!("set setting sport_kind: {e}"))?;
        if kind == "orient" {
            conn.execute(
                r#"
                INSERT INTO settings(key, value) VALUES(?, ?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value
                "#,
                params!["start_mode", "station"],
            )
            .map_err(|e| format!("set setting start_mode for orient: {e}"))?;
        }
    }
    let settings = get_settings(conn)?;
    if !only_sport_kind {
        validate_settings(&settings)?;
    }
    Ok(settings)
}

/// Write a full settings snapshot without the "incomplete form" checks.
/// Used after wiping the working start so sport switching does not fail
/// just because start_cp was reset to empty.
pub fn put_settings(conn: &Connection, settings: &Settings) -> Result<Settings, String> {
    let kind = normalize_sport_kind(&settings.sport_kind)?;
    let start_mode = settings.start_mode.trim().to_ascii_lowercase();
    if start_mode != "station" && start_mode != "time" {
        return Err("start_mode must be 'station' or 'time'".to_string());
    }
    let start_cp = match settings.start_cp {
        Some(cp) if cp > 0 => cp.to_string(),
        _ => String::new(),
    };
    let date = settings.competition_date.trim().to_string();
    if !date.is_empty() {
        NaiveDate::parse_from_str(&date, "%Y-%m-%d")
            .map_err(|_| "competition_date must be in YYYY-MM-DD format".to_string())?;
    }
    let time = normalize_competition_time(&settings.competition_start_time)?;
    for (key, value) in [
        ("control_minutes", settings.control_minutes.to_string()),
        ("penalty_per_minute", settings.penalty_per_minute.to_string()),
        ("dq_minutes", settings.dq_minutes.to_string()),
        ("finish_cp", settings.finish_cp.to_string()),
        ("start_mode", start_mode),
        ("start_cp", start_cp),
        ("competition_date", date),
        ("competition_start_time", time),
        ("sport_kind", kind),
    ] {
        set_setting_value(conn, key, &value)?;
    }
    get_settings(conn)
}

fn append_course_name_filter(
    query: &mut String,
    params_dyn: &mut Vec<String>,
    course_name: Option<&str>,
) {
    let Some(name) = course_name.map(str::trim).filter(|s| !s.is_empty()) else {
        return;
    };
    query.push_str(&format!(" AND {} = ? ", sql_effective_course_name("p")));
    params_dyn.push(name.to_string());
}

fn orient_results_order_sql(sort_by: &str, sort_dir: &str) -> String {
    let status_rank = "CASE results.status WHEN 'OK' THEN 0 WHEN 'Дисквалификация' THEN 1 WHEN 'Ошибка' THEN 2 WHEN 'Не стартовал' THEN 3 ELSE 4 END";
    match (sort_by, sort_dir) {
        ("participant_id", "desc") => {
            "results.participant_id COLLATE NOCASE DESC, results.elapsed_seconds ASC".to_string()
        }
        ("participant_id", _) => {
            "results.participant_id COLLATE NOCASE ASC, results.elapsed_seconds ASC".to_string()
        }
        ("name", "desc") => {
            "results.name COLLATE NOCASE DESC, results.participant_id COLLATE NOCASE ASC".to_string()
        }
        ("name", _) => {
            "results.name COLLATE NOCASE ASC, results.participant_id COLLATE NOCASE ASC".to_string()
        }
        ("points_raw", "desc") => {
            "results.points_raw DESC, results.elapsed_seconds ASC, results.participant_id COLLATE NOCASE ASC".to_string()
        }
        ("points_raw", _) => {
            "results.points_raw ASC, results.elapsed_seconds ASC, results.participant_id COLLATE NOCASE ASC".to_string()
        }
        ("elapsed_seconds", "desc") => format!(
            "{status_rank} ASC, results.elapsed_seconds DESC, results.participant_id COLLATE NOCASE ASC"
        ),
        ("elapsed_seconds", _) => format!(
            "{status_rank} ASC, results.elapsed_seconds ASC, results.participant_id COLLATE NOCASE ASC"
        ),
        ("place", "desc") => format!(
            "{status_rank} DESC, results.elapsed_seconds DESC, results.participant_id COLLATE NOCASE DESC"
        ),
        _ => format!(
            "{status_rank} ASC, results.elapsed_seconds ASC, results.participant_id COLLATE NOCASE ASC"
        ),
    }
}

pub fn query_results(
    conn: &Connection,
    limit: i64,
    offset: i64,
    status: Option<String>,
    search: Option<String>,
    format_id: Option<i64>,
    only_missing_format: bool,
    award_group_id: Option<i64>,
    course_name: Option<String>,
    sort_by: Option<String>,
    sort_dir: Option<String>,
) -> Result<(Vec<ResultRow>, i64), String> {
    let safe_limit = limit.clamp(1, 2000);
    let safe_offset = offset.max(0);
    let age_sql = match competition_year(conn) {
        Some(year) => sql_person_age("sp", year),
        None => "NULL".to_string(),
    };
    let effective_p = sql_effective_course_name("p");
    let effective_p2 = sql_effective_course_name("p2");
    let mut query = format!(
        r#"
        SELECT
               results.id,
               results.finish_participant_id,
               results.chip_raw_id,
               results.participant_id,
               results.name,
               results.status,
               sp.format_id,
               COALESCE(
                   CASE
                       WHEN (SELECT value FROM settings WHERE key = 'sport_kind') = 'orient'
                       THEN {effective_p}
                       ELSE NULL
                   END,
                   IFNULL(sp.format_name, '')
               ) AS format_name,
               sp.team_id,
               CASE
                   WHEN sp.team_id IS NULL THEN 0
                   ELSE (
                       SELECT COUNT(*) FROM start_protocol tsz
                       WHERE tsz.team_id = sp.team_id
                   )
               END AS team_size,
               CASE
                   WHEN sp.team_id IS NULL THEN ''
                   ELSE COALESCE((
                       SELECT GROUP_CONCAT(tsz.participant_id || ' ' || tsz.name, ' · ')
                       FROM start_protocol tsz
                       WHERE tsz.team_id = sp.team_id
                         AND NOT (
                             tsz.participant_id = sp.participant_id
                             AND tsz.name = sp.name
                         )
                   ), '')
               END AS teammates,
               sp.gender,
               {age_sql} AS age,
               EXISTS(
                 SELECT 1 FROM corrections c
                 WHERE c.finish_participant_id = results.finish_participant_id
               ) OR EXISTS(
                 SELECT 1 FROM manual_corrections mc
                 WHERE mc.finish_participant_id = results.finish_participant_id
               ) AS has_personal_corrections,
               COALESCE(a.anomaly_count, 0) AS anomaly_count,
               results.points_raw, results.penalty_points, results.points_final,
               results.elapsed_seconds, results.delay_seconds, results.penalty_minutes, results.diagnostics_json, results.computed_at,
               ranked.place
        FROM results
        LEFT JOIN start_protocol sp
          ON sp.participant_id = results.participant_id
         AND sp.name = results.name
        LEFT JOIN participants p
          ON p.id = results.finish_participant_id
        LEFT JOIN (
            SELECT
                r.id AS result_id,
                RANK() OVER (
                        PARTITION BY {effective_p2}
                    ORDER BY r.elapsed_seconds ASC, r.participant_id COLLATE NOCASE ASC
                ) AS place
            FROM results r
            LEFT JOIN participants p2 ON p2.id = r.finish_participant_id
            WHERE r.status = 'OK'
        ) ranked ON ranked.result_id = results.id
        LEFT JOIN (
            SELECT pa.finish_participant_id, COUNT(*) AS anomaly_count
            FROM participant_anomalies pa
            WHERE NOT (
                (
                    pa.anomaly_type = 'start_time_day_shift'
                    AND EXISTS (
                        SELECT 1
                        FROM manual_corrections mc
                        WHERE mc.finish_participant_id = pa.finish_participant_id
                          AND mc.correction_type = 'anomaly_day_shift_24h'
                    )
                )
                OR (
                    pa.anomaly_type IN ('start_punch_missing', 'start_punch_after_finish')
                    AND EXISTS (
                        SELECT 1
                        FROM manual_corrections mc
                        WHERE mc.finish_participant_id = pa.finish_participant_id
                          AND mc.correction_type = 'set_start_mark'
                    )
                )
            )
            GROUP BY finish_participant_id
        ) a ON a.finish_participant_id = results.finish_participant_id
        WHERE 1=1
        "#
    );
    let mut params_dyn: Vec<String> = Vec::new();
    let mut int_params: Vec<i64> = Vec::new();
    let mut values: Vec<i64> = Vec::new();

    if let Some(ref s) = status {
        if is_known_status(s) {
            query.push_str(" AND results.status = ? ");
            params_dyn.push(s.clone());
        }
    }
    if let Some(ref s) = search {
        if let Some(wildcard) = search_like_pattern(s) {
            query.push_str(
                " AND (u_lower(results.participant_id) LIKE ? OR u_lower(results.name) LIKE ?) ",
            );
            params_dyn.push(wildcard.clone());
            params_dyn.push(wildcard);
        }
    }
    if only_missing_format {
        query.push_str(
            " AND (sp.format_id IS NULL OR TRIM(IFNULL(sp.format_name, '')) = '') ",
        );
    } else if let Some(fid) = format_id {
        query.push_str(" AND sp.format_id = ? ");
        int_params.push(fid);
    }
    if let Some(ag_id) = award_group_id {
        append_award_group_sql_filter(conn, &mut query, &mut int_params, ag_id)?;
    }
    let mut course_params: Vec<String> = Vec::new();
    append_course_name_filter(&mut query, &mut course_params, course_name.as_deref());
    let is_orient = get_setting_value(conn, "sport_kind")?
        .map(|v| v == "orient")
        .unwrap_or(false);
    let safe_sort_by = match sort_by.as_deref() {
        Some("participant_id") => "participant_id",
        Some("name") => "name",
        Some("points_raw") => "points_raw",
        Some("points_final") => "points_final",
        Some("elapsed_seconds") => "elapsed_seconds",
        Some("place") => "place",
        _ => {
            if is_orient {
                "place"
            } else {
                "points_final"
            }
        }
    };
    let safe_sort_dir = match sort_dir.as_deref() {
        Some("asc") => "asc",
        Some("desc") => "desc",
        _ => {
            if safe_sort_by == "elapsed_seconds" || safe_sort_by == "place" {
                "asc"
            } else {
                "desc"
            }
        }
    };
    // Keep teammates adjacent for every sort: rank the whole team by one aggregate key.
    let team_join = r#"
            FROM results r2
            INNER JOIN start_protocol sp2
              ON sp2.participant_id = r2.participant_id AND sp2.name = r2.name
            WHERE sp.team_id IS NOT NULL AND sp2.team_id = sp.team_id
    "#;
    let team_points_final_key = format!(
        "COALESCE((SELECT MAX(r2.points_final) {team_join}), results.points_final)"
    );
    let team_points_raw_key = format!(
        "COALESCE((SELECT MAX(r2.points_raw) {team_join}), results.points_raw)"
    );
    let team_elapsed_key = format!(
        "COALESCE((SELECT MIN(NULLIF(r2.elapsed_seconds, 0)) {team_join}), results.elapsed_seconds)"
    );
    let team_name_min_key =
        format!("COALESCE((SELECT MIN(r2.name) {team_join}), results.name)");
    let team_name_max_key =
        format!("COALESCE((SELECT MAX(r2.name) {team_join}), results.name)");
    let team_bib_min_key = format!(
        "COALESCE((SELECT MIN(r2.participant_id) {team_join}), results.participant_id)"
    );
    let team_bib_max_key = format!(
        "COALESCE((SELECT MAX(r2.participant_id) {team_join}), results.participant_id)"
    );
    let status_rank = "CASE results.status WHEN 'OK' THEN 0 WHEN 'Не стартовал' THEN 1 WHEN 'Нет в протоколе' THEN 2 WHEN 'Дисквалификация' THEN 3 ELSE 4 END";
    let team_status_key = format!(
        "COALESCE((SELECT MIN(CASE r2.status WHEN 'OK' THEN 0 WHEN 'Не стартовал' THEN 1 WHEN 'Нет в протоколе' THEN 2 WHEN 'Дисквалификация' THEN 3 ELSE 4 END) {team_join}), {status_rank})"
    );
    // Must follow the team rank key immediately so members stay contiguous.
    let team_group_tail =
        "CASE WHEN sp.team_id IS NULL THEN 1 ELSE 0 END ASC, IFNULL(sp.team_id, 0) ASC";

    let order_clause = if is_orient {
        orient_results_order_sql(safe_sort_by, safe_sort_dir)
    } else {
        match (safe_sort_by, safe_sort_dir) {
        ("participant_id", "asc") => format!(
            "{team_bib_min_key} COLLATE NOCASE ASC, {team_group_tail}, results.participant_id COLLATE NOCASE ASC, results.points_raw DESC, results.elapsed_seconds ASC"
        ),
        ("participant_id", "desc") => format!(
            "{team_bib_max_key} COLLATE NOCASE DESC, {team_group_tail}, results.participant_id COLLATE NOCASE DESC, results.points_raw DESC, results.elapsed_seconds ASC"
        ),
        ("name", "asc") => format!(
            "{team_name_min_key} COLLATE NOCASE ASC, {team_group_tail}, results.name COLLATE NOCASE ASC, results.participant_id COLLATE NOCASE ASC"
        ),
        ("name", "desc") => format!(
            "{team_name_max_key} COLLATE NOCASE DESC, {team_group_tail}, results.name COLLATE NOCASE DESC, results.participant_id COLLATE NOCASE ASC"
        ),
        ("points_raw", "asc") => format!(
            "{team_points_raw_key} ASC, {team_group_tail}, results.points_raw ASC, results.elapsed_seconds ASC, results.participant_id COLLATE NOCASE ASC"
        ),
        ("points_raw", "desc") => format!(
            "{team_points_raw_key} DESC, {team_group_tail}, results.points_raw DESC, results.elapsed_seconds ASC, results.participant_id COLLATE NOCASE ASC"
        ),
        ("points_final", "asc") => format!(
            "{team_status_key} ASC, {team_points_final_key} ASC, {team_group_tail}, results.points_final ASC, results.elapsed_seconds ASC, results.participant_id COLLATE NOCASE ASC"
        ),
        ("points_final", "desc") => format!(
            "{team_status_key} ASC, {team_points_final_key} DESC, {team_group_tail}, results.points_final DESC, results.elapsed_seconds ASC, results.participant_id COLLATE NOCASE ASC"
        ),
        ("elapsed_seconds", "desc") => format!(
            "{team_elapsed_key} DESC, {team_group_tail}, results.elapsed_seconds DESC, results.points_final DESC, results.participant_id COLLATE NOCASE ASC"
        ),
        _ => format!(
            "{team_elapsed_key} ASC, {team_group_tail}, results.elapsed_seconds ASC, results.points_final DESC, results.participant_id COLLATE NOCASE ASC"
        ),
        }
    };
    query.push_str(" ORDER BY ");
    query.push_str(&order_clause);
    query.push_str(" LIMIT ? OFFSET ? ");
    values.push(safe_limit);
    values.push(safe_offset);

    let mut stmt = conn
        .prepare(&query)
        .map_err(|e| format!("prepare query results: {e}"))?;

    let mut bind_values: Vec<rusqlite::types::Value> = params_dyn
        .into_iter()
        .map(rusqlite::types::Value::Text)
        .collect();
    for v in int_params {
        bind_values.push(rusqlite::types::Value::Integer(v));
    }
    for v in course_params {
        bind_values.push(rusqlite::types::Value::Text(v));
    }
    bind_values.push(rusqlite::types::Value::Integer(values[0]));
    bind_values.push(rusqlite::types::Value::Integer(values[1]));
    let rows = stmt
        .query_map(rusqlite::params_from_iter(bind_values), |r| {
            let anomaly_count: i64 = r.get(14)?;
            Ok(ResultRow {
                id: r.get(0)?,
                finish_participant_id: r.get(1)?,
                chip_raw_id: r.get(2)?,
                participant_id: r.get(3)?,
                name: r.get(4)?,
                status: r.get(5)?,
                format_id: r.get(6)?,
                format_name: r.get(7)?,
                team_id: r.get(8)?,
                team_size: r.get(9)?,
                teammates: r.get(10)?,
                gender: r.get(11)?,
                age: r.get(12)?,
                has_personal_corrections: r.get(13)?,
                has_anomalies: anomaly_count > 0,
                anomaly_count,
                points_raw: r.get(15)?,
                penalty_points: r.get(16)?,
                points_final: r.get(17)?,
                elapsed_seconds: r.get(18)?,
                delay_seconds: r.get(19)?,
                penalty_minutes: r.get(20)?,
                diagnostics_json: r.get(21)?,
                computed_at: r.get(22)?,
                place: r.get(23)?,
            })
        })
        .map_err(|e| format!("query results: {e}"))?;

    let mut out = Vec::new();
    for row in rows {
        out.push(row.map_err(|e| format!("read results row: {e}"))?);
    }
    let mut count_query = String::from(
        r#"
        SELECT COUNT(*)
        FROM results
        LEFT JOIN start_protocol sp
          ON sp.participant_id = results.participant_id
         AND sp.name = results.name
        LEFT JOIN participants p
          ON p.id = results.finish_participant_id
        WHERE 1=1
        "#,
    );
    let mut count_params_dyn: Vec<String> = Vec::new();
    let mut count_int_params: Vec<i64> = Vec::new();
    if let Some(ref s) = status {
        if is_known_status(s) {
            count_query.push_str(" AND results.status = ? ");
            count_params_dyn.push(s.clone());
        }
    }
    if let Some(ref s) = search {
        if let Some(wildcard) = search_like_pattern(s) {
            count_query.push_str(
                " AND (u_lower(results.participant_id) LIKE ? OR u_lower(results.name) LIKE ?) ",
            );
            count_params_dyn.push(wildcard.clone());
            count_params_dyn.push(wildcard);
        }
    }
    if only_missing_format {
        count_query.push_str(
            " AND (sp.format_id IS NULL OR TRIM(IFNULL(sp.format_name, '')) = '') ",
        );
    } else if let Some(fid) = format_id {
        count_query.push_str(" AND sp.format_id = ? ");
        count_int_params.push(fid);
    }
    if let Some(ag_id) = award_group_id {
        append_award_group_sql_filter(conn, &mut count_query, &mut count_int_params, ag_id)?;
    }
    let mut count_course_params: Vec<String> = Vec::new();
    append_course_name_filter(
        &mut count_query,
        &mut count_course_params,
        course_name.as_deref(),
    );
    let mut count_stmt = conn
        .prepare(&count_query)
        .map_err(|e| format!("prepare query results count: {e}"))?;
    let mut count_bind_values: Vec<rusqlite::types::Value> = count_params_dyn
        .into_iter()
        .map(rusqlite::types::Value::Text)
        .collect();
    for v in count_int_params {
        count_bind_values.push(rusqlite::types::Value::Integer(v));
    }
    for v in count_course_params {
        count_bind_values.push(rusqlite::types::Value::Text(v));
    }
    let total_count: i64 = count_stmt
        .query_row(rusqlite::params_from_iter(count_bind_values), |r| r.get(0))
        .map_err(|e| format!("query results count: {e}"))?;

    Ok((out, total_count))
}

pub fn query_status_counts(
    conn: &Connection,
    format_id: Option<i64>,
    only_missing_format: bool,
    award_group_id: Option<i64>,
    course_name: Option<String>,
) -> Result<BTreeMap<String, i64>, String> {
    let mut out = BTreeMap::from([
        (STATUS_OK.to_string(), 0_i64),
        (STATUS_DQ.to_string(), 0_i64),
        (STATUS_ERR.to_string(), 0_i64),
        (STATUS_DNS.to_string(), 0_i64),
        (STATUS_NOT_IN_PROTOCOL.to_string(), 0_i64),
    ]);
    let mut query = String::from(
        r#"
        SELECT results.status, COUNT(*) as cnt
        FROM results
        LEFT JOIN start_protocol sp
          ON sp.participant_id = results.participant_id
         AND sp.name = results.name
        LEFT JOIN participants p
          ON p.id = results.finish_participant_id
        WHERE 1=1
        "#,
    );
    let mut bind_values: Vec<rusqlite::types::Value> = Vec::new();
    let mut int_params: Vec<i64> = Vec::new();
    if only_missing_format {
        query.push_str(
            " AND (sp.format_id IS NULL OR TRIM(IFNULL(sp.format_name, '')) = '') ",
        );
    } else if let Some(fid) = format_id {
        query.push_str(" AND sp.format_id = ? ");
        int_params.push(fid);
    }
    if let Some(ag_id) = award_group_id {
        append_award_group_sql_filter(conn, &mut query, &mut int_params, ag_id)?;
    }
    let mut course_params: Vec<String> = Vec::new();
    append_course_name_filter(&mut query, &mut course_params, course_name.as_deref());
    for v in int_params {
        bind_values.push(rusqlite::types::Value::Integer(v));
    }
    for v in course_params {
        bind_values.push(rusqlite::types::Value::Text(v));
    }
    query.push_str(" GROUP BY results.status ");
    let mut stmt = conn
        .prepare(&query)
        .map_err(|e| format!("prepare status counts: {e}"))?;
    let rows = stmt
        .query_map(rusqlite::params_from_iter(bind_values), |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?))
        })
        .map_err(|e| format!("query status counts: {e}"))?;
    for row in rows {
        let (status, cnt) = row.map_err(|e| format!("read status count row: {e}"))?;
        out.insert(status, cnt);
    }
    Ok(out)
}

pub fn query_error_list(conn: &Connection) -> Result<Vec<ErrorRow>, String> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT id, participant_id, name, diagnostics_json
            FROM results
            WHERE status = ?
            ORDER BY participant_id COLLATE NOCASE ASC, name COLLATE NOCASE ASC, id ASC
            "#,
        )
        .map_err(|e| format!("prepare error list: {e}"))?;
    let rows = stmt
        .query_map(params![STATUS_ERR], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, String>(3)?,
            ))
        })
        .map_err(|e| format!("query error list: {e}"))?;
    let mut out = Vec::new();
    for row in rows {
        let (result_id, participant_id, name, diagnostics_json) =
            row.map_err(|e| format!("read error row: {e}"))?;
        let diagnostics: Vec<String> =
            serde_json::from_str(&diagnostics_json).unwrap_or_default();
        let description = describe_result_error(&participant_id, &name, &diagnostics);
        out.push(ErrorRow {
            result_id,
            participant_id,
            name,
            description,
            diagnostics_json,
        });
    }
    Ok(out)
}

pub fn scan_anomalies(conn: &Connection) -> Result<AnomalyScanSummary, String> {
    let settings = get_settings(conn)?;
    let orient = settings.sport_kind == "orient";
    let date_ok = !settings.competition_date.trim().is_empty()
        && NaiveDate::parse_from_str(settings.competition_date.trim(), "%Y-%m-%d").is_ok();
    if !orient && settings.competition_date.trim().is_empty() {
        return Err(
            "Для поиска аномалии сдвига на сутки заполните 'Дата соревнования' в настройках."
                .to_string(),
        );
    }
    if !orient && !date_ok {
        return Err("Некорректная дата соревнования. Ожидается формат YYYY-MM-DD.".to_string());
    }

    conn.execute("DELETE FROM participant_anomalies", [])
        .map_err(|e| format!("clear participant anomalies: {e}"))?;

    let participants_checked: i64 = conn
        .query_row("SELECT COUNT(*) FROM participants", [], |r| r.get(0))
        .map_err(|e| format!("count participants for anomaly scan: {e}"))?;

    if date_ok {
        let mut stmt = conn
            .prepare("SELECT id, start_time FROM participants")
            .map_err(|e| format!("prepare participants anomaly scan: {e}"))?;
        let rows = stmt
            .query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?)))
            .map_err(|e| format!("query participants anomaly scan: {e}"))?;
        for row in rows {
            let (finish_id, start_time) =
                row.map_err(|e| format!("read participants anomaly scan row: {e}"))?;
            for anomaly in detect_anomalies_for_start_time(&start_time, &settings) {
                insert_participant_anomaly(conn, finish_id, &anomaly)?;
            }
        }
    }

    if orient {
        insert_orient_start_punch_anomalies(conn, &settings)?;
    }

    let potential_anomalies = detect_potential_event_anomalies(conn, &settings)?;
    conn.execute("DELETE FROM event_anomalies", [])
        .map_err(|e| format!("clear event anomalies: {e}"))?;
    for anomaly in &potential_anomalies {
        conn.execute(
            r#"
            INSERT INTO event_anomalies(anomaly_type, title, details, payload_json)
            VALUES(?, ?, ?, ?)
            "#,
            params![
                anomaly.anomaly_type,
                anomaly.title,
                anomaly.details,
                anomaly.payload.to_string()
            ],
        )
        .map_err(|e| format!("insert event anomaly: {e}"))?;
    }

    let mut by_type: BTreeMap<String, i64> = BTreeMap::new();
    {
        let mut stmt = conn
            .prepare(
                "SELECT anomaly_type, COUNT(*) FROM participant_anomalies GROUP BY anomaly_type",
            )
            .map_err(|e| format!("prepare anomaly type counts: {e}"))?;
        let rows = stmt
            .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)))
            .map_err(|e| format!("query anomaly type counts: {e}"))?;
        for row in rows {
            let (kind, n) = row.map_err(|e| format!("read anomaly type count: {e}"))?;
            *by_type.entry(kind).or_insert(0) += n;
        }
    }
    for anomaly in &potential_anomalies {
        *by_type.entry(anomaly.anomaly_type.clone()).or_insert(0) += 1;
    }
    let participants_with_anomalies: i64 = conn
        .query_row(
            "SELECT COUNT(DISTINCT finish_participant_id) FROM participant_anomalies",
            [],
            |r| r.get(0),
        )
        .map_err(|e| format!("count participants with anomalies: {e}"))?;
    let anomalies_total: i64 = conn
        .query_row("SELECT COUNT(*) FROM participant_anomalies", [], |r| r.get(0))
        .map_err(|e| format!("count participant anomalies: {e}"))?;

    Ok(AnomalyScanSummary {
        participants_checked,
        participants_with_anomalies,
        anomalies_total,
        by_type,
        potential_anomalies,
    })
}

fn insert_participant_anomaly(
    conn: &Connection,
    finish_id: i64,
    anomaly: &ParticipantAnomaly,
) -> Result<(), String> {
    conn.execute(
        r#"
        INSERT INTO participant_anomalies(
            finish_participant_id, anomaly_type, title, details, payload_json
        )
        VALUES(?, ?, ?, ?, ?)
        "#,
        params![
            finish_id,
            anomaly.anomaly_type,
            anomaly.title,
            anomaly.details,
            anomaly.payload.to_string()
        ],
    )
    .map_err(|e| format!("insert participant anomaly: {e}"))?;
    Ok(())
}

pub fn query_anomaly_list(conn: &Connection) -> Result<AnomalyListResponse, String> {
    let mut items = Vec::new();

    let mut event_stmt = conn
        .prepare(
            r#"
            SELECT id, anomaly_type, title, details
            FROM event_anomalies
            ORDER BY id ASC
            "#,
        )
        .map_err(|e| format!("prepare event anomalies list: {e}"))?;
    let event_rows = event_stmt
        .query_map([], |r| {
            Ok(AnomalyListItem {
                id: r.get(0)?,
                scope: "event".to_string(),
                anomaly_type: r.get(1)?,
                title: r.get(2)?,
                details: r.get(3)?,
                is_potential: true,
                resolved: false,
                participant_id: None,
                name: None,
                result_id: None,
            })
        })
        .map_err(|e| format!("query event anomalies list: {e}"))?;
    for row in event_rows {
        items.push(row.map_err(|e| format!("read event anomaly row: {e}"))?);
    }

    let day_shift_total: i64 = conn
        .query_row(
            r#"
            SELECT COUNT(*)
            FROM participant_anomalies
            WHERE anomaly_type = 'start_time_day_shift'
            "#,
            [],
            |r| r.get(0),
        )
        .map_err(|e| format!("count day-shift anomalies: {e}"))?;
    let day_shift_resolved: i64 = conn
        .query_row(
            r#"
            SELECT COUNT(*)
            FROM participant_anomalies pa
            WHERE pa.anomaly_type = 'start_time_day_shift'
              AND EXISTS (
                SELECT 1
                FROM manual_corrections mc
                WHERE mc.finish_participant_id = pa.finish_participant_id
                  AND mc.correction_type = 'anomaly_day_shift_24h'
              )
            "#,
            [],
            |r| r.get(0),
        )
        .map_err(|e| format!("count resolved day-shift anomalies: {e}"))?;

    let mut other_stmt = conn
        .prepare(
            r#"
            SELECT
                pa.id,
                pa.anomaly_type,
                pa.title,
                pa.details,
                p.participant_id,
                p.name,
                (
                    SELECT r.id
                    FROM results r
                    WHERE r.finish_participant_id = p.id
                    ORDER BY r.id DESC
                    LIMIT 1
                ) AS result_id,
                EXISTS (
                    SELECT 1
                    FROM manual_corrections mc
                    WHERE mc.finish_participant_id = p.id
                      AND mc.correction_type = 'set_start_mark'
                      AND pa.anomaly_type IN ('start_punch_missing', 'start_punch_after_finish')
                ) AS resolved
            FROM participant_anomalies pa
            JOIN participants p ON p.id = pa.finish_participant_id
            WHERE pa.anomaly_type != 'start_time_day_shift'
            ORDER BY p.name COLLATE NOCASE ASC, pa.id ASC
            "#,
        )
        .map_err(|e| format!("prepare other participant anomalies list: {e}"))?;
    let other_rows = other_stmt
        .query_map([], |r| {
            Ok(AnomalyListItem {
                id: r.get(0)?,
                scope: "participant".to_string(),
                anomaly_type: r.get(1)?,
                title: r.get(2)?,
                details: r.get(3)?,
                is_potential: false,
                resolved: r.get::<_, i64>(7)? > 0,
                participant_id: r.get(4)?,
                name: r.get(5)?,
                result_id: r.get(6)?,
            })
        })
        .map_err(|e| format!("query other participant anomalies list: {e}"))?;
    for row in other_rows {
        items.push(row.map_err(|e| format!("read other participant anomaly row: {e}"))?);
    }

    Ok(AnomalyListResponse {
        items,
        day_shift_total,
        day_shift_resolved,
    })
}

fn detect_potential_event_anomalies(
    conn: &Connection,
    settings: &Settings,
) -> Result<Vec<PotentialAnomaly>, String> {
    let mut out = Vec::new();
    if settings.sport_kind == "orient" {
        return Ok(out);
    }

    let unused_cps = find_unused_legend_cps(conn, settings)?;
    if !unused_cps.is_empty() {
        let cps_text = unused_cps
            .iter()
            .map(|cp| cp.to_string())
            .collect::<Vec<_>>()
            .join(", ");
        out.push(PotentialAnomaly {
            anomaly_type: "unused_cp_never_taken".to_string(),
            title: "КП из легенды без отметок".to_string(),
            details: format!(
                "Ни один участник не взял КП из легенды: {}. Возможна ошибка в легенде, на карте или в настройках трассы.",
                cps_text
            ),
            payload: json!({
                "cp_numbers": unused_cps,
                "count": unused_cps.len()
            }),
        });
    }

    Ok(out)
}

fn find_unused_legend_cps(conn: &Connection, settings: &Settings) -> Result<Vec<i64>, String> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT l.cp_number
            FROM cp_legends l
            WHERE NOT EXISTS (
                SELECT 1 FROM marks_raw m WHERE m.cp_number = l.cp_number
            )
            ORDER BY l.cp_number ASC
            "#,
        )
        .map_err(|e| format!("prepare unused legend cps: {e}"))?;
    let rows = stmt
        .query_map([], |r| r.get::<_, i64>(0))
        .map_err(|e| format!("query unused legend cps: {e}"))?;

    let mut out = Vec::new();
    for row in rows {
        let cp = row.map_err(|e| format!("read unused legend cp: {e}"))?;
        if Some(cp) == settings.start_cp || cp == settings.finish_cp {
            continue;
        }
        out.push(cp);
    }
    Ok(out)
}

pub fn query_participants(conn: &Connection, limit: i64) -> Result<Vec<ParticipantRow>, String> {
    let safe_limit = limit.clamp(1, 2000);
    let mut stmt = conn
        .prepare(
            r#"
            SELECT participant_id, name
            FROM participants
            ORDER BY name
            LIMIT ?
            "#,
        )
        .map_err(|e| format!("prepare participants list query: {e}"))?;
    let rows = stmt
        .query_map(params![safe_limit], |r| {
            Ok(ParticipantRow {
                participant_id: r.get(0)?,
                name: r.get(1)?,
            })
        })
        .map_err(|e| format!("query participants list: {e}"))?;
    let mut out = Vec::new();
    for row in rows {
        out.push(row.map_err(|e| format!("read participants list row: {e}"))?);
    }
    Ok(out)
}

fn suggest_from_table(
    conn: &Connection,
    table: &str,
    id_col: &str,
    name_col: &str,
    query: &str,
    limit: i64,
) -> Result<Vec<SearchSuggestion>, String> {
    let normalized = normalize_search_text(query);
    if normalized.is_empty() {
        return Ok(Vec::new());
    }
    let safe_limit = limit.clamp(1, 50);
    let wildcard = format!("%{normalized}%");
    let prefix = format!("{normalized}%");
    let sql = format!(
        r#"
        SELECT DISTINCT {id_col}, {name_col}
        FROM {table}
        WHERE u_lower({id_col}) LIKE ? OR u_lower({name_col}) LIKE ?
        ORDER BY
            CASE
                WHEN u_lower({id_col}) LIKE ? THEN 0
                WHEN u_lower({name_col}) LIKE ? THEN 1
                ELSE 2
            END,
            {id_col} COLLATE NOCASE ASC,
            {name_col} COLLATE NOCASE ASC
        LIMIT ?
        "#
    );
    let mut stmt = conn
        .prepare(&sql)
        .map_err(|e| format!("prepare search suggestions ({table}): {e}"))?;
    let rows = stmt
        .query_map(
            params![wildcard, wildcard, prefix, prefix, safe_limit],
            |r| {
                Ok(SearchSuggestion {
                    participant_id: r.get(0)?,
                    name: r.get(1)?,
                })
            },
        )
        .map_err(|e| format!("query search suggestions ({table}): {e}"))?;
    let mut out = Vec::new();
    for row in rows {
        out.push(row.map_err(|e| format!("read search suggestion ({table}): {e}"))?);
    }
    Ok(out)
}

pub fn suggest_results_search(
    conn: &Connection,
    query: &str,
    limit: i64,
) -> Result<Vec<SearchSuggestion>, String> {
    suggest_from_table(conn, "results", "participant_id", "name", query, limit)
}

pub fn suggest_start_protocol_search(
    conn: &Connection,
    query: &str,
    limit: i64,
) -> Result<Vec<SearchSuggestion>, String> {
    suggest_from_table(conn, "start_protocol", "participant_id", "name", query, limit)
}

pub fn query_start_protocol(
    conn: &Connection,
    limit: i64,
    offset: i64,
    search: Option<String>,
    format_id: Option<i64>,
    only_incomplete: bool,
    only_missing_format: bool,
) -> Result<(Vec<StartProtocolRow>, i64), String> {
    let safe_limit = limit.clamp(1, 2000);
    let safe_offset = offset.max(0);
    let missing_format_expr = r#"
        (
            sp.format_id IS NULL
            OR TRIM(IFNULL(sp.format_name, '')) = ''
        )
    "#;
    let incomplete_expr = format!(
        r#"
        (
            TRIM(IFNULL(sp.name, '')) = ''
            OR {missing_format_expr}
            OR TRIM(IFNULL(sp.gender, '')) = ''
            OR (
                TRIM(IFNULL(sp.birth_date_raw, '')) = ''
                AND TRIM(IFNULL(sp.birth_date_iso, '')) = ''
            )
        )
        "#
    );
    let mut query = format!(
        r#"
        SELECT
            {START_PROTOCOL_ROW_SELECT},
            {incomplete_expr} as is_incomplete,
            {missing_format_expr} as missing_format
        FROM start_protocol sp
        WHERE 1=1
        "#
    );
    let mut params_dyn: Vec<rusqlite::types::Value> = Vec::new();
    if let Some(ref s) = search {
        if let Some(wildcard) = search_like_pattern(s) {
            query.push_str(
                " AND (u_lower(sp.participant_id) LIKE ? OR u_lower(sp.name) LIKE ?) ",
            );
            params_dyn.push(rusqlite::types::Value::Text(wildcard.clone()));
            params_dyn.push(rusqlite::types::Value::Text(wildcard));
        }
    }
    if let Some(fid) = format_id {
        query.push_str(" AND sp.format_id = ? ");
        params_dyn.push(rusqlite::types::Value::Integer(fid));
    }
    if only_incomplete {
        query.push_str(" AND ");
        query.push_str(&incomplete_expr);
    }
    if only_missing_format {
        query.push_str(" AND ");
        query.push_str(missing_format_expr);
    }
    query.push_str(
        " ORDER BY sp.format_name, CASE WHEN sp.team_id IS NULL THEN 1 ELSE 0 END, sp.team_id, sp.participant_id COLLATE NOCASE ASC LIMIT ? OFFSET ? ",
    );
    params_dyn.push(rusqlite::types::Value::Integer(safe_limit));
    params_dyn.push(rusqlite::types::Value::Integer(safe_offset));

    let mut stmt = conn
        .prepare(&query)
        .map_err(|e| format!("prepare start protocol query: {e}"))?;
    let rows = stmt
        .query_map(rusqlite::params_from_iter(params_dyn), |r| {
            map_start_protocol_row(r, 13, 14)
        })
        .map_err(|e| format!("query start protocol: {e}"))?;

    let mut out = Vec::new();
    for row in rows {
        out.push(row.map_err(|e| format!("read start protocol row: {e}"))?);
    }

    let mut count_query = String::from("SELECT COUNT(*) FROM start_protocol sp WHERE 1=1 ");
    let mut count_params: Vec<rusqlite::types::Value> = Vec::new();
    if let Some(ref s) = search {
        if let Some(wildcard) = search_like_pattern(s) {
            count_query.push_str(
                " AND (u_lower(sp.participant_id) LIKE ? OR u_lower(sp.name) LIKE ?) ",
            );
            count_params.push(rusqlite::types::Value::Text(wildcard.clone()));
            count_params.push(rusqlite::types::Value::Text(wildcard));
        }
    }
    if let Some(fid) = format_id {
        count_query.push_str(" AND sp.format_id = ? ");
        count_params.push(rusqlite::types::Value::Integer(fid));
    }
    if only_incomplete {
        count_query.push_str(" AND ");
        count_query.push_str(&incomplete_expr);
    }
    if only_missing_format {
        count_query.push_str(" AND ");
        count_query.push_str(missing_format_expr);
    }
    let mut count_stmt = conn
        .prepare(&count_query)
        .map_err(|e| format!("prepare start protocol count query: {e}"))?;
    let total_count: i64 = count_stmt
        .query_row(rusqlite::params_from_iter(count_params), |r| r.get(0))
        .map_err(|e| format!("query start protocol count: {e}"))?;

    Ok((out, total_count))
}

pub fn query_start_protocol_formats(conn: &Connection) -> Result<Vec<String>, String> {
    let rows = query_start_protocol_format_rows(conn)?;
    Ok(rows.into_iter().map(|r| r.format_name).collect())
}

pub fn query_start_protocol_format_rows(
    conn: &Connection,
) -> Result<Vec<StartProtocolFormatRow>, String> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT
                f.id,
                f.format_name,
                COALESCE((
                    SELECT COUNT(*)
                    FROM start_protocol sp
                    WHERE sp.format_id = f.id
                ), 0) AS usage_count
            FROM start_protocol_formats f
            ORDER BY f.format_name COLLATE NOCASE ASC
            "#,
        )
        .map_err(|e| format!("prepare start protocol formats query: {e}"))?;
    let rows = stmt
        .query_map([], |r| {
            Ok(StartProtocolFormatRow {
                id: r.get(0)?,
                format_name: r.get(1)?,
                usage_count: r.get(2)?,
            })
        })
        .map_err(|e| format!("query start protocol formats: {e}"))?;
    let mut out = Vec::new();
    for row in rows {
        out.push(row.map_err(|e| format!("read start protocol format: {e}"))?);
    }
    Ok(out)
}

pub fn add_start_protocol_format(
    conn: &Connection,
    format_name: String,
) -> Result<Vec<StartProtocolFormatRow>, String> {
    let name = format_name.trim().to_string();
    if name.is_empty() {
        return Err("Имя формата не может быть пустым".to_string());
    }
    let exists = conn
        .query_row(
            "SELECT 1 FROM start_protocol_formats WHERE format_name = ? COLLATE NOCASE LIMIT 1",
            params![name],
            |_| Ok(()),
        )
        .optional()
        .map_err(|e| format!("check format exists: {e}"))?;
    if exists.is_some() {
        return Err(format!("Формат «{name}» уже существует"));
    }
    conn.execute(
        "INSERT INTO start_protocol_formats(format_name) VALUES(?)",
        params![name],
    )
    .map_err(|e| format!("insert format: {e}"))?;
    query_start_protocol_format_rows(conn)
}

pub fn rename_start_protocol_format(
    conn: &Connection,
    format_id: i64,
    new_format_name: String,
) -> Result<Vec<StartProtocolFormatRow>, String> {
    let new_name = new_format_name.trim().to_string();
    if new_name.is_empty() {
        return Err("Имя формата не может быть пустым".to_string());
    }
    let old_name: String = conn
        .query_row(
            "SELECT format_name FROM start_protocol_formats WHERE id = ? LIMIT 1",
            params![format_id],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("check format exists: {e}"))?
        .ok_or_else(|| format!("Формат id={format_id} не найден"))?;
    if old_name == new_name {
        return query_start_protocol_format_rows(conn);
    }

    let conflict = conn
        .query_row(
            "SELECT 1 FROM start_protocol_formats WHERE format_name = ? COLLATE NOCASE AND id <> ? LIMIT 1",
            params![new_name, format_id],
            |_| Ok(()),
        )
        .optional()
        .map_err(|e| format!("check format rename conflict: {e}"))?;
    if conflict.is_some() {
        return Err(format!("Формат «{new_name}» уже существует"));
    }

    let tx = conn
        .unchecked_transaction()
        .map_err(|e| format!("start format rename transaction: {e}"))?;
    tx.execute(
        "UPDATE start_protocol_formats SET format_name = ? WHERE id = ?",
        params![new_name, format_id],
    )
    .map_err(|e| format!("rename format in dictionary: {e}"))?;
    tx.execute(
        "UPDATE start_protocol SET format_name = ? WHERE format_id = ?",
        params![new_name, format_id],
    )
    .map_err(|e| format!("rename format in protocol: {e}"))?;
    tx.commit()
        .map_err(|e| format!("commit format rename: {e}"))?;
    query_start_protocol_format_rows(conn)
}

pub fn delete_start_protocol_format(
    conn: &Connection,
    format_id: i64,
) -> Result<Vec<StartProtocolFormatRow>, String> {
    let name: String = conn
        .query_row(
            "SELECT format_name FROM start_protocol_formats WHERE id = ? LIMIT 1",
            params![format_id],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("load format for delete: {e}"))?
        .ok_or_else(|| format!("Формат id={format_id} не найден"))?;
    let usage: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM start_protocol WHERE format_id = ?",
            params![format_id],
            |r| r.get(0),
        )
        .map_err(|e| format!("count format usage: {e}"))?;
    if usage > 0 {
        return Err(format!(
            "Нельзя удалить формат «{name}»: есть зарегистрированные участники ({usage})"
        ));
    }
    conn.execute(
        "DELETE FROM award_group_formats WHERE format_id = ?",
        params![format_id],
    )
    .map_err(|e| format!("delete award links for format: {e}"))?;
    conn.execute(
        "DELETE FROM format_cp_type_rules WHERE format_id = ?",
        params![format_id],
    )
    .map_err(|e| format!("delete format cp type rules: {e}"))?;
    conn.execute(
        "DELETE FROM format_settings WHERE format_id = ?",
        params![format_id],
    )
    .map_err(|e| format!("delete format settings: {e}"))?;
    conn.execute(
        "DELETE FROM leg_exclusion_rules WHERE format_id = ?",
        params![format_id],
    )
    .map_err(|e| format!("delete format exclusions: {e}"))?;
    conn.execute(
        "DELETE FROM start_protocol_formats WHERE id = ?",
        params![format_id],
    )
    .map_err(|e| format!("delete format: {e}"))?;
    query_start_protocol_format_rows(conn)
}

fn sql_col_is_male(col: &str) -> String {
    format!(
        "(u_lower(IFNULL({col}, '')) LIKE '%муж%' OR u_lower(IFNULL({col}, '')) IN ('m', 'male', 'м'))"
    )
}

fn sql_col_is_female(col: &str) -> String {
    format!(
        "(u_lower(IFNULL({col}, '')) LIKE '%жен%' OR u_lower(IFNULL({col}, '')) IN ('f', 'female', 'ж'))"
    )
}

fn append_award_group_sql_filter(
    conn: &Connection,
    query: &mut String,
    int_params: &mut Vec<i64>,
    award_group_id: i64,
) -> Result<(), String> {
    let (gender_mode_raw, min_age_raw): (String, Option<i64>) = conn
        .query_row(
            "SELECT gender_mode, min_age FROM award_groups WHERE id = ? LIMIT 1",
            params![award_group_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()
        .map_err(|e| format!("load award group: {e}"))?
        .ok_or_else(|| format!("Группа награждения id={award_group_id} не найдена"))?;
    let mode = normalize_award_gender_mode(&gender_mode_raw)?;
    let min_age = normalize_award_min_age(min_age_raw)?;

    query.push_str(
        " AND sp.format_id IN (SELECT format_id FROM award_group_formats WHERE award_group_id = ?) ",
    );
    int_params.push(award_group_id);

    let male_sp = sql_col_is_male("sp.gender");
    let female_sp = sql_col_is_female("sp.gender");
    let male_tm = sql_col_is_male("tm.gender");
    let female_tm = sql_col_is_female("tm.gender");

    match mode.as_str() {
        AWARD_GENDER_MALE => {
            query.push_str(&format!(
                r#" AND (
                    (sp.team_id IS NULL AND {male_sp})
                    OR (
                        sp.team_id IS NOT NULL
                        AND EXISTS (
                            SELECT 1 FROM start_protocol tm
                            WHERE tm.team_id = sp.team_id AND {male_tm}
                        )
                        AND NOT EXISTS (
                            SELECT 1 FROM start_protocol tm
                            WHERE tm.team_id = sp.team_id AND {female_tm}
                        )
                    )
                ) "#
            ));
        }
        AWARD_GENDER_FEMALE => {
            query.push_str(&format!(
                r#" AND (
                    (sp.team_id IS NULL AND {female_sp})
                    OR (
                        sp.team_id IS NOT NULL
                        AND EXISTS (
                            SELECT 1 FROM start_protocol tm
                            WHERE tm.team_id = sp.team_id AND {female_tm}
                        )
                        AND NOT EXISTS (
                            SELECT 1 FROM start_protocol tm
                            WHERE tm.team_id = sp.team_id AND {male_tm}
                        )
                    )
                ) "#
            ));
        }
        AWARD_GENDER_MIXED => {
            query.push_str(&format!(
                r#" AND sp.team_id IS NOT NULL
                    AND EXISTS (
                        SELECT 1 FROM start_protocol tm
                        WHERE tm.team_id = sp.team_id AND {male_tm}
                    )
                    AND EXISTS (
                        SELECT 1 FROM start_protocol tm
                        WHERE tm.team_id = sp.team_id AND {female_tm}
                    ) "#
            ));
        }
        _ => {}
    }

    if let Some(age) = min_age {
        if let Some(year) = competition_year(conn) {
            let age_expr = sql_award_age(year);
            query.push_str(&format!(
                " AND {age_expr} IS NOT NULL AND {age_expr} >= ? "
            ));
            int_params.push(age);
        } else {
            query.push_str(" AND 1=0 ");
        }
    }
    Ok(())
}

fn load_award_group_row(conn: &Connection, group_id: i64) -> Result<AwardGroupRow, String> {
    let (id, name, gender_mode, min_age, sort_order): (i64, String, String, Option<i64>, i64) = conn
        .query_row(
            "SELECT id, name, gender_mode, min_age, sort_order FROM award_groups WHERE id = ? LIMIT 1",
            params![group_id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
        )
        .map_err(|e| format!("load award group row: {e}"))?;
    let mut stmt = conn
        .prepare(
            r#"
            SELECT f.id, f.format_name
            FROM award_group_formats agf
            JOIN start_protocol_formats f ON f.id = agf.format_id
            WHERE agf.award_group_id = ?
            ORDER BY f.format_name COLLATE NOCASE ASC
            "#,
        )
        .map_err(|e| format!("prepare award group formats: {e}"))?;
    let rows = stmt
        .query_map(params![group_id], |r| {
            Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?))
        })
        .map_err(|e| format!("query award group formats: {e}"))?;
    let mut format_ids = Vec::new();
    let mut format_names = Vec::new();
    for row in rows {
        let (fid, fname) = row.map_err(|e| format!("read award group format: {e}"))?;
        format_ids.push(fid);
        format_names.push(fname);
    }
    Ok(AwardGroupRow {
        id,
        name,
        gender_mode,
        min_age: normalize_award_min_age(min_age)?,
        sort_order,
        format_ids,
        format_names,
    })
}

pub fn query_award_groups(conn: &Connection) -> Result<Vec<AwardGroupRow>, String> {
    let mut stmt = conn
        .prepare(
            "SELECT id FROM award_groups ORDER BY sort_order ASC, name COLLATE NOCASE ASC",
        )
        .map_err(|e| format!("prepare award groups: {e}"))?;
    let ids = stmt
        .query_map([], |r| r.get::<_, i64>(0))
        .map_err(|e| format!("query award group ids: {e}"))?;
    let mut out = Vec::new();
    for id in ids {
        let id = id.map_err(|e| format!("read award group id: {e}"))?;
        out.push(load_award_group_row(conn, id)?);
    }
    Ok(out)
}

pub fn upsert_award_group(
    conn: &Connection,
    group_id: Option<i64>,
    name: String,
    gender_mode: String,
    format_ids: Vec<i64>,
    min_age: Option<i64>,
    sort_order: Option<i64>,
) -> Result<AwardGroupRow, String> {
    let name = name.trim().to_string();
    if name.is_empty() {
        return Err("Название группы награждения обязательно".to_string());
    }
    let mode = normalize_award_gender_mode(&gender_mode)?;
    let min_age = normalize_award_min_age(min_age)?;
    let mut unique_formats: Vec<i64> = format_ids
        .into_iter()
        .filter(|id| *id > 0)
        .collect::<HashSet<_>>()
        .into_iter()
        .collect();
    unique_formats.sort_unstable();
    if unique_formats.is_empty() {
        return Err("Выберите хотя бы один формат".to_string());
    }
    for fid in &unique_formats {
        let exists = conn
            .query_row(
                "SELECT 1 FROM start_protocol_formats WHERE id = ? LIMIT 1",
                params![fid],
                |_| Ok(()),
            )
            .optional()
            .map_err(|e| format!("check format for award group: {e}"))?;
        if exists.is_none() {
            return Err(format!("Формат id={fid} не найден"));
        }
    }

    let id = if let Some(existing_id) = group_id.filter(|v| *v > 0) {
        let order = sort_order.unwrap_or_else(|| {
            conn.query_row(
                "SELECT sort_order FROM award_groups WHERE id = ?",
                params![existing_id],
                |r| r.get::<_, i64>(0),
            )
            .unwrap_or(0)
        });
        conn.execute(
            "UPDATE award_groups SET name = ?, gender_mode = ?, min_age = ?, sort_order = ? WHERE id = ?",
            params![name, mode, min_age, order, existing_id],
        )
        .map_err(|e| {
            let msg = e.to_string();
            if msg.contains("UNIQUE") {
                format!("Группа с именем «{name}» уже существует")
            } else {
                format!("update award group: {e}")
            }
        })?;
        if conn.changes() == 0 {
            return Err(format!("Группа награждения id={existing_id} не найдена"));
        }
        conn.execute(
            "DELETE FROM award_group_formats WHERE award_group_id = ?",
            params![existing_id],
        )
        .map_err(|e| format!("clear award group formats: {e}"))?;
        existing_id
    } else {
        let order = if let Some(o) = sort_order {
            o
        } else {
            conn.query_row(
                "SELECT COALESCE(MAX(sort_order), 0) + 1 FROM award_groups",
                [],
                |r| r.get(0),
            )
            .map_err(|e| format!("allocate award sort_order: {e}"))?
        };
        conn.execute(
            "INSERT INTO award_groups(name, gender_mode, min_age, sort_order) VALUES(?, ?, ?, ?)",
            params![name, mode, min_age, order],
        )
        .map_err(|e| {
            let msg = e.to_string();
            if msg.contains("UNIQUE") {
                format!("Группа с именем «{name}» уже существует")
            } else {
                format!("insert award group: {e}")
            }
        })?;
        conn.last_insert_rowid()
    };

    for fid in unique_formats {
        conn.execute(
            "INSERT INTO award_group_formats(award_group_id, format_id) VALUES(?, ?)",
            params![id, fid],
        )
        .map_err(|e| format!("link award group format: {e}"))?;
    }
    load_award_group_row(conn, id)
}

pub fn delete_award_group(conn: &Connection, group_id: i64) -> Result<(), String> {
    let changed = conn
        .execute("DELETE FROM award_groups WHERE id = ?", params![group_id])
        .map_err(|e| format!("delete award group: {e}"))?;
    if changed == 0 {
        return Err(format!("Группа награждения id={group_id} не найдена"));
    }
    Ok(())
}

pub fn query_cp_legends(conn: &Connection) -> Result<Vec<CpLegendRow>, String> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT
                l.id,
                l.cp_number,
                l.name,
                l.cp_type_id,
                COALESCE(t.type_name, ''),
                l.map_x,
                l.map_y
            FROM cp_legends l
            LEFT JOIN cp_legend_types t ON t.id = l.cp_type_id
            ORDER BY l.cp_number ASC, l.id ASC
            "#,
        )
        .map_err(|e| format!("prepare cp_legends query: {e}"))?;
    let rows = stmt
        .query_map([], |r| {
            Ok(CpLegendRow {
                id: r.get(0)?,
                cp_number: r.get(1)?,
                name: r.get(2)?,
                cp_type_id: r.get(3)?,
                cp_type_name: r.get(4)?,
                map_x: r.get(5)?,
                map_y: r.get(6)?,
            })
        })
        .map_err(|e| format!("query cp_legends: {e}"))?;
    let mut out = Vec::new();
    for row in rows {
        out.push(row.map_err(|e| format!("read cp_legend row: {e}"))?);
    }
    Ok(out)
}

fn load_cp_legend_row(conn: &Connection, legend_id: i64) -> Result<CpLegendRow, String> {
    conn.query_row(
        r#"
        SELECT
            l.id,
            l.cp_number,
            l.name,
            l.cp_type_id,
            COALESCE(t.type_name, ''),
            l.map_x,
            l.map_y
        FROM cp_legends l
        LEFT JOIN cp_legend_types t ON t.id = l.cp_type_id
        WHERE l.id = ?
        "#,
        params![legend_id],
        |r| {
            Ok(CpLegendRow {
                id: r.get(0)?,
                cp_number: r.get(1)?,
                name: r.get(2)?,
                cp_type_id: r.get(3)?,
                cp_type_name: r.get(4)?,
                map_x: r.get(5)?,
                map_y: r.get(6)?,
            })
        },
    )
    .map_err(|e| format!("load cp_legend: {e}"))
}

fn parse_cp_number(raw: &str, row_label: &str) -> Result<i64, String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err(format!("{row_label}: номер КП не заполнен"));
    }
    trimmed.parse::<i64>().map_err(|_| {
        format!("{row_label}: номер КП «{trimmed}» должен быть целым числом")
    })
}

fn ensure_cp_type_id(conn: &Connection, type_name: &str) -> Result<i64, String> {
    let name = type_name.trim().to_string();
    if name.is_empty() {
        return Err("Имя типа КП не может быть пустым".to_string());
    }
    conn.execute(
        "INSERT OR IGNORE INTO cp_legend_types(type_name) VALUES(?)",
        params![name],
    )
    .map_err(|e| format!("insert cp_legend_type: {e}"))?;
    conn.query_row(
        "SELECT id FROM cp_legend_types WHERE type_name = ? COLLATE NOCASE LIMIT 1",
        params![name],
        |r| r.get(0),
    )
    .map_err(|e| format!("lookup cp_legend_type: {e}"))
}

fn ensure_cp_type_id_tx(tx: &Transaction<'_>, type_name: &str) -> Result<i64, String> {
    let name = type_name.trim().to_string();
    if name.is_empty() {
        return Err("Имя типа КП не может быть пустым".to_string());
    }
    tx.execute(
        "INSERT OR IGNORE INTO cp_legend_types(type_name) VALUES(?)",
        params![name],
    )
    .map_err(|e| format!("insert cp_legend_type: {e}"))?;
    tx.query_row(
        "SELECT id FROM cp_legend_types WHERE type_name = ? COLLATE NOCASE LIMIT 1",
        params![name],
        |r| r.get(0),
    )
    .map_err(|e| format!("lookup cp_legend_type: {e}"))
}

fn resolve_cp_type_id(conn: &Connection, cp_type_id: Option<i64>) -> Result<Option<i64>, String> {
    let Some(id) = cp_type_id else {
        return Ok(None);
    };
    if id <= 0 {
        return Ok(None);
    }
    let exists: Option<i64> = conn
        .query_row(
            "SELECT id FROM cp_legend_types WHERE id = ? LIMIT 1",
            params![id],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("check cp_legend_type: {e}"))?;
    if exists.is_none() {
        return Err(format!("Тип КП id={id} не найден"));
    }
    Ok(Some(id))
}

pub fn query_cp_legend_type_rows(conn: &Connection) -> Result<Vec<CpLegendTypeRow>, String> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT
                t.id,
                t.type_name,
                COALESCE((
                    SELECT COUNT(*)
                    FROM cp_legends l
                    WHERE l.cp_type_id = t.id
                ), 0)
                + COALESCE((
                    SELECT COUNT(*)
                    FROM format_cp_type_rules r
                    WHERE r.cp_type_id = t.id
                ), 0) AS usage_count
            FROM cp_legend_types t
            ORDER BY t.type_name COLLATE NOCASE ASC
            "#,
        )
        .map_err(|e| format!("prepare cp_legend_types query: {e}"))?;
    let rows = stmt
        .query_map([], |r| {
            Ok(CpLegendTypeRow {
                id: r.get(0)?,
                type_name: r.get(1)?,
                usage_count: r.get(2)?,
            })
        })
        .map_err(|e| format!("query cp_legend_types: {e}"))?;
    let mut out = Vec::new();
    for row in rows {
        out.push(row.map_err(|e| format!("read cp_legend_type: {e}"))?);
    }
    Ok(out)
}

pub fn add_cp_legend_type(
    conn: &Connection,
    type_name: String,
) -> Result<Vec<CpLegendTypeRow>, String> {
    let name = type_name.trim().to_string();
    if name.is_empty() {
        return Err("Имя типа КП не может быть пустым".to_string());
    }
    let exists = conn
        .query_row(
            "SELECT 1 FROM cp_legend_types WHERE type_name = ? COLLATE NOCASE LIMIT 1",
            params![name],
            |_| Ok(()),
        )
        .optional()
        .map_err(|e| format!("check cp type exists: {e}"))?;
    if exists.is_some() {
        return Err(format!("Тип КП «{name}» уже существует"));
    }
    conn.execute(
        "INSERT INTO cp_legend_types(type_name) VALUES(?)",
        params![name],
    )
    .map_err(|e| format!("insert cp type: {e}"))?;
    query_cp_legend_type_rows(conn)
}

pub fn rename_cp_legend_type(
    conn: &Connection,
    type_id: i64,
    new_type_name: String,
) -> Result<Vec<CpLegendTypeRow>, String> {
    let new_name = new_type_name.trim().to_string();
    if new_name.is_empty() {
        return Err("Имя типа КП не может быть пустым".to_string());
    }
    let old_name: String = conn
        .query_row(
            "SELECT type_name FROM cp_legend_types WHERE id = ? LIMIT 1",
            params![type_id],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("check cp type exists: {e}"))?
        .ok_or_else(|| format!("Тип КП id={type_id} не найден"))?;
    if old_name == new_name {
        return query_cp_legend_type_rows(conn);
    }
    let conflict = conn
        .query_row(
            "SELECT 1 FROM cp_legend_types WHERE type_name = ? COLLATE NOCASE AND id <> ? LIMIT 1",
            params![new_name, type_id],
            |_| Ok(()),
        )
        .optional()
        .map_err(|e| format!("check cp type rename conflict: {e}"))?;
    if conflict.is_some() {
        return Err(format!("Тип КП «{new_name}» уже существует"));
    }
    conn.execute(
        "UPDATE cp_legend_types SET type_name = ? WHERE id = ?",
        params![new_name, type_id],
    )
    .map_err(|e| format!("rename cp type: {e}"))?;
    // Keep legacy free-text column in sync if it still exists.
    if table_has_column(conn, "cp_legends", "cp_type")? {
        conn.execute(
            "UPDATE cp_legends SET cp_type = ? WHERE cp_type_id = ?",
            params![new_name, type_id],
        )
        .map_err(|e| format!("rename cp type in legends: {e}"))?;
    }
    query_cp_legend_type_rows(conn)
}

pub fn delete_cp_legend_type(
    conn: &Connection,
    type_id: i64,
) -> Result<Vec<CpLegendTypeRow>, String> {
    let name: String = conn
        .query_row(
            "SELECT type_name FROM cp_legend_types WHERE id = ? LIMIT 1",
            params![type_id],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("load cp type for delete: {e}"))?
        .ok_or_else(|| format!("Тип КП id={type_id} не найден"))?;
    let usage: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM cp_legends WHERE cp_type_id = ?",
            params![type_id],
            |r| r.get(0),
        )
        .map_err(|e| format!("count cp type usage: {e}"))?;
    if usage > 0 {
        return Err(format!(
            "Нельзя удалить тип «{name}»: используется в легендах ({usage})"
        ));
    }
    let rule_usage: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM format_cp_type_rules WHERE cp_type_id = ?",
            params![type_id],
            |r| r.get(0),
        )
        .map_err(|e| format!("count cp type rule usage: {e}"))?;
    if rule_usage > 0 {
        return Err(format!(
            "Нельзя удалить тип «{name}»: используется в правилах форматов ({rule_usage})"
        ));
    }
    conn.execute("DELETE FROM cp_legend_types WHERE id = ?", params![type_id])
        .map_err(|e| format!("delete cp type: {e}"))?;
    query_cp_legend_type_rows(conn)
}

pub fn upsert_cp_legend(
    conn: &Connection,
    legend_id: Option<i64>,
    cp_number: i64,
    name: String,
    cp_type_id: Option<i64>,
) -> Result<CpLegendRow, String> {
    let name = name.trim().to_string();
    if name.is_empty() {
        return Err("Название КП обязательно".to_string());
    }
    let cp_type_id = resolve_cp_type_id(conn, cp_type_id)?;
    let type_name = if let Some(tid) = cp_type_id {
        conn.query_row(
            "SELECT type_name FROM cp_legend_types WHERE id = ?",
            params![tid],
            |r| r.get::<_, String>(0),
        )
        .map_err(|e| format!("load type name: {e}"))?
    } else {
        String::new()
    };
    let has_legacy_type = table_has_column(conn, "cp_legends", "cp_type")?;

    let id = if let Some(id) = legend_id {
        let exists: Option<i64> = conn
            .query_row(
                "SELECT id FROM cp_legends WHERE id = ? LIMIT 1",
                params![id],
                |r| r.get(0),
            )
            .optional()
            .map_err(|e| format!("check cp_legend exists: {e}"))?;
        if exists.is_none() {
            return Err(format!("Легенда КП id={id} не найдена"));
        }
        if has_legacy_type {
            conn.execute(
                r#"
                UPDATE cp_legends
                SET cp_number = ?, name = ?, cp_type_id = ?, cp_type = ?
                WHERE id = ?
                "#,
                params![
                    cp_number,
                    name,
                    cp_type_id,
                    if type_name.is_empty() {
                        None::<String>
                    } else {
                        Some(type_name)
                    },
                    id
                ],
            )
        } else {
            conn.execute(
                r#"
                UPDATE cp_legends
                SET cp_number = ?, name = ?, cp_type_id = ?
                WHERE id = ?
                "#,
                params![cp_number, name, cp_type_id, id],
            )
        }
        .map_err(|e| {
            let msg = e.to_string();
            if msg.contains("UNIQUE") {
                format!("КП с номером {cp_number} уже существует")
            } else {
                format!("update cp_legend: {e}")
            }
        })?;
        id
    } else if has_legacy_type {
        conn.execute(
            r#"
            INSERT INTO cp_legends(cp_number, name, cp_type_id, cp_type)
            VALUES(?, ?, ?, ?)
            "#,
            params![
                cp_number,
                name,
                cp_type_id,
                if type_name.is_empty() {
                    None::<String>
                } else {
                    Some(type_name)
                }
            ],
        )
        .map_err(|e| {
            let msg = e.to_string();
            if msg.contains("UNIQUE") {
                format!("КП с номером {cp_number} уже существует")
            } else {
                format!("insert cp_legend: {e}")
            }
        })?;
        conn.last_insert_rowid()
    } else {
        conn.execute(
            r#"
            INSERT INTO cp_legends(cp_number, name, cp_type_id)
            VALUES(?, ?, ?)
            "#,
            params![cp_number, name, cp_type_id],
        )
        .map_err(|e| {
            let msg = e.to_string();
            if msg.contains("UNIQUE") {
                format!("КП с номером {cp_number} уже существует")
            } else {
                format!("insert cp_legend: {e}")
            }
        })?;
        conn.last_insert_rowid()
    };

    load_cp_legend_row(conn, id)
}

pub fn set_cp_legend_map_position(
    conn: &Connection,
    legend_id: i64,
    map_x: Option<f64>,
    map_y: Option<f64>,
) -> Result<CpLegendRow, String> {
    let exists: Option<i64> = conn
        .query_row(
            "SELECT id FROM cp_legends WHERE id = ? LIMIT 1",
            params![legend_id],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("check cp_legend exists: {e}"))?;
    if exists.is_none() {
        return Err(format!("Легенда КП id={legend_id} не найдена"));
    }

    let (x, y) = match (map_x, map_y) {
        (None, None) => (None, None),
        (Some(x), Some(y)) => {
            if !(0.0..=1.0).contains(&x) || !(0.0..=1.0).contains(&y) {
                return Err("Координаты КП на карте должны быть в диапазоне 0…1".to_string());
            }
            (Some(x), Some(y))
        }
        _ => {
            return Err(
                "Нужно указать обе координаты map_x/map_y или очистить обе (null)".to_string(),
            )
        }
    };

    conn.execute(
        "UPDATE cp_legends SET map_x = ?, map_y = ? WHERE id = ?",
        params![x, y, legend_id],
    )
    .map_err(|e| format!("update cp_legend map position: {e}"))?;
    load_cp_legend_row(conn, legend_id)
}

const MAP_START_X_KEY: &str = "map_start_x";
const MAP_START_Y_KEY: &str = "map_start_y";
const MAP_FINISH_X_KEY: &str = "map_finish_x";
const MAP_FINISH_Y_KEY: &str = "map_finish_y";

#[derive(Debug, Serialize, Clone)]
pub struct CourseMapSpecialPoints {
    pub start_cp: Option<i64>,
    pub finish_cp: i64,
    pub start_map_x: Option<f64>,
    pub start_map_y: Option<f64>,
    pub finish_map_x: Option<f64>,
    pub finish_map_y: Option<f64>,
}

fn parse_optional_norm_coord(raw: Option<String>) -> Option<f64> {
    let value = raw?.trim().to_string();
    if value.is_empty() {
        return None;
    }
    value.parse::<f64>().ok().filter(|v| (0.0..=1.0).contains(v))
}

pub fn get_course_map_special_points(conn: &Connection) -> Result<CourseMapSpecialPoints, String> {
    let settings = get_settings(conn)?;
    Ok(CourseMapSpecialPoints {
        start_cp: settings.start_cp,
        finish_cp: settings.finish_cp,
        start_map_x: parse_optional_norm_coord(get_setting_value(conn, MAP_START_X_KEY)?),
        start_map_y: parse_optional_norm_coord(get_setting_value(conn, MAP_START_Y_KEY)?),
        finish_map_x: parse_optional_norm_coord(get_setting_value(conn, MAP_FINISH_X_KEY)?),
        finish_map_y: parse_optional_norm_coord(get_setting_value(conn, MAP_FINISH_Y_KEY)?),
    })
}

pub fn set_course_map_special_position(
    conn: &Connection,
    point: String,
    map_x: Option<f64>,
    map_y: Option<f64>,
) -> Result<CourseMapSpecialPoints, String> {
    let kind = point.trim().to_ascii_lowercase();
    let (x_key, y_key) = match kind.as_str() {
        "start" => (MAP_START_X_KEY, MAP_START_Y_KEY),
        "finish" => (MAP_FINISH_X_KEY, MAP_FINISH_Y_KEY),
        _ => {
            return Err(
                "point must be \"start\" or \"finish\"".to_string(),
            )
        }
    };

    match (map_x, map_y) {
        (None, None) => {
            delete_setting_value(conn, x_key)?;
            delete_setting_value(conn, y_key)?;
        }
        (Some(x), Some(y)) => {
            if !(0.0..=1.0).contains(&x) || !(0.0..=1.0).contains(&y) {
                return Err("Координаты на карте должны быть в диапазоне 0…1".to_string());
            }
            set_setting_value(conn, x_key, &format!("{x}"))?;
            set_setting_value(conn, y_key, &format!("{y}"))?;
        }
        _ => {
            return Err(
                "Нужно указать обе координаты или очистить обе (null)".to_string(),
            )
        }
    }
    get_course_map_special_points(conn)
}

pub fn clear_all_course_map_positions(conn: &Connection) -> Result<(), String> {
    ensure_map_georef_schema(conn)?;
    conn.execute(
        "UPDATE cp_legends SET map_x = NULL, map_y = NULL WHERE map_x IS NOT NULL OR map_y IS NOT NULL",
        [],
    )
    .map_err(|e| format!("clear cp_legend map positions: {e}"))?;
    delete_setting_value(conn, MAP_START_X_KEY)?;
    delete_setting_value(conn, MAP_START_Y_KEY)?;
    delete_setting_value(conn, MAP_FINISH_X_KEY)?;
    delete_setting_value(conn, MAP_FINISH_Y_KEY)?;
    conn.execute("DELETE FROM map_gps_anchors", [])
        .map_err(|e| format!("clear map gps anchors: {e}"))?;
    delete_setting_value(conn, MAP_IMAGE_WIDTH_KEY)?;
    delete_setting_value(conn, MAP_IMAGE_HEIGHT_KEY)?;
    Ok(())
}

const MAP_SCALE_DENOM_KEY: &str = "map_scale_denominator";
/// Assumed scan resolution when converting printed map scale 1:N ↔ meters/pixel.
const MAP_SCAN_DPI: f64 = 300.0;
const MAP_IMAGE_WIDTH_KEY: &str = "map_image_width";
const MAP_IMAGE_HEIGHT_KEY: &str = "map_image_height";

fn ensure_map_georef_schema(conn: &Connection) -> Result<(), String> {
    conn.execute_batch(
        r#"
        CREATE TABLE IF NOT EXISTS map_gps_anchors (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL DEFAULT '',
            map_x REAL NOT NULL,
            map_y REAL NOT NULL,
            lat REAL NULL,
            lon REAL NULL,
            link_kind TEXT NOT NULL DEFAULT 'free',
            legend_id INTEGER NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            FOREIGN KEY (legend_id) REFERENCES cp_legends(id) ON DELETE CASCADE
        );
        "#,
    )
    .map_err(|e| format!("ensure map_gps_anchors: {e}"))?;
    if !table_has_column(conn, "map_gps_anchors", "link_kind")? {
        conn.execute(
            "ALTER TABLE map_gps_anchors ADD COLUMN link_kind TEXT NOT NULL DEFAULT 'free'",
            [],
        )
        .map_err(|e| format!("add map_gps_anchors.link_kind: {e}"))?;
    }
    if !table_has_column(conn, "map_gps_anchors", "legend_id")? {
        conn.execute(
            "ALTER TABLE map_gps_anchors ADD COLUMN legend_id INTEGER NULL",
            [],
        )
        .map_err(|e| format!("add map_gps_anchors.legend_id: {e}"))?;
    }
    conn.execute_batch(
        r#"
        CREATE UNIQUE INDEX IF NOT EXISTS idx_map_gps_anchors_legend
            ON map_gps_anchors(legend_id) WHERE legend_id IS NOT NULL;
        CREATE UNIQUE INDEX IF NOT EXISTS idx_map_gps_anchors_start
            ON map_gps_anchors(link_kind) WHERE link_kind = 'start';
        CREATE UNIQUE INDEX IF NOT EXISTS idx_map_gps_anchors_finish
            ON map_gps_anchors(link_kind) WHERE link_kind = 'finish';
        "#,
    )
    .map_err(|e| format!("ensure map_gps_anchors indexes: {e}"))?;
    // Incomplete GPS anchors (without coordinates) are not allowed.
    conn.execute(
        "DELETE FROM map_gps_anchors WHERE lat IS NULL OR lon IS NULL",
        [],
    )
    .map_err(|e| format!("purge incomplete map_gps_anchors: {e}"))?;
    Ok(())
}

#[derive(Debug, Serialize, Clone)]
pub struct MapGpsAnchor {
    pub id: i64,
    pub name: String,
    pub map_x: f64,
    pub map_y: f64,
    pub lat: Option<f64>,
    pub lon: Option<f64>,
    /// free | legend | start | finish
    pub link_kind: String,
    pub legend_id: Option<i64>,
    pub cp_number: Option<i64>,
    /// true if map position is taken from КП/старт/финиш (not freely movable)
    pub map_locked: bool,
    /// false if linked feature is missing map placement
    pub placement_ok: bool,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct MapGpsAnchorUpsert {
    pub id: Option<i64>,
    pub name: Option<String>,
    pub map_x: Option<f64>,
    pub map_y: Option<f64>,
    pub lat: Option<f64>,
    pub lon: Option<f64>,
    /// free | legend | start | finish (default free)
    pub link_kind: Option<String>,
    pub legend_id: Option<i64>,
}

#[derive(Debug, Serialize, Clone)]
pub struct MapGeorefTransform {
    /// X = a*px - b*py + tx  (east meters)
    pub a: f64,
    pub b: f64,
    pub tx: f64,
    pub ty: f64,
    /// meters per pixel (uniform similarity scale)
    pub meters_per_pixel: f64,
    pub residual_rms_m: f64,
    pub anchors_used: i64,
}

#[derive(Debug, Serialize, Clone)]
pub struct MapGeorefInfo {
    pub scale_denominator: Option<i64>,
    pub image_width: Option<f64>,
    pub image_height: Option<f64>,
    pub anchors: Vec<MapGpsAnchor>,
    pub ready: bool,
    pub status: String,
    pub transform: Option<MapGeorefTransform>,
    /// Approximate printed scale 1:N implied by GPS (needs image width).
    pub gps_implied_scale_denominator: Option<f64>,
}

#[derive(Debug, Serialize, Clone)]
pub struct ParticipantPathDistance {
    pub ready: bool,
    pub status: String,
    pub distance_m: Option<f64>,
    pub legs_counted: i64,
    pub legs_missing: i64,
    pub missing_cps: Vec<i64>,
}

struct Helmert2D {
    a: f64,
    b: f64,
    tx: f64,
    ty: f64,
}

impl Helmert2D {
    fn apply(&self, x: f64, y: f64) -> (f64, f64) {
        (
            self.a * x - self.b * y + self.tx,
            self.b * x + self.a * y + self.ty,
        )
    }

    fn scale(&self) -> f64 {
        (self.a * self.a + self.b * self.b).sqrt()
    }
}

fn fit_helmert_2d(src: &[(f64, f64)], dst: &[(f64, f64)]) -> Result<Helmert2D, String> {
    if src.len() != dst.len() {
        return Err("src/dst length mismatch".to_string());
    }
    if src.len() < 2 {
        return Err("need at least 2 control points".to_string());
    }
    let n = src.len() as f64;
    let (mut sum_sx, mut sum_sy, mut sum_dx, mut sum_dy) = (0.0, 0.0, 0.0, 0.0);
    for i in 0..src.len() {
        sum_sx += src[i].0;
        sum_sy += src[i].1;
        sum_dx += dst[i].0;
        sum_dy += dst[i].1;
    }
    let (mx, my, mean_dx, mean_dy) = (sum_sx / n, sum_sy / n, sum_dx / n, sum_dy / n);
    let (mut sum_uu, mut sum_u_u, mut sum_u_v) = (0.0, 0.0, 0.0);
    for i in 0..src.len() {
        let u = src[i].0 - mx;
        let v = src[i].1 - my;
        let du = dst[i].0 - mean_dx;
        let dv = dst[i].1 - mean_dy;
        sum_uu += u * u + v * v;
        sum_u_u += u * du + v * dv;
        sum_u_v += u * dv - v * du;
    }
    if sum_uu < 1e-18 {
        return Err("опорные точки на карте слишком близко друг к другу".to_string());
    }
    let a = sum_u_u / sum_uu;
    let b = sum_u_v / sum_uu;
    Ok(Helmert2D {
        a,
        b,
        tx: mean_dx - a * mx + b * my,
        ty: mean_dy - b * mx - a * my,
    })
}

fn latlon_to_local_meters(lat: f64, lon: f64, lat0: f64, lon0: f64) -> (f64, f64) {
    const R: f64 = 6_371_000.0;
    let lat0_rad = lat0.to_radians();
    let east = (lon - lon0).to_radians() * lat0_rad.cos() * R;
    let north = (lat - lat0).to_radians() * R;
    (east, north)
}

fn parse_optional_positive_f64(raw: Option<String>) -> Option<f64> {
    let value = raw?.trim().to_string();
    if value.is_empty() {
        return None;
    }
    value.parse::<f64>().ok().filter(|v| v.is_finite() && *v > 0.0)
}

fn parse_optional_positive_i64(raw: Option<String>) -> Option<i64> {
    let value = raw?.trim().to_string();
    if value.is_empty() {
        return None;
    }
    value.parse::<i64>().ok().filter(|v| *v > 0)
}

pub fn get_map_scale_denominator(conn: &Connection) -> Result<Option<i64>, String> {
    Ok(parse_optional_positive_i64(get_setting_value(
        conn,
        MAP_SCALE_DENOM_KEY,
    )?))
}

pub fn set_map_scale_denominator(conn: &Connection, denominator: Option<i64>) -> Result<(), String> {
    match denominator {
        None => delete_setting_value(conn, MAP_SCALE_DENOM_KEY)?,
        Some(v) if v > 0 => set_setting_value(conn, MAP_SCALE_DENOM_KEY, &v.to_string())?,
        Some(_) => return Err("Масштаб должен быть положительным (например 15000 для 1:15000)".to_string()),
    }
    Ok(())
}

pub fn set_course_map_image_size(
    conn: &Connection,
    width: f64,
    height: f64,
) -> Result<(), String> {
    if !(width.is_finite() && height.is_finite() && width > 0.0 && height > 0.0) {
        return Err("Размер изображения карты должен быть > 0".to_string());
    }
    set_setting_value(conn, MAP_IMAGE_WIDTH_KEY, &format!("{width}"))?;
    set_setting_value(conn, MAP_IMAGE_HEIGHT_KEY, &format!("{height}"))?;
    Ok(())
}

fn load_map_image_size(conn: &Connection) -> Result<(Option<f64>, Option<f64>), String> {
    Ok((
        parse_optional_positive_f64(get_setting_value(conn, MAP_IMAGE_WIDTH_KEY)?),
        parse_optional_positive_f64(get_setting_value(conn, MAP_IMAGE_HEIGHT_KEY)?),
    ))
}

fn map_anchor_from_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<(i64, String, f64, f64, Option<f64>, Option<f64>, String, Option<i64>)> {
    Ok((
        r.get(0)?,
        r.get(1)?,
        r.get(2)?,
        r.get(3)?,
        r.get(4)?,
        r.get(5)?,
        r.get::<_, Option<String>>(6)?.unwrap_or_else(|| "free".to_string()),
        r.get(7)?,
    ))
}

fn normalize_link_kind(raw: &str) -> Result<String, String> {
    match raw.trim().to_ascii_lowercase().as_str() {
        "" | "free" => Ok("free".to_string()),
        "legend" => Ok("legend".to_string()),
        "start" => Ok("start".to_string()),
        "finish" => Ok("finish".to_string()),
        other => Err(format!(
            "link_kind должен быть free|legend|start|finish, получено «{other}»"
        )),
    }
}

fn resolve_linked_map_position(
    conn: &Connection,
    link_kind: &str,
    legend_id: Option<i64>,
) -> Result<(f64, f64, String, Option<i64>), String> {
    match link_kind {
        "free" => Err("resolve_linked_map_position: free".to_string()),
        "start" => {
            let special = get_course_map_special_points(conn)?;
            let (x, y) = match (special.start_map_x, special.start_map_y) {
                (Some(x), Some(y)) => (x, y),
                _ => {
                    return Err(
                        "Старт ещё не размещён на карте — сначала отметьте точку старта."
                            .to_string(),
                    )
                }
            };
            let name = match special.start_cp {
                Some(cp) => format!("Старт (КП {cp})"),
                None => "Старт".to_string(),
            };
            Ok((x, y, name, special.start_cp))
        }
        "finish" => {
            let special = get_course_map_special_points(conn)?;
            let (x, y) = match (special.finish_map_x, special.finish_map_y) {
                (Some(x), Some(y)) => (x, y),
                _ => {
                    return Err(
                        "Финиш ещё не размещён на карте — сначала отметьте точку финиша."
                            .to_string(),
                    )
                }
            };
            Ok((
                x,
                y,
                format!("Финиш (КП {})", special.finish_cp),
                Some(special.finish_cp),
            ))
        }
        "legend" => {
            let lid = legend_id.ok_or_else(|| "Для link_kind=legend нужен legend_id".to_string())?;
            let row = load_cp_legend_row(conn, lid)?;
            let (x, y) = match (row.map_x, row.map_y) {
                (Some(x), Some(y)) => (x, y),
                _ => {
                    return Err(format!(
                        "КП {} ещё не размещён на карте — сначала поставьте точку КП.",
                        row.cp_number
                    ))
                }
            };
            Ok((
                x,
                y,
                format!("КП {} — {}", row.cp_number, row.name),
                Some(row.cp_number),
            ))
        }
        other => Err(format!("неизвестный link_kind «{other}»")),
    }
}

fn hydrate_map_gps_anchor(
    conn: &Connection,
    id: i64,
    name: String,
    map_x: f64,
    map_y: f64,
    lat: Option<f64>,
    lon: Option<f64>,
    link_kind: String,
    legend_id: Option<i64>,
) -> Result<MapGpsAnchor, String> {
    let link_kind = normalize_link_kind(&link_kind)?;
    if link_kind == "free" {
        return Ok(MapGpsAnchor {
            id,
            name,
            map_x,
            map_y,
            lat,
            lon,
            link_kind,
            legend_id: None,
            cp_number: None,
            map_locked: false,
            placement_ok: true,
        });
    }
    match resolve_linked_map_position(conn, &link_kind, legend_id) {
        Ok((x, y, resolved_name, cp_number)) => {
            let _ = conn.execute(
                "UPDATE map_gps_anchors SET map_x = ?, map_y = ? WHERE id = ?",
                params![x, y, id],
            );
            let display_name = if name.trim().is_empty() || name.starts_with("GPS ") {
                resolved_name
            } else {
                name
            };
            Ok(MapGpsAnchor {
                id,
                name: display_name,
                map_x: x,
                map_y: y,
                lat,
                lon,
                link_kind: link_kind.clone(),
                legend_id: if link_kind == "legend" { legend_id } else { None },
                cp_number,
                map_locked: true,
                placement_ok: true,
            })
        }
        Err(_) => Ok(MapGpsAnchor {
            id,
            name,
            map_x,
            map_y,
            lat,
            lon,
            link_kind: link_kind.clone(),
            legend_id: if link_kind == "legend" { legend_id } else { None },
            cp_number: None,
            map_locked: true,
            placement_ok: false,
        }),
    }
}

pub fn list_map_gps_anchors(conn: &Connection) -> Result<Vec<MapGpsAnchor>, String> {
    ensure_map_georef_schema(conn)?;
    let mut stmt = conn
        .prepare(
            r#"
            SELECT id, name, map_x, map_y, lat, lon, link_kind, legend_id
            FROM map_gps_anchors
            ORDER BY id
            "#,
        )
        .map_err(|e| format!("prepare map_gps_anchors: {e}"))?;
    let rows = stmt
        .query_map([], map_anchor_from_row)
        .map_err(|e| format!("query map_gps_anchors: {e}"))?;
    let mut out = Vec::new();
    for row in rows {
        let (id, name, map_x, map_y, lat, lon, link_kind, legend_id) =
            row.map_err(|e| format!("read map_gps_anchor: {e}"))?;
        out.push(hydrate_map_gps_anchor(
            conn, id, name, map_x, map_y, lat, lon, link_kind, legend_id,
        )?);
    }
    Ok(out)
}

fn validate_map_norm(x: f64, y: f64) -> Result<(), String> {
    if !(0.0..=1.0).contains(&x) || !(0.0..=1.0).contains(&y) {
        return Err("Координаты на карте должны быть в диапазоне 0…1".to_string());
    }
    Ok(())
}

fn validate_lat_lon_required(lat: Option<f64>, lon: Option<f64>) -> Result<(f64, f64), String> {
    match (lat, lon) {
        (Some(la), Some(lo)) => {
            if !(-90.0..=90.0).contains(&la) || !(-180.0..=180.0).contains(&lo) {
                return Err("Широта −90…90, долгота −180…180".to_string());
            }
            if !la.is_finite() || !lo.is_finite() {
                return Err("GPS-координаты должны быть числами".to_string());
            }
            Ok((la, lo))
        }
        _ => Err("GPS-привязка без широты и долготы не сохраняется".to_string()),
    }
}

fn load_map_gps_anchor(conn: &Connection, id: i64) -> Result<MapGpsAnchor, String> {
    let row = conn
        .query_row(
            r#"
            SELECT id, name, map_x, map_y, lat, lon, link_kind, legend_id
            FROM map_gps_anchors
            WHERE id = ?
            "#,
            params![id],
            map_anchor_from_row,
        )
        .map_err(|e| format!("load map_gps_anchor: {e}"))?;
    let (id, name, map_x, map_y, lat, lon, link_kind, legend_id) = row;
    hydrate_map_gps_anchor(conn, id, name, map_x, map_y, lat, lon, link_kind, legend_id)
}

pub fn upsert_map_gps_anchor(
    conn: &Connection,
    item: MapGpsAnchorUpsert,
) -> Result<MapGpsAnchor, String> {
    ensure_map_georef_schema(conn)?;
    let (lat, lon) = validate_lat_lon_required(item.lat, item.lon)?;

    let existing = if let Some(id) = item.id {
        Some(load_map_gps_anchor(conn, id)?)
    } else {
        None
    };

    let link_kind = normalize_link_kind(
        item.link_kind
            .as_deref()
            .or(existing.as_ref().map(|e| e.link_kind.as_str()))
            .unwrap_or("free"),
    )?;
    let legend_id_opt = if link_kind == "legend" {
        Some(
            item.legend_id
                .or(existing.as_ref().and_then(|e| e.legend_id))
                .ok_or_else(|| "Для привязки к КП укажите legend_id".to_string())?,
        )
    } else {
        None
    };
    let legend_id = legend_id_opt.unwrap_or(0);

    let (map_x, map_y, default_name, _cp) = if link_kind == "free" {
        let x = item
            .map_x
            .or(existing.as_ref().map(|e| e.map_x))
            .ok_or_else(|| "Для свободной GPS-точки нужны map_x/map_y".to_string())?;
        let y = item
            .map_y
            .or(existing.as_ref().map(|e| e.map_y))
            .ok_or_else(|| "Для свободной GPS-точки нужны map_x/map_y".to_string())?;
        validate_map_norm(x, y)?;
        (x, y, "GPS".to_string(), None)
    } else {
        resolve_linked_map_position(conn, &link_kind, legend_id_opt)?
    };

    let name = item
        .name
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .or_else(|| existing.as_ref().map(|e| e.name.clone()))
        .filter(|s| !s.is_empty())
        .unwrap_or(default_name);

    // Reuse existing row for the same linked feature when inserting.
    let id = if let Some(existing_id) = item.id {
        let updated = conn
            .execute(
                r#"
                UPDATE map_gps_anchors
                SET name = ?, map_x = ?, map_y = ?, lat = ?, lon = ?, link_kind = ?, legend_id = ?
                WHERE id = ?
                "#,
                params![
                    name,
                    map_x,
                    map_y,
                    lat,
                    lon,
                    link_kind,
                    legend_id_opt,
                    existing_id
                ],
            )
            .map_err(|e| format!("update map_gps_anchor: {e}"))?;
        if updated == 0 {
            return Err(format!("GPS-точка #{existing_id} не найдена"));
        }
        existing_id
    } else {
        // If link already exists, update that row instead of inserting duplicate.
        let existing_link_id: Option<i64> = match link_kind.as_str() {
            "legend" => conn
                .query_row(
                    "SELECT id FROM map_gps_anchors WHERE legend_id = ? LIMIT 1",
                    params![legend_id],
                    |r| r.get(0),
                )
                .optional()
                .map_err(|e| format!("find legend gps: {e}"))?,
            "start" | "finish" => conn
                .query_row(
                    "SELECT id FROM map_gps_anchors WHERE link_kind = ? LIMIT 1",
                    params![link_kind],
                    |r| r.get(0),
                )
                .optional()
                .map_err(|e| format!("find {link_kind} gps: {e}"))?,
            _ => None,
        };
        if let Some(link_id) = existing_link_id {
            conn.execute(
                r#"
                UPDATE map_gps_anchors
                SET name = ?, map_x = ?, map_y = ?, lat = ?, lon = ?,
                    link_kind = ?, legend_id = ?
                WHERE id = ?
                "#,
                params![
                    name,
                    map_x,
                    map_y,
                    lat,
                    lon,
                    link_kind,
                    legend_id_opt,
                    link_id
                ],
            )
            .map_err(|e| format!("update linked map_gps_anchor: {e}"))?;
            link_id
        } else {
            conn.execute(
                r#"
                INSERT INTO map_gps_anchors(name, map_x, map_y, lat, lon, link_kind, legend_id)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                "#,
                params![
                    name,
                    map_x,
                    map_y,
                    lat,
                    lon,
                    link_kind,
                    legend_id_opt
                ],
            )
            .map_err(|e| format!("insert map_gps_anchor: {e}"))?;
            conn.last_insert_rowid()
        }
    };

    load_map_gps_anchor(conn, id)
}

pub fn delete_map_gps_anchor(conn: &Connection, anchor_id: i64) -> Result<(), String> {
    ensure_map_georef_schema(conn)?;
    let n = conn
        .execute(
            "DELETE FROM map_gps_anchors WHERE id = ?",
            params![anchor_id],
        )
        .map_err(|e| format!("delete map_gps_anchor: {e}"))?;
    if n == 0 {
        return Err(format!("GPS-точка #{anchor_id} не найдена"));
    }
    Ok(())
}

fn complete_gps_anchors(anchors: &[MapGpsAnchor]) -> Vec<&MapGpsAnchor> {
    anchors
        .iter()
        .filter(|a| a.lat.is_some() && a.lon.is_some() && a.placement_ok)
        .collect()
}

fn build_map_georef_transform(
    anchors: &[MapGpsAnchor],
    image_width: f64,
    image_height: f64,
) -> Result<MapGeorefTransform, String> {
    let complete = complete_gps_anchors(anchors);
    if complete.len() < 2 {
        return Err("Нужно минимум 2 GPS-точки с широтой и долготой".to_string());
    }
    let lat0 = complete.iter().map(|a| a.lat.unwrap()).sum::<f64>() / complete.len() as f64;
    let lon0 = complete.iter().map(|a| a.lon.unwrap()).sum::<f64>() / complete.len() as f64;

    let mut src = Vec::with_capacity(complete.len());
    let mut dst = Vec::with_capacity(complete.len());
    for a in &complete {
        // Image/CSS Y grows downward; local east-north Y grows northward.
        src.push(map_norm_to_image_pixels(a.map_x, a.map_y, image_width, image_height));
        dst.push(latlon_to_local_meters(a.lat.unwrap(), a.lon.unwrap(), lat0, lon0));
    }
    let helmert = fit_helmert_2d(&src, &dst)?;
    let mut sum_sq = 0.0;
    for i in 0..src.len() {
        let (e, n) = helmert.apply(src[i].0, src[i].1);
        let de = e - dst[i].0;
        let dn = n - dst[i].1;
        sum_sq += de * de + dn * dn;
    }
    let residual_rms_m = (sum_sq / src.len() as f64).sqrt();
    Ok(MapGeorefTransform {
        a: helmert.a,
        b: helmert.b,
        tx: helmert.tx,
        ty: helmert.ty,
        meters_per_pixel: helmert.scale(),
        residual_rms_m,
        anchors_used: complete.len() as i64,
    })
}

/// Soft 1:N estimate from GPS meters/pixel, assuming a ~300 dpi scan of a paper map.
fn gps_implied_scale_denominator(meters_per_pixel: f64) -> Option<f64> {
    if meters_per_pixel <= 1e-12 {
        return None;
    }
    let px_per_cm = MAP_SCAN_DPI / 2.54;
    Some(meters_per_pixel * px_per_cm * 100.0)
}

pub fn get_map_georef_info(conn: &Connection) -> Result<MapGeorefInfo, String> {
    ensure_map_georef_schema(conn)?;
    let scale_denominator = get_map_scale_denominator(conn)?;
    let (image_width, image_height) = load_map_image_size(conn)?;
    let anchors = list_map_gps_anchors(conn)?;
    let complete_n = complete_gps_anchors(&anchors).len();

    let (ready, mut status, transform, gps_implied) = match (image_width, image_height, complete_n)
    {
        (Some(w), Some(h), n) if n >= 2 => match build_map_georef_transform(&anchors, w, h) {
            Ok(t) => {
                let implied = gps_implied_scale_denominator(t.meters_per_pixel);
                let msg = format!(
                    "Привязка готова ({} GPS). Расстояния по GPS: {:.4} м/пкс.",
                    t.anchors_used, t.meters_per_pixel
                );
                (true, msg, Some(t), implied)
            }
            Err(e) => (false, e, None, None),
        },
        (_, _, n) if n < 2 => (
            false,
            format!(
                "Для привязки нужно минимум 2 GPS-точки с широтой/долготой (сейчас {n})."
            ),
            None,
            None,
        ),
        _ => (
            false,
            "Откройте карту один раз, чтобы сохранить размер изображения (пиксели).".to_string(),
            None,
            None,
        ),
    };

    if let Some(implied) = gps_implied {
        status = format!(
            "{status} Оценка масштаба по GPS (~{MAP_SCAN_DPI:.0} dpi) ≈ 1:{implied:.0}."
        );
    } else if let Some(denom) = scale_denominator {
        status = format!("{status} Заявленный масштаб 1:{denom}.");
    }

    Ok(MapGeorefInfo {
        scale_denominator,
        image_width,
        image_height,
        anchors,
        ready,
        status,
        transform,
        gps_implied_scale_denominator: gps_implied,
    })
}

fn map_norm_to_image_pixels(
    map_x: f64,
    map_y: f64,
    image_width: f64,
    image_height: f64,
) -> (f64, f64) {
    // Image/CSS Y grows downward; local east-north meters grow northward.
    (map_x * image_width, (1.0 - map_y) * image_height)
}

fn map_norm_to_meters(
    transform: &MapGeorefTransform,
    image_width: f64,
    image_height: f64,
    map_x: f64,
    map_y: f64,
) -> (f64, f64) {
    let helmert = Helmert2D {
        a: transform.a,
        b: transform.b,
        tx: transform.tx,
        ty: transform.ty,
    };
    let (px, py) = map_norm_to_image_pixels(map_x, map_y, image_width, image_height);
    helmert.apply(px, py)
}

pub fn map_distance_meters(
    conn: &Connection,
    x1: f64,
    y1: f64,
    x2: f64,
    y2: f64,
) -> Result<f64, String> {
    let info = get_map_georef_info(conn)?;
    let transform = info
        .transform
        .ok_or_else(|| info.status.clone())?;
    let w = info
        .image_width
        .ok_or_else(|| "Нет размера изображения карты".to_string())?;
    let h = info
        .image_height
        .ok_or_else(|| "Нет размера изображения карты".to_string())?;
    let (e1, n1) = map_norm_to_meters(&transform, w, h, x1, y1);
    let (e2, n2) = map_norm_to_meters(&transform, w, h, x2, y2);
    Ok(((e2 - e1).powi(2) + (n2 - n1).powi(2)).sqrt())
}

pub fn participant_path_distance_m(
    conn: &mut Connection,
    result_id: i64,
) -> Result<ParticipantPathDistance, String> {
    let details = query_participant_details(conn, result_id)?;
    let info = get_map_georef_info(conn)?;
    if !info.ready {
        return Ok(ParticipantPathDistance {
            ready: false,
            status: info.status,
            distance_m: None,
            legs_counted: 0,
            legs_missing: 0,
            missing_cps: vec![],
        });
    }
    let transform = info.transform.expect("ready implies transform");
    let w = info.image_width.expect("ready implies width");
    let h = info.image_height.expect("ready implies height");
    let positions = load_cp_map_positions(conn)?;

    let special = get_course_map_special_points(conn)?;
    let mut points: Vec<(f64, f64)> = Vec::new();
    let mut missing: Vec<i64> = Vec::new();

    if let (Some(x), Some(y)) = (special.start_map_x, special.start_map_y) {
        points.push(map_norm_to_meters(&transform, w, h, x, y));
    }

    for mark in &details.corrected_marks {
        match positions.get(&mark.cp_number) {
            Some(&(x, y)) => {
                let p = map_norm_to_meters(&transform, w, h, x, y);
                if let Some(last) = points.last() {
                    if (last.0 - p.0).abs() < 1e-6 && (last.1 - p.1).abs() < 1e-6 {
                        continue;
                    }
                }
                points.push(p);
            }
            None => {
                if !missing.contains(&mark.cp_number) {
                    missing.push(mark.cp_number);
                }
            }
        }
    }

    let mut distance = 0.0;
    let mut legs = 0_i64;
    for i in 0..points.len().saturating_sub(1) {
        let a = points[i];
        let b = points[i + 1];
        distance += ((b.0 - a.0).powi(2) + (b.1 - a.1).powi(2)).sqrt();
        legs += 1;
    }

    Ok(ParticipantPathDistance {
        ready: true,
        status: if missing.is_empty() {
            format!("Длина пути ≈ {:.0} м ({} перегонов).", distance, legs)
        } else {
            format!(
                "Длина пути ≈ {:.0} м ({} перегонов); без координат на карте: {}.",
                distance,
                legs,
                missing
                    .iter()
                    .map(|c| c.to_string())
                    .collect::<Vec<_>>()
                    .join(", ")
            )
        },
        distance_m: Some(distance),
        legs_counted: legs,
        legs_missing: missing.len() as i64,
        missing_cps: missing,
    })
}

pub fn delete_cp_legend(conn: &Connection, legend_id: i64) -> Result<(), String> {
    let changed = conn
        .execute("DELETE FROM cp_legends WHERE id = ?", params![legend_id])
        .map_err(|e| format!("delete cp_legend: {e}"))?;
    if changed == 0 {
        return Err(format!("Легенда КП id={legend_id} не найдена"));
    }
    Ok(())
}

pub fn import_cp_legends_content(
    conn: &Connection,
    csv_content: &str,
    reset: bool,
) -> Result<CpLegendImportSummary, String> {
    let mut reader = csv::ReaderBuilder::new()
        .delimiter(b';')
        .has_headers(false)
        .flexible(true)
        .from_reader(csv_content.as_bytes());

    struct ParsedLegend {
        source_row: i64,
        cp_number: i64,
        name: String,
        cp_type: Option<String>,
    }

    let mut parsed = Vec::new();
    let mut errors = Vec::new();
    let mut seen_numbers: BTreeMap<i64, i64> = BTreeMap::new();

    for (idx, row) in reader.records().enumerate() {
        let source_row = idx as i64 + 1;
        let row_label = format!("Строка {source_row}");
        let rec = match row {
            Ok(r) => r,
            Err(e) => {
                errors.push(format!("{row_label}: {e}"));
                continue;
            }
        };
        if record_is_blank(&rec) {
            continue;
        }

        let number_raw = field(&rec, 0);
        let name_raw = field(&rec, 1);
        let type_raw = field(&rec, 2);

        // Optional header row: номер;название;тип
        if idx == 0
            && number_raw.eq_ignore_ascii_case("номер")
            && (name_raw.is_empty()
                || name_raw.eq_ignore_ascii_case("название")
                || name_raw.eq_ignore_ascii_case("name"))
        {
            continue;
        }

        let cp_number = match parse_cp_number(&number_raw, &row_label) {
            Ok(n) => n,
            Err(e) => {
                errors.push(e);
                continue;
            }
        };
        if name_raw.is_empty() {
            errors.push(format!("{row_label}: название КП не заполнено"));
            continue;
        }
        if let Some(prev_row) = seen_numbers.insert(cp_number, source_row) {
            errors.push(format!(
                "{row_label}: номер КП {cp_number} повторяется (ранее в строке {prev_row})"
            ));
            continue;
        }
        let cp_type = if type_raw.is_empty() {
            None
        } else {
            Some(type_raw)
        };
        parsed.push(ParsedLegend {
            source_row,
            cp_number,
            name: name_raw,
            cp_type,
        });
    }

    if !errors.is_empty() {
        let preview: Vec<_> = errors.iter().take(20).cloned().collect();
        let mut msg = preview.join("\n");
        if errors.len() > 20 {
            msg.push_str(&format!("\n… и ещё {} ошибок", errors.len() - 20));
        }
        return Err(msg);
    }
    if parsed.is_empty() {
        return Err("В файле легенд нет данных".to_string());
    }

    let has_legacy_type = table_has_column(conn, "cp_legends", "cp_type")?;
    let tx = conn
        .unchecked_transaction()
        .map_err(|e| format!("cp_legends transaction: {e}"))?;
    if reset {
        tx.execute("DELETE FROM cp_legends", [])
            .map_err(|e| format!("clear cp_legends: {e}"))?;
    }

    let mut imported_rows = 0_i64;
    for item in &parsed {
        let type_id = match &item.cp_type {
            Some(name) => Some(ensure_cp_type_id_tx(&tx, name)?),
            None => None,
        };
        if has_legacy_type {
            tx.execute(
                r#"
                INSERT INTO cp_legends(cp_number, name, cp_type_id, cp_type)
                VALUES(?, ?, ?, ?)
                ON CONFLICT(cp_number) DO UPDATE SET
                    name = excluded.name,
                    cp_type_id = excluded.cp_type_id,
                    cp_type = excluded.cp_type
                "#,
                params![item.cp_number, item.name, type_id, item.cp_type],
            )
        } else {
            tx.execute(
                r#"
                INSERT INTO cp_legends(cp_number, name, cp_type_id)
                VALUES(?, ?, ?)
                ON CONFLICT(cp_number) DO UPDATE SET
                    name = excluded.name,
                    cp_type_id = excluded.cp_type_id
                "#,
                params![item.cp_number, item.name, type_id],
            )
        }
        .map_err(|e| format!("Строка {}: upsert cp_legend: {e}", item.source_row))?;
        imported_rows += 1;
    }

    let total_rows: i64 = tx
        .query_row("SELECT COUNT(*) FROM cp_legends", [], |r| r.get(0))
        .map_err(|e| format!("count cp_legends: {e}"))?;
    tx.commit()
        .map_err(|e| format!("commit cp_legends import: {e}"))?;

    Ok(CpLegendImportSummary {
        imported_rows,
        total_rows,
    })
}

pub fn list_courses(conn: &Connection) -> Result<Vec<CourseRow>, String> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT c.id, c.name, cc.seq, cc.cp_number
            FROM courses c
            LEFT JOIN course_controls cc ON cc.course_id = c.id
            ORDER BY c.name COLLATE NOCASE ASC, cc.seq ASC
            "#,
        )
        .map_err(|e| format!("prepare list courses: {e}"))?;
    let rows = stmt
        .query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, Option<i64>>(2)?,
                r.get::<_, Option<i64>>(3)?,
            ))
        })
        .map_err(|e| format!("query list courses: {e}"))?;
    let mut by_id: BTreeMap<i64, CourseRow> = BTreeMap::new();
    for row in rows {
        let (id, name, _seq, cp) = row.map_err(|e| format!("read course row: {e}"))?;
        let entry = by_id.entry(id).or_insert(CourseRow {
            id,
            name,
            controls: Vec::new(),
        });
        if let Some(cp_number) = cp {
            entry.controls.push(cp_number);
        }
    }
    let mut out: Vec<CourseRow> = by_id.into_values().collect();
    out.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    Ok(out)
}

fn replace_course_controls_tx(
    tx: &rusqlite::Transaction<'_>,
    course_id: i64,
    name: &str,
    controls: &[i64],
) -> Result<(), String> {
    tx.execute(
        "DELETE FROM course_controls WHERE course_id = ?",
        params![course_id],
    )
    .map_err(|e| format!("replace course controls «{name}»: {e}"))?;
    for (idx, cp) in controls.iter().enumerate() {
        tx.execute(
            "INSERT INTO course_controls(course_id, seq, cp_number) VALUES(?, ?, ?)",
            params![course_id, (idx as i64) + 1, cp],
        )
        .map_err(|e| format!("insert CP {cp} for «{name}»: {e}"))?;
    }
    Ok(())
}

fn normalize_course_controls(controls: &[i64]) -> Result<Vec<i64>, String> {
    if controls.is_empty() {
        return Err("Укажите хотя бы один КП.".to_string());
    }
    let mut out = Vec::with_capacity(controls.len());
    for cp in controls {
        if *cp <= 0 {
            return Err(format!("Номер КП должен быть > 0, получено {cp}."));
        }
        out.push(*cp);
    }
    Ok(out)
}

pub fn save_course(
    conn: &Connection,
    course_id: Option<i64>,
    name: String,
    controls: Vec<i64>,
) -> Result<CourseRow, String> {
    let name = name.split_whitespace().collect::<Vec<_>>().join(" ");
    if name.is_empty() {
        return Err("Укажите название дистанции.".to_string());
    }
    let controls = normalize_course_controls(&controls)?;
    let tx = conn
        .unchecked_transaction()
        .map_err(|e| format!("save course transaction: {e}"))?;
    let exists = tx
        .query_row(
            "SELECT id FROM courses WHERE name = ? COLLATE NOCASE LIMIT 1",
            params![name],
            |r| r.get::<_, i64>(0),
        )
        .optional()
        .map_err(|e| format!("check course name: {e}"))?;
    if let Some(existing_id) = exists {
        if course_id != Some(existing_id) {
            return Err(format!("Дистанция «{name}» уже есть."));
        }
    }
    let id = if let Some(id) = course_id {
        let changed = tx
            .execute(
                "UPDATE courses SET name = ? WHERE id = ?",
                params![name, id],
            )
            .map_err(|e| format!("update course: {e}"))?;
        if changed == 0 {
            return Err(format!("Дистанция id={id} не найдена"));
        }
        id
    } else {
        tx.execute("INSERT INTO courses(name) VALUES(?)", params![name])
            .map_err(|e| format!("insert course: {e}"))?;
        tx.last_insert_rowid()
    };
    replace_course_controls_tx(&tx, id, &name, &controls)?;
    tx.commit()
        .map_err(|e| format!("commit save course: {e}"))?;
    Ok(CourseRow {
        id,
        name,
        controls,
    })
}

pub fn delete_course(conn: &Connection, course_id: i64) -> Result<(), String> {
    conn.execute(
        "DELETE FROM leg_exclusion_rules WHERE course_id = ?",
        params![course_id],
    )
    .map_err(|e| format!("delete course exclusion rules: {e}"))?;
    conn.execute(
        "DELETE FROM course_controls WHERE course_id = ?",
        params![course_id],
    )
    .map_err(|e| format!("delete course controls: {e}"))?;
    let changed = conn
        .execute("DELETE FROM courses WHERE id = ?", params![course_id])
        .map_err(|e| format!("delete course: {e}"))?;
    if changed == 0 {
        return Err(format!("Дистанция id={course_id} не найдена"));
    }
    Ok(())
}

pub fn import_courses_content(
    conn: &Connection,
    csv_content: &str,
    reset: bool,
) -> Result<CoursesImportSummary, String> {
    let delimiter = detect_csv_delimiter(csv_content);
    let mut reader = csv::ReaderBuilder::new()
        .delimiter(delimiter)
        .has_headers(false)
        .flexible(true)
        .from_reader(csv_content.as_bytes());

    struct ParsedCourse {
        name: String,
        controls: Vec<i64>,
    }

    let mut parsed = Vec::new();
    let mut seen: BTreeMap<String, i64> = BTreeMap::new();

    for (idx, row) in reader.records().enumerate() {
        let source_row = idx as i64 + 1;
        let rec = row.map_err(|e| format!("Строка {source_row}: {e}"))?;
        if record_is_blank(&rec) {
            continue;
        }
        let name = field(&rec, 0);
        if idx == 0
            && (name.eq_ignore_ascii_case("название")
                || name.eq_ignore_ascii_case("дистанция")
                || name.eq_ignore_ascii_case("course")
                || name.eq_ignore_ascii_case("name"))
        {
            continue;
        }
        if name.is_empty() {
            return Err(format!("Строка {source_row}: не указано название дистанции"));
        }
        let key = course_key(&name);
        if let Some(prev) = seen.insert(key, source_row) {
            return Err(format!(
                "Строка {source_row}: дистанция «{name}» повторяется (ранее строка {prev})"
            ));
        }
        let mut controls = Vec::new();
        for i in 1..rec.len() {
            let raw = field(&rec, i);
            if raw.is_empty() {
                continue;
            }
            let cp = raw.parse::<i64>().map_err(|_| {
                format!("Строка {source_row}: некорректный номер КП «{raw}»")
            })?;
            if cp <= 0 {
                return Err(format!("Строка {source_row}: номер КП должен быть > 0"));
            }
            controls.push(cp);
        }
        if controls.is_empty() {
            return Err(format!(
                "Строка {source_row}: у дистанции «{name}» нет контрольных пунктов"
            ));
        }
        parsed.push(ParsedCourse {
            name,
            controls,
        });
    }

    if parsed.is_empty() {
        return Err("Файл дистанций пуст".to_string());
    }

    let tx = conn
        .unchecked_transaction()
        .map_err(|e| format!("courses transaction: {e}"))?;
    if reset {
        tx.execute("DELETE FROM leg_exclusion_rules WHERE course_id IS NOT NULL", [])
            .map_err(|e| format!("clear course exclusion rules: {e}"))?;
        tx.execute("DELETE FROM course_controls", [])
            .map_err(|e| format!("clear course_controls: {e}"))?;
        tx.execute("DELETE FROM courses", [])
            .map_err(|e| format!("clear courses: {e}"))?;
    }

    let mut imported_rows = 0_i64;
    for course in &parsed {
        tx.execute(
            r#"
            INSERT INTO courses(name) VALUES(?)
            ON CONFLICT(name) DO UPDATE SET name=excluded.name
            "#,
            params![course.name],
        )
        .map_err(|e| format!("upsert course «{}»: {e}", course.name))?;
        let course_id: i64 = tx
            .query_row(
                "SELECT id FROM courses WHERE name = ? COLLATE NOCASE LIMIT 1",
                params![course.name],
                |r| r.get(0),
            )
            .map_err(|e| format!("load course id «{}»: {e}", course.name))?;
        replace_course_controls_tx(&tx, course_id, &course.name, &course.controls)?;
        imported_rows += 1;
    }

    let total_rows: i64 = tx
        .query_row("SELECT COUNT(*) FROM courses", [], |r| r.get(0))
        .map_err(|e| format!("count courses: {e}"))?;
    tx.commit()
        .map_err(|e| format!("commit courses import: {e}"))?;
    Ok(CoursesImportSummary {
        imported_rows,
        total_rows,
    })
}

pub fn query_format_cp_type_rules(
    conn: &Connection,
) -> Result<Vec<FormatCpTypeRulesBundle>, String> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT
                f.id,
                f.format_name,
                r.cp_type_id,
                t.type_name,
                r.max_count
            FROM format_cp_type_rules r
            JOIN start_protocol_formats f ON f.id = r.format_id
            JOIN cp_legend_types t ON t.id = r.cp_type_id
            ORDER BY f.format_name COLLATE NOCASE ASC, t.type_name COLLATE NOCASE ASC
            "#,
        )
        .map_err(|e| format!("prepare format_cp_type_rules query: {e}"))?;
    let rows = stmt
        .query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, i64>(2)?,
                r.get::<_, String>(3)?,
                r.get::<_, Option<i64>>(4)?,
            ))
        })
        .map_err(|e| format!("query format_cp_type_rules: {e}"))?;

    let mut by_format: BTreeMap<i64, FormatCpTypeRulesBundle> = BTreeMap::new();
    for row in rows {
        let (format_id, format_name, cp_type_id, cp_type_name, max_count) =
            row.map_err(|e| format!("read format_cp_type_rules row: {e}"))?;
        let entry = by_format.entry(format_id).or_insert_with(|| FormatCpTypeRulesBundle {
            format_id,
            format_name: format_name.clone(),
            rules: Vec::new(),
        });
        entry.rules.push(FormatCpTypeRuleItem {
            cp_type_id,
            cp_type_name,
            max_count,
        });
    }
    Ok(by_format.into_values().collect())
}

pub fn set_format_cp_type_rules(
    conn: &Connection,
    format_id: i64,
    rules: Vec<FormatCpTypeRuleInput>,
) -> Result<Vec<FormatCpTypeRulesBundle>, String> {
    require_format_id(conn, format_id)?;

    let mut unique: BTreeMap<i64, Option<i64>> = BTreeMap::new();
    for rule in rules {
        if rule.cp_type_id <= 0 {
            return Err("Некорректный id типа КП".to_string());
        }
        let max_count = match rule.max_count {
            None => None,
            Some(n) if n > 0 => Some(n),
            Some(_) => {
                return Err(
                    "Максимальное количество КП должно быть пустым или целым числом ≥ 1"
                        .to_string(),
                )
            }
        };
        let exists: Option<i64> = conn
            .query_row(
                "SELECT id FROM cp_legend_types WHERE id = ? LIMIT 1",
                params![rule.cp_type_id],
                |r| r.get(0),
            )
            .optional()
            .map_err(|e| format!("check cp type for rule: {e}"))?;
        if exists.is_none() {
            return Err(format!("Тип КП id={} не найден", rule.cp_type_id));
        }
        unique.insert(rule.cp_type_id, max_count);
    }

    let tx = conn
        .unchecked_transaction()
        .map_err(|e| format!("format_cp_type_rules transaction: {e}"))?;
    tx.execute(
        "DELETE FROM format_cp_type_rules WHERE format_id = ?",
        params![format_id],
    )
    .map_err(|e| format!("clear format_cp_type_rules: {e}"))?;
    for (cp_type_id, max_count) in unique {
        tx.execute(
            r#"
            INSERT INTO format_cp_type_rules(format_id, cp_type_id, max_count)
            VALUES(?, ?, ?)
            "#,
            params![format_id, cp_type_id, max_count],
        )
        .map_err(|e| format!("insert format_cp_type_rule: {e}"))?;
    }
    tx.commit()
        .map_err(|e| format!("commit format_cp_type_rules: {e}"))?;
    query_format_cp_type_rules(conn)
}

pub fn delete_format_cp_type_rules(
    conn: &Connection,
    format_id: i64,
) -> Result<Vec<FormatCpTypeRulesBundle>, String> {
    require_format_id(conn, format_id)?;
    conn.execute(
        "DELETE FROM format_cp_type_rules WHERE format_id = ?",
        params![format_id],
    )
    .map_err(|e| format!("delete format_cp_type_rules: {e}"))?;
    query_format_cp_type_rules(conn)
}

pub fn query_format_settings(conn: &Connection) -> Result<Vec<FormatSettings>, String> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT
                f.id,
                f.format_name,
                fs.control_minutes,
                fs.penalty_per_minute,
                fs.dq_minutes,
                fs.finish_cp,
                fs.competition_start_time
            FROM start_protocol_formats f
            LEFT JOIN format_settings fs ON fs.format_id = f.id
            ORDER BY f.format_name COLLATE NOCASE ASC
            "#,
        )
        .map_err(|e| format!("prepare format settings query: {e}"))?;
    let rows = stmt
        .query_map([], |r| {
            Ok(FormatSettings {
                format_id: r.get(0)?,
                format_name: r.get(1)?,
                control_minutes: r.get(2)?,
                penalty_per_minute: r.get(3)?,
                dq_minutes: r.get(4)?,
                finish_cp: r.get(5)?,
                competition_start_time: r.get(6)?,
            })
        })
        .map_err(|e| format!("query format settings: {e}"))?;
    let mut out = Vec::new();
    for row in rows {
        out.push(row.map_err(|e| format!("read format settings row: {e}"))?);
    }
    Ok(out)
}

pub fn set_format_settings(
    conn: &Connection,
    format_id: i64,
    control_minutes: Option<i64>,
    penalty_per_minute: Option<i64>,
    dq_minutes: Option<i64>,
    finish_cp: Option<i64>,
    competition_start_time: Option<String>,
) -> Result<FormatSettings, String> {
    let format_name: String = conn
        .query_row(
            "SELECT format_name FROM start_protocol_formats WHERE id = ? LIMIT 1",
            params![format_id],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("check format for settings: {e}"))?
        .ok_or_else(|| format!("Формат id={format_id} не найден"))?;

    for (label, value) in [
        ("control_minutes", control_minutes),
        ("penalty_per_minute", penalty_per_minute),
        ("dq_minutes", dq_minutes),
        ("finish_cp", finish_cp),
    ] {
        if let Some(v) = value {
            if v < 0 {
                return Err(format!("{label} не может быть отрицательным"));
            }
        }
    }

    let normalized_start_time = match competition_start_time {
        None => None,
        Some(raw) => {
            let normalized = normalize_competition_time(&raw)?;
            if normalized.is_empty() {
                None
            } else {
                Some(normalized)
            }
        }
    };

    if control_minutes.is_none()
        && penalty_per_minute.is_none()
        && dq_minutes.is_none()
        && finish_cp.is_none()
        && normalized_start_time.is_none()
    {
        conn.execute(
            "DELETE FROM format_settings WHERE format_id = ?",
            params![format_id],
        )
        .map_err(|e| format!("cleanup empty format settings: {e}"))?;
    } else {
        conn.execute(
            r#"
            INSERT INTO format_settings(
                format_id, control_minutes, penalty_per_minute, dq_minutes, finish_cp, competition_start_time
            ) VALUES(?, ?, ?, ?, ?, ?)
            ON CONFLICT(format_id) DO UPDATE SET
                control_minutes=excluded.control_minutes,
                penalty_per_minute=excluded.penalty_per_minute,
                dq_minutes=excluded.dq_minutes,
                finish_cp=excluded.finish_cp,
                competition_start_time=excluded.competition_start_time
            "#,
            params![
                format_id,
                control_minutes,
                penalty_per_minute,
                dq_minutes,
                finish_cp,
                normalized_start_time
            ],
        )
        .map_err(|e| format!("upsert format settings: {e}"))?;
    }

    Ok(FormatSettings {
        format_id,
        format_name,
        control_minutes,
        penalty_per_minute,
        dq_minutes,
        finish_cp,
        competition_start_time: normalized_start_time,
    })
}

fn resolve_start_protocol_format(
    conn: &Connection,
    format_id: Option<i64>,
) -> Result<(Option<i64>, String), String> {
    match format_id {
        Some(fid) => {
            let fname: String = conn
                .query_row(
                    "SELECT format_name FROM start_protocol_formats WHERE id = ? LIMIT 1",
                    params![fid],
                    |r| r.get(0),
                )
                .optional()
                .map_err(|e| format!("resolve format by id: {e}"))?
                .ok_or_else(|| format!("Формат id={fid} не найден"))?;
            Ok((Some(fid), fname))
        }
        None => Ok((None, String::new())),
    }
}

fn load_start_protocol_row(conn: &Connection, entry_id: i64) -> Result<StartProtocolRow, String> {
    let missing_format_expr = r#"
        (
            sp.format_id IS NULL
            OR TRIM(IFNULL(sp.format_name, '')) = ''
        )
    "#;
    let incomplete_expr = format!(
        r#"
        (
            TRIM(IFNULL(sp.name, '')) = ''
            OR {missing_format_expr}
            OR TRIM(IFNULL(sp.gender, '')) = ''
            OR (
                TRIM(IFNULL(sp.birth_date_raw, '')) = ''
                AND TRIM(IFNULL(sp.birth_date_iso, '')) = ''
            )
        )
        "#
    );
    let query = format!(
        r#"
        SELECT
            {START_PROTOCOL_ROW_SELECT},
            {incomplete_expr} as is_incomplete,
            {missing_format_expr} as missing_format
        FROM start_protocol sp
        WHERE sp.id = ?
        "#
    );
    conn.query_row(&query, params![entry_id], |r| map_start_protocol_row(r, 13, 14))
        .map_err(|e| format!("load start protocol entry: {e}"))
}

fn next_team_id(conn: &Connection) -> Result<i64, String> {
    conn.query_row(
        "SELECT COALESCE(MAX(team_id), 0) + 1 FROM start_protocol",
        [],
        |r| r.get(0),
    )
    .map_err(|e| format!("allocate team_id: {e}"))
}

fn cleanup_singleton_teams(conn: &Connection, team_ids: &[i64]) -> Result<(), String> {
    for &team_id in team_ids {
        if team_id <= 0 {
            continue;
        }
        let size: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM start_protocol WHERE team_id = ?",
                params![team_id],
                |r| r.get(0),
            )
            .map_err(|e| format!("count team members: {e}"))?;
        if size < 2 {
            conn.execute(
                "UPDATE start_protocol SET team_id = NULL WHERE team_id = ?",
                params![team_id],
            )
            .map_err(|e| format!("clear singleton team: {e}"))?;
        }
    }
    Ok(())
}

/// Merge selected start-protocol entries into one team. Same non-empty format required.
pub fn merge_start_protocol_team(
    conn: &Connection,
    entry_ids: Vec<i64>,
) -> Result<i64, String> {
    let mut ids: Vec<i64> = entry_ids
        .into_iter()
        .filter(|id| *id > 0)
        .collect::<HashSet<_>>()
        .into_iter()
        .collect();
    ids.sort_unstable();
    if ids.len() < 2 {
        return Err("Выберите минимум двух участников одного формата".to_string());
    }

    let mut format_id: Option<i64> = None;
    let mut existing_team_ids: Vec<i64> = Vec::new();
    for id in &ids {
        let row: (Option<i64>, Option<i64>, String) = conn
            .query_row(
                "SELECT format_id, team_id, format_name FROM start_protocol WHERE id = ? LIMIT 1",
                params![id],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .optional()
            .map_err(|e| format!("load entry for team merge: {e}"))?
            .ok_or_else(|| format!("Запись стартового протокола id={id} не найдена"))?;
        let (fid, team_id, fname) = row;
        let fid = fid.filter(|v| *v > 0).ok_or_else(|| {
            format!("У участника без формата нельзя создать команду (id={id}, «{fname}»)")
        })?;
        match format_id {
            None => format_id = Some(fid),
            Some(current) if current != fid => {
                return Err(
                    "Объединять в команду можно только участников одного формата".to_string(),
                );
            }
            _ => {}
        }
        if let Some(tid) = team_id {
            if tid > 0 && !existing_team_ids.contains(&tid) {
                existing_team_ids.push(tid);
            }
        }
    }

    let team_id = if let Some(first) = existing_team_ids.first().copied() {
        // Prefer an already used team id; pull in members of all selected teams.
        first
    } else {
        next_team_id(conn)?
    };

    // If merging several existing teams, reassign everyone from those teams too.
    if existing_team_ids.len() > 1 {
        for &old_tid in &existing_team_ids {
            if old_tid == team_id {
                continue;
            }
            conn.execute(
                "UPDATE start_protocol SET team_id = ? WHERE team_id = ?",
                params![team_id, old_tid],
            )
            .map_err(|e| format!("merge existing teams: {e}"))?;
        }
    }

    for id in &ids {
        conn.execute(
            "UPDATE start_protocol SET team_id = ? WHERE id = ?",
            params![team_id, id],
        )
        .map_err(|e| format!("assign team_id: {e}"))?;
    }

    Ok(team_id)
}

pub fn leave_start_protocol_team(conn: &Connection, entry_id: i64) -> Result<(), String> {
    let team_id: Option<i64> = conn
        .query_row(
            "SELECT team_id FROM start_protocol WHERE id = ? LIMIT 1",
            params![entry_id],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("load team for leave: {e}"))?
        .ok_or_else(|| format!("Запись стартового протокола id={entry_id} не найдена"))?;

    let Some(team_id) = team_id.filter(|v| *v > 0) else {
        return Ok(());
    };

    conn.execute(
        "UPDATE start_protocol SET team_id = NULL WHERE id = ?",
        params![entry_id],
    )
    .map_err(|e| format!("leave team: {e}"))?;
    cleanup_singleton_teams(conn, &[team_id])?;
    Ok(())
}

pub fn leave_start_protocol_teams(
    conn: &Connection,
    entry_ids: Vec<i64>,
) -> Result<i64, String> {
    let mut left = 0_i64;
    let mut seen = HashSet::new();
    for entry_id in entry_ids {
        if entry_id <= 0 || !seen.insert(entry_id) {
            continue;
        }
        leave_start_protocol_team(conn, entry_id)?;
        left += 1;
    }
    Ok(left)
}

pub fn dissolve_start_protocol_team(conn: &Connection, team_id: i64) -> Result<(), String> {
    if team_id <= 0 {
        return Err("Некорректный team_id".to_string());
    }
    let changed = conn
        .execute(
            "UPDATE start_protocol SET team_id = NULL WHERE team_id = ?",
            params![team_id],
        )
        .map_err(|e| format!("dissolve team: {e}"))?;
    if changed == 0 {
        return Err(format!("Команда #{team_id} не найдена"));
    }
    Ok(())
}

pub fn add_start_protocol_entry(
    conn: &Connection,
    participant_id: String,
    name: String,
    format_id: Option<i64>,
    gender: Option<String>,
    birth_date_raw: Option<String>,
) -> Result<StartProtocolRow, String> {
    let pid = participant_id.trim().to_string();
    let name = name.trim().to_string();
    if pid.is_empty() || name.is_empty() {
        return Err("Поля id и имя обязательны".to_string());
    }

    let (resolved_format_id, format_name) = resolve_start_protocol_format(conn, format_id)?;
    let gender_clean = gender.and_then(|g| {
        let t = g.trim().to_string();
        if t.is_empty() {
            None
        } else {
            Some(t)
        }
    });
    let birth_raw_clean = birth_date_raw.and_then(|d| {
        let t = d.trim().to_string();
        if t.is_empty() {
            None
        } else {
            Some(t)
        }
    });
    let birth_iso = birth_raw_clean
        .as_ref()
        .and_then(|d| parse_birth_date_ru(d));

    conn.execute(
        r#"
        INSERT INTO start_protocol(
            participant_id, name, format_id, format_name, gender, birth_date_raw, birth_date_iso, source_row
        ) VALUES(?, ?, ?, ?, ?, ?, ?, NULL)
        "#,
        params![
            pid,
            name,
            resolved_format_id,
            format_name,
            gender_clean,
            birth_raw_clean,
            birth_iso
        ],
    )
    .map_err(|e| {
        let msg = e.to_string();
        if msg.contains("UNIQUE") {
            format!("Уже есть запись с номером «{pid}» и именем «{name}»")
        } else {
            format!("add start protocol entry: {e}")
        }
    })?;

    let entry_id = conn.last_insert_rowid();
    load_start_protocol_row(conn, entry_id)
}

pub fn update_start_protocol_entry(
    conn: &Connection,
    entry_id: i64,
    participant_id: String,
    name: String,
    format_id: Option<i64>,
    gender: Option<String>,
    birth_date_raw: Option<String>,
) -> Result<StartProtocolRow, String> {
    let pid = participant_id.trim().to_string();
    let name = name.trim().to_string();
    if pid.is_empty() || name.is_empty() {
        return Err("Поля id и имя обязательны".to_string());
    }
    let exists = conn
        .query_row(
            "SELECT format_id, team_id FROM start_protocol WHERE id = ? LIMIT 1",
            params![entry_id],
            |r| Ok((r.get::<_, Option<i64>>(0)?, r.get::<_, Option<i64>>(1)?)),
        )
        .optional()
        .map_err(|e| format!("check start protocol entry exists: {e}"))?;
    let Some((old_format_id, old_team_id)) = exists else {
        return Err(format!("Запись стартового протокола id={entry_id} не найдена"));
    };

    let (resolved_format_id, format_name) = resolve_start_protocol_format(conn, format_id)?;

    let gender_clean = gender.and_then(|g| {
        let t = g.trim().to_string();
        if t.is_empty() { None } else { Some(t) }
    });
    let birth_raw_clean = birth_date_raw.and_then(|d| {
        let t = d.trim().to_string();
        if t.is_empty() { None } else { Some(t) }
    });
    let birth_iso = birth_raw_clean
        .as_ref()
        .and_then(|d| parse_birth_date_ru(d));

    conn.execute(
        r#"
        UPDATE start_protocol
        SET participant_id = ?, name = ?, format_id = ?, format_name = ?, gender = ?, birth_date_raw = ?, birth_date_iso = ?
        WHERE id = ?
        "#,
        params![
            pid,
            name,
            resolved_format_id,
            format_name,
            gender_clean,
            birth_raw_clean,
            birth_iso,
            entry_id
        ],
    )
    .map_err(|e| {
        let msg = e.to_string();
        if msg.contains("UNIQUE") {
            format!("Уже есть запись с чипом «{pid}» и именем «{name}»")
        } else {
            format!("update start protocol entry: {e}")
        }
    })?;

    if old_format_id != resolved_format_id {
        if let Some(team_id) = old_team_id.filter(|v| *v > 0) {
            conn.execute(
                "UPDATE start_protocol SET team_id = NULL WHERE id = ?",
                params![entry_id],
            )
            .map_err(|e| format!("detach from team after format change: {e}"))?;
            cleanup_singleton_teams(conn, &[team_id])?;
        }
    }

    load_start_protocol_row(conn, entry_id)
}

fn sync_start_protocol_format_ids_tx(tx: &Transaction<'_>) -> Result<(), String> {
    tx.execute(
        r#"
        INSERT OR IGNORE INTO start_protocol_formats(format_name)
        SELECT DISTINCT TRIM(format_name)
        FROM start_protocol
        WHERE TRIM(format_name) <> ''
        "#,
        [],
    )
    .map_err(|e| format!("sync formats from protocol: {e}"))?;
    tx.execute(
        r#"
        UPDATE start_protocol
        SET format_id = (
            SELECT f.id FROM start_protocol_formats f
            WHERE f.format_name = start_protocol.format_name
        )
        WHERE TRIM(IFNULL(format_name, '')) <> ''
        "#,
        [],
    )
    .map_err(|e| format!("backfill format_id: {e}"))?;
    tx.execute(
        r#"
        UPDATE start_protocol
        SET format_id = NULL
        WHERE TRIM(IFNULL(format_name, '')) = ''
        "#,
        [],
    )
    .map_err(|e| format!("clear empty format_id: {e}"))?;
    Ok(())
}

fn ensure_format_id_tx(tx: &Transaction<'_>, format_name: &str) -> Result<i64, String> {
    let name = format_name.trim();
    if name.is_empty() {
        return Err("Имя формата не может быть пустым".to_string());
    }
    tx.execute(
        "INSERT OR IGNORE INTO start_protocol_formats(format_name) VALUES(?)",
        params![name],
    )
    .map_err(|e| format!("ensure format: {e}"))?;
    tx.query_row(
        "SELECT id FROM start_protocol_formats WHERE format_name = ? LIMIT 1",
        params![name],
        |r| r.get(0),
    )
    .map_err(|e| format!("load format id: {e}"))
}

pub fn find_result_id(
    conn: &Connection,
    participant_id: &str,
    name: &str,
) -> Result<Option<i64>, String> {
    conn.query_row(
        r#"
        SELECT id
        FROM results
        WHERE participant_id = ? AND name = ?
        ORDER BY id ASC
        LIMIT 1
        "#,
        params![participant_id, name],
        |r| r.get(0),
    )
    .optional()
    .map_err(|e| format!("find result id: {e}"))
}

pub fn query_participant_details(
    conn: &mut Connection,
    result_id: i64,
) -> Result<ParticipantDetails, String> {
    let tx = conn
        .transaction()
        .map_err(|e| format!("start participant details transaction: {e}"))?;

    let age_sql = match competition_year(&tx) {
        Some(year) => sql_person_age("sp", year),
        None => "NULL".to_string(),
    };
    let effective_p = sql_effective_course_name("p");
    let effective_p2 = sql_effective_course_name("p2");
    let mut result = tx
        .query_row(
            &format!(
                r#"
            SELECT
                   results.id,
                   results.finish_participant_id,
                   results.chip_raw_id,
                   results.participant_id,
                   results.name,
                   results.status,
                   sp.format_id,
                   COALESCE(
                       CASE
                           WHEN (SELECT value FROM settings WHERE key = 'sport_kind') = 'orient'
                           THEN {effective_p}
                           ELSE NULL
                       END,
                       IFNULL(sp.format_name, '')
                   ) AS format_name,
                   sp.team_id,
                   CASE
                       WHEN sp.team_id IS NULL THEN 0
                       ELSE (
                           SELECT COUNT(*) FROM start_protocol tsz
                           WHERE tsz.team_id = sp.team_id
                       )
                   END AS team_size,
                   CASE
                       WHEN sp.team_id IS NULL THEN ''
                       ELSE COALESCE((
                           SELECT GROUP_CONCAT(tsz.participant_id || ' ' || tsz.name, ' · ')
                           FROM start_protocol tsz
                           WHERE tsz.team_id = sp.team_id
                             AND NOT (
                                 tsz.participant_id = sp.participant_id
                                 AND tsz.name = sp.name
                             )
                       ), '')
                   END AS teammates,
                   sp.gender,
                   {age_sql} AS age,
                   EXISTS(
                     SELECT 1 FROM corrections c
                     WHERE c.finish_participant_id = results.finish_participant_id
                   ) OR EXISTS(
                     SELECT 1 FROM manual_corrections mc
                     WHERE mc.finish_participant_id = results.finish_participant_id
                   ) AS has_personal_corrections,
                   results.points_raw, results.penalty_points, results.points_final,
                   results.elapsed_seconds, results.delay_seconds, results.penalty_minutes,
                   results.diagnostics_json, results.computed_at,
                   ranked.place
            FROM results
            LEFT JOIN start_protocol sp
              ON sp.participant_id = results.participant_id
             AND sp.name = results.name
            LEFT JOIN participants p
              ON p.id = results.finish_participant_id
            LEFT JOIN (
                SELECT
                    r.id AS result_id,
                    RANK() OVER (
                        PARTITION BY {effective_p2}
                        ORDER BY r.elapsed_seconds ASC, r.participant_id COLLATE NOCASE ASC
                    ) AS place
                FROM results r
                LEFT JOIN participants p2 ON p2.id = r.finish_participant_id
                WHERE r.status = 'OK'
            ) ranked ON ranked.result_id = results.id
            WHERE results.id = ?
            "#
            ),
            params![result_id],
            |r| {
                Ok(ResultRow {
                    id: r.get(0)?,
                    finish_participant_id: r.get(1)?,
                    chip_raw_id: r.get(2)?,
                    participant_id: r.get(3)?,
                    name: r.get(4)?,
                    status: r.get(5)?,
                    format_id: r.get(6)?,
                    format_name: r.get(7)?,
                    team_id: r.get(8)?,
                    team_size: r.get(9)?,
                    teammates: r.get(10)?,
                    gender: r.get(11)?,
                    age: r.get(12)?,
                    has_personal_corrections: r.get(13)?,
                    has_anomalies: false,
                    anomaly_count: 0,
                    points_raw: r.get(14)?,
                    penalty_points: r.get(15)?,
                    points_final: r.get(16)?,
                    elapsed_seconds: r.get(17)?,
                    delay_seconds: r.get(18)?,
                    penalty_minutes: r.get(19)?,
                    diagnostics_json: r.get(20)?,
                    computed_at: r.get(21)?,
                    place: r.get(22)?,
                })
            },
        )
        .optional()
        .map_err(|e| format!("query result {result_id}: {e}"))?
        .ok_or_else(|| format!("result id {result_id} not found"))?;

    let bib_id = result.participant_id.clone();
    let result_name = result.name.clone();
    let finish_id = result.finish_participant_id;
    let chip_raw_id = result.chip_raw_id.clone();

    let finish_participant = if let Some(fid) = finish_id {
        tx.query_row(
            r#"
            SELECT id, participant_id, chip_raw_id, name, start_station_id, start_time, source_row, IFNULL(course_name, '')
            FROM participants
            WHERE id = ?
            "#,
            params![fid],
            |r| {
                Ok(ParticipantMeta {
                    id: Some(r.get(0)?),
                    participant_id: r.get(1)?,
                    chip_raw_id: r.get(2)?,
                    name: r.get(3)?,
                    start_station_id: r.get(4)?,
                    start_time: r.get(5)?,
                    source_row: r.get(6)?,
                    course_name: r.get(7)?,
                })
            },
        )
        .optional()
        .map_err(|e| format!("load finish participant id {fid}: {e}"))?
    } else {
        None
    };

    // Show marks for finish-linked result rows; hide for empty no_finish placeholders.
    let owns_chip_marks = {
        let diags: Vec<String> =
            serde_json::from_str(&result.diagnostics_json).unwrap_or_default();
        finish_id.is_some() && !diags.iter().any(|d| d == "no_finish_data")
    };

    let participant = if let Some(fp) = finish_participant.as_ref() {
        ParticipantMeta {
            id: fp.id,
            chip_raw_id: fp.chip_raw_id.clone(),
            participant_id: bib_id.clone(),
            name: result_name.clone(),
            start_station_id: if owns_chip_marks {
                fp.start_station_id
            } else {
                0
            },
            start_time: if owns_chip_marks {
                fp.start_time.clone()
            } else {
                String::new()
            },
            source_row: if owns_chip_marks { fp.source_row } else { None },
            course_name: fp.course_name.clone(),
        }
    } else {
        ParticipantMeta {
            id: None,
            chip_raw_id: chip_raw_id.clone(),
            participant_id: bib_id.clone(),
            name: result_name.clone(),
            start_station_id: 0,
            start_time: String::new(),
            source_row: None,
            course_name: String::new(),
        }
    };

    let (raw_marks, corrected_marks, exclusion_rules) =
        if owns_chip_marks && finish_id.is_some() {
        let fid = finish_id.unwrap();
        let raw_marks = {
            let mut raw_stmt = tx
                .prepare(
                    r#"
                    SELECT seq, cp_number, mark_time
                    FROM marks_raw
                    WHERE finish_participant_id = ?
                    ORDER BY seq
                    "#,
                )
                .map_err(|e| format!("prepare raw marks query: {e}"))?;
            let raw_rows = raw_stmt
                .query_map(params![fid], |r| {
                    Ok(MarkRow {
                        seq: r.get(0)?,
                        cp_number: r.get(1)?,
                        mark_time: r.get(2)?,
                    })
                })
                .map_err(|e| format!("query raw marks: {e}"))?;
            let mut raw_marks = Vec::new();
            for row in raw_rows {
                raw_marks.push(row.map_err(|e| format!("read raw mark row: {e}"))?);
            }
            raw_marks
        };

        let marks = load_marks(&tx, fid)?;
        let mut corrected_marks = marks.clone();
        let mut exclusion_rules = load_leg_exclusion_rules(&tx, fid, &bib_id)?;
        apply_legacy_corrections(&tx, fid, &mut corrected_marks, &mut exclusion_rules)?;
        let manual_corrections = load_manual_corrections(&tx, fid)?;
        apply_manual_mark_corrections(&mut corrected_marks, &manual_corrections);
        corrected_marks.sort_by_key(|m| (m.mark_time, m.seq));
        (raw_marks, corrected_marks, exclusion_rules)
    } else {
        (Vec::new(), Vec::new(), Vec::new())
    };

    let corrections = if owns_chip_marks {
        if let Some(fid) = finish_id {
        let mut correction_stmt = tx
            .prepare(
                r#"
                SELECT id, finish_participant_id, correction_type, payload_json, created_at, 'legacy_corrections' as source_table
                FROM corrections
                WHERE finish_participant_id = ?
                UNION ALL
                SELECT id, finish_participant_id, correction_type, payload_json, created_at, 'manual_corrections' as source_table
                FROM manual_corrections
                WHERE finish_participant_id = ? OR finish_participant_id IS NULL
                ORDER BY id
                "#,
            )
            .map_err(|e| format!("prepare corrections list query: {e}"))?;
        let correction_rows = correction_stmt
            .query_map(params![fid, fid], |r| {
                let payload_raw: String = r.get(3)?;
                let payload: Value = serde_json::from_str(&payload_raw).map_err(|e| {
                    rusqlite::Error::FromSqlConversionFailure(
                        3,
                        rusqlite::types::Type::Text,
                        Box::new(std::io::Error::new(std::io::ErrorKind::InvalidData, e)),
                    )
                })?;
                let source_table: String = r.get(5)?;
                let finish_scope: Option<i64> = r.get(1)?;
                Ok(CorrectionRow {
                    id: r.get(0)?,
                    finish_participant_id: finish_scope,
                    scope: if finish_scope.is_some() {
                        "personal".to_string()
                    } else {
                        "global".to_string()
                    },
                    source_table,
                    correction_type: r.get(2)?,
                    payload,
                    created_at: r.get(4)?,
                })
            })
            .map_err(|e| format!("query corrections list: {e}"))?;
        let mut corrections = Vec::new();
        for row in correction_rows {
            corrections.push(row.map_err(|e| format!("read correction row: {e}"))?);
        }
        corrections
        } else {
            Vec::new()
        }
    } else {
        Vec::new()
    };

    let all_anomalies = if owns_chip_marks {
        if let Some(fid) = finish_id {
        let mut anomalies_stmt = tx
            .prepare(
                r#"
                SELECT pa.anomaly_type, pa.title, pa.details, pa.payload_json, mc.id, mc.created_at
                FROM participant_anomalies pa
                LEFT JOIN manual_corrections mc
                  ON mc.finish_participant_id = pa.finish_participant_id
                 AND (
                    (mc.correction_type = 'anomaly_day_shift_24h' AND pa.anomaly_type = 'start_time_day_shift')
                    OR (
                        mc.correction_type = 'set_start_mark'
                        AND pa.anomaly_type IN ('start_punch_missing', 'start_punch_after_finish')
                    )
                 )
                WHERE pa.finish_participant_id = ?
                ORDER BY pa.id
                "#,
            )
            .map_err(|e| format!("prepare participant anomalies query: {e}"))?;
        let anomalies_rows = anomalies_stmt
            .query_map(params![fid], |r| {
                let payload_raw: String = r.get(3)?;
                let payload: Value = serde_json::from_str(&payload_raw).map_err(|e| {
                    rusqlite::Error::FromSqlConversionFailure(
                        3,
                        rusqlite::types::Type::Text,
                        Box::new(std::io::Error::new(std::io::ErrorKind::InvalidData, e)),
                    )
                })?;
                let resolved_correction_id: Option<i64> = r.get(4)?;
                let resolved_at: Option<String> = r.get(5)?;
                Ok(ParticipantAnomaly {
                    anomaly_type: r.get(0)?,
                    title: r.get(1)?,
                    details: r.get(2)?,
                    payload,
                    resolved: resolved_correction_id.is_some(),
                    resolved_correction_id,
                    resolved_at,
                })
            })
            .map_err(|e| format!("query participant anomalies: {e}"))?;
        let mut anomalies = Vec::new();
        for row in anomalies_rows {
            anomalies.push(row.map_err(|e| format!("read participant anomaly row: {e}"))?);
        }
        anomalies
        } else {
            Vec::new()
        }
    } else {
        Vec::new()
    };
    let mut anomalies = Vec::new();
    let mut anomalies_history = Vec::new();
    for anomaly in all_anomalies {
        if anomaly.resolved {
            anomalies_history.push(anomaly);
        } else {
            anomalies.push(anomaly);
        }
    }
    result.has_anomalies = !anomalies.is_empty();
    result.anomaly_count = anomalies.len() as i64;
    tx.rollback()
        .map_err(|e| format!("rollback participant details transaction: {e}"))?;

    Ok(ParticipantDetails {
        participant,
        result: Some(result),
        anomalies,
        anomalies_history,
        raw_marks,
        corrected_marks: corrected_marks
            .into_iter()
            .map(|m| MarkRow {
                seq: m.seq,
                cp_number: m.cp_number,
                mark_time: m.mark_time.format("%Y-%m-%d %H:%M:%S").to_string(),
            })
            .collect(),
        excluded_legs: exclusion_rules
            .into_iter()
            .map(|r| ExcludedLegRow {
                from_cp: r.from_cp,
                to_cp: r.to_cp,
                direction: r.direction,
                apply_mode: r.apply_mode,
                max_leg_seconds: r.max_leg_seconds,
                source: r.source,
            })
            .collect(),
        corrections,
    })
}

fn parse_finish_participant_id(raw: &str) -> Result<i64, String> {
    raw.trim()
        .parse::<i64>()
        .map_err(|_| format!("Invalid finish participant id '{raw}'"))
}

fn require_finish_participant(conn: &Connection, finish_participant_id: i64) -> Result<(), String> {
    let exists = conn
        .query_row(
            "SELECT 1 FROM participants WHERE id = ? LIMIT 1",
            params![finish_participant_id],
            |_| Ok(()),
        )
        .optional()
        .map_err(|e| format!("check finish participant exists: {e}"))?;
    if exists.is_none() {
        return Err(format!(
            "Finish participant id {finish_participant_id} not found"
        ));
    }
    Ok(())
}

fn validate_rule_fields(
    direction: &str,
    apply_mode: &str,
    max_leg_seconds: Option<i64>,
) -> Result<(), String> {
    if direction != "forward" && direction != "reverse" && direction != "both" {
        return Err("direction must be one of: forward, reverse, both".to_string());
    }
    if apply_mode != "once" && apply_mode != "always" {
        return Err("apply_mode must be one of: once, always".to_string());
    }
    if let Some(v) = max_leg_seconds {
        if v < 0 {
            return Err("max_leg_seconds must be >= 0".to_string());
        }
    }
    Ok(())
}

fn prepare_orient_exclusion_fields(
    conn: &Connection,
    from_cp: i64,
    to_cp: i64,
    course_id: Option<i64>,
    exclude_rule_id: Option<i64>,
) -> Result<(i64, i64, String, String, i64), String> {
    if from_cp <= 0 || to_cp <= 0 {
        return Err("Выберите перегон".to_string());
    }
    if from_cp == to_cp {
        return Err("Перегон должен быть между разными КП".to_string());
    }
    let course_id = course_id.ok_or_else(|| "Выберите дистанцию".to_string())?;
    require_course_id(conn, course_id)?;
    let settings = get_settings(conn)?;
    let controls = load_course_controls_by_id(conn, course_id)?;
    let removed = load_global_removed_course_cps(conn)?;
    let legs = orient_course_legs(&controls, settings.start_cp, settings.finish_cp, &removed);
    if !legs.iter().any(|(from, to)| *from == from_cp && *to == to_cp) {
        return Err(format!(
            "Перегона {from_cp} → {to_cp} нет на выбранной дистанции"
        ));
    }
    let dup: i64 = match exclude_rule_id {
        Some(id) => conn.query_row(
            r#"
            SELECT COUNT(*) FROM leg_exclusion_rules
            WHERE finish_participant_id IS NULL
              AND format_id IS NULL
              AND course_id = ?
              AND from_cp = ?
              AND to_cp = ?
              AND id != ?
            "#,
            params![course_id, from_cp, to_cp, id],
            |r| r.get(0),
        ),
        None => conn.query_row(
            r#"
            SELECT COUNT(*) FROM leg_exclusion_rules
            WHERE finish_participant_id IS NULL
              AND format_id IS NULL
              AND course_id = ?
              AND from_cp = ?
              AND to_cp = ?
            "#,
            params![course_id, from_cp, to_cp],
            |r| r.get(0),
        ),
    }
    .map_err(|e| format!("check duplicate orient exclusion: {e}"))?;
    if dup > 0 {
        return Err(format!(
            "Перегон {from_cp} → {to_cp} на этой дистанции уже исключён"
        ));
    }
    Ok((
        from_cp,
        to_cp,
        "forward".to_string(),
        "always".to_string(),
        course_id,
    ))
}

fn participant_effective_course(
    conn: &Connection,
    finish_id: i64,
) -> Result<(String, String), String> {
    let stored: String = conn
        .query_row(
            "SELECT TRIM(IFNULL(course_name, '')) FROM participants WHERE id = ?",
            params![finish_id],
            |r| r.get(0),
        )
        .map_err(|e| format!("load participant course: {e}"))?;
    let corrections = load_manual_corrections(conn, finish_id)?;
    Ok((
        stored.clone(),
        effective_course_name(&stored, finish_id, &corrections),
    ))
}

pub fn add_cp_correction(
    conn: &Connection,
    participant_id: &str,
    cp_number: i64,
    _mark_time: &str,
) -> Result<(), String> {
    let finish_id = parse_finish_participant_id(participant_id)?;
    require_finish_participant(conn, finish_id)?;
    let settings = get_settings(conn)?;
    if settings.sport_kind == "orient" {
        let course_name = participant_effective_course(conn, finish_id)?.1;
        let removed_cps = load_global_removed_course_cps(conn)?;
        let controls = filter_course_cps(
            &load_course_controls(conn, &course_name)?,
            &removed_cps,
        );
        if controls.is_empty() {
            return Err("У участника нет дистанции или в ней нет КП".to_string());
        }
        let on_course = controls.iter().filter(|cp| **cp == cp_number).count();
        if on_course == 0 {
            return Err(format!(
                "КП {cp_number} не входит в дистанцию «{course_name}»"
            ));
        }
        let marks = load_marks(conn, finish_id)?;
        let punched = marks.iter().filter(|m| m.cp_number == cp_number).count();
        let already_added: i64 = conn
            .query_row(
                r#"
                SELECT COUNT(*)
                FROM manual_corrections
                WHERE finish_participant_id = ?
                  AND correction_type = 'add_cp'
                  AND json_extract(payload_json, '$.cp_number') = ?
                "#,
                params![finish_id, cp_number],
                |r| r.get(0),
            )
            .map_err(|e| format!("count add_cp corrections: {e}"))?;
        let used = punched + already_added as usize;
        if used >= on_course {
            return Err(format!(
                "КП {cp_number} на дистанции «{course_name}» встречается {on_course} раз, уже учтено {used}."
            ));
        }
    } else {
        let duplicate: Option<i64> = conn
            .query_row(
                r#"
                SELECT id
                FROM manual_corrections
                WHERE finish_participant_id = ?
                  AND correction_type = 'add_cp'
                  AND json_extract(payload_json, '$.cp_number') = ?
                LIMIT 1
                "#,
                params![finish_id, cp_number],
                |r| r.get(0),
            )
            .optional()
            .map_err(|e| format!("check duplicate add_cp correction: {e}"))?;
        if duplicate.is_some() {
            return Err(format!(
                "Для участника {finish_id} корректировка добавления КП {cp_number} уже существует"
            ));
        }
    }
    conn.execute(
        r#"
        INSERT INTO manual_corrections(finish_participant_id, correction_type, payload_json)
        VALUES(?, 'add_cp', ?)
        "#,
        params![
            finish_id,
            json!({
                "cp_number": cp_number
            })
            .to_string()
        ],
    )
    .map_err(|e| format!("insert personal add_cp correction: {e}"))?;
    Ok(())
}

pub fn assign_course_correction(
    conn: &Connection,
    participant_id: &str,
    course_name: &str,
) -> Result<(), String> {
    let settings = get_settings(conn)?;
    if settings.sport_kind != "orient" {
        return Err("Смена дистанции доступна только в ориентировании".to_string());
    }
    let finish_id = parse_finish_participant_id(participant_id)?;
    require_finish_participant(conn, finish_id)?;
    let target_raw = course_name.trim();
    if target_raw.is_empty() {
        return Err("Укажите дистанцию".to_string());
    }
    let courses = list_courses(conn)?;
    let Some(canonical) = courses
        .iter()
        .find(|c| course_key(&c.name) == course_key(target_raw))
        .map(|c| c.name.clone())
    else {
        return Err(format!("Дистанция «{target_raw}» не найдена"));
    };
    let bib: String = conn
        .query_row(
            "SELECT participant_id FROM participants WHERE id = ?",
            params![finish_id],
            |r| r.get(0),
        )
        .map_err(|e| format!("load finish bib: {e}"))?;
    let (stored, current) = participant_effective_course(conn, finish_id)?;
    if course_key(&canonical) == course_key(&stored) {
        conn.execute(
            r#"
            DELETE FROM manual_corrections
            WHERE finish_participant_id = ?
              AND correction_type = 'assign_course'
            "#,
            params![finish_id],
        )
        .map_err(|e| format!("clear assign_course: {e}"))?;
        return Ok(());
    }
    if course_key(&canonical) == course_key(&current) {
        return Ok(());
    }
    let others: Vec<(i64, String)> = {
        let mut stmt = conn
            .prepare(
                r#"
                SELECT id, IFNULL(name, '')
                FROM participants
                WHERE participant_id = ?
                  AND id != ?
                "#,
            )
            .map_err(|e| format!("prepare course conflict query: {e}"))?;
        let rows = stmt
            .query_map(params![bib, finish_id], |r| {
                Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?))
            })
            .map_err(|e| format!("query course conflicts: {e}"))?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row.map_err(|e| format!("read course conflict: {e}"))?);
        }
        out
    };
    for (other_id, other_name) in others {
        let other_effective = participant_effective_course(conn, other_id)?.1;
        if course_key(&other_effective) == course_key(&canonical) {
            return Err(format!(
                "У номера {bib} уже есть финиш на дистанции «{canonical}» ({other_name}). Оставьте оба результата или сначала разберите дубль."
            ));
        }
    }
    conn.execute(
        r#"
        DELETE FROM manual_corrections
        WHERE finish_participant_id = ?
          AND correction_type = 'assign_course'
        "#,
        params![finish_id],
    )
    .map_err(|e| format!("replace assign_course: {e}"))?;
    conn.execute(
        r#"
        INSERT INTO manual_corrections(finish_participant_id, correction_type, payload_json)
        VALUES(?, 'assign_course', ?)
        "#,
        params![
            finish_id,
            json!({
                "from": stored,
                "to": canonical
            })
            .to_string()
        ],
    )
    .map_err(|e| format!("insert assign_course: {e}"))?;
    Ok(())
}

fn map_coord_distance(a: (f64, f64), b: (f64, f64)) -> f64 {
    let dx = a.0 - b.0;
    let dy = a.1 - b.1;
    (dx * dx + dy * dy).sqrt()
}

fn path_cost_via(
    prev: Option<(f64, f64)>,
    via: (f64, f64),
    next: Option<(f64, f64)>,
) -> f64 {
    let mut cost = 0.0;
    if let Some(p) = prev {
        cost += map_coord_distance(p, via);
    }
    if let Some(n) = next {
        cost += map_coord_distance(via, n);
    }
    cost
}

fn load_cp_map_positions(conn: &Connection) -> Result<HashMap<i64, (f64, f64)>, String> {
    let mut out: HashMap<i64, (f64, f64)> = HashMap::new();
    let mut stmt = conn
        .prepare(
            r#"
            SELECT cp_number, map_x, map_y
            FROM cp_legends
            WHERE map_x IS NOT NULL AND map_y IS NOT NULL
            "#,
        )
        .map_err(|e| format!("prepare cp map positions: {e}"))?;
    let rows = stmt
        .query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, f64>(1)?, r.get::<_, f64>(2)?)))
        .map_err(|e| format!("query cp map positions: {e}"))?;
    for row in rows {
        let (cp, x, y) = row.map_err(|e| format!("read cp map position: {e}"))?;
        out.insert(cp, (x, y));
    }

    let special = get_course_map_special_points(conn)?;
    if let (Some(cp), Some(x), Some(y)) =
        (special.start_cp, special.start_map_x, special.start_map_y)
    {
        out.entry(cp).or_insert((x, y));
    }
    if let (Some(x), Some(y)) = (special.finish_map_x, special.finish_map_y) {
        out.entry(special.finish_cp).or_insert((x, y));
    }
    Ok(out)
}

fn nearest_mapped_neighbor(
    marks: &[Mark],
    index: usize,
    direction: i32,
    positions: &HashMap<i64, (f64, f64)>,
) -> Option<(f64, f64)> {
    if direction < 0 {
        for m in marks[..index].iter().rev() {
            if let Some(pos) = positions.get(&m.cp_number) {
                return Some(*pos);
            }
        }
    } else {
        for m in marks.iter().skip(index + 1) {
            if let Some(pos) = positions.get(&m.cp_number) {
                return Some(*pos);
            }
        }
    }
    None
}

fn classify_remap_confidence(
    cost_keep: f64,
    cost_remap: f64,
    has_both_sides: bool,
) -> (String, String) {
    let delta = cost_keep - cost_remap;
    let relative = if cost_keep > 1e-9 {
        delta / cost_keep
    } else {
        0.0
    };
    if !has_both_sides {
        if delta > 0.04 {
            (
                "medium".to_string(),
                "Есть только один сосед с координатами; перегон короче через целевой КП"
                    .to_string(),
            )
        } else if delta < -0.04 {
            (
                "medium".to_string(),
                "Есть только один сосед с координатами; перегон короче через исходный КП"
                    .to_string(),
            )
        } else {
            (
                "low".to_string(),
                "Мало геометрии для уверенного решения — нужна ручная проверка".to_string(),
            )
        }
    } else if delta > 0.035 || relative > 0.18 {
        (
            "high".to_string(),
            format!("Путь через целевой КП заметно короче (Δ={delta:.3}, относит. {:.0}%)", relative * 100.0),
        )
    } else if delta < -0.035 || relative < -0.18 {
        (
            "high".to_string(),
            format!("Путь через исходный КП заметно короче (Δ={delta:.3}, относит. {:.0}%)", relative * 100.0),
        )
    } else if delta.abs() > 0.012 || relative.abs() > 0.07 {
        (
            "medium".to_string(),
            format!("Небольшая разница длин перегонов (Δ={delta:.3})"),
        )
    } else {
        (
            "low".to_string(),
            "Длины перегонов почти равны — нужна ручная проверка".to_string(),
        )
    }
}

pub fn analyze_cp_station_remap(
    conn: &Connection,
    from_cp: i64,
    to_cp: i64,
) -> Result<CpRemapAnalyzeSummary, String> {
    if from_cp <= 0 || to_cp <= 0 {
        return Err("Номера КП должны быть положительными".to_string());
    }
    if from_cp == to_cp {
        return Err("Исходный и целевой КП должны различаться".to_string());
    }

    let positions = load_cp_map_positions(conn)?;
    let missing_map_positions =
        !positions.contains_key(&from_cp) || !positions.contains_key(&to_cp);
    let from_pos = positions.get(&from_cp).copied();
    let to_pos = positions.get(&to_cp).copied();

    let mut participants_stmt = conn
        .prepare(
            r#"
            SELECT DISTINCT p.id, p.participant_id, p.name
            FROM participants p
            JOIN marks_raw m ON m.finish_participant_id = p.id
            WHERE m.cp_number = ?
            ORDER BY p.name COLLATE NOCASE, p.id
            "#,
        )
        .map_err(|e| format!("prepare participants with from_cp: {e}"))?;
    let participant_rows = participants_stmt
        .query_map(params![from_cp], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
            ))
        })
        .map_err(|e| format!("query participants with from_cp: {e}"))?;

    let mut suggestions = Vec::new();
    let mut participants_with_from_cp = 0_i64;
    let mut remap_suggested = 0_i64;
    let mut keep_suggested = 0_i64;
    let mut high_confidence_remaps = 0_i64;

    for row in participant_rows {
        let (finish_id, bib, name) =
            row.map_err(|e| format!("read participant with from_cp: {e}"))?;
        participants_with_from_cp += 1;

        let result_id: Option<i64> = conn
            .query_row(
                r#"
                SELECT id FROM results
                WHERE finish_participant_id = ?
                ORDER BY id DESC
                LIMIT 1
                "#,
                params![finish_id],
                |r| r.get(0),
            )
            .optional()
            .map_err(|e| format!("lookup result id: {e}"))?;

        let tx = conn
            .unchecked_transaction()
            .map_err(|e| format!("start remap analyze tx: {e}"))?;
        let mut marks = load_marks(&tx, finish_id)?;
        let mut exclusion_rules = load_leg_exclusion_rules(&tx, finish_id, &bib)?;
        apply_legacy_corrections(&tx, finish_id, &mut marks, &mut exclusion_rules)?;
        let manual_corrections = load_manual_corrections(&tx, finish_id)?;
        apply_manual_mark_corrections(&mut marks, &manual_corrections);
        marks.sort_by_key(|m| (m.mark_time, m.seq));
        tx.commit()
            .map_err(|e| format!("commit remap analyze tx: {e}"))?;

        let candidate_indexes: Vec<usize> = marks
            .iter()
            .enumerate()
            .filter(|(_, m)| m.cp_number == from_cp)
            .map(|(idx, _)| idx)
            .collect();

        for index in candidate_indexes {
            let mark = &marks[index];
            let mark_time = mark.mark_time.format("%Y-%m-%d %H:%M:%S").to_string();
            let already_applied = manual_corrections.iter().any(|c| {
                c.correction_type == "remap_cp"
                    && c.payload.get("from_cp").and_then(|v| v.as_i64()) == Some(from_cp)
                    && c.payload.get("to_cp").and_then(|v| v.as_i64()) == Some(to_cp)
                    && c.payload.get("mark_time").and_then(|v| v.as_str())
                        == Some(mark_time.as_str())
            });

            let prev = nearest_mapped_neighbor(&marks, index, -1, &positions);
            let next = nearest_mapped_neighbor(&marks, index, 1, &positions);
            let has_both_sides = prev.is_some() && next.is_some();

            let (suggested_cp, action, confidence, cost_keep, cost_remap, reason) =
                match (from_pos, to_pos) {
                    (Some(fp), Some(tp)) => {
                        let cost_keep = path_cost_via(prev, fp, next);
                        let cost_remap = path_cost_via(prev, tp, next);
                        let (confidence, reason) =
                            classify_remap_confidence(cost_keep, cost_remap, has_both_sides);
                        if cost_remap + 1e-9 < cost_keep {
                            (
                                to_cp,
                                "remap".to_string(),
                                confidence,
                                cost_keep,
                                cost_remap,
                                reason,
                            )
                        } else {
                            (
                                from_cp,
                                "keep".to_string(),
                                confidence,
                                cost_keep,
                                cost_remap,
                                reason,
                            )
                        }
                    }
                    _ => (
                        from_cp,
                        "keep".to_string(),
                        "low".to_string(),
                        0.0,
                        0.0,
                        "На карте нет координат исходного и/или целевого КП — автоматика недоступна"
                            .to_string(),
                    ),
                };

            if action == "remap" {
                remap_suggested += 1;
                if confidence == "high" {
                    high_confidence_remaps += 1;
                }
            } else {
                keep_suggested += 1;
            }

            let prev_cp = if index > 0 {
                Some(marks[index - 1].cp_number)
            } else {
                None
            };
            let next_cp = marks.get(index + 1).map(|m| m.cp_number);
            let context_start = index.saturating_sub(3);
            let context_end = (index + 4).min(marks.len());
            let context_cps: Vec<i64> = marks[context_start..context_end]
                .iter()
                .map(|m| m.cp_number)
                .collect();

            suggestions.push(CpRemapSuggestion {
                finish_participant_id: finish_id,
                participant_id: bib.clone(),
                name: name.clone(),
                result_id,
                mark_seq: mark.seq,
                mark_time,
                from_cp,
                to_cp,
                suggested_cp,
                action,
                confidence,
                cost_keep,
                cost_remap,
                reason,
                already_applied,
                prev_cp,
                next_cp,
                context_cps,
            });
        }
    }

    Ok(CpRemapAnalyzeSummary {
        from_cp,
        to_cp,
        participants_with_from_cp,
        suggestions,
        remap_suggested,
        keep_suggested,
        high_confidence_remaps,
        missing_map_positions,
    })
}

pub fn apply_cp_remap_corrections(
    conn: &Connection,
    items: &[CpRemapApplyItem],
) -> Result<CpRemapApplySummary, String> {
    let mut inserted = 0_i64;
    let mut skipped_existing = 0_i64;
    let mut failed = 0_i64;

    for item in items {
        if item.from_cp == item.to_cp {
            failed += 1;
            continue;
        }
        if parse_time(&item.mark_time).is_err() {
            failed += 1;
            continue;
        }
        if require_finish_participant(conn, item.finish_participant_id).is_err() {
            failed += 1;
            continue;
        }

        let duplicate: Option<i64> = conn
            .query_row(
                r#"
                SELECT id
                FROM manual_corrections
                WHERE finish_participant_id = ?
                  AND correction_type = 'remap_cp'
                  AND json_extract(payload_json, '$.from_cp') = ?
                  AND json_extract(payload_json, '$.to_cp') = ?
                  AND json_extract(payload_json, '$.mark_time') = ?
                LIMIT 1
                "#,
                params![
                    item.finish_participant_id,
                    item.from_cp,
                    item.to_cp,
                    item.mark_time
                ],
                |r| r.get(0),
            )
            .optional()
            .map_err(|e| format!("check duplicate remap_cp: {e}"))?;
        if duplicate.is_some() {
            skipped_existing += 1;
            continue;
        }

        let mut payload = json!({
            "from_cp": item.from_cp,
            "to_cp": item.to_cp,
            "mark_time": item.mark_time,
        });
        if let Some(seq) = item.seq {
            payload["seq"] = json!(seq);
        }

        match conn.execute(
            r#"
            INSERT INTO manual_corrections(finish_participant_id, correction_type, payload_json)
            VALUES(?, 'remap_cp', ?)
            "#,
            params![item.finish_participant_id, payload.to_string()],
        ) {
            Ok(_) => inserted += 1,
            Err(_) => failed += 1,
        }
    }

    Ok(CpRemapApplySummary {
        inserted,
        skipped_existing,
        failed,
    })
}

pub fn add_anomaly_day_shift_correction(conn: &Connection, participant_id: &str) -> Result<(), String> {
    let finish_id = parse_finish_participant_id(participant_id)?;
    require_finish_participant(conn, finish_id)?;
    let anomaly_exists: Option<i64> = conn
        .query_row(
            r#"
            SELECT id
            FROM participant_anomalies
            WHERE finish_participant_id = ?
              AND anomaly_type = 'start_time_day_shift'
            LIMIT 1
            "#,
            params![finish_id],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("check participant anomaly exists: {e}"))?;
    if anomaly_exists.is_none() {
        return Err(
            "Для участника нет аномалии 'сдвиг даты старта'. Запустите поиск аномалий."
                .to_string(),
        );
    }
    let duplicate: Option<i64> = conn
        .query_row(
            r#"
            SELECT id
            FROM manual_corrections
            WHERE finish_participant_id = ?
              AND correction_type = 'anomaly_day_shift_24h'
            LIMIT 1
            "#,
            params![finish_id],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("check duplicate anomaly correction: {e}"))?;
    if duplicate.is_some() {
        return Err("Корректировка аномалии (-24ч) уже существует для этого участника".to_string());
    }
    conn.execute(
        r#"
        INSERT INTO manual_corrections(finish_participant_id, correction_type, payload_json)
        VALUES(?, 'anomaly_day_shift_24h', ?)
        "#,
        params![
            finish_id,
            json!({
                "seconds": 86400,
                "anomaly_type": "start_time_day_shift"
            })
            .to_string()
        ],
    )
    .map_err(|e| format!("insert anomaly correction: {e}"))?;
    Ok(())
}

pub fn set_start_mark_correction(
    conn: &Connection,
    participant_id: &str,
    mark_time: String,
) -> Result<(), String> {
    let finish_id = parse_finish_participant_id(participant_id)?;
    require_finish_participant(conn, finish_id)?;
    let settings = get_settings(conn)?;
    if settings.sport_kind != "orient" {
        return Err("Правка стартовой отметки доступна только для заданного направления.".to_string());
    }
    let Some(start_cp) = settings.start_cp else {
        return Err("Не задана стартовая станция в настройках.".to_string());
    };
    let anomaly_exists: Option<i64> = conn
        .query_row(
            r#"
            SELECT id
            FROM participant_anomalies
            WHERE finish_participant_id = ?
              AND anomaly_type IN ('start_punch_missing', 'start_punch_after_finish')
            LIMIT 1
            "#,
            params![finish_id],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("check start punch anomaly exists: {e}"))?;
    if anomaly_exists.is_none() {
        return Err(
            "Для участника нет аномалии стартовой отметки. Запустите поиск аномалий.".to_string(),
        );
    }
    let parsed = parse_time(mark_time.trim())
        .map_err(|_| "Некорректное время старта. Ожидается YYYY-MM-DD HH:MM:SS.".to_string())?;
    let mark_time = parsed.format("%Y-%m-%d %H:%M:%S").to_string();
    let payload = json!({
        "cp_number": start_cp,
        "mark_time": mark_time
    })
    .to_string();
    let existing: Option<i64> = conn
        .query_row(
            r#"
            SELECT id
            FROM manual_corrections
            WHERE finish_participant_id = ?
              AND correction_type = 'set_start_mark'
            LIMIT 1
            "#,
            params![finish_id],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("check existing set_start_mark: {e}"))?;
    if let Some(id) = existing {
        conn.execute(
            "UPDATE manual_corrections SET payload_json = ? WHERE id = ?",
            params![payload, id],
        )
        .map_err(|e| format!("update set_start_mark correction: {e}"))?;
    } else {
        conn.execute(
            r#"
            INSERT INTO manual_corrections(finish_participant_id, correction_type, payload_json)
            VALUES(?, 'set_start_mark', ?)
            "#,
            params![finish_id, payload],
        )
        .map_err(|e| format!("insert set_start_mark correction: {e}"))?;
    }
    Ok(())
}

pub fn add_anomaly_day_shift_corrections_for_all(
    conn: &Connection,
) -> Result<BulkAnomalyCorrectionSummary, String> {
    let participants_with_anomaly: i64 = conn
        .query_row(
            r#"
            SELECT COUNT(*)
            FROM (
                SELECT DISTINCT finish_participant_id
                FROM participant_anomalies
                WHERE anomaly_type = 'start_time_day_shift'
            )
            "#,
            [],
            |r| r.get(0),
        )
        .map_err(|e| format!("count participants with anomaly: {e}"))?;
    let payload = json!({
        "seconds": 86400,
        "anomaly_type": "start_time_day_shift"
    })
    .to_string();
    let inserted = conn
        .execute(
            r#"
            INSERT INTO manual_corrections(finish_participant_id, correction_type, payload_json)
            SELECT a.finish_participant_id, 'anomaly_day_shift_24h', ?
            FROM (
                SELECT DISTINCT finish_participant_id
                FROM participant_anomalies
                WHERE anomaly_type = 'start_time_day_shift'
            ) a
            WHERE NOT EXISTS (
                SELECT 1
                FROM manual_corrections mc
                WHERE mc.finish_participant_id = a.finish_participant_id
                  AND mc.correction_type = 'anomaly_day_shift_24h'
            )
            "#,
            params![payload],
        )
        .map_err(|e| format!("insert bulk anomaly corrections: {e}"))? as i64;
    if inserted == 0 {
        return Err(
            "Нет участников, к которым можно применить -24ч (либо аномалий нет, либо корректировки уже применены)."
                .to_string(),
        );
    }
    Ok(BulkAnomalyCorrectionSummary {
        participants_with_anomaly,
        inserted,
        already_corrected: (participants_with_anomaly - inserted).max(0),
    })
}

pub fn rollback_anomaly_day_shift_corrections_for_all(
    conn: &Connection,
) -> Result<BulkAnomalyRollbackSummary, String> {
    let removed = conn
        .execute(
            "DELETE FROM manual_corrections WHERE correction_type = 'anomaly_day_shift_24h'",
            [],
        )
        .map_err(|e| format!("delete bulk anomaly corrections: {e}"))? as i64;
    if removed == 0 {
        return Err("Нет примененных корректировок -24ч для отката.".to_string());
    }
    Ok(BulkAnomalyRollbackSummary { removed })
}

pub fn get_anomaly_bulk_actions_state(conn: &Connection) -> Result<AnomalyBulkActionsState, String> {
    let available_apply_count: i64 = conn
        .query_row(
            r#"
            SELECT COUNT(*)
            FROM (
                SELECT DISTINCT a.finish_participant_id
                FROM participant_anomalies a
                WHERE a.anomaly_type = 'start_time_day_shift'
                  AND NOT EXISTS (
                    SELECT 1
                    FROM manual_corrections mc
                    WHERE mc.finish_participant_id = a.finish_participant_id
                      AND mc.correction_type = 'anomaly_day_shift_24h'
                  )
            )
            "#,
            [],
            |r| r.get(0),
        )
        .map_err(|e| format!("count available anomaly apply actions: {e}"))?;
    let applied_count: i64 = conn
        .query_row(
            r#"
            SELECT COUNT(*)
            FROM (
                SELECT DISTINCT finish_participant_id
                FROM manual_corrections
                WHERE correction_type = 'anomaly_day_shift_24h'
                  AND finish_participant_id IS NOT NULL
            )
            "#,
            [],
            |r| r.get(0),
        )
        .map_err(|e| format!("count applied anomaly corrections: {e}"))?;
    Ok(AnomalyBulkActionsState {
        can_apply_day_shift_24h: available_apply_count > 0,
        can_rollback_day_shift_24h: applied_count > 0,
        available_apply_count,
        applied_count,
    })
}

pub fn remove_cp_correction(
    conn: &Connection,
    participant_id: Option<String>,
    cp_number: i64,
    remove_mode: String,
    participant_scope: String,
) -> Result<(), String> {
    let scoped_finish_id = if participant_scope == "one" {
        let raw = participant_id
            .filter(|x| !x.trim().is_empty())
            .ok_or_else(|| {
                "finish participant id is required for participant_scope=one".to_string()
            })?;
        let finish_id = parse_finish_participant_id(&raw)?;
        require_finish_participant(conn, finish_id)?;
        Some(finish_id)
    } else if participant_scope == "all" {
        None
    } else {
        return Err("participant_scope must be one of: one, all".to_string());
    };
    let settings = get_settings(conn)?;
    let remove_mode = if settings.sport_kind == "orient" {
        if participant_scope != "all" {
            return Err(
                "В заданном направлении КП снимается только для всех участников — в настройках."
                    .to_string(),
            );
        }
        if !cp_exists_on_any_course(conn, cp_number)? {
            return Err(format!(
                "КП {cp_number} нет ни на одной дистанции. Снять можно только существующий КП."
            ));
        }
        "from_course".to_string()
    } else if remove_mode == "remove_legs" || remove_mode == "points_only" {
        remove_mode
    } else {
        return Err("remove_mode must be one of: remove_legs, points_only".to_string());
    };
    let duplicate: Option<i64> = if let Some(finish_id) = scoped_finish_id {
        conn.query_row(
            r#"
            SELECT id
            FROM manual_corrections
            WHERE finish_participant_id = ?
              AND correction_type = 'remove_cp'
              AND json_extract(payload_json, '$.cp_number') = ?
            LIMIT 1
            "#,
            params![finish_id, cp_number],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("check duplicate personal remove_cp correction: {e}"))?
    } else {
        conn.query_row(
            r#"
            SELECT id
            FROM manual_corrections
            WHERE finish_participant_id IS NULL
              AND correction_type = 'remove_cp'
              AND json_extract(payload_json, '$.cp_number') = ?
            LIMIT 1
            "#,
            params![cp_number],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("check duplicate global remove_cp correction: {e}"))?
    };
    if duplicate.is_some() {
        return Err(format!(
            "Корректировка удаления КП {cp_number} для данной области уже существует"
        ));
    }
    conn.execute(
        r#"
        INSERT INTO manual_corrections(finish_participant_id, correction_type, payload_json)
        VALUES(?, 'remove_cp', ?)
        "#,
        params![
            scoped_finish_id,
            json!({
                "cp_number": cp_number,
                "remove_mode": remove_mode
            })
            .to_string()
        ],
    )
    .map_err(|e| format!("insert remove_cp correction: {e}"))?;
    Ok(())
}

pub fn add_exclusion_rule(
    conn: &Connection,
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
    let settings = get_settings(conn)?;
    let (from_cp, to_cp, direction, apply_mode, participant_scope, format_id, course_id) =
        if settings.sport_kind == "orient" {
            let (from_cp, to_cp, direction, apply_mode, course_id) =
                prepare_orient_exclusion_fields(conn, from_cp, to_cp, course_id, None)?;
            (
                from_cp,
                to_cp,
                direction,
                apply_mode,
                "all".to_string(),
                None,
                Some(course_id),
            )
        } else {
            (
                from_cp,
                to_cp,
                direction,
                apply_mode,
                participant_scope,
                format_id,
                None,
            )
        };
    validate_rule_fields(&direction, &apply_mode, max_leg_seconds)?;
    let (scoped_finish_id, scoped_format_id) =
        resolve_exclusion_scope(conn, &participant_scope, participant_id, format_id)?;
    conn.execute(
        r#"
        INSERT INTO leg_exclusion_rules(
            finish_participant_id, format_id, course_id, from_cp, to_cp, direction, apply_mode, max_leg_seconds
        ) VALUES(?, ?, ?, ?, ?, ?, ?, ?)
        "#,
        params![
            scoped_finish_id,
            scoped_format_id,
            course_id,
            from_cp,
            to_cp,
            direction,
            apply_mode,
            max_leg_seconds
        ],
    )
    .map_err(|e| format!("insert exclusion rule: {e}"))?;
    Ok(())
}

pub fn update_exclusion_rule(
    conn: &Connection,
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
    let settings = get_settings(conn)?;
    let (from_cp, to_cp, direction, apply_mode, participant_scope, format_id, course_id) =
        if settings.sport_kind == "orient" {
            let (from_cp, to_cp, direction, apply_mode, course_id) =
                prepare_orient_exclusion_fields(conn, from_cp, to_cp, course_id, Some(rule_id))?;
            (
                from_cp,
                to_cp,
                direction,
                apply_mode,
                "all".to_string(),
                None,
                Some(course_id),
            )
        } else {
            (
                from_cp,
                to_cp,
                direction,
                apply_mode,
                participant_scope,
                format_id,
                None,
            )
        };
    validate_rule_fields(&direction, &apply_mode, max_leg_seconds)?;
    let existing = conn
        .query_row(
            "SELECT id FROM leg_exclusion_rules WHERE id = ?",
            params![rule_id],
            |_| Ok(()),
        )
        .optional()
        .map_err(|e| format!("check rule exists: {e}"))?;
    if existing.is_none() {
        return Err(format!("Rule {rule_id} not found"));
    }
    let (scoped_finish_id, scoped_format_id) =
        resolve_exclusion_scope(conn, &participant_scope, participant_id, format_id)?;
    conn.execute(
        r#"
        UPDATE leg_exclusion_rules
        SET finish_participant_id = ?, format_id = ?, course_id = ?, from_cp = ?, to_cp = ?, direction = ?, apply_mode = ?, max_leg_seconds = ?
        WHERE id = ?
        "#,
        params![
            scoped_finish_id,
            scoped_format_id,
            course_id,
            from_cp,
            to_cp,
            direction,
            apply_mode,
            max_leg_seconds,
            rule_id
        ],
    )
    .map_err(|e| format!("update exclusion rule: {e}"))?;
    Ok(())
}

pub fn delete_exclusion_rule(conn: &Connection, rule_id: i64) -> Result<(), String> {
    let existing = conn
        .query_row(
            "SELECT id FROM leg_exclusion_rules WHERE id = ?",
            params![rule_id],
            |_| Ok(()),
        )
        .optional()
        .map_err(|e| format!("check rule exists: {e}"))?;
    if existing.is_none() {
        return Err(format!("Rule {rule_id} not found"));
    }
    conn.execute(
        "DELETE FROM leg_exclusion_rules WHERE id = ?",
        params![rule_id],
    )
    .map_err(|e| format!("delete exclusion rule: {e}"))?;
    Ok(())
}

pub fn query_exclusion_rules(
    conn: &Connection,
    participant_id: Option<String>,
    format_id: Option<i64>,
) -> Result<Vec<ExclusionRuleRow>, String> {
    let (sql, params_vec): (&str, Vec<rusqlite::types::Value>) =
        if let Some(pid) = participant_id {
            let finish_id = parse_finish_participant_id(&pid)?;
            (
                r#"
                SELECT r.id, r.finish_participant_id, r.format_id, f.format_name,
                       r.from_cp, r.to_cp, r.direction, r.apply_mode, r.max_leg_seconds, r.created_at,
                       r.course_id, c.name
                FROM leg_exclusion_rules r
                LEFT JOIN start_protocol_formats f ON f.id = r.format_id
                LEFT JOIN courses c ON c.id = r.course_id
                WHERE r.finish_participant_id = ?
                   OR (
                        r.finish_participant_id IS NULL
                        AND (
                            r.format_id IS NULL
                            OR r.format_id = (
                                SELECT sp.format_id
                                FROM start_protocol sp
                                JOIN participants p ON p.participant_id = sp.participant_id
                                WHERE p.id = ?
                                LIMIT 1
                            )
                        )
                   )
                ORDER BY r.id
                "#,
                vec![
                    rusqlite::types::Value::Integer(finish_id),
                    rusqlite::types::Value::Integer(finish_id),
                ],
            )
        } else if let Some(fid) = format_id {
            (
                r#"
                SELECT r.id, r.finish_participant_id, r.format_id, f.format_name,
                       r.from_cp, r.to_cp, r.direction, r.apply_mode, r.max_leg_seconds, r.created_at,
                       r.course_id, c.name
                FROM leg_exclusion_rules r
                LEFT JOIN start_protocol_formats f ON f.id = r.format_id
                LEFT JOIN courses c ON c.id = r.course_id
                WHERE r.finish_participant_id IS NULL AND r.format_id = ?
                ORDER BY r.id
                "#,
                vec![rusqlite::types::Value::Integer(fid)],
            )
        } else {
            (
                r#"
                SELECT r.id, r.finish_participant_id, r.format_id, f.format_name,
                       r.from_cp, r.to_cp, r.direction, r.apply_mode, r.max_leg_seconds, r.created_at,
                       r.course_id, c.name
                FROM leg_exclusion_rules r
                LEFT JOIN start_protocol_formats f ON f.id = r.format_id
                LEFT JOIN courses c ON c.id = r.course_id
                WHERE r.finish_participant_id IS NULL
                ORDER BY CASE WHEN r.format_id IS NULL THEN 0 ELSE 1 END,
                         IFNULL(c.name, ''),
                         IFNULL(f.format_name, ''),
                         r.id
                "#,
                vec![],
            )
        };
    let mut stmt = conn
        .prepare(sql)
        .map_err(|e| format!("prepare exclusion rules query: {e}"))?;
    let rows = stmt
        .query_map(rusqlite::params_from_iter(params_vec), |r| {
            let finish_participant_id: Option<i64> = r.get(1)?;
            let format_id: Option<i64> = r.get(2)?;
            let course_id: Option<i64> = r.get(10)?;
            let scope = if finish_participant_id.is_some() {
                "personal".to_string()
            } else if format_id.is_some() {
                "format".to_string()
            } else if course_id.is_some() {
                "course".to_string()
            } else {
                "global".to_string()
            };
            Ok(ExclusionRuleRow {
                id: r.get(0)?,
                finish_participant_id,
                format_id,
                format_name: r.get(3)?,
                course_id,
                course_name: r.get(11)?,
                from_cp: r.get(4)?,
                to_cp: r.get(5)?,
                direction: r.get(6)?,
                apply_mode: r.get(7)?,
                max_leg_seconds: r.get(8)?,
                created_at: r.get(9)?,
                scope,
            })
        })
        .map_err(|e| format!("query exclusion rules: {e}"))?;
    let mut out = Vec::new();
    for row in rows {
        out.push(row.map_err(|e| format!("read exclusion rule row: {e}"))?);
    }
    Ok(out)
}

pub fn query_manual_corrections(
    conn: &Connection,
    participant_id: Option<String>,
) -> Result<Vec<CorrectionRow>, String> {
    let (sql, params_vec): (&str, Vec<rusqlite::types::Value>) = if let Some(pid) = participant_id {
        let finish_id = parse_finish_participant_id(&pid)?;
        (
            r#"
            SELECT id, finish_participant_id, correction_type, payload_json, created_at
            FROM manual_corrections
            WHERE finish_participant_id = ? OR finish_participant_id IS NULL
            ORDER BY id DESC
            "#,
            vec![rusqlite::types::Value::Integer(finish_id)],
        )
    } else {
        (
            r#"
            SELECT id, finish_participant_id, correction_type, payload_json, created_at
            FROM manual_corrections
            WHERE finish_participant_id IS NULL
            ORDER BY id DESC
            "#,
            vec![],
        )
    };
    let mut stmt = conn
        .prepare(sql)
        .map_err(|e| format!("prepare manual corrections query: {e}"))?;
    let rows = stmt
        .query_map(rusqlite::params_from_iter(params_vec), |r| {
            let payload_raw: String = r.get(3)?;
            let payload: Value = serde_json::from_str(&payload_raw).map_err(|e| {
                rusqlite::Error::FromSqlConversionFailure(
                    3,
                    rusqlite::types::Type::Text,
                    Box::new(std::io::Error::new(std::io::ErrorKind::InvalidData, e)),
                )
            })?;
            let finish_id: Option<i64> = r.get(1)?;
            Ok(CorrectionRow {
                id: r.get(0)?,
                finish_participant_id: finish_id,
                scope: if finish_id.is_some() {
                    "personal".to_string()
                } else {
                    "global".to_string()
                },
                source_table: "manual_corrections".to_string(),
                correction_type: r.get(2)?,
                payload,
                created_at: r.get(4)?,
            })
        })
        .map_err(|e| format!("query manual corrections: {e}"))?;
    let mut out = Vec::new();
    for row in rows {
        out.push(row.map_err(|e| format!("read manual correction row: {e}"))?);
    }
    Ok(out)
}

pub fn delete_correction_entry(conn: &Connection, source_table: String, correction_id: i64) -> Result<(), String> {
    let table = match source_table.as_str() {
        "manual_corrections" => "manual_corrections",
        "legacy_corrections" => "corrections",
        _ => return Err("source_table must be one of: manual_corrections, legacy_corrections".to_string()),
    };
    let exists: Option<i64> = conn
        .query_row(
            &format!("SELECT id FROM {table} WHERE id = ?"),
            params![correction_id],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("check correction exists: {e}"))?;
    if exists.is_none() {
        return Err(format!("Correction {correction_id} not found"));
    }
    conn.execute(
        &format!("DELETE FROM {table} WHERE id = ?"),
        params![correction_id],
    )
    .map_err(|e| format!("delete correction entry: {e}"))?;
    Ok(())
}

fn reset_import_data(tx: &Transaction<'_>) -> Result<(), rusqlite::Error> {
    clear_finish_import_data(tx)?;
    tx.execute("DELETE FROM leg_exclusion_rules", [])?;
    Ok(())
}

fn clear_finish_import_data(tx: &Transaction<'_>) -> Result<(), rusqlite::Error> {
    // Personal corrections/anomalies/rules cascade via finish_participant_id FK.
    tx.execute("DELETE FROM results", [])?;
    tx.execute("DELETE FROM participants", [])?;
    tx.execute(
        "DELETE FROM manual_corrections WHERE finish_participant_id IS NOT NULL",
        [],
    )?;
    Ok(())
}

fn record_is_blank(rec: &StringRecord) -> bool {
    rec.iter().all(|c| c.trim().is_empty())
}

fn field(rec: &StringRecord, idx: usize) -> String {
    rec.get(idx).unwrap_or("").trim().to_string()
}

fn normalize_competition_time(value: &str) -> Result<String, String> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return Ok(String::new());
    }
    if NaiveTime::parse_from_str(trimmed, "%H:%M:%S").is_ok() {
        return Ok(trimmed.to_string());
    }
    if let Ok(parsed) = NaiveTime::parse_from_str(trimmed, "%H:%M") {
        return Ok(parsed.format("%H:%M:%S").to_string());
    }
    Err("competition_start_time must be in HH:MM or HH:MM:SS format".to_string())
}

fn competition_start_datetime(settings: &Settings) -> Option<NaiveDateTime> {
    if settings.competition_date.trim().is_empty() || settings.competition_start_time.trim().is_empty() {
        return None;
    }
    let date = NaiveDate::parse_from_str(settings.competition_date.trim(), "%Y-%m-%d").ok()?;
    let time = NaiveTime::parse_from_str(settings.competition_start_time.trim(), "%H:%M:%S").ok()?;
    Some(date.and_time(time))
}

fn parse_birth_date_ru(value: &str) -> Option<String> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return None;
    }
    NaiveDate::parse_from_str(trimmed, "%d.%m.%Y")
        .ok()
        .map(|d| d.format("%Y-%m-%d").to_string())
}

const FALLBACK_START_LEG_SECONDS: i64 = 60;

fn insert_orient_start_punch_anomalies(
    conn: &Connection,
    settings: &Settings,
) -> Result<(), String> {
    let Some(start_cp) = settings.start_cp else {
        return Ok(());
    };
    let finish_cp = settings.finish_cp;
    let mut marks_by_participant: BTreeMap<i64, Vec<Mark>> = BTreeMap::new();
    {
        let mut stmt = conn
            .prepare(
                r#"
                SELECT finish_participant_id, cp_number, mark_time, seq
                FROM marks_raw
                ORDER BY finish_participant_id, mark_time, seq
                "#,
            )
            .map_err(|e| format!("prepare marks for start-punch anomalies: {e}"))?;
        let rows = stmt
            .query_map([], |r| {
                let ts: String = r.get(2)?;
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, i64>(1)?,
                    ts,
                    r.get::<_, i64>(3)?,
                ))
            })
            .map_err(|e| format!("query marks for start-punch anomalies: {e}"))?;
        for row in rows {
            let (finish_id, cp_number, ts, seq) =
                row.map_err(|e| format!("read mark for start-punch anomalies: {e}"))?;
            let Ok(mark_time) = parse_time(&ts) else {
                continue;
            };
            marks_by_participant
                .entry(finish_id)
                .or_default()
                .push(Mark {
                    cp_number,
                    mark_time,
                    seq,
                });
        }
    }
    let worst_leg = worst_start_to_cp_seconds(&marks_by_participant, start_cp, finish_cp);
    for (finish_id, marks) in &marks_by_participant {
        let Some(kind) = orient_start_punch_kind(marks, start_cp, finish_cp) else {
            continue;
        };
        let Some(first) = first_course_mark(marks, start_cp, finish_cp) else {
            continue;
        };
        let worst = *worst_leg.get(&first.cp_number).unwrap_or(&FALLBACK_START_LEG_SECONDS);
        let suggested = suggested_start_time(first.mark_time, worst);
        let start_time = marks
            .iter()
            .find(|m| m.cp_number == start_cp)
            .map(|m| m.mark_time.format("%Y-%m-%d %H:%M:%S").to_string());
        let finish_time = marks
            .iter()
            .rev()
            .find(|m| m.cp_number == finish_cp)
            .map(|m| m.mark_time.format("%Y-%m-%d %H:%M:%S").to_string());
        let (title, details) = if kind == "start_punch_missing" {
            (
                "Нет отметки стартовой станции".to_string(),
                format!(
                    "Нет отметки старта {}, есть финиш и другие КП. Первый КП {} в {}. Предлагаемое время старта {} (худший перегон старт→{}: {} с).",
                    start_cp,
                    first.cp_number,
                    first.mark_time.format("%Y-%m-%d %H:%M:%S"),
                    suggested.format("%Y-%m-%d %H:%M:%S"),
                    first.cp_number,
                    worst
                ),
            )
        } else {
            (
                "Старт позже финиша".to_string(),
                format!(
                    "Отметка старта {} в {} позже финиша {} в {}. Предлагаемое время старта {} (худший перегон старт→{}: {} с).",
                    start_cp,
                    start_time.as_deref().unwrap_or("—"),
                    finish_cp,
                    finish_time.as_deref().unwrap_or("—"),
                    suggested.format("%Y-%m-%d %H:%M:%S"),
                    first.cp_number,
                    worst
                ),
            )
        };
        insert_participant_anomaly(
            conn,
            *finish_id,
            &ParticipantAnomaly {
                anomaly_type: kind.to_string(),
                title,
                details,
                payload: json!({
                    "start_cp": start_cp,
                    "finish_cp": finish_cp,
                    "first_cp": first.cp_number,
                    "first_cp_time": first.mark_time.format("%Y-%m-%d %H:%M:%S").to_string(),
                    "worst_leg_seconds": worst,
                    "suggested_start_time": suggested.format("%Y-%m-%d %H:%M:%S").to_string(),
                    "existing_start_time": start_time,
                    "finish_time": finish_time
                }),
                resolved: false,
                resolved_correction_id: None,
                resolved_at: None,
            },
        )?;
    }
    Ok(())
}

fn orient_start_punch_kind(marks: &[Mark], start_cp: i64, finish_cp: i64) -> Option<&'static str> {
    let has_other = marks
        .iter()
        .any(|m| m.cp_number != start_cp && m.cp_number != finish_cp);
    let start_time = marks.iter().find(|m| m.cp_number == start_cp).map(|m| m.mark_time);
    let finish_time = marks
        .iter()
        .rev()
        .find(|m| m.cp_number == finish_cp)
        .map(|m| m.mark_time);
    if !has_other || finish_time.is_none() {
        return None;
    }
    match (start_time, finish_time) {
        (None, Some(_)) => Some("start_punch_missing"),
        (Some(start), Some(finish)) if start > finish => Some("start_punch_after_finish"),
        _ => None,
    }
}

fn first_course_mark(marks: &[Mark], start_cp: i64, finish_cp: i64) -> Option<&Mark> {
    marks
        .iter()
        .find(|m| m.cp_number != start_cp && m.cp_number != finish_cp)
}

fn worst_start_to_cp_seconds(
    marks_by_participant: &BTreeMap<i64, Vec<Mark>>,
    start_cp: i64,
    finish_cp: i64,
) -> HashMap<i64, i64> {
    let mut worst: HashMap<i64, i64> = HashMap::new();
    for marks in marks_by_participant.values() {
        if orient_start_punch_kind(marks, start_cp, finish_cp).is_some() {
            continue;
        }
        let Some(start_time) = marks.iter().find(|m| m.cp_number == start_cp).map(|m| m.mark_time)
        else {
            continue;
        };
        let Some(first) = first_course_mark(marks, start_cp, finish_cp) else {
            continue;
        };
        if first.mark_time <= start_time {
            continue;
        }
        let secs = (first.mark_time - start_time).num_seconds().max(1);
        let entry = worst.entry(first.cp_number).or_insert(secs);
        if secs > *entry {
            *entry = secs;
        }
    }
    worst
}

fn suggested_start_time(first_cp_time: NaiveDateTime, worst_leg_seconds: i64) -> NaiveDateTime {
    first_cp_time - Duration::seconds(worst_leg_seconds.max(1))
}

fn detect_anomalies_for_start_time(start_time: &str, settings: &Settings) -> Vec<ParticipantAnomaly> {
    let mut anomalies = Vec::new();
    let Ok(competition_date) =
        NaiveDate::parse_from_str(settings.competition_date.trim(), "%Y-%m-%d")
    else {
        return anomalies;
    };
    let Ok(participant_start_dt) = parse_time(start_time) else {
        return anomalies;
    };
    if participant_start_dt.date() != competition_date {
        let competition_midnight = competition_date.and_hms_opt(0, 0, 0).unwrap();
        let diff_seconds = (participant_start_dt - competition_midnight).num_seconds();
        anomalies.push(ParticipantAnomaly {
            anomaly_type: "start_time_day_shift".to_string(),
            title: "Сдвиг даты старта".to_string(),
            details: format!(
                "Дата старта участника {} не совпадает с датой соревнования {}",
                participant_start_dt.format("%Y-%m-%d %H:%M:%S"),
                competition_date.format("%Y-%m-%d")
            ),
            payload: json!({
                "participant_start_time": participant_start_dt.format("%Y-%m-%d %H:%M:%S").to_string(),
                "competition_date": competition_date.format("%Y-%m-%d").to_string(),
                "difference_seconds": diff_seconds
            }),
            resolved: false,
            resolved_correction_id: None,
            resolved_at: None,
        });
    }
    anomalies
}

fn parse_time(value: &str) -> Result<NaiveDateTime, chrono::ParseError> {
    NaiveDateTime::parse_from_str(value.trim(), "%Y-%m-%d %H:%M:%S")
}

fn payload_int(payload: &Value, key: &str) -> Result<i64, String> {
    payload
        .get(key)
        .and_then(|v| v.as_i64())
        .ok_or_else(|| format!("payload missing int field: {key}"))
}

fn payload_str(payload: &Value, key: &str) -> Result<String, String> {
    payload
        .get(key)
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
        .ok_or_else(|| format!("payload missing string field: {key}"))
}

#[cfg(test)]
mod tests {
    use super::{
        apply_orient_exclusion_rules, assign_course_correction,
        add_cp_correction, calculate_orient_result, ManualCorrection,
        course_subsequence_taken, course_subsequence_taken_with_adds, orient_add_cp_counts,
        ensure_schema_extras, filter_course_cps, first_course_mark,
        import_csv_content, init_db, orient_course_legs, orient_start_punch_kind, parse_time,
        query_participant_details, recalculate, resolve_orient_leg_to, set_setting_value,
        suggested_start_time, ExclusionRule, Mark, Settings,
    };
    use rusqlite::Connection;
    use std::collections::{HashMap, HashSet};

    fn memory_db() -> Connection {
        let conn = Connection::open_in_memory().expect("memory db");
        init_db(&conn).expect("init db");
        ensure_schema_extras(&conn).expect("schema extras");
        conn
    }

    fn finish_csv_duplicate_bib() -> &'static str {
        "system,chip raw id,id,team name,course,result,brief,start st id,Start time,st1,time1\n\
         sfr,AA 11,4,Ермаченков Филипп,1,OK,ok,241,2026-08-02 12:00:00,241,2026-08-02 12:00:00\n\
         sfr,BB 22,4,Гапунич Ксения,1,OK,ok,241,2026-08-02 12:00:01,241,2026-08-02 12:00:01\n"
    }

    fn mark(cp: i64, time: &str) -> Mark {
        Mark {
            cp_number: cp,
            mark_time: parse_time(time).unwrap(),
            seq: 0,
        }
    }

    #[test]
    fn orient_start_after_finish_and_missing() {
        let after = vec![
            mark(32, "2026-07-25 12:15:23"),
            mark(240, "2026-07-25 13:19:26"),
            mark(241, "2026-07-25 13:21:12"),
        ];
        assert_eq!(orient_start_punch_kind(&after, 241, 240), Some("start_punch_after_finish"));
        assert_eq!(first_course_mark(&after, 241, 240).map(|m| m.cp_number), Some(32));

        let missing = vec![
            mark(32, "2026-07-25 11:59:43"),
            mark(240, "2026-07-25 13:06:06"),
        ];
        assert_eq!(orient_start_punch_kind(&missing, 241, 240), Some("start_punch_missing"));
        assert_eq!(
            suggested_start_time(parse_time("2026-07-25 11:59:43").unwrap(), 57)
                .format("%Y-%m-%d %H:%M:%S")
                .to_string(),
            "2026-07-25 11:58:46"
        );
    }

    #[test]
    fn filter_course_cps_drops_removed() {
        let removed = HashSet::from([32]);
        assert_eq!(filter_course_cps(&[31, 32, 33], &removed), vec![31, 33]);
        assert_eq!(filter_course_cps(&[32, 32], &removed), Vec::<i64>::new());
    }

    #[test]
    fn subsequence_allows_repeats_and_extra_punches() {
        let required = [1, 2, 3, 4, 5];
        assert_eq!(course_subsequence_taken(&[1, 1, 2, 4, 3, 4, 5], &required), 5);
        assert_eq!(course_subsequence_taken(&[2, 3, 1, 5, 2, 3, 4, 5], &required), 5);
    }

    #[test]
    fn subsequence_rejects_wrong_order_and_missing() {
        let required = [1, 2, 3, 4, 5];
        assert_eq!(course_subsequence_taken(&[1, 3, 2, 4, 5], &required), 2);
        assert_eq!(course_subsequence_taken(&[1, 3, 4, 5], &required), 1);
    }

    fn orient_rule(from_cp: i64, max_leg_seconds: Option<i64>) -> ExclusionRule {
        ExclusionRule {
            from_cp,
            to_cp: 0,
            direction: "forward".to_string(),
            apply_mode: "always".to_string(),
            max_leg_seconds,
            course_id: None,
            source: "test".to_string(),
        }
    }

    fn orient_course_rule(from_cp: i64, course_id: i64) -> ExclusionRule {
        let mut rule = orient_rule(from_cp, None);
        rule.course_id = Some(course_id);
        rule
    }

    fn orient_settings(control_minutes: i64) -> Settings {
        Settings {
            control_minutes,
            penalty_per_minute: 0,
            dq_minutes: 0,
            finish_cp: 240,
            start_mode: "station".to_string(),
            start_cp: Some(241),
            competition_date: String::new(),
            competition_start_time: String::new(),
            sport_kind: "orient".to_string(),
        }
    }

    #[test]
    fn orient_exclusion_next_cp_from_course() {
        let required = [32, 45, 48];
        assert_eq!(resolve_orient_leg_to(241, &required, 241, 240), Some(32));
        assert_eq!(resolve_orient_leg_to(32, &required, 241, 240), Some(45));
        assert_eq!(resolve_orient_leg_to(48, &required, 241, 240), Some(240));
        assert_eq!(resolve_orient_leg_to(99, &required, 241, 240), None);
    }

    #[test]
    fn orient_course_legs_keeps_repeated_from_cp() {
        let legs = orient_course_legs(&[58, 68, 58], Some(241), 250, &HashSet::new());
        assert!(legs.contains(&(58, 68)));
        assert!(legs.contains(&(58, 250)));
        assert_eq!(legs, vec![(241, 58), (58, 68), (68, 58), (58, 250)]);
    }

    #[test]
    fn orient_exclusion_includes_extras_and_repeats() {
        let window = vec![
            mark(241, "2026-07-25 10:00:00"),
            mark(32, "2026-07-25 10:10:00"),
            mark(99, "2026-07-25 10:12:00"),
            mark(45, "2026-07-25 10:20:00"),
            mark(32, "2026-07-25 10:25:00"),
            mark(45, "2026-07-25 10:40:00"),
            mark(240, "2026-07-25 11:00:00"),
        ];
        let required = [32, 45];
        let elapsed = 3600;
        let rules = [orient_rule(32, None)];
        // 10:10→10:20 (600s) and 10:25→10:40 (900s)
        assert_eq!(
            apply_orient_exclusion_rules(&window, &required, 241, 240, None, &rules, elapsed),
            2100
        );
        let capped = [orient_rule(32, Some(300))];
        assert_eq!(
            apply_orient_exclusion_rules(&window, &required, 241, 240, None, &capped, elapsed),
            3000
        );
    }

    #[test]
    fn orient_exclusion_is_scoped_to_course() {
        let window = vec![
            mark(241, "2026-07-25 10:00:00"),
            mark(32, "2026-07-25 10:10:00"),
            mark(240, "2026-07-25 11:00:00"),
        ];
        let required = [32];
        let elapsed = 3600;
        let rules = [orient_course_rule(241, 1)];
        assert_eq!(
            apply_orient_exclusion_rules(&window, &required, 241, 240, Some(1), &rules, elapsed),
            3000
        );
        assert_eq!(
            apply_orient_exclusion_rules(&window, &required, 241, 240, Some(2), &rules, elapsed),
            3600
        );
    }

    #[test]
    fn orient_exclusion_can_avoid_overtime() {
        let marks = vec![
            mark(241, "2026-07-25 10:00:00"),
            mark(32, "2026-07-25 10:15:00"),
            mark(45, "2026-07-25 10:30:00"),
            mark(240, "2026-07-25 11:10:00"),
        ];
        let mut courses = HashMap::new();
        courses.insert("d1".to_string(), vec![32, 45]);
        let settings = orient_settings(60);
        let without = calculate_orient_result(
            &marks,
            &settings,
            "D1",
            Some(1),
            &courses,
            &HashSet::new(),
            &[],
            &HashMap::new(),
        );
        assert!(without.diagnostics.iter().any(|d| d == "overtime"));
        assert_eq!(without.elapsed_seconds, 70 * 60);

        let with = calculate_orient_result(
            &marks,
            &settings,
            "D1",
            Some(1),
            &courses,
            &HashSet::new(),
            &[orient_rule(241, None)],
            &HashMap::new(),
        );
        assert!(!with.diagnostics.iter().any(|d| d == "overtime"));
        assert_eq!(with.elapsed_seconds, 55 * 60);
        assert_eq!(with.status, "OK");
    }

    #[test]
    fn rogaine_import_keeps_duplicate_bib_as_anomaly() {
        let mut conn = memory_db();
        let summary = import_csv_content(&mut conn, finish_csv_duplicate_bib(), true)
            .expect("rogaine import must accept duplicate bib");
        assert_eq!(summary.participants_count, 2);
        let statuses: Vec<(String, String, String)> = conn
            .prepare(
                "SELECT name, status, diagnostics_json FROM results WHERE participant_id = '4' ORDER BY name",
            )
            .unwrap()
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap();
        assert_eq!(statuses.len(), 2);
        assert!(statuses.iter().all(|(_, status, diag)| {
            status == "Ошибка" && diag.contains("duplicate_bib_in_finish")
        }));
    }

    #[test]
    fn orient_import_rejects_duplicate_bib_on_same_course() {
        let mut conn = memory_db();
        set_setting_value(&conn, "sport_kind", "orient").unwrap();
        let err = import_csv_content(&mut conn, finish_csv_duplicate_bib(), true)
            .expect_err("orient import must reject duplicate bib");
        assert!(
            err.contains("повторяется id '4'"),
            "unexpected import error: {err}"
        );
    }

    fn seed_two_orient_courses(conn: &Connection) {
        conn.execute("INSERT INTO courses(name) VALUES ('D1')", [])
            .unwrap();
        let d1 = conn.last_insert_rowid();
        conn.execute("INSERT INTO courses(name) VALUES ('D2')", [])
            .unwrap();
        let d2 = conn.last_insert_rowid();
        for (cid, cps) in [(d1, [241_i64, 31, 240]), (d2, [241, 45, 240])] {
            for (i, cp) in cps.iter().enumerate() {
                conn.execute(
                    "INSERT INTO course_controls(course_id, seq, cp_number) VALUES (?, ?, ?)",
                    rusqlite::params![cid, (i as i64) + 1, *cp],
                )
                .unwrap();
            }
        }
        set_setting_value(conn, "sport_kind", "orient").unwrap();
        set_setting_value(conn, "start_cp", "241").unwrap();
        set_setting_value(conn, "finish_cp", "240").unwrap();
        set_setting_value(conn, "start_mode", "station").unwrap();
    }

    #[test]
    fn assign_course_scores_against_new_course_and_keeps_csv() {
        let mut conn = memory_db();
        seed_two_orient_courses(&conn);
        import_csv_content(
            &mut conn,
            "system,chip raw id,id,team name,course,result,brief,start st id,Start time,st1,time1,st2,time2,st3,time3\n\
             sfr,,3,Андреева Софья,D1,OK,ok,241,2026-08-02 12:00:00,241,2026-08-02 12:00:00,45,2026-08-02 12:10:00,240,2026-08-02 12:20:00\n",
            true,
        )
        .unwrap();
        let finish_id: i64 = conn
            .query_row("SELECT id FROM participants WHERE participant_id = '3'", [], |r| r.get(0))
            .unwrap();
        assign_course_correction(&conn, &finish_id.to_string(), "D2").unwrap();
        recalculate(&mut conn).unwrap();
        let result_id: i64 = conn
            .query_row(
                "SELECT id FROM results WHERE finish_participant_id = ?",
                rusqlite::params![finish_id],
                |r| r.get(0),
            )
            .unwrap();
        let details = query_participant_details(&mut conn, result_id).unwrap();
        assert_eq!(details.participant.course_name, "D1");
        assert_eq!(details.result.as_ref().unwrap().format_name, "D2");
        let stored: String = conn
            .query_row(
                "SELECT course_name FROM participants WHERE id = ?",
                rusqlite::params![finish_id],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(stored, "D1");
        assign_course_correction(&conn, &finish_id.to_string(), "D1").unwrap();
        recalculate(&mut conn).unwrap();
        let result_id: i64 = conn
            .query_row(
                "SELECT id FROM results WHERE finish_participant_id = ?",
                rusqlite::params![finish_id],
                |r| r.get(0),
            )
            .unwrap();
        let details = query_participant_details(&mut conn, result_id).unwrap();
        assert_eq!(details.result.as_ref().unwrap().format_name, "D1");
        let n: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM manual_corrections WHERE correction_type = 'assign_course'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(n, 0);
    }

    #[test]
    fn assign_course_rejects_when_same_bib_already_on_target() {
        let mut conn = memory_db();
        seed_two_orient_courses(&conn);
        import_csv_content(
            &mut conn,
            "system,chip raw id,id,team name,course,result,brief,start st id,Start time,st1,time1,st2,time2\n\
             sfr,,3,Андреева Софья,D1,OK,ok,241,2026-08-02 12:00:00,241,2026-08-02 12:00:00,240,2026-08-02 12:20:00\n\
             sfr,,3,Андреева Софья,D2,OK,ok,241,2026-08-02 13:00:00,241,2026-08-02 13:00:00,240,2026-08-02 13:20:00\n",
            true,
        )
        .unwrap();
        let finish_d1: i64 = conn
            .query_row(
                "SELECT id FROM participants WHERE participant_id = '3' AND course_name = 'D1'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        let err = assign_course_correction(&conn, &finish_d1.to_string(), "D2").unwrap_err();
        assert!(
            err.contains("уже есть финиш"),
            "unexpected error: {err}"
        );
    }

    fn seed_repeat_cp_course(conn: &Connection) {
        conn.execute("INSERT INTO courses(name) VALUES ('D1')", [])
            .unwrap();
        let d1 = conn.last_insert_rowid();
        for (i, cp) in [241_i64, 250, 250, 240].iter().enumerate() {
            conn.execute(
                "INSERT INTO course_controls(course_id, seq, cp_number) VALUES (?, ?, ?)",
                rusqlite::params![d1, (i as i64) + 1, *cp],
            )
            .unwrap();
        }
        set_setting_value(conn, "sport_kind", "orient").unwrap();
        set_setting_value(conn, "start_cp", "241").unwrap();
        set_setting_value(conn, "finish_cp", "240").unwrap();
        set_setting_value(conn, "start_mode", "station").unwrap();
        set_setting_value(conn, "control_minutes", "120").unwrap();
    }

    #[test]
    fn apply_orient_add_cp_covers_repeated_control() {
        let marks = vec![
            mark(241, "2026-08-02 12:00:00"),
            mark(240, "2026-08-02 13:00:00"),
        ];
        let corrections = vec![
            ManualCorrection {
                finish_participant_id: Some(1),
                correction_type: "add_cp".into(),
                payload: serde_json::json!({"cp_number": 250}),
            },
            ManualCorrection {
                finish_participant_id: Some(1),
                correction_type: "add_cp".into(),
                payload: serde_json::json!({"cp_number": 250}),
            },
        ];
        let required = vec![241, 250, 250, 240];
        let add_counts = orient_add_cp_counts(&corrections, &required);
        let punches: Vec<i64> = marks.iter().map(|m| m.cp_number).collect();
        assert_eq!(
            course_subsequence_taken_with_adds(&punches, &required, &add_counts),
            4
        );
        let mut courses = HashMap::new();
        courses.insert("d1".to_string(), required.clone());
        let result = calculate_orient_result(
            &marks,
            &orient_settings(120),
            "D1",
            Some(1),
            &courses,
            &HashSet::new(),
            &[],
            &add_counts,
        );
        assert_eq!(result.status, "OK");
        assert!(!result.diagnostics.iter().any(|d| d == "course_incomplete"));
        assert_eq!(result.elapsed_seconds, 3600);
        assert_eq!(marks.iter().filter(|m| m.cp_number == 250).count(), 0);
    }

    #[test]
    fn add_cp_fills_course_without_invented_times() {
        let marks = vec![
            mark(241, "2026-07-11 12:19:59"),
            mark(70, "2026-07-11 13:21:06"),
            mark(240, "2026-07-11 13:21:31"),
        ];
        let corrections = [35, 42, 38, 32, 250, 34, 40, 31, 39, 33, 250, 49]
            .into_iter()
            .map(|cp| ManualCorrection {
                finish_participant_id: Some(1),
                correction_type: "add_cp".into(),
                payload: serde_json::json!({ "cp_number": cp }),
            })
            .collect::<Vec<_>>();
        let required = vec![35, 42, 38, 32, 250, 34, 40, 31, 39, 33, 250, 49, 70];
        let add_counts = orient_add_cp_counts(&corrections, &required);
        let punches: Vec<i64> = marks.iter().map(|m| m.cp_number).collect();
        assert_eq!(
            course_subsequence_taken_with_adds(&punches, &required, &add_counts),
            required.len()
        );
        let mut courses = HashMap::new();
        courses.insert("d1".to_string(), required.clone());
        let result = calculate_orient_result(
            &marks,
            &orient_settings(120),
            "D1",
            Some(1),
            &courses,
            &HashSet::new(),
            &[orient_rule(32, None)],
            &add_counts,
        );
        assert_eq!(result.elapsed_seconds, 3692);
        assert_eq!(result.status, "OK");
    }

    #[test]
    fn orient_add_cp_allows_each_repeat_on_course() {
        let mut conn = memory_db();
        seed_repeat_cp_course(&conn);
        import_csv_content(
            &mut conn,
            "system,chip raw id,id,team name,course,result,brief,start st id,Start time,st1,time1,st2,time2\n\
             sfr,,7,Иванов Иван,D1,OK,ok,241,2026-08-02 12:00:00,241,2026-08-02 12:00:00,240,2026-08-02 13:00:00\n",
            true,
        )
        .unwrap();
        let finish_id: i64 = conn
            .query_row("SELECT id FROM participants WHERE participant_id = '7'", [], |r| {
                r.get(0)
            })
            .unwrap();
        add_cp_correction(&conn, &finish_id.to_string(), 250, "").unwrap();
        add_cp_correction(&conn, &finish_id.to_string(), 250, "").unwrap();
        let err = add_cp_correction(&conn, &finish_id.to_string(), 250, "").unwrap_err();
        assert!(
            err.contains("встречается 2 раз"),
            "unexpected third add_cp error: {err}"
        );
        recalculate(&mut conn).unwrap();
        let status: String = conn
            .query_row(
                "SELECT status FROM results WHERE finish_participant_id = ?",
                rusqlite::params![finish_id],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(status, "OK");
        let added: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM manual_corrections WHERE finish_participant_id = ? AND correction_type = 'add_cp'",
                rusqlite::params![finish_id],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(added, 2);
    }
}
