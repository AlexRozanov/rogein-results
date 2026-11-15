<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import { coerceBulletin, type UpcomingBulletin } from "../bulletin";
import BulletinView from "../components/BulletinView.vue";
import PageLoading from "../components/PageLoading.vue";

const route = useRoute();
const item = ref<UpcomingBulletin | null>(null);
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
    item.value = {
      ...data,
      bulletin: coerceBulletin(data.bulletin),
    };
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
    v-else-if="item"
    :title="item.title"
    :date="item.competition_date"
    :bulletin="item.bulletin"
  />
</template>
