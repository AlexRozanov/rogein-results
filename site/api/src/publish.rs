use std::collections::HashMap;
use std::path::Path;

use anyhow::{bail, Context};
use base64::Engine;
use sqlx::{PgPool, Postgres, Transaction};

use crate::models::{PublishAwardGroup, PublishMapPoint, PublishParticipant, PublishResult};

#[derive(Debug, serde::Deserialize)]
pub struct PublishPayload {
    pub title: String,
    pub competition_date: Option<chrono::NaiveDate>,
    pub award_groups: Vec<PublishAwardGroup>,
    pub participants: Vec<PublishParticipant>,
    pub results: Vec<PublishResult>,
    pub map: Option<crate::models::PublishMap>,
    #[serde(default)]
    pub map_points: Vec<PublishMapPoint>,
    #[serde(default)]
    pub meters_per_pixel: Option<f64>,
    #[serde(default = "default_sport_kind")]
    pub sport_kind: String,
    #[serde(default)]
    pub start_cp: Option<i32>,
    #[serde(default)]
    pub finish_cp: Option<i32>,
}

fn default_sport_kind() -> String {
    "rogaine".to_string()
}

fn normalize_sport_kind(raw: &str) -> String {
    if raw.trim().eq_ignore_ascii_case("orient") {
        "orient".to_string()
    } else {
        "rogaine".to_string()
    }
}

pub async fn publish_event(
    pool: &PgPool,
    media_dir: &Path,
    slug: &str,
    payload: PublishPayload,
) -> anyhow::Result<()> {
    if payload.title.trim().is_empty() {
        bail!("title is required");
    }
    if payload.participants.is_empty() {
        bail!("participants must not be empty");
    }

    let mut tx = pool.begin().await?;
    sqlx::query("DELETE FROM events WHERE slug = $1")
        .bind(slug)
        .execute(&mut *tx)
        .await?;
    let _ = std::fs::remove_dir_all(media_dir.join("events").join(slug));

    let map_points = serde_json::to_value(&payload.map_points)?;
    let event_id: i64 = sqlx::query_scalar(
        r#"
        INSERT INTO events (slug, title, competition_date, status, map_points, meters_per_pixel, sport_kind, start_cp, finish_cp)
        VALUES ($1, $2, $3, 'published', $4, $5, $6, $7, $8)
        RETURNING id
        "#,
    )
    .bind(slug)
    .bind(payload.title.trim())
    .bind(payload.competition_date)
    .bind(map_points)
    .bind(payload.meters_per_pixel)
    .bind(normalize_sport_kind(&payload.sport_kind))
    .bind(payload.start_cp)
    .bind(payload.finish_cp)
    .fetch_one(&mut *tx)
    .await?;

    let mut group_ids: HashMap<i64, i64> = HashMap::new();
    for (idx, group) in payload.award_groups.iter().enumerate() {
        if group.source_id <= 0 {
            bail!("award group source_id must be positive");
        }
        let gender = group.gender_mode.as_deref().unwrap_or("any");
        let course_cps = serde_json::to_value(&group.course_cps)?;
        let id: i64 = sqlx::query_scalar(
            r#"
            INSERT INTO award_groups (event_id, source_id, name, gender_mode, min_age, sort_order, course_cps)
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            RETURNING id
            "#,
        )
        .bind(event_id)
        .bind(group.source_id)
        .bind(group.name.trim())
        .bind(gender)
        .bind(group.min_age)
        .bind(group.sort_order.unwrap_or(idx as i32))
        .bind(course_cps)
        .fetch_one(&mut *tx)
        .await?;
        group_ids.insert(group.source_id, id);
    }

    let mut participant_ids: HashMap<i64, i64> = HashMap::new();
    for person in &payload.participants {
        if person.source_id <= 0 {
            bail!("participant source_id must be positive");
        }
        if person.name.trim().is_empty() {
            bail!("participant name is required");
        }
        let marks = serde_json::to_value(person.marks.as_deref().unwrap_or(&[]))?;
        let path = serde_json::to_value(person.path.as_deref().unwrap_or(&[]))?;
        let added_cps = serde_json::to_value(&person.added_cps)?;
        let id: i64 = sqlx::query_scalar(
            r#"
            INSERT INTO participants (
                event_id, source_id, bib, chip_physical, chip_logical, name,
                gender, birth_date, age, team_id, format_name, marks, path, distance_m, added_cps
            ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15)
            RETURNING id
            "#,
        )
        .bind(event_id)
        .bind(person.source_id)
        .bind(person.bib.trim())
        .bind(person.chip_physical.as_deref())
        .bind(person.chip_logical.as_deref())
        .bind(person.name.trim())
        .bind(person.gender.as_deref())
        .bind(person.birth_date)
        .bind(person.age)
        .bind(person.team_id)
        .bind(person.format_name.as_deref())
        .bind(marks)
        .bind(path)
        .bind(person.distance_m)
        .bind(added_cps)
        .fetch_one(&mut *tx)
        .await?;
        participant_ids.insert(person.source_id, id);
    }

    for row in &payload.results {
        let participant_id = participant_ids
            .get(&row.participant_source_id)
            .copied()
            .with_context(|| {
                format!(
                    "result refers to unknown participant source_id {}",
                    row.participant_source_id
                )
            })?;
        let award_group_id = group_ids
            .get(&row.award_group_source_id)
            .copied()
            .with_context(|| {
                format!(
                    "result refers to unknown award group source_id {}",
                    row.award_group_source_id
                )
            })?;
        let diagnostics = serde_json::to_value(&row.diagnostics)?;
        sqlx::query(
            r#"
            INSERT INTO results (
                event_id, participant_id, award_group_id, place,
                points_raw, penalty_points, points_final, elapsed_seconds, status, diagnostics
            ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)
            "#,
        )
        .bind(event_id)
        .bind(participant_id)
        .bind(award_group_id)
        .bind(row.place)
        .bind(row.points_raw.unwrap_or(0))
        .bind(row.penalty_points.unwrap_or(0))
        .bind(row.points_final.unwrap_or(0))
        .bind(row.elapsed_seconds.unwrap_or(0))
        .bind(row.status.as_deref().unwrap_or("OK"))
        .bind(diagnostics)
        .execute(&mut *tx)
        .await?;
    }

    if let Some(map) = payload.map {
        save_map_from_base64(&mut tx, media_dir, slug, event_id, map).await?;
    }

    complete_matching_upcoming(&mut tx, slug, event_id).await?;

    tx.commit().await?;
    Ok(())
}

/// Same slug as the announcement: calendar drops it, results list picks it up.
async fn complete_matching_upcoming(
    tx: &mut Transaction<'_, Postgres>,
    slug: &str,
    event_id: i64,
) -> anyhow::Result<()> {
    sqlx::query(
        r#"
        UPDATE upcoming_events
        SET status = 'completed',
            published_event_id = $1,
            completed_at = now()
        WHERE slug = $2
          AND status <> 'cancelled'
        "#,
    )
    .bind(event_id)
    .bind(slug)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

pub async fn publish_map(
    pool: &PgPool,
    media_dir: &Path,
    slug: &str,
    bytes: &[u8],
    file_name: &str,
    mime_type: Option<&str>,
    width: Option<i32>,
    height: Option<i32>,
) -> anyhow::Result<()> {
    if bytes.is_empty() {
        bail!("map payload is empty");
    }
    let mut tx = pool.begin().await?;
    let event_id: i64 = sqlx::query_scalar("SELECT id FROM events WHERE slug = $1")
        .bind(slug)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(|| anyhow::anyhow!("event not found; publish JSON first"))?;
    save_map_bytes(
        &mut tx,
        media_dir,
        slug,
        event_id,
        bytes,
        file_name,
        mime_type,
        width,
        height,
    )
    .await?;
    tx.commit().await?;
    Ok(())
}

async fn save_map_from_base64(
    tx: &mut Transaction<'_, Postgres>,
    media_dir: &Path,
    slug: &str,
    event_id: i64,
    map: crate::models::PublishMap,
) -> anyhow::Result<()> {
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(map.data_base64.trim())
        .context("invalid map base64")?;
    save_map_bytes(
        tx,
        media_dir,
        slug,
        event_id,
        &bytes,
        &map.file_name,
        map.mime_type.as_deref(),
        map.width,
        map.height,
    )
    .await
}

async fn save_map_bytes(
    tx: &mut Transaction<'_, Postgres>,
    media_dir: &Path,
    slug: &str,
    event_id: i64,
    bytes: &[u8],
    file_name: &str,
    mime_type: Option<&str>,
    width: Option<i32>,
    height: Option<i32>,
) -> anyhow::Result<()> {
    if bytes.is_empty() {
        bail!("map payload is empty");
    }
    let file_name = sanitize_file_name(file_name);
    let dir = media_dir.join("events").join(slug);
    std::fs::create_dir_all(&dir)?;
    let path = dir.join(&file_name);
    std::fs::write(&path, bytes)?;
    let mime = mime_type
        .map(str::trim)
        .filter(|v| !v.is_empty())
        .unwrap_or("application/octet-stream");
    sqlx::query(
        r#"
        UPDATE events
        SET map_file_name = $1, map_mime = $2, map_width = $3, map_height = $4
        WHERE id = $5
        "#,
    )
    .bind(&file_name)
    .bind(mime)
    .bind(width)
    .bind(height)
    .bind(event_id)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

fn sanitize_file_name(raw: &str) -> String {
    let name = std::path::Path::new(raw)
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("map.bin");
    let cleaned: String = name
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '.' || c == '-' || c == '_' {
                c
            } else {
                '_'
            }
        })
        .collect();
    if cleaned.is_empty() {
        "map.bin".into()
    } else {
        cleaned
    }
}
