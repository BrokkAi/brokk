/*
 * Copyright (c) 2021 by Gerrit Grunwald
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hansolo.fx.jdkmon.tools;

import eu.hansolo.jdktools.Architecture;
import eu.hansolo.jdktools.OperatingSystem;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.util.Locale;
import java.util.stream.Collectors;

public class Detector {

    private static final String[] DETECT_ALPINE_CMDS = {
        "/bin/sh", "-c", "cat /etc/os-release | grep 'NAME=' | grep -ic 'Alpine'"
    };
    public static final String SDKMAN_FOLDER = new StringBuilder(System.getProperty("user.home"))
            .append(File.separator)
            .append(".sdkman")
            .append(File.separator)
            .append("candidates")
            .append(File.separator)
            .append("java")
            .toString();

    public static final OperatingSystem getOperatingSystem() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.indexOf("win") >= 0) {
            return OperatingSystem.WINDOWS;
        } else if (os.indexOf("mac") >= 0) {
            return OperatingSystem.MACOS;
        } else if (os.indexOf("nix") >= 0 || os.indexOf("nux") >= 0) {
            try {
                final ProcessBuilder processBuilder = new ProcessBuilder(DETECT_ALPINE_CMDS);
                final Process process = processBuilder.start();
                final String result = new BufferedReader(new InputStreamReader(process.getInputStream()))
                        .lines()
                        .collect(Collectors.joining("\n"));
                return null == result
                        ? OperatingSystem.LINUX
                        : result.equals("1") ? OperatingSystem.ALPINE_LINUX : OperatingSystem.LINUX;
            } catch (IOException e) {
                e.printStackTrace();
                return OperatingSystem.LINUX;
            }
        } else if (os.indexOf("sunos") >= 0) {
            return OperatingSystem.SOLARIS;
        } else {
            return OperatingSystem.NOT_FOUND;
        }
    }

    public static final Architecture getArchitecture() {
        final String arch = System.getProperty("os.arch").toLowerCase(Locale.ENGLISH);
        if (arch.contains("sparc")) return Architecture.SPARC;
        if (arch.contains("amd64") || arch.contains("86_64")) return Architecture.AMD64;
        if (arch.contains("86")) return Architecture.X86;
        if (arch.contains("s390x")) return Architecture.S390X;
        if (arch.contains("ppc64")) return Architecture.PPC64;
        if (arch.contains("arm") && arch.contains("64")) return Architecture.AARCH64;
        if (arch.contains("arm")) return Architecture.ARM;
        if (arch.contains("aarch64")) return Architecture.AARCH64;
        return Architecture.NOT_FOUND;
    }

    public static final boolean isSDKMANInstalled() {
        return new File(SDKMAN_FOLDER).exists();
    }

    // ******************** Internal Classes **********************************
    static class StreamReader extends Thread {
        private InputStream is;
        private StringWriter sw;

        StreamReader(InputStream is) {
            this.is = is;
            sw = new StringWriter();
        }

        public void run() {
            try {
                int c;
                while ((c = is.read()) != -1) sw.write(c);
            } catch (IOException e) {
                ;
            }
        }

        String getResult() {
            return sw.toString();
        }
    }
}
