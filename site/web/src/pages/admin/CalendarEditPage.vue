<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api } from "../../auth";
import PageLoading from "../../components/PageLoading.vue";

type AdminUpcoming = {
  id: number;
  slug: string;
  title: string;
  competition_date: string | null;
  sport_kind: string;
  status: string;
  summary: string;
};

const route = useRoute();
const router = useRouter();
const isNew = computed(() => route.name === "admin-calendar-new");

const title = ref("");
const slug = ref("");
const competitionDate = ref("");
const sportKind = ref("rogaine");
const summary = ref("");
const status = ref("announced");
const error = ref("");
const loading = ref(!isNew.value);
const pending = ref(false);
const slugTouched = ref(false);

function slugFromTitle(value: string): string {
  return value
    .trim()
    .toLowerCase()
    .replace(/ё/g, "e")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 80);
}

function onTitleInput() {
  if (!slugTouched.value && isNew.value) {
    const generated = slugFromTitle(title.value);
    if (generated) slug.value = generated;
  }
}

async function load() {
  if (isNew.value) return;
  loading.value = true;
  error.value = "";
  try {
    const id = String(route.params.id);
    const item = await api<AdminUpcoming>(`/api/admin/upcoming/${id}`);
    title.value = item.title;
    slug.value = item.slug;
    competitionDate.value = item.competition_date ?? "";
    sportKind.value = item.sport_kind;
    summary.value = item.summary;
    status.value = item.status === "completed" ? "announced" : item.status;
    slugTouched.value = true;
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    loading.value = false;
  }
}

async function onSubmit() {
  error.value = "";
  pending.value = true;
  const body = {
    title: title.value,
    slug: slug.value,
    competition_date: competitionDate.value || null,
    sport_kind: sportKind.value,
    summary: summary.value,
    status: status.value,
  };
  try {
    if (isNew.value) {
      await api("/api/admin/upcoming", {
        method: "POST",
        body: JSON.stringify(body),
      });
    } else {
      await api(`/api/admin/upcoming/${route.params.id}`, {
        method: "PATCH",
        body: JSON.stringify(body),
      });
    }
    await router.push("/admin/calendar");
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    pending.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="admin-page">
    <p class="back-link">
      <router-link to="/admin/calendar">← К списку</router-link>
    </p>
    <h1>{{ isNew ? "Новый анонс" : "Анонс" }}</h1>
    <PageLoading v-if="loading" label="Загрузка…" />
    <form v-else class="card admin-form" @submit.prevent="onSubmit">
      <p v-if="error" class="err">{{ error }}</p>
      <label>
        Название
        <input v-model="title" required @input="onTitleInput" />
      </label>
      <label>
        Slug
        <input
          v-model="slug"
          required
          pattern="[a-z0-9_-]+"
          title="латиница, цифры, дефис"
          @input="slugTouched = true"
        />
      </label>
      <label>
        Дата
        <input v-model="competitionDate" type="date" />
      </label>
      <label>
        Вид
        <select v-model="sportKind">
          <option value="rogaine">рогейн</option>
          <option value="orient">ориентирование</option>
        </select>
      </label>
      <label>
        Кратко
        <textarea v-model="summary" rows="4" />
      </label>
      <label>
        Статус
        <select v-model="status">
          <option value="announced">в календаре</option>
          <option value="draft">черновик</option>
          <option value="cancelled">снят</option>
        </select>
      </label>
      <div class="admin-row-actions">
        <button class="btn btn-primary" type="submit" :disabled="pending">
          {{ pending ? "Сохраняем…" : "Сохранить" }}
        </button>
        <router-link class="btn" to="/admin/calendar">Отмена</router-link>
      </div>
    </form>
  </div>
</template>
