export type SplitMark = {
  cp_number: number;
  mark_time: string;
  added?: boolean;
  seq?: number;
};

export type SplitCol = {
  cp: number;
  label: string;
  finish: boolean;
};

export type SplitCell = {
  splitSec: number;
  cumSec: number;
  splitPlace: number | null;
  cumPlace: number | null;
};

export function asMarks(raw: unknown): SplitMark[] {
  if (!Array.isArray(raw)) return [];
  const out: SplitMark[] = [];
  for (const item of raw) {
    if (!item || typeof item !== "object") continue;
    const row = item as {
      cp_number?: unknown;
      cp?: unknown;
      mark_time?: unknown;
      markTime?: unknown;
      added?: unknown;
      seq?: unknown;
    };
    const cp = Number(row.cp_number ?? row.cp);
    if (!Number.isFinite(cp)) continue;
    const seq = Number(row.seq);
    out.push({
      cp_number: cp,
      mark_time: String(row.mark_time ?? row.markTime ?? ""),
      added: row.added === true,
      seq: Number.isFinite(seq) && seq > 0 ? seq : undefined,
    });
  }
  return out;
}

function asNumberList(raw: unknown): number[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((value) => Number(value)).filter((value) => Number.isFinite(value));
}

function parseMarkMs(raw: string): number | null {
  const normalized = raw.trim().replace(" ", "T");
  if (!normalized) return null;
  const ms = Date.parse(normalized);
  return Number.isFinite(ms) ? ms : null;
}

/** Drop 24h archive date-rolls so a later punch still has a real split. */
export function wrapDeltaSec(fromMs: number, toMs: number): number | null {
  let sec = Math.round((toMs - fromMs) / 1000);
  while (sec > 12 * 3600) sec -= 24 * 3600;
  while (sec < -12 * 3600) sec += 24 * 3600;
  if (sec < 0) return null;
  return sec;
}

export { parseMarkMs };

export function punchWindow(marks: SplitMark[], startCp: number | null, finishCp: number | null) {
  if (!marks.length) return [] as SplitMark[];
  let startIdx = startCp != null ? marks.findIndex((mark) => mark.cp_number === startCp) : 0;
  if (startIdx < 0) startIdx = 0;
  let finishIdx = -1;
  if (finishCp != null) {
    for (let i = marks.length - 1; i > startIdx; i -= 1) {
      if (marks[i].cp_number === finishCp) {
        finishIdx = i;
        break;
      }
    }
  }
  const endIdx = finishIdx >= 0 ? finishIdx : marks.length - 1;
  if (endIdx < startIdx) return [];
  return marks.slice(startIdx, endIdx + 1);
}

export function inferCourseCps(
  courseCps: unknown,
  rows: { place?: number | null; status?: string | null; marks?: unknown }[],
  startCp: number | null,
  finishCp: number | null,
): number[] {
  const fromGroup = asNumberList(courseCps);
  if (fromGroup.length) return fromGroup;
  const leader =
    rows.find((row) => row.place === 1) ??
    rows.find((row) => (row.status || "OK") === "OK") ??
    rows[0];
  if (!leader) return [];
  return punchWindow(asMarks(leader.marks), startCp, finishCp)
    .map((mark) => mark.cp_number)
    .filter((cp) => cp !== startCp && cp !== finishCp);
}

export type PunchKind = "ok" | "start" | "finish" | "missed" | "extra" | "out_of_order";

export type ClassifiedPunch = {
  kind: PunchKind;
  cp: number;
  seq: number | null;
  mark_time: string;
  added?: boolean;
};

type PunchInput = {
  seq?: number;
  cp_number: number;
  mark_time?: string;
  added?: boolean;
};

function markWindowIdx(marks: PunchInput[], startCp: number | null, finishCp: number | null) {
  if (!marks.length) return { startIdx: 0, endIdx: -1 };
  let startIdx = startCp != null ? marks.findIndex((mark) => mark.cp_number === startCp) : 0;
  if (startIdx < 0) startIdx = 0;
  let finishIdx = -1;
  if (finishCp != null) {
    for (let i = marks.length - 1; i > startIdx; i -= 1) {
      if (marks[i].cp_number === finishCp) {
        finishIdx = i;
        break;
      }
    }
  }
  return { startIdx, endIdx: finishIdx >= 0 ? finishIdx : marks.length - 1 };
}

function emitPunch(mark: PunchInput, kind: PunchKind): ClassifiedPunch {
  return {
    kind,
    cp: mark.cp_number,
    seq: Number.isFinite(Number(mark.seq)) ? Number(mark.seq) : null,
    mark_time: String(mark.mark_time ?? ""),
    added: mark.added === true,
  };
}

function emitMissed(cp: number): ClassifiedPunch {
  return { kind: "missed", cp, seq: null, mark_time: "" };
}

/** 0 = fixed; >0 = cluster group id. Legacy bool/0·1 → numbered runs. */
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
  if (anyAboveOne) return nums;
  const out = Array.from({ length: controlCount }, () => 0);
  let nextId = 1;
  let i = 0;
  while (i < controlCount) {
    if (!(nums[i] > 0)) {
      i += 1;
      continue;
    }
    const id = nextId;
    nextId += 1;
    while (i < controlCount && nums[i] > 0) {
      out[i] = id;
      i += 1;
    }
  }
  return out;
}

type CourseSeg =
  | { kind: "fixed"; cp: number; index: number }
  | { kind: "cluster"; cps: number[]; indices: number[] };

/** Подпись порядка: `71→72→[74·75·76]→[77·78]→79`. */
export function formatCourseOrderLabel(
  courseCps: number[],
  randomOrder: unknown = [],
): string {
  if (!courseCps.length) return "";
  return courseSegments(courseCps, randomOrder)
    .map((seg) =>
      seg.kind === "fixed" ? String(seg.cp) : `[${seg.cps.join("·")}]`,
    )
    .join("→");
}

export function courseSeqSeparator(
  randomOrder: unknown,
  index: number,
  controlCount?: number,
): "·" | "→" {
  const len =
    controlCount ??
    (Array.isArray(randomOrder) ? randomOrder.length : index + 2);
  const groups = parseClusterGroups(randomOrder, len);
  const a = groups[index] ?? 0;
  const b = groups[index + 1] ?? 0;
  return a > 0 && a === b ? "·" : "→";
}

function courseSegments(courseCps: number[], randomOrder: unknown): CourseSeg[] {
  const groups = parseClusterGroups(randomOrder, courseCps.length);
  const out: CourseSeg[] = [];
  let i = 0;
  while (i < courseCps.length) {
    const g = groups[i];
    if (g > 0) {
      const cps: number[] = [];
      const indices: number[] = [];
      while (i < courseCps.length && groups[i] === g) {
        cps.push(courseCps[i]);
        indices.push(i);
        i += 1;
      }
      out.push({ kind: "cluster", cps, indices });
    } else {
      out.push({ kind: "fixed", cp: courseCps[i], index: i });
      i += 1;
    }
  }
  return out;
}

function futureSegCps(segments: CourseSeg[], from: number): Set<number> {
  const out = new Set<number>();
  for (let i = from; i < segments.length; i += 1) {
    const seg = segments[i];
    if (seg.kind === "fixed") out.add(seg.cp);
    else seg.cps.forEach((cp) => out.add(cp));
  }
  return out;
}

export function classifyCoursePunches(
  marks: PunchInput[],
  courseCps: number[],
  startCp: number | null,
  finishCp: number | null,
  randomOrder: unknown = [],
): ClassifiedPunch[] {
  if (!marks.length) {
    return courseCps.map(emitMissed);
  }
  const { startIdx, endIdx } = markWindowIdx(marks, startCp, finishCp);
  const segments = courseSegments(courseCps, randomOrder);
  let segIdx = 0;
  let clusterNeed = new Map<number, number>();
  const loadCluster = (seg: Extract<CourseSeg, { kind: "cluster" }>) => {
    clusterNeed = new Map();
    for (const cp of seg.cps) clusterNeed.set(cp, (clusterNeed.get(cp) ?? 0) + 1);
  };
  if (segments[0]?.kind === "cluster") loadCluster(segments[0]);
  const taken = new Set<number>();
  const out: ClassifiedPunch[] = [];
  const advance = () => {
    segIdx += 1;
    clusterNeed = new Map();
    const next = segments[segIdx];
    if (next?.kind === "cluster") loadCluster(next);
  };
  for (let i = 0; i < marks.length; i += 1) {
    const mark = marks[i];
    if (i < startIdx || i > endIdx) {
      out.push(emitPunch(mark, "extra"));
      continue;
    }
    const cp = mark.cp_number;
    if (startCp != null && cp === startCp) {
      out.push(emitPunch(mark, "start"));
      continue;
    }
    if (finishCp != null && cp === finishCp) {
      for (let s = segIdx; s < segments.length; s += 1) {
        const seg = segments[s];
        if (seg.kind === "fixed") {
          if (!taken.has(seg.index)) out.push(emitMissed(seg.cp));
        } else {
          const need = new Map<number, number>();
          for (const c of seg.cps) need.set(c, (need.get(c) ?? 0) + 1);
          for (const [c, n] of need) {
            for (let k = 0; k < n; k += 1) out.push(emitMissed(c));
          }
        }
      }
      segIdx = segments.length;
      out.push(emitPunch(mark, "finish"));
      continue;
    }
    if (!courseCps.length) {
      out.push(emitPunch(mark, "ok"));
      continue;
    }
    if (segIdx >= segments.length) {
      out.push(emitPunch(mark, courseCps.includes(cp) ? "ok" : "extra"));
      continue;
    }
    const seg = segments[segIdx];
    const future = futureSegCps(segments, segIdx + 1);
    const laterSame = () =>
      marks.slice(i + 1, endIdx + 1).some((m) => m.cp_number === cp);
    if (seg.kind === "fixed") {
      if (cp === seg.cp) {
        taken.add(seg.index);
        advance();
        out.push(emitPunch(mark, "ok"));
        continue;
      }
      if (future.has(cp)) {
        // Early later-segment punch: ignore if corrected later (same as fixed-order).
        out.push(emitPunch(mark, laterSame() ? "extra" : "out_of_order"));
        continue;
      }
      if (courseCps.includes(cp)) {
        out.push(emitPunch(mark, "out_of_order"));
        continue;
      }
      out.push(emitPunch(mark, "extra"));
      continue;
    }
    const left = clusterNeed.get(cp) ?? 0;
    if (left > 0) {
      clusterNeed.set(cp, left - 1);
      out.push(emitPunch(mark, "ok"));
      if ([...clusterNeed.values()].every((n) => n <= 0)) advance();
      continue;
    }
    if (future.has(cp)) {
      out.push(emitPunch(mark, laterSame() ? "extra" : "out_of_order"));
      continue;
    }
    out.push(emitPunch(mark, courseCps.includes(cp) ? "ok" : "extra"));
  }
  for (let s = segIdx; s < segments.length; s += 1) {
    const seg = segments[s];
    if (seg.kind === "fixed") {
      if (!taken.has(seg.index)) out.push(emitMissed(seg.cp));
    } else {
      for (const [c, n] of clusterNeed.size && s === segIdx
        ? clusterNeed
        : (() => {
            const m = new Map<number, number>();
            for (const cp of seg.cps) m.set(cp, (m.get(cp) ?? 0) + 1);
            return m;
          })()) {
        for (let k = 0; k < n; k += 1) out.push(emitMissed(c));
      }
    }
  }
  return out;
}

export function splitColumns(
  courseCps: number[],
  finishCp: number | null,
  randomOrder: unknown = [],
): SplitCol[] {
  const groups = parseClusterGroups(randomOrder, courseCps.length);
  const cols: SplitCol[] = courseCps.map((cp, index) => ({
    cp,
    label: groups[index] > 0 ? `${index + 1} [${cp}]` : `${index + 1} (${cp})`,
    finish: false,
  }));
  if (finishCp != null) {
    cols.push({ cp: finishCp, label: "Финиш", finish: true });
  }
  return cols;
}

function findCpFrom(marks: SplitMark[], from: number, cp: number): number {
  for (let i = from; i < marks.length; i += 1) {
    if (marks[i].cp_number === cp) return i;
  }
  return -1;
}

function timesAlongCourse(
  marksRaw: unknown,
  startCp: number | null,
  cols: SplitCol[],
): Array<{ splitSec: number; cumSec: number } | null> {
  const marks = asMarks(marksRaw);
  let startIdx = startCp != null ? marks.findIndex((mark) => mark.cp_number === startCp) : 0;
  if (startIdx < 0) startIdx = 0;
  if (!marks.length || !cols.length) return cols.map(() => null);
  const t0 = parseMarkMs(marks[startIdx].mark_time);
  if (t0 == null) return cols.map(() => null);
  const out: Array<{ splitSec: number; cumSec: number } | null> = [];
  let i = startIdx + 1;
  let prevCum = 0;
  for (const col of cols) {
    const found = findCpFrom(marks, i, col.cp);
    if (found < 0) {
      out.push(null);
      continue;
    }
    i = found + 1;
    const t = parseMarkMs(marks[found].mark_time);
    if (t == null) {
      out.push(null);
      continue;
    }
    let cumSec = Math.round((t - t0) / 1000);
    let splitSec = cumSec - prevCum;
    // Archive CSV can roll the calendar day when a leftover punch is earlier
    // than start; drop 24h wraps so the next timed CP still has a real split.
    while (splitSec > 12 * 3600) {
      cumSec -= 24 * 3600;
      splitSec = cumSec - prevCum;
    }
    if (cumSec < 0 || splitSec < 0) {
      out.push(null);
      continue;
    }
    prevCum = cumSec;
    out.push({ splitSec, cumSec });
  }
  return out;
}

function rankTimes(values: Array<number | null>): Array<number | null> {
  const indexed = values
    .map((value, index) => ({ value, index }))
    .filter((item): item is { value: number; index: number } => item.value != null);
  indexed.sort((a, b) => a.value - b.value);
  const places: Array<number | null> = values.map(() => null);
  let place = 1;
  for (let i = 0; i < indexed.length; i += 1) {
    if (i > 0 && indexed[i].value !== indexed[i - 1].value) place = i + 1;
    places[indexed[i].index] = place;
  }
  return places;
}

export function formatRaceTime(seconds: number | null | undefined): string {
  if (seconds == null || !Number.isFinite(seconds)) return "—";
  const s = Math.max(0, Math.round(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  return `${h}:${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}`;
}

export function formatSplitMmSs(seconds: number | null | undefined): string {
  if (seconds == null || !Number.isFinite(seconds)) return "—";
  const s = Math.max(0, Math.round(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (h > 0) return `${h}:${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}`;
  return `${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}`;
}

export function formatPaceMinPerKm(meters: number | null | undefined, seconds: number | null | undefined): string | null {
  if (meters == null || meters <= 0 || seconds == null || seconds <= 0) return null;
  const secPerKm = Math.max(0, Math.round((seconds * 1000) / meters));
  const min = Math.floor(secPerKm / 60);
  const sec = secPerKm % 60;
  return `${min}:${String(sec).padStart(2, "0")}/км`;
}

export function mapStraightMeters(
  fromCp: number,
  toCp: number,
  positions: Record<number, { x: number; y: number }>,
  metersPerUnit: number | null | undefined,
): number | null {
  if (metersPerUnit == null || !(metersPerUnit > 0)) return null;
  const a = positions[fromCp];
  const b = positions[toCp];
  if (!a || !b) return null;
  const meters = Math.hypot(a.x - b.x, a.y - b.y) * metersPerUnit;
  return Number.isFinite(meters) && meters > 0 ? Math.round(meters) : null;
}

export function courseLegMeters(
  controls: number[],
  legMeters: Array<number | null | undefined>,
  fromCp: number,
  toCp: number,
  startCp: number,
  finishCp: number,
  randomOrder: unknown = [],
  positions: Record<number, { x: number; y: number }> = {},
  metersPerUnit: number | null | undefined = null,
): number | null {
  if (legMeters.length) {
    const seq = [startCp, ...controls, finishCp];
    const groups = [0, ...parseClusterGroups(randomOrder, controls.length), 0];
    // Любое соседство в опубликованном порядке (в т.ч. повтор КП на дистанции).
    for (let i = 0; i < seq.length - 1; i += 1) {
      if (seq[i] === fromCp && seq[i + 1] === toCp) {
        const meters = Number(legMeters[i]);
        if (Number.isFinite(meters) && meters > 0) return meters;
      }
    }
    if (seq.includes(fromCp) && seq.includes(toCp)) {
      const segments = courseSegments(seq, groups);
      let idx = 0;
      for (let i = 0; i < segments.length - 1; i += 1) {
        const a = segments[i];
        const b = segments[i + 1];
        if (
          a.kind === "fixed" &&
          b.kind === "cluster" &&
          a.cp === fromCp &&
          b.cps.includes(toCp)
        ) {
          const meters = Number(legMeters[idx]);
          if (Number.isFinite(meters) && meters > 0) return meters;
        }
        if (
          a.kind === "cluster" &&
          b.kind === "fixed" &&
          a.cps.includes(fromCp) &&
          b.cp === toCp
        ) {
          const meters = Number(legMeters[idx + a.cps.length - 1]);
          if (Number.isFinite(meters) && meters > 0) return meters;
        }
        if (
          a.kind === "cluster" &&
          b.kind === "cluster" &&
          a.cps.includes(fromCp) &&
          b.cps.includes(toCp)
        ) {
          const meters = Number(legMeters[idx + a.cps.length - 1]);
          if (Number.isFinite(meters) && meters > 0) return meters;
        }
        idx += a.kind === "fixed" ? 1 : a.cps.length;
      }
    }
  }
  return mapStraightMeters(fromCp, toCp, positions, metersPerUnit);
}

export function hasCourseDistances(
  lengthMeters: number | null | undefined,
  legMeters: Array<number | null | undefined>,
  metersPerUnit: number | null | undefined = null,
  positionCount = 0,
): boolean {
  if (lengthMeters != null && lengthMeters > 0) return true;
  if (legMeters.some((m) => m != null && Number(m) > 0)) return true;
  return metersPerUnit != null && metersPerUnit > 0 && positionCount >= 2;
}

function orientDiagnosticLabel(code: string): string {
  if (code === "missing_course") return "нет дистанции";
  if (code === "course_empty") return "дистанция без КП";
  if (code === "course_unknown") return "дистанция не найдена";
  if (code === "start_cp_not_configured") return "не задана стартовая станция";
  if (code === "start_time_not_configured") return "не задано общее время старта";
  if (code === "no_marks") return "нет отметок";
  if (code === "start_missing") return "нет отметки старта";
  if (code === "finish_missing") return "нет отметки финиша";
  if (code === "finish_not_last") return "финиш не последняя отметка";
  if (code === "course_incomplete") return "дистанция пройдена не полностью";
  if (code === "overtime") return "превышение контрольного времени";
  if (code === "did_not_finish") return "не финишировал";
  return code;
}

export function resultStatusLine(status: string, diagnostics: string[] = []): string {
  const reasons = [...new Set(diagnostics.map(orientDiagnosticLabel).filter(Boolean))];
  const s = status.trim();
  if (s === "OK") return "OK";
  if (s === "Дисквалификация" || s === "DSQ") {
    const reason = reasons.length ? reasons : ["превышение лимита времени"];
    return `Дисквалификация: ${reason.join(", ")}`;
  }
  if (s === "Не стартовал" || s === "DNS") {
    const reason = reasons.length ? reasons : ["не стартовал"];
    return `Не стартовал: ${reason.join(", ")}`;
  }
  if (s === "DNF" || s === "Не финишировал") return "Не финишировал";
  if (s === "Ошибка") {
    const reason = reasons.length ? reasons : ["ошибка"];
    return `Ошибка: ${reason.join(", ")}`;
  }
  return reasons.length ? `${s}: ${reasons.join(", ")}` : s;
}

export function asOptionalIntList(raw: unknown): Array<number | null> {
  if (!Array.isArray(raw)) return [];
  return raw.map((value) => {
    if (value == null || value === "") return null;
    const n = Number(value);
    return Number.isFinite(n) && n > 0 ? n : null;
  });
}

export function formatCpParen(cp: number, added = false): string {
  const body = `(${cp})`;
  return added ? `${body}*` : body;
}

export function asAddedCpCounts(raw: unknown): Map<number, number> {
  const out = new Map<number, number>();
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return out;
  for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
    const cp = Number(key);
    const count = Number(value);
    if (!Number.isFinite(cp) || cp <= 0 || !Number.isFinite(count) || count <= 0) continue;
    out.set(cp, Math.floor(count));
  }
  return out;
}

function syntheticMarkTime(prev: SplitMark | undefined, next: SplitMark | undefined): string {
  const prevMs = prev ? parseMarkMs(prev.mark_time) : null;
  const nextMs = next ? parseMarkMs(next.mark_time) : null;
  if (prevMs != null && nextMs != null && nextMs > prevMs) {
    return new Date(prevMs + Math.floor((nextMs - prevMs) / 2)).toISOString().slice(0, 19);
  }
  if (prev?.mark_time) return prev.mark_time;
  if (next?.mark_time) return next.mark_time;
  return "";
}

/**
 * Вставляет в список отметок КП из added_cps (как на телефоне) и помечает их added.
 * Если отметки уже есть в marks — только ставит флаг added.
 */
export function expandMarksWithAddedCps(
  marks: SplitMark[],
  courseCps: number[],
  addCounts: Map<number, number>,
  startCp: number | null,
  finishCp: number | null,
  randomOrder: unknown = [],
): SplitMark[] {
  if (!addCounts.size) {
    return marks.map((mark) => ({ ...mark, added: mark.added === true }));
  }

  const remaining = new Map(
    [...addCounts.entries()].filter(([, n]) => n > 0) as Array<[number, number]>,
  );
  if (![...remaining.values()].some((n) => n > 0)) {
    return marks.map((mark) => ({ ...mark, added: mark.added === true }));
  }

  const result = marks.map((mark) => ({ ...mark, added: mark.added === true }));
  let p = 0;
  if (result[0]?.cp_number === startCp && courseCps[0] !== startCp) {
    p = 1;
  }

  const insertAt = (cp: number, atHint: number): number => {
    let at = atHint;
    let finishAt = -1;
    for (let i = result.length - 1; i >= 0; i -= 1) {
      if (result[i].cp_number === finishCp) {
        finishAt = i;
        break;
      }
    }
    if (finishAt >= 0 && at >= finishAt && cp !== finishCp) at = finishAt;
    result.splice(at, 0, {
      cp_number: cp,
      mark_time: syntheticMarkTime(result[at - 1], result[at]),
      added: true,
    });
    return at + 1;
  };

  const segments = courseSegments(courseCps, randomOrder);
  for (let segIdx = 0; segIdx < segments.length; segIdx += 1) {
    const seg = segments[segIdx];
    if (seg.kind === "fixed") {
      let q = p;
      while (
        q < result.length &&
        (result[q].cp_number !== seg.cp ||
          (result[q].cp_number === finishCp && seg.cp !== finishCp))
      ) {
        q += 1;
      }
      if (q < result.length) {
        p = q + 1;
        continue;
      }
      const left = remaining.get(seg.cp) ?? 0;
      if (left <= 0) break;
      remaining.set(seg.cp, left - 1);
      p = insertAt(seg.cp, p);
      continue;
    }
    const need = new Map<number, number>();
    for (const cp of seg.cps) need.set(cp, (need.get(cp) ?? 0) + 1);
    while ([...need.values()].some((n) => n > 0)) {
      let foundIdx = -1;
      let foundCp = 0;
      let q = p;
      while (q < result.length) {
        const cp = result[q].cp_number;
        if ((need.get(cp) ?? 0) > 0) {
          foundIdx = q;
          foundCp = cp;
          break;
        }
        q += 1;
      }
      if (foundIdx >= 0) {
        need.set(foundCp, (need.get(foundCp) ?? 1) - 1);
        p = foundIdx + 1;
        continue;
      }
      let usedAdd = false;
      for (const [cp, count] of need) {
        if (count <= 0) continue;
        const left = remaining.get(cp) ?? 0;
        if (left <= 0) continue;
        remaining.set(cp, left - 1);
        need.set(cp, count - 1);
        p = insertAt(cp, p);
        usedAdd = true;
        break;
      }
      if (!usedAdd) break;
    }
  }

  const flagLeft = new Map(addCounts);
  for (const mark of result) {
    const left = flagLeft.get(mark.cp_number) ?? 0;
    if (left > 0) {
      mark.added = true;
      flagLeft.set(mark.cp_number, left - 1);
    }
  }

  return result.map((mark, index) => ({
    ...mark,
    seq: mark.seq && mark.seq > 0 ? mark.seq : index + 1,
  }));
}

export function buildSplitCells(
  rows: { marks?: unknown }[],
  cols: SplitCol[],
  startCp: number | null,
): Array<Array<SplitCell | null>> {
  const times = rows.map((row) => timesAlongCourse(row.marks, startCp, cols));
  const splitPlaces = cols.map((_, colIndex) =>
    rankTimes(times.map((item) => item[colIndex]?.splitSec ?? null)),
  );
  const cumPlaces = cols.map((_, colIndex) =>
    rankTimes(times.map((item) => item[colIndex]?.cumSec ?? null)),
  );
  return times.map((row, rowIndex) =>
    row.map((cell, colIndex) => {
      if (!cell) return null;
      return {
        splitSec: cell.splitSec,
        cumSec: cell.cumSec,
        splitPlace: splitPlaces[colIndex]?.[rowIndex] ?? null,
        cumPlace: cumPlaces[colIndex]?.[rowIndex] ?? null,
      };
    }),
  );
}
