<script setup lang="ts">
defineProps<{
  disabled?: boolean;
}>();

const emit = defineEmits<{
  confirm: [];
  cancel: [];
}>();
</script>

<template>
  <div class="bulletin-block-actions" @pointerdown.stop>
    <button
      type="button"
      class="bulletin-icon-btn bulletin-icon-confirm"
      title="Подтвердить"
      aria-label="Подтвердить"
      :disabled="disabled"
      @click.stop="emit('confirm')"
    >
      <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
        <path
          fill="currentColor"
          d="M9.2 16.6 4.8 12.2l1.4-1.4 3 3 8-8 1.4 1.4-9.4 9.4Z"
        />
      </svg>
    </button>
    <button
      type="button"
      class="bulletin-icon-btn bulletin-icon-cancel"
      title="Отменить"
      aria-label="Отменить"
      data-bulletin-cancel
      :disabled="disabled"
      @click.stop="emit('cancel')"
    >
      <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
        <path
          fill="currentColor"
          d="M6.4 17.6 5 16.2 10.2 11 5 5.8 6.4 4.4 11.6 9.6 16.8 4.4 18.2 5.8 13 11l5.2 5.2-1.4 1.4L11.6 12.4 6.4 17.6Z"
        />
      </svg>
    </button>
  </div>
</template>
