#!/bin/sh

cd /opt/graphhopper
rm -f europe_france-gh.tar.xz

wget http://stockage.taxi.appsolu.net/static/routing/europe_france-gh.tar.xz
rm -fr europe_france-gh.old

mv europe_france-gh europe_france-gh.old
tar -xJf europe_france-gh.tar.xz && rm -f europe_france-gh.tar.xz
