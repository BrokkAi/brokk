from brokk_code.app import AddDependencyModalScreen

_from_path = AddDependencyModalScreen._derive_name_from_path
_from_url = AddDependencyModalScreen._derive_name_from_url


class TestDeriveNameFromPath:
    def test_unix_path(self):
        assert _from_path("/home/user/my-lib") == "my-lib"

    def test_unix_path_trailing_slash(self):
        assert _from_path("/home/user/my-lib/") == "my-lib"

    def test_windows_path(self):
        assert _from_path("C:\\work\\lib") == "lib"

    def test_windows_path_trailing_backslash(self):
        assert _from_path("C:\\work\\lib\\") == "lib"

    def test_bare_name(self):
        assert _from_path("my-lib") == "my-lib"

    def test_whitespace_stripped(self):
        assert _from_path("  /tmp/foo  ") == "foo"

    def test_empty_string(self):
        assert _from_path("") == ""


class TestDeriveNameFromUrl:
    def test_https_url(self):
        assert _from_url("https://github.com/owner/repo") == "repo"

    def test_https_url_with_git_suffix(self):
        url = "https://github.com/owner/repo.git"
        assert _from_url(url) == "repo"

    def test_trailing_slash(self):
        url = "https://github.com/owner/repo/"
        assert _from_url(url) == "repo"

    def test_trailing_slash_with_git_suffix(self):
        url = "https://github.com/owner/repo.git/"
        assert _from_url(url) == "repo"

    def test_ssh_url(self):
        url = "git@github.com:owner/repo.git"
        assert _from_url(url) == "repo"

    def test_whitespace_stripped(self):
        assert _from_url("  https://github.com/o/r  ") == "r"

    def test_bare_name(self):
        assert _from_url("repo") == "repo"
