use chrono::{NaiveDate, DateTime, Utc};
use serde::{Deserialize, Serialize, Serializer};
use sqlx::FromRow;
use sqlx::types::JsonValue;

/// Browser JSON.parse() cannot keep integers above 2^53. Send ids as strings.
fn serialize_source_id<S: Serializer>(value: &i64, serializer: S) -> Result<S::Ok, S::Error> {
    serializer.serialize_str(&value.to_string())
}

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
    pub max_age: Option<i32>,
    pub sort_order: i32,
    pub course_cps: JsonValue,
    pub length_meters: Option<i32>,
    pub climb_meters: Option<i32>,
    pub leg_meters: JsonValue,
}

#[derive(Debug, Clone, FromRow, Serialize)]
pub struct ResultPublic {
    pub id: i64,
    pub award_group_id: i64,
    pub participant_id: i64,
    #[serde(serialize_with = "serialize_source_id")]
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
    pub marks: JsonValue,
}

#[derive(Debug, Clone, FromRow, Serialize)]
pub struct ParticipantPublic {
    pub id: i64,
    #[serde(serialize_with = "serialize_source_id")]
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
    pub added_cps: JsonValue,
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
    pub revision: i64,
    pub award_groups: Vec<AwardGroupPublic>,
    pub results: Vec<ResultPublic>,
}

pub fn sort_results_keeping_teams(rows: &mut [ResultPublic]) {
    use std::collections::HashMap;
    let mut team_best: HashMap<(i64, i64), i32> = HashMap::new();
    let mut team_status: HashMap<(i64, i64), i32> = HashMap::new();
    for row in rows.iter() {
        let Some(team_id) = row.team_id.filter(|id| *id > 0) else {
            continue;
        };
        let key = (row.award_group_id, team_id);
        let place = row.place.unwrap_or(i32::MAX);
        let entry = team_best.entry(key).or_insert(place);
        if place < *entry {
            *entry = place;
        }
        let rank = protocol_status_rank(&row.status);
        let status_entry = team_status.entry(key).or_insert(rank);
        if rank < *status_entry {
            *status_entry = rank;
        }
    }
    rows.sort_by(|a, b| {
        a.award_group_id
            .cmp(&b.award_group_id)
            .then_with(|| team_status_rank(a, &team_status).cmp(&team_status_rank(b, &team_status)))
            .then_with(|| team_rank_place(a, &team_best).cmp(&team_rank_place(b, &team_best)))
            .then_with(|| team_id_sort(a.team_id).cmp(&team_id_sort(b.team_id)))
            .then_with(|| a.place.unwrap_or(i32::MAX).cmp(&b.place.unwrap_or(i32::MAX)))
            .then_with(|| b.points_final.cmp(&a.points_final))
            .then_with(|| a.elapsed_seconds.cmp(&b.elapsed_seconds))
            .then_with(|| bib_sort_key(&a.bib).cmp(&bib_sort_key(&b.bib)))
    });
    compact_team_places(rows);
}

/// Команда — одна зачётная единица, поэтому после пары не должно быть дырки 1, 2, 4.
fn compact_team_places(rows: &mut [ResultPublic]) {
    use std::collections::{BTreeMap, HashMap};
    let mut start = 0;
    while start < rows.len() {
        let group_id = rows[start].award_group_id;
        let mut end = start + 1;
        while end < rows.len() && rows[end].award_group_id == group_id {
            end += 1;
        }
        let group = &mut rows[start..end];
        let mut units: HashMap<String, (Vec<usize>, i32)> = HashMap::new();
        let mut order: Vec<String> = Vec::new();
        for (idx, row) in group.iter().enumerate() {
            if !result_ok(&row.status) {
                continue;
            }
            let key = match row.team_id.filter(|id| *id > 0) {
                Some(team_id) => format!("t{team_id}"),
                None => format!("p{}", row.participant_id),
            };
            let rank = row.place.unwrap_or(i32::MAX);
            if let Some((indices, best)) = units.get_mut(&key) {
                indices.push(idx);
                if rank < *best {
                    *best = rank;
                }
            } else {
                units.insert(key.clone(), (vec![idx], rank));
                order.push(key);
            }
        }
        let mut by_rank: BTreeMap<i32, i32> = BTreeMap::new();
        for key in &order {
            if let Some((_, rank)) = units.get(key) {
                *by_rank.entry(*rank).or_insert(0) += 1;
            }
        }
        let mut place = 1;
        let mut rank_to_place: HashMap<i32, i32> = HashMap::new();
        for (rank, count) in by_rank {
            rank_to_place.insert(rank, place);
            place += count;
        }
        for row in group.iter_mut() {
            if !result_ok(&row.status) {
                row.place = None;
            }
        }
        for key in &order {
            let Some((indices, rank)) = units.get(key) else {
                continue;
            };
            let next_place = rank_to_place.get(rank).copied();
            for idx in indices {
                group[*idx].place = next_place;
            }
        }
        start = end;
    }
}

fn result_ok(status: &str) -> bool {
    let status = status.trim();
    status.is_empty() || status.eq_ignore_ascii_case("OK")
}

fn protocol_status_rank(status: &str) -> i32 {
    let status = status.trim();
    if result_ok(status) {
        0
    } else if status == "Дисквалификация" || status.eq_ignore_ascii_case("DSQ") {
        1
    } else if status.eq_ignore_ascii_case("DNF") {
        2
    } else if status == "Ошибка" {
        3
    } else if status == "Не стартовал" || status.eq_ignore_ascii_case("DNS") {
        4
    } else {
        5
    }
}

fn team_status_rank(
    row: &ResultPublic,
    best: &std::collections::HashMap<(i64, i64), i32>,
) -> i32 {
    row.team_id
        .filter(|id| *id > 0)
        .and_then(|team_id| best.get(&(row.award_group_id, team_id)).copied())
        .unwrap_or_else(|| protocol_status_rank(&row.status))
}

fn team_rank_place(row: &ResultPublic, best: &std::collections::HashMap<(i64, i64), i32>) -> i32 {
    row.team_id
        .filter(|id| *id > 0)
        .and_then(|team_id| best.get(&(row.award_group_id, team_id)).copied())
        .unwrap_or_else(|| row.place.unwrap_or(i32::MAX))
}

fn team_id_sort(team_id: Option<i64>) -> (u8, i64) {
    match team_id.filter(|id| *id > 0) {
        Some(id) => (0, id),
        None => (1, 0),
    }
}

fn bib_sort_key(bib: &str) -> (i64, String) {
    let trimmed = bib.trim();
    (
        trimmed.parse::<i64>().unwrap_or(i64::MAX),
        trimmed.to_ascii_lowercase(),
    )
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
    pub revision: i64,
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
    #[serde(default)]
    pub max_age: Option<i32>,
    pub sort_order: Option<i32>,
    #[serde(default)]
    pub course_cps: Vec<i32>,
    #[serde(default)]
    pub length_meters: Option<i32>,
    #[serde(default)]
    pub climb_meters: Option<i32>,
    #[serde(default)]
    pub leg_meters: Vec<Option<i32>>,
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
    #[serde(default)]
    pub added_cps: std::collections::BTreeMap<i32, i32>,
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

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn row(id: i64, bib: &str, status: &str, place: Option<i32>, points: i32) -> ResultPublic {
        ResultPublic {
            id,
            award_group_id: 1,
            participant_id: id,
            participant_source_id: id,
            bib: bib.to_string(),
            name: bib.to_string(),
            gender: None,
            age: None,
            team_id: None,
            format_name: None,
            place,
            points_raw: points,
            penalty_points: 0,
            points_final: points,
            elapsed_seconds: 0,
            status: status.to_string(),
            diagnostics: json!([]),
            marks: json!([]),
        }
    }

    #[test]
    fn dnf_sorts_after_disqualified() {
        let mut rows = vec![
            row(1, "3", "DNF", None, 90),
            row(2, "1", "OK", Some(1), 100),
            row(3, "2", "Дисквалификация", None, 80),
        ];
        sort_results_keeping_teams(&mut rows);
        let statuses: Vec<_> = rows.iter().map(|r| r.status.as_str()).collect();
        assert_eq!(statuses, vec!["OK", "Дисквалификация", "DNF"]);
    }
}
