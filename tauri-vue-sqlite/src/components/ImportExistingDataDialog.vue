<script setup lang="ts">
export type ImportExistingKind = "finish" | "start" | "legends" | "courses";

const props = defineProps<{
  open: boolean;
  kind: ImportExistingKind;
  existingCount: number;
  fileName?: string | null;
  busy?: boolean;
}>();

const emit = defineEmits<{
  cancel: [];
  merge: [];
  replace: [];
}>();

const title = {
  finish: "Финишный протокол уже загружен",
  start: "Стартовый протокол уже загружен",
  legends: "Легенды КП уже загружены",
  courses: "Дистанции уже загружены",
} as const;

const mergeHint = {
  finish: "Добавить только новых участников (по номеру). Существующие не изменяются.",
  start: "Добавить только новых участников. Существующие не изменяются.",
  legends:
    "Добавить новые КП по номеру; для уже существующих обновить название и тип.",
  courses: "Обновить дистанции с совпадающим названием и добавить новые.",
} as const;

const replaceHint = {
  finish: "Удалить текущий финишный дамп и загрузить файл заново.",
  start: "Удалить текущий стартовый протокол и загрузить файл заново.",
  legends: "Удалить текущие легенды КП и загрузить файл заново.",
  courses: "Удалить текущие дистанции и загрузить файл заново.",
} as const;
</script>

<template>
  <div v-if="open" class="modal-backdrop" @click.self="!busy && emit('cancel')">
    <div
      class="modal-card"
      role="dialog"
      aria-modal="true"
      :aria-label="title[kind]"
    >
      <div class="modal-header">
        <h3>{{ title[kind] }}</h3>
        <button
          class="modal-close-btn"
          type="button"
          title="Закрыть"
          aria-label="Закрыть"
          :disabled="busy"
          @click="emit('cancel')"
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
        В базе уже есть записей: <strong>{{ existingCount }}</strong>.
        <template v-if="fileName"> Выбран файл «{{ fileName }}».</template>
      </p>
      <p class="subtitle">{{ mergeHint[kind] }}</p>
      <p class="subtitle">{{ replaceHint[kind] }}</p>

      <div class="native-row" style="margin-top: 12px; flex-wrap: wrap">
        <button type="button" :disabled="busy" @click="emit('cancel')">Отменить</button>
        <button type="button" :disabled="busy" @click="emit('merge')">Добавить новые</button>
        <button type="button" :disabled="busy" @click="emit('replace')">Загрузить заново</button>
      </div>
    </div>
  </div>
</template>
