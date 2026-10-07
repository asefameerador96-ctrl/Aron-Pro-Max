#!/usr/bin/env python3
"""Generates shared/contract/src/commonMain/.../WireDtos.kt from contract/slices/schemas/*.yaml.

Run from anywhere: python3 shared/contract/tools/gen_wire_dtos.py   (add --check to fail when the file is stale).
The slices come from tools/slice-contract.py; contract/openapi.yaml stays the source of truth.
Mapping: Uuid/Timestamp/BusinessDate/other string aliases and every enum -> String (a newer server's unknown enum
value must not crash an old phone); Id, Mtk*, int64 -> Long; integer -> Int; number -> Double; schemas outside the
requested set -> JsonElement/JsonObject. Optional members are nullable with a null default (or the schema default).
"""
import re, sys, pathlib
import yaml

ROOT = pathlib.Path(__file__).resolve().parents[3]
SL = ROOT / "contract/slices/schemas"
OUT = ROOT / "shared/contract/src/commonMain/kotlin/com/aktcl/aron/contract/WireDtos.kt"

GROUPS = [
    ("Auth (docs/24 s5)", ["LoginRequest", "LoginResponse", "UserSummary", "ScopeSummary", "NodeRef", "LoginDevice",
                           "RefreshRequest", "TokenPair", "LogoutRequest"]),
    ("Bundle (docs/24 s3.6)", ["BundleMeta", "BundleUser", "Route", "RouteSnapshot", "BundleOutlet", "Sku", "SkuPrice", "OpenMemo"]),
    ("Records (docs/24 s4)", ["RecordEnvelope", "GeoFix", "FixDeviceState", "GnssSummary", "DeviceGeoVerdict",
                              "AttendanceEventPayload", "StockMovementPayload", "VisitPayload", "VisitClosePayload",
                              "MemoPayload", "MemoLinePayload", "MemoDiscountPayload", "QcLinePayload"]),
    ("Sync batch", ["SyncBatchRequest", "SyncBatchResponse", "RecordAck"]),
]
NESTED = {("BundleMeta", "paged_sections"): "PagedSection", ("SyncBatchResponse", "summary"): "SyncBatchSummary"}
ID_ALIASES = {"Id", "Mtk", "MtkNonNegative"}
RAW = {"RadioEnvironment": "JsonObject", "SyncRecord": "JsonObject"}  # everything else unknown -> JsonElement


def load(name):
    return yaml.safe_load((SL / f"{name}.yaml").read_text())[name]


def camel(s):
    p = s.split("_")
    return p[0] + "".join(x.capitalize() for x in p[1:])


def kt_name(s):
    n = camel(s)
    return f"`{n}`" if n in ("object", "class", "val", "fun", "in", "is", "as", "when", "typeof") else n


def ref(s):
    return s["$ref"].rsplit("/", 1)[1]


def nullable(s):
    t = s.get("type")
    if isinstance(t, list) and "null" in t:
        return True
    return any(isinstance(o, dict) and o.get("type") == "null" for o in s.get("oneOf", []))


def strip_null(s):
    if isinstance(s.get("type"), list):
        t = [x for x in s["type"] if x != "null"]
        s = {**s, "type": t[0] if len(t) == 1 else t}
    if "oneOf" in s:
        rest = [o for o in s["oneOf"] if not (isinstance(o, dict) and o.get("type") == "null")]
        if len(rest) == 1:
            return rest[0]
    return s


nested_out = []


def ktype(s, owner, prop):
    s = strip_null(s)
    if "$ref" in s:
        n = ref(s)
        if n in RAW:
            return RAW[n]
        if n in ID_ALIASES:
            return "Long"
        t = load(n)
        if t.get("type") == "string":
            return "String"
        if t.get("type") == "integer":
            return "Long" if t.get("format") == "int64" else "Int"
        if t.get("type") == "number":
            return "Double"
        if n in {g for _, ns in GROUPS for g in ns}:
            return n
        return "JsonElement"
    t = s.get("type")
    if t == "string":
        return "String"
    if t == "boolean":
        return "Boolean"
    if t == "integer":
        return "Long" if s.get("format") == "int64" else "Int"
    if t == "number":
        return "Double"
    if t == "array":
        return f"List<{ktype(s['items'], owner, prop)}>"
    if t == "object":
        if (owner, prop) in NESTED:
            cls = NESTED[(owner, prop)]
            nested_out.append((cls, s))
            return cls
        if "additionalProperties" in s and "properties" not in s:
            return f"Map<String, {ktype(s['additionalProperties'], owner, prop)}>"
        return "JsonObject"
    raise SystemExit(f"unmapped {owner}.{prop}: {s}")


def schema_members(sch):
    if "allOf" in sch:
        props, req = {}, []
        for part in sch["allOf"]:
            part = load(ref(part)) if "$ref" in part else part
            props.update(part.get("properties", {}))
            req += part.get("required", [])
        return props, req
    return sch.get("properties", {}), sch.get("required", [])


def emit_class(name, sch, owner_for_nested):
    props, req = schema_members(sch)
    doc = (sch.get("description") or "").strip().split("\n")[0]
    lines = []
    if doc and name not in ("PagedSection", "SyncBatchSummary"):
        lines.append(f"/** {doc} */")
    lines.append("@Serializable")
    lines.append(f"data class {name}(")
    for p, ps in props.items():
        t = ktype(ps, owner_for_nested, p)
        isreq = p in req
        nul = nullable(ps)
        has_default = "default" in strip_null(ps) and not nul
        if isreq:
            decl = f"{t}?" if nul else t
            default = ""
        elif has_default:
            d = strip_null(ps)["default"]
            decl, default = t, f" = {str(d).lower() if isinstance(d, bool) else d}"
        else:
            decl, default = f"{t}?", " = null"
        lines.append(f'    @SerialName("{p}") val {kt_name(p)}: {decl}{default},')
    lines.append(")")
    return "\n".join(lines)


def build():
    out = ["// GENERATED by shared/contract/tools/gen_wire_dtos.py from contract/slices/schemas (source of truth: contract/openapi.yaml).",
           "// Do not edit by hand: change the contract, regenerate the slices (tools/slice-contract.py) and re-run the generator.",
           "// WireDtosDriftTest compares every member here with the slice fields (name, required, nullable, JSON type).",
           "package com.aktcl.aron.contract", "",
           "import kotlinx.serialization.SerialName", "import kotlinx.serialization.Serializable",
           "import kotlinx.serialization.json.JsonElement", "import kotlinx.serialization.json.JsonObject", ""]
    for title, names in GROUPS:
        out.append(f"// ---- {title} ----\n")
        for n in names:
            nested_out.clear()
            sch = load(n)
            body = emit_class(n, sch, n)
            for cls, s in list(nested_out):
                out.append(emit_class(cls, s, cls) + "\n")
            out.append(body + "\n")
    return "\n".join(out)


if __name__ == "__main__":
    text = build()
    if "--check" in sys.argv:
        sys.exit(0 if OUT.exists() and OUT.read_text() == text else "WireDtos.kt is stale: re-run gen_wire_dtos.py")
    OUT.write_text(text)
    print("wrote", OUT)
