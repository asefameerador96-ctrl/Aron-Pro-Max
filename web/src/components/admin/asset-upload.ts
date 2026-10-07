// Browser side of an admin asset upload: hash, ask the BFF for the write-only SAS URL, PUT the file straight to it.
import { ASSET_RULES, type AssetPurpose } from "@/lib/admin/tutorials";

export type UploadResult = { ok: true; assetId: string } | { ok: false; code: string };

const hex = (buf: ArrayBuffer) => Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, "0")).join("");

/** Client check before any request: null when the file fits the purpose, else the message key's reason. */
export function fileProblem(file: File, purpose: AssetPurpose): "type" | "big" | "empty" | null {
  const rule = ASSET_RULES[purpose];
  if (!(rule.mimes as readonly string[]).includes(file.type)) return "type";
  if (file.size < 1) return "empty";
  return file.size > rule.max ? "big" : null;
}

export async function uploadAsset(file: File, purpose: AssetPurpose): Promise<UploadResult> {
  const assetId = crypto.randomUUID();
  const sha256 = hex(await crypto.subtle.digest("SHA-256", await file.arrayBuffer()));
  const ticket = await fetch("/api/bff/admin/assets", { method: "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ asset_id: assetId, purpose, mime: file.type, bytes: file.size, sha256 }) });
  const json = (await ticket.json().catch(() => ({}))) as { data?: { upload_url?: string }; code?: string };
  if (!ticket.ok || !json.data?.upload_url) return { ok: false, code: json.code ?? "ERR_INTERNAL" };
  const put = await fetch(json.data.upload_url, { method: "PUT", headers: { "x-ms-blob-type": "BlockBlob", "Content-Type": file.type }, body: file });
  return put.ok ? { ok: true, assetId } : { ok: false, code: "upload_failed" };
}
