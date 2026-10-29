#!/usr/bin/env python3
"""Draw the Aron final-account diagrams in the Microsoft Visio / Azure architecture style
(official Azure service icons on a white canvas with orthogonal connectors) and render them to PNG.

    python3 -I docs/35-final-azure-design-pack/diagrams.py <icon-set-dir> <out-dir>

<icon-set-dir> is the unzipped Microsoft "Azure Public Service Icons" folder (the one that contains
"Icons/"); the icons are Microsoft's and are used here as the terms of that icon set allow (architecture
diagrams). Each diagram is written as SVG and rendered to PNG at 2x with the pre-installed Chromium.
"""
import pathlib
import re
import sys

ICON_DIR = pathlib.Path(sys.argv[1]).resolve() / "Icons"
OUT = pathlib.Path(sys.argv[2]).resolve()
OUT.mkdir(parents=True, exist_ok=True)

ICONS = {
    "frontdoor": "networking/10073-icon-service-Front-Door-and-CDN-Profiles.svg",
    "waf": "networking/10362-icon-service-Web-Application-Firewall-Policies(WAF).svg",
    "cae": "other/02989-icon-service-Container-Apps-Environments.svg",
    "capp": "other/02884-icon-service-Worker-Container-App.svg",
    "pg": "databases/10131-icon-service-Azure-Database-PostgreSQL-Server.svg",
    "storage": "storage/10086-icon-service-Storage-Accounts.svg",
    "blob": "general/10780-icon-service-Blob-Block.svg",
    "queue": "general/10840-icon-service-Storage-Queue.svg",
    "kv": "security/10245-icon-service-Key-Vaults.svg",
    "acr": "containers/10105-icon-service-Container-Registries.svg",
    "law": "monitor/00009-icon-service-Log-Analytics-Workspaces.svg",
    "appi": "monitor/00012-icon-service-Application-Insights.svg",
    "monitor": "monitor/00001-icon-service-Monitor.svg",
    "alerts": "monitor/00002-icon-service-Alerts.svg",
    "workbook": "monitor/02189-icon-service-Azure-Workbooks.svg",
    "eventgrid": "integration/10206-icon-service-Event-Grid-Topics.svg",
    "vnet": "networking/10061-icon-service-Virtual-Networks.svg",
    "subnet": "networking/02742-icon-service-Subnet.svg",
    "nsg": "networking/10067-icon-service-Network-Security-Groups.svg",
    "dns": "networking/10064-icon-service-DNS-Zones.svg",
    "pe": "networking/02579-icon-service-Private-Endpoints.svg",
    "plink": "networking/00427-icon-service-Private-Link.svg",
    "mi": "identity/10227-icon-service-Managed-Identities.svg",
    "entra": "identity/10231-icon-service-Entra-ID-Protection.svg",
    "users": "identity/10230-icon-service-Users.svg",
    "budget": "general/10793-icon-service-Cost-Budgets.svg",
    "cost": "general/10019-icon-service-Cost-Management.svg",
    "mobile": "general/10822-icon-service-Mobile.svg",
    "browser": "general/10783-icon-service-Browser.svg",
    "device": "intune/10332-icon-service-Devices.svg",
    "loadtest": "other/02944-icon-service-Azure-Load-Testing.svg",
    "policy": "management + governance/10316-icon-service-Policy.svg",
    "defender": "security/10241-icon-service-Microsoft-Defender-for-Cloud.svg",
    "redis": "databases/03675-icon-service-Azure-Managed-Redis.svg",
    "appservice": "app services/10035-icon-service-App-Services.svg",
    "asp": "app services/00046-icon-service-App-Service-Plans.svg",
    "mysql": "databases/10122-icon-service-Azure-Database-MySQL-Server.svg",
    "vm": "compute/10021-icon-service-Virtual-Machine.svg",
    "rg": "general/10007-icon-service-Resource-Groups.svg",
    "sub": "general/10002-icon-service-Subscriptions.svg",
    "globe": "general/10808-icon-service-Globe-Success.svg",
    "backup": "other/02361-icon-service-Backup-Vault.svg",
    "diag": "monitor/00008-icon-service-Diagnostics-Settings.svg",
    "cert": "app services/00049-icon-service-App-Service-Certificates.svg",
    "region": "general/10116-icon-service-Region-Management.svg",
}

_icon_cache = {}
_uid = [0]


def icon_svg(key, x, y, size):
    """Return the icon as a nested <svg> with namespaced ids so several icons can share one canvas."""
    if key not in _icon_cache:
        path = ICON_DIR / ICONS[key]
        if not path.exists():  # the set's folder layout varies between releases: find the file by name
            hits = sorted(ICON_DIR.rglob(pathlib.Path(ICONS[key]).name))
            if not hits:
                raise FileNotFoundError(ICONS[key])
            path = hits[0]
        txt = path.read_text(encoding="utf-8", errors="ignore")
        txt = re.sub(r"<\?xml[^>]*\?>", "", txt)
        txt = re.sub(r"<!DOCTYPE[^>]*>", "", txt)
        m = re.search(r"<svg[^>]*viewBox=\"([^\"]+)\"", txt)
        vb = m.group(1) if m else "0 0 18 18"
        inner = re.sub(r"^.*?<svg[^>]*>", "", txt, count=1, flags=re.S)
        inner = re.sub(r"</svg>\s*$", "", inner, flags=re.S)
        _icon_cache[key] = (vb, inner)
    vb, inner = _icon_cache[key]
    _uid[0] += 1
    pfx = f"i{_uid[0]}_"
    ids = set(re.findall(r'id="([^"]+)"', inner))
    for i in sorted(ids, key=len, reverse=True):
        inner = inner.replace(f'id="{i}"', f'id="{pfx}{i}"')
        inner = inner.replace(f"url(#{i})", f"url(#{pfx}{i})")
        inner = inner.replace(f'href="#{i}"', f'href="#{pfx}{i}"')
    return f'<svg x="{x}" y="{y}" width="{size}" height="{size}" viewBox="{vb}">{inner}</svg>'


class Diagram:
    def __init__(self, name, w, h, title=None):
        self.name, self.w, self.h, self.title = name, w, h, title
        self.nodes = {}
        self.parts = []
        self.edges = []

    # ---- elements ------------------------------------------------------------------------------
    def group(self, x, y, w, h, title, kind="region"):
        styles = {
            "region": ("#f3f6fb", "#7a8aa6", "6,4", "#3b4a66"),
            "vnet": ("#eef6ff", "#2b7cd3", "0", "#1f5fa8"),
            "subnet": ("#f7f7f7", "#9a9a9a", "4,3", "#555"),
            "plain": ("#ffffff", "#b0b0b0", "0", "#444"),
            "external": ("#fbfbfb", "#c0c0c0", "5,4", "#666"),
            "dr": ("#fff8ee", "#d9922b", "6,4", "#8a5a10"),
        }
        fill, stroke, dash, color = styles[kind]
        self.parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="8" fill="{fill}" stroke="{stroke}" stroke-width="1.3" stroke-dasharray="{dash}"/>'
                          f'<text x="{x+12}" y="{y+20}" font-size="13" font-weight="600" fill="{color}">{esc(title)}</text>')

    def node(self, key, x, y, icon, label, size=56, sub=None):
        """Icon centred at (x, y) with the label beneath."""
        self.nodes[key] = (x, y, size)
        half = size / 2
        lines = label.split("\n")
        out = [icon_svg(icon, x - half, y - half, size)]
        ty = y + half + 15
        for i, ln in enumerate(lines):
            out.append(f'<text x="{x}" y="{ty + i*14}" font-size="12" text-anchor="middle" fill="#222">{esc(ln)}</text>')
        if sub:
            out.append(f'<text x="{x}" y="{ty + len(lines)*14}" font-size="10.5" text-anchor="middle" fill="#666">{esc(sub)}</text>')
        self.parts.append("".join(out))

    def box(self, key, x, y, w, h, label, sub=None, fill="#ffffff", stroke="#8a8a8a"):
        """A plain Visio-style rectangle for things that are not Azure services."""
        self.nodes[key] = (x, y, max(w, h), w, h)
        lines = label.split("\n")
        out = [f'<rect x="{x-w/2}" y="{y-h/2}" width="{w}" height="{h}" rx="4" fill="{fill}" stroke="{stroke}" stroke-width="1.2"/>']
        start = y - (len(lines)-1)*7 + 4 - (6 if sub else 0)
        for i, ln in enumerate(lines):
            out.append(f'<text x="{x}" y="{start + i*14}" font-size="12" text-anchor="middle" fill="#222">{esc(ln)}</text>')
        if sub:
            out.append(f'<text x="{x}" y="{start + len(lines)*14}" font-size="10.5" text-anchor="middle" fill="#666">{esc(sub)}</text>')
        self.parts.append("".join(out))

    def note(self, x, y, text, size=11, color="#555", anchor="start"):
        for i, ln in enumerate(text.split("\n")):
            self.parts.append(f'<text x="{x}" y="{y + i*(size+3)}" font-size="{size}" fill="{color}" text-anchor="{anchor}">{esc(ln)}</text>')

    def _bounds(self, key):
        n = self.nodes[key]
        if len(n) == 5:
            x, y, _, w, h = n
            return x, y, w / 2, h / 2
        x, y, s = n
        return x, y, s / 2 + 2, s / 2 + 2

    def edge(self, a, b, route="HV", label=None, arrow=True, dashed=False, color="#4a4a4a", via=None, lpos=0.5):
        """Orthogonal connector from a to b. route HV: horizontal first then vertical; VH: the reverse;
        H / V: straight. via: optional list of absolute bend points replacing the automatic route."""
        ax, ay, ahw, ahh = self._bounds(a)
        bx, by, bhw, bhh = self._bounds(b)
        if via:
            pts = [(ax, ay)] + list(via) + [(bx, by)]
        elif route == "H":
            pts = [(ax, ay), (bx, ay)]
        elif route == "V":
            pts = [(ax, ay), (ax, by)]
        elif route == "HV":
            pts = [(ax, ay), (bx, ay), (bx, by)]
        else:  # VH
            pts = [(ax, ay), (ax, by), (bx, by)]
        # clip the first and last segments to the node boundaries
        def clip(p_in, p_out, hw, hh):
            x0, y0 = p_in; x1, y1 = p_out
            if abs(x1 - x0) >= abs(y1 - y0):
                return (x0 + (hw if x1 > x0 else -hw), y0)
            return (x0, y0 + (hh if y1 > y0 else -hh))
        pts[0] = clip(pts[0], pts[1], ahw, ahh)
        pts[-1] = clip(pts[-1], pts[-2], bhw, bhh)
        d = " ".join(("M" if i == 0 else "L") + f"{x:.1f},{y:.1f}" for i, (x, y) in enumerate(pts))
        dash = ' stroke-dasharray="5,4"' if dashed else ""
        mark = ' marker-end="url(#arrow)"' if arrow else ""
        self.edges.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="1.4"{dash}{mark}/>')
        if label:
            # label at lpos along the longest segment
            seg = max(range(len(pts) - 1), key=lambda i: abs(pts[i+1][0]-pts[i][0]) + abs(pts[i+1][1]-pts[i][1]))
            (x0, y0), (x1, y1) = pts[seg], pts[seg+1]
            lx, ly = x0 + (x1 - x0) * lpos, y0 + (y1 - y0) * lpos
            w = 6.2 * len(label) + 8
            self.edges.append(f'<rect x="{lx - w/2}" y="{ly - 9}" width="{w}" height="15" fill="#ffffff" opacity="0.9"/>'
                              f'<text x="{lx}" y="{ly + 3}" font-size="10.5" text-anchor="middle" fill="#333">{esc(label)}</text>')

    # ---- output --------------------------------------------------------------------------------
    def svg(self):
        head = (f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="{self.w}" height="{self.h}" '
                f'viewBox="0 0 {self.w} {self.h}" font-family="Segoe UI, Liberation Sans, Arial, Helvetica, sans-serif">'
                '<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
                '<path d="M0,0 L10,5 L0,10 z" fill="#4a4a4a"/></marker></defs>'
                f'<rect width="{self.w}" height="{self.h}" fill="#ffffff"/>')
        title = f'<text x="20" y="28" font-size="16" font-weight="600" fill="#1b1b1b">{esc(self.title)}</text>' if self.title else ""
        return head + title + "".join(self.parts) + "".join(self.edges) + "</svg>"

    def write(self):
        p = OUT / f"{self.name}.svg"
        p.write_text(self.svg(), encoding="utf-8")
        return p


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


# ================================================================================================
# HLD 1: system context
# ================================================================================================
def d_context():
    d = Diagram("hld-1-context", 1300, 620, "HLD 1 — System context: who uses Aron and what it talks to")
    d.group(40, 60, 300, 520, "Field (Bangladesh): 9,842 app users", "external")
    d.node("sr", 120, 130, "mobile", "SR app\n8,500 phones")
    d.node("amo", 260, 130, "mobile", "AMO app\n1,051")
    d.node("tso", 120, 290, "mobile", "TSO app\n291")
    d.node("prn", 260, 290, "device", "Bluetooth printer\n58 mm memo")
    d.note(60, 470, "Offline-first: a rep sells all day with no signal;\nthe phone syncs when it has one.", 11)
    d.group(380, 60, 540, 520, "Aron platform on Azure (Southeast Asia, final account)", "region")
    d.node("afd", 500, 180, "frontdoor", "Front Door Premium\n+ WAF")
    d.node("cae", 700, 180, "cae", "Container Apps\napi · worker · web")
    d.node("pg", 560, 400, "pg", "PostgreSQL 16\nzone-redundant HA")
    d.node("st", 760, 400, "storage", "Blob storage\nphotos · bundles · APK")
    d.node("mon", 860, 300, "monitor", "Monitor\nlogs · alerts", size=44)
    d.group(960, 60, 310, 200, "Head office and management", "external")
    d.node("web", 1115, 150, "browser", "Dashboard and portal\nabout 150 users")
    d.group(960, 300, 310, 280, "Outside Azure", "external")
    d.box("maps", 1115, 350, 230, 44, "Google Maps Platform", "device maps · portal · geocoding on demand")
    d.box("fcm", 1115, 420, 230, 44, "Firebase Cloud Messaging", "push notifications")
    d.box("gh", 1115, 490, 230, 44, "GitHub", "code, CI/CD, OIDC deploys")
    d.edge("sr", "afd", "HV", "sync, bundles, photos")
    d.edge("amo", "afd", "HV")
    d.edge("tso", "afd", "HV")
    d.edge("sr", "prn", "V", "print", arrow=True, dashed=True)
    d.edge("web", "afd", via=[(1115, 240), (500, 240)], label="HTTPS")
    d.edge("afd", "cae", "H")
    d.edge("cae", "pg", "VH")
    d.edge("cae", "st", "V")
    d.edge("sr", "st", via=[(120, 560), (760, 560)], label="photos by SAS, straight to Blob", dashed=True)
    d.edge("cae", "fcm", via=[(700, 300), (940, 300), (940, 420)], label="push", dashed=True)
    d.edge("sr", "maps", via=[(120, 540), (960, 540), (960, 350)], dashed=True, arrow=False)
    d.edge("gh", "cae", via=[(1115, 560), (700, 560)], label="deploys", dashed=True)
    return d


# ================================================================================================
# HLD 2: logical architecture (containers)
# ================================================================================================
def d_logical():
    d = Diagram("hld-2-logical", 1400, 760, "HLD 2 — Logical architecture: the running parts and what each one does")
    d.group(30, 60, 220, 330, "Clients", "external")
    d.node("app", 140, 150, "mobile", "Android apps (Kotlin)\nSR / AMO / TSO", sub="SQLCipher, WorkManager sync")
    d.node("br", 140, 300, "browser", "Browser")
    d.group(290, 60, 200, 330, "Edge", "region")
    d.node("afd", 390, 180, "frontdoor", "Front Door Premium")
    d.node("waf", 390, 310, "waf", "WAF policy", size=44)
    d.group(530, 60, 520, 330, "Application tier — Container Apps environment, zone-redundant", "vnet")
    d.node("api", 620, 170, "capp", "api (Ktor)\nauth · sync · bundles ·\nconfig · dashboards · reports")
    d.node("wrk", 790, 170, "capp", "worker\noutbox · aggregation ·\nphoto link · retention")
    d.node("web", 960, 170, "capp", "web (Next.js)\ndashboard · portal")
    d.node("jobs", 790, 320, "capp", "jobs\nmigrate · dblogins · exports", size=44)
    d.group(530, 420, 520, 310, "Data tier", "region")
    d.node("pg", 620, 520, "pg", "PostgreSQL 16\napp · dw · cfg schemas", sub="zone-redundant HA")
    d.node("pgr", 790, 520, "pg", "Read replica\ndashboards · reports")
    d.node("blob", 960, 500, "blob", "Blob: media · bundles ·\nexports (7 d)", size=48)
    d.node("q", 960, 640, "queue", "Queue media-events", size=40)
    d.node("kv", 620, 660, "kv", "Key Vault", size=44)
    d.group(1090, 60, 280, 670, "Operations", "region")
    d.node("law", 1180, 170, "law", "Log Analytics", size=48)
    d.node("appi", 1300, 170, "appi", "App Insights", size=48)
    d.node("al", 1180, 330, "alerts", "Alerts", size=44)
    d.node("wb", 1300, 330, "workbook", "Workbook", size=44)
    d.node("acr", 1240, 500, "acr", "Container Registry\nimages by digest")
    d.node("bud", 1240, 640, "budget", "Budget and cost", size=44)
    d.edge("app", "afd", "HV", "/v1/*")
    d.edge("br", "afd", "HV", "/*")
    d.edge("afd", "api", "HV")
    d.edge("afd", "web", via=[(390, 90), (960, 90)])
    d.edge("web", "afd", via=[(960, 120), (390, 120)], label="BFF calls go back through Front Door", dashed=True)
    d.edge("api", "pg", "V", "6432 PgBouncer")
    d.edge("api", "pgr", "VH", "reads")
    d.edge("wrk", "pg", "VH", "5432")
    d.edge("jobs", "pg", "VH")
    d.edge("app", "blob", via=[(140, 740), (960, 740)], label="photos by SAS PUT", dashed=True)
    d.edge("blob", "q", "V", "BlobCreated")
    d.edge("q", "wrk", via=[(1070, 640), (1070, 240), (790, 240)], label="events (consumer planned)", dashed=True)
    d.edge("pg", "pgr", "H", "async", dashed=True)
    d.edge("api", "kv", "V", "secret refs", dashed=True)
    d.edge("acr", "web", via=[(1240, 420), (1060, 420), (1060, 170)], dashed=True, label="pull")
    d.edge("web", "law", via=[(1060, 150), (1060, 100), (1180, 100)], dashed=True)
    return d


# ================================================================================================
# HLD 3: Azure deployment (final account)
# ================================================================================================
def d_deployment():
    d = Diagram("hld-3-deployment", 1500, 900, "HLD 3 — Azure deployment, final account: resource group rg-aron-prod, Southeast Asia (3 zones), DR in East Asia")
    d.node("ph", 90, 130, "mobile", "Phones\n9,842")
    d.node("bw", 90, 260, "browser", "Browsers\n~150")
    d.node("afd", 300, 190, "frontdoor", "Front Door Premium\nafd-aron-prod", sub="global edge, TLS, routes")
    d.node("waf", 300, 340, "waf", "WAF wafaronprod\nPrevention", size=44)
    d.group(430, 60, 760, 790, "Subscription (final) › resource group rg-aron-prod › Southeast Asia", "region")
    d.group(450, 100, 720, 470, "VNet vnet-aron-prod 10.40.0.0/16", "vnet")
    d.group(470, 140, 430, 400, "snet-aca 10.40.0.0/23 (delegated to Container Apps)", "subnet")
    d.node("cae", 560, 220, "cae", "cae-aron-prod\nzone-redundant · public access OFF", sub="private endpoint for Front Door")
    d.node("api", 560, 400, "capp", "ca-aron-prod-api\n1 vCPU/2 GiB · 3–30 replicas")
    d.node("wrk", 700, 400, "capp", "ca-aron-prod-worker\n1–10 replicas")
    d.node("web", 830, 400, "capp", "ca-aron-prod-web\n2–6 replicas")
    d.node("jobs", 830, 260, "capp", "caj-aron-prod-migrate\ncaj-aron-prod-dblogins", size=44)
    d.group(920, 140, 230, 400, "snet-pg 10.40.2.0/28 (NSG: 5432/6432 from snet-aca only)", "subnet")
    d.node("pg", 1000, 230, "pg", "psql-aron-prod\nD8ds_v5 · zone 1", sub="SSD v2 1,024 GiB · 12k IOPS")
    d.node("pgs", 1100, 330, "pg", "HA standby\nzone 2 · synchronous", size=40)
    d.node("pgr", 1035, 440, "pg", "psql-aron-prod-r1\nread replica · zone 3")
    d.node("st", 520, 680, "storage", "staronprod\nRA-GZRS", sub="media · bundles · exports · queue")
    d.node("lake", 620, 790, "storage", "staronprodlake\nZRS · data lake", size=36)
    d.node("eg", 660, 680, "eventgrid", "Event Grid\nsystem topic", size=44)
    d.node("kv", 780, 680, "kv", "kv-aron-prod\npurge protection", size=48)
    d.node("acr", 900, 680, "acr", "craronprod\nPremium", size=48)
    d.node("law", 1030, 680, "law", "log-aron-prod\n90 d · 8 GB/day cap", size=48)
    d.node("appi", 1140, 680, "appi", "appi-aron-prod", size=44)
    d.node("mi", 1120, 790, "mi", "4 managed identities", size=36)
    d.node("bud", 1000, 790, "budget", "budget USD 7,000", size=36)
    d.node("al", 880, 790, "alerts", "alerts · action group", size=36)
    d.group(1220, 60, 260, 790, "East Asia — DR (designed, before wave 2)", "dr")
    d.node("pgd", 1350, 220, "pg", "cross-region replica\nD8ds_v5 · async")
    d.node("caed", 1350, 400, "cae", "cae-aron-dr\nwarm, apps at 0")
    d.node("acrd", 1350, 560, "acr", "ACR geo-replica", size=48)
    d.node("kvd", 1350, 700, "kv", "DR Key Vault", size=44)
    d.edge("ph", "afd", "HV", "HTTPS 443")
    d.edge("bw", "afd", "HV")
    d.edge("afd", "cae", "H", "Private Link")
    d.edge("waf", "afd", "V", arrow=False, dashed=True)
    d.edge("cae", "api", "V")
    d.edge("cae", "wrk", "VH")
    d.edge("cae", "web", "VH")
    d.edge("cae", "jobs", "H")
    d.edge("api", "pg", via=[(560, 330), (940, 330), (940, 230)], label="6432 PgBouncer")
    d.edge("api", "pgr", via=[(560, 460), (960, 460), (960, 440)], label="reads")
    d.edge("wrk", "pg", via=[(700, 340), (955, 340), (955, 230)], label="5432")
    d.edge("pg", "pgs", "VH", "sync, RPO 0")
    d.edge("pgs", "pgr", "VH", "async", dashed=True)
    d.edge("ph", "st", via=[(90, 870), (520, 870)], label="SAS PUT (photos)", dashed=True)
    d.edge("st", "eg", "H")
    d.edge("eg", "wrk", via=[(660, 600), (700, 600)], dashed=True, label="queue → worker (consumer planned)")
    d.edge("api", "kv", via=[(600, 560), (780, 560)], dashed=True, label="secret refs")
    d.edge("acr", "cae", via=[(900, 590), (470, 590), (470, 220)], dashed=True, label="pull by digest")
    d.edge("cae", "law", via=[(640, 220), (640, 133), (1180, 133), (1180, 620), (1030, 620)], dashed=True, label="logs, metrics")
    d.edge("pg", "pgd", "HV", dashed=True, label="async WAL", color="#b06a12", lpos=0.62)
    d.edge("acr", "acrd", via=[(900, 610), (1210, 610), (1210, 560)], dashed=True, color="#b06a12", label="geo-replication")
    d.edge("afd", "caed", via=[(300, 885), (1440, 885), (1440, 400)], dashed=True, color="#b06a12", label="DR origin (disabled)", lpos=0.35)
    return d


# ================================================================================================
# HLD 4: availability and DR
# ================================================================================================
def d_dr():
    d = Diagram("hld-4-dr", 1300, 560, "HLD 4 — Availability and disaster recovery: zone loss (automatic), data loss (restore), region loss (DR promotion)")
    d.group(40, 60, 760, 460, "Southeast Asia — primary region, three availability zones", "region")
    d.group(60, 100, 230, 400, "Zone 1", "subnet"); d.group(310, 100, 230, 400, "Zone 2", "subnet"); d.group(560, 100, 220, 400, "Zone 3", "subnet")
    d.node("a1", 175, 180, "capp", "api replica"); d.node("a2", 425, 180, "capp", "api replica"); d.node("a3", 670, 180, "capp", "api replica")
    d.node("p1", 175, 360, "pg", "PostgreSQL primary", sub="RPO 0")
    d.node("p2", 425, 360, "pg", "HA standby\nsynchronous", sub="failover 60–120 s")
    d.node("p3", 670, 360, "pg", "read replica\nasync")
    d.node("st", 425, 480, "storage", "RA-GZRS storage (3 zones + secondary region)", size=36)
    d.group(840, 60, 420, 460, "East Asia — DR region (designed, before wave 2)", "dr")
    d.node("pd", 950, 200, "pg", "cross-region replica\nsame SKU", sub="RPO 15 min · RTO 4 h")
    d.node("cd", 1130, 200, "cae", "warm environment\napps at 0 replicas")
    d.node("ad", 950, 400, "acr", "ACR geo-replica", size=44)
    d.node("kd", 1130, 400, "kv", "DR Key Vault", size=44)
    d.node("gb", 1040, 480, "backup", "geo-redundant backup (35 d PITR)", size=34)
    d.edge("p1", "p2", "H", "sync", dashed=False)
    d.edge("p1", "p3", via=[(175, 440), (670, 440)], label="async", dashed=True)
    d.edge("p1", "pd", via=[(175, 300), (900, 300), (900, 260)], label="async WAL", dashed=True, color="#b06a12")
    d.note(60, 545, "Zone loss: the standby is promoted under the same name; apps retry with the same batch_uuid. Data loss: PITR to a new server, phones re-send rows after the restore point (server generation).", 10.5)
    return d


# ================================================================================================
# LLD 1: network and traffic flow
# ================================================================================================
def d_network():
    d = Diagram("lld-1-network", 1400, 720, "LLD 1 — Network: one entrance, private application network, database reachable only from the app subnet")
    d.node("ph", 90, 200, "mobile", "Phones")
    d.node("bw", 90, 360, "browser", "Browsers")
    d.group(190, 80, 230, 400, "Front Door Premium (global)", "region")
    d.node("waf", 305, 180, "waf", "WAF wafaronprod\nPrevention · bot rules · IP backstop")
    d.node("afd", 305, 360, "frontdoor", "routes /v1/* → api\n/* → web")
    d.group(460, 80, 640, 600, "VNet vnet-aron-prod 10.40.0.0/16", "vnet")
    d.group(480, 120, 360, 250, "snet-aca 10.40.0.0/23", "subnet")
    d.node("pe", 560, 220, "plink", "Front Door Private Link\n(managed, approved by the deploy)", size=44)
    d.node("api", 690, 220, "capp", "api replicas")
    d.node("web", 790, 220, "capp", "web replicas", size=44)
    d.node("wrk", 690, 320, "capp", "worker", size=40)
    d.group(860, 120, 220, 250, "snet-pg 10.40.2.0/28", "subnet")
    d.node("nsg", 920, 170, "nsg", "NSG", size=36)
    d.node("pg", 970, 250, "pg", "psql-aron-prod\n:5432 / :6432")
    d.node("pgr", 970, 340, "pg", "replica", size=36)
    d.group(480, 400, 600, 260, "snet-pe 10.40.3.0/24 (planned) — private endpoints", "subnet")
    d.node("pekv", 580, 500, "pe", "pe-kv", size=40); d.node("peacr", 700, 500, "pe", "pe-acr", size=40); d.node("peblob", 820, 500, "pe", "pe-blob", size=40)
    d.node("dns", 980, 500, "dns", "private DNS zones", size=40)
    d.node("kv", 1220, 180, "kv", "Key Vault", size=48)
    d.node("acr", 1220, 330, "acr", "Container Registry", size=48)
    d.node("st", 1220, 500, "storage", "Storage (public endpoint\nkept for SAS uploads)", size=48)
    d.edge("ph", "waf", "HV", "443")
    d.edge("bw", "afd", "H", "443")
    d.edge("waf", "afd", "V", arrow=False)
    d.edge("afd", "pe", "HV", "Private Link")
    d.edge("pe", "api", "H")
    d.edge("api", "pg", "H", "6432 (NSG allow from snet-aca)")
    d.edge("wrk", "pg", "HV", "5432")
    d.edge("api", "pgr", via=[(690, 270), (900, 270), (900, 340)], label="6432 reads")
    d.edge("ph", "st", via=[(90, 690), (1220, 690)], label="443 SAS PUT", dashed=True)
    d.edge("pekv", "kv", via=[(580, 440), (1140, 440), (1140, 180)], dashed=True)
    d.edge("peacr", "acr", via=[(700, 430), (1150, 430), (1150, 330)], dashed=True)
    d.edge("peblob", "st", "H", dashed=True)
    d.note(470, 700, "No public address on the application: the environment refuses public traffic; PostgreSQL has no public endpoint; the API rejects any request without the Front Door ID header.", 10.5)
    return d


# ================================================================================================
# LLD 2: a sale from phone to dashboard (sync path)
# ================================================================================================
def d_sync():
    d = Diagram("lld-2-sync-flow", 1400, 520, "LLD 2 — The critical path: a sale captured offline, synced once, aggregated, shown on the dashboard")
    d.node("ph", 90, 220, "mobile", "SR phone\noffline outbox (SQLCipher)", sub="client UUIDs, batch_uuid")
    d.node("afd", 300, 220, "frontdoor", "Front Door\n+ WAF")
    d.node("api", 510, 220, "capp", "api\nPOST /v1/sync/batch")
    d.node("pg", 760, 220, "pg", "PostgreSQL primary\none transaction per batch", sub="upsert by UUID · outbox event · stored ack")
    d.node("wrk", 1010, 220, "capp", "worker\noutbox → dw aggregates")
    d.node("pgr", 1010, 400, "pg", "read replica\ndw.agg_daily_*", size=48)
    d.node("web", 1250, 400, "browser", "Dashboard user\nAMO · TSO · HQ (lag p95 60 s)")
    d.node("prn", 90, 420, "device", "memo printed\nbefore any network", size=44)
    d.edge("ph", "afd", "H", "1 gzip batch ≤ 200 rows")
    d.edge("afd", "api", "H", "2 Private Link, FDID check")
    d.edge("api", "pg", "H", "3 upsert, idempotent")
    d.edge("pg", "wrk", "H", "4 outbox poll")
    d.edge("wrk", "pgr", "V", "5 aggregates replicate")
    d.edge("pgr", "web", "H", "6 read")
    d.edge("api", "ph", via=[(510, 120), (90, 120)], label="ack per row (a repeat returns the stored response)", dashed=True)
    d.edge("ph", "prn", "V", dashed=True, arrow=True)
    d.note(60, 500, "Why it is safe at 4,500 rows/s: short transactions, a unique index on every client UUID, asynchronous aggregation that catches up from the outbox, and phones that back off with jitter on 429/503.", 10.5)
    return d


# ================================================================================================
# LLD 3: photo pipeline
# ================================================================================================
def d_photos():
    d = Diagram("lld-3-photos", 1300, 440, "LLD 3 — Photo pipeline: bytes never pass through the API")
    d.node("ph", 90, 200, "mobile", "SR phone\ncompressed photo")
    d.node("api", 330, 120, "capp", "api\nPOST /v1/media/sas", sub="up to 10 write-only URLs")
    d.node("blob", 600, 200, "blob", "Blob container media\nRA-GZRS", sub="Hot 30 d → Cool → Cold 90 d → Archive 365 d")
    d.node("eg", 830, 200, "eventgrid", "Event Grid\nBlobCreated", size=48)
    d.node("q", 1010, 200, "queue", "queue media-events", size=44)
    d.node("wrk", 1190, 200, "capp", "worker consumer\n(planned)")
    d.node("pg", 1190, 370, "pg", "PostgreSQL\nmemo ↔ photo", size=44)
    d.edge("ph", "api", "HV", "1 ask for SAS")
    d.edge("api", "ph", via=[(330, 60), (90, 60)], label="2 SAS URLs", dashed=True)
    d.edge("ph", "blob", "H", "3 PUT by SAS (TLS)")
    d.edge("blob", "eg", "H", "4 event")
    d.edge("eg", "q", "H", "5")
    d.edge("q", "wrk", "H", "6 at-least-once", dashed=True)
    d.edge("wrk", "pg", "V", "7 link (idempotent)", dashed=True)
    d.note(60, 400, "Steps 1 to 5 are in the templates; the queue consumer (6, 7) and the nightly sweep are planned. The API carries only small feedback and support JPEGs (at most 300 KB). Phone budget: photos at most 3 MB a day.", 10.5)
    return d


# ================================================================================================
# LLD 4: identity, access and secrets
# ================================================================================================
def d_identity():
    d = Diagram("lld-4-identity", 1300, 560, "LLD 4 — Identity and secrets: no stored cloud credentials, one managed identity per process")
    d.box("gh", 130, 120, 200, 50, "GitHub Actions", "OIDC federated credential")
    d.node("dep", 400, 120, "mi", "deploy identity\nContributor on rg-aron-prod only", size=48)
    d.node("rg", 680, 120, "rg", "rg-aron-prod", size=48)
    d.node("entra", 1000, 120, "entra", "Microsoft Entra ID\nMFA (TOTP) for admins", size=48)
    d.node("iapi", 160, 330, "mi", "id-aron-prod-api", size=44)
    d.node("iwrk", 340, 330, "mi", "id-aron-prod-worker", size=44)
    d.node("iweb", 520, 330, "mi", "id-aron-prod-web", size=44)
    d.node("imig", 700, 330, "mi", "id-aron-prod-migrate", size=44)
    d.node("kv", 300, 480, "kv", "Key Vault kv-aron-prod\nSecrets User (vault scope, all four)", size=48)
    d.node("ksign", 460, 480, "kv", "signing vault\n(designed)", size=44)
    d.node("acr", 640, 480, "acr", "Container Registry\nAcrPull (all four)", size=48)
    d.node("st", 820, 480, "storage", "Storage\napi: Blob Contributor + Delegator\nworker: Blob Contributor, Queue Message Processor", size=48)
    d.node("pg", 1000, 480, "pg", "PostgreSQL logins\napp_api · app_jobs · app_bi · lake_export", size=48, sub="built; OFF in prod params until go-live")
    d.edge("gh", "dep", "H", "token exchange")
    d.edge("dep", "rg", "H", "deploys")
    d.edge("iapi", "kv", "VH"); d.edge("iapi", "ksign", via=[(160, 410), (460, 410)], dashed=True); d.edge("iapi", "acr", via=[(200, 400), (640, 400)]); d.edge("iapi", "st", via=[(120, 330), (120, 440), (820, 440)])
    d.edge("iwrk", "kv", "VH"); d.edge("iwrk", "acr", "VH"); d.edge("iwrk", "st", via=[(340, 420), (820, 420)])
    d.edge("iweb", "kv", via=[(520, 390), (300, 390)]); d.edge("iweb", "acr", "VH")
    d.edge("imig", "kv", via=[(700, 380), (300, 380)]); d.edge("imig", "acr", "VH")
    d.edge("kv", "pg", via=[(300, 540), (1000, 540)], label="connection secrets (names only, values in the vault)", dashed=True)
    d.note(60, 240, "Rule: the client never sends scope; reach is derived on the server from role and assignment and signed into the token. Per-secret vault RBAC and the signing vault are designed; today every app identity can read the whole vault.", 11)
    return d


# ================================================================================================
# LLD 5: observability
# ================================================================================================
def d_observability():
    d = Diagram("lld-5-observability", 1300, 520, "LLD 5 — Observability: every service reports to one workspace; alerts map to runbooks")
    d.node("cae", 120, 150, "cae", "Container Apps\nconsole logs, metrics", size=48)
    d.node("pg", 120, 280, "pg", "PostgreSQL\nmetrics, slow queries", size=48)
    d.node("afd", 120, 410, "frontdoor", "Front Door\naccess + WAF logs", size=48)
    d.node("st", 330, 410, "storage", "Storage, Key Vault, ACR\ndiagnostics", size=44)
    d.node("diag", 330, 200, "diag", "diagnostic settings", size=44)
    d.node("law", 600, 250, "law", "log-aron-prod\nPerGB2018 · 90 d · 8 GB/day cap", sub="design volume 5.5 GB/day")
    d.node("appi", 600, 430, "appi", "appi-aron-prod\nJava agent traces", size=48)
    d.node("al", 880, 150, "alerts", "alert rules (IaC)\n5 log-search · PostgreSQL CPU, storage 70 %,\nconnections, not-alive · app 5xx, restarts · resource health", size=48)
    d.node("wb", 880, 300, "workbook", "workbook: six App Insights tiles\n(batches, latency, errors, aggregation)", size=48)
    d.node("bud", 880, 440, "budget", "budget 50/90/100 %", size=44)
    d.box("ag", 1140, 150, 200, 50, "action group ag-aron-prod", "e-mail (SMS designed)")
    d.box("rb", 1140, 300, 200, 50, "runbooks", "failover · restore · rollback")
    d.edge("cae", "diag", "H"); d.edge("pg", "diag", "HV"); d.edge("afd", "diag", via=[(250, 410), (250, 200)]); d.edge("st", "diag", "V")
    d.edge("diag", "law", "H")
    d.edge("appi", "law", "V", dashed=True)
    d.edge("law", "al", "HV"); d.edge("law", "wb", "H")
    d.edge("al", "ag", "H"); d.edge("al", "rb", via=[(1020, 150), (1020, 300)])
    d.edge("bud", "ag", via=[(1040, 440), (1040, 150)], dashed=True)
    return d


# ================================================================================================
# LLD 6: delivery pipeline
# ================================================================================================
def d_pipeline():
    d = Diagram("lld-6-pipeline", 1400, 420, "LLD 6 — Delivery: one pipeline, digest-pinned images, a health gate before traffic moves")
    d.box("gh", 110, 160, 160, 54, "GitHub\nintegration branch")
    d.box("ci", 320, 160, 170, 54, "ci.yml\ngates · tests · images")
    d.node("acr", 540, 160, "acr", "Container Registry\npush by digest", size=48)
    d.box("dep", 750, 160, 180, 54, "deploy.yml\nwhat-if guard")
    d.node("mig", 960, 300, "capp", "migrate job\nFlyway first", size=44)
    d.node("pg", 1160, 300, "pg", "PostgreSQL", size=44)
    d.node("cae", 1160, 160, "cae", "new revisions\napi · worker · web", size=48)
    d.node("afd", 1330, 160, "frontdoor", "health gate\n/v1/health = sha", size=44)
    d.edge("gh", "ci", "H", "push"); d.edge("ci", "acr", "H", "green"); d.edge("acr", "dep", "H")
    d.edge("dep", "mig", "HV", "1"); d.edge("mig", "pg", "H", "migrations"); d.edge("dep", "cae", "H", "2 after migrate")
    d.edge("cae", "afd", "H", "3 smoke")
    d.edge("afd", "cae", via=[(1330, 80), (1160, 80)], label="fail → traffic back to the previous revision", dashed=True)
    d.note(60, 380, "Promotion to prod from a server-vX.Y.Z tag after one trading day on staging; rollback: traffic switch (seconds), redeploy by digest (8–15 min), PITR for a bad migration.", 10.5)
    return d


# ================================================================================================
# LLD 7: scaling through the day
# ================================================================================================
def d_scaling():
    d = Diagram("lld-7-scaling", 1300, 400, "LLD 7 — Scaling rules: replicas follow the field day (Dhaka time)")
    # a simple bar timeline drawn with rects
    x0, y0, w = 120, 90, 1100
    hours = ["00", "03", "06", "09", "12", "15", "18", "21", "24"]
    for i, h in enumerate(hours):
        x = x0 + w * i / 8
        d.parts.append(f'<line x1="{x}" y1="{y0}" x2="{x}" y2="{y0+200}" stroke="#ddd"/><text x="{x}" y="{y0+218}" font-size="11" text-anchor="middle" fill="#555">{h}:00</text>')
    def bar(row, start_h, end_h, label, fill):
        y = y0 + 20 + row * 60
        x1 = x0 + w * start_h / 24; x2 = x0 + w * end_h / 24
        d.parts.append(f'<rect x="{x1}" y="{y}" width="{x2-x1}" height="36" fill="{fill}" stroke="#7a8aa6"/><text x="{(x1+x2)/2}" y="{y+23}" font-size="11.5" text-anchor="middle" fill="#1b1b1b">{esc(label)}</text>')
    d.parts.append(f'<text x="{x0-10}" y="{y0+43}" font-size="12" text-anchor="end" font-weight="600">api</text>')
    bar(0, 0, 6.25, "min 3 (one per zone)", "#dfe9f7"); bar(0, 6.25, 10, "pre-scale 8 · storm", "#b7cdea"); bar(0, 10, 16.75, "min 3, autoscale to 30 on demand", "#dfe9f7"); bar(0, 16.75, 20, "pre-scale 8 · check-out", "#b7cdea"); bar(0, 20, 24, "min 3", "#dfe9f7")
    d.parts.append(f'<text x="{x0-10}" y="{y0+103}" font-size="12" text-anchor="end" font-weight="600">worker</text>')
    bar(1, 0, 24, "min 1, KEDA on outbox depth (200 rows/replica) and photo queue (100/replica), max 10", "#e8f0e3")
    d.parts.append(f'<text x="{x0-10}" y="{y0+163}" font-size="12" text-anchor="end" font-weight="600">web</text>')
    bar(2, 0, 16.75, "min 2", "#f3e9dc"); bar(2, 16.75, 20, "pre-scale 4 (designed) · 1,500 readers", "#e5cfae"); bar(2, 20, 24, "min 2", "#f3e9dc")
    d.note(120, 330, "Database: D8ds_v5 all day (HA); step up to D16ds_v5 in place if the load test shows CPU above 80 % or IOPS above 7,200. Disk: 1,024 GiB, grow to 2,048 GiB by month 6.", 11)
    d.note(120, 352, "Autoscale rule: HTTP 50 concurrent requests per api replica, evaluated every 15 s, doubling up-steps, scale-in after 300 s.", 11)
    return d


# ================================================================================================
# Spec+: existing web properties migrated into the final account
# ================================================================================================
def d_specplus():
    d = Diagram("specplus-existing-sites", 1300, 520, "Spec+ — Existing web properties migrated into the final account (shares the edge, DNS and monitoring with Aron)")
    d.node("vis", 90, 200, "browser", "Visitors")
    d.node("afd", 300, 200, "frontdoor", "Front Door Premium\n(shared with Aron)", sub="custom domains, managed TLS")
    d.node("dns", 300, 400, "dns", "Azure DNS zones\naktcl.com · aksilage.com ·\nshahagro.com · arondeepstate.com", size=44)
    d.group(460, 80, 560, 400, "Resource group rg-aktcl-web (Southeast Asia)", "region")
    d.node("asp", 580, 180, "asp", "App Service plan\nLinux P1v3 (2 vCPU / 8 GiB)", sub="1 instance, autoscale to 2")
    d.node("w1", 760, 150, "appservice", "aktcl.com", size=40); d.node("w2", 880, 150, "appservice", "aksilage.com", size=40)
    d.node("w3", 760, 250, "appservice", "shahagro.com", size=40); d.node("w4", 880, 250, "appservice", "arondeepstate.com", size=40)
    d.node("w5", 960, 200, "appservice", "+ 2 reserve", size=32)
    d.node("my", 580, 380, "mysql", "MySQL Flexible Server\nB2s · 64 GiB · 7-day backup", size=48)
    d.node("st", 800, 380, "storage", "Storage LRS\nmedia · backups", size=44)
    d.node("cert", 960, 380, "cert", "managed certificates\n(free)", size=40)
    d.node("law", 1160, 200, "law", "Log Analytics\n(shared)", size=44)
    d.node("bud", 1160, 380, "budget", "budget", size=36)
    d.edge("vis", "afd", "H", "443"); d.edge("afd", "asp", "H", "routes per domain")
    d.edge("asp", "w1", "HV"); d.edge("asp", "w3", "HV"); d.edge("w1", "w2", "H", arrow=False); d.edge("w3", "w4", "H", arrow=False)
    d.edge("asp", "my", "V", "3306 (private)"); d.edge("asp", "st", via=[(580, 300), (800, 300)], dashed=True)
    d.edge("dns", "afd", "V", arrow=False, dashed=True)
    d.edge("asp", "law", via=[(580, 100), (1160, 100)], dashed=True, label="diagnostics")
    d.note(460, 500, "Assumption: the sites are PHP/WordPress or static sites on App Service; replace with the current subscription's inventory before the move.", 10.5)
    return d


DIAGRAMS = [d_context, d_logical, d_deployment, d_dr, d_network, d_sync, d_photos, d_identity, d_observability, d_pipeline, d_scaling, d_specplus]


def render_png(svg_paths):
    """PNG rendering uses the Node Playwright that ships with the container (render-png.js)."""
    import subprocess
    subprocess.run(["node", str(pathlib.Path(__file__).resolve().parent / "render-png.js"), str(OUT)], check=True)


if __name__ == "__main__":
    paths = []
    for fn in DIAGRAMS:
        d = fn()
        paths.append(d.write())
    render_png(paths)
    for p in paths:
        print(p.with_suffix(".png").name, p.with_suffix(".png").stat().st_size, "bytes")
