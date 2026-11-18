use std::sync::Arc;

use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::Response;
use axum::Json;
use chrono::{DateTime, Utc};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sqlx::PgPool;

use crate::auth::{
    check_origin, issue_session_response, new_session_token, token_hash, AuthUser,
};
use crate::password::{hash_password, normalize_email};
use crate::rbac::require_organizer_perm;
use crate::{internal, AppState};

const INVITE_HOURS: i64 = 72;
const STAFF_ROLES: &[&str] = &["admin", "secretary", "editor", "finish", "owner"];

#[derive(Debug, Serialize)]
pub struct AdminUserRow {
    pub id: i64,
    pub email: String,
    pub status: String,
    pub display_name: String,
    pub roles: Vec<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct InviteBody {
    pub email: String,
    pub roles: Vec<String>,
    pub name: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct InviteResponse {
    pub user: AdminUserRow,
    pub email_sent: bool,
    pub email_error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub invite_url: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct InviteInfo {
    pub email: String,
    pub expires_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct AcceptInviteBody {
    pub password: String,
}

pub async fn list_users(
    State(state): State<Arc<AppState>>,
    user: AuthUser,
    headers: HeaderMap,
) -> Result<Json<Vec<AdminUserRow>>, (StatusCode, String)> {
    check_origin(&headers, &state.allowed_origins)?;
    let organizer_id = require_organizer_perm(&state.pool, user.id, "users.manage").await?;
    Ok(Json(load_staff_users(&state.pool, organizer_id).await?))
}

pub async fn invite_user(
    State(state): State<Arc<AppState>>,
    user: AuthUser,
    headers: HeaderMap,
    Json(body): Json<InviteBody>,
) -> Result<Json<InviteResponse>, (StatusCode, String)> {
    check_origin(&headers, &state.allowed_origins)?;
    let organizer_id = require_organizer_perm(&state.pool, user.id, "users.manage").await?;
    let roles = normalize_staff_roles(&body.roles)?;
    if roles.iter().any(|r| r == "owner") {
        require_organizer_perm(&state.pool, user.id, "roles.manage").await?;
    }
    let email = normalize_email(&body.email).map_err(|e| (StatusCode::BAD_REQUEST, e.to_string()))?;
    let display_name = body.name.unwrap_or_default().trim().to_string();

    let existing: Option<(i64, String)> =
        sqlx::query_as("SELECT id, status FROM users WHERE email = $1")
            .bind(&email)
            .fetch_optional(&state.pool)
            .await
            .map_err(internal)?;

    let mut tx = state.pool.begin().await.map_err(internal)?;
    let user_id = match existing {
        Some((_, status)) if status == "active" => {
            return Err((
                StatusCode::CONFLICT,
                "Пользователь уже активен. Отключите его или смените роли через userctl.".into(),
            ));
        }
        Some((id, status)) if status == "disabled" || status == "invited" => {
            let placeholder = unusable_password_hash()
                .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
            sqlx::query(
                r#"
                UPDATE users
                SET status = 'invited', password_hash = $2, updated_at = now()
                WHERE id = $1
                "#,
            )
            .bind(id)
            .bind(&placeholder)
            .execute(&mut *tx)
            .await
            .map_err(internal)?;
            sqlx::query(
                r#"
                INSERT INTO profiles (user_id, display_name)
                VALUES ($1, $2)
                ON CONFLICT (user_id) DO UPDATE
                SET display_name = CASE
                    WHEN EXCLUDED.display_name = '' THEN profiles.display_name
                    ELSE EXCLUDED.display_name
                END
                "#,
            )
            .bind(id)
            .bind(&display_name)
            .execute(&mut *tx)
            .await
            .map_err(internal)?;
            replace_organizer_staff_roles(&mut tx, id, organizer_id, &roles).await?;
            id
        }
        Some(_) => {
            return Err((StatusCode::CONFLICT, "Нельзя пригласить этого пользователя".into()));
        }
        None => {
            let placeholder = unusable_password_hash()
                .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
            let id: i64 = sqlx::query_scalar(
                r#"
                INSERT INTO users (email, password_hash, status)
                VALUES ($1, $2, 'invited')
                RETURNING id
                "#,
            )
            .bind(&email)
            .bind(&placeholder)
            .fetch_one(&mut *tx)
            .await
            .map_err(internal)?;
            sqlx::query(
                r#"
                INSERT INTO profiles (user_id, display_name)
                VALUES ($1, $2)
                "#,
            )
            .bind(id)
            .bind(&display_name)
            .execute(&mut *tx)
            .await
            .map_err(internal)?;
            replace_organizer_staff_roles(&mut tx, id, organizer_id, &roles).await?;
            id
        }
    };

    invalidate_invite_tokens(&mut tx, user_id).await?;
    let raw_token = new_session_token();
    let token_digest = token_hash(&raw_token);
    sqlx::query(
        r#"
        INSERT INTO auth_tokens (user_id, purpose, token_hash, expires_at, created_by)
        VALUES ($1, 'invite', $2, now() + make_interval(hours => $3::int), $4)
        "#,
    )
    .bind(user_id)
    .bind(&token_digest)
    .bind(INVITE_HOURS as i32)
    .bind(user.id)
    .execute(&mut *tx)
    .await
    .map_err(internal)?;

    sqlx::query(
        r#"
        INSERT INTO audit_log (actor_user_id, action, entity_type, entity_id, meta)
        VALUES ($1, 'users.invite', 'user', $2, $3)
        "#,
    )
    .bind(user.id)
    .bind(user_id.to_string())
    .bind(serde_json::json!({ "email": email, "roles": roles }))
    .execute(&mut *tx)
    .await
    .map_err(internal)?;

    tx.commit().await.map_err(internal)?;

    let invite_url = state.mail.invite_url(&raw_token);
    let (email_sent, email_error) = match state.mail.send_invite(&email, &invite_url).await {
        Ok(()) => (true, None),
        Err(err) => (false, Some(err.to_string())),
    };

    let row = load_staff_user(&state.pool, organizer_id, user_id)
        .await?
        .ok_or((StatusCode::INTERNAL_SERVER_ERROR, "Пользователь не найден".into()))?;

    Ok(Json(InviteResponse {
        user: row,
        email_sent,
        email_error,
        invite_url: if email_sent {
            None
        } else {
            Some(invite_url)
        },
    }))
}

pub async fn resend_invite(
    State(state): State<Arc<AppState>>,
    user: AuthUser,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Json<InviteResponse>, (StatusCode, String)> {
    check_origin(&headers, &state.allowed_origins)?;
    let organizer_id = require_organizer_perm(&state.pool, user.id, "users.manage").await?;
    let target = load_staff_user(&state.pool, organizer_id, id)
        .await?
        .ok_or((StatusCode::NOT_FOUND, "Пользователь не найден".into()))?;
    if target.status != "invited" {
        return Err((
            StatusCode::BAD_REQUEST,
            "Повторная отправка только для статуса invited".into(),
        ));
    }

    let mut tx = state.pool.begin().await.map_err(internal)?;
    invalidate_invite_tokens(&mut tx, id).await?;
    let raw_token = new_session_token();
    let token_digest = token_hash(&raw_token);
    sqlx::query(
        r#"
        INSERT INTO auth_tokens (user_id, purpose, token_hash, expires_at, created_by)
        VALUES ($1, 'invite', $2, now() + make_interval(hours => $3::int), $4)
        "#,
    )
    .bind(id)
    .bind(&token_digest)
    .bind(INVITE_HOURS as i32)
    .bind(user.id)
    .execute(&mut *tx)
    .await
    .map_err(internal)?;
    sqlx::query(
        r#"
        INSERT INTO audit_log (actor_user_id, action, entity_type, entity_id, meta)
        VALUES ($1, 'users.resend_invite', 'user', $2, '{}'::jsonb)
        "#,
    )
    .bind(user.id)
    .bind(id.to_string())
    .execute(&mut *tx)
    .await
    .map_err(internal)?;
    tx.commit().await.map_err(internal)?;

    let invite_url = state.mail.invite_url(&raw_token);
    let (email_sent, email_error) = match state.mail.send_invite(&target.email, &invite_url).await {
        Ok(()) => (true, None),
        Err(err) => (false, Some(err.to_string())),
    };

    Ok(Json(InviteResponse {
        user: target,
        email_sent,
        email_error,
        invite_url: if email_sent {
            None
        } else {
            Some(invite_url)
        },
    }))
}

pub async fn disable_user(
    State(state): State<Arc<AppState>>,
    user: AuthUser,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Json<AdminUserRow>, (StatusCode, String)> {
    check_origin(&headers, &state.allowed_origins)?;
    let organizer_id = require_organizer_perm(&state.pool, user.id, "users.manage").await?;
    let target = load_staff_user(&state.pool, organizer_id, id)
        .await?
        .ok_or((StatusCode::NOT_FOUND, "Пользователь не найден".into()))?;
    if target.id == user.id {
        return Err((StatusCode::BAD_REQUEST, "Нельзя отключить себя".into()));
    }
    if is_last_active_owner(&state.pool, id).await? {
        return Err((
            StatusCode::BAD_REQUEST,
            "Нельзя отключить последнего активного owner".into(),
        ));
    }

    let mut tx = state.pool.begin().await.map_err(internal)?;
    sqlx::query(
        r#"
        UPDATE users
        SET status = 'disabled', updated_at = now()
        WHERE id = $1
        "#,
    )
    .bind(id)
    .execute(&mut *tx)
    .await
    .map_err(internal)?;
    sqlx::query("DELETE FROM sessions WHERE user_id = $1")
        .bind(id)
        .execute(&mut *tx)
        .await
        .map_err(internal)?;
    invalidate_invite_tokens(&mut tx, id).await?;
    sqlx::query(
        r#"
        INSERT INTO audit_log (actor_user_id, action, entity_type, entity_id, meta)
        VALUES ($1, 'users.disable', 'user', $2, '{}'::jsonb)
        "#,
    )
    .bind(user.id)
    .bind(id.to_string())
    .execute(&mut *tx)
    .await
    .map_err(internal)?;
    tx.commit().await.map_err(internal)?;

    load_staff_user(&state.pool, organizer_id, id)
        .await?
        .map(Json)
        .ok_or((StatusCode::INTERNAL_SERVER_ERROR, "Пользователь не найден".into()))
}

pub async fn invite_info(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(token): Path<String>,
) -> Result<Json<InviteInfo>, (StatusCode, String)> {
    check_origin(&headers, &state.allowed_origins)?;
    let row = find_valid_invite(&state.pool, &token).await?;
    Ok(Json(InviteInfo {
        email: row.email,
        expires_at: row.expires_at,
    }))
}

pub async fn accept_invite(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(token): Path<String>,
    Json(body): Json<AcceptInviteBody>,
) -> Result<Response, (StatusCode, String)> {
    check_origin(&headers, &state.allowed_origins)?;
    let row = find_valid_invite(&state.pool, &token).await?;
    let password_hash =
        hash_password(&body.password).map_err(|e| (StatusCode::BAD_REQUEST, e.to_string()))?;

    let mut tx = state.pool.begin().await.map_err(internal)?;
    let updated: Option<i64> = sqlx::query_scalar(
        r#"
        UPDATE auth_tokens
        SET used_at = now()
        WHERE id = $1
          AND used_at IS NULL
          AND expires_at > now()
        RETURNING id
        "#,
    )
    .bind(row.token_id)
    .fetch_optional(&mut *tx)
    .await
    .map_err(internal)?;
    if updated.is_none() {
        return Err((
            StatusCode::BAD_REQUEST,
            "Ссылка уже использована или устарела".into(),
        ));
    }
    sqlx::query(
        r#"
        UPDATE users
        SET password_hash = $2, status = 'active', updated_at = now()
        WHERE id = $1
        "#,
    )
    .bind(row.user_id)
    .bind(&password_hash)
    .execute(&mut *tx)
    .await
    .map_err(internal)?;
    invalidate_invite_tokens(&mut tx, row.user_id).await?;
    sqlx::query(
        r#"
        INSERT INTO audit_log (actor_user_id, action, entity_type, entity_id, meta)
        VALUES ($1, 'auth.invite_accept', 'user', $2, '{}'::jsonb)
        "#,
    )
    .bind(row.user_id)
    .bind(row.user_id.to_string())
    .execute(&mut *tx)
    .await
    .map_err(internal)?;
    tx.commit().await.map_err(internal)?;

    issue_session_response(&state, row.user_id, row.email, "auth.invite_login").await
}

struct InviteRow {
    token_id: i64,
    user_id: i64,
    email: String,
    expires_at: DateTime<Utc>,
}

async fn find_valid_invite(
    pool: &PgPool,
    raw_token: &str,
) -> Result<InviteRow, (StatusCode, String)> {
    let token = raw_token.trim();
    if token.is_empty() {
        return Err((StatusCode::BAD_REQUEST, "Нет токена приглашения".into()));
    }
    let digest = token_hash(token);
    let row: Option<(i64, i64, String, DateTime<Utc>, String)> = sqlx::query_as(
        r#"
        SELECT t.id, u.id, u.email, t.expires_at, u.status
        FROM auth_tokens t
        JOIN users u ON u.id = t.user_id
        WHERE t.token_hash = $1
          AND t.purpose = 'invite'
          AND t.used_at IS NULL
          AND t.expires_at > now()
        "#,
    )
    .bind(&digest)
    .fetch_optional(pool)
    .await
    .map_err(internal)?;
    let Some((token_id, user_id, email, expires_at, status)) = row else {
        return Err((
            StatusCode::NOT_FOUND,
            "Ссылка недействительна или устарела".into(),
        ));
    };
    if status != "invited" {
        return Err((
            StatusCode::BAD_REQUEST,
            "Приглашение уже принято или учётка недоступна".into(),
        ));
    }
    Ok(InviteRow {
        token_id,
        user_id,
        email,
        expires_at,
    })
}

fn normalize_staff_roles(raw: &[String]) -> Result<Vec<String>, (StatusCode, String)> {
    let mut out = Vec::new();
    for item in raw {
        let code = item.trim().to_ascii_lowercase();
        if code.is_empty() {
            continue;
        }
        if !STAFF_ROLES.contains(&code.as_str()) {
            return Err((
                StatusCode::BAD_REQUEST,
                format!("Роль '{item}' нельзя выдать через приглашение"),
            ));
        }
        if !out.contains(&code) {
            out.push(code);
        }
    }
    if out.is_empty() {
        return Err((StatusCode::BAD_REQUEST, "Укажите хотя бы одну роль".into()));
    }
    Ok(out)
}

async fn replace_organizer_staff_roles(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user_id: i64,
    organizer_id: i64,
    roles: &[String],
) -> Result<(), (StatusCode, String)> {
    sqlx::query(
        r#"
        DELETE FROM user_roles ur
        USING roles r
        WHERE ur.user_id = $1
          AND ur.organizer_id = $2
          AND ur.event_id IS NULL
          AND ur.club_id IS NULL
          AND ur.role_id = r.id
          AND r.code = ANY($3::text[])
        "#,
    )
    .bind(user_id)
    .bind(organizer_id)
    .bind(STAFF_ROLES)
    .execute(&mut **tx)
    .await
    .map_err(internal)?;

    for code in roles {
        let role_id: Option<i64> = sqlx::query_scalar("SELECT id FROM roles WHERE code = $1")
            .bind(code)
            .fetch_optional(&mut **tx)
            .await
            .map_err(internal)?;
        let role_id = role_id.ok_or((StatusCode::BAD_REQUEST, format!("Нет роли {code}")))?;
        sqlx::query(
            r#"
            INSERT INTO user_roles (user_id, role_id, organizer_id)
            VALUES ($1, $2, $3)
            "#,
        )
        .bind(user_id)
        .bind(role_id)
        .bind(organizer_id)
        .execute(&mut **tx)
        .await
        .map_err(internal)?;
    }
    Ok(())
}

async fn invalidate_invite_tokens(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user_id: i64,
) -> Result<(), (StatusCode, String)> {
    sqlx::query(
        r#"
        UPDATE auth_tokens
        SET used_at = COALESCE(used_at, now())
        WHERE user_id = $1
          AND purpose = 'invite'
          AND used_at IS NULL
        "#,
    )
    .bind(user_id)
    .execute(&mut **tx)
    .await
    .map_err(internal)?;
    Ok(())
}

async fn load_staff_users(
    pool: &PgPool,
    organizer_id: i64,
) -> Result<Vec<AdminUserRow>, (StatusCode, String)> {
    let rows: Vec<(i64, String, String, String, DateTime<Utc>, Option<String>)> = sqlx::query_as(
        r#"
        SELECT
            u.id,
            u.email,
            u.status,
            COALESCE(p.display_name, '') AS display_name,
            u.created_at,
            (
                SELECT string_agg(r.code, ',' ORDER BY r.code)
                FROM user_roles ur
                JOIN roles r ON r.id = ur.role_id
                WHERE ur.user_id = u.id
                  AND ur.organizer_id = $1
                  AND ur.event_id IS NULL
                  AND ur.club_id IS NULL
                  AND r.code = ANY($2::text[])
            ) AS roles
        FROM users u
        LEFT JOIN profiles p ON p.user_id = u.id
        WHERE EXISTS (
            SELECT 1
            FROM user_roles ur
            JOIN roles r ON r.id = ur.role_id
            WHERE ur.user_id = u.id
              AND ur.organizer_id = $1
              AND ur.event_id IS NULL
              AND ur.club_id IS NULL
              AND r.code = ANY($2::text[])
        )
        ORDER BY u.created_at DESC, u.id DESC
        "#,
    )
    .bind(organizer_id)
    .bind(STAFF_ROLES)
    .fetch_all(pool)
    .await
    .map_err(internal)?;

    Ok(rows
        .into_iter()
        .map(|(id, email, status, display_name, created_at, roles)| AdminUserRow {
            id,
            email,
            status,
            display_name,
            roles: roles
                .unwrap_or_default()
                .split(',')
                .filter(|s| !s.is_empty())
                .map(str::to_string)
                .collect(),
            created_at,
        })
        .collect())
}

async fn load_staff_user(
    pool: &PgPool,
    organizer_id: i64,
    user_id: i64,
) -> Result<Option<AdminUserRow>, (StatusCode, String)> {
    Ok(load_staff_users(pool, organizer_id)
        .await?
        .into_iter()
        .find(|row| row.id == user_id))
}

async fn is_last_active_owner(pool: &PgPool, user_id: i64) -> Result<bool, (StatusCode, String)> {
    sqlx::query_scalar(
        r#"
        SELECT EXISTS (
            SELECT 1
            FROM user_roles ur
            JOIN roles r ON r.id = ur.role_id
            JOIN users u ON u.id = ur.user_id
            WHERE r.code = 'owner'
              AND ur.event_id IS NULL
              AND ur.club_id IS NULL
              AND u.status = 'active'
              AND u.id = $1
        )
        AND (
            SELECT COUNT(*)
            FROM user_roles ur
            JOIN roles r ON r.id = ur.role_id
            JOIN users u ON u.id = ur.user_id
            WHERE r.code = 'owner'
              AND ur.event_id IS NULL
              AND ur.club_id IS NULL
              AND u.status = 'active'
        ) = 1
        "#,
    )
    .bind(user_id)
    .fetch_one(pool)
    .await
    .map_err(internal)
}

fn unusable_password_hash() -> anyhow::Result<String> {
    let mut bytes = [0u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut bytes);
    let secret: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    // Long random secret; never shown to anyone.
    hash_password(&format!("invite-placeholder-{secret}-xx"))
}
