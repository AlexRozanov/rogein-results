use std::path::{Path as FsPath, PathBuf};
use std::sync::Arc;

use axum::body::Bytes;
use axum::extract::{Multipart, Path, Query, State};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::Response;
use axum::Json;
use chrono::{DateTime, NaiveDate, Utc};
use image::GenericImageView;
use serde::{Deserialize, Serialize};
use sqlx::types::Json as SqlJson;
use sqlx::PgPool;
use uuid::Uuid;

use crate::auth::{check_origin, AuthUser};
use crate::bulletin::{sanitize, Bulletin};
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
    pub bulletin_ready: bool,
    pub bulletin: SqlJson<Bulletin>,
}

#[derive(Debug, Deserialize)]
pub struct UpcomingBody {
    pub slug: String,
    pub title: String,
    pub competition_date: Option<String>,
    pub sport_kind: Option<String>,
    pub summary: Option<String>,
    pub status: Option<String>,
    pub bulletin: Option<Bulletin>,
}

#[derive(Debug, Deserialize)]
pub struct CopyBulletinBody {
    pub source_id: i64,
}

#[derive(Debug, Deserialize)]
pub struct BulletinImageQuery {
    pub slot: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct BulletinImageResponse {
    pub url: String,
    pub bulletin: Bulletin,
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
                Ok("orient".into())
            } else {
                Err((
                    StatusCode::BAD_REQUEST,
                    "Вид спорта: rogaine или orient".into(),
                ))
            }
        }
    }
}

fn default_bulletin_for(sport_kind: &str) -> Bulletin {
    if sport_kind == "orient" {
        crate::bulletin::default_orient()
    } else {
        Bulletin::default()
    }
}

fn bulletin_dir(media_dir: &FsPath, id: i64) -> PathBuf {
    media_dir.join("bulletins").join(id.to_string())
}

fn rewrite_copied_media(
    media_dir: &FsPath,
    source_id: i64,
    target_id: i64,
    bulletin: &mut Bulletin,
) -> Result<(), (StatusCode, String)> {
    let source_prefix = format!("/media/bulletins/{source_id}/");
    let target_dir = bulletin_dir(media_dir, target_id);
    std::fs::create_dir_all(&target_dir).map_err(|_| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            "Не удалось подготовить каталог медиа".into(),
        )
    })?;

    let copy_one = |url: &str| -> Result<String, (StatusCode, String)> {
        let url = url.trim();
        if url.is_empty() {
            return Ok(String::new());
        }
        if !url.starts_with(&source_prefix) || url.contains("..") {
            return Ok(String::new());
        }
        let file_name = url.trim_start_matches(&source_prefix);
        if file_name.is_empty() || file_name.contains('/') {
            return Ok(String::new());
        }
        let src = bulletin_dir(media_dir, source_id).join(file_name);
        if !src.is_file() {
            return Ok(String::new());
        }
        let ext = src
            .extension()
            .and_then(|v| v.to_str())
            .unwrap_or("jpg");
        let stored_name = format!("{}.{}", Uuid::new_v4(), ext);
        let dest = target_dir.join(&stored_name);
        std::fs::copy(&src, &dest).map_err(|_| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Не удалось скопировать файл бюллетеня".into(),
            )
        })?;
        Ok(format!("/media/bulletins/{target_id}/{stored_name}"))
    };

    bulletin.scheme_url = copy_one(&bulletin.scheme_url)?;
    let mut maps = Vec::new();
    for url in bulletin.map_urls.drain(..) {
        let copied = copy_one(&url)?;
        if !copied.is_empty() {
            maps.push(copied);
        }
    }
    bulletin.map_urls = maps;
    Ok(())
}

async fn load_upcoming(
    pool: &PgPool,
    organizer_id: i64,
    id: i64,
) -> Result<AdminUpcoming, (StatusCode, String)> {
    sqlx::query_as::<_, AdminUpcoming>(
        r#"
        SELECT id, slug, title, competition_date, sport_kind, status, summary,
               announced_at, bulletin_ready, bulletin
        FROM upcoming_events
        WHERE id = $1 AND organizer_id = $2
        "#,
    )
    .bind(id)
    .bind(organizer_id)
    .fetch_optional(pool)
    .await
    .map_err(internal)?
    .ok_or((StatusCode::NOT_FOUND, "Старт не найден".into()))
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
        SELECT id, slug, title, competition_date, sport_kind, status, summary,
               announced_at, bulletin_ready, bulletin
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
            organizer_id, announced_at, bulletin_ready, bulletin
        )
        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, false, $9)
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
    .bind(SqlJson(Bulletin::default()))
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

    if let Some(raw_bulletin) = body.bulletin {
        if !current.bulletin_ready {
            return Err((
                StatusCode::CONFLICT,
                "Сначала создайте бюллетень".into(),
            ));
        }
        let bulletin = sanitize(raw_bulletin, id);
        sqlx::query(
            r#"
            UPDATE upcoming_events
            SET slug = $2,
                title = $3,
                competition_date = $4,
                sport_kind = $5,
                status = $6,
                summary = $7,
                announced_at = $8,
                bulletin = $9
            WHERE id = $1 AND organizer_id = $10
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
        .bind(SqlJson(bulletin))
        .bind(organizer_id)
        .execute(&state.pool)
        .await
        .map_err(|err| conflict_or_internal(err, "Такой slug уже есть"))?;
    } else {
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
    }

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

pub async fn delete_upcoming(
    State(state): State<Arc<AppState>>,
    user: AuthUser,
    Path(id): Path<i64>,
    headers: HeaderMap,
) -> Result<StatusCode, (StatusCode, String)> {
    check_origin(&headers, &state.allowed_origins)?;
    let organizer_id = require_organizer_perm(&state.pool, user.id, "events.announce").await?;
    let current = load_upcoming(&state.pool, organizer_id, id).await?;
    if current.status == "completed" {
        return Err((
            StatusCode::CONFLICT,
            "Проведённый старт удалять нельзя".into(),
        ));
    }
    let deleted = sqlx::query(
        r#"
        DELETE FROM upcoming_events
        WHERE id = $1 AND organizer_id = $2 AND status <> 'completed'
        "#,
    )
    .bind(id)
    .bind(organizer_id)
    .execute(&state.pool)
    .await
    .map_err(internal)?
    .rows_affected();
    if deleted == 0 {
        return Err((StatusCode::NOT_FOUND, "Старт не найден".into()));
    }
    let _ = std::fs::remove_dir_all(bulletin_dir(&state.media_dir, id));
    audit(
        &state.pool,
        user.id,
        "upcoming.delete",
        id,
        serde_json::json!({ "slug": current.slug, "title": current.title }),
    )
    .await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn init_bulletin(
    State(state): State<Arc<AppState>>,
    user: AuthUser,
    Path(id): Path<i64>,
    headers: HeaderMap,
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
    if current.bulletin_ready {
        return Err((
            StatusCode::CONFLICT,
            "Бюллетень уже создан".into(),
        ));
    }
    let bulletin = sanitize(default_bulletin_for(&current.sport_kind), id);
    sqlx::query(
        r#"
        UPDATE upcoming_events
        SET bulletin = $2, bulletin_ready = true
        WHERE id = $1 AND organizer_id = $3
        "#,
    )
    .bind(id)
    .bind(SqlJson(bulletin))
    .bind(organizer_id)
    .execute(&state.pool)
    .await
    .map_err(internal)?;
    audit(
        &state.pool,
        user.id,
        "upcoming.bulletin_init",
        id,
        serde_json::json!({ "slug": current.slug }),
    )
    .await?;
    Ok(Json(load_upcoming(&state.pool, organizer_id, id).await?))
}

pub async fn copy_bulletin(
    State(state): State<Arc<AppState>>,
    user: AuthUser,
    Path(id): Path<i64>,
    headers: HeaderMap,
    Json(body): Json<CopyBulletinBody>,
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
    if body.source_id == id {
        return Err((
            StatusCode::BAD_REQUEST,
            "Нельзя копировать бюллетень из самого себя".into(),
        ));
    }
    let source = load_upcoming(&state.pool, organizer_id, body.source_id).await?;
    if !source.bulletin_ready {
        return Err((
            StatusCode::BAD_REQUEST,
            "У выбранного старта нет бюллетеня".into(),
        ));
    }
    let mut bulletin = source.bulletin.0.clone();
    rewrite_copied_media(&state.media_dir, source.id, id, &mut bulletin)?;
    let bulletin = sanitize(bulletin, id);
    sqlx::query(
        r#"
        UPDATE upcoming_events
        SET bulletin = $2, bulletin_ready = true
        WHERE id = $1 AND organizer_id = $3
        "#,
    )
    .bind(id)
    .bind(SqlJson(bulletin))
    .bind(organizer_id)
    .execute(&state.pool)
    .await
    .map_err(internal)?;
    audit(
        &state.pool,
        user.id,
        "upcoming.bulletin_copy",
        id,
        serde_json::json!({
            "slug": current.slug,
            "source_id": source.id,
            "source_slug": source.slug,
        }),
    )
    .await?;
    Ok(Json(load_upcoming(&state.pool, organizer_id, id).await?))
}

pub async fn upload_bulletin_image(
    State(state): State<Arc<AppState>>,
    user: AuthUser,
    Path(id): Path<i64>,
    Query(query): Query<BulletinImageQuery>,
    headers: HeaderMap,
    mut multipart: Multipart,
) -> Result<Json<BulletinImageResponse>, (StatusCode, String)> {
    check_origin(&headers, &state.allowed_origins)?;
    let organizer_id = require_organizer_perm(&state.pool, user.id, "events.announce").await?;
    let current = load_upcoming(&state.pool, organizer_id, id).await?;
    if current.status == "completed" {
        return Err((
            StatusCode::CONFLICT,
            "Опубликованный старт в календаре не правят".into(),
        ));
    }
    if !current.bulletin_ready {
        return Err((
            StatusCode::CONFLICT,
            "Сначала создайте бюллетень".into(),
        ));
    }
    let slot = query.slot.as_deref().unwrap_or("scheme");
    if slot != "scheme" && slot != "map" {
        return Err((StatusCode::BAD_REQUEST, "slot: scheme или map".into()));
    }

    let mut file_name = String::from("image.jpg");
    let mut data: Option<Bytes> = None;
    while let Some(field) = multipart.next_field().await.map_err(|err| {
        (
            StatusCode::BAD_REQUEST,
            format!("Не удалось прочитать файл: {err}"),
        )
    })? {
        if field.name() != Some("file") {
            continue;
        }
        if let Some(name) = field.file_name() {
            file_name = name.to_string();
        }
        data = Some(field.bytes().await.map_err(|err| {
            (
                StatusCode::BAD_REQUEST,
                format!("Не удалось прочитать файл: {err}"),
            )
        })?);
        break;
    }
    let data = data.ok_or((StatusCode::BAD_REQUEST, "Нужен файл file".into()))?;
    if data.len() > 8 * 1024 * 1024 {
        return Err((StatusCode::BAD_REQUEST, "Файл больше 8 МБ".into()));
    }
    let img = image::load_from_memory(&data).map_err(|_| {
        (
            StatusCode::BAD_REQUEST,
            "Нужно изображение JPEG, PNG или WebP".into(),
        )
    })?;
    let (width, height) = img.dimensions();
    if width == 0 || height == 0 {
        return Err((StatusCode::BAD_REQUEST, "Пустое изображение".into()));
    }
    let img = if width.max(height) > 2400 {
        img.resize(2400, 2400, image::imageops::FilterType::Lanczos3)
    } else {
        img
    };
    let lower = file_name.to_ascii_lowercase();
    let jpeg = lower.ends_with(".jpg") || lower.ends_with(".jpeg") || data.starts_with(&[0xFF, 0xD8]);
    let stored_name = format!("{}.{}", Uuid::new_v4(), if jpeg { "jpg" } else { "png" });
    let dir = bulletin_dir(&state.media_dir, id);
    std::fs::create_dir_all(&dir).map_err(|_| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            "Не удалось сохранить файл".into(),
        )
    })?;
    let path = dir.join(&stored_name);
    img.save_with_format(
        &path,
        if jpeg {
            image::ImageFormat::Jpeg
        } else {
            image::ImageFormat::Png
        },
    )
    .map_err(|_| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            "Не удалось сохранить файл".into(),
        )
    })?;
    let url = format!("/media/bulletins/{id}/{stored_name}");
    let mut bulletin = current.bulletin.0;
    if slot == "scheme" {
        bulletin.scheme_url = url.clone();
    } else {
        bulletin.map_urls.push(url.clone());
    }
    sqlx::query("UPDATE upcoming_events SET bulletin = $2 WHERE id = $1 AND organizer_id = $3")
        .bind(id)
        .bind(SqlJson(bulletin.clone()))
        .bind(organizer_id)
        .execute(&state.pool)
        .await
        .map_err(internal)?;
    audit(
        &state.pool,
        user.id,
        "upcoming.bulletin_image",
        id,
        serde_json::json!({ "slot": slot, "url": url }),
    )
    .await?;
    Ok(Json(BulletinImageResponse { url, bulletin }))
}

fn conflict_or_internal(err: sqlx::Error, conflict: &str) -> (StatusCode, String) {
    if let sqlx::Error::Database(db) = &err {
        if db.code().as_deref() == Some("23505") {
            return (StatusCode::CONFLICT, conflict.into());
        }
    }
    internal(err)
}

/// Метаданные APK для личного кабинета (любой вошедший пользователь).
pub async fn android_app_version(
    State(state): State<Arc<AppState>>,
    _user: AuthUser,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let path = state.android_app_dir.join("version.json");
    let raw = tokio::fs::read_to_string(&path).await.map_err(|_| {
        (
            StatusCode::NOT_FOUND,
            "version.json не найден — выложите APK (publish-android-apk.sh)".into(),
        )
    })?;
    let mut value: serde_json::Value = serde_json::from_str(&raw).map_err(|_| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            "битый version.json".into(),
        )
    })?;
    let apk_ok = state.android_app_dir.join("rogein-chip.apk").is_file();
    if let Some(obj) = value.as_object_mut() {
        obj.insert("apkAvailable".into(), serde_json::json!(apk_ok));
        obj.insert("url".into(), serde_json::json!("/api/admin/app/download"));
    }
    Ok(Json(value))
}

/// Скачивание APK только из сессии админки.
pub async fn android_app_download(
    State(state): State<Arc<AppState>>,
    _user: AuthUser,
) -> Result<Response, (StatusCode, String)> {
    let path = state.android_app_dir.join("rogein-chip.apk");
    let raw = tokio::fs::read(&path).await.map_err(|_| {
        (
            StatusCode::NOT_FOUND,
            "APK не найден — выложите файл через publish-android-apk.sh и деплой".into(),
        )
    })?;
    let mut res = Response::new(axum::body::Body::from(raw));
    res.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/vnd.android.package-archive"),
    );
    res.headers_mut().insert(
        header::CONTENT_DISPOSITION,
        HeaderValue::from_static("attachment; filename=\"rogein-chip.apk\""),
    );
    res.headers_mut().insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("no-store"),
    );
    Ok(res)
}
