/** Fixed / cluster («гроздь») matching — mirrors android CourseOrder / Rust course_order. */

export type CourseSegment =
  | { kind: "fixed"; cp: number }
  | { kind: "cluster"; cps: number[] };

export type CourseCpProgress = {
  cp: number;
  taken: boolean;
  added: boolean;
  punched: boolean;
  blocker: boolean;
  /** True when this CP belongs to a randomOrder cluster. */
  cluster: boolean;
  clusterGroup: number;
};

/** 0 = fixed; >0 = cluster group id. */
export function normalizeClusterGroups(
  len: number,
  groups: Array<number | boolean | null | undefined> | null | undefined,
): number[] {
  return parseClusterGroups(groups, len);
}

export function boolRunsToGroups(flags: boolean[]): number[] {
  const out = Array.from({ length: flags.length }, () => 0);
  let nextId = 1;
  let i = 0;
  while (i < flags.length) {
    if (!flags[i]) {
      i += 1;
      continue;
    }
    const id = nextId;
    nextId += 1;
    while (i < flags.length && flags[i]) {
      out[i] = id;
      i += 1;
    }
  }
  return out;
}

/**
 * JSON/DB: boolean or 0/1 → numbered runs; any value >1 → explicit group ids
 * (so adjacent clusters [1,1,2,2] stay distinct).
 */
export function parseClusterGroups(raw: unknown, controlCount: number): number[] {
  if (controlCount <= 0) return [];
  if (!Array.isArray(raw) || raw.length === 0) {
    return Array.from({ length: controlCount }, () => 0);
  }
  const nums: number[] = [];
  let anyAboveOne = false;
  for (let i = 0; i < controlCount; i += 1) {
    const value = raw[i];
    let n = 0;
    if (typeof value === "boolean") n = value ? 1 : 0;
    else if (typeof value === "number" && Number.isFinite(value)) n = Math.max(0, Math.trunc(value));
    else if (typeof value === "string") {
      if (value === "true" || value === "1") n = 1;
      else if (value === "false" || value === "0" || value.trim() === "") n = 0;
      else {
        const parsed = Number(value);
        n = Number.isFinite(parsed) ? Math.max(0, Math.trunc(parsed)) : 0;
      }
    }
    if (n > 1) anyAboveOne = true;
    nums.push(n);
  }
  return anyAboveOne ? nums : boolRunsToGroups(nums.map((n) => n > 0));
}

export function nextClusterGroupId(groups: number[]): number {
  return Math.max(0, ...groups) + 1;
}

export function toggleClusterGroup(
  groups: number[],
  index: number,
  joinPrevious = true,
): number[] {
  if (index < 0 || index >= groups.length) return groups;
  const out = [...groups];
  if (out[index] > 0) {
    out[index] = 0;
    return out;
  }
  const prev = index > 0 && groups[index - 1] > 0 ? groups[index - 1] : null;
  out[index] =
    joinPrevious && prev != null ? prev : nextClusterGroupId(groups);
  return out;
}

/** Split cluster after index: right-hand same-id run gets a new id. */
export function splitClusterAfter(groups: number[], index: number): number[] | null {
  if (index < 0 || index >= groups.length) return null;
  const id = groups[index];
  if (id <= 0) return null;
  if (groups[index + 1] !== id) return null;
  const newId = nextClusterGroupId(groups);
  const out = [...groups];
  for (let i = index + 1; i < out.length && out[i] === id; i += 1) out[i] = newId;
  return out;
}

export function mergeClusterWithNext(groups: number[], index: number): number[] | null {
  if (index < 0 || index >= groups.length) return null;
  const left = groups[index];
  if (left <= 0) return null;
  let end = index;
  while (end + 1 < groups.length && groups[end + 1] === left) end += 1;
  const rightIdx = end + 1;
  if (rightIdx >= groups.length) return null;
  const right = groups[rightIdx];
  if (right <= 0 || right === left) return null;
  return groups.map((g) => (g === right ? left : g));
}

export function filterCourseWithOrder(
  controls: number[],
  randomOrder: unknown,
  removed: Set<number>,
): { controls: number[]; randomOrder: number[] } {
  const groups = parseClusterGroups(randomOrder, controls.length);
  const cps: number[] = [];
  const ro: number[] = [];
  for (let i = 0; i < controls.length; i += 1) {
    if (removed.has(controls[i])) continue;
    cps.push(controls[i]);
    ro.push(groups[i]);
  }
  return { controls: cps, randomOrder: ro };
}

export function courseSegments(
  controls: number[],
  randomOrder: unknown,
): CourseSegment[] {
  const groups = parseClusterGroups(randomOrder, controls.length);
  const out: CourseSegment[] = [];
  let i = 0;
  while (i < controls.length) {
    const g = groups[i];
    if (g > 0) {
      const cps: number[] = [];
      while (i < controls.length && groups[i] === g) {
        cps.push(controls[i]);
        i += 1;
      }
      out.push({ kind: "cluster", cps });
    } else {
      out.push({ kind: "fixed", cp: controls[i] });
      i += 1;
    }
  }
  return out;
}

function futureRequired(segments: CourseSegment[], from: number): Set<number> {
  const out = new Set<number>();
  for (let j = from; j < segments.length; j += 1) {
    const seg = segments[j];
    if (seg.kind === "fixed") out.add(seg.cp);
    else for (const cp of seg.cps) out.add(cp);
  }
  return out;
}

export function courseProgress(
  punches: number[],
  required: number[],
  addCounts: Map<number, number> = new Map(),
  randomOrder: unknown = [],
): CourseCpProgress[] {
  if (!required.length) return [];
  const punched = new Set(punches);
  const remainingAdds = new Map(addCounts);
  const groups = parseClusterGroups(randomOrder, required.length);
  const segments = courseSegments(required, groups);
  const takenFlags = Array.from({ length: required.length }, () => false);
  const addedFlags = Array.from({ length: required.length }, () => false);
  let punchAt = 0;
  let controlAt = 0;

  for (let segIdx = 0; segIdx < segments.length; segIdx += 1) {
    const seg = segments[segIdx];
    const future = futureRequired(segments, segIdx + 1);
    if (seg.kind === "fixed") {
      let q = punchAt;
      while (q < punches.length && punches[q] !== seg.cp) q += 1;
      if (q < punches.length && punches[q] === seg.cp) {
        takenFlags[controlAt] = true;
        punchAt = q + 1;
      } else {
        const left = remainingAdds.get(seg.cp) ?? 0;
        if (left > 0) {
          remainingAdds.set(seg.cp, left - 1);
          takenFlags[controlAt] = true;
          addedFlags[controlAt] = true;
        }
      }
      controlAt += 1;
      continue;
    }

    const need = new Map<number, number>();
    for (const cp of seg.cps) need.set(cp, (need.get(cp) ?? 0) + 1);
    const slotByCp = new Map<number, number[]>();
    for (let offset = 0; offset < seg.cps.length; offset += 1) {
      const cp = seg.cps[offset];
      const slots = slotByCp.get(cp) ?? [];
      slots.push(controlAt + offset);
      slotByCp.set(cp, slots);
    }
    while ([...need.values()].some((n) => n > 0)) {
      let foundIdx = -1;
      let foundCp = 0;
      let q = punchAt;
      while (q < punches.length) {
        const cp = punches[q];
        if ((need.get(cp) ?? 0) > 0) {
          foundIdx = q;
          foundCp = cp;
          break;
        }
        if (future.has(cp)) break;
        q += 1;
      }
      if (foundIdx >= 0) {
        need.set(foundCp, (need.get(foundCp) ?? 1) - 1);
        const slots = slotByCp.get(foundCp);
        const slot = slots?.shift();
        if (slot != null) takenFlags[slot] = true;
        punchAt = foundIdx + 1;
        continue;
      }
      let usedAdd = false;
      for (const [cp, count] of need) {
        if (count <= 0) continue;
        const left = remainingAdds.get(cp) ?? 0;
        if (left > 0) {
          remainingAdds.set(cp, left - 1);
          need.set(cp, count - 1);
          const slots = slotByCp.get(cp);
          const slot = slots?.shift();
          if (slot != null) {
            takenFlags[slot] = true;
            addedFlags[slot] = true;
          }
          usedAdd = true;
          break;
        }
      }
      if (!usedAdd) break;
    }
    controlAt += seg.cps.length;
  }

  let firstMiss = true;
  return required.map((cp, index) => {
    const taken = takenFlags[index] ?? false;
    const blocker = !taken && firstMiss;
    if (!taken) firstMiss = false;
    const clusterGroup = groups[index] ?? 0;
    return {
      cp,
      taken,
      added: addedFlags[index] ?? false,
      punched: punched.has(cp),
      blocker,
      cluster: clusterGroup > 0,
      clusterGroup,
    };
  });
}

/** Separator between two consecutive required CPs in the published sequence. */
export function courseSeqSeparator(
  flags: Array<number | boolean> | null | undefined,
  index: number,
): "·" | "→" {
  const groups = parseClusterGroups(flags, (flags?.length ?? 0) || index + 2);
  const a = groups[index] ?? 0;
  const b = groups[index + 1] ?? 0;
  return a > 0 && a === b ? "·" : "→";
}
