package com.lx862.mtrmap.integration.journeymap;

import journeymap.client.api.event.fabric.FabricEvents;

final class JourneyMapEventBridge {
    static void register() {
        FabricEvents.ADDON_BUTTON_DISPLAY_EVENT.register(event ->
                JourneyMapToolbar.addButtons(event.getThemeButtonDisplay()));
    }
}
