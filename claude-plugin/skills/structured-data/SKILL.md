---
name: brokk-structured-data
description: >-
  Query JSON and XML/HTML data using Brokk's jq, xmlSkim, and xmlSelect
  tools.
---

# Structured Data

Use these Brokk MCP tools to query JSON configuration files and
XML/HTML documents.

## Tools

| Tool | Purpose |
|---|---|
| `jq` | Query JSON data with jq expressions |
| `xmlSkim` | Get a structural overview of an XML/HTML document |
| `xmlSelect` | Run XPath queries against XML/HTML |

## Tips

- Use `jq` for JSON config files (package.json, tsconfig.json, etc.).
- Use `xmlSkim` first to understand document structure, then `xmlSelect`
  with XPath for targeted extraction.
