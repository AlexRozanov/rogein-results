use std::collections::{BTreeMap, HashSet};
use std::path::Path;

use chrono::{NaiveDate, NaiveDateTime, NaiveTime, Utc};
use csv::StringRecord;
use rusqlite::{params, Connection, OptionalExtension, Transaction};
use serde::Serialize;
use serde_json::{json, Value};

const FOOTER_PREFIX: &str = "Результаты выгружены из SFR Reader";

#[derive(Debug, Serialize)]
pub struct ImportSummary {
    pub participants_count: i64,
    pub results_count: i64,
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
    source: String,
}

#[derive(Debug, Clone)]
struct ManualCorrection {
    correction_type: String,
    payload: Value,
}

#[derive(Debug)]
struct Participant {
    participant_id: String,
    name: String,
    start_station_id: i64,
    start_time: String,
}

#[derive(Debug, Serialize, Clone)]
pub struct Settings {
    pub control_minutes: i64,
    pub penalty_per_minute: i64,
    pub dq_minutes: i64,
    pub finish_cp: i64,
    pub competition_date: String,
    pub competition_start_time: String,
}

#[derive(Debug, Serialize)]
pub struct ResultRow {
    pub participant_id: String,
    pub name: String,
    pub status: String,
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
}

#[derive(Debug, Serialize)]
pub struct ParticipantRow {
    pub participant_id: String,
    pub name: String,
}

#[derive(Debug, Serialize)]
pub struct ParticipantMeta {
    pub participant_id: String,
    pub name: String,
    pub start_station_id: i64,
    pub start_time: String,
    pub source_row: Option<i64>,
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
    pub participant_id: Option<String>,
    pub scope: String,
    pub source_table: String,
    pub correction_type: String,
    pub payload: Value,
    pub created_at: String,
}

#[derive(Debug, Serialize)]
pub struct ExclusionRuleRow {
    pub id: i64,
    pub participant_id: Option<String>,
    pub from_cp: i64,
    pub to_cp: i64,
    pub direction: String,
    pub apply_mode: String,
    pub max_leg_seconds: Option<i64>,
    pub created_at: String,
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
    init_db(&conn)?;
    Ok(conn)
}

pub fn init_db(conn: &Connection) -> Result<(), String> {
    conn.execute_batch(
        r#"
        PRAGMA foreign_keys = ON;

        CREATE TABLE IF NOT EXISTS participants (
            participant_id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            start_station_id INTEGER NOT NULL,
            start_time TEXT NOT NULL,
            source_row INTEGER
        );

        CREATE TABLE IF NOT EXISTS marks_raw (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            participant_id TEXT NOT NULL,
            seq INTEGER NOT NULL,
            cp_number INTEGER NOT NULL,
            mark_time TEXT NOT NULL,
            FOREIGN KEY (participant_id) REFERENCES participants(participant_id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_marks_raw_participant ON marks_raw(participant_id);

        CREATE TABLE IF NOT EXISTS corrections (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            participant_id TEXT NOT NULL,
            correction_type TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            FOREIGN KEY (participant_id) REFERENCES participants(participant_id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_corrections_participant ON corrections(participant_id, id);

        CREATE TABLE IF NOT EXISTS leg_exclusion_rules (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            participant_id TEXT NULL,
            from_cp INTEGER NOT NULL,
            to_cp INTEGER NOT NULL,
            direction TEXT NOT NULL DEFAULT 'forward',
            apply_mode TEXT NOT NULL DEFAULT 'once',
            max_leg_seconds INTEGER NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            FOREIGN KEY (participant_id) REFERENCES participants(participant_id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_leg_exclusion_rules_participant ON leg_exclusion_rules(participant_id, id);

        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS manual_corrections (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            participant_id TEXT NULL,
            correction_type TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            FOREIGN KEY (participant_id) REFERENCES participants(participant_id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_manual_corrections_participant ON manual_corrections(participant_id, id);

        CREATE TABLE IF NOT EXISTS results (
            participant_id TEXT PRIMARY KEY,
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

        CREATE TABLE IF NOT EXISTS participant_anomalies (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            participant_id TEXT NOT NULL,
            anomaly_type TEXT NOT NULL,
            title TEXT NOT NULL,
            details TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            FOREIGN KEY (participant_id) REFERENCES participants(participant_id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_participant_anomalies_participant ON participant_anomalies(participant_id, anomaly_type);
        "#,
    )
    .map_err(|e| format!("init schema: {e}"))?;

    for (k, v) in [
        ("control_minutes", "240".to_string()),
        ("penalty_per_minute", "1".to_string()),
        ("dq_minutes", "15".to_string()),
        ("finish_cp", "240".to_string()),
        ("competition_date", "".to_string()),
        ("competition_start_time", "".to_string()),
    ] {
        conn.execute(
            "INSERT OR IGNORE INTO settings(key, value) VALUES(?, ?)",
            params![k, v],
        )
        .map_err(|e| format!("init settings: {e}"))?;
    }

    // One-time migration: older builds populated competition date/time automatically.
    // We clear them once so anomaly scan requires explicit user input.
    let anomaly_defaults_migrated: Option<String> = conn
        .query_row(
            "SELECT value FROM settings WHERE key = 'anomaly_defaults_migrated' LIMIT 1",
            [],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("check anomaly defaults migration: {e}"))?;
    if anomaly_defaults_migrated.is_none() {
        conn.execute(
            "UPDATE settings SET value = '' WHERE key IN ('competition_date', 'competition_start_time')",
            [],
        )
        .map_err(|e| format!("reset anomaly default settings: {e}"))?;
        conn.execute(
            "INSERT INTO settings(key, value) VALUES('anomaly_defaults_migrated', '1')",
            [],
        )
        .map_err(|e| format!("mark anomaly defaults migration: {e}"))?;
    }
    Ok(())
}

pub fn import_csv_content(
    conn: &mut Connection,
    csv_content: &str,
    reset: bool,
) -> Result<ImportSummary, String> {
    let mut reader = csv::ReaderBuilder::new()
        .delimiter(b';')
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

    let mut participants_to_upsert: Vec<(String, String, i64, String, i64)> = Vec::new();
    let mut marks_to_insert: Vec<(String, i64, i64, String)> = Vec::new();

    for (idx, row) in reader.records().enumerate() {
        let source_row = idx as i64 + 2;
        let rec = row.map_err(|e| CsvFormatError(format!("Row {source_row}: {e}")).to_string())?;
        if record_is_blank(&rec) {
            continue;
        }
        if rec.len() < 10 {
            if rec.len() == 1 && rec.get(0).unwrap_or("").trim().starts_with(FOOTER_PREFIX) {
                continue;
            }
            return Err(CsvFormatError(format!(
                "Row {source_row}: too few columns (expected at least 10)"
            ))
            .to_string());
        }

        let participant_id = field(&rec, 2);
        let name = field(&rec, 3);
        let start_station_raw = field(&rec, 7);
        let start_time = field(&rec, 8);

        let start_station_id = start_station_raw
            .parse::<i64>()
            .map_err(|_| {
                CsvFormatError(format!(
                    "Row {source_row}: invalid start station id '{start_station_raw}'"
                ))
                .to_string()
            })?;
        if participant_id.is_empty() || name.is_empty() || start_time.is_empty() {
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

        participants_to_upsert.push((
            participant_id.clone(),
            name,
            start_station_id,
            start_time,
            source_row,
        ));

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
            marks_to_insert.push((participant_id.clone(), seq, cp_number, ts_raw));
            seq += 1;
            i += 2;
        }
    }

    if participants_to_upsert.is_empty() {
        return Err(CsvFormatError("CSV has no valid participants".into()).to_string());
    }

    let tx = conn
        .transaction()
        .map_err(|e| format!("start transaction: {e}"))?;
    if reset {
        reset_import_data(&tx).map_err(|e| format!("reset data: {e}"))?;
    }

    {
        let mut stmt = tx
            .prepare(
                r#"
                INSERT INTO participants(participant_id, name, start_station_id, start_time, source_row)
                VALUES(?, ?, ?, ?, ?)
                ON CONFLICT(participant_id) DO UPDATE SET
                    name=excluded.name,
                    start_station_id=excluded.start_station_id,
                    start_time=excluded.start_time,
                    source_row=excluded.source_row
                "#,
            )
            .map_err(|e| format!("prepare participants upsert: {e}"))?;
        for (id, name, st, st_time, source_row) in participants_to_upsert {
            stmt.execute(params![id, name, st, st_time, source_row])
                .map_err(|e| format!("upsert participant: {e}"))?;
        }
    }

    {
        let mut stmt = tx
            .prepare("INSERT INTO marks_raw(participant_id, seq, cp_number, mark_time) VALUES(?, ?, ?, ?)")
            .map_err(|e| format!("prepare marks insert: {e}"))?;
        for (participant_id, seq, cp_number, mark_time) in marks_to_insert {
            stmt.execute(params![participant_id, seq, cp_number, mark_time])
                .map_err(|e| format!("insert mark: {e}"))?;
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
    let settings = get_settings_tx(tx)?;
    tx.execute("DELETE FROM results", [])
        .map_err(|e| format!("clear results: {e}"))?;

    let mut participants_stmt = tx
        .prepare(
            "SELECT participant_id, name, start_station_id, start_time FROM participants ORDER BY participant_id",
        )
        .map_err(|e| format!("prepare participants query: {e}"))?;

    let participants_iter = participants_stmt
        .query_map([], |row| {
            Ok(Participant {
                participant_id: row.get(0)?,
                name: row.get(1)?,
                start_station_id: row.get(2)?,
                start_time: row.get(3)?,
            })
        })
        .map_err(|e| format!("query participants: {e}"))?;

    let mut participants: Vec<Participant> = Vec::new();
    for p in participants_iter {
        participants.push(p.map_err(|e| format!("read participant row: {e}"))?);
    }
    drop(participants_stmt);

    for participant in participants {
        let mut marks = load_marks(tx, &participant.participant_id)?;
        let mut exclusion_rules = load_leg_exclusion_rules(tx, &participant.participant_id)?;
        let manual_corrections = load_manual_corrections(tx, &participant.participant_id)?;
        apply_legacy_corrections(
            tx,
            &participant.participant_id,
            &mut marks,
            &mut exclusion_rules,
        )?;
        marks.sort_by_key(|m| (m.mark_time, m.seq));
        let result = calculate_result(
            &participant,
            &marks,
            &exclusion_rules,
            &manual_corrections,
            &settings,
        )?;
        tx.execute(
            r#"
            INSERT INTO results(
                participant_id, name, status, points_raw, penalty_points, points_final,
                elapsed_seconds, delay_seconds, penalty_minutes, diagnostics_json, computed_at
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            "#,
            params![
                participant.participant_id,
                participant.name,
                result.status,
                result.points_raw,
                result.penalty_points,
                result.points_final,
                result.elapsed_seconds,
                result.delay_seconds,
                result.penalty_minutes,
                serde_json::to_string(&result.diagnostics)
                    .map_err(|e| format!("diag json: {e}"))?,
                Utc::now().naive_utc().to_string(),
            ],
        )
        .map_err(|e| format!("insert result: {e}"))?;
    }

    Ok(())
}

#[derive(Debug)]
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
    participant: &Participant,
    marks: &[Mark],
    exclusion_rules: &[ExclusionRule],
    manual_corrections: &[ManualCorrection],
    settings: &Settings,
) -> Result<ResultCalc, String> {
    let mut diagnostics: Vec<String> = Vec::new();
    let control_seconds = settings.control_minutes * 60;
    let dq_seconds = (settings.control_minutes + settings.dq_minutes) * 60;
    let mut adjusted_elapsed = 0_i64;

    if marks.is_empty() {
        diagnostics.push("no_marks".to_string());
    } else {
        if !marks.iter().any(|m| m.cp_number == settings.finish_cp) {
            diagnostics.push("finish_missing".to_string());
        }
        if marks
            .last()
            .map(|m| m.cp_number != settings.finish_cp)
            .unwrap_or(false)
        {
            diagnostics.push("finish_not_last".to_string());
        }

        let start_time = parse_time(&participant.start_time)
            .map_err(|e| format!("participant start time parse: {e}"))?;
        let finish_time = marks.last().map(|m| m.mark_time).unwrap_or(start_time);
        adjusted_elapsed = (finish_time - start_time).num_seconds().max(0);
        adjusted_elapsed = apply_exclusion_rules(marks, exclusion_rules, adjusted_elapsed);
    }

    let mut unique_cps: HashSet<i64> = HashSet::new();
    for m in marks {
        if m.cp_number == participant.start_station_id || m.cp_number == settings.finish_cp {
            continue;
        }
        unique_cps.insert(m.cp_number);
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
        adjusted_elapsed = apply_removed_cp_legs(marks, &remove_legs_cps, adjusted_elapsed);
    }
    if anomaly_elapsed_subtract_seconds > 0 {
        adjusted_elapsed = (adjusted_elapsed - anomaly_elapsed_subtract_seconds).max(0);
    }
    for cp in removed_point_cps {
        unique_cps.remove(&cp);
    }
    for cp in added_point_cps {
        if cp == participant.start_station_id || cp == settings.finish_cp {
            continue;
        }
        unique_cps.insert(cp);
    }
    let points_raw: i64 = unique_cps.iter().map(|cp| cp / 10).sum();
    let delay_seconds = (adjusted_elapsed - control_seconds).max(0);
    let penalty_minutes = if delay_seconds > 0 {
        (delay_seconds + 59) / 60
    } else {
        0
    };
    let penalty_points = penalty_minutes * settings.penalty_per_minute;
    let points_final = points_raw - penalty_points;

    let status = if !diagnostics.is_empty() {
        "ERR".to_string()
    } else if adjusted_elapsed > dq_seconds {
        "DQ".to_string()
    } else {
        "OK".to_string()
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
    participant_id: &str,
    marks: &mut Vec<Mark>,
    exclusion_rules: &mut Vec<ExclusionRule>,
) -> Result<(), String> {
    let mut stmt = tx
        .prepare(
            "SELECT id, correction_type, payload_json FROM corrections WHERE participant_id = ? ORDER BY id",
        )
        .map_err(|e| format!("prepare corrections query: {e}"))?;
    let rows = stmt
        .query_map(params![participant_id], |r| {
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
    participant_id: &str,
) -> Result<Vec<ExclusionRule>, String> {
    let mut stmt = tx
        .prepare(
            r#"
            SELECT id, participant_id, from_cp, to_cp, direction, apply_mode, max_leg_seconds
            FROM leg_exclusion_rules
            WHERE participant_id = ? OR participant_id IS NULL
            ORDER BY id
            "#,
        )
        .map_err(|e| format!("prepare leg exclusion rules query: {e}"))?;
    let rows = stmt
        .query_map(params![participant_id], |r| {
            let id: i64 = r.get(0)?;
            let participant_scope: Option<String> = r.get(1)?;
            Ok(ExclusionRule {
                from_cp: r.get(2)?,
                to_cp: r.get(3)?,
                direction: r.get::<_, String>(4)?,
                apply_mode: r.get::<_, String>(5)?,
                max_leg_seconds: r.get(6)?,
                source: if participant_scope.is_none() {
                    format!("rule:{id}:global")
                } else {
                    format!("rule:{id}:participant")
                },
            })
        })
        .map_err(|e| format!("query leg exclusion rules: {e}"))?;
    let mut out = Vec::new();
    for row in rows {
        out.push(row.map_err(|e| format!("read leg rule: {e}"))?);
    }
    Ok(out)
}

fn load_manual_corrections(
    tx: &Transaction<'_>,
    participant_id: &str,
) -> Result<Vec<ManualCorrection>, String> {
    let mut stmt = tx
        .prepare(
            r#"
            SELECT id, participant_id, correction_type, payload_json
            FROM manual_corrections
            WHERE participant_id = ? OR participant_id IS NULL
            ORDER BY id
            "#,
        )
        .map_err(|e| format!("prepare manual corrections query: {e}"))?;
    let rows = stmt
        .query_map(params![participant_id], |r| {
            let payload_raw: String = r.get(3)?;
            let payload: Value = serde_json::from_str(&payload_raw).map_err(|e| {
                rusqlite::Error::FromSqlConversionFailure(
                    3,
                    rusqlite::types::Type::Text,
                    Box::new(std::io::Error::new(std::io::ErrorKind::InvalidData, e)),
                )
            })?;
            Ok(ManualCorrection {
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

fn load_marks(tx: &Transaction<'_>, participant_id: &str) -> Result<Vec<Mark>, String> {
    let mut stmt = tx
        .prepare(
            "SELECT cp_number, mark_time, seq FROM marks_raw WHERE participant_id = ? ORDER BY seq",
        )
        .map_err(|e| format!("prepare marks query: {e}"))?;
    let rows = stmt
        .query_map(params![participant_id], |r| {
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
    let mut control_minutes = 240_i64;
    let mut penalty_per_minute = 1_i64;
    let mut dq_minutes = 15_i64;
    let mut finish_cp = 240_i64;
    let mut competition_date = String::new();
    let mut competition_start_time = String::new();
    for row in rows {
        let (k, v) = row.map_err(|e| format!("read settings row: {e}"))?;
        let parsed = v.parse::<i64>().unwrap_or(0);
        match k.as_str() {
            "control_minutes" => control_minutes = parsed,
            "penalty_per_minute" => penalty_per_minute = parsed,
            "dq_minutes" => dq_minutes = parsed,
            "finish_cp" => finish_cp = parsed,
            "competition_date" => competition_date = v,
            "competition_start_time" => competition_start_time = normalize_competition_time(&v)?,
            _ => {}
        }
    }
    Ok(Settings {
        control_minutes,
        penalty_per_minute,
        dq_minutes,
        finish_cp,
        competition_date,
        competition_start_time,
    })
}

pub fn get_settings(conn: &Connection) -> Result<Settings, String> {
    let mut stmt = conn
        .prepare("SELECT key, value FROM settings")
        .map_err(|e| format!("prepare settings query: {e}"))?;
    let rows = stmt
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))
        .map_err(|e| format!("query settings: {e}"))?;
    let mut control_minutes = 240_i64;
    let mut penalty_per_minute = 1_i64;
    let mut dq_minutes = 15_i64;
    let mut finish_cp = 240_i64;
    let mut competition_date = String::new();
    let mut competition_start_time = String::new();
    for row in rows {
        let (k, v) = row.map_err(|e| format!("read settings row: {e}"))?;
        let parsed = v.parse::<i64>().unwrap_or(0);
        match k.as_str() {
            "control_minutes" => control_minutes = parsed,
            "penalty_per_minute" => penalty_per_minute = parsed,
            "dq_minutes" => dq_minutes = parsed,
            "finish_cp" => finish_cp = parsed,
            "competition_date" => competition_date = v,
            "competition_start_time" => competition_start_time = normalize_competition_time(&v)?,
            _ => {}
        }
    }
    Ok(Settings {
        control_minutes,
        penalty_per_minute,
        dq_minutes,
        finish_cp,
        competition_date,
        competition_start_time,
    })
}

pub fn set_settings(
    conn: &Connection,
    control_minutes: Option<i64>,
    penalty_per_minute: Option<i64>,
    dq_minutes: Option<i64>,
    finish_cp: Option<i64>,
    competition_date: Option<String>,
    competition_start_time: Option<String>,
) -> Result<Settings, String> {
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
    get_settings(conn)
}

pub fn query_results(
    conn: &Connection,
    limit: i64,
    offset: i64,
    status: Option<String>,
    search: Option<String>,
    sort_by: Option<String>,
    sort_dir: Option<String>,
) -> Result<(Vec<ResultRow>, i64), String> {
    let safe_limit = limit.clamp(1, 2000);
    let safe_offset = offset.max(0);
    let mut query = String::from(
        r#"
        SELECT
               results.participant_id,
               results.name,
               results.status,
               EXISTS(
                 SELECT 1 FROM corrections c
                 WHERE c.participant_id = results.participant_id
               ) OR EXISTS(
                 SELECT 1 FROM manual_corrections mc
                 WHERE mc.participant_id = results.participant_id
               ) AS has_personal_corrections,
               COALESCE(a.anomaly_count, 0) AS anomaly_count,
               results.points_raw, results.penalty_points, results.points_final,
               results.elapsed_seconds, results.delay_seconds, results.penalty_minutes, results.diagnostics_json, results.computed_at
        FROM results
        LEFT JOIN (
            SELECT pa.participant_id, COUNT(*) AS anomaly_count
            FROM participant_anomalies pa
            WHERE NOT (
                pa.anomaly_type = 'start_time_day_shift'
                AND EXISTS (
                    SELECT 1
                    FROM manual_corrections mc
                    WHERE mc.participant_id = pa.participant_id
                      AND mc.correction_type = 'anomaly_day_shift_24h'
                )
            )
            GROUP BY participant_id
        ) a ON a.participant_id = results.participant_id
        WHERE 1=1
        "#,
    );
    let mut params_dyn: Vec<String> = Vec::new();
    let mut values: Vec<i64> = Vec::new();

    if let Some(ref s) = status {
        if s == "OK" || s == "DQ" || s == "ERR" {
            query.push_str(" AND results.status = ? ");
            params_dyn.push(s.clone());
        }
    }
    if let Some(ref s) = search {
        query.push_str(" AND (results.participant_id LIKE ? OR results.name LIKE ?) ");
        let wildcard = format!("%{s}%");
        params_dyn.push(wildcard.clone());
        params_dyn.push(wildcard);
    }
    let safe_sort_by = match sort_by.as_deref() {
        Some("participant_id") => "participant_id",
        Some("name") => "name",
        Some("points_raw") => "points_raw",
        Some("points_final") => "points_final",
        Some("elapsed_seconds") => "elapsed_seconds",
        _ => "points_final",
    };
    let safe_sort_dir = match sort_dir.as_deref() {
        Some("asc") => "asc",
        Some("desc") => "desc",
        _ => {
            if safe_sort_by == "elapsed_seconds" {
                "asc"
            } else {
                "desc"
            }
        }
    };
    let order_clause = match (safe_sort_by, safe_sort_dir) {
        ("participant_id", "asc") => {
            "results.participant_id COLLATE NOCASE ASC, results.points_raw DESC, results.elapsed_seconds ASC"
        }
        ("participant_id", "desc") => {
            "results.participant_id COLLATE NOCASE DESC, results.points_raw DESC, results.elapsed_seconds ASC"
        }
        ("name", "asc") => "results.name COLLATE NOCASE ASC, results.participant_id COLLATE NOCASE ASC",
        ("name", "desc") => {
            "results.name COLLATE NOCASE DESC, results.participant_id COLLATE NOCASE ASC"
        }
        ("points_raw", "asc") => {
            "results.points_raw ASC, results.points_final ASC, results.elapsed_seconds ASC, results.participant_id COLLATE NOCASE ASC"
        }
        ("points_raw", "desc") => {
            "results.points_raw DESC, results.points_final DESC, results.elapsed_seconds ASC, results.participant_id COLLATE NOCASE ASC"
        }
        ("points_final", "asc") => {
            "CASE results.status WHEN 'OK' THEN 0 WHEN 'DQ' THEN 1 ELSE 2 END ASC, results.points_final ASC, results.elapsed_seconds ASC, results.participant_id COLLATE NOCASE ASC"
        }
        ("points_final", "desc") => {
            "CASE results.status WHEN 'OK' THEN 0 WHEN 'DQ' THEN 1 ELSE 2 END ASC, results.points_final DESC, results.elapsed_seconds ASC, results.participant_id COLLATE NOCASE ASC"
        }
        ("elapsed_seconds", "desc") => {
            "results.elapsed_seconds DESC, results.points_final DESC, results.participant_id COLLATE NOCASE ASC"
        }
        _ => {
            "results.elapsed_seconds ASC, results.points_final DESC, results.participant_id COLLATE NOCASE ASC"
        }
    };
    query.push_str(" ORDER BY ");
    query.push_str(order_clause);
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
    bind_values.push(rusqlite::types::Value::Integer(values[0]));
    bind_values.push(rusqlite::types::Value::Integer(values[1]));
    let rows = stmt
        .query_map(rusqlite::params_from_iter(bind_values), |r| {
            let anomaly_count: i64 = r.get(4)?;
            Ok(ResultRow {
                participant_id: r.get(0)?,
                name: r.get(1)?,
                status: r.get(2)?,
                has_personal_corrections: r.get(3)?,
                has_anomalies: anomaly_count > 0,
                anomaly_count,
                points_raw: r.get(5)?,
                penalty_points: r.get(6)?,
                points_final: r.get(7)?,
                elapsed_seconds: r.get(8)?,
                delay_seconds: r.get(9)?,
                penalty_minutes: r.get(10)?,
                diagnostics_json: r.get(11)?,
                computed_at: r.get(12)?,
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
        WHERE 1=1
        "#,
    );
    let mut count_params_dyn: Vec<String> = Vec::new();
    if let Some(ref s) = status {
        if s == "OK" || s == "DQ" || s == "ERR" {
            count_query.push_str(" AND status = ? ");
            count_params_dyn.push(s.clone());
        }
    }
    if let Some(ref s) = search {
        count_query.push_str(" AND (participant_id LIKE ? OR name LIKE ?) ");
        let wildcard = format!("%{s}%");
        count_params_dyn.push(wildcard.clone());
        count_params_dyn.push(wildcard);
    }
    let mut count_stmt = conn
        .prepare(&count_query)
        .map_err(|e| format!("prepare query results count: {e}"))?;
    let count_bind_values: Vec<rusqlite::types::Value> = count_params_dyn
        .into_iter()
        .map(rusqlite::types::Value::Text)
        .collect();
    let total_count: i64 = count_stmt
        .query_row(rusqlite::params_from_iter(count_bind_values), |r| r.get(0))
        .map_err(|e| format!("query results count: {e}"))?;

    Ok((out, total_count))
}

pub fn query_status_counts(conn: &Connection) -> Result<BTreeMap<String, i64>, String> {
    let mut out = BTreeMap::from([
        ("OK".to_string(), 0_i64),
        ("DQ".to_string(), 0_i64),
        ("ERR".to_string(), 0_i64),
    ]);
    let mut stmt = conn
        .prepare("SELECT status, COUNT(*) as cnt FROM results GROUP BY status")
        .map_err(|e| format!("prepare status counts: {e}"))?;
    let rows = stmt
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)))
        .map_err(|e| format!("query status counts: {e}"))?;
    for row in rows {
        let (status, cnt) = row.map_err(|e| format!("read status count row: {e}"))?;
        out.insert(status, cnt);
    }
    Ok(out)
}

pub fn scan_anomalies(conn: &Connection) -> Result<AnomalyScanSummary, String> {
    let settings = get_settings(conn)?;
    if settings.competition_date.trim().is_empty() || settings.competition_start_time.trim().is_empty() {
        return Err(
            "Для поиска аномалии сдвига на сутки заполните 'Дата соревнования' и 'Время общего старта' в настройках."
                .to_string(),
        );
    }
    if competition_start_datetime(&settings).is_none() {
        return Err(
            "Некорректные настройки даты/времени старта. Ожидаются: дата YYYY-MM-DD и время HH:MM[:SS]."
                .to_string(),
        );
    }
    conn.execute("DELETE FROM participant_anomalies", [])
        .map_err(|e| format!("clear participant anomalies: {e}"))?;
    let mut stmt = conn
        .prepare("SELECT participant_id, start_time FROM participants")
        .map_err(|e| format!("prepare participants anomaly scan: {e}"))?;
    let rows = stmt
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))
        .map_err(|e| format!("query participants anomaly scan: {e}"))?;
    let mut participants_checked = 0_i64;
    let mut participants_with_anomalies = 0_i64;
    let mut anomalies_total = 0_i64;
    let mut by_type: BTreeMap<String, i64> = BTreeMap::new();
    for row in rows {
        let (participant_id, start_time) =
            row.map_err(|e| format!("read participants anomaly scan row: {e}"))?;
        participants_checked += 1;
        let anomalies = detect_anomalies_for_start_time(&start_time, &settings);
        if !anomalies.is_empty() {
            participants_with_anomalies += 1;
            anomalies_total += anomalies.len() as i64;
            for anomaly in anomalies {
                let anomaly_type = anomaly.anomaly_type.clone();
                let entry = by_type.entry(anomaly_type).or_insert(0);
                *entry += 1;
                conn.execute(
                    r#"
                    INSERT INTO participant_anomalies(participant_id, anomaly_type, title, details, payload_json)
                    VALUES(?, ?, ?, ?, ?)
                    "#,
                    params![
                        participant_id,
                        anomaly.anomaly_type,
                        anomaly.title,
                        anomaly.details,
                        anomaly.payload.to_string()
                    ],
                )
                .map_err(|e| format!("insert participant anomaly: {e}"))?;
            }
        }
    }
    Ok(AnomalyScanSummary {
        participants_checked,
        participants_with_anomalies,
        anomalies_total,
        by_type,
    })
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

pub fn query_participant_details(
    conn: &mut Connection,
    participant_id: &str,
) -> Result<ParticipantDetails, String> {
    let tx = conn
        .transaction()
        .map_err(|e| format!("start participant details transaction: {e}"))?;
    let participant = tx
        .query_row(
            r#"
            SELECT participant_id, name, start_station_id, start_time, source_row
            FROM participants
            WHERE participant_id = ?
            "#,
            params![participant_id],
            |r| {
                Ok(ParticipantMeta {
                    participant_id: r.get(0)?,
                    name: r.get(1)?,
                    start_station_id: r.get(2)?,
                    start_time: r.get(3)?,
                    source_row: r.get(4)?,
                })
            },
        )
        .map_err(|e| format!("participant {participant_id} not found: {e}"))?;

    let raw_marks = {
        let mut raw_stmt = tx
            .prepare(
                r#"
                SELECT seq, cp_number, mark_time
                FROM marks_raw
                WHERE participant_id = ?
                ORDER BY seq
                "#,
            )
            .map_err(|e| format!("prepare raw marks query: {e}"))?;
        let raw_rows = raw_stmt
            .query_map(params![participant_id], |r| {
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

    let marks = load_marks(&tx, participant_id)?;
    let mut corrected_marks = marks.clone();
    let mut exclusion_rules = load_leg_exclusion_rules(&tx, participant_id)?;
    apply_legacy_corrections(&tx, participant_id, &mut corrected_marks, &mut exclusion_rules)?;
    corrected_marks.sort_by_key(|m| (m.mark_time, m.seq));

    let corrections = {
        let mut correction_stmt = tx
            .prepare(
                r#"
                SELECT id, participant_id, correction_type, payload_json, created_at, 'legacy_corrections' as source_table
                FROM corrections
                WHERE participant_id = ?
                UNION ALL
                SELECT id, participant_id, correction_type, payload_json, created_at, 'manual_corrections' as source_table
                FROM manual_corrections
                WHERE participant_id = ? OR participant_id IS NULL
                ORDER BY id
                "#,
            )
            .map_err(|e| format!("prepare corrections list query: {e}"))?;
        let correction_rows = correction_stmt
            .query_map(params![participant_id, participant_id], |r| {
                let payload_raw: String = r.get(3)?;
                let payload: Value = serde_json::from_str(&payload_raw).map_err(|e| {
                    rusqlite::Error::FromSqlConversionFailure(
                        3,
                        rusqlite::types::Type::Text,
                        Box::new(std::io::Error::new(std::io::ErrorKind::InvalidData, e)),
                    )
                })?;
                let source_table: String = r.get(5)?;
                let participant_scope: Option<String> = r.get(1)?;
                Ok(CorrectionRow {
                    id: r.get(0)?,
                    participant_id: participant_scope.clone(),
                    scope: if participant_scope.is_some() {
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
    };

    let result = tx
        .query_row(
            r#"
            SELECT
                   participant_id,
                   name,
                   status,
                   EXISTS(
                     SELECT 1 FROM corrections c
                     WHERE c.participant_id = results.participant_id
                   ) OR EXISTS(
                     SELECT 1 FROM manual_corrections mc
                     WHERE mc.participant_id = results.participant_id
                   ) AS has_personal_corrections,
                   points_raw, penalty_points, points_final,
                   elapsed_seconds, delay_seconds, penalty_minutes, diagnostics_json, computed_at
            FROM results
            WHERE participant_id = ?
            "#,
            params![participant_id],
            |r| {
                Ok(ResultRow {
                    participant_id: r.get(0)?,
                    name: r.get(1)?,
                    status: r.get(2)?,
                    has_personal_corrections: r.get(3)?,
                    has_anomalies: false,
                    anomaly_count: 0,
                    points_raw: r.get(4)?,
                    penalty_points: r.get(5)?,
                    points_final: r.get(6)?,
                    elapsed_seconds: r.get(7)?,
                    delay_seconds: r.get(8)?,
                    penalty_minutes: r.get(9)?,
                    diagnostics_json: r.get(10)?,
                    computed_at: r.get(11)?,
                })
            },
        )
        .optional()
        .map_err(|e| format!("query participant result: {e}"))?;
    let all_anomalies = {
        let mut anomalies_stmt = tx
            .prepare(
                r#"
                SELECT pa.anomaly_type, pa.title, pa.details, pa.payload_json, mc.id, mc.created_at
                FROM participant_anomalies pa
                LEFT JOIN manual_corrections mc
                  ON mc.participant_id = pa.participant_id
                 AND mc.correction_type = 'anomaly_day_shift_24h'
                 AND pa.anomaly_type = 'start_time_day_shift'
                WHERE pa.participant_id = ?
                ORDER BY pa.id
                "#,
            )
            .map_err(|e| format!("prepare participant anomalies query: {e}"))?;
        let anomalies_rows = anomalies_stmt
            .query_map(params![participant_id], |r| {
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
    let mut result = result;
    if let Some(result_row) = result.as_mut() {
        result_row.has_anomalies = !anomalies.is_empty();
        result_row.anomaly_count = anomalies.len() as i64;
    }
    tx.rollback()
        .map_err(|e| format!("rollback participant details transaction: {e}"))?;

    Ok(ParticipantDetails {
        participant,
        result,
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

fn require_participant(conn: &Connection, participant_id: &str) -> Result<(), String> {
    let exists = conn
        .query_row(
            "SELECT 1 FROM participants WHERE participant_id = ? LIMIT 1",
            params![participant_id],
            |_| Ok(()),
        )
        .optional()
        .map_err(|e| format!("check participant exists: {e}"))?;
    if exists.is_none() {
        return Err(format!("Participant {participant_id} not found"));
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

pub fn add_cp_correction(
    conn: &Connection,
    participant_id: &str,
    cp_number: i64,
    _mark_time: &str,
) -> Result<(), String> {
    require_participant(conn, participant_id)?;
    let duplicate: Option<i64> = conn
        .query_row(
            r#"
            SELECT id
            FROM manual_corrections
            WHERE participant_id = ?
              AND correction_type = 'add_cp'
              AND json_extract(payload_json, '$.cp_number') = ?
            LIMIT 1
            "#,
            params![participant_id, cp_number],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("check duplicate add_cp correction: {e}"))?;
    if duplicate.is_some() {
        return Err(format!(
            "Для участника {participant_id} корректировка добавления КП {cp_number} уже существует"
        ));
    }
    conn.execute(
        r#"
        INSERT INTO manual_corrections(participant_id, correction_type, payload_json)
        VALUES(?, 'add_cp', ?)
        "#,
        params![
            participant_id,
            json!({
                "cp_number": cp_number
            })
            .to_string()
        ],
    )
    .map_err(|e| format!("insert personal add_cp correction: {e}"))?;
    Ok(())
}

pub fn add_anomaly_day_shift_correction(conn: &Connection, participant_id: &str) -> Result<(), String> {
    require_participant(conn, participant_id)?;
    let anomaly_exists: Option<i64> = conn
        .query_row(
            r#"
            SELECT id
            FROM participant_anomalies
            WHERE participant_id = ?
              AND anomaly_type = 'start_time_day_shift'
            LIMIT 1
            "#,
            params![participant_id],
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
            WHERE participant_id = ?
              AND correction_type = 'anomaly_day_shift_24h'
            LIMIT 1
            "#,
            params![participant_id],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("check duplicate anomaly correction: {e}"))?;
    if duplicate.is_some() {
        return Err("Корректировка аномалии (-24ч) уже существует для этого участника".to_string());
    }
    conn.execute(
        r#"
        INSERT INTO manual_corrections(participant_id, correction_type, payload_json)
        VALUES(?, 'anomaly_day_shift_24h', ?)
        "#,
        params![
            participant_id,
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

pub fn add_anomaly_day_shift_corrections_for_all(
    conn: &Connection,
) -> Result<BulkAnomalyCorrectionSummary, String> {
    let participants_with_anomaly: i64 = conn
        .query_row(
            r#"
            SELECT COUNT(*)
            FROM (
                SELECT DISTINCT participant_id
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
            INSERT INTO manual_corrections(participant_id, correction_type, payload_json)
            SELECT a.participant_id, 'anomaly_day_shift_24h', ?
            FROM (
                SELECT DISTINCT participant_id
                FROM participant_anomalies
                WHERE anomaly_type = 'start_time_day_shift'
            ) a
            WHERE NOT EXISTS (
                SELECT 1
                FROM manual_corrections mc
                WHERE mc.participant_id = a.participant_id
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
                SELECT DISTINCT a.participant_id
                FROM participant_anomalies a
                WHERE a.anomaly_type = 'start_time_day_shift'
                  AND NOT EXISTS (
                    SELECT 1
                    FROM manual_corrections mc
                    WHERE mc.participant_id = a.participant_id
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
                SELECT DISTINCT participant_id
                FROM manual_corrections
                WHERE correction_type = 'anomaly_day_shift_24h'
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
    let scoped_participant_id = if participant_scope == "one" {
        let pid = participant_id
            .filter(|x| !x.trim().is_empty())
            .ok_or_else(|| "participant_id is required for participant_scope=one".to_string())?;
        require_participant(conn, &pid)?;
        Some(pid)
    } else if participant_scope == "all" {
        None
    } else {
        return Err("participant_scope must be one of: one, all".to_string());
    };
    if remove_mode != "remove_legs" && remove_mode != "points_only" {
        return Err("remove_mode must be one of: remove_legs, points_only".to_string());
    }
    let duplicate: Option<i64> = if let Some(pid) = &scoped_participant_id {
        conn.query_row(
            r#"
            SELECT id
            FROM manual_corrections
            WHERE participant_id = ?
              AND correction_type = 'remove_cp'
              AND json_extract(payload_json, '$.cp_number') = ?
            LIMIT 1
            "#,
            params![pid, cp_number],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| format!("check duplicate personal remove_cp correction: {e}"))?
    } else {
        conn.query_row(
            r#"
            SELECT id
            FROM manual_corrections
            WHERE participant_id IS NULL
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
        INSERT INTO manual_corrections(participant_id, correction_type, payload_json)
        VALUES(?, 'remove_cp', ?)
        "#,
        params![
            scoped_participant_id,
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
    from_cp: i64,
    to_cp: i64,
    direction: String,
    apply_mode: String,
    max_leg_seconds: Option<i64>,
) -> Result<(), String> {
    validate_rule_fields(&direction, &apply_mode, max_leg_seconds)?;
    let scoped_participant_id = if participant_scope == "one" {
        let pid = participant_id
            .filter(|x| !x.trim().is_empty())
            .ok_or_else(|| "participant_id is required for participant_scope=one".to_string())?;
        require_participant(conn, &pid)?;
        Some(pid)
    } else if participant_scope == "all" {
        None
    } else {
        return Err("participant_scope must be one of: one, all".to_string());
    };
    conn.execute(
        r#"
        INSERT INTO leg_exclusion_rules(
            participant_id, from_cp, to_cp, direction, apply_mode, max_leg_seconds
        ) VALUES(?, ?, ?, ?, ?, ?)
        "#,
        params![
            scoped_participant_id,
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
    from_cp: i64,
    to_cp: i64,
    direction: String,
    apply_mode: String,
    max_leg_seconds: Option<i64>,
) -> Result<(), String> {
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
    let scoped_participant_id = if participant_scope == "one" {
        let pid = participant_id
            .filter(|x| !x.trim().is_empty())
            .ok_or_else(|| "participant_id is required for participant_scope=one".to_string())?;
        require_participant(conn, &pid)?;
        Some(pid)
    } else if participant_scope == "all" {
        None
    } else {
        return Err("participant_scope must be one of: one, all".to_string());
    };
    conn.execute(
        r#"
        UPDATE leg_exclusion_rules
        SET participant_id = ?, from_cp = ?, to_cp = ?, direction = ?, apply_mode = ?, max_leg_seconds = ?
        WHERE id = ?
        "#,
        params![
            scoped_participant_id,
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
) -> Result<Vec<ExclusionRuleRow>, String> {
    let (sql, params_vec): (&str, Vec<rusqlite::types::Value>) = if let Some(pid) = participant_id {
        (
            r#"
            SELECT id, participant_id, from_cp, to_cp, direction, apply_mode, max_leg_seconds, created_at
            FROM leg_exclusion_rules
            WHERE participant_id = ? OR participant_id IS NULL
            ORDER BY id
            "#,
            vec![rusqlite::types::Value::Text(pid)],
        )
    } else {
        (
            r#"
            SELECT id, participant_id, from_cp, to_cp, direction, apply_mode, max_leg_seconds, created_at
            FROM leg_exclusion_rules
            ORDER BY id
            "#,
            vec![],
        )
    };
    let mut stmt = conn
        .prepare(sql)
        .map_err(|e| format!("prepare exclusion rules query: {e}"))?;
    let rows = stmt
        .query_map(rusqlite::params_from_iter(params_vec), |r| {
            Ok(ExclusionRuleRow {
                id: r.get(0)?,
                participant_id: r.get(1)?,
                from_cp: r.get(2)?,
                to_cp: r.get(3)?,
                direction: r.get(4)?,
                apply_mode: r.get(5)?,
                max_leg_seconds: r.get(6)?,
                created_at: r.get(7)?,
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
        (
            r#"
            SELECT id, participant_id, correction_type, payload_json, created_at
            FROM manual_corrections
            WHERE participant_id = ? OR participant_id IS NULL
            ORDER BY id DESC
            "#,
            vec![rusqlite::types::Value::Text(pid)],
        )
    } else {
        (
            r#"
            SELECT id, participant_id, correction_type, payload_json, created_at
            FROM manual_corrections
            WHERE participant_id IS NULL
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
            let pid: Option<String> = r.get(1)?;
            Ok(CorrectionRow {
                id: r.get(0)?,
                participant_id: pid.clone(),
                scope: if pid.is_some() {
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
    tx.execute("DELETE FROM marks_raw", [])?;
    tx.execute("DELETE FROM participants", [])?;
    tx.execute("DELETE FROM results", [])?;
    tx.execute("DELETE FROM participant_anomalies", [])?;
    tx.execute("DELETE FROM corrections", [])?;
    tx.execute("DELETE FROM manual_corrections", [])?;
    tx.execute("DELETE FROM leg_exclusion_rules", [])?;
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

fn detect_anomalies_for_start_time(start_time: &str, settings: &Settings) -> Vec<ParticipantAnomaly> {
    let mut anomalies = Vec::new();
    let Some(config_start_dt) = competition_start_datetime(settings) else {
        return anomalies;
    };
    let Ok(participant_start_dt) = parse_time(start_time) else {
        return anomalies;
    };
    if participant_start_dt.date() != config_start_dt.date() {
        let diff_seconds = (participant_start_dt - config_start_dt).num_seconds();
        anomalies.push(ParticipantAnomaly {
            anomaly_type: "start_time_day_shift".to_string(),
            title: "Сдвиг даты старта".to_string(),
            details: format!(
                "Дата старта участника {} не совпадает с датой соревнования {}",
                participant_start_dt.format("%Y-%m-%d %H:%M:%S"),
                config_start_dt.format("%Y-%m-%d")
            ),
            payload: json!({
                "participant_start_time": participant_start_dt.format("%Y-%m-%d %H:%M:%S").to_string(),
                "competition_start_datetime": config_start_dt.format("%Y-%m-%d %H:%M:%S").to_string(),
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
