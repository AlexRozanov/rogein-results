use std::collections::HashMap;
use std::path::Path;

use anyhow::{bail, Context};
use base64::Engine;
use sqlx::{PgPool, Postgres, Transaction};

use crate::map_image::{prepare_web_map, scale_meters_per_pixel};
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
    #[serde(default)]
    pub map_width: Option<i32>,
    #[serde(default)]
    pub map_height: Option<i32>,
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
        INSERT INTO events (slug, title, competition_date, status, map_points, meters_per_pixel, sport_kind, start_cp, finish_cp, revision)
        VALUES ($1, $2, $3, 'published', $4, $5, $6, $7, $8, 1)
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

    insert_event_rows(&mut tx, event_id, &payload).await?;

    if let Some(map) = payload.map {
        save_map_from_base64(&mut tx, media_dir, slug, event_id, map).await?;
    } else {
        copy_pack_map_to_event(&mut tx, media_dir, slug, event_id).await?;
    }

    complete_matching_upcoming(&mut tx, slug, event_id).await?;

    tx.commit().await?;
    Ok(())
}

pub async fn publish_live_event(pool: &PgPool, slug: &str, payload: PublishPayload) -> anyhow::Result<i64> {
    if payload.title.trim().is_empty() {
        bail!("title is required");
    }

    let mut tx = pool.begin().await?;
    let existing = sqlx::query_as::<_, (i64, String)>(
        "SELECT id, status FROM events WHERE slug = $1",
    )
    .bind(slug)
    .fetch_optional(&mut *tx)
    .await?;

    let event_id = match existing {
        Some((_, status)) if status == "published" => {
            bail!("ALREADY_PUBLISHED");
        }
        Some((id, _)) => {
            sqlx::query("DELETE FROM award_groups WHERE event_id = $1")
                .bind(id)
                .execute(&mut *tx)
                .await?;
            sqlx::query("DELETE FROM participants WHERE event_id = $1")
                .bind(id)
                .execute(&mut *tx)
                .await?;
            sqlx::query(
                r#"
                UPDATE events
                SET title = $2,
                    competition_date = $3,
                    sport_kind = $4,
                    start_cp = $5,
                    finish_cp = $6,
                    map_points = CASE
                        WHEN jsonb_typeof($7::jsonb) = 'array' AND jsonb_array_length($7::jsonb) > 0
                        THEN $7::jsonb
                        ELSE map_points
                    END,
                    meters_per_pixel = COALESCE($8, meters_per_pixel),
                    map_width = COALESCE($9, map_width),
                    map_height = COALESCE($10, map_height),
                    status = 'live',
                    published_at = now(),
                    revision = revision + 1
                WHERE id = $1
                "#,
            )
            .bind(id)
            .bind(payload.title.trim())
            .bind(payload.competition_date)
            .bind(normalize_sport_kind(&payload.sport_kind))
            .bind(payload.start_cp)
            .bind(payload.finish_cp)
            .bind(serde_json::to_value(&payload.map_points)?)
            .bind(payload.meters_per_pixel)
            .bind(payload.map_width.filter(|v| *v > 0))
            .bind(payload.map_height.filter(|v| *v > 0))
            .execute(&mut *tx)
            .await?;
            id
        }
        None => {
            sqlx::query_scalar(
                r#"
                INSERT INTO events (
                    slug, title, competition_date, status, map_points, meters_per_pixel,
                    sport_kind, start_cp, finish_cp, revision, map_width, map_height
                )
                VALUES ($1, $2, $3, 'live', $7, $8, $4, $5, $6, 1, $9, $10)
                RETURNING id
                "#,
            )
            .bind(slug)
            .bind(payload.title.trim())
            .bind(payload.competition_date)
            .bind(normalize_sport_kind(&payload.sport_kind))
            .bind(payload.start_cp)
            .bind(payload.finish_cp)
            .bind(serde_json::to_value(&payload.map_points)?)
            .bind(payload.meters_per_pixel)
            .bind(payload.map_width.filter(|v| *v > 0))
            .bind(payload.map_height.filter(|v| *v > 0))
            .fetch_one(&mut *tx)
            .await?
        }
    };

    insert_event_rows(&mut tx, event_id, &payload).await?;
    let revision: i64 = sqlx::query_scalar("SELECT revision FROM events WHERE id = $1")
        .bind(event_id)
        .fetch_one(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(revision)
}

async fn insert_event_rows(
    tx: &mut Transaction<'_, Postgres>,
    event_id: i64,
    payload: &PublishPayload,
) -> anyhow::Result<()> {
    let mut group_ids: HashMap<i64, i64> = HashMap::new();
    for (idx, group) in payload.award_groups.iter().enumerate() {
        if group.source_id <= 0 {
            bail!("award group source_id must be positive");
        }
        if group_ids.contains_key(&group.source_id) {
            bail!("duplicate award group source_id {}", group.source_id);
        }
        let gender = group.gender_mode.as_deref().unwrap_or("any");
        let course_cps = serde_json::to_value(&group.course_cps)?;
        let leg_meters = serde_json::to_value(&group.leg_meters)?;
        let id: i64 = sqlx::query_scalar(
            r#"
            INSERT INTO award_groups (
                event_id, source_id, name, gender_mode, min_age, max_age, sort_order,
                course_cps, length_meters, climb_meters, leg_meters
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
            RETURNING id
            "#,
        )
        .bind(event_id)
        .bind(group.source_id)
        .bind(group.name.trim())
        .bind(gender)
        .bind(group.min_age)
        .bind(group.max_age)
        .bind(group.sort_order.unwrap_or(idx as i32))
        .bind(course_cps)
        .bind(group.length_meters.filter(|v| *v > 0))
        .bind(group.climb_meters.filter(|v| *v >= 0))
        .bind(leg_meters)
        .fetch_one(&mut **tx)
        .await?;
        group_ids.insert(group.source_id, id);
    }

    let mut participant_ids: HashMap<i64, i64> = HashMap::new();
    for person in &payload.participants {
        if person.source_id <= 0 {
            bail!("participant source_id must be positive");
        }
        if participant_ids.contains_key(&person.source_id) {
            bail!(
                "duplicate participant source_id {} — один чип на нескольких дистанциях публикуйте как разные результаты",
                person.source_id
            );
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
        .fetch_one(&mut **tx)
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
        .execute(&mut **tx)
        .await?;
    }
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

async fn copy_pack_map_to_event(
    tx: &mut Transaction<'_, Postgres>,
    media_dir: &Path,
    slug: &str,
    event_id: i64,
) -> anyhow::Result<()> {
    let pack: Option<(Option<String>, Option<String>, Option<i32>, Option<i32>)> = sqlx::query_as(
        "SELECT map_file_name, map_mime, map_width, map_height FROM event_packs WHERE slug = $1",
    )
    .bind(slug)
    .fetch_optional(&mut **tx)
    .await?;
    let Some((name, mime, width, height)) = pack else {
        return Ok(());
    };
    let Some(name) = name.map(|s| s.trim().to_string()).filter(|s| !s.is_empty()) else {
        return Ok(());
    };
    let file_name = std::path::Path::new(&name)
        .file_name()
        .and_then(|n| n.to_str())
        .filter(|n| !n.is_empty())
        .unwrap_or("map.bin")
        .to_string();
    let src = media_dir.join("packs").join(slug).join(&file_name);
    if !src.is_file() {
        return Ok(());
    }
    let dest_dir = media_dir.join("events").join(slug);
    std::fs::create_dir_all(&dest_dir)?;
    std::fs::copy(&src, dest_dir.join(&file_name))?;
    sqlx::query(
        r#"
        UPDATE events
        SET map_file_name = $1,
            map_mime = COALESCE($2, map_mime),
            map_width = COALESCE(map_width, $3),
            map_height = COALESCE(map_height, $4)
        WHERE id = $5
        "#,
    )
    .bind(&file_name)
    .bind(mime)
    .bind(width.filter(|v| *v > 0))
    .bind(height.filter(|v| *v > 0))
    .bind(event_id)
    .execute(&mut **tx)
    .await?;
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
    let prepared = prepare_web_map(bytes, file_name, mime_type);
    let out_width = if prepared.width > 0 {
        prepared.width
    } else {
        width.filter(|v| *v > 0).unwrap_or(0)
    };
    let out_height = if prepared.height > 0 {
        prepared.height
    } else {
        height.filter(|v| *v > 0).unwrap_or(0)
    };
    let current = sqlx::query_as::<_, (Option<String>, Option<i32>, Option<f64>)>(
        "SELECT map_file_name, map_width, meters_per_pixel FROM events WHERE id = $1",
    )
    .bind(event_id)
    .fetch_one(&mut **tx)
    .await?;
    let from_width = current
        .1
        .filter(|v| *v > 0)
        .or_else(|| (prepared.source_width > 0).then_some(prepared.source_width))
        .or(width.filter(|v| *v > 0));
    let meters_per_pixel = match (current.2, from_width, out_width) {
        (Some(mpp), Some(from), to) if to > 0 => Some(scale_meters_per_pixel(mpp, from, to)),
        (mpp, _, _) => mpp,
    };
    let dir = media_dir.join("events").join(slug);
    std::fs::create_dir_all(&dir)?;
    let file_name = sanitize_file_name(&prepared.file_name);
    if let Some(old_name) = current.0.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        if old_name != file_name {
            let _ = std::fs::remove_file(dir.join(old_name));
        }
    }
    std::fs::write(dir.join(&file_name), &prepared.bytes)?;
    sqlx::query(
        r#"
        UPDATE events
        SET map_file_name = $1,
            map_mime = $2,
            map_width = $3,
            map_height = $4,
            meters_per_pixel = COALESCE($5, meters_per_pixel)
        WHERE id = $6
        "#,
    )
    .bind(&file_name)
    .bind(&prepared.mime)
    .bind((out_width > 0).then_some(out_width))
    .bind((out_height > 0).then_some(out_height))
    .bind(meters_per_pixel)
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
