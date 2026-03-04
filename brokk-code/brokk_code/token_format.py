def format_token_count(tokens: int) -> str:
    negative = tokens < 0
    value = -tokens if negative else tokens

    if value < 1_000:
        formatted = str(value)
    elif value < 10_000:
        formatted = f"{value / 1_000:.1f}".removesuffix(".0") + "k"
    elif value < 1_000_000:
        formatted = f"{value // 1_000}k"
    elif value < 10_000_000:
        formatted = f"{value / 1_000_000:.1f}".removesuffix(".0") + "m"
    elif value < 1_000_000_000:
        formatted = f"{value // 1_000_000}m"
    elif value < 10_000_000_000:
        formatted = f"{value / 1_000_000_000:.1f}".removesuffix(".0") + "b"
    else:
        formatted = f"{value // 1_000_000_000}b"

    return f"-{formatted}" if negative else formatted
