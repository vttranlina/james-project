#!/bin/sh

sed -e "s/\${REDIS_MONITOR_HOST}/$REDIS_MASTER_HOST/" \
    -e "s/\${REDIS_MONITOR_PORT}/$REDIS_MASTER_PORT/" \
    /usr/local/etc/redis/sentinel.conf.template > /usr/local/etc/redis/sentinel.conf

redis-sentinel /usr/local/etc/redis/sentinel.conf
