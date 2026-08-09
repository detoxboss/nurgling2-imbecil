#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
exec java -Dsun.java2d.uiScale.enabled=false --add-exports=java.desktop/sun.awt=ALL-UNNAMED -Xss8m -Xms1024m -Xmx4096m -jar hafen.jar "$@"
