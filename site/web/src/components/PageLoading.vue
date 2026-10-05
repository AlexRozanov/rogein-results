<script setup lang="ts">
defineProps<{ label: string }>();
</script>

<template>
  <div class="page-loading" role="status" aria-live="polite">
    <div class="page-loading-spinner" aria-hidden="true"></div>
    <p>{{ label }}</p>
  </div>
</template>
