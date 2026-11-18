#!/usr/bin/env bash
# Read-only diagnostics of the dev rehearsal environment (D-OPS-DIAGNOSE-01), run by .github/workflows/ops-diagnose.yml
# with the deploy identity: the PostgreSQL server (state, metrics, parameters, server-log excerpts when downloadable,
# activity log, diagnostic settings), the PostgreSQL server log lines streamed to Log Analytics, the migrate job's and the
# worker's and the API's console and system logs from Log Analytics, the migrate job's pre-flight lines, and the worker app's state. Everything printed is metadata, metrics
# or log excerpts; never a secret, connection string or Key Vault value.
# This script changes nothing in Azure: it only lists, shows, queries and downloads logs.
#
# Usage: AZURE_RESOURCE_GROUP=rg-aron-dev infra/scripts/diagnose.sh [--include-messages] <since-utc> <until-utc> [job-prefix] [worker-app] [api-app]
# --include-messages (PRIVATE TERMINAL ONLY): also print log message text behind the redactor and a strict line check. The script REFUSES it when GITHUB_ACTIONS is set:
# the workflow has no such input, because names, OTPs and free text cannot be reliably pattern-matched out of a message, and the job log of this repository is public.
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
use strict; use warnings; no warnings 'utf8'; use utf8;   # the source holds Bangla words: character semantics
use Encode qw(decode encode FB_DEFAULT);
use Digest::SHA qw(sha256_hex);
$| = 1; binmode(STDIN); binmode(STDOUT);
my $W = (defined $ENV{REDACT_WIDTH} && $ENV{REDACT_WIDTH} =~ /^[0-9]+$/) ? $ENV{REDACT_WIDTH} + 0 : 0;
my $SALT = (defined $ENV{REDACT_SALT} && length $ENV{REDACT_SALT}) ? $ENV{REDACT_SALT} : sprintf("%08x%08x", int(rand(4294967296)), int(rand(4294967296)));
my $STRICT = (defined $ENV{REDACT_STRICT} && $ENV{REDACT_STRICT} eq "1");   # message mode (include_messages=true): a line that still holds a key-like word and a mixed letters+digits value is replaced whole
my $DIE_ON = $ENV{REDACT_SELFTEST_DIE};   # test seam only: a line holding this text makes the scrubber fail, to prove the fail-closed notice
my $SEP = qr/[\s().\-_\/\[\]]/;
# A value: an escaped-quoted string (raw JSON inside a JSON console line), a quoted string, an unclosed quote (to the end of the line), a nested object or array, or a bare run
# that also takes ; and , (so the tail of a ;- or ,-separated secret goes too).
my $VAL = qr/\\"(?:(?!\\").){0,500}\\"|"(?:[^"\\]|\\.){0,500}"|'[^']{0,500}'|\\"[^\n]*|["'][^\n]*|\{(?:[^{}]|\{[^{}]*\}){0,500}\}|\[[^\]]{0,500}\]|[^"\s}]+/;
my $QE = qr/(?:\\?["'])?/;   # the closing quote of a key, escaped or not
# The key patterns START AT THE STEM (no variable-length prefix): a prefix before the stem is quadratic on an unbroken run of letters and digits, and it is not needed, because
# only the VALUE is replaced and the text before the stem stays as it was. Suffixes after the stem are bounded.
my $KEYLONG = qr/(?:token|secret|password|passwd|passcode|credential|authorization|apikey|api_key|accesskey|access_key|accountkey|account_key|privatekey|private_key|instrumentationkey|sessionid|session_id|cookie|connection_?string|hmac|signature|nonce|salt|(?:sms|verif(?:y|ication)|otp|auth|login|reset|confirm(?:ation)?|security)_?code)[A-Za-z0-9_.\-]{0,128}/i;
my $KEYSHORT = qr/(?<![A-Za-z0-9])(?:pass|pwd|pin|otp|key|sig|sas|session|auth)(?:[_.\-][A-Za-z0-9]{1,128}){0,8}/i;
my $KEYCAMEL = qr/(?<=[a-z0-9])(?:Key|Pin|Otp|Pass|Sig|Sas|Session)[A-Za-z0-9]{0,128}|(?<![A-Za-z0-9])(?:key|pin|otp|pass|sig|sas|session|auth)[A-Z][A-Za-z0-9]{0,128}/;
my $SEPK_T = qr/\s{0,40}(?:[=:]|\t)\s{0,40}/;   # between a key and its value: = or : or a tab (a config-style line "password<TAB>value"), with up to 40 spaces or tabs around
my $SEPK_N = qr/[ \t]{0,40}[=:][ \t]{0,40}/;     # on a TSV row (two or more tabs) a tab alone is a COLUMN separator, never a key separator; tabs AROUND an explicit = or : still count
my $KEYTEXT = qr/(?:(?:token|secret|password|passwd|passcode|credential|apikey|api_key|accesskey|access_key|accountkey|account_key|privatekey|private_key|instrumentationkey|sessionid|session_id|connection_?string|hmac|signature|nonce|salt)|(?<![A-Za-z0-9])(?:pass|pwd|pin|otp|key|sig|sas|session|auth)(?![A-Za-z0-9]))/i;
my $KEYNAME = qr/(?:user_?name|phone|mobile|msisdn|e?mail|full_?name|display_?name|name|outlet_?name|address|device_?id|imei|ip|remote_?addr|client_?ip)/i;
my $UNIT = qr/(?!\s*(?:ms|s|sec|secs|seconds|bytes|kb|mb|gb|%|rows|items|attempts|times|retries)\b)/i;
my ($blk_on, $blk_ind, $blk_first) = (0, 0, 0);   # a YAML block value on the line after a secret key that ends its line
my $REGISTRY = qr/(?:[a-z0-9]{3,50}\.azurecr\.io|ghcr\.io|docker\.io|index\.docker\.io|registry-1\.docker\.io|mcr\.microsoft\.com)(?::[0-9]{1,5})?/;
# STRICT mode only: does the line still hold "<key-like word><separator or space><value of 6+ characters with letters AND digits>"? Timestamps, durations, versions and long hex are exempt.
sub suspect {
  my ($t) = @_;
  while ($t =~ /(?<![\[\w.\-])[A-Za-z_][\w.\-]{1,40}[\s=:]{1,40}(?=([A-Za-z0-9+\/_\-]{6,}))/g) {   # the value is a lookahead: it is not consumed, so it can be the next key's word
    my $v = $1;
    next unless $v =~ /[A-Za-z]/ && $v =~ /[0-9]/;
    next if $v =~ /^[0-9]{4}-[0-9]{2}-[0-9]{2}T?[0-9:.]*Z?$/ || $v =~ /^[0-9]+(?:ms|s|kb|mb|gb|b)$/i || $v =~ /^v?[0-9]+(?:\.[0-9]+)+$/ || $v =~ /^[0-9a-f]{40,64}$/ || $v =~ /^V[0-9]{3,6}(?:[-_][A-Za-z0-9_\-]*)?$/;
    return 1;
  }
  return 0;
}
my @keep;
sub hold { push @keep, $_[0]; return "\x{E000}" . $#keep . "\x{E001}"; }
sub scrub {
  my ($l) = @_;
  $l =~ tr/\x{09E6}-\x{09EF}/0-9/; $l =~ tr/\x{0660}-\x{0669}/0-9/; $l =~ tr/\x{06F0}-\x{06F9}/0-9/;
  @keep = ();
  my $SEPK = (($l =~ tr/\t//) < 2) ? $SEPK_T : $SEPK_N;
  # what the operator needs to follow a request or a release stays readable
  die "selftest" if defined $DIE_ON && length $DIE_ON && index($l, $DIE_ON) >= 0;
  $l =~ s/(?<![A-Za-z0-9_])((?:image|digest|commit|build)(?![A-Za-z0-9_])[^0-9a-fA-F\n]{0,20}(?:sha256[:=])?)([0-9a-f]{40}|[0-9a-f]{64})(?![0-9a-f])/$1 . hold($2)/gie;
  $l =~ s/((?<![A-Za-z0-9.\-])$REGISTRY\/(?:[A-Za-z0-9._\-]{1,100}\/){0,4}[A-Za-z0-9._\-]{1,100}[:@](?:sha256:)?)([0-9a-f]{40}|[0-9a-f]{64})(?![0-9a-f])/$1 . hold($2)/gie;
  $l =~ s/(?<![0-9a-f])([0-9a-f]{40})(?![0-9a-f])/hold($1)/ge if $l =~ /health/i;
  $l =~ s/\b((?:version|ver|build|release|tag|contract|image)\w{0,20}[\s"'=:v]{0,4})(\d+\.\d+\.\d+\.\d+)(?![0-9.])/$1 . hold($2)/gie;
  $l =~ s/\b((?:epoch|ts|time(?:stamp)?|exp(?:ires)?|iat|nbf|unix)\w{0,20}["'\s]{0,4}[=:]\s*)(1[0-9]{9}|1[0-9]{12})(?![0-9])/$1 . hold($2)/gie;
  # secrets
  $l =~ s/\b((?:set-)?cookie2?)\s*[:=].*$/$1: [redacted]/gi;
  $l =~ s/eyJ[A-Za-z0-9_-]{5,}(?:\.[A-Za-z0-9_-]+)*/[jwt]/g;
  # Authorization with ANY scheme (Bearer, Basic, Token, ApiKey, Digest, SharedAccessSignature...): everything after it to the end of the value (an unquoted value: to the end of the line)
  $l =~ s/(\bauthorization["']?\s{0,40}[:=]\s{0,40})(?!["'\\{\[])[^\n]*$/${1}[redacted]/gi;
  $l =~ s/\b(Bearer|Basic)[ =:]+[A-Za-z0-9._~+\/=-]+/$1 [redacted]/gi;
  $l =~ s/(\b[A-Za-z][A-Za-z0-9+.\-]{1,20}:\/\/)[^\s\/:@"'<>]{0,128}:[^\s\/@"'<>]{1,200}@/${1}[redacted]@/g;
  $l =~ s/(jdbc:|postgres(?:ql)?:\/\/|AccountKey=|SharedAccessSignature=|Endpoint=sb:|Server=|Host=|User ?Id=|Uid=)[^\s",;]+/${1}[redacted]/gi;
  # command-line forms: --password X, --token X, -p X
  $l =~ s/(--?(?:password|passwd|pwd|pass|secret|token|api-?key|access-?key|key)\s{1,40})[^\s]+/${1}[redacted]/gi;
  $l =~ s/(?<![\w\-])(-p\s{1,40})[^\s-][^\s]*/${1}[redacted]/g;
  # XML / HTML element: <password>X</password>
  $l =~ s/(<([A-Za-z0-9_.\-]{0,64}(?:password|passwd|secret|token|apikey|credential|nonce|salt|signature)[A-Za-z0-9_.\-]{0,64})(?:\s[^>]{0,200})?>)[^<]{0,500}(<\/\2>)/${1}[redacted]${3}/gi;
  # URL-encoded separators: key%3Dvalue, key%22%3A%22value
  $l =~ s/($KEYTEXT(?:%22)?(?:%3D|%3A|%22%3A%22|%22%3A))(?:%22)?[^&\s"%]{1,300}/${1}[redacted]/gi;
  # password-like keys also take up to 3 following plain words when the value was unquoted (a password may hold a space); over-redaction, on purpose
  $l =~ s/((?:password|passwd|passcode|secret|pwd)[A-Za-z0-9_.\-]{0,128}${QE}$SEPK)([^"'\\\s{\[][^"\s}]*(?:[ \t]+(?![A-Za-z_][\w.\-]*\s{0,3}[=:])[^\s"]+){0,3})/${1}[redacted]/gi;
  $l =~ s/($KEYLONG${QE}$SEPK)$VAL/${1}[redacted]/g;
  $l =~ s/($KEYSHORT${QE}$SEPK)$VAL/${1}[redacted]/g;
  $l =~ s/($KEYCAMEL${QE}$SEPK)$VAL/${1}[redacted]/g;
  $l =~ s/\b($KEYNAME${QE}$SEPK)$VAL/${1}[redacted]/g;
  $l =~ s/\b(code${QE}\s{0,40}[=:]\s{0,40}(?:\\?["'])?)[0-9]{4,8}\b$UNIT/${1}[redacted]/gi;
  $l =~ s/\b(password|passwd|passcode|pin|otp|secret|token|key)\s+(?:is|was|=|:)\s+(?:"[^"]*"|'[^']*'|<[^>]*>|&lt;.*?&gt;|\S+)/$1 [redacted]/gi;
  $l =~ s/(?<![\w])(otp|one[- ]time (?:code|password)|verification code|passcode|ওটিপি|কোড|পিন|ভেরিফিকেশন|যাচাই)(?![\w])[^0-9\n]{0,60}[0-9]{4,8}\b$UNIT/$1 [redacted]/gi;
  $l =~ s/\b(code\b[^0-9\n]{0,10})(?<![0-9:.\/\-])[0-9]{4,8}(?![0-9:.\/\-])$UNIT/${1}[code]/gi;
  # personal data and identifiers
  $l =~ s/(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,128}@[A-Za-z0-9.-]{1,255}\.[A-Za-z]{2,}/[email]/g;
  $l =~ s/\b([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\b/"[uuid:" . substr(sha256_hex($SALT . lc($1 =~ tr|-||dr)), 0, 8) . "]"/gie;
  $l =~ s/(?<![0-9a-f])([0-9a-f]{32})(?![0-9a-f])/"[uuid:" . substr(sha256_hex($SALT . lc $1), 0, 8) . "]"/gie;
  $l =~ s/(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}(?![0-9a-f:])/[ip]/gi;
  $l =~ s/(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){1,7}:(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?(?![0-9a-f:])/[ip]/gi;
  $l =~ s/(?<![0-9.])([0-9]{1,3}(?:\.[0-9]{1,3}){3})(?![0-9.])/do { my $m = $1; ($m eq "0.0.0.0" || $m =~ m|^127\.|) ? $m : "[ip]" }/ge;
  $l =~ s/(?<![\w])((?:otp|pin|passcode|one[- ]time|verification|sms|ওটিপি|কোড|পিন|ভেরিফিকেশন|যাচাই)(?: code| password)?(?![\w])[^0-9\n]{0,60})(?<![0-9:.\/\-])[0-9]{4,8}(?![0-9:.\/\-])$UNIT/$1\[code]/gi;
  $l =~ s/(?<![0-9:.\/\-])[0-9]{4,8}(?![0-9:.\/\-])$UNIT([^0-9\n]{0,60}(?<![\w])(?:otp|pin|passcode|one[- ]time|verification|sms|ওটিপি|কোড|পিন|ভেরিফিকেশন|যাচাই)(?![\w]))/[code]$1/gi;
  $l =~ s/((?<![0-9])(?:\+\s{0,6})?(?:[(\[]?88[)\]]?${SEP}{0,6})?(?:[(\[]?0[)\]]?${SEP}{0,6})?1${SEP}{0,6}[3-9](?:${SEP}{0,6}[0-9]){8}(?![0-9]))/do { my $m = $1; ($m =~ m{(?:19|20)[0-9]{2}[\-\/.][0-9]{2}[\-\/.][0-9]{2}}) ? $m : "[phone]" }/ge;
  $l =~ s/[A-Za-z0-9+\/_-]{40,}={0,2}/[long-token]/g;
  $l =~ s/\x{E000}(\d+)\x{E001}/$keep[$1]/g;
  return $l;
}
while (my $raw = <STDIN>) {
  my $nl = ($raw =~ s/\r?\n\z//) ? "\n" : "";
  my $l = decode('UTF-8', $raw, FB_DEFAULT);
  my $o;
  my $il = ($l =~ /^([ \t]*)/) ? length($1) : 0;
  if ($blk_on && $l !~ /^\s*$/) {
    if ($blk_first || $il > $blk_ind) { $blk_first = 0; $o = "[redacted-line]"; } else { $blk_on = 0; }
  }
  unless (defined $o) {
    $o = eval { scrub($l) };
    $o = "[line withheld: redaction filter error]" unless defined $o;
    $o = "[redacted-line]" if $STRICT && eval { suspect($o) };
    if (!$blk_on && eval { $l =~ /(?:$KEYLONG|$KEYSHORT)(?:\\?["'])?\s{0,40}:\s{0,40}(?:[|>][+-]?[0-9]?)?\s*$/ }) { ($blk_on, $blk_ind, $blk_first) = (1, $il, 1); }
  }
  $o = substr($o, 0, $W) if $W && length($o) > $W;
  print encode('UTF-8', $o), $nl;
}
PERL
# One random salt per run for the UUID tag: the same id matches across lines of THIS run, but cannot be followed across runs in public logs.
REDACT_SALT="${REDACT_SALT:-$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')}"; export REDACT_SALT
redact() { perl -e "$REDACT_PL"; }
# show <width>: redact the whole text first, then cut to the width in characters (inside the same perl program).
show() { REDACT_WIDTH="$1" perl -e "$REDACT_PL"; }
# The ALLOW-LIST of built-in PostgreSQL message heads (D-OPS-DIAGNOSE-API-09/-10; the lead's ruling, replacing the open "letters before the first colon" rule that printed free text such as
# "Rahim Uddin of Gulshan"). RULE FOR THE WHOLE LIST, enforced mechanically by a test: every alternative is a LITERAL phrase (no character class, no quantifier, no digit pattern; only the
# alternation bar and non-capturing groups of literals), so the printed head is always exactly the matched literal and never a word of the record. A message that continues in words the list does not
# know prints the bare phrase (for example "terminating connection"), never the rest. The longer, more specific phrases come first (the first match wins). The same list is used by the KQL head and
# by pglog_head. Phrases only from the PostgreSQL server source; no phrase carries a name, a table or a value. The one message whose real shape has a number in it ("server process (PID N) was
# terminated by signal N") is a separate shape rule with a FIXED label and no digits (SIGNAL_LABEL).
# (The "concurrent d-e-l-e-t-e" serialization reason is not a separate literal: INT's read-only guard test (check_infra.py, ops_diagnose_is_read_only) refuses that verb anywhere in the script's code lines, and
# the bare phrase "could not serialize access" already covers it without printing the reason.)
PG_HEADS='checkpoint starting|checkpoint complete|checkpoints are occurring too frequently|database system is ready to accept read-only connections|database system is ready to accept connections|the database system is starting up|the database system is shutting down|the database system is in recovery mode|database system is shut down|database system was shut down|database system was interrupted|database system was not properly shut down|terminating connection due to administrator command|terminating connection due to conflict with recovery|terminating connection due to unexpected postmaster exit|terminating connection due to crash of another server process|terminating connection due to idle-in-transaction timeout|terminating connection due to idle-session timeout|terminating connection due to transaction timeout|terminating connection|terminating autovacuum process due to administrator command|terminating autovacuum process|terminating walsender process due to replication timeout|terminating background worker|could not receive data from client|could not send data to client|could not connect to server|could not extend file|could not write to file|could not read block|could not fsync file|could not truncate file|could not open file|could not create lock file|could not create file|could not serialize access due to read/write dependencies among transactions|could not serialize access due to concurrent update|could not serialize access|connection received|connection authorized|connection authenticated|disconnection|deadlock detected|canceling statement due to statement timeout|canceling statement due to lock timeout|canceling statement due to user request|canceling statement due to conflict with recovery|canceling statement|canceling autovacuum task|out of memory|server process|recovery restart point|recovery has paused|archive recovery complete|starting archive recovery|automatic recovery in progress|redo starts|redo done|received fast shutdown request|received smart shutdown request|received immediate shutdown request|shutting down|aborting any active transactions|archive command failed|sorry, too many clients already|remaining connection slots are reserved|password authentication failed|duplicate key value violates unique constraint|autovacuum launcher started|autovacuum launcher shutting down'
export PG_HEADS
SIGNAL_LABEL='server process was terminated by signal'
# pglog_head: a downloaded PostgreSQL server-log record -> "<time> UTC <severity> <head>", ONLY for LOG, ERROR, FATAL and PANIC records. The head is a literal allow-listed phrase, else [text]; a slow-statement
# record keeps only its duration figure ("duration 123456.789 ms"), never the statement or any value after it. Every record is cut to 400 characters BEFORE any matching (only the head is used, and a
# 100 KB whitespace run must stay linear). Bytes are decoded leniently (invalid UTF-8 becomes U+FFFD, no warning). A record that does not have the expected log_line_prefix shape (time, UTC, session id,
# severity) is not printed; the number of such records is counted.
pglog_head() {
  SIGNAL_LABEL="$SIGNAL_LABEL" perl -ne '
    BEGIN { use Encode qw(decode encode FB_DEFAULT); no warnings; binmode(STDIN); binmode(STDOUT); our $dropped = 0; our $heads = qr/(?:$ENV{PG_HEADS})/; }
    chomp; my $l = decode("UTF-8", $_, FB_DEFAULT); $l = substr($l, 0, 400);
    unless ($l =~ /^([0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:.]{8,12}) ?UTC\S{0,40}?-([A-Z]{3,9}):[ \t]{0,10}(.*)$/) { $dropped++; next }
    my ($t, $sev, $msg) = ($1, $2, $3);
    next unless $sev =~ /^(?:LOG|ERROR|FATAL|PANIC)$/;
    my $out;
    if ($msg =~ /^duration: ([0-9]+(?:\.[0-9]+)?) ms/) { $out = "$t UTC $sev duration $1 ms" }
    elsif ($msg =~ /^server process \(PID [0-9]+\) was terminated by signal [0-9]+/) { $out = "$t UTC $sev $ENV{SIGNAL_LABEL}" }
    else { my $head = ($msg =~ /^($heads)/) ? $1 : "[text]"; $out = "$t UTC $sev $head" }
    print encode("UTF-8", $out), "\n";
    END { print "($dropped record(s) dropped: unknown log line prefix shape)\n" if $dropped; }'
}
# held_note: stdin = the two numbers (held, shown) of the count query; prints the line that says how many lines the secret-word exclusion held back.
held_note() {
  local nums a b; nums="$(tr -s '[:space:],' ' ' | sed 's/^ //')"; a="${nums%% *}"; b="${nums#* }"; b="${b%% *}"
  if [[ "$a" =~ ^[0-9]+$ && "$b" =~ ^[0-9]+$ ]]; then echo "($a line(s) held back by the secret-word exclusion, $b not held; the list above is capped at the newest 150)"; else echo "(held-back count unavailable)"; fi
}

# Message mode is an explicit flag, for a private terminal only; it is refused under GitHub Actions (a public job log).
INCLUDE_MESSAGES=false; _args=()
for _x in "$@"; do if [ "$_x" = "--include-messages" ]; then INCLUDE_MESSAGES=true; else _args+=("$_x"); fi; done
set -- "${_args[@]+"${_args[@]}"}"
if [ "$INCLUDE_MESSAGES" = true ] && [ -n "${GITHUB_ACTIONS:-}" ]; then
  echo "diagnose: --include-messages is refused under GitHub Actions (the job log of a public repository must not carry message text); run it from a private terminal" >&2; exit 2
fi

# Test seams (infra/tests/check_infra.py): diagnose.sh --redact [width] and --held-note filter stdin, no Azure, no arguments.
if [ "${1:-}" = "--redact" ]; then if [ -n "${2:-}" ]; then show "$2"; else redact; fi; exit 0; fi
if [ "${1:-}" = "--held-note" ]; then held_note; exit 0; fi

RG="${AZURE_RESOURCE_GROUP:?set AZURE_RESOURCE_GROUP}"
SINCE="${1:?since (UTC, e.g. 2026-11-05T09:40:00Z)}"
UNTIL="${2:?until (UTC)}"
JOB_PREFIX="${3:-caj-aron-dev-migrate}"
WORKER_APP="${4:-ca-aron-dev-worker}"
API_APP="${5:-ca-aron-dev-api}"
# DEFAULT-DENY FOR FREE TEXT (D-OPS-DIAGNOSE-API-06). The job log of this public repository must not carry free text: the redactor below is a net, not the control.
# The control is the queries: unless the private-terminal flag --include-messages is given, every query projects only NAMED STRUCTURED COLUMNS (time, level, logger, exception and cause class,
# SQLSTATE, duration in ms, a fixed label for the known start-up, sweep, pre-flight, Flyway and job-run lines, the platform reason) and never the message or
# exception text. With --include-messages (never in GitHub Actions) the message is added, passes the redactor in STRICT mode (a line that still holds a key-like word followed by a value of
# 6+ characters with letters and digits is replaced whole by [redacted-line]) and the secret-word exclusion; messages are OFF by default in a public log.
# Every input is checked against a fixed pattern BEFORE it enters a query or an az argument (nothing here is ever evaluated, but a quote in a value would
# change a KQL query). The value itself is not echoed back.
valid() { [[ "$2" =~ $3 ]] || { echo "diagnose: invalid $1 (does not match its fixed pattern)" >&2; exit 2; }; }
valid resource-group "$RG" '^[A-Za-z0-9._()-]{1,90}$'
valid since "$SINCE" '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$'
valid until "$UNTIL" '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$'
valid job-prefix "$JOB_PREFIX" '^[a-z0-9][a-z0-9-]{0,62}$'
valid worker-app "$WORKER_APP" '^[a-z0-9][a-z0-9-]{0,62}$'
valid api-app "$API_APP" '^[a-z0-9][a-z0-9-]{0,62}$'
# showm: the pipe for anything that carries message text; strict only in message mode.
showm() { if [ "$INCLUDE_MESSAGES" = true ]; then REDACT_STRICT=1 show "$1"; else show "$1"; fi; }
# Everything below, including text this script prints itself, passes the net once more on the way out (the per-query redact-then-cut above is what
# protects against a cut inside a value; this protects every section, the older ones too).
exec > >(redact)
# A FAILED SECTION MUST NOT LOOK LIKE SUCCESS (live run 44341474466: four sections failed and the job was green). Every failed az call or query appends a line to FAILFILE (a file, so a failure inside a
# pipeline or a subshell is counted too); the script prints "diagnose: N section(s) FAILED" before its final line and exits non-zero. Only the section title, an error CODE and a parser position are
# printed for a failure: never the query text, never the platform's message.
SECTION="start"; FAILFILE="$(mktemp)"
section() { SECTION="$*"; printf '\n== %s\n' "$*"; }
note_failure() { echo x >> "$FAILFILE"; }
try() { "$@" 2>/dev/null; local rc=$?; if [ "$rc" -ne 0 ]; then note_failure; echo "(command failed: ${SECTION}, exit code $rc)"; fi; return 0; }

echo "diagnose: include_messages=$INCLUDE_MESSAGES (message and exception text is withheld; message mode exists only as the private-terminal flag --include-messages)"
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
  if [ -n "$sid" ]; then try az monitor diagnostic-settings list --resource "$sid" --query "(value || @)[].logs[].[category || categoryGroup, enabled]" -o tsv | head -60; fi
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
  files="$(az postgres flexible-server server-logs list -g "$RG" -s "$server" --file-last-written 24 --query "[].name" -o tsv 2>/dev/null)"; rc=$?
  if [ "$rc" -ne 0 ]; then note_failure; echo "(server-logs list failed, exit code $rc; logfiles.download_enable may be off)"; files=""; fi
  if [ -z "$files" ]; then
    echo "(no downloadable server log files in the last 24 h)"
  else
    tmp="$(mktemp -d)"
    ( cd "$tmp" && for f in $files; do
        # a file name comes from the platform: only a plain name is used
        [[ "$f" =~ ^[A-Za-z0-9._-]{1,200}$ ]] || { echo "(a log file with an unexpected name was skipped)"; continue; }
        az postgres flexible-server server-logs download -g "$RG" -s "$server" -n "$f" -o none >/dev/null 2>&1; rc=$?
        [ "$rc" -eq 0 ] || { echo x >> "$FAILFILE"; echo "(download of a log file failed, exit code $rc)"; }
      done )
    # Severity lines and slow statements only; DETAIL/parameter lines are dropped so no row values are printed. Postgres
    # log lines start "YYYY-MM-DD HH:MM:SS.mmm UTC"; the window bounds are ISO "YYYY-MM-DDTHH:MM", so the key is rebuilt as such.
    find "$tmp" -type f | while read -r lf; do
      echo "-- $(basename "$lf")"
      grep -E 'FATAL|PANIC|terminat|out of memory|Killed|database system|server process|checkpoint|could not|duration: [0-9]{5,}' "$lf" \
        | grep -v -E 'DETAIL|parameters:' | awk -v s="${SINCE:0:16}" -v u="${UNTIL:0:16}" '{ t=$1 "T" substr($2,1,5); if (t >= s && t <= u) print $0 }' | head -80 \
        | if [ "$INCLUDE_MESSAGES" = true ]; then sed -E 's/(statement:|execute [^:]*:).*/\1 [sql]/' | showm 200; else pglog_head | show 160; fi
    done
    rm -rf "$tmp"
  fi
fi

section "Log Analytics: workspace"
ws="$(az monitor log-analytics workspace list -g "$RG" --query "[0].customerId" -o tsv 2>/dev/null || true)"
if [ -z "$ws" ]; then echo "workspace: none (no Log Analytics workspace in $RG)"; else echo "workspace: found"; fi
# laq <kql> <jmespath columns>: run one query, result in LA_OUT. On failure: count it and print ONLY "(query failed: <section>, <error code>, parser position [line,column], exit code N)" (the code and the
# position are taken from the error text and shape-checked; the query text and the platform's message are never printed) and return 1.
laq() {
  local errf rc code pos; errf="$(mktemp)"
  LA_OUT="$(az monitor log-analytics query -w "$ws" --analytics-query "$1" --query "$2" -o tsv 2>"$errf")"; rc=$?
  if [ "$rc" -ne 0 ]; then
    note_failure
    code="$(grep -oE '[A-Za-z]{3,40}Error' "$errf" | head -1)"; pos="$(grep -oE 'line \[[0-9]{1,5},[0-9]{1,5}\]' "$errf" | head -1 | sed 's/^line //')"
    echo "(query failed: ${SECTION}, ${code:-no error code}${pos:+, parser position $pos}, exit code $rc)"
    rm -f "$errf"; LA_OUT=""; return 1
  fi
  rm -f "$errf"; return 0
}
# la <kql> <jmespath columns> <width>: laq, then the output goes through the redactor (and the strict line check in message mode) BEFORE the cut to the width.
la() { laq "$1" "$2" && printf '%s\n' "$LA_OUT" | showm "$3"; return 0; }

# The list of KQL reserved and special words checked against (live run 44341474466: `kind` is reserved and made four console queries fail to parse; a column that must keep such a name would need
# ['name'] quoting, none does): kind, type, hint, with, by, on, in, of, to, from, table, database, cluster, range, order, top, take, limit, count, sum, title, access, alias, set, let, pattern,
# as, and, or, not, where, extend, project, summarize, union, join, distinct, sort, print, datatable, parse, evaluate, invoke, contains, has, startswith, endswith, matches, between, asc, desc,
# first, last, bin, ago, now, view, function, scan, partition, serialize, facet, find, search, lookup, render, sample, make, reduce, regex, default. The KQL cannot be EXECUTED in CI: a static
# test (check_infra.py test_diagnose_kql_uses_no_reserved_word_as_a_column) scans every query the script sends and fails on such a word as an extend/project/summarize target or a bare column.
# One row = one log line, newlines and tabs folded in the query. A line that is a JSON object (logback) gives level, logger, message and stack trace; any other line is "plain".
# The structured columns below are the ONLY thing printed by default: the exception and cause CLASS (first token of the stack trace, "Caused by:"), SQLSTATE, a duration in ms,
# and a fixed label for the known operational lines. A JSON line with no message is "(no message)", never the raw JSON with its stack.
KQL_CONSOLE="union isfuzzy=true ContainerAppConsoleLogs_CL, ContainerAppConsoleLogs
     | where TimeGenerated between (datetime('$SINCE') .. datetime('$UNTIL'))
     | extend app = coalesce(column_ifexists('ContainerAppName_s', ''), column_ifexists('ContainerAppName', '')),
              exec = coalesce(column_ifexists('ContainerGroupName_s', ''), column_ifexists('ContainerGroupName', '')),
              line = tostring(coalesce(column_ifexists('Log_s', ''), column_ifexists('Log', '')))
     | extend j = parse_json(line)
     | extend isobj = gettype(j) == 'dictionary'
     | extend lg = iff(isobj, tostring(coalesce(j.loggerName, j.logger_name, j.logger)), ''), lvl = iff(isobj, tostring(j.level), '(plain)')
     | extend fm = coalesce(nullif(tostring(j.formattedMessage), ''), nullif(tostring(j.message), ''), nullif(tostring(j.msg), ''), '(no message)')
     | extend st = tostring(coalesce(j.stack_trace, j.exception, j.throwable))
     | extend msg = replace_regex(iff(isobj, fm, line), @'[\r\n\t]+', ' ')
     | extend mst = replace_regex(strcat(msg, ' ', st), @'[\r\n\t]+', ' ')
     | extend exc = coalesce(nullif(tostring(j.throwable.className), ''), nullif(tostring(j.exception.className), ''), extract(@'^\s*([A-Za-z_][A-Za-z0-9_.]{2,160})', 1, st)),
              cause = coalesce(nullif(tostring(j.throwable.cause.className), ''), nullif(tostring(j.exception.cause.className), ''), extract(@'Caused by: ([A-Za-z_][A-Za-z0-9_.]{2,160})', 1, st))
     | extend sqlstate = coalesce(extract(@'(?:SQLState|SQL State|sqlstate)[ =:]+([0-9A-Z]{5})', 1, mst), ''), ms = coalesce(extract(@'\b([0-9]{1,9}) ?ms\b', 1, msg), '')
     | extend script = coalesce(extract(@'Migration (V[0-9]+__[A-Za-z0-9_]+)', 1, mst), '')
     | extend what = case(
         msg startswith 'pre-flight: nothing to do', strcat('pre-flight nothing to do ', tostring(extract_all(@'(V[0-9]{4}-[A-Za-z0-9-]+=(?:clean|not pending|repaired))', msg))),
         msg startswith 'pre-flight', 'pre-flight line',
         msg startswith 'waiting-item sweep enabled', strcat('sweep enabled, interval ', coalesce(extract(@'interval ([0-9]+) s', 1, msg), '?'), ' s, batch ', coalesce(extract(@'batch ([0-9]+)', 1, msg), '?')),
         msg startswith 'waiting-item sweep disabled by', strcat('sweep disabled by ', coalesce(extract(@'disabled by (ARON_WAITING_SWEEP=off|cfg[.]sync[.]waiting_sweep_enabled)', 1, msg), '?')),
         msg matches regex @'^Current version of schema', strcat('flyway current version ', coalesce(extract(@'^Current version of schema [^:]*: v?([0-9][0-9.]*)', 1, msg), '?')),
         msg matches regex @'^Migrating schema', strcat('flyway migrating to ', coalesce(extract(@'^Migrating schema [^ ]+ to version [\x22]?v?([0-9][0-9.]*)', 1, msg), '?')),
         msg matches regex @'^Successfully applied', strcat('flyway applied ', coalesce(extract(@'^Successfully applied ([0-9]+) migration', 1, msg), '?'), ' now at ', coalesce(extract(@'now at version v?([0-9][0-9.]*)', 1, msg), '?')),
         msg matches regex @'^Schema .* is up to date', 'flyway up to date',
         msg matches regex @'^Successfully validated', strcat('flyway validated ', coalesce(extract(@'^Successfully validated ([0-9]+) migration', 1, msg), '?')),
         msg matches regex @'^Creating Schema History', 'flyway created schema history',
         msg matches regex @'^migrate: [0-9]+ migration', strcat('migrate: ', coalesce(extract(@'^migrate: ([0-9]+) migration', 1, msg), '?'), ' applied'),
         msg matches regex @'(?i)(started|listening|responding at)', 'startup line',
         msg matches regex @'(?i)(bundle|lapsed|aggregat|geo re-check|retention|risk signal|route-day)', 'job-run line',
         msg matches regex @'(?i)(error|failed|exception)', 'error line',
         '-')
     | extend what = iff(mst matches regex @'Migration V[0-9]+__[A-Za-z0-9_.]+ failed', strcat('flyway FAILED at ', coalesce(extract(@'Migration V([0-9]+)__', 1, mst), '?')), what)
     | extend lg = iff(lg == '' or lg matches regex @'^[A-Za-z_][A-Za-z0-9_.\x24-]{0,160}$', lg, '[text]'),
              lvl = iff(lvl matches regex @'^([A-Z]{3,8}|\(plain\))$', lvl, '[text]'),
              exc = iff(isempty(exc) or exc matches regex @'^[A-Za-z_][A-Za-z0-9_.\x24-]{0,160}$', exc, '[text]'),
              cause = iff(isempty(cause) or cause matches regex @'^[A-Za-z_][A-Za-z0-9_.\x24-]{0,160}$', cause, '[text]')"
# The secret-word exclusion (message mode only): applied to the PRINTED message text, never to a raw JSON line or a stack trace.
KQL_HELD="| extend held = msg matches regex @'(?i)password|secret|authorization|bearer|\bkey=|sig=|token[=:]|jdbc:|connection ?string|statement:|parameters:|DETAIL:'"

# console_section <app> <label>: the structured table by default; with messages, the held-back filter, its count and the message column.
console_section() {
  local app="$1" label="$2" base q qc
  section "Log Analytics: console log of $label in the window (ERROR/WARN and known operational lines, newest first)"
  [ -n "${ws:-}" ] || { echo "(no Log Analytics workspace, so no console log)"; return; }
  base="$KQL_CONSOLE
     | where app == '$app'
     | where not(lg startswith 'aron.request')
     | where lvl in ('WARN', 'ERROR', 'FATAL') or what != '-' or (not(isobj) and line matches regex @'(?i)\b(WARN|ERROR|FATAL)\b')"
  if [ "$INCLUDE_MESSAGES" = true ]; then
    q="$base $KQL_HELD | where not(held) | top 150 by TimeGenerated desc | project TimeGenerated, lvl, lg, exc, cause, sqlstate, ms, what, msg"
    la "$q" "[].[TimeGenerated, lvl, lg, exc, cause, sqlstate, ms, what, msg]" 300
    qc="$base $KQL_HELD | summarize held = countif(held), shown = countif(not(held))"
    if laq "$qc" "[0].[held, shown]"; then printf '%s\n' "$LA_OUT" | held_note; fi
  else
    echo "(columns: time, level, logger, exception class, cause class, SQLSTATE, ms, what; message and exception text are not printed)"
    q="$base | top 150 by TimeGenerated desc | project TimeGenerated, lvl, lg, exc, cause, sqlstate, ms, what"
    la "$q" "[].[TimeGenerated, lvl, lg, exc, cause, sqlstate, ms, what]" 300
    qc="$base | summarize plain = countif(not(isobj)), total = count()"
    echo "(for orientation, plain-text lines and matching lines in the window:)"
    if laq "$qc" "[0].[plain, total]"; then printf '%s\n' "$LA_OUT" | tr '\t\n' '  ' | show 80; echo; fi
  fi
}

if [ -n "${ws:-}" ]; then
  section "Log Analytics: console log of job executions starting with $JOB_PREFIX in the window"
  base="$KQL_CONSOLE
     | where exec startswith '$JOB_PREFIX'
     | where what != '-' or lvl in ('WARN', 'ERROR', 'FATAL') or line matches regex @'(?i)(error|failed|exception)'"
  if [ "$INCLUDE_MESSAGES" = true ]; then
    la "$base | order by TimeGenerated asc | project TimeGenerated, exec, lvl, what, sqlstate, script, msg | take 400" "[].[TimeGenerated, exec, lvl, what, sqlstate, script, msg]" 400
  else
    echo "(columns: time, execution, level, what, SQLSTATE, migration script name; message text is not printed)"
    la "$base | order by TimeGenerated asc | project TimeGenerated, exec, lvl, what, sqlstate, script | take 400" "[].[TimeGenerated, exec, lvl, what, sqlstate, script]" 300
  fi

  section "Log Analytics: system log (platform reasons) for $JOB_PREFIX and $WORKER_APP in the window"
  sysq="union isfuzzy=true ContainerAppSystemLogs_CL, ContainerAppSystemLogs
     | where TimeGenerated between (datetime('$SINCE') .. datetime('$UNTIL'))
     | extend who = coalesce(column_ifexists('ContainerAppName_s', ''), column_ifexists('ContainerAppName', ''), column_ifexists('JobName_s', ''), column_ifexists('JobName', '')),
              typ = coalesce(column_ifexists('Type_s', ''), column_ifexists('Type', '')),
              reason = coalesce(column_ifexists('Reason_s', ''), column_ifexists('Reason', '')),
              txt = replace_regex(tostring(coalesce(column_ifexists('Log_s', ''), column_ifexists('Log', ''))), @'[\r\n\t]+', ' ')
     | extend exitcode = coalesce(extract(@'(?i)exit ?code:? ?(-?[0-9]{1,3})', 1, txt), '')"
  if [ "$INCLUDE_MESSAGES" = true ]; then
    la "$sysq | where * has '$JOB_PREFIX' or * has '$WORKER_APP' | order by TimeGenerated asc | project TimeGenerated, who, typ, reason, txt | take 200" "[].[TimeGenerated, who, typ, reason, txt]" 300
  else
    echo "(columns: time, resource, type, reason, exit code; the platform message text is not printed)"
    la "$sysq | where * has '$JOB_PREFIX' or * has '$WORKER_APP' | order by TimeGenerated asc | project TimeGenerated, who, typ, reason, exitcode | take 200" "[].[TimeGenerated, who, typ, reason, exitcode]" 200
  fi

  section "Log Analytics: PostgreSQL server log (AzureDiagnostics, PostgreSQLLogs) in the window, severity lines only"
  # Server-side reasons for a lost session (terminated, reset, out of memory, crash recovery): ERROR/FATAL/PANIC lines and the LOG lines that name such a reason.
  # Slow-statement (duration), STATEMENT, CONTEXT, QUERY, DETAIL and parameter records are dropped, so no SQL text or bind value is printed. By default only the severity and the
  # first words of the message up to the first quote, colon or parenthesis are printed (an ERROR message carries values after those).
  pgq="AzureDiagnostics
     | where TimeGenerated between (datetime('$SINCE') .. datetime('$UNTIL'))
     | where ResourceProvider == 'MICROSOFT.DBFORPOSTGRESQL' and Category == 'PostgreSQLLogs'
     | extend msg = tostring(coalesce(column_ifexists('Message', ''), column_ifexists('message_s', '')))
     | where msg matches regex '-(ERROR|FATAL|PANIC):' or (msg matches regex '-LOG:' and msg matches regex '(?i)terminat|out of memory|database system|server process|checkpoint|could not|disconnection|reset by peer')
     | where not(msg matches regex @'-LOG:\s+(execute|statement|parse|bind)')
     | where msg !contains 'duration:' and msg !contains 'statement:' and msg !contains 'CONTEXT:' and msg !contains 'QUERY:' and msg !contains 'DETAIL:' and msg !contains 'parameters:'
     | extend sev = extract(@'^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:.]{8,12} ?UTC[^ ]{0,40}?-(LOG|ERROR|FATAL|PANIC):', 1, msg)
     | extend head = iff(isempty(sev), '', iff(msg matches regex @'^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:.]{8,12} ?UTC[^ ]{0,40}?-(?:LOG|ERROR|FATAL|PANIC):[ ]{0,10}server process \(PID [0-9]+\) was terminated by signal [0-9]+', '${SIGNAL_LABEL}', coalesce(extract(@'^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:.]{8,12} ?UTC[^ ]{0,40}?-(?:LOG|ERROR|FATAL|PANIC):[ ]{0,10}(${PG_HEADS})', 1, msg), '[text]')))"
  if [ "$INCLUDE_MESSAGES" = true ]; then
    la "$pgq | where isnotempty(sev) | order by TimeGenerated asc | project TimeGenerated, sev, msg = replace_regex(msg, @'[\r\n\t]+', ' ') | take 300" "[].[TimeGenerated, sev, msg]" 300
  else
    echo "(columns: time, severity, an allow-listed PostgreSQL message head, else [text])"
    la "$pgq | where isnotempty(sev) | order by TimeGenerated asc | project TimeGenerated, sev, head | take 300" "[].[TimeGenerated, sev, head]" 200
    # Records whose log_line_prefix shape is not the expected one are not printed; their number is.
    if laq "$pgq | summarize dropped = countif(isempty(sev))" "[0].dropped"; then n="$(printf '%s' "$LA_OUT" | tr -dc '0-9')"; echo "(${n:-?} record(s) dropped: unknown log line prefix shape)"; fi
  fi

  console_section "$WORKER_APP" "$WORKER_APP"
  console_section "$API_APP" "$API_APP"

  section "Log Analytics: system log of $API_APP in the window (platform reasons: probes, restarts, scaling), newest first"
  if [ "$INCLUDE_MESSAGES" = true ]; then
    la "$sysq | where who == '$API_APP' | top 150 by TimeGenerated desc | project TimeGenerated, typ, reason, txt" "[].[TimeGenerated, typ, reason, txt]" 300
  else
    echo "(columns: time, type, reason, exit code; the platform message text is not printed)"
    la "$sysq | where who == '$API_APP' | top 150 by TimeGenerated desc | project TimeGenerated, typ, reason, exitcode" "[].[TimeGenerated, typ, reason, exitcode]" 200
  fi

  section "Log Analytics: migrate job pre-flight lines ($JOB_PREFIX*) in the window"
  # MigrationPreflight reports one line per action and, when it has nothing to do, "pre-flight: nothing to do (checks: ...)". By default the fixed what label with the check
  # states is printed (the states are extracted tokens like V0191-cluster-names=clean); the message itself only with include_messages. Absent means the pre-flight did not run
  # (an older image) or the job did not start in the window.
  base="$KQL_CONSOLE | where exec startswith '$JOB_PREFIX' | where what startswith 'pre-flight'"
  out=""
  if [ "$INCLUDE_MESSAGES" = true ]; then
    if laq "$base | top 100 by TimeGenerated desc | order by TimeGenerated asc | project TimeGenerated, exec, what, msg" "[].[TimeGenerated, exec, what, msg]"; then out="$(printf '%s\n' "$LA_OUT" | showm 400)"; else out="(see the failed query above)"; fi
  else
    if laq "$base | top 100 by TimeGenerated desc | order by TimeGenerated asc | project TimeGenerated, exec, what" "[].[TimeGenerated, exec, what]"; then out="$(printf '%s\n' "$LA_OUT" | show 300)"; else out="(see the failed query above)"; fi
  fi
  if [ -z "$out" ]; then echo "(no pre-flight line from a $JOB_PREFIX execution in the window)"; else printf '%s\n' "$out"; fi
fi

section "worker app $WORKER_APP"
try az containerapp show -g "$RG" -n "$WORKER_APP" --query '{running:properties.runningStatus, provisioning:properties.provisioningState, latest:properties.latestRevisionName, latestReady:properties.latestReadyRevisionName, mode:properties.configuration.activeRevisionsMode}' -o json
try az containerapp revision list -g "$RG" -n "$WORKER_APP" --query "[?properties.active].{name:name, replicas:properties.replicas, running:properties.runningState, created:properties.createdTime}" -o table
echo
nfail="$(wc -l < "$FAILFILE" | tr -d ' ')"; rm -f "$FAILFILE"
if [ "${nfail:-0}" -gt 0 ]; then echo "diagnose: $nfail section(s) FAILED"; fi
echo "diagnose: done (read-only)"
[ "${nfail:-0}" -eq 0 ] || exit 1
