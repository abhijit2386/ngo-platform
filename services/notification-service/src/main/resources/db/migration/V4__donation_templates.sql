-- Donation templates: thank-you to donors, new-donation note to NGO admins (en reviewed; hi/mr drafts).
INSERT INTO notification_templates (id, code, channel, language, category, subject, body) VALUES
('8472c49b-7fb3-554a-85de-1714ac317bdc', 'DONATION_THANKS', 'EMAIL', 'en', 'DONATIONS', 'Thank you for your donation of {{amount}}', 'Thank you! Your donation of {{amount}} to {{ngoName}} for "{{campaignTitle}}" was received.

Your receipt will follow by e-mail. You can see all your donations in the app (Donations).

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('86b7b929-4457-56ca-a718-30615334528b', 'DONATION_THANKS', 'IN_APP', 'en', 'DONATIONS', 'Thank you for donating', '{{amount}} to {{ngoName}} for "{{campaignTitle}}" was received.'),
('6b91adaf-a0a1-510a-b0ad-a3a42c9ab096', 'DONATION_THANKS', 'EMAIL', 'hi', 'DONATIONS', '{{amount}} के दान के लिए धन्यवाद', 'धन्यवाद! {{ngoName}} को "{{campaignTitle}}" के लिए आपका {{amount}} का दान प्राप्त हुआ।

आपकी रसीद ई-मेल से आएगी। अपने सभी दान ऐप में देखें (दान)।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('69034171-b1c2-5932-8354-bebdfbfe935f', 'DONATION_THANKS', 'IN_APP', 'hi', 'DONATIONS', 'दान के लिए धन्यवाद', '{{ngoName}} को "{{campaignTitle}}" के लिए {{amount}} प्राप्त हुए।'),
('165c8d86-e979-58bd-b4bd-a87733754bee', 'DONATION_THANKS', 'EMAIL', 'mr', 'DONATIONS', '{{amount}} देणगीबद्दल धन्यवाद', 'धन्यवाद! {{ngoName}} यांना "{{campaignTitle}}" साठी तुमची {{amount}} ची देणगी मिळाली.

तुमची पावती ई-मेलने येईल. तुमच्या सर्व देणग्या ॲपमध्ये पहा (देणग्या).

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('b7f357cd-6d73-58cf-b420-5eead074010f', 'DONATION_THANKS', 'IN_APP', 'mr', 'DONATIONS', 'देणगीबद्दल धन्यवाद', '{{ngoName}} यांना "{{campaignTitle}}" साठी {{amount}} मिळाले.'),
('79dfb7d2-ac5f-552d-bee2-14c9f451947e', 'DONATION_RECEIVED', 'EMAIL', 'en', 'DONATIONS', 'New donation: {{amount}}', 'A donation of {{amount}} was received for "{{campaignTitle}}".

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('0fbd79ca-b166-57fa-8042-66824619f41c', 'DONATION_RECEIVED', 'IN_APP', 'en', 'DONATIONS', 'New donation: {{amount}}', '{{amount}} received for "{{campaignTitle}}".'),
('877cded2-e693-52a7-8fc7-0e3fecf98e61', 'DONATION_RECEIVED', 'EMAIL', 'hi', 'DONATIONS', 'नया दान: {{amount}}', '"{{campaignTitle}}" के लिए {{amount}} का दान प्राप्त हुआ।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('bbbbdb25-1097-58e0-a623-6fa4e6aff890', 'DONATION_RECEIVED', 'IN_APP', 'hi', 'DONATIONS', 'नया दान: {{amount}}', '"{{campaignTitle}}" के लिए {{amount}} प्राप्त हुए।'),
('55c882a8-72c5-5e8d-91e7-8df12501182d', 'DONATION_RECEIVED', 'EMAIL', 'mr', 'DONATIONS', 'नवीन देणगी: {{amount}}', '"{{campaignTitle}}" साठी {{amount}} ची देणगी मिळाली.

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('fbd3343d-1ce9-59ea-abdb-1d17b26d9525', 'DONATION_RECEIVED', 'IN_APP', 'mr', 'DONATIONS', 'नवीन देणगी: {{amount}}', '"{{campaignTitle}}" साठी {{amount}} मिळाले.');
