#!/usr/bin/env bash
# The ONLY way the Apsis dump landing place is created or filled (docs/requests/apsis-dump-landing.md): it refuses, before anything
# is created or uploaded, unless the signed-in Azure subscription is the FINAL one. The dump may enter only the final account.
#   - --final-subscription-id <id>   typed by the person running it (the owner's value; never committed, never read from the
#                                    signed-in context, which would always match); must equal the signed-in subscription;
#   - --ack "<phrase>"               the exact phrase below;
#   - the TEST subscription is recognised by its resource group rg-aron-dev and refused outright;
#   - the final subscription must hold rg-aron-stage or rg-aron-prod (it was bootstrapped).
# Usage:
#   infra/scripts/apsis-landing.sh deploy --final-subscription-id <id> --ack "<phrase>" --region <region> --owner-ip <ipv4> \
#       [--log-workspace-id <id>] [--pe-subnet-id <id> --dns-zone-id <id>] [--loader-principal-id <id>]
#   infra/scripts/apsis-landing.sh upload --final-subscription-id <id> --ack "<phrase>" --account <name> --source <folder> --date <yyyymmdd>
#   infra/scripts/apsis-landing.sh remove-owner-ip --final-subscription-id <id> --ack "<phrase>" --account <name> --owner-ip <ipv4>
#   <stream> | infra/scripts/apsis-landing.sh upload-stream --final-subscription-id <id> --ack "<phrase>" --account <name> --date <yyyymmdd> --name <file>
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
ACK_PHRASE="the dump goes to the final account only"
LANDING_RG="rg-aron-apsis-landing"

cmd="${1:-}"; [ -n "$cmd" ] || die "usage: apsis-landing.sh deploy|upload|upload-stream|remove-owner-ip ... (see the header)"
shift
final_sub="" ack="" region="" owner_ip="" law="" pe_subnet="" dns_zone="" loader="" account="" source_dir="" day="" name=""
while [ $# -gt 0 ]; do
  case "$1" in
    --final-subscription-id) final_sub="${2:-}"; shift 2 ;;
    --ack) ack="${2:-}"; shift 2 ;;
    --region) region="${2:-}"; shift 2 ;;
    --owner-ip) owner_ip="${2:-}"; shift 2 ;;
    --log-workspace-id) law="${2:-}"; shift 2 ;;
    --pe-subnet-id) pe_subnet="${2:-}"; shift 2 ;;
    --dns-zone-id) dns_zone="${2:-}"; shift 2 ;;
    --loader-principal-id) loader="${2:-}"; shift 2 ;;
    --account) account="${2:-}"; shift 2 ;;
    --source) source_dir="${2:-}"; shift 2 ;;
    --date) day="${2:-}"; shift 2 ;;
    --name) name="${2:-}"; shift 2 ;;
    *) die "unknown argument $1" ;;
  esac
done

# ---------------------------------------------------------------------------------------------------- the guard
[[ "$final_sub" =~ ^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$ ]] || die "--final-subscription-id <the FINAL subscription id> is required"
[ "$ack" = "$ACK_PHRASE" ] || die "--ack \"$ACK_PHRASE\" is required: the dump may enter only the final account"
# Every az call below names the subscription (--subscription), never the shell's default. Output is stripped of CR (Git Bash).
azs() { az "$@" --subscription "$final_sub" 2>/dev/null | tr -d '\r'; }
signed="$(az account show --query id -o tsv 2>/dev/null | tr -d '\r')" || die "az is not signed in"
[ "${signed,,}" = "${final_sub,,}" ] || die "the signed-in subscription is not the one you named as FINAL; nothing was created or uploaded (az account set --subscription <final id>)"
# `az group exists` must answer exactly true or false; an az error or anything else is a refusal (fail closed), never "false".
group_exists() {
  local out
  out="$(az group exists --name "$1" --subscription "$final_sub" 2>/dev/null | tr -d '\r')" || die "cannot tell whether $1 exists (az failed); nothing was created or uploaded"
  case "$out" in true|false) printf '%s' "$out" ;; *) die "cannot tell whether $1 exists (az answered '${out:0:40}'); nothing was created or uploaded" ;; esac
}
if [ "$(group_exists rg-aron-dev)" = true ]; then
  die "this subscription holds rg-aron-dev: it is the TEST account. The dump may enter only the final account; nothing was created or uploaded"
fi
if [ "$(group_exists rg-aron-prod)" != true ] && [ "$(group_exists rg-aron-stage)" != true ]; then
  die "neither rg-aron-prod nor rg-aron-stage exists here: this is not a bootstrapped final subscription; nothing was created or uploaded"
fi
note "subscription confirmed as the final one"

case "$cmd" in upload|upload-stream) [[ "$day" =~ ^[0-9]{8}$ ]] || die "--date <yyyymmdd> is required" ;; esac
blob_url() { printf 'https://%s.blob.core.windows.net/apsis-dump/incoming/%s/%s' "$account" "$day" "$1"; }
require_landing_account() {
  [ -n "$account" ] || die "--account <storage account name> is required"
  [ "$(azs storage account show -g "$LANDING_RG" -n "$account" --query 'tags.component' -o tsv)" = apsis-landing ] \
    || die "$account is not the landing account of $LANDING_RG in this subscription"
}

case "$cmd" in
  deploy)
    if [ -z "$region" ] || [ -z "$owner_ip" ]; then die "--region and --owner-ip are required"; fi
    owner_oid="$(az ad signed-in-user show --query id -o tsv | tr -d '\r')" || die "cannot read the signed-in user's object id"
    az group create --subscription "$final_sub" --name "$LANDING_RG" --location "$region" \
      --tags project=aron component=apsis-landing data-class=confidential lifecycle=delete-after-migration-signoff --only-show-errors -o none
    args=(--resource-group "$LANDING_RG" --template-file "$(dirname "$0")/../apsis-landing.bicep"
          --parameters "expectedSubscriptionId=$final_sub" "uploaderPrincipalIds=[\"$owner_oid\"]" "uploaderIpAddresses=[\"$owner_ip\"]")
    [ -z "$law" ] || args+=("logAnalyticsWorkspaceId=$law")
    [ -z "$pe_subnet" ] || args+=("privateEndpointSubnetId=$pe_subnet")
    [ -z "$dns_zone" ] || args+=("blobPrivateDnsZoneId=$dns_zone")
    [ -z "$loader" ] || args+=("loaderPrincipalIds=[\"$loader\"]")
    az deployment group create "${args[@]}" --subscription "$final_sub" --query properties.outputs -o json
    echo "::warning::lifecycle: dump blobs are DELETED 60 days after their last write; if the migration will run longer, re-upload or raise dumpDeleteAfterDays before then"
    echo "::warning::after the upload is verified run: apsis-landing.sh remove-owner-ip ... (the private endpoint is then the only way in)"
    if [ -n "$law" ]; then echo "::warning::blob access logs go to the shared workspace and outlive the group's deletion for that workspace's retention"; fi ;;
  remove-owner-ip)
    # The ONE supported way to close the upload path after the upload is verified (the private endpoint is then the only way in).
    require_landing_account
    [ -n "$owner_ip" ] || die "--owner-ip <the address to remove> is required"
    az storage account network-rule remove --subscription "$final_sub" -g "$LANDING_RG" --account-name "$account" --ip-address "$owner_ip" -o none
    note "owner address removed from $account" ;;
  upload)
    require_landing_account
    [ -d "$source_dir" ] || die "--source <folder with the .sql files> is required"
    command -v azcopy >/dev/null || die "azcopy is not installed"
    azcopy copy "${source_dir%/}/*" "$(blob_url '')" --put-md5 --block-blob-tier Hot ;;
  upload-stream)
    require_landing_account
    [ -n "$name" ] || die "--name <file name> is required"
    command -v azcopy >/dev/null || die "azcopy is not installed"
    azcopy copy --from-to PipeBlob "$(blob_url "$name")" --put-md5 ;;
  *) die "unknown command $cmd" ;;
esac
