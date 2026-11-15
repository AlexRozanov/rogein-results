import { createApp } from "vue";
import { createRouter, createWebHistory } from "vue-router";
import App from "./App.vue";
import { loadMe, me, meLoaded } from "./auth";
import AdminLayout from "./layouts/AdminLayout.vue";
import SiteLayout from "./layouts/SiteLayout.vue";
import AdminHomePage from "./pages/admin/AdminHomePage.vue";
import CalendarEditPage from "./pages/admin/CalendarEditPage.vue";
import CalendarListPage from "./pages/admin/CalendarListPage.vue";
import LoginPage from "./pages/admin/LoginPage.vue";
import BulletinPage from "./pages/BulletinPage.vue";
import CalendarPage from "./pages/CalendarPage.vue";
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
    {
      path: "/",
      component: SiteLayout,
      children: [
        { path: "", component: HomePage },
        { path: "results", component: EventListPage },
        { path: "calendar", component: CalendarPage },
        { path: "calendar/:slug", component: BulletinPage },
        { path: "soon/calendar", redirect: "/calendar" },
        { path: "soon/:section?", component: ComingSoonPage },
        { path: "events", redirect: "/results" },
        { path: "events/:slug", component: EventPage },
        { path: "events/:slug/p/:sourceId", component: ParticipantPage },
        { path: "live/:slug", component: EventPage },
        { path: "live/:slug/p/:sourceId", component: ParticipantPage },
        { path: "privacy", component: LegalPage, props: { doc: "privacy" } },
        { path: "terms", component: LegalPage, props: { doc: "terms" } },
      ],
    },
    { path: "/admin/login", component: LoginPage },
    {
      path: "/admin",
      component: AdminLayout,
      meta: { requiresAuth: true },
      children: [
        { path: "", component: AdminHomePage },
        {
          path: "calendar",
          component: CalendarListPage,
          meta: { perm: "events.announce" },
        },
        {
          path: "calendar/new",
          name: "admin-calendar-new",
          component: CalendarEditPage,
          meta: { perm: "events.announce" },
        },
        {
          path: "calendar/:id",
          component: CalendarEditPage,
          meta: { perm: "events.announce" },
        },
      ],
    },
  ],
});

router.beforeEach(async (to) => {
  const needsAuth = to.matched.some((record) => record.meta.requiresAuth);
  if (!needsAuth && to.path !== "/admin/login") {
    return true;
  }
  if (!meLoaded.value) {
    await loadMe();
  }
  if (to.path === "/admin/login") {
    if (me.value) {
      return { path: "/admin" };
    }
    return true;
  }
  if (!me.value) {
    return { path: "/admin/login", query: { next: to.fullPath } };
  }
  const perm = to.matched
    .map((record) => record.meta.perm as string | undefined)
    .find((value) => value);
  if (perm && !me.value.permissions.includes(perm)) {
    return { path: "/admin" };
  }
  return true;
});

createApp(App).use(router).mount("#app");
