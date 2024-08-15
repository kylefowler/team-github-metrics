#!/usr/bin/env bash
# Run one of the samples.
# The first argument must be the name of the sample task (e.g. echo).
# Any remaining arguments are forwarded to the sample's argv.

./gradlew --quiet ":metrics:installDist" && "./metrics/build/install/metrics/bin/metrics" "$@"