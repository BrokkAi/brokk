"""Settings modal screens for the Brokk TUI."""

import logging
import shlex
from typing import Any, Dict, List, Optional

from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import (
    Button,
    Checkbox,
    DataTable,
    Input,
    ListItem,
    ListView,
    RadioButton,
    RadioSet,
    Select,
    Static,
    TabbedContent,
    TabPane,
    TextArea,
)

TIMEOUT_OPTIONS: list[tuple[str, str]] = [
    ("No timeout", "-1"),
    ("30", "30"),
    ("60", "60"),
    ("120", "120"),
    ("300", "300"),
    ("600", "600"),
    ("1800", "1800"),
    ("3600", "3600"),
    ("10800", "10800"),
]

# Lazy import to avoid circular dependency
# TaskTitleModalScreen is defined in app.py and used by SettingsModalScreen
# for the exclusion pattern add dialog
_TaskTitleModalScreen = None


def _get_task_title_modal():
    global _TaskTitleModalScreen
    if _TaskTitleModalScreen is None:
        from brokk_code.app import TaskTitleModalScreen

        _TaskTitleModalScreen = TaskTitleModalScreen
    return _TaskTitleModalScreen


logger = logging.getLogger(__name__)


class ModuleEditModalScreen(ModalScreen[Optional[Dict[str, str]]]):
    """Small modal to add or edit a module build entry."""

    BINDINGS = [
        Binding("escape", "dismiss", "Cancel", show=False),
    ]

    def __init__(
        self,
        title: str = "Add Module",
        *,
        alias: str = "",
        language: str = "",
        relative_path: str = "",
        build_lint_command: str = "",
        test_all_command: str = "",
        test_some_command: str = "",
    ) -> None:
        super().__init__()
        self._title = title
        self._alias = alias
        self._language = language
        self._relative_path = relative_path
        self._build_lint_command = build_lint_command
        self._test_all_command = test_all_command
        self._test_some_command = test_some_command

    def compose(self) -> ComposeResult:
        with Vertical(id="module-edit-modal-container"):
            yield Static(self._title, id="module-edit-modal-title")
            yield Static("Alias:", classes="module-edit-label")
            yield Input(value=self._alias, placeholder="e.g., backend", id="module-edit-alias")
            yield Static("Language:", classes="module-edit-label")
            yield Input(value=self._language, placeholder="e.g., Java", id="module-edit-language")
            yield Static("Relative Path:", classes="module-edit-label")
            yield Input(
                value=self._relative_path,
                placeholder="e.g., backend/",
                id="module-edit-path",
            )
            yield Static("Build/Lint Command:", classes="module-edit-label")
            yield Input(
                value=self._build_lint_command,
                placeholder="e.g., ./gradlew build",
                id="module-edit-build-cmd",
            )
            yield Static("Test All Command:", classes="module-edit-label")
            yield Input(
                value=self._test_all_command,
                placeholder="e.g., ./gradlew test",
                id="module-edit-test-all-cmd",
            )
            yield Static("Test Some Command:", classes="module-edit-label")
            yield Input(
                value=self._test_some_command,
                placeholder="e.g., ./gradlew test --tests {{#classes}}",
                id="module-edit-test-some-cmd",
            )
            with Horizontal(id="module-edit-modal-actions"):
                yield Button("Save", id="module-edit-save", variant="primary")
                yield Button("Cancel", id="module-edit-cancel")

    def on_mount(self) -> None:
        self.query_one("#module-edit-alias", Input).focus()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        button_id = event.button.id or ""
        if button_id == "module-edit-save":
            self._do_save()
        elif button_id == "module-edit-cancel":
            self.dismiss(None)

    def on_input_submitted(self, event: Input.Submitted) -> None:
        input_id = event.input.id or ""
        field_order = [
            "module-edit-alias",
            "module-edit-language",
            "module-edit-path",
            "module-edit-build-cmd",
            "module-edit-test-all-cmd",
            "module-edit-test-some-cmd",
        ]
        if input_id in field_order:
            idx = field_order.index(input_id)
            if idx < len(field_order) - 1:
                self.query_one(f"#{field_order[idx + 1]}", Input).focus()
            else:
                self._do_save()

    def _do_save(self) -> None:
        result = {
            "alias": self.query_one("#module-edit-alias", Input).value.strip(),
            "language": self.query_one("#module-edit-language", Input).value.strip(),
            "relativePath": self.query_one("#module-edit-path", Input).value.strip(),
            "buildLintCommand": self.query_one("#module-edit-build-cmd", Input).value.strip(),
            "testAllCommand": self.query_one("#module-edit-test-all-cmd", Input).value.strip(),
            "testSomeCommand": self.query_one("#module-edit-test-some-cmd", Input).value.strip(),
        }
        self.dismiss(result)


class SettingsModalScreen(ModalScreen[None]):
    """Full-screen modal for project settings configuration."""

    BINDINGS = [
        Binding("escape", "dismiss", "Cancel", show=False),
    ]

    def __init__(self) -> None:
        super().__init__()
        self._settings_data: Optional[Dict[str, Any]] = None
        self._exclusion_patterns: List[str] = []
        self._modules: List[Dict[str, str]] = []
        self._shell_executable: str = ""
        self._shell_args: str = ""
        self._issue_provider_type: str = "NONE"
        self._github_override: bool = False
        self._analyzer_languages: List[Dict[str, str]] = []
        self._configured_languages: List[str] = []

    def compose(self) -> ComposeResult:
        with Vertical(id="settings-modal-container"):
            yield Static("Settings", id="settings-modal-title")
            with TabbedContent(id="settings-tabs"):
                with TabPane("Build", id="settings-tab-build"):
                    with VerticalScroll(classes="settings-tab-scroll"):
                        # Build Configuration Section
                        yield Static("Build Configuration", classes="settings-section-title")

                        with Horizontal(classes="settings-row"):
                            yield Static("Build/Lint Command:", classes="settings-label")
                            yield Input(
                                placeholder="e.g., make lint", id="settings-build-lint-command"
                            )
                            yield Checkbox("Enabled", id="settings-build-lint-enabled", value=True)

                        with Horizontal(classes="settings-row"):
                            yield Static("Test All Command:", classes="settings-label")
                            yield Input(
                                placeholder="e.g., make test", id="settings-test-all-command"
                            )
                            yield Checkbox("Enabled", id="settings-test-all-enabled", value=True)

                        with Horizontal(classes="settings-row"):
                            yield Static("Test Some Command:", classes="settings-label")
                            yield Input(
                                placeholder="e.g., make test TESTS={testfiles}",
                                id="settings-test-some-command",
                            )
                            yield Checkbox("Enabled", id="settings-test-some-enabled", value=True)

                        with Horizontal(classes="settings-row"):
                            yield Static("Post-Task-List Command:", classes="settings-label")
                            yield Input(
                                placeholder="Command to run after task list completes",
                                id="settings-after-tasklist-command",
                            )

                        with Horizontal(classes="settings-row"):
                            yield Static("Code Agent Test Scope:", classes="settings-label")
                            with RadioSet(id="settings-test-scope"):
                                yield RadioButton(
                                    "Run All Tests", id="settings-scope-all", value=True
                                )
                                yield RadioButton(
                                    "Run Tests in Workspace", id="settings-scope-workspace"
                                )

                        with Horizontal(classes="settings-row"):
                            yield Static("Run Command Timeout (sec):", classes="settings-label")
                            yield Select(
                                TIMEOUT_OPTIONS,
                                id="settings-run-timeout",
                                allow_blank=False,
                                value="-1",
                            )

                        with Horizontal(classes="settings-row"):
                            yield Static("Test Command Timeout (sec):", classes="settings-label")
                            yield Select(
                                TIMEOUT_OPTIONS,
                                id="settings-test-timeout",
                                allow_blank=False,
                                value="-1",
                            )

                        # Modules Section
                        yield Static("Modules", classes="settings-section-title")
                        yield Static(
                            "Configure build commands for submodules/subprojects",
                            classes="settings-hint",
                        )
                        yield DataTable(id="settings-modules-table", cursor_type="row")
                        with Horizontal(classes="settings-list-actions"):
                            yield Button("Add", id="settings-module-add", variant="primary")
                            yield Button("Edit", id="settings-module-edit")
                            yield Button("Remove", id="settings-module-remove")
                            yield Button("\u25b2", id="settings-module-up")
                            yield Button("\u25bc", id="settings-module-down")

                        # Shell Configuration Section
                        yield Static("Shell Configuration", classes="settings-section-title")
                        yield Static(
                            "Configure the shell used to execute build commands",
                            classes="settings-hint",
                        )

                        with Horizontal(classes="settings-row"):
                            yield Static("Execute With:", classes="settings-label")
                            yield Input(
                                placeholder="e.g., /bin/sh",
                                id="settings-shell-executable",
                            )

                        with Horizontal(classes="settings-row"):
                            yield Static("Default Parameters:", classes="settings-label")
                            yield Input(
                                placeholder="e.g., -c",
                                id="settings-shell-args",
                            )

                with TabPane("Code Intelligence", id="settings-tab-ci"):
                    with VerticalScroll(classes="settings-tab-scroll"):
                        yield Static("Analyzer Languages", classes="settings-section-title")
                        yield Static(
                            "Select which languages to analyze for code intelligence",
                            classes="settings-hint",
                        )
                        with VerticalScroll(id="settings-languages-scroll"):
                            pass

                        yield Static("Code Intelligence", classes="settings-section-title")

                        yield Static("Exclusion Patterns:", classes="settings-subsection-label")
                        yield Static(
                            "Patterns to exclude from code intelligence"
                            " (e.g., build, node_modules, *.svg)",
                            classes="settings-hint",
                        )
                        yield ListView(id="settings-exclusion-list")
                        with Horizontal(classes="settings-list-actions"):
                            yield Button("Add", id="settings-exclusion-add", variant="primary")
                            yield Button("Remove", id="settings-exclusion-remove")

                        with Horizontal(classes="settings-row"):
                            yield Static(
                                "Auto-update Local Dependencies:", classes="settings-label"
                            )
                            yield Checkbox("", id="settings-auto-update-local", value=False)

                        with Horizontal(classes="settings-row"):
                            yield Static("Auto-update Git Dependencies:", classes="settings-label")
                            yield Checkbox("", id="settings-auto-update-git", value=False)

                with TabPane("Integrations", id="settings-tab-integrations"):
                    with VerticalScroll(classes="settings-tab-scroll"):
                        # Issue Provider Section
                        yield Static("Issue Provider", classes="settings-section-title")
                        yield Static(
                            "Configure issue tracker integration for fetching issues",
                            classes="settings-hint",
                        )

                        with Horizontal(id="settings-issue-provider-select"):
                            yield Button("None", id="settings-issue-none", variant="primary")
                            yield Button("GitHub", id="settings-issue-github")
                            yield Button("Jira", id="settings-issue-jira")

                        # None provider section
                        with Vertical(id="settings-issue-none-section"):
                            yield Static(
                                "No issue provider configured.",
                                id="settings-issue-none-text",
                            )

                        # GitHub provider section
                        with Vertical(id="settings-issue-github-section", classes="hidden"):
                            yield Checkbox(
                                "Fetch issues from a different GitHub repository",
                                id="settings-github-override",
                                value=False,
                            )
                            with Vertical(id="settings-github-fields", classes="hidden"):
                                with Horizontal(classes="settings-row"):
                                    yield Static("Owner:", classes="settings-label")
                                    yield Input(
                                        placeholder="e.g., owner", id="settings-github-owner"
                                    )
                                with Horizontal(classes="settings-row"):
                                    yield Static("Repository:", classes="settings-label")
                                    yield Input(placeholder="e.g., repo", id="settings-github-repo")
                                with Horizontal(classes="settings-row"):
                                    yield Static("Host (optional):", classes="settings-label")
                                    yield Input(placeholder="github.com", id="settings-github-host")
                            yield Static(
                                "If not overridden, issues are fetched from"
                                " the project's own GitHub repository.",
                                classes="settings-hint",
                            )

                        # Jira provider section
                        with Vertical(id="settings-issue-jira-section", classes="hidden"):
                            with Horizontal(classes="settings-row"):
                                yield Static("Base URL:", classes="settings-label")
                                yield Input(
                                    placeholder="https://yourcompany.atlassian.net",
                                    id="settings-jira-base-url",
                                )
                            with Horizontal(classes="settings-row"):
                                yield Static("API Token:", classes="settings-label")
                                yield Input(
                                    placeholder="API Token",
                                    password=True,
                                    id="settings-jira-api-token",
                                )
                            with Horizontal(classes="settings-row"):
                                yield Static("Project Key:", classes="settings-label")
                                yield Input(
                                    placeholder="e.g., CASSANDRA",
                                    id="settings-jira-project-key",
                                )

                with TabPane("General", id="settings-tab-general"):
                    with VerticalScroll(classes="settings-tab-scroll"):
                        yield Static("Project General", classes="settings-section-title")

                        yield Static("Commit Message Format:", classes="settings-subsection-label")
                        yield TextArea(id="settings-commit-format")

                        yield Static("Data Retention Policy:", classes="settings-subsection-label")
                        with RadioSet(id="settings-data-retention"):
                            yield RadioButton(
                                "Make Brokk Better for Everyone",
                                id="settings-retention-improve",
                                value=True,
                            )
                            yield RadioButton(
                                "Essential Use Only",
                                id="settings-retention-minimal",
                            )
                        yield Static(
                            "Data retention policy affects which AI models are available. "
                            "Deepseek models are not available under Essential Use Only policy.",
                            classes="settings-hint",
                        )

            yield Static("", id="settings-error")

            with Horizontal(id="settings-modal-actions"):
                yield Button("Save", id="settings-save", variant="primary")
                yield Button("Cancel", id="settings-cancel")

    def on_mount(self) -> None:
        self.query_one("#settings-build-lint-command", Input).focus()
        self.app.run_worker(self._load_settings())

    async def _load_settings(self) -> None:
        """Loads current settings from executor and populates form fields."""
        try:
            self._settings_data = await self.app.executor.get_settings()
            self._populate_form()
        except Exception as e:
            error_label = self.query_one("#settings-error", Static)
            error_label.update(f"[bold red]Failed to load settings: {e}[/]")

    def _populate_form(self) -> None:
        """Populates form fields with loaded settings data."""
        if not self._settings_data:
            return

        build_details = self._settings_data.get("buildDetails", {})
        project_settings = self._settings_data.get("projectSettings", {})

        # Build/Lint Command
        self.query_one("#settings-build-lint-command", Input).value = build_details.get(
            "buildLintCommand", ""
        )
        self.query_one("#settings-build-lint-enabled", Checkbox).value = build_details.get(
            "buildLintEnabled", True
        )

        # Test All Command
        self.query_one("#settings-test-all-command", Input).value = build_details.get(
            "testAllCommand", ""
        )
        self.query_one("#settings-test-all-enabled", Checkbox).value = build_details.get(
            "testAllEnabled", True
        )

        # Test Some Command
        self.query_one("#settings-test-some-command", Input).value = build_details.get(
            "testSomeCommand", ""
        )
        self.query_one("#settings-test-some-enabled", Checkbox).value = build_details.get(
            "testSomeEnabled", True
        )

        # Post-Task-List Command
        self.query_one("#settings-after-tasklist-command", Input).value = build_details.get(
            "afterTaskListCommand", ""
        )

        # Code Agent Test Scope
        scope = project_settings.get("codeAgentTestScope", "ALL")
        if scope == "WORKSPACE":
            self.query_one("#settings-scope-workspace", RadioButton).value = True
        else:
            self.query_one("#settings-scope-all", RadioButton).value = True

        # Run Command Timeout
        run_timeout = project_settings.get("runCommandTimeoutSeconds")
        self.query_one("#settings-run-timeout", Select).value = (
            str(run_timeout) if run_timeout is not None else "-1"
        )

        # Test Command Timeout
        test_timeout = project_settings.get("testCommandTimeoutSeconds")
        self.query_one("#settings-test-timeout", Select).value = (
            str(test_timeout) if test_timeout is not None else "-1"
        )

        # Code Intelligence - Exclusion Patterns
        raw_patterns = build_details.get("exclusionPatterns", [])
        self._exclusion_patterns = sorted(set(str(p) for p in raw_patterns if p), key=str.lower)
        self._refresh_exclusion_list()

        # Code Intelligence - Auto-update flags
        self.query_one("#settings-auto-update-local", Checkbox).value = project_settings.get(
            "autoUpdateLocalDependencies", False
        )
        self.query_one("#settings-auto-update-git", Checkbox).value = project_settings.get(
            "autoUpdateGitDependencies", False
        )

        # Modules
        raw_modules = build_details.get("modules", [])
        self._modules = [
            {
                "alias": str(m.get("alias", "")),
                "language": str(m.get("language", "")),
                "relativePath": str(m.get("relativePath", "")),
                "buildLintCommand": str(m.get("buildLintCommand", "")),
                "testAllCommand": str(m.get("testAllCommand", "")),
                "testSomeCommand": str(m.get("testSomeCommand", "")),
            }
            for m in raw_modules
            if isinstance(m, dict)
        ]
        self._refresh_modules_table()

        # Shell Configuration
        shell_config = self._settings_data.get("shellConfig", {})
        self._shell_executable = str(shell_config.get("executable", ""))
        self._shell_args = " ".join(shell_config.get("args", []))
        self.query_one("#settings-shell-executable", Input).value = self._shell_executable
        self.query_one("#settings-shell-args", Input).value = self._shell_args

        # Issue Provider
        issue_provider = self._settings_data.get("issueProvider", {})
        provider_type = str(issue_provider.get("type", "NONE")).upper()
        if provider_type not in ("NONE", "GITHUB", "JIRA"):
            provider_type = "NONE"
        self._set_issue_provider_type(provider_type)

        config = issue_provider.get("config", {})
        if provider_type == "GITHUB":
            owner = str(config.get("owner", ""))
            repo = str(config.get("repo", ""))
            host = str(config.get("host", ""))
            # Check if override is enabled (non-empty fields)
            has_override = bool(owner or repo or host)
            self._github_override = has_override
            self.query_one("#settings-github-override", Checkbox).value = has_override
            self._toggle_github_override(has_override)
            self.query_one("#settings-github-owner", Input).value = owner
            self.query_one("#settings-github-repo", Input).value = repo
            self.query_one("#settings-github-host", Input).value = host
        elif provider_type == "JIRA":
            self.query_one("#settings-jira-base-url", Input).value = str(config.get("baseUrl", ""))
            self.query_one("#settings-jira-api-token", Input).value = str(
                config.get("apiToken", "")
            )
            self.query_one("#settings-jira-project-key", Input).value = str(
                config.get("projectKey", "")
            )

        # Project General - Commit Message Format
        commit_format = project_settings.get("commitMessageFormat", "")
        self.query_one("#settings-commit-format", TextArea).text = commit_format

        # Project General - Data Retention Policy
        data_retention = project_settings.get("dataRetentionPolicy", "IMPROVE_BROKK")
        if data_retention == "MINIMAL":
            self.query_one("#settings-retention-minimal", RadioButton).value = True
        else:
            self.query_one("#settings-retention-improve", RadioButton).value = True

        # Analyzer Languages
        analyzer_langs = self._settings_data.get("analyzerLanguages", {})
        self._configured_languages = analyzer_langs.get("configured", [])
        available = analyzer_langs.get("available", [])
        detected = set(analyzer_langs.get("detected", []))

        # Build display list: union of configured + detected, sorted by name
        configured_set = set(self._configured_languages)
        show_langs = []
        for lang in available:
            internal = lang.get("internalName", "")
            if internal in configured_set or internal in detected:
                show_langs.append(lang)

        # Sort by display name
        show_langs.sort(key=lambda x: x.get("name", "").lower())
        self._analyzer_languages = show_langs

        # Populate the scroll container with Checkbox widgets
        lang_scroll = self.query_one("#settings-languages-scroll", VerticalScroll)
        for lang in self._analyzer_languages:
            internal = lang.get("internalName", "")
            name = lang.get("name", internal)
            is_configured = internal in configured_set
            cb = Checkbox(name, value=is_configured, id=f"settings-lang-{internal}")
            lang_scroll.mount(cb)

    def _refresh_exclusion_list(self) -> None:
        """Refreshes the exclusion patterns ListView."""
        list_view = self.query_one("#settings-exclusion-list", ListView)
        list_view.clear()
        for pattern in self._exclusion_patterns:
            list_view.append(ListItem(Static(pattern, markup=False)))

    def _refresh_modules_table(self) -> None:
        """Refreshes the modules DataTable."""
        table = self.query_one("#settings-modules-table", DataTable)
        table.clear(columns=True)
        table.add_columns("Alias", "Language", "Path")
        for module in self._modules:
            table.add_row(
                module.get("alias", ""),
                module.get("language", ""),
                module.get("relativePath", ""),
            )

    def _add_module(self) -> None:
        """Opens a modal to add a new module."""

        def on_result(result: Optional[Dict[str, str]]) -> None:
            if result:
                self._modules.append(result)
                self._refresh_modules_table()

        self.app.push_screen(ModuleEditModalScreen("Add Module"), on_result)

    def _get_selected_module_index(self) -> Optional[int]:
        """Returns the index of the selected row in the modules table, or None."""
        table = self.query_one("#settings-modules-table", DataTable)
        if table.cursor_row is not None and 0 <= table.cursor_row < len(self._modules):
            return table.cursor_row
        return None

    def _edit_selected_module(self) -> None:
        """Opens a modal to edit the selected module."""
        idx = self._get_selected_module_index()
        if idx is None:
            return
        module = self._modules[idx]

        def on_result(result: Optional[Dict[str, str]]) -> None:
            if result:
                self._modules[idx] = result
                self._refresh_modules_table()

        self.app.push_screen(
            ModuleEditModalScreen(
                "Edit Module",
                alias=module.get("alias", ""),
                language=module.get("language", ""),
                relative_path=module.get("relativePath", ""),
                build_lint_command=module.get("buildLintCommand", ""),
                test_all_command=module.get("testAllCommand", ""),
                test_some_command=module.get("testSomeCommand", ""),
            ),
            on_result,
        )

    def _remove_selected_module(self) -> None:
        """Removes the selected module."""
        idx = self._get_selected_module_index()
        if idx is not None:
            del self._modules[idx]
            self._refresh_modules_table()

    def _move_module(self, direction: int) -> None:
        """Moves the selected module up (-1) or down (+1)."""
        idx = self._get_selected_module_index()
        if idx is None:
            return
        new_idx = idx + direction
        if 0 <= new_idx < len(self._modules):
            self._modules[idx], self._modules[new_idx] = (
                self._modules[new_idx],
                self._modules[idx],
            )
            self._refresh_modules_table()
            table = self.query_one("#settings-modules-table", DataTable)
            table.move_cursor(row=new_idx)

    def _set_issue_provider_type(self, provider_type: str) -> None:
        """Sets the issue provider type and shows/hides corresponding sections."""
        self._issue_provider_type = provider_type

        # Update button variants
        none_btn = self.query_one("#settings-issue-none", Button)
        github_btn = self.query_one("#settings-issue-github", Button)
        jira_btn = self.query_one("#settings-issue-jira", Button)

        none_btn.variant = "primary" if provider_type == "NONE" else "default"
        github_btn.variant = "primary" if provider_type == "GITHUB" else "default"
        jira_btn.variant = "primary" if provider_type == "JIRA" else "default"

        # Show/hide sections
        none_section = self.query_one("#settings-issue-none-section")
        github_section = self.query_one("#settings-issue-github-section")
        jira_section = self.query_one("#settings-issue-jira-section")

        if provider_type == "NONE":
            none_section.remove_class("hidden")
            github_section.add_class("hidden")
            jira_section.add_class("hidden")
        elif provider_type == "GITHUB":
            none_section.add_class("hidden")
            github_section.remove_class("hidden")
            jira_section.add_class("hidden")
        elif provider_type == "JIRA":
            none_section.add_class("hidden")
            github_section.add_class("hidden")
            jira_section.remove_class("hidden")

    def _toggle_github_override(self, enabled: bool) -> None:
        """Toggles the GitHub override fields visibility."""
        self._github_override = enabled
        github_fields = self.query_one("#settings-github-fields")
        if enabled:
            github_fields.remove_class("hidden")
        else:
            github_fields.add_class("hidden")

    def on_checkbox_changed(self, event: Checkbox.Changed) -> None:
        """Handle checkbox changes for GitHub override toggle."""
        if event.checkbox.id == "settings-github-override":
            self._toggle_github_override(event.value)

    def on_button_pressed(self, event: Button.Pressed) -> None:
        button_id = event.button.id or ""
        if button_id == "settings-save":
            self.app.run_worker(self._save_settings())
        elif button_id == "settings-cancel":
            self.dismiss(None)
        elif button_id == "settings-exclusion-add":
            self._add_exclusion_pattern()
        elif button_id == "settings-exclusion-remove":
            self._remove_selected_exclusion()
        elif button_id == "settings-module-add":
            self._add_module()
        elif button_id == "settings-module-edit":
            self._edit_selected_module()
        elif button_id == "settings-module-remove":
            self._remove_selected_module()
        elif button_id == "settings-module-up":
            self._move_module(-1)
        elif button_id == "settings-module-down":
            self._move_module(1)
        elif button_id == "settings-issue-none":
            self._set_issue_provider_type("NONE")
        elif button_id == "settings-issue-github":
            self._set_issue_provider_type("GITHUB")
        elif button_id == "settings-issue-jira":
            self._set_issue_provider_type("JIRA")

    def on_data_table_row_selected(self, event: DataTable.RowSelected) -> None:
        """Handle Enter key on table rows to edit modules."""
        if event.data_table.id == "settings-modules-table":
            self._edit_selected_module()

    def _add_exclusion_pattern(self) -> None:
        """Opens a modal to add a new exclusion pattern."""

        def on_result(pattern: Optional[str]) -> None:
            if pattern and pattern.strip():
                normalized = pattern.strip()
                if normalized.lower() not in [p.lower() for p in self._exclusion_patterns]:
                    self._exclusion_patterns.append(normalized)
                    self._exclusion_patterns.sort(key=str.lower)
                    self._refresh_exclusion_list()

        self.app.push_screen(
            _get_task_title_modal()("Add Exclusion Pattern", initial=""), on_result
        )

    def _remove_selected_exclusion(self) -> None:
        """Removes the selected exclusion pattern."""
        list_view = self.query_one("#settings-exclusion-list", ListView)
        if list_view.index is not None and 0 <= list_view.index < len(self._exclusion_patterns):
            del self._exclusion_patterns[list_view.index]
            self._refresh_exclusion_list()

    async def _save_settings(self) -> None:
        """Saves form values via executor API."""
        error_label = self.query_one("#settings-error", Static)
        error_label.update("")

        try:
            # Collect build settings (including exclusion patterns and modules)
            build_data: Dict[str, Any] = {
                "buildLintCommand": self.query_one("#settings-build-lint-command", Input).value,
                "buildLintEnabled": self.query_one("#settings-build-lint-enabled", Checkbox).value,
                "testAllCommand": self.query_one("#settings-test-all-command", Input).value,
                "testAllEnabled": self.query_one("#settings-test-all-enabled", Checkbox).value,
                "testSomeCommand": self.query_one("#settings-test-some-command", Input).value,
                "testSomeEnabled": self.query_one("#settings-test-some-enabled", Checkbox).value,
                "afterTaskListCommand": self.query_one(
                    "#settings-after-tasklist-command", Input
                ).value,
                "exclusionPatterns": self._exclusion_patterns,
                "modules": self._modules,
            }

            # Collect project settings
            scope_all = self.query_one("#settings-scope-all", RadioButton).value
            test_scope = "ALL" if scope_all else "WORKSPACE"

            run_timeout_str = str(self.query_one("#settings-run-timeout", Select).value)
            test_timeout_str = str(self.query_one("#settings-test-timeout", Select).value)

            project_data: Dict[str, Any] = {
                "codeAgentTestScope": test_scope,
                "autoUpdateLocalDependencies": self.query_one(
                    "#settings-auto-update-local", Checkbox
                ).value,
                "autoUpdateGitDependencies": self.query_one(
                    "#settings-auto-update-git", Checkbox
                ).value,
                "commitMessageFormat": self.query_one("#settings-commit-format", TextArea).text,
            }

            # Parse timeouts
            if run_timeout_str:
                try:
                    project_data["runCommandTimeoutSeconds"] = int(run_timeout_str)
                except ValueError:
                    error_label.update("[bold red]Run timeout must be a number[/]")
                    return

            if test_timeout_str:
                try:
                    project_data["testCommandTimeoutSeconds"] = int(test_timeout_str)
                except ValueError:
                    error_label.update("[bold red]Test timeout must be a number[/]")
                    return

            # Build shell configuration
            shell_executable = self.query_one("#settings-shell-executable", Input).value.strip()
            shell_args_str = self.query_one("#settings-shell-args", Input).value.strip()
            shell_args = shlex.split(shell_args_str) if shell_args_str else []

            # Build issue provider configuration
            issue_provider_data: Dict[str, Any] = {"type": self._issue_provider_type}
            if self._issue_provider_type == "GITHUB":
                if self._github_override:
                    issue_provider_data["config"] = {
                        "owner": self.query_one("#settings-github-owner", Input).value.strip(),
                        "repo": self.query_one("#settings-github-repo", Input).value.strip(),
                        "host": self.query_one("#settings-github-host", Input).value.strip(),
                    }
                else:
                    issue_provider_data["config"] = {}
            elif self._issue_provider_type == "JIRA":
                issue_provider_data["config"] = {
                    "baseUrl": self.query_one("#settings-jira-base-url", Input).value.strip(),
                    "apiToken": self.query_one("#settings-jira-api-token", Input).value.strip(),
                    "projectKey": self.query_one("#settings-jira-project-key", Input).value.strip(),
                }
            else:
                issue_provider_data["config"] = {}

            # Build data retention policy
            retention_improve = self.query_one("#settings-retention-improve", RadioButton).value
            retention_policy = "IMPROVE_BROKK" if retention_improve else "MINIMAL"

            # Build analyzer languages
            selected_languages = []
            for lang in self._analyzer_languages:
                internal = lang.get("internalName", "")
                try:
                    cb = self.query_one(f"#settings-lang-{internal}", Checkbox)
                    if cb.value:
                        selected_languages.append(internal)
                except Exception:
                    pass

            # Save all settings atomically
            payload: Dict[str, Any] = {
                "buildDetails": build_data,
                "projectSettings": project_data,
                "shellConfig": {"executable": shell_executable, "args": shell_args},
                "issueProvider": issue_provider_data,
                "dataRetentionPolicy": retention_policy,
            }
            if self._analyzer_languages:
                payload["analyzerLanguages"] = {"languages": selected_languages}

            await self.app.executor.update_all_settings(payload)

            # Show success message in chat
            chat = self.app._maybe_chat()
            if chat:
                chat.add_system_message("Settings saved successfully.", level="SUCCESS")

            self.dismiss(None)

        except Exception as e:
            error_label.update(f"[bold red]Failed to save settings: {e}[/]")
