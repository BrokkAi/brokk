#!/bin/bash

# TreeSitter Performance Baseline Runner (Simplified)
# Convenient wrapper for the TreeSitterRepoRunner utility

set -e

# Configuration
JAVA_OPTS="-Xmx8g -XX:+UseZGC -XX:+UnlockExperimentalVMOptions"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Functions
print_usage() {
    echo "TreeSitter Performance Baseline Runner (Simplified)"
    echo
    echo "Usage: $0 <command> [options]"
    echo
    echo "Commands:"
    echo "  setup          Download/clone all test projects"
    echo "  full           Run comprehensive baseline suite"
    echo "  stress         Memory stress test until OutOfMemoryError"
    echo "  chromium-cpp   Test Chromium C++ analysis specifically"
    echo "  vscode-ts      Test VS Code TypeScript analysis"
    echo "  openjdk-java   Test OpenJDK Java analysis"
    echo "  spring-java    Test Spring Framework Java analysis"
    echo "  java-frameworks Compare Java frameworks (Kafka, Hibernate, Spring)"
    echo "  java-enterprise Test large Java projects (Elasticsearch, IntelliJ)"
    echo "  multi-lang     Multi-language analysis on Chromium"
    echo "  quick          Quick test with smaller file counts"
    echo "  directory      Analyze files in a specific directory"
    echo "  cleanup        Clean up report files from output directory"
    echo
    echo "Options:"
    echo "  --max-files N     Maximum files to process (default: 1000)"
    echo "  --memory-profile  Enable detailed memory profiling"
    echo "  --verbose         Enable verbose logging"
    echo "  --json            Output results in JSON format"
    echo "  --show-details    Show symbols found in each file"
    echo "  --projects-dir D  Base directory for test projects (default: ../test-projects)"
    echo "  --directory D     Custom directory to analyze instead of predefined projects (use absolute paths)"
    echo "  --patterns P      File patterns to match, e.g., '*.java,*.cpp'"
    echo "  --cleanup         Clean up report files before running command"
    echo
    echo "Examples:"
    echo "  $0 setup                                    # Download all test projects to ../test-projects/"
    echo "  $0 setup --projects-dir /shared/projects    # Download to custom location"
    echo "  $0 full --memory-profile                    # Full baseline with memory profiling"
    echo "  $0 stress                                   # Find memory limits"
    echo "  $0 chromium-cpp --max-files 500             # Test Chromium with 500 C++ files"
    echo "  $0 java-frameworks                          # Compare Java framework performance"
    echo "  $0 openjdk-java --max-files 2000            # Test OpenJDK with 2000 Java files"
    echo "  $0 directory --directory /absolute/path/to/project --language java    # Test custom directory"
    echo "  $0 stress --directory /absolute/path/to/project --language cpp       # Stress test custom directory"
    echo "  $0 --cleanup openjdk-java                       # Clean up old reports before testing OpenJDK"
    echo "  $0 cleanup                                       # Clean up all report files"
    echo
    echo "Note: This is a simplified version compatible with master branch."
    echo "      Some advanced features may not be available."
    echo
}

build_if_needed() {
    if [[ ! -d "app/build/classes" ]]; then
        echo -e "${YELLOW}Building project...${NC}"
        ./gradlew compileJava compileTestJava
    fi
}

run_java() {
    local cmd="$1"
    shift

    # Use Gradle task with proper classpath
    local args_str="$cmd"
    for arg in "$@"; do
        args_str="$args_str $arg"
    done

    echo -e "${BLUE}Running: ./gradlew runTreeSitterRepoRunner -Pargs=\"$args_str\"${NC}"
    ./gradlew runTreeSitterRepoRunner -Pargs="$args_str"
}

# Parse command
if [[ $# -eq 0 ]]; then
    print_usage
    exit 1
fi

COMMAND="$1"
shift

# Ensure project is built
build_if_needed

case "$COMMAND" in
    setup)
        echo -e "${GREEN}Setting up test projects...${NC}"
        run_java "setup-projects" "$@"
        ;;

    full)
        echo -e "${GREEN}Running comprehensive baselines...${NC}"
        echo -e "${YELLOW}This will take a long time and may hit OutOfMemoryError${NC}"
        run_java "run-baselines" "$@"
        ;;

    stress)
        echo -e "${GREEN}Running memory stress test...${NC}"
        echo -e "${YELLOW}This will run until OutOfMemoryError to find limits${NC}"
        run_java "memory-stress" "$@"
        ;;

    chromium-cpp)
        echo -e "${GREEN}Testing Chromium C++ analysis...${NC}"
        run_java "test-project" --project chromium --language cpp "$@"
        ;;

    vscode-ts)
        echo -e "${GREEN}Testing VS Code TypeScript analysis...${NC}"
        run_java "test-project" --project vscode --language typescript "$@"
        ;;

    openjdk-java)
        echo -e "${GREEN}Testing OpenJDK Java analysis...${NC}"
        run_java "test-project" --project openjdk --language java "$@"
        ;;

    spring-java)
        echo -e "${GREEN}Testing Spring Framework Java analysis...${NC}"
        run_java "test-project" --project spring-framework --language java "$@"
        ;;

    java-frameworks)
        echo -e "${GREEN}Testing Java frameworks comparison...${NC}"
        echo "Testing Kafka..."
        run_java "test-project" --project kafka --language java --max-files 500
        echo "Testing Hibernate..."
        run_java "test-project" --project hibernate-orm --language java --max-files 500
        echo "Testing Spring..."
        run_java "test-project" --project spring-framework --language java --max-files 500
        ;;

    java-enterprise)
        echo -e "${GREEN}Testing enterprise Java projects...${NC}"
        echo "Testing Elasticsearch..."
        run_java "test-project" --project elasticsearch --language java --max-files 1000
        echo "Testing IntelliJ Community..."
        run_java "test-project" --project intellij-community --language java --max-files 1000
        ;;

    multi-lang)
        echo -e "${GREEN}Running multi-language analysis...${NC}"
        run_java "multi-language" "$@"
        ;;

    quick)
        echo -e "${GREEN}Running quick baseline test...${NC}"
        run_java "run-baselines" --max-files 100
        ;;

    directory)
        echo -e "${GREEN}Running directory analysis...${NC}"
        run_java "test-project" "$@"
        ;;

    cleanup)
        echo -e "${GREEN}Cleaning up report files...${NC}"
        run_java "cleanup" "$@"
        ;;

    help|--help|-h)
        print_usage
        ;;

    *)
        echo -e "${RED}Unknown command: $COMMAND${NC}"
        print_usage
        exit 1
        ;;
esac

echo -e "${GREEN}Baseline execution completed!${NC}"
echo "Results saved in: baseline-results/"