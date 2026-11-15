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
        <p class="muted">Анонсы предстоящих стартов на сайте</p>
      </router-link>
      <p v-else class="muted">Нет разделов, к которым есть доступ.</p>
    </div>
  </div>
</template>
