package matcher.gui.menu;

import java.util.function.BiConsumer;
import java.util.prefs.Preferences;
import javafx.beans.property.BooleanProperty;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import matcher.config.Config;

public class UserPrefSaveableMenu extends Menu {
    public UserPrefSaveableMenu(String text) {
        super(text);
    }

    // Functional interface for handling boolean menu items that are saved in the user preferences.
    @FunctionalInterface
    interface BooleanActionMenuHandler {
        void handle(MenuItem menu, Preferences prefs, BooleanProperty property);

    }

    private Preferences getParentMenuPrefs() {
        return Config.getUserPreferencesNode().node("menus").node(getClass().getSimpleName());
    }

    protected void loadUserPrefs() {
        loadMenu(this, null);
    }

    protected void saveUserPrefs() {
        saveMenu(this, null);
        Config.saveAsLast();
    }

    private void handleMenuItems(Menu menu, Preferences parentPrefs, BooleanActionMenuHandler booleanPropertyHandler, BiConsumer<Menu, Preferences> recurseAction) {
        Preferences prefs = parentPrefs != null ? parentPrefs.node(menu.getText()) : getParentMenuPrefs();
        ObservableList<MenuItem> items = menu.getItems();
        for (MenuItem item : items) {
            BooleanProperty selectedProperty = getSelectedProperty(item);
            if (selectedProperty != null) {
                booleanPropertyHandler.handle(item, prefs, selectedProperty);
            } else if (item instanceof Menu) {
                recurseAction.accept((Menu) item, prefs);
            }
        }
    }

    private void loadMenu(Menu menu, Preferences parentPrefs) {
        handleMenuItems(menu, parentPrefs, (m, p, prop) -> prop.set(p.getBoolean(m.getText(), false)), this::loadMenu);
    }

    private void saveMenu(Menu menu, Preferences parentPrefs) {
        handleMenuItems(menu, parentPrefs, (m, p, prop) -> p.putBoolean(m.getText(), prop.get()), this::saveMenu);
    }

    private BooleanProperty getSelectedProperty(MenuItem item) {
        if (item instanceof Toggle) {
            return ((Toggle) item).selectedProperty();
        } else if (item instanceof CheckMenuItem) {
            return ((CheckMenuItem) item).selectedProperty();
        }
        return null;
    }
}
