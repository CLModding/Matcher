package matcher.gui.undo;

import java.util.List;
import java.util.Stack;
import matcher.gui.undo.cmd.GroupUndoRedoCommand;
import matcher.gui.undo.cmd.MatchMemberActionCommand;
import matcher.type.MethodVarInstance;

public enum UndoManager implements AutoCloseable {
    INSTANCE;

    private static final int MAX_UNDO_SIZE = 100;

    private final Stack<UndoRedoCommand> undoStack = new SizedStack<>(MAX_UNDO_SIZE);
    private final Stack<UndoRedoCommand> redoStack = new SizedStack<>(MAX_UNDO_SIZE);

    private final Stack<List<UndoRedoCommand>> temporaryGrouppedCommands = new Stack<>();

    private boolean isActive = false;

    public void add(UndoRedoCommand command) {
        if (isActive) return;
        if (!temporaryGrouppedCommands.isEmpty()) {
            temporaryGrouppedCommands.peek().add(command);
            return;
        }
        undoStack.push(cleanupCommand(command));
        redoStack.clear();
    }

    private UndoRedoCommand cleanupCommand(UndoRedoCommand command) {
        if (command instanceof GroupUndoRedoCommand group) {
            // Recursively flatten the group command by accessing the nested commands
            List<UndoRedoCommand> finalCommands = new Stack<>();
            for (UndoRedoCommand nestedCommand : group.commands()) {
                finalCommands.add(cleanupCommand(nestedCommand));
            }

            // Place method vars at the end of the list
            var methodVars = finalCommands.stream()
                    .filter(c -> c instanceof MatchMemberActionCommand memberMatch && memberMatch.a() instanceof MethodVarInstance)
                    .toList();

            // Remove all of these commands whereever they are
            finalCommands.removeAll(methodVars);
            // Finally, add them back in at the end
            finalCommands.addAll(methodVars);

            // Create a new group command with the flattened commands
            return new GroupUndoRedoCommand(finalCommands);
        }
        return command;
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
            add(commands.get(0));
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
