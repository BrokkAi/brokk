
const fs = require('fs');
const path = require('path');

const METRICS_TO_TRACK = [
    { key: 'analysis_time_seconds', name: 'Analysis Time', higherIsWorse: true, unit: 's', threshold: 10.0 },
    { key: 'peak_memory_mb', name: 'Peak Memory', higherIsWorse: true, unit: 'MB', threshold: 10.0 },
    { key: 'files_per_second', name: 'Files/Second', higherIsWorse: false, unit: ' files/s', threshold: 10.0 },
];

function findReportFile(dir) {
    if (!fs.existsSync(dir) || !fs.lstatSync(dir).isDirectory()) {
        return null;
    }
    const files = fs.readdirSync(dir);
    const reportFile = files.find(f => f.startsWith('baseline-') && f.endsWith('.json'));
    if (!reportFile) {
        console.warn(`Could not find report file in ${dir}`);
        return null;
    }
    return path.join(dir, reportFile);
}

function parseReport(filePath) {
    if (!filePath) return null;
    try {
        const content = fs.readFileSync(filePath, 'utf-8');
        return JSON.parse(content);
    } catch (e) {
        console.error(`Error parsing ${filePath}:`, e.message);
        return null;
    }
}

function extractMetrics(report) {
    const metrics = {};
    if (!report || !report.results || !report.results.chromium) return {};
    const results = report.results.chromium;

    for (const lang in results) {
        for (const metric of METRICS_TO_TRACK) {
            const metricKey = `${lang}.${metric.key}`;
            if (results[lang] && results[lang][metric.key] !== undefined) {
                metrics[metricKey] = results[lang][metric.key];
            }
        }
    }
    return metrics;
}

function averageResults(dirs) {
    const reports = dirs.map(findReportFile).filter(Boolean).map(parseReport).filter(Boolean);
    if (reports.length === 0) {
        throw new Error(`No valid reports found for directories: ${dirs.join(', ')}`);
    }

    const allMetrics = reports.map(extractMetrics);
    const averagedMetrics = {};
    const metricCounts = {};

    for (const metrics of allMetrics) {
        for (const key in metrics) {
            averagedMetrics[key] = (averagedMetrics[key] || 0) + metrics[key];
            metricCounts[key] = (metricCounts[key] || 0) + 1;
        }
    }

    for (const key in averagedMetrics) {
        if (metricCounts[key] > 0) {
            averagedMetrics[key] /= metricCounts[key];
        }
    }
    return averagedMetrics;
}

function formatValue(value, metricConfig) {
    if (typeof value !== 'number') return 'N/A';
    const decimals = (metricConfig.key === 'peak_memory_mb') ? 1 : 2;
    return value.toFixed(decimals);
}

function generateReport(baseAvg, headAvg) {
    const headSha = process.env.HEAD_SHA_SHORT || 'HEAD';
    const baseSha = process.env.BASE_SHA_SHORT || 'BASE';
    
    let markdown = `:rocket: **Daily Performance Report**\n\n`;
    markdown += `Comparison between \`master@${headSha}\` and \`master@${baseSha}\`.\n\n`;
    markdown += `| Metric | Language | Baseline (${baseSha}) | Current (${headSha}) | Change | Status |\n`;
    markdown += `|---|---|---|---|---|---|\n`;

    const allKeys = [...new Set([...Object.keys(baseAvg), ...Object.keys(headAvg)])].sort();

    for (const key of allKeys) {
        const [lang, metricKey] = key.split('.', 2);
        const metricConfig = METRICS_TO_TRACK.find(m => m.key === metricKey);
        if (!metricConfig) continue;

        const baseValue = baseAvg[key];
        const headValue = headAvg[key];

        let changeStr = 'N/A';
        let status = ':question:';

        if (typeof baseValue === 'number' && typeof headValue === 'number' && baseValue !== 0) {
            const change = ((headValue - baseValue) / baseValue) * 100;
            changeStr = `${change > 0 ? '+' : ''}${change.toFixed(1)}%`;
            
            const isWorse = metricConfig.higherIsWorse ? change > 0 : change < 0;
            
            if (Math.abs(change) > metricConfig.threshold) {
                status = isWorse ? ':x: Regression' : ':white_check_mark: Improvement';
            } else {
                status = ':heavy_check_mark: OK';
            }
        } else if (typeof headValue === 'number' && typeof baseValue !== 'number') {
            changeStr = 'New';
            status = ':sparkles:';
        }


        markdown += `| ${metricConfig.name} | ${lang} | ${formatValue(baseValue, metricConfig)}${metricConfig.unit} | ${formatValue(headValue, metricConfig)}${metricConfig.unit} | ${changeStr} | ${status} |\n`;
    }

    return markdown;
}

function main() {
    const args = process.argv.slice(2);
    const baseDirs = args.filter(arg => arg.includes('report-base-'));
    const headDirs = args.filter(arg => arg.includes('report-head-'));

    if (baseDirs.length === 0 || headDirs.length === 0) {
        console.error('Usage: node compare-perf-results.js <base-report-dirs...> <head-report-dirs...>');
        process.exit(1);
    }
    
    try {
        const baseAvg = averageResults(baseDirs);
        const headAvg = averageResults(headDirs);
        const report = generateReport(baseAvg, headAvg);
        console.log(report);
    } catch (error) {
        console.error('Error generating performance report:', error.message);
        const headSha = process.env.HEAD_SHA_SHORT || 'HEAD';
        const baseSha = process.env.BASE_SHA_SHORT || 'BASE';
        let markdown = `:warning: **Failed to generate performance report**\n\n`;
        markdown += `Comparison between \`master@${headSha}\` and \`master@${baseSha}\` failed.\n\n`;
        markdown += `\`\`\`\n${error.stack}\n\`\`\``;
        console.log(markdown);
        process.exit(0); // Exit 0 so the markdown error report is still sent
    }
}

main();
