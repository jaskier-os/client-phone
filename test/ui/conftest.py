"""
Phone UI test suite -- pytest configuration and shared fixtures.

Uses uiautomator2 for UI interaction, screenshots, and element selection.
Phone has touchscreen, so native u2 click/type/swipe work.
Device must be connected via USB (adb devices shows it).
"""
import os
import re
import time
import traceback
from datetime import datetime

import pytest
import uiautomator2 as u2

PHONE_SERIAL = "YOUR_PHONE_SERIAL"
PHONE_PACKAGE = "com.repository.listener"
SCREENSHOTS_DIR = os.path.join(os.path.dirname(__file__), "screenshots")
REPORTS_DIR = os.path.join(os.path.dirname(__file__), "reports")

# Resource ID prefix
RID = f"{PHONE_PACKAGE}:id"


# ---------------------------------------------------------------------------
# StepReporter -- per-step screenshot and text report generation
# ---------------------------------------------------------------------------

class StepReporter:
    """Records test steps with before/after screenshots and generates text reports."""

    def __init__(self, device, test_name, screenshots_dir, reports_dir):
        self.device = device
        self.test_name = test_name
        self.screenshots_dir = screenshots_dir
        self.reports_dir = reports_dir
        self.steps = []
        self.start_time = time.time()
        self._step_counter = 0
        os.makedirs(screenshots_dir, exist_ok=True)
        os.makedirs(reports_dir, exist_ok=True)

    def _screenshot_path(self, label):
        return os.path.join(
            self.screenshots_dir,
            f"{self.test_name}_{self._step_counter:02d}_{label}.png",
        )

    def _take_screenshot(self, label):
        path = self._screenshot_path(label)
        self.device.screenshot(path)
        return path

    def step(self, name, action=None):
        """Execute a named step with before/after screenshots.

        Args:
            name: human-readable step name (used in report and filenames)
            action: callable to execute between screenshots. If None, just takes a screenshot.
        """
        self._step_counter += 1
        step_start = time.time()
        step_record = {
            "index": self._step_counter,
            "name": name,
            "status": "PASSED",
            "before": None,
            "after": None,
            "error": None,
            "duration": 0,
        }

        step_record["before"] = self._take_screenshot(f"{name}_before")

        if action is not None:
            try:
                action()
            except Exception as e:
                step_record["status"] = "FAILED"
                step_record["error"] = traceback.format_exc()
                step_record["after"] = self._take_screenshot(f"{name}_error")
                step_record["duration"] = time.time() - step_start
                self.steps.append(step_record)
                raise

        step_record["after"] = self._take_screenshot(f"{name}_after")
        step_record["duration"] = time.time() - step_start
        self.steps.append(step_record)

    def screenshot(self, name):
        """Take a standalone screenshot without an action."""
        self._step_counter += 1
        path = self._take_screenshot(name)
        self.steps.append({
            "index": self._step_counter,
            "name": name,
            "status": "SCREENSHOT",
            "before": path,
            "after": None,
            "error": None,
            "duration": 0,
        })
        return path

    def finalize(self, failed=False):
        """Write text report to reports/{test_name}.txt."""
        duration = time.time() - self.start_time
        status = "FAILED" if failed else "PASSED"
        total = len(self.steps)

        lines = [
            f"TEST: {self.test_name}",
            f"DATE: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
            f"STATUS: {status}",
            f"DURATION: {duration:.1f}s",
            "",
            "STEPS:",
        ]

        for s in self.steps:
            idx = s["index"]
            name = s["name"]
            st = s["status"]
            dur = s["duration"]
            pad = "." * max(1, 40 - len(name))
            lines.append(f"  [{idx}/{total}] {name} {pad} {st} ({dur:.1f}s)")
            if s["before"]:
                label = "File" if st == "SCREENSHOT" else "Before"
                lines.append(f"         {label}: {os.path.basename(s['before'])}")
            if s["after"]:
                lines.append(f"         After:  {os.path.basename(s['after'])}")
            if s["error"]:
                for err_line in s["error"].strip().split("\n"):
                    lines.append(f"         ! {err_line}")

        report_path = os.path.join(self.reports_dir, f"{self.test_name}.txt")
        with open(report_path, "w") as f:
            f.write("\n".join(lines) + "\n")
        return report_path


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture(scope="session")
def device():
    """Connect to phone device via ADB. Session-scoped -- one connection for all tests."""
    d = u2.connect(PHONE_SERIAL)
    info = d.info
    assert info["displayWidth"] > 0 and info["displayHeight"] > 0, (
        f"Invalid display size: {info['displayWidth']}x{info['displayHeight']}"
    )
    yield d


@pytest.fixture(scope="session")
def ensure_app_foreground(device):
    """Ensure our app is in the foreground at session start."""
    current = device.app_current()
    if current["package"] != PHONE_PACKAGE:
        device.app_start(PHONE_PACKAGE)
        time.sleep(2)
    return device


def _ensure_in_app(device):
    """Check we're in our app. If not, relaunch it."""
    current = device.app_current()
    if current["package"] != PHONE_PACKAGE:
        device.app_start(PHONE_PACKAGE, ".MainActivity")
        time.sleep(2)


@pytest.fixture(autouse=True)
def reset_to_home(device, ensure_app_foreground):
    """Before each test, press BACK until we're at the main screen."""
    for _ in range(5):
        current = device.app_current()
        if current["package"] != PHONE_PACKAGE:
            _ensure_in_app(device)
            break
        device.press("back")
        time.sleep(0.3)
    _ensure_in_app(device)
    time.sleep(0.5)


@pytest.fixture
def step_reporter(device, request):
    """Per-test step reporter with before/after screenshots and text report."""
    test_name = request.node.name
    reporter = StepReporter(device, test_name, SCREENSHOTS_DIR, REPORTS_DIR)
    yield reporter
    failed = request.node.rep_call.failed if hasattr(request.node, "rep_call") else False
    reporter.finalize(failed=failed)


@pytest.hookimpl(tryfirst=True, hookwrapper=True)
def pytest_runtest_makereport(item, call):
    """Store test result on the item for the step_reporter fixture to read."""
    outcome = yield
    rep = outcome.get_result()
    setattr(item, f"rep_{rep.when}", rep)


# ---------------------------------------------------------------------------
# Navigation helpers
# ---------------------------------------------------------------------------

def navigate_to_todo_primary(device):
    """Navigate to Main Tab 0 (Todo) -> Sub-tab 0 (Primary).

    Tab order in main ViewPager: Todo(0), ChatsList(1), Reid(2), Glasses(3), ...
    Todo sub-tabs: Primary(0), Secondary(1)
    """
    # Tap the first tab in main tab layout
    tab_layout = device(resourceId=f"{RID}/tabLayout")
    if tab_layout.exists:
        # Click the first tab child
        first_tab = tab_layout.child(className="android.widget.LinearLayout", index=0)
        if first_tab.exists:
            first_tab.click()
            time.sleep(0.5)

    # Ensure Primary sub-tab is selected (index 0)
    todo_tab_layout = device(resourceId=f"{RID}/todoTabLayout")
    if todo_tab_layout.exists:
        primary_tab = todo_tab_layout.child(className="android.widget.LinearLayout", index=0)
        if primary_tab.exists:
            primary_tab.click()
            time.sleep(0.5)

    # Wait for RecyclerView to appear
    wait_for_element(device, resourceId=f"{RID}/todoRecyclerView", timeout=3)


def get_todo_count(device):
    """Count visible items in the todo RecyclerView."""
    rv = device(resourceId=f"{RID}/todoRecyclerView")
    if not rv.exists:
        return 0
    return rv.child_count


def get_todo_texts(device):
    """Get all visible todo item texts."""
    texts = []
    rv = device(resourceId=f"{RID}/todoRecyclerView")
    if not rv.exists:
        return texts
    for i in range(rv.child_count):
        text_view = rv.child(index=i).child(resourceId=f"{RID}/todoText")
        if text_view.exists:
            texts.append(text_view.get_text())
    return texts


def wait_for_element(device, timeout=5, **selector):
    """Poll until element exists or timeout."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if device(**selector).exists:
            return True
        time.sleep(0.3)
    return False


# ---------------------------------------------------------------------------
# Todo-specific helpers
# ---------------------------------------------------------------------------

def create_todo(device, text):
    """Create a todo item: tap FAB -> type text -> tap Add."""
    fab = device(resourceId=f"{RID}/addTodoFab")
    fab.click()
    time.sleep(0.5)

    # Dialog EditText has hint "What needs to be done?"
    edit = device(className="android.widget.EditText")
    edit.set_text(text)
    time.sleep(0.3)

    # Tap "Add" button
    device(text="Add").click()
    time.sleep(1)


def toggle_todo_by_text(device, text):
    """Find a todo item by its text and tap its checkbox."""
    text_view = device(resourceId=f"{RID}/todoText", text=text)
    if not text_view.exists:
        raise AssertionError(f"Todo item with text '{text}' not found")

    # Navigate to the parent item layout, then find the checkbox sibling
    parent = text_view.up(className="android.widget.LinearLayout")
    if parent and parent.exists:
        checkbox = parent.child(resourceId=f"{RID}/checkBox")
        if checkbox.exists:
            checkbox.click()
            return
    # Fallback: tapping the text also triggers toggle
    text_view.click()


def delete_todo_by_text(device, text):
    """Find a todo item by its text and tap its delete button."""
    text_view = device(resourceId=f"{RID}/todoText", text=text)
    if not text_view.exists:
        raise AssertionError(f"Todo item with text '{text}' not found")

    parent = text_view.up(className="android.widget.LinearLayout")
    if parent and parent.exists:
        delete_btn = parent.child(resourceId=f"{RID}/deleteButton")
        if delete_btn.exists:
            delete_btn.click()
            time.sleep(0.6)  # 500ms debounce + margin
            return
    raise AssertionError(f"Delete button not found for todo '{text}'")


def assert_todo_exists(device, text):
    """Assert a todo item with given text exists in the RecyclerView."""
    found = device(resourceId=f"{RID}/todoText", text=text).exists
    assert found, f"Expected todo '{text}' to exist but it was not found"


def assert_todo_not_exists(device, text):
    """Assert no todo item with given text exists in the RecyclerView."""
    found = device(resourceId=f"{RID}/todoText", text=text).exists
    assert not found, f"Expected todo '{text}' to NOT exist but it was found"


def is_todo_completed(device, text):
    """Check if a todo item is completed (checkbox checked)."""
    text_view = device(resourceId=f"{RID}/todoText", text=text)
    if not text_view.exists:
        raise AssertionError(f"Todo item with text '{text}' not found")

    parent = text_view.up(className="android.widget.LinearLayout")
    if parent and parent.exists:
        checkbox = parent.child(resourceId=f"{RID}/checkBox")
        if checkbox.exists:
            return checkbox.info.get("checked", False)
    raise AssertionError(f"Checkbox not found for todo '{text}'")


def assert_todo_completed(device, text, expected):
    """Assert a todo item's completion state matches expected."""
    actual = is_todo_completed(device, text)
    assert actual == expected, (
        f"Todo '{text}': expected completed={expected}, got {actual}"
    )


def take_screenshot(device, name):
    """Take a screenshot and save it with a descriptive name."""
    os.makedirs(SCREENSHOTS_DIR, exist_ok=True)
    path = os.path.join(SCREENSHOTS_DIR, f"{name}.png")
    device.screenshot(path)
    return path


def cleanup_test_todos(device, prefix="UI Test"):
    """Delete all todo items whose text starts with the given prefix."""
    for _ in range(20):  # Safety limit
        text_view = device(resourceId=f"{RID}/todoText", textStartsWith=prefix)
        if not text_view.exists:
            break
        parent = text_view.up(className="android.widget.LinearLayout")
        if parent and parent.exists:
            delete_btn = parent.child(resourceId=f"{RID}/deleteButton")
            if delete_btn.exists:
                delete_btn.click()
                time.sleep(0.6)
                continue
        break
