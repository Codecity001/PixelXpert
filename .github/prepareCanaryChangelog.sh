#!/bin/bash

NEWVERNAME=$(cat version.properties | grep -i "^CANARY_VERSION_NAME" | cut -d = -f 2 | tr -d ' \r')

echo "**$NEWVERNAME**  " > newChangeLog.md
cat changeLog.md >> newChangeLog.md
echo "  " >> newChangeLog.md
cat CanaryChangelog.md >> newChangeLog.md
mv  newChangeLog.md CanaryChangelog.md