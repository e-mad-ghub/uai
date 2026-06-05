#!/usr/bin/env bash
# UAI Build Script
# Usage: ./build.sh [option]
# If no option is given, an interactive menu is shown.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ── Output paths ──────────────────────────────────────────────────────────────
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
RELEASE_APK="app/build/outputs/apk/release/app-release.apk"
RELEASE_AAB="app/build/outputs/bundle/release/app-release.aab"

# ── Signing config (edit or set env vars to override) ─────────────────────────
KEYSTORE_FILE="${KEYSTORE_FILE:-uai-release.jks}"
KEY_ALIAS="${KEY_ALIAS:-screenagent-key}"

# ── Helpers ───────────────────────────────────────────────────────────────────
bold() { printf '\033[1m%s\033[0m\n' "$*"; }
info() { printf '\033[34m→ %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m✓ %s\033[0m\n' "$*"; }
err()  { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; }

run_gradle_with_aapt2_retry() {
    local log_file retry_log_file exit_code
    log_file="$(mktemp "${TMPDIR:-/tmp}/uai-gradle.XXXXXX.log")"

    set +e
    ./gradlew "$@" 2>&1 | tee "$log_file"
    exit_code=${PIPESTATUS[0]}
    set -e

    if [[ $exit_code -eq 0 ]]; then
        rm -f "$log_file"
        return 0
    fi

    if grep -Eq 'AAPT2 .*Daemon #[0-9]+: Daemon startup failed|Daemon startup failed' "$log_file"; then
        err "AAPT2 daemon failed to start. Stopping Gradle daemons and retrying once…"
        ./gradlew --stop || true

        retry_log_file="$(mktemp "${TMPDIR:-/tmp}/uai-gradle-retry.XXXXXX.log")"
        set +e
        ./gradlew "$@" 2>&1 | tee "$retry_log_file"
        exit_code=${PIPESTATUS[0]}
        set -e

        if [[ $exit_code -ne 0 ]]; then
            err "Gradle retry failed. Logs:"
            err "  first: $log_file"
            err "  retry: $retry_log_file"
            return "$exit_code"
        fi

        rm -f "$log_file" "$retry_log_file"
        return 0
    fi

    err "Gradle failed. Log: $log_file"
    return "$exit_code"
}

require_signing_vars() {
    if [[ -z "${KEYSTORE_PASS:-}" ]]; then
        read -rsp "Keystore password: " KEYSTORE_PASS; echo
    fi
    if [[ -z "${KEY_PASS:-}" ]]; then
        read -rsp "Key password (leave blank if same as keystore): " KEY_PASS; echo
        KEY_PASS="${KEY_PASS:-$KEYSTORE_PASS}"
    fi
}

# ── Build functions ────────────────────────────────────────────────────────────
build_debug_apk() {
    info "Building debug APK…"
    run_gradle_with_aapt2_retry assembleDebug
    ok "Debug APK built"
    printf '   \033[33m%s\033[0m\n' "$SCRIPT_DIR/$DEBUG_APK"
}

build_release_aab() {
    require_signing_vars
    info "Building release AAB (Play Store)…"
    run_gradle_with_aapt2_retry bundleRelease \
        -Pandroid.injected.signing.store.file="$SCRIPT_DIR/$KEYSTORE_FILE" \
        -Pandroid.injected.signing.store.password="$KEYSTORE_PASS" \
        -Pandroid.injected.signing.key.alias="$KEY_ALIAS" \
        -Pandroid.injected.signing.key.password="$KEY_PASS"
    ok "Release AAB built"
    printf '   \033[33m%s\033[0m\n' "$SCRIPT_DIR/$RELEASE_AAB"
}

build_release_apk() {
    require_signing_vars
    info "Building release APK…"
    run_gradle_with_aapt2_retry assembleRelease \
        -Pandroid.injected.signing.store.file="$SCRIPT_DIR/$KEYSTORE_FILE" \
        -Pandroid.injected.signing.store.password="$KEYSTORE_PASS" \
        -Pandroid.injected.signing.key.alias="$KEY_ALIAS" \
        -Pandroid.injected.signing.key.password="$KEY_PASS"
    ok "Release APK built"
    printf '   \033[33m%s\033[0m\n' "$SCRIPT_DIR/$RELEASE_APK"
}

APP_ID="com.mad.screenagent"

select_device() {
    # Returns the serial to use in ADB_DEVICE (exported)
    local devices
    mapfile -t devices < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
    if [[ ${#devices[@]} -eq 0 ]]; then
        err "No adb devices connected."
        exit 1
    elif [[ ${#devices[@]} -eq 1 ]]; then
        ADB_DEVICE="${devices[0]}"
    else
        bold "  Multiple devices connected:"
        for i in "${!devices[@]}"; do
            printf "    %d) %s\n" "$((i+1))" "${devices[$i]}"
        done
        printf "\n  Choose device [1-%d]: " "${#devices[@]}"
        read -r dev_choice
        echo
        if [[ "$dev_choice" -lt 1 || "$dev_choice" -gt "${#devices[@]}" ]] 2>/dev/null; then
            err "Invalid choice: $dev_choice"
            exit 1
        fi
        ADB_DEVICE="${devices[$((dev_choice-1))]}"
    fi
    info "Using device: $ADB_DEVICE"
}

adb_uninstall_if_signature_mismatch() {
    local apk="$1"
    # Try install; if it fails with signature mismatch, uninstall and retry
    local output
    if ! output=$(adb -s "$ADB_DEVICE" install -r "$apk" 2>&1); then
        if echo "$output" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
            info "Signature mismatch — uninstalling existing app first…"
            adb -s "$ADB_DEVICE" uninstall "$APP_ID" || true
            adb -s "$ADB_DEVICE" install "$apk"
        else
            echo "$output" >&2
            return 1
        fi
    fi
}

install_debug_apk() {
    if [[ ! -f "$DEBUG_APK" ]]; then
        info "Debug APK not found — building first…"
        build_debug_apk
    fi
    select_device
    info "Installing debug APK…"
    adb_uninstall_if_signature_mismatch "$DEBUG_APK"
    ok "Installed debug APK"
}

install_release_apk() {
    if [[ ! -f "$RELEASE_APK" ]]; then
        info "Release APK not found — building first…"
        build_release_apk
    fi
    select_device
    info "Installing release APK…"
    adb_uninstall_if_signature_mismatch "$RELEASE_APK"
    ok "Installed release APK"
}

# ── Test functions ─────────────────────────────────────────────────────────────
run_unit_tests() {
    info "Running unit tests…"
    run_gradle_with_aapt2_retry testDebugUnitTest
    ok "Unit tests passed"
}

run_instrumented_tests() {
    info "Running instrumented tests (device/emulator required)…"
    run_gradle_with_aapt2_retry connectedDebugAndroidTest
    ok "Instrumented tests passed"
}

run_all_tests() {
    run_unit_tests
    run_instrumented_tests
}

# ── Menu / dispatch ────────────────────────────────────────────────────────────
run_option() {
    case "$1" in
        1) build_debug_apk ;;
        2) build_release_aab ;;
        3) build_release_apk ;;
        4) install_debug_apk ;;
        5) install_release_apk ;;
        6) run_unit_tests ;;
        7) run_instrumented_tests ;;
        8) run_all_tests ;;
        *) err "Unknown option: $1"; exit 1 ;;
    esac
}

if [[ $# -ge 1 ]]; then
    run_option "$1"
else
    bold ""
    bold "  UAI Build Script"
    bold "  ─────────────────────────────────"
    echo "  1) Build debug APK"
    echo "  2) Build release AAB  (Google Play)"
    echo "  3) Build release APK  (sideload)"
    echo "  4) Install debug APK  (adb)"
    echo "  5) Install release APK (adb)"
    echo "  6) Run unit tests"
    echo "  7) Run instrumented tests  (adb)"
    echo "  8) Run all tests"
    bold "  ─────────────────────────────────"
    printf "\n  Choose [1-8]: "
    read -r choice
    echo
    run_option "$choice"
fi
