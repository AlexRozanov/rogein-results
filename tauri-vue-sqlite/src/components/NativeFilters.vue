<script setup lang="ts">
import { computed, onBeforeUnmount } from "vue";

type FormatOption = {
  id: number;
  format_name: string;
};

type AwardGroupOption = {
  id: number;
  name: string;
};

type CourseOption = {
  id: number;
  name: string;
};

const props = defineProps<{
  busy: boolean;
  status: string;
  search: string;
  formatId: string;
  awardGroupId: string;
  courseName: string;
  formats: FormatOption[];
  awardGroups: AwardGroupOption[];
  courses: CourseOption[];
  isOrient?: boolean;
}>();

const emit = defineEmits<{
  "update:status": [value: string];
  "update:search": [value: string];
  "update:formatId": [value: string];
  "update:awardGroupId": [value: string];
  "update:courseName": [value: string];
  apply: [];
}>();

const selectedStatusLabel = computed(() => {
  if (props.status === "OK") return "OK";
  if (props.status === "Дисквалификация") return "Дисквалификация";
  if (props.status === "Ошибка") return "Ошибка";
  if (props.status === "Не стартовал") return "Не стартовал";
  if (props.status === "Нет в протоколе") return "Нет в протоколе";
  return "Все";
});

const selectedFormatLabel = computed(() => {
  if (props.formatId === "missing") return "Без формата";
  if (!props.formatId) return "Все";
  const found = props.formats.find((f) => String(f.id) === props.formatId);
  return found?.format_name ?? "Все";
});

const selectedAwardLabel = computed(() => {
  if (!props.awardGroupId) return "Все";
  const found = props.awardGroups.find((g) => String(g.id) === props.awardGroupId);
  return found?.name ?? "Все";
});

const selectedCourseLabel = computed(() => {
  if (!props.courseName) return props.courses[0]?.name ?? "—";
  return props.courses.find((c) => c.name === props.courseName)?.name ?? props.courseName;
});

let searchTimer: ReturnType<typeof setTimeout> | null = null;

function onSearchInput(event: Event) {
  const value = (event.target as HTMLInputElement).value;
  emit("update:search", value);
  if (searchTimer) clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    searchTimer = null;
    emit("apply");
  }, 250);
}

onBeforeUnmount(() => {
  if (searchTimer) clearTimeout(searchTimer);
});
</script>

<template>
  <div class="native-settings">
    <label>
      Поиск:
      <input
        type="search"
        :value="search"
        placeholder="ID или имя"
        :disabled="busy"
        @input="onSearchInput"
      />
    </label>
    <label v-if="!isOrient">
      Группа награждения:
      <select
        :value="awardGroupId"
        @change="emit('update:awardGroupId', ($event.target as HTMLSelectElement).value)"
      >
        <option value="">Все</option>
        <option v-for="g in awardGroups" :key="g.id" :value="String(g.id)">
          {{ g.name }}
        </option>
      </select>
    </label>
    <span v-if="!isOrient" class="subtitle">Группа: {{ selectedAwardLabel }}</span>
    <label>
      Статус:
      <select
        :value="status"
        @change="emit('update:status', ($event.target as HTMLSelectElement).value)"
      >
        <option value="">Все</option>
        <option value="OK">OK</option>
        <option value="Дисквалификация">Дисквалификация</option>
        <option value="Ошибка">Ошибка</option>
        <option value="Не стартовал">Не стартовал</option>
        <option v-if="!isOrient" value="Нет в протоколе">Нет в протоколе</option>
      </select>
    </label>
    <span class="subtitle">Выбрано: {{ selectedStatusLabel }}</span>
    <label v-if="!isOrient">
      Формат:
      <select
        :value="formatId"
        @change="emit('update:formatId', ($event.target as HTMLSelectElement).value)"
      >
        <option value="">Все форматы</option>
        <option value="missing">Без формата</option>
        <option v-for="f in formats" :key="f.id" :value="String(f.id)">
          {{ f.format_name }}
        </option>
      </select>
    </label>
    <span v-if="!isOrient" class="subtitle">Формат: {{ selectedFormatLabel }}</span>
    <label v-if="isOrient">
      Дистанция:
      <select
        :value="courseName"
        :disabled="busy || !courses.length"
        @change="emit('update:courseName', ($event.target as HTMLSelectElement).value)"
      >
        <option v-for="c in courses" :key="c.id" :value="c.name">
          {{ c.name }}
        </option>
      </select>
    </label>
    <span v-if="isOrient" class="subtitle">Дистанция: {{ selectedCourseLabel }}</span>
    <button :disabled="busy" @click="emit('apply')">Применить фильтр</button>
  </div>
</template>
