<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "../../auth";
import { sportKindLabel } from "../../content/site";
import PageLoading from "../../components/PageLoading.vue";

export type AdminUpcoming = {
  id: number;
  slug: string;
  title: string;
  competition_date: string | null;
  sport_kind: string;
  status: string;
  summary: string;
};

const items = ref<AdminUpcoming[]>([]);
const error = ref("");
const loading = ref(true);

const statusLabel: Record<string, string> = {
  draft: "черновик",
  announced: "в календаре",
  cancelled: "снят",
  completed: "проведён",
};

async function reload() {
  loading.value = true;
  error.value = "";
  try {
    items.value = await api<AdminUpcoming[]>("/api/admin/upcoming");
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    loading.value = false;
  }
}

onMounted(reload);
</script>

<template>
  <div class="admin-page">
    <div class="admin-page-head">
      <h1>Календарь</h1>
      <router-link class="btn btn-primary" to="/admin/calendar/new">Новый анонс</router-link>
    </div>
    <p class="muted">
      Анонсы с статусом «в календаре» видны на
      <router-link to="/calendar">публичной странице</router-link>.
    </p>
    <p v-if="error" class="err">{{ error }}</p>
    <PageLoading v-else-if="loading" label="Загрузка анонсов…" />
    <p v-else-if="!items.length" class="muted">Пока нет анонсов.</p>
    <div v-else class="event-list">
      <article v-for="item in items" :key="item.id" class="card">
        <p class="kind-pill">{{ sportKindLabel(item.sport_kind) }}</p>
        <h2>{{ item.title }}</h2>
        <p class="muted">
          {{ item.competition_date || "дата уточняется" }}
          · {{ statusLabel[item.status] || item.status }}
          · {{ item.slug }}
        </p>
        <p v-if="item.summary">{{ item.summary }}</p>
        <p v-if="item.status !== 'completed'" class="admin-row-actions">
          <router-link class="btn" :to="`/admin/calendar/${item.id}`">Править</router-link>
        </p>
      </article>
    </div>
  </div>
</template>
