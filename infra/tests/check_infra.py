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
        seed = (ROOT / "infra" / "scripts" / "seed-secrets.sh").read_text(encoding="utf-8")
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
        self.assertNotIn("stage", self.wf("deploy.yml").split("options:")[1].split("\n")[0])

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
        self.assertNotIn("concurrency:", d)
        self.assertIn("RUN_MIGRATIONS: ${{ inputs.run_migrations == false && 'false' || 'true' }}", d)
        call = d[d.index("workflow_call:"):d.index("workflow_dispatch:")]
        self.assertIn("run_migrations:", call, "a called deploy (promote-prod) must see run_migrations = true, not null")
        self.assertIn('[ "${GITHUB_WORKFLOW}" = promote-prod ]', d, "prod only through promote-prod")
        self.assertIn('[ "${FINAL}" = "true" ]', d, "prod only in the final account")
        conditions = re.findall(r"(?m)^\s*if:\s*(.*)$", d)
        self.assertEqual(conditions, ["failure() && steps.login.outcome == 'failure'"],
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
        self.assertIn('die "migrations $execution ended ${status:-without finishing in 20 minutes}; the apps were NOT updated', d)
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
        self.assertIn('[ "$(read_lock | cut -d\' \' -f1)" = "$lock_me" ] && break', d, "write, settle, re-read")
        self.assertIn("-lt 5700 ]", d, "a lock older than 95 minutes is stale")
        full = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        self.assertIn('RUN_MIGRATIONS="${RUN_MIGRATIONS:-true}"', full)
        self.assertNotIn("grep -qs", full, "no source sniffing to decide what to deploy")

    def test_ci_calls_deploy_only_for_pushes_to_the_integration_branch(self):
        c = self.text("ci.yml")
        block = c[c.index("\n  deploy:"):]
        self.assertIn("uses: ./.github/workflows/deploy.yml", block)
        expected_if = ("!cancelled() && !failure() && github.event_name == 'push' "
                       f"&& github.ref == 'refs/heads/{INTEGRATION_BRANCH}' && needs.changes.outputs.deploy == 'true'")
        m = re.search(r"if: >-\n((?:\s{6}.*\n)+)", block)
        self.assertTrue(m, "deploy job has no if:")
        self.assertEqual(" ".join(m.group(1).split()), expected_if)
        self.assertIn("needs: [changes, gates, contract, jvm, web, android, android-release, images, infra]", block)
        self.assertIn("secrets: inherit", block)

    def test_push_runs_are_never_cancelled(self):
        # 14 lanes push to the integration branch: every workflow a push triggers groups by ref AND commit, and only
        # pull requests cancel their predecessor. The deploy itself is serialised in Azure (deploy.sh lock), not here.
        for wf in WORKFLOWS.glob("*.yml"):
            c = self.text(wf.name)
            if not re.search(r"(?m)^  push:", c):
                continue
            self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", c, wf.name)
            self.assertIn("github.event_name == 'pull_request' && github.ref || format('{0}-{1}', github.ref, github.sha)", c, wf.name)
            self.assertEqual(c.count("cancel-in-progress"), 1, wf.name)
        self.assertNotRegex(self.text("deploy.yml"), r"(?m)^\s*concurrency:", "deploy serialises with the Azure-side lock in deploy.sh")
        d = (ROOT / "infra" / "deploy.sh").read_text(encoding="utf-8")
        self.assertIn("deploy lock held", d)
        self.assertIn("merge-base --is-ancestor", d)

    def test_repository_gates_run_on_every_push(self):
        c = self.text("ci.yml")
        block = c[c.index("\n  gates:"):c.index("\n  contract:")]
        self.assertNotIn("\n    if:", block, "the gates job runs on every push and pull request")
        for needle in ("tools/ci/install-tool.sh", "tools/ci/test_gates.py", "gitleaks git --no-banner --redact --exit-code 1",
                       "--gitleaks-ignore-path tools/ci/gitleaksignore", "--config tools/ci/gitleaks.toml", "tools/ci/migrations-check.sh",
                       '"${CHECK_BASE}" squawk', 'git merge-base "${BEFORE}" "${GITHUB_SHA}"',
                       "tools/ci/contract-breaking.sh", "fetch-depth: 0"):
            self.assertIn(needle, block)
        tools = (ROOT / "tools" / "ci" / "install-tool.sh").read_text(encoding="utf-8")
        self.assertEqual(len(re.findall(r'sha="[0-9a-f]{64}"', tools)), 4, "every gate binary is checksum-pinned")
        self.assertIn("sha256sum -c", tools)

    def test_release_apk_and_size_gate(self):
        c = self.text("ci.yml")
        block = c[c.index("\n  android-release:"):c.index("\n  web:")]
        self.assertIn(":android:app-sr:assembleRelease", block)
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


if __name__ == "__main__":
    if not (COMPILED / "main.json").exists():
        sys.exit(f"compiled templates not found in {COMPILED}; run infra/validate.sh")
    unittest.main(verbosity=2)
