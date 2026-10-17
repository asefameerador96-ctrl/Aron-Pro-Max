"use client";
import Link from "next/link";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { MessageKey } from "@/lib/i18n";
import { asciiDigits, codePoints } from "./crud/validation";
import { callMasterOp } from "./master-op-client";
import { Field, inputClass } from "./kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "./kit/reason-field";

export const CHANGE_TYPES = ["info", "location", "cluster", "close"] as const;
type ChangeType = (typeof CHANGE_TYPES)[number];
export interface Option {
  value: string;
  label: string;
}
export interface OutletFacts {
  id: number;
  code: string;
  name: string;
  name_bn: string;
  owner_name: string;
  contact_number: string;
  address: string;
  cluster_id: string;
  sub_channel_id: string;
  lat: string;
  lng: string;
}

interface Props {
  outlet: OutletFacts;
  clusters: Option[];
  subChannels: Option[];
  closeReasons: Option[];
}

const PHONE = /^01[3-9][0-9]{8}$/;
const inv = (errs: Record<string, string>, k: string) => (errs[k] ? { "aria-invalid": true as const, "aria-describedby": `error-f-${k}` } : {});
const num = (s: string) => Number(String(asciiDigits(s)).trim());

/** Propose a change to one outlet (info, location, cluster or closure). The mandatory reason travels as the request note. */
export function OutletChangeForm({ outlet, clusters, subChannels, closeReasons }: Props) {
  const { t, problem } = useI18n();
  const [type, setType] = useState<ChangeType>("info");
  // The browser makes the idempotency key when the form opens; a retry after a failure reuses it, a new request gets a new one.
  const [requestUuid, setRequestUuid] = useState(() => crypto.randomUUID());
  // What the last send was for: the key is reused only for an identical retry (a stored request would otherwise answer an edited proposal).
  const [lastSent, setLastSent] = useState<string | null>(null);
  const [v, setV] = useState<Record<string, string>>({ name: outlet.name, name_bn: outlet.name_bn, owner_name: outlet.owner_name, contact_number: "", address: outlet.address, sub_channel_id: outlet.sub_channel_id, cluster_id: outlet.cluster_id, lat: outlet.lat, lng: outlet.lng, close_reason_code: "" });
  const [reason, setReason] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const set = (k: string, val: string) => setV((x) => ({ ...x, [k]: val }));

  function proposal(): { proposed: Record<string, unknown>; errors: Record<string, string> } {
    const e: Record<string, string> = {};
    const p: Record<string, unknown> = {};
    const bad = t("error.field.invalid");
    if (type === "info") {
      const text = (k: "name" | "owner_name", orig: string) => {
        const s = v[k]!.trim();
        if (s === orig.trim()) return;
        if (codePoints(s) < 2 || codePoints(s) > 120) e[k] = bad;
        else p[k] = s;
      };
      text("name", outlet.name);
      text("owner_name", outlet.owner_name);
      const bn = v.name_bn!.trim();
      if (bn !== outlet.name_bn.trim()) {
        if (!bn) e.name_bn = bad; // clearing a name is not a proposal the API takes (null removes it; ask an administrator)
        else if (codePoints(bn) > 120) e.name_bn = bad;
        else p.name_bn = bn;
      }
      const phone = String(asciiDigits(v.contact_number!)).trim();
      if (phone) {
        if (!PHONE.test(phone)) e.contact_number = bad;
        else p.contact_number = phone;
      }
      const addr = v.address!.trim();
      if (addr !== outlet.address.trim()) {
        if (!addr || codePoints(addr) > 300) e.address = bad;
        else p.address = addr;
      }
      if (v.sub_channel_id !== outlet.sub_channel_id) p.sub_channel_id = v.sub_channel_id ? Number(v.sub_channel_id) : null;
      if (Object.keys(e).length === 0 && Object.keys(p).length === 0) e.form = t("och.no_change");
    } else if (type === "location") {
      const lat = num(v.lat!);
      const lng = num(v.lng!);
      if (!v.lat!.trim() || !Number.isFinite(lat) || Math.abs(lat) > 90) e.lat = bad;
      if (!v.lng!.trim() || !Number.isFinite(lng) || Math.abs(lng) > 180) e.lng = bad;
      if (!e.lat && !e.lng) {
        p.lat = lat;
        p.lng = lng;
        if (String(lat) === outlet.lat && String(lng) === outlet.lng) e.form = t("och.no_change");
      }
    } else if (type === "cluster") {
      const c = String(asciiDigits(v.cluster_id!)).trim();
      if (!/^[1-9][0-9]{0,14}$/.test(c)) e.cluster_id = bad;
      else if (c === outlet.cluster_id) e.form = t("och.no_change");
      else p.cluster_id = Number(c);
    } else if (!/^[a-z][a-z0-9_]{1,40}$/.test(v.close_reason_code!.trim())) e.close_reason_code = t("error.field.required");
    else p.close_reason_code = v.close_reason_code!.trim();
    return { proposed: p, errors: e };
  }

  async function submit() {
    const { proposed, errors: e } = proposal();
    const n = codePoints(reason.replace(/[​-‍⁠﻿]/g, "").trim());
    if (n < REASON_MIN_LENGTH) e.reason = t("admin.reason.too_short");
    else if (n > 500) e.reason = t("error.field.too_long");
    setErrors(e);
    setMsg(null);
    if (Object.keys(e).length) return;
    setBusy(true);
    const signature = JSON.stringify([type, proposed, reason.trim()]);
    const uuid = lastSent === null || lastSent === signature ? requestUuid : crypto.randomUUID();
    setRequestUuid(uuid);
    setLastSent(signature);
    const r = await callMasterOp("outlet-request.create", { body: { request_uuid: uuid, request_type: type, outlet_id: outlet.id, proposed }, reason: reason.trim() });
    setBusy(false);
    if (!r.ok) {
      setMsg({ ok: false, text: problem(r.problem.code) });
      return;
    }
    setMsg({ ok: true, text: t("och.sent") });
    setReason("");
    setRequestUuid(crypto.randomUUID()); // the next proposal is a new request
    setLastSent(null);
  }

  const typeHint: Record<ChangeType, MessageKey> = { info: "och.type_hint.info", location: "och.type_hint.location", cluster: "och.type_hint.cluster", close: "och.type_hint.close" };
  return (
    <form
      onSubmit={(ev) => {
        ev.preventDefault();
        void submit();
      }}
      noValidate
      className="space-y-4 rounded-lg border border-slate-200 bg-white p-4 shadow-sm"
      data-testid="outlet-change-form"
    >
      <p className="text-sm text-slate-700" data-testid="och-outlet">{`${outlet.code} · ${outlet.name}`}</p>
      <Field label={t("och.type")} htmlFor="f-request_type" required hint={t(typeHint[type])}>
        <select id="f-request_type" value={type} onChange={(ev) => { setType(ev.target.value as ChangeType); setErrors({}); setMsg(null); }} className={`${inputClass} w-64`}>
          {CHANGE_TYPES.map((k) => (
            <option key={k} value={k}>{t(`och.type.${k}` as MessageKey)}</option>
          ))}
        </select>
      </Field>
      {type === "info" ? (
        <>
          <Field label={t("entity.field.name")} htmlFor="f-name" error={errors.name}>
            <input id="f-name" {...inv(errors, "name")} maxLength={120} value={v.name} onChange={(ev) => set("name", ev.target.value)} className={inputClass} />
          </Field>
          <Field label={t("entity.field.name_bn")} htmlFor="f-name_bn" error={errors.name_bn}>
            <input id="f-name_bn" {...inv(errors, "name_bn")} maxLength={120} value={v.name_bn} onChange={(ev) => set("name_bn", ev.target.value)} className={inputClass} />
          </Field>
          <Field label={t("entity.field.owner_name")} htmlFor="f-owner_name" error={errors.owner_name}>
            <input id="f-owner_name" {...inv(errors, "owner_name")} maxLength={120} value={v.owner_name} onChange={(ev) => set("owner_name", ev.target.value)} className={inputClass} />
          </Field>
          <Field label={t("entity.field.contact_number")} htmlFor="f-contact_number" hint={t("och.contact_hint")} error={errors.contact_number}>
            <input id="f-contact_number" {...inv(errors, "contact_number")} inputMode="numeric" maxLength={11} value={v.contact_number} onChange={(ev) => set("contact_number", ev.target.value)} className={`${inputClass} w-48`} />
          </Field>
          <Field label={t("entity.field.address")} htmlFor="f-address" error={errors.address}>
            <input id="f-address" {...inv(errors, "address")} maxLength={300} value={v.address} onChange={(ev) => set("address", ev.target.value)} className={inputClass} />
          </Field>
          {subChannels.length > 0 ? (
            <Field label={t("entity.field.sub_channel_id")} htmlFor="f-sub_channel_id">
              <select id="f-sub_channel_id" value={v.sub_channel_id} onChange={(ev) => set("sub_channel_id", ev.target.value)} className={`${inputClass} w-64`}>
                <option value="">{t("common.none")}</option>
                {subChannels.map((o) => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </Field>
          ) : null}
        </>
      ) : null}
      {type === "location" ? (
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t("entity.field.lat")} htmlFor="f-lat" required error={errors.lat}>
            <input id="f-lat" {...inv(errors, "lat")} inputMode="decimal" value={v.lat} onChange={(ev) => set("lat", ev.target.value)} aria-required="true" className={inputClass} />
          </Field>
          <Field label={t("entity.field.lng")} htmlFor="f-lng" required error={errors.lng}>
            <input id="f-lng" {...inv(errors, "lng")} inputMode="decimal" value={v.lng} onChange={(ev) => set("lng", ev.target.value)} aria-required="true" className={inputClass} />
          </Field>
        </div>
      ) : null}
      {type === "cluster" ? (
        <Field label={t("entity.field.cluster")} htmlFor="f-cluster_id" required error={errors.cluster_id}>
          {clusters.length > 0 ? (
            <select id="f-cluster_id" {...inv(errors, "cluster_id")} value={v.cluster_id} onChange={(ev) => set("cluster_id", ev.target.value)} aria-required="true" className={`${inputClass} w-80`}>
              {clusters.some((o) => o.value === v.cluster_id) ? null : <option value="">{t("common.none")}</option>}
              {clusters.map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          ) : (
            <input id="f-cluster_id" {...inv(errors, "cluster_id")} inputMode="numeric" value={v.cluster_id} onChange={(ev) => set("cluster_id", ev.target.value)} aria-required="true" className={`${inputClass} w-40`} />
          )}
        </Field>
      ) : null}
      {type === "close" ? (
        <Field label={t("och.close_reason")} htmlFor="f-close_reason_code" required error={errors.close_reason_code}>
          {closeReasons.length > 0 ? (
            <select id="f-close_reason_code" {...inv(errors, "close_reason_code")} value={v.close_reason_code} onChange={(ev) => set("close_reason_code", ev.target.value)} aria-required="true" className={`${inputClass} w-80`}>
              <option value="">{t("common.none")}</option>
              {closeReasons.map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          ) : (
            <input id="f-close_reason_code" {...inv(errors, "close_reason_code")} value={v.close_reason_code} onChange={(ev) => set("close_reason_code", ev.target.value)} aria-required="true" className={`${inputClass} w-64`} />
          )}
        </Field>
      ) : null}
      {errors.form ? <p role="alert" data-testid="och-no-change" className="text-sm font-medium text-red-700">{errors.form}</p> : null}
      <ReasonField value={reason} onChange={setReason} error={errors.reason} />
      <p className="text-xs text-slate-600">{t("och.reason_note")}</p>
      {msg ? (
        <p role={msg.ok ? "status" : "alert"} data-testid={msg.ok ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${msg.ok ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>
          {msg.text}{" "}
          {msg.ok ? <Link href="/admin/outlet-requests" className="underline">{t("menu.admin.outlet_requests")}</Link> : null}
        </p>
      ) : null}
      <button type="submit" disabled={busy} data-testid="och-submit" className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
        {t("och.submit")}
      </button>
    </form>
  );
}
