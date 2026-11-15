<script setup lang="ts">
import { ref } from "vue";
import {
  emptyCourse,
  type Bulletin,
} from "../bulletin";
import RichTextEditor from "./RichTextEditor.vue";

const bulletin = defineModel<Bulletin>({ required: true });

const props = defineProps<{
  upcomingId: number | null;
  disabled?: boolean;
}>();

const emit = defineEmits<{
  error: [message: string];
}>();

const uploading = ref("");

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
    if (!res.ok) {
      throw new Error(await res.text());
    }
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
  <div class="bulletin-editor">
    <fieldset>
      <legend>Шапка бюллетеня</legend>
      <label>
        Тип старта
        <input v-model="bulletin.event_kind" :disabled="disabled" />
      </label>
      <label>
        Округ / район
        <input v-model="bulletin.district" :disabled="disabled" placeholder="САО" />
      </label>
      <label>
        Место
        <input v-model="bulletin.venue" :disabled="disabled" placeholder="парк Березовая роща" />
      </label>
      <label>
        Дисциплина
        <input v-model="bulletin.discipline" :disabled="disabled" placeholder="парковый спринт" />
      </label>
    </fieldset>

    <fieldset>
      <legend>Дата, место и программа</legend>
      <label>
        Вводный абзац
        <RichTextEditor v-model="bulletin.intro" :disabled="disabled" />
      </label>
      <div class="admin-pair">
        <label>
          Широта
          <input v-model="bulletin.lat" :disabled="disabled" placeholder="55.784728" />
        </label>
        <label>
          Долгота
          <input v-model="bulletin.lon" :disabled="disabled" placeholder="37.516126" />
        </label>
      </div>
      <p class="muted">
        Ссылки на Яндекс и Google карты собираются из координат сами.
      </p>
      <div class="admin-pair">
        <label>
          Старт с
          <input v-model="bulletin.start_from" :disabled="disabled" placeholder="12:00" />
        </label>
        <label>
          Старт до
          <input v-model="bulletin.start_to" :disabled="disabled" placeholder="14:00" />
        </label>
        <label>
          Финиш до
          <input v-model="bulletin.finish_until" :disabled="disabled" placeholder="15:30" />
        </label>
      </div>
      <label>
        Где старт, как добраться, размещение
        <RichTextEditor v-model="bulletin.location" :disabled="disabled" />
      </label>
      <label>
        Парковка
        <RichTextEditor v-model="bulletin.parking" :disabled="disabled" min-height="4.5rem" />
      </label>
      <div class="bulletin-upload">
        <p>Схема старта</p>
        <img v-if="bulletin.scheme_url" :src="bulletin.scheme_url" alt="" />
        <div class="admin-row-actions">
          <label class="btn">
            {{ uploading === "scheme" ? "Загрузка…" : "Загрузить схему" }}
            <input
              type="file"
              accept="image/jpeg,image/png,image/webp"
              hidden
              :disabled="disabled || uploading !== ''"
              @change="onScheme"
            />
          </label>
          <button
            v-if="bulletin.scheme_url"
            class="btn"
            type="button"
            :disabled="disabled"
            @click="clearScheme"
          >
            Убрать
          </button>
        </div>
        <p v-if="!upcomingId" class="muted">Картинки можно загрузить после первого сохранения.</p>
      </div>
    </fieldset>

    <fieldset>
      <legend>Техническая информация</legend>
      <label>
        Правила и дисциплина
        <RichTextEditor v-model="bulletin.rules" :disabled="disabled" min-height="4.5rem" />
      </label>
      <label>
        Контрольное время, минут
        <input v-model="bulletin.time_limit_minutes" :disabled="disabled" />
      </label>
      <label>
        Система отметки
        <input v-model="bulletin.punching" :disabled="disabled" />
      </label>
      <label>
        Станции
        <RichTextEditor v-model="bulletin.stations" :disabled="disabled" min-height="4.5rem" />
      </label>
      <label>
        Предварительные параметры
        <RichTextEditor v-model="bulletin.course_intro" :disabled="disabled" min-height="4.5rem" />
      </label>
      <label>
        Дополнительно про дистанции
        <RichTextEditor v-model="bulletin.extra_course_notes" :disabled="disabled" min-height="4.5rem" />
      </label>
      <div class="table-wrap">
        <table class="admin-courses">
          <thead>
            <tr>
              <th>Дистанция</th>
              <th>длина</th>
              <th>кол-во КП</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in bulletin.courses" :key="index">
              <td><input v-model="row.name" :disabled="disabled" /></td>
              <td><input v-model="row.length" :disabled="disabled" /></td>
              <td><input v-model="row.controls" :disabled="disabled" /></td>
              <td>
                <button
                  class="btn"
                  type="button"
                  :disabled="disabled"
                  @click="removeCourse(index)"
                >
                  Убрать
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <button class="btn" type="button" :disabled="disabled" @click="addCourse">
        Добавить дистанцию
      </button>
      <label>
        Местность
        <RichTextEditor v-model="bulletin.terrain" :disabled="disabled" min-height="4.5rem" />
      </label>
      <label>
        Рельеф
        <RichTextEditor v-model="bulletin.relief" :disabled="disabled" min-height="4rem" />
      </label>
      <label>
        Карта
        <RichTextEditor v-model="bulletin.map_info" :disabled="disabled" min-height="4.5rem" />
      </label>
      <label>
        Особенности рисовки
        <RichTextEditor v-model="bulletin.map_notes" :disabled="disabled" min-height="4.5rem" />
      </label>
      <label>
        Границы района
        <RichTextEditor v-model="bulletin.bounds" :disabled="disabled" min-height="4rem" />
      </label>
      <label>
        Аварийный азимут
        <input v-model="bulletin.emergency_azimuth" :disabled="disabled" />
      </label>
      <label>
        Формат и печать карты
        <RichTextEditor v-model="bulletin.map_format" :disabled="disabled" min-height="4rem" />
      </label>
      <div class="admin-pair">
        <label>
          Масштаб
          <input v-model="bulletin.map_scale" :disabled="disabled" placeholder="1:4000" />
        </label>
        <label>
          Сечение рельефа
          <input v-model="bulletin.contour" :disabled="disabled" placeholder="2,5м" />
        </label>
      </div>
      <div class="bulletin-upload">
        <p>Фрагменты карты</p>
        <div v-if="bulletin.map_urls.length" class="bulletin-maps">
          <figure v-for="(url, index) in bulletin.map_urls" :key="url" class="bulletin-figure">
            <img :src="url" alt="" />
            <button class="btn" type="button" :disabled="disabled" @click="removeMap(index)">
              Убрать
            </button>
          </figure>
        </div>
        <label class="btn">
          {{ uploading === "map" ? "Загрузка…" : "Добавить фрагмент" }}
          <input
            type="file"
            accept="image/jpeg,image/png,image/webp"
            hidden
            :disabled="disabled || uploading !== ''"
            @change="onMap"
          />
        </label>
      </div>
    </fieldset>

    <fieldset>
      <legend>Опасные места</legend>
      <label>
        Текст
        <RichTextEditor v-model="bulletin.hazards" :disabled="disabled" />
      </label>
    </fieldset>

    <fieldset>
      <legend>Заявка и стоимость</legend>
      <label>
        Тип
        <input v-model="bulletin.entry_kind" :disabled="disabled" />
      </label>
      <label>
        Ссылка на регистрацию
        <input
          v-model="bulletin.registration_url"
          type="url"
          :disabled="disabled"
          placeholder="https://…"
        />
      </label>
      <p class="muted">
        Если ссылка заполнена, в бюллетене появится большая кнопка «Зарегистрироваться».
      </p>
      <label>
        Сроки, цены и детали
        <RichTextEditor v-model="bulletin.entry_details" :disabled="disabled" />
      </label>
    </fieldset>

    <fieldset>
      <legend>Контакты</legend>
      <label>
        Текст
        <RichTextEditor v-model="bulletin.contacts" :disabled="disabled" min-height="4rem" />
      </label>
    </fieldset>
  </div>
</template>
