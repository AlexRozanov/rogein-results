<script setup lang="ts">
import { nextTick, ref, watch } from "vue";
import { richTextHtml } from "../bulletin";
import RichTextEditor from "./RichTextEditor.vue";

const model = defineModel<string>({ required: true });

const props = withDefaults(
  defineProps<{
    field: string;
    active: boolean;
    canEdit: boolean;
    kind?: "rich" | "text";
    placeholder: string;
    label?: string;
    minHeight?: string;
    displayClass?: string;
  }>(),
  { kind: "rich" },
);

const emit = defineEmits<{
  edit: [field: string];
  done: [];
}>();

const inputEl = ref<HTMLInputElement | null>(null);

function hasText(value: string | undefined): boolean {
  return Boolean(value?.replace(/<[^>]+>/g, "").trim());
}

function startEdit() {
  if (!props.canEdit || props.active) return;
  emit("edit", props.field);
}

function finish() {
  emit("done");
}

watch(
  () => props.active,
  async (active) => {
    if (!active || props.kind !== "text") return;
    await nextTick();
    inputEl.value?.focus();
    inputEl.value?.select();
  },
);
</script>

<template>
  <div
    class="bulletin-field"
    :class="{
      'bulletin-field-active': active,
      'bulletin-field-editable': canEdit && !active,
      'bulletin-field-empty': canEdit && !hasText(model),
    }"
  >
    <p v-if="label && (canEdit || hasText(model))" class="bulletin-field-label">{{ label }}</p>

    <template v-if="active && canEdit">
      <RichTextEditor
        v-if="kind === 'rich'"
        v-model="model"
        :placeholder="placeholder"
        :min-height="minHeight"
      />
      <input
        v-else
        ref="inputEl"
        v-model="model"
        class="bulletin-inline-input"
        :placeholder="placeholder"
        @keydown.enter.prevent="finish"
        @keydown.escape.prevent="finish"
      />
      <div class="bulletin-field-actions">
        <button class="btn btn-primary" type="button" @click="finish">Готово</button>
      </div>
    </template>

    <button
      v-else-if="canEdit && !hasText(model)"
      type="button"
      class="bulletin-placeholder"
      @click="startEdit"
    >
      {{ placeholder }}
    </button>

    <div
      v-else-if="canEdit && kind === 'rich'"
      class="bulletin-field-view"
      role="button"
      tabindex="0"
      @click="startEdit"
      @keydown.enter.prevent="startEdit"
    >
      <div class="bulletin-text" v-html="richTextHtml(model)" />
    </div>

    <div
      v-else-if="canEdit"
      class="bulletin-field-view"
      :class="displayClass"
      role="button"
      tabindex="0"
      @click="startEdit"
      @keydown.enter.prevent="startEdit"
    >
      {{ model }}
    </div>

    <div
      v-else-if="hasText(model) && kind === 'rich'"
      class="bulletin-text"
      v-html="richTextHtml(model)"
    />

    <div v-else-if="hasText(model)" :class="displayClass || 'bulletin-note'">
      <template v-if="label === 'Тип заявки'">
        <strong>Тип заявки:</strong> {{ model }}
      </template>
      <template v-else>{{ model }}</template>
    </div>
  </div>
</template>
