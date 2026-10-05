<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { invoke } from "@tauri-apps/api/core";
import FilePickerButton from "./FilePickerButton.vue";
import DateInput from "./DateInput.vue";
import IconActionButton from "./IconActionButton.vue";
import OverwriteSiteEventDialog from "./OverwriteSiteEventDialog.vue";
import { buildEventSlug } from "../eventSlug";

type SitePublishSettings = {
  api_base_url: string;
  publish_token: string;
  suggested_slug: string;
};

type ArchiveCoursePreview = {
  name: string;
  count: number;
};

type ArchiveParticipantPreview = {
  name: string;
  course: string;
  elapsed_seconds: number;
  place: number | null;
  result: string;
};

type ArchiveOrientPreview = {
  row_count: number;
  ok_count: number;
  dsq_count: number;
  dnf_count: number;
  dns_count: number;
  courses: ArchiveCoursePreview[];
  participants: ArchiveParticipantPreview[];
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
const selectedCourse = ref("");
const overwriteOpen = ref(false);

const isBusy = computed(() => props.busy || localBusy.value);
const visibleParticipants = computed(() => {
  const rows = preview.value?.participants ?? [];
  if (!selectedCourse.value) return rows;
  return rows.filter((row) => row.course === selectedCourse.value);
});
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

watch([competitionDate, title], () => {
  if (slug.value.trim()) return;
  const generated = buildEventSlug(competitionDate.value, title.value);
  if (generated) slug.value = generated;
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
  selectedCourse.value = "";
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
  if (!slug.value.trim()) {
    slug.value =
      buildEventSlug(competitionDate.value, title.value) || preview.value.suggested_slug;
  }
}

function generateSlug() {
  const generated = buildEventSlug(competitionDate.value, title.value);
  if (!generated) {
    emit("status", "Для slug нужны название старта и дата.");
    return;
  }
  slug.value = generated;
}

function fmtHms(totalSeconds: number) {
  const s = Math.max(0, totalSeconds | 0);
  const hh = String(Math.floor(s / 3600)).padStart(2, "0");
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, "0");
  const ss = String(s % 60).padStart(2, "0");
  return `${hh}:${mm}:${ss}`;
}

async function publish() {
  if (!canPublish.value) {
    emit("status", "Выберите CSV и укажите название, slug и дату.");
    return;
  }
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
    emit("status", `Ошибка загрузки архива: ${String(error)}`);
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
    emit("status", `Ошибка загрузки архива: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function uploadToSite() {
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
      (OK {{ preview.ok_count }}, DSQ {{ preview.dsq_count }}, DNF {{ preview.dnf_count }}, DNS {{ preview.dns_count }}).
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
        <div class="slug-field-row">
          <input
            v-model="slug"
            type="text"
            :disabled="isBusy"
            placeholder="start-2024-04-12-usadba-trubeckih"
          />
          <IconActionButton
            variant="generate"
            label="Собрать slug из даты и названия"
            :disabled="isBusy"
            @click="generateSlug"
          />
        </div>
      </label>
      <label>
        Дата
        <DateInput v-model="competitionDate" :disabled="isBusy" />
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

    <div v-if="preview" class="archive-participants">
      <div class="native-settings" style="margin-top: 16px">
        <label>
          Дистанция
          <select v-model="selectedCourse" :disabled="isBusy">
            <option value="">Все дистанции</option>
            <option v-for="course in preview.courses" :key="course.name" :value="course.name">
              {{ course.name }} ({{ course.count }})
            </option>
          </select>
        </label>
      </div>
      <div class="native-results-wrap">
        <table class="native-results">
          <thead>
            <tr>
              <th>Место</th>
              <th>Имя</th>
              <th>Дистанция</th>
              <th>Время</th>
              <th>Результат</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!visibleParticipants.length">
              <td colspan="5">Нет участников на выбранной дистанции.</td>
            </tr>
            <tr v-for="(row, index) in visibleParticipants" :key="`${row.name}-${index}`">
              <td>{{ row.place ?? "—" }}</td>
              <td>{{ row.name }}</td>
              <td>{{ row.course }}</td>
              <td>{{ row.result === "DNS" ? "—" : fmtHms(row.elapsed_seconds) }}</td>
              <td>{{ row.result }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="native-row" style="margin-top: 12px">
      <button type="button" :disabled="isBusy || !canPublish" @click="publish">
        Загрузить на сайт
      </button>
    </div>

    <OverwriteSiteEventDialog
      :open="overwriteOpen"
      :busy="isBusy"
      @yes="onOverwriteYes"
      @no="onOverwriteNo"
    />
  </div>
</template>
