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

cp /opt/graphhopper/graphhopper.service /etc/systemd/system/

# After you make changes to your unit file, you should run systemctl daemon-reload, as outlined here : https://www.freedesktop.org/software/systemd/man/systemctl.html#daemon-reload
systemctl daemon-reload
systemctl disable graphhopper.service
systemctl enable graphhopper.service
systemctl start graphhopper.service 

systemctl status graphhopper
