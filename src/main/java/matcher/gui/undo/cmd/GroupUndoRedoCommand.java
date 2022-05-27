package matcher.gui.undo.cmd;

import java.util.List;
import matcher.gui.undo.UndoRedoCommand;

public record GroupUndoRedoCommand(List<UndoRedoCommand> commands) implements UndoRedoCommand {

    @Override
    public void undo() {
        for (var command : commands) command.undo();
    }

    @Override
    public void redo() {
        for (var command : commands) command.redo();
    }
}
