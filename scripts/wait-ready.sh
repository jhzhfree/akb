#!/usr/bin/env sh
set -eu
for url in http://127.0.0.1:12000/health http://127.0.0.1:8080/actuator/health http://127.0.0.1:8088/; do
  i=0
  until curl -fsS "$url" >/dev/null; do
    i=$((i+1)); [ "$i" -gt 60 ] && { echo "timeout: $url"; exit 1; }; sleep 2
  done
  echo "ready: $url"
done
