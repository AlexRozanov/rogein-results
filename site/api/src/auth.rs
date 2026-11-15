use std::sync::Arc;

use axum::extract::{FromRequestParts, State};
use axum::http::header::{self, HeaderMap, HeaderValue};
use axum::http::request::Parts;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::PgPool;

use crate::password::{normalize_email, verify_password};
use crate::rbac::{permission_codes, role_codes};
use crate::{internal, AppState};

pub const SESSION_COOKIE: &str = "rogein_sid";
const SESSION_DAYS: i64 = 14;

#[derive(Clone, Debug)]
pub struct AuthUser {
    pub id: i64,
    pub email: String,
}

#[derive(Debug, Deserialize)]
pub struct LoginBody {
    pub email: String,
    pub password: String,
}

#[derive(Debug, Serialize)]
pub struct MeResponse {
    pub id: i64,
    pub email: String,
    pub display_name: String,
    pub roles: Vec<String>,
    pub permissions: Vec<String>,
}

pub fn check_origin(
    headers: &HeaderMap,
    allowed: &[String],
) -> Result<(), (StatusCode, String)> {
    if let Some(origin) = header_text(headers, header::ORIGIN) {
        if allowed.iter().any(|item| item == origin) {
            return Ok(());
        }
        return Err((StatusCode::FORBIDDEN, "Неверный Origin".into()));
    }
    if let Some(referer) = header_text(headers, header::REFERER) {
        if allowed.iter().any(|item| referer.starts_with(item.as_str())) {
            return Ok(());
        }
    }
    Err((StatusCode::FORBIDDEN, "Нужен Origin".into()))
}

fn header_text<'a>(headers: &'a HeaderMap, name: header::HeaderName) -> Option<&'a str> {
    headers.get(name).and_then(|v| v.to_str().ok()).map(str::trim)
}

pub fn parse_allowed_origins() -> Vec<String> {
    let mut out = vec![
        "http://127.0.0.1:5174".into(),
        "http://localhost:5174".into(),
        "http://127.0.0.1:8088".into(),
        "http://localhost:8088".into(),
        "http://127.0.0.1:18790".into(),
    ];
    for key in ["SITE_ORIGIN", "ALLOWED_ORIGINS"] {
        let Ok(raw) = std::env::var(key) else {
            continue;
        };
        for part in raw.split(',') {
            let origin = part.trim().trim_end_matches('/').to_string();
            if !origin.is_empty() && !out.contains(&origin) {
                out.push(origin);
            }
        }
    }
    out
}

pub fn cookie_secure_from_env() -> bool {
    match std::env::var("COOKIE_SECURE").unwrap_or_default().trim().to_ascii_lowercase().as_str() {
        "1" | "true" | "yes" => true,
        "0" | "false" | "no" => false,
        _ => std::env::var("SITE_ORIGIN")
            .map(|v| v.trim().starts_with("https://"))
            .unwrap_or(false),
    }
}

fn cookie_value(headers: &HeaderMap, name: &str) -> Option<String> {
    let raw = headers.get(header::COOKIE)?.to_str().ok()?;
    for part in raw.split(';') {
        let part = part.trim();
        let Some((key, value)) = part.split_once('=') else {
            continue;
        };
        if key.trim() == name {
            return Some(value.trim().to_string());
        }
    }
    None
}

fn token_hash(token: &str) -> String {
    let digest = Sha256::digest(token.as_bytes());
    digest.iter().map(|b| format!("{b:02x}")).collect()
}

fn new_session_token() -> String {
    let mut bytes = [0u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut bytes);
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn set_cookie_header(token: &str, secure: bool, max_age: i64) -> HeaderValue {
    let mut value = format!(
        "{SESSION_COOKIE}={token}; Path=/; HttpOnly; SameSite=Lax; Max-Age={max_age}"
    );
    if secure {
        value.push_str("; Secure");
    }
    HeaderValue::from_str(&value).unwrap_or_else(|_| HeaderValue::from_static("rogein_sid=; Path=/"))
}

fn clear_cookie_header(secure: bool) -> HeaderValue {
    set_cookie_header("deleted", secure, 0)
}

async fn load_me(pool: &PgPool, user_id: i64, email: String) -> Result<MeResponse, (StatusCode, String)> {
    let display_name: String = sqlx::query_scalar(
        "SELECT COALESCE(display_name, '') FROM profiles WHERE user_id = $1",
    )
    .bind(user_id)
    .fetch_optional(pool)
    .await
    .map_err(internal)?
    .unwrap_or_default();
    let roles = role_codes(pool, user_id).await.map_err(internal)?;
    let permissions = permission_codes(pool, user_id).await.map_err(internal)?;
    Ok(MeResponse {
        id: user_id,
        email,
        display_name,
        roles,
        permissions,
    })
}

pub async fn login(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(body): Json<LoginBody>,
) -> Result<Response, (StatusCode, String)> {
    check_origin(&headers, &state.allowed_origins)?;
    let email = normalize_email(&body.email).map_err(|e| (StatusCode::BAD_REQUEST, e.to_string()))?;
    let row: Option<(i64, String, String)> = sqlx::query_as(
        "SELECT id, password_hash, status FROM users WHERE email = $1",
    )
    .bind(&email)
    .fetch_optional(&state.pool)
    .await
    .map_err(internal)?;
    let invalid = (StatusCode::UNAUTHORIZED, "Неверный email или пароль".into());
    let Some((user_id, password_hash, status)) = row else {
        return Err(invalid);
    };
    if status != "active" || !verify_password(&body.password, &password_hash) {
        return Err(invalid);
    }
    let token = new_session_token();
    let hash = token_hash(&token);
    sqlx::query(
        r#"
        INSERT INTO sessions (user_id, token_hash, expires_at, last_seen_at)
        VALUES ($1, $2, now() + interval '14 days', now())
        "#,
    )
    .bind(user_id)
    .bind(&hash)
    .execute(&state.pool)
    .await
    .map_err(internal)?;
    sqlx::query(
        r#"
        INSERT INTO audit_log (actor_user_id, action, entity_type, entity_id, meta)
        VALUES ($1, 'auth.login', 'user', $2, '{}'::jsonb)
        "#,
    )
    .bind(user_id)
    .bind(user_id.to_string())
    .execute(&state.pool)
    .await
    .map_err(internal)?;
    let me = load_me(&state.pool, user_id, email).await?;
    let mut response = Json(me).into_response();
    response.headers_mut().insert(
        header::SET_COOKIE,
        set_cookie_header(&token, state.cookie_secure, SESSION_DAYS * 86400),
    );
    Ok(response)
}

pub async fn logout(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Response, (StatusCode, String)> {
    check_origin(&headers, &state.allowed_origins)?;
    if let Some(token) = cookie_value(&headers, SESSION_COOKIE) {
        let hash = token_hash(&token);
        sqlx::query("DELETE FROM sessions WHERE token_hash = $1")
            .bind(&hash)
            .execute(&state.pool)
            .await
            .map_err(internal)?;
    }
    let mut response = Json(serde_json::json!({ "ok": true })).into_response();
    response.headers_mut().insert(
        header::SET_COOKIE,
        clear_cookie_header(state.cookie_secure),
    );
    Ok(response)
}

pub async fn me(
    State(state): State<Arc<AppState>>,
    user: AuthUser,
) -> Result<Json<MeResponse>, (StatusCode, String)> {
    Ok(Json(load_me(&state.pool, user.id, user.email).await?))
}

impl FromRequestParts<Arc<AppState>> for AuthUser {
    type Rejection = (StatusCode, String);

    async fn from_request_parts(
        parts: &mut Parts,
        state: &Arc<AppState>,
    ) -> Result<Self, Self::Rejection> {
        let token = cookie_value(&parts.headers, SESSION_COOKIE).ok_or((
            StatusCode::UNAUTHORIZED,
            "Нужен вход".into(),
        ))?;
        let hash = token_hash(&token);
        let row: Option<(i64, String)> = sqlx::query_as(
            r#"
            SELECT u.id, u.email
            FROM sessions s
            JOIN users u ON u.id = s.user_id
            WHERE s.token_hash = $1
              AND s.expires_at > now()
              AND u.status = 'active'
            "#,
        )
        .bind(&hash)
        .fetch_optional(&state.pool)
        .await
        .map_err(internal)?;
        let Some((id, email)) = row else {
            return Err((StatusCode::UNAUTHORIZED, "Нужен вход".into()));
        };
        sqlx::query("UPDATE sessions SET last_seen_at = now() WHERE token_hash = $1")
            .bind(&hash)
            .execute(&state.pool)
            .await
            .map_err(internal)?;
        Ok(AuthUser { id, email })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::HeaderName;

    #[test]
    fn origin_allows_listed() {
        let mut headers = HeaderMap::new();
        headers.insert(header::ORIGIN, HeaderValue::from_static("http://127.0.0.1:5174"));
        assert!(check_origin(&headers, &["http://127.0.0.1:5174".into()]).is_ok());
    }

    #[test]
    fn origin_rejects_other() {
        let mut headers = HeaderMap::new();
        headers.insert(header::ORIGIN, HeaderValue::from_static("http://evil.example"));
        assert!(check_origin(&headers, &["http://127.0.0.1:5174".into()]).is_err());
    }

    #[test]
    fn referer_fallback() {
        let mut headers = HeaderMap::new();
        headers.insert(
            HeaderName::from_static("referer"),
            HeaderValue::from_static("http://127.0.0.1:5174/admin/login"),
        );
        assert!(check_origin(&headers, &["http://127.0.0.1:5174".into()]).is_ok());
    }
}
