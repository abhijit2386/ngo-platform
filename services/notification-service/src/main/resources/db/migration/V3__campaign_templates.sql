-- Campaign approval workflow templates (en reviewed; hi/mr drafts for native review).
INSERT INTO notification_templates (id, code, channel, language, category, subject, body) VALUES
('2e564073-f369-5131-af23-8dbe8fdf7cc0', 'CAMPAIGN_SUBMITTED', 'EMAIL', 'en', 'TEAM', 'Approval needed: {{campaignTitle}}', '{{memberName}} sent the campaign "{{campaignTitle}}" for approval.

Review it in the app (More > Campaigns). It goes live for donors only after you approve it.

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('4ec00854-0437-5574-a8a1-8b94c092a709', 'CAMPAIGN_SUBMITTED', 'IN_APP', 'en', 'TEAM', 'Campaign awaiting approval', '{{memberName}} sent "{{campaignTitle}}" for approval.'),
('966dd35f-852a-5e92-92f2-ae0803faa413', 'CAMPAIGN_SUBMITTED', 'EMAIL', 'hi', 'TEAM', 'मंज़ूरी चाहिए: {{campaignTitle}}', '{{memberName}} ने अभियान "{{campaignTitle}}" मंज़ूरी के लिए भेजा है।

ऐप में इसकी समीक्षा करें (अधिक > अभियान)। आपकी मंज़ूरी के बाद ही यह दानदाताओं को दिखेगा।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('1959249d-f671-5e04-a348-d578f818de79', 'CAMPAIGN_SUBMITTED', 'IN_APP', 'hi', 'TEAM', 'अभियान मंज़ूरी की प्रतीक्षा में', '{{memberName}} ने "{{campaignTitle}}" मंज़ूरी के लिए भेजा।'),
('74af2971-abe9-505d-9c27-b3ef2ee9e69e', 'CAMPAIGN_SUBMITTED', 'EMAIL', 'mr', 'TEAM', 'मंजुरी हवी: {{campaignTitle}}', '{{memberName}} यांनी "{{campaignTitle}}" ही मोहीम मंजुरीसाठी पाठवली आहे.

ॲपमध्ये तपासा (अधिक > मोहिमा). तुमच्या मंजुरीनंतरच ती देणगीदारांना दिसेल.

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('658ab2c8-58bb-52d8-8de8-66b740211ae7', 'CAMPAIGN_SUBMITTED', 'IN_APP', 'mr', 'TEAM', 'मोहीम मंजुरीच्या प्रतीक्षेत', '{{memberName}} यांनी "{{campaignTitle}}" मंजुरीसाठी पाठवली.'),
('8d47c794-ff5d-59e0-826f-36c687ccb82a', 'CAMPAIGN_APPROVED', 'EMAIL', 'en', 'TEAM', 'Your campaign is live: {{campaignTitle}}', 'Good news! "{{campaignTitle}}" was approved and is now visible to donors.

Share it from the app to reach more supporters.

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('5b05ff41-2551-5d6f-9e11-03ad0858d7a6', 'CAMPAIGN_APPROVED', 'IN_APP', 'en', 'TEAM', 'Campaign approved', '"{{campaignTitle}}" is now live for donors.'),
('424d495e-1868-583e-915a-924a4a4f479b', 'CAMPAIGN_APPROVED', 'EMAIL', 'hi', 'TEAM', 'आपका अभियान शुरू हो गया: {{campaignTitle}}', 'खुशखबरी! "{{campaignTitle}}" को मंज़ूरी मिल गई है और अब यह दानदाताओं को दिख रहा है।

अधिक समर्थकों तक पहुँचने के लिए इसे ऐप से साझा करें।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('4b55a1ce-2281-5e9e-b78b-7c0f51ff4781', 'CAMPAIGN_APPROVED', 'IN_APP', 'hi', 'TEAM', 'अभियान मंज़ूर', '"{{campaignTitle}}" अब दानदाताओं के लिए शुरू है।'),
('7c5bad41-2ba0-5735-99d1-76c2d1c37d8e', 'CAMPAIGN_APPROVED', 'EMAIL', 'mr', 'TEAM', 'तुमची मोहीम सुरू झाली: {{campaignTitle}}', 'आनंदाची बातमी! "{{campaignTitle}}" मंजूर झाली असून आता देणगीदारांना दिसत आहे.

अधिक लोकांपर्यंत पोहोचण्यासाठी ॲपमधून शेअर करा.

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('ca483467-b649-5a18-aeae-3d8f3e4122a4', 'CAMPAIGN_APPROVED', 'IN_APP', 'mr', 'TEAM', 'मोहीम मंजूर', '"{{campaignTitle}}" आता देणगीदारांसाठी सुरू आहे.'),
('b4d35536-15f8-53f4-874f-b4432b52a0ff', 'CAMPAIGN_REJECTED', 'EMAIL', 'en', 'TEAM', 'Changes requested: {{campaignTitle}}', 'An administrator sent "{{campaignTitle}}" back for changes.

What to change: {{reason}}

Edit the campaign in the app and send it for approval again.

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('db6b47da-74d3-5adf-85ee-f5c833dc613c', 'CAMPAIGN_REJECTED', 'IN_APP', 'en', 'TEAM', 'Campaign needs changes', '"{{campaignTitle}}": {{reason}}'),
('e358f531-8e2d-567e-830c-80b59fa588b8', 'CAMPAIGN_REJECTED', 'EMAIL', 'hi', 'TEAM', 'बदलाव ज़रूरी: {{campaignTitle}}', 'एक प्रशासक ने "{{campaignTitle}}" को बदलाव के लिए लौटाया है।

क्या बदलना है: {{reason}}

ऐप में अभियान संपादित करें और फिर से मंज़ूरी के लिए भेजें।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('d71576df-30bc-543a-8fd6-7ae11947210b', 'CAMPAIGN_REJECTED', 'IN_APP', 'hi', 'TEAM', 'अभियान में बदलाव चाहिए', '"{{campaignTitle}}": {{reason}}'),
('4fa08a76-3917-519f-b845-fb95b072af93', 'CAMPAIGN_REJECTED', 'EMAIL', 'mr', 'TEAM', 'बदल आवश्यक: {{campaignTitle}}', 'एका प्रशासकाने "{{campaignTitle}}" बदलांसाठी परत पाठवली आहे.

काय बदलायचे: {{reason}}

ॲपमध्ये मोहीम संपादित करा आणि पुन्हा मंजुरीसाठी पाठवा.

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('a07dc310-b563-57c3-b130-c06d28108b44', 'CAMPAIGN_REJECTED', 'IN_APP', 'mr', 'TEAM', 'मोहिमेत बदल हवेत', '"{{campaignTitle}}": {{reason}}');
