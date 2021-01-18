#!/bin/sh
#rsync -avx -e ssh --progress europe_france-gh.tar.xz root@stockage.taxi.appsolu.net:/var/www/stockage/static/routing
rsync -avx -e ssh --progress europe-gh.tar.xz root@stockage.taxi.appsolu.net:/var/www/stockage/static/routing
