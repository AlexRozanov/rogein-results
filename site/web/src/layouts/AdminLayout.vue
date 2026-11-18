<script setup lang="ts">
import { hasPerm, logout, me } from "../auth";
import { useRouter } from "vue-router";

const router = useRouter();

async function onLogout() {
  await logout();
  await router.push("/admin/login");
}
</script>

<template>
  <div class="admin">
    <header class="admin-header">
      <div class="admin-header-inner">
        <router-link to="/admin" class="admin-brand">Админка организатора</router-link>
        <nav class="admin-nav" aria-label="Админка">
          <router-link
            v-if="hasPerm('events.announce')"
            to="/admin/calendar"
            class="admin-nav-link"
          >
            Календарь
          </router-link>
          <router-link
            v-if="hasPerm('users.manage')"
            to="/admin/users"
            class="admin-nav-link"
          >
            Сотрудники
          </router-link>
        </nav>
        <div class="admin-user">
          <span class="muted">{{ me?.email }}</span>
          <router-link class="admin-nav-link" to="/admin/password">Пароль</router-link>
          <button type="button" class="btn btn-quiet" @click="onLogout">Выйти</button>
          <router-link class="admin-nav-link" to="/">На сайт</router-link>
        </div>
      </div>
    </header>
    <main class="admin-main">
      <router-view />
    </main>
  </div>
</template>
