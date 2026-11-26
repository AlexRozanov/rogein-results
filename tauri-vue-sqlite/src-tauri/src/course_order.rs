use std::collections::{HashMap, HashSet};

/// Ordered course segment: a single mandatory CP or a free-order cluster («гроздь»).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CourseSegment {
    Fixed(i64),
    Cluster(Vec<i64>),
}

/// 0 = fixed, >0 = cluster group id. Same id in a row = one гроздь; different ids back-to-back
/// = adjacent clusters that must be taken in sequence.
pub fn normalize_cluster_groups(len: usize, groups: &[i32]) -> Vec<i32> {
    (0..len)
        .map(|i| groups.get(i).copied().unwrap_or(0).max(0))
        .collect()
}

/// Legacy boolean flags → unique id per consecutive true-run.
pub fn bool_runs_to_groups(flags: &[bool]) -> Vec<i32> {
    let mut out = vec![0; flags.len()];
    let mut next_id = 1i32;
    let mut i = 0usize;
    while i < flags.len() {
        if !flags[i] {
            i += 1;
            continue;
        }
        let id = next_id;
        next_id += 1;
        while i < flags.len() && flags[i] {
            out[i] = id;
            i += 1;
        }
    }
    out
}

pub fn normalize_random_order(len: usize, flags: &[bool]) -> Vec<bool> {
    (0..len)
        .map(|i| flags.get(i).copied().unwrap_or(false))
        .collect()
}

pub fn cluster_groups_from_flags(len: usize, flags: &[bool]) -> Vec<i32> {
    bool_runs_to_groups(&normalize_random_order(len, flags))
}

/// Parse pack/DB values: bool / 0·1 → numbered runs; any value >1 → explicit group ids.
pub fn parse_cluster_groups(raw: &[i32], len: usize) -> Vec<i32> {
    if len == 0 {
        return Vec::new();
    }
    let mut nums = Vec::with_capacity(len);
    let mut any_above_one = false;
    for i in 0..len {
        let n = raw.get(i).copied().unwrap_or(0).max(0);
        if n > 1 {
            any_above_one = true;
        }
        nums.push(n);
    }
    if any_above_one {
        normalize_cluster_groups(len, &nums)
    } else {
        bool_runs_to_groups(&nums.iter().map(|n| *n > 0).collect::<Vec<_>>())
    }
}

pub fn any_cluster_group(groups: &[i32]) -> bool {
    groups.iter().any(|g| *g > 0)
}

pub fn any_random_order(flags: &[bool]) -> bool {
    flags.iter().any(|v| *v)
}

pub fn groups_to_bool_flags(groups: &[i32]) -> Vec<bool> {
    groups.iter().map(|g| *g > 0).collect()
}

pub fn next_cluster_group_id(groups: &[i32]) -> i32 {
    groups.iter().copied().max().unwrap_or(0) + 1
}

pub fn toggle_cluster_group(groups: &[i32], index: usize, join_previous: bool) -> Vec<i32> {
    if index >= groups.len() {
        return groups.to_vec();
    }
    let mut out = groups.to_vec();
    if out[index] > 0 {
        out[index] = 0;
        return out;
    }
    let prev = index
        .checked_sub(1)
        .and_then(|i| groups.get(i).copied())
        .filter(|g| *g > 0);
    out[index] = if join_previous {
        prev.unwrap_or_else(|| next_cluster_group_id(groups))
    } else {
        next_cluster_group_id(groups)
    };
    out
}

/// Split cluster after `index`: right-hand same-id run gets a new id.
pub fn split_cluster_after(groups: &[i32], index: usize) -> Option<Vec<i32>> {
    if index >= groups.len() {
        return None;
    }
    let id = groups[index];
    if id <= 0 {
        return None;
    }
    let next = *groups.get(index + 1)?;
    if next != id {
        return None;
    }
    let new_id = next_cluster_group_id(groups);
    let mut out = groups.to_vec();
    let mut i = index + 1;
    while i < out.len() && out[i] == id {
        out[i] = new_id;
        i += 1;
    }
    Some(out)
}

pub fn merge_cluster_with_next(groups: &[i32], index: usize) -> Option<Vec<i32>> {
    if index >= groups.len() {
        return None;
    }
    let left = groups[index];
    if left <= 0 {
        return None;
    }
    let mut end = index;
    while end + 1 < groups.len() && groups[end + 1] == left {
        end += 1;
    }
    let right_idx = end + 1;
    if right_idx >= groups.len() {
        return None;
    }
    let right = groups[right_idx];
    if right <= 0 || right == left {
        return None;
    }
    Some(
        groups
            .iter()
            .map(|g| if *g == right { left } else { *g })
            .collect(),
    )
}

/// Build segments: consecutive equal positive group ids form one cluster.
pub fn course_segments(controls: &[i64], random_order: &[bool]) -> Vec<CourseSegment> {
    course_segments_from_groups(controls, &cluster_groups_from_flags(controls.len(), random_order))
}

pub fn course_segments_from_groups(controls: &[i64], cluster_groups: &[i32]) -> Vec<CourseSegment> {
    let groups = normalize_cluster_groups(controls.len(), cluster_groups);
    let mut out = Vec::new();
    let mut i = 0usize;
    while i < controls.len() {
        let g = groups[i];
        if g > 0 {
            let mut cluster = vec![controls[i]];
            i += 1;
            while i < controls.len() && groups[i] == g {
                cluster.push(controls[i]);
                i += 1;
            }
            out.push(CourseSegment::Cluster(cluster));
        } else {
            out.push(CourseSegment::Fixed(controls[i]));
            i += 1;
        }
    }
    out
}

pub fn filter_course_with_order(
    controls: &[i64],
    random_order: &[bool],
    removed: &HashSet<i64>,
) -> (Vec<i64>, Vec<bool>) {
    let (cps, groups) =
        filter_course_with_groups(controls, &cluster_groups_from_flags(controls.len(), random_order), removed);
    (cps, groups_to_bool_flags(&groups))
}

pub fn filter_course_with_groups(
    controls: &[i64],
    cluster_groups: &[i32],
    removed: &HashSet<i64>,
) -> (Vec<i64>, Vec<i32>) {
    let groups = normalize_cluster_groups(controls.len(), cluster_groups);
    let mut cps = Vec::new();
    let mut out = Vec::new();
    for (i, &cp) in controls.iter().enumerate() {
        if removed.contains(&cp) {
            continue;
        }
        cps.push(cp);
        out.push(groups[i]);
    }
    (cps, out)
}

fn segment_cps(seg: &CourseSegment) -> Vec<i64> {
    match seg {
        CourseSegment::Fixed(cp) => vec![*cp],
        CourseSegment::Cluster(cps) => cps.clone(),
    }
}

fn future_required(segments: &[CourseSegment], from: usize) -> HashSet<i64> {
    let mut out = HashSet::new();
    for seg in segments.iter().skip(from) {
        for cp in segment_cps(seg) {
            out.insert(cp);
        }
    }
    out
}

pub fn course_taken_with_order(
    punches: &[i64],
    controls: &[i64],
    random_order: &[bool],
    add_counts: &HashMap<i64, usize>,
) -> usize {
    course_taken_with_groups(
        punches,
        controls,
        &cluster_groups_from_flags(controls.len(), random_order),
        add_counts,
    )
}

pub fn course_taken_with_groups(
    punches: &[i64],
    controls: &[i64],
    cluster_groups: &[i32],
    add_counts: &HashMap<i64, usize>,
) -> usize {
    if controls.is_empty() {
        return 0;
    }
    let segments = course_segments_from_groups(controls, cluster_groups);
    let mut remaining_adds = add_counts.clone();
    let mut p = 0usize;
    let mut taken = 0usize;

    for (seg_idx, seg) in segments.iter().enumerate() {
        let future = future_required(&segments, seg_idx + 1);
        match seg {
            CourseSegment::Fixed(cp) => {
                let mut q = p;
                while q < punches.len() {
                    if punches[q] == *cp {
                        break;
                    }
                    q += 1;
                }
                if q < punches.len() && punches[q] == *cp {
                    p = q + 1;
                    taken += 1;
                    continue;
                }
                if let Some(left) = remaining_adds.get_mut(cp) {
                    if *left > 0 {
                        *left -= 1;
                        taken += 1;
                        continue;
                    }
                }
                return taken;
            }
            CourseSegment::Cluster(cps) => {
                let mut need: HashMap<i64, usize> = HashMap::new();
                for &cp in cps {
                    *need.entry(cp).or_insert(0) += 1;
                }
                while need.values().any(|n| *n > 0) {
                    let mut found: Option<(usize, i64)> = None;
                    let mut q = p;
                    while q < punches.len() {
                        let cp = punches[q];
                        if need.get(&cp).copied().unwrap_or(0) > 0 {
                            found = Some((q, cp));
                            break;
                        }
                        if future.contains(&cp) {
                            break;
                        }
                        q += 1;
                    }
                    if let Some((q, cp)) = found {
                        *need.get_mut(&cp).unwrap() -= 1;
                        p = q + 1;
                        taken += 1;
                        continue;
                    }
                    let mut used_add = false;
                    for (&cp, count) in need.iter_mut() {
                        if *count == 0 {
                            continue;
                        }
                        if let Some(left) = remaining_adds.get_mut(&cp) {
                            if *left > 0 {
                                *left -= 1;
                                *count -= 1;
                                taken += 1;
                                used_add = true;
                                break;
                            }
                        }
                    }
                    if !used_add {
                        return taken;
                    }
                }
            }
        }
    }
    taken
}

#[cfg(test)]
mod tests {
    use super::*;

    fn flags(bits: &[u8]) -> Vec<bool> {
        bits.iter().map(|b| *b != 0).collect()
    }

    #[test]
    fn segments_split_clusters() {
        let controls = vec![31, 32, 33, 34, 35, 36, 37, 38];
        let ro = flags(&[0, 0, 1, 1, 1, 0, 1, 1]);
        let segs = course_segments(&controls, &ro);
        assert_eq!(
            segs,
            vec![
                CourseSegment::Fixed(31),
                CourseSegment::Fixed(32),
                CourseSegment::Cluster(vec![33, 34, 35]),
                CourseSegment::Fixed(36),
                CourseSegment::Cluster(vec![37, 38]),
            ]
        );
    }

    #[test]
    fn adjacent_clusters_are_ordered() {
        let controls = vec![71, 72, 73, 74, 75, 76, 77, 78, 79];
        // [74 75 76] then [77 78]
        let groups = vec![0, 0, 0, 1, 1, 1, 2, 2, 0];
        let segs = course_segments_from_groups(&controls, &groups);
        assert_eq!(
            segs,
            vec![
                CourseSegment::Fixed(71),
                CourseSegment::Fixed(72),
                CourseSegment::Fixed(73),
                CourseSegment::Cluster(vec![74, 75, 76]),
                CourseSegment::Cluster(vec![77, 78]),
                CourseSegment::Fixed(79),
            ]
        );
        // Finish first cluster in any order, then second.
        let ok = vec![71, 72, 73, 76, 74, 75, 78, 77, 79];
        assert_eq!(
            course_taken_with_groups(&ok, &controls, &groups, &HashMap::new()),
            9
        );
        // Enter second cluster before finishing first → stop after 73 + one of first cluster.
        let early = vec![71, 72, 73, 74, 77, 75, 76, 78, 79];
        assert_eq!(
            course_taken_with_groups(&early, &controls, &groups, &HashMap::new()),
            4
        );
        // First cluster incomplete → second cluster punches ignored until first is done.
        let skip = vec![71, 72, 73, 74, 75, 78, 77, 76, 79];
        assert_eq!(
            course_taken_with_groups(&skip, &controls, &groups, &HashMap::new()),
            5
        );
    }

    #[test]
    fn split_and_merge_helpers() {
        let groups = vec![0, 1, 1, 1, 1, 0];
        let split = split_cluster_after(&groups, 2).unwrap();
        assert_eq!(split, vec![0, 1, 1, 2, 2, 0]);
        let merged = merge_cluster_with_next(&split, 1).unwrap();
        assert_eq!(merged, vec![0, 1, 1, 1, 1, 0]);
    }

    #[test]
    fn parse_legacy_zero_one_numbers_runs() {
        // Two separate true-runs → two group ids even when stored as 0/1.
        assert_eq!(
            parse_cluster_groups(&[0, 1, 1, 0, 1, 1], 6),
            vec![0, 1, 1, 0, 2, 2]
        );
        assert_eq!(
            parse_cluster_groups(&[0, 1, 1, 2, 2, 0], 6),
            vec![0, 1, 1, 2, 2, 0]
        );
    }

    #[test]
    fn ordered_course_still_subsequence() {
        let required = vec![31, 32, 33];
        let punches = vec![31, 99, 32, 33];
        assert_eq!(
            course_taken_with_order(&punches, &required, &[], &HashMap::new()),
            3
        );
        assert_eq!(
            course_taken_with_order(&vec![31, 33], &required, &[], &HashMap::new()),
            1
        );
    }

    #[test]
    fn cluster_any_order_ok() {
        let controls = vec![31, 32, 33, 34, 35, 36];
        let ro = flags(&[0, 0, 1, 1, 1, 0]);
        let a = vec![31, 32, 33, 35, 34, 36];
        let b = vec![31, 32, 35, 33, 34, 36];
        assert_eq!(course_taken_with_order(&a, &controls, &ro, &HashMap::new()), 6);
        assert_eq!(course_taken_with_order(&b, &controls, &ro, &HashMap::new()), 6);
    }

    #[test]
    fn early_fixed_before_cluster_done_fails() {
        let controls = vec![31, 32, 33, 34, 35, 36];
        let ro = flags(&[0, 0, 1, 1, 1, 0]);
        let punches = vec![31, 32, 33, 36, 34, 35];
        assert_eq!(
            course_taken_with_order(&punches, &controls, &ro, &HashMap::new()),
            3
        );
    }

    #[test]
    fn missing_cluster_cp_incomplete() {
        let controls = vec![31, 33, 34, 35, 36];
        let ro = flags(&[0, 1, 1, 1, 0]);
        let punches = vec![31, 33, 34, 36];
        assert_eq!(
            course_taken_with_order(&punches, &controls, &ro, &HashMap::new()),
            3
        );
    }
}
