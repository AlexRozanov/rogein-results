<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { invoke } from "@tauri-apps/api/core";

type SearchSuggestion = {
  participant_id: string;
  name: string;
};

type SuggestSource = "results" | "start_protocol";

const props = withDefaults(
  defineProps<{
    modelValue: string;
    source: SuggestSource;
    disabled?: boolean;
    placeholder?: string;
    debounceMs?: number;
    limit?: number;
  }>(),
  {
    disabled: false,
    placeholder: "ID или имя",
    debounceMs: 180,
    limit: 12,
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: string];
  apply: [value: string];
}>();

const inputEl = ref<HTMLInputElement | null>(null);
const open = ref(false);
const loading = ref(false);
const activeIndex = ref(-1);
const suggestions = ref<SearchSuggestion[]>([]);
let debounceTimer: ReturnType<typeof setTimeout> | null = null;
let requestSeq = 0;

const commandName = computed(() =>
  props.source === "start_protocol" ? "suggest_start_protocol_search" : "suggest_results_search",
);

const showList = computed(() => open.value && suggestions.value.length > 0);

function clearTimer() {
  if (debounceTimer != null) {
    clearTimeout(debounceTimer);
    debounceTimer = null;
  }
}

async function fetchSuggestions(query: string) {
  const trimmed = query.trim();
  if (!trimmed) {
    suggestions.value = [];
    activeIndex.value = -1;
    return;
  }
  const seq = ++requestSeq;
  loading.value = true;
  try {
    const rows = await invoke<SearchSuggestion[]>(commandName.value, {
      query: trimmed,
      limit: props.limit,
    });
    if (seq !== requestSeq) return;
    suggestions.value = rows;
    activeIndex.value = rows.length ? 0 : -1;
  } catch {
    if (seq !== requestSeq) return;
    suggestions.value = [];
    activeIndex.value = -1;
  } finally {
    if (seq === requestSeq) loading.value = false;
  }
}

function scheduleFetch(query: string) {
  clearTimer();
  debounceTimer = setTimeout(() => {
    void fetchSuggestions(query);
  }, props.debounceMs);
}

function onInput(event: Event) {
  const value = (event.target as HTMLInputElement).value;
  emit("update:modelValue", value);
  open.value = true;
  scheduleFetch(value);
}

function applyValue(value: string) {
  emit("update:modelValue", value);
  emit("apply", value);
  open.value = false;
  suggestions.value = [];
  activeIndex.value = -1;
}

function selectSuggestion(item: SearchSuggestion) {
  applyValue(item.participant_id);
  void nextTick(() => inputEl.value?.blur());
}

function onFocus() {
  open.value = true;
  if (props.modelValue.trim()) {
    scheduleFetch(props.modelValue);
  }
}

function onBlur() {
  // Delay so option click can fire first.
  window.setTimeout(() => {
    open.value = false;
    activeIndex.value = -1;
  }, 120);
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === "Escape") {
    open.value = false;
    activeIndex.value = -1;
    return;
  }
  if (event.key === "Enter") {
    event.preventDefault();
    if (showList.value && activeIndex.value >= 0 && suggestions.value[activeIndex.value]) {
      selectSuggestion(suggestions.value[activeIndex.value]);
    } else {
      applyValue(props.modelValue.trim());
    }
    return;
  }
  if (!showList.value) return;
  if (event.key === "ArrowDown") {
    event.preventDefault();
    activeIndex.value = Math.min(activeIndex.value + 1, suggestions.value.length - 1);
  } else if (event.key === "ArrowUp") {
    event.preventDefault();
    activeIndex.value = Math.max(activeIndex.value - 1, 0);
  }
}

watch(
  () => props.modelValue,
  (value) => {
    if (!open.value) return;
    scheduleFetch(value);
  },
);

onBeforeUnmount(() => {
  clearTimer();
});
</script>

<template>
  <div class="search-suggest">
    <input
      ref="inputEl"
      :value="modelValue"
      type="text"
      :placeholder="placeholder"
      :disabled="disabled"
      autocomplete="off"
      spellcheck="false"
      role="combobox"
      :aria-expanded="showList"
      aria-autocomplete="list"
      @input="onInput"
      @focus="onFocus"
      @blur="onBlur"
      @keydown="onKeydown"
    />
    <ul v-if="showList" class="search-suggest-list" role="listbox">
      <li
        v-for="(item, idx) in suggestions"
        :key="`${item.participant_id}:${item.name}:${idx}`"
        class="search-suggest-item"
        :class="{ active: idx === activeIndex }"
        role="option"
        :aria-selected="idx === activeIndex"
        @mousedown.prevent="selectSuggestion(item)"
      >
        <span class="search-suggest-id">{{ item.participant_id }}</span>
        <span class="search-suggest-name">{{ item.name }}</span>
      </li>
    </ul>
    <p v-else-if="open && loading" class="search-suggest-hint">Поиск…</p>
  </div>
</template>
