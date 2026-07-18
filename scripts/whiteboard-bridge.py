#!/usr/bin/env python3
"""Bridge: EducatorWeb whiteboard JSON -> whiteboard D/E pipeline -> video/preview.mp4.

Converts a prepared work directory (boards.json + script.json + images/<board-id>.png,
written by VideoGenerator.java) into the whiteboard tool's project-mode input format,
then runs the pipeline:

  1. auto-calibration (best effort; results kept only when trustworthy)
  2. D stage: generate_board_package.py --project ... --output <work>/board_package
  3. E stage: render_multi_board_project.mjs --project-dir <work> --board-root ...

Success contract: <work-dir>/video/preview.mp4 exists non-empty, exit 0.
Any failure: a clear "FATAL: ..." line and a non-zero exit code.

Usage:
  python whiteboard-bridge.py --work-dir <dir> --whiteboard-root <dir>

--whiteboard-root may be an installed layout (<root>/runtime/<module>/...) or a
source checkout (<root>/<module>/...). Modules used:
  hand-drawn-infographic-video-board/scripts/{auto_calibrate.py,generate_board_package.py}
  whiteboard-infographic-video-renderer/scripts/render_multi_board_project.mjs

Directory layout created under work-dir:
  project/                          converted whiteboard project (D input)
    board_asset_manifest.json
    infographic/infographic_plan.json
    infographic/board_specs/<id>.board_spec.json
    script/voiceover_segments.json
    images/<id>.model-generated.png (name required by auto_calibrate.py)
    calibration/                    written by auto_calibrate, pruned by us
  board_package/                    D output (board_index.json, combined_motion_plan.json)
  audio/ sync/ video/ board/        E output; video/preview.mp4 is the final artifact
  (E's --project-dir is work-dir itself; note E deletes/recreates <project-dir>/board,
   which is why D output lives at board_package/, not board/.)

stdout stays terse on purpose: the Java caller (WhiteboardPipelineRunner) buffers the
whole stream in a pipe and only reads it after process exit, so each subprocess's
output is truncated to its last 2000 characters.

Environment knobs (all optional):
  WHITEBOARD_RENDER_QUALITY  hyperframes render quality (default "standard")
  WHITEBOARD_FULL_QA=1       keep E's hyperframes lint/validate/inspect + keyframe QA
  WHITEBOARD_CALIBRATION_PROVIDER  consumed by auto_calibrate.py itself when
                             --calibration-provider is left at "auto"
"""

import argparse
import json
import os
import re
import shutil
import struct
import subprocess
import sys
from pathlib import Path

BOARD_MODULE = "hand-drawn-infographic-video-board"
RENDER_MODULE = "whiteboard-infographic-video-renderer"

# Mirrors the whiteboard tool's supported annotation vocabulary (generate_board_package.py).
SUPPORTED_ACTIONS = ("underline", "circle", "box", "check", "strike")
ACTION_ALIASES = {"highlight": "underline", "point": "underline", "zoom": "box", "frame": "box", "rect": "box"}

TAIL_CHARS = 2000
CALIBRATE_TIMEOUT = 240
D_TIMEOUT = 180
E_TIMEOUT = 600  # Java side hard-kills the whole tree at 10 min; this is a local backstop

PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
ITEM_REF_RE = re.compile(r"^(?P<base>.+?)-item-(?P<idx>\d+)$")


def say(msg):
    print(msg, flush=True)


def fail(msg):
    print(f"FATAL: {msg}", file=sys.stderr, flush=True)
    sys.exit(1)


def tail(text):
    if not text:
        return ""
    if isinstance(text, bytes):
        text = text.decode("utf-8", errors="replace")
    text = text.strip()
    return text[-TAIL_CHARS:]


def run_step(cmd, cwd, label, timeout, check=True):
    """Run one pipeline subprocess. Prints only the tail of its output."""
    say(f"=== {label} ===")
    cmd = [str(part) for part in cmd]
    try:
        result = subprocess.run(
            cmd,
            cwd=str(cwd),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
    except FileNotFoundError:
        if check:
            fail(f"{label}: command not found: {cmd[0]}")
        say(f"WARN {label}: command not found: {cmd[0]}")
        return None
    except subprocess.TimeoutExpired as e:
        out = tail(e.stdout)
        if out:
            say(out)
        if check:
            fail(f"{label} timed out after {timeout}s")
        say(f"WARN {label}: timed out after {timeout}s")
        return None
    if result.stdout:
        say(tail(result.stdout))
    if result.stderr:
        print(tail(result.stderr), file=sys.stderr, flush=True)
    if check and result.returncode != 0:
        fail(f"{label} failed with exit code {result.returncode}")
    return result


def read_json(path, label):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        fail(f"{label} not found: {path}")
    except (OSError, ValueError) as e:
        fail(f"cannot read {label} ({path}): {e}")


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")


# --- id sanitation: byte-for-byte mirror of generate_board_package.py slug/ensure_id ---

def slug(value):
    text = str(value or "").strip().lower()
    text = re.sub("[^a-z0-9_\\-\\u4e00-\\u9fff]+", "-", text)
    text = re.sub("-+", "-", text).strip("-")
    return text or "item"


def ensure_id(value, fallback):
    raw = value or fallback
    ident = slug(raw)
    if re.search("[\\u4e00-\\u9fff]", ident):
        return slug(fallback)
    return ident


def dedupe_id(ident, used):
    candidate = ident
    index = 2
    while candidate in used:
        candidate = f"{ident}-{index}"
        index += 1
    used.add(candidate)
    return candidate


def as_float(value, default=0.0):
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def r3(value):
    return round(as_float(value), 3)


def clean_actions(raw_list, default=("underline",)):
    out = []
    for value in raw_list or []:
        action = ACTION_ALIASES.get(str(value or "").strip().lower(), str(value or "").strip().lower())
        if action in SUPPORTED_ACTIONS and action not in out:
            out.append(action)
    return out or list(default)


def clean_action_type(value):
    action = ACTION_ALIASES.get(str(value or "").strip().lower(), str(value or "").strip().lower())
    return action if action in SUPPORTED_ACTIONS else "underline"


def png_size(path):
    try:
        with open(path, "rb") as f:
            header = f.read(24)
    except OSError:
        return None
    if len(header) < 24 or header[:8] != PNG_MAGIC or header[12:16] != b"IHDR":
        return None
    return struct.unpack(">II", header[16:24])


def prepare_board_image(src, dest, board_id):
    """Copy a board image into the project as PNG; convert via Pillow if needed."""
    if not src.is_file():
        fail(f"board image missing for '{board_id}': {src}")
    size = png_size(src)
    if size:
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dest)
        return size
    # The image provider may return JPEG/WebP; D hard-requires PNG.
    try:
        from PIL import Image  # optional dependency
    except ImportError:
        fail(f"board image for '{board_id}' is not a PNG and Pillow is not installed to convert it: {src}")
    try:
        dest.parent.mkdir(parents=True, exist_ok=True)
        with Image.open(src) as img:
            img.convert("RGB").save(dest, "PNG")
    except Exception as e:  # Pillow raises many types
        fail(f"board image for '{board_id}' could not be converted to PNG: {e}")
    say(f"note: converted non-PNG image for '{board_id}' to PNG")
    return png_size(dest)


# --- conversion: boards.json + script.json -> whiteboard project ---

def convert_board(board, index, plan_topic, used_board_ids):
    """Build one board_spec dict plus its target-remapping metadata."""
    orig_id = str(board.get("id") or "")
    board_id = dedupe_id(ensure_id(orig_id, f"board-{index + 1}"), used_board_ids)

    raw_sections = [s for s in (board.get("sections") or []) if isinstance(s, dict)]
    if not raw_sections:
        # A title-only board is still renderable; D requires sections/elements/keyObjects.
        raw_sections = [{"id": "section-1", "title": board.get("title") or plan_topic or board_id, "items": []}]

    spec_sections = []
    elements = []
    # section_map: original section id -> (sanitized id, item count); used for target remaps
    section_map = {}
    used_section_ids = set()
    for i, section in enumerate(raw_sections):
        sec_orig = str(section.get("id") or "")
        sec_id = dedupe_id(ensure_id(sec_orig, f"section-{i + 1}"), used_section_ids)
        items = [str(item) for item in (section.get("items") or []) if str(item or "").strip()]
        # boards.json calls them "annotations"; board_spec calls them "actions"
        actions = clean_actions(section.get("annotations") or section.get("actions"))
        title = str(section.get("title") or sec_id)
        spec_sections.append({"id": sec_id, "title": title, "items": items, "actions": actions})
        if sec_orig:
            section_map[sec_orig] = (sec_id, len(items))
        section_map[sec_id] = (sec_id, len(items))
        # bbox-less elements: ignored by D's deterministic layout, but they make every
        # section/item a calibration candidate for auto_calibrate.py
        elements.append({"id": sec_id, "kind": "section", "text": title, "actions": actions})
        for n, item in enumerate(items):
            elements.append({"id": f"{sec_id}-item-{n + 1}", "kind": "item", "text": item, "actions": actions})

    spec = {
        "id": board_id,
        "title": str(board.get("title") or plan_topic or board_id),
        "canvas": {"width": 1920, "height": 1080},
        "sections": spec_sections,
        "elements": elements,
    }
    if board.get("subtitle"):
        spec["subtitle"] = str(board["subtitle"])
    return {
        "orig_id": orig_id or board_id,
        "board_id": board_id,
        "title": spec["title"],
        "spec": spec,
        "section_map": section_map,
    }


def remap_target(raw, board_info):
    """Map a script.json target/element onto the ids D will actually generate.

    VideoGenerator's narration prompt documents item references as 0-indexed
    ("sectionId-item-N"), while D generates 1-indexed "<section>-item-<n>" ids,
    so item indexes are shifted by +1 and clamped into the section's item range.
    Unknown ids pass through untouched: D runs project mode with allow_remap=True
    and fuzzy-matches them by spokenAnchor/caption text.
    """
    target = str(raw or "").strip()
    if not target:
        return None
    if target == "title":
        return "title"
    section_map = board_info["section_map"]
    if target in section_map:
        return section_map[target][0]
    match = ITEM_REF_RE.match(target)
    if match:
        base = match.group("base")
        if base in section_map:
            sec_id, count = section_map[base]
            if count <= 0:
                return sec_id
            one_based = min(max(int(match.group("idx")) + 1, 1), count)
            return f"{sec_id}-item-{one_based}"
    return target


def convert_segments(script_doc, boards_by_id, default_board_id):
    segments = []
    used_ids = set()
    dropped = 0
    reassigned = 0
    cursor = 0.0
    for i, seg in enumerate(script_doc.get("segments") or []):
        if not isinstance(seg, dict):
            continue
        caption = str(seg.get("caption") or "").strip()
        if not caption:
            dropped += 1
            continue
        seg_id = dedupe_id(str(seg.get("id") or "").strip() or f"seg-{i + 1}", used_ids)

        board_id = str(seg.get("boardId") or "").strip()
        if board_id not in boards_by_id:
            board_id = default_board_id
            reassigned += 1
        board_info = boards_by_id[board_id]

        start = as_float(seg.get("start"), cursor)
        speech_end = as_float(seg.get("speechEnd"), 0.0)
        if speech_end <= start:
            # degenerate LLM timing: estimate ~4 Chinese chars/second
            speech_end = start + max(1.0, len(caption) * 0.25)
        end = as_float(seg.get("end"), 0.0)
        if end < speech_end:
            end = speech_end + 0.2
        cursor = end

        target = remap_target(seg.get("target"), board_info) or "title"

        actions = []
        raw_actions = [a for a in (seg.get("actions") or []) if isinstance(a, dict)]
        for j, act in enumerate(raw_actions):
            entry = {
                "type": clean_action_type(act.get("type")),
                "element": remap_target(act.get("element"), board_info) or target,
            }
            anchor = str(act.get("spokenAnchor") or "").strip()
            if anchor:
                entry["spokenAnchor"] = anchor
            duration = as_float(act.get("duration"), 0.0)
            if duration > 0:
                entry["duration"] = r3(duration)
            if not anchor or anchor not in caption:
                # No usable in-caption anchor: give D/E a mid-speech draw point
                # instead of the degenerate offset-0 they would derive otherwise.
                entry["anchorRatio"] = round(min(0.85, 0.4 + 0.25 * j), 2)
            actions.append(entry)

        segments.append({
            "id": seg_id,
            "start": r3(start),
            "speechEnd": r3(speech_end),
            "end": r3(end),
            "caption": caption,
            "text": caption,  # E's TTS reads text || caption
            "boardId": board_id,
            "target": target,
            "targetElement": target,  # auto_calibrate.py reads targetElement, D reads either
            "actions": actions,
        })
    return segments, dropped, reassigned


def build_project(work_dir, project_dir):
    boards_doc = read_json(work_dir / "boards.json", "boards.json")
    script_doc = read_json(work_dir / "script.json", "script.json")
    if not isinstance(boards_doc, dict) or not isinstance(script_doc, dict):
        fail("boards.json and script.json must be JSON objects")
    plan_topic = str(boards_doc.get("topic") or script_doc.get("topic") or "")

    raw_boards = [b for b in (boards_doc.get("boards") or []) if isinstance(b, dict)]
    if not raw_boards:
        fail("boards.json contains no boards")
    images_dir = work_dir / "images"
    if not images_dir.is_dir():
        fail(f"images directory missing: {images_dir}")

    if project_dir.exists():
        shutil.rmtree(project_dir)

    boards = []
    used_board_ids = set()
    for index, raw_board in enumerate(raw_boards):
        boards.append(convert_board(raw_board, index, plan_topic, used_board_ids))
    boards_by_id = {b["board_id"]: b for b in boards}

    # Board images: copied to the name auto_calibrate.py discovers them under.
    manifest_boards = []
    for board in boards:
        src = images_dir / f"{board['orig_id']}.png"
        if not src.is_file():
            src = images_dir / f"{board['board_id']}.png"
        image_name = f"{board['board_id']}.model-generated.png"
        size = prepare_board_image(src, project_dir / "images" / image_name, board["board_id"])
        asset = {"kind": "file", "uri": f"images/{image_name}"}
        if size:
            asset["width"], asset["height"] = int(size[0]), int(size[1])
        manifest_boards.append({"boardId": board["board_id"], "title": board["title"], "asset": asset})

    segments, dropped, reassigned = convert_segments(script_doc, boards_by_id, boards[0]["board_id"])
    if not segments:
        fail("script.json produced no usable narration segments (all captions empty?)")

    for board in boards:
        write_json(
            project_dir / "infographic" / "board_specs" / f"{board['board_id']}.board_spec.json",
            board["spec"],
        )
    write_json(project_dir / "infographic" / "infographic_plan.json", {
        "version": "0.1",
        "topic": plan_topic,
        "boards": [
            {
                "id": b["board_id"],
                "title": b["title"],
                "boardSpecPath": f"infographic/board_specs/{b['board_id']}.board_spec.json",
                "sourceSegments": [s["id"] for s in segments if s["boardId"] == b["board_id"]],
            }
            for b in boards
        ],
    })
    write_json(project_dir / "board_asset_manifest.json", {
        "version": "0.1",
        "assetContract": {"allowedKinds": ["file"]},
        "boards": manifest_boards,
    })
    voiceover_path = project_dir / "script" / "voiceover_segments.json"
    write_json(voiceover_path, {"topic": plan_topic, "segments": segments})

    notes = []
    if dropped:
        notes.append(f"{dropped} empty-caption segment(s) dropped")
    if reassigned:
        notes.append(f"{reassigned} segment(s) had unknown boardId, reassigned to '{boards[0]['board_id']}'")
    say(f"converted: {len(boards)} board(s), {len(segments)} segment(s)"
        + (f" ({'; '.join(notes)})" if notes else ""))
    return voiceover_path


# --- calibration (best effort) ---

def prune_calibration(calibration_dir, run_result):
    """Keep only calibration bboxes that beat D's deterministic layout.

    - mock provider: fake grid boxes, strictly worse than D's section layout -> drop all
    - real provider (agent/vlm/ocr): keep boards whose report status is "complete";
      partial boards would shrink the element set and can break motion targets -> drop
    - crashed/timed-out run: drop everything (files may be half-written)
    """
    bbox_files = list(calibration_dir.glob("*.element_bboxes.json")) if calibration_dir.is_dir() else []
    if not bbox_files:
        say("calibration: no bbox files produced; D will use its deterministic layout")
        return

    def drop_all(reason):
        for f in bbox_files:
            f.unlink(missing_ok=True)
        say(f"calibration: discarded ({reason}); D will use its deterministic layout")

    if run_result is None or run_result.returncode not in (0, 3):
        drop_all("calibration run did not complete cleanly")
        return
    report_path = calibration_dir / "auto_calibration_report.json"
    try:
        with open(report_path, "r", encoding="utf-8") as f:
            report = json.load(f)
    except (OSError, ValueError):
        drop_all("no readable auto_calibration_report.json")
        return
    if report.get("provider") == "mock":
        drop_all("mock provider produces placeholder boxes")
        return
    kept = 0
    for entry in report.get("boards") or []:
        board_id = entry.get("boardId")
        bbox_file = calibration_dir / f"{board_id}.element_bboxes.json"
        if entry.get("status") == "complete" and bbox_file.is_file():
            kept += 1
        else:
            bbox_file.unlink(missing_ok=True)
    say(f"calibration: kept {kept} complete board(s) from provider '{report.get('provider')}'")


def run_calibration(project_dir, board_scripts, provider):
    calibrate_script = board_scripts / "auto_calibrate.py"
    if not calibrate_script.is_file():
        say("calibration: auto_calibrate.py not found; D will use its deterministic layout")
        return
    result = run_step(
        [sys.executable, calibrate_script, "--project-dir", project_dir, "--provider", provider],
        cwd=project_dir,
        label="calibrate",
        timeout=CALIBRATE_TIMEOUT,
        check=False,  # exit 3 = partial, 2 = error; both are survivable
    )
    prune_calibration(project_dir / "calibration", result)


# --- module resolution ---

def module_dir(root, name):
    """Installed layout keeps modules under <root>/runtime/, source checkout at <root>/."""
    for base in (root / "runtime", root):
        candidate = base / name
        if candidate.is_dir():
            return candidate
    return None


def main():
    try:  # Windows consoles may default to a non-UTF-8 codepage
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass

    parser = argparse.ArgumentParser(description="EducatorWeb -> whiteboard D/E pipeline bridge")
    parser.add_argument("--work-dir", required=True, type=Path,
                        help="directory with boards.json, script.json, images/")
    parser.add_argument("--whiteboard-root", required=True, type=Path,
                        help="whiteboard install root or source checkout")
    parser.add_argument("--calibration-provider", default="auto",
                        choices=("auto", "agent", "vlm", "ocr", "mock", "skip"),
                        help="auto_calibrate.py backend; 'skip' disables calibration")
    parser.add_argument("--quality", default=os.environ.get("WHITEBOARD_RENDER_QUALITY", "standard"),
                        help="hyperframes render quality passed to the E renderer")
    args = parser.parse_args()

    work_dir = args.work_dir.expanduser().resolve()
    whiteboard_root = args.whiteboard_root.expanduser().resolve()
    if not work_dir.is_dir():
        fail(f"work dir not found: {work_dir}")
    if not whiteboard_root.is_dir():
        fail(f"whiteboard root not found: {whiteboard_root}")

    board_module = module_dir(whiteboard_root, BOARD_MODULE)
    render_module = module_dir(whiteboard_root, RENDER_MODULE)
    if not board_module:
        fail(f"module '{BOARD_MODULE}' not found under {whiteboard_root} (tried runtime/ and root)")
    if not render_module:
        fail(f"module '{RENDER_MODULE}' not found under {whiteboard_root} (tried runtime/ and root)")
    d_script = board_module / "scripts" / "generate_board_package.py"
    e_script = render_module / "scripts" / "render_multi_board_project.mjs"
    if not d_script.is_file():
        fail(f"D script missing: {d_script}")
    if not e_script.is_file():
        fail(f"E script missing: {e_script}")

    # Step 1: convert our JSON into the whiteboard project layout
    say("=== convert EducatorWeb JSON -> whiteboard project ===")
    project_dir = work_dir / "project"
    voiceover_path = build_project(work_dir, project_dir)

    # Step 2: best-effort bbox calibration against the generated PNGs
    if args.calibration_provider != "skip":
        run_calibration(project_dir, board_module / "scripts", args.calibration_provider)

    # Step 3: D stage - board control packages + combined motion plan
    board_package = work_dir / "board_package"
    if board_package.exists():
        shutil.rmtree(board_package)
    run_step(
        [
            sys.executable, d_script,
            "--project", project_dir,
            "--asset-manifest", project_dir / "board_asset_manifest.json",
            "--voiceover", voiceover_path,
            "--output", board_package,
            # --calibration-dir defaults to <project>/calibration in project mode
        ],
        cwd=work_dir,
        label="D board package",
        timeout=D_TIMEOUT,
    )
    for required in ("board_index.json", "combined_motion_plan.json"):
        if not (board_package / required).is_file():
            fail(f"D stage completed but {required} is missing in {board_package}")

    # Step 4: E stage - TTS + HyperFrames render; writes <work>/video/preview.mp4.
    # D output sits in board_package/ because E wipes and rewrites <project-dir>/board.
    e_cmd = [
        "node", e_script,
        "--project-dir", work_dir,
        "--board-root", board_package,
        "--voiceover", voiceover_path,
        "--quality", args.quality,
    ]
    if os.environ.get("WHITEBOARD_FULL_QA") != "1":
        e_cmd += ["--skip-checks", "--skip-keyframes"]
    run_step(e_cmd, cwd=work_dir, label="E render", timeout=E_TIMEOUT)

    # Step 5: final success contract
    video = work_dir / "video" / "preview.mp4"
    if not video.is_file() or video.stat().st_size == 0:
        fail(f"pipeline finished but preview.mp4 is missing or empty: {video}")
    say(f"OK: {video} ({video.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
