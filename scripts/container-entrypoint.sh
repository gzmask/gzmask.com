#!/bin/sh
set -eu

# Fly mounts volumes owned by root; hand the mounted directory to the app user.
chown app:app /data
ln -sf /data/visitor-count.txt /app/visitor-count.txt
exec su -s /bin/sh app -c 'exec /app/persona-blog'
