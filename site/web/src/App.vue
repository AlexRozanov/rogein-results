<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import { site } from "./content/site";

const route = useRoute();
const headerRef = ref<HTMLElement | null>(null);
let headerObserver: ResizeObserver | null = null;

const nav = [
  { to: "/", label: "Главная" },
  { to: "/results", label: "Результаты" },
  { to: "/soon/calendar", label: "Календарь" },
  { to: "/soon/rogaine", label: "Рогейн" },
  { to: "/soon/orient", label: "Ориентирование" },
  { to: "/soon/club", label: "Клуб" },
];

function navActive(to: string) {
  if (to === "/") {
    return route.path === "/" && !route.hash;
  }
  if (to === "/results") {
    return route.path === "/results" || route.path.startsWith("/events/");
  }
  return route.path === to;
}

function syncHeaderHeight() {
  const height = headerRef.value?.offsetHeight ?? 0;
  document.documentElement.style.setProperty(
    "--header-h",
    `${Math.max(height, 96)}px`,
  );
}

onMounted(() => {
  syncHeaderHeight();
  if (!headerRef.value || typeof ResizeObserver === "undefined") return;
  headerObserver = new ResizeObserver(syncHeaderHeight);
  headerObserver.observe(headerRef.value);
});

onBeforeUnmount(() => {
  headerObserver?.disconnect();
  document.documentElement.style.removeProperty("--header-h");
});
</script>

<template>
  <div class="site">
    <header ref="headerRef" class="site-header">
      <div class="site-header-inner">
        <div class="header-row">
          <router-link to="/" class="brand-lockup">
            <span class="brand-logo-plate">
              <img
                class="brand-logo"
                src="/logo-malachit.png"
                width="64"
                height="64"
                alt="СК Малахит"
              />
            </span>
            <span class="brand-text">
              <span class="brand-name">{{ site.clubName }}</span>
              <span class="brand-tagline">{{ site.tagline }}</span>
            </span>
          </router-link>
          <div class="header-contacts">
            <a class="header-contact" :href="site.phone.href">{{ site.phone.value }}</a>
            <a
              class="header-contact"
              :href="site.telegram.href"
              target="_blank"
              rel="noreferrer"
            >
              {{ site.telegram.handle }}
            </a>
          </div>
        </div>
        <nav class="site-nav" aria-label="Разделы сайта">
          <router-link
            v-for="item in nav"
            :key="item.to"
            :to="item.to"
            class="site-nav-link"
            :class="{ 'is-active': navActive(item.to) }"
            active-class=""
            exact-active-class=""
            :aria-current="navActive(item.to) ? 'page' : undefined"
          >
            {{ item.label }}
          </router-link>
        </nav>
      </div>
    </header>
    <main class="shell page">
      <router-view />
    </main>
    <footer class="site-footer">
      <div class="site-footer-inner">
        <div class="footer-row">
          <div>
            <p class="footer-title">{{ site.clubName }}</p>
            <p class="muted">{{ site.tagline }}</p>
          </div>
          <div class="footer-contacts">
            <a class="footer-link" :href="site.phone.href">{{ site.phone.value }}</a>
            <a
              class="footer-link"
              :href="site.telegram.href"
              target="_blank"
              rel="noreferrer"
            >
              {{ site.telegram.handle }}
            </a>
          </div>
        </div>
        <nav class="footer-nav" aria-label="Документы и разделы">
          <router-link class="footer-link" to="/results">Результаты</router-link>
          <router-link class="footer-link" to="/soon/calendar">Календарь</router-link>
          <router-link class="footer-link" to="/privacy">
            Политика обработки персональных данных
          </router-link>
          <router-link class="footer-link" to="/terms">Пользовательское соглашение</router-link>
        </nav>
      </div>
    </footer>
  </div>
</template>
