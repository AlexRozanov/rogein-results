<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  bulletinPlaceLine,
  emptyCourse,
  coursesHaveScale,
  filledCourses,
  formatDateRu,
  googleMapsUrl,
  hasCoords,
  isHttpUrl,
  liveResultsPath,
  parseContacts,
  snapshotBulletin,
  yandexMapsUrl,
  type Bulletin,
} from "../bulletin";
import BulletinBlockActions from "./BulletinBlockActions.vue";
import BulletinField from "./BulletinField.vue";

const bulletin = defineModel<Bulletin>({ required: true });

const props = defineProps<{
  title: string;
  date: string | null;
  slug?: string | null;
  editable?: boolean;
  preview?: boolean;
  upcomingId?: number | null;
  saving?: boolean;
}>();

const emit = defineEmits<{
  error: [message: string];
  save: [bulletin: Bulletin];
}>();

const uploading = ref("");
const dropTarget = ref<"scheme" | "map" | "">("");
const activeField = ref("");
const editSnapshot = ref<Bulletin | null>(null);
const rootEl = ref<HTMLElement | null>(null);
const schemeMediaEl = ref<HTMLElement | null>(null);
const mapsMediaEl = ref<HTMLElement | null>(null);

const canEdit = computed(() => Boolean(props.editable && !props.preview));

const mediaSlot = computed<"scheme" | "map" | null>(() => {
  if (!canEdit.value) return null;
  if (activeField.value === "scheme") return "scheme";
  if (activeField.value === "maps") return "map";
  return null;
});

watch(
  () => props.preview,
  (preview) => {
    if (preview) {
      activeField.value = "";
      editSnapshot.value = null;
    }
  },
);

watch(activeField, async (field) => {
  if (field !== "scheme" && field !== "maps") return;
  await nextTick();
  const el = field === "scheme" ? schemeMediaEl.value : mapsMediaEl.value;
  el?.focus({ preventScroll: true });
});

function hasText(value: string | undefined): boolean {
  return Boolean(value?.replace(/<[^>]+>/g, "").trim());
}

function isActive(field: string): boolean {
  return canEdit.value && activeField.value === field;
}

function syncBulletin() {
  bulletin.value = snapshotBulletin(bulletin.value);
}

function startEdit(field: string) {
  if (!canEdit.value || props.saving) return;
  if (!activeField.value) {
    editSnapshot.value = snapshotBulletin(bulletin.value);
  }
  activeField.value = field;
}

async function confirmEdit() {
  if (!activeField.value) return;
  activeField.value = "";
  await nextTick();
  syncBulletin();
  editSnapshot.value = null;
  emit("save", snapshotBulletin(bulletin.value));
}

function cancelEdit() {
  if (editSnapshot.value) {
    bulletin.value = snapshotBulletin(editSnapshot.value);
  }
  activeField.value = "";
  editSnapshot.value = null;
}

function fileFromImageBlob(blob: Blob, mimeHint = ""): File {
  const type = blob.type || mimeHint || "image/png";
  const ext =
    type === "image/jpeg" || type === "image/jpg"
      ? "jpg"
      : type === "image/webp"
        ? "webp"
        : type === "image/gif"
          ? "gif"
          : "png";
  return new File([blob], `paste.${ext}`, { type });
}

function imageFromDataTransfer(data: DataTransfer | null): File | null {
  if (!data) return null;

  for (const file of Array.from(data.files || [])) {
    if (file.type.startsWith("image/") || /\.(png|jpe?g|webp|gif)$/i.test(file.name)) {
      return file;
    }
  }

  const items = data.items;
  if (items) {
    for (const item of Array.from(items)) {
      if (item.kind === "file" && item.type.startsWith("image/")) {
        const blob = item.getAsFile();
        if (blob) return fileFromImageBlob(blob, item.type);
      }
    }
    for (const item of Array.from(items)) {
      if (item.kind === "file") {
        const blob = item.getAsFile();
        if (blob && (blob.type.startsWith("image/") || !blob.type)) {
          return fileFromImageBlob(blob, item.type || "image/png");
        }
      }
    }
  }

  return null;
}

function imageFromClipboard(event: ClipboardEvent): File | null {
  return imageFromDataTransfer(event.clipboardData);
}

const registrationUrl = computed(() => {
  const url = bulletin.value.registration_url.trim();
  return isHttpUrl(url) ? url : "";
});

const liveUrl = computed(() => liveResultsPath(props.slug));

const contactItems = computed(() => parseContacts(bulletin.value.contacts));

const placeLine = computed(
  () => bulletinPlaceLine(props.date, bulletin.value) || props.title,
);

const startHoursLine = computed(() => {
  const parts: string[] = [];
  if (bulletin.value.start_from || bulletin.value.start_to) {
    let line = "Старт";
    if (bulletin.value.start_from) line += ` с ${bulletin.value.start_from}`;
    if (bulletin.value.start_to) line += ` до ${bulletin.value.start_to}`;
    parts.push(line);
  }
  if (bulletin.value.finish_until) {
    parts.push(`Финиш до ${bulletin.value.finish_until}`);
  }
  return parts.join(" · ");
});

const mapScaleLine = computed(() => {
  const parts: string[] = [];
  if (bulletin.value.map_scale) parts.push(`масштаб ${bulletin.value.map_scale}`);
  if (bulletin.value.contour) parts.push(`сечение ${bulletin.value.contour}`);
  return parts.join(", ");
});

const courses = computed(() =>
  canEdit.value || isActive("courses")
    ? bulletin.value.courses
    : filledCourses(bulletin.value),
);

const showProgram = computed(
  () =>
    canEdit.value ||
    hasText(bulletin.value.intro) ||
    hasCoords(bulletin.value) ||
    startHoursLine.value ||
    hasText(bulletin.value.location) ||
    hasText(bulletin.value.parking) ||
    bulletin.value.scheme_url,
);

const showTech = computed(
  () =>
    canEdit.value ||
    hasText(bulletin.value.rules) ||
    bulletin.value.time_limit_minutes ||
    bulletin.value.punching ||
    hasText(bulletin.value.stations) ||
    hasText(bulletin.value.course_intro) ||
    filledCourses(bulletin.value).length ||
    hasText(bulletin.value.extra_course_notes) ||
    hasText(bulletin.value.terrain) ||
    hasText(bulletin.value.relief) ||
    hasText(bulletin.value.map_info) ||
    hasText(bulletin.value.bounds) ||
    bulletin.value.emergency_azimuth ||
    hasText(bulletin.value.map_format) ||
    bulletin.value.map_scale ||
    bulletin.value.contour ||
    hasText(bulletin.value.map_notes) ||
    bulletin.value.map_urls.length,
);

const showEntry = computed(
  () =>
    canEdit.value ||
    bulletin.value.entry_kind ||
    hasText(bulletin.value.entry_details) ||
    registrationUrl.value,
);

async function upload(slot: "scheme" | "map", file: File | undefined) {
  if (!file) return;
  if (!props.upcomingId) {
    emit("error", "Сначала сохраните старт, затем можно загрузить картинки");
    return;
  }
  uploading.value = slot;
  emit("error", "");
  try {
    const body = new FormData();
    body.append("file", file);
    const res = await fetch(
      `/api/admin/upcoming/${props.upcomingId}/bulletin-image?slot=${slot}`,
      { method: "POST", credentials: "include", body },
    );
    if (!res.ok) throw new Error(await res.text());
    const data = (await res.json()) as { url: string; bulletin: Bulletin };
    bulletin.value = {
      ...bulletin.value,
      scheme_url: data.bulletin.scheme_url,
      map_urls: data.bulletin.map_urls,
    };
  } catch (e) {
    emit("error", String(e).replace(/^Error:\s*/, ""));
  } finally {
    uploading.value = "";
  }
}

function onScheme(event: Event) {
  const input = event.target as HTMLInputElement;
  void upload("scheme", input.files?.[0]);
  input.value = "";
}

function onMap(event: Event) {
  const input = event.target as HTMLInputElement;
  void upload("map", input.files?.[0]);
  input.value = "";
}

function onMediaPaste(slot: "scheme" | "map", event: ClipboardEvent) {
  if (!canEdit.value || uploading.value) return;
  const file = imageFromClipboard(event);
  if (!file) return;
  event.preventDefault();
  event.stopPropagation();
  void upload(slot, file);
}

function onDocPaste(event: ClipboardEvent) {
  const slot = mediaSlot.value;
  if (!slot || uploading.value) return;
  const file = imageFromClipboard(event);
  if (!file) return;
  event.preventDefault();
  event.stopPropagation();
  void upload(slot, file);
}

function onMediaDragOver(slot: "scheme" | "map", event: DragEvent) {
  if (!canEdit.value || uploading.value) return;
  if (![...Array.from(event.dataTransfer?.types || [])].includes("Files")) return;
  event.preventDefault();
  dropTarget.value = slot;
}

function onMediaDragLeave(slot: "scheme" | "map") {
  if (dropTarget.value === slot) dropTarget.value = "";
}

function onMediaDrop(slot: "scheme" | "map", event: DragEvent) {
  if (!canEdit.value || uploading.value) return;
  event.preventDefault();
  dropTarget.value = "";
  const file = imageFromDataTransfer(event.dataTransfer);
  if (file) void upload(slot, file);
}

function addCourse() {
  bulletin.value.courses = [...bulletin.value.courses, emptyCourse()];
}

function removeCourse(index: number) {
  bulletin.value.courses = bulletin.value.courses.filter((_, i) => i !== index);
}

function removeMap(index: number) {
  bulletin.value.map_urls = bulletin.value.map_urls.filter((_, i) => i !== index);
}

function clearScheme() {
  bulletin.value.scheme_url = "";
}

function onDocPointerDown(event: PointerEvent) {
  if (!activeField.value || !rootEl.value || props.saving) return;
  const target = event.target as HTMLElement | null;
  if (target?.closest?.("[data-bulletin-cancel]")) return;
  if (target && rootEl.value.contains(target)) {
    const active = rootEl.value.querySelector(
      ".bulletin-zone-active, .bulletin-field-active, .bulletin-media-editing",
    );
    if (active?.contains(target)) return;
  }
  void confirmEdit();
}

onMounted(() => {
  document.addEventListener("pointerdown", onDocPointerDown, true);
  document.addEventListener("paste", onDocPaste, true);
});

onBeforeUnmount(() => {
  document.removeEventListener("pointerdown", onDocPointerDown, true);
  document.removeEventListener("paste", onDocPaste, true);
});
</script>

<template>
  <article
    ref="rootEl"
    class="bulletin"
    :class="{ 'bulletin-editable': canEdit, 'bulletin-previewing': preview }"
  >
    <header class="bulletin-hero">
      <div class="bulletin-hero-brand">
        <img
          class="bulletin-logo"
          src="/logo-malachit.png"
          width="72"
          height="72"
          alt="СК Малахит"
        />
        <div class="bulletin-hero-copy">
          <p class="bulletin-eyebrow">Информационный бюллетень</p>

          <div
            v-if="isActive('hero')"
            class="bulletin-zone bulletin-zone-active"
          >
            <label class="bulletin-field-label">
              Тип старта
              <input
                v-model="bulletin.event_kind"
                class="bulletin-inline-input"
                placeholder="Открытая тренировка."
              />
            </label>
            <h1 class="bulletin-title">{{ title || "Название старта" }}</h1>
            <div class="bulletin-hero-meta-edit">
              <input
                v-model="bulletin.district"
                class="bulletin-inline-input"
                placeholder="Округ / район"
              />
              <input
                v-model="bulletin.venue"
                class="bulletin-inline-input"
                placeholder="Место"
              />
              <input
                v-model="bulletin.discipline"
                class="bulletin-inline-input"
                placeholder="Дисциплина"
              />
            </div>
            <BulletinBlockActions :disabled="saving" @confirm="confirmEdit" @cancel="cancelEdit" />
          </div>

          <button
            v-else-if="canEdit"
            type="button"
            class="bulletin-zone bulletin-zone-hit"
            @click="startEdit('hero')"
          >
            <p v-if="bulletin.event_kind" class="bulletin-kicker">{{ bulletin.event_kind }}</p>
            <p v-else class="bulletin-placeholder-inline">Тип старта</p>
            <h1 class="bulletin-title">{{ placeLine }}</h1>
            <div class="bulletin-pills">
              <span v-if="date" class="bulletin-pill">{{ formatDateRu(date) }}</span>
              <span v-if="bulletin.discipline" class="bulletin-pill">{{ bulletin.discipline }}</span>
              <span v-else class="bulletin-pill bulletin-pill-muted">Дисциплина</span>
            </div>
          </button>

          <template v-else>
            <p v-if="bulletin.event_kind" class="bulletin-kicker">{{ bulletin.event_kind }}</p>
            <h1 class="bulletin-title">{{ placeLine }}</h1>
            <div class="bulletin-pills">
              <span v-if="date" class="bulletin-pill">{{ formatDateRu(date) }}</span>
              <span v-if="bulletin.discipline" class="bulletin-pill">{{ bulletin.discipline }}</span>
            </div>
          </template>
        </div>
      </div>

      <div
        v-if="canEdit || registrationUrl || liveUrl"
        class="bulletin-hero-cta"
      >
        <div v-if="isActive('registration')" class="bulletin-zone bulletin-zone-active">
          <label class="bulletin-field-label">
            Ссылка на регистрацию
            <input
              v-model="bulletin.registration_url"
              class="bulletin-inline-input"
              type="url"
              placeholder="https://…"
            />
          </label>
          <BulletinBlockActions :disabled="saving" @confirm="confirmEdit" @cancel="cancelEdit" />
        </div>
        <button
          v-else-if="canEdit"
          type="button"
          class="bulletin-zone bulletin-zone-hit"
          @click="startEdit('registration')"
        >
          <a
            v-if="registrationUrl"
            class="bulletin-register-btn"
            :href="registrationUrl"
            tabindex="-1"
            @click.prevent
          >
            Регистрация
          </a>
          <span v-else class="bulletin-placeholder-inline">Добавить ссылку на регистрацию</span>
        </button>
        <div v-else class="bulletin-hero-actions">
          <a
            v-if="registrationUrl"
            class="bulletin-register-btn"
            :href="registrationUrl"
            target="_blank"
            rel="noreferrer"
          >
            Регистрация
          </a>
          <a
            v-if="liveUrl"
            class="bulletin-live-btn"
            :href="liveUrl"
          >
            Live-результаты
          </a>
        </div>
      </div>
    </header>

    <section v-if="showProgram" class="bulletin-section">
      <h2>Дата, место и программа</h2>

      <div
        v-if="isActive('schedule')"
        class="bulletin-zone bulletin-zone-active"
      >
        <div class="bulletin-facts bulletin-facts-edit">
          <label>
            Старт с
            <input v-model="bulletin.start_from" class="bulletin-inline-input" placeholder="12:00" />
          </label>
          <label>
            Старт до
            <input v-model="bulletin.start_to" class="bulletin-inline-input" placeholder="14:00" />
          </label>
          <label>
            Финиш до
            <input v-model="bulletin.finish_until" class="bulletin-inline-input" placeholder="15:30" />
          </label>
          <label>
            Широта
            <input v-model="bulletin.lat" class="bulletin-inline-input" placeholder="55.784728" />
          </label>
          <label>
            Долгота
            <input v-model="bulletin.lon" class="bulletin-inline-input" placeholder="37.516126" />
          </label>
        </div>
        <BulletinBlockActions :disabled="saving" @confirm="confirmEdit" @cancel="cancelEdit" />
      </div>

      <button
        v-else-if="canEdit"
        type="button"
        class="bulletin-zone bulletin-zone-hit"
        @click="startEdit('schedule')"
      >
        <div v-if="startHoursLine || hasCoords(bulletin)" class="bulletin-facts">
          <div v-if="startHoursLine" class="bulletin-fact">
            <span class="bulletin-fact-label">Время</span>
            <span>{{ startHoursLine }}</span>
          </div>
          <div v-if="hasCoords(bulletin)" class="bulletin-fact">
            <span class="bulletin-fact-label">Координаты</span>
            <span>{{ bulletin.lat }}, {{ bulletin.lon }}</span>
          </div>
        </div>
        <span v-else class="bulletin-placeholder">Время старта и координаты</span>
      </button>

      <div v-else-if="startHoursLine || hasCoords(bulletin)" class="bulletin-facts">
        <div v-if="startHoursLine" class="bulletin-fact">
          <span class="bulletin-fact-label">Время</span>
          <span>{{ startHoursLine }}</span>
        </div>
        <div v-if="hasCoords(bulletin)" class="bulletin-fact">
          <span class="bulletin-fact-label">Координаты</span>
          <span>{{ bulletin.lat }}, {{ bulletin.lon }}</span>
          <span class="bulletin-map-links">
            <a :href="yandexMapsUrl(bulletin.lat, bulletin.lon)" target="_blank" rel="noreferrer">
              Яндекс
            </a>
            <a :href="googleMapsUrl(bulletin.lat, bulletin.lon)" target="_blank" rel="noreferrer">
              Google
            </a>
          </span>
        </div>
      </div>

      <BulletinField
        v-model="bulletin.intro"
        field="intro"
        :active="isActive('intro')"
        :can-edit="canEdit"
        placeholder="Вводный абзац — нажмите, чтобы заполнить"
        :min-height="'4.5rem'"
        :saving="saving"
        @edit="startEdit"
        @confirm="confirmEdit"
        @cancel="cancelEdit"
      />

      <BulletinField
        v-model="bulletin.location"
        field="location"
        label="Место"
        :active="isActive('location')"
        :can-edit="canEdit"
        placeholder="Где старт и как добраться"
        :saving="saving"
        @edit="startEdit"
        @confirm="confirmEdit"
        @cancel="cancelEdit"
      />

      <BulletinField
        v-model="bulletin.parking"
        field="parking"
        label="Парковка"
        :active="isActive('parking')"
        :can-edit="canEdit"
        placeholder="Парковка"
        min-height="4rem"
        :saving="saving"
        @edit="startEdit"
        @confirm="confirmEdit"
        @cancel="cancelEdit"
      />

      <div
        v-if="canEdit || bulletin.scheme_url"
        ref="schemeMediaEl"
        class="bulletin-media"
        :class="{
          'bulletin-media-active': dropTarget === 'scheme' || isActive('scheme'),
          'bulletin-media-editing': isActive('scheme'),
          'bulletin-zone-active': isActive('scheme'),
          'bulletin-zone-hit': canEdit && !isActive('scheme'),
        }"
        :tabindex="canEdit ? 0 : undefined"
        @click="canEdit && !isActive('scheme') ? startEdit('scheme') : undefined"
        @paste="onMediaPaste('scheme', $event)"
        @dragover="onMediaDragOver('scheme', $event)"
        @dragleave="onMediaDragLeave('scheme')"
        @drop="onMediaDrop('scheme', $event)"
      >
        <p v-if="canEdit || bulletin.scheme_url" class="bulletin-field-label">Схема старта</p>
        <figure v-if="bulletin.scheme_url" class="bulletin-figure">
          <img :src="bulletin.scheme_url" alt="Схема расположения старта" />
        </figure>
        <div
          v-if="canEdit && (isActive('scheme') || !bulletin.scheme_url)"
          class="bulletin-dropzone"
          :class="{ busy: uploading === 'scheme' }"
        >
          {{
            uploading === "scheme"
              ? "Загрузка…"
              : isActive("scheme")
                ? "Вставьте картинку: Ctrl+V (или Cmd+V), либо перетащите файл"
                : "Нажмите, чтобы вставить или загрузить схему"
          }}
        </div>
        <div v-if="canEdit && isActive('scheme')" class="admin-row-actions">
          <label class="btn" @click.stop>
            {{ uploading === "scheme" ? "Загрузка…" : "Загрузить файл" }}
            <input
              type="file"
              accept="image/jpeg,image/png,image/webp"
              hidden
              :disabled="uploading !== ''"
              @change="onScheme"
            />
          </label>
          <button
            v-if="bulletin.scheme_url"
            class="btn"
            type="button"
            @click.stop="clearScheme"
          >
            Убрать
          </button>
          <BulletinBlockActions :disabled="saving" @confirm="confirmEdit" @cancel="cancelEdit" />
          <p v-if="!upcomingId" class="muted">Сначала сохраните старт.</p>
        </div>
      </div>
    </section>

    <section v-if="showTech" class="bulletin-section">
      <h2>Техническая информация</h2>

      <div
        v-if="isActive('tech_facts')"
        class="bulletin-zone bulletin-zone-active"
      >
        <div class="bulletin-facts bulletin-facts-edit">
          <label>
            Контрольное время, мин
            <input v-model="bulletin.time_limit_minutes" class="bulletin-inline-input" />
          </label>
          <label>
            Система отметки
            <input v-model="bulletin.punching" class="bulletin-inline-input" />
          </label>
          <label>
            Аварийный азимут
            <input v-model="bulletin.emergency_azimuth" class="bulletin-inline-input" />
          </label>
          <label>
            Масштаб по умолчанию
            <input v-model="bulletin.map_scale" class="bulletin-inline-input" placeholder="1:4000" />
          </label>
          <label>
            Сечение
            <input v-model="bulletin.contour" class="bulletin-inline-input" placeholder="2,5м" />
          </label>
          <p class="muted" style="grid-column: 1 / -1; margin: 0">
            Масштаб для конкретной дистанции задаётся в таблице дистанций.
          </p>
        </div>
        <BulletinBlockActions :disabled="saving" @confirm="confirmEdit" @cancel="cancelEdit" />
      </div>

      <button
        v-else-if="canEdit"
        type="button"
        class="bulletin-zone bulletin-zone-hit"
        @click="startEdit('tech_facts')"
      >
        <div
          v-if="bulletin.time_limit_minutes || bulletin.punching || mapScaleLine || bulletin.emergency_azimuth"
          class="bulletin-facts"
        >
          <div v-if="bulletin.time_limit_minutes" class="bulletin-fact">
            <span class="bulletin-fact-label">Контрольное время</span>
            <span>{{ bulletin.time_limit_minutes }} мин</span>
          </div>
          <div v-if="bulletin.punching" class="bulletin-fact">
            <span class="bulletin-fact-label">Отметка</span>
            <span>{{ bulletin.punching }}</span>
          </div>
          <div v-if="mapScaleLine" class="bulletin-fact">
            <span class="bulletin-fact-label">Карта</span>
            <span>{{ mapScaleLine }}</span>
          </div>
          <div v-if="bulletin.emergency_azimuth" class="bulletin-fact">
            <span class="bulletin-fact-label">Азимут</span>
            <span>{{ bulletin.emergency_azimuth }}</span>
          </div>
        </div>
        <span v-else class="bulletin-placeholder">Контрольное время, отметка, масштаб…</span>
      </button>

      <div
        v-else-if="bulletin.time_limit_minutes || bulletin.punching"
        class="bulletin-facts"
      >
        <div v-if="bulletin.time_limit_minutes" class="bulletin-fact">
          <span class="bulletin-fact-label">Контрольное время</span>
          <span>{{ bulletin.time_limit_minutes }} мин</span>
        </div>
        <div v-if="bulletin.punching" class="bulletin-fact">
          <span class="bulletin-fact-label">Отметка</span>
          <span>{{ bulletin.punching }}</span>
        </div>
      </div>

      <BulletinField
        v-model="bulletin.rules"
        field="rules"
        label="Правила и дисциплина"
        :active="isActive('rules')"
        :can-edit="canEdit"
        placeholder="Правила и дисциплина"
        min-height="4.5rem"
        :saving="saving"
        @edit="startEdit"
        @confirm="confirmEdit"
        @cancel="cancelEdit"
      />

      <BulletinField
        v-model="bulletin.stations"
        field="stations"
        label="Станции"
        :active="isActive('stations')"
        :can-edit="canEdit"
        placeholder="Станции"
        min-height="4rem"
        :saving="saving"
        @edit="startEdit"
        @confirm="confirmEdit"
        @cancel="cancelEdit"
      />

      <BulletinField
        v-model="bulletin.course_intro"
        field="course_intro"
        label="Предварительные параметры"
        :active="isActive('course_intro')"
        :can-edit="canEdit"
        placeholder="Предварительные параметры дистанций"
        min-height="4.5rem"
        :saving="saving"
        @edit="startEdit"
        @confirm="confirmEdit"
        @cancel="cancelEdit"
      />

      <div
        v-if="canEdit || courses.length"
        class="bulletin-block"
        :class="{ 'bulletin-zone-active': isActive('courses') }"
      >
        <p class="bulletin-field-label">Дистанции</p>
        <div
          v-if="isActive('courses')"
          class="bulletin-zone"
        >
          <div class="table-wrap">
            <table class="bulletin-table">
              <thead>
                <tr>
                  <th>Дистанция</th>
                  <th>длина</th>
                  <th>кол-во КП</th>
                  <th>масштаб</th>
                  <th v-if="registrationUrl">регистрация</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in bulletin.courses" :key="index">
                  <td><input v-model="row.name" class="bulletin-inline-input" /></td>
                  <td><input v-model="row.length" class="bulletin-inline-input" /></td>
                  <td><input v-model="row.controls" class="bulletin-inline-input" /></td>
                  <td>
                    <input
                      v-model="row.scale"
                      class="bulletin-inline-input"
                      placeholder="1:4000"
                    />
                  </td>
                  <td v-if="registrationUrl">
                    <a
                      class="bulletin-register-link"
                      :href="registrationUrl"
                      target="_blank"
                      rel="noreferrer"
                      @click.stop
                    >
                      Регистрация
                    </a>
                  </td>
                  <td>
                    <button class="btn" type="button" @click="removeCourse(index)">Убрать</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="admin-row-actions">
            <button class="btn" type="button" @click="addCourse">Добавить дистанцию</button>
            <BulletinBlockActions
              :disabled="saving"
              @confirm="confirmEdit"
              @cancel="cancelEdit"
            />
          </div>
        </div>
        <button
          v-else-if="canEdit"
          type="button"
          class="bulletin-zone bulletin-zone-hit"
          @click="startEdit('courses')"
        >
          <table v-if="filledCourses(bulletin).length" class="bulletin-table">
            <thead>
              <tr>
                <th>Дистанция</th>
                <th>длина</th>
                <th>кол-во КП</th>
                <th v-if="coursesHaveScale(bulletin)">масштаб</th>
                <th v-if="registrationUrl">регистрация</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in filledCourses(bulletin)" :key="index">
                <td>{{ row.name }}</td>
                <td>{{ row.length }}</td>
                <td>{{ row.controls }}</td>
                <td v-if="coursesHaveScale(bulletin)">{{ row.scale }}</td>
                <td v-if="registrationUrl">
                  <a
                    class="bulletin-register-link"
                    :href="registrationUrl"
                    target="_blank"
                    rel="noreferrer"
                    @click.stop
                  >
                    Регистрация
                  </a>
                </td>
              </tr>
            </tbody>
          </table>
          <span v-else class="bulletin-placeholder">Таблица дистанций</span>
        </button>
        <table v-else-if="filledCourses(bulletin).length" class="bulletin-table">
          <thead>
            <tr>
              <th>Дистанция</th>
              <th>длина</th>
              <th>кол-во КП</th>
              <th v-if="coursesHaveScale(bulletin)">масштаб</th>
              <th v-if="registrationUrl">регистрация</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in filledCourses(bulletin)" :key="index">
              <td>{{ row.name }}</td>
              <td>{{ row.length }}</td>
              <td>{{ row.controls }}</td>
              <td v-if="coursesHaveScale(bulletin)">{{ row.scale }}</td>
              <td v-if="registrationUrl">
                <a
                  class="bulletin-register-link"
                  :href="registrationUrl"
                  target="_blank"
                  rel="noreferrer"
                >
                  Регистрация
                </a>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <BulletinField
        v-model="bulletin.extra_course_notes"
        field="extra_course_notes"
        label="Дополнительно про дистанции"
        :active="isActive('extra_course_notes')"
        :can-edit="canEdit"
        placeholder="Дополнительно про дистанции"
        min-height="4rem"
        :saving="saving"
        @edit="startEdit"
        @confirm="confirmEdit"
        @cancel="cancelEdit"
      />

      <BulletinField
        v-model="bulletin.terrain"
        field="terrain"
        label="Местность"
        :active="isActive('terrain')"
        :can-edit="canEdit"
        placeholder="Местность"
        min-height="4rem"
        :saving="saving"
        @edit="startEdit"
        @confirm="confirmEdit"
        @cancel="cancelEdit"
      />

      <BulletinField
        v-model="bulletin.relief"
        field="relief"
        label="Рельеф"
        :active="isActive('relief')"
        :can-edit="canEdit"
        placeholder="Рельеф"
        min-height="3.5rem"
        :saving="saving"
        @edit="startEdit"
        @confirm="confirmEdit"
        @cancel="cancelEdit"
      />

      <BulletinField
        v-model="bulletin.map_info"
        field="map_info"
        label="Карта"
        :active="isActive('map_info')"
        :can-edit="canEdit"
        placeholder="Карта"
        min-height="4rem"
        :saving="saving"
        @edit="startEdit"
        @confirm="confirmEdit"
        @cancel="cancelEdit"
      />

      <BulletinField
        v-model="bulletin.map_notes"
        field="map_notes"
        label="Особенности рисовки"
        :active="isActive('map_notes')"
        :can-edit="canEdit"
        placeholder="Особенности рисовки"
        min-height="4rem"
        :saving="saving"
        @edit="startEdit"
        @confirm="confirmEdit"
        @cancel="cancelEdit"
      />

      <BulletinField
        v-model="bulletin.bounds"
        field="bounds"
        label="Границы района"
        :active="isActive('bounds')"
        :can-edit="canEdit"
        placeholder="Границы района"
        min-height="3.5rem"
        :saving="saving"
        @edit="startEdit"
        @confirm="confirmEdit"
        @cancel="cancelEdit"
      />

      <p
        v-if="!canEdit && bulletin.emergency_azimuth"
        class="bulletin-note"
      >
        Аварийный азимут — {{ bulletin.emergency_azimuth }}.
      </p>

      <BulletinField
        v-model="bulletin.map_format"
        field="map_format"
        label="Формат и печать карты"
        :active="isActive('map_format')"
        :can-edit="canEdit"
        placeholder="Формат и печать карты"
        min-height="3.5rem"
        :saving="saving"
        @edit="startEdit"
        @confirm="confirmEdit"
        @cancel="cancelEdit"
      />

      <p v-if="!canEdit && mapScaleLine" class="bulletin-note">
        {{ mapScaleLine.charAt(0).toUpperCase() + mapScaleLine.slice(1) }}.
      </p>

      <div
        v-if="canEdit || bulletin.map_urls.length"
        ref="mapsMediaEl"
        class="bulletin-media"
        :class="{
          'bulletin-media-active': dropTarget === 'map' || isActive('maps'),
          'bulletin-media-editing': isActive('maps'),
          'bulletin-zone-active': isActive('maps'),
          'bulletin-zone-hit': canEdit && !isActive('maps'),
        }"
        :tabindex="canEdit ? 0 : undefined"
        @click="canEdit && !isActive('maps') ? startEdit('maps') : undefined"
        @paste="onMediaPaste('map', $event)"
        @dragover="onMediaDragOver('map', $event)"
        @dragleave="onMediaDragLeave('map')"
        @drop="onMediaDrop('map', $event)"
      >
        <p class="bulletin-field-label">Фрагменты карты</p>
        <div v-if="bulletin.map_urls.length" class="bulletin-maps">
          <figure v-for="(url, index) in bulletin.map_urls" :key="url" class="bulletin-figure">
            <img :src="url" alt="Фрагмент карты" />
            <button
              v-if="canEdit && isActive('maps')"
              class="btn"
              type="button"
              @click.stop="removeMap(index)"
            >
              Убрать
            </button>
          </figure>
        </div>
        <div
          v-if="canEdit && (isActive('maps') || !bulletin.map_urls.length)"
          class="bulletin-dropzone"
          :class="{ busy: uploading === 'map' }"
        >
          {{
            uploading === "map"
              ? "Загрузка…"
              : isActive("maps")
                ? "Вставьте картинку: Ctrl+V (или Cmd+V), либо перетащите файл"
                : "Нажмите, чтобы вставить или загрузить фрагмент"
          }}
        </div>
        <div v-if="canEdit && isActive('maps')" class="admin-row-actions">
          <label class="btn" @click.stop>
            {{ uploading === "map" ? "Загрузка…" : "Добавить файл" }}
            <input
              type="file"
              accept="image/jpeg,image/png,image/webp"
              hidden
              :disabled="uploading !== ''"
              @change="onMap"
            />
          </label>
          <BulletinBlockActions :disabled="saving" @confirm="confirmEdit" @cancel="cancelEdit" />
        </div>
      </div>
    </section>

    <section
      v-if="canEdit || hasText(bulletin.hazards)"
      class="bulletin-section bulletin-section-warn"
    >
      <h2>Опасные места</h2>
      <BulletinField
        v-model="bulletin.hazards"
        field="hazards"
        :active="isActive('hazards')"
        :can-edit="canEdit"
        placeholder="Опасные места"
        :saving="saving"
        @edit="startEdit"
        @confirm="confirmEdit"
        @cancel="cancelEdit"
      />
    </section>

    <section v-if="showEntry" class="bulletin-section">
      <h2>Заявка и стоимость</h2>
      <div v-if="(registrationUrl || liveUrl) && !canEdit" class="bulletin-register-wrap">
        <a
          v-if="registrationUrl"
          class="bulletin-register-btn"
          :href="registrationUrl"
          target="_blank"
          rel="noreferrer"
        >
          Регистрация
        </a>
        <a v-if="liveUrl" class="bulletin-live-btn" :href="liveUrl">
          Live-результаты
        </a>
      </div>

      <BulletinField
        v-model="bulletin.entry_kind"
        field="entry_kind"
        kind="text"
        label="Тип заявки"
        :active="isActive('entry_kind')"
        :can-edit="canEdit"
        placeholder="Тип заявки (например, динамическая)"
        display-class="bulletin-note"
        :saving="saving"
        @edit="startEdit"
        @confirm="confirmEdit"
        @cancel="cancelEdit"
      />

      <BulletinField
        v-model="bulletin.entry_details"
        field="entry_details"
        label="Сроки, цены и детали"
        :active="isActive('entry_details')"
        :can-edit="canEdit"
        placeholder="Сроки, цены и детали"
        :saving="saving"
        @edit="startEdit"
        @confirm="confirmEdit"
        @cancel="cancelEdit"
      />
    </section>

    <section v-if="canEdit || hasText(bulletin.contacts)" class="bulletin-section">
      <h2>Контакты</h2>
      <template v-if="isActive('contacts')">
        <BulletinField
          v-model="bulletin.contacts"
          field="contacts"
          :active="true"
          :can-edit="canEdit"
          placeholder="Telegram, телефон, имя…"
          min-height="3.5rem"
          :saving="saving"
          @edit="startEdit"
          @confirm="confirmEdit"
          @cancel="cancelEdit"
        />
      </template>
      <button
        v-else-if="canEdit && !hasText(bulletin.contacts)"
        type="button"
        class="bulletin-placeholder"
        @click="startEdit('contacts')"
      >
        Контакты
      </button>
      <div
        v-else-if="canEdit"
        class="bulletin-contacts bulletin-field-view"
        role="button"
        tabindex="0"
        @click="startEdit('contacts')"
        @keydown.enter.prevent="startEdit('contacts')"
      >
        <template v-for="(item, index) in contactItems" :key="index">
          <a
            v-if="item.kind === 'telegram'"
            class="bulletin-contact bulletin-contact-tg"
            :href="item.href"
            target="_blank"
            rel="noreferrer"
            :title="item.label"
            @click.stop
          >
            <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
              <path
                fill="currentColor"
                d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10 10-4.5 10-10S17.5 2 12 2Zm4.7 6.9-1.6 7.5c-.1.5-.4.6-.9.4l-2.4-1.8-1.2 1.1c-.1.1-.3.3-.6.3l.2-2.5 4.5-4.1c.2-.2 0-.3-.3-.1l-5.6 3.5-2.4-.8c-.5-.1-.5-.5.1-.8l9.4-3.6c.4-.2.8.1.7.9Z"
              />
            </svg>
            <span class="sr-only">{{ item.label }}</span>
          </a>
          <a
            v-else-if="item.kind === 'phone'"
            class="bulletin-contact bulletin-contact-phone"
            :href="item.href"
            @click.stop
          >
            <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
              <path
                fill="currentColor"
                d="M7.2 3.5c.4-.4 1-.5 1.5-.3l2.2.9c.5.2.8.7.8 1.2l-.2 2.3c0 .4-.2.7-.5.9l-1.3.9c.9 1.8 2.3 3.2 4.1 4.1l.9-1.3c.2-.3.5-.5.9-.5l2.3-.2c.5 0 1 .3 1.2.8l.9 2.2c.2.5.1 1.1-.3 1.5l-1.4 1.4c-.4.4-1 .6-1.5.5-3.3-.5-6.4-2.3-8.8-4.7S4.2 9.3 3.7 6c-.1-.5.1-1.1.5-1.5l1.4-1.4-.4-.6.2-.5.3-.5Z"
              />
            </svg>
            <span>{{ item.label }}</span>
          </a>
          <span v-else class="bulletin-contact-text">{{ item.label }}</span>
        </template>
      </div>
      <div v-else class="bulletin-contacts">
        <template v-for="(item, index) in contactItems" :key="index">
          <a
            v-if="item.kind === 'telegram'"
            class="bulletin-contact bulletin-contact-tg"
            :href="item.href"
            target="_blank"
            rel="noreferrer"
            :title="item.label"
          >
            <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
              <path
                fill="currentColor"
                d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10 10-4.5 10-10S17.5 2 12 2Zm4.7 6.9-1.6 7.5c-.1.5-.4.6-.9.4l-2.4-1.8-1.2 1.1c-.1.1-.3.3-.6.3l.2-2.5 4.5-4.1c.2-.2 0-.3-.3-.1l-5.6 3.5-2.4-.8c-.5-.1-.5-.5.1-.8l9.4-3.6c.4-.2.8.1.7.9Z"
              />
            </svg>
            <span class="sr-only">{{ item.label }}</span>
          </a>
          <a
            v-else-if="item.kind === 'phone'"
            class="bulletin-contact bulletin-contact-phone"
            :href="item.href"
          >
            <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
              <path
                fill="currentColor"
                d="M7.2 3.5c.4-.4 1-.5 1.5-.3l2.2.9c.5.2.8.7.8 1.2l-.2 2.3c0 .4-.2.7-.5.9l-1.3.9c.9 1.8 2.3 3.2 4.1 4.1l.9-1.3c.2-.3.5-.5.9-.5l2.3-.2c.5 0 1 .3 1.2.8l.9 2.2c.2.5.1 1.1-.3 1.5l-1.4 1.4c-.4.4-1 .6-1.5.5-3.3-.5-6.4-2.3-8.8-4.7S4.2 9.3 3.7 6c-.1-.5.1-1.1.5-1.5l1.4-1.4-.4-.6.2-.5.3-.5Z"
              />
            </svg>
            <span>{{ item.label }}</span>
          </a>
          <span v-else class="bulletin-contact-text">{{ item.label }}</span>
        </template>
      </div>
    </section>
  </article>
</template>
