use std::collections::{HashMap, HashSet};

/// Ordered course segment: a single mandatory CP or a free-order cluster («гроздь»).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CourseSegment {
    Fixed(i64),
    Cluster(Vec<i64>),
}

pub fn normalize_random_order(len: usize, flags: &[bool]) -> Vec<bool> {
    (0..len)
        .map(|i| flags.get(i).copied().unwrap_or(false))
        .collect()
}

pub fn any_random_order(flags: &[bool]) -> bool {
    flags.iter().any(|v| *v)
}

/// Build segments from document-order controls + parallel randomOrder flags.
/// Consecutive `true` flags form one cluster; each `false` is a fixed CP.
pub fn course_segments(controls: &[i64], random_order: &[bool]) -> Vec<CourseSegment> {
    let flags = normalize_random_order(controls.len(), random_order);
    let mut out = Vec::new();
    let mut i = 0usize;
    while i < controls.len() {
        if flags[i] {
            let mut cluster = vec![controls[i]];
            i += 1;
            while i < controls.len() && flags[i] {
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
    let flags = normalize_random_order(controls.len(), random_order);
    let mut cps = Vec::new();
    let mut ro = Vec::new();
    for (i, &cp) in controls.iter().enumerate() {
        if removed.contains(&cp) {
            continue;
        }
        cps.push(cp);
        ro.push(flags[i]);
    }
    (cps, ro)
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

/// How many required CPs are satisfied under fixed/cluster rules.
/// Stops early on a missing CP or punching a later-segment CP before the current segment is done.
pub fn course_taken_with_order(
    punches: &[i64],
    controls: &[i64],
    random_order: &[bool],
    add_counts: &HashMap<i64, usize>,
) -> usize {
    if controls.is_empty() {
        return 0;
    }
    let segments = course_segments(controls, random_order);
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
        // 36 before finishing {33,34,35}
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
