export type SplitMark = {
  cp_number: number;
  mark_time: string;
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

function asMarks(raw: unknown): SplitMark[] {
  if (!Array.isArray(raw)) return [];
  const out: SplitMark[] = [];
  for (const item of raw) {
    if (!item || typeof item !== "object") continue;
    const row = item as { cp_number?: unknown; mark_time?: unknown };
    const cp = Number(row.cp_number);
    if (!Number.isFinite(cp)) continue;
    out.push({ cp_number: cp, mark_time: String(row.mark_time ?? "") });
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

function punchWindow(marks: SplitMark[], startCp: number | null, finishCp: number | null) {
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
};

type PunchInput = {
  seq?: number;
  cp_number: number;
  mark_time?: string;
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
  };
}

function emitMissed(cp: number): ClassifiedPunch {
  return { kind: "missed", cp, seq: null, mark_time: "" };
}

export function classifyCoursePunches(
  marks: PunchInput[],
  courseCps: number[],
  startCp: number | null,
  finishCp: number | null,
): ClassifiedPunch[] {
  if (!marks.length) {
    return courseCps.map(emitMissed);
  }
  const { startIdx, endIdx } = markWindowIdx(marks, startCp, finishCp);
  const remaining = courseCps.slice();
  const out: ClassifiedPunch[] = [];
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
      remaining.forEach((miss) => out.push(emitMissed(miss)));
      remaining.length = 0;
      out.push(emitPunch(mark, "finish"));
      continue;
    }
    if (!courseCps.length) {
      out.push(emitPunch(mark, "ok"));
      continue;
    }
    const idx = remaining.indexOf(cp);
    if (idx === 0) {
      remaining.shift();
      out.push(emitPunch(mark, "ok"));
      continue;
    }
    if (idx > 0) {
      remaining.splice(0, idx).forEach((miss) => out.push(emitMissed(miss)));
      remaining.shift();
      out.push(emitPunch(mark, "ok"));
      continue;
    }
    out.push(emitPunch(mark, courseCps.includes(cp) ? "out_of_order" : "extra"));
  }
  remaining.forEach((miss) => out.push(emitMissed(miss)));
  return out;
}

export function splitColumns(
  courseCps: number[],
  finishCp: number | null,
): SplitCol[] {
  const cols: SplitCol[] = courseCps.map((cp, index) => ({
    cp,
    label: `${index + 1} (${cp})`,
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
