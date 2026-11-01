-- Meeting invitation and action item templates (en reviewed; hi/mr drafts for native review).
INSERT INTO notification_templates (id, code, channel, language, category, subject, body) VALUES
('fe0f374b-4653-5dd6-a0f5-87dce05b15f4', 'MEETING_SCHEDULED', 'EMAIL', 'en', 'TEAM', 'Meeting: {{meetingTitle}}', 'You are invited to "{{meetingTitle}}" on {{when}}.

Open the app (More > Meetings) for the agenda and venue.

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('5d54e617-ebf4-5e29-b0bd-241ccd482457', 'MEETING_SCHEDULED', 'IN_APP', 'en', 'TEAM', 'Meeting invitation', '{{meetingTitle}} · {{when}}'),
('c1573047-ddb1-5739-9d62-b818eaaaf91f', 'MEETING_SCHEDULED', 'EMAIL', 'hi', 'TEAM', 'बैठक: {{meetingTitle}}', 'आपको {{when}} को "{{meetingTitle}}" में आमंत्रित किया गया है।

कार्यसूची और स्थान के लिए ऐप खोलें (और > बैठकें)।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('cb7f77e1-e815-589e-8bd0-9e3a81598cda', 'MEETING_SCHEDULED', 'IN_APP', 'hi', 'TEAM', 'बैठक का निमंत्रण', '{{meetingTitle}} · {{when}}'),
('bc71a39d-2f21-56bd-86ae-833e1f833a4b', 'MEETING_SCHEDULED', 'EMAIL', 'mr', 'TEAM', 'सभा: {{meetingTitle}}', 'तुम्हाला {{when}} रोजी "{{meetingTitle}}" साठी निमंत्रण आहे.

विषयसूची आणि ठिकाणासाठी ॲप उघडा (अधिक > सभा).

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('bb0ed3af-f1f8-5921-8184-10a908630510', 'MEETING_SCHEDULED', 'IN_APP', 'mr', 'TEAM', 'सभेचे निमंत्रण', '{{meetingTitle}} · {{when}}'),
('7f975507-2e60-5e5b-8ddb-66a131456550', 'MEETING_ACTION_ASSIGNED', 'EMAIL', 'en', 'TEAM', 'Action item for you: {{meetingTitle}}', 'From the meeting "{{meetingTitle}}" you have an action item:

{{text}}

Due: {{dueDate}}. Mark it done in the app (More > Meetings).

—
Seva Setu · You can change which e-mails you receive in the app: Settings > Notifications.'),
('e5c7c189-d4ac-5004-93ed-b5117afe5a95', 'MEETING_ACTION_ASSIGNED', 'IN_APP', 'en', 'TEAM', 'New action item', '{{text}} · due {{dueDate}}'),
('dae4d47d-0084-5795-8c4c-a8bae526aed9', 'MEETING_ACTION_ASSIGNED', 'EMAIL', 'hi', 'TEAM', 'आपके लिए कार्य: {{meetingTitle}}', 'बैठक "{{meetingTitle}}" से आपको यह कार्य सौंपा गया है:

{{text}}

अंतिम तिथि: {{dueDate}}। पूरा होने पर ऐप में चिह्नित करें (और > बैठकें)।

—
सेवा सेतु · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।'),
('3ac74c57-6e98-5774-8444-edbb1d9135ea', 'MEETING_ACTION_ASSIGNED', 'IN_APP', 'hi', 'TEAM', 'नया कार्य', '{{text}} · अंतिम तिथि {{dueDate}}'),
('b22a2fff-2a61-5cc2-ae7b-c8dca53a000b', 'MEETING_ACTION_ASSIGNED', 'EMAIL', 'mr', 'TEAM', 'तुमच्यासाठी कार्य: {{meetingTitle}}', 'सभा "{{meetingTitle}}" मधून तुम्हाला हे कार्य दिले आहे:

{{text}}

अंतिम तारीख: {{dueDate}}. पूर्ण झाल्यावर ॲपमध्ये खूण करा (अधिक > सभा).

—
सेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना.'),
('5133ba3b-0470-5a63-a268-bb5768cf1f26', 'MEETING_ACTION_ASSIGNED', 'IN_APP', 'mr', 'TEAM', 'नवीन कार्य', '{{text}} · अंतिम तारीख {{dueDate}}');
