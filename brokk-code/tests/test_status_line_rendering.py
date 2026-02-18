from unittest.mock import MagicMock
from brokk_code.widgets.status_line import StatusLine

def test_status_line_rendering_compact():
    status = StatusLine()
    # Mock _metadata widget
    mock_metadata = MagicMock()
    status._metadata = mock_metadata
    
    status.update_status(
        mode="LUTZ",
        model="gpt-4",
        reasoning="high",
        workspace="/path/to/my-project",
        branch="main"
    )
    
    # Expected: {branch} - {mode} - {model} ({reasoning}) - {workspace}
    # _workspace_label("/path/to/my-project") should be "my-project"
    expected = "main - LUTZ - gpt-4 (high) - my-project"
    mock_metadata.update.assert_called_with(expected)

def test_status_line_fragment_rendering():
    status = StatusLine()
    mock_metadata = MagicMock()
    status._metadata = mock_metadata
    
    status.set_fragment_info("my-file.py", 1234)
    
    # Expected: {description} ({tokens} tokens)
    from brokk_code.token_format import format_token_count
    size_text = format_token_count(1234)
    expected = f"my-file.py ({size_text} tokens)"
    mock_metadata.update.assert_called_with(expected)

def test_workspace_label_logic():
    assert StatusLine._workspace_label("/usr/local/bin") == "bin"
    assert StatusLine._workspace_label("C:\\Users\\Brokk\\project") == "project"
    assert StatusLine._workspace_label("root") == "root"
    assert StatusLine._workspace_label("") == "unknown"
    assert StatusLine._workspace_label("  ") == "unknown"
