<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";

type ArchiveListItem = {
  file_name: string;
  path: string;
  title: string;
  created_at: string;
  competition_date: string | null;
  size_bytes: number;
};

type ActiveArchiveInfo = {
  path: string;
  title: string;
  file_name: string;
};

type DataPresenceCounts = {
  finish_participants: number;
  start_protocol: number;
  cp_legends: number;
};

type ArchiveActionResult = {
  archive_path: string;
  title: string;
  cleared: boolean;
};

const props = defineProps<{
  busy: boolean;
}>();

const emit = defineEmits<{
  status: [message: string];
  workspaceReset: [];
}>();

const archivesDir = ref("");
const archives = ref<ArchiveListItem[]>([]);
const activeArchive = ref<ActiveArchiveInfo | null>(null);
const localBusy = ref(false);
const finishOpen = ref(false);
const finishName = ref("");
const finishOverwrite = ref(false);
const openConfirmOpen = ref(false);
const pendingOpenPath = ref<string | null>(null);

const isBusy = computed(() => props.busy || localBusy.value);
const hasActiveArchive = computed(() => activeArchive.value != null);

onMounted(() => {
  void refresh();
});

async function refresh() {
  try {
    archivesDir.value = await invoke<string>("get_archives_dir");
    archives.value = await invoke<ArchiveListItem[]>("list_start_archives");
    activeArchive.value = await invoke<ActiveArchiveInfo | null>("get_active_start_archive");
  } catch (error) {
    emit("status", `Ошибка списка архивов: ${String(error)}`);
  }
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} Б`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} КБ`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} МБ`;
}

function formatCreatedAt(value: string): string {
  if (!value) return "—";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString("ru-RU");
}

function isActiveItem(item: ArchiveListItem): boolean {
  return activeArchive.value?.path === item.path;
}

async function openFinishDialog() {
  localBusy.value = true;
  try {
    activeArchive.value = await invoke<ActiveArchiveInfo | null>("get_active_start_archive");
    finishName.value = await invoke<string>("suggest_finish_start_name");
    finishOverwrite.value = false;
    finishOpen.value = true;
  } catch (error) {
    emit("status", `Ошибка подготовки завершения старта: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function confirmFinish(mode: "overwrite_active" | "save_as") {
  localBusy.value = true;
  try {
    const result = await invoke<ArchiveActionResult>("finish_current_start", {
      mode,
      archiveName: mode === "save_as" ? finishName.value : null,
      overwrite: mode === "save_as" ? finishOverwrite.value : false,
    });
    finishOpen.value = false;
    finishOverwrite.value = false;
    emit(
      "status",
      `Старт «${result.title}» сохранён в архив и рабочая база очищена.`,
    );
    emit("workspaceReset");
    await refresh();
  } catch (error) {
    const message = String(error);
    if (mode === "save_as" && message.includes("уже существует")) {
      finishOverwrite.value = true;
      emit("status", `${message} Нажмите ещё раз для перезаписи.`);
    } else {
      emit("status", `Ошибка завершения старта: ${message}`);
    }
  } finally {
    localBusy.value = false;
  }
}

async function hasWorkingData(): Promise<boolean> {
  const counts = await invoke<DataPresenceCounts>("get_data_presence_counts");
  return (
    counts.finish_participants > 0 ||
    counts.start_protocol > 0 ||
    counts.cp_legends > 0
  );
}

async function requestOpen(path: string) {
  pendingOpenPath.value = path;
  localBusy.value = true;
  try {
    if (await hasWorkingData()) {
      openConfirmOpen.value = true;
      return;
    }
    await doOpen(false);
  } catch (error) {
    emit("status", `Ошибка открытия архива: ${String(error)}`);
    pendingOpenPath.value = null;
  } finally {
    localBusy.value = false;
  }
}

async function doOpen(archiveCurrentFirst: boolean) {
  const path = pendingOpenPath.value;
  if (!path) return;
  localBusy.value = true;
  openConfirmOpen.value = false;
  try {
    const result = await invoke<ArchiveActionResult>("open_start_archive", {
      archivePath: path,
      archiveCurrentFirst,
    });
    pendingOpenPath.value = null;
    emit("status", `Открыт архив «${result.title}».`);
    emit("workspaceReset");
    await refresh();
  } catch (error) {
    emit("status", `Ошибка открытия архива: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function pickAndOpen() {
  try {
    const path = await invoke<string | null>("pick_start_archive_file");
    if (!path) return;
    await requestOpen(path);
  } catch (error) {
    emit("status", `Ошибка выбора файла: ${String(error)}`);
  }
}

defineExpose({ refresh });
</script>

<template>
  <section class="native-tools nested-card">
    <h2>Архивы стартов</h2>
    <p class="subtitle">
      «Завершить старт» сохраняет все данные в файл
      <code>.rogein</code>
      и очищает рабочую базу. Архивы из папки программы можно открыть из списка;
      также можно выбрать файл с другого диска или компьютера.
    </p>
    <p v-if="archivesDir" class="subtitle">Папка архивов: {{ archivesDir }}</p>
    <p v-if="activeArchive" class="subtitle">
      Сейчас открыт архив:
      <strong>{{ activeArchive.title }}</strong>
      ({{ activeArchive.file_name }})
    </p>

    <div class="native-row" style="flex-wrap: wrap">
      <button type="button" :disabled="isBusy" @click="openFinishDialog">
        Завершить старт
      </button>
      <button type="button" :disabled="isBusy" @click="pickAndOpen">
        Выбрать файл…
      </button>
      <button type="button" :disabled="isBusy" @click="refresh">Обновить список</button>
    </div>

    <div v-if="archives.length" class="archive-list">
      <button
        v-for="item in archives"
        :key="item.path"
        type="button"
        class="archive-item"
        :class="{ active: isActiveItem(item) }"
        :disabled="isBusy"
        @click="requestOpen(item.path)"
      >
        <span class="archive-item-title">
          {{ item.title || item.file_name }}
          <span v-if="isActiveItem(item)" class="archive-item-badge">открыт</span>
        </span>
        <span class="archive-item-meta">
          {{ item.file_name }} · {{ formatCreatedAt(item.created_at) }} ·
          {{ formatSize(item.size_bytes) }}
        </span>
      </button>
    </div>
    <p v-else class="subtitle">В папке программы пока нет архивов `.rogein`.</p>
  </section>

  <div
    v-if="finishOpen"
    class="modal-backdrop"
    @click.self="!isBusy && (finishOpen = false)"
  >
    <div class="modal-card" role="dialog" aria-modal="true" aria-label="Завершить старт">
      <div class="modal-header">
        <h3>Завершить старт</h3>
        <button
          class="modal-close-btn"
          type="button"
          title="Закрыть"
          aria-label="Закрыть"
          :disabled="isBusy"
          @click="finishOpen = false"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path
              d="M18.3 5.7a1 1 0 0 0-1.4-1.4L12 9.17 7.1 4.3A1 1 0 0 0 5.7 5.7L10.59 10.6 5.7 15.5a1 1 0 1 0 1.4 1.4L12 12l4.9 4.9a1 1 0 0 0 1.4-1.4L13.41 10.6l4.89-4.9z"
              fill="currentColor"
            />
          </svg>
        </button>
      </div>

      <p>
        Все текущие данные будут записаны в архив, после чего рабочая база и карта
        будут очищены для нового старта.
      </p>

      <template v-if="hasActiveArchive && activeArchive">
        <p>
          Сейчас открыт архив
          <strong>{{ activeArchive.title }}</strong>
          ({{ activeArchive.file_name }}). Выберите действие:
        </p>
        <div class="native-row" style="margin-top: 12px; flex-wrap: wrap">
          <button type="button" :disabled="isBusy" @click="finishOpen = false">Отменить</button>
          <button type="button" :disabled="isBusy" @click="confirmFinish('overwrite_active')">
            Перезаписать этот архив
          </button>
        </div>
        <hr class="archive-finish-sep" />
        <label>
          Или сохранить под новым именем
          <input v-model="finishName" type="text" :disabled="isBusy" />
        </label>
        <p v-if="finishOverwrite" class="subtitle">
          Файл с таким именем уже есть — следующее подтверждение перезапишет его.
        </p>
        <div class="native-row" style="margin-top: 12px; flex-wrap: wrap">
          <button
            type="button"
            :disabled="isBusy || !finishName.trim()"
            @click="confirmFinish('save_as')"
          >
            {{ finishOverwrite ? "Перезаписать новое имя и очистить" : "Создать новый архив и очистить" }}
          </button>
        </div>
      </template>

      <template v-else>
        <label>
          Имя архива
          <input v-model="finishName" type="text" :disabled="isBusy" />
        </label>
        <p v-if="finishOverwrite" class="subtitle">
          Файл с таким именем уже есть — следующее подтверждение перезапишет его.
        </p>
        <div class="native-row" style="margin-top: 12px; flex-wrap: wrap">
          <button type="button" :disabled="isBusy" @click="finishOpen = false">Отменить</button>
          <button
            type="button"
            :disabled="isBusy || !finishName.trim()"
            @click="confirmFinish('save_as')"
          >
            {{ finishOverwrite ? "Перезаписать и очистить" : "Сохранить и очистить" }}
          </button>
        </div>
      </template>
    </div>
  </div>

  <div
    v-if="openConfirmOpen"
    class="modal-backdrop"
    @click.self="!isBusy && ((openConfirmOpen = false), (pendingOpenPath = null))"
  >
    <div class="modal-card" role="dialog" aria-modal="true" aria-label="Открыть архив">
      <div class="modal-header">
        <h3>В рабочей базе уже есть данные</h3>
        <button
          class="modal-close-btn"
          type="button"
          title="Закрыть"
          aria-label="Закрыть"
          :disabled="isBusy"
          @click="openConfirmOpen = false; pendingOpenPath = null"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path
              d="M18.3 5.7a1 1 0 0 0-1.4-1.4L12 9.17 7.1 4.3A1 1 0 0 0 5.7 5.7L10.59 10.6 5.7 15.5a1 1 0 1 0 1.4 1.4L12 12l4.9 4.9a1 1 0 0 0 1.4-1.4L13.41 10.6l4.89-4.9z"
              fill="currentColor"
            />
          </svg>
        </button>
      </div>
      <p>Как поступить перед открытием архива?</p>
      <div class="native-row" style="margin-top: 12px; flex-wrap: wrap">
        <button
          type="button"
          :disabled="isBusy"
          @click="openConfirmOpen = false; pendingOpenPath = null"
        >
          Отменить
        </button>
        <button type="button" :disabled="isBusy" @click="doOpen(true)">
          Сначала завершить текущий
        </button>
        <button type="button" :disabled="isBusy" @click="doOpen(false)">
          Заменить без сохранения
        </button>
      </div>
    </div>
  </div>
</template>
