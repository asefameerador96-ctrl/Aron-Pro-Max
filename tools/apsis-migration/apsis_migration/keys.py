"""Deterministic keys (D-APSIS-MIG-03): UUID v5 of namespace + vendor table + vendor key."""
import hashlib
import uuid

NAMESPACE = uuid.uuid5(uuid.NAMESPACE_URL, "urn:aktcl:aron:apsis-migration:v1")


def row_uuid(vendor_table: str, key_parts) -> str:
    """UUID v5 string for one vendor row. key_parts: the primary key values (None -> empty)."""
    name = vendor_table + ":" + "|".join("" if p is None else str(p) for p in key_parts)
    return str(uuid.uuid5(NAMESPACE, name))


def full_row_key(values) -> str:
    """Key for a table without a primary key: md5 of the canonical row text, so exact duplicates collapse."""
    h = hashlib.md5()
    for v in values:
        h.update(b"\x00" if v is None else b"\x01" + str(v).encode("utf-8"))
        h.update(b"\x1f")
    return "md5:" + h.hexdigest()
