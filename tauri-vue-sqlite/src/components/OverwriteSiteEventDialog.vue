<script setup lang="ts">
const props = defineProps<{
  open: boolean;
  busy?: boolean;
}>();

const emit = defineEmits<{
  yes: [];
  no: [];
}>();
</script>

<template>
  <div v-if="open" class="modal-backdrop" @click.self="!busy && emit('no')">
    <div
      class="modal-card"
      role="dialog"
      aria-modal="true"
      aria-label="Перезаписать результаты на сайте"
    >
      <div class="modal-header">
        <h3>Старт уже на сайте</h3>
        <button
          class="modal-close-btn"
          type="button"
          title="Нет"
          aria-label="Нет"
          :disabled="busy"
          @click="emit('no')"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path
              d="M18.3 5.7a1 1 0 0 0-1.4-1.4L12 9.17 7.1 4.3A1 1 0 0 0 5.7 5.7L10.59 10.6 5.7 15.5a1 1 0 1 0 1.4 1.4L12 12l4.9 4.9a1 1 0 0 0 1.4-1.4L13.41 10.6l4.89-4.9z"
              fill="currentColor"
            />
          </svg>
        </button>
      </div>
      <p>Результат данного старта уже есть на сайте. Перезаписать?</p>
      <div class="native-row" style="margin-top: 12px; flex-wrap: wrap">
        <button type="button" :disabled="busy" @click="emit('no')">Нет</button>
        <button type="button" :disabled="busy" @click="emit('yes')">Да</button>
      </div>
    </div>
  </div>
</template>
