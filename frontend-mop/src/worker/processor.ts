import {unified, type Processor} from 'unified';
import type {Root as HastRoot} from 'hast';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkBreaks from 'remark-breaks';
import remarkRehype from 'remark-rehype';
import {shikiPluginPromise} from './shiki/shiki-plugin';
import type {OutboundFromWorker, ShikiReadyMsg} from './shared';

function post(msg: OutboundFromWorker) {
    self.postMessage(msg);
}

export function createBaseProcessor(): Processor {
    return unified()
        .use(remarkParse)
        .use(remarkGfm)
        .use(remarkBreaks)
        .use(remarkRehype, {allowDangerousHtml: true});
}

let baseProcessor: Processor = createBaseProcessor();
let currentProcessor: Processor = baseProcessor;

export function initProcessor() {
    // Asynchronously initialize Shiki and create a new processor with it.
    console.log('[shiki] loading lib...');
    shikiPluginPromise
        .then(({rehypePlugin}) => {
            const [pluginFn, highlighter, opts] = rehypePlugin as any;
            const shikiProcessor = createBaseProcessor().use(pluginFn, highlighter, opts);
            currentProcessor = shikiProcessor;
            console.log('[shiki] loaded!');
            post(<ShikiReadyMsg>{type: 'shiki-ready'});
        })
        .catch(e => {
            console.error('[md-worker] Shiki init failed', e);
        });
}

export function parseMarkdown(src: string, fast = false): HastRoot {
    const timeLabel = fast ? 'parse (fast)' : 'parse';
    console.time(timeLabel);
    const proc = fast ? baseProcessor : currentProcessor;
    const result = proc.runSync(proc.parse(src)) as HastRoot;
    console.timeEnd(timeLabel);
    return result;
}
