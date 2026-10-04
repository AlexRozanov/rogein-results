use std::collections::BTreeMap;
use std::path::Path;

use chrono::{Duration, NaiveDate, NaiveTime};
use serde::Serialize;

use crate::site_publish::{
    send_event_payload, PublishAwardGroup, PublishMark, PublishParticipant, PublishPayload,
    PublishResultRow, SiteConnection, SitePublishResult,
};

#[derive(Debug, Clone, Serialize)]
pub struct ArchiveOrientPreview {
    pub row_count: usize,
    pub ok_count: usize,
    pub dsq_count: usize,
    pub courses: Vec<ArchiveCoursePreview>,
    pub suggested_slug: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct ArchiveCoursePreview {
    pub name: String,
    pub count: usize,
}

#[derive(Debug, Clone)]
struct ArchiveRow {
    name: String,
    course: String,
    elapsed_seconds: i32,
    status: String,
    place: Option<i32>,
    punches: Vec<(i32, NaiveTime)>,
}

pub fn preview_csv(csv_content: &str, competition_date: &str) -> Result<ArchiveOrientPreview, String> {
    let rows = parse_csv(csv_content)?;
    Ok(preview_from_rows(&rows, competition_date))
}

pub fn publish_csv(
    app_data_dir: &Path,
    csv_content: &str,
    title: &str,
    slug: &str,
    competition_date: &str,
    api_base_url: &str,
    publish_token: &str,
) -> Result<SitePublishResult, String> {
    let title = title.trim();
    if title.is_empty() {
        return Err("Укажите название старта.".into());
    }
    let date = parse_date(competition_date)?;
    let rows = parse_csv(csv_content)?;
    if rows.is_empty() {
        return Err("В файле нет участников.".into());
    }
    let payload = build_payload(title, date, &rows);
    let connection = SiteConnection {
        api_base_url: api_base_url.to_string(),
        publish_token: publish_token.to_string(),
    };
    crate::site_publish::save_connection(app_data_dir, &connection)?;
    send_event_payload(&connection, slug, &payload)
}

fn preview_from_rows(rows: &[ArchiveRow], competition_date: &str) -> ArchiveOrientPreview {
    let mut courses: BTreeMap<String, usize> = BTreeMap::new();
    let mut ok_count = 0_usize;
    let mut dsq_count = 0_usize;
    for row in rows {
        *courses.entry(row.course.clone()).or_insert(0) += 1;
        if row.status == "OK" {
            ok_count += 1;
        } else {
            dsq_count += 1;
        }
    }
    ArchiveOrientPreview {
        row_count: rows.len(),
        ok_count,
        dsq_count,
        courses: courses
            .into_iter()
            .map(|(name, count)| ArchiveCoursePreview { name, count })
            .collect(),
        suggested_slug: suggested_slug(competition_date),
    }
}

pub fn suggested_slug(competition_date: &str) -> String {
    match parse_date(competition_date) {
        Ok(date) => format!("orient-{}", date.format("%Y-%m-%d")),
        Err(_) => String::new(),
    }
}

fn parse_date(raw: &str) -> Result<NaiveDate, String> {
    let raw = raw.trim();
    NaiveDate::parse_from_str(raw, "%Y-%m-%d")
        .map_err(|_| "Укажите дату старта в формате ГГГГ-ММ-ДД.".to_string())
}

fn parse_csv(content: &str) -> Result<Vec<ArchiveRow>, String> {
    let content = content.trim_start_matches('\u{feff}');
    let mut reader = csv::ReaderBuilder::new()
        .delimiter(b';')
        .has_headers(true)
        .flexible(true)
        .from_reader(content.as_bytes());
    let headers = reader
        .headers()
        .map_err(|e| format!("не удалось прочитать заголовок CSV: {e}"))?
        .clone();
    let place_idx = find_header(&headers, &["place", "место"])?;
    let name_idx = find_header(&headers, &["team name", "team_name", "name", "имя"])?;
    let time_idx = find_header(&headers, &["time", "время"])?;
    let course_idx = find_header(&headers, &["course", "дистанция"])?;
    let result_idx = find_header(&headers, &["result", "результат"])?;
    let punch_from = result_idx + 1;

    let mut rows = Vec::new();
    for (line_no, rec) in reader.records().enumerate() {
        let rec = rec.map_err(|e| format!("строка {}: {e}", line_no + 2))?;
        let name = field(&rec, name_idx);
        if name.is_empty() {
            continue;
        }
        let course = field(&rec, course_idx);
        if course.is_empty() {
            return Err(format!("строка {}: не указана дистанция", line_no + 2));
        }
        let status = map_status(&field(&rec, result_idx))?;
        let elapsed_seconds = parse_elapsed(&field(&rec, time_idx)).unwrap_or(0);
        let place = if status == "OK" {
            parse_place(&field(&rec, place_idx))
        } else {
            None
        };
        let punch_cells: Vec<String> = (punch_from..rec.len()).map(|i| field(&rec, i)).collect();
        rows.push(ArchiveRow {
            name,
            course,
            elapsed_seconds,
            status,
            place,
            punches: extract_punches(&punch_cells),
        });
    }
    if rows.is_empty() {
        return Err("В файле нет строк с участниками.".into());
    }
    Ok(rows)
}

fn find_header(headers: &csv::StringRecord, aliases: &[&str]) -> Result<usize, String> {
    for (idx, raw) in headers.iter().enumerate() {
        let key = normalize_header(raw);
        if aliases.iter().any(|alias| normalize_header(alias) == key) {
            return Ok(idx);
        }
    }
    Err(format!(
        "В заголовке нет колонки «{}».",
        aliases.first().unwrap_or(&"")
    ))
}

fn normalize_header(raw: &str) -> String {
    raw.trim()
        .trim_start_matches('\u{feff}')
        .to_lowercase()
        .replace('_', " ")
}

fn field(rec: &csv::StringRecord, idx: usize) -> String {
    rec.get(idx).unwrap_or("").trim().to_string()
}

fn map_status(raw: &str) -> Result<String, String> {
    let key = raw.trim().to_ascii_lowercase();
    if key.is_empty() || key == "ok" {
        return Ok("OK".into());
    }
    if key == "dsq" || key.starts_with("дискв") {
        return Ok("Дисквалификация".into());
    }
    Err(format!("неизвестный результат «{raw}»"))
}

fn parse_place(raw: &str) -> Option<i32> {
    let raw = raw.trim();
    if raw.is_empty() {
        return None;
    }
    raw.parse::<i32>().ok().filter(|v| *v > 0)
}

fn parse_elapsed(raw: &str) -> Option<i32> {
    let parts = split_hms(raw)?;
    match parts.as_slice() {
        [minutes, seconds] => Some(minutes * 60 + seconds),
        [hours, minutes, seconds] => Some(hours * 3600 + minutes * 60 + seconds),
        _ => None,
    }
}

fn split_hms(raw: &str) -> Option<Vec<i32>> {
    let raw = raw.trim();
    if raw.is_empty() || !raw.contains(':') {
        return None;
    }
    let parts: Vec<i32> = raw
        .split(':')
        .map(|p| p.trim().parse::<i32>().ok())
        .collect::<Option<Vec<_>>>()?;
    if parts.iter().any(|v| *v < 0) {
        return None;
    }
    Some(parts)
}

fn parse_cp(raw: &str) -> Option<i32> {
    let raw = raw.trim();
    if raw.is_empty() || raw.contains(':') {
        return None;
    }
    raw.parse::<i32>().ok().filter(|v| (1..=399).contains(v))
}

fn is_split_duration(raw: &str) -> bool {
    let Some(parts) = split_hms(raw) else {
        return false;
    };
    matches!(parts.as_slice(), [0, minutes, seconds] if *minutes <= 59 && *seconds <= 59)
}

fn parse_clock(raw: &str) -> Option<NaiveTime> {
    if is_split_duration(raw) {
        return None;
    }
    let parts = split_hms(raw)?;
    match parts.as_slice() {
        [hours, minutes, seconds]
            if (0..=23).contains(hours) && (0..=59).contains(minutes) && (0..=59).contains(seconds) =>
        {
            NaiveTime::from_hms_opt(*hours as u32, *minutes as u32, *seconds as u32)
        }
        _ => None,
    }
}

fn extract_punches(cells: &[String]) -> Vec<(i32, NaiveTime)> {
    let tokens: Vec<&str> = cells
        .iter()
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < tokens.len() {
        let Some(cp) = parse_cp(tokens[i]) else {
            i += 1;
            continue;
        };
        let mut j = i + 1;
        let mut clock = None;
        while j < tokens.len() && parse_cp(tokens[j]).is_none() {
            if let Some(time) = parse_clock(tokens[j]) {
                clock = Some(time);
                break;
            }
            j += 1;
        }
        if let Some(time) = clock {
            out.push((cp, time));
        }
        i += 1;
    }
    out
}

fn build_payload(title: &str, date: NaiveDate, rows: &[ArchiveRow]) -> PublishPayload {
    let mut course_ids: BTreeMap<String, i64> = BTreeMap::new();
    for row in rows {
        if !course_ids.contains_key(&row.course) {
            let id = (course_ids.len() as i64) + 1;
            course_ids.insert(row.course.clone(), id);
        }
    }
    let award_groups = course_ids
        .iter()
        .map(|(name, source_id)| PublishAwardGroup {
            source_id: *source_id,
            name: name.clone(),
            gender_mode: "any".into(),
            min_age: None,
            sort_order: (*source_id as i32) - 1,
            course_cps: Vec::new(),
        })
        .collect();

    let mut participants = Vec::with_capacity(rows.len());
    let mut results = Vec::with_capacity(rows.len());
    for (idx, row) in rows.iter().enumerate() {
        let source_id = (idx as i64) + 1;
        participants.push(PublishParticipant {
            source_id,
            bib: String::new(),
            chip_physical: None,
            chip_logical: None,
            name: row.name.clone(),
            gender: None,
            birth_date: None,
            age: None,
            team_id: None,
            format_name: Some(row.course.clone()),
            marks: stamp_marks(date, &row.punches),
            path: Vec::new(),
            distance_m: None,
        });
        results.push(PublishResultRow {
            participant_source_id: source_id,
            award_group_source_id: course_ids[&row.course],
            place: row.place,
            points_raw: 0,
            penalty_points: 0,
            points_final: 0,
            elapsed_seconds: row.elapsed_seconds,
            status: row.status.clone(),
            diagnostics: Vec::new(),
        });
    }

    PublishPayload {
        title: title.to_string(),
        competition_date: Some(date),
        award_groups,
        participants,
        results,
        map_points: Vec::new(),
        meters_per_pixel: None,
        sport_kind: "orient".into(),
        start_cp: Some(241),
        finish_cp: Some(240),
    }
}

fn stamp_marks(date: NaiveDate, punches: &[(i32, NaiveTime)]) -> Vec<PublishMark> {
    let mut day = date;
    let mut prev: Option<NaiveTime> = None;
    let mut out = Vec::with_capacity(punches.len());
    for (idx, (cp, clock)) in punches.iter().enumerate() {
        if let Some(previous) = prev {
            if *clock < previous {
                day = day.checked_add_signed(Duration::days(1)).unwrap_or(day);
            }
        }
        prev = Some(*clock);
        out.push(PublishMark {
            seq: (idx as i32) + 1,
            cp_number: *cp,
            mark_time: format!("{}T{}", day.format("%Y-%m-%d"), clock.format("%H:%M:%S")),
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const USADBA: &str = "place;team name;time;course;result;start st id;Start time;st;time2;;etc...\n\
1;Ейбогин Тимофей;18:41;D1;OK;241;10:50:53;36;10:51:25;0:00:31;50;10:52:39;10:52:07;240;11:09:34;0:00:12\n\
13;Евдокимова Полина;25:30;D1;OK\n\
18;Сгибнева Дарья;27:49;D1;OK;241;10:08:06;36;10:08:25;0:00:19\n";

    const FILI: &str = "place;team name;time;course;result;start st id;Start time;st2;;time2;etc...\n\
1;Матвиенко Савелий;35:53;D1;OK;241;12:55:20;47;0:01:05;12:56:25;49;0:00:47;12:57:12;240;0:01:10;13:31:13\n\
26;Соловьев Филипп;1:00:19;D1;OK;241;12:12:03;47;0:01:40;12:13:43;240;0:01:32;13:12:22\n";

    const DSQ: &str = "place;team name;time;course;result\n\
;Иванов Иван;12:00;D2;DSQ\n";

    #[test]
    fn parses_usadba_clock_then_split() {
        let rows = parse_csv(USADBA).unwrap();
        assert_eq!(rows.len(), 3);
        assert_eq!(rows[0].name, "Ейбогин Тимофей");
        assert_eq!(rows[0].elapsed_seconds, 18 * 60 + 41);
        assert_eq!(rows[0].place, Some(1));
        assert_eq!(
            rows[0]
                .punches
                .iter()
                .map(|(cp, t)| (*cp, t.format("%H:%M:%S").to_string()))
                .collect::<Vec<_>>(),
            vec![
                (241, "10:50:53".into()),
                (36, "10:51:25".into()),
                (50, "10:52:39".into()),
                (240, "11:09:34".into()),
            ]
        );
        assert!(rows[1].punches.is_empty());
        assert_eq!(rows[2].place, Some(18));
    }

    #[test]
    fn parses_fili_split_then_clock() {
        let rows = parse_csv(FILI).unwrap();
        assert_eq!(rows[0].elapsed_seconds, 35 * 60 + 53);
        assert_eq!(
            rows[0]
                .punches
                .iter()
                .map(|(cp, t)| (*cp, t.format("%H:%M:%S").to_string()))
                .collect::<Vec<_>>(),
            vec![
                (241, "12:55:20".into()),
                (47, "12:56:25".into()),
                (49, "12:57:12".into()),
                (240, "13:31:13".into()),
            ]
        );
        assert_eq!(rows[1].elapsed_seconds, 3600 + 19);
    }

    #[test]
    fn dsq_has_no_place() {
        let rows = parse_csv(DSQ).unwrap();
        assert_eq!(rows[0].status, "Дисквалификация");
        assert_eq!(rows[0].place, None);
        assert_eq!(rows[0].elapsed_seconds, 12 * 60);
    }

    #[test]
    fn payload_keeps_empty_bib_and_csv_places() {
        let rows = parse_csv(USADBA).unwrap();
        let payload = build_payload("Усадьба", NaiveDate::from_ymd_opt(2024, 4, 12).unwrap(), &rows);
        assert_eq!(payload.sport_kind, "orient");
        assert!(payload.participants.iter().all(|p| p.bib.is_empty()));
        assert_eq!(payload.results[0].place, Some(1));
        assert_eq!(payload.results[2].place, Some(18));
        assert_eq!(payload.participants[0].marks[0].mark_time, "2024-04-12T10:50:53");
    }

    #[test]
    fn preview_counts_courses() {
        let preview = preview_csv(USADBA, "2024-04-12").unwrap();
        assert_eq!(preview.row_count, 3);
        assert_eq!(preview.ok_count, 3);
        assert_eq!(preview.suggested_slug, "orient-2024-04-12");
        assert_eq!(preview.courses[0].name, "D1");
        assert_eq!(preview.courses[0].count, 3);
    }

    #[test]
    fn parses_field_csv_files_when_present() {
        let roots = [
            std::path::Path::new("/data/projects/Rogein/csv/усадьба трубецких.csv"),
            std::path::Path::new("/data/projects/Rogein/csv/фили.csv"),
        ];
        for path in roots {
            if !path.exists() {
                continue;
            }
            let text = std::fs::read_to_string(path).unwrap();
            let rows = parse_csv(&text).unwrap();
            assert!(rows.len() > 10, "{} too few rows", path.display());
            assert!(rows.iter().any(|r| !r.punches.is_empty()));
            assert!(rows.iter().any(|r| r.punches.iter().any(|(cp, _)| *cp == 241)));
            assert!(rows.iter().any(|r| r.punches.iter().any(|(cp, _)| *cp == 240)));
        }
    }
}
