#!/usr/bin/env bash
# Registry retention (AUD-DG-06): in each Aron repository keep the newest KEEP images plus every image younger than
# MIN_AGE_DAYS, and NEVER delete an image that a container app or the migrate job runs now (by digest or by tag).
# Everything older that carries a tag is deleted by digest (tag and manifest together; layers are reclaimed by the
# registry). Untagged manifests are never touched (children of an index, attestations).
# The Basic registry has no retention policy of its own and 10 GiB of storage; this keeps it bounded.
# Rollback reach = the KEEP newest commits (docs/runbooks/rollback-bad-deploy.md).
# Usage: infra/scripts/acr-purge.sh <resource-group> [keep=30]   (DRY_RUN=true lists without deleting)
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
RG="${1:?resource group}"
KEEP="${2:-30}"
MIN_AGE_DAYS="${MIN_AGE_DAYS:-3}"
DRY_RUN="${DRY_RUN:-false}"
if ! [[ "$KEEP" =~ ^[0-9]+$ ]] || [ "$KEEP" -lt 10 ]; then die "keep must be a number of at least 10, was $KEEP"; fi

registry="$(az acr list -g "$RG" --query "[?starts_with(name, 'craron')].name | [0]" -o tsv)"
if [ -z "$registry" ] || [ "$registry" = None ]; then die "no Aron registry in $RG"; fi
login="$(az acr show -n "$registry" --query loginServer -o tsv)"

# Images in use: every container of every app and job in the group. A failed read stops the purge (never guess).
in_use="$(az containerapp list -g "$RG" --query "[].properties.template.containers[].image" -o tsv)" || die "cannot list the container apps"
jobs="$(az containerapp job list -g "$RG" --query "[].properties.template.containers[].image" -o tsv)" || die "cannot list the container apps jobs"
in_use="$(printf '%s\n%s\n' "$in_use" "$jobs" | grep -F "$login/" || true)"
note "registry $registry; images in use:"; printf '%s\n' "${in_use:-  (none)}"

cutoff="$(date -u -d "-${MIN_AGE_DAYS} days" +%Y-%m-%dT%H:%M:%SZ)"
deleted=0; kept=0
for repo in aron-backend aron-web; do
  az acr repository show -n "$registry" --repository "$repo" -o none 2>/dev/null || { note "$repo: not in the registry"; continue; }
  # newest first: digest, tags (comma-joined), last update
  rows="$(az acr manifest list-metadata --registry "$registry" --name "$repo" --orderby time_desc \
    --query "[].[digest, join(',', tags || \`[]\`), lastUpdateTime]" -o tsv)" || die "cannot list the manifests of $repo"
  i=0
  # '|' as the separator: tab is IFS whitespace, so an empty tag list between two tabs would shift the fields.
  while IFS='|' read -r digest tags updated; do
    # Only tagged manifests count and are deleted: untagged ones are the children of a multi-platform index or
    # attestations; deleting one could break the image that references it. Deleting a tag's manifest frees its own.
    if [ -z "$digest" ] || [ -z "$tags" ]; then continue; fi
    i=$(( i + 1 ))
    reason=""
    [ "$i" -le "$KEEP" ] && reason="newest $KEEP"
    [ -z "$reason" ] && [[ "$updated" > "$cutoff" ]] && reason="younger than ${MIN_AGE_DAYS} days"
    if [ -z "$reason" ]; then
      if grep -qF "${login}/${repo}@${digest}" <<<"$in_use"; then reason="in use"; fi
      for t in ${tags//,/ }; do
        grep -qxF "${login}/${repo}:${t}" <<<"$in_use" && reason="in use"
      done
    fi
    if [ -n "$reason" ]; then kept=$(( kept + 1 )); continue; fi
    if [ "$DRY_RUN" = true ]; then
      echo "would delete ${repo}@${digest} (${tags:-untagged}, ${updated})"
    else
      az acr repository delete -n "$registry" --image "${repo}@${digest}" --yes -o none \
        || die "could not delete ${repo}@${digest}"
      echo "deleted ${repo}@${digest} (${tags:-untagged}, ${updated})"
    fi
    deleted=$(( deleted + 1 ))
  done <<<"$(tr '\t' '|' <<<"$rows")"
done
line="ACR purge of ${registry}: kept ${kept}, $([ "$DRY_RUN" = true ] && echo 'would delete' || echo deleted) ${deleted} (keep ${KEEP}, min age ${MIN_AGE_DAYS} days)"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && echo "$line" >> "$GITHUB_STEP_SUMMARY"
echo "$line"
