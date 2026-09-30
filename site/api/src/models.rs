use chrono::{NaiveDate, DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;
use sqlx::types::JsonValue;

#[derive(Debug, Clone, FromRow, Serialize)]
pub struct EventListItem {
    pub slug: String,
    pub title: String,
    pub competition_date: Option<NaiveDate>,
    pub status: String,
    pub published_at: DateTime<Utc>,
    pub sport_kind: String,
    pub participant_count: i64,
}

#[derive(Debug, Serialize)]
pub struct EventListPage {
    pub items: Vec<EventListItem>,
    pub total: i64,
    pub page: i64,
    pub per_page: i64,
}

#[derive(Debug, Clone, FromRow, Serialize)]
pub struct UpcomingListItem {
    pub slug: String,
    pub title: String,
    pub competition_date: Option<NaiveDate>,
    pub sport_kind: String,
    pub status: String,
    pub summary: String,
}

#[derive(Debug, Clone, FromRow, Serialize)]
pub struct AwardGroupPublic {
    pub id: i64,
    pub source_id: i64,
    pub name: String,
    pub gender_mode: String,
    pub min_age: Option<i32>,
    pub sort_order: i32,
    pub course_cps: JsonValue,
}

#[derive(Debug, Clone, FromRow, Serialize)]
pub struct ResultPublic {
    pub id: i64,
    pub award_group_id: i64,
    pub participant_id: i64,
    pub participant_source_id: i64,
    pub bib: String,
    pub name: String,
    pub gender: Option<String>,
    pub age: Option<i32>,
    pub team_id: Option<i64>,
    pub format_name: Option<String>,
    pub place: Option<i32>,
    pub points_raw: i32,
    pub penalty_points: i32,
    pub points_final: i32,
    pub elapsed_seconds: i32,
    pub status: String,
    pub diagnostics: JsonValue,
}

#[derive(Debug, Clone, FromRow, Serialize)]
pub struct ParticipantPublic {
    pub id: i64,
    pub source_id: i64,
    pub bib: String,
    pub chip_physical: Option<String>,
    pub chip_logical: Option<String>,
    pub name: String,
    pub gender: Option<String>,
    pub birth_date: Option<NaiveDate>,
    pub age: Option<i32>,
    pub team_id: Option<i64>,
    pub format_name: Option<String>,
    pub marks: JsonValue,
    pub path: JsonValue,
    pub distance_m: Option<f64>,
    pub points_final: Option<i32>,
    pub elapsed_seconds: Option<i32>,
}

#[derive(Debug, Serialize)]
pub struct EventDetail {
    pub slug: String,
    pub title: String,
    pub competition_date: Option<NaiveDate>,
    pub sport_kind: String,
    pub status: String,
    pub published_at: DateTime<Utc>,
    pub map_url: Option<String>,
    pub map_mime: Option<String>,
    pub map_width: Option<i32>,
    pub map_height: Option<i32>,
    pub meters_per_pixel: Option<f64>,
    pub map_points: JsonValue,
    pub start_cp: Option<i32>,
    pub finish_cp: Option<i32>,
    pub award_groups: Vec<AwardGroupPublic>,
    pub results: Vec<ResultPublic>,
}

#[derive(Debug, FromRow)]
pub struct EventRow {
    pub id: i64,
    pub slug: String,
    pub title: String,
    pub competition_date: Option<NaiveDate>,
    pub sport_kind: String,
    pub status: String,
    pub published_at: DateTime<Utc>,
    pub map_file_name: Option<String>,
    pub map_mime: Option<String>,
    pub map_width: Option<i32>,
    pub map_height: Option<i32>,
    pub map_points: JsonValue,
    pub meters_per_pixel: Option<f64>,
    pub start_cp: Option<i32>,
    pub finish_cp: Option<i32>,
}

#[derive(Debug, Deserialize)]
pub struct PublishMap {
    pub file_name: String,
    pub mime_type: Option<String>,
    pub data_base64: String,
    pub width: Option<i32>,
    pub height: Option<i32>,
}

#[derive(Debug, Deserialize)]
pub struct PublishAwardGroup {
    pub source_id: i64,
    pub name: String,
    pub gender_mode: Option<String>,
    pub min_age: Option<i32>,
    pub sort_order: Option<i32>,
    #[serde(default)]
    pub course_cps: Vec<i32>,
}

#[derive(Debug, Deserialize, Serialize)]
pub struct PublishMark {
    pub seq: i32,
    pub cp_number: i32,
    pub mark_time: String,
}

#[derive(Debug, Deserialize, Serialize, Clone)]
pub struct PublishPathPoint {
    pub x: f64,
    pub y: f64,
    #[serde(default)]
    pub cp_number: Option<i32>,
    #[serde(default)]
    pub kind: Option<String>,
}

#[derive(Debug, Deserialize, Serialize, Clone)]
pub struct PublishMapPoint {
    pub kind: String,
    pub cp_number: Option<i32>,
    pub name: String,
    pub map_x: f64,
    pub map_y: f64,
}

#[derive(Debug, Deserialize)]
pub struct PublishParticipant {
    pub source_id: i64,
    pub bib: String,
    pub chip_physical: Option<String>,
    pub chip_logical: Option<String>,
    pub name: String,
    pub gender: Option<String>,
    pub birth_date: Option<NaiveDate>,
    pub age: Option<i32>,
    pub team_id: Option<i64>,
    pub format_name: Option<String>,
    pub marks: Option<Vec<PublishMark>>,
    pub path: Option<Vec<PublishPathPoint>>,
    #[serde(default)]
    pub distance_m: Option<f64>,
}

#[derive(Debug, Deserialize)]
pub struct PublishResult {
    pub participant_source_id: i64,
    pub award_group_source_id: i64,
    pub place: Option<i32>,
    pub points_raw: Option<i32>,
    pub penalty_points: Option<i32>,
    pub points_final: Option<i32>,
    pub elapsed_seconds: Option<i32>,
    pub status: Option<String>,
    #[serde(default)]
    pub diagnostics: Vec<String>,
}
