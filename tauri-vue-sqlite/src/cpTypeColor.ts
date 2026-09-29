/** Color of a CP marker from its legend type, same on map, legends and courses. */
export function cpTypeColor(typeName: string): string {
  const n = String(typeName || "").toLowerCase();
  if (n.includes("вод")) return "#38bdf8";
  if (n.includes("смеш")) return "#c084fc";
  if (n.includes("сух") || n.includes("земля") || n.includes("пеш")) return "#fb923c";
  if (!n.trim()) return "#94a3b8";
  let hash = 0;
  for (let i = 0; i < n.length; i += 1) hash = (hash * 31 + n.charCodeAt(i)) >>> 0;
  const hue = hash % 360;
  return `hsl(${hue} 65% 55%)`;
}
