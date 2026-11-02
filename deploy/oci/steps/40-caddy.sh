#!/usr/bin/env bash
# Caddy: HTTPS (Let's Encrypt, automatic renewal) for DOMAIN, API_HOST and AUTH_HOST -> gateway / Keycloak on
# localhost. Internal paths (/internal, /actuator) and Keycloak's admin console are not reachable from the internet.
# Needs: DNS A records for the three names pointing to this server, ports 80 and 443 open in OCI.
source "$(dirname "$0")/../lib.sh"
load_env
require_env DOMAIN API_HOST AUTH_HOST ACME_EMAIL
if ! rpm -q caddy >/dev/null 2>&1; then
  log "install Caddy"
  dnf_install 'dnf-command(copr)'
  dnf -y -q copr enable @caddy/caddy >/dev/null
  dnf_install caddy
fi
setsebool -P httpd_can_network_connect 1 2>/dev/null || true   # SELinux: let the proxy reach local ports

cat > /etc/caddy/Caddyfile <<CADDY
{
	email ${ACME_EMAIL}
}

(common) {
	encode gzip
	header {
		Strict-Transport-Security "max-age=31536000"
		X-Content-Type-Options nosniff
		Referrer-Policy strict-origin-when-cross-origin
		-Server
	}
	log {
		output file /var/log/caddy/access.log {
			roll_size 20MiB
			roll_keep 10
		}
	}
}

${API_HOST} {
	import common
	@internal path /internal/* /actuator /actuator/*
	respond @internal 404
	request_body {
		max_size 25MB
	}
	reverse_proxy 127.0.0.1:8080
}

${DOMAIN}, www.${DOMAIN} {
	import common
	@internal path /internal/* /actuator /actuator/*
	respond @internal 404
	reverse_proxy 127.0.0.1:8080
}

${AUTH_HOST} {
	import common
	@admin path /admin /admin/*
	respond @admin 404
	reverse_proxy 127.0.0.1:8180
}
CADDY
install -d -o caddy -g caddy /var/log/caddy
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1 || die "Caddyfile invalid: caddy validate --config /etc/caddy/Caddyfile"
systemctl enable caddy >/dev/null
systemctl restart caddy
ok "Caddy running; certificates are fetched on the first request to each name (DNS must already point here)"
if wait_http "https://$AUTH_HOST/realms/${KEYCLOAK_REALM:-ngo}/.well-known/openid-configuration" 90; then
  ok "https://$AUTH_HOST answers with a valid certificate"
else
  warn "https://$AUTH_HOST not reachable yet: check DNS (dig +short $AUTH_HOST), OCI ports 80/443, journalctl -u caddy"
fi
