#!/bin/sh
set -e
cd "$(dirname "$0")/App"
if ! command -v xcodegen >/dev/null; then
  echo "Установите XcodeGen: brew install xcodegen" >&2
  exit 1
fi
xcodegen generate
echo "Откройте ios/App/MalahitChip.xcodeproj"
