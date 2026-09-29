<script setup lang="ts">
import { computed } from "vue";
import { privacyPolicy, userAgreement, type LegalDoc } from "../content/legal";

const props = defineProps<{ doc: "privacy" | "terms" }>();

const document = computed<LegalDoc>(() =>
  props.doc === "privacy" ? privacyPolicy : userAgreement,
);
</script>

<template>
  <article class="legal">
    <p class="back-link">
      <router-link to="/">← На главную</router-link>
    </p>
    <h1>{{ document.title }}</h1>
    <p class="muted legal-meta">Редакция от {{ document.updated }}</p>
    <p>{{ document.intro }}</p>
    <section v-for="article in document.articles" :key="article.title" class="legal-article">
      <h2>{{ article.title }}</h2>
      <p v-for="(paragraph, index) in article.paragraphs" :key="index">{{ paragraph }}</p>
    </section>
  </article>
</template>
