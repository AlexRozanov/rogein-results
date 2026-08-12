<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from "vue";
import NativeWorkspace from "./components/NativeWorkspace.vue";
import NativeErrorsPage from "./components/NativeErrorsPage.vue";
import NativeCourseMapPage from "./components/NativeCourseMapPage.vue";
import NativeParticipantPathPage from "./components/NativeParticipantPathPage.vue";
import NativeCpRemapPage from "./components/NativeCpRemapPage.vue";
import ParticipantCardPage from "./components/ParticipantCardPage.vue";

const hash = ref(window.location.hash || "");
const isParticipantPage = computed(() => hash.value.startsWith("#result/"));
const isErrorsPage = computed(() => hash.value === "#errors" || hash.value.startsWith("#errors?"));
const isCourseMapPage = computed(
  () => hash.value === "#course-map" || hash.value.startsWith("#course-map?"),
);
const isCoursePathPage = computed(() => hash.value.startsWith("#course-path/"));
const isCpRemapPage = computed(
  () => hash.value === "#cp-remap" || hash.value.startsWith("#cp-remap?"),
);
const resultIdFromHash = computed(() => {
  if (!hash.value.startsWith("#result/")) return null;
  const raw = hash.value.replace("#result/", "").trim();
  const id = Number(raw);
  return Number.isFinite(id) && id > 0 ? id : null;
});
const pathResultIdFromHash = computed(() => {
  if (!hash.value.startsWith("#course-path/")) return null;
  const raw = hash.value.replace("#course-path/", "").trim();
  const id = Number(raw);
  return Number.isFinite(id) && id > 0 ? id : null;
});

function onHashChange() {
  hash.value = window.location.hash || "";
}

onMounted(() => {
  window.addEventListener("hashchange", onHashChange);
});

onBeforeUnmount(() => {
  window.removeEventListener("hashchange", onHashChange);
});
</script>

<template>
  <ParticipantCardPage v-if="isParticipantPage" :result-id="resultIdFromHash" />
  <NativeErrorsPage v-else-if="isErrorsPage" />
  <NativeCourseMapPage v-else-if="isCourseMapPage" />
  <NativeParticipantPathPage v-else-if="isCoursePathPage" :result-id="pathResultIdFromHash" />
  <NativeCpRemapPage v-else-if="isCpRemapPage" />
  <main v-else class="container">
    <NativeWorkspace />
  </main>
</template>
