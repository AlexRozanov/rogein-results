<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { EditorContent, useEditor } from "@tiptap/vue-3";
import StarterKit from "@tiptap/starter-kit";
import Link from "@tiptap/extension-link";
import Placeholder from "@tiptap/extension-placeholder";
import Highlight from "@tiptap/extension-highlight";
import { TextStyle } from "@tiptap/extension-text-style";
import { Color } from "@tiptap/extension-color";
import { plainToEditorHtml } from "../bulletin";

const model = defineModel<string>({ required: true });

const props = defineProps<{
  disabled?: boolean;
  placeholder?: string;
  minHeight?: string;
}>();

const HIGHLIGHT_COLORS = [
  { label: "Жёлтый", value: "#fef08a" },
  { label: "Зелёный", value: "#bbf7d0" },
  { label: "Голубой", value: "#bae6fd" },
  { label: "Розовый", value: "#fecdd3" },
  { label: "Оранжевый", value: "#fed7aa" },
] as const;

const TEXT_COLORS = [
  { label: "Красный", value: "#dc2626" },
  { label: "Оранжевый", value: "#ea580c" },
  { label: "Зелёный", value: "#16a34a" },
  { label: "Синий", value: "#2563eb" },
  { label: "Фиолетовый", value: "#7c3aed" },
] as const;

const colorMenu = ref<"highlight" | "text" | null>(null);

const editor = useEditor({
  content: plainToEditorHtml(model.value),
  editable: !props.disabled,
  extensions: [
    StarterKit.configure({
      heading: false,
      code: false,
      codeBlock: false,
      blockquote: false,
      horizontalRule: false,
    }),
    Link.configure({
      openOnClick: false,
      autolink: true,
      HTMLAttributes: {
        rel: "noreferrer",
        target: "_blank",
      },
    }),
    Highlight.configure({ multicolor: true }),
    TextStyle,
    Color,
    Placeholder.configure({
      placeholder: props.placeholder || "",
    }),
  ],
  onCreate: ({ editor: ed }) => {
    ed.commands.focus("end");
  },
  onUpdate: ({ editor: ed }) => {
    const html = ed.isEmpty ? "" : ed.getHTML();
    if (html !== model.value) {
      model.value = html;
    }
  },
  onSelectionUpdate: () => {
    colorMenu.value = null;
  },
});

watch(
  () => props.disabled,
  (disabled) => {
    editor.value?.setEditable(!disabled);
    if (disabled) colorMenu.value = null;
  },
);

watch(model, (value) => {
  const ed = editor.value;
  if (!ed) return;
  const next = plainToEditorHtml(value);
  const current = ed.isEmpty ? "" : ed.getHTML();
  if (next === current || (ed.isEmpty && !value.trim())) return;
  ed.commands.setContent(next, { emitUpdate: false });
});

const styleVars = computed(() => ({
  "--rte-min-height": props.minHeight || "6.5rem",
}));

const activeHighlight = computed(() => {
  const ed = editor.value;
  if (!ed?.isActive("highlight")) return "";
  return String(ed.getAttributes("highlight").color || "");
});

const activeTextColor = computed(() => {
  const ed = editor.value;
  if (!ed) return "";
  return String(ed.getAttributes("textStyle").color || "");
});

function setLink() {
  const ed = editor.value;
  if (!ed) return;
  colorMenu.value = null;
  const prev = ed.getAttributes("link").href as string | undefined;
  const url = window.prompt("Ссылка", prev || "https://");
  if (url === null) return;
  const trimmed = url.trim();
  if (!trimmed) {
    ed.chain().focus().extendMarkRange("link").unsetLink().run();
    return;
  }
  ed.chain().focus().extendMarkRange("link").setLink({ href: trimmed }).run();
}

function toggleMenu(kind: "highlight" | "text") {
  if (props.disabled) return;
  colorMenu.value = colorMenu.value === kind ? null : kind;
}

function applyHighlight(color: string) {
  editor.value?.chain().focus().toggleHighlight({ color }).run();
  colorMenu.value = null;
}

function clearHighlight() {
  editor.value?.chain().focus().unsetHighlight().run();
  colorMenu.value = null;
}

function applyTextColor(color: string) {
  editor.value?.chain().focus().setColor(color).run();
  colorMenu.value = null;
}

function clearTextColor() {
  editor.value?.chain().focus().unsetColor().run();
  colorMenu.value = null;
}
</script>

<template>
  <div class="rte" :class="{ 'rte-disabled': disabled }" :style="styleVars">
    <div v-if="editor" class="rte-toolbar" role="toolbar">
      <button
        type="button"
        class="rte-btn"
        :class="{ active: editor.isActive('bold') }"
        :disabled="disabled"
        title="Жирный"
        aria-label="Жирный"
        @click="editor.chain().focus().toggleBold().run()"
      >
        <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
          <path
            fill="currentColor"
            d="M6.5 4h6.2c2.6 0 4.4 1.5 4.4 3.7 0 1.5-.8 2.7-2.1 3.3 1.7.5 2.8 1.9 2.8 3.8 0 2.5-2 4.2-4.9 4.2H6.5V4Zm3.1 5.7h2.7c1.1 0 1.8-.6 1.8-1.5s-.7-1.4-1.8-1.4H9.6v2.9Zm0 6.5h3.1c1.3 0 2.1-.7 2.1-1.7s-.8-1.6-2.1-1.6H9.6v3.3Z"
          />
        </svg>
      </button>
      <button
        type="button"
        class="rte-btn"
        :class="{ active: editor.isActive('italic') }"
        :disabled="disabled"
        title="Курсив"
        aria-label="Курсив"
        @click="editor.chain().focus().toggleItalic().run()"
      >
        <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
          <path
            fill="currentColor"
            d="M10 4h9v2.4h-3.1l-3.5 11.2H16V20H7v-2.4h3.1L13.6 6.4H10V4Z"
          />
        </svg>
      </button>

      <span class="rte-sep" aria-hidden="true" />

      <button
        type="button"
        class="rte-btn"
        :class="{ active: editor.isActive('bulletList') }"
        :disabled="disabled"
        title="Маркированный список"
        aria-label="Маркированный список"
        @click="editor.chain().focus().toggleBulletList().run()"
      >
        <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
          <path
            fill="currentColor"
            d="M8 6h13v2H8V6Zm0 5h13v2H8v-2Zm0 5h13v2H8v-2ZM4.5 6.8a1.2 1.2 0 1 1 0-2.4 1.2 1.2 0 0 1 0 2.4Zm0 5a1.2 1.2 0 1 1 0-2.4 1.2 1.2 0 0 1 0 2.4Zm0 5a1.2 1.2 0 1 1 0-2.4 1.2 1.2 0 0 1 0 2.4Z"
          />
        </svg>
      </button>
      <button
        type="button"
        class="rte-btn"
        :class="{ active: editor.isActive('orderedList') }"
        :disabled="disabled"
        title="Нумерованный список"
        aria-label="Нумерованный список"
        @click="editor.chain().focus().toggleOrderedList().run()"
      >
        <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
          <path
            fill="currentColor"
            d="M8 6h13v2H8V6Zm0 5h13v2H8v-2Zm0 5h13v2H8v-2ZM3.2 4.2h2.2v5H3.8V5.8H3.2V4.2Zm.2 7.3h2.8v1.2l-1.8 2.3h1.8V16.5H3.2v-1.2l1.8-2.3H3.4v-1.5Z"
          />
        </svg>
      </button>
      <button
        type="button"
        class="rte-btn"
        :class="{ active: editor.isActive('link') }"
        :disabled="disabled"
        title="Ссылка"
        aria-label="Ссылка"
        @click="setLink"
      >
        <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
          <path
            fill="currentColor"
            d="M10.6 13.4a3.8 3.8 0 0 1 0-5.4l2.1-2.1a3.8 3.8 0 1 1 5.4 5.4l-1 1-1.4-1.4 1-1a1.9 1.9 0 1 0-2.6-2.6l-2.1 2.1a1.9 1.9 0 0 0 0 2.6l-1.4 1.4Zm2.8-2.8a3.8 3.8 0 0 1 0 5.4l-2.1 2.1a3.8 3.8 0 1 1-5.4-5.4l1-1 1.4 1.4-1 1a1.9 1.9 0 1 0 2.6 2.6l2.1-2.1a1.9 1.9 0 0 0 0-2.6l1.4-1.4Z"
          />
        </svg>
      </button>

      <span class="rte-sep" aria-hidden="true" />

      <div class="rte-color-wrap">
        <button
          type="button"
          class="rte-btn"
          :class="{ active: !!activeHighlight || colorMenu === 'highlight' }"
          :disabled="disabled"
          title="Выделение цветом"
          aria-label="Выделение цветом"
          aria-haspopup="true"
          :aria-expanded="colorMenu === 'highlight'"
          @click="toggleMenu('highlight')"
        >
          <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
            <path
              fill="currentColor"
              d="M15.2 3.3 20.7 8.8 10 19.5H4.5V14L15.2 3.3Zm1.4 1.4L5.9 15.4v2.7h2.7L19.3 7.4l-2.7-2.7ZM4.5 20.5h15v2h-15v-2Z"
            />
          </svg>
          <span
            class="rte-swatch"
            :style="{ background: activeHighlight || '#fef08a' }"
          />
        </button>
        <div v-if="colorMenu === 'highlight'" class="rte-palette" role="menu">
          <button
            v-for="swatch in HIGHLIGHT_COLORS"
            :key="swatch.value"
            type="button"
            class="rte-palette-swatch"
            :class="{ active: activeHighlight === swatch.value }"
            :style="{ background: swatch.value }"
            :title="swatch.label"
            :aria-label="swatch.label"
            @click="applyHighlight(swatch.value)"
          />
          <button
            type="button"
            class="rte-palette-clear"
            title="Убрать выделение"
            aria-label="Убрать выделение"
            @click="clearHighlight"
          >
            ×
          </button>
        </div>
      </div>

      <div class="rte-color-wrap">
        <button
          type="button"
          class="rte-btn"
          :class="{ active: !!activeTextColor || colorMenu === 'text' }"
          :disabled="disabled"
          title="Цвет текста"
          aria-label="Цвет текста"
          aria-haspopup="true"
          :aria-expanded="colorMenu === 'text'"
          @click="toggleMenu('text')"
        >
          <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
            <path
              fill="currentColor"
              d="M11 3h2l5.5 14h-2.2l-1.3-3.4H9l-1.3 3.4H5.5L11 3Zm.9 3.2L9.8 11.6h4.4L12.1 6.2h-.2Z"
            />
          </svg>
          <span
            class="rte-swatch"
            :style="{ background: activeTextColor || '#dc2626' }"
          />
        </button>
        <div v-if="colorMenu === 'text'" class="rte-palette" role="menu">
          <button
            v-for="swatch in TEXT_COLORS"
            :key="swatch.value"
            type="button"
            class="rte-palette-swatch"
            :class="{ active: activeTextColor === swatch.value }"
            :style="{ background: swatch.value }"
            :title="swatch.label"
            :aria-label="swatch.label"
            @click="applyTextColor(swatch.value)"
          />
          <button
            type="button"
            class="rte-palette-clear"
            title="Убрать цвет"
            aria-label="Убрать цвет"
            @click="clearTextColor"
          >
            ×
          </button>
        </div>
      </div>

      <span class="rte-sep" aria-hidden="true" />

      <button
        type="button"
        class="rte-btn"
        :disabled="disabled || !editor.can().chain().focus().unsetAllMarks().clearNodes().run()"
        title="Очистить оформление"
        aria-label="Очистить оформление"
        @click="editor.chain().focus().unsetAllMarks().clearNodes().run()"
      >
        <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
          <path
            fill="currentColor"
            d="M12.9 3.2 20.8 11l-1.4 1.4-1.5-1.5-7.8 7.8H4.5v-5.6l7.8-7.8-1.5-1.5 1.4-1.4 1.2 1.2Zm-1.4 3.3L6.1 12.9v2.7h2.7l6.4-6.4-3.7-3.7Zm8 12.5v2H12v-2h7.5Z"
          />
        </svg>
      </button>
    </div>
    <EditorContent :editor="editor" class="rte-body" />
  </div>
</template>
