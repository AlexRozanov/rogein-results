<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import NativeResultsTable from "./NativeResultsTable.vue";
import FilePickerButton from "./FilePickerButton.vue";
import NativeFilters from "./NativeFilters.vue";
import NativeAdjustments from "./NativeAdjustments.vue";
import NativeStartProtocol from "./NativeStartProtocol.vue";
import NativeCpLegends from "./NativeCpLegends.vue";
import NativeSettingsTab from "./NativeSettingsTab.vue";
import ImportExistingDataDialog from "./ImportExistingDataDialog.vue";
import IconActionButton from "./IconActionButton.vue";
import NewStartWizard from "./NewStartWizard.vue";
import {
  loadWorkspaceUiState,
  openAuxWebviewWindow,
  saveWorkspaceUiState,
  type WorkspaceUiState,
} from "../workspaceUiState";

type ImportSummary = {
  participants_count: number;
  results_count: number;
};

type NativeSettings = {
  control_minutes: number;
  penalty_per_minute: number;
  dq_minutes: number;
  finish_cp: number;
  start_mode: "station" | "time";
  start_cp: number | null;
  competition_date: string;
  competition_start_time: string;
};

type NativeResultRow = {
  id: number;
  finish_participant_id: number | null;
  chip_raw_id: string | null;
  participant_id: string;
  name: string;
  status: "OK" | "Дисквалификация" | "Ошибка" | "Не стартовал" | "Нет в протоколе";
  format_id: number | null;
  format_name: string;
  team_id: number | null;
  team_size: number;
  teammates: string;
  gender: string | null;
  age: number | null;
  has_personal_corrections: boolean;
  has_anomalies: boolean;
  anomaly_count: number;
  points_raw: number;
  penalty_points: number;
  points_final: number;
  elapsed_seconds: number;
  delay_seconds: number;
  penalty_minutes: number;
  diagnostics_json: string;
  computed_at: string;
};

type NativeResultsResponse = {
  rows: NativeResultRow[];
  counts: Record<string, number>;
  total_count: number;
  offset: number;
  limit: number;
};

type AnomalyScanSummary = {
  participants_checked: number;
  participants_with_anomalies: number;
  anomalies_total: number;
  by_type: Record<string, number>;
  potential_anomalies: {
    anomaly_type: string;
    title: string;
    details: string;
    payload: Record<string, unknown>;
  }[];
};

type AnomalyListItem = {
  id: number;
  scope: string;
  anomaly_type: string;
  title: string;
  details: string;
  is_potential: boolean;
  resolved: boolean;
  participant_id: string | null;
  name: string | null;
  result_id: number | null;
};

type AnomalyListResponse = {
  items: AnomalyListItem[];
  day_shift_total: number;
  day_shift_resolved: number;
};

type BulkAnomalyCorrectionSummary = {
  participants_with_anomaly: number;
  inserted: number;
  already_corrected: number;
};

type BulkAnomalyRollbackSummary = {
  removed: number;
};

type AnomalyBulkActionsState = {
  can_apply_day_shift_24h: boolean;
  can_rollback_day_shift_24h: boolean;
  available_apply_count: number;
  applied_count: number;
};

type FormatOption = {
  id: number;
  format_name: string;
};

type AwardGroupOption = {
  id: number;
  name: string;
};

type SortBy = "participant_id" | "name" | "points_raw" | "points_final" | "elapsed_seconds";
type SortDir = "asc" | "desc";
type MainTab = "results" | "start_protocol" | "cp_legends" | "settings";

const nativeStatus = ref("");
const csvFile = ref<File | null>(null);
const importDialogOpen = ref(false);
const importExistingCount = ref(0);
const nativeBusy = ref(false);
const showNewStartWizard = ref(false);
const wizardDismissed = ref(false);
const workspaceWasEmpty = ref(true);

const nativeSettings = ref<NativeSettings | null>(null);
const nativeResults = ref<NativeResultRow[]>([]);
const nativeCounts = ref<Record<string, number>>({
  OK: 0,
  Дисквалификация: 0,
  Ошибка: 0,
  "Не стартовал": 0,
  "Нет в протоколе": 0,
});
const nativeFilterStatus = ref<string>("");
const nativeFilterSearch = ref<string>("");
const nativeFilterFormatId = ref<string>("");
const nativeFilterAwardGroupId = ref<string>("");
const formatOptions = ref<FormatOption[]>([]);
const awardGroupOptions = ref<AwardGroupOption[]>([]);
const pageSize = ref(50);
const pageOffset = ref(0);
const totalCount = ref(0);
const sortBy = ref<SortBy>("points_final");
const sortDir = ref<SortDir>("desc");
const activeTab = ref<MainTab>("start_protocol");
const anomalyBulkState = ref<AnomalyBulkActionsState>({
  can_apply_day_shift_24h: false,
  can_rollback_day_shift_24h: false,
  available_apply_count: 0,
  applied_count: 0,
});
const anomalyList = ref<AnomalyListItem[]>([]);
const anomalyDayShiftTotal = ref(0);
const anomalyDayShiftResolved = ref(0);
const anomaliesExpanded = ref(true);

const anomalyDayShiftActive = computed(() =>
  Math.max(0, anomalyDayShiftTotal.value - anomalyDayShiftResolved.value),
);

const hasAnomaliesPanel = computed(
  () => anomalyList.value.length > 0 || anomalyDayShiftTotal.value > 0,
);

const anomalyPanelActiveCount = computed(
  () => anomalyList.value.filter((a) => !a.resolved).length + anomalyDayShiftActive.value,
);
const selectedResultId = ref<number | null>(null);

function snapshotWorkspaceUi(): WorkspaceUiState {
  return {
    activeTab: activeTab.value,
    filterStatus: nativeFilterStatus.value,
    filterSearch: nativeFilterSearch.value,
    filterFormatId: nativeFilterFormatId.value,
    filterAwardGroupId: nativeFilterAwardGroupId.value,
    pageSize: pageSize.value,
    pageOffset: pageOffset.value,
    sortBy: sortBy.value,
    sortDir: sortDir.value,
  };
}

function persistWorkspaceUi() {
  saveWorkspaceUiState(snapshotWorkspaceUi());
}

function applyWorkspaceUi(state: WorkspaceUiState) {
  activeTab.value = state.activeTab;
  nativeFilterStatus.value = state.filterStatus;
  nativeFilterSearch.value = state.filterSearch;
  nativeFilterFormatId.value = state.filterFormatId;
  nativeFilterAwardGroupId.value = state.filterAwardGroupId;
  pageSize.value = state.pageSize;
  pageOffset.value = state.pageOffset;
  sortBy.value = state.sortBy;
  sortDir.value = state.sortDir;
}

function selectResult(resultId: number) {
  selectedResultId.value = resultId;
  void openResultWindow(resultId);
}

async function openResultWindow(resultId: number) {
  const id = Number(resultId);
  if (!Number.isFinite(id) || id <= 0) {
    nativeStatus.value = "ID результата пустой.";
    return;
  }
  selectedResultId.value = id;
  persistWorkspaceUi();
  const targetHash = `#result/${id}`;
  const opened = await openAuxWebviewWindow({
    label: "participant-card",
    title: `Карточка результата #${id}`,
    width: 1220,
    height: 900,
    url: targetHash,
  });
  if (!opened.ok) {
    nativeStatus.value =
      "Не удалось открыть отдельное окно карточки, открыл карточку в текущем окне.";
    window.location.hash = targetHash;
    console.error(opened.error);
  }
}

async function openErrorsWindow() {
  const count = nativeCounts.value["Ошибка"] ?? 0;
  if (count <= 0) return;
  persistWorkspaceUi();
  const targetHash = "#errors";
  const opened = await openAuxWebviewWindow({
    label: "errors-list",
    title: `Ошибки (${count})`,
    width: 960,
    height: 720,
    url: targetHash,
  });
  if (opened.ok) {
    nativeStatus.value = `Открыт список ошибок (${count}).`;
  } else {
    nativeStatus.value = "Не удалось открыть окно ошибок, открыл список в текущем окне.";
    window.location.hash = targetHash;
    console.error(opened.error);
  }
}

async function openCpRemapWindow() {
  persistWorkspaceUi();
  const targetHash = "#cp-remap";
  const opened = await openAuxWebviewWindow({
    label: "cp-remap",
    title: "Путаница станций КП",
    width: 1220,
    height: 900,
    url: targetHash,
  });
  if (opened.ok) {
    nativeStatus.value = "Открыто окно «Путаница КП».";
  } else {
    nativeStatus.value =
      "Не удалось открыть отдельное окно путаницы КП, открыл в текущем окне.";
    window.location.hash = targetHash;
    console.error(opened.error);
  }
}

onMounted(() => {
  const restored = loadWorkspaceUiState();
  if (restored) {
    applyWorkspaceUi(restored);
  }
  void refreshNativeData();
});

async function onCsvSelected(file: File | null) {
  csvFile.value = file;
  if (!csvFile.value) return;
  await beginCsvImportFlow();
}

async function onCsvImportClick() {
  if (!csvFile.value) {
    nativeStatus.value = "Выберите CSV файл.";
    return;
  }
  await beginCsvImportFlow();
}

async function beginCsvImportFlow() {
  try {
    const counts = await invoke<{ finish_participants: number }>("get_data_presence_counts");
    if (counts.finish_participants > 0) {
      importExistingCount.value = counts.finish_participants;
      importDialogOpen.value = true;
      return;
    }
    await importCsv(true);
  } catch (error) {
    nativeStatus.value = `Ошибка проверки данных: ${String(error)}`;
  }
}

async function onImportDialogCancel() {
  importDialogOpen.value = false;
}

async function onImportDialogMerge() {
  importDialogOpen.value = false;
  await importCsv(false);
}

async function onImportDialogReplace() {
  importDialogOpen.value = false;
  await importCsv(true);
}

async function importCsv(reset: boolean) {
  if (!csvFile.value) {
    nativeStatus.value = "Выберите CSV файл.";
    return;
  }
  nativeBusy.value = true;
  nativeStatus.value = reset
    ? "Импорт в native SQLite (загрузка заново)..."
    : "Импорт в native SQLite (добавление новых)...";
  try {
    const content = await csvFile.value.text();
    const summary = await invoke<ImportSummary>("import_csv_content", {
      csvContent: content,
      reset,
    });
    nativeStatus.value =
      `Импорт завершен: участников ${summary.participants_count}, результатов ${summary.results_count}.`;
    await refreshNativeData();
  } catch (error) {
    nativeStatus.value = `Ошибка импорта: ${String(error)}`;
  } finally {
    nativeBusy.value = false;
  }
}

async function recalculateNative() {
  nativeBusy.value = true;
  nativeStatus.value = "Пересчет native результатов...";
  try {
    await invoke("recalculate_results");
    nativeStatus.value = "Пересчет завершен.";
    await refreshNativeData();
  } catch (error) {
    nativeStatus.value = `Ошибка пересчета: ${String(error)}`;
  } finally {
    nativeBusy.value = false;
  }
}

async function refreshNativeData() {
  try {
    const settings = await invoke<NativeSettings>("get_settings");
    nativeSettings.value = settings;
    formatOptions.value = await invoke<FormatOption[]>("get_start_protocol_format_rows");
    awardGroupOptions.value = await invoke<AwardGroupOption[]>("get_award_groups");
    if (
      nativeFilterAwardGroupId.value &&
      !awardGroupOptions.value.some((g) => String(g.id) === nativeFilterAwardGroupId.value)
    ) {
      nativeFilterAwardGroupId.value = "";
    }
    const onlyMissingFormat = nativeFilterFormatId.value === "missing";
    const formatId =
      !nativeFilterFormatId.value || onlyMissingFormat
        ? null
        : Number(nativeFilterFormatId.value);
    const awardGroupId = nativeFilterAwardGroupId.value
      ? Number(nativeFilterAwardGroupId.value)
      : null;
    const response = await invoke<NativeResultsResponse>("get_results", {
      limit: pageSize.value,
      offset: pageOffset.value,
      status: nativeFilterStatus.value || null,
      search: nativeFilterSearch.value || null,
      formatId,
      onlyMissingFormat,
      awardGroupId,
      sortBy: sortBy.value,
      sortDir: sortDir.value,
    });
    nativeResults.value = response.rows;
    nativeCounts.value = response.counts;
    totalCount.value = response.total_count;
    pageOffset.value = response.offset;
    anomalyBulkState.value = await invoke<AnomalyBulkActionsState>(
      "get_anomaly_bulk_actions_state",
    );
    const anomalyReport = await invoke<AnomalyListResponse>("get_anomaly_list");
    anomalyList.value = anomalyReport.items || [];
    anomalyDayShiftTotal.value = anomalyReport.day_shift_total || 0;
    anomalyDayShiftResolved.value = anomalyReport.day_shift_resolved || 0;
    await updateWizardVisibility();
  } catch (error) {
    nativeStatus.value = `Ошибка загрузки native данных: ${String(error)}`;
  }
}

async function updateWizardVisibility() {
  try {
    const counts = await invoke<{
      finish_participants: number;
      start_protocol: number;
      cp_legends: number;
    }>("get_data_presence_counts");
    const empty =
      counts.finish_participants <= 0 &&
      counts.start_protocol <= 0 &&
      counts.cp_legends <= 0;
    if (!empty) {
      workspaceWasEmpty.value = false;
      wizardDismissed.value = false;
      showNewStartWizard.value = false;
      return;
    }
    // После очистки старта показываем визард снова.
    if (!workspaceWasEmpty.value) {
      wizardDismissed.value = false;
    }
    workspaceWasEmpty.value = true;
    showNewStartWizard.value = !wizardDismissed.value;
  } catch {
    // keep current wizard state if presence check fails
  }
}

async function onWizardFinished() {
  wizardDismissed.value = true;
  showNewStartWizard.value = false;
  await refreshNativeData();
}

function onWizardDismissed() {
  wizardDismissed.value = true;
  showNewStartWizard.value = false;
}

function anomalyTypeLabel(anomalyType: string) {
  if (anomalyType === "start_time_day_shift") return "Сдвиг даты старта";
  if (anomalyType === "unused_cp_never_taken") return "КП без отметок";
  return anomalyType;
}

function toggleAnomaliesPanel() {
  anomaliesExpanded.value = !anomaliesExpanded.value;
}

function onAnomalyClick(item: AnomalyListItem) {
  if (item.result_id != null && item.result_id > 0) {
    void openResultWindow(item.result_id);
  }
}

async function scanAnomalies() {
  const settings = nativeSettings.value;
  if (!settings?.competition_date?.trim()) {
    nativeStatus.value =
      "Предупреждение: для поиска аномалии сдвига на сутки заполните 'Дата соревнования'.";
    return;
  }
  nativeBusy.value = true;
  nativeStatus.value = "Поиск аномалий...";
  try {
    await invoke<AnomalyScanSummary>("scan_anomalies");
    await refreshNativeData();
    if (!hasAnomaliesPanel.value) {
      nativeStatus.value = "Поиск аномалий завершён: аномалий не найдено.";
    } else {
      nativeStatus.value = `Поиск аномалий завершён: активных ${anomalyPanelActiveCount.value}.`;
    }
  } catch (error) {
    nativeStatus.value = `Ошибка поиска аномалий: ${String(error)}`;
  } finally {
    nativeBusy.value = false;
  }
}

async function applyAnomalyDayShiftForAll() {
  nativeBusy.value = true;
  nativeStatus.value = "Применяю -24ч всем участникам с аномалией...";
  try {
    const summary = await invoke<BulkAnomalyCorrectionSummary>(
      "add_anomaly_day_shift_corrections_for_all",
    );
    await invoke("recalculate_results");
    await refreshNativeData();
    nativeStatus.value =
      `Массовая корректировка выполнена: с аномалией ${summary.participants_with_anomaly}, добавлено ${summary.inserted}, уже было ${summary.already_corrected}.`;
  } catch (error) {
    nativeStatus.value = `Ошибка массовой корректировки: ${String(error)}`;
  } finally {
    nativeBusy.value = false;
  }
}

async function rollbackAnomalyDayShiftForAll() {
  nativeBusy.value = true;
  nativeStatus.value = "Откатываю -24ч корректировки...";
  try {
    const summary = await invoke<BulkAnomalyRollbackSummary>(
      "rollback_anomaly_day_shift_corrections_for_all",
    );
    await invoke("recalculate_results");
    await refreshNativeData();
    nativeStatus.value = `Откат выполнен: удалено корректировок ${summary.removed}.`;
  } catch (error) {
    nativeStatus.value = `Ошибка отката корректировок: ${String(error)}`;
  } finally {
    nativeBusy.value = false;
  }
}

async function applyFilters() {
  pageOffset.value = 0;
  await refreshNativeData();
}

async function prevPage() {
  pageOffset.value = Math.max(0, pageOffset.value - pageSize.value);
  await refreshNativeData();
}

async function nextPage() {
  if (pageOffset.value + pageSize.value >= totalCount.value) return;
  pageOffset.value += pageSize.value;
  await refreshNativeData();
}

async function firstPage() {
  if (pageOffset.value <= 0) return;
  pageOffset.value = 0;
  await refreshNativeData();
}

async function lastPage() {
  const lastOffset = Math.max(0, (totalPages.value - 1) * pageSize.value);
  if (pageOffset.value === lastOffset) return;
  pageOffset.value = lastOffset;
  await refreshNativeData();
}

function pageFrom() {
  return totalCount.value === 0 ? 0 : pageOffset.value + 1;
}

function pageTo() {
  return Math.min(pageOffset.value + pageSize.value, totalCount.value);
}

const totalPages = computed(() =>
  totalCount.value > 0 ? Math.ceil(totalCount.value / pageSize.value) : 1,
);
const currentPage = computed(() => Math.floor(pageOffset.value / pageSize.value) + 1);
const pageButtons = computed(() => {
  const pages: number[] = [];
  const max = totalPages.value;
  if (max <= 0) return pages;
  const cur = currentPage.value;
  const windowSize = 10;
  let start = Math.max(1, cur - Math.floor((windowSize - 1) / 2));
  let end = start + windowSize - 1;
  if (end > max) {
    end = max;
    start = Math.max(1, end - windowSize + 1);
  }
  for (let p = start; p <= end; p += 1) pages.push(p);
  return pages;
});

async function goToPage(page: number) {
  const safePage = Math.min(Math.max(1, page), totalPages.value);
  pageOffset.value = (safePage - 1) * pageSize.value;
  await refreshNativeData();
}

async function changePageSize(size: string) {
  const parsed = Number(size);
  if (!Number.isFinite(parsed) || parsed <= 0) return;
  pageSize.value = parsed;
  pageOffset.value = 0;
  await refreshNativeData();
}

async function onSortChanged(column: SortBy) {
  if (sortBy.value === column) {
    sortDir.value = sortDir.value === "asc" ? "desc" : "asc";
  } else {
    sortBy.value = column;
    sortDir.value = column === "elapsed_seconds" ? "asc" : "desc";
  }
  pageOffset.value = 0;
  await refreshNativeData();
}
</script>

<template>
  <section class="native-tools">
    <div class="native-tabs">
      <button
        class="tab-btn"
        :class="{ active: activeTab === 'start_protocol' }"
        :disabled="nativeBusy && activeTab !== 'start_protocol'"
        @click="activeTab = 'start_protocol'"
      >
        Стартовый протокол
      </button>
      <button
        class="tab-btn"
        :class="{ active: activeTab === 'cp_legends' }"
        :disabled="nativeBusy && activeTab !== 'cp_legends'"
        @click="activeTab = 'cp_legends'"
      >
        Легенды КП
      </button>
      <button
        class="tab-btn"
        :class="{ active: activeTab === 'results' }"
        :disabled="nativeBusy && activeTab !== 'results'"
        @click="activeTab = 'results'"
      >
        Результаты
      </button>
      <button
        class="tab-btn"
        :class="{ active: activeTab === 'settings' }"
        :disabled="nativeBusy && activeTab !== 'settings'"
        @click="activeTab = 'settings'"
      >
        Настройки
      </button>
    </div>
    <p v-if="nativeStatus" class="status">{{ nativeStatus }}</p>

    <template v-if="activeTab === 'results'">
      <div class="native-row">
        <FilePickerButton @file-selected="onCsvSelected" />
        <button :disabled="nativeBusy || !csvFile" @click="onCsvImportClick">Импорт CSV</button>
        <button :disabled="nativeBusy" @click="recalculateNative">Пересчитать</button>
        <button :disabled="nativeBusy" @click="scanAnomalies">Поиск аномалий</button>
        <button :disabled="nativeBusy" @click="openCpRemapWindow">Путаница КП</button>
        <button
          v-if="anomalyBulkState.can_apply_day_shift_24h"
          :disabled="nativeBusy"
          @click="applyAnomalyDayShiftForAll"
        >
          Применить -24ч всем с аномалией ({{ anomalyBulkState.available_apply_count }})
        </button>
        <button
          v-if="anomalyBulkState.can_rollback_day_shift_24h"
          :disabled="nativeBusy"
          @click="rollbackAnomalyDayShiftForAll"
        >
          Откатить -24ч у всех ({{ anomalyBulkState.applied_count }})
        </button>
      </div>
      <p class="subtitle">
        При выборе файла импорт запускается автоматически.
      </p>

      <div class="native-summary">
        <span>OK: {{ nativeCounts.OK ?? 0 }}</span>
        <span>Дисквалификация: {{ nativeCounts["Дисквалификация"] ?? 0 }}</span>
        <button
          v-if="(nativeCounts['Ошибка'] ?? 0) > 0"
          type="button"
          class="summary-link"
          @click="openErrorsWindow"
        >
          Ошибка: {{ nativeCounts["Ошибка"] ?? 0 }}
        </button>
        <span v-else>Ошибка: {{ nativeCounts["Ошибка"] ?? 0 }}</span>
        <span>Не стартовал: {{ nativeCounts["Не стартовал"] ?? 0 }}</span>
        <span>Нет в протоколе: {{ nativeCounts["Нет в протоколе"] ?? 0 }}</span>
      </div>

      <section v-if="hasAnomaliesPanel" class="anomalies-panel">
        <button
          type="button"
          class="anomalies-panel-toggle"
          :aria-expanded="anomaliesExpanded"
          @click="toggleAnomaliesPanel"
        >
          <span class="anomalies-panel-chevron" :class="{ collapsed: !anomaliesExpanded }">▾</span>
          <span>
            Аномалии ({{ anomalyPanelActiveCount }})
            <template v-if="anomalyList.some((a) => a.is_potential && !a.resolved)">
              · есть потенциальные
            </template>
          </span>
        </button>
        <ul v-show="anomaliesExpanded" class="anomalies-panel-list">
          <li
            v-if="anomalyDayShiftTotal > 0"
            class="anomalies-panel-item"
            :class="{ resolved: anomalyDayShiftActive === 0 }"
          >
            <span class="anomalies-panel-kind">
              <template v-if="anomalyDayShiftActive === 0">Исправлена</template>
              <template v-else>Аномалия</template>
              · {{ anomalyTypeLabel("start_time_day_shift") }}
            </span>
            <span class="anomalies-panel-title">Сдвиг даты старта</span>
            <span class="anomalies-panel-details">
              Найдено: {{ anomalyDayShiftTotal }},
              исправлено: {{ anomalyDayShiftResolved }}
              <template v-if="anomalyDayShiftActive > 0">
                , осталось: {{ anomalyDayShiftActive }}
              </template>
            </span>
          </li>
          <li
            v-for="item in anomalyList"
            :key="`${item.scope}-${item.id}`"
            class="anomalies-panel-item"
            :class="{
              clickable: item.result_id != null,
              resolved: item.resolved,
            }"
            @click="onAnomalyClick(item)"
          >
            <span class="anomalies-panel-kind">
              <template v-if="item.resolved">Исправлена</template>
              <template v-else-if="item.is_potential">Потенциальная</template>
              <template v-else>Аномалия</template>
              · {{ anomalyTypeLabel(item.anomaly_type) }}
            </span>
            <span class="anomalies-panel-title">{{ item.title }}</span>
            <span
              v-if="item.participant_id || item.name"
              class="anomalies-panel-who"
            >
              {{ item.participant_id || "" }}
              {{ item.name || "" }}
            </span>
            <span class="anomalies-panel-details">{{ item.details }}</span>
          </li>
        </ul>
      </section>

      <NativeFilters
        :busy="nativeBusy"
        :status="nativeFilterStatus"
        :search="nativeFilterSearch"
        :format-id="nativeFilterFormatId"
        :award-group-id="nativeFilterAwardGroupId"
        :formats="formatOptions"
        :award-groups="awardGroupOptions"
        @update:status="
          nativeFilterStatus = $event;
          applyFilters();
        "
        @update:search="nativeFilterSearch = $event"
        @update:format-id="
          nativeFilterFormatId = $event;
          applyFilters();
        "
        @update:award-group-id="
          nativeFilterAwardGroupId = $event;
          applyFilters();
        "
        @apply="applyFilters"
      />

      <NativeResultsTable
        :rows="nativeResults"
        :sort-by="sortBy"
        :sort-dir="sortDir"
        :busy="nativeBusy"
        @result-selected="selectResult"
        @sort-changed="onSortChanged"
        @status="nativeStatus = $event"
        @protocol-updated="refreshNativeData"
      />
      <div class="native-settings">
        <IconActionButton
          variant="pageFirst"
          label="На первую страницу"
          :disabled="nativeBusy || pageOffset <= 0"
          @click="firstPage"
        />
        <IconActionButton
          variant="pagePrev"
          label="Назад"
          :disabled="nativeBusy || pageOffset <= 0"
          @click="prevPage"
        />
        <span class="subtitle">
          Показано {{ pageFrom() }}-{{ pageTo() }} из {{ totalCount }}
        </span>
        <IconActionButton
          variant="pageNext"
          label="Вперёд"
          :disabled="nativeBusy || pageOffset + pageSize >= totalCount"
          @click="nextPage"
        />
        <IconActionButton
          variant="pageLast"
          label="На последнюю страницу"
          :disabled="nativeBusy || pageOffset + pageSize >= totalCount"
          @click="lastPage"
        />
      </div>
      <div class="native-settings">
        <label>
          Строк на странице:
          <select :value="String(pageSize)" @change="changePageSize(($event.target as HTMLSelectElement).value)">
            <option value="25">25</option>
            <option value="50">50</option>
            <option value="100">100</option>
          </select>
        </label>
        <button
          v-for="p in pageButtons"
          :key="`page-${p}`"
          class="page-num-btn"
          :class="{ active: p === currentPage }"
          :disabled="nativeBusy || p === currentPage"
          @click="goToPage(p)"
        >
          {{ p }}
        </button>
        <span class="subtitle">Страница {{ currentPage }} из {{ totalPages }}</span>
      </div>

      <NativeAdjustments :busy="nativeBusy" @status="nativeStatus = $event" />
    </template>

    <template v-else-if="activeTab === 'settings'">
      <NativeSettingsTab
        :busy="nativeBusy"
        @status="nativeStatus = $event"
        @saved="refreshNativeData"
        @workspace-reset="refreshNativeData"
      />
    </template>

    <template v-else-if="activeTab === 'cp_legends'">
      <NativeCpLegends :busy="nativeBusy" @status="nativeStatus = $event" />
    </template>

    <template v-else>
      <NativeStartProtocol
        :busy="nativeBusy"
        @status="nativeStatus = $event"
        @teams-changed="refreshNativeData"
      />
    </template>

    <ImportExistingDataDialog
      :open="importDialogOpen"
      kind="finish"
      :existing-count="importExistingCount"
      :file-name="csvFile?.name"
      :busy="nativeBusy"
      @cancel="onImportDialogCancel"
      @merge="onImportDialogMerge"
      @replace="onImportDialogReplace"
    />

    <NewStartWizard
      v-if="showNewStartWizard"
      :busy="nativeBusy"
      @status="nativeStatus = $event"
      @finished="onWizardFinished"
      @dismissed="onWizardDismissed"
    />
  </section>
</template>
