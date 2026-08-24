<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import IconActionButton from "./IconActionButton.vue";
import CollapsiblePanel from "./CollapsiblePanel.vue";

type CorrectionRow = {
  id: number;
  finish_participant_id: number | null;
  scope: "global" | "personal";
  source_table: "manual_corrections" | "legacy_corrections";
  correction_type: string;
  payload: Record<string, unknown>;
  created_at: string;
};

type CourseRow = {
  id: number;
  name: string;
  controls: number[];
};

const props = defineProps<{
  busy: boolean;
}>();

const emit = defineEmits<{
  status: [message: string];
  saved: [];
}>();

const localBusy = ref(false);
const selectedCp = ref<number | null>(null);
const courseCps = ref<number[]>([]);
const removed = ref<CorrectionRow[]>([]);

const isBusy = computed(() => props.busy || localBusy.value);

const removedNumbers = computed(() => {
  const out = new Set<number>();
  for (const row of removed.value) {
    const cp = Number(row.payload?.cp_number);
    if (Number.isFinite(cp)) out.add(cp);
  }
  return out;
});

const availableCps = computed(() =>
  courseCps.value.filter((cp) => !removedNumbers.value.has(cp)),
);

onMounted(() => {
  void refresh();
});

async function refresh() {
  try {
    const [courses, corrections] = await Promise.all([
      invoke<CourseRow[]>("get_courses"),
      invoke<CorrectionRow[]>("get_manual_corrections", { participantId: null }),
    ]);
    const unique = new Set<number>();
    for (const course of courses) {
      for (const cp of course.controls) unique.add(cp);
    }
    courseCps.value = [...unique].sort((a, b) => a - b);
    removed.value = corrections.filter((row) => row.correction_type === "remove_cp");
    if (selectedCp.value != null && !availableCps.value.includes(selectedCp.value)) {
      selectedCp.value = null;
    }
  } catch (error) {
    emit("status", `Ошибка загрузки снятых КП: ${String(error)}`);
  }
}

async function addRemovedCp() {
  if (selectedCp.value == null) {
    emit("status", "Выберите КП, который есть на дистанции.");
    return;
  }
  if (!availableCps.value.includes(selectedCp.value)) {
    emit("status", "Снять можно только КП, который есть на дистанции.");
    return;
  }
  localBusy.value = true;
  try {
    await invoke("remove_cp_correction", {
      participantId: null,
      cpNumber: selectedCp.value,
      removeMode: "from_course",
      participantScope: "all",
    });
    selectedCp.value = null;
    await refresh();
    emit("status", "КП снят с дистанций для всех участников, выполнен пересчёт.");
    emit("saved");
  } catch (error) {
    emit("status", `Ошибка снятия КП: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function undoRemovedCp(row: CorrectionRow) {
  localBusy.value = true;
  try {
    await invoke("delete_correction_entry", {
      sourceTable: row.source_table,
      correctionId: row.id,
    });
    await refresh();
    emit("status", `КП снова на дистанции, выполнен пересчёт.`);
    emit("saved");
  } catch (error) {
    emit("status", `Ошибка отмены: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

function cpText(row: CorrectionRow) {
  const cp = Number(row.payload?.cp_number);
  return Number.isFinite(cp) ? String(cp) : "—";
}

defineExpose({ refresh });
</script>

<template>
  <CollapsiblePanel panel-id="orient-removed-cps" title="Снятые КП">
    <p class="subtitle">
      Если станция не срабатывала или КП поставили неправильно — снимите его здесь.
      Для всех участников этот КП считается несуществующим: его не нужно брать,
      лишняя отметка не мешает.
    </p>
    <div class="native-settings">
      <label>
        КП на дистанциях
        <select
          :value="selectedCp == null ? '' : String(selectedCp)"
          :disabled="isBusy || !availableCps.length"
          @change="
            selectedCp = ($event.target as HTMLSelectElement).value
              ? Number(($event.target as HTMLSelectElement).value)
              : null
          "
        >
          <option value="">Выберите КП</option>
          <option v-for="cp in availableCps" :key="cp" :value="String(cp)">
            {{ cp }}
          </option>
        </select>
      </label>
      <button type="button" :disabled="isBusy || selectedCp == null" @click="addRemovedCp">
        Снять КП
      </button>
    </div>
    <p v-if="!courseCps.length" class="subtitle">Сначала загрузите дистанции.</p>
    <p v-else-if="!availableCps.length && removed.length" class="subtitle">
      Все КП с дистанций уже сняты.
    </p>

    <div class="native-results-wrap">
      <table class="native-results">
        <thead>
          <tr>
            <th>КП</th>
            <th>Создано</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!removed.length">
            <td colspan="3">Снятых КП нет</td>
          </tr>
          <tr v-for="row in removed" :key="`${row.source_table}-${row.id}`">
            <td>{{ cpText(row) }}</td>
            <td>{{ row.created_at }}</td>
            <td>
              <IconActionButton
                variant="undo"
                label="Вернуть КП"
                :disabled="isBusy"
                @click="undoRemovedCp(row)"
              />
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </CollapsiblePanel>
</template>
