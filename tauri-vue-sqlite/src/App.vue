<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import NativeWorkspace from "./components/NativeWorkspace.vue";
import ParticipantCardPage from "./components/ParticipantCardPage.vue";

const hash = ref(window.location.hash || "");
const isParticipantPage = computed(() => hash.value.startsWith("#participant/"));
const participantIdFromHash = computed(() =>
  hash.value.startsWith("#participant/")
    ? hash.value.replace("#participant/", "")
    : "",
);

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
  <ParticipantCardPage v-if="isParticipantPage" :participant-id="participantIdFromHash" />
  <main v-else class="container">
    <NativeWorkspace />
  </main>
</template>
