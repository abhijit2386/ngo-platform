import uuid, sys
F_EN = "\n\n—\nYogdaan · You can change which e-mails you receive in the app: Settings > Notifications."
F_HI = "\n\n—\nयोगदान · कौन-से ई-मेल मिलें, यह आप ऐप में बदल सकते हैं: सेटिंग्ज > सूचनाएँ।"
F_MR = "\n\n—\nसेवा सेतू · कोणते ई-मेल मिळावेत ते ॲपमध्ये बदला: सेटिंग्ज > सूचना."
T = {
 # code: (category, {lang: (email_subject, email_body, inapp_title, inapp_body)})
 "NGO_APPROVED": ("ACCOUNT", {
  "en": ("{{ngoName}} is verified", "Good news! {{ngoName}} has been verified on Yogdaan.\n\n{{message}}\n\nYour 30-day free trial has started. Open the app to invite your team and start recording donations.",
         "{{ngoName}} is verified", "Your free trial has started. {{message}}"),
  "hi": ("{{ngoName}} सत्यापित हो गया", "खुशखबरी! {{ngoName}} योगदान पर सत्यापित हो गया है।\n\n{{message}}\n\nआपका 30 दिन का निःशुल्क परीक्षण शुरू हो गया है। अपनी टीम को आमंत्रित करने और दान दर्ज करने के लिए ऐप खोलें।",
         "{{ngoName}} सत्यापित हो गया", "आपका निःशुल्क परीक्षण शुरू हो गया है। {{message}}"),
  "mr": ("{{ngoName}} पडताळले गेले", "आनंदाची बातमी! {{ngoName}} सेवा सेतूवर पडताळले गेले आहे.\n\n{{message}}\n\nतुमची 30 दिवसांची मोफत चाचणी सुरू झाली आहे. टीमला आमंत्रित करण्यासाठी आणि देणग्या नोंदवण्यासाठी ॲप उघडा.",
         "{{ngoName}} पडताळले गेले", "तुमची मोफत चाचणी सुरू झाली आहे. {{message}}")}),
 "NGO_REJECTED": ("ACCOUNT", {
  "en": ("Update on your registration for {{ngoName}}", "We could not verify {{ngoName}} at this time.\n\nReason: {{reason}}\n\nYou can correct the details in the app and submit again.",
         "Registration not approved", "{{ngoName}}: {{reason}}"),
  "hi": ("{{ngoName}} के पंजीकरण की जानकारी", "हम अभी {{ngoName}} का सत्यापन नहीं कर सके।\n\nकारण: {{reason}}\n\nआप ऐप में विवरण सुधारकर फिर से जमा कर सकते हैं।",
         "पंजीकरण स्वीकृत नहीं हुआ", "{{ngoName}}: {{reason}}"),
  "mr": ("{{ngoName}} नोंदणीबाबत माहिती", "आम्ही सध्या {{ngoName}} पडताळू शकलो नाही.\n\nकारण: {{reason}}\n\nॲपमध्ये तपशील दुरुस्त करून पुन्हा सादर करा.",
         "नोंदणी मंजूर झाली नाही", "{{ngoName}}: {{reason}}")}),
 "NGO_INFO_REQUESTED": ("ACCOUNT", {
  "en": ("More information needed for {{ngoName}}", "Our verification team needs a little more information about {{ngoName}}:\n\n{{message}}\n\nPlease update your registration in the app and submit it again.",
         "More information needed", "{{ngoName}}: {{message}}"),
  "hi": ("{{ngoName}} के लिए और जानकारी चाहिए", "हमारी सत्यापन टीम को {{ngoName}} के बारे में थोड़ी और जानकारी चाहिए:\n\n{{message}}\n\nकृपया ऐप में पंजीकरण अपडेट करके फिर से जमा करें।",
         "और जानकारी चाहिए", "{{ngoName}}: {{message}}"),
  "mr": ("{{ngoName}} साठी अधिक माहिती हवी", "आमच्या पडताळणी टीमला {{ngoName}} बद्दल आणखी थोडी माहिती हवी आहे:\n\n{{message}}\n\nकृपया ॲपमध्ये नोंदणी अद्ययावत करून पुन्हा सादर करा.",
         "अधिक माहिती हवी", "{{ngoName}}: {{message}}")}),
 "NGO_SUSPENDED": ("ACCOUNT", {
  "en": ("{{ngoName}} has been suspended", "The account of {{ngoName}} has been suspended on Yogdaan. Please contact support from the app for details.",
         "Organization suspended", "{{ngoName}} has been suspended. Contact support for details."),
  "hi": ("{{ngoName}} निलंबित किया गया", "योगदान पर {{ngoName}} का खाता निलंबित कर दिया गया है। विवरण के लिए ऐप से सहायता से संपर्क करें।",
         "संस्था निलंबित", "{{ngoName}} निलंबित है। विवरण के लिए सहायता से संपर्क करें।"),
  "mr": ("{{ngoName}} निलंबित केले", "सेवा सेतूवर {{ngoName}} चे खाते निलंबित केले आहे. तपशिलासाठी ॲपमधून सहाय्याशी संपर्क साधा.",
         "संस्था निलंबित", "{{ngoName}} निलंबित आहे. तपशिलासाठी सहाय्याशी संपर्क साधा.")}),
 "STAFF_INVITED": ("TEAM", {
  "en": ("You're invited to join {{ngoName}}", "{{ngoName}} has invited you to join their team on Yogdaan as {{role}}.\n\nInstall the Yogdaan app and sign in with this e-mail address to accept. The invitation is valid until {{expiresOn}}.",
         "Invitation from {{ngoName}}", "You are invited to join as {{role}}. Valid until {{expiresOn}}."),
  "hi": ("{{ngoName}} से जुड़ने का निमंत्रण", "{{ngoName}} ने आपको योगदान पर अपनी टीम में {{role}} के रूप में जुड़ने के लिए आमंत्रित किया है।\n\nस्वीकार करने के लिए योगदान ऐप इंस्टॉल करें और इसी ई-मेल पते से साइन इन करें। निमंत्रण {{expiresOn}} तक मान्य है।",
         "{{ngoName}} से निमंत्रण", "आपको {{role}} के रूप में आमंत्रित किया गया है। {{expiresOn}} तक मान्य।"),
  "mr": ("{{ngoName}} मध्ये सामील होण्याचे आमंत्रण", "{{ngoName}} ने तुम्हाला सेवा सेतूवर त्यांच्या टीममध्ये {{role}} म्हणून सामील होण्यासाठी आमंत्रित केले आहे.\n\nस्वीकारण्यासाठी सेवा सेतू ॲप इन्स्टॉल करा आणि याच ई-मेलने साइन इन करा. आमंत्रण {{expiresOn}} पर्यंत वैध आहे.",
         "{{ngoName}} कडून आमंत्रण", "तुम्हाला {{role}} म्हणून आमंत्रित केले आहे. {{expiresOn}} पर्यंत वैध.")}),
 "STAFF_JOINED": ("TEAM", {
  "en": ("{{memberName}} joined {{ngoName}}", "{{memberName}} accepted the invitation and joined {{ngoName}} as {{role}}.",
         "New team member", "{{memberName}} joined as {{role}}."),
  "hi": ("{{memberName}} {{ngoName}} से जुड़े", "{{memberName}} ने निमंत्रण स्वीकार किया और {{ngoName}} में {{role}} के रूप में जुड़ गए।",
         "नया टीम सदस्य", "{{memberName}} {{role}} के रूप में जुड़े।"),
  "mr": ("{{memberName}} {{ngoName}} मध्ये सामील", "{{memberName}} यांनी आमंत्रण स्वीकारले आणि {{ngoName}} मध्ये {{role}} म्हणून सामील झाले.",
         "नवीन टीम सदस्य", "{{memberName}} {{role}} म्हणून सामील झाले.")}),
 "SUBSCRIPTION_RENEWAL_DUE": ("BILLING", {
  "en": ("{{plan}} plan: {{daysLeft}} days left", "The {{plan}} plan of {{ngoName}} ends on {{periodEndOn}} ({{daysLeft}} days left).\n\nRenew in the app (More > Subscription) to keep donations, receipts and your team running without interruption.",
         "{{daysLeft}} days left on your plan", "{{plan}} ends on {{periodEndOn}}. Renew to avoid interruption."),
  "hi": ("{{plan}} योजना: {{daysLeft}} दिन बाकी", "{{ngoName}} की {{plan}} योजना {{periodEndOn}} को समाप्त होगी ({{daysLeft}} दिन बाकी)।\n\nदान, रसीदें और टीम बिना रुकावट चलाने के लिए ऐप में नवीनीकरण करें (अधिक > सदस्यता)।",
         "योजना में {{daysLeft}} दिन बाकी", "{{plan}} {{periodEndOn}} को समाप्त होगी। रुकावट से बचने के लिए नवीनीकरण करें।"),
  "mr": ("{{plan}} योजना: {{daysLeft}} दिवस बाकी", "{{ngoName}} ची {{plan}} योजना {{periodEndOn}} रोजी संपेल ({{daysLeft}} दिवस बाकी).\n\nदेणग्या, पावत्या आणि टीम विनाव्यत्यय सुरू ठेवण्यासाठी ॲपमध्ये नूतनीकरण करा (अधिक > सदस्यता).",
         "योजनेचे {{daysLeft}} दिवस बाकी", "{{plan}} {{periodEndOn}} रोजी संपेल. व्यत्यय टाळण्यासाठी नूतनीकरण करा.")}),
 "SUBSCRIPTION_EXPIRED": ("BILLING", {
  "en": ("Your {{plan}} plan has expired", "The {{plan}} plan of {{ngoName}} has expired and the account is in its grace period. Renew in the app to avoid suspension.",
         "Plan expired", "{{plan}} has expired. Renew to avoid suspension."),
  "hi": ("आपकी {{plan}} योजना समाप्त हो गई", "{{ngoName}} की {{plan}} योजना समाप्त हो गई है और खाता अनुग्रह अवधि में है। निलंबन से बचने के लिए ऐप में नवीनीकरण करें।",
         "योजना समाप्त", "{{plan}} समाप्त हो गई। निलंबन से बचने के लिए नवीनीकरण करें।"),
  "mr": ("तुमची {{plan}} योजना संपली", "{{ngoName}} ची {{plan}} योजना संपली आहे आणि खाते सवलत कालावधीत आहे. निलंबन टाळण्यासाठी ॲपमध्ये नूतनीकरण करा.",
         "योजना संपली", "{{plan}} संपली. निलंबन टाळण्यासाठी नूतनीकरण करा.")}),
 "SUBSCRIPTION_SUSPENDED": ("BILLING", {
  "en": ("{{ngoName}} subscription suspended", "The subscription of {{ngoName}} is suspended because it was not renewed. Your data is safe; renew in the app to restore access.",
         "Subscription suspended", "Renew to restore access. Your data is safe."),
  "hi": ("{{ngoName}} की सदस्यता निलंबित", "नवीनीकरण न होने के कारण {{ngoName}} की सदस्यता निलंबित है। आपका डेटा सुरक्षित है; पहुँच वापस पाने के लिए ऐप में नवीनीकरण करें।",
         "सदस्यता निलंबित", "पहुँच वापस पाने के लिए नवीनीकरण करें। आपका डेटा सुरक्षित है।"),
  "mr": ("{{ngoName}} ची सदस्यता निलंबित", "नूतनीकरण न झाल्याने {{ngoName}} ची सदस्यता निलंबित आहे. तुमचा डेटा सुरक्षित आहे; प्रवेश परत मिळवण्यासाठी ॲपमध्ये नूतनीकरण करा.",
         "सदस्यता निलंबित", "प्रवेश परत मिळवण्यासाठी नूतनीकरण करा. तुमचा डेटा सुरक्षित आहे.")}),
 "SUBSCRIPTION_PAYMENT_FAILED": ("BILLING", {
  "en": ("Payment of ₹{{amount}} failed", "A subscription payment of ₹{{amount}} for {{ngoName}} did not go through. No money was taken. Please try again from the app.",
         "Payment failed", "₹{{amount}} for {{ngoName}} did not go through. Please try again."),
  "hi": ("₹{{amount}} का भुगतान विफल", "{{ngoName}} के लिए ₹{{amount}} का सदस्यता भुगतान पूरा नहीं हुआ। कोई राशि नहीं कटी। कृपया ऐप से फिर प्रयास करें।",
         "भुगतान विफल", "{{ngoName}} के लिए ₹{{amount}} का भुगतान नहीं हुआ। फिर प्रयास करें।"),
  "mr": ("₹{{amount}} चे पेमेंट अयशस्वी", "{{ngoName}} साठी ₹{{amount}} चे सदस्यता पेमेंट पूर्ण झाले नाही. कोणतीही रक्कम कापली गेली नाही. कृपया ॲपमधून पुन्हा प्रयत्न करा.",
         "पेमेंट अयशस्वी", "{{ngoName}} साठी ₹{{amount}} चे पेमेंट झाले नाही. पुन्हा प्रयत्न करा.")}),
}
FOOT = {"en": F_EN, "hi": F_HI, "mr": F_MR}
def q(s): return "'" + s.replace("'", "''") + "'" if s is not None else "NULL"
ns = uuid.UUID("6f1c2a52-0000-4000-8000-00000000a1a1")
out = ["-- Seed templates (en reviewed; hi/mr are drafts for native review - docs/business/LOCALIZATION.md).",
       "-- Placeholders: {{name}}. Admins edit these via /api/v1/admin/notification-templates (ADMIN-016).",
       "INSERT INTO notification_templates (id, code, channel, language, category, subject, body) VALUES"]
rows = []
for code, (cat, langs) in T.items():
    for lang, (es, eb, it, ib) in langs.items():
        rows.append(f"({q(str(uuid.uuid5(ns, code+'/EMAIL/'+lang)))}, {q(code)}, 'EMAIL', '{lang}', '{cat}', {q(es)}, {q(eb + FOOT[lang])})")
        rows.append(f"({q(str(uuid.uuid5(ns, code+'/IN_APP/'+lang)))}, {q(code)}, 'IN_APP', '{lang}', '{cat}', {q(it)}, {q(ib)})")
out.append(",\n".join(rows) + ";")
open(sys.argv[1], "w", encoding="utf-8").write("\n".join(out) + "\n")
print(len(rows), "rows")
