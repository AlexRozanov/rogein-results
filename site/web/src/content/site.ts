/** Club copy and contacts. Replace placeholders when real texts are ready. */
export const site = {
  clubName: "СК Малахит",
  tagline: "Рогейны и тренировочные старты по спортивному ориентированию",
  phone: {
    label: "Телефон",
    value: "+7 (903) 158-33-46",
    href: "tel:+70000000000",
  },
  telegram: {
    label: "Telegram",
    handle: "@malahit_sprint",
    href: "https://t.me/malahit_sprint",
  },
  aboutTitle: "О клубе",
  about:
    "[Текст] Краткое описание СК Малахит: кого объединяет клуб, какие старты проводит и для кого они открыты. Заменить в src/content/site.ts.",
  sections: [
    {
      id: "rogaine",
      title: "Рогейн",
      body: "[Текст] Что такое рогейн у Малахита: форматы, контрольное время, как готовиться. Заменить в src/content/site.ts.",
    },
    {
      id: "orient",
      title: "Спортивное ориентирование",
      body: "[Текст] Тренировочные старты и заданное направление: лес и город. Заменить в src/content/site.ts.",
    },
    {
      id: "club",
      title: "Клуб",
      body: "[Текст] Как попасть на тренировку, кому писать, где собираемся. Заменить в src/content/site.ts.",
    },
  ],
  calendarEmpty:
    "[Календарь] Здесь появятся анонсы предстоящих стартов. Заменить этот текст, когда будет первый анонс.",
};

export function sportKindLabel(kind: string | null | undefined): string {
  return kind === "orient" ? "ориентирование" : "рогейн";
}
