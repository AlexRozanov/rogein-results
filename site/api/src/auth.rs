use std::sync::Arc;

use axum::extract::{FromRequestParts, State};
use axum::http::header::{self, HeaderMap, HeaderName, HeaderValue};
use axum::http::Uri;
use axum::http::request::Parts;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::PgPool;

use crate::password::{hash_password, normalize_email, verify_password};
use crate::rbac::{permission_codes, role_codes};
use crate::{internal, AppState};

pub const SESSION_COOKIE: &str = "rogein_sid";
const SESSION_DAYS: i64 = 14;
const RESET_HOURS: i64 = 2;

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

#[derive(Debug, Deserialize)]
pub struct ChangePasswordBody {
    pub current_password: String,
    pub new_password: String,
}

#[derive(Debug, Deserialize)]
pub struct ForgotPasswordBody {
    pub email: String,
}

#[derive(Debug, Deserialize)]
pub struct ResetPasswordBody {
    pub password: String,
}

#[derive(Debug, Serialize)]
pub struct ResetInfo {
    pub email: String,
    pub expires_at: chrono::DateTime<chrono::Utc>,
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
        if origin_is_allowed(origin, headers, allowed) {
            return Ok(());
        }
        return Err((
            StatusCode::FORBIDDEN,
            "Запрос не с этого сайта".into(),
        ));
    }
    if let Some(referer) = header_text(headers, header::REFERER) {
        if allowed.iter().any(|item| referer.starts_with(item.as_str())) {
            return Ok(());
        }
        if referer_host_matches_request(referer, headers) {
            return Ok(());
        }
    }
    Err((StatusCode::FORBIDDEN, "Запрос не с этого сайта".into()))
}

fn origin_is_allowed(origin: &str, headers: &HeaderMap, allowed: &[String]) -> bool {
    let origin = origin.trim().trim_end_matches('/');
    if allowed.iter().any(|item| item == origin) {
        return true;
    }
    let Some(origin_host) = host_of_url(origin) else {
        return false;
    };
    request_hosts(headers).iter().any(|host| host == &origin_host)
}

fn referer_host_matches_request(referer: &str, headers: &HeaderMap) -> bool {
    let Some(referer_host) = host_of_url(referer) else {
        return false;
    };
    request_hosts(headers).iter().any(|host| host == &referer_host)
}

fn host_of_url(raw: &str) -> Option<String> {
    let uri: Uri = raw.parse().ok()?;
    let host = uri.host()?.trim().trim_matches(|c| c == '[' || c == ']');
    if host.is_empty() {
        return None;
    }
    Some(host.to_ascii_lowercase())
}

fn request_hosts(headers: &HeaderMap) -> Vec<String> {
    let mut hosts = Vec::new();
    if let Some(value) = header_text(headers, header::HOST) {
        if let Some(host) = host_header_name(value) {
            hosts.push(host);
        }
    }
    if let Some(value) = header_text(headers, HeaderName::from_static("x-forwarded-host")) {
        if let Some(first) = value.split(',').next() {
            if let Some(host) = host_header_name(first.trim()) {
                hosts.push(host);
            }
        }
    }
    hosts
}

fn host_header_name(raw: &str) -> Option<String> {
    let raw = raw.trim().to_ascii_lowercase();
    if raw.is_empty() {
        return None;
    }
    if let Some(rest) = raw.strip_prefix('[') {
        let end = rest.find(']')?;
        return Some(rest[..end].to_string());
    }
    Some(raw.split(':').next()?.to_string())
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

pub(crate) fn token_hash(token: &str) -> String {
    let digest = Sha256::digest(token.as_bytes());
    digest.iter().map(|b| format!("{b:02x}")).collect()
}

pub(crate) fn new_session_token() -> String {
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
    if status == "invited" {
        return Err((
            StatusCode::UNAUTHORIZED,
            "Приглашение ещё не принято. Откройте ссылку из письма.".into(),
        ));
    }
    if status == "disabled" {
        return Err((
            StatusCode::UNAUTHORIZED,
            "Учётная запись отключена".into(),
        ));
    }
    if status != "active" || !verify_password(&body.password, &password_hash) {
        return Err(invalid);
    }
    issue_session_response(&state, user_id, email, "auth.login").await
}

pub(crate) async fn issue_session_response(
    state: &AppState,
    user_id: i64,
    email: String,
    audit_action: &str,
) -> Result<Response, (StatusCode, String)> {
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
        VALUES ($1, $2, 'user', $3, '{}'::jsonb)
        "#,
    )
    .bind(user_id)
    .bind(audit_action)
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

pub async fn change_password(
    State(state): State<Arc<AppState>>,
    user: AuthUser,
    headers: HeaderMap,
    Json(body): Json<ChangePasswordBody>,
) -> Result<Response, (StatusCode, String)> {
    check_origin(&headers, &state.allowed_origins)?;
    let row: Option<(String, String)> =
        sqlx::query_as("SELECT password_hash, status FROM users WHERE id = $1")
            .bind(user.id)
            .fetch_optional(&state.pool)
            .await
            .map_err(internal)?;
    let Some((password_hash, status)) = row else {
        return Err((StatusCode::UNAUTHORIZED, "Нужен вход".into()));
    };
    if status != "active" {
        return Err((StatusCode::FORBIDDEN, "Учётная запись недоступна".into()));
    }
    if !verify_password(&body.current_password, &password_hash) {
        return Err((StatusCode::BAD_REQUEST, "Неверный текущий пароль".into()));
    }
    if body.current_password == body.new_password {
        return Err((
            StatusCode::BAD_REQUEST,
            "Новый пароль должен отличаться от текущего".into(),
        ));
    }
    let new_hash =
        hash_password(&body.new_password).map_err(|e| (StatusCode::BAD_REQUEST, e.to_string()))?;

    let mut tx = state.pool.begin().await.map_err(internal)?;
    sqlx::query(
        r#"
        UPDATE users
        SET password_hash = $2, updated_at = now()
        WHERE id = $1
        "#,
    )
    .bind(user.id)
    .bind(&new_hash)
    .execute(&mut *tx)
    .await
    .map_err(internal)?;
    sqlx::query("DELETE FROM sessions WHERE user_id = $1")
        .bind(user.id)
        .execute(&mut *tx)
        .await
        .map_err(internal)?;
    sqlx::query(
        r#"
        INSERT INTO audit_log (actor_user_id, action, entity_type, entity_id, meta)
        VALUES ($1, 'auth.change_password', 'user', $2, '{}'::jsonb)
        "#,
    )
    .bind(user.id)
    .bind(user.id.to_string())
    .execute(&mut *tx)
    .await
    .map_err(internal)?;
    tx.commit().await.map_err(internal)?;

    issue_session_response(&state, user.id, user.email, "auth.change_password_login").await
}

pub async fn forgot_password(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(body): Json<ForgotPasswordBody>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    check_origin(&headers, &state.allowed_origins)?;
    let ok = Json(serde_json::json!({
        "ok": true,
        "message": "Если такой аккаунт есть, мы отправили письмо со ссылкой."
    }));
    let email = match normalize_email(&body.email) {
        Ok(v) => v,
        Err(_) => return Ok(ok),
    };
    let row: Option<(i64, String)> =
        sqlx::query_as("SELECT id, status FROM users WHERE email = $1")
            .bind(&email)
            .fetch_optional(&state.pool)
            .await
            .map_err(internal)?;
    let Some((user_id, status)) = row else {
        return Ok(ok);
    };
    if status != "active" {
        return Ok(ok);
    }

    let mut tx = state.pool.begin().await.map_err(internal)?;
    sqlx::query(
        r#"
        UPDATE auth_tokens
        SET used_at = COALESCE(used_at, now())
        WHERE user_id = $1
          AND purpose = 'reset'
          AND used_at IS NULL
        "#,
    )
    .bind(user_id)
    .execute(&mut *tx)
    .await
    .map_err(internal)?;
    let raw_token = new_session_token();
    let digest = token_hash(&raw_token);
    sqlx::query(
        r#"
        INSERT INTO auth_tokens (user_id, purpose, token_hash, expires_at, created_by)
        VALUES ($1, 'reset', $2, now() + make_interval(hours => $3::int), $1)
        "#,
    )
    .bind(user_id)
    .bind(&digest)
    .bind(RESET_HOURS as i32)
    .execute(&mut *tx)
    .await
    .map_err(internal)?;
    sqlx::query(
        r#"
        INSERT INTO audit_log (actor_user_id, action, entity_type, entity_id, meta)
        VALUES ($1, 'auth.forgot_password', 'user', $2, '{}'::jsonb)
        "#,
    )
    .bind(user_id)
    .bind(user_id.to_string())
    .execute(&mut *tx)
    .await
    .map_err(internal)?;
    tx.commit().await.map_err(internal)?;

    let reset_url = state.mail.reset_url(&raw_token);
    if let Err(err) = state.mail.send_password_reset(&email, &reset_url).await {
        tracing::warn!(error = %err, email = %email, "не удалось отправить сброс пароля");
        // Don't leak mail config to anonymous callers; still return generic ok.
        // Ops can check logs. For local/dev without mail, include hint only if same-origin admin...
        // Keep response generic for security.
    }
    Ok(ok)
}

pub async fn reset_info(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    axum::extract::Path(token): axum::extract::Path<String>,
) -> Result<Json<ResetInfo>, (StatusCode, String)> {
    check_origin(&headers, &state.allowed_origins)?;
    let row = find_valid_reset(&state.pool, &token).await?;
    Ok(Json(ResetInfo {
        email: row.email,
        expires_at: row.expires_at,
    }))
}

pub async fn accept_reset(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    axum::extract::Path(token): axum::extract::Path<String>,
    Json(body): Json<ResetPasswordBody>,
) -> Result<Response, (StatusCode, String)> {
    check_origin(&headers, &state.allowed_origins)?;
    let row = find_valid_reset(&state.pool, &token).await?;
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
        SET password_hash = $2, updated_at = now()
        WHERE id = $1 AND status = 'active'
        "#,
    )
    .bind(row.user_id)
    .bind(&password_hash)
    .execute(&mut *tx)
    .await
    .map_err(internal)?;
    sqlx::query(
        r#"
        UPDATE auth_tokens
        SET used_at = COALESCE(used_at, now())
        WHERE user_id = $1
          AND purpose = 'reset'
          AND used_at IS NULL
        "#,
    )
    .bind(row.user_id)
    .execute(&mut *tx)
    .await
    .map_err(internal)?;
    sqlx::query("DELETE FROM sessions WHERE user_id = $1")
        .bind(row.user_id)
        .execute(&mut *tx)
        .await
        .map_err(internal)?;
    sqlx::query(
        r#"
        INSERT INTO audit_log (actor_user_id, action, entity_type, entity_id, meta)
        VALUES ($1, 'auth.reset_password', 'user', $2, '{}'::jsonb)
        "#,
    )
    .bind(row.user_id)
    .bind(row.user_id.to_string())
    .execute(&mut *tx)
    .await
    .map_err(internal)?;
    tx.commit().await.map_err(internal)?;

    issue_session_response(&state, row.user_id, row.email, "auth.reset_login").await
}

struct ResetRow {
    token_id: i64,
    user_id: i64,
    email: String,
    expires_at: chrono::DateTime<chrono::Utc>,
}

async fn find_valid_reset(
    pool: &sqlx::PgPool,
    raw_token: &str,
) -> Result<ResetRow, (StatusCode, String)> {
    let token = raw_token.trim();
    if token.is_empty() {
        return Err((StatusCode::BAD_REQUEST, "Нет токена сброса".into()));
    }
    let digest = token_hash(token);
    let row: Option<(i64, i64, String, chrono::DateTime<chrono::Utc>, String)> = sqlx::query_as(
        r#"
        SELECT t.id, u.id, u.email, t.expires_at, u.status
        FROM auth_tokens t
        JOIN users u ON u.id = t.user_id
        WHERE t.token_hash = $1
          AND t.purpose = 'reset'
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
    if status != "active" {
        return Err((
            StatusCode::BAD_REQUEST,
            "Учётная запись недоступна".into(),
        ));
    }
    Ok(ResetRow {
        token_id,
        user_id,
        email,
        expires_at,
    })
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
        headers.insert(header::HOST, HeaderValue::from_static("malahit-sprint.ru"));
        assert!(check_origin(&headers, &["http://127.0.0.1:5174".into()]).is_err());
    }

    #[test]
    fn origin_allows_same_host_as_forwarded() {
        let mut headers = HeaderMap::new();
        headers.insert(header::ORIGIN, HeaderValue::from_static("https://malahit-sprint.ru"));
        headers.insert(header::HOST, HeaderValue::from_static("127.0.0.1:18790"));
        headers.insert(
            HeaderName::from_static("x-forwarded-host"),
            HeaderValue::from_static("malahit-sprint.ru"),
        );
        assert!(check_origin(&headers, &[]).is_ok());
    }

    #[test]
    fn origin_allows_host_header() {
        let mut headers = HeaderMap::new();
        headers.insert(header::ORIGIN, HeaderValue::from_static("https://malahit-sprint.ru"));
        headers.insert(header::HOST, HeaderValue::from_static("malahit-sprint.ru"));
        assert!(check_origin(&headers, &[]).is_ok());
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
