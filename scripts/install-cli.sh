#!/bin/bash

set -e

JBANG_DEFAULT_JAVA_VERSION=21 curl -fsSL https://sh.jbang.dev | bash -s - app setup
export PATH="$HOME/.jbang/bin:$PATH"

jbang trust add https://github.com/BrokkAi/brokk-releases
jbang trust add https://github.com/BrokkAi/brokk-releases/releases/download/
jbang app install brokk@brokkai/brokk-releases

brokk login $userkey
brokk install
