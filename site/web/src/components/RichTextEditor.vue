<script setup lang="ts">
import { computed, watch } from "vue";
import { EditorContent, useEditor } from "@tiptap/vue-3";
import StarterKit from "@tiptap/starter-kit";
import Link from "@tiptap/extension-link";
import Placeholder from "@tiptap/extension-placeholder";
import { plainToEditorHtml } from "../bulletin";

const model = defineModel<string>({ required: true });

const props = defineProps<{
  disabled?: boolean;
  placeholder?: string;
  minHeight?: string;
}>();

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
    Placeholder.configure({
      placeholder: props.placeholder || "",
    }),
  ],
  onUpdate: ({ editor: ed }) => {
    const html = ed.isEmpty ? "" : ed.getHTML();
    if (html !== model.value) {
      model.value = html;
    }
  },
});

watch(
  () => props.disabled,
  (disabled) => {
    editor.value?.setEditable(!disabled);
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

function setLink() {
  const ed = editor.value;
  if (!ed) return;
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
        @click="editor.chain().focus().toggleBold().run()"
      >
        Ж
      </button>
      <button
        type="button"
        class="rte-btn"
        :class="{ active: editor.isActive('italic') }"
        :disabled="disabled"
        title="Курсив"
        @click="editor.chain().focus().toggleItalic().run()"
      >
        К
      </button>
      <button
        type="button"
        class="rte-btn"
        :class="{ active: editor.isActive('bulletList') }"
        :disabled="disabled"
        title="Маркированный список"
        @click="editor.chain().focus().toggleBulletList().run()"
      >
        • Список
      </button>
      <button
        type="button"
        class="rte-btn"
        :class="{ active: editor.isActive('orderedList') }"
        :disabled="disabled"
        title="Нумерованный список"
        @click="editor.chain().focus().toggleOrderedList().run()"
      >
        1. Список
      </button>
      <button
        type="button"
        class="rte-btn"
        :class="{ active: editor.isActive('link') }"
        :disabled="disabled"
        title="Ссылка"
        @click="setLink"
      >
        Ссылка
      </button>
      <button
        type="button"
        class="rte-btn"
        :disabled="disabled || !editor.can().chain().focus().unsetAllMarks().clearNodes().run()"
        title="Очистить оформление"
        @click="editor.chain().focus().unsetAllMarks().clearNodes().run()"
      >
        Очистить
      </button>
    </div>
    <EditorContent :editor="editor" class="rte-body" />
  </div>
</template>
