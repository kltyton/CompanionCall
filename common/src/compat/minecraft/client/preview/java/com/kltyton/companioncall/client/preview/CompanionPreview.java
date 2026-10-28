package com.kltyton.companioncall.client.preview;

import com.kltyton.companioncall.storage.pet.PetRecord;
import io.github.kltyton.kltytonui.init.Element;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

public final class CompanionPreview {
    public static final String TAG_NAME = "companion-preview";
    private static final Set<CompanionPreviewElement> ELEMENTS =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static Map<UUID, PetRecord> pets = Map.of();
    private static int version;
    private static boolean registered;

    private CompanionPreview() {}

    public static void register() {
        if (registered) return;
        registered = true;
        Element.register(TAG_NAME, (document, tagName) -> new CompanionPreviewElement(document));
    }

    public static void update(List<PetRecord> records) {
        LinkedHashMap<UUID, PetRecord> next = new LinkedHashMap<>();
        for (PetRecord pet : records) next.put(pet.id(), pet);
        pets = Map.copyOf(next);
        version++;
        prune();
    }

    public static void clear() {
        for (CompanionPreviewElement element : new ArrayList<>(ELEMENTS)) element.release();
        ELEMENTS.clear();
        pets = Map.of();
        version++;
    }

    static PetRecord pet(UUID id) {
        return id == null ? null : pets.get(id);
    }

    static int version() {
        return version;
    }

    static void track(CompanionPreviewElement element) {
        ELEMENTS.add(element);
    }

    static void untrack(CompanionPreviewElement element) {
        ELEMENTS.remove(element);
    }

    public static void prune() {
        for (CompanionPreviewElement element : new ArrayList<>(ELEMENTS)) {
            if (element.detached()) element.release();
        }
    }
}
