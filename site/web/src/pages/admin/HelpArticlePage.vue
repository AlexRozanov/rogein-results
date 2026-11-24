<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { api } from "../../auth";
import AdminIcon from "../../components/AdminIcon.vue";
import MarkdownView from "../../components/MarkdownView.vue";

type HelpArticle = {
  slug: string;
  section: string;
  title: string;
  summary: string;
  body_md: string;
  sort_order: number;
  updated_at: string;
};

const route = useRoute();
const loading = ref(true);
const error = ref("");
const article = ref<HelpArticle | null>(null);

const slug = computed(() => String(route.params.slug || "").trim());

const updatedLabel = computed(() => {
  const raw = article.value?.updated_at;
  if (!raw) return "";
  const d = new Date(raw);
  if (Number.isNaN(d.getTime())) return "";
  return d.toLocaleString("ru-RU");
});

async function load() {
  loading.value = true;
  error.value = "";
  article.value = null;
  try {
    article.value = await api<HelpArticle>(
      `/api/admin/help/${encodeURIComponent(slug.value)}`,
    );
  } catch (e) {
    error.value = e instanceof Error ? e.message : "Не удалось загрузить статью";
  } finally {
    loading.value = false;
  }
}

onMounted(load);
watch(slug, load);
</script>

<template>
  <div class="admin-page help-article">
    <div class="admin-page-head">
      <h1>{{ article?.title || "Справка" }}</h1>
      <router-link class="admin-icon-btn" to="/admin/help" title="К оглавлению">
        <AdminIcon name="back" />
      </router-link>
    </div>

    <p v-if="loading" class="muted">Загрузка…</p>
    <p v-else-if="error" class="help-error">{{ error }}</p>

    <template v-else-if="article">
      <p v-if="article.summary" class="muted lead">{{ article.summary }}</p>
      <p v-if="updatedLabel" class="muted meta">Обновлено {{ updatedLabel }}</p>
      <MarkdownView :source="article.body_md" />
      <p class="back-row">
        <router-link to="/admin/help">← К оглавлению</router-link>
      </p>
    </template>
  </div>
</template>

<style scoped>
.lead {
  max-width: 42rem;
  margin: 0 0 0.35rem;
}

.meta {
  font-size: 0.9em;
  margin: 0 0 1.25rem;
}

.help-error {
  margin: 1rem 0;
  padding: 0.85rem 1rem;
  background: #f8e8e0;
  color: #8a3b12;
  border-radius: 8px;
}

.back-row {
  margin: 2rem 0 1rem;
}

.back-row a {
  color: var(--green-dark, #178a2d);
}
</style>
