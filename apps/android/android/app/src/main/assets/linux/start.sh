#!/bin/bash
# Built-in Linux engine start: serves the harness on loopback; the app reads the printed sign-in link.
set -euo pipefail
cd /root/work
exec dsh web --port "${DSH_PORT:-47400}" --no-open
