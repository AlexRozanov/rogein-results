mod models;
mod publish;

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;

use anyhow::Context;
use axum::body::Bytes;
use axum::extract::{DefaultBodyLimit, Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::{get, put};
use axum::{Json, Router};
use sqlx::postgres::PgPoolOptions;
use sqlx::PgPool;
use tower_http::cors::{Any, CorsLayer};
use tower_http::services::ServeDir;
use tower_http::trace::TraceLayer;

use chrono::NaiveDate;
use serde::Deserialize;
use crate::models::{
    AwardGroupPublic, EventDetail, EventListItem, EventListPage, EventRow, ParticipantPublic,
    ResultPublic, UpcomingListItem,
};
use crate::publish::{publish_event, publish_map, PublishPayload};

#[derive(Clone)]
struct AppState {
    pool: PgPool,
    media_dir: PathBuf,
    publish_token: String,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    dotenvy::dotenv().ok();
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "rogein_site_api=info,tower_http=info".into()),
        )
        .init();

    let database_url = std::env::var("DATABASE_URL")
        .unwrap_or_else(|_| "postgres://rogain:rogain@127.0.0.1:5432/rogain".into());
    let media_dir = PathBuf::from(
        std::env::var("MEDIA_DIR").unwrap_or_else(|_| "../media".into()),
    );
    let publish_token =
        std::env::var("PUBLISH_TOKEN").unwrap_or_else(|_| "dev-token".into());
    let bind = std::env::var("BIND_ADDR").unwrap_or_else(|_| "127.0.0.1:18790".into());

    std::fs::create_dir_all(&media_dir).context("create media dir")?;

    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(&database_url)
        .await
        .context("connect postgres")?;
    sqlx::migrate!("./migrations")
        .run(&pool)
        .await
        .context("run migrations")?;

    let media_service = ServeDir::new(&media_dir);
    let state = Arc::new(AppState {
        pool,
        media_dir,
        publish_token,
    });
    let app = Router::new()
        .route("/api/health", get(health))
        .route("/api/events", get(list_events))
        .route("/api/upcoming", get(list_upcoming))
        .route("/api/events/{slug}", get(get_event).put(put_event))
        .route("/api/events/{slug}/map", put(put_event_map))
        .route(
            "/api/events/{slug}/participants/{source_id}",
            get(get_participant),
        )
        .nest_service("/media", media_service)
        .layer(DefaultBodyLimit::max(80 * 1024 * 1024))
        .layer(TraceLayer::new_for_http())
        .layer(
            CorsLayer::new()
                .allow_origin(Any)
                .allow_methods(Any)
                .allow_headers(Any),
        )
        .with_state(state);

    let addr: SocketAddr = bind.parse().context("parse BIND_ADDR")?;
    let listener = tokio::net::TcpListener::bind(addr).await?;
    tracing::info!("listening on http://{addr}");
    axum::serve(listener, app).await?;
    Ok(())
}

async fn health() -> Json<serde_json::Value> {
    Json(serde_json::json!({ "ok": true }))
}

fn unauthorized() -> (StatusCode, String) {
    (StatusCode::UNAUTHORIZED, "Invalid publish token".into())
}

fn check_token(state: &AppState, headers: &HeaderMap) -> Result<(), (StatusCode, String)> {
    let expected = format!("Bearer {}", state.publish_token);
    let got = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    if got == expected {
        Ok(())
    } else {
        Err(unauthorized())
    }
}

#[derive(Debug, Deserialize)]
struct EventListQuery {
    kind: Option<String>,
    from: Option<String>,
    to: Option<String>,
    page: Option<i64>,
    per_page: Option<i64>,
}

fn sport_kind_filter(raw: Option<&str>) -> Option<String> {
    match raw.map(str::trim) {
        Some("orient") => Some("orient".into()),
        Some("rogaine") => Some("rogaine".into()),
        _ => None,
    }
}

fn parse_date_param(
    raw: Option<&str>,
    name: &str,
) -> Result<Option<NaiveDate>, (StatusCode, String)> {
    let Some(value) = raw.map(str::trim).filter(|s| !s.is_empty()) else {
        return Ok(None);
    };
    NaiveDate::parse_from_str(value, "%Y-%m-%d").map(Some).map_err(|_| {
        (
            StatusCode::BAD_REQUEST,
            format!("Invalid {name}, expected YYYY-MM-DD"),
        )
    })
}

async fn list_events(
    State(state): State<Arc<AppState>>,
    Query(query): Query<EventListQuery>,
) -> Result<Json<EventListPage>, (StatusCode, String)> {
    let kind = sport_kind_filter(query.kind.as_deref());
    let from = parse_date_param(query.from.as_deref(), "from")?;
    let to = parse_date_param(query.to.as_deref(), "to")?;
    let per_page = query.per_page.unwrap_or(20).clamp(1, 50);
    let page = query.page.unwrap_or(1).max(1);
    let offset = (page - 1) * per_page;

    let total: i64 = sqlx::query_scalar(
        r#"
        SELECT COUNT(*)
        FROM events e
        WHERE status = 'published'
          AND ($1::text IS NULL OR sport_kind = $1)
          AND ($2::date IS NULL OR competition_date >= $2)
          AND ($3::date IS NULL OR competition_date <= $3)
        "#,
    )
    .bind(&kind)
    .bind(from)
    .bind(to)
    .fetch_one(&state.pool)
    .await
    .map_err(internal)?;

    let items = sqlx::query_as::<_, EventListItem>(
        r#"
        SELECT
            slug,
            title,
            competition_date,
            status,
            published_at,
            sport_kind,
            (SELECT COUNT(*) FROM participants p WHERE p.event_id = e.id) AS participant_count
        FROM events e
        WHERE status = 'published'
          AND ($1::text IS NULL OR sport_kind = $1)
          AND ($2::date IS NULL OR competition_date >= $2)
          AND ($3::date IS NULL OR competition_date <= $3)
        ORDER BY competition_date DESC NULLS LAST, published_at DESC
        LIMIT $4 OFFSET $5
        "#,
    )
    .bind(&kind)
    .bind(from)
    .bind(to)
    .bind(per_page)
    .bind(offset)
    .fetch_all(&state.pool)
    .await
    .map_err(internal)?;

    Ok(Json(EventListPage {
        items,
        total,
        page,
        per_page,
    }))
}

async fn list_upcoming(
    State(state): State<Arc<AppState>>,
) -> Result<Json<Vec<UpcomingListItem>>, (StatusCode, String)> {
    let rows = sqlx::query_as::<_, UpcomingListItem>(
        r#"
        SELECT slug, title, competition_date, sport_kind, status, summary
        FROM upcoming_events
        WHERE status = 'announced'
          AND published_event_id IS NULL
        ORDER BY competition_date ASC NULLS LAST, created_at ASC
        "#,
    )
    .fetch_all(&state.pool)
    .await
    .map_err(internal)?;
    Ok(Json(rows))
}

async fn get_event(
    State(state): State<Arc<AppState>>,
    Path(slug): Path<String>,
) -> Result<Json<EventDetail>, (StatusCode, String)> {
    let event = load_event_detail(&state.pool, &slug)
        .await
        .map_err(internal)?
        .ok_or((StatusCode::NOT_FOUND, "Event not found".into()))?;
    Ok(Json(event))
}

async fn get_participant(
    State(state): State<Arc<AppState>>,
    Path((slug, source_id)): Path<(String, i64)>,
) -> Result<Json<ParticipantPublic>, (StatusCode, String)> {
    let row = sqlx::query_as::<_, ParticipantPublic>(
        r#"
        SELECT
            p.id,
            p.source_id,
            p.bib,
            p.chip_physical,
            p.chip_logical,
            p.name,
            p.gender,
            p.birth_date,
            p.age,
            p.team_id,
            p.format_name,
            p.marks,
            p.path,
            p.distance_m,
            (
                SELECT r.points_final
                FROM results r
                WHERE r.participant_id = p.id
                ORDER BY r.id
                LIMIT 1
            ) AS points_final,
            (
                SELECT r.elapsed_seconds
                FROM results r
                WHERE r.participant_id = p.id
                ORDER BY r.id
                LIMIT 1
            ) AS elapsed_seconds
        FROM participants p
        JOIN events e ON e.id = p.event_id
        WHERE e.slug = $1 AND p.source_id = $2
        "#,
    )
    .bind(&slug)
    .bind(source_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(internal)?
    .ok_or((StatusCode::NOT_FOUND, "Participant not found".into()))?;
    Ok(Json(row))
}

async fn put_event(
    State(state): State<Arc<AppState>>,
    Path(slug): Path<String>,
    headers: HeaderMap,
    Json(payload): Json<PublishPayload>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    check_token(&state, &headers)?;
    let slug = slug.trim().to_lowercase();
    if slug.is_empty() || !slug.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
    {
        return Err((StatusCode::BAD_REQUEST, "Invalid slug".into()));
    }
    publish_event(&state.pool, &state.media_dir, &slug, payload)
        .await
        .map_err(|e| (StatusCode::BAD_REQUEST, e.to_string()))?;
    Ok(Json(serde_json::json!({ "ok": true, "slug": slug })))
}

fn header_text<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    headers.get(name).and_then(|v| v.to_str().ok()).map(str::trim)
}

fn parse_optional_i32(raw: Option<&str>) -> Option<i32> {
    raw.filter(|v| !v.is_empty())?.parse().ok()
}

async fn put_event_map(
    State(state): State<Arc<AppState>>,
    Path(slug): Path<String>,
    headers: HeaderMap,
    body: Bytes,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    check_token(&state, &headers)?;
    let slug = slug.trim().to_lowercase();
    if slug.is_empty() || !slug.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
    {
        return Err((StatusCode::BAD_REQUEST, "Invalid slug".into()));
    }
    if body.is_empty() {
        return Err((StatusCode::BAD_REQUEST, "Map body is empty".into()));
    }
    let file_name = header_text(&headers, "x-map-file-name").unwrap_or("map.bin");
    let mime = header_text(&headers, "content-type")
        .map(|v| v.split(';').next().unwrap_or(v).trim())
        .filter(|v| !v.is_empty() && *v != "application/octet-stream");
    let width = parse_optional_i32(header_text(&headers, "x-map-width"));
    let height = parse_optional_i32(header_text(&headers, "x-map-height"));
    publish_map(
        &state.pool,
        &state.media_dir,
        &slug,
        &body,
        file_name,
        mime,
        width,
        height,
    )
    .await
    .map_err(|e| {
        let msg = e.to_string();
        if msg.contains("not found") {
            (StatusCode::NOT_FOUND, msg)
        } else {
            (StatusCode::BAD_REQUEST, msg)
        }
    })?;
    Ok(Json(serde_json::json!({ "ok": true, "slug": slug })))
}

async fn load_event_detail(pool: &PgPool, slug: &str) -> Result<Option<EventDetail>, sqlx::Error> {
    let event = sqlx::query_as::<_, EventRow>(
        r#"
        SELECT id, slug, title, competition_date, sport_kind, status, published_at,
               map_file_name, map_mime, map_width, map_height, map_points, meters_per_pixel,
               start_cp, finish_cp
        FROM events WHERE slug = $1
        "#,
    )
    .bind(slug)
    .fetch_optional(pool)
    .await?;
    let Some(event) = event else {
        return Ok(None);
    };

    let groups = sqlx::query_as::<_, AwardGroupPublic>(
        r#"
        SELECT id, source_id, name, gender_mode, min_age, sort_order, course_cps
        FROM award_groups
        WHERE event_id = $1
        ORDER BY sort_order ASC, name ASC
        "#,
    )
    .bind(event.id)
    .fetch_all(pool)
    .await?;

    let results = sqlx::query_as::<_, ResultPublic>(
        r#"
        SELECT
            r.id,
            r.award_group_id,
            p.id AS participant_id,
            p.source_id AS participant_source_id,
            p.bib,
            p.name,
            p.gender,
            p.age,
            p.team_id,
            p.format_name,
            r.place,
            r.points_raw,
            r.penalty_points,
            r.points_final,
            r.elapsed_seconds,
            r.status,
            r.diagnostics
        FROM results r
        JOIN participants p ON p.id = r.participant_id
        WHERE r.event_id = $1
        ORDER BY r.award_group_id, r.place NULLS LAST, r.points_final DESC, r.elapsed_seconds ASC
        "#,
    )
    .bind(event.id)
    .fetch_all(pool)
    .await?;

    let map_url = event
        .map_file_name
        .as_ref()
        .map(|name| format!("/media/events/{}/{}", event.slug, name));

    Ok(Some(EventDetail {
        slug: event.slug,
        title: event.title,
        competition_date: event.competition_date,
        sport_kind: event.sport_kind,
        status: event.status,
        published_at: event.published_at,
        map_url,
        map_mime: event.map_mime,
        map_width: event.map_width,
        map_height: event.map_height,
        meters_per_pixel: event.meters_per_pixel,
        map_points: event.map_points,
        start_cp: event.start_cp,
        finish_cp: event.finish_cp,
        award_groups: groups,
        results,
    }))
}

fn internal(err: sqlx::Error) -> (StatusCode, String) {
    tracing::error!(?err, "database error");
    (StatusCode::INTERNAL_SERVER_ERROR, "Database error".into())
}
