<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";

const props = withDefaults(
  defineProps<{
    modelValue: string;
    disabled?: boolean;
    placeholder?: string;
  }>(),
  {
    disabled: false,
    placeholder: "дд.мм.гггг",
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: string];
}>();

const MONTHS = [
  "январь",
  "февраль",
  "март",
  "апрель",
  "май",
  "июнь",
  "июль",
  "август",
  "сентябрь",
  "октябрь",
  "ноябрь",
  "декабрь",
];
const WEEKDAYS = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"];
const POPUP_WIDTH = 280;

const triggerEl = ref<HTMLButtonElement | null>(null);
const popupEl = ref<HTMLElement | null>(null);
const open = ref(false);
const viewYear = ref(new Date().getFullYear());
const viewMonth = ref(new Date().getMonth());
const popupStyle = ref<Record<string, string>>({});

const selected = computed(() => parseIso(props.modelValue));
const displayValue = computed(() => formatRu(props.modelValue));
const today = computed(() => {
  const now = new Date();
  return { year: now.getFullYear(), month: now.getMonth(), day: now.getDate() };
});

const cells = computed(() => {
  const year = viewYear.value;
  const month = viewMonth.value;
  const first = new Date(year, month, 1);
  const startPad = (first.getDay() + 6) % 7;
  const daysHere = new Date(year, month + 1, 0).getDate();
  const daysPrev = new Date(year, month, 0).getDate();
  const out: { year: number; month: number; day: number; outside: boolean }[] = [];
  for (let i = startPad; i > 0; i -= 1) {
    const prev = month === 0 ? { year: year - 1, month: 11 } : { year, month: month - 1 };
    out.push({ ...prev, day: daysPrev - i + 1, outside: true });
  }
  for (let day = 1; day <= daysHere; day += 1) {
    out.push({ year, month, day, outside: false });
  }
  const tail = (7 - (out.length % 7)) % 7;
  for (let day = 1; day <= tail; day += 1) {
    const next = month === 11 ? { year: year + 1, month: 0 } : { year, month: month + 1 };
    out.push({ ...next, day, outside: true });
  }
  return out;
});

watch(open, (isOpen) => {
  if (isOpen) {
    const current = selected.value ?? today.value;
    viewYear.value = current.year;
    viewMonth.value = current.month;
    document.addEventListener("pointerdown", onPointerDown, true);
    document.addEventListener("keydown", onDocKey);
    window.addEventListener("resize", onViewportChange);
    window.addEventListener("scroll", onViewportChange, true);
    void nextTick(placePopup);
  } else {
    detach();
  }
});

function parseIso(raw: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(raw || "").trim());
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]) - 1;
  const day = Number(match[3]);
  const date = new Date(year, month, day);
  if (date.getFullYear() !== year || date.getMonth() !== month || date.getDate() !== day) {
    return null;
  }
  return { year, month, day };
}

function toIso(year: number, month: number, day: number) {
  return `${year}-${String(month + 1).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

function formatRu(raw: string) {
  const parsed = parseIso(raw);
  if (!parsed) return "";
  return `${String(parsed.day).padStart(2, "0")}.${String(parsed.month + 1).padStart(2, "0")}.${parsed.year}`;
}

function sameDay(
  a: { year: number; month: number; day: number } | null,
  b: { year: number; month: number; day: number },
) {
  return Boolean(a && a.year === b.year && a.month === b.month && a.day === b.day);
}

function placePopup() {
  const trigger = triggerEl.value;
  if (!trigger) return;
  const rect = trigger.getBoundingClientRect();
  const height = popupEl.value?.offsetHeight || 340;
  const gap = 6;
  const left = Math.min(Math.max(8, rect.left), window.innerWidth - POPUP_WIDTH - 8);
  const below = rect.bottom + gap;
  const top =
    below + height <= window.innerHeight - 8 ? below : Math.max(8, rect.top - gap - height);
  popupStyle.value = {
    top: `${Math.round(top)}px`,
    left: `${Math.round(left)}px`,
  };
}

function toggle() {
  if (props.disabled) return;
  open.value = !open.value;
}

function close() {
  open.value = false;
}

function shiftMonth(delta: number) {
  const date = new Date(viewYear.value, viewMonth.value + delta, 1);
  viewYear.value = date.getFullYear();
  viewMonth.value = date.getMonth();
}

function pick(cell: { year: number; month: number; day: number }) {
  emit("update:modelValue", toIso(cell.year, cell.month, cell.day));
  close();
}

function onDocKey(event: KeyboardEvent) {
  if (event.key === "Escape") close();
}

function onPointerDown(event: PointerEvent) {
  const target = event.target as Node | null;
  if (!target) return;
  if (triggerEl.value?.contains(target) || popupEl.value?.contains(target)) return;
  close();
}

function onViewportChange() {
  if (open.value) placePopup();
}

function detach() {
  document.removeEventListener("pointerdown", onPointerDown, true);
  document.removeEventListener("keydown", onDocKey);
  window.removeEventListener("resize", onViewportChange);
  window.removeEventListener("scroll", onViewportChange, true);
}

onBeforeUnmount(() => {
  detach();
});
</script>

<template>
  <div class="date-input">
    <button
      ref="triggerEl"
      type="button"
      class="date-input-trigger"
      :disabled="disabled"
      :aria-expanded="open"
      aria-haspopup="dialog"
      @click.stop="toggle"
    >
      <span :class="{ muted: !displayValue }">{{ displayValue || placeholder }}</span>
    </button>
    <Teleport to="body">
      <div
        v-if="open"
        ref="popupEl"
        class="rogein-date-popup"
        role="dialog"
        aria-label="Календарь"
        :style="popupStyle"
        @mousedown.stop
        @click.stop
      >
        <div class="rogein-date-popup__head">
          <button type="button" class="rogein-date-popup__nav" aria-label="Предыдущий месяц" @click="shiftMonth(-1)">
            ‹
          </button>
          <div class="rogein-date-popup__month">{{ MONTHS[viewMonth] }} {{ viewYear }}</div>
          <button type="button" class="rogein-date-popup__nav" aria-label="Следующий месяц" @click="shiftMonth(1)">
            ›
          </button>
        </div>
        <div class="rogein-date-popup__weekdays">
          <span v-for="day in WEEKDAYS" :key="day">{{ day }}</span>
        </div>
        <div class="rogein-date-popup__days">
          <button
            v-for="cell in cells"
            :key="`${cell.year}-${cell.month}-${cell.day}-${cell.outside}`"
            type="button"
            class="rogein-date-popup__day"
            :class="{
              'is-outside': cell.outside,
              'is-selected': sameDay(selected, cell),
              'is-today': sameDay(today, cell),
            }"
            @click="pick(cell)"
          >
            {{ cell.day }}
          </button>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.date-input {
  display: block;
  width: 100%;
  max-width: 520px;
  min-width: 10.5rem;
}

.date-input-trigger {
  display: block;
  box-sizing: border-box;
  width: 100%;
  min-height: 2.1rem;
  border-radius: 6px;
  border: 1px solid #475569;
  padding: 6px 8px;
  background: #0f172a;
  color: #e2e8f0;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.date-input-trigger:disabled {
  opacity: 0.6;
  cursor: default;
}

.date-input-trigger .muted {
  color: #94a3b8;
}
</style>

<style>
.rogein-date-popup {
  position: fixed;
  z-index: 4000;
  box-sizing: border-box;
  width: 280px;
  max-width: 280px;
  padding: 12px;
  background: #fffcf6;
  color: #163a2a;
  border: 1px solid #d9d3c4;
  border-radius: 12px;
  box-shadow: 0 16px 40px rgba(22, 58, 42, 0.18);
  font-family: Inter, system-ui, sans-serif;
}

.rogein-date-popup__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.rogein-date-popup__month {
  flex: 1 1 auto;
  text-align: center;
  font-weight: 700;
  text-transform: capitalize;
  font-size: 1rem;
  line-height: 1.2;
}

.rogein-date-popup__nav {
  box-sizing: border-box;
  width: 2rem;
  min-width: 2rem;
  height: 2rem;
  padding: 0;
  border: 1px solid #d9d3c4;
  border-radius: 8px;
  background: #fff;
  color: #163a2a;
  font-size: 1.15rem;
  line-height: 1;
  cursor: pointer;
}

.rogein-date-popup__nav:hover {
  border-color: #1fae3a;
  color: #178a2d;
}

.rogein-date-popup__weekdays,
.rogein-date-popup__days {
  display: grid;
  grid-template-columns: repeat(7, 36px);
  justify-content: center;
  gap: 0;
  width: 252px;
  margin: 0 auto;
}

.rogein-date-popup__weekdays span {
  box-sizing: border-box;
  width: 36px;
  text-align: center;
  font-size: 0.75rem;
  font-weight: 700;
  color: #3d4f5c;
  padding: 6px 0;
}

.rogein-date-popup__day {
  box-sizing: border-box;
  width: 36px;
  min-width: 36px;
  max-width: 36px;
  height: 36px;
  padding: 0;
  margin: 0;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #163a2a;
  font: inherit;
  font-size: 0.9rem;
  font-weight: 600;
  line-height: 36px;
  text-align: center;
  cursor: pointer;
}

.rogein-date-popup__day:hover {
  background: #e7f6ea;
}

.rogein-date-popup__day.is-outside {
  color: #9aa7a0;
  font-weight: 500;
}

.rogein-date-popup__day.is-today:not(.is-selected) {
  box-shadow: inset 0 0 0 1.5px #1fae3a;
}

.rogein-date-popup__day.is-selected {
  background: #1fae3a;
  color: #fff;
}
</style>
