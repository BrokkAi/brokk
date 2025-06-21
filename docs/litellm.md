Want to bring your own keys to Brokk? No problem! Just set up litellm locally and then choose the Local option
in Settings -> Global -> Service.

Here's how you run litellm locally:

1. docker pull ghcr.io/berriai/litellm:main-latest
2. docker run -v $(pwd)/docs/litellm_config.yaml:/app/config.yaml -e OPENAI_API_KEY=XXX -e ANTHROPIC_API_KEY=XXY -e DEEPSEEK_API_KEY=XYY -e GEMINI_API_KEY=YYY -e GROK_API_KEY=YYZ -p 4000:4000 ghcr.io/berriai/litellm:main-latest --config /app/config.yaml

Modify the above to point to secrets on disk with `cat ...`, or just hardcode them if you prefer.

Those are the keys you'll need to get the same models that brokk.ai provides, but Brokk will load the models from litellm so you can run with fewer providers than this or other providers or local models instead if that's your jam.
