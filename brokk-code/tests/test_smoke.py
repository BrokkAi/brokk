import asyncio
from pathlib import Path
from unittest.mock import AsyncMock

import httpx

from brokk_code.app import BrokkApp
from brokk_code.executor import ExecutorError, ExecutorManager


def test_executor_manager_init():
    """Verify ExecutorManager can be instantiated with default paths."""
    workspace = Path("/tmp/fake-workspace")
    executor = ExecutorManager(workspace_dir=workspace, executor_version="v1.0.0")
    assert executor.workspace_dir == workspace.resolve()
    assert executor.executor_version == "v1.0.0"
    assert executor.session_id is None
    assert executor._process is None


def test_app_importable():
    """Verify BrokkApp can be instantiated without starting the event loop."""
    # We don't call .run() or ._start_executor() to avoid side effects
    app = BrokkApp(workspace_dir=Path("."))
    assert app.current_model == "gpt-5.2"
    assert app.executor is not None


def test_app_theme_persistence(tmp_path, monkeypatch):
    """Verify BrokkApp loads and saves theme settings."""
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    # 1. Create initial settings with a legacy theme alias
    from brokk_code.settings import Settings
    Settings(theme="builtin:light").save()

    # 2. Instantiate app and verify it normalized and loaded the theme
    app = BrokkApp(workspace_dir=tmp_path)
    assert app.theme == "textual-light"

    # 3. Change theme via action and verify save
    # Cycle should move from light -> dark (assuming alphabetical: textual-dark, textual-light)
    app.action_cycle_theme()
    assert app.theme == "textual-dark"

    loaded = Settings.load()
    assert loaded.theme == "textual-dark"


def test_app_theme_cycling(tmp_path, monkeypatch):
    """Verify cycling through all available themes."""
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    app = BrokkApp(workspace_dir=tmp_path)

    themes = sorted(app.available_themes)
    # Ensure we start at a known point
    app._set_theme(themes[0])

    for i in range(len(themes)):
        expected_theme = themes[(i + 1) % len(themes)]
        app.action_cycle_theme()
        assert app.theme == expected_theme


def test_app_theme_commands(tmp_path, monkeypatch):
    """Verify /theme commands update settings and app state."""
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    app = BrokkApp(workspace_dir=tmp_path)

    # Test /theme <name>
    app._handle_command("/theme textual-light")
    assert app.theme == "textual-light"
    assert app.settings.theme == "textual-light"

    # Test /theme <alias>
    app._handle_command("/theme builtin:dark")
    assert app.theme == "textual-dark"
    assert app.settings.theme == "textual-dark"

    # Test /theme list (smoke test for no crash)
    app._handle_command("/theme list")

    # Test /theme (smoke test for no crash)
    app._handle_command("/theme")


def test_version():
    from brokk_code import __version__

    assert __version__ == "0.1.0"


def test_executor_manager_stop_closes_http_client():
    """Verify stop() calls aclose() on the HTTP client."""
    workspace = Path("/tmp/fake-workspace")
    executor = ExecutorManager(workspace_dir=workspace)

    # Create a mock HTTP client with an async aclose method
    mock_client = AsyncMock()
    executor._http_client = mock_client

    # Run the async stop method
    asyncio.run(executor.stop())

    # Verify aclose was called
    mock_client.aclose.assert_called_once()
    # Verify the client was cleaned up
    assert executor._http_client is None


def test_find_jar_uses_versioned_cached_path_when_present(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    executor = ExecutorManager(workspace_dir=tmp_path / "ws", executor_version="feature/test 1")
    cached = tmp_path / ".brokk" / "brokk-feature_test_1.jar"
    cached.parent.mkdir(parents=True, exist_ok=True)
    cached.write_bytes(b"cached")

    # Ensure we do not attempt to download if cached exists
    monkeypatch.setattr(
        executor, "_download_jar", lambda version=None: (_ for _ in ()).throw(AssertionError())
    )

    assert executor._find_jar() == cached


def test_download_jar_selects_exact_tag_and_writes_versioned_cache(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    releases_payload = [
        {
            "tag_name": "v2.0.0",
            "assets": [
                {
                    "name": "brokk-v2.0.0.jar",
                    "browser_download_url": "https://example.invalid/brokk-v2.0.0.jar",
                }
            ],
        }
    ]

    created_clients = []

    class FakeResponse:
        def __init__(self, status_code=200, json_data=None, content=b""):
            self.status_code = status_code
            self._json_data = json_data
            self.content = content

        def raise_for_status(self):
            if self.status_code >= 400:
                raise httpx.HTTPError(f"status={self.status_code}")

        def json(self):
            return self._json_data

    class FakeClient:
        def __init__(self):
            self.requested_urls = []

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

        def get(self, url, params=None):
            self.requested_urls.append((url, params))
            if url.endswith("/repos/BrokkAi/brokk-releases/releases"):
                # If page > 1 and we only have one page of data, return empty
                if params and params.get("page", 1) > 1:
                    return FakeResponse(json_data=[])
                return FakeResponse(json_data=releases_payload)
            if url == "https://example.invalid/brokk-v2.0.0.jar":
                return FakeResponse(content=b"jar-bytes")
            raise AssertionError(f"Unexpected url: {url}")

    def fake_httpx_client(*args, **kwargs):
        c = FakeClient()
        created_clients.append(c)
        return c

    monkeypatch.setattr(httpx, "Client", fake_httpx_client)

    executor = ExecutorManager(workspace_dir=tmp_path / "ws")
    jar_path = executor._download_jar("v2.0.0")

    assert jar_path == tmp_path / ".brokk" / "brokk-v2.0.0.jar"
    assert jar_path.read_bytes() == b"jar-bytes"
    # Check that the first request was for releases with pagination params
    url, params = created_clients[0].requested_urls[0]
    assert url.endswith("/repos/BrokkAi/brokk-releases/releases")
    assert params["per_page"] == 100


def test_download_jar_latest_skips_snapshot(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    releases_payload = [
        {
            "tag_name": "v3.0.0-SNAPSHOT",
            "assets": [
                {
                    "name": "brokk-v3.0.0-SNAPSHOT.jar",
                    "browser_download_url": "https://example.invalid/snapshot.jar",
                }
            ],
        },
        {
            "tag_name": "v2.9.0",
            "assets": [
                {
                    "name": "brokk-v2.9.0.jar",
                    "browser_download_url": "https://example.invalid/stable.jar",
                }
            ],
        },
    ]

    created_clients = []

    class FakeResponse:
        def __init__(self, status_code=200, json_data=None, content=b""):
            self.status_code = status_code
            self._json_data = json_data
            self.content = content

        def raise_for_status(self):
            if self.status_code >= 400:
                raise httpx.HTTPError(f"status={self.status_code}")

        def json(self):
            return self._json_data

    class FakeClient:
        def __init__(self):
            self.requested_urls = []

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

        def get(self, url, params=None):
            self.requested_urls.append((url, params))
            if url.endswith("/repos/BrokkAi/brokk-releases/releases"):
                if params and params.get("page", 1) > 1:
                    return FakeResponse(json_data=[])
                return FakeResponse(json_data=releases_payload)
            if url == "https://example.invalid/stable.jar":
                return FakeResponse(content=b"stable-jar")
            raise AssertionError(f"Unexpected url: {url}")

    def fake_httpx_client(*args, **kwargs):
        c = FakeClient()
        created_clients.append(c)
        return c

    monkeypatch.setattr(httpx, "Client", fake_httpx_client)

    executor = ExecutorManager(workspace_dir=tmp_path / "ws")
    jar_path = executor._download_jar(None)

    assert jar_path == tmp_path / ".brokk" / "brokk.jar"
    assert jar_path.read_bytes() == b"stable-jar"
    # Ensure we never fetched the snapshot asset
    fetched_urls = [u for u, p in created_clients[0].requested_urls]
    assert "https://example.invalid/snapshot.jar" not in fetched_urls
    assert "https://example.invalid/stable.jar" in fetched_urls


def test_download_jar_raises_error_when_tag_not_found(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    releases_payload = [{"tag_name": "v1.0.0", "assets": []}]

    class FakeResponse:
        def __init__(self, json_data):
            self._json_data = json_data
            self.status_code = 200

        def raise_for_status(self):
            pass

        def json(self):
            return self._json_data

    class FakeClient:
        def __enter__(self):
            return self

        def __exit__(self, *args):
            pass

        def get(self, url, params=None):
            return FakeResponse(json_data=releases_payload)

    monkeypatch.setattr(httpx, "Client", lambda **kw: FakeClient())

    executor = ExecutorManager(workspace_dir=tmp_path)
    import pytest

    with pytest.raises(ExecutorError, match="Executor release tag not found on GitHub"):
        executor._download_jar("non-existent-tag")


def test_find_jar_downloads_when_not_cached(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    # Mock _download_jar to return a fake path instead of doing network IO
    fake_jar = tmp_path / "downloaded.jar"

    def mock_download(version=None):
        fake_jar.write_bytes(b"downloaded-content")
        return fake_jar

    executor = ExecutorManager(workspace_dir=tmp_path, executor_version="v1.2.3")
    monkeypatch.setattr(executor, "_download_jar", mock_download)

    # find_jar should call our mock because the file doesn't exist yet
    result = executor._find_jar()

    assert result == fake_jar
    assert result.read_bytes() == b"downloaded-content"


def test_download_jar_tgz_fallback(tmp_path, monkeypatch):
    import io
    import tarfile

    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    version = "0.21.0.4"
    jar_content = b"fake-extracted-jar-bytes"

    # Create in-memory TGZ
    tgz_io = io.BytesIO()
    with tarfile.open(fileobj=tgz_io, mode="w:gz") as tar:
        jar_data = io.BytesIO(jar_content)
        tarinfo = tarfile.TarInfo(name=f"package/jdeploy-bundle/brokk-{version}.jar")
        tarinfo.size = len(jar_content)
        tar.addfile(tarinfo, jar_data)
    tgz_bytes = tgz_io.getvalue()

    releases_payload = [
        {
            "tag_name": version,
            "assets": [
                {
                    "name": f"brokk-{version}.tgz",
                    "browser_download_url": "https://example.invalid/brokk.tgz",
                }
            ],
        }
    ]

    class FakeResponse:
        def __init__(self, status_code=200, json_data=None, content=b""):
            self.status_code = status_code
            self._json_data = json_data
            self.content = content

        def raise_for_status(self):
            if self.status_code >= 400:
                raise httpx.HTTPError(f"status={self.status_code}")

        def json(self):
            return self._json_data

    class FakeClient:
        def __enter__(self):
            return self

        def __exit__(self, *args):
            pass

        def get(self, url, params=None):
            if url.endswith("/releases"):
                return FakeResponse(json_data=releases_payload)
            if url == "https://example.invalid/brokk.tgz":
                return FakeResponse(content=tgz_bytes)
            raise AssertionError(f"Unexpected url: {url}")

    monkeypatch.setattr(httpx, "Client", lambda **kw: FakeClient())

    executor = ExecutorManager(workspace_dir=tmp_path)
    jar_path = executor._download_jar(version)

    assert jar_path == tmp_path / ".brokk" / f"brokk-{version}.jar"
    assert jar_path.read_bytes() == jar_content


def test_download_jar_prefers_brokk_tgz_over_installer(tmp_path, monkeypatch):
    import io
    import tarfile

    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    version = "0.20.3.4"
    jar_content = b"correct-jar-content"
    url_a = "https://example.invalid/Brokk.Installer-mac-x64-0.20.3.4_267P.tgz"
    url_b = f"https://example.invalid/brokk-{version}.tgz"

    # TGZ A (Installer) - No JAR
    tgz_a_io = io.BytesIO()
    with tarfile.open(fileobj=tgz_a_io, mode="w:gz") as tar:
        data = b"not-a-jar"
        tarinfo = tarfile.TarInfo(name="installer-script.sh")
        tarinfo.size = len(data)
        tar.addfile(tarinfo, io.BytesIO(data))
    tgz_a_bytes = tgz_a_io.getvalue()

    # TGZ B (Bundle) - Has JAR
    tgz_b_io = io.BytesIO()
    with tarfile.open(fileobj=tgz_b_io, mode="w:gz") as tar:
        jar_data = io.BytesIO(jar_content)
        tarinfo = tarfile.TarInfo(name=f"package/jdeploy-bundle/brokk-{version}.jar")
        tarinfo.size = len(jar_content)
        tar.addfile(tarinfo, jar_data)
    tgz_b_bytes = tgz_b_io.getvalue()

    releases_payload = [
        {
            "tag_name": version,
            "assets": [
                {
                    "name": f"Brokk.Installer-mac-x64-{version}_267P.tgz",
                    "browser_download_url": url_a,
                },
                {"name": f"brokk-{version}.tgz", "browser_download_url": url_b},
            ],
        }
    ]

    class FakeResponse:
        def __init__(self, status_code=200, json_data=None, content=b""):
            self.status_code = status_code
            self._json_data = json_data
            self.content = content

        def raise_for_status(self):
            if self.status_code >= 400:
                raise httpx.HTTPError(f"status={self.status_code}")

        def json(self):
            return self._json_data

    class FakeClient:
        def __init__(self):
            self.requested_urls = []

        def __enter__(self):
            return self

        def __exit__(self, *args):
            pass

        def get(self, url, params=None):
            self.requested_urls.append((url, params))
            if url.endswith("/releases"):
                return FakeResponse(json_data=releases_payload)
            if url == url_a:
                return FakeResponse(content=tgz_a_bytes)
            if url == url_b:
                return FakeResponse(content=tgz_b_bytes)
            raise AssertionError(f"Unexpected url: {url}")

    fake_client = FakeClient()
    monkeypatch.setattr(httpx, "Client", lambda **kw: fake_client)

    executor = ExecutorManager(workspace_dir=tmp_path)
    jar_path = executor._download_jar(version)

    assert jar_path == tmp_path / ".brokk" / f"brokk-{version}.jar"
    assert jar_path.read_bytes() == jar_content

    # Assert URL A (Installer) was never fetched, but URL B (Bundle) was
    fetched_urls = [u for u, p in fake_client.requested_urls]
    assert url_a not in fetched_urls
    assert url_b in fetched_urls


def test_download_jar_latest_snapshot_mode_paging(tmp_path, monkeypatch):
    """Test that snapshot mode finds a release on the second page."""
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    # Page 1: only stable releases
    page1 = [{"tag_name": f"v2.{i}.0", "assets": []} for i in range(10, 0, -1)]
    # Page 2: contains the snapshot
    page2 = [
        {
            "tag_name": "master-snapshot",
            "assets": [
                {
                    "name": "brokk-snapshot.jar",
                    "browser_download_url": "https://example.invalid/snapshot.jar",
                }
            ],
        }
    ]

    class FakeResponse:
        def __init__(self, status_code=200, json_data=None, content=b""):
            self.status_code = status_code
            self._json_data = json_data
            self.content = content

        def raise_for_status(self):
            pass

        def json(self):
            return self._json_data

    class FakeClient:
        def __init__(self):
            self.requested_pages = []

        def __enter__(self):
            return self

        def __exit__(self, *args):
            pass

        def get(self, url, params=None):
            if url.endswith("/releases"):
                page = params.get("page", 1)
                self.requested_pages.append(page)
                if page == 1:
                    return FakeResponse(json_data=page1)
                if page == 2:
                    return FakeResponse(json_data=page2)
                return FakeResponse(json_data=[])
            if url == "https://example.invalid/snapshot.jar":
                return FakeResponse(content=b"snapshot-jar")
            raise AssertionError(f"Unexpected url: {url}")

    fake_client = FakeClient()
    monkeypatch.setattr(httpx, "Client", lambda **kw: fake_client)

    # Initialize with snapshot mode
    executor = ExecutorManager(workspace_dir=tmp_path / "ws", executor_snapshot=True)
    jar_path = executor._download_jar(None)

    assert jar_path == tmp_path / ".brokk" / "brokk-snapshot.jar"
    assert jar_path.read_bytes() == b"snapshot-jar"
    assert 1 in fake_client.requested_pages
    assert 2 in fake_client.requested_pages


def test_chat_panel_no_markup_crash():
    """Verify ChatPanel methods don't crash when text contains Rich markup characters."""
    from unittest.mock import MagicMock

    from textual.widgets import RichLog

    from brokk_code.widgets.chat_panel import ChatPanel

    # Create panel and manually mock the log since we aren't running in an app
    panel = ChatPanel()
    mock_log = MagicMock(spec=RichLog)
    # Patch query_one to return our mock log
    panel.query_one = lambda selector, cls=None: mock_log if selector == "#chat-log" else None

    # This should not raise MarkupError
    panel.add_system_message("closing tag '[/]' has nothing to close")
    panel.append_token(
        token="[/]",
        message_type="AI",
        is_new_message=False,
        is_reasoning=False,
        is_terminal=True,
    )
    panel.append_message("System", "another [/] test")

    # Verify log.write was called
    assert mock_log.write.called


def test_chat_panel_streaming_markdown():
    """Verify that streaming tokens are buffered and rendered as Markdown at the end."""
    from unittest.mock import MagicMock

    from rich.markdown import Markdown
    from textual.widgets import RichLog

    from brokk_code.widgets.chat_panel import ChatPanel

    panel = ChatPanel()
    mock_log = MagicMock(spec=RichLog)
    panel.query_one = lambda selector, cls=None: mock_log if selector == "#chat-log" else None

    # Simulate a stream of tokens
    panel.append_token("**bold**", "AI", is_new_message=True, is_reasoning=False, is_terminal=False)
    panel.append_token(
        " [link](url)", "AI", is_new_message=False, is_reasoning=False, is_terminal=False
    )
    panel.append_token(" tail", "AI", is_new_message=False, is_reasoning=False, is_terminal=True)

    # Check that Markdown was written to the log
    markdown_calls = [
        call for call in mock_log.write.call_args_list if isinstance(call.args[0], Markdown)
    ]
    assert len(markdown_calls) == 1
    md_instance = markdown_calls[0].args[0]
    assert "**bold** [link](url) tail" in md_instance.markup


def test_chat_panel_flushes_on_finish_without_terminal_token():
    """Verify buffered output is rendered when a job finishes without terminal metadata."""
    from unittest.mock import MagicMock

    from rich.markdown import Markdown
    from textual.widgets import LoadingIndicator, RichLog

    from brokk_code.widgets.chat_panel import ChatPanel

    panel = ChatPanel()
    mock_log = MagicMock(spec=RichLog)
    mock_spinner = MagicMock(spec=LoadingIndicator)

    def mock_query_one(selector, cls=None):
        if selector == "#chat-log":
            return mock_log
        if selector == "#chat-spinner":
            return mock_spinner
        return None

    panel.query_one = mock_query_one
    panel._monitor_inactivity = lambda: None

    panel.append_token("partial", "AI", is_new_message=False, is_reasoning=False, is_terminal=False)
    panel.append_token(" output", "AI", is_new_message=False, is_reasoning=False, is_terminal=False)

    # No terminal chunk arrived yet, so nothing should be rendered.
    markdown_calls = [
        call for call in mock_log.write.call_args_list if isinstance(call.args[0], Markdown)
    ]
    assert len(markdown_calls) == 0

    panel.set_response_finished()

    markdown_calls = [
        call for call in mock_log.write.call_args_list if isinstance(call.args[0], Markdown)
    ]
    assert len(markdown_calls) == 1
    md_instance = markdown_calls[0].args[0]
    assert "partial output" in md_instance.markup


def test_chat_panel_notification_safety():
    """Verify notification panel handles markup safely."""
    from unittest.mock import MagicMock

    from rich.text import Text
    from textual.widgets import RichLog

    from brokk_code.widgets.chat_panel import ChatPanel

    panel = ChatPanel()
    mock_notif_log = MagicMock(spec=RichLog)

    def mock_query_one(selector, cls=None):
        if selector == "#notification-panel":
            return mock_notif_log
        return None

    panel.query_one = mock_query_one

    # Should use Text object to prevent markup crash
    panel.add_notification("This is [bold]not[/bold] markup [/]", level="WARNING")

    assert mock_notif_log.write.called
    args = mock_notif_log.write.call_args[0][0]
    assert isinstance(args, Text)
    assert "[WARNING] This is [bold]not[/bold] markup [/]" in args.plain


def test_chat_panel_lifecycle_states():
    """Verify ChatPanel correctly tracks its response lifecycle state."""
    from brokk_code.widgets.chat_panel import ChatPanel

    panel = ChatPanel()
    assert not panel.response_pending
    assert not panel.response_active

    panel.set_response_pending()
    assert panel.response_pending
    assert not panel.response_active

    panel.set_response_active()
    assert not panel.response_pending
    assert panel.response_active

    panel.set_response_finished()
    assert not panel.response_pending
    assert not panel.response_active


def test_chat_panel_spinner_transitions():
    """Verify spinner visibility during various response lifecycle stages."""
    from unittest.mock import MagicMock

    from textual.widgets import LoadingIndicator, RichLog

    from brokk_code.widgets.chat_panel import ChatPanel

    panel = ChatPanel()
    mock_log = MagicMock(spec=RichLog)
    mock_spinner = MagicMock(spec=LoadingIndicator)

    def mock_query_one(selector, cls=None):
        if selector == "#chat-log":
            return mock_log
        if selector == "#chat-spinner":
            return mock_spinner
        return None

    panel.query_one = mock_query_one

    # 1. Simulate prompt submission -> spinner shown
    panel.set_response_pending()
    mock_spinner.remove_class.assert_called_with("hidden")

    # 2. Simulate first token arrival -> spinner hidden
    # Setup mock time for inactivity test
    current_time = 1000.0
    panel._get_now = lambda: current_time
    panel.append_token("Hello", "AI", is_new_message=True, is_reasoning=False, is_terminal=False)
    mock_spinner.add_class.assert_called_with("hidden")

    # 3. Simulate inactivity while stream active -> spinner shown again
    # Advance time beyond inactivity timeout (default 10s)
    current_time += 15.0
    panel._check_inactivity()
    # verify remove_class("hidden") was called (it was called during set_response_pending too,
    # but append_token added "hidden" back)
    assert mock_spinner.remove_class.call_args_list[-1][0][0] == "hidden"

    # 4. Simulate token arrival after inactivity -> spinner hidden again
    panel.append_token(" world", "AI", is_new_message=False, is_reasoning=False, is_terminal=False)
    assert mock_spinner.add_class.call_args_list[-1][0][0] == "hidden"

    # 5. Simulate terminal token -> spinner remains hidden (or explicitly hidden)
    panel.append_token("!", "AI", is_new_message=False, is_reasoning=False, is_terminal=True)
    assert mock_spinner.add_class.call_args_list[-1][0][0] == "hidden"

    # 6. Final finish state
    panel.set_response_finished()
    assert mock_spinner.add_class.call_args_list[-1][0][0] == "hidden"
