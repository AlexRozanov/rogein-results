<script setup lang="ts">
</script>

<template>
  <div class="shell">
    <header class="top">
      <router-link to="/" class="brand">Rogein</router-link>
      <span class="muted top-tagline">Результаты прошедших стартов</span>
    </header>
    <main class="page">
      <router-view />
    </main>
  </div>
</template>
