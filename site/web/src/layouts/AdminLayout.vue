<script setup lang="ts">
import { computed } from "vue";
import { hasPerm, logout, me } from "../auth";
import { useRouter } from "vue-router";
import AdminIcon from "../components/AdminIcon.vue";

const router = useRouter();
const isOwner = computed(
  () =>
    (me.value?.roles.includes("owner") ?? false) || hasPerm("roles.manage"),
);

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
          <router-link
            v-if="isOwner"
            to="/admin/live"
            class="admin-nav-link"
          >
            Online
          </router-link>
          <router-link to="/admin/app" class="admin-nav-link">
            Приложение
          </router-link>
          <router-link to="/admin/help" class="admin-nav-link">
            Справка
          </router-link>
        </nav>
        <div class="admin-user">
          <span class="muted">{{ me?.email }}</span>
          <router-link
            class="admin-icon-btn"
            to="/admin/password"
            title="Смена пароля"
            aria-label="Смена пароля"
          >
            <AdminIcon name="key" />
          </router-link>
          <button
            type="button"
            class="admin-icon-btn"
            title="Выйти"
            aria-label="Выйти"
            @click="onLogout"
          >
            <AdminIcon name="logout" />
          </button>
          <router-link
            class="admin-icon-btn"
            to="/"
            title="На сайт"
            aria-label="На сайт"
          >
            <AdminIcon name="external" />
          </router-link>
        </div>
      </div>
    </header>
    <main class="admin-main">
      <router-view />
    </main>
  </div>
</template>
