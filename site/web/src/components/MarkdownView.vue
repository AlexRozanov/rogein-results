<script setup lang="ts">
import { computed } from "vue";
import { marked } from "marked";

const props = defineProps<{
  source: string;
}>();

marked.setOptions({
  gfm: true,
  breaks: false,
});

const html = computed(() => {
  const raw = props.source || "";
  try {
    return marked.parse(raw, { async: false }) as string;
  } catch {
    return `<p>${escapeHtml(raw)}</p>`;
  }
});

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}
</script>

<template>
  <div class="md-body" v-html="html" />
</template>

<style scoped>
.md-body {
  line-height: 1.55;
  max-width: 42rem;
}

.md-body :deep(h1),
.md-body :deep(h2),
.md-body :deep(h3) {
  margin: 1.4em 0 0.5em;
  line-height: 1.25;
}

.md-body :deep(h1) {
  font-size: 1.45rem;
}

.md-body :deep(h2) {
  font-size: 1.2rem;
}

.md-body :deep(h3) {
  font-size: 1.05rem;
}

.md-body :deep(p),
.md-body :deep(ul),
.md-body :deep(ol) {
  margin: 0.65em 0;
}

.md-body :deep(li) {
  margin: 0.25em 0;
}

.md-body :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 1em 0;
  font-size: 0.95em;
}

.md-body :deep(th),
.md-body :deep(td) {
  border: 1px solid var(--line, #d9d3c4);
  padding: 0.45rem 0.6rem;
  text-align: left;
  vertical-align: top;
}

.md-body :deep(th) {
  background: rgba(0, 0, 0, 0.03);
}

.md-body :deep(code) {
  font-size: 0.9em;
  padding: 0.1em 0.35em;
  background: rgba(0, 0, 0, 0.05);
  border-radius: 4px;
}

.md-body :deep(pre) {
  overflow: auto;
  padding: 0.85rem 1rem;
  background: rgba(0, 0, 0, 0.05);
  border-radius: 8px;
  border: 1px solid var(--line, #d9d3c4);
}

.md-body :deep(pre code) {
  padding: 0;
  background: none;
}

.md-body :deep(a) {
  color: var(--green-dark, #178a2d);
}

.md-body :deep(hr) {
  border: none;
  border-top: 1px solid var(--line, #d9d3c4);
  margin: 1.5rem 0;
}

.md-body :deep(strong) {
  font-weight: 650;
}

.md-body :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
}
</style>
