from unittest.mock import MagicMock

import pytest
from textual.widgets import Footer

from brokk_code.app import BrokkApp


@pytest.mark.asyncio
async def test_no_footer_is_present_in_app():
    """
    Regression test to ensure that the Footer widget is not mounted in the application.
    We prefer the custom StatusLine and Help label over the standard Textual Footer.
    """
    # Create app with a mocked executor to avoid subprocess startup
    app = BrokkApp(executor=MagicMock())

    async with app.run_test():
        # Assert that no Footer widget is found in the DOM
        assert not list(app.query(Footer)), "A Footer widget was found but should not be present."
