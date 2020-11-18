#!/bin/sh

echo "Stopping service"
service graphhopper stop || exit 1

cd /opt/graphhopper
echo "Pulling new code"
git pull || exit 1

echo "Pull finished, building"
./graphhopper.sh build || exit 1

echo "Build finished, starting service"
service graphhopper start
