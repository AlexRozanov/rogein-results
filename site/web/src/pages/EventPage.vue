<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";

type AwardGroup = {
  id: number;
  source_id: number;
  name: string;
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
};

type EventDetail = {
  slug: string;
  title: string;
  competition_date: string | null;
  map_url: string | null;
  award_groups: AwardGroup[];
  results: ResultRow[];
};

const route = useRoute();
const event = ref<EventDetail | null>(null);
const error = ref("");
const activeGroupId = ref<number | null>(null);
const navOpen = ref(false);
const navRef = ref<HTMLElement | null>(null);
const navBarRef = ref<HTMLElement | null>(null);
let observer: IntersectionObserver | null = null;
let navResizeObserver: ResizeObserver | null = null;

const groupedResults = computed(() => {
  if (!event.value) return [];
  return event.value.award_groups.map((group) => ({
    group,
    rows: event.value!.results.filter((row) => row.award_group_id === group.id),
  }));
});

function fmtHms(total: number) {
  const s = Math.max(0, total | 0);
  const hh = String(Math.floor(s / 3600)).padStart(2, "0");
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, "0");
  const ss = String(s % 60).padStart(2, "0");
  return `${hh}:${mm}:${ss}`;
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
  if (!navOpen.value || !navRef.value) return;
  if (navRef.value.contains(event.target as Node)) return;
  closeNav();
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === "Escape") closeNav();
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
  error.value = "";
  observer?.disconnect();
  try {
    const res = await fetch(`/api/events/${route.params.slug}`);
    if (!res.ok) throw new Error(await res.text());
    event.value = await res.json();
    await afterRender();
  } catch (e) {
    error.value = String(e);
    event.value = null;
  }
}

onMounted(() => {
  load();
  document.addEventListener("pointerdown", onDocumentPointerDown);
  window.addEventListener("keydown", onKeydown);
});
watch(() => route.params.slug, () => {
  navOpen.value = false;
  load();
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
  <template v-else-if="event">
    <h1>{{ event.title }}</h1>
    <p class="muted event-date">{{ event.competition_date || "дата не указана" }}</p>

    <nav
      v-if="event.award_groups.length"
      ref="navRef"
      class="group-nav"
      :class="{ 'is-open': navOpen }"
      aria-label="Наградные группы"
    >
      <button
        ref="navBarRef"
        type="button"
        class="group-nav-bar"
        :aria-expanded="navOpen"
        aria-controls="group-nav-panel"
        @click="toggleNav"
      >
        <span>Все группы</span>
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
      v-for="block in groupedResults"
      :id="groupAnchor(block.group.id)"
      :key="block.group.id"
      class="results-section"
      :data-group-id="block.group.id"
    >
      <h2>{{ block.group.name }}</h2>
      <div class="card table-wrap">
        <table class="results-table">
          <thead>
            <tr>
              <th>Место</th>
              <th>Номер</th>
              <th>Имя</th>
              <th>Пол</th>
              <th>Возраст</th>
              <th>Очки</th>
              <th>Штраф</th>
              <th>Итог</th>
              <th>Время</th>
              <th>Статус</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!block.rows.length">
              <td colspan="10">В этой группе пока нет результатов</td>
            </tr>
            <tr v-for="row in block.rows" :key="row.participant_source_id">
              <td>{{ row.place ?? "—" }}</td>
              <td>
                <router-link
                  class="bib"
                  :to="`/events/${event.slug}/p/${row.participant_source_id}?g=${block.group.id}`"
                >
                  {{ row.bib }}
                </router-link>
              </td>
              <td>{{ row.name }}</td>
              <td>{{ row.gender || "—" }}</td>
              <td>{{ row.age ?? "—" }}</td>
              <td>{{ row.points_raw }}</td>
              <td>{{ row.penalty_points }}</td>
              <td>{{ row.points_final }}</td>
              <td>{{ fmtHms(row.elapsed_seconds) }}</td>
              <td>{{ row.status }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </template>
</template>
