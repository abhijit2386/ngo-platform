-- Document storage per NGO (owner decision 2026-11-01): Community 2 GB, Growth 10 GB, Professional 50 GB, Enterprise 100 GB.
INSERT INTO plan_entitlements (plan_code, key, value) VALUES
 ('COMMUNITY','STORAGE_MB_MAX','2048'),('GROWTH','STORAGE_MB_MAX','10240'),
 ('PROFESSIONAL','STORAGE_MB_MAX','51200'),('ENTERPRISE','STORAGE_MB_MAX','102400')
ON CONFLICT DO NOTHING;
