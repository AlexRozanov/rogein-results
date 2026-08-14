<script setup lang="ts">
import { ref } from "vue";

const STORAGE_KEY = "rogein.settings.panels";

const props = withDefaults(
  defineProps<{
    panelId: string;
    title: string;
    defaultOpen?: boolean;
  }>(),
  { defaultOpen: true },
);

function readStored(): boolean | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const map = JSON.parse(raw) as Record<string, boolean>;
    if (typeof map[props.panelId] === "boolean") return map[props.panelId];
  } catch {
    /* ignore broken storage */
  }
  return null;
}

function writeStored(next: boolean) {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const map = raw ? (JSON.parse(raw) as Record<string, boolean>) : {};
    map[props.panelId] = next;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(map));
  } catch {
    /* ignore quota / private mode */
  }
}

const open = ref(readStored() ?? props.defaultOpen);

function toggle() {
  open.value = !open.value;
  writeStored(open.value);
}
</script>

<template>
  <section class="native-tools nested-card settings-panel" :class="{ collapsed: !open }">
    <div class="settings-panel-header">
      <button
        type="button"
        class="settings-panel-toggle"
        :aria-expanded="open"
        :aria-controls="`settings-panel-${panelId}`"
        @click="toggle"
      >
        <span class="settings-panel-chevron" :class="{ collapsed: !open }" aria-hidden="true">
          ▾
        </span>
        <h2>{{ title }}</h2>
      </button>
      <div v-if="$slots.actions" class="settings-panel-actions">
        <slot name="actions" />
      </div>
    </div>
    <div
      v-show="open"
      :id="`settings-panel-${panelId}`"
      class="settings-panel-body"
    >
      <slot />
    </div>
    <slot name="overlay" />
  </section>
</template>
