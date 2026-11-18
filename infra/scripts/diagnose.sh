#!/usr/bin/env bash
# Read-only diagnostics of the dev rehearsal environment (D-OPS-DIAGNOSE-01), run by .github/workflows/ops-diagnose.yml
# with the deploy identity: the PostgreSQL server (state, metrics, parameters, server-log excerpts when downloadable,
# activity log, diagnostic settings), the PostgreSQL server log lines streamed to Log Analytics, the migrate job's and the
# worker's and the API's console and system logs from Log Analytics, the migrate job's pre-flight lines, and the worker app's state. Everything printed is metadata, metrics
# or log excerpts; never a secret, connection string or Key Vault value.
# This script changes nothing in Azure: it only lists, shows, queries and downloads logs.
#
# Usage: AZURE_RESOURCE_GROUP=rg-aron-dev infra/scripts/diagnose.sh <since-utc> <until-utc> [job-prefix] [worker-app] [api-app]
set -uo pipefail

# --- redaction (D-OPS-DIAGNOSE-API-01/-02/-03). The repository is public and so are its job logs: EVERY line this script prints goes through redact(), and
# each query result is redacted over the WHOLE text and only then cut to its width, by CHARACTERS (so a cut can never leave half a number or token behind and
# never splits a Bangla letter). The filter is one perl program: it decodes leniently (invalid bytes become U+FFFD, it never dies on a line), fails closed (a line
# it cannot process is replaced by a notice, never printed raw) and keeps reading to the end of its input.
# What it catches: key=value and JSON "key":"value" forms of secret-like keys (compound names too: access_token, refreshToken, otp_code, client_secret,
# sessionid, pass, InstrumentationKey), quoted values with spaces, "the password is X" prose, personal keys (name, phone, e-mail, address, device id, ip),
# OTP/PIN digits in prose on lines that talk about OTP/PIN, Bangladesh mobile numbers in every spacing (dashed, spaced, dotted, bracketed, slashed, digit by digit,
# +880, without the 0, glued to letters; Bangla and Arabic-Indic digits), e-mail addresses, JWTs, Bearer/Basic, cookies and Set-Cookie, connection strings,
# IPv4 and IPv6, and any 40+ character token-looking run. Kept on purpose, so a request can still be followed: a UUID (hyphenated or not) is replaced by the
# first 8 hex of its sha256 ([uuid:xxxxxxxx], the same UUID gives the same text), and 40-hex git shas after build/commit/sha/image or on a health line,
# four-part versions after version/build/release/tag, and epoch seconds after exp/iat/ts/time keys are left alone.
# What it does NOT catch: FREE-TEXT names and usernames that are not in a key=value or JSON form. The defence for those is that the API and worker log ids,
# hashes and class names only (no payload, no person data); this net is not a licence to log such values. It over-redacts elsewhere on purpose: a password-like key
# takes up to 3 plain words after an unquoted value, a bare 10-digit number starting 13..19 reads as a phone (not before a date), and OTP/PIN digits next to an otp/pin/
# sms/verification word go (durations and counts with a unit stay). Every pattern has a BOUNDED prefix, so an unbroken 100 KB run is linear (tested). The UUID tag
# uses a per-run random salt (REDACT_SALT): the same id matches across lines of one run but cannot be followed across runs in public logs.
read -r -d '' REDACT_PL <<'PERL' || true
use strict; use warnings; no warnings 'utf8';
use Encode qw(decode encode FB_DEFAULT);
use Digest::SHA qw(sha256_hex);
$| = 1; binmode(STDIN); binmode(STDOUT);
my $W = (defined $ENV{REDACT_WIDTH} && $ENV{REDACT_WIDTH} =~ /^[0-9]+$/) ? $ENV{REDACT_WIDTH} + 0 : 0;
my $SALT = (defined $ENV{REDACT_SALT} && length $ENV{REDACT_SALT}) ? $ENV{REDACT_SALT} : sprintf("%08x%08x", int(rand(4294967296)), int(rand(4294967296)));
my $DIE_ON = $ENV{REDACT_SELFTEST_DIE};   # test seam only: a line holding this text makes the scrubber fail, to prove the fail-closed notice
my $SEP = qr/[\s().\-_\/\[\]]/;
# A value: an escaped-quoted string (raw JSON inside a JSON console line), a quoted string, an unclosed quote (to the end of the line), a nested object or array, or a bare run
# that also takes ; and , (so the tail of a ;- or ,-separated secret goes too).
my $VAL = qr/\\"(?:(?!\\").){0,500}\\"|"(?:[^"\\]|\\.){0,500}"|'[^']{0,500}'|\\"[^\n]*|["'][^\n]*|\{(?:[^{}]|\{[^{}]*\}){0,500}\}|\[[^\]]{0,500}\]|[^"\s}]+/;
my $QE = qr/(?:\\?["'])?/;   # the closing quote of a key, escaped or not
# Prefixes and suffixes around a stem are BOUNDED: an unbounded one is quadratic on an unbroken run of letters and digits (a 100 KB blob would hit the job timeout).
my $KEYLONG = qr/[A-Za-z0-9_.\-]{0,128}(?:token|secret|password|passwd|passcode|credential|authorization|apikey|api_key|accesskey|access_key|accountkey|account_key|privatekey|private_key|instrumentationkey|sessionid|session_id|cookie|connection_?string)[A-Za-z0-9_.\-]{0,128}/i;
my $KEYSHORT = qr/(?<![A-Za-z0-9])(?:[A-Za-z0-9]{1,128}[_.\-]){0,8}(?:pass|pwd|pin|otp|key|sig|sas|session|auth)(?:[_.\-][A-Za-z0-9]{1,128}){0,8}/i;
my $KEYCAMEL = qr/[a-z][a-z0-9]{0,128}(?:Key|Pin|Otp|Pass|Sig|Sas|Session)[A-Za-z0-9]{0,128}|(?:key|pin|otp|pass|sig|sas|session|auth)[A-Z][A-Za-z0-9]{0,128}/;
my $KEYNAME = qr/(?:user_?name|phone|mobile|msisdn|e?mail|full_?name|display_?name|name|outlet_?name|address|device_?id|imei|ip|remote_?addr|client_?ip)/i;
my $UNIT = qr/(?!\s*(?:ms|s|sec|secs|seconds|bytes|kb|mb|gb|%|rows|items|attempts|times|retries)\b)/i;
my @keep;
sub hold { push @keep, $_[0]; return "\x{E000}" . $#keep . "\x{E001}"; }
sub scrub {
  my ($l) = @_;
  $l =~ tr/\x{09E6}-\x{09EF}/0-9/; $l =~ tr/\x{0660}-\x{0669}/0-9/; $l =~ tr/\x{06F0}-\x{06F9}/0-9/;
  @keep = ();
  # what the operator needs to follow a request or a release stays readable
  die "selftest" if defined $DIE_ON && length $DIE_ON && index($l, $DIE_ON) >= 0;
  $l =~ s/\b((?:build|commit|sha|rev(?:ision)?|git|image|digest)\w{0,20}[^0-9a-fA-F\n]{0,20})([0-9a-f]{40}|[0-9a-f]{64})(?![0-9a-f])/$1 . hold($2)/gie;
  $l =~ s/((?<![A-Za-z0-9.\-])(?:localhost|[A-Za-z0-9\-]{1,63}(?:\.[A-Za-z0-9\-]{1,63}){1,5})(?::[0-9]{1,5})?\/(?:[A-Za-z0-9._\-]{1,100}\/){0,4}[A-Za-z0-9._\-]{1,100}[:@](?:sha256:)?)([0-9a-f]{40}|[0-9a-f]{64})(?![0-9a-f])/$1 . hold($2)/ge;
  $l =~ s/(?<![0-9a-f])([0-9a-f]{40})(?![0-9a-f])/hold($1)/ge if $l =~ /health/i;
  $l =~ s/\b((?:version|ver|build|release|tag|contract|image)\w{0,20}[\s"'=:v]{0,4})(\d+\.\d+\.\d+\.\d+)(?![0-9.])/$1 . hold($2)/gie;
  $l =~ s/\b((?:epoch|ts|time(?:stamp)?|exp(?:ires)?|iat|nbf|unix)\w{0,20}["'\s]{0,4}[=:]\s*)(1[0-9]{9}|1[0-9]{12})(?![0-9])/$1 . hold($2)/gie;
  # secrets
  $l =~ s/\b((?:set-)?cookie2?)\s*[:=].*$/$1: [redacted]/gi;
  $l =~ s/eyJ[A-Za-z0-9_-]{5,}(?:\.[A-Za-z0-9_-]+)*/[jwt]/g;
  $l =~ s/\b(Bearer|Basic)[ =:]+[A-Za-z0-9._~+\/=-]+/$1 [redacted]/gi;
  $l =~ s/(jdbc:|postgres(?:ql)?:\/\/|AccountKey=|SharedAccessSignature=|Endpoint=sb:|Server=|Host=|User ?Id=|Uid=)[^\s",;]+/${1}[redacted]/gi;
  # password-like keys also take up to 3 following plain words when the value was unquoted (a password may hold a space); over-redaction, on purpose
  $l =~ s/((?:[A-Za-z0-9_.\-]{0,128})(?:password|passwd|passcode|secret|pwd)[A-Za-z0-9_.\-]{0,128}${QE}\s{0,40}[=:]\s{0,40})([^"'\\\s{\[][^"\s}]*(?:[ \t]+(?![A-Za-z_][\w.\-]*\s{0,3}[=:])[^\s"]+){0,3})/${1}[redacted]/gi;
  $l =~ s/($KEYLONG${QE}\s{0,40}[=:]\s{0,40})$VAL/${1}[redacted]/g;
  $l =~ s/($KEYSHORT${QE}\s{0,40}[=:]\s{0,40})$VAL/${1}[redacted]/g;
  $l =~ s/\b($KEYCAMEL${QE}\s{0,40}[=:]\s{0,40})$VAL/${1}[redacted]/g;
  $l =~ s/\b($KEYNAME${QE}\s{0,40}[=:]\s{0,40})$VAL/${1}[redacted]/g;
  $l =~ s/\b(password|passwd|passcode|pin|otp|secret|token|key)\s+(?:is|was|=|:)\s+(?:"[^"]*"|'[^']*'|<[^>]*>|&lt;.*?&gt;|\S+)/$1 [redacted]/gi;
  $l =~ s/\b(otp|one[- ]time (?:code|password)|verification code|passcode)\b[^0-9\n]{0,60}[0-9]{4,8}\b$UNIT/$1 [redacted]/gi;
  # personal data and identifiers
  $l =~ s/[A-Za-z0-9._%+-]{1,128}@[A-Za-z0-9.-]{1,255}\.[A-Za-z]{2,}/[email]/g;
  $l =~ s/\b([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\b/"[uuid:" . substr(sha256_hex($SALT . lc($1 =~ tr|-||dr)), 0, 8) . "]"/gie;
  $l =~ s/(?<![0-9a-f])([0-9a-f]{32})(?![0-9a-f])/"[uuid:" . substr(sha256_hex($SALT . lc $1), 0, 8) . "]"/gie;
  $l =~ s/(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}(?![0-9a-f:])/[ip]/gi;
  $l =~ s/(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){1,7}:(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?(?![0-9a-f:])/[ip]/gi;
  $l =~ s/(?<![0-9.])([0-9]{1,3}(?:\.[0-9]{1,3}){3})(?![0-9.])/do { my $m = $1; ($m eq "0.0.0.0" || $m =~ m|^127\.|) ? $m : "[ip]" }/ge;
  $l =~ s/\b((?:otp|pin|passcode|one[- ]time|verification|sms)(?: code| password)?\b[^0-9\n]{0,60})(?<![0-9:.\/\-])[0-9]{4,8}(?![0-9:.\/\-])$UNIT/$1\[code]/gi;
  $l =~ s/(?<![0-9:.\/\-])[0-9]{4,8}(?![0-9:.\/\-])$UNIT([^0-9\n]{0,60}\b(?:otp|pin|passcode|one[- ]time|verification|sms)\b)/[code]$1/gi;
  $l =~ s/((?<![0-9])(?:\+\s{0,6})?(?:[(\[]?88[)\]]?${SEP}{0,6})?(?:[(\[]?0[)\]]?${SEP}{0,6})?1${SEP}{0,6}[3-9](?:${SEP}{0,6}[0-9]){8}(?![0-9]))/do { my $m = $1; ($m =~ m{(?:19|20)[0-9]{2}[\-\/.][0-9]{2}[\-\/.][0-9]{2}}) ? $m : "[phone]" }/ge;
  $l =~ s/[A-Za-z0-9+\/_-]{40,}={0,2}/[long-token]/g;
  $l =~ s/\x{E000}(\d+)\x{E001}/$keep[$1]/g;
  return $l;
}
while (my $raw = <STDIN>) {
  my $nl = ($raw =~ s/\r?\n\z//) ? "\n" : "";
  my $l = decode('UTF-8', $raw, FB_DEFAULT);
  my $o = eval { scrub($l) };
  $o = "[line withheld: redaction filter error]" unless defined $o;
  $o = substr($o, 0, $W) if $W && length($o) > $W;
  print encode('UTF-8', $o), $nl;
}
PERL
# One random salt per run for the UUID tag: the same id matches across lines of THIS run, but cannot be followed across runs in public logs.
REDACT_SALT="${REDACT_SALT:-$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')}"; export REDACT_SALT
redact() { perl -e "$REDACT_PL"; }
# show <width>: redact the whole text first, then cut to the width in characters (inside the same perl program).
show() { REDACT_WIDTH="$1" perl -e "$REDACT_PL"; }
# held_note: stdin = the two numbers (held, shown) of the count query; prints the line that says how many lines the secret-word exclusion held back.
held_note() {
  local nums a b; nums="$(tr -s '[:space:],' ' ' | sed 's/^ //')"; a="${nums%% *}"; b="${nums#* }"; b="${b%% *}"
  if [[ "$a" =~ ^[0-9]+$ && "$b" =~ ^[0-9]+$ ]]; then echo "($a line(s) held back by the secret-word exclusion, $b not held; the list below is capped at the newest 150)"; else echo "(held-back count unavailable)"; fi
}

# Test seams (infra/tests/check_infra.py): diagnose.sh --redact [width] and --held-note filter stdin, no Azure, no arguments.
if [ "${1:-}" = "--redact" ]; then if [ -n "${2:-}" ]; then show "$2"; else redact; fi; exit 0; fi
if [ "${1:-}" = "--held-note" ]; then held_note; exit 0; fi

RG="${AZURE_RESOURCE_GROUP:?set AZURE_RESOURCE_GROUP}"
SINCE="${1:?since (UTC, e.g. 2026-11-05T09:40:00Z)}"
UNTIL="${2:?until (UTC)}"
JOB_PREFIX="${3:-caj-aron-dev-migrate}"
WORKER_APP="${4:-ca-aron-dev-worker}"
API_APP="${5:-ca-aron-dev-api}"
# Every input is checked against a fixed pattern BEFORE it enters a query or an az argument (nothing here is ever evaluated, but a quote in a value would
# change a KQL query). The value itself is not echoed back.
valid() { [[ "$2" =~ $3 ]] || { echo "diagnose: invalid $1 (does not match its fixed pattern)" >&2; exit 2; }; }
valid resource-group "$RG" '^[A-Za-z0-9._()-]{1,90}$'
valid since "$SINCE" '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$'
valid until "$UNTIL" '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$'
valid job-prefix "$JOB_PREFIX" '^[a-z0-9][a-z0-9-]{0,62}$'
valid worker-app "$WORKER_APP" '^[a-z0-9][a-z0-9-]{0,62}$'
valid api-app "$API_APP" '^[a-z0-9][a-z0-9-]{0,62}$'
# Everything below, including text this script prints itself, passes the net once more on the way out (the per-query redact-then-cut above is what
# protects against a cut inside a value; this protects every section, the older ones too).
exec > >(redact)
section() { printf '\n== %s\n' "$*"; }
try() { "$@" 2>&1 || echo "(command failed: $*)"; }

section "az version"
az version -o tsv 2>/dev/null | head -1 || true
section "containerapp start/stop commands available in this az"
az containerapp -h 2>&1 | grep -E '^\s+(start|stop)\b' || echo "(no start/stop subcommands listed)"

section "PostgreSQL server"
server="$(az postgres flexible-server list -g "$RG" --query "[?starts_with(name,'psql-aron-')].name | [0]" -o tsv 2>/dev/null || true)"
if [ -z "$server" ]; then echo "(no psql-aron-* server in $RG)"; else
  echo "server: $server"
  try az postgres flexible-server show -g "$RG" -n "$server" --query '{state:state, version:version, sku:sku.name, tier:sku.tier, storageGb:storage.storageSizeGb, ha:highAvailability.mode, haState:highAvailability.state, maintenance:maintenanceWindow.customWindow}' -o json
  sid="$(az postgres flexible-server show -g "$RG" -n "$server" --query id -o tsv 2>/dev/null || true)"
  section "PostgreSQL diagnostic settings (which log categories stream to Log Analytics)"
  if [ -n "$sid" ]; then try az monitor diagnostic-settings list --resource "$sid" -o json | head -60; fi
  section "PostgreSQL parameters that bound a migration"
  for p in work_mem shared_buffers maintenance_work_mem temp_buffers statement_timeout idle_in_transaction_session_timeout lock_timeout log_min_duration_statement logfiles.download_enable logfiles.retention_days max_connections; do
    v="$(az postgres flexible-server parameter show -g "$RG" -s "$server" -n "$p" --query value -o tsv 2>/dev/null || echo '?')"
    printf '%-40s %s\n' "$p" "$v"
  done
  section "PostgreSQL metrics $SINCE .. $UNTIL (PT1M, max)"
  if [ -n "$sid" ]; then
    try az monitor metrics list --resource "$sid" --metric cpu_percent memory_percent active_connections connections_failed is_db_alive storage_percent iops \
      --start-time "$SINCE" --end-time "$UNTIL" --interval PT1M --aggregation Maximum \
      --query "value[].{metric:name.value, points:timeseries[0].data[?maximum!=null].[timeStamp,maximum]}" -o json | head -400
  fi
  section "PostgreSQL activity log $SINCE .. $UNTIL (platform events: restarts, failovers, maintenance)"
  if [ -n "$sid" ]; then
    try az monitor activity-log list --resource-id "$sid" --start-time "$SINCE" --end-time "$UNTIL" \
      --query "[].{time:eventTimestamp, op:operationName.localizedValue, status:status.localizedValue, sub:subStatus.localizedValue}" -o table
  fi
  section "PostgreSQL server log files (last 24 h) and excerpts in the window"
  files="$(az postgres flexible-server server-logs list -g "$RG" -s "$server" --file-last-written 24 --query "[].name" -o tsv 2>&1)" || { echo "(server-logs list failed: ${files:-no text}; logfiles.download_enable is off unless the error says otherwise)"; files=""; }
  if [ -z "$files" ]; then
    echo "(no downloadable server log files in the last 24 h)"
  else
    tmp="$(mktemp -d)"
    ( cd "$tmp" && for f in $files; do
        az postgres flexible-server server-logs download -g "$RG" -s "$server" -n "$f" -o none 2>&1 || echo "(download of $f failed)"
      done )
    # Severity lines and slow statements only; DETAIL/parameter lines are dropped so no row values are printed. Postgres
    # log lines start "YYYY-MM-DD HH:MM:SS.mmm UTC"; the window bounds are ISO "YYYY-MM-DDTHH:MM", so the key is rebuilt as such.
    find "$tmp" -type f | while read -r lf; do
      echo "-- $(basename "$lf")"
      grep -E 'FATAL|PANIC|terminat|out of memory|Killed|database system|server process|checkpoint|could not|duration: [0-9]{5,}' "$lf" \
        | grep -v -E 'DETAIL|parameters:' | awk -v s="${SINCE:0:16}" -v u="${UNTIL:0:16}" '{ t=$1 "T" substr($2,1,5); if (t >= s && t <= u) print $0 }' | head -80 | show 200
    done
    rm -rf "$tmp"
  fi
fi

section "Log Analytics: console log of job executions starting with $JOB_PREFIX in the window"
ws="$(az monitor log-analytics workspace list -g "$RG" --query "[0].customerId" -o tsv 2>/dev/null || true)"
if [ -z "$ws" ]; then echo "(no Log Analytics workspace in $RG)"; else
  q="union isfuzzy=true ContainerAppConsoleLogs_CL, ContainerAppConsoleLogs
     | where TimeGenerated between (datetime('$SINCE') .. datetime('$UNTIL'))
     | extend exec = coalesce(column_ifexists('ContainerGroupName_s', ''), column_ifexists('ContainerGroupName', '')),
              line = coalesce(column_ifexists('Log_s', ''), column_ifexists('Log', ''))
     | where exec startswith '$JOB_PREFIX' | order by TimeGenerated asc | project TimeGenerated, exec, line = replace_regex(line, @'[\r\n\t]+', ' ') | take 400"
  try az monitor log-analytics query -w "$ws" --analytics-query "$q" --query "[].[TimeGenerated, exec, line]" -o tsv | show 400
  section "Log Analytics: system log (platform reasons) for $JOB_PREFIX and $WORKER_APP in the window"
  q="union isfuzzy=true ContainerAppSystemLogs_CL, ContainerAppSystemLogs
     | where TimeGenerated between (datetime('$SINCE') .. datetime('$UNTIL'))
     | where * has '$JOB_PREFIX' or * has '$WORKER_APP'
     | extend line = strcat(coalesce(column_ifexists('Reason_s', ''), column_ifexists('Reason', '')), ': ',
                            coalesce(column_ifexists('Log_s', ''), column_ifexists('Log', '')))
     | order by TimeGenerated asc | project TimeGenerated, line = replace_regex(line, @'[\r\n\t]+', ' ') | take 200"
  try az monitor log-analytics query -w "$ws" --analytics-query "$q" --query "[].[TimeGenerated, line]" -o tsv | show 300
  section "Log Analytics: PostgreSQL server log (AzureDiagnostics, PostgreSQLLogs) in the window, severity lines only"
  # Server-side reasons for a lost session (terminated, reset, out of memory, crash recovery): ERROR/FATAL/PANIC lines and the
  # LOG lines that name such a reason. Slow-statement (duration), STATEMENT, CONTEXT, QUERY, DETAIL and parameter records are
  # dropped, so no SQL text or bind value is printed; an ERROR line is kept as the server wrote it, and a disconnection line
  # names the login role, database and client host (not secrets). The anchored exclusion on -LOG: execute/statement/parse/bind
  # (the prefixes log_statement and log_min_duration_statement produce) keeps SQL text out even if log_statement is ever turned on.
  q="AzureDiagnostics
     | where TimeGenerated between (datetime('$SINCE') .. datetime('$UNTIL'))
     | where ResourceProvider == 'MICROSOFT.DBFORPOSTGRESQL' and Category == 'PostgreSQLLogs'
     | extend msg = tostring(coalesce(column_ifexists('Message', ''), column_ifexists('message_s', '')))
     | where msg matches regex '-(ERROR|FATAL|PANIC):' or (msg matches regex '-LOG:' and msg matches regex '(?i)terminat|out of memory|database system|server process|checkpoint|could not|disconnection|reset by peer')
     | where not(msg matches regex @'-LOG:\s+(execute|statement|parse|bind)')
     | where msg !contains 'duration:' and msg !contains 'statement:' and msg !contains 'CONTEXT:' and msg !contains 'QUERY:' and msg !contains 'DETAIL:' and msg !contains 'parameters:'
     | order by TimeGenerated asc | project TimeGenerated, msg = replace_regex(msg, @'[\r\n\t]+', ' ') | take 300"
  try az monitor log-analytics query -w "$ws" --analytics-query "$q" --query "[].[TimeGenerated, msg]" -o tsv | show 300
fi

section "Log Analytics: console log of $WORKER_APP in the window (severity and job-run lines only, 200 chars each)"
# The worker's scheduled jobs (bundle pre-generation at 16:00 UTC, outlet-request lapse, aggregation, geo re-check, retention)
# log one line per run. The console is one JSON object per line (logback), so the query projects level, logger and
# formattedMessage (cut at 200 characters) and not the raw line. Lines naming SQL, parameters, keys or secrets are dropped, but a
# driver message can still carry a value: the output is for operators and the repository is public, so read it before
# pasting it anywhere (D-OPS-DIAGNOSE-WORKER-01). The newest 200 matching lines are kept.
if [ -n "${ws:-}" ]; then
  q="union isfuzzy=true ContainerAppConsoleLogs_CL, ContainerAppConsoleLogs
     | where TimeGenerated between (datetime('$SINCE') .. datetime('$UNTIL'))
     | extend app = coalesce(column_ifexists('ContainerAppName_s', ''), column_ifexists('ContainerAppName', '')),
              line = tostring(coalesce(column_ifexists('Log_s', ''), column_ifexists('Log', '')))
     | where app == '$WORKER_APP'
     | where line matches regex @'(?i)\b(WARN|ERROR|FATAL)\b|\"level\":\"(WARN|ERROR)' or line matches regex @'(?i)bundle|lapsed|worker started|PAUSED|aggregat|geo re-check|retention|risk signal|route-day'
     | where not(line matches regex @'(?i)statement|parameters|bind|password|secret|token|authorization|bearer|key=|sig=|jdbc:')
     | extend j = parse_json(line)
     | extend isobj = gettype(j) == 'dictionary'
     | extend fm = coalesce(nullif(tostring(j.formattedMessage), ''), nullif(tostring(j.message), ''), '(no message)')
     | extend text = iff(isobj, strcat(tostring(j.level), ' ', tostring(j.loggerName), ' ', fm), line)
     | top 200 by TimeGenerated desc | order by TimeGenerated asc
     | project TimeGenerated, line = replace_regex(text, @'[\r\n\t]+', ' ')"
  try az monitor log-analytics query -w "$ws" --analytics-query "$q" --query "[].[TimeGenerated, line]" -o tsv | show 260
else
  echo "(no Log Analytics workspace, so no worker console log)"
fi

section "Log Analytics: console log of $API_APP in the window (ERROR/WARN and start-up lines, newest first, 240 chars each)"
# Same query shape as the worker, with three differences that matter (review of D-OPS-DIAGNOSE-API-01):
#  - the secret-word exclusion is applied to the PROJECTED text that is printed (level + logger + formattedMessage), never to the raw JSON line, whose stack
#    trace names org.jdbi.v3.core.statement / PgStatement and would drop the API's most useful ERROR lines without a trace; the exclusion is narrow (password,
#    secret, authorization, bearer, key=/sig=/token=, jdbc:, connection string, "statement:"/"parameters:"/DETAIL: SQL echo) and the COUNT of dropped lines is printed;
#  - the include words are matched on logger + message, and the request logger (aron.request) is excluded, so request lines cannot push start-up lines and
#    errors out of the cap;
#  - the hold-back and its count are done in KQL (two queries), the whole message is redacted and only then cut. The newest 150 matching lines are kept.
if [ -n "${ws:-}" ]; then
  # The hold-back is done in KQL, never on the az output: az prints a KQL bool as Python "True"/"False", and a message with a tab or a newline would break any
  # column split in the shell. One row = one line (newlines and tabs are folded in the query); the whole message is fetched (no substring before redaction).
  base="union isfuzzy=true ContainerAppConsoleLogs_CL, ContainerAppConsoleLogs
     | where TimeGenerated between (datetime('$SINCE') .. datetime('$UNTIL'))
     | extend app = coalesce(column_ifexists('ContainerAppName_s', ''), column_ifexists('ContainerAppName', '')),
              line = tostring(coalesce(column_ifexists('Log_s', ''), column_ifexists('Log', '')))
     | where app == '$API_APP'
     | extend j = parse_json(line)
     | extend isobj = gettype(j) == 'dictionary'
     | extend lg = iff(isobj, tostring(j.loggerName), ''), lvl = iff(isobj, tostring(j.level), '')
     | where not(lg startswith 'aron.request')
     | extend fm = coalesce(nullif(tostring(j.formattedMessage), ''), nullif(tostring(j.message), ''), '(no message)')
     | extend text = replace_regex(iff(isobj, strcat(lvl, ' ', lg, ' ', fm), line), @'[\r\n\t]+', ' ')
     | where lvl in ('WARN', 'ERROR', 'FATAL') or (not(isobj) and line matches regex @'(?i)\b(WARN|ERROR|FATAL)\b')
         or text matches regex @'(?i)started|starting|listening|responding|migrat|pre-?flight|waiting-item sweep|ARON_|worker'
     | extend held = text matches regex @'(?i)password|secret|authorization|bearer|\bkey=|sig=|token[=:]|jdbc:|connection ?string|statement:|parameters:|DETAIL:'"
  q="$base | where not(held) | top 150 by TimeGenerated desc | project TimeGenerated, text"
  try az monitor log-analytics query -w "$ws" --analytics-query "$q" --query "[].[TimeGenerated, text]" -o tsv | show 240
  qc="$base | summarize held = countif(held), shown = countif(not(held))"
  az monitor log-analytics query -w "$ws" --analytics-query "$qc" --query "[0].[held, shown]" -o tsv 2>/dev/null | held_note
  section "Log Analytics: system log of $API_APP in the window (platform reasons: probes, restarts, scaling), newest first"
  q="union isfuzzy=true ContainerAppSystemLogs_CL, ContainerAppSystemLogs
     | where TimeGenerated between (datetime('$SINCE') .. datetime('$UNTIL'))
     | extend app = coalesce(column_ifexists('ContainerAppName_s', ''), column_ifexists('ContainerAppName', ''))
     | where app == '$API_APP'
     | extend line = strcat(coalesce(column_ifexists('Reason_s', ''), column_ifexists('Reason', '')), ': ',
                            coalesce(column_ifexists('Log_s', ''), column_ifexists('Log', '')))
     | top 150 by TimeGenerated desc
     | project TimeGenerated, line = replace_regex(line, @'[\r\n\t]+', ' ')"
  try az monitor log-analytics query -w "$ws" --analytics-query "$q" --query "[].[TimeGenerated, line]" -o tsv | show 300
  section "Log Analytics: migrate job pre-flight lines ($JOB_PREFIX*) in the window"
  # MigrationPreflight reports one line per action and, when it has nothing to do, "pre-flight: nothing to do (checks: ...)". The query prints the
  # projected message (formattedMessage of a JSON line, else the plain line), never the raw JSON with a stack trace.
  # Absent here means the pre-flight did not run (an older image) or the job did not start in the window.
  q="union isfuzzy=true ContainerAppConsoleLogs_CL, ContainerAppConsoleLogs
     | where TimeGenerated between (datetime('$SINCE') .. datetime('$UNTIL'))
     | extend exec = coalesce(column_ifexists('ContainerGroupName_s', ''), column_ifexists('ContainerGroupName', '')),
              line = tostring(coalesce(column_ifexists('Log_s', ''), column_ifexists('Log', '')))
     | where exec startswith '$JOB_PREFIX'
     | extend j = parse_json(line)
     | extend isobj = gettype(j) == 'dictionary'
     | extend msg = replace_regex(iff(isobj, coalesce(nullif(tostring(j.formattedMessage), ''), nullif(tostring(j.message), ''), '(no message)'), line), @'[\r\n\t]+', ' ')
     | where msg matches regex @'(?i)pre-?flight'
     | top 100 by TimeGenerated desc | order by TimeGenerated asc
     | project TimeGenerated, exec, msg"
  out="$(az monitor log-analytics query -w "$ws" --analytics-query "$q" --query "[].[TimeGenerated, exec, msg]" -o tsv 2>&1 | show 400)"
  if [ -z "$out" ]; then echo "(no pre-flight line from a $JOB_PREFIX execution in the window)"; else printf '%s\n' "$out"; fi
else
  echo "(no Log Analytics workspace, so no API console log)"
fi

section "worker app $WORKER_APP"
try az containerapp show -g "$RG" -n "$WORKER_APP" --query '{running:properties.runningStatus, provisioning:properties.provisioningState, latest:properties.latestRevisionName, latestReady:properties.latestReadyRevisionName, mode:properties.configuration.activeRevisionsMode}' -o json
try az containerapp revision list -g "$RG" -n "$WORKER_APP" --query "[?properties.active].{name:name, replicas:properties.replicas, running:properties.runningState, created:properties.createdTime}" -o table
echo
echo "diagnose: done (read-only)"
