-- Seed templates (en reviewed; hi/mr are drafts for native review - docs/business/LOCALIZATION.md).
-- Placeholders: {{name}}. Admins edit these via /api/v1/admin/notification-templates (ADMIN-016).
INSERT INTO notification_templates (id, code, channel, language, category, subject, body) VALUES
('e282a0d7-31bf-52c8-a284-19393145112e', 'NGO_APPROVED', 'EMAIL', 'en', 'ACCOUNT', '{{ngoName}} is verified', 'Good news! {{ngoName}} has been verified on Seva Setu.

{{message}}

Your 30-day free trial has started. Open the app to invite your team and start recording donations.

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('c544713c-0c6f-5c75-8334-004da73630ac', 'NGO_APPROVED', 'IN_APP', 'en', 'ACCOUNT', '{{ngoName}} is verified', 'Your free trial has started. {{message}}'),
('7e6c3e06-0b32-50f8-8448-5dff120ed6ee', 'NGO_APPROVED', 'EMAIL', 'hi', 'ACCOUNT', '{{ngoName}} सत्यापित हो गया', 'खुशखबरी! {{ngoName}} सेवा सेतु पर सत्यापित हो गया है।

{{message}}

आपका 30 दिन का निःशुल्क परीक्षण शुरू हो गया है। अपनी टीम को आमंत्रित करने और दान दर्ज करने के लिए ऐप खोलें।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('7e675836-dacf-5d80-bcb2-daf0a9b0c83e', 'NGO_APPROVED', 'IN_APP', 'hi', 'ACCOUNT', '{{ngoName}} सत्यापित हो गया', 'आपका निःशुल्क परीक्षण शुरू हो गया है। {{message}}'),
('ce69fea2-c9f7-5c1a-931c-c98f4fbe6777', 'NGO_APPROVED', 'EMAIL', 'mr', 'ACCOUNT', '{{ngoName}} पडताळले गेले', 'आनंदाची बातमी! {{ngoName}} सेवा सेतूवर पडताळले गेले आहे.

{{message}}

तुमची 30 दिवसांची मोफत चाचणी सुरू झाली आहे. टीमला आमंत्रित करण्यासाठी आणि देणग्या नोंदवण्यासाठी ॲप उघडा.

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('70f2d5e9-abbd-54dc-89a9-dbb2a1eef376', 'NGO_APPROVED', 'IN_APP', 'mr', 'ACCOUNT', '{{ngoName}} पडताळले गेले', 'तुमची मोफत चाचणी सुरू झाली आहे. {{message}}'),
('8ccafa1b-edf4-5918-862f-58bf60d679ba', 'NGO_REJECTED', 'EMAIL', 'en', 'ACCOUNT', 'Update on your registration for {{ngoName}}', 'We could not verify {{ngoName}} at this time.

Reason: {{reason}}

You can correct the details in the app and submit again.

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('12ad64d9-a88e-5548-bcf7-d5889bb64976', 'NGO_REJECTED', 'IN_APP', 'en', 'ACCOUNT', 'Registration not approved', '{{ngoName}}: {{reason}}'),
('5e9bac76-56de-54aa-aa94-094c3902739e', 'NGO_REJECTED', 'EMAIL', 'hi', 'ACCOUNT', '{{ngoName}} के पंजीकरण की जानकारी', 'हम अभी {{ngoName}} का सत्यापन नहीं कर सके।

कारण: {{reason}}

आप ऐप में विवरण सुधारकर फिर से जमा कर सकते हैं।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('69e36345-ab1a-5025-be8d-286588a95d69', 'NGO_REJECTED', 'IN_APP', 'hi', 'ACCOUNT', 'पंजीकरण स्वीकृत नहीं हुआ', '{{ngoName}}: {{reason}}'),
('185a1721-031f-5158-9147-5d68b179451e', 'NGO_REJECTED', 'EMAIL', 'mr', 'ACCOUNT', '{{ngoName}} नोंदणीबाबत माहिती', 'आम्ही सध्या {{ngoName}} पडताळू शकलो नाही.

कारण: {{reason}}

ॲपमध्ये तपशील दुरुस्त करून पुन्हा सादर करा.

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('344f17a6-df74-5b3b-b169-8498dd3bd891', 'NGO_REJECTED', 'IN_APP', 'mr', 'ACCOUNT', 'नोंदणी मंजूर झाली नाही', '{{ngoName}}: {{reason}}'),
('34f66161-272f-5d3d-b92e-b3be7880d194', 'NGO_INFO_REQUESTED', 'EMAIL', 'en', 'ACCOUNT', 'More information needed for {{ngoName}}', 'Our verification team needs a little more information about {{ngoName}}:

{{message}}

Please update your registration in the app and submit it again.

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('bff134ee-3b0a-5d54-994c-f9fa43fc3ab1', 'NGO_INFO_REQUESTED', 'IN_APP', 'en', 'ACCOUNT', 'More information needed', '{{ngoName}}: {{message}}'),
('9ed29f33-e664-595f-8b14-d33b056304d8', 'NGO_INFO_REQUESTED', 'EMAIL', 'hi', 'ACCOUNT', '{{ngoName}} के लिए और जानकारी चाहिए', 'हमारी सत्यापन टीम को {{ngoName}} के बारे में थोड़ी और जानकारी चाहिए:

{{message}}

कृपया ऐप में पंजीकरण अपडेट करके फिर से जमा करें।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('7cc74598-ded1-59b2-8aed-4973047c810b', 'NGO_INFO_REQUESTED', 'IN_APP', 'hi', 'ACCOUNT', 'और जानकारी चाहिए', '{{ngoName}}: {{message}}'),
('d19f2876-df71-501e-84a3-6f2ab046f942', 'NGO_INFO_REQUESTED', 'EMAIL', 'mr', 'ACCOUNT', '{{ngoName}} साठी अधिक माहिती हवी', 'आमच्या पडताळणी टीमला {{ngoName}} बद्दल आणखी थोडी माहिती हवी आहे:

{{message}}

कृपया ॲपमध्ये नोंदणी अद्ययावत करून पुन्हा सादर करा.

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('db005011-edc5-533f-9b3d-3fc8e9595e6c', 'NGO_INFO_REQUESTED', 'IN_APP', 'mr', 'ACCOUNT', 'अधिक माहिती हवी', '{{ngoName}}: {{message}}'),
('f994843b-1dc7-5668-ab48-afa1cacf7d6b', 'NGO_SUSPENDED', 'EMAIL', 'en', 'ACCOUNT', '{{ngoName}} has been suspended', 'The account of {{ngoName}} has been suspended on Seva Setu. Please contact support from the app for details.

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('149bd827-1e4f-58ee-b232-c617146c51dc', 'NGO_SUSPENDED', 'IN_APP', 'en', 'ACCOUNT', 'Organization suspended', '{{ngoName}} has been suspended. Contact support for details.'),
('5df49bae-602e-5715-85ed-779ff9a9466f', 'NGO_SUSPENDED', 'EMAIL', 'hi', 'ACCOUNT', '{{ngoName}} निलंबित किया गया', 'सेवा सेतु पर {{ngoName}} का खाता निलंबित कर दिया गया है। विवरण के लिए ऐप से सहायता से संपर्क करें।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('5141d3df-197e-5153-b966-6f40d38b71ae', 'NGO_SUSPENDED', 'IN_APP', 'hi', 'ACCOUNT', 'संस्था निलंबित', '{{ngoName}} निलंबित है। विवरण के लिए सहायता से संपर्क करें।'),
('467ec415-1d94-5779-9b56-048d96bcb30a', 'NGO_SUSPENDED', 'EMAIL', 'mr', 'ACCOUNT', '{{ngoName}} निलंबित केले', 'सेवा सेतूवर {{ngoName}} चे खाते निलंबित केले आहे. तपशिलासाठी ॲपमधून सहाय्याशी संपर्क साधा.

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('fd35c3ef-32bb-582b-9e97-8316c2bb4d6d', 'NGO_SUSPENDED', 'IN_APP', 'mr', 'ACCOUNT', 'संस्था निलंबित', '{{ngoName}} निलंबित आहे. तपशिलासाठी सहाय्याशी संपर्क साधा.'),
('13170c82-03fc-5a81-970e-3cbd202f7131', 'STAFF_INVITED', 'EMAIL', 'en', 'TEAM', 'You''re invited to join {{ngoName}}', '{{ngoName}} has invited you to join their team on Seva Setu as {{role}}.

Install the Seva Setu app and sign in with this e-mail address to accept. The invitation is valid until {{expiresOn}}.

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('ccddd6c8-ad79-5152-ad2b-0bca51fc58f1', 'STAFF_INVITED', 'IN_APP', 'en', 'TEAM', 'Invitation from {{ngoName}}', 'You are invited to join as {{role}}. Valid until {{expiresOn}}.'),
('91e4fdc7-10c7-54e7-9e9c-96a157c9e089', 'STAFF_INVITED', 'EMAIL', 'hi', 'TEAM', '{{ngoName}} से जुड़ने का निमंत्रण', '{{ngoName}} ने आपको सेवा सेतु पर अपनी टीम में {{role}} के रूप में जुड़ने के लिए आमंत्रित किया है।

स्वीकार करने के लिए सेवा सेतु ऐप इंस्टॉल करें और इसी ई-मेल पते से साइन इन करें। निमंत्रण {{expiresOn}} तक मान्य है।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('22819648-4070-5b5f-a574-a2f2f19fefae', 'STAFF_INVITED', 'IN_APP', 'hi', 'TEAM', '{{ngoName}} से निमंत्रण', 'आपको {{role}} के रूप में आमंत्रित किया गया है। {{expiresOn}} तक मान्य।'),
('a98a12af-e5a5-5aab-bf27-9ff1d0cf91b1', 'STAFF_INVITED', 'EMAIL', 'mr', 'TEAM', '{{ngoName}} मध्ये सामील होण्याचे आमंत्रण', '{{ngoName}} ने तुम्हाला सेवा सेतूवर त्यांच्या टीममध्ये {{role}} म्हणून सामील होण्यासाठी आमंत्रित केले आहे.

स्वीकारण्यासाठी सेवा सेतू ॲप इन्स्टॉल करा आणि याच ई-मेलने साइन इन करा. आमंत्रण {{expiresOn}} पर्यंत वैध आहे.

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('73b81667-7595-5de9-8ed8-afe109db77f3', 'STAFF_INVITED', 'IN_APP', 'mr', 'TEAM', '{{ngoName}} कडून आमंत्रण', 'तुम्हाला {{role}} म्हणून आमंत्रित केले आहे. {{expiresOn}} पर्यंत वैध.'),
('b59f0e9c-3122-5335-abf3-ba682afecc45', 'STAFF_JOINED', 'EMAIL', 'en', 'TEAM', '{{memberName}} joined {{ngoName}}', '{{memberName}} accepted the invitation and joined {{ngoName}} as {{role}}.

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('c9bc290f-07eb-5a7d-ba0b-eee75a8e2745', 'STAFF_JOINED', 'IN_APP', 'en', 'TEAM', 'New team member', '{{memberName}} joined as {{role}}.'),
('b968b890-1cc9-520c-8d24-deebaa1e56ce', 'STAFF_JOINED', 'EMAIL', 'hi', 'TEAM', '{{memberName}} {{ngoName}} से जुड़े', '{{memberName}} ने निमंत्रण स्वीकार किया और {{ngoName}} में {{role}} के रूप में जुड़ गए।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('500a9341-abc0-5198-a9a5-5fb30251563e', 'STAFF_JOINED', 'IN_APP', 'hi', 'TEAM', 'नया टीम सदस्य', '{{memberName}} {{role}} के रूप में जुड़े।'),
('a56e062a-86e4-52c7-ad02-5d7517de3912', 'STAFF_JOINED', 'EMAIL', 'mr', 'TEAM', '{{memberName}} {{ngoName}} मध्ये सामील', '{{memberName}} यांनी आमंत्रण स्वीकारले आणि {{ngoName}} मध्ये {{role}} म्हणून सामील झाले.

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('2505bf4f-3583-59bb-9c57-00a4124ca845', 'STAFF_JOINED', 'IN_APP', 'mr', 'TEAM', 'नवीन टीम सदस्य', '{{memberName}} {{role}} म्हणून सामील झाले.'),
('c5c03a89-34f6-5d7c-9a3f-bea88ae07759', 'SUBSCRIPTION_RENEWAL_DUE', 'EMAIL', 'en', 'BILLING', '{{plan}} plan: {{daysLeft}} days left', 'The {{plan}} plan of {{ngoName}} ends on {{periodEndOn}} ({{daysLeft}} days left).

Renew in the app (More > Subscription) to keep donations, receipts and your team running without interruption.

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('8fa746b0-ee78-5771-977d-beb4aed65c9d', 'SUBSCRIPTION_RENEWAL_DUE', 'IN_APP', 'en', 'BILLING', '{{daysLeft}} days left on your plan', '{{plan}} ends on {{periodEndOn}}. Renew to avoid interruption.'),
('09506ce4-0d65-51dd-8a80-28f8a443b969', 'SUBSCRIPTION_RENEWAL_DUE', 'EMAIL', 'hi', 'BILLING', '{{plan}} योजना: {{daysLeft}} दिन बाकी', '{{ngoName}} की {{plan}} योजना {{periodEndOn}} को समाप्त होगी ({{daysLeft}} दिन बाकी)।

दान, रसीदें और टीम बिना रुकावट चलाने के लिए ऐप में नवीनीकरण करें (अधिक > सदस्यता)।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('526c3441-4ed3-5b73-b4f0-18005bb060f3', 'SUBSCRIPTION_RENEWAL_DUE', 'IN_APP', 'hi', 'BILLING', 'योजना में {{daysLeft}} दिन बाकी', '{{plan}} {{periodEndOn}} को समाप्त होगी। रुकावट से बचने के लिए नवीनीकरण करें।'),
('3b701e32-f740-5183-aade-0e9cd55abf97', 'SUBSCRIPTION_RENEWAL_DUE', 'EMAIL', 'mr', 'BILLING', '{{plan}} योजना: {{daysLeft}} दिवस बाकी', '{{ngoName}} ची {{plan}} योजना {{periodEndOn}} रोजी संपेल ({{daysLeft}} दिवस बाकी).

देणग्या, पावत्या आणि टीम विनाव्यत्यय सुरू ठेवण्यासाठी ॲपमध्ये नूतनीकरण करा (अधिक > सदस्यता).

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('79322c24-f156-53a9-a508-2b3b1b656727', 'SUBSCRIPTION_RENEWAL_DUE', 'IN_APP', 'mr', 'BILLING', 'योजनेचे {{daysLeft}} दिवस बाकी', '{{plan}} {{periodEndOn}} रोजी संपेल. व्यत्यय टाळण्यासाठी नूतनीकरण करा.'),
('52a1a0fd-711f-50b8-b377-c96a76f6abfd', 'SUBSCRIPTION_EXPIRED', 'EMAIL', 'en', 'BILLING', 'Your {{plan}} plan has expired', 'The {{plan}} plan of {{ngoName}} has expired and the account is in its grace period. Renew in the app to avoid suspension.

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('8abf3e3e-124e-5405-b231-5ae94bbed592', 'SUBSCRIPTION_EXPIRED', 'IN_APP', 'en', 'BILLING', 'Plan expired', '{{plan}} has expired. Renew to avoid suspension.'),
('421cb4b5-91f8-5bbb-a0da-bc47f97daa4f', 'SUBSCRIPTION_EXPIRED', 'EMAIL', 'hi', 'BILLING', 'आपकी {{plan}} योजना समाप्त हो गई', '{{ngoName}} की {{plan}} योजना समाप्त हो गई है और खाता अनुग्रह अवधि में है। निलंबन से बचने के लिए ऐप में नवीनीकरण करें।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('17f15065-63c2-5a7c-8969-c7cb6a984152', 'SUBSCRIPTION_EXPIRED', 'IN_APP', 'hi', 'BILLING', 'योजना समाप्त', '{{plan}} समाप्त हो गई। निलंबन से बचने के लिए नवीनीकरण करें।'),
('4033a7b9-fa89-593e-918e-f6256d7d7228', 'SUBSCRIPTION_EXPIRED', 'EMAIL', 'mr', 'BILLING', 'तुमची {{plan}} योजना संपली', '{{ngoName}} ची {{plan}} योजना संपली आहे आणि खाते सवलत कालावधीत आहे. निलंबन टाळण्यासाठी ॲपमध्ये नूतनीकरण करा.

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('ebcfa889-1414-5214-920c-5c2fd8406ae9', 'SUBSCRIPTION_EXPIRED', 'IN_APP', 'mr', 'BILLING', 'योजना संपली', '{{plan}} संपली. निलंबन टाळण्यासाठी नूतनीकरण करा.'),
('ddbf0423-3b9d-5e5c-88c7-5bfc0652da44', 'SUBSCRIPTION_SUSPENDED', 'EMAIL', 'en', 'BILLING', '{{ngoName}} subscription suspended', 'The subscription of {{ngoName}} is suspended because it was not renewed. Your data is safe; renew in the app to restore access.

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('85220ef4-ceda-56b9-9efa-9a66f80f6330', 'SUBSCRIPTION_SUSPENDED', 'IN_APP', 'en', 'BILLING', 'Subscription suspended', 'Renew to restore access. Your data is safe.'),
('6e3c7539-f77f-5f8f-8977-f85d802461ce', 'SUBSCRIPTION_SUSPENDED', 'EMAIL', 'hi', 'BILLING', '{{ngoName}} की सदस्यता निलंबित', 'नवीनीकरण न होने के कारण {{ngoName}} की सदस्यता निलंबित है। आपका डेटा सुरक्षित है; पहुँच वापस पाने के लिए ऐप में नवीनीकरण करें।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('80f9ebf3-19ea-515c-86f0-d4c08bf6fe9b', 'SUBSCRIPTION_SUSPENDED', 'IN_APP', 'hi', 'BILLING', 'सदस्यता निलंबित', 'पहुँच वापस पाने के लिए नवीनीकरण करें। आपका डेटा सुरक्षित है।'),
('535c74b2-3a2d-5f63-a31b-d917e985bc53', 'SUBSCRIPTION_SUSPENDED', 'EMAIL', 'mr', 'BILLING', '{{ngoName}} ची सदस्यता निलंबित', 'नूतनीकरण न झाल्याने {{ngoName}} ची सदस्यता निलंबित आहे. तुमचा डेटा सुरक्षित आहे; प्रवेश परत मिळवण्यासाठी ॲपमध्ये नूतनीकरण करा.

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('2333bc9f-716e-5ea1-8090-9b16bee165ed', 'SUBSCRIPTION_SUSPENDED', 'IN_APP', 'mr', 'BILLING', 'सदस्यता निलंबित', 'प्रवेश परत मिळवण्यासाठी नूतनीकरण करा. तुमचा डेटा सुरक्षित आहे.'),
('f5b6bbf8-83d2-5775-8f5d-b388e726e6a6', 'SUBSCRIPTION_PAYMENT_FAILED', 'EMAIL', 'en', 'BILLING', 'Payment of ₹{{amount}} failed', 'A subscription payment of ₹{{amount}} for {{ngoName}} did not go through. No money was taken. Please try again from the app.

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('7722ea33-1668-56a8-be87-ce5038017c11', 'SUBSCRIPTION_PAYMENT_FAILED', 'IN_APP', 'en', 'BILLING', 'Payment failed', '₹{{amount}} for {{ngoName}} did not go through. Please try again.'),
('da64df75-ffb5-5cb8-a3ad-a65f027cdc70', 'SUBSCRIPTION_PAYMENT_FAILED', 'EMAIL', 'hi', 'BILLING', '₹{{amount}} का भुगतान विफल', '{{ngoName}} के लिए ₹{{amount}} का सदस्यता भुगतान पूरा नहीं हुआ। कोई राशि नहीं कटी। कृपया ऐप से फिर प्रयास करें।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('ed0f3466-c855-57fe-8a5f-168ad6072936', 'SUBSCRIPTION_PAYMENT_FAILED', 'IN_APP', 'hi', 'BILLING', 'भुगतान विफल', '{{ngoName}} के लिए ₹{{amount}} का भुगतान नहीं हुआ। फिर प्रयास करें।'),
('f246485e-c846-52ae-b0c8-1421d783e00b', 'SUBSCRIPTION_PAYMENT_FAILED', 'EMAIL', 'mr', 'BILLING', '₹{{amount}} चे पेमेंट अयशस्वी', '{{ngoName}} साठी ₹{{amount}} चे सदस्यता पेमेंट पूर्ण झाले नाही. कोणतीही रक्कम कापली गेली नाही. कृपया ॲपमधून पुन्हा प्रयत्न करा.

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('fa10b2f1-d6fb-5c4b-b748-68a17f9368a8', 'SUBSCRIPTION_PAYMENT_FAILED', 'IN_APP', 'mr', 'BILLING', 'पेमेंट अयशस्वी', '{{ngoName}} साठी ₹{{amount}} चे पेमेंट झाले नाही. पुन्हा प्रयत्न करा.');
