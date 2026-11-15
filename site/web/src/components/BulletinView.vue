<script setup lang="ts">
import { computed, ref } from "vue";
import {
  bulletinPlaceLine,
  emptyCourse,
  filledCourses,
  formatDateRu,
  googleMapsUrl,
  hasCoords,
  isHttpUrl,
  richTextHtml,
  yandexMapsUrl,
  type Bulletin,
} from "../bulletin";
import RichTextEditor from "./RichTextEditor.vue";

const bulletin = defineModel<Bulletin>({ required: true });

const props = defineProps<{
  title: string;
  date: string | null;
  editable?: boolean;
  upcomingId?: number | null;
}>();

const emit = defineEmits<{
  error: [message: string];
}>();

const uploading = ref("");
const dropTarget = ref<"scheme" | "map" | "">("");

function textHtml(value: string): string {
  return richTextHtml(value);
}

function imageFromClipboard(event: ClipboardEvent): File | null {
  const items = event.clipboardData?.items;
  if (!items) return null;
  for (const item of items) {
    if (!item.type.startsWith("image/")) continue;
    const blob = item.getAsFile();
    if (!blob) continue;
    const ext =
      item.type === "image/png"
        ? "png"
        : item.type === "image/webp"
          ? "webp"
          : "jpg";
    return new File([blob], `paste.${ext}`, { type: item.type || "image/png" });
  }
  return null;
}

function imageFromDataTransfer(data: DataTransfer | null): File | null {
  if (!data) return null;
  for (const file of Array.from(data.files || [])) {
    if (file.type.startsWith("image/")) return file;
  }
  return null;
}

function hasText(value: string | undefined): boolean {
  return Boolean(value?.replace(/<[^>]+>/g, "").trim());
}

const registrationUrl = computed(() => {
  const url = bulletin.value.registration_url.trim();
  return isHttpUrl(url) ? url : "";
});

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
  props.editable ? bulletin.value.courses : filledCourses(bulletin.value),
);

const showProgram = computed(
  () =>
    props.editable ||
    hasText(bulletin.value.intro) ||
    hasCoords(bulletin.value) ||
    startHoursLine.value ||
    hasText(bulletin.value.location) ||
    hasText(bulletin.value.parking) ||
    bulletin.value.scheme_url,
);

const showTech = computed(
  () =>
    props.editable ||
    hasText(bulletin.value.rules) ||
    bulletin.value.time_limit_minutes ||
    bulletin.value.punching ||
    hasText(bulletin.value.stations) ||
    hasText(bulletin.value.course_intro) ||
    courses.value.length ||
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
    props.editable ||
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
  if (!props.editable || uploading.value) return;
  const file = imageFromClipboard(event);
  if (!file) return;
  event.preventDefault();
  void upload(slot, file);
}

function onMediaDragOver(slot: "scheme" | "map", event: DragEvent) {
  if (!props.editable || uploading.value) return;
  if (![...Array.from(event.dataTransfer?.types || [])].includes("Files")) return;
  event.preventDefault();
  dropTarget.value = slot;
}

function onMediaDragLeave(slot: "scheme" | "map") {
  if (dropTarget.value === slot) dropTarget.value = "";
}

function onMediaDrop(slot: "scheme" | "map", event: DragEvent) {
  if (!props.editable || uploading.value) return;
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
</script>

<template>
  <article class="bulletin" :class="{ 'bulletin-editable': editable }">
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
          <template v-if="editable">
            <input
              v-model="bulletin.event_kind"
              class="bulletin-inline-input bulletin-inline-kicker"
              placeholder="Тип старта"
            />
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
          </template>
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

      <div v-if="editable || registrationUrl" class="bulletin-hero-cta">
        <template v-if="editable">
          <label class="bulletin-field-label">
            Ссылка на регистрацию
            <input
              v-model="bulletin.registration_url"
              class="bulletin-inline-input"
              type="url"
              placeholder="https://…"
            />
          </label>
        </template>
        <a
          v-if="registrationUrl"
          class="bulletin-register-btn"
          :href="registrationUrl"
          target="_blank"
          rel="noreferrer"
          :tabindex="editable ? -1 : undefined"
          @click="editable ? $event.preventDefault() : undefined"
        >
          Зарегистрироваться
        </a>
        <p v-else-if="editable" class="muted bulletin-cta-hint">
          Кнопка появится после сохранения ссылки.
        </p>
      </div>
    </header>

    <section v-if="showProgram" class="bulletin-section">
      <h2>Дата, место и программа</h2>

      <div v-if="editable" class="bulletin-facts bulletin-facts-edit">
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

      <div v-if="editable || hasText(bulletin.intro)" class="bulletin-block">
        <p v-if="editable" class="bulletin-field-label">Вводный абзац</p>
        <RichTextEditor
          v-if="editable"
          v-model="bulletin.intro"
          placeholder="Коротко о старте…"
          min-height="4.5rem"
        />
        <div v-else class="bulletin-text" v-html="textHtml(bulletin.intro)" />
      </div>

      <div v-if="editable || hasText(bulletin.location)" class="bulletin-block">
        <p class="bulletin-field-label">{{ editable ? "Где старт и как добраться" : "Место" }}</p>
        <RichTextEditor
          v-if="editable"
          v-model="bulletin.location"
          placeholder="Как добраться, размещение…"
        />
        <div v-else class="bulletin-text" v-html="textHtml(bulletin.location)" />
      </div>

      <div v-if="editable || hasText(bulletin.parking)" class="bulletin-block">
        <p class="bulletin-field-label">Парковка</p>
        <RichTextEditor
          v-if="editable"
          v-model="bulletin.parking"
          placeholder="Где парковаться…"
          min-height="4rem"
        />
        <div v-else class="bulletin-text" v-html="textHtml(bulletin.parking)" />
      </div>

      <div
        v-if="editable || bulletin.scheme_url"
        class="bulletin-media"
        :class="{ 'bulletin-media-active': dropTarget === 'scheme' }"
        :tabindex="editable ? 0 : undefined"
        @click="editable ? ($event.currentTarget as HTMLElement).focus() : undefined"
        @paste="onMediaPaste('scheme', $event)"
        @dragover="onMediaDragOver('scheme', $event)"
        @dragleave="onMediaDragLeave('scheme')"
        @drop="onMediaDrop('scheme', $event)"
      >
        <p class="bulletin-field-label">Схема старта</p>
        <figure v-if="bulletin.scheme_url" class="bulletin-figure">
          <img :src="bulletin.scheme_url" alt="Схема расположения старта" />
        </figure>
        <div
          v-if="editable"
          class="bulletin-dropzone"
          :class="{ busy: uploading === 'scheme' }"
        >
          {{
            uploading === "scheme"
              ? "Загрузка…"
              : bulletin.scheme_url
                ? "Ctrl+V или drag-and-drop — заменить схему"
                : "Вставьте картинку (Ctrl+V) или перетащите файл сюда"
          }}
        </div>
        <div v-if="editable" class="admin-row-actions">
          <label class="btn">
            {{ uploading === "scheme" ? "Загрузка…" : "Загрузить схему" }}
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
            @click="clearScheme"
          >
            Убрать
          </button>
          <p v-if="!upcomingId" class="muted">Сначала сохраните старт.</p>
        </div>
      </div>
    </section>

    <section v-if="showTech" class="bulletin-section">
      <h2>Техническая информация</h2>

      <div v-if="!editable && (bulletin.time_limit_minutes || bulletin.punching)" class="bulletin-facts">
        <div v-if="bulletin.time_limit_minutes" class="bulletin-fact">
          <span class="bulletin-fact-label">Контрольное время</span>
          <span>{{ bulletin.time_limit_minutes }} мин</span>
        </div>
        <div v-if="bulletin.punching" class="bulletin-fact">
          <span class="bulletin-fact-label">Отметка</span>
          <span>{{ bulletin.punching }}</span>
        </div>
      </div>

      <div v-if="editable" class="bulletin-facts bulletin-facts-edit">
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
          Масштаб
          <input v-model="bulletin.map_scale" class="bulletin-inline-input" placeholder="1:4000" />
        </label>
        <label>
          Сечение
          <input v-model="bulletin.contour" class="bulletin-inline-input" placeholder="2,5м" />
        </label>
      </div>

      <div v-if="editable || hasText(bulletin.rules)" class="bulletin-block">
        <p class="bulletin-field-label">Правила и дисциплина</p>
        <RichTextEditor
          v-if="editable"
          v-model="bulletin.rules"
          min-height="4.5rem"
        />
        <div v-else class="bulletin-text" v-html="textHtml(bulletin.rules)" />
      </div>

      <div v-if="editable || hasText(bulletin.stations)" class="bulletin-block">
        <p class="bulletin-field-label">Станции</p>
        <RichTextEditor
          v-if="editable"
          v-model="bulletin.stations"
          min-height="4rem"
        />
        <div v-else class="bulletin-text" v-html="textHtml(bulletin.stations)" />
      </div>

      <div v-if="editable || hasText(bulletin.course_intro)" class="bulletin-block">
        <p class="bulletin-field-label">Предварительные параметры</p>
        <RichTextEditor
          v-if="editable"
          v-model="bulletin.course_intro"
          min-height="4.5rem"
        />
        <div v-else class="bulletin-text" v-html="textHtml(bulletin.course_intro)" />
      </div>

      <div v-if="editable || courses.length" class="bulletin-block">
        <p class="bulletin-field-label">Дистанции</p>
        <div class="table-wrap">
          <table class="bulletin-table">
            <thead>
              <tr>
                <th>Дистанция</th>
                <th>длина</th>
                <th>кол-во КП</th>
                <th v-if="editable"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in courses" :key="index">
                <td>
                  <input
                    v-if="editable"
                    v-model="row.name"
                    class="bulletin-inline-input"
                  />
                  <template v-else>{{ row.name }}</template>
                </td>
                <td>
                  <input
                    v-if="editable"
                    v-model="row.length"
                    class="bulletin-inline-input"
                  />
                  <template v-else>{{ row.length }}</template>
                </td>
                <td>
                  <input
                    v-if="editable"
                    v-model="row.controls"
                    class="bulletin-inline-input"
                  />
                  <template v-else>{{ row.controls }}</template>
                </td>
                <td v-if="editable">
                  <button class="btn" type="button" @click="removeCourse(index)">Убрать</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <button v-if="editable" class="btn" type="button" @click="addCourse">
          Добавить дистанцию
        </button>
      </div>

      <div v-if="editable || hasText(bulletin.extra_course_notes)" class="bulletin-block">
        <p class="bulletin-field-label">Дополнительно про дистанции</p>
        <RichTextEditor
          v-if="editable"
          v-model="bulletin.extra_course_notes"
          min-height="4rem"
        />
        <div v-else class="bulletin-text" v-html="textHtml(bulletin.extra_course_notes)" />
      </div>

      <div v-if="editable || hasText(bulletin.terrain)" class="bulletin-block">
        <p class="bulletin-field-label">Местность</p>
        <RichTextEditor v-if="editable" v-model="bulletin.terrain" min-height="4rem" />
        <div v-else class="bulletin-text" v-html="textHtml(bulletin.terrain)" />
      </div>

      <div v-if="editable || hasText(bulletin.relief)" class="bulletin-block">
        <p class="bulletin-field-label">Рельеф</p>
        <RichTextEditor v-if="editable" v-model="bulletin.relief" min-height="3.5rem" />
        <div v-else class="bulletin-text" v-html="textHtml(bulletin.relief)" />
      </div>

      <div v-if="editable || hasText(bulletin.map_info)" class="bulletin-block">
        <p class="bulletin-field-label">Карта</p>
        <RichTextEditor v-if="editable" v-model="bulletin.map_info" min-height="4rem" />
        <div v-else class="bulletin-text" v-html="textHtml(bulletin.map_info)" />
      </div>

      <div v-if="editable || hasText(bulletin.map_notes)" class="bulletin-block">
        <p class="bulletin-field-label">Особенности рисовки</p>
        <RichTextEditor v-if="editable" v-model="bulletin.map_notes" min-height="4rem" />
        <div v-else class="bulletin-text" v-html="textHtml(bulletin.map_notes)" />
      </div>

      <div v-if="editable || hasText(bulletin.bounds)" class="bulletin-block">
        <p class="bulletin-field-label">Границы района</p>
        <RichTextEditor v-if="editable" v-model="bulletin.bounds" min-height="3.5rem" />
        <div v-else class="bulletin-text" v-html="textHtml(bulletin.bounds)" />
      </div>

      <p
        v-if="!editable && bulletin.emergency_azimuth && !hasText(bulletin.bounds)"
        class="bulletin-note"
      >
        Аварийный азимут — {{ bulletin.emergency_azimuth }}.
      </p>
      <p
        v-else-if="!editable && bulletin.emergency_azimuth && hasText(bulletin.bounds)"
        class="bulletin-note"
      >
        Аварийный азимут — {{ bulletin.emergency_azimuth }}.
      </p>

      <div v-if="editable || hasText(bulletin.map_format)" class="bulletin-block">
        <p class="bulletin-field-label">Формат и печать карты</p>
        <RichTextEditor v-if="editable" v-model="bulletin.map_format" min-height="3.5rem" />
        <div v-else class="bulletin-text" v-html="textHtml(bulletin.map_format)" />
      </div>

      <p v-if="!editable && mapScaleLine" class="bulletin-note">
        {{ mapScaleLine.charAt(0).toUpperCase() + mapScaleLine.slice(1) }}.
      </p>

      <div
        v-if="editable || bulletin.map_urls.length"
        class="bulletin-media"
        :class="{ 'bulletin-media-active': dropTarget === 'map' }"
        :tabindex="editable ? 0 : undefined"
        @click="editable ? ($event.currentTarget as HTMLElement).focus() : undefined"
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
              v-if="editable"
              class="btn"
              type="button"
              @click="removeMap(index)"
            >
              Убрать
            </button>
          </figure>
        </div>
        <div
          v-if="editable"
          class="bulletin-dropzone"
          :class="{ busy: uploading === 'map' }"
        >
          {{ uploading === "map" ? "Загрузка…" : "Вставьте картинку (Ctrl+V) или перетащите файл сюда" }}
        </div>
        <label v-if="editable" class="btn">
          {{ uploading === "map" ? "Загрузка…" : "Добавить фрагмент" }}
          <input
            type="file"
            accept="image/jpeg,image/png,image/webp"
            hidden
            :disabled="uploading !== ''"
            @change="onMap"
          />
        </label>
      </div>
    </section>

    <section v-if="editable || hasText(bulletin.hazards)" class="bulletin-section bulletin-section-warn">
      <h2>Опасные места</h2>
      <RichTextEditor v-if="editable" v-model="bulletin.hazards" />
      <div v-else class="bulletin-text" v-html="textHtml(bulletin.hazards)" />
    </section>

    <section v-if="showEntry" class="bulletin-section">
      <h2>Заявка и стоимость</h2>
      <p v-if="registrationUrl && !editable" class="bulletin-register-wrap">
        <a
          class="bulletin-register-btn"
          :href="registrationUrl"
          target="_blank"
          rel="noreferrer"
        >
          Зарегистрироваться
        </a>
      </p>
      <div v-if="editable" class="bulletin-block">
        <p class="bulletin-field-label">Тип заявки</p>
        <input
          v-model="bulletin.entry_kind"
          class="bulletin-inline-input"
          placeholder="динамическая"
        />
      </div>
      <p v-else-if="bulletin.entry_kind" class="bulletin-note">
        <strong>Тип заявки:</strong> {{ bulletin.entry_kind }}
      </p>
      <div v-if="editable || hasText(bulletin.entry_details)" class="bulletin-block">
        <p class="bulletin-field-label">Сроки, цены и детали</p>
        <RichTextEditor v-if="editable" v-model="bulletin.entry_details" />
        <div v-else class="bulletin-text" v-html="textHtml(bulletin.entry_details)" />
      </div>
    </section>

    <section v-if="editable || hasText(bulletin.contacts)" class="bulletin-section">
      <h2>Контакты</h2>
      <RichTextEditor v-if="editable" v-model="bulletin.contacts" min-height="3.5rem" />
      <div v-else class="bulletin-text" v-html="textHtml(bulletin.contacts)" />
    </section>
  </article>
</template>
