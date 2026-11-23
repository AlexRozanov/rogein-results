<script setup lang="ts">
import { computed } from "vue";
import { hasPerm, me } from "../../auth";

const isOwner = computed(() => me.value?.roles.includes("owner") ?? false);
</script>

<template>
  <div class="admin-page">
    <h1>Организатор</h1>
    <p class="muted">{{ me?.email }} · роли: {{ me?.roles.join(", ") || "нет" }}</p>
    <div class="event-list">
      <router-link
        v-if="hasPerm('events.announce')"
        class="card event-link"
        to="/admin/calendar"
      >
        <h2>Календарь</h2>
        <p class="muted">Предстоящие старты на сайте</p>
      </router-link>
      <router-link
        v-if="hasPerm('users.manage')"
        class="card event-link"
        to="/admin/users"
      >
        <h2>Сотрудники</h2>
        <p class="muted">Приглашения в админку по email</p>
      </router-link>
      <router-link
        v-if="isOwner"
        class="card event-link"
        to="/admin/live"
      >
        <h2>Online / live</h2>
        <p class="muted">Адрес API, токен и настройка публикации</p>
      </router-link>
      <router-link class="card event-link" to="/admin/app">
        <h2>Приложение</h2>
        <p class="muted">Скачать SFR Reader (Android)</p>
      </router-link>
      <p
        v-if="!hasPerm('events.announce') && !hasPerm('users.manage')"
        class="muted"
      >
        Календарь и сотрудники недоступны — можно скачать приложение.
      </p>
    </div>
  </div>
</template>
