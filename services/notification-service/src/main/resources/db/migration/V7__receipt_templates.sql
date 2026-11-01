-- Donation receipt delivery (receipt-service, owner request 2026-11-01). Category ACCOUNT = always sent:
-- a receipt is a legal document the donor needs for tax, so it is not switched off with donation news (hi/mr drafts).
INSERT INTO notification_templates (id, code, channel, language, category, subject, body) VALUES
('febe216a-b67f-591e-aba1-4dabd3a9e93e', 'RECEIPT_ISSUED', 'EMAIL', 'en', 'ACCOUNT', 'Your donation receipt {{receiptNumber}} from {{ngoName}}', 'Thank you for your donation of {{amount}} to {{ngoName}}.

Your receipt {{receiptNumber}} is ready. Download it (link valid for 30 days):
{{downloadUrl}}

If you use the Seva Setu app, the receipt is always available under Profile > My receipts.

—
Seva Setu · This e-mail was sent because a donation receipt was issued in your name.'),
('52f89132-cdf3-5763-bc05-9932601ba1e3', 'RECEIPT_ISSUED', 'IN_APP', 'en', 'ACCOUNT', 'Your receipt is ready', '{{receiptNumber}} · {{amount}} to {{ngoName}}'),
('5d6f20b9-a0e2-5d2f-bda1-2e9e5b2910ab', 'RECEIPT_ISSUED', 'EMAIL', 'hi', 'ACCOUNT', '{{ngoName}} से आपकी दान रसीद {{receiptNumber}}', '{{ngoName}} को {{amount}} के दान के लिए धन्यवाद।

आपकी रसीद {{receiptNumber}} तैयार है। डाउनलोड करें (लिंक 30 दिन मान्य):
{{downloadUrl}}

सेवा सेतु ऐप में रसीद हमेशा प्रोफ़ाइल > मेरी रसीदें में उपलब्ध है।

—
सेवा सेतु · यह ई-मेल इसलिए भेजा गया क्योंकि आपके नाम से दान रसीद जारी हुई है।'),
('df117593-4aae-5fe7-b4c3-d1ab2d65751b', 'RECEIPT_ISSUED', 'IN_APP', 'hi', 'ACCOUNT', 'आपकी रसीद तैयार है', '{{receiptNumber}} · {{ngoName}} को {{amount}}'),
('ec65492d-37ad-5e0d-a214-8f9886996377', 'RECEIPT_ISSUED', 'EMAIL', 'mr', 'ACCOUNT', '{{ngoName}} कडून तुमची देणगी पावती {{receiptNumber}}', '{{ngoName}} यांना {{amount}} देणगीबद्दल धन्यवाद.

तुमची पावती {{receiptNumber}} तयार आहे. डाउनलोड करा (लिंक 30 दिवस वैध):
{{downloadUrl}}

सेवा सेतु ॲपमध्ये पावती नेहमी प्रोफाइल > माझ्या पावत्या येथे उपलब्ध आहे.

—
सेवा सेतु · तुमच्या नावाने देणगी पावती जारी झाल्यामुळे हा ई-मेल पाठवला आहे.'),
('c31b4631-4d13-5906-a6e6-ad646ff7cfa5', 'RECEIPT_ISSUED', 'IN_APP', 'mr', 'ACCOUNT', 'तुमची पावती तयार आहे', '{{receiptNumber}} · {{ngoName}} यांना {{amount}}');
