# Brokk Code Agent Guide

This project is the Python-based Terminal User Interface (TUI) client for Brokk. It provides an interactive interface for users to work with the Brokk Headless Executor.

## Environment & Requirements

- **Python Version**: 3.11 or higher is required.
- **Key Dependencies**:
  - `textual`: For building the TUI.
  - `httpx`: For asynchronous communication with the executor.

## Communication Architecture

This project acts as a client that communicates with the Java-based Brokk executor via an HTTP API.
- The TUI spawns the Java executor as a subprocess.
- It authenticates using a bearer token generated at startup.
- It streams job events and updates the UI based on state hints from the executor.

## Code Style & Standards

- **PEP 8**: Follow standard Python style guidelines.
- **Linting**: Use `ruff` for linting and formatting. 
- **Type Hints**: Use type hints for all function signatures and complex variables.
- **Naming**: Use `snake_case` for variables and functions, and `PascalCase` for classes.

## Testing

- **Framework**: Use `pytest` for all tests.
- **Location**: Place tests in the `tests/` directory.
- **Smoke Tests**: Maintain `test_smoke.py` to ensure basic app and executor manager instantiation works without starting the subprocess.

## Project Structure

- `brokk_code/`: Main package directory.
  - `app.py`: Main Textual Application class.
  - `executor.py`: Logic for managing the Java executor lifecycle and API calls.
  - `widgets/`: Custom Textual widgets (Chat, Context, TaskList).
  - `styles/`: TCSS files for application styling.
