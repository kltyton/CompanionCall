package com.kltyton.companioncall.client.state;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.client.input.ClientAccess;
import com.kltyton.companioncall.client.preview.CompanionPreview;
import com.kltyton.companioncall.client.screen.CompanionSelector;
import com.kltyton.companioncall.config.client.ClientOptions;
import com.kltyton.companioncall.storage.pet.PetRecord;
import com.kltyton.companioncall.network.payload.ClientRequest;
import com.kltyton.companioncall.network.payload.ServerSnapshot;
import io.github.kltyton.kltytonui.KltytonUI;
import io.github.kltyton.kltytonui.init.Document;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;

public final class CompanionClient {
    public static final String HUD = "companion_call/hud.html";
    private static final Gson GSON = new Gson();
    private static final String[] TEXTS = {"title", "choose", "release", "cancel", "empty", "empty_help",
            "health", "type", "dimension", "position", "recorded", "sitting", "standing", "movement",
            "speed", "jump", "land", "air", "water", "current", "selected", "preview_unavailable"};
    private static final String[] MANAGEMENT_TEXTS = {"manage", "active", "blacklist", "restore", "remove",
            "add_ridden", "current_ridden", "not_riding", "close", "remove_help", "blacklist_empty", "unavailable", "set_selected"};
    private static boolean initialized;
    private static ClientSnapshot snapshot = ClientSnapshot.awaiting();
    private static boolean pressed;
    private static boolean longPress;
    private static boolean ignoreUntilRelease;
    private static boolean mouseWasGrabbed;
    private static long pressedAt;
    private static CompanionSelector selector;
    private static Document hud;
    private static Object connection;
    private static boolean syncError;
    private static boolean managementOpen;
    private static boolean previewsEnabled = true;
    private static ClientSnapshot documentSnapshot;
    private static Language documentLanguage;
    private static String documentKey;
    private static String documentAddKey;
    private static String documentJson;
    private static boolean documentPreviews;
    private static boolean documentManagement;
    private static boolean documentSyncError;

    private CompanionClient() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        CompanionPreview.register();
        ServerSnapshot.installReceiver(CompanionClient::receive);
    }

    private static void receive(String json) {
        try {
            var updated = GSON.fromJson(json, ClientSnapshot.class);
            if (updated == null) throw new IllegalArgumentException("Empty companion state");
            snapshot = updated;
            refreshPreviews();
            syncError = false;
        } catch (JsonParseException | IllegalArgumentException error) {
            syncError = true;
            cancelGesture();
            ignoreUntilRelease = ClientAccess.down();
            CompanionCall.LOGGER.error("Rejected invalid companion state from server", error);
        }
    }

    public static void tick() {
        if (!initialized) return;
        int clicks = 0;
        while (ClientAccess.KEY.consumeClick()) clicks++;
        int addClicks = 0;
        while (ClientAccess.ADD_RIDDEN.consumeClick()) addClicks++;
        boolean down = ClientAccess.down();
        if (!ClientAccess.ready()) {
            cancelGesture();
            if (hud != null) { hud.remove(); hud = null; }
            connection = null;
            snapshot = ClientSnapshot.awaiting();
            documentSnapshot = null;
            documentLanguage = null;
            documentJson = null;
            CompanionPreview.clear();
            ignoreUntilRelease = down;
            return;
        }
        if (connection != ClientAccess.connection()) {
            cancelGesture();
            CompanionPreview.clear();
            connection = ClientAccess.connection();
            snapshot = ClientSnapshot.awaiting();
            new ClientRequest("refresh", "").sendToServer();
        }
        var options = ClientOptions.get();
        if (options.entityPreviews() != previewsEnabled) {
            previewsEnabled = options.entityPreviews();
            refreshPreviews();
        }
        if (selector != null && (!options.selectorEnabled() || managementOpen && !options.managementEnabled())) {
            cancelGesture();
            ignoreUntilRelease = down;
        }
        if (addClicks > 0 && options.riddenShortcutEnabled() && ClientAccess.focused() && ClientAccess.screen() == null) {
            new ClientRequest("add_ridden", "").sendToServer();
        }
        updateDocuments();
        if (!ClientAccess.focused()) {
            cancelGesture();
            ignoreUntilRelease = down;
            return;
        }
        if (selector != null && ClientAccess.screen() != selector) {
            selector = null;
            managementOpen = false;
            CompanionPreview.prune();
            pressed = false;
            longPress = false;
            ignoreUntilRelease = down;
            ClientAccess.restoreMouse(mouseWasGrabbed);
        }
        if (ClientAccess.screen() != null && ClientAccess.screen() != selector) {
            pressed = false;
            longPress = false;
            ignoreUntilRelease = down;
            return;
        }
        if (selector != null && managementOpen) {
            pressed = false;
            longPress = false;
            ignoreUntilRelease = down;
            return;
        }
        if (ignoreUntilRelease) {
            if (!down) ignoreUntilRelease = false;
            return;
        }
        long now = System.nanoTime();
        if (!down && !pressed && clicks > 0) {
            new ClientRequest("recall", "").sendToServer();
        }
        if (down && !pressed) {
            pressed = true;
            longPress = false;
            pressedAt = now;
        }
        long heldMillis = (now - pressedAt) / 1_000_000L;
        if (options.selectorEnabled() && down && pressed && !longPress && heldMillis >= snapshot.holdMillis()) {
            longPress = true;
            mouseWasGrabbed = ClientAccess.grabbed();
            selector = new CompanionSelector();
            refreshPreviews();
            ClientAccess.screen(selector);
            updateDocuments();
            new ClientRequest("refresh", "").sendToServer();
        }
        if (!down && pressed) {
            pressed = false;
            if (longPress) {
                if (selector != null && ClientAccess.screen() == selector) confirmSelection();
                closeSelector();
            } else if (heldMillis < snapshot.holdMillis() || !options.selectorEnabled()) {
                new ClientRequest("recall", "").sendToServer();
            }
            longPress = false;
        }
    }

    private static void confirmSelection() {
        for (var document : KltytonUI.getDocument(CompanionSelector.PATH)) {
            var candidate = document.getElementById("candidate");
            if (candidate == null) continue;
            String value = candidate.getAttribute("data-pet");
            if (value == null || value.isEmpty()) continue;
            UUID id = UUID.fromString(value);
            if (snapshot.pets().stream().anyMatch(pet -> id.equals(pet.id()))) {
                new ClientRequest("select", value).sendToServer();
                return;
            }
        }
    }

    private static void closeSelector() {
        if (selector != null && ClientAccess.screen() == selector) ClientAccess.screen(null);
        if (selector != null) ClientAccess.restoreMouse(mouseWasGrabbed);
        selector = null;
        managementOpen = false;
        CompanionPreview.prune();
    }

    private static void cancelGesture() {
        closeSelector();
        pressed = false;
        longPress = false;
    }

    private static void updateDocuments() {
        var options = ClientOptions.get();
        if (!options.hudEnabled() && hud != null) { hud.remove(); hud = null; }
        if (options.hudEnabled() && hud == null) {
            hud = KltytonUI.createDocument(HUD);
            if (hud == null) throw new IllegalStateException("Missing companion HUD resource: " + HUD);
        }
        if (hud == null && selector == null) return;
        var root = hud == null ? null : hud.getElementById("hud");
        String hudStyle = ClientAccess.screen() == null
                ? "left:" + options.hudLeft() + "px;bottom:" + options.hudBottom() + "px" : "display:none";
        if (root != null && !hudStyle.equals(root.getAttribute("style"))) root.setAttribute("style", hudStyle);
        String keyName = ClientAccess.KEY.getTranslatedKeyMessage().getString();
        String addKeyName = ClientAccess.ADD_RIDDEN.getTranslatedKeyMessage().getString();
        Language language = Language.getInstance();
        if (documentJson == null || snapshot != documentSnapshot || language != documentLanguage
                || options.entityPreviews() != documentPreviews || options.managementEnabled() != documentManagement
                || syncError != documentSyncError || !Objects.equals(keyName, documentKey)
                || !Objects.equals(addKeyName, documentAddKey)) {
            documentJson = serializeDocument(options, keyName, addKeyName);
            documentSnapshot = snapshot;
            documentLanguage = language;
            documentPreviews = options.entityPreviews();
            documentManagement = options.managementEnabled();
            documentSyncError = syncError;
            documentKey = keyName;
            documentAddKey = addKeyName;
        }
        if (hud != null) writeDocument(hud, documentJson);
        if (selector != null) {
            for (var document : KltytonUI.getDocument(CompanionSelector.PATH)) {
                writeDocument(document, documentJson);
                bindCommands(document);
            }
        }
    }

    private static String serializeDocument(ClientOptions.Values options, String keyName, String addKeyName) {
        var data = new LinkedHashMap<String, Object>();
        data.put("pets", snapshot.pets());
        data.put("blocked", snapshot.blocked());
        data.put("ridden", snapshot.ridden());
        data.put("previewsEnabled", options.entityPreviews());
        data.put("managementEnabled", options.managementEnabled());
        data.put("selected", snapshot.selected());
        data.put("loaded", snapshot.loaded());
        var records = allRecords();
        var typeNames = new LinkedHashMap<String, String>();
        for (var pet : records) {
            String key = "entity." + pet.type().replace(':', '.');
            String translated = I18n.get(key);
            typeNames.put(pet.id().toString(), translated.equals(key) ? pet.type() : translated);
        }
        data.put("typeNames", typeNames);
        var dimensionNames = new LinkedHashMap<String, String>();
        for (var pet : records) {
            String key = "dimension." + pet.dimension().replace(':', '.');
            String translated = I18n.get(key);
            dimensionNames.put(pet.id().toString(), translated.equals(key) ? pet.dimension() : translated);
        }
        data.put("dimensionNames", dimensionNames);
        String status = syncError ? "sync_failed" : snapshot.status();
        data.put("status", I18n.get("companion_call.status." + status));
        var texts = new LinkedHashMap<String, String>();
        for (String key : TEXTS) {
            texts.put(key, "release".equals(key) ? I18n.get("companion_call.ui." + key, keyName)
                    : I18n.get("companion_call.ui." + key));
        }
        texts.put("key", keyName);
        for (String key : MANAGEMENT_TEXTS) texts.put(key, I18n.get("companion_call.ui." + key));
        texts.put("add_key_help", I18n.get("companion_call.ui.add_key_help", addKeyName));
        data.put("texts", texts);
        return GSON.toJson(data);
    }

    private static List<PetRecord> allRecords() {
        var records = new LinkedHashMap<UUID, PetRecord>();
        for (var pet : snapshot.pets()) records.put(pet.id(), pet);
        for (var pet : snapshot.blocked()) records.put(pet.id(), pet);
        if (snapshot.ridden() != null) records.put(snapshot.ridden().id(), snapshot.ridden());
        return List.copyOf(records.values());
    }

    private static void refreshPreviews() {
        if (ClientOptions.get().entityPreviews()) CompanionPreview.update(allRecords());
        else CompanionPreview.clear();
    }

    private static void bindCommands(Document document) {
        var commands = document.getElementById("commands");
        if (commands == null || "true".equals(commands.getAttribute("data-client-bound"))) return;
        commands.setAttribute("data-client-bound", "true");
        commands.addEventListener("companion-command", event -> {
            String action = commands.getAttribute("data-action");
            String id = commands.getAttribute("data-id");
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                if (selector == null || !ClientAccess.ready() || !ClientOptions.get().managementEnabled()) return;
                if ("manage".equals(action)) {
                    managementOpen = true;
                    new ClientRequest("refresh", "").sendToServer();
                } else if ("close".equals(action)) closeSelector();
                else if ("blacklist".equals(action) || "restore".equals(action) || "select".equals(action) || "add_ridden".equals(action)) {
                    new ClientRequest(action, id == null ? "" : id).sendToServer();
                }
            });
        });
    }

    private static void writeDocument(Document document, String json) {
        var target = document.getElementById("state");
        if (target != null && !json.equals(target.getTextContent())) target.setTextContent(json);
    }
}
