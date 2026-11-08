package xyz.iamkhalid.macindroid;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a one-time code (OTP, verification or login code, PIN) in a message, for copying it to the Mac.
 *
 * <p>A code is 4 to 8 digits, or 4 to 8 capitals and digits with at least one digit ("G-482913" and "482 913"
 * give 482913), near a word like code, OTP, verification, passcode or PIN in one of about 30 languages. Copying
 * the wrong thing overwrites the Mac's clipboard, so it errs towards missing a code: amounts, dates, times, phone
 * numbers, card or account endings, order, reference and flight numbers, quantities and promo codes are rejected.
 *
 * <p>Pure Java with no Android classes, so android/test.sh runs its test set on the Mac. It never logs.
 */
final class Otp {
    private Otp() {}

    private static final int MAX_TEXT = 800;
    /** A context word this many characters before the code, in its sentence or the one before. */
    private static final int BEFORE = 80;
    /** Or this many after it, in the same sentence ("482913 is your Google verification code"). */
    private static final int AFTER = 50;

    /**
     * Unicode \\b, \\w and \\d, so words in any script count. Android's regex engine (ICU) works that way already and
     * throws on this flag; desktop Java, where test.sh runs the test set, needs it to behave the same.
     */
    private static final int UNICODE = "Dalvik".equals(System.getProperty("java.vm.name")) ? 0 : Pattern.UNICODE_CHARACTER_CLASS;
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | UNICODE;

    /**
     * Words that say "this is a code". Words in scripts with spaces are matched whole, plus compounds ending in
     * code/kode/kod/koodi (Bestätigungscode, verificatiecode, engangskode, vahvistuskoodi); Chinese, Japanese and
     * Thai have no spaces, so their words are matched anywhere.
     */
    private static final Pattern CONTEXT = Pattern.compile("(?:\\b(?:"
            // English
            + "codes?|otp|one[-\\s]?time|verification|verify|passcode|password|pin|2fa|two[-\\s]?factor|authentication"
            // Spanish, Portuguese, French, Italian, Catalan (SMS often drop the accents)
            + "|c[óo]digo|clave|contrase[ñn]a|verificaci[óo]n|senha|verifica[çc][ãa]o|mot de passe|v[ée]rification"
            + "|codice|verifica"
            // German, Dutch, Scandinavian, Finnish, Polish, Czech, Slovak, Turkish (with case endings: kodu, koodisi)
            + "|passwort|kennwort|bestätigung|wachtwoord|kode|engångskod|engangskode|kod\\p{L}{0,5}|kód\\p{L}{0,2}"
            + "|kood\\p{L}{1,4}|hasło|weryfikacyjny|ověřovací|overovací|şifre\\p{L}{0,3}|doğrulama"
            // Romanian, Hungarian, Greek, Russian, Ukrainian, Hebrew, Swahili
            + "|parola|cod|ellenőrző|κωδικ\\p{L}{0,3}|код\\p{L}{0,3}|парол\\p{L}{1,3}|קוד|msimbo"
            // Indonesian, Malay, Filipino, Vietnamese
            + "|sandi|verifikasi|pengesahan|mã|xác minh"
            + ")\\b"
            + "|\\b\\p{L}{2,24}(?:codes?|kode|codice|código)\\b|\\b\\p{L}{2,24}(?:koodi|kod|kód)\\p{L}{0,4}\\b"
            // Scripts that attach particles or prefixes to the word (Korean 인증번호는, Arabic الرمز), or have no spaces
            // (Chinese, Japanese, Thai): matched anywhere.
            + "|인증번호|인증 ?코드|확인 ?코드|코드|비밀번호|승인번호"
            + "|رمز|كود|التحقق|کد|कोड|ओटीपी|सत्यापन|কোড|ওটিপি"
            + "|验证码|驗證碼|认证码|認證碼|認証番号|校验码|校驗碼|动态码|動態碼|确认码|確認碼|安全码|安全碼|密码|密碼|验证|驗證"
            + "|認証|確認コード|ワンタイム|パスコード|パスワード|コード|暗証番号"
            + "|รหัส)", FLAGS);
    /** German and Austrian banks' TAN (pushTAN, smsTAN), case-sensitive so it isn't the colour. */
    private static final Pattern TAN = Pattern.compile("\\b\\p{L}{0,8}TAN\\b", UNICODE);

    /** Phrases with "code" in them that aren't one-time codes; blanked before looking for context words. */
    private static final Pattern NOT_CONTEXT = Pattern.compile("\\b(?:"
            + "(?:promo|promotional|coupon|discount|referral|invite|invitation|gift|voucher|redeem|redemption|offer|bonus"
            + "|zip|postal|post|area|country|dialling|dialing|qr|bar|source|dress|tracking|tax|product|item|error|status"
            + "|reason|response|exit|morse|colou?r|shortcode|short)\\s?codes?"
            + "|codes?\\s(?:review|reviews|reviewer|owner|owners|freeze|of conduct|editor|block|base|snippet|red|blue|black|orange|yellow)"
            + "|(?:debit|credit|card|bank|atm|sim|puk)\\s?pin|pin\\s?(?:code\\s)?(?:change|changed|reset)"
            + ")\\b", FLAGS);

    /** In the code's own sentence, these mean it's a promotion or a gift card, not a one-time code. */
    private static final Pattern PROMO = Pattern.compile(
            "\\b(?:promo|coupon|discount|voucher|referral|redeem|cashback|gift\\s?card|giftcard|free|offer|deal|sale|percent off)\\b"
                    + "|%\\s?off\\b",
            FLAGS);

    /** A run of ASCII letters and digits not inside a Latin, Cyrillic or Greek word (CJK and Hangul may touch it). */
    private static final Pattern TOKEN = Pattern.compile(
            "(?<![\\p{IsLatin}\\p{IsCyrillic}\\p{IsGreek}\\p{Nd}_])(?:[A-Za-z]{1,3}-)?[A-Za-z0-9]{4,8}"
                    + "(?![\\p{IsLatin}\\p{IsCyrillic}\\p{IsGreek}\\p{Nd}_])");
    /**
     * Digit groups joined by spaces, hyphens, dots or slashes, or thousands commas (1,25,000): phone numbers, dates,
     * amounts, or "482 913". Not "482913,5" (a code, then "，5分钟" in Chinese).
     */
    private static final Pattern DIGIT_CHAIN = Pattern.compile(
            "\\+?\\(?\\d+\\)?(?:(?:[ ./]|-|\\s-\\s|,(?=\\d{2,3}(?!\\d)))\\(?\\d+\\)?)+");
    private static final Pattern URL = Pattern.compile("\\b(?:https?://|www\\.)\\S+|\\S+@\\S+\\.\\S+", FLAGS);
    private static final Pattern UNIT_TOKEN = Pattern.compile("\\d+(?:MB|GB|KB|TB|KM|KG|AM|PM|HZ|MHZ|GHZ|MP|K|P|X|ML|MG|CM|MM)");
    private static final Pattern MASK = Pattern.compile("[Xx]{2,}\\d+");
    /** A word and a year (SPRING2025, GIFT2024): a promotion. */
    private static final Pattern PROMO_TOKEN = Pattern.compile("[A-Z]{3,}(?:19|20)\\d\\d");

    /** A word just before the code that says it's some other number. */
    private static final Set<String> LABELS = new HashSet<>(Arrays.asList(
            "ending", "ends", "a/c", "ac", "acct", "account", "card", "no", "nr", "number", "num", "order", "ref", "reference",
            "txn", "transaction", "trans", "invoice", "inv", "ticket", "pr", "issue", "room", "gate", "flight", "seat", "booking",
            "pnr", "tracking", "id", "ext", "tel", "phone", "mobile", "call", "dial", "box", "unit", "apt", "suite", "version",
            "ver", "v", "model", "build", "server", "host", "port", "node", "branch", "store", "batch", "lot", "case", "job", "year", "route", "bus", "train", "platform", "terminal", "floor", "level", "chapter",
            "page", "step", "item", "qty", "x", "from", "since", "by", "till", "until", "at", "before", "after", // not "to", "on" (Polish, Finnish "is")
            // money
            "rs", "inr", "usd", "eur", "gbp", "aed", "sar", "cad", "aud", "jpy", "cny", "rmb", "idr", "myr", "php", "pkr",
            "bdt", "ngn", "zar", "try", "rub", "chf", "sek", "nok", "dkk", "pln", "brl", "mxn", "krw", "vnd", "thb",
            "amount", "amt", "bal", "balance", "total", "price", "cost", "paid", "pay", "debited", "credited", "spent", "fee",
            "fare", "of", "worth", "limit"));
    /** A word just after the code that says it's a quantity, amount or time. */
    private static final Set<String> UNITS = new HashSet<>(Arrays.asList(
            "min", "mins", "minute", "minutes", "sec", "secs", "second", "seconds", "hour", "hours", "hr", "hrs", "day", "days",
            "week", "weeks", "month", "months", "year", "years", "am", "pm", "km", "kg", "mb", "gb", "points", "pts", "items",
            "steps", "followers", "likes", "views", "times", "people", "rs", "inr", "usd", "eur", "gbp", "dollars", "rupees",
            "euros", "pounds", "yen", "won", "miles", "calories", "kcal", "words", "messages", "new", "unread"));
    private static final String CURRENCY_SIGNS = "$€£¥₹₩₽₺₪₫฿₦₱¢";

    /** The code in {@code text}, without spaces, dashes or a letter prefix, or null if there isn't one. */
    static String find(CharSequence text) {
        if (text == null) return null;
        String s = normalize(text);
        if (s.length() > MAX_TEXT) s = s.substring(0, MAX_TEXT);
        s = blank(URL.matcher(s), s);

        String context = blank(NOT_CONTEXT.matcher(s), s);
        List<int[]> words = new ArrayList<>();
        for (Pattern p : new Pattern[] {CONTEXT, TAN}) {
            Matcher m = p.matcher(context);
            while (m.find()) words.add(new int[] {m.start(), m.end()});
        }
        if (words.isEmpty()) return null;

        List<int[]> candidates = new ArrayList<>(); // start, end of each candidate in s
        List<String> codes = new ArrayList<>();
        boolean[] inChain = new boolean[s.length()];
        Matcher chain = DIGIT_CHAIN.matcher(s);
        while (chain.find()) {
            String c = chain.group();
            String[] groups = c.split("[ ./,()+-]+");
            boolean pair = !c.startsWith("+") && !c.contains("(") && c.matches("\\d{3}(?: |-| - )\\d{3}|\\d{4}(?: |-)\\d{4}")
                    && groups.length == 2;
            if (pair) {
                candidates.add(new int[] {chain.start(), chain.end()});
                codes.add(groups[0] + groups[1]);
            }
            for (int i = chain.start(); i < chain.end(); i++) inChain[i] = true;
        }
        Matcher t = TOKEN.matcher(s);
        while (t.find()) {
            if (inChain[t.start()] || inChain[t.end() - 1]) continue;
            String token = t.group();
            String code = token;
            int dash = token.indexOf('-');
            if (dash >= 0) {
                code = token.substring(dash + 1);
                if (!code.matches("\\d+")) continue; // only "G-482913"-style prefixes
            }
            if (!looksLikeCode(code)) continue;
            candidates.add(new int[] {t.start(), t.end()});
            codes.add(code);
        }

        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            int[] c = candidates.get(i);
            String code = codes.get(i);
            if (otherNumber(s, c[0], c[1])) continue;
            if (PROMO.matcher(sentence(s, c[0], c[1])).find()) continue;
            int distance = distance(s, words, c[0], c[1]);
            boolean year = code.length() == 4 && code.matches("(?:19|20)\\d\\d");
            if (year && distance > 5) continue; // "code: 2024" but not "the code tomorrow, it's 2024"
            if (distance < bestDistance) {
                bestDistance = distance;
                best = code;
            }
        }
        return best;
    }

    private static boolean looksLikeCode(String code) {
        int n = code.length();
        if (n < 4 || n > 8) return false;
        boolean digit = false;
        for (int i = 0; i < n; i++) {
            char ch = code.charAt(i);
            if (ch >= '0' && ch <= '9') digit = true;
            else if (ch < 'A' || ch > 'Z') return false; // lowercase letters: a word, a model name, not a code
        }
        if (!digit) return false;
        return !UNIT_TOKEN.matcher(code).matches() && !MASK.matcher(code).matches() && !PROMO_TOKEN.matcher(code).matches();
    }

    /** Amount, time, masked card number, "#123456", a labelled number ("card ending 1234", "order 48291"), a quantity. */
    private static boolean otherNumber(String s, int start, int end) {
        char before = start > 0 ? s.charAt(start - 1) : ' ';
        char after = end < s.length() ? s.charAt(end) : ' ';
        if (before == '#' || before == '*' || before == '•' || before == '+' || CURRENCY_SIGNS.indexOf(before) >= 0) return true;
        if (after == '%' || CURRENCY_SIGNS.indexOf(after) >= 0) return true;
        if (before == ':' && start > 1 && Character.isDigit(s.charAt(start - 2))) return true; // 12:3456
        if (after == ':' && end + 1 < s.length() && Character.isDigit(s.charAt(end + 1))) return true; // 1230:45
        int i = start - 1;
        while (i >= 0 && (s.charAt(i) == ' ' || s.charAt(i) == '\u00a0')) i--;
        if (i >= 0 && CURRENCY_SIGNS.indexOf(s.charAt(i)) >= 0) return true; // "$ 5000"
        String w1 = wordBefore(s, start);
        String w2 = w1 == null ? null : wordBefore(s, start - w1.length() - gapBefore(s, start));
        if (w1 != null && LABELS.contains(w1)) return true;
        if (w2 != null && (w2.equals("ending") || w2.equals("ends") || w2.equals("card") || w2.equals("account")
                || w2.equals("a/c") || w2.equals("order") || w2.equals("ref"))) return true;
        String next = wordAfter(s, end);
        return next != null && UNITS.contains(next);
    }

    private static int gapBefore(String s, int at) {
        int i = at - 1;
        while (i >= 0 && " \t\n:=.-(#№".indexOf(s.charAt(i)) >= 0) i--;
        return at - 1 - i;
    }

    /** The word before {@code at}, skipping spaces and ":=.-(#", lowercase; letters and '/', so "a/c" is one word. */
    private static String wordBefore(String s, int at) {
        int end = at - gapBefore(s, at);
        int i = end - 1;
        while (i >= 0 && (Character.isLetter(s.charAt(i)) || s.charAt(i) == '/')) i--;
        if (i == end - 1) return null;
        return s.substring(i + 1, end).toLowerCase(Locale.ROOT);
    }

    private static String wordAfter(String s, int at) {
        int i = at;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\u00a0')) i++;
        if (i - at > 1) return null;
        int j = i;
        while (j < s.length() && Character.isLetter(s.charAt(j))) j++;
        return j == i ? null : s.substring(i, j).toLowerCase(Locale.ROOT);
    }

    /**
     * Characters between the code and the nearest context word: before it in the same or the previous sentence, or
     * after it in the same sentence. Integer.MAX_VALUE when there's none close enough.
     */
    private static int distance(String s, List<int[]> words, int start, int end) {
        int best = Integer.MAX_VALUE;
        for (int[] w : words) {
            if (w[1] <= start) {
                int d = start - w[1];
                if (d <= BEFORE && sentenceBreaks(s, w[1], start) <= 1) best = Math.min(best, d);
            } else if (w[0] >= end) {
                int d = w[0] - end;
                if (d <= AFTER && sentenceBreaks(s, end, w[0]) == 0) best = Math.min(best, d);
            } else {
                best = 0; // the code is inside a context word's span (cannot happen with whole words, kept for safety)
            }
        }
        return best;
    }

    private static boolean isBreak(String s, int i) {
        char c = s.charAt(i);
        if (c == '。' || c == '！' || c == '？') return true;
        if (c != '.' && c != '!' && c != '?') return false;
        return i + 1 >= s.length() || Character.isWhitespace(s.charAt(i + 1)); // not "Rs.5000", "a.m.", "2.5"
    }

    private static int sentenceBreaks(String s, int from, int to) {
        int n = 0;
        for (int i = Math.max(from, 0); i < to && i < s.length(); i++) if (isBreak(s, i)) n++;
        return n;
    }

    private static String sentence(String s, int start, int end) {
        int a = start;
        while (a > 0 && !isBreak(s, a - 1)) a--;
        int b = end;
        while (b < s.length() && !isBreak(s, b)) b++;
        return s.substring(a, b);
    }

    /** Full-width and other compatibility forms to plain ones, any script's digits to 0-9, odd spaces to spaces. */
    static String normalize(CharSequence text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > '9' && Character.isDigit(c)) out.append((char) ('0' + Character.digit(c, 10)));
            else if (Character.isSpaceChar(c) && c != ' ') out.append(' ');
            else if (c == '\u2010' || c == '\u2011' || c == '\u2012' || c == '\u2013' || c == '\u2212') out.append('-');
            else out.append(c);
        }
        return out.toString();
    }

    /** Replaces each match with spaces, keeping every other character where it was. */
    private static String blank(Matcher m, String s) {
        StringBuilder out = null;
        while (m.find()) {
            if (out == null) out = new StringBuilder(s);
            for (int i = m.start(); i < m.end(); i++) out.setCharAt(i, ' ');
        }
        return out == null ? s : out.toString();
    }
}
