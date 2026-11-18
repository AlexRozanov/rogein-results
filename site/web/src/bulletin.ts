export type BulletinCourse = {
  name: string;
  length: string;
  controls: string;
  scale: string;
};

export type Bulletin = {
  event_kind: string;
  district: string;
  venue: string;
  discipline: string;
  intro: string;
  lat: string;
  lon: string;
  start_from: string;
  start_to: string;
  finish_until: string;
  location: string;
  parking: string;
  scheme_url: string;
  rules: string;
  time_limit_minutes: string;
  punching: string;
  stations: string;
  course_intro: string;
  courses: BulletinCourse[];
  extra_course_notes: string;
  terrain: string;
  relief: string;
  map_info: string;
  bounds: string;
  emergency_azimuth: string;
  map_format: string;
  map_scale: string;
  contour: string;
  map_notes: string;
  map_urls: string[];
  hazards: string;
  entry_kind: string;
  entry_details: string;
  registration_url: string;
  contacts: string;
};

export type UpcomingBulletin = {
  slug: string;
  title: string;
  competition_date: string | null;
  sport_kind: string;
  status: string;
  summary: string;
  bulletin_ready: boolean;
  bulletin: Bulletin;
};

const MONTHS_RU = [
  "января",
  "февраля",
  "марта",
  "апреля",
  "мая",
  "июня",
  "июля",
  "августа",
  "сентября",
  "октября",
  "ноября",
  "декабря",
];

export function emptyCourse(): BulletinCourse {
  return { name: "", length: "", controls: "", scale: "" };
}

export function defaultOrientBulletin(): Bulletin {
  return {
    event_kind: "Открытая тренировка.",
    district: "",
    venue: "",
    discipline: "парковый спринт",
    intro: "",
    lat: "",
    lon: "",
    start_from: "12:00",
    start_to: "14:00",
    finish_until: "15:30",
    location: "",
    parking: "",
    scheme_url: "",
    rules:
      "Тренировка проходит в соответствии с российскими правилами вида спорта «спортивное ориентирование» в дисциплине «ориентирование бегом».",
    time_limit_minutes: "90",
    punching: "SFR, аренда чипа на старте 50 руб.",
    stations: "Станции работают в контактном режиме.",
    course_intro:
      "Дистанция заданного направления. Доступно прохождение двух дистанций по цене одной, в случае наличия свободных карт.",
    courses: [
      { name: "D1", length: "", controls: "", scale: "" },
      { name: "D2", length: "", controls: "", scale: "" },
    ],
    extra_course_notes: "",
    terrain: "",
    relief: "",
    map_info: "",
    bounds: "",
    emergency_azimuth: "180 градусов (юг)",
    map_format:
      "Формат карты — A4. Карта напечатана типографским способом на бумаге повышенной плотности, не заламинирована.",
    map_scale: "",
    contour: "",
    map_notes: "",
    map_urls: [],
    hazards: "",
    entry_kind: "динамическая",
    entry_details: "",
    registration_url: "",
    contacts: "https://t.me/malahit_sprint +7(903)158-33-46 Вячеслав",
  };
}

function coerceCourses(rows: BulletinCourse[] | undefined): BulletinCourse[] {
  return (rows ?? []).map((row) => ({
    name: row.name ?? "",
    length: row.length ?? "",
    controls: row.controls ?? "",
    scale: row.scale ?? "",
  }));
}

export function emptyBulletin(): Bulletin {
  return {
    event_kind: "",
    district: "",
    venue: "",
    discipline: "",
    intro: "",
    lat: "",
    lon: "",
    start_from: "",
    start_to: "",
    finish_until: "",
    location: "",
    parking: "",
    scheme_url: "",
    rules: "",
    time_limit_minutes: "",
    punching: "",
    stations: "",
    course_intro: "",
    courses: [],
    extra_course_notes: "",
    terrain: "",
    relief: "",
    map_info: "",
    bounds: "",
    emergency_azimuth: "",
    map_format: "",
    map_scale: "",
    contour: "",
    map_notes: "",
    map_urls: [],
    hazards: "",
    entry_kind: "",
    entry_details: "",
    registration_url: "",
    contacts: "",
  };
}

export function coerceBulletin(raw: Partial<Bulletin> | null | undefined): Bulletin {
  const base = emptyBulletin();
  if (!raw) return base;
  return {
    ...base,
    ...raw,
    courses: coerceCourses(raw.courses),
    map_urls: (raw.map_urls ?? []).filter(Boolean),
  };
}

export function normalizeBulletin(raw: Partial<Bulletin> | null | undefined): Bulletin {
  const base = defaultOrientBulletin();
  const incoming = coerceBulletin(raw);
  const blank = !Object.entries(incoming).some(([key, value]) => {
    if (key === "courses") return incoming.courses.length > 0;
    if (key === "map_urls") return incoming.map_urls.length > 0;
    return String(value).trim().length > 0;
  });
  if (blank) return base;
  return {
    ...base,
    ...incoming,
    courses: incoming.courses.length ? incoming.courses : base.courses,
  };
}

export function formatDateRu(iso: string | null | undefined): string {
  if (!iso) return "";
  const [year, month, day] = iso.split("-").map(Number);
  if (!year || !month || !day) return iso;
  return `${day} ${MONTHS_RU[month - 1]} ${year}`;
}

export function bulletinPlaceLine(
  dateIso: string | null | undefined,
  bulletin: Bulletin,
): string {
  const parts = [
    formatDateRu(dateIso),
    bulletin.district.trim(),
    bulletin.venue.trim(),
  ].filter(Boolean);
  return parts.join(", ");
}

export function yandexMapsUrl(lat: string, lon: string): string {
  return `https://yandex.ru/maps/?pt=${encodeURIComponent(lon)},${encodeURIComponent(lat)}&z=16`;
}

export function googleMapsUrl(lat: string, lon: string): string {
  return `https://www.google.com/maps?q=${encodeURIComponent(lat)},${encodeURIComponent(lon)}`;
}

export function hasCoords(bulletin: Bulletin): boolean {
  return Boolean(bulletin.lat.trim() && bulletin.lon.trim());
}

export function filledCourses(bulletin: Bulletin): BulletinCourse[] {
  return bulletin.courses.filter(
    (row) =>
      row.name.trim() ||
      row.length.trim() ||
      row.controls.trim() ||
      row.scale.trim(),
  );
}

export function coursesHaveScale(bulletin: Bulletin): boolean {
  return filledCourses(bulletin).some((row) => row.scale.trim());
}

export function snapshotBulletin(bulletin: Bulletin): Bulletin {
  return coerceBulletin(JSON.parse(JSON.stringify(bulletin)) as Partial<Bulletin>);
}

/** Same rule as Android EventStore.liveViewerUrl / site route /live/:slug */
export function liveResultsPath(slug: string | null | undefined): string {
  const id = (slug ?? "").trim();
  return id ? `/live/${encodeURIComponent(id)}` : "";
}

export type ContactBlocks = {
  telegram: { href: string } | null;
  phone: { href: string; number: string; name: string } | null;
};

function stripTags(value: string): string {
  return value
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/p>/gi, "\n")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/\s+/g, " ")
    .trim();
}

function normalizePhoneHref(raw: string): string {
  const digits = raw.replace(/[^\d+]/g, "");
  if (digits.startsWith("+")) return `tel:${digits}`;
  if (digits.startsWith("8") && digits.length === 11) return `tel:+7${digits.slice(1)}`;
  if (digits.startsWith("7") && digits.length === 11) return `tel:+${digits}`;
  return `tel:${digits}`;
}

/** Telegram + phone(+name) blocks from free-text contacts. */
export function parseContactBlocks(raw: string): ContactBlocks {
  const text = stripTags(raw);
  if (!text) return { telegram: null, phone: null };

  let rest = text;
  let telegram: ContactBlocks["telegram"] = null;
  let phone: ContactBlocks["phone"] = null;

  const tgMatch = rest.match(
    /(?:https?:\/\/)?(?:t\.me|telegram\.me)\/([A-Za-z0-9_]{4,})|(@[A-Za-z0-9_]{4,})/i,
  );
  if (tgMatch) {
    const handle = (tgMatch[1] || tgMatch[2] || "").replace(/^@/, "");
    if (handle) {
      telegram = { href: `https://t.me/${handle}` };
      rest = `${rest.slice(0, tgMatch.index ?? 0)} ${rest.slice((tgMatch.index ?? 0) + tgMatch[0].length)}`.trim();
    }
  }

  const phoneMatch = rest.match(
    /(?:\+?7|8)[\s\-()]*(?:\d[\s\-()]*){10}|\+\d[\d\s\-()]{8,18}\d/,
  );
  if (phoneMatch) {
    const number = phoneMatch[0].replace(/\s+/g, " ").trim();
    const after = rest.slice((phoneMatch.index ?? 0) + phoneMatch[0].length).trim();
    const before = rest.slice(0, phoneMatch.index ?? 0).trim();
    const name = [before, after]
      .filter(Boolean)
      .join(" ")
      .replace(/^[,;.\-–—]+|[,;.\-–—]+$/g, "")
      .trim();
    phone = {
      href: normalizePhoneHref(number),
      number,
      name,
    };
  }

  return { telegram, phone };
}

export function hasContactBlocks(raw: string): boolean {
  const blocks = parseContactBlocks(raw);
  return Boolean(blocks.telegram || blocks.phone);
}

export function linkify(text: string): string {
  const escaped = text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
  return escaped.replace(
    /https?:\/\/[^\s<]+/g,
    (url) => `<a href="${url}" target="_blank" rel="noreferrer">${url}</a>`,
  );
}

const RICH_TAGS = new Set([
  "P",
  "BR",
  "STRONG",
  "B",
  "EM",
  "I",
  "U",
  "UL",
  "OL",
  "LI",
  "A",
  "MARK",
  "SPAN",
]);

const SAFE_COLOR =
  /^(#[0-9a-f]{3,8}|rgb\(\s*\d{1,3}\s*,\s*\d{1,3}\s*,\s*\d{1,3}\s*\)|rgba\(\s*\d{1,3}\s*,\s*\d{1,3}\s*,\s*\d{1,3}\s*,\s*(0|0?\.\d+|1)\s*\))$/i;

function looksLikeHtml(value: string): boolean {
  return /<\/?(p|br|strong|b|em|i|u|ul|ol|li|a|mark|span)\b/i.test(value);
}

function safeColor(value: string | null | undefined): string {
  const color = (value || "").trim();
  return SAFE_COLOR.test(color) ? color : "";
}

/** Legacy plain text → HTML TipTap can load; pass through existing HTML. */
export function plainToEditorHtml(value: string): string {
  const text = value ?? "";
  if (!text.trim()) return "";
  if (looksLikeHtml(text)) return text;
  return text
    .split(/\n{2,}/)
    .map((block) => {
      const lines = block
        .split("\n")
        .map((line) =>
          line
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;"),
        )
        .join("<br>");
      return `<p>${lines || "<br>"}</p>`;
    })
    .join("");
}

export function sanitizeHtml(html: string): string {
  if (typeof DOMParser === "undefined") return html;
  const doc = new DOMParser().parseFromString(html, "text/html");

  const walk = (node: Node) => {
    const children = Array.from(node.childNodes);
    for (const child of children) {
      if (child.nodeType === Node.ELEMENT_NODE) {
        const el = child as HTMLElement;
        const tag = el.tagName;
        if (!RICH_TAGS.has(tag)) {
          while (el.firstChild) {
            node.insertBefore(el.firstChild, el);
          }
          node.removeChild(el);
          continue;
        }
        if (tag === "A") {
          const href = (el.getAttribute("href") || "").trim();
          [...el.attributes].forEach((attr) => el.removeAttribute(attr.name));
          if (/^https?:\/\//i.test(href) || href.startsWith("/")) {
            el.setAttribute("href", href);
            el.setAttribute("target", "_blank");
            el.setAttribute("rel", "noreferrer");
          }
        } else if (tag === "MARK") {
          const bg =
            safeColor(el.getAttribute("data-color")) ||
            safeColor(el.style.backgroundColor);
          [...el.attributes].forEach((attr) => el.removeAttribute(attr.name));
          if (bg) {
            el.setAttribute("data-color", bg);
            el.style.backgroundColor = bg;
          }
        } else if (tag === "SPAN") {
          const color = safeColor(el.style.color);
          [...el.attributes].forEach((attr) => el.removeAttribute(attr.name));
          if (color) {
            el.style.color = color;
          } else {
            while (el.firstChild) {
              node.insertBefore(el.firstChild, el);
            }
            node.removeChild(el);
            continue;
          }
        } else {
          [...el.attributes].forEach((attr) => el.removeAttribute(attr.name));
        }
        walk(el);
      } else if (child.nodeType === Node.COMMENT_NODE) {
        node.removeChild(child);
      }
    }
  };

  walk(doc.body);
  return doc.body.innerHTML;
}

/** Render bulletin multiline field: rich HTML or legacy plain text. */
export function richTextHtml(value: string): string {
  const text = value ?? "";
  if (!text.trim()) return "";
  if (looksLikeHtml(text)) return sanitizeHtml(text);
  return linkify(text).replace(/\n/g, "<br>");
}

export function isHttpUrl(value: string): boolean {
  const url = value.trim();
  return /^https?:\/\//i.test(url);
}
