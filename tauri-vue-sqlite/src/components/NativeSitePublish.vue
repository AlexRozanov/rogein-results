<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import CollapsiblePanel from "./CollapsiblePanel.vue";

type SitePublishSettings = {
  api_base_url: string;
  publish_token: string;
  slug: string;
  title: string;
  suggested_slug: string;
  suggested_title: string;
};

type SitePublishResult = {
  slug: string;
  participant_count: number;
  result_count: number;
  has_map: boolean;
};

const props = defineProps<{
  busy: boolean;
}>();

const emit = defineEmits<{
  status: [message: string];
}>();

const localBusy = ref(false);
const apiBaseUrl = ref("http://127.0.0.1:18790");
const publishToken = ref("");
const slug = ref("");
const title = ref("");
const suggestedSlug = ref("");
const suggestedTitle = ref("");

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

async function publish() {
  localBusy.value = true;
  try {
    const result = await invoke<SitePublishResult>("publish_current_start", payload());
    emit(
      "status",
      `Опубликовано «${result.slug}»: участников ${result.participant_count}, строк ${result.result_count}${
        result.has_map ? ", карта отправлена" : ""
      }. Повторная публикация с тем же slug заменит результаты.`,
    );
  } catch (error) {
    emit("status", `Ошибка публикации: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

defineExpose({ refresh });
</script>

<template>
  <CollapsiblePanel panel-id="publish" title="Публикация на сайт">
    <p class="subtitle">
      Адрес и токен хранятся в папке программы на этом компьютере и не сбрасываются
      при завершении старта. Slug и название относятся к текущему старту: повторная
      отправка с тем же slug заменяет публикацию на сайте.
    </p>

    <h3>Доступ к серверу</h3>
    <div class="native-settings site-publish-settings">
      <label class="site-publish-wide">
        Адрес API
        <input
          v-model="apiBaseUrl"
          type="url"
          placeholder="http://127.0.0.1:18790"
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

    <h3>Этот старт</h3>
    <div class="native-settings site-publish-settings">
      <label>
        Slug
        <input
          v-model="slug"
          type="text"
          :placeholder="suggestedSlug || 'start-2026-04-12'"
          :disabled="isBusy"
        />
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
    </div>
  </CollapsiblePanel>
</template>
