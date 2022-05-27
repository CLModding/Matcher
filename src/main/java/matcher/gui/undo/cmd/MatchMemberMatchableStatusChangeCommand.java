package matcher.gui.undo.cmd;

import matcher.gui.undo.UndoRedoCommand;
import matcher.type.Matchable;

public record MatchMemberMatchableStatusChangeCommand(Matchable<?> element, boolean oldValue, boolean newValue) implements UndoRedoCommand {

    public MatchMemberMatchableStatusChangeCommand(Matchable<?> element, boolean newValue) {
        this(element, element.isMatchable(), newValue);
    }

    @Override
    public void undo() {
        element.setMatchable(!newValue);
    }

    @Override
    public void redo() {
        element.setMatchable(newValue);
    }
}
