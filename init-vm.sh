#!/bin/bash
# tuto : https://cloud.google.com/load-balancing/docs/https/ext-http-lb-simple?hl=fr#console

apt-get update
apt-get --assume-yes install xz-utils tar wget git openjdk-11-jdk maven

mkdir -p /opt
cd /opt
echo "cloning"
git clone -q https://github.com/remi-appsolu/graphhopper.git/
cd /opt/graphhopper
git checkout 2.x

map="europe"


if [ -f "/opt/graphhopper/$map.pbf" ]; then
	echo "/opt/graphhopper/$map.pbf deja présent"
else
	echo "downloading $map.pbf"
	wget http://stockage.taxi.appsolu.net/static/routing/europe_france.pbf
	echo "downloaded $map.pbf"
fi

if [ -d "/opt/graphhopper/$map-gh" ]; then
	echo "/opt/graphhopper/$map-gh deja présent"
else

	echo "downloading $map-gh.tar.xz"
	wget http://stockage.taxi.appsolu.net/static/routing/$map-gh.tar.xz

	echo "downloaded $map-gh.tar.xz, extracting"
	tar -xJf $map-gh.tar.xz && rm -f $map-gh.tar.xz
	echo "extracted $map-gh.tar.xz"
fi


echo "Building GraphHopper"
./graphhopper.sh build
echo "Builded GraphHopper"

# pour demarrage direct
#./graphhopper.sh -a web -i europe_france.pbf

# pour demarrage par service
cp /opt/graphhopper/graphhopper.service /etc/systemd/system/

systemctl enable graphhopper.service
systemctl start graphhopper.service 
