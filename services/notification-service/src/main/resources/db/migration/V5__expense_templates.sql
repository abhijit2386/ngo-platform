-- Campaign expense approval templates (en reviewed; hi/mr drafts for native review).
INSERT INTO notification_templates (id, code, channel, language, category, subject, body) VALUES
('11cfe67b-32e0-57a0-83be-a5da91c6a91b', 'EXPENSE_SUBMITTED', 'EMAIL', 'en', 'CAMPAIGNS', 'Expense to approve: {{amount}}', '{{memberName}} added an expense of {{amount}} for "{{campaignTitle}}": {{description}}.

Open the app to check the bill and approve or reject it.

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('6db16235-e3fc-552b-8f0f-7977bc3c28cb', 'EXPENSE_SUBMITTED', 'IN_APP', 'en', 'CAMPAIGNS', 'Expense to approve: {{amount}}', '{{memberName}}: {{description}} ({{campaignTitle}})'),
('688369b0-1d81-533d-a260-ecc05b949d39', 'EXPENSE_SUBMITTED', 'EMAIL', 'hi', 'CAMPAIGNS', 'मंज़ूरी के लिए खर्च: {{amount}}', '{{memberName}} ने "{{campaignTitle}}" के लिए {{amount}} का खर्च जोड़ा: {{description}}।

बिल देखकर मंज़ूर या अस्वीकार करने के लिए ऐप खोलें।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('6ca4056c-ed18-5b05-bc30-e3fa26412499', 'EXPENSE_SUBMITTED', 'IN_APP', 'hi', 'CAMPAIGNS', 'मंज़ूरी के लिए खर्च: {{amount}}', '{{memberName}}: {{description}} ({{campaignTitle}})'),
('ddfca350-ddaf-5806-8221-33c5e3abd1e8', 'EXPENSE_SUBMITTED', 'EMAIL', 'mr', 'CAMPAIGNS', 'मंजुरीसाठी खर्च: {{amount}}', '{{memberName}} यांनी "{{campaignTitle}}" साठी {{amount}} चा खर्च नोंदवला: {{description}}.

बिल पाहून मंजूर किंवा नामंजूर करण्यासाठी ॲप उघडा.

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('36a9c756-6d71-5bf3-a0af-71ccfebd85aa', 'EXPENSE_SUBMITTED', 'IN_APP', 'mr', 'CAMPAIGNS', 'मंजुरीसाठी खर्च: {{amount}}', '{{memberName}}: {{description}} ({{campaignTitle}})'),
('e400b7cd-9f8d-59d2-9387-367fded9874b', 'EXPENSE_APPROVED', 'EMAIL', 'en', 'CAMPAIGNS', 'Expense approved: {{amount}}', 'Your expense of {{amount}} for "{{campaignTitle}}" ({{description}}) was approved. Voucher {{voucherNo}}.

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('11908620-55f3-5106-906f-4c770af2b2ca', 'EXPENSE_APPROVED', 'IN_APP', 'en', 'CAMPAIGNS', 'Expense approved', '{{amount}} for "{{campaignTitle}}" · voucher {{voucherNo}}'),
('3b6300e6-3134-5ee6-9129-54ee2ac7e42c', 'EXPENSE_APPROVED', 'EMAIL', 'hi', 'CAMPAIGNS', 'खर्च मंज़ूर: {{amount}}', '"{{campaignTitle}}" के लिए आपका {{amount}} का खर्च ({{description}}) मंज़ूर हुआ। वाउचर {{voucherNo}}।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('bec75e6f-8c94-5577-8797-7d918dbddaf0', 'EXPENSE_APPROVED', 'IN_APP', 'hi', 'CAMPAIGNS', 'खर्च मंज़ूर', '"{{campaignTitle}}" के लिए {{amount}} · वाउचर {{voucherNo}}'),
('68a907ea-7f8e-5e7e-9c7b-ab9b33567a4e', 'EXPENSE_APPROVED', 'EMAIL', 'mr', 'CAMPAIGNS', 'खर्च मंजूर: {{amount}}', '"{{campaignTitle}}" साठी तुमचा {{amount}} चा खर्च ({{description}}) मंजूर झाला. व्हाउचर {{voucherNo}}.

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('4e61d7b2-db04-5d0c-9f9e-006b6d7a3837', 'EXPENSE_APPROVED', 'IN_APP', 'mr', 'CAMPAIGNS', 'खर्च मंजूर', '"{{campaignTitle}}" साठी {{amount}} · व्हाउचर {{voucherNo}}'),
('15ba3ba6-4978-58f3-9416-0b8f22e56ef5', 'EXPENSE_REJECTED', 'EMAIL', 'en', 'CAMPAIGNS', 'Expense needs changes: {{amount}}', 'Your expense of {{amount}} for "{{campaignTitle}}" ({{description}}) was sent back.

Reason: {{reason}}

Correct it in the app and it goes back for approval.

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('52f44b29-7720-526f-b982-6284c57a44b3', 'EXPENSE_REJECTED', 'IN_APP', 'en', 'CAMPAIGNS', 'Expense sent back', '{{description}}: {{reason}}'),
('fc10a673-5f0b-5c67-8e00-818925a37f58', 'EXPENSE_REJECTED', 'EMAIL', 'hi', 'CAMPAIGNS', 'खर्च में बदलाव चाहिए: {{amount}}', '"{{campaignTitle}}" के लिए आपका {{amount}} का खर्च ({{description}}) वापस भेजा गया।

कारण: {{reason}}

ऐप में सुधारें, फिर यह दोबारा मंज़ूरी के लिए जाएगा।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('318c0b44-c0a7-5a9b-9215-b822eeb9ce77', 'EXPENSE_REJECTED', 'IN_APP', 'hi', 'CAMPAIGNS', 'खर्च वापस भेजा गया', '{{description}}: {{reason}}'),
('871771bc-1363-534a-978a-5c2aebf021be', 'EXPENSE_REJECTED', 'EMAIL', 'mr', 'CAMPAIGNS', 'खर्चात बदल हवा: {{amount}}', '"{{campaignTitle}}" साठी तुमचा {{amount}} चा खर्च ({{description}}) परत पाठवला.

कारण: {{reason}}

ॲपमध्ये दुरुस्त करा, मग तो पुन्हा मंजुरीसाठी जाईल.

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('9c056490-abab-526d-b37e-dd97f7826743', 'EXPENSE_REJECTED', 'IN_APP', 'mr', 'CAMPAIGNS', 'खर्च परत पाठवला', '{{description}}: {{reason}}');
