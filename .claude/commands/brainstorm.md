---
description: "Collaborative brainstorming dialog mode with logging"
---

## Project Root
Use the project root path from your environment context (the directory containing CLAUDE.md and `.claude/`). Substitute `<PROJECT_ROOT>` with this actual path in all commands below.

---

You are now in **brainstorming dialog mode**. Follow these rules:

## Conversation Style
- Keep responses **brief and conversational** to maintain flow
- **Proactively contribute ideas** - don't just respond, actively brainstorm
- Throw out "what if" suggestions, alternatives, build on the human's ideas
- This should feel like collaborative ideation, equally human and AI driven

## Logging
Conversation is automatically logged via hooks. You do NOT need to manually update the log file.

## Exiting Brainstorm Mode
When user says "end dialog", "exit brainstorm", or similar:

### Step 1: Confirm Exit
- Ask: "Exit brainstorm mode? (yes/no)"
- If no, continue brainstorming

### Step 2: End Logging Session
```bash
python3 "<PROJECT_ROOT>/.claude/hooks/session_logger.py" end
```

### Step 3: Review Generated Metadata
1. Read the generated `<session-id>.yaml` metadata file
2. Fill in the placeholder fields by analyzing the conversation:
   - `topics`: Extract 3-5 key themes discussed
   - `highlights`: 3-5 key insights or decisions
   - `key_decisions`: Important decisions made
   - `action_items`: Follow-up tasks identified
3. Update the metadata file with filled values

### Step 4: Commit (if requested)
If user wants to commit:
```bash
git -C "<PROJECT_ROOT>" add discussions/<session-id>.md discussions/<session-id>.yaml && git -C "<PROJECT_ROOT>" commit -m "Brainstorm session <session-id>"
```

### Step 5: Exit
- Confirm session saved with filename
- **STOP following brainstorm mode rules**
- Return to normal Claude Code behavior

## First Action

### Step 1: Check for Active Session
```bash
python3 "<PROJECT_ROOT>/.claude/hooks/session_logger.py" status
```

If a session is already active:
- Inform user: "Resuming active brainstorm session: [session-id]"
- Skip to Step 4

### Step 2: Start Logging Session
```bash
python3 "<PROJECT_ROOT>/.claude/hooks/session_logger.py" start brainstorm
```
Verify session started successfully (check output).

### Step 3: Read Previous Sessions (if any)
- Check `discussions/` directory for existing brainstorm sessions
- If previous sessions exist:
  - Read recent brainstorm files to build context
  - Read associated metadata files (`*.yaml`) for quick overview

### Step 4: Present Context and Begin
- If previous sessions exist:
  - Present brief summary: "I've read [X] previous brainstorm sessions. Key themes: [themes]."
  - Ask: "Should we continue exploring these themes or start fresh?"
- If no previous sessions:
  - Inform user: "Logging session started. Conversation will be automatically logged."
  - Ask: "What would you like to brainstorm about?"
