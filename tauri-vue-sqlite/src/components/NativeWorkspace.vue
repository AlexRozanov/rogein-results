<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { WebviewWindow } from "@tauri-apps/api/webviewWindow";
import NativeResultsTable from "./NativeResultsTable.vue";
import FilePickerButton from "./FilePickerButton.vue";
import NativeSettingsForm from "./NativeSettingsForm.vue";
import NativeFilters from "./NativeFilters.vue";
import NativeAdjustments from "./NativeAdjustments.vue";

type ImportSummary = {
  participants_count: number;
  results_count: number;
};

type NativeSettings = {
  control_minutes: number;
  penalty_per_minute: number;
  dq_minutes: number;
  finish_cp: number;
  competition_date: string;
  competition_start_time: string;
};

type NativeResultRow = {
  participant_id: string;
  name: string;
  status: "OK" | "DQ" | "ERR";
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

type SortBy = "participant_id" | "name" | "points_raw" | "points_final" | "elapsed_seconds";
type SortDir = "asc" | "desc";

const nativeStatus = ref("Native core готов к импорту.");
const csvFile = ref<File | null>(null);
const nativeBusy = ref(false);

const nativeSettings = ref<NativeSettings | null>(null);
const nativeResults = ref<NativeResultRow[]>([]);
const nativeCounts = ref<Record<string, number>>({ OK: 0, DQ: 0, ERR: 0 });
const nativeFilterStatus = ref<string>("");
const nativeFilterSearch = ref<string>("");
const pageSize = ref(50);
const pageOffset = ref(0);
const totalCount = ref(0);
const sortBy = ref<SortBy>("points_final");
const sortDir = ref<SortDir>("desc");
const anomalyBulkState = ref<AnomalyBulkActionsState>({
  can_apply_day_shift_24h: false,
  can_rollback_day_shift_24h: false,
  available_apply_count: 0,
  applied_count: 0,
});
const selectedParticipantId = ref("");
function selectParticipant(participantId: string) {
  selectedParticipantId.value = participantId;
  void openParticipantWindow(participantId);
}

async function openParticipantWindow(participantId: string) {
  const pid = participantId.trim();
  if (!pid) {
    nativeStatus.value = "ID участника пустой.";
    return;
  }
  selectedParticipantId.value = pid;
  const encodedId = encodeURIComponent(pid);
  const targetHash = `#participant/${encodedId}`;
  const label = "participant-card";
  const existing = await WebviewWindow.getByLabel(label);

  if (existing) {
    await existing.close();
  }

  const participantWindow = new WebviewWindow(label, {
    title: `Карточка участника ${pid}`,
    width: 1220,
    height: 900,
    url: targetHash,
  });

  participantWindow.once("tauri://created", () => {
    nativeStatus.value = `Открыта карточка участника ${pid} в отдельном окне.`;
  });

  participantWindow.once("tauri://error", (error) => {
    nativeStatus.value =
      "Не удалось открыть отдельное окно карточки, открыл карточку в текущем окне.";
    window.location.hash = targetHash;
    console.error(error);
  });
}

onMounted(() => {
  void refreshNativeData();
});

async function onCsvSelected(file: File | null) {
  csvFile.value = file;
  if (csvFile.value) {
    await importCsv();
  }
}

async function importCsv() {
  if (!csvFile.value) {
    nativeStatus.value = "Выберите CSV файл.";
    return;
  }
  nativeBusy.value = true;
  nativeStatus.value = "Импорт в native SQLite...";
  try {
    const content = await csvFile.value.text();
    const summary = await invoke<ImportSummary>("import_csv_content", {
      csvContent: content,
      reset: true,
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
    const response = await invoke<NativeResultsResponse>("get_results", {
      limit: pageSize.value,
      offset: pageOffset.value,
      status: nativeFilterStatus.value || null,
      search: nativeFilterSearch.value || null,
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
  } catch (error) {
    nativeStatus.value = `Ошибка загрузки native данных: ${String(error)}`;
  }
}

async function saveNativeSettings(form: NativeSettings) {
  nativeBusy.value = true;
  try {
    const updated = await invoke<NativeSettings>("set_settings", {
      controlMinutes: Number(form.control_minutes),
      penaltyPerMinute: Number(form.penalty_per_minute),
      dqMinutes: Number(form.dq_minutes),
      finishCp: Number(form.finish_cp),
      competitionDate: String(form.competition_date || "").trim(),
      competitionStartTime: String(form.competition_start_time || "").trim(),
    });
    nativeSettings.value = updated;
    nativeStatus.value = "Настройки сохранены.";
    await recalculateNative();
  } catch (error) {
    nativeStatus.value = `Ошибка сохранения настроек: ${String(error)}`;
  } finally {
    nativeBusy.value = false;
  }
}

async function scanAnomalies() {
  const settings = nativeSettings.value;
  if (!settings?.competition_date?.trim() || !settings?.competition_start_time?.trim()) {
    nativeStatus.value =
      "Предупреждение: для поиска аномалии сдвига на сутки заполните 'Дата соревнования' и 'Время общего старта'.";
    return;
  }
  nativeBusy.value = true;
  nativeStatus.value = "Поиск аномалий...";
  try {
    const summary = await invoke<AnomalyScanSummary>("scan_anomalies");
    const byType = Object.entries(summary.by_type || {})
      .map(([k, v]) => `${k}: ${v}`)
      .join(", ");
    nativeStatus.value =
      `Поиск аномалий завершен: участников ${summary.participants_checked}, с аномалиями ${summary.participants_with_anomalies}, найдено ${summary.anomalies_total}` +
      (byType ? ` (${byType})` : ".");
    await refreshNativeData();
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
  const cur = currentPage.value;
  const start = Math.max(1, cur - 2);
  const end = Math.min(max, cur + 2);
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
    <h2>Native Core (этап 1)</h2>
    <p class="subtitle">
      Импорт CSV и пересчет через Rust + SQLite (без Python backend).
    </p>
    <div class="native-row">
      <FilePickerButton @file-selected="onCsvSelected" />
      <button :disabled="nativeBusy" @click="importCsv">Импорт CSV</button>
      <button :disabled="nativeBusy" @click="recalculateNative">Пересчитать</button>
      <button :disabled="nativeBusy" @click="scanAnomalies">Поиск аномалий</button>
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
    <p class="status">{{ nativeStatus }}</p>

    <div class="native-summary">
      <span>OK: {{ nativeCounts.OK ?? 0 }}</span>
      <span>DQ: {{ nativeCounts.DQ ?? 0 }}</span>
      <span>ERR: {{ nativeCounts.ERR ?? 0 }}</span>
    </div>
    <NativeSettingsForm
      :settings="nativeSettings"
      :busy="nativeBusy"
      @save="saveNativeSettings"
    />

    <NativeFilters
      :busy="nativeBusy"
      :status="nativeFilterStatus"
      :search="nativeFilterSearch"
      @update:status="nativeFilterStatus = $event"
      @update:search="nativeFilterSearch = $event"
      @apply="applyFilters"
    />

    <NativeResultsTable
      :rows="nativeResults"
      :sort-by="sortBy"
      :sort-dir="sortDir"
      @participant-selected="selectParticipant"
      @sort-changed="onSortChanged"
    />
    <div class="native-settings">
      <button :disabled="nativeBusy || pageOffset <= 0" @click="prevPage">← Назад</button>
      <span class="subtitle">
        Показано {{ pageFrom() }}-{{ pageTo() }} из {{ totalCount }}
      </span>
      <button
        :disabled="nativeBusy || pageOffset + pageSize >= totalCount"
        @click="nextPage"
      >
        Вперед →
      </button>
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
        :disabled="nativeBusy || p === currentPage"
        @click="goToPage(p)"
      >
        {{ p }}
      </button>
      <span class="subtitle">Страница {{ currentPage }} из {{ totalPages }}</span>
    </div>

    <NativeAdjustments :busy="nativeBusy" @status="nativeStatus = $event" />
  </section>
</template>
