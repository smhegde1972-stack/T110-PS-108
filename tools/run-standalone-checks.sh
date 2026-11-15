#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf build/classes build/test-classes
mkdir -p build/classes build/test-classes
javac --release 17 -d build/classes $(find src/main/java -name '*.java')
javac --release 17 -cp build/classes -d build/test-classes tools/StandaloneChecks.java
java -cp build/classes:build/test-classes StandaloneChecks
java -cp build/classes in.ps108.Main data/venues.csv data/vendors.csv data/commands.txt data/auspicious-dates.csv
