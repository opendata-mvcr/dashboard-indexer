#!/usr/bin/env bash
sh -c "printenv | grep -v "no_proxy" >> /etc/environment && /usr/sbin/service cron start && rm -f /var/run/rsyslogd.pid && rm -f /var/run/indexer.pid && /usr/sbin/service rsyslog start && tail -f /dev/null"
