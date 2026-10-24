#!/bin/bash
# Start the Muse Recorder receiver. Generates and remembers a token on
# first run (~/workspace/muse-recordings/token.txt) - use the same token
# in the Android app.
set -e
BASE="$HOME/workspace/muse-recordings"
mkdir -p "$BASE"
if [ ! -f "$BASE/token.txt" ]; then
  python3 -c "import secrets; print(secrets.token_urlsafe(32))" > "$BASE/token.txt"
  echo "New token created: $(cat "$BASE/token.txt")"
else
  echo "Token: $(cat "$BASE/token.txt")"
fi
export MUSE_RECORDER_TOKEN="$(cat "$BASE/token.txt")"
exec python3 "$(dirname "$0")/server.py" --port 8765 --base "$BASE"
