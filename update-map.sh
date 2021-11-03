#!/bin/sh

map="europe"

echo "Stopping service"
service graphhopper stop

cd /opt/graphhopper
rm -f $map.pbf

echo "Downloading new map"
wget http://stockage.taxi.appsolu.net/static/routing/$map.pbf

echo "download done"

echo "Update map finished, updating map cache"
./update-map-cache.sh