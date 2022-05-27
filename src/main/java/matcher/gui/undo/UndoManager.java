package matcher.gui.undo;

import java.util.List;
import java.util.Stack;
import matcher.gui.undo.cmd.GroupUndoRedoCommand;

public enum UndoManager implements AutoCloseable {
    INSTANCE;

    private static final int MAX_UNDO_SIZE = 100;

    private final Stack<UndoRedoCommand> undoStack = new SizedStack<>(MAX_UNDO_SIZE);
    private final Stack<UndoRedoCommand> redoStack = new SizedStack<>(MAX_UNDO_SIZE);

    private Stack<List<UndoRedoCommand>> temporaryGrouppedCommands = new Stack<>();

    private boolean isActive = false;

    public void add(UndoRedoCommand command) {
        if (isActive) return;
        if (!temporaryGrouppedCommands.isEmpty()) {
            temporaryGrouppedCommands.peek().add(command);
            return;
        }
        undoStack.push(command);
        redoStack.clear();
    }

    public void undo() {
        isActive = true;
        try {
            if (!undoStack.isEmpty()) {
                UndoRedoCommand command = undoStack.pop();
                System.out.println("Running undo command: " + command);
                command.undo();
                redoStack.push(command);
            }
        } finally {
            isActive = false;
        }
    }

    public void redo() {
        isActive = true;
        try {
            if (!redoStack.isEmpty()) {
                UndoRedoCommand command = redoStack.pop();
                System.out.println("Running redo command: " + command);
                command.redo();
                undoStack.push(command);
            }
        } finally {
            isActive = false;
        }
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }

    private void startGrouping() {
        temporaryGrouppedCommands.push(new Stack<>());
    }

    private void endGrouping() {
        if (temporaryGrouppedCommands.isEmpty()) return;
        List<UndoRedoCommand> commands = temporaryGrouppedCommands.pop();
        if (commands.isEmpty()) return;

        if (commands.size() == 1) {
            undoStack.push(commands.get(0));
        } else {
            add(new GroupUndoRedoCommand(commands));
        }
    }

    public UndoManager group() {
        startGrouping();
        return this;
    }

    @Override
    public void close() {
        endGrouping();
    }
}
