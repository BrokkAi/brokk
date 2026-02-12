from pathlib import Path

import pytest

from brokk_code.executor import ExecutorManager


class _FakeResponse:
    def __init__(self, payload, content: bytes = b"") -> None:
        self._payload = payload
        self.content = content

    def raise_for_status(self) -> None:
        return

    def json(self):
        return self._payload


class _FakeClient:
    def __init__(self, *args, **kwargs) -> None:
        self.calls = []

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        return None

    def get(self, url: str, params=None):
        self.calls.append((url, params))
        if "api.github.com" in url:
            if params and params.get("page") == 1:
                releases = [
                    {
                        "tag_name": "v-test",
                        "assets": [
                            {
                                "name": "brokk-v-test.tgz",
                                "browser_download_url": "https://example.com/brokk-v-test.tgz",
                            }
                        ],
                    }
                ]
                return _FakeResponse(releases)
            return _FakeResponse([])
        return _FakeResponse({}, content=b"fake-tgz")


@pytest.mark.usefixtures("tmp_path")
def test_download_jar_tgz_path_does_not_require_jar_asset(monkeypatch, tmp_path):
    manager = ExecutorManager(workspace_dir=tmp_path, executor_version="v-test")
    manager._cached_jar_path = lambda version: tmp_path / "downloaded.jar"  # type: ignore[method-assign]
    manager._extract_jar_from_tgz = lambda content, version, asset: b"jar-bytes"  # type: ignore[method-assign]

    import brokk_code.executor as executor_module

    monkeypatch.setattr(executor_module.httpx, "Client", _FakeClient)

    out = manager._download_jar("v-test")
    assert out == Path(tmp_path / "downloaded.jar")
    assert out.read_bytes() == b"jar-bytes"
