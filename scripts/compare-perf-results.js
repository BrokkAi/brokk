const fs = require('fs');
const path = require('path');

const METRICS_TO_TRACK = [
    { key: 'analysis_time_seconds', name: 'Analysis Time', higherIsWorse: true, unit: 's', threshold: 10.0 },
    { key: 'peak_memory_mb', name: 'Peak Memory', higherIsWorse: true, unit: 'MB', threshold: 10.0 },
    { key: 'files_per_second', name: 'Files/Second', higherIsWorse: false, unit: ' files/s', threshold: 10.0 },
];

/**
 * Resolve a report JSON file from either:
 * - a path to a JSON file, or
 * - a directory containing one or more JSON files.
 * 
 * Selection priority inside a directory:
 *  1) baseline-*.json
 *  2) results.json, report.json, summary.json
 *  3) the most recently modified *.json file
 */
function findReportFile(p) {
    if (!fs.existsSync(p)) {
        console.warn(`Path does not exist: ${p}`);
        return null;
    }

    const stat = fs.lstatSync(p);
    if (stat.isFile() && p.endsWith('.json')) {
        return p;
    }

    if (!stat.isDirectory()) {
        console.warn(`Not a directory or JSON file: ${p}`);
        return null;
    }

    const files = fs.readdirSync(p).map(f => path.join(p, f)).filter(full => {
        try {
            return fs.lstatSync(full).isFile() && full.endsWith('.json');
        } catch (_) {
            return false;
        }
    });

    if (files.length === 0) {
        console.warn(`Could not find any *.json report file in ${p}`);
        return null;
    }

    // 1) Prefer baseline-*.json
    const baseline = files.find(f => path.basename(f).startsWith('baseline-') && f.endsWith('.json'));
    if (baseline) return baseline;

    // 2) Common names
    const preferredNames = ['results.json', 'report.json', 'summary.json'];
    const preferred = files.find(f => preferredNames.includes(path.basename(f)));
    if (preferred) return preferred;

    // 3) Most recent by mtime
    files.sort((a, b) => {
        const aTime = fs.statSync(a).mtimeMs;
        const bTime = fs.statSync(b).mtimeMs;
        return bTime - aTime;
    });
    return files[0];
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

/**
 * Extract metrics for all repos present. If "chromium" exists, limit to that repo to
 * keep output focused; otherwise include all repos. Language label is "repo/lang".
 */
function extractMetrics(report) {
    const metrics = {};
    if (!report || !report.results || typeof report.results !== 'object') return {};

    const repos = report.results.chromium ? ['chromium'] : Object.keys(report.results);

    for (const repo of repos) {
        const repoResults = report.results[repo];
        if (!repoResults || typeof repoResults !== 'object') continue;

        for (const lang of Object.keys(repoResults)) {
            const langMetrics = repoResults[lang];
            if (!langMetrics || typeof langMetrics !== 'object') continue;

            for (const metric of METRICS_TO_TRACK) {
                const metricKey = `${repo}/${lang}.${metric.key}`;
                if (langMetrics[metric.key] !== undefined) {
                    metrics[metricKey] = langMetrics[metric.key];
                }
            }
        }
    }
    return metrics;
}

function averageResults(inputs) {
    const reportFiles = inputs.map(findReportFile).filter(Boolean);
    const reports = reportFiles.map(parseReport).filter(Boolean);

    if (reports.length === 0) {
        throw new Error(`No valid reports found for directories: ${inputs.join(', ')}`);
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

function buildStatus(baseValue, headValue, metricConfig) {
    if (typeof baseValue === 'number' && typeof headValue === 'number' && baseValue !== 0) {
        const change = ((headValue - baseValue) / baseValue) * 100;
        const changeStr = `${change > 0 ? '+' : ''}${change.toFixed(1)}%`;
        const isWorse = metricConfig.higherIsWorse ? change > 0 : change < 0;
        let status = ':question:';
        if (Math.abs(change) > metricConfig.threshold) {
            status = isWorse ? ':x: Regression' : ':white_check_mark: Improvement';
        } else {
            status = ':heavy_check_mark: OK';
        }
        return { changeStr, status };
    } else if (typeof headValue === 'number' && typeof baseValue !== 'number') {
        return { changeStr: 'New', status: ':sparkles:' };
    }
    return { changeStr: 'N/A', status: ':question:' };
}

function generateMarkdownReport(baseAvg, headAvg) {
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

        const { changeStr, status } = buildStatus(baseValue, headValue, metricConfig);

        markdown += `| ${metricConfig.name} | ${lang} | ${formatValue(baseValue, metricConfig)}${metricConfig.unit} | ${formatValue(headValue, metricConfig)}${metricConfig.unit} | ${changeStr} | ${status} |\n`;
    }

    return markdown;
}

function generateBlockKitReport(baseAvg, headAvg) {
    const headSha = process.env.HEAD_SHA_SHORT || 'HEAD';
    const baseSha = process.env.BASE_SHA_SHORT || 'BASE';

    const blocks = [];

    blocks.push({
        type: 'header',
        text: { type: 'plain_text', text: 'Daily Performance Report' }
    });
    blocks.push({
        type: 'context',
        elements: [
            { type: 'mrkdwn', text: `Comparison between \`master@${headSha}\` and \`master@${baseSha}\`.` }
        ]
    });
    blocks.push({ type: 'divider' });

    const allKeys = [...new Set([...Object.keys(baseAvg), ...Object.keys(headAvg)])].sort();

    for (const key of allKeys) {
        const [lang, metricKey] = key.split('.', 2);
        const metricConfig = METRICS_TO_TRACK.find(m => m.key === metricKey);
        if (!metricConfig) continue;

        const baseValue = baseAvg[key];
        const headValue = headAvg[key];
        const { changeStr, status } = buildStatus(baseValue, headValue, metricConfig);

        const fields = [
            { type: 'mrkdwn', text: `*Metric*\n${metricConfig.name}` },
            { type: 'mrkdwn', text: `*Scope*\n${lang}` },
            { type: 'mrkdwn', text: `*Baseline (${baseSha})*\n${formatValue(baseValue, metricConfig)}${metricConfig.unit}` },
            { type: 'mrkdwn', text: `*Current (${headSha})*\n${formatValue(headValue, metricConfig)}${metricConfig.unit}` },
            { type: 'mrkdwn', text: `*Change*\n${changeStr}` },
            { type: 'mrkdwn', text: `*Status*\n${status}` }
        ];

        blocks.push({ type: 'section', fields });
    }

    return JSON.stringify(blocks);
}

function main() {
    const args = process.argv.slice(2);

    // Format handling
    let format = 'markdown';
    const fmtArgIdx = args.findIndex(a => a.startsWith('--format=') || a === '--format' || a === '-f');
    if (fmtArgIdx !== -1) {
        const val = args[fmtArgIdx].includes('=') ? args[fmtArgIdx].split('=')[1] : args[fmtArgIdx + 1];
        if (val) format = val.trim().toLowerCase();
    }

    // Accept both directories and direct JSON file paths. We still separate base/head by naming pattern.
    const filtered = args.filter(a => !a.startsWith('--format'));
    const baseInputs = filtered.filter(arg => arg.includes('report-base-'));
    const headInputs = filtered.filter(arg => arg.includes('report-head-'));

    if (baseInputs.length === 0 || headInputs.length === 0) {
        console.error('Usage: node compare-perf-results.js [--format=markdown|blocks] <base-report-dirs...> <head-report-dirs...>');
        process.exit(1);
    }
    
    try {
        const baseAvg = averageResults(baseInputs);
        const headAvg = averageResults(headInputs);

        if (format === 'blocks') {
            const blocks = generateBlockKitReport(baseAvg, headAvg);
            console.log(blocks);
        } else {
            const report = generateMarkdownReport(baseAvg, headAvg);
            console.log(report);
        }
    } catch (error) {
        const headSha = process.env.HEAD_SHA_SHORT || 'HEAD';
        const baseSha = process.env.BASE_SHA_SHORT || 'BASE';

        if (format === 'blocks') {
            const blocks = [
                {
                    type: 'section',
                    text: { type: 'mrkdwn', text: `:warning: *Failed to generate performance report*\nComparison between \`master@${headSha}\` and \`master@${baseSha}\` failed.` }
                },
                {
                    type: 'section',
                    text: { type: 'mrkdwn', text: '```' + (error && error.stack ? error.stack : String(error)) + '```' }
                }
            ];
            console.log(JSON.stringify(blocks));
            process.exit(0);
        } else {
            console.error('Error generating performance report:', error.message);
            let markdown = `:warning: **Failed to generate performance report**\n\n`;
            markdown += `Comparison between \`master@${headSha}\` and \`master@${baseSha}\` failed.\n\n`;
            markdown += `\`\`\`\n${error && error.stack ? error.stack : String(error)}\n\`\`\``;
            console.log(markdown);
            process.exit(0); // Exit 0 so the error report is still sent
        }
    }
}

main();
