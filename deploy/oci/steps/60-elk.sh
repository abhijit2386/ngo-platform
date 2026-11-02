#!/usr/bin/env bash
# Logs: Elasticsearch + Kibana + Filebeat (Elastic 9.x packages), all on 127.0.0.1. Filebeat reads the services' ECS
# JSON log files (/var/log/yogdaan/*.log) and Caddy's access log. Kibana only through SSH:
#   ssh -L 5601:127.0.0.1:5601 opc@<server>   then http://localhost:5601  (user elastic, password in secrets)
source "$(dirname "$0")/../lib.sh"
load_env
ES_PASS_FILE="$SECRETS_DIR/elastic-password"; KB_PASS_FILE="$SECRETS_DIR/kibana-system-password"
CA=/etc/elasticsearch/certs/http_ca.crt

if [ ! -f /etc/yum.repos.d/elastic.repo ]; then
  rpm --import https://artifacts.elastic.co/GPG-KEY-elasticsearch
  cat > /etc/yum.repos.d/elastic.repo <<REPO
[elastic]
name=Elastic ${ELASTIC_MAJOR}.x packages
baseurl=https://artifacts.elastic.co/packages/${ELASTIC_MAJOR}.x/yum
gpgcheck=1
gpgkey=https://artifacts.elastic.co/GPG-KEY-elasticsearch
enabled=0
type=rpm-md
REPO
fi
log "install Elasticsearch, Kibana, Filebeat ${ELASTIC_MAJOR}.x"
dnf -y -q --enablerepo=elastic install elasticsearch kibana filebeat >/dev/null

log "Elasticsearch: single node, localhost, 2 GB heap, keep 30 days of logs"
ESYML=/etc/elasticsearch/elasticsearch.yml
grep -q '^network.host:' "$ESYML" && sed -i 's/^network.host:.*/network.host: 127.0.0.1/' "$ESYML" || echo 'network.host: 127.0.0.1' >> "$ESYML"
grep -q '^http.host:' "$ESYML" && sed -i 's/^http.host:.*/http.host: 127.0.0.1/' "$ESYML" || true
grep -q '^cluster.name:' "$ESYML" || echo 'cluster.name: yogdaan-logs' >> "$ESYML"
printf -- '-Xms2g\n-Xmx2g\n' > /etc/elasticsearch/jvm.options.d/heap.options
systemctl daemon-reload
systemctl enable elasticsearch >/dev/null
systemctl restart elasticsearch
[ -f "$CA" ] || die "Elasticsearch did not create its certificates ($CA)"
end=$((SECONDS + 180))
until curl -s --cacert "$CA" -o /dev/null https://127.0.0.1:9200; do
  [ $SECONDS -lt $end ] || die "Elasticsearch did not start (journalctl -u elasticsearch; /var/log/elasticsearch/)"
  sleep 3
done
if [ ! -s "$ES_PASS_FILE" ]; then
  /usr/share/elasticsearch/bin/elasticsearch-reset-password -u elastic -b -s > "$ES_PASS_FILE"
  /usr/share/elasticsearch/bin/elasticsearch-reset-password -u kibana_system -b -s > "$KB_PASS_FILE"
  chmod 0600 "$ES_PASS_FILE" "$KB_PASS_FILE"
fi
ES_PASS=$(tr -d '\n' < "$ES_PASS_FILE"); KB_PASS=$(tr -d '\n' < "$KB_PASS_FILE")
es() { curl -fsS --cacert "$CA" -u "elastic:$ES_PASS" -H 'Content-Type: application/json' "$@"; }
es -o /dev/null https://127.0.0.1:9200/ || die "cannot log in to Elasticsearch as elastic"
es -o /dev/null -X PUT https://127.0.0.1:9200/_ilm/policy/yogdaan-logs-30d -d '{"policy":{"phases":{
  "hot":{"actions":{"rollover":{"max_age":"1d","max_primary_shard_size":"10gb"}}},
  "delete":{"min_age":"30d","actions":{"delete":{}}}}}}'
ok "Elasticsearch ready (single node, logs kept 30 days)"

log "Kibana on 127.0.0.1:5601"
install -d -m 0750 -o root -g kibana /etc/kibana/certs
install -m 0640 -o root -g kibana "$CA" /etc/kibana/certs/http_ca.crt
cat > /etc/kibana/kibana.yml <<KB
server.host: "127.0.0.1"
server.port: 5601
server.publicBaseUrl: "http://localhost:5601"
elasticsearch.hosts: ["https://127.0.0.1:9200"]
elasticsearch.username: "kibana_system"
elasticsearch.password: "${KB_PASS}"
elasticsearch.ssl.certificateAuthorities: ["/etc/kibana/certs/http_ca.crt"]
logging.root.level: warn
KB
chmod 0640 /etc/kibana/kibana.yml; chgrp kibana /etc/kibana/kibana.yml
systemctl enable kibana >/dev/null
systemctl restart kibana

log "Filebeat: service logs (ECS JSON) + Caddy access log -> data stream logs-yogdaan"
install -d -m 0750 /etc/filebeat/certs
install -m 0640 "$CA" /etc/filebeat/certs/http_ca.crt
cat > /etc/filebeat/filebeat.yml <<FB
filebeat.inputs:
  - type: filestream
    id: yogdaan-services
    paths: ["${LOG_DIR}/*.log"]
    parsers:
      - ndjson:
          target: ""
          overwrite_keys: true
          add_error_key: true
          expand_keys: true
    fields_under_root: true
    fields: { event.dataset: yogdaan.services }
  - type: filestream
    id: caddy-access
    paths: ["/var/log/caddy/access.log"]
    parsers:
      - ndjson:
          target: caddy
          add_error_key: true
    fields_under_root: true
    fields: { event.dataset: caddy.access }
output.elasticsearch:
  hosts: ["https://127.0.0.1:9200"]
  username: "elastic"
  password: "\${ES_PWD}"
  ssl.certificate_authorities: ["/etc/filebeat/certs/http_ca.crt"]
  index: "logs-yogdaan-default"
setup.ilm.enabled: false
setup.template.enabled: false
processors:
  - add_host_metadata: ~
logging.level: warning
FB
chmod 0600 /etc/filebeat/filebeat.yml
filebeat keystore list >/dev/null 2>&1 || filebeat keystore create --force >/dev/null
printf '%s' "$ES_PASS" | filebeat keystore add ES_PWD --stdin --force >/dev/null
es -o /dev/null -X PUT https://127.0.0.1:9200/_index_template/logs-yogdaan -d '{"index_patterns":["logs-yogdaan-*"],
  "data_stream":{},"priority":300,"composed_of":["logs@mappings","logs@settings","ecs@mappings"],
  "template":{"settings":{"index.lifecycle.name":"yogdaan-logs-30d"}}}'
filebeat test config >/dev/null || die "filebeat config invalid (filebeat test config)"
systemctl enable filebeat >/dev/null
systemctl restart filebeat
wait_http http://127.0.0.1:5601/api/status 240 || warn "Kibana still starting (systemctl status kibana)"
ok "ELK ready. Kibana: ssh -L 5601:127.0.0.1:5601 <user>@<server>, http://localhost:5601, user elastic"
ok "elastic password: sudo cat $ES_PASS_FILE ; in Kibana create a data view for 'logs-yogdaan-*'"
