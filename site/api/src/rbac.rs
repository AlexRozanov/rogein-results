use axum::http::StatusCode;
use sqlx::PgPool;

use crate::internal;

pub async fn require_organizer_perm(
    pool: &PgPool,
    user_id: i64,
    permission: &str,
) -> Result<i64, (StatusCode, String)> {
    let organizer_id: Option<i64> = sqlx::query_scalar(
        r#"
        SELECT ur.organizer_id
        FROM user_roles ur
        JOIN role_permissions rp ON rp.role_id = ur.role_id
        JOIN users u ON u.id = ur.user_id
        WHERE ur.user_id = $1
          AND u.status = 'active'
          AND rp.permission_code = $2
          AND ur.event_id IS NULL
          AND ur.club_id IS NULL
        ORDER BY ur.id
        LIMIT 1
        "#,
    )
    .bind(user_id)
    .bind(permission)
    .fetch_optional(pool)
    .await
    .map_err(internal)?;
    organizer_id.ok_or((
        StatusCode::FORBIDDEN,
        "Недостаточно прав".into(),
    ))
}

pub async fn permission_codes(pool: &PgPool, user_id: i64) -> Result<Vec<String>, sqlx::Error> {
    sqlx::query_scalar(
        r#"
        SELECT DISTINCT rp.permission_code
        FROM user_roles ur
        JOIN role_permissions rp ON rp.role_id = ur.role_id
        WHERE ur.user_id = $1
        ORDER BY rp.permission_code
        "#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
}

pub async fn role_codes(pool: &PgPool, user_id: i64) -> Result<Vec<String>, sqlx::Error> {
    sqlx::query_scalar(
        r#"
        SELECT DISTINCT r.code
        FROM user_roles ur
        JOIN roles r ON r.id = ur.role_id
        WHERE ur.user_id = $1
        ORDER BY r.code
        "#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
}
