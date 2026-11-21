//! Cloud field journal relay: phones fold locally; site stores/serves ops.
//! Auth: Bearer publish token + X-RogeIn-Field-Code (same as LAN host).

use std::collections::HashMap;
use std::sync::Arc;

use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::Json;
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use sqlx::types::Json as SqlJson;
use sqlx::PgPool;

use crate::{check_token, internal, normalize_slug, AppState};

/// Must stay in sync with Android `FIELD_OP_SCHEMA` / `FIELD_PROTOCOL_VERSION`.
pub const FIELD_OP_SCHEMA: i32 = 1;
pub const FIELD_PROTOCOL_VERSION: i32 = 1;

const MAX_OPS_PER_REQUEST: usize = 500;
const MAX_OPS_PULL: i64 = 1000;

#[derive(Debug, Deserialize)]
pub struct FieldOpsQuery {
    /// JSON object: `{ "device_id": max_seq, ... }`
    pub cursors: Option<String>,
}

fn field_code(headers: &HeaderMap) -> String {
    headers
        .get("x-rogein-field-code")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .trim()
        .to_string()
}

fn hash_start_code(code: &str) -> String {
    let normalized = code.trim().to_uppercase();
    let mut hasher = Sha256::new();
    hasher.update(normalized.as_bytes());
    format!("{:x}", hasher.finalize())
}

fn codes_match(got: &str, stored_hash: &str) -> bool {
    if got.trim().is_empty() || stored_hash.is_empty() {
        return false;
    }
    let got_hash = hash_start_code(got);
    // Constant-time-ish compare of equal-length hex hashes.
    if got_hash.len() != stored_hash.len() {
        return false;
    }
    got_hash
        .bytes()
        .zip(stored_hash.bytes())
        .fold(0u8, |acc, (a, b)| acc | (a ^ b))
        == 0
}

async fn ensure_session(
    pool: &PgPool,
    slug: &str,
    code: &str,
) -> Result<(), (StatusCode, String)> {
    if code.trim().is_empty() {
        return Err((StatusCode::UNAUTHORIZED, "нужен код старта".into()));
    }
    let hash = hash_start_code(code);
    let existing: Option<String> = sqlx::query_scalar(
        "SELECT start_code_hash FROM field_sessions WHERE slug = $1",
    )
    .bind(slug)
    .fetch_optional(pool)
    .await
    .map_err(internal)?;

    match existing {
        None => {
            sqlx::query(
                r#"
                INSERT INTO field_sessions (slug, start_code_hash)
                VALUES ($1, $2)
                "#,
            )
            .bind(slug)
            .bind(&hash)
            .execute(pool)
            .await
            .map_err(internal)?;
            Ok(())
        }
        Some(stored) if codes_match(code, &stored) => {
            sqlx::query(
                "UPDATE field_sessions SET updated_at = now() WHERE slug = $1",
            )
            .bind(slug)
            .execute(pool)
            .await
            .map_err(internal)?;
            Ok(())
        }
        Some(_) => Err((StatusCode::UNAUTHORIZED, "неверный код старта".into())),
    }
}

async fn authorize_field(
    state: &AppState,
    headers: &HeaderMap,
    slug: &str,
) -> Result<(), (StatusCode, String)> {
    check_token(state, headers)?;
    let code = field_code(headers);
    ensure_session(&state.pool, slug, &code).await
}

pub async fn field_hello(
    State(state): State<Arc<AppState>>,
    Path(slug): Path<String>,
    headers: HeaderMap,
) -> Result<Json<Value>, (StatusCode, String)> {
    let slug = normalize_slug(&slug)?;
    authorize_field(&state, &headers, &slug).await?;
    let op_count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM field_ops WHERE slug = $1")
        .bind(&slug)
        .fetch_one(&state.pool)
        .await
        .map_err(internal)?;
    Ok(Json(json!({
        "ok": true,
        "protocol_version": FIELD_PROTOCOL_VERSION,
        "schema": FIELD_OP_SCHEMA,
        "event_slug": slug,
        "op_count": op_count,
        "mode": "cloud",
    })))
}

pub async fn get_field_ops(
    State(state): State<Arc<AppState>>,
    Path(slug): Path<String>,
    Query(query): Query<FieldOpsQuery>,
    headers: HeaderMap,
) -> Result<Json<Value>, (StatusCode, String)> {
    let slug = normalize_slug(&slug)?;
    authorize_field(&state, &headers, &slug).await?;

    let cursors_raw = query.cursors.unwrap_or_else(|| "{}".into());
    let cursors_json: Value = serde_json::from_str(&cursors_raw).unwrap_or_else(|_| json!({}));

    let rows: Vec<SqlJson<Value>> = sqlx::query_scalar(
        r#"
        SELECT envelope
        FROM field_ops o
        WHERE o.slug = $1
          AND o.device_seq > COALESCE(($2::jsonb ->> o.device_id)::bigint, 0)
        ORDER BY o.received_at ASC, o.device_id ASC, o.device_seq ASC
        LIMIT $3
        "#,
    )
    .bind(&slug)
    .bind(&cursors_json)
    .bind(MAX_OPS_PULL)
    .fetch_all(&state.pool)
    .await
    .map_err(internal)?;

    let ops: Vec<Value> = rows.into_iter().map(|j| j.0).collect();
    Ok(Json(json!({ "ops": ops })))
}

pub async fn post_field_ops(
    State(state): State<Arc<AppState>>,
    Path(slug): Path<String>,
    headers: HeaderMap,
    Json(body): Json<Value>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let slug = normalize_slug(&slug)?;
    authorize_field(&state, &headers, &slug).await?;

    let arr = body
        .get("ops")
        .and_then(|v| v.as_array())
        .ok_or_else(|| (StatusCode::BAD_REQUEST, "нужен объект {\"ops\":[...]}".into()))?;

    if arr.len() > MAX_OPS_PER_REQUEST {
        return Err((
            StatusCode::BAD_REQUEST,
            format!("слишком много ops (макс {MAX_OPS_PER_REQUEST})"),
        ));
    }

    let mut accepted: i64 = 0;
    let mut tx = state.pool.begin().await.map_err(internal)?;

    for item in arr {
        let op_id = item
            .get("op_id")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim();
        let device_id = item
            .get("device_id")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim();
        let device_seq = item.get("device_seq").and_then(|v| v.as_i64()).unwrap_or(0);
        let clock_ms = item.get("clock_ms").and_then(|v| v.as_i64()).unwrap_or(0);
        let actor = item
            .get("actor")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let op_type = item
            .get("type")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim();
        let schema = item
            .get("schema")
            .and_then(|v| v.as_i64())
            .unwrap_or(FIELD_OP_SCHEMA as i64) as i32;
        let event_slug = item
            .get("event_slug")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim();
        let payload = item.get("payload").cloned().unwrap_or_else(|| json!({}));

        if op_id.is_empty() || device_id.is_empty() || op_type.is_empty() || device_seq <= 0 {
            return Err((
                StatusCode::BAD_REQUEST,
                "каждая op: op_id, device_id, device_seq>0, type".into(),
            ));
        }
        if schema > FIELD_OP_SCHEMA {
            return Err((
                StatusCode::BAD_REQUEST,
                format!("schema {schema} новее сервера ({FIELD_OP_SCHEMA})"),
            ));
        }
        if !event_slug.is_empty() && !event_slug.eq_ignore_ascii_case(&slug) {
            return Err((
                StatusCode::BAD_REQUEST,
                format!("op event_slug «{event_slug}» ≠ путь «{slug}»"),
            ));
        }

        let result = sqlx::query(
            r#"
            INSERT INTO field_ops (
                slug, op_id, device_id, device_seq, clock_ms, actor, op_type, payload, envelope
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
            ON CONFLICT (op_id) DO NOTHING
            "#,
        )
        .bind(&slug)
        .bind(op_id)
        .bind(device_id)
        .bind(device_seq)
        .bind(clock_ms)
        .bind(&actor)
        .bind(op_type)
        .bind(SqlJson(payload))
        .bind(SqlJson(item.clone()))
        .execute(&mut *tx)
        .await
        .map_err(internal)?;

        if result.rows_affected() > 0 {
            accepted += 1;
        }
    }

    tx.commit().await.map_err(internal)?;

    let cursors = load_cursors(&state.pool, &slug).await?;
    Ok(Json(json!({
        "accepted": accepted,
        "cursors": cursors,
    })))
}

async fn load_cursors(pool: &PgPool, slug: &str) -> Result<HashMap<String, i64>, (StatusCode, String)> {
    let rows: Vec<(String, i64)> = sqlx::query_as(
        r#"
        SELECT device_id, MAX(device_seq)
        FROM field_ops
        WHERE slug = $1
        GROUP BY device_id
        "#,
    )
    .bind(slug)
    .fetch_all(pool)
    .await
    .map_err(internal)?;
    Ok(rows.into_iter().collect())
}
