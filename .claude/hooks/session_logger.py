#!/usr/bin/env python3
"""
Session Logger for Claude Code

Logs conversations during structured sessions (brainstorm, discuss, etc.)
Activated/deactivated by slash commands.

Commands (via CLI argument):
    user_prompt           - Log user prompt (reads JSON from stdin)
    stop                  - Log assistant response (reads JSON from stdin)
    start <session_type>  - Start logging a new session
    end                   - Stop logging, generate metadata
    status                - Check current session state

Hook Input (via stdin JSON):
    UserPromptSubmit: { "prompt": "...", "transcript_path": "..." }
    Stop: { "transcript_path": "...", "stop_hook_active": true }
"""

import json
import os
import re
import sys
import time
from datetime import datetime
from pathlib import Path

# State file location
STATE_FILE = Path.home() / ".claude" / "session_state.json"


def get_project_root() -> Path:
    """Find project root by looking for .claude directory."""
    cwd = Path.cwd()
    for parent in [cwd] + list(cwd.parents):
        if (parent / ".claude").is_dir():
            return parent
    return cwd


def load_state() -> dict:
    """Load session state from file."""
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text())
        except (json.JSONDecodeError, IOError):
            pass
    return {}


def save_state(state: dict) -> None:
    """Save session state to file."""
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state, indent=2))


def clear_state() -> None:
    """Clear session state."""
    if STATE_FILE.exists():
        STATE_FILE.unlink()


def get_timestamp() -> str:
    """Get current timestamp in standard format."""
    return datetime.now().strftime("%Y-%m-%d_%H%M")


def get_discussions_dir() -> Path:
    """Get or create discussions directory."""
    project_root = get_project_root()
    discussions_dir = project_root / "discussions"
    discussions_dir.mkdir(parents=True, exist_ok=True)
    return discussions_dir


def extract_file_references(content: str) -> list[str]:
    """Extract file paths mentioned in content."""
    patterns = [
        r'`([^`]+\.[a-zA-Z]{1,10})`',  # `filename.ext`
        r"['\"]([^'\"]+\.[a-zA-Z]{1,10})['\"]",  # 'file' or "file"
        r'(?:^|\s)(\S+\.(?:java|md|yaml|yml|xml|json|py|sh))',  # bare paths
    ]
    files = set()
    for pattern in patterns:
        for match in re.findall(pattern, content):
            if not match.startswith("http"):
                files.add(match)
    return sorted(files)


def extract_search_queries(content: str) -> list[str]:
    """Extract web search queries from content."""
    patterns = [
        r'search(?:ed|ing)?\s+(?:for\s+)?["\']([^"\']+)["\']',
        r'google[d]?\s+["\']([^"\']+)["\']',
        r'look(?:ed|ing)?\s+up\s+["\']([^"\']+)["\']',
    ]
    queries = set()
    for pattern in patterns:
        for match in re.findall(pattern, content, re.IGNORECASE):
            queries.add(match)
    return sorted(queries)


def start_session(session_type: str) -> None:
    """Start a new logging session."""
    state = load_state()

    if state.get("active"):
        print(f"Session already active: {state.get('session_type')}", file=sys.stderr)
        sys.exit(1)

    timestamp = get_timestamp()
    session_id = f"{session_type}_{timestamp}"
    discussions_dir = get_discussions_dir()
    log_file = discussions_dir / f"{session_id}.md"

    # Initialize log file
    log_file.write_text(f"# {session_type.title()} Session\n\n")
    log_file.write_text(
        f"# {session_type.title()} Session\n\n"
        f"**Date:** {datetime.now().strftime('%Y-%m-%d %H:%M')}\n\n"
        f"---\n\n"
    )

    # Save state
    state = {
        "active": True,
        "session_type": session_type,
        "session_id": session_id,
        "log_file": str(log_file),
        "start_time": datetime.now().isoformat(),
        "message_count": 0,
        "project_root": str(get_project_root()),
    }
    save_state(state)

    print(f"Session started: {session_id}")
    print(f"Log file: {log_file}")


def stop_session() -> None:
    """Stop the current session and generate metadata."""
    state = load_state()

    if not state.get("active"):
        print("No active session", file=sys.stderr)
        sys.exit(1)

    log_file = Path(state["log_file"])

    # Calculate duration
    start_time = datetime.fromisoformat(state["start_time"])
    duration_minutes = int((datetime.now() - start_time).total_seconds() / 60)

    # Read log content for extraction
    content = log_file.read_text() if log_file.exists() else ""

    # Generate metadata file
    meta_file = log_file.with_suffix(".yaml")
    meta_content = f"""session: {state["session_id"]}
session_type: {state["session_type"]}
date: {start_time.strftime("%Y-%m-%d %H:%M")}
duration_minutes: {duration_minutes}
message_count: {state["message_count"]}

# Auto-extracted (verify and supplement)
files_referenced:
{chr(10).join(f"  - {f}" for f in extract_file_references(content)) or "  # none detected"}

web_searches:
{chr(10).join(f'  - "{q}"' for q in extract_search_queries(content)) or "  # none detected"}

# Fill in manually or ask AI to complete
topics:
  - # topic 1
  - # topic 2

key_decisions:
  - # decision 1

action_items:
  - # action 1

highlights:
  - # key insight 1
"""
    meta_file.write_text(meta_content)

    print(f"Session stopped: {state['session_id']}")
    print(f"Duration: {duration_minutes} minutes")
    print(f"Messages: {state['message_count']}")
    print(f"Log: {log_file}")
    print(f"Metadata: {meta_file}")

    # Clear state
    clear_state()


def status() -> None:
    """Print current session status."""
    state = load_state()

    if not state.get("active"):
        print("No active session")
        return

    start_time = datetime.fromisoformat(state["start_time"])
    duration = int((datetime.now() - start_time).total_seconds() / 60)

    print(f"Active session: {state['session_id']}")
    print(f"Type: {state['session_type']}")
    print(f"Duration: {duration} minutes")
    print(f"Messages: {state['message_count']}")
    print(f"Log: {state['log_file']}")


def read_hook_input() -> dict:
    """Read JSON input from stdin (hook data)."""
    try:
        return json.loads(sys.stdin.read())
    except (json.JSONDecodeError, IOError):
        return {}


def get_last_assistant_message(transcript_path: str) -> str:
    """Extract the last assistant message from the transcript file."""
    try:
        transcript = Path(transcript_path)
        if not transcript.exists():
            return ""

        lines = transcript.read_text().strip().split("\n")
        last_assistant_content = ""
        for line in lines:
            if not line:
                continue
            try:
                entry = json.loads(line)
                if entry.get("type") == "assistant" and "message" in entry:
                    msg = entry["message"]
                    if "content" in msg:
                        # Extract text content from content blocks
                        content_parts = []
                        for block in msg["content"]:
                            if isinstance(block, dict) and block.get("type") == "text":
                                content_parts.append(block.get("text", ""))
                            elif isinstance(block, str):
                                content_parts.append(block)
                        last_assistant_content = "\n".join(content_parts)
            except json.JSONDecodeError:
                continue
        return last_assistant_content
    except Exception:
        return ""


def log_user_prompt() -> None:
    """Log user prompt from UserPromptSubmit hook."""
    state = load_state()
    if not state.get("active"):
        return

    hook_input = read_hook_input()
    content = hook_input.get("prompt", "").strip()
    if not content:
        return

    log_file = Path(state["log_file"])
    formatted = f"\n**User:**\n\n{content}\n\n---\n"

    with open(log_file, "a") as f:
        f.write(formatted)

    state["message_count"] = state.get("message_count", 0) + 1
    save_state(state)


def log_assistant_response() -> None:
    """Log assistant response from Stop hook."""
    state = load_state()
    if not state.get("active"):
        return

    hook_input = read_hook_input()
    transcript_path = hook_input.get("transcript_path", "")

    # Retry with delay - transcript may not be fully written yet
    content = ""
    for _ in range(5):
        content = get_last_assistant_message(transcript_path)
        if content:
            break
        time.sleep(0.2)  # Wait 200ms between retries

    if not content:
        return

    log_file = Path(state["log_file"])
    formatted = f"\n**Assistant:**\n\n{content}\n\n---\n"

    with open(log_file, "a") as f:
        f.write(formatted)

    state["message_count"] = state.get("message_count", 0) + 1
    save_state(state)


def main():
    if len(sys.argv) < 2:
        print("Usage: session_logger.py <command> [args]", file=sys.stderr)
        print("Commands: user_prompt, stop, start, end, status", file=sys.stderr)
        sys.exit(1)

    command = sys.argv[1]

    if command == "start":
        session_type = sys.argv[2] if len(sys.argv) > 2 else "session"
        start_session(session_type)
    elif command == "user_prompt":
        log_user_prompt()
    elif command == "stop":
        log_assistant_response()
    elif command == "end":
        stop_session()
    elif command == "status":
        status()
    else:
        print(f"Unknown command: {command}", file=sys.stderr)
        print("Commands: user_prompt, stop, start, end, status", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
