package cn.photolib.common.util;

import java.security.SecureRandom;

public final class PublicId {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private PublicId() {}

    public static String next() {
        char[] value = new char[26];
        for (int i = 0; i < value.length; i++) {
            value[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(value);
    }
}
