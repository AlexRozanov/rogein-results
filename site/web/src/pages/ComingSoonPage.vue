<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";

const titles: Record<string, string> = {
  calendar: "Календарь",
  rogaine: "Рогейн",
  orient: "Ориентирование",
  club: "Клуб",
};

const route = useRoute();
const sectionTitle = computed(() => {
  const key = String(route.params.section ?? "");
  return titles[key] ?? "Этот раздел";
});
</script>

<template>
  <section class="coming-soon">
    <h1>{{ sectionTitle }}</h1>
    <p>Раздел находится в разработке.</p>
    <p class="back-link">
      <router-link to="/">← На главную</router-link>
      ·
      <router-link to="/results">К результатам</router-link>
    </p>
  </section>
</template>
