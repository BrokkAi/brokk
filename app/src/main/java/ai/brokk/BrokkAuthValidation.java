package ai.brokk;

import java.util.Locale;

/**
 * Normalized result for Brokk API key validation.
 *
 * @param state validation state
 * @param valid whether the key is considered valid
 * @param subscribed whether the account is subscribed (paid tier)
 * @param hasBalance whether {@code balance} is present
 * @param balance available balance when {@code hasBalance} is true
 * @param message human-readable status message
 */
public record BrokkAuthValidation(
        State state, boolean valid, boolean subscribed, boolean hasBalance, float balance, String message) {
    public enum State {
        PAID_USER,
        FREE_USER,
        UNKNOWN_USER,
        INVALID_KEY,
        INVALID_KEY_FORMAT,
        MISSING_KEY,
        NETWORK_ERROR,
        SERVICE_ERROR
    }

    public String balanceDisplay() {
        if (!hasBalance) {
            return "N/A";
        }
        return String.format(Locale.ROOT, "$%.2f", balance);
    }
}
