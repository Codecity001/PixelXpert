#!/bin/bash

NEWVERNAME=$(cat version.properties | grep -i "^CANARY_VERSION_NAME" | cut -d = -f 2 | tr -d ' \r')

# If CanaryChangelog.md already contains the entry, do not duplicate it
if grep -q "^\*\*$NEWVERNAME\*\*" CanaryChangelog.md; then
  echo "CanaryChangelog.md already contains entry for $NEWVERNAME. Skipping prepending."
else
  echo "Prepending $NEWVERNAME to CanaryChangelog.md"
  echo "**$NEWVERNAME**  " > newChangeLog.md
  cat changeLog.md >> newChangeLog.md
  echo "  " >> newChangeLog.md
  cat CanaryChangelog.md >> newChangeLog.md
  mv newChangeLog.md CanaryChangelog.md
fi