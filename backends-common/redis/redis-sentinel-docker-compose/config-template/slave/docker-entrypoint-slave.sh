#!/bin/sh

# Replace placeholders with environment variable values
sed -e "s/\${REDIS_MASTER_HOST}/$REDIS_MASTER_HOST/" \
    -e "s/\${REDIS_MASTER_PORT}/$REDIS_MASTER_PORT/" \
    /usr/local/etc/redis/redis.conf.template > /usr/local/etc/redis/redis.conf

# Start Redis server
redis-server /usr/local/etc/redis/redis.conf
