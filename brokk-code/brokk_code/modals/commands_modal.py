"""Modal screen for viewing command history with expandable output."""

from datetime import datetime
from typing import Any, Dict, List, Optional

from textual import events
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.css.query import NoMatches
from textual.screen import ModalScreen
from textual.widgets import ListItem, ListView, Static


class CommandListItem(ListItem):
    """A ListItem that carries associated command data."""

    def __init__(self, *args, cmd_data: Dict[str, Any], **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self.cmd_data = cmd_data


class CommandsModalScreen(ModalScreen[None]):
    """Modal showing command history with expandable output."""

    BINDINGS = [
        Binding("q", "dismiss", "Close", show=False),
        Binding("enter", "toggle_output", "Toggle Output", show=False),
    ]

    def __init__(self, command_history: List[Dict[str, Any]]) -> None:
        super().__init__()
        self.command_history = command_history
        self._expanded_id: Optional[str] = None

    def compose(self):
        total = len(self.command_history)
        succeeded = sum(1 for cmd in self.command_history if cmd.get("success", False))
        failed = total - succeeded

        with Vertical(id="commands-modal-container"):
            yield Static("Command History", id="commands-modal-title")

            if not self.command_history:
                with VerticalScroll(id="commands-list-wrap"):
                    yield Static("No commands have been executed yet.", id="commands-empty")
            else:
                with Horizontal(id="commands-header"):
                    yield Static("Time", classes="cmd-col-time")
                    yield Static("Stage", classes="cmd-col-stage")
                    yield Static("Command", classes="cmd-col-command")
                    yield Static("Status", classes="cmd-col-status")

                with VerticalScroll(id="commands-list-wrap"):
                    list_items = []
                    for cmd in self.command_history:
                        ts = cmd.get("timestamp", 0)
                        dt = datetime.fromtimestamp(ts).strftime("%H:%M:%S")
                        stage = cmd.get("stage", "Command")
                        command = cmd.get("command", "")
                        success = cmd.get("success", False)
                        cmd_id = cmd.get("id", "")

                        # Truncate command for display
                        if len(command) > 40:
                            command_display = command[:37] + "..."
                        else:
                            command_display = command

                        status = "[bold green]OK[/]" if success else "[bold red]FAIL[/]"

                        li = CommandListItem(
                            Horizontal(
                                Static(dt, classes="cmd-col-time"),
                                Static(stage, classes="cmd-col-stage"),
                                Static(command_display, classes="cmd-col-command", markup=False),
                                Static(status, classes="cmd-col-status"),
                                classes="command-row",
                            ),
                            cmd_data=cmd,
                            id=f"cmd-{cmd_id}",
                        )
                        list_items.append(li)

                    yield ListView(*list_items, id="commands-list")

                # Output panel for expanded view
                yield Static("", id="commands-output-panel", classes="hidden")

            # Summary
            with Vertical(id="commands-summary"):
                summary_text = f"[bold]{total} command{'s' if total != 1 else ''}[/]"
                if total > 0:
                    summary_text += f" ({succeeded} succeeded, {failed} failed)"
                yield Static(summary_text, id="commands-summary-text")

            yield Static("Enter: Toggle Output  Esc: Close", id="commands-help-line")

    def on_mount(self) -> None:
        if self.query(ListView):
            self.query_one(ListView).focus()
        else:
            self.query_one("#commands-help-line").focus()

    def on_list_view_highlighted(self, message: ListView.Highlighted) -> None:
        """Ensure the highlighted row is scrolled into view and update output if expanded."""
        if message.item:
            try:
                scroll_wrap = self.query_one("#commands-list-wrap")
                scroll_wrap.scroll_to_widget(message.item, animate=False)
            except NoMatches:
                pass
            except Exception:
                pass

            # If we have an expanded view, update it with new selection
            if self._expanded_id is not None:
                self._show_output_for_item(message.item)

    def on_list_view_selected(self, message: ListView.Selected) -> None:
        """Toggle output panel when item is selected."""
        self.action_toggle_output()

    def action_toggle_output(self) -> None:
        """Toggle the output panel for the currently highlighted command."""
        try:
            list_view = self.query_one("#commands-list", ListView)
            output_panel = self.query_one("#commands-output-panel", Static)
        except NoMatches:
            return

        if list_view.highlighted_child is None:
            return

        item = list_view.highlighted_child
        if not isinstance(item, CommandListItem):
            return

        cmd_id = item.cmd_data.get("id", "")

        # Toggle expansion
        if self._expanded_id == cmd_id:
            # Collapse
            self._expanded_id = None
            output_panel.add_class("hidden")
            output_panel.update("")
        else:
            # Expand
            self._expanded_id = cmd_id
            self._show_output_for_item(item)

    def _show_output_for_item(self, item: CommandListItem) -> None:
        """Show the output panel for the given list item."""
        try:
            output_panel = self.query_one("#commands-output-panel", Static)
        except NoMatches:
            return

        if not isinstance(item, CommandListItem):
            return

        self._expanded_id = item.cmd_data.get("id", "")
        output = item.cmd_data.get("output", "").strip()
        exception = item.cmd_data.get("exception")
        command = item.cmd_data.get("command", "")

        parts = [f"[bold]Command:[/] {command}"]
        if output:
            parts.append(f"\n[bold]Output:[/]\n{output}")
        if exception:
            parts.append(f"\n[bold red]Error:[/] {exception}")
        if not output and not exception:
            parts.append("\n[dim]No output[/]")

        output_panel.update("\n".join(parts))
        output_panel.remove_class("hidden")

    def on_key(self, event: events.Key) -> None:
        """Handle key events."""
        if event.key == "escape":
            if self._expanded_id is not None:
                try:
                    output_panel = self.query_one("#commands-output-panel", Static)
                    output_panel.add_class("hidden")
                    output_panel.update("")
                    self._expanded_id = None
                except NoMatches:
                    pass
            else:
                self.dismiss(None)
            event.stop()
