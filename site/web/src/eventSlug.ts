const TRANSLIT: Record<string, string> = {
  а: "a",
  б: "b",
  в: "v",
  г: "g",
  д: "d",
  е: "e",
  ё: "e",
  ж: "zh",
  з: "z",
  и: "i",
  й: "j",
  к: "k",
  л: "l",
  м: "m",
  н: "n",
  о: "o",
  п: "p",
  р: "r",
  с: "s",
  т: "t",
  у: "u",
  ф: "f",
  х: "h",
  ц: "c",
  ч: "ch",
  ш: "sh",
  щ: "sch",
  ъ: "",
  ы: "y",
  ь: "",
  э: "e",
  ю: "yu",
  я: "ya",
  і: "i",
  ї: "i",
  є: "e",
  ґ: "g",
};

export function transliterateTitle(raw: string): string {
  let out = "";
  for (const ch of raw.trim().toLowerCase()) {
    if (TRANSLIT[ch] !== undefined) {
      out += TRANSLIT[ch];
    } else if (/[a-z0-9]/.test(ch)) {
      out += ch;
    } else {
      out += "-";
    }
  }
  return out.replace(/-+/g, "-").replace(/^-|-$/g, "");
}

export function buildEventSlug(date: string, title: string): string | null {
  const iso = date.trim();
  const name = transliterateTitle(title);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(iso) || !name) return null;
  return `start-${iso}-${name}`;
}
