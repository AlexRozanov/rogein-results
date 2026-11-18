<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api } from "../../auth";
import { formatDateRu, richTextHtml } from "../../bulletin";
import { sportKindLabel } from "../../content/site";
import AdminIcon from "../../components/AdminIcon.vue";
import PageLoading from "../../components/PageLoading.vue";

export type AdminUpcoming = {
  id: number;
  slug: string;
  title: string;
  competition_date: string | null;
  sport_kind: string;
  status: string;
  summary: string;
  bulletin_ready: boolean;
};

const items = ref<AdminUpcoming[]>([]);
const error = ref("");
const loading = ref(true);
const showPast = ref(false);
const pendingId = ref<number | null>(null);

const statusLabel: Record<string, string> = {
  draft: "черновик",
  announced: "в календаре",
  cancelled: "снят",
  completed: "проведён",
};

function todayIso(): string {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, "0");
  const d = String(now.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

function isPast(item: AdminUpcoming): boolean {
  if (item.status === "completed" || item.status === "cancelled") return true;
  const date = item.competition_date?.trim();
  if (!date) return false;
  return date < todayIso();
}

function statusClass(status: string): string {
  if (status === "announced") return "is-announced";
  if (status === "draft") return "is-draft";
  if (status === "completed") return "is-completed";
  if (status === "cancelled") return "is-cancelled";
  return "";
}

const visibleItems = computed(() => {
  if (showPast.value) return items.value;
  return items.value.filter((item) => !isPast(item));
});

const pastCount = computed(() => items.value.filter(isPast).length);

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

async function onDelete(item: AdminUpcoming) {
  if (
    !confirm(
      `Удалить старт «${item.title}»? Это действие нельзя отменить.`,
    )
  ) {
    return;
  }
  error.value = "";
  pendingId.value = item.id;
  try {
    await api(`/api/admin/upcoming/${item.id}`, { method: "DELETE" });
    items.value = items.value.filter((row) => row.id !== item.id);
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    pendingId.value = null;
  }
}

onMounted(reload);
</script>

<template>
  <div class="admin-page admin-calendar-page">
    <div class="admin-page-head admin-calendar-head">
      <div>
        <h1>Календарь</h1>
        <p class="muted">
          Старты со статусом «в календаре» видны на
          <router-link to="/calendar">публичной странице</router-link>.
        </p>
      </div>
      <router-link
        class="btn btn-primary btn-with-icon"
        to="/admin/calendar/new"
        title="Новый старт"
      >
        <AdminIcon name="plus" />
        <span>Новый старт</span>
      </router-link>
    </div>

    <div class="admin-calendar-toolbar">
      <label class="admin-toggle">
        <input v-model="showPast" type="checkbox" />
        <span>Показывать прошедшие</span>
        <span v-if="pastCount" class="admin-toggle-count">{{ pastCount }}</span>
      </label>
    </div>

    <p v-if="error" class="err">{{ error }}</p>
    <PageLoading v-else-if="loading" label="Загрузка стартов…" />
    <p v-else-if="!items.length" class="muted">Пока нет стартов.</p>
    <p v-else-if="!visibleItems.length" class="muted admin-empty-with-action">
      Нет актуальных стартов.
      <button
        type="button"
        class="btn btn-quiet btn-with-icon"
        title="Показать прошедшие"
        @click="showPast = true"
      >
        <AdminIcon name="eye" />
        <span>Показать прошедшие ({{ pastCount }})</span>
      </button>
    </p>

    <ul v-else class="admin-calendar-list">
      <li
        v-for="item in visibleItems"
        :key="item.id"
        class="admin-calendar-row"
        :class="{ 'is-past': isPast(item) }"
      >
        <div class="admin-calendar-main">
          <div class="admin-calendar-topline">
            <span class="admin-kind-pill">{{ sportKindLabel(item.sport_kind) }}</span>
            <span class="admin-status-pill" :class="statusClass(item.status)">
              {{ statusLabel[item.status] || item.status }}
            </span>
            <span
              class="admin-status-pill"
              :class="item.bulletin_ready ? 'is-bulletin-ready' : 'is-bulletin-pending'"
            >
              {{ item.bulletin_ready ? "бюллетень" : "только анонс" }}
            </span>
            <span v-if="isPast(item)" class="admin-past-mark">прошедший</span>
          </div>
          <h2 class="admin-calendar-title">{{ item.title }}</h2>
          <p class="admin-calendar-meta">
            <span>{{ formatDateRu(item.competition_date) || "дата уточняется" }}</span>
            <span class="admin-calendar-slug">{{ item.slug }}</span>
          </p>
          <div
            v-if="item.summary"
            class="calendar-summary admin-calendar-summary"
            v-html="richTextHtml(item.summary)"
          />
        </div>
        <div class="admin-calendar-actions">
          <router-link
            v-if="item.status !== 'completed'"
            class="admin-icon-btn"
            :to="`/admin/calendar/${item.id}`"
            title="Править"
            aria-label="Править"
          >
            <AdminIcon name="edit" />
          </router-link>
          <router-link
            v-if="item.status === 'announced'"
            class="admin-icon-btn"
            :to="`/calendar/${item.slug}`"
            :title="item.bulletin_ready ? 'Бюллетень' : 'Страница старта'"
            :aria-label="item.bulletin_ready ? 'Бюллетень' : 'Страница старта'"
          >
            <AdminIcon name="doc" />
          </router-link>
          <button
            v-if="item.status !== 'completed'"
            type="button"
            class="admin-icon-btn is-danger"
            title="Удалить"
            aria-label="Удалить"
            :disabled="pendingId === item.id"
            @click="onDelete(item)"
          >
            <AdminIcon name="trash" />
          </button>
        </div>
      </li>
    </ul>
  </div>
</template>
