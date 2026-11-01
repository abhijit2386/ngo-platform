package org.ngoplatform.identity.sms;

/**
 * SMS provider adapter. Production providers in India (MSG91, Gupshup, Twilio...) need TRAI DLT registration:
 * registered entity, sender header and the approved OTP template {@link #OTP_TEMPLATE}.
 */
public interface SmsSender {
    /** Text submitted for DLT approval; {#var#} is the code. Keep the app text identical. */
    String OTP_TEMPLATE = "{#var#} is your Yogdaan verification code. It expires in 5 minutes. Do not share it with anyone.";

    void sendOtp(String phone10, String code);

    static String otpText(String code) { return OTP_TEMPLATE.replace("{#var#}", code); }
}
