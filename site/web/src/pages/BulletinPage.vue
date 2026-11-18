<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import {
  coerceBulletin,
  formatDateRu,
  richTextHtml,
  type Bulletin,
  type UpcomingBulletin,
} from "../bulletin";
import { sportKindLabel } from "../content/site";
import BulletinView from "../components/BulletinView.vue";
import PageLoading from "../components/PageLoading.vue";

const route = useRoute();
const item = ref<UpcomingBulletin | null>(null);
const bulletin = ref<Bulletin | null>(null);
const error = ref("");
const loading = ref(true);

onMounted(async () => {
  loading.value = true;
  try {
    const slug = String(route.params.slug);
    const res = await fetch(`/api/upcoming/${encodeURIComponent(slug)}`);
    if (res.status === 404) {
      error.value = "Старт не найден";
      return;
    }
    if (!res.ok) throw new Error(await res.text());
    const data = (await res.json()) as UpcomingBulletin;
    item.value = data;
    bulletin.value = data.bulletin_ready ? coerceBulletin(data.bulletin) : null;
  } catch (e) {
    error.value = String(e);
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <p v-if="error" class="err">{{ error }}</p>
  <PageLoading v-else-if="loading" label="Загрузка бюллетеня…" />
  <BulletinView
    v-else-if="item && bulletin"
    v-model="bulletin"
    :title="item.title"
    :date="item.competition_date"
    :slug="item.slug"
  />
  <section v-else-if="item" class="bulletin-pending">
    <p class="kind-pill">{{ sportKindLabel(item.sport_kind) }}</p>
    <h1>{{ item.title }}</h1>
    <p class="muted">{{ formatDateRu(item.competition_date) || "дата уточняется" }}</p>
    <div
      v-if="item.summary"
      class="calendar-summary"
      v-html="richTextHtml(item.summary)"
    />
    <p class="bulletin-pending-note">
      Информационный бюллетень появится ближе к старту.
    </p>
    <p class="back-link">
      <router-link to="/calendar">← К календарю</router-link>
    </p>
  </section>
</template>
