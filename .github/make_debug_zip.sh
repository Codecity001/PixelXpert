#!/bin/bash

mkdir -p output
if [ -f app/build/outputs/apk/debug/PixelXpert-signed.apk ]; then
  cp app/build/outputs/apk/debug/PixelXpert-signed.apk MagiskModBase/system/priv-app/PixelXpert/PixelXpert.apk
else
  cp app/build/outputs/apk/debug/PixelXpert.apk MagiskModBase/system/priv-app/PixelXpert/PixelXpert.apk
fi
cd MagiskModBase

FILENAME="PixelXpert.zip"
zip -r ../output/$FILENAME *;