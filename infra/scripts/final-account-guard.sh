#!/usr/bin/env bash
# Where may a READ-ONLY ops workflow (what-if, diagnose) run for this profile? One rule set for the workflows that are not
# deploys (deploy.yml keeps its own inline copy of the same rules, pinned by infra/tests/check_infra.py):
#   dev, dev-lite   from the integration branch (the azure-dev environment deploys from there only);
#   stage, stage-lite   only in the FINAL account (ARON_FINAL_ACCOUNT=true) and only when the GitHub environment
#                   azure-stage / azure-prod exists WITH a required reviewer (GitHub creates a missing environment unprotected),
#                   from main. prod is refused here (see below). Fails closed: an unreadable environment is a refusal.
# Usage: GITHUB_REF=... GITHUB_REPOSITORY=... GH_TOKEN=... ARON_FINAL_ACCOUNT=... infra/scripts/final-account-guard.sh <profile>
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
profile="${1:?profile: dev, dev-lite, stage, stage-lite or prod}"
ref="${GITHUB_REF:?GITHUB_REF}"
case "$profile" in
  dev|dev-lite)
    [ "$ref" = "refs/heads/claude/wonderful-thompson-k6ejnf" ] || die "${profile} runs only from the integration branch, not ${ref}" ;;
  prod)
    die "read-only ops workflows do not run for prod: azure-prod admits only server-v* tags and its identity is the deployer. Run infra/validate.sh (what-if) and infra/scripts/diagnose.sh from the laptop with the owner's az sign-in instead" ;;
  app-release)
    # release-app.yml's release job: the environment must exist with a required reviewer (GitHub would create it unprotected)
    gh_env="app-release"
    reviewers="$(gh api "repos/${GITHUB_REPOSITORY:?GITHUB_REPOSITORY}/environments/${gh_env}" \
      --jq '[.protection_rules[]? | select(.type == "required_reviewers")] | length' 2>/dev/null)" \
      || die "cannot read the GitHub environment ${gh_env}; refusing the app release"
    if ! [[ "$reviewers" =~ ^[0-9]+$ ]] || [ "$reviewers" -lt 1 ]; then die "the GitHub environment ${gh_env} has no required reviewer; run tools/github-governance.ps1 first"; fi ;;
  stage|stage-lite)
    [ "${ARON_FINAL_ACCOUNT:-}" = true ] || die "no ${profile} before the final account exists (ARON_FINAL_ACCOUNT, docs/28)"
    [ "$ref" = "refs/heads/main" ] || die "${profile} runs only from main, not ${ref}"
    gh_env="azure-${profile%-lite}"
    reviewers="$(gh api "repos/${GITHUB_REPOSITORY:?GITHUB_REPOSITORY}/environments/${gh_env}" \
      --jq '[.protection_rules[]? | select(.type == "required_reviewers")] | length' 2>/dev/null)" \
      || die "cannot read the GitHub environment ${gh_env} (does it exist? does the token have actions: read?); refusing ${profile}"
    if ! [[ "$reviewers" =~ ^[0-9]+$ ]] || [ "$reviewers" -lt 1 ]; then
      die "the GitHub environment ${gh_env} has no required reviewer; run tools/github-governance.ps1 first"
    fi ;;
  *) die "unknown profile ${profile}" ;;
esac
note "${profile}: allowed from ${ref}"
