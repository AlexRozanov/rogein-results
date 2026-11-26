//! Receive-only field journal client for desktop (cloud + LAN).
//! Pulls ops; never POSTs; never claims publisher.

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::Connection;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::domain::{
    self, add_cp_correction, apply_field_chip_read, apply_field_course_delete,
    apply_field_course_upsert, apply_field_event_config, apply_field_exclusion_delete,
    apply_field_exclusion_upsert, apply_field_finish_reassign, apply_field_person_course,
    apply_field_person_delete, apply_field_person_upsert, apply_field_queue_drop,
    apply_field_team_assign, apply_field_team_join, apply_field_team_leave,
    assign_course_correction, copy_team_punches, delete_correction_entry, FieldApplyChange,
    FieldChipReadInput, finish_id_for_logical, forget_field_correction, last_manual_correction_id,
    load_field_cursors, lookup_field_correction, mark_did_not_finish, remove_cp_correction,
    remember_field_correction, store_field_op, field_op_known,
};
use crate::site_publish;

pub const FIELD_OP_SCHEMA: i32 = 1;
pub const FIELD_PROTOCOL_VERSION: i32 = 1;
pub const FIELD_DEFAULT_PORT: u16 = 8787;

const CONFIG_FILE: &str = "field_sync.json";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub enum FieldSyncMode {
    #[default]
    Off,
    Cloud,
    Lan,
}

impl FieldSyncMode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Off => "off",
            Self::Cloud => "cloud",
            Self::Lan => "lan",
        }
    }

    pub fn parse(raw: &str) -> Self {
        match raw.trim().to_ascii_lowercase().as_str() {
            "cloud" => Self::Cloud,
            "lan" => Self::Lan,
            _ => Self::Off,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FieldSyncSettings {
    /// Preferred transport. Empty/missing falls back to legacy `enabled`.
    #[serde(default)]
    pub mode: String,
    /// Legacy flag from cloud-only MVP (`true` ⇒ cloud when `mode` empty).
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub start_code: String,
    #[serde(default)]
    pub device_id: String,
    #[serde(default = "default_device_name")]
    pub device_name: String,
    /// LAN host base: `http://ip:8787`, raw IP, or `rogein-field://…` (normalized on save).
    #[serde(default)]
    pub lan_endpoint: String,
}

fn default_device_name() -> String {
    "Десктоп".to_string()
}

impl Default for FieldSyncSettings {
    fn default() -> Self {
        Self {
            mode: FieldSyncMode::Off.as_str().to_string(),
            enabled: false,
            start_code: String::new(),
            device_id: String::new(),
            device_name: default_device_name(),
            lan_endpoint: String::new(),
        }
    }
}

impl FieldSyncSettings {
    pub fn effective_mode(&self) -> FieldSyncMode {
        let trimmed = self.mode.trim();
        if trimmed.is_empty() {
            if self.enabled {
                FieldSyncMode::Cloud
            } else {
                FieldSyncMode::Off
            }
        } else {
            FieldSyncMode::parse(trimmed)
        }
    }

    pub fn receive_enabled(&self) -> bool {
        self.effective_mode() != FieldSyncMode::Off
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FieldSyncUiSettings {
    pub mode: String,
    pub enabled: bool,
    pub start_code: String,
    pub device_id: String,
    pub device_name: String,
    pub lan_endpoint: String,
    pub has_site_access: bool,
    pub slug: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FieldSyncTickResult {
    pub enabled: bool,
    pub mode: String,
    pub pulled: i64,
    pub applied: i64,
    pub skipped: i64,
    pub linked: bool,
    pub status: String,
    pub error: String,
}

#[derive(Debug, Clone)]
pub struct FieldOpEnvelope {
    pub schema: i32,
    pub op_id: String,
    pub device_id: String,
    pub device_seq: i64,
    pub event_slug: String,
    pub clock_ms: i64,
    pub actor: String,
    pub op_type: String,
    pub payload: Value,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FieldJoinUri {
    pub host: String,
    pub port: u16,
    pub slug: String,
    pub code: String,
}

fn config_path(app_data_dir: &Path) -> PathBuf {
    app_data_dir.join(CONFIG_FILE)
}

fn new_device_id() -> String {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    format!("desktop-{nanos:x}")
}

pub fn load_settings(app_data_dir: &Path) -> FieldSyncSettings {
    let path = config_path(app_data_dir);
    let mut settings = match fs::read(&path) {
        Ok(bytes) => serde_json::from_slice(&bytes).unwrap_or_default(),
        Err(_) => FieldSyncSettings::default(),
    };
    if settings.device_id.trim().is_empty() {
        settings.device_id = new_device_id();
        let _ = save_settings(app_data_dir, &settings);
    }
    if settings.device_name.trim().is_empty() {
        settings.device_name = default_device_name();
    }
    // Persist migrated mode so UI/file stay consistent.
    let mode = settings.effective_mode();
    let mode_str = mode.as_str().to_string();
    if settings.mode.trim() != mode_str {
        settings.mode = mode_str;
        settings.enabled = mode != FieldSyncMode::Off;
        let _ = save_settings(app_data_dir, &settings);
    }
    settings
}

pub fn save_settings(app_data_dir: &Path, settings: &FieldSyncSettings) -> Result<(), String> {
    let mut next = settings.clone();
    if next.device_id.trim().is_empty() {
        next.device_id = new_device_id();
    }
    if next.device_name.trim().is_empty() {
        next.device_name = default_device_name();
    }
    next.start_code = next.start_code.trim().to_string();

    // Accept join URI pasted into lan_endpoint.
    if let Some(join) = parse_join_uri(&next.lan_endpoint) {
        next.lan_endpoint = build_lan_endpoint(&join.host, join.port);
        if !join.code.is_empty() {
            next.start_code = join.code;
        }
    } else if !next.lan_endpoint.trim().is_empty() {
        next.lan_endpoint = normalize_lan_endpoint(&next.lan_endpoint)?;
    } else {
        next.lan_endpoint.clear();
    }

    let mode = next.effective_mode();
    next.mode = mode.as_str().to_string();
    next.enabled = mode != FieldSyncMode::Off;

    if let Some(parent) = config_path(app_data_dir).parent() {
        fs::create_dir_all(parent).map_err(|e| format!("create app data dir: {e}"))?;
    }
    let json = serde_json::to_vec_pretty(&next)
        .map_err(|e| format!("serialize field sync config: {e}"))?;
    fs::write(config_path(app_data_dir), json)
        .map_err(|e| format!("write field sync config: {e}"))?;
    Ok(())
}

pub fn ui_settings(app_data_dir: &Path, conn: &Connection) -> Result<FieldSyncUiSettings, String> {
    let settings = load_settings(app_data_dir);
    let connection = site_publish::load_connection(app_data_dir);
    let (slug, _) = site_publish::load_event_meta(conn)?;
    let mode = settings.effective_mode();
    Ok(FieldSyncUiSettings {
        mode: mode.as_str().to_string(),
        enabled: mode != FieldSyncMode::Off,
        start_code: settings.start_code,
        device_id: settings.device_id,
        device_name: settings.device_name,
        lan_endpoint: settings.lan_endpoint,
        has_site_access: !connection.publish_token.trim().is_empty()
            && !connection.api_base_url.trim().is_empty(),
        slug,
    })
}

pub fn is_receive_enabled(app_data_dir: &Path) -> bool {
    load_settings(app_data_dir).receive_enabled()
}

/// Parse `rogein-field://<host>:<port>/<slug>/<code>` (§7.4).
pub fn parse_join_uri(raw: &str) -> Option<FieldJoinUri> {
    let value = raw.trim();
    if value.is_empty() {
        return None;
    }
    let body = if value.to_ascii_lowercase().starts_with("rogein-field://") {
        let idx = value.find("://").unwrap_or(0) + 3;
        &value[idx..]
    } else {
        return None;
    };
    let slash = body.find('/')?;
    let host_port = &body[..slash];
    let rest = &body[slash + 1..];
    let (host, port) = parse_host_endpoint(host_port, FIELD_DEFAULT_PORT);
    if host.is_empty() {
        return None;
    }
    let (slug, code) = match rest.rfind('/') {
        Some(i) => (rest[..i].trim_matches('/').to_string(), rest[i + 1..].trim().to_string()),
        None => (String::new(), rest.trim().to_string()),
    };
    if code.is_empty() {
        return None;
    }
    Some(FieldJoinUri {
        host,
        port,
        slug,
        code,
    })
}

fn parse_host_endpoint(raw: &str, default_port: u16) -> (String, u16) {
    let mut value = raw.trim().to_string();
    if value.is_empty() {
        return (String::new(), default_port);
    }
    for prefix in ["http://", "https://", "HTTP://", "HTTPS://"] {
        if let Some(rest) = value.strip_prefix(prefix) {
            value = rest.to_string();
            break;
        }
    }
    while value.ends_with('/') {
        value.pop();
    }
    if value.starts_with('[') {
        if let Some(end) = value.find(']') {
            let host = value[1..end].to_string();
            let rest = value[end + 1..].trim_start_matches(':');
            let port = rest
                .parse::<u16>()
                .ok()
                .filter(|p| *p > 0)
                .unwrap_or(default_port);
            return (host, port);
        }
    }
    if let Some(colon) = value.rfind(':') {
        let host = &value[..colon];
        let port_part = &value[colon + 1..];
        if let Ok(port) = port_part.parse::<u16>() {
            if port > 0 && host.chars().filter(|c| *c == '.').count() >= 1 {
                return (host.to_string(), port);
            }
            // hostname:port without dots (e.g. localhost:8787)
            if port > 0 && !host.is_empty() && !host.contains('/') {
                return (host.to_string(), port);
            }
        }
    }
    (value, default_port)
}

fn build_lan_endpoint(host: &str, port: u16) -> String {
    let host = host
        .trim()
        .trim_start_matches("http://")
        .trim_start_matches("https://")
        .trim_end_matches('/')
        .trim_end_matches(':');
    if host.is_empty() {
        return String::new();
    }
    let port = if port == 0 { FIELD_DEFAULT_PORT } else { port };
    format!("http://{host}:{port}")
}

/// Normalize IP / URL / join-URI to `http://host:port`.
pub fn normalize_lan_endpoint(raw: &str) -> Result<String, String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err("укажите адрес LAN-хоста".to_string());
    }
    if let Some(join) = parse_join_uri(trimmed) {
        return Ok(build_lan_endpoint(&join.host, join.port));
    }
    let (host, port) = parse_host_endpoint(trimmed, FIELD_DEFAULT_PORT);
    if host.is_empty() {
        return Err("некорректный адрес LAN-хоста".to_string());
    }
    Ok(build_lan_endpoint(&host, port))
}

fn percent_encode_component(raw: &str) -> String {
    let mut out = String::new();
    for b in raw.as_bytes() {
        match *b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*b as char);
            }
            b' ' => out.push_str("%20"),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

fn cloud_field_base_url(api_base: &str, slug: &str) -> Result<String, String> {
    let base = site_publish::normalize_base_url(api_base)?;
    let encoded = percent_encode_component(slug.trim());
    Ok(format!("{base}/api/field/{encoded}"))
}

fn open_get(
    url: &str,
    bearer_token: Option<&str>,
    start_code: &str,
    device_id: &str,
    device_name: &str,
) -> Result<(u16, String), String> {
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(std::time::Duration::from_secs(5))
        .timeout_read(std::time::Duration::from_secs(30))
        .build();
    let encoded_name = percent_encode_component(device_name);
    let mut req = agent
        .get(url)
        .set("X-RogeIn-Field-Code", start_code.trim())
        .set("X-RogeIn-Device-Id", device_id.trim())
        .set("X-RogeIn-Device-Name", &encoded_name);
    if let Some(token) = bearer_token.map(str::trim).filter(|t| !t.is_empty()) {
        req = req.set("Authorization", &format!("Bearer {token}"));
    }
    match req.call() {
        Ok(r) => {
            let status = r.status();
            let body = r.into_string().unwrap_or_default();
            Ok((status, body))
        }
        Err(ureq::Error::Status(code, r)) => {
            let body = r.into_string().unwrap_or_default();
            Ok((code, body))
        }
        Err(e) => Err(format!("field sync HTTP: {e}")),
    }
}

fn parse_envelope(value: &Value) -> Option<FieldOpEnvelope> {
    let obj = value.as_object()?;
    let payload = match obj.get("payload") {
        Some(Value::Object(_)) | Some(Value::Array(_)) => {
            obj.get("payload").cloned().unwrap_or(json!({}))
        }
        Some(Value::String(s)) => serde_json::from_str(s).unwrap_or(json!({})),
        _ => json!({}),
    };
    Some(FieldOpEnvelope {
        schema: obj.get("schema").and_then(|v| v.as_i64()).unwrap_or(1) as i32,
        op_id: obj.get("op_id")?.as_str()?.trim().to_string(),
        device_id: obj.get("device_id")?.as_str()?.trim().to_string(),
        device_seq: obj.get("device_seq")?.as_i64()?,
        event_slug: obj
            .get("event_slug")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim()
            .to_string(),
        clock_ms: obj.get("clock_ms").and_then(|v| v.as_i64()).unwrap_or(0),
        actor: obj
            .get("actor")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim()
            .to_string(),
        op_type: obj.get("type")?.as_str()?.trim().to_string(),
        payload,
    })
}

fn parse_ops_bundle(body: &str) -> Result<Vec<FieldOpEnvelope>, String> {
    let root: Value =
        serde_json::from_str(body).map_err(|e| format!("field ops JSON: {e}"))?;
    let arr = root
        .get("ops")
        .and_then(|v| v.as_array())
        .ok_or_else(|| "ответ без массива ops".to_string())?;
    Ok(arr.iter().filter_map(parse_envelope).collect())
}

fn cursors_query(cursors: &BTreeMap<String, i64>) -> String {
    let obj = Value::Object(
        cursors
            .iter()
            .map(|(k, v)| (k.clone(), json!(*v)))
            .collect(),
    );
    percent_encode_component(&obj.to_string())
}

fn payload_i64(payload: &Value, key: &str) -> Option<i64> {
    payload.get(key).and_then(|v| {
        v.as_i64()
            .or_else(|| v.as_f64().map(|f| f as i64))
            .or_else(|| v.as_str()?.parse().ok())
    })
}

fn payload_str(payload: &Value, key: &str) -> String {
    payload
        .get(key)
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .trim()
        .to_string()
}

fn payload_bool(payload: &Value, key: &str) -> bool {
    payload
        .get(key)
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
}

fn apply_one(conn: &mut Connection, op: &FieldOpEnvelope) -> Result<FieldApplyChange, String> {
    match op.op_type.as_str() {
        "chip_read" => {
            let queue_id = payload_str(&op.payload, "queue_id");
            let uid = payload_str(&op.payload, "uid");
            let logical_id = payload_i64(&op.payload, "logical_id").filter(|id| *id > 0);
            let course_hint = payload_str(&op.payload, "course_hint");
            let replace = payload_bool(&op.payload, "replace");
            let punches = op
                .payload
                .get("punches")
                .and_then(|v| v.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|p| {
                            let cp = payload_i64(p, "cp")?;
                            let seconds = payload_i64(p, "seconds").unwrap_or(0) as i32;
                            Some((cp, seconds))
                        })
                        .collect::<Vec<_>>()
                })
                .unwrap_or_default();
            apply_field_chip_read(
                conn,
                &FieldChipReadInput {
                    queue_id,
                    uid,
                    logical_id,
                    punches,
                    course_hint,
                    replace,
                    name: None,
                },
            )
        }
        "queue_drop" => {
            let queue_id = payload_str(&op.payload, "queue_id");
            apply_field_queue_drop(conn, &queue_id)
        }
        "finish_reassign" => {
            let queue_id = payload_str(&op.payload, "queue_id");
            let new_logical = payload_i64(&op.payload, "new_logical_id").unwrap_or(0);
            let uid = payload_str(&op.payload, "uid");
            apply_field_finish_reassign(conn, &queue_id, new_logical, &uid)
        }
        "person_upsert" => {
            let logical_id = payload_i64(&op.payload, "logical_id").unwrap_or(0);
            let name = payload_str(&op.payload, "name");
            let gender = payload_str(&op.payload, "gender");
            let birth = payload_str(&op.payload, "birth_date");
            let course = payload_str(&op.payload, "course");
            let courses = op
                .payload
                .get("courses")
                .and_then(|v| v.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|x| x.as_str().map(|s| s.trim().to_string()))
                        .filter(|s| !s.is_empty())
                        .collect::<Vec<_>>()
                })
                .unwrap_or_default();
            apply_field_person_upsert(conn, logical_id, &name, &gender, &birth, &course, &courses)
        }
        "correction_add_cp" => {
            let correction_id = payload_str(&op.payload, "correction_id");
            if lookup_field_correction(conn, &correction_id)?.is_some() {
                return Ok(FieldApplyChange::Skipped);
            }
            let logical_id = payload_i64(&op.payload, "logical_id").unwrap_or(0);
            let cp = payload_i64(&op.payload, "cp").unwrap_or(0);
            let time = payload_str(&op.payload, "time");
            let Some(finish_id) = finish_id_for_logical(conn, logical_id)? else {
                return Ok(FieldApplyChange::Skipped);
            };
            add_cp_correction(conn, &finish_id.to_string(), cp, &time)?;
            if let Some(local) = last_manual_correction_id(conn)? {
                remember_field_correction(conn, &correction_id, local)?;
            }
            Ok(FieldApplyChange::Applied)
        }
        "correction_remove_cp" => {
            let correction_id = payload_str(&op.payload, "correction_id");
            if lookup_field_correction(conn, &correction_id)?.is_some() {
                return Ok(FieldApplyChange::Skipped);
            }
            let logical_id = payload_i64(&op.payload, "logical_id");
            let cp = payload_i64(&op.payload, "cp").unwrap_or(0);
            let remove_mode = payload_str(&op.payload, "remove_mode");
            let mode = if remove_mode.is_empty() {
                "points_only".to_string()
            } else {
                remove_mode
            };
            if let Some(lid) = logical_id.filter(|id| *id > 0) {
                let Some(finish_id) = finish_id_for_logical(conn, lid)? else {
                    return Ok(FieldApplyChange::Skipped);
                };
                remove_cp_correction(
                    conn,
                    Some(finish_id.to_string()),
                    cp,
                    mode,
                    "one".to_string(),
                )?;
            } else {
                remove_cp_correction(conn, None, cp, mode, "all".to_string())?;
            }
            if let Some(local) = last_manual_correction_id(conn)? {
                remember_field_correction(conn, &correction_id, local)?;
            }
            Ok(FieldApplyChange::Applied)
        }
        "correction_dnf" => {
            let correction_id = payload_str(&op.payload, "correction_id");
            let logical_id = payload_i64(&op.payload, "logical_id").unwrap_or(0);
            if logical_id <= 0 {
                return Ok(FieldApplyChange::Skipped);
            }
            let _ = mark_did_not_finish(conn, &logical_id.to_string())?;
            if !correction_id.is_empty() {
                if let Some(local) = last_manual_correction_id(conn)? {
                    remember_field_correction(conn, &correction_id, local)?;
                }
            }
            Ok(FieldApplyChange::Applied)
        }
        "correction_clear_dnf" => {
            let logical_id = payload_i64(&op.payload, "logical_id").unwrap_or(0);
            let Some(finish_id) = finish_id_for_logical(conn, logical_id)? else {
                return Ok(FieldApplyChange::Skipped);
            };
            conn.execute(
                "DELETE FROM manual_corrections WHERE finish_participant_id = ? AND correction_type = 'did_not_finish'",
                rusqlite::params![finish_id],
            )
            .map_err(|e| format!("clear dnf: {e}"))?;
            Ok(FieldApplyChange::Applied)
        }
        "correction_assign_course" => {
            let correction_id = payload_str(&op.payload, "correction_id");
            if lookup_field_correction(conn, &correction_id)?.is_some() {
                return Ok(FieldApplyChange::Skipped);
            }
            let logical_id = payload_i64(&op.payload, "logical_id").unwrap_or(0);
            let to_course = payload_str(&op.payload, "to_course");
            let Some(finish_id) = finish_id_for_logical(conn, logical_id)? else {
                return Ok(FieldApplyChange::Skipped);
            };
            assign_course_correction(conn, &finish_id.to_string(), &to_course)?;
            if let Some(local) = last_manual_correction_id(conn)? {
                remember_field_correction(conn, &correction_id, local)?;
            }
            Ok(FieldApplyChange::Applied)
        }
        "correction_copy_punches" => {
            let correction_id = payload_str(&op.payload, "correction_id");
            if lookup_field_correction(conn, &correction_id)?.is_some() {
                return Ok(FieldApplyChange::Skipped);
            }
            let logical_id = payload_i64(&op.payload, "logical_id").unwrap_or(0);
            let from_logical = payload_i64(&op.payload, "from_logical_id").unwrap_or(0);
            let Some(source) = finish_id_for_logical(conn, from_logical)? else {
                return Ok(FieldApplyChange::Skipped);
            };
            let _ = copy_team_punches(conn, source, &logical_id.to_string())?;
            if let Some(local) = last_manual_correction_id(conn)? {
                remember_field_correction(conn, &correction_id, local)?;
            }
            Ok(FieldApplyChange::Applied)
        }
        "correction_delete" => {
            let correction_id = payload_str(&op.payload, "correction_id");
            let Some(local) = lookup_field_correction(conn, &correction_id)? else {
                return Ok(FieldApplyChange::Skipped);
            };
            delete_correction_entry(conn, "manual_corrections".to_string(), local)?;
            forget_field_correction(conn, &correction_id)?;
            Ok(FieldApplyChange::Applied)
        }
        "person_delete" => {
            let logical_id = payload_i64(&op.payload, "logical_id").unwrap_or(0);
            apply_field_person_delete(conn, logical_id)
        }
        "person_course" => {
            let logical_id = payload_i64(&op.payload, "logical_id").unwrap_or(0);
            let course = payload_str(&op.payload, "course");
            let courses = op
                .payload
                .get("courses")
                .and_then(|v| v.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|x| x.as_str().map(|s| s.trim().to_string()))
                        .filter(|s| !s.is_empty())
                        .collect::<Vec<_>>()
                })
                .unwrap_or_default();
            apply_field_person_course(conn, logical_id, &course, &courses)
        }
        "person_team_join" => {
            let logical_id = payload_i64(&op.payload, "logical_id").unwrap_or(0);
            let other = payload_i64(&op.payload, "other_logical_id").unwrap_or(0);
            let team_id = payload_i64(&op.payload, "team_id").unwrap_or(0);
            let dropped = payload_i64(&op.payload, "dropped_team_id").unwrap_or(0);
            apply_field_team_join(conn, logical_id, other, team_id, dropped)
        }
        "person_team_leave" => {
            let logical_id = payload_i64(&op.payload, "logical_id").unwrap_or(0);
            apply_field_team_leave(conn, logical_id)
        }
        "person_team_assign" => {
            let logical_id = payload_i64(&op.payload, "logical_id").unwrap_or(0);
            let team_id = payload_i64(&op.payload, "team_id").unwrap_or(0);
            apply_field_team_assign(conn, logical_id, team_id)
        }
        "event_config" => apply_field_event_config(conn, &op.payload),
        "course_upsert" => {
            let name = payload_str(&op.payload, "name");
            let previous_name = payload_str(&op.payload, "previous_name");
            let controls = op
                .payload
                .get("controls")
                .and_then(|v| v.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|x| x.as_i64().or_else(|| x.as_u64().map(|u| u as i64)))
                        .collect::<Vec<_>>()
                })
                .unwrap_or_default();
            let random_order = op
                .payload
                .get("random_order")
                .and_then(|v| v.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|x| {
                            x.as_i64()
                                .or_else(|| x.as_u64().map(|u| u as i64))
                                .map(|n| n as i32)
                        })
                        .collect::<Vec<_>>()
                })
                .unwrap_or_default();
            let length_meters = payload_i64(&op.payload, "length_meters");
            let climb_meters = payload_i64(&op.payload, "climb_meters");
            let legs_json = payload_str(&op.payload, "legs_json");
            apply_field_course_upsert(
                conn,
                &name,
                &previous_name,
                &controls,
                &random_order,
                length_meters,
                climb_meters,
                &legs_json,
            )
        }
        "course_delete" => {
            let name = payload_str(&op.payload, "name");
            apply_field_course_delete(conn, &name)
        }
        "exclusion_upsert" => {
            let exclusion_id = payload_str(&op.payload, "exclusion_id");
            let course = payload_str(&op.payload, "course");
            let from_cp = payload_i64(&op.payload, "from_cp").unwrap_or(0);
            let to_cp = payload_i64(&op.payload, "to_cp").unwrap_or(0);
            let max_leg_seconds = payload_i64(&op.payload, "max_leg_seconds");
            apply_field_exclusion_upsert(
                conn,
                &exclusion_id,
                &course,
                from_cp,
                to_cp,
                max_leg_seconds,
            )
        }
        "exclusion_delete" => {
            let exclusion_id = payload_str(&op.payload, "exclusion_id");
            let course = payload_str(&op.payload, "course");
            let from_cp = payload_i64(&op.payload, "from_cp").unwrap_or(0);
            let to_cp = payload_i64(&op.payload, "to_cp").unwrap_or(0);
            apply_field_exclusion_delete(conn, &exclusion_id, &course, from_cp, to_cp)
        }
        "chip_issue" => Ok(FieldApplyChange::Skipped),
        _ => Ok(FieldApplyChange::Skipped),
    }
}

struct IngestStats {
    pulled: i64,
    applied: i64,
    skipped: i64,
}

fn ingest_ops(conn: &mut Connection, ops: &[FieldOpEnvelope]) -> Result<IngestStats, String> {
    let pulled = ops.len() as i64;
    let mut applied = 0_i64;
    let mut skipped = 0_i64;
    let mut need_recalc = false;
    for op in ops {
        if op.op_id.is_empty() || op.device_id.is_empty() {
            skipped += 1;
            continue;
        }
        if field_op_known(conn, &op.op_id)? {
            skipped += 1;
            continue;
        }
        let change = match apply_one(conn, op) {
            Ok(c) => c,
            Err(err) => {
                eprintln!("field op {} ({}) apply: {err}", op.op_id, op.op_type);
                FieldApplyChange::Skipped
            }
        };
        let payload_json = op.payload.to_string();
        store_field_op(
            conn,
            &op.op_id,
            &op.device_id,
            op.device_seq,
            &op.op_type,
            op.clock_ms,
            &op.actor,
            &payload_json,
        )?;
        match change {
            FieldApplyChange::Applied => {
                applied += 1;
                need_recalc = true;
            }
            FieldApplyChange::Skipped => skipped += 1,
        }
    }
    if need_recalc {
        domain::recalculate(conn)?;
    }
    Ok(IngestStats {
        pulled,
        applied,
        skipped,
    })
}

fn validate_hello(hello_body: &str, local_slug: &str, peer_label: &str) -> Result<(), String> {
    let hello: Value =
        serde_json::from_str(hello_body).map_err(|e| format!("hello JSON: {e}"))?;
    let protocol = hello
        .get("protocol_version")
        .and_then(|v| v.as_i64())
        .unwrap_or(0) as i32;
    if protocol > FIELD_PROTOCOL_VERSION {
        return Err(format!(
            "{peer_label} новее (protocol {protocol}) — обновите десктоп"
        ));
    }
    let schema = hello
        .get("schema")
        .and_then(|v| v.as_i64())
        .unwrap_or(0) as i32;
    if schema > FIELD_OP_SCHEMA {
        return Err(format!("{peer_label} schema {schema} — обновите десктоп"));
    }
    let remote_slug = hello
        .get("event_slug")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .trim();
    if !remote_slug.is_empty() && remote_slug != local_slug.trim() {
        return Err(format!(
            "slug не совпадает: локально «{local_slug}», у {peer_label} «{remote_slug}»"
        ));
    }
    Ok(())
}

fn tick_result(
    mode: FieldSyncMode,
    pulled: i64,
    applied: i64,
    skipped: i64,
    linked: bool,
    status: impl Into<String>,
    error: impl Into<String>,
) -> FieldSyncTickResult {
    FieldSyncTickResult {
        enabled: mode != FieldSyncMode::Off,
        mode: mode.as_str().to_string(),
        pulled,
        applied,
        skipped,
        linked,
        status: status.into(),
        error: error.into(),
    }
}

fn pull_and_ingest(
    conn: &mut Connection,
    base: &str,
    bearer_token: Option<&str>,
    settings: &FieldSyncSettings,
    mode: FieldSyncMode,
    peer_label: &str,
    local_slug: &str,
) -> Result<FieldSyncTickResult, String> {
    let hello_url = format!("{base}/hello");
    let (hello_code, hello_body) = open_get(
        &hello_url,
        bearer_token,
        &settings.start_code,
        &settings.device_id,
        &settings.device_name,
    )?;
    if hello_code == 401 {
        return Ok(tick_result(
            mode,
            0,
            0,
            0,
            false,
            "",
            if bearer_token.is_some() {
                "неверный токен или код старта"
            } else {
                "неверный код старта"
            },
        ));
    }
    if !(200..300).contains(&hello_code) {
        return Ok(tick_result(
            mode,
            0,
            0,
            0,
            false,
            "",
            format!("hello HTTP {hello_code}: {hello_body}"),
        ));
    }
    if let Err(err) = validate_hello(&hello_body, local_slug, peer_label) {
        return Ok(tick_result(mode, 0, 0, 0, false, "", err));
    }

    let cursors = load_field_cursors(conn)?;
    let ops_url = format!("{base}/ops?cursors={}", cursors_query(&cursors));
    let (ops_code, ops_body) = open_get(
        &ops_url,
        bearer_token,
        &settings.start_code,
        &settings.device_id,
        &settings.device_name,
    )?;
    if ops_code == 401 {
        return Ok(tick_result(
            mode,
            0,
            0,
            0,
            false,
            "",
            if bearer_token.is_some() {
                "неверный токен или код старта"
            } else {
                "неверный код старта"
            },
        ));
    }
    if !(200..300).contains(&ops_code) {
        return Ok(tick_result(
            mode,
            0,
            0,
            0,
            true,
            "hello ok",
            format!("GET ops HTTP {ops_code}: {ops_body}"),
        ));
    }

    let ops = parse_ops_bundle(&ops_body)?;
    let stats = ingest_ops(conn, &ops)?;
    let status = if stats.pulled == 0 {
        "синхронно".to_string()
    } else {
        format!("принято {} из {}", stats.applied, stats.pulled)
    };
    Ok(tick_result(
        mode,
        stats.pulled,
        stats.applied,
        stats.skipped,
        true,
        status,
        "",
    ))
}

fn tick_cloud(
    app_data_dir: &Path,
    conn: &mut Connection,
    settings: &FieldSyncSettings,
) -> Result<FieldSyncTickResult, String> {
    let mode = FieldSyncMode::Cloud;
    let connection = site_publish::load_connection(app_data_dir);
    let (slug, _) = site_publish::load_event_meta(conn)?;
    if slug.trim().is_empty() {
        return Ok(tick_result(
            mode,
            0,
            0,
            0,
            false,
            "",
            "нужен slug старта (публикация)",
        ));
    }
    if connection.publish_token.trim().is_empty() {
        return Ok(tick_result(mode, 0, 0, 0, false, "", "нужен токен публикации"));
    }
    if settings.start_code.trim().is_empty() {
        return Ok(tick_result(
            mode,
            0,
            0,
            0,
            false,
            "",
            "укажите код старта полевого обмена",
        ));
    }
    let base = cloud_field_base_url(&connection.api_base_url, &slug)?;
    pull_and_ingest(
        conn,
        &base,
        Some(&connection.publish_token),
        settings,
        mode,
        "сайт",
        &slug,
    )
}

fn tick_lan(
    conn: &mut Connection,
    settings: &FieldSyncSettings,
) -> Result<FieldSyncTickResult, String> {
    let mode = FieldSyncMode::Lan;
    let (slug, _) = site_publish::load_event_meta(conn)?;
    if slug.trim().is_empty() {
        return Ok(tick_result(
            mode,
            0,
            0,
            0,
            false,
            "",
            "нужен slug старта (должен совпасть с хостом)",
        ));
    }
    if settings.start_code.trim().is_empty() {
        return Ok(tick_result(
            mode,
            0,
            0,
            0,
            false,
            "",
            "укажите код старта полевого обмена",
        ));
    }
    let endpoint = match normalize_lan_endpoint(&settings.lan_endpoint) {
        Ok(v) => v,
        Err(err) => return Ok(tick_result(mode, 0, 0, 0, false, "", err)),
    };
    let base = format!("{endpoint}/field");
    pull_and_ingest(conn, &base, None, settings, mode, "хост", &slug)
}

pub fn tick(app_data_dir: &Path, conn: &mut Connection) -> Result<FieldSyncTickResult, String> {
    let settings = load_settings(app_data_dir);
    match settings.effective_mode() {
        FieldSyncMode::Off => Ok(tick_result(
            FieldSyncMode::Off,
            0,
            0,
            0,
            false,
            "выкл",
            "",
        )),
        FieldSyncMode::Cloud => tick_cloud(app_data_dir, conn, &settings),
        FieldSyncMode::Lan => tick_lan(conn, &settings),
    }
}

/// Expose for tests.
#[cfg(test)]
pub fn parse_ops_bundle_for_test(body: &str) -> Result<Vec<FieldOpEnvelope>, String> {
    parse_ops_bundle(body)
}

#[cfg(test)]
pub fn ingest_ops_for_test(
    conn: &mut Connection,
    body: &str,
) -> Result<(i64, i64, i64), String> {
    let ops = parse_ops_bundle(body)?;
    let stats = ingest_ops(conn, &ops)?;
    Ok((stats.pulled, stats.applied, stats.skipped))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_ops_bundle_snake_case() {
        let body = r#"{
          "ops": [{
            "schema": 1,
            "op_id": "a1",
            "device_id": "phone-1",
            "device_seq": 3,
            "event_slug": "demo",
            "clock_ms": 1000,
            "actor": "Финиш",
            "type": "chip_read",
            "payload": {
              "queue_id": "q1",
              "uid": "AA",
              "logical_id": 8,
              "punches": [{"cp": 31, "seconds": 36000}],
              "course_hint": "D1"
            }
          }]
        }"#;
        let ops = parse_ops_bundle(body).unwrap();
        assert_eq!(ops.len(), 1);
        assert_eq!(ops[0].op_id, "a1");
        assert_eq!(ops[0].device_seq, 3);
        assert_eq!(ops[0].op_type, "chip_read");
        assert_eq!(ops[0].payload["queue_id"], "q1");
    }

    #[test]
    fn normalizes_lan_ip_and_join_uri() {
        assert_eq!(
            normalize_lan_endpoint("192.168.43.1").unwrap(),
            "http://192.168.43.1:8787"
        );
        assert_eq!(
            normalize_lan_endpoint("http://10.0.0.5:9000/").unwrap(),
            "http://10.0.0.5:9000"
        );
        assert_eq!(
            normalize_lan_endpoint("rogein-field://192.168.1.10:8787/start-demo/secret").unwrap(),
            "http://192.168.1.10:8787"
        );
        let join = parse_join_uri("rogein-field://192.168.1.10:8787/start-demo/secret").unwrap();
        assert_eq!(join.host, "192.168.1.10");
        assert_eq!(join.port, 8787);
        assert_eq!(join.slug, "start-demo");
        assert_eq!(join.code, "secret");
    }

    #[test]
    fn legacy_enabled_maps_to_cloud_mode() {
        let s = FieldSyncSettings {
            mode: String::new(),
            enabled: true,
            ..FieldSyncSettings::default()
        };
        assert_eq!(s.effective_mode(), FieldSyncMode::Cloud);
    }

    #[test]
    fn ingest_event_config_from_ops_bundle() {
        let mut conn = Connection::open_in_memory().unwrap();
        domain::init_db(&conn).unwrap();
        domain::ensure_schema_extras(&conn).unwrap();
        let body = r#"{
          "ops": [{
            "schema": 1,
            "op_id": "cfg-1",
            "device_id": "phone-lan",
            "device_seq": 1,
            "event_slug": "demo",
            "clock_ms": 1,
            "actor": "Хост",
            "type": "event_config",
            "payload": {"control_minutes": 180, "penalty_per_minute": 3}
          }]
        }"#;
        let (pulled, applied, skipped) = ingest_ops_for_test(&mut conn, body).unwrap();
        assert_eq!((pulled, applied, skipped), (1, 1, 0));
        assert_eq!(domain::get_settings(&conn).unwrap().control_minutes, 180);
        let (pulled2, applied2, skipped2) = ingest_ops_for_test(&mut conn, body).unwrap();
        assert_eq!(pulled2, 1);
        assert_eq!(applied2, 0);
        assert_eq!(skipped2, 1);
    }
}
