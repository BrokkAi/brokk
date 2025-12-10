                        package com.acme;

                        public class Foo {
                            public int compute(int a, int b) {
                                return Math.addExact(a, b);
                            }

                            /**
                                * Returns a greeting for the given name.
                                * @param name the name to greet.
                                * @return the greeting string.
                                */
                            public String greet(String name) {
                                            return "Hi, " + name;
                            }

                            private static int fib(int n) {
                                if (n < 2) return n;
                                int a = 0, b = 1;
                                for (int i = 2; i <= n; i++) {
                                    int next = Math.addExact(a, b);
                                    a = b;
                                    b = next;
                                }
                                return b;
                            }

                            /**
                             * Returns the current size.
                             */
                            public int size() {
                                return 0;
                            }

                            /** A simple value holder. */
                            public static class Result {
                                public final int value;

                                public Result(int value) {
                                    this.value = value;
                                }
                            }
                            }
