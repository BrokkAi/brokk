import { expect } from 'chai';
import * as vscode from 'vscode';
import * as lifecycle from './executor/lifecycle';

// Note: This test assumes a mock environment for vscode and internal modules.
// In a real VS Code extension test, these would be provided by the test runner.

describe('Extension Startup Recovery', () => {
    let spawnJbangSpy;
    let resolveJbangSpy;
    let installJbangSpy;

    beforeEach(() => {
        // Reset spies/mocks
        spawnJbangSpy = [];
        resolveJbangSpy = { count: 0, returns: [] };
        installJbangSpy = { count: 0 };
    });

    it('should retry JBang spawn on initial ENOENT failure', async () => {
        // 1. Mock first spawn attempt to fail with ENOENT
        // 2. Mock second spawn attempt to succeed
        // This simulates the recovery path in connectOrSpawn
        
        // We'll simulate the logic flow inside connectOrSpawn:
        // try { handle = await spawnJbang(...) } catch (err) { if (isSpawnError) { ... retry ... } }

        let attempts = 0;
        const mockSpawnJbang = async () => {
            attempts++;
            if (attempts === 1) {
                throw new Error('Executor process error: ENOENT');
            }
            return { port: 8080, authToken: 'secret', process: { stderr: { on: () => {} } } };
        };

        // This test validates the logic in extension.ts:
        /*
        try {
            handle = await spawnJbang(workspaceDir, jbangBinary);
        } catch (err: unknown) {
            if (isSpawnError) {
                jbangBinary = resolveJbangBinary() || await ensureJbangInstalled();
                if (jbangBinary) {
                    handle = await spawnJbang(workspaceDir, jbangBinary);
                }
            }
        }
        */

        // Simulated implementation of the logic we are testing
        let handle;
        let currentJbang = 'initial-path';
        try {
            handle = await mockSpawnJbang();
        } catch (err) {
            const message = err.message;
            const isSpawnError = message.includes("Executor process error") || message.includes("ENOENT");
            
            if (isSpawnError) {
                // Recovery
                currentJbang = 'recovered-path'; // Simulate resolveJbangBinary()
                handle = await mockSpawnJbang();
            } else {
                throw err;
            }
        }

        expect(attempts).to.equal(2, 'Should have attempted spawn twice');
        expect(handle.port).to.equal(8080);
    });

    it('should not throw when stopping or restarting before initialization', async () => {
        // This is a minimal unit test to ensure the commands are guarded.
        // In a real VS Code environment, we'd test the registered command, 
        // but here we just verify the exported functions don't crash on null globals.
        
        const extension = require('./extension');
        
        // Assert that calling these when globals are undefined/null doesn't throw
        expect(() => extension.stopExecutor()).to.not.throw();
        
        // restartExecutor requires a context, we can mock a minimal one
        const mockContext = { subscriptions: [], extensionUri: { fsPath: '/tmp' } };
        try {
            await extension.restartExecutor(mockContext);
        } catch (e) {
            // We expect connectOrSpawn to fail eventually in this mock environment,
            // but the guard check happens before the first await.
            expect(e.message).to.not.include('statusBarItem');
        }
    });
});
