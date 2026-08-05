<script setup lang="ts">
import { computed } from "vue";

type FormatOption = {
  id: number;
  format_name: string;
};

const props = defineProps<{
  busy: boolean;
  status: string;
  search: string;
  formatId: string;
  formats: FormatOption[];
}>();

const emit = defineEmits<{
  "update:status": [value: string];
  "update:search": [value: string];
  "update:formatId": [value: string];
  apply: [];
}>();

const selectedStatusLabel = computed(() => {
  if (props.status === "OK") return "OK";
  if (props.status === "DQ") return "DQ";
  if (props.status === "ERR") return "ERR";
  return "Все";
});

const selectedFormatLabel = computed(() => {
  if (props.formatId === "missing") return "Без формата";
  if (!props.formatId) return "Все";
  const found = props.formats.find((f) => String(f.id) === props.formatId);
  return found?.format_name ?? "Все";
});
</script>

<template>
  <div class="native-settings">
    <label>
      Статус:
      <select
        :value="status"
        @change="emit('update:status', ($event.target as HTMLSelectElement).value)"
      >
        <option value="">Все</option>
        <option value="OK">OK</option>
        <option value="DQ">DQ</option>
        <option value="ERR">ERR</option>
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
    <label>
      Поиск:
      <input
        :value="search"
        type="text"
        placeholder="ID или имя"
        @input="emit('update:search', ($event.target as HTMLInputElement).value)"
      />
    </label>
    <button :disabled="busy" @click="emit('apply')">Применить фильтр</button>
  </div>
</template>
