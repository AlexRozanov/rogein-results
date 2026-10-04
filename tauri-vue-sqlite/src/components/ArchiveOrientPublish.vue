<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { invoke } from "@tauri-apps/api/core";
import FilePickerButton from "./FilePickerButton.vue";

type SitePublishSettings = {
  api_base_url: string;
  publish_token: string;
  suggested_slug: string;
};

type ArchiveCoursePreview = {
  name: string;
  count: number;
};

type ArchiveOrientPreview = {
  row_count: number;
  ok_count: number;
  dsq_count: number;
  courses: ArchiveCoursePreview[];
  suggested_slug: string;
};

type SitePublishResult = {
  slug: string;
  participant_count: number;
  result_count: number;
};

const props = defineProps<{
  busy?: boolean;
}>();

const emit = defineEmits<{
  status: [message: string];
  published: [result: SitePublishResult];
}>();

const localBusy = ref(false);
const csvFile = ref<File | null>(null);
const csvContent = ref("");
const title = ref("");
const slug = ref("");
const competitionDate = ref("");
const apiBaseUrl = ref("http://127.0.0.1:18790");
const publishToken = ref("");
const preview = ref<ArchiveOrientPreview | null>(null);

const isBusy = computed(() => props.busy || localBusy.value);
const canPublish = computed(
  () =>
    Boolean(csvContent.value) &&
    Boolean(title.value.trim()) &&
    Boolean(slug.value.trim()) &&
    Boolean(competitionDate.value.trim()) &&
    Boolean(preview.value),
);

onMounted(() => {
  void loadDefaults();
});

watch(competitionDate, (date) => {
  if (!slug.value.trim() && date) {
    slug.value = `orient-${date}`;
  }
});

async function loadDefaults() {
  try {
    const settings = await invoke<SitePublishSettings>("get_site_publish_settings");
    apiBaseUrl.value = settings.api_base_url || apiBaseUrl.value;
    publishToken.value = settings.publish_token || "";
  } catch {
    // stay with defaults
  }
  try {
    const native = await invoke<{ competition_date: string }>("get_settings");
    if (!competitionDate.value && native.competition_date) {
      competitionDate.value = native.competition_date;
    }
  } catch {
    // date is entered by the user
  }
}

async function onFileSelected(file: File | null) {
  csvFile.value = file;
  csvContent.value = "";
  preview.value = null;
  if (!file) return;
  localBusy.value = true;
  try {
    const text = await file.text();
    csvContent.value = text;
    if (!title.value.trim()) {
      title.value = file.name.replace(/\.csv$/i, "").trim();
    }
    await refreshPreview();
  } catch (error) {
    emit("status", `Ошибка чтения файла: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function refreshPreview() {
  if (!csvContent.value) return;
  preview.value = await invoke<ArchiveOrientPreview>("preview_archive_orient_csv", {
    csvContent: csvContent.value,
    competitionDate: competitionDate.value.trim(),
  });
  if (!slug.value.trim() && preview.value.suggested_slug) {
    slug.value = preview.value.suggested_slug;
  }
}

async function publish() {
  if (!canPublish.value) {
    emit("status", "Выберите CSV и укажите название, slug и дату.");
    return;
  }
  localBusy.value = true;
  try {
    if (csvContent.value) {
      preview.value = await invoke<ArchiveOrientPreview>("preview_archive_orient_csv", {
        csvContent: csvContent.value,
        competitionDate: competitionDate.value.trim(),
      });
    }
    const result = await invoke<SitePublishResult>("publish_archive_orient_csv", {
      csvContent: csvContent.value,
      title: title.value.trim(),
      slug: slug.value.trim(),
      competitionDate: competitionDate.value.trim(),
      apiBaseUrl: apiBaseUrl.value.trim(),
      publishToken: publishToken.value.trim(),
    });
    emit(
      "status",
      `На сайт загружено «${result.slug}»: участников ${result.participant_count}, строк ${result.result_count}. Повтор с тем же slug заменит старт.`,
    );
    emit("published", result);
  } catch (error) {
    emit("status", `Ошибка загрузки архива: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}
</script>

<template>
  <div class="archive-orient-publish">
    <p class="subtitle">
      Готовые результаты ориентирования: место, имя, время, дистанция и статус
      берутся из колонок файла. Пары КП/время только для карточки, без пересчёта.
      Номера на сайте не публикуются.
    </p>

    <FilePickerButton
      button-label="Выбрать CSV результатов"
      empty-label="Файл не выбран"
      :disabled="isBusy"
      @file-selected="onFileSelected"
    />

    <div v-if="preview" class="subtitle" style="margin-top: 10px">
      В файле {{ preview.row_count }} участников
      (OK {{ preview.ok_count }}, DSQ {{ preview.dsq_count }}).
      Дистанции:
      {{ preview.courses.map((c) => `${c.name} (${c.count})`).join(", ") || "—" }}
    </div>

    <div class="native-settings site-publish-settings" style="margin-top: 12px">
      <label class="site-publish-wide">
        Название старта
        <input v-model="title" type="text" :disabled="isBusy" placeholder="Усадьба Трубецких" />
      </label>
      <label>
        Slug
        <input v-model="slug" type="text" :disabled="isBusy" placeholder="orient-2024-04-12" />
      </label>
      <label>
        Дата
        <input v-model="competitionDate" type="date" :disabled="isBusy" />
      </label>
      <label class="site-publish-wide">
        Адрес сайта
        <input v-model="apiBaseUrl" type="url" :disabled="isBusy" />
      </label>
      <label class="site-publish-wide">
        Токен публикации
        <input v-model="publishToken" type="password" autocomplete="off" :disabled="isBusy" />
      </label>
    </div>

    <div class="native-row" style="margin-top: 12px">
      <button type="button" :disabled="isBusy || !canPublish" @click="publish">
        Загрузить на сайт
      </button>
    </div>
  </div>
</template>
