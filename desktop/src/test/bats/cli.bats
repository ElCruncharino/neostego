#!/usr/bin/env bats
#
# UNIX / pipe-compliance tests for the NeoStego command-line interface.
# Run with:  bats desktop/src/test/bats/cli.bats
# (requires the distribution to be built first: ./gradlew :desktop:distBase)

setup() {
    REPO="$(cd "$BATS_TEST_DIRNAME/../../../.." && pwd)"
    LAUNCHER="$REPO/desktop/build/distributions/package/neostego.sh"
    COVER="$REPO/desktop/src/test/resources/compat/cover.png"
    WORK="$BATS_TEST_TMPDIR"
    # Make sure no inherited password leaks into the prompt-guard tests.
    unset NEOSTEGO_PASSWORD
}

ns() { bash "$LAUNCHER" "$@"; }

@test "help prints usage and exits 0" {
    run ns --help
    [ "$status" -eq 0 ]
    [[ "$output" == *"command"* ]]
}

@test "algorithms lists plugins on stdout and exits 0" {
    run ns algorithms
    [ "$status" -eq 0 ]
    [[ "$output" == *"RandomLSB"* ]]
    [[ "$output" == *"DWTSVD"* ]]
}

@test "exit code is non-zero for an invalid algorithm" {
    run bash -c "echo hi | '$LAUNCHER' embed -a NoSuchAlgo -cf '$COVER' -sf '$WORK/o.png'"
    [ "$status" -ne 0 ]
}

@test "exit code is non-zero for a corrupt stego file" {
    printf 'not a png' > "$WORK/bad.png"
    run ns extract -a RandomLSB -sf "$WORK/bad.png" -xf -
    [ "$status" -ne 0 ]
}

@test "stream hygiene: data on stdout survives stderr suppression" {
    run bash -c "'$LAUNCHER' algorithms 2>/dev/null"
    [ "$status" -eq 0 ]
    [[ "$output" == *"Adaptive"* ]]
}

@test "pipe round-trip: stdin message -> stego on stdout -> payload on stdout" {
    msg="neostego pipe test 12345"
    out="$(echo "$msg" | bash "$LAUNCHER" embed -a RandomLSB -cf "$COVER" -sf - 2>/dev/null \
            | bash "$LAUNCHER" extract -a RandomLSB -sf - -xf - 2>/dev/null)"
    [ "$out" = "$msg" ]
}

@test "extract -xf - emits only the payload (no log noise on stdout)" {
    msg="payload-only"
    echo "$msg" | bash "$LAUNCHER" embed -a RandomLSB -cf "$COVER" -sf "$WORK/s.png" 2>/dev/null
    run bash -c "'$LAUNCHER' extract -a RandomLSB -sf '$WORK/s.png' -xf - 2>/dev/null"
    [ "$status" -eq 0 ]
    [ "$output" = "$msg" ]
}

@test "non-TTY: encrypted extract without a password fails fast (no hang)" {
    echo "secret" | NEOSTEGO_PASSWORD=pw bash "$LAUNCHER" embed -a RandomLSB -e -cf "$COVER" -sf "$WORK/enc.png" 2>/dev/null
    run timeout 20 bash -c "'$LAUNCHER' extract -a RandomLSB -sf '$WORK/enc.png' -xf - </dev/null"
    [ "$status" -ne 0 ]     # must report an error...
    [ "$status" -ne 124 ]   # ...and not have hung until the timeout fired
}

@test "NEOSTEGO_PASSWORD round-trips encrypted data non-interactively" {
    echo "enc msg" | NEOSTEGO_PASSWORD=pw bash "$LAUNCHER" embed -a RandomLSB -e -cf "$COVER" -sf "$WORK/e2.png" 2>/dev/null
    out="$(NEOSTEGO_PASSWORD=pw bash "$LAUNCHER" extract -a RandomLSB -sf "$WORK/e2.png" -xf - 2>/dev/null)"
    [ "$out" = "enc msg" ]
}
