<script setup lang="ts">
import { computed, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { closeAuxiliaryWindowOrClearHash } from "../workspaceUiState";

export type CpRemapSuggestion = {
  finish_participant_id: number;
  participant_id: string;
  name: string;
  result_id: number | null;
  mark_seq: number;
  mark_time: string;
  from_cp: number;
  to_cp: number;
  suggested_cp: number;
  action: "remap" | "keep" | string;
  confidence: "high" | "medium" | "low" | string;
  cost_keep: number;
  cost_remap: number;
  reason: string;
  already_applied: boolean;
  prev_cp: number | null;
  next_cp: number | null;
  context_cps: number[];
};

type CpRemapAnalyzeSummary = {
  from_cp: number;
  to_cp: number;
  participants_with_from_cp: number;
  suggestions: CpRemapSuggestion[];
  remap_suggested: number;
  keep_suggested: number;
  high_confidence_remaps: number;
  missing_map_positions: boolean;
};

type CpRemapApplySummary = {
  inserted: number;
  skipped_existing: number;
  failed: number;
};

const status = ref("Укажите КП и нажмите «Анализировать».");
const localBusy = ref(false);
const fromCp = ref(57);
const toCp = ref(120);
const summary = ref<CpRemapAnalyzeSummary | null>(null);
const selected = ref<Record<string, boolean>>({});
const filterAction = ref<"all" | "remap" | "keep">("remap");
const filterConfidence = ref<"all" | "high" | "medium" | "low">("all");
const focusedKey = ref<string | null>(null);

const suggestionKey = (s: CpRemapSuggestion) =>
  `${s.finish_participant_id}:${s.mark_seq}:${s.mark_time}`;

const filteredSuggestions = computed(() => {
  const rows = summary.value?.suggestions || [];
  return rows.filter((s) => {
    if (filterAction.value !== "all" && s.action !== filterAction.value) return false;
    if (filterConfidence.value !== "all" && s.confidence !== filterConfidence.value) {
      return false;
    }
    return true;
  });
});

const selectedRemapItems = computed(() => {
  const rows = summary.value?.suggestions || [];
  return rows.filter(
    (s) =>
      s.action === "remap" &&
      !s.already_applied &&
      selected.value[suggestionKey(s)],
  );
});

const focusedSuggestion = computed(() => {
  const key = focusedKey.value;
  if (!key) return null;
  return (summary.value?.suggestions || []).find((s) => suggestionKey(s) === key) || null;
});

function confidenceLabel(c: string) {
  if (c === "high") return "высокая";
  if (c === "medium") return "средняя";
  if (c === "low") return "низкая";
  return c;
}

function contextText(s: CpRemapSuggestion) {
  if (!s.context_cps?.length) {
    return `${s.prev_cp ?? "—"} → [${s.from_cp}] → ${s.next_cp ?? "—"}`;
  }
  return s.context_cps
    .map((cp) => (cp === s.from_cp ? `[${cp}]` : String(cp)))
    .join(" → ");
}

async function closeWindow() {
  await closeAuxiliaryWindowOrClearHash(["cp-remap"]);
}

function focusRow(s: CpRemapSuggestion) {
  focusedKey.value = suggestionKey(s);
}

async function reviewOnMap(s: CpRemapSuggestion) {
  focusRow(s);
  await openPath(s);
}

function toggleRow(s: CpRemapSuggestion, value?: boolean) {
  if (s.action !== "remap" || s.already_applied) return;
  const key = suggestionKey(s);
  selected.value = {
    ...selected.value,
    [key]: value == null ? !selected.value[key] : value,
  };
}

function selectHighConfidenceRemaps() {
  const next: Record<string, boolean> = { ...selected.value };
  for (const s of summary.value?.suggestions || []) {
    if (s.action === "remap" && !s.already_applied && s.confidence === "high") {
      next[suggestionKey(s)] = true;
    }
  }
  selected.value = next;
}

function clearSelection() {
  selected.value = {};
}

async function openAuxWindow(label: string, title: string, targetHash: string) {
  try {
    await invoke("open_aux_window", {
      label,
      title,
      url: targetHash,
      width: 1280,
      height: 900,
    });
    status.value = `${title} открыто рядом. Окно путаницы КП остаётся открытым.`;
  } catch (error) {
    status.value = `Ошибка открытия окна: ${String(error)}`;
  }
}

async function openPath(s: CpRemapSuggestion) {
  if (s.result_id == null || s.result_id <= 0) {
    status.value = "Нет result_id для открытия пути.";
    return;
  }
  focusRow(s);
  await openAuxWindow(
    "course-path",
    `Путь — ${s.participant_id} ${s.name}`,
    `#course-path/${s.result_id}`,
  );
}

async function analyze() {
  localBusy.value = true;
  try {
    const result = await invoke<CpRemapAnalyzeSummary>("analyze_cp_station_remap", {
      fromCp: Number(fromCp.value),
      toCp: Number(toCp.value),
    });
    summary.value = result;
    selected.value = {};
    focusedKey.value = null;
    for (const s of result.suggestions) {
      if (s.action === "remap" && !s.already_applied && s.confidence === "high") {
        selected.value[suggestionKey(s)] = true;
      }
    }
    status.value =
      `Анализ ${result.from_cp}→${result.to_cp}: участников ${result.participants_with_from_cp}, предложено заменить ${result.remap_suggested} (высокая уверенность: ${result.high_confidence_remaps}).`;
  } catch (error) {
    status.value = `Ошибка анализа путаницы КП: ${String(error)}`;
  } finally {
    localBusy.value = false;
  }
}

async function applyOne(s: CpRemapSuggestion) {
  if (s.action !== "remap" || s.already_applied) {
    status.value = "Для этой строки замена не требуется.";
    return;
  }
  focusRow(s);
  localBusy.value = true;
  try {
    const result = await invoke<CpRemapApplySummary>("apply_cp_remap_corrections", {
      items: [
        {
          finishParticipantId: s.finish_participant_id,
          fromCp: s.from_cp,
          toCp: s.to_cp,
          markTime: s.mark_time,
          seq: s.mark_seq,
        },
      ],
    });
    await invoke("recalculate_results");
    status.value =
      `${s.participant_id} ${s.name}: ${s.from_cp}→${s.to_cp} — добавлено ${result.inserted}, уже было ${result.skipped_existing}, ошибок ${result.failed}.`;
    await analyze();
  } catch (error) {
    status.value = `Ошибка применения замены: ${String(error)}`;
  } finally {
    localBusy.value = false;
  }
}

async function applySelected() {
  const items = selectedRemapItems.value.map((s) => ({
    finishParticipantId: s.finish_participant_id,
    fromCp: s.from_cp,
    toCp: s.to_cp,
    markTime: s.mark_time,
    seq: s.mark_seq,
  }));
  if (!items.length) {
    status.value = "Нет выбранных замен для применения.";
    return;
  }
  localBusy.value = true;
  try {
    const result = await invoke<CpRemapApplySummary>("apply_cp_remap_corrections", {
      items,
    });
    await invoke("recalculate_results");
    status.value =
      `Замены применены: добавлено ${result.inserted}, уже было ${result.skipped_existing}, ошибок ${result.failed}.`;
    await analyze();
  } catch (error) {
    status.value = `Ошибка применения замен: ${String(error)}`;
  } finally {
    localBusy.value = false;
  }
}
</script>

<template>
  <main class="container">
    <section class="native-tools">
      <div class="native-row">
        <button type="button" :disabled="localBusy" @click="closeWindow">Закрыть</button>
        <button type="button" :disabled="localBusy" @click="analyze">Анализировать</button>
      </div>
      <h2>Путаница станций КП</h2>
      <p class="status">{{ status }}</p>
      <p class="subtitle">
        Клик по строке открывает путь на карте. Кнопка «Применить» вносит замену только для этой отметки.
      </p>
    </section>

    <section class="native-tools nested-card">
      <div class="native-settings">
        <label>
          Исходный КП (в чипе)
          <input v-model.number="fromCp" type="number" min="1" step="1" />
        </label>
        <label>
          Целевой КП (реальный)
          <input v-model.number="toCp" type="number" min="1" step="1" />
        </label>
        <button :disabled="localBusy" @click="analyze">Анализировать</button>
      </div>

      <p v-if="summary?.missing_map_positions" class="status" style="color: #b91c1c">
        На карте нет координат КП {{ summary.from_cp }} и/или {{ summary.to_cp }}.
        Сначала разметьте оба КП на карте.
      </p>

      <template v-if="summary">
        <p class="subtitle">
          Участников с КП {{ summary.from_cp }}: {{ summary.participants_with_from_cp }}.
          Предложить заменить: {{ summary.remap_suggested }}
          (высокая уверенность: {{ summary.high_confidence_remaps }}),
          оставить: {{ summary.keep_suggested }}.
        </p>

        <div class="native-row" style="flex-wrap: wrap">
          <label>
            Действие
            <select v-model="filterAction">
              <option value="all">Все</option>
              <option value="remap">Только заменить</option>
              <option value="keep">Только оставить</option>
            </select>
          </label>
          <label>
            Уверенность
            <select v-model="filterConfidence">
              <option value="all">Любая</option>
              <option value="high">Высокая</option>
              <option value="medium">Средняя</option>
              <option value="low">Низкая</option>
            </select>
          </label>
          <button :disabled="localBusy" @click="selectHighConfidenceRemaps">
            Выбрать высокоуверенные замены
          </button>
          <button :disabled="localBusy" @click="clearSelection">Снять выбор</button>
          <button
            :disabled="localBusy || !selectedRemapItems.length"
            @click="applySelected"
          >
            Применить выбранные ({{ selectedRemapItems.length }})
          </button>
        </div>

        <p v-if="focusedSuggestion" class="remap-focus-bar">
          Выбрано:
          {{ focusedSuggestion.participant_id }}
          {{ focusedSuggestion.name }}
          ·
          {{ contextText(focusedSuggestion) }}
        </p>

        <div class="native-results-wrap" style="margin-top: 10px; max-height: calc(100vh - 340px)">
          <table class="native-results">
            <thead>
              <tr>
                <th></th>
                <th>ID</th>
                <th>Имя</th>
                <th>Порядок КП</th>
                <th>Время</th>
                <th>Предложение</th>
                <th>Увер.</th>
                <th>Почему</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!filteredSuggestions.length">
                <td colspan="9">Нет строк по фильтру</td>
              </tr>
              <tr
                v-for="s in filteredSuggestions"
                :key="suggestionKey(s)"
                :class="{
                  'row-remap-high': s.action === 'remap' && s.confidence === 'high',
                  'row-remap-mid': s.action === 'remap' && s.confidence !== 'high',
                  'row-remap-focus': focusedKey === suggestionKey(s),
                }"
                @click="reviewOnMap(s)"
              >
                <td @click.stop>
                  <input
                    v-if="s.action === 'remap' && !s.already_applied"
                    type="checkbox"
                    :checked="!!selected[suggestionKey(s)]"
                    @change="
                      toggleRow(
                        s,
                        ($event.target as HTMLInputElement).checked,
                      )
                    "
                  />
                  <span v-else-if="s.already_applied">✓</span>
                </td>
                <td>{{ s.participant_id }}</td>
                <td>{{ s.name }}</td>
                <td class="correction-text-cell remap-context">{{ contextText(s) }}</td>
                <td>{{ s.mark_time }}</td>
                <td>
                  <template v-if="s.action === 'remap'">
                    {{ s.from_cp }} → {{ s.to_cp }}
                  </template>
                  <template v-else> оставить {{ s.from_cp }} </template>
                </td>
                <td>{{ confidenceLabel(s.confidence) }}</td>
                <td class="correction-text-cell">{{ s.reason }}</td>
                <td @click.stop>
                  <button
                    v-if="s.action === 'remap' && !s.already_applied"
                    type="button"
                    :disabled="localBusy"
                    title="Применить замену для этой отметки"
                    @click="applyOne(s)"
                  >
                    Применить
                  </button>
                  <span v-else-if="s.already_applied" class="subtitle">сделано</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </section>
  </main>
</template>
