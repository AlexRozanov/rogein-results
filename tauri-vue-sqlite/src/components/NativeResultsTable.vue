<script setup lang="ts">
import { ref } from "vue";
import AddToStartProtocolDialog, {
  type AddToStartProtocolDraft,
} from "./AddToStartProtocolDialog.vue";

type NativeResultRow = {
  id: number;
  finish_participant_id?: number | null;
  chip_raw_id?: string | null;
  participant_id: string;
  name: string;
  status: "OK" | "Дисквалификация" | "Ошибка" | "Не стартовал" | "Нет в протоколе";
  format_id: number | null;
  format_name: string;
  team_id?: number | null;
  team_size?: number;
  teammates?: string;
  gender?: string | null;
  age?: number | null;
  has_personal_corrections: boolean;
  has_anomalies: boolean;
  anomaly_count: number;
  points_raw: number;
  penalty_points: number;
  points_final: number;
  elapsed_seconds: number;
  diagnostics_json?: string;
};

type SortBy = "participant_id" | "name" | "points_raw" | "points_final" | "elapsed_seconds";
type SortDir = "asc" | "desc";
type TeamGroupPos = "none" | "start" | "mid" | "end" | "only";

const props = defineProps<{
  rows: NativeResultRow[];
  sortBy: SortBy;
  sortDir: SortDir;
  busy?: boolean;
}>();
const emit = defineEmits<{
  resultSelected: [resultId: number];
  sortChanged: [sortBy: SortBy];
  status: [message: string];
  protocolUpdated: [];
}>();

const dialogOpen = ref(false);
const dialogDraft = ref<AddToStartProtocolDraft | null>(null);

function fmtHms(totalSeconds: number) {
  const s = Math.max(0, totalSeconds | 0);
  const hh = String(Math.floor(s / 3600)).padStart(2, "0");
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, "0");
  const ss = String(s % 60).padStart(2, "0");
  return `${hh}:${mm}:${ss}`;
}

function genderText(value: string | null | undefined) {
  const raw = String(value || "").trim();
  return raw || "—";
}

function ageText(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value) || value < 0) return "—";
  return String(value);
}

function sortMark(column: SortBy) {
  if (props.sortBy !== column) return "";
  return props.sortDir === "asc" ? " ▲" : " ▼";
}

function notInStartProtocol(row: NativeResultRow) {
  if (row.status === "Нет в протоколе") return true;
  const diag = String(row.diagnostics_json || "");
  return diag.includes("not_in_start_protocol");
}

function teamGroupPos(row: NativeResultRow, index: number): TeamGroupPos {
  if (row.team_id == null || row.team_id <= 0) return "none";
  const prev = props.rows[index - 1];
  const next = props.rows[index + 1];
  const samePrev = prev != null && prev.team_id === row.team_id;
  const sameNext = next != null && next.team_id === row.team_id;
  if (!samePrev && !sameNext) return "only";
  if (!samePrev && sameNext) return "start";
  if (samePrev && sameNext) return "mid";
  return "end";
}

function isTeamBlockStart(row: NativeResultRow, index: number) {
  const pos = teamGroupPos(row, index);
  return pos === "start" || pos === "only";
}

function openAddToProtocol(row: NativeResultRow) {
  dialogDraft.value = {
    participant_id: row.participant_id,
    name: row.name,
    format_id: row.format_id,
  };
  dialogOpen.value = true;
}

function closeDialog() {
  dialogOpen.value = false;
  dialogDraft.value = null;
}

function onSaved() {
  emit("protocolUpdated");
}
</script>

<template>
  <div class="native-results-wrap">
    <table class="native-results">
      <thead>
        <tr>
          <th class="team-brace-col" title="Группировка команды"></th>
          <th class="sortable-col" @click="emit('sortChanged', 'participant_id')">ID{{ sortMark("participant_id") }}</th>
          <th class="sortable-col" @click="emit('sortChanged', 'name')">Имя{{ sortMark("name") }}</th>
          <th>Формат</th>
          <th>Пол</th>
          <th>Возраст</th>
          <th>Статус</th>
          <th class="sortable-col" @click="emit('sortChanged', 'points_raw')">Очки{{ sortMark("points_raw") }}</th>
          <th>Штраф</th>
          <th class="sortable-col" @click="emit('sortChanged', 'points_final')">Итог{{ sortMark("points_final") }}</th>
          <th class="sortable-col" @click="emit('sortChanged', 'elapsed_seconds')">Время{{ sortMark("elapsed_seconds") }}</th>
          <th>Действие</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="(row, rowIndex) in rows"
          :key="row.id"
          :class="{
            'row-has-anomaly': row.has_anomalies,
            'row-team-block-start': isTeamBlockStart(row, rowIndex),
          }"
        >
          <td class="team-brace-col" aria-hidden="true">
            <span
              v-if="teamGroupPos(row, rowIndex) !== 'none'"
              class="team-brace"
              :class="`team-brace-${teamGroupPos(row, rowIndex)}`"
            >
              <span class="team-brace-rail"></span>
            </span>
          </td>
          <td>
            <button
              class="participant-id-btn"
              type="button"
              @click="emit('resultSelected', row.id)"
            >
              {{ row.participant_id }}
            </button>
            <span
              v-if="row.has_personal_corrections"
              class="participant-correction-badge"
              title="Есть персональные корректировки"
            >
              ПК
            </span>
            <span
              v-if="row.has_anomalies"
              class="participant-anomaly-badge"
              :title="`Найдено аномалий: ${row.anomaly_count}`"
            >
              А{{ row.anomaly_count }}
            </span>
          </td>
          <td>
            <span class="result-name-cell">
              <span>{{ row.name }}</span>
              <span
                v-if="row.team_id != null && row.team_id > 0"
                class="team-badge"
                :title="`Команда #${row.team_id}`"
              >
                Т{{ row.team_id }}
              </span>
            </span>
          </td>
          <td>{{ row.format_name || "—" }}</td>
          <td>{{ genderText(row.gender) }}</td>
          <td>{{ ageText(row.age) }}</td>
          <td>{{ row.status }}</td>
          <td>{{ row.points_raw }}</td>
          <td>{{ row.penalty_points }}</td>
          <td>{{ row.points_final }}</td>
          <td>{{ fmtHms(row.elapsed_seconds) }}</td>
          <td>
            <button
              v-if="notInStartProtocol(row)"
              type="button"
              :disabled="busy"
              @click="openAddToProtocol(row)"
            >
              Добавить в протокол
            </button>
            <span v-else>—</span>
          </td>
        </tr>
      </tbody>
    </table>

    <AddToStartProtocolDialog
      :open="dialogOpen"
      :busy="busy"
      :initial="dialogDraft"
      title="Добавить в протокол"
      @close="closeDialog"
      @saved="onSaved"
      @status="emit('status', $event)"
    />
  </div>
</template>
