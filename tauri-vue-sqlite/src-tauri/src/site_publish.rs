use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use chrono::NaiveDate;
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};

use crate::domain::{self, AwardGroupRow, ResultRow};
use crate::archive;

const CONFIG_FILE: &str = "site_publish.json";
const SLUG_KEY: &str = "publish_slug";
const TITLE_KEY: &str = "publish_title";
const PROTOCOL_SOURCE_OFFSET: i64 = 1_000_000_000;
const RESULT_SOURCE_OFFSET: i64 = 2_000_000_000;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SiteConnection {
    #[serde(default = "default_api_base_url")]
    pub api_base_url: String,
    #[serde(default)]
    pub publish_token: String,
}

fn default_api_base_url() -> String {
    "http://127.0.0.1:18790".to_string()
}

impl Default for SiteConnection {
    fn default() -> Self {
        Self {
            api_base_url: default_api_base_url(),
            publish_token: String::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct SitePublishSettings {
    pub api_base_url: String,
    pub publish_token: String,
    pub slug: String,
    pub title: String,
    pub suggested_slug: String,
    pub suggested_title: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct SitePublishResult {
    pub slug: String,
    pub participant_count: usize,
    pub result_count: usize,
    pub has_map: bool,
}

#[derive(Debug, Serialize)]
struct PublishPayload {
    title: String,
    competition_date: Option<NaiveDate>,
    award_groups: Vec<PublishAwardGroup>,
    participants: Vec<PublishParticipant>,
    results: Vec<PublishResultRow>,
    map_points: Vec<PublishMapPoint>,
    meters_per_pixel: Option<f64>,
    sport_kind: String,
}

#[derive(Debug, Serialize)]
struct PublishAwardGroup {
    source_id: i64,
    name: String,
    gender_mode: String,
    min_age: Option<i32>,
    sort_order: i32,
}

#[derive(Debug, Clone, Serialize)]
struct PublishPathPoint {
    x: f64,
    y: f64,
    cp_number: Option<i32>,
    kind: String,
}

#[derive(Debug, Clone, Serialize)]
struct PublishMapPoint {
    kind: String,
    cp_number: Option<i32>,
    name: String,
    map_x: f64,
    map_y: f64,
}

#[derive(Debug, Clone, Serialize)]
struct PublishMark {
    seq: i32,
    cp_number: i32,
    mark_time: String,
}

#[derive(Debug, Serialize)]
struct PublishParticipant {
    source_id: i64,
    bib: String,
    chip_physical: Option<String>,
    chip_logical: Option<String>,
    name: String,
    gender: Option<String>,
    birth_date: Option<NaiveDate>,
    age: Option<i32>,
    team_id: Option<i64>,
    format_name: Option<String>,
    marks: Vec<PublishMark>,
    path: Vec<PublishPathPoint>,
    distance_m: Option<f64>,
}

#[derive(Debug, Serialize)]
struct PublishResultRow {
    participant_source_id: i64,
    award_group_source_id: i64,
    place: Option<i32>,
    points_raw: i32,
    penalty_points: i32,
    points_final: i32,
    elapsed_seconds: i32,
    status: String,
}

struct MapUpload {
    file_name: String,
    mime_type: String,
    bytes: Vec<u8>,
    width: Option<i32>,
    height: Option<i32>,
}

pub fn config_path(app_data_dir: &Path) -> PathBuf {
    app_data_dir.join(CONFIG_FILE)
}

pub fn load_connection(app_data_dir: &Path) -> SiteConnection {
    let path = config_path(app_data_dir);
    let Ok(bytes) = fs::read(&path) else {
        return SiteConnection::default();
    };
    serde_json::from_slice(&bytes).unwrap_or_default()
}

pub fn save_connection(app_data_dir: &Path, connection: &SiteConnection) -> Result<(), String> {
    let api_base_url = normalize_base_url(&connection.api_base_url)?;
    let payload = SiteConnection {
        api_base_url,
        publish_token: connection.publish_token.trim().to_string(),
    };
    if let Some(parent) = config_path(app_data_dir).parent() {
        fs::create_dir_all(parent).map_err(|e| format!("create app data dir: {e}"))?;
    }
    let json = serde_json::to_vec_pretty(&payload)
        .map_err(|e| format!("serialize site publish config: {e}"))?;
    fs::write(config_path(app_data_dir), json)
        .map_err(|e| format!("write site publish config: {e}"))?;
    Ok(())
}

pub fn load_event_meta(conn: &Connection) -> Result<(String, String), String> {
    let slug = domain::get_setting_value(conn, SLUG_KEY)?
        .unwrap_or_default()
        .trim()
        .to_string();
    let title = domain::get_setting_value(conn, TITLE_KEY)?
        .unwrap_or_default()
        .trim()
        .to_string();
    Ok((slug, title))
}

pub fn save_event_meta(conn: &Connection, slug: &str, title: &str) -> Result<(), String> {
    let slug = normalize_slug(slug)?;
    let title = title.trim();
    if title.is_empty() {
        return Err("Укажите название старта для сайта.".into());
    }
    domain::set_setting_value(conn, SLUG_KEY, &slug)?;
    domain::set_setting_value(conn, TITLE_KEY, title)?;
    Ok(())
}

pub fn suggested_slug(competition_date: &str) -> String {
    let date = competition_date.trim();
    if date.is_empty() {
        String::new()
    } else {
        format!("start-{date}")
    }
}

pub fn suggested_title(competition_date: &str, active_title: Option<&str>) -> String {
    if let Some(title) = active_title.map(str::trim).filter(|v| !v.is_empty()) {
        return title.to_string();
    }
    archive::default_archive_title(competition_date)
}

pub fn get_settings(
    app_data_dir: &Path,
    conn: &Connection,
    active_title: Option<&str>,
) -> Result<SitePublishSettings, String> {
    let connection = load_connection(app_data_dir);
    let settings = domain::get_settings(conn)?;
    let (slug, title) = load_event_meta(conn)?;
    Ok(SitePublishSettings {
        api_base_url: connection.api_base_url,
        publish_token: connection.publish_token,
        slug,
        title,
        suggested_slug: suggested_slug(&settings.competition_date),
        suggested_title: suggested_title(&settings.competition_date, active_title),
    })
}

pub fn test_connection(api_base_url: &str) -> Result<String, String> {
    let base = normalize_base_url(api_base_url)?;
    let url = format!("{base}/api/health");
    let response = agent()
        .get(&url)
        .call()
        .map_err(|e| map_http_error("проверки связи", e, 0))?;
    let status = response.status();
    if !(200..300).contains(&status) {
        return Err(format!("Сайт ответил кодом {status} на {url}"));
    }
    Ok(base)
}

pub fn publish_current_start(
    app_data_dir: &Path,
    db_path: &Path,
    course_map_path: &Path,
    api_base_url: &str,
    publish_token: &str,
    slug: &str,
    title: &str,
) -> Result<SitePublishResult, String> {
    let connection = SiteConnection {
        api_base_url: api_base_url.to_string(),
        publish_token: publish_token.to_string(),
    };
    save_connection(app_data_dir, &connection)?;

    let mut conn = domain::open_and_init_db(db_path)?;
    save_event_meta(&conn, slug, title)?;
    let slug = normalize_slug(slug)?;
    let title = title.trim().to_string();
    let payload = build_payload(&mut conn, &title)?;
    let map = load_map_upload(&conn, course_map_path)?;
    let participant_count = payload.participants.len();
    let result_count = payload.results.len();

    let base = normalize_base_url(&connection.api_base_url)?;
    let token = connection.publish_token.trim();
    if token.is_empty() {
        return Err("Укажите токен публикации.".into());
    }
    let url = format!("{base}/api/events/{slug}");
    let body = serde_json::to_vec(&payload)
        .map_err(|e| format!("Не удалось собрать JSON публикации: {e}"))?;
    let response = agent()
        .put(&url)
        .set("Authorization", &format!("Bearer {token}"))
        .set("Content-Type", "application/json")
        .send_bytes(&body)
        .map_err(|e| map_http_error("публикации результатов", e, body.len()))?;
    expect_ok(response, "публикации результатов")?;

    let mut has_map = false;
    if let Some(map) = map {
        let map_url = format!("{base}/api/events/{slug}/map");
        let mut request = agent()
            .put(&map_url)
            .set("Authorization", &format!("Bearer {token}"))
            .set("Content-Type", &map.mime_type)
            .set("X-Map-File-Name", &map.file_name);
        if let Some(width) = map.width {
            request = request.set("X-Map-Width", &width.to_string());
        }
        if let Some(height) = map.height {
            request = request.set("X-Map-Height", &height.to_string());
        }
        let map_len = map.bytes.len();
        let response = request
            .send_bytes(&map.bytes)
            .map_err(|e| {
                format!(
                    "Результаты опубликованы, но карту отправить не удалось: {}. Повторите публикацию.",
                    map_http_error("публикации карты", e, map_len)
                )
            })?;
        expect_ok(response, "публикации карты").map_err(|e| {
            format!(
                "Результаты опубликованы, но карту отправить не удалось: {e} Повторите публикацию."
            )
        })?;
        has_map = true;
    }

    Ok(SitePublishResult {
        slug,
        participant_count,
        result_count,
        has_map,
    })
}

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout(Duration::from_secs(120))
        .build()
}

fn normalize_base_url(raw: &str) -> Result<String, String> {
    let url = raw.trim().trim_end_matches('/').to_string();
    if url.is_empty() {
        return Err("Укажите адрес сайта (например http://127.0.0.1:18790).".into());
    }
    if !(url.starts_with("http://") || url.starts_with("https://")) {
        return Err("Адрес сайта должен начинаться с http:// или https://".into());
    }
    Ok(url)
}

fn normalize_slug(raw: &str) -> Result<String, String> {
    let slug = raw.trim().to_lowercase();
    if slug.is_empty() {
        return Err("Укажите slug старта (латиница, цифры, дефис).".into());
    }
    if !slug
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
    {
        return Err("Slug может содержать только латиницу, цифры, дефис и подчёркивание.".into());
    }
    Ok(slug)
}

fn expect_ok(response: ureq::Response, action: &str) -> Result<(), String> {
    let status = response.status();
    if (200..300).contains(&status) {
        return Ok(());
    }
    let body = response.into_string().unwrap_or_default();
    let body = body.trim();
    if body.is_empty() {
        Err(format!("Ошибка {action}: сервер ответил {status}"))
    } else {
        Err(format!("Ошибка {action}: сервер ответил {status}: {body}"))
    }
}

fn map_http_error(action: &str, err: ureq::Error, payload_bytes: usize) -> String {
    match err {
        ureq::Error::Status(code, response) => {
            let body = response.into_string().unwrap_or_default();
            let body = body.trim();
            if body.is_empty() {
                format!("Ошибка {action}: сервер ответил {code}")
            } else {
                format!("Ошибка {action}: сервер ответил {code}: {body}")
            }
        }
        other => {
            let size = if payload_bytes > 0 {
                format!(
                    " (тело запроса {:.1} МБ)",
                    payload_bytes as f64 / (1024.0 * 1024.0)
                )
            } else {
                String::new()
            };
            let hint = if other.to_string().contains("32")
                || other.to_string().to_lowercase().contains("обрыв")
            {
                " Обычно так бывает, если API отклонил слишком большое тело (карта) или процесс API перезапустился."
            } else {
                ""
            };
            format!("Ошибка {action}{size}: {other}.{hint}")
        }
    }
}

fn build_payload(
    conn: &mut Connection,
    title: &str,
) -> Result<PublishPayload, String> {
    let groups = domain::query_award_groups(conn)?;
    if groups.is_empty() {
        return Err("Сначала создайте хотя бы одну группу награждения.".into());
    }

    let settings = domain::get_settings(conn)?;
    let competition_date = NaiveDate::parse_from_str(settings.competition_date.trim(), "%Y-%m-%d").ok();
    let special = domain::get_course_map_special_points(conn)?;
    let legend_positions = legend_positions(conn)?;
    let map_points = collect_map_points(conn, &special, &legend_positions)?;
    let map_metric = map_metric(conn);

    let mut award_groups = Vec::new();
    let mut results = Vec::new();
    let mut participants: HashMap<i64, PublishParticipant> = HashMap::new();
    let mut marks_cache: HashMap<i64, Vec<PublishMark>> = HashMap::new();

    for group in &groups {
        award_groups.push(PublishAwardGroup {
            source_id: group.id,
            name: group.name.clone(),
            gender_mode: group.gender_mode.clone(),
            min_age: group.min_age.map(|v| v as i32),
            sort_order: group.sort_order as i32,
        });
        let rows = load_group_rows(conn, group)?;
        let places = assign_places(&rows);
        for (row, place) in rows.into_iter().zip(places) {
            let source_id = upsert_participant(
                conn,
                &mut participants,
                &mut marks_cache,
                &row,
                &special,
                &legend_positions,
                map_metric.as_ref(),
            )?;
            results.push(PublishResultRow {
                participant_source_id: source_id,
                award_group_source_id: group.id,
                place,
                points_raw: row.points_raw as i32,
                penalty_points: row.penalty_points as i32,
                points_final: row.points_final as i32,
                elapsed_seconds: row.elapsed_seconds as i32,
                status: row.status,
            });
        }
    }

    if participants.is_empty() {
        return Err("В группах награждения нет участников для публикации.".into());
    }

    let mut participants: Vec<PublishParticipant> = participants.into_values().collect();
    participants.sort_by(|a, b| a.bib.cmp(&b.bib).then(a.name.cmp(&b.name)));

    Ok(PublishPayload {
        title: title.to_string(),
        competition_date,
        award_groups,
        participants,
        results,
        map_points,
        meters_per_pixel: map_metric.as_ref().map(|m| m.meters_per_pixel),
        sport_kind: settings.sport_kind,
    })
}

fn load_group_rows(conn: &Connection, group: &AwardGroupRow) -> Result<Vec<ResultRow>, String> {
    let mut all = Vec::new();
    let mut offset = 0_i64;
    loop {
        let (rows, total) = domain::query_results(
            conn,
            500,
            offset,
            None,
            None,
            None,
            false,
            Some(group.id),
            None,
            Some("points_final".into()),
            Some("desc".into()),
        )?;
        let n = rows.len() as i64;
        all.extend(rows);
        offset += n;
        if n == 0 || offset >= total {
            break;
        }
    }
    Ok(all)
}

fn assign_places(rows: &[ResultRow]) -> Vec<Option<i32>> {
    let mut out = Vec::with_capacity(rows.len());
    let mut place = 0_i32;
    let mut last_unit: Option<String> = None;
    for row in rows {
        if row.status != "OK" {
            out.push(None);
            continue;
        }
        let unit = if let Some(team_id) = row.team_id.filter(|id| *id > 0) {
            format!("t{team_id}")
        } else {
            format!("p{}", public_source_id(row.finish_participant_id, None, row.id))
        };
        if last_unit.as_deref() != Some(unit.as_str()) {
            place += 1;
            last_unit = Some(unit);
        }
        out.push(Some(place));
    }
    out
}

fn public_source_id(
    finish_participant_id: Option<i64>,
    protocol_id: Option<i64>,
    result_id: i64,
) -> i64 {
    if let Some(id) = finish_participant_id {
        return id;
    }
    if let Some(id) = protocol_id {
        return PROTOCOL_SOURCE_OFFSET + id;
    }
    RESULT_SOURCE_OFFSET + result_id
}

fn upsert_participant(
    conn: &mut Connection,
    participants: &mut HashMap<i64, PublishParticipant>,
    marks_cache: &mut HashMap<i64, Vec<PublishMark>>,
    row: &ResultRow,
    special: &domain::CourseMapSpecialPoints,
    legend_positions: &HashMap<i64, (f64, f64, String)>,
    map_metric: Option<&MapMetric>,
) -> Result<i64, String> {
    let (protocol_id, birth_date) = protocol_extras(conn, &row.participant_id, &row.name)?;
    let source_id = public_source_id(row.finish_participant_id, protocol_id, row.id);
    if participants.contains_key(&source_id) {
        return Ok(source_id);
    }

    let marks = if let Some(finish_id) = row.finish_participant_id {
        if let Some(cached) = marks_cache.get(&finish_id) {
            cached.clone()
        } else {
            let loaded = load_corrected_marks(conn, row.id)?;
            marks_cache.insert(finish_id, loaded.clone());
            loaded
        }
    } else {
        Vec::new()
    };
    let path = build_path(&marks, special, legend_positions);
    let distance_m = domain::participant_path_distance_m(conn, row.id)
        .ok()
        .and_then(|info| info.distance_m)
        .filter(|v| v.is_finite() && *v > 0.0)
        .or_else(|| path_distance_m(&path, map_metric));
    let chip_physical = row
        .chip_raw_id
        .as_deref()
        .map(str::trim)
        .filter(|v| !v.is_empty())
        .map(ToString::to_string);
    let bib = row.participant_id.trim().to_string();
    participants.insert(
        source_id,
        PublishParticipant {
            source_id,
            bib: bib.clone(),
            chip_physical,
            chip_logical: Some(bib),
            name: row.name.clone(),
            gender: row.gender.clone(),
            birth_date,
            age: row.age.map(|v| v as i32),
            team_id: row.team_id,
            format_name: Some(row.format_name.clone()).filter(|v| !v.trim().is_empty()),
            marks,
            path,
            distance_m,
        },
    );
    Ok(source_id)
}

fn protocol_extras(
    conn: &Connection,
    bib: &str,
    name: &str,
) -> Result<(Option<i64>, Option<NaiveDate>), String> {
    conn.query_row(
        r#"
        SELECT id, birth_date_iso
        FROM start_protocol
        WHERE participant_id = ? AND name = ?
        LIMIT 1
        "#,
        params![bib, name],
        |r| {
            let id: i64 = r.get(0)?;
            let iso: Option<String> = r.get(1)?;
            Ok((id, iso))
        },
    )
    .optional()
    .map_err(|e| format!("load start protocol extras: {e}"))
    .map(|row| match row {
        Some((id, iso)) => {
            let date = iso
                .and_then(|v| NaiveDate::parse_from_str(v.trim(), "%Y-%m-%d").ok());
            (Some(id), date)
        }
        None => (None, None),
    })
}

fn load_corrected_marks(conn: &mut Connection, result_id: i64) -> Result<Vec<PublishMark>, String> {
    let details = domain::query_participant_details(conn, result_id)?;
    Ok(details
        .corrected_marks
        .into_iter()
        .map(|m| PublishMark {
            seq: m.seq as i32,
            cp_number: m.cp_number as i32,
            mark_time: m.mark_time.replace(' ', "T"),
        })
        .collect())
}

struct MapMetric {
    width: f64,
    height: f64,
    meters_per_pixel: f64,
}

fn map_metric(conn: &Connection) -> Option<MapMetric> {
    let info = domain::get_map_georef_info(conn).ok()?;
    let meters_per_pixel = info
        .transform
        .as_ref()
        .map(|t| t.meters_per_pixel)
        .filter(|v| *v > 1e-12)
        .or_else(|| {
            let denom = info.scale_denominator? as f64;
            if denom <= 0.0 {
                return None;
            }
            Some(denom / ((300.0 / 2.54) * 100.0))
        })?;
    Some(MapMetric {
        width: info.image_width?,
        height: info.image_height?,
        meters_per_pixel,
    })
}

fn path_distance_m(path: &[PublishPathPoint], metric: Option<&MapMetric>) -> Option<f64> {
    let metric = metric?;
    if path.len() < 2 || metric.width <= 0.0 || metric.height <= 0.0 || metric.meters_per_pixel <= 0.0 {
        return None;
    }
    let mut distance = 0.0;
    for pair in path.windows(2) {
        let dx = (pair[1].x - pair[0].x) * metric.width;
        let dy = (pair[1].y - pair[0].y) * metric.height;
        distance += (dx * dx + dy * dy).sqrt() * metric.meters_per_pixel;
    }
    (distance.is_finite() && distance > 0.0).then_some(distance)
}

fn legend_positions(conn: &Connection) -> Result<HashMap<i64, (f64, f64, String)>, String> {
    let mut out = HashMap::new();
    for row in domain::query_cp_legends(conn)? {
        if let (Some(x), Some(y)) = (row.map_x, row.map_y) {
            out.insert(row.cp_number, (x, y, row.name));
        }
    }
    Ok(out)
}

fn collect_map_points(
    _conn: &Connection,
    special: &domain::CourseMapSpecialPoints,
    legend_positions: &HashMap<i64, (f64, f64, String)>,
) -> Result<Vec<PublishMapPoint>, String> {
    let mut points = Vec::new();
    if let (Some(x), Some(y)) = (special.start_map_x, special.start_map_y) {
        points.push(PublishMapPoint {
            kind: "start".into(),
            cp_number: special.start_cp.map(|v| v as i32),
            name: "Старт".into(),
            map_x: x,
            map_y: y,
        });
    }
    if let (Some(x), Some(y)) = (special.finish_map_x, special.finish_map_y) {
        points.push(PublishMapPoint {
            kind: "finish".into(),
            cp_number: Some(special.finish_cp as i32),
            name: "Финиш".into(),
            map_x: x,
            map_y: y,
        });
    }
    for (cp, (x, y, name)) in legend_positions {
        points.push(PublishMapPoint {
            kind: "cp".into(),
            cp_number: Some(*cp as i32),
            name: name.clone(),
            map_x: *x,
            map_y: *y,
        });
    }
    points.sort_by(|a, b| {
        a.cp_number
            .unwrap_or(i32::MAX)
            .cmp(&b.cp_number.unwrap_or(i32::MAX))
            .then(a.kind.cmp(&b.kind))
    });
    Ok(points)
}

fn resolve_cp_position(
    cp_number: i64,
    special: &domain::CourseMapSpecialPoints,
    legend_positions: &HashMap<i64, (f64, f64, String)>,
) -> Option<(f64, f64, &'static str)> {
    if special.start_cp == Some(cp_number) {
        if let (Some(x), Some(y)) = (special.start_map_x, special.start_map_y) {
            return Some((x, y, "start"));
        }
    }
    if cp_number == special.finish_cp {
        if let (Some(x), Some(y)) = (special.finish_map_x, special.finish_map_y) {
            return Some((x, y, "finish"));
        }
    }
    let (x, y, _) = legend_positions.get(&cp_number)?;
    Some((*x, *y, "cp"))
}

fn build_path(
    marks: &[PublishMark],
    special: &domain::CourseMapSpecialPoints,
    legend_positions: &HashMap<i64, (f64, f64, String)>,
) -> Vec<PublishPathPoint> {
    let mut points = Vec::new();
    if let (Some(x), Some(y)) = (special.start_map_x, special.start_map_y) {
        points.push(PublishPathPoint {
            x,
            y,
            cp_number: special.start_cp.map(|v| v as i32),
            kind: "start".into(),
        });
    }
    for mark in marks {
        let Some((x, y, kind)) =
            resolve_cp_position(mark.cp_number as i64, special, legend_positions)
        else {
            continue;
        };
        if let Some(last) = points.last() {
            if (last.x - x).abs() < 1e-9 && (last.y - y).abs() < 1e-9 {
                continue;
            }
        }
        points.push(PublishPathPoint {
            x,
            y,
            cp_number: Some(mark.cp_number),
            kind: kind.into(),
        });
    }
    points
}

fn load_map_upload(
    conn: &Connection,
    course_map_path: &Path,
) -> Result<Option<MapUpload>, String> {
    if !course_map_path.is_file() {
        return Ok(None);
    }
    let file_name = domain::get_setting_value(conn, "course_map_file_name")?
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| "map.bin".into());
    let mime_type = domain::get_setting_value(conn, "course_map_mime")?
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| "application/octet-stream".into());
    let bytes = fs::read(course_map_path).map_err(|e| format!("read course map: {e}"))?;
    if bytes.is_empty() {
        return Ok(None);
    }
    let georef = domain::get_map_georef_info(conn)?;
    Ok(Some(MapUpload {
        file_name,
        mime_type,
        bytes,
        width: georef.image_width.map(|v| v.round() as i32),
        height: georef.image_height.map(|v| v.round() as i32),
    }))
}
