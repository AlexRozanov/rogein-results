<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import CourseMapView from "../components/CourseMapView.vue";
import PageLoading from "../components/PageLoading.vue";

type PathPoint = {
  x: number;
  y: number;
  cp_number?: number | null;
  kind?: string | null;
};

type Mark = { seq: number; cp_number: number; mark_time: string };

type Participant = {
  id: number;
  source_id: number;
  bib: string;
  name: string;
  marks: Mark[];
  path: unknown;
  distance_m?: number | null;
  points_final?: number | null;
  elapsed_seconds?: number | null;
};

type AwardGroup = {
  id: number;
  name: string;
  course_cps?: number[] | null;
};

type ResultRow = {
  award_group_id: number;
  participant_source_id: number;
  points_final: number;
  elapsed_seconds: number;
  status?: string | null;
  diagnostics?: string[] | null;
};

type EventDetail = {
  slug: string;
  title: string;
  map_url: string | null;
  map_width?: number | null;
  map_height?: number | null;
  meters_per_pixel?: number | null;
  sport_kind?: string;
  start_cp?: number | null;
  finish_cp?: number | null;
  award_groups: AwardGroup[];
  results: ResultRow[];
};

type CourseProgressItem = {
  cp: number;
  taken: boolean;
  punched: boolean;
  blocker: boolean;
};

const route = useRoute();
const person = ref<Participant | null>(null);
const event = ref<EventDetail | null>(null);
const error = ref("");
const loading = ref(true);

const personResults = computed(() => {
  if (!person.value || !event.value) return [];
  return event.value.results.filter((row) => row.participant_source_id === person.value!.source_id);
});

const selectedResult = computed(() => {
  const rows = personResults.value;
  if (!rows.length) return null;
  const groupId = Number(route.query.g);
  if (Number.isFinite(groupId) && groupId > 0) {
    return rows.find((row) => row.award_group_id === groupId) ?? rows[0];
  }
  return rows[0];
});

const awardGroupName = computed(() => {
  if (!event.value) return "—";
  const selected = selectedResult.value;
  if (selected) {
    return event.value.award_groups.find((group) => group.id === selected.award_group_id)?.name || "—";
  }
  const names = personResults.value
    .map((row) => event.value!.award_groups.find((group) => group.id === row.award_group_id)?.name)
    .filter((name): name is string => Boolean(name));
  return names.length ? [...new Set(names)].join(", ") : "—";
});

const isOrient = computed(() => event.value?.sport_kind === "orient");
const pathPoints = computed<PathPoint[]>(() => normalizePath(person.value?.path));
const selectedStatus = computed(() => selectedResult.value?.status || "OK");
const selectedGroup = computed(() =>
  event.value?.award_groups.find((group) => group.id === selectedResult.value?.award_group_id) ?? null,
);
const courseControls = computed(() => asNumberList(selectedGroup.value?.course_cps));
const diagnostics = computed(() => asStringList(selectedResult.value?.diagnostics));

const courseProgress = computed(() => {
  const required = courseControls.value;
  if (!required.length) return [] as CourseProgressItem[];
  const punches = punchWindow(person.value?.marks ?? [], event.value?.start_cp, event.value?.finish_cp);
  const punched = new Set(punches);
  let i = 0;
  let firstMiss = true;
  return required.map((cp) => {
    while (i < punches.length && punches[i] !== cp) i += 1;
    const taken = i < punches.length && punches[i] === cp;
    if (taken) i += 1;
    const blocker = !taken && firstMiss;
    if (!taken) firstMiss = false;
    return { cp, taken, punched: punched.has(cp), blocker };
  });
});

const missingCourseCps = computed(() => courseProgress.value.filter((item) => !item.taken));
const firstMissedCp = computed(() => courseProgress.value.find((item) => item.blocker)?.cp ?? null);
const outOfOrderCps = computed(() =>
  courseProgress.value.filter((item) => !item.taken && item.punched).map((item) => item.cp),
);

const courseError = computed(() => {
  if (!isOrient.value || selectedStatus.value === "OK") return null;
  const labels = diagnostics.value.map(diagnosticLabel);
  const messages = labels.length ? labels : fallbackStatusMessage(selectedStatus.value);
  return {
    diagnosticsText: labels.length ? labels.join(", ") : messages.join(", "),
    firstMissedCp: firstMissedCp.value,
    missingMore: missingCourseCps.value.length > 1,
    progress: courseProgress.value,
    showProgress: courseProgress.value.some((item) => !item.taken),
  };
});

const wrongMarkSeqs = computed(() => {
  const wrong = new Set<number>();
  if (!isOrient.value || selectedStatus.value === "OK") return wrong;
  if (!outOfOrderCps.value.length && !diagnostics.value.includes("course_incomplete")) return wrong;
  const marks = person.value?.marks ?? [];
  const required = courseControls.value;
  if (!marks.length || !required.length) return wrong;
  const { startIdx, endIdx } = markWindow(marks, event.value?.start_cp, event.value?.finish_cp);
  const consumed = consumedMarkSeqs(marks, required, startIdx, endIdx);
  const remaining = new Set(outOfOrderCps.value);
  for (let i = startIdx; i <= endIdx; i += 1) {
    const mark = marks[i];
    if (!mark) continue;
    if (consumed.has(mark.seq)) continue;
    if (remaining.has(mark.cp_number)) wrong.add(mark.seq);
  }
  return wrong;
});

const resolvedDistanceM = computed(() => {
  const stored = person.value?.distance_m;
  if (stored != null && Number.isFinite(stored) && stored > 0) return stored;
  return pathDistanceM(pathPoints.value, event.value);
});

const metrics = computed(() => {
  const result = selectedResult.value;
  const points = result?.points_final ?? person.value?.points_final ?? null;
  const elapsed = result?.elapsed_seconds ?? person.value?.elapsed_seconds ?? null;
  const km = distanceKm(resolvedDistanceM.value);
  const hours = elapsed != null && elapsed > 0 ? elapsed / 3600 : null;
  const speed = km != null && hours != null && hours > 0 ? km / hours : null;
  const pace = km != null && elapsed != null && elapsed > 0 && km > 0 ? elapsed / km : null;
  const pointsPerKm = km != null && points != null && km > 0 ? points / km : null;
  return { points, elapsed, pointsPerKm, pace, speed };
});

function pathDistanceM(path: PathPoint[], ev: EventDetail | null) {
  if (!ev || path.length < 2) return null;
  const w = Number(ev.map_width);
  const h = Number(ev.map_height);
  const mpp = Number(ev.meters_per_pixel);
  if (!(w > 0) || !(h > 0) || !(mpp > 0)) return null;
  let distance = 0;
  for (let i = 0; i < path.length - 1; i += 1) {
    const dx = (path[i + 1].x - path[i].x) * w;
    const dy = (path[i + 1].y - path[i].y) * h;
    distance += Math.hypot(dx, dy) * mpp;
  }
  return Number.isFinite(distance) && distance > 0 ? distance : null;
}

function distanceKm(meters: number | null | undefined) {
  if (meters == null || !Number.isFinite(meters) || meters <= 0) return null;
  return meters / 1000;
}

function normalizePath(raw: unknown): PathPoint[] {
  if (!Array.isArray(raw)) return [];
  const out: PathPoint[] = [];
  for (const item of raw) {
    if (Array.isArray(item) && item.length >= 2) {
      const x = Number(item[0]);
      const y = Number(item[1]);
      if (Number.isFinite(x) && Number.isFinite(y)) out.push({ x, y });
      continue;
    }
    if (item && typeof item === "object" && "x" in item && "y" in item) {
      const row = item as { x: unknown; y: unknown; cp_number?: unknown; kind?: unknown };
      const x = Number(row.x);
      const y = Number(row.y);
      if (!Number.isFinite(x) || !Number.isFinite(y)) continue;
      out.push({
        x,
        y,
        cp_number: row.cp_number == null ? null : Number(row.cp_number),
        kind: typeof row.kind === "string" ? row.kind : null,
      });
    }
  }
  return out;
}

function fmtHms(total: number | null | undefined) {
  if (total == null || !Number.isFinite(total)) return "—";
  const s = Math.max(0, total | 0);
  const hh = String(Math.floor(s / 3600)).padStart(2, "0");
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, "0");
  const ss = String(s % 60).padStart(2, "0");
  return `${hh}:${mm}:${ss}`;
}

function fmtNum(value: number | null | undefined, digits = 1) {
  if (value == null || !Number.isFinite(value)) return "—";
  return value.toFixed(digits);
}

function fmtPace(secPerKm: number | null | undefined) {
  if (secPerKm == null || !Number.isFinite(secPerKm) || secPerKm <= 0) return "—";
  const total = Math.round(secPerKm);
  const mm = Math.floor(total / 60);
  const ss = String(total % 60).padStart(2, "0");
  return `${mm}:${ss} /км`;
}

function fmtMarkTime(raw: string) {
  const part = raw.includes("T") ? raw.split("T")[1] : raw.includes(" ") ? raw.split(" ")[1] : raw;
  return (part || raw).slice(0, 8);
}

function asNumberList(raw: unknown): number[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((value) => Number(value)).filter((value) => Number.isFinite(value));
}

function asStringList(raw: unknown): string[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((value) => String(value)).filter((value) => value.length > 0);
}

function markWindow(marks: Mark[], startCp: number | null | undefined, finishCp: number | null | undefined) {
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

function punchWindow(marks: Mark[], startCp: number | null | undefined, finishCp: number | null | undefined) {
  const { startIdx, endIdx } = markWindow(marks, startCp, finishCp);
  if (endIdx < startIdx) return [];
  return marks.slice(startIdx, endIdx + 1).map((mark) => mark.cp_number);
}

function consumedMarkSeqs(marks: Mark[], required: number[], startIdx: number, endIdx: number) {
  const consumed = new Set<number>();
  let i = startIdx;
  for (const cp of required) {
    while (i <= endIdx && marks[i]?.cp_number !== cp) i += 1;
    if (i <= endIdx && marks[i]?.cp_number === cp) {
      consumed.add(marks[i].seq);
      i += 1;
      continue;
    }
    break;
  }
  return consumed;
}

function diagnosticLabel(code: string) {
  if (code === "missing_course") return "Нет дистанции";
  if (code === "course_empty") return "Дистанция без КП";
  if (code === "course_unknown") return "Дистанция не найдена";
  if (code === "start_cp_not_configured") return "Не задана стартовая станция";
  if (code === "no_marks") return "Нет отметок";
  if (code === "start_missing") return "Нет отметки старта";
  if (code === "finish_missing") return "Нет отметки финиша";
  if (code === "course_incomplete") return "Дистанция пройдена не полностью";
  if (code === "overtime") return "Превышение контрольного времени";
  return code;
}

function fallbackStatusMessage(status: string) {
  if (status === "Не стартовал") return ["Нет отметки старта"];
  if (status === "DNF") return ["Не финишировал"];
  if (status === "Дисквалификация") return ["Дистанция пройдена не полностью"];
  if (status === "Ошибка") return ["Результат не засчитан"];
  return status && status !== "OK" ? [status] : [];
}

function courseCpTitle(item: CourseProgressItem) {
  if (item.taken) return "взято в порядке дистанции";
  if (item.punched) return "есть отметка, но не в зачёт: предыдущий КП не взят";
  if (item.blocker) return "не взято — с этого КП дистанция не засчитана";
  return "не взято";
}

async function load() {
  loading.value = true;
  error.value = "";
  person.value = null;
  event.value = null;
  try {
    const slug = String(route.params.slug);
    const sourceId = String(route.params.sourceId);
    const [pRes, eRes] = await Promise.all([
      fetch(`/api/events/${slug}/participants/${sourceId}`),
      fetch(`/api/events/${slug}`),
    ]);
    if (!pRes.ok) throw new Error(await pRes.text());
    if (!eRes.ok) throw new Error(await eRes.text());
    person.value = await pRes.json();
    event.value = await eRes.json();
  } catch (e) {
    error.value = String(e);
    person.value = null;
    event.value = null;
  } finally {
    loading.value = false;
  }
}

onMounted(load);
watch(() => [route.params.slug, route.params.sourceId], load);
</script>

<template>
  <p v-if="error" class="err">{{ error }}</p>
  <PageLoading v-else-if="loading" label="Загрузка карточки участника…" />
  <template v-else-if="person && event">
    <p class="back-link">
      <router-link :to="`/events/${event.slug}`">← {{ event.title }}</router-link>
    </p>

    <header class="person-head">
      <h1>{{ person.name }}</h1>
      <p class="person-sub">
        <template v-if="isOrient">
          <span>Дистанция: {{ awardGroupName }}</span>
        </template>
        <template v-else>
          <span>№ {{ person.bib }}</span>
          <span>{{ awardGroupName }}</span>
        </template>
      </p>
    </header>

    <div class="stat-grid" :class="{ 'is-single': isOrient }">
      <div v-if="!isOrient" class="stat">
        <span class="stat-label">Очки</span>
        <span class="stat-value">{{ metrics.points ?? "—" }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">Время</span>
        <span class="stat-value">{{ fmtHms(metrics.elapsed) }}</span>
      </div>
      <div v-if="!isOrient" class="stat">
        <span class="stat-label">Очков на км</span>
        <span class="stat-value">{{ fmtNum(metrics.pointsPerKm, 1) }}</span>
      </div>
      <div v-if="!isOrient" class="stat">
        <span class="stat-label">Темп</span>
        <span class="stat-value">{{ fmtPace(metrics.pace) }}</span>
      </div>
      <div v-if="!isOrient" class="stat">
        <span class="stat-label">Скорость</span>
        <span class="stat-value">{{ metrics.speed != null ? `${fmtNum(metrics.speed, 1)} км/ч` : "—" }}</span>
      </div>
    </div>

    <section v-if="courseError" class="course-error">
      <h2>Диагностика</h2>
      <p class="course-error-diag">{{ courseError.diagnosticsText }}</p>
      <div v-if="courseError.showProgress" class="course-chip-row">
        <template v-for="(item, idx) in courseError.progress" :key="`${idx}-${item.cp}`">
          <span
            class="course-chip"
            :class="{
              muted: !item.taken && !item.punched,
              'out-of-order': !item.taken && item.punched,
              blocker: item.blocker,
            }"
            :title="courseCpTitle(item)"
          >
            <span class="course-chip-num">{{ item.cp }}</span>
          </span>
          <span v-if="idx < courseError.progress.length - 1" class="course-seq-arrow">→</span>
        </template>
      </div>
      <p v-if="courseError.firstMissedCp != null" class="course-error-break">
        Дистанция оборвалась на КП {{ courseError.firstMissedCp }}
        <template v-if="courseError.missingMore">
          — следующие КП не в зачёт, пока не взят этот.
        </template>
      </p>
    </section>

    <div class="person-main" :class="{ 'is-simple': isOrient }">
      <section class="person-marks">
        <h2>Финишные отметки</h2>
        <div class="card marks-card">
          <p v-if="!person.marks.length" class="muted">Отметок нет</p>
          <ol v-else class="marks-list">
            <li
              v-for="mark in person.marks"
              :key="mark.seq"
              class="mark-row"
              :class="{ 'is-wrong': wrongMarkSeqs.has(mark.seq) }"
            >
              <span class="mark-seq">{{ mark.seq }}</span>
              <span class="mark-cp">КП {{ mark.cp_number }}</span>
              <span class="mark-time">{{ fmtMarkTime(mark.mark_time) }}</span>
            </li>
          </ol>
        </div>
      </section>

      <section v-if="!isOrient" class="person-map">
        <h2>Путь на карте</h2>
        <div v-if="event.map_url" class="card map-card">
          <CourseMapView :map-url="event.map_url" :path="pathPoints" />
        </div>
        <p v-else class="muted">Карта для этого старта не опубликована.</p>
      </section>
    </div>
  </template>
</template>
