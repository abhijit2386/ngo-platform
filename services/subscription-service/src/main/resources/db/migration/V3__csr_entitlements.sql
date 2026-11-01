-- CSR features (owner decision 2026-11-02): Enterprise only for now. Each feature has its own key so the owner can
-- later move it to another plan (or sell it as an add-on) by changing these rows; no code change needed.
INSERT INTO plan_entitlements (plan_code, key, value) VALUES
 ('COMMUNITY','CSR_COMPLIANCE_PACK','false'),('GROWTH','CSR_COMPLIANCE_PACK','false'),
 ('PROFESSIONAL','CSR_COMPLIANCE_PACK','false'),('ENTERPRISE','CSR_COMPLIANCE_PACK','true'),
 ('COMMUNITY','CSR_UTILISATION_CERT','false'),('GROWTH','CSR_UTILISATION_CERT','false'),
 ('PROFESSIONAL','CSR_UTILISATION_CERT','false'),('ENTERPRISE','CSR_UTILISATION_CERT','true'),
 ('COMMUNITY','CSR_PROPOSALS','false'),('GROWTH','CSR_PROPOSALS','false'),
 ('PROFESSIONAL','CSR_PROPOSALS','false'),('ENTERPRISE','CSR_PROPOSALS','true')
ON CONFLICT DO NOTHING;
