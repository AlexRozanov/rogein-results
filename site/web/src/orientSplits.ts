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
    const time = String(row.mark_time ?? "");
    if (!Number.isFinite(cp) || !time) continue;
    out.push({ cp_number: cp, mark_time: time });
  }
  return out;
}

function asNumberList(raw: unknown): number[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((value) => Number(value)).filter((value) => Number.isFinite(value));
}

function parseMarkMs(raw: string): number | null {
  const normalized = raw.trim().replace(" ", "T");
  const ms = Date.parse(normalized);
  return Number.isFinite(ms) ? ms : null;
}

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
  rows: { place?: number | null; status?: string; marks?: unknown }[],
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
  let missed = false;
  for (const col of cols) {
    if (missed) {
      out.push(null);
      continue;
    }
    while (i < marks.length && marks[i].cp_number !== col.cp) i += 1;
    if (i >= marks.length) {
      missed = true;
      out.push(null);
      continue;
    }
    const t = parseMarkMs(marks[i].mark_time);
    i += 1;
    if (t == null) {
      missed = true;
      out.push(null);
      continue;
    }
    const cumSec = Math.max(0, Math.round((t - t0) / 1000));
    const splitSec = Math.max(0, cumSec - prevCum);
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
