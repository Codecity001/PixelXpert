#!/bin/bash

# reset the file - most likely not needd
rm -f changeLog.md
rm -f Tchangelog.htm
touch changeLog.md
touch Tchangelog.htm

NEWVERNAME=$(cat version.properties | grep -i "^CANARY_VERSION_NAME" | cut -d = -f 2 | tr -d ' \r')

# If CanaryChangelog.md already contains the new version entry, extract it directly
if [ -f CanaryChangelog.md ] && grep -q "^\*\*$NEWVERNAME\*\*" CanaryChangelog.md; then
  echo "Extracting release notes for $NEWVERNAME directly from CanaryChangelog.md"
  awk '/^\*\*canary-/ {count++; if(count==1) next} count==2 {exit} {print}' CanaryChangelog.md > changeLog.md
else
  # Fallback: find the last time we made a changelog
  LASTUPDATE=$(git log -100 | grep -B 4 "Version update: Release" | grep "commit" -m 1 | cut -d " " -f 2)
  # find commits since - starting with the magic phrase
  COMMITS=$(git rev-list $LASTUPDATE..HEAD --grep "^CHANGELOG: ")
  # separator is newline
  IFS=$'\n'
  for COMMIT in $COMMITS
  do
    COMMITMSGS=$(git show $COMMIT --pretty=format:"%s" | grep "^CHANGELOG: " | tr -d '\0')
    for LINE in $COMMITMSGS
    do
      # save in the temp file to be used by next script
      echo "- "${LINE##*CHANGELOG: }"  " >> changeLog.md
    done
  done
fi

# Fallback if changeLog.md is empty
if [ ! -s changeLog.md ]; then
  echo "- PixelXpertFork $NEWVERNAME Canary Release  " > changeLog.md
fi