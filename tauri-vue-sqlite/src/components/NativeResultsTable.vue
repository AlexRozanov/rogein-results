<script setup lang="ts">
type NativeResultRow = {
  participant_id: string;
  name: string;
  status: "OK" | "DQ" | "ERR";
  format_id: number | null;
  format_name: string;
  has_personal_corrections: boolean;
  has_anomalies: boolean;
  anomaly_count: number;
  points_raw: number;
  penalty_points: number;
  points_final: number;
  elapsed_seconds: number;
};

type SortBy = "participant_id" | "name" | "points_raw" | "points_final" | "elapsed_seconds";
type SortDir = "asc" | "desc";

const props = defineProps<{
  rows: NativeResultRow[];
  sortBy: SortBy;
  sortDir: SortDir;
}>();
const emit = defineEmits<{
  participantSelected: [participantId: string];
  sortChanged: [sortBy: SortBy];
}>();

function fmtHms(totalSeconds: number) {
  const s = Math.max(0, totalSeconds | 0);
  const hh = String(Math.floor(s / 3600)).padStart(2, "0");
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, "0");
  const ss = String(s % 60).padStart(2, "0");
  return `${hh}:${mm}:${ss}`;
}

function sortMark(column: SortBy) {
  if (props.sortBy !== column) return "";
  return props.sortDir === "asc" ? " ▲" : " ▼";
}
</script>

<template>
  <div class="native-results-wrap">
    <table class="native-results">
      <thead>
        <tr>
          <th class="sortable-col" @click="emit('sortChanged', 'participant_id')">ID{{ sortMark("participant_id") }}</th>
          <th class="sortable-col" @click="emit('sortChanged', 'name')">Имя{{ sortMark("name") }}</th>
          <th>Формат</th>
          <th>Статус</th>
          <th class="sortable-col" @click="emit('sortChanged', 'points_raw')">Очки{{ sortMark("points_raw") }}</th>
          <th>Штраф</th>
          <th class="sortable-col" @click="emit('sortChanged', 'points_final')">Итог{{ sortMark("points_final") }}</th>
          <th class="sortable-col" @click="emit('sortChanged', 'elapsed_seconds')">Время{{ sortMark("elapsed_seconds") }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.participant_id" :class="{ 'row-has-anomaly': row.has_anomalies }">
          <td>
            <button
              class="participant-id-btn"
              type="button"
              @click="emit('participantSelected', row.participant_id)"
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
          <td>{{ row.name }}</td>
          <td>{{ row.format_name || "—" }}</td>
          <td>{{ row.status }}</td>
          <td>{{ row.points_raw }}</td>
          <td>{{ row.penalty_points }}</td>
          <td>{{ row.points_final }}</td>
          <td>{{ fmtHms(row.elapsed_seconds) }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
