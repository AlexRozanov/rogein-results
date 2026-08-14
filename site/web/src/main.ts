import { createApp } from "vue";
import { createRouter, createWebHistory } from "vue-router";
import App from "./App.vue";
import EventListPage from "./pages/EventListPage.vue";
import EventPage from "./pages/EventPage.vue";
import ParticipantPage from "./pages/ParticipantPage.vue";
import "./style.css";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", component: EventListPage },
    { path: "/events/:slug", component: EventPage },
    { path: "/events/:slug/p/:sourceId", component: ParticipantPage },
  ],
});

createApp(App).use(router).mount("#app");
