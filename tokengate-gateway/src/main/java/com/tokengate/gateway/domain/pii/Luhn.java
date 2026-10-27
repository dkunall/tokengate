package com.tokengate.gateway.domain.pii;

public final class Luhn {

    private Luhn() {
    }

    public static boolean isValid(String digits) {
        // a single digit is "valid" by the arithmetic but is never a payload worth checking
        if (digits.length() < 2) {
            return false;
        }
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            int d = c - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
