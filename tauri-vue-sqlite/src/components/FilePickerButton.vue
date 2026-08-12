<script setup lang="ts">
import { ref } from "vue";

const props = withDefaults(
  defineProps<{
    accept?: string;
    buttonLabel?: string;
    emptyLabel?: string;
    disabled?: boolean;
  }>(),
  {
    accept: ".csv,text/csv",
    buttonLabel: "Выбрать файл",
    emptyLabel: "Файл не выбран",
    disabled: false,
  },
);

const emit = defineEmits<{
  fileSelected: [file: File | null];
}>();

const inputRef = ref<HTMLInputElement | null>(null);
const selectedName = ref(props.emptyLabel);

function openPicker() {
  if (props.disabled) return;
  inputRef.value?.click();
}

function onFileChange(event: Event) {
  const target = event.target as HTMLInputElement;
  const file = target.files?.[0] ?? null;
  selectedName.value = file?.name ?? props.emptyLabel;
  emit("fileSelected", file);
  // allow re-selecting the same file
  target.value = "";
}
</script>

<template>
  <div class="file-picker">
    <input
      ref="inputRef"
      class="file-picker-input"
      type="file"
      :accept="props.accept"
      :disabled="props.disabled"
      @change="onFileChange"
    />
    <button
      type="button"
      class="file-picker-btn"
      :disabled="props.disabled"
      @click="openPicker"
    >
      {{ props.buttonLabel }}
    </button>
    <span class="file-picker-name">{{ selectedName }}</span>
  </div>
</template>
