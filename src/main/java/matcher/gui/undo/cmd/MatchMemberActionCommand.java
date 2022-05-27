package matcher.gui.undo.cmd;

import java.util.function.BiConsumer;
import matcher.Matcher;
import matcher.gui.undo.UndoRedoCommand;
import matcher.type.Matchable;

public record MatchMemberActionCommand(Matcher matcher, Action action, Matchable<?> a, Matchable<?> b) implements UndoRedoCommand {

    @Override
    public void undo() {
        var method = action.getOpposite().getMethod(matcher);
        method.accept(a, b);
    }

    @Override
    public void redo() {
        var method = action.getMethod(matcher);
        method.accept(a, b);
    }

    public enum Action {
        MATCH, UNMATCH;

        public Action getOpposite() {
            return this == MATCH ? UNMATCH : MATCH;
        }

        public BiConsumer<Matchable<?>, Matchable<?>> getMethod(Matcher matcher) {
            if (this == MATCH) {
                return matcher::match;
            } else if (this == UNMATCH) {
                return matcher::unmatch;
            } else {
                throw new IllegalArgumentException("Unknown action: " + this);
            }
        }
    }



}
