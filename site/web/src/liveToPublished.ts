import type { RouteLocationNormalizedLoaded, Router } from "vue-router";

export function isPublishedGone(res: Response): boolean {
  return res.status === 410;
}

export function replaceWithPublished(router: Router, route: RouteLocationNormalizedLoaded) {
  const slug = String(route.params.slug ?? "");
  const sourceId = route.params.sourceId;
  const path =
    sourceId != null && String(sourceId) !== ""
      ? `/events/${slug}/p/${String(sourceId)}`
      : `/events/${slug}`;
  return router.replace({ path, query: route.query, hash: route.hash });
}
