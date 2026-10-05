#!/bin/sh
# Completes the platform admin's forced first-sign-in password change, the way a person does
# after a fresh install: sign in on kelta-auth's login form with the initial password (the
# KELTA_BOOTSTRAP_ADMIN_PASSWORD value, or the one kelta-auth printed in its first-boot banner),
# get sent to /change-password, choose NEW_PASSWORD.
#
# Runs inside a curlimages/curl container on the compose network (piped in over stdin — see
# ci/quickstart-run.sh and the "e2e" job in .github/workflows/ci.yml).
#
# Cookies are carried by hand rather than with curl's cookie engine: the session cookie may be
# Secure, and curl neither stores nor sends a Secure cookie over plain http.
set -eu

AUTH_URL="${AUTH_URL:-http://kelta-auth:8081}"
TENANT_SLUG="${TENANT_SLUG:-default}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin@kelta.local}"
: "${INITIAL_PASSWORD:?INITIAL_PASSWORD must be set}"
: "${NEW_PASSWORD:?NEW_PASSWORD must be set}"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
: > "$TMP/jar"

cookie_header() {
  tr '\n' ';' < "$TMP/jar" | sed 's/;$//; s/;/; /g'
}

# request <body-file> <curl args...>: one request with the session's cookies; remembers new ones.
request() {
  body="$1"
  shift
  curl -sS -o "$body" -D "$TMP/headers" -H "Cookie: $(cookie_header)" "$@"
  tr -d '\r' < "$TMP/headers" | sed -n 's/^[Ss]et-[Cc]ookie: *\([^=;]*=[^;]*\).*/\1/p' > "$TMP/set"
  while IFS= read -r c; do
    grep -v "^${c%%=*}=" "$TMP/jar" > "$TMP/jar.new" || true
    echo "$c" >> "$TMP/jar.new"
    mv "$TMP/jar.new" "$TMP/jar"
  done < "$TMP/set"
}

location() {
  tr -d '\r' < "$TMP/headers" | sed -n 's/^[Ll]ocation: *//p'
}

csrf() {
  grep -o '<input[^>]*name="_csrf"[^>]*>' "$1" | sed -n 's/.*value="\([^"]*\)".*/\1/p' | head -n 1
}

request "$TMP/login.html" "$AUTH_URL/login?tenant=$TENANT_SLUG"
TOKEN=$(csrf "$TMP/login.html")
[ -n "$TOKEN" ] || { echo "No CSRF token on the login page" >&2; exit 1; }

request "$TMP/signin.html" -X POST "$AUTH_URL/login" \
  --data-urlencode "username=$ADMIN_USERNAME" \
  --data-urlencode "password=$INITIAL_PASSWORD" \
  --data-urlencode "_csrf=$TOKEN"
case "$(location)" in
  */change-password*) ;;
  *) echo "Sign-in did not ask for a password change (Location: '$(location)')" >&2; exit 1 ;;
esac

request "$TMP/change.html" "$AUTH_URL/change-password"
TOKEN=$(csrf "$TMP/change.html")
[ -n "$TOKEN" ] || { echo "No CSRF token on the change-password page" >&2; exit 1; }

request "$TMP/changed.html" -X POST "$AUTH_URL/change-password" \
  --data-urlencode "currentPassword=$INITIAL_PASSWORD" \
  --data-urlencode "newPassword=$NEW_PASSWORD" \
  --data-urlencode "confirmPassword=$NEW_PASSWORD" \
  --data-urlencode "_csrf=$TOKEN"
case "$(location)" in
  *passwordChanged*) echo "Platform admin first sign-in complete: password changed" ;;
  *) echo "Password change was not accepted (Location: '$(location)')" >&2; exit 1 ;;
esac
