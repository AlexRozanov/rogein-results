export type BulletinCourse = {
  name: string;
  length: string;
  controls: string;
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
  contacts: string;
};

export type UpcomingBulletin = {
  slug: string;
  title: string;
  competition_date: string | null;
  sport_kind: string;
  status: string;
  summary: string;
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
  return { name: "", length: "", controls: "" };
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
      { name: "D1", length: "", controls: "" },
      { name: "D2", length: "", controls: "" },
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
    contacts: "https://t.me/malahit_sprint +7(903)158-33-46 Вячеслав",
  };
}

function coerceCourses(rows: BulletinCourse[] | undefined): BulletinCourse[] {
  return (rows ?? []).map((row) => ({
    name: row.name ?? "",
    length: row.length ?? "",
    controls: row.controls ?? "",
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
    (row) => row.name.trim() || row.length.trim() || row.controls.trim(),
  );
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
