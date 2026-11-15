<script setup lang="ts">
import { computed } from "vue";
import {
  bulletinPlaceLine,
  filledCourses,
  googleMapsUrl,
  hasCoords,
  isHttpUrl,
  richTextHtml,
  yandexMapsUrl,
  type Bulletin,
} from "../bulletin";

const props = defineProps<{
  title: string;
  date: string | null;
  bulletin: Bulletin;
}>();

function textHtml(value: string): string {
  return richTextHtml(value);
}

const registrationUrl = computed(() => {
  const url = props.bulletin.registration_url.trim();
  return isHttpUrl(url) ? url : "";
});

const startHoursLine = computed(() => {
  const parts: string[] = [];
  if (props.bulletin.start_from || props.bulletin.start_to) {
    let line = "Время работы старта";
    if (props.bulletin.start_from) line += ` с ${props.bulletin.start_from}`;
    if (props.bulletin.start_to) line += ` до ${props.bulletin.start_to}`;
    parts.push(`${line}.`);
  }
  if (props.bulletin.finish_until) {
    parts.push(`Финиш работает до ${props.bulletin.finish_until}.`);
  }
  return parts.join(" ");
});

const mapScaleLine = computed(() => {
  const parts: string[] = [];
  if (props.bulletin.map_scale) parts.push(`Масштаб карты ${props.bulletin.map_scale}`);
  if (props.bulletin.contour) parts.push(`сечение рельефа ${props.bulletin.contour}`);
  return parts.length ? `${parts.join(", ")}.` : "";
});
</script>

<template>
  <article class="bulletin">
    <header class="bulletin-head">
      <img
        class="bulletin-logo"
        src="/logo-malachit.png"
        width="88"
        height="88"
        alt="СК Малахит"
      />
      <p v-if="bulletin.event_kind" class="bulletin-kicker">{{ bulletin.event_kind }}</p>
      <p class="bulletin-place">
        {{ bulletinPlaceLine(date, bulletin) || title }}
      </p>
      <p v-if="bulletin.discipline" class="bulletin-discipline">
        {{ bulletin.discipline }}
      </p>
      <h1 class="bulletin-doc-title">Информационный бюллетень</h1>
      <p v-if="registrationUrl" class="bulletin-register-wrap">
        <a
          class="bulletin-register-btn"
          :href="registrationUrl"
          target="_blank"
          rel="noreferrer"
        >
          Зарегистрироваться
        </a>
      </p>
    </header>

    <section>
      <h2>Дата, место и программа</h2>
      <div v-if="bulletin.intro" class="bulletin-text" v-html="textHtml(bulletin.intro)" />
      <p v-if="hasCoords(bulletin)">
        Координаты расположения старта / финиша:
        {{ bulletin.lat }}, {{ bulletin.lon }}
      </p>
      <p v-if="hasCoords(bulletin)" class="bulletin-map-links">
        <a :href="yandexMapsUrl(bulletin.lat, bulletin.lon)" target="_blank" rel="noreferrer">
          яндекс карты
        </a>
        <a :href="googleMapsUrl(bulletin.lat, bulletin.lon)" target="_blank" rel="noreferrer">
          гугл карты
        </a>
      </p>
      <p v-if="startHoursLine">{{ startHoursLine }}</p>
      <div v-if="bulletin.location" class="bulletin-text" v-html="textHtml(bulletin.location)" />
      <div v-if="bulletin.parking" class="bulletin-text" v-html="textHtml(bulletin.parking)" />
      <figure v-if="bulletin.scheme_url" class="bulletin-figure">
        <img :src="bulletin.scheme_url" alt="Схема расположения старта" />
      </figure>
    </section>

    <section
      v-if="
        bulletin.rules ||
        bulletin.time_limit_minutes ||
        bulletin.punching ||
        bulletin.stations ||
        bulletin.course_intro ||
        filledCourses(bulletin).length ||
        bulletin.extra_course_notes ||
        bulletin.terrain ||
        bulletin.relief ||
        bulletin.map_info ||
        bulletin.bounds ||
        bulletin.emergency_azimuth ||
        bulletin.map_format ||
        bulletin.map_scale ||
        bulletin.contour ||
        bulletin.map_notes ||
        bulletin.map_urls.length
      "
    >
      <h2>Техническая информация</h2>
      <div v-if="bulletin.rules || bulletin.time_limit_minutes" class="bulletin-text">
        <div v-if="bulletin.rules" v-html="textHtml(bulletin.rules)" />
        <p v-if="bulletin.time_limit_minutes">
          Контрольное время — {{ bulletin.time_limit_minutes }} минут.
        </p>
      </div>
      <p v-if="bulletin.punching">
        <strong>Система отметки:</strong> {{ bulletin.punching }}
      </p>
      <div v-if="bulletin.stations" class="bulletin-text" v-html="textHtml(bulletin.stations)" />
      <div v-if="bulletin.course_intro" class="bulletin-text">
        <strong>Предварительные параметры:</strong>
        <div v-html="textHtml(bulletin.course_intro)" />
      </div>
      <div
        v-if="bulletin.extra_course_notes"
        class="bulletin-text"
        v-html="textHtml(bulletin.extra_course_notes)"
      />
      <table v-if="filledCourses(bulletin).length" class="bulletin-table">
        <thead>
          <tr>
            <th>Дистанция</th>
            <th>длина</th>
            <th>кол-во КП</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in filledCourses(bulletin)" :key="index">
            <td>{{ row.name }}</td>
            <td>{{ row.length }}</td>
            <td>{{ row.controls }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="bulletin.terrain" class="bulletin-text">
        <strong>Местность:</strong>
        <div v-html="textHtml(bulletin.terrain)" />
      </div>
      <div v-if="bulletin.relief" class="bulletin-text">
        <strong>Рельеф:</strong>
        <div v-html="textHtml(bulletin.relief)" />
      </div>
      <div v-if="bulletin.map_info" class="bulletin-text">
        <strong>Карта:</strong>
        <div v-html="textHtml(bulletin.map_info)" />
      </div>
      <div
        v-if="bulletin.map_notes"
        class="bulletin-text"
        v-html="textHtml(bulletin.map_notes)"
      />
      <div v-if="bulletin.bounds" class="bulletin-text">
        <span>Район ограничен:</span>
        <div v-html="textHtml(bulletin.bounds)" />
        <p v-if="bulletin.emergency_azimuth">
          Аварийный азимут — {{ bulletin.emergency_azimuth }}.
        </p>
      </div>
      <p v-else-if="bulletin.emergency_azimuth">
        Аварийный азимут — {{ bulletin.emergency_azimuth }}.
      </p>
      <div
        v-if="bulletin.map_format"
        class="bulletin-text"
        v-html="textHtml(bulletin.map_format)"
      />
      <p v-if="mapScaleLine">{{ mapScaleLine }}</p>
      <div v-if="bulletin.map_urls.length" class="bulletin-maps">
        <figure v-for="url in bulletin.map_urls" :key="url" class="bulletin-figure">
          <img :src="url" alt="Фрагмент карты" />
        </figure>
      </div>
    </section>

    <section v-if="bulletin.hazards">
      <h2>Опасные места</h2>
      <div class="bulletin-text" v-html="textHtml(bulletin.hazards)" />
    </section>

    <section v-if="bulletin.entry_kind || bulletin.entry_details || registrationUrl">
      <h2>Заявка и стоимость участия</h2>
      <p v-if="registrationUrl" class="bulletin-register-wrap">
        <a
          class="bulletin-register-btn"
          :href="registrationUrl"
          target="_blank"
          rel="noreferrer"
        >
          Зарегистрироваться
        </a>
      </p>
      <p v-if="bulletin.entry_kind">
        <strong>Заявка и стоимость участия:</strong> {{ bulletin.entry_kind }}.
      </p>
      <div
        v-if="bulletin.entry_details"
        class="bulletin-text"
        v-html="textHtml(bulletin.entry_details)"
      />
    </section>

    <section v-if="bulletin.contacts">
      <h2>Контакты</h2>
      <div class="bulletin-text" v-html="textHtml(bulletin.contacts)" />
    </section>
  </article>
</template>
