use std::collections::BTreeMap;

pub const IOF_COURSE_MISMATCH: &str = "IOF_COURSE_MISMATCH";

#[derive(Debug, Clone)]
pub struct IofCourse {
    pub name: String,
    pub controls: Vec<i64>,
    pub length_meters: Option<i64>,
    pub climb_meters: Option<i64>,
    pub leg_meters: Vec<Option<i64>>,
}

pub fn looks_like_iof_course_xml(text: &str) -> bool {
    let trimmed = text.trim_start_matches(['\u{feff}', ' ', '\t', '\n', '\r']);
    let head: String = trimmed.chars().take(400).collect::<String>().to_ascii_lowercase();
    head.contains("coursedata") || head.contains("iofversion")
}

pub fn parse_iof_course_xml(text: &str) -> Result<Vec<IofCourse>, String> {
    let xml = strip_xml_comments(text);
    let root_inner = element_inner(&xml, "CourseData").ok_or_else(|| {
        "Это не файл дистанций IOF XML (нужен CourseData)".to_string()
    })?;
    let mut rows = Vec::new();
    let mut seen: BTreeMap<String, String> = BTreeMap::new();
    for race_inner in element_inners(root_inner, "RaceCourseData") {
        for (attrs, course_inner) in elements(race_inner, "Course") {
            let _ = attrs;
            let name = child_text(course_inner, "Name");
            if name.is_empty() {
                continue;
            }
            let key = course_key(&name);
            if seen.insert(key, name.clone()).is_some() {
                return Err(format!("Дистанция «{name}» повторяется в XML"));
            }
            let parsed = parse_one_course(course_inner, &name)?;
            if parsed.controls.is_empty() {
                return Err(format!("У дистанции «{name}» нет КП"));
            }
            rows.push(parsed);
        }
    }
    if rows.is_empty() {
        return Err("В IOF XML нет дистанций".to_string());
    }
    Ok(rows)
}

pub fn courses_need_replace(existing: &[(String, Vec<i64>)], incoming: &[IofCourse]) -> bool {
    if existing.is_empty() {
        return false;
    }
    for row in incoming {
        let Some((_, controls)) = existing
            .iter()
            .find(|(name, _)| course_key(name) == course_key(&row.name))
        else {
            continue;
        };
        if controls != &row.controls {
            return true;
        }
    }
    false
}

fn parse_one_course(inner: &str, name: &str) -> Result<IofCourse, String> {
    let length_meters = parse_meters(&child_text(inner, "Length"));
    let climb_meters = parse_meters(&child_text(inner, "Climb"));
    let mut controls = Vec::new();
    let mut legs: Vec<Option<i64>> = Vec::new();
    let mut pending_meters = 0_i64;
    let mut pending_has = false;
    let mut emitted = 0_usize;
    for (attrs, node) in elements(inner, "CourseControl") {
        let type_name = attrs.get("type").cloned().unwrap_or_default();
        let code = child_text(node, "Control");
        if let Some(leg) = parse_meters(&child_text(node, "LegLength")) {
            pending_meters += leg;
            pending_has = true;
        }
        let kind = control_kind(&type_name, &code);
        if kind == IofControlKind::Skip {
            continue;
        }
        if emitted > 0 {
            legs.push(if pending_has { Some(pending_meters) } else { None });
        }
        pending_meters = 0;
        pending_has = false;
        if kind == IofControlKind::Control {
            let cp = code.parse::<i64>().map_err(|_| {
                format!("У дистанции «{name}» некорректный номер КП «{code}»")
            })?;
            if cp <= 0 {
                return Err(format!("У дистанции «{name}» номер КП должен быть > 0"));
            }
            controls.push(cp);
        }
        emitted += 1;
    }
    Ok(IofCourse {
        name: name.to_string(),
        controls,
        length_meters,
        climb_meters,
        leg_meters: legs,
    })
}

#[derive(PartialEq, Eq)]
enum IofControlKind {
    Start,
    Control,
    Finish,
    Skip,
}

fn control_kind(type_name: &str, code: &str) -> IofControlKind {
    match type_name.trim().to_ascii_lowercase().as_str() {
        "start" => IofControlKind::Start,
        "finish" => IofControlKind::Finish,
        "crossingpoint" | "endofmarkedroute" => IofControlKind::Skip,
        "control" => IofControlKind::Control,
        _ => {
            if is_start_or_finish_code(code, 's') {
                IofControlKind::Start
            } else if is_start_or_finish_code(code, 'f') {
                IofControlKind::Finish
            } else if code.parse::<i64>().is_ok() {
                IofControlKind::Control
            } else {
                IofControlKind::Skip
            }
        }
    }
}

fn is_start_or_finish_code(code: &str, kind: char) -> bool {
    let mut chars = code.chars();
    let Some(first) = chars.next() else {
        return false;
    };
    first.eq_ignore_ascii_case(&kind) && chars.all(|c| c.is_ascii_digit()) && code.len() >= 2
}

fn parse_meters(raw: &str) -> Option<i64> {
    let value = raw.trim().parse::<f64>().ok()?;
    if value < 0.0 {
        None
    } else {
        Some(value as i64)
    }
}

fn course_key(name: &str) -> String {
    name.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase()
}

fn strip_xml_comments(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(start) = rest.find("<!--") {
        out.push_str(&rest[..start]);
        rest = &rest[start + 4..];
        if let Some(end) = rest.find("-->") {
            rest = &rest[end + 3..];
        } else {
            break;
        }
    }
    out.push_str(rest);
    out
}

fn local_name(raw: &str) -> &str {
    raw.rsplit(':').next().unwrap_or(raw)
}

fn element_inner<'a>(xml: &'a str, local: &str) -> Option<&'a str> {
    elements(xml, local).into_iter().next().map(|(_, inner)| inner)
}

fn element_inners<'a>(xml: &'a str, local: &str) -> Vec<&'a str> {
    elements(xml, local).into_iter().map(|(_, inner)| inner).collect()
}

fn elements<'a>(xml: &'a str, local: &str) -> Vec<(BTreeMap<String, String>, &'a str)> {
    let mut out = Vec::new();
    let mut pos = 0usize;
    while let Some((attrs, inner, next)) = next_element(xml, pos, local) {
        out.push((attrs, inner));
        pos = next;
    }
    out
}

fn next_element<'a>(
    xml: &'a str,
    from: usize,
    local: &str,
) -> Option<(BTreeMap<String, String>, &'a str, usize)> {
    let slice = xml.get(from..)?;
    let mut search = 0usize;
    while search < slice.len() {
        let rel = slice[search..].find('<')?;
        let start = search + rel;
        let after_lt = start + 1;
        if slice[after_lt..].starts_with('/')
            || slice[after_lt..].starts_with('?')
            || slice[after_lt..].starts_with('!')
        {
            search = after_lt;
            continue;
        }
        let rest = &slice[after_lt..];
        let name_end = rest.find(|c: char| c.is_whitespace() || c == '>' || c == '/')?;
        let raw_name = &rest[..name_end];
        if !local_name(raw_name).eq_ignore_ascii_case(local) {
            search = after_lt;
            continue;
        }
        let gt = rest.find('>')?;
        let open_body = &rest[..gt];
        let attrs = parse_attrs(open_body.get(raw_name.len()..).unwrap_or(""));
        let content_start = from + after_lt + gt + 1;
        if open_body.trim_end().ends_with('/') {
            return Some((attrs, "", content_start));
        }
        let (inner, after) = matching_close(xml, local, content_start)?;
        return Some((attrs, inner, after));
    }
    None
}

fn matching_close<'a>(xml: &'a str, local: &str, content_start: usize) -> Option<(&'a str, usize)> {
    let mut depth = 1usize;
    let mut pos = content_start;
    while pos < xml.len() {
        let rel = xml[pos..].find('<')?;
        pos += rel;
        let rest = &xml[pos + 1..];
        if rest.starts_with('?') || rest.starts_with('!') {
            pos += 1;
            continue;
        }
        let closing = rest.starts_with('/');
        let name_src = if closing { &rest[1..] } else { rest };
        let name_end = name_src.find(|c: char| c.is_whitespace() || c == '>' || c == '/')?;
        let raw_name = &name_src[..name_end];
        let gt = rest.find('>')?;
        let self_close = !closing && rest[..gt].trim_end().ends_with('/');
        if local_name(raw_name).eq_ignore_ascii_case(local) {
            if closing {
                depth -= 1;
                if depth == 0 {
                    let after = pos + 1 + gt + 1;
                    return Some((&xml[content_start..pos], after));
                }
            } else if !self_close {
                depth += 1;
            }
        }
        pos += 1 + gt + 1;
    }
    None
}

fn parse_attrs(raw: &str) -> BTreeMap<String, String> {
    let mut map = BTreeMap::new();
    let mut rest = raw.trim().trim_end_matches('/');
    while !rest.is_empty() {
        rest = rest.trim_start();
        let Some(eq) = rest.find('=') else {
            break;
        };
        let key = local_name(rest[..eq].trim()).to_ascii_lowercase();
        rest = rest[eq + 1..].trim_start();
        let (value, next) = parse_quoted(rest);
        map.insert(key, value);
        rest = next;
    }
    map
}

fn parse_quoted(s: &str) -> (String, &str) {
    let quote = s.chars().next();
    if quote == Some('"') || quote == Some('\'') {
        let q = quote.unwrap();
        if let Some(end) = s[1..].find(q) {
            return (s[1..1 + end].to_string(), &s[1 + end + 1..]);
        }
    }
    let end = s.find(char::is_whitespace).unwrap_or(s.len());
    (s[..end].to_string(), &s[end..])
}

fn child_text(inner: &str, local: &str) -> String {
    next_element(inner, 0, local)
        .map(|(_, text, _)| text.trim().to_string())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_ocad_sample() {
        let text = include_str!("fixtures/iof-courses.xml");
        let rows = parse_iof_course_xml(text).unwrap();
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].name, "D1");
        assert_eq!(rows[0].length_meters, Some(4820));
        assert_eq!(rows[0].controls.len(), 28);
        assert_eq!(&rows[0].controls[..3], &[32, 43, 36]);
        assert_eq!(*rows[0].controls.last().unwrap(), 33);
        assert_eq!(rows[0].leg_meters.first().copied().flatten(), Some(133));
        assert_eq!(rows[0].leg_meters.last().copied().flatten(), Some(274));
        assert_eq!(rows[1].name, "D2");
        assert_eq!(rows[1].length_meters, Some(2310));
        assert_eq!(rows[1].controls.len(), 14);
        assert_eq!(rows[1].leg_meters.len(), 15);
    }
}
