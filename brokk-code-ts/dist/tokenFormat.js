export function formatTokenCount(tokens) {
    const negative = tokens < 0;
    const value = negative ? -tokens : tokens;
    let formatted;
    if (value < 1_000) {
        formatted = `${value}`;
    }
    else if (value < 10_000) {
        formatted = `${(value / 1_000).toFixed(1).replace(/\.0$/, "")}k`;
    }
    else if (value < 1_000_000) {
        formatted = `${Math.floor(value / 1_000)}k`;
    }
    else if (value < 10_000_000) {
        formatted = `${(value / 1_000_000).toFixed(1).replace(/\.0$/, "")}m`;
    }
    else if (value < 1_000_000_000) {
        formatted = `${Math.floor(value / 1_000_000)}m`;
    }
    else if (value < 10_000_000_000) {
        formatted = `${(value / 1_000_000_000).toFixed(1).replace(/\.0$/, "")}b`;
    }
    else {
        formatted = `${Math.floor(value / 1_000_000_000)}b`;
    }
    return negative ? `-${formatted}` : formatted;
}
