<script setup lang="ts">
import { ref } from "vue";
import {
  emptyCourse,
  type Bulletin,
} from "../bulletin";

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
        <textarea v-model="bulletin.intro" rows="3" :disabled="disabled" />
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
        <textarea v-model="bulletin.location" rows="4" :disabled="disabled" />
      </label>
      <label>
        Парковка
        <textarea v-model="bulletin.parking" rows="3" :disabled="disabled" />
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
        <textarea v-model="bulletin.rules" rows="3" :disabled="disabled" />
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
        <textarea v-model="bulletin.stations" rows="3" :disabled="disabled" />
      </label>
      <label>
        Предварительные параметры
        <textarea v-model="bulletin.course_intro" rows="3" :disabled="disabled" />
      </label>
      <label>
        Дополнительно про дистанции
        <textarea v-model="bulletin.extra_course_notes" rows="3" :disabled="disabled" />
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
        <textarea v-model="bulletin.terrain" rows="3" :disabled="disabled" />
      </label>
      <label>
        Рельеф
        <textarea v-model="bulletin.relief" rows="2" :disabled="disabled" />
      </label>
      <label>
        Карта
        <textarea v-model="bulletin.map_info" rows="3" :disabled="disabled" />
      </label>
      <label>
        Особенности рисовки
        <textarea v-model="bulletin.map_notes" rows="3" :disabled="disabled" />
      </label>
      <label>
        Границы района
        <textarea v-model="bulletin.bounds" rows="2" :disabled="disabled" />
      </label>
      <label>
        Аварийный азимут
        <input v-model="bulletin.emergency_azimuth" :disabled="disabled" />
      </label>
      <label>
        Формат и печать карты
        <textarea v-model="bulletin.map_format" rows="2" :disabled="disabled" />
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
        <textarea v-model="bulletin.hazards" rows="5" :disabled="disabled" />
      </label>
    </fieldset>

    <fieldset>
      <legend>Заявка и стоимость</legend>
      <label>
        Тип
        <input v-model="bulletin.entry_kind" :disabled="disabled" />
      </label>
      <label>
        Сроки, ссылка, цены
        <textarea v-model="bulletin.entry_details" rows="4" :disabled="disabled" />
      </label>
    </fieldset>

    <fieldset>
      <legend>Контакты</legend>
      <label>
        Текст
        <textarea v-model="bulletin.contacts" rows="2" :disabled="disabled" />
      </label>
    </fieldset>
  </div>
</template>
