package com.tokengate.gateway.domain.pii;

/**
 * Verhoeff check digit, used by Aadhaar for its 12th digit. Unlike Luhn it catches every single-digit error
 * and every adjacent transposition, using the dihedral group D5 instead of arithmetic mod 10.
 */
public final class Verhoeff {

    private static final int[][] D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
            {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
            {4, 0, 1, 2, 3, 9, 5, 6, 7, 8},
            {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2},
            {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
            {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}};

    private static final int[][] P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
            {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
            {9, 4, 5, 3, 1, 2, 6, 8, 7, 0},
            {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5},
            {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}};

    private static final int[] INV = {0, 4, 3, 2, 1, 5, 6, 7, 8, 9};

    private Verhoeff() {
    }

    public static boolean isValid(String digits) {
        return !digits.isEmpty() && checksum(digits, false) == 0;
    }

    public static int checkDigit(String digitsWithoutCheck) {
        return INV[checksum(digitsWithoutCheck, true)];
    }

    private static int checksum(String digits, boolean appendingCheck) {
        int c = 0;
        int len = digits.length();
        for (int i = 0; i < len; i++) {
            char ch = digits.charAt(len - 1 - i);
            if (ch < '0' || ch > '9') {
                return -1;
            }
            int pos = appendingCheck ? i + 1 : i;
            c = D[c][P[pos % 8][ch - '0']];
        }
        return c;
    }
}
