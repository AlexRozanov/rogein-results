<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "../../auth";
import { richTextHtml } from "../../bulletin";
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
      <router-link class="btn btn-primary" to="/admin/calendar/new">Новый старт</router-link>
    </div>
    <p class="muted">
      Старты со статусом «в календаре» видны на
      <router-link to="/calendar">публичной странице</router-link>.
    </p>
    <p v-if="error" class="err">{{ error }}</p>
    <PageLoading v-else-if="loading" label="Загрузка стартов…" />
    <p v-else-if="!items.length" class="muted">Пока нет стартов.</p>
    <div v-else class="event-list">
      <article v-for="item in items" :key="item.id" class="card">
        <p class="kind-pill">{{ sportKindLabel(item.sport_kind) }}</p>
        <h2>{{ item.title }}</h2>
        <p class="muted">
          {{ item.competition_date || "дата уточняется" }}
          · {{ statusLabel[item.status] || item.status }}
          · {{ item.slug }}
        </p>
        <div
          v-if="item.summary"
          class="calendar-summary"
          v-html="richTextHtml(item.summary)"
        />
        <p class="admin-row-actions">
          <router-link
            v-if="item.status !== 'completed'"
            class="btn"
            :to="`/admin/calendar/${item.id}`"
          >
            Править
          </router-link>
          <router-link
            v-if="item.status === 'announced'"
            class="btn"
            :to="`/calendar/${item.slug}`"
          >
            Бюллетень
          </router-link>
        </p>
      </article>
    </div>
  </div>
</template>
