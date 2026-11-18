<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api } from "../../auth";
import {
  emptyBulletin,
  formatDateRu,
  normalizeBulletin,
  snapshotBulletin,
  type Bulletin,
} from "../../bulletin";
import { buildEventSlug } from "../../eventSlug";
import AdminIcon from "../../components/AdminIcon.vue";
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
  bulletin_ready: boolean;
  bulletin: Bulletin;
};

type AdminUpcomingListItem = AdminUpcoming;

const route = useRoute();
const router = useRouter();
const isNew = computed(() => route.name === "admin-calendar-new");

const title = ref("");
const slug = ref("");
const competitionDate = ref("");
const sportKind = ref("orient");
const summary = ref("");
const status = ref("announced");
const bulletinReady = ref(false);
const bulletin = ref<Bulletin>(emptyBulletin());
const copySources = ref<AdminUpcomingListItem[]>([]);
const copySourceId = ref<number | "">("");
const savedId = ref<number | null>(null);
const error = ref("");
const saved = ref("");
const loading = ref(!isNew.value);
const pending = ref(false);
const savingBulletin = ref(false);
const bulletinBusy = ref(false);
const slugTouched = ref(false);
const previewMode = ref(false);

const generatedSlug = computed(() =>
  buildEventSlug(competitionDate.value, title.value),
);

const upcomingId = computed(() => {
  if (isNew.value) return savedId.value;
  const id = Number(route.params.id);
  return Number.isFinite(id) ? id : savedId.value;
});

const copyOptions = computed(() =>
  copySources.value.filter(
    (item) => item.bulletin_ready && item.id !== upcomingId.value,
  ),
);

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

function applyItem(item: AdminUpcoming) {
  title.value = item.title;
  slug.value = item.slug;
  competitionDate.value = item.competition_date ?? "";
  sportKind.value = item.sport_kind;
  summary.value = item.summary;
  status.value = item.status === "completed" ? "announced" : item.status;
  bulletinReady.value = item.bulletin_ready;
  bulletin.value = item.bulletin_ready
    ? normalizeBulletin(item.bulletin)
    : emptyBulletin();
  savedId.value = item.id;
  slugTouched.value = true;
}

function metaBody() {
  return {
    title: title.value,
    slug: slug.value,
    competition_date: competitionDate.value || null,
    sport_kind: sportKind.value,
    summary: summary.value,
    status: status.value,
  };
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

async function loadCopySources() {
  try {
    copySources.value = await api<AdminUpcomingListItem[]>("/api/admin/upcoming");
  } catch {
    copySources.value = [];
  }
}

async function load() {
  if (isNew.value) {
    await loadCopySources();
    return;
  }
  loading.value = true;
  error.value = "";
  saved.value = "";
  try {
    const id = String(route.params.id);
    const item = await api<AdminUpcoming>(`/api/admin/upcoming/${id}`);
    applyItem(item);
    await loadCopySources();
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    loading.value = false;
  }
}

async function onSubmitMeta() {
  error.value = "";
  saved.value = "";
  pending.value = true;
  try {
    if (isNew.value && !savedId.value) {
      const created = await api<AdminUpcoming>("/api/admin/upcoming", {
        method: "POST",
        body: JSON.stringify(metaBody()),
      });
      applyItem(created);
      await router.replace(`/admin/calendar/${created.id}`);
      saved.value = "Анонс сохранён. Бюллетень можно создать отдельно.";
      await loadCopySources();
    } else {
      const id = upcomingId.value;
      if (!id) throw new Error("Нет id старта");
      const updated = await api<AdminUpcoming>(`/api/admin/upcoming/${id}`, {
        method: "PATCH",
        body: JSON.stringify(metaBody()),
      });
      applyItem(updated);
      saved.value = "Карточка сохранена.";
    }
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    pending.value = false;
  }
}

async function onInitBulletin() {
  const id = upcomingId.value;
  if (!id) {
    error.value = "Сначала сохраните анонс старта";
    return;
  }
  error.value = "";
  saved.value = "";
  bulletinBusy.value = true;
  try {
    const updated = await api<AdminUpcoming>(`/api/admin/upcoming/${id}/bulletin`, {
      method: "POST",
      body: "{}",
    });
    applyItem(updated);
    previewMode.value = false;
    saved.value = "Бюллетень создан с полями по умолчанию.";
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    bulletinBusy.value = false;
  }
}

async function onCopyBulletin() {
  const id = upcomingId.value;
  if (!id) {
    error.value = "Сначала сохраните анонс старта";
    return;
  }
  if (!copySourceId.value) {
    error.value = "Выберите старт, из которого копировать";
    return;
  }
  if (
    bulletinReady.value &&
    !confirm("Заменить текущий бюллетень копией из другого старта?")
  ) {
    return;
  }
  error.value = "";
  saved.value = "";
  bulletinBusy.value = true;
  try {
    const updated = await api<AdminUpcoming>(
      `/api/admin/upcoming/${id}/bulletin/copy`,
      {
        method: "POST",
        body: JSON.stringify({ source_id: Number(copySourceId.value) }),
      },
    );
    applyItem(updated);
    previewMode.value = false;
    saved.value = "Бюллетень скопирован.";
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    bulletinBusy.value = false;
  }
}

async function onDelete() {
  const id = upcomingId.value;
  if (!id) return;
  if (
    !confirm(
      `Удалить старт «${title.value || "без названия"}»? Это действие нельзя отменить.`,
    )
  ) {
    return;
  }
  error.value = "";
  pending.value = true;
  try {
    await api(`/api/admin/upcoming/${id}`, { method: "DELETE" });
    await router.replace("/admin/calendar");
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    pending.value = false;
  }
}

async function onBulletinSave(payload: Bulletin) {
  error.value = "";
  saved.value = "";
  const id = upcomingId.value;
  if (!id) {
    error.value = "Сначала сохраните карточку старта (название, дата, slug)";
    return;
  }
  if (!bulletinReady.value) {
    error.value = "Сначала создайте бюллетень";
    return;
  }
  savingBulletin.value = true;
  bulletin.value = snapshotBulletin(payload);
  try {
    const updated = await api<AdminUpcoming>(`/api/admin/upcoming/${id}`, {
      method: "PATCH",
      body: JSON.stringify({
        ...metaBody(),
        bulletin: snapshotBulletin(payload),
      }),
    });
    applyItem(updated);
    saved.value = "Блок сохранён.";
  } catch (e) {
    error.value = String(e).replace(/^Error:\s*/, "");
  } finally {
    savingBulletin.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="admin-page">
    <p class="back-link">
      <router-link
        class="btn-with-icon"
        to="/admin/calendar"
        title="К списку"
      >
        <AdminIcon name="back" />
        <span>К списку</span>
      </router-link>
    </p>
    <h1>{{ isNew && !savedId ? "Новый старт" : "Старт" }}</h1>
    <PageLoading v-if="loading" label="Загрузка…" />
    <form v-else class="card admin-form admin-form-wide" @submit.prevent="onSubmitMeta">
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
          {{ pending ? "Сохраняем…" : "Сохранить анонс" }}
        </button>
        <router-link
          class="admin-icon-btn"
          to="/admin/calendar"
          title="К списку"
          aria-label="К списку"
        >
          <AdminIcon name="back" />
        </router-link>
        <a
          v-if="status === 'announced' && slug"
          class="admin-icon-btn"
          :href="`/calendar/${slug}`"
          target="_blank"
          rel="noreferrer"
          :title="bulletinReady ? 'Открыть бюллетень' : 'Открыть страницу старта'"
          :aria-label="bulletinReady ? 'Открыть бюллетень' : 'Открыть страницу старта'"
        >
          <AdminIcon name="external" />
        </a>
        <button
          v-if="savedId"
          class="admin-icon-btn is-danger"
          type="button"
          title="Удалить старт"
          aria-label="Удалить старт"
          :disabled="pending"
          @click="onDelete"
        >
          <AdminIcon name="trash" />
        </button>
      </div>
    </form>

    <section v-if="!loading" class="bulletin-preview">
      <div class="bulletin-preview-head">
        <div>
          <h2 class="admin-block-title">Информационный бюллетень</h2>
          <p class="muted">
            <template v-if="!bulletinReady">
              Сначала сохраните анонс, затем создайте бюллетень или скопируйте из другого старта.
            </template>
            <template v-else-if="previewMode">
              Так страницу увидят на сайте. Нажмите карандаш, чтобы править блоки.
            </template>
            <template v-else>
              Нажмите блок → правьте → ✓ сохранить или ✕ отменить. Клик мимо блока тоже сохраняет.
            </template>
          </p>
          <p v-if="error" class="err">{{ error }}</p>
          <p v-else-if="saved" class="ok">{{ saved }}</p>
          <p v-else-if="savingBulletin || bulletinBusy" class="muted">Сохраняем…</p>
        </div>
        <div v-if="bulletinReady" class="admin-row-actions">
          <button
            class="admin-icon-btn"
            type="button"
            :class="{ 'is-on': previewMode }"
            :title="previewMode ? 'Редактировать' : 'Просмотр'"
            :aria-label="previewMode ? 'Редактировать' : 'Просмотр'"
            @click="previewMode = !previewMode"
          >
            <AdminIcon :name="previewMode ? 'edit' : 'eye'" />
          </button>
        </div>
      </div>

      <div v-if="!bulletinReady" class="admin-bulletin-setup">
        <button
          class="btn btn-primary btn-with-icon"
          type="button"
          :disabled="!upcomingId || bulletinBusy"
          @click="onInitBulletin"
        >
          <AdminIcon name="doc" />
          <span>{{ bulletinBusy ? "Создаём…" : "Создать бюллетень" }}</span>
        </button>

        <div class="admin-bulletin-copy">
          <label>
            Скопировать из старта
            <select v-model="copySourceId" :disabled="bulletinBusy || !copyOptions.length">
              <option value="">
                {{ copyOptions.length ? "Выберите старт…" : "Нет стартов с бюллетенем" }}
              </option>
              <option
                v-for="item in copyOptions"
                :key="item.id"
                :value="item.id"
              >
                {{ formatDateRu(item.competition_date) || "без даты" }} — {{ item.title }}
              </option>
            </select>
          </label>
          <button
            class="btn btn-with-icon"
            type="button"
            :disabled="!upcomingId || !copySourceId || bulletinBusy"
            @click="onCopyBulletin"
          >
            <AdminIcon name="copy" />
            <span>Скопировать</span>
          </button>
        </div>
      </div>

      <BulletinView
        v-else
        v-model="bulletin"
        editable
        :preview="previewMode"
        :saving="savingBulletin"
        :title="title || 'Название старта'"
        :date="competitionDate || null"
        :slug="slug || null"
        :upcoming-id="upcomingId"
        @error="error = $event"
        @save="onBulletinSave"
      />
    </section>
  </div>
</template>
