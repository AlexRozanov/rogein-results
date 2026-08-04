<script setup lang="ts">
import { ref } from "vue";

const emit = defineEmits<{
  fileSelected: [file: File | null];
}>();

const inputRef = ref<HTMLInputElement | null>(null);
const selectedName = ref("No file selected");

function openPicker() {
  inputRef.value?.click();
}

function onFileChange(event: Event) {
  const target = event.target as HTMLInputElement;
  const file = target.files?.[0] ?? null;
  selectedName.value = file?.name ?? "No file selected";
  emit("fileSelected", file);
}
</script>

<template>
  <div class="file-picker">
    <input
      ref="inputRef"
      class="file-picker-input"
      type="file"
      accept=".csv,text/csv"
      @change="onFileChange"
    />
    <button type="button" class="file-picker-btn" @click="openPicker">
      Choose file
    </button>
    <span class="file-picker-name">{{ selectedName }}</span>
  </div>
</template>
