<script setup lang="ts">
import { onMounted, ref } from "vue";

type EventListItem = {
  slug: string;
  title: string;
  competition_date: string | null;
  participant_count: number;
};

const events = ref<EventListItem[]>([]);
const error = ref("");

onMounted(async () => {
  try {
    const res = await fetch("/api/events");
    if (!res.ok) throw new Error(await res.text());
    events.value = await res.json();
  } catch (e) {
    error.value = String(e);
  }
});
</script>

<template>
  <h1>Прошедшие старты</h1>
  <p v-if="error" class="err">{{ error }}</p>
  <p v-else-if="!events.length" class="muted">Пока нет опубликованных стартов.</p>
  <div v-else class="event-list">
    <router-link
      v-for="event in events"
      :key="event.slug"
      class="card event-link"
      :to="`/events/${event.slug}`"
    >
      <h2>{{ event.title }}</h2>
      <p class="muted">
        {{ event.competition_date || "дата не указана" }}
        · участников {{ event.participant_count }}
      </p>
    </router-link>
  </div>
</template>
