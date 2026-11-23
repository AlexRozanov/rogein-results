<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import CollapsiblePanel from "./CollapsiblePanel.vue";
import IconActionButton from "./IconActionButton.vue";
import OverwriteSiteEventDialog from "./OverwriteSiteEventDialog.vue";
import { buildEventSlug } from "../eventSlug";

type SitePublishSettings = {
  api_base_url: string;
  publish_token: string;
  slug: string;
  title: string;
  suggested_slug: string;
  suggested_title: string;
  competition_date?: string;
};

type SitePublishResult = {
  slug: string;
  participant_count: number;
  result_count: number;
  has_map: boolean;
  award_group_count?: number;
  legend_count?: number;
};

type UpcomingSiteStart = {
  slug: string;
  title: string;
  competition_date: string | null;
  sport_kind: string;
  summary: string;
};

const props = defineProps<{
  busy: boolean;
  accessOnly?: boolean;
  isOrient?: boolean;
}>();

const emit = defineEmits<{
  status: [message: string];
  settingsChanged: [];
}>();

const localBusy = ref(false);
const apiBaseUrl = ref("http://127.0.0.1:18790");
const publishToken = ref("");
const slug = ref("");
const title = ref("");
const suggestedSlug = ref("");
const suggestedTitle = ref("");
const overwriteOpen = ref(false);

const pickerOpen = ref(false);
const pickerLoading = ref(false);
const pickerError = ref("");
const pickerItems = ref<UpcomingSiteStart[]>([]);

const isBusy = computed(() => props.busy || localBusy.value);

onMounted(() => {
  void refresh();
});

async function refresh() {
  try {
    const settings = await invoke<SitePublishSettings>("get_site_publish_settings");
    applySettings(settings);
  } catch (error) {
    emit("status", `Ошибка настроек публикации: ${String(error)}`);
  }
}

function applySettings(settings: SitePublishSettings) {
  apiBaseUrl.value = settings.api_base_url || "http://127.0.0.1:18790";
  publishToken.value = settings.publish_token || "";
  slug.value = settings.slug || settings.suggested_slug || "";
  title.value = settings.title || settings.suggested_title || "";
  suggestedSlug.value = settings.suggested_slug;
  suggestedTitle.value = settings.suggested_title;
}

async function generateSlug() {
  try {
    const settings = await invoke<{ competition_date: string }>("get_settings");
    const generated = buildEventSlug(settings.competition_date, title.value);
    if (!generated) {
      emit("status", "Для slug нужны название старта и дата соревнования.");
      return;
    }
    slug.value = generated;
  } catch (error) {
    emit("status", `Не удалось собрать slug: ${String(error)}`);
  }
}

function payload() {
  return {
    apiBaseUrl: apiBaseUrl.value.trim(),
    publishToken: publishToken.value.trim(),
    slug: slug.value.trim(),
    title: title.value.trim(),
  };
}

async function saveAccess() {
  localBusy.value = true;
  try {
    const settings = await invoke<SitePublishSettings>("save_site_publish_settings", payload());
    applySettings(settings);
    emit("status", "Доступ к сайту сохранён на этом компьютере.");
  } catch (error) {
    emit("status", `Ошибка сохранения доступа: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function testConnection() {
  localBusy.value = true;
  try {
    const base = await invoke<string>("test_site_publish_connection", {
      apiBaseUrl: apiBaseUrl.value.trim(),
    });
    emit("status", `Сайт отвечает: ${base}`);
  } catch (error) {
    emit("status", `Нет связи с сайтом: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function openUpcomingPicker() {
  const base = apiBaseUrl.value.trim();
  if (!base) {
    emit("status", "Сначала укажите адрес сайта.");
    return;
  }
  pickerOpen.value = true;
  pickerLoading.value = true;
  pickerError.value = "";
  pickerItems.value = [];
  localBusy.value = true;
  try {
    // Сохраняем адрес/токен, как на телефоне перед «Взять с сайта».
    await invoke<SitePublishSettings>("save_site_publish_settings", payload());
    const settings = await invoke<{ sport_kind: string }>("get_settings");
    const items = await invoke<UpcomingSiteStart[]>("fetch_upcoming_site_starts", {
      apiBaseUrl: base,
      sportKind: settings.sport_kind || null,
    });
    pickerItems.value = items;
  } catch (error) {
    pickerError.value = String(error);
  } finally {
    pickerLoading.value = false;
    localBusy.value = false;
  }
}

function closeUpcomingPicker() {
  if (localBusy.value && pickerLoading.value) return;
  pickerOpen.value = false;
  pickerError.value = "";
  pickerItems.value = [];
}

async function applyUpcoming(item: UpcomingSiteStart) {
  localBusy.value = true;
  pickerError.value = "";
  try {
    const dateRaw =
      typeof item.competition_date === "string"
        ? item.competition_date
        : item.competition_date != null
          ? String(item.competition_date)
          : "";
    const settings = await invoke<SitePublishSettings>("apply_upcoming_site_start", {
      slug: item.slug,
      title: item.title,
      competitionDate: dateRaw.trim() || null,
    });
    applySettings(settings);
    pickerOpen.value = false;
    const label = item.title.trim() || item.slug;
    const date = dateRaw.trim();
    emit(
      "status",
      date
        ? `С сайта: «${label}», дата ${date}, slug ${item.slug}.`
        : `С сайта: «${label}», slug ${item.slug}.`,
    );
    emit("settingsChanged");
  } catch (error) {
    const msg = `Не удалось взять старт с сайта: ${String(error)}`;
    pickerError.value = msg;
    emit("status", msg);
  } finally {
    localBusy.value = false;
  }
}

async function publish() {
  localBusy.value = true;
  try {
    const exists = await invoke<boolean>("site_event_exists", {
      apiBaseUrl: apiBaseUrl.value.trim(),
      slug: slug.value.trim(),
    });
    if (exists) {
      overwriteOpen.value = true;
      return;
    }
    await uploadToSite();
  } catch (error) {
    emit("status", `Ошибка публикации: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

function onOverwriteNo() {
  overwriteOpen.value = false;
}

async function onOverwriteYes() {
  overwriteOpen.value = false;
  localBusy.value = true;
  try {
    await uploadToSite();
  } catch (error) {
    emit("status", `Ошибка публикации: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function uploadToSite() {
  const result = await invoke<SitePublishResult>("publish_current_start", payload());
  emit(
    "status",
    `Опубликовано «${result.slug}»: участников ${result.participant_count}, строк ${result.result_count}${
      result.has_map ? ", карта отправлена" : ""
    }. Повторная публикация с тем же slug заменит результаты.`,
  );
}

function packOnSiteStatus(result: SitePublishResult, verb: string) {
  const groups = result.award_group_count ?? 0;
  const legends = result.legend_count ?? 0;
  const map = result.has_map ? "карта есть" : "карты нет";
  return `${verb} «${result.slug}»: участников ${result.participant_count}, форматов ${result.result_count}, групп ${groups}, КП ${legends}, ${map}.`;
}

async function uploadEventPack() {
  localBusy.value = true;
  try {
    const result = await invoke<SitePublishResult>("upload_event_pack_to_site", payload());
    emit(
      "status",
      `${packOnSiteStatus(result, "Пакет старта на сайте")} Телефон: Протокол → Загрузить → Пакет с сайта.`,
    );
  } catch (error) {
    emit("status", `Ошибка отправки пакета: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function verifyEventPack() {
  localBusy.value = true;
  try {
    const result = await invoke<SitePublishResult>("verify_event_pack_on_site", {
      apiBaseUrl: apiBaseUrl.value.trim(),
      publishToken: publishToken.value.trim(),
      slug: slug.value.trim(),
    });
    emit("status", packOnSiteStatus(result, "На сайте лежит пакет"));
  } catch (error) {
    emit("status", String(error));
  } finally {
    localBusy.value = false;
  }
}

defineExpose({ refresh });
</script>

<template>
  <CollapsiblePanel panel-id="publish" title="Публикация на сайт">
    <p class="subtitle">
      Адрес — корень сайта, как в браузере: https://malahit-sprint.ru
      (без :18790). Порт API снаружи не открыт, nginx сам проксирует /api/.
      Адрес и токен хранятся в папке программы на этом компьютере и не сбрасываются
      при завершении старта.
      <template v-if="!props.accessOnly">
        Slug и название относятся к текущему старту: повторная
        отправка с тем же slug заменяет публикацию на сайте.
      </template>
    </p>

    <h3>Доступ к серверу</h3>
    <div class="native-settings site-publish-settings">
      <label class="site-publish-wide">
        Адрес API
        <input
          v-model="apiBaseUrl"
          type="url"
          placeholder="https://malahit-sprint.ru"
          :disabled="isBusy"
        />
      </label>
      <label class="site-publish-wide">
        Токен публикации
        <input
          v-model="publishToken"
          type="password"
          autocomplete="off"
          placeholder="Bearer-токен"
          :disabled="isBusy"
        />
      </label>
    </div>
    <div class="native-row">
      <button type="button" :disabled="isBusy" @click="saveAccess">Сохранить доступ</button>
      <button type="button" :disabled="isBusy" @click="testConnection">Проверить связь</button>
    </div>

    <template v-if="!props.accessOnly">
      <h3>Этот старт</h3>
      <div class="native-row" style="margin-bottom: 8px">
        <button type="button" :disabled="isBusy" @click="openUpcomingPicker">
          Взять с сайта
        </button>
      </div>
      <p class="subtitle" style="margin-top: 0">
        Подставит название, дату соревнования и slug из анонса календаря
        (как «Взять с сайта» на телефоне).
      </p>
      <div class="native-settings site-publish-settings">
        <label>
          Slug
          <div class="slug-field-row">
            <input
              v-model="slug"
              type="text"
              :placeholder="suggestedSlug || 'start-2026-04-12-usadba-trubeckih'"
              :disabled="isBusy"
            />
            <IconActionButton
              variant="generate"
              label="Собрать slug из даты и названия"
              :disabled="isBusy"
              @click="generateSlug"
            />
          </div>
        </label>
        <label class="site-publish-wide">
          Название
          <input
            v-model="title"
            type="text"
            :placeholder="suggestedTitle || 'Название старта'"
            :disabled="isBusy"
          />
        </label>
      </div>
      <div class="native-row">
        <button type="button" :disabled="isBusy" @click="publish">Опубликовать</button>
        <button type="button" :disabled="isBusy" @click="uploadEventPack">
          Отправить пакет на телефон (сайт)
        </button>
        <button type="button" :disabled="isBusy" @click="verifyEventPack">
          Проверить пакет на сайте
        </button>
      </div>
      <p class="subtitle">
        <template v-if="props.isOrient">
          Пакет ориентирования — протокол, дистанции и карта для телефона.
        </template>
        <template v-else>
          Пакет рогейна — протокол, форматы, группы, легенды и карта для телефона.
        </template>
        На сайте он не публикуется в календарь. Телефон скачивает его по slug и токену
        (Ещё → Публикация: адрес, токен; затем Протокол → Загрузить → Пакет с сайта).
        «Проверить пакет на сайте» читает уже загруженный пакет.
      </p>
    </template>
  </CollapsiblePanel>

  <OverwriteSiteEventDialog
    :open="overwriteOpen"
    :busy="isBusy"
    @yes="onOverwriteYes"
    @no="onOverwriteNo"
  />

  <div
    v-if="pickerOpen"
    class="modal-backdrop"
    @click.self="!pickerLoading && closeUpcomingPicker()"
  >
    <div
      class="modal-card upcoming-picker-card"
      role="dialog"
      aria-modal="true"
      aria-label="Старт с сайта"
    >
      <div class="modal-header">
        <h3>Старт с сайта</h3>
        <button
          class="modal-close-btn"
          type="button"
          title="Закрыть"
          aria-label="Закрыть"
          :disabled="pickerLoading"
          @click="closeUpcomingPicker"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path
              d="M18.3 5.7a1 1 0 0 0-1.4-1.4L12 9.17 7.1 4.3A1 1 0 0 0 5.7 5.7L10.59 10.6 5.7 15.5a1 1 0 1 0 1.4 1.4L12 12l4.9 4.9a1 1 0 0 0 1.4-1.4L13.41 10.6l4.89-4.9z"
              fill="currentColor"
            />
          </svg>
        </button>
      </div>

      <p v-if="pickerLoading" class="subtitle">Загрузка календаря…</p>
      <p v-else-if="pickerError && pickerItems.length === 0" class="upcoming-picker-error">
        {{ pickerError }}
      </p>
      <p v-else-if="pickerItems.length === 0" class="subtitle">
        В календаре нет анонсированных стартов для этого вида.
      </p>
      <template v-else>
        <p class="subtitle">
          Подставит название, дату и slug. Можно пропустить и заполнить вручную.
        </p>
        <p v-if="pickerError" class="upcoming-picker-error">{{ pickerError }}</p>
        <ul class="upcoming-picker-list">
          <li v-for="item in pickerItems" :key="item.slug">
            <button
              type="button"
              class="upcoming-picker-item"
              :disabled="isBusy"
              @click="applyUpcoming(item)"
            >
              <span class="upcoming-picker-title">{{
                item.title.trim() || item.slug
              }}</span>
              <span class="upcoming-picker-meta">
                {{
                  (typeof item.competition_date === "string"
                    ? item.competition_date.trim()
                    : "") || "без даты"
                }}
                · {{ item.slug }}
              </span>
            </button>
          </li>
        </ul>
      </template>

      <div class="native-row" style="margin-top: 12px">
        <button type="button" :disabled="pickerLoading" @click="closeUpcomingPicker">
          {{ pickerLoading ? "Отмена" : "Пропустить" }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.upcoming-picker-card {
  max-width: 28rem;
  width: min(28rem, 100%);
}

.upcoming-picker-error {
  color: #b91c1c;
  margin: 0.5rem 0;
}

.upcoming-picker-list {
  list-style: none;
  margin: 0.5rem 0 0;
  padding: 0;
  max-height: 22rem;
  overflow: auto;
}

.upcoming-picker-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.15rem;
  width: 100%;
  text-align: left;
  padding: 0.65rem 0.75rem;
  margin: 0.15rem 0;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font: inherit;
}

.upcoming-picker-item:hover:not(:disabled),
.upcoming-picker-item:focus-visible {
  border-color: #475569;
  background: rgba(148, 163, 184, 0.12);
}

.upcoming-picker-title {
  font-weight: 600;
}

.upcoming-picker-meta {
  font-size: 0.85em;
  opacity: 0.75;
}
</style>
