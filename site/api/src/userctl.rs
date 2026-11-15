use std::io::{self, IsTerminal};

use anyhow::{bail, Context, Result};
use clap::{Args, Subcommand};
use serde_json::json;
use sqlx::{PgPool, Postgres, Transaction};

use crate::password::{hash_password, normalize_email};

#[derive(Debug, Subcommand)]
pub enum UserctlCommand {
    /// Создать пользователя и выдать роли
    Create(CreateArgs),
    /// Добавить роль уже существующему пользователю
    Grant(GrantArgs),
    /// Сменить пароль
    SetPassword(EmailArgs),
    /// Отключить вход
    Disable(EmailArgs),
    /// Список пользователей и ролей
    List,
}

#[derive(Debug, Args)]
pub struct CreateArgs {
    #[arg(long)]
    pub email: String,
    #[arg(long, required = true)]
    pub role: Vec<String>,
    #[arg(long)]
    pub name: Option<String>,
    #[arg(long, default_value = "malahit")]
    pub organizer: String,
    #[arg(long)]
    pub event: Option<String>,
    #[arg(long)]
    pub club: Option<String>,
}

#[derive(Debug, Args)]
pub struct GrantArgs {
    #[arg(long)]
    pub email: String,
    #[arg(long, required = true)]
    pub role: Vec<String>,
    #[arg(long, default_value = "malahit")]
    pub organizer: String,
    #[arg(long)]
    pub event: Option<String>,
    #[arg(long)]
    pub club: Option<String>,
}

#[derive(Debug, Args)]
pub struct EmailArgs {
    #[arg(long)]
    pub email: String,
}

pub async fn run(command: UserctlCommand, pool: PgPool) -> Result<()> {
    refuse_unless_local_db()?;
    match command {
        UserctlCommand::Create(args) => create(pool, args).await,
        UserctlCommand::Grant(args) => grant(pool, args).await,
        UserctlCommand::SetPassword(args) => set_password(pool, args).await,
        UserctlCommand::Disable(args) => disable(pool, args).await,
        UserctlCommand::List => list(pool).await,
    }
}

pub fn refuse_unless_local_db() -> Result<()> {
    if env_flag("USERCTL_ALLOW_REMOTE") {
        return Ok(());
    }
    let url = std::env::var("DATABASE_URL").unwrap_or_default();
    if database_is_local(&url) {
        return Ok(());
    }
    bail!(
        "userctl только на машине с Postgres (localhost). \
         Зайдите по SSH на сервер БД или, если база отдельно, задайте USERCTL_ALLOW_REMOTE=1 там."
    )
}

fn env_flag(name: &str) -> bool {
    matches!(
        std::env::var(name).unwrap_or_default().trim().to_ascii_lowercase().as_str(),
        "1" | "true" | "yes"
    )
}

pub fn database_is_local(url: &str) -> bool {
    let Some(host) = postgres_host(url) else {
        return false;
    };
    host.is_empty()
        || host == "localhost"
        || host == "127.0.0.1"
        || host == "::1"
        || host == "[::1]"
        || host.starts_with('/')
}

fn postgres_host(url: &str) -> Option<String> {
    let rest = url.split("://").nth(1)?;
    let authority = rest.split('/').next().unwrap_or(rest);
    let hostport = authority.rsplit('@').next().unwrap_or(authority);
    if hostport.starts_with('[') {
        let end = hostport.find(']')?;
        return Some(hostport[..=end].to_string());
    }
    let host = hostport.split(':').next().unwrap_or(hostport).trim();
    Some(host.to_string())
}

async fn create(pool: PgPool, args: CreateArgs) -> Result<()> {
    let email = normalize_email(&args.email)?;
    if args.role.is_empty() {
        bail!("Укажите хотя бы --role owner (можно несколько --role)");
    }
    let exists: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM users WHERE email = $1)")
        .bind(&email)
        .fetch_one(&pool)
        .await?;
    if exists {
        bail!("Пользователь {email} уже есть. Роль добавьте через userctl grant");
    }
    let scope = resolve_scope(&pool, &args.organizer, args.event.as_deref(), args.club.as_deref()).await?;
    let roles = load_roles(&pool, &args.role).await?;
    let password_hash = hash_password(&read_password_twice()?)?;
    let display_name = args.name.unwrap_or_default().trim().to_string();

    let mut tx = pool.begin().await?;
    let user_id: i64 = sqlx::query_scalar(
        r#"
        INSERT INTO users (email, password_hash, status)
        VALUES ($1, $2, 'active')
        RETURNING id
        "#,
    )
    .bind(&email)
    .bind(&password_hash)
    .fetch_one(&mut *tx)
    .await?;
    sqlx::query(
        r#"
        INSERT INTO profiles (user_id, display_name)
        VALUES ($1, $2)
        "#,
    )
    .bind(user_id)
    .bind(&display_name)
    .execute(&mut *tx)
    .await?;
    for role in &roles {
        insert_user_role(&mut tx, user_id, role.id, &scope).await?;
    }
    write_audit(
        &mut tx,
        "userctl.create",
        user_id,
        json!({
            "email": email,
            "roles": args.role,
            "organizer": args.organizer,
            "event": args.event,
            "club": args.club,
        }),
    )
    .await?;
    tx.commit().await?;
    eprintln!("Создан {email} (id {user_id}), роли: {}", args.role.join(", "));
    Ok(())
}

async fn grant(pool: PgPool, args: GrantArgs) -> Result<()> {
    let email = normalize_email(&args.email)?;
    if args.role.is_empty() {
        bail!("Укажите --role");
    }
    let user_id = load_user_id(&pool, &email).await?;
    let scope = resolve_scope(&pool, &args.organizer, args.event.as_deref(), args.club.as_deref()).await?;
    let roles = load_roles(&pool, &args.role).await?;
    let mut tx = pool.begin().await?;
    for role in &roles {
        insert_user_role(&mut tx, user_id, role.id, &scope).await?;
    }
    write_audit(
        &mut tx,
        "userctl.grant",
        user_id,
        json!({
            "email": email,
            "roles": args.role,
            "organizer": args.organizer,
            "event": args.event,
            "club": args.club,
        }),
    )
    .await?;
    tx.commit().await?;
    eprintln!("Выданы роли {} пользователю {email}", args.role.join(", "));
    Ok(())
}

async fn set_password(pool: PgPool, args: EmailArgs) -> Result<()> {
    let email = normalize_email(&args.email)?;
    let user_id = load_user_id(&pool, &email).await?;
    let password_hash = hash_password(&read_password_twice()?)?;
    let mut tx = pool.begin().await?;
    sqlx::query(
        r#"
        UPDATE users
        SET password_hash = $2, updated_at = now()
        WHERE id = $1
        "#,
    )
    .bind(user_id)
    .bind(&password_hash)
    .execute(&mut *tx)
    .await?;
    sqlx::query("DELETE FROM sessions WHERE user_id = $1")
        .bind(user_id)
        .execute(&mut *tx)
        .await?;
    write_audit(&mut tx, "userctl.set_password", user_id, json!({ "email": email })).await?;
    tx.commit().await?;
    eprintln!("Пароль сменён, сессии {email} сброшены");
    Ok(())
}

async fn disable(pool: PgPool, args: EmailArgs) -> Result<()> {
    let email = normalize_email(&args.email)?;
    let user_id = load_user_id(&pool, &email).await?;
    let last_owner: bool = sqlx::query_scalar(
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
    .fetch_one(&pool)
    .await?;
    if last_owner {
        bail!("Нельзя отключить последнего активного owner");
    }
    let mut tx = pool.begin().await?;
    sqlx::query(
        r#"
        UPDATE users
        SET status = 'disabled', updated_at = now()
        WHERE id = $1
        "#,
    )
    .bind(user_id)
    .execute(&mut *tx)
    .await?;
    sqlx::query("DELETE FROM sessions WHERE user_id = $1")
        .bind(user_id)
        .execute(&mut *tx)
        .await?;
    write_audit(&mut tx, "userctl.disable", user_id, json!({ "email": email })).await?;
    tx.commit().await?;
    eprintln!("Пользователь {email} отключён");
    Ok(())
}

async fn list(pool: PgPool) -> Result<()> {
    let rows = sqlx::query_as::<_, UserListRow>(
        r#"
        SELECT
            u.id,
            u.email,
            u.status,
            COALESCE(p.display_name, '') AS display_name,
            COALESCE(
                (
                    SELECT string_agg(
                        r.code
                        || CASE
                            WHEN ur.event_id IS NOT NULL THEN ' @event:' || e.slug
                            WHEN ur.club_id IS NOT NULL THEN ' @club:' || c.slug
                            ELSE ' @' || o.slug
                        END,
                        ', '
                        ORDER BY r.code
                    )
                    FROM user_roles ur
                    JOIN roles r ON r.id = ur.role_id
                    JOIN organizers o ON o.id = ur.organizer_id
                    LEFT JOIN events e ON e.id = ur.event_id
                    LEFT JOIN clubs c ON c.id = ur.club_id
                    WHERE ur.user_id = u.id
                ),
                ''
            ) AS roles
        FROM users u
        LEFT JOIN profiles p ON p.user_id = u.id
        ORDER BY u.id
        "#,
    )
    .fetch_all(&pool)
    .await?;
    if rows.is_empty() {
        println!("Пользователей нет. Создайте owner: userctl create --email … --role owner");
        return Ok(());
    }
    for row in rows {
        let name = if row.display_name.is_empty() {
            String::new()
        } else {
            format!(" ({})", row.display_name)
        };
        let roles = if row.roles.is_empty() {
            "без ролей".into()
        } else {
            row.roles
        };
        println!("{}  {}{}  [{}]  {}", row.id, row.email, name, row.status, roles);
    }
    Ok(())
}

#[derive(sqlx::FromRow)]
struct UserListRow {
    id: i64,
    email: String,
    status: String,
    display_name: String,
    roles: String,
}

struct Scope {
    organizer_id: i64,
    event_id: Option<i64>,
    club_id: Option<i64>,
}

struct RoleRow {
    id: i64,
}

async fn resolve_scope(
    pool: &PgPool,
    organizer_slug: &str,
    event_slug: Option<&str>,
    club_slug: Option<&str>,
) -> Result<Scope> {
    if event_slug.is_some() && club_slug.is_some() {
        bail!("Нельзя сразу --event и --club");
    }
    let organizer_id: Option<i64> = sqlx::query_scalar("SELECT id FROM organizers WHERE slug = $1")
        .bind(organizer_slug.trim())
        .fetch_optional(pool)
        .await?;
    let organizer_id = organizer_id.ok_or_else(|| anyhow::anyhow!("Нет организатора '{}'", organizer_slug))?;
    let event_id = if let Some(slug) = event_slug.map(str::trim).filter(|s| !s.is_empty()) {
        let id: Option<i64> = sqlx::query_scalar(
            "SELECT id FROM events WHERE slug = $1 AND organizer_id = $2",
        )
        .bind(slug)
        .bind(organizer_id)
        .fetch_optional(pool)
        .await?;
        Some(id.ok_or_else(|| anyhow::anyhow!("Нет старта '{slug}' у организатора {organizer_slug}"))?)
    } else {
        None
    };
    let club_id = if let Some(slug) = club_slug.map(str::trim).filter(|s| !s.is_empty()) {
        let id: Option<i64> = sqlx::query_scalar("SELECT id FROM clubs WHERE slug = $1")
            .bind(slug)
            .fetch_optional(pool)
            .await?;
        Some(id.ok_or_else(|| anyhow::anyhow!("Нет клуба '{slug}'"))?)
    } else {
        None
    };
    Ok(Scope {
        organizer_id,
        event_id,
        club_id,
    })
}

async fn load_roles(pool: &PgPool, codes: &[String]) -> Result<Vec<RoleRow>> {
    let mut out = Vec::new();
    for raw in codes {
        let code = raw.trim().to_ascii_lowercase();
        let id: Option<i64> = sqlx::query_scalar("SELECT id FROM roles WHERE code = $1")
            .bind(&code)
            .fetch_optional(pool)
            .await?;
        let Some(id) = id else {
            bail!("Неизвестная роль '{raw}'. Смотрите docs/site-admin.md §4.2");
        };
        out.push(RoleRow { id });
    }
    Ok(out)
}

async fn load_user_id(pool: &PgPool, email: &str) -> Result<i64> {
    sqlx::query_scalar("SELECT id FROM users WHERE email = $1")
        .bind(email)
        .fetch_optional(pool)
        .await?
        .ok_or_else(|| anyhow::anyhow!("Нет пользователя {email}"))
}

async fn insert_user_role(
    tx: &mut Transaction<'_, Postgres>,
    user_id: i64,
    role_id: i64,
    scope: &Scope,
) -> Result<()> {
    sqlx::query(
        r#"
        INSERT INTO user_roles (user_id, role_id, organizer_id, event_id, club_id)
        VALUES ($1, $2, $3, $4, $5)
        ON CONFLICT DO NOTHING
        "#,
    )
    .bind(user_id)
    .bind(role_id)
    .bind(scope.organizer_id)
    .bind(scope.event_id)
    .bind(scope.club_id)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

async fn write_audit(
    tx: &mut Transaction<'_, Postgres>,
    action: &str,
    user_id: i64,
    meta: serde_json::Value,
) -> Result<()> {
    sqlx::query(
        r#"
        INSERT INTO audit_log (actor_user_id, action, entity_type, entity_id, meta)
        VALUES (NULL, $1, 'user', $2, $3)
        "#,
    )
    .bind(action)
    .bind(user_id.to_string())
    .bind(meta)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

fn read_password_twice() -> Result<String> {
    if !io::stdin().is_terminal() {
        bail!("userctl спрашивает пароль с терминала. Не запускайте без TTY и не передавайте пароль в argv");
    }
    let first = rpassword::prompt_password("Пароль: ").context("не удалось прочитать пароль")?;
    let second = rpassword::prompt_password("Ещё раз: ").context("не удалось прочитать пароль")?;
    if first != second {
        bail!("Пароли не совпали");
    }
    Ok(first)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn local_urls() {
        assert!(database_is_local("postgres://rogain:rogain@127.0.0.1:5432/rogain"));
        assert!(database_is_local("postgres://rogain@localhost/rogain"));
        assert!(database_is_local("postgres://rogain@localhost:5432/rogain"));
        assert!(database_is_local("postgres:///rogain"));
        assert!(database_is_local("postgres://malahit@/rogain"));
        assert!(!database_is_local("postgres://rogain:x@db.example:5432/rogain"));
        assert!(!database_is_local(""));
    }
}
