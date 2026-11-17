<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api } from "../../auth";
import {
  coerceBulletin,
  defaultOrientBulletin,
  normalizeBulletin,
  snapshotBulletin,
  type Bulletin,
} from "../../bulletin";
import { buildEventSlug } from "../../eventSlug";
import BulletinView from "../../components/BulletinView.vue";
import PageLoading from "../../components/PageLoading.vue";

type AdminUpcoming = {
  id: number;
  slug: string;
  title: string;
  competition_date: string | null;
  sport_kind: string;
  status: string;
  summary: string;
  bulletin: Bulletin;
};

const route = useRoute();
const router = useRouter();
const isNew = computed(() => route.name === "admin-calendar-new");

const title = ref("");
const slug = ref("");
const competitionDate = ref("");
const sportKind = ref("orient");
const summary = ref("");
const status = ref("announced");
const bulletin = ref<Bulletin>(defaultOrientBulletin());
const savedId = ref<number | null>(null);
const error = ref("");
const saved = ref("");
const loading = ref(!isNew.value);
const pending = ref(false);
const slugTouched = ref(false);
const previewMode = ref(false);
const bulletinView = ref<{ commit: () => Promise<Bulletin> } | null>(null);

const generatedSlug = computed(() =>
  buildEventSlug(competitionDate.value, title.value),
);

const upcomingId = computed(() => {
  if (isNew.value) return savedId.value;
  const id = Number(route.params.id);
  return Number.isFinite(id) ? id : savedId.value;
});

function applyGeneratedSlug() {
  if (generatedSlug.value) {
    slug.value = generatedSlug.value;
  }
}

function onSlugInput() {
  slugTouched.value = true;
}

function generateSlug() {
  if (!generatedSlug.value) {
    error.value = "Для slug нужны название и дата";
    return;
  }
  error.value = "";
  slug.value = generatedSlug.value;
  slugTouched.value = false;
}

watch([title, competitionDate], () => {
  if (isNew.value && !slugTouched.value) {
    applyGeneratedSlug();
  }
});

watch(
  () => route.params.id,
  (id, prev) => {
    if (!id || id === prev || route.name === "admin-calendar-new") return;
    if (savedId.value && String(savedId.value) === String(id)) return;
    void load();
  },
);

async function load() {
  if (isNew.value) return;
  loading.value = true;
  error.value = "";
  saved.value = "";
  try {
    const id = String(route.params.id);
    const item = await api<AdminUpcoming>(`/api/admin/upcoming/${id}`);
    title.value = item.title;
    slug.value = item.slug;
    competitionDate.value = item.competition_date ?? "";
    sportKind.value = item.sport_kind;
    summary.value = item.summary;
    status.value = item.status === "completed" ? "announced" : item.status;
    bulletin.value = normalizeBulletin(item.bulletin);
    savedId.value = item.id;
    slugTouched.value = true;
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    loading.value = false;
  }
}

async function onSubmit() {
  error.value = "";
  saved.value = "";
  pending.value = true;
  const bulletinPayload = bulletinView.value
    ? await bulletinView.value.commit()
    : snapshotBulletin(bulletin.value);
  bulletin.value = bulletinPayload;
  const body = {
    title: title.value,
    slug: slug.value,
    competition_date: competitionDate.value || null,
    sport_kind: sportKind.value,
    summary: summary.value,
    status: status.value,
    bulletin: bulletinPayload,
  };
  try {
    if (isNew.value && !savedId.value) {
      const created = await api<AdminUpcoming>("/api/admin/upcoming", {
        method: "POST",
        body: JSON.stringify(body),
      });
      savedId.value = created.id;
      bulletin.value = coerceBulletin(created.bulletin);
      await router.replace(`/admin/calendar/${created.id}`);
      saved.value = "Сохранено. Можно загрузить схему и фрагмент карты.";
    } else {
      const id = upcomingId.value;
      if (!id) {
        throw new Error("Сначала сохраните карточку старта (название, дата, slug)");
      }
      const updated = await api<AdminUpcoming>(`/api/admin/upcoming/${id}`, {
        method: "PATCH",
        body: JSON.stringify(body),
      });
      bulletin.value = coerceBulletin(updated.bulletin);
      saved.value = "Сохранено.";
    }
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
    <h1>{{ isNew && !savedId ? "Новый старт" : "Старт" }}</h1>
    <PageLoading v-if="loading" label="Загрузка…" />
    <form v-else class="card admin-form admin-form-wide" @submit.prevent="onSubmit">
      <p v-if="error" class="err">{{ error }}</p>
      <p v-else-if="saved" class="ok">{{ saved }}</p>
      <label>
        Название
        <input v-model="title" required />
      </label>
      <label>
        Дата
        <input v-model="competitionDate" type="date" required />
      </label>
      <label>
        Slug
        <span class="admin-slug-row">
          <input
            v-model="slug"
            required
            pattern="[a-z0-9_-]+"
            title="латиница, цифры, дефис"
            @input="onSlugInput"
          />
          <button
            class="btn"
            type="button"
            :disabled="!generatedSlug"
            @click="generateSlug"
          >
            Из названия и даты
          </button>
        </span>
      </label>
      <label>
        Вид
        <select v-model="sportKind">
          <option value="orient">ориентирование</option>
          <option value="rogaine">рогейн</option>
        </select>
      </label>
      <label>
        Кратко для календаря
        <textarea v-model="summary" rows="3" />
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
        <router-link class="btn" to="/admin/calendar">К списку</router-link>
        <a
          v-if="status === 'announced' && slug"
          class="btn"
          :href="`/calendar/${slug}`"
          target="_blank"
          rel="noreferrer"
        >
          Открыть бюллетень
        </a>
      </div>
    </form>

    <section v-if="!loading" class="bulletin-preview">
      <div class="bulletin-preview-head">
        <div>
          <h2 class="admin-block-title">Информационный бюллетень</h2>
          <p class="muted">
            {{
              previewMode
                ? "Так страницу увидят на сайте. Нажмите «Редактировать», чтобы править блоки."
                : "Страница как на сайте. Нажмите на блок, чтобы редактировать. Пустые блоки — с подсказкой."
            }}
          </p>
          <p v-if="error" class="err">{{ error }}</p>
          <p v-else-if="saved" class="ok">{{ saved }}</p>
        </div>
        <div class="admin-row-actions">
          <button
            class="btn"
            type="button"
            :class="{ 'btn-primary': previewMode }"
            @click="previewMode = !previewMode"
          >
            {{ previewMode ? "Редактировать" : "Просмотр" }}
          </button>
          <button
            class="btn btn-primary"
            type="button"
            :disabled="pending || previewMode"
            @click="onSubmit"
          >
            {{ pending ? "Сохраняем…" : "Сохранить бюллетень" }}
          </button>
        </div>
      </div>
      <BulletinView
        ref="bulletinView"
        v-model="bulletin"
        editable
        :preview="previewMode"
        :title="title || 'Название старта'"
        :date="competitionDate || null"
        :upcoming-id="upcomingId"
        @error="error = $event"
      />
    </section>
  </div>
</template>
