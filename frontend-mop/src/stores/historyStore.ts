import {writable} from 'svelte/store';
import type {BrokkEvent, BubbleState, HistoryTask} from '../types';
import type {ResultMsg} from '../worker/shared';
import {parse} from '../worker/worker-bridge';
import {register, unregister} from '../worker/parseRouter';

export const historyStore = writable<HistoryTask[]>([]);

// Start history bubble sequences at a high number to avoid any collision with the main bubblesStore
let nextHistoryBubbleSeq = 1_000_000;

function handleParseResult(msg: ResultMsg, taskSequence: number): void {
    historyStore.update(currentTasks => {
        return currentTasks.map(task => {
            if (task.sequence === taskSequence) {
                return {
                    ...task,
                    entries: task.entries.map(e => (e.seq === msg.seq ? {...e, hast: msg.tree} : e)),
                };
            }
            return task;
        });
    });
}

export function onHistoryEvent(evt: BrokkEvent): void {
    if (evt.type !== 'history-reset' && evt.type !== 'history-task') {
        return;
    }

    historyStore.update(tasks => {
        switch (evt.type) {
            case 'history-reset':
                tasks.forEach(task => task.entries.forEach(entry => unregister(entry.seq)));
                return [];

            case 'history-task': {
                // Ignore duplicate tasks
                if (tasks.some(t => t.sequence === evt.sequence)) {
                    return tasks;
                }

                const entries: BubbleState[] = [];
                if (evt.compressed && evt.summary) {
                    entries.push({
                        seq: nextHistoryBubbleSeq++,
                        type: 'SYSTEM',
                        markdown: evt.summary,
                        streaming: false,
                    });
                } else {
                    (evt.messages ?? []).forEach(msg => {
                        entries.push({
                            seq: nextHistoryBubbleSeq++,
                            type: msg.msgType,
                            markdown: msg.text,
                            streaming: false,
                        });
                    });
                }

                const newTask: HistoryTask = {
                    sequence: evt.sequence,
                    title: evt.title,
                    messageCount: evt.messageCount,
                    compressed: evt.compressed,
                    isCollapsed: true, // Always collapse historical tasks by default
                    entries: entries,
                };

                // Parse all new entries and register result handlers
                newTask.entries.forEach(entry => {
                    register(entry.seq, (msg: ResultMsg) => handleParseResult(msg, newTask.sequence));
                    // First a fast pass, then a deferred full pass for syntax highlighting
                    parse(entry.markdown, entry.seq, true);
                    setTimeout(() => parse(entry.markdown, entry.seq), 0);
                });

                // Insert in order of sequence
                const newTasks = [...tasks, newTask];
                newTasks.sort((a, b) => a.sequence - b.sequence);
                return newTasks;
            }
        }
        return tasks;
    });
}

export function toggleTaskCollapsed(sequence: number): void {
    historyStore.update(tasks =>
        tasks.map(task =>
            task.sequence === sequence ? {...task, isCollapsed: !task.isCollapsed} : task
        )
    );
}
