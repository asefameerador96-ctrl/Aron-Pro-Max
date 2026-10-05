#!/usr/bin/env python3
"""Offline acceptance checks for N-012 (Azure IaC) and N-013 (CI/CD). Standard library only.

Run by infra/validate.sh after `bicep build`, with ARON_COMPILED pointing at the folder holding main.json,
apps.json and the compiled parameter files (<name>.parameters.json). What these checks prove without a subscription:
the templates deploy into ONE resource group only, carry no hard-coded subscription, region or secret, contain every
resource the row asks for with the reliability settings the spec fixes, and the workflows deploy only from the
integration branch with OIDC and pinned actions. What they cannot prove (needs Azure) is listed in infra/README.md.
"""
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
        seed = (ROOT / "infra" / "scripts" / "seed-secrets.sh").read_text(encoding="utf-8")
        for s in ("aron-jwt-signing-key", "aron-jwt-kid", "aron-fcm-service-account"):
            self.assertIn(s, seed)

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

    def test_postgres_is_private(self):
        servers = self.resources("Microsoft.DBforPostgreSQL/flexibleServers")
        self.assertEqual(len(servers), 2, "primary and the optional replica")
        for s in servers:
            self.assertEqual(s["properties"]["network"]["publicNetworkAccess"], "Disabled")

    def test_no_secret_in_outputs(self):
        for name in ("main.json", "apps.json"):
            for t in nested_templates(load(name)):
                for k, o in t.get("outputs", {}).items():
                    self.assertNotRegex(k.lower(), "password|secret", f"{name}: output {k} looks like a secret")
                    self.assertNotIn("listKeys", json.dumps(o), f"{name}: output {k} leaks a key")


class SizingParameters(unittest.TestCase):
    def test_both_environments_are_zone_redundant_with_geo_backup(self):
        for env in ("dev", "prod"):
            p = params(f"{env}.parameters.json")
            self.assertEqual(p["postgresHaMode"], "ZoneRedundant", env)
            self.assertIs(p["postgresGeoRedundantBackup"], True, f"{env}: geo backup can only be set at creation")
            self.assertGreaterEqual(p["postgresBackupRetentionDays"], 7, env)
            self.assertNotIn("containerEnvZoneRedundant", {k for k, v in p.items() if v is False}, env)
            self.assertRegex(p["budgetStartDate"], r"^\d{4}-\d{2}-01$", f"{env}: budget must start on the 1st")
            self.assertGreater(p["budgetAmount"], 0)
            self.assertTrue(p["alertEmails"], f"{env}: budget contact e-mail")

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
        self.assertIn("id-token: write", d)
        self.assertIn("azure/login@", d)
        self.assertNotRegex(d, r"client-secret|creds:", "OIDC only, no client secret")
        for s in ("AZURE_CLIENT_ID", "AZURE_TENANT_ID", "AZURE_SUBSCRIPTION_ID"):
            self.assertIn(f"secrets.{s}", d)
        self.assertIn("'azure-dev'", d)
        self.assertIn(f"INTEGRATION_BRANCH: {INTEGRATION_BRANCH}", d)
        self.assertIn('"refs/heads/${INTEGRATION_BRANCH}"', d)
        self.assertIn("title=Azure is not set up for this repository", d, "clear failure when secrets are absent")
        self.assertNotRegex(d, r"(?m)^\s*(push|pull_request|pull_request_target):", "deploy is called or dispatched only")

    def test_deploy_order(self):
        d = self.text("deploy.yml")
        order = ["scope-check.sh", "infra/main.bicep", "seed-secrets.sh", "backend.Dockerfile",
                 'ARON_DEPLOY_SERVICES: "false"', "containerapp job start", 'ARON_DEPLOY_SERVICES: "true"', "smoke.sh",
                 "consumption budget show"]
        positions = [d.index(step) for step in order]
        self.assertEqual(positions, sorted(positions), "deploy steps out of order")

    def test_ci_calls_deploy_only_for_pushes_to_the_integration_branch(self):
        c = self.text("ci.yml")
        block = c[c.index("\n  deploy:"):]
        self.assertIn("uses: ./.github/workflows/deploy.yml", block)
        self.assertIn("github.event_name == 'push'", block)
        self.assertIn(f"github.ref == 'refs/heads/{INTEGRATION_BRANCH}'", block)
        for job in ("contract", "jvm", "android", "infra"):
            self.assertRegex(block, r"needs:.*\b%s\b" % job)
        self.assertIn("!failure()", block)

    def test_push_runs_are_never_cancelled(self):
        c = self.text("ci.yml")
        self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", c)
        self.assertIn("github.event_name == 'pull_request' && github.ref || github.sha", c)


if __name__ == "__main__":
    if not (COMPILED / "main.json").exists():
        sys.exit(f"compiled templates not found in {COMPILED}; run infra/validate.sh")
    unittest.main(verbosity=2)
