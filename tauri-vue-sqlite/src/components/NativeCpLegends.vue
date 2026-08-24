<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import FilePickerButton from "./FilePickerButton.vue";
import IconActionButton from "./IconActionButton.vue";
import ImportExistingDataDialog from "./ImportExistingDataDialog.vue";
import { openAuxWebviewWindow } from "../workspaceUiState";
import { cpTypeColor } from "../cpTypeColor";

type CpLegendRow = {
  id: number;
  cp_number: number;
  name: string;
  cp_type_id: number | null;
  cp_type_name: string;
  map_x: number | null;
  map_y: number | null;
};

type CpLegendTypeRow = {
  id: number;
  type_name: string;
  usage_count: number;
};

type CpLegendImportSummary = {
  imported_rows: number;
  total_rows: number;
};

type CourseMapInfo = {
  has_map: boolean;
  file_name: string | null;
  mime_type: string | null;
};

const props = defineProps<{
  busy: boolean;
}>();

const emit = defineEmits<{
  status: [message: string];
}>();

const rows = ref<CpLegendRow[]>([]);
const typeRows = ref<CpLegendTypeRow[]>([]);
const localBusy = ref(false);
const legendsFile = ref<File | null>(null);
const importDialogOpen = ref(false);
const importExistingCount = ref(0);
const search = ref("");
const mapInfo = ref<CourseMapInfo>({
  has_map: false,
  file_name: null,
  mime_type: null,
});

const addModalOpen = ref(false);
const addNumber = ref<number | null>(null);
const addName = ref("");
const addTypeId = ref<string>("");

const editId = ref<number | null>(null);
const editNumber = ref<number | null>(null);
const editName = ref("");
const editTypeId = ref<string>("");

const typesModalOpen = ref(false);
const typesBusy = ref(false);
const typesError = ref("");
const newTypeName = ref("");
const renameDrafts = ref<Record<number, string>>({});

const clearMapModalOpen = ref(false);
const clearMapPlacedLegends = ref(0);
const clearMapPlacedSpecial = ref(0);
const scaleDenominator = ref<number | null>(null);
const georefStatus = ref("Загрузка привязки…");

const clearMapPlacedTotal = computed(
  () => clearMapPlacedLegends.value + clearMapPlacedSpecial.value,
);

const filteredRows = computed(() => {
  const q = search.value.trim().toLowerCase();
  if (!q) return rows.value;
  return rows.value.filter((r) => {
    const type = (r.cp_type_name || "").toLowerCase();
    return (
      String(r.cp_number).includes(q) ||
      r.name.toLowerCase().includes(q) ||
      type.includes(q)
    );
  });
});

onMounted(() => {
  void loadTypes();
  void refresh();
  void refreshMapInfo();
  void refreshGeoref();
});

type MapGeorefInfo = {
  scale_denominator: number | null;
  ready: boolean;
  status: string;
};

async function refreshGeoref() {
  try {
    const info = await invoke<MapGeorefInfo>("get_map_georef_info");
    scaleDenominator.value = info.scale_denominator;
    georefStatus.value = info.status;
  } catch (error) {
    georefStatus.value = `Ошибка привязки: ${String(error)}`;
  }
}

async function saveMapScale() {
  const raw = scaleDenominator.value;
  const denom =
    raw == null || String(raw).trim() === "" ? null : Number(raw);
  if (denom != null && (!Number.isFinite(denom) || denom <= 0 || !Number.isInteger(denom))) {
    emit("status", "Масштаб должен быть целым числом > 0 (например 15000).");
    return;
  }
  localBusy.value = true;
  try {
    const info = await invoke<MapGeorefInfo>("set_map_scale_denominator", {
      denominator: denom,
    });
    scaleDenominator.value = info.scale_denominator;
    georefStatus.value = info.status;
    emit(
      "status",
      denom == null
        ? "Масштаб карты сброшен."
        : `Масштаб карты сохранён: 1:${denom}.`,
    );
  } catch (error) {
    emit("status", `Ошибка сохранения масштаба: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function clearMapScale() {
  scaleDenominator.value = null;
  await saveMapScale();
}

function applyTypeRows(rowsData: CpLegendTypeRow[]) {
  typeRows.value = rowsData;
  const drafts: Record<number, string> = {};
  for (const item of rowsData) {
    drafts[item.id] = item.type_name;
  }
  renameDrafts.value = drafts;
}

async function loadTypes() {
  try {
    const rowsData = await invoke<CpLegendTypeRow[]>("get_cp_legend_type_rows");
    applyTypeRows(rowsData);
  } catch (error) {
    emit("status", `Ошибка загрузки справочника типов КП: ${String(error)}`);
  }
}

async function refresh() {
  try {
    rows.value = await invoke<CpLegendRow[]>("get_cp_legends");
  } catch (error) {
    emit("status", `Ошибка загрузки легенд КП: ${String(error)}`);
  }
}

async function refreshMapInfo() {
  try {
    mapInfo.value = await invoke<CourseMapInfo>("get_course_map_info");
  } catch (error) {
    emit("status", `Ошибка проверки карты: ${String(error)}`);
  }
}

async function onFileSelected(file: File | null) {
  legendsFile.value = file;
  if (!legendsFile.value) return;
  await beginImportFlow();
}

async function importLegends() {
  if (!legendsFile.value) {
    emit("status", "Выберите CSV с легендами КП.");
    return;
  }
  await beginImportFlow();
}

async function beginImportFlow() {
  try {
    const counts = await invoke<{ cp_legends: number }>("get_data_presence_counts");
    if (counts.cp_legends > 0) {
      importExistingCount.value = counts.cp_legends;
      importDialogOpen.value = true;
      return;
    }
    await runImport(true);
  } catch (error) {
    emit("status", `Ошибка проверки данных: ${String(error)}`);
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
  if (!legendsFile.value) {
    emit("status", "Выберите CSV с легендами КП.");
    return;
  }
  localBusy.value = true;
  try {
    const content = await legendsFile.value.text();
    const summary = await invoke<CpLegendImportSummary>("import_cp_legends_content", {
      csvContent: content,
      reset,
    });
    emit(
      "status",
      reset
        ? `Легенды КП загружены заново: импортировано ${summary.imported_rows}, всего в БД ${summary.total_rows}.`
        : `Легенды КП обновлены: обработано ${summary.imported_rows}, всего в БД ${summary.total_rows}.`,
    );
    cancelEdit();
    await loadTypes();
    await refresh();
  } catch (error) {
    emit("status", `Ошибка импорта легенд КП: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = String(reader.result || "");
      const comma = result.indexOf(",");
      resolve(comma >= 0 ? result.slice(comma + 1) : result);
    };
    reader.onerror = () => reject(reader.error || new Error("Не удалось прочитать файл"));
    reader.readAsDataURL(file);
  });
}

async function onMapFileSelected(file: File | null) {
  if (!file) return;
  if (!file.type.startsWith("image/") && !/\.(png|jpe?g|gif|webp|bmp|svg|tiff?|ico|avif|heic)$/i.test(file.name)) {
    emit("status", "Выберите файл изображения карты.");
    return;
  }
  localBusy.value = true;
  try {
    const contentBase64 = await fileToBase64(file);
    mapInfo.value = await invoke<CourseMapInfo>("save_course_map", {
      fileName: file.name,
      contentBase64,
      mimeType: file.type || null,
    });
    emit("status", `Карта «${file.name}» загружена.`);
  } catch (error) {
    emit("status", `Ошибка загрузки карты: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function openMapWindow() {
  if (!mapInfo.value.has_map) {
    emit("status", "Сначала загрузите карту.");
    return;
  }
  const targetHash = "#course-map";
  const opened = await openAuxWebviewWindow({
    label: "course-map",
    title: mapInfo.value.file_name ? `Карта — ${mapInfo.value.file_name}` : "Карта",
    width: 1280,
    height: 900,
    url: targetHash,
  });
  if (opened.ok) {
    emit("status", "Карта открыта в отдельном окне.");
  } else {
    emit("status", "Не удалось открыть отдельное окно карты, открыл в текущем окне.");
    window.location.hash = targetHash;
    console.error(opened.error);
  }
}

async function clearMap() {
  if (!mapInfo.value.has_map) return;

  const placedLegends = rows.value.filter(
    (r) => r.map_x != null && r.map_y != null,
  ).length;
  let placedSpecial = 0;
  try {
    const special = await invoke<{
      start_map_x: number | null;
      start_map_y: number | null;
      finish_map_x: number | null;
      finish_map_y: number | null;
    }>("get_course_map_special_points");
    if (special.start_map_x != null && special.start_map_y != null) placedSpecial += 1;
    if (special.finish_map_x != null && special.finish_map_y != null) placedSpecial += 1;
  } catch {
    // ignore — fall back to known legend placements
  }

  if (placedLegends + placedSpecial === 0) {
    if (!window.confirm("Удалить загруженную карту?")) return;
    await performClearMap(false);
    return;
  }

  clearMapPlacedLegends.value = placedLegends;
  clearMapPlacedSpecial.value = placedSpecial;
  clearMapModalOpen.value = true;
}

function closeClearMapModal() {
  clearMapModalOpen.value = false;
}

async function performClearMap(clearPositions: boolean) {
  clearMapModalOpen.value = false;
  localBusy.value = true;
  try {
    mapInfo.value = await invoke<CourseMapInfo>("clear_course_map", {
      clearPositions,
    });
    if (clearPositions) {
      await refresh();
      emit("status", "Карта и размещённые точки удалены.");
    } else {
      emit("status", "Карта удалена.");
    }
  } catch (error) {
    emit("status", `Ошибка удаления карты: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

function isEditingRow(rowId: number) {
  return editId.value === rowId;
}

function beginEdit(row: CpLegendRow) {
  if (editId.value === row.id) return;
  editId.value = row.id;
  editNumber.value = row.cp_number;
  editName.value = row.name;
  editTypeId.value = row.cp_type_id == null ? "" : String(row.cp_type_id);
}

function cancelEdit() {
  editId.value = null;
  editNumber.value = null;
  editName.value = "";
  editTypeId.value = "";
}

async function saveEdit() {
  if (editId.value == null) return;
  const number = Number(editNumber.value);
  if (!Number.isInteger(number)) {
    emit("status", "Номер КП должен быть целым числом.");
    return;
  }
  const name = editName.value.trim();
  if (!name) {
    emit("status", "Укажите название КП.");
    return;
  }

  localBusy.value = true;
  try {
    await invoke("upsert_cp_legend", {
      legendId: editId.value,
      cpNumber: number,
      name,
      cpTypeId: editTypeId.value ? Number(editTypeId.value) : null,
    });
    emit("status", `Легенда КП ${number} обновлена.`);
    cancelEdit();
    await loadTypes();
    await refresh();
  } catch (error) {
    emit("status", `Ошибка сохранения легенды КП: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

function openAddModal() {
  cancelEdit();
  addNumber.value = null;
  addName.value = "";
  addTypeId.value = "";
  addModalOpen.value = true;
}

function closeAddModal() {
  addModalOpen.value = false;
  addNumber.value = null;
  addName.value = "";
  addTypeId.value = "";
}

async function saveAdd() {
  const number = Number(addNumber.value);
  if (!Number.isInteger(number)) {
    emit("status", "Номер КП должен быть целым числом.");
    return;
  }
  const name = addName.value.trim();
  if (!name) {
    emit("status", "Укажите название КП.");
    return;
  }

  localBusy.value = true;
  try {
    await invoke("upsert_cp_legend", {
      legendId: null,
      cpNumber: number,
      name,
      cpTypeId: addTypeId.value ? Number(addTypeId.value) : null,
    });
    emit("status", `Легенда КП ${number} добавлена.`);
    closeAddModal();
    await loadTypes();
    await refresh();
  } catch (error) {
    emit("status", `Ошибка добавления легенды КП: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function removeRow(row: CpLegendRow) {
  if (!window.confirm(`Удалить КП ${row.cp_number} «${row.name}»?`)) return;
  localBusy.value = true;
  try {
    await invoke("delete_cp_legend", { legendId: row.id });
    if (editId.value === row.id) cancelEdit();
    emit("status", `КП ${row.cp_number} удалён.`);
    await loadTypes();
    await refresh();
  } catch (error) {
    emit("status", `Ошибка удаления легенды КП: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function openTypesModal() {
  typesError.value = "";
  newTypeName.value = "";
  typesModalOpen.value = true;
  await loadTypes();
}

function closeTypesModal() {
  typesModalOpen.value = false;
  typesError.value = "";
  newTypeName.value = "";
}

async function addType() {
  const name = newTypeName.value.trim();
  if (!name) {
    typesError.value = "Введите имя нового типа.";
    return;
  }
  typesBusy.value = true;
  typesError.value = "";
  try {
    const rowsData = await invoke<CpLegendTypeRow[]>("add_cp_legend_type", {
      typeName: name,
    });
    applyTypeRows(rowsData);
    newTypeName.value = "";
    emit("status", `Тип КП «${name}» добавлен.`);
  } catch (error) {
    typesError.value = String(error);
  } finally {
    typesBusy.value = false;
  }
}

async function renameType(typeId: number, oldName: string) {
  const newName = String(renameDrafts.value[typeId] || "").trim();
  if (!newName) {
    typesError.value = "Имя типа не может быть пустым.";
    return;
  }
  if (newName === oldName) return;
  typesBusy.value = true;
  typesError.value = "";
  try {
    const rowsData = await invoke<CpLegendTypeRow[]>("rename_cp_legend_type", {
      typeId,
      newTypeName: newName,
    });
    applyTypeRows(rowsData);
    await refresh();
    emit("status", `Тип КП «${oldName}» переименован в «${newName}».`);
  } catch (error) {
    typesError.value = String(error);
  } finally {
    typesBusy.value = false;
  }
}

async function deleteType(typeId: number, name: string, usageCount: number) {
  if (usageCount > 0) {
    typesError.value = `Нельзя удалить тип «${name}»: используется в легендах (${usageCount}).`;
    return;
  }
  typesBusy.value = true;
  typesError.value = "";
  try {
    const rowsData = await invoke<CpLegendTypeRow[]>("delete_cp_legend_type", {
      typeId,
    });
    applyTypeRows(rowsData);
    if (editTypeId.value === String(typeId)) editTypeId.value = "";
    if (addTypeId.value === String(typeId)) addTypeId.value = "";
    emit("status", `Тип КП «${name}» удалён.`);
  } catch (error) {
    typesError.value = String(error);
  } finally {
    typesBusy.value = false;
  }
}
</script>

<template>
  <section class="native-tools nested-card">
    <h2>Легенды КП</h2>
    <p class="subtitle">
      CSV без заголовка: номер;название;тип (тип необязателен). Номер — уникальное целое число.
      При импорте типы добавляются в справочник автоматически.
    </p>
    <div class="native-row">
      <FilePickerButton
        button-label="Выбрать легенды КП"
        empty-label="Файл не выбран"
        @file-selected="onFileSelected"
      />
      <button :disabled="props.busy || localBusy" @click="importLegends">
        Импорт легенд
      </button>
      <button :disabled="props.busy || localBusy" @click="openAddModal">Добавить КП</button>
      <button :disabled="props.busy || localBusy" @click="openTypesModal">Справочник типов</button>
      <button :disabled="props.busy || localBusy" @click="refresh">Обновить</button>
    </div>

    <div class="native-row">
      <FilePickerButton
        accept="image/*,.png,.jpg,.jpeg,.gif,.webp,.bmp,.svg,.tif,.tiff,.ico,.avif"
        button-label="Выбрать карту"
        @file-selected="onMapFileSelected"
      />
      <button :disabled="props.busy || localBusy || !mapInfo.has_map" @click="openMapWindow">
        Показать карту
      </button>
      <IconActionButton
        variant="delete"
        label="Удалить карту"
        :disabled="props.busy || localBusy || !mapInfo.has_map"
        @click="clearMap"
      />
      <span class="subtitle">
        {{
          mapInfo.has_map
            ? `Карта: ${mapInfo.file_name || "загружена"}`
            : "Карта не загружена"
        }}
      </span>
    </div>

    <div class="native-settings">
      <label>
        Масштаб карты 1 :
        <input
          v-model.number="scaleDenominator"
          type="number"
          min="1"
          step="1"
          placeholder="15000"
          style="width: 7rem"
          :disabled="props.busy || localBusy"
        />
      </label>
      <IconActionButton
        variant="save"
        label="Сохранить масштаб"
        :disabled="props.busy || localBusy"
        @click="saveMapScale"
      />
      <button
        type="button"
        :disabled="props.busy || localBusy || scaleDenominator == null"
        title="Очистить масштаб"
        @click="clearMapScale"
      >
        Сбросить
      </button>
      <span class="subtitle">
        Справочно (1:N карты). Километраж участников считается по GPS-привязке
      </span>
      <span class="subtitle">{{ georefStatus }}</span>
    </div>
    <p class="subtitle" style="margin-top: 4px">
      GPS-точки для привязки ставятся на карте (режим «GPS-привязка»). Нужно минимум 2 точки с широтой и долготой.
    </p>

    <div class="native-settings">
      <label>
        Поиск:
        <input v-model="search" type="search" placeholder="Номер, название или тип" />
      </label>
      <span class="subtitle">Показано: {{ filteredRows.length }} из {{ rows.length }}</span>
    </div>

    <div class="native-results-wrap">
      <table class="native-results">
        <thead>
          <tr>
            <th>Номер</th>
            <th>Название</th>
            <th>Тип</th>
            <th>Действие</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!filteredRows.length">
            <td colspan="4">Легенд КП нет</td>
          </tr>
          <tr
            v-for="row in filteredRows"
            :key="row.id"
            class="editable-row"
            @click="beginEdit(row)"
          >
            <td>
              <span class="legend-cp-cell">
                <span
                  class="course-map-cp-dot"
                  :style="{ background: cpTypeColor(row.cp_type_name) }"
                ></span>
                <input
                  v-if="isEditingRow(row.id)"
                  v-model.number="editNumber"
                  class="row-edit-input"
                  type="number"
                  step="1"
                  @click.stop
                />
                <span v-else class="course-map-cp-num">{{ row.cp_number }}</span>
              </span>
            </td>
            <td class="cell-wrap">
              <textarea
                v-if="isEditingRow(row.id)"
                v-model="editName"
                class="row-edit-input"
                rows="2"
                @click.stop
              />
              <span v-else>{{ row.name }}</span>
            </td>
            <td>
              <select
                v-if="isEditingRow(row.id)"
                :key="`type-${row.id}`"
                v-model="editTypeId"
                class="row-edit-input"
                @click.stop
                @mousedown.stop
              >
                <option value="">Не выбран</option>
                <option v-for="t in typeRows" :key="`type-edit-${t.id}`" :value="String(t.id)">
                  {{ t.type_name }}
                </option>
              </select>
              <span v-else>{{ row.cp_type_name || "—" }}</span>
            </td>
            <td>
              <template v-if="isEditingRow(row.id)">
                <IconActionButton
                  variant="save"
                  label="Сохранить"
                  :disabled="props.busy || localBusy"
                  @click="saveEdit"
                />
                <IconActionButton
                  variant="cancel"
                  label="Отмена"
                  :disabled="props.busy || localBusy"
                  @click="cancelEdit"
                />
              </template>
              <IconActionButton
                v-else
                variant="delete"
                label="Удалить"
                :disabled="props.busy || localBusy"
                @click="removeRow(row)"
              />
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="addModalOpen" class="modal-backdrop" @click.self="closeAddModal">
      <div class="modal-card" role="dialog" aria-modal="true" aria-label="Добавить КП">
        <div class="modal-header">
          <h3>Добавить КП</h3>
          <button
            class="modal-close-btn"
            type="button"
            title="Закрыть"
            aria-label="Закрыть"
            :disabled="localBusy"
            @click="closeAddModal"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path
                d="M18.3 5.7a1 1 0 0 0-1.4-1.4L12 9.17 7.1 4.3A1 1 0 0 0 5.7 5.7L10.59 10.6 5.7 15.5a1 1 0 1 0 1.4 1.4L12 12l4.9 4.9a1 1 0 0 0 1.4-1.4L13.41 10.6l4.89-4.9z"
                fill="currentColor"
              />
            </svg>
          </button>
        </div>

        <div class="native-settings">
          <label>
            Номер КП
            <input v-model.number="addNumber" type="number" step="1" placeholder="например 31" />
          </label>
          <label>
            Название
            <input v-model="addName" type="text" placeholder="Описание точки" />
          </label>
          <label>
            Тип (необязательно)
            <select v-model="addTypeId">
              <option value="">Не выбран</option>
              <option v-for="t in typeRows" :key="`type-add-${t.id}`" :value="String(t.id)">
                {{ t.type_name }}
              </option>
            </select>
          </label>
        </div>

        <div class="native-row" style="margin-top: 12px">
          <IconActionButton
            variant="save"
            label="Сохранить"
            :disabled="localBusy || props.busy"
            @click="saveAdd"
          />
          <IconActionButton
            variant="cancel"
            label="Отмена"
            :disabled="localBusy"
            @click="closeAddModal"
          />
        </div>
      </div>
    </div>

    <div
      v-if="clearMapModalOpen"
      class="modal-backdrop"
      @click.self="closeClearMapModal"
    >
      <div class="modal-card" role="dialog" aria-modal="true" aria-label="Удалить карту">
        <div class="modal-header">
          <h3>Удалить карту</h3>
          <button
            class="modal-close-btn"
            type="button"
            title="Закрыть"
            aria-label="Закрыть"
            :disabled="localBusy"
            @click="closeClearMapModal"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path
                d="M18.3 5.7a1 1 0 0 0-1.4-1.4L12 9.17 7.1 4.3A1 1 0 0 0 5.7 5.7L10.59 10.6 5.7 15.5a1 1 0 1 0 1.4 1.4L12 12l4.9 4.9a1 1 0 0 0 1.4-1.4L13.41 10.6l4.89-4.9z"
                fill="currentColor"
              />
            </svg>
          </button>
        </div>

        <p>
          На карте уже размещено точек: {{ clearMapPlacedTotal }}
        </p>
        <ul class="subtitle" style="margin: 8px 0 12px; padding-left: 18px">
          <li v-if="clearMapPlacedLegends > 0">
            КП из легенды: {{ clearMapPlacedLegends }}
          </li>
          <li v-if="clearMapPlacedSpecial > 0">
            старт/финиш: {{ clearMapPlacedSpecial }}
          </li>
        </ul>
        <p class="subtitle">
          «Удалить карту» уберёт только изображение — координаты останутся для новой карты.
          «Удалить карту и точки» также сбросит все размещённые координаты.
        </p>

        <div class="native-row" style="margin-top: 12px; flex-wrap: wrap">
          <IconActionButton
            variant="cancel"
            label="Отмена"
            :disabled="localBusy"
            @click="closeClearMapModal"
          />
          <button :disabled="localBusy || props.busy" @click="performClearMap(false)">
            Удалить карту
          </button>
          <button :disabled="localBusy || props.busy" @click="performClearMap(true)">
            Удалить карту и точки
          </button>
        </div>
      </div>
    </div>

    <div v-if="typesModalOpen" class="modal-backdrop" @click.self="closeTypesModal">
      <div class="modal-card" role="dialog" aria-modal="true" aria-label="Справочник типов КП">
        <div class="modal-header">
          <h3>Справочник типов КП</h3>
          <button
            class="modal-close-btn"
            type="button"
            title="Закрыть"
            aria-label="Закрыть"
            :disabled="typesBusy"
            @click="closeTypesModal"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path
                d="M18.3 5.7a1 1 0 0 0-1.4-1.4L12 9.17 7.1 4.3A1 1 0 0 0 5.7 5.7L10.59 10.6 5.7 15.5a1 1 0 1 0 1.4 1.4L12 12l4.9 4.9a1 1 0 0 0 1.4-1.4L13.41 10.6l4.89-4.9z"
                fill="currentColor"
              />
            </svg>
          </button>
        </div>
        <p class="subtitle">
          Можно добавить новый тип или переименовать существующий.
          Переименование обновит отображение во всех легендах с этим типом.
        </p>

        <div class="native-settings">
          <label>
            Новый тип:
            <input
              v-model="newTypeName"
              type="text"
              placeholder="Например: сухопутный"
              @keyup.enter="addType"
            />
          </label>
          <button :disabled="typesBusy || props.busy" @click="addType">Добавить</button>
        </div>

        <p v-if="typesError" class="status">{{ typesError }}</p>

        <div class="native-results-wrap">
          <table class="native-results">
            <thead>
              <tr>
                <th>Текущее имя</th>
                <th>Новое имя</th>
                <th>КП</th>
                <th>Действие</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!typeRows.length">
                <td colspan="4">Справочник типов пуст</td>
              </tr>
              <tr v-for="item in typeRows" :key="item.id">
                <td>{{ item.type_name }}</td>
                <td>
                  <input
                    v-model="renameDrafts[item.id]"
                    class="row-edit-input"
                    type="text"
                    @keyup.enter="renameType(item.id, item.type_name)"
                  />
                </td>
                <td>
                  <span class="format-usage-cell">
                    <span>{{ item.usage_count }}</span>
                    <IconActionButton
                      v-if="item.usage_count === 0"
                      variant="delete"
                      label="Удалить тип"
                      :disabled="typesBusy || props.busy"
                      @click="deleteType(item.id, item.type_name, item.usage_count)"
                    />
                  </span>
                </td>
                <td>
                  <button
                    :disabled="
                      typesBusy ||
                      props.busy ||
                      String(renameDrafts[item.id] || '').trim() === item.type_name
                    "
                    @click="renameType(item.id, item.type_name)"
                  >
                    Переименовать
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <ImportExistingDataDialog
      :open="importDialogOpen"
      kind="legends"
      :existing-count="importExistingCount"
      :file-name="legendsFile?.name"
      :busy="localBusy || props.busy"
      @cancel="onImportDialogCancel"
      @merge="onImportDialogMerge"
      @replace="onImportDialogReplace"
    />
  </section>
</template>
