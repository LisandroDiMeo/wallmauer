#!/bin/bash

echo "Giving permissions to $1"

{ aapt d permissions "$1" | grep -Eo "name='.+'" | sed -e "s/name='//g" | sed -e "s/'//g" | xargs -n 1 adb shell pm grant "$2"; } > /dev/null 2>&1
