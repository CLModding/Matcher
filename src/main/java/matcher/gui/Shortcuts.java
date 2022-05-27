package matcher.gui;

import java.util.EnumSet;
import javafx.event.ActionEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import java.util.Map;
import matcher.gui.undo.UndoManager;
import matcher.type.MatchType;

class Shortcuts {
	public static void init(Gui gui) {
		Map<KeyCombination, Runnable> accelerators = gui.getScene().getAccelerators();

		// M - match
		accelerators.put(new KeyCodeCombination(KeyCode.M), () -> gui.getBottomPane().getMatchButton().fireEvent(new ActionEvent()));
		// U - unmatch
		accelerators.put(new KeyCodeCombination(KeyCode.U), () -> {
			if (!gui.getBottomPane().getUnmatchVarButton().isDisable()) {
				gui.getBottomPane().getUnmatchVarButton().fireEvent(new ActionEvent());
			} else if (!gui.getBottomPane().getUnmatchMemberButton().isDisable()) {
				gui.getBottomPane().getUnmatchMemberButton().fireEvent(new ActionEvent());
			} else if (!gui.getBottomPane().getUnmatchClassButton().isDisable()) {
				gui.getBottomPane().getUnmatchClassButton().fireEvent(new ActionEvent());
			}
		});
		// I - ignore (toggle matchable)
		accelerators.put(new KeyCodeCombination(KeyCode.I), () -> gui.getBottomPane().getMatchableButton().fireEvent(new ActionEvent()));
		// A - match 100%
		accelerators.put(new KeyCodeCombination(KeyCode.A), () -> gui.getBottomPane().getMatchPerfectMembersButton().fireEvent(new ActionEvent()));

		// CTRL + Z - undo
		accelerators.put(new KeyCodeCombination(KeyCode.Z, KeyCombination.CONTROL_DOWN), () -> {
			if (UndoManager.INSTANCE.canUndo()) {
				UndoManager.INSTANCE.undo();
				gui.onMatchChange(EnumSet.allOf(MatchType.class));
			}
		});

		// CTRL + SHIFT + Z - redo
		accelerators.put(new KeyCodeCombination(KeyCode.Z, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN), () -> {
			if (UndoManager.INSTANCE.canRedo()) {
				UndoManager.INSTANCE.redo();
				gui.onMatchChange(EnumSet.allOf(MatchType.class));
			}
		});
	}
}
