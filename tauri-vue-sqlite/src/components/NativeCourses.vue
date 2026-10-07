<script setup lang="ts">
import { nextTick, onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import FilePickerButton from "./FilePickerButton.vue";
import IconActionButton from "./IconActionButton.vue";
import ImportExistingDataDialog from "./ImportExistingDataDialog.vue";

type CourseRow = {
  id: number;
  name: string;
  controls: number[];
};

type CourseDraft = {
  key: string;
  id: number | null;
  name: string;
  controls: number[];
  addText: string;
  editingIndex: number | null;
  editText: string;
};

type CoursesImportSummary = {
  imported_rows: number;
  total_rows: number;
};

const props = defineProps<{
  busy: boolean;
}>();

const emit = defineEmits<{
  status: [message: string];
  saved: [];
}>();

const drafts = ref<CourseDraft[]>([]);
const removedCps = ref<Set<number>>(new Set());
const localBusy = ref(false);
const coursesFile = ref<File | null>(null);
const importDialogOpen = ref(false);
const importExistingCount = ref(0);
const dragFrom = ref<{ key: string; index: number } | null>(null);
const dropAt = ref<{ key: string; index: number } | null>(null);
const skipClick = ref(false);
const saveTimers = new Map<string, ReturnType<typeof setTimeout>>();
let newDraftSeq = 0;

const isBusy = () => props.busy || localBusy.value;

onMounted(() => {
  void refresh();
});

function toDraft(row: CourseRow): CourseDraft {
  return {
    key: `id-${row.id}`,
    id: row.id,
    name: row.name,
    controls: [...row.controls],
    addText: "",
    editingIndex: null,
    editText: "",
  };
}

async function refresh() {
  try {
    const [rows, corrections] = await Promise.all([
      invoke<CourseRow[]>("get_courses"),
      invoke<{ correction_type: string; payload: Record<string, unknown> }[]>(
        "get_manual_corrections",
        { participantId: null },
      ),
    ]);
    drafts.value = rows.map(toDraft);
    removedCps.value = new Set(
      corrections
        .filter((row) => row.correction_type === "remove_cp")
        .map((row) => Number(row.payload?.cp_number))
        .filter((cp) => Number.isFinite(cp)),
    );
  } catch (error) {
    emit("status", `Ошибка загрузки дистанций: ${String(error)}`);
  }
}

function isRemovedCp(cp: number) {
  return removedCps.value.has(cp);
}

function parseCpList(raw: string): number[] {
  return raw
    .split(/[\s,;→\-–]+/u)
    .map((part) => part.trim())
    .filter(Boolean)
    .map((part) => Number(part))
    .filter((n) => Number.isInteger(n) && n > 0);
}

function addCourse() {
  newDraftSeq += 1;
  drafts.value.push({
    key: `new-${newDraftSeq}`,
    id: null,
    name: "",
    controls: [],
    addText: "",
    editingIndex: null,
    editText: "",
  });
}

function scheduleSave(draft: CourseDraft) {
  const prev = saveTimers.get(draft.key);
  if (prev) clearTimeout(prev);
  saveTimers.set(
    draft.key,
    setTimeout(() => {
      saveTimers.delete(draft.key);
      void persist(draft);
    }, 280),
  );
}

async function persist(draft: CourseDraft) {
  const name = draft.name.trim();
  if (!name || draft.controls.length === 0) return;
  if (localBusy.value) {
    scheduleSave(draft);
    return;
  }
  localBusy.value = true;
  try {
    const saved = await invoke<CourseRow>("save_course", {
      courseId: draft.id,
      name,
      controls: draft.controls,
    });
    draft.id = saved.id;
    draft.name = saved.name;
    draft.controls = [...saved.controls];
    draft.key = `id-${saved.id}`;
    emit("status", `Дистанция «${saved.name}» сохранена, результаты пересчитаны.`);
    emit("saved");
  } catch (error) {
    emit("status", `Ошибка сохранения дистанции: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function removeCourse(draft: CourseDraft) {
  if (isBusy()) return;
  const label = draft.name.trim() || "без названия";
  if (!window.confirm(`Удалить дистанцию «${label}»? Результаты будут пересчитаны.`)) return;
  if (draft.id == null) {
    drafts.value = drafts.value.filter((d) => d.key !== draft.key);
    return;
  }
  localBusy.value = true;
  try {
    await invoke("delete_course", { courseId: draft.id });
    drafts.value = drafts.value.filter((d) => d.key !== draft.key);
    emit("status", `Дистанция «${label}» удалена, результаты пересчитаны.`);
    emit("saved");
  } catch (error) {
    emit("status", `Ошибка удаления дистанции: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

function addFromInput(draft: CourseDraft) {
  const nums = parseCpList(draft.addText);
  if (!nums.length) return;
  draft.controls.push(...nums);
  draft.addText = "";
  scheduleSave(draft);
}

function onAddKeydown(draft: CourseDraft, event: KeyboardEvent) {
  if (event.key === "Enter" || event.key === " " || event.key === ",") {
    event.preventDefault();
    addFromInput(draft);
    return;
  }
  if (event.key === "Backspace" && !draft.addText && draft.controls.length) {
    event.preventDefault();
    draft.controls.pop();
    scheduleSave(draft);
  }
}

function onAddPaste(draft: CourseDraft, event: ClipboardEvent) {
  const text = event.clipboardData?.getData("text") ?? "";
  const nums = parseCpList(text);
  if (nums.length <= 1) return;
  event.preventDefault();
  draft.controls.push(...nums);
  draft.addText = "";
  scheduleSave(draft);
}

function removeCp(draft: CourseDraft, index: number) {
  draft.controls.splice(index, 1);
  if (draft.editingIndex === index) {
    draft.editingIndex = null;
    draft.editText = "";
  }
  scheduleSave(draft);
}

function moveCpToSlot(draft: CourseDraft, from: number, slot: number) {
  let to = slot;
  if (from < to) to -= 1;
  if (from === to || to < 0 || to > draft.controls.length - 1) return;
  const [cp] = draft.controls.splice(from, 1);
  draft.controls.splice(to, 0, cp);
  scheduleSave(draft);
}

function isDropCaret(draft: CourseDraft, slot: number) {
  if (!dragFrom.value || !dropAt.value) return false;
  if (dragFrom.value.key !== draft.key || dropAt.value.key !== draft.key) return false;
  if (dropAt.value.index !== slot) return false;
  return slot !== dragFrom.value.index && slot !== dragFrom.value.index + 1;
}

function setDropSlot(draft: CourseDraft, slot: number, event: DragEvent) {
  event.preventDefault();
  if (!dragFrom.value || dragFrom.value.key !== draft.key) return;
  if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
  if (dropAt.value?.key !== draft.key || dropAt.value.index !== slot) {
    dropAt.value = { key: draft.key, index: slot };
  }
}

function startEditChip(draft: CourseDraft, index: number) {
  if (skipClick.value) {
    skipClick.value = false;
    return;
  }
  draft.editingIndex = index;
  draft.editText = String(draft.controls[index] ?? "");
  void nextTick(() => {
    const el = document.querySelector<HTMLInputElement>(
      `input[data-course-edit="${draft.key}-${index}"]`,
    );
    el?.focus();
    el?.select();
  });
}

function commitEditChip(draft: CourseDraft) {
  if (draft.editingIndex == null) return;
  const nums = parseCpList(draft.editText);
  const index = draft.editingIndex;
  draft.editingIndex = null;
  draft.editText = "";
  if (!nums.length) {
    removeCp(draft, index);
    return;
  }
  draft.controls.splice(index, 1, ...nums);
  scheduleSave(draft);
}

function onDragStart(draft: CourseDraft, index: number, event: DragEvent) {
  dragFrom.value = { key: draft.key, index };
  dropAt.value = null;
  skipClick.value = true;
  event.dataTransfer?.setData("text/plain", String(index));
  if (event.dataTransfer) event.dataTransfer.effectAllowed = "move";
}

function onChipDragOver(draft: CourseDraft, index: number, event: DragEvent) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
  const slot = event.clientX < rect.left + rect.width / 2 ? index : index + 1;
  setDropSlot(draft, slot, event);
}

function onChipDrop(draft: CourseDraft, index: number, event: DragEvent) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
  const fallback = event.clientX < rect.left + rect.width / 2 ? index : index + 1;
  const slot = dropAt.value?.key === draft.key ? dropAt.value.index : fallback;
  onDropAt(draft, slot);
}

function onDropAt(draft: CourseDraft, slot: number) {
  const from = dragFrom.value;
  dropAt.value = null;
  dragFrom.value = null;
  if (!from || from.key !== draft.key) return;
  moveCpToSlot(draft, from.index, slot);
}

function onRowDragLeave(draft: CourseDraft, event: DragEvent) {
  const next = event.relatedTarget as Node | null;
  if (next && (event.currentTarget as HTMLElement).contains(next)) return;
  if (dropAt.value?.key === draft.key) dropAt.value = null;
}

function onDragEnd() {
  dragFrom.value = null;
  dropAt.value = null;
  setTimeout(() => {
    skipClick.value = false;
  }, 0);
}

async function onFileSelected(file: File | null) {
  coursesFile.value = file;
  if (!coursesFile.value) return;
  await beginImportFlow();
}

async function beginImportFlow() {
  try {
    const counts = await invoke<{ courses: number }>("get_data_presence_counts");
    if (counts.courses > 0) {
      importExistingCount.value = counts.courses;
      importDialogOpen.value = true;
      return;
    }
    await runImport(true);
  } catch (error) {
    emit("status", `Ошибка проверки дистанций: ${String(error)}`);
  }
}

function onImportDialogCancel() {
  importDialogOpen.value = false;
}

async function onImportDialogMerge() {
  importDialogOpen.value = false;
  await runImport(false);
}

async function onImportDialogReplace() {
  importDialogOpen.value = false;
  await runImport(true);
}

async function runImport(reset: boolean) {
  if (!coursesFile.value) {
    emit("status", "Выберите CSV с дистанциями.");
    return;
  }
  localBusy.value = true;
  try {
    const csvContent = await coursesFile.value.text();
    const summary = await invoke<CoursesImportSummary>("import_courses_content", {
      csvContent,
      reset,
    });
    await invoke("recalculate_results");
    emit(
      "status",
      reset
        ? `Дистанции загружены заново: ${summary.imported_rows}, всего ${summary.total_rows}. Результаты пересчитаны.`
        : `Дистанции обновлены: ${summary.imported_rows}, всего ${summary.total_rows}. Результаты пересчитаны.`,
    );
    await refresh();
    emit("saved");
  } catch (error) {
    emit("status", `Ошибка импорта дистанций: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

defineExpose({ refresh });
</script>

<template>
  <section class="native-tools nested-card">
    <h2>Дистанции</h2>
    <p class="subtitle">
      Порядок КП: <code>1 → 2 → 3</code>. Клик по номеру — изменить, × — удалить из этой дистанции,
      перетаскивание — порядок (полоска показывает, куда встанет КП).
      В поле справа введите номер и Enter / пробел; можно вставить список
      <code>32 51 34</code>. Старт и финиш задаются в настройках.
      CSV: строка на дистанцию, <code>D1;31;32;33</code> или <code>D1;31 32 33</code>.
    </p>
    <p v-if="removedCps.size" class="subtitle">
      Зачёркнутый КП снят в настройках для всех участников — как будто его не было на дистанции.
    </p>
    <div class="native-row">
      <FilePickerButton
        button-label="Выбрать CSV дистанций"
        empty-label="Файл не выбран"
        :disabled="isBusy()"
        @file-selected="onFileSelected"
      />
      <button type="button" :disabled="isBusy()" @click="addCourse">Добавить дистанцию</button>
      <button type="button" :disabled="isBusy()" @click="refresh">Обновить</button>
    </div>
    <div class="native-results-wrap">
      <table class="native-results course-table">
        <thead>
          <tr>
            <th>Дистанция</th>
            <th>КП</th>
            <th>Порядок</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!drafts.length">
            <td colspan="4">Дистанции ещё не загружены</td>
          </tr>
          <tr v-for="draft in drafts" :key="draft.key">
          <td>
            <input
              v-model="draft.name"
              class="course-name-input"
              placeholder="D1"
              :disabled="isBusy()"
              @change="scheduleSave(draft)"
              @keydown.enter.prevent="persist(draft)"
            />
          </td>
          <td>{{ draft.controls.length }}</td>
          <td class="course-seq-cell">
            <div
              class="course-chip-row"
              @dragleave="onRowDragLeave(draft, $event)"
            >
              <template v-for="(cp, idx) in draft.controls" :key="`${draft.key}-${idx}-${cp}`">
                <span
                  class="course-drop-caret"
                  :class="{ active: isDropCaret(draft, idx) }"
                  aria-hidden="true"
                />
                <span
                  class="course-chip"
                  :class="{
                    editing: draft.editingIndex === idx,
                    dragging: dragFrom?.key === draft.key && dragFrom.index === idx,
                    voided: isRemovedCp(cp),
                  }"
                  :title="isRemovedCp(cp) ? 'Снят в настройках — как будто не было на дистанции' : undefined"
                  draggable="true"
                  @dragstart="onDragStart(draft, idx, $event)"
                  @dragover="onChipDragOver(draft, idx, $event)"
                  @drop.prevent="onChipDrop(draft, idx, $event)"
                  @dragend="onDragEnd"
                >
                  <input
                    v-if="draft.editingIndex === idx"
                    :data-course-edit="`${draft.key}-${idx}`"
                    v-model="draft.editText"
                    class="course-chip-edit"
                    :disabled="isBusy()"
                    @keydown.enter.prevent="commitEditChip(draft)"
                    @keydown.escape="draft.editingIndex = null"
                    @blur="commitEditChip(draft)"
                  />
                  <template v-else>
                    <button
                      type="button"
                      class="course-chip-num"
                      :disabled="isBusy()"
                      @click="startEditChip(draft, idx)"
                    >
                      {{ cp }}
                    </button>
                    <button
                      type="button"
                      class="course-chip-x"
                      title="Удалить КП"
                      :disabled="isBusy()"
                      @click.stop="removeCp(draft, idx)"
                    >
                      ×
                    </button>
                  </template>
                </span>
                <span
                  v-if="idx < draft.controls.length - 1"
                  class="course-seq-arrow"
                  :class="{ active: isDropCaret(draft, idx + 1) }"
                  @dragover="setDropSlot(draft, idx + 1, $event)"
                  @drop.prevent="onDropAt(draft, idx + 1)"
                >
                  →
                </span>
              </template>
              <span
                class="course-drop-caret"
                :class="{ active: isDropCaret(draft, draft.controls.length) }"
                aria-hidden="true"
              />
              <input
                v-model="draft.addText"
                class="course-add-input"
                placeholder="+ КП"
                :disabled="isBusy()"
                @dragover="setDropSlot(draft, draft.controls.length, $event)"
                @drop.prevent="onDropAt(draft, draft.controls.length)"
                @keydown="onAddKeydown(draft, $event)"
                @paste="onAddPaste(draft, $event)"
                @blur="addFromInput(draft)"
              />
            </div>
          </td>
          <td>
            <IconActionButton
              variant="delete"
              label="Удалить дистанцию"
              :disabled="isBusy()"
              @click="removeCourse(draft)"
            />
          </td>
        </tr>
        </tbody>
      </table>
    </div>
  </section>

  <ImportExistingDataDialog
    :open="importDialogOpen"
    kind="courses"
    :existing-count="importExistingCount"
    :file-name="coursesFile?.name"
    :busy="isBusy()"
    @cancel="onImportDialogCancel"
    @merge="onImportDialogMerge"
    @replace="onImportDialogReplace"
  />
</template>
