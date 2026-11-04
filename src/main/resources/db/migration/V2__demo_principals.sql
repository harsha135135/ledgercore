-- Local demo identities. Usernames map to these subjects in application configuration;
-- password hashes stay in external configuration.
INSERT INTO principals (id, external_subject, role) VALUES
    ('a0000000-0000-4000-8000-00000000000a', 'local:customer-a', 'CUSTOMER'),
    ('b0000000-0000-4000-8000-00000000000b', 'local:customer-b', 'CUSTOMER'),
    ('ad000000-0000-4000-8000-0000000000ad', 'local:demo-admin', 'ADMIN');
