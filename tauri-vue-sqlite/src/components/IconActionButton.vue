<script setup lang="ts">
withDefaults(
  defineProps<{
    variant:
      | "delete"
      | "undo"
      | "cancel"
      | "edit"
      | "copy"
      | "generate"
      | "save"
      | "pageFirst"
      | "pagePrev"
      | "pageNext"
      | "pageLast";
    label: string;
    disabled?: boolean;
  }>(),
  { disabled: false },
);

const emit = defineEmits<{
  click: [event: MouseEvent];
}>();

function onClick(event: MouseEvent) {
  event.stopPropagation();
  emit("click", event);
}
</script>

<template>
  <button
    type="button"
    class="icon-btn"
    :class="{
      danger: variant === 'delete',
      save: variant === 'save',
      quiet: variant !== 'delete' && variant !== 'save',
    }"
    :title="label"
    :aria-label="label"
    :disabled="disabled"
    @click="onClick"
  >
    <!-- trash -->
    <svg v-if="variant === 'delete'" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path
        d="M9 3h6l1 2h4v2H4V5h4l1-2zm1 6h2v9h-2V9zm4 0h2v9h-2V9zM7 9h2v9H7V9z"
        fill="currentColor"
      />
    </svg>
    <!-- save / check -->
    <svg v-else-if="variant === 'save'" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path
        d="M9.55 18.2 3.8 12.45l1.4-1.4 4.35 4.35L18.8 6.15l1.4 1.4L9.55 18.2z"
        fill="currentColor"
      />
    </svg>
    <!-- pencil / edit -->
    <svg v-else-if="variant === 'edit'" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path
        d="M4 17.25V20h2.75L17.81 8.94l-2.75-2.75L4 17.25zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 2.75 2.75 1.83-1.83z"
        fill="currentColor"
      />
    </svg>
    <!-- sparkles / generate -->
    <svg v-else-if="variant === 'generate'" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path
        d="M11 2.5 12.4 7 17 8.5 12.4 10 11 14.5 9.6 10 5 8.5 9.6 7 11 2.5zm7.2 9.2.9 2.6 2.6.9-2.6.9-.9 2.6-.9-2.6-2.6-.9 2.6-.9.9-2.6zM6.8 14.2l.7 2 2 .7-2 .7-.7 2-.7-2-2-.7 2-.7.7-2z"
        fill="currentColor"
      />
    </svg>
    <!-- copy -->
    <svg v-else-if="variant === 'copy'" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path
        d="M16 1H6a2 2 0 0 0-2 2v12h2V3h10V1zm3 4H10a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h9a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2zm0 16H10V7h9v14z"
        fill="currentColor"
      />
    </svg>
    <!-- undo -->
    <svg v-else-if="variant === 'undo'" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path
        d="M12.5 8c-2.33 0-4.31 1.46-5.11 3.5H10l-3.5 3.5L3 11.5h2.89C6.7 8.61 9.36 6.5 12.5 6.5c3.58 0 6.5 2.92 6.5 6.5s-2.92 6.5-6.5 6.5c-1.79 0-3.4-.72-4.57-1.89l1.42-1.42A4.47 4.47 0 0 0 12.5 17.5c2.76 0 5-2.24 5-5s-2.24-5-5-5z"
        fill="currentColor"
      />
    </svg>
    <!-- first page |◀ -->
    <svg v-else-if="variant === 'pageFirst'" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d="M18.41 16.59 13.82 12l4.59-4.59L17 6l-6 6 6 6 1.41-1.41zM6 6h2v12H6V6z" fill="currentColor" />
    </svg>
    <!-- prev page ◀ -->
    <svg v-else-if="variant === 'pagePrev'" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d="M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12l4.58-4.59z" fill="currentColor" />
    </svg>
    <!-- next page ▶ -->
    <svg v-else-if="variant === 'pageNext'" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d="M8.59 16.59 13.17 12 8.59 7.41 10 6l6 6-6 6-1.41-1.41z" fill="currentColor" />
    </svg>
    <!-- last page ▶| -->
    <svg v-else-if="variant === 'pageLast'" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d="M5.59 7.41 10.18 12l-4.59 4.59L7 18l6-6-6-6-1.41 1.41zM16 6h2v12h-2V6z" fill="currentColor" />
    </svg>
    <!-- cancel / close -->
    <svg v-else viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path
        d="M18.3 5.7a1 1 0 0 0-1.4-1.4L12 9.17 7.1 4.3A1 1 0 0 0 5.7 5.7L10.59 10.6 5.7 15.5a1 1 0 1 0 1.4 1.4L12 12l4.9 4.9a1 1 0 0 0 1.4-1.4L13.41 10.6l4.89-4.9z"
        fill="currentColor"
      />
    </svg>
  </button>
</template>
