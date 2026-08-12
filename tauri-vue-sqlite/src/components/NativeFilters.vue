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

const props = defineProps<{
  busy: boolean;
  status: string;
  search: string;
  formatId: string;
  awardGroupId: string;
  formats: FormatOption[];
  awardGroups: AwardGroupOption[];
}>();

const emit = defineEmits<{
  "update:status": [value: string];
  "update:search": [value: string];
  "update:formatId": [value: string];
  "update:awardGroupId": [value: string];
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
    <label>
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
    <span class="subtitle">Группа: {{ selectedAwardLabel }}</span>
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
        <option value="Нет в протоколе">Нет в протоколе</option>
      </select>
    </label>
    <span class="subtitle">Выбрано: {{ selectedStatusLabel }}</span>
    <label>
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
    <span class="subtitle">Формат: {{ selectedFormatLabel }}</span>
    <button :disabled="busy" @click="emit('apply')">Применить фильтр</button>
  </div>
</template>
