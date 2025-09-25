import {writable} from 'svelte/store';
import type {BrokkEvent, BubbleState, HistoryTask} from '../types';
import type {ResultMsg} from '../worker/shared';
import {parse} from '../worker/worker-bridge';
import {register, unregister} from '../worker/parseRouter';
import { getNextThreadId, threadStore } from './threadStore';

export const historyStore = writable<HistoryTask[]>([]);

// Start history bubble sequences at a high number to avoid any collision with the main bubblesStore
let nextHistoryBubbleSeq = 1_000_000;

function handleParseResult(msg: ResultMsg, threadId: number): void {
    historyStore.update(currentTasks => {
        return currentTasks.map(task => {
            if (task.threadId === threadId) {
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
                threadStore.clearThreadsByType('history');
                return [];

            case 'history-task': {
                const threadId = getNextThreadId();
                const entries: BubbleState[] = [];
                if (evt.compressed && evt.summary) {
                    entries.push({
                        seq: nextHistoryBubbleSeq++,
                        threadId: threadId,
                        type: 'SYSTEM',
                        markdown: evt.summary,
                        streaming: false,
                    });
                } else {
                    (evt.messages ?? []).forEach(msg => {
                        entries.push({
                            seq: nextHistoryBubbleSeq++,
                            threadId: threadId,
                            type: msg.msgType,
                            markdown: msg.text,
                            streaming: false,
                        });
                    });
                }

                const newTask: HistoryTask = {
                    threadId: threadId,
                    taskSequence: evt.taskSequence,
                    compressed: evt.compressed,
                    entries: entries,
                };

                threadStore.setThreadCollapsed(newTask.threadId, true, 'history');

                // Parse all new entries and register result handlers
                newTask.entries.forEach(entry => {
                    register(entry.seq, (msg: ResultMsg) => handleParseResult(msg, newTask.threadId));
                    // First a fast pass, then a deferred full pass for syntax highlighting
                    parse(entry.markdown, entry.seq, true);
                    setTimeout(() => parse(entry.markdown, entry.seq), 0);
                });

                // Insert in order of sequence
                const newTasks = [...tasks, newTask];
                newTasks.sort((a, b) => a.threadId - b.threadId);
                return newTasks;
            }
        }
        return tasks;
    });
}

export function reparseAll(): void {
    historyStore.update(tasks => {
        for (const task of tasks) {
            for (const entry of task.entries) {
                // Re-register the handler to update the correct task entry
                register(entry.seq, (msg: ResultMsg) => handleParseResult(msg, task.threadId));
                // Re-parse for syntax highlighting, don't update worker buffer
                parse(entry.markdown, entry.seq, false, false);
            }
        }
        return tasks;
    });
}

export function deleteHistoryTaskByThreadId(threadId: number): void {
    historyStore.update(tasks => {
        const task = tasks.find(t => t.threadId === threadId);
        if (task) {
            // Notify backend to drop this history entry by TaskEntry.sequence
            window.javaBridge?.deleteHistoryTask?.(task.taskSequence);
            // Optimistic local cleanup
            task.entries.forEach(entry => unregister(entry.seq));
        }
        return tasks.filter(t => t.threadId !== threadId);
    });
}

