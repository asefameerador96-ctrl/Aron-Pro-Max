// Typed parsing and validation of config values against the registry (docs/24 s9): the portal refuses what the
// registry would refuse, before a request is made. The API validates again; this only gives a fast, exact message.
import type { ConfigBounds, ConfigKey } from "./types";

export type ValueType = ConfigKey["value_type"];
export type ParseResult = { ok: true; value: unknown } | { ok: false; code: "required" | "invalid" | "too_small" | "too_big" | "not_allowed" | "too_many" };

/** Bounds the registry seed leaves open but the meaning fixes (ISO weekdays). */
const KEY_BOUNDS: Record<string, ConfigBounds> = { "cfg.calendar.weekend_days": { min: 1, max: 7 } };
const NUMBER = /^-?\d+(\.\d+)?$/;
const INTEGER = /^-?\d+$/;

function inRange(n: number, b: ConfigBounds | null | undefined): ParseResult | null {
  if (b?.min !== undefined && b.min !== null && n < b.min) return { ok: false, code: "too_small" };
  if (b?.max !== undefined && b.max !== null && n > b.max) return { ok: false, code: "too_big" };
  return null;
}

/** Parse the text of an input into the JSON value of a key of type `type`. */
export function parseConfigInput(type: ValueType, raw: string, bounds?: ConfigBounds | null, keyName?: string): ParseResult {
  if (keyName && KEY_BOUNDS[keyName]) bounds = { ...bounds, ...KEY_BOUNDS[keyName] };
  const text = raw.trim();
  if (text === "" && type !== "bool") return { ok: false, code: "required" };
  switch (type) {
    case "int":
    case "money_mtk": {
      if (!INTEGER.test(text)) return { ok: false, code: "invalid" };
      const n = Number(text);
      if (!Number.isSafeInteger(n)) return { ok: false, code: "too_big" };
      return inRange(n, bounds) ?? { ok: true, value: n };
    }
    case "number":
    case "pct": {
      if (!NUMBER.test(text)) return { ok: false, code: "invalid" };
      const n = Number(text);
      if (!Number.isFinite(n)) return { ok: false, code: "too_big" };
      if (type === "pct" && (n < 0 || n > 100)) return { ok: false, code: n < 0 ? "too_small" : "too_big" };
      return inRange(n, bounds) ?? { ok: true, value: n };
    }
    case "bool":
      return { ok: true, value: text === "true" };
    case "time": {
      const m = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(text);
      return m ? { ok: true, value: text } : { ok: false, code: "invalid" };
    }
    case "url":
      return /^https:\/\/[^\s]{3,}$/.test(text) ? { ok: true, value: text } : { ok: false, code: "invalid" };
    case "enum":
      if (bounds?.enum && !bounds.enum.includes(text)) return { ok: false, code: "not_allowed" };
      return { ok: true, value: text };
    case "text":
      return text.length > 4000 ? { ok: false, code: "too_big" } : { ok: true, value: text };
    case "list": {
      const parts = text.startsWith("[") ? safeJson(text) : text.split(",").map((p) => p.trim()).filter((p) => p !== "");
      if (!Array.isArray(parts)) return { ok: false, code: "invalid" };
      const items = parts.map((p) => (typeof p === "string" && NUMBER.test(p) ? Number(p) : p));
      if (new Set(items.map(String)).size !== items.length) return { ok: false, code: "invalid" };
      if (!bounds?.enum && !items.every((it) => typeof it === "number" && Number.isInteger(it))) return { ok: false, code: "invalid" };
      if (bounds?.max_items !== undefined && bounds.max_items !== null && items.length > bounds.max_items) return { ok: false, code: "too_many" };
      for (const it of items) if (typeof it === "number") {
        const r = inRange(it, bounds);
        if (r) return r;
      }
      if (bounds?.enum) for (const it of items) if (!bounds.enum.includes(String(it))) return { ok: false, code: "not_allowed" };
      return { ok: true, value: items };
    }
    case "json": {
      const v = safeJson(text);
      const shape = keyName ? jsonShape(keyName, v) : true;
      if (!shape) return { ok: false, code: "invalid" };
      return v !== undefined && v !== null && typeof v === "object" ? { ok: true, value: v } : { ok: false, code: "invalid" };
    }
  }
}

const FLAVOURS = ["sr", "amo", "tso"];
const posInt = (x: unknown) => typeof x === "number" && Number.isSafeInteger(x) && x >= 1 && x <= 2_100_000_000;
/** Shape rules of json-valued keys the registry cannot express (docs/24 s4: per-flavour version codes). */
function jsonShape(keyName: string, v: unknown): boolean {
  if (keyName !== "cfg.release.min_version_code" && keyName !== "cfg.release.blocked_version_codes") return true;
  if (v === null || typeof v !== "object" || Array.isArray(v)) return false;
  const o = v as Record<string, unknown>;
  if (!Object.keys(o).every((k) => FLAVOURS.includes(k))) return false;
  if (keyName === "cfg.release.min_version_code") return FLAVOURS.every((f) => posInt(o[f]));
  return Object.values(o).every((x) => Array.isArray(x) && x.length <= 100 && x.every(posInt));
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

/** Text shown in an input for a stored value. */
export function configInputText(v: unknown): string {
  if (v === null || v === undefined) return "";
  if (Array.isArray(v) && v.every((x) => typeof x === "string" || typeof x === "number")) return v.join(", ");
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}

export const RISK_LABEL_KEYS = ["cfgc.risk.0", "cfgc.risk.1", "cfgc.risk.2", "cfgc.risk.3"] as const;
