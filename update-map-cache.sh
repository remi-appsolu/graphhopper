#!/bin/sh

cd /opt/graphhopper
rm -f europe_france-gh.tar.xz

echo "Downloading new map cache"
wget http://stockage.taxi.appsolu.net/static/routing/europe_france-gh.tar.xz
rm -fr europe_france-gh.old

echo "download done, stopping service"
service graphhopper stop
mv europe_france-gh europe_france-gh.old

echo "Extracting cache"
tar -xJf europe_france-gh.tar.xz && rm -f europe_france-gh.tar.xz

echo "Restarting service"
service graphhopper start

echo "Update map cache finished"
