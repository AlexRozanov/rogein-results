<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api } from "../../auth";

type HelpListItem = {
  slug: string;
  section: string;
  title: string;
  summary: string;
  sort_order: number;
  updated_at: string;
};

const SECTION_LABELS: Record<string, string> = {
  start: "Старт",
  day: "Старт дня",
  field: "Обмен",
  online: "Online",
  faq: "FAQ",
};

const loading = ref(true);
const error = ref("");
const articles = ref<HelpListItem[]>([]);

const groups = computed(() => {
  const order = ["start", "day", "field", "online", "faq"];
  const map = new Map<string, HelpListItem[]>();
  for (const a of articles.value) {
    const list = map.get(a.section) ?? [];
    list.push(a);
    map.set(a.section, list);
  }
  return order
    .filter((key) => map.has(key))
    .map((key) => ({
      key,
      label: SECTION_LABELS[key] || key,
      items: map.get(key)!,
    }));
});

onMounted(async () => {
  loading.value = true;
  error.value = "";
  try {
    articles.value = await api<HelpListItem[]>("/api/admin/help");
  } catch (e) {
    error.value = e instanceof Error ? e.message : "Не удалось загрузить справку";
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <div class="admin-page help-index">
    <div class="admin-page-head">
      <h1>Справка</h1>
    </div>
    <p class="muted intro">
      Инструкции по мобильному приложению SFR Reader для судей. Читать можно с
      телефона после входа в кабинет.
    </p>

    <p v-if="loading" class="muted">Загрузка…</p>
    <p v-else-if="error" class="help-error">{{ error }}</p>

    <template v-else>
      <section v-for="group in groups" :key="group.key" class="help-section">
        <h2>{{ group.label }}</h2>
        <ul class="help-list">
          <li v-for="item in group.items" :key="item.slug">
            <router-link :to="`/admin/help/${item.slug}`" class="help-card">
              <span class="help-card-title">{{ item.title }}</span>
              <span class="help-card-summary muted">{{ item.summary }}</span>
            </router-link>
          </li>
        </ul>
      </section>
      <p v-if="articles.length === 0" class="muted">Пока нет опубликованных статей.</p>
    </template>
  </div>
</template>

<style scoped>
.intro {
  max-width: 40rem;
  margin: 0 0 1.25rem;
}

.help-error {
  margin: 1rem 0;
  padding: 0.85rem 1rem;
  background: #f8e8e0;
  color: #8a3b12;
  border-radius: 8px;
}

.help-section {
  margin: 1.5rem 0;
  max-width: 40rem;
}

.help-section h2 {
  margin: 0 0 0.65rem;
  font-size: 1.05rem;
}

.help-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.help-card {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  padding: 0.85rem 1rem;
  background: var(--card, #fffcf6);
  border: 1px solid var(--line, #d9d3c4);
  border-radius: 10px;
  text-decoration: none;
  color: inherit;
}

.help-card:hover {
  border-color: var(--green, #1fae3a);
}

.help-card-title {
  font-weight: 650;
}

.help-card-summary {
  font-size: 0.92em;
}
</style>
