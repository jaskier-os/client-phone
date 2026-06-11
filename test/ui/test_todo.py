"""
Tests for the TODO checklist on the phone app.

Tab structure: Main Tab 0 (TodoFragment) -> Sub-tab 0 (TodoPrimaryFragment)
TodoPrimaryFragment has:
  - RecyclerView (R.id.todoRecyclerView) with todo items
  - FAB (R.id.addTodoFab) to create new todos
  - Each item: CheckBox (R.id.checkBox), TextView (R.id.todoText),
               ImageButton (R.id.deleteButton)

IMPORTANT: These tests require:
- Phone connected via USB
- Orchestrator running (for todo persistence)
"""
import time

import pytest

from conftest import (
    PHONE_PACKAGE,
    RID,
    navigate_to_todo_primary,
    create_todo,
    toggle_todo_by_text,
    delete_todo_by_text,
    assert_todo_exists,
    assert_todo_not_exists,
    assert_todo_completed,
    is_todo_completed,
    get_todo_count,
    get_todo_texts,
    cleanup_test_todos,
    wait_for_element,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

TEST_TODO_PREFIX = "UI Test"


def make_todo_text(label):
    """Generate a unique test todo text."""
    return f"{TEST_TODO_PREFIX} {label} {int(time.time()) % 100000}"


# ---------------------------------------------------------------------------
# Lifecycle spec: create -> toggle -> toggle back -> delete
# ---------------------------------------------------------------------------

class TestTodoLifecycle:
    """Full lifecycle test: create a todo, toggle it twice, delete it."""

    def test_full_todo_lifecycle(self, device, step_reporter):
        """Create, toggle on, toggle off, delete -- the primary spec."""
        todo_text = make_todo_text("lifecycle")

        step_reporter.step("navigate_to_todo",
                           lambda: navigate_to_todo_primary(device))

        step_reporter.screenshot("initial_state")
        initial_count = get_todo_count(device)

        step_reporter.step("tap_add_fab",
                           lambda: device(resourceId=f"{RID}/addTodoFab").click())
        time.sleep(0.5)

        step_reporter.step("type_todo_text", lambda: (
            device(className="android.widget.EditText").set_text(todo_text)
        ))

        step_reporter.step("tap_add_button",
                           lambda: device(text="Add").click())
        time.sleep(2)

        step_reporter.step("verify_created",
                           lambda: assert_todo_exists(device, todo_text))

        new_count = get_todo_count(device)
        assert new_count == initial_count + 1, (
            f"Expected {initial_count + 1} items, got {new_count}"
        )

        # Toggle to completed
        step_reporter.step("toggle_complete",
                           lambda: toggle_todo_by_text(device, todo_text))
        time.sleep(1)

        step_reporter.step("verify_completed",
                           lambda: assert_todo_completed(device, todo_text, True))

        # Toggle back to uncompleted
        step_reporter.step("toggle_uncomplete",
                           lambda: toggle_todo_by_text(device, todo_text))
        time.sleep(1)

        step_reporter.step("verify_uncompleted",
                           lambda: assert_todo_completed(device, todo_text, False))

        # Delete
        step_reporter.step("delete_todo",
                           lambda: delete_todo_by_text(device, todo_text))
        time.sleep(1)

        step_reporter.step("verify_deleted",
                           lambda: assert_todo_not_exists(device, todo_text))

        final_count = get_todo_count(device)
        assert final_count == initial_count, (
            f"Expected {initial_count} items after delete, got {final_count}"
        )


# ---------------------------------------------------------------------------
# Create tests
# ---------------------------------------------------------------------------

class TestTodoCreate:
    """Focused tests for todo creation."""

    @pytest.fixture(autouse=True)
    def _cleanup(self, device):
        """Delete any test todos after each test."""
        yield
        navigate_to_todo_primary(device)
        cleanup_test_todos(device, TEST_TODO_PREFIX)

    def test_create_via_add_button(self, device, step_reporter):
        """Create a todo using FAB -> dialog -> Add button."""
        todo_text = make_todo_text("create")

        step_reporter.step("navigate",
                           lambda: navigate_to_todo_primary(device))

        step_reporter.step("tap_fab",
                           lambda: device(resourceId=f"{RID}/addTodoFab").click())
        time.sleep(0.5)

        step_reporter.step("enter_text", lambda: (
            device(className="android.widget.EditText").set_text(todo_text)
        ))

        step_reporter.step("tap_add",
                           lambda: device(text="Add").click())
        time.sleep(2)

        step_reporter.step("verify",
                           lambda: assert_todo_exists(device, todo_text))

    def test_create_cancel_dialog(self, device, step_reporter):
        """Open dialog, type text, cancel -- no item should be created."""
        todo_text = make_todo_text("cancel")

        step_reporter.step("navigate",
                           lambda: navigate_to_todo_primary(device))

        initial_count = get_todo_count(device)

        step_reporter.step("tap_fab",
                           lambda: device(resourceId=f"{RID}/addTodoFab").click())
        time.sleep(0.5)

        step_reporter.step("enter_text", lambda: (
            device(className="android.widget.EditText").set_text(todo_text)
        ))

        step_reporter.step("tap_cancel",
                           lambda: device(text="Cancel").click())
        time.sleep(0.5)

        step_reporter.step("verify_not_created",
                           lambda: assert_todo_not_exists(device, todo_text))

        final_count = get_todo_count(device)
        assert final_count == initial_count, (
            f"Item count changed after cancel: {initial_count} -> {final_count}"
        )

    def test_create_empty_rejected(self, device, step_reporter):
        """Open dialog, leave text empty, tap Add -- should not create item."""
        step_reporter.step("navigate",
                           lambda: navigate_to_todo_primary(device))

        initial_count = get_todo_count(device)

        step_reporter.step("tap_fab",
                           lambda: device(resourceId=f"{RID}/addTodoFab").click())
        time.sleep(0.5)

        # Don't type anything, just tap Add
        step_reporter.step("tap_add_empty",
                           lambda: device(text="Add").click())
        time.sleep(0.5)

        # Dialog should still be open or item count unchanged
        final_count = get_todo_count(device)
        step_reporter.screenshot("after_empty_add")

        # Dismiss dialog if still open
        if device(text="Cancel").exists:
            device(text="Cancel").click()
            time.sleep(0.3)

        assert final_count == initial_count, (
            f"Empty todo was created: {initial_count} -> {final_count}"
        )


# ---------------------------------------------------------------------------
# Reorder tests ("update" equivalent -- text editing not implemented)
# ---------------------------------------------------------------------------

class TestTodoReorder:
    """Test drag-to-reorder as the 'update' operation.

    Text editing is not implemented in the app. Reorder uses ItemTouchHelper
    with UP/DOWN drag on non-completed items.
    """

    @pytest.fixture(autouse=True)
    def _cleanup(self, device):
        yield
        navigate_to_todo_primary(device)
        cleanup_test_todos(device, TEST_TODO_PREFIX)

    def test_reorder_item_down(self, device, step_reporter):
        """Create 2 items, drag first below second, verify new order."""
        text_a = make_todo_text("reorder_A")
        text_b = make_todo_text("reorder_B")

        step_reporter.step("navigate",
                           lambda: navigate_to_todo_primary(device))

        # Create two items (B will be above A since newest is first)
        step_reporter.step("create_item_a", lambda: create_todo(device, text_a))
        time.sleep(1)
        step_reporter.step("create_item_b", lambda: create_todo(device, text_b))
        time.sleep(1)

        step_reporter.screenshot("before_reorder")
        texts_before = get_todo_texts(device)

        # Find item positions and drag the first one down
        item_a = device(resourceId=f"{RID}/todoText", text=text_a)
        item_b = device(resourceId=f"{RID}/todoText", text=text_b)

        if item_a.exists and item_b.exists:
            bounds_a = item_a.info["bounds"]
            bounds_b = item_b.info["bounds"]
            center_a_x = (bounds_a["left"] + bounds_a["right"]) // 2
            center_a_y = (bounds_a["top"] + bounds_a["bottom"]) // 2
            center_b_y = (bounds_b["top"] + bounds_b["bottom"]) // 2

            step_reporter.step("drag_a_below_b", lambda: (
                device.drag(center_a_x, center_a_y, center_a_x, center_b_y, duration=0.5)
            ))
            time.sleep(1)

        step_reporter.screenshot("after_reorder")
        texts_after = get_todo_texts(device)

        # Cleanup
        step_reporter.step("cleanup_a", lambda: delete_todo_by_text(device, text_a))
        time.sleep(0.6)
        step_reporter.step("cleanup_b", lambda: delete_todo_by_text(device, text_b))

    @pytest.mark.skip(reason="Edit todo text not implemented in app")
    def test_edit_text_not_implemented(self, device, step_reporter):
        """Placeholder: todo text editing is not yet available."""
        pass


# ---------------------------------------------------------------------------
# Toggle tests
# ---------------------------------------------------------------------------

class TestTodoToggle:
    """Focused tests for toggling todo items."""

    @pytest.fixture(autouse=True)
    def _setup_and_cleanup(self, device):
        """Create a test todo before and clean up after."""
        self._todo_text = make_todo_text("toggle")
        navigate_to_todo_primary(device)
        create_todo(device, self._todo_text)
        time.sleep(1)
        yield
        navigate_to_todo_primary(device)
        cleanup_test_todos(device, TEST_TODO_PREFIX)

    def test_toggle_via_checkbox(self, device, step_reporter):
        """Toggle by tapping the CheckBox widget."""
        step_reporter.step("navigate",
                           lambda: navigate_to_todo_primary(device))

        initial_state = is_todo_completed(device, self._todo_text)

        step_reporter.step("toggle_checkbox",
                           lambda: toggle_todo_by_text(device, self._todo_text))
        time.sleep(1)

        step_reporter.step("verify_toggled",
                           lambda: assert_todo_completed(
                               device, self._todo_text, not initial_state))

    def test_toggle_twice_restores_state(self, device, step_reporter):
        """Toggle twice -- should return to original state."""
        step_reporter.step("navigate",
                           lambda: navigate_to_todo_primary(device))

        initial_state = is_todo_completed(device, self._todo_text)
        step_reporter.screenshot("initial")

        step_reporter.step("toggle_first",
                           lambda: toggle_todo_by_text(device, self._todo_text))
        time.sleep(1)
        step_reporter.screenshot("after_first_toggle")

        step_reporter.step("toggle_second",
                           lambda: toggle_todo_by_text(device, self._todo_text))
        time.sleep(1)

        step_reporter.step("verify_restored",
                           lambda: assert_todo_completed(
                               device, self._todo_text, initial_state))


# ---------------------------------------------------------------------------
# Delete tests
# ---------------------------------------------------------------------------

class TestTodoDelete:
    """Focused tests for deleting todo items."""

    def test_delete_removes_item(self, device, step_reporter):
        """Create an item, delete it, verify it's gone."""
        todo_text = make_todo_text("delete")

        step_reporter.step("navigate",
                           lambda: navigate_to_todo_primary(device))

        step_reporter.step("create",
                           lambda: create_todo(device, todo_text))
        time.sleep(1)

        step_reporter.step("verify_exists",
                           lambda: assert_todo_exists(device, todo_text))

        count_before = get_todo_count(device)

        step_reporter.step("delete",
                           lambda: delete_todo_by_text(device, todo_text))
        time.sleep(1)

        step_reporter.step("verify_gone",
                           lambda: assert_todo_not_exists(device, todo_text))

        count_after = get_todo_count(device)
        assert count_after == count_before - 1, (
            f"Expected {count_before - 1} items after delete, got {count_after}"
        )
