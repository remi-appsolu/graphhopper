#!/bin/sh

service graphhopper stop
cd /opt/graphhopper
git pull
./graphhopper.sh build
service graphhopper start
