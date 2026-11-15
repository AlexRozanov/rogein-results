use std::sync::Arc;

use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::Json;
use chrono::{DateTime, NaiveDate, Utc};
use serde::{Deserialize, Serialize};
use sqlx::PgPool;

use crate::auth::{check_origin, AuthUser};
use crate::rbac::require_organizer_perm;
use crate::{internal, normalize_slug, sport_kind_filter, AppState};

#[derive(Debug, Clone, sqlx::FromRow, Serialize)]
pub struct AdminUpcoming {
    pub id: i64,
    pub slug: String,
    pub title: String,
    pub competition_date: Option<NaiveDate>,
    pub sport_kind: String,
    pub status: String,
    pub summary: String,
    pub announced_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Deserialize)]
pub struct UpcomingBody {
    pub slug: String,
    pub title: String,
    pub competition_date: Option<String>,
    pub sport_kind: Option<String>,
    pub summary: Option<String>,
    pub status: Option<String>,
}

fn parse_status(raw: Option<&str>, default: &str) -> Result<String, (StatusCode, String)> {
    let value = raw.map(str::trim).filter(|s| !s.is_empty()).unwrap_or(default);
    match value {
        "draft" | "announced" | "cancelled" => Ok(value.into()),
        _ => Err((
            StatusCode::BAD_REQUEST,
            "Статус: draft, announced или cancelled".into(),
        )),
    }
}

fn parse_date(raw: Option<&str>) -> Result<Option<NaiveDate>, (StatusCode, String)> {
    crate::parse_date_param(raw, "competition_date")
}

fn parse_sport(raw: Option<&str>) -> Result<String, (StatusCode, String)> {
    match sport_kind_filter(raw) {
        Some(kind) => Ok(kind),
        None => {
            let value = raw.map(str::trim).unwrap_or("");
            if value.is_empty() {
                Ok("rogaine".into())
            } else {
                Err((
                    StatusCode::BAD_REQUEST,
                    "Вид спорта: rogaine или orient".into(),
                ))
            }
        }
    }
}

async fn load_upcoming(
    pool: &PgPool,
    organizer_id: i64,
    id: i64,
) -> Result<AdminUpcoming, (StatusCode, String)> {
    sqlx::query_as::<_, AdminUpcoming>(
        r#"
        SELECT id, slug, title, competition_date, sport_kind, status, summary, announced_at
        FROM upcoming_events
        WHERE id = $1 AND organizer_id = $2
        "#,
    )
    .bind(id)
    .bind(organizer_id)
    .fetch_optional(pool)
    .await
    .map_err(internal)?
    .ok_or((StatusCode::NOT_FOUND, "Анонс не найден".into()))
}

async fn audit(
    pool: &PgPool,
    user_id: i64,
    action: &str,
    entity_id: i64,
    meta: serde_json::Value,
) -> Result<(), (StatusCode, String)> {
    sqlx::query(
        r#"
        INSERT INTO audit_log (actor_user_id, action, entity_type, entity_id, meta)
        VALUES ($1, $2, 'upcoming', $3, $4)
        "#,
    )
    .bind(user_id)
    .bind(action)
    .bind(entity_id.to_string())
    .bind(meta)
    .execute(pool)
    .await
    .map_err(internal)?;
    Ok(())
}

pub async fn list_upcoming(
    State(state): State<Arc<AppState>>,
    user: AuthUser,
) -> Result<Json<Vec<AdminUpcoming>>, (StatusCode, String)> {
    let organizer_id = require_organizer_perm(&state.pool, user.id, "events.announce").await?;
    let rows = sqlx::query_as::<_, AdminUpcoming>(
        r#"
        SELECT id, slug, title, competition_date, sport_kind, status, summary, announced_at
        FROM upcoming_events
        WHERE organizer_id = $1
        ORDER BY
            CASE status
                WHEN 'announced' THEN 0
                WHEN 'draft' THEN 1
                WHEN 'cancelled' THEN 2
                ELSE 3
            END,
            competition_date ASC NULLS LAST,
            id DESC
        "#,
    )
    .bind(organizer_id)
    .fetch_all(&state.pool)
    .await
    .map_err(internal)?;
    Ok(Json(rows))
}

pub async fn get_upcoming(
    State(state): State<Arc<AppState>>,
    user: AuthUser,
    Path(id): Path<i64>,
) -> Result<Json<AdminUpcoming>, (StatusCode, String)> {
    let organizer_id = require_organizer_perm(&state.pool, user.id, "events.announce").await?;
    Ok(Json(load_upcoming(&state.pool, organizer_id, id).await?))
}

pub async fn create_upcoming(
    State(state): State<Arc<AppState>>,
    user: AuthUser,
    headers: HeaderMap,
    Json(body): Json<UpcomingBody>,
) -> Result<(StatusCode, Json<AdminUpcoming>), (StatusCode, String)> {
    check_origin(&headers, &state.allowed_origins)?;
    let organizer_id = require_organizer_perm(&state.pool, user.id, "events.announce").await?;
    let slug = normalize_slug(&body.slug)?;
    let title = body.title.trim();
    if title.is_empty() {
        return Err((StatusCode::BAD_REQUEST, "Нужно название".into()));
    }
    let sport_kind = parse_sport(body.sport_kind.as_deref())?;
    let status = parse_status(body.status.as_deref(), "announced")?;
    let competition_date = parse_date(body.competition_date.as_deref())?;
    let summary = body.summary.unwrap_or_default();
    let announced_at = (status == "announced").then_some(Utc::now());
    let id: i64 = sqlx::query_scalar(
        r#"
        INSERT INTO upcoming_events (
            slug, title, competition_date, sport_kind, status, summary,
            organizer_id, announced_at
        )
        VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
        RETURNING id
        "#,
    )
    .bind(&slug)
    .bind(title)
    .bind(competition_date)
    .bind(&sport_kind)
    .bind(&status)
    .bind(summary.trim())
    .bind(organizer_id)
    .bind(announced_at)
    .fetch_one(&state.pool)
    .await
    .map_err(|err| conflict_or_internal(err, "Такой slug уже есть"))?;
    audit(
        &state.pool,
        user.id,
        "upcoming.create",
        id,
        serde_json::json!({ "slug": slug, "status": status }),
    )
    .await?;
    Ok((
        StatusCode::CREATED,
        Json(load_upcoming(&state.pool, organizer_id, id).await?),
    ))
}

pub async fn update_upcoming(
    State(state): State<Arc<AppState>>,
    user: AuthUser,
    Path(id): Path<i64>,
    headers: HeaderMap,
    Json(body): Json<UpcomingBody>,
) -> Result<Json<AdminUpcoming>, (StatusCode, String)> {
    check_origin(&headers, &state.allowed_origins)?;
    let organizer_id = require_organizer_perm(&state.pool, user.id, "events.announce").await?;
    let current = load_upcoming(&state.pool, organizer_id, id).await?;
    if current.status == "completed" {
        return Err((
            StatusCode::CONFLICT,
            "Опубликованный старт в календаре не правят".into(),
        ));
    }
    let slug = normalize_slug(&body.slug)?;
    let title = body.title.trim();
    if title.is_empty() {
        return Err((StatusCode::BAD_REQUEST, "Нужно название".into()));
    }
    let sport_kind = parse_sport(body.sport_kind.as_deref())?;
    let status = parse_status(body.status.as_deref(), &current.status)?;
    let competition_date = parse_date(body.competition_date.as_deref())?;
    let summary = body.summary.clone().unwrap_or(current.summary.clone());
    let announced_at = if status == "announced" {
        Some(current.announced_at.unwrap_or_else(Utc::now))
    } else {
        current.announced_at
    };
    sqlx::query(
        r#"
        UPDATE upcoming_events
        SET slug = $2,
            title = $3,
            competition_date = $4,
            sport_kind = $5,
            status = $6,
            summary = $7,
            announced_at = $8
        WHERE id = $1 AND organizer_id = $9
        "#,
    )
    .bind(id)
    .bind(&slug)
    .bind(title)
    .bind(competition_date)
    .bind(&sport_kind)
    .bind(&status)
    .bind(summary.trim())
    .bind(announced_at)
    .bind(organizer_id)
    .execute(&state.pool)
    .await
    .map_err(|err| conflict_or_internal(err, "Такой slug уже есть"))?;
    audit(
        &state.pool,
        user.id,
        "upcoming.update",
        id,
        serde_json::json!({ "slug": slug, "status": status }),
    )
    .await?;
    Ok(Json(load_upcoming(&state.pool, organizer_id, id).await?))
}

fn conflict_or_internal(err: sqlx::Error, conflict: &str) -> (StatusCode, String) {
    if let sqlx::Error::Database(db) = &err {
        if db.code().as_deref() == Some("23505") {
            return (StatusCode::CONFLICT, conflict.into());
        }
    }
    internal(err)
}
