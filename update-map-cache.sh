#!/bin/sh

map="europe"

cd /opt/graphhopper
rm -f $map-gh.tar.xz

echo "Downloading new map cache"
wget http://stockage.taxi.appsolu.net/static/routing/$map-gh.tar.xz
rm -fr $map-gh.old

echo "download done, stopping service"
service graphhopper stop
mv $map-gh $map-gh.old

echo "Extracting cache"
tar -xJf$map-gh.tar.xz && rm -f $map-gh.tar.xz

echo "Restarting service"
service graphhopper start

echo "Update map cache finished"
