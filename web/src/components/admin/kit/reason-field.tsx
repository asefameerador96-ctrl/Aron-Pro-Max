"use client";
import { useI18n } from "@/components/i18n-provider";
import { Field, inputClass } from "./field";

export const REASON_MIN_LENGTH = 10; // ChangeReason (contract): at least 10 characters

/** The mandatory reason on every write. Every admin form includes this; a form without it is a review reject. */
export function ReasonField({ value, onChange, error, id = "reason" }: { value: string; onChange: (v: string) => void; error?: string | null; id?: string }) {
  const { t, number } = useI18n();
  const length = value.trim().length;
  return (
    <Field label={t("admin.reason.label")} htmlFor={id} required hint={`${t("admin.reason.hint")} (${number(length)}/${number(REASON_MIN_LENGTH)})`} error={error}>
      <textarea
        id={id}
        name="reason"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        required
        minLength={REASON_MIN_LENGTH}
        maxLength={500}
        rows={3}
        aria-required="true"
        aria-invalid={error ? true : undefined}
        className={inputClass}
      />
    </Field>
  );
}
