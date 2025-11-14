package ai.brokk.cli;

import java.util.Locale;

public final class BalanceFormatter {
    private BalanceFormatter() {}

    public static String format(float balance) {
        if (Float.isNaN(balance) || Float.isInfinite(balance) || balance < 0f) {
            return "$0.00";
        }
        return String.format(Locale.US, "$%.2f", balance);
    }
}
