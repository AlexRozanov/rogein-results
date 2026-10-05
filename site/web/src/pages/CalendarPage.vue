<script setup lang="ts">
import { onMounted, ref } from "vue";
import { site, sportKindLabel } from "../content/site";
import PageLoading from "../components/PageLoading.vue";

type UpcomingItem = {
  slug: string;
  title: string;
  competition_date: string | null;
  sport_kind: string;
  summary: string;
};

const items = ref<UpcomingItem[]>([]);
const error = ref("");
const loading = ref(true);

onMounted(async () => {
  loading.value = true;
  try {
    const res = await fetch("/api/upcoming");
    if (!res.ok) throw new Error(await res.text());
    items.value = await res.json();
  } catch (e) {
    error.value = String(e);
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <h1>Календарь</h1>
  <p v-if="error" class="err">{{ error }}</p>
  <PageLoading v-else-if="loading" label="Загрузка календаря…" />
  <p v-else-if="!items.length" class="muted">{{ site.calendarEmpty }}</p>
  <div v-else class="event-list">
    <article
      v-for="item in items"
      :id="item.slug"
      :key="item.slug"
      class="card"
    >
      <p class="kind-pill">{{ sportKindLabel(item.sport_kind) }}</p>
      <h2>{{ item.title }}</h2>
      <p class="muted">{{ item.competition_date || "дата уточняется" }}</p>
      <p v-if="item.summary">{{ item.summary }}</p>
    </article>
  </div>
</template>
