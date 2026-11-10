import {
  asMarks,
  courseLegMeters,
  formatPaceMinPerKm,
  formatSplitMmSs,
  parseMarkMs,
  punchWindow,
  wrapDeltaSec,
} from "./orientSplits";

export type MapMetric = {
  width: number;
  height: number;
  metersPerPixel: number;
  points: Array<{ x: number; y: number; cp: number | null; kind: string | null }>;
};

export type RoutePeer = {
  sourceId: string;
  marks: unknown;
};

export type RouteLegRow = {
  n: number;
  fromCp: number;
  toCp: number;
  fromLabel: string;
  toLabel: string;
  splitSec: number;
  bestSec: number | null;
  lossSec: number | null;
  legPlace: number | null;
  legCount: number;
  meters: number | null;
  kmh: number | null;
  pace: string | null;
  pointsAdded: number;
  pointsPerMin: number | null;
  cumSec: number;
  cumMeters: number | null;
  cumPoints: number | null;
  place: number | null;
  placeDelta: number | null;
};

export type RouteStats = {
  rows: RouteLegRow[];
  hasDistance: boolean;
  hasPoints: boolean;
};

function cpLabel(cp: number, startCp: number | null, finishCp: number | null): string {
  if (startCp != null && cp === startCp) return "Старт";
  if (finishCp != null && cp === finishCp) return "Финиш";
  return String(cp);
}

function timedWindow(raw: unknown, startCp: number | null, finishCp: number | null) {
  const window = punchWindow(asMarks(raw), startCp, finishCp);
  const out: Array<{ cp: number; ms: number }> = [];
  for (const mark of window) {
    const ms = parseMarkMs(mark.mark_time);
    if (ms == null) continue;
    out.push({ cp: mark.cp_number, ms });
  }
  return out;
}

function pairKey(from: number, to: number) {
  return `${from}>${to}`;
}

function lookupXy(
  cp: number,
  startCp: number | null,
  finishCp: number | null,
  map: MapMetric | null,
): { x: number; y: number } | null {
  if (!map) return null;
  let start: { x: number; y: number } | null = null;
  let finish: { x: number; y: number } | null = null;
  let byCp: { x: number; y: number } | null = null;
  for (const point of map.points) {
    if (point.cp === cp) byCp = { x: point.x, y: point.y };
    if (point.kind === "start") start = { x: point.x, y: point.y };
    if (point.kind === "finish") finish = { x: point.x, y: point.y };
  }
  if (byCp) return byCp;
  if (startCp != null && cp === startCp) return start;
  if (finishCp != null && cp === finishCp) return finish;
  return null;
}

function segmentMeters(
  from: { x: number; y: number },
  to: { x: number; y: number },
  map: MapMetric,
): number | null {
  const meters =
    Math.hypot((to.x - from.x) * map.width, (to.y - from.y) * map.height) * map.metersPerPixel;
  return Number.isFinite(meters) && meters >= 0 ? meters : null;
}

function punchVertices(
  timed: Array<{ cp: number }>,
  startCp: number | null,
  finishCp: number | null,
  map: MapMetric | null,
): Array<{ x: number; y: number } | null> {
  return timed.map((mark) => lookupXy(mark.cp, startCp, finishCp, map));
}

/** One segment per punch pair, so the legs sum to the same path length as the map. */
function legMetersList(
  vertices: Array<{ x: number; y: number } | null>,
  map: MapMetric | null,
): Array<number | null> {
  if (!map || vertices.length < 2) return vertices.slice(1).map(() => null);
  const out: Array<number | null> = [];
  let lastMapped = vertices[0];
  for (let i = 1; i < vertices.length; i += 1) {
    const to = vertices[i];
    if (to == null) {
      out.push(lastMapped != null ? 0 : null);
      continue;
    }
    const from = vertices[i - 1] ?? lastMapped;
    if (from == null) {
      out.push(null);
      lastMapped = to;
      continue;
    }
    out.push(segmentMeters(from, to, map));
    lastMapped = to;
  }
  return out;
}

function pointsOf(cp: number, startCp: number | null, finishCp: number | null): number {
  if (startCp != null && cp === startCp) return 0;
  if (finishCp != null && cp === finishCp) return 0;
  return Math.floor(cp / 10);
}

function scoreAtElapsed(
  timed: Array<{ cp: number; ms: number }>,
  elapsed: number,
  startCp: number | null,
  finishCp: number | null,
  rogaine: boolean,
): { points: number; controls: number; reachedAt: number } {
  if (!timed.length) return { points: 0, controls: 0, reachedAt: Number.POSITIVE_INFINITY };
  const t0 = timed[0].ms;
  const seen = new Set<number>();
  let points = 0;
  let controls = 0;
  let reachedAt = 0;
  for (const mark of timed) {
    const fromStart = wrapDeltaSec(t0, mark.ms);
    if (fromStart == null || fromStart > elapsed) continue;
    reachedAt = fromStart;
    if (startCp != null && mark.cp === startCp) continue;
    if (finishCp != null && mark.cp === finishCp) continue;
    if (seen.has(mark.cp)) continue;
    seen.add(mark.cp);
    controls += 1;
    if (rogaine) points += pointsOf(mark.cp, startCp, finishCp);
  }
  return { points, controls, reachedAt };
}

function isAhead(
  score: { points: number; controls: number; reachedAt: number },
  self: { points: number; controls: number; reachedAt: number },
  rogaine: boolean,
): boolean {
  if (rogaine && score.points !== self.points) return score.points > self.points;
  if (score.controls !== self.controls) return score.controls > self.controls;
  return score.reachedAt < self.reachedAt;
}

function rankAtElapsed(
  selfElapsed: number,
  selfScore: { points: number; controls: number; reachedAt: number },
  field: Array<Array<{ cp: number; ms: number }>>,
  startCp: number | null,
  finishCp: number | null,
  rogaine: boolean,
): number {
  let better = 0;
  for (const other of field) {
    const score = scoreAtElapsed(other, selfElapsed, startCp, finishCp, rogaine);
    if (isAhead(score, selfScore, rogaine)) better += 1;
  }
  return better + 1;
}

function collectPairs(timed: Array<{ cp: number; ms: number }>) {
  const out: Array<{ from: number; to: number; splitSec: number; cumSec: number; at: number }> = [];
  for (let i = 1; i < timed.length; i += 1) {
    const split = wrapDeltaSec(timed[i - 1].ms, timed[i].ms);
    const cum = wrapDeltaSec(timed[0].ms, timed[i].ms);
    if (split == null || cum == null) continue;
    out.push({ from: timed[i - 1].cp, to: timed[i].cp, splitSec: split, cumSec: cum, at: i });
  }
  return out;
}

export function buildRouteStats(opts: {
  selfMarks: unknown;
  peers: RoutePeer[];
  selfSourceId: string;
  startCp: number | null;
  finishCp: number | null;
  rogaine: boolean;
  map: MapMetric | null;
  courseControls?: number[];
  legMeters?: Array<number | null>;
}): RouteStats | null {
  const timed = timedWindow(opts.selfMarks, opts.startCp, opts.finishCp);
  const pairs = collectPairs(timed);
  if (!pairs.length) return null;

  const peerTimed = opts.peers
    .filter((peer) => String(peer.sourceId) !== opts.selfSourceId)
    .map((peer) => timedWindow(peer.marks, opts.startCp, opts.finishCp));
  const allTimed = [timed, ...peerTimed];
  const splitsByPair = new Map<string, number[]>();
  for (const person of allTimed) {
    for (const pair of collectPairs(person)) {
      const key = pairKey(pair.from, pair.to);
      const list = splitsByPair.get(key) ?? [];
      list.push(pair.splitSec);
      splitsByPair.set(key, list);
    }
  }

  const rows: RouteLegRow[] = [];
  let cumMeters = 0;
  let hasCumMeters = false;
  let cumPoints = 0;
  const seenCps = new Set<number>();
  let prevPlace: number | null = null;
  const startCp = opts.startCp;
  const finishCp = opts.finishCp;
  const selfVertices = punchVertices(timed, startCp, finishCp, opts.map);
  const selfLegMeters = legMetersList(selfVertices, opts.map);
  let prevAt = 0;

  pairs.forEach((pair, index) => {
    const key = pairKey(pair.from, pair.to);
    const splits = (splitsByPair.get(key) ?? [pair.splitSec]).slice().sort((a, b) => a - b);
    const bestSec = splits[0] ?? null;
    const faster = splits.filter((sec) => sec < pair.splitSec).length;
    const xmlMeters =
      !opts.rogaine && opts.courseControls && opts.legMeters
        ? courseLegMeters(
            opts.courseControls,
            opts.legMeters,
            pair.from,
            pair.to,
            startCp ?? 0,
            finishCp ?? 0,
          )
        : null;
    let meters = xmlMeters;
    if (meters == null) {
      let sum: number | null = 0;
      for (let i = prevAt; i < pair.at; i += 1) {
        const part = selfLegMeters[i];
        if (part == null) {
          sum = null;
          break;
        }
        sum += part;
      }
      meters = sum;
    }
    prevAt = pair.at;
    if (meters != null) {
      meters = Math.round(meters / 10) * 10;
      cumMeters += meters;
      hasCumMeters = true;
    }
    const hours = pair.splitSec / 3600;
    const kmh = meters != null && hours > 0 ? meters / 1000 / hours : null;
    const pace = formatPaceMinPerKm(meters, pair.splitSec);
    let pointsAdded = 0;
    if (opts.rogaine && !seenCps.has(pair.to)) {
      pointsAdded = pointsOf(pair.to, startCp, finishCp);
      if (pointsAdded > 0) seenCps.add(pair.to);
    }
    if (opts.rogaine) cumPoints += pointsAdded;
    const pointsPerMin =
      pointsAdded > 0 && pair.splitSec > 0 ? pointsAdded / (pair.splitSec / 60) : null;
    const snapshot = scoreAtElapsed(timed, pair.cumSec, startCp, finishCp, opts.rogaine);
    const place = rankAtElapsed(pair.cumSec, snapshot, allTimed, startCp, finishCp, opts.rogaine);
    const placeDelta = prevPlace == null ? null : prevPlace - place;
    prevPlace = place;
    rows.push({
      n: index + 1,
      fromCp: pair.from,
      toCp: pair.to,
      fromLabel: cpLabel(pair.from, startCp, finishCp),
      toLabel: cpLabel(pair.to, startCp, finishCp),
      splitSec: pair.splitSec,
      bestSec,
      lossSec: bestSec != null && pair.splitSec > bestSec ? pair.splitSec - bestSec : null,
      legPlace: faster + 1,
      legCount: splits.length,
      meters,
      kmh,
      pace,
      pointsAdded,
      pointsPerMin,
      cumSec: pair.cumSec,
      cumMeters: hasCumMeters ? cumMeters : null,
      cumPoints: opts.rogaine ? cumPoints : null,
      place,
      placeDelta,
    });
  });

  return {
    rows,
    hasDistance: rows.some((row) => row.meters != null),
    hasPoints: opts.rogaine,
  };
}

export function formatLoss(seconds: number | null | undefined): string {
  if (seconds == null || seconds <= 0) return "—";
  return `+${formatSplitMmSs(seconds)}`;
}

export function formatKm(meters: number | null | undefined): string {
  if (meters == null || !Number.isFinite(meters) || meters < 0) return "—";
  return (meters / 1000).toFixed(2);
}

export function formatKmh(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value) || value <= 0) return "—";
  return value.toFixed(1);
}

export function formatPointsPerMin(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value) || value <= 0) return "—";
  return value.toFixed(2);
}
