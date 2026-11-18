<script setup lang="ts">
import { hasPerm, me } from "../../auth";
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
      <p
        v-if="!hasPerm('events.announce') && !hasPerm('users.manage')"
        class="muted"
      >
        Нет разделов, к которым есть доступ.
      </p>
    </div>
  </div>
</template>
