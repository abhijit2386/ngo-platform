package org.ngoplatform.identity.app;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.ngoplatform.commons.security.Permissions;
import org.ngoplatform.identity.domain.NgoMembership;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Keeps permissions / role_permissions tables in sync with the code-defined catalogue (idempotent). */
@Component
public class RoleCatalog implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public RoleCatalog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (String p : Permissions.ALL) {
            jdbc.update("insert into permissions(code, description) values (?, ?) on conflict (code) do nothing",
                    p, p.replace('_', ' ').toLowerCase());
        }
        for (Map.Entry<String, Set<String>> e : Permissions.ROLE_TEMPLATES.entrySet()) {
            for (String p : e.getValue()) {
                jdbc.update("insert into role_permissions(role_code, permission_code) values (?, ?) on conflict do nothing",
                        e.getKey(), p);
            }
        }
    }

    public static boolean isKnownRole(String role) { return Permissions.ROLE_TEMPLATES.containsKey(role); }

    /** Effective permissions = role template + explicit grants - explicit revocations. Admins always keep all. */
    public static Set<String> effective(NgoMembership m) {
        if (m.isAdmin()) return Permissions.ALL;
        Set<String> out = new HashSet<>(Permissions.ROLE_TEMPLATES.getOrDefault(m.getRole(), Set.of()));
        m.getOverrides().forEach((perm, granted) -> {
            if (Boolean.TRUE.equals(granted)) out.add(perm); else out.remove(perm);
        });
        return Set.copyOf(out);
    }
}
