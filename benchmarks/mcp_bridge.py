#!/usr/bin/env python3
"""
MCP bridge for the structured-refactoring-agent IntelliJ plugin.

Exposes the plugin's localhost HTTP tool API as an MCP server over stdio transport.
Any MCP-compatible agent — Claude Desktop, Gemini CLI, Codex CLI, or any future
model with MCP support — can call the same AST-safe refactoring tools that the
benchmark runners use, without any changes to the plugin.

Requirements:
    pip install mcp requests

Usage (start directly for debugging):
    python benchmarks/mcp_bridge.py [--port 6473]

Configure in Claude Desktop (~/.config/claude/claude_desktop_config.json on Linux/Mac,
%APPDATA%\\Claude\\claude_desktop_config.json on Windows):

    {
      "mcpServers": {
        "refactoring": {
          "command": "python",
          "args": ["C:/path/to/benchmarks/mcp_bridge.py", "--port", "6473"]
        }
      }
    }

Configure for Gemini CLI (~/.gemini/settings.json):

    {
      "mcpServers": {
        "refactoring": {
          "command": "python",
          "args": ["path/to/benchmarks/mcp_bridge.py"]
        }
      }
    }

The bridge fetches the tool schema live from the plugin each time an MCP client
requests the tool list, so it automatically reflects any new tools added to the
plugin without restarting the bridge.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys

import requests
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import TextContent, Tool

DEFAULT_PORT = 6473


# ── Plugin HTTP helpers ──────────────────────────────────────────────────────

def _plugin_url(port: int, path: str) -> str:
    return f"http://127.0.0.1:{port}{path}"


def fetch_schema(port: int) -> list[dict]:
    r = requests.get(_plugin_url(port, "/tools/schema"), timeout=5)
    r.raise_for_status()
    return r.json()


def call_plugin(port: int, tool_name: str, params: dict) -> dict:
    r = requests.post(
        _plugin_url(port, "/tools"),
        json={"tool": tool_name, "params": params},
        timeout=60,
    )
    r.raise_for_status()
    return r.json()


def fetch_status(port: int) -> dict:
    r = requests.get(_plugin_url(port, "/status"), timeout=5)
    r.raise_for_status()
    return r.json()


# ── MCP server ───────────────────────────────────────────────────────────────

def build_server(port: int) -> Server:
    server = Server("structured-refactoring-agent")

    @server.list_tools()
    async def handle_list_tools() -> list[Tool]:
        try:
            plugin_tools = fetch_schema(port)
        except Exception as e:
            # Return a single error-reporter tool so the LLM can surface the issue
            return [Tool(
                name="plugin_unavailable",
                description=f"IntelliJ plugin not reachable on port {port}: {e}. "
                            "Start IntelliJ with the plugin and a project open.",
                inputSchema={"type": "object", "properties": {}},
            )]

        tools = [
            Tool(
                name=t["name"],
                description=t.get("description", ""),
                inputSchema=t.get("input_schema", {"type": "object", "properties": {}}),
            )
            for t in plugin_tools
        ]
        # Extra status tool not in the plugin HTTP schema
        tools.append(Tool(
            name="get_project_status",
            description=(
                "Return the name of the IntelliJ project currently open in the IDE "
                "and the tool server port. Call this first to confirm the correct "
                "project is loaded before running any refactoring operations."
            ),
            inputSchema={"type": "object", "properties": {}},
        ))
        return tools

    @server.call_tool()
    async def handle_call_tool(name: str, arguments: dict) -> list[TextContent]:
        params = arguments or {}
        try:
            if name == "get_project_status":
                result = fetch_status(port)
            else:
                result = call_plugin(port, name, params)
        except requests.exceptions.ConnectionError:
            result = {
                "error": f"Could not reach IntelliJ plugin on port {port}. "
                         "Ensure IntelliJ is open with the structured-refactoring-agent plugin loaded."
            }
        except Exception as e:
            result = {"error": str(e)}

        return [TextContent(type="text", text=json.dumps(result, indent=2))]

    return server


# ── Entry point ──────────────────────────────────────────────────────────────

async def _run(port: int) -> None:
    # Startup probe — warn but do not abort; IntelliJ may not be up yet
    try:
        status = fetch_status(port)
        print(
            f"[mcp_bridge] Connected to IntelliJ project "
            f"'{status.get('project', '?')}' on port {port}",
            file=sys.stderr,
        )
    except Exception as e:
        print(
            f"[mcp_bridge] WARNING: plugin not reachable on port {port}: {e}\n"
            f"             Start IntelliJ with the plugin and a project open.",
            file=sys.stderr,
        )

    server = build_server(port)
    async with stdio_server() as (read_stream, write_stream):
        await server.run(
            read_stream,
            write_stream,
            server.create_initialization_options(),
        )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="MCP bridge — exposes the structured-refactoring-agent IntelliJ plugin as an MCP server"
    )
    parser.add_argument(
        "--port", type=int, default=DEFAULT_PORT,
        help=f"Plugin HTTP port (default {DEFAULT_PORT})",
    )
    args = parser.parse_args()
    asyncio.run(_run(args.port))


if __name__ == "__main__":
    main()
