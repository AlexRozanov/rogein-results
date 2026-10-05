<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { sportKindLabel } from "../content/site";
import PageLoading from "../components/PageLoading.vue";
import {
  buildSplitCells,
  inferCourseCps,
  splitColumns,
  type SplitCell,
  type SplitCol,
} from "../orientSplits";

type AwardGroup = {
  id: number;
  source_id: number;
  name: string;
  course_cps?: number[] | null;
};

type ResultRow = {
  award_group_id: number;
  participant_source_id: number;
  bib: string;
  name: string;
  gender: string | null;
  age: number | null;
  team_id: number | null;
  format_name: string | null;
  place: number | null;
  points_raw: number;
  penalty_points: number;
  points_final: number;
  elapsed_seconds: number;
  status: string;
  marks?: unknown;
};

type EventDetail = {
  slug: string;
  title: string;
  competition_date: string | null;
  sport_kind?: string;
  map_url: string | null;
  start_cp?: number | null;
  finish_cp?: number | null;
  award_groups: AwardGroup[];
  results: ResultRow[];
};

const route = useRoute();
const event = ref<EventDetail | null>(null);
const error = ref("");
const loading = ref(true);
const showSplits = ref(false);
const compareIds = ref<Record<number, number[]>>({});
const compareQuery = ref<Record<number, string>>({});
const compareMenuFor = ref<number | null>(null);
const activeGroupId = ref<number | null>(null);
const navOpen = ref(false);
const navRef = ref<HTMLElement | null>(null);
const navBarRef = ref<HTMLElement | null>(null);
let observer: IntersectionObserver | null = null;
let navResizeObserver: ResizeObserver | null = null;

const isOrient = computed(() => event.value?.sport_kind === "orient");
const groupNavLabel = computed(() => (isOrient.value ? "Дистанции" : "Наградные группы"));
const groupNavAllLabel = computed(() => (isOrient.value ? "Все дистанции" : "Все группы"));
const emptyGroupText = computed(() =>
  isOrient.value ? "На этой дистанции пока нет результатов" : "В этой группе пока нет результатов",
);
const groupedResults = computed(() => {
  if (!event.value) return [];
  const startCp = event.value.start_cp ?? 241;
  const finishCp = event.value.finish_cp ?? 240;
  return event.value.award_groups.map((group) => {
    const rows = event.value!.results.filter((row) => row.award_group_id === group.id);
    const okTimes = rows
      .filter((row) => isOk(row.status) && row.elapsed_seconds > 0)
      .map((row) => row.elapsed_seconds);
    const leaderElapsed = okTimes.length ? Math.min(...okTimes) : null;
    const course = inferCourseCps(group.course_cps, rows, startCp, finishCp);
    const cols: SplitCol[] = splitColumns(course, finishCp);
    const splitCells: Array<Array<SplitCell | null>> = cols.length
      ? buildSplitCells(rows, cols, startCp)
      : rows.map(() => []);
    return { group, rows, leaderElapsed, splitCols: cols, splitCells };
  });
});

const displayedResults = computed(() => {
  const startCp = event.value?.start_cp ?? 241;
  return groupedResults.value.map((block) => {
    const selected = compareIds.value[block.group.id] ?? [];
    const comparing = isOrient.value && selected.length >= 2;
    if (!comparing) {
      return {
        ...block,
        viewRows: block.rows,
        viewSplitCells: block.splitCells,
        viewLeader: block.leaderElapsed,
        comparing: false,
        selected,
      };
    }
    const idSet = new Set(selected);
    const viewRows = block.rows.filter((row) => idSet.has(row.participant_source_id));
    const viewSplitCells = block.splitCols.length
      ? buildSplitCells(viewRows, block.splitCols, startCp)
      : viewRows.map(() => []);
    const okTimes = viewRows
      .filter((row) => isOk(row.status) && row.elapsed_seconds > 0)
      .map((row) => row.elapsed_seconds);
    return {
      ...block,
      viewRows,
      viewSplitCells,
      viewLeader: okTimes.length ? Math.min(...okTimes) : null,
      comparing: true,
      selected,
    };
  });
});

function normName(value: string) {
  return value.trim().toLowerCase().replace(/ё/g, "е");
}

function selectedRows(block: { group: AwardGroup; rows: ResultRow[] }) {
  const ids = compareIds.value[block.group.id] ?? [];
  return ids
    .map((id) => block.rows.find((row) => row.participant_source_id === id))
    .filter((row): row is ResultRow => Boolean(row));
}

function compareSuggestions(block: { group: AwardGroup; rows: ResultRow[] }) {
  const q = normName(compareQuery.value[block.group.id] ?? "");
  if (q.length < 1) return [] as ResultRow[];
  const selected = new Set(compareIds.value[block.group.id] ?? []);
  return block.rows
    .filter((row) => !selected.has(row.participant_source_id) && normName(row.name).includes(q))
    .slice(0, 8);
}

function onCompareInput(groupId: number, event: Event) {
  const value = (event.target as HTMLInputElement).value;
  compareQuery.value = { ...compareQuery.value, [groupId]: value };
  compareMenuFor.value = groupId;
}

function addToCompare(groupId: number, row: ResultRow) {
  const current = compareIds.value[groupId] ?? [];
  if (current.includes(row.participant_source_id)) return;
  compareIds.value = { ...compareIds.value, [groupId]: [...current, row.participant_source_id] };
  compareQuery.value = { ...compareQuery.value, [groupId]: "" };
  compareMenuFor.value = null;
}

function removeFromCompare(groupId: number, id: number) {
  compareIds.value = {
    ...compareIds.value,
    [groupId]: (compareIds.value[groupId] ?? []).filter((item) => item !== id),
  };
}

function clearCompare(groupId: number) {
  compareIds.value = { ...compareIds.value, [groupId]: [] };
  compareQuery.value = { ...compareQuery.value, [groupId]: "" };
  compareMenuFor.value = null;
}

function onCompareKeydown(groupId: number, block: { group: AwardGroup; rows: ResultRow[] }, event: KeyboardEvent) {
  if (event.key === "Enter") {
    const first = compareSuggestions(block)[0];
    if (first) {
      event.preventDefault();
      addToCompare(groupId, first);
    }
  }
}

function splitsOn(comparing: boolean) {
  return showSplits.value || comparing;
}

function isOk(status: string | null | undefined) {
  return (status || "OK") === "OK";
}

function statusLabel(status: string) {
  if (status === "Дисквалификация") return "DSQ";
  if (status === "Не стартовал") return "DNS";
  if (status === "DNF") return "DNF";
  return status;
}

function fmtHms(total: number) {
  const s = Math.max(0, total | 0);
  const hh = String(Math.floor(s / 3600)).padStart(2, "0");
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, "0");
  const ss = String(s % 60).padStart(2, "0");
  return `${hh}:${mm}:${ss}`;
}

function fmtOrientTime(total: number) {
  const s = Math.max(0, total | 0);
  const hh = Math.floor(s / 3600);
  const mm = Math.floor((s % 3600) / 60);
  const ss = s % 60;
  if (hh > 0) {
    return `${hh}:${String(mm).padStart(2, "0")}:${String(ss).padStart(2, "0")}`;
  }
  return `${mm}:${String(ss).padStart(2, "0")}`;
}

function resultCell(row: ResultRow) {
  if (isOk(row.status)) return fmtOrientTime(row.elapsed_seconds);
  return statusLabel(row.status);
}

function gapCell(row: ResultRow, leaderElapsed: number | null) {
  if (!isOk(row.status) || leaderElapsed == null) return "";
  const gap = row.elapsed_seconds - leaderElapsed;
  if (gap <= 0) return "—";
  return `+${fmtOrientTime(gap)}`;
}

function fmtSplit(sec: number) {
  return fmtHms(sec);
}

function splitText(sec: number, place: number | null) {
  return place != null ? `${fmtSplit(sec)} (${place})` : fmtSplit(sec);
}

function orientColspan(splitCount: number, comparing: boolean) {
  return 4 + (splitsOn(comparing) ? splitCount : 0);
}

function groupAnchor(id: number) {
  return `group-${id}`;
}

function syncNavHeight() {
  const height = navBarRef.value?.offsetHeight ?? navRef.value?.offsetHeight ?? 0;
  document.documentElement.style.setProperty("--nav-h", `${Math.max(height, 44)}px`);
}

function toggleNav() {
  navOpen.value = !navOpen.value;
}

function closeNav() {
  navOpen.value = false;
}

function onDocumentPointerDown(event: PointerEvent) {
  const target = event.target as HTMLElement | null;
  if (compareMenuFor.value != null && !target?.closest(".compare-box")) {
    compareMenuFor.value = null;
  }
  if (!navOpen.value || !navRef.value) return;
  if (navRef.value.contains(event.target as Node)) return;
  closeNav();
}

function onKeydown(event: KeyboardEvent) {
  if (event.key !== "Escape") return;
  if (compareMenuFor.value != null) {
    compareMenuFor.value = null;
    return;
  }
  closeNav();
}

async function scrollToGroup(id: number) {
  activeGroupId.value = id;
  navOpen.value = false;
  await nextTick();
  syncNavHeight();
  document.getElementById(groupAnchor(id))?.scrollIntoView({
    behavior: "smooth",
    block: "start",
  });
  history.replaceState(null, "", `#${groupAnchor(id)}`);
}

function setupObserver() {
  observer?.disconnect();
  const sections = document.querySelectorAll<HTMLElement>(".results-section");
  if (!sections.length) return;
  observer = new IntersectionObserver(
    (entries) => {
      const visible = entries
        .filter((entry) => entry.isIntersecting)
        .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
      const top = visible[0]?.target as HTMLElement | undefined;
      const id = Number(top?.dataset.groupId);
      if (Number.isFinite(id) && id > 0) {
        activeGroupId.value = id;
      }
    },
    { rootMargin: "-18% 0px -70% 0px", threshold: [0, 0.1] },
  );
  sections.forEach((section) => observer!.observe(section));
}

function observeNav() {
  navResizeObserver?.disconnect();
  const el = navBarRef.value ?? navRef.value;
  if (!el) return;
  navResizeObserver = new ResizeObserver(() => syncNavHeight());
  navResizeObserver.observe(el);
  syncNavHeight();
}

async function afterRender() {
  await nextTick();
  observeNav();
  setupObserver();
  const hash = window.location.hash.replace(/^#/, "");
  const match = /^group-(\d+)$/.exec(hash);
  if (match) {
    const id = Number(match[1]);
    if (event.value?.award_groups.some((group) => group.id === id)) {
      scrollToGroup(id);
      return;
    }
  }
  activeGroupId.value = event.value?.award_groups[0]?.id ?? null;
}

async function load() {
  loading.value = true;
  error.value = "";
  event.value = null;
  observer?.disconnect();
  try {
    const res = await fetch(`/api/events/${route.params.slug}`);
    if (!res.ok) throw new Error(await res.text());
    event.value = await res.json();
    await afterRender();
  } catch (e) {
    error.value = String(e);
    event.value = null;
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  load();
  document.addEventListener("pointerdown", onDocumentPointerDown);
  window.addEventListener("keydown", onKeydown);
});
watch(() => route.params.slug, () => {
  navOpen.value = false;
  showSplits.value = false;
  compareIds.value = {};
  compareQuery.value = {};
  compareMenuFor.value = null;
  load();
});
watch(showSplits, () => {
  void nextTick(() => {
    observeNav();
    setupObserver();
  });
});
watch(compareIds, () => {
  void nextTick(() => {
    observeNav();
    setupObserver();
  });
});
onBeforeUnmount(() => {
  observer?.disconnect();
  navResizeObserver?.disconnect();
  document.removeEventListener("pointerdown", onDocumentPointerDown);
  window.removeEventListener("keydown", onKeydown);
  document.documentElement.style.removeProperty("--nav-h");
});
</script>

<template>
  <p v-if="error" class="err">{{ error }}</p>
  <PageLoading v-else-if="loading" label="Загрузка результатов…" />
  <template v-else-if="event">
    <p class="back-link">
      <router-link to="/results">← Все результаты</router-link>
    </p>
    <h1>{{ event.title }}</h1>
    <p class="muted event-date">
      {{ event.competition_date || "дата не указана" }}
      · {{ sportKindLabel(event.sport_kind) }}
    </p>

    <label v-if="isOrient" class="splits-toggle" :class="{ 'is-on': showSplits }">
      <input v-model="showSplits" type="checkbox" />
      <span class="splits-toggle-text">
        <strong>Сплиты</strong>
        <small>время и место на перегоне и от старта</small>
      </span>
    </label>

    <nav
      v-if="event.award_groups.length"
      ref="navRef"
      class="group-nav"
      :class="{ 'is-open': navOpen }"
      :aria-label="groupNavLabel"
    >
      <button
        ref="navBarRef"
        type="button"
        class="group-nav-bar"
        :aria-expanded="navOpen"
        aria-controls="group-nav-panel"
        @click="toggleNav"
      >
        <span>{{ groupNavAllLabel }}</span>
        <svg class="group-nav-icon" viewBox="0 0 20 20" aria-hidden="true">
          <path
            d="M5 7.5 10 12.5 15 7.5"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </button>
      <div v-show="navOpen" id="group-nav-panel" class="group-nav-panel">
        <a
          v-for="group in event.award_groups"
          :id="`nav-${group.id}`"
          :key="group.id"
          class="group-nav-item"
          :class="{ active: group.id === activeGroupId }"
          :href="`#${groupAnchor(group.id)}`"
          @click.prevent="scrollToGroup(group.id)"
        >
          {{ group.name }}
        </a>
      </div>
    </nav>

    <section
      v-for="block in displayedResults"
      :id="groupAnchor(block.group.id)"
      :key="block.group.id"
      class="results-section"
      :data-group-id="block.group.id"
    >
      <h2>{{ block.group.name }}</h2>
      <div v-if="isOrient" class="compare-box">
        <div class="compare-search">
          <input
            :value="compareQuery[block.group.id] ?? ''"
            type="search"
            autocomplete="off"
            placeholder="Добавить к сравнению"
            :aria-label="`Добавить к сравнению на дистанции ${block.group.name}`"
            @focus="compareMenuFor = block.group.id"
            @input="onCompareInput(block.group.id, $event)"
            @keydown="onCompareKeydown(block.group.id, block, $event)"
          />
          <ul
            v-if="compareMenuFor === block.group.id && compareSuggestions(block).length"
            class="compare-suggest"
          >
            <li v-for="row in compareSuggestions(block)" :key="row.participant_source_id">
              <button type="button" @mousedown.prevent="addToCompare(block.group.id, row)">
                <span>{{ row.name }}</span>
                <span class="compare-suggest-meta">{{ row.place ?? "—" }}</span>
              </button>
            </li>
          </ul>
        </div>
        <div v-if="selectedRows(block).length" class="compare-chips">
          <button
            v-for="row in selectedRows(block)"
            :key="row.participant_source_id"
            type="button"
            class="compare-chip"
            :title="`Убрать ${row.name}`"
            @click="removeFromCompare(block.group.id, row.participant_source_id)"
          >
            {{ row.name }}
            <span aria-hidden="true">×</span>
          </button>
          <button type="button" class="compare-reset" @click="clearCompare(block.group.id)">
            Сбросить
          </button>
        </div>
        <p v-if="selectedRows(block).length === 1" class="muted compare-hint">
          Выберите ещё одного участника этой дистанции
        </p>
        <p v-else-if="block.comparing" class="muted compare-hint">
          Сравнение {{ block.viewRows.length }} участников · места на перегонах среди выбранных
        </p>
      </div>
      <div class="card table-wrap">
        <table
          class="results-table"
          :class="{ compact: isOrient, 'with-splits': isOrient && splitsOn(block.comparing) }"
        >
          <thead>
            <tr>
              <th>Место</th>
              <th v-if="!isOrient">Номер</th>
              <th class="col-name">Имя</th>
              <th v-if="!isOrient">Пол</th>
              <th v-if="!isOrient">Возраст</th>
              <th v-if="!isOrient">Очки</th>
              <th v-if="!isOrient">Штраф</th>
              <th v-if="!isOrient">Итог</th>
              <th>Время</th>
              <th v-if="isOrient">Отставание</th>
              <th v-if="!isOrient">Статус</th>
              <th
                v-for="col in isOrient && splitsOn(block.comparing) ? block.splitCols : []"
                :key="col.label"
                class="col-split"
              >
                {{ col.label }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!block.viewRows.length">
              <td :colspan="isOrient ? orientColspan(block.splitCols.length, block.comparing) : 10">
                {{ emptyGroupText }}
              </td>
            </tr>
            <tr v-for="(row, rowIndex) in block.viewRows" :key="row.participant_source_id">
              <td>{{ row.place ?? "—" }}</td>
              <td v-if="!isOrient">
                <router-link
                  class="bib"
                  :to="`/events/${event.slug}/p/${row.participant_source_id}?g=${block.group.id}`"
                >
                  {{ row.bib }}
                </router-link>
              </td>
              <td class="col-name">
                <router-link
                  class="bib"
                  :to="`/events/${event.slug}/p/${row.participant_source_id}?g=${block.group.id}`"
                >
                  {{ row.name }}
                </router-link>
              </td>
              <td v-if="!isOrient">{{ row.gender || "—" }}</td>
              <td v-if="!isOrient">{{ row.age ?? "—" }}</td>
              <td v-if="!isOrient">{{ row.points_raw }}</td>
              <td v-if="!isOrient">{{ row.penalty_points }}</td>
              <td v-if="!isOrient">{{ row.points_final }}</td>
              <td class="col-time">{{ isOrient ? resultCell(row) : fmtHms(row.elapsed_seconds) }}</td>
              <td v-if="isOrient" class="col-gap">{{ gapCell(row, block.viewLeader) }}</td>
              <td v-if="!isOrient">{{ row.status }}</td>
              <td
                v-for="(cell, colIndex) in isOrient && splitsOn(block.comparing) ? block.viewSplitCells[rowIndex] ?? [] : []"
                :key="`${row.participant_source_id}-${block.splitCols[colIndex]?.label ?? colIndex}`"
                class="col-split"
              >
                <template v-if="cell">
                  <div class="split-leg" :class="{ best: cell.splitPlace === 1 }">
                    {{ splitText(cell.splitSec, cell.splitPlace) }}
                  </div>
                  <div class="split-cum">
                    {{ splitText(cell.cumSec, cell.cumPlace) }}
                  </div>
                </template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </template>
</template>
