#!/usr/bin/env python3
"""Offline acceptance checks for N-012 (Azure IaC) and N-013 (CI/CD). Standard library only.

Run by infra/validate.sh after `bicep build`, with ARON_COMPILED pointing at the folder holding main.json,
apps.json and the compiled parameter files (<name>.parameters.json). What these checks prove without a subscription:
the templates deploy into ONE resource group only, carry no hard-coded subscription, region or secret, contain every
resource the row asks for with the reliability settings the spec fixes, and the workflows deploy only from the
integration branch with OIDC and pinned actions. What they cannot prove (needs Azure) is listed in infra/README.md.
"""
import hashlib
import json
import os
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMPILED = Path(os.environ.get("ARON_COMPILED", ROOT / "infra" / ".compiled"))
WORKFLOWS = ROOT / ".github" / "workflows"
INTEGRATION_BRANCH = "claude/wonderful-thompson-k6ejnf"
RG_SCHEMA = "https://schema.management.azure.com/schemas/2019-04-01/deploymentTemplate.json#"

# Built-in roles the templates may grant (all data-plane or registry roles; never Owner, Contributor or UAA).
ALLOWED_ROLES = {
    "7f951dda-4ed3-4680-a7ca-43fe172d538d",  # AcrPull
    "8311e382-0749-4cb8-b61a-304f252e45ec",  # AcrPush
    "4633458b-17de-408a-b874-0445c86b69e6",  # Key Vault Secrets User
    "b86a8fe4-44ce-4948-aee5-eccb2c155cd7",  # Key Vault Secrets Officer
    "ba92f5b4-2d11-453d-a403-e96b0029c9fe",  # Storage Blob Data Contributor
    "db58b8e5-c6ad-4a2a-8342-4190687cbf4a",  # Storage Blob Delegator
    "8a0f0c08-91a1-4084-bc3d-661d67233fed",  # Storage Queue Data Message Processor
    "c6a89b2d-59bc-44d0-9896-0f6e12d7b80a",  # Storage Queue Data Message Sender
}


def load(name):
    return json.loads((COMPILED / name).read_text(encoding="utf-8"))


def walk_resources(template, path="root"):
    """Yields (resource, template_path) for every resource, descending into nested module deployments."""
    resources = template.get("resources", [])
    items = resources.values() if isinstance(resources, dict) else resources
    for r in items:
        if r.get("existing"):
            continue  # a reference to a resource of stage 1, not a resource this template deploys
        yield r, path
        if r.get("type") == "Microsoft.Resources/deployments":
            inner = r.get("properties", {}).get("template")
            if inner:
                yield from walk_resources(inner, f"{path}/{r.get('name')}")


def nested_templates(template):
    yield template
    for r, _ in walk_resources(template):
        if r.get("type") == "Microsoft.Resources/deployments" and r.get("properties", {}).get("template"):
            yield r["properties"]["template"]


def types_of(template):
    return [r.get("type") for r, _ in walk_resources(template)]


def module(template_name, deployment_name):
    """The compiled template of one module deployment (main.json -> 'postgres', 'frontdoor', ...)."""
    for r, _ in walk_resources(load(template_name)):
        if r.get("type") == "Microsoft.Resources/deployments" and r.get("name") == deployment_name:
            return r["properties"]["template"], r["properties"].get("parameters", {})
    raise AssertionError(f"module {deployment_name} not found in {template_name}")


def resources_of(template, type_):
    return [r for r, _ in walk_resources(template) if r.get("type") == type_]


def param_default(template_name, name):
    return load(template_name)["parameters"][name].get("defaultValue")


def params(name):
    return {k: v.get("value") for k, v in load(name)["parameters"].items()}


class ResourceGroupScopeOnly(unittest.TestCase):
    """Acceptance N-012: everything deploys into the one group; nothing needs subscription rights."""

    def test_every_template_targets_a_resource_group(self):
        for name in ("main.json", "apps.json"):
            for t in nested_templates(load(name)):
                self.assertEqual(t.get("$schema"), RG_SCHEMA, f"{name}: a template is not resource-group scoped")

    def test_no_module_leaves_the_group(self):
        for name in ("main.json", "apps.json"):
            for r, where in walk_resources(load(name)):
                if r.get("type") == "Microsoft.Resources/deployments":
                    self.assertNotIn("subscriptionId", r, f"{where}: module deploys to another subscription")
                    self.assertNotIn("resourceGroup", r, f"{where}: module deploys to another group")
                self.assertNotEqual(r.get("type"), "Microsoft.Resources/resourceGroups", f"{where}: creates a group")
                scope = json.dumps(r.get("scope", ""))
                self.assertNotIn("/subscriptions/", scope, f"{where}: {r.get('type')} scoped outside the group")

    def test_no_hard_coded_subscription_region_or_tenant(self):
        guid = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        for src in list((ROOT / "infra").rglob("*.bicep")) + list((ROOT / "infra").rglob("*.bicepparam")):
            text = src.read_text(encoding="utf-8")
            for g in guid.findall(text):
                self.assertIn(g, ALLOWED_ROLES | set(), f"{src}: literal GUID {g} (subscription or tenant id?)")
            if src.suffix == ".bicep":
                self.assertNotRegex(text, r"location:\s*'(?!global')[a-z]+'", f"{src}: hard-coded region")

    def test_region_comes_from_a_parameter(self):
        for name in ("main.json", "apps.json"):
            for r, where in walk_resources(load(name)):
                loc = r.get("location")
                if loc is None:
                    continue
                self.assertTrue(loc == "global" or loc.startswith("["), f"{where}: {r.get('type')} location {loc}")

    def test_role_assignments_are_least_privilege(self):
        for r, where in walk_resources(load("main.json")):
            if r.get("type") == "Microsoft.Authorization/roleAssignments":
                rd = json.dumps(r["properties"]["roleDefinitionId"])
                ids = set(re.findall(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", rd))
                # Loops reference roles through the exported variable; resolve those in the source instead.
                if not ids:
                    continue
                self.assertTrue(ids <= ALLOWED_ROLES, f"{where}: grants a role outside the allowed set: {ids}")
        naming = (ROOT / "infra" / "lib" / "naming.bicep").read_text(encoding="utf-8")
        for g in re.findall(r"'([0-9a-f-]{36})'", naming):
            self.assertIn(g, ALLOWED_ROLES, f"naming.bicep lists a role outside the allowed set: {g}")


class StackIsComplete(unittest.TestCase):
    REQUIRED_MAIN = [
        "Microsoft.Cdn/profiles",
        "Microsoft.Cdn/profiles/afdEndpoints",
        "Microsoft.Cdn/profiles/securityPolicies",
        "Microsoft.Network/FrontDoorWebApplicationFirewallPolicies",
        "Microsoft.App/managedEnvironments",
        "Microsoft.ContainerRegistry/registries",
        "Microsoft.DBforPostgreSQL/flexibleServers",
        "Microsoft.DBforPostgreSQL/flexibleServers/databases",
        "Microsoft.DBforPostgreSQL/flexibleServers/configurations",
        "Microsoft.Storage/storageAccounts",
        "Microsoft.Storage/storageAccounts/blobServices/containers",
        "Microsoft.KeyVault/vaults",
        "Microsoft.KeyVault/vaults/secrets",
        "Microsoft.OperationalInsights/workspaces",
        "Microsoft.Insights/components",
        "Microsoft.Insights/actionGroups",
        "Microsoft.Insights/scheduledQueryRules",
        "Microsoft.Insights/metricAlerts",
        "Microsoft.Consumption/budgets",
        "Microsoft.ManagedIdentity/userAssignedIdentities",
        "Microsoft.Authorization/roleAssignments",
        "Microsoft.Network/virtualNetworks",
        "Microsoft.Network/privateDnsZones",
        "Microsoft.EventGrid/systemTopics/eventSubscriptions",
    ]
    REQUIRED_APPS = [
        "Microsoft.App/jobs",
        "Microsoft.App/containerApps",
        "Microsoft.Cdn/profiles/originGroups",
        "Microsoft.Cdn/profiles/originGroups/origins",
        "Microsoft.Cdn/profiles/afdEndpoints/routes",
    ]

    def test_main_has_every_component(self):
        present = set(types_of(load("main.json")))
        for t in self.REQUIRED_MAIN:
            self.assertIn(t, present, f"main.bicep lacks {t}")

    def test_apps_has_job_apps_and_routes(self):
        present = types_of(load("apps.json"))
        for t in self.REQUIRED_APPS:
            self.assertIn(t, present, f"apps.bicep lacks {t}")
        self.assertEqual(present.count("Microsoft.App/containerApps"), 3, "api, worker and web apps")

    def test_diagnostic_settings_cover_the_services(self):
        scoped = set()
        for r, where in walk_resources(load("main.json")):
            if r.get("type") == "Microsoft.Insights/diagnosticSettings":
                scoped.add(where.split("/")[-1])
        for module in ("keyvault", "registry", "postgres", "storage", "containerenv", "frontdoor"):
            self.assertIn(module, scoped, f"no diagnostic setting in module {module}")

    def test_secret_names_of_docs_24(self):
        text = json.dumps(load("apps.json")) + json.dumps(load("main.json"))
        for s in ("aron-jwt-signing-key", "aron-fcm-service-account", "aron-db-url", "aron-db-read-url"):
            self.assertIn(s, text, f"Key Vault secret {s} is not used")
        seed = (ROOT / "infra" / "scripts" / "seed-secrets.sh").read_text(encoding="utf-8") \
            + (ROOT / "infra" / "scripts" / "db-login-secrets.sh").read_text(encoding="utf-8")
        for s in ("aron-jwt-signing-key", "aron-jwt-kid", "aron-fcm-service-account", "aron-web-session-secret"):
            self.assertIn(s, seed, f"apps reference {s} but seed-secrets.sh never creates it")
        apps = (ROOT / "infra" / "apps.bicep").read_text(encoding="utf-8")
        naming = (ROOT / "infra" / "lib" / "naming.bicep").read_text(encoding="utf-8")
        for key in re.findall(r"secretNames\.(\w+)", apps):
            name = re.search(r"%s: '([\w-]+)'" % key, naming).group(1)
            created = name in seed or name in (ROOT / "infra" / "modules" / "keyvault.bicep").read_text(encoding="utf-8") \
                or re.search(r"secretNames\.%s\b" % key, (ROOT / "infra" / "modules" / "keyvault.bicep").read_text(encoding="utf-8"))
            self.assertTrue(created, f"apps.bicep references Key Vault secret {name}, which nothing creates")

    def test_front_door_references_are_conditional(self):
        # An unconditional `existing` node is read at deployment time and fails in the TEST profile (no Front Door).
        res = load("apps.json")["resources"]
        for name in ("fd", "fdEndpoint"):
            self.assertEqual(res[name].get("condition"), "[parameters('frontDoorEnabled')]", f"{name} must be conditional")

    def test_one_image_three_roles(self):
        text = json.dumps(load("apps.json"))
        for role in ("migrate", "api", "worker"):
            found = re.search(r'"name": "ARON_ROLE", "value": "%s"|\'ARON_ROLE\', \'value\', \'%s\'' % (role, role), text)
            self.assertTrue(found, f"no container runs ARON_ROLE={role}")
        self.assertNotIn('"image": "mcr.microsoft.com', text, "an image is hard-coded in apps.bicep")


class SecurityDefaults(unittest.TestCase):
    def resources(self, type_):
        return [r for r, _ in walk_resources(load("main.json")) if r.get("type") == type_]

    def test_storage(self):
        (st,) = self.resources("Microsoft.Storage/storageAccounts")
        p = st["properties"]
        self.assertIs(p["allowBlobPublicAccess"], False)
        self.assertIs(p["allowSharedKeyAccess"], False, "SAS must be user-delegation only")
        self.assertEqual(p["minimumTlsVersion"], "TLS1_2")
        self.assertIs(p["supportsHttpsTrafficOnly"], True)

    def test_key_vault(self):
        (kv,) = self.resources("Microsoft.KeyVault/vaults")
        self.assertIs(kv["properties"]["enableRbacAuthorization"], True)
        self.assertIs(kv["properties"]["enableSoftDelete"], True)

    def test_registry_has_no_admin_user(self):
        (acr,) = self.resources("Microsoft.ContainerRegistry/registries")
        self.assertIs(acr["properties"]["adminUserEnabled"], False)

    def test_postgres_network(self):
        servers = self.resources("Microsoft.DBforPostgreSQL/flexibleServers")
        self.assertEqual(len(servers), 2, "primary and the optional replica")
        for srv in servers:
            self.assertEqual(srv["properties"]["network"], "[variables('network')]")
        t, _ = module("main.json", "postgres")
        self.assertEqual(t["variables"]["network"],
                         "[if(parameters('privateNetworking'), createObject('delegatedSubnetResourceId', parameters('subnetId'), "
                         "'privateDnsZoneArmResourceId', resourceId('Microsoft.Network/privateDnsZones', parameters('dnsZoneName')), "
                         "'publicNetworkAccess', 'Disabled'), createObject('publicNetworkAccess', 'Enabled'))]")
        (fw,) = resources_of(t, "Microsoft.DBforPostgreSQL/flexibleServers/firewallRules")
        self.assertEqual(fw["condition"], "[not(parameters('privateNetworking'))]")
        self.assertEqual(fw["properties"], {"startIpAddress": "0.0.0.0", "endIpAddress": "0.0.0.0"},
                         "without a VNet only Azure services may connect, never the internet")
        self.assertIs(params("prod.parameters.json")["privateNetworking"], True, "FINAL profile is private")

    def test_no_secret_in_outputs(self):
        for name in ("main.json", "apps.json"):
            for t in nested_templates(load(name)):
                for k, o in t.get("outputs", {}).items():
                    self.assertNotRegex(k.lower(), "password|secret", f"{name}: output {k} looks like a secret")
                    self.assertNotIn("listKeys", json.dumps(o), f"{name}: output {k} leaks a key")


class ReliabilityProperties(unittest.TestCase):
    """The settings N-012 promises, asserted on the compiled resources (not only on the parameter files)."""

    def test_postgres_zones_follow_the_live_server_after_a_failover(self):
        # Deploy run 37608044223 failed: the forced-failover drill swapped primary (1 -> 2) and standby (2 -> 1), the
        # template still asked for standby zone 2 and the what-if guard refused. The zones must be parameters that
        # deploy.sh fills from the live server, with the creation defaults only when no server exists yet.
        _, bound = module("main.json", "postgres")
        self.assertEqual(bound["primaryZone"]["value"], "[parameters('postgresPrimaryZone')]")
        self.assertEqual(bound["standbyZone"]["value"], "[parameters('postgresStandbyZone')]")
        self.assertEqual(param_default("main.json", "postgresPrimaryZone"), "1")
        self.assertEqual(param_default("main.json", "postgresStandbyZone"), "2")
        for prof in ("dev", "dev-lite", "stage", "prod"):
            src = (ROOT / "infra" / "params" / f"{prof}.bicepparam").read_text(encoding="utf-8")
            self.assertIn("readEnvironmentVariable('ARON_PG_PRIMARY_ZONE', '')", src, prof)
            self.assertIn("readEnvironmentVariable('ARON_PG_STANDBY_ZONE', '')", src, prof)
            p = params(f"{prof}.parameters.json")
            self.assertEqual((p["postgresPrimaryZone"], p["postgresStandbyZone"]), ("1", "2"),
                             f"{prof}: without a live server the creation defaults apply")
        d = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8").replace("\r\n", "\n")
        start, end = d.index("# >>> pg-live-zones"), d.index("# <<< pg-live-zones")
        # Exported before the parameter comparison (a swap re-runs the infra stage) and before the what-if.
        self.assertLess(end, d.index("params_unchanged() {"))
        self.assertLess(end, d.index("az deployment group what-if"))
        block = d[start:end]
        g = (ROOT / "infra" / "scripts" / "whatif-guard.py").read_text(encoding="utf-8")
        self.assertIn('"properties.highavailability"', g, "the guard itself stays strict")
        # The fake az below ignores --query, so the null guards that keep the tsv fields in place are checked here.
        self.assertIn("[].[name, availabilityZone || '-', highAvailability.mode || '-', "
                      "highAvailability.standbyAvailabilityZone || '-']", block)
        import subprocess
        harness = ("set -euo pipefail\n"
                   "die() { echo \"DIE: $*\"; exit 1; }\nnote() { :; }\n"
                   "az() { [ \"$FAKE_FAIL\" = 1 ] && return 1; printf '%b' \"$FAKE_OUT\"; }\n"
                   "RG=rg; ENV_NAME=dev\n" + block +
                   "echo \"P=${ARON_PG_PRIMARY_ZONE:-unset} S=${ARON_PG_STANDBY_ZONE:-unset}\"\n")
        srv = "psql-aron-dev-7i7g53"
        cases = [
            # (name, az tsv output, rollback, az fails, expected)
            ("after a failover swap (CRLF)", f"{srv}\\t2\\tZoneRedundant\\t1\\r\\n", "", "0", "P=2 S=1"),
            ("fresh server", f"{srv}\\t1\\tZoneRedundant\\t2\\n", "", "0", "P=1 S=2"),
            ("no server", "", "", "0", "P=unset S=unset"),
            ("HA disabled (dev-lite)", f"{srv}\\t1\\tDisabled\\t-\\n", "", "0", "P=1 S=unset"),
            ("SameZone", f"{srv}\\t2\\tSameZone\\t2\\n", "", "0", "P=2 S=unset"),
            ("no zone reported", f"{srv}\\t-\\tZoneRedundant\\t1\\n", "", "0", "P=unset S=unset"),
            ("standby zone missing: never equal to the primary", f"{srv}\\t2\\tZoneRedundant\\t-\\n", "", "0", "P=2 S=1"),
            ("PITR drill restore and replica are ignored",
             f"{srv}\\t2\\tZoneRedundant\\t1\\n{srv}-drill-10071200\\t1\\tDisabled\\t-\\n{srv}-r1\\t3\\tDisabled\\t-\\n",
             "", "0", "P=2 S=1"),
            ("rollback skips the lookup", f"{srv}\\t2\\tZoneRedundant\\t1\\n", "a" * 40, "1", "P=unset S=unset"),
            ("az failure stops the deploy", "", "", "1", "DIE: cannot list"),
            ("two servers of the profile stop the deploy",
             f"{srv}\\t2\\tZoneRedundant\\t1\\npsql-aron-dev-abc123\\t1\\tDisabled\\t-\\n", "", "0", "DIE: more than one"),
            ("standby reported in the primary zone is never asked for", f"{srv}\\t2\\tZoneRedundant\\t2\\n", "", "0", "P=2 S=1"),
            ("given suffix with a hyphen (ARON_NAME_SUFFIX=pilot-2): exact name only",
             f"psql-aron-dev-pilot-2\\t2\\tZoneRedundant\\t1\\n{srv}\\t1\\tDisabled\\t-\\n"
             f"psql-aron-dev-pilot-2-drill-1\\t1\\tDisabled\\t-\\n", "", "0", "P=2 S=1", "pilot-2"),
        ]
        for name, out, rollback, fail, want, *suffix in cases:
            env = dict(os.environ, FAKE_OUT=out, FAKE_FAIL=fail, ROLLBACK_SHA=rollback,
                       ARON_NAME_SUFFIX=(suffix or [""])[0])
            r = subprocess.run(["bash", "-c", harness], env=env, capture_output=True, text=True, cwd=ROOT)
            self.assertIn(want, r.stdout, f"{name}: {r.stdout!r} {r.stderr!r}")

    def test_psql_image_is_imported_by_digest_only(self):
        # Deploy run 37619397240: `az acr import` refused "postgres:16-alpine@sha256:..." (a tag AND a digest).
        d = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        m = re.search(r'^PSQL_SOURCE="([^"]+)"', d, re.M)
        self.assertIsNotNone(m)
        self.assertRegex(m.group(1), r"^docker\.io/library/postgres@sha256:[0-9a-f]{64}$")

    def test_postgres_ha_backup_and_pooling_follow_the_parameters(self):
        t, bound = module("main.json", "postgres")
        primary = [r for r in resources_of(t, "Microsoft.DBforPostgreSQL/flexibleServers") if "createMode" not in r["properties"]]
        self.assertEqual(len(primary), 1)
        p = primary[0]["properties"]
        self.assertEqual(
            p["highAvailability"],
            "[if(equals(parameters('haMode'), 'Disabled'), createObject('mode', 'Disabled'), createObject('mode', "
            "parameters('haMode'), 'standbyAvailabilityZone', if(equals(parameters('haMode'), 'ZoneRedundant'), "
            "parameters('standbyZone'), parameters('primaryZone'))))]",
            "PostgreSQL HA no longer follows haMode with the standby in another zone")
        self.assertEqual(p["backup"]["geoRedundantBackup"], "[if(parameters('geoRedundantBackup'), 'Enabled', 'Disabled')]")
        self.assertIn("parameters('backupRetentionDays')", json.dumps(p["backup"]))
        self.assertEqual(bound["haMode"]["value"], "[parameters('postgresHaMode')]")
        self.assertEqual(bound["geoRedundantBackup"]["value"], "[parameters('postgresGeoRedundantBackup')]")
        self.assertEqual(param_default("main.json", "postgresHaMode"), "ZoneRedundant")
        self.assertIs(param_default("main.json", "postgresGeoRedundantBackup"), True)
        pgb = {x["name"]: x["value"] for x in t["variables"]["pgbouncerSettings"]}
        self.assertEqual(pgb.get("pgbouncer.enabled"), "true", "built-in PgBouncer must be on where the tier has it")
        self.assertEqual(t["variables"]["settings"],
                         "[concat(if(parameters('pgbouncerEnabled'), variables('pgbouncerSettings'), createArray()), "
                         "variables('baseSettings'), variables('queryStoreSettings'))]")
        self.assertEqual(bound["pgbouncerEnabled"]["value"], "[variables('pgbouncerEnabled')]")
        self.assertEqual(load("main.json")["variables"]["pgbouncerEnabled"], "[not(equals(parameters('postgresSkuTier'), 'Burstable'))]",
                         "PgBouncer must be on for every tier that has it (all but Burstable)")
        base = {x["name"]: x["value"] for x in t["variables"]["baseSettings"]}
        self.assertIn("BTREE_GIST", base["azure.extensions"], "docs/requests/db-azure-btree-gist.md")
        self.assertEqual(t["variables"]["replicaSettings"],
                         "[if(parameters('pgbouncerEnabled'), variables('pgbouncerSettings'), createArray())]",
                         "the replica must get the PgBouncer settings of the primary")
        loops = {r["copy"]["name"]: r for r in resources_of(t, "Microsoft.DBforPostgreSQL/flexibleServers/configurations")}
        self.assertEqual(loops["config"]["copy"]["count"], "[length(variables('settings'))]")
        self.assertEqual(loops["replicaConfig"]["copy"]["count"], "[length(variables('replicaSettings'))]")
        self.assertIn("parameters('replicaName')", loops["replicaConfig"]["name"])

    def test_container_apps_environment_is_zone_redundant(self):
        t, bound = module("main.json", "containerenv")
        (env,) = resources_of(t, "Microsoft.App/managedEnvironments")
        self.assertEqual(env["properties"]["zoneRedundant"], "[parameters('zoneRedundant')]")
        self.assertEqual(bound["zoneRedundant"]["value"],
                         "[and(parameters('privateNetworking'), parameters('containerEnvZoneRedundant'))]")
        self.assertIs(param_default("main.json", "containerEnvZoneRedundant"), True)
        prod = params("prod.parameters.json")
        self.assertIs(prod["containerEnvZoneRedundant"], True)
        self.assertIs(prod["privateNetworking"], True, "zone redundancy needs the VNet")

    def test_budget_notifications_are_on(self):
        t, _ = module("main.json", "budget")
        (b,) = resources_of(t, "Microsoft.Consumption/budgets")
        notes = b["properties"]["notifications"]
        self.assertGreaterEqual(len(notes), 2)
        for k, n in notes.items():
            self.assertIs(n["enabled"], True, f"budget notification {k} disabled")
            self.assertEqual(n["contactEmails"], "[parameters('contactEmails')]")

    def test_budget_is_skipped_only_when_the_cost_policy_is_off(self):
        main = load("main.json")
        b = main["resources"]["budget"] if isinstance(main["resources"], dict) else next(
            r for r in main["resources"] if r.get("name") == "budget")
        self.assertEqual(b.get("condition"), "[parameters('deployBudget')]")
        self.assertIs(param_default("main.json", "deployBudget"), True)
        for env in ("dev", "dev-lite", "prod"):
            self.assertIs(params(f"{env}.parameters.json")["deployBudget"], True, f"{env}: budget on by default")
            src = (ROOT / "infra" / "params" / f"{env}.bicepparam").read_text(encoding="utf-8")
            self.assertIn("param deployBudget = envDeployBudget != 'false'", src)
        d = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        self.assertIn('elif grep -qi "cost policy is turned off" <<<"$budget_err"; then', d)
        self.assertIn('die "cannot read budgets in $RG"', d, "any other budget read failure must stop the deploy")
        self.assertIn("::warning::No budget", d)

    def test_waf_is_attached_and_follows_its_mode(self):
        t, bound = module("main.json", "frontdoor")
        (waf,) = resources_of(t, "Microsoft.Network/FrontDoorWebApplicationFirewallPolicies")
        self.assertEqual(waf["properties"]["policySettings"]["mode"], "[parameters('wafMode')]")
        self.assertEqual(waf["properties"]["policySettings"]["enabledState"], "Enabled")
        self.assertEqual(bound["wafMode"]["value"], "[parameters('wafMode')]")
        for env_name in ("dev", "prod"):
            self.assertEqual(params(f"{env_name}.parameters.json")["wafMode"], "Prevention", env_name)
        (sp,) = resources_of(t, "Microsoft.Cdn/profiles/securityPolicies")
        self.assertEqual(sp["properties"]["parameters"]["type"], "WebApplicationFirewall")


class SizingParameters(unittest.TestCase):
    def wf(self, name):
        return (WORKFLOWS / name).read_text(encoding="utf-8")

    def test_final_profile_is_zone_redundant_with_geo_backup(self):
        p = params("prod.parameters.json")
        self.assertEqual(p["postgresHaMode"], "ZoneRedundant")
        self.assertIs(p["postgresGeoRedundantBackup"], True, "geo backup can only be set at creation")
        self.assertEqual(p["postgresSkuTier"], "GeneralPurpose")
        self.assertIs(p["deployFrontDoor"], True)
        for env in ("dev", "prod"):
            q = params(f"{env}.parameters.json")
            self.assertRegex(q["budgetStartDate"], r"^\d{4}-\d{2}-01$", f"{env}: budget must start on the 1st")
            self.assertGreater(q["budgetAmount"], 0)
            self.assertTrue(q["alertEmails"], f"{env}: budget contact e-mail")

    def test_rehearsal_profile_matches_what_exists(self):
        # docs/28 "Exception approved": dev adopts exactly the resources created on 2026-10-06; no fleet additions.
        p = params("dev.parameters.json")
        expected = {
            "postgresSkuTier": "GeneralPurpose", "postgresSkuName": "Standard_D2ds_v5", "postgresStorageType": "PremiumV2_LRS",
            "postgresStorageSizeGb": 128, "postgresStorageIops": 3000, "postgresStorageThroughputMBps": 125,
            "postgresHaMode": "ZoneRedundant", "postgresGeoRedundantBackup": True, "postgresBackupRetentionDays": 7,
            "postgresReadReplica": False, "privateNetworking": True, "deployFrontDoor": True,
            "frontDoorSku": "Standard_AzureFrontDoor", "frontDoorPrivateLink": False, "storageSku": "Standard_ZRS",
            "registrySku": "Basic", "vnetAddressPrefix": "10.51.0.0/16", "acaSubnetPrefix": "10.51.0.0/24",
            "postgresSubnetPrefix": "10.51.2.0/28", "budgetAmount": 130,
        }
        for k, v in expected.items():
            self.assertEqual(p[k], v, f"dev must match the existing resources: {k}")
        a = params("dev.apps.parameters.json")
        self.assertIs(a["frontDoorEnabled"], True)
        self.assertLessEqual(a["apiMaxReplicas"], 2, "no fleet-sized additions")

    def test_reset_deletes_only_for_an_owner_approved_lite_profile(self):
        r = (WORKFLOWS / "reset.yml").read_text(encoding="utf-8")
        self.assertIn('if [ "${CONFIRM_TEXT}" = "owner approved reset" ] && [ "${PROFILE}" = "dev-lite" ]; then', r)
        self.assertNotRegex(r, r"(?m)^\s*(push|pull_request|schedule|workflow_call):", "manual dispatch only")
        script = (ROOT / "infra" / "scripts" / "reset-to-profile.sh").read_text(encoding="utf-8")
        self.assertIn('[ "${OWNER_APPROVED:-}" != yes ] || [ "${env_name%-lite}" = "$env_name" ]', script)

    def test_deploy_runs_the_what_if_guard_before_main(self):
        d = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        self.assertLess(d.index("az deployment group what-if"), d.index("az deployment group create -g \"$RG\" -n aron-infra"))
        self.assertIn('infra/scripts/whatif-guard.py "$whatif_file" || die', d)

    def test_what_if_guard_decisions(self):
        import subprocess, tempfile
        srv = "/subscriptions/s/resourceGroups/rg/providers/Microsoft.DBforPostgreSQL/flexibleServers/psql"
        sub = "/subscriptions/s/resourceGroups/rg/providers/Microsoft.Network/virtualNetworks/v/subnets/snet-pg"

        def run(change, existing=srv):
            with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
                json.dump({"changes": [change]}, f)
            r = subprocess.run([sys.executable, str(ROOT / "infra" / "scripts" / "whatif-guard.py"), f.name],
                               env={**os.environ, "EXISTING_POSTGRES_IDS": existing}, capture_output=True, text=True)
            os.unlink(f.name)
            return r.returncode

        def modify(path, before, after):
            return {"resourceId": srv, "changeType": "Modify", "delta": [
                {"path": path, "propertyChangeType": "Modify", "before": before, "after": after}]}
        self.assertEqual(run(modify("properties.network.delegatedSubnetResourceId", sub, sub.upper())), 0,
                         "a case-only id difference is not a change")
        self.assertEqual(run(modify("properties.network.delegatedSubnetResourceId", sub, sub + "2")), 1)
        self.assertEqual(run(modify("sku.tier", "GeneralPurpose", "Burstable")), 1)
        self.assertEqual(run(modify("properties.administratorLoginPassword", "a", "b")), 0)
        self.assertEqual(run({"resourceId": srv, "changeType": "Delete"}), 1)
        self.assertEqual(run({"resourceId": srv + "2", "changeType": "Create"}), 1, "second server next to one")
        self.assertEqual(run({"resourceId": srv, "changeType": "Create"}, existing=""), 0, "first create")

    def test_network_ids_are_resolvable_by_what_if(self):
        # Module outputs are unknown at what-if time; an adopted server/environment would show a subnet "change".
        m = (ROOT / "infra" / "main.bicep").read_text(encoding="utf-8")
        self.assertNotRegex(m, r"network!?\.outputs\.(vnetId|postgresSubnetId|acaSubnetId)")
        self.assertIn("var pgSubnetId = resourceId('Microsoft.Network/virtualNetworks/subnets', n.vnet, 'snet-pg')", m)
        self.assertIn("var acaSubnetId = resourceId('Microsoft.Network/virtualNetworks/subnets', n.vnet, 'snet-aca')", m)

    def test_stage_profile_is_parameters_only_and_prod_shaped(self):
        # docs/30 s1: staging lives in the final account; here it is a parameter file nobody can deploy yet.
        st, pr = params("stage.parameters.json"), params("prod.parameters.json")
        self.assertEqual(st["environmentName"], "stage")
        for k in ("postgresSkuName", "postgresSkuTier", "postgresStorageType", "postgresHaMode", "privateNetworking",
                  "deployFrontDoor", "frontDoorSku", "containerEnvZoneRedundant", "postgresReadReplica"):
            self.assertEqual(st[k], pr[k], f"stage must be prod-shaped in {k}")
        self.assertNotEqual(st["vnetAddressPrefix"], pr["vnetAddressPrefix"])
        self.assertEqual(params("stage.apps.parameters.json")["environmentName"], "stage")
        d = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        self.assertIn('case "$PROFILE" in dev|dev-lite|prod) ;;', d, "deploy.sh must not offer stage yet")
        options = self.wf("deploy.yml").split("options:")[1].split("\n")[0]
        self.assertNotIn("stage", options)
        self.assertNotIn("prod", options, "AUD-DG-08: no manual prod deploy before the final account (promote-prod only)")

    def test_promotion_workflows_are_inert_until_the_final_account(self):
        pp, ra = self.wf("promote-prod.yml"), self.wf("release-app.yml")
        for name, w in (("promote-prod", pp), ("release-app", ra)):
            self.assertIn("vars.ARON_FINAL_ACCOUNT", w, name)
            self.assertIn(f"title={name} is inert", w, name)
        self.assertIn("needs.guard.outputs.go == 'true'", pp)
        self.assertIn("uses: ./.github/workflows/deploy.yml", pp)
        self.assertIn("git merge-base --is-ancestor", pp, "promote only what is on main")
        self.assertIn('select(.name == "Deploy to stage" and .conclusion == "success")', pp, "soak = a real staging deploy")
        self.assertIn("environment: prod", pp)
        self.assertIn("needs.guard.outputs.go == 'true'", ra)
        for sname in ("ANDROID_SIGNING_KEYSTORE_BASE64", "ANDROID_SIGNING_KEYSTORE_PASSWORD",
                      "ANDROID_SIGNING_KEY_ALIAS", "ANDROID_SIGNING_KEY_PASSWORD"):
            self.assertIn(f"secrets.{sname}", ra)
        self.assertIn("--ks-pass env:KSP", ra, "passwords go to apksigner by environment, never on the command line")
        self.assertIn("SHA256SUMS", ra)

    def test_test_profile_matches_docs_28(self):
        p = params("dev-lite.parameters.json")
        self.assertEqual(p["postgresSkuTier"], "Burstable")
        self.assertRegex(p["postgresSkuName"], r"^Standard_B(1ms|2s)$")
        self.assertEqual(p["postgresStorageType"], "Premium_LRS", "Burstable cannot use SSD v2")
        self.assertLessEqual(p["postgresStorageSizeGb"], 32)
        self.assertEqual(p["postgresHaMode"], "Disabled")
        self.assertIs(p["postgresGeoRedundantBackup"], False)
        self.assertIs(p["postgresReadReplica"], False)
        self.assertEqual(p["postgresBackupRetentionDays"], 7)
        self.assertIs(p["deployFrontDoor"], False, "no Front Door/WAF in the TEST profile")
        self.assertIs(p["privateNetworking"], False)
        self.assertIs(p["enableLogAlerts"], False)
        self.assertEqual(p["storageSku"], "Standard_LRS")
        self.assertEqual(p["registrySku"], "Basic")
        self.assertLessEqual(float(p["logDailyQuotaGb"]), 0.5)
        self.assertLessEqual(p["budgetAmount"], 100, "the owner's whole budget is USD 100 a month")
        a = params("dev-lite.apps.parameters.json")
        self.assertIs(a["frontDoorEnabled"], False)
        self.assertEqual(a["apiMinReplicas"], 0, "api scales to zero")
        self.assertLessEqual(a["apiMaxReplicas"], 2)
        self.assertEqual((a["workerMinReplicas"], a["workerMaxReplicas"]), (1, 1))
        self.assertEqual(a["webMinReplicas"], 0)
        self.assertLessEqual(a["webMaxReplicas"], 1)
        self.assertEqual((a["workerCpu"], a["workerMemory"]), ("0.25", "0.5Gi"), "worker at the minimum size")
        # B1ms admits about 35 client connections (50 minus reserved).
        conns = a["apiMaxReplicas"] * (a["apiDbPoolMax"] + a["apiDbReadPoolMax"]) \
            + a["workerMaxReplicas"] * (a["workerDbPoolMax"] + a["workerDbReadPoolMax"]) + 2
        self.assertLessEqual(conns, 35, f"{conns} connections exceed what a Burstable B1ms admits")

    def test_prod_is_sized_for_the_fleet(self):
        p = params("prod.parameters.json")
        self.assertEqual(p["frontDoorSku"], "Premium_AzureFrontDoor", "managed WAF rules and Private Link")
        self.assertIs(p["frontDoorPrivateLink"], True)
        self.assertIs(p["postgresReadReplica"], True)
        self.assertIs(p["keyVaultPurgeProtection"], True)
        self.assertEqual(p["postgresBackupRetentionDays"], 35)
        self.assertEqual(p["postgresSkuName"], "Standard_D8ds_v5")
        a = params("prod.apps.parameters.json")
        self.assertGreaterEqual(a["apiMinReplicas"], 3, "at least one api replica per zone")
        self.assertGreaterEqual(a["apiMaxReplicas"], 30)

    def test_private_link_needs_premium(self):
        for env in ("dev", "prod"):
            p = params(f"{env}.parameters.json")
            if p["frontDoorPrivateLink"]:
                self.assertEqual(p["frontDoorSku"], "Premium_AzureFrontDoor", env)
            a = params(f"{env}.apps.parameters.json")
            self.assertEqual(a["frontDoorPrivateLink"], p["frontDoorPrivateLink"], f"{env}: infra and apps disagree")


class PowerShellScripts(unittest.TestCase):
    def test_no_jmespath_functions_in_az_queries(self):
        # az is a .cmd wrapper on Windows: cmd.exe re-parses the arguments and breaks on the parentheses of a JMESPath
        # function ("-o was unexpected at this time", docs/status/laptop.md). Filter in PowerShell instead.
        for ps1 in list((ROOT / "infra").glob("*.ps1")) + list((ROOT / "tools").glob("*.ps1")):
            for n, line in enumerate(ps1.read_text(encoding="utf-8").splitlines(), 1):
                if line.lstrip().startswith("#"):
                    continue
                for q in re.findall(r"--query\s+(\'[^\']*\'|\"[^\"]*\"|\S+)", line):
                    self.assertNotRegex(q, r"[()]", f"{ps1.name}:{n} uses a JMESPath function in --query: {q}")


class Bootstrap(unittest.TestCase):
    def test_trusts_both_github_subject_forms(self):
        b = (ROOT / "infra" / "bootstrap-azure.ps1").read_text(encoding="utf-8")
        self.assertIn('subject = "repo:${Repo}:environment:$Environment"', b)
        self.assertIn('"repo:$($repoInfo.owner.login)@$($repoInfo.owner.id)/$($repoInfo.name)@$($repoInfo.id):environment:$Environment"', b)
        self.assertNotIn(":pull_request", b.split("$creds = @(")[1].split(")")[0], "no pull-request credential")


class Workflows(unittest.TestCase):
    def text(self, name):
        return (WORKFLOWS / name).read_text(encoding="utf-8")

    def test_every_action_is_pinned_by_commit(self):
        for wf in WORKFLOWS.glob("*.yml"):
            for line in self.text(wf.name).splitlines():
                m = re.match(r"\s*(?:-\s*)?uses:\s*(\S+)", line)
                if not m or m.group(1).startswith("./"):
                    continue
                self.assertRegex(m.group(1), r"^[\w.-]+/[\w./-]+@[0-9a-f]{40}$", f"{wf.name}: {m.group(1)} not pinned")
                self.assertRegex(line, r"#\s*v\d+\.\d+\.\d+", f"{wf.name}: pin without its version comment")

    def test_deploy_signs_in_with_oidc_from_the_integration_branch(self):
        d = self.text("deploy.yml")
        self.assertIn("  id-token: write", d)
        self.assertIn("azure/login@", d)
        self.assertNotRegex(d, r"client-secret|creds:", "OIDC only, no client secret")
        for sname in ("AZURE_CLIENT_ID", "AZURE_TENANT_ID", "AZURE_SUBSCRIPTION_ID"):
            self.assertIn(f"secrets.{sname}", d)
        self.assertIn("|| 'azure-dev' }}", d)
        self.assertIn(f"INTEGRATION_BRANCH: {INTEGRATION_BRANCH}", d)
        # The exact guard (an inverted comparison would let every other branch deploy).
        self.assertIn('elif [ "${REF}" != "refs/heads/${INTEGRATION_BRANCH}" ]; then\n'
                      '            echo "::error::Deploys run only from', d)
        # prod: only a server-v* tag (promote-prod.yml); any other ref fails.
        self.assertIn('if [ "${ENV_NAME}" = prod ]; then\n', d)
        self.assertIn('            case "${REF}" in\n              refs/tags/server-v[0-9]*) echo', d)
        self.assertIn("title=Azure is not set up for this repository", d, "clear failure when secrets are absent")
        self.assertNotRegex(d, r"(?m)^\s*(push|pull_request|pull_request_target):", "deploy is called or dispatched only")
        # A GitHub concurrency group would cancel pending CI runs; deploy.sh serialises instead.
        self.assertIn("    concurrency:\n      group: deploy-${{ inputs.environment || 'dev' }}${{ (github.event_name == 'workflow_dispatch' && inputs.rollback_sha != '') && '-rollback' || '' }}\n      cancel-in-progress: false", d,
                      "a deploy is never cancelled; a rollback has its own group, so a push never cancels a pending rollback")
        # CI audit s5 item 5: the group is on the job, after its `if`, so a skipped run (red ci) never takes the slot of
        # a waiting green deploy. A workflow-level group would be entered by every run, skipped or not.
        self.assertNotRegex(d, r"(?m)^concurrency:", "no workflow-level concurrency in deploy.yml")
        job = d[d.index("\n  deploy:"):]
        self.assertLess(job.index("    if: github.event_name != 'workflow_run'"), job.index("    concurrency:"))
        self.assertIn("RUN_MIGRATIONS: ${{ (github.event_name == 'workflow_dispatch' && inputs.run_migrations == false) && 'false' || 'true' }}", d)
        call = d[d.index("workflow_call:"):d.index("workflow_dispatch:")]
        self.assertIn("run_migrations:", call, "a called deploy (promote-prod) must see run_migrations = true, not null")
        self.assertIn('[ "${GITHUB_WORKFLOW}" = promote-prod ]', d, "prod only through promote-prod")
        self.assertIn('[ "${FINAL}" = "true" ]', d, "prod only in the final account")
        conditions = re.findall(r"(?m)^\s*if:\s*(.*)$", d)
        self.assertEqual(conditions, ["github.event_name != 'workflow_run' || (github.event.workflow_run.conclusion == 'success' && github.event.workflow_run.event == 'push')",
                                      "failure() && steps.login.outcome == 'failure'"],
                         "only the sign-in explanation may be conditional; no deploy step may be switched off")
        steps = ["Deploy only from the integration branch", "Check the Azure secrets", "azure/login@",
                 "scope-check.sh", "infra/deploy.sh"]
        body = d[d.index("\njobs:"):]
        pos = [body.index(x) for x in steps]
        self.assertEqual(pos, sorted(pos), "deploy workflow steps out of order")

    def test_deploy_script_order(self):
        d = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        d = d[d.index("# ---") :]  # the executable part, after the header comment
        order = ["merge-base --is-ancestor", "--template-file infra/main.bicep", "seed-secrets.sh", "backend.Dockerfile",
                 "ARON_DEPLOY_SERVICES=false", "containerapp job start", "ARON_DEPLOY_SERVICES=true",
                 "approve-private-link.sh", "smoke.sh", "die \"budget $BUDGET not found"]
        positions = [d.index(step) for step in order]
        self.assertEqual(positions, sorted(positions), "deploy steps out of order")
        self.assertIn('die "migrations $execution ended ${status}; the apps were NOT updated', d)
        self.assertIn("az containerapp job stop", d, "a timed-out migration is stopped before the lock is released")
        block = d[d.index('if [ "$RUN_MIGRATIONS" = true ]; then'):d.index("# ---", d.index('if [ "$RUN_MIGRATIONS" = true ]; then'))]
        self.assertIn('execution="$(az containerapp job start', block)
        # The only retry: a first execution that could not open a database connection, once.
        self.assertIn("for attempt in 1 2; do", block)
        self.assertIn('[ "$attempt" -eq 1 ] && [ "$status" = Failed ]', block)
        self.assertIn("FlywaySqlUnableToConnectToDbException|Connection is not available, request timed out", block)

    def test_deploy_holds_one_lock_for_the_whole_deploy(self):
        d = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        body = d[d.index("# ---"):]
        self.assertLess(body.index('note "deploy lock held"'), body.index("merge-base --is-ancestor"),
                        "the ordering guard runs while the lock is held")
        self.assertIn("trap 'rm -f \"$whatif_file\" \"$migrate_log\"; release_lock' EXIT", d)
        self.assertIn('if got="$(read_lock)" && [ "$(lock_owner "$got")" = "$lock_me" ]; then break; fi', d, "write, settle, re-read")
        self.assertIn('if ! cur="$(read_lock)"; then', d, "a failed read counts as held")
        self.assertIn("|| return 1", d[d.index("read_lock() {"):d.index("lock_owner()")])
        self.assertIn("[[ \"$ts\" =~ ^[0-9]{9,11}$ ]] || { echo 999999; return; }", d, "a malformed value is stale, not fatal")
        self.assertIn("lock_ttl=900", d)
        self.assertIn("trap 'exit 130' INT TERM", d)
        self.assertIn("while sleep 60; do", d, "heartbeat")
        full = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        self.assertIn('RUN_MIGRATIONS="${RUN_MIGRATIONS:-true}"', full)
        self.assertNotIn("grep -qs", full, "no source sniffing to decide what to deploy")

    def test_deploy_follows_only_green_ci_of_integration_pushes(self):
        c, d = self.text("ci.yml"), self.text("deploy.yml")
        self.assertNotIn("\n  deploy:", c, "the deploy is its own workflow, so a cancelled ci run never stops it")
        trig = d[d.index("  workflow_run:"):d.index("  workflow_call:")]
        self.assertIn("workflows: [ci]", trig)
        self.assertIn(f"branches: [{INTEGRATION_BRANCH}]", trig)
        self.assertIn("github.event.workflow_run.conclusion == 'success' && github.event.workflow_run.event == 'push'", d)
        self.assertIn("ref: ${{ github.event.workflow_run.head_sha || github.sha }}", d, "deploy the commit ci tested")
        self.assertIn("GIT_SHA: ${{ github.event.workflow_run.head_sha || github.sha }}", d)
        s = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        self.assertIn("Skipped: nothing deployable changed", s)

    def test_ci_skips_superseded_pending_runs_but_never_a_deploy(self):
        # Lead decision 2026-10-07: one ci run per ref (the integration branch is linear; the newer head contains every
        # older commit and compares with the last green head). The deploy is never cancelled mid-flight.
        c = self.text("ci.yml")
        self.assertIn("group: ci-${{ github.workflow }}-${{ github.ref }}\n  cancel-in-progress: ${{ github.event_name == 'pull_request' }}", c,
                      "a running push run always finishes; only an older pending run is replaced")
        self.assertIn("status=success&per_page=1", c, "the changes job compares with the last green head")
        d = self.text("deploy.yml")
        self.assertEqual(d.count("cancel-in-progress: false"), 1)
        self.assertNotIn("cancel-in-progress: true", d)
        q = self.text("codeql.yml")
        self.assertNotRegex(q, r"(?m)^  push:", "CodeQL only on pull requests to main and weekly")
        self.assertIn("branches: [main]", q)
        s = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        self.assertIn("deploy lock held", s)
        self.assertIn("merge-base --is-ancestor", s)

    def test_docs_only_pushes_start_no_ci_run(self):
        c = self.text("ci.yml")
        push = c[c.index("  push:"):c.index("  pull_request:")]
        for p in ('"!docs/**"', '"!**/*.md"', '"docs/24-build-spec.md"', '"docs/data-dictionary.md"'):
            self.assertIn(p, push)
        self.assertLess(push.index('"!docs/**"'), push.index('"docs/24-build-spec.md"'), "re-includes come after the excludes")

    def test_repository_gates_run_on_every_push(self):
        c = self.text("ci.yml")
        block = c[c.index("\n  gates:"):c.index("\n  jvm:")]
        self.assertNotIn("\n    if:", block, "the gates job runs on every push and pull request")
        for needle in ("tools/ci/install-tool.sh", "tools/ci/test_gates.py", "gitleaks git --no-banner --redact --exit-code 1",
                       "--gitleaks-ignore-path tools/ci/gitleaksignore", "--config tools/ci/gitleaks.toml", "tools/ci/migrations-check.sh",
                       '"${CHECK_BASE}" squawk', 'git merge-base "${BEFORE}" "${GITHUB_SHA}"',
                       "tools/ci/contract-breaking.sh", "fetch-depth: 0"):
            self.assertIn(needle, block)
        tools = (ROOT / "tools" / "ci" / "install-tool.sh").read_text(encoding="utf-8")
        self.assertEqual(len(re.findall(r'sha="[0-9a-f]{64}"', tools)), 6, "every gate binary is checksum-pinned")
        self.assertIn("sha256sum -c", tools)

    def test_release_apk_and_size_gate(self):
        c = self.text("ci.yml")
        block = c[c.index("\n  android-release:"):c.index("\n  web:")]
        for app in ("sr", "amo", "tso"):
            self.assertIn(f":android:app-{app}:assembleRelease", block)
        self.assertIn("--ks-pass env:KSP", block, "signing passwords by environment only")
        self.assertIn("if: github.event_name == 'push' && github.ref == 'refs/heads/claude/wonderful-thompson-k6ejnf'", block)
        # AUD-DG-03: every CI APK talks to dev and upgrades over the previous run's.
        for flag in ('-Paron.apiBaseUrl="${ARON_API_BASE_URL}"', '-Paron.versionCode="${ARON_VERSION_CODE}"'):
            self.assertIn(flag, block)
            self.assertIn(flag, c[c.index("\n  android:"):c.index("\n  android-release:")])
        self.assertIn("ARON_VERSION_CODE: ${{ github.run_number }}", c)
        # N-064: the version name fits the contract pattern for every run number (set before any build step).
        name_step = 'echo "ARON_VERSION_NAME=0.$((GITHUB_RUN_NUMBER / 1000 + 1)).$((GITHUB_RUN_NUMBER % 1000))" >> "${GITHUB_ENV}"'
        for job in (block, c[c.index("\n  android:"):c.index("\n  android-release:")]):
            self.assertLess(job.index(name_step), job.index("-Paron.versionName="))
        self.assertNotIn("ARON_VERSION_NAME: ", c, "no workflow-level value that the step could fail to override")
        self.assertIn('release-manifest.py signed "${ARON_VERSION_NAME}" "${ARON_VERSION_CODE}" "${GITHUB_SHA}"', block)
        self.assertIn("python3 tools/ci/apk-size-gate.py", block)
        gate = (ROOT / "tools" / "ci" / "apk-size-gate.py").read_text(encoding="utf-8")
        self.assertIn("ABS_DOWNLOAD_MB, ABS_INSTALLED_MB = 30, 70", gate)
        self.assertIn("WARN_PCT, FAIL_PCT = 5, 15", gate)
        base = json.loads((ROOT / "tools" / "ci" / "apk-size-baseline.json").read_text(encoding="utf-8"))
        self.assertIn("arm64-v8a", base["sr-release"])

    def test_images_get_an_sbom(self):
        c = self.text("ci.yml")
        block = c[c.index("\n  images:"):c.index("\n  infra:")]
        self.assertIn('syft="$(tools/ci/install-tool.sh syft', block, "syft comes checksum-pinned, not from an install script")
        self.assertIn("for img in aron-backend aron-web; do", block)
        self.assertIn('-o "spdx-json=sbom-${img}.spdx.json"', block)
        self.assertNotIn("sbom-action", block)

    def test_contract_slices_and_web_types_cannot_go_stale(self):
        c = self.text("ci.yml")
        self.assertIn("python3 tools/slice-contract.py --check", c[c.index("\n  gates:"):c.index("\n  jvm:")])
        self.assertNotIn("\n  contract:", c, "contract lint is folded into the gates job (CI audit s5 item 8)")
        gov = (ROOT / "tools" / "github-governance.ps1").read_text(encoding="utf-8")
        for name in re.findall(r"(?m)^    name: (.+)$", c):
            if name.strip() != "Detect changed areas":
                self.assertIn(f"'{name.strip()}'", gov, "every ci job is a required check on main")
        self.assertNotIn("'Contract lint'", gov, "no required check without a job")
        self.assertIn("tools/ci/last-green-int.sh", c, "every ci run shows the last green INT run and its age")
        self.assertIn("bash scripts/ci.sh generate", c, "web/src/contract/openapi.d.ts regenerate-and-diff")

    def test_data_dictionary_stays_a_required_check(self):
        c = self.text("ci.yml")
        jvm = c[c.index("\n  jvm:"):c.index("\n  android:")]
        self.assertIn(":db:build", jvm, "DataDictionaryTest runs inside :db:build")
        self.assertIn("tools/data-dictionary/render.sh", jvm)
        self.assertIn("name: data-dictionary-${{ github.sha }}", jvm)
        self.assertIn("docs/data-dictionary\\.md", c, "a dictionary-only change re-runs the JVM job")

    def test_codeql_covers_kotlin_and_typescript(self):
        q = self.text("codeql.yml")
        self.assertIn("language: java-kotlin", q)
        self.assertIn("language: javascript-typescript", q)
        self.assertIn("security-events: write", q)
        self.assertIn("--no-build-cache", q, "CodeQL must see a real Kotlin compile")
        for app in ("app-sr", "app-amo", "app-tso"):
            self.assertIn(f":android:{app}:compileDebugKotlin", q)

    def test_apks_are_uploaded_on_every_successful_android_run(self):
        c = self.text("ci.yml")
        i = c.index("name: Upload debug APKs")
        block = c[i:c.index("- name:", i + 10)]
        self.assertNotIn("if:", block)
        for app in ("app-sr", "app-amo", "app-tso"):
            self.assertIn(f"android/{app}/build/outputs/apk/debug/*.apk", block)
        self.assertIn("if-no-files-found: error", block)


class DeploySafety(unittest.TestCase):
    """AUD-DG-07, AUD-DG-06, AUD-REL-06, AUD-DG-04: ordering re-checked, deploy by digest, health gate, rollback."""

    def deploy(self):
        d = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        return d[d.index("# ---"):]

    def test_ordering_guard_runs_again_before_migrations_and_apps(self):
        d = self.deploy()
        order = ['guard_newer_live "start"', "publish aron-backend build_backend", 'guard_newer_live "before the migrations"',
                 "containerapp job start", 'guard_newer_live "before the apps"', "ARON_DEPLOY_SERVICES=true"]
        pos = [d.index(x) for x in order]
        self.assertEqual(pos, sorted(pos), "the ordering guard must re-read the live commit right before each stage")
        g = d[d.index("guard_newer_live() {"):d.index("deployed_sha=\"$(live_sha)\"")]
        self.assertIn('git merge-base --is-ancestor "$SHA" "$live"', g)
        self.assertIn("exit 0", g, "a newer live commit skips, it does not fail")
        self.assertIn("env[?name=='ARON_BUILD'].value", d, "the live commit is read from ARON_BUILD (digest deploys)")

    def test_images_are_deployed_by_digest_and_tags_locked(self):
        d = self.deploy()
        self.assertIn('IMAGE_REF="${REGISTRY}/${repo}@${d}"', d)
        self.assertIn('[[ "$d" =~ ^sha256:[0-9a-f]{64}$ ]] || die', d)
        self.assertIn("--write-enabled false --delete-enabled true", d)
        self.assertIn('ARON_BACKEND_IMAGE="$BACKEND_IMAGE" ARON_WEB_IMAGE="$WEB_IMAGE" ARON_BUILD_ID="$SHA"', d)
        self.assertNotRegex(d, r'BACKEND_IMAGE="\$\{REGISTRY\}/aron-backend:', "never deploy by tag")
        for f in ("dev", "dev-lite", "stage", "prod"):
            src = (ROOT / "infra" / "params" / f"{f}.apps.bicepparam").read_text(encoding="utf-8")
            self.assertIn("param buildId = readEnvironmentVariable('ARON_BUILD_ID', '')", src, f)
        text = json.dumps(load("apps.json"))
        self.assertIn("parameters('buildId')", text, "ARON_BUILD must come from the commit, not the digest")

    def test_revision_mode_multiple_only_in_the_final_profiles(self):
        res = load("apps.json")["resources"]
        modes = {k: res[k]["properties"]["configuration"]["activeRevisionsMode"] for k in ("api", "worker", "web")}
        self.assertEqual(modes, {"api": "[parameters('apiRevisionsMode')]", "worker": "Single", "web": "Single"})
        self.assertEqual(param_default("apps.json", "apiRevisionsMode"), "Single")
        for f in ("dev", "dev-lite"):
            self.assertNotIn("apiRevisionsMode", params(f"{f}.apps.parameters.json"), f"{f} must stay Single (docs/28)")
        for f in ("stage", "prod"):
            self.assertEqual(params(f"{f}.apps.parameters.json")["apiRevisionsMode"], "Multiple", f)
        d = self.deploy()
        self.assertIn('--revision-weight "${prev_revision}=100"', d, "a failed gate puts traffic back in Multiple mode")
        self.assertIn('if ! infra/scripts/smoke.sh "$API_HOST" "$SHA" "$WEB_HOST"; then', d)
        f = d[d.index('prev_revision=""'):d.index("ARON_DEPLOY_SERVICES=true")]
        self.assertIn('[ "$build" != "$SHA" ]', f, "the fallback runs another build (a re-run's own revision is never it)")
        self.assertIn("properties.trafficWeight > ", f, "the fallback serves traffic now")
        self.assertIn('then web_digest="$(digest_of aron-web "$SHA")"; fi', d, "a registry error stops a rollback")

    def test_rollback_redeploys_an_existing_image_without_migrations(self):
        full = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        rb = full[full.index('if [ -n "$ROLLBACK_SHA" ]; then'):full.index("# Dhaka selling window")]
        self.assertIn('git merge-base --is-ancestor "$ROLLBACK_SHA" "$SHA"', rb, "only an earlier commit of the branch")
        self.assertIn("RUN_MIGRATIONS=false", rb)
        d = self.deploy()
        self.assertIn('[ -z "$ROLLBACK_SHA" ] || die "rollback: $repo:$SHA is no longer in the registry', d, "a rollback never builds")
        self.assertIn('[ -n "$ROLLBACK_SHA" ] && return 0', d, "a rollback bypasses the ordering guard")
        i = d.index('if [ -n "$ROLLBACK_SHA" ]; then\n  # A rollback changes images only')
        self.assertIn("skip_infra=true", d[i:i + 300])
        w = (WORKFLOWS / "deploy.yml").read_text(encoding="utf-8")
        self.assertIn("ROLLBACK_SHA: ${{ github.event_name == 'workflow_dispatch' && inputs.rollback_sha || '' }}", w)

    def test_pitr_point_and_freeze_window(self):
        d = self.deploy()
        self.assertLess(d.index("PITR restore point (before the migrations)"), d.index("containerapp job start"))
        full = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        self.assertIn('if [ -z "${ARON_DEPLOY_FREEZE_DHAKA:-}" ] || [ -n "$ROLLBACK_SHA" ]; then return 0; fi', full)
        self.assertIn('check_freeze "at the start"', full)

    def run_smoke(self, build="b" * 40, ready=200, login=200, want="b" * 40, web="web.example", timeout="0"):
        import subprocess, tempfile
        builds = build if isinstance(build, list) else [build]
        with tempfile.TemporaryDirectory() as t:
            counter = Path(t) / "n"
            stub = Path(t) / "curl"
            stub.write_text(f"""#!/usr/bin/env python3
import sys
a = sys.argv[1:]
url = a[-1]
out = a[a.index('-o') + 1] if '-o' in a else None
hdr = a[a.index('-D') + 1] if '-D' in a else None
if url.endswith('/v1/health'):
    import os
    c = {str(counter)!r}
    n = int(open(c).read()) if os.path.exists(c) else 0
    if hdr: open(c, 'w').write(str(n + 1))
    builds = {builds!r}
    code, body = 200, '{{"status":"ok","build":"%s"}}' % builds[min(n, len(builds) - 1)]
    if hdr: open(hdr, 'w').write('HTTP/2 200\\r\\nx-aron-api: 1\\r\\n')
elif url.endswith('/v1/health/ready'):
    code, body = {ready}, 'database: down' if {ready} != 200 else 'ok'
elif url.endswith('/login'):
    code, body = {login}, '<html>login</html>'
else:
    code, body = 404, ''
if out and out != '/dev/null': open(out, 'w').write(body)
sys.stdout.write(str(code))
""")
            stub.chmod(0o755)
            (Path(t) / "sleep").write_text("#!/bin/sh\nexit 0\n")
            (Path(t) / "sleep").chmod(0o755)
            env = {**os.environ, "PATH": f"{t}:{os.environ['PATH']}", "SMOKE_TIMEOUT_S": timeout,
                   "SMOKE_WEB_TIMEOUT_S": "0"}
            r = subprocess.run(["bash", str(ROOT / "infra" / "scripts" / "smoke.sh"), "api.example", want, web],
                               env=env, capture_output=True, text=True, timeout=60)
            return r.returncode, r.stdout + r.stderr

    def test_health_gate_behaviour(self):
        self.assertEqual(self.run_smoke()[0], 0)
        rc, out = self.run_smoke(build="a" * 40)
        self.assertNotEqual(rc, 0, "an old build answering must fail the gate")
        self.assertIn("expected", out)
        rc, out = self.run_smoke(ready=503)
        self.assertNotEqual(rc, 0, "readiness 503 must fail the gate")
        self.assertIn("database: down", out, "the readiness response is shown")
        self.assertNotEqual(self.run_smoke(login=502)[0], 0, "a broken web login page must fail the gate")
        self.assertEqual(self.run_smoke(login=502, web="")[0], 0, "no web host, no web check")
        rc, out = self.run_smoke(build=["a" * 40, "a" * 40, "b" * 40], timeout="600")
        self.assertEqual(rc, 0, "the gate waits while the old revision still answers, then passes on the new build")
        self.assertIn("build " + "a" * 40, out)

    def run_purge(self, manifests, in_use, keep="10", dry=False):
        import subprocess, tempfile
        with tempfile.TemporaryDirectory() as t:
            log = Path(t) / "deleted"
            rows = "\n".join("\t".join(m) for m in manifests)
            stub = Path(t) / "az"
            stub.write_text(f"""#!/usr/bin/env python3
import sys
a = ' '.join(sys.argv[1:])
if a.startswith('acr list'): print('craronx')
elif a.startswith('acr show'): print('craronx.azurecr.io')
elif a.startswith('containerapp list'): print({in_use!r})
elif a.startswith('containerapp job list'): print('')
elif a.startswith('acr repository show'):
    sys.exit(0 if 'aron-backend' in a else 3)
elif a.startswith('acr manifest list-metadata'): print({rows!r})
elif a.startswith('acr repository delete'):
    open({str(log)!r}, 'a').write(sys.argv[sys.argv.index('--image') + 1] + '\\n')
else: sys.exit('unexpected az ' + a)
""")
            stub.chmod(0o755)
            env = {**os.environ, "PATH": f"{t}:{os.environ['PATH']}", "DRY_RUN": "true" if dry else "false"}
            r = subprocess.run(["bash", str(ROOT / "infra" / "scripts" / "acr-purge.sh"), "rg", keep],
                               env=env, capture_output=True, text=True, timeout=60)
            deleted = log.read_text().split() if log.exists() else []
            return r.returncode, deleted, r.stdout + r.stderr

    def test_registry_purge_keeps_newest_young_and_in_use(self):
        old = "2020-01-01T00:00:00Z"
        ms = [(f"sha256:{i:064x}", f"c{i}", old) for i in range(14)]          # newest first
        ms[13] = (ms[13][0], ms[13][1], "2999-01-01T00:00:00Z")  # young: kept although old by rank
        ms.insert(3, ("sha256:" + "f" * 64, "", old))  # untagged child of an index: never counted, never deleted
        in_use = "craronx.azurecr.io/aron-backend@sha256:%064x" % 12 + "\n" + "craronx.azurecr.io/aron-backend:c11"
        rc, deleted, out = self.run_purge(ms, in_use)
        self.assertEqual(rc, 0, out)
        self.assertEqual(deleted, ["aron-backend@sha256:%064x" % 10],
                         "keeps the 10 newest, the young one and the in-use images (by digest and by tag)")
        rc, deleted, out = self.run_purge(ms, in_use, dry=True)
        self.assertEqual((rc, deleted), (0, []), "a dry run deletes nothing")
        self.assertNotEqual(self.run_purge(ms, in_use, keep="3")[0], 0, "keep below 10 is refused")

    def test_freeze_window(self):
        import subprocess
        def inside(window, minute):
            r = subprocess.run(["bash", "-c", f'source infra/scripts/lib.sh; in_freeze_window "{window}" {minute}'],
                               cwd=ROOT, capture_output=True, text=True)
            return "error" if "::error::" in r.stderr else {0: True, 1: False}.get(r.returncode, "error")
        self.assertEqual([inside("07:00-19:00", m) for m in (419, 420, 1139, 1140)], [False, True, True, False])
        self.assertEqual([inside("22:00-06:00", m) for m in (1319, 1320, 0, 359, 360)], [False, True, True, True, False],
                         "a window past midnight wraps")
        for bad in ("24:00-06:00", "7:00-19:00", "07:00-29:00"):
            self.assertEqual(inside(bad, 0), "error", f"{bad} must be refused, never ignored")
        d = self.deploy()
        for stage in ('check_freeze "before the migrations"', 'check_freeze "before the apps"'):
            self.assertIn(stage, d)
        self.assertLess(d.index('check_freeze "before the apps"'), d.index("ARON_DEPLOY_SERVICES=true"))

    def test_rerun_of_the_live_commit_deploys_again(self):
        d = self.deploy()
        i = d.index('summary "Skipped: nothing deployable changed')
        self.assertIn('[ "$deployed_sha" != "$SHA" ]', d[i - 600:i], "a re-run after a failed gate must run the gate again")
        self.assertIn("--provenance=false --sbom=false", d, "no attestation manifests the purge could orphan")
        self.assertIn('ARON_MIGRATE_IMAGE="$(az containerapp job show', d, "a rollback keeps the migrate job on the newest image")
        self.assertIn("image: '[if(empty(parameters('migrateImage')), parameters('backendImage'), parameters('migrateImage'))]'".replace("image: '", "").rstrip("'"), json.dumps(load("apps.json")))

    def test_purge_workflow(self):
        w = (WORKFLOWS / "acr-purge.yml").read_text(encoding="utf-8")
        self.assertIn("DRY_RUN: ${{ (github.event_name == 'workflow_dispatch' && inputs.dry_run != false) && 'true' || 'false' }}", w)
        self.assertIn("environment: azure-dev", w)
        self.assertNotRegex(w, r"(?m)^\s*(push|pull_request|pull_request_target):")


class SupplyChainGates(unittest.TestCase):
    """AUD-SEC-05 and the lead's scanning ask: OSV, Semgrep, npm audit, Trivy, dependency review, ignore-scripts."""

    def test_wired_into_ci(self):
        c = (WORKFLOWS / "ci.yml").read_text(encoding="utf-8")
        gates = c[c.index("\n  gates:"):c.index("\n  jvm:")]
        for needle in ("for t in gitleaks oasdiff squawk osv-scanner; do", "tools/ci/osv-gate.py", "tools/ci/osv-allow.txt",
                       'tools/ci/semgrep.sh "${base}"', 'base="$(git merge-base "origin/${INTEGRATION_BRANCH}" "${GITHUB_SHA}"', "tools/ci/install-scripts-check.py web/package-lock.json", '[ "${GITHUB_EVENT_NAME}" = push ] && [ "${GITHUB_REF}" != "refs/heads/main" ]',
                       "actions/dependency-review-action@", "fail-on-severity: high"):
            self.assertIn(needle, gates)
        self.assertIn("if: github.event_name == 'pull_request'", gates[gates.index("Dependency review"):])
        web = c[c.index("\n  web:"):c.index("\n  images:")]
        self.assertIn("npm audit --omit=dev --audit-level=high", web)
        images = c[c.index("\n  images:"):c.index("\n  infra:")]
        self.assertIn("--severity CRITICAL --ignore-unfixed --exit-code 1", images)
        self.assertLess(images.index("image-smoke.sh"), images.index("trivy"))

    def test_pins(self):
        sg = (ROOT / "tools" / "ci" / "semgrep.sh").read_text(encoding="utf-8")
        self.assertRegex(sg, r'IMAGE="semgrep/semgrep:[\d.]+@sha256:[0-9a-f]{64}"', "semgrep image pinned by digest")
        self.assertIn('[ -n "$base" ] && args+=(--baseline-commit "$base" --error)', sg)
        tools = (ROOT / "tools" / "ci" / "install-tool.sh").read_text(encoding="utf-8")
        for t in ("osv-scanner)", "trivy)"):
            self.assertIn(t, tools)

    def test_web_installs_without_scripts(self):
        self.assertIn("ignore-scripts=true", (ROOT / "web" / ".npmrc").read_text(encoding="utf-8"))
        df = (ROOT / "infra" / "docker" / "web.Dockerfile").read_text(encoding="utf-8")
        self.assertIn("COPY package.json package-lock.json .npmrc ./", df, "the image build must use web/.npmrc")


class PerAppDatabaseLogins(unittest.TestCase):
    """docs/requests/db-runtime-roles.md: api and worker on least-privilege logins; migrate keeps the admin login."""

    def test_apps_use_their_own_logins(self):
        res = load("apps.json")["resources"]
        def secrets(app):  # compiled as __bicep.kvSecret('<name>', <secret expression>, ...) calls
            return {re.match(r"\[__bicep\.kvSecret\('([\w-]+)'", x).group(1): x
                    for x in res[app]["properties"]["configuration"]["secrets"]}
        api_secrets, worker_secrets = secrets("api"), secrets("worker")
        text = json.dumps(load("apps.json"))
        for v in ("aron-db-api-url", "aron-db-api-read-url", "aron-db-jobs-direct-url", "aron-db-jobs-read-url"):
            self.assertIn(v, text)
        self.assertIn("apiDbUrlSecret", api_secrets["db-url"])
        self.assertIn("workerDbUrlSecret", worker_secrets["db-direct-url"])
        mig = secrets("migrate")
        self.assertIn("dbDirectUrl", mig["db-direct-url"], "the migrate job keeps the admin login")
        # On in dev since the grants gap closed (V0029); off elsewhere until dev has proven it.
        self.assertIs(params("dev.apps.parameters.json")["dbPerAppLogins"], True)
        for f in ("dev-lite", "stage", "prod"):
            self.assertIs(params(f"{f}.apps.parameters.json")["dbPerAppLogins"], False, f)
        self.assertIs(param_default("apps.json", "dbPerAppLogins"), False, "off unless a profile turns it on")

    def test_dblogins_job_runs_the_checked_in_sql(self):
        job = load("apps.json")["resources"]["dblogins"]
        self.assertEqual(job["condition"], "[not(empty(parameters('psqlImage'))))]".replace("))))", ")))"))
        c = job["properties"]["template"]["containers"][0]
        env = {e["name"]: e for e in c["env"]}
        sql = (ROOT / "infra" / "sql" / "runtime-logins.sql").read_text(encoding="utf-8")
        compiled = load("apps.json")
        # The SQL is a mounted file, never an env value: as a 7 KB env value the replica was never created
        # (deploy run 37659152959: probes A and B succeeded, the real job did not).
        self.assertNotIn("ARON_SQL", env)
        # The secrets compile to one concat(...) expression (the dev seed secret is conditional), so the SQL secret is
        # checked in the Bicep source (exactly the checked-in file) and its presence in the compiled template.
        src = (ROOT / "infra" / "apps.bicep").read_text(encoding="utf-8")
        self.assertIn("{ name: 'logins-sql', value: loadTextContent('sql/runtime-logins.sql') }", src,
                      "the job runs exactly infra/sql/runtime-logins.sql")
        secrets = job["properties"]["configuration"]["secrets"]
        m = re.search(r"'name', 'logins-sql', 'value', variables\('([^']+)'\)", secrets)
        self.assertTrue(m, secrets[:300])
        self.assertEqual(compiled["variables"][m.group(1)].strip(), sql.strip(), "the compiled job embeds exactly that file")
        (vol,) = job["properties"]["template"]["volumes"]
        self.assertEqual(vol["storageType"], "Secret")
        self.assertEqual(vol["secrets"], [{"secretRef": "logins-sql", "path": "runtime-logins.sql"}],
                         "only the SQL is projected; an empty list would mount every secret, the database URL included")
        self.assertEqual(c["volumeMounts"], [{"volumeName": vol["name"], "mountPath": "/sql"}])
        cmd = " ".join(c["command"])
        self.assertIn('exec psql "${ARON_DB_URL#jdbc:}" -X -q -f /sql/runtime-logins.sql', cmd, "shell expansion, not a Bicep one")
        smoke = (ROOT / "infra" / "scripts" / "image-smoke.sh").read_text(encoding="utf-8")
        self.assertIn("-f /sql/runtime-logins.sql", smoke, "CI runs the SQL from the same path")
        for k in ("ARON_PW_APP_API", "ARON_PW_APP_WORKER", "ARON_PW_APP_JOBS", "ARON_DB_URL"):
            self.assertIn("secretRef", env[k], f"{k} must come from Key Vault")
        for needle in ("GRANT %I TO %I WITH INHERIT %s, SET TRUE", "REVOKE %I FROM %I", "\\getenv pw_api ARON_PW_APP_API",
                       "('app_api', 'pii_reader', false)", "SELECT app.apply_login_limits()", "\\if :{?pw_jobs}",
                       "EXCEPTION WHEN others THEN", "\\gset"):
            self.assertIn(needle, sql)
        role_stmts = [l for l in sql.splitlines() if "ROLE %I LOGIN" in l]
        self.assertEqual(len(role_stmts), 2, "one CREATE and one ALTER")
        for l in role_stmts:
            self.assertRegex(l, r"LOGIN INHERIT NOCREATEDB NOCREATEROLE PASSWORD %L")

    def test_deploy_order(self):
        d = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        d = d[d.index("# ---"):]
        order = ["seed-secrets.sh", "db-login-secrets.sh", "az acr import", "ARON_DEPLOY_SERVICES=false",
                 'az containerapp job start -g "$RG" -n "$JOB"', 'az containerapp job start -g "$RG" -n "$DBLOGINS_JOB"',
                 "ARON_DEPLOY_SERVICES=true"]
        pos = [d.index(x) for x in order]
        self.assertEqual(pos, sorted(pos), "logins exist (after the migrations) before the apps switch to them")
        self.assertIn('die "database logins $execution ended', d)
        s = (ROOT / "infra" / "scripts" / "db-login-secrets.sh").read_text(encoding="utf-8")
        self.assertIn("die \"cannot read $1 from $kv (not generating a new one)", s, "a failed read never rotates a password")
        smoke = (ROOT / "infra" / "scripts" / "image-smoke.sh").read_text(encoding="utf-8")
        self.assertIn('"ARON_DB_URL=$PG_API"', smoke, "CI runs the api as app_api")
        self.assertIn('"ARON_DB_URL=$PG_JOBS"', smoke, "CI runs the worker as app_jobs")


class PlatformAlerts(unittest.TestCase):
    """AUD-REL-04: application-metric-free alerts, each naming an owner and a runbook."""

    def test_database_and_resource_health(self):
        t, _ = module("main.json", "alerts")
        text = json.dumps(t)
        for needle in ("is_db_alive", "pg-not-alive", "ResourceHealth", "Unavailable", "Degraded", "ServiceHealth"):
            self.assertIn(needle, text)
        (rh, rec, sh) = sorted(resources_of(t, "Microsoft.Insights/activityLogAlerts"), key=lambda r: r["name"])
        for a in (rh, rec):
            self.assertEqual(a["properties"]["scopes"], ["[resourceGroup().id]"])
            self.assertIn("Owner:", a["properties"]["description"])
        self.assertIn("recovered", rec["name"])
        self.assertEqual(sh.get("condition"), "[parameters('enableServiceHealthAlert')]", "off until subscription Reader exists")

    def test_app_alerts(self):
        apps = load("apps.json")
        text = json.dumps(apps["variables"]) + json.dumps(apps["resources"]["appMetricAlerts"])
        for needle in ("api-5xx", "statusCodeCategory", "5xx", "-restarts", "-no-replica", "RestartCount", "Replicas",
                       "Microsoft.App/containerApps"):
            self.assertIn(needle, text)
        self.assertEqual(apps["resources"]["appMetricAlerts"]["condition"], "[parameters('deployServices')]")
        src = (ROOT / "infra" / "apps.bicep").read_text(encoding="utf-8")
        block = src[src.index("var appAlerts"):src.index("resource appMetricAlerts")]
        self.assertEqual(block.count("description:"), block.count("Owner: infra lane"), "every alert names its owner")
        self.assertEqual(block.count("description:"), block.count("Runbook: RB-"), "every alert names its runbook")


class Drills(unittest.TestCase):
    """AUD-REL-05: the s5a drills are a workflow behind explicit approval phrases; the restore copy never outlives the run."""

    def test_drill_workflow_and_script(self):
        w = (WORKFLOWS / "drill.yml").read_text(encoding="utf-8")
        self.assertNotRegex(w, r"(?m)^\s*(push|pull_request|schedule|workflow_call):", "manual dispatch only")
        self.assertIn('failover) want="lead approved failover drill" ;;', w)
        self.assertIn('pitr) want="owner approved restore drill" ;;', w)
        self.assertLess(w.index("Approval phrase"), w.index("azure/login@"), "nothing touches Azure before the phrase")
        d = (ROOT / "infra" / "scripts" / "drill.sh").read_text(encoding="utf-8")
        self.assertIn("trap cleanup EXIT", d, "the restored server is deleted on every exit")
        self.assertLess(d.index("trap cleanup EXIT"), d.index("az postgres flexible-server restore"))
        self.assertIn("--failover Forced", d)
        self.assertIn('die "a deploy holds the lock', d, "never during a deploy")
        self.assertNotIn("starts_with(name", d, "the profile server by exact name, never a drill restore or replica")
        self.assertIn("-n aron-infra --query properties.outputs.postgresServerName.value", d)

    def run_failover(self, codes, call_s=6, max_s="30"):
        """Stub az (the failover call takes call_s seconds) and curl (answers the codes in order, then the last one)."""
        import os, subprocess, tempfile
        with tempfile.TemporaryDirectory() as t:
            (Path(t) / "az").write_text(f"""#!/usr/bin/env python3
import sys, time
a = ' '.join(sys.argv[1:])
if 'postgresServerName' in a: print('psql-aron-dev-x')
elif 'deployment group show' in a: print('api.example')
elif 'group show' in a: print('')
elif 'flexible-server list' in a: print('psql-aron-dev-x')
elif 'highAvailability.mode' in a: print('ZoneRedundant')
elif 'availabilityZone' in a: print('2' if 'done' in open('{t}/state').read() else '1')
elif '--failover Forced' in a:
    time.sleep({call_s}); open('{t}/state', 'w').write('done')
""")
            (Path(t) / "state").write_text("")
            (Path(t) / "curl").write_text(f"""#!/usr/bin/env python3
import os, sys
c = '{t}/n'
n = int(open(c).read()) if os.path.exists(c) else 0
open(c, 'w').write(str(n + 1))
codes = {codes!r}
sys.stdout.write(str(codes[min(n, len(codes) - 1)]))
""")
            for f in ("az", "curl"):
                (Path(t) / f).chmod(0o755)
            env = {**os.environ, "PATH": f"{t}:{os.environ['PATH']}", "DRILL_PROBE_S": "0.5", "DRILL_MAX_S": max_s}
            env.pop("GITHUB_STEP_SUMMARY", None)
            r = subprocess.run(["bash", str(ROOT / "infra" / "scripts" / "drill.sh"), "rg-x", "failover"],
                               env=env, capture_output=True, text=True, timeout=120)
            return r.returncode, r.stdout + r.stderr

    def test_failover_outage_is_measured_from_before_the_call(self):
        import re
        rc, out = self.run_failover([200, 503, "000", "000", 200])
        self.assertEqual(rc, 0, out)
        self.assertIn("primary zone: 1 -> 2", out)
        self.assertIn("via api.example", out)
        self.assertIn("psql-aron-dev-x", out, "the server named by the aron-infra output")
        self.assertIn("this is NOT the outage", out)
        m = re.search(r"user-visible outage: about (\d+) s", out)
        self.assertTrue(m, out)
        call = int(re.search(r"call returned after (\d+) s", out).group(1))
        self.assertGreaterEqual(call, 5)
        self.assertLess(int(m.group(1)), call, "the outage is the failed-probe window, not the Azure call")
        rc, out = self.run_failover([200])
        self.assertEqual(rc, 0, out)
        self.assertIn("no failed probe", out)
        rc, out = self.run_failover([200, 503], call_s=1, max_s="4")
        self.assertNotEqual(rc, 0, "never ready again must fail the drill")
        self.assertIn("NOT ready", out)
        rc, out = self.run_failover([200, 503, 200], call_s=3, max_s="1")
        self.assertEqual(rc, 0, "a call longer than the wait limit is not a false failure: " + out)


class WorkerWithoutSigningKey(unittest.TestCase):
    """AUD-SEC-07 (docs/requests/infra-worker-no-signing-key.md): the token signing key reaches api replicas only."""

    def test_signing_key_only_in_the_api(self):
        src = (ROOT / "infra" / "apps.bicep").read_text(encoding="utf-8")
        worker = src[src.index("resource worker "):src.index("resource web ") if "resource web " in src else len(src)]
        api = src[src.index("resource api "):src.index("resource worker ")]
        for needle in ("jwt-signing-key", "jwtSecretRefs", "jwt-kid"):
            self.assertNotIn(needle, worker, needle)
        self.assertIn("kvSecret('jwt-signing-key'", api)
        self.assertIn("concat(commonEnv, jwtSecretRefs, appSecretRefs", api)


class DbLoginsGate(unittest.TestCase):
    """dblogins failed three deploys with no log anywhere while nothing used its logins (dbPerAppLogins off): it blocks
    the apps only when they use those logins, and a failure prints the platform's own execution record and log."""

    def test_blocks_only_when_the_apps_use_the_logins(self):
        d = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        block = d[d.index("# ------------------------------------------------------------------------------------------------- db logins"):d.index("# ------------------------------------------------------------------------------------------------------ apps")]
        self.assertIn("properties.outputs.dbPerAppLogins.value", block)
        self.assertIn('if [ "${per_app,,}" != false ]; then', block, "unknown counts as on: fail closed")
        self.assertIn('die "database logins $execution ended', block)
        self.assertIn("az containerapp job logs show", block)
        self.assertIn("az containerapp job execution show", block)
        self.assertIn('summary "| Database logins |', d)
        self.assertIn("infra/scripts/dblogins-probe.sh", block)
        p = (ROOT / "infra" / "scripts" / "dblogins-probe.sh").read_text(encoding="utf-8")
        self.assertIn("--yaml", p, "per-execution override; the job's own template is unchanged")
        self.assertNotIn('echo "$x"', p, "never prints a secret value")


class InfraStageSkip(unittest.TestCase):
    """2026-10-07: while dblogins failed, the live api commit never advanced, so every INT push re-applied main.bicep
    (and re-PUT Front Door). The skip now diffs against the commit main.bicep was last applied from."""

    def test_skip_base_is_the_last_applied_infra_commit(self):
        d = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        self.assertIn('--tags "aron-infra-sha=${SHA}"', d, "a successful main.bicep apply records its commit")
        self.assertIn('tags."aron-infra-sha"', d)
        self.assertLess(d.index("-n aron-infra --template-file infra/main.bicep"), d.index('--tags "aron-infra-sha='))
        create = d[d.index("az deployment group create -g \"$RG\" -n aron-infra"):]
        create = create[:create.index(")\"")]
        self.assertNotIn("--tags", create, "az deployment group create has no --tags (run 37639072495)")
        self.assertIn('git diff --quiet "$infra_sha" "$SHA"', d)
        self.assertLess(d.index('infra_sha="$(az group show'), d.index('git diff --quiet "$infra_sha"'))
        self.assertIn('infra_sha="$deployed_sha"', d, "falls back to the live commit when untagged")

    def run_params_unchanged(self, now_params, last_params):
        """Runs deploy.sh's params_unchanged with a fake az; returns (exit code, stderr)."""
        import subprocess, tempfile
        d = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        fn = d[d.index("params_unchanged() {"):]
        fn = fn[:fn.index("\n}\n") + 3]
        with tempfile.TemporaryDirectory() as t:
            t = Path(t)
            (t / "now.json").write_text(json.dumps({"parametersJson": json.dumps({"parameters": now_params})}))
            (t / "last.json").write_text(json.dumps(last_params))
            (t / "az").write_text(f"""#!/usr/bin/env bash
case "$*" in *build-params*) cat "{t}/now.json" ;; *"deployment group show"*) cat "{t}/last.json" ;; *) exit 9 ;; esac
""")
            (t / "az").chmod(0o755)
            env = dict(os.environ, PATH=f"{t}:{os.environ['PATH']}", PROFILE="dev", RG="rg-x")
            r = subprocess.run(["bash", "-c", fn + "\nparams_unchanged"], env=env, capture_output=True, text=True,
                               cwd=ROOT, timeout=60)
            return r.returncode, r.stderr

    def test_parameter_comparison_survives_large_parameters(self):
        """Deploy run 144: 'python3: Argument list too long' (attestation roots > 128 KiB in one argv string) made every
        deploy re-apply main.bicep. The comparison reads files now."""
        big = {"attestationRoots": {"value": "x" * 300_000}, "budgetAmount": {"value": 130}}
        rc, err = self.run_params_unchanged(big, big)
        self.assertEqual(rc, 0, err)
        self.assertNotIn("Argument list too long", err)
        rc, err = self.run_params_unchanged(big, {**big, "budgetAmount": {"value": 140}})
        self.assertEqual(rc, 1)
        self.assertIn("infra parameters changed: budgetAmount", err)
        rc, _ = self.run_params_unchanged(big, {**big, "postgresAdminPassword": {"value": "other"}})
        self.assertEqual(rc, 0, "the admin password is never compared")

    def test_recovered_alert_closes_resource_health(self):
        a = (ROOT / "infra" / "modules" / "alerts.bicep").read_text(encoding="utf-8")
        self.assertIn("-resource-health-recovered'", a)
        self.assertIn("'properties.previousHealthStatus', equals: 'Unavailable'", a)


class AgentDownload(unittest.TestCase):
    """CI run 361: BuildKit's single ADD request got other bytes from Maven Central (digest mismatch). Both image builds
    fetch the pinned agent with retries and a checksum, and pass it as the named build context replacing the ADD stage."""

    def test_both_builds_use_the_retrying_fetch(self):
        df = (ROOT / "infra" / "docker" / "backend.Dockerfile").read_text(encoding="utf-8")
        self.assertIn("FROM ${JRE_IMAGE} AS agent", df)
        self.assertRegex(df, r"(?m)^ARG AI_AGENT_VERSION=\S+$")
        self.assertRegex(df, r"(?m)^ARG AI_AGENT_SHA256=[0-9a-f]{64}$")
        f = (ROOT / "infra" / "scripts" / "fetch-ai-agent.sh").read_text(encoding="utf-8")
        self.assertIn("sha256sum -c", f)
        self.assertIn("--retry-all-errors", f)
        for script in ("infra/scripts/image-smoke.sh", "infra/deploy.sh"):
            t = (ROOT / script).read_text(encoding="utf-8")
            self.assertIn("infra/scripts/fetch-ai-agent.sh", t, script)
            self.assertIn('--build-context "agent=', t, script)
            self.assertLess(t.index("fetch-ai-agent.sh"), t.index("-f infra/docker/backend.Dockerfile"), script)


    def test_second_source_when_maven_central_refuses(self):
        """Deploy run 144: Maven Central answered 429 on every attempt. The GitHub release is tried next, same checksum."""
        import hashlib, subprocess, tempfile
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            (d / "infra" / "scripts").mkdir(parents=True)
            (d / "infra" / "docker").mkdir(parents=True)
            (d / "bin").mkdir()
            jar = b"agent bytes"
            (d / "infra" / "docker" / "backend.Dockerfile").write_text(
                f"ARG AI_AGENT_VERSION=3.7.10\nARG AI_AGENT_SHA256={hashlib.sha256(jar).hexdigest()}\n")
            script = d / "infra" / "scripts" / "fetch-ai-agent.sh"
            script.write_text((ROOT / "infra" / "scripts" / "fetch-ai-agent.sh").read_text(encoding="utf-8"))
            (d / "bin" / "curl").write_text("""#!/usr/bin/env python3
import sys
a = sys.argv[1:]
open(sys.argv[0] + '.log', 'a').write(a[-1] + '\\n')
if 'repo1.maven.org' in a[-1]:
    sys.exit(22)
open(a[a.index('-o') + 1], 'wb').write(b'agent bytes')
""")
            (d / "bin" / "curl").chmod(0o755)
            env = dict(os.environ, PATH=f"{d / 'bin'}:{os.environ['PATH']}")
            r = subprocess.run(["bash", str(script), str(d / "out")], env=env, capture_output=True, text=True, timeout=60)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual((d / "out" / "agent" / "applicationinsights-agent.jar").read_bytes(), jar)
            self.assertIn("sha256 ok from github.com (attempt 1)", r.stdout)
            calls = (d / "bin" / "curl.log").read_text().split()
            self.assertEqual(calls, [
                "https://repo1.maven.org/maven2/com/microsoft/azure/applicationinsights-agent/3.7.10/applicationinsights-agent-3.7.10.jar",
                "https://github.com/microsoft/ApplicationInsights-Java/releases/download/3.7.10/applicationinsights-agent-3.7.10.jar"])

class DeviceEnrolment(unittest.TestCase):
    """N-031 (lead #3): the api gets its public base URL and the Android key-attestation roots on every deploy."""

    def test_public_url_and_attestation_roots(self):
        src = (ROOT / "infra" / "apps.bicep").read_text(encoding="utf-8")
        self.assertIn("{ name: 'ARON_PUBLIC_API_URL', value: publicApiUrl }", src)
        self.assertIn("{ name: 'ARON_ATTESTATION_ROOTS', value: join(attestationRootsSha256, ',') }", src)
        roots = json.loads((ROOT / "infra" / "params" / "attestation-roots.json").read_text(encoding="utf-8"))
        self.assertTrue(roots["source"].startswith("https://developer.android.com/"))
        self.assertGreaterEqual(len(roots["sha256"]), 2)
        for h in roots["sha256"]:
            self.assertRegex(h, r"^[0-9a-f]{64}$", "the backend keeps only 64-char lower-case hex")
        for f in ("dev", "dev-lite", "stage", "prod"):
            p = (ROOT / "infra" / "params" / f"{f}.apps.bicepparam").read_text(encoding="utf-8")
            self.assertNotIn("attestationRootsSha256 = []", p, "no profile turns the roots off")


class BrowserUploads(unittest.TestCase):
    """docs/requests/web-admin-asset-upload-csp.md: CORS for the web origin only, PUT only; web knows the blob origin."""

    def test_cors_and_blob_origin(self):
        t, bound = module("main.json", "storage")
        (blobs,) = resources_of(t, "Microsoft.Storage/storageAccounts/blobServices")
        text = json.dumps(blobs["properties"]["cors"])
        for needle in ("PUT", "x-ms-blob-type", "content-type", "uploadOrigins"):
            self.assertIn(needle, text)
        for bad in ("'*'", '"*"', "GET", "DELETE"):
            self.assertNotIn(bad, text, "no wildcard origin, PUT only")
        self.assertIn("endpointHost", json.dumps(bound["uploadOrigins"]))
        web_env = json.dumps(load("apps.json")["resources"]["web"]["properties"]["template"]["containers"][0]["env"])
        self.assertIn("ARON_BLOB_ORIGIN", web_env)



class Observability(unittest.TestCase):
    """N-062: sync-health alerts on the Java agent's logs, the ops workbook, release markers from the deploy."""

    def test_sync_health_alerts(self):
        t, _ = module("main.json", "alerts")
        rules = t["variables"]["appRequests"]
        for key, logger in (("syncErrors", 'startswith "aron.sync"'), ("aggregationStuck", '== "aron.analytics.worker"')):
            a = rules[key]
            self.assertEqual(a["frequency"], "PT1M", "evaluated every minute: alert within about 5 minutes")
            self.assertIn("union traces, exceptions", a["query"], "errors logged with a throwable land in exceptions")
            self.assertIn(logger, a["query"])
            self.assertIn('(itemType == "exception" or severityLevel >= 3)', a["query"], "errors only, never warnings")
            self.assertIn("Owner:", a["description"])
            self.assertIn("Runbook: RB-", a["description"])
        self.assertIn("frequency", json.dumps(resources_of(t, "Microsoft.Insights/scheduledQueryRules")))

    def test_workbook(self):
        t, _ = module("main.json", "monitoring")
        (wb,) = resources_of(t, "Microsoft.Insights/workbooks")
        self.assertEqual(wb["kind"], "shared")
        text = json.dumps(t)
        for needle in ("Notebook/1.0", "/v1/sync/batch", "aron.analytics.worker"):
            self.assertIn(needle, text)
        src = (ROOT / "infra" / "modules" / "monitoring.bicep").read_text(encoding="utf-8")
        fn = src[src.index("func kql("):src.index("var workbookItems")]
        content = fn[fn.index("content: {"):]
        self.assertRegex(content, r"(?m)^    showAnnotations: true$", "a root flag of content, not inside chartSettings")
        self.assertNotIn("chartSettings", fn)

    def _marker(self, az_exit, kind=None):
        import subprocess
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            log = Path(d) / "az.log"
            (Path(d) / "az").write_text(f'#!/usr/bin/env bash\nprintf "%s\\n" "$@" > "{log}"\nexit {az_exit}\n')
            (Path(d) / "az").chmod(0o755)
            env = dict(os.environ, PATH=f"{d}:{os.environ['PATH']}")
            r = subprocess.run(["bash", "infra/scripts/release-marker.sh", "/subscriptions/s/resourceGroups/rg-aron-dev",
                                "dev", "c992c9cf8f6f1c1ce3983606e54b091593896bd0", "https://x/runs/1", *([kind] if kind else [])],
                               env=env, capture_output=True, text=True, cwd=ROOT)
            return r, log.read_text().splitlines()

    def test_release_marker_request(self):
        r, args = self._marker(0)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(args[:4], ["rest", "--method", "put", "--uri"])
        self.assertEqual(args[4], "/subscriptions/s/resourceGroups/rg-aron-dev/providers/Microsoft.Insights/components/"
                                  "appi-aron-dev/Annotations?api-version=2015-05-01")
        body = json.loads(args[args.index("--body") + 1])
        self.assertEqual(body["Category"], "Deployment", "other categories do not show in the portal (Learn)")
        self.assertEqual(body["AnnotationName"], "deploy c992c9c")
        self.assertRegex(body["EventTime"], r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$")
        self.assertEqual(json.loads(body["Properties"])["Commit"], "c992c9cf8f6f1c1ce3983606e54b091593896bd0")
        _, args = self._marker(0, "rollback")
        self.assertEqual(json.loads(args[args.index("--body") + 1])["AnnotationName"], "rollback c992c9c")

    def test_release_marker_never_fails_the_deploy(self):
        r, _ = self._marker(1)
        self.assertEqual(r.returncode, 0)
        self.assertIn("::warning::release marker not written", r.stdout)
        src = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        call = src.index("infra/scripts/release-marker.sh")
        self.assertLess(src.index('infra/scripts/smoke.sh "$API_HOST"'), call, "only after the health gate passed")
        self.assertIn('|| echo "::warning::release marker step failed"', src[call:call + 400])


class SliceSmoke(unittest.TestCase):
    """Lead request 2026-10-07: the SR slice proof after a dev deploy (infra/scripts/slice-smoke.py), run here against a
    local stub of the API: it must pass on a correct server, fail on a server that doubles a re-uploaded sale, void
    its sale on the way out, and never print the password or the token."""

    PW, TOKEN = "seed-pw-Never-Printed-1", "tok-Never-Printed-2"

    def setUp(self):
        import subprocess, tempfile
        self.keydir = tempfile.TemporaryDirectory(); self.addCleanup(self.keydir.cleanup)
        self.key = os.path.join(self.keydir.name, "key.pem"); self.pub = os.path.join(self.keydir.name, "pub.pem")
        subprocess.run(["openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", self.key], check=True, capture_output=True)
        subprocess.run(["openssl", "ec", "-in", self.key, "-pubout", "-out", self.pub], check=True, capture_output=True)

    def verify(self, proof, message):
        """Raw r||s base64url -> DER, then openssl verify with the public key (what the backend does with JCA)."""
        import base64, subprocess, tempfile
        try:
            raw = base64.urlsafe_b64decode(proof + "=" * (-len(proof) % 4))
        except ValueError:
            return False
        if len(raw) != 64:
            return False
        def der_int(b):
            b = b.lstrip(b"\0") or b"\0"
            if b[0] & 0x80:
                b = b"\0" + b
            return b"\x02" + bytes([len(b)]) + b
        body = der_int(raw[:32]) + der_int(raw[32:])
        with tempfile.NamedTemporaryFile() as sig, tempfile.NamedTemporaryFile() as msg:
            sig.write(b"\x30" + bytes([len(body)]) + body); sig.flush(); msg.write(message.encode()); msg.flush()
            r = subprocess.run(["openssl", "dgst", "-sha256", "-verify", self.pub, "-signature", sig.name, msg.name], capture_output=True)
        return r.returncode == 0

    def serve(self, doubles=False, memo_read=True, totals_off=0):
        import gzip as gz, http.server, threading
        from urllib.parse import urlparse, parse_qs
        state = {"records": {}, "batches": {}, "memos": {}, "voided": set(), "calls": []}
        test = self

        def totals(day):
            live = [m for m in state["memos"].values() if m["client_uuid"] not in state["voided"]]
            return {"business_date": day, "as_of": "x",
                    "by_type": {"memo": {"accepted": len(state["memos"]), "rejected": 0, "quarantined": 0}},
                    "money": {"active_memo_count": len(live), "gross_mtk": sum(m["payload"]["gross_mtk"] for m in live)}}

        class H(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def reply(self, code, body):
                raw = json.dumps(body).encode()
                self.send_response(code); self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)

            def authed(self):
                return self.headers.get("Authorization") == "Bearer " + test.TOKEN

            def do_POST(self):
                body = self.rfile.read(int(self.headers["Content-Length"]))
                raw_gz = body
                if self.headers.get("Content-Encoding") == "gzip":
                    body = gz.decompress(body)
                b = json.loads(body)
                path = urlparse(self.path).path
                state["calls"].append(path)
                if path == "/v1/auth/login":
                    ok = b == {"username": "sr1001", "password": test.PW, "client": "app_sr",
                               "device_uuid": "00000000-0000-4000-8000-000000000001"}
                    return self.reply(200, {"status": "ok", "access_token": test.TOKEN}) if ok else self.reply(401, {"code": "bad"})
                if path == "/v1/sync/batch" and self.authed():
                    # As SyncApi: a device with a key must sign the gzip bytes (raw r||s ES256, base64url).
                    proof = self.headers.get("X-Device-Proof")
                    if not proof or not test.verify(proof, "\n".join(["aron-proof-v1", "batch", b["device_uuid"],
                                                                        hashlib.sha256(raw_gz).hexdigest(), b["batch_uuid"],
                                                                        self.headers.get("X-Batch-Attempt", "1")])):
                        return self.reply(401, {"code": "ERR_DEVICE_PROOF_INVALID"})
                    envelope = {"type", "client_uuid", "family_uuid", "rank", "schema_version", "business_date", "captured_at",
                                "route_id", "bundle_version", "config_version", "payload"}
                    for r in b["records"]:
                        if not envelope <= r.keys() or (r["type"] == "memo" and not re.fullmatch(
                                r"[a-z][a-z0-9]{3,31}-\d{6}-\d{3,4}", r["payload"]["memo_no"])):
                            return self.reply(400, {"code": "ERR_VALIDATION"})
                    if b["batch_uuid"] in state["batches"]:
                        return self.reply(200, {**state["batches"][b["batch_uuid"]], "replayed": True})
                    acks = []
                    for r in b["records"]:
                        dup = r["client_uuid"] in state["records"] and not doubles
                        if not dup:
                            state["records"][r["client_uuid"] + ("x" if r["client_uuid"] in state["records"] else "")] = r
                            if r["type"] == "memo":
                                key = r["client_uuid"] + str(len(state["memos"]))
                                state["memos"][key] = r
                            if r["type"] == "memo_void":
                                state["voided"].add(r["payload"]["memo_client_uuid"])
                        acks.append({"client_uuid": r["client_uuid"], "type": r["type"], "status": "duplicate" if dup else "accepted"})
                    resp = {"batch_uuid": b["batch_uuid"], "replayed": False, "acks": acks,
                            "server_totals": [totals(b["records"][0]["business_date"])]}
                    state["batches"][b["batch_uuid"]] = resp
                    return self.reply(200, resp)
                return self.reply(401, {"code": "unauthorized"})

            def do_GET(self):
                u = urlparse(self.path); q = parse_qs(u.query)
                state["calls"].append(u.path)
                if not self.authed():
                    return self.reply(401, {"code": "unauthorized"})
                memos = [m for m in state["memos"].values() if m["client_uuid"] not in state["voided"]]
                if u.path == "/v1/sync/bundle":
                    return self.reply(200, {
                        "meta": {"bundle_version": q["for"][0] + ":1", "config_version": 7},
                        "products": {"skus": [{"id": 5, "code": "GL-20", "status": "active", "base_unit": "stick"}]},
                        "prices": [{"sku_id": 5, "price_type": "outlet", "amount_mtk": 14500, "per_base_qty": 1, "valid_from": "2026-01-01"}],
                        "routes": [{"route_id": 3, "planned_today": True, "sales_plan_sku_ids": [5],
                                    "outlets": [{"outlet_id": 8, "code": "MIR-D-001", "lat": 23.8, "lng": 90.3, "status": "active", "radius_m": 100, "max_accuracy_m": 100},
                                                {"outlet_id": 9, "code": "SMOKE-SR-001", "lat": 23.8, "lng": 90.3, "status": "active", "radius_m": 100, "max_accuracy_m": 100}]}]})
                if u.path == "/v1/app/home":
                    return self.reply(200, {"kpis": {"active_memo_count": len(memos), "gross_mtk": sum(m["payload"]["gross_mtk"] for m in memos)}})
                if u.path == "/v1/sync/totals":
                    t = totals(q["business_date"][0]); t["money"]["gross_mtk"] += totals_off
                    return self.reply(200, {"totals": t, "day_states": [], "supervisor_day": None})
                if u.path == "/v1/memos" and memo_read:
                    return self.reply(200, {"next_cursor": None, "items": [
                        {"memo_client_uuid": m["client_uuid"], "memo_no": m["payload"]["memo_no"], "status": "active",
                         "totals": {k: m["payload"][k] for k in ("gross_mtk", "net_mtk", "paid_mtk", "due_mtk")}}
                        for m in memos if m["payload"]["memo_no"] == q["memo_no"][0]]})
                return self.reply(404, {"code": "not_found"})

        srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.shutdown)
        return srv, state

    def run_smoke(self, srv):
        import subprocess, tempfile
        with tempfile.NamedTemporaryFile("r", suffix=".md") as summary:
            env = dict(os.environ, SLICE_API_HOST=f"127.0.0.1:{srv.server_address[1]}", SLICE_SCHEME="http",
                       SLICE_PASSWORD=self.PW, SLICE_DEVICE_KEY=self.key, SLICE_TILE_WAIT_S="2", SLICE_TILE_POLL_S="0.2", GITHUB_STEP_SUMMARY=summary.name)
            r = subprocess.run([sys.executable, str(ROOT / "infra" / "scripts" / "slice-smoke.py")], env=env,
                               capture_output=True, text=True, timeout=60)
            out = r.stdout + r.stderr + summary.read()
        self.assertNotIn(self.PW, out, "the password is never printed")
        self.assertNotIn(self.TOKEN, out, "the token is never printed")
        return r.returncode, out

    def test_passes_on_a_correct_server_and_voids_its_sale(self):
        srv, state = self.serve()
        rc, out = self.run_smoke(srv)
        self.assertEqual(rc, 0, out)
        for line in ("PASS 4 sale uploaded", "PASS 5 re-upload acked duplicate", "PASS 6 batch replay",
                     "PASS 7 server count unchanged by the re-upload: (1, 1, 145000) -> (1, 1, 145000)",
                     "PASS 7b GET /v1/sync/totals agrees: (1, 1, 145000)", "PASS 8 memo read",
                     "PASS 9 dashboard tile shows the sale",
                     "PASS 10 cleanup: sale voided", "### SR slice smoke: PASSED"):
            self.assertIn(line, out)
        (memo,) = state["memos"].values()
        self.assertIn(memo["client_uuid"], state["voided"], "the smoke sale is voided")
        self.assertRegex(memo["payload"]["memo_no"], r"^sr1001-\d{6}-9\d{3}$", "contract pattern, clear of phone blocks")
        self.assertEqual(memo["payload"]["outlet_id"], 9, "the smoke's own outlet, never a tester's")

    def test_fails_when_a_re_upload_doubles_the_sale(self):
        srv, state = self.serve(doubles=True)
        rc, out = self.run_smoke(srv)
        self.assertEqual(rc, 1, out)
        self.assertIn("FAIL 5 re-upload acked duplicate", out)
        self.assertIn("### SR slice smoke: FAILED", out)
        self.assertIn("PASS 10 cleanup: sale voided", out, "a failed later step still voids the smoke sale")
        self.assertTrue(state["voided"])

    def test_fails_when_the_totals_endpoint_disagrees_with_the_batch_answers(self):
        srv, state = self.serve(totals_off=1)
        rc, out = self.run_smoke(srv)
        self.assertEqual(rc, 1, out)
        self.assertIn("FAIL 7b GET /v1/sync/totals agrees: (1, 1, 145001)", out)
        self.assertIn("PASS 10 cleanup: sale voided", out)

    def test_fails_when_the_memo_read_is_not_served(self):
        srv, state = self.serve(memo_read=False)
        rc, out = self.run_smoke(srv)
        self.assertEqual(rc, 1, out)
        self.assertIn("FAIL 8 memo read: HTTP 404", out)
        self.assertIn("PASS 10 cleanup: sale voided", out)

    def test_print_jwk_gives_the_public_half_and_its_rfc7638_thumbprint(self):
        import base64, hashlib, subprocess
        r = subprocess.run([sys.executable, str(ROOT / "infra" / "scripts" / "slice-smoke.py"), "--print-jwk", self.key],
                           capture_output=True, text=True, check=True)
        out = json.loads(r.stdout)
        jwk = out["jwk"]
        self.assertEqual((jwk["kty"], jwk["crv"]), ("EC", "P-256"))
        self.assertNotIn("d", jwk, "never the private scalar")
        self.assertEqual(len(base64.urlsafe_b64decode(jwk["x"] + "=")), 32)
        canon = json.dumps({"crv": "P-256", "kty": "EC", "x": jwk["x"], "y": jwk["y"]}, separators=(",", ":"))
        self.assertEqual(out["thumbprint"], base64.urlsafe_b64encode(hashlib.sha256(canon.encode()).digest()).rstrip(b"=").decode())
        run = (ROOT / "infra" / "scripts" / "devseed-run.sh").read_text(encoding="utf-8")
        self.assertIn("AND public_key_thumbprint IN ('seed-dev-device-0001', :'tp')", run, "never replaces another real key")

    def test_wired_after_the_health_gate_for_dev_only(self):
        d = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        self.assertLess(d.index('infra/scripts/smoke.sh "$API_HOST"'), d.rindex("python3 infra/scripts/slice-smoke.py; then"))
        self.assertLess(d.index('DBLOGINS_JOB="$(az deployment'), d.index("publish aron-devseed build_devseed"))
        self.assertLess(d.index("publish aron-devseed build_devseed"), d.index('guard_newer_live "before the apps"'))
        self.assertIn("grep -qx 'param devSeed = true' \"infra/params/${PROFILE}.apps.bicepparam\"", d, "a committed switch")
        self.assertIn('rm -f "$ctx"/04_*.sql', d, "the global dev relaxations stay off (lead)")
        self.assertIn("param devSeed = true\n", (ROOT / "infra/params/dev.apps.bicepparam").read_text(encoding="utf-8"))
        for f in ("dev-lite", "stage", "prod"):
            self.assertNotIn("param devSeed = true", (ROOT / f"infra/params/{f}.apps.bicepparam").read_text(encoding="utf-8"), f)
        self.assertNotIn("ARON_DEV_SEED", (WORKFLOWS / "deploy.yml").read_text(encoding="utf-8"), "no repository variable")
        sql = (ROOT / "infra/sql/devseed-smoke-outlet.sql").read_text(encoding="utf-8")
        self.assertIn("'SMOKE-SR-001'", sql)
        self.assertNotIn("cfg_value", sql)
        self.assertIn('echo "::add-mask::${slice_pw}"', d)
        self.assertIn('( publish aron-devseed build_devseed', d, "a failed seed image never stops the deploy")
        for f in ("stage", "prod"):
            self.assertIs(params(f"{f}.apps.parameters.json").get("devSeed", False), False, f)
        job = load("apps.json")["resources"]["dblogins"]
        self.assertIn("seed-pw", json.dumps(job["properties"]["configuration"]["secrets"]))
        self.assertIn("parameters('devSeed')", json.dumps(job["properties"]["configuration"]["secrets"]))
        run = (ROOT / "infra" / "scripts" / "devseed-run.sh").read_text(encoding="utf-8")
        self.assertIn('printf \'%s\' "$ARON_SEED_PASSWORD" | argon2', run, "the password reaches argon2 on stdin only")
        self.assertIn("\\getenv h ARON_SEED_HASH", run)
        self.assertIn("-id -t 2 -k 19456 -p 1", run, "the backend's Argon2id parameters")


class WorkerCheck(unittest.TestCase):
    """Lead 2026-10-07: the worker (no HTTP endpoint) is proven up after a deploy: this build's image, a replica Running
    with 0 restarts, still so after the hold (infra/scripts/worker-check.sh, with a fake az)."""

    IMG = "cr.example/aron-backend@sha256:" + "a" * 64

    def run_check(self, image, first, second):
        import subprocess, tempfile
        with tempfile.TemporaryDirectory() as t:
            t = Path(t)
            (t / "first.json").write_text(json.dumps(first)); (t / "second.json").write_text(json.dumps(second))
            (t / "az").write_text(f"""#!/usr/bin/env bash
case "$*" in
  *"containerapp show"*) echo rev-2 ;;
  *"revision show"*) echo {image} ;;
  *"replica list"*) if [ -f {t}/seen ]; then cat {t}/second.json; else touch {t}/seen; cat {t}/first.json; fi ;;
  *) exit 9 ;;
esac
""")
            (t / "az").chmod(0o755)
            env = dict(os.environ, PATH=f"{t}:{os.environ['PATH']}", WORKER_TIMEOUT_S="1", WORKER_HOLD_S="0", WORKER_POLL_S="0")
            r = subprocess.run(["bash", "infra/scripts/worker-check.sh", "rg", "ca-aron-dev-worker", self.IMG],
                               env=env, capture_output=True, text=True, cwd=ROOT, timeout=60)
            return r.returncode, r.stdout + r.stderr

    @staticmethod
    def replica(state="Running", cstate="Running", restarts=0):
        return [{"name": "rep-1", "properties": {"runningState": state,
                 "containers": [{"name": "worker", "runningState": cstate, "restartCount": restarts}]}}]

    def test_running_and_staying_up_passes(self):
        rc, out = self.run_check(self.IMG, self.replica(), self.replica())
        self.assertEqual(rc, 0, out)
        self.assertIn("still running", out)

    def test_old_image_crash_loop_or_restart_fails(self):
        self.assertEqual(self.run_check("cr.example/aron-backend@sha256:" + "b" * 64, self.replica(), self.replica())[0], 1,
                         "an older revision is not this deploy's worker")
        crash = self.replica(cstate="Waiting", restarts=3)
        rc, out = self.run_check(self.IMG, crash, crash)
        self.assertEqual(rc, 1, "a crash-looping replica never passes")
        self.assertIn("no replica of rev-2 running with 0 restarts", out)
        rc, out = self.run_check(self.IMG, self.replica(), self.replica(restarts=1))
        self.assertEqual(rc, 1, "a restart during the hold is a crash loop")
        self.assertIn("did not stay up", out)

    def test_wired_after_the_health_gate(self):
        d = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        self.assertLess(d.index('infra/scripts/smoke.sh "$API_HOST"'), d.index("infra/scripts/worker-check.sh"))
        self.assertIn('"ca-aron-${ENV_NAME}-worker" "$BACKEND_IMAGE"', d)
        self.assertIn('summary "| Worker | ${worker_result} |"', d)


class JvmSplit(unittest.TestCase):
    """Lead 2026-10-07: :backend:app:test (about 14.5 of 26 minutes) runs in its own parallel job; every test still runs
    exactly once, and both job names are required checks on main."""

    def test_app_tests_run_once_in_their_own_job(self):
        c = (WORKFLOWS / "ci.yml").read_text(encoding="utf-8")
        jvm = c[c.index("\n  jvm:"):c.index("\n  jvm-app:")]
        app = c[c.index("\n  jvm-app:"):c.index("\n  android:")]
        self.assertIn(":backend:app:build -x :backend:app:test", jvm)
        self.assertIn("run: ./gradlew --console=plain :backend:app:test", app)
        self.assertIn("name: Backend app tests", app)
        self.assertIn("if: needs.changes.outputs.jvm == 'true'", app, "runs exactly when the jvm job runs")
        self.assertIn("ARON_TEST_PG_URL", app, "its own PostgreSQL service")
        gov = (ROOT / "tools" / "github-governance.ps1").read_text(encoding="utf-8")
        for name in ("'Shared, db and backend (build and tests)'", "'Backend app tests'"):
            self.assertIn(name, gov)

if __name__ == "__main__":
    if not (COMPILED / "main.json").exists():
        sys.exit(f"compiled templates not found in {COMPILED}; run infra/validate.sh")
    unittest.main(verbosity=2)
