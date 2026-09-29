<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { site, sportKindLabel } from "../content/site";

type EventListItem = {
  slug: string;
  title: string;
  competition_date: string | null;
  sport_kind: string;
  participant_count: number;
};

type EventListPage = {
  items: EventListItem[];
  total: number;
  page: number;
  per_page: number;
};

const route = useRoute();
const router = useRouter();
const page = ref<EventListPage | null>(null);
const error = ref("");

const kind = computed(() => String(route.query.kind ?? ""));
const from = computed(() => String(route.query.from ?? ""));
const to = computed(() => String(route.query.to ?? ""));
const pageNo = computed(() => Math.max(1, Number(route.query.page ?? 1) || 1));
const pageCount = computed(() => {
  if (!page.value) return 1;
  return Math.max(1, Math.ceil(page.value.total / page.value.per_page));
});

function setQuery(patch: Record<string, string | number | undefined>) {
  const next = { ...route.query, ...patch };
  for (const key of Object.keys(next)) {
    if (next[key] === "" || next[key] === undefined) {
      delete next[key];
    }
  }
  router.replace({ query: next });
}

async function load() {
  error.value = "";
  const params = new URLSearchParams();
  if (kind.value) params.set("kind", kind.value);
  if (from.value) params.set("from", from.value);
  if (to.value) params.set("to", to.value);
  params.set("page", String(pageNo.value));
  params.set("per_page", "20");
  try {
    const res = await fetch(`/api/events?${params.toString()}`);
    if (!res.ok) throw new Error(await res.text());
    page.value = await res.json();
  } catch (e) {
    error.value = String(e);
  }
}

watch(
  () => [route.query.kind, route.query.from, route.query.to, route.query.page],
  load,
  { immediate: false },
);
onMounted(load);
</script>

<template>
  <h1>Результаты</h1>
  <p class="muted">{{ site.resultsLead }}</p>

  <form class="filters" @submit.prevent>
    <label>
      Тип
      <select :value="kind" @change="setQuery({ kind: ($event.target as HTMLSelectElement).value, page: 1 })">
        <option value="">все</option>
        <option value="rogaine">рогейн</option>
        <option value="orient">ориентирование</option>
      </select>
    </label>
    <label>
      С
      <input
        type="date"
        :value="from"
        @change="setQuery({ from: ($event.target as HTMLInputElement).value, page: 1 })"
      />
    </label>
    <label>
      По
      <input
        type="date"
        :value="to"
        @change="setQuery({ to: ($event.target as HTMLInputElement).value, page: 1 })"
      />
    </label>
  </form>

  <p v-if="error" class="err">{{ error }}</p>
  <p v-else-if="page && !page.items.length" class="muted">Нет стартов по выбранным фильтрам.</p>
  <div v-else-if="page" class="event-list">
    <router-link
      v-for="event in page.items"
      :key="event.slug"
      class="card event-link"
      :to="`/events/${event.slug}`"
    >
      <p class="kind-pill">{{ sportKindLabel(event.sport_kind) }}</p>
      <h2>{{ event.title }}</h2>
      <p class="muted">
        {{ event.competition_date || "дата не указана" }}
        · участников {{ event.participant_count }}
      </p>
    </router-link>
  </div>

  <nav v-if="page && page.total > page.per_page" class="pager" aria-label="Страницы">
    <button type="button" :disabled="pageNo <= 1" @click="setQuery({ page: pageNo - 1 })">
      Назад
    </button>
    <span class="muted">{{ pageNo }} из {{ pageCount }}</span>
    <button type="button" :disabled="pageNo >= pageCount" @click="setQuery({ page: pageNo + 1 })">
      Вперёд
    </button>
  </nav>
</template>
