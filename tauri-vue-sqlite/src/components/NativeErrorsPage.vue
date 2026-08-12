<script setup lang="ts">
import { onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import {
  closeAuxiliaryWindowOrClearHash,
  markReturnToResultsTab,
  openAuxWebviewWindow,
} from "../workspaceUiState";

type ErrorRow = {
  result_id: number;
  participant_id: string;
  name: string;
  description: string;
  diagnostics_json: string;
};

const rows = ref<ErrorRow[]>([]);
const status = ref("Загрузка ошибок...");
const busy = ref(false);

async function loadErrors() {
  busy.value = true;
  status.value = "Загрузка ошибок...";
  try {
    rows.value = await invoke<ErrorRow[]>("get_error_list");
    status.value =
      rows.value.length === 0
        ? "Ошибок нет."
        : `Найдено ошибок: ${rows.value.length}`;
  } catch (error) {
    rows.value = [];
    status.value = `Ошибка загрузки: ${String(error)}`;
  } finally {
    busy.value = false;
  }
}

async function openResult(resultId: number) {
  const id = Number(resultId);
  if (!Number.isFinite(id) || id <= 0) return;
  const targetHash = `#result/${id}`;
  const opened = await openAuxWebviewWindow({
    label: "participant-card",
    title: `Карточка результата #${id}`,
    width: 1220,
    height: 900,
    url: targetHash,
  });
  if (!opened.ok) {
    window.location.hash = targetHash;
    console.error(opened.error);
  }
}

async function goBack() {
  markReturnToResultsTab();
  await closeAuxiliaryWindowOrClearHash(["errors-list"]);
}

onMounted(() => {
  void loadErrors();
});
</script>

<template>
  <main class="container">
    <section class="native-tools">
      <div class="native-row">
        <button type="button" @click="goBack">← Назад</button>
        <button type="button" :disabled="busy" @click="loadErrors">Обновить</button>
      </div>
      <h2>Ошибки</h2>
      <p class="status">{{ status }}</p>
    </section>

    <section class="native-tools nested-card">
      <div class="native-results-wrap">
        <table class="native-results">
          <thead>
            <tr>
              <th>ID</th>
              <th>Имя</th>
              <th>Описание</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!rows.length">
              <td colspan="4">Список пуст</td>
            </tr>
            <tr v-for="row in rows" :key="row.result_id">
              <td>{{ row.participant_id }}</td>
              <td>{{ row.name }}</td>
              <td class="correction-text-cell">{{ row.description }}</td>
              <td>
                <button type="button" @click="openResult(row.result_id)">Карточка</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </main>
</template>
