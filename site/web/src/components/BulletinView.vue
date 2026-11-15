<script setup lang="ts">
import { computed } from "vue";
import {
  bulletinPlaceLine,
  filledCourses,
  googleMapsUrl,
  hasCoords,
  linkify,
  yandexMapsUrl,
  type Bulletin,
} from "../bulletin";

const props = defineProps<{
  title: string;
  date: string | null;
  bulletin: Bulletin;
}>();

function textHtml(value: string): string {
  return linkify(value).replace(/\n/g, "<br>");
}

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
    </header>

    <section>
      <h2>Дата, место и программа</h2>
      <p v-if="bulletin.intro" class="bulletin-text" v-html="textHtml(bulletin.intro)" />
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
      <p v-if="bulletin.location" class="bulletin-text" v-html="textHtml(bulletin.location)" />
      <p v-if="bulletin.parking" class="bulletin-text" v-html="textHtml(bulletin.parking)" />
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
      <p v-if="bulletin.rules || bulletin.time_limit_minutes">
        <span v-if="bulletin.rules">{{ bulletin.rules }}</span>
        <template v-if="bulletin.time_limit_minutes">
          Контрольное время — {{ bulletin.time_limit_minutes }} минут.
        </template>
      </p>
      <p v-if="bulletin.punching">
        <strong>Система отметки:</strong> {{ bulletin.punching }}
      </p>
      <p v-if="bulletin.stations">{{ bulletin.stations }}</p>
      <p v-if="bulletin.course_intro">
        <strong>Предварительные параметры:</strong> {{ bulletin.course_intro }}
      </p>
      <p
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
      <p v-if="bulletin.terrain">
        <strong>Местность:</strong> {{ bulletin.terrain }}
      </p>
      <p v-if="bulletin.relief">
        <strong>Рельеф:</strong> {{ bulletin.relief }}
      </p>
      <p v-if="bulletin.map_info">
        <strong>Карта:</strong> {{ bulletin.map_info }}
      </p>
      <p
        v-if="bulletin.map_notes"
        class="bulletin-text"
        v-html="textHtml(bulletin.map_notes)"
      />
      <p v-if="bulletin.bounds">
        Район ограничен: {{ bulletin.bounds }}
        <template v-if="bulletin.emergency_azimuth">
          Аварийный азимут — {{ bulletin.emergency_azimuth }}.
        </template>
      </p>
      <p v-else-if="bulletin.emergency_azimuth">
        Аварийный азимут — {{ bulletin.emergency_azimuth }}.
      </p>
      <p v-if="bulletin.map_format">{{ bulletin.map_format }}</p>
      <p v-if="mapScaleLine">{{ mapScaleLine }}</p>
      <div v-if="bulletin.map_urls.length" class="bulletin-maps">
        <figure v-for="url in bulletin.map_urls" :key="url" class="bulletin-figure">
          <img :src="url" alt="Фрагмент карты" />
        </figure>
      </div>
    </section>

    <section v-if="bulletin.hazards">
      <h2>Опасные места</h2>
      <p class="bulletin-text" v-html="textHtml(bulletin.hazards)" />
    </section>

    <section v-if="bulletin.entry_kind || bulletin.entry_details">
      <h2>Заявка и стоимость участия</h2>
      <p v-if="bulletin.entry_kind">
        <strong>Заявка и стоимость участия:</strong> {{ bulletin.entry_kind }}.
      </p>
      <p
        v-if="bulletin.entry_details"
        class="bulletin-text"
        v-html="textHtml(bulletin.entry_details)"
      />
    </section>

    <section v-if="bulletin.contacts">
      <h2>Контакты</h2>
      <p class="bulletin-text" v-html="textHtml(bulletin.contacts)" />
    </section>
  </article>
</template>
