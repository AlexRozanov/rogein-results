use std::sync::Arc;

use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::Json;
use chrono::NaiveDate;
use serde::{Deserialize, Serialize};
use sqlx::types::Json as SqlJson;

use crate::{internal, AppState};

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct BulletinCourse {
    pub name: String,
    pub length: String,
    pub controls: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Bulletin {
    pub event_kind: String,
    pub district: String,
    pub venue: String,
    pub discipline: String,
    pub intro: String,
    pub lat: String,
    pub lon: String,
    pub start_from: String,
    pub start_to: String,
    pub finish_until: String,
    pub location: String,
    pub parking: String,
    pub scheme_url: String,
    pub rules: String,
    pub time_limit_minutes: String,
    pub punching: String,
    pub stations: String,
    pub course_intro: String,
    pub courses: Vec<BulletinCourse>,
    pub extra_course_notes: String,
    pub terrain: String,
    pub relief: String,
    pub map_info: String,
    pub bounds: String,
    pub emergency_azimuth: String,
    pub map_format: String,
    pub map_scale: String,
    pub contour: String,
    pub map_notes: String,
    pub map_urls: Vec<String>,
    pub hazards: String,
    pub entry_kind: String,
    pub entry_details: String,
    pub contacts: String,
}

#[derive(Debug, Serialize, sqlx::FromRow)]
pub struct PublicUpcoming {
    pub slug: String,
    pub title: String,
    pub competition_date: Option<NaiveDate>,
    pub sport_kind: String,
    pub status: String,
    pub summary: String,
    pub bulletin: SqlJson<Bulletin>,
}

pub fn default_orient() -> Bulletin {
    Bulletin {
        event_kind: "Открытая тренировка.".into(),
        discipline: "парковый спринт".into(),
        start_from: "12:00".into(),
        start_to: "14:00".into(),
        finish_until: "15:30".into(),
        rules: "Тренировка проходит в соответствии с российскими правилами вида спорта «спортивное ориентирование» в дисциплине «ориентирование бегом».".into(),
        time_limit_minutes: "90".into(),
        punching: "SFR, аренда чипа на старте 50 руб.".into(),
        stations: "Станции работают в контактном режиме.".into(),
        course_intro: "Дистанция заданного направления. Доступно прохождение двух дистанций по цене одной, в случае наличия свободных карт.".into(),
        courses: vec![
            BulletinCourse {
                name: "D1".into(),
                ..BulletinCourse::default()
            },
            BulletinCourse {
                name: "D2".into(),
                ..BulletinCourse::default()
            },
        ],
        emergency_azimuth: "180 градусов (юг)".into(),
        map_format: "Формат карты — A4. Карта напечатана типографским способом на бумаге повышенной плотности, не заламинирована.".into(),
        entry_kind: "динамическая".into(),
        contacts: "https://t.me/malahit_sprint +7(903)158-33-46 Вячеслав".into(),
        ..Bulletin::default()
    }
}

pub fn sanitize(mut bulletin: Bulletin, upcoming_id: i64) -> Bulletin {
    bulletin.scheme_url = sanitize_media_url(&bulletin.scheme_url, upcoming_id);
    bulletin.map_urls = bulletin
        .map_urls
        .into_iter()
        .map(|url| sanitize_media_url(&url, upcoming_id))
        .filter(|url| !url.is_empty())
        .collect();
    bulletin.courses = bulletin
        .courses
        .into_iter()
        .filter(|row| {
            !row.name.trim().is_empty()
                || !row.length.trim().is_empty()
                || !row.controls.trim().is_empty()
        })
        .collect();
    bulletin
}

fn sanitize_media_url(raw: &str, upcoming_id: i64) -> String {
    let url = raw.trim();
    let prefix = format!("/media/bulletins/{upcoming_id}/");
    if url.starts_with(&prefix)
        && !url.contains("..")
        && url.chars().all(|c| c.is_ascii_graphic())
    {
        url.to_string()
    } else {
        String::new()
    }
}

pub async fn get_public_upcoming(
    State(state): State<Arc<AppState>>,
    Path(slug): Path<String>,
) -> Result<Json<PublicUpcoming>, (StatusCode, String)> {
    let row = sqlx::query_as::<_, PublicUpcoming>(
        r#"
        SELECT slug, title, competition_date, sport_kind, status, summary, bulletin
        FROM upcoming_events
        WHERE slug = $1
          AND status = 'announced'
          AND published_event_id IS NULL
        "#,
    )
    .bind(slug.trim())
    .fetch_optional(&state.pool)
    .await
    .map_err(internal)?
    .ok_or((StatusCode::NOT_FOUND, "Старт не найден".into()))?;
    Ok(Json(row))
}
