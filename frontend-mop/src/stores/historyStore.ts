import {writable} from 'svelte/store';
import type {BrokkEvent, BubbleState, HistoryTask} from '../types';
import type {ResultMsg} from '../worker/shared';
import {parse} from '../worker/worker-bridge';
import {register, unregister, isRegistered} from '../worker/parseRouter';
import { getNextThreadId, threadStore } from './threadStore';
import { setSummaryParseEntry, updateSummaryParseTree, deleteSummaryParseEntry, clearAllSummaryParseEntries, getSummaryParseEntry } from './summaryParseStore';

export const historyStore = writable<HistoryTask[]>([]);

// Start history bubble sequences at a high number to avoid any collision with the main bubblesStore
let nextHistoryBubbleSeq = 1_000_000;

// Start summary parse sequences at an even higher number to avoid collisions
let nextSummaryParseSeq = 2_000_000;

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

function handleSummaryParseResult(msg: ResultMsg, threadId: number): void {
    updateSummaryParseTree(threadId, msg.tree);
}

export function onHistoryEvent(evt: BrokkEvent): void {
    if (evt.type !== 'history-reset' && evt.type !== 'history-task') {
        return;
    }

    historyStore.update(tasks => {
        switch (evt.type) {
            case 'history-reset':
                tasks.forEach(task => {
                    task.entries.forEach(entry => unregister(entry.seq));
                    const s = getSummaryParseEntry(task.threadId);
                    if (s) {
                        unregister(s.seq);
                    }
                });
                clearAllSummaryParseEntries();
                threadStore.clearThreadsByType('history');
                return [];

            case 'history-task': {
                const threadId = getNextThreadId();
                const entries: BubbleState[] = [];
                
                // Build bubbles from messages first
                (evt.messages ?? []).forEach(msg => {
                    const isReasoning = !!msg.reasoning;
                    entries.push({
                        seq: nextHistoryBubbleSeq++,
                        threadId: threadId,
                        type: msg.msgType,
                        markdown: msg.text,
                        streaming: false,
                        reasoning: isReasoning,
                        reasoningComplete: isReasoning,
                        isCollapsed: isReasoning,
                    });
                });

                const newTask: HistoryTask = {
                    threadId: threadId,
                    taskSequence: evt.taskSequence,
                    compressed: evt.compressed,
                    summary: evt.summary,
                    entries: entries,
                };

                threadStore.setThreadCollapsed(newTask.threadId, true, 'history');

                // Parse all new entries and register result handlers
                newTask.entries.forEach(entry => {
                    register(entry.seq, (msg: ResultMsg) => handleParseResult(msg, newTask.threadId));
                    // First a fast pass, then a deferred full pass for syntax highlighting
                    // Use updateBuffer=false to avoid colliding with live streaming buffer
                    parse(entry.markdown, entry.seq, true, false);
                    // slow parse can block around 50ms, delay it to give main area/live streaming time to post results between slow parses or before
                    setTimeout(() => {
                        if (isRegistered(entry.seq)) {
                            parse(entry.markdown, entry.seq, false, false);
                        }
                    }, 100);
                });

                // Parse summary if present and compressed
                if (evt.compressed && evt.summary) {
                    const summarySeq = nextSummaryParseSeq++;
                    setSummaryParseEntry(threadId, {
                        seq: summarySeq,
                        text: evt.summary,
                    });
                    register(summarySeq, (msg: ResultMsg) => handleSummaryParseResult(msg, threadId));
                    parse(evt.summary, summarySeq, false, false);
                }

                // Insert in order of sequence
                const newTasks = [...tasks, newTask];
                newTasks.sort((a, b) => a.threadId - b.threadId);
                return newTasks;
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
            task.entries.forEach(entry => unregister(entry.seq));
            // Unregister summary parse seq and clear the entry
            const summaryEntry = getSummaryParseEntry(threadId);
            if (summaryEntry) {
                unregister(summaryEntry.seq);
            }
            deleteSummaryParseEntry(threadId);
        }
        // notifying backend triggers a history-reset event, which clears the store
        return tasks;
    });
}

export function reparseAll(): void {
    historyStore.update(tasks => {
        for (const task of tasks) {
            for (const entry of task.entries) {
                register(entry.seq, (msg: ResultMsg) => handleParseResult(msg, task.threadId));
                parse(entry.markdown, entry.seq, false, false);
            }
            if (task.compressed && task.summary) {
                const summaryEntry = getSummaryParseEntry(task.threadId);
                if (summaryEntry) {
                    register(summaryEntry.seq, (msg: ResultMsg) => handleSummaryParseResult(msg, task.threadId));
                    parse(task.summary, summaryEntry.seq, false, false);
                }
            }
        }
        return tasks;
    });
}

export function reparseAllOnLangsReady(): void {
    reparseAll();
}
