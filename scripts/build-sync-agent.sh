#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT_DIR="$ROOT_DIR/sync-agent"

cd "$AGENT_DIR"

if command -v mvn >/dev/null 2>&1; then
  mvn -B -DskipTests package
else
  echo "ERROR: mvn not found in PATH" >&2
  exit 1
fi

echo "==> Built $AGENT_DIR/target/phu360-sync-agent.jar"
ls -lh "$AGENT_DIR/target/phu360-sync-agent-*.jar"
