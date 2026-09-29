import { createApp } from "vue";
import { createRouter, createWebHistory } from "vue-router";
import App from "./App.vue";
import ComingSoonPage from "./pages/ComingSoonPage.vue";
import EventListPage from "./pages/EventListPage.vue";
import EventPage from "./pages/EventPage.vue";
import HomePage from "./pages/HomePage.vue";
import LegalPage from "./pages/LegalPage.vue";
import ParticipantPage from "./pages/ParticipantPage.vue";
import "./style.css";

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior(to) {
    if (to.hash) {
      return { el: to.hash, behavior: "smooth" };
    }
    return { top: 0 };
  },
  routes: [
    { path: "/", component: HomePage },
    { path: "/results", component: EventListPage },
    { path: "/calendar", redirect: "/soon/calendar" },
    { path: "/soon/:section?", component: ComingSoonPage },
    { path: "/events", redirect: "/results" },
    { path: "/events/:slug", component: EventPage },
    { path: "/events/:slug/p/:sourceId", component: ParticipantPage },
    { path: "/privacy", component: LegalPage, props: { doc: "privacy" } },
    { path: "/terms", component: LegalPage, props: { doc: "terms" } },
  ],
});

createApp(App).use(router).mount("#app");
