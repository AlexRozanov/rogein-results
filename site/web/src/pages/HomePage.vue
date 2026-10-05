<script setup lang="ts">
import { onMounted, ref } from "vue";
import { sportKindLabel } from "../content/site";
import PageLoading from "../components/PageLoading.vue";

type EventListItem = {
  slug: string;
  title: string;
  competition_date: string | null;
  sport_kind: string;
  participant_count: number;
};

const latest = ref<EventListItem[]>([]);
const error = ref("");
const loading = ref(true);

onMounted(async () => {
  loading.value = true;
  try {
    const res = await fetch("/api/events?per_page=3");
    if (!res.ok) throw new Error(await res.text());
    const page = await res.json();
    latest.value = page.items ?? [];
  } catch (e) {
    error.value = String(e);
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <p v-if="error" class="err">{{ error }}</p>
  <PageLoading v-else-if="loading" label="Загрузка результатов…" />

  <section v-else class="content-section">
    <h1>
      <router-link class="section-title-link" to="/results">Последние результаты</router-link>
    </h1>
    <p v-if="!latest.length" class="muted">Пока нет опубликованных стартов.</p>
    <div v-else class="event-list">
      <router-link
        v-for="event in latest"
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
  </section>
</template>
