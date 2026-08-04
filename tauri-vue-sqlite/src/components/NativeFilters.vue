<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{
  busy: boolean;
  status: string;
  search: string;
}>();

const emit = defineEmits<{
  "update:status": [value: string];
  "update:search": [value: string];
  apply: [];
}>();

const selectedStatusLabel = computed(() => {
  if (props.status === "OK") return "OK";
  if (props.status === "DQ") return "DQ";
  if (props.status === "ERR") return "ERR";
  return "Все";
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
