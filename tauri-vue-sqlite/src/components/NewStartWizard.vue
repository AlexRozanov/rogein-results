<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import FilePickerButton from "./FilePickerButton.vue";
import NativeSettingsForm from "./NativeSettingsForm.vue";

type SportKind = "rogaine" | "orient";

type NativeSettings = {
  control_minutes: number;
  penalty_per_minute: number;
  dq_minutes: number;
  finish_cp: number;
  start_mode: "station" | "time";
  start_cp: number | null;
  competition_date: string;
  competition_start_time: string;
  sport_kind: SportKind;
};

type StepId = "sport" | "start" | "legends" | "settings" | "courses" | "finish";

type WizardStep = { id: StepId; title: string; hint: string };

const props = defineProps<{
  busy: boolean;
}>();

const emit = defineEmits<{
  status: [message: string];
  finished: [];
  dismissed: [];
}>();

const stepIndex = ref(0);
const localBusy = ref(false);
const settings = ref<NativeSettings | null>(null);
const startFile = ref<File | null>(null);
const legendsFile = ref<File | null>(null);
const finishFile = ref<File | null>(null);
const coursesFile = ref<File | null>(null);
const sportKind = ref<SportKind>("rogaine");
const stepDone = ref<Record<StepId, boolean>>({
  sport: false,
  start: false,
  legends: false,
  settings: false,
  courses: false,
  finish: false,
});

const isBusy = computed(() => props.busy || localBusy.value);
const isOrient = computed(() => sportKind.value === "orient");
const steps = computed<WizardStep[]>(() => {
  const sport: WizardStep = {
    id: "sport",
    title: "Вид соревнования",
    hint: "Рогейн считает очки за КП. Заданное направление — порядок КП и время.",
  };
  const finish: WizardStep = {
    id: "finish",
    title: "Финишный протокол",
    hint: "Выберите CSV финишного дампа или пропустите и загрузите позже во вкладке «Результаты».",
  };
  const settingsStep: WizardStep = {
    id: "settings",
    title: "Общие настройки",
    hint: "Проверьте параметры соревнования. Их можно изменить позже во вкладке «Настройки».",
  };
  if (isOrient.value) {
    return [
      sport,
      settingsStep,
      {
        id: "courses",
        title: "Дистанции",
        hint: "Выберите CSV дистанций (название и номера КП) или пропустите и загрузите позже во вкладке «Дистанции».",
      },
      finish,
    ];
  }
  return [
    sport,
    {
      id: "start",
      title: "Стартовый протокол",
      hint: "Выберите CSV со стартовым протоколом или пропустите шаг.",
    },
    {
      id: "legends",
      title: "Легенды КП",
      hint: "Выберите CSV с легендами контрольных пунктов или пропустите шаг.",
    },
    settingsStep,
    finish,
  ];
});
const current = computed(() => steps.value[stepIndex.value]);
const isLast = computed(() => stepIndex.value >= steps.value.length - 1);

onMounted(() => {
  void loadSettings();
});

async function loadSettings() {
  try {
    settings.value = await invoke<NativeSettings>("get_settings");
    sportKind.value = settings.value.sport_kind === "orient" ? "orient" : "rogaine";
  } catch (error) {
    emit("status", `Ошибка загрузки настроек: ${String(error)}`);
  }
}

function goNext() {
  if (isLast.value) {
    emit("finished");
    return;
  }
  stepIndex.value += 1;
}

function skipStep() {
  goNext();
}

function skipWizard() {
  emit("dismissed");
}

async function chooseSport(kind: SportKind) {
  localBusy.value = true;
  try {
    settings.value = await invoke<NativeSettings>("set_settings", {
      sportKind: kind,
    });
    sportKind.value = kind;
    stepDone.value.sport = true;
    emit(
      "status",
      kind === "orient"
        ? "Выбрано заданное направление."
        : "Выбран рогейн.",
    );
    if (current.value?.id === "sport") {
      goNext();
    }
  } catch (error) {
    emit("status", `Ошибка выбора вида соревнования: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function importStart() {
  if (!startFile.value) {
    emit("status", "Выберите CSV стартового протокола.");
    return;
  }
  localBusy.value = true;
  try {
    const csvContent = await startFile.value.text();
    const summary = await invoke<{ imported_rows: number; total_rows: number }>(
      "import_start_protocol_content",
      { csvContent, reset: true },
    );
    stepDone.value.start = true;
    emit(
      "status",
      `Стартовый протокол загружен: ${summary.imported_rows} из файла, всего ${summary.total_rows}.`,
    );
    goNext();
  } catch (error) {
    emit("status", `Ошибка импорта стартового протокола: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function importLegends() {
  if (!legendsFile.value) {
    emit("status", "Выберите CSV с легендами КП.");
    return;
  }
  localBusy.value = true;
  try {
    const csvContent = await legendsFile.value.text();
    const summary = await invoke<{ imported_rows: number; total_rows: number }>(
      "import_cp_legends_content",
      { csvContent, reset: true },
    );
    stepDone.value.legends = true;
    emit(
      "status",
      `Легенды КП загружены: ${summary.imported_rows} из файла, всего ${summary.total_rows}.`,
    );
    goNext();
  } catch (error) {
    emit("status", `Ошибка импорта легенд КП: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function importCourses() {
  if (!coursesFile.value) {
    emit("status", "Выберите CSV с дистанциями.");
    return;
  }
  localBusy.value = true;
  try {
    const csvContent = await coursesFile.value.text();
    const summary = await invoke<{ imported_rows: number; total_rows: number }>(
      "import_courses_content",
      { csvContent, reset: true },
    );
    stepDone.value.courses = true;
    emit(
      "status",
      `Дистанции загружены: ${summary.imported_rows}, всего ${summary.total_rows}.`,
    );
    goNext();
  } catch (error) {
    emit("status", `Ошибка импорта дистанций: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function saveSettings(form: NativeSettings) {
  localBusy.value = true;
  try {
    settings.value = await invoke<NativeSettings>("set_settings", {
      controlMinutes: Number(form.control_minutes),
      penaltyPerMinute: Number(form.penalty_per_minute),
      dqMinutes: Number(form.dq_minutes),
      finishCp: Number(form.finish_cp),
      startMode: form.start_mode,
      startCp: form.start_cp,
      competitionDate: String(form.competition_date || "").trim(),
      competitionStartTime: String(form.competition_start_time || "").trim(),
      sportKind: sportKind.value,
    });
    stepDone.value.settings = true;
    emit("status", "Общие настройки сохранены.");
    goNext();
  } catch (error) {
    emit("status", `Ошибка сохранения настроек: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}

async function importFinish() {
  if (!finishFile.value) {
    emit("status", "Выберите CSV финишного протокола.");
    return;
  }
  localBusy.value = true;
  try {
    const csvContent = await finishFile.value.text();
    const summary = await invoke<{ participants_count: number; results_count: number }>(
      "import_csv_content",
      { csvContent, reset: true },
    );
    stepDone.value.finish = true;
    emit(
      "status",
      `Финишный протокол загружен: участников ${summary.participants_count}, результатов ${summary.results_count}.`,
    );
    emit("finished");
  } catch (error) {
    emit("status", `Ошибка импорта финишного протокола: ${String(error)}`);
  } finally {
    localBusy.value = false;
  }
}
</script>

<template>
  <div class="wizard-backdrop">
    <div class="wizard-card" role="dialog" aria-modal="true" aria-label="Мастер нового старта">
      <div class="modal-header">
        <h3>Новый старт</h3>
        <button
          class="modal-close-btn"
          type="button"
          title="Закрыть и работать в основном интерфейсе"
          aria-label="Закрыть"
          :disabled="isBusy"
          @click="skipWizard"
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
        Данных для текущего старта пока нет. Можно пройти шаги по порядку или пропустить
        любой из них и сделать это позже в основном интерфейсе.
      </p>

      <ol class="wizard-steps">
        <li
          v-for="(step, index) in steps"
          :key="step.id"
          :class="{
            active: index === stepIndex,
            done: stepDone[step.id] || index < stepIndex,
          }"
        >
          <span class="wizard-step-num">{{ index + 1 }}</span>
          <span>{{ step.title }}</span>
        </li>
      </ol>

      <div class="wizard-body">
        <h4>{{ current.title }}</h4>
        <p class="subtitle">{{ current.hint }}</p>

        <template v-if="current.id === 'sport'">
          <div class="sport-kind-row">
            <button
              type="button"
              class="sport-kind-btn"
              :class="{ active: sportKind === 'rogaine' }"
              :disabled="isBusy"
              @click="chooseSport('rogaine')"
            >
              Рогейн
            </button>
            <button
              type="button"
              class="sport-kind-btn"
              :class="{ active: sportKind === 'orient' }"
              :disabled="isBusy"
              @click="chooseSport('orient')"
            >
              Заданное направление
            </button>
          </div>
        </template>

        <template v-else-if="current.id === 'start'">
          <FilePickerButton
            button-label="Выбрать стартовый протокол"
            empty-label="Файл не выбран"
            :disabled="isBusy"
            @file-selected="startFile = $event"
          />
          <div class="native-row" style="margin-top: 12px">
            <button type="button" :disabled="isBusy" @click="skipStep">Пропустить</button>
            <button type="button" :disabled="isBusy || !startFile" @click="importStart">
              Загрузить и далее
            </button>
          </div>
        </template>

        <template v-else-if="current.id === 'legends'">
          <FilePickerButton
            button-label="Выбрать легенды КП"
            empty-label="Файл не выбран"
            :disabled="isBusy"
            @file-selected="legendsFile = $event"
          />
          <div class="native-row" style="margin-top: 12px">
            <button type="button" :disabled="isBusy" @click="skipStep">Пропустить</button>
            <button type="button" :disabled="isBusy || !legendsFile" @click="importLegends">
              Загрузить и далее
            </button>
          </div>
        </template>

        <template v-else-if="current.id === 'settings'">
          <p class="subtitle">Нажмите «Сохранить настройки», чтобы записать значения и перейти дальше.</p>
          <NativeSettingsForm
            :settings="settings"
            :busy="isBusy"
            @save="saveSettings"
            @request-sport-kind="chooseSport"
          />
          <div class="native-row" style="margin-top: 12px">
            <button type="button" :disabled="isBusy" @click="skipStep">Пропустить</button>
          </div>
        </template>

        <template v-else-if="current.id === 'courses'">
          <FilePickerButton
            button-label="Выбрать CSV дистанций"
            empty-label="Файл не выбран"
            :disabled="isBusy"
            @file-selected="coursesFile = $event"
          />
          <div class="native-row" style="margin-top: 12px">
            <button type="button" :disabled="isBusy" @click="skipStep">Пропустить</button>
            <button type="button" :disabled="isBusy || !coursesFile" @click="importCourses">
              Загрузить и далее
            </button>
          </div>
        </template>

        <template v-else>
          <FilePickerButton
            button-label="Выбрать финишный протокол"
            empty-label="Файл не выбран"
            :disabled="isBusy"
            @file-selected="finishFile = $event"
          />
          <div class="native-row" style="margin-top: 12px">
            <button type="button" :disabled="isBusy" @click="skipStep">Пропустить</button>
            <button type="button" :disabled="isBusy || !finishFile" @click="importFinish">
              Загрузить и открыть
            </button>
          </div>
        </template>
      </div>

      <div class="native-row wizard-footer">
        <button type="button" :disabled="isBusy" @click="skipWizard">
          Закрыть визард
        </button>
      </div>
    </div>
  </div>
</template>
