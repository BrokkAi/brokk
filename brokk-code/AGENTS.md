# Brokk Code Agent Guide

## What this project is for

This project is a Python (Textual) terminal UI client for Brokk that launches and manages a local Java executor subprocess. It authenticates via an HTTP bearer token to submit jobs and stream real-time events or tokens, presenting an interactive workspace through dedicated chat, context, and task panels.

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
