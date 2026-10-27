-- Prices for the mock providers, matching the gateway's application.yml so the demo invoice lines up with
-- the gateway's own cost estimates. Real provider prices are added through POST /admin/prices.
INSERT INTO model_prices (provider, provider_model, input_per_1k_usd, output_per_1k_usd, effective_from) VALUES
    ('mock-alpha', 'mock-alpha-small', 0.00015, 0.0006, '2026-01-01T00:00:00Z'),
    ('mock-alpha', 'mock-alpha-embed', 0.00002, 0,      '2026-01-01T00:00:00Z'),
    ('mock-beta',  'mock-beta-small',  0.0003,  0.0012, '2026-01-01T00:00:00Z');
