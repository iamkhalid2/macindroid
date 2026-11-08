package xyz.iamkhalid.macindroid;

/**
 * Test set for Otp.find: real-world shapes of one-time-code messages (SMS and app notifications, many languages)
 * and messages full of numbers that are not codes. Run with android/test.sh. Codes and numbers here are made up.
 */
public final class OtpTest {
    // { message, expected code }
    static final String[][] CODES = {
            // English, global services
            {"G-482913 is your Google verification code.", "482913"},
            {"Your Google verification code is 482913", "482913"},
            {"Your WhatsApp code: 482-913\n\nDon't share this code with others", "482913"},
            {"WhatsApp code 482 913. Don't share this code with others.", "482913"},
            {"Use 4829 as Microsoft account security code", "4829"},
            {"Your Apple Account Code is: 482913. Don't share it with anyone.\n\n@apple.com #482913 %apple.com", "482913"},
            {"482913 is your Amazon OTP. Do not share it with anyone.", "482913"},
            {"Telegram code: 48291\n\nYou can also tap on this link to log in:\nhttps://t.me/login/48291", "48291"},
            {"Your Instagram code is 482 913. Don't share it. #ig", "482913"},
            {"Your Uber code is 4829. Never share this code.", "4829"},
            {"Your verification code is 482913. It expires in 10 minutes.", "482913"},
            {"Your one-time passcode is 48291372", "48291372"},
            {"Your PayPal security code is 482913. Your code expires in 10 minutes. Please don't reply.", "482913"},
            {"Steam Guard code: 7XKQM", "7XKQM"},
            {"Your Discord verification code is: AB12CD", "AB12CD"},
            {"Your login code is 482913. If you didn't request it, you can ignore this message.", "482913"},
            {"Verification code\n482913", "482913"},
            {"482913\nYour Netflix sign-in code", "482913"},
            {"Your 2FA code: 482913", "482913"},
            {"Enter 4829 to verify your phone number", "4829"},
            {"Your Revolut verification code is 482-913. Never share this code, not even with Revolut.", "482913"},
            {"Your temporary password is K7Q2P9", "K7Q2P9"},
            {"[Coinbase] 482913 is your verification code. Don't share it.", "482913"},
            {"Your code is 4829 (valid for 5 minutes)", "4829"},
            {"Use verification code 482913 for Facebook authentication.", "482913"},
            {"Your PIN is 4829", "4829"},

            // India (banks, payments, government; amounts, card endings and times around the code)
            {"OTP is 482913 for txn of INR 5,000.00 at AMAZON on HDFC Bank card ending 1234. Valid till 10:20. Do not share OTP for security reasons.", "482913"},
            {"Dear Customer, 482913 is the OTP for your transaction of Rs. 5,000.00 at FLIPKART on your SBI Card ending 4321. Valid for 10 mins. Do not share it with anyone.", "482913"},
            {"482913 is OTP to login to your ICICI Bank account XX1234. OTP valid for 5 minutes. Do not share it with anyone.", "482913"},
            {"Your Aadhaar OTP is 482913 (valid for 10 mins). Do not share this with anyone. - UIDAI", "482913"},
            {"Dear Customer, use OTP 482913 to log in to Paytm. Never share your OTP with anyone. Ref: 8823", "482913"},
            {"Your Swiggy OTP is 4829. Don't share it with anyone.", "4829"},
            {"Rs.1999.00 payment to Netflix: OTP 482913. Valid for 3 min. Do not share. -Axis Bank", "482913"},
            {"482913 is your OTP for UPI PIN reset on BHIM. Do not share OTP with anyone.", "482913"},
            {"Use 482913 as one time password (OTP) to log in to your Zomato account. Do not share this OTP with anyone for security reasons.", "482913"},

            // Other languages
            {"Tu código de verificación es 482913", "482913"},
            {"Tu codigo de Uber es 4829", "4829"},
            {"Seu código de verificação é 482913. Não compartilhe.", "482913"},
            {"Votre code de vérification est 482913.", "482913"},
            {"Ihr Bestätigungscode lautet 482913.", "482913"},
            {"Ihre pushTAN: 482913", "482913"},
            {"Il tuo codice di verifica è 482913", "482913"},
            {"Je verificatiecode is 482913", "482913"},
            {"Din engångskod är 482913", "482913"},
            {"Vahvistuskoodisi on 482913", "482913"},
            {"Twój kod weryfikacyjny to 482913", "482913"},
            {"Doğrulama kodunuz: 482913", "482913"},
            {"Ο κωδικός σας είναι 482913", "482913"},
            {"Код подтверждения: 4829. Никому не сообщайте его.", "4829"},
            {"Ваш код для входа: 48291", "48291"},
            {"קוד האימות שלך הוא 482913", "482913"},
            {"رمز التحقق الخاص بك هو 482913", "482913"},
            {"رمز التحقق: ٤٨٢٩١٣", "482913"},
            {"आपका ओटीपी 482913 है। इसे किसी के साथ साझा न करें।", "482913"},
            {"Kode verifikasi Anda adalah 482913. Jangan berikan kode ini kepada siapa pun.", "482913"},
            {"Mã xác minh của bạn là 482913", "482913"},
            {"รหัส OTP ของคุณคือ 482913", "482913"},
            {"【某某银行】您的验证码是482913，5分钟内有效，请勿泄露。", "482913"},
            {"【支付寶】校驗碼：482913，請勿告訴他人。", "482913"},
            {"认证码 482913", "482913"},
            {"認証コード：482913", "482913"},
            {"確認コードは４８２９１３です。", "482913"},
            {"[Web발신] 인증번호 [482913]를 입력해주세요.", "482913"},
            {"인증번호는 482913입니다", "482913"},
            {"Your Microsoft account code is 4829372", "4829372"},
            {"Bank of America: 48291372 is your authorization code. We'll never call you asking for this code.", "48291372"},
            {"Dear Customer, OTP for your Kotak Bank transaction of Rs 2,499.00 on Card XX4321 is 482913. Valid for 5 mins.", "482913"},
            {"Your OTP for IRCTC is 482913. PNR 4512345678.", "482913"},
            {"Ihr Sicherheitscode für die Anmeldung: 482913", "482913"},
            {"Votre code Uber : 4829", "4829"},
            {"Ваш одноразовый пароль: 482913", "482913"},
            {"【美团】482913（登录验证码），请勿告诉他人", "482913"},
            {"Kode OTP Gojek kamu 4829. JANGAN BERIKAN kode ini ke siapa pun", "4829"},
    };

    static final String[] NOT_CODES = {
            "G-482913", // no context word: could be anything
            "Code red! Server 4829 is down",
            "Your Spotify code: Get 3 months free with code GIFT2024",
            "Your Amazon order #402-4829137-1234567 has shipped",
            "Dear Customer, your a/c no. XX4829 is credited with INR 4,829.00 on 08-11-26 by UPI ref 482913482913",
            "Your Ola ride with KA05 MN 4829 is arriving in 3 mins",
            "Call 0800 4829 1372 to speak to us",
            "Electricity bill of Rs 4829 due on 15-11-2026. Pay at https://pay.example.com/4829",
            "PR #4829: Fix code formatting",
            "Your order 482913 has shipped and will arrive on 12/05/2025.",
            "Meeting moved to 10:30 in room 4021",
            "Call me back on +44 7700 900123 when you can",
            "Can you call 1800 123 4567 tomorrow?",
            "You paid Rs. 5000 to AMAZON. Ref no 482913123",
            "Debited INR 2,500.00 from a/c XX1234 on 05-11-26. Avl bal INR 10,000.00",
            "Use promo code SAVE20 for 20% off your next order!",
            "Get 15% off with code SPRING2025 this weekend only",
            "Your code review on PR 4821 was approved",
            "Flight BA2490 departs at 14:35 from gate 23",
            "Your card PIN was changed on 2025-11-05. If this wasn't you, call 1800 123 4567.",
            "Your OTP was used to sign in. If this wasn't you, call 1800 123 4567.",
            "Happy birthday! See you in 2026",
            "The new release is version 4.2.1",
            "Invoice 48291 for $1,249.00 is due on 15 Nov",
            "Your package with tracking code 1Z999AA10123456784 is out for delivery",
            "You have 1234 unread messages",
            "Your Uber is arriving in 4 minutes. Look for a white Toyota KA01AB1234",
            "Meet at 1450 Market Street",
            "The zip code is 94103",
            "Redeem your gift card code AB12CD34 at checkout",
            "I'll send the code tomorrow, it's 2024 after all",
            "Your balance is $4,829.13",
            "Your appointment is on 12.05.2025 at 14:30",
            "Your account ending 4829 was charged $15.99",
            "Tickets for row 12 seats 4829 and 4830",
            "Debited Rs 4829 for your order. Not you? Call us",
            "Order #482913: your code to pick up is ready", // "#482913" is an order number
            "5000 points added to your account",
            "Join the call with PIN 482913 1234 #",
            "Your refund of €4829 has been processed",
            "Download 4K videos with the new app",
            "Hello, how are you? Long time no see",
            "",
    };

    public static void main(String[] args) {
        int failed = 0;
        for (String[] c : CODES) {
            String got = Otp.find(c[0]);
            if (!c[1].equals(got)) {
                failed++;
                System.out.println("MISSED  expected " + c[1] + ", got " + got + ":  " + c[0].replace('\n', ' '));
            }
        }
        for (String text : NOT_CODES) {
            String got = Otp.find(text);
            if (got != null) {
                failed++;
                System.out.println("FALSE   got " + got + ":  " + text);
            }
        }
        if (Otp.find(null) != null) failed++;
        int total = CODES.length + NOT_CODES.length + 1;
        System.out.println((total - failed) + "/" + total + " passed (" + CODES.length + " codes, " + NOT_CODES.length + " non-codes)");
        if (failed > 0) System.exit(1);
    }
}
