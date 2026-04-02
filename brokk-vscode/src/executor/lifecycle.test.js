import { expect } from 'chai';
import * as lifecycle from './lifecycle';
import os from 'os';
import fs from 'fs';
import path from 'path';
import { execSync } from 'child_process';

describe('Lifecycle JBang Resolution', () => {
    let originalPlatform;
    let originalExistsSync;
    let originalExecSync;

    beforeEach(() => {
        originalPlatform = Object.getOwnPropertyDescriptor(process, 'platform');
        originalExistsSync = fs.existsSync;
        originalExecSync = execSync;
    });

    afterEach(() => {
        Object.defineProperty(process, 'platform', originalPlatform);
        fs.existsSync = originalExistsSync;
        // @ts-ignore
        execSync = originalExecSync;
    });

    function mockPlatform(platform) {
        Object.defineProperty(process, 'platform', { value: platform });
    }

    it('should prioritize .cmd over .ps1 and bare binary on Windows', () => {
        mockPlatform('win32');
        
        const home = os.homedir();
        const cmdPath = path.join(home, '.jbang', 'bin', 'jbang.cmd');
        const ps1Path = path.join(home, '.jbang', 'bin', 'jbang.ps1');
        const barePath = path.join(home, '.jbang', 'bin', 'jbang');

        // Mock PATH check to fail
        // @ts-ignore
        execSync = () => { throw new Error('not on path'); };

        const existingPaths = new Set([cmdPath, ps1Path, barePath]);
        fs.existsSync = (p) => existingPaths.has(p.toString());

        const resolved = lifecycle.resolveJbangBinary();
        expect(resolved).to.equal(cmdPath, 'Should prefer .cmd on Windows');
    });

    it('should return .ps1 if .cmd is missing on Windows', () => {
        mockPlatform('win32');
        const home = os.homedir();
        const ps1Path = path.join(home, '.jbang', 'bin', 'jbang.ps1');

        // @ts-ignore
        execSync = () => { throw new Error('not on path'); };
        fs.existsSync = (p) => p.toString() === ps1Path;

        const resolved = lifecycle.resolveJbangBinary();
        expect(resolved).to.equal(ps1Path);
    });

    it('should prefer jbang from PATH if available', () => {
        mockPlatform('win32');
        const pathJbang = 'C:\\Users\\Test\\AppData\\Local\\bin\\jbang.exe';
        
        // @ts-ignore
        execSync = (cmd) => {
            if (cmd.startsWith('where')) return pathJbang;
            throw new Error('unexpected command');
        };

        const resolved = lifecycle.resolveJbangBinary();
        expect(resolved).to.equal(pathJbang);
    });
});
