#!/usr/bin/env bash
# Simulates normal traffic followed by a burst against the rate limiter.
#
# Usage: ./traffic_test.sh [base_url] [client_id]

BASE_URL="${1:-http://localhost:8080}"
CLIENT_ID="${2:-demo-client}"
URL="$BASE_URL/api/resource"

echo "== Phase 1: Normal traffic (1 request/sec, well under refill rate) =="
for i in $(seq 1 5); do
  status=$(curl -s -o /dev/null -w "%{http_code}" -H "X-Client-Id: $CLIENT_ID" "$URL")
  echo "Request $i -> HTTP $status"
  sleep 1
done

echo
echo "== Phase 2: Burst traffic (20 requests fired immediately) =="
allowed=0
rejected=0
for i in $(seq 1 20); do
  status=$(curl -s -o /dev/null -w "%{http_code}" -H "X-Client-Id: $CLIENT_ID" "$URL")
  if [ "$status" = "200" ]; then
    allowed=$((allowed+1))
  else
    rejected=$((rejected+1))
  fi
  echo "Burst request $i -> HTTP $status"
done

echo
echo "Summary: $allowed allowed, $rejected rejected (HTTP 429) out of 20 burst requests"
echo "Expect roughly 'bucket-capacity' allowed and the rest rejected, since the burst"
echo "arrives faster than the refill rate can replenish tokens."
