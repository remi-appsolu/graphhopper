#!/bin/sh
#rsync -avx -e ssh --progress europe_france-gh.tar.xz root@stockage.taxi.appsolu.net:/var/www/stockage/static/routing
rsync -av -e "ssh -o PubkeyAcceptedKeyTypes=+ssh-dss" -P --append-verify europe.pbf root@stockage.taxi.appsolu.net:/var/www/stockage/static/routing
