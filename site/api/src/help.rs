//! Справка для судей в личном кабинете (чтение; правка — на следующих этапах).

use std::sync::Arc;

use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::Json;
use serde::Serialize;
use sqlx::FromRow;

use crate::auth::AuthUser;
use crate::{internal, AppState};

#[derive(Debug, Clone, FromRow, Serialize)]
pub struct HelpArticleListItem {
    pub slug: String,
    pub section: String,
    pub title: String,
    pub summary: String,
    pub sort_order: i32,
    pub updated_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, FromRow, Serialize)]
pub struct HelpArticle {
    pub slug: String,
    pub section: String,
    pub title: String,
    pub summary: String,
    pub body_md: String,
    pub sort_order: i32,
    pub updated_at: chrono::DateTime<chrono::Utc>,
}

struct SeedArticle {
    slug: &'static str,
    section: &'static str,
    title: &'static str,
    summary: &'static str,
    sort_order: i32,
    body: &'static str,
}

const SEED: &[SeedArticle] = &[
    SeedArticle {
        slug: "install",
        section: "start",
        title: "Установка приложения",
        summary: "APK из кабинета, обновление поверх, без Google Play.",
        sort_order: 10,
        body: include_str!("../help-seed/install.md"),
    },
    SeedArticle {
        slug: "dictionary",
        section: "start",
        title: "Словарь",
        summary: "Хост, клиент, slug, пароль обмена, LTE, пакет.",
        sort_order: 20,
        body: include_str!("../help-seed/dictionary.md"),
    },
    SeedArticle {
        slug: "start-day",
        section: "day",
        title: "Старт дня",
        summary: "Публикация, взять старт с сайта, протокол и дистанции.",
        sort_order: 30,
        body: include_str!("../help-seed/start-day.md"),
    },
    SeedArticle {
        slug: "field-sync",
        section: "field",
        title: "Обмен между телефонами",
        summary: "Локальная сеть, роутер, облако, online и чеклист.",
        sort_order: 40,
        body: include_str!("../help-seed/field-sync.md"),
    },
    SeedArticle {
        slug: "online",
        section: "online",
        title: "Online и пакет на сайт",
        summary: "Токен, slug, live и пакет для телефона.",
        sort_order: 50,
        body: include_str!("../help-seed/online.md"),
    },
    SeedArticle {
        slug: "faq",
        section: "faq",
        title: "Частые вопросы",
        summary: "Связь, пароль, slug, два хоста, пакет, установка.",
        sort_order: 60,
        body: include_str!("../help-seed/faq.md"),
    },
];

/// Вставляет стартовые статьи, если slug ещё нет (не перезаписывает правки).
pub async fn ensure_seed(pool: &sqlx::PgPool) -> anyhow::Result<()> {
    for article in SEED {
        sqlx::query(
            r#"
            INSERT INTO help_articles (slug, section, title, summary, body_md, sort_order, published)
            VALUES ($1, $2, $3, $4, $5, $6, true)
            ON CONFLICT (slug) DO NOTHING
            "#,
        )
        .bind(article.slug)
        .bind(article.section)
        .bind(article.title)
        .bind(article.summary)
        .bind(article.body)
        .bind(article.sort_order)
        .execute(pool)
        .await?;
    }
    Ok(())
}

pub async fn list_articles(
    State(state): State<Arc<AppState>>,
    _user: AuthUser,
) -> Result<Json<Vec<HelpArticleListItem>>, (StatusCode, String)> {
    let rows = sqlx::query_as::<_, HelpArticleListItem>(
        r#"
        SELECT slug, section, title, summary, sort_order, updated_at
        FROM help_articles
        WHERE published = true
        ORDER BY sort_order ASC, title ASC
        "#,
    )
    .fetch_all(&state.pool)
    .await
    .map_err(internal)?;
    Ok(Json(rows))
}

pub async fn get_article(
    State(state): State<Arc<AppState>>,
    _user: AuthUser,
    Path(slug): Path<String>,
) -> Result<Json<HelpArticle>, (StatusCode, String)> {
    let slug = slug.trim().to_lowercase();
    if slug.is_empty()
        || !slug
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
    {
        return Err((StatusCode::BAD_REQUEST, "Некорректный slug статьи".into()));
    }
    let row = sqlx::query_as::<_, HelpArticle>(
        r#"
        SELECT slug, section, title, summary, body_md, sort_order, updated_at
        FROM help_articles
        WHERE slug = $1 AND published = true
        "#,
    )
    .bind(&slug)
    .fetch_optional(&state.pool)
    .await
    .map_err(internal)?
    .ok_or((StatusCode::NOT_FOUND, "Статья не найдена".into()))?;
    Ok(Json(row))
}
