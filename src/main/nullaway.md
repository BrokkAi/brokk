# Error Prone and Null Away

Brokk enables Error Prone and Null Away with -Werror. This means that warnings by these static analysis tools
will be treated as errors, which is very useful in forcing the LLM edit loop to respect them.

The warnings are generally self-explanatory, but it's worth being explicit about how to deal with nulls correctly:

- Fields, parameters, and return values are treated as non-null by default. So the easiest way to avoid
  problems is to simply not pass nulls values around.
- If you need to use a null value, mark the appropriate symbol as @Nullable.
- The only tricky part is dealing with another API's null values. If you receive a null value from a
  method call that you *know* is never null, you can use NullnessUtil.castNonNull() to deal with it. But if the value
  is only *not supposed* to be null, you should use Objects.requireNonNull() instead. Assertions are not
  respected by Null Away, so you should avoid that option.
