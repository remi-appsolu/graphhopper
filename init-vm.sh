#!/bin/bash

apt update
apt install xz-utils tar wget git

mkdir -p /opt
cd /opt
echo "cloning"
git clone https://github.com/remi-appsolu/graphhopper.git/
cd /opt/graphhopper
git checkout 2.x


if [ -f "/opt/graphhopper/europe_france.pbf" ]; then
	echo "/opt/graphhopper/europe_france.pbf deja présent"
else
	echo "downloading europe_france.pbf"
	wget http://stockage.taxi.appsolu.net/static/routing/europe_france.pbf
	echo "downloaded europe_france.pbf"
fi

if [ -d "/opt/graphhopper/europe_france-gh" ]; then
	echo "/opt/graphhopper/europe_france-gh deja présent"
else

	echo "downloading europe_france-gh.tar.xz"
	wget http://stockage.taxi.appsolu.net/static/routing/europe_france-gh.tar.xz

	echo "downloaded europe_france-gh.tar.xz, extracting"
	tar -xJf europe_france-gh.tar.xz && rm -f europe_france-gh.tar.xz
	echo "extracted europe_france-gh.tar.xz"
fi


echo "Building GraphHopper"
./graphhopper.sh build
echo "Builded GraphHopper, STARTING"
./graphhopper.sh -a web -i europe_france.pbf

