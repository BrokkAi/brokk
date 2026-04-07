package ai.brokk;

/**
 * Holds user balance and subscription status information.
 *
 * @param balance the user's available balance
 * @param isSubscribed whether the user has an active subscription
 */
public record BalanceInfo(float balance, boolean isSubscribed) {}
