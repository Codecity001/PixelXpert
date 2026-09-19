#!/bin/bash

TAG="$1"
CHANGELOG="StableChangelog.md"
OUTPUT="releaseNotes.md"

rm -f "$OUTPUT"

if [ ! -f "$CHANGELOG" ]; then
  echo "PixelXpertFork $TAG Stable Release" > "$OUTPUT"
  exit 0
fi

# Extract the block starting at **$TAG** up to the next **v... header
FOUND=0
while IFS= read -r line || [ -n "$line" ]; do
  clean_line=$(echo "$line" | tr -d '\r')
  if [[ "$clean_line" =~ ^\*\*"$TAG"\*\* ]]; then
    FOUND=1
  elif [[ "$clean_line" =~ ^\*\*v[0-9] ]] && [ $FOUND -eq 1 ]; then
    break
  fi

  if [ $FOUND -eq 1 ]; then
    echo "$clean_line" >> "$OUTPUT"
  fi
done < "$CHANGELOG"

# Fallback to full changelog if tag was not found or output is empty
if [ ! -s "$OUTPUT" ]; then
  cp "$CHANGELOG" "$OUTPUT"
fi
